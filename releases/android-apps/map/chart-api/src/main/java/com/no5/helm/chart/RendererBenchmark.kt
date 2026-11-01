package com.no5.helm.chart

val RENDERER_BENCHMARK_CELLS = setOf("US5BOSCC", "US5BOSCD", "US5BOSDC", "US5BOSDD")

/** Same actionable prerequisites for the UI and the engine; user chart selection is independent of the fixed bundled test data. */
fun rendererBenchmarkStartBlocker(state: ChartEngineStatus, otherOperationActive: Boolean = false): String? = when {
    !state.initialized -> "Der Kartenrenderer wird noch vorbereitet. Bitte warten."
    state.rendererBenchmark.active -> "Eine Messung läuft bereits. Abbrechen oder das Ende abwarten."
    state.rendererBenchmarkRecoveryPending -> "Eine unterbrochene Messung muss zuerst wiederhergestellt werden. Bitte Wiederherstellung erneut versuchen."
    otherOperationActive || state.rendererCapture.active -> "Import, Aktivierung, Stilwechsel oder andere Messung zuerst abschließen."
    !state.rendererBenchmarkDataAvailable -> "Die mitgelieferten Boston-Testkarten fehlen. App erneut öffnen; bei weiterem Fehler die vollständige APK installieren. Nutzerkarten müssen für den Test nicht importiert werden."
    !state.surfaceAttached -> "Kartenanzeige noch nicht bereit. App im Vordergrund öffnen und den Kartenaufbau abwarten."
    state.presentationPending -> "Der Kartenstil wird noch aufgebaut. Bitte warten, bis die Darstellung fertig ist."
    !state.viewport.latitude.isFinite() || state.viewport.latitude !in -85.0..85.0 ||
        !state.viewport.longitude.isFinite() || state.viewport.longitude !in -180.0..180.0 ||
        !state.viewport.metresToPixels.isFinite() || state.viewport.metresToPixels !in .00001..10.0 ||
        !state.viewport.rotationRadians.isFinite() || state.viewport.width !in 1..MAX_CHART_WIDTH ||
        state.viewport.height !in 1..MAX_CHART_HEIGHT -> "Die Kartenansicht ist ungültig. Karte neu aktivieren und erneut versuchen."
    else -> null
}

/** Diagnostic arms only; ordinary rendering retains its build defaults. */
enum class RendererViewportReuseVariant { OFF, SOUNDINGS, SPATIAL, GPU, ALL }

