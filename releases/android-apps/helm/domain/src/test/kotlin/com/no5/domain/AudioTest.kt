package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class AudioTest {
    private fun demo() = AudioModel.fixture(AudioFixture.DEMO)
    private fun meterFrame(routing: Boolean = false) = AudioMeterFrame((0..4).map { AudioMeter(it, -12.0, -20.0, it == 0, label = listOf("FL", "FR", "RL", "RR", "SUB")[it]) }, TelemetryQuality(TelemetryStatus.LIVE, 100_000, "ADAPTER"), routing)
    @Test fun canonicalIsLockedWithoutInventedSourceLimitsOrMeters() {
        val s = AudioModel.initial(); assertNull(s.limits); assertNull(s.dsp.meters); assertFalse(s.source.connected)
        listOf(AudioCommand.Fader(AudioChannel.MASTER, 0.0), AudioCommand.Mute(AudioChannel.SUB, true), AudioCommand.Preset(AudioModel.PRESET), AudioCommand.Transport(TransportAction.PLAY)).forEach { assertFalse(AudioModel.startWrite(s, it, 100_000).accepted) }
    }
    @Test fun commandGateCodesDistinguishTransportAndCamillaPreflight() {
        val noPause = demo().copy(capabilities = demo().capabilities.copy(playPause = false))
        assertEquals("AUDIO_GATE_PLAY_PAUSE_UNAVAILABLE", AudioCommandGate.reason(noPause, AudioCommand.Transport(TransportAction.PAUSE)))
        val noFader = demo().copy(capabilities = demo().capabilities.copy(faderWrite = false))
        assertEquals("AUDIO_GATE_FADER_WRITE_UNAVAILABLE", AudioCommandGate.reason(noFader, AudioCommand.Fader(AudioChannel.MASTER, -24.0)))
        assertNull(AudioCommandGate.reason(demo(), AudioCommand.Transport(TransportAction.PAUSE)))
        assertNull(AudioCommandGate.reason(demo(), AudioCommand.Fader(AudioChannel.MASTER, -24.0)))
    }
    @Test fun everyDemoFaderClampsAndVerifies() {
        for (channel in AudioChannel.entries) for (value in listOf(999.0, -999.0, 0.0)) {
            val tx = AudioModel.startWrite(demo(), AudioCommand.Fader(channel, value), 100_000)
            assertTrue(tx.accepted); val limits = AudioModel.DEMO_LIMITS.getValue(channel)
            assertTrue(tx.state.dsp.faders.getValue(channel).db!! in limits.minDb..limits.maxDb)
            val done = AudioModel.finishWrite(tx.state, tx.requestId!!, true, 100_001)
            assertEquals(AudioWriteState.VERIFIED, done.dsp.faders.getValue(channel).writeState)
        }
        assertEquals(-6.0, AudioModel.DEMO_LIMITS.getValue(AudioChannel.MASTER).maxDb, 0.0)
        assertFalse(AudioModel.startWrite(demo(), AudioCommand.Fader(AudioChannel.MASTER, Double.NaN), 0).accepted)
    }
    @Test fun singleVolumeAuthorityAndOneTransactionAtATime() {
        val command = AudioCommand.Fader(AudioChannel.MASTER, -12.0)
        assertFalse(AudioModel.startWrite(demo().copy(authority = VolumeAuthority.NONE), command, 0).accepted)
        val tx = AudioModel.startWrite(demo(), command, 0)
        assertFalse(AudioModel.startWrite(tx.state, AudioCommand.Transport(TransportAction.PLAY), 1).accepted)
    }
    @Test fun muteRollbackRetainsNewerMetersAndClipping() {
        val tx = AudioModel.startWrite(demo(), AudioCommand.Mute(AudioChannel.SUB, true), 100_000)
        assertTrue(tx.state.dsp.faders.getValue(AudioChannel.SUB).muted)
        val newer = AudioModel.meters(tx.state.copy(dsp = tx.state.dsp.copy(clippedSamples = 10)), meterFrame())
        val done = AudioModel.finishWrite(newer, tx.requestId!!, false, 100_001)
        assertFalse(done.dsp.faders.getValue(AudioChannel.SUB).muted)
        assertEquals(10L, done.dsp.clippedSamples); assertEquals(5, done.dsp.meters!!.channels.size)
    }
    @Test fun sourceLossPreservesDspTransactionButCancelsTransport() {
        val fader = AudioModel.startWrite(demo(), AudioCommand.Fader(AudioChannel.SUB, -10.0), 0)
        assertEquals(fader.requestId, AudioModel.offline(fader.state, AudioOfflineTarget.SOURCE).pending!!.requestId)
        val transport = AudioModel.startWrite(demo(), AudioCommand.Transport(TransportAction.PLAY), 0)
        val stopped = AudioModel.offline(transport.state, AudioOfflineTarget.SOURCE)
        assertNull(stopped.pending); assertEquals(MediaState.PAUSED, stopped.media.state)
        assertTrue(AudioModel.available(stopped, AudioCommand.Fader(AudioChannel.MASTER, -24.0)))
        assertEquals(stopped, AudioModel.finishWrite(stopped, transport.requestId!!, true, 1))
    }
    @Test fun dspAndUsbLossDropCommandsAndSuppressLateSuccess() {
        for (target in listOf(AudioOfflineTarget.DSP, AudioOfflineTarget.USB)) {
            val tx = AudioModel.startWrite(demo(), AudioCommand.Fader(AudioChannel.MASTER, -12.0), 0)
            val offline = AudioModel.offline(tx.state, target)
            assertNull(offline.pending); assertEquals(-24.0, offline.dsp.faders.getValue(AudioChannel.MASTER).db!!, 0.0)
            assertFalse(AudioModel.available(offline, AudioCommand.Fader(AudioChannel.MASTER, -12.0)))
            assertEquals(offline, AudioModel.finishWrite(offline, tx.requestId!!, true, 1))
        }
    }
    @Test fun oldSessionAndOldRequestCannotVerifyNewWrite() {
        val old = AudioModel.startWrite(demo(), AudioCommand.Fader(AudioChannel.MASTER, -12.0), 0)
        val fresh = AudioModel.startWrite(demo(), AudioCommand.Fader(AudioChannel.MASTER, -18.0), 0)
        assertNotEquals(old.requestId, fresh.requestId)
        assertEquals(fresh.state, AudioModel.finishWrite(fresh.state, old.requestId!!, true, 1))
    }
    @Test fun timeoutRollsBackWithoutReplay() {
        val tx = AudioModel.startWrite(demo(), AudioCommand.Fader(AudioChannel.MASTER, -12.0), 100_000)
        val expired = AudioModel.expireWrite(tx.state, 105_000)
        assertNull(expired.pending); assertEquals(-24.0, expired.dsp.faders.getValue(AudioChannel.MASTER).db!!, 0.0)
        assertEquals(expired, AudioModel.finishWrite(expired, tx.requestId!!, true, 105_001))
    }
    @Test fun soleValidatedPresetAndDisplayOnlyTransportGates() {
        assertFalse(AudioModel.startWrite(demo(), AudioCommand.Preset("Night"), 0).accepted)
        val tx = AudioModel.startWrite(demo(), AudioCommand.Preset(AudioModel.PRESET), 0)
        assertEquals(DspState.APPLYING_PRESET, tx.state.dsp.state)
        assertEquals(DspState.RUNNING, AudioModel.finishWrite(tx.state, tx.requestId!!, false, 1).dsp.state)
        val display = AudioModel.fixture(AudioFixture.DISPLAY_ONLY)
        TransportAction.entries.forEach { assertFalse(AudioModel.startWrite(display, AudioCommand.Transport(it, 0), 0).accepted) }
        assertEquals(83_000L, display.media.positionMs)
    }
    @Test fun physicalLabelsNeedVerifiedRoutingAndMetersExpire() {
        val unverified = AudioModel.meters(AudioModel.initial(), meterFrame())
        assertEquals("OUTPUT 1", unverified.dsp.meters!!.channels[0].label)
        val verified = AudioModel.meters(unverified, meterFrame(true))
        assertEquals("FL", verified.dsp.meters!!.channels[0].label)
        assertTrue(verified.dsp.meters!!.channels[0].clip)
        assertEquals(TelemetryStatus.STALE, AudioModel.meterQuality(verified, 103_000))
    }
    @Test fun malformedMeterFramesRejectedBeforeMutation() {
        val s = AudioModel.initial()
        assertThrows(IllegalArgumentException::class.java) { AudioModel.meters(s, meterFrame().copy(channels = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { AudioModel.meters(s, meterFrame().copy(quality = TelemetryQuality(TelemetryStatus.LIVE))) }
        assertThrows(IllegalArgumentException::class.java) { AudioModel.meters(s, meterFrame().copy(channels = (0..4).map { AudioMeter(it, Double.NaN, -20.0, false) })) }
        assertNull(s.dsp.meters)
    }
    @Test fun liveAudioRequiresIndependentFreshComponentQualityAndAuthority() {
        val q = TelemetryQuality(TelemetryStatus.LIVE, 10_000, "PI SIMULATOR", demo = false, simulated = true)
        val live = demo().let { it.copy(mode = OperatingMode.LIVE, source = it.source.copy(quality = q), dsp = it.dsp.copy(quality = q)) }
        val transport = AudioCommand.Transport(TransportAction.NEXT)
        val fader = AudioCommand.Fader(AudioChannel.MASTER, -18.0)
        assertTrue(AudioModel.available(live.withAudioFreshness(10_001), transport))
        assertTrue(AudioModel.available(live.withAudioFreshness(10_001), fader))
        assertFalse(AudioModel.available(live.withAudioFreshness(13_000), transport))
        assertFalse(AudioModel.available(live.withAudioFreshness(13_000), fader))
        assertFalse(AudioModel.startWrite(live, fader, 13_000).accepted)
        assertFalse(AudioModel.available(live.copy(source = live.source.copy(quality = q.copy(timestampEpochMs = null))), transport))
        assertFalse(AudioModel.available(live.copy(dsp = live.dsp.copy(quality = q.copy(demo = true))), fader))
        assertFalse(AudioModel.available(live.copy(authority = VolumeAuthority.NONE), fader))
        val sourceLost = live.copy(source = live.source.copy(quality = q.copy(status = TelemetryStatus.LOST)))
        assertFalse(AudioModel.available(sourceLost, transport))
        assertTrue(AudioModel.available(sourceLost, fader))
    }
}
