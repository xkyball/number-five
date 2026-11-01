package com.no5.helm.ui

import com.no5.ui.No5Text
import com.no5.ui.No5ActionFeedback
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.no5.helm.chart.ChartEngineStatus
import com.no5.helm.chart.ChartResidencyStatus
import com.no5.helm.chart.ChartResidentStep
import com.no5.helm.chart.ChartResidentCameraReason
import com.no5.helm.chart.ChartResidentStartState
import java.util.Locale

internal class RendererWorkOverlayPreference(private val preferences: SharedPreferences) {
    var enabled: Boolean
        get() = preferences.getBoolean("rendererWorkOverlayEnabled", false)
        set(value) { preferences.edit().putBoolean("rendererWorkOverlayEnabled", value).apply() }
}

internal data class RendererWorkOverlayModel(val work: String, val cells: String, val budget: String)
internal const val SOURCE_PRESENTATION_UNSUPPORTED_MESSAGE =
    "Dieser Grafiktreiber kann Kartenbilder keinem Quellenstand sicher zuordnen. Kartenanzeige nicht freigegeben."

/** An old confirmed image cannot admit a new catalog/surface start. */
internal fun residentStartPending(chart: ChartEngineStatus): Boolean = chart.residentCatalogActive &&
    (!chart.residentCameraReady || chart.residentStartState != ChartResidentStartState.NOT_REQUIRED &&
        chart.residentStartState != ChartResidentStartState.READY)

internal fun residentStartWork(chart: ChartEngineStatus): String? {
    if (!residentStartPending(chart)) return null
    return when (chart.residentStartState) {
        ChartResidentStartState.WAITING_SURFACE -> "Start: Zeichenfläche vorbereiten"
        ChartResidentStartState.BLOCKED -> "Startausschnitt an Zellgrenze gesperrt"
        ChartResidentStartState.UNKNOWN -> "Startausschnitt: Zellbedarf unbekannt"
        ChartResidentStartState.STALE_AUTHORITY -> "Startausschnitt: Prüfung veraltet"
        ChartResidentStartState.NOT_REQUIRED, ChartResidentStartState.READY -> "Startausschnitt noch nicht geprüft"
    }
}

