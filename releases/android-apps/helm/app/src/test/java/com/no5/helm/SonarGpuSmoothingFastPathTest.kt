package com.no5.helm

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test

/** Independent frozen36 implementation, including its original Double operation order. */
class SonarGpuSmoothingFastPathTest {
    private fun compare(adaptive:Boolean,gap:Boolean) {
        for(scale in listOf(4095,65535))for(display in listOf(SonarDisplay(),SonarDisplay(gainDb=3.0,gamma=.7,threshold=.1),SonarDisplay(gainDb=-9.0,gamma=2.3,threshold=.4))){
            val ring=SonarRingBuffer(32);val old=FrozenSonarDisplaySmoothingCache36();val current=SonarDisplaySmoothingCache()
            var time=1000L
            for(id in 0 until 48){
                time+=if(gap&&id%11==0)600L else 50L
                ring.push(SonarFrame(ShortArray(1024){((it*21401+id*5431)and scale).toShort()},id.toLong(),time),
                    geometry=SonarGeometry(if(adaptive&&id%9<4)2.046 else 3.069,SonarTxReference.HOST_VERIFIED),
                    amplitudeFullScale=scale,pingSource=SonarPingSource.OWN,amplitude=SonarAmplitude(scale,if(scale==4095)12 else 16,true))
                if(id !in listOf(8,17,31,47))continue
                assertTrue(old.begin(ring,null,display));assertTrue(current.begin(ring,null,display))
                for(age in 0 until ring.count){
                    old.prepare(age);current.prepare(age);val bulk=ShortArray(1024);current.copyRawCodes(age,bulk,0)
                    for(bin in 0 until 1024){val expected=old.intensityCodeAt(age,bin);assertEquals("age=$age bin=$bin",expected,current.intensityCodeAt(age,bin));assertEquals(expected,bulk[bin].toInt()and 65535)}
                    assertEquals(old.qualifiedAt(age),current.qualifiedAt(age));assertEquals(old.qualifiedRowsAt(age),current.qualifiedRowsAt(age))
                }
            }
            old.release();current.release()
        }
    }
    @Test fun identicalGeometryUInt16CodesRemainExact()=compare(false,false)
    @Test fun adaptiveGeometryAndFallbackRemainExact()=compare(true,false)
    @Test fun transportGapsAndOverwriteRemainExact()=compare(false,true)
}
