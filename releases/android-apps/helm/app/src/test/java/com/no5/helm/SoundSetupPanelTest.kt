package com.no5.helm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import com.no5.domain.AudioChannel
import com.no5.domain.AudioFixture
import com.no5.domain.MockAudioSource
import com.no5.domain.AudioSourcePort
import com.no5.domain.MutableSourceFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w2000dp-h1200dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SoundSetupPanelTest {
    @get:Rule val ui = createComposeRule()

    @Test fun actualSelectionSurvivesClosingReopeningAndHelmUsingTheSameAudioController() {
        val source = MockAudioSource(now = { 10_000L }).also { it.loadFixture(AudioFixture.DEMO) }
        val controller = AudioNativeController(now={10_000L},automaticTicker=false,automaticVerification=false,source=source)
        controller.openPanel("mixer")
        val page=androidx.compose.runtime.mutableStateOf("AUDIO")
        val actor=AudioControlActor(controller)
        ui.setContent { MaterialTheme { Box(Modifier.size(AudioNativeGeometry.soundSetup.width.dp, AudioNativeGeometry.soundSetup.height.dp)) {
            ObserveAudioKnobActor(actor,controller,page.value,1L)
            SoundSetupPanel(1f,false,controller,AudioPresentationSettings.DEFAULT,{},controller::closePanel,Modifier.fillMaxSize())
        } } }
        try {
            ui.onNodeWithTag("sound-setup-knob-SUB").performScrollTo().performClick()
            ui.runOnIdle { actor.action(com.no5.control.NormalizedKnobInput.LEFT_CCW).submit(1,android.os.SystemClock.uptimeMillis()) }
            ui.runOnIdle {
                val pending=source.state.value.pending!!
                assertEquals(com.no5.domain.AudioCommand.Fader(AudioChannel.SUB,-7.0),pending.command)
                assertEquals(-24.0,source.state.value.dsp.faders.getValue(AudioChannel.MASTER).db!!,0.0)
                source.completePending(pending.requestId,true)
                controller.closePanel()
            }
            ui.waitForIdle()
            ui.runOnIdle { actor.action(com.no5.control.NormalizedKnobInput.LEFT_CW).submit(2,android.os.SystemClock.uptimeMillis()) }
            ui.runOnIdle {
                val pending=source.state.value.pending!!
                assertEquals(com.no5.domain.AudioCommand.Fader(AudioChannel.SUB,-6.0),pending.command)
                source.completePending(pending.requestId,true)
                controller.openPanel("mixer")
                assertEquals(controller.faderControl(AudioChannel.SUB)!!.physicalIndex,controller.selectedOutput)
                controller.closePanel();page.value="HELM"
            }
            ui.waitForIdle()
            ui.runOnIdle { actor.action(com.no5.control.NormalizedKnobInput.LEFT_CW).submit(3,android.os.SystemClock.uptimeMillis()) }
            ui.runOnIdle {
                assertEquals(com.no5.domain.AudioCommand.Fader(AudioChannel.SUB,-5.0),source.state.value.pending!!.command)
                assertEquals(-24.0,source.state.value.dsp.faders.getValue(AudioChannel.MASTER).db!!,0.0)
            }
        } finally { ui.runOnIdle {actor.close();controller.dispose()} }
    }

    @Test fun savePersistsLocalAliasAndVisibilityWithoutChangingTheGatewayId() {
        val source = MockAudioSource(now = { 10_000L }).also { it.loadFixture(AudioFixture.DEMO) }
        val controller = AudioNativeController(automaticTicker = false, automaticVerification = false, source = source)
        var saved = AudioPresentationSettings.DEFAULT
        ui.setContent { MaterialTheme { Box(Modifier.size(AudioNativeGeometry.soundSetup.width.dp, AudioNativeGeometry.soundSetup.height.dp)) {
            SoundSetupPanel(1f, false, controller, saved, { saved = it }, {}, Modifier.fillMaxSize())
        } } }

        editAlias("MASTER","Cabin volume")
        ui.onNodeWithTag("sound-setup-visible-AUX4").performScrollTo().performClick()
        ui.onNodeWithTag("sound-setup-save").performClick()

        ui.runOnIdle {
            assertEquals("Cabin volume", saved.alias(AudioChannel.MASTER))
            assertFalse(saved.visible(AudioChannel.AUX4))
            controller.updatePresentation(saved)
            assertEquals(AudioChannel.MASTER, controller.masterControl()!!.gatewayId)
            controller.dispose()
        }
    }

    @Test fun closeRequiresExplicitDiscardAndDoesNotCallSave() {
        val source = MockAudioSource(now = { 10_000L }).also { it.loadFixture(AudioFixture.DEMO) }
        val controller = AudioNativeController(automaticTicker = false, automaticVerification = false, source = source)
        var persisted: AudioPresentationSettings? = null
        var closed = 0
        ui.setContent { MaterialTheme { Box(Modifier.size(AudioNativeGeometry.soundSetup.width.dp, AudioNativeGeometry.soundSetup.height.dp)) {
            SoundSetupPanel(1f, false, controller, AudioPresentationSettings.DEFAULT, { persisted = it }, { closed++ }, Modifier.fillMaxSize())
        } } }

        editAlias("MASTER","Discarded")
        ui.onNodeWithTag("sound-setup-close").performClick()
        ui.runOnIdle { assertNull(persisted); assertEquals(0, closed) }
        focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("unsaved-changes-continue").assertIsEnabled().performClick()
        focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("sound-setup-alias-MASTER").assertContentDescriptionEquals(com.no5.ui.No5Text.ui("Name for %s", "Discarded"))
        ui.onNodeWithTag("sound-setup-close").performClick()
        focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("unsaved-changes-discard").assertIsEnabled().performClick()
        focusHelmTestForegroundWindow(ui)
        ui.runOnIdle { assertNull(persisted); assertEquals(1, closed); controller.dispose() }
    }

    @Test fun navigationSaveFailureKeepsDraftAndOnlySuccessfulRetryRunsContinuation() {
        val source = MockAudioSource(now = { 10_000L }).also { it.loadFixture(AudioFixture.DEMO) }
        val controller = AudioNativeController(automaticTicker = false, automaticVerification = false, source = source)
        var attempts = 0
        var persisted: AudioPresentationSettings? = null
        var navigations = 0
        var guard: ((() -> Unit) -> Unit)? = null
        ui.setContent { MaterialTheme { Box(Modifier.size(AudioNativeGeometry.soundSetup.width.dp, AudioNativeGeometry.soundSetup.height.dp)) {
            SoundSetupPanel(1f, false, controller, AudioPresentationSettings.DEFAULT,
                { if (++attempts == 1) error("disk failure") else persisted = it }, {}, Modifier.fillMaxSize(),
                registerNavigationGuard = { guard = it })
        } } }
        editAlias("MASTER","Retained")
        ui.runOnIdle { requireNotNull(guard).invoke { navigations++ } }
        focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("unsaved-changes-save").assertIsEnabled().performClick()
        focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("unsaved-changes-error").assertExists()
        ui.runOnIdle { assertEquals(1, attempts); assertEquals(0, navigations); assertNull(persisted) }
        focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("unsaved-changes-save").assertIsEnabled().performClick()
        focusHelmTestForegroundWindow(ui)
        ui.runOnIdle { assertEquals(2, attempts); assertEquals(1, navigations); assertEquals("Retained", persisted!!.alias(AudioChannel.MASTER)); controller.dispose() }
    }

    @Test fun confirmedLiveFaderSelectionIsNotAnUnsavedDisplayDraft() {
        val source = MockAudioSource(now = { 10_000L }).also { it.loadFixture(AudioFixture.DEMO) }
        val controller = AudioNativeController(automaticTicker = false, automaticVerification = false, source = source)
        var closed = 0
        ui.setContent { MaterialTheme { Box(Modifier.size(AudioNativeGeometry.soundSetup.width.dp, AudioNativeGeometry.soundSetup.height.dp)) {
            SoundSetupPanel(1f, false, controller, AudioPresentationSettings.DEFAULT, {}, { closed++ }, Modifier.fillMaxSize())
        } } }
        ui.onNodeWithTag("sound-setup-knob-SUB").performScrollTo().performClick()
        ui.onNodeWithTag("sound-setup-close").performClick()
        ui.runOnIdle { assertEquals(1, closed); controller.dispose() }
    }

    @Test fun controllerPanelSwitchUsesTheSameGuardAndDoesNotCloseItsNewPanel() {
        val source = MockAudioSource(now = { 10_000L }).also { it.loadFixture(AudioFixture.DEMO) }
        val controller = AudioNativeController(automaticTicker = false, automaticVerification = false, source = source)
        controller.openPanel("mixer")
        ui.setContent { MaterialTheme { Box(Modifier.size(AudioNativeGeometry.soundSetup.width.dp, AudioNativeGeometry.soundSetup.height.dp)) {
            SoundSetupPanel(1f, false, controller, AudioPresentationSettings.DEFAULT, {}, controller::closePanel,
                Modifier.fillMaxSize(), registerNavigationGuard = { controller.panelNavigationGuard = it })
        } } }
        editAlias("MASTER","Retained alias")
        ui.runOnIdle { controller.openPanel("media"); assertEquals("mixer", controller.panel) }
        focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("unsaved-changes-continue").assertIsEnabled().performClick()
        focusHelmTestForegroundWindow(ui)
        ui.runOnIdle { assertEquals("mixer", controller.panel); controller.openPanel("media") }
        focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("unsaved-changes-discard").assertIsEnabled().performClick()
        focusHelmTestForegroundWindow(ui)
        ui.runOnIdle { assertEquals("media", controller.panel); controller.dispose() }
    }

    @Test fun emptyAliasAndVisibilityRestoreDefaultsWithoutChangingSound() {
        val source=MockAudioSource(now={10_000L}).also{it.loadFixture(AudioFixture.DEMO)}
        val controller=AudioNativeController(automaticTicker=false,automaticVerification=false,source=source)
        val original=AudioPresentationSettings.DEFAULT.update(AudioChannel.REAR,"Aft",false)
        var saved:AudioPresentationSettings?=null
        ui.setContent { MaterialTheme { Box(Modifier.size(800.dp,620.dp)) {
            SoundSetupPanel(1f,false,controller,original,{saved=it},{},Modifier.fillMaxSize())
        } } }
        editAlias("REAR","")
        ui.onNodeWithTag("sound-setup-visible-REAR").performScrollTo().performClick()
        ui.onNodeWithTag("sound-setup-save").performClick()
        ui.runOnIdle{assertEquals(AudioPresentationSettings.DEFAULT,saved);assertNull(source.state.value.pending);controller.dispose()}
    }

    @Test fun compactEditorHasOneCloseAndNoDuplicatedLivePresetOrDiagnosticControls() {
        val source = MockAudioSource(now = { 10_000L }).also { it.loadFixture(AudioFixture.DEMO) }
        val controller = AudioNativeController(automaticTicker = false, automaticVerification = false, source = source)
        ui.setContent { MaterialTheme { Box(Modifier.size(AudioNativeGeometry.soundSetup.width.dp, AudioNativeGeometry.soundSetup.height.dp)) {
            SoundSetupPanel(1f, false, controller, AudioPresentationSettings.DEFAULT, {}, {}, Modifier.fillMaxSize())
        } } }
        ui.onNodeWithTag("sound-setup-close").assertExists()
        ui.onNodeWithTag("sound-setup-cancel").assertDoesNotExist()
        ui.onNodeWithTag("sound-setup-apply-demo-stereo-4-1").assertDoesNotExist()
        ui.onNodeWithTag("sound-setup-last-command").assertDoesNotExist()
        ui.onNodeWithTag("sound-setup-command-gates").assertDoesNotExist()
        ui.onNodeWithTag("sound-setup-technical-MASTER").assertDoesNotExist()
        ui.onNodeWithTag("sound-setup-details").performClick()
        focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("sound-setup-technical-MASTER").performScrollTo().assertExists()
        ui.onNodeWithTag("sound-setup-technical-SUB").assertDoesNotExist()
        ui.onNodeWithTag("sound-setup-technical-id").assertContentDescriptionEquals("Stabile Kanal-ID. master")
        ui.runOnIdle { assertNull(source.state.value.pending); controller.dispose() }
    }

    @Test fun compactRowsExposeDistinctAccessibleChannelNamesAndSelectedKnob() {
        val source = MockAudioSource(now = { 10_000L }).also { it.loadFixture(AudioFixture.DEMO) }
        val controller = AudioNativeController(automaticTicker = false, automaticVerification = false, source = source)
        ui.setContent { MaterialTheme { Box(Modifier.size(AudioNativeGeometry.soundSetup.width.dp, AudioNativeGeometry.soundSetup.height.dp)) {
            SoundSetupPanel(1f, false, controller, AudioPresentationSettings.DEFAULT, {}, {}, Modifier.fillMaxSize())
        } } }
        val master = controller.masterControl()!!
        val sub = controller.faderControl(AudioChannel.SUB)!!
        ui.onNodeWithTag("sound-setup-alias-MASTER").assertContentDescriptionEquals(com.no5.ui.No5Text.ui("Name for %s", controller.displayName(master)))
        ui.onNodeWithTag("sound-setup-visible-MASTER").assertContentDescriptionEquals(com.no5.ui.No5Text.ui("Show %s", controller.displayName(master)))
        ui.onNodeWithTag("sound-setup-knob-MASTER").assertIsSelected()
        ui.onNodeWithTag("sound-setup-knob-SUB").performScrollTo().performClick()
            .assertIsSelected().assertContentDescriptionEquals(com.no5.ui.No5Text.ui("Assign %s to left knob", controller.displayName(sub)))
        ui.onNodeWithTag("sound-setup-knob-MASTER").performScrollTo().assertIsNotSelected()
        ui.runOnIdle { assertNull(source.state.value.pending); controller.dispose() }
    }

    @Test fun actualEditorAndOutsideCloseUseTheSameSmallerStageBounds() {
        val source = MockAudioSource(now = { 10_000L }).also { it.loadFixture(AudioFixture.DEMO) }
        val controller = AudioNativeController(automaticTicker = false, automaticVerification = false, source = source)
        controller.openPanel("mixer")
        ui.setContent { MaterialTheme { Box(Modifier.size(2000.dp, 1200.dp).testTag("audio-editor-stage")) {
            AudioScreen(1f, false, controller)
        } } }
        val expected = AudioNativeGeometry.soundSetup
        val old = AudioNativeGeometry.drawer
        val actual = ui.onNodeWithTag("sound-setup-panel").fetchSemanticsNode().boundsInRoot
        assertEquals(expected.x, actual.left, 0.1f)
        assertEquals(expected.y, actual.top, 0.1f)
        assertEquals(expected.width, actual.width, 0.1f)
        assertEquals(expected.height, actual.height, 0.1f)
        assertTrue(expected.width * expected.height < old.width * old.height * 0.8f)
        assertFalse(expected.overlaps(AudioNativeGeometry.masterSlider))
        // This point belonged to the old editor. It is now outside and must
        // close through the same scrim, without falling through to AUDIO.
        ui.onNodeWithTag("audio-editor-stage").performTouchInput { click(Offset(old.x + 10f, old.y + 10f)) }
        ui.runOnIdle { assertNull(controller.panel); assertNull(source.state.value.pending); controller.dispose() }
    }

    @Test @Config(sdk = [29], qualifiers = "en-rUS-w1000dp-h600dp-land-xhdpi")
    fun fourActualChannelsFitWithoutScrollingAtLuminaDensityInEveryLanguageAndTheme() {
        val delegate = MockAudioSource(now = { 10_000L }).also { it.loadFixture(AudioFixture.DEMO) }
        val base = delegate.state.value
        val channels = listOf(AudioChannel.MASTER, AudioChannel.FRONT, AudioChannel.REAR, AudioChannel.SUB)
        val labels = listOf("Master", "Front", "Rear", "Sub")
        val catalog = channels.mapIndexed { index, channel ->
            base.dsp.faderControls[index].copy(gatewayId = channel, physicalIndex = index, label = labels[index])
        }
        val source = object : AudioSourcePort by delegate {
            override val state = MutableSourceFeed(base.copy(dsp = base.dsp.copy(faderControls = catalog)))
        }
        val controller = AudioNativeController(now = { 10_000L }, automaticTicker = false, automaticVerification = false, source = source)
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val originalLanguage = com.no5.ui.UiLanguage.current(context)
        val language = androidx.compose.runtime.mutableStateOf("de")
        val night = androidx.compose.runtime.mutableStateOf(false)
        // User names may wrap; complete accessibility names and fixed row geometry are retained.
        val saved = channels.fold(AudioPresentationSettings.DEFAULT) { state, channel ->
            state.update(channel, "Long local channel display name / Plaaslike kanaalnaam / Anzeigename ".repeat(2).take(80), true)
        }
        ui.setContent { MaterialTheme {
            androidx.compose.runtime.key(language.value, night.value) {
                Box(Modifier.size(400.dp, 310.dp)) {
                    SoundSetupPanel(.5f, night.value, controller, saved, {}, {}, Modifier.fillMaxSize())
                }
            }
        } }
        val geometry = linkedMapOf<String, androidx.compose.ui.geometry.Rect>()
        try {
            for (tag in listOf("de", "en", "af")) for (isNight in listOf(false, true)) {
                ui.runOnIdle {
                    com.no5.ui.UiLanguage.acceptConfirmed(context, tag)
                    language.value = tag; night.value = isNight
                }
                val panel = ui.onNodeWithTag("sound-setup-panel").fetchSemanticsNode().boundsInRoot
                assertEquals("Lumina physical width", 800f, panel.width, .5f)
                assertEquals("Lumina physical height", 620f, panel.height, .5f)
                val footer=ui.onNodeWithTag("sound-setup-close").fetchSemanticsNode().boundsInRoot
                for (channel in channels) for (kind in listOf("alias", "visible", "knob")) {
                    val id = "sound-setup-$kind-${channel.name}"
                    val node = ui.onNodeWithTag(id).assertIsDisplayed().fetchSemanticsNode()
                    val bounds = node.boundsInRoot
                    assertTrue("$tag night=$isNight $id < 48 dp: $bounds", bounds.width >= 95.5f && bounds.height >= 95.5f)
                    assertTrue("$tag night=$isNight $id clipped by footer: $bounds", bounds.bottom <= footer.top + .5f)
                    assertTrue("$tag night=$isNight $id outside panel: $bounds", panel.contains(bounds.topLeft) && panel.contains(bounds.bottomRight))
                    geometry[id]?.let { assertEquals("Language/night changed editor geometry: $id", it, bounds) }
                    geometry.putIfAbsent(id, bounds)
                }
                for (id in listOf("sound-setup-close", "sound-setup-details", "sound-setup-save")) {
                    val bounds = ui.onNodeWithTag(id).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertTrue("$id < 48 dp: $bounds", bounds.width >= 95.5f && bounds.height >= 95.5f)
                }
                val scroll = ui.onNodeWithTag("sound-setup-channel-table").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                assertEquals("Four ordinary channels require scrolling", 0f, scroll.maxValue(), .5f)
            }
            // The approved detail sheet describes the explicitly chosen channel.
            // The compact overview still exposes every channel above.
            ui.onNodeWithTag("sound-setup-knob-SUB").performClick()
            ui.onNodeWithTag("sound-setup-details").performClick()
            focusHelmTestForegroundWindow(ui)
            ui.onNodeWithTag("sound-setup-technical-SUB").performScrollTo().assertIsDisplayed()
            ui.onNodeWithTag("sound-setup-technical-MASTER").assertDoesNotExist()
            ui.runOnIdle { assertNull(source.state.value.pending) }
        } finally {
            ui.runOnIdle { controller.dispose(); com.no5.ui.UiLanguage.acceptConfirmed(context, originalLanguage) }
        }
    }
    private fun editAlias(channel:String,value:String) {
        ui.onNodeWithTag("sound-setup-alias-$channel").performScrollTo().performClick()
        focusHelmTestForegroundWindow(ui)
        ui.onNodeWithTag("sound-setup-name-input").performTextReplacement(value)
        ui.onNodeWithTag("sound-setup-name-apply").performClick()
        focusHelmTestForegroundWindow(ui)
    }

}
