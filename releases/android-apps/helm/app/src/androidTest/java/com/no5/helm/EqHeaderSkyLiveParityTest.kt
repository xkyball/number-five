package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Explicit physical-GPU experiment, frozen data, no audio commands or preferences.
 * Opaque header text/background and footer are cached; contours and EQ remain direct.
 */
@RunWith(AndroidJUnit4::class)
class EqHeaderSkyLiveParityTest {
    @Test fun opaqueSkyRetainsNativePixelsAndInvalidates() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit physical GPU fixture required",InstrumentationRegistry.getArguments().getString("no5ChromeLive")=="1")
        val activity=ActivityScenario.launch(MainActivity::class.java)
        var cached by mutableStateOf(false)
        var stage by mutableStateOf(0)
        val drawn=AtomicInteger(-1)
        lateinit var window:android.view.Window
        lateinit var decor:android.view.View
        val dir=File(instrumentation.targetContext.cacheDir,"eq-header-sky-proof").apply{mkdirs()}
        val rows=JSONArray()
        try {
            activity.onActivity { host ->
                window=host.window;decor=window.decorView
                assertTrue("Physical hardware drawing required",decor.isHardwareAccelerated)
                val scenes=listOf(HelmScene(host,false),HelmScene(host,true))
                host.setContent {
                    val scene=scenes[stage/3]
                    val sample=stage%3
                    val quality=listOf("LIVE","STALE","UNAVAILABLE")[sample]
                    val values=HelmRenderValues(quality=quality,demo=false,text=mapOf(
                        "rpm-port" to listOf("1250","2450","—")[sample],
                        "rpm-stbd" to listOf("1280","2490","—")[sample],
                        "hud-sog" to listOf("5.2","8.4","—")[sample],
                        "hud-sog-unit" to "kn",
                        "header-source-label" to "SOURCE · $quality",
                    ))
                    val drawingStage=stage
                    Box(Modifier.fillMaxSize().drawWithContent {drawContent();drawn.set(drawingStage)}) {
                        SoundHeaderLayer(scene,scene.night,1f/(host.resources.displayMetrics.density),rememberUpdatedState(values),
                            status={quality},description="Frozen native chrome fixture",cached=cached)
                    }
                }
            }
            fun committed() {
                instrumentation.waitForIdleSync()
                val done=CountDownLatch(1)
                instrumentation.runOnMainSync {
                    decor.viewTreeObserver.registerFrameCommitCallback {done.countDown()}
                    decor.invalidate()
                }
                assertTrue("Hardware frame did not commit",done.await(5,TimeUnit.SECONDS))
            }
            fun capture(name:String):Bitmap {
                // A committed hardware frame is the fence; no sleep-based screenshot proof.
                committed()
                assertEquals(2000,decor.width);assertEquals(1200,decor.height)
                val image=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888)
                val done=CountDownLatch(1);var result=-1
                instrumentation.runOnMainSync {
                    PixelCopy.request(window,image,{result=it;done.countDown()},Handler(Looper.getMainLooper()))
                }
                assertTrue(done.await(5,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,result)
                File(dir,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
                return image
            }
            for(next in 0..5) {
                instrumentation.runOnMainSync {cached=false;stage=next}
                val deadline=android.os.SystemClock.elapsedRealtime()+5000
                while(drawn.get()!=next&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(10)
                assertEquals("New header values were not drawn",next,drawn.get())
                val name="${if(next<3)"day"else"night"}-${next%3}"
                val direct=capture("$name-direct")
                instrumentation.runOnMainSync {cached=true}
                val offscreen=capture("$name-cached")
                instrumentation.runOnMainSync {cached=false}
                val returned=capture("$name-returned")
                try {
                    val match=direct.sameAs(offscreen)&&direct.sameAs(returned)
                    rows.put(JSONObject().put("case",name).put("pixelIdentical",match).put("width",direct.width).put("height",direct.height))
                    File(dir,"result.json").writeText(JSONObject().put("schema",1).put("cases",rows).toString(2))
                    assertTrue("Cached header/footer with direct contours differs on physical GPU: $name",match)
                } finally {direct.recycle();offscreen.recycle();returned.recycle()}
            }
        } finally {activity.close()}
    }
}
