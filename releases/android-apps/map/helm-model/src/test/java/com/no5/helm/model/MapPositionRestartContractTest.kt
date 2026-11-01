package com.no5.helm.model

import org.junit.Assert.*
import org.junit.Test

/** New-owner contract only. These synthetic fixtures do not claim an Android process restart. */
class MapPositionRestartContractTest {
    private class Camera : PositionCamera {
        val sequences = mutableListOf<Long>()
        override fun center(latitude: Double, longitude: Double, sequence: Long, physicalUptimeMs: Long): Long {
            sequences += sequence
            return sequences.size.toLong()
        }
        override fun cancelPending() = Unit
    }

    private fun fix(observed: Long, publication: Long = observed, server: String = "synthetic-epoch") = HelmState(
        ConnectionState.LIVE, Snapshot(serverId = server, revision = publication, generatedAt = publication.toString(),
            state = HelmProjection(null, emptyList(), emptyList(), emptyList()), gatewayV1 = true,
            positionSources = PositionSources(primarySourceId = "synthetic.gps", total = 1, sources = listOf(
                PositionCandidate("synthetic.gps", "synthetic.gps", "synthetic.gps", PositionStatus.LIVE,
                    observed, GeoPoint(0.0, 0.0))))))

    @Test fun centeredIsNotPersistentAndNewOwnerNeverReplaysTheOneShot() {
        var stored = PositionIntent()
        val oldCamera = Camera()
        val old = MapPositionController(oldCamera, stored) { stored = it }
        old.update(fix(10_000), 10_000)
        assertEquals(1L, old.cyclePosition(10_000, 741, 100))
        assertEquals(PositionMode.CENTERED, old.state.value.mode)
        assertFalse(stored.follow)

        val nextCamera = Camera()
        val next = MapPositionController(nextCamera, stored)
        assertEquals(PositionMode.OFF, next.state.value.mode)
        assertNull(next.state.value.ownship)
        next.update(fix(10_000, publication = 10_500), 10_500)
        assertNull(next.state.value.ownship)
        assertEquals(0L, next.follow(10_500, 101))
        next.update(fix(10_501), 10_501)
        assertNotNull(next.state.value.ownship)
        assertEquals(PositionMode.OFF, next.state.value.mode)
        assertEquals(0L, next.follow(10_501, 102))
        assertTrue(nextCamera.sequences.isEmpty())
        assertEquals(listOf(741L), oldCamera.sequences)
    }

    @Test fun savedFollowWaitsForNewOriginalFixAfterTheNewTransportFenceAndRunsOnce() {
        val camera = Camera()
        val restored = MapPositionController(camera,
            PositionIntent(choice = PositionChoice.MANUAL, sourceId = "synthetic.gps", follow = true))
        assertEquals(PositionMode.FOLLOW, restored.state.value.mode)
        assertFalse(restored.state.value.followActive)
        assertNull(restored.state.value.ownship)
        assertEquals(0L, restored.follow(20_000, 1))

        // This is still fresh by age, but predates this owner's connection. A new envelope is not a new fix.
        restored.update(fix(19_999, publication = 20_000), 20_000)
        repeat(3) { i ->
            restored.update(fix(19_999, publication = 20_001L + i), 20_001L + i)
            assertTrue(restored.state.value.awaitingFreshFix)
            assertFalse(restored.state.value.followActive)
            assertEquals(0L, restored.follow(20_001L + i, 2L + i))
        }
        assertTrue(camera.sequences.isEmpty())
        restored.update(fix(20_004), 20_004)
        assertTrue(restored.state.value.followActive)
        assertEquals(1L, restored.follow(20_004, 6))
        repeat(3) { assertEquals(0L, restored.follow(20_004, 7L + it)) }
        // Follow is a fresh semantic camera intent; the old physical one-shot sequence is never stored/replayed.
        assertEquals(listOf(0L), camera.sequences)
    }

    @Test fun restoredFollowCannotUseAFormerSessionFixEvenIfItsPublicationIsNew() {
        val camera = Camera()
        val restored = MapPositionController(camera, PositionIntent(follow = true))
        restored.update(fix(30_000), 30_000)
        assertEquals(1L, restored.follow(30_000, 1))
        restored.update(fix(30_000, publication = 30_100, server = "synthetic-reconnected"), 30_100)
        assertFalse(restored.state.value.followActive)
        assertEquals(0L, restored.follow(30_100, 2))
        restored.update(fix(30_101, server = "synthetic-reconnected"), 30_101)
        assertEquals(2L, restored.follow(30_101, 3))
        assertEquals(listOf(0L, 0L), camera.sequences)
    }
}
