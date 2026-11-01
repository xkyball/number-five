package com.no5.helm.control

import com.no5.control.NormalizedKnobInput.*
import com.no5.domain.*
import com.no5.helm.AudioNativeController
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

class AudioKnobActionsTest {
    private var now = 10_000L
    private fun source() = MockAudioSource(now = { now }).also { it.loadFixture(AudioFixture.DEMO) }
    private fun controller(s: AudioSourcePort) = AudioNativeController(Dispatchers.Unconfined, { now },
        automaticTicker = false, automaticVerification = false, source = s)
    private fun ack(s: MockAudioSource) = s.completePending(s.state.value.pending!!.requestId, true)

    @Test fun sixDefaultsUseExistingPanelControllerAndConfirmedStep() {
        val s = source(); val c = controller(s); val actions = AudioKnobActions(c)
        val other = s.state.value.dsp.faders.filterKeys { it != AudioChannel.MASTER }
        assertTrue(actions.perform(LEFT_CW)); assertEquals(AudioCommand.Fader(AudioChannel.MASTER, -23.0), s.state.value.pending!!.command)
        assertTrue(actions.perform(LEFT_CW)); ack(s)
        assertEquals(AudioCommand.Fader(AudioChannel.MASTER, -22.0), s.state.value.pending!!.command); ack(s)
        assertTrue(actions.perform(LEFT_CCW)); assertEquals(AudioCommand.Fader(AudioChannel.MASTER, -23.0), s.state.value.pending!!.command); ack(s)
        assertTrue(actions.perform(LEFT_PUSH)); assertEquals(AudioCommand.Mute(AudioChannel.MASTER, true), s.state.value.pending!!.command); ack(s)
        for ((input, expected) in listOf(RIGHT_CW to TransportAction.NEXT, RIGHT_CCW to TransportAction.PREVIOUS,
            RIGHT_PUSH to TransportAction.PLAY, RIGHT_PUSH to TransportAction.PAUSE)) {
            assertTrue(actions.perform(input)); assertEquals(AudioCommand.Transport(expected), s.state.value.pending!!.command); ack(s)
        }
        assertEquals(other, s.state.value.dsp.faders.filterKeys { it != AudioChannel.MASTER }); c.dispose()
    }
    @Test fun longIsHelmSpecificAndClosingNeverReplays() {
        val s = source(); val c = controller(s); val a = AudioKnobActions(c)
        assertFalse(a.perform(LEFT_LONG_PUSH)); assertFalse(a.perform(RIGHT_LONG_PUSH)); assertNull(s.state.value.pending)
        c.dispose(); assertFalse(a.perform(LEFT_CW)); assertFalse(a.perform(RIGHT_PUSH))
    }
    @Test fun longActionCanOnlyUseTheInjectedHelmHandler() {
        val s = source(); val c = controller(s); val received = mutableListOf<com.no5.control.NormalizedKnobInput>()
        val a = AudioKnobActions(c, HelmAudioSpecialActions { input -> received += input; true })
        assertTrue(a.perform(LEFT_LONG_PUSH)); assertTrue(a.perform(RIGHT_LONG_PUSH))
        assertEquals(listOf(LEFT_LONG_PUSH, RIGHT_LONG_PUSH), received)
        assertNull(s.state.value.pending); c.dispose()
    }
    @Test fun physicalMasterIndexAndUniqueCatalogueAreMandatory() {
        val original = source(); val state = original.state.value
        fun altered(controls: List<AudioFaderControl>) = object : AudioSourcePort by original {
            override val state = MutableSourceFeed(state.copy(dsp = state.dsp.copy(faderControls = controls)))
        }
        val master = state.dsp.faderControls.single { it.gatewayId == AudioChannel.MASTER }
        for (controls in listOf(emptyList(), listOf(master.copy(physicalIndex = 1)), listOf(master, master))) {
            val c = controller(altered(controls)); val a = AudioKnobActions(c)
            assertFalse(a.perform(LEFT_CW)); assertFalse(a.perform(LEFT_PUSH)); assertNull(original.state.value.pending); c.dispose()
        }
    }
    @Test fun sourceLossStillAllowsIndependentDspAndRecoveryHasNoAccumulator() {
        val s = source(); val c = controller(s); val a = AudioKnobActions(c)
        s.setOffline(AudioOfflineTarget.SOURCE); assertFalse(a.perform(RIGHT_CW)); assertTrue(a.perform(LEFT_CW)); ack(s)
        s.setOffline(AudioOfflineTarget.DSP); assertFalse(a.perform(LEFT_CW)); assertFalse(a.perform(LEFT_PUSH))
        s.setOffline(AudioOfflineTarget.DSP, false); assertNull(s.state.value.pending); c.dispose()
    }
    @Test fun authorityAndUnknownStateDoNotUseAndroidVolumeOrLegacyMap() {
        val original = source(); val state = original.state.value
        val altered = object : AudioSourcePort by original {
            override val state = MutableSourceFeed(state.copy(authority = VolumeAuthority.NONE))
        }
        val c = controller(altered); val a = AudioKnobActions(c)
        assertFalse(a.perform(LEFT_CW)); assertFalse(a.perform(LEFT_PUSH)); assertNull(original.state.value.pending); c.dispose()
    }
}
