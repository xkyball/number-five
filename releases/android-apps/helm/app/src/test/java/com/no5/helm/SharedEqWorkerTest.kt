package com.no5.helm
import com.no5.domain.*
import com.no5.helm.network.soundFixture
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class SharedEqWorkerTest {
 @Test fun unchangedReadbackMustActuallyRetryOnceThenFailClosedWithoutStrandingPending(){
  val p=Port();val v=object:EqValidator{override fun project(previous:SoundPreset,requested:SoundPreset,pointId:String?,experimental:Boolean,policy:SoundEqValidationPolicy?)=requested}
  val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=v,validationDispatcher=Dispatchers.Unconfined)
  try{
   c.openPreset();val before=c.preset!!;c.updatePoint(before.eqPoints.single().copy(gainDb=2.0))
   repeat(3){p.results.emit(AudioSoundResult(p.commands.size.toLong(),"SUPERSEDED","LOCAL_VALIDATION_STALE"))}
   assertEquals(2,p.commands.size,"Only one retry is allowed against an unchanged readback")
   assertFalse(c.eqPending);assertFalse(c.eqCurveEditable);assertEquals(before,c.preset);assertTrue(c.eqActuallyEnabled)
  }finally{c.close()}
 }
 @Test fun aNewConfirmedRevisionPermitsANewRevalidation(){
  val p=Port();val v=object:EqValidator{override fun project(previous:SoundPreset,requested:SoundPreset,pointId:String?,experimental:Boolean,policy:SoundEqValidationPolicy?)=requested}
  val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=v,validationDispatcher=Dispatchers.Unconfined)
  try{
   c.openPreset();c.updatePoint(c.preset!!.eqPoints.single().copy(gainDb=2.0))
   p.results.emit(AudioSoundResult(1,"SUPERSEDED","LOCAL_VALIDATION_STALE"));assertEquals(2,p.commands.size)
   val preview=(p.commands.first()as AudioSoundCommand.Preview).previewId
   p.state.emit(p.state.value.copy(configurationRevision=p.state.value.configurationRevision+1,preview=SoundPreview(preview,9999999999999,true,false)))
   p.results.emit(AudioSoundResult(2,"SUPERSEDED","LOCAL_VALIDATION_STALE"))
   assertEquals(3,p.commands.size);assertTrue(c.eqPending);assertTrue(c.eqCurveEditable);assertTrue(c.eqActuallyEnabled)
  }finally{c.close()}
 }
 @Test fun nativeProjectionCannotSilentlyChangeAnUneditedPeer(){
  val p=Port();p.state.emit(p.state.value.copy(preset=p.state.value.preset!!.copy(eqPoints=listOf(SoundEqPoint("p",1000.0),SoundEqPoint("peer",10000.000000000002)))))
  val v=object:EqValidator{override fun project(previous:SoundPreset,requested:SoundPreset,pointId:String?,experimental:Boolean,policy:SoundEqValidationPolicy?)=
   requested.copy(eqPoints=requested.eqPoints.map{if(it.id=="peer")it.copy(frequencyHz=Math.nextDown(it.frequencyHz))else it})}
  val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=v,validationDispatcher=Dispatchers.Unconfined)
  try{c.openPreset();val before=c.preset!!;c.updatePoint(before.eqPoints.first().copy(gainDb=1.0));assertTrue(p.commands.isEmpty());assertEquals(before,c.preset);assertTrue(c.eqActuallyEnabled);assertFalse(c.eqPending);assertFalse(c.eqCurveEditable)}finally{c.close()}
 }
 private class Port:AudioSoundPort {
  override val state=MutableSourceFeed(soundFixture().let{it.copy(preset=it.preset!!.copy(eqEnabled=true,eqPoints=listOf(SoundEqPoint("p",1000.0,0.0,2.0))),capabilities=it.capabilities.copy(eqProjection=true),eqEditing=SoundEqEditing())})
  override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null);override val results=MutableSourceFeed<AudioSoundResult?>(null)
  val commands=java.util.concurrent.CopyOnWriteArrayList<AudioSoundCommand>()
  override fun submit(command:AudioSoundCommand):Long{commands+=command;return commands.size.toLong()}
  override fun refresh(){};override fun close(){};override fun setEditorVisible(visible:Boolean,eqVisible:Boolean){}
 }
 private fun until(check:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!check()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(check())}
 @Test fun oneActiveAndOnlyLatestGoalCanReachTransport(){
  val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger();val worker=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
  val validator=object:EqValidator{override fun project(previous:SoundPreset,requested:SoundPreset,pointId:String?,experimental:Boolean,policy:SoundEqValidationPolicy?):SoundPreset{if(calls.incrementAndGet()==1){entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS))};return requested}}
  val p=Port();val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=validator,validationDispatcher=worker)
  try{c.openPreset();val point=c.preset!!.eqPoints.single();c.updatePoint(point.copy(gainDb=1.0));assertTrue(entered.await(2,TimeUnit.SECONDS));repeat(100){c.updatePoint(point.copy(gainDb=(it+1)/20.0))};assertEquals(5.0,c.eqTarget!!.gainDb);assertTrue(p.commands.isEmpty());release.countDown();until{p.commands.size==1};assertEquals(5.0,(p.commands.single()as AudioSoundCommand.Preview).preset!!.eqPoints.single().gainDb);assertEquals(2,calls.get());assertEquals(0.0,c.preset!!.eqPoints.single().gainDb)}finally{release.countDown();c.close();worker.close()}
 }
 @Test fun frequencyGeometryProjectionRunsOffGestureThreadAndOnlySafeLatestTargetIsSent(){
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  val worker=Executors.newSingleThreadExecutor{r->Thread(r,"eq-validation-test")}.asCoroutineDispatcher()
  val p=Port();val points=listOf(SoundEqPoint("p",1000.0,0.0,2.0),SoundEqPoint("peer",3000.0,0.0,2.0))
  p.state.emit(p.state.value.copy(preset=p.state.value.preset!!.copy(eqPoints=points)))
  val geometry=EqGeometryProjector{before,wanted,all->
   assertTrue(Thread.currentThread().name.startsWith("eq-validation-test"),"geometry projection must not block touch dispatch")
   entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));CanonicalEqGeometryProjector.project(before,wanted,all)
  }
  val validator=object:EqValidator{override fun project(previous:SoundPreset,requested:SoundPreset,pointId:String?,experimental:Boolean,policy:SoundEqValidationPolicy?)=requested}
  val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=validator,validationDispatcher=worker,geometryProjector=geometry)
  try{
   c.openPreset();val original=c.preset!!.eqPoints.first();val wanted=original.copy(frequencyHz=4000.0)
   c.updatePoint(wanted)
   assertEquals(wanted,c.eqTarget,"hollow target publishes the latest finger intent immediately")
   assertTrue(entered.await(2,TimeUnit.SECONDS));assertTrue(p.commands.isEmpty(),"unvalidated geometry must never reach transport")
   release.countDown();until{p.commands.size==1}
   val sent=(p.commands.single() as AudioSoundCommand.Preview).preset!!.eqPoints
   assertTrue(sent.first().frequencyHz<sent.last().frequencyHz,"frequency order must remain bounded")
   assertTrue(SoundEqVisibleBands.compatible(points,sent),"all visible band boundaries must remain bounded")
   assertEquals(points.last(),sent.last(),"unmoved peer must stay bit-exact")
  }finally{release.countDown();c.close();worker.close()}
 }
 @Test fun sourceLossDiscardsActiveLocalResult(){
  val entered=CountDownLatch(1);val release=CountDownLatch(1);val worker=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
  val v=object:EqValidator{override fun project(previous:SoundPreset,requested:SoundPreset,pointId:String?,experimental:Boolean,policy:SoundEqValidationPolicy?):SoundPreset{entered.countDown();release.await(2,TimeUnit.SECONDS);return requested}}
  val p=Port();val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=v,validationDispatcher=worker)
  try{c.openPreset();c.updatePoint(c.preset!!.eqPoints.single().copy(gainDb=2.0));assertTrue(entered.await(2,TimeUnit.SECONDS));p.state.emit(p.state.value.copy(available=false));release.countDown();Thread.sleep(50);assertTrue(p.commands.isEmpty());assertNull(c.eqTarget);assertFalse(c.eqPending)}finally{release.countDown();c.close();worker.close()}
 }
 @Test fun closeWaitsForValidationAndReadbackBeforeDiscardDecision(){
  val entered=CountDownLatch(1);val release=CountDownLatch(1);val worker=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
  val v=object:EqValidator{override fun project(previous:SoundPreset,requested:SoundPreset,pointId:String?,experimental:Boolean,policy:SoundEqValidationPolicy?):SoundPreset{entered.countDown();release.await(2,TimeUnit.SECONDS);return requested}}
  val p=Port();val c=AudioSoundUiController(p,Dispatchers.Unconfined,validator=v,validationDispatcher=worker)
  try{c.openPreset();c.updatePoint(c.preset!!.eqPoints.single().copy(gainDb=2.0));assertTrue(entered.await(2,TimeUnit.SECONDS));c.requestClose(SoundScope.PRESET){};assertNull(c.closeDecision);assertTrue(p.commands.isEmpty());release.countDown();until{p.commands.size==1};val command=p.commands.single()as AudioSoundCommand.Preview;p.state.emit(p.state.value.copy(preset=command.preset,preview=SoundPreview(command.previewId,9999999999999,true,false)));p.results.emit(AudioSoundResult(1,"APPLIED"));assertEquals(SoundScope.PRESET,c.closeDecision);c.discard();assertTrue(p.commands[1]is AudioSoundCommand.Discard);assertNull(c.eqTarget);assertFalse(c.eqPending)}finally{release.countDown();c.close();worker.close()}
 }
 @Test fun absentPolicyNeverSendsAndNeverChangesConfirmedSound(){val p=Port();val c=AudioSoundUiController(p,Dispatchers.Unconfined,validationDispatcher=Dispatchers.Unconfined);try{c.openPreset();val old=c.preset;c.updatePoint(old!!.eqPoints.single().copy(gainDb=4.0));assertTrue(p.commands.isEmpty());assertEquals(old,c.preset);assertTrue(c.eqActuallyEnabled);assertFalse(c.eqPending)}finally{c.close()}}
}
