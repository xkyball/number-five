package com.no5.helm

import com.no5.domain.*
import com.no5.helm.network.*
import org.json.JSONObject
import org.junit.Test
import java.time.Instant
import kotlin.test.*

class OpenMeteoWeatherSourceTest {
    private val identity = SourceIdentity("weather", SourceMode.DEMO, "TEST")
    private val location = WeatherLocation(52.52, 13.405)
    private val now = 1_790_986_800_000L

    private fun response(observed: Long = now, temperature: String = "0.0", humidity: String = "0", wind: String = "0.0", direction: String = "360.0") = """
        {"latitude":52.52,"longitude":13.42,"utc_offset_seconds":0,
         "current_units":{"time":"unixtime","interval":"seconds","temperature_2m":"°C","relative_humidity_2m":"%","pressure_msl":"hPa","wind_speed_10m":"kn","wind_direction_10m":"°","wind_gusts_10m":"kn"},
         "current":{"time":${observed / 1000},"interval":900,"temperature_2m":$temperature,"relative_humidity_2m":$humidity,"pressure_msl":1016.3,"wind_speed_10m":$wind,"wind_direction_10m":$direction,"wind_gusts_10m":4.0}}
    """.trimIndent()

    @Test fun optionalSceneFieldsValidateUnitsRangesNullsAndMissingCapabilities() {
        val base=JSONObject(response())
        fun decode()=OpenMeteoDecoder.current(base.toString(),now,location)
        assertNull(decode().values.visibilityMeters);assertFalse(decode().capabilities.cloudCover)
        val units=base.getJSONObject("current_units");val values=base.getJSONObject("current")
        units.put("cloud_cover","%").put("visibility","m").put("weather_code","wmo code")
        values.put("cloud_cover",0).put("visibility",0).put("weather_code",45)
        assertEquals(0.0,decode().values.cloudCoverPercent);assertEquals(0.0,decode().values.visibilityMeters)
        assertEquals(45,decode().values.weatherCode);assertTrue(decode().capabilities.visibility)
        values.put("visibility",JSONObject.NULL);assertNull(decode().values.visibilityMeters)
        values.put("visibility",-1);assertFailsWith<IllegalArgumentException>{decode()}
        values.put("visibility",500);units.put("visibility","km");assertFailsWith<IllegalArgumentException>{decode()}
        units.put("visibility","m");values.put("cloud_cover",101);assertFailsWith<IllegalArgumentException>{decode()}
        values.put("cloud_cover",50);values.put("weather_code",45.5);assertFailsWith<IllegalArgumentException>{decode()}
    }
    @Test fun capturedSceneProviderFieldsRetainForecastProvenance() {
        val raw=javaClass.getResourceAsStream("/weather/open-meteo-scene-public-synthetic.json")!!.bufferedReader().use { it.readText() }
        val evidence=JSONObject(raw);assertTrue(evidence.getBoolean("synthetic_coordinates"));assertEquals(200,evidence.getInt("http_status"))
        val received=Instant.parse(evidence.getString("checked_at_utc")).toEpochMilli()
        val decoded=OpenMeteoDecoder.current(evidence.getJSONObject("response").toString(),received,WeatherLocation(-34.3,18.6))
        assertEquals(WeatherProviderKind.FORECAST,decoded.provider!!.kind)
        assertNotNull(decoded.values.cloudCoverPercent);assertNotNull(decoded.values.visibilityMeters);assertNotNull(decoded.values.weatherCode)
        assertEquals(received,decoded.receivedAtEpochMs);assertFalse(decoded.simulated)
        assertTrue(UrlConnectionOpenMeteoTransport.requestUrl(location).contains("cloud_cover,visibility,weather_code"))
    }

    private class ManualScheduler : PiPollScheduler {
        var interval = 0L
        var closed = false
        var callback: (() -> Unit)? = null
        override fun schedule(intervalMs: Long, poll: () -> Unit): SourceSubscription {
            interval = intervalMs
            callback = poll
            return SourceSubscription { closed = true }
        }
        fun tick() { if (!closed) callback?.invoke() }
    }

