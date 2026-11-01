package com.no5.domain

/**
 * An adapter owns telemetry, capabilities, command completion and its own session. No Android types.
 * Serialize calls and emissions within each source. State revisions never go backwards in a session;
 * request IDs never repeat. Availability must use that adapter's current quality/freshness policy.
 * UI consumers marshal feed callbacks onto their UI dispatcher; close cancels all pending work.
 */
interface AudioSourcePort : AutoCloseable {
    val identity: SourceIdentity
    val state: SourceFeed<AudioState>
    val outcomes: SourceFeed<AudioCommandOutcome?>
    val freshnessPolicy: FreshnessPolicy get() = QualityEvaluator.AUDIO
    val demoControls: AudioDemoControls? get() = null
    val faderStream: AudioFaderStreamPort? get() = null
    val mediaBrowser: AudioMediaPort? get() = null
    val sound: AudioSoundPort? get() = null
    fun available(command: AudioCommand): Boolean
    fun submit(command: AudioCommand): AudioCommandOutcome
    /** Pump due transport work, playback and freshness using the adapter's injected clock. */
    fun tick()
    override fun close()
}

/** One active gesture, one in-flight value and one replaceable latest value. */
interface AudioFaderStreamPort {
    val status: String
    val active: Boolean
    val channel: AudioChannel?
    fun available(channel: AudioChannel, valueDb: Double): Boolean
    fun preview(channel: AudioChannel, valueDb: Double): Boolean
    fun finish(): AudioCommandOutcome?
    /** Drop unsent values. An already sent value may still be observed. */
    fun cancel()
}

enum class AudioCommandStatus { PENDING, VERIFIED, FAILED, DROPPED, REJECTED }
data class AudioCommandOutcome(
    val sessionId: String,
    val requestId: String?,
    val command: AudioCommand,
    val status: AudioCommandStatus,
    val timestampEpochMs: Long,
    val message: String,
    /** Gateway state revision used for a real Pi command; never contains auth material. */
    val expectedGatewayRevision: Long? = null,
    /** Gateway revision carried by the accepted/rejected acknowledgement, if any. */
    val acknowledgedGatewayRevision: Long? = null,
    /** Numeric HTTP failure class only; response bodies and keys are deliberately excluded. */
    val httpStatusCode: Int? = null,
    /** Confirmed fader dB value from the acknowledgement snapshot, if applicable. */
    val confirmedValueDb: Double? = null,
) {
    val accepted: Boolean get() = status == AudioCommandStatus.PENDING || status == AudioCommandStatus.VERIFIED
}

data class AudioDemoState(val running: Boolean = false, val failNextWrite: Boolean = false)
/** Optional simulator surface. A Pi adapter must not implement or expose this interface. */
interface AudioDemoControls {
    val demoState: SourceFeed<AudioDemoState>
    fun loadFixture(fixture: AudioFixture)
    fun start()
    fun stop()
    fun reset()
    fun failNextWrite()
    fun setOffline(target: AudioOfflineTarget, offline: Boolean = true)
    fun completePending(requestId: String, verified: Boolean)
}

/** Evaluates source, DSP and meters independently; never turns host time into telemetry time. */
fun AudioState.withAudioFreshness(nowEpochMs: Long, policy: FreshnessPolicy = QualityEvaluator.AUDIO): AudioState {
    fun quality(q: TelemetryQuality) = q.copy(status = QualityEvaluator.evaluate(q, nowEpochMs, policy))
    return copy(source = source.copy(quality = quality(source.quality)), dsp = dsp.copy(
        quality = quality(dsp.quality), meters = dsp.meters?.let { it.copy(quality = quality(it.quality)) },
        equalizer = dsp.equalizer?.let { eq ->
            val observed = dsp.quality.copy(timestampEpochMs = eq.observedAtEpochMs)
            if (QualityEvaluator.evaluate(observed, nowEpochMs, policy) == TelemetryStatus.LIVE) eq else eq.copy(writable = false)
        }))
}

