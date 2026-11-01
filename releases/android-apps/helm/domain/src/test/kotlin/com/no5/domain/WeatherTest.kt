package com.no5.domain

import org.junit.Test
import kotlin.test.*

class WeatherTest {
    private val identity = SourceIdentity("weather-test", SourceMode.DEMO, "LOCAL DEMO")
    private fun weather(wall: Long = 100_000L): WeatherSnapshot = MockWeatherSource(identity, { wall }).snapshots.value

    @Test fun acquisitionIssuesExplainUnavailableValuesWithoutClaimingProviderData() {
        for ((issue, label) in mapOf(
            WeatherIssue.BOAT_POSITION_UNAVAILABLE to "NO BOAT POSITION",
            WeatherIssue.BOAT_POSITION_STALE to "BOAT POSITION STALE",
            WeatherIssue.LOCATION_SHARING_DISABLED to "LOCATION NOT SHARED",
            WeatherIssue.PROVIDER_UNAVAILABLE to "PROVIDER UNAVAILABLE",
        )) {
            val snapshot = WeatherSnapshot.unavailable(issue = issue)
            val view = WeatherPresentation.from(snapshot, 100_000)
            assertEquals(label, view.evidenceLabel)
            assertEquals("WEATHER · UNAVAILABLE", view.statusLabel)
            assertEquals("—", view.airTemperature)
            assertEquals("—", view.windSpeed)
            assertEquals("No weather provider", view.providerLabel)
        }
        val cached = weather().copy(provider = WeatherProvider("OPEN_METEO", "Forecast", WeatherProviderKind.FORECAST),
            simulated = false, simulation = null, observedAtEpochMs = 100_000, status = TelemetryStatus.STALE,
            issue = WeatherIssue.PROVIDER_UNAVAILABLE)
        assertEquals("FORECAST CACHE · STALE", WeatherPresentation.from(cached, 100_000, WeatherFreshness.FORECAST).evidenceLabel)
        assertEquals("PROVIDER UNAVAILABLE", WeatherPresentation.from(cached, 3_700_001, WeatherFreshness.FORECAST).evidenceLabel)
    }

    @Test fun zeroAndMissingFieldsStayDistinctWithCapabilityGates() {
        val zero = weather().copy(values = WeatherValues(0.0, 0.0, 0.0, 0.0, 1013.0, 0.0, 0.0))
        val shown = WeatherPresentation.from(zero, 100_000)
        assertEquals("0.0", shown.airTemperature)
        assertEquals("0.0", shown.windSpeed)
        assertEquals("000°", shown.windDirection)
        assertEquals("0", shown.humidity)
        assertEquals("0.00000, 0.00000", shown.observedLocation)
        assertEquals("—", WeatherPresentation.from(zero.copy(values = WeatherValues()), 100_000).windSpeed)
        assertEquals("—", WeatherPresentation.from(zero.copy(capabilities = WeatherCapabilities()), 100_000).airTemperature)
    }

    @Test fun simulatedProviderStillAgesByWallPublicationTime() {
        val sample = weather()
        assertTrue(sample.simulated)
        assertEquals(TelemetryStatus.LIVE, WeatherFreshness.evaluate(sample, 105_000))
        assertEquals(TelemetryStatus.STALE, WeatherFreshness.evaluate(sample, 105_001))
        assertEquals(TelemetryStatus.LOST, WeatherFreshness.evaluate(sample, 130_001))
        assertEquals("—", WeatherPresentation.from(sample, 130_001).airTemperature)
        assertEquals(TelemetryStatus.STALE, WeatherFreshness.evaluate(sample, 94_999))
    }

    @Test fun simulatedObservationCanBeFarInFutureWithoutPretendingNetworkFreshness() {
        val sample = weather()
        assertTrue(sample.observedAtEpochMs!! > 100_000)
        assertEquals(TelemetryStatus.LIVE, WeatherFreshness.evaluate(sample, 100_000))
        assertEquals(TelemetryStatus.INVALID, WeatherFreshness.evaluate(sample.copy(simulation = null), 100_000))
        assertEquals(TelemetryStatus.INVALID, WeatherFreshness.evaluate(sample.copy(observedAtEpochMs = 1), 100_000))
    }

    @Test fun externalObservationDoesNotBecomeFreshThroughNewGatewayOrReceiveTimestamp() {
        val sample = weather().copy(
            provider = WeatherProvider("model", "Forecast provider", WeatherProviderKind.FORECAST),
            simulated = false, simulation = null, timestampEpochMs = 100_000, observedAtEpochMs = 10_000,
            receivedAtEpochMs = 100_000,
        )
        assertEquals(TelemetryStatus.LOST, WeatherFreshness.evaluate(sample, 100_000))
        assertEquals(TelemetryStatus.LIVE, WeatherFreshness.evaluate(sample, 100_000, WeatherFreshness.FORECAST))
        assertEquals("FORECAST · LIVE", WeatherPresentation.from(sample, 100_000, WeatherFreshness.FORECAST).statusLabel)
        assertEquals(TelemetryStatus.INVALID, WeatherFreshness.evaluate(sample.copy(simulated = true), 100_000))
    }

