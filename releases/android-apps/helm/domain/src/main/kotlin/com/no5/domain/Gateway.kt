package com.no5.domain

import java.util.UUID

/** Draft integration contract. A production gateway/transport is intentionally absent. */
object GatewayProtocol { const val NAMESPACE = "no5-native-gateway-draft"; const val VERSION = 1 }
enum class GatewayCapability { HELM_TELEMETRY, AUDIO_METERS, AUDIO_FADER_WRITE, AUDIO_MUTE, AUDIO_PRESET_WRITE, AUDIO_PLAY_PAUSE, AUDIO_NEXT_PREVIOUS, AUDIO_SEEK, AUDIO_SHUFFLE_REPEAT, SONAR_BINARY, SONAR_CALIBRATION, SONAR_BOTTOM, AUDIO_EQUALIZER_WRITE }
enum class MeasurementUnit { RPM, PERCENT, DEGREES_TRUE, KNOTS, METERS, CELSIUS, VOLTS, DB, MILLISECONDS, MICROSECONDS, METERS_PER_SECOND, UINT16_AMPLITUDE }
enum class HelmMetric(val unit: MeasurementUnit, val range: ClosedFloatingPointRange<Double>) {
    PORT_RPM(MeasurementUnit.RPM, 0.0..15000.0), STARBOARD_RPM(MeasurementUnit.RPM, 0.0..15000.0),
    PORT_TRIM(MeasurementUnit.PERCENT, 0.0..100.0), STARBOARD_TRIM(MeasurementUnit.PERCENT, 0.0..100.0),
    HEADING(MeasurementUnit.DEGREES_TRUE, 0.0..359.999999), SPEED(MeasurementUnit.KNOTS, 0.0..200.0),
    DEPTH(MeasurementUnit.METERS, 0.0..12000.0), WATER_TEMPERATURE(MeasurementUnit.CELSIUS, -5.0..60.0),
    PORT_VOLTAGE(MeasurementUnit.VOLTS, 0.0..60.0), STARBOARD_VOLTAGE(MeasurementUnit.VOLTS, 0.0..60.0), HOUSE_VOLTAGE(MeasurementUnit.VOLTS, 0.0..60.0),
    PORT_FUEL(MeasurementUnit.PERCENT, 0.0..100.0), STARBOARD_FUEL(MeasurementUnit.PERCENT, 0.0..100.0),
}
data class Measurement(val value: Double?, val unit: MeasurementUnit, val quality: TelemetryQuality) {
    init { require(value == null || value.isFinite()) }
}
sealed interface GatewayPayload
data class GatewayHello(val supportedVersions: Set<Int> = setOf(GatewayProtocol.VERSION)) : GatewayPayload
data class GatewayCapabilities(
    val capabilities: Set<GatewayCapability>, val validUntilEpochMs: Long,
    val volumeAuthority: VolumeAuthority = VolumeAuthority.NONE,
    val faderLimits: Map<AudioChannel, FaderLimit> = emptyMap(),
    val validatedPresets: Set<String> = emptySet(),
    val sourceOnline: Boolean = false, val dspOnline: Boolean = false, val outputHardwareOnline: Boolean = false,
    val equalizer: AudioEqualizer? = null,
) : GatewayPayload
data class GatewayHelmTelemetry(val values: Map<HelmMetric, Measurement>, val vesselClock: String? = null) : GatewayPayload {
    init {
        require(values.all { (metric, measurement) -> measurement.unit == metric.unit && (measurement.value == null || measurement.value in metric.range) && !measurement.quality.demo })
        require(vesselClock == null || Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9])?").matches(vesselClock))
    }
}
/** Sonar data use the binary adapter path; no JSON array reinterpretation is permitted. */
data class GatewaySonarTelemetry(val frame: SonarFrame, val sampleUnit: MeasurementUnit = MeasurementUnit.UINT16_AMPLITUDE) : GatewayPayload { init { require(sampleUnit == MeasurementUnit.UINT16_AMPLITUDE) } }
data class GatewayAudioMeters(val frame: AudioMeterFrame, val unit: MeasurementUnit = MeasurementUnit.DB) : GatewayPayload { init { require(unit == MeasurementUnit.DB && !frame.quality.demo) } }
data class GatewayCommand(val audio: AudioCommand, val deadlineEpochMs: Long, val unit: MeasurementUnit? = when (audio) { is AudioCommand.Fader -> MeasurementUnit.DB; is AudioCommand.Transport -> if (audio.action == TransportAction.SEEK) MeasurementUnit.MILLISECONDS else null; else -> null }) : GatewayPayload
/** VERIFIED requires backend read-back of the applied value; receipt alone must use ACCEPTED. */
enum class GatewayAckStatus { ACCEPTED, VERIFIED, REJECTED }
enum class GatewayRejection { OFFLINE, VERSION_MISMATCH, INVALID_ENVELOPE, STALE_CAPABILITIES, CAPABILITY_UNAVAILABLE, AUTHORITY_UNAVAILABLE, INVALID_ARGUMENT, BUSY, TIMEOUT, BACKEND_REJECTED, STALE_ACK, SESSION_CHANGED }
data class GatewayAck(val status: GatewayAckStatus, val rejection: GatewayRejection? = null, val detail: String? = null) : GatewayPayload {
    init { require((status == GatewayAckStatus.REJECTED) == (rejection != null)); require(detail == null || detail.length <= 256) }
}
data class GatewayEnvelope<out P : GatewayPayload>(
    val payload: P, val sessionId: String, val requestId: String, val timestampEpochMs: Long, val source: String,
    val namespace: String = GatewayProtocol.NAMESPACE, val version: Int = GatewayProtocol.VERSION,
) {
    fun validMetadata(): Boolean = namespace == GatewayProtocol.NAMESPACE && version == GatewayProtocol.VERSION && sessionId.isNotBlank() && sessionId.length <= 128 && requestId.isNotBlank() && requestId.length <= 128 && source.isNotBlank() && source.length <= 80
}
sealed interface GatewayAdmission {
    data class Prepared(val envelope: GatewayEnvelope<GatewayCommand>) : GatewayAdmission
    data class Rejected(val reason: GatewayRejection, val detail: String) : GatewayAdmission
}
data class GatewayCompletion(val requestId: String, val verified: Boolean, val rejection: GatewayRejection? = null)
/** Implement only after adapter authentication, source validation and vessel limits are specified. */
interface LiveGateway {
    fun connect(onEnvelope: (GatewayEnvelope<GatewayPayload>) -> Unit)
    fun send(envelope: GatewayEnvelope<GatewayCommand>)
    fun close()
}
/** Local capability gate and acknowledgement tracker. Does not implement LiveGateway or transmit. */
class GatewaySession(private val clientSource: String = "NO5_ANDROID") {
    var connected: Boolean = false; private set
    var sessionId: String? = null; private set
    var capabilities: GatewayCapabilities? = null; private set
    var pending: GatewayEnvelope<GatewayCommand>? = null; private set
    private var gatewaySource: String? = null
    fun negotiate(envelope: GatewayEnvelope<GatewayCapabilities>, nowEpochMs: Long): GatewayRejection? {
        if (envelope.version != GatewayProtocol.VERSION) return GatewayRejection.VERSION_MISMATCH
        if (!envelope.validMetadata() || envelope.timestampEpochMs.toDouble() - nowEpochMs.toDouble() > 5_000) return GatewayRejection.INVALID_ENVELOPE
        if (envelope.payload.validUntilEpochMs <= nowEpochMs || nowEpochMs.toDouble() - envelope.timestampEpochMs.toDouble() > 30_000) return GatewayRejection.STALE_CAPABILITIES
        // A changed session discards old pending writes; reconnect never replays them.
        if (sessionId != envelope.sessionId) pending = null
        pending?.let { request ->
            val c = request.payload.audio
            val grant = envelope.payload
            val withdrew = if (c is AudioCommand.Transport) !grant.sourceOnline else !grant.dspOnline || !grant.outputHardwareOnline || grant.volumeAuthority != VolumeAuthority.CAMILLA_DSP_MASTER
            if (withdrew || requiredCapability(c) !in grant.capabilities) pending = null
        }
        sessionId = envelope.sessionId; gatewaySource = envelope.source
        capabilities = envelope.payload.copy(capabilities = envelope.payload.capabilities.toSet(), faderLimits = envelope.payload.faderLimits.toMap(), validatedPresets = envelope.payload.validatedPresets.toSet(),
            equalizer = envelope.payload.equalizer?.let { it.copy(frequenciesHz = it.frequenciesHz.toList(), gainsDb = it.gainsDb.toList()) })
        connected = true
        return null
    }
    fun disconnect(): GatewayCompletion? {
        val dropped = pending?.let { GatewayCompletion(it.requestId, false, GatewayRejection.OFFLINE) }
        connected = false; pending = null; capabilities = null; sessionId = null; gatewaySource = null
        return dropped
    }
    private fun requiredCapability(audio: AudioCommand): GatewayCapability = when (audio) {
            is AudioCommand.Fader -> GatewayCapability.AUDIO_FADER_WRITE
            is AudioCommand.Mute -> GatewayCapability.AUDIO_MUTE
            is AudioCommand.Preset -> GatewayCapability.AUDIO_PRESET_WRITE
            is AudioCommand.Equalizer -> GatewayCapability.AUDIO_EQUALIZER_WRITE
            is AudioCommand.Transport -> when (audio.action) {
                TransportAction.PLAY, TransportAction.PAUSE -> GatewayCapability.AUDIO_PLAY_PAUSE
                TransportAction.NEXT, TransportAction.PREVIOUS -> GatewayCapability.AUDIO_NEXT_PREVIOUS
                TransportAction.SEEK -> GatewayCapability.AUDIO_SEEK
                TransportAction.SHUFFLE, TransportAction.REPEAT -> GatewayCapability.AUDIO_SHUFFLE_REPEAT
            }
        }

