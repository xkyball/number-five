package com.no5.helm

import org.junit.Assert.*
import org.junit.Test

class HelmLowerDeckAlignmentTest {
    private fun top(id: String) = NativeHelmLayout.textTop(id, Float.NaN)

    @Test fun twoRowCardsUseTheSameIconLabelAndValueRhythm() {
        val rowStep = 48f
        for ((upper, lower) in listOf("deck-label-17" to "deck-label-19",
            "deck-label-18" to "deck-label-20", "deck-label-23" to "deck-label-25",
            "deck-label-24" to "deck-label-26", "deck-enginesStatus" to "deck-gpsStatus",
            "deck-n2kStatus" to "deck-alarmsStatus", "deck-tripDuration" to "deck-fuelUsed")) {
            assertEquals("$upper and $lower move as cohesive rows", rowStep, top(lower) - top(upper), 0f)
        }
        for ((upper, lower) in listOf("deck-icon-engine-8" to "deck-icon-gps-10",
            "deck-icon-network-9" to "deck-icon-bell-11", "deck-icon-distance-13" to "deck-icon-fuel-15",
            "deck-icon-clock-14" to "deck-icon-efficiency-16")) {
            val first = checkNotNull(NativeHelmLayout.iconPlacement(upper))
            val second = checkNotNull(NativeHelmLayout.iconPlacement(lower))
            assertEquals(rowStep, second.y - first.y, 0f)
            assertEquals(NativeHelmLayout.DECK_CONTENT_CENTER,
                (first.y + second.y + 48f * second.scale) / 2f - 1f, .01f)
        }
    }

    @Test fun headersStayAboveCohesiveBodyAndBottomValuesKeepVisiblePadding() {
        val titleBottom = top("deck-label-9") + 24f * 1.1f
        assertTrue(NativeHelmLayout.DECK_GROUP_TOP > titleBottom + 6f)
        assertEquals(18f, NativeHelmLayout.FOOTER_TOP - NativeHelmLayout.DECK_BOTTOM, 0f)
        for (column in NativeHelmLayout.weatherColumns) {
            val icon = checkNotNull(NativeHelmLayout.iconPlacement(column.iconId))
            val value = checkNotNull(NativeHelmLayout.valueUnitBox(column.valueId))
            val label = checkNotNull(NativeHelmLayout.weatherLabelBox(column.labelId))
            assertTrue(icon.y + 48f * icon.scale + 5f <= value.top)
            assertTrue(label.bottom <= NativeHelmLayout.DECK_BOTTOM - 11f)
            assertEquals(NativeHelmLayout.DECK_CONTENT_CENTER + 1.5f,
                (icon.y + label.bottom) / 2f, .01f)
        }
        for (id in listOf("deck-tripDistance", "deck-efficiency")) {
            val box = checkNotNull(NativeHelmLayout.tripValueUnitBox(id))
            assertTrue(box.bottom <= NativeHelmLayout.DECK_BOTTOM - 11f)
            assertTrue(box.top > titleBottom + 12f)
        }
        assertTrue(top("deck-cog") + 29f * 1.1f <= NativeHelmLayout.DECK_BOTTOM - 11f)
    }
}
