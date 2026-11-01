package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

/** Explicit display proof mode; all fixtures are synthetic, never substitute measured profiles. */
class SonarExactPingProfilesTest {
    private var now=1_000_000L
    private var id=0L
    private val geometry=SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED)
    private val amplitude=SonarAmplitude(4095,12,true)
    private fun model()=SonarModel{now}.also {
        it.connect("SYNTHETIC EXACT PING PROOF");it.acquisition(true)
        it.updateContext(SonarEpoch("synthetic-exact",1,1),SonarPingSource.OWN)
        it.setAnalysis(geometry,SonarBottom(),null,false,amplitude=amplitude)
    }
    private fun append(m:SonarModel,range:SonarGeometry=geometry,scale:SonarAmplitude=amplitude) {
        now+=50;id++
        m.setAnalysis(range,SonarBottom(),null,false,amplitude=scale)
        m.frame(SonarFrame(ShortArray(1024){((it+id)and 4095).toShort()},id,now),pingSource=m.selectedPingSource?:SonarPingSource.OWN)
    }
    @Test fun explicitRawSelectionOverridesTheWorkLatchAndRetainedFallbackWithoutChangingEvidence() {
        val m=model();assertFalse(m.display.exactPingProfiles)
        repeat(300){append(m)};assertTrue(m.imageHistoryAggregated)
        val raw=m.latest!!.samples;val count=m.ring.count;val rate=m.measuredRate();val version=m.imageHistory.version
        m.setDisplay(m.display.copy(exactPingProfiles=true))
        assertFalse(m.imageHistoryAggregated);assertSame(m.ring,m.viewImageColumns)
        assertEquals(count,m.ring.count);assertArrayEquals(raw,m.latest!!.samples);assertEquals(rate,m.measuredRate());assertEquals(version,m.imageHistory.version)
        append(m,SonarGeometry(null,SonarTxReference.UNVERIFIED),SonarAmplitude(null,verified=false))
        assertEquals(1,m.ring.count);assertFalse(m.imageHistoryAggregated);assertSame(m.ring,m.viewImageColumns)
        m.setDisplay(m.display.copy(exactPingProfiles=false))
        assertTrue("The unchanged normal retained-image policy resumes",m.imageHistoryAggregated)
    }
    @Test fun fullRawRingAndFrozenProofStayRawAcrossNewLiveSamplesAndEpochsPreserveThePreference() {
        val m=model();m.setDisplay(m.display.copy(historySeconds=600.0,exactPingProfiles=true))
        repeat(2050){append(m)}
        assertEquals(2048,m.ring.count);assertFalse(m.imageHistoryAggregated);assertSame(m.ring,m.viewImageColumns)
        assertTrue(m.historyCoverage().capacityLimited)
        m.setFreeze(true);val held=m.viewImageColumns;val newest=m.imageNewestTime()
        repeat(10){append(m)}
        assertSame(held,m.viewImageColumns);assertFalse(m.imageHistoryAggregated);assertEquals(newest,m.imageNewestTime())
        m.updateContext(SonarEpoch("synthetic-exact-new",2,2),SonarPingSource.NATIVE)
        assertTrue(m.display.exactPingProfiles);assertFalse(m.display.freeze);assertEquals(0,m.ring.count);assertFalse(m.imageHistoryAggregated)
        append(m);assertSame(m.ring,m.viewImageColumns)
        m.resetAcquisition();assertTrue(m.display.exactPingProfiles);assertEquals(0,m.ring.count)
    }
}
