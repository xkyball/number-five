package com.no5.domain
import org.junit.Test
import kotlin.math.pow
import kotlin.test.*
class SoundEqDisplayBatchTest {
 @Test fun displayBatchIsBitIdenticalToTheOriginalFormulaAcrossAllPointCountsAndBudgets(){
  val frequencies=doubleArrayOf(0.0,24000.0)+DoubleArray(512){20.0*1000.0.pow(it/511.0)}
  for(count in 0..16)for(gain in listOf(-12.0,0.0,6.0,12.0,18.0,21.0)){
   val points=List(count){i->SoundEqPoint("p$i",20.0*1000.0.pow(i/16.0),if(i%3==0)-12.0 else gain,.3+i*.58)}
   val batch=SoundEqResponse.gainsDb(points,frequencies)
   frequencies.forEachIndexed{i,f->assertEquals(SoundEqResponse.gainDb(points,f).toBits(),batch[i].toBits(),"$count/$gain/$f")}
  }
 }
 @Test fun invalidDisplayFrequenciesRemainRejected(){
  for(f in listOf(Double.NaN,-1.0,24001.0))assertFails{SoundEqResponse.gainsDb(emptyList(),doubleArrayOf(f))}
 }
}
