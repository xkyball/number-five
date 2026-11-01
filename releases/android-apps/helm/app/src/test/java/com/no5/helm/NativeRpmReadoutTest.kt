package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Uses the shipped Barlow font and real Android/Skia raster; it is not device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeRpmReadoutTest {
    private fun readout() = NativeRpmReadout(RuntimeEnvironment.getApplication()
        .resources.getFont(R.font.barlow_condensed_extrabold))

    @Test fun nativeFontKeepsNormalGlyphAdvancesScaleAndBaselineStable() {
        val readout = readout()
        for (size in NativeRpmReadout.Size.entries) {
            val samples = listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
                "999", "1000", "1111", "8888", "9999", "3400*")
                .map { readout.layout(it, 38f, 285f, TabularRpmLayout.Anchor.LEFT, size) }
            samples.forEach {
                assertEquals(samples.first().advance, it.advance, 0f)
                assertEquals(samples.first().glyphScale, it.glyphScale, 0f)
                assertEquals(285f, it.baseline, 0f)
            }
            val fourDigit = samples.filter { it.shown.length == 4 }
            fourDigit.forEach { assertEquals(fourDigit.first().width, it.width, 0f) }
        }
    }

    @Test fun nativeRastersAreVisibleBoundedMirroredAndPreserveStaleMarkers() {
        val readout = readout()
        val examples = listOf("999", "1000", "1111", "8888", "10000", "100000", "3400*", "—")
        val sheet = Bitmap.createBitmap(2000, 1200, Bitmap.Config.ARGB_8888)
        // Keep the inspected contact sheet readable; per-row alpha assertions stay transparent.
        Canvas(sheet).drawColor(Color.WHITE)
        for ((index, value) in examples.withIndex()) {
            val row = Bitmap.createBitmap(2000, 140, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(row)
            for ((x, anchor) in listOf(38f to TabularRpmLayout.Anchor.LEFT, 1962f to TabularRpmLayout.Anchor.RIGHT)) {
                readout.draw(canvas, value, x, 108f, Color.BLACK, anchor)
            }
            var pixels = 0
            for (y in 0 until row.height) for (x in 0 until row.width) {
                if (Color.alpha(row.getPixel(x, y)) > 0) {
                    pixels++
                    assertTrue("$value raster outside fixed engine slots at $x,$y",
                        x in 38..320 || x in 1679..1962)
                    assertTrue("$value raster crossed baseline area", y in 5..112)
                }
            }
            assertTrue("$value native glyphs must remain visible", pixels > 100)
            Canvas(sheet).drawBitmap(row, 0f, index * 140f, null)
            row.recycle()
        }
        val output = File(System.getProperty("no5.renderOutputDir") ?: "../verification/renders")
        check(output.isDirectory || output.mkdirs())
        File(output, "rpm-tabular-native.png").outputStream().use {
            assertTrue(sheet.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        sheet.recycle()
    }
}
