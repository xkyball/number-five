package com.no5.helm

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.no5.ui.No5ActionFeedback
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The feedback owner never becomes a second owner of a real toggle value. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], qualifiers = "w400dp-h300dp-land-mdpi")
class No5ToggleFeedbackTest {
    @get:Rule val ui = createComposeRule()
    private val checkbox = mutableStateOf(true)
    private val switch = mutableStateOf(true)
    private var changes = 0
    private var input: InputModeManager? = null
    private fun launch() {
        ui.setContent {
            input = LocalInputModeManager.current
            Row {
                No5ActionFeedback(feedbackColor = Color.Blue) { feedback ->
                    Checkbox(checkbox.value, { checkbox.value = it; changes++ },
                        Modifier.testTag("checkbox").then(feedback.modifier), enabled = feedback.enabled,
                        interactionSource = feedback.interactionSource)
                }
                No5ActionFeedback(feedbackColor = Color.Blue) { feedback ->
                    Switch(switch.value, { switch.value = it; changes++ },
                        Modifier.testTag("switch").then(feedback.modifier), enabled = feedback.enabled,
                        interactionSource = feedback.interactionSource)
                }
            }
        }
        ui.waitForIdle()
    }
    @Test fun cancelledPressKeepsCheckedStateAndOnlyACompletedClickChangesIt() {
        launch()
        for (tag in listOf("checkbox", "switch")) {
            ui.onNodeWithTag(tag).performTouchInput { down(center); cancel() }
            ui.onNodeWithTag(tag).assertIsOn()
        }
        ui.runOnIdle { assertEquals(0, changes) }
        for (tag in listOf("checkbox", "switch")) {
            ui.onNodeWithTag(tag).performTouchInput { click(center) }
            ui.onNodeWithTag(tag).assertIsOff()
        }
        ui.runOnIdle { assertEquals(2, changes) }
    }
    @Test fun focusNavigationDoesNotToggleEitherConfirmedValue() {
        launch()
        ui.runOnIdle { input!!.requestInputMode(InputMode.Keyboard) }
        for (tag in listOf("checkbox", "switch")) {
            ui.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            ui.onNodeWithTag(tag).assertIsFocused().assertIsOn()
        }
        ui.onNodeWithTag("checkbox").assertIsOn()
        ui.runOnIdle { assertEquals(0, changes) }
    }
}
