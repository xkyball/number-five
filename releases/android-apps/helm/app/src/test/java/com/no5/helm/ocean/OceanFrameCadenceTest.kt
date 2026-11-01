package com.no5.helm.ocean

import org.junit.Assert.*
import org.junit.Test

class OceanFrameCadenceTest {
    @Test fun lowPublishesThirtyBackgroundFramesWhileInstrumentsReceiveEveryDisplayFrame() {
        for (displayHz in listOf(60, 90, 120)) {
            val low = OceanFrameCadence()
            val high = OceanFrameCadence()
            var instrumentFrames = 0
            var lowBackgroundFrames = 0
            var highBackgroundFrames = 0
            repeat(displayHz) { index ->
                val frameNanos = index * 1_000_000_000L / displayHz
                instrumentFrames++
                if (low.shouldPublish(frameNanos, OceanQuality.LOW)) lowBackgroundFrames++
                if (high.shouldPublish(frameNanos, OceanQuality.HIGH)) highBackgroundFrames++
            }
            assertEquals(displayHz, instrumentFrames)
            assertEquals(displayHz, highBackgroundFrames)
            assertEquals("LOW at $displayHz Hz", 30, lowBackgroundFrames)
        }
    }

    @Test fun floorRoundedDisplayIntervalsDoNotAliasLowToTwentyFramesPerSecond() {
        for (displayHz in listOf(60, 120)) {
            val cadence = OceanFrameCadence()
            val roundedDisplayInterval = 1_000_000_000L / displayHz
            val publications = (0 until displayHz).count {
                cadence.shouldPublish(it * roundedDisplayInterval, OceanQuality.LOW)
            }
            assertEquals("Floor-rounded $displayHz Hz", 30, publications)
        }
    }

    @Test fun lowHonorsItsBoundedToleranceAndDoesNotCatchUpAfterAStall() {
        val cadence = OceanFrameCadence()
        val interval = OceanFrameCadence.LOW_INTERVAL_NANOS
        val tolerance = OceanFrameCadence.ROUNDING_TOLERANCE_NANOS
        assertTrue(cadence.shouldPublish(0, OceanQuality.LOW))
        assertFalse(cadence.shouldPublish(interval - tolerance - 1, OceanQuality.LOW))
        assertTrue(cadence.shouldPublish(interval - tolerance, OceanQuality.LOW))
        assertTrue(cadence.shouldPublish(9_000_000_000L, OceanQuality.LOW))
        assertFalse(cadence.shouldPublish(9_000_000_001L, OceanQuality.LOW))
    }

    @Test fun irregularDisplayCadenceKeepsPublicationSpacingAndNeverQueuesCatchUpFrames() {
        val cadence = OceanFrameCadence()
        val spacing = OceanFrameCadence.LOW_INTERVAL_NANOS - OceanFrameCadence.ROUNDING_TOLERANCE_NANOS
        val callbacks = listOf(0L, 16_500_000L, 33_100_000L, 49_900_000L, 66_300_000L,
            100_000_000L, 180_000_000L, 180_100_000L, 196_600_000L, 213_200_000L)
        val publications = callbacks.filter { cadence.shouldPublish(it, OceanQuality.LOW) }
        assertEquals(listOf(0L, 33_100_000L, 66_300_000L, 100_000_000L, 180_000_000L, 213_200_000L), publications)
        assertTrue(publications.zipWithNext().all { (before, after) -> after - before >= spacing })
    }

    @Test fun staticStopsPublicationAndActivationResetsTheGateWithoutWaiting() {
        val cadence = OceanFrameCadence()
        assertTrue(cadence.shouldPublish(1_000_000_000L, OceanQuality.LOW))
        assertFalse(cadence.shouldPublish(2_000_000_000L, OceanQuality.STATIC))
        cadence.reset()
        assertTrue(cadence.shouldPublish(2_000_000_001L, OceanQuality.LOW))
        assertFalse(cadence.shouldPublish(2_000_000_002L, OceanQuality.LOW))
        assertTrue(cadence.shouldPublish(2_000_000_003L, OceanQuality.HIGH))
    }

    @Test fun clockEpochChangeRebasesWithoutFreezingTheLayer() {
        val cadence = OceanFrameCadence()
        assertTrue(cadence.shouldPublish(1_000_000_000L, OceanQuality.LOW))
        assertTrue(cadence.shouldPublish(0, OceanQuality.LOW))
        assertFalse(cadence.shouldPublish(1, OceanQuality.LOW))
    }
}
