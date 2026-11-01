package com.no5.helm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w2000dp-h1200dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LegacyEqualizerRetirementTest {
    @get:Rule val ui=createComposeRule()
    private class Source(withPresets:Boolean):AudioSourcePort {
        override val identity=SourceIdentity("legacy-eq-retirement",SourceMode.DEMO,"EXPLICIT TEST")
        override val state=MutableSourceFeed(AudioModel.fixture(AudioFixture.DEMO).let { base ->
            base.copy(sessionId=identity.sessionId,capabilities=base.capabilities.copy(equalizer=true),
                dsp=base.dsp.copy(presets=if(withPresets)base.dsp.presets else emptyList(),
                    equalizer=AudioEqualizer("master","a".repeat(64),AudioEqualizer.FREQUENCIES_HZ,listOf(2.0,-1.0,0.0,3.0,-2.0),-6.0,6.0,1.0,0.0,true,1_000_000L)))
        })
        override val outcomes=MutableSourceFeed<AudioCommandOutcome?>(null)
        val commands=mutableListOf<AudioCommand>()
        override fun available(command:AudioCommand)=AudioModel.available(state.value,command)
        override fun submit(command:AudioCommand):AudioCommandOutcome {commands+=command;error("Retired EQ must not submit commands")}
        override fun tick()=Unit
        override fun close()=Unit
    }
    @Test fun legacyBankRemainsReadableButNeverAppearsAsAFallbackEditor() {
        val source=Source(true)
        val controller=AudioNativeController(Dispatchers.Unconfined,{1_000_000L},false,false,source)
        val original=controller.state.dsp
        ui.setContent { MaterialTheme { Box(Modifier.size(2000.dp,928.dp)) {
            AudioPresetPanel(1f,false,controller,{},Modifier.fillMaxSize())
        } } }
        try {
            ui.onNodeWithTag("audio-equalizer").assertDoesNotExist()
            ui.onNodeWithTag("equalizer-neutral").assertDoesNotExist()
            repeat(5){ui.onNodeWithTag("equalizer-band-$it").assertDoesNotExist()}
            ui.runOnIdle {
                val root=(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity.window.decorView
                val edit=nativeWorkspaceViews(root).single{it.tag=="no5-row-audio-open-preset-editor"}
                assertFalse("Legacy EQ capability must not enable the parametric editor",edit.isEnabled)
            }
            ui.runOnIdle {
                assertEquals(original,controller.state.dsp)
                assertTrue(source.commands.isEmpty())
                assertEquals(listOf(2.0,-1.0,0.0,3.0,-2.0),controller.equalizer!!.gainsDb)
            }
        } finally {ui.runOnIdle{controller.dispose()}}
    }
    @Test fun legacyCapabilityAloneDoesNotAdvertiseAnEditableToneRoute() {
        val controller=AudioNativeController(Dispatchers.Unconfined,{1_000_000L},false,false,Source(false))
        try {assertFalse(controller.toneAvailable);assertNotNull(controller.equalizer)}finally{controller.dispose()}
    }
}
