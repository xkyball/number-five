package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.junit.Test
import java.io.IOException
import kotlin.test.*

/** Real model weather may be delivered beside a simulated vessel on the same gateway. */
class GatewayForecastWeatherTest {
    private val fetched = 1_791_000_000_000L
    private val valid = fetched - 15 * 60_000L
    private val identity = SourceIdentity("forecast-test", SourceMode.PI, "GATEWAY TEST")

    private fun forecast(): JSONObject = JSONObject(gatewayConformanceFixture("weather")).apply {
        put("source", "NO5_PI_SIMULATOR")
        put("timestampEpochMs", fetched)
        put("publishedAtEpochMs", fetched)
        put("observedAtEpochMs", valid)
        remove("simulation")
        put("provider", JSONObject().put("id", "OPEN_METEO").put("label", "Open-Meteo · weather model").put("kind", "FORECAST"))
        getJSONObject("delivery").put("gatewayId", "NO5_PI_SIMULATOR")
        put("pressureReference", "MEAN_SEA_LEVEL")
        put("provenance", JSONObject().put("validAtEpochMs", valid).put("fetchedAtEpochMs", fetched)
            .put("requestedLatitude", -34.3).put("requestedLongitude", 18.6)
            .put("measurementKind", "MODELLED_CURRENT_WEATHER"))
        getJSONObject("values").put("latitudeDegrees", -34.25).put("longitudeDegrees", 18.625)
    }

    @Test fun forecastKeepsRealProvenanceValidTimeFetchTimeAndProviderGridDistinct() {
        val state = PiV1Decoder.weather(forecast().toString())
        assertFalse(state.simulated)
        assertNull(state.simulation)
        assertEquals(WeatherProviderKind.FORECAST, state.provider?.kind)
        assertEquals("NO5_PI_SIMULATOR", state.delivery?.gatewayId)
        assertEquals(valid, state.observedAtEpochMs)
        assertEquals(fetched, state.timestampEpochMs)
        assertNull(state.receivedAtEpochMs)
        assertEquals(WeatherLocation(-34.3, 18.6), state.requestedLocation)
        assertEquals(-34.25, state.values.latitudeDegrees)
        assertEquals(18.625, state.values.longitudeDegrees)
        assertEquals(WeatherPressureReference.MEAN_SEA_LEVEL, state.pressureReference)
    }

    @Test fun forecastUsesModelCadenceAndReplayedFetchCannotRefreshOldValidity() {
        var now = fetched
        val timer = Timer()
        val transport = Transport(forecast().toString())
        val source = NetworkPiWeatherSource(identity, transport, { now }, timer)
        try {
            timer.poll()
            assertEquals(WeatherFreshness.FORECAST, source.freshnessPolicy)
            assertEquals(TelemetryStatus.LIVE, source.snapshots.value.status)
            assertEquals("FORECAST · LIVE", WeatherPresentation.from(source.snapshots.value, now, source.freshnessPolicy).statusLabel)
            now += 31_000
            timer.poll()
            assertEquals(TelemetryStatus.LIVE, source.snapshots.value.status)
            assertEquals(now, source.snapshots.value.receivedAtEpochMs)
            assertEquals(fetched, source.snapshots.value.timestampEpochMs)
            now = valid + 30 * 60_000L + 1
            timer.poll()
            assertEquals(TelemetryStatus.STALE, source.snapshots.value.status)
            assertNotNull(source.snapshots.value.values.airTemperatureC)
            // Even a newly fetched response containing the old model interval must expire.
            now = valid + 60 * 60_000L + 1
            val refreshed = forecast().put("timestampEpochMs", now).put("publishedAtEpochMs", now)
            refreshed.getJSONObject("provenance").put("fetchedAtEpochMs", now)
            transport.body = refreshed.toString()
            timer.poll()
            assertEquals(TelemetryStatus.LOST, source.snapshots.value.status)
            assertEquals(WeatherValues(), source.snapshots.value.values)
            assertEquals("—", WeatherPresentation.from(source.snapshots.value, now, source.freshnessPolicy).airTemperature)
        } finally { source.close() }
    }

    @Test fun transportFailureRetainsOnlyBoundedForecastCacheAndRecoveryClearsError() {
        var now = fetched
        val timer = Timer()
        val transport = Transport(forecast().toString())
        val source = NetworkPiWeatherSource(identity, transport, { now }, timer)
        try {
            transport.failed = true
            timer.poll()
            assertEquals(TelemetryStatus.UNAVAILABLE, source.snapshots.value.status)
            assertEquals(WeatherValues(), source.snapshots.value.values)
            transport.failed = false
            timer.poll()
            val accepted = source.snapshots.value
            transport.failed = true
            now += 60_000
            timer.poll()
            assertEquals(TelemetryStatus.STALE, source.snapshots.value.status)
            assertEquals(accepted.values, source.snapshots.value.values)
            assertEquals(fetched, source.snapshots.value.receivedAtEpochMs)
            assertEquals(fetched, source.snapshots.value.timestampEpochMs)
            assertEquals(valid, source.snapshots.value.observedAtEpochMs)
            assertNotNull(source.snapshots.value.error)
            transport.failed = false
            timer.poll()
            assertEquals(TelemetryStatus.LIVE, source.snapshots.value.status)
            assertNull(source.snapshots.value.error)
            transport.failed = true
            now = valid + 60 * 60_000L + 1
            timer.poll()
            assertEquals(TelemetryStatus.LOST, source.snapshots.value.status)
            assertEquals(WeatherValues(), source.snapshots.value.values)
        } finally { source.close() }
    }

