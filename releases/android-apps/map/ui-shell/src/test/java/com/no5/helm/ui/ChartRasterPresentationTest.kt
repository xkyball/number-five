package com.no5.helm.ui

import android.graphics.Matrix
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Controlled matrix/acquisition fixture; no EGL, BufferQueue or physical-display claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ChartRasterPresentationTest {
    @Test fun controlAndReservePromotionKeepSameVisibleCameraAndOnePixelScale() {
        val visible = ChartSurfaceGeometry.fit(1000, 600)
        val raster = ChartRasterGeometry.reserve(visible)
        val camera = ChartViewport(42.36, -71.04, .15, width = 1000, height = 600)
        val chart = ChartSurfaceView(RuntimeEnvironment.getApplication())
        val presentation = ChartPresentationView(chart.context, chart)
        presentation.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        presentation.layout(0, 0, 1000, 600)
        chart.viewport = camera; chart.bufferTransformGeneration = 7
        val packets = mutableMapOf<Long, ChartBufferSnapshot>(); chart.onTaggedBuffer = packets::get
        fun texture(slot: Int): TextureView =
            (0 until presentation.childCount).map { presentation.getChildAt(it) }.filterIsInstance<FrameLayout>()
                .flatMap { wrapper -> (0 until wrapper.childCount).map { wrapper.getChildAt(it) } }
                .filterIsInstance<TextureView>().first { (it === chart) == (slot == 0) }
        fun map(slot: Int, x: Float, y: Float): FloatArray = floatArrayOf(x, y).also {
            texture(slot).getTransform(Matrix()).mapPoints(it)
        }
        for ((slot, mode) in listOf(0 to ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_PADDED_CONTROL,
            1 to ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE)) {
            chart.bufferTransformMode = mode
            val stamp = 20L + slot
            val produced = ChartBufferRaster.capture(raster, camera, slot == 1)
            packets[stamp] = ChartBufferSnapshot(stamp, 7, stamp, ChartFrame(stamp, "reserve:$stamp", visible, camera),
                producerCompleted = true, presentationSlot = slot, raster = produced)
            presentation.updateCamera(camera)
            assertTrue(presentation.sample(slot, stamp, "CONTROLLED_ACQUIRE"))
            presentation.acknowledgeCommittedBuffer(slot, stamp, 7)
            assertEquals(produced, presentation.slots.visible!!.raster)
            assertArrayEquals(floatArrayOf(500f, 300f), map(slot, 500f, 300f), .001f)
            // TextureView pre-scales the 1250x750 queue into a 1000x600 View.
            assertArrayEquals(floatArrayOf(501f, 301f), map(slot, 500.8f, 300.8f), .001f)
            val target = transformChartViewport(camera, pan = ScreenPoint(120.0, -40.0))
            presentation.updateCamera(target)
            assertArrayEquals(floatArrayOf(620f, 260f), map(slot, 500f, 300f), .001f)
            assertEquals(camera, presentation.slots.visible!!.frame.viewport)
        }
    }

    @Test fun bothTextureProducersPermitTransformedGapsOverOpaqueWrappers() {
        val chart = ChartSurfaceView(RuntimeEnvironment.getApplication())
        val presentation = ChartPresentationView(chart.context, chart)
        chart.bufferTransformMode = ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE
        val textures = (0 until presentation.childCount).map { presentation.getChildAt(it) }.filterIsInstance<FrameLayout>()
            .flatMap { wrapper -> (0 until wrapper.childCount).map { wrapper.getChildAt(it) } }.filterIsInstance<TextureView>()
        assertEquals(2, textures.size); assertTrue(textures.all { !it.isOpaque })
    }
}