    private class FakeTransport(var payload: String) : OpenMeteoTransport {
        var failure = false
        var closed = false
        var calls = mutableListOf<WeatherLocation>()
        var duringRequest: (() -> Unit)? = null
        override fun current(location: WeatherLocation): String {
            calls += location
            duringRequest?.invoke()
            if (failure) error("Offline")
            return payload
        }
        override fun close() { closed = true }
    }

    private class PositionSource(override val identity: SourceIdentity, initial: HelmState) : HelmSource {
        val feed = MutableSourceFeed(initial)
        override val snapshots: SourceFeed<HelmState> get() = feed
        override fun advance(elapsedMillis: Long) = Unit
        override fun close() = feed.close()
    }

    private fun vessel() = HelmState(latitudeDegrees = location.latitudeDegrees, longitudeDegrees = location.longitudeDegrees,
        quality = TelemetryQuality(TelemetryStatus.LIVE, now, "TEST", demo = true))

    @Test fun forecastDecoderPreservesZeroNullUnitsModelTimeAndGridProvenance() {
        val forecast = OpenMeteoDecoder.current(response(), now + 100, location)
        assertEquals(0.0, forecast.values.airTemperatureC)
        assertEquals(0.0, forecast.values.windSpeedKn)
        assertEquals(0.0, forecast.values.windDirectionDegrees)
        assertEquals(0.0, forecast.values.relativeHumidityPercent)
        assertEquals(WeatherProviderKind.FORECAST, forecast.provider!!.kind)
        assertEquals(WeatherPressureReference.MEAN_SEA_LEVEL, forecast.pressureReference)
        assertFalse(forecast.simulated)
        assertNull(forecast.simulation)
        assertEquals(now, forecast.observedAtEpochMs)
        assertEquals(now, forecast.timestampEpochMs)
        assertEquals(now + 100, forecast.receivedAtEpochMs)
        assertEquals(location, forecast.requestedLocation)
        assertEquals(13.42, forecast.values.longitudeDegrees)
        val missing = OpenMeteoDecoder.current(response(temperature = "null", humidity = "null", wind = "null", direction = "null"), now, location)
        assertNull(missing.values.airTemperatureC)
        assertNull(missing.values.windSpeedKn)
        assertEquals("—", WeatherPresentation.from(missing, now, WeatherFreshness.FORECAST).airTemperature)
    }

    @Test fun capturedPublicSyntheticApiResponseMatchesProviderContract() {
        // Exact copy of verification/weather/open-meteo-public-synthetic.json, captured 2026-10-03.
        // Reading this fixture performs no Internet call and sends no coordinates.
        val raw = javaClass.getResourceAsStream("/weather/open-meteo-public-synthetic.json")!!.bufferedReader().use { it.readText() }
        val evidence = JSONObject(raw)
        assertTrue(evidence.getBoolean("synthetic_coordinates"))
        assertEquals(200, evidence.getInt("http_status"))
        val received = Instant.parse(evidence.getString("checked_at_utc")).toEpochMilli()
        val requested = WeatherLocation(-34.3, 18.6)
        val snapshot = OpenMeteoDecoder.current(evidence.getJSONObject("response").toString(), received, requested)
        assertEquals(14.8, snapshot.values.airTemperatureC)
        assertEquals(78.0, snapshot.values.relativeHumidityPercent)
        assertEquals(1024.4, snapshot.values.pressureHpa)
        assertEquals(19.5, snapshot.values.windSpeedKn)
        assertEquals(157.0, snapshot.values.windDirectionDegrees)
        assertEquals(25.1, snapshot.values.windGustSpeedKn)
        assertEquals(-34.27065, snapshot.values.latitudeDegrees)
        assertEquals(18.632372, snapshot.values.longitudeDegrees)
        assertEquals(requested, snapshot.requestedLocation)
        assertNotEquals(requested.latitudeDegrees, snapshot.values.latitudeDegrees)
        assertEquals(1_790_999_100_000L, snapshot.observedAtEpochMs)
        assertEquals(received, snapshot.receivedAtEpochMs)
        assertNotEquals(snapshot.observedAtEpochMs, snapshot.receivedAtEpochMs)
        assertEquals(WeatherPressureReference.MEAN_SEA_LEVEL, snapshot.pressureReference)
        assertEquals(WeatherProviderKind.FORECAST, snapshot.provider!!.kind)
        assertEquals("OPEN_METEO", snapshot.provider!!.id)
        assertEquals("api.open-meteo.com", snapshot.delivery!!.gatewayId)
        assertFalse(snapshot.simulated)
        assertNull(snapshot.simulation)
        val presentation = WeatherPresentation.from(snapshot, received, WeatherFreshness.FORECAST)
        assertEquals("FORECAST · LIVE", presentation.statusLabel)
        assertEquals("19.5", presentation.windSpeed)
        assertEquals("157°", presentation.windDirection)
    }

