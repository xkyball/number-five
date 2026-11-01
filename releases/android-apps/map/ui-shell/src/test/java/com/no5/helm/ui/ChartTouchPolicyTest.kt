package com.no5.helm.ui

import com.no5.helm.chart.ScreenPoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** The same raw coordinates exercise G0, G1 and G2; screen landmarks are an independent oracle. */
class ChartTouchPolicyTest {
    private fun p(id: Int, x: Double, y: Double = 500.0) = TouchPoint(id, x, y)
    private val initial = listOf(p(7, 400.0), p(19, 600.0))
    private class Trace(policy: ChartTouchPolicy, minimumSpan: Double = 80.0) {
        val tracker = ChartTouchTracker(8.0, 500, minimumSpan, policy)
        val moves = mutableListOf<TouchTransform>()
        val taps = mutableListOf<ScreenPoint>()
        private var time = 1000L
        fun send(action: TouchAction, points: List<TouchPoint>, changedId: Int = -1): TouchResult {
            time += 16
            return tracker.handle(action, points, time, changedId).also {
                it.transform?.let(moves::add); it.tap?.let(taps::add)
            }
        }
        fun down(points: List<TouchPoint>) {
            send(TouchAction.DOWN, points.take(1))
            if (points.size > 1) send(TouchAction.POINTER_DOWN, points, points[1].id)
        }
    }
    private fun transformed(points: List<TouchPoint>, pan: ScreenPoint = ScreenPoint(0.0, 0.0),
        zoom: Double = 1.0, radians: Double = 0.0): List<TouchPoint> {
        val cx = points.sumOf { it.x } / points.size; val cy = points.sumOf { it.y } / points.size
        return points.map { p ->
            val dx = p.x - cx; val dy = p.y - cy
            TouchPoint(p.id, cx + pan.x + zoom * (dx * cos(radians) - dy * sin(radians)),
                cy + pan.y + zoom * (dx * sin(radians) + dy * cos(radians)))
        }
    }
    private fun screen(point: ScreenPoint, moves: List<TouchTransform>): ScreenPoint = moves.fold(point) { old, move ->
        val x = old.x - move.anchor.x; val y = old.y - move.anchor.y
        ScreenPoint(move.anchor.x + move.pan.x + move.zoom * (x * cos(move.rotation) - y * sin(move.rotation)),
            move.anchor.y + move.pan.y + move.zoom * (x * sin(move.rotation) + y * cos(move.rotation)))
    }
    private fun zoom(trace: Trace) = trace.moves.fold(1.0) { value, move -> value * move.zoom }
    private fun rotation(trace: Trace) = trace.moves.sumOf { it.rotation }
    private fun row(name: String, policy: ChartTouchPolicy, trace: Trace) {
        println("GESTURE_COMPARE,$name,$policy,${trace.moves.size},${zoom(trace)},${Math.toDegrees(rotation(trace))},${trace.taps.size}")
    }

    @Test fun pinchWithSmallAngleErrorDoesNotRotateTheIndependentOrLockedCandidate() {
        val next = listOf(p(7, 380.0, 497.0), p(19, 620.0, 503.0))
        for (policy in ChartTouchPolicy.entries) {
            val trace = Trace(policy); trace.down(initial); trace.send(TouchAction.MOVE, next)
            row("pinch_angle_noise", policy, trace)
            assertEquals(hypot(240.0, 6.0) / 200, zoom(trace), 1e-12)
            if (policy == ChartTouchPolicy.LEGACY_SHARED) assertTrue(abs(rotation(trace)) > Math.toRadians(1.0))
            else assertEquals(0.0, rotation(trace), 0.0)
        }
    }

