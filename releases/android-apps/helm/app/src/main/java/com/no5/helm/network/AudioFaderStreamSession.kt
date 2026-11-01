package com.no5.helm.network

import com.no5.domain.*
import com.no5.helm.control.HelmAudioEffectLease
import org.json.JSONObject
import kotlin.math.abs

/** Serialized by the owning source lock; callbacks never touch Compose. */
internal class AudioFaderStreamSession(
    private val lock: Any,
    private val identity: SourceIdentity,
    private val transport: AudioFaderStreamTransport,
    private val now: () -> Long,
    private val serverSession: () -> String?,
    private val revision: () -> Long,
    private val nextId: () -> String,
    private val gate: (AudioCommand.Fader) -> Boolean,
    private val accept: (AudioV1Snapshot) -> Unit,
    private val standardPending: () -> Boolean,
    private val pending: (AudioCommandOutcome, Long) -> Unit,
    private val complete: (AudioCommandOutcome) -> Unit,
    private val encodeCommand: (String, String, String, Long, AudioCommand) -> String = AudioV1Decoder::command,
    private val admitPendingSnapshot: (AudioV1Snapshot) -> Boolean = { false },
) : AudioFaderStreamPort, AutoCloseable {
    private data class Target(val command: AudioCommand.Fader, val stamp: Long, val id: String? = null,
                              val effect: HelmAudioEffectLease? = null, val server: String? = null, val revision: Long? = null)
    private data class Flight(val target: Target, val id: String, val server: String, val revision: Long, val sent: Long, val deadline: Long, val terminal: Boolean)
    private var connection: SourceSubscription? = null
    private var closed = false
    private var connected = false
    private var generation = 0L
    private var nextConnect = 0L
    private var nextSend = 0L
    private var gesture: AudioChannel? = null
    private var target: Target? = null
    private var desired: Target? = null
    private var flight: Flight? = null
    private var released = false
    private var sent = 0L
    private var confirmed = 0L
    private var lastLatency: Long? = null
    override val status get() = synchronized(lock) {
        transport.label + (if (connected) " READY" else " UNAVAILABLE") + " · sent $sent · confirmed $confirmed" + (lastLatency?.let { " · last ACK ${it}ms" } ?: "")
    }
    override val active get() = synchronized(lock) { gesture != null }
    override val channel get() = synchronized(lock) { gesture }
    val ready get() = synchronized(lock) { connected }
    val busy get() = synchronized(lock) { gesture != null || target != null || flight != null }
    override fun available(channel: AudioChannel, valueDb: Double) = synchronized(lock) {
        !closed && connected && !released && !standardPending() && (gesture == null || gesture == channel) && gate(AudioCommand.Fader(channel, valueDb))
    }
    override fun preview(channel: AudioChannel, valueDb: Double): Boolean = synchronized(lock) {
        if (!available(channel, valueDb)) return false
        gesture = channel
        target = Target(AudioCommand.Fader(channel, valueDb), now()); desired = target
        true
    }
    override fun finish(): AudioCommandOutcome? = synchronized(lock) {
        val latest = target ?: flight?.target ?: desired ?: return null
        gesture = null; desired = null; released = true
        val id = nextId()
        target = latest.copy(stamp = now(), id = id)
        val result = outcome(id, latest.command, AudioCommandStatus.PENDING, "AUDIO · CONFIRMING FADER END VALUE")
        pending(result, now() + 5_000)
        result
    }
    /** A discrete knob write retains its original owner/focus lease and gateway revision. */
    fun submitKnob(command: AudioCommand.Fader, effect: HelmAudioEffectLease): AudioCommandOutcome = synchronized(lock) {
        if (busy || !available(command.channel, command.valueDb)) {
            effect.cancel()
            return@synchronized outcome(null, command, AudioCommandStatus.REJECTED, "Knob WSS unavailable; no HTTP fallback").also(complete)
        }
        val id = nextId()
        target = Target(command, now(), id, effect, serverSession(), revision())
        released = true
        outcome(id, command, AudioCommandStatus.PENDING, "AUDIO · CONFIRMING KNOB FADER").also { pending(it, now() + 5_000) }
    }
    override fun cancel(): Unit = synchronized(lock) {
        if (gesture == null) return
        gesture = null; target = null; desired = null
        // A sent value may complete; it is never retracted/replayed.
    }
    fun pump(): Unit = synchronized(lock) {
        if (closed) return
        if (connection == null && now() >= nextConnect) {
            nextConnect = now() + 2_000
            val epoch = ++generation
            connection = transport.connect(object : AudioFaderStreamTransport.Listener {
                override fun text(value: String): Unit = synchronized(lock) { if (epoch == generation) receive(value) }
                override fun failure(): Unit = synchronized(lock) { if (epoch == generation) lost() }
            })
        }
        flight?.let { if (now() >= it.deadline) { lost(); return } }
        target?.takeIf { it.effect?.dropQueuedNow(identity.sessionId) == true }?.let {
            discardKnob(it, "Knob lease retired before send; no replay"); return
        }
        if (!connected || flight != null || now() < nextSend) return
        val latest = target ?: return
        if (now() - latest.stamp > (if (latest.id == null) 500 else 5_000) || !gate(latest.command)) { lost(); return }
        val server = latest.server ?: serverSession() ?: return
        if (server != serverSession()) { lost(); return }
        val id = latest.id ?: nextId()
        val write = Flight(latest, id, server, latest.revision ?: revision(), now(), now() + 1_500, latest.id != null)
        try {
            val body = encodeCommand(identity.sessionId, id, server, write.revision, latest.command)
            // Same final lease check as HTTP: AFTER encoding, immediately BEFORE handing off bytes.
            if (latest.effect?.beginSend(identity.sessionId) == false) {
                discardKnob(latest, "Knob lease retired before WSS send; no replay"); return
            }
            target = null; flight = write; nextSend = now() + 40 // <=25 writes/s
            if (!transport.send(body)) lost() else sent++
        } catch (_: Exception) { lost() }
    }
    private fun discardKnob(value: Target, message: String) {
        value.effect?.cancel(); target = null; released = false
        complete(outcome(value.id, value.command, AudioCommandStatus.DROPPED, message))
    }
    private fun receive(value: String): Unit = synchronized(lock) {
        if (closed) return
        try {
            when (JSONObject(value).getString("kind")) {
                "audio" -> {
                    val snapshot = AudioV1Decoder.state(value)
                    if (flight == null && (!standardPending() || admitPendingSnapshot(snapshot))) {
                        val oldServer = serverSession()
                        accept(snapshot)
                        if (oldServer != null && snapshot.serverSessionId != oldServer && busy) { lost(); return }
                    }
                    connected = true
                }
                "audio-ack" -> {
                    val write = flight ?: throw PiProtocolException("Unexpected fader acknowledgement")
                    val ack = AudioV1Decoder.ack(value)
                    if (ack.sessionId != identity.sessionId || ack.requestId != write.id || ack.snapshot.serverSessionId != write.server)
                        throw PiProtocolException("Fader acknowledgement session/request mismatch")
                    val db = ack.snapshot.state.dsp.faderControls.singleOrNull { it.gatewayId == write.target.command.channel }?.db
                    val verified = ack.status == AudioCommandStatus.VERIFIED && ack.snapshot.state.revision > write.revision &&
                        db != null && abs(db - write.target.command.valueDb) < .01
                    accept(ack.snapshot)
                    flight = null
                    if (verified) write.target.effect?.verifiedReceipt()
                    else { write.target.effect?.completionUnknown(); write.target.effect?.cancel() }
                    if (!verified) {
                        target?.id?.let { complete(outcome(it, target!!.command, AudioCommandStatus.DROPPED, "Fader stream rejected; gesture discarded without replay")) }
                        target = null; desired = null; gesture = null; released = false
                    } else { confirmed++; lastLatency = now() - write.sent }
                    if (write.terminal) {
                        released = false
                        complete(outcome(write.id, write.target.command, if (verified) AudioCommandStatus.VERIFIED else AudioCommandStatus.REJECTED,
                            ack.message, write.revision, ack.snapshot.state.revision, db))
                    }
                }
                else -> throw PiProtocolException("Unexpected AUDIO stream message")
            }
        } catch (_: Exception) { lost() }
    }
    private fun lost(): Unit = synchronized(lock) {
        connected = false; generation++
        val final = target?.takeIf { it.id != null }?.let { it.id!! to it.command }
            ?: flight?.takeIf { it.terminal }?.let { it.id to it.target.command }
        target?.effect?.cancel()
        flight?.target?.effect?.let { it.completionUnknown(); it.cancel() }
        target = null; desired = null; flight = null; gesture = null; released = false
        connection?.close(); connection = null; nextConnect = now() + 2_000
        final?.let { complete(outcome(it.first, it.second, AudioCommandStatus.DROPPED, "Fader stream disconnected; target discarded without replay")) }
    }
    private fun outcome(id: String?, command: AudioCommand.Fader, status: AudioCommandStatus, message: String,
                        expected: Long? = null, acknowledged: Long? = null, db: Double? = null) =
        AudioCommandOutcome(identity.sessionId, id, command, status, now(), message,
            expectedGatewayRevision = expected, acknowledgedGatewayRevision = acknowledged, confirmedValueDb = db)
    override fun close(): Unit = synchronized(lock) {
        if (closed) return
        closed = true
        try { lost() } finally { transport.close() }
    }
}
