package com.no5.helm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.no5.domain.SourceConfig
import com.no5.domain.SourceConfigValidator
import com.no5.domain.SourceMode
import com.no5.helm.diagnostics.GatewayDiagnosticEntry
import com.no5.helm.diagnostics.GatewayDiagnosticExportFormat
import com.no5.helm.diagnostics.GatewayDiagnosticFilter
import com.no5.helm.diagnostics.GatewayDiagnosticOutcome
import com.no5.helm.security.GatewayDraftOriginState
import com.no5.helm.security.GatewayMaterialState
import com.no5.helm.security.GatewayProfileState
import java.time.Instant
import kotlinx.coroutines.CoroutineScope

/** Dedicated native SETTINGS child page; it is the only user surface for Pi URL, material and tests. */
@Composable
fun GatewayConnectionScreen(
    scale: Float,
    night: Boolean,
    config: SourceConfig,
    endpointHistory: List<String>,
    diagnostics: GatewayConnectionDiagnosticsModel,
    gatewayKeyStatus: String?,
    gatewayTrustStatus: String?,
    activeConnectionStatus: String,
    scope: CoroutineScope,
    diagnosticTransferStatus: String?,
    onBack: () -> Unit,
    onSaveEndpoint: (SourceConfig) -> Unit,
    onImportGatewayKey: (deleteSource: Boolean) -> Unit,
    onImportGatewayTrust: () -> Unit,
    onReload: () -> Unit,
    onCopyDiagnostics: (ByteArray) -> Unit,
    onExportDiagnostics: (GatewayDiagnosticExportFormat, ByteArray) -> Unit,
) {
    BackHandler(onBack = onBack)
    var draftEndpoint by remember(config.endpoint) { mutableStateOf(config.endpoint) }
    var saveError by remember(config.endpoint) { mutableStateOf<String?>(null) }
    var deleteGatewayKeySource by remember(config.endpoint) { mutableStateOf(false) }
    var confirmGatewayTrustOrigin by remember(config.endpoint) { mutableStateOf(false) }
    var filter by remember { mutableStateOf(GatewayDiagnosticFilter.ALL) }
    val focus = LocalFocusManager.current
    val palette = remember(night) { GatewayPalette(night) }
    val font = remember { FontFamily(Font(R.font.barlow_condensed_regular, FontWeight.Normal), Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold), Font(R.font.barlow_condensed_extrabold, FontWeight.ExtraBold)) }
    val bodySize = (30f * scale).coerceAtLeast(14f).sp
    val labelSize = (34f * scale).coerceAtLeast(16f).sp
    val padding = (44f * scale).coerceAtLeast(12f).dp
    val gap = (22f * scale).coerceAtLeast(9f).dp
    val draftConfig = config.copy(mode = SourceMode.PI, endpoint = draftEndpoint, connectionEnabled = true)
    val validation = SourceConfigValidator.validate(draftConfig)
    val normalizedEndpoint = validation.config?.endpoint
    val draftChanged = normalizedEndpoint != null && normalizedEndpoint != config.endpoint
    val inspection = diagnostics.inspection
    LaunchedEffect(config.endpoint, draftEndpoint) { diagnostics.refresh(config.endpoint, draftEndpoint) }
    // A refresh mutates Compose-observed state, therefore it must happen in LaunchedEffect and
    // never while the tree is being composed. Until it completes, render only conservative
    // unavailable state; no import, reload or probe can be enabled from that placeholder.
    val savedHttps = inspection?.savedOrigin != null
    val draftMatchesSaved = inspection?.draftOriginState == GatewayDraftOriginState.MATCHES_SAVED
    val materialReady = inspection?.profile == GatewayProfileState.READY
    val importBlocker = when {
        !savedHttps -> "Secure material needs a saved HTTPS gateway origin."
        !draftMatchesSaved -> "Save the changed HTTPS gateway address first. Material is bound to the exact saved scheme, host and port."
        else -> null
    }
    val importsAllowed = importBlocker == null
    val operationAllowed = importsAllowed && materialReady && !diagnostics.testRunning
    val journal = diagnostics.snapshot(filter)

    Column(Modifier.fillMaxSize().background(palette.background).verticalScroll(rememberScrollState()).padding(padding)
        .testTag("gateway-connection-scroll"),
        verticalArrangement = Arrangement.spacedBy(gap)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("PI CONNECTION", fontFamily = font, fontWeight = FontWeight.ExtraBold,
                    fontSize = (58f * scale).coerceAtLeast(26f).sp, color = palette.ink, modifier = Modifier.semantics { heading() })
                Text("Saved connection, origin-bound material and explicit read-only diagnostics.", fontFamily = font, fontSize = bodySize, color = palette.secondary)
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag("gateway-back")) { Text("Back", fontFamily = font, fontSize = bodySize) }
        }
        GatewayCard(palette, scale) {
            Text("SAVED ORIGIN", fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = labelSize, color = palette.accent)
            Text(config.endpoint, Modifier.testTag("gateway-saved-endpoint"), fontFamily = font, fontSize = bodySize, color = palette.ink)
            Text(inspection?.savedOrigin ?: "Inspecting saved HTTPS origin…", Modifier.testTag("gateway-saved-origin"), fontFamily = font, fontSize = bodySize, color = palette.secondary)
            Text("Active source: $activeConnectionStatus", fontFamily = font, fontSize = bodySize, color = palette.secondary)
        }
        GatewayCard(palette, scale) {
            Text("GATEWAY ADDRESS", fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = labelSize, color = palette.accent)
            OutlinedTextField(
                value = draftEndpoint, onValueChange = { draftEndpoint = it; saveError = null },
                label = { Text("Pi endpoint", fontFamily = font, fontSize = labelSize) },
                modifier = Modifier.fillMaxWidth().testTag("gateway-endpoint").semantics { contentDescription = "Pi endpoint draft" },
                singleLine = true, isError = validation.error != null,
                textStyle = TextStyle(fontFamily = font, fontSize = (36f * scale).coerceAtLeast(16f).sp, color = palette.ink),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = palette.ink, unfocusedTextColor = palette.ink,
                    focusedContainerColor = palette.panel, unfocusedContainerColor = palette.panel, errorContainerColor = palette.panel,
                    focusedBorderColor = palette.accent, unfocusedBorderColor = palette.outline, errorBorderColor = palette.warning,
                    focusedLabelColor = palette.accent, unfocusedLabelColor = palette.secondary, errorLabelColor = palette.warning),
            )
            Text(validation.error ?: if (draftChanged) "Draft differs from saved origin. Save before material, reload or test." else "Draft matches the active saved origin.",
                Modifier.testTag("gateway-draft-state").semantics { liveRegion = LiveRegionMode.Polite }, fontFamily = font, fontSize = bodySize,
                color = if (validation.error != null || draftChanged) palette.warning else palette.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { draftEndpoint = config.endpoint; saveError = null }, enabled = draftEndpoint != config.endpoint,
                    modifier = Modifier.testTag("gateway-cancel-address")) { Text("Discard draft", fontFamily = font, fontSize = bodySize) }
                Button(onClick = {
                    val checked = SourceConfigValidator.validate(draftConfig)
                    val saved = checked.config ?: return@Button
                    if (saved.endpoint == config.endpoint) return@Button
                    try { onSaveEndpoint(saved); saveError = null; focus.clearFocus() }
                    catch (failure: RuntimeException) { saveError = "Gateway address could not be saved; the previous active source remains." }
                }, enabled = validation.config != null && draftChanged,
                    modifier = Modifier.heightIn(min = (76f * scale).coerceAtLeast(48f).dp).testTag("gateway-save-address")) {
                    Text("Save gateway address", fontFamily = font, fontSize = bodySize)
                }
            }
            saveError?.let { Text(it, Modifier.testTag("gateway-save-error"), fontFamily = font, fontSize = bodySize, color = palette.warning) }
            if (endpointHistory.isNotEmpty()) {
                Text("RECENT SAVED ADDRESSES", fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = bodySize, color = palette.secondary)
                endpointHistory.forEachIndexed { index, endpoint ->
                    OutlinedButton(onClick = { draftEndpoint = endpoint; saveError = null }, modifier = Modifier.fillMaxWidth().testTag("gateway-history-$index")) {
                        Text(endpoint, Modifier.fillMaxWidth(), fontFamily = font, fontSize = bodySize)
                    }
                }
            }
        }
        GatewayCard(palette, scale) {
            Text("SECURITY MATERIAL", fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = labelSize, color = palette.accent)
            GatewayFact("Draft origin", inspection?.draftOriginState?.name?.replace('_', ' ') ?: "INSPECTING", font, bodySize, palette)
            GatewayFact("Gateway key", inspection?.let { materialText(it.key, "saved for this exact origin") } ?: "INSPECTING", font, bodySize, palette)
            GatewayFact("Public CA", inspection?.let { materialText(it.ca, "saved for this exact origin") } ?: "INSPECTING", font, bodySize, palette)
            GatewayFact("Security profile", inspection?.profile?.name?.replace('_', ' ') ?: "INSPECTING", font, bodySize, palette)
            inspection?.certificate?.let { certificate ->
                GatewayFact("CA SHA-256", certificate.sha256Fingerprint, font, bodySize, palette)
                GatewayFact("CA valid", "${Instant.ofEpochMilli(certificate.validFromEpochMs)} to ${Instant.ofEpochMilli(certificate.validUntilEpochMs)}", font, bodySize, palette)
            }
            Text("This view never shows a key, header, token, certificate body or private key.", fontFamily = font, fontSize = bodySize, color = palette.secondary)
        }
        GatewayCard(palette, scale) {
            Text("GATEWAY KEY FILE", fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = labelSize, color = palette.accent)
            importBlocker?.let { Text(it, Modifier.testTag("gateway-key-unavailable"), fontFamily = font, fontSize = bodySize, color = palette.warning) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = deleteGatewayKeySource, enabled = importsAllowed, onCheckedChange = { deleteGatewayKeySource = it }, modifier = Modifier.testTag("gateway-key-delete-source"))
                Text("I confirm: after verified secure import, delete only the selected source file.", fontFamily = font, fontSize = bodySize, color = palette.secondary)
            }
            OutlinedButton(onClick = { onImportGatewayKey(deleteGatewayKeySource) }, enabled = importsAllowed, modifier = Modifier.testTag("gateway-key-import")) {
                Text(if (deleteGatewayKeySource) "Choose key file and delete source" else "Choose gateway key file", fontFamily = font, fontSize = bodySize)
            }
            Text(gatewayKeyStatus ?: "No connection test is run by importing a key.", Modifier.testTag("gateway-key-status"), fontFamily = font, fontSize = bodySize, color = palette.secondary)
        }
        GatewayCard(palette, scale) {
            Text("GATEWAY CA CERTIFICATE", fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = labelSize, color = palette.accent)
            importBlocker?.let { Text(it, Modifier.testTag("gateway-ca-unavailable"), fontFamily = font, fontSize = bodySize, color = palette.warning) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = confirmGatewayTrustOrigin, enabled = importsAllowed, onCheckedChange = { confirmGatewayTrustOrigin = it }, modifier = Modifier.testTag("gateway-ca-confirm"))
                Text("I confirm: this is a public CA certificate for the exact saved HTTPS gateway origin ${config.endpoint}.", fontFamily = font, fontSize = bodySize, color = palette.secondary)
            }
            OutlinedButton(onClick = onImportGatewayTrust, enabled = importsAllowed && confirmGatewayTrustOrigin, modifier = Modifier.testTag("gateway-ca-import")) {
                Text("Choose public CA certificate", fontFamily = font, fontSize = bodySize)
            }
            Text(gatewayTrustStatus ?: "No connection test is run by importing a CA certificate.", Modifier.testTag("gateway-ca-status"), fontFamily = font, fontSize = bodySize, color = palette.secondary)
        }
        GatewayCard(palette, scale) {
            Text("ACTIVE CONNECTION", fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = labelSize, color = palette.accent)
            Text("Reload retires every current poller and WebSocket before rebuilding exactly one session from the saved origin. It does not save this draft.", fontFamily = font, fontSize = bodySize, color = palette.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                Button(onClick = onReload, enabled = operationAllowed, modifier = Modifier.testTag("gateway-reload")) { Text("Reload saved connection", fontFamily = font, fontSize = bodySize) }
                OutlinedButton(onClick = { diagnostics.runReadOnlyTest(scope, config.endpoint, draftEndpoint) }, enabled = operationAllowed, modifier = Modifier.testTag("gateway-test")) { Text(if (diagnostics.testRunning) "Testing…" else "Test saved services", fontFamily = font, fontSize = bodySize) }
            }
            Text(if (!draftMatchesSaved) "Save or discard the address draft before reload or test."
                else if (!materialReady) "A matching key, CA and profile are required before an HTTPS reload or test."
                else "Read-only test checks HELM, weather, audio, SONAR HTTP and SONAR WebSocket. It never writes audio or starts acquisition.",
                Modifier.testTag("gateway-operation-status"), fontFamily = font, fontSize = bodySize, color = if (operationAllowed) palette.secondary else palette.warning)
        }
        GatewayDiagnosticJournal(scale, font, bodySize, palette, journal.entries, journal.droppedEntries, journal.lastSuccess, journal.lastFailure, filter,
            onFilter = { filter = it }, onCopy = { onCopyDiagnostics(diagnostics.export(GatewayDiagnosticExportFormat.JSON)) },
            onExport = { format -> onExportDiagnostics(format, diagnostics.export(format)) }, onClear = diagnostics::clear,
            transferStatus = diagnosticTransferStatus)
    }
}

