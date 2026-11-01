package com.no5.helm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import com.no5.ui.no5Clickable
import com.no5.ui.No5ActionFeedback
import com.no5.ui.LocalNo5ActionFeedbackColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox as MaterialCheckbox
import androidx.compose.material3.DropdownMenuItem as MaterialDropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.no5.domain.*
import com.no5.domain.SourceConfigValidator
import com.no5.domain.SourceMode
import com.no5.domain.WeatherProviderMode
import com.no5.helm.ocean.BackgroundModeControl
import com.no5.helm.ocean.BackgroundSelection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.no5.ui.No5Text

private val DisplayUnitsDraftSaver = listSaver<DisplayUnits, String>(
    save = { listOf(it.speed.name, it.consumption.name, it.depth.name) },
    restore = { parts -> runCatching { DisplayUnits(SpeedUnit.valueOf(parts[0]), ConsumptionUnit.valueOf(parts[1]), DepthUnit.valueOf(parts[2])) }.getOrNull() },
)

/** Native app configuration only. The host owns the header and footer outside y=112..1058. */
@Composable
fun SourceSettingsScreen(
    scale: Float,
    night: Boolean,
    config: SourceConfig,
    onApply: (SourceConfig) -> Unit,
    warning: String? = null,
    connectionStatus: String? = null,
    background: BackgroundSelection? = null,
    backgroundDescription: String = "",
    onSaveBackground: () -> Unit = {},
    onChooseVideo: () -> Unit = {},
    onRetryVideo: () -> Unit = {},
    videoDescription: String = "",
) = SourceSettingsScreenWithHistory(
    scale = scale,
    night = night,
    config = config,
    onApply = onApply,
    endpointHistory = emptyList(),
    warning = warning,
    connectionStatus = connectionStatus,
    background = background,
    backgroundDescription = backgroundDescription,
    onSaveBackground = onSaveBackground,
    onChooseVideo = onChooseVideo,
    onRetryVideo = onRetryVideo,
    videoDescription = videoDescription,
)

