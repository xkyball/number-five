package com.no5.helm

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import com.no5.helm.network.soundFixture
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal fun nativeWorkspaceViews(view:View):List<View> = listOf(view)+
    if(view is ViewGroup)(0 until view.childCount).flatMap{nativeWorkspaceViews(view.getChildAt(it))}else emptyList()

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioWorkspaceNavigationTest {
    @get:Rule val ui=createComposeRule()
    @Test fun realAudioEntryReachesEverySettingsTargetAndReturnsWithoutWritingSound(){
        com.no5.ui.UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")
        val port=object:AudioSoundPort {
            override val state=MutableSourceFeed(soundFixture())
            override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null)
            override val results=MutableSourceFeed<AudioSoundResult?>(null)
            override fun submit(command:AudioSoundCommand):Long=error("Navigation must not write sound")
            override fun setEditorVisible(visible:Boolean,eqVisible:Boolean)=Unit
            override fun refresh()=Unit
            override fun close()=Unit
        }
        val delegate=MockAudioSource(now={10000}).also{it.loadFixture(AudioFixture.DEMO)}
        val source=object:AudioSourcePort by delegate {override val sound=port}
        val audio=AudioNativeController(Dispatchers.Unconfined,{10000},false,false,source)
        var diagnostics=0
        ui.setContent{MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){
            AudioScreen(.5f,false,audio,onOpenDiagnostics={diagnostics++})
        }}}
        fun clickNative(tag:String){ui.runOnIdle{
            val root=(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity.window.decorView
            nativeWorkspaceViews(root).single{it.tag==tag}.also{assertTrue(it.isEnabled)}.performClick()
        };ui.waitForIdle()}
        try {
            ui.onNodeWithContentDescription("Audio einrichten").performClick()
            ui.onNodeWithTag("audio-settings-menu").assertIsDisplayed()
            clickNative("no5-row-audio-menu-presets")
            ui.onNodeWithTag("audio-preset-panel").assertIsDisplayed()
            clickNative("no5-row-audio-open-preset-editor")
            ui.onNodeWithTag("audio-sound-editor").assertIsDisplayed()
            ui.runOnIdle{audio.closeSoundPanel()}
            ui.onNodeWithTag("audio-preset-panel").assertIsDisplayed()
            clickNative("no5-workspace-back")
            ui.onNodeWithTag("audio-settings-menu").assertIsDisplayed()
            clickNative("no5-row-audio-menu-speakers")
            ui.onNodeWithTag("sound-speaker-SUB").assertIsDisplayed()
            ui.runOnIdle{audio.closeSoundPanel()}
            ui.onNodeWithTag("audio-settings-menu").assertIsDisplayed()
            clickNative("no5-row-audio-menu-faders")
            ui.onNodeWithTag("sound-setup-save").assertIsDisplayed()
            ui.onNodeWithTag("sound-setup-close").performClick()
            ui.onNodeWithTag("audio-settings-menu").assertIsDisplayed()
            clickNative("no5-row-audio-menu-connection")
            assertEquals(1,diagnostics)
            clickNative("no5-workspace-back")
            ui.runOnIdle{assertNull(audio.panel);assertNull(audio.state.pending)}
        } finally {ui.runOnIdle{audio.dispose()}}
    }
}