    @Test fun invalidReplyClearsForecastAndCannotBeResurrectedByLaterFailure() {
        val timer = Timer()
        val transport = Transport(forecast().toString())
        val source = NetworkPiWeatherSource(identity, transport, { fetched }, timer)
        try {
            timer.poll()
            transport.body = "{broken"
            timer.poll()
            assertEquals(TelemetryStatus.INVALID, source.snapshots.value.status)
            assertEquals(WeatherValues(), source.snapshots.value.values)
            transport.failed = true
            timer.poll()
            assertEquals(TelemetryStatus.LOST, source.snapshots.value.status)
            assertEquals(WeatherValues(), source.snapshots.value.values)
        } finally { source.close() }
    }

    @Test fun declaredStaleCacheStaysStaleAndExplicitUnavailableHidesValues() {
        val timer = Timer()
        val transport = Transport(forecast().put("status", "STALE").toString())
        val source = NetworkPiWeatherSource(identity, transport, { fetched }, timer)
        try {
            timer.poll()
            assertEquals(TelemetryStatus.STALE, source.snapshots.value.status)
            assertNotNull(source.snapshots.value.values.airTemperatureC)
            transport.body = forecast().put("status", "UNAVAILABLE").toString()
            timer.poll()
            assertEquals(TelemetryStatus.UNAVAILABLE, source.snapshots.value.status)
            assertEquals(WeatherValues(), source.snapshots.value.values)
        } finally { source.close() }
    }

    @Test fun validZeroNullAndMissingWeatherValuesRemainDistinct() {
        val root = forecast()
        root.getJSONObject("values").put("airTemperatureC", 0.0).put("windSpeedKn", 0.0)
            .put("windDirectionDegrees", 0.0).put("relativeHumidityPercent", JSONObject.NULL)
        root.getJSONObject("capabilities").put("humidity", false)
        val snapshot = PiV1Decoder.weather(root.toString())
        val shown = WeatherPresentation.from(snapshot, fetched, WeatherFreshness.policyFor(snapshot))
        assertEquals("0.0", shown.airTemperature)
        assertEquals("0.0", shown.windSpeed)
        assertEquals("000°", shown.windDirection)
        assertEquals("—", shown.humidity)
        root.getJSONObject("values").remove("relativeHumidityPercent")
        assertFailsWith<PiProtocolException> { PiV1Decoder.weather(root.toString()) }
    }

    @Test fun contradictoryForecastProvenanceAndPartialRequestedLocationFailClosed() {
        val mutations = listOf<(JSONObject) -> Unit>(
            { it.put("simulated", true) },
            { it.getJSONObject("provider").put("kind", "SIMULATED") },
            { it.put("simulation", JSONObject(wireFixture("weather")).getJSONObject("simulation")) },
            { it.getJSONObject("provenance").put("validAtEpochMs", valid + 1) },
            { it.getJSONObject("provenance").put("fetchedAtEpochMs", fetched + 1) },
            { it.getJSONObject("provenance").remove("requestedLongitude") },
        )
        for (mutate in mutations) {
            val root = forecast()
            mutate(root)
            assertFailsWith<PiProtocolException> { PiV1Decoder.weather(root.toString()) }
        }
    }

    @Test fun legacySimulatedWeatherRetainsShortPublicationFreshness() {
        val root = JSONObject(wireFixture("weather"))
        val published = root.getLong("timestampEpochMs")
        var now = published
        val timer = Timer()
        val source = NetworkPiWeatherSource(identity, Transport(root.toString()), { now }, timer)
        try {
            timer.poll()
            assertEquals(QualityEvaluator.SHARED, source.freshnessPolicy)
            now += 5_001
            timer.poll()
            assertEquals(TelemetryStatus.STALE, source.snapshots.value.status)
            now = published + 30_001
            timer.poll()
            assertEquals(TelemetryStatus.LOST, source.snapshots.value.status)
            assertEquals(WeatherValues(), source.snapshots.value.values)
        } finally { source.close() }
    }

    private class Timer : PiPollScheduler {
        lateinit var poll: () -> Unit
        override fun schedule(intervalMs: Long, poll: () -> Unit): SourceSubscription {
            this.poll = poll
            return SourceSubscription { }
        }
    }

    private class Transport(var body: String) : PiHttpTransport {
        var failed = false
        override fun get(path: String): String {
            assertEquals("/v1/weather", path)
            if (failed) throw IOException("Gateway unavailable")
            return body
        }
        override fun close() = Unit
    }
}
