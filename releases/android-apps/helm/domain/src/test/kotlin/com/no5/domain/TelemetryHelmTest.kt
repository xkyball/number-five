package com.no5.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class TelemetryHelmTest {
    private val live = TelemetryQuality(TelemetryStatus.LIVE, 100_000, "TEST_ADAPTER")
    @Test fun sharedThresholdsPreserveBoundary() {
        assertEquals(TelemetryStatus.LIVE, QualityEvaluator.evaluate(live, 105_000))
        assertEquals(TelemetryStatus.STALE, QualityEvaluator.evaluate(live, 105_001))
        assertEquals(TelemetryStatus.STALE, QualityEvaluator.evaluate(live, 130_000))
        assertEquals(TelemetryStatus.LOST, QualityEvaluator.evaluate(live, 130_001))
    }
    @Test fun audioUsesTwoPointFiveSeconds() {
        assertEquals(TelemetryStatus.LIVE, QualityEvaluator.evaluate(live, 102_500, QualityEvaluator.AUDIO))
        assertEquals(TelemetryStatus.STALE, QualityEvaluator.evaluate(live, 102_501, QualityEvaluator.AUDIO))
        assertEquals(TelemetryStatus.LOST, QualityEvaluator.evaluate(live, 130_001, QualityEvaluator.AUDIO))
    }
    @Test fun timestampAndExplicitSourceRequired() {
        assertEquals(TelemetryStatus.INVALID, QualityEvaluator.evaluate(live.copy(timestampEpochMs = null), 100_000))
        assertEquals(TelemetryStatus.INVALID, QualityEvaluator.evaluate(live.copy(source = ""), 100_000))
        assertEquals(TelemetryStatus.STALE, QualityEvaluator.evaluate(live, 90_000))
        assertEquals(TelemetryStatus.UNAVAILABLE, QualityEvaluator.evaluate(TelemetryQuality(), 100_000))
    }
    @Test fun zeroRetainedLostAndInvalidValuesHidden() {
        assertEquals(0.0, TelemetryValue(0.0, live).displayValue(100_000, 0.0..15_000.0)!!, 0.0)
        assertNull(TelemetryValue(0.0, live).displayValue(131_000, 0.0..15_000.0))
        assertNull(TelemetryValue(Double.NaN, live).displayValue(100_000, 0.0..15_000.0))
        assertNull(TelemetryValue(-1.0, live).displayValue(100_000, 0.0..15_000.0))
    }
    @Test fun explicitDemoNeverMasqueradesAsGatewayAging() {
        val demo = TelemetryQuality(TelemetryStatus.LIVE, source = "DEMO", demo = true)
        assertEquals(TelemetryStatus.LIVE, QualityEvaluator.evaluate(demo, Long.MAX_VALUE))
        assertTrue(DemoHelmRepository().state.quality.demo)
    }
    @Test fun canonicalHelmNumbersAndAllNumericInputs() {
        val r = DemoHelmRepository()
        assertEquals(3400.0, r.state.portRpm, 0.0); assertEquals(3420.0, r.state.starboardRpm, 0.0)
        assertEquals(21.8, r.state.speed, 0.0); assertEquals(5.2, r.state.depth!!, 0.0)
        val inputs = listOf(HelmInput.Rpm(EngineSide.PORT, 0.0), HelmInput.Rpm(EngineSide.STARBOARD, 0.0), HelmInput.Trim(EngineSide.PORT, 0.0), HelmInput.Trim(EngineSide.STARBOARD, 0.0), HelmInput.Heading(360.0), HelmInput.Speed(0.0), HelmInput.Depth(0.0), HelmInput.WaterTemperature(0.0), HelmInput.Voltage(ElectricalBus.HOUSE, 0.0), HelmInput.Voltage(ElectricalBus.STARTER_PORT, 0.0), HelmInput.Voltage(ElectricalBus.STARTER_STARBOARD, 0.0), HelmInput.Fuel(FuelTank.PORT, 0.0), HelmInput.Fuel(FuelTank.STARBOARD, 0.0))
        inputs.forEach { assertTrue(r.update(it).accepted) }
        assertEquals(0.0, r.state.heading, 0.0); assertEquals(0.0, r.state.portRpm, 0.0); assertEquals(0.0, r.state.depth!!, 0.0)
    }
    @Test fun invalidHelmInputLeavesStateUnchanged() {
        val r = DemoHelmRepository(); val before = r.state
        listOf(HelmInput.Rpm(EngineSide.PORT, Double.NaN), HelmInput.Rpm(EngineSide.PORT, 7001.0), HelmInput.Speed(Double.POSITIVE_INFINITY), HelmInput.Fuel(FuelTank.PORT, 101.0), HelmInput.Trim(EngineSide.PORT, -1.0), HelmInput.Heading(361.0), HelmInput.TimeTick(-1), HelmInput.SetTelemetryAge(-1)).forEach { assertFalse(r.update(it).accepted); assertEquals(before, r.state) }
    }
    @Test fun pauseTickResetAndMidnightAreExplicitSimulation() {
        val r = DemoHelmRepository(); r.update(HelmInput.SetTime(LocalTime.of(23, 59, 59))); r.tick()
        assertEquals(LocalTime.MIDNIGHT, r.state.time)
        r.update(HelmInput.SetRunning(false)); val time = r.state.time; r.tick(20); assertEquals(time, r.state.time)
        r.update(HelmInput.SetTheme(Theme.NIGHT)); r.update(HelmInput.SetTelemetryAge(30_001)); assertEquals(TelemetryStatus.LOST, r.state.quality.status)
        r.reset(); assertEquals(Theme.NIGHT, r.state.theme); assertEquals(0L, r.state.timeTick); assertEquals(LocalTime.of(14, 23), r.state.time)
    }
    @Test fun networkSimulationHasProvenanceWithoutBypassingFreshness() {
        val quality=TelemetryQuality(TelemetryStatus.LIVE,1000,"NO5_PI_SIMULATOR",demo=false,simulated=true)
        assertEquals(TelemetryStatus.LIVE,QualityEvaluator.evaluate(quality,2000))
        assertEquals(TelemetryStatus.STALE,QualityEvaluator.evaluate(quality,7000))
        assertEquals(TelemetryStatus.LOST,QualityEvaluator.evaluate(quality,32000))
        assertEquals(OperatingMode.DEMO,HelmState(quality=quality).mode)
    }
}
