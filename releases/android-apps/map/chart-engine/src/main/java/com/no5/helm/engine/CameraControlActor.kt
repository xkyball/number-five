package com.no5.helm.engine

import android.os.SystemClock
import com.no5.helm.chart.*
import org.lumina.control.commands.v1.CommandRing
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.*

internal fun interface CameraControlClock { fun now():Long }
internal fun interface CameraControlRevision { fun current():Long }

/** One camera target inbox for controls, gestures and absolute views. No physical input identities. */
internal class CameraControlActor(
    private val state: () -> ChartEngineStatus,
    private val revision: CameraControlRevision,
    private val clock: CameraControlClock = CameraControlClock { SystemClock.uptimeMillis() },
    autoStart: Boolean = true,
    private val onBoundsDecision: (ChartCameraBoundsDecision, Long, Long) -> Unit = { _, _, _ -> },
    private val commit: (Long, CommandRing.Entry) -> Boolean,
) : AutoCloseable {
    companion object { const val NORMAL = 0; const val TOUCH_PREVIEW = 1; const val RESTORE = 2 }
    private val ring = CommandRing()
    private val goalLock = Any()
    private var source = -1L; private var surface = -1L; private var basisRevision = -1L
    private var lat = 0.0; private var lon = 0.0; private var scale = 0.0; private var rotation = 0.0
    private var width = 0; private var height = 0
    private var admissionNow=0L;private var lastSubmittedId=0L
    private val navigation = ChartCameraBoundsCache()
    private var beforeGoal: ChartViewport? = null
    private var pendingDeadline = Long.MAX_VALUE
    private var pendingAlternative:ChartViewport?=null
    @Volatile private var runningAlternative:ChartViewport?=null
    @Volatile private var ownerEpoch = 0L
    @Volatile private var runningEpoch = -1L
    @Volatile private var runningDeadline = Long.MAX_VALUE
    @Volatile private var runningId = 0L
    private val work = CommandRing.Entry()
    private val preparedTarget=CommandRing.Entry()
    private val nativeTarget=CommandRing.Entry()
    // Fixed sidecars for the existing phases, not another queue or result owner.
    private var pendingDiagnosticOrigin: CameraDiagnosticOrigin? = null // goalLock
    private var pendingDiagnosticId = 0L; private var pendingDiagnosticEpoch = -1L
    @Volatile private var drainedDiagnosticOrigin: CameraDiagnosticOrigin? = null
    private var preparedDiagnosticOrigin: CameraDiagnosticOrigin? = null // camera owner
    private var nativeDiagnosticOrigin: CameraDiagnosticOrigin? = null // camera owner
    private val diagnosticProbe = CommandRing.Entry() // goalLock; observes the original Ring only
    private val resultRow = CommandRing.Entry()
    private val idleRow = CommandRing.Entry()
    private val resultLock = Any()
    private var exclusiveAdmission: Any? = null
    @Volatile var appliedRevision = -1L; private set
    @Volatile var presentedRevision = -1L; private set
    @Volatile var actualScale = Double.NaN; private set
    @Volatile var closed = false; private set
    @Volatile private var preparing=false
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "No5CameraControl").apply { isDaemon = true } }
    init { if(autoStart)worker.scheduleWithFixedDelay({ drainOne() }, 0, 5, TimeUnit.MILLISECONDS) }

    private fun begin(sequence:Long, stamp:Long, deadline:Long,expectedSource:Long=-1,expectedSurface:Long=-1,requireSurface:Boolean=true):Boolean {
        val s = state(); val now = clock.now()
        if (closed || exclusiveAdmission != null || sequence < 0 || now < stamp || now-stamp > 250 || now >= deadline || !s.initialized || !s.chartLoaded ||
            requireSurface&&!s.surfaceAttached || s.rendererBenchmark.active && !s.rendererBenchmark.liveInputEnabled ||
            s.rendererBenchmarkRecoveryPending || expectedSource>=0&&expectedSource!=s.chartSourceGeneration ||
            expectedSurface>=0&&expectedSurface!=s.surfaceGeneration) return false
        val rev = revision.current()
        if (source != s.chartSourceGeneration || surface != s.surfaceGeneration || basisRevision<0 || (!preparing && basisRevision != rev)) {
            val camera=s.viewport;lat=camera.latitude;lon=camera.longitude;scale=camera.metresToPixels;rotation=camera.rotationRadians
            source=s.chartSourceGeneration;surface=s.surfaceGeneration;basisRevision=rev
        }
        width=s.viewport.width;height=s.viewport.height
        beforeGoal=ChartViewport(lat,lon,scale,rotation,width,height)
        admissionNow=now
        return true
    }
    private fun enqueue(sequence:Long, now:Long, preview:Boolean=false, deadline:Long=Long.MAX_VALUE, alternative:ChartViewport?=null,restoring:Boolean=false,diagnosticOrigin:CameraDiagnosticOrigin?=null):Long {
        val current=state();val region=navigation.get(current.navigationBounds)
        val requested=ChartViewport(lat,lon,scale,rotation,width,height)
        if(region!=null) {
            val decision=region.constrain(requested,beforeGoal?:current.viewport)
            val actual=decision.effective?:run {
                beforeGoal?.let{lat=it.latitude;lon=it.longitude;scale=it.metresToPixels;rotation=it.rotationRadians}
                onBoundsDecision(decision,source,surface);return 0
            }
            lat=actual.latitude;lon=actual.longitude;scale=actual.metresToPixels;rotation=actual.rotationRadians
            onBoundsDecision(decision,source,surface)
        } else if(current.residentCatalogActive && !restoring) {
            beforeGoal?.let{lat=it.latitude;lon=it.longitude;scale=it.metresToPixels;rotation=it.rotationRadians}
            onBoundsDecision(ChartCameraBoundsDecision(requested,null,ChartCameraLimit.UNKNOWN_BOUNDS,null),source,surface);return 0
        }
        pendingDeadline=deadline;pendingAlternative=alternative?.let{region?.constrain(it,beforeGoal?:current.viewport)?.effective?:it}
        // Shared gesture math already returns a canonical angle. Re-normalizing it changes
        // the last bits on every admission and makes an absolute target non-idempotent.
        if(rotation < -PI || rotation > PI)rotation=atan2(sin(rotation),cos(rotation))
        val id=ring.submit(0,if(restoring)RESTORE else if(preview)TOUCH_PREVIEW else NORMAL,sequence,source,surface,lat,lon,scale,rotation,now)
        lastSubmittedId=id
        if ((diagnosticOrigin!=null || pendingDiagnosticOrigin!=null) &&
            ring.read(id, diagnosticProbe) && diagnosticProbe.state == CommandRing.State.ACCEPTED) {
            pendingDiagnosticId=id; pendingDiagnosticEpoch=ownerEpoch
            pendingDiagnosticOrigin=diagnosticOrigin?.takeIf { it.source==source && it.surface==surface }
        }
        // A rejected command cannot replace the diagnostic sidecar of an older accepted goal.
        return id
    }
    /** Zoom/rotate step sizes belong to the camera control, equally usable by touch and hardware. */
    fun submit(control:ChartCameraControl, amount:Double, sequence:Long, stamp:Long,
        fixLat:Double=Double.NaN, fixLon:Double=Double.NaN, homeScale:Double=Double.NaN,expectedSource:Long=-1,expectedSurface:Long=-1):Long = synchronized(goalLock) {
        if(!amount.isFinite())return 0
        if(control==ChartCameraControl.CENTER && !positionValid(fixLat,fixLon))return 0
        if(control==ChartCameraControl.CENTER && navigation.get(state().navigationBounds)?.contains(fixLat,fixLon)==false)return 0
        if(control==ChartCameraControl.RESET && (!homeScale.isFinite()||homeScale<=0))return 0
        if(!begin(sequence,stamp,Long.MAX_VALUE,expectedSource,expectedSurface))return 0
        val now=admissionNow
        when(control) {
            ChartCameraControl.ZOOM_STEPS -> scale=(scale*1.25.pow(amount.coerceIn(-64.0,64.0))).coerceIn(.00001,10.0)
            ChartCameraControl.ROTATE_STEPS -> rotation+=(amount%72.0)*PI/36
            ChartCameraControl.CENTER -> {lat=fixLat;lon=fixLon}
            ChartCameraControl.NORTH_UP -> rotation=0.0
            ChartCameraControl.RESET -> {rotation=0.0;scale=homeScale.coerceIn(.00001,10.0)}
        }
        enqueue(sequence,now)
    }
    fun absolute(viewport:ChartViewport, sequence:Long, stamp:Long, preview:Boolean=false,expectedSource:Long=-1,expectedSurface:Long=-1,diagnosticOrigin:CameraDiagnosticOrigin?=null):Long = synchronized(goalLock) {
        if(!cameraValid(viewport))return 0
        if(!begin(sequence,stamp,Long.MAX_VALUE,expectedSource,expectedSurface))return 0
        val now=admissionNow
        lat=viewport.latitude;lon=viewport.longitude;scale=viewport.metresToPixels;rotation=viewport.rotationRadians
        enqueue(sequence,now,preview,diagnosticOrigin=diagnosticOrigin)
    }
    /** Explicit non-interactive initialization; cannot authorize touch or hardware without a Surface. */
    fun restore(viewport:ChartViewport,sequence:Long,stamp:Long,expectedSource:Long,deadline:Long):Long=synchronized(goalLock) {
        val state=state()
        if(!cameraValid(viewport)||state.surfaceAttached||state.residentCatalogActive)return 0
        if(!begin(sequence,stamp,deadline,expectedSource,state.surfaceGeneration,requireSurface=false))return 0
        lat=viewport.latitude;lon=viewport.longitude;scale=viewport.metresToPixels;rotation=viewport.rotationRadians
        enqueue(sequence,admissionNow,deadline=deadline,restoring=true)
    }
    fun motion(motion:ChartMotion, geometry:ChartSurfaceGeometry, sequence:Long, stamp:Long,expectedSource:Long=-1,expectedSurface:Long=-1,diagnosticOrigin:CameraDiagnosticOrigin?=null):Long = synchronized(goalLock) {
        if(!begin(sequence,stamp,Long.MAX_VALUE,expectedSource,expectedSurface))return 0
        val now=admissionNow
        val basis=ChartViewport(lat,lon,scale,rotation,width,height)
        val anchor=geometry.toFrame(motion.anchor)
        val wanted=transformChartCamera(basis,anchor,ScreenPoint(motion.pan.x/geometry.scale,motion.pan.y/geometry.scale),motion.zoom,motion.rotation)
        lat=wanted.latitude;lon=wanted.longitude;scale=wanted.metresToPixels;rotation=wanted.rotationRadians
        val alternative=if(motion.zoom!=1.0)transformChartCamera(basis,anchor,
            ScreenPoint(motion.pan.x/geometry.scale,motion.pan.y/geometry.scale),1.0,motion.rotation) else null
        enqueue(sequence,now,alternative=alternative,diagnosticOrigin=diagnosticOrigin)
    }
    fun adjustment(command:ChartCameraAdjustment, sequence:Long, stamp:Long,expectedSource:Long=-1,expectedSurface:Long=-1,diagnosticOrigin:CameraDiagnosticOrigin?=null):Long = synchronized(goalLock) {
        if(!command.zoomFactor.isFinite() || command.zoomFactor<=0 || command.rotationRadians?.isFinite()==false ||
            command.center?.let{!positionValid(it.latitude,it.longitude)}==true)return 0
        if(!begin(sequence,stamp,command.deadlineUptimeMs,expectedSource,expectedSurface))return 0
        val now=admissionNow
        command.center?.let{lat=it.latitude;lon=it.longitude}
        scale=(scale*command.zoomFactor).coerceIn(.00001,10.0)
        command.rotationRadians?.let{rotation=it}
        enqueue(sequence,now,deadline=command.deadlineUptimeMs,diagnosticOrigin=diagnosticOrigin)
    }
    internal fun drainOne() {
        synchronized(goalLock) {
            // Readiness controls execution only. Admission retains the latest accumulated target.
            if(closed || !state().residentCameraReady || !ring.take(work))return
            preparing=true;runningId=work.id;runningEpoch=ownerEpoch;runningDeadline=pendingDeadline;runningAlternative=pendingAlternative
            drainedDiagnosticOrigin = if (pendingDiagnosticId==work.id && pendingDiagnosticEpoch==ownerEpoch)
                pendingDiagnosticOrigin?.takeIf { it.source==work.generation && it.surface==work.surface } else null
            pendingDiagnosticOrigin=null; pendingDiagnosticId=0; pendingDiagnosticEpoch=-1
        }
        // Never hold goalLock while the camera owner or renderer is occupied.
        try { if(!canCommit(work.id) || !commit(work.id,work)) {
            val current=state()
            val reason=when {
                closed -> CommandRing.Reason.CLOSED
                runningEpoch!=ownerEpoch -> CommandRing.Reason.FOCUS_CHANGED
                work.generation!=current.chartSourceGeneration||work.surface!=current.surfaceGeneration -> CommandRing.Reason.SOURCE_CHANGED
                else -> CommandRing.Reason.UNAVAILABLE
            }
            ring.finish(work.id,if(reason==CommandRing.Reason.FOCUS_CHANGED||reason==CommandRing.Reason.CLOSED)CommandRing.State.CANCELLED else CommandRing.State.REJECTED,
                reason,-1,clock.now())
            synchronized(goalLock){if(lastSubmittedId==work.id)basisRevision=-1}
        } }
        catch(_:Exception) { ring.finish(work.id,CommandRing.State.UNKNOWN,CommandRing.Reason.COMPLETION_UNKNOWN,-1,clock.now()) }
        finally { drainedDiagnosticOrigin=null;runningId=0;preparing=false }
    }
    /** Called again under the camera lock immediately before camera ownership changes. */
    fun canCommit(id:Long):Boolean = !closed && id==runningId && runningEpoch==ownerEpoch && clock.now()<runningDeadline
    fun noZoomAlternative(id:Long):ChartViewport?=if(id==runningId)runningAlternative else null
    fun runningInputOrigin(id:Long):CameraDiagnosticOrigin? =
        if(id==runningId && runningEpoch==ownerEpoch) drainedDiagnosticOrigin else null
    fun nativeInputOrigin(cameraRevision:Long):CameraDiagnosticOrigin? =
        if(nativeTarget.id>0 && nativeTarget.revision==cameraRevision) nativeDiagnosticOrigin else null
    /** Caller holds cameraLock; releasing diagnostic refs never alters a command/result. */
    fun retireInputOrigins(capture:RendererLiveInputBuffer) = synchronized(goalLock) {
        if(pendingDiagnosticOrigin?.capture===capture){pendingDiagnosticOrigin=null;pendingDiagnosticId=0;pendingDiagnosticEpoch=-1}
        if(drainedDiagnosticOrigin?.capture===capture)drainedDiagnosticOrigin=null
        if(preparedDiagnosticOrigin?.capture===capture)preparedDiagnosticOrigin=null
        if(nativeDiagnosticOrigin?.capture===capture)nativeDiagnosticOrigin=null
    }
    /** Called under the camera owner lock, before its pending native pose is replaced. */
    fun prepared(id:Long,cameraRevision:Long,actual:ChartViewport?=null) {
        // Retain the running owner's identity even after its public result ages out of the 256-row history.
        // Otherwise a long blocked render could leave an unretained RUNNING slot occupied forever.
        if(preparedTarget.id>0&&preparedTarget.id!=id)ring.supersedeBeforeEffect(preparedTarget.id,id,clock.now())
        preparedDiagnosticOrigin=runningInputOrigin(id)
        preparedTarget.id=id;preparedTarget.revision=cameraRevision;preparedTarget.key=work.kind;preparedTarget.generation=work.generation
        preparedTarget.a=actual?.latitude?:work.a;preparedTarget.b=actual?.longitude?:work.b
        preparedTarget.c=actual?.metresToPixels?:work.c;preparedTarget.d=actual?.rotationRadians?:work.d
        preparedTarget.kind=if(abs(preparedTarget.a-work.a)>1e-9||abs(preparedTarget.b-work.b)>1e-9||abs(preparedTarget.c-work.c)>1e-9)1 else 0
        ring.prepared(id,cameraRevision)
        synchronized(goalLock) {
            basisRevision=cameraRevision
            if(actual!=null&&lastSubmittedId==id){lat=actual.latitude;lon=actual.longitude;scale=actual.metresToPixels;rotation=actual.rotationRadians}
        }
    }
    fun unchanged(id:Long,cameraRevision:Long,actual:ChartViewport) {
        actualScale=actual.metresToPixels
        ring.finish(id,CommandRing.State.APPLIED,CommandRing.Reason.BOUND_REACHED,cameraRevision,clock.now())
        synchronized(goalLock){basisRevision=cameraRevision
            if(lastSubmittedId==id){lat=actual.latitude;lon=actual.longitude;scale=actual.metresToPixels;rotation=actual.rotationRadians}}
    }
    fun beginNative(cameraRevision:Long,requested:ChartViewport?=null) {
        if(preparedTarget.id<=0)return
        // Attaching the first EGL Surface advances the camera epoch. Rebind only the exact saved
        // camera already owned by this same source; live commands never receive this exception.
        if(preparedTarget.revision!=cameraRevision && preparedTarget.key==RESTORE && requested!=null &&
            preparedTarget.generation==state().chartSourceGeneration && cameraRevision>preparedTarget.revision &&
            abs(requested.latitude-preparedTarget.a)<1e-9&&abs(requested.longitude-preparedTarget.b)<1e-9&&
            abs(requested.metresToPixels-preparedTarget.c)<1e-9&&abs(requested.rotationRadians-preparedTarget.d)<1e-9) {
            preparedTarget.revision=cameraRevision;ring.prepared(preparedTarget.id,cameraRevision)
        }
        if(preparedTarget.revision!=cameraRevision||!ring.beginEffect(preparedTarget.id))return
        // Never silently lose an earlier operation which already crossed the native boundary.
        // Its effect cannot honestly be called SUPERSEDED, and an unobserved completion is not replayable.
        if(nativeTarget.id>0&&nativeTarget.id!=preparedTarget.id)nativeFailed(nativeTarget.revision)
        nativeDiagnosticOrigin=preparedDiagnosticOrigin
        nativeTarget.id=preparedTarget.id;nativeTarget.revision=cameraRevision;nativeTarget.kind=preparedTarget.kind
        nativeTarget.a=preparedTarget.a;nativeTarget.b=preparedTarget.b;nativeTarget.c=preparedTarget.c;nativeTarget.d=preparedTarget.d
    }
    fun nativeFailed(cameraRevision:Long) {
        if(nativeTarget.id<=0||nativeTarget.revision!=cameraRevision)return
        ring.finish(nativeTarget.id,CommandRing.State.UNKNOWN,CommandRing.Reason.COMPLETION_UNKNOWN,nativeTarget.revision,clock.now())
        if(preparedTarget.id==nativeTarget.id)preparedDiagnosticOrigin=null
        nativeDiagnosticOrigin=null;nativeTarget.id=0
    }
    fun nativeApplied(cameraRevision:Long,actual:ChartViewport) {
        if(cameraRevision<appliedRevision||nativeTarget.id>0&&cameraRevision<nativeTarget.revision)return
        appliedRevision=cameraRevision;actualScale=actual.metresToPixels
        if(nativeTarget.id>0&&nativeTarget.revision<=cameraRevision) {
            val exact=nativeTarget.revision==cameraRevision&&abs(actual.latitude-nativeTarget.a)<1e-9&&
                abs(actual.longitude-nativeTarget.b)<1e-9&&abs(actual.metresToPixels-nativeTarget.c)<1e-9&&
                abs(actual.rotationRadians-nativeTarget.d)<1e-9
            ring.finish(nativeTarget.id,if(exact)CommandRing.State.APPLIED else CommandRing.State.UNKNOWN,
                if(!exact)CommandRing.Reason.COMPLETION_UNKNOWN else if(nativeTarget.kind==1)CommandRing.Reason.BOUND_REACHED else CommandRing.Reason.NONE,
                nativeTarget.revision,clock.now())
            if(preparedTarget.id==nativeTarget.id)preparedDiagnosticOrigin=null
            nativeDiagnosticOrigin=null;nativeTarget.id=0
        }
    }
    fun presented(cameraRevision:Long){if(cameraRevision>=0)presentedRevision=maxOf(presentedRevision,cameraRevision)}
    /** Camera owner holds cameraLock before entering goalLock. No callback is made by this query. */
    fun idleForMeasurement(): Boolean = synchronized(goalLock) { idleUnderGoalLock() }
    private fun idleUnderGoalLock(): Boolean {
        if (closed || exclusiveAdmission != null || preparing || runningId != 0L || ring.pending() != 0 || nativeTarget.id > 0) return false
        if (preparedTarget.id > 0 && preparedTarget.revision > appliedRevision) synchronized(resultLock) {
            if (!ring.read(preparedTarget.id, idleRow) || !idleRow.terminal()) return false
        }
        return true
    }
    /** The caller prepares all IDs/status outside this lock. Activation only changes memory.
     * A one-slot token rejects not-yet-accepted input until active status is published outside goalLock.
     * Existing queued/preparing/native work is never cancelled or drained to obtain admission. */
    fun admitMeasurementWhenIdle(owner: Any, activate: () -> Boolean): Boolean = synchronized(goalLock) {
        if (!idleUnderGoalLock() || !activate()) false else { exclusiveAdmission = owner; true }
    }
    fun releaseMeasurementAdmission(owner: Any): Boolean = synchronized(goalLock) {
        if (exclusiveAdmission !== owner) false else { exclusiveAdmission = null; true }
    }
    fun read(id:Long,destination:ChartControlResult):Boolean = synchronized(resultLock) {
        if(!ring.read(id,resultRow))return false
        destination.id=resultRow.id;destination.replacementId=resultRow.replacementId;destination.sequence=resultRow.sequence;destination.state=resultRow.state.ordinal;destination.reason=resultRow.reason.name
        destination.revision=resultRow.revision;destination.appliedRevision=appliedRevision;destination.presentedRevision=presentedRevision
        destination.desiredScale=resultRow.c;destination.actualScale=actualScale
        true
    }
    fun cancel(){synchronized(goalLock){ownerEpoch++;ring.cancelPending(CommandRing.Reason.FOCUS_CHANGED,clock.now());basisRevision=-1
        pendingDiagnosticOrigin=null;pendingDiagnosticId=0;pendingDiagnosticEpoch=-1}}
    override fun close(){closed=true;cancel();worker.shutdownNow()}
    private fun positionValid(latitude:Double,longitude:Double)=latitude.isFinite()&&longitude.isFinite()&&latitude in -85.0..85.0&&longitude in -180.0..180.0
    private fun cameraValid(view:ChartViewport)=positionValid(view.latitude,view.longitude)&&view.metresToPixels.isFinite()&&view.metresToPixels in .00001..10.0&&view.rotationRadians.isFinite()&&view.width>0&&view.height>0
}
