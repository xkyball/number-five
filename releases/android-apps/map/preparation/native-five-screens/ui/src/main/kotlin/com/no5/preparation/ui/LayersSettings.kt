package com.no5.preparation.ui

import androidx.compose.foundation.layout.*
import com.no5.ui.No5Text
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

@Immutable data class LayerOption(
    val id: String,
    val title: String,
    val supported: Boolean,
    val effective: Boolean,
    val explanation: String,
    val locked: Boolean = false,
    val requested: Boolean? = null,
    val pendingRequestId: Long? = null,
)
@Immutable data class LayersState(
    val layers: FrozenList<LayerOption> = frozenListOf(),
    val status: DataStatus = DataStatus(),
    val nextRequestId: Long = 1,
    val notice: String? = null,
)
sealed interface LayersEvent {
    data class SetVisible(val id: String, val visible: Boolean) : LayersEvent
    data class Acknowledge(val id: String, val requestId: Long, val accepted: Boolean, val reason: String? = null) : LayersEvent
}
/** Produces intent only. An adapter must explicitly acknowledge the exact request. */
fun reduceLayers(state: LayersState, event: LayersEvent): LayersState = when (event) {
    is LayersEvent.SetVisible -> {
        val layer = state.layers.find { it.id == event.id }
        when {
            layer == null -> state.copy(notice = "Ebene nicht bekannt.")
            !layer.supported || layer.locked -> state.copy(notice = No5Text.ui("%s: %s", No5Text.ui(layer.title), No5Text.ui(layer.explanation)))
            layer.pendingRequestId != null -> state.copy(notice = "Änderung wird bereits geprüft.")
            layer.effective == event.visible -> state
            else -> state.copy(layers = FrozenList.copyOf(state.layers.map {
                if (it.id == event.id) it.copy(requested = event.visible, pendingRequestId = state.nextRequestId) else it
            }), nextRequestId = state.nextRequestId + 1, notice = "Anzeigeänderung angefragt.")
        }
    }
    is LayersEvent.Acknowledge -> {
        val layer = state.layers.find { it.id == event.id }
        if (layer == null || layer.pendingRequestId != event.requestId || layer.requested == null) state
        else state.copy(layers = FrozenList.copyOf(state.layers.map {
            if (it.id == event.id) it.copy(
                effective = if (event.accepted) it.requested!! else it.effective,
                requested = null, pendingRequestId = null,
            ) else it
        }), notice = if (event.accepted) "Anzeigeänderung bestätigt." else event.reason ?: "Anzeigeänderung abgelehnt.")
    }
}

@Composable fun LayersScreen(state: LayersState, onEvent: (LayersEvent) -> Unit, modifier: Modifier = Modifier, objectContent: @Composable () -> Unit = {}) {
    No5Panel(No5Text.ui("EBENEN"), modifier) {
        StatusNotice(state.status)
        Text(No5Text.ui("Nur bestätigte Fähigkeiten sind bedienbar. Zum Anzeigen weiterer Ebenen wischen."))
        if (state.layers.isEmpty()) Text(No5Text.ui("Keine Ebenenfähigkeiten vom Kartenadapter verfügbar."))
        state.layers.forEach { layer ->
            Surface(tonalElevation = 0.dp) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(No5Text.ui(layer.title), style = MaterialTheme.typography.titleLarge)
                        Text(No5Text.ui(layer.explanation))
                        Text(when {
                            !layer.supported -> No5Text.ui("Nicht unterstützt")
                            layer.locked -> No5Text.ui("Fest aktiviert · Navigationsgrundlage")
                            layer.pendingRequestId != null -> No5Text.ui("Angefragt: %s · Bestätigung ausstehend", if (layer.requested == true) No5Text.ui("sichtbar") else No5Text.ui("verborgen"))
                            layer.effective -> No5Text.ui("Bestätigt sichtbar")
                            else -> No5Text.ui("Bestätigt verborgen")
                        })
                    }
                    No5CompactAction(if (layer.effective) No5Text.ui("Ausblenden") else No5Text.ui("Einblenden"), {
                        onEvent(LayersEvent.SetVisible(layer.id, !layer.effective))
                    }, Modifier.testTag("layer-toggle-${layer.id}"), enabled = layer.supported && !layer.locked && layer.pendingRequestId == null)
                }
            }
        }
        state.notice?.let { Text(No5Text.ui(it), color = MaterialTheme.colorScheme.primary) }
        objectContent()
    }
}

