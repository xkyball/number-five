package com.no5.helm

import org.junit.Assert.*
import org.junit.Test

class TabularRpmLayoutTest {
    // Deliberately proportional metrics expose accidental use of per-value string widths.
    private val metrics = "0123456789>—".associateWith {
        val width = if (it == '1') 20f else if (it == '—') 80f else 40f + (it.code % 7)
        TabularRpmLayout.Glyph(width + 4f, -2f, width - 2f)
    }
    private val font = TabularRpmLayout.Font(metrics, 281.35f)
    private fun run(value: String, right: Boolean = false) = font.place(value,
        if (right) 1962f else 38f, 285f,
        if (right) TabularRpmLayout.Anchor.RIGHT else TabularRpmLayout.Anchor.LEFT)

    @Test fun proportionalDigitShapesReceiveIdenticalAdvancesAndFourDigitWidths() {
        val allDigits = ('0'..'9').map { run(it.toString()) }
        allDigits.forEach { assertEquals(font.advance, it.advance, 0f) }
        val samples = listOf("1000", "1111", "8888", "9999").map { run(it) }
        samples.forEach {
            assertEquals(samples.first().width, it.width, 0f)
            assertEquals(samples.first().glyphScale, it.glyphScale, 0f)
            assertEquals(285f, it.baseline, 0f)
            it.cells.zipWithNext().forEach { (a, b) -> assertEquals(a.right, b.left, .001f) }
        }
    }

    @Test fun digitCountTransitionsKeepPlaceValuesAndBaselineFixed() {
        for (value in listOf("0", "9", "99", "999", "1000", "8888")) {
            val port = run(value)
            val stbd = run(value, true)
            assertEquals(38f + TabularRpmLayout.DIGITS * font.advance, port.cells.last().right, .001f)
            assertEquals(1962f, stbd.cells.last().right, .001f)
            assertTrue(port.cells.first().left >= 38f)
            assertEquals(port.glyphScale, stbd.glyphScale, 0f)
            assertEquals(font.scale, port.glyphScale, 0f)
        }
    }

    @Test fun staleMarkerDoesNotAffectDigitsOrChangeItsReservedPosition() {
        for (value in listOf("0", "999", "1000", "8888", "100000", "—")) {
            for (right in listOf(false, true)) {
                val fresh = run(value, right)
                val stale = run("$value*", right)
                assertEquals(fresh.cells, stale.cells)
                assertEquals(fresh.glyphScale, stale.glyphScale, 0f)
                assertFalse(fresh.stale)
                assertTrue(stale.stale)
                assertEquals(run("0*", right).markerCenter, stale.markerCenter, 0f)
            }
        }
    }

    @Test fun exactOverspeedValuesUseOneFixedSixDigitTierAndStayBounded() {
        val tier = run("10000").glyphScale
        for (value in listOf("10000", "11111", "88888", "99999", "100000")) {
            val result = run(value)
            assertEquals(value, result.shown)
            assertEquals(tier, result.glyphScale, 0f)
            assertTrue(result.width <= font.numberWidth + .001f)
        }
        assertEquals("—", run("—").shown)
        assertEquals("0", run("0000").shown)
        assertEquals("—", run("NaN").shown)
        assertEquals(">999999", run("123456789123456789").shown)
    }

    @Test fun allInkExtentsStayInsideTheirCellsIncludingMissingAndOverspeed() {
        for (value in listOf("1111", "8888", "0", "—", "100000", "123456789")) {
            for (right in listOf(false, true)) {
                val result = run(value, right)
                result.cells.forEach { cell ->
                    val metric = metrics.getValue(cell.character)
                    assertTrue(cell.origin + metric.left * result.glyphScale >= cell.left - .001f)
                    assertTrue(cell.origin + metric.right * result.glyphScale <= cell.right + .001f)
                }
            }
        }
    }
}
