package com.no5.domain

import java.util.UUID
import kotlin.math.floor

enum class AudioChannel { MASTER, SUB, REAR, AUX3, AUX4, FRONT }
enum class DspState { STOPPED, RUNNING, APPLYING_PRESET, ERROR }
enum class MediaState { STOPPED, PAUSED, PLAYING }
enum class AudioWriteState { LOCKED, DEMO, PENDING, VERIFIED }
enum class VolumeAuthority { NONE, CAMILLA_DSP_MASTER }
enum class AudioOfflineTarget { SOURCE, DSP, USB }
enum class AudioFixture { CANONICAL, DEMO, SOURCE_OFFLINE, DSP_OFFLINE, USB_OFFLINE, CLIPPING, DISPLAY_ONLY }
enum class TransportAction { PLAY, PAUSE, NEXT, PREVIOUS, SEEK, SHUFFLE, REPEAT }
data class FaderLimit(val minDb: Double, val maxDb: Double, val stepDb: Double = 1.0) {
    init { require(minDb.isFinite() && maxDb.isFinite() && stepDb.isFinite() && minDb <= maxDb && stepDb > 0) }
    fun clamp(value: Double): Double { require(value.isFinite()); return (floor(value / stepDb + .5) * stepDb).coerceIn(minDb, maxDb) }
}
data class AudioFader(val db: Double? = null, val muted: Boolean = false, val writeState: AudioWriteState = AudioWriteState.LOCKED)
/**
 * A CamillaDSP control advertised by the gateway.  [gatewayId] is protocol-only;
 * product text must always use [label].  [physicalIndex] orders actual Camilla
 * controls and never implies a boat-zone meaning.
 */
data class AudioFaderControl(
    val gatewayId: AudioChannel,
    val physicalIndex: Int,
    val label: String,
    val db: Double?,
    val muted: Boolean,
    val limit: FaderLimit?,
) {
    init {
        require(physicalIndex >= 0)
        require(label.isNotBlank() && label.length <= 80 && label.none(Char::isISOControl))
        require(db == null || db.isFinite())
    }
}
/** A server-validated CamillaDSP preset. [id] is the only value permitted on the write wire. */
data class AudioPreset(val id: String, val name: String, val active: Boolean = false) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        require(name.isNotBlank() && name.length <= 200 && name.none(Char::isISOControl))
    }
}
/** Narrow, observed master-tone bank. Its generation excludes its own gains/headroom. */
data class AudioEqualizer(
    val bankId: String, val configGeneration: String, val frequenciesHz: List<Double>, val gainsDb: List<Double>,
    val minDb: Double, val maxDb: Double, val stepDb: Double, val headroomDb: Double,
    val writable: Boolean, val observedAtEpochMs: Long, val verification: String = "ACTIVE_CONFIG_READBACK",
) {
    init {
        require(bankId == "master" && configGeneration.matches(Regex("[0-9a-f]{64}")))
        require(frequenciesHz == FREQUENCIES_HZ && gainsDb.size == FREQUENCIES_HZ.size)
        require(minDb == -6.0 && maxDb == 6.0 && stepDb == 1.0)
        require(gainsDb.all { validGain(it) })
        require(headroomDb.isFinite() && headroomDb >= 0 && observedAtEpochMs > 0)
        require(verification == "ACTIVE_CONFIG_READBACK")
    }
    fun validGain(value: Double) = value.isFinite() && value in minDb..maxDb && kotlin.math.abs(value / stepDb - kotlin.math.round(value / stepDb)) < .000001
    fun clamp(value: Double): Double { require(value.isFinite()); return (floor(value / stepDb + .5) * stepDb).coerceIn(minDb, maxDb) }
    fun admits(command: AudioCommand.Equalizer) = writable && command.bankId == bankId && command.configGeneration == configGeneration &&
        command.gainsDb.size == gainsDb.size && command.gainsDb.all(::validGain)
    companion object { val FREQUENCIES_HZ = listOf(63.0, 250.0, 1000.0, 4000.0, 12000.0) }
}
data class AudioCapabilities(val playPause: Boolean = false, val nextPrevious: Boolean = false, val seek: Boolean = false, val shuffleRepeat: Boolean = false, val faderWrite: Boolean = false, val mute: Boolean = false, val presetWrite: Boolean = false, val equalizer: Boolean = false)
data class AudioSource(val connected: Boolean = false, val active: Boolean = false, val name: String? = null, val quality: TelemetryQuality = TelemetryQuality())
data class AudioMedia(val state: MediaState = MediaState.STOPPED, val title: String? = null, val artists: List<String> = emptyList(), val durationMs: Long? = null, val positionMs: Long? = null, val shuffle: Boolean? = null, val repeat: Boolean? = null,
    val trackId: String? = null, val artworkId: String? = null, val album: String? = null)
