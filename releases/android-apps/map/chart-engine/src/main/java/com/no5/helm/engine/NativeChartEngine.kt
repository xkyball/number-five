package com.no5.helm.engine
import android.util.Log
import android.view.Surface
import com.no5.helm.chart.*
import com.no5.helm.library.ChartCellMetadataDecoder
import com.no5.helm.library.S57Identification
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import java.io.File
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

private data class NormalRenderPresentationPermit(val bufferGeneration: Long, val reservedSlot: Int,
    val currentFrontSlot: Int)

internal object NativeBridge {
    init { System.loadLibrary("no5_chart") }
    external fun presentationPrepare(dataDirectory: String, style: String, symbols: String): Long
    external fun presentationDiscard(token: Long)
    external fun presentationApply(engine: Long, token: Long, relief: Boolean, generation: Long): Boolean
    external fun engineCreate(dataDirectory: String): Long
    external fun worldUnderlaySet(engine: Long, enabled: Boolean)
    external fun engineDestroy(engine: Long)
    external fun benchmarkRunBegin(engine: Long, runId: String, deadlineElapsedRealtimeMs: Long): Boolean
    external fun benchmarkRunCancel(engine: Long, expectedRunId: String, reason: String): Boolean
    external fun benchmarkRunEnd(engine: Long, expectedRunId: String): Boolean
    external fun benchmarkPreparationConfigure(engine: Long, expectedRunId: String, requestedWorkers: Int): Boolean
    external fun benchmarkPreparationRead(engine: Long, expectedRunId: String): String
    external fun benchmarkPreparationRestore(engine: Long, expectedRunId: String): Boolean
    external fun benchmarkViewportReuseConfigure(engine: Long, expectedRunId: String, variant: String): Boolean
    external fun benchmarkViewportReuseRead(engine: Long, expectedRunId: String): String
    external fun benchmarkViewportReuseRestore(engine: Long, expectedRunId: String): Boolean
    external fun benchmarkViewportReuseCapture(engine: Long, expectedRunId: String, expectedNativeFrame: Long,
        expectedCatalogGeneration: String, expectedContentRevision: Long, expectedNativeDataGeneration: Long,
        expectedLatitude: Double, expectedLongitude: Double, expectedPpm: Double, expectedRotation: Double,
        expectedWidth: Int, expectedHeight: Int): String
    external fun benchmarkCurrentLiveParityBegin(engine: Long, runId: String, captureId: String,
        expectedNormalAbort: Boolean, catalogGeneration: String, contentRevision: Long, dataGeneration: Long,
        sourceGeneration: Long, surfaceGeneration: Long, bufferGeneration: Long): Boolean
    external fun benchmarkCurrentLiveParityCapture(engine: Long, runId: String, captureId: String,
        expectedStamp: Long, expectedNativeFrame: Long, expectedCameraRevision: Long, expectedBufferGeneration: Long,
        latitude: Double, longitude: Double, ppm: Double, rotation: Double, width: Int, height: Int,
        expectedSlot: Int): String
    external fun benchmarkCurrentLiveParityEnd(engine: Long, runId: String, captureId: String): Boolean
    external fun renderAuthorityAdvance(engine: Long, epoch: Long, cameraRevision: Long,
        sourceGeneration: Long, surfaceGeneration: Long): Boolean
    external fun renderAuthorityApply(engine: Long, epoch: Long, cameraRevision: Long,
        sourceGeneration: Long, surfaceGeneration: Long, latitude: Double, longitude: Double,
        ppm: Double, rotation: Double, width: Int, height: Int): Boolean
    external fun benchmarkNormalAbortConfigure(engine: Long, expectedRunId: String, enabled: Boolean): Boolean
    external fun benchmarkNormalAbortRead(engine: Long, expectedRunId: String): String
    external fun benchmarkNormalAbortRestore(engine: Long, expectedRunId: String): Boolean
    external fun renderRequestTaggedWithAuthority(engine: Long, stamp: Long, expectedAppliedEpoch: Long,
        bufferGeneration: Long, reservedSlot: Int, currentFrontSlot: Int): Int
    external fun surfaceAttach(engine: Long, surface: Surface, width: Int, height: Int)
    external fun surfaceAttachPair(engine: Long, surface0: Surface, surface1: Surface, width: Int, height: Int)
    external fun surfaceAttachPairRaster(engine: Long, surface0: Surface, surface1: Surface, width: Int, height: Int, rasterWidth: Int, rasterHeight: Int)
    external fun rasterReserveSet(engine: Long, enabled: Boolean)
    external fun navigationBoundsRead(engine: Long): String
    external fun presentationSlotSelect(engine: Long, slot: Int)
    external fun surfaceDetach(engine: Long)
    external fun viewportSet(engine: Long, latitude: Double, longitude: Double, ppm: Double, rotation: Double, width: Int, height: Int)
    external fun cameraTransform(engine: Long, ax: Double, ay: Double, px: Double, py: Double, zoom: Double, rotation: Double)
    external fun projectCoordinates(engine: Long, coordinates: DoubleArray): DoubleArray
    external fun unprojectCoordinate(engine: Long, x: Double, y: Double): DoubleArray
    external fun distancePixels(engine: Long, x0: Double, y0: Double, x1: Double, y1: Double): Double
    external fun objectFiltersApply(engine: Long, category: String, hidden: Array<String>, flags: BooleanArray)
    external fun objectFiltersRead(engine: Long): String
    external fun overlaysReplace(engine: Long, counts: IntArray, positions: DoubleArray, labels: Array<String>, ids: Array<String>, headings: DoubleArray, role: String)
    external fun s52SettingsSet(engine: Long, palette: String, safety: Double)
    external fun chartSetOpen(engine: Long, paths: Array<String>): Boolean
    external fun chartSetOpenWithBackground(engine: Long, paths: Array<String>, backgroundPaths: Array<String>): Boolean
    external fun residentCatalogSet(engine: Long, generation: String, sources: Array<Array<String>>,
        cells: Int, bytes: Long, loadingBytes: Long)
    external fun residentCatalogActivate(engine: Long, generation: String, sources: Array<Array<String>>,
        cells: Int, bytes: Long, loadingBytes: Long)
    external fun residentDemandSet(engine: Long, generation: String, revision: Long, cameraKey: String,
        policyKey: String, required: Array<String>, prefetch: Array<String>): Boolean
    external fun residentAdvance(engine: Long): Int
    external fun residentPolicySet(engine: Long, policyKey: String)
    external fun residentPolicyRestore(engine: Long, policyKey: String)
    external fun residencyRead(engine: Long): String
    external fun residentPlanningViewport(engine: Long): DoubleArray
    external fun residentPixelsPerMillimetre(engine: Long): Double
    external fun residentRetiredRelease(engine: Long, generation: String): Boolean
    external fun residentGeometryReset(engine: Long, generation: String, cells: Int, bytes: Long,
        loadingBytes: Long, emptyGeometry: Boolean): Boolean
    external fun chartSelectionClear(engine: Long)
    external fun chartSetClose(engine: Long)
    external fun chartFileValidate(engine: Long, path: String, cachePath: String)
    external fun chartFileValidateMetadata(engine: Long, path: String, cachePath: String): String
    external fun featureQuery(engine: Long, x: Double, y: Double): String
    external fun objectQuery(engine: Long, x: Double, y: Double, radius: Double, offset: Int): String
    external fun objectSelect(engine: Long, sourceKey: String, index: Int, radius: Double): Boolean
    external fun projectionOwnshipReplace(engine: Long, data: DoubleArray)
    external fun projectionAisReplace(engine: Long, data: DoubleArray)
    external fun projectionRouteReplace(engine: Long, data: DoubleArray)
    external fun projectionWaypointsReplace(engine: Long, data: DoubleArray)
    external fun outlineGeometryRead(engine: Long): String
    external fun presentationTimestampAvailable(engine: Long): Boolean
    external fun renderRequestTagged(engine: Long, stamp: Long)
    external fun renderRequestAreasFirst(engine: Long, baseStamp: Long, fullStamp: Long): Boolean
    external fun renderRequestAreasReuse(engine: Long, stamp: Long): Boolean
    external fun renderRequest(engine: Long)
    external fun renderRequestWithoutPresent(engine: Long)
    external fun diagnosticMarkerSet(engine: Long, enabled: Boolean, latitude: Double, longitude: Double)
    external fun captureModeSet(engine: Long, mode: Int)
    external fun rendererIdentityRead(engine: Long): String
    external fun frameTimingRead(engine: Long): String
    external fun rendererDiagnostics(engine: Long): String
    external fun statusRead(engine: Long): String
}

/** IO preflight only. No camera/FULL/overlay snapshot may span this suspension. */
internal suspend fun rendererOuterSnapshotPreflight(
    validated: ChartResidentBinding, recovery: BenchmarkRecoveryStore,
    captureCurrentBinding: () -> ChartResidentBinding,
    verifySources: (ChartResidentActivation) -> Unit = { verifyResidentSourcePins(it) },
): ChartResidentBinding {
    withContext(Dispatchers.IO) {
        verifySources(validated.activation)
        // A journal created while hashing must also block this new snapshot.
        check(recovery.read() == null) { "Existing recovery journal must finish first" }
    }
    val current = captureCurrentBinding()
    check(current.activation == validated.activation &&
        current.sourceGeneration == validated.sourceGeneration &&
        current.contentGeneration == validated.contentGeneration &&
        current.surfaceGeneration == validated.surfaceGeneration &&
        current.producerViewport == validated.producerViewport &&
        current.status.policyKey == validated.status.policyKey &&
        current.status.budget == validated.status.budget &&
        current.status.complete && current.status.loading == 0) { "Snapshot preflight authority changed: VALIDATED_BINDING" }
    return current
}

/** A consumed coverage loan must schedule its owed FULL even without another camera event. */
internal fun presentationAckNeedsRender(mode: ChartBufferTransformMode, renderDirty: Boolean,
    pendingViewport: Boolean, checkpointDebt: Boolean): Boolean = renderDirty || pendingViewport ||
    checkpointDebt && when (mode) {
        ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS,
        ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_PADDED_CONTROL,
        ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE -> true
        else -> false
    }

/** Native-owner operation, injected only for deterministic JVM coverage of camera application. */
internal fun interface NativeCameraApplication {
    fun applyAndRead(engine:Long,desired:ChartViewport):ChartViewport
}
private object LiveNativeCameraApplication:NativeCameraApplication {
    override fun applyAndRead(engine:Long,desired:ChartViewport):ChartViewport {
        NativeBridge.viewportSet(engine,desired.latitude,desired.longitude,desired.metresToPixels,desired.rotationRadians,desired.width,desired.height)
        val pose=NativeBridge.residentPlanningViewport(engine)
        check(pose.size==6){"Native camera readback unavailable"}
        return ChartViewport(pose[0],pose[1],pose[2],pose[3],pose[4].toInt(),pose[5].toInt())
    }
}

/** Identity-only native loan. A member identifies the whole paired attachment. */
internal class NativeSurfaceOwner(private val first:Any,private val second:Any?=null,private val attachmentOwner:Any=Any()) {
    fun matches(surface:Any,expectedAttachmentOwner:Any?=null):Boolean =
        (surface===first || surface===second) && (expectedAttachmentOwner==null || expectedAttachmentOwner===attachmentOwner)
}
internal class NativeSurfaceRefresh(val command:Long,val surface:Any,val attachmentOwner:Any)

/** Process-local qualification input, default off. Only the signed test instrumentation sets it
 * before Activity creation; regular launches read no preference, intent or system property. */
internal object WorldUnderlayQualification {
    @JvmField @Volatile var initiallyEnabled = false
}

