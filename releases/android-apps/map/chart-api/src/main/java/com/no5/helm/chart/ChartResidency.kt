package com.no5.helm.chart

/** Explicit opt-in transport. Merely constructing this catalog performs no IO. */
data class ChartResidentFile(val path: String, val sha256: String)
data class ChartResidentSource(val provider: String, val name: String, val sourceKey: String,
    val edition: Int, val update: Int, val order: Int, val files: List<ChartResidentFile>,
    val product: String? = null, val licenseHost: String? = null)
data class ChartResidentBudget(val cells: Int = 100, val bytes: Long = 512L * 1024 * 1024,
    val loadingBytes: Long = 128L * 1024 * 1024) {
    init { require(cells in 1..100 && loadingBytes > 0 && bytes >= loadingBytes) }
}
data class ChartResidentCatalog(val generation: String, val sources: List<ChartResidentSource>,
    val budget: ChartResidentBudget)
/** Explicit source transition. Bounds describe every released cell, independently of residency.
 * An empty bound set explicitly disables edge stopping when catalog extents are unknown. */
data class ChartResidentActivation(val id: String, val catalog: ChartResidentCatalog,
    val initialViewport: ChartViewport, val navigationBounds: ChartNavigationBounds,
    val cameraPolicy: ChartResidentCameraPolicy? = null)
data class ChartResidentDemand(val catalogGeneration: String, val cameraRevision: Long,
    val cameraKey: String, val policyKey: String, val camera: ChartViewport,
    val required: List<String>, val prefetch: List<String>)
enum class ChartResidentStep { IDLE, LOADED, BLOCKED, FAILED, CANCELLED }
/** One held arena, including retired source generations. The loader reservation is separate.
 * Bounds cover the native charged geometry calculation, never total process memory. */
data class ChartResidentGeometry(val geometryId: String, val sourceKey: String,
    val chargedBytes: Long, val pinned: Boolean, val reusableForCatalog: Boolean,
    val reservationBoundBytes: Long? = null)
/** complete concerns required geometry residency; it is no nautical/pixel coverage assertion. */
data class ChartResidencyStatus(val enabled: Boolean, val complete: Boolean,
    val catalogGeneration: String, val cameraKey: String, val policyKey: String,
    val cameraRevision: Long, val contentRevision: Long, val resident: Int, val loading: Int,
    val pinned: Int, val required: Int, val missing: Int, val chargedBytes: Long,
    val geometryBytes: Long, val loadingBytes: Long, val peakCells: Int,
    val peakChargedBytes: Long, val loads: Long, val retains: Long, val evictions: Long,
    val failures: Long, val message: String, val sources: List<String>,
    val geometries: List<ChartResidentGeometry> = emptyList(),
    val budget: ChartResidentBudget? = null, val step: ChartResidentStep? = null)
data class ChartResidentUpdate(val result: EngineResult, val status: ChartResidencyStatus? = null,
    val step: ChartResidentStep? = null)

/** Immutable, owner-synchronous authority for measurement and proposed-camera policy.
 * Unloaded sources have no inferred reservation; absence remains unknown. */
data class ChartResidentBinding(val activation: ChartResidentActivation,
    val sourceGeneration: Long, val contentGeneration: Long, val surfaceGeneration: Long,
    val producerViewport: ChartViewport, val status: ChartResidencyStatus,
    val pixelsPerMillimetre: Double) {
    fun chartScaleFor(metresToPixels: Double): Double? =
        if (metresToPixels.isFinite() && metresToPixels > 0 && pixelsPerMillimetre.isFinite() && pixelsPerMillimetre > 0)
            pixelsPerMillimetre * 1000 / metresToPixels.coerceIn(.00001, 10.0) else null
}

/** In-memory count admission before a camera or its affine image is committed. */
data class ChartResidentCameraProposal(val basis: ChartViewport, val wanted: ChartViewport,
    val noZoomAlternative: ChartViewport? = null, val pureZoomInRecovery: Boolean = false,
    val expectedCatalogGeneration: String, val expectedSourceGeneration: Long,
    val expectedSurfaceGeneration: Long,
    val planningWidth: Int = wanted.width, val planningHeight: Int = wanted.height)
enum class ChartResidentCameraReason {
    ACCEPTED, NO_ZOOM_ALTERNATIVE, RECOVERY_ABOVE_BUDGET, BLOCKED, UNKNOWN, STALE_AUTHORITY
}
data class ChartResidentCameraDecision(val acceptedCamera: ChartViewport?,
    val reason: ChartResidentCameraReason, val requiredUpperBound: Int?, val cellBudget: Int,
    val catalogGeneration: String, val sourceGeneration: Long, val surfaceGeneration: Long,
    val conservative: Boolean = true, val recoveryAboveBudget: Boolean = false)
/** Catalog metadata/index is frozen by its producer. This call performs no IO or native reads. */
fun interface ChartResidentCameraGuard {
    fun evaluate(proposal: ChartResidentCameraProposal, budget: ChartResidentBudget): ChartResidentCameraDecision
    /** Bounded, IO-free startup/revalidation; the owner rechecks the returned candidate. */
    fun selectStart(start: ChartResidentCameraStart, budget: ChartResidentBudget): ChartResidentCameraDecision =
        evaluate(start.proposal(start.desired), budget)
}
/** The engine assigns the runtime source epoch; producers never guess it. */
fun interface ChartResidentCameraPolicy {
    fun bind(sourceGeneration: Long): ChartResidentCameraGuard
}
data class ChartResidentCameraStart(val desired: ChartViewport, val previous: ChartViewport?,
    val expectedCatalogGeneration: String, val expectedSourceGeneration: Long,
    val expectedSurfaceGeneration: Long, val planningWidth: Int, val planningHeight: Int) {
    fun proposal(camera: ChartViewport) = ChartResidentCameraProposal(desired, camera,
        expectedCatalogGeneration = expectedCatalogGeneration, expectedSourceGeneration = expectedSourceGeneration,
        expectedSurfaceGeneration = expectedSurfaceGeneration, planningWidth = planningWidth, planningHeight = planningHeight)
}
enum class ChartResidentStartState { NOT_REQUIRED, WAITING_SURFACE, READY, BLOCKED, UNKNOWN, STALE_AUTHORITY }


enum class ResidentCapacityCacheMode { EMPTY_GEOMETRY, WARM }
/** One arm; these values are configurations to measure, not a hardware capacity claim. */
data class ResidentCapacityArm(val budget: ChartResidentBudget,
    val cacheMode: ResidentCapacityCacheMode = ResidentCapacityCacheMode.EMPTY_GEOMETRY,
    val expectedCatalogGeneration: String, val expectedSourceGeneration: Long,
    val durationMs: Long = 10_000) {
    init {
        require(expectedCatalogGeneration.matches(Regex("[a-f0-9]{64}")))
        require(expectedSourceGeneration >= 0 && durationMs in 5_000..90_000)
    }
}
