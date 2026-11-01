package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

/** Synthetic capture ranges, never copied live samples, IDs or session data. */
class SonarAdaptiveGeometryTest {
    private var now=1_000_000L
    private val model=SonarModel{now}.also {it.connect("SYNTHETIC GEOMETRY TEST");it.acquisition(true)}
    private fun append(id:Long,maximum:Double?,reference:SonarTxReference=SonarTxReference.HOST_VERIFIED,scale:Int=4095):ShortArray {
        val raw=ShortArray(1024){((it*17+id.toInt())%scale).toShort()}
        val geometry=SonarGeometry(maximum,reference)
        model.setAnalysis(geometry,maximum?.let{SonarBottom(1.536/it*1023,.9)}?:SonarBottom(),null,true,
            amplitude=SonarAmplitude(scale,verified=true))
        model.frame(SonarFrame(raw,id,now));now+=100
        return raw
    }
    @Test fun factorEightMetricRangesKeepEveryRawColumnAndPhysicalDepthThroughFreezeAndEpochChange() {
        model.updateContext(SonarEpoch("synthetic-epoch",1,1),SonarPingSource.ALL)
        val first=append(1,3.072);val second=append(2,24.576)
        assertEquals(2,model.ring.count);assertFalse(model.viewHistoryHasUniformGeometry())
        val axis=model.viewMetersPerBin!!
        for(age in 0..1) {
            val columnBin=model.ring.bottomAt(age).bin!!
            assertEquals(1.536,columnBin*model.ring.metersPerBinValueAt(age),1e-12)
            assertEquals(1.536/axis,model.ring.axisBinAt(age,columnBin,axis),1e-10)
        }
        model.setFreeze(true)
        append(3,3.072);append(4,24.576)
        assertEquals(4,model.ring.count);assertEquals(2,model.viewRing.count)
        assertEquals(axis,model.viewMetersPerBin!!,0.0)
        assertArrayEquals(first,model.ring.atAge(3)!!.samples);assertArrayEquals(second,model.ring.atAge(2)!!.samples)
        assertArrayEquals(first,model.viewRing.atAge(1)!!.samples);assertArrayEquals(second,model.viewRing.atAge(0)!!.samples)
        model.setFreeze(false);assertEquals(4,model.viewRing.count)
        model.updateContext(SonarEpoch("synthetic-epoch",1,2),SonarPingSource.OWN)
        assertEquals(0,model.ring.count);assertNull(model.latest);assertFalse(model.display.freeze)
    }
    @Test fun referenceAndNormalizationBoundariesStillStartANewSegment() {
        append(1,3.072);append(2,24.576);assertEquals(2,model.ring.count)
        append(3,3.072,SonarTxReference.EMPIRICAL_FALLBACK);assertEquals(1,model.ring.count)
        append(4,null,SonarTxReference.UNVERIFIED);assertEquals(1,model.ring.count)
        append(5,3.072);assertEquals(1,model.ring.count)
        append(6,3.072,scale=65535);assertEquals(1,model.ring.count)
    }
    @Test fun physicalProjectionHasExactCoverageAndNeverInventsAnUnmeasuredSample() {
        append(1,3.072);append(2,24.576)
        val axis=model.viewMetersPerBin!!
        assertEquals(1023.0/8,model.ring.axisBinAt(1,1023.0,axis),1e-12)
        assertEquals(1023.0,model.ring.sourceBinAtAxis(1,1023.0/8,axis),1e-10)
        assertTrue(model.ring.sourceBinAtAxis(1,128.0,axis).isNaN())
        assertTrue(model.ring.sourceBinAtAxis(1,-1.0,axis).isNaN())
        assertTrue(model.ring.axisBinAt(1,100.0,Double.NaN).isNaN())
        val raw=SonarRingBuffer(2);raw.push(SonarFrame(ShortArray(1024),1,0))
        assertEquals(100.0,raw.axisBinAt(0,100.0,Double.NaN),0.0)
        assertTrue(raw.axisBinAt(0,100.0,axis).isNaN())
    }
    @Test fun frozenHomogeneousReliefCapabilityIsNotChangedByMixedLiveGeometry() {
        append(1,3.072);append(2,3.072);assertTrue(model.viewHistoryHasUniformGeometry())
        model.setFreeze(true);append(3,24.576)
        assertTrue(model.viewHistoryHasUniformGeometry());assertEquals(2,model.viewRing.count)
        model.setFreeze(false);assertFalse(model.viewHistoryHasUniformGeometry())
    }
}