    fun prepare(audio: AudioCommand, nowEpochMs: Long, timeoutMs: Long = 5_000): GatewayAdmission {
        fun reject(reason: GatewayRejection, detail: String) = GatewayAdmission.Rejected(reason, detail)
        if (!connected) return reject(GatewayRejection.OFFLINE, "No verified gateway session; no command queued")
        if (pending != null) return reject(GatewayRejection.BUSY, "A write is already pending; no command queued")
        val report = capabilities ?: return reject(GatewayRejection.CAPABILITY_UNAVAILABLE, "No capabilities")
        if (report.validUntilEpochMs <= nowEpochMs) return reject(GatewayRejection.STALE_CAPABILITIES, "Refresh capabilities before writing")
        if (timeoutMs !in 1..30_000 || nowEpochMs > Long.MAX_VALUE - timeoutMs) return reject(GatewayRejection.INVALID_ARGUMENT, "Invalid timeout")
        val capability = requiredCapability(audio)
        if (capability !in report.capabilities) return reject(GatewayRejection.CAPABILITY_UNAVAILABLE, "Command capability was not negotiated")
        if (audio is AudioCommand.Transport) {
            if (!report.sourceOnline) return reject(GatewayRejection.OFFLINE, "Source offline")
            if (audio.action == TransportAction.SEEK && (audio.positionMs == null || audio.positionMs < 0)) return reject(GatewayRejection.INVALID_ARGUMENT, "Invalid seek position")
        } else {
            if (!report.dspOnline || !report.outputHardwareOnline) return reject(GatewayRejection.OFFLINE, "DSP/output unavailable")
            if (report.volumeAuthority != VolumeAuthority.CAMILLA_DSP_MASTER) return reject(GatewayRejection.AUTHORITY_UNAVAILABLE, "CamillaDSP Master must be the single volume authority")
        }
        if (audio is AudioCommand.Fader) {
            val limit = report.faderLimits[audio.channel] ?: return reject(GatewayRejection.CAPABILITY_UNAVAILABLE, "Vessel fader bounds not validated")
            if (!audio.valueDb.isFinite() || audio.valueDb !in limit.minDb..limit.maxDb || kotlin.math.abs(limit.clamp(audio.valueDb) - audio.valueDb) > .000001) return reject(GatewayRejection.INVALID_ARGUMENT, "Fader value outside validated limits or step")
        }
        if (audio is AudioCommand.Preset && audio.id !in report.validatedPresets) return reject(GatewayRejection.INVALID_ARGUMENT, "Preset was not validated by gateway")
        if (audio is AudioCommand.Equalizer && report.equalizer?.let {
                it.admits(audio) && nowEpochMs.toDouble() - it.observedAtEpochMs.toDouble() in -5_000.0..QualityEvaluator.AUDIO.staleAfterMs.toDouble()
            } != true) return reject(GatewayRejection.INVALID_ARGUMENT, "Equalizer bank/generation/target was not validated by gateway")
        val envelope = GatewayEnvelope(GatewayCommand(audio, nowEpochMs + timeoutMs), sessionId!!, UUID.randomUUID().toString(), nowEpochMs, clientSource)
        pending = envelope
        return GatewayAdmission.Prepared(envelope)
    }
    fun acknowledge(envelope: GatewayEnvelope<GatewayAck>, nowEpochMs: Long): GatewayCompletion {
        val request = pending
        if (!connected || request == null || !envelope.validMetadata() || envelope.sessionId != sessionId || envelope.source != gatewaySource || envelope.requestId != request.requestId || envelope.timestampEpochMs < request.timestampEpochMs || envelope.timestampEpochMs.toDouble() - nowEpochMs.toDouble() > 5_000) return GatewayCompletion(envelope.requestId, false, GatewayRejection.STALE_ACK)
        if (nowEpochMs >= request.payload.deadlineEpochMs) { pending = null; return GatewayCompletion(request.requestId, false, GatewayRejection.TIMEOUT) }
        if (envelope.payload.status == GatewayAckStatus.ACCEPTED) return GatewayCompletion(request.requestId, false)
        pending = null
        return GatewayCompletion(request.requestId, envelope.payload.status == GatewayAckStatus.VERIFIED, envelope.payload.rejection)
    }
    fun expire(nowEpochMs: Long): GatewayCompletion? {
        val request = pending ?: return null
        if (nowEpochMs < request.payload.deadlineEpochMs) return null
        pending = null
        return GatewayCompletion(request.requestId, false, GatewayRejection.TIMEOUT)
    }
}
