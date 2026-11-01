package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.no5.domain.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.sin

/**
 * Raster evidence from the production painters and packaged resources, using native Skia.
 * This is host Android-Canvas evidence, not an emulator screenshot, device test or FPS test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeCanvasRenderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val now = 120_000L
    private val units = DisplayUnits(speed = SpeedUnit.KMH)
    private val history = DepthHistorySnapshot((0..120).map { second ->
        DepthHistoryPoint(second * 1_000L, 12.9 + sin(second / 13.0) * 1.4, second == 0)
    }, 0L, now, now)
    private val fixture = HelmState(speed = 29.0 / 1.852, depth = 12.9, heading = 36.0,
        quality = TelemetryQuality(TelemetryStatus.LIVE, source = "NATIVE_RASTER_FIXTURE", demo = true))

    @Test fun exportProductionHelmAndAudioInBothThemes() {
        // Resource-backed fonts must load, without replacing them with system approximations.
        listOf(R.font.barlow_condensed_extrabold, R.font.barlow_condensed_semibold,
            R.font.barlow_condensed_regular).forEach { assertNotNull(context.resources.getFont(it)) }
        for (night in listOf(false, true)) {
            val name = if (night) "night" else "day"
            val scene = HelmScene(context, night)
            val helm = bitmap()
            scene.draw(Canvas(helm), renderValues(fixture, now, units = units), fixture, history, units)
            write(helm, "helm-$name.png")
            assertOpaque(helm)
            assertTrue("HELM must contain actual painted detail", uniqueColors(helm, 690, 214, 1310, 697) > 50)
            if (night) assertRedPalette(helm, "HELM")

            val controller = AudioNativeController(Dispatchers.Unconfined, now = { now },
                automaticTicker = false, automaticVerification = false, source = MockAudioSource(now = { now }))
            try {
                controller.loadFixture(AudioFixture.DEMO)
                controller.startDemo()
                val audio = bitmap()
                val canvas = Canvas(audio)
                canvas.drawColor(if (night) Color.BLACK else Color.rgb(246, 249, 250))
                AudioNativeScene(context, night).draw(canvas, controller)
                scene.drawChrome(canvas, "AUDIO", renderValues(fixture, now, units = units), "SIMULATED")
                write(audio, "audio-$name.png")
                assertOpaque(audio)
                assertTrue("AUDIO artwork must be painted", uniqueColors(audio, 67, 158, 373, 464) > 50)
                if (night) assertRedPalette(audio, "AUDIO")
                audio.recycle()
            } finally { controller.dispose() }
            helm.recycle()
        }
    }

    @Test fun transparentGaugePreservesSceneAndPaintsReadableNumerals() {
        for (night in listOf(false, true)) {
            val name = if (night) "night" else "day"
            val overlay = bitmap()
            SpeedDepthInstrument(context, night).draw(Canvas(overlay), fixture, history, units, "LIVE", now)
            write(overlay, "gauge-$name-transparent.png")
            var untouched = 0
            var painted = 0
            for (y in SpeedDepthInstrumentGeometry.TOP.toInt() until SpeedDepthInstrumentGeometry.BOTTOM.toInt())
                for (x in SpeedDepthInstrumentGeometry.LEFT.toInt() until SpeedDepthInstrumentGeometry.RIGHT.toInt()) {
                if (Color.alpha(overlay.getPixel(x, y)) == 0) untouched++ else painted++
            }
            assertTrue("The gauge face must remain transparent, not become an opaque panel", untouched > (untouched + painted) * .70)
            assertEquals("Outside gauge remains untouched", Color.TRANSPARENT, overlay.getPixel(600, 400))
            // Large speed glyphs must have a substantial opaque raster, not missing font tofu or invisible text.
            var numeralInk = 0
            for (y in 389 until 480) for (x in 865 until 1135) {
                if (Color.alpha(overlay.getPixel(x, y)) > 220) numeralInk++
            }
            assertTrue("The speed numeral must render solid legible-size glyphs", numeralInk > 1500)
            assertTrue("The speed box must retain open background around the digits", numeralInk < 15000)
            val background = bitmap()
            val canvas = Canvas(background)
            canvas.drawColor(if (night) Color.rgb(8, 0, 0) else Color.rgb(185, 222, 246))
            val before = background.getPixel(1000, 512)
            canvas.drawBitmap(overlay, 0f, 0f, null)
            assertEquals("Clear space between speed unit and depth rule must preserve scene", before, background.getPixel(1000, 512))
            write(background, "gauge-$name-background-check.png")
            if (night) assertRedPalette(background, "gauge")
            overlay.recycle(); background.recycle()
        }
    }

    @Test fun zeroOverrangeAndUnavailableRastersRemainDistinctAndInsideInstrumentBounds() {
        val stressUnits = DisplayUnits(speed = SpeedUnit.KMH, depth = DepthUnit.FEET)
        val cases = listOf(
            "zero" to fixture.copy(speed = 0.0, depth = 0.0),
            "overrange" to fixture.copy(speed = 200.0, depth = 12_000.0),
            "unavailable" to HelmState.unavailable()
        )
        val overlays = mutableListOf<Bitmap>()
        try {
            cases.forEach { (name, state) ->
                val overlay = bitmap()
                overlays += overlay
                val quality = if (name == "unavailable") "UNAVAILABLE" else "LIVE"
                SpeedDepthInstrument(context, false).draw(Canvas(overlay), state,
                    DepthHistorySnapshot.empty(), stressUnits, quality, now)
                val pixels = IntArray(overlay.width * overlay.height)
                overlay.getPixels(pixels, 0, overlay.width, 0, 0, overlay.width, overlay.height)
                var speedInk = 0
                var depthInk = 0
                pixels.forEachIndexed { index, color ->
                    if (Color.alpha(color) != 0) {
                        val x = index % overlay.width
                        val y = index / overlay.width
                        assertTrue("$name paints outside the canonical instrument at $x,$y",
                            x >= SpeedDepthInstrumentGeometry.LEFT && x < SpeedDepthInstrumentGeometry.RIGHT &&
                                y >= SpeedDepthInstrumentGeometry.TOP && y < SpeedDepthInstrumentGeometry.BOTTOM)
                        if (x in 865 until 1135 && y in 389 until 480 && Color.alpha(color) > 220) speedInk++
                        if (x in 915 until 1012 && y in 568 until 609 && Color.alpha(color) > 220) depthInk++
                    }
                }
                assertTrue("$name speed raster must remain visible", speedInk > 100)
                assertTrue("$name depth raster must remain visible", depthInk > 40)
                val scene = bitmap()
                HelmScene(context, false).draw(Canvas(scene), renderValues(state, now, units = stressUnits),
                    state, DepthHistorySnapshot.empty(), stressUnits)
                assertOpaque(scene)
                val crop = Bitmap.createBitmap(scene,
                    SpeedDepthInstrumentGeometry.LEFT.toInt(), SpeedDepthInstrumentGeometry.TOP.toInt(),
                    (SpeedDepthInstrumentGeometry.RIGHT - SpeedDepthInstrumentGeometry.LEFT).toInt(),
                    (SpeedDepthInstrumentGeometry.BOTTOM - SpeedDepthInstrumentGeometry.TOP).toInt())
                write(crop, "helm-day-stress-$name-native-crop.png")
                crop.recycle()
                scene.recycle()
            }
            assertFalse("Zero must remain visually different from unavailable", overlays[0].sameAs(overlays[2]))
            assertFalse("Overrange must remain visually different from zero", overlays[0].sameAs(overlays[1]))
        } finally { overlays.forEach(Bitmap::recycle) }
    }

    private fun bitmap() = Bitmap.createBitmap(2000, 1200, Bitmap.Config.ARGB_8888)
    private fun write(bitmap: Bitmap, name: String) {
        val directory = File(System.getProperty("no5.renderOutputDir") ?: "../verification/renders")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun assertOpaque(bitmap: Bitmap) {
        for (y in 0 until bitmap.height step 13) for (x in 0 until bitmap.width step 13)
            assertEquals("Rendered scene has an unpainted pixel at $x,$y", 255, Color.alpha(bitmap.getPixel(x, y)))
    }
    private fun uniqueColors(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        val colors = mutableSetOf<Int>()
        for (y in top until bottom step 3) for (x in left until right step 3) colors += bitmap.getPixel(x, y)
        return colors.size
    }
    private fun assertRedPalette(bitmap: Bitmap, scene: String) {
        var brightNeutral = 0
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
            val pixel = bitmap.getPixel(x, y)
            // Explicit user-approved exception: only the 278×4 orange active footer rule.
            // Keep the rest of NIGHT fully subject to the original red-only assertion.
            val page=if(scene=="HELM")1 else if(scene=="AUDIO")2 else -10
            val indicatorLeft=if(page>=0)kotlin.math.round(BottomNavigation.cellLeft(page)+17).toInt()else -1000
            val approvedOrange = x in indicatorLeft until indicatorLeft+206 && y in 1171 until 1175 && pixel == Color.rgb(184,71,18)
            if (!approvedOrange && (Color.green(pixel) > 65 || Color.blue(pixel) > 65)) brightNeutral++
        }
        assertEquals("$scene night raster must not contain bright daylight media or neutral surfaces", 0, brightNeutral)
    }
}
