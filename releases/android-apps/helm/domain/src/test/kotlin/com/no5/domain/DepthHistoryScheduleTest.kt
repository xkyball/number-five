package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class DepthHistoryScheduleTest {
    @Test fun onlyObservationExpiryAndFutureVisibilityChangeSnapshotScheduling() {
        val history=DepthHistory(windowMs=60000)
        assertNull(history.nextSnapshotChange(100000))
        val raw=HelmState(depth=5.0,quality=TelemetryQuality(TelemetryStatus.LIVE,101000,"fixture"))
        assertTrue(history.accept(raw,100000,"one"))
        val revision=history.version()
        assertEquals(101000L,history.nextSnapshotChange(100000))
        assertTrue(history.snapshot(100000).points.isEmpty())
        assertEquals(1,history.snapshot(101000).points.size)
        assertEquals(161001L,history.nextSnapshotChange(101000))
        assertEquals(revision,history.version())
        assertTrue(history.snapshot(161001).points.isEmpty())
        assertTrue(history.version()>revision)
        assertNull(history.nextSnapshotChange(161001))
    }
}
