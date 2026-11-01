package com.no5.helm

import com.no5.domain.*
import com.no5.helm.network.AudioSoundCodec
import com.no5.helm.network.soundFixture
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

private class EqControlPort(initial:AudioSoundState=soundFixture()):AudioSoundPort {
    override val state=MutableSourceFeed(initial)
    override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null)
    override val results=MutableSourceFeed<AudioSoundResult?>(null)
    val commands=mutableListOf<AudioSoundCommand>()
    override fun submit(command:AudioSoundCommand):Long {commands+=command;return commands.size.toLong()}
    override fun setEditorVisible(visible:Boolean,eqVisible:Boolean)=Unit
    override fun refresh()=Unit
    override fun close()=Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AudioSoundEqControlTest {
    @Test fun eqTogglePreservesTheWholeCurveAndChosenReserveNeverChangesDuringPointEdits(){
        val initial=soundFixture().let{it.copy(preset=it.preset!!.copy(eqPoints=listOf(SoundEqPoint("p",800.0,3.0)),room=SoundRoom(.6,.7)))}
        val port=EqControlPort(initial);val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.openPreset()
        val original=c.preset!!;c.setEqEnabled(true);assertTrue(c.preset!!.eqEnabled);assertEquals(original.eqPoints,c.preset!!.eqPoints)
        c.setReserve(15.0);assertEquals(15.0,c.preset!!.reserveDb)
        c.updatePoint(c.preset!!.eqPoints.first().move(900.0,6.0))
        assertEquals(15.0,c.preset!!.reserveDb);assertEquals(original.room,c.preset!!.room);assertEquals(initial.calibration,c.calibration)
        val curve=c.preset!!.eqPoints;c.setEqEnabled(false)
        assertFalse(c.preset!!.eqEnabled);assertEquals(curve,c.preset!!.eqPoints);assertEquals(15.0,c.preset!!.reserveDb)
        assertTrue(port.commands.all{it is AudioSoundCommand.Preview && it.scope==SoundScope.PRESET})
        c.close()
    }
    @Test fun neutralResetsOnlyGainsAndReserveControlsRespectNormalBounds(){
        val p=soundFixture().preset!!.copy(eqPoints=listOf(SoundEqPoint("p",1200.0,5.0,2.0,true)),reserveDb=17.0)
        val port=EqControlPort(soundFixture().copy(preset=p));val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.openPreset();c.neutral()
        assertFalse(c.preset!!.eqEnabled);assertEquals(17.0,c.preset!!.reserveDb);assertEquals(p.eqPoints.first().copy(gainDb=0.0),c.preset!!.eqPoints.first())
        c.setReserve(100.0);assertEquals(18.0,c.preset!!.reserveDb);val count=port.commands.size;c.adjustReserve(1);assertEquals(count,port.commands.size)
        c.setReserve(-100.0);assertEquals(6.0,c.preset!!.reserveDb);c.close()
    }
    @Test fun bypassedLegacyOverlapIsPreservedButCannotBeEnabledUntilExplicitlyCorrected(){
        val p=soundFixture().preset!!.copy(eqPoints=listOf(SoundEqPoint("a",1000.0,8.0),SoundEqPoint("b",1100.0,8.0)),eqEnabled=false)
        val port=EqControlPort(soundFixture().copy(preset=p));val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.openPreset()
        assertEquals(p,c.preset);assertTrue(port.commands.isEmpty())
        c.setEqEnabled(true);assertFalse(c.preset!!.eqEnabled);assertEquals(p.eqPoints,c.preset!!.eqPoints);assertTrue(port.commands.isEmpty());assertNotNull(c.eqBoundReason)
        c.neutral();assertFalse(c.preset!!.eqEnabled);c.setEqEnabled(true);assertTrue(c.preset!!.eqEnabled)
        assertEquals(p.eqPoints.map{it.copy(gainDb=0.0)},c.preset!!.eqPoints);c.close()
    }
    @Test fun touchTargetsStopAtPositiveBandBoundaryWithoutScalingAnyOtherPointOrReserve(){
        val p=soundFixture().preset!!.copy(eqPoints=listOf(SoundEqPoint("a",300.0,3.0,2.0),SoundEqPoint("b",1000.0,4.0,4.0)),eqEnabled=true,reserveDb=16.0)
        val port=EqControlPort(soundFixture().copy(preset=p,headroomDb=16.0));val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.openPreset()
        c.updatePoint(p.eqPoints.first().move(5000.0,3.0))
        val target=c.preset!!;assertTrue(target.eqPoints.first().highHz<=p.eqPoints.last().lowHz+1e-9)
        assertEquals(p.eqPoints.last(),target.eqPoints.last());assertEquals(16.0,target.reserveDb);assertNotNull(c.eqBoundReason)
        val preview=port.commands.single() as AudioSoundCommand.Preview;assertEquals(target,preview.preset);assertEquals(3,preview.basis!!.presetRevision)
        c.close()
    }
    @Test fun bypassedEditingCannotIntroduceOrWidenOverlapButUnchangedLegacyPointsAllowOtherPresetEdits(){
        val valid=soundFixture().preset!!.copy(eqPoints=listOf(SoundEqPoint("a",500.0,-2.0),SoundEqPoint("b",1000.0,4.0)),eqEnabled=false)
        val port=EqControlPort(soundFixture().copy(preset=valid));val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.openPreset()
        c.changePreset(valid.copy(eqPoints=valid.eqPoints.map{if(it.id=="a")it.copy(gainDb=3.0)else it}))
        assertEquals(valid,c.preset);assertTrue(port.commands.isEmpty());assertNotNull(c.eqBoundReason);c.close()
        val legacy=valid.copy(eqPoints=listOf(SoundEqPoint("a",1000.0,7.0),SoundEqPoint("b",1100.0,7.0)))
        val oldPort=EqControlPort(soundFixture().copy(preset=legacy));val old=AudioSoundUiController(oldPort,Dispatchers.Unconfined);old.openPreset()
        old.changePreset(legacy.copy(name="Renamed",room=SoundRoom(.5,.7),reserveDb=16.0))
        assertEquals(legacy.eqPoints,old.preset!!.eqPoints);assertFalse(old.preset!!.eqEnabled);assertEquals(1,oldPort.commands.size)
        val saved=old.preset!!;old.changePreset(saved.copy(eqPoints=saved.eqPoints.map{if(it.id=="a")it.copy(q=.3)else it}))
        assertEquals(saved,old.preset);assertEquals(1,oldPort.commands.size);old.close()
    }

    @Test fun nativeCombinedBudgetRejectionKeepsTheLocalTargetAndShowsARealBoundReason(){
        val port=EqControlPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.openPreset();c.setEqEnabled(true)
        val target=c.preset;port.results.emit(AudioSoundResult(port.commands.size.toLong(),"REJECTED","EQ_COMBINED_GAIN_LIMIT"))
        assertEquals(target,c.preset);assertTrue(c.presetDirty);assertTrue(c.eqBoundReason!!.contains("Ziel nicht übernommen"))
        assertEquals(0.0,c.observed.headroomDb);assertFalse(c.observed.preset!!.eqEnabled)
        assertEquals(1,port.commands.size);c.close()
    }
    @Test fun saveRejectionShowsTheSameNativeBoundWithoutLosingTheDraftOrClaimingItWasApplied(){
        val port=EqControlPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.openPreset();c.setEqEnabled(true)
        c.save(SoundScope.PRESET);assertTrue(c.busy)
        port.results.emit(AudioSoundResult(port.commands.size.toLong(),"REJECTED","EQ_COMBINED_GAIN_LIMIT"))
        assertFalse(c.busy);assertTrue(c.preset!!.eqEnabled);assertTrue(c.presetDirty)
        assertTrue(c.eqBoundReason!!.contains("Ziel nicht übernommen"));assertFalse(c.observed.preset!!.eqEnabled)
        assertTrue(port.commands.last() is AudioSoundCommand.Save);c.close()
    }
    @Test fun oldLocalDraftWithoutEqFieldsKeepsItsOriginalBasisAndCannotSilentlyEnableEq(){
        val context=RuntimeEnvironment.getApplication();val storage=context.getSharedPreferences("no5-sound-drafts-v1",0);storage.edit().clear().commit()
        val base=soundFixture();val legacy=AudioSoundCodec.preset(base.preset!!.copy(eqPoints=listOf(SoundEqPoint("point",1000.0,8.0))))
        legacy.remove("eqEnabled");legacy.remove("reserveDb")
        val basis=JSONObject().put("configGeneration",base.configGeneration).put("presetRevision",2).put("calibrationRevision",7)
        storage.edit().putString("draft",JSONObject().put("version",2).put("preset",legacy).put("presetBasis",basis).toString()).commit()
        val port=EqControlPort(base);val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.attachStorage(context);c.openPreset()
        assertEquals(8.0,c.preset!!.eqPoints.first().gainDb);assertFalse(c.preset!!.eqEnabled);assertEquals(12.0,c.preset!!.reserveDb)
        c.setEqEnabled(true);assertTrue(port.commands.isEmpty());assertFalse(c.editable)
        c.close();assertEquals(2,JSONObject(storage.getString("draft",null)!!).getJSONObject("presetBasis").getLong("presetRevision"))
        storage.edit().clear().commit()
    }
    @Test fun failedEnableIsNotShownAsActiveAndRetryActuallySubmitsAgain(){
        val port=EqControlPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.openPreset();c.setEqEnabled(true)
        port.results.emit(AudioSoundResult(port.commands.size.toLong(),"REJECTED","EQ_COMBINED_GAIN_LIMIT"))
        assertTrue(c.eqActivationRejected);assertTrue(c.preset!!.eqEnabled);assertFalse(c.observed.preset!!.eqEnabled)
        val target=c.preset;val count=port.commands.size;c.setEqEnabled(true)
        assertEquals(count+1,port.commands.size);assertEquals(target,c.preset);assertFalse(c.eqActivationRejected)
        c.close()
    }
    @Test fun repairedLegacyWidthsCannotBeWidenedBackIntoOverlapEvenWithEqOff(){
        val a=SoundEqPoint("a",5477.0,4.1,5477.0/4715);val b=SoundEqPoint("b",13179.0,11.3,13179.0/15316)
        val p=soundFixture().preset!!.copy(eqPoints=listOf(a,b),eqEnabled=false)
        val port=EqControlPort(soundFixture().copy(preset=p));val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.openPreset()
        assertEquals(0 to 1,SoundEqBands.firstOverlap(c.preset!!.eqPoints))
        c.updatePoint(a.withBandEdge(b.lowHz-1,false));val fixed=c.preset!!
        assertFalse(SoundEqBands.overlaps(fixed.eqPoints));assertEquals(a.frequencyHz,fixed.eqPoints.first().frequencyHz);assertEquals(a.gainDb,fixed.eqPoints.first().gainDb)
        c.updatePoint(a);assertFalse(SoundEqBands.overlaps(c.preset!!.eqPoints));assertNotNull(c.eqBoundReason)
        c.changePreset(p);assertFalse(SoundEqBands.overlaps(c.preset!!.eqPoints))
        c.setEqEnabled(true);assertTrue(c.preset!!.eqEnabled);c.close()
    }

    @Test fun rejectedEditDoesNotClaimAnAlreadyRunningEqWasSwitchedOff(){
        val base=soundFixture();val p=base.preset!!.copy(eqEnabled=true)
        val port=EqControlPort(base.copy(preset=p,headroomDb=p.reserveDb));val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.openPreset()
        c.setReserve(13.0);port.results.emit(AudioSoundResult(port.commands.size.toLong(),"REJECTED","EQ_COMBINED_GAIN_LIMIT"))
        assertFalse(c.eqActivationRejected);assertTrue(c.observed.preset!!.eqEnabled)
        assertTrue(c.eqBoundReason!!.contains("Ziel nicht übernommen"));c.close()
    }

    @Test fun unavailableControllerCannotEraseAnUnrestoredOperatorDraft(){
        val context=RuntimeEnvironment.getApplication();val store=context.getSharedPreferences("no5-sound-drafts-v1",0);store.edit().clear().commit()
        val original=EqControlPort();val c=AudioSoundUiController(original,Dispatchers.Unconfined);c.attachStorage(context);c.openPreset();c.addPoint(3400.0);c.close()
        val exact=store.getString("draft",null);val cold=AudioSoundUiController(EqControlPort(AudioSoundState()),Dispatchers.Unconfined);cold.attachStorage(context);cold.close()
        assertEquals(exact,store.getString("draft",null));store.edit().clear().commit()
    }
    @Test fun restoredDraftMatchingAnOldActivePreviewStillNeedsExplicitSaveOrDiscard(){
        val context=RuntimeEnvironment.getApplication();val store=context.getSharedPreferences("no5-sound-drafts-v1",0);store.edit().clear().commit()
        val port=EqControlPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.attachStorage(context);c.openPreset();c.setReserve(13.0);c.setEqEnabled(true)
        val target=c.preset!!;val preview=(port.commands.last()as AudioSoundCommand.Preview).previewId;c.close()
        val active=port.state.value.copy(preset=target,headroomDb=13.0,preview=SoundPreview(preview,10000,true,false))
        val nextPort=EqControlPort(active);val restored=AudioSoundUiController(nextPort,Dispatchers.Unconfined);restored.attachStorage(context)
        assertEquals(target,restored.preset);assertTrue(restored.presetDirty);restored.close()
        assertTrue(JSONObject(store.getString("draft",null)!!).has("preset"));assertTrue(nextPort.commands.isEmpty())
        val again=AudioSoundUiController(EqControlPort(active.copy(preview=null)),Dispatchers.Unconfined);again.attachStorage(context);again.openPreset();assertEquals(target,again.preset);again.requestClose(SoundScope.PRESET){};again.discard();assertFalse(again.presetDirty);again.close()
        assertFalse(JSONObject(store.getString("draft",null)!!).has("preset"));store.edit().clear().commit()
    }
    @Test fun retiredIdleControllerCannotOverwriteANewerDraft(){
        val context=RuntimeEnvironment.getApplication();val store=context.getSharedPreferences("no5-sound-drafts-v1",0);store.edit().clear().commit()
        val stale=AudioSoundUiController(EqControlPort(),Dispatchers.Unconfined);stale.attachStorage(context)
        val active=AudioSoundUiController(EqControlPort(),Dispatchers.Unconfined);active.attachStorage(context);active.openPreset();active.addPoint(3500.0)
        val exact=store.getString("draft",null);stale.close();assertEquals(exact,store.getString("draft",null));active.close();assertEquals(exact,store.getString("draft",null));store.edit().clear().commit()
    }

}
