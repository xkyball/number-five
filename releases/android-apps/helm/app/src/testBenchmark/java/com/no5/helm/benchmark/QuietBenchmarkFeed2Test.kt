package com.no5.helm.benchmark

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test

class QuietBenchmarkFeed2Test {
 @Test fun pendingPingsAreAllAdoptedInOrderBeforeOnePublishedSnapshot(){
  val profile:(Int,Long)->SonarFrame={id,time->SonarFrame(ShortArray(1024){if(it in 400..420)1400 else 30},id.toLong(),time)}
  for(path in listOf("gpu195","gpuQuiet")){
   val f=QuietBenchmarkFeed2(path,2048,true,profile)
   try{
    val before=f.controller.snapshot
    repeat(100){f.input(it,123000L+it,publish=false)}
    assertEquals(before,f.controller.snapshot)
    assertEquals(100,f.controller.model.ring.count);assertEquals(99L,f.controller.model.latest!!.pingId)
    f.publish();assertEquals(99L,f.controller.snapshot.pingId);assertEquals(100,f.controller.snapshot.ringCount)
    assertEquals(123099L,f.controller.model.ring.localReceivedAtNanosAt(0));assertEquals(0L,f.controller.model.droppedFrames)
    assertTrue(f.controller.linearInterpolation)
   }finally{f.close()}
  }
 }
}
