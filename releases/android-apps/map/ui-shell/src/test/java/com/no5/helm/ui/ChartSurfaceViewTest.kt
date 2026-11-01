package com.no5.helm.ui

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Camera rejection must happen before canonical camera and reused pixels change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ChartSurfaceViewTest {
    private val home = ChartViewport(42.36, -71.04, .15, .3, 1000, 600)
    private fun view(fling: ChartFlingController? = null) = ChartSurfaceView(RuntimeEnvironment.getApplication(),
        flingController = fling).apply {
        resizeSurface(1000, 600)
        measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        layout(0, 0, 1000, 600)
        viewport = home
        chartNavigationBounds = { ChartNavigationBounds(1, listOf(ChartCellExtent("world", -85.0, 85.0, -180.0, 180.0))) }
        bufferTransformMode = ChartBufferTransformMode.TRANSFORM_WITH_RENDER
        bufferTransformGeneration = 7
        onTouchBegin = { true }
        cameraRevision = { 10L }
        consumeTransformBuffer(ChartBufferSnapshot(10, 7, 10,
            ChartFrame(10, "home", ChartSurfaceGeometry.fit(1000, 600), home), true))
    }
    private fun send(view: ChartSurfaceView, action: Int, time: Long, x: Float) {
        MotionEvent.obtain(1000, time, action, x, 300f, 0).let {
            try { assertTrue(view.onTouchEvent(it)) } finally { it.recycle() }
        }
    }

    @Test fun rejectedTouchDoesNotPublishCameraOrMoveCachedPixels() {
        val view = view()
        var published = 0
        view.onTouchViewport = { published++ }
        view.admitCamera = { _, _, _, _ -> null }
        send(view, MotionEvent.ACTION_DOWN, 1000, 400f)
        send(view, MotionEvent.ACTION_MOVE, 1020, 600f)
        assertEquals(home, view.viewport)
        assertNull(view.bufferTransformTarget)
        assertEquals(0, published)
        send(view, MotionEvent.ACTION_UP, 1040, 600f)
        assertEquals(home, view.viewport)
    }

    @Test fun mixedGestureBuildsNoZoomAlternativeFromOriginalAnchorAndBasis() {
        val view = view()
        val anchor = ScreenPoint(200.0, 180.0)
        val pan = ScreenPoint(70.0, -20.0)
        var seenRecovery = true
        view.admitCamera = { basis, wanted, withoutZoom, recovery ->
            assertEquals(home, basis)
            assertEquals(transformChartViewport(home, anchor, pan, .5, .2), wanted)
            assertEquals(transformChartViewport(home, anchor, pan, 1.0, .2), withoutZoom)
            assertNotEquals(wanted.copy(metresToPixels = home.metresToPixels), withoutZoom)
            seenRecovery = recovery
            withoutZoom
        }
        val accepted = view.admittedMotion(anchor, pan, .5, .2)
        assertEquals(transformChartViewport(home, anchor, pan, 1.0, .2), accepted)
        assertFalse(seenRecovery)
        assertEquals("Preflight has no camera side effects", home, view.viewport)
    }

    @Test fun onlyPureZoomInMarksRecovery() {
        val view = view()
        val flags = mutableListOf<Boolean>()
        view.admitCamera = { _, wanted, _, recovery -> flags += recovery; wanted }
        view.admittedMotion(ScreenPoint(500.0, 300.0), zoom = 2.0)
        view.admittedMotion(ScreenPoint(500.0, 300.0), ScreenPoint(1.0, 0.0), zoom = 2.0)
        view.admittedMotion(ScreenPoint(500.0, 300.0), zoom = 2.0, rotation = .1)
        view.admittedMotion(ScreenPoint(500.0, 300.0), zoom = .5)
        assertEquals(listOf(true, false, false, false), flags)
    }

    @Test fun unchangedAdmittedCameraDoesNotCreateAnotherChangeEvent() {
        val view = view()
        var published = 0
        view.onTouchViewport = { published++ }
        view.admitCamera = { basis, _, _, _ -> basis }
        send(view, MotionEvent.ACTION_DOWN, 1000, 400f)
        send(view, MotionEvent.ACTION_MOVE, 1020, 600f)
        assertEquals(home, view.viewport)
        assertEquals(0, published)
        send(view, MotionEvent.ACTION_CANCEL, 1040, 600f)
    }

    @Test fun nativeFallbackIsCheckedBeforeHandlerAndKeepsNativeCameraOwnership() {
        val view = view()
        val nativeBasis = home.copy(longitude = home.longitude + .01)
        view.nativeCameraBasis = { nativeBasis }
        view.onTouchBegin = { false }
        val motions = mutableListOf<ChartMotion>()
        view.nativeMotion = { motions += it }
        var checked = false
        view.admitCamera = { basis, _, _, _ -> assertEquals(nativeBasis, basis); checked = true; null }
        send(view, MotionEvent.ACTION_DOWN, 1000, 400f)
        send(view, MotionEvent.ACTION_MOVE, 1020, 600f)
        assertTrue(checked)
        assertTrue(motions.isEmpty())
        assertEquals(home, view.viewport)
        send(view, MotionEvent.ACTION_CANCEL, 1040, 600f)
    }

    private class Driver : ChartFlingDriver {
        override var isFinished = true
        override var currentX = 0
        override var currentY = 0
        var advances = 0
        override fun fling(velocityX: Int, velocityY: Int) { isFinished = false }
        override fun computeScrollOffset(): Boolean { advances++; currentX = 100; return true }
        override fun forceFinished() { isFinished = true }
    }

    @Test fun rejectedMomentumStopsAtLastAcceptedCameraWithoutLateBounce() {
        val context = RuntimeEnvironment.getApplication()
        val driver = Driver()
        val view = view(ChartFlingController(context, driver, touchSlop = 8.0, minimumVelocity = 50, maximumVelocity = 8000))
        val parent = FrameLayout(context).apply { addView(view, FrameLayout.LayoutParams(-1, -1)) }
        parent.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 1000, 600)
        view.momentumEnabled = true
        var finishes = 0
        view.onTouchFinish = { finishes++ }
        send(view, MotionEvent.ACTION_DOWN, 1000, 400f)
        send(view, MotionEvent.ACTION_MOVE, 1020, 470f)
        send(view, MotionEvent.ACTION_MOVE, 1040, 540f)
        send(view, MotionEvent.ACTION_UP, 1060, 610f)
        assertFalse(driver.isFinished)
        val last = view.viewport
        view.admitCamera = { _, _, _, _ -> null }
        view.advanceFlingFrame()
        assertEquals(last, view.viewport)
        assertTrue(driver.isFinished)
        assertEquals(1, finishes)
        view.advanceFlingFrame()
        assertEquals(1, driver.advances)
        assertEquals(1, finishes)
    }
}
