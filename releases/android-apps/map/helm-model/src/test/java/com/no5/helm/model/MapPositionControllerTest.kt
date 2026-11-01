package com.no5.helm.model

import org.junit.Assert.*
import org.junit.Test

/** Synthetic sources/coordinates only. No real receiver or vessel locations. */
class MapPositionControllerTest {
    private class Camera: PositionCamera {
        val centers = mutableListOf<Pair<Double,Double>>()
        var cancellations = 0
        var covered = true
        var boundsKnown = true
        override fun navigationBoundsKnown() = boundsKnown
        override fun covers(latitude: Double, longitude: Double) = covered
        override fun center(latitude: Double, longitude: Double, sequence: Long, physicalUptimeMs: Long): Long {
            centers += latitude to longitude; return centers.size.toLong()
        }
        override fun cancelPending() { cancellations++ }
    }
    @Test fun outsideChartPausesFollowWithoutMovingTheFixAndFreshReturnResumes() {
        val camera=Camera();val c=MapPositionController(camera)
        c.update(backend(candidate()),10000);assertTrue(c.toggleFollow(10000));assertTrue(c.follow(10000,100)>0)
        val count=camera.centers.size;val cancelled=camera.cancellations
        camera.covered=false;c.update(backend(candidate(stamp=10001,lat=1.0)),10001)
        assertTrue(c.state.value.intent.follow);assertFalse(c.state.value.followActive)
        assertTrue(c.state.value.outsideChartBounds);assertEquals(1.0,c.state.value.ownship!!.position.latitude,0.0)
        assertEquals(0L,c.follow(10001,101));assertEquals(0L,c.center(10001,0,101))
        assertEquals(count,camera.centers.size);assertTrue(camera.cancellations>cancelled)
        camera.covered=true;c.update(backend(candidate(stamp=10002)),10002)
        assertFalse(c.state.value.outsideChartBounds);assertTrue(c.state.value.followActive)
        assertTrue(c.follow(10002,102)>0)
        camera.covered=false;c.update(backend(candidate(stamp=10003,lat=1.0)),10003)
        assertTrue(c.toggleFollow(10003));assertFalse(c.state.value.intent.follow)
        camera.covered=true;c.update(backend(candidate(stamp=10004)),10004)
        assertFalse(c.state.value.followActive)
    }
    @Test fun missingBoundsPauseWithoutClaimingTheRealPositionIsOutside() {
        val camera=Camera();val c=MapPositionController(camera)
        c.update(backend(candidate()),10000);c.toggleFollow(10000)
        camera.boundsKnown=false;c.tick(10001)
        assertFalse(c.state.value.chartBoundsKnown);assertFalse(c.state.value.outsideChartBounds)
        assertTrue(c.state.value.intent.follow);assertFalse(c.state.value.followActive)
        assertNotNull(c.state.value.ownship);assertEquals(0L,c.follow(10001,100));assertEquals(0L,c.center(10001,0,100))
    }
    @Test fun outsideCannotStartCenterOrFollowAndMissingFreshFixCannotResume() {
        val camera=Camera();camera.covered=false;val c=MapPositionController(camera)
        c.update(backend(candidate()),10000)
        assertFalse(c.toggleFollow(10000));assertEquals(0L,c.center(10000,0,100));assertTrue(camera.centers.isEmpty())
        camera.covered=true;c.tick(15001);assertFalse(c.toggleFollow(15001));assertFalse(c.state.value.followActive)
    }
    private fun candidate(id:String="synthetic.gps.1", stamp:Long=10000, lat:Double=0.0, status:PositionStatus=PositionStatus.LIVE) =
        PositionCandidate(id,id,id,status,stamp,GeoPoint(lat,0.0))
    private fun backend(vararg c:PositionCandidate, primary:String?=c.firstOrNull()?.sourceId,server:String="synthetic-epoch", connection:ConnectionState=ConnectionState.LIVE) =
        HelmState(connection,Snapshot(serverId=server,revision=1,generatedAt="synthetic",state=HelmProjection(
            Ownship(GeoPoint(50.0,50.0),1.0,2.0,3.0,4.0),emptyList(),emptyList(),emptyList()),gatewayV1=true,
            positionSources=PositionSources(primarySourceId=primary,total=c.size,sources=c.toList())))
    @Test fun automaticSelectsProvenFreshPrimaryAndNeverImportsGlobalMotion() {
        val c=MapPositionController(Camera());c.update(backend(candidate()),10000)
        assertEquals("synthetic.gps.1",c.state.value.selectedSourceId)
        assertEquals(GeoPoint(0.0,0.0),c.state.value.ownship!!.position)
        assertNull(c.state.value.ownship!!.headingRadians);assertNull(c.state.value.ownship!!.cogRadians)
        assertNull(c.state.value.ownship!!.sogMetresPerSecond)
        c.tick(15000);assertNotNull(c.state.value.ownship)
        c.tick(15001);assertNull(c.state.value.ownship);assertEquals(PositionStatus.STALE,c.state.value.status)
        c.tick(40001);assertEquals(PositionStatus.LOST,c.state.value.status)
    }
    @Test fun manualMissingOrInvalidSourceNeverSilentlyFallsBackAndSelectionSurvivesEpoch() {
        val saved=mutableListOf<PositionIntent>();val c=MapPositionController(Camera(),save={saved+=it})
        c.update(backend(candidate(),candidate("synthetic.gps.2",lat=1.0)),10000)
        c.select(PositionChoice.MANUAL,"synthetic.gps.2",10000)
        assertEquals(1.0,c.state.value.ownship!!.position.latitude,0.0)
        val generation=c.state.value.generation
        c.update(backend(candidate(stamp=11000),server="synthetic-new-epoch"),11000)
        assertEquals("synthetic.gps.2",c.state.value.intent.sourceId);assertNull(c.state.value.ownship)
        assertTrue(c.state.value.generation>generation);assertEquals(1,saved.size)
        c.select(PositionChoice.COMBINED,nowEpochMs=11000);assertNull(c.state.value.ownship)
        assertFalse(c.state.value.sources!!.combinationAvailable)
    }
    @Test fun autoFallbackIsFreshnessBasedAndSourceRefsRemainDistinct() {
        val c=MapPositionController(Camera());val a=candidate("synthetic.A.129029",8000);val b=candidate("synthetic.B.129029",14000,1.0)
        c.update(backend(a,b),14000)
        assertEquals(b.sourceId,c.state.value.selectedSourceId)
        assertEquals(2,c.state.value.sources!!.total)
    }
    @Test fun centerIsOneShotAndLateCompletionCannotReactivateFollowAfterGesture() {
        val camera=Camera();val c=MapPositionController(camera);c.update(backend(candidate()),10000)
        val id=c.center(10000,7,100)
        assertEquals(1L,id);assertFalse(c.state.value.intent.follow);assertFalse(c.state.value.followActive)
        assertTrue(c.toggleFollow(10000));assertEquals(2L,c.follow(10000,101));assertEquals(0L,c.follow(10000,102))
        val before=camera.cancellations;c.gestureStarted(10000)
        assertTrue(camera.cancellations>before);assertFalse(c.state.value.intent.follow)
        // The original camera result has no setter for follow intent. Further data does not resurrect it.
        c.update(backend(candidate(stamp=10001,lat=1.0)),10001)
        assertFalse(c.state.value.followActive);assertEquals(0L,c.follow(10001,103))
    }
    @Test fun reconnectRequiresNewOriginalFixEvenWhenPublicationAndTransportAreFresh() {
        val camera=Camera();val c=MapPositionController(camera);c.update(backend(candidate()),10000);c.toggleFollow(10000)
        c.update(backend(candidate(),connection=ConnectionState.DEGRADED),11000)
        assertNull(c.state.value.ownship);assertTrue(c.state.value.intent.follow);assertFalse(c.state.value.followActive)
        c.update(backend(candidate()),12000)
        assertNull(c.state.value.ownship);assertTrue(c.state.value.awaitingFreshFix)
        c.update(backend(candidate(stamp=11999)),12500);assertNull(c.state.value.ownship)
        c.update(backend(candidate(stamp=12000)),12500);assertNotNull(c.state.value.ownship);assertTrue(c.state.value.followActive)
        c.gestureStarted(12500);c.update(backend(candidate(stamp=13000)),13000);assertFalse(c.state.value.followActive)
    }
    @Test fun restoredIntentAndBackendLossNeverRestoreAnOldFixOrCommand() {
        val camera=Camera();val saved=PositionIntent(choice=PositionChoice.MANUAL,sourceId="synthetic.gps.1",follow=true)
        val c=MapPositionController(camera,saved);assertTrue(c.state.value.intent.follow);assertNull(c.state.value.ownship)
        c.update(backend(candidate(stamp=9999)),10000);assertNull(c.state.value.ownship);assertEquals(0L,c.follow(10000,0))
        c.update(backend(candidate(stamp=10001)),10001);assertTrue(c.state.value.followActive)
        assertEquals(1L,c.follow(10001,1));c.tick(15002);assertFalse(c.state.value.followActive)
        assertEquals(0L,c.center(15002,0,2));assertEquals(1,camera.centers.size)
    }
    @Test fun futureAndOutOfMercatorFixesNeverDriveCameraAndDoNotBecomeZeroHeading() {
        val c=MapPositionController(Camera());c.update(backend(candidate(stamp=10001)),10000)
        assertNull(c.state.value.ownship);assertEquals(PositionStatus.INVALID,c.state.value.status)
        c.update(backend(candidate(lat=89.0)),10000);assertNull(c.state.value.ownship)
    }
    private fun combinedBackend(stamp:Long=10000, ids:List<String> = listOf("synthetic.a","synthetic.b"),
        status:PositionStatus=PositionStatus.LIVE, server:String="synthetic-epoch", connection:ConnectionState=ConnectionState.LIVE): HelmState {
        val original=backend(*ids.map { candidate(it,stamp) }.toTypedArray(),server=server,connection=connection)
        val combination=PositionCombination(mode=if(status==PositionStatus.LIVE) if(ids.size==1) "SINGLE_SOURCE" else "CONSENSUS" else "NONE",
            status=status,reason=if(status==PositionStatus.INVALID) "SOURCE_DISAGREEMENT" else "OK",observedAtEpochMs=stamp,
            position=if(status==PositionStatus.LIVE) GeoPoint(0.00005,0.0) else null,
            contributors=ids.map { CombinationContributor(it,stamp) })
        return original.copy(snapshot=original.snapshot!!.copy(positionSources=original.snapshot!!.positionSources!!.copy(
            combinationAvailable=true,combinationReason=combination.reason,combination=combination)))
    }
    @Test fun combinedUsesGatewayResultAndAgesWithoutNewHTTPData() {
        val c=MapPositionController(Camera(),PositionIntent(choice=PositionChoice.COMBINED))
        c.update(combinedBackend(),10000)
        assertEquals(GeoPoint(0.00005,0.0),c.state.value.ownship!!.position)
        assertEquals("@combined",c.state.value.selectedSourceId);assertNull(c.state.value.ownship!!.sogMetresPerSecond)
        c.tick(15001);assertNull(c.state.value.ownship);assertEquals(PositionStatus.STALE,c.state.value.status)
        c.tick(40001);assertEquals(PositionStatus.LOST,c.state.value.status)
    }
    @Test fun contributorChangesAndConflictRetireCameraWithoutChangingFollowIntent() {
        val camera=Camera();val c=MapPositionController(camera,PositionIntent(choice=PositionChoice.COMBINED,follow=true))
        c.update(combinedBackend(),10000);assertTrue(c.state.value.followActive);c.follow(10000,1)
        val generation=c.state.value.generation;val cancellations=camera.cancellations
        c.update(combinedBackend(11000,listOf("synthetic.a")),11000)
        assertTrue(c.state.value.generation>generation);assertTrue(camera.cancellations>cancellations)
        c.update(combinedBackend(12000,status=PositionStatus.INVALID),12000)
        assertNull(c.state.value.ownship);assertFalse(c.state.value.followActive);assertTrue(c.state.value.intent.follow)
        assertEquals(0L,c.center(12000,0,2));assertEquals(0L,c.follow(12000,2))
        c.gestureStarted(12000);c.update(combinedBackend(13000),13000)
        assertNotNull(c.state.value.ownship);assertFalse(c.state.value.followActive)
    }
    @Test fun combinedReconnectAndNewServerCannotReviveOldFixes() {
        val c=MapPositionController(Camera(),PositionIntent(choice=PositionChoice.COMBINED,follow=true))
        c.update(combinedBackend(),10000)
        c.update(combinedBackend(connection=ConnectionState.DEGRADED),11000);assertNull(c.state.value.ownship)
        c.update(combinedBackend(),12000);assertNull(c.state.value.ownship)
        c.update(combinedBackend(12000),12001);assertNotNull(c.state.value.ownship)
        c.update(combinedBackend(12000,server="new-session"),13000);assertNull(c.state.value.ownship)
        c.update(combinedBackend(13001,server="new-session"),13001);assertNotNull(c.state.value.ownship)
    }
    @Test fun legacyIsExplicitPrimaryOnlyAndDoesNotInventASensorId() {
        val c=MapPositionController(Camera());val s=backend(candidate()).let { it.copy(snapshot=it.snapshot!!.copy(positionSources=null,fixObservedAtEpochMs=10000)) }
        c.update(s,10000);assertTrue(c.state.value.legacy);assertNull(c.state.value.selectedSourceId)
        assertEquals(GeoPoint(50.0,50.0),c.state.value.ownship!!.position)
        c.select(PositionChoice.MANUAL,"missing-sensor",10000);assertNull(c.state.value.ownship)
    }
    @Test fun singlePositionCycleCentersOnceThenFollowsThenStopsWithoutRepeatingTheOneShot() {
        val camera=Camera();val saved=mutableListOf<PositionIntent>()
        val c=MapPositionController(camera,save={saved+=it});c.update(backend(candidate()),10000)
        assertEquals(PositionMode.OFF,c.state.value.mode)
        assertEquals(1L,c.cyclePosition(10000,1,100));assertEquals(PositionMode.CENTERED,c.state.value.mode)
        assertFalse(c.state.value.intent.follow);assertEquals(0L,c.follow(10000,101))
        c.update(backend(candidate(stamp=10001,lat=1.0)),10001)
        assertEquals(1,camera.centers.size);assertEquals(PositionMode.CENTERED,c.state.value.mode)
        assertEquals(2L,c.cyclePosition(10001,2,102));assertEquals(PositionMode.FOLLOW,c.state.value.mode)
        assertEquals(0L,c.follow(10001,103))
        c.update(backend(candidate(stamp=10002,lat=2.0)),10002)
        assertEquals(3L,c.follow(10002,104))
        assertEquals(-1L,c.cyclePosition(10002,3,105));assertEquals(PositionMode.OFF,c.state.value.mode)
        assertEquals(3,camera.centers.size);assertEquals(listOf(true,false),saved.map{it.follow})
        assertEquals(PositionMode.OFF,MapPositionController(Camera(),saved.last()).state.value.mode)
    }
    @Test fun pureTouchZoomAndRotationPauseThenResumeFollowButLatePanTurnsItOff() {
        val camera=Camera();val c=MapPositionController(camera);c.update(backend(candidate()),10000)
        c.cyclePosition(10000,1,100);c.cyclePosition(10000,2,101)
        c.touchActivity(true,10000);assertTrue(c.state.value.intent.follow);assertFalse(c.state.value.followActive)
        c.update(backend(candidate(stamp=10001,lat=1.0)),10001);assertEquals(0L,c.follow(10001,102))
        val cancelled=camera.cancellations
        c.touchActivity(false,10001);assertEquals(cancelled,camera.cancellations)
        assertTrue(c.state.value.followActive);assertEquals(3L,c.follow(10001,103))
        c.touchActivity(true,10001);c.gestureStarted(10001);c.touchActivity(false,10001)
        assertEquals(PositionMode.OFF,c.state.value.mode);assertFalse(c.state.value.intent.follow)
        c.update(backend(candidate(stamp=10002)),10002);assertEquals(0L,c.follow(10002,104))
    }
    @Test fun absentFixCannotAdvanceButFollowCanAlwaysBeDisabledAndCenteredIsNeverRestored() {
        val c=MapPositionController(Camera(),PositionIntent(follow=true))
        assertEquals(PositionMode.FOLLOW,c.state.value.mode)
        assertEquals(-1L,c.cyclePosition(10000,1,100));assertEquals(PositionMode.OFF,c.state.value.mode)
        assertEquals(0L,c.cyclePosition(10000,2,101))
        c.update(backend(candidate()),10000);c.cyclePosition(10000,3,102)
        assertEquals(PositionMode.CENTERED,c.state.value.mode)
        c.tick(15001);assertEquals(PositionMode.OFF,c.state.value.mode)
        c.update(backend(candidate(stamp=16000)),16000);assertEquals(PositionMode.OFF,c.state.value.mode)
    }
    @Test fun admittedCycleDoesNotCrossSourceGenerationOrCreateCombinedMotion() {
        val c=MapPositionController(Camera());c.update(backend(candidate(),candidate("synthetic.gps.2",lat=1.0)),10000)
        val generation=c.state.value.generation
        c.select(PositionChoice.MANUAL,"synthetic.gps.2",10000)
        assertEquals(0L,c.cyclePosition(10000,1,100,generation));assertEquals(PositionMode.OFF,c.state.value.mode)
        assertTrue(c.cyclePosition(10000,2,101,c.state.value.generation)>0)
        assertEquals(PositionMode.CENTERED,c.state.value.mode)
        c.select(PositionChoice.AUTOMATIC,nowEpochMs=10000);assertEquals(PositionMode.OFF,c.state.value.mode)
    }

}
