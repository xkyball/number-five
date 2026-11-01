package com.no5.helm.chart
import android.view.Surface
import kotlinx.coroutines.flow.StateFlow
data class ChartEngineConfig(val dataDirectory: String, val appVersion: String = "unknown", val densityDpi: Int? = null, val sourceCommit: String = "unknown", val touchOutlinesEnabled: Boolean = true)
data class EngineResult(val success: Boolean, val message: String = "")
data class ChartViewport(val latitude: Double = 0.0, val longitude: Double = 0.0, val metresToPixels: Double = 0.02, val rotationRadians: Double = 0.0, val width: Int = 2000, val height: Int = 1200)
data class ChartCameraAdjustment(val zoomFactor: Double = 1.0, val rotationRadians: Double? = null,
    val center: GeoProjection? = null, val deadlineUptimeMs: Long)
/** Semantic camera controls; physical input identities never cross this boundary. */
enum class ChartCameraControl { ZOOM_STEPS, ROTATE_STEPS, CENTER, NORTH_UP, RESET }
class ChartControlResult {
    var id=0L;var replacementId=0L;var sequence=0L;var state=0;var reason="";var revision=-1L;var appliedRevision=-1L;var presentedRevision=-1L
    var desiredScale=Double.NaN;var actualScale=Double.NaN
}
data class ChartCameraCommit(val revision:Long,val viewport:ChartViewport)
enum class ChartPalette { DAY, DUSK, NIGHT }
data class S52Settings(val palette: ChartPalette = ChartPalette.DAY, val safetyContourMetres: Double = 3.0, val objectFilters: ChartObjectFilters = ChartObjectFilters())
data class ScreenPoint(val x: Double, val y: Double)
data class ChartFeature(val id: String, val name: String, val attributes: Map<String, String>)
data class GeoProjection(val latitude: Double, val longitude: Double, val headingRadians: Double = 0.0)
typealias OwnshipProjection = GeoProjection
typealias AisProjection = GeoProjection
typealias WaypointProjection = GeoProjection
data class RouteProjection(val points: List<GeoProjection>, val id: String = "", val revision: Long = 0, val role: ChartRouteRole = ChartRouteRole.SAVED, val pointIds: List<String> = emptyList(), val pointNames: List<String> = emptyList())
data class ChartOverlays(val ownship: GeoProjection? = null, val ais: List<GeoProjection> = emptyList(),
    val waypoints: List<GeoProjection> = emptyList(), val route: RouteProjection? = null,
    val aisObjects: List<ChartOverlayObject> = emptyList(), val waypointObjects: List<ChartOverlayObject> = emptyList())
