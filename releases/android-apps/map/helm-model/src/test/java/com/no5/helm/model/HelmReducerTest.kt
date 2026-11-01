package com.no5.helm.model
import org.junit.Test
import org.junit.Assert.*
class HelmReducerTest {
    private val ship = Ownship(GeoPoint(-33.9, 18.4), 0.1, 0.2, 3.0, 6.0)
    private fun snap(revision: Long = 4, id: String = "boat") = Snapshot(serverId = id, revision = revision, generatedAt = "2026-10-03T00:00:00Z", state = HelmProjection(ship, emptyList(), emptyList(), emptyList()))
    @Test fun continuousDeltaAtomicallyReplacesProjection() {
        val old = HelmReducer.snapshot(HelmState(), snap())
        val next = HelmReducer.delta(old, Delta(serverId = "boat", baseRevision = 4, revision = 5, generatedAt = "now", ownship = ship.copy(depthMetres = 8.0))) as Reduction.Applied
        assertEquals(5L, next.state.snapshot!!.revision); assertEquals(8.0, next.state.snapshot!!.state.ownship!!.depthMetres!!, 0.0)
        assertEquals(6.0, old.snapshot!!.state.ownship!!.depthMetres!!, 0.0)
    }
    @Test fun revisionGapStopsApplicationAndRequiresSnapshot() {
        val old = HelmReducer.snapshot(HelmState(), snap())
        val gap = HelmReducer.delta(old, Delta(serverId = "boat", baseRevision = 8, revision = 9, generatedAt = "now", ownship = ship.copy(depthMetres = 99.0))) as Reduction.Resync
        assertEquals(ConnectionState.SYNCING, gap.state.connection);assertEquals(old.snapshot, gap.state.snapshot)
    }
    @Test fun lossAndReconnect() {
        val live = HelmReducer.snapshot(HelmState(), snap())
        val lost = HelmReducer.lost(live, "closed")
        assertEquals(ConnectionState.DEGRADED, lost.connection);assertEquals(live.snapshot, lost.snapshot)
        assertEquals(ConnectionState.LIVE, HelmReducer.snapshot(lost, snap(7)).connection)
    }
    @Test fun serverRestartCanResetRevision() {
        val old = HelmReducer.snapshot(HelmState(), snap(100))
        assertEquals(0L, HelmReducer.snapshot(old, snap(0, "restart")).snapshot!!.revision)
    }
    @Test fun staleSnapshotAndDuplicateDeltaDoNotRollBack() {
        val old = HelmReducer.snapshot(HelmState(), snap(8))
        assertEquals(old, HelmReducer.snapshot(old, snap(3)))
        assertTrue(HelmReducer.delta(old, Delta(serverId = "boat", baseRevision = 6, revision = 7, generatedAt = "old")) is Reduction.Ignored)
    }
}
