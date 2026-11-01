package com.no5.helm.ui

import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Controlled API29 View/input observations, never hardware display proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RendererLiveInputObservationTest {
    private class Sink : RendererLiveInputSink {
        override var replayRecipe = RendererReplayRecipe.LEGACY
        val markers = mutableListOf<ScreenPoint>()
        override fun markerTarget(point: ScreenPoint) { markers += point }
        data class Input(val id: Long, val history: Int, val origin: RendererInputOrigin, val ids: List<Int>, val action: Int)
        val inputs = mutableListOf<Input>(); val cameraInputs = mutableListOf<Long>(); val transforms = mutableListOf<Pair<Long, List<Float>>>()
        val replayStates = mutableListOf<String>(); var context = 0L; var claimed = false
        override fun input(uptimeMs: Long, beforeMonoNs: Long, afterMonoNs: Long, dispatchUptimeMs: Long, action: Int,
            changedId: Int, historyIndex: Int, gesture: Long, pointerIds: IntArray, positions: FloatArray, origin: RendererInputOrigin): Long {
            val id = inputs.size + 1L; inputs += Input(id, historyIndex, origin, pointerIds.toList(), action); return id
        }
        override fun <T> withInput(id: Long, block: () -> T): T { val previous = context; context = id; try { return block() } finally { context = previous } }
        override fun currentInput() = context
        override fun camera(inputId: Long, revision: Long, generation: Long, viewport: ChartViewport, applied: Boolean) { cameraInputs += inputId }
        override fun disposition(inputId: Long, reason: String, replacement: Long) = Unit
        override fun buffer(buffer: ChartBufferSnapshot, completed: Boolean) = Unit
        override fun texture(tag: Long, knownBasis: Boolean, phase: String) = Unit
        override fun transform(base: ChartBufferSnapshot?, targetRevision: Long, transformRevision: Long,
            matrix: FloatArray, beforeMonoNs: Long, afterMonoNs: Long) { transforms += context to matrix.toList() }
        override fun replay(state: String, delivered: Int, maxLatenessMs: Long) { replayStates += state }
        override fun claimReplay(): Boolean { if (claimed) return false; claimed = true; return true }
    }
    private lateinit var view: ChartSurfaceView
    private lateinit var sink: Sink
    private var revision = 0L
    private val home = ChartViewport(42.36, -71.04, .15, width = 1000, height = 600)
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        sink = Sink()
        view = ChartSurfaceView(context)
        val parent = FrameLayout(context).apply { addView(view) }
        parent.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 1000, 600)
        view.viewport = home; view.inputSink = { sink }; view.onTouchBegin = { true }
        view.bufferTransformMode = ChartBufferTransformMode.TRANSFORM_WITH_RENDER; view.bufferTransformGeneration = 4
        view.cameraRevision = { revision }
        view.onTouchViewport = { camera -> sink.camera(sink.currentInput(), ++revision, 4, camera, false) }
        view.consumeTransformBuffer(ChartBufferSnapshot(123, 4, 0, ChartFrame(1, "base", ChartSurfaceGeometry.fit(1000, 600), home)))
    }
    private fun send(action: Int, at: Long, x: Float): MotionEvent = MotionEvent.obtain(1000, at, action, x, 300f, 0)
    @Test fun fixedReplayTraversesPanPinchAndRotationAndIsClearlyLabelledInjected() {
        view.touchReplay.sync(true, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(listOf("STARTED", "COMPLETED"), sink.replayStates)
        assertEquals(190, sink.inputs.size)
        assertTrue(sink.inputs.all { it.origin == RendererInputOrigin.VIEW_INJECTED })
        assertTrue(sink.inputs.any { it.ids == listOf(7, 19) })
        assertTrue(sink.cameraInputs.size > 100)
        assertTrue(sink.transforms.any { (_, m) -> kotlin.math.abs(m[1]) > .01f }) // rotation actually set
        assertEquals(home.metresToPixels, view.viewport.metresToPixels, 1e-8)
        assertEquals(home.rotationRadians, view.viewport.rotationRadians, 1e-8)
        assertFalse(view.isTouchActive)
        val before = sink.inputs.size; view.touchReplay.sync(true, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(before, sink.inputs.size) // same run can never replay twice
    }
    @Test fun batchedExcursionAndReturnRetainSeparateInputIdentities() {
        send(MotionEvent.ACTION_DOWN, 1000, 500f).let { try { view.dispatchTouchEvent(it) } finally { it.recycle() } }
        send(MotionEvent.ACTION_MOVE, 1010, 600f).let { move ->
            move.addBatch(1020, 500f, 300f, 1f, 1f, 0)
            try { view.dispatchTouchEvent(move) } finally { move.recycle() }
        }
        assertEquals(listOf(-1, 0, -1), sink.inputs.map { it.history })
        assertEquals(listOf(2L, 3L), sink.cameraInputs)
        assertTrue(sink.transforms.any { it.first == 2L }); assertTrue(sink.transforms.any { it.first == 3L })
        assertEquals(0L, sink.context)
    }
    @Test fun continuousRecipeHasOneOwnedTouchSequenceAndMarkerIgnoresReplacementBasis() {
        sink.replayRecipe = RendererReplayRecipe.P0_CONTINUOUS
        val initial = ChartBufferSnapshot(123, 4, 0, ChartFrame(1, "base", ChartSurfaceGeometry.fit(1000, 600), home))
        view.consumeTransformBuffer(initial)
        var begins = 0; var ends = 0
        view.onTouchBegin = { begins++; true }; view.onTouchFinish = { ends++ }
        view.touchReplay.sync(true, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1800))
        assertTrue(view.isTouchActive)
        val markerBefore = sink.markers.last()
        view.consumeTransformBuffer(initial.copy(stamp = 124, cameraRevision = revision,
            frame = initial.frame.copy(viewport = view.viewport)))
        assertEquals(markerBefore.x, sink.markers.last().x, .001)
        assertEquals(markerBefore.y, sink.markers.last().y, .001)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        assertEquals(listOf("STARTED", "COMPLETED"), sink.replayStates)
        assertEquals(147, sink.inputs.size)
        assertEquals(1, sink.inputs.count { it.action == MotionEvent.ACTION_DOWN })
        assertEquals(1, sink.inputs.count { it.action == MotionEvent.ACTION_UP })
        assertEquals(1, begins); assertEquals(1, ends); assertFalse(view.isTouchActive)
        assertTrue(sink.inputs.all { it.origin == RendererInputOrigin.VIEW_INJECTED })
        assertEquals(.65, view.viewport.metresToPixels / home.metresToPixels, .015)
        assertEquals(Math.PI / 6, view.viewport.rotationRadians, .02)
        assertTrue(sink.markers.last().x.isFinite())
        var shown: ScreenPoint? = markerBefore
        view.onDiagnosticMarker = { shown = it }
        view.bufferTransformMode = ChartBufferTransformMode.OFF
        assertNull(shown)
    }
    @Test fun externalInputAndLifecycleStopCannotLeaveScheduledReplayOrHeldGesture() {
        view.touchReplay.sync(true, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(view.isTouchActive)
        send(MotionEvent.ACTION_DOWN, 2000, 500f).let { try { view.dispatchTouchEvent(it) } finally { it.recycle() } }
        assertTrue(sink.replayStates.contains("FAILED_EXTERNAL_INPUT_INTERFERENCE"))
        val count = sink.inputs.size; view.touchReplay.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(count, sink.inputs.size)
        assertTrue(sink.inputs.last().origin == RendererInputOrigin.PLATFORM_DISPATCH)
    }
}
