package com.no5.helm.ui

import com.no5.helm.chart.ScreenPoint
import kotlin.math.*

internal enum class TouchAction { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }
internal data class TouchPoint(val id: Int, val x: Double, val y: Double)
internal data class TouchTransform(val anchor: ScreenPoint, val pan: ScreenPoint, val zoom: Double, val rotation: Double)
internal data class TouchResult(val transform: TouchTransform? = null, val tap: ScreenPoint? = null, val panIntent: Boolean = false)
internal enum class ChartTouchPolicy { LEGACY_SHARED, INDEPENDENT, PAN_ZOOM_LOCK }

/** Tracks an entire Android touch sequence by pointer ID, including its final positions. */
internal class ChartTouchTracker(
    private val touchSlop: Double,
    private val tapTimeout: Long,
    private val minimumSpan: Double = touchSlop * 2,
    private val policy: ChartTouchPolicy = ChartTouchPolicy.INDEPENDENT,
) {
    private var active = false
    private var navigating = false
    private var panIntentStarted = false
    private var hadMultiplePointers = false
    private var downTime = 0L
    private var previous = emptyMap<Int, TouchPoint>()
    private var origin = emptyMap<Int, TouchPoint>()
    private var zoomStarted = false
    private var rotationStarted = false
    private var rotationLocked = false
    private var accumulatedRotation = 0.0
    private var publishedPan = ScreenPoint(0.0, 0.0)
    private var publishedZoom = 1.0
    private var publishedRotation = 0.0

    fun reset() {
        active = false
        navigating = false
        panIntentStarted = false
        hadMultiplePointers = false
        previous = emptyMap()
        origin = emptyMap()
        rotationLocked = false
        rebase(emptyMap())
    }

    // A pointer/span change starts a new geometric reference, not a new Android touch sequence.
    private fun rebase(points: Map<Int, TouchPoint>) {
        origin = points
        zoomStarted = false
        rotationStarted = false
        accumulatedRotation = 0.0
        publishedPan = ScreenPoint(0.0, 0.0)
        publishedZoom = 1.0
        publishedRotation = 0.0
    }

    private fun center(points: Collection<TouchPoint>) = ScreenPoint(
        points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)

    private fun span(points: Collection<TouchPoint>): Double {
        if (points.size < 2) return 0.0
        val c = center(points)
        return 2 * sqrt(points.sumOf { (it.x - c.x).pow(2) + (it.y - c.y).pow(2) } / points.size)
    }

    private fun intentTransform(before: Map<Int, TouchPoint>, after: Map<Int, TouchPoint>): TouchTransform? {
        val raw = transform(before.values.toList(), after.values.toList()) ?: return null
        val start = origin.filterKeys { it in after }
        if (start.isEmpty()) return null
        val startCenter = center(start.values)
        val total = transform(start.values.toList(), after.values.toList())
            ?: TouchTransform(startCenter, ScreenPoint(0.0, 0.0), 1.0, 0.0)
        val startSpan = span(start.values)
        val beforeUsable = before.size > 1 && span(before.values) >= minimumSpan
        val afterUsable = after.size > 1 && span(after.values) >= minimumSpan
        val spanTransition = beforeUsable != afterUsable
        val usable = startSpan >= minimumSpan && beforeUsable && afterUsable
        if (usable) accumulatedRotation += raw.rotation

        val panReady = hypot(total.pan.x, total.pan.y) > touchSlop
        if (panReady) panIntentStarted = true
        val zoomReady = usable && abs(span(after.values) - startSpan) > touchSlop * 2
        val rotateReady = usable && abs(accumulatedRotation) * startSpan / 2 > touchSlop
        if (!navigating && policy == ChartTouchPolicy.PAN_ZOOM_LOCK &&
            (panReady || zoomReady) && !rotateReady) rotationLocked = true
        if (zoomReady) zoomStarted = true
        if (rotateReady && !rotationLocked) rotationStarted = true
        navigating = navigating || panReady || zoomStarted || rotationStarted

        // Publish differences of ONE filtered transform. Mixing component deltas with
        // different origins would move the geographic finger anchor when another intent begins.
        val wantedPan = if (navigating) total.pan else ScreenPoint(0.0, 0.0)
        val wantedZoom = if (spanTransition) publishedZoom else if (zoomStarted) total.zoom else 1.0
        val wantedRotation = if (spanTransition) publishedRotation else if (rotationStarted) accumulatedRotation else 0.0
        val result = TouchTransform(
            ScreenPoint(startCenter.x + publishedPan.x, startCenter.y + publishedPan.y),
            ScreenPoint(wantedPan.x - publishedPan.x, wantedPan.y - publishedPan.y),
            wantedZoom / publishedZoom, wantedRotation - publishedRotation)
        publishedPan = wantedPan; publishedZoom = wantedZoom; publishedRotation = wantedRotation
        // Crossing the minimum span must not apply a huge ratio from almost coincident fingers.
        if (spanTransition) rebase(after)
        return result.takeIf { it.pan.x != 0.0 || it.pan.y != 0.0 || it.zoom != 1.0 || it.rotation != 0.0 }
    }

    fun handle(action: TouchAction, points: List<TouchPoint>, time: Long, changedId: Int = -1): TouchResult {
        if (action == TouchAction.CANCEL) { reset(); return TouchResult() }
        val current = points.associateBy { it.id }
        if (action == TouchAction.DOWN) {
            reset()
            active = current.isNotEmpty()
            downTime = time
            previous = current
            origin = current
            hadMultiplePointers = current.size > 1
            return TouchResult()
        }
        if (!active) return TouchResult()
        hadMultiplePointers = hadMultiplePointers || current.size > 1

        // Compare stable IDs, never Android's pointer indices (which change after a finger lifts).
        val common = previous.keys.intersect(current.keys)
        val before = previous.filterKeys { it in common }
        val after = current.filterKeys { it in common }
        val wasNavigating = navigating
        val wasPanIntent = panIntentStarted
        if (policy == ChartTouchPolicy.LEGACY_SHARED && after.isNotEmpty()) {
            val start = origin.filterKeys { it in after }
            if (start.isNotEmpty()) {
                val from = center(start.values); val to = center(after.values)
                if (hypot(to.x - from.x, to.y - from.y) > touchSlop) panIntentStarted = true
            }
        }
        if (policy == ChartTouchPolicy.LEGACY_SHARED && !navigating && after.isNotEmpty()) {
            navigating = after.values.any { p ->
                origin[p.id]?.let { hypot(p.x - it.x, p.y - it.y) > touchSlop } ?: false
            }
        }
        val transform = if (policy != ChartTouchPolicy.LEGACY_SHARED && after.isNotEmpty()) {
            intentTransform(before, after)
        } else if (navigating && after.isNotEmpty()) {
            // On the first accepted move, include ALL motion since the real DOWN position.
            val baseline = if (wasNavigating) before else origin.filterKeys { it in common }
            transform(baseline.values.toList(), after.values.toList())
        } else null

        val tap = if (action == TouchAction.UP && !navigating && !hadMultiplePointers &&
            current.size == 1 && time - downTime in 0 until tapTimeout) {
            points.first().let { ScreenPoint(it.x, it.y) }
        } else null

        val panStartedNow = panIntentStarted && !wasPanIntent
        when (action) {
            TouchAction.UP -> reset()
            TouchAction.POINTER_DOWN, TouchAction.POINTER_UP -> {
                previous = if (action == TouchAction.POINTER_UP) current - changedId else current
                origin = previous
                rebase(previous)
            }
            else -> previous = current
        }
        return TouchResult(transform, tap, panStartedNow)
    }

    private fun transform(before: List<TouchPoint>, after: List<TouchPoint>): TouchTransform? {
        if (before.isEmpty() || before.size != after.size) return null
        fun center(points: List<TouchPoint>) = ScreenPoint(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)
        val oldCenter = center(before)
        val newCenter = center(after)
        val oldById = before.associateBy { it.id }
        var oldRadius2 = 0.0
        var newRadius2 = 0.0
        var cross = 0.0
        var dot = 0.0
        for (p in after) {
            val old = oldById.getValue(p.id)
            val ax = old.x - oldCenter.x; val ay = old.y - oldCenter.y
            val bx = p.x - newCenter.x; val by = p.y - newCenter.y
            oldRadius2 += ax * ax + ay * ay
            newRadius2 += bx * bx + by * by
            cross += ax * by - ay * bx
            dot += ax * bx + ay * by
        }
        val hasSpan = before.size > 1 && oldRadius2 >= 1.0 && newRadius2 >= 1.0
        val zoom = if (hasSpan) sqrt(newRadius2 / oldRadius2) else 1.0
        val rotation = if (hasSpan) atan2(cross, dot) else 0.0
        val pan = ScreenPoint(newCenter.x - oldCenter.x, newCenter.y - oldCenter.y)
        if (pan.x == 0.0 && pan.y == 0.0 && zoom == 1.0 && rotation == 0.0) return null
        return TouchTransform(oldCenter, pan, zoom, rotation)
    }
}
