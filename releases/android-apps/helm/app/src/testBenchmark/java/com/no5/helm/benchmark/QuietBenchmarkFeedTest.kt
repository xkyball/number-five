package com.no5.helm.benchmark

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test

class QuietBenchmarkFeedTest {
 @Test fun eachPathHasTheSameCompleteTimedRawInputAndLinearSetting(){
  val profile:(Int,Long)->SonarFrame={id,time->SonarFrame(ShortArray(1024){if(it in 400..420)1400 else 30},id.toLong(),time)}
  for(path in listOf("canvas","gpu195","gpuQuiet"))for(auto in listOf(false,true)){
   val feed=QuietBenchmarkFeed(path,2048,auto,profile)
   try{
    repeat(2048){feed.input(it)}
    val model=feed.controller.model
    assertEquals(2048,model.ring.count);assertEquals(2047L,model.latest!!.pingId);assertEquals(1102350L,model.latest!!.timestampEpochMs)
    assertEquals(2048,model.visibleImageCount());assertTrue(feed.controller.linearInterpolation)
    assertEquals(1400,model.ring.sampleAt(900,410));val stored=model.ring.imageMappingAt(900)
    repeat(200){feed.input(2048+it)}
    assertEquals(2247L,model.latest!!.pingId);assertEquals(2048,model.ring.count);assertEquals(0L,model.droppedFrames)
    assertEquals(stored,model.ring.imageMappingAt(1100))
    assertEquals(SonarImageMode.MANUAL,model.display.gainMode) // explicit comparison estimator, no hidden second evaluation
   }finally{feed.close()}
  }
 }
}
