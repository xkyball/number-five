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

/** Synthetic single-column reference versus64-column upload; source/time/physics are identical. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarBulkUploadParityTest {
    private var now=1_000_000L
    private var id=0L
    private val expected=IntArray(2048*1024)
    private val actual=IntArray(2048*1024)
    private fun controller(window:Double)=SonarNativeController(DeterministicMockSonarSource(now={now})){now}.also {
        it.model.connect("SYNTHETIC BULK PARITY");it.model.acquisition(true)
        it.model.updateContext(SonarEpoch("synthetic-bulk",1,1),SonarPingSource.ALL)
        it.model.setDisplay(it.model.display.copy(historySeconds=window,palette=SonarPalette.MONO_AMBER,gainDb=-6.0,gamma=.65,threshold=.1))
    }
    private fun append(c:SonarNativeController,time:Long=now+50,source:SonarPingSource?=SonarPingSource.OWN,maximum:Double=3.072,scale:Int=4095) {
        now=time;id++
        c.model.setAnalysis(SonarGeometry(maximum,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,amplitude=SonarAmplitude(scale,when(scale){255->8;4095->12;else->16},true))
        c.model.frame(SonarFrame(ShortArray(1024){((it*67+id*997)and 65535).toShort()},id,time),pingSource=source)
    }
    private fun render(r:SonarNativeRenderer,c:SonarNativeController)=Bitmap.createBitmap(1000,512,Bitmap.Config.ARGB_8888).also{r.drawPreview(Canvas(it),c,false,RectF(0f,0f,1000f,512f))}
    private fun save(b:Bitmap,name:String){val p=File(System.getProperty("no5.renderOutputDir"),"bulk-upload/$name.png");p.parentFile!!.mkdirs();p.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
    private fun compare(c:SonarNativeController,reference:SonarNativeRenderer,bulk:SonarNativeRenderer,name:String,save:Boolean=false) {
        c.refresh();val a=render(reference,c);val b=render(bulk,c)
        reference.readUploadedPixels(expected);bulk.readUploadedPixels(actual)
        assertArrayEquals("Every backing-bitmap ARGB pixel: $name",expected,actual)
        val ap=IntArray(1000*512);val bp=IntArray(ap.size)
        a.getPixels(ap,0,1000,0,0,1000,512);b.getPixels(bp,0,1000,0,0,1000,512)
        assertArrayEquals("Native rendered pixels: $name",ap,bp)
        if(save){save(a,"$name-reference");save(b,"$name-bulk")}
        a.recycle();b.recycle()
    }
    @Test fun rawStripeEdgesWrapPartialUpdatesGeometryAndFullscaleArePixelIdentical() {
        val c=controller(10.0);val reference=SonarNativeRenderer(RuntimeEnvironment.getApplication(),bulkUpload=SonarBulkUploadBuffer{throw OutOfMemoryError("synthetic single-column reference")});val bulk=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        try {
            assertEquals(262144,bulk.uploadStripeBytes)
            var appended=0
            for(count in listOf(63,64,65,2047,2048)) {
                while(appended<count){append(c,maximum=if(appended%17==0)24.576 else 3.072);appended++}
                c.display{it.copy(gainDb=it.gainDb+.1)} // Forces a complete recolor at each exact boundary.
                compare(c,reference,bulk,"raw-$count")
                val required=minOf(count,c.model.visibleCount()+1).toLong()
                assertEquals(required,bulk.mainUploadedColumns)
                assertEquals((required+63L)/64,bulk.mainUploadCalls)
                assertEquals(required*1024*4,bulk.mainUploadedBytes)
            }
            repeat(33){append(c)};compare(c,reference,bulk,"raw-wrapped-partial",true)
            assertEquals(33,bulk.mainUploadedColumns);assertEquals(1,bulk.mainUploadCalls)
            compare(c,reference,bulk,"raw-warm-no-upload")
            assertEquals(0,bulk.mainUploadCalls);assertEquals(0,bulk.mainUploadedBytes)
            for(scale in listOf(65535,255,4095)){append(c,scale=scale);compare(c,reference,bulk,"scale-$scale")}
        }finally{reference.dispose();bulk.dispose();c.dispose()}
    }
    @Test fun aggregateContextPlanesWrapAndFreezeRemainPixelIdentical() {
        val c=controller(600.0);val reference=SonarNativeRenderer(RuntimeEnvironment.getApplication(),bulkUpload=SonarBulkUploadBuffer{throw OutOfMemoryError("synthetic single-column reference")});val bulk=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        try {
            val start=1_000_200L // Exact300ms boundary; physical2048-column index wraps during this fixture.
            for(bucket in 0 until 800)for(context in 0..3) {
                append(c,start+bucket*300+context*24,source=when(context){0->SonarPingSource.OWN;1->SonarPingSource.NATIVE;else->null},maximum=if(bucket%5==0)24.576 else 3.072)
            }
            assertTrue(c.model.imageHistoryAggregated);assertEquals(4,c.model.imageHistory.allocatedPoolCount)
            c.interpolation(true);compare(c,reference,bulk,"aggregate-four-contexts-wrap",true)
            assertEquals(3200,bulk.mainUploadedColumns)
            assertTrue("Calls are reduced without changing context planes",bulk.mainUploadCalls<64)
            val misses=bulk.observedPairMisses
            compare(c,reference,bulk,"aggregate-warm")
            assertEquals(0,bulk.mainUploadCalls);assertEquals(misses,bulk.observedPairMisses)
            for(context in 0..3)append(c,start+800*300+context*24,source=when(context){0->SonarPingSource.OWN;1->SonarPingSource.NATIVE;else->null})
            compare(c,reference,bulk,"aggregate-partial-four-contexts")
            assertEquals(4,bulk.mainUploadedColumns);assertEquals(4,bulk.mainUploadCalls)
            c.model.setFreeze(true);compare(c,reference,bulk,"aggregate-frozen",true)
            append(c,scale=255,maximum=24.576);compare(c,reference,bulk,"aggregate-frozen-after-new-live-scale")
            assertEquals(0,bulk.mainUploadCalls)
        }finally{reference.dispose();bulk.dispose();c.dispose()}
    }
    @Test fun stripeAllocationFailureKeepsSingleColumnPixelsAndDoesNotRetry() {
        var attempts=0;val buffer=SonarBulkUploadBuffer{attempts++;throw OutOfMemoryError("synthetic stripe allocation")}
        repeat(20){assertTrue(buffer.unavailable);assertEquals(1,buffer.maxColumns);assertNull(buffer.pixels)}
        assertEquals(1,attempts)
    }
}
