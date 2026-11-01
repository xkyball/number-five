package com.no5.helm.ui

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Rect
import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ChartSurfaceFlingTest {
    private class Driver : ChartFlingDriver {
        data class Position(val x: Int, val y: Int, val last: Boolean)
        val positions = ArrayDeque<Position>()
        override var isFinished = true
        override var currentX = 0
        override var currentY = 0
        override fun fling(velocityX: Int, velocityY: Int) { isFinished = false }
        override fun computeScrollOffset(): Boolean {
            val next = positions.removeFirstOrNull() ?: return false
            currentX = next.x; currentY = next.y; isFinished = next.last; return true
        }
        override fun forceFinished() { isFinished = true; positions.clear() }
    }
    private val home = ChartViewport(42.36, -71.04, .15, .3, 1000, 600)
    private val geometry = ChartSurfaceGeometry.fit(1000, 600)
    private lateinit var driver: Driver
    private lateinit var view: ChartSurfaceView
    private lateinit var parent: FrameLayout
    private var starts = 0
    private var finishes = 0
    private var flings = 0
    private var revision = 10L
    private val activity = mutableListOf<Boolean>()

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        driver = Driver()
        view = ChartSurfaceView(context, flingController = ChartFlingController(context, driver,
            touchSlop = 8.0, minimumVelocity = 50, maximumVelocity = 8000))
        parent = FrameLayout(context).apply { addView(view, FrameLayout.LayoutParams(-1, -1)) }
        parent.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 1000, 600)
        view.viewport = home; view.momentumEnabled = true
        view.chartNavigationBounds = { ChartNavigationBounds(1, listOf(ChartCellExtent("world", -85.0, 85.0, -180.0, 180.0))) }
        view.bufferTransformMode = ChartBufferTransformMode.TRANSFORM_WITH_RENDER; view.bufferTransformGeneration = 7
        view.onTouchBegin = { starts++; true }; view.onTouchFinish = { finishes++ }
        view.onFlingStarted = { flings++ }; view.onTouchActivity = { activity += it }
        view.onTouchViewport = { revision++ }; view.cameraRevision = { revision }
        view.consumeTransformBuffer(ChartBufferSnapshot(10, 7, 10, ChartFrame(10, "home", geometry, home), true))
    }
    private fun send(action: Int, time: Long, x: Float) {
        MotionEvent.obtain(1000, time, action, x, 300f, 0).let {
            try { assertTrue(parent.dispatchTouchEvent(it)) } finally { it.recycle() }
        }
    }
    private fun fastPan() {
        send(MotionEvent.ACTION_DOWN, 1000, 400f)
        send(MotionEvent.ACTION_MOVE, 1020, 470f)
        send(MotionEvent.ACTION_MOVE, 1040, 540f)
        send(MotionEvent.ACTION_UP, 1060, 610f)
    }

    @Test fun physicalUpEndsContactButKeepsCameraOwnershipThroughTheLastMomentumFrame() {
        fastPan(); val release = view.viewport
        assertEquals(listOf(true, false), activity)
        assertFalse(view.isTouchActive); assertEquals(1, starts); assertEquals(1, flings); assertEquals(0, finishes)
        driver.positions.add(Driver.Position(80, 0, false))
        driver.positions.add(Driver.Position(200, 0, true))
        view.advanceFlingFrame(); assertEquals(ChartFlingCamera(release, geometry).at(80, 0), view.viewport)
        assertEquals(0, finishes)
        // A delayed replacement image changes only the pixel basis, never the fling path.
        view.consumeTransformBuffer(ChartBufferSnapshot(11, 7, 10,
            ChartFrame(11, "delayed", geometry, home.copy(longitude = home.longitude + .002)), true))
        view.advanceFlingFrame()
        assertEquals(ChartFlingCamera(release, geometry).at(200, 0), view.viewport)
        assertEquals(1, finishes)
        view.advanceFlingFrame(); assertEquals(1, finishes)
    }

    @Test fun retouchStopsAtPublishedCameraAndStartsNewPanWithoutTheAbandonedEndpoint() {
        fastPan()
        driver.positions.add(Driver.Position(80, 0, false)); driver.positions.add(Driver.Position(700, 0, true))
        view.advanceFlingFrame(); val shown = view.viewport
        send(MotionEvent.ACTION_DOWN, 2000, 500f)
        assertEquals(shown, view.viewport); assertEquals(1, finishes); assertEquals(2, starts)
        view.advanceFlingFrame(); assertEquals(shown, view.viewport)
        send(MotionEvent.ACTION_MOVE, 2020, 450f)
        assertEquals(transformChartViewport(shown, ScreenPoint(500.0, 300.0), ScreenPoint(-50.0, 0.0)), view.viewport)
        send(MotionEvent.ACTION_CANCEL, 2040, 900f)
        assertEquals(2, finishes); assertFalse(view.isTouchActive)
    }

    @Test fun returningButtonMaskAfterPhysicalUpKeepsMomentumButAnExplicitDockClaimStopsIt() {
        fastPan(); val release = view.viewport
        view.blockedRegions = listOf(Rect(800f, 0f, 1000f, 600f))
        assertEquals(0, finishes)
        driver.positions.add(Driver.Position(80, 0, false)); driver.positions.add(Driver.Position(400, 0, true))
        view.advanceFlingFrame()
        val shown = ChartFlingCamera(release, geometry).at(80, 0)
        assertEquals(shown, view.viewport); assertEquals(0, finishes)
        view.cancelTouchForDock()
        assertEquals(1, finishes)
        view.advanceFlingFrame()
        assertEquals(shown, view.viewport)
        view.cancelTouchForDock(); assertEquals(1, finishes)
    }

    @Test fun skippedFlingFrameStopsAtActiveCellEdgeAndEndsOwnershipOnce() {
        val extent = ChartCellExtent("local", home.latitude - .1, home.latitude + .1,
            home.longitude - .1, home.longitude + .1)
        val bounds=ChartNavigationBounds(4,listOf(extent));val region=ChartCameraBounds.from(bounds)!!
        view.chartNavigationBounds = { bounds }
        view.admitCamera={basis,wanted,_,_->region.constrain(wanted,basis).effective}
        fastPan()
        assertEquals(1, flings); assertEquals(0, finishes)
        driver.positions.add(Driver.Position(20000, 0, false))
        driver.positions.add(Driver.Position(70000, 0, true))
        view.advanceFlingFrame()
        val edge = view.viewport
        assertEquals(edge,region.constrain(edge).effective)
        assertTrue(edge.longitude>extent.west && edge.longitude<home.longitude)
        assertEquals(1, finishes); assertFalse(view.isTouchActive)
        view.advanceFlingFrame()
        assertEquals(edge, view.viewport); assertEquals(1, finishes)
    }

    @Test fun unavailableBoundsDisableOnlyMomentumAndLeaveDirectDraggingAvailable() {
        view.chartNavigationBounds = { null }
        fastPan()
        assertNotEquals(home, view.viewport)
        val release = view.viewport
        assertEquals(0, flings); assertEquals(1, finishes)
        driver.positions.add(Driver.Position(1000, 0, true))
        view.advanceFlingFrame()
        assertEquals(release, view.viewport)
    }

    @Test fun newDataGenerationEndsMomentumWithoutRecentringOrApplyingPendingFrame() {
        var generation = 1L
        view.chartNavigationBounds = { ChartNavigationBounds(generation,
            listOf(ChartCellExtent("world", -85.0, 85.0, -180.0, 180.0))) }
        fastPan()
        driver.positions.add(Driver.Position(80, 0, false)); driver.positions.add(Driver.Position(700, 0, true))
        view.advanceFlingFrame(); val shown = view.viewport
        generation = 2
        view.advanceFlingFrame()
        assertEquals(shown, view.viewport); assertEquals(1, finishes)
        view.advanceFlingFrame(); assertEquals(1, finishes)
    }

    @Test fun imageGenerationResetStopsMomentumAndReleasesItsOwnerExactlyOnce() {
        fastPan()
        driver.positions.add(Driver.Position(80, 0, false)); driver.positions.add(Driver.Position(700, 0, true))
        view.advanceFlingFrame(); val shown = view.viewport
        view.bufferTransformGeneration = 8
        assertEquals(shown, view.viewport); assertEquals(1, finishes)
        view.advanceFlingFrame()
        assertEquals(shown, view.viewport); assertEquals(1, finishes)
        view.bufferTransformGeneration = 9
        assertEquals(1, finishes)
    }
}
