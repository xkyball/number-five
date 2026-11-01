package com.no5.domain

import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class HelmPresentationTest {
    private fun sample(rpm: Double, stamp: Long = 1_000L) = HelmState(
        portRpm = rpm, starboardRpm = rpm, portTrim = 0.0, starboardTrim = 0.0,
        speed = 0.0, heading = 0.0,
        quality = TelemetryQuality(TelemetryStatus.LIVE, stamp, "TEST_PI", demo = false, simulated = true),
        simulationRunId = "run-a", simulationElapsedSeconds = stamp / 1_000.0,
    )

    @Test fun oneHertzFeedUsesFiniteTransitionAndNeverExtrapolates() {
        val initial = sample(0.0)
        val next = sample(1_000.0, 2_000)
        val model = HelmPresentationModel(initial)
        assertEquals(initial, model.render(999))
        assertTrue(model.accept(next, 1_000))
        assertEquals(0.0, model.render(1_000).portRpm, 0.0)
        assertEquals(500.0, model.render(1_050).portRpm, 0.0)
        assertEquals(next, model.render(1_100))
        assertEquals(next, model.render(60_000))
    }

    @Test fun twentyHertzRetargetingIsContinuousAndLastSampleReachesExactTarget() {
        val model = HelmPresentationModel(sample(0.0))
        var last = 0.0
        for (index in 1..20) {
            val time = index * 50L
            val before = model.render(time).portRpm
            val target = sample(index * 100.0, 1_000 + time)
            assertTrue(model.accept(target, time))
            assertEquals(before, model.render(time).portRpm, 0.000001)
            val between = model.render(time + 25).portRpm
            assertTrue(between > before)
            assertTrue(between <= target.portRpm)
            assertTrue(between >= last)
            last = between
        }
        assertEquals(2_000.0, model.render(1_100).portRpm, 0.0)
        assertEquals(2_000.0, model.render(20_000).portRpm, 0.0)
    }

    @Test fun frameRateAndSkippedFramesDoNotChangeValuesAtTheSameTime() {
        val fast = HelmPresentationModel(sample(0.0))
        val irregular = HelmPresentationModel(sample(0.0))
        val target = sample(6_000.0, 1_050)
        fast.accept(target, 50)
        irregular.accept(target, 50)
        (50L..100L step 5).forEach { fast.render(it) }
        irregular.render(73)
        assertEquals(fast.render(117), irregular.render(117))
        assertEquals(fast.render(10_000), irregular.render(10_000))
    }

    @Test fun repeatedTargetsAndCriticalOnlyUpdatesDoNotExtendDeadline() {
        val model = HelmPresentationModel(sample(0.0))
        val target = sample(6_000.0, 1_050)
        model.accept(target, 50)
        model.render(75)
        val updated = target.copy(portOilBar = 0.0, quality = target.quality.copy(timestampEpochMs = 1_090))
        model.accept(updated, 90)
        assertEquals(0.0, model.render(90).portOilBar!!, 0.0)
        assertEquals(3_000.0, model.render(100).portRpm, 0.0)
        assertEquals(updated, model.render(150))
    }

    @Test fun latestRawMetadataCriticalAndDiscreteFieldsAreImmediate() {
        val model = HelmPresentationModel(sample(0.0))
        val target = sample(6_000.0, 1_050).copy(
            portCoolantC = 150.0, starboardCoolantC = null, portOilBar = 0.0, starboardOilBar = 0.2,
            portVoltage = 8.0, starboardVoltage = 7.0, houseVoltage = 6.0,
            portFuel = 0.0, starboardFuel = 1.0, fuelRemainingLiters = 0.0,
            portFuelFlowLph = 100.0, starboardFuelFlowLph = 102.0, totalFuelFlowLph = 202.0,
            depth = 0.0, waterTemp = 0.0, portGear = "R", starboardGear = "N",
            time = LocalTime.NOON, timeTick = 10, telemetryAgeMs = 40, theme = Theme.NIGHT,
            tripDistanceNm = 12.0, tripDurationSeconds = 120.0, fuelUsedLiters = 14.0,
            efficiencyNmPerLiter = 0.5, simulationPhase = "acceleration", simulationTimeScale = 4.0,
        )
        model.accept(target, 50)
        val visible = model.render(75)
        // Only the RPM fields differ in this fixture; every other field must equal the raw data.
        assertEquals(target, visible.copy(portRpm = target.portRpm, starboardRpm = target.starboardRpm))
        assertEquals(target, model.latestRaw)
        assertSame(target.quality, visible.quality)
    }

    @Test fun headingsUseShortestArcAcrossNorthInBothDirections() {
        val model = HelmPresentationModel(sample(0.0).copy(heading = 359.0, courseOverGroundDegrees = 1.0))
        model.accept(sample(0.0, 1_050).copy(heading = 1.0, courseOverGroundDegrees = 359.0), 50)
        val half = model.render(100)
        assertEquals(0.0, half.heading, 0.000001)
        assertEquals(0.0, half.courseOverGroundDegrees!!, 0.000001)
        assertEquals(1.0, model.render(150).heading, 0.0)
        assertEquals(359.0, model.render(150).courseOverGroundDegrees!!, 0.0)
    }

    @Test fun positionCrossesDatelineByTwoDegreesAndRetainsLatitudeBounds() {
        val initial = sample(0.0).copy(latitudeDegrees = -90.0, longitudeDegrees = 179.0)
        val next = sample(0.0, 1_050).copy(latitudeDegrees = 90.0, longitudeDegrees = -179.0)
        val model = HelmPresentationModel(initial)
        model.accept(next, 50)
        assertEquals(0.0, model.render(100).latitudeDegrees!!, 0.0)
        assertEquals(-180.0, model.render(100).longitudeDegrees!!, 0.0)
        assertEquals(next, model.render(150))
        model.accept(sample(0.0, 1_200).copy(longitudeDegrees = 179.0), 200)
        assertEquals(-180.0, model.render(250).longitudeDegrees!!, 0.0)
    }

    @Test fun zeroAndMaximumRemainRealAndNeverOvershoot() {
        val zero = sample(0.0).copy(portTrim = 0.0, starboardTrim = 100.0, speed = 0.0)
        val max = sample(7_000.0, 1_050).copy(portTrim = 100.0, starboardTrim = 0.0, speed = 200.0)
        val model = HelmPresentationModel(zero)
        model.accept(max, 50)
        for (time in 50L..150L) {
            val value = model.render(time)
            assertTrue(value.portRpm in 0.0..7_000.0)
            assertTrue(value.portTrim in 0.0..100.0)
            assertTrue(value.starboardTrim in 0.0..100.0)
            assertTrue(value.speed in 0.0..200.0)
        }
        assertEquals(max, model.render(200))
        val finalZero = zero.copy(quality = zero.quality.copy(timestampEpochMs = 1_250), simulationElapsedSeconds = 1.25)
        model.accept(finalZero, 250)
        assertEquals(finalZero, model.render(350))
    }

    @Test fun absentAndInvalidMotionCannotBeInventedByInterpolation() {
        val initial = sample(0.0).copy(latitudeDegrees = null, longitudeDegrees = null, courseOverGroundDegrees = null)
        val next = sample(1_000.0, 1_050).copy(latitudeDegrees = 1.0, longitudeDegrees = 2.0, courseOverGroundDegrees = 3.0)
        val model = HelmPresentationModel(initial)
        model.accept(next, 50)
        assertEquals(next.latitudeDegrees, model.render(75).latitudeDegrees)
        assertEquals(next.longitudeDegrees, model.render(75).longitudeDegrees)
        assertEquals(next.courseOverGroundDegrees, model.render(75).courseOverGroundDegrees)
        val missing = next.copy(latitudeDegrees = null, longitudeDegrees = null, courseOverGroundDegrees = null,
            speed = Double.NaN, quality = next.quality.copy(timestampEpochMs = 1_100))
        model.accept(missing, 100)
        assertNull(model.render(125).latitudeDegrees)
        assertNull(model.render(125).longitudeDegrees)
        assertNull(model.render(125).courseOverGroundDegrees)
        assertTrue(model.render(125).speed.isNaN())
    }

    @Test fun staleLostInvalidAndUnavailableShortCircuitAndDoNotRewriteQuality() {
        for (status in listOf(TelemetryStatus.STALE, TelemetryStatus.LOST, TelemetryStatus.INVALID, TelemetryStatus.UNAVAILABLE)) {
            val model = HelmPresentationModel(sample(0.0))
            val target = sample(5_000.0, 1_050)
            model.accept(target, 50)
            assertEquals(target, model.render(75, status))
            assertEquals(TelemetryStatus.LIVE, model.latestRaw.quality.status)
            assertEquals(1_050L, model.latestRaw.quality.timestampEpochMs)
            assertEquals(target, model.render(80, TelemetryStatus.LIVE))
        }
    }

    @Test fun rawQualityFailureAndFreshReconnectAreImmediate() {
        val model = HelmPresentationModel(sample(0.0))
        model.accept(sample(5_000.0, 1_050), 50)
        val failure = model.latestRaw.copy(quality = model.latestRaw.quality.copy(status = TelemetryStatus.STALE))
        model.accept(failure, 75)
        assertEquals(failure, model.render(75))
        val restored = sample(100.0, 1_100)
        model.accept(restored, 100)
        assertEquals(restored, model.render(100))
    }

    @Test fun evaluatedFreshnessComesFromRawSourceTimestampEvenWithoutNewSamples() {
        val model = HelmPresentationModel(sample(0.0))
        val target = sample(4_000.0, 1_050)
        model.accept(target, 50)
        val status = QualityEvaluator.evaluate(model.latestRaw.quality, 6_051)
        assertEquals(TelemetryStatus.STALE, status)
        assertEquals(target, model.render(75, status))
        assertEquals(TelemetryStatus.LOST, QualityEvaluator.evaluate(model.latestRaw.quality, 31_051))
    }

    @Test fun manualEditsPauseResumeAndExplicitResetNeverTrail() {
        val model = HelmPresentationModel(sample(0.0))
        val manual = sample(7_000.0, 1_050)
        model.accept(manual, 50, immediate = true)
        assertEquals(manual, model.render(50))
        val paused = sample(1_000.0, 1_100).copy(running = false)
        model.accept(paused, 100)
        assertEquals(paused, model.render(100))
        val resumed = sample(2_000.0, 1_150)
        model.accept(resumed, 150)
        assertEquals(resumed, model.render(150))
        model.accept(sample(3_000.0, 1_200), 200)
        val reset = sample(0.0, 1)
        model.reset(reset, 210)
        assertEquals(reset, model.render(210))
    }

    @Test fun sourceAndRunChangesSnapEvenWhenNewSourceClockIsOlder() {
        for (next in listOf(
            sample(10.0, 1).copy(quality = sample(10.0, 1).quality.copy(source = "OTHER")),
            sample(20.0, 1).copy(quality = sample(20.0, 1).quality.copy(simulated = false)),
            sample(30.0, 1).copy(simulationRunId = "run-b"),
        )) {
            val model = HelmPresentationModel(sample(4_000.0))
            assertTrue(model.accept(next, 50))
            assertEquals(next, model.render(50))
        }
    }

    @Test fun simulationResetIsDetectedAtSubsecondAndWholeSecondResolution() {
        val initial = sample(4_000.0).copy(timeTick = 10, simulationElapsedSeconds = 10.9)
        for (next in listOf(
            sample(0.0, 1_050).copy(timeTick = 10, simulationElapsedSeconds = 10.0),
            sample(0.0, 1_050).copy(timeTick = 0, simulationElapsedSeconds = null),
        )) {
            val model = HelmPresentationModel(initial)
            assertTrue(model.accept(next, 50))
            assertEquals(next, model.render(50))
        }
    }

    @Test fun outOfOrderArrivalsAndSourceTimestampsCannotReplaceNewerData() {
        val model = HelmPresentationModel(sample(0.0))
        val target = sample(1_000.0, 1_050)
        model.accept(target, 50)
        assertFalse(model.accept(sample(6_000.0, 1_060), 49))
        assertFalse(model.accept(sample(6_000.0, 1_049), 60))
        assertEquals(target, model.latestRaw)
        assertEquals(500.0, model.render(100).portRpm, 0.0)
    }

    @Test fun longGapSnapsRatherThanAnimatingAcrossAnOldTrajectory() {
        val model = HelmPresentationModel(sample(0.0))
        model.accept(sample(1_000.0, 1_050), 50)
        assertEquals(1_000.0, model.render(1_000).portRpm, 0.0)
        val recovered = sample(6_000.0, 3_000)
        model.accept(recovered, 2_000)
        assertEquals(recovered, model.render(2_000))
    }

    @Test fun renderClockReversalCancelsOldAnimationAndReanchorsNextSample() {
        val model = HelmPresentationModel(sample(0.0))
        val target = sample(1_000.0, 1_050)
        model.accept(target, 50)
        assertEquals(500.0, model.render(100).portRpm, 0.0)
        assertEquals(target, model.render(10))
        model.accept(sample(2_000.0, 1_100), 20)
        assertEquals(1_500.0, model.render(70).portRpm, 0.0)
    }

    @Test fun queuedArrivalDoesNotRewindTheMostRecentlyRenderedValue() {
        val model = HelmPresentationModel(sample(0.0))
        model.accept(sample(1_000.0, 1_050), 50)
        val visible = model.render(100)
        model.accept(sample(2_000.0, 1_090), 90)
        assertEquals(visible.portRpm, model.render(100).portRpm, 0.0)
    }

    @Test fun simulationTimeScaleIsMetadataAndCannotAdvanceSourceValues() {
        val model = HelmPresentationModel(sample(0.0).copy(simulationTimeScale = 1.0))
        val target = sample(4_000.0, 1_050).copy(simulationTimeScale = 100.0)
        model.accept(target, 50)
        val midway = model.render(100)
        assertEquals(100.0, midway.simulationTimeScale!!, 0.0)
        assertEquals(2_000.0, midway.portRpm, 0.0)
        assertEquals(target.simulationElapsedSeconds, midway.simulationElapsedSeconds)
        assertEquals(target, model.render(150))
        assertEquals(target, model.render(50_000))
    }

    @Test fun configurationRejectsUnboundedOrContradictoryTransitions() {
        assertThrows(IllegalArgumentException::class.java) { HelmPresentationModel(sample(0.0), transitionDurationMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { HelmPresentationModel(sample(0.0), transitionDurationMs = 1_001) }
        assertThrows(IllegalArgumentException::class.java) { HelmPresentationModel(sample(0.0), maxInterpolationGapMs = 99) }
    }
}
