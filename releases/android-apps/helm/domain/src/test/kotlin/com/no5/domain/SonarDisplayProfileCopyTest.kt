package com.no5.domain

import org.junit.Test
import kotlin.math.roundToInt
import kotlin.test.*

/** Native-row copies retain uint16 bit patterns and never fabricate a missing profile. */
class SonarDisplayProfileCopyTest {
    private val geometry=SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED)
    private val guard=(-12345).toShort()
    private fun raw(id:Int)=ShortArray(1024){((id*5431+it*67)and 65535).toShort()}
    private fun assertParity(columns:SonarImageColumns,age:Int) {
        val rows=if(columns.aggregated)256 else 1024;val offset=7
        val destination=ShortArray(rows+18){guard};val version=columns.version
        val expected=ShortArray(rows){row->columns.sampleAt(age,(row*1023.0/(rows-1)).roundToInt()).toShort()}
        columns.copyDisplayProfileAt(age,destination,offset)
        for(row in 0 until rows)assertEquals(expected[row],destination[offset+row],"row=$row age=$age")
        for(index in 0 until offset)assertEquals(guard,destination[index])
        for(index in offset+rows until destination.size)assertEquals(guard,destination[index])
        assertEquals(version,columns.version)
        destination[offset]=(destination[offset].toInt()xor 65535).toShort()
        assertEquals(expected[0].toInt()and 65535,columns.sampleAt(age,0),"Destination never aliases source")
    }
    @Test fun rawCopyPreservesAllUnsignedPatternsOffsetsAndMeasurementsAcrossRingWrap() {
        val ring=SonarRingBuffer(8)
        for(id in 0 until 21)ring.push(SonarFrame(raw(id),id.toLong(),900_000L+id*50),geometry=geometry)
        assertEquals(8,ring.count);assertEquals(5,ring.head)
        val version=ring.version
        for(age in 0 until ring.count){val original=ring.atAge(age)!!.samples;assertParity(ring,age);assertContentEquals(original,ring.atAge(age)!!.samples)}
        assertEquals(version,ring.version)
    }
    @Test fun aggregateCopyPreservesEachOfTheFourBackingPoolPlanesAndPeakRows() {
        val history=SonarTimeImageHistory()
        val sources=listOf(SonarPingSource.NATIVE,null,SonarPingSource.ALL,SonarPingSource.OWN)
        for(plane in 0..3)history.observe(SonarFrame(raw(plane+1),plane+1L,900_000L+plane*10),geometry,geometry.metersPerBin,65535,sources[plane],plane.toLong())
        assertEquals(4,history.allocatedPoolCount)
        for(age in 0..3){assertTrue(history.occupiedAt(age));assertEquals(age,history.storageSlotAt(age)/2048);assertParity(history,age)}
    }
    @Test fun aggregateTimeBucketWrapCopiesTheCurrentBucketRatherThanExpiredStorageContents() {
        val history=SonarTimeImageHistory()
        for(index in 0 until 2051)history.observe(SonarFrame(ShortArray(1024){index.toShort()},index.toLong(),900_000L+index*300),geometry,geometry.metersPerBin,4095,SonarPingSource.OWN,0)
        assertEquals(8192,history.count)
        for(age in listOf(0,4,8188)){assertTrue(history.occupiedAt(age));assertParity(history,age)}
        val first=ShortArray(256);history.copyDisplayProfileAt(0,first)
        val last=ShortArray(256);history.copyDisplayProfileAt(8188,last)
        assertTrue(first.all{it.toInt()and 65535==2050});assertTrue(last.all{it.toInt()and 65535==3})
    }
    private class FallbackColumns(override val aggregated:Boolean):SonarImageColumns {
        override val capacity=1;override val count=1;override val version=7L
        var reads=0
        fun value(bin:Int)=(bin*97+40001)and 65535
        override fun storageSlotAt(age:Int)=0
        override fun sampleAt(age:Int,bin:Int):Int {require(age==0&&bin in 0..1023);reads++;return value(bin)}
        override fun timestampAt(age:Int)=900_000L
        override fun contentVersionAt(age:Int)=7L
        override fun geometryAt(age:Int):SonarGeometry?=null
        override fun metersPerBinValueAt(age:Int)=Double.NaN
        override fun amplitudeFullScaleAt(age:Int)=65535
        override fun pingSourceAt(age:Int):SonarPingSource?=null
        override fun imageSegmentAt(age:Int)=0L
    }
    @Test fun genericDefaultCopiesExactlyNativeRowsWithNullMetadataWithoutInventingGeometry() {
        for(aggregate in listOf(false,true)){
            val columns=FallbackColumns(aggregate);val rows=if(aggregate)256 else 1024
            val destination=ShortArray(rows+8){guard};columns.copyDisplayProfileAt(0,destination,3)
            assertEquals(rows,columns.reads,"One sample access per native row in the default fallback")
            for(row in 0 until rows)assertEquals(columns.value((row*1023.0/(rows-1)).roundToInt()),destination[row+3].toInt()and 65535)
            assertTrue(destination.take(3).all{it==guard});assertTrue(destination.takeLast(5).all{it==guard})
            assertNull(columns.geometryAt(0));assertTrue(columns.metersPerBinValueAt(0).isNaN());assertNull(columns.pingSourceAt(0))
        }
    }
    @Test fun invalidAgesOffsetsAndDestinationSizesRejectBeforeAnyWriteOrFallbackSampleRead() {
        val ring=SonarRingBuffer(2);ring.push(SonarFrame(raw(1),1,900_000))
        val sources=listOf<SonarImageColumns>(ring,FallbackColumns(false),FallbackColumns(true))
        for(columns in sources){val rows=if(columns.aggregated)256 else 1024
            val cases=listOf(Triple(-1,0,rows),Triple(columns.count,0,rows),Triple(0,-1,rows),Triple(0,0,rows-1),Triple(0,1,rows),Triple(0,Int.MAX_VALUE,rows))
            for((age,offset,size)in cases){val destination=ShortArray(size){guard}
                assertFailsWith<IllegalArgumentException>{columns.copyDisplayProfileAt(age,destination,offset)}
                assertTrue(destination.all{it==guard})
            }
            if(columns is FallbackColumns)assertEquals(0,columns.reads)
        }
    }
    @Test fun missingTimeColumnsAndUnavailablePoolsRejectInsteadOfReturningFabricatedZeros() {
        val history=SonarTimeImageHistory()
        for(index in listOf(0,3))history.observe(SonarFrame(raw(index),index.toLong(),900_000L+index*300),geometry,geometry.metersPerBin,65535,SonarPingSource.OWN,0)
        for(age in listOf(1,4,8)){assertFalse(history.occupiedAt(age));val destination=ShortArray(256){guard}
            assertFailsWith<IllegalArgumentException>{history.copyDisplayProfileAt(age,destination)}
            assertTrue(destination.all{it==guard})
        }
        val unavailable=SonarTimeImageHistory{throw OutOfMemoryError("synthetic missing pool")}
        unavailable.observe(SonarFrame(raw(1),1,900_000),geometry,geometry.metersPerBin,65535,SonarPingSource.OWN,0)
        assertTrue(unavailable.unavailable);assertEquals(0,unavailable.allocatedPoolCount);assertFalse(unavailable.occupiedAt(0))
        val destination=ShortArray(256){guard}
        assertFailsWith<IllegalArgumentException>{unavailable.copyDisplayProfileAt(0,destination)}
        assertTrue(destination.all{it==guard})
    }
}
