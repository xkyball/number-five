package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.lumina.control.commands.v1.CommandRing
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ScheduledExecutorService
import kotlinx.serialization.json.*
import kotlin.concurrent.thread

/** Actual camera inbox/application code; only the native set+pose read is replaced, never rendering. */
class NativeCameraCompletionTest {
    private lateinit var engine:NativeChartEngine
    private val nativeCalls=mutableListOf<ChartViewport>()
    private var failReadback=false
    private var changeActualPose=false
    private fun field(name:String)=NativeChartEngine::class.java.getDeclaredField(name).apply{isAccessible=true}
    @Suppress("UNCHECKED_CAST") private fun state()=field("mutable").get(engine) as MutableStateFlow<ChartEngineStatus>
    private fun camera()=NativeChartEngine::class.java.getDeclaredMethod("getCameraControl").apply{isAccessible=true}.invoke(engine) as CameraControlActor
    private fun apply() {NativeChartEngine::class.java.getDeclaredMethod("applyPendingViewport").apply{isAccessible=true}.invoke(engine)}
    private fun result(id:Long)=ChartControlResult().also{assertTrue(engine.cameraControlResult(id,it))}
    private fun zoom(sequence:Long):Long {
        val id=engine.submitCameraControl(ChartCameraControl.ZOOM_STEPS,1.0,sequence,100)
        assertTrue(id>0);camera().drainOne();return id
    }
    @Before fun setup() {
        engine=NativeChartEngine(CameraControlClock{100L},NativeCameraApplication{_,desired ->
            nativeCalls+=desired
            check(!failReadback){"pose readback failed after native set"}
            if(changeActualPose)desired.copy(metresToPixels=desired.metresToPixels*.9) else desired
        })
        field("surfaceGeometry").set(engine,ChartSurfaceGeometry.fit(2000,1200))
        state().value=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,viewport=ChartViewport(-33.9,18.4,.01))
        CameraControlActor::class.java.getDeclaredField("worker").apply{isAccessible=true}.get(camera())
            .let{(it as ScheduledExecutorService).shutdownNow()}
    }
    @After fun close(){engine.close()}

    @Test fun planningApplicationCompletesEvenWhenSubsequentRenderHasNoWritableSlot() {
        val first=zoom(1)
        apply() // The same camera-application entry used by readResidentPlanningViewport.
        assertEquals(CommandRing.State.APPLIED.ordinal,result(first).state)
        assertEquals(1,nativeCalls.size)
        assertEquals(-1L,result(first).presentedRevision)

        // Exercise the real render gate: it returns before rendering/refresh when both image slots are held.
        field("pairedPresentation").setBoolean(engine,true)
        field("bufferTransformMode").set(engine,ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS)
        val gate=field("presentationGate").get(engine) as ChartPresentationGate
        gate.reset(0);assertTrue(gate.reserve(0,1,123))
        NativeChartEngine::class.java.getDeclaredMethod("renderAndRefresh",Long::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
            .apply{isAccessible=true}.invoke(engine,0L,true)
        assertTrue(gate.busy())
        assertEquals(CommandRing.State.APPLIED.ordinal,result(first).state)
        assertNull(engine.status.value.confirmedFrame)
        assertEquals(-1L,result(first).presentedRevision)

        val second=zoom(2);apply()
        assertEquals(2,nativeCalls.size)
        assertEquals(CommandRing.State.APPLIED.ordinal,result(first).state)
        assertEquals(CommandRing.State.APPLIED.ordinal,result(second).state)
        assertEquals(.015625,result(second).actualScale,1e-12)
        assertEquals(-1L,result(second).presentedRevision)
    }
    @Test fun failedNativeReadbackIsTerminalUnknownWithoutAnotherInputOrReplay() {
        val id=zoom(1);failReadback=true
        try {apply();fail("Missing native pose proof must not succeed")}
        catch(failure:InvocationTargetException){assertEquals("pose readback failed after native set",failure.cause?.message)}
        val result=result(id)
        assertEquals(CommandRing.State.UNKNOWN.ordinal,result.state)
        assertEquals("COMPLETION_UNKNOWN",result.reason)
        assertEquals(-1L,result.appliedRevision)
        assertEquals(1,nativeCalls.size)
        camera().drainOne();assertEquals("Unknown completion must never replay the native command",1,nativeCalls.size)
    }
    @Test fun nativePoseMismatchCannotBeConfirmedAsApplied() {
        val id=zoom(1);changeActualPose=true;apply()
        val result=result(id)
        assertEquals(CommandRing.State.UNKNOWN.ordinal,result.state)
        assertEquals("COMPLETION_UNKNOWN",result.reason)
        assertEquals(.01125,result.actualScale,1e-12)
        assertEquals(-1L,result.presentedRevision)
    }

    private fun input(buffer:RendererLiveInputBuffer):Long = buffer.input(100, 200, 202, 101, 2, 7, -1, 1,
        intArrayOf(7),floatArrayOf(10f,20f),RendererInputOrigin.VIEW_INJECTED)
    private fun capture(buffer:RendererLiveInputBuffer) { field("liveInputBuffer").set(engine,buffer); buffer.startLive() }
    private fun cameraRows(report:JsonObject,kind:String)=report.getValue("rows").jsonArray
        .map { it.jsonArray }.filter { it[0].jsonPrimitive.content==kind }
    private fun appliedInput()=NativeChartEngine::class.java.getDeclaredMethod("getAppliedLiveInputId")
        .apply{isAccessible=true}.invoke(engine) as Long
    private fun absolute(buffer:RendererLiveInputBuffer,scale:Double):Long = buffer.withInput(input(buffer)) {
        engine.submitCameraViewport(state().value.viewport.copy(metresToPixels=scale),0,100)
    }

    @Test fun actualEngineRetainsUiOriginAcrossActorWorkerAndRecordsActualNativeReadbackNotAck() {
        val buffer=RendererLiveInputBuffer("actual-engine");capture(buffer)
        val command=absolute(buffer,.02);assertTrue(command>0)
        val worker=thread { assertEquals(0L,buffer.currentInput());camera().drainOne() }
        worker.join(2000);assertFalse(worker.isAlive)
        changeActualPose=true;apply()
        assertEquals(CommandRing.State.UNKNOWN.ordinal,result(command).state)
        assertEquals(1L,appliedInput())
        val report=buffer.finishReport()
        val accepted=cameraRows(report,"CAMERA_ACCEPTED").single()
        val returned=cameraRows(report,"CAMERA_APPLIED").single()
        assertEquals(1L,accepted[2].jsonPrimitive.long)
        assertEquals(1L,returned[2].jsonPrimitive.long)
        assertEquals(accepted[3],returned[3]) // Exact camera revision, not latest input.
        assertEquals(.02,accepted[7].jsonArray[2].jsonPrimitive.double,1e-12)
        assertEquals(.018,returned[7].jsonArray[2].jsonPrimitive.double,1e-12)
        assertEquals("OWNER_CAMERA_COMMIT_NOT_COMMAND_RING_RECEIPT",report.getValue("cameraAcceptedScope").jsonPrimitive.content)
        assertEquals("NATIVE_SET_READBACK_RETURN_NOT_COMMAND_RESULT",report.getValue("cameraAppliedScope").jsonPrimitive.content)
        assertEquals("ACTUAL_NATIVE_READBACK",report.getValue("cameraAppliedValues").jsonPrimitive.content)
        assertEquals("NOT_MEASURED",report.getValue("commandReceiptLatency").jsonPrimitive.content)
    }

    @Test fun actualEngineCoalescedPendingTargetHasOnlyItsOwnOriginAndExistingSupersessionResult() {
        val buffer=RendererLiveInputBuffer("coalesced-engine");capture(buffer)
        val first=absolute(buffer,.02);val second=absolute(buffer,.03)
        assertEquals(CommandRing.State.SUPERSEDED.ordinal,result(first).state)
        camera().drainOne();apply()
        val report=buffer.finishReport()
        assertEquals(listOf(2L),cameraRows(report,"CAMERA_ACCEPTED").map{it[2].jsonPrimitive.long})
        assertEquals(listOf(2L),cameraRows(report,"CAMERA_APPLIED").map{it[2].jsonPrimitive.long})
        assertEquals(CommandRing.State.APPLIED.ordinal,result(second).state)
        assertEquals(CommandRing.State.SUPERSEDED.ordinal,result(first).state)
    }

    @Test fun lateNativeReturnFromRetiredCaptureCannotJoinReusedInputIdInNewCapture() {
        val old=RendererLiveInputBuffer("same-run");capture(old)
        val command=absolute(old,.02);camera().drainOne()
        old.finishReport()
        val current=RendererLiveInputBuffer("same-run");capture(current);assertEquals(1L,input(current))
        apply() // The old command still completes normally; the new capture receives no old camera rows.
        assertEquals(CommandRing.State.APPLIED.ordinal,result(command).state)
        assertEquals(0L,appliedInput())
        assertTrue(cameraRows(current.finishReport(),"CAMERA_APPLIED").isEmpty())
    }

    @Test fun exactAppliedRevisionSurvivesNoRequestRetryButNotNewUnattributedCamera() {
        val buffer=RendererLiveInputBuffer("retry-engine");capture(buffer)
        absolute(buffer,.02);camera().drainOne();apply()
        assertEquals(1L,appliedInput())
        apply() // Producer retry without another camera application keeps the same causal revision.
        assertEquals(1L,appliedInput());assertEquals(1,nativeCalls.size)
        engine.submitCameraViewport(state().value.viewport.copy(metresToPixels=.03),0,100)
        camera().drainOne();apply()
        assertEquals(0L,appliedInput())
        val report=buffer.finishReport()
        assertEquals(listOf(1L,0L),cameraRows(report,"CAMERA_APPLIED").map{it[2].jsonPrimitive.long})
    }

    @Test fun retiringDiagnosticReferencesDoesNotCancelPendingOrNativeUserCommands() {
        val buffer=RendererLiveInputBuffer("retire-engine");capture(buffer)
        val command=absolute(buffer,.02);camera().drainOne()
        NativeChartEngine::class.java.getDeclaredMethod("retireCameraOrigins",RendererLiveInputBuffer::class.java)
            .apply{isAccessible=true}.invoke(engine,buffer)
        apply()
        assertEquals(CommandRing.State.APPLIED.ordinal,result(command).state)
        assertEquals(0L,appliedInput())
        val report=buffer.finishReport()
        assertEquals(listOf(0L),cameraRows(report,"CAMERA_APPLIED").map{it[2].jsonPrimitive.long})
    }

    @Test fun failedReadbackCannotCreateAppliedOriginOrClaimSuccessfulNativeReturn() {
        val buffer=RendererLiveInputBuffer("failed-readback");capture(buffer)
        val command=absolute(buffer,.02);camera().drainOne();failReadback=true
        try { apply();fail("Missing readback must remain an exception") }
        catch(failure:InvocationTargetException){assertEquals("pose readback failed after native set",failure.cause?.message)}
        assertEquals(CommandRing.State.UNKNOWN.ordinal,result(command).state)
        assertEquals(0L,appliedInput())
        val report=buffer.finishReport()
        assertEquals(1,cameraRows(report,"CAMERA_ACCEPTED").size)
        assertTrue(cameraRows(report,"CAMERA_APPLIED").isEmpty())
    }
}