/** Fixed public Boston recipe. Counts are submitted native frames, never display FPS. */
data class RendererBenchmarkConfig(
    val repetitions: Int = 2,
    val clientRunToken: String = java.util.UUID.randomUUID().toString(),
    val deadlineElapsedRealtimeMs: Long = 0,
    val recoveryBudgetMs: Long = 120_000,
    val requestedWorkers: Int = 1,
    val capacityRecipeId: String? = null,
    val runKind: RendererRunKind = RendererRunKind.SCRIPTED,
    val liveMode: ChartBufferTransformMode = ChartBufferTransformMode.CONTROL,
    val liveDurationMs: Long = 10_000,
    val liveReplay: Boolean = true,
    val liveReplayRecipe: RendererReplayRecipe = RendererReplayRecipe.LEGACY,
    val liveRepaintTransforms: Boolean = true,
    val capacityArm: ResidentCapacityArm? = null,
    val viewportReuseCandidate: RendererViewportReuseVariant? = null,
    val viewportReuseDetailed: Boolean = false,
    /** Separate exact readback/query evidence. Never enabled by a normal timing arm. */
    val viewportReuseParity: Boolean = false,
    /** Independent default-OFF diagnostic; not a member of the three reuse flags. */
    val normalRenderAbort: Boolean = false,
    /** Explicit current-catalog diagnostic; absent retains the legacy public Boston arm. */
    val currentResidentLive: CurrentResidentLiveArm? = null,
    /** Separate bounded read-only FULL oracle; never enabled by timing or touch replay. */
    val currentLiveParityOnly: Boolean = false,

) {
    init {
        require(repetitions in 1..4)
        require(clientRunToken.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(deadlineElapsedRealtimeMs >= 0)
        require(recoveryBudgetMs in 1..120_000)
        require(requestedWorkers in setOf(1, 2, 4, 6))
        if (viewportReuseCandidate != null) require(requestedWorkers == 1 && (runKind == RendererRunKind.SCRIPTED ||
            runKind == RendererRunKind.RESIDENT_CAPACITY && capacityRecipeId == "CURRENT_VIEW_REUSE")) {
            "Viewport reuse arms require a scripted or CURRENT_VIEW_REUSE single-worker measurement"
        }
        if (runKind == RendererRunKind.LIVE_INPUT) {
            require(requestedWorkers == 1 && repetitions == 1)
            require(liveMode != ChartBufferTransformMode.OFF)
            require(liveDurationMs in 5_000..30_000)
        }
        require(capacityRecipeId == null || (capacityRecipeId in setOf("CURRENT_VIEWPORT_V1", "CURRENT_VIEW_REUSE") &&
            runKind == RendererRunKind.RESIDENT_CAPACITY && (capacityArm?.durationMs ?: 0) >= 10_000))
        if (capacityRecipeId == "CURRENT_VIEW_REUSE") require(viewportReuseCandidate != null &&
            capacityArm?.cacheMode == ResidentCapacityCacheMode.WARM) { "CURRENT_VIEW_REUSE retains the current geometry and requires an explicit variant" }
        require(!viewportReuseDetailed || capacityRecipeId == "CURRENT_VIEW_REUSE")
        require(!viewportReuseParity || (capacityRecipeId == "CURRENT_VIEW_REUSE" &&
            viewportReuseCandidate in setOf(RendererViewportReuseVariant.OFF, RendererViewportReuseVariant.ALL) &&
            !viewportReuseDetailed)) { "Parity is a separate OFF/ALL current-view evidence run, never detailed timing" }
        require(!normalRenderAbort || (runKind == RendererRunKind.LIVE_INPUT && liveMode == ChartBufferTransformMode.CONTROL &&
            viewportReuseCandidate == null && !viewportReuseParity && !viewportReuseDetailed)) {
            "Normal-render cancellation is a separate plain FULL LIVE_INPUT/CONTROL diagnostic"
        }
        require(currentResidentLive == null || (runKind == RendererRunKind.LIVE_INPUT &&
            liveMode == ChartBufferTransformMode.CONTROL && capacityArm == null && capacityRecipeId == null &&
            viewportReuseCandidate == null && !viewportReuseDetailed && !viewportReuseParity)) {
            "Current-resident live input is a separate owner-pinned CONTROL arm"
        }
        require(!currentLiveParityOnly || (currentResidentLive != null && runKind == RendererRunKind.LIVE_INPUT &&
            liveMode == ChartBufferTransformMode.CONTROL && !liveReplay && liveDurationMs == 15_000L &&
            capacityArm == null && capacityRecipeId == null && viewportReuseCandidate == null &&
            !viewportReuseParity && !viewportReuseDetailed)) {
            "Current-live FULL parity is a separate six-pose owner diagnostic, never replay or timing"
        }
        if (runKind == RendererRunKind.RESIDENT_CAPACITY) {
            require(requestedWorkers == 1 && repetitions == 1 && capacityArm != null)
        } else require(capacityArm == null)
    }
}

data class RendererBenchmarkStatus(
    val active: Boolean = false,
    val runId: String = "",
    val phase: String = "idle",
    val completedGroups: Int = 0,
    val totalGroups: Int = 0,
    val groupFrame: Int = 0,
    val message: String = "",
    val reportFile: String = "",
    val restored: Boolean = false,
    val clientRunToken: String = "",
    val deadlineElapsedRealtimeMs: Long = 0,
    val recoveryBudgetMs: Long = 0,
    val cancelReason: String = "",
    val recoveryDeadlineExceeded: Boolean = false,
    val runKind: RendererRunKind = RendererRunKind.SCRIPTED,
    val liveInputEnabled: Boolean = false,
    val liveReplay: Boolean = false,
    val liveReplayRecipe: RendererReplayRecipe = RendererReplayRecipe.LEGACY,
    val liveRepaintTransforms: Boolean = true,

)