/** Existing SETTINGS composition; a host viewport can own its bounds without a second offset. */
@Composable
fun SourceSettingsScreenWithHistory(
    scale: Float,
    night: Boolean,
    config: SourceConfig,
    onApply: (SourceConfig) -> Unit,
    endpointHistory: List<String> = emptyList(),
    warning: String? = null,
    connectionStatus: String? = null,
    background: BackgroundSelection? = null,
    backgroundDescription: String = "",
    onSaveBackground: () -> Unit = {},
    onChooseVideo: () -> Unit = {},
    onRetryVideo: () -> Unit = {},
    videoDescription: String = "",
    gatewayKeyStatus: String? = null,
    onImportGatewayKey: ((deleteSource: Boolean) -> Unit)? = null,
    gatewayTrustStatus: String? = null,
    onImportGatewayTrust: (() -> Unit)? = null,
    onOpenGatewayConnection: (() -> Unit)? = null,
    bodyModifier: Modifier? = null,
    onLanguageSelected: (String) -> Unit = {},
    registerNavigationGuard: ((((() -> Unit) -> Unit)?) -> Unit) = {},
) {
    // The product route supplies a dedicated connection page.  Keep the
    // standalone editor below for configuration-store tests and embedders,
    // but never make the real Lumina SETTINGS page one long mixed form.
    if (onOpenGatewayConnection != null) {
        CompactSourceSettingsScreen(
            scale = scale,
            night = night,
            config = config,
            onApply = onApply,
            warning = warning,
            connectionStatus = connectionStatus,
            background = background,
            backgroundDescription = backgroundDescription,
            onSaveBackground = onSaveBackground,
            onChooseVideo = onChooseVideo,
            onRetryVideo = onRetryVideo,
            videoDescription = videoDescription,
            onOpenGatewayConnection = onOpenGatewayConnection,
            bodyModifier = bodyModifier,
            onLanguageSelected = onLanguageSelected,
            registerNavigationGuard = registerNavigationGuard,
        )
        return
    }
    var draftEndpoint by remember(config) { mutableStateOf(config.endpoint) }
    var applyError by remember(config) { mutableStateOf<String?>(null) }
    var draftWeather by rememberSaveable(config) { mutableStateOf(config.weatherProvider) }
    var draftConsent by rememberSaveable(config) { mutableStateOf(config.allowWeatherLocationSharing) }
    var draftUnits by rememberSaveable(config, stateSaver = DisplayUnitsDraftSaver) { mutableStateOf(config.displayUnits) }
    val draft = config.copy(mode = SourceMode.PI, endpoint = draftEndpoint, weatherProvider = draftWeather, allowWeatherLocationSharing = draftConsent, connectionEnabled = true, displayUnits = draftUnits)
    val validation = SourceConfigValidator.validate(draft)
    val dirty = draft != config
    val normalizedChange = validation.config != null && validation.config != config
    // A damaged saved record also needs an explicit repair even when the safe default is kept.
    val canApply = validation.isValid && (normalizedChange || warning != null)
    val focus = LocalFocusManager.current
    val uriHandler = LocalUriHandler.current
    fun revert() {
        draftUnits = config.displayUnits
        draftEndpoint = config.endpoint
        draftWeather = config.weatherProvider
        draftConsent = config.allowWeatherLocationSharing
        applyError = null
        focus.clearFocus()
    }
    fun apply() {
        val checked = SourceConfigValidator.validate(draft)
        if (!checked.isValid || !canApply) return
        focus.clearFocus()
        try {
            onApply(checked.config!!)
            applyError = null
        } catch (_: RuntimeException) {
            applyError = No5Text.ui("The app connection could not be saved. The previous source remains active.")
        }
    }
    fun revertAll() { revert(); background?.cancel() }
    val pendingChanges = dirty || applyError != null || background?.dirty == true || background?.error != null || background?.saving == true
    val closeGuard = rememberUnsavedChangesGuard(
        dirty = pendingChanges, busy = background?.saving == true, night = night,
        error = applyError ?: background?.error,
        onSave = {
            if (dirty && !canApply) false else {
                if (dirty) apply()
                applyError == null && saveBackgroundForClose(background, onSaveBackground)
            }
        }, onDiscard = ::revertAll,
        isDirty = { draftEndpoint != config.endpoint || draftWeather != config.weatherProvider ||
            draftConsent != config.allowWeatherLocationSharing || draftUnits != config.displayUnits ||
            applyError != null || background?.dirty == true || background?.error != null },
        isBusy = { background?.saving == true },
    )
    RegisterSettingsNavigationGuard(closeGuard, registerNavigationGuard)
    BackHandler(enabled = pendingChanges) { closeGuard {} }
    val palette = remember(night) { SettingsPalette(night) }
    val font = remember {
        FontFamily(
            Font(R.font.barlow_condensed_regular, FontWeight.Normal),
            Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
            Font(R.font.barlow_condensed_extrabold, FontWeight.ExtraBold),
        )
    }
    val bodySize = (30f * scale).coerceAtLeast(14f).sp
    val labelSize = (34f * scale).coerceAtLeast(16f).sp
    val padding = (44f * scale).coerceAtLeast(12f).dp
    val gap = (26f * scale).coerceAtLeast(10f).dp
    CompositionLocalProvider(LocalNo5ActionFeedbackColor provides palette.accent) {
    Box(
        (bodyModifier ?: Modifier.offset(y = (112f * scale).dp).size((2000f * scale).dp, (946f * scale).dp))
            .clipToBounds().background(palette.background).testTag("source-settings-body")
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape && pendingChanges) {
                    closeGuard {}; true
                } else false
            }
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(No5Text.text(LocalContext.current, "APP CONNECTION"), fontFamily = font, fontWeight = FontWeight.ExtraBold,
                        fontSize = (58f * scale).coerceAtLeast(26f).sp, color = palette.ink,
                        modifier = Modifier.semantics { heading() })
                    Text(No5Text.text(LocalContext.current, "Configures this app connection only. The Pi server is not changed."),
                        fontFamily = font, fontSize = bodySize, color = palette.secondary)
                    Text("APP ${BuildConfig.VERSION_NAME} · BUILD ${BuildConfig.VERSION_CODE}",
                        fontFamily = font, fontSize = (22f * scale).coerceAtLeast(12f).sp,
                        color = palette.secondary, modifier = Modifier.testTag("app-version"))
                }
                Surface(color = palette.panel, shape = RoundedCornerShape((12f * scale).dp), border = BorderStroke(1.dp, palette.outline)) {
                    Text(No5Text.text(LocalContext.current, if(config.connectionEnabled) "EXTERNAL GATEWAY" else "CONNECTIONS DISABLED"),
                        Modifier.padding(horizontal = (24f * scale).coerceAtLeast(10f).dp, vertical = (14f * scale).coerceAtLeast(8f).dp),
                        fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = bodySize, color = palette.accent)
                }
            }
            if (background != null) Surface(color = palette.panel,
                shape = RoundedCornerShape((20f * scale).coerceAtLeast(8f).dp), border = BorderStroke(1.dp, palette.outline)) {
                Column(Modifier.fillMaxWidth().padding((28f * scale).coerceAtLeast(12f).dp),
                    verticalArrangement = Arrangement.spacedBy((12f * scale).coerceAtLeast(6f).dp)) {
                    BackgroundModeControl(background.draft, backgroundDescription,
                        background::select, enabled = !background.saving, scale = scale, font = font)
                    if (background.draft == com.no5.helm.ocean.BackgroundMode.VIDEO) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            OutlinedButton(onClick=onChooseVideo,enabled=!background.saving,modifier=Modifier.testTag("video-choose")) { Text(No5Text.text(LocalContext.current, "Choose 360° video"),fontFamily=font,fontSize=bodySize) }
                            OutlinedButton(onClick=onRetryVideo,modifier=Modifier.testTag("video-retry")) { Text(No5Text.text(LocalContext.current, "Retry video"),fontFamily=font,fontSize=bodySize) }
                        }
                        Text(No5Text.text(LocalContext.current, if(background.draftVideoUri==null) "No video selected. Choose a licensed local 2:1 equirectangular video." else "Video selected · Save background to apply."),fontFamily=font,fontSize=bodySize)
                        Text(videoDescription,fontFamily=font,fontSize=bodySize)
                        Text(No5Text.text(LocalContext.current, "Recorded scene, no live geography. Muted repeat; the loop cut may be visible. Heading is relative to the video's starting direction. Weather fog is a visual overlay demo; the recording stays unchanged."),fontFamily=font,fontSize=bodySize)
                    }
                    background.error?.let { Text(No5Text.text(LocalContext.current, it),
                        Modifier.testTag("background-error").semantics { liveRegion = LiveRegionMode.Polite },
                        fontFamily = font, fontSize = bodySize, color = palette.warning) }
                    Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { closeGuard {} }, enabled = !background.saving && (background.dirty || background.error != null),
                            modifier = Modifier.heightIn(min = (76f * scale).coerceAtLeast(48f).dp).testTag("background-cancel")) {
                            Text(No5Text.text(LocalContext.current, "Cancel background"), fontFamily = font, fontSize = bodySize)
                        }
                        Button(onClick = onSaveBackground, enabled = !background.saving && background.dirty,
                            modifier = Modifier.heightIn(min = (76f * scale).coerceAtLeast(48f).dp).testTag("background-save")) {
                            Text(No5Text.text(LocalContext.current, if (background.saving) "Saving…" else "Save background"), fontFamily = font, fontSize = bodySize)
                        }
                        Text(No5Text.text(LocalContext.current, "Saved separately from app connection and weather."), Modifier.weight(1f),
                            fontFamily = font, fontSize = bodySize, color = palette.secondary)
                    }
                }
            }
            if (onOpenGatewayConnection == null) {
            Column(verticalArrangement = Arrangement.spacedBy((10f * scale).coerceAtLeast(5f).dp)) {
                OutlinedTextField(
                    value = draftEndpoint,
                    onValueChange = { draftEndpoint = it; applyError = null },
                    label = { Text(No5Text.text(LocalContext.current, "Pi endpoint"), fontFamily = font, fontSize = labelSize) },
                    placeholder = { Text("http://<pi-host>:<port>", fontFamily = font, fontSize = bodySize) },
                    modifier = Modifier.fillMaxWidth().testTag("source-endpoint")
                        .semantics { contentDescription = "Pi endpoint" },
                    singleLine = true,
                    isError = validation.error != null,
                    textStyle = TextStyle(fontFamily = font, fontSize = (36f * scale).coerceAtLeast(16f).sp, color = palette.ink),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    shape = RoundedCornerShape((16f * scale).coerceAtLeast(6f).dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = palette.ink, unfocusedTextColor = palette.ink,
                        focusedContainerColor = palette.panel, unfocusedContainerColor = palette.panel,
                        errorContainerColor = palette.panel, errorTextColor = palette.ink,
                        cursorColor = palette.accent, errorCursorColor = palette.warning,
                        focusedBorderColor = palette.accent, unfocusedBorderColor = palette.outline, errorBorderColor = palette.warning,
                        focusedLabelColor = palette.accent, unfocusedLabelColor = palette.secondary, errorLabelColor = palette.warning,
                        focusedPlaceholderColor = palette.secondary, unfocusedPlaceholderColor = palette.secondary,
                    ),
                )
                Text(validation.error ?: No5Text.text(LocalContext.current, "HTTP or HTTPS; no credentials, query or fragment. The default address is an editable example. Verify your Pi address."),
                    Modifier.semantics { if (validation.error != null) liveRegion = LiveRegionMode.Polite },
                    fontFamily = font, fontSize = bodySize, color = if (validation.error != null) palette.warning else palette.secondary)
            }
            if (endpointHistory.isNotEmpty()) {
                Surface(color = palette.panel, shape = RoundedCornerShape((20f * scale).coerceAtLeast(8f).dp), border = BorderStroke(1.dp, palette.outline)) {
                    Column(Modifier.fillMaxWidth().padding((28f * scale).coerceAtLeast(12f).dp),
                        verticalArrangement = Arrangement.spacedBy((10f * scale).coerceAtLeast(5f).dp)) {
                        Text(No5Text.text(LocalContext.current, "RECENT ADDRESSES"), fontFamily = font, fontWeight = FontWeight.SemiBold,
                            fontSize = labelSize, color = palette.accent)
                        Text(No5Text.text(LocalContext.current, "Choose a confirmed address, or enter a new one above. Save adds a valid address; the oldest of five is replaced."),
                            fontFamily = font, fontSize = bodySize, color = palette.secondary)
                        endpointHistory.forEachIndexed { index, endpoint ->
                            OutlinedButton(onClick = { draftEndpoint = endpoint; applyError = null }, modifier = Modifier.fillMaxWidth()
                                .heightIn(min = (68f * scale).coerceAtLeast(42f).dp).testTag("source-history-$index")
                                .semantics { contentDescription = "Recent Pi address ${index + 1}: $endpoint" },
                                border = BorderStroke(1.dp, if (draftEndpoint == endpoint) palette.accent else palette.outline),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.ink)) {
                                Text(endpoint, Modifier.fillMaxWidth(), fontFamily = font, fontSize = bodySize)
                            }
                        }
                    }
                }
            }
            if (onImportGatewayKey != null) {
                val validatedGatewayEndpoint = validation.config?.endpoint
                val gatewayKeyImportBlocker = when {
                    validatedGatewayEndpoint == null -> "Gateway key import unavailable: ${validation.error ?: "correct the Pi address and save it first."}"
                    validatedGatewayEndpoint != config.endpoint -> "Gateway key import unavailable: save the changed HTTPS gateway address first. A key file must match its exact saved scheme, host and port."
                    !validatedGatewayEndpoint.startsWith("https://") -> "Gateway key import unavailable: key files are bound to a saved HTTPS origin; the current address uses HTTP. Changing only HTTP to HTTPS enables this local origin gate, not a connection test."
                    else -> null
                }
                val keyImportAllowed = gatewayKeyImportBlocker == null
                var deleteGatewayKeySource by remember(config.endpoint) { mutableStateOf(false) }
                Surface(color = palette.panel, shape = RoundedCornerShape((20f * scale).coerceAtLeast(8f).dp), border = BorderStroke(1.dp, palette.outline)) {
                    Column(Modifier.fillMaxWidth().padding((28f * scale).coerceAtLeast(12f).dp),
                        verticalArrangement = Arrangement.spacedBy((10f * scale).coerceAtLeast(5f).dp)) {
                        Text(No5Text.text(LocalContext.current, "GATEWAY KEY FILE"), fontFamily = font, fontWeight = FontWeight.SemiBold,
                            fontSize = labelSize, color = palette.accent)
                        Text(No5Text.text(LocalContext.current, if (keyImportAllowed) "Import a user-provisioned key file for this exact saved HTTPS origin (scheme, host and port). The key is stored separately and does not prove a connection."
                            else "Key files need a saved HTTPS gateway origin; import and deletion confirmation stay disabled until then."),
                            fontFamily = font, fontSize = bodySize, color = palette.secondary)
                        gatewayKeyImportBlocker?.let { blocker ->
                            Text(blocker, Modifier.testTag("gateway-key-unavailable").semantics { liveRegion = LiveRegionMode.Polite },
                                fontFamily = font, fontSize = bodySize, color = palette.warning)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = deleteGatewayKeySource, enabled = keyImportAllowed,
                                onCheckedChange = { deleteGatewayKeySource = it }, modifier = Modifier.testTag("gateway-key-delete-source"))
                            Text(No5Text.text(LocalContext.current, "I confirm: after verified secure import, delete only the selected source file."),
                                fontFamily = font, fontSize = bodySize, color = palette.secondary)
                        }
                        OutlinedButton(onClick = { onImportGatewayKey(deleteGatewayKeySource) }, enabled = keyImportAllowed,
                            modifier = Modifier.heightIn(min = (76f * scale).coerceAtLeast(48f).dp).testTag("gateway-key-import"),
                            border = BorderStroke(1.dp, palette.outline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.ink, disabledContentColor = palette.outline)) {
                            Text(No5Text.text(LocalContext.current, if (deleteGatewayKeySource) "Choose key file and delete source" else "Choose gateway key file"),
                                fontFamily = font, fontSize = bodySize)
                        }
                        Text(gatewayKeyStatus ?: No5Text.text(LocalContext.current, "No connection test is run by importing a key."),
                            Modifier.testTag("gateway-key-status").semantics { liveRegion = LiveRegionMode.Polite },
                            fontFamily = font, fontSize = bodySize, color = if (gatewayKeyStatus == null) palette.secondary else palette.warning)
                    }
                }
            }
            if (onImportGatewayTrust != null) {
                val validatedGatewayEndpoint = validation.config?.endpoint
                val gatewayTrustImportBlocker = when {
                    validatedGatewayEndpoint == null -> "Gateway CA import unavailable: ${validation.error ?: "correct the Pi address and save it first."}"
                    validatedGatewayEndpoint != config.endpoint -> "Gateway CA import unavailable: save the changed HTTPS gateway address first. The CA is stored only for its exact saved scheme, host and port."
                    !validatedGatewayEndpoint.startsWith("https://") -> "Gateway CA import unavailable: CA certificates are stored only for a saved HTTPS origin; the current address uses HTTP. Changing only HTTP to HTTPS enables this local origin gate, not a connection test."
                    else -> null
                }
                val trustImportAllowed = gatewayTrustImportBlocker == null
                var confirmGatewayTrustOrigin by remember(config.endpoint) { mutableStateOf(false) }
                Surface(color = palette.panel, shape = RoundedCornerShape((20f * scale).coerceAtLeast(8f).dp), border = BorderStroke(1.dp, palette.outline)) {
                    Column(Modifier.fillMaxWidth().padding((28f * scale).coerceAtLeast(12f).dp),
                        verticalArrangement = Arrangement.spacedBy((10f * scale).coerceAtLeast(5f).dp)) {
                        Text(No5Text.text(LocalContext.current, "GATEWAY CA CERTIFICATE"), fontFamily = font, fontWeight = FontWeight.SemiBold,
                            fontSize = labelSize, color = palette.accent)
                        Text(No5Text.text(LocalContext.current, if (trustImportAllowed) "Choose the public CA certificate for this exact saved HTTPS origin. The certificate is stored separately and does not prove a connection."
                            else "CA certificates need a saved HTTPS gateway origin; selection and confirmation stay disabled until then."),
                            fontFamily = font, fontSize = bodySize, color = palette.secondary)
                        gatewayTrustImportBlocker?.let { blocker ->
                            Text(blocker, Modifier.testTag("gateway-ca-unavailable").semantics { liveRegion = LiveRegionMode.Polite },
                                fontFamily = font, fontSize = bodySize, color = palette.warning)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = confirmGatewayTrustOrigin, enabled = trustImportAllowed,
                                onCheckedChange = { confirmGatewayTrustOrigin = it }, modifier = Modifier.testTag("gateway-ca-confirm"))
                            Text(No5Text.text(LocalContext.current, "I confirm: this is a public CA certificate for the exact saved HTTPS gateway origin %s.", config.endpoint),
                                fontFamily = font, fontSize = bodySize, color = palette.secondary)
                        }
                        OutlinedButton(onClick = onImportGatewayTrust, enabled = trustImportAllowed && confirmGatewayTrustOrigin,
                            modifier = Modifier.heightIn(min = (76f * scale).coerceAtLeast(48f).dp).testTag("gateway-ca-import"),
                            border = BorderStroke(1.dp, palette.outline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.ink, disabledContentColor = palette.outline)) {
                            Text(No5Text.text(LocalContext.current, "Choose public CA certificate"), fontFamily = font, fontSize = bodySize)
                        }
                        Text(gatewayTrustStatus ?: No5Text.text(LocalContext.current, "No connection test is run by importing a CA certificate."),
                            Modifier.testTag("gateway-ca-status").semantics { liveRegion = LiveRegionMode.Polite },
                            fontFamily = font, fontSize = bodySize, color = if (gatewayTrustStatus == null) palette.secondary else palette.warning)
                    }
                }
            }
            } else Surface(color = palette.panel, shape = RoundedCornerShape((20f * scale).coerceAtLeast(8f).dp), border = BorderStroke(1.dp, palette.outline)) {
                Column(Modifier.fillMaxWidth().padding((28f * scale).coerceAtLeast(12f).dp), verticalArrangement = Arrangement.spacedBy((10f * scale).coerceAtLeast(5f).dp)) {
                    Text(No5Text.text(LocalContext.current, "PI CONNECTION"), fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = labelSize, color = palette.accent)
                    Text(No5Text.text(LocalContext.current, "Gateway address, key, public CA, saved-origin status, reload and read-only diagnostics are managed on their own page."), fontFamily = font, fontSize = bodySize, color = palette.secondary)
                    OutlinedButton(onClick = onOpenGatewayConnection, modifier = Modifier.testTag("open-gateway-connection")) {
                        Text(No5Text.text(LocalContext.current, "Open Pi connection"), fontFamily = font, fontSize = bodySize)
                    }
                }
            }
            Surface(color = palette.panel, shape = RoundedCornerShape((20f * scale).coerceAtLeast(8f).dp), border = BorderStroke(1.dp, palette.outline)) {
                Column(Modifier.fillMaxWidth().padding((28f * scale).coerceAtLeast(12f).dp), verticalArrangement = Arrangement.spacedBy((8f * scale).coerceAtLeast(4f).dp)) {
                    Text(No5Text.text(LocalContext.current, "NO5 GATEWAY · SHARED V1 API"), fontFamily = font, fontWeight = FontWeight.SemiBold,
                        fontSize = labelSize, color = palette.accent)
                    Text(No5Text.text(LocalContext.current, "The simulator and future Pi implement the same interface. Saving connection changes connects to this address; capabilities and source status determine what is available."),
                        fontFamily = font, fontSize = bodySize, color = palette.secondary)
                }
            }
            if (connectionStatus != null) Text(connectionStatus, fontFamily = font, fontSize = bodySize, color = palette.secondary)
            Text(No5Text.text(LocalContext.current, "DISPLAY UNITS"), fontFamily = font, fontWeight = FontWeight.ExtraBold,
                fontSize = labelSize, color = palette.ink)
            Text(No5Text.text(LocalContext.current, "Speed and distance"), fontFamily = font, fontSize = bodySize, color = palette.secondary)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                SpeedUnit.entries.forEach { unit ->
                    SettingsChoiceCard(unit.symbol, unit.distanceSymbol, draftUnits.speed == unit, Modifier.weight(1f), scale, font, palette, "Speed unit ${unit.symbol}") {
                        applyError = null
                        draftUnits = draftUnits.copy(speed = unit)
                    }
                }
            }
            Text(No5Text.text(LocalContext.current, "Consumption"), fontFamily = font, fontSize = bodySize, color = palette.secondary)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                ConsumptionUnit.entries.forEach { unit ->
                    SettingsChoiceCard(unit.symbol, when(unit) { ConsumptionUnit.LPH -> "Fuel flow"; ConsumptionUnit.L_PER_100KM -> "Fuel / distance"; else -> "Distance / fuel" }, draftUnits.consumption == unit, Modifier.weight(1f), scale, font, palette, "Consumption unit ${unit.symbol}") {
                        applyError = null
                        draftUnits = draftUnits.copy(consumption = unit)
                    }
                }
            }
            Text(No5Text.text(LocalContext.current, "Depth"), fontFamily = font, fontSize = bodySize, color = palette.secondary)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                DepthUnit.entries.forEach { unit ->
                    SettingsChoiceCard(unit.symbol, if (unit == DepthUnit.METERS) "Meters" else "Feet", draftUnits.depth == unit, Modifier.weight(1f), scale, font, palette, "Depth unit ${unit.symbol}") {
                        applyError = null
                        draftUnits = draftUnits.copy(depth = unit)
                    }
                }
            }
            Text(No5Text.text(LocalContext.current, "WEATHER PROVIDER"), fontFamily = font, fontWeight = FontWeight.ExtraBold,
                fontSize = labelSize, color = palette.ink)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                SettingsChoiceCard(No5Text.text(LocalContext.current, "Gateway weather"), "Independent gateway provider", draftWeather == WeatherProviderMode.SIMULATED,
                    Modifier.weight(1f), scale, font, palette) { draftWeather = WeatherProviderMode.SIMULATED }
                SettingsChoiceCard(No5Text.text(LocalContext.current, "Open-Meteo"), "Model forecast · Internet", draftWeather == WeatherProviderMode.OPEN_METEO,
                    Modifier.weight(1f), scale, font, palette) { draftWeather = WeatherProviderMode.OPEN_METEO }
                SettingsChoiceCard(No5Text.text(LocalContext.current, "Weather off"), "No weather requests", draftWeather == WeatherProviderMode.DISABLED,
                    Modifier.weight(1f), scale, font, palette) { draftWeather = WeatherProviderMode.DISABLED }
            }
            if (draftWeather == WeatherProviderMode.OPEN_METEO) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = draftConsent, onCheckedChange = { draftConsent = it },
                        modifier = Modifier.testTag("weather-location-consent"))
                    Text(No5Text.text(LocalContext.current, "Allow current boat-source latitude/longitude to be sent to Open-Meteo. Coordinates may remain in service logs for up to 90 days. No phone location permission is used."),
                        fontFamily = font, fontSize = bodySize, color = palette.secondary)
                }
                Text(No5Text.text(LocalContext.current, if (draftConsent) "Forecast model values, not onboard weather measurements. The selected boat source supplies the position."
                    else "Location sharing is off. Open-Meteo will remain unavailable until enabled and applied."),
                    fontFamily = font, fontSize = bodySize, color = palette.secondary)
                Row {
                    TextButton(onClick = { uriHandler.openUri("https://open-meteo.com/") }) { Text(No5Text.text(LocalContext.current, "Weather data: Open-Meteo"), fontFamily = font, fontSize = bodySize) }
                    TextButton(onClick = { uriHandler.openUri("https://open-meteo.com/en/terms") }) { Text(No5Text.text(LocalContext.current, "Terms / privacy"), fontFamily = font, fontSize = bodySize) }
                }
            }
            if (warning != null || applyError != null) {
                Text(sourceSettingsWarningLabel(applyError ?: warning.orEmpty()), Modifier.testTag("source-settings-warning").semantics { liveRegion = LiveRegionMode.Polite },
                    fontFamily = font, fontSize = bodySize, color = palette.warning)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { closeGuard {} }, enabled = dirty || applyError != null,
                    modifier = Modifier.heightIn(min = (84f * scale).coerceAtLeast(48f).dp).testTag("source-settings-cancel"),
                    border = BorderStroke(1.dp, palette.outline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.ink, disabledContentColor = palette.outline)) {
                    Text(No5Text.text(LocalContext.current, "Cancel changes"), fontFamily = font, fontSize = labelSize)
                }
                Button(onClick = ::apply, enabled = canApply,
                    modifier = Modifier.heightIn(min = (84f * scale).coerceAtLeast(48f).dp).testTag("source-settings-apply"),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.buttonInk,
                        disabledContainerColor = palette.disabled, disabledContentColor = palette.secondary)) {
                    Text(No5Text.text(LocalContext.current, "Save settings"), fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = labelSize)
                }
                Text(No5Text.text(LocalContext.current, if (dirty) "Unsaved app settings" else "Changes take effect after Save"),
                    Modifier.weight(1f), fontFamily = font, fontSize = bodySize, color = palette.secondary)
            }
        }
    }
    }
}

