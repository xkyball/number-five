package com.no5.helm.network

import com.no5.domain.*
import com.no5.helm.control.HelmAudioEffectContext
import com.no5.helm.control.HelmAudioEffectLease
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** One serialized background loop owns GET and POST. UI submission never performs I/O. */
class NetworkPiAudioSource(
    override val identity: SourceIdentity,
    private val transport: PiHttpTransport,
    private val now: () -> Long = System::currentTimeMillis,
    scheduler: PiPollScheduler = BackgroundPiPollScheduler("audio"),
    private val policy: PiNetworkPolicy = PiNetworkPolicy.AUDIO,
    streamTransport: AudioFaderStreamTransport? = null,
    override val mediaBrowser: AudioMediaPort? = null,
    private val encodeCommand: (String, String, String, Long, AudioCommand) -> String = AudioV1Decoder::command,
    override val sound: AudioSoundPort? = null,
) : AudioSourcePort {
    init { require(identity.mode == SourceMode.PI) }
    private val lock = Any()
    private var closed = false
    private val polling = AtomicBoolean(false)
    private var counter = 0L
    private var serverSession: String? = null
    private var serverRevision = -1L
    private var acceptedTimestamp: Long? = null
    private val componentTimestamps = mutableMapOf<String, Long>()
    private var everConnected = false
    private val stateFeed = MutableSourceFeed(AudioModel.initial().copy(sessionId = identity.sessionId, feedback = "AUDIO · CONNECTING"))
    private val outcomeFeed = MutableSourceFeed<AudioCommandOutcome?>(null)
    override val state: SourceFeed<AudioState> get() = stateFeed
    override val outcomes: SourceFeed<AudioCommandOutcome?> get() = outcomeFeed
    private var queued: Write? = null
    private data class Write(val id: String, val command: AudioCommand, val serverSession: String, val revision: Long, val deadline: Long, val rootEffect: HelmAudioEffectLease?, var gatewayId: String? = null)
    private val streaming = streamTransport?.let { stream -> AudioFaderStreamSession(lock, identity, stream, now,
        serverSession = { serverSession }, revision = { serverRevision },
        nextId = { "${identity.sessionId}:${++counter}" },
        gate = { command -> !closed && queued == null && serverSession != null &&
            AudioModel.available(state.value.withAudioFreshness(now(), freshnessPolicy).copy(pending = null), command) && valid(command) },
        accept = { snapshot ->
            // A periodic publication can already be in the socket's send queue
            // when a newer command ACK arrives. Never roll back a confirmed state.
            if (snapshot.serverSessionId != serverSession || (snapshot.state.revision >= serverRevision &&
                    acceptedTimestamp?.let { snapshot.timestampEpochMs >= it } != false)) accept(snapshot)
        }, standardPending = { queued != null },
        pending = { outcome, deadline ->
            emitState(state.value.copy(pending = AudioPending(requireNotNull(outcome.requestId), outcome.command,
                state.value.dsp, state.value.media, deadline), feedback = outcome.message)); emitOutcome(outcome)
        }, complete = { outcome ->
            if (state.value.pending?.requestId == outcome.requestId) emitState(state.value.copy(pending = null, feedback = outcome.message))
            emitOutcome(outcome)
        }, encodeCommand = encodeCommand, admitPendingSnapshot = { snapshot ->
            // A queued EQ must see an actual profile/capability withdrawal before
            // sending. Keep ordinary publications suppressed during other HTTP
            // commands, and never interrupt an in-flight WSS fader gesture.
            (queued?.command as? AudioCommand.Equalizer)?.let { command ->
                snapshot.serverSessionId != serverSession || !snapshot.state.capabilities.equalizer ||
                    snapshot.state.dsp.equalizer?.let { !it.admits(command) } != false
            } == true
        }) }
    override val faderStream: AudioFaderStreamPort? get() = streaming
    private val streamJob = try { streaming?.let { scheduler.schedule(20, it::pump) } } catch (failure: RuntimeException) {
        streaming?.close(); sound?.close(); mediaBrowser?.close(); stateFeed.close(); outcomeFeed.close(); transport.close(); throw failure
    }
    private val job = try { scheduler.schedule(policy.pollIntervalMs, ::poll) } catch (failure: RuntimeException) {
        streamJob?.close(); streaming?.close(); sound?.close(); mediaBrowser?.close(); stateFeed.close(); outcomeFeed.close(); transport.close(); throw failure
    }

    override fun available(command: AudioCommand): Boolean = synchronized(lock) {
        !closed && streaming?.busy != true && queued == null && state.value.pending == null && serverSession != null &&
            (command !is AudioCommand.Fader || streaming == null || streaming.ready) &&
            AudioModel.available(state.value.withAudioFreshness(now(), freshnessPolicy), command) && valid(command)
    }
    /**
     * Real gateway DSP writes are admitted only through the current catalog.
     * Legacy fader maps remain display compatibility data; they are never an
     * authorization substitute for a physical index/label mapping or a
     * validated preset identifier.
     */
    private fun valid(command: AudioCommand): Boolean = when (command) {
        is AudioCommand.Fader -> state.value.dsp.faderControls.singleOrNull { it.gatewayId == command.channel }?.let { control ->
            command.valueDb.isFinite() && control.db != null && control.limit?.let { limit ->
                command.valueDb in limit.minDb..limit.maxDb && abs(limit.clamp(command.valueDb) - command.valueDb) < 0.000_001
            } == true
        } == true
        is AudioCommand.Mute -> state.value.dsp.faderControls.any { it.gatewayId == command.channel }
        is AudioCommand.Preset -> state.value.dsp.presets.any { it.id == command.id }
        is AudioCommand.Equalizer -> state.value.dsp.equalizer?.admits(command) == true
        is AudioCommand.Transport -> command.action != TransportAction.SEEK || (command.positionMs != null && state.value.media.durationMs != null)
    }
    override fun submit(command: AudioCommand): AudioCommandOutcome = synchronized(lock) {
        val stamp = now()
        if (!available(command)) {
            return@synchronized outcome(command, null, AudioCommandStatus.REJECTED, unavailableReason(command), stamp,
                expectedRevision = serverRevision.takeIf { it >= 0 }).also {
                if (!closed) { emitState(state.value.copy(feedback = it.message)); emitOutcome(it) }
            }
        }
        val rootEffect = HelmAudioEffectContext.current()
        if (rootEffect != null && !rootEffect.claimQueue(identity.sessionId)) {
            return@synchronized outcome(command, null, AudioCommandStatus.REJECTED, "Knob lease retired; no command queued", stamp).also(::emitOutcome)
        }
        if (command is AudioCommand.Fader && streaming != null) {
            if (rootEffect != null) return@synchronized streaming.submitKnob(command, rootEffect)
            if (streaming.preview(command.channel, command.valueDb)) return@synchronized requireNotNull(streaming.finish())
            return@synchronized outcome(command, null, AudioCommandStatus.REJECTED, "WSS fader unavailable; no HTTP fallback", stamp).also(::emitOutcome)
        }
        val requestId = "${identity.sessionId}:${++counter}"
        val deadline = if (stamp > Long.MAX_VALUE - 5_000) Long.MAX_VALUE else stamp + 5_000
        val write = Write(requestId, command, requireNotNull(serverSession), serverRevision, deadline, rootEffect)
        queued = write
        emitState(state.value.copy(pending = AudioPending(requestId, command, state.value.dsp, state.value.media, deadline),
            feedback = "AUDIO · APPLYING / VERIFYING"))
        val pending = outcome(command, requestId, AudioCommandStatus.PENDING, "AUDIO · APPLYING / VERIFYING", stamp,
            expectedRevision = serverRevision).also(::emitOutcome)
        // Only a queued Root knob lease wakes the EXISTING command job. No new I/O worker.
        val wake = job as? WakeablePollSubscription
        if (rootEffect != null && wake != null && !try { wake.requestNow() } catch (_: RuntimeException) { false }) {
            finish(write, AudioCommandStatus.DROPPED, "Audio worker wake unavailable; command dropped without replay")
            return@synchronized outcome(command, requestId, AudioCommandStatus.DROPPED,
                "Audio worker wake unavailable; command dropped without replay", stamp, expectedRevision = write.revision)
        }
        pending
    }
    private fun unavailableReason(command: AudioCommand): String {
        val fresh = state.value.withAudioFreshness(now(), freshnessPolicy)
        val gate = AudioCommandGate.reason(fresh, command)
        return when {
        closed -> "Audio source is closed; no command sent"
        queued != null || state.value.pending != null -> "Audio command already pending; no command sent"
        serverSession == null -> "Gateway session is not established; no command sent"
        gate != null -> "$gate; no command sent"
        !valid(command) -> "AUDIO_GATE_CATALOG_OR_FADER_LIMIT_UNAVAILABLE; no command sent"
        else -> "Audio command unavailable; no command sent"
        }
    }
    private fun poll() {
        if (!polling.compareAndSet(false, true)) return
        try {
            val write = synchronized(lock) {
                if (closed) return
                expire()
                queued
            }
            if (write != null) send(write) else if (streaming?.ready != true) read()
        } finally { polling.set(false) }
    }
    private fun send(write: Write) {
        synchronized(lock) {
            if (closed || queued?.id != write.id) return
            val fresh = state.value.withAudioFreshness(now(), freshnessPolicy)
            if (!AudioModel.available(fresh, write.command) || now() >= write.deadline) {
                finish(write, AudioCommandStatus.DROPPED, "Audio became unavailable; command dropped without replay")
                return
            }
        }
        try {
            val body = encodeCommand(identity.sessionId, write.id, write.serverSession, write.revision, write.command)
            // Final local check AFTER original encoding and immediately BEFORE original POST.
            if (write.gatewayId == null && write.rootEffect?.beginSend(identity.sessionId) == false) {
                synchronized(lock) { finish(write, AudioCommandStatus.DROPPED, "Knob lease retired before send; no replay") }
                return
            }
            val response = write.gatewayId?.let { transport.get("/v1/audio/commands/$it") }
                ?: transport.post("/v1/audio/commands", body)
            val (gatewayId, completed) = audioCommandResult(response, write.id, write.serverSession)
            if (write.gatewayId != null && write.gatewayId != gatewayId) throw PiProtocolException("Audio command identity changed")
            write.gatewayId = gatewayId
            val ack = completed ?: return
            synchronized(lock) {
                if (closed || queued?.id != write.id) return
                if (now() >= write.deadline) { finish(write, AudioCommandStatus.FAILED, "Audio command timed out; no replay"); return }
                if (ack.sessionId != identity.sessionId || ack.requestId != write.id || ack.snapshot.serverSessionId != write.serverSession)
                    throw PiProtocolException("Audio acknowledgement session/request mismatch")
                if (ack.status == AudioCommandStatus.VERIFIED && ack.snapshot.state.revision <= write.revision)
                    throw PiProtocolException("Audio acknowledgement did not advance revision")
                accept(ack.snapshot)
                val confirmedValue = confirmedFaderValue(write.command)
                val faderConfirmed = (write.command as? AudioCommand.Fader)?.let { requested ->
                    confirmedValue != null && abs(confirmedValue - requested.valueDb) < 0.01
                } ?: true
                val equalizerConfirmed = (write.command as? AudioCommand.Equalizer)?.let { requested ->
                    val eq = state.value.dsp.equalizer
                    eq != null && eq.bankId == requested.bankId && eq.configGeneration == requested.configGeneration &&
                        eq.verification == "ACTIVE_CONFIG_READBACK" && eq.gainsDb.size == requested.gainsDb.size &&
                        now().toDouble() - eq.observedAtEpochMs.toDouble() in -5_000.0..freshnessPolicy.staleAfterMs.toDouble() &&
                        eq.gainsDb.indices.all { abs(eq.gainsDb[it] - requested.gainsDb[it]) < .000001 }
                } ?: true
                val finalStatus = if (ack.status == AudioCommandStatus.VERIFIED && (!faderConfirmed || !equalizerConfirmed)) AudioCommandStatus.FAILED else ack.status
                val finalMessage = if (finalStatus == AudioCommandStatus.FAILED)
                    if (!equalizerConfirmed) "Gateway acknowledgement did not confirm requested equalizer configuration; no command replayed"
                    else "Gateway acknowledgement did not confirm requested fader; no command replayed" else ack.message
                finish(write, finalStatus, finalMessage, acknowledgedRevision = serverRevision, confirmedValueDb = confirmedValue)
            }
        } catch (failure: PiHttpStatusException) {
            synchronized(lock) { if (!closed && queued?.id == write.id) {
                failState(TelemetryStatus.LOST)
                finish(write, AudioCommandStatus.FAILED, "Audio command HTTP ${failure.statusCode}; no command replayed",
                    httpStatusCode = failure.statusCode)
            } }
        } catch (_: PiProtocolException) {
            synchronized(lock) { if (!closed && queued?.id == write.id) { failState(TelemetryStatus.INVALID); finish(write, AudioCommandStatus.FAILED, "Invalid audio acknowledgement; no command replayed") } }
        } catch (_: Exception) {
            synchronized(lock) { if (!closed && queued?.id == write.id) { failState(TelemetryStatus.LOST); finish(write, AudioCommandStatus.FAILED, "Audio command unconfirmed; no command replayed") } }
        } finally { if (queued?.id != write.id) write.rootEffect?.completionUnknown() }
    }
    private fun read() {
        try {
            val snapshot = AudioV1Decoder.state(transport.get("/v1/audio"))
            synchronized(lock) { if (!closed && streaming?.ready != true) accept(snapshot) }
        } catch (_: PiProtocolException) {
            synchronized(lock) { if (!closed && streaming?.ready != true) { failState(TelemetryStatus.INVALID); dropQueued("Invalid audio state; pending command dropped") } }
        } catch (_: Exception) {
            synchronized(lock) { if (!closed && streaming?.ready != true) { failState(if (everConnected) TelemetryStatus.LOST else TelemetryStatus.UNAVAILABLE); dropQueued("Audio disconnected; pending command dropped") } }
        }
    }
    private fun accept(snapshot: AudioV1Snapshot) {
        val stamp = snapshot.timestampEpochMs
        val currentTime = now()
        if (stamp.toDouble() - currentTime.toDouble() > 5_000) throw PiProtocolException("Audio timestamp in future")
        val changedSession = serverSession != null && snapshot.serverSessionId != serverSession
        if (!changedSession && (snapshot.state.revision < serverRevision || acceptedTimestamp?.let { stamp < it } == true))
            throw PiProtocolException("Audio state moved backwards")
        val qualities = mapOf("source" to snapshot.state.source.quality, "dsp" to snapshot.state.dsp.quality,
            "meters" to snapshot.state.dsp.meters?.quality,
            "equalizer" to snapshot.state.dsp.equalizer?.let { snapshot.state.dsp.quality.copy(timestampEpochMs = it.observedAtEpochMs) })
        if (!changedSession) for ((component, quality) in qualities) {
            val observed = quality?.timestampEpochMs ?: continue
            if (componentTimestamps[component]?.let { observed < it } == true)
                throw PiProtocolException("Audio component timestamp moved backwards")
        }
        if (changedSession) { componentTimestamps.clear(); dropQueued("Gateway restarted; pending command dropped without replay"); acceptedTimestamp = null }
        serverSession = snapshot.serverSessionId
        serverRevision = snapshot.state.revision
        if (stamp <= currentTime) acceptedTimestamp = stamp
        for ((component, quality) in qualities) quality?.timestampEpochMs?.takeIf { it <= currentTime }?.let { componentTimestamps[component] = it }
        everConnected = true
        val next = snapshot.state.withAudioFreshness(currentTime, freshnessPolicy)
        emitState(next.copy(sessionId = identity.sessionId, revision = state.value.revision + 1, pending = state.value.pending,
            feedback = if (next.source.quality.simulated) "SIMULATED · NO SOUND OUTPUT / HARDWARE ROUTING" else "AUDIO · GATEWAY CONNECTED"))
        // A GET can finish while a command is queued. Revalidate its original server revision
        // at POST, which safely rejects a conflicting command instead of silently rebasing it.
    }
    private fun failState(status: TelemetryStatus) {
        fun quality(old: TelemetryQuality) = old.copy(status = status)
        val previous = state.value
        emitState(previous.copy(source = previous.source.copy(connected = false, active = false, quality = quality(previous.source.quality)),
            dsp = previous.dsp.copy(state = DspState.ERROR, quality = quality(previous.dsp.quality),
                meters = previous.dsp.meters?.let { it.copy(quality = quality(it.quality)) }),
            capabilities = AudioCapabilities(), feedback = "AUDIO · ${status.name}"))
    }
    private fun confirmedFaderValue(command: AudioCommand): Double? = (command as? AudioCommand.Fader)?.let { requested ->
        state.value.dsp.faderControls.singleOrNull { it.gatewayId == requested.channel }?.db
    }
    private fun finish(
        write: Write,
        status: AudioCommandStatus,
        message: String,
        acknowledgedRevision: Long? = null,
        httpStatusCode: Int? = null,
        confirmedValueDb: Double? = null,
    ) {
        if (queued?.id != write.id) return
        queued = null
        if (status == AudioCommandStatus.VERIFIED) write.rootEffect?.verifiedReceipt()
        else { write.rootEffect?.completionUnknown(); write.rootEffect?.cancel() }
        emitState(state.value.copy(pending = null, feedback = message))
        emitOutcome(outcome(write.command, write.id, status, message, now(), expectedRevision = write.revision,
            acknowledgedRevision = acknowledgedRevision, httpStatusCode = httpStatusCode, confirmedValueDb = confirmedValueDb))
    }
    private fun dropQueued(message: String) { queued?.let { finish(it, AudioCommandStatus.DROPPED, message) } }
    private fun expire() {
        queued?.takeIf { it.rootEffect?.dropQueuedNow(identity.sessionId) == true }?.let {
            finish(it, AudioCommandStatus.DROPPED, "Knob lease retired before send; no replay")
        }
        queued?.takeIf { now() >= it.deadline }?.let { finish(it, AudioCommandStatus.FAILED, "Audio command timed out; no replay") }
    }
    override fun tick() = synchronized(lock) {
        if (!closed) { expire(); emitState(state.value.withAudioFreshness(now(), freshnessPolicy)) }
    }
    override fun close() {
        var failure: RuntimeException? = null
        fun retire(action: () -> Unit) {
            try { action() } catch (error: RuntimeException) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        synchronized(lock) {
            if (closed) return
            closed = true
            retire { dropQueued("Audio source closed; pending command dropped") }
            retire { streaming?.close() }
            retire { sound?.close() }
            retire { mediaBrowser?.close() }
            stateFeed.close(); outcomeFeed.close()
        }
        // A failing child/observer must not strand the old session's two polling
        // executors and HTTP worker on every reconnect. Still report the failure.
        retire { streamJob?.close() }; retire { job.close() }; retire { transport.close() }
        failure?.let { throw it }
    }
    private fun outcome(
        command: AudioCommand,
        id: String?,
        status: AudioCommandStatus,
        message: String,
        stamp: Long,
        expectedRevision: Long? = null,
        acknowledgedRevision: Long? = null,
        httpStatusCode: Int? = null,
        confirmedValueDb: Double? = null,
    ) = AudioCommandOutcome(identity.sessionId, id, command, status, stamp, message,
        expectedRevision, acknowledgedRevision, httpStatusCode, confirmedValueDb)
    private fun emitState(value: AudioState) { try { stateFeed.emit(value) } catch (_: RuntimeException) { } }
    private fun emitOutcome(value: AudioCommandOutcome) { try { outcomeFeed.emit(value) } catch (_: RuntimeException) { } }
}
