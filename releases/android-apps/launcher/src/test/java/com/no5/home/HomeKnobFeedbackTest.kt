package com.no5.home

import android.os.SystemClock
import com.no5.appcontrol.KnobActionTicket
import com.no5.control.ClientDisposition
import com.no5.header.artwork.No5MomentFeedback
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35])
class HomeKnobFeedbackTest {
    @Test fun unassignedHoldsRejectHonestlyExpireAndNeverReplayAfterPause(){
        Robolectric.buildActivity(HomeActivity::class.java).setup().visible().windowFocusChanged(true).use{host->
            val activity=host.get();var result:ClientDisposition?=null
            activity.reportUnassignedHold(KnobActionTicket(SystemClock.uptimeMillis()+80,{true}){result=it})
            assertEquals(ClientDisposition.REFUSED,result)
            assertEquals("Nicht belegt",No5MomentFeedback.current()?.action)
            assertEquals(No5MomentFeedback.Phase.FAILED,No5MomentFeedback.current()?.phase)
            host.pause();assertNull(No5MomentFeedback.current())
            host.resume().windowFocusChanged(true);assertNull(No5MomentFeedback.current())
            activity.reportUnassignedHold(KnobActionTicket(SystemClock.uptimeMillis()+80,{false}){result=it})
            assertEquals(ClientDisposition.AUDIO_DEFAULT,result);assertNull(No5MomentFeedback.current())
        }
    }
}
