package com.no5.helm.ui

import com.no5.helm.model.HelmState
import com.no5.helm.model.ConnectionState
import com.no5.helm.model.MapPositionState
import com.no5.helm.model.PositionChoice
import com.no5.helm.model.PositionStatus
import com.no5.ui.No5Text

enum class NativeGpsProvenance { REAL, UNKNOWN }

/** Selected GPS only. Its observation clock is never the HELM publication clock. */
data class NativeHeaderPosition(val label: String, val sourceId: String?, val generation: Long,
    val observedAtEpochMs: Long?, val status: PositionStatus, val provenance: NativeGpsProvenance,
    private val selection: MapPositionState? = null) {
    /** Capture the immutable controller state; no coordinate is exported to the header contract. */
    fun matches(current: MapPositionState) = selection != null && selection == current
    override fun toString() = "NativeHeaderPosition(label=$label, sourceId=$sourceId, generation=$generation, " +
        "observedAtEpochMs=$observedAtEpochMs, status=$status, provenance=$provenance)"
}

/** Immutable observation from the actual native page and confirmed renderer palette. */
data class NativeHeaderContext(val sourceGeneration: Long, val page: String, val redNight: Boolean, val backend: HelmState,
    val sourceDetail: String, val transport: String, val position: NativeHeaderPosition? = null) {
    /** The original immutable transport state is the fence, never a selected-ownship copy. */
    fun matches(expectedSourceGeneration: Long, currentBackend: HelmState) =
        sourceGeneration == expectedSourceGeneration && backend == currentBackend
    fun matches(expectedSourceGeneration: Long, currentBackend: HelmState, currentPosition: MapPositionState) =
        matches(expectedSourceGeneration, currentBackend) && position?.matches(currentPosition) == true
}

internal fun nativeHeaderPosition(state: MapPositionState, language: String): NativeHeaderPosition {
    val block = state.sources
    val selected = if (state.intent.choice == PositionChoice.COMBINED) {
        block?.combination?.takeIf { block.combinationAvailable && state.selectedSourceId == "@combined" &&
            it.observedAtEpochMs == state.observedAtEpochMs }
    } else null
    val candidate = block?.sources?.firstOrNull { it.sourceId == state.selectedSourceId &&
        it.observedAtEpochMs == state.observedAtEpochMs }
    val confirmed = !state.legacy && block?.valid == true && block.sourceKind == "REAL" && !block.simulated &&
        (selected != null || state.intent.choice != PositionChoice.COMBINED && candidate != null)
    val label = if (state.intent.follow && !state.chartBoundsKnown) No5Text.text(language, "Kartengrenzen unbekannt") else if (state.intent.follow && state.outsideChartBounds) No5Text.text(language, "Folgen pausiert am Kartenrand") else if (state.intent.choice == PositionChoice.COMBINED) No5Text.text(language, "GPS kombiniert") else
        state.selectedLabel ?: state.intent.sourceId ?: No5Text.text(language,
            if (state.legacy) "Gateway-Primärposition · Legacy" else "Keine GPS-Quelle bestätigt")
    val status = if (state.status == PositionStatus.LIVE && (state.awaitingFreshFix || state.ownship == null))
        PositionStatus.UNAVAILABLE else state.status
    return NativeHeaderPosition(label.take(128), state.selectedSourceId.takeIf { confirmed }, state.generation,
        state.observedAtEpochMs, status, if (confirmed) NativeGpsProvenance.REAL else NativeGpsProvenance.UNKNOWN, state)
}

/** A short GPS summary plus an explicitly separate aggregate HELM provenance notice. */
internal fun nativeHeaderSourceStatus(backend: HelmState, position: MapPositionState, language: String) =
    sourceStatus(backend).let { global ->
        val snapshot = backend.snapshot
        if (snapshot == null || backend.connection != ConnectionState.LIVE) global else {
            val telemetry = snapshot.gatewayTelemetryProvenance
            val notice = No5Text.text(language, when (telemetry?.simulated) {
                true -> "HELM simuliert"
                false -> "HELM real"
                null -> "HELM-Herkunft unbekannt"
            })
            val gps = nativeHeaderPosition(position, language)
            val status = if (!position.chartBoundsKnown) No5Text.text(language, "Kartengrenzen unbekannt") else if (position.outsideChartBounds) No5Text.text(language, "Außerhalb des aktiven Kartenbereichs") else if (position.awaitingFreshFix && position.status == PositionStatus.LIVE)
                No5Text.text(language, "Warte auf neuen Fix") else positionStatusText(gps.status, language)
            global.copy(detail = "$status · $notice")
        }
    }
