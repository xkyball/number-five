package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

/** Synthetic accelerated clocks only; no hardware run or fictional measured pings. */
class SonarTimeImageHistoryTest {
    private val geometry=SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED)
    private fun frame(id:Long,time:Long,value:Int=100)=SonarFrame(ShortArray(1024){value.toShort()},id,time)
    @Test fun sixHundredSecondsAtTwentyHzSurviveWithoutChangingRawCapacityOrTheChosenAxis() {
        var now=1_000_000L
        val m=SonarModel{now};m.connect("synthetic");m.acquisition(true)
        m.setAnalysis(geometry,SonarBottom(),null,false)
        m.setDisplay(m.display.copy(historySeconds=600.0))
        for(id in 0L..12_000L){now=1_000_000+id*50;m.frame(frame(id,now),pingSource=SonarPingSource.OWN)}
        assertEquals(2048,m.ring.count)
        assertEquals(102.35,m.historyCoverage().availableSeconds,1e-10)
        assertTrue(m.imageHistoryAggregated)
        assertTrue(m.viewImageColumns===m.imageHistory)
        assertEquals(600.0,m.viewDisplay.historySeconds,0.0)
        assertEquals(600.0,m.imageAvailableSeconds(),0.0)
        assertEquals(1,m.imageHistory.allocatedPoolCount)
        val old=(0 until m.visibleImageCount()).last{m.viewImageColumns.occupiedAt(it)}
        assertTrue(m.imageFractionAt(old)>.999)
        assertEquals(12_000,m.latest!!.pingId)
        val rawVersion=m.ring.version;val rate=m.measuredRate()
        m.setDisplay(m.display.copy(historySeconds=60.0))
        assertTrue("The approved 256-column display budget also covers the original 60-s dataset",m.imageHistoryAggregated)
        assertEquals(rawVersion,m.ring.version);assertEquals(rate,m.measuredRate())
        m.setDisplay(m.display.copy(historySeconds=10.0));assertTrue(m.viewImageColumns===m.ring)
        m.setDisplay(m.display.copy(historySeconds=600.0));assertEquals(600.0,m.imageAvailableSeconds(),0.0)
    }
    @Test fun mixedPhysicalRangesPeakBinOnlyObservedDepthAndKeepRawInputUnchanged() {
        val h=SonarTimeImageHistory()
        val short=SonarFrame(ShortArray(1024){if(it==512)4095 else 0},1,900_000)
        val unchanged=short.samples
        h.observe(short,geometry,geometry.metersPerBin,4095,SonarPingSource.OWN,1)
        val long=SonarGeometry(24.576,SonarTxReference.HOST_VERIFIED)
        h.observe(frame(2,900_050,0),long,long.metersPerBin,4095,SonarPingSource.OWN,1)
        assertEquals(2,h.contributionCountAt(0))
        assertEquals(long.metersPerBin!!,h.metersPerBinValueAt(0),0.0)
        assertEquals(4095,h.sampleAt(0,64))
        assertEquals(0,h.sampleAt(0,512))
        assertArrayEquals(unchanged,short.samples)
        assertNull("Aggregation must not expose a fictional ping ID",h.measuredPingIdAt(0))
    }
    @Test fun allSourcesUnknownsAndDiscontinuitiesNeverMergeIntoAnotherContext() {
        val h=SonarTimeImageHistory()
        fun add(id:Long,source:SonarPingSource?,segment:Long)=h.observe(frame(id,900_000+id*10,id.toInt()),geometry,geometry.metersPerBin,4095,source,segment)
        add(1,SonarPingSource.OWN,1);add(2,SonarPingSource.NATIVE,1);add(3,null,1);add(4,SonarPingSource.OWN,2)
        assertEquals(4,h.allocatedPoolCount)
        assertEquals(listOf(SonarPingSource.OWN,SonarPingSource.NATIVE,null,SonarPingSource.OWN),(0..3).map{h.pingSourceAt(it)})
        assertEquals(listOf(1L,1L,1L,2L),(0..3).map{h.imageSegmentAt(it)})
        add(5,null,2);assertEquals(1,h.omittedContributions)
        for(age in 0..3)assertEquals(1,h.contributionCountAt(age))
        h.observe(frame(6,902_000),geometry,geometry.metersPerBin,4095,SonarPingSource.OWN,3)
        assertTrue((4 until h.count-4).any{!h.occupiedAt(it)})
    }
    @Test fun freezeCopiesOnlyObservedPoolsAndEpochClearsAcquiredImageEvidence() {
        var now=1_000_000L;val m=SonarModel{now};m.connect("synthetic");m.acquisition(true)
        m.setAnalysis(geometry,SonarBottom(),null,false);m.setDisplay(m.display.copy(historySeconds=600.0))
        for(id in 1L..2050L){now+=50;m.frame(frame(id,now),pingSource=SonarPingSource.OWN)}
        m.setFreeze(true);val frozen=m.viewTimeImageHistory;val available=m.imageAvailableSeconds();val version=frozen.version
        now+=2000;m.markImageContinuityBoundary();m.frame(frame(2051,now,4095),pingSource=SonarPingSource.OWN)
        assertEquals(version,frozen.version);assertEquals(available,m.imageAvailableSeconds(),0.0)
        assertEquals(1,frozen.allocatedPoolCount)
        assertTrue(m.imageHistory.version>version)
        m.updateContext(SonarEpoch("synthetic-new",2,1),SonarPingSource.OWN)
        assertEquals(0,m.imageHistory.count);assertEquals(0,m.ring.count);assertFalse(m.display.freeze)
    }
    @Test fun returningToAnEarlierTxReferenceStillCreatesANewImageSegment() {
        var now=900_000L;val m=SonarModel{now};m.connect("synthetic");m.acquisition(true)
        fun add(id:Long,g:SonarGeometry) {
            now+=50;m.setAnalysis(g,SonarBottom(),null,false)
            m.frame(frame(id,now),pingSource=SonarPingSource.OWN)
        }
        add(1,geometry);add(2,SonarGeometry(null,SonarTxReference.UNVERIFIED));add(3,geometry)
        assertEquals(1,m.ring.count)
        val h=m.imageHistory
        assertEquals(listOf(1L,1L,1L),(0..2).map{h.contributionCountAt(it)})
        assertEquals(3,(0..2).map{h.imageSegmentAt(it)}.toSet().size)
    }
    @Test fun outOfMemoryIsExplicitAndDoesNotCreateAnAllocationRetryLoop() {
        var attempts=0
        val h=SonarTimeImageHistory{attempts++;throw OutOfMemoryError("synthetic allocation failure")}
        repeat(10){h.observe(frame(it.toLong(),900_000+it*50L),geometry,geometry.metersPerBin,4095,SonarPingSource.OWN,1)}
        assertTrue(h.unavailable);assertEquals(1,attempts);assertEquals(0,h.allocatedPoolCount)
    }
}
