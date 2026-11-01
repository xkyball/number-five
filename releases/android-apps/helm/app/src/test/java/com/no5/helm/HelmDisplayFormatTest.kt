package com.no5.helm

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.Random

class HelmDisplayFormatTest {
    @Test fun roundedBinsPreserveFormatterAtBothSidesOfEveryRelevantHalfwayBoundary() {
        for(d in 0..3) {
            val scale=Math.pow(10.0,d.toDouble())
            for(i in -2000..2000) {
                val middle=(i+.5)/scale
                for(v in listOf(Math.nextDown(middle),middle,Math.nextUp(middle)))
                    assertEquals("value=$v decimals=$d",String.format(Locale.US,"%.${d}f",v),HelmNumberFormatter.format(v,d))
            }
        }
    }
    @Test fun arbitraryValuesSignedZeroExtremesAndEvictionRemainExact() {
        val random=Random(1042)
        repeat(10000) {
            val d=it%7
            val v=(random.nextDouble()-.5)*Math.pow(10.0,(it%18).toDouble())
            assertEquals("value=$v decimals=$d",String.format(Locale.US,"%.${d}f",v),HelmNumberFormatter.format(v,d))
        }
        for(v in listOf(-0.0,0.0,Double.MIN_VALUE,-Double.MIN_VALUE,Double.MAX_VALUE,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY))
            for(d in 0..6)assertEquals(String.format(Locale.US,"%.${d}f",v),HelmNumberFormatter.format(v,d))
        assertTrue(HelmNumberFormatter.retainedEntries()<=256)
    }
}
