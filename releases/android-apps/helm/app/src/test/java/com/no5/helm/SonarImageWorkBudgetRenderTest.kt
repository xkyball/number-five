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
import kotlin.math.abs

/** Synthetic work-count/native-image evidence only; actual device CPU walltime remains separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarImageWorkBudgetRenderTest {
    private var now=1_200_000L
    private var id=0L
    private fun controller()=SonarNativeController(DeterministicMockSonarSource(now={now})){now}.also {
        it.model.connect("SYNTHETIC EARLY IMAGE LOD");it.model.acquisition(true)
        it.model.updateContext(SonarEpoch("synthetic-work",1,1),SonarPingSource.OWN)
        it.model.setDisplay(it.model.display.copy(historySeconds=120.0,palette=SonarPalette.MONO_AMBER))
        it.model.setImageAuto(true);it.interpolation(true)
    }
    private fun append(c:SonarNativeController,interval:Long) {
        now+=interval;id++
        val geometry=SonarGeometry(if(id%31==0L)24.576 else 3.072,SonarTxReference.HOST_VERIFIED)
        val bin=(1.2+(id%11)*.01)/geometry.metersPerBin!!
        val bottom=SonarBottom(bin,.9,.8,SonarBottomSource.M1_RAW)
        c.model.setAnalysis(geometry,bottom,SonarPresentation(id,SonarAnalysisQuality.LOCKED,null,"auto","auto_raw",false,false,null,false),true,
            amplitude=SonarAmplitude(4095,12,true))
        val raw=ShortArray(1024){if(abs(it-bin)<12)(1000+(id%13)*100).toShort()else(50+(id%9)*10).toShort()}
        c.model.frame(SonarFrame(raw,id,now),pingSource=SonarPingSource.OWN);c.refresh()
    }
    private fun render(c:SonarNativeController,r:SonarNativeRenderer)=Bitmap.createBitmap(1000,512,Bitmap.Config.ARGB_8888).also {
        r.drawPreview(Canvas(it),c,false,RectF(0f,0f,1000f,512f))
    }
    private fun pixels(b:Bitmap)=IntArray(b.width*b.height).also{b.getPixels(it,0,b.width,0,0,b.width,b.height)}
    private fun save(b:Bitmap,name:String){val f=File(System.getProperty("no5.renderOutputDir"),"image-work-budget/$name.png");f.parentFile!!.mkdirs();f.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}

    @Test fun earlyPeakSelectionBoundsAutoTransferUploadsAndReleasesRawAnchoredPairsWithoutChangingMeasurements() {
        for(interval in listOf(50L,100L)) {
            now=1_200_000;id=0
            val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
            try {
                repeat(256){append(c,interval)}
                assertFalse(c.model.imageHistoryAggregated)
                val raw=render(c,r);save(raw,"${1000/interval}-hz-raw256")
                val rawBytes=r.mainUploadedBytes;val pairCount=r.preparedTemporalPairs
                assertEquals(256L*1024*4,rawBytes);assertTrue(pairCount>0)
                append(c,interval)
                val samples=c.model.latest!!.samples;val depth=c.model.depth();val rate=c.model.measuredRate()
                val peak=render(c,r);save(peak,"${1000/interval}-hz-peak257")
                val peakBytes=r.mainUploadedBytes
                assertTrue(c.model.imageHistoryAggregated);assertEquals(257,c.model.ring.count)
                assertEquals(pairCount,r.preparedTemporalPairs)
                assertTrue("Actual occupied peak uploads must be substantially smaller",r.mainUploadedBytes<rawBytes/8)
                assertArrayEquals(samples,c.model.latest!!.samples);assertEquals(depth,c.model.depth());assertEquals(rate,c.model.measuredRate())
                val display=c.model.display
                append(c,interval)
                assertNotEquals("Synthetic image Auto still changes its exact transfer",display,c.model.display)
                val changed=render(c,r)
                val changedBytes=r.mainUploadedBytes
                assertTrue(r.mainUploadedBytes<rawBytes/8);assertEquals(pairCount,r.preparedTemporalPairs)
                val main=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
                r.draw(Canvas(main),c,false);save(main,"${1000/interval}-hz-main-true-coverage-and-aggregation-label")
                println("SYNTHETIC_WORK intervalMs=$interval raw256Bytes=$rawBytes earlyPeak257Bytes=$peakBytes nextAutoTransferBytes=$changedBytes rawPairs=$pairCount frozenCoverageSeconds=${c.model.imageAvailableSeconds()}")
                c.freeze();val held=render(c,r)
                repeat(10){append(c,interval)}
                val continued=render(c,r);assertArrayEquals(pixels(held),pixels(continued))
                assertEquals(0,r.mainUploadedBytes)
                raw.recycle();peak.recycle();changed.recycle();main.recycle();held.recycle();continued.recycle()
            }finally{r.dispose();c.dispose()}
        }
    }

    @Test fun shortRawViewportUploadsOnlyItsNeededSamplesAndExpansionOrSameIdsInANewEpochAreFresh() {
        now=1_200_000;id=0
        val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        val backing=IntArray(2048*1024)
        try {
            c.display{it.copy(historySeconds=10.0)}
            repeat(2048){append(c,50)}
            assertFalse(c.model.imageHistoryAggregated);assertEquals(201,c.model.visibleCount())
            val initial=render(c,r)
            assertEquals(202,r.mainUploadedColumns);assertEquals(202L*1024*4,r.mainUploadedBytes)
            c.setGain(-6.0)
            val recolored=render(c,r)
            assertEquals(202,r.mainUploadedColumns);assertEquals(202L*1024*4,r.mainUploadedBytes)
            val oldSlot=c.model.ring.storageSlotAt(230)
            r.readUploadedPixels(backing)
            assertEquals("Outside-viewport slots have not been uploaded",0,backing[100*2048+oldSlot])
            c.display{it.copy(historySeconds=12.0)}
            assertFalse(c.model.imageHistoryAggregated);assertEquals(241,c.model.visibleCount())
            val expanded=render(c,r)
            assertEquals("Only the new 40 boundary/visible columns are required",40,r.mainUploadedColumns)
            r.readUploadedPixels(backing)
            for(age in 0..241)for(bin in listOf(0,100,511,1023)) {
                val raw=c.model.ring.sampleAt(age,bin)
                val expected=SonarNativeMath.palette(c.model.displayAmplitude(raw,4095,c.model.viewDisplay),c.model.viewDisplay.palette,false)
                assertEquals("Exact original transfer age=$age bin=$bin",expected,backing[bin*2048+c.model.ring.storageSlotAt(age)])
            }
            c.model.updateContext(SonarEpoch("synthetic-work-new",2,1),SonarPingSource.OWN)
            id=0;append(c,50)
            assertEquals(1L,c.model.ring.pingIdAt(0));assertEquals(1,c.model.ring.count)
            val newEpoch=render(c,r)
            assertEquals(1,r.mainUploadedColumns)
            r.readUploadedPixels(backing)
            val expected=SonarNativeMath.palette(c.model.displayAmplitude(c.model.ring.sampleAt(0,100),4095,c.model.viewDisplay),c.model.viewDisplay.palette,false)
            assertEquals(expected,backing[100*2048+c.model.ring.storageSlotAt(0)])
            val beforeFreeze=pixels(newEpoch)
            c.freeze();repeat(3){append(c,50)}
            val held=render(c,r)
            assertArrayEquals(beforeFreeze,pixels(held))
            println("SYNTHETIC_RAW_VIEWPORT retained=2048 visible10s=201 uploadedWithNeighbor=202 recolorBytes=827392 expanded12sVisible=241 newlyUploaded=40 newEpochSameIdUpload=1")
            initial.recycle();recolored.recycle();expanded.recycle();newEpoch.recycle();held.recycle()
        }finally{r.dispose();c.dispose()}
    }

    @Test fun earlyAggregateStripeOutOfMemoryFallbackKeepsExactPixelsAndRawEvidence() {
        val c=controller();val ordinary=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        val fallback=SonarNativeRenderer(RuntimeEnvironment.getApplication(),bulkUpload=SonarBulkUploadBuffer{throw OutOfMemoryError("synthetic stripe failure")})
        try {
            repeat(300){append(c,50)}
            assertTrue(c.model.imageHistoryAggregated)
            val samples=c.model.latest!!.samples;val depth=c.model.depth();val rate=c.model.measuredRate()
            val a=render(c,ordinary);val b=render(c,fallback)
            assertArrayEquals(pixels(a),pixels(b));assertEquals(0,fallback.uploadStripeBytes)
            assertArrayEquals(samples,c.model.latest!!.samples);assertEquals(depth,c.model.depth());assertEquals(rate,c.model.measuredRate())
            a.recycle();b.recycle()
        }finally{ordinary.dispose();fallback.dispose();c.dispose()}
    }
}
