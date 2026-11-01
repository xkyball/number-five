package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Accelerated synthetic measured-time fixtures, not a ten-minute hardware run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarLongImageHistoryRenderTest {
    private var now=1_000_000L
    private fun controller()=SonarNativeController(DeterministicMockSonarSource(now={now})){now}.also {
        it.model.connect("SYNTHETIC LONG IMAGE HISTORY");it.model.acquisition(true)
        it.model.updateContext(SonarEpoch("synthetic-long-image",1,1),SonarPingSource.OWN)
        it.model.setDisplay(it.model.display.copy(historySeconds=600.0,palette=SonarPalette.MONO_AMBER))
    }
    private fun append(c:SonarNativeController,id:Long,time:Long,maximum:Double=3.072,value:Int=4095) {
        now=time;c.model.setAnalysis(SonarGeometry(maximum,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true))
        c.model.frame(SonarFrame(ShortArray(1024){value.toShort()},id,time),pingSource=SonarPingSource.OWN)
    }
    private fun render(c:SonarNativeController,r:SonarNativeRenderer)=Bitmap.createBitmap(1000,512,Bitmap.Config.ARGB_8888).also {
        r.drawPreview(Canvas(it),c,false,RectF(0f,0f,1000f,512f))
    }
    private fun pixels(image:Bitmap)=IntArray(image.width*image.height).also{image.getPixels(it,0,image.width,0,0,image.width,image.height)}
    private fun save(image:Bitmap,name:String){val f=File(System.getProperty("no5.renderOutputDir"),"long-image/$name.png");f.parentFile.mkdirs();f.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun fullChosenSixHundredSecondsPaintAcrossWidthAndFreezeWhileLivePingsContinue() {
        val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        try {
            for(id in 0L..12_000L)append(c,id,1_000_000+id*50)
            c.refresh();val raw=c.model.latest!!.samples
            val image=render(c,r);save(image,"twenty-hz-six-hundred-seconds")
            val main=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
            r.draw(Canvas(main),c,false);save(main,"six-hundred-seconds-main-legends");main.recycle()
            val expected=SonarNativeMath.palette(1.0,SonarPalette.MONO_AMBER,false)
            for(x in 10..990 step 10)assertEquals("True historical image should reach x=$x",expected,image.getPixel(x,200))
            assertEquals(2048,c.model.ring.count);assertArrayEquals(raw,c.model.latest!!.samples)
            assertEquals(600.0,c.model.viewDisplay.historySeconds,0.0)
            c.inspect(.2f,.4f);val cursor=c.cursor!!
            assertNull(cursor.pingId);assertNotNull(cursor.firstTimestampEpochMs);assertTrue(cursor.aggregationCount>1)
            assertEquals(SonarPingSource.OWN,cursor.pingSource)
            c.model.setFreeze(true);c.refresh();val frozen=render(c,r)
            append(c,12_001,1_601_000,value=0);c.refresh()
            val held=render(c,r);assertArrayEquals(pixels(frozen),pixels(held))
            save(held,"frozen-six-hundred-seconds")
            image.recycle();frozen.recycle();held.recycle()
        } finally {r.dispose();c.dispose()}
    }
    @Test fun genuineOutageLeavesBlankTimeAndPixelBudgetKeepsOnlyTheSmallWindowOnTheRawBasis() {
        val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        try {
            var id=0L
            for(time in 0L..240_000L step 50)append(c,id++,1_000_000+time)
            c.model.markImageContinuityBoundary()
            for(time in 360_000L..600_000L step 50)append(c,id++,1_000_000+time)
            c.refresh();c.interpolation(true)
            val image=render(c,r);save(image,"real-two-minute-outage-remains-blank")
            val full=SonarNativeMath.palette(1.0,SonarPalette.MONO_AMBER,false)
            assertEquals(full,image.getPixel(200,200));assertEquals(full,image.getPixel(800,200))
            assertEquals(0xff061323.toInt(),image.getPixel(450,200))
            assertEquals(0xff061323.toInt(),image.getPixel(550,200))
            val rawVersion=c.model.ring.version;val rate=c.model.measuredRate()
            c.display{it.copy(historySeconds=60.0)}
            assertTrue("The explicit 256-column work budget also applies to the existing 60-s fixture",c.model.imageHistoryAggregated)
            assertEquals(rawVersion,c.model.ring.version);assertEquals(rate,c.model.measuredRate())
            c.display{it.copy(historySeconds=10.0)}
            assertFalse(c.model.imageHistoryAggregated);assertTrue(c.model.viewImageColumns===c.model.viewRing)
            image.recycle()
        } finally {r.dispose();c.dispose()}
    }
    @Test fun oldShortCapturesDoNotFillUnmeasuredDepthInTheLongImageHistory() {
        val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        try {
            for(id in 0L..12_000L)append(c,id,1_000_000+id*50,if(id<6000)3.072 else 24.576,if(id<6000)4095 else 0)
            c.refresh();val image=render(c,r);save(image,"old-short-coverage-on-current-long-axis")
            assertEquals(SonarNativeMath.palette(1.0,SonarPalette.MONO_AMBER,false),image.getPixel(200,40))
            assertEquals(0xff061323.toInt(),image.getPixel(200,400))
            image.recycle()
        } finally {r.dispose();c.dispose()}
    }
    @Test fun aShortUnconfirmedMetadataGapDoesNotShrinkTheRetainedTwoMinuteRasterAfterRecovery() {
        for(referenceUnknown in listOf(false,true)) {
            now=1_000_000L
            val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
            try {
                c.model.setDisplay(c.model.display.copy(historySeconds=120.0))
                for(id in 0L..2400L)append(c,id,1_000_000+id*50)
                now=1_120_050
                c.model.setAnalysis(if(referenceUnknown)SonarGeometry(null,SonarTxReference.UNVERIFIED)
                    else SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,
                    amplitude=SonarAmplitude(null,verified=false))
                c.model.frame(SonarFrame(ShortArray(1024),2401,now),pingSource=SonarPingSource.OWN)
                append(c,2402,1_120_100);c.refresh();c.interpolation(true)
                assertEquals(1,c.model.ring.count)
                assertTrue(c.model.imageHistoryAggregated)
                assertEquals(120.0,c.model.viewDisplay.historySeconds,0.0)
                val image=render(c,r)
                val full=SonarNativeMath.palette(1.0,SonarPalette.MONO_AMBER,false)
                for(x in 20..980 step 10)
                    assertEquals("Retained measured image must still cover x=$x after referenceUnknown=$referenceUnknown",full,image.getPixel(x,200))
                save(image,"two-minute-metadata-gap-reference-$referenceUnknown")
                c.model.setFreeze(true);c.refresh();val frozen=render(c,r)
                append(c,2403,1_121_000,value=0);c.refresh();val continued=render(c,r)
                assertArrayEquals(pixels(frozen),pixels(continued))
                image.recycle();frozen.recycle();continued.recycle()
            }finally{r.dispose();c.dispose()}
        }
    }
}