/** Reports observed work and native charged geometry, never screen or nautical completeness. */
internal fun rendererWorkOverlayModel(chart: ChartEngineStatus, residency: ChartResidencyStatus?,
    styleBusy: Boolean = false, libraryBusy: Boolean = false, libraryProgress: String = ""): RendererWorkOverlayModel {
    val current = residency?.takeIf { it.enabled && chart.residentCatalogActive &&
        it.catalogGeneration == chart.residentCatalogGeneration }
    val startWork = residentStartWork(chart)
    val decision = chart.residentCameraDecision
    val currentDecision = decision?.takeIf { chart.residentCatalogActive && chart.surfaceAttached &&
        it.catalogGeneration == chart.residentCatalogGeneration && it.sourceGeneration == chart.chartSourceGeneration &&
        it.surfaceGeneration == chart.surfaceGeneration && (startWork == null ||
        chart.residentStartState == ChartResidentStartState.BLOCKED && it.reason == ChartResidentCameraReason.BLOCKED ||
        chart.residentStartState == ChartResidentStartState.UNKNOWN && it.reason == ChartResidentCameraReason.UNKNOWN) }
    val cameraWork = currentDecision?.let { when {
        it.recoveryAboveBudget || it.reason == ChartResidentCameraReason.RECOVERY_ABOVE_BUDGET ->
            "Noch über Zoom-Budget · weiter hineinzoomen"
        it.reason == ChartResidentCameraReason.BLOCKED ->
            "Zoomgrenze: Bedarf > aktuelles Budget${if (it.conservative) " (konservativ)" else ""}"
        it.reason == ChartResidentCameraReason.NO_ZOOM_ALTERNATIVE -> "Zoomgrenze · Bewegen und Drehen bleibt möglich"
        it.reason == ChartResidentCameraReason.UNKNOWN -> "Zoomprüfung: Zellbedarf unbekannt"
        it.reason == ChartResidentCameraReason.STALE_AUTHORITY -> "Zoomprüfung: Status veraltet"
        else -> null
    } } ?: if (decision != null && currentDecision == null) "Zoomprüfung: Status veraltet" else null
    val work = when {
        chart.sourcePresentationUnsupported -> "Grafiktreiber-Unterstützung fehlt"
        libraryBusy -> libraryProgress.ifBlank { "Kartenbestand vorbereiten" }
        startWork != null -> startWork
        current?.step == ChartResidentStep.FAILED -> "Fehler beim Zellenladen"
        cameraWork != null -> cameraWork
        current?.step == ChartResidentStep.BLOCKED -> "Nachladen wartet auf Budget"
        styleBusy -> "Kartenstil aufbauen"
        !chart.initialized -> "Renderer wird initialisiert"
        !chart.chartLoaded && !chart.residentCatalogActive && current == null -> "Kein aktiver Kartenbestand"
        !chart.surfaceAttached -> "Zeichenfläche noch nicht bereit"
        current != null && (current.loading > 0 || current.missing > 0) -> "Zellen nachladen"
        current != null && !current.complete -> "Kartenbedarf bestimmen"
        chart.residentCatalogActive && current == null -> "Zellenstatus noch unbekannt"
        chart.presentationPending || chart.confirmedFrame == null || !chart.presentationAvailable -> "Kartenbild aufbauen"
        else -> "Aktueller Bildstand bestätigt"
    }
    val proposedNeed = currentDecision?.takeUnless { it.reason == ChartResidentCameraReason.UNKNOWN ||
        it.reason == ChartResidentCameraReason.STALE_AUTHORITY }
        ?.requiredUpperBound?.takeIf { it >= 0 }
    val cells = current?.takeUnless { startWork != null }?.let {
        No5Text.ui("Aktuell: %d geladen · %d benötigt · %d fehlen", it.resident,it.required,it.missing) +
            if (it.loading > 0) No5Text.ui(" · %d laden",it.loading) else ""
    } ?: proposedNeed?.let {
        No5Text.ui(if(currentDecision?.conservative==true)"Vorgeschlagen: bis zu %d Zellen (konservativ)" else "Vorgeschlagen: bis zu %d Zellen",it)
    } ?: "Zellenstatus: unbekannt"
    val budget = current?.takeUnless { startWork != null }?.let { resident -> resident.budget?.let { limit ->
        fun mib(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes.toDouble() / (1024 * 1024))
        No5Text.ui("Budget: %d Zellen · Geometrie %s/%s MiB",limit.cells,mib(resident.chargedBytes),mib(limit.bytes))
    } } ?: currentDecision?.let { No5Text.ui("Budget: %d Zellen · Geometrie unbekannt",it.cellBudget) } ?: "Budget: unbekannt"
    return RendererWorkOverlayModel(No5Text.ui(work), No5Text.ui(cells), No5Text.ui(budget))
}

@Composable internal fun RendererWorkOverlayToggle(enabled: Boolean, writable: Boolean, onEnabled: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(com.no5.ui.No5Text.ui("Renderer-Arbeitsstatus"), Modifier.weight(1f))
        No5ActionFeedback(enabled = writable, shape = androidx.compose.foundation.shape.CircleShape) { feedback ->
            Switch(enabled, onEnabled, enabled = feedback.enabled, interactionSource = feedback.interactionSource,
                modifier = Modifier.testTag("renderer-work-overlay-switch")
                    .semantics { contentDescription = No5Text.ui("Renderer-Arbeitsstatus einblenden") }.then(feedback.modifier))
        }
    }
    Text(com.no5.ui.No5Text.ui("Kleine Statusanzeige auf der Karte. Deine Auswahl bleibt nach Neustart erhalten."), style = MaterialTheme.typography.bodySmall)
}

/** No pointer input: the status display does not claim or terminate map gestures. */
@Composable internal fun RendererWorkOverlay(model: RendererWorkOverlayModel, modifier: Modifier = Modifier) {
    Surface(modifier.testTag("renderer-work-overlay"), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(model.work, Modifier.testTag("renderer-work-overlay-phase"), style = MaterialTheme.typography.labelLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(model.cells, Modifier.testTag("renderer-work-overlay-cells"), style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(model.budget, Modifier.testTag("renderer-work-overlay-budget"), style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
