package com.no5.helm.ui

import android.view.InputDevice
import android.view.MotionEvent
import com.no5.helm.chart.ChartNavigationBounds
import com.no5.helm.chart.ChartCellExtent
import com.no5.helm.chart.ChartSurfaceGeometry
import com.no5.helm.chart.ChartViewport
import com.no5.helm.chart.ChartCameraBounds
import com.no5.helm.chart.ScreenPoint
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ChartFlingControllerTest {
    private val geometry = ChartSurfaceGeometry.fit(1000, 600)
    private val bounds = ChartNavigationBounds(1, listOf(ChartCellExtent("world", -85.0, 85.0, -180.0, 180.0)))
    private val home = ChartViewport(42.36, -71.04, .15, .6, 1000, 600)
    private lateinit var driver: PositionDriver
    private lateinit var controller: ChartFlingController

    /** The test supplies absolute positions; it does not reimplement Android's friction curve. */
    private class PositionDriver : ChartFlingDriver {
        data class Frame(val x: Int, val y: Int, val last: Boolean = false)
        private val frames = ArrayDeque<Frame>()
        override var isFinished = true
        override var currentX = 0
        override var currentY = 0
        var velocity = ScreenPoint(0.0, 0.0)
        var starts = 0
        var stops = 0
        fun enqueue(vararg frame: Frame) { frames.addAll(frame) }
        override fun fling(velocityX: Int, velocityY: Int) {
            starts++; velocity = ScreenPoint(velocityX.toDouble(), velocityY.toDouble())
            currentX = 0; currentY = 0; isFinished = false
        }
        override fun computeScrollOffset(): Boolean {
            val frame = frames.removeFirstOrNull() ?: return false
            currentX = frame.x; currentY = frame.y; isFinished = frame.last
            return true
        }
        override fun forceFinished() { stops++; isFinished = true; frames.clear() }
    }

    @Before fun setup() {
        driver = PositionDriver()
        controller = ChartFlingController(RuntimeEnvironment.getApplication(), driver,
            touchSlop = 8.0, minimumVelocity = 50, maximumVelocity = 8000)
    }

    private fun send(action: Int, time: Long, vararg point: TouchPoint) {
        val event = MotionEvent.obtain(1000, time, action, point.size,
            point.map { p -> MotionEvent.PointerProperties().apply { id = p.id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray(),
            point.map { p -> MotionEvent.PointerCoords().apply { x = p.x.toFloat(); y = p.y.toFloat(); pressure = 1f; size = 1f } }.toTypedArray(),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { controller.observeAcceptedTouch(event) } finally { event.recycle() }
    }
    private fun p(x: Double, y: Double = 300.0, id: Int = 7) = TouchPoint(id, x, y)
    private fun fastPan() {
        send(MotionEvent.ACTION_DOWN, 1000, p(400.0))
        send(MotionEvent.ACTION_MOVE, 1020, p(470.0, 325.0))
        send(MotionEvent.ACTION_MOVE, 1040, p(540.0, 350.0))
        send(MotionEvent.ACTION_UP, 1060, p(610.0, 375.0))
    }

    @Test fun slowDragAndQuickMotionInsideSlopDoNotStartMomentum() {
        send(MotionEvent.ACTION_DOWN, 1000, p(400.0))
        send(MotionEvent.ACTION_MOVE, 2000, p(412.0))
        send(MotionEvent.ACTION_UP, 3000, p(424.0))
        assertFalse(controller.start(home, geometry, true, bounds))
        send(MotionEvent.ACTION_DOWN, 4000, p(400.0))
        send(MotionEvent.ACTION_MOVE, 4001, p(401.0))
        send(MotionEvent.ACTION_UP, 4002, p(402.0))
        assertFalse(controller.start(home, geometry, true, bounds))
        assertEquals(0, driver.starts)
    }

    @Test fun fastReleaseUsesPointerIdEventTimesAndOneReleaseOnly() {
        fastPan()
        assertTrue(controller.start(home, geometry, true, bounds))
        assertTrue(driver.velocity.x > 1000); assertTrue(driver.velocity.y > 100)
        assertTrue(driver.velocity.x <= 8000); assertTrue(driver.velocity.y <= 8000)
        assertFalse("The same UP cannot launch another fling", controller.start(home, geometry, true, bounds))
        assertEquals(1, driver.starts)
        assertTrue(controller.isRunning)
    }

    @Test fun navigationRejectedInvalidCameraOrResizeCannotLaunchThePendingRelease() {
        fastPan(); assertFalse(controller.start(home, geometry, false, bounds))
        assertFalse(controller.start(home, geometry, true, bounds))
        fastPan(); assertFalse(controller.start(home.copy(width = 900), geometry, true, bounds))
        fastPan(); assertFalse(controller.start(home.copy(latitude = Double.NaN), geometry, true, bounds))
        fastPan(); assertFalse(controller.start(home.copy(metresToPixels = 0.0), geometry, true, bounds))
        assertEquals(0, driver.starts)
    }

    @Test fun secondPointerAndPointerReplacementDisqualifyTheWholeSequence() {
        send(MotionEvent.ACTION_DOWN, 1000, p(400.0))
        send(MotionEvent.ACTION_MOVE, 1020, p(500.0))
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            1040, p(550.0), p(700.0, id = 19))
        send(MotionEvent.ACTION_POINTER_UP, 1060, p(600.0), p(750.0, id = 19))
        send(MotionEvent.ACTION_MOVE, 1080, p(850.0, id = 19))
        send(MotionEvent.ACTION_UP, 1100, p(950.0, id = 19))
        assertFalse(controller.start(home, geometry, true, bounds))
        send(MotionEvent.ACTION_DOWN, 1200, p(400.0))
        send(MotionEvent.ACTION_MOVE, 1220, p(500.0, id = 19))
        send(MotionEvent.ACTION_UP, 1240, p(600.0, id = 19))
        assertFalse(controller.start(home, geometry, true, bounds))
        assertEquals(0, driver.starts)
    }

    @Test fun cancelAndBlockedSequenceCannotLeaveAnOldVelocityForTheNextTap() {
        fastPan(); controller.cancel()
        assertFalse(controller.start(home, geometry, true, bounds))
        send(MotionEvent.ACTION_DOWN, 1200, p(400.0))
        send(MotionEvent.ACTION_MOVE, 1220, p(500.0))
        send(MotionEvent.ACTION_CANCEL, 1240, p(600.0))
        assertFalse(controller.start(home, geometry, true, bounds))
        send(MotionEvent.ACTION_DOWN, 1300, p(400.0))
        send(MotionEvent.ACTION_UP, 1320, p(400.0))
        assertFalse(controller.start(home, geometry, true, bounds))
        assertNull(controller.advanceFrame())
    }

    @Test fun backwardsEventTimeDisqualifiesVelocityRatherThanUsingArrivalTime() {
        send(MotionEvent.ACTION_DOWN, 1000, p(400.0))
        send(MotionEvent.ACTION_MOVE, 1040, p(500.0))
        send(MotionEvent.ACTION_MOVE, 1020, p(600.0))
        send(MotionEvent.ACTION_UP, 1060, p(700.0))
        assertFalse(controller.start(home, geometry, true, bounds))
    }

    @Test fun terminalAnimationPositionIsPublishedBeforeOwnershipEnds() {
        fastPan(); assertTrue(controller.start(home, geometry, true, bounds))
        driver.enqueue(PositionDriver.Frame(40, 15), PositionDriver.Frame(200, 75, last = true))
        val first = controller.advanceFrame()!!
        assertTrue(controller.isRunning)
        val last = controller.advanceFrame()!!
        assertFalse(controller.isRunning)
        assertNotEquals(first, last)
        assertEquals(ChartFlingCamera(home, geometry).at(200, 75), last)
        assertNull(controller.advanceFrame())
    }

    @Test fun newDownStopsAtLastPublishedCameraAndReversalStartsFromThatCamera() {
        fastPan(); assertTrue(controller.start(home, geometry, true, bounds))
        driver.enqueue(PositionDriver.Frame(75, 30), PositionDriver.Frame(400, 160, last = true))
        val shown = controller.advanceFrame()!!
        send(MotionEvent.ACTION_DOWN, 2000, p(700.0))
        assertFalse(controller.isRunning)
        assertEquals(shown, controller.stop())
        assertNull("A stale callback cannot publish the abandoned endpoint", controller.advanceFrame())
        send(MotionEvent.ACTION_MOVE, 2020, p(630.0))
        send(MotionEvent.ACTION_MOVE, 2040, p(560.0))
        send(MotionEvent.ACTION_UP, 2060, p(490.0))
        val release = transformChartViewport(shown, pan = ScreenPoint(-210.0, 0.0))
        assertTrue(controller.start(release, geometry, true, bounds))
        assertTrue(driver.velocity.x < 0)
        driver.enqueue(PositionDriver.Frame(-50, 0, last = true))
        assertEquals(ChartFlingCamera(release, geometry).at(-50, 0), controller.advanceFrame())
    }

    // Independent landmark projection checks the result in surface pixels, including rotation and scale.
    private fun project(view: ChartViewport, lat: Double, lon: Double, fitted: ChartSurfaceGeometry): ScreenPoint {
        val radius = 6378137.0 * .9996
        val east = radius * atan2(sin(Math.toRadians(lon - view.longitude)), cos(Math.toRadians(lon - view.longitude)))
        val north = radius * ln(tan(PI / 4 + Math.toRadians(lat) / 2) / tan(PI / 4 + Math.toRadians(view.latitude) / 2))
        return fitted.toSurface(ScreenPoint(view.width / 2.0 + view.metresToPixels * (east * cos(view.rotationRadians) + north * sin(view.rotationRadians)),
            view.height / 2.0 + view.metresToPixels * (east * sin(view.rotationRadians) - north * cos(view.rotationRadians))))
    }

    @Test fun diagonalFlingPreservesZoomAndRotationAndMovesLandmarksBySurfacePixels() {
        for (fitted in listOf(geometry, ChartSurfaceGeometry.fit(1200, 2000))) {
            val basis = home.copy(metresToPixels = .8, rotationRadians = -1.1,
                width = fitted.frameWidth, height = fitted.frameHeight)
            val current = ChartFlingCamera(basis, fitted).at(230, -120)
            assertEquals(basis.metresToPixels, current.metresToPixels, 0.0)
            assertEquals(basis.rotationRadians, current.rotationRadians, 1e-12)
            val before = project(basis, 42.361, -71.039, fitted)
            val after = project(current, 42.361, -71.039, fitted)
            assertEquals(230.0, after.x - before.x, 1e-6)
            assertEquals(-120.0, after.y - before.y, 1e-6)
        }
    }

    @Test fun droppedFramesAndDelayedOlderImageCannotChangeCanonicalFlingPath() {
        fastPan(); assertTrue(controller.start(home, geometry, true, bounds))
        driver.enqueue(PositionDriver.Frame(40, 15), PositionDriver.Frame(600, 225, last = true))
        val shown = controller.advanceFrame()!!
        // Content may still be from this older camera; the fling neither consumes nor adopts it.
        val oldImageCamera = transformChartViewport(home, pan = ScreenPoint(-150.0, 20.0), zoom = 1.2)
        val second = controller.advanceFrame()!!
        val fromOld = chartBufferAffine(oldImageCamera, second, geometry)!!.map(project(oldImageCamera, 42.361, -71.039, geometry))
        val expected = project(second, 42.361, -71.039, geometry)
        assertEquals(expected.x, fromOld.x, 1e-6); assertEquals(expected.y, fromOld.y, 1e-6)
        assertEquals(ChartFlingCamera(home, geometry).at(600, 225), second)
        assertNotEquals(shown, second)
    }

    @Test fun unavailableOrEmptyDataBoundsDoNotLaunchInertiaOrMoveTheReleaseCamera() {
        fastPan(); assertFalse(controller.start(home, geometry, true, null))
        assertEquals(0, driver.starts)
        fastPan(); assertFalse(controller.start(home, geometry, true, ChartNavigationBounds(2, emptyList())))
        assertEquals(0, driver.starts)
        fastPan(); assertFalse(controller.start(home, geometry, true,
            ChartNavigationBounds(3, listOf(ChartCellExtent("remote", 0.0, 1.0, 0.0, 1.0)))))
        assertEquals(0, driver.starts)
        assertNull(controller.advanceFrame())
    }

    @Test fun dataGenerationChangeStopsAtTheLastPublishedPositionWithoutJumpingToEndpoint() {
        fastPan(); assertTrue(controller.start(home, geometry, true, bounds))
        driver.enqueue(PositionDriver.Frame(40, 15), PositionDriver.Frame(600, 225, last = true))
        val shown = controller.advanceFrame(bounds)!!
        assertNull(controller.advanceFrame(bounds.copy(generation = 2)))
        assertFalse(controller.isRunning)
        assertEquals(shown, controller.stop())
        assertNull(controller.advanceFrame(bounds))
    }

    @Test fun firstCrossingPublishesClippedCameraAndStopsDriverEvenIfProposedFrameIsFarBeyondIt() {
        val limits = ChartNavigationBounds(4, listOf(ChartCellExtent("local", home.latitude - 1.0,
            home.latitude + 1.0, home.longitude - 1.0, home.longitude + 1.0)))
        fastPan(); assertTrue(controller.start(home, geometry, true, limits))
        driver.enqueue(PositionDriver.Frame(500000, 200000), PositionDriver.Frame(1000000, 400000, last = true))
        val edge = controller.advanceFrame(limits)!!
        assertFalse(controller.isRunning)
        assertTrue(driver.isFinished)
        val region=ChartCameraBounds.from(limits)!!
        assertEquals(edge,region.constrain(edge).effective)
        assertTrue(edge.longitude >= home.longitude - 1.0 && edge.latitude >= home.latitude - 1.0)
        assertNull(controller.advanceFrame(limits))
        assertEquals(edge, controller.stop())
    }
}
