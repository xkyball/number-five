package com.no5.helm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import com.no5.ui.No5Text
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AudioNativeUiTest {
    @get:Rule val compose=createComposeRule()
    private fun display(c:AudioNativeController) {
        compose.setContent { MaterialTheme { Box(Modifier.size(1000.dp,600.dp)) { AudioScreen(.5f,false,c) } } }
    }
    private fun nativeTap(tag:String) {
        val bounds=android.graphics.Rect()
        compose.runOnIdle{
            val root=(compose as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity.window.decorView
            val target=requireNotNull(root.findViewWithTag<android.view.View>(tag))
            assertTrue(target.isEnabled);assertTrue(target.getGlobalVisibleRect(bounds))
        }
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val time=android.os.SystemClock.uptimeMillis()
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)){
            val event=android.view.MotionEvent.obtain(time,time+if(action==android.view.MotionEvent.ACTION_UP)20 else 0,action,bounds.exactCenterX(),bounds.exactCenterY(),0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            try{instrumentation.sendPointerSync(event)}finally{event.recycle()}
        }
        compose.waitForIdle()
    }
    @Test fun unavailableControlsHaveNoInAppSimulatorEntryPoint() {
        val c=AudioNativeController(automaticTicker=false,automaticVerification=false,source=MockAudioSource(now=System::currentTimeMillis));display(c)
        compose.onNodeWithContentDescription(No5Text.ui("Play")).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Open audio demo controls").assertDoesNotExist()
        compose.onNodeWithContentDescription("Start audio demo").assertDoesNotExist()
        compose.runOnIdle { c.dispose() }
    }
    @Test fun sourceCapabilitiesEnableNativeFader() {
        val c=AudioNativeController(automaticTicker=false,automaticVerification=false,source=MockAudioSource(now=System::currentTimeMillis))
        c.startDemo();display(c)
        compose.onNodeWithContentDescription(No5Text.ui("Pause")).assertIsEnabled()
        compose.onNodeWithContentDescription(No5Text.ui("Pegel %s","Master")).performSemanticsAction(SemanticsActions.SetProgress){it(-18f)}
        compose.runOnIdle { assertEquals(-18.0,c.state.dsp.faders.getValue(AudioChannel.MASTER).db!!,0.0);assertNotNull(c.state.pending);c.dispose() }
    }
    @Test fun soundSetupPreservesMasterAndHidesCoveredControls() {
        val c=AudioNativeController(automaticTicker=false,automaticVerification=false,source=MockAudioSource(now=System::currentTimeMillis));c.loadFixture(AudioFixture.DEMO);display(c)
        compose.onNodeWithContentDescription(No5Text.ui("Audio einrichten")).performClick()
        nativeTap("no5-row-audio-menu-faders")
        compose.onNodeWithContentDescription(No5Text.ui("Play")).assertDoesNotExist()
        compose.onNodeWithTag("sound-setup-panel").assertExists()
        compose.onNodeWithTag("sound-setup-apply-demo-stereo-4-1").assertDoesNotExist()
        compose.onNodeWithTag("sound-setup-command-gates").assertDoesNotExist()
        compose.onNodeWithTag("sound-setup-alias-MASTER").assertExists()
        compose.onNodeWithContentDescription(No5Text.ui("Pegel %s","Master")).assertIsEnabled()
        compose.onNodeWithTag("sound-setup-close").performClick()
        compose.onNodeWithTag("audio-settings-menu").assertExists()
        nativeTap("no5-workspace-back")
        compose.onNodeWithContentDescription(No5Text.ui("Play")).assertExists()
        compose.runOnIdle{c.dispose()}
    }
    @Test fun validatedPresetsUseTheSharedAudioSetupMenu() {
        val c=AudioNativeController(automaticTicker=false,automaticVerification=false,source=MockAudioSource(now=System::currentTimeMillis));c.loadFixture(AudioFixture.DEMO);display(c)
        compose.onNodeWithContentDescription(No5Text.ui("Audio einrichten")).performClick()
        nativeTap("no5-row-audio-menu-presets")
        compose.onNodeWithTag("audio-preset-panel").assertExists()
        nativeTap("no5-workspace-back")
        compose.onNodeWithTag("audio-settings-menu").assertExists()
        nativeTap("no5-workspace-back")
        compose.onNodeWithContentDescription(No5Text.ui("Play")).assertExists()
        compose.runOnIdle{c.dispose()}
    }
    @Test fun physicalTouchSeekCommitsUsingScaledStageCoordinates() {
        val c=AudioNativeController(automaticTicker=false,automaticVerification=false,source=MockAudioSource(now=System::currentTimeMillis));c.loadFixture(AudioFixture.DEMO);display(c)
        compose.onNodeWithContentDescription(No5Text.ui("Seek track")).performTouchInput { click(center) }
        compose.runOnIdle {
            assertTrue(c.state.media.positionMs!! in 120_000L..127_000L)
            assertTrue(c.state.pending?.command is AudioCommand.Transport)
            c.dispose()
        }
    }
    @Test fun physicalFaderReleaseQueuesAWriteInsteadOfOnlyChangingThePreview() {
        val c=AudioNativeController(automaticTicker=false,automaticVerification=false,source=MockAudioSource(now=System::currentTimeMillis));c.loadFixture(AudioFixture.DEMO);display(c)
        compose.onNodeWithContentDescription(No5Text.ui("Pegel %s","Master")).performTouchInput { click(androidx.compose.ui.geometry.Offset(width / 2f, 18f)) }
        compose.runOnIdle {
            assertTrue(c.state.pending?.command is AudioCommand.Fader)
            assertNotNull(c.lastOutcome)
            c.dispose()
        }
    }
}
