package com.no5.helm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import com.no5.ui.UiLanguage
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic audio source; tests spoken UI labels without translating actual source/user values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class AudioAccessibilityLocaleTest {
    @get:Rule val ui = createComposeRule()

    private class Fixture {
        val delegate = MockAudioSource(now = { 10_000L }).also { it.loadFixture(AudioFixture.DEMO) }
        val state = MutableSourceFeed(delegate.state.value.let { it.copy(
            media = it.media.copy(title = "Previous track", artists = listOf("Next track")), feedback = "Play") })
        val writes = mutableListOf<AudioCommand>()
        val source = object : AudioSourcePort by delegate {
            override val state: SourceFeed<AudioState> = this@Fixture.state
            override fun submit(command: AudioCommand): AudioCommandOutcome {
                writes += command
                return delegate.submit(command)
            }
        }
        val audio = AudioNativeController(Dispatchers.Unconfined, now = { 10_000L },
            automaticTicker = false, automaticVerification = false, source = source,
            initialPresentation = AudioPresentationSettings().update(AudioChannel.MASTER, "Play", true))
    }

    @Test fun spokenActionsReadoutsAndFaderStatesUseAllLocalesWhileSourceNamesStayExact() {
        val context = RuntimeEnvironment.getApplication()
        val original = UiLanguage.current(context)
        val fixture = Fixture()
        val epoch = mutableIntStateOf(0)
        ui.setContent { key(epoch.intValue) { MaterialTheme {
            Box(Modifier.size(1000.dp, 600.dp)) { AudioScreen(.5f, false, fixture.audio) }
        } } }
        data class Labels(val previous: String, val next: String, val play: String, val pause: String,
            val down: String, val level: String, val mute: String, val unmute: String, val seek: String,
            val position: String, val playing: String, val write: String, val simulated: String, val preview: String)
        val labels = mapOf(
            "de" to Labels("Vorheriger Titel", "Nächster Titel", "Abspielen", "Pause", "Master-Lautstärke senken",
                "Pegel Play", "Play stummschalten", "Play wieder einschalten", "Titelposition", "1:23 von 4:07",
                "Aktueller Titel", "Audio-Schreibstatus", " simulierter Wert", " Vorschau"),
            "en" to Labels("Previous track", "Next track", "Play", "Pause", "Master volume down",
                "Play level", "Mute Play", "Unmute Play", "Seek track", "1:23 of 4:07",
                "Now playing", "Audio write status", " simulated value", " preview"),
            "af" to Labels("Vorige snit", "Volgende snit", "Speel", "Onderbreek", "Verlaag hoofvolume",
                "Play-vlak", "Demp Play", "Ontdemp Play", "Snitposisie", "1:23 van 4:07",
                "Speel tans", "Oudio-skryfstatus", " gesimuleerde waarde", " voorskou"),
        )
        try {
            for ((language, text) in labels) {
                ui.runOnIdle { UiLanguage.acceptConfirmed(context, language); epoch.intValue++ }
                for (label in listOf(text.previous, text.next, text.play, text.down, text.mute))
                    ui.onNodeWithContentDescription(label).assertIsEnabled()
                ui.onNodeWithContentDescription("${text.playing}. Previous track. Next track. LOCAL DEMO").assertExists()
                ui.onNodeWithContentDescription("${text.write}. Play").assertExists()
                ui.onNodeWithContentDescription(text.seek).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text.position))
                ui.onNodeWithContentDescription(text.level).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "-24.0 dB${text.simulated}"))
                ui.runOnIdle { fixture.audio.previewLevel(AudioChannel.MASTER, -23.0) }
                ui.onNodeWithContentDescription(text.level).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "-23.0 dB${text.preview}"))
                ui.runOnIdle {
                    fixture.audio.cancelPreview()
                    fixture.state.emit(fixture.state.value.let { it.copy(media = it.media.copy(state = MediaState.PLAYING),
                        dsp = it.dsp.copy(faderControls = it.dsp.faderControls.map { fader ->
                            if (fader.gatewayId == AudioChannel.MASTER) fader.copy(muted = true) else fader })) })
                }
                ui.onNodeWithContentDescription(text.pause).assertIsEnabled()
                ui.onNodeWithContentDescription(text.unmute).assertIsEnabled()
                ui.runOnIdle {
                    fixture.state.emit(fixture.state.value.let { it.copy(media = it.media.copy(state = MediaState.PAUSED),
                        dsp = it.dsp.copy(faderControls = it.dsp.faderControls.map { fader ->
                            if (fader.gatewayId == AudioChannel.MASTER) fader.copy(muted = false) else fader })) })
                    assertTrue(fixture.writes.isEmpty())
                }
            }
        } finally { ui.runOnIdle { fixture.audio.dispose(); UiLanguage.acceptConfirmed(context, original) } }
    }

    @Test fun translatedSeekStillUsesItsOriginalFiveSecondKeyboardStep() {
        val context = RuntimeEnvironment.getApplication()
        val original = UiLanguage.current(context)
        UiLanguage.acceptConfirmed(context, "af")
        val fixture = Fixture()
        ui.setContent { MaterialTheme { Box(Modifier.size(1000.dp, 600.dp)) { AudioScreen(.5f, false, fixture.audio) } } }
        try {
            val seek = ui.onNodeWithContentDescription("Snitposisie")
            seek.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            seek.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
            ui.runOnIdle {
                assertEquals(listOf(AudioCommand.Transport(TransportAction.SEEK, 88_000L)), fixture.writes)
            }
        } finally { ui.runOnIdle { fixture.audio.dispose(); UiLanguage.acceptConfirmed(context, original) } }
    }
}
