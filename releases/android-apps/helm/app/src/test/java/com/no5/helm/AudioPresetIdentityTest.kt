package com.no5.helm

import com.no5.domain.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

/** A queued preset intent must keep its ID when the server changes catalog order. */
class AudioPresetIdentityTest {
    private fun queued(change:(List<AudioPreset>)->List<AudioPreset>):Pair<String,List<AudioCommand>> {
        val delegate=MockAudioSource(now={10_000L}).also{it.loadFixture(AudioFixture.DEMO)}
        val base=delegate.state.value
        val presets=base.dsp.presets+AudioPreset("other-validated","Other validated preset")
        val pending=AudioPending("fixture-in-flight",AudioCommand.Fader(AudioChannel.MASTER,-20.0),base.dsp,base.media,20_000L)
        val state=MutableSourceFeed(base.copy(dsp=base.dsp.copy(presets=presets),pending=pending))
        val writes=mutableListOf<AudioCommand>()
        val source=object:AudioSourcePort by delegate {
            override val state=state
            override fun available(command:AudioCommand)=AudioModel.available(state.value,command)
            override fun submit(command:AudioCommand)=delegate.submit(command).also{writes+=command}
        }
        val controller=AudioNativeController(Dispatchers.Unconfined,{10_000L},false,false,source)
        try {
            val intended=presets.first().id
            assertTrue(controller.write(AudioCommand.Preset(intended)))
            assertTrue("The unrelated in-flight effect must finish first",writes.isEmpty())
            state.emit(base.copy(revision=base.revision+1,dsp=base.dsp.copy(presets=change(presets))))
            return intended to writes.toList()
        } finally {controller.dispose()}
    }
    @Test fun catalogReorderingCannotSelectADifferentPreset(){
        val (intended,writes)=queued{it.reversed()}
        assertEquals(listOf(AudioCommand.Preset(intended)),writes)
    }
    @Test fun removingTheIntendedPresetRejectsTheActionWithoutSelectingItsNewNeighbour(){
        val (_,writes)=queued{it.drop(1)}
        assertTrue(writes.isEmpty())
    }
}
