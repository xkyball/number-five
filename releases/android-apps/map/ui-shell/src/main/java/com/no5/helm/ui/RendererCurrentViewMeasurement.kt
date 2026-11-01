package com.no5.helm.ui

import com.no5.helm.chart.*
import org.json.JSONObject
import java.util.UUID

/** UI admission only. The native owner rechecks the full authority before starting. */
internal fun currentViewReuseStartBlocker(state: ChartEngineStatus, otherOperationActive: Boolean): String? = when {
    otherOperationActive || state.rendererBenchmark.active || state.rendererCapture.active -> "Andere Kartenarbeit zuerst abschließen."
    state.rendererBenchmarkRecoveryPending -> "Zuerst die ursprüngliche Ansicht wiederherstellen."
    !state.initialized || !state.chartLoaded || !state.surfaceAttached || !state.residentCatalogActive -> "Aktuellen Kartenbestand laden und die Karte im Vordergrund bereitstellen."
    state.presentationPending || !state.residentCameraReady || state.confirmedFrame == null || state.sourcePresentationUnsupported -> "Auf eine bestätigte vollständige Kartenansicht warten."
    state.bufferTransformMode != ChartBufferTransformMode.OFF -> "Schnellen Kartenmodus vor dieser Messung ausschalten."
    else -> null
}

/** Consumes only the existing owner's read-only, atomically checked snapshot.
 * No UI-supplied coordinates, catalog replacement, budget or persistent toggle.
 */
internal fun currentViewReuseConfig(snapshot: String, current: ChartEngineStatus,
    variant: RendererViewportReuseVariant, detailed: Boolean, nowElapsedMs: Long,
    clientRunToken: String = UUID.randomUUID().toString()): RendererBenchmarkConfig {
    require(snapshot.toByteArray(Charsets.UTF_8).size <= 16 * 1024 * 1024)
    val outer = JSONObject(snapshot)
    require(outer.getString("schema") == "no5-capacity-outer-state/1" &&
        outer.getString("scope") == "CURRENT_ENTRY_CATALOG_ONLY") { "Kartenstand für die Messung nicht bestätigt."
    }
    val saved = outer.getJSONObject("state")
    val catalog = saved.getJSONObject("residentActivation").getJSONObject("catalog")
    val authority = outer.getJSONObject("authority")
    val generation = catalog.getString("generation")
    val source = saved.getLong("residentSourceGeneration")
    val budget = catalog.getJSONObject("budget")
    require(current.residentCatalogActive && current.residentCatalogGeneration == generation &&
        current.chartSourceGeneration == source && current.surfaceGeneration == authority.getLong("surfaceGeneration") &&
        authority.getString("catalogGeneration") == generation && authority.getLong("sourceGeneration") == source &&
        authority.getJSONObject("budget").let {
            it.getInt("cells") == budget.getInt("cells") && it.getLong("bytes") == budget.getLong("bytes") &&
                it.getLong("loadingBytes") == budget.getLong("loadingBytes")
        }) { "Kartenstand für die Messung nicht bestätigt." }
    require(current.bufferTransformMode == ChartBufferTransformMode.OFF && saved.getString("bufferTransformMode") == "OFF")
    require(nowElapsedMs in 0L..(Long.MAX_VALUE - 60_000))
    return RendererBenchmarkConfig(repetitions = 1, clientRunToken = clientRunToken,
        deadlineElapsedRealtimeMs = nowElapsedMs + 60_000, requestedWorkers = 1,
        runKind = RendererRunKind.RESIDENT_CAPACITY,
        capacityArm = ResidentCapacityArm(ChartResidentBudget(budget.getInt("cells"), budget.getLong("bytes"),
            budget.getLong("loadingBytes")), ResidentCapacityCacheMode.WARM, generation, source, 15_000),
        capacityRecipeId = "CURRENT_VIEW_REUSE", viewportReuseCandidate = variant, viewportReuseDetailed = detailed)
}

/** Explicit separate evidence admission; the existing timing helper and its JVM signature stay unchanged. */
internal fun currentViewReuseParityConfig(snapshot: String, current: ChartEngineStatus,
    variant: RendererViewportReuseVariant, nowElapsedMs: Long,
    clientRunToken: String = UUID.randomUUID().toString()): RendererBenchmarkConfig =
    currentViewReuseConfig(snapshot, current, variant, false, nowElapsedMs, clientRunToken).copy(viewportReuseParity = true)

/** References the owner's single typed snapshot; no coordinates or replacement source are supplied.
 * Caller must first pass the shared Host Follow/touch/other-work guard. The engine cannot infer GPS state.
 */
internal fun currentResidentLiveInputConfig(snapshot: String, current: ChartEngineStatus,
    normalAbort: Boolean, nowElapsedMs: Long, durationMs: Long = 10_000,
    clientRunToken: String = UUID.randomUUID().toString()): RendererBenchmarkConfig {
    // Reuse current-entry admission parsing and its exact source/surface/budget checks.
    currentViewReuseConfig(snapshot, current, RendererViewportReuseVariant.OFF, false, nowElapsedMs, clientRunToken)
    val id = JSONObject(snapshot).getString("snapshotId")
    return RendererBenchmarkConfig(repetitions = 1, clientRunToken = clientRunToken,
        deadlineElapsedRealtimeMs = nowElapsedMs + 60_000, runKind = RendererRunKind.LIVE_INPUT,
        liveMode = ChartBufferTransformMode.CONTROL, liveDurationMs = durationMs,
        normalRenderAbort = normalAbort, currentResidentLive = CurrentResidentLiveArm(id))
}

/** Exact current owner, unchanged six-pose recipe; captures are not part of timing arms. */
internal fun currentResidentLiveFullParityConfig(snapshot: String, current: ChartEngineStatus,
    normalAbort: Boolean, nowElapsedMs: Long,
    clientRunToken: String = UUID.randomUUID().toString()): RendererBenchmarkConfig =
    currentResidentLiveInputConfig(snapshot, current, normalAbort, nowElapsedMs, 15_000, clientRunToken)
        .copy(liveReplay = false, currentLiveParityOnly = true)
