package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.no5.domain.*
import com.no5.helm.sonar.gl.SonarReliefFeed
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native Skia paint evidence; not physical touch/device/FPS acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarNativeRenderTest {
    @Test fun alignedFrozenScopeUsesHeldPingAndRangeWhileAcquisitionContinues() {
        var now=1_000_000L
        val source=DeterministicMockSonarSource(now={now})
        val c=SonarNativeController(source,now={now})
        try {
            source.loadFixture(SonarFixture.CALIBRATED,SonarAcquisitionRequest(source.identity.sessionId,100))
            c.relief(true);c.alignReliefRange();c.crop(180.0,800.0);c.freeze()
            val held=c.model.viewRing.atAge(0)!!.pingId
            fun scope():IntArray {
                val bitmap=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
                SonarNativeRenderer(RuntimeEnvironment.getApplication()).draw(Canvas(bitmap),c,false)
                val pixels=IntArray(256*686)
                bitmap.getPixels(pixels,0,256,1744,174,256,686);bitmap.recycle()
                return pixels
            }
            val before=scope()
            repeat(30){now+=100;c.tick()}
            assertTrue(c.model.latest!!.pingId>held)
            assertEquals(held,c.model.viewRing.atAge(0)!!.pingId)
            assertArrayEquals("Frozen aligned A-scope must show the held ping",before,scope())
            c.reliefCamera(.3f,0f)
            assertArrayEquals("Freeze keeps the same scope even when relief camera moves",before,scope())
            c.display{it.copy(rangeMode=SonarRangeMode.MANUAL,overlayBottom=false,overlayTargets=false)}
            c.freeze()
            assertFalse(c.snapshot.display.freeze)
            now+=100;c.tick() // Frozen RAW was not duplicated; resume adopts the next received ping.
            assertEquals("Unfreezing adopts the real latest profile even if its samples are identical",c.model.latest!!.pingId,c.model.viewRing.atAge(0)!!.pingId)
        }finally{c.dispose()}
    }

    @Test fun reportedFaultRemainsAccessibleWithoutCoveringTheMeasuredViewport() {
        val time=1_000_000L
        val raw=DeterministicMockSonarSource(now={time})
        val reason=SonarObservationReason("ORIGINAL_REFERENCE_LOST","native_reference_lost",null,time,true,true)
        val statuses=MutableSourceFeed(raw.status.value.copy(observationReason=reason))
        val subscription=raw.status.subscribe {statuses.emit(it.copy(observationReason=reason))}
        val source=object:SonarSourcePort by raw {override val status:SourceFeed<SonarSourceStatus> get()=statuses}
        val c=SonarNativeController(source){time}
        try {
            raw.loadFixture(SonarFixture.CALIBRATED,SonarAcquisitionRequest(raw.identity.sessionId,100))
            fun reasonPixels():IntArray {
                val image=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
                SonarNativeRenderer(RuntimeEnvironment.getApplication()).draw(Canvas(image),c,false)
                val pixels=IntArray(1500*50);image.getPixels(pixels,0,1500,100,178,1500,50);image.recycle();return pixels
            }
            val live=reasonPixels()
            statuses.emit(statuses.value.copy(observationReason=null))
            assertArrayEquals("Changing host fault must not cover the measured plot",live,reasonPixels())
            assertNull(c.observationReasonLabel())
            statuses.emit(statuses.value.copy(observationReason=reason));assertNotNull(c.observationReasonLabel());assertEquals(SonarStatusLevel.WARNING,c.uiStatus.symbolLevel);c.freeze()
            assertArrayEquals("Freeze must not paint a banner over measured pings",live,reasonPixels())
        } finally {c.dispose();subscription.close()}
    }

    @Test fun knobModeSwitchPaintsActiveAssignmentWithoutChangingAcquisitionImage() {
        val now=1_000_000L
        val source=DeterministicMockSonarSource(now={now})
        val controller=SonarNativeController(source,now={now})
        try {
            source.loadFixture(SonarFixture.CALIBRATED,SonarAcquisitionRequest(source.identity.sessionId,100))
            for(night in listOf(false,true)) {
                if(controller.knobMode!=SonarKnobMode.SONAR)controller.toggleKnobMode()
                fun render():Bitmap=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888).also {
                    Canvas(it).apply {drawColor(Color.MAGENTA);SonarNativeRenderer(RuntimeEnvironment.getApplication()).draw(this,controller,night)}
                }
                val sonar=render();controller.toggleKnobMode();val audio=render()
                val accent=if(night)0xffff1a0a.toInt() else 0xff005daf.toInt()
                assertEquals(accent,sonar.getPixel(1418,1023));assertNotEquals(accent,audio.getPixel(1418,1023))
                assertEquals(accent,audio.getPixel(1572,1023));assertNotEquals(accent,sonar.getPixel(1572,1023))
                val before=IntArray(2000*730);val after=IntArray(before.size)
                sonar.getPixels(before,0,2000,0,130,2000,730);audio.getPixels(after,0,2000,0,130,2000,730)
                assertArrayEquals("Changing input ownership must preserve the measured echogram",before,after)
                for(image in listOf(sonar,audio)) {
                    assertEquals("Header remains untouched",Color.MAGENTA,image.getPixel(1000,80))
                    assertEquals("Footer remains untouched",Color.MAGENTA,image.getPixel(1000,1100));image.recycle()
                }
            }
        } finally {controller.dispose()}
    }

    @Test fun renderCompactFasciaDayNightAndCollapsedScope() {
        val context=RuntimeEnvironment.getApplication()
        val now=1_000_000L
        val source=DeterministicMockSonarSource(now={now})
        val controller=SonarNativeController(source,now={now})
        try {
            source.loadFixture(SonarFixture.CALIBRATED,SonarAcquisitionRequest(source.identity.sessionId,100))
            controller.refresh()
            assertEquals(686f,SonarLayout.HEIGHT,0f)
            assertTrue(SonarLayout.plotWidth(false)>SonarLayout.plotWidth(true))
            for(night in listOf(false,true))for(scope in listOf(false,true)){
                controller.display{it.copy(aScope=scope)}
                val bitmap=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(bitmap)
                val marker=Color.rgb(35,47,58);canvas.drawColor(marker)
                SonarNativeRenderer(context).draw(canvas,controller,night)
                assertEquals("Header remains outside SONAR ownership",marker,bitmap.getPixel(1000,80))
                assertEquals("Navigation remains outside SONAR ownership",marker,bitmap.getPixel(1000,1100))
                assertNotEquals(marker,bitmap.getPixel(1000,500))
                val file=File(System.getProperty("no5.renderOutputDir","verification/renders"),"sonar-${if(night)"night" else "day"}-${if(scope)"scope" else "wide"}.png")
                file.parentFile.mkdirs();file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                if(scope){
                    val state=HelmState(speed=10.0,depth=7.6,heading=36.0,quality=TelemetryQuality(TelemetryStatus.LIVE,source="TEST",demo=true))
                    HelmScene(context,night).drawChrome(canvas,"SONAR",renderValues(state,now),controller.statusText)
                    val full=File(file.parentFile,"sonar-${if(night)"night" else "day"}-full.png")
                    full.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                }
                bitmap.recycle()
            }
            // Export one common raw source for actual GLES + native A-scope comparison.
            controller.relief(true);controller.alignReliefRange();controller.display{it.copy(aScope=true)}
            val export=File(System.getProperty("no5.renderOutputDir","verification/renders"),"sonar-gl").apply{mkdirs()}
            val data=SonarReliefFeed().snapshot(controller.model.viewRing,controller.snapshot.display.historySeconds)
            val buffer=ByteBuffer.allocate(16+2048*1024*2+2048*2*4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(data.head);buffer.putInt(data.count);buffer.putFloat(data.seconds);buffer.putInt(controller.snapshot.display.palette.ordinal)
            for(slot in 0 until 2048)for(bin in 0..1023){val age=(data.head-1-slot+data.source.capacity)%data.source.capacity;buffer.putShort(if(age<data.count)data.source.sampleAt(age,bin).toShort()else 0)}
            for(slot in 0 until 2048){val age=(data.head-1-slot+data.source.capacity)%data.source.capacity
                buffer.putFloat(if(age<data.count)((data.source.timestampAt(age)-data.source.timestampAt(0))/1000.0).toFloat()else 0f)
                buffer.putFloat(if(age<data.count)data.source.imageSegmentAt(age).toFloat()else 0f)
            }
            File(export,"matched-source.bin").writeBytes(buffer.array())
            for(night in listOf(false,true)){
                val matched=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);val canvas=Canvas(matched)
                SonarNativeRenderer(context).draw(canvas,controller,night)
                val state=HelmState(speed=10.0,depth=7.6,heading=36.0,quality=TelemetryQuality(TelemetryStatus.LIVE,source="TEST",demo=true))
                HelmScene(context,night).drawChrome(canvas,"SONAR",renderValues(state,now),controller.statusText)
                File(export,"matched-native-${if(night)"night" else "day"}.png").outputStream().use{matched.compress(Bitmap.CompressFormat.PNG,100,it)}
                matched.recycle()
            }
            controller.freeze()
            controller.model.setCalibration(Calibration(false))
            controller.refresh()
            assertNotNull(controller.model.viewMetersPerBin)
            val bitmap=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
            SonarNativeRenderer(context).draw(Canvas(bitmap),controller,false)
            val file=File(System.getProperty("no5.renderOutputDir","verification/renders"),"sonar-day-frozen-invalid-live.png")
            file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }finally{controller.dispose()}
    }
}
