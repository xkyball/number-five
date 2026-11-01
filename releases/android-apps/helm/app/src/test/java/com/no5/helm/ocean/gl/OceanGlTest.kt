package com.no5.helm.ocean.gl

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import com.no5.helm.renderValues
import com.no5.domain.HelmState
import com.no5.helm.ocean.BackgroundMode
import com.no5.helm.ocean.OceanFrame
import com.no5.helm.HelmScene
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33,35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OceanGlTest {
    private val output get()=File(System.getProperty("no5.renderOutputDir"),"ocean-gl").also { it.mkdirs() }
    @Test fun authoredMaskUsesLocalCoordinatesAtPhysicalResolution() {
        val scene=HelmScene(RuntimeEnvironment.getApplication(),false)
        for (scale in listOf(.5f,1f,1.5f)) {
            val mask=oceanMask(scene.oceanAperture(),(1200*scale).toInt(),(704*scale).toInt())
            fun alpha(x: Int,y: Int)=Color.alpha(mask.getPixel((x*scale).toInt(),(y*scale).toInt()))
            assertEquals(255,alpha(600,470)) // Transparent instrument opening reveals the scenery.
            assertEquals(0,alpha(10,470)) // Outside original curved aperture.
            assertEquals(255,alpha(600,700))
            assertTrue(alpha(40,30) in 90..130) // Authored 8% lateral fade.
            File(output,"mask-${mask.width}.png").outputStream().use { mask.compress(Bitmap.CompressFormat.PNG,100,it) }
            mask.recycle()
        }
    }
    @Test fun nativeHelmUnderlayAndForegroundPreserveTransparentInstrument() {
        val scene=HelmScene(RuntimeEnvironment.getApplication(),false)
        for(foreground in listOf(false,true)) {
            val bitmap=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
            if(foreground) {
                scene.drawForeground(Canvas(bitmap),renderValues(HelmState.unavailable()).copy(heading=0f,headingAvailable=true,demo=true,quality="LIVE"))
                assertEquals(Color.TRANSPARENT,bitmap.getPixel(1000,470))
            } else {
                scene.drawBackground(Canvas(bitmap),BackgroundMode.ANIMATION,false,OceanFrame(),externalOcean=true)
                assertEquals(Color.rgb(185,222,246),bitmap.getPixel(1000,470))
                assertEquals(Color.rgb(246,249,250),bitmap.getPixel(400,470))
            }
            File(output,if(foreground) "helm-foreground.png" else "helm-underlay.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
    }
    @Test fun exportFreshRotorAndCompassOverlaysForGlintReadability() {
        val now=System.currentTimeMillis()
        val state=HelmState(speed=27.6,depth=12.4,heading=0.0,
            quality=com.no5.domain.TelemetryQuality(com.no5.domain.TelemetryStatus.LIVE,now,"RENDER_TEST",simulated=true))
        val bitmap=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
        HelmScene(RuntimeEnvironment.getApplication(),false).drawForeground(Canvas(bitmap),renderValues(state,now),state)
        // Original outlined numerals are fully opaque; background remains transparent between them.
        var white=0;var dark=0
        for(y in 370 until 480)for(x in 860 until 1150) {
            val c=bitmap.getPixel(x,y)
            if(Color.alpha(c)==255 && Color.red(c)>240)white++
            if(Color.alpha(c)==255 && Color.red(c)<20)dark++
        }
        assertTrue(white>500);assertTrue(dark>200)
        File(output,"helm-foreground-readability.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun weatherComparisonUsesMatchingWestHeading() {
        val bitmap=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
        HelmScene(RuntimeEnvironment.getApplication(),false).drawForeground(Canvas(bitmap),
            renderValues(HelmState.unavailable()).copy(heading=270f,headingAvailable=true,demo=true,quality="LIVE"))
        File(output,"helm-foreground-270.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    @Test fun boundedMeshesHaveFiniteVerticesValidIndicesAndClosedSeams() {
        for(low in listOf(false,true)) for((name,mesh) in listOf("water" to OceanGlGeometry.water(low),"terrain" to OceanGlGeometry.terrain(low))) {
            assertTrue(mesh.vertices.all { it.isFinite() })
            assertTrue(mesh.indices.all { it in 0 until mesh.vertices.size/mesh.stride })
            assertTrue(mesh.vertices.size/mesh.stride<60000)
            if(mesh.stride==11) for(i in mesh.vertices.indices step 11) {
                assertEquals(1f,sqrt(mesh.vertices[i+3].pow(2)+mesh.vertices[i+4].pow(2)+mesh.vertices[i+5].pow(2)),.00001f)
                assertTrue(mesh.vertices[i+6] in .42f..1f)
                val radius=hypot(mesh.vertices[i],mesh.vertices[i+2])
                // Keep peaks below 7.5 degrees and away from the camera: open-water composition.
                assertTrue(mesh.vertices[i+1]/radius < .13f)
                if(radius<1171f || radius>8999f) assertTrue(mesh.vertices[i+1]<0f)
            }
            val columns=if(name=="water") { if(low) 128 else 256 } else { if(low) 256 else 512 }
            if(name=="water") for(start in mesh.vertices.indices step ((columns+1)*mesh.stride)) {
                for(j in 0 until mesh.stride) assertEquals(mesh.vertices[start+j],mesh.vertices[start+columns*mesh.stride+j],0f)
            }
            val bytes=ByteBuffer.allocate(12+mesh.vertices.size*4+mesh.indices.size*4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.putInt(mesh.stride).putInt(mesh.vertices.size).putInt(mesh.indices.size)
            mesh.vertices.forEach { bytes.putFloat(it) }; mesh.indices.forEach { bytes.putInt(it) }
            File(output,"$name-${if(low) "low" else "high"}.bin").writeBytes(bytes.array())
        }
    }
    @Test fun spectrumIsDeterministicFiniteBoundedAndHasNoDcOrNyquist() {
        val data=OceanSpectrum.initial()
        assertArrayEquals(data,OceanSpectrum.initial(),0f)
        assertTrue(data.all { it.isFinite() })
        assertEquals(128*128*4,data.size)
        var energy=0.0
        for(z in 0 until OceanSpectrum.SIZE) for(x in 0 until OceanSpectrum.SIZE) {
            val i=(z*OceanSpectrum.SIZE+x)*4
            energy+=data[i]*data[i]+data[i+1]*data[i+1]
            if((x==0 && z==0) || x==64 || z==64) assertEquals(0f,data[i]+data[i+1],0f)
            assertEquals(0f,data[i+2],0f); assertEquals(0f,data[i+3],0f)
        }
        assertEquals(.14,sqrt(2*energy),1e-7)
        val bytes=ByteBuffer.allocate(data.size*4).order(ByteOrder.LITTLE_ENDIAN)
        data.forEach { bytes.putFloat(it) }
        File(output,"spectrum.bin").writeBytes(bytes.array())
    }
    @Test fun landscapeIsDeterministicAndAsymmetric() {
        val mesh=OceanGlGeometry.terrain()
        val points=mesh.vertices.toList().chunked(mesh.stride)
        assertEquals(setOf(0f,1f,2f),points.map { it[9] }.toSet())
        assertEquals(7,points.map { it[10] }.toSet().size)
        assertTrue(points.any { it[1]>200 && it[4]<-.01f }) // Actual undercuts, not a heightfield.
        assertTrue(points.any { it[1]>400 && it[4]>.95f }) // Flat plateau tops.
        val occupied=points.filter { it[1]>0 }.map { ((atan2(it[0],it[2])*180/PI+360)%360).toInt()/5 }.toSet()
        assertTrue(occupied.size<60) // Open sea between distinct masses.
        assertEquals(OceanGlGeometry.terrain().vertices.toList(),OceanGlGeometry.terrain().vertices.toList())
    }
}