/** Identifiers select bundled procedural artwork. They are never URLs or filesystem paths. */
object AudioArtworkCatalog {
    val ids: Set<String> = setOf("tidal-lines", "harbor-lights", "orange-current")
}
data class AudioMeter(val index: Int, val peakDb: Double, val rmsDb: Double, val clip: Boolean, val peakHoldDb: Double = peakDb, val label: String? = null)
data class AudioMeterFrame(val channels: List<AudioMeter>, val quality: TelemetryQuality, val routingVerified: Boolean = false, val groups: Map<AudioChannel, List<Int>> = emptyMap())
data class AudioDsp(val state: DspState = DspState.STOPPED, val preset: String? = null, val faders: Map<AudioChannel, AudioFader> = AudioChannel.entries.associateWith { AudioFader() }, val quality: TelemetryQuality = TelemetryQuality(), val hardwareAvailable: Boolean? = null, val clippedSamples: Long? = null, val meters: AudioMeterFrame? = null,
    val faderControls: List<AudioFaderControl> = emptyList(), val presets: List<AudioPreset> = emptyList(), val equalizer: AudioEqualizer? = null)
sealed interface AudioCommand {
    data class Fader(val channel: AudioChannel, val valueDb: Double) : AudioCommand
    data class Mute(val channel: AudioChannel, val muted: Boolean) : AudioCommand
    data class Preset(val id: String) : AudioCommand
    data class Equalizer(val bankId: String, val configGeneration: String, val gainsDb: List<Double>) : AudioCommand
    data class Transport(val action: TransportAction, val positionMs: Long? = null) : AudioCommand
}
data class AudioPending(val requestId: String, val command: AudioCommand, val previousDsp: AudioDsp, val previousMedia: AudioMedia, val deadlineEpochMs: Long)
data class AudioState(
    val mode: OperatingMode = OperatingMode.UNAVAILABLE,
    val source: AudioSource = AudioSource(), val media: AudioMedia = AudioMedia(), val dsp: AudioDsp = AudioDsp(),
    val capabilities: AudioCapabilities = AudioCapabilities(), val authority: VolumeAuthority = VolumeAuthority.NONE,
    val limits: Map<AudioChannel, FaderLimit>? = null, val pending: AudioPending? = null,
    val feedback: String? = null, val revision: Long = 0, val sessionId: String = UUID.randomUUID().toString(),
)
data class AudioWriteResult(val state: AudioState, val accepted: Boolean, val requestId: String? = null, val error: String? = null)
/** Deterministic state reducer apart from unique local request/session IDs. Never sends commands. */
object AudioModel {
    /** Stable demo wire identifier; [PRESET_NAME] remains display-only text. */
    const val PRESET = "demo-stereo-4-1"
    const val PRESET_NAME = "No 5 - Stereo 4.1"
    val DEMO_LIMITS: Map<AudioChannel, FaderLimit> = AudioChannel.entries.associateWith { if (it == AudioChannel.MASTER) FaderLimit(-60.0, -6.0) else FaderLimit(-18.0, 0.0) }
    fun initial(): AudioState = AudioState()
    fun fixture(name: AudioFixture): AudioState {
        if (name == AudioFixture.CANONICAL) return initial()
        val quality = TelemetryQuality(TelemetryStatus.LIVE, source = "LOCAL_AUDIO_DEMO", demo = true)
        val s = AudioState(mode = OperatingMode.DEMO, source = AudioSource(true, true, "SPOTIFY · DEMO", quality),
            media = AudioMedia(MediaState.PAUSED, "Open water", listOf("No 5 listening session"), 247_000, 83_000, false, false,
                trackId = "open-water", artworkId = "tidal-lines", album = "No 5 · Imaginary voyages"),
            dsp = demoDsp(quality),
            capabilities = AudioCapabilities(true, true, true, true, true, true, true), authority = VolumeAuthority.CAMILLA_DSP_MASTER, limits = DEMO_LIMITS)
        return when (name) {
            AudioFixture.SOURCE_OFFLINE -> offline(s, AudioOfflineTarget.SOURCE)
            AudioFixture.DSP_OFFLINE -> offline(s, AudioOfflineTarget.DSP)
            AudioFixture.USB_OFFLINE -> offline(s, AudioOfflineTarget.USB)
            AudioFixture.CLIPPING -> s.copy(dsp = s.dsp.copy(clippedSamples = 32))
            AudioFixture.DISPLAY_ONLY -> s.copy(source = s.source.copy(name = "RASPOTIFY · DEMO"), capabilities = s.capabilities.copy(playPause = false, nextPrevious = false, seek = false, shuffleRepeat = false))
            else -> s
        }
    }
    private fun demoDsp(quality: TelemetryQuality): AudioDsp {
        val faders = AudioChannel.entries.associateWith { channel ->
            AudioFader(
                db = when (channel) {
                    AudioChannel.MASTER -> -24.0
                    AudioChannel.SUB -> -6.0
                    AudioChannel.REAR -> -9.0
                    else -> -12.0
                },
                writeState = AudioWriteState.DEMO,
            )
        }
        val physical = listOf(AudioChannel.MASTER, AudioChannel.SUB, AudioChannel.REAR, AudioChannel.AUX3, AudioChannel.AUX4)
        return AudioDsp(
            state = DspState.RUNNING,
            preset = PRESET_NAME,
            faders = faders,
            quality = quality,
            hardwareAvailable = true,
            clippedSamples = 0,
            faderControls = physical.mapIndexed { index, channel ->
                AudioFaderControl(channel, index, if (index == 0) "Master" else "Aux $index", faders.getValue(channel).db,
                    faders.getValue(channel).muted, DEMO_LIMITS.getValue(channel))
            },
            presets = listOf(AudioPreset(PRESET, PRESET_NAME, active = true)),
        )
    }
    fun available(s: AudioState, command: AudioCommand): Boolean {
        if (s.mode !in setOf(OperatingMode.DEMO, OperatingMode.LIVE)) return false
        fun fresh(q: TelemetryQuality) = q.status == TelemetryStatus.LIVE &&
            (s.mode == OperatingMode.DEMO || (q.timestampEpochMs != null && !q.source.isNullOrBlank() && !q.demo))
        return when (command) {
            is AudioCommand.Transport -> s.source.connected && fresh(s.source.quality) && when (command.action) {
                TransportAction.PLAY, TransportAction.PAUSE -> s.capabilities.playPause
                TransportAction.NEXT, TransportAction.PREVIOUS -> s.capabilities.nextPrevious
                TransportAction.SEEK -> s.capabilities.seek
                TransportAction.SHUFFLE, TransportAction.REPEAT -> s.capabilities.shuffleRepeat
            }
            else -> s.dsp.state == DspState.RUNNING && fresh(s.dsp.quality) && s.dsp.hardwareAvailable == true && s.authority == VolumeAuthority.CAMILLA_DSP_MASTER && when (command) {
                is AudioCommand.Fader -> s.capabilities.faderWrite && s.dsp.faders[command.channel]?.db != null && faderLimit(s, command.channel) != null
                is AudioCommand.Mute -> s.capabilities.mute && s.dsp.faders.containsKey(command.channel)
                is AudioCommand.Preset -> s.capabilities.presetWrite
                is AudioCommand.Equalizer -> s.capabilities.equalizer && s.dsp.equalizer?.admits(command) == true
                else -> false
            }
        }
    }
    fun startWrite(s: AudioState, command: AudioCommand, nowEpochMs: Long, timeoutMs: Long = 5_000): AudioWriteResult {
        fun refused(reason: String) = AudioWriteResult(s, false, error = reason)
        if (s.pending != null) return refused("A write is already pending; no command queued")
        if (!available(s.withAudioFreshness(nowEpochMs), command)) return refused("Capability unavailable; no command sent")
        if (timeoutMs !in 1..30_000 || nowEpochMs > Long.MAX_VALUE - timeoutMs) return refused("Invalid timeout")
        var next = s
        when (command) {
            is AudioCommand.Fader -> {
                val limit = faderLimit(s, command.channel) ?: return refused("Validated fader limits unavailable")
                if (!command.valueDb.isFinite()) return refused("Invalid fader value")
                val fader = s.dsp.faders[command.channel] ?: return refused("Unknown fader")
                next = next.copy(dsp = updateControl(s.dsp, command.channel, fader.copy(db = limit.clamp(command.valueDb), writeState = AudioWriteState.PENDING)))
            }
            is AudioCommand.Mute -> {
                val fader = s.dsp.faders[command.channel] ?: return refused("Unknown fader")
                next = next.copy(dsp = updateControl(s.dsp, command.channel, fader.copy(muted = command.muted, writeState = AudioWriteState.PENDING)))
            }
            is AudioCommand.Preset -> { if (s.dsp.presets.none { it.id == command.id }) return refused("Preset not validated"); next = next.copy(dsp = s.dsp.copy(state = DspState.APPLYING_PRESET)) }
            is AudioCommand.Equalizer -> Unit // A target is not an observed DSP value.
            is AudioCommand.Transport -> {
                val m = s.media
                val updated = when (command.action) {
                    TransportAction.PLAY -> m.copy(state = MediaState.PLAYING)
                    TransportAction.PAUSE -> m.copy(state = MediaState.PAUSED)
                    TransportAction.NEXT, TransportAction.PREVIOUS -> m.copy(positionMs = 0)
                    TransportAction.SEEK -> { val duration = m.durationMs ?: return refused("Seek unavailable"); val pos = command.positionMs ?: return refused("Missing seek position"); if (duration < 0) return refused("Invalid duration"); m.copy(positionMs = pos.coerceIn(0, duration)) }
                    TransportAction.SHUFFLE -> m.copy(shuffle = !(m.shuffle ?: false))
                    TransportAction.REPEAT -> m.copy(repeat = !(m.repeat ?: false))
                }
                next = next.copy(media = updated)
            }
        }
        val revision = s.revision + 1
        val id = "${s.sessionId}:$revision"
        next = next.copy(revision = revision, pending = AudioPending(id, command, s.dsp, s.media, nowEpochMs + timeoutMs), feedback = "${if (s.mode == OperatingMode.DEMO) "DEMO" else "ADAPTER"} · APPLYING / VERIFYING")
        return AudioWriteResult(next, true, id)
    }
    /** Only the exact current request may complete. A delayed ack cannot replay a dropped write. */
    fun finishWrite(s: AudioState, requestId: String, verified: Boolean, nowEpochMs: Long): AudioState {
        val p = s.pending?.takeIf { it.requestId == requestId } ?: return s
        val origin = if (s.mode == OperatingMode.DEMO) "DEMO" else "ADAPTER"
        if (!verified || nowEpochMs >= p.deadlineEpochMs) return restoreTouched(s, p).copy(pending = null, feedback = "$origin · WRITE FAILED / ROLLED BACK")
        var dsp = s.dsp
        val channel = when (val c = p.command) { is AudioCommand.Fader -> c.channel; is AudioCommand.Mute -> c.channel; else -> null }
        if (channel != null) dsp = updateControl(dsp, channel, dsp.faders.getValue(channel).copy(writeState = AudioWriteState.VERIFIED))
        if (p.command is AudioCommand.Preset) {
            val selected = dsp.presets.singleOrNull { it.id == p.command.id }
            dsp = dsp.copy(
                state = DspState.RUNNING,
                preset = selected?.name ?: p.command.id,
                presets = dsp.presets.map { it.copy(active = it.id == p.command.id) },
            )
        }
        if (p.command is AudioCommand.Equalizer) {
            val eq = dsp.equalizer?.takeIf { it.admits(p.command) } ?: return restoreTouched(s, p).copy(pending = null, feedback = "$origin · EQ GENERATION CHANGED")
            dsp = dsp.copy(equalizer = eq.copy(gainsDb = p.command.gainsDb.toList(), observedAtEpochMs = nowEpochMs))
        }
        return s.copy(dsp = dsp, pending = null, feedback = "$origin · VERIFIED BY ${if (s.mode == OperatingMode.DEMO) "LOCAL " else ""}ADAPTER")
    }
    fun expireWrite(s: AudioState, nowEpochMs: Long): AudioState = s.pending?.takeIf { nowEpochMs >= it.deadlineEpochMs }?.let { finishWrite(s, it.requestId, false, nowEpochMs) } ?: s
    private fun restoreTouched(s: AudioState, p: AudioPending): AudioState = when (val c = p.command) {
        is AudioCommand.Fader -> s.copy(dsp = restoreControl(s.dsp, p.previousDsp, c.channel))
        is AudioCommand.Mute -> s.copy(dsp = restoreControl(s.dsp, p.previousDsp, c.channel))
        is AudioCommand.Preset -> s.copy(dsp = s.dsp.copy(state = p.previousDsp.state, preset = p.previousDsp.preset))
        is AudioCommand.Equalizer -> s // No optimistic EQ mutation to roll back.
        is AudioCommand.Transport -> s.copy(media = p.previousMedia)
    }
    fun offline(s: AudioState, target: AudioOfflineTarget): AudioState {
        val cancel = target != AudioOfflineTarget.SOURCE || s.pending?.command is AudioCommand.Transport
        var next = if (cancel && s.pending != null) restoreTouched(s, s.pending).copy(pending = null, revision = s.revision + 1) else s
        val lost = TelemetryQuality(TelemetryStatus.LOST)
        next = when (target) {
            AudioOfflineTarget.SOURCE -> next.copy(source = next.source.copy(connected = false, active = false, quality = lost))
            AudioOfflineTarget.DSP -> next.copy(dsp = next.dsp.copy(state = DspState.ERROR, quality = lost))
            AudioOfflineTarget.USB -> next.copy(dsp = next.dsp.copy(hardwareAvailable = false))
        }
        return next.copy(feedback = "$target OFFLINE · AFFECTED COMMANDS DROPPED")
    }
    fun meters(s: AudioState, frame: AudioMeterFrame): AudioState {
        require(frame.channels.size == 5) { "Exactly five meter channels required" }
        if (frame.quality.status == TelemetryStatus.LIVE) require(frame.quality.timestampEpochMs != null && !frame.quality.source.isNullOrBlank()) { "Live meters require source and timestamp" }
        val channels = frame.channels.mapIndexed { i, m ->
            require(m.index == i && listOf(m.peakDb, m.rmsDb, m.peakHoldDb).all { it.isFinite() && it in -160.0..0.0 }) { "Invalid typed channel meter" }
            m.copy(label = if (frame.routingVerified && !m.label.isNullOrBlank()) m.label.take(32) else "OUTPUT ${i + 1}")
        }
        return s.copy(dsp = s.dsp.copy(meters = frame.copy(channels = channels)))
    }
    fun meterQuality(s: AudioState, nowEpochMs: Long): TelemetryStatus = s.dsp.meters?.let { QualityEvaluator.evaluate(it.quality, nowEpochMs, QualityEvaluator.AUDIO) } ?: TelemetryStatus.UNAVAILABLE

