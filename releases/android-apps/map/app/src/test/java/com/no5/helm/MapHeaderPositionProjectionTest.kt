package com.no5.helm

import com.no5.header.*
import com.no5.helm.model.*
import com.no5.helm.ui.*
import org.junit.Assert.*
import org.junit.Test

class MapHeaderPositionProjectionTest {
    private val backend = HelmState(ConnectionState.LIVE, Snapshot(serverId = "synthetic-backend",
        revision = 50_000, generatedAt = "1970-01-01T00:00:50Z",
        state = HelmProjection(null, emptyList(), emptyList(), emptyList()), gatewayV1 = true,
        gatewayTelemetryProvenance = GatewayTelemetryProvenance("synthetic-helm-overlay", true)))
    private fun context(position: NativeHeaderPosition? = null) = NativeHeaderContext(4, "CHART", false,
        backend, "GPS selected · HELM simulated", "READY", position)

    @Test fun selectedGpsClockAndProvenanceDoNotBecomeTheGlobalPublicationOrSimulationFlag() {
        val input = mapHeaderSourceInput(context(NativeHeaderPosition("Selected GPS", "synthetic.gps", 9,
            10_000, PositionStatus.LIVE, NativeGpsProvenance.REAL)))
        assertEquals("synthetic-backend", input.serverId)
        assertEquals("Selected GPS", input.sourceLabel)
        assertEquals("synthetic.gps", input.sourceId)
        assertEquals(10_000L, input.observedAtEpochMs)
        assertEquals(HeaderQuality.LIVE, input.quality)
        assertEquals(HeaderProvenance.REAL, input.provenance)
        assertTrue(backend.snapshot!!.gatewayTelemetryProvenance!!.simulated)
        val publication = HeaderPublication(HeaderApps.MAP, "synthetic-producer", 1, 1, "synthetic-scope", 1, 1, 100)
        val header = MapHeaderSnapshotBuilder.build(publication, HeaderPage.CHART, HeaderTheme.DAY, input)
        assertEquals("Selected GPS", header.source.label)
        assertEquals("synthetic-backend", header.source.backendSessionId)
        assertEquals(HeaderQuality.STALE, header.at(16_000).source.quality)
        assertEquals(HeaderQuality.LOST, header.at(41_000).source.quality)
    }

    @Test fun legacyOrUnconfirmedSelectionDoesNotInventQualityFromReadyTransport() {
        for (position in listOf(null, NativeHeaderPosition("Legacy GPS", null, 0, 10_000,
            PositionStatus.LIVE, NativeGpsProvenance.UNKNOWN))) {
            val input = mapHeaderSourceInput(context(position))
            assertNull(input.sourceId)
            assertEquals(HeaderQuality.UNKNOWN, input.quality)
            assertEquals(HeaderProvenance.UNKNOWN, input.provenance)
            if (position == null) assertNull(input.observedAtEpochMs)
            assertNotEquals(50_000L, input.observedAtEpochMs)
        }
    }

    @Test fun lostTransportCannotKeepOldSelectedGpsLiveWhileTheControllerUpdateIsQueued() {
        val oldSelection = NativeHeaderPosition("Selected GPS", "synthetic.gps", 9,
            10_000, PositionStatus.LIVE, NativeGpsProvenance.REAL)
        val lost = context(oldSelection).copy(backend = backend.copy(connection = ConnectionState.DEGRADED), transport = "STALE")
        val input = mapHeaderSourceInput(lost)
        assertEquals(HeaderQuality.LOST, input.quality)
        assertEquals(10_000L, input.observedAtEpochMs)
        assertEquals(HeaderProvenance.REAL, input.provenance)
    }
}