/** Reusable local simulator. Explicit identity + clock make fixtures, IDs and timing reproducible. */
class MockAudioSource(
    override val identity: SourceIdentity = SourceIdentity("audio-demo", SourceMode.DEMO, "LOCAL AUDIO DEMO"),
    private val now: () -> Long = { 0L },
    private val verificationDelayMs: Long = 240,
    private val writeTimeoutMs: Long = 5_000,
) : AudioSourcePort, AudioDemoControls {
    init {
        require(identity.mode == SourceMode.DEMO)
        require(verificationDelayMs in 0..30_000)
        require(writeTimeoutMs in 1..30_000)
    }
    private val stateFeed = MutableSourceFeed(AudioModel.initial().copy(sessionId = identity.sessionId))
    private val outcomeFeed = MutableSourceFeed<AudioCommandOutcome?>(null)
    private val demoFeed = MutableSourceFeed(AudioDemoState())
    override val state: SourceFeed<AudioState> get() = stateFeed
    override val outcomes: SourceFeed<AudioCommandOutcome?> get() = outcomeFeed
    override val demoState: SourceFeed<AudioDemoState> get() = demoFeed
    override val demoControls: AudioDemoControls get() = this
    private var closed = false
    private var scheduled: ScheduledVerification? = null
    private var lastPlaybackEpochMs: Long? = null
    private var trackIndex = 0
    private data class Track(val id: String, val title: String, val durationMs: Long, val artworkId: String)
    private val tracks = listOf(Track("open-water", "Open water", 247_000L, "tidal-lines"),
        Track("blue-horizon", "Blue horizon", 213_000L, "harbor-lights"),
        Track("evening-anchorage", "Evening anchorage", 286_000L, "orange-current"))
    private fun mediaForTrack(previous: AudioMedia, positionMs: Long = 0): AudioMedia = tracks[trackIndex].let {
        previous.copy(title = it.title, durationMs = it.durationMs, positionMs = positionMs,
            trackId = it.id, artworkId = it.artworkId, album = "No 5 · Imaginary voyages")
    }
    private data class ScheduledVerification(val sessionId: String, val requestId: String, val dueEpochMs: Long, val verified: Boolean)

    override fun available(command: AudioCommand): Boolean = !closed && state.value.pending == null &&
        AudioModel.available(state.value.withAudioFreshness(now()), command)

    override fun submit(command: AudioCommand): AudioCommandOutcome {
        val stamp = now()
        if (closed) return outcome(command, null, AudioCommandStatus.REJECTED, "Source closed; no command sent", stamp)
        val result = AudioModel.startWrite(state.value.withAudioFreshness(stamp), command, stamp, writeTimeoutMs)
        if (!result.accepted) {
            val rejected = outcome(command, null, AudioCommandStatus.REJECTED, result.error ?: "No command sent", stamp)
            stateFeed.emit(state.value.copy(feedback = rejected.message))
            outcomeFeed.emit(rejected)
            return rejected
        }
        val id = requireNotNull(result.requestId)
        val due = if (stamp > Long.MAX_VALUE - verificationDelayMs) Long.MAX_VALUE else stamp + verificationDelayMs
        scheduled = ScheduledVerification(identity.sessionId, id, due, !demoState.value.failNextWrite)
        demoFeed.emit(demoState.value.copy(failNextWrite = false))
        val pending = outcome(command, id, AudioCommandStatus.PENDING, "DEMO · APPLYING / VERIFYING", stamp)
        stateFeed.emit(result.state)
        outcomeFeed.emit(pending)
        return pending
    }

    /** Session and request must both match. A dropped, closed or old request cannot mutate state. */
    fun acknowledge(sessionId: String, requestId: String, verified: Boolean): Boolean {
        if (closed || sessionId != identity.sessionId) return false
        val pending = state.value.pending?.takeIf { it.requestId == requestId } ?: return false
        val stamp = now()
        val success = verified && stamp < pending.deadlineEpochMs
        var next = AudioModel.finishWrite(state.value, requestId, verified, stamp)
        if (success && pending.command is AudioCommand.Transport && pending.command.action in listOf(TransportAction.NEXT, TransportAction.PREVIOUS)) {
            trackIndex = Math.floorMod(trackIndex + if (pending.command.action == TransportAction.NEXT) 1 else -1, tracks.size)
            next = next.copy(media = mediaForTrack(next.media))
        }
        scheduled = null
        lastPlaybackEpochMs = stamp
        stateFeed.emit(next.withAudioFreshness(stamp))
        outcomeFeed.emit(outcome(pending.command, requestId, if (success) AudioCommandStatus.VERIFIED else AudioCommandStatus.FAILED,
            next.feedback ?: "DEMO · WRITE FAILED", stamp))
        return true
    }

    override fun completePending(requestId: String, verified: Boolean) { acknowledge(identity.sessionId, requestId, verified) }

    override fun tick() {
        if (closed) return
        val stamp = now()
        val pending = state.value.pending
        if (pending != null && stamp >= pending.deadlineEpochMs) acknowledge(identity.sessionId, pending.requestId, false)
        else scheduled?.takeIf { stamp >= it.dueEpochMs }?.let { acknowledge(it.sessionId, it.requestId, it.verified) }
        var next = state.value.withAudioFreshness(stamp)
        val previousTick = lastPlaybackEpochMs
        lastPlaybackEpochMs = stamp
        if (demoState.value.running && next.pending == null && next.source.connected && next.media.state == MediaState.PLAYING && previousTick != null) {
            val elapsed = (stamp.toDouble() - previousTick.toDouble()).coerceIn(0.0, 60_000.0).toLong()
            val duration = next.media.durationMs
            if (duration != null && elapsed > 0) {
                val position = ((next.media.positionMs ?: 0).toDouble() + elapsed).coerceAtMost(duration.toDouble()).toLong()
                val repeat = next.media.repeat == true
                next = if (position == duration && !repeat) {
                    trackIndex = (trackIndex + 1) % tracks.size
                    next.copy(media = mediaForTrack(next.media).copy(state = MediaState.PLAYING))
                } else next.copy(media = next.media.copy(positionMs = if (position == duration) 0 else position,
                    state = MediaState.PLAYING))
            }
        }
        stateFeed.emit(next)
    }

    override fun loadFixture(fixture: AudioFixture) {
        if (closed) return
        dropPending("DEMO RESET · PENDING COMMAND DROPPED")
        demoFeed.emit(AudioDemoState())
        lastPlaybackEpochMs = now()
        trackIndex = 0
        val stamp = now()
        var next = AudioModel.fixture(fixture).copy(sessionId = identity.sessionId, revision = state.value.revision + 1)
        if (fixture != AudioFixture.CANONICAL) {
            next = next.copy(source = next.source.copy(quality = stamped(next.source.quality, stamp)),
                dsp = next.dsp.copy(quality = stamped(next.dsp.quality, stamp)))
            if (fixture !in listOf(AudioFixture.DSP_OFFLINE, AudioFixture.USB_OFFLINE)) next = AudioModel.meters(next, meterFrame(fixture == AudioFixture.CLIPPING))
        }
        stateFeed.emit(next)
    }

    override fun start() {
        if (closed) return
        if (state.value.mode != OperatingMode.DEMO) loadFixture(AudioFixture.DEMO)
        demoFeed.emit(demoState.value.copy(running = true))
        lastPlaybackEpochMs = now()
        if (available(AudioCommand.Transport(TransportAction.PLAY))) stateFeed.emit(state.value.copy(media = state.value.media.copy(state = MediaState.PLAYING)))
    }
    override fun stop() {
        if (closed) return
        demoFeed.emit(demoState.value.copy(running = false))
        dropPending("DEMO STOPPED · PENDING COMMAND DROPPED")
        if (state.value.mode == OperatingMode.DEMO) stateFeed.emit(state.value.copy(media = state.value.media.copy(state = MediaState.PAUSED)))
        lastPlaybackEpochMs = null
    }
    override fun reset() = loadFixture(AudioFixture.CANONICAL)
    override fun failNextWrite() { if (!closed && state.value.mode == OperatingMode.DEMO) demoFeed.emit(demoState.value.copy(failNextWrite = true)) }
    override fun setOffline(target: AudioOfflineTarget, offline: Boolean) {
        if (closed || state.value.mode != OperatingMode.DEMO) return
        if (offline) {
            val pending = state.value.pending
            var next = AudioModel.offline(state.value, target)
            if (target != AudioOfflineTarget.SOURCE) next = next.copy(dsp = next.dsp.copy(meters = next.dsp.meters?.let { it.copy(quality = it.quality.copy(status = TelemetryStatus.LOST)) }))
            if (pending != null && next.pending == null) {
                scheduled = null
                outcomeFeed.emit(outcome(pending.command, pending.requestId, AudioCommandStatus.DROPPED, next.feedback ?: "Command dropped", now()))
            }
            stateFeed.emit(next)
        } else {
            val q = TelemetryQuality(TelemetryStatus.LIVE, now(), "LOCAL_AUDIO_DEMO", demo = true)
            var next = when (target) {
                AudioOfflineTarget.SOURCE -> state.value.copy(source = state.value.source.copy(connected = true, active = true, quality = q))
                AudioOfflineTarget.DSP -> state.value.copy(dsp = state.value.dsp.copy(state = DspState.RUNNING, quality = q))
                AudioOfflineTarget.USB -> state.value.copy(dsp = state.value.dsp.copy(hardwareAvailable = true))
            }
            if (next.dsp.state == DspState.RUNNING && next.dsp.hardwareAvailable == true) next = AudioModel.meters(next, meterFrame())
            stateFeed.emit(next.copy(feedback = "$target RESTORED · NO COMMANDS REPLAYED"))
        }
        lastPlaybackEpochMs = now()
    }
    override fun close() {
        if (closed) return
        closed = true
        try {
            try { dropPending("SOURCE CLOSED · PENDING COMMAND DROPPED") }
            finally { demoFeed.emit(AudioDemoState()) }
        } finally {
            stateFeed.close(); outcomeFeed.close(); demoFeed.close()
        }
    }
    private fun dropPending(message: String) {
        scheduled = null
        val pending = state.value.pending ?: return
        val stamp = now()
        try { stateFeed.emit(AudioModel.finishWrite(state.value, pending.requestId, false, stamp).copy(feedback = message)) }
        finally { outcomeFeed.emit(outcome(pending.command, pending.requestId, AudioCommandStatus.DROPPED, message, stamp)) }
    }
    private fun outcome(command: AudioCommand, id: String?, status: AudioCommandStatus, message: String, stamp: Long) =
        AudioCommandOutcome(identity.sessionId, id, command, status, stamp, message)
    private fun stamped(q: TelemetryQuality, stamp: Long) = q.copy(timestampEpochMs = stamp, source = "LOCAL_AUDIO_DEMO", demo = true)
    private fun meterFrame(clipping: Boolean = false) = AudioMeterFrame(
        listOf(-18.0, -19.0, -28.0, -29.0, -22.0).mapIndexed { index, peak ->
            AudioMeter(index, if (clipping && index == 0) 0.0 else peak, peak - 7, clipping && index == 0, if (clipping && index == 0) 0.0 else peak + 2)
        }, TelemetryQuality(TelemetryStatus.LIVE, now(), "LOCAL_AUDIO_DEMO_FIXED_METER_FIXTURE", demo = true), routingVerified = false)
}

