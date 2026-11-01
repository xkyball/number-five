package com.no5.helm.numeric

import org.junit.Assert.*
import org.junit.Test

class NumericSlotsTest {
    @Test fun valuesRetainEveryOriginalCharacterAndNeverGainLeadingZeroes() {
        for(value in listOf("0","1","7","8","9","10","99","100","999","1000","-99.9","+10.0","−0.1","—","1.2*","12345678901234567890.123456789","N 9°00.123′*")) {
            val cells=NumericCells.place(value,NumericSlots(4,3,true,true))
            assertEquals(value,cells.filter { it.visible }.joinToString(""){it.text})
        }
    }
    @Test fun digitCountSignPrecisionMissingAndStaleTransitionsKeepColumnIndices() {
        val spec=NumericSlots(4,1,true,true)
        val values=listOf("0","1","7","8","9","10","99","100","999","1000","-1000.0","999.9*","—")
        val cells=values.map { NumericCells.place(it,spec) }
        assertEquals(1,cells.map { it.size }.distinct().size)
        for(run in cells) {
            assertEquals(".",run[5].text)
            assertEquals("*",run[7].text)
        }
        assertEquals("9",NumericCells.place("9",spec)[4].text)
        assertEquals("0",NumericCells.place("10",spec)[4].text)
        assertEquals("0",NumericCells.place("1000",spec)[4].text)
    }
    @Test fun unitAndFollowingClockFieldsRemainAtFixedPositions() {
        val runs=listOf("0.0 dB","-1.7 dB","-99.9 dB","-160.0 dB","— dB").map { NumericCells.place(it,NumericFormats.db) }
        assertEquals(1,runs.map { it.indexOfFirst { c->!c.fixed } }.distinct().size)
        val clocks=listOf("0:00","9:59","10:00","999:59","1440:00","—:—").map { NumericCells.place(it,NumericFormats.clock,NumericFormats.clockPart) }
        assertEquals(1,clocks.map { it.size }.distinct().size)
    }
    @Test fun utcSecondFractionsReserveTheirSlotWithoutChangingTimestampText() {
        val times=listOf("2026-10-03T01:07:09Z","2026-10-03T01:07:09.123Z")
        val cells=times.map{NumericCells.place(it,NumericSlots(4))}
        assertEquals(cells[0].size,cells[1].size)
        for(i in times.indices)assertEquals(times[i],cells[i].filter{it.visible}.joinToString(""){it.text})
    }
    @Test fun allMappedDisplayFieldsHaveExplicitContracts() {
        for(id in listOf("secondary-port-flow","secondary-stbd-trim","secondary-port-coolant","secondary-stbd-oil","secondary-port-voltage","deck-airTemp","deck-wind","deck-humidity","deck-pressure","deck-fuelPct","deck-remainingL","deck-totalFlow","deck-tripDistance","deck-tripDuration","deck-fuelUsed","deck-efficiency","deck-latitude","deck-longitude","deck-cog"))assertNotNull(id,NumericFormats.helm(id))
        for(label in listOf("FRAME RATE","DROPPED FRAMES","SAMPLE PERIOD","SOUND SPEED","TRANSDUCER OFFSET","KEEL OFFSET","CURRENT CONFIDENCE","LAST PING / EPOCH MS","STREAM LATENCY","RING BUFFER","RAW MIN / MAX","RAW MEAN","DISPLAY SNAPSHOT"))assertNotNull(label,NumericFormats.sonar(label))
    }
}
