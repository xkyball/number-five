package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.no5.domain.*
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeaderChromeProjectionTest {
    private val values=HelmRenderValues(quality="LIVE",demo=false,text=mapOf(
        "rpm-port" to "1234","rpm-stbd" to "2345","hud-sog" to "4.2","hud-sog-unit" to "kn",
        "header-quality-portRpm" to "SOURCE · LIVE","header-quality-starboardRpm" to "SOURCE · STALE",
        "header-quality-speed" to "SOURCE · LIVE","header-source-label" to "Gateway",
        "heading" to "42","weather" to "changed elsewhere"))
    private fun bitmap(draw:(Canvas)->Unit)=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888).also{draw(Canvas(it))}
    @Test fun projectionKeepsEveryPaintedHeaderPixel(){
        val context=RuntimeEnvironment.getApplication()
        for(night in listOf(false,true)){
            val scene=HelmScene(context,night)
            val full=bitmap{scene.drawChrome(it,"AUDIO",values,"AUDIO · GATEWAY")}
            val projected=bitmap{scene.drawChrome(it,"AUDIO",headerChromeValues(values),"AUDIO · GATEWAY")}
            assertTrue(full.sameAs(projected));full.recycle();projected.recycle()
        }
        for(key in values.text.keys.take(8))assertNotEquals(headerChromeValues(values),headerChromeValues(values.copy(text=values.text+(key to "different"))),key)
    }
    @Test fun hiddenMotionDoesNotInvalidateVisibleChrome(){
        val raw=mutableStateOf(values);val visible=derivedStateOf{headerChromeValues(raw.value)}
        var invalidations=0;val owner=Any();val observer=SnapshotStateObserver{it()}
        observer.start()
        try{
            observer.observeReads(owner,{_:Any->invalidations++}){visible.value}
            repeat(20){raw.value=values.copy(heading=it.toFloat(),portTrim=it.toFloat(),text=values.text+("weather" to "$it"));Snapshot.sendApplyNotifications()}
            assertEquals(0,invalidations)
            raw.value=values.copy(text=values.text+("hud-sog" to "5.0"));Snapshot.sendApplyNotifications()
            assertEquals(1,invalidations)
        }finally{observer.stop();observer.clear()}
    }
    @Test fun projectedKnobsKeepNativePixels(){
        val context=RuntimeEnvironment.getApplication();val source=MockAudioSource(now={10000})
        val controller=AudioNativeController(Dispatchers.Unconfined,{10000},false,false,source)
        try{for(night in listOf(false,true))for(selected in listOf(false,true)){
            val ink=KnobOverlay(context)
            val full=bitmap{ink.draw(it,night,controller,useSelectedAudioFader=selected)}
            val projected=bitmap{ink.draw(it,night,ink.values(controller,useSelectedAudioFader=selected))}
            assertTrue(full.sameAs(projected));full.recycle();projected.recycle()
        }}finally{controller.dispose();source.close()}
    }
}
