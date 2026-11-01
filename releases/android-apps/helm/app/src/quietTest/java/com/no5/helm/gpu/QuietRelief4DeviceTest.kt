package com.no5.helm.gpu

import android.app.Presentation
import android.content.Context
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.*
import android.view.*
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.*
import com.no5.helm.sonar.gl.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.*

@RunWith(AndroidJUnit4::class)
class QuietRelief4DeviceTest {
 private val inst get()=InstrumentationRegistry.getInstrumentation()
 private val main=Handler(Looper.getMainLooper())
 private fun ui(block:()->Unit){val e=AtomicReference<Throwable?>();inst.runOnMainSync{try{block()}catch(t:Throwable){e.set(t)}};e.get()?.let{throw it}}
 private fun await(message:String,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+2000;while(!condition()&&SystemClock.elapsedRealtime()<end)Thread.sleep(5);assertTrue(message,condition())}
 private fun pixels(p:Presentation):String{
  val b=Bitmap.createBitmap(800,480,Bitmap.Config.ARGB_8888);var code=-1;val done=CountDownLatch(1)
  ui{PixelCopy.request(p.window!!,b,{code=it;done.countDown()},main)}
  try{assertTrue(done.await(2,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,code)
   val hash=java.security.MessageDigest.getInstance("SHA-256");val row=IntArray(800);val bytes=java.nio.ByteBuffer.allocate(3200)
   repeat(480){y->b.getPixels(row,0,800,0,y,800,1);bytes.clear();row.forEach{bytes.putInt(it)};hash.update(bytes.array())}
   return hash.digest().joinToString(""){"%02x".format(it.toInt()and 255)}
  }finally{b.recycle()}
 }
 @Test fun canonicalReliefToneAndSharedDisplayTeardown(){
  val ctx=inst.targetContext;require(ctx.packageName=="com.no5.helm.sonarfixture")
  val ring=SonarRingBuffer(12000);repeat(12000){i->ring.push(SonarFrame(ShortArray(1024){b->(if(b in 400..420)1400 else if(b in 200..204)130 else 30).toShort()},i.toLong(),1000000L+i*50),amplitudeFullScale=4095,amplitude=SonarAmplitude(4095,12,true),pingSource=SonarPingSource.OWN,imageMapping=SonarImageValues(4.0,1.1,.01))}
  val drain=HandlerThread("quiet-relief-pixels").apply{start()}
  val reader=ImageReader.newInstance(800,480,PixelFormat.RGBA_8888,2).apply{setOnImageAvailableListener({it.acquireLatestImage()?.close()},Handler(drain.looper))}
  var display:android.hardware.display.VirtualDisplay?=null;var window:Presentation?=null;var primary:SonarGpuView?=null;var relief:SonarReliefTextureView?=null;var root:FrameLayout?=null
  val error=AtomicReference<String?>();val ready=AtomicBoolean()
  val report=JSONObject().put("passed",false).put("qualification_seconds",0)
  fun checkpoint(stage:String){report.put("stage",stage);File(ctx.getExternalFilesDir(null),"quiet-relief-progress.json").writeText(report.toString())}
  fun primaryFrame(palette:SonarPalette=SonarPalette.SUN_HIGH_CONTRAST)=SonarGpuFrame(ring,ring.lifecycleGeneration,ring.version,"private-shared-relief-source",SonarDisplay(historySeconds=600.0,palette=palette),null,4095,0.0,1023.0,true,false)
  fun primaryPresent(frame:SonarGpuFrame){val done=CountDownLatch(1);ui{primary!!.onTexturePresented={done.countDown()};primary!!.submit(frame)};val ok=done.await(2,TimeUnit.SECONDS);assertTrue("2D Window<=2s; error=${primary!!.lastError}",ok);assertNull(primary!!.lastError)}
  try{
   ui{display=(ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).createVirtualDisplay("quiet-relief-owned",800,480,160,reader.surface,DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
    window=Presentation(ctx,display!!.display);window!!.window!!.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    root=FrameLayout(window!!.context);primary=SonarGpuView(window!!.context);root!!.addView(primary,FrameLayout.LayoutParams(-1,-1));window!!.setContentView(root);window!!.show();window!!.window!!.setLayout(800,480)}
   primaryPresent(primaryFrame());checkpoint("2D_BASELINE")
   val data=SonarReliefFeed().snapshot(ring,600.0)
   var frame=ReliefFrame(data,0f,1023f,1f,1f,0f,0,false,autoMask=7)
   ui{relief=SonarReliefTextureView(window!!.context,{ready.set(it)},{error.set(it)});root!!.addView(relief,FrameLayout.LayoutParams(-1,-1));relief!!.submit(frame)}
   val until=SystemClock.elapsedRealtime()+2000
   while(!ready.get()&&error.get()==null&&SystemClock.elapsedRealtime()<until)Thread.sleep(5)
   ui{report.put("relief_available",relief!!.isAvailable).put("relief_width",relief!!.width).put("relief_height",relief!!.height).put("relief_hardware",relief!!.isHardwareAccelerated).put("relief_alpha",relief!!.alpha.toDouble())}
   fun lifecycle(label:String){
    ui{
     fun field(o:Any,n:String):Any?=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
     val owner=field(relief!!,"worker")
     report.put(label+"_frame_present",field(relief!!,"frame")!=null).put(label+"_worker_present",owner!=null)
     if(owner!=null){report.put(label+"_worker_width",field(owner,"w")).put(label+"_worker_height",field(owner,"h")).put(label+"_renderer_present",field(owner,"renderer")!=null).put(label+"_pending",(field(owner,"latest")as AtomicReference<*>).get()!=null).put(label+"_scheduled",(field(owner,"scheduled")as AtomicBoolean).get())}
    }
    val stacks=org.json.JSONArray()
    Thread.getAllStackTraces().filterKeys{it.name=="No5SonarReliefGL"}.forEach{(thread,stack)->stacks.put(JSONObject().put("state",thread.state.toString()).put("stack",org.json.JSONArray(stack.take(10).map{it.toString()})))}
    report.put(label+"_threads",stacks);checkpoint(label)
   }
   lifecycle("RELIEF_BOOTSTRAP_OBSERVED")
   if(!ready.get()&&error.get()==null){
    report.put("explicit_sized_resubmission_required",true)
    ui{relief!!.submit(frame)}
    val retryEnd=SystemClock.elapsedRealtime()+2000
    while(!ready.get()&&error.get()==null&&SystemClock.elapsedRealtime()<retryEnd)Thread.sleep(5)
    lifecycle("AFTER_EXPLICIT_SIZED_SUBMISSION")
   }
   assertTrue("Relief first buffer: ${report}; error=${error.get()}",ready.get()||error.get()!=null);assertNull(error.get());checkpoint("RELIEF_FIRST_BUFFER")
   fun present(value:ReliefFrame){val old=relief!!.surfaceTexture!!.timestamp;ui{relief!!.submit(value)};await("new Relief texture; error=${error.get()}"){relief!!.surfaceTexture!!.timestamp!=old||error.get()!=null};assertNull(error.get());val latch=CountDownLatch(1);ui{root!!.viewTreeObserver.registerFrameCommitCallback{latch.countDown()};root!!.invalidate()};assertTrue(latch.await(2,TimeUnit.SECONDS))}
   val before=pixels(window!!);frame=frame.copy(gain=20f,gamma=2.5f,threshold=.5f);present(frame);assertEquals(before,pixels(window!!));checkpoint("RELIEF_STORED_AUTO_STABLE")
   frame=frame.copy(gainOffset=1.2589254f);present(frame);assertNotEquals(before,pixels(window!!));checkpoint("RELIEF_OFFSET_CHANGED")
   ui{relief!!.dispose();root!!.removeView(relief)}
   await("own Relief GL thread closed"){Thread.getAllStackTraces().keys.none{it.isAlive&&it.name=="No5SonarReliefGL"}}
   checkpoint("RELIEF_CLOSED_PRIMARY_REMAINS")
   primaryPresent(primaryFrame(SonarPalette.MONO_AMBER));checkpoint("PRIMARY_AFTER_RELIEF_CLOSE")
   report.put("passed",true).put("diagnostic_only",true).put("same_raw_ring",true).put("capacity",12000)
  }finally{
   ui{relief?.dispose();primary?.close();window?.dismiss()}
   await("primary native resources zero"){SonarGpuNative.resources().all{it==0L}}
   display?.release();reader.close();drain.quitSafely();drain.join(1000);ring.clear()
   File(ctx.getExternalFilesDir(null),"quiet-relief.json").writeText(report.put("cleanup",true).toString())
  }
 }
}