data class ChartEngineStatus(val initialized: Boolean = false, val chartLoaded: Boolean = false, val completePortrayal: Boolean = false, val featureCount: Int = 0, val unsupportedFeatures: Int = 0, val viewport: ChartViewport = ChartViewport(), val message: String = "", val rendererDiagnostics: String = "Renderer not initialized", val presentation: ChartPresentation = ChartPresentation(), val presentationGeneration: Long = 0, val presentationAvailable: Boolean = false, val surfaceAttached: Boolean = false, val presentationPending: Boolean = false, val confirmedFrame: ChartFrame? = null, val rendererCapture: RendererCaptureStatus = RendererCaptureStatus(), val rendererBenchmark: RendererBenchmarkStatus = RendererBenchmarkStatus(), val loadedChartCells: List<String> = emptyList(), val rendererBenchmarkDataAvailable: Boolean = false, val rendererBenchmarkRecoveryPending: Boolean = false, val outlines: ChartOutlines? = null, val touchPreview: ChartTouchPreview? = null, val touchOutlinesAvailable: Boolean = false, val touchOutlinesEnabled: Boolean = true, val bufferTransformMode: ChartBufferTransformMode = ChartBufferTransformMode.OFF, val bufferTransformGeneration: Long = 0, val bufferTransformBaseReady: Boolean = false, val surfaceGeneration: Long = 0, val navigationBounds: ChartNavigationBounds? = null, val chartSourceGeneration: Long = 0, val chartContentGeneration: Long = 0, val residentCatalogActive: Boolean = false, val residentCheckpointRevision: Long = -1, val chartScaleDenominator: Double? = null, val residentCatalogGeneration: String? = null, val residentCameraDecision: ChartResidentCameraDecision? = null, val sourcePresentationUnsupported: Boolean = false, val residentCameraAdmissionRevision: Long = 0, val residentCameraReady: Boolean = true, val residentStartState: ChartResidentStartState = ChartResidentStartState.NOT_REQUIRED, val objectSelection: ChartObjectHighlight = ChartObjectHighlight(), val objectFilters: ChartObjectFilterState = ChartObjectFilterState(), val cameraBoundsDecision: ChartCameraBoundsDecision? = null, val worldUnderlayCandidate: Boolean = false)
interface ChartEngine : AutoCloseable {
    val supportsPresentationSlots: Boolean get() = false
    val supportsResidentCatalog: Boolean get() = false
    /** Qualification only. Not a saved preference or coverage/residency source. Defaults OFF. */
    suspend fun setWorldUnderlayCandidate(enabled: Boolean): EngineResult = EngineResult(false, "World background unavailable")
    suspend fun initialize(config: ChartEngineConfig): EngineResult
    fun attachSurface(surface: Surface, width: Int, height: Int)
    /** Local opaque loan token, renewed for every binding even when the Surface object is reused. */
    fun attachSurface(surface: Surface, width: Int, height: Int, attachmentOwner: Any) = attachSurface(surface, width, height)
    /** Two immutable image slots; default keeps compatibility with other engines. */
    fun attachPresentationSurfaces(surface0: Surface, surface1: Surface, width: Int, height: Int) = attachSurface(surface0, width, height)
    fun attachPresentationSurfaces(surface0: Surface, surface1: Surface, width: Int, height: Int, attachmentOwner: Any) =
        attachPresentationSurfaces(surface0, surface1, width, height)
    fun detachSurface()
    /** Release the producer only after EGL has detached on its owning worker. */
    fun detachSurface(onDetached: () -> Unit) { detachSurface(); onDetached() }
    /** Retire only this native Surface loan; an old view must not detach a replacement. */
    fun detachSurface(expectedSurface: Surface, onDetached: () -> Unit) = detachSurface(onDetached)
    fun detachSurface(expectedSurface: Surface, attachmentOwner: Any, onDetached: () -> Unit) =
        detachSurface(expectedSurface, onDetached)
    /** Repaint the current loan after a visible window traversal; true is admission, not presentation. */
    fun refreshSurface(expectedSurface: Surface, attachmentOwner: Any): Boolean = false
    fun setViewport(viewport: ChartViewport)
    /** Absolute camera intent with the same numeric receipt/result contract as relative controls. */
    fun submitCameraViewport(viewport:ChartViewport,sequence:Long,inputUptimeMs:Long):Long = 0
    /** Saved/configuration camera before the first Surface. Confirms ownership, never JNI or a frame. */
    suspend fun restoreCameraViewport(viewport:ChartViewport,expectedSourceGeneration:Long):Boolean = false
    fun moveCamera(motion: ChartMotion) = Unit
    /** False uses the ordinary native full-render touch path. Active measurements reject changes. */
    fun setTouchOutlinesEnabled(enabled: Boolean): Boolean = false
    /** UI-only touch entry; other camera sources retain full rendering. */
    fun beginTouchGesture(): Boolean = false
    fun updateTouchViewport(viewport: ChartViewport) = Unit
    /** Contact may end while pan inertia continues using the same camera owner. */
    fun setTouchMotionState(state: ChartCameraMotionState) = Unit
    fun endTouchGesture() = Unit
    fun interruptTouchGesture() = Unit
    fun bufferConsumed(timestampNanos: Long) = Unit
    /** The UI promoted this exact slot and tag with its matching camera basis. */
    fun presentationBufferConsumed(slot: Int, timestampNanos: Long) = bufferConsumed(timestampNanos)
    /** Reserve an exact acquired image before changing the UI front; commit releases the old slot. */
    fun presentationBufferAcquired(slot: Int, timestampNanos: Long): Boolean = true
    fun setBufferTransformMode(mode: ChartBufferTransformMode): EngineResult = EngineResult(false, "T0 buffer transformation unavailable")
    fun chartBufferForTimestamp(timestampNanos: Long): ChartBufferSnapshot? = null
    /** Passive diagnostics only: never observes, acknowledges or selects a rendering basis. */
    fun chartBufferDiagnostics(timestampNanos: Long): ChartBufferSnapshot? = null
    fun bufferTransformCameraRevision(): Long = -1
    suspend fun zoomCamera(factor: Double, anchor: ScreenPoint, frame: ChartFrame): Boolean = false
    suspend fun centerCamera(position: GeoProjection, rotationRadians: Double? = null): Boolean = false
    /** Admit an action to the same camera owner used by touch. Receipt never waits for JNI or a frame. */
    fun submitCameraControl(control:ChartCameraControl,amount:Double,sequence:Long,physicalUptimeMs:Long,
        latitude:Double=Double.NaN,longitude:Double=Double.NaN,homeScale:Double=Double.NaN):Long = 0
    /** An already accepted intent must never rebind itself to a newer chart or Surface. */
    fun submitCameraControl(control:ChartCameraControl,amount:Double,sequence:Long,physicalUptimeMs:Long,
        latitude:Double,longitude:Double,homeScale:Double,expectedSourceGeneration:Long,expectedSurfaceGeneration:Long):Long = 0
    fun cameraControlResult(id:Long,destination:ChartControlResult):Boolean = false
    fun cancelCameraControl() = Unit
    fun commitCameraAdjustment(viewport:ChartViewport,expectedSource:Long,expectedSurface:Long,commandId:Long):ChartCameraCommit? = null
    suspend fun adjustCamera(command: ChartCameraAdjustment, confirmed: (ChartViewport) -> Unit): ChartViewport? = null
    suspend fun projectCoordinates(positions: List<GeoProjection>, frame: ChartFrame): List<ScreenPoint>? = null
    suspend fun unprojectCoordinate(point: ScreenPoint, frame: ChartFrame): GeoProjection? = null
    suspend fun queryFeature(point: ScreenPoint, frame: ChartFrame): List<ChartFeature> = emptyList()
    suspend fun queryObjects(request: ChartObjectQuery): ChartObjectQueryResult = ChartObjectQueryResult(ChartObjectQueryState.UNAVAILABLE)
    /** Stable source identity, never an arena pointer. Null clears the independent highlight layer. */
    suspend fun selectObject(reference: ChartObjectReference?, radiusSurfacePixels: Double = 24.0): Boolean = false
    suspend fun applyObjectFilters(filters: ChartObjectFilters): EngineResult = EngineResult(false, "S52-Filter nicht verfügbar")
    fun setS52Settings(settings: S52Settings)
    /** Opt-in source switch; other engines retain their eager activation path. */
    suspend fun activateResidentCatalog(activation: ChartResidentActivation): ChartResidentUpdate =
        ChartResidentUpdate(EngineResult(false, "Resident loading unavailable"))
    /** P3 opt-in owner APIs; existing eager activation remains the default. */
    suspend fun configureResidentCatalog(catalog: ChartResidentCatalog): ChartResidentUpdate =
        ChartResidentUpdate(EngineResult(false, "Resident loading unavailable"))
    suspend fun submitResidentDemand(demand: ChartResidentDemand): ChartResidentUpdate =
        ChartResidentUpdate(EngineResult(false, "Resident loading unavailable"))
    /** Explicit policy authority change; preserves immutable resident geometry. */
    suspend fun setResidentPlanningPolicy(policyKey: String): ChartResidentUpdate =
        ChartResidentUpdate(EngineResult(false, "Resident loading unavailable"))
    suspend fun advanceResidentLoading(): ChartResidentUpdate =
        ChartResidentUpdate(EngineResult(false, "Resident loading unavailable"))
    suspend fun readResidency(): ChartResidentUpdate =
        ChartResidentUpdate(EngineResult(false, "Resident loading unavailable"))
    /** Successful return is an owner/file-reader release checkpoint; preserves the camera. */
    suspend fun clearChartSelection(expectedSourceGeneration: Long): EngineResult =
        EngineResult(false, "Chart selection release unavailable")
    /** After replacement, release removed arenas. False/deferred never authorizes file deletion. */
    suspend fun releaseRetiredChartSources(expectedCatalogGeneration: String): EngineResult =
        EngineResult(false, "Retired source release unavailable")
    suspend fun readResidentBinding(): ChartResidentBinding? = null
    /** Install the committed whole-catalog index against the current runtime source authority. */
    fun installResidentCameraGuard(expectedCatalogGeneration: String, guard: ChartResidentCameraGuard?): Boolean = false
    /** Synchronous and in-memory, suitable before changing a visible camera transform. */
    fun evaluateResidentCamera(proposal: ChartResidentCameraProposal): ChartResidentCameraDecision? = null
    /** Prefer the tagged calibration in readResidentBinding for a multi-step policy decision. */
    suspend fun residentChartScaleFor(proposedMetresToPixels: Double): Double? =
        readResidentBinding()?.chartScaleFor(proposedMetresToPixels)
    /** Actual producer canvas (including raster reserve) for the next P2 plan. */
    suspend fun readResidentPlanningViewport(): ChartViewport? = null
    suspend fun loadChartSet(id: String, paths: List<String>): EngineResult
    /** Import preflight on the chart worker; must leave the loaded set and presentation untouched. */
    suspend fun validateChartFiles(paths: List<String>, cacheDirectory: String): EngineResult = EngineResult(false, "Chart validation unavailable")
    /** Older engines can validate without supplying optional catalog metadata. */
    suspend fun validateChartFilesWithMetadata(paths: List<String>, cacheDirectory: String): ChartValidationResult =
        ChartValidationResult(validateChartFiles(paths, cacheDirectory))
    /** Plaintext SENC from an already verified private Pi export; never identify it as ISO 8211. */
    suspend fun validatePiCacheFilesWithMetadata(paths: List<String>, cacheDirectory: String): ChartValidationResult =
        ChartValidationResult(EngineResult(false, "Pi cache validation unavailable"))
    suspend fun profileRenderer(): EngineResult = EngineResult(false, "Renderer profiling is unavailable")
    /** Starts an engine-owned job; UI recreation does not cancel the measurement. */
    suspend fun startRendererBenchmark(config: RendererBenchmarkConfig = RendererBenchmarkConfig()): EngineResult = EngineResult(false, "Style benchmark unavailable")
    fun cancelRendererBenchmark() = Unit
    fun cancelRendererBenchmark(expectedRunId: String): EngineResult = EngineResult(false, "Run-bound cancellation unavailable")
    fun rendererBenchmarkStatus(clientRunToken: String? = null): RendererBenchmarkStatus = status.value.rendererBenchmark
    /** Debug caller must separately pin the durable selection/source files. No state mutation. */
    suspend fun snapshotRendererOuterState(snapshotId: String): String? = null
    /** Only restores a prevalidated owner journal already placed in the private recovery slot. */
    suspend fun restoreRendererOuterState(expectedSnapshotId: String): EngineResult = EngineResult(false, "Outer restore unavailable")
    suspend fun restoreInterruptedRendererBenchmark(): EngineResult = EngineResult(false, "Keine unterbrochene Messung wiederherzustellen")
    suspend fun startRendererCapture(config: RendererCaptureConfig = RendererCaptureConfig()): EngineResult = EngineResult(false, "Renderer capture is unavailable")
    fun rendererUiSink(): RendererUiSink? = null
    fun rendererLiveInputSink(): RendererLiveInputSink? = null
    suspend fun stopRendererCapture(): EngineResult = EngineResult(false, "Renderer capture is unavailable")
    /** Retrieve a completed report without starting or clearing a measurement. */
    suspend fun readRendererReport(): EngineResult = EngineResult(false, "No completed renderer report available")
    /** Refresh renderer diagnostics on demand instead of on every rendered frame. */
    suspend fun readRendererDiagnostics(): EngineResult = EngineResult(false, "Renderer diagnostics unavailable")
    suspend fun applyPresentationProfile(value: ChartPresentation): EngineResult = EngineResult(false, "Presentation profiles are unavailable")
    suspend fun loadChartSource(id: String, source: ChartSource): EngineResult = when (source) {
        is ChartSource.PublicFiles -> loadChartSet(id, source.paths)
        is ChartSource.PiCacheFiles -> EngineResult(false, "Pi cache adapter is unavailable")
        is ChartSource.OChartsPackage -> EngineResult(false, "Licensed chart provider is unavailable")
    }
    suspend fun queryFeature(point: ScreenPoint): List<ChartFeature>
    fun setOwnship(state: OwnshipProjection)
    fun clearOwnship() = Unit
    /** Queue the newest live overlay snapshot; an unrendered older snapshot may be superseded. */
    fun setOverlays(value: ChartOverlays) = Unit
    suspend fun applyOverlays(value: ChartOverlays): Boolean = false
    fun setAisTargets(targets: List<AisProjection>)
    fun setRouteProjection(route: RouteProjection?)
    fun setWaypointProjection(waypoints: List<WaypointProjection>)
    val status: StateFlow<ChartEngineStatus>
}