    @Test fun decoderRejectsMalformedUnitsRangesStringsDuplicateKeysAndTimezones() {
        val invalid = listOf(
            response().replace("\"kn\"", "\"km/h\""),
            response().replace("\"°C\"", "\"°F\""),
            response(temperature = "\"12.4\""),
            response(humidity = "101"),
            response(wind = "-1"),
            response(direction = "361"),
            response().replace("\"utc_offset_seconds\":0", "\"utc_offset_seconds\":3600"),
            response().replace("\"latitude\":52.52", "\"latitude\":52.52,\"latitude\":42.0"),
            response().replace("\"temperature_2m\":0.0,", ""),
            response() + " {}",
        )
        invalid.forEach { json -> assertFails { OpenMeteoDecoder.current(json, now, location) } }
    }

    @Test fun requestUrlHasOnlyFixedHttpsProviderAndExplicitUnits() {
        val url = UrlConnectionOpenMeteoTransport.requestUrl(location)
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=52.52000&longitude=13.40500&"))
        assertTrue(url.contains("wind_speed_unit=kn"))
        assertTrue(url.contains("timeformat=unixtime&timezone=GMT"))
        assertFalse(url.contains("apikey"))
        assertFailsWith<IllegalArgumentException> { WeatherLocation(Double.NaN, 0.0) }
    }

    @Test fun absentConsentDoesNotScheduleOrTransmitPosition() {
        val scheduler = ManualScheduler()
        val transport = FakeTransport(response())
        val source = OpenMeteoWeatherSource(identity, PositionSource(identity, vessel()), false, { now }, transport, scheduler)
        scheduler.tick()
        source.advance(1000)
        assertNull(scheduler.callback)
        assertEquals(0, transport.calls.size)
        assertEquals(TelemetryStatus.UNAVAILABLE, source.snapshots.value.status)
        assertEquals(WeatherIssue.LOCATION_SHARING_DISABLED, source.snapshots.value.issue)
        source.close()
        assertTrue(transport.closed)
    }

    @Test fun initialUnknownPositionWaitsAndQueriesPromptlyWhenValidWithoutFallback() {
        val helm = PositionSource(identity, HelmState.unavailable())
        val scheduler = ManualScheduler()
        val transport = FakeTransport(response())
        val source = OpenMeteoWeatherSource(identity, helm, true, { now }, transport, scheduler)
        scheduler.tick()
        assertEquals(0, transport.calls.size)
        assertEquals(WeatherIssue.BOAT_POSITION_UNAVAILABLE, source.snapshots.value.issue)
        assertTrue(scheduler.interval <= 5_000)
        helm.feed.emit(vessel())
        scheduler.tick()
        assertEquals(listOf(location), transport.calls)
        assertEquals(TelemetryStatus.LIVE, source.snapshots.value.status)
        assertNull(source.snapshots.value.issue)
        source.close()
    }