    @Test fun rotationWithSmallSpanErrorDoesNotZoomTheIndependentOrLockedCandidate() {
        val next = transformed(initial, zoom = 1.02, radians = PI / 2)
        for (policy in ChartTouchPolicy.entries) {
            val trace = Trace(policy); trace.down(initial); trace.send(TouchAction.MOVE, next)
            row("rotate_span_noise", policy, trace)
            assertEquals(PI / 2, rotation(trace), 1e-12)
            assertEquals(if (policy == ChartTouchPolicy.LEGACY_SHARED) 1.02 else 1.0, zoom(trace), 1e-12)
        }
    }

    @Test fun twoFingerPanDoesNotAutomaticallyEnableScaleAndRotation() {
        val next = transformed(initial, ScreenPoint(30.0, 20.0), 1.02, Math.toRadians(1.0))
        for (policy in ChartTouchPolicy.entries) {
            val trace = Trace(policy); trace.down(initial); trace.send(TouchAction.MOVE, next)
            row("pan_component_noise", policy, trace)
            val center = screen(ScreenPoint(500.0, 500.0), trace.moves)
            assertEquals(530.0, center.x, 1e-9); assertEquals(520.0, center.y, 1e-9)
            if (policy != ChartTouchPolicy.LEGACY_SHARED) {
                assertEquals(1.0, zoom(trace), 0.0); assertEquals(0.0, rotation(trace), 0.0)
            }
        }
    }

    @Test fun allCandidatesAcceptDeliberateCombinedIntentBeginningTogether() {
        val next = transformed(initial, ScreenPoint(40.0, -30.0), 1.3, .4)
        for (policy in ChartTouchPolicy.entries) {
            val trace = Trace(policy); trace.down(initial); trace.send(TouchAction.MOVE, next)
            row("combined_at_begin", policy, trace)
            for (i in initial.indices) {
                val landmark = screen(ScreenPoint(initial[i].x, initial[i].y), trace.moves)
                assertEquals(next[i].x, landmark.x, 1e-9); assertEquals(next[i].y, landmark.y, 1e-9)
            }
        }
    }

    @Test fun independentCandidateAllowsPanThenDeliberateRotationWhileLockRequiresANewSequence() {
        for (policy in ChartTouchPolicy.entries) {
            val trace = Trace(policy); val single = listOf(p(7, 400.0))
            trace.down(single); trace.send(TouchAction.MOVE, listOf(p(7, 420.0)))
            val two = listOf(p(7, 420.0), p(19, 620.0))
            trace.send(TouchAction.POINTER_DOWN, two, 19)
            trace.send(TouchAction.MOVE, transformed(two, radians = .3))
            row("pan_then_rotate", policy, trace)
            assertEquals(if (policy == ChartTouchPolicy.PAN_ZOOM_LOCK) 0.0 else .3, rotation(trace), 1e-12)
        }
    }

    @Test fun addingRemovingAndReindexingPointersDoesNotInventAMotion() {
        for (policy in ChartTouchPolicy.entries) {
            val trace = Trace(policy); trace.down(initial)
            val moved = transformed(initial, ScreenPoint(30.0, 0.0))
            trace.send(TouchAction.MOVE, moved.reversed())
            val count = trace.moves.size
            trace.send(TouchAction.POINTER_UP, moved, 7)
            trace.send(TouchAction.MOVE, listOf(moved[1]))
            assertEquals(count, trace.moves.size)
            trace.send(TouchAction.MOVE, listOf(moved[1].copy(x = moved[1].x + 4)))
            assertEquals(4.0, trace.moves.last().pan.x, 1e-12)
            trace.send(TouchAction.UP, listOf(moved[1].copy(x = moved[1].x + 4)))
            assertTrue(trace.taps.isEmpty())
            row("pointer_reindex_and_lift", policy, trace)
        }
    }

