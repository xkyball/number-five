package com.no5.helm

import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.app.Presentation
import android.content.Context
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.*
import android.view.*
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.*
import com.no5.helm.gpu.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.*
import java.io.File
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual API29 / ART / GLES pixels, PRIVATE display only. No root Activity/source/transport is created. */
@RunWith(AndroidJUnit4::class)
class SonarGpuParityMemoryDeviceTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private val main=Handler(Looper.getMainLooper())
    private fun ui(block:()->Unit){val failed=AtomicReference<Throwable?>();instrument.runOnMainSync{try{block()}catch(e:Throwable){failed.set(e)}};failed.get()?.let{throw AssertionError(it)}}
    private fun save(name:String,result:JSONObject){val root=File(context.filesDir,"sonar-private-proof");root.mkdirs();File(root,"$name.json").writeText(result.toString(2))}
    private fun await(text:String,limit:Long=2000,predicate:()->Boolean){val end=SystemClock.elapsedRealtime()+limit;while(!predicate()&&SystemClock.elapsedRealtime()<end)Thread.sleep(10);assertTrue(text,predicate())}
    private class Fixture(context:Context,width:Int,height:Int,densityDpi:Int=160):AutoCloseable {
        val drain=HandlerThread("sonar-private-proof-output").apply{start()}
        val reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2).apply{
            setOnImageAvailableListener({it.acquireLatestImage()?.close()},Handler(drain.looper))
        }
        val display=(context.getSystemService(Context.DISPLAY_SERVICE)as DisplayManager).createVirtualDisplay("No5 private parity fixture",width,height,densityDpi,reader.surface,DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        val window=Presentation(context,display.display)
        val root=FrameLayout(window.context)
        init{
            window.window!!.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            window.window!!.setBackgroundDrawableResource(android.R.color.transparent)
            window.setContentView(root);window.show();window.window!!.setLayout(width,height)
        }
        override fun close(){window.dismiss();display.release();reader.close();drain.quitSafely()}
    }
    private fun pixels(f:Fixture,width:Int,height:Int):IntArray {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);val done=CountDownLatch(1);var code=-1
        ui{val xy=IntArray(2);f.root.getLocationInWindow(xy);PixelCopy.request(f.window.window!!,Rect(xy[0],xy[1],xy[0]+width,xy[1]+height),bitmap,{code=it;done.countDown()},main)}
        try{assertTrue(done.await(2,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,code);return IntArray(width*height).also{bitmap.getPixels(it,0,width,0,0,width,height)}}finally{bitmap.recycle()}
    }
    @Test fun ownedViewTemporaryDetachReattachKeepsWorker() {
        require(context.packageName=="com.no5.helm.sonarfixture"&&Build.VERSION.SDK_INT==29)
        var now=1_000_000L
        val c=SonarNativeController(DeterministicMockSonarSource(now={now})){now}
        var fixture:Fixture?=null;var scene:SonarGpuSceneView?=null
        val report=JSONObject().put("qualifying_seconds",0).put("hard_limit_ns",2_000_000_000L)
        val shown=java.util.concurrent.atomic.AtomicLong(-1)
        fun frame():SonarGpuFrame {val m=c.model;val ring=m.viewRing;val b=c.bounds();return SonarGpuFrame(ring,ring.lifecycleGeneration,ring.version,c.gpuSourceContext(),m.viewDisplay,m.viewMetersPerBin,m.viewAmplitudeFullScale,b.start,b.end,false,false,1f,true,c.displayRevision)}
        try {
            c.model.connect("PRIVATE LIFECYCLE");c.model.acquisition(true)
            c.model.updateContext(SonarEpoch("private-lifecycle",1,1),SonarPingSource.OWN)
            c.model.setDisplay(SonarDisplay(historySeconds=10.0,gainDb=0.0,gamma=1.0,threshold=0.0))
            c.model.setAnalysis(SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true))
            for(id in 0L..39L){now+=50;c.model.frame(SonarFrame(ShortArray(1024){b->((b*31+id*13).toInt()and 4095).toShort()},id,now),pingSource=SonarPingSource.OWN)}
            c.refresh()
            ui{fixture=Fixture(context,800,480);scene=SonarGpuSceneView(fixture!!.window.context,c);scene!!.onPresented={shown.set(it.pingId)};fixture!!.root.addView(scene,FrameLayout.LayoutParams(800,480));scene!!.update(frame())}
            await("initial private scene commit",2000){shown.get()==39L}
            val generation=scene!!.gpu.viewGeneration
            ui{fixture!!.root.removeView(scene)}
            await("detached private GPU resources released",2000){SonarGpuNative.resources().all{it==0L}}
            now+=50;c.model.frame(SonarFrame(ShortArray(1024){b->((b*47+111)and 4095).toShort()},40L,now),pingSource=SonarPingSource.OWN);c.refresh()
            val started=SystemClock.elapsedRealtimeNanos()
            ui{fixture!!.root.addView(scene,FrameLayout.LayoutParams(800,480));scene!!.update(frame())}
            await("same owned View must reattach and present",2000){shown.get()==40L}
            val elapsed=SystemClock.elapsedRealtimeNanos()-started
            assertTrue(elapsed<=2_000_000_000L);assertEquals(generation,scene!!.gpu.viewGeneration)
            report.put("same_view_generation",generation).put("reattach_presentation_ns",elapsed).put("new_ping",shown.get()).put("passed",true)
        }finally{
            ui{scene?.close();fixture?.close()};await("final private native resources zero",2000){SonarGpuNative.resources().all{it==0L}}
            c.dispose();report.put("native_resources_after",JSONArray(SonarGpuNative.resources()));save("view-lifecycle-reattach1",report)
        }
    }

    @Test fun maximumRingSameViewReparentsAcrossWindowSizes() {
        require(context.packageName=="com.no5.helm.sonarfixture"&&Build.VERSION.SDK_INT==29)
        val rows=JSONArray();val report=JSONObject().put("qualifying_seconds",0).put("hard_limit_ns",2_000_000_000L)
        var now=1_000_000L
        val c=SonarNativeController(DeterministicMockSonarSource(now={now})){now}
        var full:Fixture?=null;var small:Fixture?=null;var scene:SonarGpuSceneView?=null
        val shown=java.util.concurrent.atomic.AtomicLong(-1)
        fun frame():SonarGpuFrame {val m=c.model;val r=m.viewRing;val b=c.bounds();return SonarGpuFrame(r,r.lifecycleGeneration,r.version,c.gpuSourceContext(),m.viewDisplay,m.viewMetersPerBin,m.viewAmplitudeFullScale,b.start,b.end,false,false,1f,true,c.displayRevision)}
        try {
            ui{full=Fixture(context,2000,1200,320);small=Fixture(context,400,240,320)}
            for(seconds in listOf(240,600)) {
                c.model.resetAcquisition();c.model.connect("PRIVATE LIFECYCLE MAXIMUM");c.model.acquisition(true)
                c.model.updateContext(SonarEpoch("lifecycle-max-$seconds",1,1),SonarPingSource.OWN)
                c.model.setDisplay(SonarDisplay(historySeconds=seconds.toDouble(),gainDb=0.0,gamma=1.0,threshold=0.0))
                c.model.setAnalysis(SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true))
                var next=0L
                repeat(seconds*20){now+=50;c.model.frame(SonarFrame(ShortArray(1024){b->((b*31+next*13).toInt()and 4095).toShort()},next++,now),pingSource=SonarPingSource.OWN)}
                c.refresh();ui{scene=SonarGpuSceneView(full!!.window.context,c);scene!!.onPresented={shown.set(it.pingId)}}
                val generation=scene!!.gpu.viewGeneration
                for(cycle in 0 until 6) {
                    val f=if(cycle%2==0)full!!else small!!;val width=if(cycle%2==0)2000 else 400;val height=width*3/5
                    val id=next++;now+=50;c.model.frame(SonarFrame(ShortArray(1024){b->((b*47+id*7).toInt()and 4095).toShort()},id,now),pingSource=SonarPingSource.OWN);c.refresh()
                    val started=SystemClock.elapsedRealtimeNanos()
                    ui{f.root.addView(scene,FrameLayout.LayoutParams(width,height));scene!!.update(frame())}
                    await("max ring reparent commit $seconds/$cycle",2000){shown.get()==id}
                    val elapsed=SystemClock.elapsedRealtimeNanos()-started;assertTrue(elapsed<=2_000_000_000L)
                    val stamp=scene!!.gpu.presented!!;assertEquals(width,stamp.width);assertEquals(height,stamp.height);assertEquals(generation,stamp.viewGeneration)
                    assertEquals(seconds*20,c.model.viewRing.capacity);assertEquals(seconds*20,c.model.viewRing.count)
                    rows.put(JSONObject().put("capacity",seconds*20).put("cycle",cycle).put("width",width).put("height",height).put("presentation_ns",elapsed))
                    ui{f.root.removeView(scene)};await("detached max ring native resources zero",2000){SonarGpuNative.resources().all{it==0L}}
                }
                ui{scene!!.close();scene=null}
            }
            report.put("passed",true)
        }finally{ui{scene?.close();small?.close();full?.close()};await("all lifecycle native resources zero",2000){SonarGpuNative.resources().all{it==0L}};c.dispose();report.put("cases",rows).put("native_resources_after",JSONArray(SonarGpuNative.resources()));save("max-ring-window-reparent1",report)}
    }

    @Test fun actualComposeControllerReplacementOwnsNewGpuView() {
        require(context.packageName=="com.no5.helm.sonarfixture"&&Build.VERSION.SDK_INT==29)
        class Owner:androidx.savedstate.SavedStateRegistryOwner {
            val registry=androidx.lifecycle.LifecycleRegistry(this)
            val saved=androidx.savedstate.SavedStateRegistryController.create(this)
            override val lifecycle:androidx.lifecycle.Lifecycle get()=registry
            override val savedStateRegistry:androidx.savedstate.SavedStateRegistry get()=saved.savedStateRegistry
            init{saved.performAttach();saved.performRestore(null);registry.currentState=androidx.lifecycle.Lifecycle.State.RESUMED}
        }
        val dashboard=InstrumentationRegistry.getArguments().getString("sonar_compose_dashboard")=="true"
        val width=if(dashboard)192 else 800;val height=if(dashboard)108 else 480
        var now=1_000_000L
        fun controller(start:Long):SonarNativeController {
            val c=SonarNativeController(DeterministicMockSonarSource(now={now})){now}
            c.model.connect("PRIVATE COMPOSE CONTROLLER");c.model.acquisition(true)
            c.model.updateContext(SonarEpoch("private-compose-$start",1,1),SonarPingSource.OWN)
            c.model.setDisplay(SonarDisplay(historySeconds=10.0,gainDb=0.0,gamma=1.0,threshold=0.0))
            c.model.setAnalysis(SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true))
            for(id in start until start+40){now+=50;c.model.frame(SonarFrame(ShortArray(1024){b->((b*31+id*13).toInt()and 4095).toShort()},id,now),pingSource=SonarPingSource.OWN)}
            c.refresh();return c
        }
        val first=controller(0);val second=controller(1000);val selected=androidx.compose.runtime.mutableStateOf(first)
        var fixture:Fixture?=null;var compose:androidx.compose.ui.platform.ComposeView?=null;var owner:Owner?=null
        val report=JSONObject().put("qualifying_seconds",0).put("hard_limit_ns",2_000_000_000L)
        try {
            ui{fixture=Fixture(context,width,height);owner=Owner();compose=androidx.compose.ui.platform.ComposeView(fixture!!.window.context)
                fixture!!.window.window!!.decorView.setViewTreeLifecycleOwner(owner);fixture!!.window.window!!.decorView.setViewTreeSavedStateRegistryOwner(owner)
                fixture!!.root.setViewTreeLifecycleOwner(owner);fixture!!.root.setViewTreeSavedStateRegistryOwner(owner)
                compose!!.setViewTreeLifecycleOwner(owner);compose!!.setViewTreeSavedStateRegistryOwner(owner)
                compose!!.setContent{if(dashboard)SonarGpuPlot(selected.value,false,"DASHBOARD",androidx.compose.ui.Modifier)else SonarGpuScenePlot(selected.value,false,DepthUnit.METERS,null,androidx.compose.ui.Modifier)}
                fixture!!.root.addView(compose,FrameLayout.LayoutParams(width,height))}
            await("initial real Compose owned GPU",2000){first.lastGpuPresentation?.pingId==39L}
            val oldGeneration=first.activeGpuViews().single().viewGeneration
            val started=SystemClock.elapsedRealtimeNanos()
            ui{first.dispose();selected.value=second}
            await("new Controller must own actual AndroidView factory and presentation",2000){second.lastGpuPresentation?.pingId==1039L}
            val elapsed=SystemClock.elapsedRealtimeNanos()-started;assertTrue(elapsed<=2_000_000_000L)
            assertEquals(1,second.activeGpuViews().size);assertTrue(second.activeGpuViews().single().viewGeneration>oldGeneration)
            assertEquals(0,first.activeGpuViews().size)
            report.put("dashboard",dashboard).put("width",width).put("height",height).put("replacement_presentation_ns",elapsed).put("new_ping",second.lastGpuPresentation!!.pingId).put("passed",true)
        }finally{ui{compose?.disposeComposition();owner?.registry?.currentState=androidx.lifecycle.Lifecycle.State.DESTROYED;fixture?.close();first.dispose();second.dispose()};await("Compose lifecycle native resources zero",2000){SonarGpuNative.resources().all{it==0L}};report.put("native_resources_after",JSONArray(SonarGpuNative.resources()));save(if(dashboard)"dashboard-controller-ownership1"else"compose-controller-ownership1",report)}
    }

    @Test fun actualCanvasAndGlesPlotParity() {
        require(context.packageName=="com.no5.helm.sonarfixture"){"separate private package required"}
        require(Build.VERSION.SDK_INT==29)
        val results=JSONArray();var failing=0;var now=1_000_000L
        val source=DeterministicMockSonarSource(now={now});val controller=SonarNativeController(source){now}
        val renderer=SonarNativeRenderer(context)
        var fixture:Fixture?=null;var gpu:SonarGpuView?=null
        val report=JSONObject().put("qualifying_seconds",0).put("synthetic_pixels_only",true)
        try {
            ui{fixture=Fixture(context,384,256)};val f=fixture!!
            for(linear in listOf(false,true))for(exact in if(linear)listOf(false)else listOf(false,true))for(size in listOf(320 to 180,192 to 108)){
                controller.model.resetAcquisition();controller.model.connect("SYNTHETIC PRIVATE PIXEL COMPARISON");controller.model.acquisition(true)
                controller.model.updateContext(SonarEpoch("private-parity",1,1),SonarPingSource.OWN)
                controller.model.setDisplay(SonarDisplay(historySeconds=10.0,palette=SonarPalette.SUN_HIGH_CONTRAST,gainDb=0.0,gamma=1.0,threshold=0.0,exactPingProfiles=exact))
                controller.model.setAnalysis(SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true))
                for(id in 0 until 120){now+=50;controller.model.frame(SonarFrame(ShortArray(1024){bin->((bin*31+id*13)and 4095).toShort()},id.toLong(),now),pingSource=SonarPingSource.OWN)}
                controller.refresh();controller.interpolation(linear)
                val width=size.first;val height=size.second;val committed=CountDownLatch(1)
                ui{
                    gpu?.close();f.root.removeAllViews()
                    val canvasView=object:View(f.window.context){override fun onDraw(c:Canvas){renderer.drawPlotForProof(c,controller,false,RectF(0f,0f,width.toFloat(),height.toFloat()))}}
                    canvasView.viewTreeObserver.addOnPreDrawListener{canvasView.viewTreeObserver.registerFrameCommitCallback{committed.countDown()};true}
                    f.root.addView(canvasView,FrameLayout.LayoutParams(width,height))
                }
                assertTrue("CPU Window frame commit",committed.await(2,TimeUnit.SECONDS));val reference=pixels(f,width,height)
                val gpuDone=CountDownLatch(1)
                ui{
                    f.root.removeAllViews();gpu=SonarGpuView(f.window.context)
                    gpu!!.onTexturePresented={gpuDone.countDown()}
                    f.root.addView(gpu,FrameLayout.LayoutParams(width,height))
                    val m=controller.model;val ring=m.viewRing;val b=controller.bounds()
                    gpu!!.submit(SonarGpuFrame(ring,ring.lifecycleGeneration,ring.version,"private-parity",m.viewDisplay,m.viewMetersPerBin,m.viewAmplitudeFullScale,b.start,b.end,linear,false))
                }
                val arrived=gpuDone.await(3,TimeUnit.SECONDS)
                report.put("last_native_error",gpu?.lastError?:JSONObject.NULL).put("last_resources",JSONArray(SonarGpuNative.resources()))
                assertTrue("actual Texture update and Window presentation; error=${gpu?.lastError}",arrived)
                val actual=pixels(f,width,height);var unequal=0;var maxError=0
                for(i in reference.indices){if(reference[i]!=actual[i])unequal++;for(shift in listOf(0,8,16))maxError=maxOf(maxError,kotlin.math.abs((reference[i]ushr shift and 255)-(actual[i]ushr shift and 255)))}
                if(unequal>0){
                    failing++
                    val label="parity-${if(linear)"linear"else if(exact)"exact"else"normal"}-${width}x$height"
                    for((suffix,data)in listOf("reference" to reference,"gpu" to actual)){
                        val image=Bitmap.createBitmap(data,width,height,Bitmap.Config.ARGB_8888)
                        try{File(context.filesDir,"$label-$suffix.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
                    }
                }
                results.put(JSONObject().put("linear",linear).put("exact",exact).put("width",width).put("height",height).put("pixels",actual.size).put("unequal",unequal).put("maximum_channel_error",maxError))
                ui{gpu?.close();gpu=null;f.root.removeAllViews()};await("native resources zero"){SonarGpuNative.resources().take(4).all{it==0L}}
            }
        } finally {
            ui{gpu?.close();fixture?.close()};renderer.dispose();controller.dispose()
            report.put("cases",results).put("failed_cases",failing);save("gpu-pixel-parity1",report)
        }
        assertEquals("strict native pixel parity; preserve report on failure",0,failing)
    }
    @Test fun samePainterDestinationDiagnostic() {
        require(context.packageName=="com.no5.helm.sonarfixture")
        val report=JSONObject().put("diagnostic_only",true).put("qualifying_seconds",0)
        val results=JSONArray();var now=1_000_000L
        val controller=SonarNativeController(DeterministicMockSonarSource(now={now})){now}
        val renderer=SonarNativeRenderer(context);var fixture:Fixture?=null
        var surface:Surface?=null;var fence:no5.sonar.windowrecovery.WindowBufferPresentation?=null
        try{
            ui{fixture=Fixture(context,384,256)};val f=fixture!!
            for(linear in listOf(false,true))for(size in listOf(320 to 180,192 to 108)){
                controller.model.resetAcquisition();controller.model.connect("SYNTHETIC SAME PAINTER DESTINATION PROBE");controller.model.acquisition(true)
                controller.model.updateContext(SonarEpoch("private-canvas-destination",1,1),SonarPingSource.OWN)
                controller.model.setDisplay(SonarDisplay(historySeconds=10.0,palette=SonarPalette.SUN_HIGH_CONTRAST,gainDb=0.0,gamma=1.0,threshold=0.0))
                controller.model.setAnalysis(SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true))
                for(id in 0 until 120){now+=50;controller.model.frame(SonarFrame(ShortArray(1024){bin->((bin*31+id*13)and 4095).toShort()},id.toLong(),now),pingSource=SonarPingSource.OWN)}
                controller.refresh();controller.interpolation(linear)
                val width=size.first;val height=size.second;val committed=CountDownLatch(1)
                ui{
                    f.root.removeAllViews()
                    val original=object:View(f.window.context){override fun onDraw(c:Canvas){check(c.isHardwareAccelerated);renderer.drawPlotForProof(c,controller,false,RectF(0f,0f,width.toFloat(),height.toFloat()))}}
                    original.viewTreeObserver.addOnPreDrawListener{original.viewTreeObserver.registerFrameCommitCallback{committed.countDown()};true}
                    f.root.addView(original,FrameLayout.LayoutParams(width,height))
                }
                assertTrue(committed.await(2,TimeUnit.SECONDS));val reference=pixels(f,width,height)
                val available=CountDownLatch(1);val presented=CountDownLatch(1)
                ui{
                    f.root.removeAllViews();val texture=TextureView(f.window.context)
                    val ownFence=no5.sonar.windowrecovery.WindowBufferPresentation(texture,object:no5.sonar.windowrecovery.WindowBufferPresentation.Listener{
                        override fun viewportReady(){};override fun presented(ticket:Long){presented.countDown()};override fun discarded(ticket:Long){}
                    })
                    fence=ownFence
                    texture.surfaceTextureListener=object:TextureView.SurfaceTextureListener{
                        override fun onSurfaceTextureAvailable(value:SurfaceTexture,w:Int,h:Int){surface=Surface(value);available.countDown()}
                        override fun onSurfaceTextureSizeChanged(value:SurfaceTexture,w:Int,h:Int){ownFence.viewportChanged()}
                        override fun onSurfaceTextureDestroyed(value:SurfaceTexture):Boolean{ownFence.surfaceDestroyed();return true}
                        override fun onSurfaceTextureUpdated(value:SurfaceTexture){ownFence.textureUpdated()}
                    }
                    f.root.addView(texture,FrameLayout.LayoutParams(width,height))
                }
                assertTrue(available.await(2,TimeUnit.SECONDS));await("same-painter viewport baseline"){fence!!.ready()}
                val ticket=fence!!.beginSubmission();assertTrue(ticket>0)
                val canvas=surface!!.lockHardwareCanvas()
                try{check(canvas.isHardwareAccelerated);renderer.drawPlotForProof(canvas,controller,false,RectF(0f,0f,width.toFloat(),height.toFloat()))}
                finally{surface!!.unlockCanvasAndPost(canvas)}
                fence!!.completeSubmission(ticket);assertTrue(presented.await(2,TimeUnit.SECONDS));val actual=pixels(f,width,height)
                var unequal=0;var maximum=0
                for(at in reference.indices){if(reference[at]!=actual[at])unequal++;for(shift in listOf(0,8,16))maximum=maxOf(maximum,kotlin.math.abs((reference[at]ushr shift and 255)-(actual[at]ushr shift and 255)))}
                results.put(JSONObject().put("linear",linear).put("width",width).put("height",height).put("unequal",unequal).put("maximum_channel_error",maximum).put("both_canvases_hardware",true))
                val label="same-painter-${if(linear)"linear"else"normal"}-${width}x$height"
                for((name,data)in listOf("root" to reference,"texture" to actual)){
                    val image=Bitmap.createBitmap(data,width,height,Bitmap.Config.ARGB_8888)
                    try{File(context.filesDir,"$label-$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
                }
                ui{fence?.close();fence=null;f.root.removeAllViews()};surface?.release();surface=null
            }
            report.put("diagnostic_completed",true)
        }finally{ui{fence?.close();fixture?.close()};surface?.release();renderer.dispose();controller.dispose();report.put("cases",results);save("same-painter-destination1",report)}
    }
    @Test fun sameDestinationCanvasGlesParity() {
        require(context.packageName=="com.no5.helm.sonarfixture")
        val report=JSONObject().put("same_destination_strict_RGBA_parity",true).put("qualifying_seconds",0)
        val results=JSONArray();var now=1_000_000L
        val controller=SonarNativeController(DeterministicMockSonarSource(now={now})){now}
        val renderer=SonarNativeRenderer(context);var fixture:Fixture?=null
        var surface:Surface?=null;var fence:no5.sonar.windowrecovery.WindowBufferPresentation?=null
        try{
            ui{fixture=Fixture(context,384,256)};val f=fixture!!
            var failedCases=0
            for(linear in listOf(false,true))for(exact in if(linear)listOf(false)else listOf(false,true))for(size in listOf(320 to 180,192 to 108)){
                controller.model.resetAcquisition();controller.model.connect("SYNTHETIC SAME PAINTER DESTINATION PROBE");controller.model.acquisition(true)
                controller.model.updateContext(SonarEpoch("private-canvas-destination",1,1),SonarPingSource.OWN)
                controller.model.setDisplay(SonarDisplay(historySeconds=10.0,palette=SonarPalette.SUN_HIGH_CONTRAST,gainDb=0.0,gamma=1.0,threshold=0.0,exactPingProfiles=exact))
                controller.model.setAnalysis(SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true))
                for(id in 0 until 120){now+=50;controller.model.frame(SonarFrame(ShortArray(1024){bin->((bin*31+id*13)and 4095).toShort()},id.toLong(),now),pingSource=SonarPingSource.OWN)}
                controller.refresh();controller.interpolation(linear)
                val width=size.first;val height=size.second;val committed=CountDownLatch(1)
                ui{
                    f.root.removeAllViews()
                    val original=object:View(f.window.context){override fun onDraw(c:Canvas){check(c.isHardwareAccelerated);renderer.drawPlotForProof(c,controller,false,RectF(0f,0f,width.toFloat(),height.toFloat()))}}
                    original.viewTreeObserver.addOnPreDrawListener{original.viewTreeObserver.registerFrameCommitCallback{committed.countDown()};true}
                    f.root.addView(original,FrameLayout.LayoutParams(width,height))
                }
                assertTrue(committed.await(2,TimeUnit.SECONDS));val reference=pixels(f,width,height)
                val available=CountDownLatch(1);val presented=CountDownLatch(1)
                ui{
                    f.root.removeAllViews();val texture=TextureView(f.window.context)
                    val ownFence=no5.sonar.windowrecovery.WindowBufferPresentation(texture,object:no5.sonar.windowrecovery.WindowBufferPresentation.Listener{
                        override fun viewportReady(){};override fun presented(ticket:Long){presented.countDown()};override fun discarded(ticket:Long){}
                    })
                    fence=ownFence
                    texture.surfaceTextureListener=object:TextureView.SurfaceTextureListener{
                        override fun onSurfaceTextureAvailable(value:SurfaceTexture,w:Int,h:Int){surface=Surface(value);available.countDown()}
                        override fun onSurfaceTextureSizeChanged(value:SurfaceTexture,w:Int,h:Int){ownFence.viewportChanged()}
                        override fun onSurfaceTextureDestroyed(value:SurfaceTexture):Boolean{ownFence.surfaceDestroyed();return true}
                        override fun onSurfaceTextureUpdated(value:SurfaceTexture){ownFence.textureUpdated()}
                    }
                    f.root.addView(texture,FrameLayout.LayoutParams(width,height))
                }
                assertTrue(available.await(2,TimeUnit.SECONDS));await("same-painter viewport baseline"){fence!!.ready()}
                val ticket=fence!!.beginSubmission();assertTrue(ticket>0)
                val canvas=surface!!.lockHardwareCanvas()
                try{check(canvas.isHardwareAccelerated);renderer.drawPlotForProof(canvas,controller,false,RectF(0f,0f,width.toFloat(),height.toFloat()))}
                finally{surface!!.unlockCanvasAndPost(canvas)}
                fence!!.completeSubmission(ticket);assertTrue(presented.await(2,TimeUnit.SECONDS));val actual=pixels(f,width,height)
                var unequal=0;var maximum=0
                for(at in reference.indices){if(reference[at]!=actual[at])unequal++;for(shift in listOf(0,8,16))maximum=maxOf(maximum,kotlin.math.abs((reference[at]ushr shift and 255)-(actual[at]ushr shift and 255)))}
                results.put(JSONObject().put("linear",linear).put("exact",exact).put("width",width).put("height",height).put("unequal",unequal).put("maximum_channel_error",maximum).put("both_canvases_hardware",true))
                val label="same-painter-${if(linear)"linear"else if(exact)"exact"else"normal"}-${width}x$height"
                for((name,data)in listOf("root" to reference,"texture" to actual)){
                    val image=Bitmap.createBitmap(data,width,height,Bitmap.Config.ARGB_8888)
                    try{File(context.filesDir,"$label-$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
                }
                ui{fence?.close();fence=null;f.root.removeAllViews()};surface?.release();surface=null
                val gpuDone=CountDownLatch(1);var candidate:SonarGpuView?=null
                try{
                    ui{
                        candidate=SonarGpuView(f.window.context);candidate!!.onTexturePresented={gpuDone.countDown()}
                        f.root.addView(candidate,FrameLayout.LayoutParams(width,height))
                        val m=controller.model;val ring=m.viewRing;val b=controller.bounds()
                        candidate!!.submit(SonarGpuFrame(ring,ring.lifecycleGeneration,ring.version,"same-destination",m.viewDisplay,m.viewMetersPerBin,m.viewAmplitudeFullScale,b.start,b.end,linear,false))
                    }
                    val arrived=gpuDone.await(2,TimeUnit.SECONDS);assertTrue("candidate hardware presentation ${candidate?.lastError}",arrived)
                    val gles=pixels(f,width,height);var different=0
                    for(at in actual.indices)if(actual[at]!=gles[at])different++
                    if(different>0)failedCases++
                    results.getJSONObject(results.length()-1).put("GLES_vs_same_destination_reference_unequal",different)
                    for((name,data)in listOf("reference" to actual,"gles" to gles)){
                        val image=Bitmap.createBitmap(data,width,height,Bitmap.Config.ARGB_8888)
                        try{File(context.filesDir,"$label-same-target-$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
                    }
                }finally{ui{candidate?.close();f.root.removeAllViews()};await("own native resources released"){SonarGpuNative.resources().take(4).all{it==0L}}}
            }
            report.put("failed_same_destination_cases",failedCases)
            assertEquals("same-destination RGBA remains strictly exact",0,failedCases)
            report.put("passed",true)
        }finally{ui{fence?.close();fixture?.close()};surface?.release();renderer.dispose();controller.dispose();report.put("cases",results);save("same-destination-gles-parity1",report)}
    }
    @Test fun artVisibleRawOwnersAreBoundedAndCollectible() {
        require(context.packageName=="com.no5.helm.sonarfixture")
        val report=JSONObject().put("qualifying_seconds",0).put("explicit_gc_in_test_only",true);val cases=JSONArray()
        try{
            for(seconds in listOf(240.0,600.0)){
                val cap=sonarVisiblePingBudget(seconds);val queue=ReferenceQueue<SonarFrame>();val old=ArrayList<WeakReference<SonarFrame>>(32)
                var ring=SonarRingBuffer(cap)
                for(id in 0 until cap+64){
                    val frame=SonarFrame(ShortArray(1024){bin->((id+bin)and 65535).toShort()},id.toLong(),1_000_000L+id*50L)
                    if(id<32)old+=WeakReference(frame,queue)
                    ring.push(frame)
                }
                assertEquals(cap,ring.count);assertEquals(cap*2048L,ring.allocatedSampleBytes)
                var collected=0
                val until=SystemClock.elapsedRealtime()+3000
                while(collected<32&&SystemClock.elapsedRealtime()<until){System.gc();System.runFinalization();Thread.sleep(30);while(queue.poll()!=null)collected++}
                assertEquals("overwritten immutable RAW owners must collect on actual ART",32,collected)
                assertEquals(cap+63L,ring.pingIdAt(0));assertEquals(64L,ring.pingIdAt(cap-1))
                val newest=ring.atAge(0)!!;assertEquals((cap+63)and 65535,newest.samples[0].toInt()and 65535)
                ring=ring.moveToCapacity(200);assertEquals(200,ring.count);ring.clear();assertEquals(0L,ring.allocatedSampleBytes)
                cases.put(JSONObject().put("seconds",seconds).put("capacity",cap).put("collected_overwritten_owners",collected).put("cleared_raw_bytes",ring.allocatedSampleBytes).put("native_heap_bytes",Debug.getNativeHeapAllocatedSize()))
            }
        }finally{report.put("cases",cases);save("art-raw-owner-memory1",report)}
    }
    /** Both implementations paint into the identical hardware TextureView destination. */
    private inner class CanvasDestination(val f:Fixture,val width:Int,val height:Int,val opaque:Boolean=true):AutoCloseable {
        private val available=CountDownLatch(1)
        private val completed=AtomicReference<CountDownLatch?>()
        private var surface:Surface?=null
        private lateinit var fence:no5.sonar.windowrecovery.WindowBufferPresentation
        private lateinit var ownedView:TextureView
        init {
            ui {
                val texture=TextureView(f.window.context).apply{isOpaque=opaque};ownedView=texture
                fence=no5.sonar.windowrecovery.WindowBufferPresentation(texture,object:no5.sonar.windowrecovery.WindowBufferPresentation.Listener{
                    override fun viewportReady(){}
                    override fun presented(ticket:Long){completed.getAndSet(null)?.countDown()}
                    override fun discarded(ticket:Long){}
                })
                texture.surfaceTextureListener=object:TextureView.SurfaceTextureListener{
                    override fun onSurfaceTextureAvailable(value:SurfaceTexture,w:Int,h:Int){surface=Surface(value);available.countDown()}
                    override fun onSurfaceTextureSizeChanged(value:SurfaceTexture,w:Int,h:Int){fence.viewportChanged()}
                    override fun onSurfaceTextureDestroyed(value:SurfaceTexture):Boolean{fence.surfaceDestroyed();return true}
                    override fun onSurfaceTextureUpdated(value:SurfaceTexture){fence.textureUpdated()}
                }
                f.root.addView(texture,FrameLayout.LayoutParams(width,height))
            }
            assertTrue(available.await(2,TimeUnit.SECONDS));await("hardware Canvas destination ready"){fence.ready()}
        }
        fun paint(controller:SonarNativeController,renderer:SonarNativeRenderer,night:Boolean,scale:Float=1f,target:RectF?=null):Long {
            val start=SystemClock.elapsedRealtimeNanos();val done=CountDownLatch(1);completed.set(done)
            val ticket=fence.beginSubmission();assertTrue(ticket>0)
            val c=surface!!.lockHardwareCanvas()
            try{check(c.isHardwareAccelerated);if(!opaque)c.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);c.scale(scale,scale);renderer.drawPlotForProof(c,controller,night,target?:RectF(0f,0f,width/scale,height/scale))}
            finally{surface!!.unlockCanvasAndPost(c)}
            fence.completeSubmission(ticket);assertTrue("Canvas buffer and Window commit",done.await(2,TimeUnit.SECONDS))
            return SystemClock.elapsedRealtimeNanos()-start
        }
        override fun close(){ui{fence.close();f.root.removeView(ownedView)};surface?.release()}
    }
    private fun gpuFrame(c:SonarNativeController,night:Boolean=false,scale:Float=1f):SonarGpuFrame {
        val m=c.model;val r=m.viewRing;val b=c.bounds()
        return SonarGpuFrame(r,r.lifecycleGeneration,r.version,"private-native-proof",m.viewDisplay,m.viewMetersPerBin,m.viewAmplitudeFullScale,b.start,b.end,c.linearInterpolation,night,scale)
    }
    private fun present(view:SonarGpuView,c:SonarNativeController,night:Boolean=false,scale:Float=1f):Long {
        val done=CountDownLatch(1);val start=SystemClock.elapsedRealtimeNanos()
        ui{view.onTexturePresented={done.countDown()};view.submit(gpuFrame(c,night,scale))}
        assertTrue("GLES buffer and Window commit ${view.lastError}",done.await(5,TimeUnit.SECONDS));assertNull(view.lastError)
        return SystemClock.elapsedRealtimeNanos()-start
    }
    private fun gcOwners(queue:ReferenceQueue<SonarFrame>,expected:Int):Int {
        var n=0;val deadline=SystemClock.elapsedRealtime()+3000
        while(n<expected&&SystemClock.elapsedRealtime()<deadline){System.gc();System.runFinalization();Thread.sleep(30);while(queue.poll()!=null)n++}
        assertEquals("all evicted immutable RAW owners collectible on ART",expected,n);return n
    }
    @Test fun broaderSameDestinationGeometryPaletteAndViewportParity() {
        require(context.packageName=="com.no5.helm.sonarfixture")
        data class Case(val name:String,val palette:SonarPalette,val night:Boolean=false,val linear:Boolean=false,
            val exact:Boolean=false,val geometry:Boolean=false,val gap:Boolean=false,val crop:Boolean=false,
            val width:Int=320,val height:Int=180,val anchor:Boolean=false,val scale:Float=1f)
        val cases=SonarPalette.entries.flatMap{p->listOf(Case("${p}-day-transfer",p,crop=true),Case("${p}-night-mean",p,true,true,crop=true))}+
            listOf(Case("adaptive-geometry-raw",SonarPalette.SUN_WHITE,geometry=true),
                Case("adaptive-geometry-mean",SonarPalette.MONO_AMBER,linear=true,geometry=true,crop=true),
                Case("transport-gap-exact",SonarPalette.NIGHT_RED,exact=true,gap=true),
                Case("transport-gap-mean",SonarPalette.SUN_WHITE,linear=true,gap=true),
                Case("full-pixel-viewport",SonarPalette.MONO_AMBER,linear=true,crop=true,width=1600,height=780),
                Case("small-dashboard",SonarPalette.SUN_WHITE,linear=true,width=192,height=108),
                Case("tiny-viewport",SonarPalette.NIGHT_RED,true,width=37,height=21),
                Case("source-epoch-clear",SonarPalette.SUN_WHITE,exact=true,width=799,height=393),
                Case("bottom-anchored-mean",SonarPalette.SUN_HIGH_CONTRAST,linear=true,width=640,height=360,anchor=true),
                Case("fractional-scale-075",SonarPalette.SUN_WHITE,linear=true,width=799,height=393,scale=.75f),
                Case("fractional-scale-125",SonarPalette.MONO_AMBER,linear=true,width=799,height=393,scale=1.25f))
        val selected=InstrumentationRegistry.getArguments().getString("sonar_diagnostic_cases")
        val diagnosticNames=selected?.split(',')?.toSet()
        require(diagnosticNames==null || diagnosticNames.isNotEmpty()&&diagnosticNames.all { name -> cases.any { it.name==name } })
        val channelGate=InstrumentationRegistry.getArguments().getString("sonar_rgba_channel_gate")?.toInt()?:0
        require(channelGate==0||channelGate==2)
        val result=JSONObject().put("qualification_seconds",0).put("pixel_bit_equality_required",channelGate==0).put("RGBA_channel_error_gate",channelGate)
            .put("diagnostic_selection",selected?:JSONObject.NULL);val rows=JSONArray();var failures=0
        var now=1_000_000L;val c=SonarNativeController(DeterministicMockSonarSource(now={now})){now}
        val renderer=SonarNativeRenderer(context);var fixture:Fixture?=null
        try {
            ui{fixture=Fixture(context,1664,900)};val f=fixture!!
            for(case in cases.filter { diagnosticNames==null || it.name in diagnosticNames }){
                c.model.resetAcquisition();c.model.connect("PRIVATE GEOMETRY PALETTE PIXELS");c.model.acquisition(true)
                c.model.updateContext(SonarEpoch(case.name,2,3),SonarPingSource.OWN)
                for(id in 0 until 123){
                    now+=if(case.gap&&id==60)600L else 50L
                    val depth=if(case.geometry&&id<61)2.046 else 3.069
                    val bottom=if(case.anchor)SonarBottom(300.0+id*.5,1.0,1.0,SonarBottomSource.M1_RAW)else SonarBottom()
                    val presentation=if(case.anchor)SonarPresentation(id.toLong(),SonarAnalysisQuality.LOCKED,null,"off","off",false,false,null,false)else null
                    c.model.setAnalysis(SonarGeometry(depth,SonarTxReference.HOST_VERIFIED),bottom,presentation,case.anchor,amplitude=SonarAmplitude(4095,12,true))
                    c.model.frame(SonarFrame(ShortArray(1024){bin->((bin*31+id*13)and 4095).toShort()},id.toLong(),now),pingSource=SonarPingSource.OWN)
                }
                c.model.setDisplay(SonarDisplay(historySeconds=10.0,palette=case.palette,gainDb=3.0,gamma=.7,threshold=.1,
                    exactPingProfiles=case.exact,cropStart=if(case.crop)43.0 else 0.0,cropEnd=if(case.crop)789.0 else 1023.0))
                c.refresh();c.interpolation(case.linear)
                val reference=CanvasDestination(f,case.width,case.height).use{it.paint(c,renderer,case.night,case.scale);pixels(f,case.width,case.height)}
                var gpu:SonarGpuView?=null
                try{
                    ui{gpu=SonarGpuView(f.window.context);f.root.addView(gpu,FrameLayout.LayoutParams(case.width,case.height))}
                    present(gpu!!,c,case.night,case.scale);val actual=pixels(f,case.width,case.height)
                    assertTrue("plot alpha remains bit exact",actual.indices.all{(actual[it] ushr 24)==(reference[it] ushr 24)})
                    var unequal=0;var maximum=0
                    for(i in actual.indices){if(actual[i]!=reference[i])unequal++;for(shift in listOf(0,8,16))maximum=maxOf(maximum,kotlin.math.abs((actual[i]ushr shift and 255)-(reference[i]ushr shift and 255)))}
                    rows.put(JSONObject().put("name",case.name).put("pixels",actual.size).put("raster_scale",case.scale).put("bottom_anchors",case.anchor).put("unequal",unequal).put("maximum_channel_error",maximum))
                    if(unequal!=0){
                        val columns=JSONArray()
                        for(slot in listOf(10,40,61,100)){
                            val expected=IntArray(1024);val observed=IntArray(1024);renderer.copyColorColumnForProof(slot,expected)
                            val copied=CountDownLatch(1);val error=AtomicReference<Throwable?>()
                            gpu!!.fixtureOwnHandle{handle->try{SonarGpuTestHooks.colorColumn(handle,slot,observed)}catch(e:Throwable){error.set(e)}finally{copied.countDown()}}
                            assertTrue(copied.await(2,TimeUnit.SECONDS));error.get()?.let{throw AssertionError(it)}
                            var mismatches=0;for(bin in expected.indices)if(expected[bin]!=observed[bin])mismatches++
                            assertEquals("derived color LUT bytes must stay exact",0,mismatches)
                            columns.put(JSONObject().put("slot",slot).put("derived_color_unequal",mismatches))
                        }
                        rows.getJSONObject(rows.length()-1).put("derived_color_columns",columns)
                        if(maximum>channelGate)failures++
                        val coordinates=JSONArray()
                        for(index in actual.indices)if(actual[index]!=reference[index]&&coordinates.length()<64)
                            coordinates.put(JSONArray(listOf(index%case.width,index/case.width,reference[index].toLong()and 0xffffffffL,actual[index].toLong()and 0xffffffffL)))
                        rows.getJSONObject(rows.length()-1).put("first_unequal_RGBA",coordinates)
                        for((label,data)in listOf("old" to reference,"gles" to actual)){
                        val image=Bitmap.createBitmap(data,case.width,case.height,Bitmap.Config.ARGB_8888)
                        try{File(context.filesDir,"broader-${case.name}-$label.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
                    }}
                }finally{ui{gpu?.close();f.root.removeAllViews()};await("native release"){SonarGpuNative.resources().take(4).all{it==0L}}}
            }
            result.put("failed_cases",failures);assertEquals("authorised same-destination channel bound",0,failures);result.put("passed",true)
        }finally{ui{fixture?.close()};renderer.dispose();c.dispose();result.put("cases",rows);result.put("own_files",JSONArray(context.filesDir.list()?.toList()?:emptyList<String>()));save("gpu-broader-parity1",result)}
    }
    @Test fun fractionalFullStageViewportAndResizeParity() {
        require(context.packageName=="com.no5.helm.sonarfixture")
        val result=JSONObject().put("qualifying_seconds",0).put("strict_RGBA_gate",0).put("scope","Data-only old painter versus GLES at actual full-stage origin, fractional viewport, and same-View resize; no full-app UI acceptance")
        val rows=JSONArray();var fixture:Fixture?=null;var gpu:SonarGpuView?=null;var now=1_000_000L
        val c=SonarNativeController(DeterministicMockSonarSource(now={now})){now};val renderer=SonarNativeRenderer(context)
        try {
            c.model.resetAcquisition();c.model.connect("PRIVATE FULL STAGE COORDINATES");c.model.acquisition(true)
            c.model.updateContext(SonarEpoch("full-stage",1,1),SonarPingSource.OWN);c.model.setDisplay(SonarDisplay(historySeconds=10.0))
            c.model.setAnalysis(SonarGeometry(3.069,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true))
            for(id in 0 until 123){now+=50;c.model.frame(SonarFrame(ShortArray(1024){((it*31+id*13)and 4095).toShort()},id.toLong(),now),pingSource=SonarPingSource.OWN)}
            c.refresh();c.interpolation(true)
            val sizes=listOf(1600 to 960,1501 to 901,799 to 481)
            ui{fixture=Fixture(context,1664,1024)};val f=fixture!!
            val references=sizes.map{size->CanvasDestination(f,size.first,size.second,false).use{
                it.paint(c,renderer,false,size.first/2000f,RectF(SonarLayout.PLOT_LEFT,SonarLayout.TOP,SonarLayout.plotRight(true),SonarLayout.BOTTOM));pixels(f,size.first,size.second)}}
            ui{gpu=SonarGpuView(f.window.context);f.root.addView(gpu,FrameLayout.LayoutParams(sizes[0].first,sizes[0].second))}
            var lastGeneration=0L;var failures=0
            for((index,size)in sizes.withIndex()){
                ui{gpu!!.layoutParams=FrameLayout.LayoutParams(size.first,size.second)};await("actual full stage size"){gpu!!.width==size.first&&gpu!!.height==size.second}
                val done=CountDownLatch(1);ui{gpu!!.onTexturePresented={done.countDown()};gpu!!.submit(gpuFrame(c,scale=size.first/2000f).copy(fullStage=true,displayRevision=c.displayRevision))}
                assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(gpu!!.lastError)
                val stamp=gpu!!.presented!!;assertTrue(stamp.viewportGeneration>lastGeneration);lastGeneration=stamp.viewportGeneration
                val ratio=size.first/2000f;assertEquals(SonarLayout.PLOT_LEFT*ratio,stamp.plotLeft,0f);assertEquals(SonarLayout.TOP*ratio,stamp.plotTop,0f)
                assertEquals(SonarLayout.plotWidth(true)*ratio,stamp.plotWidth,0f);assertEquals(SonarLayout.HEIGHT*ratio,stamp.plotHeight,0f)
                val actual=pixels(f,size.first,size.second);val expected=references[index];var unequal=0;var maximum=0;var outside=0
                for(at in actual.indices)if(actual[at]!=expected[at]){
                    unequal++;for(shift in listOf(0,8,16))maximum=maxOf(maximum,kotlin.math.abs((actual[at]ushr shift and 255)-(expected[at]ushr shift and 255)))
                    val x=at%size.first;val y=at/size.first
                    if(x<kotlin.math.floor(stamp.plotLeft+.5f)||x>=kotlin.math.floor(stamp.plotLeft+stamp.plotWidth+.5f)||y<kotlin.math.floor(stamp.plotTop+.5f)||y>=kotlin.math.floor(stamp.plotTop+stamp.plotHeight+.5f))outside++
                }
                val name="full-stage-${size.first}x${size.second}";val row=JSONObject().put("name",name).put("width",size.first).put("height",size.second).put("unequal",unequal).put("maximum_channel_error",maximum).put("outside_plot_unequal",outside).put("viewport_generation",stamp.viewportGeneration).put("density",stamp.density)
                rows.put(row)
                if(unequal!=0){failures++;for((label,data)in listOf("old" to expected,"gles" to actual)){
                    val image=Bitmap.createBitmap(data,size.first,size.second,Bitmap.Config.ARGB_8888);try{File(context.filesDir,"broader-$name-$label.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
                }}
            }
            result.put("failed_cases",failures);assertEquals("full stage native pixel parity",0,failures);result.put("passed",true)
        }finally{ui{gpu?.close();fixture?.close()};renderer.dispose();c.dispose();result.put("cases",rows);save("full-stage-parity1",result)}
    }

    @Test fun actualOpaqueSonarSceneAndCursorCompositeParity() {
        require(context.packageName=="com.no5.helm.sonarfixture")
        val nativePixels=InstrumentationRegistry.getArguments().getString("sonar_native_pixel_viewport")=="true"
        val channelGate=InstrumentationRegistry.getArguments().getString("sonar_rgba_channel_gate")?.toInt()?:0
        require(channelGate==0||channelGate==2)
        val result=JSONObject().put("native_pixel_density_320",nativePixels).put("qualifying_seconds",0).put("pixel_bit_equality_required",channelGate==0).put("RGBA_channel_error_gate",channelGate).put("outside_plot_unequal_gate",0).put("scope","Actual legacy Sonar scene CanvasView versus full-stage GLES plus unchanged Canvas overlay, including cursor and A-scope; private VirtualDisplay")
        val rows=JSONArray();var fixture:Fixture?=null;var gpu:SonarGpuView?=null;var gpuScene:SonarGpuSceneView?=null;var now=1_000_000L
        val c=SonarNativeController(DeterministicMockSonarSource(now={now})){now}
        val referenceRenderer=SonarNativeRenderer(context);val overlayRenderer=SonarNativeRenderer(context,externalPlot=true)
        try {
            c.model.resetAcquisition();c.model.connect("PRIVATE NATIVE SCENE");c.model.acquisition(true)
            c.model.updateContext(SonarEpoch("scene",1,1),SonarPingSource.OWN);c.model.setDisplay(SonarDisplay(historySeconds=10.0))
            c.model.setAnalysis(SonarGeometry(3.069,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true))
            for(id in 0 until 123){now+=50;c.model.frame(SonarFrame(ShortArray(1024){((it*31+id*13)and 4095).toShort()},id.toLong(),now),pingSource=SonarPingSource.OWN)}
            c.refresh();c.interpolation(true);c.inspect(.85f,.3f)
            ui{fixture=if(nativePixels)Fixture(context,2064,1264,320)else Fixture(context,1664,1024)};val f=fixture!!;var failures=0
            fun scene(renderer:SonarNativeRenderer,backgroundOnly:Boolean=false)=object:View(f.window.context){override fun onDraw(canvas:Canvas){canvas.save();val ratio=width/2000f;canvas.scale(ratio,ratio);renderer.draw(canvas,c,false,backgroundOnly=backgroundOnly);canvas.restore()}}
            fun commit(view:View,done:CountDownLatch){view.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{view.viewTreeObserver.removeOnPreDrawListener(this);view.viewTreeObserver.registerFrameCommitCallback{done.countDown()};return true}});view.invalidate()}
            for(size in if(nativePixels)listOf(2000 to 1200)else listOf(1600 to 960,1501 to 901,799 to 481)){
                var oldView:View?=null;val referenceDone=CountDownLatch(1)
                ui{oldView=scene(referenceRenderer);f.root.addView(oldView,FrameLayout.LayoutParams(size.first,size.second));commit(oldView!!,referenceDone)}
                assertTrue(referenceDone.await(5,TimeUnit.SECONDS));val expected=pixels(f,size.first,size.second)
                ui{f.root.removeView(oldView)}
                val currentDone=CountDownLatch(1)
                ui{gpuScene=SonarGpuSceneView(f.window.context,c);gpu=gpuScene!!.gpu;gpuScene!!.onPresented={currentDone.countDown()}
                    f.root.addView(gpuScene,FrameLayout.LayoutParams(size.first,size.second))
                    gpuScene!!.update(gpuFrame(c,scale=size.first/2000f).copy(context=c.gpuSourceContext(),fullStage=true,displayRevision=c.displayRevision))}
                assertTrue(currentDone.await(5,TimeUnit.SECONDS));assertNull(gpu!!.lastError)
                val actual=pixels(f,size.first,size.second);assertTrue("scene alpha remains bit exact",actual.indices.all{(actual[it] ushr 24)==(expected[it] ushr 24)});var unequal=0;var maximum=0;var outside=0
                val ratio=size.first/2000f
                for(at in actual.indices)if(actual[at]!=expected[at]){
                    unequal++;for(shift in listOf(0,8,16))maximum=maxOf(maximum,kotlin.math.abs((actual[at]ushr shift and 255)-(expected[at]ushr shift and 255)))
                    val x=at%size.first;val y=at/size.first
                    if(x<SonarLayout.PLOT_LEFT*ratio||x>SonarLayout.plotRight(true)*ratio||y<SonarLayout.TOP*ratio||y>SonarLayout.BOTTOM*ratio)outside++
                }
                val name="full-scene-${size.first}x${size.second}";rows.put(JSONObject().put("name",name).put("width",size.first).put("height",size.second).put("unequal",unequal).put("maximum_channel_error",maximum).put("outside_plot_unequal",outside))
                if(maximum>channelGate||outside!=0)failures++
                if(unequal!=0){for((label,data)in listOf("old" to expected,"gles" to actual)){
                    val image=Bitmap.createBitmap(data,size.first,size.second,Bitmap.Config.ARGB_8888);try{File(context.filesDir,"broader-$name-$label.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
                }}
                ui{gpuScene!!.close();gpuScene=null;gpu=null;f.root.removeAllViews()};await("scene GPU resources released"){SonarGpuNative.resources().all{it==0L}}
            }
            result.put("failed_cases",failures);assertEquals("actual native scene parity",0,failures);result.put("passed",true)
        }finally{ui{gpuScene?.close();gpu?.close();fixture?.close()};referenceRenderer.dispose();overlayRenderer.dispose();c.dispose();result.put("cases",rows);save("full-scene-parity1",result)}
    }

    @Test fun fullRingRasterVisibilityCullingKeepsActualPixelsBitExactly() {
        require(context.packageName=="com.no5.helm.sonarfixture")
        val report=JSONObject().put("qualifying_seconds",0).put("required_RGBA_error",0).put("scope","Same full original shared ring/geometry, actual hardware Window pixels, culling OFF versus ON; no RAW reduction")
        val rows=JSONArray();var now=1_000_000L;var fixture:Fixture?=null;var scene:SonarGpuSceneView?=null
        val c=SonarNativeController(DeterministicMockSonarSource(now={now})){now}
        try {
            ui{fixture=Fixture(context,1664,1024)};val f=fixture!!
            val specifications=listOf(Triple(240,1600 to 960,false),Triple(600,1600 to 960,true),Triple(240,192 to 108,false),Triple(600,192 to 108,true),Triple(240,1501 to 901,false))
            for((seconds,size,adaptive)in specifications){
                c.model.resetAcquisition();c.model.connect("PRIVATE PIXEL VISIBILITY");c.model.acquisition(true);c.model.updateContext(SonarEpoch("visibility-$seconds-$size-$adaptive",1,1),SonarPingSource.OWN);c.model.setDisplay(SonarDisplay(historySeconds=seconds.toDouble()))
                repeat(seconds*20){id->now+=50
                    c.model.setAnalysis(SonarGeometry(if(adaptive&&id%2==0)2.046 else 3.069,SonarTxReference.HOST_VERIFIED),SonarBottom(300.0+(id%200)*.5,1.0,1.0,SonarBottomSource.M1_RAW),SonarPresentation(id.toLong(),SonarAnalysisQuality.LOCKED,null,"off","off",false,false,null,false),true,amplitude=SonarAmplitude(4095,12,true))
                    c.model.frame(SonarFrame(ShortArray(1024){((it*31+id*13)and 4095).toShort()},id.toLong(),now),pingSource=SonarPingSource.OWN)
                };c.refresh();c.interpolation(true)
                val full=size.first>192;var gpu:SonarGpuView?=null;var done=CountDownLatch(1)
                ui{if(full){scene=SonarGpuSceneView(f.window.context,c);gpu=scene!!.gpu;scene!!.onPresented={done.countDown()};f.root.addView(scene,FrameLayout.LayoutParams(size.first,size.second));scene!!.update(gpuFrame(c).copy(context=c.gpuSourceContext(),fullStage=true,displayRevision=c.displayRevision))}
                    else{gpu=SonarGpuView(f.window.context);gpu!!.onTexturePresented={c.gpuPresented(it);done.countDown()};f.root.addView(gpu,FrameLayout.LayoutParams(size.first,size.second));gpu!!.submit(gpuFrame(c).copy(context=c.gpuSourceContext()))}}
                assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(gpu!!.lastError)
                fun redraw(enabled:Boolean):Pair<IntArray,LongArray>{
                    done=CountDownLatch(1);val applied=CountDownLatch(1)
                    ui{gpu!!.fixtureOwnHandle{handle->SonarGpuNativeFaultProof.rasterCull(handle,enabled);applied.countDown();gpu!!.fixtureRenderAgain()}}
                    assertTrue(applied.await(2,TimeUnit.SECONDS));assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(gpu!!.lastError)
                    val counts=AtomicReference<LongArray>();val observed=CountDownLatch(1)
                    ui{gpu!!.fixtureOwnHandle{handle->counts.set(SonarGpuNativeFaultProof.rasterPrimitiveCounts(handle));observed.countDown()}}
                    assertTrue(observed.await(2,TimeUnit.SECONDS));return pixels(f,size.first,size.second) to counts.get()
                }
                val before=redraw(false);val after=redraw(true);val unequal=before.first.indices.count{before.first[it]!=after.first[it]}
                val name="visibility-$seconds-${size.first}-$adaptive"
                rows.put(JSONObject().put("name",name).put("capacity",seconds*20).put("adaptive",adaptive).put("width",size.first).put("height",size.second).put("unequal",unequal).put("unculled_primitives",JSONArray(before.second)).put("culled_primitives",JSONArray(after.second)))
                if(unequal!=0){for((label,data)in listOf("old" to before.first,"gles" to after.first)){
                    val bitmap=Bitmap.createBitmap(data,size.first,size.second,Bitmap.Config.ARGB_8888);try{File(context.filesDir,"broader-$name-$label.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
                }}
                assertEquals("Visibility changes no hardware RGBA pixel",0,unequal)
                assertEquals(seconds*20,c.model.viewRing.count);assertTrue("actual single sample optimisation executed",after.second[3]==1L);assertTrue("at least one invisible primitive eliminated",after.second.take(3).sum()<before.second.take(3).sum())
                ui{scene?.close();scene=null;gpu!!.close();f.root.removeAllViews()};await("release private visibility renderer"){SonarGpuNative.resources().all{it==0L}}
            }
            report.put("passed",true)
        }finally{ui{scene?.close();fixture?.close()};c.dispose();report.put("cases",rows).put("native_resources_after",JSONArray(SonarGpuNative.resources()));save("full-ring-visibility1",report)}
    }

    @Test fun incrementalColorRowsMatchFullRepaintAcrossPageAndRingWrap() {
        require(context.packageName=="com.no5.helm.sonarfixture")
        val result=JSONObject().put("qualifying_seconds",0).put("required_RGBA_error",0).put("scope","actual new original ping per step; incremental color FBO versus complete repaint, same hardware pixels")
        val rows=JSONArray();var fixture:Fixture?=null;var scene:SonarGpuSceneView?=null;var gpu:SonarGpuView?=null;var now=1_000_000L;var next=0
        val c=SonarNativeController(DeterministicMockSonarSource(now={now})){now}
        fun feed(){now+=50;val id=next++
            c.model.setAnalysis(SonarGeometry(if(id%17==0)2.046 else 3.069,SonarTxReference.HOST_VERIFIED),SonarBottom(300.0+(id%200)*.5,1.0,1.0,SonarBottomSource.M1_RAW),SonarPresentation(id.toLong(),SonarAnalysisQuality.LOCKED,null,"off","off",false,false,null,false),true,amplitude=SonarAmplitude(4095,12,true))
            c.model.frame(SonarFrame(ShortArray(1024){((it*31+id*13)and 4095).toShort()},id.toLong(),now),pingSource=SonarPingSource.OWN);c.refresh()
        }
        try {
            ui{fixture=Fixture(context,832,512)};val f=fixture!!
            for((seconds,head,size)in listOf(Triple(240,4792,799 to 481),Triple(600,2040,320 to 180),Triple(600,11992,320 to 180))){
                c.model.resetAcquisition();c.model.connect("PRIVATE DIRTY COLOR");c.model.acquisition(true);c.model.updateContext(SonarEpoch("dirty-$seconds-$head",1,1),SonarPingSource.OWN);c.model.setDisplay(SonarDisplay(historySeconds=seconds.toDouble()));next=0
                repeat(seconds*20+head){feed()};c.interpolation(true)
                var done=CountDownLatch(1);val full=size.first>320
                ui{if(full){scene=SonarGpuSceneView(f.window.context,c);gpu=scene!!.gpu;scene!!.onPresented={done.countDown()};f.root.addView(scene,FrameLayout.LayoutParams(size.first,size.second));scene!!.update(gpuFrame(c).copy(context=c.gpuSourceContext(),fullStage=true,displayRevision=c.displayRevision))}
                    else{gpu=SonarGpuView(f.window.context);gpu!!.onTexturePresented={c.gpuPresented(it);done.countDown()};f.root.addView(gpu,FrameLayout.LayoutParams(size.first,size.second));gpu!!.submit(gpuFrame(c).copy(context=c.gpuSourceContext()))}}
                assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(gpu!!.lastError)
                fun fullRepaint(value:Boolean){val done=CountDownLatch(1);ui{gpu!!.fixtureOwnHandle{h->SonarGpuNativeFaultProof.fullColorRepaint(h,value);done.countDown()}};assertTrue(done.await(2,TimeUnit.SECONDS))}
                fun columns():Long {val answer=java.util.concurrent.atomic.AtomicLong();val done=CountDownLatch(1);ui{gpu!!.fixtureOwnHandle{h->answer.set(SonarGpuNativeFaultProof.rasterPrimitiveCounts(h)[4]);done.countDown()}};assertTrue(done.await(2,TimeUnit.SECONDS));return answer.get()}
                for(step in 0 until 16){
                    fullRepaint(false);feed();done=CountDownLatch(1)
                    ui{if(full)scene!!.update(gpuFrame(c).copy(context=c.gpuSourceContext(),fullStage=true,displayRevision=c.displayRevision))else gpu!!.submit(gpuFrame(c).copy(context=c.gpuSourceContext()))}
                    assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(gpu!!.lastError);val partialColumns=columns();val actual=pixels(f,size.first,size.second)
                    fullRepaint(true);done=CountDownLatch(1);ui{gpu!!.fixtureRenderAgain()};assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(gpu!!.lastError)
                    val fullColumns=columns();val reference=pixels(f,size.first,size.second);val unequal=actual.indices.count{actual[it]!=reference[it]}
                    val name="dirty-$seconds-$head-$step";rows.put(JSONObject().put("name",name).put("step",step).put("capacity",seconds*20).put("width",size.first).put("height",size.second).put("unequal",unequal).put("partial_color_columns",partialColumns).put("full_color_columns",fullColumns))
                    if(unequal!=0)for((label,data)in listOf("old" to reference,"gles" to actual)){val bitmap=Bitmap.createBitmap(data,size.first,size.second,Bitmap.Config.ARGB_8888);try{File(context.filesDir,"broader-$name-$label.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}}
                    assertEquals("new ping color rows versus full repaint",0,unequal);assertTrue("actual bounded dirty rectangle",partialColumns in 1..64);assertEquals(((seconds*20+2047)/2048)*2048L,fullColumns)
                }
                ui{scene?.close();scene=null;gpu!!.close();gpu=null;f.root.removeAllViews()};await("release dirty color proof resources"){SonarGpuNative.resources().all{it==0L}}
            };result.put("passed",true)
        }finally{ui{scene?.close();gpu?.close();fixture?.close()};c.dispose();result.put("cases",rows).put("native_resources_after",JSONArray(SonarGpuNative.resources()));save("incremental-color-rows1",result)}
    }

    @Test fun modelGpuRawOwnershipFreezeEvictionAndMemoryPlateau() {
        require(context.packageName=="com.no5.helm.sonarfixture")
        val result=JSONObject().put("qualification_seconds",0).put("explicit_GC_test_only",true)
            .put("plateau_growth_limit_bytes",32L*1024*1024).put("registered_pair_cache_exercised",true);val rows=JSONArray()
        var now=1_000_000L;val c=SonarNativeController(DeterministicMockSonarSource(now={now})){now}
        var fixture:Fixture?=null;var gpu:SonarGpuView?=null
        try{
            ui{fixture=Fixture(context,384,256);gpu=SonarGpuView(fixture!!.window.context);fixture!!.root.addView(gpu,FrameLayout.LayoutParams(320,180))}
            for(seconds in listOf(240.0,600.0)){
                val cap=sonarVisiblePingBudget(seconds);val queue=ReferenceQueue<SonarFrame>();val owners=ArrayList<WeakReference<SonarFrame>>()
                c.model.resetAcquisition();c.model.connect("PRIVATE MODEL GPU MEMORY");c.model.acquisition(true)
                c.model.updateContext(SonarEpoch("memory-$seconds",1,1),SonarPingSource.OWN)
                c.model.setDisplay(SonarDisplay(historySeconds=seconds));c.model.setAnalysis(SonarGeometry(3.069,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true))
                c.interpolation(true)
                fun add(id:Int,watch:Boolean=false){
                    now+=50
                    val bottom=SonarBottom(300.0+(id%200)*.5,1.0,1.0,SonarBottomSource.M1_RAW)
                    val detail=SonarPresentation(id.toLong(),SonarAnalysisQuality.LOCKED,null,"off","off",false,false,null,false)
                    c.model.setAnalysis(SonarGeometry(3.069,SonarTxReference.HOST_VERIFIED),bottom,detail,true,amplitude=SonarAmplitude(4095,12,true))
                    val frame=SonarFrame(ShortArray(1024){((it+id)and 4095).toShort()},id.toLong(),now);if(watch)owners+=WeakReference(frame,queue);c.model.frame(frame,pingSource=SonarPingSource.OWN)
                }
                for(id in 0 until cap)add(id,id<32)
                c.refresh();present(gpu!!,c);assertEquals(9L,SonarGpuNative.resources()[3]);assertEquals(cap,c.model.viewRing.count);assertEquals(cap*2048L,c.model.viewRing.allocatedSampleBytes)
                for(id in cap until cap+64)add(id)
                c.refresh();present(gpu!!,c);val collected=gcOwners(queue,32)
                val shared=c.model.viewRing;val frozenLast=shared.pingIdAt(0);c.model.setFreeze(true)
                val freezeQueue=ReferenceQueue<SonarFrame>();val freezeRefs=ArrayList<WeakReference<SonarFrame>>()
                for(id in cap+64 until cap+96){now+=50;c.model.setAnalysis(SonarGeometry(3.069,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true));val frame=SonarFrame(ShortArray(1024),id.toLong(),now);freezeRefs+=WeakReference(frame,freezeQueue);c.model.frame(frame,pingSource=SonarPingSource.OWN)}
                assertSame(shared,c.model.viewRing);assertEquals(frozenLast,shared.pingIdAt(0));assertEquals(cap,shared.count)
                // All incoming frames except the separately bounded current live frame must release.
                val freezeCollected=gcOwners(freezeQueue,31)
                c.model.setFreeze(false);now+=(seconds*1000).toLong()+51
                c.model.setAnalysis(SonarGeometry(3.069,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true))
                c.model.frame(SonarFrame(ShortArray(1024), (cap+96).toLong(),now),pingSource=SonarPingSource.OWN)
                assertEquals("time horizon eviction on resume",1,c.model.viewRing.count)
                c.refresh();present(gpu!!,c)
                // Measure a constant FULL raw ring, not a refilling horizon.
                var nextId=cap+97;repeat(cap){add(nextId++)}
                assertEquals(cap,c.model.viewRing.count)
                val samples=JSONArray();var firstPss=0L;var peakPss=0L
                for(round in 0 until 6){
                    repeat(cap){add(nextId++)}
                    assertEquals(cap,c.model.viewRing.count)
                    assertEquals(cap*2048L,c.model.viewRing.allocatedSampleBytes)
                    c.refresh();present(gpu!!,c);System.gc();Thread.sleep(40)
                    val info=Debug.MemoryInfo();Debug.getMemoryInfo(info);val pss=info.totalPss*1024L
                    if(round==1)firstPss=pss;if(round>=1)peakPss=maxOf(peakPss,pss)
                    samples.put(JSONObject().put("round",round).put("full_ring_overwrite_pings",cap).put("retained_raw_pings",c.model.viewRing.count).put("pss_bytes",pss).put("java_live_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()).put("native_heap_bytes",Debug.getNativeHeapAllocatedSize()))
                }
                assertTrue("stable-sized model/GPU PSS plateau",peakPss-firstPss<=32L*1024*1024)
                c.model.setDisplay(c.model.display.copy(historySeconds=10.0));assertEquals(200,c.model.viewRing.capacity);assertTrue(c.model.viewRing.count<=200)
                c.refresh();present(gpu!!,c)
                rows.put(JSONObject().put("seconds",seconds).put("capacity",cap).put("overwritten_collected",collected).put("freeze_incoming_collected",freezeCollected)
                    .put("same_ring_during_freeze",true).put("post_resume_horizon_count",1).put("shrunk_capacity",200).put("plateau_growth_bytes",peakPss-firstPss).put("measurements",samples))
            }
            ui{gpu!!.close();gpu=null;fixture!!.root.removeAllViews()};await("zero owned GPU resources"){SonarGpuNative.resources().all{it==0L}}
            c.model.resetAcquisition();assertEquals(0L,c.model.viewRing.allocatedSampleBytes);result.put("passed",true)
        }finally{ui{gpu?.close();fixture?.close()};c.dispose();result.put("cases",rows).put("final_gpu_resources",JSONArray(SonarGpuNative.resources()));save("model-gpu-memory1",result)}
    }

    @Test fun controlledCanvasGlesCpuAndPresentationLatency() {
        require(context.packageName=="com.no5.helm.sonarfixture")
        val report=JSONObject().put("qualifying_seconds",0).put("common_history_seconds",60).put("common_raw_pings",1200).put("rate_hz",20).put("warmup_frames",40).put("measured_frames_per_case",160)
            .put("repetitions",3).put("CPU_gate","GLES median <= same-target Canvas median")
            .put("presentation_p95_limit_ms",50).put("p95_baseline_ratio_limit",1.05)
        val rows=JSONArray();var fixture:Fixture?=null;var now=1_000_000L
        val c=SonarNativeController(DeterministicMockSonarSource(now={now})){now};val renderer=SonarNativeRenderer(context)
        fun percentile(xs:List<Long>,p:Double):Double {val sorted=xs.sorted();return sorted[kotlin.math.ceil(p*sorted.size).toInt().coerceIn(1,sorted.size)-1]/1e6}
        try{
            ui{fixture=Fixture(context,1664,900)};val f=fixture!!
            for(size in listOf(1600 to 780,192 to 108))for(rep in 0 until 3){
                val order=if(rep%2==0)listOf("canvas","gles")else listOf("gles","canvas")
                for(engine in order){
                    c.model.resetAcquisition();c.model.connect("PRIVATE CONTROLLED CPU FRAME TEST");c.model.acquisition(true)
                    c.model.updateContext(SonarEpoch("performance",1,1),SonarPingSource.OWN)
                    c.model.setDisplay(SonarDisplay(historySeconds=60.0))
                    c.model.setAnalysis(SonarGeometry(3.069,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true))
                    fun feed(id:Int){now+=50;c.model.frame(SonarFrame(ShortArray(1024){((it*31+id*13)and 4095).toShort()},id.toLong(),now),pingSource=SonarPingSource.OWN);c.refresh()}
                    for(id in 0 until 1200)feed(id);c.interpolation(true)
                    var canvas:CanvasDestination?=null;var gpu:SonarGpuView?=null
                    try{
                        if(engine=="canvas")canvas=CanvasDestination(f,size.first,size.second)
                        else ui{gpu=SonarGpuView(f.window.context);f.root.addView(gpu,FrameLayout.LayoutParams(size.first,size.second))}
                        fun draw():Long=if(canvas!=null)canvas!!.paint(c,renderer,false)else present(gpu!!,c)
                        draw()
                        for(id in 1200 until 1240){val start=SystemClock.elapsedRealtimeNanos();feed(id);draw();val pause=50_000_000L-(SystemClock.elapsedRealtimeNanos()-start);if(pause>0)TimeUnit.NANOSECONDS.sleep(pause)}
                        val latencies=ArrayList<Long>();val cpuBefore=android.os.Process.getElapsedCpuTime();val began=SystemClock.elapsedRealtimeNanos();var missed=0
                        for(id in 1240 until 1400){
                            val enqueue=SystemClock.elapsedRealtimeNanos();feed(id);draw();val observed=SystemClock.elapsedRealtimeNanos()-enqueue;latencies+=observed
                            val pause=50_000_000L-observed;if(pause>0)TimeUnit.NANOSECONDS.sleep(pause)else missed++
                        }
                        val elapsed=SystemClock.elapsedRealtimeNanos()-began;val cpuMs=android.os.Process.getElapsedCpuTime()-cpuBefore
                        rows.put(JSONObject().put("engine",engine).put("width",size.first).put("height",size.second).put("repeat",rep)
                            .put("frames",latencies.size).put("CPU_one_core_percent",cpuMs*100_000_000.0/elapsed).put("elapsed_ns",elapsed).put("process_CPU_ms",cpuMs)
                            .put("presentation_p95_ms",percentile(latencies,.95)).put("missed_50ms_periods",missed).put("latencies_ns",JSONArray(latencies)))
                    }finally{canvas?.close();ui{gpu?.close();f.root.removeAllViews()};await("native resources after performance case"){SonarGpuNative.resources().all{it==0L}}}
                }
            }
            val gates=JSONArray();var failures=0
            for(width in listOf(1600,192)){
                fun median(engine:String,key:String):Double {val values=(0 until rows.length()).map{rows.getJSONObject(it)}.filter{it.getInt("width")==width&&it.getString("engine")==engine}.map{it.getDouble(key)}.sorted();return values[1]}
                val cpu=median("gles","CPU_one_core_percent");val baselineCpu=median("canvas","CPU_one_core_percent")
                val p95=median("gles","presentation_p95_ms");val baselineP95=median("canvas","presentation_p95_ms")
                val pass=cpu<=baselineCpu&&p95<=50.0&&p95<=1.05*baselineP95
                if(!pass)failures++
                gates.put(JSONObject().put("width",width).put("CPU_GLES",cpu).put("CPU_Canvas",baselineCpu).put("p95_GLES_ms",p95).put("p95_Canvas_ms",baselineP95).put("passed",pass))
            }
            report.put("gates",gates);assertEquals("predeclared CPU/presentation gates",0,failures);report.put("passed",true)
        }finally{ui{fixture?.close()};renderer.dispose();c.dispose();report.put("cases",rows);save("controlled-gpu-performance1",report)}
    }

    @Test fun maximumRingSteadyAndTransferPresentationBudget() {
        fun percentile(xs:List<Long>,p:Double):Double {val sorted=xs.sorted();return sorted[kotlin.math.ceil(p*sorted.size).toInt().coerceIn(1,sorted.size)-1]/1e6}
        require(context.packageName=="com.no5.helm.sonarfixture")
        val nativePixels=InstrumentationRegistry.getArguments().getString("sonar_native_pixel_viewport")=="true"
        val warmup=InstrumentationRegistry.getArguments().getString("sonar_scene_warmup_frames")?.toInt()?:0
        require(warmup==0||warmup==40)
        val diagnosticsArgument=InstrumentationRegistry.getArguments().getString("sonar_stage_diagnostics")
        val stageDiagnostics=diagnosticsArgument=="true"||diagnosticsArgument=="all"
        val singleDiagnostic=diagnosticsArgument=="true"
        val outputName=if(nativePixels)"maximum-ring-native-pixels1"else if(warmup==0)"maximum-ring-performance1"else"maximum-ring-scene-warmed2"
        val report=JSONObject().put("qualifying_seconds",0).put("frames_per_case",30).put("rate_hz",20)
            .put("diagnostic_only",singleDiagnostic).put("warmup_frames",warmup).put("all_cold_and_warmup_frames_2s_gate_ms",2000).put("steady_p95_gate_ms",50).put("each_observed_frame_gate_ms",2000).put("transfer_gate_ms",2000)
            .put("scope","Current actual private full Sonar underlay/GLES/overlay and dashboard, 4800/12000 shared rings; nominal20Hz, no physical source capacity claim")
        val rows=JSONArray();var failures=0;var fixture:Fixture?=null;var now=1_000_000L
        val c=SonarNativeController(DeterministicMockSonarSource(now={now})){now}
        try {
            ui{fixture=if(nativePixels)Fixture(context,2064,1264,320)else Fixture(context,1664,1024)};val f=fixture!!
            for(seconds in if(singleDiagnostic)listOf(600)else listOf(240,600))for(size in if(nativePixels)listOf(2000 to 1200)else if(singleDiagnostic)listOf(1600 to 960)else listOf(1600 to 960,192 to 108))for(anchored in if(singleDiagnostic)listOf(true)else listOf(false,true)){
                val row=JSONObject().put("seconds",seconds).put("capacity",seconds*20).put("width",size.first).put("height",size.second).put("registered_bottom",anchored).put("actual_density_dpi",f.window.context.resources.displayMetrics.densityDpi)
                rows.put(row);var gpu:SonarGpuView?=null;var gpuScene:SonarGpuSceneView?=null;val sceneRenderer=SonarNativeRenderer(context,externalPlot=true)
                try {
                    c.model.resetAcquisition();c.model.connect("PRIVATE FULL RING BUDGET");c.model.acquisition(true)
                    c.model.updateContext(SonarEpoch("maximum-$seconds-${size.first}-$anchored",1,1),SonarPingSource.OWN)
                    c.model.setDisplay(SonarDisplay(historySeconds=seconds.toDouble()))
                    fun feed(id:Int){
                        now+=50;val bottom=if(anchored)SonarBottom(300.0+(id%200)*.5,1.0,1.0,SonarBottomSource.M1_RAW)else SonarBottom()
                        val presentation=if(anchored)SonarPresentation(id.toLong(),SonarAnalysisQuality.LOCKED,null,"off","off",false,false,null,false)else null
                        c.model.setAnalysis(SonarGeometry(3.069,SonarTxReference.HOST_VERIFIED),bottom,presentation,anchored,amplitude=SonarAmplitude(4095,12,true))
                        c.model.frame(SonarFrame(ShortArray(1024){((it*31+id*13)and 4095).toShort()},id.toLong(),now),pingSource=SonarPingSource.OWN);c.refresh()
                    }
                    val cap=seconds*20;for(id in 0 until cap)feed(id);c.interpolation(true)
                    val full=size.first>=1600
                    ui{if(full){gpuScene=SonarGpuSceneView(f.window.context,c);gpu=gpuScene!!.gpu;f.root.addView(gpuScene,FrameLayout.LayoutParams(size.first,size.second))}
                        else{gpu=SonarGpuView(f.window.context);f.root.addView(gpu,FrameLayout.LayoutParams(size.first,size.second))}}
                    val stages=JSONArray()
                    fun draw():Long {
                        var gpuAt=0L;var overlayNs=0L
                        val began=SystemClock.elapsedRealtimeNanos();val done=CountDownLatch(1)
                        ui{if(full){gpuScene!!.onPresented={gpuAt=SystemClock.elapsedRealtimeNanos()-began;done.countDown()}
                                gpuScene!!.update(gpuFrame(c).copy(context=c.gpuSourceContext(),fullStage=true,displayRevision=c.displayRevision))}
                            else{gpu!!.onTexturePresented={stamp->gpuAt=SystemClock.elapsedRealtimeNanos()-began;c.gpuPresented(stamp);done.countDown()}
                                gpu!!.submit(gpuFrame(c).copy(context=c.gpuSourceContext(),displayRevision=c.displayRevision))}}
                        assertTrue("complete current scene commit ${gpu!!.lastError}",done.await(5,TimeUnit.SECONDS));assertNull(gpu!!.lastError)
                        if(stageDiagnostics){stages.put(JSONObject().put("GPU_commit_ns",gpuAt).put("subsequent_overlay_commit_ns",overlayNs).put("worker",JSONArray(gpu!!.fixtureProgress())));row.put("stage_diagnostics",stages)}
                        return SystemClock.elapsedRealtimeNanos()-began
                    }
                    val cold=draw();row.put("cold_full_ring_ms",cold/1e6).put("complete_scene",full).put("cold_prepared_mean_columns",gpu!!.smoothingColumnsPrepared)
                    val warm=ArrayList<Long>();val gcBefore=android.os.Debug.getRuntimeStats()
                    for(id in cap until cap+warmup){val queued=SystemClock.elapsedRealtimeNanos();feed(id);draw();val observed=SystemClock.elapsedRealtimeNanos()-queued;warm+=observed
                        val pause=50_000_000L-observed;if(pause>0)TimeUnit.NANOSECONDS.sleep(pause)}
                    row.put("all_warmup_latencies_ns",JSONArray(warm)).put("warmup_gc_count_before",gcBefore["art.gc.gc-count"]).put("warmup_gc_count_after",android.os.Debug.getRuntimeStat("art.gc.gc-count"))
                    val times=ArrayList<Long>();val cpuBefore=android.os.Process.getElapsedCpuTime();val began=SystemClock.elapsedRealtimeNanos()
                    for(id in cap+warmup until cap+warmup+30){val queued=SystemClock.elapsedRealtimeNanos();feed(id);draw();val observed=SystemClock.elapsedRealtimeNanos()-queued;times+=observed
                        val pause=50_000_000L-observed;if(pause>0)TimeUnit.NANOSECONDS.sleep(pause)}
                    val elapsed=SystemClock.elapsedRealtimeNanos()-began
                    row.put("CPU_one_core_percent",(android.os.Process.getElapsedCpuTime()-cpuBefore)*100_000_000.0/elapsed)
                        .put("steady_p95_ms",percentile(times,.95)).put("steady_max_ms",times.max()/1e6).put("latencies_ns",JSONArray(times))
                    c.model.setDisplay(c.model.display.copy(gainDb=3.0));c.refresh();val transfer=draw()
                    row.put("transfer_change_ms",transfer/1e6).put("ring_count",c.model.viewRing.count).put("raw_sample_bytes",c.model.viewRing.allocatedSampleBytes)
                        .put("native_resources",JSONArray(SonarGpuNative.resources())).put("process_PSS_bytes",android.os.Debug.MemoryInfo().also{android.os.Debug.getMemoryInfo(it)}.totalPss*1024L)
                    val pass=cold<=2_000_000_000L&&warm.all{it<=2_000_000_000L}&&times.max()<=2_000_000_000L&&percentile(times,.95)<=50.0&&transfer<=2_000_000_000L&&c.model.viewRing.count==cap&&c.model.viewRing.allocatedSampleBytes==cap*2048L
                    row.put("passed",pass);if(!pass)failures++
                }catch(error:Throwable){row.put("passed",false).put("error",(error.javaClass.simpleName+": "+error.message).take(500));failures++}
                finally{ui{gpuScene?.close();gpu?.close();f.root.removeAllViews()};sceneRenderer.dispose();await("release maximum native owner"){SonarGpuNative.resources().all{it==0L}};save(outputName,report.put("cases",rows).put("finished",false))}
            }
            report.put("failed_cases",failures).put("finished",true).put("passed",failures==0);assertEquals("maximum ring presentation gates",0,failures)
        }finally{ui{fixture?.close()};c.dispose();report.put("cases",rows).put("native_resources_after",JSONArray(SonarGpuNative.resources()));save(outputName,report)}
    }

    @Test fun nativeSevenPingKernelMatchesUnchangedJavaCacheBitExactly() {
        require(context.packageName=="com.no5.helm.sonarfixture")
        val result=JSONObject().put("qualifying_seconds",0).put("reference","unchanged Java cache Double sum/order/rounding; native only optional identical-geometry branch")
        val rows=JSONArray();var now=1_000_000L;val model=SonarModel{now};var profiles=0
        try{
            for(fullScale in listOf(4095,65535))for(adaptive in listOf(false,true)){
                model.resetAcquisition();model.connect("PRIVATE JNI MEAN");model.acquisition(true);model.updateContext(SonarEpoch("kernel-$fullScale-$adaptive",1,1),SonarPingSource.OWN)
                for(id in 0 until 160){now+=if(id==80)600 else 50
                    model.setAnalysis(SonarGeometry(if(adaptive&&id<64)2.046 else 3.069,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(fullScale,if(fullScale==4095)12 else 16,true))
                    model.frame(SonarFrame(ShortArray(1024){bin->when{bin%31==0->0;bin%31==1->fullScale.toShort();else->((bin*541+id*937)and fullScale).toShort()}},id.toLong(),now),pingSource=SonarPingSource.OWN)
                }
                for(display in listOf(SonarDisplay(),SonarDisplay(gainDb=3.0,gamma=.7,threshold=.1),SonarDisplay(gainDb=-20.0,gamma=2.0,threshold=.8))){
                    val java=SonarDisplaySmoothingCache();val native=SonarDisplaySmoothingCache(sameGeometryKernel=SonarGpuNative::mean7Exact)
                    try{java.begin(model.viewRing,model.epoch,display);native.begin(model.viewRing,model.epoch,display)
                        val expected=ShortArray(1024);val actual=ShortArray(1024)
                        for(age in 0 until model.viewRing.count){java.copyRawCodes(age,expected,0);native.copyRawCodes(age,actual,0);assertArrayEquals("native mean profile $fullScale/$adaptive/$age",expected,actual);assertEquals(java.qualifiedAt(age),native.qualifiedAt(age));profiles++}
                        rows.put(JSONObject().put("full_scale",fullScale).put("adaptive",adaptive).put("gain_db",display.gainDb).put("gamma",display.gamma).put("threshold",display.threshold).put("exact_profiles",model.viewRing.count))
                    }finally{java.release();native.release()}
                }
            }
            try{SonarGpuNative.mean7Exact(ShortArray(1),FloatArray(65536),ShortArray(1024),0);fail("bad native bounds accepted")}catch(expected:IllegalStateException){}
            val invalid=FloatArray(65536){Float.NaN}
            try{SonarGpuNative.mean7Exact(ShortArray(7*1024),invalid,ShortArray(1024),0);fail("invalid native intensity accepted")}catch(expected:IllegalStateException){}
            result.put("passed",true).put("profiles",profiles).put("exact_codes",profiles*1024).put("critical_array_copy_path","would fail closed; no source/GC/platform settings modified")
        }finally{model.resetAcquisition();result.put("cases",rows).put("GPU_resources",JSONArray(SonarGpuNative.resources()));save("native-seven-mean1",result)}
    }

    @Test fun nativeRegisteredPairKernelMatchesOriginalColorsBitExactly() {
        require(context.packageName=="com.no5.helm.sonarfixture")
        val result=JSONObject().put("qualifying_seconds",0);var now=1_000_000L;var pairs=0
        val model=SonarModel{now};val java=SonarDisplaySmoothingCache();val transfer=SonarTransferLut();val palette=IntArray(65536)
        val expected=IntArray(2048);val actual=IntArray(2048);val codes=ShortArray(2048)
        try {
            for(scale in listOf(4095,65535))for(adaptive in listOf(false,true))for(anchor in listOf(false,true)) {
                model.resetAcquisition();model.connect("PRIVATE PAIR");model.acquisition(true);model.updateContext(SonarEpoch("pair-$scale-$adaptive-$anchor",1,1),SonarPingSource.OWN)
                for(id in 0 until 40){now+=50
                    model.setAnalysis(SonarGeometry(if(adaptive&&id%2==0)2.046 else 3.069,SonarTxReference.HOST_VERIFIED),
                        if(anchor)SonarBottom(300.0+(id%7)*.5,1.0,1.0,SonarBottomSource.M1_RAW)else SonarBottom(),
                        if(anchor)SonarPresentation(id.toLong(),SonarAnalysisQuality.LOCKED,null,"off","off",false,false,null,false)else null,true,amplitude=SonarAmplitude(scale,if(scale==4095)12 else 16,true))
                    model.frame(SonarFrame(ShortArray(1024){bin->((bin*541+id*937)and scale).toShort()},id.toLong(),now),pingSource=SonarPingSource.OWN)
                }
                for(display in listOf(SonarDisplay(),SonarDisplay(gainDb=3.0,gamma=.7,threshold=.1),SonarDisplay(gainDb=-20.0,gamma=2.0,threshold=.8))){
                    transfer.fillIntensityColors(palette,display.palette,false);java.begin(model.viewRing,null,display)
                    for(age in 0 until model.viewRing.count-1){
                        val a=com.no5.helm.gpu.SonarGpuTemporalPairs.prepare(model.viewRing,age,palette,java,expected,codes)
                        val b=com.no5.helm.gpu.SonarGpuTemporalPairs.prepare(model.viewRing,age,palette,java,actual,codes,nativeExact=true)
                        assertArrayEquals("exact pair $scale/$adaptive/$anchor/$age",expected,actual);assertEquals(a,b);assertTrue(codes.all{it==0.toShort()});pairs++
                    }
                }
            }
            try{SonarGpuNative.pairColorsExact(ShortArray(1),palette,actual,1.0,1.0,1.0,Double.NaN,Double.NaN);fail("bad pair bounds accepted")}catch(expected:IllegalStateException){}
            try{SonarGpuNative.pairColorsExact(codes,palette,actual,0.0,1.0,1.0,Double.NaN,Double.NaN);fail("invalid pair geometry accepted")}catch(expected:IllegalStateException){}
            result.put("passed",true).put("pairs",pairs).put("exact_RGBA_values",pairs*2048).put("RAW_or_UTC_changes",false)
        }finally{java.release();model.resetAcquisition();result.put("GPU_resources",JSONArray(SonarGpuNative.resources()));save("native-pair-kernel1",result)}
    }

    @Test fun concurrentFullRingOwnContextLossAndBudgetedViewReplacement() {
        require(context.packageName=="com.no5.helm.sonarfixture")
        val report=JSONObject().put("qualifying_seconds",0).put("source","private synthetic20Hz, no hardware/source commands")
            .put("scope","Actual current GLES context death and new TextureView under concurrent receiver; durable policy permit, not full Activity admission or Activity relaunch")
        val policy=no5.sonar.windowrecovery.RecoveryBudgetPolicy(com.no5.helm.watchdog.RecoveryLedger(context,"private-view-native"))
        var now=1_000_000L;var next=0;val c=SonarNativeController(DeterministicMockSonarSource(now={now})){now}
        var fixture:Fixture?=null;var gpu:SonarGpuView?=null;var running=false
        val stamps=java.util.concurrent.CopyOnWriteArrayList<Long>();val shown=java.util.concurrent.atomic.AtomicLong(-1)
        val producerFailure=AtomicReference<Throwable?>();val received=java.util.concurrent.atomic.AtomicInteger()
        fun feed(){now+=50;val id=next++
            c.model.setAnalysis(SonarGeometry(3.069,SonarTxReference.HOST_VERIFIED),SonarBottom(300.0+(id%200)*.5,1.0,1.0,SonarBottomSource.M1_RAW),
                SonarPresentation(id.toLong(),SonarAnalysisQuality.LOCKED,null,"off","off",false,false,null,false),true,amplitude=SonarAmplitude(4095,12,true))
            c.model.frame(SonarFrame(ShortArray(1024){((it*31+id*13)and 4095).toShort()},id.toLong(),now),pingSource=SonarPingSource.OWN);c.refresh()
        }
        val producer=object:Runnable{override fun run(){if(!running)return;try{feed();received.incrementAndGet();gpu?.submit(gpuFrame(c))}catch(error:Throwable){producerFailure.set(error);running=false};if(running)main.postDelayed(this,50)}}
        fun create(){gpu=SonarGpuView(fixture!!.window.context).also{view->view.onTexturePresented={stamp->
            if(now-stamp.originalEpochMs !in 0..2000)producerFailure.compareAndSet(null,AssertionError("concurrently received newest age >2s or future"))
            stamps.add(SystemClock.elapsedRealtimeNanos());shown.set(stamp.pingId)
        };fixture!!.root.addView(view,FrameLayout.LayoutParams(320,180));view.submit(gpuFrame(c))}}
        try {
            c.model.connect("PRIVATE CONCURRENT RX");c.model.acquisition(true);c.model.updateContext(SonarEpoch("private-live-rebuild",1,1),SonarPingSource.OWN)
            c.model.setDisplay(SonarDisplay(historySeconds=600.0));repeat(12000){feed()};c.interpolation(true)
            val cold=SystemClock.elapsedRealtimeNanos();ui{fixture=Fixture(context,384,256);create();running=true;main.post(producer)}
            await("cold full ring presents while RX continues",2000){shown.get()>=11999};report.put("concurrent_cold_ms",(SystemClock.elapsedRealtimeNanos()-cold)/1e6)
            val firstCount=received.get();await("20 independently received frames during normal presentation",2000){received.get()>=firstCount+20}
            val lost=CountDownLatch(1);ui{gpu!!.fixtureOwnHandle{handle->com.no5.helm.gpu.SonarGpuNativeFaultProof.loseOwnContext(handle);lost.countDown()}}
            assertTrue(lost.await(2,TimeUnit.SECONDS));await("actual lost own EGL context is sticky",2000){gpu!!.hasFailure()};report.put("actual_context_error",gpu!!.lastError)
            val fault=no5.sonar.windowrecovery.RecoveryBudgetPolicy.Fault.OWN_GPU_FAILED
            val evidence=no5.sonar.windowrecovery.RecoveryBudgetPolicy.Evidence("private-window","private-live-rebuild",true,true,true,true,0,SystemClock.elapsedRealtimeNanos(),fault)
            assertTrue(policy.consume(no5.sonar.windowrecovery.RecoveryBudgetPolicy.Action.VIEW_REBUILD,evidence,"private-window","private-live-rebuild",SystemClock.elapsedRealtimeNanos()))
            val reloaded=no5.sonar.windowrecovery.RecoveryBudgetPolicy(com.no5.helm.watchdog.RecoveryLedger(context,"private-view-native"))
            assertEquals(2,reloaded.snapshot().windowMask)
            val before=shown.get();ui{val old=gpu!!;old.close();fixture!!.root.removeView(old);create()}
            await("new owned View hardware frame under continuing RX",2000){shown.get()>before};val end=received.get();await("receiver continues after own View replacement",2000){received.get()>=end+20}
            assertFalse(reloaded.consume(no5.sonar.windowrecovery.RecoveryBudgetPolicy.Action.VIEW_REBUILD,evidence,"private-window","private-live-rebuild",SystemClock.elapsedRealtimeNanos()))
            producerFailure.get()?.let{throw AssertionError(it)}
            val gaps=stamps.zipWithNext{a,b->b-a};assertTrue(gaps.isNotEmpty());assertTrue("no erased or >2s presentation gap across rebuild",gaps.max()<=2_000_000_000L)
            assertEquals(12000,c.model.viewRing.count);assertEquals(12000*2048L,c.model.viewRing.allocatedSampleBytes)
            report.put("receiver_pings",received.get()).put("presentations",stamps.size).put("maximum_presentation_gap_ns",gaps.max()).put("durable_permit_before_rebuild",true).put("duplicate_rebuild_denied",true).put("ring_count",c.model.viewRing.count).put("passed",true)
        }finally{ui{running=false;main.removeCallbacks(producer);gpu?.close();fixture?.close()};await("all own GLES resources released",3000){SonarGpuNative.resources().all{it==0L}};c.dispose();report.put("native_resources_after",JSONArray(SonarGpuNative.resources())).put("last_view_debug_progress",gpu?.fixtureProgress()?.let{JSONArray(it)}).put("last_view_error",gpu?.lastError);save("concurrent-view-recovery1",report)}
    }

    @Test fun missingFirstPresentationIsObservableWithoutTouchingAnyWindow() {
        require(context.packageName=="com.no5.helm.sonarfixture")
        var now=1_000_000L;val c=SonarNativeController(DeterministicMockSonarSource(now={now})){now}
        var gpu:SonarGpuView?=null
        try {
            c.model.connect("PRIVATE UNATTACHED FIRST FRAME OBSERVER");c.model.acquisition(true)
            c.model.frame(SonarFrame(ShortArray(1024),1L,now));c.refresh()
            ui{gpu=SonarGpuView(context);gpu!!.submit(gpuFrame(c))}
            assertEquals(0L,gpu!!.lastPresentedAtNanos)
            assertFalse(gpu!!.missingPresentation(System.nanoTime()))
            Thread.sleep(1050)
            assertTrue("first expected presentation can stall before any successful baseline",gpu!!.missingPresentation(System.nanoTime()))
        }finally{ui{gpu?.close()};c.dispose()}
    }

}