class NativeChartEngine internal constructor(private val cameraClock:CameraControlClock,
    private val nativeCameraApplication:NativeCameraApplication=LiveNativeCameraApplication,
    private val surfaceDiagnosticSink:(String)->Unit={},
    private val diagnosticPermission:()->Boolean={false}) : ChartEngine {
    constructor():this(CameraControlClock { android.os.SystemClock.uptimeMillis() },LiveNativeCameraApplication,
        {Log.i("No5Chart",it)})
    constructor(diagnosticPermission:()->Boolean):this(CameraControlClock { android.os.SystemClock.uptimeMillis() },
        LiveNativeCameraApplication,{Log.i("No5Chart",it)},diagnosticPermission)
    override val supportsPresentationSlots = true
    override val supportsResidentCatalog = true
    private val residentOwner = ChartResidentOwner()
    private data class PendingResidentStart(val desired: ChartViewport, val previous: ChartViewport?)
    @Volatile private var pendingResidentStart: PendingResidentStart? = null // Engine worker only.
    private var residentCameraPolicy: ChartResidentCameraPolicy? = null
    @Volatile private var preserveRestoredCamera = false
    private data class CameraGuardBinding(val catalogGeneration: String, val sourceGeneration: Long,
        val guard: ChartResidentCameraGuard)
    @Volatile private var residentCameraGuard: CameraGuardBinding? = null
    private data class CameraAdmission(val proposal: ChartResidentCameraProposal, val decision: ChartResidentCameraDecision,
        val catalog: ChartResidentCatalog, val mode: ChartBufferTransformMode, val geometry: ChartSurfaceGeometry)
    @Volatile private var residentCameraAdmission: CameraAdmission? = null
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "No5Chart") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val render = Channel<Unit>(Channel.CONFLATED)
    private val pendingViewport = AtomicReference<ChartViewport?>(null)
    private val cameraLock = Any()
    private val touchFrames = TouchFrameGate()
    private val consumedStamp = AtomicLong()
    private val bufferRegistry = ChartBufferRegistry()
    private val presentationGate = ChartPresentationGate()
    @Volatile private var pairedPresentation = false
    @Volatile private var cameraMotionState = ChartCameraMotionState.IDLE
    private val contentProgress = ChartContentProgress()
    private val progressResetPending = AtomicBoolean(true)
    private val surfaceCommandPending = AtomicBoolean()
    private val surfaceCommandRevision = AtomicLong()
    @Volatile private var workerSurfaceCommand=0L // Last successfully applied native attachment, never a queued intent.
    private var requestedSurfaceOwner:NativeSurfaceOwner?=null // cameraLock; latest announced attachment.
    private var workerSurfaceOwner:NativeSurfaceOwner?=null // Native worker; actual EGL loan.
    private var pendingSurfaceRefresh:NativeSurfaceRefresh?=null // cameraLock; latest owner-bound visible-window wake.
    private val surfaceDiagnosticLock=Any()
    private val surfaceDiagnostics=arrayOfNulls<String>(64)
    private var surfaceDiagnosticNext=0
    private var surfaceDiagnosticCount=0
    private var surfacePlainFrameCount=0 // Native worker; at most eight submissions per actual attachment.
    private var tracedPlainStamp=0L
    private fun recordSurfaceDiagnostic(row:String) {
        synchronized(surfaceDiagnosticLock) {
            surfaceDiagnostics[surfaceDiagnosticNext]=row
            surfaceDiagnosticNext=(surfaceDiagnosticNext+1)%surfaceDiagnostics.size
            if(surfaceDiagnosticCount<surfaceDiagnostics.size)surfaceDiagnosticCount++
        }
        surfaceDiagnosticSink(row)
    }
    private fun recordSurfaceCommand(action:String,command:Long) {
        recordSurfaceDiagnostic("surface action=$action command=$command appliedAttach=$workerSurfaceCommand generation=$surfaceRevision latestRequest=${surfaceCommandRevision.get()}")
    }
    private fun recordPlainSurfaceFrame(action:String,stamp:Long) {
        recordSurfaceDiagnostic("surfaceFrame action=$action stamp=$stamp slot=0 appliedAttach=$workerSurfaceCommand "+
            "surfaceGeneration=$surfaceRevision bufferGeneration=${bufferRegistry.generation()} "+
            "cameraRevision=$appliedCameraRevision requestedRevision=$requestedCameraRevision "+
            "sourceGeneration=${residentOwner.sourceGeneration} contentGeneration=${residentOwner.contentGeneration} "+
            "mode=${bufferTransformMode.name} paired=$pairedPresentation")
    }
    internal fun surfaceOwnerDiagnostics():String=synchronized(surfaceDiagnosticLock) {
        buildString {
            append("surfaceOwner appliedAttach=").append(workerSurfaceCommand).append(" generation=").append(surfaceRevision)
                .append(" latestRequest=").append(surfaceCommandRevision.get()).append(" pending=").append(surfaceCommandPending.get())
            for(age in surfaceDiagnosticCount-1 downTo 0) {
                append('\n').append(surfaceDiagnostics[(surfaceDiagnosticNext-1-age+surfaceDiagnostics.size)%surfaceDiagnostics.size])
            }
        }
    }
    private val transformTouchActive = AtomicBoolean()
    @Volatile private var bufferTransformMode = ChartBufferTransformMode.OFF
    private var transformSubmittedStamp = 0L // Chart worker only.
    private var transformBaseStageStamp = 0L
    private var plainSubmittedStamp = 0L // OFF full-image source identity, never an affine preview.
    private val touchCommandFrame = AtomicReference<ChartFrame?>()
    @Volatile private var touchTimestampSupported = false
    /** Runtime loss of exact buffer association is as visible as a missing extension. */
    private fun disableSourceTimestampProof() {
        touchTimestampSupported = false
        interruptTouch()
        mutable.update { it.copy(touchOutlinesAvailable = false, sourcePresentationUnsupported = true) }
    }
    private fun abandonUnconfirmedOutline(buffer: TouchFrameBuffer) {
        if (!touchFrames.discard(buffer)) return
        disableSourceTimestampProof()
        mutable.update { it.copy(confirmedFrame = null, message = "Outline-Buffer nicht bestätigt; Vollrender aktiv") }
        queueRender()
    }
    private fun interruptTouch(keepCommandFrame:Boolean=false) {
        synchronized(cameraLock){
            touchFrames.invalidate()
            cameraMotionState = ChartCameraMotionState.IDLE
            if (bufferTransformMode != ChartBufferTransformMode.OFF) {
                transformTouchActive.set(false)
                val generation = bufferRegistry.invalidate()
                mutable.update { it.copy(bufferTransformGeneration = generation, bufferTransformBaseReady = false) }
            }
            presentationGate.reset(bufferRegistry.generation(), keepFront = pairedPresentation && bufferTransformMode != ChartBufferTransformMode.OFF)
            progressResetPending.set(true)
        }
        if(!keepCommandFrame)touchCommandFrame.set(null)
        mutable.update { it.copy(touchPreview=null) }
    }
    override fun interruptTouchGesture() { interruptTouch(keepCommandFrame=true) }
    override fun setBufferTransformMode(mode: ChartBufferTransformMode): EngineResult {
        synchronized(cameraLock) {
            val state = mutable.value
            if (closed || profiling || state.rendererCapture.active || state.rendererBenchmark.active ||
                state.rendererBenchmarkRecoveryPending || benchmarkRuns.activeRun() != null ||
                touchFrames.held.get() || transformTouchActive.get())
                return EngineResult(false, "Finish the active gesture/measurement before changing T0 mode")
            if (mode != ChartBufferTransformMode.OFF && (!state.surfaceAttached || !touchTimestampSupported || !state.chartLoaded))
                return EngineResult(false, "T0 requires loaded charts and exact tagged-buffer support")
            if (mode in setOf(ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS, ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_FIXED,
                    ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_PADDED_CONTROL, ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE) && !pairedPresentation)
                return EngineResult(false, "Schneller Kartenmodus benötigt die hardwarebeschleunigte Bildübergabe ab Android API29")
            if (mode == bufferTransformMode) return EngineResult(true)
            interruptTouch(); bufferTransformMode = mode
            if (mode == ChartBufferTransformMode.OFF) presentationGate.reset(bufferRegistry.generation())
            mutable.update { it.copy(bufferTransformMode = mode,
                residentCameraDecision = null, residentCameraReady = if (residentOwner.active) false else it.residentCameraReady,
                residentStartState = if (residentOwner.active) ChartResidentStartState.UNKNOWN else it.residentStartState,
                chartScaleDenominator = if (residentOwner.active) null else it.chartScaleDenominator) }
        }
        queueRender()
        return EngineResult(true, "T0 $mode; wait for bufferTransformBaseReady before gestures; Display NOT_MEASURED")
    }
    override fun bufferTransformCameraRevision(): Long = requestedCameraRevision
    override fun chartBufferForTimestamp(timestampNanos: Long): ChartBufferSnapshot? = synchronized(cameraLock) {
        if (bufferTransformMode == ChartBufferTransformMode.OFF) return@synchronized bufferRegistry.lookup(timestampNanos)
        if (pairedPresentation) return@synchronized bufferRegistry.lookup(timestampNanos)
        val buffer = bufferRegistry.observe(timestampNanos)
        if (buffer == null) {
            mutable.update { it.copy(bufferTransformBaseReady = false) }
        } else bufferConsumed(timestampNanos)
        buffer
    }
    override fun chartBufferDiagnostics(timestampNanos:Long):ChartBufferSnapshot? = synchronized(cameraLock) {
        bufferRegistry.lookup(timestampNanos)
    }
    override fun presentationBufferConsumed(slot: Int, timestampNanos: Long) {
        if (!pairedPresentation) {
            bufferRegistry.lookup(timestampNanos)?.takeIf { it.producerCompleted }?.let { cameraControl.presented(it.cameraRevision) }
            bufferConsumed(timestampNanos); return
        }
        synchronized(cameraLock) {
            if (!presentationGate.committed(slot, timestampNanos)) return
            val buffer = bufferRegistry.lookup(timestampNanos)
            if (buffer != null && buffer.presentationSlot == slot) {
                bufferRegistry.observe(timestampNanos)
                cameraControl.presented(buffer.cameraRevision)
                bufferConsumed(timestampNanos)
            }
        }
        if (presentationAckNeedsRender(bufferTransformMode, renderDirty.get(), pendingViewport.get() != null,
                contentProgress.hasCheckpointDebt)) queueRender()
    }
    override fun presentationBufferAcquired(slot: Int, timestampNanos: Long): Boolean = synchronized(cameraLock) {
        val buffer = bufferRegistry.lookup(timestampNanos) ?: return@synchronized false
        buffer.presentationSlot == slot && presentationGate.acquired(buffer.generation, slot, timestampNanos)
    }
    override suspend fun setWorldUnderlayCandidate(enabled: Boolean): EngineResult = withContext(dispatcher) {
        val state=mutable.value
        if(closed || engine==0L || !state.surfaceAttached || profiling || state.rendererCapture.active ||
            state.rendererBenchmark.active || state.rendererBenchmarkRecoveryPending || benchmarkRuns.activeRun()!=null ||
            touchFrames.held.get() || transformTouchActive.get()) return@withContext EngineResult(false,"World background change requires idle renderer")
        if(state.worldUnderlayCandidate==enabled)return@withContext EngineResult(true)
        try {
            invalidateSourcePresentation()
            NativeBridge.worldUnderlaySet(engine,enabled)
            mutable.update{it.copy(worldUnderlayCandidate=enabled)}
            queueRender();EngineResult(true)
        } catch(failure:Exception){EngineResult(false,failure.message?:"World background failed")}
    }
    override fun setTouchOutlinesEnabled(enabled: Boolean): Boolean {
        val needsFullRender=synchronized(cameraLock) {
            val state=mutable.value
            if (bufferTransformMode != ChartBufferTransformMode.OFF) return false
            if(closed||profiling||state.rendererCapture.active||state.rendererBenchmark.active||state.rendererBenchmarkRecoveryPending)return false
            if(state.touchOutlinesEnabled==enabled)return true
            val pending=state.touchPreview!=null||touchFrames.current()!=null||state.confirmedFrame==null||requestedCameraRevision!=presentedCameraRevision
            // Retain the latest camera inbox. Only invalidate preview ownership and its buffer slot;
            // a native render already running must finish on its own worker.
            touchFrames.invalidate();touchCommandFrame.set(null)
            mutable.update { it.copy(touchOutlinesEnabled=enabled,touchPreview=null,confirmedFrame=if(pending)null else it.confirmedFrame) }
            pending
        }
        if(needsFullRender)queueRender()
        return true
    }
    override fun beginTouchGesture():Boolean {
        val state=mutable.value
        if (bufferTransformMode != ChartBufferTransformMode.OFF) return synchronized(cameraLock) {
            val base = bufferRegistry.lookup(bufferRegistry.observedStamp()) ?: return@synchronized false
            if (closed || profiling || !state.chartLoaded || !state.surfaceAttached ||
                !liveCameraAllowed(state) || state.rendererBenchmarkRecoveryPending || !touchTimestampSupported)
                return@synchronized false
            touchFrames.invalidate(); transformTouchActive.set(true)
            cameraMotionState = ChartCameraMotionState.DRAG
            touchFrames.held.set(bufferTransformMode == ChartBufferTransformMode.TRANSFORM_ONLY)
            touchCommandFrame.set(base.frame)
            mutable.update { it.copy(touchPreview = ChartTouchPreview(it.viewport, true,
                it.touchPreview?.showing == true, touchFrames.generation.get(), base.frame)) }
            true
        }
        if(closed||profiling||!state.touchOutlinesEnabled||!touchTimestampSupported||!state.chartLoaded||state.outlines==null||!normalCameraAllowed(state)||state.rendererBenchmarkRecoveryPending||(!state.surfaceAttached))return false
        val base=state.confirmedFrame?:state.touchPreview?.baseFrame?:return false
        val showing=state.touchPreview?.showing==true
        synchronized(cameraLock){touchFrames.invalidate();touchFrames.held.set(true)}
        var accepted=false
        mutable.update { current ->
            accepted=current.touchOutlinesEnabled && current.outlines===state.outlines && (current.confirmedFrame?:current.touchPreview?.baseFrame)==base && touchTimestampSupported && !profiling
            if(accepted)current.copy(touchPreview=ChartTouchPreview(current.viewport,true,showing,touchFrames.generation.get(),base)) else current
        }
        if(accepted)touchCommandFrame.set(base) else touchFrames.held.set(false)
        return accepted
    }
    override fun updateTouchViewport(viewport:ChartViewport) {
        val state=mutable.value
        if (!liveCameraAllowed(state) || profiling || state.rendererBenchmarkRecoveryPending) return
        if (bufferTransformMode != ChartBufferTransformMode.OFF && !transformTouchActive.get()) return
        if (bufferTransformMode == ChartBufferTransformMode.OFF && !touchFrames.held.get()) return
        cameraControl.absolute(viewport,0,cameraClock.now(),preview=true,
            expectedSource=state.chartSourceGeneration,expectedSurface=state.surfaceGeneration,diagnosticOrigin=diagnosticOrigin(state))
    }
    override fun endTouchGesture() {
        if (bufferTransformMode != ChartBufferTransformMode.OFF) {
            if (!transformTouchActive.getAndSet(false)) return
            cameraMotionState = ChartCameraMotionState.IDLE
            touchFrames.held.set(false)
            mutable.update { state -> state.copy(touchPreview = state.touchPreview?.takeIf { it.showing }?.copy(held = false)) }
            queueRender()
            return
        }
        if(!touchFrames.held.getAndSet(false))return
        mutable.update { state -> state.copy(touchPreview=state.touchPreview?.takeIf{it.showing}?.copy(held=false)) }
        // A real last-UP/CANCEL is definitive: no debounce or inertia timer.
        queueRender()
    }
    override fun setTouchMotionState(state: ChartCameraMotionState) {
        if (transformTouchActive.get() && state != ChartCameraMotionState.IDLE) {
            cameraMotionState = state
            liveInputBuffer?.let { it.disposition(it.currentInput(), "MOTION_${state.name}") }
        }
    }
    override fun bufferConsumed(timestampNanos:Long) {
        consumedStamp.set(timestampNanos)
        if (bufferTransformMode != ChartBufferTransformMode.OFF) {
            synchronized(cameraLock) {
                // A producer completion/deferred ACK cannot select an older basis after an unknown newer texture.
                if (timestampNanos != bufferRegistry.observedStamp()) return
                val buffer = bufferRegistry.lookup(timestampNanos) ?: return
                mutable.update { state ->
                    if (buffer.producerCompleted && buffer.stage == ChartContentStage.FULL && residentOwner.frameMatches(buffer.frame) && !transformTouchActive.get() && buffer.cameraRevision == requestedCameraRevision) {
                        presentedCameraRevision = buffer.cameraRevision; touchCommandFrame.set(null)
                        state.copy(viewport = buffer.frame.viewport, confirmedFrame = buffer.frame, touchPreview = null, bufferTransformBaseReady = true)
                    } else state.copy(bufferTransformBaseReady = true)
                }
            }
            return
        }
        val buffer=touchFrames.consume(timestampNanos)?:return
        mutable.update { state ->
            val frame=buffer.frame
            if(frame!=null&&touchFrames.valid(buffer,requestedCameraRevision)&&state.touchPreview?.generation==buffer.generation) {
                presentedCameraRevision=buffer.cameraRevision;touchCommandFrame.set(null)
                state.copy(viewport=frame.viewport,confirmedFrame=frame,touchPreview=null)
            } else state
        }
        if(buffer.frame==null||!touchFrames.valid(buffer,requestedCameraRevision)||renderDirty.get())queueRender()
    }
    private fun renderNative(): Boolean {
        val events = capacitySoftwareEvents ?: return renderNativeWork()
        val authority = capacityAuthority(appliedCameraRevision); val start = System.nanoTime()
        try {
            val rendered = renderNativeWork()
            val ended = System.nanoTime()
            if (rendered) events.recordDuration("RENDER_NATIVE_DISPATCH", start, ended, authority,
                buildJsonObject {
                    put("endpoint", "renderNative owner call including tag setup/JNI; not GPU time")
                    if (currentViewReuseSampling && currentViewReuseFrameCount++ < 256) {
                        val frame = runCatching {
                            val raw=NativeBridge.frameTimingRead(engine)
                            check(raw.toByteArray(Charsets.UTF_8).size<=65_536)
                            Json.parseToJsonElement(raw)
                        }.getOrElse { buildJsonObject { put("available",false);put("reason","NATIVE_METADATA_UNAVAILABLE_OR_LIMIT") } }
                        put("nativeFrame",frame)
                    }
                })
            return rendered
        } catch (failure: Exception) {
            events.recordDuration("RENDER_NATIVE_FAILED", start, System.nanoTime(), authority)
            throw failure
        }
    }
    private fun renderNativeWork():Boolean {
        if (surfaceCommandPending.get()) return false
        if (residentOwner.active && !mutable.value.residentCameraReady && !revalidateResidentStart()) return false
        if(touchFrames.held.get())return false
        val (frameMode, frameGeneration) = synchronized(cameraLock) {
            if (surfaceCommandPending.get() || residentOwner.active && !mutable.value.residentCameraReady) return false
            val mode = bufferTransformMode
            NativeBridge.rasterReserveSet(engine, mode == ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE)
            mode to bufferRegistry.generation()
        }
        val reserve = frameMode == ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE
        check(!normalAbortEnabled || frameMode == ChartBufferTransformMode.CONTROL) {
            "Normal-abort diagnostic requires plain CONTROL FULL rendering"
        }
        if (frameMode == ChartBufferTransformMode.TRANSFORM_RENDER_WITHHELD && transformTouchActive.get()) {
            // Draw the full latest camera on the same owner/context, but do not
            // advertise or swap a buffer which the consumer cannot have acquired.
            transformSubmittedStamp = 0
            NativeBridge.renderRequestWithoutPresent(engine)
            liveInputBuffer?.disposition(appliedLiveInputId, "FULL_RENDER_WITHHELD")
            return true
        }
        if (frameMode != ChartBufferTransformMode.OFF) {
            val geometry = surfaceGeometry ?: return false
            val generation = frameGeneration
            // Read the actual worker camera after applying the inbox, before submitting this exact tag.
            val raw = Json.parseToJsonElement(NativeBridge.statusRead(engine)).jsonObject
            if (!currentResidentLiveNativeValid(raw)) return false
            val camera = mutable.value.viewport.copy(latitude = raw.getValue("latitude").jsonPrimitive.double,
                longitude = raw.getValue("longitude").jsonPrimitive.double, metresToPixels = raw.getValue("ppm").jsonPrimitive.double,
                rotationRadians = raw.getValue("rotation").jsonPrimitive.double,
                width = geometry.frameWidth, height = geometry.frameHeight)
            transformBaseStageStamp = 0
            val base = if (!pairedPresentation && frameMode == ChartBufferTransformMode.TRANSFORM_AREAS_FIRST)
                bufferRegistry.submit(generation, System.nanoTime(), appliedCameraRevision, geometry, camera,
                    rendererSession, ChartContentStage.BASE_AREAS, sourceGeneration = residentOwner.sourceGeneration,
                    contentGeneration = residentOwner.contentGeneration) else null
            var reuse = false
            val buffer = synchronized(cameraLock) {
                if (surfaceCommandPending.get()) return false
                val slot = if (pairedPresentation) presentationGate.writableSlot(generation) ?: return false else -1
                // A blocked/stale slot cannot consume the one permitted coverage refresh.
                if (generation != bufferRegistry.generation()) return false
                if (progressResetPending.getAndSet(false)) contentProgress.reset()
                val progressMode = frameMode in setOf(ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS,
                    ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_FIXED, ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_PADDED_CONTROL,
                    ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE)
                val front = bufferRegistry.lookup(bufferRegistry.observedStamp())
                val gap = frameMode in setOf(ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS,
                    ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_PADDED_CONTROL, ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE) &&
                    front?.takeIf { it.generation == generation && it.frame.geometry == geometry }?.let {
                        chartBufferUncoveredFraction(it.frame.viewport, camera, geometry, it.raster)?.let { fraction -> fraction > 0.05 }
                    } == true
                val hadDebt = contentProgress.hasCheckpointDebt
                reuse = transformTouchActive.get() && (frameMode == ChartBufferTransformMode.TRANSFORM_AREAS_REUSE ||
                    progressMode && contentProgress.useReuse(true, System.nanoTime(), gap))
                if (!hadDebt && contentProgress.hasCheckpointDebt)
                    liveInputBuffer?.disposition(appliedLiveInputId, "COVERAGE_BEFORE_CHECKPOINT")
                val stage = if (reuse) ChartContentStage.REUSED_DETAILS_WITH_BASE else ChartContentStage.FULL
                val raster = producerRasterGeometry?.let { ChartBufferRaster.capture(it, camera, reserve) }
                val packet = bufferRegistry.submit(generation, System.nanoTime(), appliedCameraRevision, geometry, camera, rendererSession, stage, slot, raster, residentOwner.sourceGeneration, residentOwner.contentGeneration)
                    ?: return false
                if (pairedPresentation && !presentationGate.reserve(generation, slot, packet.stamp)) { bufferRegistry.discard(packet.stamp); return false }
                packet
            }
            transformSubmittedStamp = buffer.stamp
            base?.let { liveInputBuffer?.buffer(it, false) }
            liveInputBuffer?.buffer(buffer, false)
            try {
                if (pairedPresentation) NativeBridge.presentationSlotSelect(engine, buffer.presentationSlot)
                if (base != null) {
                    if (NativeBridge.renderRequestAreasFirst(engine, base.stamp, buffer.stamp)) transformBaseStageStamp = base.stamp
                    else bufferRegistry.discard(base.stamp)
                } else if (reuse) {
                    val hit = NativeBridge.renderRequestAreasReuse(engine, buffer.stamp)
                    liveInputBuffer?.disposition(appliedLiveInputId, if (hit) "AREA_REUSE_HIT" else "AREA_REUSE_FULL_FALLBACK")
                } else if (normalAbortEnabled) {
                    val completed = renderNormalAbortBuffer(buffer, camera)
                    if (!completed) return false // No refresh(true), FULL, camera failure or consumer ACK.
                } else if (currentResidentLiveLease != null) {
                    if (!renderCurrentResidentLiveBuffer(buffer)) return false
                } else NativeBridge.renderRequestTagged(engine, buffer.stamp)
            } catch (failure: Exception) {
                presentationGate.discard(generation, buffer.stamp)
                base?.let { bufferRegistry.discard(it.stamp) }; bufferRegistry.discard(buffer.stamp); throw failure
            }
            return true
        }
        if (pairedPresentation) NativeBridge.presentationSlotSelect(engine, 0)
        val preview=mutable.value.touchPreview
        plainSubmittedStamp = 0
        if(preview?.showing!=true){
            val geometry = surfaceGeometry
            if (touchTimestampSupported && geometry != null) {
                val raw = Json.parseToJsonElement(NativeBridge.statusRead(engine)).jsonObject
                val camera = mutable.value.viewport.copy(latitude = raw.getValue("latitude").jsonPrimitive.double,
                    longitude = raw.getValue("longitude").jsonPrimitive.double, metresToPixels = raw.getValue("ppm").jsonPrimitive.double,
                    rotationRadians = raw.getValue("rotation").jsonPrimitive.double, width = geometry.frameWidth, height = geometry.frameHeight)
                val packet = bufferRegistry.submit(frameGeneration, System.nanoTime(), appliedCameraRevision,
                    geometry, camera, rendererSession, sourceGeneration = residentOwner.sourceGeneration,
                    contentGeneration = residentOwner.contentGeneration) ?: return false
                plainSubmittedStamp = packet.stamp
                if(surfacePlainFrameCount<8) {
                    surfacePlainFrameCount++;tracedPlainStamp=packet.stamp
                    recordPlainSurfaceFrame("SUBMIT",packet.stamp)
                }
                try { NativeBridge.renderRequestTagged(engine, packet.stamp) }
                catch (failure: Exception) {
                    if(tracedPlainStamp==packet.stamp){recordPlainSurfaceFrame("FAILED",packet.stamp);tracedPlainStamp=0}
                    bufferRegistry.discard(packet.stamp); plainSubmittedStamp = 0; throw failure
                }
            } else NativeBridge.renderRequest(engine)
            return true
        }
        val buffer=touchFrames.begin(appliedCameraRevision,System.nanoTime())?:return false
        try { NativeBridge.renderRequestTagged(engine,buffer.stamp) }
        catch(failure:Exception){touchFrames.discard(buffer);disableSourceTimestampProof();throw failure}
        return true
    }

    /** Only the explicit full-image diagnostic calls this; APPLIED camera results are untouched. */
    private fun renderNormalAbortBuffer(buffer: ChartBufferSnapshot, actualNativeViewport: ChartViewport): Boolean {
        val runId = checkNotNull(normalAbortRunId)
        fun discard() {
            presentationGate.discard(buffer.generation, buffer.stamp); bufferRegistry.discard(buffer.stamp)
            if (transformSubmittedStamp == buffer.stamp) transformSubmittedStamp = 0
        }
        if (normalAbortFailure != null || benchmarkRunCancelled(runId) ||
            residentOwner.sourceGeneration != normalAbortSource || surfaceRevision != normalAbortSurface) {
            discard(); return false
        }
        return normalRenderOperation({ closed || benchmarkRunCancelled(runId) }, ::discard) {
            if (!currentResidentLiveNativeValid()) { discard(); return@normalRenderOperation false }
            val applied = checkNotNull(normalRenderAuthority.applied()) { "Actual applied render authority missing" }
            check(applied.cameraRevision == appliedCameraRevision && applied.sourceGeneration == residentOwner.sourceGeneration &&
                applied.surfaceGeneration == surfaceRevision) { "Native-applied render authority changed" }
            // Bind the captured applied token. A concurrently announced newer target remains newer.
            check(NativeBridge.renderAuthorityApply(engine, applied.epoch, applied.cameraRevision,
                applied.sourceGeneration, applied.surfaceGeneration, actualNativeViewport.latitude, actualNativeViewport.longitude,
                actualNativeViewport.metresToPixels, actualNativeViewport.rotationRadians,
                actualNativeViewport.width, actualNativeViewport.height)) { "Native applied render binding refused" }
            // Capture only the actual current reservation. Do not hold cameraLock through JNI: a
            // newer camera/source/surface authority must remain able to cancel the native render.
            val permit = synchronized(cameraLock) {
                if (!normalAbortEnabled || normalAbortRunId != runId || normalAbortFailure != null || closed ||
                    bufferTransformMode != ChartBufferTransformMode.CONTROL || buffer.generation <= 0 ||
                    buffer.generation != bufferRegistry.generation() || bufferRegistry.lookup(buffer.stamp) !== buffer ||
                    buffer.stage != ChartContentStage.FULL || buffer.producerCompleted ||
                    buffer.frame.geometry != surfaceGeometry || !residentOwner.frameMatches(buffer.frame) ||
                    residentOwner.sourceGeneration != normalAbortSource || surfaceRevision != normalAbortSurface ||
                    normalRenderAuthority.applied() !== applied || applied.cameraRevision != appliedCameraRevision ||
                    applied.cameraRevision != buffer.cameraRevision || applied.sourceGeneration != residentOwner.sourceGeneration ||
                    applied.surfaceGeneration != surfaceRevision || buffer.frame.sourceGeneration != residentOwner.sourceGeneration)
                    null
                else if (pairedPresentation) presentationGate.reservation(buffer.generation, buffer.presentationSlot, buffer.stamp)?.let {
                    NormalRenderPresentationPermit(it.generation, it.reservedSlot, it.currentFrontSlot)
                }
                else if (buffer.presentationSlot == -1) NormalRenderPresentationPermit(buffer.generation, -1, -1)
                else null
            }
            if (permit == null) { discard(); return@normalRenderOperation false }
            val completed = normalRenderOutcome(NativeBridge.renderRequestTaggedWithAuthority(engine, buffer.stamp, applied.epoch,
                permit.bufferGeneration, permit.reservedSlot, permit.currentFrontSlot),
                ::discard, {
                    observeCameraDisposition(appliedLiveOrigin, "SUPERSEDED_RENDER")
                    renderDirty.set(true); queueRender()
                }) == NormalRenderOutcome.FULL_COMPLETE
            if (completed && !currentResidentLiveNativeValid()) { discard(); false } else completed
        }
    }

    /** Baseline uses the same owner/cancellation boundary, without enabling native normal-abort. */
    private fun renderCurrentResidentLiveBuffer(buffer: ChartBufferSnapshot): Boolean {
        val lease = checkNotNull(currentResidentLiveLease)
        fun discard() {
            presentationGate.discard(buffer.generation, buffer.stamp); bufferRegistry.discard(buffer.stamp)
            if (transformSubmittedStamp == buffer.stamp) transformSubmittedStamp = 0
        }
        return normalRenderOperation({ closed || benchmarkRunCancelled(lease.runId) }, ::discard) {
            if (!currentResidentLiveNativeValid()) { discard(); return@normalRenderOperation false }
            NativeBridge.renderRequestTagged(engine, buffer.stamp)
            if (!currentResidentLiveNativeValid()) { discard(); false } else true
        }
    }

    private fun normalAbortReport(runId: String): JsonObject {
        return normalRenderAbortReport(NativeBridge.benchmarkNormalAbortRead(engine, runId), runId)
    }

    private fun restoreNormalAbortControls(expectedRunId: String): JsonObject {
        check(normalAbortRunId == null || normalAbortRunId == expectedRunId) { "Normal-abort recovery owner changed" }
        check(NativeBridge.benchmarkNormalAbortRestore(engine, expectedRunId)) { "Normal-abort controls not restored" }
        return normalAbortReport(expectedRunId).also {
            check(normalRenderAbortRestored(it)) { "Normal-abort restoration not confirmed" }
            stopNormalAbortLocal(expectedRunId)
        }
    }
    private fun prepareOutlines() {
        val value=runCatching { OutlinePreparation.prepare(NativeBridge.outlineGeometryRead(engine)) }.getOrElse {
            Log.w("No5Chart","Touch outlines unavailable; retain full-render touch",it);null }
        mutable.update { it.copy(outlines=value) }
    }
    private fun openNativeCharts(paths:Array<String>, backgrounds:List<String> = emptyList()):Boolean {
        invalidateSourcePresentation();synchronized(cameraLock){pendingViewport.set(null);pendingMotions.clear()};mutable.update { it.copy(outlines=null, navigationBounds=null) }
        val opened=if(backgrounds.isEmpty()) NativeBridge.chartSetOpen(engine,paths)
            else NativeBridge.chartSetOpenWithBackground(engine,paths,backgrounds.toTypedArray())
        residentOwner.eager(); sourceCameraGuardChanged()
        if(opened)prepareOutlines()
        return opened
    }
    private fun closeNativeCharts(){NativeBridge.chartSelectionClear(engine);invalidateSourcePresentation();mutable.update{it.copy(outlines=null, navigationBounds=null)};residentOwner.eager(); sourceCameraGuardChanged()}
    /** A source transition revokes old pixels as well as queries; camera interruption alone keeps the front. */
    private fun invalidateSourcePresentation() {
        interruptTouch(); markCameraPending()
        synchronized(cameraLock) {
            val generation = bufferRegistry.invalidate(); presentationGate.reset(generation, keepFront = false)
            plainSubmittedStamp = 0; transformSubmittedStamp = 0; transformBaseStageStamp = 0
            progressResetPending.set(true)
            mutable.update { it.copy(confirmedFrame = null, touchPreview = null, presentationAvailable = false,
                bufferTransformGeneration = generation, bufferTransformBaseReady = false, residentCameraDecision = null) }
        }
    }
    private val pendingMotions = mutableListOf<ChartMotion>()
    @Volatile private var requestedCameraRevision = 0L
    @Volatile private var appliedCameraRevision = 0L
    @Volatile private var presentedCameraRevision = -1L
    private val normalRenderAuthority = NormalRenderAuthorityTracker()
    @Volatile private var normalAbortRunId: String? = null
    @Volatile private var normalAbortEnabled = false
    @Volatile private var normalAbortFailure: String? = null
    private var normalAbortSource = -1L
    private var normalAbortSurface = -1L
    private var normalPublishedEpoch = 0L

    /** Called only after validated ownership/invalidation, never during Ring admission. */
    private fun announceNormalRenderAuthority(force: Boolean = false): NormalRenderAuthority? = synchronized(cameraLock) {
        if (!normalAbortEnabled || closed || engine == 0L) return@synchronized null
        try {
            val token = normalRenderAuthority.advance(requestedCameraRevision, residentOwner.sourceGeneration, surfaceRevision)
            if (force || token.epoch != normalPublishedEpoch) {
                check(NativeBridge.renderAuthorityAdvance(engine, token.epoch, token.cameraRevision,
                    token.sourceGeneration, token.surfaceGeneration)) { "Native render authority advance refused" }
                normalPublishedEpoch = token.epoch
            }
            token
        } catch (failure: Exception) {
            if (normalAbortFailure == null) normalAbortFailure = (failure.message ?: "Render authority unavailable").take(256)
            normalAbortRunId?.let { signalBenchmarkCancel(it, "Render authority unavailable") }
            null
        }
    }

    private fun markNormalRenderApplied(captured: NormalRenderAuthority?) {
        if (!normalAbortEnabled || captured == null) return
        if (!normalRenderAuthority.markApplied(captured, appliedCameraRevision, residentOwner.sourceGeneration, surfaceRevision)) {
            normalAbortFailure = "Applied render authority does not match the native-applied camera"
            normalAbortRunId?.let { signalBenchmarkCancel(it, "Applied render authority unavailable") }
        }
    }

    private fun stopNormalAbortLocal(expectedRunId: String) = synchronized(cameraLock) {
        if (normalAbortRunId == expectedRunId) {
            normalAbortEnabled = false; normalAbortRunId = null; normalAbortFailure = null
            normalRenderAuthority.clearApplied()
        }
    }
    private var frameRevision = 0L
    private val rendererSession = UUID.randomUUID().toString()
    private val mutable = MutableStateFlow(ChartEngineStatus())
    override val status = mutable.asStateFlow()
    @Volatile private var engine = 0L
    private var dataDirectory = ""
    private var appVersion = "unknown"
    private var sourceCommit = "unknown"
    private var densityDpi: Int? = null
    private var activeSettings = S52Settings()
    private val presentationRequests = AtomicLong(0)
    private val presentationPreparation = Semaphore(1)
    private val presentationPreparing = AtomicInteger(0)
    private var loadedChartId: String? = null
    private var loadedChartPaths = emptyList<String>()
    private var loadedBackgroundPaths = emptyList<String>()
    @Volatile private var profiling = false
    private val benchmarkRuns = BenchmarkRunRegistry()
    private val benchmarkWatchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var benchmarkJob: Job? = null
    private var benchmarkRecoveryJob: Job? = null
    private var benchmarkDataAvailable = false
    private var benchmarkRecoveryStore: BenchmarkRecoveryStore? = null
    private var pendingBenchmarkRecovery: BenchmarkUserState? = null
    private var benchmarkRecoveryError: String? = null
    private var startupSelectionAllowed = true
    private var activeOverlays = ChartOverlays()
    private val overlayInbox = OverlaySnapshotInbox(activeOverlays)
    private var captureBuffer: RendererCaptureBuffer? = null
    @Volatile private var activeUiCapture: RendererUiCaptureBuffer? = null
    override fun rendererUiSink(): RendererUiSink? = activeUiCapture
    @Volatile private var liveInputBuffer: RendererLiveInputBuffer? = null
    private val currentResidentLiveSnapshots = CurrentResidentLiveSnapshotSlot()
    @Volatile private var currentResidentLiveLease: CurrentResidentLiveLease? = null
    override fun rendererLiveInputSink(): RendererLiveInputSink? = liveInputBuffer
    private var pendingLiveOrigin: CameraDiagnosticOrigin? = null // cameraLock
    private var appliedLiveOrigin: CameraDiagnosticOrigin? = null // Chart owner
    private var appliedLiveOriginRevision = -1L
    private val appliedLiveInputId: Long get() = if(appliedLiveOriginRevision==appliedCameraRevision)
        diagnosticInputId(appliedLiveOrigin) else 0L
    private fun diagnosticOrigin(state:ChartEngineStatus=mutable.value):CameraDiagnosticOrigin? =
        CameraDiagnosticOrigin.capture(liveInputBuffer,state.chartSourceGeneration,state.surfaceGeneration)
    private fun diagnosticInputId(origin:CameraDiagnosticOrigin?):Long =
        origin?.takeIf { it.matches(liveInputBuffer,residentOwner.sourceGeneration,surfaceRevision) }?.inputId ?: 0L
    private fun observeCamera(origin:CameraDiagnosticOrigin?,revision:Long,generation:Long,view:ChartViewport,nativeReturn:Boolean) {
        val sink=liveInputBuffer?:return
        if(origin!=null&&!origin.matches(sink,residentOwner.sourceGeneration,surfaceRevision))return
        sink.camera(origin?.inputId?:0L,revision,generation,view,nativeReturn)
    }
    private fun observeCameraDisposition(origin:CameraDiagnosticOrigin?,reason:String,replacement:CameraDiagnosticOrigin?=null) {
        val sink=liveInputBuffer?:return
        if(origin!=null&&!origin.matches(sink,residentOwner.sourceGeneration,surfaceRevision))return
        sink.disposition(origin?.inputId?:0L,reason,diagnosticInputId(replacement))
    }
    private fun retireCameraOrigins(capture:RendererLiveInputBuffer) = synchronized(cameraLock) {
        if(pendingLiveOrigin?.capture===capture)pendingLiveOrigin=null
        if(appliedLiveOrigin?.capture===capture){appliedLiveOrigin=null;appliedLiveOriginRevision=-1}
        cameraControl.retireInputOrigins(capture)
    }
    private fun liveCameraAllowed(state: ChartEngineStatus): Boolean {
        if (currentResidentLiveLease != null && !currentResidentLiveCameraAllowed()) return false
        return residentLiveCameraAllowed(state, benchmarkRuns.activeRun()?.config?.runKind,
            capacityResidentWorkAllowed(), currentResidentLiveCameraAllowed())
    }
    private fun normalCameraAllowed(state: ChartEngineStatus) = residentNormalCameraAllowed(state,
        benchmarkRuns.activeRun() != null, capacityResidentWorkAllowed(), currentResidentLiveCameraAllowed())
    private var captureMetadata = buildJsonObject {}
    private var lastCaptureReport = ""
    private var reportStore: RendererReportStore? = null
    private val reportIoDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var lastReportWrite: Job? = null
    private val pendingProfileReports = mutableListOf<String>()
    private fun retainReport(report: String) {
        lastCaptureReport = report
        if (profiling || benchmarkRuns.activeRun() != null) { pendingProfileReports += report; return }
        persistReport(report)
    }
    private fun persistReport(report: String) {
        val store = reportStore ?: return
        lastReportWrite = scope.launch(reportIoDispatcher) {
            try { store.save(report) }
            catch (failure: Exception) { Log.e("No5Chart", "Completed renderer report remains in memory; file save failed", failure) }
        }
    }
    private var captureTimeout: Job? = null
    private var activeFrameMetrics: MutableMap<String, Double>? = null
    private val renderDirty=AtomicBoolean()
    private val queuedRenderAt = AtomicLong(0)
    private val latestInputAt = AtomicLong(0)
    private var previousFrameEnd = 0L
    private var lastCapturedInputNanos = 0L
    private var appliedInputAt = 0L
    private fun cpuNanos() = android.os.Debug.threadCpuTimeNanos()
    private fun queueRender() { renderDirty.set(true);queuedRenderAt.compareAndSet(0, System.nanoTime()); render.trySend(Unit) }
    private inline fun <T> measured(name: String, block: () -> T): T {
        val capture = captureBuffer ?: return block()
        val wall = System.nanoTime(); val cpu = cpuNanos()
        try { return block() } finally {
            val wallMs = (System.nanoTime() - wall) / 1e6; val cpuMs = (cpuNanos() - cpu) / 1e6
            activeFrameMetrics?.let { it["${name}WallMs"] = wallMs; it["${name}CpuMs"] = cpuMs }
                ?: capture.event(name, wallMs, cpuMs)
        }
    }
    private fun renderAndRefresh(queuedAt: Long = 0L, applyCamera: Boolean = false) {
        if (surfaceCommandPending.get()) return
        if(touchFrames.held.get()||touchFrames.current()!=null)return
        // Do not drain the latest-camera inbox or overwrite either visible/pending image.
        if (pairedPresentation && bufferTransformMode != ChartBufferTransformMode.OFF && presentationGate.busy()) return
        renderDirty.set(false)
        val capture = captureBuffer
        if (capture == null) {
            if (applyCamera) applyPendingViewport()
            applyPendingOverlays()
            if(!renderNative())return
            refresh(true)
            return
        }
        val start = System.nanoTime(); val cpu = cpuNanos(); val values = linkedMapOf<String, Double>()
        activeFrameMetrics = values
        try {
            if (queuedAt > 0) values["queueWaitWallMs"] = (start - queuedAt).coerceAtLeast(0) / 1e6
            if (previousFrameEnd > 0) values["betweenWorkerFramesWallMs"] = (start - previousFrameEnd).coerceAtLeast(0) / 1e6
            if (!currentResidentLiveNativeValid()) return
            if (applyCamera) measured("cameraApply") { applyPendingViewport() }
            if (!currentResidentLiveNativeValid()) return
            measured("overlayApply") { applyPendingOverlays() }
            val input = appliedInputAt
            if (input > 0 && input != lastCapturedInputNanos) {
                values["engineInputToSubmitWallMs"] = (System.nanoTime() - input).coerceAtLeast(0) / 1e6
                lastCapturedInputNanos = input
            }
            if(!measured("jniRender") { renderNative() })return
            if (!currentResidentLiveNativeValid()) return
            refresh(true)
            values["workerFrameWallMs"] = (System.nanoTime() - start) / 1e6
            values["workerFrameCpuMs"] = (cpuNanos() - cpu) / 1e6
            values["outsideNativeWallMs"] = (values.getValue("workerFrameWallMs") - values.getValue("jniRenderWallMs")).coerceAtLeast(0.0)
            values["workerUnattributedWallMs"] = (values.getValue("workerFrameWallMs") - listOf("cameraApply", "overlayApply", "jniRender", "statusRead", "statusParse", "scaleRead", "diagnosticsRead", "statePublish").sumOf { values["${it}WallMs"] ?: 0.0 }).coerceAtLeast(0.0)
            val probe = System.nanoTime(); val probeCpu = cpuNanos()
            val native = Json.parseToJsonElement(NativeBridge.frameTimingRead(engine)).jsonObject
            liveInputBuffer?.nativeFrame(native, transformSubmittedStamp, appliedCameraRevision, appliedLiveInputId)
            val nativeActive = native["activeFrameWallMs"]?.jsonPrimitive?.doubleOrNull
            if (nativeActive != null) values["jniOutsideNativeFrameWallMs"] = (values.getValue("jniRenderWallMs") - nativeActive).coerceAtLeast(0.0)
            capture.record(values, native)
            capture.updateLast(mapOf("captureProbeWallMs" to (System.nanoTime() - probe) / 1e6, "captureProbeCpuMs" to (cpuNanos() - probeCpu) / 1e6))
            previousFrameEnd = System.nanoTime()
            mutable.update { it.copy(rendererCapture = RendererCaptureStatus(true, capture.frames, (previousFrameEnd - capture.startedNanos) / 1e6)) }
            if (capture.full || capture.expired(previousFrameEnd)) finishCapture(if (capture.full) "frame limit" else "timeout")
        } catch (failure: Exception) { finishCapture("render failed"); throw failure }
        finally { activeFrameMetrics = null }
    }
    private fun finishCapture(reason: String) {
        val capture = captureBuffer ?: return
        activeUiCapture = null
        captureBuffer = null; captureTimeout?.cancel(); captureTimeout = null
        if (engine != 0L) NativeBridge.captureModeSet(engine, 0)
        val elapsed = (System.nanoTime() - capture.startedNanos) / 1e6
        val metadata=withTouchRenderingEndMetadata(captureMetadata,mutable.value)
        retainReport(capture.report(metadata, reason, elapsed).toString())
        mutable.update { it.copy(rendererCapture = RendererCaptureStatus(false, capture.frames, elapsed, reason)) }
    }
    private fun captureMetadata(scriptedNativeFullRender: Boolean = false): JsonObject = buildJsonObject {
        put("appVersion", appVersion)
        put("sourceCommit", sourceCommit)
        put("touchRendering",touchRenderingMetadata(mutable.value,scriptedNativeFullRender))
        put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        put("androidApi", android.os.Build.VERSION.SDK_INT)
        put("androidRelease", android.os.Build.VERSION.RELEASE)
        put("densityDpi", densityDpi?.let { JsonPrimitive(it) } ?: JsonNull)
        put("supportedAbis", JsonArray(android.os.Build.SUPPORTED_ABIS.map { JsonPrimitive(it) }))
        put("gl", Json.parseToJsonElement(NativeBridge.rendererIdentityRead(engine)))
        put("featureCount", mutable.value.featureCount)
        put("presentation", buildJsonObject { put("style", mutable.value.presentation.styleId); put("symbols", mutable.value.presentation.symbols.id); put("relief", mutable.value.presentation.landRelief) })
        put("viewport", buildJsonObject { put("width", mutable.value.viewport.width); put("height", mutable.value.viewport.height); put("metresToPixels", mutable.value.viewport.metresToPixels); put("rotationRadians", mutable.value.viewport.rotationRadians) })
        put("coordinatesIncluded", false)
        put("bufferTransformMode", bufferTransformMode.name)
        put("bufferRaster", rasterMetadata(bufferTransformMode))
        put("bufferTransformDisplayEndpoint", "NOT_MEASURED")
    }
    private fun rasterMetadata(mode: ChartBufferTransformMode): JsonObject = buildJsonObject {
        val visible = surfaceGeometry
        val raster = producerRasterGeometry
        val reserve = mode == ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE && raster != null
        put("pairedPaddedAttachment", raster != null)
        put("arm", if (reserve) "RESERVE" else if (raster != null) "PADDED_CONTROL" else "LEGACY_VISIBLE")
        visible?.let {
            put("visibleSurface", buildJsonObject { put("width", it.surfaceWidth); put("height", it.surfaceHeight) })
            put("visibleCanonicalFrame", buildJsonObject { put("width", it.frameWidth); put("height", it.frameHeight) })
        }
        if (raster != null) {
            put("bufferQueueRaster", buildJsonObject { put("width", raster.bufferWidth); put("height", raster.bufferHeight) })
            put("producerFrame", buildJsonObject {
                put("width", if (reserve) raster.bufferWidth else raster.visible.frameWidth)
                put("height", if (reserve) raster.bufferHeight else raster.visible.frameHeight)
            })
            put("filledRectangle", buildJsonObject {
                put("left", if (reserve) 0.0 else raster.cropLeft); put("top", if (reserve) 0.0 else raster.cropTop)
                put("width", if (reserve) raster.bufferWidth else raster.visible.frameWidth)
                put("height", if (reserve) raster.bufferHeight else raster.visible.frameHeight)
            })
            put("requestedAxisFactor", 1.25); put("effectiveAxisFactor", raster.effectiveFactor)
            put("logicalRgbaBytesPerQueueBuffer", raster.queuePixelBytes)
            put("extraLogicalRgbaBytesPerQueueBuffer", raster.queuePixelBytes - raster.visible.frameWidth.toLong() * raster.visible.frameHeight * 4)
            put("queueAllocationCountAndTotalBytesMeasured", false)
            put("queueLifetime", "fixed padded queues for both arms and OFF; only actual visible resize recreates EGL surfaces")
            put("presentationMapping", "central 1:1 producer pixels; explicit TextureView crop followed by visible-camera affine; unchanged visible input/tap camera")
            put("coverage", if (reserve) "entire prepared producer raster" else "central visible producer rectangle only; padding excluded")
            put("fullCostComparableToUnpaddedBaseline", false)
        }
    }
    override suspend fun startRendererCapture(config: RendererCaptureConfig): EngineResult = withContext(dispatcher) {
        if (closed || engine == 0L || surfaceGeometry == null || profiling || captureBuffer != null || mutable.value.rendererBenchmark.active || mutable.value.rendererBenchmarkRecoveryPending)
            return@withContext EngineResult(false, "Attach the chart and finish any active measurement first")
        lastReportWrite?.join()
        if (closed || engine == 0L || surfaceGeometry == null || profiling || captureBuffer != null || mutable.value.rendererBenchmark.active || mutable.value.rendererBenchmarkRecoveryPending)
            return@withContext EngineResult(false, "Chart or capture state changed while saving the previous report")
        synchronized(cameraLock) {
            captureMetadata = captureMetadata(); previousFrameEnd = 0L; lastCapturedInputNanos = 0L; latestInputAt.set(0)
            captureBuffer = RendererCaptureBuffer(config, System.nanoTime())
            activeUiCapture = captureBuffer?.ui
            mutable.update { it.copy(rendererCapture = RendererCaptureStatus(active = true)) }
        }
        NativeBridge.captureModeSet(engine, if (config.detailed) 1 else 0)
        captureTimeout = scope.launch { delay(config.durationSeconds * 1000L); finishCapture("timeout") }
        queueRender(); EngineResult(true)
    }
    override suspend fun stopRendererCapture(): EngineResult = withContext(dispatcher) {
        if (mutable.value.rendererBenchmark.active) return@withContext EngineResult(false, "Use benchmark cancellation; the run owns this capture")
        finishCapture("user stop")
        completedReport()
    }
    private suspend fun completedReport(): EngineResult {
        lastReportWrite?.join()
        if (lastCaptureReport.isNotEmpty()) return EngineResult(true, lastCaptureReport)
        val saved = withContext(reportIoDispatcher) { reportStore?.latest() }
        return if (saved == null) EngineResult(false, "Keine abgeschlossene Aufnahme vorhanden") else EngineResult(true, saved)
    }
    override suspend fun readRendererReport(): EngineResult = withContext(dispatcher) {
        if (mutable.value.rendererBenchmark.active || mutable.value.rendererBenchmarkRecoveryPending)
            return@withContext EngineResult(false, "Stilvergleich oder Wiederherstellung läuft noch. Erst danach exportieren.")
        try { completedReport() }
        catch (failure: Exception) { EngineResult(false, failure.message ?: "Gespeicherte Aufnahme nicht lesbar") }
    }
    override suspend fun readRendererDiagnostics(): EngineResult = withContext(dispatcher) {
        try {
            if (closed || engine == 0L) EngineResult(false, "Renderer nicht initialisiert")
            else {
                refresh(includeDiagnostics = true)
                EngineResult(true, mutable.value.rendererDiagnostics)
            }
        } catch (failure: Exception) { EngineResult(false, failure.message ?: "Rendererdiagnose nicht lesbar") }
    }
    private val licensedProvider = OChartsPreparationProvider()
    private val sourceLoader = ChartSourceLoader(licensedProvider,
        loadPiCache = { id, paths -> loadLibraryCharts(id, paths) },
        loadComposite = { id, source -> loadLibraryCharts(id, source.paths, source.backgroundPaths) },
        loadPublic = { id, paths -> loadLibraryCharts(id, paths) })
    private fun loadLibraryCharts(id: String, paths: List<String>, backgrounds: List<String> = emptyList()): EngineResult {
        val previousId = loadedChartId
        val previousPaths = loadedChartPaths
        val previousBackgrounds = loadedBackgroundPaths
        val previousView = mutable.value.viewport
        val success = measured("encOpenTotal") { openNativeCharts(paths.toTypedArray(), backgrounds) }
        val failure = if (!success) Json.parseToJsonElement(NativeBridge.statusRead(engine)).jsonObject.getValue("message").jsonPrimitive.content else ""
        if (success) { loadedChartId = id; loadedChartPaths = paths.toList(); loadedBackgroundPaths = backgrounds.toList() }
        else if (previousPaths.isNotEmpty()) {
            check(openNativeCharts(previousPaths.toTypedArray(), previousBackgrounds)) { "Chart activation and previous-set restoration failed" }
            NativeBridge.viewportSet(engine, previousView.latitude, previousView.longitude, previousView.metresToPixels,
                previousView.rotationRadians, previousView.width, previousView.height)
            loadedChartId = previousId
        } else { loadedChartId = null; loadedChartPaths = emptyList(); loadedBackgroundPaths = emptyList() }
        refresh()
        queueRender()
        return EngineResult(success, if (success) mutable.value.message else failure)
    }
    @Volatile private var surfaceGeometry: ChartSurfaceGeometry? = null // Immutable geometry published by the worker.
    @Volatile private var producerRasterGeometry: ChartRasterGeometry? = null // Fixed attachment, shared by both comparison arms.
    private var surfaceRevision = 0L
    @Volatile private var closed = false
    init { scope.launch { for (ignored in render) {
        val queuedAt = queuedRenderAt.getAndSet(0)
        guarded { if (engine != 0L && !profiling && !touchFrames.held.get()) { if (surfaceGeometry != null) renderAndRefresh(queuedAt, applyCamera = true) else { applyPendingViewport(); refresh() } } }
        if (!closed && mutable.value.presentationPending && (!residentOwner.active || mutable.value.residentCameraReady)) { delay(20); queueRender() }
    } } }

    private fun applyPendingViewport() = synchronized(cameraLock) {
        if (surfaceCommandPending.get()) return@synchronized
        if (residentOwner.active && !mutable.value.residentCameraReady) return@synchronized
        // The outer owner lock spans this drain, its exact authority and native apply/read.
        // A later camera-worker target cannot be substituted for this drained viewport.
        appliedInputAt = latestInputAt.get()
        val requestedOrigin = pendingLiveOrigin
        pendingLiveOrigin = null
        val requested = pendingViewport.getAndSet(null)
        val motions = pendingMotions.toList().also { pendingMotions.clear() }
        val revision = requestedCameraRevision
        if(requested!=null || motions.isNotEmpty() || appliedLiveOriginRevision!=revision) {
            appliedLiveOrigin=null;appliedLiveOriginRevision=-1
        }
        val renderAuthority = announceNormalRenderAuthority()
        activeFrameMetrics?.put("cameraSamplesCoalesced", motions.size.toDouble() + if (requested != null) 1 else 0)
        requested?.let {
            val view = surfaceGeometry?.let { requested.copy(width = it.frameWidth, height = it.frameHeight) } ?: requested
            cameraControl.beginNative(revision,view)
            val nativeOrigin=cameraControl.nativeInputOrigin(revision)
            val origin=if(nativeOrigin===requestedOrigin)nativeOrigin else null
            val actual=try {
                // Complete the actual native operation here, even for planning-only reads or when
                // rendering later has no writable image slot. A displayed frame is a separate fact.
                val actual=nativeCameraApplication.applyAndRead(engine,view)
                cameraControl.nativeApplied(revision,actual)
                actual
            } catch(failure:Exception) {
                cameraControl.nativeFailed(revision)
                throw failure
            }
            if (revision == requestedCameraRevision) mutable.value = mutable.value.copy(viewport = view)
            appliedLiveOrigin=origin;appliedLiveOriginRevision=revision
            observeCamera(origin, revision, bufferRegistry.generation(), actual, true)
        }
        val geometry = surfaceGeometry
        if (geometry != null) motions.forEach { motion ->
            val anchor = geometry.toFrame(motion.anchor)
            NativeBridge.cameraTransform(engine, anchor.x, anchor.y, motion.pan.x / geometry.scale,
                motion.pan.y / geometry.scale, motion.zoom, motion.rotation)
        }
        appliedCameraRevision = revision
        markNormalRenderApplied(renderAuthority)
    }
    private fun markCameraPending() {
        synchronized(cameraLock) { requestedCameraRevision++; announceNormalRenderAuthority() }
        mutable.update { it.copy(confirmedFrame = null) }
    }
    private fun frameMatches(frame: ChartFrame): Boolean = !closed && surfaceGeometry != null &&
        (!residentOwner.active || mutable.value.residentCameraReady) &&
        residentOwner.frameMatches(frame) && mutable.value.confirmedFrame == frame && presentedCameraRevision == requestedCameraRevision
    private fun rendererDiagnostics(): String = NativeBridge.rendererDiagnostics(engine) + "\n\n" + licensedProvider.status.value.diagnostics() + "\n\n" + surfaceOwnerDiagnostics()
    private fun refresh(presented: Boolean = false, includeDiagnostics: Boolean = false) {
        val readCameraRevision=appliedCameraRevision
        val raw = measured("statusRead") { NativeBridge.statusRead(engine) }
        val value = measured("statusParse") { Json.parseToJsonElement(raw).jsonObject }
        val dataGeneration = value["dataGeneration"]?.jsonPrimitive?.long
        val resident = if (residentOwner.active) residencyStatus().also { changedResidency(it) } else null
        if (!residentOwner.active && dataGeneration != null && mutable.value.navigationBounds?.generation != dataGeneration) {
            val bounds = Json.parseToJsonElement(NativeBridge.navigationBoundsRead(engine)).jsonObject
            val cells = bounds.getValue("cells").jsonArray.map { entry ->
                val cell = entry.jsonObject
                ChartCellExtent(cell.getValue("cellId").jsonPrimitive.content, cell.getValue("south").jsonPrimitive.double,
                    cell.getValue("north").jsonPrimitive.double, cell.getValue("west").jsonPrimitive.double, cell.getValue("east").jsonPrimitive.double)
            }
            mutable.update { it.copy(navigationBounds = ChartNavigationBounds(bounds.getValue("generation").jsonPrimitive.long, cells)) }
        }
        val presentation = value["presentation"]?.jsonObject
        val view = mutable.value.viewport
        val actualView = view.copy(latitude = value["latitude"]?.jsonPrimitive?.double ?: view.latitude,
            longitude = value["longitude"]?.jsonPrimitive?.double ?: view.longitude,
            metresToPixels = value["ppm"]?.jsonPrimitive?.double ?: view.metresToPixels,
            rotationRadians = value["rotation"]?.jsonPrimitive?.double ?: view.rotationRadians,
            width = value["width"]?.jsonPrimitive?.int ?: view.width,
            height = value["height"]?.jsonPrimitive?.int ?: view.height)
        cameraControl.nativeApplied(readCameraRevision,actualView)
        val geometry = surfaceGeometry
        val pendingTouch=touchFrames.current()
        if (presented && bufferTransformMode == ChartBufferTransformMode.OFF && plainSubmittedStamp > 0) {
            bufferRegistry.lookup(plainSubmittedStamp)?.let { packet ->
                val completed=bufferRegistry.complete(packet.stamp, packet.frame.copy(viewport = actualView,
                    scale = geometry?.let { confirmedScale(it) }))
                if(tracedPlainStamp==packet.stamp) {
                    recordPlainSurfaceFrame(if(completed!=null)"COMPLETE" else "STALE_COMPLETE",packet.stamp)
                    tracedPlainStamp=0
                }
            }
        }
        val frame = if (presented && bufferTransformMode == ChartBufferTransformMode.OFF && geometry != null && appliedCameraRevision == requestedCameraRevision && !touchFrames.held.get()) {
            presentedCameraRevision = appliedCameraRevision
            ChartFrame(++frameRevision, "$rendererSession:$surfaceRevision:$appliedCameraRevision", geometry, actualView, measured("scaleRead") { confirmedScale(geometry) },
                residentOwner.sourceGeneration, residentOwner.contentGeneration)
        } else if (bufferTransformMode != ChartBufferTransformMode.OFF) {
            bufferRegistry.lookup(bufferRegistry.observedStamp())?.takeIf { it.producerCompleted && it.stage == ChartContentStage.FULL &&
                residentOwner.frameMatches(it.frame) && !transformTouchActive.get() && it.cameraRevision == requestedCameraRevision }?.frame
        } else mutable.value.confirmedFrame?.takeIf { !presented && geometry != null && presentedCameraRevision == requestedCameraRevision }
        if (presented && bufferTransformMode == ChartBufferTransformMode.OFF && frame != null) residentOwner.completed(frame, true)
        val driver = if (includeDiagnostics) measured("diagnosticsRead") { rendererDiagnostics() } else mutable.value.rendererDiagnostics
        measured("statePublish") { mutable.update { state -> state.copy(initialized = true, chartLoaded = value.getValue("loaded").jsonPrimitive.boolean,
            completePortrayal = value.getValue("completePortrayal").jsonPrimitive.boolean,
            objectSelection = decodeObjectHighlight(value["objectSelection"]),
            objectFilters = readObjectFilters(),
            featureCount = value.getValue("featureCount").jsonPrimitive.int, unsupportedFeatures = value.getValue("unsupportedFeatures").jsonPrimitive.int,
            viewport = if (residentOwner.active && !state.residentCameraReady) state.viewport else if(appliedCameraRevision!=requestedCameraRevision)state.viewport else actualView,
            confirmedFrame = if(state.touchPreview?.showing==true||touchFrames.held.get()||appliedCameraRevision!=requestedCameraRevision)state.confirmedFrame?.takeIf{state.touchPreview?.showing!=true&&presentedCameraRevision==requestedCameraRevision} else frame,
            loadedChartCells = resident?.let(residentOwner::loadedNames) ?: loadedChartPaths.map { File(it).nameWithoutExtension },
            chartSourceGeneration = residentOwner.sourceGeneration, chartContentGeneration = residentOwner.contentGeneration,
            residentCatalogActive = residentOwner.active, residentCheckpointRevision = residentOwner.checkpointRevision,
            residentCatalogGeneration = residentOwner.catalog?.generation,
            chartScaleDenominator = if (residentOwner.active && !state.residentCameraReady) null else value["chartScaleDenominator"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it > 0 },
            navigationBounds = if (residentOwner.active) residentOwner.navigationBounds else state.navigationBounds,
            rendererBenchmarkDataAvailable = benchmarkDataAvailable,
            message = value.getValue("message").jsonPrimitive.content,
            presentation = presentation?.let { p -> ChartPresentation(p.getValue("style").jsonPrimitive.content,
                ChartSymbolSet.entries.first { it.id == p.getValue("symbols").jsonPrimitive.content }, p.getValue("relief").jsonPrimitive.boolean) } ?: mutable.value.presentation,
            presentationGeneration = presentation?.get("generation")?.jsonPrimitive?.long ?: 0,
            presentationAvailable = state.presentationAvailable || presented, surfaceAttached = surfaceGeometry != null,
            presentationPending = presentation?.get("landPending")?.jsonPrimitive?.boolean ?: false,
            rendererDiagnostics = driver) } }
        if(presented&&pendingTouch!=null){
            touchFrames.swapped(pendingTouch,frame?.takeIf{!mutable.value.presentationPending})
            bufferConsumed(consumedStamp.get())
            scope.launch { delay(2000); abandonUnconfirmedOutline(pendingTouch) }
        }
        if (presented && bufferTransformMode != ChartBufferTransformMode.OFF) {
            bufferRegistry.lookup(transformBaseStageStamp)?.let { base ->
                bufferRegistry.complete(base.stamp, base.frame)
                bufferRegistry.lookup(base.stamp)?.let { liveInputBuffer?.buffer(it, true) }
            }
            bufferRegistry.lookup(transformSubmittedStamp)?.let { buffer ->
                val complete = buffer.frame.copy(viewport = actualView, scale = geometry?.let { confirmedScale(it) })
                val completed = bufferRegistry.complete(buffer.stamp, complete)
                completed?.let(residentOwner::completed)
                mutable.update { it.copy(residentCheckpointRevision = residentOwner.checkpointRevision) }
                if (completed != null && buffer.generation == bufferRegistry.generation()) contentProgress.completed(buffer.stage == ChartContentStage.FULL, System.nanoTime())
                bufferRegistry.lookup(buffer.stamp)?.let { liveInputBuffer?.buffer(it, true) }
                bufferConsumed(bufferRegistry.observedStamp())
            }
        }
        capacityProducedFull()
    }
    private fun confirmedScale(geometry: ChartSurfaceGeometry): ChartScale? {
        val width = minOf(120.0, geometry.surfaceWidth / 3.0)
        val p = geometry.toFrame(ScreenPoint(geometry.surfaceWidth / 2.0, geometry.surfaceHeight / 2.0))
        val distance = NativeBridge.distancePixels(engine, p.x, p.y, p.x + width / geometry.scale, p.y)
        if (!distance.isFinite() || distance <= 0) return null
        val power = 10.0.pow(floor(log10(distance)))
        val nice = listOf(5.0, 2.0, 1.0).first { it * power <= distance } * power
        return ChartScale(width * nice / distance, nice)
    }
    private var lastLoggedFailure: String? = null
    private fun guarded(block: () -> Unit) {
        try { block() } catch (e: Exception) {
            interruptTouch()
            val message = e.message ?: "Native failure"
            val driver = if (engine != 0L) runCatching { NativeBridge.rendererDiagnostics(engine) }.getOrDefault("Renderer diagnostics unavailable") else "Engine unavailable"
            val diagnostics = "RENDER ERROR: $message\n$driver\nNo CPU fallback. Previous frame may remain visible."
            if (lastLoggedFailure != message) { Log.e("No5Chart", diagnostics, e); lastLoggedFailure = message }
            mutable.value = mutable.value.copy(confirmedFrame = null, message = message, rendererDiagnostics = diagnostics)
        }
    }
    private fun submit(block: () -> Unit) { if (!closed) scope.launch { guarded { if (engine != 0L) { block(); queueRender() } } } }
    override suspend fun initialize(config: ChartEngineConfig): EngineResult = withContext(dispatcher) {
        try { check(!closed && engine == 0L); dataDirectory = config.dataDirectory; appVersion = config.appVersion; densityDpi = config.densityDpi; sourceCommit = config.sourceCommit
            mutable.update { it.copy(touchOutlinesEnabled=config.touchOutlinesEnabled) }
            reportStore = RendererReportStore(File(File(dataDirectory).parentFile, "renderer-captures"))
            benchmarkDataAvailable = bundledBenchmarkPaths(dataDirectory).all { File(it).isFile }
            benchmarkRecoveryStore = BenchmarkRecoveryStore(File(File(dataDirectory).parentFile, "renderer-benchmark-recovery.json"))
            try { pendingBenchmarkRecovery = withContext(Dispatchers.IO) { benchmarkRecoveryStore?.read() } }
            catch (failure: Exception) { benchmarkRecoveryError = failure.message }
            if (pendingBenchmarkRecovery != null || benchmarkRecoveryError != null) mutable.update { it.copy(
                rendererBenchmarkRecoveryPending = true, rendererBenchmark = RendererBenchmarkStatus(
                    runId = pendingBenchmarkRecovery?.runId.orEmpty(), phase = "recovery", message = benchmarkRecoveryError ?: "Unterbrochene Messung; ursprüngliche Ansicht wird wiederhergestellt")) }
            engine = NativeBridge.engineCreate(dataDirectory)
            if(WorldUnderlayQualification.initiallyEnabled){
                NativeBridge.worldUnderlaySet(engine,true)
                mutable.update{it.copy(worldUnderlayCandidate=true)}
            }
            refresh(includeDiagnostics = true); EngineResult(true) }
        catch (e: Exception) { EngineResult(false, e.message ?: "Initialize failed") }
    }
    @Volatile private var capacitySoftwareEvents: ResidentCapacitySoftwareEvents? = null
    private val capacityCancelRequestedNs = AtomicLong(0)
    private var capacityRecipeJob: Job? = null
    private var capacityFullStamp = 0L // Producer completion deduplication, worker only.
    private fun capacityAuthority(actualCameraRevision: Long? = null, actualViewport: ChartViewport? = null,
        capturedState: ChartEngineStatus? = null, capturedRequestedRevision: Long? = null): JsonObject = synchronized(cameraLock) { buildJsonObject {
        val state = capturedState ?: mutable.value; val catalog = residentOwner.catalog
        val requestedRevision = capturedRequestedRevision ?: requestedCameraRevision
        put("catalogGeneration", catalog?.generation ?: "")
        put("sourceGeneration", residentOwner.sourceGeneration); put("contentGeneration", residentOwner.contentGeneration)
        put("surfaceGeneration", surfaceRevision); put("cameraRevision", actualCameraRevision ?: requestedRevision)
        put("requestedCameraRevision", requestedRevision)
        catalog?.budget?.let { budget -> put("budget", buildJsonObject {
            put("cells", budget.cells); put("bytes", budget.bytes); put("loadingBytes", budget.loadingBytes)
        }) }
        put("viewport", BostonBenchmarkRecipe.cameraJson(actualViewport ?: state.viewport))
        put("viewportAuthority", if (actualViewport != null) "REGISTERED_BUFFER_FRAME" else "REQUESTED_STATE_NOT_NATIVE_POSE_PROOF")
        put("bufferTransformMode", bufferTransformMode.name)
        put("presentationGeneration", state.presentationGeneration)
        put("presentation", buildJsonObject { put("style", state.presentation.styleId); put("symbols", state.presentation.symbols.id); put("landRelief", state.presentation.landRelief) })
        surfaceGeometry?.let { geometry -> put("visible", buildJsonObject { put("width", geometry.frameWidth); put("height", geometry.frameHeight) }) }
        producerRasterGeometry?.let { raster -> put("reserve", buildJsonObject { put("width", raster.bufferWidth); put("height", raster.bufferHeight) }) }
    } }
    private fun capacityProducedFull() {
        val events = capacitySoftwareEvents ?: return
        val stamp = if (bufferTransformMode == ChartBufferTransformMode.OFF) plainSubmittedStamp else transformSubmittedStamp
        if (stamp <= capacityFullStamp) return
        val buffer = bufferRegistry.lookup(stamp) ?: return
        if (!buffer.producerCompleted || buffer.stage != ChartContentStage.FULL || !residentOwner.frameMatches(buffer.frame)) return
        capacityFullStamp = stamp
        events.recordInstant("FULL_PRODUCED", System.nanoTime(), capacityAuthority(buffer.cameraRevision, buffer.frame.viewport), buildJsonObject {
            put("bufferStamp", stamp); put("bufferCameraRevision", buffer.cameraRevision)
            put("endpoint", "registered FULL producer completion; no physical display claim")
        })
    }
    @Volatile private var capacitySamplingRunId: String? = null // Worker-owned allowance, absent in prepare/recovery.
    private var currentViewReuseSampling = false // Same resident lease; no public global toggle.
    private var currentViewReuseFrameCount = 0
    private fun capacityResidentWorkAllowed(): Boolean {
        val run = benchmarkRuns.activeRun() ?: return false
        return residentCapacityLeaseMatches(mutable.value, run.id, capacitySamplingRunId, run.config.runKind,
            run.config.capacityArm, residentOwner.sourceGeneration, residentOwner.catalog, closed, engine != 0L,
            profiling, benchmarkRuns.cancelled(run.id) || android.os.SystemClock.elapsedRealtime() >= run.deadlineMs)
    }
    private fun residencyGuard(allowCapacityWork: Boolean = false) {
        if (allowCapacityWork && capacityResidentWorkAllowed() && !closed && engine != 0L &&
            !mutable.value.rendererCapture.active) return
        if (allowCapacityWork && currentResidentLiveWorkAllowed()) return
        residentOperationBlocker(mutable.value, closed, engine != 0L, profiling,
            benchmarkRuns.activeRun() != null)?.let { error(it) }
    }

    /** Owner-only JNI/JSON, never called by camera/Ring admission. */
    private fun readCurrentResidentLiveIdentity(native: JsonObject? = null,
        resident: ChartResidencyStatus = residencyStatus()): CurrentResidentLiveIdentity {
        val raw = native ?: Json.parseToJsonElement(NativeBridge.statusRead(engine)).jsonObject
        val catalog = checkNotNull(residentOwner.catalog)
        check(resident.enabled && resident.catalogGeneration == catalog.generation && resident.budget == catalog.budget) {
            "Current native catalog/budget authority unavailable"
        }
        return CurrentResidentLiveIdentity(rendererSession, catalog,
            residentOwner.sourceGeneration, surfaceRevision, residentOwner.contentGeneration,
            raw.getValue("dataGeneration").jsonPrimitive.long, resident.contentRevision,
            raw.getValue("presentation").jsonObject.getValue("generation").jsonPrimitive.long,
            resident.policyKey, mutable.value.presentation, benchmarkUserSettings(activeSettings, raw), activeSettings, activeOverlays)
    }

    /** Short memory checks compose only this exact run/capture with ordinary resident work. */
    private fun currentResidentLiveWorkAllowed(): Boolean {
        val lease = currentResidentLiveLease ?: return false
        val frozen = lease.snapshot.identity
        val memoryMatches = rendererSession == frozen.rendererSession && residentOwner.catalog == frozen.catalog &&
            residentOwner.sourceGeneration == frozen.source && surfaceRevision == frozen.surface &&
            residentOwner.contentGeneration == frozen.content && mutable.value.presentation == frozen.presentation &&
            activeSettings == frozen.requestedSettings && activeOverlays == frozen.overlays
        // Also used before the short camera inbox acceptance. Read only memory/clock
        // here; the existing watchdog owns native cancellation and deadline effects.
        val active = benchmarkRuns.activeRun()
        val expiredOrCancelled = benchmarkRuns.cancelled(lease.runId) ||
            active == null || android.os.SystemClock.elapsedRealtime() >= active.deadlineMs
        return lease.allows(mutable.value, active?.id, captureBuffer, memoryMatches,
            closed || engine == 0L, profiling, expiredOrCancelled)
    }
    private fun currentResidentLiveCameraAllowed(): Boolean = currentResidentLiveWorkAllowed() &&
        currentResidentLiveLease?.cameraAllowed(mutable.value) == true

    private fun currentResidentLiveNativeValid(native: JsonObject? = null,
        resident: ChartResidencyStatus? = null): Boolean {
        val lease = currentResidentLiveLease ?: return true
        if (lease.phase != "warming" && lease.phase != "live_input") return false
        val same = runCatching { lease.matches(readCurrentResidentLiveIdentity(native, resident ?: residencyStatus())) }.getOrDefault(false)
        if (!same) {
            lease.invalidate(); signalBenchmarkCancel(lease.runId, "SOURCE_FREEZE_LOST"); return false
        }
        return !benchmarkRunCancelled(lease.runId)
    }

    private fun currentResidentSnapshotCurrent(snapshot: CurrentResidentLiveSnapshot): Boolean =
        snapshot.identity == readCurrentResidentLiveIdentity() && surfaceGeometry == snapshot.geometry &&
            bufferTransformMode == ChartBufferTransformMode.OFF && requestedCameraRevision == snapshot.cameraRevision &&
            appliedCameraRevision == snapshot.cameraRevision && mutable.value.viewport == snapshot.saved.viewport &&
            pendingViewport.get() == null && synchronized(cameraLock) { pendingMotions.isEmpty() } &&
            plainSubmittedStamp == snapshot.full.stamp && bufferRegistry.lookup(snapshot.full.stamp) == snapshot.full &&
            synchronized(cameraLock) { cameraControl.idleForMeasurement() } &&
            !surfaceCommandPending.get() && mutable.value.chartLoaded && mutable.value.surfaceAttached &&
            !mutable.value.rendererCapture.active && !mutable.value.rendererBenchmarkRecoveryPending &&
            !mutable.value.sourcePresentationUnsupported && mutable.value.residentCameraReady && !mutable.value.presentationPending

    private fun sourceCameraGuardChanged() {
        residentCameraGuard = null; residentCameraAdmission = null
        pendingResidentStart = null; residentCameraPolicy = null; preserveRestoredCamera = false
        mutable.update { it.copy(residentCatalogGeneration = residentOwner.catalog?.generation,
            chartSourceGeneration = residentOwner.sourceGeneration, residentCatalogActive = residentOwner.active,
            residentCameraDecision = null, cameraBoundsDecision = null, residentCameraReady = !residentOwner.active,
            residentStartState = if (residentOwner.active) ChartResidentStartState.UNKNOWN else ChartResidentStartState.NOT_REQUIRED) }
        announceNormalRenderAuthority()
    }

    override fun installResidentCameraGuard(expectedCatalogGeneration: String, guard: ChartResidentCameraGuard?): Boolean =
        synchronized(cameraLock) {
            val catalog = residentOwner.catalog ?: return@synchronized false
            val source = residentOwner.sourceGeneration
            if (closed || catalog.generation != expectedCatalogGeneration ||
                mutable.value.chartSourceGeneration != source ||
                mutable.value.residentCatalogGeneration != catalog.generation) return@synchronized false
            residentCameraGuard = guard?.let { CameraGuardBinding(catalog.generation, source, it) }
            residentCameraAdmission = null
            mutable.update { it.copy(residentCameraDecision = null) }
            val installed = source == residentOwner.sourceGeneration && catalog.generation == residentOwner.catalog?.generation
            if (installed && guard != null && !mutable.value.residentCameraReady) submit { revalidateResidentStart() }
            installed
        }

    override fun evaluateResidentCamera(proposal: ChartResidentCameraProposal): ChartResidentCameraDecision? = synchronized(cameraLock) {
        val state = mutable.value
        val catalog = residentOwner.catalog ?: return@synchronized null
        val source = residentOwner.sourceGeneration
        val surface = state.surfaceGeneration
        val mode = bufferTransformMode
        fun rejected(reason: ChartResidentCameraReason) = ChartResidentCameraDecision(null, reason, null,
            catalog.budget.cells, catalog.generation, source, surface)
        fun authorityMatches() = !closed && !surfaceCommandPending.get() &&
            residentOwner.sourceGeneration == source && residentOwner.catalog == catalog &&
            mutable.value.surfaceGeneration == surface && bufferTransformMode == mode && mutable.value.chartSourceGeneration == source &&
            mutable.value.residentCatalogGeneration == catalog.generation &&
            proposal.expectedCatalogGeneration == catalog.generation &&
            proposal.expectedSourceGeneration == source && proposal.expectedSurfaceGeneration == surface
        val geometry = surfaceGeometry
        val raster = producerRasterGeometry.takeIf { mode == ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE }
        val guardedProposal = proposal.copy(planningWidth = raster?.bufferWidth ?: geometry?.frameWidth ?: 0,
            planningHeight = raster?.bufferHeight ?: geometry?.frameHeight ?: 0)
        var decision = if (!authorityMatches()) rejected(ChartResidentCameraReason.STALE_AUTHORITY)
        else if (geometry == null || !residentCameraValid(proposal.basis, geometry) ||
            !residentCameraValid(proposal.wanted, geometry) ||
            proposal.noZoomAlternative?.let { !residentCameraValid(it, geometry) } == true)
            rejected(ChartResidentCameraReason.UNKNOWN)
        else {
            if (catalog.sources.isEmpty()) ChartResidentCameraDecision(proposal.wanted,
                ChartResidentCameraReason.ACCEPTED, 0, catalog.budget.cells, catalog.generation, source, surface)
            else {
                val installed = residentCameraGuard
                if (installed == null || installed.catalogGeneration != catalog.generation || installed.sourceGeneration != source)
                    rejected(ChartResidentCameraReason.UNKNOWN)
                else runCatching { installed.guard.evaluate(guardedProposal, catalog.budget) }
                    .getOrElse { rejected(ChartResidentCameraReason.UNKNOWN) }
            }
        }
        if (!authorityMatches()) decision = rejected(ChartResidentCameraReason.STALE_AUTHORITY)
        else if (!residentCameraDecisionValid(guardedProposal, catalog.budget, decision))
            decision = rejected(ChartResidentCameraReason.UNKNOWN)
        if (decision.acceptedCamera != null) residentCameraAdmission = geometry?.let { CameraAdmission(guardedProposal, decision, catalog, mode, it) }
        mutable.update { current ->
            if (current.chartSourceGeneration == source && current.surfaceGeneration == surface &&
                current.residentCatalogGeneration == catalog.generation) current.copy(residentCameraDecision = decision)
            else current
        }
        decision
    }

    private val navigationLimits = ChartCameraBoundsCache()
    private fun admitCamera(requested: ChartViewport, basis: ChartViewport = mutable.value.viewport,
        noZoomAlternative: ChartViewport? = null, pureZoomInRecovery: Boolean = false): ChartViewport? {
        val state = mutable.value
        val region = navigationLimits.get(state.navigationBounds)
        val boundsDecision = region?.constrain(requested, basis)
        val decision = boundsDecision ?: if (state.residentCatalogActive)
            ChartCameraBoundsDecision(requested, null, ChartCameraLimit.UNKNOWN_BOUNDS, null) else null
        if (decision != null) mutable.update { it.copy(cameraBoundsDecision = decision) }
        val wanted = decision?.effective ?: requested.takeIf { !state.residentCatalogActive } ?: return null
        if (!state.residentCatalogActive) return wanted
        if (!state.residentCameraReady && surfaceGeometry == null) return null
        // Equality still needs proof after source/surface/reserve/budget changes.
        val previous = residentCameraAdmission
        val proposal = if (previous?.decision?.acceptedCamera == wanted && previous.proposal.basis == basis)
            previous.proposal else ChartResidentCameraProposal(basis, wanted,
                noZoomAlternative?.let { region?.constrain(it, basis)?.effective ?: it }, pureZoomInRecovery,
                state.residentCatalogGeneration ?: return null, state.chartSourceGeneration, state.surfaceGeneration)
        return evaluateResidentCamera(proposal)?.acceptedCamera
    }
    private fun residentStartRequest(desired: ChartViewport, previous: ChartViewport?, catalog: ChartResidentCatalog,
        source: Long, geometry: ChartSurfaceGeometry): ChartResidentCameraStart {
        val raster = producerRasterGeometry.takeIf { bufferTransformMode == ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE }
        return ChartResidentCameraStart(desired.copy(width = geometry.frameWidth, height = geometry.frameHeight),
            previous?.copy(width = geometry.frameWidth, height = geometry.frameHeight), catalog.generation, source,
            surfaceRevision, raster?.bufferWidth ?: geometry.frameWidth, raster?.bufferHeight ?: geometry.frameHeight)
    }
    private fun publishResidentStart(decision: ChartResidentCameraDecision) {
        mutable.update { it.copy(residentCameraAdmissionRevision = if (decision.acceptedCamera != null) it.residentCameraAdmissionRevision + 1 else it.residentCameraAdmissionRevision,
            residentCameraReady = decision.acceptedCamera != null,
            residentCameraDecision = decision, residentStartState = when (decision.reason) {
                ChartResidentCameraReason.ACCEPTED, ChartResidentCameraReason.NO_ZOOM_ALTERNATIVE -> ChartResidentStartState.READY
                ChartResidentCameraReason.BLOCKED, ChartResidentCameraReason.RECOVERY_ABOVE_BUDGET -> ChartResidentStartState.BLOCKED
                ChartResidentCameraReason.STALE_AUTHORITY -> ChartResidentStartState.STALE_AUTHORITY
                else -> ChartResidentStartState.UNKNOWN
            }, chartScaleDenominator = if (decision.acceptedCamera == null) null else it.chartScaleDenominator) }
    }
    /** First real Surface, resize, reserve-mode and budget revalidation share one precommit gate. */
    private fun revalidateResidentStart(ownSurfaceCommand: Long? = null): Boolean {
        if (!residentOwner.active) return true
        val catalog = checkNotNull(residentOwner.catalog)
        val geometry = surfaceGeometry ?: run {
            mutable.update { it.copy(residentCameraReady = false, residentStartState = ChartResidentStartState.WAITING_SURFACE, chartScaleDenominator = null) }
            return false
        }
        val source = residentOwner.sourceGeneration; val surface = surfaceRevision; val mode = bufferTransformMode
        val command = surfaceCommandRevision.get(); val cameraRevision = requestedCameraRevision
        val binding = residentCameraGuard?.takeIf { it.catalogGeneration == catalog.generation && it.sourceGeneration == source }
        fun current() = !closed && residentOwner.sourceGeneration == source && residentOwner.catalog == catalog &&
            surfaceRevision == surface && surfaceGeometry == geometry && bufferTransformMode == mode &&
            surfaceCommandRevision.get() == command && requestedCameraRevision == cameraRevision &&
            residentCameraGuard === binding &&
            (!surfaceCommandPending.get() || ownSurfaceCommand == command)
        val intent = pendingResidentStart ?: PendingResidentStart(mutable.value.viewport, mutable.value.viewport)
        val request = residentStartRequest(intent.desired, intent.previous, catalog, source, geometry)
        val decision = synchronized(cameraLock) {
            if (!current()) return@synchronized null
            val inbox = pendingViewport.get()
            val admission = residentCameraAdmission?.takeIf { it.decision.acceptedCamera == inbox && inbox != null &&
                it.catalog == catalog && it.mode == mode && it.geometry == geometry &&
                it.proposal.expectedSourceGeneration == source && it.proposal.expectedSurfaceGeneration == surface }
            if (admission != null) {
                // An explicit user's newer admitted target supersedes a saved/start camera.
                // Its above-N inward recovery remains visibly marked and is never a safe-start proof.
                residentInboxAdmission(admission.proposal, catalog.budget, geometry, catalog.sources.isEmpty(),
                    binding?.guard, ::current)
            } else residentStartAdmission(request, catalog.budget, geometry, catalog.sources.isEmpty(), binding?.guard,
                select = !preserveRestoredCamera, stillCurrent = ::current)
        } ?: ChartResidentCameraDecision(null, ChartResidentCameraReason.STALE_AUTHORITY, null,
            catalog.budget.cells, catalog.generation, source, surface)
        return synchronized(cameraLock) {
            if (!current()) {
                publishResidentStart(decision.copy(acceptedCamera = null, reason = ChartResidentCameraReason.STALE_AUTHORITY)); return@synchronized false
            }
            val camera = decision.acceptedCamera
            if (camera == null) { publishResidentStart(decision); return@synchronized false }
            val alreadyRequested = pendingViewport.get() == camera
            NativeBridge.rasterReserveSet(engine, mode == ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE)
            NativeBridge.viewportSet(engine, camera.latitude, camera.longitude, camera.metresToPixels,
                camera.rotationRadians, camera.width, camera.height)
            pendingViewport.set(null); pendingMotions.clear()
            if (!alreadyRequested) requestedCameraRevision++
            appliedCameraRevision = requestedCameraRevision
            markNormalRenderApplied(announceNormalRenderAuthority())
            pendingResidentStart = null; preserveRestoredCamera = false
            mutable.update { it.copy(viewport = camera, confirmedFrame = null) }
            publishResidentStart(decision)
            true
        }
    }
    private var residentLastStep: ChartResidentStep? = null // Most recent actual loader result, cleared by new demand.
    private fun residencyStatus(): ChartResidencyStatus {
        val value = ChartResidencyDecoder.decode(NativeBridge.residencyRead(engine))
        return value.copy(step = if (value.enabled) residentLastStep else null)
    }
    private fun residencyViewport(): ChartViewport {
        val pose = NativeBridge.residentPlanningViewport(engine)
        check(pose.size == 6)
        return ChartViewport(pose[0], pose[1], pose[2], pose[3], pose[4].toInt(), pose[5].toInt())
    }
    override suspend fun readResidentPlanningViewport(): ChartViewport? = withContext(dispatcher) {
        try { residencyGuard(allowCapacityWork = true); applyPendingViewport(); residencyViewport() }
        catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
    }
    private fun catalogFields(catalog: ChartResidentCatalog) = catalog.sources.map { source ->
        arrayOf(source.provider, source.name, source.sourceKey, source.edition.toString(), source.update.toString(),
            source.order.toString(), *(if (source.provider == PI_CACHE_PROVIDER)
                listOf(checkNotNull(source.product), checkNotNull(source.licenseHost)) else emptyList()).toTypedArray(),
            *source.files.flatMap { listOf(it.path, it.sha256) }.toTypedArray())
    }.toTypedArray()
    private fun changedResidency(status: ChartResidencyStatus): Boolean {
        val changed = residentOwner.update(status)
        if (changed) {
            // Keep the current affine basis and contact owner. Geometry mutations only
            // revoke feature-query authority and require a fresh FULL checkpoint.
            progressResetPending.set(true)
            mutable.update { it.copy(confirmedFrame = null, chartContentGeneration = residentOwner.contentGeneration,
                residentCheckpointRevision = -1) }
            currentResidentLiveLease?.takeIf { it.phase in setOf("warming", "live_input") }?.let {
                it.invalidate(); signalBenchmarkCancel(it.runId, "SOURCE_FREEZE_LOST")
            }
        }
        return changed
    }
    override suspend fun activateResidentCatalog(activation: ChartResidentActivation): ChartResidentUpdate = withContext(dispatcher) {
        try {
            residencyGuard()
            val value = residentActivationSnapshot(activation)
            val geometry = surfaceGeometry
            val oldSource = residentOwner.sourceGeneration; val oldCatalog = residentOwner.catalog
            val surface = surfaceRevision; val mode = bufferTransformMode; val cameraRevision = requestedCameraRevision
            check(!surfaceCommandPending.get()) { "Anzeigewechsel noch nicht abgeschlossen" }
            val source = oldSource + 1 // This worker owns source transitions; verify actual assigned epoch after commit.
            val guard = value.cameraPolicy?.bind(source)
            val previous = mutable.value.viewport
            val decision = geometry?.let {
                val request = residentStartRequest(value.initialViewport, previous, value.catalog, source, it)
                residentStartAdmission(request, value.catalog.budget, it, value.catalog.sources.isEmpty(), guard) {
                    !closed && !surfaceCommandPending.get() && residentOwner.sourceGeneration == oldSource &&
                        residentOwner.catalog == oldCatalog && surfaceRevision == surface && surfaceGeometry == geometry && bufferTransformMode == mode &&
                        requestedCameraRevision == cameraRevision
                }.also { chosen -> check(chosen.acceptedCamera != null) { "Startausschnitt nicht zugelassen: ${chosen.reason}" } }
            }
            currentCoroutineContext().ensureActive()
            val resident = synchronized(cameraLock) {
                check(!closed && !surfaceCommandPending.get() && residentOwner.sourceGeneration == oldSource &&
                    residentOwner.catalog == oldCatalog && surfaceRevision == surface && surfaceGeometry == geometry &&
                    bufferTransformMode == mode && requestedCameraRevision == cameraRevision) { "Startautorität vor Commit verändert" }
            NativeBridge.residentCatalogActivate(engine, value.catalog.generation, catalogFields(value.catalog),
                value.catalog.budget.cells, value.catalog.budget.bytes, value.catalog.budget.loadingBytes)
            residentLastStep = null
            invalidateSourcePresentation()
            synchronized(cameraLock) { pendingViewport.set(null); pendingMotions.clear() }
            loadedChartId = value.id; loadedChartPaths = emptyList(); loadedBackgroundPaths = emptyList()
            val resident = residencyStatus()
            residentOwner.activate(value.catalog, value.navigationBounds, resident); sourceCameraGuardChanged()
            check(residentOwner.sourceGeneration == source) { "Quellautorität während Aktivierung verändert" }
            residentCameraPolicy = value.cameraPolicy
            residentCameraGuard = guard?.let { CameraGuardBinding(value.catalog.generation, source, it) }
            pendingResidentStart = PendingResidentStart(value.initialViewport, previous)
            mutable.update { it.copy(outlines = null, navigationBounds = value.navigationBounds,
                confirmedFrame = null, chartSourceGeneration = source, chartContentGeneration = residentOwner.contentGeneration,
                residentCatalogActive = true, residentCheckpointRevision = -1, chartScaleDenominator = null,
                residentCameraReady = false, residentStartState = ChartResidentStartState.WAITING_SURFACE) }
            if (decision != null) {
                val camera = checkNotNull(decision.acceptedCamera)
                NativeBridge.rasterReserveSet(engine, mode == ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE)
                NativeBridge.viewportSet(engine, camera.latitude, camera.longitude, camera.metresToPixels,
                    camera.rotationRadians, camera.width, camera.height)
                appliedCameraRevision = requestedCameraRevision; pendingResidentStart = null
                markNormalRenderApplied(announceNormalRenderAuthority())
                mutable.update { it.copy(viewport = camera) }; publishResidentStart(decision)
            }
            resident
            }
            refresh(); queueRender()
            ChartResidentUpdate(EngineResult(true), resident)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { ChartResidentUpdate(EngineResult(false, failure.message ?: "Resident activation rejected")) }
    }
    override suspend fun configureResidentCatalog(catalog: ChartResidentCatalog): ChartResidentUpdate = withContext(dispatcher) {
        try {
            residencyGuard()
            val frozen = residentActivationSnapshot(ChartResidentActivation("resident-config", catalog,
                mutable.value.viewport, ChartNavigationBounds(0, emptyList()))).catalog
            currentCoroutineContext().ensureActive()
            NativeBridge.residentCatalogSet(engine, frozen.generation, catalogFields(frozen), frozen.budget.cells,
                frozen.budget.bytes, frozen.budget.loadingBytes)
            residentLastStep = null
            invalidateSourcePresentation()
            val resident = residencyStatus()
            residentOwner.activate(frozen, null, resident); sourceCameraGuardChanged()
            pendingResidentStart = PendingResidentStart(mutable.value.viewport, mutable.value.viewport)
            loadedChartId = null; loadedChartPaths = emptyList(); loadedBackgroundPaths = emptyList()
            mutable.update { it.copy(outlines = null, navigationBounds = null, confirmedFrame = null) }
            refresh(); queueRender()
            ChartResidentUpdate(EngineResult(true), resident)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { ChartResidentUpdate(EngineResult(false, failure.message ?: "Resident catalog rejected")) }
    }
    override suspend fun submitResidentDemand(demand: ChartResidentDemand): ChartResidentUpdate = withContext(dispatcher) {
        try {
            residencyGuard(allowCapacityWork = true)
            check(mutable.value.residentCameraReady) { "Startausschnitt noch nicht geprüft" }
            applyPendingViewport(); refresh()
            check(residentPoseMatches(demand.camera, residencyViewport())) { "Ansichtsplan passt nicht zur aktuellen Renderer-Kamera" }
            val accepted = synchronized(cameraLock) {
                check(mutable.value.residentCameraReady && !surfaceCommandPending.get()) { "Ansichtszulassung vor Ladeplanung verändert" }
                NativeBridge.residentDemandSet(engine, demand.catalogGeneration, demand.cameraRevision,
                    demand.cameraKey, demand.policyKey, demand.required.toTypedArray(), demand.prefetch.toTypedArray())
            }
            if (accepted) residentLastStep = null
            ChartResidentUpdate(EngineResult(accepted, if (accepted) "" else "Veralteter oder ungültiger Ansichtsplan"), residencyStatus())
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { ChartResidentUpdate(EngineResult(false, failure.message ?: "Resident demand rejected")) }
    }
    override suspend fun setResidentPlanningPolicy(policyKey: String): ChartResidentUpdate = withContext(dispatcher) {
        try { residencyGuard(); NativeBridge.residentPolicySet(engine, policyKey); residentLastStep = null; ChartResidentUpdate(EngineResult(true), residencyStatus()) }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { ChartResidentUpdate(EngineResult(false, failure.message ?: "Resident policy rejected")) }
    }
    override suspend fun advanceResidentLoading(): ChartResidentUpdate = withContext(dispatcher) {
        try {
            residencyGuard(allowCapacityWork = true)
            check(currentResidentLiveNativeValid()) { "SOURCE_FREEZE_LOST" }
            // Linearize one useful load under the current admission. Long native
            // preparation runs outside the touch lock and cannot publish Ready.
            val authority = synchronized(cameraLock) {
                check(mutable.value.residentCameraReady && !surfaceCommandPending.get()) { "Startausschnitt noch nicht geprüft" }
                capacityAuthority(appliedCameraRevision)
            }
            val workStart = System.nanoTime(); val events = capacitySoftwareEvents
            val step = try { ChartResidentStep.entries[NativeBridge.residentAdvance(engine)] }
                catch (failure: Exception) {
                    events?.recordDuration("LOAD_STEP_FAILED", workStart, System.nanoTime(), authority)
                    throw failure
                }
            events?.recordDuration("LOAD_STEP", workStart, System.nanoTime(), authority,
                buildJsonObject { put("step", step.name); put("endpoint", "actual residentAdvance JNI call") })
            residentLastStep = step
            val status = residencyStatus()
            val changed = changedResidency(status)
            check(currentResidentLiveNativeValid(resident = status)) { "SOURCE_FREEZE_LOST" }
            refresh()
            // A failed/blocked load may already have evicted a cell. Its changed
            // geometry must also get a checkpoint; no busy-loop retry here.
            if (changed) queueRender()
            ChartResidentUpdate(EngineResult(step == ChartResidentStep.IDLE || step == ChartResidentStep.LOADED,
                status.message), status, step)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { ChartResidentUpdate(EngineResult(false, failure.message ?: "Resident load failed")) }
    }
    override suspend fun readResidency(): ChartResidentUpdate = withContext(dispatcher) {
        try { check(!closed && engine != 0L); val resident = residencyStatus(); if (residentOwner.active) changedResidency(resident); ChartResidentUpdate(EngineResult(true), resident) }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { ChartResidentUpdate(EngineResult(false, failure.message ?: "Resident status unavailable")) }
    }
    override suspend fun clearChartSelection(expectedSourceGeneration: Long): EngineResult = withContext(dispatcher) {
        try {
            residencyGuard()
            check(expectedSourceGeneration == residentOwner.sourceGeneration) { "Veraltete Kartenauswahl; Freigabe erneut prüfen" }
            currentCoroutineContext().ensureActive()
            NativeBridge.chartSelectionClear(engine)
            residentLastStep = null
            invalidateSourcePresentation(); residentOwner.eager(); sourceCameraGuardChanged()
            loadedChartId = null; loadedChartPaths = emptyList(); loadedBackgroundPaths = emptyList()
            mutable.update { it.copy(outlines = null, navigationBounds = null, chartLoaded = false,
                loadedChartCells = emptyList(), chartSourceGeneration = residentOwner.sourceGeneration,
                chartContentGeneration = residentOwner.contentGeneration, residentCatalogActive = false,
                residentCheckpointRevision = -1) }
            refresh(); queueRender(); EngineResult(true)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { EngineResult(false, failure.message ?: "Kartenfreigabe ausstehend") }
    }
    override suspend fun releaseRetiredChartSources(expectedCatalogGeneration: String): EngineResult = withContext(dispatcher) {
        try {
            residencyGuard()
            check(residentOwner.catalog?.generation == expectedCatalogGeneration) { "Veralteter Katalog-Freigabecheckpoint" }
            currentCoroutineContext().ensureActive()
            val released = NativeBridge.residentRetiredRelease(engine, expectedCatalogGeneration)
            changedResidency(residencyStatus()); refresh(); queueRender()
            EngineResult(released, if (released) "" else "Alte Kartenressourcen noch gepinnt; Dateilöschung ausstehend")
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { EngineResult(false, failure.message ?: "Kartenfreigabe ausstehend") }
    }
    private fun residentBindingSnapshot(): ChartResidentBinding {
        val catalog = checkNotNull(residentOwner.catalog) { "Kein freigegebener Resident-Katalog" }
        val status = residencyStatus(); changedResidency(status)
        check(status.enabled && status.catalogGeneration == catalog.generation)
        val viewport = residencyViewport()
        val activation = residentActivationSnapshot(ChartResidentActivation(loadedChartId ?: "resident-catalog",
            catalog, mutable.value.viewport, residentOwner.navigationBounds ?: ChartNavigationBounds(0, emptyList()), residentCameraPolicy))
        val ppmm = NativeBridge.residentPixelsPerMillimetre(engine)
        check(ppmm.isFinite() && ppmm > 0)
        return ChartResidentBinding(activation, residentOwner.sourceGeneration, residentOwner.contentGeneration,
            surfaceRevision, viewport, status.copy(sources = java.util.Collections.unmodifiableList(status.sources.toList()),
                geometries = java.util.Collections.unmodifiableList(status.geometries.toList())), ppmm)
    }
    override suspend fun readResidentBinding(): ChartResidentBinding? = withContext(dispatcher) {
        try { residencyGuard(allowCapacityWork = true); applyPendingViewport(); refresh(); residentBindingSnapshot() }
        catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
    }
    override suspend fun loadChartSet(id: String, paths: List<String>): EngineResult =
        loadChartSource(id, ChartSource.PublicFiles(paths.toList()))
    override suspend fun validateChartFiles(paths: List<String>, cacheDirectory: String): EngineResult = withContext(dispatcher) {
        try {
            check(!closed && engine != 0L && !profiling && captureBuffer == null && benchmarkRuns.activeRun() == null &&
                !mutable.value.rendererBenchmark.active && !mutable.value.rendererBenchmarkRecoveryPending) { "Messung oder Wiederherstellung vor dem Kartenimport abschließen" }
            for ((index, path) in paths.withIndex()) {
                currentCoroutineContext().ensureActive()
                NativeBridge.chartFileValidate(engine, path, "$cacheDirectory/$index.senc")
                yield()
            }
            EngineResult(true)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { EngineResult(false, failure.message ?: "S57 validation failed") }
    }
    override suspend fun validateChartFilesWithMetadata(paths: List<String>, cacheDirectory: String): ChartValidationResult =
        validateFilesWithMetadata(paths, cacheDirectory, piCache = false)
    override suspend fun validatePiCacheFilesWithMetadata(paths: List<String>, cacheDirectory: String): ChartValidationResult =
        validateFilesWithMetadata(paths, cacheDirectory, piCache = true)
    private suspend fun validateFilesWithMetadata(paths: List<String>, cacheDirectory: String, piCache: Boolean): ChartValidationResult = withContext(dispatcher) {
        try {
            check(!closed && engine != 0L && !profiling && captureBuffer == null && benchmarkRuns.activeRun() == null &&
                !mutable.value.rendererBenchmark.active && !mutable.value.rendererBenchmarkRecoveryPending) { "Messung oder Wiederherstellung vor dem Kartenimport abschließen" }
            val metadata = mutableListOf<ValidatedChartCell>()
            for ((index, path) in paths.withIndex()) {
                currentCoroutineContext().ensureActive()
                val validationContext = currentCoroutineContext()
                val provider = if (piCache) {
                    require(File(path).extension == "senc") { "Pi cache validation requires a plaintext SENC" }
                    PI_CACHE_PROVIDER
                } else S57Identification.read(File(path)) { validationContext.ensureActive() }.provider
                metadata += ChartCellMetadataDecoder.decode(path,
                    NativeBridge.chartFileValidateMetadata(engine, path, "$cacheDirectory/$index.senc"), provider)
                yield()
            }
            ChartValidationResult(EngineResult(true), metadata)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { ChartValidationResult(EngineResult(false, failure.message ?: "S57 validation failed")) }
    }
    override suspend fun loadChartSource(id: String, source: ChartSource): EngineResult = withContext(dispatcher) {
        try { check(!closed && engine != 0L && !profiling && captureBuffer == null &&
            benchmarkRuns.activeRun() == null && !mutable.value.rendererBenchmark.active &&
            (!mutable.value.rendererBenchmarkRecoveryPending || startupSelectionAllowed)) { "Messung oder Wiederherstellung vor der Kartenaktivierung abschließen" }
            synchronized(cameraLock) { pendingViewport.set(null); pendingMotions.clear() }
            markCameraPending(); sourceLoader.load(id, source) }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { EngineResult(false, e.message ?: "Chart load failed") }
    }
    override fun cancelRendererBenchmark() { benchmarkRuns.activeRun()?.let { cancelRendererBenchmark(it.id) } }
    override fun cancelRendererBenchmark(expectedRunId: String): EngineResult {
        if (expectedRunId.isBlank() || !benchmarkRuns.cancel(expectedRunId, "client cancel"))
            return EngineResult(false, "Expected run does not own an active measurement; recovery is never cancelled")
        signalBenchmarkCancel(expectedRunId, "client cancel")
        return EngineResult(true, expectedRunId)
    }
    override fun rendererBenchmarkStatus(clientRunToken: String?): RendererBenchmarkStatus =
        benchmarkRuns.status(mutable.value.rendererBenchmark, clientRunToken)
    private fun signalBenchmarkCancel(runId: String, reason: String) {
        if (capacitySamplingRunId == runId) {
            val at = System.nanoTime()
            if (capacityCancelRequestedNs.compareAndSet(0, at))
                capacitySoftwareEvents?.recordInstant("CANCEL_REQUESTED", at, capacityAuthority(),
                    buildJsonObject { put("reason", reason) })
        }
        val handle = engine
        // This JNI function uses a separate control registry, never the global renderer/GL mutex.
        if (handle != 0L) NativeBridge.benchmarkRunCancel(handle, runId, reason)
    }
    private fun benchmarkRunCancelled(runId: String): Boolean {
        benchmarkRuns.tick(android.os.SystemClock.elapsedRealtime())?.let { signalBenchmarkCancel(it.id, "device deadline exceeded") }
        return benchmarkRuns.cancelled(runId)
    }
    private fun benchmarkControlJson(runId: String): JsonObject = buildJsonObject {
        val control = checkNotNull(benchmarkRuns.control(runId))
        put("schema", "no5.measurement-control/1"); put("runId", runId)
        put("clientRunToken", control.run.config.clientRunToken)
        put("deadlineElapsedRealtimeMs", control.run.deadlineMs); put("recoveryBudgetMs", control.run.config.recoveryBudgetMs)
        put("requestedWorkers", control.run.config.requestedWorkers)
        put("cancelReason", control.cancelReason); put("recoveryDeadlineExceeded", control.recoveryDeadlineExceeded)
        put("recoveryStartedElapsedRealtimeMs", control.recoveryStartedMs?.let(::JsonPrimitive) ?: JsonNull)
        put("recoveryFinishedElapsedRealtimeMs", control.recoveryFinishedMs?.let(::JsonPrimitive) ?: JsonNull)
        put("clockDomain", "Android elapsedRealtime / CLOCK_BOOTTIME")
        put("idempotencyScope", "engine instance; on process restart retrieve saved report and verify process starttime before any START")
        put("nativeCancellation", "cooperative checkpoints; blocked driver/parser call cannot be preempted")
        put("recoveryBudgetPolicy", "over-budget flagged; private journal retained until validated restoration, never force-stop")
    }


    private suspend fun restoreBenchmarkUserState(saved: BenchmarkUserState): Boolean {
        if (closed || engine == 0L || surfaceGeometry == null) return false
        if (saved.currentViewOnly) return restoreCurrentViewReuseState(saved)
        if (saved.residentActivation != null) return restoreResidentBenchmarkUserState(saved)
        if (saved.paths.isEmpty()) closeNativeCharts()
        else check(openNativeCharts(saved.paths.toTypedArray(), saved.backgroundPaths)) { "Ursprüngliche Karten konnten nicht geladen werden. Nutzerbestand bleibt erhalten; Wiederherstellung erneut versuchen." }
        loadedChartId = saved.chartId; loadedChartPaths = saved.paths; loadedBackgroundPaths = saved.backgroundPaths
        activeSettings = saved.settings
        NativeBridge.s52SettingsSet(engine, saved.settings.palette.name, saved.settings.safetyContourMetres); replaceObjectFilters(saved.settings.objectFilters)
        val geometry = checkNotNull(surfaceGeometry)
        benchmarkViewport(saved.viewport.copy(width = geometry.frameWidth, height = geometry.frameHeight))
        benchmarkPresentation(saved.presentation)
        if (closed || surfaceGeometry == null) return false
        NativeBridge.s52SettingsSet(engine, saved.settings.palette.name, saved.settings.safetyContourMetres); replaceObjectFilters(saved.settings.objectFilters)
        activeOverlays = saved.overlays; replaceOverlays(saved.overlays); overlayInbox.markApplied(saved.overlays)
        renderAndRefresh()
        check(!mutable.value.presentationPending) { "Ursprünglicher Kartenstil noch nicht vollständig aufgebaut. Wiederherstellung erneut versuchen." }
        val restored = mutable.value
        check(restored.chartLoaded == saved.paths.isNotEmpty() && restored.presentation == saved.presentation &&
            kotlin.math.abs(restored.viewport.latitude - saved.viewport.latitude) < 1e-12 &&
            kotlin.math.abs(restored.viewport.longitude - saved.viewport.longitude) < 1e-12 &&
            kotlin.math.abs(restored.viewport.metresToPixels - saved.viewport.metresToPixels) < 1e-12 &&
            kotlin.math.abs(restored.viewport.rotationRadians - saved.viewport.rotationRadians) < 1e-12) {
            "Ursprüngliche Kartenansicht konnte nicht bestätigt werden. Wiederherstellung erneut versuchen."
        }
        withContext(Dispatchers.IO) { checkNotNull(benchmarkRecoveryStore).clear() }
        pendingBenchmarkRecovery = null; benchmarkRecoveryError = null
        mutable.update { it.copy(rendererBenchmarkRecoveryPending = false) }
        return true
    }

    /** No catalog replacement, geometry reset or writeback over a changed foreign configuration. */
    private suspend fun restoreCurrentViewReuseState(saved: BenchmarkUserState): Boolean {
        val activation = checkNotNull(saved.residentActivation)
        check(residentOwner.active && residentOwner.catalog == activation.catalog &&
            residentOwner.sourceGeneration == saved.residentSourceGeneration &&
            mutable.value.presentation == saved.presentation && benchmarkUserSettings(activeSettings,
                Json.parseToJsonElement(NativeBridge.statusRead(engine)).jsonObject) == saved.settings &&
            activeOverlays == saved.overlays && residencyStatus().policyKey == saved.residentPolicyKey.orEmpty() &&
            bufferTransformMode == saved.bufferTransformMode) { "Current-view restore authority/configuration changed; newer work preserved" }
        withContext(Dispatchers.IO) { verifyResidentSourcePins(activation) }
        // A current-view arm never changes policy; do not write over a newer owner's policy.
        val geometry = checkNotNull(surfaceGeometry)
        benchmarkViewport(saved.viewport.copy(width=geometry.frameWidth,height=geometry.frameHeight))
        renderAndRefresh()
        check(residentPoseMatches(mutable.value.viewport,saved.viewport.copy(width=geometry.frameWidth,height=geometry.frameHeight)) &&
            mutable.value.presentation == saved.presentation && residentOwner.catalog == activation.catalog) { "Current-view camera restoration unconfirmed" }
        withContext(Dispatchers.IO) { checkNotNull(benchmarkRecoveryStore).clear() }
        pendingBenchmarkRecovery=null;benchmarkRecoveryError=null
        mutable.update { it.copy(rendererBenchmarkRecoveryPending=false) }
        return true
    }

    private suspend fun restoreResidentBenchmarkUserState(saved: BenchmarkUserState): Boolean {
        val activation = residentActivationSnapshot(checkNotNull(saved.residentActivation))
        withContext(Dispatchers.IO) { verifyResidentSourcePins(activation) }
        val catalog = activation.catalog
        capacitySamplingRunId = null; invalidateSourcePresentation()
        if (residentOwner.catalog?.generation == catalog.generation) {
            check(saved.residentSourceGeneration == residentOwner.sourceGeneration) { "Runtime-Quellgeneration während Budgetarm verändert" }
            check(residentOwner.catalog?.sources == catalog.sources) { "Gesicherte Quellenpins wurden verändert" }
            val warm = NativeBridge.residentGeometryReset(engine, catalog.generation, catalog.budget.cells,
                catalog.budget.bytes, catalog.budget.loadingBytes, false)
            check(warm || NativeBridge.residentGeometryReset(engine, catalog.generation, catalog.budget.cells,
                catalog.budget.bytes, catalog.budget.loadingBytes, true)) { "Geometrie noch gepinnt; Wiederherstellung ausstehend" }
            residentOwner.budget(catalog.budget); changedResidency(residencyStatus())
        } else {
            NativeBridge.residentCatalogActivate(engine, catalog.generation, catalogFields(catalog),
                catalog.budget.cells, catalog.budget.bytes, catalog.budget.loadingBytes)
            check(NativeBridge.residentRetiredRelease(engine, catalog.generation)) { "Vorherige Ressourcen noch gepinnt" }
            residentOwner.activate(catalog, activation.navigationBounds, residencyStatus()); sourceCameraGuardChanged()
        }
        residentCameraPolicy = activation.cameraPolicy
        activation.cameraPolicy?.bind(residentOwner.sourceGeneration)?.let {
            residentCameraGuard = CameraGuardBinding(catalog.generation, residentOwner.sourceGeneration, it)
        }
        pendingResidentStart = PendingResidentStart(saved.viewport, null); preserveRestoredCamera = true
        mutable.update { it.copy(residentCameraReady = false, residentStartState = ChartResidentStartState.UNKNOWN) }
        NativeBridge.residentPolicyRestore(engine, saved.residentPolicyKey.orEmpty())
        residentLastStep = null
        loadedChartId = activation.id; loadedChartPaths = emptyList(); loadedBackgroundPaths = emptyList()
        activeSettings = saved.settings
        val geometry = checkNotNull(surfaceGeometry)
        benchmarkViewport(saved.viewport.copy(width = geometry.frameWidth, height = geometry.frameHeight))
        if (mutable.value.presentation != saved.presentation) benchmarkPresentation(saved.presentation)
        NativeBridge.s52SettingsSet(engine, saved.settings.palette.name, saved.settings.safetyContourMetres); replaceObjectFilters(saved.settings.objectFilters)
        activeOverlays = saved.overlays; replaceOverlays(saved.overlays); overlayInbox.markApplied(saved.overlays)
        bufferTransformMode = saved.bufferTransformMode
        mutable.update { it.copy(bufferTransformMode = bufferTransformMode, navigationBounds = activation.navigationBounds,
            chartSourceGeneration = residentOwner.sourceGeneration, chartContentGeneration = residentOwner.contentGeneration,
            residentCatalogActive = true, residentCheckpointRevision = -1) }
        renderAndRefresh()
        val restored = residentBindingSnapshot()
        check(restored.activation.catalog == catalog && restored.activation.navigationBounds == activation.navigationBounds &&
            restored.status.policyKey == saved.residentPolicyKey.orEmpty() &&
            mutable.value.presentation == saved.presentation && activeSettings == saved.settings && activeOverlays == saved.overlays &&
            bufferTransformMode == saved.bufferTransformMode && residentPoseMatches(mutable.value.viewport, saved.viewport.copy(width = geometry.frameWidth, height = geometry.frameHeight))) {
            "Gesicherter Katalog/Budget/Kamera konnte nicht bestätigt werden"
        }
        // A valid catalog/configuration restore is separate from geometry/FULL/display completion.
        withContext(Dispatchers.IO) { checkNotNull(benchmarkRecoveryStore).clear() }
        pendingBenchmarkRecovery = null; benchmarkRecoveryError = null
        mutable.update { it.copy(rendererBenchmarkRecoveryPending = false) }
        return true
    }

    override suspend fun snapshotRendererOuterState(snapshotId: String): String? = withContext(dispatcher) {
        check(java.util.UUID.fromString(snapshotId).toString() == snapshotId)
        fun readyForOuterSnapshot() = !closed && engine != 0L && residentOwner.active && surfaceGeometry != null &&
            !profiling && captureBuffer == null && benchmarkRuns.activeRun() == null &&
            mutable.value.chartLoaded && mutable.value.residentCameraReady && !mutable.value.rendererCapture.active &&
            !surfaceCommandPending.get() && !mutable.value.rendererBenchmark.active && !mutable.value.rendererBenchmarkRecoveryPending &&
            !mutable.value.presentationPending && presentationPreparing.get() == 0 &&
            !transformTouchActive.get() && !touchFrames.held.get()
        check(readyForOuterSnapshot()) { "Settle the current entry catalog first" }
        // This immutable binding authorizes only the IO preflight, not the later camera snapshot.
        val validatedBinding = residentBindingSnapshot()
        check(validatedBinding.status.complete && validatedBinding.status.loading == 0) { "Wait for required geometry completion" }
        val validatedRecoveryStore = checkNotNull(benchmarkRecoveryStore)
        val binding = rendererOuterSnapshotPreflight(validatedBinding, validatedRecoveryStore, {
            check(readyForOuterSnapshot()) { "Snapshot preflight authority changed: OWNER_READINESS" }
            check(benchmarkRecoveryStore === validatedRecoveryStore) { "Snapshot preflight authority changed: RECOVERY_STORE_OWNER" }
            residentBindingSnapshot()
        })
        // No suspension below: capture the current FULL/camera/overlays under the same owner.
        val before = mutable.value
        val cameraRevision = requestedCameraRevision
        val geometry = checkNotNull(surfaceGeometry)
        val rasterGeometry = producerRasterGeometry
        val stamp = if (bufferTransformMode == ChartBufferTransformMode.OFF) plainSubmittedStamp else bufferRegistry.observedStamp()
        val full = checkNotNull(bufferRegistry.lookup(stamp)) { "Wait for a registered FULL producer" }
        val registeredProducer = checkNotNull(benchmarkOuterProducerCamera(full, before.viewport,
            geometry, rasterGeometry, binding.producerViewport, bufferTransformMode)) {
            "Visible camera/filled producer/native raster binding is not confirmed"
        }
        check(full.producerCompleted && full.stage == ChartContentStage.FULL && residentOwner.frameMatches(full.frame) &&
            full.cameraRevision == cameraRevision && full.frame.geometry == geometry) { "Current camera/producer admission is not confirmed" }
        check(appliedCameraRevision == cameraRevision && pendingViewport.get() == null &&
            synchronized(cameraLock) { pendingMotions.isEmpty() }) { "Camera work is still pending" }
        // Controller recovery rebinds the guard and clears its old admission. Recount
        // this exact, already produced camera; never move it or reuse that old proof.
        val guardBinding = residentCameraGuard
        val surfaceCommand = surfaceCommandRevision.get()
        fun admissionCurrent() = !closed && residentOwner.catalog == binding.activation.catalog &&
            residentOwner.sourceGeneration == binding.sourceGeneration && residentCameraGuard === guardBinding &&
            (binding.activation.catalog.sources.isEmpty() || guardBinding?.let {
                it.catalogGeneration == binding.activation.catalog.generation && it.sourceGeneration == binding.sourceGeneration
            } == true) && mutable.value.chartSourceGeneration == binding.sourceGeneration &&
            mutable.value.residentCatalogGeneration == binding.activation.catalog.generation &&
            requestedCameraRevision == cameraRevision && appliedCameraRevision == cameraRevision &&
            surfaceGeometry == geometry && surfaceCommandRevision.get() == surfaceCommand && !surfaceCommandPending.get() &&
            mutable.value.viewport == before.viewport && mutable.value.surfaceGeneration == before.surfaceGeneration &&
            bufferTransformMode == before.bufferTransformMode && pendingViewport.get() == null && pendingMotions.isEmpty()
        val admission = synchronized(cameraLock) {
            checkNotNull(benchmarkCurrentCameraAdmission(residentStartRequest(before.viewport, before.viewport,
                binding.activation.catalog, binding.sourceGeneration, geometry), binding.activation.catalog.budget,
                geometry, binding.activation.catalog.sources.isEmpty(), guardBinding?.guard, ::admissionCurrent)) {
                "Current camera admission could not be reconfirmed under the bound guard"
            }
        }
        val saved = BenchmarkUserState(snapshotId, loadedChartId, emptyList(), before.viewport,
            before.presentation, benchmarkUserSettings(activeSettings,
                Json.parseToJsonElement(NativeBridge.statusRead(engine)).jsonObject),
            activeOverlays, binding.activation, binding.sourceGeneration, binding.status.policyKey, bufferTransformMode)
        // Optional new diagnostic metadata cannot weaken or replace the existing outer admission.
        // If unavailable, the legacy snapshot still works but cannot arm current-resident LIVE_INPUT.
        val liveIdentityBefore = if (bufferTransformMode == ChartBufferTransformMode.OFF &&
            synchronized(cameraLock) { cameraControl.idleForMeasurement() })
            runCatching { readCurrentResidentLiveIdentity() }.getOrNull() else null
        val after = mutable.value
        // Sequential keys retain short-circuit safety: never issue JNI after an invalid owner.
        fun authority(condition: Boolean, key: String) { check(condition) { "Snapshot authority changed: $key" } }
        authority(!closed && engine != 0L, "OWNER_OPEN")
        authority(residentOwner.active, "RESIDENT_ACTIVE")
        authority(!profiling && captureBuffer == null && benchmarkRuns.activeRun() == null, "MEASUREMENT_IDLE")
        authority(requestedCameraRevision == cameraRevision, "REQUESTED_CAMERA_REVISION")
        authority(surfaceGeometry == geometry, "SURFACE_GEOMETRY")
        authority(producerRasterGeometry == rasterGeometry, "PRODUCER_RASTER_GEOMETRY")
        authority(benchmarkOuterProducerCamera(full, after.viewport, geometry, rasterGeometry,
            residencyViewport(), bufferTransformMode) == registeredProducer, "FULL_CAMERA_BINDING")
        authority(residentOwner.catalog == binding.activation.catalog, "CATALOG")
        authority(residentOwner.sourceGeneration == binding.sourceGeneration, "SOURCE_GENERATION")
        authority(after.viewport == saved.viewport, "VIEWPORT")
        authority(after.presentation == saved.presentation, "PRESENTATION")
        authority(activeOverlays == saved.overlays, "OVERLAYS")
        authority(bufferTransformMode == saved.bufferTransformMode, "BUFFER_MODE")
        authority(benchmarkUserSettings(activeSettings,
            Json.parseToJsonElement(NativeBridge.statusRead(engine)).jsonObject) == saved.settings, "SETTINGS")
        authority(after.chartLoaded && after.residentCameraReady, "CHART_READY")
        authority(!after.rendererCapture.active, "CAPTURE_IDLE")
        authority(after.residentCameraAdmissionRevision == before.residentCameraAdmissionRevision, "ADMISSION_REVISION")
        authority(after.residentCameraDecision == before.residentCameraDecision, "CAMERA_DECISION")
        authority(!after.presentationPending && presentationPreparing.get() == 0, "PRESENTATION_READY")
        authority(!transformTouchActive.get() && !touchFrames.held.get(), "TOUCH_IDLE")
        authority(!surfaceCommandPending.get(), "SURFACE_COMMAND_IDLE")
        authority(pendingViewport.get() == null, "VIEWPORT_INBOX_EMPTY")
        authority(synchronized(cameraLock) { pendingMotions.isEmpty() }, "MOTION_INBOX_EMPTY")
        authority(appliedCameraRevision == cameraRevision, "APPLIED_CAMERA_REVISION")
        authority(residentOwner.contentGeneration == binding.contentGeneration, "CONTENT_GENERATION")
        authority(bufferRegistry.lookup(stamp) == full, "FULL_REGISTRY")
        authority(!after.rendererBenchmark.active && !after.rendererBenchmarkRecoveryPending, "BENCHMARK_RECOVERY_IDLE")
        synchronized(cameraLock) {
            check(admissionCurrent()) { "Snapshot camera/guard authority changed" }
            mutable.update {
                check(admissionCurrent()) { "Snapshot camera/guard authority changed before admission publication" }
                it.copy(residentCameraDecision = admission)
            }
            residentCameraAdmission = CameraAdmission(residentStartRequest(before.viewport, before.viewport,
                binding.activation.catalog, binding.sourceGeneration, geometry).proposal(before.viewport), admission,
                binding.activation.catalog, bufferTransformMode, geometry)
        }
        if (liveIdentityBefore != null && liveIdentityBefore.catalog.sources.isNotEmpty()) {
            check(readCurrentResidentLiveIdentity() == liveIdentityBefore) { "Snapshot native data authority changed" }
            synchronized(cameraLock) {
                check(admissionCurrent() && requestedCameraRevision == cameraRevision && bufferRegistry.lookup(stamp) == full)
                if (cameraControl.idleForMeasurement())
                    currentResidentLiveSnapshots.remember(CurrentResidentLiveSnapshot(snapshotId, liveIdentityBefore,
                        saved, geometry, cameraRevision, full))
            }
        }
        buildJsonObject {
            put("schema", "no5-capacity-outer-state/1"); put("scope", "CURRENT_ENTRY_CATALOG_ONLY")
            put("snapshotId", snapshotId); put("rendererSession", rendererSession); put("state", checkNotNull(benchmarkRecoveryStore).snapshot(saved))
            put("authority", capacityAuthority(full.cameraRevision, registeredProducer))
            put("visibleViewport", BostonBenchmarkRecipe.cameraJson(full.frame.viewport))
            put("visibleGeometry", benchmarkOuterGeometry(geometry))
            put("producerRasterGeometry", rasterGeometry?.let(::benchmarkOuterRasterGeometry) ?: JsonNull)
            put("producerRaster", full.raster?.let(::benchmarkOuterRaster) ?: JsonNull)
            put("fullProducerBuffer", buildJsonObject { put("stamp", full.stamp); put("cameraRevision", full.cameraRevision); put("sourceGeneration", full.frame.sourceGeneration); put("contentGeneration", full.frame.contentGeneration); put("producerCompleted", true); put("physicalDisplay", "NOT_MEASURED") })
            put("producerViewport", BostonBenchmarkRecipe.cameraJson(registeredProducer))
            put("residency", Json.parseToJsonElement(NativeBridge.residencyRead(engine)))
            put("physicalInputToDisplay", "NOT_MEASURED")
        }.toString()
    }
    override suspend fun restoreRendererOuterState(expectedSnapshotId: String): EngineResult = withContext(dispatcher) {
        try {
            check(java.util.UUID.fromString(expectedSnapshotId).toString() == expectedSnapshotId)
            check(!profiling && captureBuffer == null && benchmarkRuns.activeRun() == null &&
                !mutable.value.rendererBenchmark.active && pendingBenchmarkRecovery == null && benchmarkRecoveryError == null) {
                "Recover the existing arm first; never overwrite its journal"
            }
            val saved = withContext(Dispatchers.IO) { checkNotNull(benchmarkRecoveryStore).read() }
            check(saved != null && saved.runId == expectedSnapshotId && saved.residentActivation != null) { "Outer journal owner mismatch" }
            restoreInterruptedRendererBenchmark()
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { EngineResult(false, failure.message ?: "Outer restore rejected") }
    }

    override suspend fun restoreInterruptedRendererBenchmark(): EngineResult = withContext(dispatcher) {
        if (closed || engine == 0L || surfaceGeometry == null) return@withContext EngineResult(false, "Kartenanzeige für Wiederherstellung im Vordergrund öffnen.")
        if (profiling || mutable.value.rendererBenchmark.active) return@withContext EngineResult(false, "Messung oder Wiederherstellung läuft bereits.")
        profiling = true
        benchmarkRecoveryJob = currentCoroutineContext()[Job]
        mutable.update { it.copy(rendererBenchmark = it.rendererBenchmark.copy(active = true, phase = "restoring", message = "Ursprüngliche Kartenansicht wird wiederhergestellt")) }
        try {
            val saved = pendingBenchmarkRecovery ?: withContext(Dispatchers.IO) { benchmarkRecoveryStore?.read() }
            checkNotNull(saved) { benchmarkRecoveryError ?: "Keine gesicherte Ansicht vorhanden" }
            normalAbortRunId?.let {
                check(it == saved.runId) { "Normal-abort recovery owner changed" }
                NativeBridge.benchmarkRunEnd(engine, saved.runId)
                restoreNormalAbortControls(saved.runId)
            }
            check(restoreBenchmarkUserState(saved)) { "Wiederherstellung unterbrochen. App erneut öffnen oder erneut versuchen." }
            mutable.update { it.copy(rendererBenchmark = it.rendererBenchmark.copy(active = false, phase = "interrupted", restored = true,
                message = "Unterbrochene Messung beendet; ursprüngliche Karten und Ansicht wiederhergestellt. Test kann erneut gestartet werden.")) }
            EngineResult(true)
        } catch (failure: Exception) {
            mutable.update { it.copy(rendererBenchmarkRecoveryPending = true, rendererBenchmark = it.rendererBenchmark.copy(active = false,
                phase = "failed", restored = false, message = failure.message ?: "Wiederherstellung fehlgeschlagen. Bitte erneut versuchen.")) }
            EngineResult(false, mutable.value.rendererBenchmark.message)
        } finally { benchmarkRecoveryJob = null; profiling = false; if (!closed) queueRender() }
    }

    private suspend fun benchmarkPresentation(value: ChartPresentation) {
        value.validate()
        var token = 0L
        try {
            token = presentationPreparation.withPermit {
                withContext(NonCancellable + Dispatchers.IO) { NativeBridge.presentationPrepare(dataDirectory, value.styleId, value.symbols.id) }
            }
            check(engine != 0L) { "Engine closed during presentation preparation" }
            val generation = presentationRequests.incrementAndGet()
            val applied = NativeBridge.presentationApply(engine, token, value.landRelief, generation)
            token = 0L
            check(applied) { "Benchmark presentation could not be applied" }
            refresh()
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { throw IllegalStateException("Kartenstil ${value.styleId}/${value.symbols.id} konnte nicht aufgebaut werden. Rendererdiagnose und vollständige APK prüfen.", failure) }
        finally { if (token != 0L) NativeBridge.presentationDiscard(token) }
    }

    private fun benchmarkViewport(view: ChartViewport) {
        markCameraPending()
        val renderAuthority = synchronized(cameraLock) {
            pendingLiveOrigin = null;appliedLiveOrigin=null;appliedLiveOriginRevision=-1
            pendingViewport.set(null); pendingMotions.clear(); appliedCameraRevision = requestedCameraRevision
            announceNormalRenderAuthority()
        }
        NativeBridge.viewportSet(engine, view.latitude, view.longitude, view.metresToPixels, view.rotationRadians, view.width, view.height)
        markNormalRenderApplied(renderAuthority)
        mutable.update { it.copy(viewport = view) }
    }

    override suspend fun startRendererBenchmark(config: RendererBenchmarkConfig): EngineResult =
        if (config.currentLiveParityOnly && !diagnosticPermission()) EngineResult(false, "DUMP permission required for current-live FULL parity")
        else if (config.runKind == RendererRunKind.RESIDENT_CAPACITY) startResidentCapacityMeasurement(config)
        else if (config.currentResidentLive != null) startLiveInputMeasurement(config)
        else if (mutable.value.residentCatalogActive) EngineResult(false, "SCRIPTED/LIVE_INPUT benötigen einen separat wiederherstellbaren eager Bestand")
        else if (config.runKind == RendererRunKind.LIVE_INPUT) startLiveInputMeasurement(config) else startRendererMeasurement(config)

    private fun residentCapacityObservation(): JsonObject {
        applyPendingViewport(); refresh()
        val binding = residentBindingSnapshot()
        val stamp = if (bufferTransformMode == ChartBufferTransformMode.OFF) plainSubmittedStamp else bufferRegistry.observedStamp()
        val full = bufferRegistry.lookup(stamp)?.takeIf { it.producerCompleted && it.stage == ChartContentStage.FULL &&
            residentOwner.frameMatches(it.frame) }
        return buildJsonObject {
            put("catalogGeneration", binding.activation.catalog.generation)
            put("sourceGeneration", binding.sourceGeneration); put("contentGeneration", binding.contentGeneration)
            put("surfaceGeneration", binding.surfaceGeneration); put("pixelsPerMillimetre", binding.pixelsPerMillimetre)
            put("producerViewport", BostonBenchmarkRecipe.cameraJson(binding.producerViewport))
            put("chartScaleDenominator", binding.chartScaleFor(binding.producerViewport.metresToPixels)?.let(::JsonPrimitive) ?: JsonNull)
            put("residency", Json.parseToJsonElement(NativeBridge.residencyRead(engine)))
            put("fullProducerBuffer", full?.let { buffer -> buildJsonObject {
                put("stamp", buffer.stamp); put("cameraRevision", buffer.cameraRevision)
                put("sourceGeneration", buffer.frame.sourceGeneration); put("contentGeneration", buffer.frame.contentGeneration)
                put("producerCompleted", true); put("physicalDisplay", "notMeasured")
            } } ?: JsonNull)
            put("residentCheckpointRevision", residentOwner.checkpointRevision)
        }
    }

    /** This runs only on the serial native owner, and never holds cameraLock across JNI readback. */
    private fun viewportReuseParityAuthority(runId: String, wanted: ChartViewport, revision: Long,
        frozen: JsonObject, surfaceEpoch: Long): ViewportReuseParityAuthority? {
        check(capacityResidentWorkAllowed() && !benchmarkRunCancelled(runId) && surfaceRevision == surfaceEpoch &&
            !surfaceCommandPending.get() && bufferTransformMode == ChartBufferTransformMode.OFF) { "Parity run/surface authority revoked" }
        val full = synchronized(cameraLock) {
            val state = mutable.value
            val authority = capacityAuthority(capturedState = state, capturedRequestedRevision = requestedCameraRevision)
            check(listOf("catalogGeneration", "sourceGeneration", "surfaceGeneration", "budget").all {
                frozen[it] != null && frozen[it] != JsonNull && frozen[it] == authority[it]
            } && requestedCameraRevision == revision && appliedCameraRevision == revision && state.viewport == wanted &&
                pendingViewport.get() == null && pendingMotions.isEmpty() && state.residentCameraReady && !state.presentationPending) {
                "Parity camera/source authority changed"
            }
            val buffer = bufferRegistry.lookup(plainSubmittedStamp)
            buffer?.takeIf { viewportReuseParityFullMatches(it, wanted, revision, surfaceGeometry,
                residentOwner.sourceGeneration, residentOwner.contentGeneration) && residentOwner.frameMatches(it.frame) }
        } ?: return null // An older completed buffer is not relabeled as this camera's FULL.
        val native = Json.parseToJsonElement(NativeBridge.statusRead(engine)).jsonObject
        val timing = Json.parseToJsonElement(NativeBridge.frameTimingRead(engine)).jsonObject
        val resident = Json.parseToJsonElement(NativeBridge.residencyRead(engine)).jsonObject
        check(resident.getValue("enabled").jsonPrimitive.boolean && resident.getValue("complete").jsonPrimitive.boolean &&
            resident.getValue("catalogGeneration").jsonPrimitive.content == mutable.value.residentCatalogGeneration &&
            native.getValue("latitude").jsonPrimitive.double == wanted.latitude &&
            native.getValue("longitude").jsonPrimitive.double == wanted.longitude &&
            native.getValue("ppm").jsonPrimitive.double == wanted.metresToPixels &&
            native.getValue("rotation").jsonPrimitive.double == wanted.rotationRadians &&
            native.getValue("width").jsonPrimitive.int == wanted.width && native.getValue("height").jsonPrimitive.int == wanted.height) {
            "Parity native FULL/viewport/geometry not exact"
        }
        return ViewportReuseParityAuthority(runId, resident.getValue("catalogGeneration").jsonPrimitive.content,
            full.frame.sourceGeneration, surfaceEpoch, full.frame.contentGeneration, revision,
            timing.getValue("frame").jsonPrimitive.long, native.getValue("dataGeneration").jsonPrimitive.long,
            resident.getValue("contentRevision").jsonPrimitive.long, mutable.value.presentationGeneration, full.stamp, wanted)
    }

    /** One pose may wait for FULL, but cannot move the original recipe or extend any frame/run deadline. */
    private suspend fun captureViewportReuseParity(runId: String, step: ResidentCapacityPoseStep,
        revision: Long, recipe: ResidentCapacityPoseRecipe, frozen: JsonObject, surfaceEpoch: Long,
        evidence: ViewportReuseParityEvidence) {
        val started = recipe.snapshot().getValue("startedNs").jsonPrimitive.long
        val nextOffset = recipe.steps.getOrNull(step.index + 1)?.offsetMs ?: recipe.durationMs
        val deadlineNs = viewportReuseParityDeadlineNs(System.nanoTime(), started, nextOffset)
        try {
            while (System.nanoTime() < deadlineNs) {
                currentCoroutineContext().ensureActive()
                val captured = withContext(dispatcher) {
                    val before = viewportReuseParityAuthority(runId, step.viewport, revision, frozen, surfaceEpoch)
                        ?: return@withContext false
                    evidence.capture(step.index, before, deadlineNs, System::nanoTime,
                        { capacityResidentWorkAllowed() && !benchmarkRunCancelled(runId) && surfaceRevision == surfaceEpoch },
                        { NativeBridge.benchmarkViewportReuseCapture(engine, runId, before.nativeFrame,
                            before.catalogGeneration, before.nativeContentRevision, before.nativeDataGeneration,
                            step.viewport.latitude, step.viewport.longitude, step.viewport.metresToPixels,
                            step.viewport.rotationRadians, step.viewport.width, step.viewport.height) },
                        { checkNotNull(viewportReuseParityAuthority(runId, step.viewport, revision, frozen, surfaceEpoch)) {
                            "Parity FULL disappeared after capture"
                        } })
                    true
                }
                if (captured) return
                delay(1)
            }
            error("Exact FULL not available inside parity deadline")
        } catch (failure: Exception) {
            evidence.fail(failure.message ?: "Parity capture failed")
            signalBenchmarkCancel(runId, "Parity capture failed")
            throw failure
        }
    }

    /** No source/camera inference: read one exact completed registry FULL on the serial native owner. */
    private data class CurrentLiveParityObservation(val buffer: ChartBufferSnapshot,
        val authority: CurrentLiveFullParityAuthority)

    private fun currentLiveParityAuthority(lease: CurrentResidentLiveLease, evidence: CurrentLiveFullParityEvidence,
        wanted: ChartViewport, revision: Long, enabled: Boolean): CurrentLiveParityObservation? {
        check(diagnosticPermission() && currentResidentLiveLease === lease && currentResidentLiveWorkAllowed() &&
            !benchmarkRunCancelled(lease.runId) && !surfaceCommandPending.get() &&
            bufferTransformMode == ChartBufferTransformMode.CONTROL && normalAbortEnabled == enabled) {
            "Current-live parity run/permission/surface authority revoked"
        }
        val full = synchronized(cameraLock) {
            val state = mutable.value
            check(requestedCameraRevision == revision && state.viewport == wanted) { "Current-live parity camera authority changed" }
            if (pendingViewport.get() != null || pendingMotions.isNotEmpty() || !state.residentCameraReady ||
                state.presentationPending || appliedCameraRevision != revision) return null
            bufferRegistry.lookup(transformSubmittedStamp)?.takeIf {
                it.generation > 0 && it.generation == bufferRegistry.generation() &&
                    viewportReuseParityFullMatches(it, wanted, revision, surfaceGeometry,
                        lease.snapshot.identity.source, lease.snapshot.identity.content) &&
                    it.presentationSlot in 0..1 && residentOwner.frameMatches(it.frame)
            }
        } ?: return null
        val native = Json.parseToJsonElement(NativeBridge.statusRead(engine)).jsonObject
        val timing = Json.parseToJsonElement(NativeBridge.frameTimingRead(engine)).jsonObject
        val resident = Json.parseToJsonElement(NativeBridge.residencyRead(engine)).jsonObject
        check(currentResidentLiveNativeValid(native) && currentResidentLiveLease === lease &&
            resident.getValue("enabled").jsonPrimitive.boolean && resident.getValue("complete").jsonPrimitive.boolean &&
            resident.getValue("catalogGeneration").jsonPrimitive.content == lease.snapshot.identity.catalog.generation &&
            resident.getValue("contentRevision").jsonPrimitive.long == lease.snapshot.identity.nativeContent &&
            native.getValue("dataGeneration").jsonPrimitive.long == lease.snapshot.identity.nativeData &&
            native.getValue("latitude").jsonPrimitive.double == wanted.latitude &&
            native.getValue("longitude").jsonPrimitive.double == wanted.longitude &&
            native.getValue("ppm").jsonPrimitive.double == wanted.metresToPixels &&
            native.getValue("rotation").jsonPrimitive.double == wanted.rotationRadians &&
            native.getValue("width").jsonPrimitive.int == wanted.width && native.getValue("height").jsonPrimitive.int == wanted.height) {
            "Current-live parity native FULL/viewport/source not exact"
        }
        val applied = synchronized(cameraLock) {
            check(currentResidentLiveLease === lease && currentResidentLiveWorkAllowed() &&
                requestedCameraRevision == revision && appliedCameraRevision == revision && mutable.value.viewport == wanted &&
                bufferRegistry.lookup(full.stamp) === full && bufferRegistry.generation() == full.generation &&
                transformSubmittedStamp == full.stamp && !surfaceCommandPending.get()) { "Current-live parity registry FULL changed" }
            if (enabled) checkNotNull(normalRenderAuthority.applied()).also {
                check(it === normalRenderAuthority.latest() && it.cameraRevision == revision &&
                    it.sourceGeneration == lease.snapshot.identity.source && it.surfaceGeneration == lease.snapshot.identity.surface) {
                    "Current-live parity applied authority changed"
                }
            } else null
        }
        return CurrentLiveParityObservation(full, CurrentLiveFullParityAuthority(ViewportReuseParityAuthority(lease.runId,
            lease.snapshot.identity.catalog.generation, full.frame.sourceGeneration, lease.snapshot.identity.surface,
            full.frame.contentGeneration, revision, timing.getValue("frame").jsonPrimitive.long,
            native.getValue("dataGeneration").jsonPrimitive.long, resident.getValue("contentRevision").jsonPrimitive.long,
            mutable.value.presentationGeneration, full.stamp, wanted), evidence.captureId, full.generation,
            full.presentationSlot, enabled, applied?.epoch))
    }

    /** Six existing deterministic owner proposals; only readbacks are new, never a second render path. */
    private suspend fun runCurrentLiveFullParity(lease: CurrentResidentLiveLease, evidence: CurrentLiveFullParityEvidence,
        recipe: ResidentCapacityPoseRecipe, enabled: Boolean, checkReady: () -> Unit) {
        val frozen = recipe.frozenAuthority
        recipe.run(propose = { step ->
            checkReady()
            val target = submitCameraViewport(step.viewport, 0, cameraClock.now())
            fun observe() = captureResidentRecipeCameraReceipt(cameraLock, target, step.viewport, frozen,
                { cameraControlResult(target, it) }, { mutable.value }, { requestedCameraRevision },
                { state, revision -> capacityAuthority(capturedState = state, capturedRequestedRevision = revision) },
                { diagnosticPermission() && currentResidentLiveLease === lease && currentResidentLiveCameraAllowed() &&
                    !surfaceCommandPending.get() && !benchmarkRunCancelled(lease.runId) })
            var observation: ResidentRecipeCameraReceipt? = null
            val accepted = withTimeoutOrNull(250) {
                var current = observe(); observation = current
                while (!current.accepted && !current.terminal) { delay(1); current = observe(); observation = current }
                currentCoroutineContext().ensureActive(); current.accepted
            } == true
            val response = checkNotNull(observation) { "No current-live camera receipt before deadline" }.response(accepted)
            check(accepted) { "Current-live parity pose was not accepted" }
            val revision = response.getValue("authority").jsonObject.getValue("cameraRevision").jsonPrimitive.long
            val started = recipe.snapshot().getValue("startedNs").jsonPrimitive.long
            val nextOffset = recipe.steps.getOrNull(step.index + 1)?.offsetMs ?: recipe.durationMs
            val until = viewportReuseParityDeadlineNs(System.nanoTime(), started, nextOffset)
            var captured = false
            while (!captured && System.nanoTime() < until) {
                currentCoroutineContext().ensureActive(); checkReady()
                captured = withContext(dispatcher) {
                    val before = currentLiveParityAuthority(lease, evidence, step.viewport, revision, enabled)
                        ?: return@withContext false
                    evidence.capture(step.index, before.authority, until, System::nanoTime,
                        { diagnosticPermission() && currentResidentLiveLease === lease && currentResidentLiveWorkAllowed() &&
                            !benchmarkRunCancelled(lease.runId) },
                        { NativeBridge.benchmarkCurrentLiveParityCapture(engine, lease.runId, evidence.captureId,
                            before.buffer.stamp, before.authority.native.nativeFrame, revision, before.buffer.generation,
                            step.viewport.latitude, step.viewport.longitude, step.viewport.metresToPixels,
                            step.viewport.rotationRadians, step.viewport.width, step.viewport.height, before.buffer.presentationSlot) },
                        { val after = checkNotNull(currentLiveParityAuthority(lease, evidence, step.viewport, revision, enabled)) {
                                "Current-live exact FULL disappeared after capture"
                            }
                            check(after.buffer === before.buffer) { "Current-live FULL registry reference replaced during capture" }
                            after.authority })
                    true
                }
                if (!captured) delay(1)
            }
            check(captured) { "Exact current-live FULL unavailable inside original pose deadline" }
            response
        })
        check(recipe.snapshot().getValue("comparableRealizedPath").jsonPrimitive.boolean &&
            evidence.snapshot().getValue("complete").jsonPrimitive.boolean) { "Current-live parity recipe or evidence incomplete" }
    }

    /** A single budget arm reuses the ordinary session/loader; it never substitutes an eager subset. */
    private suspend fun startResidentCapacityMeasurement(config: RendererBenchmarkConfig): EngineResult {
        val arm = checkNotNull(config.capacityArm)
        val currentViewReuse = config.capacityRecipeId == "CURRENT_VIEW_REUSE"
        val admission = benchmarkRuns.admit(config, android.os.SystemClock.elapsedRealtime())
        val run = when (admission) {
            is BenchmarkRunRegistry.Admission.Reused -> return EngineResult(true, admission.run.id)
            is BenchmarkRunRegistry.Admission.Rejected -> return EngineResult(false, admission.message)
            is BenchmarkRunRegistry.Admission.Started -> admission.run
        }
        val runId = run.id
        val parity = if (config.viewportReuseParity) ViewportReuseParityEvidence(runId) else null
        val watchdog = benchmarkWatchScope.launch {
            while (benchmarkRuns.isActive(runId)) {
                benchmarkRuns.tick(android.os.SystemClock.elapsedRealtime())?.let { signalBenchmarkCancel(it.id, "device deadline exceeded") }
                delay(100)
            }
        }
        var launched = false
        try {
            val accepted = withContext(dispatcher) {
                check(!closed && engine != 0L && residentOwner.active && surfaceGeometry != null &&
                    !profiling && !mutable.value.rendererBenchmark.active && !mutable.value.rendererBenchmarkRecoveryPending &&
                    captureBuffer == null && presentationPreparing.get() == 0 && !mutable.value.presentationPending) {
                    "Resident-Katalog und Anzeige bereitstellen; andere Messung/Wiederherstellung abschließen"
                }
                applyPendingViewport(); applyPendingOverlays(); refresh()
                val binding = residentBindingSnapshot()
                if (currentViewReuse) check(arm.budget == binding.activation.catalog.budget &&
                    arm.cacheMode == ResidentCapacityCacheMode.WARM && bufferTransformMode == ChartBufferTransformMode.OFF) {
                    "CURRENT_VIEW_REUSE requires the unchanged current budget, retained geometry and OFF buffer-transform mode"
                }
                check(binding.sourceGeneration == arm.expectedSourceGeneration &&
                    binding.activation.catalog.generation == arm.expectedCatalogGeneration) { "Capacityarm gehört zu einem anderen Kartenbestand" }
                check(mutable.value.residentCameraReady) { "Startausschnitt zuerst prüfen" }
                val start = residentStartRequest(mutable.value.viewport, null, binding.activation.catalog,
                    binding.sourceGeneration, checkNotNull(surfaceGeometry))
                val admitted = residentStartAdmission(start, arm.budget, checkNotNull(surfaceGeometry),
                    binding.activation.catalog.sources.isEmpty(), residentCameraGuard?.guard, select = false)
                check(admitted.acceptedCamera == mutable.value.viewport) { "Feste Startpose überschreitet diesen Budgetarm oder ist unbekannt" }
                val saved = BenchmarkUserState(runId, loadedChartId, emptyList(), mutable.value.viewport,
                    mutable.value.presentation, benchmarkUserSettings(activeSettings, Json.parseToJsonElement(NativeBridge.statusRead(engine)).jsonObject),
                    activeOverlays, binding.activation, binding.sourceGeneration, binding.status.policyKey, bufferTransformMode, currentViewOnly=currentViewReuse)
                val surfaceEpoch = surfaceRevision
                if (benchmarkRunCancelled(runId)) return@withContext EngineResult(false, "Messung vor Vorbereitung abgebrochen")
                check(NativeBridge.benchmarkRunBegin(engine, runId, run.deadlineMs)) { "Native run control refused capacity run" }
                interruptTouch(); profiling = true
                mutable.update { it.copy(rendererBenchmark = RendererBenchmarkStatus(active = true, runId = runId,
                    phase = "preparing", totalGroups = 1, runKind = RendererRunKind.RESIDENT_CAPACITY)) }
                launched = true
                benchmarkJob = scope.launch {
                    var journalSaved = false
                    var recipe: ResidentCapacityPoseRecipe? = null
                    var reuseRestored = buildJsonObject { put("available",false) }
                    val events = ResidentCapacitySoftwareEvents(runId)
                    val backend = object : ResidentCapacityBackend {
                        override suspend fun prepare(): JsonObject {
                            withContext(Dispatchers.IO) {
                                verifyResidentSourcePins(binding.activation) { if (benchmarkRunCancelled(runId)) throw BenchmarkCancelled() }
                                checkNotNull(benchmarkRecoveryStore).save(saved)
                            }
                            pendingBenchmarkRecovery = saved; journalSaved = true
                            if (benchmarkRunCancelled(runId)) throw BenchmarkCancelled()
                            val budget = arm.budget
                            if (currentViewReuse) {
                                check(NativeBridge.benchmarkViewportReuseConfigure(engine,runId,checkNotNull(config.viewportReuseCandidate).name)) {
                                    "Current-view reuse arm lost its native owner"
                                }
                                NativeBridge.captureModeSet(engine,if(config.viewportReuseDetailed)1 else 0)
                                currentViewReuseSampling=!config.viewportReuseParity;currentViewReuseFrameCount=0
                            } else {
                                check(NativeBridge.residentGeometryReset(engine, arm.expectedCatalogGeneration, budget.cells,
                                    budget.bytes, budget.loadingBytes, arm.cacheMode == ResidentCapacityCacheMode.EMPTY_GEOMETRY)) {
                                    "Budgetarm benötigt zuerst freigegebene Geometrie/Pins; Bestand unverändert"
                                }
                                residentOwner.budget(budget); changedResidency(residencyStatus())
                                residentCameraAdmission = null
                                publishResidentStart(admitted)
                                residentLastStep = null
                                invalidateSourcePresentation()
                            }
                            capacitySamplingRunId = runId
                            capacitySoftwareEvents = events; capacityFullStamp = 0; capacityCancelRequestedNs.set(0)
                            profiling = false
                            mutable.update { it.copy(rendererBenchmark = it.rendererBenchmark.copy(phase = "sampling", liveInputEnabled = true,
                                message = "Resident-Budgetarm; normale Kartenbewegung, Display nicht gemessen")) }
                            if (config.capacityRecipeId != null) {
                                val frozen = capacityAuthority()
                                recipe = if(currentViewReuse) ResidentCapacityPoseRecipe.currentViewReuse(saved.viewport,arm.durationMs,frozen)
                                    else ResidentCapacityPoseRecipe.currentViewportV1(mutable.value.viewport, arm.durationMs, frozen)
                                capacityRecipeJob = benchmarkWatchScope.launch {
                                    checkNotNull(recipe).run(propose = { step ->
                                        check(capacityResidentWorkAllowed() && surfaceRevision == surfaceEpoch) { "Recipe authority revoked" }
                                        val target=submitCameraViewport(step.viewport,0,cameraClock.now())
                                        fun observe() = captureResidentRecipeCameraReceipt(cameraLock, target, step.viewport, frozen,
                                            { cameraControlResult(target, it) }, { mutable.value }, { requestedCameraRevision },
                                            { state, revision -> capacityAuthority(capturedState = state, capturedRequestedRevision = revision) },
                                            { capacityResidentWorkAllowed() && surfaceRevision == surfaceEpoch &&
                                                !surfaceCommandPending.get() && !benchmarkRunCancelled(runId) })
                                        var observation: ResidentRecipeCameraReceipt? = null
                                        val acceptedWithinDeadline = withTimeoutOrNull(250) {
                                            var current = observe()
                                            observation = current
                                            while (!current.accepted && !current.terminal) {
                                                delay(1)
                                                current = observe()
                                                observation = current
                                            }
                                            currentCoroutineContext().ensureActive()
                                            current.accepted
                                        } == true
                                        val response = checkNotNull(observation) { "No camera observation before diagnostic deadline" }
                                            .response(acceptedWithinDeadline)
                                        if (parity != null) {
                                            if (!response.getValue("accepted").jsonPrimitive.boolean) {
                                                parity.fail("Parity pose was not accepted")
                                                signalBenchmarkCancel(runId, "Parity pose was not accepted")
                                                error("Parity pose was not accepted")
                                            }
                                            captureViewportReuseParity(runId, step,
                                                response.getValue("authority").jsonObject.getValue("cameraRevision").jsonPrimitive.long,
                                                checkNotNull(recipe), frozen, surfaceEpoch, parity)
                                        }
                                        response
                                    })
                                }
                            }
                            queueRender()
                            return buildJsonObject {
                                put("runId", runId); put("runKind", "RESIDENT_CAPACITY")
                                put("appVersion", appVersion); put("sourceCommit", sourceCommit)
                                put("catalogGeneration", arm.expectedCatalogGeneration); put("sourceGeneration", arm.expectedSourceGeneration)
                                put("catalogSourceCount", binding.activation.catalog.sources.size)
                                put("sourcePins", JsonArray(binding.activation.catalog.sources.map { source -> buildJsonObject {
                                    put("name", source.name); put("sourceKey", source.sourceKey); put("edition", source.edition); put("update", source.update)
                                } }))
                                put("cells", budget.cells); put("chargedByteBudget", budget.bytes); put("loadingByteReservation", budget.loadingBytes)
                                put("cacheMode", arm.cacheMode.name)
                                put("nativeCounterEpoch", if(currentViewReuse) "unchanged running catalog; counters are cumulative and samples are polling" else "geometry-budget-reset for this arm; samples are polling")
                                put("cacheScope", if (arm.cacheMode == ResidentCapacityCacheMode.EMPTY_GEOMETRY) "verified empty geometry; prepared files/OS caches unchanged" else "retained current geometry within arm")
                                put("inputRecipe", config.capacityRecipeId ?: "manual normal camera input")
                                if(currentViewReuse) {
                                    put("viewportReuse",config.viewportReuseCandidate!!.name);put("detailed",config.viewportReuseDetailed)
                                    put("parity",config.viewportReuseParity);put("timingComparable",!config.viewportReuseParity)
                                    put("viewportReuseDiagnostic",Json.parseToJsonElement(NativeBridge.benchmarkViewportReuseRead(engine,runId)))
                                    put("geometryReset",false);put("catalogSwap",false);put("automaticActivation",false)
                                    put("processStart", "NOT_MEASURED; capture process start externally before this post-ready diagnostic")
                                    put("coordinatePrivacy","Current private camera/source pins; keep this report outside public Git")
                                }
                                put("bindingScope", "current entry catalog only; no automatic Florida/source switch")
                                put("recipe", recipe?.snapshot() ?: JsonNull)
                            }
                        }
                        override fun checkReady() {
                            if (benchmarkRunCancelled(runId)) throw BenchmarkCancelled()
                            check(!closed && engine != 0L && surfaceRevision == surfaceEpoch && surfaceGeometry != null &&
                                capacityResidentWorkAllowed()) { "Capacityowner/Katalog/Budget/Surface verändert" }
                            if(currentViewReuse) check(mutable.value.presentation==saved.presentation && benchmarkUserSettings(activeSettings,
                                Json.parseToJsonElement(NativeBridge.statusRead(engine)).jsonObject)==saved.settings) {
                                "Current-view presentation changed; no longer a matched measurement"
                            }
                        }
                        override suspend fun observe() = residentCapacityObservation()
                        override fun end(): JsonObject {
                            currentViewReuseSampling=false
                            capacitySamplingRunId = null; profiling = true; interruptTouch()
                            capacityRecipeJob?.cancel(); capacityRecipeJob = null
                            val endedAt = System.nanoTime(); val endAuthority = capacityAuthority()
                            events.recordInstant("SAMPLING_ENDED", endedAt, endAuthority)
                            capacityCancelRequestedNs.get().takeIf { it != 0L }?.let { cancelledAt ->
                                events.recordDuration("CANCEL_TO_SAMPLING_END", cancelledAt, endedAt, endAuthority,
                                    buildJsonObject { put("endpoint", "cancel request to native-owner sampling end; restoration/display excluded") })
                            }
                            capacitySoftwareEvents = null
                            mutable.update { it.copy(rendererBenchmark = it.rendererBenchmark.copy(phase = "restoring", liveInputEnabled = false)) }
                            return buildJsonObject {
                                put("samplingEndpoint", "owner polling; software work durations separately tagged")
                                put("softwareEvents", events.snapshot()); put("recipe", recipe?.snapshot() ?: JsonNull)
                                put("renderDurationEndpoint", "renderNative owner call; not GPU/physical display")
                                if(currentViewReuse) {
                                    put("nativeFrameReportLimit",256);put("nativeFrameReportsOmitted",(currentViewReuseFrameCount-256).coerceAtLeast(0))
                                    put("requiredInputPathValid",recipe?.snapshot()?.get("comparableRealizedPath") ?: JsonPrimitive(false))
                                    if (parity != null) {
                                        val proof = parity.snapshot()
                                        put("viewportReuseParity", proof)
                                        put("requiredInputPathValid", proof.getValue("complete").jsonPrimitive.boolean &&
                                            recipe?.snapshot()?.get("comparableRealizedPath")?.jsonPrimitive?.booleanOrNull == true)
                                    }
                                }
                            }
                        }
                        override suspend fun restore(): Boolean {
                            benchmarkRuns.beginRecovery(runId, android.os.SystemClock.elapsedRealtime())
                            mutable.update { it.copy(rendererBenchmarkRecoveryPending = journalSaved) }
                            NativeBridge.benchmarkRunEnd(engine, runId)
                            try {
                                if(currentViewReuse) {
                                    check(NativeBridge.benchmarkViewportReuseRestore(engine,runId)) { "Current-view reuse flags not restored" }
                                    reuseRestored=Json.parseToJsonElement(NativeBridge.benchmarkViewportReuseRead(engine,runId)).jsonObject
                                    NativeBridge.captureModeSet(engine,0)
                                }
                                return !journalSaved || restoreBenchmarkUserState(saved)
                            }
                            finally { benchmarkRuns.endRecovery(runId, android.os.SystemClock.elapsedRealtime()) }
                        }
                    }
                    try {
                        val result = ResidentCapacityMeasurement(backend, android.os.SystemClock::elapsedRealtime).run(arm.durationMs)
                        val delivered = buildJsonObject { result.forEach { (key, value) -> put(key, value) }; put("measurementControl", benchmarkControlJson(runId))
                            if(currentViewReuse)put("viewportReuseRestoration",reuseRestored) }
                        lastCaptureReport = delivered.toString()
                        val report = withContext(reportIoDispatcher) { checkNotNull(reportStore).saveBenchmark(runId, lastCaptureReport) }
                        mutable.update { it.copy(rendererBenchmark = it.rendererBenchmark.copy(active = false,
                            phase = result.getValue("outcome").jsonPrimitive.content,
                            completedGroups = 1, reportFile = report,
                            restored = result.getValue("restored").jsonPrimitive.boolean,
                            message = listOf(result.getValue("error").jsonPrimitive.content, result.getValue("restorationError").jsonPrimitive.content)
                                .filter(String::isNotBlank).joinToString("; ").ifEmpty { "Budgetarm gespeichert; Quellen und Konfiguration wiederhergestellt" })) }
                    } catch (failure: Exception) {
                        mutable.update { it.copy(rendererBenchmark = it.rendererBenchmark.copy(active = false, phase = "failed",
                            message = failure.message ?: "Capacityreport konnte nicht gespeichert werden")) }
                    } finally {
                        capacitySamplingRunId = null; capacitySoftwareEvents = null;currentViewReuseSampling=false
                        capacityRecipeJob?.cancel(); capacityRecipeJob = null; profiling = false
                        if (pendingBenchmarkRecovery != null || benchmarkRecoveryError != null)
                            mutable.update { it.copy(rendererBenchmarkRecoveryPending = true) }
                        NativeBridge.benchmarkRunEnd(engine, runId)
                        if(currentViewReuse) {NativeBridge.benchmarkViewportReuseRestore(engine,runId);NativeBridge.captureModeSet(engine,0)}
                        benchmarkRuns.finish(runId, mutable.value.rendererBenchmark); watchdog.cancel()
                        if (!closed) queueRender()
                    }
                }
                EngineResult(true, runId)
            }
            if (!accepted.success) { benchmarkRuns.finish(runId, RendererBenchmarkStatus(phase = "rejected", message = accepted.message)); watchdog.cancel() }
            return accepted
        } catch (failure: Exception) {
            if (!launched) {
                withContext(NonCancellable + dispatcher) { if (engine != 0L) NativeBridge.benchmarkRunEnd(engine, runId) }
                benchmarkRuns.finish(runId, RendererBenchmarkStatus(phase = "rejected", message = failure.message.orEmpty())); watchdog.cancel()
            }
            return EngineResult(false, failure.message ?: "Capacityarm abgewiesen")
        }
    }

    /** New current-resident runs acquire ownership only after an atomic actor-idle boundary. */
    private suspend fun admitCurrentResidentLive(config: RendererBenchmarkConfig): BenchmarkRunRegistry.Admission {
        // Tokens are never evicted: a known token can only reuse/reject, never activate a new run.
        if (benchmarkRuns.status(mutable.value.rendererBenchmark, config.clientRunToken).runId.isNotBlank())
            return benchmarkRuns.admit(config, android.os.SystemClock.elapsedRealtime())
        return withContext(dispatcher) {
            val snapshot = currentResidentLiveSnapshots.get(checkNotNull(config.currentResidentLive).snapshotId)
                ?: return@withContext BenchmarkRunRegistry.Admission.Rejected("Current-view snapshot unavailable")
            val preparedId = java.util.UUID.randomUUID().toString()
            val transitionOwner = Any()
            val preparedStatus = RendererBenchmarkStatus(active = true, runId = preparedId, phase = "accepted",
                runKind = RendererRunKind.LIVE_INPUT, liveReplay = config.liveReplay,
                liveReplayRecipe = config.liveReplayRecipe, liveRepaintTransforms = config.liveRepaintTransforms)
            var admission: BenchmarkRunRegistry.Admission = BenchmarkRunRegistry.Admission.Rejected("Camera work is still pending")
            synchronized(cameraLock) {
                if (closed || engine == 0L || profiling || captureBuffer != null || presentationPreparing.get() > 0 ||
                    transformTouchActive.get() || touchFrames.held.get() || !currentResidentSnapshotCurrent(snapshot))
                    return@synchronized
                // No JNI, JSON, UUID, IO or observer callback occurs under the actor's goalLock.
                val owned = cameraControl.admitMeasurementWhenIdle(transitionOwner) {
                    admission = benchmarkRuns.admit(config, android.os.SystemClock.elapsedRealtime(), preparedId)
                    admission is BenchmarkRunRegistry.Admission.Started
                }
                if (owned) try {
                    // Actor's token covers callers that passed their engine precheck before activation.
                    mutable.update { it.copy(rendererBenchmark = preparedStatus) }
                } finally { check(cameraControl.releaseMeasurementAdmission(transitionOwner)) }
            }
            admission
        }
    }

    /** LIVE_INPUT shares run-token ownership, native cancellation, private recovery and saved report delivery. */
    private suspend fun startLiveInputMeasurement(config: RendererBenchmarkConfig): EngineResult {
        if (mutable.value.residentCatalogActive && config.currentResidentLive == null) return EngineResult(false, "Resident-Katalog kann vom Messjournal noch nicht wiederhergestellt werden")
        if (bufferTransformMode != ChartBufferTransformMode.OFF && rendererBenchmarkStatus(config.clientRunToken).runId.isBlank())
            return EngineResult(false, "Set T0_MODE OFF before starting the run-owned LIVE_INPUT mode")
        val admission = if (config.currentResidentLive != null) admitCurrentResidentLive(config)
            else benchmarkRuns.admit(config, android.os.SystemClock.elapsedRealtime())
        val run = when (admission) {
            is BenchmarkRunRegistry.Admission.Reused -> return EngineResult(true, admission.run.id)
            is BenchmarkRunRegistry.Admission.Rejected -> return EngineResult(false, admission.message)
            is BenchmarkRunRegistry.Admission.Started -> admission.run
        }
        val runId = run.id
        val watchdog = benchmarkWatchScope.launch {
            while (benchmarkRuns.isActive(runId)) {
                benchmarkRuns.tick(android.os.SystemClock.elapsedRealtime())?.let { signalBenchmarkCancel(it.id, "device deadline exceeded") }
                delay(100)
            }
        }
        var launched = false
        try {
            val accepted = withContext(dispatcher) {
                val current = config.currentResidentLive?.let { checkNotNull(currentResidentLiveSnapshots.get(it.snapshotId)) {
                    "Current-view snapshot unavailable; capture the exact current owner first"
                } }
                fun blocker(): String? = if (closed || engine == 0L) "Renderer unavailable" else if (current != null) {
                    if (!residentOwner.active || profiling || captureBuffer != null || presentationPreparing.get() > 0 ||
                        transformTouchActive.get() || touchFrames.held.get() || mutable.value.rendererBenchmarkRecoveryPending ||
                        !currentResidentSnapshotCurrent(current)) "Current-view snapshot authority changed" else null
                } else if (mutable.value.residentCatalogActive) "Resident-Katalog kann vom Messjournal noch nicht wiederhergestellt werden" else
                    rendererBenchmarkStartBlocker(mutable.value, profiling || captureBuffer != null || presentationPreparing.get() > 0 || transformTouchActive.get() || touchFrames.held.get())
                blocker()?.let { return@withContext EngineResult(false, it) }
                lastReportWrite?.join()
                blocker()?.let { return@withContext EngineResult(false, it) }
                if (!touchTimestampSupported) return@withContext EngineResult(false, "LIVE_INPUT requires exact tagged-buffer support")
                if (current == null) { applyPendingViewport(); applyPendingOverlays(); refresh() }
                val geometry = checkNotNull(surfaceGeometry); val surfaceEpoch = surfaceRevision
                val saved = current?.saved?.copy(runId = runId, currentViewOnly = true) ?: BenchmarkUserState(runId, loadedChartId, loadedChartPaths.toList(), mutable.value.viewport,
                    mutable.value.presentation, benchmarkUserSettings(activeSettings, Json.parseToJsonElement(NativeBridge.statusRead(engine)).jsonObject), activeOverlays,
                    backgroundPaths = loadedBackgroundPaths.toList())
                val paths = if (current == null) bundledBenchmarkPaths(dataDirectory) else emptyList()
                current?.let { currentResidentLiveSnapshots.clear(it.id) }
                if (benchmarkRunCancelled(runId)) return@withContext EngineResult(false, "Run cancelled before preparation")
                if (!NativeBridge.benchmarkRunBegin(engine, runId, run.deadlineMs)) return@withContext EngineResult(false, "Native run refused")
                if (!NativeBridge.benchmarkPreparationConfigure(engine, runId, 1)) {
                    NativeBridge.benchmarkRunEnd(engine, runId); return@withContext EngineResult(false, "Preparation controls refused")
                }
                profiling = true
                mutable.update { it.copy(rendererBenchmark = RendererBenchmarkStatus(active = true, runId = runId, phase = "preparing",
                    totalGroups = 1, runKind = RendererRunKind.LIVE_INPUT, liveReplay = config.liveReplay,
                    liveReplayRecipe = config.liveReplayRecipe, liveRepaintTransforms = config.liveRepaintTransforms)) }
                launched = true
                benchmarkJob = scope.launch {
                    var journalSaved = false
                    var liveCaptureStarted = false
                    var normalAbortConfigured = false
                    var currentLease: CurrentResidentLiveLease? = null
                    val parity = if (config.currentLiveParityOnly) CurrentLiveFullParityEvidence(runId,
                        java.util.UUID.randomUUID().toString(), config.normalRenderAbort) else null
                    var parityNativeConfigured = false
                    var parityRecipe: ResidentCapacityPoseRecipe? = null
                    fun endParityCapture() {
                        if (parityNativeConfigured) {
                            check(NativeBridge.benchmarkCurrentLiveParityEnd(engine, runId, checkNotNull(parity).captureId)) {
                                "Current-live parity capture cleanup not confirmed"
                            }
                            parityNativeConfigured = false
                        }
                    }
                    var normalAbortRestoration = buildJsonObject { put("configured", false) }
                    val startedUtc = java.time.Instant.now().toString()
                    val observations = RendererLiveInputBuffer(runId, replayRecipe = config.liveReplayRecipe,
                        repaintTransforms = config.liveRepaintTransforms, currentPrivateView = current != null)
                    val backend = object : RendererLiveInputBackend {
                        override suspend fun prepare(): JsonObject {
                            withContext(Dispatchers.IO) {
                                current?.let { verifyResidentSourcePins(checkNotNull(it.saved.residentActivation)) {
                                    if (benchmarkRunCancelled(runId)) throw BenchmarkCancelled()
                                } }
                                checkNotNull(benchmarkRecoveryStore).save(saved)
                            }
                            pendingBenchmarkRecovery = saved; journalSaved = true
                            if (benchmarkRunCancelled(runId)) throw BenchmarkCancelled()
                            val dataset = prepareLiveInputSource(current, prepareCurrent = { frozen ->
                                check(currentResidentSnapshotCurrent(frozen)) { "SOURCE_FREEZE_LOST before preparation" }
                                buildJsonObject { put("scope", "CURRENT_RESIDENT_FROZEN_DATA"); put("private", true)
                                    put("catalogGeneration", frozen.identity.catalog.generation)
                                    put("catalogSourceCount", frozen.identity.catalog.sources.size)
                                    put("geometryReset", false); put("catalogSwitch", false) }
                            }, preparePublicBoston = {
                                val publicDataset = withContext(Dispatchers.IO) { bundledBenchmarkDataset(paths) }
                                if (benchmarkRunCancelled(runId)) throw BenchmarkCancelled()
                                check(openNativeCharts(paths.toTypedArray())) { "Public Boston charts could not be loaded" }
                                loadedChartId = "noaa-boston-harbour"; loadedChartPaths = paths; loadedBackgroundPaths = emptyList()
                                activeSettings = S52Settings(ChartPalette.DAY, 3.0)
                                NativeBridge.s52SettingsSet(engine, activeSettings.palette.name, activeSettings.safetyContourMetres); replaceObjectFilters(activeSettings.objectFilters)
                                benchmarkViewport(BostonBenchmarkRecipe.camera(false, 0, geometry))
                                val markerPoint = geometry.toFrame(rendererDiagnosticMarkerPoint(geometry))
                                val markerGeo = NativeBridge.unprojectCoordinate(engine, markerPoint.x, markerPoint.y)
                                NativeBridge.diagnosticMarkerSet(engine, observations.diagnosticMarkerEnabled, markerGeo[0], markerGeo[1])
                                benchmarkPresentation(ChartPresentation("lagoon-sculpt", ChartSymbolSet.R04, false))
                                replaceOverlays(ChartOverlays()); replaceObjectFilters(ChartObjectFilters())
                                publicDataset
                            })
                            bufferTransformMode = config.liveMode
                            interruptTouch()
                            mutable.update { it.copy(bufferTransformMode = config.liveMode, rendererBenchmark = it.rendererBenchmark.copy(phase = "warming")) }
                            liveInputBuffer = observations
                            captureMetadata = buildJsonObject {
                                captureMetadata().forEach { (k, v) -> put(k, v) }; put("runId", runId); put("dataset", dataset)
                            }
                            previousFrameEnd = 0; lastCapturedInputNanos = 0; latestInputAt.set(0); appliedInputAt = 0
                            captureBuffer = RendererCaptureBuffer(RendererCaptureConfig(60, 1200, false), System.nanoTime())
                            liveCaptureStarted = true
                            activeUiCapture = captureBuffer?.ui
                            mutable.update { it.copy(rendererCapture = RendererCaptureStatus(active = true)) }
                            if (current != null) {
                                currentLease = CurrentResidentLiveLease(runId, current, checkNotNull(captureBuffer))
                                currentResidentLiveLease = currentLease
                            }
                            NativeBridge.captureModeSet(engine, 0)
                            if (config.normalRenderAbort) {
                                check(NativeBridge.benchmarkNormalAbortConfigure(engine, runId, true)) { "Normal-abort run configuration refused" }
                                normalAbortConfigured = true
                                val initial = normalAbortReport(runId)
                                check(initial["available"]?.jsonPrimitive?.booleanOrNull == true &&
                                    initial["enabled"]?.jsonPrimitive?.booleanOrNull == true &&
                                    initial["originalEnabled"]?.jsonPrimitive?.booleanOrNull == false) { "Unexpected normal-abort baseline" }
                                synchronized(cameraLock) {
                                    normalAbortRunId = runId; normalAbortFailure = null
                                    normalAbortSource = residentOwner.sourceGeneration; normalAbortSurface = surfaceRevision
                                    normalAbortEnabled = true
                                    check(appliedCameraRevision == requestedCameraRevision) { "Warm native camera not yet applied" }
                                    markNormalRenderApplied(announceNormalRenderAuthority(force = true))
                                    check(normalAbortFailure == null && normalRenderAuthority.applied() != null) { "Initial normal render authority unavailable" }
                                }
                            }
                            if (parity != null) {
                                check(diagnosticPermission()) { "DUMP permission revoked before current-live parity" }
                                val frozen = checkNotNull(current).identity
                                check(NativeBridge.benchmarkCurrentLiveParityBegin(engine, runId, parity.captureId,
                                    config.normalRenderAbort, frozen.catalog.generation, frozen.nativeContent, frozen.nativeData,
                                    frozen.source, frozen.surface, bufferRegistry.generation())) { "Current-live parity capture authority refused" }
                                parityNativeConfigured = true
                                parityRecipe = ResidentCapacityPoseRecipe.currentViewReuse(saved.viewport, 15_000, capacityAuthority())
                            }
                            renderAndRefresh()
                            // Yielding below allows the normal single GL-owner queue and real TextureView callbacks to run.
                            profiling = false; queueRender()
                            return buildJsonObject {
                                captureMetadata.forEach { (k, v) -> put(k, v) }
                                put("mode", config.liveMode.name); put("liveDurationMs", config.liveDurationMs)
                                put("normalRenderAbort", config.normalRenderAbort)
                                put("currentLiveParityOnly", config.currentLiveParityOnly)
                                if (config.currentLiveParityOnly) put("timingComparable", false)
                                put("normalRenderAbortDiagnostic", if (normalAbortConfigured) normalAbortReport(runId) else
                                    buildJsonObject { put("configured", false); put("defaultEnabled", false) })
                                put("replayEnabled", config.liveReplay); put("replaySha256", RendererTouchReplay.sha256(config.liveReplayRecipe))
                                put("replayRecipe", config.liveReplayRecipe.name)
                                put("repaintTransforms", config.liveRepaintTransforms)
                                put("contentPresentation", when (config.liveMode) {
                                    ChartBufferTransformMode.TRANSFORM_AREAS_FIRST -> "base areas including ready effect/land then same-camera full details"
                                    ChartBufferTransformMode.TRANSFORM_AREAS_REUSE -> "current base areas plus immutable reprojected old full details during touch; current full after UP"
                                    ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS -> "bounded in-motion full checkpoints; one current-areas refresh before a due checkpoint when geometric buffer gaps exceed five percent; detail debt continues after area ACK"
                                    ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_FIXED -> "fixed 600ms/six-partials in-motion full checkpoint policy; no coverage refresh loan"
                                    ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_PADDED_CONTROL -> "bounded in-motion full checkpoints and honest central filled-rectangle gap refresh; visible FBO centered 1:1 in shared padded queue"
                                    ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE -> "same checkpoint/gap policy with capped 1.25-axis prepared raster at unchanged pixels per metre; TextureView retains and centrally crops reserve pixels"
                                    else -> "full image"
                                })
                                put("stagePixelProbe", if (observations.diagnosticMarkerEnabled) "native window top-right 24x12: orange BASE, cyan FULL; public only" else "OFF")
                                put("diagnosticMarker", if (observations.diagnosticMarkerEnabled) "native magenta cross; target green sibling ring; fixed public world point offset from gesture center" else "OFF")
                                put("withheldWorkScope", "same full draw and final blit; glFlush replaces swap; no equivalent swap/backpressure claim")
                                put("inputPath", when {
                                    config.currentLiveParityOnly -> "CAMERA_PROPOSALS_NOT_TOUCH_REPLAY"
                                    config.liveReplay -> "VIEW_INJECTED actual ChartSurfaceView Touch path"
                                    else -> "PLATFORM_DISPATCH; physical origin not verified"
                                })
                                put("surface", buildJsonObject { put("width", geometry.surfaceWidth); put("height", geometry.surfaceHeight) })
                                if (current == null) put("publicPresetViewport", BostonBenchmarkRecipe.cameraJson(BostonBenchmarkRecipe.camera(false, 0, geometry)))
                                else { put("currentResidentSnapshotId", current.id); put("coordinatePrivacy", "PRIVATE_CURRENT_VIEW")
                                    put("sourceGeneration", current.identity.source); put("surfaceGeneration", current.identity.surface)
                                    put("contentGeneration", current.identity.content); put("nativeDataGeneration", current.identity.nativeData)
                                    put("nativeContentRevision", current.identity.nativeContent)
                                    put("sourceFreezeScope", "Immutable current resident data interval; loading changes abort") }
                                put("windowPresentCorrelation", "NOT_MEASURED")
                                put("presentationSlots", if (pairedPresentation) 2 else 1)
                                put("presentationReleaseEndpoint", if (pairedPresentation) "API29 hardware frame commit; not physical display" else "legacy timestamp ACK")
                            }
                        }
                        override fun checkReady() {
                            if (benchmarkRunCancelled(runId)) throw BenchmarkCancelled()
                            check(normalAbortFailure == null) { normalAbortFailure ?: "Normal render authority failed" }
                            if (config.currentLiveParityOnly) check(diagnosticPermission()) { "DUMP permission revoked" }
                            check(!closed && engine != 0L && surfaceGeometry == geometry && surfaceRevision == surfaceEpoch) { "Surface/lifecycle changed" }
                            check(captureBuffer != null && bufferTransformMode == config.liveMode) { "Live capture changed" }
                            if (current == null) check(loadedChartPaths == paths) { "Public source changed" }
                            else {
                                check(currentResidentLiveNativeValid()) { "SOURCE_FREEZE_LOST" }
                                check(currentResidentLiveWorkAllowed()) { "Current resident live lease lost" }
                            }
                            check(!observations.replayState.startsWith("FAILED")) { observations.replayState }
                        }
                        override fun baseReady(): Boolean = mutable.value.bufferTransformBaseReady && !mutable.value.presentationPending &&
                            observations.uiBasisStamp == bufferRegistry.observedStamp() &&
                            bufferRegistry.lookup(bufferRegistry.observedStamp())?.let { it.producerCompleted && it.stage == ChartContentStage.FULL && it.cameraRevision == requestedCameraRevision } == true
                        override fun begin() {
                            currentLease?.phase = "live_input"
                            observations.startLive()
                            mutable.update { it.copy(rendererBenchmark = it.rendererBenchmark.copy(phase = "live_input", liveInputEnabled = true,
                                message = if (current == null) "Öffentlicher T0-Inputlauf; Window/Present nicht gemessen" else "Privater aktueller Kartenbestand; Window/Present nicht gemessen")) }
                        }
                        override fun checkFinished() {
                            check(currentResidentLiveNativeValid()) { "SOURCE_FREEZE_LOST" }
                            check(!config.liveReplay || observations.replayState == "COMPLETED") { "Pinned replay did not finish: ${observations.replayState}" }
                            if (parity == null) observations.liveValidityError()?.let { error(it) }
                            else check(parity.snapshot().getValue("complete").jsonPrimitive.boolean &&
                                checkNotNull(parityRecipe).snapshot().getValue("comparableRealizedPath").jsonPrimitive.boolean) {
                                "Current-live parity set not complete"
                            }
                        }
                        override fun end(): JsonObject {
                            profiling = true
                            if (currentLease != null && !benchmarkRunCancelled(runId)) currentResidentLiveNativeValid()
                            try { endParityCapture() } catch (failure: Exception) {
                                // Preserve partial frames and the failed cleanup fact in the bounded report;
                                // restore() still retries only this exact capture before ordinary user-state recovery.
                                parity?.fail(failure.message ?: "Current-live parity capture cleanup not confirmed")
                            }
                            currentLease?.phase = "restoring"
                            if (currentResidentLiveLease === currentLease) currentResidentLiveLease = null
                            if (current == null) NativeBridge.diagnosticMarkerSet(engine, false, 0.0, 0.0)
                            mutable.update { it.copy(rendererBenchmark = it.rendererBenchmark.copy(liveInputEnabled = false, phase = "restoring")) }
                            observations.finishLive()
                            val raw = observations.finishReport()
                            if (current != null) benchmarkRuns.sealMeasurementForRecovery(runId, android.os.SystemClock.elapsedRealtime())
                                ?.let { signalBenchmarkCancel(it.id, "device deadline exceeded") }
                            val finalCurrentError = if (current != null) currentResidentLiveCompletionError(
                                benchmarkRunCancelled(runId), benchmarkRuns.control(runId)?.cancelReason,
                                currentLease?.failure, if (parity == null) observations.liveValidityError() else
                                    parity.snapshot().getValue("error").jsonPrimitive.content.takeIf(String::isNotBlank)) else ""
                            retireCameraOrigins(observations)
                            liveInputBuffer = null
                            finishCapture("live input ended")
                            interruptTouch(); bufferTransformMode = ChartBufferTransformMode.OFF
                            mutable.update { it.copy(bufferTransformMode = ChartBufferTransformMode.OFF) }
                            return buildJsonObject {
                                put("input", raw)
                                if (parity != null) put("currentLiveFullParity", buildJsonObject {
                                    parity.snapshot().forEach { (key, value) -> put(key, value) }
                                    put("recipe", parityRecipe?.snapshot() ?: JsonNull)
                                })
                                if (current != null) {
                                    put("currentResidentFreeze", buildJsonObject { put("valid", currentLease?.failure == null)
                                        put("reason", currentLease?.failure?.let(::JsonPrimitive) ?: JsonNull) })
                                    put("completionError", finalCurrentError)
                                }
                                put("nativeCapture", if (liveCaptureStarted && lastCaptureReport.isNotBlank()) Json.parseToJsonElement(lastCaptureReport) else JsonNull)
                                if (normalAbortConfigured) put("normalRenderAbort", normalAbortReport(runId))
                            }
                        }
                        override suspend fun restore(): Boolean {
                            benchmarkRuns.beginRecovery(runId, android.os.SystemClock.elapsedRealtime())
                            var captureCleanupFailure: Exception? = null
                            try { endParityCapture() } catch (failure: Exception) { captureCleanupFailure = failure }
                            NativeBridge.benchmarkRunEnd(engine, runId)
                            try {
                                if (normalAbortConfigured) {
                                    normalAbortRestoration = restoreNormalAbortControls(runId)
                                }
                                check(NativeBridge.benchmarkPreparationRestore(engine, runId)) { "Preparation controls not restored" }
                                val restored = !journalSaved || restoreBenchmarkUserState(saved)
                                captureCleanupFailure?.let { throw it }
                                return restored
                            } finally { benchmarkRuns.endRecovery(runId, android.os.SystemClock.elapsedRealtime()) }
                        }
                    }
                    try {
                        val measurement: (suspend () -> Unit)? = if (parity == null) null else ({
                            try { runCurrentLiveFullParity(checkNotNull(currentLease), parity,
                                checkNotNull(parityRecipe), config.normalRenderAbort, backend::checkReady) }
                            catch (failure: Exception) {
                                parity.fail(failure.message ?: "Current-live parity failed")
                                signalBenchmarkCancel(runId, "Current-live parity failed")
                                throw failure
                            }
                        })
                        val result = RendererLiveInputRunner(backend, android.os.SystemClock::elapsedRealtime)
                            .run(config.liveDurationMs, measurement)
                        val delivered = buildJsonObject {
                            result.forEach { (k, v) -> put(k, v) }; put("runId", runId); put("runKind", "LIVE_INPUT")
                            put("build", buildJsonObject { put("appVersion", appVersion); put("sourceCommit", sourceCommit) })
                            put("startedUtc", startedUtc); put("finishedUtc", java.time.Instant.now().toString())
                            put("measurementControl", benchmarkControlJson(runId))
                            put("normalRenderAbortRestoration", normalAbortRestoration)
                        }
                        lastCaptureReport = delivered.toString()
                        val rawReports = pendingProfileReports.toList().also { pendingProfileReports.clear() }
                        val delivery = withContext(reportIoDispatcher) {
                            val store = checkNotNull(reportStore)
                            saveBenchmarkReports(runId, delivered.toString(), rawReports, store::save, store::saveBenchmark)
                        }
                        mutable.update { it.copy(rendererBenchmark = it.rendererBenchmark.copy(active = false,
                            phase = result.getValue("outcome").jsonPrimitive.content, reportFile = delivery.file,
                            restored = result.getValue("restored").jsonPrimitive.boolean, liveInputEnabled = false,
                            message = listOf(result.getValue("error").jsonPrimitive.content, result.getValue("restorationError").jsonPrimitive.content,
                                delivery.warning).filter(String::isNotBlank).joinToString("; ").ifEmpty {
                                    "T0-Eingabelauf abgeschlossen; ursprüngliche Ansicht wiederhergestellt. Window/Present nicht gemessen." })) }
                    } catch (failure: Exception) {
                        mutable.update { it.copy(rendererBenchmark = it.rendererBenchmark.copy(active = false, phase = "failed", liveInputEnabled = false,
                            message = "Live report delivery failed: ${failure.message}")) }
                    } finally {
                        currentLease?.phase = "restoring"
                        if (currentResidentLiveLease === currentLease) currentResidentLiveLease = null
                        retireCameraOrigins(observations)
                        liveInputBuffer = null; profiling = false
                        if (pendingBenchmarkRecovery != null || benchmarkRecoveryError != null) mutable.update { it.copy(rendererBenchmarkRecoveryPending = true) }
                        try {
                            if (engine != 0L) {
                                try { endParityCapture() } catch (failure: Exception) {
                                    benchmarkRecoveryError = failure.message ?: "Current-live parity capture cleanup not confirmed"
                                    mutable.update { it.copy(rendererBenchmarkRecoveryPending = true,
                                        rendererBenchmark = it.rendererBenchmark.copy(phase = "failed", restored = false,
                                            message = benchmarkRecoveryError.orEmpty())) }
                                }
                                NativeBridge.benchmarkRunEnd(engine, runId)
                                if (normalAbortConfigured) try { restoreNormalAbortControls(runId) }
                                catch (failure: Exception) {
                                    benchmarkRecoveryError = failure.message ?: "Normal-abort restoration not confirmed"
                                    mutable.update { it.copy(rendererBenchmarkRecoveryPending = true,
                                        rendererBenchmark = it.rendererBenchmark.copy(phase = "failed", restored = false,
                                            message = benchmarkRecoveryError.orEmpty())) }
                                }
                                NativeBridge.benchmarkPreparationRestore(engine, runId)
                            }
                        } finally {
                            benchmarkRuns.finish(runId, mutable.value.rendererBenchmark); watchdog.cancel()
                            if (!closed && !normalAbortEnabled) queueRender()
                        }
                    }
                }
                EngineResult(true, runId)
            }
            if (!accepted.success) {
                val rejected = RendererBenchmarkStatus(runId = runId, phase = "rejected", message = accepted.message, restored = true,
                    runKind = RendererRunKind.LIVE_INPUT)
                benchmarkRuns.finish(runId, rejected); watchdog.cancel()
                if (config.currentResidentLive != null) mutable.update {
                    if (it.rendererBenchmark.runId == runId) it.copy(rendererBenchmark = rejected) else it }
            }
            return accepted
        } catch (failure: Exception) {
            if (!launched) {
                withContext(NonCancellable + dispatcher) { if (engine != 0L) {
                    NativeBridge.benchmarkRunEnd(engine, runId); NativeBridge.benchmarkPreparationRestore(engine, runId)
                } }
                val failed = RendererBenchmarkStatus(runId = runId, phase = "failed", message = failure.message.orEmpty(), restored = true,
                    runKind = RendererRunKind.LIVE_INPUT)
                benchmarkRuns.finish(runId, failed); watchdog.cancel()
                if (config.currentResidentLive != null) mutable.update {
                    if (it.rendererBenchmark.runId == runId) it.copy(rendererBenchmark = failed) else it }
            }
            throw failure
        }
    }

    /** One transaction owns bundled charts, capture, private recovery and report delivery for both recipes. */
    private suspend fun startRendererMeasurement(config: RendererBenchmarkConfig, standard: Boolean = false,
        completion: CompletableDeferred<EngineResult>? = null): EngineResult {
        if (mutable.value.residentCatalogActive) return EngineResult(false, "Resident-Katalog kann vom Messjournal noch nicht wiederhergestellt werden")
        if (bufferTransformMode != ChartBufferTransformMode.OFF && rendererBenchmarkStatus(config.clientRunToken).runId.isBlank())
            return EngineResult(false, "Set T0_MODE OFF before the scripted style/worker benchmark")
        val admission = benchmarkRuns.admit(config, android.os.SystemClock.elapsedRealtime())
        val run = when (admission) {
            is BenchmarkRunRegistry.Admission.Reused -> return EngineResult(true, admission.run.id)
            is BenchmarkRunRegistry.Admission.Rejected -> return EngineResult(false, admission.message)
            is BenchmarkRunRegistry.Admission.Started -> admission.run
        }
        val runId = run.id
        // Starts before waiting for the renderer dispatcher. STATUS/CANCEL remain independent of it.
        val watchdog = benchmarkWatchScope.launch {
            while (benchmarkRuns.isActive(runId)) {
                benchmarkRuns.tick(android.os.SystemClock.elapsedRealtime())?.let { signalBenchmarkCancel(it.id, "device deadline exceeded") }
                delay(100)
            }
        }
        var launched = false
        try {
            val accepted = withContext(dispatcher) {
                fun blocker(): String? = if (mutable.value.residentCatalogActive) "Resident-Katalog kann vom Messjournal noch nicht wiederhergestellt werden"
                    else if (closed || engine == 0L) "Kartenrenderer nicht verfügbar. App erneut öffnen."
                    else rendererBenchmarkStartBlocker(mutable.value, profiling || captureBuffer != null || presentationPreparing.get() > 0)
                blocker()?.let { return@withContext EngineResult(false, it) }
                lastReportWrite?.join()
                blocker()?.let { return@withContext EngineResult(false, it) }
                applyPendingViewport(); applyPendingOverlays(); refresh()
                val previousView = mutable.value.viewport
                val previousPresentation = mutable.value.presentation
                val previousSettings = try { benchmarkUserSettings(activeSettings, Json.parseToJsonElement(NativeBridge.statusRead(engine)).jsonObject) }
                    catch (failure: Exception) { return@withContext EngineResult(false, failure.message ?: "S52-Einstellungen konnten nicht gesichert werden.") }
                val previousOverlays = activeOverlays
                val geometry = checkNotNull(surfaceGeometry)
                val surfaceEpoch = surfaceRevision
                val paths = bundledBenchmarkPaths(dataDirectory)
                val startedUtc = java.time.Instant.now().toString()
                val previousState = BenchmarkUserState(runId, loadedChartId, loadedChartPaths.toList(), previousView,
                    previousPresentation, previousSettings, previousOverlays, backgroundPaths = loadedBackgroundPaths.toList())
                if (benchmarkRunCancelled(runId)) return@withContext EngineResult(false, "Measurement deadline/cancel reached before preparation")
                if (!NativeBridge.benchmarkRunBegin(engine, runId, run.deadlineMs))
                    return@withContext EngineResult(false, "Native run control refused this run")
                if (!NativeBridge.benchmarkPreparationConfigure(engine, runId, config.requestedWorkers)) {
                    NativeBridge.benchmarkRunEnd(engine, runId)
                    return@withContext EngineResult(false, "Native preparation measurement refused this run")
                }
                if (config.viewportReuseCandidate != null && !NativeBridge.benchmarkViewportReuseConfigure(engine, runId, "OFF")) {
                    NativeBridge.benchmarkRunEnd(engine, runId)
                    NativeBridge.benchmarkPreparationRestore(engine, runId)
                    return@withContext EngineResult(false, "Native viewport reuse diagnostic refused this run")
                }
                profiling = true
                val palette = if (standard || config.viewportReuseCandidate != null) previousSettings.palette else ChartPalette.DAY
                val total = config.viewportReuseCandidate?.let {
                    BostonBenchmarkRecipe.viewportReuseCases(config.repetitions, previousPresentation, it).size
                } ?: if (standard) BostonBenchmarkRecipe.standardCases(previousPresentation).size else BostonBenchmarkRecipe.cases(config.repetitions).size
                mutable.update { it.copy(rendererBenchmark = RendererBenchmarkStatus(true, runId, "preparing", totalGroups = total, message = "Boston-Kartendaten werden geprüft")) }
                launched = true
                benchmarkJob = scope.launch {
                    var result = EngineResult(false, "Messreport nicht verfügbar. Bitte erneut versuchen.")
                    var groupStarted = false
                    var benchmarkChartsLoaded = false
                    var lastBenchmarkProfile: ChartPresentation? = null
                    var preparationWorkers = buildJsonObject { put("available", false); put("reason", "Open not reached") }
                    var viewportReuseControls = buildJsonObject { put("available", false) }
                    fun readViewportReuse(): JsonObject = if (config.viewportReuseCandidate == null) buildJsonObject { put("available", false) }
                        else Json.parseToJsonElement(NativeBridge.benchmarkViewportReuseRead(engine, runId)).jsonObject.also {
                            check(it["schema"]?.jsonPrimitive?.content == "no5.viewport-reuse-diagnostic/1" &&
                                it["runId"]?.jsonPrimitive?.content == runId) { "Viewport reuse schema/run mismatch" }
                        }
                    fun readPreparationWorkers(): JsonObject = try {
                        Json.parseToJsonElement(NativeBridge.benchmarkPreparationRead(engine, runId)).jsonObject.also { report ->
                            check(report["schema"]?.jsonPrimitive?.content == "no5.preparation-metrics.v1" &&
                                report["runId"]?.jsonPrimitive?.content == runId) { "Native preparation schema/run pin mismatch" }
                        }
                    } catch (failure: Exception) {
                        buildJsonObject { put("available", false); put("reason", failure.message ?: "Native preparation report unavailable") }
                    }
                    val backend = object : RendererBenchmarkBackend {
                        override suspend fun metadata(): JsonObject {
                            withContext(Dispatchers.IO) { checkNotNull(benchmarkRecoveryStore).save(previousState) }
                            pendingBenchmarkRecovery = previousState
                            val dataset = withContext(Dispatchers.IO) { bundledBenchmarkDataset(paths) }
                            checkReady()
                            val openStarted = System.nanoTime()
                            val openCallerCpu = android.os.Debug.threadCpuTimeNanos()
                            val openProcessCpu = android.os.Process.getElapsedCpuTime()
                            val opened = openNativeCharts(paths.toTypedArray())
                            val openEnded = System.nanoTime()
                            val openCallerCpuEnded = android.os.Debug.threadCpuTimeNanos()
                            val openProcessCpuEnded = android.os.Process.getElapsedCpuTime()
                            preparationWorkers = readPreparationWorkers()
                            check(opened) { "Mitgelieferte Boston-Testkarten konnten nicht geladen werden" }
                            loadedChartId = "noaa-boston-harbour"; loadedChartPaths = paths; loadedBackgroundPaths = emptyList(); benchmarkChartsLoaded = true; refresh()
                            return buildJsonObject {
                                captureMetadata(scriptedNativeFullRender=true).forEach { (key, value) -> if (key != "presentation" && key != "viewport") put(key, value) }
                                put("dataset", dataset)
                                put("preparationWorkers", preparationWorkers)
                                put("viewportReuseDiagnostic", readViewportReuse())
                                put("kotlinOpenIncludingOutlines", buildJsonObject {
                                    put("startMonotonicNs", openStarted); put("endMonotonicNs", openEnded)
                                    put("wallMs", (openEnded - openStarted) / 1e6)
                                    put("callerCpuMs", (openCallerCpuEnded - openCallerCpu) / 1e6)
                                    put("processCpuMs", openProcessCpuEnded - openProcessCpu)
                                    put("scope", "chartSetOpen plus outline preparation; process CPU includes all App threads, overlapping work possible")
                                    put("processCpuResolution", "platform millisecond API; no sub-ms precision asserted")
                                })
                                put("surface", buildJsonObject { put("width", geometry.surfaceWidth); put("height", geometry.surfaceHeight) })
                                put("framebuffer", buildJsonObject { put("width", geometry.frameWidth); put("height", geometry.frameHeight) })
                                put("framebufferScope", "visible canonical camera; bufferRaster.producerFrame and gl.frameWidth/frameHeight describe actual native draw raster")
                                put("s52", buildJsonObject { put("palette", palette.name); put("safetyContourMetres", previousSettings.safetyContourMetres) })
                                put("publicPresetCoordinatesIncluded", true)
                            }
                        }
                        override fun checkReady() {
                            if (benchmarkRunCancelled(runId)) throw BenchmarkCancelled()
                            check(!closed && engine != 0L && surfaceGeometry == geometry && surfaceRevision == surfaceEpoch) { "Kartenanzeige unterbrochen oder Größe geändert; Messung abgebrochen" }
                            check(loadedChartPaths == if (benchmarkChartsLoaded) paths else previousState.paths) {
                                "Kartenauswahl während der Messung geändert; Lauf abgebrochen"
                            }
                        }
                        override suspend fun apply(profile: ChartPresentation) {
                            checkReady()
                            activeSettings = previousSettings.copy(palette = palette)
                            NativeBridge.s52SettingsSet(engine, palette.name, activeSettings.safetyContourMetres); replaceObjectFilters(activeSettings.objectFilters)
                            if (lastBenchmarkProfile != profile) {
                                // Land/relief preparation uses the fixed camera, outside warmup and measured frames.
                                benchmarkViewport(BostonBenchmarkRecipe.camera(false, 0, geometry))
                                benchmarkPresentation(profile)
                                lastBenchmarkProfile = profile
                            }
                            checkReady()
                            NativeBridge.s52SettingsSet(engine, palette.name, activeSettings.safetyContourMetres); replaceObjectFilters(activeSettings.objectFilters)
                            replaceOverlays(ChartOverlays()); replaceObjectFilters(ChartObjectFilters())
                        }
                        override fun configureViewportReuse(variant: RendererViewportReuseVariant) {
                            checkReady()
                            check(config.viewportReuseCandidate != null && NativeBridge.benchmarkViewportReuseConfigure(engine, runId, variant.name)) {
                                "Viewport reuse arm lost its native run owner"
                            }
                        }
                        override fun begin(case: BenchmarkCase, viewport: ChartViewport) {
                            checkReady()
                            benchmarkViewport(viewport); refresh()
                            check(!mutable.value.presentationPending) { "Presentation preparation still pending; repeat after it completes" }
                            captureMetadata = buildJsonObject {
                                captureMetadata(scriptedNativeFullRender=true).forEach { (key, value) -> put(key, value) }
                                put("runId", runId); put("case", BostonBenchmarkRecipe.caseJson(case, standard))
                                put("viewportReuseDiagnostic", readViewportReuse())
                                put("publicPresetViewport", BostonBenchmarkRecipe.cameraJson(viewport))
                            }
                            previousFrameEnd = 0L; latestInputAt.set(0); appliedInputAt = 0L; lastCapturedInputNanos = 0L
                            captureBuffer = RendererCaptureBuffer(RendererCaptureConfig(60, BostonBenchmarkRecipe.FRAMES, case.detailed), System.nanoTime(), BostonBenchmarkRecipe.WARMUP)
                            activeUiCapture = captureBuffer?.ui
                            groupStarted = true
                            NativeBridge.captureModeSet(engine, if (case.detailed) 1 else 0)
                        }
                        override fun frame(viewport: ChartViewport) {
                            checkReady()
                            check(captureBuffer != null) { "Capture interrupted or timed out" }
                            // Feed one absolute camera step through the measured worker path; never coalesce motion.
                            pendingViewport.set(viewport)
                            synchronized(cameraLock) { requestedCameraRevision++ }
                            mutable.update { it.copy(confirmedFrame = null) }
                            renderAndRefresh(applyCamera = true)
                            val actual = mutable.value.viewport
                            check(kotlin.math.abs(actual.latitude - viewport.latitude) < 1e-6 &&
                                kotlin.math.abs(actual.longitude - viewport.longitude) < 1e-6 &&
                                kotlin.math.abs(actual.metresToPixels - viewport.metresToPixels) < 1e-6 &&
                                kotlin.math.abs(actual.rotationRadians - viewport.rotationRadians) < 1e-6 &&
                                actual.width == viewport.width && actual.height == viewport.height) { "Native camera differs from benchmark recipe" }
                        }
                        override fun end(reason: String): JsonObject? {
                            if (!groupStarted) return null
                            finishCapture(reason)
                            groupStarted = false
                            return Json.parseToJsonElement(lastCaptureReport).jsonObject
                        }
                        override suspend fun restore(): Boolean {
                            mutable.update { it.copy(rendererBenchmark = it.rendererBenchmark.copy(phase = "restoring", message = "Ursprünglicher Stil, Kartenausschnitt und Overlays werden wiederhergestellt")) }
                            benchmarkRuns.beginRecovery(runId, android.os.SystemClock.elapsedRealtime())
                            preparationWorkers = readPreparationWorkers()
                            NativeBridge.benchmarkRunEnd(engine, runId)
                            try {
                                if (config.viewportReuseCandidate != null) {
                                    check(NativeBridge.benchmarkViewportReuseRestore(engine, runId)) { "Viewport reuse controls could not be restored" }
                                    viewportReuseControls = readViewportReuse()
                                }
                                check(NativeBridge.benchmarkPreparationRestore(engine, runId)) { "Preparation controls could not be restored" }
                                preparationWorkers = readPreparationWorkers() // Frozen before reopening the previous user sources.
                                val restored = restoreBenchmarkUserState(previousState)
                                if (!restored) mutable.update { it.copy(rendererBenchmarkRecoveryPending = true) }
                                return restored
                            } finally { benchmarkRuns.endRecovery(runId, android.os.SystemClock.elapsedRealtime()) }
                        }
                        override fun progress(completed: Int, total: Int, frame: Int, message: String) {
                            mutable.update { it.copy(rendererBenchmark = it.rendererBenchmark.copy(phase = "measuring", completedGroups = completed, totalGroups = total, groupFrame = frame, message = message)) }
                        }
                    }
                    try {
                        val report = RendererBenchmarkRunner(backend) { benchmarkRunCancelled(runId) }.run(runId, config, geometry,
                            standardProfile = if (standard || config.viewportReuseCandidate != null) previousPresentation else null)
                        val deliveredReport = buildJsonObject {
                            report.forEach { (key, value) -> put(key, value) }
                            put("build", buildJsonObject { put("appVersion", appVersion); put("sourceCommit", sourceCommit) })
                            put("startedUtc", startedUtc); put("finishedUtc", java.time.Instant.now().toString())
                            put("measurementControl", benchmarkControlJson(runId))
                            put("preparationWorkers", preparationWorkers)
                            put("viewportReuseDiagnostic", viewportReuseControls)
                        }
                        lastCaptureReport = deliveredReport.toString()
                        result = EngineResult(true, lastCaptureReport)
                        mutable.update { it.copy(rendererBenchmark = it.rendererBenchmark.copy(phase = "saving", message = "Report wird gespeichert", restored = report.getValue("restored").jsonPrimitive.boolean)) }
                        // No report IO overlaps measured native frames. Keep partial raw captures as well.
                        val rawReports = pendingProfileReports.toList().also { pendingProfileReports.clear() }
                        val delivery = withContext(reportIoDispatcher) {
                            val store = checkNotNull(reportStore)
                            saveBenchmarkReports(runId, deliveredReport.toString(), rawReports, store::save, store::saveBenchmark)
                        }
                        mutable.update { it.copy(rendererBenchmark = it.rendererBenchmark.copy(active = false,
                            phase = report.getValue("outcome").jsonPrimitive.content, reportFile = delivery.file,
                            restored = report.getValue("restored").jsonPrimitive.boolean,
                            message = listOf(report.getValue("error").jsonPrimitive.content, report.getValue("restorationError").jsonPrimitive.content,
                                delivery.warning).filter { it.isNotBlank() }.joinToString("; ").ifEmpty { "Messung abgeschlossen. Report gespeichert; ursprüngliche Ansicht wiederhergestellt." })) }
                    } catch (failure: Exception) {
                        mutable.update { it.copy(rendererBenchmark = it.rendererBenchmark.copy(active = false, phase = "failed",
                            message = "Report konnte nicht gespeichert werden. Im Speicher erhalten; bitte als JSON exportieren.")) }
                        if (!result.success) result = EngineResult(false, mutable.value.rendererBenchmark.message)
                        Log.e("No5Chart", "Benchmark report delivery failed", failure)
                    } finally {
                        profiling = false
                        if (pendingBenchmarkRecovery != null || benchmarkRecoveryError != null)
                            mutable.update { it.copy(rendererBenchmarkRecoveryPending = true) }
                        if (!closed && engine != 0L) { NativeBridge.captureModeSet(engine, 0); queueRender() }
                        if (engine != 0L) {
                            NativeBridge.benchmarkRunEnd(engine, runId)
                            if (config.viewportReuseCandidate != null) NativeBridge.benchmarkViewportReuseRestore(engine, runId)
                            NativeBridge.benchmarkPreparationRestore(engine, runId)
                        }
                        benchmarkRuns.finish(runId, mutable.value.rendererBenchmark)
                        watchdog.cancel()
                        completion?.complete(result)
                    }
                }
                EngineResult(true, runId)
            }
            if (!accepted.success) {
                benchmarkRuns.finish(runId, RendererBenchmarkStatus(runId = runId, phase = "rejected", message = accepted.message, restored = true))
                watchdog.cancel()
            }
            return accepted
        } catch (failure: Exception) {
            // Once launched, engine scope owns the run even if the START caller loses/cancels its reply.
            if (!launched) {
                withContext(NonCancellable + dispatcher) { if (engine != 0L) {
                    NativeBridge.benchmarkRunEnd(engine, runId)
                    if (config.viewportReuseCandidate != null) NativeBridge.benchmarkViewportReuseRestore(engine, runId)
                    NativeBridge.benchmarkPreparationRestore(engine, runId)
                } }
                benchmarkRuns.finish(runId, RendererBenchmarkStatus(runId = runId, phase = "failed", message = failure.message.orEmpty(), restored = true))
                watchdog.cancel()
            }
            throw failure
        }
    }
    override suspend fun profileRenderer(): EngineResult = withContext(dispatcher) {
        val completion = CompletableDeferred<EngineResult>()
        val start = startRendererMeasurement(RendererBenchmarkConfig(1), standard = true, completion = completion)
        if (!start.success) return@withContext start
        // Preserve the synchronous API, but let the engine own recovery even if the UI is recreated.
        val job = checkNotNull(benchmarkJob)
        try { completion.await() }
        catch (cancel: CancellationException) {
            cancelRendererBenchmark(start.message)
            withContext(NonCancellable) { job.join() }
            throw cancel
        }
    }
    override suspend fun applyPresentationProfile(value: ChartPresentation): EngineResult {
        interruptTouch()
        if (mutable.value.rendererBenchmark.active || mutable.value.rendererBenchmarkRecoveryPending) return EngineResult(false, "Messung oder Wiederherstellung vor dem Stilwechsel abschließen")
        presentationPreparing.incrementAndGet()
        val generation = presentationRequests.incrementAndGet()
        var token = 0L
        try {
            value.validate()
            token = presentationPreparation.withPermit {
                if (generation != presentationRequests.get() || closed) return EngineResult(false, "Superseded presentation request")
                withContext(NonCancellable + Dispatchers.IO) { NativeBridge.presentationPrepare(dataDirectory, value.styleId, value.symbols.id) }
            }
            return withContext(dispatcher) {
                if (closed || engine == 0L || generation != presentationRequests.get()) return@withContext EngineResult(false, "Superseded presentation request")
                check(!profiling && benchmarkRuns.activeRun() == null && !mutable.value.rendererBenchmark.active &&
                    !mutable.value.rendererBenchmarkRecoveryPending) { "Wait for the active measurement/recovery" }
                markCameraPending(); applyPendingViewport()
                val applied = measured("presentationApplyTotal") { NativeBridge.presentationApply(engine, token, value.landRelief, generation) }
                token = 0L
                if (!applied) return@withContext EngineResult(false, "Superseded presentation generation")
                activeSettings = activeSettings.copy(palette = if (value.mode == PresentationMode.DAY) ChartPalette.DAY else ChartPalette.DUSK)
                renderAndRefresh()
                queueRender()
                EngineResult(mutable.value.presentationGeneration == generation)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { return EngineResult(false, failure.message ?: "Presentation switch failed") }
        finally { if (token != 0L) NativeBridge.presentationDiscard(token); presentationPreparing.decrementAndGet() }
    }
    override fun attachSurface(surface:Surface,width:Int,height:Int) = attachSurface(surface,width,height,Any())
    override fun attachSurface(surface:Surface,width:Int,height:Int,attachmentOwner:Any) {
        val owner=NativeSurfaceOwner(surface,attachmentOwner=attachmentOwner)
        val surfaceCommand=prepareSurfaceAttachment(owner,false)
        submit {
            applySurfaceAttachment(surfaceCommand,ChartSurfaceGeometry.fit(width,height),null,
                {NativeBridge.surfaceAttach(engine,surface,width,height)},
                {NativeBridge.presentationTimestampAvailable(engine)},owner)
        }
    }
    override fun attachPresentationSurfaces(surface0:Surface,surface1:Surface,width:Int,height:Int) =
        attachPresentationSurfaces(surface0,surface1,width,height,Any())
    override fun attachPresentationSurfaces(surface0:Surface,surface1:Surface,width:Int,height:Int,attachmentOwner:Any) {
        val owner=NativeSurfaceOwner(surface0,surface1,attachmentOwner)
        val surfaceCommand=prepareSurfaceAttachment(owner,true)
        submit {
            val visible=ChartSurfaceGeometry.fit(width,height);val raster=ChartRasterGeometry.reserve(visible)
            applySurfaceAttachment(surfaceCommand,visible,raster,
                {NativeBridge.surfaceAttachPairRaster(engine,surface0,surface1,width,height,raster.bufferWidth,raster.bufferHeight)},
                {NativeBridge.presentationTimestampAvailable(engine)},owner)
        }
    }
    internal fun prepareSurfaceAttachment(owner:NativeSurfaceOwner,paired:Boolean):Long = synchronized(cameraLock) {
        surfaceCommandPending.set(true);val command=surfaceCommandRevision.incrementAndGet()
        requestedSurfaceOwner=owner;pendingSurfaceRefresh=null;pairedPresentation=paired
        interruptTouch();touchTimestampSupported=false
        if(paired)presentationGate.reset(bufferRegistry.generation())
        mutable.update{it.copy(touchOutlinesAvailable=false)}
        markCameraPending();pendingMotions.clear();recordSurfaceCommand(if(paired)"REQUEST_ATTACH_PAIR" else "REQUEST_ATTACH",command)
        command
    }
    /** Confined to the native worker. Tests replace only the actual EGL attach/proof boundary. */
    internal fun applySurfaceAttachment(surfaceCommand:Long,visible:ChartSurfaceGeometry,raster:ChartRasterGeometry?,
        nativeAttach:()->Unit,timestampAvailable:()->Boolean,owner:NativeSurfaceOwner?=null):Boolean {
        try {
            if(surfaceCommand!=surfaceCommandRevision.get()) {
                recordSurfaceCommand("SKIP_ATTACH",surfaceCommand);return false
            }
            finishCapture(if(raster==null)"surface replaced" else "surface pair replaced");resetWorkerSurface()
            if(!residentOwner.active)applyPendingViewport() else synchronized(cameraLock){pendingViewport.set(null);pendingMotions.clear()}
            if(raster==null&&bufferTransformMode!=ChartBufferTransformMode.OFF) {
                bufferTransformMode=ChartBufferTransformMode.OFF
                mutable.update{it.copy(bufferTransformMode=ChartBufferTransformMode.OFF)}
            }
            nativeAttach() // The native implementation detaches/relinquishes its previous EGL Surface first.
            workerSurfaceCommand=surfaceCommand;workerSurfaceOwner=owner
            surfacePlainFrameCount=0;tracedPlainStamp=0
            surfaceGeometry=visible;producerRasterGeometry=raster
            mutable.update{it.copy(surfaceAttached=true)}
            recordSurfaceCommand(if(raster==null)"ATTACH" else "ATTACH_PAIR",surfaceCommand)
            revalidateResidentStart(surfaceCommand)
            touchTimestampSupported=timestampAvailable()
            mutable.update{it.copy(touchOutlinesAvailable=touchTimestampSupported,sourcePresentationUnsupported = !touchTimestampSupported)}
            startupSelectionAllowed=false
            if(mutable.value.rendererBenchmarkRecoveryPending&&!profiling&&!mutable.value.rendererBenchmark.active)
                benchmarkJob=scope.launch{restoreInterruptedRendererBenchmark()}
            synchronized(cameraLock){pendingSurfaceRefresh?.takeIf{it.command==surfaceCommand}}?.let(::applySurfaceRefresh)
            return true
        } finally {if(surfaceCommand==surfaceCommandRevision.get())surfaceCommandPending.set(false)}
    }
    /** Worker barrier: no tag registered against the former EGL attachment can survive its replacement. */
    private fun resetWorkerSurface() {
        surfaceRevision++; surfaceGeometry = null; producerRasterGeometry = null
        residentOwner.invalidateCheckpoint()
        synchronized(cameraLock) {
            val generation = bufferRegistry.invalidate()
            presentationGate.reset(generation)
            progressResetPending.set(true)
            mutable.update { it.copy(surfaceGeneration = surfaceRevision, surfaceAttached = false, confirmedFrame = null,
                residentCheckpointRevision = -1, residentCameraDecision = null, sourcePresentationUnsupported = false,
                residentCameraReady = !residentOwner.active,
                residentStartState = if (residentOwner.active) ChartResidentStartState.WAITING_SURFACE else ChartResidentStartState.NOT_REQUIRED,
                chartScaleDenominator = null,
                bufferTransformGeneration = generation, bufferTransformBaseReady = false) }
            announceNormalRenderAuthority()
        }
    }
    override fun detachSurface()=detachSurface{}
    override fun refreshSurface(expectedSurface:Surface,attachmentOwner:Any):Boolean {
        val request=prepareSurfaceRefresh(expectedSurface,attachmentOwner)?:return false
        scope.launch{applySurfaceRefresh(request)}
        return true
    }
    internal fun prepareSurfaceRefresh(expectedSurface:Any,attachmentOwner:Any):NativeSurfaceRefresh? = synchronized(cameraLock) {
        if(closed||requestedSurfaceOwner?.matches(expectedSurface,attachmentOwner)!=true)return@synchronized null
        NativeSurfaceRefresh(surfaceCommandRevision.get(),expectedSurface,attachmentOwner).also {
            pendingSurfaceRefresh=it;recordSurfaceCommand("REQUEST_REFRESH",it.command)
        }
    }
    /** Only the renderer worker may wake an actual loan; a queued matching attach retains the wake. */
    internal fun applySurfaceRefresh(request:NativeSurfaceRefresh):Boolean = synchronized(cameraLock) {
        if(closed||pendingSurfaceRefresh!==request||surfaceCommandRevision.get()!=request.command||
            requestedSurfaceOwner?.matches(request.surface,request.attachmentOwner)!=true) {
            if(pendingSurfaceRefresh===request)pendingSurfaceRefresh=null
            recordSurfaceCommand("SKIP_REFRESH",request.command);return@synchronized false
        }
        if(workerSurfaceCommand==request.command&&workerSurfaceOwner?.matches(request.surface,request.attachmentOwner)==true&&surfaceGeometry!=null) {
            pendingSurfaceRefresh=null;queueRender();recordSurfaceCommand("REFRESH",request.command)
            return@synchronized true
        }
        if(surfaceCommandPending.get()) {
            recordSurfaceCommand("DEFER_REFRESH",request.command);return@synchronized true
        }
        pendingSurfaceRefresh=null;recordSurfaceCommand("SKIP_REFRESH",request.command);false
    }
    override fun detachSurface(onDetached:()->Unit) {
        val surfaceCommand=prepareSurfaceDetachment()
        if(closed){onDetached();return}
        scope.launch{applySurfaceDetachment(surfaceCommand,onDetached)}
    }
    override fun detachSurface(expectedSurface:Surface,onDetached:()->Unit) = detachOwnedSurface(expectedSurface,null,onDetached)
    override fun detachSurface(expectedSurface:Surface,attachmentOwner:Any,onDetached:()->Unit) =
        detachOwnedSurface(expectedSurface,attachmentOwner,onDetached)
    private fun detachOwnedSurface(expectedSurface:Surface,attachmentOwner:Any?,onDetached:()->Unit) {
        val surfaceCommand=prepareOwnedSurfaceDetachment(expectedSurface,attachmentOwner)
        if(closed){onDetached();return}
        // Even an obsolete view waits on the renderer worker: its old EGL loan may
        // still be attached while the replacement is only announced, not applied.
        val diagnosticCommand=surfaceCommand?:surfaceCommandRevision.get()
        scope.launch{applySurfaceDetachment(diagnosticCommand,onDetached,expectedSurface=expectedSurface,
            ownsPendingCommand=surfaceCommand!=null,expectedAttachmentOwner=attachmentOwner)}
    }
    internal fun prepareSurfaceDetachment():Long = synchronized(cameraLock) {
        requestedSurfaceOwner=null
        prepareSurfaceDetachmentLocked()
    }
    internal fun prepareOwnedSurfaceDetachment(expectedSurface:Any,expectedAttachmentOwner:Any?=null):Long? = synchronized(cameraLock) {
        if(requestedSurfaceOwner?.matches(expectedSurface,expectedAttachmentOwner)!=true) {
            recordSurfaceCommand("REQUEST_RELEASE_OLD",surfaceCommandRevision.get())
            return@synchronized null
        }
        requestedSurfaceOwner=null
        prepareSurfaceDetachmentLocked()
    }
    private fun prepareSurfaceDetachmentLocked():Long {
        pendingSurfaceRefresh=null
        surfaceCommandPending.set(true);val command=surfaceCommandRevision.incrementAndGet()
        interruptTouch();touchTimestampSupported=false;mutable.update{it.copy(touchOutlinesAvailable=false)}
        markCameraPending();pendingMotions.clear();recordSurfaceCommand("REQUEST_DETACH",command)
        return command
    }
    /** Release is always after the old EGL loan is gone, even when a newer attachment is only queued. */
    internal fun applySurfaceDetachment(surfaceCommand:Long,onDetached:()->Unit,
        nativeDetach:()->Unit={if(engine!=0L)NativeBridge.surfaceDetach(engine)},
        expectedSurface:Any?=null,ownsPendingCommand:Boolean=true,expectedAttachmentOwner:Any?=null):Boolean {
        var detached=false
        try {guarded {
            if(expectedSurface!=null&&workerSurfaceOwner?.matches(expectedSurface,expectedAttachmentOwner)!=true) {
                recordSurfaceCommand("SKIP_OWNER_RELEASE",surfaceCommand);return@guarded
            }
            // Comparing only latestRequest is unsafe: a pending newer attach has not released the old EGL loan.
            // A successfully applied newer attach did release it, so an old detach must preserve that new owner.
            if(workerSurfaceCommand>surfaceCommand) {
                recordSurfaceCommand("SKIP_DETACH",surfaceCommand);return@guarded
            }
            finishCapture("surface detached");resetWorkerSurface()
            nativeDetach()
            workerSurfaceCommand=0;workerSurfaceOwner=null;surfaceGeometry=null
            detached=true;recordSurfaceCommand("DETACH",surfaceCommand)
        }} finally {
            if(ownsPendingCommand&&surfaceCommand==surfaceCommandRevision.get())surfaceCommandPending.set(false)
            onDetached()
        }
        return detached
    }
    override fun setViewport(viewport: ChartViewport) {submitCameraViewport(viewport,0,cameraClock.now())}
    override fun submitCameraViewport(viewport:ChartViewport,sequence:Long,inputUptimeMs:Long):Long {
        val state=mutable.value
        if(closed||profiling||!normalCameraAllowed(state)||state.rendererBenchmarkRecoveryPending)return 0
        return cameraControl.absolute(viewport,sequence,inputUptimeMs,expectedSource=state.chartSourceGeneration,expectedSurface=state.surfaceGeneration,diagnosticOrigin=diagnosticOrigin(state))
    }
    override suspend fun restoreCameraViewport(viewport:ChartViewport,expectedSourceGeneration:Long):Boolean {
        val state=mutable.value
        if(closed||profiling||surfaceCommandPending.get()||surfaceGeometry!=null||state.surfaceAttached||state.residentCatalogActive||
            !normalCameraAllowed(state)||state.rendererBenchmarkRecoveryPending||state.chartSourceGeneration!=expectedSourceGeneration)return false
        val now=cameraClock.now()
        val id=cameraControl.restore(viewport,0,now,expectedSourceGeneration,now+1000)
        if(id==0L)return false
        val result=ChartControlResult()
        return withTimeoutOrNull(1000) {
            while(cameraControlResult(id,result)&&result.state<2&&result.revision<0)delay(1)
            result.id==id&&result.revision>=0&&result.state in 1..2&&mutable.value.chartSourceGeneration==expectedSourceGeneration
        }==true
    }
    override fun moveCamera(motion: ChartMotion) {
        val state=mutable.value
        if (closed || profiling || !normalCameraAllowed(state) || state.rendererBenchmarkRecoveryPending) return
        if (motion.pan == ScreenPoint(0.0, 0.0) && motion.zoom == 1.0 && motion.rotation == 0.0) return
        val geometry=surfaceGeometry?:return
        cameraControl.motion(motion,geometry,0,cameraClock.now(),state.chartSourceGeneration,state.surfaceGeneration,diagnosticOrigin(state))
    }
    private val cameraControl by lazy { CameraControlActor({mutable.value},{requestedCameraRevision},cameraClock,
        onBoundsDecision = { decision, source, surface -> mutable.update {
            if(it.chartSourceGeneration==source && it.surfaceGeneration==surface)it.copy(cameraBoundsDecision=decision)else it
        } }) { id,row ->
        commitCameraGoal(ChartViewport(row.a,row.b,row.c,row.d,mutable.value.viewport.width,mutable.value.viewport.height),
            row.generation,row.surface,id,row.kind==CameraControlActor.TOUCH_PREVIEW,row.kind==CameraControlActor.RESTORE)!=null
    } }
    override fun submitCameraControl(control:ChartCameraControl,amount:Double,sequence:Long,physicalUptimeMs:Long,
        latitude:Double,longitude:Double,homeScale:Double):Long {
        val state=mutable.value
        if(closed||profiling||!normalCameraAllowed(state)||state.rendererCapture.active&&!currentResidentLiveCameraAllowed()||state.rendererBenchmarkRecoveryPending)return 0
        return cameraControl.submit(control,amount,sequence,physicalUptimeMs,latitude,longitude,homeScale,state.chartSourceGeneration,state.surfaceGeneration)
    }
    override fun submitCameraControl(control:ChartCameraControl,amount:Double,sequence:Long,physicalUptimeMs:Long,
        latitude:Double,longitude:Double,homeScale:Double,expectedSourceGeneration:Long,expectedSurfaceGeneration:Long):Long {
        val state=mutable.value
        if(closed||profiling||!normalCameraAllowed(state)||state.rendererCapture.active&&!currentResidentLiveCameraAllowed()||state.rendererBenchmarkRecoveryPending||
            expectedSourceGeneration<0||expectedSurfaceGeneration<0)return 0
        return cameraControl.submit(control,amount,sequence,physicalUptimeMs,latitude,longitude,homeScale,expectedSourceGeneration,expectedSurfaceGeneration)
    }
    override fun cameraControlResult(id:Long,destination:ChartControlResult):Boolean = cameraControl.read(id,destination)
    override fun cancelCameraControl() = cameraControl.cancel()
    override fun commitCameraAdjustment(viewport:ChartViewport,expectedSource:Long,expectedSurface:Long,commandId:Long):ChartCameraCommit? =
        commitCameraGoal(viewport,expectedSource,expectedSurface,commandId,false)
    /** This is the single camera ownership transfer for touch, overlays, follow and physical controls. */
    private fun commitCameraGoal(viewport:ChartViewport,expectedSource:Long,expectedSurface:Long,commandId:Long,
        requestedPreview:Boolean,requestedRestore:Boolean=false):ChartCameraCommit? = synchronized(cameraLock) {
        val state=mutable.value
        val preview=requestedPreview && if(bufferTransformMode==ChartBufferTransformMode.OFF)touchFrames.held.get() else transformTouchActive.get()
        val restoring=requestedRestore&&!state.surfaceAttached&&surfaceGeometry==null&&!state.residentCatalogActive
        if(requestedRestore&&!restoring)return@synchronized null
        if(closed||profiling||surfaceCommandPending.get()||!state.initialized||!state.chartLoaded||(!restoring&&surfaceGeometry==null)||
            !(if(preview)liveCameraAllowed(state) else normalCameraAllowed(state))||state.rendererBenchmarkRecoveryPending||
            state.chartSourceGeneration!=expectedSource||state.surfaceGeneration!=expectedSurface||!cameraControl.canCommit(commandId))return@synchronized null
        val basis=state.viewport
        val admitted=admitCamera(viewport,basis,cameraControl.noZoomAlternative(commandId),
            pureZoomInRecovery=viewport.metresToPixels>basis.metresToPixels&&viewport.latitude==basis.latitude&&viewport.longitude==basis.longitude&&viewport.rotationRadians==basis.rotationRadians)?:return@synchronized null
        if(mutable.value.chartSourceGeneration!=expectedSource||mutable.value.surfaceGeneration!=expectedSurface||!cameraControl.canCommit(commandId))return@synchronized null
        if(admitted==basis && state.residentCameraReady && pendingViewport.get()==null && cameraControl.appliedRevision==requestedCameraRevision) {
            cameraControl.unchanged(commandId,requestedCameraRevision,admitted)
            return@synchronized ChartCameraCommit(requestedCameraRevision,admitted)
        }
        if(!preview)interruptTouch()
        val origin=cameraControl.runningInputOrigin(commandId)
        if(pendingViewport.get()!=null && diagnosticInputId(pendingLiveOrigin)>0 && pendingLiveOrigin!==origin)
            observeCameraDisposition(pendingLiveOrigin,"COALESCED_CAMERA",origin)
        pendingLiveOrigin=origin
        latestInputAt.set(System.nanoTime());pendingViewport.set(admitted);pendingMotions.clear();requestedCameraRevision++
        if(!mutable.value.residentCameraReady) {pendingResidentStart=PendingResidentStart(admitted,null);preserveRestoredCamera=false}
        cameraControl.prepared(commandId,requestedCameraRevision,admitted)
        observeCamera(origin,requestedCameraRevision,bufferRegistry.generation(),admitted,false)
        mutable.update { it.copy(viewport=admitted,confirmedFrame=null,
            touchPreview=if(preview)it.touchPreview?.copy(camera=admitted,showing=true) else it.touchPreview) }
        announceNormalRenderAuthority()
        if(!preview || bufferTransformMode!=ChartBufferTransformMode.OFF && bufferTransformMode!=ChartBufferTransformMode.TRANSFORM_ONLY)queueRender()
        ChartCameraCommit(requestedCameraRevision,admitted)
    }
    override suspend fun adjustCamera(command:ChartCameraAdjustment,confirmed:(ChartViewport)->Unit):ChartViewport? {
        val state=mutable.value
        if(closed||profiling||!normalCameraAllowed(state)||surfaceGeometry==null||state.rendererBenchmarkRecoveryPending)return null
        val id=cameraControl.adjustment(command,0,cameraClock.now(),state.chartSourceGeneration,state.surfaceGeneration,diagnosticOrigin(state))
        if(id==0L)return null
        val result=ChartControlResult()
        while(cameraClock.now()<command.deadlineUptimeMs) {
            if(!cameraControl.read(id,result))return null
            if(result.replacementId>0 || result.state>=3)return null
            if(result.revision>=0) {
                val actual=mutable.value.viewport
                confirmed(actual);return actual
            }
            delay(1)
        }
        return null
    }
    override suspend fun zoomCamera(factor:Double,anchor:ScreenPoint,frame:ChartFrame):Boolean {
        val state=mutable.value
        if((!frameMatches(frame)&&touchCommandFrame.get()!=frame)||surfaceGeometry!=frame.geometry||profiling||
            !normalCameraAllowed(state)||!factor.isFinite()||factor<=0||!frame.geometry.contains(anchor))return false
        if(factor==1.0)return true
        return cameraControl.motion(ChartMotion.anchored(anchor,zoom=factor),frame.geometry,0,cameraClock.now(),state.chartSourceGeneration,state.surfaceGeneration,diagnosticOrigin(state))>0
    }
    override suspend fun centerCamera(position:GeoProjection,rotationRadians:Double?):Boolean {
        val state=mutable.value
        if(closed||profiling||!normalCameraAllowed(state)||surfaceGeometry==null||state.rendererBenchmarkRecoveryPending)return false
        return cameraControl.adjustment(ChartCameraAdjustment(rotationRadians=rotationRadians,center=position,
            deadlineUptimeMs=Long.MAX_VALUE),0,cameraClock.now(),state.chartSourceGeneration,state.surfaceGeneration,diagnosticOrigin(state))>0
    }
    override suspend fun projectCoordinates(positions: List<GeoProjection>, frame: ChartFrame): List<ScreenPoint>? = withContext(dispatcher) {
        if (mutable.value.rendererBenchmark.active) return@withContext null
        if (!frameMatches(frame) || positions.any { !it.latitude.isFinite() || !it.longitude.isFinite() || it.latitude !in -85.0..85.0 || it.longitude !in -180.0..180.0 }) return@withContext null
        val values = NativeBridge.projectCoordinates(engine, positions.flatMap { listOf(it.latitude, it.longitude) }.toDoubleArray())
        if (!frameMatches(frame)) null else values.toList().chunked(2).map { frame.geometry.toSurface(ScreenPoint(it[0], it[1])) }
    }
    override suspend fun unprojectCoordinate(point: ScreenPoint, frame: ChartFrame): GeoProjection? = withContext(dispatcher) {
        if (mutable.value.rendererBenchmark.active) return@withContext null
        if (!frameMatches(frame) || !frame.geometry.contains(point)) return@withContext null
        val p = frame.geometry.toFrame(point); val geo = NativeBridge.unprojectCoordinate(engine, p.x, p.y)
        if (!frameMatches(frame) || !geo.all { it.isFinite() } || geo[0] !in -85.0..85.0 || geo[1] !in -180.0..180.0) null else GeoProjection(geo[0], geo[1])
    }
    override suspend fun queryFeature(point: ScreenPoint, frame: ChartFrame): List<ChartFeature> = withContext(dispatcher) {
        if (mutable.value.rendererBenchmark.active) return@withContext emptyList()
        if (!frameMatches(frame) || !frame.geometry.contains(point)) return@withContext emptyList()
        val p = frame.geometry.toFrame(point)
        val found = Json.parseToJsonElement(NativeBridge.featureQuery(engine, p.x, p.y)).jsonArray.map {
            val f = it.jsonObject; ChartFeature(f.getValue("id").jsonPrimitive.content, f.getValue("name").jsonPrimitive.content,
                f.getValue("attributes").jsonObject.mapValues { pair -> pair.value.jsonPrimitive.content })
        }
        if (frameMatches(frame)) found else emptyList()
    }
    override suspend fun queryObjects(request: ChartObjectQuery): ChartObjectQueryResult = withContext(dispatcher) {
        if (closed || engine == 0L || mutable.value.rendererBenchmark.active || mutable.value.rendererBenchmarkRecoveryPending)
            return@withContext ChartObjectQueryResult(ChartObjectQueryState.UNAVAILABLE)
        if (mutable.value.presentationPending || !mutable.value.residentCameraReady || mutable.value.confirmedFrame == null)
            return@withContext ChartObjectQueryResult(ChartObjectQueryState.PENDING)
        fun current() = frameMatches(request.frame) && mutable.value.presentationGeneration == request.presentationGeneration && mutable.value.objectFilters.revision == request.filterRevision
        if (!current()) return@withContext ChartObjectQueryResult(ChartObjectQueryState.STALE)
        if (!request.frame.geometry.contains(request.point) || !request.radiusSurfacePixels.isFinite() || request.radiusSurfacePixels <= 0 || request.offset < 0)
            return@withContext ChartObjectQueryResult(ChartObjectQueryState.FAILED, message = "Ungültige Kartenabfrage")
        try {
            val point = request.frame.geometry.toFrame(request.point)
            val result = decodeObjectQuery(NativeBridge.objectQuery(engine, point.x, point.y,
                request.radiusSurfacePixels / request.frame.geometry.scale, request.offset), request.frame.sourceGeneration)
            if (current()) result else ChartObjectQueryResult(ChartObjectQueryState.STALE)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { ChartObjectQueryResult(ChartObjectQueryState.FAILED, message = failure.message ?: "Kartenabfrage fehlgeschlagen") }
    }
    override suspend fun selectObject(reference: ChartObjectReference?, radiusSurfacePixels: Double): Boolean = withContext(dispatcher) {
        if (closed || engine == 0L) return@withContext false
        if (reference != null && (mutable.value.rendererBenchmark.active || mutable.value.rendererBenchmarkRecoveryPending ||
            reference.sourceGeneration != mutable.value.chartSourceGeneration)) return@withContext false
        if (reference == null && mutable.value.objectSelection.featureIndex < 0) return@withContext true
        val scale = surfaceGeometry?.scale ?: 1.0
        val accepted = NativeBridge.objectSelect(engine, reference?.sourceKey.orEmpty(), reference?.featureIndex ?: -1, radiusSurfacePixels / scale)
        if (accepted && surfaceGeometry != null) { markCameraPending(); applyPendingViewport(); renderAndRefresh() }
        accepted
    }
    private fun readObjectFilters(): ChartObjectFilterState {
        val value = Json.parseToJsonElement(NativeBridge.objectFiltersRead(engine)).jsonObject
        return ChartObjectFilterState(true, decodeObjectFilters(value), value.getValue("revision").jsonPrimitive.long)
    }
    private fun replaceObjectFilters(value: ChartObjectFilters) {
        NativeBridge.objectFiltersApply(engine, value.category.name, value.hiddenClasses.sorted().toTypedArray(),
            booleanArrayOf(value.soundings, value.text, value.importantTextOnly, value.atonText, value.lightText, value.metaObjects, true))
    }
    override suspend fun applyObjectFilters(request: ChartObjectFilters): EngineResult {
        val filters = request.copy(hiddenClasses = request.hiddenClasses.toSet())
        val accepted = withContext(dispatcher) {
            if (closed || engine == 0L || profiling || mutable.value.rendererBenchmark.active || mutable.value.rendererBenchmarkRecoveryPending || surfaceGeometry == null)
                return@withContext EngineResult(false, "S52-Filter gerade nicht verfügbar")
            if (readObjectFilters().effective == filters) return@withContext EngineResult(true)
            try {
                interruptTouch(); markCameraPending(); applyPendingViewport()
                replaceObjectFilters(filters); activeSettings = activeSettings.copy(objectFilters = filters)
                mutable.update { it.copy(confirmedFrame = null, objectFilters = readObjectFilters()) }
                renderAndRefresh(); queueRender(); EngineResult(true)
            } catch (failure: Exception) { EngineResult(false, failure.message ?: "S52-Filter abgelehnt") }
        }
        if (!accepted.success) return accepted
        val drawn = withTimeoutOrNull(20_000) { status.first { it.objectFilters.effective == filters && it.confirmedFrame != null && !it.presentationPending } }
        return if (drawn != null) EngineResult(true) else EngineResult(false, "Filter übernommen; sichtbares Kartenbild noch nicht bestätigt")
    }
    override fun setS52Settings(settings: S52Settings) { interruptTouch();submit { if (!mutable.value.rendererBenchmark.active && !mutable.value.rendererBenchmarkRecoveryPending) { activeSettings = settings.copy(objectFilters = activeSettings.objectFilters); NativeBridge.s52SettingsSet(engine, settings.palette.name, settings.safetyContourMetres) } } }
    override suspend fun queryFeature(point: ScreenPoint): List<ChartFeature> = withContext(dispatcher) {
        if (mutable.value.rendererBenchmark.active || residentOwner.active) return@withContext emptyList()
        check(!closed && engine != 0L)
        applyPendingViewport()
        Json.parseToJsonElement(NativeBridge.featureQuery(engine, point.x, point.y)).jsonArray.map {
            val f = it.jsonObject; ChartFeature(f.getValue("id").jsonPrimitive.content, f.getValue("name").jsonPrimitive.content,
                f.getValue("attributes").jsonObject.mapValues { pair -> pair.value.jsonPrimitive.content })
        }
    }
    private fun triples(values: List<GeoProjection>) = values.flatMap { listOf(it.latitude, it.longitude, it.headingRadians) }.toDoubleArray()
    private fun validOverlays(value: ChartOverlays): Boolean {
        val positions = listOfNotNull(value.ownship) + value.ais + value.waypoints + value.route?.points.orEmpty() + value.aisObjects.map { it.position } + value.waypointObjects.map { it.position }
        val objects = value.aisObjects + value.waypointObjects
        if (value.ais.size > 5000 || value.waypoints.size > 5000 || value.route?.points.orEmpty().size > 5000 || value.aisObjects.size > 5000 || value.waypointObjects.size > 5000 ||
            objects.any { it.id.length > 200 || it.label.length > 200 || it.headingRadians?.let { h -> !h.isFinite() || h < 0 || h >= Math.PI * 2 } == true }) return false
        return positions.none { !it.latitude.isFinite() || !it.longitude.isFinite() || !it.headingRadians.isFinite() ||
            it.latitude !in -85.0..85.0 || it.longitude !in -180.0..180.0 }
    }
    private fun applyPendingOverlays() {
        val value = overlayInbox.take(frozen = mutable.value.rendererBenchmark.active || mutable.value.rendererBenchmarkRecoveryPending) ?: return
        try {
            replaceOverlays(value)
            activeOverlays = value
            overlayInbox.markApplied(value)
        } catch (failure: Exception) {
            overlayInbox.markRejected(value)
            throw failure
        }
    }
    override fun setOverlays(value: ChartOverlays) {
        if (closed || engine == 0L || profiling || mutable.value.rendererBenchmark.active || mutable.value.rendererBenchmarkRecoveryPending || !validOverlays(value)) return
        if (overlayInbox.offer(value)) queueRender()
    }
    override suspend fun applyOverlays(input: ChartOverlays): Boolean {
        val value = input.frozenSnapshot()
        val applied = withContext(dispatcher) {
            if (closed || engine == 0L || profiling || mutable.value.rendererBenchmark.active || mutable.value.rendererBenchmarkRecoveryPending || surfaceGeometry == null || !validOverlays(value)) return@withContext false
            interruptTouch(); markCameraPending(); applyPendingViewport()
            replaceOverlays(value); activeOverlays = value; overlayInbox.markApplied(value)
            renderAndRefresh(); queueRender(); true
        }
        if (!applied) return false
        return withTimeoutOrNull(20_000) { status.first { it.confirmedFrame != null && !it.presentationPending }; withContext(dispatcher) { activeOverlays == value } } ?: false
    }
    override fun setOwnship(state: OwnshipProjection) = submit { if (!mutable.value.rendererBenchmark.active && !mutable.value.rendererBenchmarkRecoveryPending) { activeOverlays = activeOverlays.copy(ownship = state); NativeBridge.projectionOwnshipReplace(engine, triples(listOf(state))) } }
    override fun clearOwnship() = submit { if (!mutable.value.rendererBenchmark.active && !mutable.value.rendererBenchmarkRecoveryPending) { activeOverlays = activeOverlays.copy(ownship = null); NativeBridge.projectionOwnshipReplace(engine, doubleArrayOf()) } }
    override fun setAisTargets(targets: List<AisProjection>) = submit { if (!mutable.value.rendererBenchmark.active && !mutable.value.rendererBenchmarkRecoveryPending) { activeOverlays = activeOverlays.copy(ais = targets); NativeBridge.projectionAisReplace(engine, triples(targets)) } }
    override fun setRouteProjection(route: RouteProjection?) = submit { if (!mutable.value.rendererBenchmark.active && !mutable.value.rendererBenchmarkRecoveryPending) { activeOverlays = activeOverlays.copy(route = route); NativeBridge.projectionRouteReplace(engine, triples(route?.points.orEmpty())) } }
    override fun setWaypointProjection(waypoints: List<WaypointProjection>) = submit { if (!mutable.value.rendererBenchmark.active && !mutable.value.rendererBenchmarkRecoveryPending) { activeOverlays = activeOverlays.copy(waypoints = waypoints); NativeBridge.projectionWaypointsReplace(engine, triples(waypoints)) } }
    private fun replaceOverlays(value: ChartOverlays) {
        val ais = value.aisObjects.takeIf { it.isNotEmpty() } ?: value.ais.mapIndexed { i, point -> ChartOverlayObject("ais-$i", point) }
        val waypoints = value.waypointObjects.takeIf { it.isNotEmpty() } ?: value.waypoints.mapIndexed { i, point -> ChartOverlayObject("waypoint-$i", point) }
        val own = listOfNotNull(value.ownship?.let { ChartOverlayObject("ownship", it) })
        val route = value.route?.points.orEmpty().mapIndexed { i, point -> ChartOverlayObject(value.route?.pointIds?.getOrNull(i) ?: "route-$i", point, "${i + 1}") }
        val objects = own + ais + waypoints + route
        NativeBridge.overlaysReplace(engine, intArrayOf(own.size, ais.size, waypoints.size, route.size),
            objects.flatMap { listOf(it.position.latitude, it.position.longitude) }.toDoubleArray(),
            objects.map { it.label.take(200) }.toTypedArray(), objects.map { it.id.take(200) }.toTypedArray(),
            objects.map { it.headingRadians ?: Double.NaN }.toDoubleArray(), value.route?.role?.name ?: "SAVED")
    }

    override fun close() {
        cameraControl.close()
        if (closed) return
        interruptTouch()
        closed = true
        normalAbortEnabled = false; normalRenderAuthority.close(); currentResidentLiveSnapshots.close()
        currentResidentLiveLease?.invalidate()
        benchmarkRuns.activeRun()?.let { if (benchmarkRuns.cancel(it.id, "engine closed")) signalBenchmarkCancel(it.id, "engine closed") }
        // Synchronously drain preceding tasks, then release EGL on its owning worker.
        runBlocking { withContext(dispatcher) { benchmarkJob?.join(); benchmarkRecoveryJob?.join(); finishCapture("engine closed"); licensedProvider.close(); if (engine != 0L) NativeBridge.engineDestroy(engine); engine = 0L } }
        runBlocking { lastReportWrite?.join() }
        benchmarkWatchScope.cancel(); scope.cancel(); render.close(); dispatcher.close()
    }
}
