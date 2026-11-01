package com.no5.helm.ui

import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.no5.helm.chart.*

/** One Activity window, only while this capture and lifecycle are active. */
internal class RendererWindowCapture(
    private val window: Window,
    private val sink: RendererUiSink,
    private val stillActive: () -> Boolean,
) : DefaultLifecycleObserver {
    private var registered = false
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        recordWindowMetrics(sink, metrics, dropped)
    }
    fun start() {
        if (registered || !stillActive()) return
        try {
            window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
            registered = true; sink.windowState(RendererWindowState.REGISTERED)
        } catch (_: RuntimeException) { sink.windowState(RendererWindowState.UNAVAILABLE) }
    }
    fun stop() {
        if (!registered) return
        window.removeOnFrameMetricsAvailableListener(listener); registered = false
    }
    override fun onStart(owner: LifecycleOwner) = start()
    override fun onStop(owner: LifecycleOwner) = stop()
    override fun onDestroy(owner: LifecycleOwner) = stop()
}

/** FrameMetrics is reused by Android; read numeric values during this callback only. */
internal fun recordWindowMetrics(sink: RendererUiSink, metrics: FrameMetrics, dropped: Int) {
    val started = System.nanoTime(); val cpuStarted = Debug.threadCpuTimeNanos()
    sink.windowFrame(metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L, dropped)
    fun value(phase: RendererUiPhase, id: Int) = sink.record(phase, metrics.getMetric(id))
    value(RendererUiPhase.WINDOW_UNKNOWN_DELAY, FrameMetrics.UNKNOWN_DELAY_DURATION)
    value(RendererUiPhase.WINDOW_INPUT, FrameMetrics.INPUT_HANDLING_DURATION)
    value(RendererUiPhase.WINDOW_ANIMATION, FrameMetrics.ANIMATION_DURATION)
    value(RendererUiPhase.WINDOW_LAYOUT_MEASURE, FrameMetrics.LAYOUT_MEASURE_DURATION)
    value(RendererUiPhase.WINDOW_DRAW, FrameMetrics.DRAW_DURATION)
    value(RendererUiPhase.WINDOW_SYNC, FrameMetrics.SYNC_DURATION)
    value(RendererUiPhase.WINDOW_COMMAND_ISSUE, FrameMetrics.COMMAND_ISSUE_DURATION)
    value(RendererUiPhase.WINDOW_SWAP, FrameMetrics.SWAP_BUFFERS_DURATION)
    value(RendererUiPhase.WINDOW_TOTAL, FrameMetrics.TOTAL_DURATION)
    if (Build.VERSION.SDK_INT >= 31) value(RendererUiPhase.WINDOW_GPU, FrameMetrics.GPU_DURATION)
    else sink.record(RendererUiPhase.WINDOW_GPU, -1)
    val cpuEnd = Debug.threadCpuTimeNanos(); val ended = System.nanoTime()
    sink.record(RendererUiPhase.CAPTURE_PROBE, ended - started,
        if (cpuStarted >= 0 && cpuEnd >= cpuStarted) cpuEnd - cpuStarted else -1)
}

internal inline fun <T> measureChartUi(engine: ChartEngine, phase: RendererUiPhase, block: () -> T): T {
    val sink = engine.rendererUiSink() ?: return block()
    val start = System.nanoTime(); val cpu = Debug.threadCpuTimeNanos()
    try { return block() }
    finally {
        val cpuEnd = Debug.threadCpuTimeNanos(); val end = System.nanoTime()
        sink.record(phase, end - start, if (cpu >= 0 && cpuEnd >= cpu) cpuEnd - cpu else -1)
    }
}
