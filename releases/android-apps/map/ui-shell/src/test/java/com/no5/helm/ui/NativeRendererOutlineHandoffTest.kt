package com.no5.helm.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.helm.chart.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Compose AndroidView host + MotionEvents; producer/consumer is controlled, no device/GLES claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeRendererOutlineHandoffTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val engine=FrameEngine()
    private lateinit var activity:ActivityController<No5ImmersiveActivity>
    private lateinit var chart:ChartSurfaceView
    private lateinit var outline:ChartOutlineView
    private lateinit var texture:SurfaceTexture
    private lateinit var adapter:NativeRendererAdapter
    private var time=1000L
    @Before fun setup() {
        activity=Robolectric.buildActivity(No5ImmersiveActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar);activity.setup()
        adapter=NativeRendererAdapter(engine,engine.status.value.viewport)
        activity.get().setContent { adapter.Content(Modifier.fillMaxSize()) {} }
        compose.waitForIdle()
        compose.runOnIdle {
            fun descendants(v:View):Sequence<View> = sequence { yield(v);if(v is ViewGroup)for(i in 0 until v.childCount)yieldAll(descendants(v.getChildAt(i))) }
            val views=descendants(activity.get().window.decorView).toList()
            chart=views.filterIsInstance<ChartSurfaceView>().single();outline=views.filterIsInstance<ChartOutlineView>().single()
            texture=SurfaceTexture(0);chart.surfaceTextureListener!!.onSurfaceTextureAvailable(texture,chart.width,chart.height)
        }
        compose.waitForIdle()
    }
    @After fun close() {
        if(::chart.isInitialized&&chart.renderSurface!=null)chart.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture)
        if(::activity.isInitialized)activity.pause().stop().destroy()
    }
    private fun send(action:Int,x:Float) {
        compose.runOnIdle {
            time+=16;val event=MotionEvent.obtain(1000,time,action,x,chart.height*.5f,0)
            try { assertTrue(chart.dispatchTouchEvent(event)) } finally { event.recycle() }
        };compose.waitForIdle()
    }
    private fun move() { send(MotionEvent.ACTION_DOWN,chart.width*.5f);send(MotionEvent.ACTION_MOVE,chart.width*.5f+100) }
    private fun assertReleased(action:Int) {
        send(action,chart.width*.5f+100)
        assertEquals(1,engine.finalRequests)
        assertFalse(engine.status.value.touchPreview!!.held)
        assertNull(engine.status.value.confirmedFrame)
    }
    @Test fun lastUpRefreshesWaitingOverlayEvenWhenAndroidViewParametersStayStable() {
        move();assertTrue(outline.preview!!.held);assertReleased(MotionEvent.ACTION_UP)
        assertFalse("Compose host must show the release, not retain the last MOVE's held state",outline.preview!!.held)
        assertTrue(outline.preview!!.showing)
    }
    @Test fun fullRenderCompletionKeepsPreviewUntilExactConsumedBufferThenRemovesOpaqueView() {
        move();assertReleased(MotionEvent.ACTION_UP)
        compose.runOnIdle { engine.renderCompleted(123) };compose.waitForIdle()
        assertNull(engine.status.value.confirmedFrame);assertTrue(outline.preview!!.showing)
        compose.runOnIdle { chart.onBufferConsumed(122) };compose.waitForIdle();assertNull(engine.status.value.confirmedFrame)
        compose.runOnIdle { chart.onBufferConsumed(123) };compose.waitForIdle()
        assertEquals(engine.status.value.viewport,engine.status.value.confirmedFrame!!.viewport)
        assertNull(engine.status.value.touchPreview)
        assertNull("Confirmed full frame must clear the actual mounted outline View",outline.preview)
        assertEquals(View.INVISIBLE,outline.visibility)
        compose.runOnIdle {
            val b=Bitmap.createBitmap(chart.width,chart.height,Bitmap.Config.ARGB_8888)
            try { outline.draw(Canvas(b));assertEquals("No opaque grey preview pixels after handoff",0,b.getPixel(chart.width/2,chart.height/2)) } finally { b.recycle() }
        }
    }
    @Test fun cancelThenRetouchRejectsOldBufferAndNewestFullFrameStillClearsPreview() {
        move();assertReleased(MotionEvent.ACTION_CANCEL);compose.runOnIdle { engine.renderCompleted(200) }
        move();compose.runOnIdle { chart.onBufferConsumed(200) };compose.waitForIdle()
        assertTrue(engine.status.value.touchPreview!!.held);assertNull(engine.status.value.confirmedFrame);assertTrue(outline.preview!!.showing)
        send(MotionEvent.ACTION_UP,chart.width*.5f+100);assertEquals(2,engine.finalRequests)
        compose.runOnIdle { engine.renderCompleted(201);chart.onBufferConsumed(201) };compose.waitForIdle()
        assertNotNull(engine.status.value.confirmedFrame);assertNull(outline.preview);assertEquals(View.INVISIBLE,outline.visibility)
    }
    @Test fun disablingDuringTouchCancelsRemainingStreamAndNextTouchUsesNativeFullMotion() {
        move();assertEquals(View.VISIBLE,outline.visibility)
        compose.runOnIdle { assertTrue(adapter.setTouchOutlinesEnabled(false)) };compose.waitForIdle()
        assertNull(outline.preview);assertEquals(View.INVISIBLE,outline.visibility)
        send(MotionEvent.ACTION_MOVE,chart.width*.5f+200);send(MotionEvent.ACTION_UP,chart.width*.5f+200)
        assertEquals(0,engine.nativeMotions.size);assertEquals(0,engine.finalRequests)
        for(action in listOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL)) {
            move();send(action,chart.width*.5f+100)
            assertNull(engine.status.value.touchPreview);assertEquals(View.INVISIBLE,outline.visibility)
        }
        assertEquals(2,engine.nativeMotions.size);assertEquals(0,engine.finalRequests)
    }
    @Test fun disableWhileWaitingRejectsLateBufferAndReenableNeedsANewGesture() {
        move();assertReleased(MotionEvent.ACTION_UP);compose.runOnIdle { engine.renderCompleted(300);assertTrue(adapter.setTouchOutlinesEnabled(false));chart.onBufferConsumed(300) };compose.waitForIdle()
        assertNull(outline.preview);assertEquals(View.INVISIBLE,outline.visibility)
        move();assertEquals(1,engine.nativeMotions.size)
        compose.runOnIdle { assertTrue(adapter.setTouchOutlinesEnabled(true)) };compose.waitForIdle()
        send(MotionEvent.ACTION_MOVE,chart.width*.5f+200);send(MotionEvent.ACTION_UP,chart.width*.5f+200)
        assertEquals(1,engine.nativeMotions.size)
        move();send(MotionEvent.ACTION_CANCEL,chart.width*.5f+100)
        assertTrue(outline.preview!!.showing);assertFalse(outline.preview!!.held)
        compose.runOnIdle { engine.renderCompleted(301);chart.onBufferConsumed(301) };compose.waitForIdle()
        assertNull(outline.preview);assertEquals(View.INVISIBLE,outline.visibility)
    }
    @Test fun surfaceReplacementClearsVisiblePreviewAndRetainsBothSelectedModes() {
        for(enabled in listOf(true,false)) {
            compose.runOnIdle { assertTrue(adapter.setTouchOutlinesEnabled(enabled)) };compose.waitForIdle()
            move()
            compose.runOnIdle {
                chart.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture)
                texture=SurfaceTexture(0);chart.surfaceTextureListener!!.onSurfaceTextureAvailable(texture,chart.width,chart.height)
            };compose.waitForIdle()
            assertEquals(enabled,engine.status.value.touchOutlinesEnabled)
            assertNull(outline.preview);assertEquals(View.INVISIBLE,outline.visibility)
            move();send(MotionEvent.ACTION_UP,chart.width*.5f+100)
            if(enabled) { compose.runOnIdle { engine.renderCompleted(400);chart.onBufferConsumed(400) };compose.waitForIdle() }
            assertNull(outline.preview);assertEquals(View.INVISIBLE,outline.visibility)
        }
    }
    private class FrameEngine:ChartEngine {
        private val home=ChartViewport(42.36,-71.04,.15)
        override val status=MutableStateFlow(ChartEngineStatus(initialized=true,chartLoaded=true,viewport=home,
            outlines=ChartOutlines(GeoProjection(42.36,-71.04),floatArrayOf(-100f,0f,100f,0f),20.0,"controlled-coast"),touchOutlinesAvailable=true))
        private var geometry=ChartSurfaceGeometry.fit(2000,1200)
        private var generation=0L
        private var revision=0L
        private var pending:Pair<Long,ChartFrame>?=null
        var finalRequests=0;private set
        val nativeMotions=mutableListOf<ChartMotion>()
        override fun attachSurface(surface:Surface,width:Int,height:Int) { geometry=ChartSurfaceGeometry.fit(width,height);status.value=status.value.copy(surfaceAttached=true,confirmedFrame=frame()) }
        private fun frame()=ChartFrame(++revision,"controlled:$generation:$revision",geometry,status.value.viewport,null)
        override fun setTouchOutlinesEnabled(enabled:Boolean):Boolean { pending=null;generation++;status.value=status.value.copy(touchOutlinesEnabled=enabled,touchPreview=null,confirmedFrame=frame());return true }
        override fun beginTouchGesture():Boolean { val s=status.value;if(!s.touchOutlinesEnabled)return false;val base=s.confirmedFrame?:s.touchPreview?.baseFrame?:return false;pending=null;status.value=s.copy(touchPreview=ChartTouchPreview(s.viewport,true,s.touchPreview?.showing==true,++generation,base));return true }
        override fun moveCamera(motion:ChartMotion) { nativeMotions+=motion;val view=transformChartViewport(status.value.viewport,geometry.toFrame(motion.anchor),ScreenPoint(motion.pan.x/geometry.scale,motion.pan.y/geometry.scale),motion.zoom,motion.rotation);status.value=status.value.copy(viewport=view,confirmedFrame=frame().copy(viewport=view)) }
        override fun updateTouchViewport(viewport:ChartViewport) { val s=status.value;status.value=s.copy(viewport=viewport,confirmedFrame=null,touchPreview=s.touchPreview!!.copy(camera=viewport,showing=true)) }
        override fun endTouchGesture() { val s=status.value;val preview=s.touchPreview?:return;if(!preview.held)return;finalRequests++;status.value=s.copy(touchPreview=preview.copy(held=false)) }
        fun renderCompleted(stamp:Long) { check(status.value.touchPreview?.held==false);pending=stamp to frame() }
        override fun bufferConsumed(timestampNanos:Long) { val p=pending?:return;if(p.first!=timestampNanos||status.value.touchPreview?.held!=false)return;pending=null;status.value=status.value.copy(confirmedFrame=p.second,touchPreview=null) }
        override fun interruptTouchGesture() { pending=null;status.value=status.value.copy(touchPreview=null) }
        override fun detachSurface() { interruptTouchGesture();status.value=status.value.copy(surfaceAttached=false,confirmedFrame=null) }
        override fun setViewport(viewport:ChartViewport) { status.value=status.value.copy(viewport=viewport) }
        override suspend fun initialize(config:ChartEngineConfig)=EngineResult(true)
        override suspend fun loadChartSet(id:String,paths:List<String>)=EngineResult(true)
        override suspend fun queryFeature(point:ScreenPoint)=emptyList<ChartFeature>()
        override fun setS52Settings(settings:S52Settings)=Unit
        override fun setOwnship(state:OwnshipProjection)=Unit
        override fun setAisTargets(targets:List<AisProjection>)=Unit
        override fun setRouteProjection(route:RouteProjection?)=Unit
        override fun setWaypointProjection(waypoints:List<WaypointProjection>)=Unit
        override fun close()=Unit
    }
}
