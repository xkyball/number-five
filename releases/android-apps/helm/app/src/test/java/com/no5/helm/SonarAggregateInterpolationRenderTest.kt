package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Rect
import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.*

/** Synthetic virtual clocks. Display crossfade, never an inferred depth/analysis observation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarAggregateInterpolationRenderTest {
    private var now=1_000_000L
    private var id=0L
    private val base=1_002_300L // Exact 300-ms boundary after the intentionally dense raw-ring prefill.
    private val normal=SonarAmplitude(4095,12,true,displayTransform="max_pool_or_interpolate_without_amplitude_scaling",noiseRevision=1,noiseActive=false)
    private val reduced=SonarAmplitude(4095,12,true,displayTransform="background_reduction_then_max_pool_or_interpolate",noiseRevision=1,noiseActive=true)
    private fun controller()=SonarNativeController(DeterministicMockSonarSource(now={now})){now}.also {c->
        c.model.connect("SYNTHETIC AGGREGATE CROSSFADE");c.model.acquisition(true)
        c.model.updateContext(SonarEpoch("synthetic-aggregate",1,1),SonarPingSource.ALL)
        c.model.setDisplay(c.model.display.copy(historySeconds=10.0,palette=SonarPalette.MONO_AMBER))
        repeat(2048){append(c,1_000_000L+it,raw=ShortArray(1024))}
        assertTrue(c.model.imageHistoryAggregated)
    }
    private fun echo(center:Double)=ShortArray(1024){(4095*exp(-.5*((it-center)/48).pow(2))).roundToInt().toShort()}
    private fun append(c:SonarNativeController,time:Long,source:SonarPingSource?=SonarPingSource.OWN,maximum:Double=3.072,amp:SonarAmplitude=normal,raw:ShortArray=ShortArray(1024){4095}) {
        now=time;c.model.setAnalysis(SonarGeometry(maximum,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=amp)
        c.model.frame(SonarFrame(raw,++id,time),pingSource=source)
    }
    private fun render(c:SonarNativeController,r:SonarNativeRenderer)=Bitmap.createBitmap(1000,512,Bitmap.Config.ARGB_8888).also {r.drawPreview(Canvas(it),c,false,RectF(0f,0f,1000f,512f))}
    private fun x(c:SonarNativeController,time:Long)=997.5-(c.model.imageNewestTime()-time)/10.0
    private fun pixels(b:Bitmap)=IntArray(b.width*b.height).also{b.getPixels(it,0,b.width,0,0,b.width,b.height)}
    private fun save(b:Bitmap,name:String){val f=File(System.getProperty("no5.renderOutputDir"),"aggregate-linear/$name.png");f.parentFile!!.mkdirs();f.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun broadMovingEchoFillsIntermediateTimeCellsOnlyWithLinearAndKeepsRawFacts() {
        val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        try {
            append(c,base,raw=echo(340.0));append(c,base+360,raw=echo(370.0));c.refresh()
            val raw=c.model.latest!!.samples;val count=c.model.ring.count;val rate=c.model.measuredRate().eventCount;val depth=c.model.depth()
            val nearest=render(c,r);c.interpolation(true);val linear=render(c,r)
            save(nearest,"broad-moving-nearest");save(linear,"broad-moving-linear-crossfade")
            val first=ceil(x(c,base)+2).toInt();val last=floor(x(c,base+360)-2).toInt()
            for(xx in first..last) {
                assertEquals("Nearest has no invented inter-bin time cell",0xff061323.toInt(),nearest.getPixel(xx,178))
                assertTrue("Linear continuously mixes the two measured broad profiles at x=$xx",(linear.getPixel(xx,178) ushr 16 and 255)>180)
            }
            assertEquals("Adjacent equal-geometry image columns need no second atlas",0,r.aggregateTemporalPools)
            assertArrayEquals(raw,c.model.latest!!.samples);assertEquals(count,c.model.ring.count);assertEquals(rate,c.model.measuredRate().eventCount);assertEquals(depth,c.model.depth())
            nearest.recycle();linear.recycle()
        }finally{r.dispose();c.dispose()}
    }
    @Test fun irregularTwentyFourTwentyFourOneNinetyMillisecondPausesUseOriginalTimes() {
        val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        try {
            for(delta in listOf(240L,264L,288L,478L))append(c,base+delta)
            c.refresh();c.interpolation(true);val b=render(c,r);save(b,"irregular-24-24-190ms")
            for(xx in ceil(x(c,base+288)+2).toInt()..floor(x(c,base+478)-2).toInt())
                assertEquals(SonarNativeMath.palette(1.0,SonarPalette.MONO_AMBER,false),b.getPixel(xx,200))
            b.recycle()
        }finally{r.dispose();c.dispose()}
    }
    @Test fun realGapSourceBoundaryNoiseContextAndObservedSegmentRemainUnfilled() {
        for(kind in listOf("gap","source","noise","segment","unknown")) {
            val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
            try {
                append(c,base)
                if(kind=="segment")c.model.markImageContinuityBoundary()
                val delta=if(kind=="gap")900L else 360L
                append(c,base+delta,source=when(kind){"source"->SonarPingSource.NATIVE;"unknown"->null;else->SonarPingSource.OWN},amp=if(kind=="noise")reduced else normal)
                c.refresh();c.interpolation(true);val b=render(c,r)
                assertEquals("Never bridge $kind",0xff061323.toInt(),b.getPixel(((x(c,base)+x(c,base+delta))/2).roundToInt(),200))
                save(b,"boundary-$kind");b.recycle()
            }finally{r.dispose();c.dispose()}
        }
    }
    @Test fun mixedRangesUseOnlyMeasuredCoverageAndLazyCacheSurvivesFreezeAndMemoryLoss() {
        val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        try {
            append(c,base,maximum=3.072);append(c,base+360,maximum=24.576);c.refresh();c.interpolation(true)
            val first=render(c,r);val mid=((x(c,base)+x(c,base+360))/2).roundToInt()
            assertEquals(SonarNativeMath.palette(1.0,SonarPalette.MONO_AMBER,false),first.getPixel(mid,40))
            assertEquals(0xff061323.toInt(),first.getPixel(mid,400))
            assertEquals(1,r.aggregateTemporalPools)
            val prepared=r.preparedAggregatePairs;val warm=render(c,r);assertEquals(prepared,r.preparedAggregatePairs);assertArrayEquals(pixels(first),pixels(warm))
            c.model.setFreeze(true);c.refresh();val frozen=render(c,r)
            append(c,base+1000,amp=reduced,raw=ShortArray(1024));c.refresh();val continued=render(c,r)
            assertArrayEquals(pixels(frozen),pixels(continued));assertNull(c.model.viewImageColumns.measuredPingIdAt(0))
            save(frozen,"mixed-coverage-frozen-crossfade")
            r.releaseTemporalResources(false);assertEquals(0,r.aggregateTemporalPools)
            val limited=render(c,r);assertEquals(0,r.aggregateTemporalPools)
            assertEquals("Resource loss does not silently retry cache allocation",0xff061323.toInt(),limited.getPixel(mid,40))
            first.recycle();warm.recycle();frozen.recycle();continued.recycle();limited.recycle()
        }finally{r.dispose();c.dispose()}
    }
    @Test fun noiseContextChangesAreRejectedEvenWithAnUnchangedExplicitSegmentAndCopy() {
        val h=SonarTimeImageHistory();val g=SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED)
        h.observe(SonarFrame(ShortArray(1024),1,base),g,g.metersPerBin,4095,SonarPingSource.OWN,1,normal)
        h.observe(SonarFrame(ShortArray(1024),2,base+360),g,g.metersPerBin,4095,SonarPingSource.OWN,1,reduced)
        val copy=h.copy();val cache=SonarAggregateInterpolationCache()
        try{assertFalse(cache.compatible(h,0,4));assertFalse(cache.compatible(copy,0,4));assertEquals(0,cache.allocatedPools)}finally{cache.release()}
    }
    @Test fun pairAllocationFailureIsBoundedAndNeverRetriesUntilExplicitRelease() {
        val h=SonarTimeImageHistory();val a=SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED);val b=SonarGeometry(24.576,SonarTxReference.HOST_VERIFIED)
        h.observe(SonarFrame(ShortArray(1024),1,base),a,a.metersPerBin,4095,SonarPingSource.OWN,1,normal)
        h.observe(SonarFrame(ShortArray(1024),2,base+360),b,b.metersPerBin,4095,SonarPingSource.OWN,1,normal)
        var attempts=0;val cache=SonarAggregateInterpolationCache{attempts++;throw OutOfMemoryError("synthetic pair allocation")}
        val bitmap=Bitmap.createBitmap(2048,1024,Bitmap.Config.ARGB_8888)
        try {cache.begin(h,"synthetic");repeat(10){assertEquals(-1,cache.prepare(h,0,4,bitmap))};assertTrue(cache.unavailable);assertEquals(1,attempts);assertEquals(0,cache.allocatedPools)}finally{bitmap.recycle();cache.release()}
    }
    @Test fun smoothedMixedRangePairInterpolatesVerticalIntensityBeforePaletteWhileNonSmoothKeepsRgbBehavior() {
        val h=SonarTimeImageHistory();val display=SonarDisplay(palette=SonarPalette.SUN_HIGH_CONTRAST)
        val epoch=SonarEpoch("synthetic-aggregate-intensity-pair",1,1)
        for(index in 0..7){
            val maximum=if(index==4)6.144 else 3.072
            val g=SonarGeometry(maximum,SonarTxReference.HOST_VERIFIED)
            // Identical physical step. Native row50 of the long capture is dark, row51 bright.
            val threshold=if(index==4)204 else 408
            val raw=ShortArray(1024){if(it>=threshold)4095 else 0}
            h.observe(SonarFrame(raw,index+1L,900_000L+index*300),g,g.metersPerBin,4095,SonarPingSource.OWN,0,normal)
        }
        val newer=(0 until h.count).first{h.occupiedAt(it)&&h.firstTimestampAt(it)==901_200L}
        val older=(0 until h.count).first{h.occupiedAt(it)&&h.firstTimestampAt(it)==900_900L}
        val originalPeak=IntArray(1024){h.sampleAt(newer,it)};val version=h.version
        val smoothing=SonarDisplaySmoothingCache();val pair=SonarAggregateInterpolationCache()
        val atlas=Bitmap.createBitmap(2048,1024,Bitmap.Config.ARGB_8888)
        val colors=IntArray(65536);SonarTransferLut().fillIntensityColors(colors,display.palette,false)
        try {
            assertTrue(smoothing.begin(h,epoch,display))
            for(age in intArrayOf(newer,older)){
                smoothing.prepare(age)
                val column=IntArray(256){row->colors[smoothing.intensityCodeAt(age,(row*1023.0/255).roundToInt())]}
                val slot=h.storageSlotAt(age)
                atlas.setPixels(column,0,1,slot%2048,(slot/2048)*256,1,256)
            }
            val low=smoothing.intensityCodeAt(newer,(50*1023.0/255).roundToInt())
            val high=smoothing.intensityCodeAt(newer,(51*1023.0/255).roundToInt())
            assertEquals(0,low);assertEquals(65535,high)
            val expected=colors[((low+high)/2.0).roundToInt()]
            fun channel(shift:Int)=(((colors[low] ushr shift)and 255)+((colors[high] ushr shift)and 255)+1)/2
            val oldRgb=(255 shl 24)or(channel(16)shl 16)or(channel(8)shl 8)or channel(0)
            assertNotEquals("The selected palette distinguishes intensity interpolation from RGB mixing",oldRgb,expected)
            assertTrue(pair.begin(h,"actual-intensity"))
            val slot=pair.prepare(h,newer,older,atlas,smoothing,colors);assertTrue(slot>=0)
            val rect=Rect();pair.sourceRect(slot,rect)
            assertEquals("Row101 at the shorter spacing samples long row50.5 before palette",expected,pair.bitmap(slot).getPixel(rect.left+1,rect.top+101))
            val prepared=pair.preparedPairs;pair.prepare(h,newer,older,atlas,smoothing,colors)
            assertEquals(prepared,pair.preparedPairs)
            pair.begin(h,"existing-nonsmooth-rgb")
            val rawSlot=pair.prepare(h,newer,older,atlas)
            pair.sourceRect(rawSlot,rect)
            assertEquals("The non-smooth path retains its existing RGB interpolation exactly",oldRgb,pair.bitmap(rawSlot).getPixel(rect.left+1,rect.top+101))
            assertEquals(version,h.version);assertArrayEquals(originalPeak,IntArray(1024){h.sampleAt(newer,it)})
        }finally{atlas.recycle();pair.release();smoothing.release()}
    }
}