    @Test fun almostCoincidentPointersCannotCauseAHugeScaleOrAngleAndCrossingMinimumSpanRebases() {
        for (policy in listOf(ChartTouchPolicy.INDEPENDENT, ChartTouchPolicy.PAN_ZOOM_LOCK)) {
            val trace = Trace(policy); trace.down(listOf(p(7, 400.0)))
            trace.send(TouchAction.MOVE, listOf(p(7, 420.0)))
            trace.send(TouchAction.POINTER_DOWN, listOf(p(7, 420.0), p(19, 422.0)), 19)
            trace.send(TouchAction.MOVE, listOf(p(7, 420.0), p(19, 422.0, 501.0)))
            assertEquals(1.0, zoom(trace), 0.0); assertEquals(0.0, rotation(trace), 0.0)
            val usable = listOf(p(7, 400.0), p(19, 600.0))
            trace.send(TouchAction.MOVE, usable)
            assertEquals(1.0, zoom(trace), 0.0); assertEquals(0.0, rotation(trace), 0.0)
            trace.send(TouchAction.MOVE, transformed(usable, zoom = 1.3))
            assertEquals(1.3, zoom(trace), 1e-12)
            row("minimum_span_rebase", policy, trace)
        }
    }

    @Test fun stationaryNoiseDoesNotBeginAGesture() {
        for (policy in ChartTouchPolicy.entries) {
            val trace = Trace(policy); trace.down(initial)
            repeat(30) { i -> trace.send(TouchAction.MOVE,
                transformed(initial, ScreenPoint(if (i % 2 == 0) 1.0 else -1.0, .5), 1.005, .005)) }
            trace.send(TouchAction.UP, initial.take(1))
            assertTrue(trace.moves.isEmpty()); assertTrue(trace.taps.isEmpty())
            row("stationary_noise", policy, trace)
        }
    }

    @Test fun slowScaleAndLateRotationUseOneCoherentReference() {
        for (policy in ChartTouchPolicy.entries) {
            val trace = Trace(policy); trace.down(initial)
            for (i in 1..20) trace.send(TouchAction.MOVE,
                transformed(initial, ScreenPoint(i.toDouble(), i / 2.0), 1 + i / 100.0, .005))
            assertEquals(1.2, zoom(trace), 1e-12)
            val next = transformed(initial, ScreenPoint(30.0, 15.0), 1.3, .4)
            trace.send(TouchAction.MOVE, next)
            row("slow_pinch_then_rotate", policy, trace)
            if (policy != ChartTouchPolicy.PAN_ZOOM_LOCK) for (i in initial.indices) {
                val landmark = screen(ScreenPoint(initial[i].x, initial[i].y), trace.moves)
                assertEquals(next[i].x, landmark.x, 1e-9); assertEquals(next[i].y, landmark.y, 1e-9)
            }
            else assertEquals(0.0, rotation(trace), 0.0)
        }
    }

    @Test fun rotationDoesNotWrapOrJumpAtPi() {
        for (policy in ChartTouchPolicy.entries) {
            val trace = Trace(policy); trace.down(initial)
            for (i in 1..20) trace.send(TouchAction.MOVE, transformed(initial, radians = i * .3))
            assertEquals(6.0, rotation(trace), 1e-12)
            row("rotation_cross_pi", policy, trace)
        }
    }

    @Test fun excursionAndReturnCannotBecomeATapAndCancelCannotApplyItsCoordinates() {
        for (policy in ChartTouchPolicy.entries) {
            val trace = Trace(policy); val single = initial.take(1)
            trace.down(single)
            trace.send(TouchAction.MOVE, listOf(p(7, 550.0)))
            trace.send(TouchAction.MOVE, single)
            trace.send(TouchAction.UP, single)
            assertTrue(trace.taps.isEmpty())
            val restored = screen(ScreenPoint(400.0, 500.0), trace.moves)
            assertEquals(400.0, restored.x, 1e-12)
            trace.down(single); val count = trace.moves.size
            trace.send(TouchAction.CANCEL, listOf(p(7, 900.0)))
            trace.send(TouchAction.MOVE, listOf(p(7, 950.0)))
            assertEquals(count, trace.moves.size)
            trace.down(single); trace.send(TouchAction.UP, single)
            assertEquals(1, trace.taps.size)
            row("return_up_cancel_new_down", policy, trace)
        }
    }
}