@Composable private fun GatewayDiagnosticJournal(scale: Float, font: FontFamily, bodySize: androidx.compose.ui.unit.TextUnit, palette: GatewayPalette,
    entries: List<GatewayDiagnosticEntry>, dropped: Long, lastSuccess: GatewayDiagnosticEntry?, lastFailure: GatewayDiagnosticEntry?, selected: GatewayDiagnosticFilter, onFilter: (GatewayDiagnosticFilter) -> Unit,
    onCopy: () -> Unit, onExport: (GatewayDiagnosticExportFormat) -> Unit, onClear: () -> Unit, transferStatus: String?) {
    GatewayCard(palette, scale) {
        Text("CONNECTION DIAGNOSTICS", fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = bodySize, color = palette.accent)
        Text("Memory-only: max 500 entries / 256 KiB, oldest entries are displaced. No payloads, headers, credentials, CA bodies or position data.", fontFamily = font, fontSize = bodySize, color = palette.secondary)
        GatewayFact("Last success", lastSuccess?.let(::summary) ?: "NONE", font, bodySize, palette)
        GatewayFact("Last error", lastFailure?.let(::summary) ?: "NONE", font, bodySize, palette)
        if (dropped > 0) Text("Truncated: $dropped oldest entries displaced.", Modifier.testTag("gateway-log-truncated"), fontFamily = font, fontSize = bodySize, color = palette.warning)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { GatewayDiagnosticFilter.entries.forEach { filter ->
            OutlinedButton(onClick = { onFilter(filter) }, modifier = Modifier.testTag("gateway-log-filter-${filter.name}")) { Text(filter.name.lowercase().replaceFirstChar(Char::uppercase), fontFamily = font, fontSize = bodySize) }
        } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onCopy, modifier = Modifier.testTag("gateway-log-copy")) { Text("Copy JSON", fontFamily = font, fontSize = bodySize) }
            OutlinedButton(onClick = { onExport(GatewayDiagnosticExportFormat.JSON) }, modifier = Modifier.testTag("gateway-log-export-json")) { Text("Export JSON", fontFamily = font, fontSize = bodySize) }
            OutlinedButton(onClick = { onExport(GatewayDiagnosticExportFormat.CSV) }, modifier = Modifier.testTag("gateway-log-export-csv")) { Text("Export CSV", fontFamily = font, fontSize = bodySize) }
            OutlinedButton(onClick = onClear, modifier = Modifier.testTag("gateway-log-clear")) { Text("Clear", fontFamily = font, fontSize = bodySize) }
        }
        transferStatus?.let { Text(it, Modifier.testTag("gateway-log-transfer-status"), fontFamily = font, fontSize = bodySize, color = palette.secondary) }
        if (entries.isEmpty()) Text("No diagnostic attempts in this app session.", Modifier.testTag("gateway-log-empty"), fontFamily = font, fontSize = bodySize, color = palette.secondary)
        entries.takeLast(40).asReversed().forEach { entry ->
            Text("${Instant.ofEpochMilli(entry.timestampEpochMs)} · #${entry.attemptId} · ${entry.service} · ${entry.phase} · ${entry.outcome} · ${entry.durationMs ?: "—"} ms · ${entry.error}${entry.httpStatus?.let { " · HTTP $it" } ?: ""}",
                fontFamily = font, fontSize = bodySize, color = if (entry.outcome == GatewayDiagnosticOutcome.FAILURE) palette.warning else palette.ink)
        }
    }
}

