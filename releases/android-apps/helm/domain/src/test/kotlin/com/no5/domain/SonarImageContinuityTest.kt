package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

/** Continuity tags are image metadata, never edits to acquired samples or ping counts. */
class SonarImageContinuityTest {
    @Test fun transportBoundaryKeepsRawHistoryRateAndFrozenSegmentsButSplitsTheNextImageInterval() {
        var now=1_000_000L
        val model=SonarModel{now};model.connect("SYNTHETIC CONTINUITY");model.acquisition(true)
        model.updateContext(SonarEpoch("synthetic-continuity",1,1),SonarPingSource.OWN)
        val raw=ShortArray(1024){it.toShort()}
        fun next(id:Long) {model.frame(SonarFrame(raw,id,now),pingSource=SonarPingSource.OWN)}
        next(1);val first=model.ring.imageSegmentAt(0)
        model.setFreeze(true);model.markImageContinuityBoundary();now+=100;next(2)
        assertNotEquals(first,model.ring.imageSegmentAt(0));assertEquals(first,model.ring.imageSegmentAt(1))
        assertEquals(first,model.viewRing.imageSegmentAt(0));assertEquals(1,model.viewRing.count)
        assertEquals(2,model.ring.count)
        now+=500 // The existing rate display deliberately refreshes at most twice per second.
        assertEquals(2,model.measuredRate().eventCount)
        assertArrayEquals(raw,model.ring.atAge(0)!!.samples);assertArrayEquals(raw,model.ring.atAge(1)!!.samples)
        model.setFreeze(false);assertEquals(2,model.viewRing.count)
    }
}
