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

/** Actual synthetic sample columns only; no inference about real returns or display FPS. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarExactPingProfilesRenderTest {
    private var now=1_000_000L
    private var id=0L
    private fun controller(window:Double=10.0)=SonarNativeController(DeterministicMockSonarSource(now={now})){now}.also {
        it.model.connect("SYNTHETIC EXACT COLUMNS");it.model.acquisition(true)
        it.model.updateContext(SonarEpoch("synthetic-exact-columns",1,1),SonarPingSource.OWN)
        it.model.setDisplay(it.model.display.copy(historySeconds=window,palette=SonarPalette.MONO_AMBER))
    }
    private fun append(c:SonarNativeController,time:Long,value:Int=4095,geometry:SonarGeometry=SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),amplitude:SonarAmplitude=SonarAmplitude(4095,12,true)) {
        now=time;id++
        c.model.setAnalysis(geometry,SonarBottom(),null,false,amplitude=amplitude)
        c.model.frame(SonarFrame(ShortArray(1024){value.toShort()},id,now),pingSource=SonarPingSource.OWN);c.refresh()
    }
    private fun render(c:SonarNativeController,r:SonarNativeRenderer)=Bitmap.createBitmap(1000,512,Bitmap.Config.ARGB_8888).also {
        r.drawPreview(Canvas(it),c,false,RectF(0f,0f,1000f,512f))
    }
    private fun pixels(b:Bitmap)=IntArray(b.width*b.height).also{b.getPixels(it,0,b.width,0,0,b.width,b.height)}
    private fun save(b:Bitmap,name:String){val f=File(System.getProperty("no5.renderOutputDir"),"exact-pings/$name.png");f.parentFile!!.mkdirs();f.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
    private fun paintedX(b:Bitmap)=(0 until b.width).filter{b.getPixel(it,200)!=0xff061323.toInt()}

    @Test fun exactModeKeepsOneColumnPerActualTimeWithoutNearestHoldOrLinearHelpersEvenAcrossStaleAndGaps() {
        val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        try {
            append(c,1_000_000);append(c,1_000_100);c.model.markImageContinuityBoundary();append(c,1_002_000)
            val nearest=render(c,r);assertTrue("The old nearest path really holds columns wider than one pixel",paintedX(nearest).size>3)
            c.interpolation(true);c.exactPingProfiles(true)
            val raw=c.model.latest!!.samples;val rate=c.model.measuredRate();val revision=c.model.ring.version
            val exact=render(c,r);save(exact,"three-original-columns-no-hold-gap")
            assertEquals(listOf(797,807,997),paintedX(exact))
            assertEquals(0,r.preparedTemporalPairs);assertEquals(0,r.preparedAggregatePairs)
            c.interpolation(false);val noLinear=render(c,r);assertArrayEquals(pixels(exact),pixels(noLinear))
            c.interpolation(true);c.model.setSourceStale(true);c.refresh()
            val stale=render(c,r);assertArrayEquals(pixels(exact),pixels(stale))
            c.freeze();val frozen=render(c,r)
            append(c,1_002_100,value=0);val continued=render(c,r);assertArrayEquals(pixels(frozen),pixels(continued))
            assertEquals(revision+1,c.model.ring.version);assertArrayEquals(raw,c.model.viewRing.atAge(0)!!.samples)
            now+=500;c.refresh()
            assertEquals("Live event evidence continues while the image is frozen",rate.eventCount+1,c.model.measuredRate().eventCount)
            nearest.recycle();exact.recycle();noLinear.recycle();stale.recycle();frozen.recycle();continued.recycle()
        }finally{r.dispose();c.dispose()}
    }
    @Test fun proofForcesRawAfterBudgetAndRetentionWhileFrozenAggregateCannotBeSilentlyChanged() {
        val c=controller(120.0);val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        try {
            for(i in 0..300)append(c,1_000_000+i*50L)
            c.interpolation(true);assertTrue(c.model.imageHistoryAggregated)
            c.freeze();val peak=render(c,r)
            c.exactPingProfiles(true);assertFalse(c.model.display.exactPingProfiles)
            val ignored=render(c,r);assertArrayEquals(pixels(peak),pixels(ignored))
            c.freeze();c.exactPingProfiles(true)
            val actual=render(c,r);assertFalse(c.model.imageHistoryAggregated);assertSame(c.model.ring,c.model.viewImageColumns)
            assertEquals(0,r.preparedTemporalPairs);assertEquals(0,r.preparedAggregatePairs)
            append(c,1_015_100,geometry=SonarGeometry(null,SonarTxReference.UNVERIFIED),amplitude=SonarAmplitude(null,verified=false))
            assertFalse(c.model.imageHistoryAggregated);assertEquals(1,c.model.ring.count)
            val retainedRaw=render(c,r);assertEquals(1,paintedX(retainedRaw).size)
            val full=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);r.draw(Canvas(full),c,false);save(full,"raw-retention-fallback-disabled-with-explicit-label")
            c.exactPingProfiles(false);assertTrue(c.model.imageHistoryAggregated)
            peak.recycle();ignored.recycle();actual.recycle();retainedRaw.recycle();full.recycle()
        }finally{r.dispose();c.dispose()}
    }
}
