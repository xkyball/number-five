package com.no5.helm.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.compose.setContent
import org.robolectric.Robolectric
import androidx.compose.ui.unit.dp
import com.no5.helm.chart.*
import com.no5.preparation.ui.No5ControlTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ChartObjectPanelTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun choosingAScrolledCandidateStartsDetailsAtTheTop() {
        val objects = (1..12).map { ChartObject(ChartObjectReference("source", it, 1), "$it", "WRECKS", "POINT", "cell",
            GeoProjection(1.0, 2.0), 0.0, mapOf("OBJNAM" to "Wrack $it", "INFORM" to "Hinweis ".repeat(200))) }
        var selected by mutableStateOf<ChartObject?>(null)
        val activity = Robolectric.buildActivity(No5ImmersiveActivity::class.java).setup().visible().windowFocusChanged(true)
        activity.get().setContent { No5ControlTheme(false) {
            ChartObjectPanel(selected, objects, objects.size, null, false, ChartObjectHighlight(), ChartObjectDictionary("", ""),
                null, emptyList(), false, { selected = it }, {}, {}, Modifier.size(310.dp, 450.dp))
        } }
        compose.onNodeWithTag("object-pick-source-12").performScrollTo().performClick()
        compose.onNodeWithTag("object-kind").assertIsDisplayed().assertTextEquals("Wrack · WRECKS")
        compose.onNodeWithTag("object-raw-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("object-raw-INFORM").assertTextEquals("INFORM: ${objects.last().attributes.getValue("INFORM")}")
        activity.pause().stop().destroy()
    }
}
