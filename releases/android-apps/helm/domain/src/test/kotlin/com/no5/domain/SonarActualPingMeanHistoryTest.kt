package com.no5.domain

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

/** Synthetic display means only. No producer, TX, physical sonar or device claim. */
class SonarActualPingMeanHistoryTest {
    private val base=1_200_000L // Exact 300-ms boundary.
    private val geometry=SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED)
    private val amplitude=SonarAmplitude(4095,12,true)
    private fun lut(gain:Double=24.1418424066,gamma:Double=1.0,threshold:Double=0.0)=FloatArray(65536){
        (((it/4095.0*10.0.pow(gain/20)-threshold)/(1-threshold)).coerceIn(0.0,1.0).pow(1/gamma)).toFloat()
    }
    private fun push(h:SonarTimeImageHistory,id:Long,value:Int,time:Long=base+id*10,
                     g:SonarGeometry?=geometry,source:SonarPingSource?=SonarPingSource.OWN,
                     a:SonarAmplitude?=amplitude,raw:ShortArray=ShortArray(1024){value.toShort()}) {
        h.observe(SonarFrame(raw,id,time),g,g?.metersPerBin,4095,source,0,a)
    }
    private fun age(h:SonarTimeImageHistory,time:Long)=
        (0 until h.count).first{h.occupiedAt(it)&&h.firstTimestampAt(it)==time}

    @Test fun sevenActualPingsAreAveragedAfterTransferWhilePeakHistoryStaysPeak(){
        val h=SonarTimeImageHistory()
        for(i in 0L..6)push(h,i,if(i==3L)4095 else 0)
        val output=FloatArray(260){-9f};val index=age(h,base)
        assertTrue(h.copyMeanIntensityProfileAt(index,lut(),output,2))
        for(row in 0 until 256)assertEquals(1f/7,output[2+row],1e-7f)
        assertEquals(-9f,output[0],0f);assertEquals(-9f,output[259],0f)
        assertEquals(4095,h.sampleAt(index,500));assertEquals(7L,h.contributionCountAt(index))
    }

    @Test fun meanUsesEachActualIntensityNotMeanAdcAndCanBeRecomputedWithNewTransfer(){
        val h=SonarTimeImageHistory();val values=intArrayOf(20,300,2000)
        for(i in values.indices)push(h,i.toLong(),values[i])
        val output=FloatArray(256);val transfer=lut(6.0,2.0,.1)
        assertTrue(h.copyMeanIntensityProfileAt(age(h,base),transfer,output))
        val expected=values.map{transfer[it].toDouble()}.average().toFloat()
        assertEquals(expected,output[100],0f)
        assertNotEquals(transfer[values.average().toInt()],output[100])
        val neutral=lut(0.0);assertTrue(h.copyMeanIntensityProfileAt(age(h,base),neutral,output))
        assertEquals(values.map{neutral[it].toDouble()}.average().toFloat(),output[100],0f)
    }

    @Test fun geometryUsesPhysicalDepthAndMissingRealCoverageIsNanRatherThanZero(){
        val h=SonarTimeImageHistory();val long=SonarGeometry(6.144,SonarTxReference.HOST_VERIFIED)
        val shortRaw=ShortArray(1024).also{it[400]=4095.toShort()}
        val longRaw=ShortArray(1024).also{it[200]=4095.toShort();it[800]=4095.toShort()}
        push(h,0,0,raw=shortRaw);push(h,1,0,g=long,raw=longRaw)
        val output=FloatArray(256)
        assertTrue(h.copyMeanIntensityProfileAt(age(h,base),lut(),output))
        assertEquals(1f,output[50],1e-6f)
        assertTrue(output[128].isNaN());assertTrue(output[200].isNaN())
        assertEquals(4095,h.sampleAt(age(h,base),800))
    }

    @Test fun sixteenRealReferencesAreSupportedButSeventeenthInvalidatesMeanOnly(){
        val h=SonarTimeImageHistory();val output=FloatArray(256)
        for(i in 0L..15)push(h,i,if(i==0L)4095 else 0)
        val index=age(h,base)
        assertTrue(h.copyMeanIntensityProfileAt(index,lut(),output));assertEquals(1f/16,output[90],0f)
        val old=h.meanIntensityContentVersionAt(index)
        push(h,16,0)
        assertFalse(h.copyMeanIntensityProfileAt(index,lut(),output))
        assertTrue(h.meanIntensityContentVersionAt(index)>old)
        assertEquals(1L,h.meanOverflowColumns);assertEquals(17L,h.contributionCountAt(index))
        assertEquals(4095,h.sampleAt(index,500));assertFalse(h.unavailable)
    }

    @Test fun globalOverwriteInvalidatesOldMeanRevisionButFrozenCopyKeepsItsOwnProof(){
        val h=SonarTimeImageHistory();push(h,0,4095)
        val originalPeakVersion=h.contentVersionAt(age(h,base))
        val originalMeanVersion=h.meanIntensityContentVersionAt(age(h,base))
        val held=h.copy();val transfer=lut();val output=FloatArray(256)
        for(i in 0 until SonarTimeImageHistory.MEAN_PROFILE_CAPACITY){
            val time=base+300+(i/16)*300L+(i%16)*10L
            push(h,i+1L,0,time=time)
        }
        val index=age(h,base)
        assertEquals(originalPeakVersion,h.contentVersionAt(index))
        assertTrue(h.meanIntensityContentVersionAt(index)>originalMeanVersion)
        assertTrue(h.meanOverwrittenColumns>=1)
        assertFalse(h.copyMeanIntensityProfileAt(index,transfer,output))
        assertTrue(held.copyMeanIntensityProfileAt(age(held,base),transfer,output))
        assertEquals(1f,output[128],0f);assertEquals(originalMeanVersion,held.meanIntensityContentVersionAt(age(held,base)))
    }

    @Test fun clearInvalidatesReferencesAndDoesNotResurrectAnOldSerial(){
        val h=SonarTimeImageHistory();push(h,0,4095)
        val old=h.meanIntensityContentVersionAt(age(h,base));val output=FloatArray(256){-7f}
        h.clear();assertFalse(h.copyMeanIntensityProfileAt(0,lut(),output))
        assertTrue(output.all{it==-7f})
        push(h,0,0)
        assertTrue(h.meanIntensityContentVersionAt(age(h,base))>old)
        assertTrue(h.copyMeanIntensityProfileAt(age(h,base),lut(),output));assertTrue(output.all{it==0f})
    }

    @Test fun knownOwnAndNativeContextsStaySeparateInSameTimeBucket(){
        val h=SonarTimeImageHistory();push(h,0,4095);push(h,1,0,source=SonarPingSource.NATIVE)
        val output=FloatArray(256);val transfer=lut()
        val own=(0 until h.count).first{h.occupiedAt(it)&&h.pingSourceAt(it)==SonarPingSource.OWN}
        val native=(0 until h.count).first{h.occupiedAt(it)&&h.pingSourceAt(it)==SonarPingSource.NATIVE}
        assertTrue(h.copyMeanIntensityProfileAt(own,transfer,output));assertEquals(1f,output[100],0f)
        assertTrue(h.copyMeanIntensityProfileAt(native,transfer,output));assertEquals(0f,output[100],0f)
    }

    @Test fun unknownSourceOrUnconfirmedAmplitudeNeverBecomesMeanEvidence(){
        for(source in listOf(null,SonarPingSource.ALL,SonarPingSource.OWN)){
            val h=SonarTimeImageHistory()
            push(h,0,4095,source=source,a=if(source==SonarPingSource.OWN)SonarAmplitude(null,null,false)else amplitude)
            val output=FloatArray(256){-3f}
            assertFalse(h.copyMeanIntensityProfileAt(age(h,base),lut(),output))
            assertTrue(output.all{it==-3f});assertEquals(4095,h.sampleAt(age(h,base),500))
        }
    }

    @Test fun unavailableMeanStillInvalidatesWhenItsHonestPeakFallbackChanges(){
        val h=SonarTimeImageHistory();val unconfirmed=SonarAmplitude(null,null,false)
        push(h,0,0,a=unconfirmed)
        val index=age(h,base);val previous=h.meanIntensityContentVersionAt(index)
        assertFalse(h.copyMeanIntensityProfileAt(index,lut(),FloatArray(256)))
        assertEquals(0,h.sampleAt(index,500))
        push(h,1,4095,a=unconfirmed)
        assertTrue(h.meanIntensityContentVersionAt(index)>previous)
        assertFalse(h.copyMeanIntensityProfileAt(index,lut(),FloatArray(256)))
        assertEquals(4095,h.sampleAt(index,500))
    }

    @Test fun legacyExplicitScaleAndUnknownMetricGeometryRetainBinBasedMean(){
        val h=SonarTimeImageHistory();push(h,0,4095,g=null,a=null);push(h,1,0,g=null,a=null)
        val output=FloatArray(256)
        assertTrue(h.copyMeanIntensityProfileAt(age(h,base),lut(),output));assertEquals(.5f,output[100],0f)
        assertTrue(h.metersPerBinValueAt(age(h,base)).isNaN())
    }

    @Test fun meanPoolIsLazyAndOomDoesNotDisableOrFabricatePeakHistory(){
        var allocations=0
        val h=SonarTimeImageHistory({ShortArray(SonarTimeImageHistory.TIME_BINS*256)},
            {allocations++;throw OutOfMemoryError("synthetic bounded mean-pool failure")})
        assertEquals(0,allocations);push(h,0,4095)
        assertEquals(1,allocations);assertTrue(h.meanProfileStorageUnavailable);assertFalse(h.unavailable)
        assertFalse(h.copyMeanIntensityProfileAt(age(h,base),lut(),FloatArray(256)))
        assertEquals(4095,h.sampleAt(age(h,base),500))
        push(h,1,4095,time=base+300);assertEquals(1,allocations)
        h.clear();push(h,2,4095,time=base+600);assertEquals(2,allocations)
    }

    @Test fun missingSlotsAndDefaultUnsupportedImplementationDoNotWriteOutput(){
        val h=SonarTimeImageHistory();push(h,0,4095);push(h,1,0,time=base+600)
        val output=FloatArray(256){-2f};val transfer=lut()
        val missing=(0 until h.count).first{!h.occupiedAt(it)}
        assertFalse(h.copyMeanIntensityProfileAt(missing,transfer,output));assertTrue(output.all{it==-2f})
        assertFalse(SonarRingBuffer().copyMeanIntensityProfileAt(0,transfer,output))
        assertTrue(output.all{it==-2f})
        try{h.copyMeanIntensityProfileAt(0,FloatArray(2),output);fail("LUT bounds must be explicit")}catch(_:IllegalArgumentException){}
        try{h.copyMeanIntensityProfileAt(0,transfer,output,1);fail("Output bounds must be explicit")}catch(_:IllegalArgumentException){}
    }
}
