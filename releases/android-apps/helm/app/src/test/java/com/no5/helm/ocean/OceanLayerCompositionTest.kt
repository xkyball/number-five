package com.no5.helm.ocean

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.no5.domain.HelmState
import com.no5.helm.HelmRenderValues
import com.no5.helm.HelmScene
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native host pixel checks for layer/order compatibility; no GPU or device-FPS claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OceanLayerCompositionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun retainedBackgroundThenForegroundMatchesCombinedProductionPainter() {
        val renderer = OceanBackgroundRenderer(context).also { it.prepare(BackgroundMode.ANIMATION) }
        val values = HelmRenderValues()
        val state = HelmState.unavailable()
        for (night in listOf(false, true)) {
            val scene = HelmScene(context, night, renderer)
            for (mode in BackgroundMode.entries) for (scale in listOf(1f, .5f)) {
                val width = (2000 * scale).toInt()
                val height = (1200 * scale).toInt()
                val expected = bitmap(width, height)
                val background = bitmap(width, height)
                val composed = bitmap(width, height)
                try {
                    val frame = OceanFrame(timeSeconds = 1f, quality = OceanQuality.LOW)
                    scene.draw(stageCanvas(expected, scale), values, state,
                        backgroundMode = mode, oceanFrame = frame)
                    scene.drawBackground(stageCanvas(background, scale), mode, false, frame)
                    val canvas = Canvas(composed)
                    canvas.drawBitmap(background, 0f, 0f, null)
                    canvas.scale(scale, scale)
                    canvas.clipRect(0f, 0f, 2000f, 1200f)
                    scene.drawForeground(canvas, values, state)
                    assertTrue("Split pixels differ: night=$night mode=$mode scale=$scale",
                        expected.sameAs(composed))
                } finally {
                    expected.recycle(); background.recycle(); composed.recycle()
                }
            }
        }
    }

    @Test fun transparentForegroundHasNoPhotoOrBlueUnderlayAndPreservesGaugeOpening() {
        val renderer = OceanBackgroundRenderer(context).also { it.prepare(BackgroundMode.IMAGE) }
        for (night in listOf(false, true)) {
            val foreground = bitmap()
            try {
                HelmScene(context, night, renderer).drawForeground(Canvas(foreground), HelmRenderValues())
                assertEquals("The instrument opening must reveal the retained background",
                    Color.TRANSPARENT, foreground.getPixel(1000, 470))
                assertEquals("The gap above the lower navigation bands remains transparent",
                    Color.TRANSPARENT, foreground.getPixel(1000, 914))
            } finally { foreground.recycle() }
        }
    }

    @Test fun noneAndNightSuppressMediaAndUnderlayWhileImageRetainsAperture() {
        val renderer = OceanBackgroundRenderer(context).also { it.prepare(BackgroundMode.IMAGE) }
        val day = HelmScene(context, false, renderer)
        val night = HelmScene(context, true, renderer)
        val output = bitmap()
        try {
            day.drawBackground(Canvas(output), BackgroundMode.NONE, false, OceanFrame())
            assertEquals(Color.rgb(246, 249, 250), output.getPixel(1000, 300))
            assertEquals(Color.rgb(246, 249, 250), output.getPixel(200, 30))
            day.drawBackground(Canvas(output), BackgroundMode.IMAGE, false, OceanFrame())
            assertNotEquals(Color.rgb(246, 249, 250), output.getPixel(1000, 300))
            assertEquals("Media outside the authored aperture stays on the stage",
                Color.rgb(246, 249, 250), output.getPixel(400, 300))
            assertEquals("Media cannot extend into the deck", Color.rgb(246, 249, 250),
                output.getPixel(1000, 754))
            night.drawBackground(Canvas(output), BackgroundMode.ANIMATION, false, OceanFrame())
            assertEquals(Color.BLACK, output.getPixel(1000, 300))
            assertEquals(Color.BLACK, output.getPixel(200, 30))
        } finally { output.recycle() }
    }

    private fun bitmap(width: Int = 2000, height: Int = 1200) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun stageCanvas(bitmap: Bitmap, scale: Float) = Canvas(bitmap).apply {
        scale(scale, scale)
        clipRect(0f, 0f, 2000f, 1200f)
    }
}
