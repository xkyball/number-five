package com.no5.helm.ui

import com.no5.helm.model.*
import org.junit.Assert.*
import org.junit.Test

class ChartOwnshipFreshnessTest {
    private fun state(observed: Long?, gateway: Boolean = true, connection: ConnectionState = ConnectionState.LIVE) = HelmState(connection,
        Snapshot(serverId = "test-gateway", revision = 1, generatedAt = "2026-10-13T00:00:00Z",
            state = HelmProjection(Ownship(GeoPoint(0.0, 0.0), null, null, null, null), emptyList(), emptyList(), emptyList()),
            gatewayV1 = gateway, fixObservedAtEpochMs = observed))
    @Test fun zeroCoordinatesAreRealAndOnlyTheirObservationAdmitsFollowing() {
        assertEquals(GeoPoint(0.0, 0.0), currentOwnship(state(1000), 1000)!!.position)
        assertNotNull(currentOwnship(state(1000), 6000))
        assertNull(currentOwnship(state(1000), 6001))
        assertNull(currentOwnship(state(null), 1000))
        assertNull(currentOwnship(state(1001), 1000))
        assertNull(currentOwnship(state(1000, gateway = false), 1000))
        assertNull(currentOwnship(state(1000, connection = ConnectionState.DEGRADED), 1000))
    }
}
