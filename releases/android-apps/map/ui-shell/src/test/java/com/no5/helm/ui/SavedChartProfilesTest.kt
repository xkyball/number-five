package com.no5.helm.ui
import android.content.Context
import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SavedChartProfilesTest {
    @Test fun restartRestoresIndependentConfirmedProfiles() = runBlocking {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("test-presentation", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val saved = SavedChartProfiles(prefs)
        val day = ChartPresentation("prism", ChartSymbolSet.NO5, true)
        val night = ChartPresentation("redline", ChartSymbolSet.BIG)
        saved.confirmed(day); saved.confirmed(night)
        val restored = SavedChartProfiles(prefs)
        assertEquals(day, restored.profiles.day)
        assertEquals(night, restored.profiles.night)
        assertEquals(PresentationMode.RED_NIGHT, restored.activeMode)
        restored.confirmed(ChartPresentation("redwater-gray"))
        assertEquals(night, SavedChartProfiles(prefs).profiles.night)
    }
    @Test fun restartRetainsSelectedS01WithItsOriginalSymbols() = runBlocking {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("test-presentation-s01", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val selected = ChartPresentation("lagoon-sculpt", ChartSymbolSet.R04)
        val saved = SavedChartProfiles(prefs)
        saved.confirmed(selected)
        saved.confirmed(ChartPresentation("redline", ChartSymbolSet.BIG))
        val restored = SavedChartProfiles(prefs)
        assertEquals(selected, restored.profiles.day)
        assertEquals("redline", restored.profiles.night.styleId)
        assertFalse(restored.profiles.day.landRelief)
        assertEquals(ChartSymbolSet.R04, restored.profiles.day.symbols)
    }
    @Test fun corruptSavedSelectionUsesItsOwnGroupDefault() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("test-presentation-corrupt", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("DAY.style", "redline").putString("RED_NIGHT.style", "missing")
            .putString("GRAY_COMPARISON.symbols", "missing").commit()
        val restored = SavedChartProfiles(prefs)
        assertEquals(ChartPresentation(), restored.profiles.day)
        assertEquals("redwater-focus", restored.profiles.night.styleId)
        assertEquals("redwater-gray", restored.profiles.gray.styleId)
    }
}
