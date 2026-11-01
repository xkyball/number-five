package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicReference

/** Actual engine input/gate code, with a seeded ready state and no initialized JNI/GL engine. */
class NativeTouchRenderingModeTest {
    private lateinit var engine:NativeChartEngine
    private var cameraNow=100L
    private val home=ChartViewport(42.36,-71.04,.15)
    private val frame=ChartFrame(1,"seeded",ChartSurfaceGeometry.fit(2000,1200),home,null)
    private fun field(name:String)=NativeChartEngine::class.java.getDeclaredField(name).apply { isAccessible=true }
    @Suppress("UNCHECKED_CAST") private fun state()=field("mutable").get(engine) as MutableStateFlow<ChartEngineStatus>
    private fun gate()=field("touchFrames").get(engine) as TouchFrameGate
    private fun proofLost()=NativeChartEngine::class.java.getDeclaredMethod("disableSourceTimestampProof")
        .apply { isAccessible=true }.invoke(engine)
    private fun expire(buffer:TouchFrameBuffer)=NativeChartEngine::class.java
        .getDeclaredMethod("abandonUnconfirmedOutline",TouchFrameBuffer::class.java)
        .apply { isAccessible=true }.invoke(engine,buffer)
    @Before fun setup() {
        cameraNow=100L;engine=NativeChartEngine(CameraControlClock {cameraNow});field("touchTimestampSupported").setBoolean(engine,true)
        field("presentedCameraRevision").setLong(engine,0)
        field("surfaceGeometry").set(engine,frame.geometry)
        state().value=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,viewport=home,confirmedFrame=frame,
            touchOutlinesAvailable=true,outlines=ChartOutlines(GeoProjection(42.36,-71.04),floatArrayOf(-100f,0f,100f,0f),20.0,"seeded"))
        CameraControlActor::class.java.getDeclaredField("worker").apply{isAccessible=true}
            .get(camera()).let{(it as java.util.concurrent.ScheduledExecutorService).shutdownNow()}
    }
    private fun camera():CameraControlActor=NativeChartEngine::class.java.getDeclaredMethod("getCameraControl")
        .apply{isAccessible=true}.invoke(engine) as CameraControlActor
    private fun drain()=camera().drainOne()
    @After fun close() { engine.close() }
    @Test fun idleModeChangesRetainTheConfirmedFrameWithoutAnUnnecessaryRenderOrZoomBlocker() {
        for(enabled in listOf(false,true)) {
            assertTrue(engine.setTouchOutlinesEnabled(enabled));assertSame(frame,engine.status.value.confirmedFrame)
            assertFalse((field("renderDirty").get(engine) as java.util.concurrent.atomic.AtomicBoolean).get())
        }
    }
    @Test fun disabledTouchUsesTheSameCoalescedCameraOwnerDespiteReadyOutlineCache() {
        assertTrue(engine.setTouchOutlinesEnabled(false));state().value=state().value.copy(confirmedFrame=frame)
        assertFalse(engine.beginTouchGesture());assertFalse(gate().held.get())
        val unexpected=home.copy(longitude=-72.0);engine.updateTouchViewport(unexpected)
        val first=ChartMotion.anchored(ScreenPoint(600.0,300.0),ScreenPoint(60.0,0.0),1.2,.2)
        val second=ChartMotion.anchored(ScreenPoint(600.0,300.0),ScreenPoint(20.0,10.0),.9,-.1)
        engine.moveCamera(first);engine.moveCamera(second);engine.endTouchGesture()
        drain()
        val wanted=transformChartCamera(transformChartCamera(home,first.anchor,first.pan,first.zoom,first.rotation),
            second.anchor,second.pan,second.zoom,second.rotation)
        assertEquals(wanted,engine.status.value.viewport)
        assertEquals(emptyList<ChartMotion>(),field("pendingMotions").get(engine))
        assertEquals(wanted,(field("pendingViewport").get(engine) as AtomicReference<*>).get())
        assertNull(engine.status.value.touchPreview);assertNull(gate().current())
    }
    @Test fun disablingHeldPreviewKeepsLatestCameraAndReleasesGateForFullRendering() {
        assertTrue(engine.beginTouchGesture());val latest=home.copy(longitude=-71.08,metresToPixels=.23,rotationRadians=.4)
        engine.updateTouchViewport(latest);drain();assertTrue(gate().held.get())
        assertTrue(engine.setTouchOutlinesEnabled(false))
        assertFalse(gate().held.get());assertNull(gate().current());assertNull(engine.status.value.touchPreview)
        assertEquals(latest,engine.status.value.viewport)
        assertEquals(latest,(field("pendingViewport").get(engine) as AtomicReference<*>).get())
        assertTrue(field("renderDirty").get(engine).let { (it as java.util.concurrent.atomic.AtomicBoolean).get() })
        engine.endTouchGesture();assertFalse(engine.beginTouchGesture())
    }
    @Test fun lastUpOrCancelPendingBufferCannotRevivePreviewAfterDisableAndReenable() {
        assertTrue(engine.beginTouchGesture());engine.updateTouchViewport(home.copy(longitude=-71.05));drain();engine.endTouchGesture()
        val old=gate().begin(1,123)!!;gate().swapped(old,frame)
        assertTrue(engine.setTouchOutlinesEnabled(false));engine.bufferConsumed(123)
        assertNull(engine.status.value.confirmedFrame);assertNull(engine.status.value.touchPreview);assertNull(gate().current())
        assertFalse(gate().valid(old,1));assertTrue(engine.setTouchOutlinesEnabled(true))
        state().value=state().value.copy(confirmedFrame=frame);assertTrue(engine.beginTouchGesture())
        engine.bufferConsumed(123);assertTrue(gate().held.get());assertTrue(engine.status.value.touchPreview!!.held)
    }
    @Test fun recordingBenchmarkRecoveryAndProfilingRejectModeChanges() {
        val original=state().value
        for(blocked in listOf(original.copy(rendererCapture=RendererCaptureStatus(active=true)),
            original.copy(rendererBenchmark=RendererBenchmarkStatus(active=true)),original.copy(rendererBenchmarkRecoveryPending=true))) {
            state().value=blocked;assertFalse(engine.setTouchOutlinesEnabled(false));assertTrue(engine.status.value.touchOutlinesEnabled)
        }
        state().value=original;field("profiling").setBoolean(engine,true);assertFalse(engine.setTouchOutlinesEnabled(false))
        field("profiling").setBoolean(engine,false);assertTrue(engine.setTouchOutlinesEnabled(false))
    }
    @Test fun surfaceDetachInvalidatesTouchButRetainsTheSelectedOffMode() {
        assertTrue(engine.beginTouchGesture());engine.updateTouchViewport(home.copy(rotationRadians=.2));drain()
        engine.detachSurface();assertFalse(gate().held.get());assertNull(engine.status.value.touchPreview);assertFalse(engine.status.value.touchOutlinesAvailable)
        assertTrue(engine.setTouchOutlinesEnabled(false));engine.detachSurface()
        assertFalse(engine.status.value.touchOutlinesEnabled);assertFalse(engine.beginTouchGesture())
    }
    @Test fun runtimeTaggedFailureAdvertisesLostProofAndEndsTheGestureWithoutChangingSource() {
        state().value=state().value.copy(chartSourceGeneration=7,chartContentGeneration=12)
        assertTrue(engine.beginTouchGesture())
        proofLost()
        assertTrue(engine.status.value.sourcePresentationUnsupported)
        assertFalse(field("touchTimestampSupported").getBoolean(engine))
        assertFalse(engine.status.value.touchOutlinesAvailable)
        assertFalse(gate().held.get());assertNull(engine.status.value.touchPreview)
        assertEquals(7L,engine.status.value.chartSourceGeneration)
        assertEquals(12L,engine.status.value.chartContentGeneration)
    }
    @Test fun unconfirmedOutlineTimeoutAdvertisesLostProofUntilSurfaceRetry() {
        val pending=gate().begin(0,123)!!;gate().swapped(pending,frame)
        expire(pending)
        assertTrue(engine.status.value.sourcePresentationUnsupported)
        assertFalse(field("touchTimestampSupported").getBoolean(engine))
        assertNull(engine.status.value.confirmedFrame);assertNull(gate().current())
        NativeChartEngine::class.java.getDeclaredMethod("resetWorkerSurface")
            .apply { isAccessible=true }.invoke(engine)
        assertFalse(engine.status.value.sourcePresentationUnsupported)
        assertFalse(engine.status.value.surfaceAttached)
    }
    @Test fun staleOutlineTimeoutCannotDisableTheNewSourceOrItsCurrentBuffer() {
        val old=gate().begin(0,123)!!;gate().swapped(old,frame)
        engine.interruptTouchGesture()
        state().value=state().value.copy(chartSourceGeneration=8,chartContentGeneration=20)
        val current=gate().begin(1,124)!!
        expire(old)
        assertFalse(engine.status.value.sourcePresentationUnsupported)
        assertTrue(field("touchTimestampSupported").getBoolean(engine))
        assertSame(current,gate().current())
        assertEquals(8L,engine.status.value.chartSourceGeneration)
        assertEquals(20L,engine.status.value.chartContentGeneration)
    }
    @Test fun unchangedCameraRetainsFrameAndDecisionWithoutRevisionOrRenderWork() {
        val hint=ChartResidentCameraDecision(home,ChartResidentCameraReason.NO_ZOOM_ALTERNATIVE,
            3,25,"a".repeat(64),7,1)
        state().value=state().value.copy(residentCameraDecision=hint)
        val before=engine.bufferTransformCameraRevision()
        engine.setViewport(home)
        engine.moveCamera(ChartMotion.anchored(ScreenPoint(500.0,300.0)))
        assertEquals(before,engine.bufferTransformCameraRevision())
        assertSame(frame,engine.status.value.confirmedFrame)
        assertSame(hint,engine.status.value.residentCameraDecision)
        assertFalse((field("renderDirty").get(engine) as java.util.concurrent.atomic.AtomicBoolean).get())
    }
    @Test fun coldNonresidentCameraRestoreTransfersOwnershipBeforeFirstSurfaceWithoutJNI()=runBlocking {
        val saved=home.copy(latitude=-33.91,longitude=18.42,metresToPixels=.23,rotationRadians=.4)
        state().value=state().value.copy(surfaceAttached=false,confirmedFrame=null,chartSourceGeneration=9)
        field("surfaceGeometry").set(engine,null)
        engine.setViewport(saved);drain()
        assertEquals("Live camera input still needs an attached Surface",home,engine.status.value.viewport)
        assertFalse(engine.restoreCameraViewport(saved,8))
        val restore=async(start=CoroutineStart.UNDISPATCHED){engine.restoreCameraViewport(saved,9)}
        drain();assertTrue(restore.await())
        assertEquals(saved,engine.status.value.viewport)
        assertEquals(saved,(field("pendingViewport").get(engine) as AtomicReference<*>).get())
        assertEquals("Ownership is not a native/frame acknowledgement",-1L,camera().appliedRevision)
    }

    @Test fun engineAndOwnerUseTheSameMonotonicClockForPhysicalFreshness() {
        cameraNow=400L
        assertEquals(0L,engine.submitCameraControl(ChartCameraControl.ZOOM_STEPS,1.0,1,100))
        drain();assertEquals(home,engine.status.value.viewport)
        val fresh=engine.submitCameraControl(ChartCameraControl.ZOOM_STEPS,1.0,2,400)
        assertTrue(fresh>0);drain()
        assertEquals(home.metresToPixels*1.25,engine.status.value.viewport.metresToPixels,1e-12)
    }

}
