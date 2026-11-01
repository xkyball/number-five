package com.no5.helm.ui

import android.os.Looper
import com.no5.header.artwork.No5MomentFeedback
import com.no5.helm.chart.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.lumina.control.commands.v1.CommandRing
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class MapKnobFeedbackTest {
    private var now=100L;private var generation=1L
    private var state=ChartEngineStatus(chartSourceGeneration=1,surfaceGeneration=1)
    private var receiptState=CommandRing.State.APPLIED
    private var revision=10L;private var applied=10L;private var presented=-1L
    private var known=true;private var intent=false;private var reads=0;private var lastId=0L
    private fun owner()=MapKnobFeedback({state},{generation},{id,result->reads++;lastId=id;result.state=receiptState.ordinal;result.revision=revision;result.appliedRevision=applied;result.presentedRevision=presented;known},{intent},{it.name},{now})
    private fun tick(ms:Long=0){now+=ms;org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))}
    @After fun clear(){No5MomentFeedback.clear()}
    @Test fun admissionDoesNotPublishOrReadAndCameraSuccessRequiresActuallyPresentedRevision(){
        owner().use{f->
            f.submitted(ChartKnobInput.RIGHT_PUSH,42)
            assertNull(No5MomentFeedback.current());assertEquals(0,reads)
            tick();assertEquals(No5MomentFeedback.Phase.ACCEPTED,No5MomentFeedback.current()!!.phase)
            presented=9;tick(50);assertEquals(No5MomentFeedback.Phase.ACCEPTED,No5MomentFeedback.current()!!.phase)
            presented=10;tick(50);assertEquals(No5MomentFeedback.Phase.EXECUTED,No5MomentFeedback.current()!!.phase)
        }
    }
    @Test fun repeatedInputsReplaceOneObservationAndIntentOnlyOffNeedsNoInventedFrame(){
        owner().use{f->
            repeat(100){f.submitted(ChartKnobInput.LEFT_CW,it+1L)}
            intent=true;tick();assertEquals(1,reads);assertEquals(100L,lastId)
            assertEquals(No5MomentFeedback.Phase.EXECUTED,No5MomentFeedback.current()!!.phase)
        }
    }
    @Test fun sourceSurfaceSelectionAndOwnerRetirementCannotPublishLateCompletion(){
        for(boundary in listOf("source","surface","selection","closed")){
            No5MomentFeedback.clear();state=ChartEngineStatus(chartSourceGeneration=1,surfaceGeneration=1);generation=1;presented=-1
            owner().use{f->
                f.submitted(ChartKnobInput.LEFT_LONG_PUSH,1);tick();assertNotNull(No5MomentFeedback.current())
                when(boundary){"source"->state=state.copy(chartSourceGeneration=2);"surface"->state=state.copy(surfaceGeneration=2);"selection"->generation++;else->f.close()}
                presented=10;tick(50);assertNull(boundary,No5MomentFeedback.current())
            }
        }
        val other=Any();val f=owner();f.submitted(ChartKnobInput.LEFT_CW,1);tick();No5MomentFeedback.begin(other,"Other owner")
        f.close();assertEquals("Other owner",No5MomentFeedback.current()!!.action)
    }
    @Test fun rejectionIsAFailureButUnknownOrExpiredReceiptsAreNeverAdvertisedAsSuccess(){
        for(outcome in listOf("rejected","unknown","expired")){
            No5MomentFeedback.clear();known=outcome!="expired";receiptState=if(outcome=="rejected")CommandRing.State.REJECTED else CommandRing.State.UNKNOWN
            owner().use{f->f.submitted(ChartKnobInput.RIGHT_LONG_PUSH,1);tick();if(outcome=="expired")tick(2000)
                assertEquals(outcome,if(outcome=="rejected")No5MomentFeedback.Phase.FAILED else No5MomentFeedback.Phase.UNKNOWN,No5MomentFeedback.current()!!.phase)}
        }
    }
}
