package com.no5.helm.video

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.no5.domain.SourceConfig
import com.no5.helm.SourceSettingsScreen
import com.no5.helm.focusHelmTestForegroundWindow
import com.no5.helm.ocean.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,33,35],qualifiers="en-rUS-w2000dp-h1200dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoSettingsUiTest {
    @get:Rule val ui=createComposeRule()
    @Test fun choosingVideoRemainsDraftUntilExplicitSaveAndCancelRestoresPreviousMode(){
        val selection=BackgroundSelection(BackgroundMode.IMAGE)
        var connectionWrites=0
        ui.setContent {SourceSettingsScreen(1f,false,SourceConfig(connectionEnabled=false),{connectionWrites++},
            background=selection,onChooseVideo={selection.selectVideo("content://owned-test/360.mp4")},
            onSaveBackground={selection.beginSave()?.let{selection.finishSave(it,true)}})}
        ui.onNodeWithTag("background-mode-VIDEO").performScrollTo().performClick()
        ui.onNodeWithTag("video-choose").performScrollTo().performClick()
        ui.runOnIdle{assertEquals(BackgroundMode.IMAGE,selection.active);assertNull(selection.activeVideoUri)}
        ui.onNodeWithTag("background-cancel").performScrollTo().performClick()
        ui.onNodeWithTag("unsaved-changes-dialog").assertExists()
        ui.runOnIdle{assertEquals(BackgroundMode.VIDEO,selection.draft);assertEquals(BackgroundMode.IMAGE,selection.active)}
        focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("unsaved-changes-discard").assertIsEnabled().performClick()
        focusHelmTestForegroundWindow(ui)
        ui.runOnIdle{assertEquals(BackgroundMode.IMAGE,selection.draft);assertNull(selection.draftVideoUri)}
        ui.onNodeWithTag("background-mode-VIDEO").performScrollTo().performClick()
        ui.onNodeWithTag("video-choose").performScrollTo().performClick()
        ui.onNodeWithTag("background-save").performScrollTo().performClick()
        ui.runOnIdle{assertEquals(BackgroundMode.VIDEO,selection.active);assertEquals("content://owned-test/360.mp4",selection.activeVideoUri);assertEquals(0,connectionWrites)}
    }
}
