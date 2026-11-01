package com.no5.helm

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

/** Exact primitive-storage evidence; no Android heap or frame-time claim. */
class SonarSmoothingStorageTest {
    private val geometry=SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED)
    private val amplitude=SonarAmplitude(4095,12,true)
    private val epoch=SonarEpoch("storage-only",1,1)
    @Test fun twoMinuteWorkingCacheIsBoundedAndKeepsEveryActualMean(){
        val h=SonarTimeImageHistory();var id=0L
        repeat(400){bucket->repeat(6){ping->h.observe(SonarFrame(ShortArray(1024){if(ping==0)4095 else 0},id++,1200000L+bucket*300+ping*50),
            geometry,geometry.metersPerBin,4095,SonarPingSource.OWN,0,amplitude)}}
        val cache=SonarDisplaySmoothingCache()
        assertEquals(0L,cache.allocatedSampleBytes);assertTrue(cache.begin(h,epoch,SonarDisplay(gainDb=24.14184240658)))
        for(age in 0 until h.count)if(h.occupiedAt(age))cache.prepare(age)
        assertTrue("Working cache must stay bounded independently of the history address space",cache.allocatedSampleBytes<2*1024*1024)
        for(age in 12 until h.count-12 step 4)assertEquals(10923,cache.intensityCodeAt(age,500))
        cache.release();assertEquals(0L,cache.allocatedSampleBytes)
    }
    @Test fun rawOutputEvictionRecomputesTheExactTransferredSevenPingMean(){
        val ring=SonarRingBuffer();val display=SonarDisplay(gainDb=11.25,gamma=1.34,threshold=.083)
        for(id in 0 until ring.capacity)ring.push(SonarFrame(ShortArray(1024){((id*17+it*3)%4096).toShort()},id.toLong(),1200000+id*50L),
            geometry=geometry,amplitudeFullScale=4095,pingSource=SonarPingSource.OWN,amplitude=amplitude)
        var allocations=0
        val cache=SonarDisplaySmoothingCache{allocations++;ShortArray(SonarDisplaySmoothingCache.RAW_CACHE_COLUMNS*1024)}
        val lut=FloatArray(65536);SonarTransferLut().fillIntensities(lut,4095,display)
        assertTrue(cache.begin(ring,epoch,display))
        // Model the renderer: prepare the full upload first, then pack/read earlier columns.
        for(age in 0 until ring.count)cache.prepare(age)
        assertTrue(cache.outputEvictions>0)
        for(age in listOf(0,3,510,1023,1537,2044,2047))for(bin in 0..1023){
            val expected=if(age<3||age+3>=ring.count)lut[ring.sampleAt(age,bin)].toDouble()
                else (-3..3).sumOf{lut[ring.sampleAt(age+it,bin)].toDouble()}/7
            assertEquals((expected*65535).roundToInt(),cache.intensityCodeAt(age,bin))
        }
        assertEquals(1,allocations);assertTrue(cache.workingStorageBytes<2*1024*1024);assertFalse(cache.unavailable)
        cache.release()
    }
    @Test fun fullHistoryAndItsFrozenCopyRemainExactAcrossBothLruEvictions(){
        val live=SonarTimeImageHistory();var id=0L
        repeat(2048){bucket->repeat(6){ping->live.observe(SonarFrame(ShortArray(1024){if(ping==0)4095 else 0},id++,1200000L+bucket*300+ping*50),
            geometry,geometry.metersPerBin,4095,SonarPingSource.OWN,0,amplitude)}}
        val held=live.copy();val cache=SonarDisplaySmoothingCache()
        assertTrue(cache.begin(held,epoch,SonarDisplay(gainDb=24.14184240658)))
        for(age in 0 until held.count)if(held.occupiedAt(age))cache.prepare(age)
        assertTrue(cache.meanEvictions>0);assertTrue(cache.outputEvictions>0)
        val memory=cache.workingStorageBytes
        for(age in listOf(12,2048,4096,6144,held.count-16)){
            assertEquals(10923,cache.intensityCodeAt(age,500));assertTrue(cache.qualifiedAt(age))
        }
        live.releaseStorage();cache.release()
        assertTrue(cache.begin(held,epoch,SonarDisplay(gainDb=24.14184240658)))
        for(age in listOf(12,2048,4096,6144,held.count-16))assertEquals(10923,cache.intensityCodeAt(age,500))
        assertEquals(memory,cache.workingStorageBytes);assertTrue(memory<2*1024*1024)
        assertEquals(8192,held.count);assertFalse(cache.unavailable)
        held.releaseStorage();cache.release()
    }
    @Test fun rawToAggregateAndNewSourceKeepExactValuesWithoutRetainingOldWorkingPages(){
        val ring=SonarRingBuffer();for(id in 0L..6)ring.push(SonarFrame(ShortArray(1024){if(id==3L)4095 else 0},id,1200000+id*100),
            geometry=geometry,amplitudeFullScale=4095,pingSource=SonarPingSource.OWN,amplitude=amplitude)
        val cache=SonarDisplaySmoothingCache();assertTrue(cache.begin(ring,epoch));cache.prepare(3)
        assertEquals(9362,cache.intensityCodeAt(3,500));assertTrue(cache.workingStorageBytes<2*1024*1024)
        val h=SonarTimeImageHistory();h.observe(SonarFrame(ShortArray(1024){1234},0,1200000),geometry,geometry.metersPerBin,4095,SonarPingSource.OWN,0,amplitude)
        assertTrue(cache.begin(h,epoch.copy(generation=2)));cache.prepare(0)
        assertTrue(kotlin.math.abs((1234/4095.0*65535).toInt()-cache.intensityCodeAt(0,500))<=1)
        assertTrue(cache.workingStorageBytes<2*1024*1024)
        cache.release();assertEquals(0L,cache.allocatedSampleBytes)
    }
}
