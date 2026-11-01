package com.no5.helm

import androidx.compose.ui.platform.LocalContext
import com.no5.ui.No5Text
import com.no5.ui.No5ActionFeedback
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.no5.domain.AudioFaderControl
import com.no5.domain.AudioPreset
import java.util.Locale
import com.no5.domain.AudioChannel

/** Only app-local aliases and visibility are retained; never live DSP values. */
private val AudioSetupDraftSaver = Saver<AudioPresentationSettings, ArrayList<String>>(
    save = { value -> ArrayList<String>().apply { value.faders.forEach { (channel, settings) ->
        add(channel.name); add(settings.alias.orEmpty()); add(settings.visible.toString())
    } } },
    restore = { rows -> runCatching {
        require(rows.size % 3 == 0)
        var restored = AudioPresentationSettings.DEFAULT
        rows.chunked(3).forEach { row -> restored = restored.update(AudioChannel.valueOf(row[0]), row[1], row[2].toBooleanStrict()) }
        restored
    }.getOrNull() },
)

/**
 * Compact app-local channel presentation editor. The gateway remains the
 * authority for channel identity; live levels and presets stay on AUDIO.
 */
@Composable
internal fun SoundSetupPanel(
    scale: Float,
    night: Boolean,
    controller: AudioNativeController,
    saved: AudioPresentationSettings,
    onSave: (AudioPresentationSettings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
    registerCloseRequest: (((() -> Unit)?) -> Unit) = {},
    registerNavigationGuard: ((((() -> Unit) -> Unit)?) -> Unit) = {},
) {
    var draft by rememberSaveable(saved, stateSaver = AudioSetupDraftSaver) { mutableStateOf(saved) }
    var error by remember(saved) { mutableStateOf<String?>(null) }
    var closeRequested by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var afterClose by remember { mutableStateOf<(() -> Unit)?>(null) }
    val dirty = draft != saved
    fun finishClose() {
        closeRequested = false
        val continuation = afterClose
        afterClose = null
        val previousPanel = controller.panel
        continuation?.invoke()
        // A confirmed internal switch owns the new panel. An ordinary close or
        // page departure still closes the old one after its guard succeeds.
        if (controller.panel == previousPanel) onClose()
    }
    fun discard() {
        draft = saved
        error = null
        finishClose()
    }
    fun update(control: AudioFaderControl, alias: String? = draft.alias(control.gatewayId), visible: Boolean = draft.visible(control.gatewayId)) {
        try {
            draft = draft.update(control.gatewayId, alias, visible)
            error = null
        } catch (_: IllegalArgumentException) {
            error = No5Text.ui("Display names must be 1–%d characters and cannot contain control characters.", AudioFaderPresentation.MAX_ALIAS_LENGTH)
        }
    }
    fun requestNavigation(continuation: () -> Unit) {
        if (saving) return
        afterClose = continuation
        if (draft != saved) closeRequested = true else finishClose()
    }
    fun requestClose() { requestNavigation {} }
    fun save() {
        if (saving) return
        if (draft == saved) { finishClose(); return }
        saving = true
        try {
            onSave(draft)
            error = null
            finishClose()
        } catch (_: RuntimeException) {
            error = No5Text.ui("The local display settings could not be saved. AUDIO controls are unchanged.")
        } finally { saving = false }
    }
    val latestCloseRequest by rememberCurrentReference(::requestClose)
    val latestRegister by rememberUpdatedState(registerCloseRequest)
    val latestNavigation by rememberCurrentReference(::requestNavigation)
    val latestNavigationRegister by rememberUpdatedState(registerNavigationGuard)
    DisposableEffect(Unit) {
        latestRegister { latestCloseRequest() }
        latestNavigationRegister { continuation -> latestNavigation(continuation) }
        onDispose { latestRegister(null); latestNavigationRegister(null) }
    }
    BackHandler { requestClose() }

    FaderAssignmentPanel(scale, night, controller, draft, dirty, error, ::requestClose, ::save, ::update, modifier)
    if (closeRequested) UnsavedChangesDialog(::save, ::discard, { closeRequested = false; afterClose = null }, saving = saving, error = error, night = night)
}

@Composable
internal fun AudioSettingsMenu(night:Boolean,controller:AudioNativeController,onClose:()->Unit,
                              onOpenDiagnostics:(()->Unit)?,modifier:Modifier) {
    BackHandler(onBack=onClose)
    val rows=listOf(
        com.no5.header.artwork.No5WorkspaceView.Row("Klangpresets","EQ und Raumwirkung",key="audio-menu-presets",action={controller.openPanel("preset")}),
        com.no5.header.artwork.No5WorkspaceView.Row("Lautsprecher abgleichen",
            if(controller.soundEditor.observationInk.available)"Hörplatz, Laufzeit und relativer Pegel"else"Verbindungsstatus beim Öffnen prüfen",
            enabled=!controller.soundEditor.busy,
            key="audio-menu-speakers",action={controller.openSoundEditor(true)}),
        com.no5.header.artwork.No5WorkspaceView.Row("Fader zuordnen","Sichtbarkeit und linker Knopf",key="audio-menu-faders",action={controller.openPanel("mixer")}),
        com.no5.header.artwork.No5WorkspaceView.Row("Verbindung und Diagnose","Technische Angaben nachgeordnet",enabled=onOpenDiagnostics!=null,key="audio-menu-connection",action=onOpenDiagnostics)
    )
    com.no5.ui.No5Workspace("Audio einrichten","Klang, Hörplatz und Bedienung.",rows,night,onClose,
        modifier.testTag("audio-settings-menu"))
}

private fun formatDb(value: Double?): String = value?.let { String.format(Locale.US, "%+.1f dB", it) } ?: "— dB"