    /**
     * AUDIO V1's physical catalog is authoritative for a modern gateway. The
     * legacy top-level map remains a compatibility fallback only; a real
     * network source separately refuses any uncatalogued control.
     */
    private fun faderLimit(s: AudioState, channel: AudioChannel): FaderLimit? =
        s.dsp.faderControls.singleOrNull { it.gatewayId == channel }?.limit ?: s.limits?.get(channel)

    private fun updateControl(dsp: AudioDsp, channel: AudioChannel, fader: AudioFader): AudioDsp = dsp.copy(
        faders = dsp.faders + (channel to fader),
        faderControls = dsp.faderControls.map { control -> if (control.gatewayId == channel) control.copy(db = fader.db, muted = fader.muted) else control },
    )
    private fun restoreControl(current: AudioDsp, previous: AudioDsp, channel: AudioChannel): AudioDsp {
        val fader = previous.faders.getValue(channel)
        val prior = previous.faderControls.firstOrNull { it.gatewayId == channel }
        return current.copy(
            faders = current.faders + (channel to fader),
            faderControls = current.faderControls.map { control -> if (control.gatewayId == channel && prior != null) prior else control },
        )
    }
}

/**
 * Explains why a gateway snapshot cannot admit a command. The code prefix is
 * stable and carries no endpoint, header, or key material, so it is safe to
 * expose in the app-local command diagnostic.
 *
 * The caller must pass a state already evaluated with its freshness policy.
 * A null result only means the state-level gate is open; an adapter can still
 * reject a missing session or an unvalidated catalog/value at its boundary.
 */
