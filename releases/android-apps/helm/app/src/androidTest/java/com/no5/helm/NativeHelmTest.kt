package com.no5.helm
import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Android instrumentation acceptance. Merely compiling this file is not a test pass. */
class NativeHelmTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun themeAndNativeNavigationRemainNative(){
        ui.onNodeWithContentDescription("Night theme").performClick().assertIsSelected()
        ui.onNodeWithContentDescription("AUDIO").performClick().assertIsSelected()
        ui.onNodeWithContentDescription("HELM").performClick().assertIsSelected()
        ui.onNodeWithContentDescription("Day theme").performClick().assertIsSelected()
    }
    @Test fun localDemoControlsAreAbsent(){
        ui.onNodeWithContentDescription("Open demo controls").assertDoesNotExist()
        ui.onNodeWithContentDescription("Port trim (simulation)").assertDoesNotExist()
        ui.onNodeWithContentDescription("SETTINGS").performClick()
        ui.onNodeWithContentDescription("Demo source").assertDoesNotExist()
        ui.onNodeWithContentDescription("Pi endpoint").assertExists()
    }
    @Test fun repeatedNavigationPreservesNativeSourceSelection(){
        repeat(3){
            listOf("DASHBOARD, staged screen","AUDIO","SONAR","SETTINGS","HELM").forEach { page ->
                ui.onNodeWithContentDescription(page).performTouchInput { click(center) }.assertIsSelected()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab))
                ui.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab) and isSelected()).assertCountEquals(1)
            }
        }
    }
    @Test fun themePointerTargetMatchesItsNativeSemantics(){
        ui.onNodeWithContentDescription("Night theme").performTouchInput { click(center) }.assertIsSelected()
        ui.onNodeWithContentDescription("Day theme").performTouchInput { click(center) }.assertIsSelected()
    }
    @Test fun rtlLocaleDoesNotMovePhysicalThemeTargets(){
        ui.runOnUiThread{ui.activity.setContent{CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl){No5App(ui.activity.sourceSessions)}}}
        ui.waitForIdle()
        val day=ui.onNodeWithContentDescription("Day theme").fetchSemanticsNode().boundsInRoot
        val night=ui.onNodeWithContentDescription("Night theme").fetchSemanticsNode().boundsInRoot
        assertTrue(day.left<night.left)
        ui.onNodeWithContentDescription("Night theme").performClick().assertIsSelected()
    }
}
