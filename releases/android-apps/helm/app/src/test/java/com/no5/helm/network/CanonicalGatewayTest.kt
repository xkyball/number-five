package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.test.*

internal fun gatewayConformanceFixture(kind: String): String = sequenceOf(File("."), File("..")).map {
    File(it, "contracts/fixtures/real-$kind-v1.json")
}.first { it.isFile }.readText()

/** REAL markers here are contract examples, never observations of physical vessel hardware. */
class CanonicalGatewayTest {
    @Test fun theSameDecoderAcceptsRealAndSimulatedHelmWithPreservedProvenance() {
        val real = PiV1Decoder.helm(gatewayConformanceFixture("helm"))
        val simulated = PiV1Decoder.helm(wireFixture("helm"))
        assertEquals("NO5_CONFORMANCE_REAL", real.quality.source)
        assertFalse(real.quality.simulated)
        assertEquals(OperatingMode.LIVE, real.mode)
        assertNull(real.simulationRunId)
        assertNull(real.simulationElapsedSeconds)
        assertNull(real.simulationTimeScale)
        assertEquals(simulated.speed, real.speed)
        assertEquals(simulated.depth, real.depth)
        assertTrue(simulated.quality.simulated)
    }

    @Test fun realWeatherRetainsItsProviderAndObservationFreshness() {
        val root = JSONObject(gatewayConformanceFixture("weather"))
        val stamp = root.getLong("timestampEpochMs")
        root.put("observedAtEpochMs", stamp - 6_000)
        val real = PiV1Decoder.weather(root.toString())
        assertEquals(WeatherProviderKind.ONBOARD, real.provider?.kind)
        assertEquals("NO5_CONFORMANCE_REAL", real.delivery?.gatewayId)
        assertFalse(real.simulated)
        assertNull(real.simulation)
        assertEquals(TelemetryStatus.STALE, WeatherFreshness.evaluate(real, stamp))
    }

    @Test fun conflictingProvenanceSyntheticClocksOrUnsupportedValuesAreRejected() {
        val mutations = listOf<(JSONObject) -> Unit>(
            { it.put("simulated", true) },
            { it.put("sourceKind", "UNKNOWN") },
            { it.put("simulation", JSONObject(wireFixture("helm")).getJSONObject("simulation")) },
            { it.getJSONObject("capabilities").put("depth", false) },
            { it.getJSONObject("capabilities").put("commands", true) },
            { it.getJSONObject("values").put("latitudeDegrees", JSONObject.NULL) },
        )
        for (mutation in mutations) {
            val root = JSONObject(gatewayConformanceFixture("helm")); mutation(root)
            assertFailsWith<PiProtocolException> { PiV1Decoder.helm(root.toString()) }
        }
    }

    @Test fun realSourcesRetainTheirProvenanceThroughFailureAndRecovery() {
        val stamp = JSONObject(gatewayConformanceFixture("helm")).getLong("timestampEpochMs")
        val helmTimer = Timer()
        val weatherTimer = Timer()
        val helmTransport = Transport(gatewayConformanceFixture("helm"))
        val weatherTransport = Transport(gatewayConformanceFixture("weather"))
        val identity = SourceIdentity("canonical-real", SourceMode.PI, "GATEWAY TEST")
        val helm = NetworkPiHelmSource(identity, helmTransport, { stamp }, helmTimer)
        val weather = NetworkPiWeatherSource(identity, weatherTransport, { stamp }, weatherTimer)
        try {
            helmTimer.poll(); weatherTimer.poll()
            assertEquals(TelemetryStatus.LIVE, helm.snapshots.value.quality.status)
            helmTransport.failed = true; weatherTransport.failed = true
            helmTimer.poll(); weatherTimer.poll()
            assertEquals(TelemetryStatus.LOST, helm.snapshots.value.quality.status)
            assertFalse(helm.snapshots.value.quality.simulated)
            assertEquals("NO5_CONFORMANCE_REAL", helm.snapshots.value.quality.source)
            assertEquals(stamp, helm.snapshots.value.quality.timestampEpochMs)
            assertEquals("fixture-run-0", helm.snapshots.value.sourceSessionId)
            assertTrue(helm.snapshots.value.telemetryFields.isNotEmpty())
            assertNull(helm.snapshots.value.depth)
            assertEquals(TelemetryStatus.LOST, weather.snapshots.value.status)
            assertFalse(weather.snapshots.value.simulated)
            assertEquals(WeatherProviderKind.ONBOARD, weather.snapshots.value.provider?.kind)
            assertNull(weather.snapshots.value.values.airTemperatureC)
            helmTransport.failed = false; weatherTransport.failed = false
            helmTimer.poll(); weatherTimer.poll()
            assertEquals(TelemetryStatus.LIVE, helm.snapshots.value.quality.status)
            assertEquals(TelemetryStatus.LIVE, weather.snapshots.value.status)
            assertFalse(helm.snapshots.value.quality.simulated)
        } finally { helm.close(); weather.close() }
    }

    @Test fun nullableCoreAndOverspeedAreNotReplacedByPlausibleDemoValues() {
        val root = JSONObject(gatewayConformanceFixture("helm"))
        val stamp = root.getLong("publishedAtEpochMs")
        root.getJSONObject("values").put("portRpm", JSONObject.NULL).put("starboardRpm", 8_100.0)
        root.getJSONObject("fieldQuality").getJSONObject("portRpm")
            .put("status", "UNAVAILABLE").put("timestampEpochMs", JSONObject.NULL)
        val decoded = PiV1Decoder.helm(root.toString())
        assertNull(decoded.telemetryFields["portRpm"]?.value)
        assertNull(decoded.displayField("portRpm", decoded.portRpm, stamp))
        assertEquals(8_100.0, decoded.starboardRpm)
        assertEquals(8_100.0, decoded.displayField("starboardRpm", decoded.starboardRpm, stamp))
        assertEquals(0.0, decoded.displayField("speed", decoded.speed, stamp))
    }

