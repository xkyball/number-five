package com.no5.helm
import org.junit.Assert.*
import org.junit.Test

class SonarStatusPolicyTest {
    @Test fun onlyExactLocalSubscriptionMarkersAreInformational(){
        assertEquals(SonarStatusReason.WAITING,sonarProfileIntentStatus("PROFILE_DEMAND_PAUSED"))
        assertEquals(SonarStatusReason.PREPARING,sonarProfileIntentStatus("PROFILE_DEMAND_RESUMING"))
        assertNull(sonarProfileIntentStatus("native_reference_lost"))
        assertNull(sonarProfileIntentStatus("HTTP_READ_FAILED"));assertNull(sonarProfileIntentStatus(null))
    }
    @Test fun briefBackpressureNeverClaimsThePictureIsOld(){
        val p=SonarStatusPolicy()
        repeat(100){i->val s=p.update("source",if(i%2==0)SonarStatusReason.BACKPRESSURE else SonarStatusReason.LIVE,i*50_000_000L)
            assertEquals(SonarStatusLevel.NORMAL,s.symbolLevel);assertNull(s.currentIncident);assertNull(s.lastIncident)}
    }
    @Test fun currentProblemIsImmediateAndFiveHealthySecondsAreRequired(){
        val p=SonarStatusPolicy()
        assertEquals(SonarStatusLevel.WARNING,p.update("a",SonarStatusReason.STALE,10).symbolLevel)
        val ended=p.update("a",SonarStatusReason.LIVE,1_000_000_000)
        assertTrue(ended.recovering);assertEquals("Wieder aktuell",ended.label);assertNull(ended.currentIncident)
        assertEquals(SonarStatusReason.STALE,ended.lastIncident!!.reason)
        assertEquals(SonarStatusLevel.WARNING,p.update("a",SonarStatusReason.LIVE,5_999_999_999).symbolLevel)
        assertEquals(SonarStatusLevel.NORMAL,p.update("a",SonarStatusReason.LIVE,6_000_000_000).symbolLevel)
    }
    @Test fun repeatedFaultsRestartRecoveryWithoutLosingTheirRealCurrentState(){
        val p=SonarStatusPolicy()
        p.update("a",SonarStatusReason.RENDER_ERROR,0)
        p.update("a",SonarStatusReason.LIVE,1_000_000_000)
        assertEquals(SonarStatusLevel.ERROR,p.update("a",SonarStatusReason.STALE,5_000_000_000).symbolLevel)
        val recover=p.update("a",SonarStatusReason.FROZEN,6_000_000_000)
        assertEquals("Ansicht eingefroren",recover.label)
        assertEquals(SonarStatusReason.STALE,recover.lastIncident!!.reason)
        assertEquals(SonarStatusLevel.ERROR,p.update("a",SonarStatusReason.LIVE,10_999_999_999).symbolLevel)
        assertEquals(SonarStatusLevel.NORMAL,p.update("a",SonarStatusReason.LIVE,11_000_000_000).symbolLevel)
    }
    @Test fun sourceChangeResetsIncidentAndClockRegressionCannotClearIt(){
        val p=SonarStatusPolicy();p.update("a",SonarStatusReason.STALE,100)
        p.update("a",SonarStatusReason.LIVE,200)
        assertTrue(p.update("a",SonarStatusReason.LIVE,199).recovering)
        val fresh=p.update("b",SonarStatusReason.LIVE,300)
        assertEquals(SonarStatusLevel.NORMAL,fresh.symbolLevel);assertNull(fresh.lastIncident)
    }
}
