package com.no5.preparation.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.no5.ui.No5LocaleScope
import com.no5.ui.No5Text
import com.no5.ui.UiLanguage
import com.no5.ui.no5Text
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class No5LocaleRetentionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>

    @After fun close() {
        if (::activity.isInitialized) {
            UiLanguage.acceptConfirmed(activity.get(), "de")
            activity.pause().stop().destroy()
        }
    }

    @Test fun sharedLanguageUpdatesStableLabelsWithoutRecreatingDraftOrController() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val language = mutableStateOf("de")
        var controllerCreations = 0
        UiLanguage.acceptConfirmed(activity.get(), "de")
        activity.get().setContent {
            No5LocaleScope(language.value) {
                // A stable child also has to invalidate when a static locale provider changes.
                RetainedEditor { controllerCreations++ }
            }
        }
        compose.onNodeWithText("Einstellungen").assertExists()
        compose.onNodeWithTag("draft").performTextInput("Granger Bay → Hout Bay")
        compose.runOnIdle { UiLanguage.acceptConfirmed(activity.get(), "en"); language.value = "en" }
        compose.onNodeWithText("Settings").assertExists()
        compose.onNodeWithTag("draft").assertTextContains("Granger Bay → Hout Bay")
        compose.runOnIdle { UiLanguage.acceptConfirmed(activity.get(), "af"); language.value = "af" }
        compose.onNodeWithText("Instellings").assertExists()
        compose.onNodeWithTag("draft").assertTextContains("Granger Bay → Hout Bay")
        compose.runOnIdle { assertEquals(1, controllerCreations) }
    }

    @Composable private fun RetainedEditor(onCreateController: () -> Unit) {
        remember { Any().also { onCreateController() } }
        val draft = remember { mutableStateOf("") }
        Column {
            // Existing Java-backed UI calls and the new Compose reader both participate.
            Text(No5Text.ui("Einstellungen"))
            Text(no5Text("Für alle No5-Apps"))
            OutlinedTextField(draft.value, { draft.value = it }, Modifier.testTag("draft"))
        }
    }
}
