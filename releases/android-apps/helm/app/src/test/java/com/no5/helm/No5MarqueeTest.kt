package com.no5.helm

import com.no5.header.artwork.No5Marquee
import org.junit.Assert.*
import org.junit.Test

class No5MarqueeTest {
    @Test fun fittingInvalidAndReducedMotionTextNeverMoves() {
        listOf(0L, 1200L, 5000L, Long.MAX_VALUE).forEach { time ->
            assertEquals(0f, No5Marquee.offset(100f, 100f, time), 0f)
            assertEquals(0f, No5Marquee.offset(500f, 100f, time, enabled = false), 0f)
            assertEquals(0f, No5Marquee.offset(Float.NaN, 100f, time), 0f)
            assertEquals(0f, No5Marquee.offset(500f, 0f, time), 0f)
        }
    }

    @Test fun actualOverflowPausesThenMovesAtFortyEightDesignPixelsPerSecond() {
        assertEquals(0f, No5Marquee.offset(580f, 100f, 1200), .001f)
        assertEquals(48f, No5Marquee.offset(580f, 100f, 2200), .001f)
        assertEquals(480f, No5Marquee.offset(580f, 100f, 11200), .001f)
        assertEquals(480f, No5Marquee.offset(580f, 100f, 12400), .001f)
        assertEquals(432f, No5Marquee.offset(580f, 100f, 13400), .001f)
        assertEquals(0f, No5Marquee.offset(580f, 100f, 22400), .001f)
    }

    @Test fun longTitleRemainsWithinItsClipDistance() {
        for (time in 0L..100_000L step 37L) {
            val offset = No5Marquee.offset(943f, 232f, time)
            assertTrue(offset in 0f..711f)
        }
    }
}
