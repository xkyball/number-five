package com.no5.helm

import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.AudioSoundPort
import com.no5.ui.No5Text
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Two short trials on the retained current editor draft. No fixture, preset
 * save or library mutation. All curve changes are real injected touch.
 * FrameMetrics is Android frame duration, never claimed as GPU time or FPS.
 */
@RunWith(AndroidJUnit4::class)
class EqReadLaneLiveTest {
    @Test fun latestTargetsAndReadLaneRemainResponsive() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit live EQ test required",InstrumentationRegistry.getArguments().getString("no5EqLive")=="1")
        val context=instrumentation.targetContext
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val metricsThread=HandlerThread("EqPairedFrameMetrics").apply{start()}
        val phaseNames=listOf("input","animation","layout","draw","sync","commandIssue","swap")
        val phaseMetrics=intArrayOf(FrameMetrics.INPUT_HANDLING_DURATION,FrameMetrics.ANIMATION_DURATION,
            FrameMetrics.LAYOUT_MEASURE_DURATION,FrameMetrics.DRAW_DURATION,FrameMetrics.SYNC_DURATION,
            FrameMetrics.COMMAND_ISSUE_DURATION,FrameMetrics.SWAP_BUFFERS_DURATION)
        data class Frame(val ns:Long,val durationNs:Long,val dropped:Int,val phases:LongArray)
        data class Change(val ns:Long,val revision:Long,val gain:Double,val enabled:Boolean)
        val frames=ConcurrentLinkedQueue<Frame>();val changes=ConcurrentLinkedQueue<Change>()
        val trials=JSONArray()
        val targetDraws=java.util.concurrent.atomic.AtomicInteger()
        val headerDraws=java.util.concurrent.atomic.AtomicInteger()
        val dir=File(context.cacheDir,"eq-read-lane-performance").apply{mkdirs()}

