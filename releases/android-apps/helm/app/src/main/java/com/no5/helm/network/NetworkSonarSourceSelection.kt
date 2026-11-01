package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.lumina.control.commands.v1.CommandRing

/** Source selection shares the existing SONAR worker; admission never performs HTTP. */
internal class NetworkSonarSourceSelection(private val identity: SourceIdentity, private val http: PiHttpTransport,
    private val now: () -> Long) {
    private val lock = Any()
    private val ring = CommandRing()
    private val work = CommandRing.Entry()
    private val read = CommandRing.Entry()
    private var origin: SonarV1Origin? = null
    private var available = false
    private var epoch = 0L
    private var lastRequest = -1L
    private var latestId = 0L
    private var flight = 0L
    private var gatewayId = 0L
    private var flightOrigin: SonarV1Origin? = null
    private var deadline = 0L
    private var nextRead = 0L
    private var closed = false
    private val stateFeed = MutableSourceFeed(SonarSourceSelectionState(identity))
    private val outcomeFeed = MutableSourceFeed<SonarSourceSelectionOutcome?>(null)
    val state: SourceFeed<SonarSourceSelectionState> get() = stateFeed
    val outcomes: SourceFeed<SonarSourceSelectionOutcome?> get() = outcomeFeed
    private var nextState = stateFeed.value

    fun observe(value: SonarV1State?) = synchronized(lock) {
        val next = value?.origin
        if (next != origin) {
            epoch++
            ring.cancelPending(CommandRing.Reason.SOURCE_CHANGED, now())
            // A successful source command itself advances the gateway generation. Its receipt
            // remains bound to the original accepted generation and must still be read once.
            val receiptStillOwned = flight != 0L && next != null && origin != null &&
                next.serverSessionId == origin!!.serverSessionId && next.source == origin!!.source &&
                next.simulated == origin!!.simulated && next.generation > origin!!.generation
            if (!receiptStillOwned) {
                if (flight != 0L) ring.finish(flight, CommandRing.State.UNKNOWN, CommandRing.Reason.COMPLETION_UNKNOWN, -1, now())
                flight = 0; gatewayId = 0; flightOrigin = null
            }
            origin = next; nextRead = 0
            nextState = SonarSourceSelectionState(identity, epoch)
        }
        available = !closed && value?.sourceSelection == true
        nextState = nextState.copy(available = available, selection = value?.displaySelection ?: nextState.selection)
    }
    fun submit(request: SonarSourceSelectionRequest): SonarSourceSelectionOutcome = synchronized(lock) {
        if (closed || request.sessionId != identity.sessionId || request.requestId <= lastRequest || !available)
            return SonarSourceSelectionOutcome(identity.sessionId, request.requestId, null, request.source,
                SonarFilterCommandStatus.REJECTED, when { closed -> "CLOSED"; request.sessionId != identity.sessionId -> "WRONG_SESSION"; !available -> "UNSUPPORTED"; else -> "STALE_REQUEST" })
        lastRequest = request.requestId
        latestId = ring.submit(0, request.source.ordinal, request.requestId, epoch, 0, 0.0, 0.0, 0.0, 0.0, now())
        result(latestId)!!
    }
    fun result(id: Long): SonarSourceSelectionOutcome? = synchronized(lock) {
        if (!ring.read(id, read)) return null
        SonarSourceSelectionOutcome(identity.sessionId, read.sequence, id, SonarPingSource.entries[read.kind],
            if (read.state == CommandRing.State.CANCELLED && read.reason == CommandRing.Reason.NEWER_TARGET)
                SonarFilterCommandStatus.SUPERSEDED else SonarFilterCommandStatus.valueOf(read.state.name), read.reason.name)
    }
    fun pump() {
        val captured = synchronized(lock) {
            if (closed || !available && flight == 0L) return
            if (flight == 0L && ring.take(work)) {
                if (work.generation != epoch) { ring.finish(work.id, CommandRing.State.CANCELLED, CommandRing.Reason.SOURCE_CHANGED, -1, now()); return }
                flight = work.id; gatewayId = 0; flightOrigin = origin; deadline = now() + 5000; ring.beginEffect(flight)
            }
            Capture((if (flight == 0L) origin else flightOrigin) ?: return, epoch, flight, gatewayId, work.sequence, work.kind)
        }
        try {
            if (captured.flight != 0L) {
                val body = if (captured.gatewayId == 0L) {
                    val request = JSONObject().put("version", 1).put("serverSessionId", captured.origin.serverSessionId)
                        .put("generation", captured.origin.generation).put("clientId", identity.sessionId)
                        .put("requestId", captured.requestId).put("source", SonarPingSource.entries[captured.kind].name.lowercase()).toString()
                    http.put("/v1/sonar/source", request)
                } else http.get("/v1/sonar/commands/${captured.gatewayId}")
                val payload = boundedJson(body)
                require(payload.get("version") == 1 && payload.getString("kind") == "sonar-command-result")
                require(payload.getString("clientId") == identity.sessionId && payload.getLong("requestId") == captured.requestId)
                require(payload.getString("serverSessionId") == captured.origin.serverSessionId && payload.getLong("generation") == captured.origin.generation)
                val status = CommandRing.State.valueOf(payload.getString("status"))
                val returnedId = payload.getLong("commandId").also { require(it > 0 && (captured.gatewayId == 0L || captured.gatewayId == it)) }
                synchronized(lock) {
                    if (closed || flight != captured.flight || flightOrigin != captured.origin) return
                    gatewayId = returnedId
                    if (status != CommandRing.State.ACCEPTED && status != CommandRing.State.RUNNING) {
                        if (status == CommandRing.State.APPLIED) require(SonarV1Decoder.optionalPingSource(payload, "confirmedSource")?.ordinal == captured.kind)
                        // The authoritative WS state/GET readback owns selection and its generation.
                        ring.finish(flight, if (status == CommandRing.State.SUPERSEDED) CommandRing.State.CANCELLED else status,
                            when (status) { CommandRing.State.APPLIED -> CommandRing.Reason.NONE; CommandRing.State.SUPERSEDED -> CommandRing.Reason.NEWER_TARGET; else -> CommandRing.Reason.TRANSPORT }, gatewayId, now())
                        flight = 0; flightOrigin = null; nextRead = 0
                    } else if (now() >= deadline) {
                        ring.finish(flight, CommandRing.State.UNKNOWN, CommandRing.Reason.COMPLETION_UNKNOWN, gatewayId, now()); flight = 0; flightOrigin = null
                    }
                }
            } else if (now() >= nextRead) {
                nextRead = now() + 1000
                val payload = boundedJson(http.get("/v1/sonar/source"))
                require(payload.get("version") == 1 && payload.getString("kind") == "sonar-source")
                require(payload.getString("serverSessionId") == captured.origin.serverSessionId && payload.getLong("generation") == captured.origin.generation)
                val selection = SonarV1Decoder.displaySelection(payload)
                synchronized(lock) {
                    if (!closed && captured.epoch == epoch && (selection == null || nextState.selection == null ||
                            selection.generation >= nextState.selection!!.generation)) nextState = nextState.copy(selection = selection)
                }
            }
        } catch (_: Exception) {
            synchronized(lock) {
                if (!closed && flight == captured.flight && flightOrigin == captured.origin && flight != 0L) {
                    ring.finish(flight, CommandRing.State.UNKNOWN, CommandRing.Reason.COMPLETION_UNKNOWN, gatewayId, now()); flight = 0; flightOrigin = null
                }
            }
        }
    }
    private data class Capture(val origin: SonarV1Origin, val epoch: Long, val flight: Long, val gatewayId: Long, val requestId: Long, val kind: Int)
    private fun boundedJson(body: String): JSONObject { require(body.toByteArray(Charsets.UTF_8).size <= 16_384); StrictJsonSyntax.validate(body); return JSONObject(body) }
    fun tick() = synchronized(lock) {
        if (!closed) {
            if (stateFeed.value != nextState) stateFeed.emit(nextState)
            result(latestId)?.let { if (outcomeFeed.value != it) outcomeFeed.emit(it) }
        }
    }
    fun close() = synchronized(lock) {
        closed = true; epoch++; ring.cancelPending(CommandRing.Reason.CLOSED, now())
        if (flight != 0L) ring.finish(flight, CommandRing.State.UNKNOWN, CommandRing.Reason.COMPLETION_UNKNOWN, gatewayId, now())
        flight = 0; flightOrigin = null
        nextState = nextState.copy(available = false)
        try { stateFeed.emit(nextState) } catch (_: RuntimeException) { }
        stateFeed.close(); outcomeFeed.close()
    }
}