    @Test fun providerAndDeliveryAreRequiredAndCannotBeConflated() {
        val sample = weather()
        assertNotEquals(sample.provider!!.id, sample.delivery!!.gatewayId)
        assertEquals(TelemetryStatus.INVALID, WeatherFreshness.evaluate(sample.copy(provider = null), 100_000))
        assertEquals(TelemetryStatus.INVALID, WeatherFreshness.evaluate(sample.copy(delivery = null), 100_000))
        assertEquals(TelemetryStatus.INVALID, WeatherFreshness.evaluate(sample.copy(timestampEpochMs = null), 100_000))
        assertEquals(TelemetryStatus.INVALID, WeatherFreshness.evaluate(sample.copy(observedAtEpochMs = null), 100_000))
    }

    @Test fun strictUnitsDoNotSilentlyConvertDifferentWindOrTemperatureConventions() {
        assertTrue(WeatherUnits.matchesV1(WeatherUnits.V1))
        assertFalse(WeatherUnits.matchesV1(WeatherUnits.V1 - "pressureHpa"))
        assertFalse(WeatherUnits.matchesV1(WeatherUnits.V1 + ("windSpeedKn" to "m/s")))
        assertFalse(WeatherUnits.matchesV1(WeatherUnits.V1 + ("airTemperatureC" to "degF")))
        assertFalse(WeatherUnits.matchesV1(WeatherUnits.V1 + ("windDirectionDegrees" to "deg_apparent")))
    }

    @Test fun invalidOrPartialMeasurementsAreRejectedAtTypedBoundary() {
        assertFailsWith<IllegalArgumentException> { WeatherValues(airTemperatureC = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { WeatherValues(windSpeedKn = -1.0) }
        assertFailsWith<IllegalArgumentException> { WeatherValues(windDirectionDegrees = 360.0) }
        assertFailsWith<IllegalArgumentException> { WeatherValues(relativeHumidityPercent = 101.0) }
        assertFailsWith<IllegalArgumentException> { WeatherValues(pressureHpa = 0.0) }
        assertFailsWith<IllegalArgumentException> { WeatherValues(latitudeDegrees = 1.0) }
        assertFailsWith<IllegalArgumentException> { WeatherValues(latitudeDegrees = 91.0, longitudeDegrees = 0.0) }
    }

    @Test fun sessionSharesSimulationClockAndLocationButWeatherFailureIsIsolated() {
        var now = 100_000L
        val session = DefaultSourceSessionFactory { now }.create(SourceConfig(), identity)
        assertEquals(session.helm.identity, session.weather.identity)
        session.helm.advance(5_000)
        session.weather.advance(5_000)
        val vessel = session.helm.snapshots.value
        val weather = session.weather.snapshots.value
        assertEquals(vessel.latitudeDegrees, weather.values.latitudeDegrees)
        assertEquals(vessel.longitudeDegrees, weather.values.longitudeDegrees)
        assertEquals(vessel.timeTick.toDouble(), weather.simulation!!.elapsedSeconds)
        session.weather.close()
        now += 31_000
        session.helm.advance(1_000)
        assertEquals(TelemetryStatus.LOST, WeatherFreshness.evaluate(weather, now))
        assertEquals(TelemetryStatus.LIVE, session.helm.snapshots.value.quality.status)
        assertTrue(session.helm.snapshots.value.timeTick > vessel.timeTick)
        assertEquals(weather, session.weather.snapshots.value)
        session.close()
        session.close()
    }

    @Test fun pausedVoyageRetainsObservationAndValuesWhileDeliveryClockAdvances() {
        var now = 100_000L
        val helm = MockHelmSource(identity, nowEpochMs = { now })
        val source = MockWeatherSource(identity, { now }) { helm.snapshots.value }
        helm.demo.update(HelmInput.SetRunning(false))
        source.advance(0)
        val before = source.snapshots.value
        now += 60_000
        helm.advance(60_000)
        source.advance(60_000)
        val after = source.snapshots.value
        assertEquals(before.observedAtEpochMs, after.observedAtEpochMs)
        assertEquals(before.values, after.values)
        assertEquals(now, after.timestampEpochMs)
        assertFalse(after.simulation!!.running)
        assertEquals(TelemetryStatus.LIVE, WeatherFreshness.evaluate(after, now))
    }

    @Test fun unavailableWeatherHasNoValuesOrProviderClaimsAndCloseStopsAllCallbacks() {
        val source = UnavailableWeatherSource(identity)
        assertEquals(WeatherValues(), source.snapshots.value.values)
        assertNull(source.snapshots.value.provider)
        assertFalse(source.snapshots.value.simulated)
        assertEquals(TelemetryStatus.UNAVAILABLE, WeatherFreshness.evaluate(source.snapshots.value, 0))
        source.close()
        var called = false
        source.snapshots.subscribe { called = true }
        assertFalse(called)
    }

    @Test fun observationDateAdvancesAcrossMidnightAndDirectionWrapsAtNorth() {
        val helm = MockHelmSource(identity)
        val weatherSource = MockWeatherSource(identity, { 100_000L }) { helm.snapshots.value }
        val initial = weatherSource.snapshots.value.observedAtEpochMs!!
        helm.advance(36_000_000)
        weatherSource.advance(36_000_000)
        assertEquals(initial + 36_000_000, weatherSource.snapshots.value.observedAtEpochMs)
        val north = weather().copy(values = WeatherValues(windDirectionDegrees = 359.8))
        assertEquals("000°", WeatherPresentation.from(north, 100_000).windDirection)
        weatherSource.close()
        helm.close()
    }
}
