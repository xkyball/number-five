package com.no5.helm.ocean

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class OceanIntegrationTest {
    private val now = 100_000L
    private fun quality(stamp: Long = now, status: TelemetryStatus = TelemetryStatus.LIVE) =
        TelemetryQuality(status, stamp, "gateway", demo = false)
    private fun helm(heading: Double? = 0.0, stamp: Long = now) = HelmState.unavailable().copy(
        heading = 99.0, quality = quality(), sourceSessionId = "server-a", simulationRunId = "run-a",
        telemetryFields = mapOf("heading" to TelemetryValue(heading, quality(stamp))))
    private fun weather(stamp: Long = now, status: TelemetryStatus = TelemetryStatus.LIVE,
        wind: Double? = 0.0, supported: Boolean = true) = WeatherSnapshot(
        status = status, provider = WeatherProvider("model", "Forecast", WeatherProviderKind.FORECAST),
        delivery = WeatherDelivery("external", "HTTPS"), capabilities = WeatherCapabilities(wind = supported),
        values = WeatherValues(windSpeedKn = wind), timestampEpochMs = stamp, observedAtEpochMs = stamp)
    private fun mapped(raw: HelmState = helm(), weather: WeatherSnapshot = weather()) = oceanSourceInputs(
        "app-session", raw, QualityEvaluator.SHARED, weather, WeatherFreshness.FORECAST, now)

    @Test fun draftCancelAndRepeatedSaveAreIndependentTransactions() {
        val state = BackgroundSelection(BackgroundMode.IMAGE)
        state.select(BackgroundMode.ANIMATION)
        assertEquals(BackgroundMode.IMAGE, state.active)
        state.cancel()
        assertEquals(BackgroundMode.IMAGE, state.draft)
        assertNull(state.beginSave())
        state.select(BackgroundMode.NONE)
        val request = state.beginSave()!!
        assertNull(state.beginSave())
        state.select(BackgroundMode.ANIMATION)
        state.cancel()
        assertEquals(BackgroundMode.NONE, state.draft)
        assertEquals(BackgroundMode.IMAGE, state.active)
        state.finishSave(request, true)
        assertEquals(BackgroundMode.NONE, state.active)
        assertNull(state.beginSave())
    }
    @Test fun failedSaveKeepsActiveAndOlderCompletionCannotPublish() {
        val state = BackgroundSelection(BackgroundMode.IMAGE)
        state.select(BackgroundMode.ANIMATION)
        val first = state.beginSave()!!
        state.finishSave(first, false)
        assertEquals(BackgroundMode.IMAGE, state.active)
        assertNotNull(state.error)
        val retry = state.beginSave()!!
        state.finishSave(first, true)
        assertTrue(state.saving)
        assertEquals(BackgroundMode.IMAGE, state.active)
        state.finishSave(retry, true)
        assertEquals(BackgroundMode.ANIMATION, state.active)
        assertNull(state.error)
    }
    @Test fun saveCompletionAfterDraftOwnerLeavesStillPublishes() {
        val state = BackgroundSelection(BackgroundMode.IMAGE)
        state.select(BackgroundMode.NONE)
        val request = state.beginSave()!!
        state.cancel() // SourceSettingsScreen disposal, while app-owned IO is still running.
        state.finishSave(request, true)
        assertEquals(BackgroundMode.NONE, state.active)
        assertEquals(state.active, state.draft)
        assertFalse(state.dirty)
    }
    @Test fun fieldSpecificRawHeadingAndIndependentWeatherKeepRealZero() {
        val input = mapped()
        assertEquals(0.0, input.headingDegrees!!, 0.0)
        assertEquals(OceanInputQuality.LIVE, input.headingQuality)
        assertEquals(0.0, input.windSpeedKn!!, 0.0)
        assertEquals(OceanInputQuality.LIVE, input.weatherQuality)
        val staleHeading = mapped(helm(stamp = now - 10_000))
        assertEquals(OceanInputQuality.STALE, staleHeading.headingQuality)
        assertEquals(OceanInputQuality.LIVE, staleHeading.weatherQuality)
        val lostHelm = mapped(helm().copy(quality = quality(status = TelemetryStatus.LOST)))
        assertEquals(OceanInputQuality.LOST, lostHelm.headingQuality)
        assertEquals(OceanInputQuality.LIVE, lostHelm.weatherQuality)
    }
    @Test fun weatherPolicyAndCapabilityExplicitlyBoundStaleRetention() {
        val stale = mapped(weather = weather(status = TelemetryStatus.STALE))
        assertTrue(stale.staleWeatherUsable)
        assertFalse(mapped(weather = weather(status = TelemetryStatus.STALE, supported = false)).staleWeatherUsable)
        assertFalse(mapped(weather = weather(status = TelemetryStatus.STALE, wind = null)).staleWeatherUsable)
        val lost = oceanSourceInputs("s", helm(), QualityEvaluator.SHARED,
            weather(stamp = 1L), QualityEvaluator.SHARED, now)
        assertEquals(OceanInputQuality.LOST, lost.weatherQuality)
        assertFalse(lost.staleWeatherUsable)
        assertEquals(OceanInputQuality.UNAVAILABLE, mapped(helm(null)).headingQuality)
    }
    @Test fun producerResetReacquiresHeadingWithoutResettingOceanClock() {
        val mapper = OceanPresentation("app-session")
        mapper.accept(mapped(helm(270.0)))
        assertEquals((1.5 * PI).toFloat(), mapper.frame(4f, 0f, OceanQuality.HIGH).headingRadians, 0f)
        mapper.accept(mapped(helm(30.0).copy(simulationRunId = "run-b")))
        val reset = mapper.frame(4f, 0f, OceanQuality.HIGH)
        assertEquals((PI / 6).toFloat(), reset.headingRadians, .0001f)
        assertEquals(4f, reset.timeSeconds, 0f)
        mapper.accept(mapped(helm(180.0).copy(sourceSessionId = "server-b", simulationRunId = "run-b")))
        assertEquals(PI.toFloat(), mapper.frame(4f, 0f, OceanQuality.HIGH).headingRadians, 0f)
    }
    @Test fun simulatorSpeedAndClockNeverEnterOceanInputs() {
        val base = helm()
        val input = mapped(base)
        for (timeScale in listOf(0.0, 1.0, 10.0, 50.0)) {
            assertEquals(input, mapped(base.copy(speed = 150.0, portRpm = 10_000.0,
                simulationTimeScale = timeScale, simulationElapsedSeconds = 99_999.0, running = false)))
        }
    }
    @Test fun lifecyclePauseRequiresNewSustainedPressureAndRecovery() {
        val governor = OceanQualityGovernor()
        repeat(5) { governor.observe(1f, true) }
        governor.pause()
        governor.observe(1f, true)
        assertEquals(OceanQuality.HIGH, governor.quality)
        governor.observe(1f, false, lowMemory = true)
        repeat(29) { governor.observe(1f, false) }
        governor.pause()
        governor.observe(1f, false)
        assertEquals(OceanQuality.STATIC, governor.quality)
    }
}