    @Test fun liveEngineOnlyGatewayExplainsMissingPositionInExistingWeatherDisplayAndRecovers() {
        fun quality(status: TelemetryStatus = TelemetryStatus.LIVE) = TelemetryQuality(status, now, "no5-pi-gateway-simulator", simulated = true)
        // Public physical-Pi shape: HELM is LIVE while position capability/values are absent.
        val engineOnly = HelmState.unavailable().copy(
            portRpm = 3400.0,
            quality = quality(),
            telemetryFields = mapOf(
                "portRpm" to TelemetryValue(3400.0, quality()),
                "latitudeDegrees" to TelemetryValue(null, quality(TelemetryStatus.UNAVAILABLE)),
                "longitudeDegrees" to TelemetryValue(null, quality(TelemetryStatus.UNAVAILABLE)),
            ),
        )
        val helm = PositionSource(identity, engineOnly)
        val scheduler = ManualScheduler()
        val transport = FakeTransport(response())
        val source = OpenMeteoWeatherSource(identity, helm, true, { now }, transport, scheduler)
        try {
            scheduler.tick()
            assertEquals(0, transport.calls.size)
            val snapshot = source.snapshots.value
            assertEquals(WeatherIssue.BOAT_POSITION_UNAVAILABLE, snapshot.issue)
            val shown = renderValues(engineOnly, now, weather = snapshot, weatherPolicy = source.freshnessPolicy)
            assertEquals("NO BOAT POSITION", shown.text["deck-evidence-environment"])
            assertEquals("—", shown.text["deck-airTemp"])
            assertEquals("3400", shown.text["rpm-port"])

            helm.feed.emit(engineOnly.copy(latitudeDegrees = location.latitudeDegrees, longitudeDegrees = location.longitudeDegrees,
                telemetryFields = engineOnly.telemetryFields + mapOf(
                    "latitudeDegrees" to TelemetryValue(location.latitudeDegrees, quality()),
                    "longitudeDegrees" to TelemetryValue(location.longitudeDegrees, quality()),
                )))
            scheduler.tick()
            assertEquals(listOf(location), transport.calls)
            assertNull(source.snapshots.value.issue)
            assertEquals("OPEN-METEO · FORECAST · LIVE", renderValues(helm.snapshots.value, now,
                weather = source.snapshots.value, weatherPolicy = source.freshnessPolicy).text["deck-evidence-environment"])
        } finally { source.close() }
    }

    @Test fun freshEnginePublicationsCannotAuthorizeSendingExpiredOrMissingGps() {
        var clock = now
        fun quality(stamp: Long) = TelemetryQuality(TelemetryStatus.LIVE, stamp, "REAL_GATEWAY")
        fun state(gpsStamp: Long, latitude: Double? = location.latitudeDegrees) = vessel().copy(
            quality = quality(clock),
            telemetryFields = mapOf(
                "portRpm" to TelemetryValue(3400.0, quality(clock)),
                "latitudeDegrees" to TelemetryValue(latitude, quality(gpsStamp)),
                "longitudeDegrees" to TelemetryValue(location.longitudeDegrees, quality(gpsStamp)),
            ),
        )
        val helm = PositionSource(identity, state(now - 6_000))
        val scheduler = ManualScheduler()
        val transport = FakeTransport(response())
        val source = OpenMeteoWeatherSource(identity, helm, true, { clock }, transport, scheduler)
        try {
            scheduler.tick()
            assertEquals(0, transport.calls.size)
            assertEquals(WeatherIssue.BOAT_POSITION_STALE, source.snapshots.value.issue)
            assertEquals("BOAT POSITION STALE", WeatherPresentation.from(source.snapshots.value, clock, source.freshnessPolicy).evidenceLabel)
            helm.feed.emit(state(now, latitude = null))
            scheduler.tick()
            assertEquals(0, transport.calls.size)
            assertEquals(WeatherIssue.BOAT_POSITION_UNAVAILABLE, source.snapshots.value.issue)
            helm.feed.emit(state(now))
            scheduler.tick()
            assertEquals(listOf(location), transport.calls)
            clock += 600_000
            helm.feed.emit(state(now)) // Engine/publication fresh; GPS observation remains old.
            scheduler.tick()
            assertEquals(1, transport.calls.size)
            assertEquals(TelemetryStatus.STALE, source.snapshots.value.status)
            assertEquals(WeatherIssue.BOAT_POSITION_STALE, source.snapshots.value.issue)
            assertEquals(now, source.snapshots.value.observedAtEpochMs)
        } finally { source.close() }
    }

    @Test fun cacheAndDistanceThresholdRespectOneAttemptPerMinute() {
        var clock = now
        val helm = PositionSource(identity, vessel())
        val scheduler = ManualScheduler()
        val transport = FakeTransport(response())
        val source = OpenMeteoWeatherSource(identity, helm, true, { clock }, transport, scheduler)
        scheduler.tick()
        repeat(10) { clock += 5_000; scheduler.tick() }
        assertEquals(1, transport.calls.size)
        helm.feed.emit(vessel().copy(latitudeDegrees = 53.0))
        scheduler.tick()
        assertEquals(1, transport.calls.size)
        clock = now + 60_000
        scheduler.tick()
        assertEquals(2, transport.calls.size)
        clock += 599_999
        scheduler.tick()
        assertEquals(2, transport.calls.size)
        clock += 1
        scheduler.tick()
        assertEquals(3, transport.calls.size)
        source.close()
    }