private enum class CompactSettingsDetail { MAIN, DISPLAY, WEATHER, BACKGROUND }

/**
 * The production SETTINGS route: a short map-style overview with focused
 * detail pages.  The scroll content never owns the destructive/apply actions;
 * they stay in the fixed bottom bar so keyboard and long-form content cannot
 * hide them on the Lumina landscape display.
 */
@Composable
private fun CompactSourceSettingsScreen(
    scale: Float,
    night: Boolean,
    config: SourceConfig,
    onApply: (SourceConfig) -> Unit,
    warning: String?,
    connectionStatus: String?,
    background: BackgroundSelection?,
    backgroundDescription: String,
    onSaveBackground: () -> Unit,
    onChooseVideo: () -> Unit,
    onRetryVideo: () -> Unit,
    videoDescription: String,
    onOpenGatewayConnection: () -> Unit,
    bodyModifier: Modifier?,
    onLanguageSelected: (String) -> Unit,
    registerNavigationGuard: ((((() -> Unit) -> Unit)?) -> Unit),
) {
    var draftWeather by rememberSaveable(config) { mutableStateOf(config.weatherProvider) }
    var draftConsent by rememberSaveable(config) { mutableStateOf(config.allowWeatherLocationSharing) }
    var draftUnits by rememberSaveable(config, stateSaver = DisplayUnitsDraftSaver) { mutableStateOf(config.displayUnits) }
    var applyError by remember(config) { mutableStateOf<String?>(null) }
    var detailName by rememberSaveable { mutableStateOf(CompactSettingsDetail.MAIN.name) }
    val detail = CompactSettingsDetail.entries.firstOrNull { it.name == detailName } ?: CompactSettingsDetail.MAIN
    // This page edits local presentation/privacy only. Gateway admission remains owned by
    // Lumina Control; opening or saving these fields must not enable a disabled connection.
    val draft = config.copy(weatherProvider = draftWeather,
        allowWeatherLocationSharing = draftConsent, displayUnits = draftUnits)
    val validation = SourceConfigValidator.validate(draft)
    val dirty = draft != config
    val canApply = validation.isValid && (validation.config != config || warning != null)
    val focus = LocalFocusManager.current
    fun cancelAppSettings() {
        draftWeather = config.weatherProvider
        draftConsent = config.allowWeatherLocationSharing
        draftUnits = config.displayUnits
        applyError = null
        focus.clearFocus()
    }
    fun applyAppSettings() {
        val checked = SourceConfigValidator.validate(draft)
        if (!checked.isValid || !canApply) return
        focus.clearFocus()
        try {
            onApply(checked.config!!)
            applyError = null
        } catch (_: RuntimeException) {
            applyError = No5Text.ui("The app settings could not be saved. The active connection is unchanged.")
        }
    }
    val pending = dirty || applyError != null || background?.dirty == true || background?.error != null || background?.saving == true
    val closeGuard = rememberUnsavedChangesGuard(
        dirty = pending, busy = background?.saving == true, error = applyError ?: background?.error, night = night,
        onSave = {
            if (dirty && !canApply) false else {
                if (dirty) applyAppSettings()
                applyError == null && saveBackgroundForClose(background, onSaveBackground)
            }
        },
        onDiscard = { cancelAppSettings(); background?.cancel() },
        isDirty = { draftWeather != config.weatherProvider || draftConsent != config.allowWeatherLocationSharing ||
            draftUnits != config.displayUnits || applyError != null || background?.dirty == true || background?.error != null },
        isBusy = { background?.saving == true },
    )
    RegisterSettingsNavigationGuard(closeGuard, registerNavigationGuard)
    fun requestDetailBack() { closeGuard { detailName = CompactSettingsDetail.MAIN.name; focus.clearFocus() } }
    BackHandler(enabled = detail != CompactSettingsDetail.MAIN || pending) {
        requestDetailBack()
    }

    val scope=rememberCoroutineScope()
    HelmSettingsWorkspace(detailName,night,draftUnits,draftWeather,draftConsent,
        {draftUnits=it;applyError=null},{draftWeather=it;applyError=null},{draftConsent=it;applyError=null},
        background,backgroundDescription,videoDescription,onChooseVideo,onRetryVideo,
        {closeGuard{onOpenGatewayConnection()}},{detailName=it},::requestDetailBack,
        {applyAppSettings();if(applyError==null)detailName=CompactSettingsDetail.MAIN.name},
        {scope.launch{if(saveBackgroundForClose(background,onSaveBackground))detailName=CompactSettingsDetail.MAIN.name}},
        canApply,applyError?:warning?.let(::sourceSettingsWarningLabel)?:background?.error?.let{No5Text.ui(it)},
        bodyModifier?:Modifier.offset(y=(130f*scale).dp).size((2000f*scale).dp,(928f*scale).dp))
}

