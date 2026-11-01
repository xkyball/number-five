package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class SonarObservationTest {
    @Test fun sevenSlotBurstsUseUniqueFiveSecondEventsNotTheirIntervals() {
        val rate = SonarPingRateWindow()
        var id = 0L
        // 7 slots every 400 ms: the 27 ms intraburst interval is not 37 Hz.
        for (cycle in 0 until 15) for (slot in 0..6) {
            val time = cycle * 400L + slot * 27L
            rate.observe(id++, time, time)
        }
        val result = rate.read(6000, true, SonarPingSource.OWN)
        assertEquals(SonarRateState.LIVE, result.state)
        assertEquals(84, result.eventCount)
        assertEquals(16.8, result.hz!!, 1e-12)
        assertEquals(5000L, result.coverageMs)
        assertEquals(SonarPingSource.OWN, result.selectedSource)
    }
    @Test fun duplicatesDoNotCountAndIncompleteWindowsAreWarming() {
        val rate = SonarPingRateWindow()
        assertTrue(rate.observe(7, 1000, 1001))
        assertFalse(rate.observe(7, 1000, 1002))
        assertTrue(rate.observe(8, 1100, 1101))
        val result = rate.read(2000, true)
        assertEquals(2, result.eventCount)
        assertEquals(1000L, result.coverageMs)
        assertEquals(SonarRateState.WARMING, result.state)
    }
    @Test fun livingConnectionGoesToZeroButStatusCannotFreshenMeasurements() {
        val rate = SonarPingRateWindow()
        rate.observe(1, 1000, 1000)
        assertEquals(SonarRateState.ZERO, rate.read(6000, true).state)
        assertEquals(0.0, rate.read(6500, true).hz!!, 0.0)
        assertEquals(SonarRateState.STALE, rate.read(6501, false).state)
        assertNull(rate.read(6502, false).hz)
    }
    @Test fun aLiveSourceWithNoFirstPingShowsZeroAfterFullObservationWindow() {
        val rate = SonarPingRateWindow()
        rate.start(1000)
        assertEquals(SonarRateState.WARMING, rate.read(5999, true).state)
        assertEquals(SonarRateState.ZERO, rate.read(6000, true).state)
        assertEquals(0.0, rate.read(6000, true).hz!!, 0.0)
    }
    @Test fun digitsAreGatedButStateChangesAreImmediate() {
        val rate = SonarPingRateWindow()
        rate.observe(1, 0, 0)
        val first = rate.read(5000, true)
        rate.observe(2, 5100, 5100)
        val next = rate.read(5100, true)
        assertEquals(SonarRateState.LIVE, next.state) // zero -> live is immediate
        rate.observe(3, 5200, 5200)
        assertEquals(next, rate.read(5200, true))
        assertNotEquals(next.eventCount, rate.read(5600, true).eventCount)
        assertEquals(SonarRateState.ZERO, first.state)
    }
    @Test fun receptionFallbackIsExplicitAndNeverMixesTimeBases() {
        val rate = SonarPingRateWindow()
        rate.observe(1, 0, 0)
        rate.observe(2, null, 1000)
        val result = rate.read(1500, true)
        assertEquals(SonarRateTimeBasis.RECEPTION, result.timeBasis)
        assertEquals(1, result.eventCount)
        assertEquals(500L, result.coverageMs)
    }
    @Test fun boundedCounterDoesNotClaimAnExactRateAfterOverflow() {
        val rate = SonarPingRateWindow(2)
        for (id in 0L..5L) rate.observe(id, 1000 + id, 1000 + id)
        assertEquals(SonarRateState.INCOMPLETE, rate.read(2000, true).state)
        assertNull(rate.read(2000, true).hz)
        assertEquals(SonarRateState.ZERO, rate.read(7000, true).state)
    }
    @Test fun invalidAmplitudeCannotBecomeVerifiedByAFlagAlone() {
        assertTrue(SonarAmplitude(4095, 12, true).usable)
        assertFalse(SonarAmplitude(65535, 12, true).usable)
        assertFalse(SonarAmplitude(null, 12, true).usable)
        assertFalse(SonarAmplitude(4095, 12, false).usable)
    }
}
