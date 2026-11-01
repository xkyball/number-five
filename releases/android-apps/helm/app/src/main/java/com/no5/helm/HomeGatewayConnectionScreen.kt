package com.no5.helm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.no5.domain.SourceConfig
import com.no5.gateway.GatewayAddressSource
import com.no5.gateway.HomeGatewayClient
import com.no5.gateway.HomeGatewayStatus
import com.no5.helm.diagnostics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun HomeGatewayConnectionScreen(scale: Float, night: Boolean, config: SourceConfig,
    connectionStatus: String, onHome: () -> Unit, onBack: () -> Unit, onRefreshSources: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnostics = remember { GatewayConnectionDiagnosticsModel(context) }
    val home = remember { HomeGatewayClient(context) }
    var status by remember { mutableStateOf<HomeGatewayStatus?>(null) }
    var resumeRevision by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(diagnostics, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumeRevision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); diagnostics.close() }
    }
    LaunchedEffect(config.endpoint, diagnostics.testRunning, resumeRevision) {
        status = withContext(Dispatchers.IO) { runCatching { home.status() }.getOrNull() }
    }
    var transferStatus by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri != null && bytes != null) scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching {
                checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
            }.isSuccess }
            transferStatus = if (saved) "Diagnose exportiert." else "Export konnte nicht gespeichert werden."
        }
    }
    HomeGatewayConnectionContent(scale, night, status, connectionStatus, diagnostics.testRunning,
        diagnostics.snapshot(GatewayDiagnosticFilter.ALL), onHome, onBack,
        onTest = {
            onRefreshSources()
            diagnostics.runReadOnlyTest(scope, config.endpoint, config.endpoint)
        },
        onCopy = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("No5 Gateway-Diagnose", diagnostics.export(GatewayDiagnosticExportFormat.JSON).decodeToString()))
            transferStatus = "Diagnose als JSON kopiert."
        },
        onExport = { format ->
            pendingExport = diagnostics.export(format)
            export.launch("no5-gateway-diagnostics.${format.name.lowercase()}")
        }, onClear = diagnostics::clear, transferStatus = transferStatus)
}

