package com.no5.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WifiSignalTest {
    @Test public void offDisconnectedUnknownAndConnectedHaveDistinctEvidence() {
        assertEquals(WifiSignal.State.OFF, WifiSignal.fromObservation(false, true, -60).state);
        assertEquals(WifiSignal.State.DISCONNECTED, WifiSignal.fromObservation(true, false, -60).state);
        assertEquals(WifiSignal.State.UNKNOWN, WifiSignal.fromObservation(null, false, -60).state);
        assertEquals(WifiSignal.State.UNKNOWN, WifiSignal.fromObservation(true, null, -60).state);
        assertEquals(WifiSignal.State.CONNECTED, WifiSignal.fromObservation(null, true, -60).state);
        assertEquals(Integer.valueOf(-60), WifiSignal.fromObservation(true, true, -60).rssiDbm);
        assertNull(WifiSignal.off().rssiDbm); assertNull(WifiSignal.disconnected().rssiDbm);
    }
    @Test public void unavailableRssiNeverBecomesZeroDbmOrAClaimedSignalLevel() {
        for (Integer value : new Integer[] { null, -128, -127, 0, 20 }) {
            WifiSignal signal = WifiSignal.connected(value);
            assertEquals(WifiSignal.State.CONNECTED, signal.state);
            assertNull(signal.rssiDbm); assertEquals(-1, signal.bars);
        }
    }
    @Test public void commonFiveLevelScaleIsMonotoneAndKeepsActualDbm() {
        int previous = 0;
        for (int rssi = -126; rssi < 0; rssi++) {
            WifiSignal signal = WifiSignal.connected(rssi);
            assertEquals(Integer.valueOf(rssi), signal.rssiDbm);
            assertTrue(signal.bars >= previous); assertTrue(signal.bars >= 0 && signal.bars <= 4);
            previous = signal.bars;
        }
        assertEquals(0, WifiSignal.barsForRssi(-100)); assertEquals(0, WifiSignal.barsForRssi(-89));
        assertEquals(1, WifiSignal.barsForRssi(-88)); assertEquals(2, WifiSignal.barsForRssi(-77));
        assertEquals(3, WifiSignal.barsForRssi(-66)); assertEquals(4, WifiSignal.barsForRssi(-55));
        assertEquals(-1, WifiSignal.barsForRssi(-127)); assertEquals(-1, WifiSignal.barsForRssi(0));
    }
    @Test public void immutableValuesDeduplicateOnlyIdenticalStateAndActualRssi() {
        assertEquals(WifiSignal.connected(-60), WifiSignal.connected(-60));
        assertEquals(WifiSignal.connected(-60).hashCode(), WifiSignal.connected(-60).hashCode());
        assertNotEquals(WifiSignal.connected(-60), WifiSignal.connected(-61));
        assertNotEquals(WifiSignal.unknown(), WifiSignal.disconnected());
    }
}
