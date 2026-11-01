package com.no5.helm.engine

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test
import org.lumina.control.commands.v1.CommandRing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CameraControlActorTest {
    @Test fun navigationEdgeDoesNotAccumulateHiddenZoomAndOverviewRotatesAsAWhole() {
        val bounds=ChartNavigationBounds(1,listOf(ChartCellExtent("A",-50.0,-10.0,-30.0,70.0)))
        val region=ChartCameraBounds.from(bounds)!!
        var status=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,residentCatalogActive=true,
            residentCameraReady=true,navigationBounds=bounds,viewport=region.overview(ChartViewport(width=2000,height=1070)))
        var revision=0L;lateinit var actor:CameraControlActor
        actor=CameraControlActor({status},{revision},{100L},false) { id,row ->
            revision++;status=status.copy(viewport=status.viewport.copy(latitude=row.a,longitude=row.b,metresToPixels=row.c,rotationRadians=row.d))
            actor.prepared(id,revision);true
        }
        try {
            val full=status.viewport
            for (sequence in 1L..100L) assertTrue(actor.submit(ChartCameraControl.ZOOM_STEPS,-64.0,sequence,100)>0)
            actor.drainOne();assertEquals(full,status.viewport)
            actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,101,100);actor.drainOne()
            assertEquals(full.metresToPixels*1.25,status.viewport.metresToPixels,1e-12)
            actor.absolute(full,102,100);actor.drainOne()
            actor.submit(ChartCameraControl.ROTATE_STEPS,18.0,103,100);actor.drainOne()
            assertEquals(region.overviewScale(2000,1070,Math.PI/2),status.viewport.metresToPixels,1e-12)
            assertEquals(0L,actor.submit(ChartCameraControl.CENTER,1.0,104,100,60.0,100.0))
            val preserved=status.viewport
            status=status.copy(navigationBounds=ChartNavigationBounds(2,emptyList()))
            assertEquals(0L,actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,105,100));actor.drainOne()
            assertEquals(preserved,status.viewport)
        } finally {actor.close()}
    }
    @Test fun burstsAccumulateIntentAndOnlyLatestPoseReachesNative() {
        var status=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,viewport=ChartViewport(metresToPixels=.01))
        var revision=0L;lateinit var actor:CameraControlActor;var commits=0
        actor=CameraControlActor({status},{revision},{100L},false) { id,row ->
            commits++;revision++;status=status.copy(viewport=status.viewport.copy(latitude=row.a,longitude=row.b,metresToPixels=row.c,rotationRadians=row.d))
            actor.prepared(id,revision);true
        }
        try {
            val ids=(1L..8).map { actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,it,100) }
            val result=ChartControlResult()
            assertTrue(actor.read(ids.first(),result));assertEquals(CommandRing.State.SUPERSEDED.ordinal,result.state)
            actor.drainOne();assertEquals(1,commits);assertEquals(.01*Math.pow(1.25,8.0),status.viewport.metresToPixels,1e-12)
            assertTrue(actor.read(ids.last(),result));assertEquals(CommandRing.State.RUNNING.ordinal,result.state)
            actor.beginNative(revision);actor.nativeApplied(revision,status.viewport)
            actor.read(ids.last(),result);assertEquals(CommandRing.State.APPLIED.ordinal,result.state);assertEquals(-1,result.presentedRevision)
            actor.presented(revision);actor.read(ids.last(),result);assertEquals(revision,result.presentedRevision)
        } finally {actor.close()}
    }
    @Test fun blockedRenderDoesNotBlockReceiptAndPreparedOlderPoseIsSuperseded() {
        var status=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,viewport=ChartViewport(metresToPixels=.01))
        var revision=0L;lateinit var actor:CameraControlActor
        actor=CameraControlActor({status},{revision},{100L},false) { id,row ->
            revision++;status=status.copy(viewport=status.viewport.copy(metresToPixels=row.c));actor.prepared(id,revision);true
        }
        try {
            val first=actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,1,100);actor.drainOne()
            val second=actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,2,100);actor.drainOne()
            val result=ChartControlResult();actor.read(first,result);assertEquals(CommandRing.State.SUPERSEDED.ordinal,result.state)
            actor.beginNative(revision);actor.nativeApplied(revision,status.viewport);actor.read(second,result)
            assertEquals(CommandRing.State.APPLIED.ordinal,result.state);assertEquals(.015625,result.actualScale,1e-12)
        } finally {actor.close()}
    }
    @Test fun residentLoadingAcceptsAndAccumulatesTargetsWithoutExecutingAnIntermediatePose() {
        var status=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,residentCameraReady=false,viewport=ChartViewport(metresToPixels=.01))
        var revision=0L;var commits=0;lateinit var actor:CameraControlActor
        actor=CameraControlActor({status},{revision},{100L},false) { id,row ->
            commits++;revision++;status=status.copy(viewport=status.viewport.copy(metresToPixels=row.c));actor.prepared(id,revision);true
        }
        try {
            val first=actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,1,100)
            actor.drainOne();assertEquals(0,commits)
            val latest=actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,2,100)
            assertTrue(first>0&&latest>first)
            val result=ChartControlResult();actor.read(first,result);assertEquals(CommandRing.State.SUPERSEDED.ordinal,result.state)
            status=status.copy(residentCameraReady=true);actor.drainOne();assertEquals(1,commits)
            assertEquals(.015625,status.viewport.metresToPixels,1e-12)
            actor.beginNative(revision);actor.nativeApplied(revision,status.viewport);actor.read(latest,result)
            assertEquals(CommandRing.State.APPLIED.ordinal,result.state)
        }finally{actor.close()}
    }
    @Test fun staleInputAndSourceChangeCannotMutateCameraAndFocusCancelsPending() {
        var status=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,chartSourceGeneration=1,viewport=ChartViewport(metresToPixels=.01))
        var commits=0
        val actor=CameraControlActor({status},{0L},{500L},false) { _,row -> if(row.generation!=status.chartSourceGeneration)false else {commits++;true} }
        try {
            assertEquals(0,actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,1,200))
            val first=actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,2,500)
            status=status.copy(chartSourceGeneration=2);actor.drainOne();assertEquals(0,commits)
            val result=ChartControlResult();actor.read(first,result);assertEquals(CommandRing.State.REJECTED.ordinal,result.state)
            val second=actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,3,500);actor.cancel();actor.drainOne()
            actor.read(second,result);assertEquals(CommandRing.State.CANCELLED.ordinal,result.state);assertEquals(0,commits)
        } finally {actor.close()}
    }
    @Test fun touchAnchorAndKnobZoomUseTheSameAccumulatedCameraGoal() {
        var status=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,viewport=ChartViewport(42.0,-71.0,.01))
        var revision=0L;var commits=0;lateinit var actor:CameraControlActor
        actor=CameraControlActor({status},{revision},{100L},false) {id,row ->
            commits++;revision++;status=status.copy(viewport=status.viewport.copy(latitude=row.a,longitude=row.b,metresToPixels=row.c,rotationRadians=row.d))
            actor.prepared(id,revision,status.viewport);true
        }
        try {
            val knob=actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,1,100)
            val touch=actor.motion(ChartMotion.anchored(ScreenPoint(1000.0,600.0),zoom=1.25),ChartSurfaceGeometry.fit(2000,1200),2,100)
            val result=ChartControlResult();actor.read(knob,result)
            assertEquals(CommandRing.State.SUPERSEDED.ordinal,result.state);assertEquals(touch,result.replacementId)
            actor.drainOne();assertEquals(1,commits)
            assertEquals(.015625,status.viewport.metresToPixels,1e-12)
            assertEquals(42.0,status.viewport.latitude,1e-9);assertEquals(-71.0,status.viewport.longitude,1e-9)
            actor.beginNative(revision);actor.nativeApplied(revision,status.viewport)
            actor.read(touch,result);assertEquals(CommandRing.State.APPLIED.ordinal,result.state)
        }finally{actor.close()}
    }
    @Test fun touchAbsolutePreviewAndHardwareShareTheSameOwnerAndResultRing() {
        var status=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,viewport=ChartViewport(42.0,-71.0,.01))
        var revision=0L;lateinit var actor:CameraControlActor
        actor=CameraControlActor({status},{revision},{100L},false) {id,row ->
            revision++;status=status.copy(viewport=status.viewport.copy(latitude=row.a,longitude=row.b,metresToPixels=row.c,rotationRadians=row.d))
            actor.prepared(id,revision,status.viewport);true
        }
        try {
            val preview=actor.absolute(status.viewport.copy(longitude=-72.0,metresToPixels=.02),1,100,true)
            val knob=actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,2,100)
            actor.drainOne();val result=ChartControlResult();actor.read(preview,result)
            assertEquals(CommandRing.State.SUPERSEDED.ordinal,result.state)
            assertEquals(-72.0,status.viewport.longitude,1e-9);assertEquals(.025,status.viewport.metresToPixels,1e-12)
            actor.beginNative(revision);actor.nativeApplied(revision,status.viewport);actor.read(knob,result)
            assertEquals(CommandRing.State.APPLIED.ordinal,result.state)
        }finally{actor.close()}
    }
    @Test fun expiredAdjustmentDoesNotAlterTheNextRelativeGoal() {
        var status=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,viewport=ChartViewport(metresToPixels=.01))
        var now=100L;var commits=0;lateinit var actor:CameraControlActor
        actor=CameraControlActor({status},{0L},{now},false) {_,row ->commits++;status=status.copy(viewport=status.viewport.copy(metresToPixels=row.c));true}
        try {
            assertEquals(0,actor.adjustment(ChartCameraAdjustment(zoomFactor=8.0,deadlineUptimeMs=100),1,100))
            val fresh=actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,2,100)
            assertTrue(fresh>0);actor.drainOne();assertEquals(1,commits);assertEquals(.0125,status.viewport.metresToPixels,1e-12)
            val expiring=actor.adjustment(ChartCameraAdjustment(zoomFactor=2.0,deadlineUptimeMs=150),3,100)
            now=150;actor.drainOne();assertEquals(1,commits)
            val result=ChartControlResult();actor.read(expiring,result);assertEquals(CommandRing.State.REJECTED.ordinal,result.state)
        }finally{actor.close()}
    }
    @Test fun blockedCameraCommitCannotBlockAdmissionAndCancelledOwnershipHasNoLateEffect() {
        val status=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,viewport=ChartViewport(metresToPixels=.01))
        val entered=CountDownLatch(1);val release=CountDownLatch(1);var effects=0;lateinit var actor:CameraControlActor
        actor=CameraControlActor({status},{0L},{100L},false) {id,_ ->
            entered.countDown();check(release.await(2,TimeUnit.SECONDS))
            if(actor.canCommit(id)){effects++;true}else false
        }
        try {
            val first=actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,1,100)
            val worker=thread {actor.drainOne()};assertTrue(entered.await(2,TimeUnit.SECONDS))
            val admitted=CountDownLatch(1);var second=0L
            val input=thread {second=actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,2,100);admitted.countDown()}
            assertTrue("Admission must not wait for the blocked camera owner",admitted.await(200,TimeUnit.MILLISECONDS))
            input.join();assertTrue(second>first)
            actor.cancel();release.countDown();worker.join(2000);assertFalse(worker.isAlive);assertEquals(0,effects)
            val result=ChartControlResult();actor.read(second,result);assertEquals(CommandRing.State.CANCELLED.ordinal,result.state)
        }finally{release.countDown();actor.close()}
    }
    @Test fun unchangedCameraBoundsAreAnAppliedNormalResult() {
        val status=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,viewport=ChartViewport(metresToPixels=10.0))
        lateinit var actor:CameraControlActor
        actor=CameraControlActor({status},{7L},{100L},false) {id,_ ->actor.unchanged(id,7,status.viewport);true}
        try {
            val id=actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,1,100);actor.drainOne()
            val result=ChartControlResult();actor.read(id,result)
            assertEquals(CommandRing.State.APPLIED.ordinal,result.state);assertEquals("BOUND_REACHED",result.reason)
            assertEquals(10.0,result.actualScale,0.0);assertEquals(7L,result.revision)
        }finally{actor.close()}
    }

    @Test fun longBlockedNativeHistoryCannotLeakUnretainedRunningSlots() {
        var status=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,viewport=ChartViewport(metresToPixels=.01))
        var revision=0L;lateinit var actor:CameraControlActor
        actor=CameraControlActor({status},{revision},{100L},false) {id,row ->
            revision++;status=status.copy(viewport=status.viewport.copy(metresToPixels=row.c));actor.prepared(id,revision,status.viewport);true
        }
        try {
            actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,1,100);actor.drainOne()
            var latest=0L
            repeat(80) {n ->
                // More than the result horizon elapses between preparation and replacement.
                repeat(300){i ->latest=actor.submit(ChartCameraControl.ZOOM_STEPS,if(i%2==0)1.0 else -1.0,(n*300+i+2).toLong(),100)}
                actor.drainOne()
            }
            actor.beginNative(revision);actor.nativeApplied(revision,status.viewport)
            val result=ChartControlResult();assertTrue(actor.read(latest,result));assertEquals(CommandRing.State.APPLIED.ordinal,result.state)
        }finally{actor.close()}
    }

    @Test fun sourceOrSurfaceChangedBeforeAdmissionCannotRebindAnOldGestureToTheNewChart() {
        val status=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,chartSourceGeneration=4,surfaceGeneration=8,viewport=ChartViewport(metresToPixels=.01))
        var commits=0
        val actor=CameraControlActor({status},{0L},{100L},false){_,_->commits++;true}
        try {
            val motion=ChartMotion.anchored(ScreenPoint(1000.0,600.0),zoom=2.0)
            assertEquals(0,actor.motion(motion,ChartSurfaceGeometry.fit(2000,1200),1,100,expectedSource=3,expectedSurface=8))
            assertEquals(0,actor.absolute(status.viewport.copy(metresToPixels=.1),2,100,true,4,7))
            actor.drainOne();assertEquals(0,commits)
            actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,3,100);actor.drainOne();assertEquals(1,commits)
        }finally{actor.close()}
    }

    @Test fun capturedCenterSourceAndSurfaceUseExactLongGenerationsAndCannotCrossAChartChange() {
        val high=9_007_199_254_740_993L
        var status=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,chartSourceGeneration=high,surfaceGeneration=high+2)
        var commits=0
        val actor=CameraControlActor({status},{0L},{100L},false){_,row->
            if(row.generation!=status.chartSourceGeneration||row.surface!=status.surfaceGeneration)false else {commits++;true}
        }
        try {
            assertEquals(0L,actor.submit(ChartCameraControl.CENTER,1.0,1,100,0.0,0.0,expectedSource=high-1,expectedSurface=high+2))
            assertEquals(0L,actor.submit(ChartCameraControl.CENTER,1.0,2,100,0.0,0.0,expectedSource=high,expectedSurface=high+1))
            val id=actor.submit(ChartCameraControl.CENTER,1.0,3,100,0.0,0.0,expectedSource=high,expectedSurface=high+2)
            assertTrue(id>0)
            status=status.copy(chartSourceGeneration=high+1)
            actor.drainOne();assertEquals(0,commits)
            val result=ChartControlResult();assertTrue(actor.read(id,result));assertEquals("SOURCE_CHANGED",result.reason)
        }finally{actor.close()}
    }

    @Test fun coldSavedCameraCanBeOwnedBeforeSurfaceWithoutAuthorizingRealInput() {
        val saved=ChartViewport(-33.91,18.42,.23,.4)
        var status=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=false,chartSourceGeneration=7,
            viewport=ChartViewport(metresToPixels=.01))
        var revision=0L;lateinit var actor:CameraControlActor
        actor=CameraControlActor({status},{revision},{100L},false){id,row ->
            assertEquals(CameraControlActor.RESTORE,row.kind)
            if(row.generation!=status.chartSourceGeneration||status.surfaceAttached)false else {
                revision++;status=status.copy(viewport=saved);actor.prepared(id,revision,saved);true
            }
        }
        try {
            assertEquals(0,actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,1,100))
            assertEquals(0,actor.absolute(saved,2,100))
            assertEquals(0,actor.restore(saved,3,100,6,1000))
            val id=actor.restore(saved,4,100,7,1000);assertTrue(id>0);actor.drainOne()
            assertEquals(saved,status.viewport)
            val result=ChartControlResult();actor.read(id,result)
            assertEquals(CommandRing.State.RUNNING.ordinal,result.state)
            assertEquals(-1L,result.appliedRevision)
            status=status.copy(surfaceAttached=true,surfaceGeneration=1);revision++
            actor.beginNative(revision,saved);actor.nativeApplied(revision,saved)
            actor.read(id,result);assertEquals(CommandRing.State.APPLIED.ordinal,result.state)
            assertEquals(revision,result.revision);assertEquals(revision,result.appliedRevision)
            assertEquals(0,actor.restore(saved,5,100,7,1000))
        }finally{actor.close()}
    }
    @Test fun savedCameraCannotCrossAConcurrentSourceOrSurfaceChange() {
        var status=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=false,chartSourceGeneration=7,
            viewport=ChartViewport(metresToPixels=.01))
        var effects=0;lateinit var actor:CameraControlActor
        actor=CameraControlActor({status},{0L},{100L},false){_,row ->
            if(row.generation!=status.chartSourceGeneration||row.surface!=status.surfaceGeneration)false else {effects++;true}
        }
        try {
            val first=actor.restore(status.viewport.copy(metresToPixels=.3),1,100,7,1000)
            status=status.copy(chartSourceGeneration=8);actor.drainOne();assertEquals(0,effects)
            val result=ChartControlResult();actor.read(first,result);assertEquals("SOURCE_CHANGED",result.reason)
            val second=actor.restore(status.viewport.copy(metresToPixels=.3),2,100,8,1000)
            status=status.copy(surfaceAttached=true,surfaceGeneration=1);actor.drainOne();assertEquals(0,effects)
            actor.read(second,result);assertEquals("SOURCE_CHANGED",result.reason)
        }finally{actor.close()}
    }

    @Test fun canonicalGestureRotationIsNotNormalizedASecondTimeByAdmission() {
        val home=ChartViewport(42.36,-71.04,.15,.2)
        val desired=transformChartCamera(home,ScreenPoint(600.0,300.0),ScreenPoint(20.0,10.0),.9,-.1)
        var status=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,viewport=home)
        val actor=CameraControlActor({status},{0L},{100L},false){_,row ->
            status=status.copy(viewport=status.viewport.copy(latitude=row.a,longitude=row.b,metresToPixels=row.c,rotationRadians=row.d));true
        }
        try {
            actor.absolute(desired,1,100);actor.drainOne()
            assertEquals(desired,status.viewport)
            assertEquals(java.lang.Double.doubleToRawLongBits(desired.rotationRadians),
                java.lang.Double.doubleToRawLongBits(status.viewport.rotationRadians))
        }finally{actor.close()}
    }

    @Test fun aLaterNativeOperationCannotLoseAnEarlierUnobservedCompletion() {
        var status=ChartEngineStatus(initialized=true,chartLoaded=true,surfaceAttached=true,viewport=ChartViewport(metresToPixels=.01))
        var revision=0L;var commits=0;lateinit var actor:CameraControlActor
        actor=CameraControlActor({status},{revision},{100L},false){id,row ->
            commits++;revision++;status=status.copy(viewport=status.viewport.copy(metresToPixels=row.c));actor.prepared(id,revision,status.viewport);true
        }
        try {
            val first=actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,1,100);actor.drainOne()
            val firstRevision=revision;val firstPose=status.viewport
            actor.beginNative(firstRevision) // Native began, but its readback was lost.
            val second=actor.submit(ChartCameraControl.ZOOM_STEPS,1.0,2,100);actor.drainOne()
            actor.beginNative(revision)
            val result=ChartControlResult();actor.read(first,result)
            assertEquals(CommandRing.State.UNKNOWN.ordinal,result.state)
            assertEquals("COMPLETION_UNKNOWN",result.reason)
            assertEquals(0L,result.replacementId)
            actor.nativeApplied(firstRevision,firstPose) // Late old proof must not settle the new owner.
            actor.read(second,result);assertEquals(CommandRing.State.RUNNING.ordinal,result.state)
            assertEquals(-1L,result.appliedRevision)
            actor.nativeApplied(revision,status.viewport)
            actor.read(second,result);assertEquals(CommandRing.State.APPLIED.ordinal,result.state)
            actor.read(first,result);assertEquals(CommandRing.State.UNKNOWN.ordinal,result.state)
            actor.drainOne();assertEquals("Neither UNKNOWN nor a late result may replay a command",2,commits)
        }finally{actor.close()}
    }

}
