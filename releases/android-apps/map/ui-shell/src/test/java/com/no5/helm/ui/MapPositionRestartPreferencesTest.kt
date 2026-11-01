package com.no5.helm.ui

import android.content.Context
import com.no5.helm.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real preference codec, synthetic replacement owners. NOT Android process-loss evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MapPositionRestartPreferencesTest {
    private class Camera : PositionCamera {
        var requests = 0
        override fun center(latitude: Double, longitude: Double, sequence: Long, physicalUptimeMs: Long): Long {
            requests++
            return requests.toLong()
        }
        override fun cancelPending() = Unit
    }

    private fun fix(observed: Long) = HelmState(ConnectionState.LIVE,
        Snapshot(serverId = "synthetic-restart", revision = observed, generatedAt = "synthetic", gatewayV1 = true,
            state = HelmProjection(null, emptyList(), emptyList(), emptyList()),
            positionSources = PositionSources(primarySourceId = "synthetic.gps", total = 1, sources = listOf(
                PositionCandidate("synthetic.gps", "synthetic.gps", "synthetic.gps", PositionStatus.LIVE,
                    observed, GeoPoint(0.0, 0.0))))))

    @Test fun persistedCenteredIntentReloadsOffAndDoesNotContainAPositionOrCommand() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("synthetic-centered-restart", Context.MODE_PRIVATE)
        val store = MapPositionPreferences(preferences)
        store.save(PositionIntent(choice = PositionChoice.MANUAL, sourceId = "synthetic.gps"))
        val old = MapPositionController(Camera(), store.load(), store::save)
        old.update(fix(10_000), 10_000)
        assertTrue(old.cyclePosition(10_000, 741, 100) > 0)
        assertEquals(PositionMode.CENTERED, old.state.value.mode)
        val encoded = preferences.getString("positionIntentV1", null)!!
        assertFalse(encoded.contains("CENTERED"))
        for (forbidden in listOf("latitude", "longitude", "observedAt", "command", "741")) assertFalse(forbidden, encoded.contains(forbidden))
        val camera = Camera()
        val replacement = MapPositionController(camera, MapPositionPreferences(preferences).load())
        assertEquals(PositionMode.OFF, replacement.state.value.mode)
        replacement.update(fix(10_001), 10_001)
        assertEquals(0L, replacement.follow(10_001, 101))
        assertEquals(0, camera.requests)
    }

    @Test fun persistedFollowReloadsAsIntentOnlyAndRequiresNewOriginalObservation() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("synthetic-follow-restart", Context.MODE_PRIVATE)
        val store = MapPositionPreferences(preferences)
        val intent = PositionIntent(choice = PositionChoice.MANUAL, sourceId = "synthetic.gps", follow = true)
        store.save(intent)
        val camera = Camera()
        val replacement = MapPositionController(camera, MapPositionPreferences(preferences).load())
        assertEquals(intent, replacement.state.value.intent)
        assertNull(replacement.state.value.ownship)
        assertFalse(replacement.state.value.followActive)
        replacement.update(fix(19_999), 20_000)
        assertNull(replacement.state.value.ownship)
        assertEquals(0L, replacement.follow(20_000, 1))
        replacement.update(fix(20_001), 20_001)
        assertTrue(replacement.state.value.followActive)
        assertEquals(1L, replacement.follow(20_001, 2))
        assertEquals(0L, replacement.follow(20_001, 3))
        assertEquals(1, camera.requests)
    }
}
