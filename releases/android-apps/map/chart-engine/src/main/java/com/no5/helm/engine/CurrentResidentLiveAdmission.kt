package com.no5.helm.engine

import com.no5.helm.chart.*

/** Immutable interpretation/data authority, distinct from camera and render-cancellation epochs. */
internal data class CurrentResidentLiveIdentity(val rendererSession: String,
    val catalog: ChartResidentCatalog, val source: Long, val surface: Long, val content: Long,
    val nativeData: Long, val nativeContent: Long, val presentationGeneration: Long,
    val policyKey: String, val presentation: ChartPresentation, val settings: S52Settings,
    val requestedSettings: S52Settings,
    val overlays: ChartOverlays) {
    init {
        require(java.util.UUID.fromString(rendererSession).toString() == rendererSession)
        require(catalog.generation.matches(Regex("[a-f0-9]{64}")))
        require(listOf(source, surface, content, nativeData, nativeContent, presentationGeneration).all { it >= 0 })
    }
}

internal data class CurrentResidentLiveSnapshot(val id: String, val identity: CurrentResidentLiveIdentity,
    val saved: BenchmarkUserState, val geometry: ChartSurfaceGeometry,
    val cameraRevision: Long, val full: ChartBufferSnapshot) {
    init {
        require(java.util.UUID.fromString(id).toString() == id && saved.runId == id)
        require(identity.catalog.sources.isNotEmpty()) { "Current live input requires the actual nonempty entry catalog" }
        require(saved.bufferTransformMode == ChartBufferTransformMode.OFF)
        require(full.producerCompleted && full.stage == ChartContentStage.FULL && cameraRevision >= 0 &&
            full.cameraRevision == cameraRevision && full.stamp > 0 && full.frame.geometry == geometry &&
            full.frame.viewport == saved.viewport && full.frame.sourceGeneration == identity.source &&
            full.frame.contentGeneration == identity.content)
    }
}

/** One bounded record, no file intake, coordinates from callers, queue or source substitution. */
internal class CurrentResidentLiveSnapshotSlot {
    private var value: CurrentResidentLiveSnapshot? = null
    @Synchronized fun remember(snapshot: CurrentResidentLiveSnapshot) { value = snapshot }
    @Synchronized fun get(id: String): CurrentResidentLiveSnapshot? = value?.takeIf { it.id == id }
    @Synchronized fun clear(id: String) { if (value?.id == id) value = null }
    @Synchronized fun close() { value = null }
}

/** Source-specific preparation is exclusive. Failure never falls back to another dataset. */
internal suspend fun <T> prepareLiveInputSource(current: CurrentResidentLiveSnapshot?,
    prepareCurrent: suspend (CurrentResidentLiveSnapshot) -> T, preparePublicBoston: suspend () -> T): T =
    if (current == null) preparePublicBoston() else prepareCurrent(current)

/** Final observation freeze is the completion boundary, not the earlier readiness probe. */
internal fun currentResidentLiveCompletionError(cancelled: Boolean, cancelReason: String?,
    freezeFailure: String?, observationError: String?): String = when {
    cancelled -> cancelReason?.takeIf(String::isNotBlank) ?: "Messung abgebrochen"
    !freezeFailure.isNullOrBlank() -> freezeFailure
    !observationError.isNullOrBlank() -> observationError
    else -> ""
}

/** Only the exact admitted capture may compose with resident work; mutable authority is never inferred. */
internal class CurrentResidentLiveLease(val runId: String, val snapshot: CurrentResidentLiveSnapshot,
    val capture: Any) {
    @Volatile var phase: String = "warming"
    @Volatile var failure: String? = null; private set
    @Synchronized fun invalidate() { if (failure == null) failure = "SOURCE_FREEZE_LOST" }
    fun matches(identity: CurrentResidentLiveIdentity): Boolean = failure == null && snapshot.identity == identity
    fun allows(state: ChartEngineStatus, activeRunId: String?, actualCapture: Any?,
        identityMatches: Boolean, closed: Boolean, profiling: Boolean, cancelled: Boolean): Boolean =
        !closed && !profiling && !cancelled && failure == null && identityMatches && capture === actualCapture &&
            activeRunId == runId && state.rendererBenchmark.active && state.rendererBenchmark.runId == runId &&
            state.rendererBenchmark.runKind == RendererRunKind.LIVE_INPUT && !state.rendererBenchmarkRecoveryPending &&
            state.rendererCapture.active && (phase == "warming" || phase == "live_input") &&
            state.rendererBenchmark.phase == phase
    fun cameraAllowed(state: ChartEngineStatus): Boolean = failure == null && phase == "live_input" &&
        state.rendererBenchmark.runId == runId && state.rendererBenchmark.runKind == RendererRunKind.LIVE_INPUT &&
        state.rendererBenchmark.phase == "live_input" && state.rendererBenchmark.liveInputEnabled
}
