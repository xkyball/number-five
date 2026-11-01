package com.no5.helm

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.no5.domain.*
import com.no5.helm.network.soundFixture
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

private class PaintPort:AudioSoundPort {
    override val state=MutableSourceFeed(soundFixture().let{it.copy(
        preset=it.preset!!.copy(eqEnabled=true,eqPoints=listOf(SoundEqPoint("point",1000.0,4.0,2.0))),
        capabilities=it.capabilities.copy(eqProjection=true,experimentalEq=true))})
    override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null)
    override val results=MutableSourceFeed<AudioSoundResult?>(null)
    val commands=mutableListOf<AudioSoundCommand>()
    override fun submit(command:AudioSoundCommand):Long {
        commands+=command;val id=commands.size.toLong();results.emit(AudioSoundResult(id,"ACCEPTED"));return id
    }
    override fun setEditorVisible(visible:Boolean,eqVisible:Boolean)=Unit
    override fun refresh()=Unit
    override fun close()=Unit
    fun applied(id:Long) {
        val command=commands[id.toInt()-1]as AudioSoundCommand.Preview
        state.emit(state.value.copy(preset=command.preset,preview=SoundPreview(command.previewId,10000,true,false),
            eqEditing=SoundEqEditing(command.eqEditing?.experimental==true)))
        results.emit(AudioSoundResult(id,"APPLIED"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AudioSoundPaintTest {
    @Test fun liveHeadroomCannotInvalidateGestureOwnerOrControlTree() {
        val p=PaintPort();val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined)
        var controls=0;var ink=0;val observer=SnapshotStateObserver{it()};observer.start()
        try {
            observer.observeReads("controls",{_:Any->controls++}){c.controlInk}
            observer.observeReads("ink",{_:Any->ink++}){c.observationInk}
            p.state.emit(p.state.value.copy(headroomDb=p.state.value.headroomDb+1));Snapshot.sendApplyNotifications()
            assertEquals(0,controls);assertEquals(1,ink)
            p.state.emit(p.state.value.copy(sourceGeneration="new-source"));Snapshot.sendApplyNotifications()
            assertEquals(1,controls)
        } finally {observer.stop();observer.clear();c.close()}
    }
    @Test fun leaseBookkeepingDoesNotRepaintButRawRevisionsAndExpiryRemainCurrent() {
        val p=PaintPort();p.state.emit(p.state.value.copy(preview=SoundPreview("owned",10000,true,false)))
        val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined)
        var invalidations=0;val key=Any();val observer=SnapshotStateObserver{it()};observer.start()
        try {
            observer.observeReads(key,{_:Any->invalidations++}){c.observationInk}
            val actual=p.state.value.copy(configurationRevision=99,preview=p.state.value.preview!!.copy(expiresAtEpochMs=12000))
            p.state.emit(actual);Snapshot.sendApplyNotifications()
            assertEquals(0,invalidations);assertSame(actual,c.observed)
            p.state.emit(actual.copy(available=false,writable=false,reason="PREVIEW_EXPIRED"));Snapshot.sendApplyNotifications()
            assertEquals(1,invalidations);assertFalse(c.eqActuallyEnabled);assertEquals("PREVIEW_EXPIRED",c.observed.reason)
        }finally{observer.stop();observer.clear();c.close()}
    }
    @Test fun unchangedSpectrumPixelsRetainNewSampleMetadataAndRealChangesStillRepaint() {
        val p=PaintPort();val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined)
        val first=AudioSpectrumFrame(1,c.observed.sourceGeneration,1024,48000,8192,
            listOf(20.0,20000.0),listOf(-90.0,-120.0),listOf(-40.0,-42.0),0,1000000)
        p.spectrum.emit(first)
        var invalidations=0;val key=Any();val observer=SnapshotStateObserver{it()};observer.start()
        val onChanged:(Any)->Unit={invalidations++}
        try {
            observer.observeReads(key,onChanged){c.spectrumInk}
            val latest=first.copy(sequence=2,samplePosition=2048,sampleTimeNs=2000000)
            p.spectrum.emit(latest);Snapshot.sendApplyNotifications()
            assertEquals(0,invalidations);assertSame(latest,c.spectrum)
            p.spectrum.emit(latest.copy(binsDbfs=listOf(-89.0,-120.0)));Snapshot.sendApplyNotifications()
            assertEquals(1,invalidations)
            observer.observeReads(key,onChanged){c.spectrumInk}
            p.spectrum.emit(null);Snapshot.sendApplyNotifications()
            assertEquals(2,invalidations);assertNull(c.spectrum)
        }finally{observer.stop();observer.clear();c.close()}
    }
    @Test fun identicalPendingPointerGoalIsOneCommandButChangedIntentAndRetryArePreserved() {
        val p=PaintPort();val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined);c.openPreset()
        try {
            val original=c.preset!!.eqPoints.single();val target=original.copy(gainDb=5.0)
            repeat(100){c.updatePoint(target)}
            assertEquals(1,p.commands.size);assertTrue(c.eqPending);assertEquals(original,c.preset!!.eqPoints.single())
            c.updatePoint(target.copy(gainDb=5.5));assertEquals(2,p.commands.size)
            p.applied(1);assertEquals(5.0,c.preset!!.eqPoints.single().gainDb);assertTrue(c.eqPending)
            // Same destination, different confirmed motion origin: never deduplicate it.
            c.updatePoint(target.copy(gainDb=5.5));assertEquals(3,p.commands.size)
            p.results.emit(AudioSoundResult(3,"REJECTED"));assertFalse(c.eqPending)
            c.updatePoint(target.copy(gainDb=5.5));assertEquals(4,p.commands.size)
            p.applied(4);assertFalse(c.eqPending);assertTrue(c.eqActuallyEnabled)
            c.useExperimental(true);assertEquals(5,p.commands.size)
            assertTrue((p.commands.last()as AudioSoundCommand.Preview).eqEditing!!.experimental)
        }finally{c.close()}
    }
    @Test fun derivedDirtyAndEditableStatesStillResetAfterSaveAndClose() {
        val p=PaintPort();val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined);c.openPreset()
        try {
            assertFalse(c.presetDirty);assertTrue(c.editable)
            c.updatePoint(c.preset!!.eqPoints.single().copy(gainDb=5.0));p.applied(1)
            assertTrue(c.presetDirty)
            c.save(SoundScope.PRESET)
            assertTrue(c.busy);val saveId=p.commands.size.toLong()
            assertIs<AudioSoundCommand.Save>(p.commands.last())
            p.state.emit(p.state.value.copy(presetRevision=p.state.value.presetRevision+1,preview=null))
            p.results.emit(AudioSoundResult(saveId,"APPLIED"))
            assertFalse(c.busy);assertFalse(c.presetDirty);assertTrue(c.editable)
            c.close();assertFalse(c.editable)
        }finally{c.close()}
    }
}