        lateinit var host:MainActivity
        lateinit var ctl:AudioSoundUiController
        lateinit var port:AudioSoundPort
        var originalPoint:com.no5.domain.SoundEqPoint?=null
        var originalSource:String?=null
        var originalPreset:com.no5.domain.SoundPreset?=null
        var restored=false
        var observation:com.no5.domain.SourceSubscription?=null
        var metricListener:android.view.Window.OnFrameMetricsAvailableListener?=null
        fun onMain(action:()->Unit)=instrumentation.runOnMainSync(action)
        fun waitFor(message:String,timeout:Long=8000,check:()->Boolean) {
            val deadline=SystemClock.elapsedRealtime()+timeout
            var ready=false
            while(!ready&&SystemClock.elapsedRealtime()<deadline){onMain{ready=check()};if(!ready)Thread.sleep(25)}
            assertTrue(message,ready)
        }
        fun find(root:AccessibilityNodeInfo?,labels:List<String>):AccessibilityNodeInfo? {
            if(root==null)return null
            if(root.isEnabled&&(root.contentDescription?.toString()in labels||root.text?.toString()in labels))return root
            for(i in 0 until root.childCount)find(root.getChild(i),labels)?.let{return it}
            return null
        }
        fun bounds(vararg labels:String):Rect {
            val translated=labels.map{No5Text.text(context,it)};val deadline=SystemClock.elapsedRealtime()+8000
            var found:AccessibilityNodeInfo?=null
            while(found==null&&SystemClock.elapsedRealtime()<deadline){found=find(instrumentation.uiAutomation.rootInActiveWindow,translated);if(found==null)Thread.sleep(50)}
            assertNotNull("Required native touch target unavailable",found)
            return Rect().also{found!!.getBoundsInScreen(it)}
        }
        fun event(down:Long,action:Int,x:Float,y:Float) {
            val motion=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
            try{instrumentation.sendPointerSync(motion)}finally{motion.recycle()}
        }
        fun tap(vararg labels:String) {
            val b=bounds(*labels);val now=SystemClock.uptimeMillis()
            event(now,MotionEvent.ACTION_DOWN,b.exactCenterX(),b.exactCenterY());Thread.sleep(30)
            event(now,MotionEvent.ACTION_UP,b.exactCenterX(),b.exactCenterY())
        }
        fun pointBounds():Rect {
            var label=""
            fun number(value:Double)=String.format(java.util.Locale.ROOT,if(kotlin.math.abs(value)>=100)"%.0f"else"%.1f",value)
            onMain{val p=checkNotNull(ctl.preset).eqPoints[2];label=No5Text.text(context,"EQ-Punkt %s: %s Hz, %s dB","3",number(p.frequencyHz),number(p.gainDb))}
            return bounds(label)
        }
        fun swipe(b:Rect,dy:Float) {
            val start=SystemClock.uptimeMillis();val x=b.exactCenterX();val y=b.exactCenterY()
            event(start,MotionEvent.ACTION_DOWN,x,y)
            try{for(i in 1..100){val remaining=start+i*20-SystemClock.uptimeMillis();if(remaining>0)Thread.sleep(remaining);event(start,MotionEvent.ACTION_MOVE,x,y+dy*i/100)}}
            finally{event(start,MotionEvent.ACTION_UP,x,y+dy)}
        }
        try {
            scenario.onActivity { activity ->
                host=activity
                assertTrue(activity.window.decorView.isHardwareAccelerated)
            }
            waitFor("Authenticated source unavailable",15000){host.sourceSessions.isSourceOwnerAvailable&&host.sourceSessions.gateway?.status?.phase==com.no5.gateway.GatewaySessionPhase.READY}
            onMain {
                val source=host.sourceSessions.session
                port=checkNotNull(source.audio.sound)
                ctl=host.presentationModels.acquire(source,AndroidAudioPresentationStore(host).load()).audio.soundEditor
            }
            tap("AUDIO");tap("Audio einrichten");tap("Klangpresets. EQ und Raumwirkung. ›");tap("Entwurf fortsetzen")
            waitFor("Current editable draft unavailable"){(ctl.preset?.eqPoints?.size?:0)>=3&&!ctl.eqPending&&ctl.eqCurveEditable}
            onMain{originalPoint=ctl.preset!!.eqPoints[2];originalPreset=ctl.preset;originalSource=ctl.observed.sourceGeneration}
            var enabled=false;onMain{enabled=ctl.eqActuallyEnabled}
            if(!enabled)tap("EQ aus","EQ nicht aktiv")
            waitFor("EQ preview not confirmed"){ctl.eqActuallyEnabled&&!ctl.eqPending&&port.state.value.preview!=null}
            val initial=port.state.value;val preset=checkNotNull(initial.preset);val point=preset.eqPoints[2]
            metricListener=android.view.Window.OnFrameMetricsAvailableListener {_,m,dropped->frames.add(Frame(m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),m.getMetric(FrameMetrics.TOTAL_DURATION),dropped,LongArray(phaseMetrics.size){m.getMetric(phaseMetrics[it])}))}
            onMain {host.window.addOnFrameMetricsAvailableListener(checkNotNull(metricListener),Handler(metricsThread.looper))}
            observation=port.state.subscribe {state->state.preset?.eqPoints?.firstOrNull{it.id==point.id}?.let{changes.add(Change(System.nanoTime(),state.configurationRevision,it.gainDb,state.preset?.eqEnabled==true))}}
            onMain {
                SoundTemplateDiagnostics.observeEqTarget={targetDraws.incrementAndGet();Unit}
                SoundTemplateDiagnostics.observeFit={if(it.source=="FREQUENZ")headerDraws.incrementAndGet();Unit}
            }
            for(index in 0..1) {
                waitFor("Previous touch did not settle"){ctl.eqActuallyEnabled&&!ctl.eqPending}
                instrumentation.waitForIdleSync();Thread.sleep(300)
                val first=pointBounds();val start=System.nanoTime();val wall=System.currentTimeMillis()
                val targetStart=targetDraws.get();val headerStart=headerDraws.get()
                swipe(first,50f)
                waitFor("Down movement did not settle"){ctl.eqActuallyEnabled&&!ctl.eqPending}
                val second=pointBounds()
                swipe(second,-50f)
                waitFor("Up movement did not settle"){ctl.eqActuallyEnabled&&!ctl.eqPending}
                val end=System.nanoTime();Thread.sleep(150)
                val timing=frames.filter{it.ns in start..end}.map{it.durationNs/1_000_000.0}.sorted()
                val update=changes.filter{it.ns in start..end}
                val changed=update.fold(mutableListOf<Change>()){out,row->if(out.lastOrNull()?.gain!=row.gain)out.add(row);out}
                assertTrue("Insufficient actual drawn frames",timing.size>=30)
                assertTrue("No confirmed curve changes",changed.size>=5)
                assertTrue("Confirmed EQ turned off",update.all{it.enabled})
                val current=port.state.value;val now=checkNotNull(current.preset)
                assertEquals(preset.copy(eqPoints=now.eqPoints),now)
                assertEquals(preset.eqPoints.filter{it.id!=point.id},now.eqPoints.filter{it.id!=point.id})
                assertEquals(point.frequencyHz.toBits(),now.eqPoints[2].frequencyHz.toBits())
                assertEquals(point.q.toBits(),now.eqPoints[2].q.toBits())
                assertEquals(initial.calibration,current.calibration)
                assertEquals(initial.sourceGeneration,current.sourceGeneration)
                assertEquals(initial.preview!!.id,current.preview?.id)
                val targetCount=targetDraws.get()-targetStart;val headerCount=headerDraws.get()-headerStart
                assertTrue("Local target was not drawn",targetCount>0)
                assertTrue("Pointer redraw still repaints the selected-point header",headerCount<targetCount)
                trials.put(JSONObject().put("trial",index).put("startNs",start).put("endNs",end).put("wallStartMs",wall)
                    .put("localTargetDraws",targetCount).put("selectedHeaderDraws",headerCount)
                    .put("frameMs",JSONArray(timing)).put("confirmedChanges",JSONArray(changed.map{JSONObject().put("ns",it.ns).put("revision",it.revision).put("gainDb",it.gain)}))
                    .put("phaseMs",JSONObject().also{out->phaseNames.forEachIndexed{i,name->out.put(name,JSONArray(frames.filter{it.ns in start..end}.map{it.phases[i]/1_000_000.0}))}})
                    .put("localValidation",JSONArray(ctl.localEqTimings.snapshot().filter{it.startedNs in start..end}.map{q->JSONObject().put("queuedNs",q.queuedNs).put("startedNs",q.startedNs).put("finishedNs",q.finishedNs).put("submittedNs",q.submittedNs)}))
                    .put("droppedMetricCallbacks",frames.filter{it.ns in start..end}.sumOf{it.dropped}))
                File(dir,"result.json").writeText(JSONObject().put("schema",1).put("trials",trials).put("nativeGpuTimeMeasured",false).put("saveRequested",false).toString(2))
            }
        } finally {
            var restorationFailure:Throwable?=null
            if(originalPoint!=null){
                onMain {
                    val current=ctl.preset;val before=originalPreset
                    if(ctl.eqCurveEditable&&ctl.observed.sourceGeneration==originalSource&&current?.id==before?.id&&
                        current?.eqPoints?.filter{it.id!=originalPoint!!.id}==before?.eqPoints?.filter{it.id!=originalPoint!!.id})ctl.updatePoint(originalPoint!!)
                }
                try{waitFor("Original point restoration not confirmed",8000){
                    ctl.eqActuallyEnabled&&!ctl.eqPending&&ctl.observed.preset?.eqPoints?.firstOrNull{it.id==originalPoint!!.id}==originalPoint
                };restored=true}catch(failure:Throwable){restorationFailure=failure}finally{
                    val file=File(dir,"result.json")
                    val result=if(file.exists())JSONObject(file.readText())else JSONObject()
                    file.writeText(result.put("originalPointRestored",restored).put("saveRequested",false).toString(2))
                }
            }
            onMain {SoundTemplateDiagnostics.observeEqTarget=null;SoundTemplateDiagnostics.observeFit=null}
            observation?.close()
            if(metricListener!=null)onMain{host.window.removeOnFrameMetricsAvailableListener(metricListener)}
            metricsThread.quitSafely();metricsThread.join(3000)
            restorationFailure?.let{throw it}
        }
    }
}
