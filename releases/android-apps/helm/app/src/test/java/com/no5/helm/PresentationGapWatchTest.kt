package com.no5.helm

import com.no5.helm.watchdog.PresentationGapWatch
import org.junit.Assert.*
import org.junit.Test

class PresentationGapWatchTest {
    @Test fun zeroClockAndFirstMissingFrameRemainObservable() {
        val p=PresentationGapWatch();p.admission("own",true,0)
        assertEquals(2_000_000_000L,p.maximumNs(2_000_000_000L));assertEquals(2_000_000_001L,p.maximumNs(2_000_000_001L))
    }
    @Test fun replacementViewCannotEraseTheGap() {
        val p=PresentationGapWatch();p.admission("own",true,0);p.presented("own",50)
        p.presented("own",2_000_000_051L)
        assertEquals(2_000_000_001L,p.maximumNs(2_000_000_060L))
    }
    @Test fun callbackHistoryRetainsGapBetweenSparseObservations() {
        val p=PresentationGapWatch();p.admission("own",true,0);p.presented("own",50)
        p.presented("own",2_000_000_051L);p.presented("own",2_000_000_060L)
        assertEquals(2_000_000_001L,p.maximumNs(2_000_000_070L))
    }
    @Test fun sourceSwitchRejectsLateOldCallbacks() {
        val p=PresentationGapWatch();p.admission("old",true,0);p.admission("new",true,500)
        p.presented("old",5_000_000_000L);p.presented("new",600);assertEquals(500L,p.maximumNs(650))
    }
    @Test fun freezeAndPauseAreExcludedAndCannotClearRecordedFailures() {
        val p=PresentationGapWatch();p.admission("own",true,0);p.presented("own",2_000_000_001L)
        p.admission("own",false,2_000_000_002L);p.presented("own",20_000_000_000L)
        p.admission("own",true,20_000_000_000L);p.presented("own",20_000_000_050L)
        assertEquals(2_000_000_001L,p.maximumNs(20_000_000_060L))
    }
    @Test fun clockReversalRemainsAStickyDiagnosticError() {
        val p=PresentationGapWatch();p.admission("own",true,100);p.presented("own",90)
        assertEquals(1,p.failures);assertEquals(Long.MAX_VALUE,p.maximumNs(110))
    }
}