    @Test fun providerFailurePreservesAgingCacheAndNeverChangesHelm() {
        var clock = now
        val helm = PositionSource(identity, vessel())
        val original = helm.snapshots.value
        val scheduler = ManualScheduler()
        val transport = FakeTransport(response())
        val source = OpenMeteoWeatherSource(identity, helm, true, { clock }, transport, scheduler)
        scheduler.tick()
        transport.failure = true
        clock += 600_000
        scheduler.tick()
        assertEquals(TelemetryStatus.STALE, source.snapshots.value.status)
        assertEquals(WeatherIssue.PROVIDER_UNAVAILABLE, source.snapshots.value.issue)
        assertEquals(now, source.snapshots.value.observedAtEpochMs)
        assertEquals(now, source.snapshots.value.receivedAtEpochMs)
        assertEquals(original, helm.snapshots.value)
        clock += 1
        scheduler.tick()
        assertEquals(2, transport.calls.size)
        assertEquals(TelemetryStatus.LOST, WeatherFreshness.evaluate(source.snapshots.value, now + 3_600_001, source.freshnessPolicy))
        source.close()
    }

    @Test fun closeDropsInflightReplyAndPreventsFutureRequestsOrSubscriptions() {
        val helm = PositionSource(identity, vessel())
        val scheduler = ManualScheduler()
        val transport = FakeTransport(response())
        val source = OpenMeteoWeatherSource(identity, helm, true, { now }, transport, scheduler)
        var emissions = 0
        source.snapshots.subscribe { emissions++ }
        transport.duringRequest = { source.close() }
        scheduler.tick()
        assertEquals(1, transport.calls.size)
        assertEquals(1, emissions)
        assertEquals(TelemetryStatus.UNAVAILABLE, source.snapshots.value.status)
        scheduler.tick()
        source.snapshots.subscribe { emissions++ }
        assertEquals(1, emissions)
        assertEquals(1, transport.calls.size)
        assertTrue(transport.closed)
        source.close()
    }

    @Test fun cacheRecoversImmediatelyWhenSamePositionReturnsWithoutExtraApiCall() {
        val helm = PositionSource(identity, vessel())
        val scheduler = ManualScheduler()
        val transport = FakeTransport(response())
        val source = OpenMeteoWeatherSource(identity, helm, true, { now }, transport, scheduler)
        scheduler.tick()
        helm.feed.emit(HelmState.unavailable())
        scheduler.tick()
        assertEquals(TelemetryStatus.STALE, source.snapshots.value.status)
        assertEquals("NO POSITION · STALE CACHE", WeatherPresentation.from(source.snapshots.value, now, source.freshnessPolicy).evidenceLabel)
        helm.feed.emit(vessel())
        scheduler.tick()
        assertEquals(TelemetryStatus.LIVE, source.snapshots.value.status)
        assertNull(source.snapshots.value.error)
        assertNull(source.snapshots.value.issue)
        assertEquals(1, transport.calls.size)
        source.close()
    }

    @Test fun brokenObserverDoesNotTurnSuccessfulProviderRequestIntoAnOutage() {
        val scheduler = ManualScheduler()
        val transport = FakeTransport(response())
        val source = OpenMeteoWeatherSource(identity, PositionSource(identity, vessel()), true, { now }, transport, scheduler)
        source.snapshots.subscribe { if (it.provider != null) error("Broken UI listener") }
        scheduler.tick()
        assertEquals(TelemetryStatus.LIVE, source.snapshots.value.status)
        assertNull(source.snapshots.value.error)
        source.close()
    }

    @Test fun failedSchedulerConstructionClosesItsTransport() {
        val transport = FakeTransport(response())
        val scheduler = PiPollScheduler { _, _ -> error("Cannot create worker") }
        assertFailsWith<IllegalStateException> {
            OpenMeteoWeatherSource(identity, PositionSource(identity, vessel()), true, { now }, transport, scheduler)
        }
        assertTrue(transport.closed)
    }
}
