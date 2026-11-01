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
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class QuietHistory3DeviceTest {
 private val inst get()=InstrumentationRegistry.getInstrumentation()
 private val main=Handler(Looper.getMainLooper())
 private fun ui(body:()->Unit){val failure=AtomicReference<Throwable?>();inst.runOnMainSync{try{body()}catch(t:Throwable){failure.set(t)}};failure.get()?.let{throw it}}
 private fun present(v:SonarGpuView,f:SonarGpuFrame){val done=CountDownLatch(1);ui{v.onTexturePresented={done.countDown()};v.submit(f)};val ok=done.await(2,TimeUnit.SECONDS);assertTrue("Window commit <=2s; hardware=${v.isHardwareAccelerated}; error=${v.lastError}; progress=${v.fixtureProgress().contentToString()}",ok);assertNull(v.lastError)}
 private fun pixels(w:Presentation,width:Int,height:Int):String{
  val b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);val done=CountDownLatch(1);var code=-1
  ui{PixelCopy.request(w.window!!,b,{code=it;done.countDown()},main)}
  try{assertTrue(done.await(2,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,code);val digest=java.security.MessageDigest.getInstance("SHA-256");val row=IntArray(width);val bytes=java.nio.ByteBuffer.allocate(width*4)
   for(y in 0 until height){b.getPixels(row,0,width,0,y,width,1);bytes.clear();row.forEach{bytes.putInt(it)};digest.update(bytes.array())}
   return digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}}finally{b.recycle()}
 }
 @Test fun retainedTonePaletteOffsetGeometryAndCleanup(){
  require(inst.targetContext.packageName=="com.no5.helm.sonarfixture")
  val report=JSONArray();val ctx=inst.targetContext
  for(seconds in listOf(120,300,600))for(small in listOf(false,true)){
   val width=if(small)960 else 2000;val height=if(small)400 else 1200
   val drain=HandlerThread("quiet-private-pixels").apply{start()}
   val reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2).apply{setOnImageAvailableListener({it.acquireLatestImage()?.close()},Handler(drain.looper))}
   var display:android.hardware.display.VirtualDisplay?=null;var window:Presentation?=null;var view:SonarGpuView?=null
   val ring=SonarRingBuffer(seconds*20)
   repeat(seconds*20){i->ring.push(SonarFrame(ShortArray(1024){b->(if(b in 390..402)1500 else if(b in 201..203)120 else 25+b%30).toShort()},i.toLong(),1000000L+i*50),
    pingSource=SonarPingSource.OWN,amplitudeFullScale=4095,amplitude=SonarAmplitude(4095,12,true),imageMapping=SonarImageValues(4.0,1.1,.01))}
   var d=SonarDisplay(historySeconds=seconds.toDouble(),gainMode=SonarImageMode.AUTO,gammaMode=SonarImageMode.AUTO,thresholdMode=SonarImageMode.AUTO)
   fun frame()=SonarGpuFrame(ring,ring.lifecycleGeneration,ring.version,"private-quiet-source",d,null,4095,0.0,1023.0,true,false)
   try{
    ui{display=(ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).createVirtualDisplay("quiet-sonar-private",width,height,160,reader.surface,DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
     window=Presentation(ctx,display!!.display);view=SonarGpuView(window!!.context);val root=FrameLayout(window!!.context);root.addView(view,FrameLayout.LayoutParams(-1,-1));window!!.setContentView(root);window!!.window!!.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);window!!.show();window!!.window!!.setLayout(width,height)}
    present(view!!,frame());val before=pixels(window!!,width,height)
    d=d.copy(gainDb=30.0,gamma=2.5,threshold=.3);present(view!!,frame())
    val held=pixels(window!!,width,height);assertEquals("automatic estimates cannot recolor stored columns",before,held)
    d=d.copy(autoGainOffsetDb=2.0);present(view!!,frame());val offset=pixels(window!!,width,height)
    assertFalse("explicit gain offset changes history",held.contentEquals(offset))
    d=d.copy(palette=SonarPalette.MONO_AMBER);present(view!!,frame());assertFalse(offset.contentEquals(pixels(window!!,width,height)))
    assertEquals(seconds*20,ring.count);assertEquals(1500,ring.sampleAt(10,395))
    report.put(JSONObject().put("seconds",seconds).put("width",width).put("height",height).put("stored_auto_pixels_unchanged",true).put("offset_and_palette_changed",true))
   }finally{
    ui{view?.close();window?.dismiss()}
    val until=SystemClock.elapsedRealtime()+2000
    while(SonarGpuNative.resources().any{it!=0L}&&SystemClock.elapsedRealtime()<until)Thread.sleep(5)
    assertTrue("native owned resources return to zero",SonarGpuNative.resources().all{it==0L})
    display?.release();reader.close();drain.quitSafely();drain.join(1000);ring.clear()
   }
  }
  File(ctx.getExternalFilesDir(null),"quiet-semantic.json").writeText(JSONObject().put("cases",report).put("passed",true).put("cleanup",true).put("qualification_seconds",0).toString())
 }
}
