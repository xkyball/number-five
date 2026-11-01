package com.no5.helm

import com.no5.domain.*
import com.no5.helm.network.soundFixture
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

private class ExperimentalPort:AudioSoundPort {
 val initial=soundFixture().let{it.copy(preset=it.preset!!.copy(eqEnabled=true,reserveDb=6.0,eqPoints=listOf(SoundEqPoint("p",1000.0,4.0,2.0))),headroomDb=6.0,
  capabilities=it.capabilities.copy(eqProjection=true,experimentalEq=true),eqEditing=SoundEqEditing())}
 override val state=MutableSourceFeed(initial)
 override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null)
 override val results=MutableSourceFeed<AudioSoundResult?>(null)
 val commands=mutableListOf<AudioSoundCommand>();var allowed=false;var visible=false
 override fun setExperimentalAllowed(allowed:Boolean){this.allowed=allowed}
 override fun submit(command:AudioSoundCommand):Long {commands+=command;return commands.size.toLong()}
 override fun setEditorVisible(visible:Boolean,eqVisible:Boolean){this.visible=visible}
 override fun refresh(){};override fun close(){}
 fun applied(id:Long=commands.size.toLong(),gain:Double?=null){
  val cmd=commands[id.toInt()-1] as AudioSoundCommand.Preview;val requested=cmd.preset!!;val experimental=cmd.eqEditing?.experimental==true
  val ceiling=requested.reserveDb+if(experimental)3 else 0
  val model=requested.copy(eqPoints=requested.eqPoints.map{it.copy(gainDb=gain?:minOf(it.gainDb,ceiling))})
  val normal=if(experimental)model.copy(eqPoints=model.eqPoints.map{it.copy(gainDb=minOf(it.gainDb,model.reserveDb))})else null
  state.emit(state.value.copy(preset=model,preview=SoundPreview(cmd.previewId,9999999999999,true,false),headroomDb=if(model.eqEnabled)model.reserveDb else 0.0,
    eqEditing=SoundEqEditing(experimental,model!=requested,normal)))
  results.emit(AudioSoundResult(id,"APPLIED"))
 }
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AudioSoundExperimentalTest {
 @Test fun intermediateNativeReadbacksStayVisibleWhileNewerMotionIsPending(){
  val p=ExperimentalPort();val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined);c.openPreset()
  val original=c.preset!!.eqPoints[0]
  c.beginEqGesture(12.0);c.updatePoint(original.copy(gainDb=5.0));c.updatePoint(original.copy(gainDb=5.5))
  p.applied(1,gain=5.0)
  assertEquals(5.0,c.preset!!.eqPoints[0].gainDb);assertTrue(c.eqPending);assertEquals(12.0,c.eqGestureRange)
  p.applied(2,gain=5.5);assertEquals(5.5,c.preset!!.eqPoints[0].gainDb);assertFalse(c.eqPending)
  c.endEqGesture();assertNull(c.eqGestureRange);c.close()
 }
 @Test fun immediateTargetNeverBecomesConfirmedOrMovesBackwardsWithOlderReceipt(){
  val p=ExperimentalPort();val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined);c.openPreset()
  val start=c.preset!!.eqPoints.single()
  c.updatePoint(start.copy(frequencyHz=1300.0,gainDb=5.0));c.updatePoint(start.copy(frequencyHz=1600.0,gainDb=5.5))
  assertEquals(1600.0,c.eqTarget!!.frequencyHz);assertEquals(start,c.preset!!.eqPoints.single())
  p.applied(1,gain=5.0)
  assertEquals(1600.0,c.eqTarget!!.frequencyHz);assertEquals(1300.0,c.preset!!.eqPoints.single().frequencyHz)
  assertTrue(c.eqPending);assertFalse(c.canSavePreset)
  p.applied(2,gain=5.5);assertNull(c.eqTarget);assertFalse(c.eqPending)
  assertEquals(1600.0,c.preset!!.eqPoints.single().frequencyHz);assertTrue(c.eqActuallyEnabled);c.close()
 }
 @Test fun geometryLimitsCutsAndNeutralTargetsWithoutChangingOtherPoints(){
  for(gain in listOf(-4.0,0.0,3.0)){
   val p=ExperimentalPort();val a=SoundEqPoint("a",500.0,gain,5.0);val b=SoundEqPoint("b",1000.0,-2.0,5.0)
   p.state.emit(p.initial.copy(preset=p.initial.preset!!.copy(eqPoints=listOf(a,b))))
   val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined);c.openPreset();c.updatePoint(a.copy(frequencyHz=5000.0))
   val request=(p.commands.last() as AudioSoundCommand.Preview).preset!!
   assertTrue(request.eqPoints[0].highHz<=b.lowHz+1e-9);assertEquals(b,request.eqPoints[1])
   assertEquals(a.gainDb,request.eqPoints[0].gainDb);assertTrue(c.eqTargetLimited)
   assertEquals(listOf(a,b),c.preset!!.eqPoints);c.close()
  }
 }
 @Test fun pointerTargetsDoNotInvalidateTheWholeFooterWhenPendingStateIsUnchanged(){
  val p=ExperimentalPort();val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined);c.openPreset()
  val point=c.preset!!.eqPoints.single();c.updatePoint(point.copy(gainDb=5.0))
  androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
  var invalidations=0;var unfilteredInvalidations=0
  val unfiltered=androidx.compose.runtime.derivedStateOf{c.eqTarget!=null}
  val observer=androidx.compose.runtime.snapshots.SnapshotStateObserver{it()}
  observer.start()
  try {
   observer.observeReads("filtered",{invalidations++}){assertTrue(c.eqTargetPending)}
   observer.observeReads("baseline",{unfilteredInvalidations++}){assertTrue(unfiltered.value)}
   repeat(100){c.updatePoint(point.copy(gainDb=4.5+it/1000.0))}
   androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
   assertEquals(0,invalidations,"A moving target may repaint its overlay, not the static footer")
   println("DIR_EQ_INVALIDATIONS filtered=$invalidations default=$unfilteredInvalidations")
  } finally {observer.stop();observer.clear();c.close()}
 }
 @Test fun expiredNativeLeaseNeverClaimsTheUnsavedEnabledDraftIsStillActive(){
  val p=ExperimentalPort();val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined);c.openPreset()
  c.updatePoint(c.preset!!.eqPoints[0].copy(gainDb=5.0));p.applied();assertTrue(c.eqActuallyEnabled)
  val draft=c.preset;p.state.emit(p.initial.copy(preset=p.initial.preset!!.copy(eqEnabled=false),preview=null))
  assertEquals(draft,c.preset);assertFalse(c.eqActuallyEnabled)
  val count=p.commands.size;c.updatePoint(c.preset!!.eqPoints[0].copy(gainDb=3.0));assertEquals(count,p.commands.size);assertEquals(draft,c.preset)
  c.setEqEnabled(true);assertTrue((p.commands.last() as AudioSoundCommand.Preview).preset!!.eqEnabled)
  p.applied();assertTrue(c.eqActuallyEnabled)
  c.beginEqGesture(18.0);c.visibility(false);assertNull(c.eqGestureRange);c.close()
 }
 @Test fun foreignReadbackNeverReplacesTheLocalGestureDraft(){
  val p=ExperimentalPort();val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined);c.openPreset();c.updatePoint(c.preset!!.eqPoints[0].copy(gainDb=5.0))
  val draft=c.preset;p.state.emit(p.initial.copy(preset=p.initial.preset!!.copy(eqPoints=listOf(SoundEqPoint("p",500.0,1.0))),preview=SoundPreview("other",9999999999999,true,false)))
  assertEquals(draft,c.preset);assertFalse(c.editable);c.close()
 }
 @Test fun rejectedProjectedActivationShowsFailureEvenThoughTheDraftWasNeverOptimisticallyEnabled(){
  val p=ExperimentalPort();p.state.emit(p.initial.copy(preset=p.initial.preset!!.copy(eqEnabled=false)))
  val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined);c.openPreset();c.setEqEnabled(true)
  assertFalse(c.preset!!.eqEnabled);assertTrue(c.eqPending)
  p.results.emit(AudioSoundResult(1,"REJECTED",reason="EQ_COMBINED_GAIN_LIMIT"))
  assertTrue(c.eqActivationRejected);assertFalse(c.eqPending);assertFalse(c.preset!!.eqEnabled)
  assertTrue(audioSoundFooter(RuntimeEnvironment.getApplication(),c).contains("Pegelreserve"))
  c.setEqEnabled(true);p.applied();assertFalse(c.eqActivationRejected);assertTrue(c.preset!!.eqEnabled);c.close()
 }
 @Test fun explicitEnableSeparatesOldBypassedBandsWithoutSavingAndAdoptsOnlyNativeConfirmedCurve(){
  val points=listOf(SoundEqPoint("cut",250.0,-3.0),SoundEqPoint("a",5477.162391840849,4.097563978750259,1.1615530991856202),SoundEqPoint("b",13179.202930309702,11.327203120104969,.8605087271267844))
  val p=ExperimentalPort();val initial=p.initial.preset!!.copy(eqEnabled=false,reserveDb=12.0,eqPoints=points)
  p.state.emit(p.initial.copy(preset=initial))
  val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined);c.openPreset();c.setEqEnabled(true)
  val request=(p.commands.single() as AudioSoundCommand.Preview).preset!!
  assertFalse(SoundEqBands.overlaps(request.eqPoints));assertEquals(initial,c.preset)
  assertEquals(points[0],request.eqPoints[0]);assertEquals(initial.reserveDb,request.reserveDb)
  assertEquals(points.map{it.frequencyHz to it.gainDb},request.eqPoints.map{it.frequencyHz to it.gainDb})
  p.applied();assertTrue(c.preset!!.eqEnabled);assertTrue(c.presetDirty)
  assertEquals("Bandbreiten angepasst · noch nicht gespeichert",c.eqBoundReason)
  assertTrue(p.commands.all{it is AudioSoundCommand.Preview});c.close()
 }
 @Test fun nativeProjectionIsAdoptedOnlyAfterAppliedAndNeverSwitchesEqOff(){
  val p=ExperimentalPort();val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined);c.openPreset()
  c.updatePoint(c.preset!!.eqPoints[0].copy(gainDb=10.0))
  assertEquals(4.0,c.preset!!.eqPoints[0].gainDb);assertTrue(c.eqPending)
  val requested=(p.commands.last() as AudioSoundCommand.Preview).preset!!
  assertEquals(6.0,requested.eqPoints[0].gainDb);assertTrue(requested.eqEnabled)
  assertEquals(6.0,c.eqTarget!!.gainDb);assertTrue(c.eqTargetLimited)
  p.applied(gain=5.8);assertEquals(5.8,c.preset!!.eqPoints[0].gainDb);assertTrue(c.preset!!.eqEnabled)
  assertFalse(c.eqPending);assertEquals("Grenze erreicht · EQ bleibt aktiv",c.eqBoundReason);c.close()
 }
 @Test fun reserveAndGainReductionAppearTogetherAfterNativeConfirmation(){
  val p=ExperimentalPort();p.state.emit(p.initial.copy(preset=p.initial.preset!!.copy(reserveDb=12.0,eqPoints=listOf(SoundEqPoint("p",1000.0,10.0,2.0)))))
  val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined);c.openPreset();c.setReserve(6.0)
  assertEquals(12.0,c.preset!!.reserveDb);assertEquals(10.0,c.preset!!.eqPoints[0].gainDb)
  p.applied();assertEquals(6.0,c.preset!!.reserveDb);assertEquals(6.0,c.preset!!.eqPoints[0].gainDb);assertEquals(1000.0,c.preset!!.eqPoints[0].frequencyHz);assertEquals(2.0,c.preset!!.eqPoints[0].q);c.close()
 }
 @Test fun experimentalCannotSaveAndOnlyNormalProjectionIsPersistedAndOffBeforeCloseDecision(){
  val context=RuntimeEnvironment.getApplication();val storage=context.getSharedPreferences("no5-sound-drafts-v1",0);storage.edit().clear().commit()
  val p=ExperimentalPort();val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined);c.attachStorage(context);c.openPreset();c.useExperimental(true)
  assertTrue(c.experimental);assertTrue(p.allowed);p.applied()
  c.updatePoint(c.preset!!.eqPoints[0].copy(gainDb=9.0));p.applied()
  val draft=JSONObject(storage.getString("draft",null)!!).getJSONObject("preset")
  assertEquals(6.0,draft.getJSONArray("eqPoints").getJSONObject(0).getDouble("gainDb"));assertFalse(draft.has("experimental"))
  val count=p.commands.size;c.save(SoundScope.PRESET);assertEquals(count,p.commands.size)
  var closed=false;c.requestClose(SoundScope.PRESET){closed=true}
  assertFalse(c.experimental);assertFalse(p.allowed);assertTrue(c.experimentEnding);assertFalse(closed);assertNull(c.closeDecision)
  p.applied();assertFalse(c.experimentEnding);assertEquals(6.0,c.preset!!.eqPoints[0].gainDb);assertEquals(SoundScope.PRESET,c.closeDecision);assertFalse(closed)
  assertTrue(p.commands.all{it is AudioSoundCommand.Preview});c.continueEditing();c.close();storage.edit().clear().commit()
 }
 @Test fun eqRoomSwitchKeepsExperimentButBackgroundAndLateResultsDoNotReactivateIt(){
  val p=ExperimentalPort();val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined);c.openPreset();c.useExperimental(true);p.applied()
  c.changeSection("room");assertTrue(c.experimental);c.changeSection("eq");assertTrue(c.experimental)
  c.updatePoint(c.preset!!.eqPoints[0].copy(gainDb=9.0));val old=p.commands.size.toLong()
  c.visibility(false);assertFalse(c.experimental);assertFalse(p.allowed);assertFalse(p.visible)
  p.applied(old);assertFalse(c.experimental);assertTrue(c.experimentEnding)
  p.applied();assertFalse(c.experimentEnding);assertTrue(c.preset!!.eqPoints[0].gainDb<=6);c.close()
 }
 @Test fun lostConnectionRetainsOnlyNormalDraftAndOldSourceCannotEnableExperiment(){
  val p=ExperimentalPort();val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined);c.openPreset();c.useExperimental(true);p.applied();c.updatePoint(c.preset!!.eqPoints[0].copy(gainDb=9.0));p.applied()
  val last=p.commands.size.toLong();p.state.emit(p.state.value.copy(available=false,writable=false))
  assertFalse(c.experimental);assertFalse(p.allowed);assertEquals(6.0,c.preset!!.eqPoints[0].gainDb)
  p.results.emit(AudioSoundResult(last,"APPLIED"));assertFalse(c.experimental)
  p.state.emit(p.initial.copy(sourceGeneration="new-source"));assertFalse(c.experimental);c.close()
 }
 @Test fun unsupportedBackendNeverReceivesExperimentalIntent(){
  val p=ExperimentalPort();p.state.emit(p.initial.copy(capabilities=p.initial.capabilities.copy(experimentalEq=false,eqProjection=false),eqEditing=null))
  val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=FixtureEqValidator,validationDispatcher=Dispatchers.Unconfined);c.openPreset();c.useExperimental(true);assertFalse(c.experimental);assertTrue(p.commands.isEmpty());c.close()
 }
}
