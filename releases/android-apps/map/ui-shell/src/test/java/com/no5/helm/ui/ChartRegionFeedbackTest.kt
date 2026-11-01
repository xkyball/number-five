package com.no5.helm.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.no5.helm.library.ChartCell
import com.no5.helm.library.ChartSelectionExtent
import com.no5.preparation.ui.No5ControlTheme
import com.no5.ui.No5Text
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual active region surface; confirmed open/selected state is independent from gestures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w800dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartRegionFeedbackTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    @After fun close() { if (::host.isInitialized) host.pause().stop().destroy() }

    @Test fun regionSurfaceCancelsObsoletePressesWithoutClearingTheOpenedRegionOrSelection() {
        val enabled = mutableStateOf(true)
        val cell = ChartCell("FIXTURE", 1, 0, "fixture.000", "20261017", emptyMap(), false,
            ChartSelectionExtent(42.2, 42.3, -71.8, -71.7))
        val region = ChartRegion(ChartRegionKey(42, -72), listOf(cell))
        var opened = 0
        host = Robolectric.buildActivity(ComponentActivity::class.java)
        host.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        host.setup().visible().windowFocusChanged(true)
        host.get().setContent {
            No5ControlTheme(false) { Box(Modifier.width(350.dp)) {
                ChartRegionGrid(listOf(region), setOf(cell.name), region.id, enabled.value) { opened++ }
            } }
        }
        fun settle() { ui.waitForIdle(); ui.mainClock.advanceTimeBy(32); ui.waitForIdle() }
        val target = ui.onNodeWithTag("chart-zone-${region.id}")
        settle()
        target.performTouchInput { down(center); moveTo(Offset(width + 20f, center.y)); up() }
        settle(); assertEquals(0, opened)
        target.performTouchInput { down(center); cancel() }
        settle(); assertEquals(0, opened)
        target.performTouchInput { down(center) }
        ui.runOnIdle { enabled.value = false }; settle()
        ui.runOnIdle { enabled.value = true }; settle()
        target.performTouchInput { up() }; settle()
        assertEquals(0, opened)
        ui.onNodeWithText(No5Text.ui("Details geöffnet")).assertExists()
        ui.onNodeWithText(No5Text.ui("%d Zellen · %d gewählt", 1, 1)).assertExists()
        target.performTouchInput { click(center) }; settle()
        assertEquals(1, opened)
        ui.onNodeWithText(No5Text.ui("Details geöffnet")).assertExists()
    }
}
