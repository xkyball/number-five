package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

/** Accelerated synthetic acquisition clocks. This is display LOD, not a new rate or detector. */
class SonarImageWorkBudgetTest {
    private var now=1_200_000L
    private var id=0L
    private val geometry=SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED)
    private val amplitude=SonarAmplitude(4095,12,true)
    private fun model(window:Double=120.0)=SonarModel{now}.also {
        it.connect("SYNTHETIC IMAGE WORK BUDGET");it.acquisition(true)
        it.updateContext(SonarEpoch("synthetic-budget",1,1),SonarPingSource.ALL)
        it.setDisplay(it.display.copy(historySeconds=window))
    }
    private fun append(m:SonarModel,elapsed:Long=50,source:SonarPingSource?=SonarPingSource.OWN,maximum:Double=3.072) {
        now+=elapsed;id++
        m.setAnalysis(geometry.copy(displayedMaxDepthM=maximum),SonarBottom(),null,false,amplitude=amplitude)
        m.frame(SonarFrame(ShortArray(1024){((it+id)and 4095).toShort()},id,now),pingSource=source)
    }
    private fun occupied(m:SonarModel):Int=(0 until m.visibleImageCount()).count{m.viewImageColumns.occupiedAt(it)}

    @Test fun actualWorkSelectsTheExistingPeakImageAt257ColumnsForBothTenAndTwentyHz() {
        for(interval in listOf(50L,100L)) {
            id=0;now=1_200_000;val m=model()
            repeat(256){append(m,interval)}
            assertFalse(m.imageHistoryAggregated);assertEquals(256,m.ring.count)
            val first=m.ring.atAge(255)!!.samples;val oldHistoryVersion=m.imageHistory.version
            append(m,interval)
            assertTrue(m.imageHistoryAggregated);assertSame(m.imageHistory,m.viewImageColumns)
            assertEquals(257,m.ring.count);assertArrayEquals(first,m.ring.atAge(256)!!.samples)
            assertEquals(oldHistoryVersion+1,m.imageHistory.version)
            assertEquals(120.0,m.viewDisplay.historySeconds,0.0)
            assertEquals(256*interval/1000.0,m.imageAvailableSeconds(),0.0)
            assertTrue(occupied(m).toLong()*256<257L*1024)
            assertNull(m.viewImageColumns.measuredPingIdAt(0))
            assertEquals(SonarPingSource.ALL,m.measuredRate().selectedSource)
            assertNotNull(m.measuredRate().hz)
            // Read access never changes the chosen representation or evidence.
            val version=m.imageHistory.version
            repeat(1000){assertSame(m.imageHistory,m.viewImageColumns);assertTrue(m.imageHistoryAggregated)}
            assertEquals(version,m.imageHistory.version)
        }
    }
    @Test fun theOriginalSixtySecondDataRemainsCoveredWhileTenSecondsKeepsFullRawResolution() {
        val m=model(10.0)
        repeat(2401){append(m)}
        assertEquals(2048,m.ring.count);assertEquals(201,m.visibleCount())
        assertFalse(m.imageHistoryAggregated);assertSame(m.ring,m.viewImageColumns)
        val version=m.ring.version;val raw=m.latest!!.samples;val rate=m.measuredRate()
        m.setDisplay(m.display.copy(historySeconds=60.0))
        assertTrue(m.imageHistoryAggregated);assertEquals(1201,m.visibleCount())
        assertEquals(version,m.ring.version);assertArrayEquals(raw,m.latest!!.samples);assertEquals(rate,m.measuredRate())
        m.setDisplay(m.display.copy(historySeconds=10.0))
        assertFalse(m.imageHistoryAggregated);assertSame(m.ring,m.viewImageColumns)
        assertEquals(version,m.ring.version);assertEquals(rate,m.measuredRate())
    }
    @Test fun theBudgetChoiceLatchesOnlyForItsWindowAndEpochAndNeverFillsAnUnobservedGap() {
        val m=model(600.0)
        repeat(257){append(m,if(it%3==2)190 else 24,source=if(it%2==0)SonarPingSource.OWN else SonarPingSource.NATIVE,
            maximum=if(it%17==0)24.576 else 3.072)}
        assertTrue(m.imageHistoryAggregated);assertEquals(600.0,m.viewDisplay.historySeconds,0.0)
        val coverage=m.imageAvailableSeconds();assertTrue(coverage<30)
        m.markImageContinuityBoundary();append(m,601_000)
        assertTrue("A temporary lower visible count must not oscillate the same window",m.imageHistoryAggregated)
        assertEquals(1,occupied(m));assertEquals(600.0,m.viewDisplay.historySeconds,0.0)
        assertTrue("Missing time stays unoccupied",(0 until m.imageHistory.count).any{!m.imageHistory.occupiedAt(it)})
        m.setDisplay(m.display.copy(historySeconds=10.0))
        assertFalse(m.imageHistoryAggregated)
        m.updateContext(SonarEpoch("synthetic-budget",1,2),SonarPingSource.NATIVE)
        assertFalse(m.imageHistoryAggregated);assertEquals(0,m.ring.count);assertEquals(0,m.imageHistory.count)
        append(m,50,SonarPingSource.NATIVE)
        assertSame(m.ring,m.viewImageColumns);assertEquals(1,m.ring.count)
    }
    @Test fun freezePreservesItsExactRawOrPeakChoiceWhileNewLivePingsContinue() {
        val m=model()
        repeat(256){append(m)}
        m.setFreeze(true);val rawFrozen=m.viewImageColumns
        repeat(10){append(m)}
        assertSame(rawFrozen,m.viewImageColumns);assertFalse(m.imageHistoryAggregated);assertEquals(256,m.viewRing.count)
        assertEquals(266,m.ring.count)
        m.setFreeze(false);assertTrue(m.imageHistoryAggregated)
        m.setFreeze(true);val peakFrozen=m.viewImageColumns;val version=peakFrozen.version
        val newest=m.imageNewestTime();val coverage=m.imageAvailableSeconds()
        repeat(10){append(m,maximum=24.576)}
        assertSame(peakFrozen,m.viewImageColumns);assertTrue(m.imageHistoryAggregated)
        assertEquals(version,peakFrozen.version);assertEquals(newest,m.imageNewestTime());assertEquals(coverage,m.imageAvailableSeconds(),0.0)
        assertTrue(m.imageHistory.version>version)
        m.updateContext(SonarEpoch("synthetic-budget-new",2,1),SonarPingSource.OWN)
        assertFalse(m.display.freeze);assertFalse(m.imageHistoryAggregated);assertEquals(0,m.imageHistory.count)
    }
}
