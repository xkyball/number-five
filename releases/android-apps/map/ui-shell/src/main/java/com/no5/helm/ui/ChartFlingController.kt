package com.no5.helm.ui

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.no5.helm.chart.ChartSurfaceGeometry
import com.no5.helm.chart.ChartViewport
import com.no5.helm.chart.ChartCameraBounds
import com.no5.helm.chart.ChartNavigationBounds
import com.no5.helm.chart.ScreenPoint
import kotlin.math.abs
import kotlin.math.hypot

/** The API29 platform curve produces absolute surface-pixel positions, never camera deltas. */
internal interface ChartFlingDriver {
    val isFinished: Boolean
    val currentX: Int
    val currentY: Int
    fun fling(velocityX: Int, velocityY: Int)
    fun computeScrollOffset(): Boolean
    fun forceFinished()
}

private class AndroidChartFlingDriver(context: Context) : ChartFlingDriver {
    private val scroller = OverScroller(context)
    override val isFinished get() = scroller.isFinished
    override val currentX get() = scroller.currX
    override val currentY get() = scroller.currY
    override fun fling(velocityX: Int, velocityY: Int) {
        // Geographic clipping happens in the controller; these bounds are not cached-image edges.
        scroller.fling(0, 0, velocityX, velocityY, Int.MIN_VALUE, Int.MAX_VALUE,
            Int.MIN_VALUE, Int.MAX_VALUE)
    }
    override fun computeScrollOffset() = scroller.computeScrollOffset()
    override fun forceFinished() = scroller.forceFinished(true)
}

/** An immutable UP-camera keeps missed animation frames and replacement images from changing the path. */
internal class ChartFlingCamera(private val basis: ChartViewport, private val geometry: ChartSurfaceGeometry) {
    fun at(surfaceX: Int, surfaceY: Int): ChartViewport = at(surfaceX.toDouble(), surfaceY.toDouble())
    fun at(surfaceX: Double, surfaceY: Double): ChartViewport = transformChartViewport(basis,
        pan = ScreenPoint(surfaceX / geometry.scale, surfaceY / geometry.scale))
}

/**
 * Collects one accepted, single-pointer pan and drives its release momentum on the UI animation clock.
 * The caller applies the UP position first, then calls [start], and retains camera ownership until
 * [isRunning] becomes false. Physical finger contact is independent from that ownership.
 *
 * Only MotionEvent timestamps/pointer IDs enter VelocityTracker. No renderer completion or image
 * basis enters the velocity estimate or the fling camera; delayed content cannot pull the camera back.
 */
