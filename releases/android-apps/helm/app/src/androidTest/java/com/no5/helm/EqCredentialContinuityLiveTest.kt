package com.no5.helm

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.No5SourceSession
import com.no5.helm.control.HelmSourceOwner
import com.no5.helm.control.HelmSourceRegistry
import com.no5.ui.No5Text
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit Lumina/Pi acceptance, temporary preinstalled test draft only. Never Save.
 * Three genuine renewals in seconds, without changing clocks, token TTL or DSP leases.
 */
@RunWith(AndroidJUnit4::class)
class EqCredentialContinuityLiveTest {
    @Test fun authenticatedRenewalsKeepTheSameLiveEqPreview() {
        assumeTrue("Explicit temporary Lumina EQ fixture required",InstrumentationRegistry.getArguments().getString("no5EqLive")=="1")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        fun find(root:AccessibilityNodeInfo?,label:String):AccessibilityNodeInfo? {
            if(root==null)return null
            if((root.contentDescription?.toString()==label||root.text?.toString()==label)&&root.isEnabled)return root
            for(i in 0 until root.childCount)find(root.getChild(i),label)?.let{return it}
            return null
        }
        fun tap(vararg labels:String){
            val translated=labels.map{No5Text.text(context,it)}
            val deadline=SystemClock.elapsedRealtime()+10000
            var found:AccessibilityNodeInfo?=null
            while(found==null&&SystemClock.elapsedRealtime()<deadline){
                val root=instrumentation.uiAutomation.rootInActiveWindow
                found=translated.firstNotNullOfOrNull{find(root,it)}
                if(found==null)Thread.sleep(100)
            }
            assertNotNull("Required enabled native action unavailable",found)
            val bounds=Rect();found!!.getBoundsInScreen(bounds)
            val t=SystemClock.uptimeMillis()
            instrumentation.sendPointerSync(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0))
            instrumentation.sendPointerSync(MotionEvent.obtain(t,t+30,MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0))
        }
        val activity=ActivityScenario.launch(MainActivity::class.java)
        lateinit var owner:HelmSourceOwner
        lateinit var borrowed:No5SourceSession
        var ready=false
        val deadline=SystemClock.elapsedRealtime()+15000
        while(!ready&&SystemClock.elapsedRealtime()<deadline){
            instrumentation.runOnMainSync{ready=HelmSourceRegistry.peek(context)?.recoveryStatus?.sourceAccepted==true}
            if(!ready)Thread.sleep(100)
        }
        assertTrue("Authenticated source unavailable",ready)
        instrumentation.runOnMainSync{owner=HelmSourceRegistry.requireOwner(context);borrowed=owner.borrowSession()}
        try{
            tap("AUDIO");tap("Audio einrichten");tap("Klangpresets. EQ und Raumwirkung. ›");tap("Entwurf fortsetzen")
            tap("EQ aus","EQ nicht aktiv")
            lateinit var sound:com.no5.domain.AudioSoundPort
            instrumentation.runOnMainSync{sound=checkNotNull(borrowed.audio.sound)}
            val appliedDeadline=SystemClock.elapsedRealtime()+8000
            while((!sound.state.value.available||sound.state.value.preset?.eqEnabled!=true||sound.state.value.preview==null)&&SystemClock.elapsedRealtime()<appliedDeadline)Thread.sleep(50)
            val initial=sound.state.value
            assertTrue("EQ preview was not confirmed",initial.available&&initial.preset?.eqEnabled==true&&initial.preview!=null)
            var generation=0L;instrumentation.runOnMainSync{generation=owner.sessionGeneration}
            repeat(3){
                Thread.sleep(1100)
                var previous="";var changed=false
                instrumentation.runOnMainSync{
                    val gateway=checkNotNull(owner.luminaGateway)
                    previous=checkNotNull(gateway.currentSession()).identity;gateway.refresh()
                }
                val renewedDeadline=SystemClock.elapsedRealtime()+6000
                while(!changed&&SystemClock.elapsedRealtime()<renewedDeadline){
                    Thread.sleep(50)
                    instrumentation.runOnMainSync{changed=owner.luminaGateway?.currentSession()?.identity?.let{it!=previous}==true}
                }
                assertTrue("Authenticated renewal unavailable",changed)
                instrumentation.runOnMainSync{assertTrue(owner.synchronizeHome(requestRefresh=false));assertEquals(generation,owner.sessionGeneration)}
                Thread.sleep(700)
                val now=sound.state.value
                assertTrue("Renewal ended EQ",now.available&&now.preset?.eqEnabled==true)
                assertEquals(initial.preview!!.id,now.preview?.id)
                assertEquals(initial.configGeneration,now.configGeneration);assertEquals(initial.sourceGeneration,now.sourceGeneration)
                assertEquals(initial.preset,now.preset);assertEquals(initial.calibration,now.calibration)
                assertEquals(initial.presetRevision,now.presetRevision);assertEquals(initial.calibrationRevision,now.calibrationRevision)
            }
        }finally{instrumentation.runOnMainSync{borrowed.close()};activity.close()}
    }
}
