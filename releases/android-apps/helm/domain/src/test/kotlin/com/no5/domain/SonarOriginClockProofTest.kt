package com.no5.domain
import org.junit.Assert.*
import org.junit.Test

class SonarOriginClockProofTest {
    private val request=SonarOriginClockRequest("gateway",1,2,3,100000)
    @Test fun entireQueueNetworkAndWaitingTimeCountsWithoutGuestWallClock() {
        val proof=SonarOriginClockProof(request,101000,1_000_000_000,1_250_000_000)
        assertEquals(1250L,proof.upperAgeMs(1_250_000_000))
        assertEquals(2000L,proof.upperAgeMs(2_000_000_000))
        assertNull(proof.upperAgeMs(2_000_000_001))
    }
    @Test fun futureUnknownReversedAndOverflowRejected() {
        assertNull(SonarOriginClockProof(request,99999,1,2).upperAgeMs(3))
        assertNull(SonarOriginClockProof(request,101000,2,1).upperAgeMs(3))
        assertNull(SonarOriginClockProof(request,101000,1,3).upperAgeMs(2))
        assertNull(SonarOriginClockProof(request,Long.MAX_VALUE,1,2).upperAgeMs(Long.MAX_VALUE))
    }
}
