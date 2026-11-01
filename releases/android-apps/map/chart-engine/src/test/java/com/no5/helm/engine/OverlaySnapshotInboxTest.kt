package com.no5.helm.engine

import com.no5.helm.chart.ChartOverlays
import com.no5.helm.chart.GeoProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaySnapshotInboxTest {
    private fun overlay(latitude: Double) = ChartOverlays(ownship = GeoProjection(latitude, -71.0))

    @Test fun producerMutationCannotChangeQueuedRouteOrMetadata() {
        val points = mutableListOf(GeoProjection(42.0, -71.0)); val ids = mutableListOf("p1")
        val value = ChartOverlays(route = com.no5.helm.chart.RouteProjection(points, pointIds = ids))
        val inbox = OverlaySnapshotInbox(); inbox.offer(value)
        points.clear(); ids[0] = "foreign"
        val frozen = inbox.take()!!; assertEquals(1, frozen.route!!.points.size); assertEquals("p1", frozen.route!!.pointIds.single())
    }
    @Test fun unchangedSnapshotIsSuppressedAfterItWasApplied() {
        val inbox = OverlaySnapshotInbox()
        val latest = overlay(42.0)

        assertTrue(inbox.offer(latest))
        assertEquals(latest, inbox.take())
        inbox.markApplied(latest)

        assertFalse(inbox.offer(latest))
        assertNull(inbox.take())
    }

    @Test fun pendingSnapshotIsReplacedByTheNewestCompleteValue() {
        val inbox = OverlaySnapshotInbox()
        val first = overlay(42.0)
        val latest = overlay(42.2)

        assertTrue(inbox.offer(first))
        assertTrue(inbox.offer(latest))
        assertEquals(latest, inbox.take())
    }

    @Test fun inFlightSnapshotDoesNotHideANewerPendingValue() {
        val inbox = OverlaySnapshotInbox()
        val first = overlay(42.0)
        val middle = overlay(42.1)
        val latest = overlay(42.2)

        assertTrue(inbox.offer(first))
        assertEquals(first, inbox.take())
        assertTrue(inbox.offer(middle))
        assertTrue(inbox.offer(latest))
        inbox.markApplied(first)

        assertEquals(latest, inbox.take())
    }

    @Test fun rejectedSnapshotMayBeRetried() {
        val inbox = OverlaySnapshotInbox()
        val latest = overlay(42.0)

        assertTrue(inbox.offer(latest))
        assertEquals(latest, inbox.take())
        inbox.markRejected(latest)

        assertTrue(inbox.offer(latest))
    }
    @Test fun benchmarkFreezeDoesNotConsumeOrLoseAnOverlayOfferedImmediatelyBeforeStart() {
        val previous = overlay(42.0)
        val latest = overlay(42.2)
        val inbox = OverlaySnapshotInbox(previous)
        assertTrue(inbox.offer(latest))
        repeat(35) { assertNull(inbox.take(frozen = true)) }
        inbox.markApplied(previous) // Native state has been restored before live updates resume.
        assertEquals(latest, inbox.take())
        inbox.markApplied(latest)
        assertFalse(inbox.offer(latest))
    }
}
