package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class AudioSourcePortTest {
    private class Clock(var time: Long = 10_000) { fun now() = time }
    private fun source(clock: Clock = Clock(), session: String = "test-audio", delay: Long = 240, timeout: Long = 5_000) =
        MockAudioSource(SourceIdentity(session, SourceMode.DEMO, "TEST AUDIO"), clock::now, delay, timeout)
    private val fader = AudioCommand.Fader(AudioChannel.MASTER, -12.0)

    @Test fun canonicalFixtureRemainsUnknownUntilExplicitStart() {
        val s = source()
        assertEquals(OperatingMode.UNAVAILABLE, s.state.value.mode)
        assertNull(s.state.value.media.title)
        assertNull(s.state.value.limits)
        assertFalse(s.submit(fader).accepted)
        s.start()
        assertEquals(OperatingMode.DEMO, s.state.value.mode)
        assertTrue(s.demoState.value.running)
        assertTrue(s.state.value.source.quality.demo)
        assertEquals(10_000L, s.state.value.source.quality.timestampEpochMs)
        assertEquals("test-audio", s.state.value.sessionId)
    }
    @Test fun clockControlsVerificationAndFailureWithoutSleeping() {
        val clock = Clock(); val s = source(clock)
        s.loadFixture(AudioFixture.DEMO)
        val receipt = s.submit(fader)
        assertEquals(AudioCommandStatus.PENDING, receipt.status)
        clock.time += 239; s.tick()
        assertNotNull(s.state.value.pending)
        clock.time++; s.tick()
        assertNull(s.state.value.pending)
        assertEquals(AudioCommandStatus.VERIFIED, s.outcomes.value!!.status)
        s.failNextWrite()
        s.submit(AudioCommand.Mute(AudioChannel.MASTER, true))
        assertFalse(s.demoState.value.failNextWrite)
        clock.time += 240; s.tick()
        assertFalse(s.state.value.dsp.faders.getValue(AudioChannel.MASTER).muted)
        assertEquals(AudioCommandStatus.FAILED, s.outcomes.value!!.status)
    }
    @Test fun timeoutWinsOverLateScheduledVerification() {
        val clock = Clock(); val s = source(clock, delay = 6000, timeout = 5000)
        s.start(); val receipt = s.submit(fader)
        clock.time += 5000; s.tick()
        assertEquals(AudioCommandStatus.FAILED, s.outcomes.value!!.status)
        assertEquals(-24.0, s.state.value.dsp.faders.getValue(AudioChannel.MASTER).db!!, 0.0)
        clock.time += 10_000; s.tick()
        assertFalse(s.acknowledge(s.identity.sessionId, receipt.requestId!!, true))
        assertNull(s.state.value.pending)
    }
    @Test fun wrongSessionAndResetRequestCannotAcknowledgeCurrentWrite() {
        val s = source(); s.start(); val old = s.submit(fader)
        assertFalse(s.acknowledge("other-session", old.requestId!!, true))
        assertNotNull(s.state.value.pending)
        s.reset(); s.start(); val current = s.submit(AudioCommand.Fader(AudioChannel.MASTER, -18.0))
        assertNotEquals(old.requestId, current.requestId)
        assertFalse(s.acknowledge(s.identity.sessionId, old.requestId, true))
        assertEquals(current.requestId, s.state.value.pending!!.requestId)
        assertTrue(s.acknowledge(s.identity.sessionId, current.requestId!!, true))
        assertEquals(-18.0, s.state.value.dsp.faders.getValue(AudioChannel.MASTER).db!!, 0.0)
    }
    @Test fun offlineAndRecoveryDiscardTransportButKeepDspIndependent() {
        val clock = Clock(); val s = source(clock); s.start()
        val transport = s.submit(AudioCommand.Transport(TransportAction.NEXT))
        s.setOffline(AudioOfflineTarget.SOURCE)
        assertEquals(AudioCommandStatus.DROPPED, s.outcomes.value!!.status)
        assertEquals("Open water", s.state.value.media.title)
        s.setOffline(AudioOfflineTarget.SOURCE, false)
        clock.time += 1000; s.tick()
        assertFalse(s.acknowledge(s.identity.sessionId, transport.requestId!!, true))
        assertEquals("Open water", s.state.value.media.title)
        val dsp = s.submit(fader)
        s.setOffline(AudioOfflineTarget.SOURCE)
        assertEquals(dsp.requestId, s.state.value.pending!!.requestId)
        assertTrue(s.acknowledge(s.identity.sessionId, dsp.requestId!!, true))
    }
    @Test fun usbOfflineRecoveryNeverReplaysPendingOrRejectedWrite() {
        val clock = Clock(); val s = source(clock); s.start(); val old = s.submit(fader)
        s.setOffline(AudioOfflineTarget.USB)
        assertNull(s.state.value.pending)
        assertFalse(s.submit(AudioCommand.Fader(AudioChannel.MASTER, -8.0)).accepted)
        s.setOffline(AudioOfflineTarget.USB, false)
        clock.time += 5000; s.tick()
        assertFalse(s.acknowledge(s.identity.sessionId, old.requestId!!, true))
        assertEquals(-24.0, s.state.value.dsp.faders.getValue(AudioChannel.MASTER).db!!, 0.0)
    }
    @Test fun closeDropsPendingDetachesListenersAndRejectsFurtherWork() {
        val s = source(); var events = 0
        s.state.subscribe { events++ }
        s.start(); val old = s.submit(fader)
        s.close(); val count = events; val closedState = s.state.value
        assertNull(closedState.pending)
        assertEquals(AudioCommandStatus.DROPPED, s.outcomes.value!!.status)
        s.start(); s.tick(); s.loadFixture(AudioFixture.DEMO); s.setOffline(AudioOfflineTarget.USB, false)
        assertFalse(s.acknowledge(s.identity.sessionId, old.requestId!!, true))
        assertFalse(s.submit(fader).accepted)
        assertEquals(count, events)
        assertEquals(closedState, s.state.value)
    }
    @Test fun closeStillDetachesEveryFeedWhenAnObserverThrows() {
        val s = source(); s.start(); s.submit(fader)
        var armed = false
        s.state.subscribe { if (armed) throw IllegalStateException("Observer failure") }
        armed = true
        assertThrows(IllegalStateException::class.java) { s.close() }
        assertNull(s.state.value.pending)
        assertEquals(AudioCommandStatus.DROPPED, s.outcomes.value!!.status)
        assertFalse(s.demoState.value.running)
        var callbacks = 0
        s.state.subscribe { callbacks++ }
        s.outcomes.subscribe { callbacks++ }
        s.demoState.subscribe { callbacks++ }
        s.close(); s.start(); s.tick()
        assertEquals(0, callbacks)
        assertFalse(s.submit(fader).accepted)
    }
    @Test fun instancesAndTheirOutcomesAreIsolated() {
        val a = source(session = "one"); val b = source(session = "two")
        a.start(); b.start(); val write = a.submit(fader)
        assertNull(b.state.value.pending)
        assertNull(b.outcomes.value)
        assertFalse(b.acknowledge("one", write.requestId!!, true))
        assertEquals(-24.0, b.state.value.dsp.faders.getValue(AudioChannel.MASTER).db!!, 0.0)
        a.close(); assertTrue(b.submit(fader).accepted)
    }
    @Test fun identicalIdentityAndClockProduceIdenticalFixtureAndRequest() {
        val a = source(); val b = source()
        a.start(); b.start()
        assertEquals(a.state.value, b.state.value)
        assertEquals(a.submit(fader), b.submit(fader))
        assertEquals(a.state.value, b.state.value)
    }
    @Test fun clockAdvancesPlaybackWhileMetersRemainExplicitFixedFixture() {
        val clock = Clock(); val s = source(clock); s.start()
        val meters = s.state.value.dsp.meters
        clock.time += 1000; s.tick()
        assertEquals(84_000L, s.state.value.media.positionMs)
        assertEquals(meters, s.state.value.dsp.meters)
        s.stop(); clock.time += 1000; s.tick()
        assertEquals(84_000L, s.state.value.media.positionMs)
    }
    @Test fun piUnavailableSourceHasNoDemoSurfaceAndNoInventedValues() {
        val s = UnavailableAudioSource(SourceIdentity("pi-one", SourceMode.PI, "PI"))
        assertNull(s.demoControls)
        assertFalse(s.submit(fader).accepted)
        assertNull(s.state.value.dsp.meters)
        assertNull(s.state.value.media.title)
        assertNull(s.state.value.limits)
        assertEquals(OperatingMode.UNAVAILABLE, s.state.value.mode)
    }
    @Test fun freshnessProjectionUsesAdapterPolicyAndDoesNotChangeTimestamps() {
        val q = TelemetryQuality(TelemetryStatus.LIVE, 10_000, "PI TEST")
        val s = AudioModel.fixture(AudioFixture.DEMO).copy(mode = OperatingMode.LIVE).let {
            it.copy(source = it.source.copy(quality = q), dsp = it.dsp.copy(quality = q))
        }
        assertEquals(TelemetryStatus.STALE, s.withAudioFreshness(13_000).source.quality.status)
        assertEquals(TelemetryStatus.LIVE, s.withAudioFreshness(13_000, FreshnessPolicy(5000, 10000)).source.quality.status)
        assertEquals(TelemetryStatus.LOST, s.withAudioFreshness(41_000).dsp.quality.status)
        assertEquals(10_000L, s.withAudioFreshness(41_000).dsp.quality.timestampEpochMs)
        assertTrue(AudioModel.available(s.withAudioFreshness(10_001), fader))
        assertFalse(AudioModel.available(s.withAudioFreshness(13_000), fader))
    }
    @Test fun trackChangesCarryBundledArtworkAndAlbumThroughNextPreviousAndEnd() {
        val clock = Clock(); val s = source(clock); s.start()
        assertEquals("tidal-lines", s.state.value.media.artworkId)
        val next = s.submit(AudioCommand.Transport(TransportAction.NEXT))
        s.acknowledge(s.identity.sessionId, next.requestId!!, true)
        assertEquals("blue-horizon", s.state.value.media.trackId)
        assertEquals("harbor-lights", s.state.value.media.artworkId)
        val previous = s.submit(AudioCommand.Transport(TransportAction.PREVIOUS))
        s.acknowledge(s.identity.sessionId, previous.requestId!!, true)
        assertEquals("open-water", s.state.value.media.trackId)
        val seek = s.submit(AudioCommand.Transport(TransportAction.SEEK, s.state.value.media.durationMs!! - 500))
        s.acknowledge(s.identity.sessionId, seek.requestId!!, true)
        clock.time += 1000; s.tick()
        assertEquals("blue-horizon", s.state.value.media.trackId)
        assertEquals("harbor-lights", s.state.value.media.artworkId)
        assertEquals(MediaState.PLAYING, s.state.value.media.state)
        assertNotNull(s.state.value.media.album)
        assertTrue(s.state.value.media.artworkId in AudioArtworkCatalog.ids)
        s.close()
    }
}
