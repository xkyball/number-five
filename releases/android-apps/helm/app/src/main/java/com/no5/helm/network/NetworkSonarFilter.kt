package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.lumina.control.commands.v1.CommandRing

/** The existing source worker owns HTTP; UI admission only touches primitive rings. */
internal class NetworkSonarFilter(private val identity: SourceIdentity, private val http: PiHttpTransport,
    private val now: () -> Long) {
    private val lock = Any()
    private val ring = CommandRing()
    private val work = CommandRing.Entry()
    private val read = CommandRing.Entry()
    private var origin: SonarV1Origin? = null
    private var available = false
    private var epoch = 0L
    private var lastRequest = 0L
    private var latestId = 0L
    private var flight = 0L
    private var gatewayId = 0L
    private var deadline = 0L
    private var nextRead = 0L
    private var closed = false
    private val stateFeed = MutableSourceFeed(SonarFilterState(identity))
    private val outcomeFeed = MutableSourceFeed<SonarFilterOutcome?>(null)
    val state: SourceFeed<SonarFilterState> get() = stateFeed
    val outcomes: SourceFeed<SonarFilterOutcome?> get() = outcomeFeed
    private var nextState = stateFeed.value
    fun observe(value: SonarV1State?) = synchronized(lock) {
        val next = value?.origin
        if (next != origin) {
            epoch++
            ring.cancelPending(CommandRing.Reason.SOURCE_CHANGED, now())
            if (flight != 0L) ring.finish(flight, CommandRing.State.UNKNOWN, CommandRing.Reason.COMPLETION_UNKNOWN, -1, now())
            flight = 0; origin = next
            nextState = SonarFilterState(identity, epoch)
        }
        available = !closed && value?.filterControl == true
        nextState = nextState.copy(available = available)
    }
    fun submit(request: SonarFilterRequest): SonarFilterOutcome = synchronized(lock) {
        if (closed || request.sessionId != identity.sessionId || request.requestId <= lastRequest || !available)
            return SonarFilterOutcome(identity.sessionId, request.requestId, null, request.mode, SonarFilterCommandStatus.REJECTED,
                if (closed) "CLOSED" else if (!available) "UNSUPPORTED" else "STALE_REQUEST")
        lastRequest = request.requestId
        latestId = ring.submit(0, request.mode.ordinal, request.requestId, epoch, 0, 0.0, 0.0, 0.0, 0.0, now())
        result(latestId)!!
    }
    fun result(id: Long): SonarFilterOutcome? = synchronized(lock) {
        if (!ring.read(id, read)) return null
        SonarFilterOutcome(identity.sessionId, read.sequence, id, SonarFilterMode.entries[read.kind],
            if (read.state == CommandRing.State.CANCELLED && read.reason == CommandRing.Reason.NEWER_TARGET)
                SonarFilterCommandStatus.SUPERSEDED else SonarFilterCommandStatus.valueOf(read.state.name), read.reason.name)
    }
    /** Called on the source's existing serial worker, never under the admission lock. */
    fun pump() {
        val captured = synchronized(lock) {
            if (closed || !available) return
            if (flight == 0L && ring.take(work)) {
                if (work.generation != epoch) { ring.finish(work.id, CommandRing.State.CANCELLED, CommandRing.Reason.SOURCE_CHANGED, -1, now()); return }
                flight = work.id; gatewayId = 0; deadline = now() + 5000
                ring.beginEffect(flight)
            }
            Triple(origin ?: return, epoch, flight)
        }
        try {
            if (captured.third != 0L) {
                val id = synchronized(lock) { gatewayId }
                val payload = if (id == 0L) {
                    val body = JSONObject().put("version", 1).put("serverSessionId", captured.first.serverSessionId)
                        .put("generation", captured.first.generation).put("clientId", identity.sessionId)
                        .put("requestId", work.sequence).put("mode", SonarFilterMode.entries[work.kind].name.lowercase()).toString()
                    JSONObject(http.put("/v1/sonar/filter", body))
                } else JSONObject(http.get("/v1/sonar/commands/$id"))
                require(payload.getInt("version") == 1 && payload.getString("kind") == "sonar-command-result")
                require(payload.getString("clientId") == identity.sessionId && payload.getLong("requestId") == work.sequence)
                require(payload.getString("serverSessionId") == captured.first.serverSessionId && payload.getLong("generation") == captured.first.generation)
                val status = CommandRing.State.valueOf(payload.getString("status"))
                synchronized(lock) {
                    if (closed || captured.second != epoch || flight != captured.third) return
                    val returnedId = payload.getLong("commandId"); require(returnedId > 0 && (gatewayId == 0L || gatewayId == returnedId))
                    gatewayId = returnedId
                    if (status != CommandRing.State.ACCEPTED && status != CommandRing.State.RUNNING) {
                        if (status == CommandRing.State.APPLIED) {
                            val mode = SonarFilterMode.valueOf(payload.getString("confirmedMode").uppercase())
                            require(mode.ordinal == work.kind)
                            nextState = nextState.copy(mode = mode)
                        }
                        // Remote replacements remain terminal; this client never repeats an old goal.
                        ring.finish(flight, if (status == CommandRing.State.SUPERSEDED) CommandRing.State.CANCELLED else status,
                            when (status) { CommandRing.State.APPLIED -> CommandRing.Reason.NONE; CommandRing.State.SUPERSEDED -> CommandRing.Reason.NEWER_TARGET; else -> CommandRing.Reason.TRANSPORT }, gatewayId, now())
                        flight = 0L
                    } else if (now() >= deadline) {
                        ring.finish(flight, CommandRing.State.UNKNOWN, CommandRing.Reason.COMPLETION_UNKNOWN, gatewayId, now()); flight = 0L
                    }
                }
            } else if (now() >= nextRead) {
                nextRead = now() + 1000
                val payload = JSONObject(http.get("/v1/sonar/filter"))
                require(payload.getInt("version") == 1 && payload.getString("kind") == "sonar-filter")
                require(payload.getString("serverSessionId") == captured.first.serverSessionId && payload.getLong("generation") == captured.first.generation)
                val mode = SonarFilterMode.valueOf(payload.getString("mode").uppercase())
                val reason = if (!payload.has("reason") || payload.isNull("reason")) null
                    else payload.getString("reason").also { require(it.length <= 512 && it.none(Char::isISOControl)) }
                synchronized(lock) {
                    if (!closed && captured.second == epoch) nextState = nextState.copy(mode = mode,
                        effectiveMode = if (payload.isNull("effectiveMode")) null else payload.getString("effectiveMode"),
                        active = if (payload.isNull("active")) null else payload.getBoolean("active"), reason = reason)
                }
            }
        } catch (_: Exception) {
            synchronized(lock) {
                if (!closed && epoch == captured.second && flight == captured.third && flight != 0L) {
                    ring.finish(flight, CommandRing.State.UNKNOWN, CommandRing.Reason.COMPLETION_UNKNOWN, gatewayId, now()); flight = 0
                }
            }
        }
    }
    fun tick() = synchronized(lock) {
        if (!closed) {
            if (stateFeed.value != nextState) stateFeed.emit(nextState)
            result(latestId)?.let { if (outcomeFeed.value != it) outcomeFeed.emit(it) }
        }
    }
    fun close() = synchronized(lock) {
        closed = true; epoch++; ring.cancelPending(CommandRing.Reason.CLOSED, now())
        if (flight != 0L) ring.finish(flight, CommandRing.State.UNKNOWN, CommandRing.Reason.COMPLETION_UNKNOWN, gatewayId, now())
        flight = 0; nextState = nextState.copy(available = false)
        try { stateFeed.emit(nextState) } catch (_: RuntimeException) { }
        stateFeed.close(); outcomeFeed.close()
    }
}
