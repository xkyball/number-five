package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class GatewayTest {
    private val command = AudioCommand.Fader(AudioChannel.MASTER, -18.0)
    private fun report(session: String = "boot-1", source: String = "VESSEL_ADAPTER") = GatewayEnvelope(
        GatewayCapabilities(setOf(GatewayCapability.AUDIO_FADER_WRITE, GatewayCapability.AUDIO_MUTE, GatewayCapability.AUDIO_PRESET_WRITE, GatewayCapability.AUDIO_PLAY_PAUSE), 130_000, VolumeAuthority.CAMILLA_DSP_MASTER, AudioModel.DEMO_LIMITS, setOf(AudioModel.PRESET), true, true, true), session, "hello-1", 100_000, source)
    private fun ready() = GatewaySession().also { assertNull(it.negotiate(report(), 100_000)) }
    private fun ack(request: GatewayEnvelope<GatewayCommand>, status: GatewayAckStatus = GatewayAckStatus.VERIFIED, session: String = request.sessionId, source: String = "VESSEL_ADAPTER") = GatewayEnvelope(GatewayAck(status), session, request.requestId, 100_001, source)
    @Test fun offlineCommandsAreRefusedAndNotQueued() {
        val s = GatewaySession(); assertEquals(GatewayRejection.OFFLINE, (s.prepare(command, 100_000) as GatewayAdmission.Rejected).reason); assertNull(s.pending)
    }
    @Test fun versionAndMetadataAndCapabilitiesAreRequired() {
        val s = GatewaySession(); assertEquals(GatewayRejection.VERSION_MISMATCH, s.negotiate(report().copy(version = 99), 100_000))
        assertEquals(GatewayRejection.INVALID_ENVELOPE, s.negotiate(report().copy(source = ""), 100_000))
        assertEquals(GatewayRejection.STALE_CAPABILITIES, s.negotiate(report(), 131_000)); assertFalse(s.connected)
    }
    @Test fun capabilityAuthorityAndValidatedLimitsGateWrites() {
        val s = GatewaySession(); var r = report(); s.negotiate(r.copy(payload = r.payload.copy(capabilities = emptySet())), 100_000)
        assertEquals(GatewayRejection.CAPABILITY_UNAVAILABLE, (s.prepare(command, 100_000) as GatewayAdmission.Rejected).reason)
        s.negotiate(r.copy(payload = r.payload.copy(volumeAuthority = VolumeAuthority.NONE)), 100_000)
        assertEquals(GatewayRejection.AUTHORITY_UNAVAILABLE, (s.prepare(command, 100_000) as GatewayAdmission.Rejected).reason)
        s.negotiate(r.copy(payload = r.payload.copy(faderLimits = emptyMap())), 100_000)
        assertEquals(GatewayRejection.CAPABILITY_UNAVAILABLE, (s.prepare(command, 100_000) as GatewayAdmission.Rejected).reason)
        s.negotiate(r, 100_000)
        assertEquals(GatewayRejection.INVALID_ARGUMENT, (s.prepare(AudioCommand.Fader(AudioChannel.MASTER, 0.0), 100_000) as GatewayAdmission.Rejected).reason)
        assertEquals(GatewayRejection.INVALID_ARGUMENT, (s.prepare(AudioCommand.Fader(AudioChannel.MASTER, -18.3), 100_000) as GatewayAdmission.Rejected).reason)
        assertEquals(GatewayRejection.INVALID_ARGUMENT, (s.prepare(AudioCommand.Preset("UNKNOWN"), 100_000) as GatewayAdmission.Rejected).reason)
    }
    @Test fun preparedRequestCarriesVersionUnitTimestampIdAndTimeout() {
        val s = ready(); val prepared = (s.prepare(command, 100_000) as GatewayAdmission.Prepared).envelope
        assertEquals(1, prepared.version); assertEquals(MeasurementUnit.DB, prepared.payload.unit); assertEquals(100_000L, prepared.timestampEpochMs)
        assertTrue(prepared.requestId.isNotBlank()); assertEquals(105_000L, prepared.payload.deadlineEpochMs)
        assertEquals(GatewayRejection.BUSY, (s.prepare(command, 100_001) as GatewayAdmission.Rejected).reason)
    }
    @Test fun receiptIsNotVerificationAndExactAckIsRequired() {
        val s = ready(); val request = (s.prepare(command, 100_000) as GatewayAdmission.Prepared).envelope
        assertFalse(s.acknowledge(ack(request, GatewayAckStatus.ACCEPTED), 100_001).verified); assertNotNull(s.pending)
        assertEquals(GatewayRejection.STALE_ACK, s.acknowledge(ack(request, source = "OTHER"), 100_001).rejection); assertNotNull(s.pending)
        assertTrue(s.acknowledge(ack(request), 100_001).verified); assertNull(s.pending)
        assertEquals(GatewayRejection.STALE_ACK, s.acknowledge(ack(request), 100_002).rejection)
    }
    @Test fun timeoutRejectsLateSuccessAndNoAutomaticReplayExists() {
        val s = ready(); val request = (s.prepare(command, 100_000) as GatewayAdmission.Prepared).envelope
        assertNull(s.expire(104_999)); assertEquals(GatewayRejection.TIMEOUT, s.expire(105_000)!!.rejection)
        assertEquals(GatewayRejection.STALE_ACK, s.acknowledge(ack(request), 105_001).rejection); assertNull(s.pending)
    }
    @Test fun lossAndNewSessionDropPendingCommands() {
        val s = ready(); val old = (s.prepare(command, 100_000) as GatewayAdmission.Prepared).envelope
        assertEquals(GatewayRejection.OFFLINE, s.disconnect()!!.rejection); assertNull(s.pending)
        assertNull(s.negotiate(report("boot-2"), 100_001)); val next = (s.prepare(command, 100_001) as GatewayAdmission.Prepared).envelope
        assertEquals(GatewayRejection.STALE_ACK, s.acknowledge(ack(old), 100_002).rejection); assertEquals(next.requestId, s.pending!!.requestId)
        s.negotiate(report("boot-3"), 100_002); assertNull(s.pending)
    }
    @Test fun backendRejectionsAreTypedAndTerminal() {
        val s = ready(); val request = (s.prepare(command, 100_000) as GatewayAdmission.Prepared).envelope
        val rejected = GatewayEnvelope(GatewayAck(GatewayAckStatus.REJECTED, GatewayRejection.BACKEND_REJECTED, "Hardware read-back mismatch"), request.sessionId, request.requestId, 100_001, "VESSEL_ADAPTER")
        assertEquals(GatewayRejection.BACKEND_REJECTED, s.acknowledge(rejected, 100_001).rejection); assertNull(s.pending)
    }
    @Test fun capabilityWithdrawalDropsPendingAndRejectsLateVerification() {
        val s = ready(); val request = (s.prepare(command, 100_000) as GatewayAdmission.Prepared).envelope
        val report = report()
        s.negotiate(report.copy(payload = report.payload.copy(dspOnline = false)), 100_001)
        assertNull(s.pending)
        assertEquals(GatewayRejection.STALE_ACK, s.acknowledge(ack(request), 100_002).rejection)
        assertEquals(GatewayRejection.OFFLINE, (s.prepare(command, 100_002) as GatewayAdmission.Rejected).reason)
    }
    @Test fun unitsAndDemoSeparationPreventAmbiguousTelemetry() {
        val quality = TelemetryQuality(TelemetryStatus.LIVE, 100_000, "ADAPTER")
        assertThrows(IllegalArgumentException::class.java) { GatewayHelmTelemetry(mapOf(HelmMetric.PORT_RPM to Measurement(3400.0, MeasurementUnit.KNOTS, quality))) }
        assertThrows(IllegalArgumentException::class.java) { GatewayHelmTelemetry(mapOf(HelmMetric.PORT_RPM to Measurement(3400.0, MeasurementUnit.RPM, quality.copy(demo = true)))) }
        assertThrows(IllegalArgumentException::class.java) { GatewayHelmTelemetry(emptyMap(), "99:99") }
        val telemetry = GatewayHelmTelemetry(mapOf(HelmMetric.PORT_RPM to Measurement(0.0, MeasurementUnit.RPM, quality)))
        assertEquals(0.0, telemetry.values.getValue(HelmMetric.PORT_RPM).value!!, 0.0)
    }
}
