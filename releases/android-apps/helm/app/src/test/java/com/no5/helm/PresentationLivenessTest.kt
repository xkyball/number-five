package com.no5.helm

import com.no5.helm.watchdog.PresentationLiveness as P
import org.junit.Assert.*
import org.junit.Test

class PresentationLivenessTest {
    @Test fun healthyIdleHasNoRefreshOrMissingFrame(){assertFalse(P.missing(10,20,30,20,9_000_000_000));assertFalse(P.progressing(20,30,20,30))}
    @Test fun actualWorkerStallIsDetectedAfterOneSecond(){assertTrue(P.missing(10,20,0,10,1_000_000_010))}
    @Test fun knownForwardProgressCannotExtendTwoSeconds(){assertFalse(P.missing(10,20,0,2_000_000_000,2_000_000_010));assertTrue(P.missing(10,20,0,2_000_000_011,2_000_000_011))}
    @Test fun enqueuedPingsCannotHideAnUnmovingWorker(){assertTrue(P.missing(10,1_500_000_000,0,10,1_500_000_000));assertFalse(P.progressing(1_500_000_000,0,10,1_500_000_000))}
}
