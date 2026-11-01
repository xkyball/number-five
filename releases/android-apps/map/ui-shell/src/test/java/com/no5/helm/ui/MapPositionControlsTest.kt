package com.no5.helm.ui

import com.no5.helm.chart.*
import com.no5.helm.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.lumina.control.commands.v1.CommandRing
import java.lang.reflect.Proxy

/** Synthetic intent/camera receipts; no live position or native rendering claim. */
class MapPositionControlsTest {
    private class Fixture {
        var uptime=100L
        val tasks=ArrayDeque<()->Unit>()
        var cameraCalls=0
        var cancellations=0
        var cameraState=CommandRing.State.APPLIED
        var failCenter=false
        var beforeCameraAdmission:(()->Unit)?=null
        var capturedSource:Long?=null
        var capturedSurface:Long?=null
        val engineState=MutableStateFlow(ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,chartSourceGeneration=1,surfaceGeneration=1))
        val engine=Proxy.newProxyInstance(ChartEngine::class.java.classLoader,arrayOf(ChartEngine::class.java)){_,method,args->when(method.name){
            "getStatus"->engineState
            "cancelCameraControl"->{cancellations++;Unit}
            "submitCameraControl"->{
                beforeCameraAdmission?.let { beforeCameraAdmission=null;it() }
                if(args!!.size==9){
                    capturedSource=args[7] as Long;capturedSurface=args[8] as Long
                }
                if(args.size!=9||capturedSource!=engineState.value.chartSourceGeneration||capturedSurface!=engineState.value.surfaceGeneration)0L
                else {cameraCalls++;if(failCenter)error("synthetic completion unknown");cameraCalls.toLong()}
            }
            "cameraControlResult"->{val result=args!![1] as ChartControlResult;result.state=cameraState.ordinal;result.revision=5;result.appliedRevision=5;result.presentedRevision=5;true}
            else->error("Unexpected camera path ${method.name}")
        }} as ChartEngine
        val camera=ChartPositionCamera(engine)
        val controller=MapPositionController(camera)
        val controls=MapPositionControls(controller,engine,{uptime},{10000L},post={tasks.addLast(it)})
        init {
            val candidate=PositionCandidate("synthetic.gps","synthetic.gps","synthetic.gps",PositionStatus.LIVE,10000,GeoPoint(0.0,0.0))
            controller.update(HelmState(ConnectionState.LIVE,Snapshot(serverId="synthetic",revision=1,generatedAt="synthetic",state=HelmProjection(null,emptyList(),emptyList(),emptyList()),gatewayV1=true,
                positionSources=PositionSources(primarySourceId=candidate.sourceId,total=1,sources=listOf(candidate)))),10000)
            controls.observe()
        }
        fun run(){while(tasks.isNotEmpty())tasks.removeFirst().invoke()}
        fun result(id:Long)=ChartControlResult().also{assertTrue(controls.result(id,it))}
        fun cycle():Long=controls.cycle(1,uptime).also{run()}
    }
    @Test fun touchAndLeftShortKnobShareTheCycleAndOffHasItsOwnPositiveReceiptWithoutFakeCameraCommand(){
        val f=Fixture()
        try {
            val touch=f.controls.cycle();assertTrue(touch>MapPositionControls.PREFIX)
            assertEquals(PositionMode.OFF,f.controller.state.value.mode);f.run()
            assertEquals(PositionMode.CENTERED,f.controller.state.value.mode)
            val knob=submitChartKnobControl(f.engine,ChartKnobInput.LEFT_PUSH,2,f.uptime,Double.NaN,Double.NaN,.02,f.controls)
            assertTrue(knob>touch);f.run();assertEquals(PositionMode.FOLLOW,f.controller.state.value.mode)
            val off=f.cycle();assertEquals(PositionMode.OFF,f.controller.state.value.mode)
            val receipt=f.result(off)
            assertEquals(CommandRing.State.APPLIED.ordinal,receipt.state);assertEquals("NONE",receipt.reason)
            assertEquals(2,f.cameraCalls);assertEquals(-1L,receipt.appliedRevision);assertEquals(-1L,receipt.presentedRevision)
            assertTrue(f.controls.intentOnly(off));assertFalse(f.controls.intentOnly(touch));assertFalse(f.controls.intentOnly(knob))
        } finally { f.controls.close() }
    }
    @Test fun unknownCameraCompletionOrAlreadyStartedIntentIsNeverRetriedOrAdvertisedAsRejected(){
        val f=Fixture()
        try {
            f.cameraState=CommandRing.State.UNKNOWN
            val id=f.cycle();repeat(3){val receipt=f.result(id);assertEquals(CommandRing.State.UNKNOWN.ordinal,receipt.state);assertEquals("COMPLETION_UNKNOWN",receipt.reason)}
            assertEquals(1,f.cameraCalls)
            val g=Fixture();g.failCenter=true
            try {val failed=g.cycle();assertEquals(CommandRing.State.UNKNOWN.ordinal,g.result(failed).state);assertEquals(1,g.cameraCalls)} finally {g.controls.close()}
        } finally {f.controls.close()}
    }
    @Test fun queuedPositionIntentIsFencedBySourceSurfaceFocusAndItsDeadline(){
        for(boundary in listOf("source","surface","focus","deadline")){
            val f=Fixture()
            try {
                val id=f.controls.cycle(2,f.uptime)
                when(boundary){
                    "source"->f.controller.select(PositionChoice.MANUAL,"synthetic.gps",10000)
                    "surface"->f.engineState.value=f.engineState.value.copy(surfaceGeneration=2)
                    "focus"->f.controls.cancel()
                    else->f.uptime+=2000
                }
                f.run();assertEquals(boundary,CommandRing.State.CANCELLED.ordinal,f.result(id).state)
                assertEquals(boundary,0,f.cameraCalls);assertEquals(PositionMode.OFF,f.controller.state.value.mode)
                f.controls.observe();assertTrue(f.controls.cycle(3,f.uptime)>0);f.run()
                assertEquals(PositionMode.CENTERED,f.controller.state.value.mode)
            } finally {f.controls.close()}
        }
    }
    @Test fun positioningActionsRemainFifoWithin64PendingAnd256RetainedResults(){
        val f=Fixture()
        try {
            val ids=LongArray(65){f.controls.cycle(it.toLong(),f.uptime)}
            assertEquals(CommandRing.State.REJECTED.ordinal,f.result(ids.last()).state)
            assertEquals("BUSY",f.result(ids.last()).reason)
            f.run();assertEquals(PositionMode.CENTERED,f.controller.state.value.mode);assertEquals(43,f.cameraCalls)
            assertEquals(CommandRing.State.APPLIED.ordinal,f.result(ids[2]).state)
            assertEquals(CommandRing.State.APPLIED.ordinal,f.result(ids[63]).state)
        } finally {f.controls.close()}
    }
    @Test fun aChartSourceOrSurfaceChangeBetweenGuardAndActualCameraAdmissionCannotRebindTheCycle(){
        for(boundary in listOf("source","surface")){
            val f=Fixture()
            try {
                f.beforeCameraAdmission={f.engineState.value=if(boundary=="source")f.engineState.value.copy(chartSourceGeneration=2)else f.engineState.value.copy(surfaceGeneration=2)}
                val id=f.cycle()
                assertEquals(boundary,0,f.cameraCalls);assertEquals(PositionMode.OFF,f.controller.state.value.mode)
                assertEquals(CommandRing.State.REJECTED.ordinal,f.result(id).state)
                assertEquals(1L,f.capturedSource);assertEquals(1L,f.capturedSurface)
            }finally{f.controls.close()}
        }
    }
    @Test fun allPositionChartAndSurfaceGenerationsRemainExactAboveDoubleIntegerPrecision(){
        val f=Fixture();val high=9_007_199_254_740_993L
        try {
            MapPositionController::class.java.getDeclaredField("generation").apply{isAccessible=true}.setLong(f.controller,high+4)
            f.controller.tick(10000)
            f.engineState.value=f.engineState.value.copy(chartSourceGeneration=high,surfaceGeneration=high+2)
            f.controls.observe();val id=f.cycle()
            assertEquals(PositionMode.CENTERED,f.controller.state.value.mode);assertEquals(1,f.cameraCalls)
            assertEquals(high,f.capturedSource);assertEquals(high+2,f.capturedSurface)
            assertEquals(CommandRing.State.APPLIED.ordinal,f.result(id).state)
            val obsolete=f.controls.cycle(3,f.uptime)
            f.engineState.value=f.engineState.value.copy(surfaceGeneration=high+3)
            f.run();assertEquals(CommandRing.State.CANCELLED.ordinal,f.result(obsolete).state);assertEquals(1,f.cameraCalls)
        }finally{f.controls.close()}
    }
    @Test fun saturatedRejectedSubmissionsCannotOverwriteThe64AcceptedFenceSlots(){
        val f=Fixture()
        try {
            repeat(512){f.controls.cycle(it.toLong(),f.uptime)}
            f.run();assertEquals(PositionMode.CENTERED,f.controller.state.value.mode);assertEquals(43,f.cameraCalls)
        }finally{f.controls.close()}
    }

}
