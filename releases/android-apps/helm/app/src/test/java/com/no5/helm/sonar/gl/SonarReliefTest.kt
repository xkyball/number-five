package com.no5.helm.sonar.gl

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SonarReliefTest {
    private fun push(ring:SonarRingBuffer,id:Long,time:Long=id*50,amplitude:Int=id.toInt() and 65535) =
        ring.push(SonarFrame.fromUnsigned(IntArray(1024){amplitude},id,time))
    @Test fun metadataRequestsShareTheOnlyRawRingAndFenceOverwrite(){
        val ring=SonarRingBuffer(12000);val feed=SonarReliefFeed()
        repeat(12000){push(ring,it.toLong())};val before=feed.snapshot(ring,600.0)
        assertSame(ring,before.source);assertEquals(12000,before.count)
        val raw=ring.atAge(0);assertSame(raw,before.source.atAge(0))
        push(ring,12000);val after=feed.snapshot(ring,600.0)
        assertNotEquals(before.revision,after.revision)
        assertEquals(12000,after.count)
        ring.clear();assertNotEquals(after.generation,feed.snapshot(ring,600.0).generation)
    }
    @Test fun timeWindowAndStoredMappingsUseTheCanonicalColumns(){
        val ring=SonarRingBuffer(2400);push(ring,0,1000000);push(ring,1,1000050);push(ring,5,1004000)
        val data=SonarReliefFeed().snapshot(ring,120.0)
        assertEquals(4f,data.seconds,0f);assertEquals(1,SonarReliefFeed().snapshot(ring,1.0).count)
        ring.rebaseImageMapping(SonarImageField.GAIN,3.0)
        assertEquals(3.0,data.source.imageMappingAt(0)!!.gainDb,0.0)
        assertNotEquals(data.mappingVersion,SonarReliefFeed().snapshot(ring,120.0).mappingVersion)
    }
    @Test fun exportBoundedProductionMeshForRealShaderPixelVerification() {
        val mesh=SonarReliefGeometry.mesh()
        assertEquals(513*257*2,mesh.vertices.size)
        assertEquals(512*256*6,mesh.indices.size)
        assertTrue(mesh.vertices.all{it.isFinite()})
        assertTrue(mesh.indices.all{it in 0 until mesh.vertices.size/2})
        for(i in mesh.indices.indices step 6){
            // Flat validity of both triangles must belong to the older column of this cell.
            assertEquals(mesh.indices[i+2]/257,mesh.indices[i+5]/257)
        }
        val output=File(System.getProperty("no5.renderOutputDir"),"sonar-gl").also{it.mkdirs()}
        val bytes=ByteBuffer.allocate(12+mesh.vertices.size*4+mesh.indices.size*4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(2).putInt(mesh.vertices.size).putInt(mesh.indices.size)
        mesh.vertices.forEach{bytes.putFloat(it)};mesh.indices.forEach{bytes.putInt(it)}
        File(output,"relief-mesh.bin").writeBytes(bytes.array())
    }
}
