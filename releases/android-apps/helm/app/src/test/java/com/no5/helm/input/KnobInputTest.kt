package com.no5.helm.input

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test

class KnobInputTest {
    private val key = QfKey(7, 901, 902, 903, 904) // Synthetic fixture, never a firmware mapping.
    private var now = 10_000L
    private fun source() = MockAudioSource(now = { now }).also { it.loadFixture(AudioFixture.DEMO) }
    private class Input : KnobEventSource {
        var starts = 0; var stops = 0
        var receive: ((KnobEvent) -> Unit)? = null
        override fun start(receive: (KnobEvent) -> Unit) { starts++; this.receive = receive }
        override fun stop() { stops++; receive = null }
    }
    private fun profile(action: KnobAction = KnobAction.MASTER_UP) = KnobProfile(mapOf(key to action), "test fixture", "test-exclusive input")
    private fun event() = KnobEvent(key, 0, 0, now, now)
    private fun complete(s: MockAudioSource) = s.completePending(s.state.value.pending!!.requestId, true)

    @Test fun activityResumeFocusLossStopCloseAndSourceRebindAreSynchronous() {
        var live = 0; var starts = 0
        val owner = KnobForegroundOwner { starts++; live++; AutoCloseable { live-- } }
        val source = source()
        val old = owner.bind(source)
        owner.resume(false); assertEquals(0, live)
        owner.focus(true); assertEquals(1, live)
        owner.focus(true); assertEquals(1, starts)
        owner.focus(false); assertEquals(0, live)
        owner.focus(true); assertEquals(1, live)
        val fresh = owner.bind(source); old.close(); assertEquals(1, live)
        owner.stop(); assertEquals(0, live)
        owner.focus(true); assertEquals(0, live)
        owner.resume(true); assertEquals(1, live)
        fresh.close(); assertEquals(0, live)
        owner.bind(source); assertEquals(1, live)
        owner.close(); owner.close(); owner.resume(true); owner.focus(true)
        assertEquals(0, live)
    }
    @Test fun unverifiedMappingOrOemExclusivityNeverRegisters() {
        for (p in listOf(KnobProfile.UNVERIFIED, KnobProfile(mapOf(key to KnobAction.MASTER_UP), "known mapping"))) {
            val input = Input(); val hub = ForegroundKnobInput(input, p) { now }
            hub.acquire(source()).close()
            assertEquals(0, input.starts)
        }
    }
    @Test fun takeoverOldCloseStopResumeAndLateCallbacksHaveOneOwner() {
        val input = Input(); val hub = ForegroundKnobInput(input, profile()) { now }
        val first = source(); val second = source()
        val old = hub.acquire(first); val late = input.receive!!
        now++
        val current = hub.acquire(second)
        old.close(); late(event())
        assertNull(first.state.value.pending)
        input.receive!!(event()); assertNotNull(second.state.value.pending)
        val stoppedCallback = input.receive!!
        current.close(); current.close()
        assertNull(input.receive)
        complete(second); now++
        stoppedCallback(event()); assertNull(second.state.value.pending)
        hub.acquire(second).use { input.receive!!(event()); assertNotNull(second.state.value.pending) }
        assertEquals(3, input.starts)
    }
    @Test fun duplicateDownUpRepeatAndOldEventsDoNotReplayEvenAfterAck() {
        val input = Input(); val hub = ForegroundKnobInput(input, profile()) { now }
        val s = source()
        hub.acquire(s).use {
            val e = event()
            input.receive!!(e); complete(s)
            input.receive!!(e)
            input.receive!!(e.copy(action = 1))
            input.receive!!(e.copy(repeat = 1))
            input.receive!!(e.copy(eventTime = now - 1, downTime = now - 1))
            input.receive!!(e.copy(key = key.copy(deviceId = 999)))
            assertNull(s.state.value.pending)
            now++
            input.receive!!(event()); assertNotNull(s.state.value.pending)
        }
    }
    @Test fun masterOnlyUsesAdvertisedStepLimitsAndConfirmedBase() {
        val s = source(); val c = KnobAudioCommands(s)
        val other = s.state.value.dsp.faders.filterKeys { it != AudioChannel.MASTER }
        assertTrue(c.perform(KnobAction.MASTER_UP))
        assertEquals(AudioCommand.Fader(AudioChannel.MASTER, -23.0), s.state.value.pending!!.command)
        assertFalse(c.perform(KnobAction.MASTER_UP)) // no accumulator based on pending values
        complete(s)
        assertTrue(c.perform(KnobAction.MASTER_MUTE))
        assertEquals(AudioCommand.Mute(AudioChannel.MASTER, true), s.state.value.pending!!.command)
        complete(s)
        assertEquals(other, s.state.value.dsp.faders.filterKeys { it != AudioChannel.MASTER })
    }
    @Test fun camillaLossNeverFallsBackAndRecoveryNeverReplays() {
        val s = source(); val c = KnobAudioCommands(s)
        s.setOffline(AudioOfflineTarget.DSP)
        assertFalse(c.perform(KnobAction.MASTER_UP)); assertFalse(c.perform(KnobAction.MASTER_MUTE))
        assertNull(s.state.value.pending)
        s.setOffline(AudioOfflineTarget.DSP, false)
        assertNull(s.state.value.pending)
        assertEquals(-24.0, s.state.value.dsp.faders.getValue(AudioChannel.MASTER).db!!, 0.0)
    }
    @Test fun missingAuthorityUnknownLevelAndLimitsCannotUseFallbacks() {
        val original = source()
        fun altered(state: AudioState) = object : AudioSourcePort by original {
            override val state: SourceFeed<AudioState> = MutableSourceFeed(state)
        }
        val state = original.state.value
        assertFalse(KnobAudioCommands(altered(state.copy(authority = VolumeAuthority.NONE))).perform(KnobAction.MASTER_UP))
        assertFalse(KnobAudioCommands(altered(state.copy(authority = VolumeAuthority.NONE))).perform(KnobAction.MASTER_MUTE))
        assertFalse(KnobAudioCommands(altered(state.copy(limits = null))).perform(KnobAction.MASTER_UP))
        val unknown = state.copy(dsp = state.dsp.copy(faders = mapOf(AudioChannel.MASTER to AudioFader())))
        assertFalse(KnobAudioCommands(altered(unknown)).perform(KnobAction.MASTER_UP))
        val upper = state.copy(dsp = state.dsp.copy(faders = mapOf(AudioChannel.MASTER to AudioFader(-6.0))))
        assertFalse(KnobAudioCommands(altered(upper)).perform(KnobAction.MASTER_UP))
        assertNull(original.state.value.pending)
    }
    @Test fun rightActionsUseExistingTransportAndCapabilities() {
        val s = source(); val c = KnobAudioCommands(s)
        for ((action, expected) in listOf(KnobAction.NEXT to TransportAction.NEXT, KnobAction.PREVIOUS to TransportAction.PREVIOUS,
            KnobAction.PLAY_PAUSE to TransportAction.PLAY, KnobAction.PLAY_PAUSE to TransportAction.PAUSE)) {
            assertTrue(c.perform(action))
            assertEquals(AudioCommand.Transport(expected), s.state.value.pending!!.command)
            complete(s)
        }
        s.setOffline(AudioOfflineTarget.SOURCE)
        assertFalse(c.perform(KnobAction.NEXT))
    }
}