    @Test fun freshGpsCannotRefreshOldEngineAndOldObservationDoesNotPoisonPublicationOrder() {
        val root = JSONObject(gatewayConformanceFixture("helm"))
        val observed = root.getLong("timestampEpochMs")
        var now = observed + 40_000
        root.put("publishedAtEpochMs", now)
        for (name in listOf("speed", "latitudeDegrees", "longitudeDegrees"))
            root.getJSONObject("fieldQuality").getJSONObject(name).put("timestampEpochMs", now)
        val timer = Timer()
        val transport = Transport(root.toString())
        val source = NetworkPiHelmSource(SourceIdentity("mixed-fields", SourceMode.PI, "TEST"), transport, { now }, timer)
        try {
            timer.poll()
            var state = source.snapshots.value
            assertEquals(TelemetryStatus.LIVE, state.quality.status)
            assertEquals(TelemetryStatus.LOST, state.fieldStatus("portRpm", now))
            assertEquals(TelemetryStatus.LIVE, state.fieldStatus("speed", now))
            assertNull(state.displayField("portRpm", state.portRpm, now))
            assertEquals(0.0, state.displayField("speed", state.speed, now))
            // An older valid sensor observation may appear in a newer publication.
            root.getJSONObject("capabilities").put("waterTemperature", true)
            root.getJSONObject("values").put("waterTemp", 19.0)
            root.getJSONObject("fieldQuality").getJSONObject("waterTemp").put("status", "LIVE").put("timestampEpochMs", observed - 1_000)
            root.put("timestampEpochMs", observed - 1_000).put("publishedAtEpochMs", ++now)
            transport.json = root.toString()
            timer.poll()
            state = source.snapshots.value
            assertEquals(TelemetryStatus.LIVE, state.quality.status)
            assertEquals(TelemetryStatus.LOST, state.fieldStatus("waterTemp", now))
            assertEquals(TelemetryStatus.LIVE, state.fieldStatus("latitudeDegrees", now))
        } finally { source.close() }
    }

    @Test fun newerPublicationCannotMoveAnAlreadyObservedFieldBackward() {
        val root = JSONObject(gatewayConformanceFixture("helm"))
        val stamp = root.getLong("publishedAtEpochMs")
        val timer = Timer()
        val transport = Transport(root.toString())
        val source = NetworkPiHelmSource(SourceIdentity("field-order", SourceMode.PI, "TEST"), transport, { stamp + 100 }, timer)
        try {
            timer.poll()
            root.getJSONObject("fieldQuality").getJSONObject("portRpm").put("timestampEpochMs", stamp - 1)
            root.put("timestampEpochMs", stamp - 1).put("publishedAtEpochMs", stamp + 100)
            transport.json = root.toString()
            timer.poll()
            assertEquals(TelemetryStatus.INVALID, source.snapshots.value.quality.status)
        } finally { source.close() }
    }

    @Test fun publisherRestartChangesContinuityAndRejectsRetiredSessionReplay() {
        val root = JSONObject(gatewayConformanceFixture("helm"))
        val stamp = root.getLong("publishedAtEpochMs")
        val timer = Timer()
        val transport = Transport(root.toString())
        val source = NetworkPiHelmSource(SourceIdentity("restart", SourceMode.PI, "TEST"), transport, { stamp + 100 }, timer)
        try {
            timer.poll()
            val original = source.snapshots.value.sourceSessionId
            root.put("serverSessionId", "restarted-publisher").put("publishedAtEpochMs", stamp + 50)
            transport.json = root.toString()
            timer.poll()
            assertEquals("restarted-publisher", source.snapshots.value.sourceSessionId)
            assertEquals(TelemetryStatus.LIVE, source.snapshots.value.quality.status)
            root.put("serverSessionId", original).put("publishedAtEpochMs", stamp + 100)
            transport.json = root.toString()
            timer.poll()
            assertEquals(TelemetryStatus.INVALID, source.snapshots.value.quality.status)
        } finally { source.close() }
    }

    @Test fun freshPublicationCannotHideFutureFieldTimestampOrWrongOldestObservation() {
        val root = JSONObject(gatewayConformanceFixture("helm"))
        val stamp = root.getLong("publishedAtEpochMs")
        root.getJSONObject("fieldQuality").getJSONObject("speed").put("timestampEpochMs", stamp + 6_000)
        val timer = Timer()
        val source = NetworkPiHelmSource(SourceIdentity("future-field", SourceMode.PI, "TEST"), Transport(root.toString()), { stamp }, timer)
        try { timer.poll(); assertEquals(TelemetryStatus.INVALID, source.snapshots.value.quality.status) }
        finally { source.close() }
        root.put("timestampEpochMs", stamp + 1)
        assertFailsWith<PiProtocolException> { PiV1Decoder.helm(root.toString()) }
    }

    private class Timer : PiPollScheduler {
        lateinit var poll: () -> Unit
        override fun schedule(intervalMs: Long, poll: () -> Unit): SourceSubscription {
            this.poll = poll
            return SourceSubscription { }
        }
    }
    private class Transport(var json: String) : PiHttpTransport {
        var failed = false
        override fun get(path: String): String { if (failed) throw IOException("Outage"); return json }
        override fun close() = Unit
    }
}
