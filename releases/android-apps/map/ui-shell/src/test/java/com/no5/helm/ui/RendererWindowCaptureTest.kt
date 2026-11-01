package com.no5.helm.ui

import android.graphics.SurfaceTexture
import android.view.FrameMetrics
import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.FrameMetricsBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RendererWindowCaptureTest {
    private class Sink : RendererUiSink {
        val values = mutableListOf<Triple<RendererUiPhase, Long, Long>>()
        var frames = 0; var dropped = 0; var updates = 0
        override fun record(phase: RendererUiPhase, wallNanos: Long, cpuNanos: Long) { values += Triple(phase, wallNanos, cpuNanos) }
        override fun windowFrame(firstDraw: Boolean, droppedReports: Int) { frames++; dropped += droppedReports }
        override fun windowState(state: RendererWindowState) = Unit
        override fun textureUpdated() { updates++ }
    }
    private fun metrics() = FrameMetricsBuilder().setMetric(FrameMetrics.DRAW_DURATION, 2000000).setMetric(FrameMetrics.LAYOUT_MEASURE_DURATION, 3000000).build()
    @Test fun actualFrameMetricsAreReadDuringCallbackWithoutWindowCpuOrApi31Gpu() {
        val sink = Sink(); recordWindowMetrics(sink, metrics(), 2)
        assertEquals(1, sink.frames); assertEquals(2, sink.dropped)
        assertEquals(2000000L, sink.values.first { it.first == RendererUiPhase.WINDOW_DRAW }.second)
        assertEquals(3000000L, sink.values.first { it.first == RendererUiPhase.WINDOW_LAYOUT_MEASURE }.second)
        assertEquals(-1L, sink.values.first { it.first == RendererUiPhase.WINDOW_DRAW }.third)
        assertEquals(-1L, sink.values.first { it.first == RendererUiPhase.WINDOW_GPU }.second)
    }
    @Test fun listenerStopAndExpiredCapturePreventCallbacksAfterLifecycleRemoval() {
        val activity = Robolectric.buildActivity(No5ImmersiveActivity::class.java).apply { get().setTheme(android.R.style.Theme_Material_NoActionBar); setup() }
        val sink = Sink(); var active = true
        val recorder = RendererWindowCapture(activity.get().window, sink) { active }
        try {
            activity.get().lifecycle.addObserver(recorder); recorder.start()
            shadowOf(activity.get().window).reportOnFrameMetricsAvailable(metrics())
            assertEquals(1, sink.frames)
            activity.pause().stop(); recorder.stop()
            shadowOf(activity.get().window).reportOnFrameMetricsAvailable(metrics())
            assertEquals(1, sink.frames)
            active = false; activity.start().resume(); recorder.start()
            shadowOf(activity.get().window).reportOnFrameMetricsAvailable(metrics())
            assertEquals(1, sink.frames)
        } finally { activity.get().lifecycle.removeObserver(recorder); recorder.stop(); activity.pause().stop().destroy() }
    }
    @Test fun textureCallbacksHaveIntervalsWithinOneCaptureAndNeverAcrossSessions() {
        val view = ChartSurfaceView(RuntimeEnvironment.getApplication())
        val first = Sink(); var current: RendererUiSink? = first; view.captureSink = { current }
        val texture = SurfaceTexture(0)
        try {
            view.surfaceTextureListener!!.onSurfaceTextureAvailable(texture,1000,600)
            view.surfaceTextureListener!!.onSurfaceTextureUpdated(texture)
            view.surfaceTextureListener!!.onSurfaceTextureUpdated(texture)
            assertEquals(2, first.updates); assertEquals(1, first.values.count { it.first == RendererUiPhase.TEXTURE_UPDATE_INTERVAL })
            val second = Sink(); current = second
            view.surfaceTextureListener!!.onSurfaceTextureUpdated(texture)
            assertEquals(1, second.updates); assertTrue(second.values.isEmpty())
            current = null; view.surfaceTextureListener!!.onSurfaceTextureUpdated(texture)
            current = second; view.surfaceTextureListener!!.onSurfaceTextureUpdated(texture)
            assertTrue(second.values.isEmpty())
        } finally { view.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture) }
    }
}
