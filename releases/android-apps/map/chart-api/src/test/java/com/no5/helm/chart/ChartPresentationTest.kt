package com.no5.helm.chart
import org.junit.Assert.*
import org.junit.Test
class ChartPresentationTest {
    @Test fun catalogHasSeparateGroupsAndAllCombinations() {
        assertEquals(19, ChartStyles.all.size)
        assertEquals(19, ChartStyles.all.map { it.id }.toSet().size)
        assertEquals(12, ChartStyles.all.count { it.mode == PresentationMode.DAY })
        assertEquals(5, ChartStyles.all.count { it.mode == PresentationMode.RED_NIGHT })
        assertEquals(2, ChartStyles.all.count { it.mode == PresentationMode.GRAY_COMPARISON })
        val combinations = ChartStyles.all.sumOf { (if (it.allowsLandRelief) 2 else 1) * it.symbolSets.size }
        assertEquals(88, combinations)
    }
    @Test fun confirmedGrayAndNightPreserveSavedDay() {
        val day = ChartPresentation("sculpt", ChartSymbolSet.NO5, true)
        val night = ChartPresentation("redline", ChartSymbolSet.BIG)
        val gray = ChartPresentation("redwater-focus-gray")
        val profiles = PresentationProfiles().confirmed(day).confirmed(night).confirmed(gray)
        assertEquals(day, profiles.forMode(PresentationMode.DAY))
        assertEquals(night, profiles.forMode(PresentationMode.RED_NIGHT))
        assertEquals(gray, profiles.forMode(PresentationMode.GRAY_COMPARISON))
    }
    @Test fun invalidReliefCannotChangeSavedProfiles() {
        val original = PresentationProfiles()
        for (style in listOf("redwater", "redwater-gray")) {
            assertThrows(IllegalArgumentException::class.java) { original.confirmed(ChartPresentation(style, landRelief = true)) }
        }
        assertEquals(ChartPresentation(), original.day)
        assertEquals("redwater-focus", original.night.styleId)
    }
    @Test fun unknownStylesFailBeforeNativePreparation() {
        assertThrows(IllegalStateException::class.java) { ChartPresentation("missing").validate() }
    }
    @Test fun selectedS01KeepsR04SymbolsAndIndependentReliefOff() {
        val selected = ChartPresentation("lagoon-sculpt", ChartSymbolSet.R04)
        selected.validate()
        assertEquals(PresentationMode.DAY, selected.mode)
        assertEquals(selected, PresentationProfiles().confirmed(selected).day)
        assertThrows(IllegalArgumentException::class.java) { selected.copy(symbols = ChartSymbolSet.NO5).validate() }
        assertThrows(IllegalArgumentException::class.java) { selected.copy(landRelief = true).validate() }
        assertThrows(IllegalArgumentException::class.java) { ChartPresentation("sculpt", ChartSymbolSet.R04).validate() }
    }
}
