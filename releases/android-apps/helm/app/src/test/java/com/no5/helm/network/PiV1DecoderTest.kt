package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.test.*

/** The files are emitted by the Python simulator, not duplicated Android-only examples. */
internal fun wireFixture(feature: String): String {
    val file = listOf(File("../simulator/tests/fixtures/$feature-v1.json"), File("simulator/tests/fixtures/$feature-v1.json"))
        .firstOrNull(File::isFile) ?: error("Missing canonical simulator fixture: $feature")
    return file.readText()
}

class PiV1DecoderTest {
    @Test fun canonicalHelmPreservesRealZeroUnknownValuesAndSimulationProvenance() {
        val state = PiV1Decoder.helm(wireFixture("helm"))
        assertEquals(0.0, state.portRpm)
        assertEquals(0.0, state.speed)
        assertEquals("N", state.portGear)
        assertEquals(12.0, state.depth)
        assertNull(state.waterTemp)
        assertNull(state.courseOverGroundDegrees)
        assertEquals(0.0, state.tripDistanceNm)
        assertEquals("STOPPED", state.simulationPhase)
        assertEquals("fixture-run-0", state.simulationRunId)
        assertEquals(0.0, state.simulationElapsedSeconds)
        assertEquals(1.0, state.simulationTimeScale)
        assertTrue(state.quality.simulated)
        assertFalse(state.quality.demo)
        assertEquals(OperatingMode.DEMO, state.mode)
        assertEquals(TelemetryStatus.LOST, QualityEvaluator.evaluate(state.quality, state.quality.timestampEpochMs!! + 30_001))
    }

    @Test fun olderPacketsWithoutRunIdRemainReadableButInvalidIdsAreRejected() {
        val root = JSONObject(wireFixture("helm"))
        root.getJSONObject("simulation").remove("runId")
        assertNull(PiV1Decoder.helm(root.toString()).simulationRunId)
        for (bad in listOf("", "x\ny", 1, JSONObject.NULL)) {
            root.getJSONObject("simulation").put("runId", bad)
            assertFailsWith<PiProtocolException> { PiV1Decoder.helm(root.toString()) }
        }
    }

    @Test fun canonicalWeatherKeepsProviderSeparateFromDeliveryAndClock() {
        val state = PiV1Decoder.weather(wireFixture("weather"))
        assertEquals("NO5_WEATHER_SIMULATOR", state.provider?.id)
        assertEquals("NO5_PI_SIMULATOR", state.delivery?.gatewayId)
        assertEquals(20.0, state.values.airTemperatureC)
        assertEquals(225.0, state.values.windDirectionDegrees)
        assertTrue(state.simulated)
        assertEquals(TelemetryStatus.STALE, WeatherFreshness.evaluate(state, state.timestampEpochMs!! + 5_001))
    }

    @Test fun wrongEnvelopeUnitsTypesAndRangesAreRejected() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("version", 2) }, { it.put("version", "1") }, { it.put("kind", "weather") },
            { it.put("source", "") }, { it.put("simulated", false) },
            { it.put("timestampEpochMs", "1790996400000") }, { it.put("timestampEpochMs", 0) },
            { it.put("status", "CONNECTED") }, { it.put("vesselTime", "25:00:00") },
            { it.getJSONObject("units").put("speed", "km/h") },
            { it.getJSONObject("units").remove("depth") },
            { it.getJSONObject("values").put("portRpm", "0") },
            { it.getJSONObject("values").put("portRpm", JSONObject.NULL) },
            { it.getJSONObject("values").put("portTrim", 101) },
            { it.getJSONObject("values").put("heading", 360) },
            { it.getJSONObject("values").put("portGear", "D") },
            { it.getJSONObject("values").remove("depth") },
        )
        for (mutate in mutations) {
            val root = JSONObject(wireFixture("helm")); mutate(root)
            assertFailsWith<PiProtocolException> { PiV1Decoder.helm(root.toString()) }
        }
    }

    @Test fun permissiveJsonExtensionsAndResourceAbuseAreRejected() {
        val body = wireFixture("helm")
        val invalid = listOf("", "[]", "{", body + " garbage", body.replaceFirst("\"version\"", "'version'"),
            body.replaceFirst("\"version\": 1", "\"version\": 01"),
            body.replaceFirst("\"version\": 1", "\"version\": 1, \"version\": 1"),
            body.replaceFirst("\"version\": 1", "\"version\": 1, \"vers\\u0069on\": 1"),
            body.replaceFirst("\"version\": 1", "/*comment*/ \"version\": 1"),
            "[".repeat(30) + "0" + "]".repeat(30), " ".repeat(65_537))
        invalid.forEach { assertFailsWith<PiProtocolException> { PiV1Decoder.helm(it) } }
    }

    @Test fun unavailablePayloadCannotExposeRetainedNumbers() {
        val root = JSONObject(wireFixture("helm")).put("status", "LOST")
        val state = PiV1Decoder.helm(root.toString())
        assertEquals(TelemetryStatus.LOST, state.quality.status)
        assertEquals("fixture-run-0", state.sourceSessionId)
        assertEquals("fixture-run-0", state.simulationRunId)
        assertNull(state.latitudeDegrees)
        assertNull(state.fuelRemainingLiters)
        assertNull(state.depth)
        assertEquals(0.0, state.portRpm)
    }

    @Test fun wrongWeatherSourceWindUnitsOrCoordinatePairFailsClosed() {
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("provider").put("kind", "EXTERNAL") },
            { it.getJSONObject("delivery").put("gatewayId", "OTHER") },
            { it.getJSONObject("units").put("windDirectionDegrees", "deg_apparent") },
            { it.getJSONObject("values").put("latitudeDegrees", JSONObject.NULL) },
        )) {
            val root = JSONObject(wireFixture("weather")); mutate(root)
            assertFailsWith<PiProtocolException> { PiV1Decoder.weather(root.toString()) }
        }
    }
}