object AudioCommandGate {
    fun reason(s: AudioState, command: AudioCommand): String? {
        if (s.mode !in setOf(OperatingMode.DEMO, OperatingMode.LIVE)) return "AUDIO_GATE_MODE_${s.mode.name}"
        fun fresh(q: TelemetryQuality) = q.status == TelemetryStatus.LIVE &&
            (s.mode == OperatingMode.DEMO || (q.timestampEpochMs != null && !q.source.isNullOrBlank() && !q.demo))
        return when (command) {
            is AudioCommand.Transport -> when {
                !s.source.connected -> "AUDIO_GATE_SOURCE_DISCONNECTED"
                !fresh(s.source.quality) -> "AUDIO_GATE_SOURCE_QUALITY_${s.source.quality.status.name}"
                command.action in setOf(TransportAction.PLAY, TransportAction.PAUSE) && !s.capabilities.playPause -> "AUDIO_GATE_PLAY_PAUSE_UNAVAILABLE"
                command.action in setOf(TransportAction.NEXT, TransportAction.PREVIOUS) && !s.capabilities.nextPrevious -> "AUDIO_GATE_NEXT_PREVIOUS_UNAVAILABLE"
                command.action == TransportAction.SEEK && !s.capabilities.seek -> "AUDIO_GATE_SEEK_UNAVAILABLE"
                command.action in setOf(TransportAction.SHUFFLE, TransportAction.REPEAT) && !s.capabilities.shuffleRepeat -> "AUDIO_GATE_SHUFFLE_REPEAT_UNAVAILABLE"
                else -> null
            }
            else -> when {
                s.dsp.state != DspState.RUNNING -> "AUDIO_GATE_DSP_${s.dsp.state.name}"
                !fresh(s.dsp.quality) -> "AUDIO_GATE_DSP_QUALITY_${s.dsp.quality.status.name}"
                s.dsp.hardwareAvailable != true -> "AUDIO_GATE_DSP_HARDWARE_UNAVAILABLE"
                s.authority != VolumeAuthority.CAMILLA_DSP_MASTER -> "AUDIO_GATE_CAMILLA_AUTHORITY_${s.authority.name}"
                command is AudioCommand.Fader && !s.capabilities.faderWrite -> "AUDIO_GATE_FADER_WRITE_UNAVAILABLE"
                command is AudioCommand.Fader && (s.dsp.faders[command.channel]?.db == null || faderLimit(s, command.channel) == null) -> "AUDIO_GATE_FADER_STATE_UNAVAILABLE"
                command is AudioCommand.Mute && !s.capabilities.mute -> "AUDIO_GATE_MUTE_UNAVAILABLE"
                command is AudioCommand.Mute && !s.dsp.faders.containsKey(command.channel) -> "AUDIO_GATE_MUTE_STATE_UNAVAILABLE"
                command is AudioCommand.Preset && !s.capabilities.presetWrite -> "AUDIO_GATE_PRESET_WRITE_UNAVAILABLE"
                command is AudioCommand.Equalizer && !s.capabilities.equalizer -> "AUDIO_GATE_EQUALIZER_UNAVAILABLE"
                command is AudioCommand.Equalizer && s.dsp.equalizer?.admits(command) != true -> "AUDIO_GATE_EQUALIZER_GENERATION_OR_TARGET_INVALID"
                else -> null
            }
        }
    }

    private fun faderLimit(s: AudioState, channel: AudioChannel): FaderLimit? =
        s.dsp.faderControls.singleOrNull { it.gatewayId == channel }?.limit ?: s.limits?.get(channel)
}
