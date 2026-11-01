package com.no5.domain

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SonarImageAutoTest {
    private fun profile(t: Long, level: Double = 1.0, clipped: Boolean = false) = SonarFrame(
        ShortArray(1024) { b -> (if(clipped)4095 else ((if(b in 400..500)400 else 35+b%7)*level).toInt().coerceIn(0,4095)).toShort() }, t, t)
    private val initial=SonarImageValues(0.0,1.0,0.0)
    @Test fun bootstrapRequiresASecondAndTenValidPings() {
        val a=SonarImageAuto()
        repeat(10){assertNull(a.observe(profile(it*50L),4095,it*50L,initial))}
        assertEquals(SonarImageAutoState.WARMING,a.state)
        assertNotNull(a.observe(profile(1000),4095,1000,initial))
    }
    @Test fun sparsePingsCannotPretendTheyHaveEnoughEvidence() {
        val a=SonarImageAuto()
        repeat(9){assertNull(a.observe(profile(it*1000L),4095,it*1000L,initial))}
        assertNotNull(a.observe(profile(9000),4095,9000,initial))
    }
    @Test fun slewAndEvaluationCountAreIndependentOfInputRate() {
        for(hz in listOf(10,20,100)) {
            val a=SonarImageAuto();var v=initial;var updates=0
            repeat(hz*40){i -> val t=i*1000L/hz
                a.observe(profile(t,if(t<10000)1.0 else .1),4095,t,v)?.let { n ->
                    assertTrue(abs(n.gainDb-v.gainDb)<=.2500001)
                    assertTrue(abs(n.gamma-v.gamma)<=.0100001)
                    assertTrue(abs(n.threshold-v.threshold)<=.0010001)
                    updates++;v=n
                }
            }
            assertEquals(39,updates)
            assertTrue(v.gainDb<10) // no three-second global reacquisition contract
        }
    }
    @Test fun oneClippedPingDoesNotCreateACalibration() {
        val a=SonarImageAuto()
        repeat(50){assertNull(a.observe(profile(it*50L,clipped=true),4095,it*50L,initial))}
        assertEquals(0,a.evaluations)
    }
    @Test fun singleInterferenceAndHundredPingBurstAreBounded() {
        val a=SonarImageAuto();var v=initial
        repeat(2400){i->val t=i*50L;v=a.observe(profile(t),4095,t,v)?:v}
        val before=v
        repeat(100){i->val t=120000+i*50L;v=a.observe(profile(t,6.0),4095,t,v)?:v}
        assertTrue(abs(v.gainDb-before.gainDb)<=1.250001)
        assertTrue(abs(v.gamma-before.gamma)<=.050001)
        assertTrue(abs(v.threshold-before.threshold)<=.005001)
    }
    @Test fun sustainedLevelChangeEventuallyAdaptsWithinTheRollingHorizon() {
        val a=SonarImageAuto();var v=initial;var before=initial
        repeat(9000){i->val t=i*50L;v=a.observe(profile(t,if(t<150000)1.0 else .1),4095,t,v)?:v;if(t==149950L)before=v}
        assertTrue(v.gainDb>before.gainDb+8)
    }
    @Test fun scaleAndInterruptionRequireFreshBootstrap() {
        val a=SonarImageAuto();var v=initial
        repeat(100){i->v=a.observe(profile(i*50L),4095,i*50L,v)?:v}
        assertNull(a.observe(profile(5000),null,5000,v));assertEquals(SonarImageAutoState.INCOMPATIBLE,a.state)
        assertNull(a.observe(profile(5050),4095,5050,v));assertEquals(SonarImageAutoState.WARMING,a.state)
        assertNull(a.observe(profile(10000),4095,10000,v));assertEquals(SonarImageAutoState.WARMING,a.state)
    }
    @Test fun histogramPlusMaximumColumnMetadataStayWithinFourMiB() {
        // JVM ring, GPU padded stripe, JNI staging, and conservative object overhead.
        assertTrue(SonarImageAuto().estimatedBytes + 12000*(12L+8L+8L) + 2*47*256*16L + 256*32L + 512*1024L < 4*1024*1024)
    }
    @Test fun mappingsSurviveEvictionMoveAndExplicitRebaseWithoutRawChanges() {
        val r=SonarRingBuffer(20);repeat(20){i->r.push(profile(i*50L),imageMapping=SonarImageValues(i.toDouble(),1.0,.01))}
        val old=r.atAge(10)!!;val m=r.moveToCapacity(40)
        assertSame(old,m.atAge(10));assertEquals(9.0,m.imageMappingAt(10)!!.gainDb,0.0)
        m.rebaseImageMapping(SonarImageField.GAMMA,1.4)
        assertSame(old,m.atAge(10));assertEquals(1.4,m.imageMappingAt(10)!!.gamma,.00001)
        m.evictBefore(800.0);assertEquals(4,m.count);m.clear();assertEquals(0,m.count)
    }
    @Test fun automaticNewColumnsNeverRecolorRetainedColumnsAndOffsetDoes() {
        val r=SonarRingBuffer(200)
        var v=initial;val a=SonarImageAuto();val d=SonarDisplay(gainMode=SonarImageMode.AUTO,gammaMode=SonarImageMode.AUTO,thresholdMode=SonarImageMode.AUTO)
        repeat(100){i->val t=i*50L;r.push(profile(t),imageMapping=v);v=a.observe(profile(t),4095,t,v)?:v}
        val retained=r.imageValuesAt(80,d);repeat(100){i->val t=5000+i*50L;r.push(profile(t,.1),imageMapping=v);v=a.observe(profile(t,.1),4095,t,v)?:v}
        assertEquals(retained,r.imageValuesAt(180,d))
        assertEquals(retained.gainDb+2,r.imageValuesAt(180,d.copy(autoGainOffsetDb=2.0)).gainDb,.00001)
        assertEquals(36.0,r.imageValuesAt(180,d.copy(autoGainOffsetDb=24.0,gainMode=SonarImageMode.MANUAL,gainDb=36.0)).gainDb,0.0)
    }
    @Test fun sustainedSaturationCannotReanimateAnExpiredCalibrationWithOneGoodPing(){
        val a=SonarImageAuto();var v=initial
        repeat(100){i->val t=i*50L;v=a.observe(profile(t),4095,t,v)?:v}
        repeat(80){i->val t=5000+i*50L;assertNull(a.observe(profile(t,clipped=true),4095,t,v))}
        assertNull(a.observe(profile(9000),4095,9000,v))
        assertEquals(SonarImageAutoState.WARMING,a.state)
    }

}
