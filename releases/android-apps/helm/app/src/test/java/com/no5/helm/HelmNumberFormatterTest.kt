package com.no5.helm

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class HelmNumberFormatterTest {
 @Test fun exactRoundingSignsAndPrecisionSurviveCacheHitsAndEviction() {
  val values=listOf(-0.0,0.0,1.25,1.35,9.95,-9.95,Double.NaN,Double.POSITIVE_INFINITY,
   Double.NEGATIVE_INFINITY,Double.MIN_VALUE,Double.MAX_VALUE)+(0..600).map{it/7.0}
  repeat(2) { for(value in values) for(d in 0..3)
   assertEquals(String.format(Locale.US,"%.${d}f",value),format(value,d)) }
  assertTrue(HelmNumberFormatter.retainedEntries()<=256)
 }
}
