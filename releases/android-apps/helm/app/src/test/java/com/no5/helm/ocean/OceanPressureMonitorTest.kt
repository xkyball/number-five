package com.no5.helm.ocean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class OceanPressureMonitorTest {
    private val displayBudget = 16_666_667L
    private fun sample(duration: Long, count: Int = 60, deadline: Long = displayBudget): OceanFramePressure {
        val accumulator = OceanFramePressureAccumulator()
        repeat(count) { accumulator.record(duration, displayBudget, frameDeadlineNanos = deadline) }
        return accumulator.drain()
    }

    @Test fun ordinaryBoundaryJitterNeverDisablesAnimation() {
        val governor = OceanQualityGovernor()
        for (duration in listOf(17_000_000L, 18_000_000L, 19_000_000L)) {
            val measured = sample(duration)
            assertFalse(measured.overBudget)
            repeat(120) { governor.observeMeasuredPressure(1f, measured) }
        }
        assertEquals(OceanQuality.HIGH, governor.quality)
    }

    @Test fun actualFrameDeadlineTakesPriorityOverOneRefreshPeriod() {
        // Android can allocate more than a single display period for an individual frame.
        val measured = sample(25_000_000L, deadline = 30_000_000L)
        assertFalse(measured.overBudget)
        assertTrue(measured.healthy)
        assertEquals(0L, measured.lateFrames)
        assertEquals(25.0 / 30.0, measured.meanRatio, .0001)
    }

    @Test fun unavailableDeadlineFallsBackToPerFrameDisplayBudget() {
        val accumulator = OceanFramePressureAccumulator()
        repeat(60) { accumulator.record(14_000_000L, 8_333_333L, frameDeadlineNanos = -1) }
        val measured = accumulator.drain()
        assertTrue(measured.overBudget)
        assertFalse(measured.severePressure)
        assertEquals(60L, measured.lateFrames)
    }

    @Test fun changingFrameDeadlinesAreComparedIndividually() {
        val accumulator = OceanFramePressureAccumulator()
        repeat(10) {
            accumulator.record(30_000_000L, displayBudget, frameDeadlineNanos = 40_000_000L)
            accumulator.record(15_000_000L, displayBudget, frameDeadlineNanos = 10_000_000L)
        }
        val measured = accumulator.drain()
        assertEquals(10L, measured.lateFrames)
        assertEquals(1.125, measured.meanRatio, 0.0)
        assertFalse(measured.overBudget)
    }

    @Test fun isolatedHugeStallCannotTurnMostlyHealthyFramesIntoSustainedPressure() {
        val accumulator = OceanFramePressureAccumulator()
        accumulator.record(1_000_000_000L, displayBudget)
        repeat(59) { accumulator.record(12_000_000L, displayBudget) }
        val measured = accumulator.drain()
        assertFalse(measured.overBudget)
        assertFalse(measured.healthy) // A stall also interrupts a sustained recovery interval.
        val governor = OceanQualityGovernor()
        repeat(120) { governor.observeMeasuredPressure(1f, measured) }
        assertEquals(OceanQuality.HIGH, governor.quality)
    }

    @Test fun sustainedModerateMissesReduceDetailsButNeverSelectPhotograph() {
        val governor = OceanQualityGovernor()
        val measured = sample(27_000_000L)
        assertTrue(measured.overBudget)
        assertFalse(measured.severePressure)
        repeat(5) { governor.observeMeasuredPressure(1f, measured) }
        assertEquals(OceanQuality.HIGH, governor.quality)
        governor.observeMeasuredPressure(1f, measured)
        assertEquals(OceanQuality.LOW, governor.quality)
        repeat(120) { governor.observeMeasuredPressure(1f, measured) }
        assertEquals(OceanQuality.LOW, governor.quality)
    }

    @Test fun seriousContinuedMissesAfterLowEventuallySelectStatic() {
        val governor = OceanQualityGovernor()
        val measured = sample(45_000_000L, count = 20)
        assertTrue(measured.severePressure)
        repeat(6) { governor.observeMeasuredPressure(1f, measured) }
        assertEquals(OceanQuality.LOW, governor.quality)
        repeat(9) { governor.observeMeasuredPressure(1f, measured) }
        assertEquals(OceanQuality.LOW, governor.quality)
        governor.observeMeasuredPressure(1f, measured)
        assertEquals(OceanQuality.STATIC, governor.quality)
    }

    @Test fun insufficientSlowSamplesAreNotPressureButSeveralLongStallsAre() {
        val governor = OceanQualityGovernor()
        val sparse = sample(40_000_000L, count = 2)
        assertFalse(sparse.sufficientPressureEvidence)
        repeat(60) { governor.observeMeasuredPressure(1f, sparse) }
        assertEquals(OceanQuality.HIGH, governor.quality)
        val prolonged = sample(200_000_000L, count = 3)
        assertTrue(prolonged.sufficientPressureEvidence)
        assertTrue(prolonged.severePressure)
        repeat(6) { governor.observeMeasuredPressure(1f, prolonged) }
        assertEquals(OceanQuality.LOW, governor.quality)
    }

    @Test fun firstDrawAndUnavailableMetricsDoNotCreatePressure() {
        val accumulator = OceanFramePressureAccumulator()
        accumulator.record(500_000_000L, displayBudget, firstDraw = true)
        accumulator.record(-1L, displayBudget)
        accumulator.record(100L, 0L)
        val measured = accumulator.drain()
        assertEquals(0L, measured.frameCount)
        assertTrue(measured.firstDrawSeen)
        assertFalse(measured.overBudget)
        assertEquals(OceanFramePressure(), accumulator.drain())
    }

    @Test fun startupResumeAndLayoutResetHaveAFullSettlingInterval() {
        val gate = OceanPressureSamplingGate()
        assertFalse(gate.ready(0))
        gate.reset(0)
        assertFalse(gate.ready(2_999_999_999L))
        assertTrue(gate.ready(3_000_000_000L))
        gate.reset(100_000_000_000L) // new resumed window / first draw / quality change
        assertFalse(gate.ready(102_999_999_999L))
        assertTrue(gate.ready(103_000_000_000L))
        val governor = OceanQualityGovernor()
        val layoutSample = sample(45_000_000L).copy(firstDrawSeen = true)
        repeat(10) { governor.observeMeasuredPressure(1f, layoutSample) }
        assertEquals(OceanQuality.HIGH, governor.quality)
    }

    @Test fun safetySignalsOverrideWarmupSparseAndMissingFramesImmediately() {
        for (thermal in listOf(false, true)) {
            val governor = OceanQualityGovernor()
            governor.observeMeasuredPressure(1f, OceanFramePressure(firstDrawSeen = true),
                severeThermal = thermal, lowMemory = !thermal)
            assertEquals(OceanQuality.STATIC, governor.quality)
        }
    }

    @Test fun idleStaticWindowRequiresThirtySecondsOfActuallyHealthyFrames() {
        val governor = OceanQualityGovernor()
        governor.observeMeasuredPressure(1f, OceanFramePressure(), lowMemory = true)
        repeat(60) { governor.observeMeasuredPressure(1f, OceanFramePressure()) }
        assertEquals(OceanQuality.STATIC, governor.quality)
        val healthy = sample(5_000_000L, count = 1)
        repeat(29) { governor.observeMeasuredPressure(1f, healthy) }
        assertEquals(OceanQuality.STATIC, governor.quality)
        governor.observeMeasuredPressure(1f, healthy)
        assertEquals(OceanQuality.LOW, governor.quality)
        repeat(30) { governor.observeMeasuredPressure(1f, healthy) }
        assertEquals(OceanQuality.HIGH, governor.quality)
    }

    @Test fun missingFrameEvidenceBreaksSustainedRecovery() {
        val governor = OceanQualityGovernor()
        val healthy = sample(5_000_000L)
        governor.observeMeasuredPressure(1f, OceanFramePressure(), severeThermal = true)
        repeat(29) { governor.observeMeasuredPressure(1f, healthy) }
        governor.observeMeasuredPressure(1f, OceanFramePressure())
        governor.observeMeasuredPressure(1f, healthy)
        assertEquals(OceanQuality.STATIC, governor.quality)
        repeat(29) { governor.observeMeasuredPressure(1f, healthy) }
        assertEquals(OceanQuality.LOW, governor.quality)
    }

    @Test fun concurrentCallbacksAndSamplingDoNotLoseMeasurements() {
        val accumulator = OceanFramePressureAccumulator()
        val begin = CountDownLatch(1)
        val workers = List(4) {
            thread(start = true) {
                begin.await()
                repeat(10_000) { accumulator.record(30_000_000L, 10_000_000L) }
            }
        }
        begin.countDown()
        var received = 0L
        while (workers.any { it.isAlive }) {
            val measured = accumulator.drain()
            assertEquals(measured.frameCount, measured.lateFrames)
            assertEquals(measured.frameCount, measured.severeFrames)
            received += measured.frameCount
            Thread.yield()
        }
        workers.forEach { it.join() }
        received += accumulator.drain().frameCount
        assertEquals(40_000L, received)
    }
}
