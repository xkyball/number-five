package com.no5.helm.gpu

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.*
import android.view.*
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.*

@RunWith(AndroidJUnit4::class)
class QuietStreamingBootstrapDeviceTest {
 private val inst get()=InstrumentationRegistry.getInstrumentation()
 private val main=Handler(Looper.getMainLooper())
 private fun ui(f:()->Unit){val error=AtomicReference<Throwable?>();inst.runOnMainSync{try{f()}catch(t:Throwable){error.set(t)}};error.get()?.let{throw it}}
 @Test fun boundedFullRingUploadWhileTwentyHzInputContinues(){
  val ctx=inst.targetContext;require(ctx.packageName=="com.no5.helm.sonarfixture")
  val report=JSONArray()
  for(count in listOf(4800,12000))for(old in listOf(true,false)){
   val ring=SonarRingBuffer(count);var id=0L
   fun add(){val next=id++;ring.push(SonarFrame(ShortArray(1024){if(it in 400..420)1400 else 30},next,1000000L+next*50),pingSource=SonarPingSource.OWN,amplitudeFullScale=4095,amplitude=SonarAmplitude(4095,12,true),imageMapping=SonarImageValues(4.0,1.1,.01),localReceivedAtNanos=System.nanoTime())}
   fun frame()=SonarGpuFrame(ring,ring.lifecycleGeneration,ring.version,"private-race",SonarDisplay(historySeconds=count/20.0),null,4095,0.0,1023.0,true,false)
   val drain=HandlerThread("quiet-race-drain").apply{start()};val producer=HandlerThread("quiet-race-input").apply{start()}
   val receiver=Handler(producer.looper);val stop=AtomicBoolean(false)
   val reader=ImageReader.newInstance(960,400,PixelFormat.RGBA_8888,2).apply{setOnImageAvailableListener({it.acquireLatestImage()?.close()},Handler(drain.looper))}
   var display:android.hardware.display.VirtualDisplay?=null;var window:Presentation?=null;var root:FrameLayout?=null
   var view:View?=null;var close:(()->Unit)?=null;var submit:((SonarGpuFrame)->Unit)?=null
   val empty=CountDownLatch(1);val populated=CountDownLatch(1);val first=AtomicLong();var started=0L
   val complete:(SonarGpuCompletion)->Unit={s->if(s.pingId<0)empty.countDown()else if(first.compareAndSet(0,System.nanoTime()))populated.countDown()}
   try{
    ui{
     display=(ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).createVirtualDisplay("quiet-race-owned",960,400,160,reader.surface,DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
     window=Presentation(ctx,display!!.display);window!!.window!!.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
     root=FrameLayout(window!!.context)
     if(old){val v=PreFenceGpuView(window!!.context);v.onTexturePresented=complete;view=v;close=v::close;submit=v::submit}
     else{val v=SonarGpuView(window!!.context);v.onTexturePresented=complete;view=v;close=v::close;submit=v::submit}
     root!!.addView(view,FrameLayout.LayoutParams(-1,-1));window!!.setContentView(root);window!!.show();window!!.window!!.setLayout(960,400);submit!!(frame())
    }
    assertTrue("empty pipeline<=2s",empty.await(2,TimeUnit.SECONDS))
    repeat(count){add()}
    started=System.nanoTime();ui{submit!!(frame())}
    val origin=SystemClock.uptimeMillis()
    var received=0
    fun receive(){if(stop.get())return;main.post{if(!stop.get()){add();submit!!(frame())}};received++;receiver.postAtTime({receive()},origin+received*50L)}
    receiver.post{receive()}
    val timely=populated.await(2,TimeUnit.SECONDS)
    report.put(JSONObject().put("pings",count).put("before_fence",old).put("window_within_2s",timely).put("first_populated_ms",if(first.get()>0)(first.get()-started)/1e6 else JSONObject.NULL).put("source_received",received).put("canonical_count",ring.count))
    File(ctx.getExternalFilesDir(null),"quiet-race-progress.json").writeText(JSONObject().put("cases",report).toString())
    if(!old)assertTrue("new cache must make a first populated Window under continuing input: $report",timely)
    assertTrue("source continues during upload",received>0);assertEquals(count,ring.count)
   }finally{
    stop.set(true);receiver.removeCallbacksAndMessages(null);producer.quitSafely();producer.join(1000)
    ui{close?.invoke();window?.dismiss()}
    val until=SystemClock.elapsedRealtime()+2000;while(SonarGpuNative.resources().any{it!=0L}&&SystemClock.elapsedRealtime()<until)Thread.sleep(5)
    assertTrue("all native owned resources released",SonarGpuNative.resources().all{it==0L})
    display?.release();reader.close();drain.quitSafely();drain.join(1000);ring.clear()
   }
  }
  File(ctx.getExternalFilesDir(null),"quiet-race.json").writeText(JSONObject().put("passed",true).put("cleanup",true).put("cases",report).put("qualification_seconds",0).toString())
 }
}