/** Translate this known local warning only; remote errors and user data remain exact. */
internal fun sourceSettingsWarningLabel(message: String): String = when (message) {
    "Saved source settings could not be read safely. Connections are disabled. Review and explicitly apply the app connection settings." -> No5Text.ui(message)
    else -> message
}

/** Saving is complete only after the background owner's durable store result. */
private suspend fun saveBackgroundForClose(background: BackgroundSelection?, save: () -> Unit): Boolean {
    if (background == null) return true
    if (!background.dirty) return background.error == null
    try { save() } catch (_: RuntimeException) { return false }
    if (!background.saving) return !background.dirty && background.error == null
    snapshotFlow { background.saving }.first { !it }
    return !background.dirty && background.error == null
}

@Composable private fun RegisterSettingsNavigationGuard(
    guard: ((() -> Unit) -> Unit),
    register: ((((() -> Unit) -> Unit)?) -> Unit),
) {
    val latestGuard by rememberUpdatedState(guard)
    val latestRegister by rememberUpdatedState(register)
    DisposableEffect(Unit) {
        latestRegister { continuation -> latestGuard(continuation) }
        onDispose { latestRegister(null) }
    }
}



private fun weatherProviderTitle(value: WeatherProviderMode) = No5Text.ui(when (value) {
    WeatherProviderMode.SIMULATED -> "Gateway weather"
    WeatherProviderMode.OPEN_METEO -> "Open-Meteo"
    WeatherProviderMode.DISABLED -> "Weather off"
})