enum class UiPalette { DAY, RED_NIGHT }
enum class DistanceUnit { NAUTICAL_MILES, KILOMETRES }
enum class SpeedUnit { KNOTS, KILOMETRES_PER_HOUR }
enum class MapOrientation { NORTH_UP, COG_UP }
enum class SettingsTab { DISPLAY, SOURCES, CHARTS, DIAGNOSTICS }
@Immutable data class SourceInformation(val name: String, val status: DataStatus, val detail: String)
/** Read-only snapshot boundary. Implementations must not connect, configure or control devices here. */
interface SettingsInformationGateway { fun sourceInformation(): FrozenList<SourceInformation> }
@Immutable data class SettingsState(
    val palette: UiPalette = UiPalette.DAY,
    val requestedPalette: UiPalette? = null,
    val paletteRequestId: Long? = null,
    val nextRequestId: Long = 1,
    val distanceUnit: DistanceUnit = DistanceUnit.NAUTICAL_MILES,
    val speedUnit: SpeedUnit = SpeedUnit.KNOTS,
    val orientation: MapOrientation = MapOrientation.NORTH_UP,
    val orientationConfirmed: Boolean = true,
    val requestedOrientation: MapOrientation? = null,
    val orientationRequestId: Long? = null,
    val cogUpSupported: Boolean = false,
    val movementReliable: Boolean = false,
    val uiDimmingPercent: Int = 100,
    val sources: FrozenList<SourceInformation> = frozenListOf(),
    val chartStatus: DataStatus = DataStatus(),
    val offlineStatus: DataStatus = DataStatus(),
    val safetyDepthSupported: Boolean = false,
    val safetyDepthDetail: String = "Keine gekoppelte Sicherheitstiefe verfügbar. Kein Sicherheitsnachweis für die Route.",
    val tab: SettingsTab = SettingsTab.DISPLAY,
    val notice: String? = null,
)
sealed interface SettingsEvent {
    data class RequestPalette(val palette: UiPalette) : SettingsEvent
    data class AcknowledgePalette(val requestId: Long, val accepted: Boolean) : SettingsEvent
    data class SetDistanceUnit(val unit: DistanceUnit) : SettingsEvent
    data class SetSpeedUnit(val unit: SpeedUnit) : SettingsEvent
    data class RequestOrientation(val orientation: MapOrientation) : SettingsEvent
    data class AcknowledgeOrientation(val requestId: Long, val accepted: Boolean) : SettingsEvent
    data class SetUiDimming(val percent: Int) : SettingsEvent
    data class ShowTab(val tab: SettingsTab) : SettingsEvent
}
fun reduceSettings(state: SettingsState, event: SettingsEvent): SettingsState = when (event) {
    is SettingsEvent.RequestPalette -> when {
        state.paletteRequestId != null -> state.copy(notice = "Darstellungsbestätigung ausstehend.")
        event.palette == state.palette -> state
        else -> state.copy(requestedPalette = event.palette, paletteRequestId = state.nextRequestId, nextRequestId = state.nextRequestId + 1, notice = "Darstellung angefragt.")
    }
    is SettingsEvent.AcknowledgePalette -> if (state.paletteRequestId != event.requestId || state.requestedPalette == null) state else state.copy(
        palette = if (event.accepted) state.requestedPalette else state.palette,
        requestedPalette = null, paletteRequestId = null,
        notice = if (event.accepted) "Darstellung bestätigt." else "Darstellung abgelehnt.",
    )
    is SettingsEvent.SetDistanceUnit -> state.copy(distanceUnit = event.unit)
    is SettingsEvent.SetSpeedUnit -> state.copy(speedUnit = event.unit)
    is SettingsEvent.RequestOrientation -> when {
        event.orientation == MapOrientation.COG_UP && (!state.cogUpSupported || !state.movementReliable) -> state.copy(notice = "COG oben benötigt Unterstützung und verlässliche Bewegung.")
        state.orientationRequestId != null -> state.copy(notice = "Kartenausrichtung noch nicht bestätigt.")
        state.orientation == event.orientation && state.orientationConfirmed -> state
        else -> state.copy(requestedOrientation = event.orientation, orientationRequestId = state.nextRequestId, nextRequestId = state.nextRequestId + 1)
    }
    is SettingsEvent.AcknowledgeOrientation -> if (state.orientationRequestId != event.requestId || state.requestedOrientation == null) state else {
        val valid = event.accepted && (state.requestedOrientation != MapOrientation.COG_UP || state.cogUpSupported && state.movementReliable)
        state.copy(orientation = if (valid) state.requestedOrientation else state.orientation, orientationConfirmed = if (valid) true else state.orientationConfirmed, requestedOrientation = null, orientationRequestId = null,
            notice = if (valid) "Kartenausrichtung bestätigt." else "Kartenausrichtung abgelehnt.")
    }
    is SettingsEvent.SetUiDimming -> if (event.percent !in 30..100) state.copy(notice = "UI-Helligkeit muss zwischen 30 und 100 % liegen.") else state.copy(uiDimmingPercent = event.percent)
    is SettingsEvent.ShowTab -> state.copy(tab = event.tab)
}

