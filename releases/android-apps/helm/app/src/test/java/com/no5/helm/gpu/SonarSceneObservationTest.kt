package com.no5.helm.gpu

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import com.no5.domain.*
import com.no5.helm.SonarNativeController
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class SonarSceneObservationTest {
    private fun controller():SonarNativeController {
        val c=SonarNativeController(DeterministicMockSonarSource(now={1_000_000L})){1_000_000L}
        c.model.resetAcquisition();c.model.connect("SNAPSHOT OBSERVATION TEST");c.model.acquisition(true)
        c.model.updateContext(SonarEpoch("batch",1,1),SonarPingSource.OWN)
        c.model.setDisplay(SonarDisplay(historySeconds=10.0))
        return c
    }
    private fun append(c:SonarNativeController,first:Int,n:Int){
        repeat(n){offset->val id=first+offset
            c.model.setAnalysis(SonarGeometry(3.069,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true))
            c.model.frame(SonarFrame(ShortArray(1024){(id+it).toShort()},id.toLong(),999_000L+id*20),pingSource=SonarPingSource.OWN)
        }
        c.refresh();Snapshot.sendApplyNotifications()
    }
    @Test fun blockedRendererConflatesCommandsButRetainsEveryOrderedRawPing()=runBlocking {
        val c=controller();val seen=Channel<SonarSceneRequest>(Channel.UNLIMITED);val release=CompletableDeferred<Unit>();var calls=0
        val job=launch(start=CoroutineStart.UNDISPATCHED){snapshotFlow{sonarSceneRequest(c,false,null)}.collect{
            seen.send(it);if(++calls==2)release.await()
        }}
        try{
            withTimeout(1000){seen.receive()}
            append(c,0,20);val first=withTimeout(1000){seen.receive()}
            append(c,20,10);release.complete(Unit)
            val next=withTimeout(1000){seen.receive()}
            assertSame(first.frame.ring,next.frame.ring);assertEquals(30,next.frame.ring.count)
            for(age in 0 until 30){assertEquals((29-age).toLong(),next.frame.ring.pingIdAt(age));assertEquals(29-age,next.frame.ring.sampleAt(age,0))}
            assertEquals(c.model.viewRing.version,next.frame.ringVersion)
        }finally{job.cancelAndJoin();c.dispose()}
    }
    @Test fun uiWarningArrivesWithoutRequiringAnotherPingOrGpuCompletion()=runBlocking {
        val c=controller();val seen=Channel<SonarSceneRequest>(Channel.UNLIMITED)
        val job=launch(start=CoroutineStart.UNDISPATCHED){snapshotFlow{sonarSceneRequest(c,false,null)}.collect{seen.send(it)}}
        try{
            val before=withTimeout(1000){seen.receive()};c.tell("Source delayed");Snapshot.sendApplyNotifications()
            val after=withTimeout(1000){seen.receive()};assertEquals(before.frame,after.frame);assertNotEquals(before.ui,after.ui)
        }finally{job.cancelAndJoin();c.dispose()}
    }
    @Test fun sourceBoundaryKeepsItsNewEpochAndCannotReuseThePriorFrame(){
        val c=controller();try{
            append(c,0,5);val before=sonarSceneRequest(c,false,null)
            c.model.updateContext(SonarEpoch("other",2,1),SonarPingSource.OWN);c.refresh()
            val after=sonarSceneRequest(c,false,null);assertNotEquals(before.frame.ringGeneration,after.frame.ringGeneration)
            assertEquals(0,after.frame.ring.count)
        }finally{c.dispose()}
    }
    @Test fun dashboardViewportKeepsTheSameRawOwnerSourceAndDisplayPolicy(){
        val c=controller();try{
            append(c,0,30)
            val full=sonarSceneRequest(c,false,null)
            val preview=sonarSceneRequest(c,false,null,dashboardPreview=true)
            assertSame(full.frame.ring,preview.frame.ring)
            assertEquals(full.frame.context,preview.frame.context)
            assertEquals(full.frame.ringGeneration,preview.frame.ringGeneration)
            assertEquals(full.frame.display,preview.frame.display)
            assertEquals(30,preview.frame.ring.count)
            val policy=SonarGpuSceneUpdatePolicy()
            policy.immediate(full.frame,DepthUnit.METERS,null,full.ui)
            assertTrue(policy.immediate(preview.frame,DepthUnit.METERS,null,preview.ui))
            assertEquals(full,sonarSceneRequest(c,false,null))
        }finally{c.dispose()}
    }

    @Test fun nextRawPingChangesGpuCommandButNotUnchangedVisibleLabels(){
        val c=controller();try{
            append(c,0,5);val before=sonarSceneRequest(c,false,null)
            append(c,5,1);val after=sonarSceneRequest(c,false,null)
            assertNotEquals(before.frame,after.frame)
            assertEquals("RAW identity/count are not a second old-buffer UI redraw",before.ui,after.ui)
        }finally{c.dispose()}
    }
}
