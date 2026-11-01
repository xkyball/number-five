package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class HelmSourceTest {
    private val demoIdentity = SourceIdentity("helm-demo-1", SourceMode.DEMO, "Local HELM demo")

    @Test fun fractionalDemoSamplesAreDeterministicAtTwentyHertz() {
        val fine = MockHelmSource(demoIdentity, nowEpochMs = { 100L })
        val coarse = MockHelmSource(demoIdentity, nowEpochMs = { 100L })
        val samples = mutableListOf<HelmState>()
        fine.snapshots.subscribe(samples::add)
        repeat(20) { fine.advance(50) }
        coarse.advance(1_000)
        assertEquals(coarse.snapshots.value, fine.snapshots.value)
        assertEquals(21, samples.size)
        assertEquals(0L, samples[1].timeTick)
        assertEquals(0.05, samples[1].simulationElapsedSeconds!!, 0.0)
        assertTrue(samples[1].portRpm > samples[0].portRpm)
        assertTrue(samples[2].portRpm > samples[1].portRpm)
        assertEquals(1L, samples.last().timeTick)
        assertEquals(1.0, samples.last().simulationElapsedSeconds!!, 0.0)
        val beforeReset = fine.snapshots.value.simulationRunId
        fine.demo.reset()
        assertNotEquals(beforeReset, fine.snapshots.value.simulationRunId)
        assertEquals(0.0, fine.snapshots.value.simulationElapsedSeconds!!, 0.0)
    }

    @Test fun fractionalDemoPauseKeepsItsExactSimulationPosition() {
        val source = MockHelmSource(demoIdentity)
        source.advance(450)
        source.demo.update(HelmInput.SetRunning(false))
        val paused = source.snapshots.value
        source.advance(1_000)
        assertEquals(paused, source.snapshots.value)
        source.demo.update(HelmInput.SetRunning(true))
        source.advance(50)
        assertEquals(0.5, source.snapshots.value.simulationElapsedSeconds!!, 0.0)
        assertEquals(0L, source.snapshots.value.timeTick)
        source.advance(500)
        assertEquals(1L, source.snapshots.value.timeTick)
        assertEquals(1.0, source.snapshots.value.simulationElapsedSeconds!!, 0.0)
    }

    @Test fun deterministicMockOwnsAllPresentedTelemetryAndClock() {
        val first = MockHelmSource(demoIdentity, nowEpochMs = { 100L })
        val second = MockHelmSource(demoIdentity, nowEpochMs = { 100L })
        first.advance(400)
        first.advance(600)
        second.advance(1_000)
        val state = first.snapshots.value
        assertEquals(second.snapshots.value, state)
        assertEquals(1L, state.timeTick)
        assertEquals(100L, state.quality.timestampEpochMs)
        assertEquals(demoIdentity.label, state.quality.source)
        assertEquals(OperatingMode.DEMO, state.mode)
        assertEquals(82.0, state.portCoolantC!!, 0.0)
        assertEquals(4.1, state.starboardOilBar!!, 0.0)
        assertEquals("F", state.portGear)
        assertEquals(-34.00205, state.latitudeDegrees!!, 0.0)
        assertEquals(420.0, state.fuelRemainingLiters!!, 0.0)
        assertEquals(state.heading, state.courseOverGroundDegrees!!, 0.0)
        assertEquals(state.portFuelFlowLph!! + state.starboardFuelFlowLph!!, state.totalFuelFlowLph!!, 0.0)
    }

    @Test fun demoControlsAreExplicitLocalCapabilityAndPauseStopsAdvancement() {
        val source = MockHelmSource(demoIdentity)
        assertTrue(source.demo.update(HelmInput.SetRunning(false)).accepted)
        val paused = source.snapshots.value
        source.advance(10_000)
        assertEquals(paused, source.snapshots.value)
        source.demo.update(HelmInput.SetTheme(Theme.NIGHT))
        source.demo.update(HelmInput.SetTelemetryAge(31_000))
        assertEquals(TelemetryStatus.LOST, source.snapshots.value.quality.status)
        assertEquals(Theme.NIGHT, source.demo.reset().theme)
        assertEquals(TelemetryStatus.LIVE, source.snapshots.value.quality.status)
    }

    @Test fun localFixtureDerivationsPreserveTrueZeroAndNullInputs() {
        val source = MockHelmSource(demoIdentity, HelmState(starboardFuelFlowLph = null))
        source.demo.update(HelmInput.Rpm(EngineSide.PORT, 0.0))
        assertEquals(0.0, source.snapshots.value.portFuelFlowLph!!, 0.0)
        assertNull(source.snapshots.value.totalFuelFlowLph)
        source.demo.update(HelmInput.Rpm(EngineSide.STARBOARD, 0.0))
        source.demo.update(HelmInput.Fuel(FuelTank.PORT, 0.0))
        source.demo.update(HelmInput.Fuel(FuelTank.STARBOARD, 0.0))
        assertEquals(0.0, source.snapshots.value.totalFuelFlowLph!!, 0.0)
        assertEquals(0.0, source.snapshots.value.fuelRemainingLiters!!, 0.0)
    }

    @Test fun closeRejectsOldUpdatesTicksAndSubscriptions() {
        val source = MockHelmSource(demoIdentity)
        val updates = mutableListOf<HelmState>()
        source.snapshots.subscribe(updates::add)
        source.advance(1_000)
        val last = source.snapshots.value
        source.close()
        source.close()
        assertFalse(source.demo.update(HelmInput.Rpm(EngineSide.PORT, 6_000.0)).accepted)
        source.advance(10_000)
        assertEquals(last, source.demo.reset())
        assertEquals(last, source.snapshots.value)
        var callback = false
        source.snapshots.subscribe { callback = true }
        assertFalse(callback)
        assertEquals(2, updates.size)
    }

    @Test fun piPlaceholderHasNoDemoControlAndNoFabricatedValues() {
        val source = UnavailablePiHelmSource(SourceIdentity("pi-1", SourceMode.PI, "Pi adapter unavailable"), Theme.NIGHT)
        val state = source.snapshots.value
        assertNull(source.demo)
        assertEquals(OperatingMode.UNAVAILABLE, state.mode)
        assertEquals(TelemetryStatus.UNAVAILABLE, state.quality.status)
        assertFalse(state.quality.demo)
        assertFalse(state.running)
        assertEquals(Theme.NIGHT, state.theme)
        listOf(state.portCoolantC, state.starboardCoolantC, state.portOilBar, state.starboardOilBar,
            state.portFuelFlowLph, state.starboardFuelFlowLph, state.totalFuelFlowLph,
            state.latitudeDegrees, state.longitudeDegrees, state.fuelRemainingLiters, state.courseOverGroundDegrees).forEach { assertNull(it) }
        assertNull(state.portGear)
        assertNull(state.starboardGear)
        source.advance(5_000)
        assertEquals(state, source.snapshots.value)
    }

    @Test fun modeFollowsEvidenceInsteadOfAlwaysClaimingDemo() {
        assertEquals(OperatingMode.DEMO, HelmState().mode)
        assertEquals(OperatingMode.UNAVAILABLE, HelmState.unavailable().mode)
        assertEquals(OperatingMode.LIVE, HelmState(quality = TelemetryQuality(TelemetryStatus.STALE, 1_000, "PI_HELM")).mode)
    }

    @Test fun adapterIdentitiesCannotSilentlyMislabelData() {
        assertThrows(IllegalArgumentException::class.java) { MockHelmSource(demoIdentity.copy(mode = SourceMode.PI)) }
        assertThrows(IllegalArgumentException::class.java) { UnavailablePiHelmSource(demoIdentity) }
        assertThrows(IllegalArgumentException::class.java) { SourceIdentity("", SourceMode.DEMO, "Demo") }
        assertThrows(IllegalArgumentException::class.java) { SourceIdentity("session", SourceMode.DEMO, "") }
    }
}
