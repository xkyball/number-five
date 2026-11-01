package com.no5.helm

import com.no5.domain.*
import com.no5.helm.network.soundFixture
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

private class PresetWorkflowPort:AudioSoundPort {
    val original=soundFixture().preset!!
    override val state=MutableSourceFeed(soundFixture().copy(capabilities=soundFixture().capabilities.copy(presetLibrary=true,presetManagement=true),presetCatalog=listOf(SoundPresetEntry(original.id,original.name,original.routingTemplate),SoundPresetEntry("evening","Evening",original.routingTemplate))))
    override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null)
    override val results=MutableSourceFeed<AudioSoundResult?>(null)
    val commands=mutableListOf<AudioSoundCommand>()
    override fun submit(command:AudioSoundCommand):Long{commands+=command;return commands.size.toLong()}
    override fun refresh()=Unit
    override fun setEditorVisible(visible:Boolean,eqVisible:Boolean)=Unit
    override fun close()=Unit
    fun applied(){results.emit(AudioSoundResult(commands.size.toLong(),"APPLIED"))}
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AudioPresetWorkflowTest {
    @Test fun newIsBlankTemporaryAndDiscardWaitsForActualOriginal(){
        val port=PresetWorkflowPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined)
        try {
            assertTrue(c.beginNewPreset());val draft=c.preset!!
            assertNotEquals(port.original.id,draft.id);assertTrue(draft.eqPoints.isEmpty());assertEquals(SoundRoom(0.0,.5),draft.room)
            assertEquals(port.original.eqEnabled,draft.eqEnabled);assertEquals(port.original.reserveDb,draft.reserveDb)
            assertEquals(2,port.state.value.presetCatalog.size);assertIs<AudioSoundCommand.Preview>(port.commands.single())
            assertFalse(c.conflicted);var closed=0
            port.state.emit(port.state.value.copy(preset=draft,preview=SoundPreview(port.commands.single().previewId,1000,true,false)))
            c.requestClose(SoundScope.PRESET){closed++};c.discard();assertIs<AudioSoundCommand.Discard>(port.commands.last())
            port.applied();assertEquals(0,closed);assertTrue(c.busy)
            port.state.emit(port.state.value.copy(preset=port.original,preview=null))
            assertEquals(1,closed);assertFalse(c.newPresetDraft);assertFalse(c.presetDirty);assertEquals(port.original,c.preset)
        }finally{c.close()}
    }
    @Test fun newSaveAsksForNameAndAnUnknownSaveRetainsNameAndDraftWithoutReplay(){
        val port=PresetWorkflowPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined)
        try {
            assertTrue(c.beginNewPreset());val before=port.commands.size;var closed=0
            c.save(SoundScope.PRESET){closed++};assertTrue(c.presetNameRequested);assertEquals(before,port.commands.size)
            c.editPresetName("My sound");c.confirmPresetName();assertIs<AudioSoundCommand.Save>(port.commands.last())
            val count=port.commands.size;port.results.emit(AudioSoundResult(count.toLong(),"UNKNOWN"))
            assertFalse(c.busy);assertTrue(c.presetNameRequested);assertEquals("My sound",c.presetName);assertTrue(c.newPresetDraft);assertEquals(0,closed)
            c.confirmPresetName();assertEquals(count,port.commands.size,"No retry before a fresh read after UNKNOWN")
            port.state.emit(port.state.value);assertEquals(count,port.commands.size)
        }finally{c.close()}
    }
    @Test fun newSaveCompletesForAdjacentReadbackButNeverAnotherValueOrRevision(){
        for(steps in listOf(1,2)){
            val port=PresetWorkflowPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined)
            try{
                c.beginNewPreset();c.addPoint(1000.0,1.9816084653139114)
                c.save(SoundScope.PRESET){};c.editPresetName("Native save");c.confirmPresetName();val saved=c.preset!!
                var gain=saved.eqPoints.single().gainDb;repeat(steps){gain=Math.nextAfter(gain,0.0)}
                port.applied();port.state.emit(port.state.value.copy(preset=saved.copy(eqPoints=listOf(saved.eqPoints.single().copy(gainDb=gain))),presetRevision=port.state.value.presetRevision+1,preview=null))
                assertEquals(steps!=1,c.busy);assertEquals(steps!=1,c.newPresetDraft)
            }finally{c.close()}
        }
    }
    @Test fun confirmedSaveCreatesOneIDAndClosesOnlyAfterFreshSavedState(){
        val port=PresetWorkflowPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined)
        try {
            c.beginNewPreset();c.save(SoundScope.PRESET){};c.editPresetName("Night");c.confirmPresetName();val saved=c.preset!!
            port.applied();assertTrue(c.busy);assertTrue(c.newPresetDraft)
            port.state.emit(port.state.value.copy(preset=saved,presetRevision=port.state.value.presetRevision+1,presetCatalog=port.state.value.presetCatalog+SoundPresetEntry(saved.id,saved.name,saved.routingTemplate),preview=null))
            assertFalse(c.busy);assertFalse(c.newPresetDraft);assertFalse(c.presetNameRequested);assertFalse(c.presetDirty)
            assertEquals(1,c.observed.presetCatalog.count{it.id==saved.id})
        }finally{c.close()}
    }
    @Test fun editDoesNotOpenOnAcknowledgementOrUnrelatedStateAndDeleteNeedsCatalogReadback(){
        val port=PresetWorkflowPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined)
        try {
            val target=port.state.value.presetCatalog.last();var opens=0
            assertTrue(c.loadPreset(target){opens++});port.applied();assertEquals(0,opens)
            port.state.emit(port.state.value.copy(presetRevision=port.state.value.presetRevision+1));assertEquals(0,opens)
            port.state.emit(port.state.value.copy(preset=port.original.copy(id=target.id,name=target.name)))
            assertEquals(1,opens);assertFalse(c.busy)
            assertTrue(c.deletePreset(target));port.applied();assertTrue(c.busy)
            port.state.emit(port.state.value.copy(preset=port.original,presetCatalog=port.state.value.presetCatalog.filter{it.id!=target.id},presetRevision=port.state.value.presetRevision+1))
            assertFalse(c.busy);assertEquals(1,opens)
        }finally{c.close()}
    }
    @Test fun recoveredNewDraftKeepsOriginalBasisAndSourceChangesCannotPreviewIt(){
        val context=RuntimeEnvironment.getApplication();val prefs=context.getSharedPreferences("no5-sound-drafts-v1",0);prefs.edit().clear().commit()
        val port=PresetWorkflowPort();val first=AudioSoundUiController(port,Dispatchers.Unconfined)
        first.attachStorage(context);first.openPreset();first.beginNewPreset();val draft=first.preset;first.close()
        val second=AudioSoundUiController(port,Dispatchers.Unconfined)
        try {
            second.attachStorage(context);second.openPreset();assertTrue(second.newPresetDraft);assertEquals(draft,second.preset);assertFalse(second.conflicted)
            val count=port.commands.size
            port.state.emit(port.state.value.copy(configGeneration="foreign-generation"));assertTrue(second.conflicted)
            second.setReserve(15.0);assertEquals(count,port.commands.size)
        }finally{second.close();prefs.edit().clear().commit()}
    }
    @Test fun crossRoutingWaitsForVerifiedRootAndFreshSoundAndDoesNotReopenAnOldPanel() {
        for(leave in listOf(false,true)){
            val port=PresetWorkflowPort();val target=SoundPresetEntry("cross","Cross","immersive_4_1")
            port.state.emit(port.state.value.copy(presetCatalog=port.state.value.presetCatalog+target,routingProfiles=mapOf(target.routingTemplate to SoundRoutingProfile("verified_root"))))
            val delegate=MockAudioSource(now={10000L}).also{it.loadFixture(AudioFixture.DEMO)}
            val sourceState=MutableSourceFeed(delegate.state.value.let{it.copy(dsp=it.dsp.copy(presets=listOf(AudioPreset("verified_root","Arbitrary name"))),capabilities=it.capabilities.copy(presetWrite=true))})
            val outcomes=MutableSourceFeed<AudioCommandOutcome?>(null)
            val sent=mutableListOf<AudioCommand>()
            val source=object:AudioSourcePort by delegate {
                override val state=sourceState;override val sound=port;override val outcomes=outcomes
                override fun available(command:AudioCommand)=command is AudioCommand.Preset
                override fun submit(command:AudioCommand):AudioCommandOutcome {sent+=command;return AudioCommandOutcome(state.value.sessionId,"root-request",command,AudioCommandStatus.PENDING,10000,"accepted")}
            }
            val audio=AudioNativeController(Dispatchers.Unconfined,{10000L},false,false,source)
            try {
                audio.openPanel("preset");var opened=0
                assertTrue(audio.activateSoundPreset(target){opened++});assertEquals(listOf<AudioCommand>(AudioCommand.Preset("verified_root")),sent)
                assertTrue(port.commands.isEmpty());assertTrue(audio.presetActivationBusy)
                if(leave)audio.openPanel("audio-menu")
                sourceState.emit(sourceState.value.let{it.copy(revision=it.revision+1,dsp=it.dsp.copy(presets=listOf(AudioPreset("verified_root","Arbitrary name",true))))})
                port.state.emit(port.state.value.copy(configGeneration="new-root",sourceGeneration="new-native-source",preset=port.original.copy(id="root-default",routingTemplate=target.routingTemplate)))
                outcomes.emit(AudioCommandOutcome(sourceState.value.sessionId,"root-request",sent.single(),AudioCommandStatus.VERIFIED,10000,"verified"))
                if(leave){assertTrue(port.commands.isEmpty());assertEquals(0,opened)}
                else {
                    assertIs<AudioSoundCommand.LoadPreset>(port.commands.single());assertEquals(0,opened)
                    port.state.emit(port.state.value.copy(preset=port.state.value.preset!!.copy(id=target.id,name=target.name),presetRevision=port.state.value.presetRevision+1))
                    port.applied();assertEquals(1,opened)
                }
            }finally{audio.dispose()}
        }
    }

    @Test fun unavailableNewDiscardRetainsDraftAndStaleDeletionCannotUseAFreshBasis(){
        val port=PresetWorkflowPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined)
        try {
            val selected=port.state.value.presetCatalog.last();val old=port.state.value
            val basis=SoundRevisionBasis(old.configGeneration,old.presetRevision,old.calibrationRevision)
            port.state.emit(old.copy(presetRevision=old.presetRevision+1));assertFalse(c.deletePreset(selected,basis,old.sourceGeneration));assertTrue(port.commands.isEmpty())
            assertTrue(c.beginNewPreset());val count=port.commands.size;var closed=0
            port.state.emit(port.state.value.copy(available=false,writable=false));c.requestClose(SoundScope.PRESET){closed++};c.discard()
            assertEquals(0,closed);assertTrue(c.newPresetDraft);assertTrue(c.presetDirty);assertEquals(count,port.commands.size)
        }finally{c.close()}
    }

    @Test fun aPreviousProcessLeaseMustReadBackTheOriginalBeforeDiscardCanClose(){
        val port=PresetWorkflowPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined)
        try{
            c.beginNewPreset();val draft=c.preset!!;val count=port.commands.size
            port.state.emit(port.state.value.copy(preset=draft,preview=null))
            var closed=0;c.requestClose(SoundScope.PRESET){closed++};c.discard()
            assertTrue(c.busy);assertTrue(c.newPresetDraft);assertEquals(0,closed);assertEquals(count,port.commands.size)
            port.state.emit(port.state.value.copy(preset=port.original))
            assertEquals(1,closed);assertFalse(c.busy);assertFalse(c.newPresetDraft);assertEquals(count,port.commands.size)
        }finally{c.close()}
    }

    @Test fun aLaterForeignPresetRevisionCannotCompleteAnOldEditorOpen(){
        val port=PresetWorkflowPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined)
        try{
            val target=port.state.value.presetCatalog.last();var opened=0
            c.loadPreset(target){opened++};port.applied()
            port.state.emit(port.state.value.copy(preset=port.original.copy(id=target.id,name=target.name),presetRevision=port.state.value.presetRevision+2))
            assertEquals(0,opened);assertTrue(c.busy);assertEquals(target.id,port.state.value.preset!!.id)
        }finally{c.close()}
    }

}