/** Honest placeholder until the Pi's real protocol and read/write capabilities are confirmed. */
class UnavailableAudioSource(
    override val identity: SourceIdentity,
    reason: String = "PI AUDIO UNAVAILABLE · ADAPTER NOT IMPLEMENTED",
) : AudioSourcePort {
    private val stateFeed = MutableSourceFeed(AudioModel.initial().copy(sessionId = identity.sessionId, feedback = reason))
    private val outcomeFeed = MutableSourceFeed<AudioCommandOutcome?>(null)
    private var closed = false
    override val state: SourceFeed<AudioState> get() = stateFeed
    override val outcomes: SourceFeed<AudioCommandOutcome?> get() = outcomeFeed
    override fun available(command: AudioCommand) = false
    override fun submit(command: AudioCommand): AudioCommandOutcome {
        val result = AudioCommandOutcome(identity.sessionId, null, command, AudioCommandStatus.REJECTED, 0,
            if (closed) "Source closed; no command sent" else "Pi adapter unavailable; no command sent")
        if (!closed) { stateFeed.emit(state.value.copy(feedback = result.message)); outcomeFeed.emit(result) }
        return result
    }
    override fun tick() = Unit
    override fun close() { if (!closed) { closed = true; stateFeed.close(); outcomeFeed.close() } }
}
