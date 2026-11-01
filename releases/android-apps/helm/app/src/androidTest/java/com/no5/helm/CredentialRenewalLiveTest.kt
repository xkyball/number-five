package com.no5.helm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.No5SourceSession
import com.no5.domain.SonarPingSource
import com.no5.helm.control.HelmSourceOwner
import com.no5.helm.control.HelmSourceRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit real-chain acceptance: authenticated renewal, no TX or settings writes. */
@RunWith(AndroidJUnit4::class)
class CredentialRenewalLiveTest {
    @Test fun threeRealRenewalsPreserveTheExistingSourceAndFreshOwnFrames() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        lateinit var owner:HelmSourceOwner
        lateinit var borrowed:No5SourceSession
        var generation=0L
        val activity=ActivityScenario.launch(MainActivity::class.java)
        val readyDeadline=android.os.SystemClock.elapsedRealtime()+15_000
        var ready=false
        while(!ready && android.os.SystemClock.elapsedRealtime()<readyDeadline) {
            Thread.sleep(100)
            instrumentation.runOnMainSync {
                val candidate=HelmSourceRegistry.peek(instrumentation.targetContext)
                if(candidate?.recoveryStatus?.sourceAccepted==true && candidate.luminaGateway?.currentSession()!=null) {
                    candidate.borrowSession().use { ready=it.sonar.frames.value!=null }
                }
            }
        }
        assertTrue("Real source was not ready",ready)
        instrumentation.runOnMainSync {
            owner=HelmSourceRegistry.requireOwner(instrumentation.targetContext)
            assertTrue(owner.recoveryStatus.sourceAccepted)
            borrowed=owner.borrowSession();generation=owner.sessionGeneration
        }
        try {
            repeat(3) {
                // Distinct issue/expiry instants; keep the real wall clock and token TTL unchanged.
                Thread.sleep(1_100)
                var previous="";var changed=false
                instrumentation.runOnMainSync {
                    val gateway=checkNotNull(owner.luminaGateway)
                    previous=checkNotNull(gateway.currentSession()).identity
                    gateway.refresh()
                }
                val deadline=android.os.SystemClock.elapsedRealtime()+6_000
                while(!changed && android.os.SystemClock.elapsedRealtime()<deadline) {
                    Thread.sleep(50)
                    instrumentation.runOnMainSync {
                        changed=owner.luminaGateway?.currentSession()?.identity?.let{it!=previous}==true
                    }
                }
                assertTrue("Real authenticated renewal did not complete",changed)
                instrumentation.runOnMainSync {
                    assertTrue(owner.synchronizeHome(requestRefresh=false))
                    assertEquals("A pure credential renewal retired the source/history owner",generation,owner.sessionGeneration)
                    val frame=checkNotNull(borrowed.sonar.frames.value)
                    assertFalse(frame.simulated)
                    assertEquals(SonarPingSource.OWN,frame.pingSource)
                    assertEquals("0.4.28",frame.hostPackageVersion)
                    assertTrue(System.currentTimeMillis()-frame.frame.timestampEpochMs in -2_000..2_000)
                }
            }
        } finally {instrumentation.runOnMainSync{borrowed.close()};activity.close()}
    }
}