@Composable
private fun SettingsChoiceCard(label: String, detail: String, selected: Boolean, modifier: Modifier,
    scale: Float, font: FontFamily, palette: SettingsPalette, accessibilityLabel: String = "$label provider", onClick: () -> Unit) {
    Surface(modifier = modifier, color = if (selected) palette.selection else palette.panel,
        shape = RoundedCornerShape((20f * scale).coerceAtLeast(8f).dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) palette.accent else palette.outline)) {
        Row(Modifier.fillMaxWidth().heightIn(min = (154f * scale).coerceAtLeast(76f).dp)
            .no5Clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected; contentDescription = accessibilityLabel }
            .padding((20f * scale).coerceAtLeast(8f).dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((16f * scale).coerceAtLeast(6f).dp)) {
            RadioButton(selected = selected, onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = palette.accent, unselectedColor = palette.outline))
            Column(Modifier.weight(1f)) {
                Text(label, fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = (42f * scale).coerceAtLeast(20f).sp, color = palette.ink)
                Text(No5Text.text(LocalContext.current, detail), fontFamily = font, fontSize = (28f * scale).coerceAtLeast(13f).sp, color = palette.secondary)
            }
        }
    }
}

/** Preserve Material geometry; all momentary SETTINGS actions use the common interaction owner. */
@Composable private fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(), content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    No5ActionFeedback(enabled, shape = ButtonDefaults.shape) { feedback ->
        MaterialButton(onClick, modifier.then(feedback.modifier), enabled = feedback.enabled,
            interactionSource = feedback.interactionSource, colors = colors, content = content)
    }
}
@Composable private fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    border: BorderStroke? = null, colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    No5ActionFeedback(enabled, shape = ButtonDefaults.shape) { feedback ->
        if (border == null) MaterialOutlinedButton(onClick, modifier.then(feedback.modifier), enabled = feedback.enabled,
            interactionSource = feedback.interactionSource, colors = colors, content = content)
        else MaterialOutlinedButton(onClick, modifier.then(feedback.modifier), enabled = feedback.enabled,
            interactionSource = feedback.interactionSource, border = border, colors = colors, content = content)
    }
}
@Composable private fun TextButton(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    No5ActionFeedback(shape = ButtonDefaults.shape) { feedback ->
        MaterialTextButton(onClick, feedback.modifier, enabled = feedback.enabled,
            interactionSource = feedback.interactionSource, content = content)
    }
}
@Composable private fun DropdownMenuItem(text: @Composable () -> Unit, onClick: () -> Unit) {
    No5ActionFeedback { feedback ->
        MaterialDropdownMenuItem(text, onClick, modifier = feedback.modifier, enabled = feedback.enabled,
            interactionSource = feedback.interactionSource)
    }
}
@Composable private fun Checkbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    No5ActionFeedback(enabled, shape = androidx.compose.foundation.shape.CircleShape) { feedback ->
        MaterialCheckbox(checked, onCheckedChange, modifier = modifier.then(feedback.modifier),
            enabled = feedback.enabled, interactionSource = feedback.interactionSource)
    }
}

internal class SettingsPalette(night: Boolean) {
    val background = Color(if (night) 0xFF000000 else 0xFFF6F9FA)
    val panel = Color(if (night) 0xFF090000 else 0xFFFFFFFF)
    val selection = Color(if (night) 0xFF210000 else 0xFFEAF3F9)
    val ink = Color(if (night) 0xFFD40000 else 0xFF071022)
    val secondary = Color(if (night) 0xFFB00000 else 0xFF122653)
    val outline = Color(if (night) 0xFF700000 else 0xFF526B82)
    val accent = Color(if (night) 0xFFFF1A0A else 0xFF005DAF)
    val warning = Color(if (night) 0xFFFF1A0A else 0xFFAE3000)
    val disabled = Color(if (night) 0xFF240000 else 0xFFD9E2E8)
    val buttonInk = Color(if (night) 0xFF090000 else 0xFFFFFFFF)
}
