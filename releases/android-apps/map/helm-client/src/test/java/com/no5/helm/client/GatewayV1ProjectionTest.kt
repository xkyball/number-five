package com.no5.helm.client

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GatewayV1ProjectionTest {
    private fun fixture() = File("../../helm/contracts/fixtures/real-helm-v1.json").readText()
    private fun now() = JSONObject(fixture()).getLong("publishedAtEpochMs")
    @Test fun canonicalGatewayOwnshipUsesObservedFieldsAndRetainsRealZero() {
        val snapshot = GatewayV1Projection.decode(fixture(), now())
        assertTrue(snapshot.gatewayV1); assertTrue(snapshot.sourceDetail!!.contains("REAL"))
        assertNotNull(snapshot.state.ownship)
        assertEquals(0.0, snapshot.state.ownship!!.sogMetresPerSecond!!, 0.0)
        assertNull(snapshot.state.ownship!!.cogRadians)
        assertEquals(-34.3, snapshot.state.ownship!!.position.latitude, 0.0)
        assertTrue(snapshot.state.ais.isEmpty()); assertTrue(snapshot.state.routes.isEmpty())
        assertNotNull(snapshot.fixObservedAtEpochMs)
        assertEquals(JSONObject(fixture()).getString("source"), snapshot.gatewayTelemetryProvenance!!.source)
        assertFalse(snapshot.gatewayTelemetryProvenance!!.simulated)
    }
    @Test fun repeatedPublicationCannotFreshenOldPositionOrOtherFields() {
        val root = JSONObject(fixture()); val old = now()
        root.put("publishedAtEpochMs", old + 6000)
        val snapshot = GatewayV1Projection.decode(root.toString(), old + 6000)
        assertNull(snapshot.state.ownship); assertNull(snapshot.fixObservedAtEpochMs)
        assertTrue(snapshot.sourceDetail!!.contains("veraltet"))
    }
    @Test fun missingNumericObservationNeverTurnsIntoDepthOrHeadingZero() {
        val root = JSONObject(fixture())
        root.getJSONObject("values").put("depth", JSONObject.NULL).put("heading", JSONObject.NULL)
        root.getJSONObject("fieldQuality").getJSONObject("depth").put("status", "UNAVAILABLE")
        root.getJSONObject("fieldQuality").getJSONObject("heading").put("status", "UNAVAILABLE")
        val ship = GatewayV1Projection.decode(root.toString(), now()).state.ownship!!
        assertNull(ship.depthMetres); assertNull(ship.headingRadians)
    }
    @Test fun wrongContractUnitsAndDuplicateFieldsAreRejected() {
        val root = JSONObject(fixture()); root.getJSONObject("units").put("position", "radians")
        try { GatewayV1Projection.decode(root.toString(), now()); fail("Wrong units") } catch (_: IllegalArgumentException) { }
        try { GatewayV1Projection.decode(fixture().replaceFirst("{", "{\"version\":1,"), now()); fail("Duplicate field") } catch (_: IllegalArgumentException) { }
    }
    @Test fun simulatedHelmEnvelopeRetainsIndependentRealPositionCatalogueAndOriginalTimes() {
        val root = JSONObject(fixture()); val stamp = now()
        root.put("source", "synthetic-helm-overlay").put("sourceKind", "SIMULATED").put("simulated", true)
        root.put("simulation", JSONObject().put("elapsedSeconds", 0).put("observedAtEpochMs", stamp)
            .put("running", true).put("timeScale", 1).put("phase", "synthetic").put("scenario", "synthetic"))
        val candidate = JSONObject().put("sourceId", "synthetic.real.gps").put("sourceRef", "synthetic.real.gps")
            .put("label", "synthetic.real.gps").put("status", "LIVE").put("observedAtEpochMs", stamp - 100)
            .put("position", JSONObject().put("latitudeDegrees", 0.0).put("longitudeDegrees", 0.0)
                .put("altitudeMetres", JSONObject.NULL)).put("gnss", JSONObject())
        root.put("positionSources", JSONObject().put("version", 1).put("sourceKind", "REAL").put("simulated", false)
            .put("primarySourceId", "synthetic.real.gps").put("total", 1).put("omitted", 0)
            .put("freshness", JSONObject().put("liveForMs", 5000).put("lostAfterMs", 30000))
            .put("sources", org.json.JSONArray().put(candidate))
            .put("combination", JSONObject().put("available", false).put("reason", "COMBINATION_NOT_CONFIGURED")))
        val snapshot = GatewayV1Projection.decode(root.toString(), stamp)
        assertTrue(snapshot.gatewayTelemetryProvenance!!.simulated)
        assertEquals("synthetic-helm-overlay", snapshot.gatewayTelemetryProvenance!!.source)
        assertTrue(snapshot.sourceDetail!!.contains("SIMULATED"))
        assertTrue(snapshot.positionSources!!.valid)
        assertFalse(snapshot.positionSources!!.simulated)
        assertEquals(stamp - 100, snapshot.positionSources!!.sources.single().observedAtEpochMs)
        assertEquals(com.no5.helm.model.GeoPoint(0.0, 0.0), snapshot.positionSources!!.sources.single().position)
        assertNotEquals(snapshot.state.ownship!!.position, snapshot.positionSources!!.sources.single().position)
    }
}