/** Same native palette, typography and panel outline as SOUND SETUP. Home owns all edits. */
@Composable internal fun HomeGatewayConnectionContent(scale: Float, night: Boolean, status: HomeGatewayStatus?,
    connectionStatus: String, testRunning: Boolean, journal: GatewayDiagnosticSnapshot,
    onHome: () -> Unit, onBack: () -> Unit, onTest: () -> Unit, onCopy: () -> Unit,
    onExport: (GatewayDiagnosticExportFormat) -> Unit, onClear: () -> Unit, transferStatus: String? = null) {
    val palette = remember(night) { SettingsPalette(night) }
    val font = remember { FontFamily(Font(R.font.barlow_condensed_regular),
        Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold), Font(R.font.barlow_condensed_extrabold, FontWeight.ExtraBold)) }
    val body = (26f * scale).coerceAtLeast(14f).sp
    val small = (22f * scale).coerceAtLeast(12f).sp
    val gap = (12f * scale).coerceAtLeast(6f).dp
    val padding = (24f * scale).coerceAtLeast(12f).dp
    val shape = RoundedCornerShape((12f * scale).coerceAtLeast(7f).dp)
    val record = status?.configuration
    val ready = record?.usable == true && status?.keyPresent == true && status.caPresent
    var historyOpen by remember { mutableStateOf(false) }
    var failuresOnly by remember { mutableStateOf(false) }
    val attempt = latestGatewayTest(journal.entries)
    val results = attempt.filter { it.phase == GatewayDiagnosticPhase.TEST_RESULT && it.service in gatewayTestServices }
    fun timestamp(ms: Long) = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))
    @Composable fun label(text: String, modifier: Modifier = Modifier, emphasis: Boolean = false, warning: Boolean = false) {
        Text(text, modifier, color = if(warning) palette.warning else palette.ink, fontFamily = font,
            fontWeight = if(emphasis) FontWeight.SemiBold else FontWeight.Normal, fontSize = body)
    }
    @Composable fun action(text: String, tag: String, enabled: Boolean = true, primary: Boolean = false, click: () -> Unit) {
        val modifier = Modifier.heightIn(min = (56f * scale).coerceAtLeast(48f).dp).testTag(tag)
        if(primary) Button(click, modifier, enabled, shape = shape, colors = ButtonDefaults.buttonColors(
            containerColor = palette.ink, contentColor = palette.buttonInk,
            disabledContainerColor = palette.disabled, disabledContentColor = palette.secondary)) {
            Text(text, fontFamily = font, fontSize = body, fontWeight = FontWeight.SemiBold)
        } else OutlinedButton(click, modifier, enabled, shape = shape, border = BorderStroke(1.dp, palette.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.ink, disabledContentColor = palette.secondary)) {
            Text(text, fontFamily = font, fontSize = body)
        }
    }
    Surface(Modifier.fillMaxSize().testTag("gateway-connection-panel"), color = palette.background,
        shape = shape, border = BorderStroke(2.dp, palette.ink)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(padding), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gap)) {
                Column(Modifier.weight(1f)) {
                    Text("GATEWAY · HOME", color = palette.ink, fontFamily = font, fontWeight = FontWeight.ExtraBold,
                        fontSize = (40f * scale).coerceAtLeast(23f).sp, modifier = Modifier.semantics { heading() })
                    Text("Ein Zugang für alle No5 Apps auf diesem Gerät", color = palette.secondary, fontFamily = font, fontSize = small)
                }
                action("Zurück", "gateway-back", click = onBack)
            }
            HorizontalDivider(color = palette.outline)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .testTag("gateway-connection-scroll").padding(padding), verticalArrangement = Arrangement.spacedBy(gap)) {
                Column(verticalArrangement = Arrangement.spacedBy((6f * scale).coerceAtLeast(3f).dp)) {
                    label(record?.origin?.url ?: "Gateway-Zugang in Home einrichten", Modifier.testTag("gateway-saved-endpoint"), emphasis = true)
                    label(record?.let { "${if(it.source == GatewayAddressSource.MDNS) "mDNS" else "Manuelle Auflösung"} · ${it.address} · ${if(it.usable) "Adresse verfügbar" else "Adresse in Home erneut prüfen"}" }
                        ?: if(status?.installed == true) "Home ist verfügbar · noch keine Gateway-Adresse" else "Home-Konfiguration ist nicht verfügbar")
                    Text("Zugangsschlüssel: ${if(status?.keyPresent == true) "vorhanden" else "fehlt"}    ·    CA-Zertifikat: ${if(status?.caPresent == true) "vorhanden" else "fehlt"}",
                        color = if(ready) palette.secondary else palette.warning, fontFamily = font, fontSize = small)
                    record?.checkedAt?.takeIf { it > 0 }?.let { Text("Home-Adressprüfung: ${timestamp(it)} · aufeinanderfolgende Fehler: ${record.consecutiveFailures}",
                        color = palette.secondary, fontFamily = font, fontSize = small) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                    action("In Home konfigurieren", "gateway-home", click = onHome)
                    action(if(testRunning) "Dienste werden geprüft …" else "Verbindung testen", "gateway-test", ready && !testRunning, primary = true, click = onTest)
                }
                if(!ready) Text("Für den Dienstetest müssen Adresse, Zugangsschlüssel und CA in Home bereitstehen.",
                    color = palette.warning, fontFamily = font, fontSize = small, modifier = Modifier.testTag("gateway-test-blocker"))
                Text(connectionStatus, color = palette.secondary, fontFamily = font, fontSize = small)
                HorizontalDivider(color = palette.outline)
                Column(verticalArrangement = Arrangement.spacedBy((5f * scale).coerceAtLeast(3f).dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    label("VERBINDUNGSTEST", emphasis = true)
                    Text(attempt.firstOrNull()?.let { "Versuch #${it.attemptId} · ${timestamp(it.timestampEpochMs)} · ${results.count { e -> e.outcome == GatewayDiagnosticOutcome.SUCCESS }}/5 Dienste OK${if(testRunning) " · läuft" else ""}" }
                        ?: "Noch kein Test in dieser App-Sitzung.", color = palette.secondary, fontFamily = font, fontSize = small)
                    Text("HTTPS prüft Hostname und CA. HTTP liest den Dienstzustand; WebSocket prüft den Handshake.", color = palette.secondary, fontFamily = font, fontSize = small)
                }
                Surface(color = palette.panel, border = BorderStroke(1.dp, palette.outline), shape = shape) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = (18f * scale).coerceAtLeast(8f).dp)) {
                        gatewayTestServices.forEachIndexed { index, service ->
                            val entry = attempt.lastOrNull { it.service == service }
                            Row(Modifier.fillMaxWidth().padding(vertical = (8f * scale).coerceAtLeast(5f).dp)
                                .testTag("gateway-result-${service.name}"), horizontalArrangement = Arrangement.spacedBy(gap)) {
                                Column(Modifier.weight(.33f)) {
                                    label(gatewayServiceLabel(service), emphasis = true)
                                    Text(gatewayServicePath(service), color = palette.secondary, fontFamily = font, fontSize = small)
                                }
                                Column(Modifier.weight(.67f)) {
                                    label(entry?.let(::gatewayResultLabel) ?: if(attempt.isEmpty()) "Noch nicht geprüft" else "Ausstehend",
                                        warning = entry?.outcome == GatewayDiagnosticOutcome.FAILURE)
                                    Text(entry?.let { gatewayResultDetail(it) } ?: "—", color = palette.secondary, fontFamily = font, fontSize = small)
                                }
                            }
                            if(index < gatewayTestServices.lastIndex) HorizontalDivider(color = palette.outline.copy(alpha = .45f))
                        }
                    }
                }
                attempt.lastOrNull { it.service == GatewayDiagnosticService.SECURITY && it.outcome == GatewayDiagnosticOutcome.SKIPPED }?.let {
                    label(gatewayErrorExplanation(it.error, it.httpStatus), warning = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    action(if(historyOpen) "Verlauf schließen" else "Diagnoseverlauf (${journal.entries.size})", "gateway-log-toggle", click = { historyOpen = !historyOpen })
                    action("JSON kopieren", "gateway-log-copy", journal.entries.isNotEmpty(), click = onCopy)
                }
                transferStatus?.let { Text(it, color = palette.secondary, fontFamily = font, fontSize = small, modifier = Modifier.testTag("gateway-log-transfer-status")) }
                if(historyOpen) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        action(if(failuresOnly) "Alle Einträge" else "Nur Fehler", "gateway-log-filter", click = { failuresOnly = !failuresOnly })
                        action("JSON", "gateway-log-export-json", journal.entries.isNotEmpty(), click = { onExport(GatewayDiagnosticExportFormat.JSON) })
                        action("CSV", "gateway-log-export-csv", journal.entries.isNotEmpty(), click = { onExport(GatewayDiagnosticExportFormat.CSV) })
                        action("Leeren", "gateway-log-clear", !testRunning && journal.entries.isNotEmpty(), click = onClear)
                    }
                    Text("Nur technische Statusdaten · bis 500 Einträge im Sitzungsspeicher · die letzten 40 werden angezeigt${if(journal.droppedEntries > 0) " · ${journal.droppedEntries} ältere Einträge verworfen" else ""}",
                        color = palette.secondary, fontFamily = font, fontSize = small)
                    journal.entries.filter { !failuresOnly || it.outcome == GatewayDiagnosticOutcome.FAILURE }.takeLast(40).asReversed().forEach {
                        Text("${timestamp(it.timestampEpochMs)} · #${it.attemptId} · ${gatewayServiceLabel(it.service)} · ${it.phase} · ${gatewayResultLabel(it)} · ${gatewayResultDetail(it)}",
                            color = if(it.outcome == GatewayDiagnosticOutcome.FAILURE) palette.warning else palette.secondary, fontFamily = font, fontSize = small)
                    }
                }
            }
        }
    }
}
