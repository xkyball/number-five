package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

/** Exact bounded storage tests, not Android heap/device performance claims. */
class SonarImageStorageTest {
    @Test fun coldStoreAllocatesNoPayloadAndOnlyTouchedPagesGrow(){
        val short=SonarShortStorage(4194304)
        assertEquals(0L,short.allocatedBytes)
        short[13]=65535.toShort()
        assertEquals(32768L,short.allocatedBytes)
        assertEquals(65535,short[13].toInt()and 65535);assertEquals(0,short[17000].toInt())
        short[16384]=77;assertEquals(65536L,short.allocatedBytes)
        short.release();assertEquals(0L,short.allocatedBytes)
    }
    @Test fun crossPageCopiesKeepAllUnsignedBitsAndBounds(){
        val s=SonarShortStorage(40000);val values=ShortArray(33000){((it*313+65000)and 65535).toShort()}
        s.copyFrom(values,77);val result=ShortArray(33004){(-3).toShort()}
        s.copyInto(result,2,77,33077);assertArrayEquals(values,result.copyOfRange(2,33002))
        assertEquals(-3,result[0].toInt());assertEquals(-3,result.last().toInt())
        s.zero(16380,16390);for(i in 16380 until 16390)assertEquals(0,s[i].toInt())
        assertEquals(values[16379-77],s[16379]);assertEquals(values[16390-77],s[16390])
        try{s.copyFrom(values,8000);fail("outside logical bound")}catch(_:IllegalArgumentException){}
    }
    @Test fun snapshotsShareOnlyUntilEitherOwnerWritesAndReleaseIsIndependent(){
        val live=SonarShortStorage(40000);live[6]=12;live[20000]=34
        val held=live.copy()
        assertSame(live.page(6),held.page(6));assertSame(live.page(20000),held.page(20000))
        live[6]=56;assertNotSame(live.page(6),held.page(6));assertEquals(12,held[6].toInt())
        held[20000]=78;assertNotSame(live.page(20000),held.page(20000));assertEquals(34,live[20000].toInt())
        live.release();assertEquals(12,held[6].toInt());assertEquals(78,held[20000].toInt())
        held[39999]=91;assertEquals(91,held[39999].toInt())
    }
    @Test fun ringFreezeHasNoFullPayloadCopyButRingWrapCannotChangeFrozenSamples(){
        val live=SonarRingBuffer(32)
        assertEquals(0L,live.allocatedSampleBytes)
        for(i in 0L..19)live.push(SonarFrame(ShortArray(1024){i.toShort()},i,i+1000))
        val frozen=live.copy();val before=(0 until frozen.count).map{frozen.atAge(it)!!.samples}
        for(i in 20L..90)live.push(SonarFrame(ShortArray(1024){i.toShort()},i,i+1000))
        for(i in before.indices)assertArrayEquals(before[i],frozen.atAge(i)!!.samples)
        live.releaseStorage();assertEquals(0L,live.allocatedSampleBytes);assertEquals(0,live.count)
        assertEquals(20,frozen.count);for(i in before.indices)assertArrayEquals(before[i],frozen.atAge(i)!!.samples)
    }
    @Test fun modelDisposalReleasesLiveAndFrozenPayloadWhileFreshModelIsCold(){
        var now=1200000L;val m=SonarModel{now};m.connect("synthetic storage");m.acquisition(true)
        m.updateContext(SonarEpoch("memory",1,1),SonarPingSource.OWN)
        repeat(2400){i->now+=50;m.setAnalysis(SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,
            amplitude=SonarAmplitude(4095,12,true));m.frame(SonarFrame(ShortArray(1024){(i%4096).toShort()},i.toLong(),now),pingSource=SonarPingSource.OWN)}
        // At 20Hz/120s the original mean pool allocated8MiB. These 2400 actual profiles use <=1.25MiB,
        // and peak storage touches only the actual 300ms image slots, not all614.4s or four contexts.
        assertTrue(m.imageHistory.allocatedSampleBytes<2*1024*1024)
        m.setFreeze(true);val heldRing=m.viewRing;val heldImage=m.viewTimeImageHistory
        assertTrue(heldRing.allocatedSampleBytes>0);assertTrue(heldImage.allocatedSampleBytes>0)
        m.resetAcquisition()
        assertEquals(0L,m.ring.allocatedSampleBytes);assertEquals(0L,m.imageHistory.allocatedSampleBytes)
        assertEquals(0L,heldRing.allocatedSampleBytes);assertEquals(0L,heldImage.allocatedSampleBytes)
        assertEquals(0,heldRing.count);assertEquals(0,heldImage.count)
        val next=SonarModel();assertEquals(0L,next.ring.allocatedSampleBytes);assertEquals(0L,next.imageHistory.allocatedSampleBytes)
    }
}
