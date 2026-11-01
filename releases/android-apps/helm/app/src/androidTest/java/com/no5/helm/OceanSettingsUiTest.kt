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
import com.no5.helm.ocean.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native interaction tests; compiling them is not evidence of device execution. */
@OptIn(ExperimentalTestApi::class)
class OceanSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val original = SourceConfig(SourceMode.PI, "http://pi-test.local:8080")
    private val background = BackgroundSelection(BackgroundMode.IMAGE)
    private val showSettings = mutableStateOf(true)
    private val sourceSaves = mutableListOf<SourceConfig>()
    private val backgroundSaves = mutableListOf<BackgroundSelection.Save>()
    private fun display(defer: Boolean = false, fail: Boolean = false, night: Boolean = false) {
        compose.setContent {
            var active by remember { mutableStateOf(original) }
            MaterialTheme {
                Box(Modifier.size(1000.dp, 600.dp)) {
                    if (showSettings.value) SourceSettingsScreen(.5f, night, active,
                        onApply = { sourceSaves += it; active = it }, background = background,
                        backgroundDescription = "Aktiv: Hintergrundbild", onSaveBackground = {
                            background.beginSave()?.let {
                                backgroundSaves += it
                                if (!defer) background.finishSave(it, !fail)
                            }
                        })
                }
            }
        }
    }
    @Test fun selectionIsDraftUntilSeparateSaveAndCancelRestoresImage() {
        display()
        compose.onNodeWithTag("background-mode-ANIMATION").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(BackgroundMode.IMAGE, background.active); assertTrue(backgroundSaves.isEmpty()) }
        compose.onNodeWithTag("background-cancel").performScrollTo().performClick()
        compose.onNodeWithTag("background-mode-IMAGE").assertIsSelected()
        compose.onNodeWithTag("background-save").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(sourceSaves.isEmpty()); assertTrue(backgroundSaves.isEmpty()) }
    }
    @Test fun backgroundSaveDoesNotApplyEndpointOrWeatherDraft() {
        display()
        compose.onNodeWithTag("source-endpoint").performScrollTo().performTextReplacement("http://draft.local:8080")
        compose.onNodeWithTag("background-mode-NONE").performScrollTo().performClick()
        compose.onNodeWithTag("background-save").performScrollTo().performClick()
        compose.onNodeWithTag("background-save").assertIsNotEnabled()
        compose.onNodeWithTag("source-endpoint").performScrollTo().assertTextContains("http://draft.local:8080")
        compose.runOnIdle { assertEquals(BackgroundMode.NONE, background.active); assertTrue(sourceSaves.isEmpty()) }
        compose.onNodeWithTag("source-settings-cancel").performScrollTo().performClick()
        compose.onNodeWithTag("source-endpoint").performScrollTo().assertTextContains(original.endpoint)
        compose.runOnIdle { assertEquals(BackgroundMode.NONE, background.active) }
    }
    @Test fun sourceSaveDoesNotSilentlyCommitBackgroundDraft() {
        display()
        compose.onNodeWithTag("background-mode-ANIMATION").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Speed unit km/h").performScrollTo().performClick()
        compose.onNodeWithTag("source-settings-apply").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, sourceSaves.size)
            assertTrue(backgroundSaves.isEmpty())
            assertEquals(BackgroundMode.IMAGE, background.active)
            assertEquals(BackgroundMode.ANIMATION, background.draft)
        }
    }
    @Test fun failedBackgroundSaveKeepsActiveAndCancelClearsError() {
        display(fail = true, night = true)
        compose.onNodeWithTag("background-mode-ANIMATION").performScrollTo().performClick()
        compose.onNodeWithTag("background-save").performScrollTo().performClick()
        compose.onNodeWithTag("background-error").assertTextContains("previous background remains active", substring = true)
        compose.runOnIdle { assertEquals(BackgroundMode.IMAGE, background.active); assertTrue(sourceSaves.isEmpty()) }
        compose.onNodeWithTag("background-cancel").performScrollTo().performClick()
        compose.onNodeWithTag("background-error").assertDoesNotExist()
        compose.onNodeWithTag("background-mode-IMAGE").assertIsSelected()
    }
    @Test fun repeatedSaveIsBlockedAndPageLeaveDoesNotLoseCompletedSave() {
        display(defer = true)
        compose.onNodeWithTag("background-mode-NONE").performScrollTo().performClick()
        compose.onNodeWithTag("background-save").performScrollTo().performClick()
        compose.onNodeWithTag("background-save").assertIsNotEnabled()
        compose.onNodeWithTag("background-mode-ANIMATION").assertIsNotEnabled()
        compose.runOnIdle { showSettings.value = false }
        compose.runOnIdle {
            assertEquals(1, backgroundSaves.size)
            background.finishSave(backgroundSaves.single(), true)
            assertEquals(BackgroundMode.NONE, background.active)
            showSettings.value = true
        }
        compose.onNodeWithTag("background-mode-NONE").assertIsSelected()
        compose.onNodeWithTag("background-save").assertIsNotEnabled()
    }
    @Test fun escapeAndNavigationDiscardUnsavedBackgroundAndSourceDrafts() {
        display()
        compose.onNodeWithTag("background-mode-ANIMATION").performScrollTo().performClick()
        compose.onNodeWithTag("source-endpoint").performScrollTo().performTextReplacement("http://discard.local")
        compose.onNodeWithTag("source-endpoint").performKeyInput { pressKey(Key.Escape) }
        compose.onNodeWithTag("source-endpoint").assertTextContains(original.endpoint)
        compose.onNodeWithTag("background-mode-IMAGE").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("background-mode-NONE").performClick()
        compose.runOnIdle { showSettings.value = false }
        compose.runOnIdle { showSettings.value = true }
        compose.onNodeWithTag("background-mode-IMAGE").assertIsSelected()
        compose.runOnIdle { assertTrue(sourceSaves.isEmpty()); assertTrue(backgroundSaves.isEmpty()) }
    }
}