private fun summary(entry: GatewayDiagnosticEntry): String =
    "${entry.service} ${entry.phase} ${entry.durationMs?.let { "${it} ms" } ?: "—"} ${entry.error}"

@Composable private fun GatewayCard(palette: GatewayPalette, scale: Float, content: @Composable () -> Unit) {
    Surface(color = palette.panel, shape = androidx.compose.foundation.shape.RoundedCornerShape((20f * scale).coerceAtLeast(8f).dp), border = BorderStroke(1.dp, palette.outline)) {
        Column(Modifier.fillMaxWidth().padding((28f * scale).coerceAtLeast(12f).dp), verticalArrangement = Arrangement.spacedBy((10f * scale).coerceAtLeast(5f).dp)) { content() }
    }
}

@Composable private fun GatewayFact(label: String, value: String, font: FontFamily, bodySize: androidx.compose.ui.unit.TextUnit, palette: GatewayPalette) {
    Text("$label: $value", fontFamily = font, fontSize = bodySize, color = palette.ink)
}

private fun materialText(state: GatewayMaterialState, suffix: String): String = when (state) {
    GatewayMaterialState.PRESENT -> "PRESENT · $suffix"
    GatewayMaterialState.MISSING -> "MISSING for saved origin"
    GatewayMaterialState.UNREADABLE -> "UNREADABLE"
}

private class GatewayPalette(night: Boolean) {
    val background = androidx.compose.ui.graphics.Color(if (night) 0xFF000000 else 0xFFF6F9FA)
    val panel = androidx.compose.ui.graphics.Color(if (night) 0xFF090000 else 0xFFFFFFFF)
    val ink = androidx.compose.ui.graphics.Color(if (night) 0xFFD40000 else 0xFF071022)
    val secondary = androidx.compose.ui.graphics.Color(if (night) 0xFFB00000 else 0xFF122653)
    val outline = androidx.compose.ui.graphics.Color(if (night) 0xFF700000 else 0xFF526B82)
    val accent = androidx.compose.ui.graphics.Color(if (night) 0xFFFF1A0A else 0xFF005DAF)
    val warning = androidx.compose.ui.graphics.Color(if (night) 0xFFFF1A0A else 0xFFAE3000)
}