internal class ChartFlingController(
    context: Context,
    private val driver: ChartFlingDriver = AndroidChartFlingDriver(context),
    private val touchSlop: Double = ViewConfiguration.get(context).scaledTouchSlop.toDouble(),
    private val minimumVelocity: Int = ViewConfiguration.get(context).scaledMinimumFlingVelocity,
    private val maximumVelocity: Int = ViewConfiguration.get(context).scaledMaximumFlingVelocity,
) {
    private var velocityTracker: VelocityTracker? = null
    private var pointerId = -1
    private var eligible = false
    private var origin = ScreenPoint(0.0, 0.0)
    private var maximumDistance = 0.0
    private var previousEventTime = Long.MIN_VALUE
    private var releaseVelocity: ScreenPoint? = null
    private var flingCamera: ChartFlingCamera? = null
    private var boundary: ChartCameraBounds? = null
    private var navigationBounds: ChartNavigationBounds? = null
    private var running = false
    private var publishedCamera: ChartViewport? = null

    val isRunning: Boolean get() = running

    init {
        require(touchSlop.isFinite() && touchSlop >= 0)
        require(minimumVelocity > 0 && maximumVelocity >= minimumVelocity)
    }

    /** Call only for the chart's accepted stream, once per original MotionEvent (history included). */
    fun observeAcceptedTouch(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            stop()
            clearGesture()
            eligible = event.pointerCount == 1
            if (eligible) {
                pointerId = event.getPointerId(0)
                origin = ScreenPoint(event.getX(0).toDouble(), event.getY(0).toDouble())
                velocityTracker = VelocityTracker.obtain()
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            stop()
            clearGesture()
            return
        }
        if (velocityTracker == null) return
        if (event.pointerCount != 1 || event.getPointerId(0) != pointerId ||
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.eventTime < previousEventTime) {
            eligible = false
        }
        previousEventTime = event.eventTime
        if (event.pointerCount == 1 && event.getPointerId(0) == pointerId) {
            for (history in 0 until event.historySize) {
                recordDistance(event.getHistoricalX(0, history).toDouble(), event.getHistoricalY(0, history).toDouble())
            }
            recordDistance(event.getX(0).toDouble(), event.getY(0).toDouble())
        }
        velocityTracker?.addMovement(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val tracker = requireNotNull(velocityTracker)
            if (eligible && maximumDistance > touchSlop) {
                tracker.computeCurrentVelocity(1000, maximumVelocity.toFloat())
                val x = tracker.getXVelocity(pointerId).toDouble()
                val y = tracker.getYVelocity(pointerId).toDouble()
                if (x.isFinite() && y.isFinite() &&
                    maxOf(abs(x), abs(y)) >= minimumVelocity &&
                    maxOf(abs(x), abs(y)) <= maximumVelocity.toDouble()) {
                    releaseVelocity = ScreenPoint(x, y)
                }
            }
            tracker.recycle()
            velocityTracker = null
            eligible = false
        }
    }

    private fun recordDistance(x: Double, y: Double) {
        if (!x.isFinite() || !y.isFinite()) { eligible = false; return }
        maximumDistance = maxOf(maximumDistance, hypot(x - origin.x, y - origin.y))
    }

    /** Start exactly once, after the final UP camera has been applied; no zoom/rotation inertia. */
    fun start(viewport: ChartViewport, geometry: ChartSurfaceGeometry, navigationAccepted: Boolean,
        bounds: ChartNavigationBounds? = null): Boolean {
        val velocity = releaseVelocity
        releaseVelocity = null
        if (!navigationAccepted || velocity == null || bounds == null || viewport.width != geometry.frameWidth ||
            viewport.height != geometry.frameHeight || !viewport.latitude.isFinite() ||
            viewport.latitude !in -85.0..85.0 || !viewport.longitude.isFinite() ||
            !viewport.metresToPixels.isFinite() || viewport.metresToPixels !in 0.00001..10.0 ||
            !viewport.rotationRadians.isFinite()) return false
        val snapshot = bounds.copy(cells = bounds.cells.toList())
        val limit = ChartCameraBounds.from(snapshot) ?: return false
        if (limit.constrain(viewport).effective != viewport) return false // Never snap an invalid release pose.
        flingCamera = ChartFlingCamera(viewport, geometry)
        boundary = limit
        navigationBounds = snapshot
        publishedCamera = viewport
        driver.fling(velocity.x.toInt(), velocity.y.toInt())
        running = !driver.isFinished
        return running
    }

    /** At most one canonical camera per animation callback, even when several frames were missed. */
    fun advanceFrame(bounds: ChartNavigationBounds? = navigationBounds): ChartViewport? {
        if (!running) return null
        if (bounds == null || bounds != navigationBounds) {
            stop() // Data changed or became unknown: retain the last published camera.
            return null
        }
        val hasPosition = driver.computeScrollOffset()
        val wanted = if (hasPosition) requireNotNull(flingCamera).at(driver.currentX, driver.currentY) else null
        val camera = wanted?.let { requireNotNull(boundary).constrain(it, publishedCamera ?: it).effective }
        val hitBoundary = wanted != null && camera != wanted
        val changed = camera?.takeIf { it != publishedCamera }
        if (camera != null) publishedCamera = camera
        if (!hasPosition || driver.isFinished || hitBoundary) {
            driver.forceFinished()
            running = false
            flingCamera = null
            boundary = null
            navigationBounds = null
        }
        return changed
    }

    /** Stop at the last published position, never OverScroller's final endpoint. */
    fun stop(): ChartViewport? {
        driver.forceFinished()
        running = false
        flingCamera = null
        boundary = null
        navigationBounds = null
        releaseVelocity = null
        return publishedCamera
    }

    /** Lifecycle/blocked-region cancellation also releases the native VelocityTracker. */
    fun cancel() {
        stop()
        clearGesture()
    }

    private fun clearGesture() {
        velocityTracker?.recycle()
        velocityTracker = null
        pointerId = -1
        eligible = false
        maximumDistance = 0.0
        previousEventTime = Long.MIN_VALUE
        releaseVelocity = null
    }
}
