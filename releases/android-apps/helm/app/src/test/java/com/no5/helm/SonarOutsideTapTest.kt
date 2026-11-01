package com.no5.helm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.DeterministicMockSonarSource
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1000dp-h600dp-land-mdpi", shadows = [NavigationComposeRenderTest.HostPowerManager::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarOutsideTapTest {
    @get:Rule val ui = createComposeRule()
    private val controller = SonarNativeController(DeterministicMockSonarSource(now = { 1_000_000L })) { 1_000_000L }
    @After fun close() { controller.dispose() }
    @Test fun menuClosesOnlyForASingleOutsideTapAndRetainsWorkingInsideControls() {
        controller.open()
        ui.setContent { Box(Modifier.size(1000.dp, 600.dp).testTag("sonar-root")) { SonarScreen(.5f, false, controller) } }
        ui.onNodeWithTag("sonar-root").performTouchInput { swipe(Offset(20f, 100f), Offset(980f, 100f)) }
        ui.runOnIdle { assertEquals(SonarDrawerTab.DISPLAY, controller.drawer) }
        ui.onNodeWithTag("sonar-root").performTouchInput {
            down(0, Offset(20f, 100f)); down(1, Offset(40f, 100f)); up(1); up(0)
        }
        ui.runOnIdle { assertEquals(SonarDrawerTab.DISPLAY, controller.drawer) }
        ui.onNodeWithTag("sonar-root").performTouchInput { down(Offset(20f, 100f)); cancel() }
        ui.runOnIdle { assertEquals(SonarDrawerTab.DISPLAY, controller.drawer) }
        ui.onNodeWithTag("Sonar tab ANALYSIS").performTouchInput { click(center) }
        ui.runOnIdle { assertEquals(SonarDrawerTab.ANALYSIS, controller.drawer) }
        ui.onNodeWithTag("sonar-root").performTouchInput { click(Offset(20f, 100f)) }
        ui.runOnIdle { assertNull(controller.drawer) }
    }
}
