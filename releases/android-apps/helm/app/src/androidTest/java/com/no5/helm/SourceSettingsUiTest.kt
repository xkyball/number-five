package com.no5.helm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Compiled source coverage; execution requires a stable emulator or target device. */
@OptIn(ExperimentalTestApi::class)
class SourceSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val saved = SourceConfig(SourceMode.PI, "http://pi-test.local:8080")
    private fun display(night: Boolean = false, warning: String? = null, config: SourceConfig = saved, onApply: (SourceConfig) -> Unit) {
        compose.setContent {
            var active by remember { mutableStateOf(config) }
            MaterialTheme {
                Box(Modifier.size(1000.dp, 600.dp)) {
                    SourceSettingsScreen(.5f, night, active, { proposed -> onApply(proposed); active = proposed }, warning)
                }
            }
        }
    }

    @Test fun unitDraftDoesNotApplyUntilSaveAndCancelRestoresAllSavedFields() {
        val applied = mutableListOf<SourceConfig>()
        display(onApply = applied::add)
        compose.onNodeWithTag("source-settings-apply").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Speed unit km/h").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Consumption unit km/L").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Depth unit ft").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
        compose.onNodeWithTag("source-settings-cancel").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Speed unit kn").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Consumption unit L/h").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Depth unit m").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("source-endpoint").performScrollTo().assertTextContains(saved.endpoint)
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
    }

    @Test fun savedUnitChangeIsPublishedOnceAndRepeatedSaveIsDisabled() {
        val applied = mutableListOf<SourceConfig>()
        display(onApply = applied::add)
        compose.onNodeWithContentDescription("Speed unit mi/h").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Consumption unit L/100km").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Depth unit ft").performScrollTo().performClick()
        compose.onNodeWithTag("source-settings-apply").performScrollTo().performClick()
        compose.onNodeWithTag("source-settings-apply").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf(saved.copy(displayUnits = DisplayUnits(SpeedUnit.MPH, ConsumptionUnit.L_PER_100KM, DepthUnit.FEET))), applied)
        }
    }

    @Test fun invalidCredentialUrlCannotApplyAndValidUrlIsNormalized() {
        val applied = mutableListOf<SourceConfig>()
        display(onApply = applied::add)
        compose.onNodeWithTag("source-endpoint").performTextReplacement("http://user:secret@pi-test.local")
        compose.onNodeWithTag("source-settings-apply").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
        compose.onNodeWithTag("source-endpoint").performTextReplacement("HTTP://OTHER-GATEWAY.LOCAL:8080")
        compose.onNodeWithTag("source-settings-apply").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(saved.copy(endpoint = "http://other-gateway.local:8080")), applied) }
        compose.onNodeWithText("NO5 GATEWAY · SHARED V1 API").assertExists()
    }

    @Test fun escapeRevertsFocusedNativeEndpointWithoutSaving() {
        val applied = mutableListOf<SourceConfig>()
        display(onApply = applied::add)
        compose.onNodeWithTag("source-endpoint").performTextReplacement("http://changed.local:8080")
        compose.onNodeWithTag("source-endpoint").performKeyInput { pressKey(Key.Escape) }
        compose.onNodeWithTag("source-endpoint").assertTextContains(saved.endpoint)
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
    }

    @Test fun corruptedRecordWarningRequiresExplicitSaveInNightMode() {
        val applied = mutableListOf<SourceConfig>()
        val unavailable = saved.copy(connectionEnabled = false)
        display(night = true, warning = "Saved settings could not be read safely. Connections are disabled.", config = unavailable, onApply = applied::add)
        compose.onNodeWithTag("source-settings-warning").assertExists()
        compose.onNodeWithText("CONNECTIONS DISABLED").assertExists()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
        compose.onNodeWithTag("source-settings-apply").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(unavailable.copy(connectionEnabled = true)), applied) }
    }

    @Test fun persistenceFailureLeavesSavedSelectionAndOffersCancel() {
        display { throw IllegalStateException("storage unavailable") }
        compose.onNodeWithContentDescription("Speed unit km/h").performScrollTo().performClick()
        compose.onNodeWithTag("source-settings-apply").performScrollTo().performClick()
        compose.onNodeWithTag("source-settings-warning").assertTextContains("The previous source remains active.", substring = true)
        compose.onNodeWithTag("source-settings-cancel").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Speed unit kn").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("source-settings-warning").assertDoesNotExist()
        compose.onNodeWithTag("source-settings-apply").assertIsNotEnabled()
    }

    @Test fun gatewaySettingsHaveNoAppDemoChoice() {
        display(onApply = {})
        compose.onNodeWithText("EXTERNAL GATEWAY").assertExists()
        compose.onNodeWithContentDescription("Demo source").assertDoesNotExist()
        compose.onNodeWithText("DEMO").assertDoesNotExist()
    }

    @Test fun bodyBoundsPreserveSharedHeaderAndFooter() {
        display(onApply = {})
        compose.onNodeWithTag("source-settings-body")
            .assertTopPositionInRootIsEqualTo(56.dp)
            .assertHeightIsEqualTo(473.dp)
            .assertWidthIsEqualTo(1000.dp)
    }
}