@Composable fun SettingsScreen(state: SettingsState, onEvent: (SettingsEvent) -> Unit, modifier: Modifier = Modifier,
    displayContent: @Composable () -> Unit = {}, diagnosticsContent: @Composable () -> Unit = {}, chartsContent: @Composable () -> Unit = {},
    showTabs:Boolean = true) {
    No5Panel(No5Text.ui("EINSTELLUNGEN"), modifier) {
        if(showTabs) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SettingsTab.entries.forEach { tab -> No5Tab(when (tab) {
                SettingsTab.DISPLAY -> No5Text.ui("Darstellung")
                SettingsTab.SOURCES -> No5Text.ui("Quellen")
                SettingsTab.CHARTS -> No5Text.ui("Karten")
                SettingsTab.DIAGNOSTICS -> No5Text.ui("Karte / Offline")
            }, state.tab == tab, { onEvent(SettingsEvent.ShowTab(tab)) }) }
        }
        }
        when (state.tab) {
            SettingsTab.CHARTS -> chartsContent()
            SettingsTab.DISPLAY -> {
                displayContent()
                state.requestedPalette?.let { Text(No5Text.ui("Angefragt: %s · Bestätigung ausstehend", paletteLabel(it))) }
                SettingsChoiceRow(No5Text.ui("Farben · bestätigt: %s", paletteLabel(state.palette))) {
                    UiPalette.entries.forEach { palette -> No5Selection(paletteLabel(palette), state.palette == palette, { onEvent(SettingsEvent.RequestPalette(palette)) }, enabled = state.paletteRequestId == null) }
                }
                SettingsChoiceRow(No5Text.ui("Distanz · lokale Anzeige")) {
                    DistanceUnit.entries.forEach { unit -> No5Selection(if (unit == DistanceUnit.NAUTICAL_MILES) No5Text.ui("Seemeilen (sm)") else No5Text.ui("Kilometer (km)"), state.distanceUnit == unit, { onEvent(SettingsEvent.SetDistanceUnit(unit)) }) }
                }
                SettingsChoiceRow(No5Text.ui("Geschwindigkeit")) {
                    SpeedUnit.entries.forEach { unit -> No5Selection(if (unit == SpeedUnit.KNOTS) No5Text.ui("Knoten (kn)") else "km/h", state.speedUnit == unit, { onEvent(SettingsEvent.SetSpeedUnit(unit)) }) }
                }
                state.requestedOrientation?.let { Text(No5Text.ui("Angefragt: %s · Bestätigung ausstehend", orientationLabel(it))) }
                SettingsChoiceRow(if (state.orientationConfirmed) No5Text.ui("Ausrichtung · bestätigt") else No5Text.ui("Ausrichtung · frei gedreht")) {
                    MapOrientation.entries.forEach { orientation -> No5Selection(orientationLabel(orientation), state.orientationConfirmed && state.orientation == orientation, { onEvent(SettingsEvent.RequestOrientation(orientation)) }, enabled = state.orientationRequestId == null && (orientation == MapOrientation.NORTH_UP || state.cogUpSupported && state.movementReliable)) }
                }
                if (!state.cogUpSupported || !state.movementReliable) Text(No5Text.ui("COG oben nicht verfügbar: Kartenunterstützung und verlässliche Bewegung erforderlich."))
                SettingsChoiceRow(No5Text.ui("UI-Helligkeit")) {
                    listOf(30, 60, 100).forEach { percent -> No5Selection("$percent %", state.uiDimmingPercent == percent, { onEvent(SettingsEvent.SetUiDimming(percent)) }) }
                }
                Text(No5Text.ui("UI-Helligkeit betrifft nur diese Oberfläche; keine Geräte- oder Kartenhelligkeit."), style = MaterialTheme.typography.bodySmall)
            }
            SettingsTab.SOURCES -> {
                Text(No5Text.ui("Quellenstatus · nur lesbar. Verbindungen werden hier nicht geändert."))
                if (state.sources.isEmpty()) Text(No5Text.ui("Keine Quelleninformationen verfügbar."))
                state.sources.forEach { source ->
                    Text(source.name, style = MaterialTheme.typography.titleLarge)
                    StatusNotice(source.status)
                    Text(source.detail)
                    HorizontalDivider()
                }
            }
            SettingsTab.DIAGNOSTICS -> {
                diagnosticsContent()
                Text(No5Text.ui("Kartenadapter"), style = MaterialTheme.typography.titleLarge)
                StatusNotice(state.chartStatus)
                Text(No5Text.ui("Offline-Verfügbarkeit"), style = MaterialTheme.typography.titleLarge)
                StatusNotice(state.offlineStatus)
                Text(No5Text.ui("Sicherheitstiefe · %s", if (state.safetyDepthSupported) No5Text.ui("Status vom Adapter") else No5Text.ui("Kopplung nicht unterstützt")), style = MaterialTheme.typography.titleLarge)
                Text(No5Text.ui(state.safetyDepthDetail))
                Text(No5Text.ui("Darstellung und Offline-Verfügbarkeit bestätigen keine sichere Befahrbarkeit. Keine Autopilotsteuerung."))
            }
        }
        state.notice?.let { Text(No5Text.ui(it), color = MaterialTheme.colorScheme.primary) }
    }
}
private fun paletteLabel(palette: UiPalette) = if (palette == UiPalette.DAY) No5Text.ui("Tag") else No5Text.ui("Rotnacht")
private fun orientationLabel(orientation: MapOrientation) = if (orientation == MapOrientation.NORTH_UP) No5Text.ui("Nord oben") else No5Text.ui("COG oben")

@Composable private fun SettingsChoiceRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(label, Modifier.width(100.dp), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}
