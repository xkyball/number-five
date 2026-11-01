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
import kotlin.math.*

/** Synthetic image interpolation only. No inferred measurement or hardware timing claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarTemporalInterpolationRenderTest {
    private var now=1_000_000L
    private fun controller()=SonarNativeController(DeterministicMockSonarSource(now={now})){now}.also {
        it.model.connect("SYNTHETIC TEMPORAL IMAGE");it.model.acquisition(true)
        it.model.updateContext(SonarEpoch("synthetic-image",1,1),SonarPingSource.ALL)
        it.model.setDisplay(it.model.display.copy(historySeconds=10.0,palette=SonarPalette.MONO_AMBER))
    }
    private fun append(c:SonarNativeController,id:Long,time:Long,maximum:Double=3.072,source:SonarPingSource?=SonarPingSource.OWN,
                       raw:ShortArray=ShortArray(1024){4095},bottomBin:Double?=null) {
        now=max(now,time)
        c.model.setAnalysis(SonarGeometry(maximum,SonarTxReference.HOST_VERIFIED),bottomBin?.let{SonarBottom(it,.9)}?:SonarBottom(),null,true,
            amplitude=SonarAmplitude(4095,12,true))
        c.model.frame(SonarFrame(raw,id,time),receivedAtEpochMs=now,pingSource=source);c.refresh()
    }
    private fun echo(center:Double,width:Double)=ShortArray(1024){(4095*exp(-.5*((it-center)/width).pow(2))).roundToInt().toShort()}
    private fun render(c:SonarNativeController,r:SonarNativeRenderer)=Bitmap.createBitmap(1000,512,Bitmap.Config.ARGB_8888).also {
        r.drawPreview(Canvas(it),c,false,RectF(0f,0f,1000f,512f))
    }
    private fun save(image:Bitmap,name:String) {
        val file=File(System.getProperty("no5.renderOutputDir"),"temporal-image/$name.png")
        file.parentFile!!.mkdirs();file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun pixels(image:Bitmap)=IntArray(image.width*image.height).also{image.getPixels(it,0,image.width,0,0,image.width,image.height)}
    @Test fun broadMovingEchoBecomesSmootherWhileRawDepthAndRateRemainUnchangedAndFreezeHolds() {
        val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        try {
            for(i in 0..8)append(c,i+1L,now-800+i*100,raw=echo(340.0+i*6,18.0),bottomBin=340.0+i*6)
            now+=500;c.refresh() // Observe after the unchanged 2-Hz rate-display gate.
            val raw=c.model.latest!!.samples;val count=c.model.ring.count;val depth=c.model.depth();val rate=c.model.measuredRate().eventCount
            val nearest=render(c,r);c.interpolation(true);val linear=render(c,r)
            save(nearest,"broad-moving-nearest");save(linear,"broad-moving-linear")
            val ring=c.model.viewRing
            val firstCentre=1000-c.model.historyFractionAt(ring.count-1)*1000
            val lastFullCentre=1000-c.model.historyFractionAt(1)*1000
            val lastFullWidth=(ring.timestampAt(0)-ring.timestampAt(1)).coerceIn(0,150)/10000.0*1000
            // Compare the common measured-cell interior, not the existing empty
            // half-cell before Nearest's single newest boundary column.
            val firstX=ceil(firstCentre).toInt()+1
            val lastX=ceil(lastFullCentre+lastFullWidth/2).toInt()-1
            fun maximumStep(image:Bitmap):Double {
                fun centre(x:Int):Double {
                    var sum=0.0;var weighted=0.0
                    for(y in 140..220){val a=((image.getPixel(x,y) ushr 16 and 255)-8).coerceAtLeast(0);sum+=a;weighted+=a*y}
                    assertTrue("Measured ridge is present at every evaluated pixel x=$x",sum>0)
                    return weighted/sum
                }
                var largest=0.0;for(x in firstX+1..lastX)largest=max(largest,abs(centre(x)-centre(x-1)));return largest
            }
            val oldStep=maximumStep(nearest);val newStep=maximumStep(linear)
            println("SYNTHETIC_RIDGE nearestMaxStepPx=$oldStep linearMaxStepPx=$newStep evaluatedX=$firstX..$lastX")
            assertTrue("Fixture has real rectangular ridge steps: $oldStep",oldStep>2)
            assertTrue("Temporal blending smooths the broad moving ridge: old=$oldStep new=$newStep",newStep<oldStep/2)
            assertArrayEquals(raw,c.model.latest!!.samples);assertEquals(count,c.model.ring.count);assertEquals(depth,c.model.depth());assertEquals(rate,c.model.measuredRate().eventCount)
            assertFalse("Identical geometry uses the existing atlas, no second bitmap",r.temporalAllocated)
            c.model.setFreeze(true);c.refresh();val held=render(c,r)
            c.interpolation(false)
            assertTrue("Common controller must reject a frozen interpolation change",c.linearInterpolation)
            val attempted=render(c,r);assertArrayEquals("Frozen pixels survive even a direct controller call",pixels(held),pixels(attempted))
            assertArrayEquals(raw,c.model.latest!!.samples);assertEquals(count,c.model.ring.count)
            attempted.recycle()
            append(c,10,now+100,raw=echo(440.0,18.0),bottomBin=440.0)
            now+=500;c.refresh()
            assertEquals("Freeze must not stop live measurement counting",rate+1,c.model.measuredRate().eventCount)
            val continued=render(c,r);assertArrayEquals(pixels(held),pixels(continued))
            save(nearest,"broad-moving-nearest");save(linear,"broad-moving-linear");save(held,"frozen-held-image")
            nearest.recycle();linear.recycle();held.recycle();continued.recycle()
        }finally{r.dispose();c.dispose()}
    }
    @Test fun irregularOwnBurstPausesUseOriginalTimesOnEveryIntermediateRasterCell() {
        val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        try {
            val end=now
            for((index,offset) in listOf(-238L,-214L,-190L,0L).withIndex())
                append(c,index+1L,end+offset)
            c.interpolation(true);val image=render(c,r)
            val observed=SonarNativeMath.palette(1.0,SonarPalette.MONO_AMBER,false)
            for(x in 975..997)assertEquals("24/24/190ms cadence must fill existing intermediate cell $x",observed,image.getPixel(x,200))
            assertEquals("There is no future ping beyond the measured time origin",0xff061323.toInt(),image.getPixel(999,200))
            assertEquals(4,c.model.ring.count)
            now+=500 // Count must update after the existing display-cache period, not before.
            assertEquals(4,c.model.measuredRate().eventCount)
            save(image,"irregular-24-24-190ms-filled-raster");image.recycle()
        }finally{r.dispose();c.dispose()}
    }

    @Test fun observedSegmentSourceAndLongTimeGapsRemainOpenInsteadOfBeingInterpolated() {
        for(kind in listOf("segment","source","unknown","time")) {
            val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
            try {
                append(c,1,now-100)
                if(kind=="segment")c.model.markImageContinuityBoundary()
                val source=when(kind){"source"->SonarPingSource.NATIVE;"unknown"->null;else->SonarPingSource.OWN}
                append(c,2,now+if(kind=="time")500 else 0,source=source)
                c.interpolation(true);val image=render(c,r)
                val oldX=1000f-((c.model.viewRing.timestampAt(0)-c.model.viewRing.timestampAt(1))/10000f)*1000
                val middle=((oldX+997.5f)/2).roundToInt()
                assertEquals("No fabricated image bridge for $kind",0xff061323.toInt(),image.getPixel(middle,200))
                assertEquals("Actual newest ping remains",SonarNativeMath.palette(1.0,SonarPalette.MONO_AMBER,false),image.getPixel(997,200))
                save(image,"gap-$kind");image.recycle()
            }finally{r.dispose();c.dispose()}
        }
    }
    @Test fun changingCaptureRangesInterpolateOnlyTheirSharedMeasuredDepthAndCacheIncrementally() {
        val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        try {
            append(c,1,now-100,3.072);append(c,2,now,24.576)
            val nearest=render(c,r);assertFalse(r.temporalAllocated)
            c.interpolation(true);val linear=render(c,r)
            assertTrue(r.temporalAllocated);assertEquals(1L,r.preparedTemporalPairs)
            assertEquals(SonarNativeMath.palette(1.0,SonarPalette.MONO_AMBER,false),linear.getPixel(994,32))
            assertEquals("Shared short capture has no deeper samples",0xff061323.toInt(),linear.getPixel(994,200))
            assertEquals("Long endpoint retains its full real coverage",SonarNativeMath.palette(1.0,SonarPalette.MONO_AMBER,false),linear.getPixel(997,200))
            val repeat=render(c,r);assertEquals(1L,r.preparedTemporalPairs);assertArrayEquals(pixels(linear),pixels(repeat))
            append(c,3,now+100,3.072);val next=render(c,r);assertEquals(2L,r.preparedTemporalPairs)
            r.releaseTemporalResources(false);val fallback=render(c,r);assertFalse(r.temporalAllocated);assertEquals(2L,r.preparedTemporalPairs)
            val again=render(c,r);assertFalse(r.temporalAllocated);assertEquals(2L,r.preparedTemporalPairs)
            c.interpolation(false);val disabled=render(c,r);c.interpolation(true);val recovered=render(c,r)
            assertTrue("Explicit re-enable allows recovery",r.temporalAllocated);assertEquals(4L,r.preparedTemporalPairs)
            save(nearest,"range-transition-nearest");save(linear,"range-transition-shared-depth");save(next,"range-transition-back");save(fallback,"resource-loss-real-pings-only")
            for(image in listOf(nearest,linear,repeat,next,fallback,again,disabled,recovered))image.recycle()
        }finally{r.dispose();c.dispose()}
    }
    @Test fun largeNonOverlappingEchoJumpShowsTwoFadingPeaksWithoutInventedPeakMigration() {
        val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        try {
            append(c,1,now-100,raw=echo(200.0,4.0));append(c,2,now,raw=echo(800.0,4.0))
            c.interpolation(true);val image=render(c,r)
            val oldPeak=image.getPixel(994,100) ushr 16 and 255
            val newPeak=image.getPixel(994,400) ushr 16 and 255
            assertTrue("Both observed peaks blend in time",oldPeak>40 && newPeak>40)
            assertEquals("No invented middle-depth echo",8,image.getPixel(994,250) ushr 16 and 255)
            save(image,"large-jump-limit-two-fading-peaks");image.recycle()
        }finally{r.dispose();c.dispose()}
    }
    private fun appendCanonical(c:SonarNativeController,id:Long,time:Long,bin:Double,quality:SonarAnalysisQuality=SonarAnalysisQuality.LOCKED):ShortArray {
        now=max(now,time)
        val raw=echo(bin,4.0)
        val p=SonarPresentation(id,quality,null,"auto","auto_raw",false,false,null,false)
        c.model.setAnalysis(SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),SonarBottom(bin,.9,source=SonarBottomSource.M1_RAW),p,true,
            amplitude=SonarAmplitude(4095,12,true))
        c.model.frame(SonarFrame(raw,id,time),receivedAtEpochMs=now,pingSource=SonarPingSource.OWN);c.refresh()
        return raw
    }
    private fun connectedRidge(image:Bitmap,oldX:Double,oldY:Double,newX:Double,newY:Double):Boolean {
        val left=max(0,floor(min(oldX,newX)).toInt()-1);val right=min(image.width-1,ceil(max(oldX,newX)).toInt()+1)
        val top=max(0,floor(min(oldY,newY)).toInt()-3);val bottom=min(image.height-1,ceil(max(oldY,newY)).toInt()+3)
        val width=right-left+1;val height=bottom-top+1
        fun isEcho(x:Int,y:Int)=x in left..right && y in top..bottom && (image.getPixel(x,y) ushr 16 and 255)>35
        val startX=floor(oldX).toInt();val startY=floor(oldY).toInt();val endX=floor(newX).toInt();val endY=floor(newY).toInt()
        if(!isEcho(startX,startY) || !isEcho(endX,endY))return false
        val visited=BooleanArray(width*height);val queue=IntArray(visited.size);var head=0;var tail=0
        fun add(x:Int,y:Int) {if(!isEcho(x,y))return;val i=(y-top)*width+x-left;if(!visited[i]){visited[i]=true;queue[tail++]=i}}
        add(startX,startY)
        while(head<tail) {val i=queue[head++];val x=i%width+left;val y=i/width+top;if(x==endX&&y==endY)return true
            add(x-1,y);add(x+1,y);add(x,y-1);add(x,y+1)}
        return false
    }
    @Test fun matchingLockedBottomAnchorsConnectLargeAndSubpixelJumpsOnlyInTheImage() {
        for(interval in listOf(100L,8L)) {
            val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
            try {
                val first=appendCanonical(c,1,now-interval,200.0);val last=appendCanonical(c,2,now,800.0)
                val depth=c.model.depth();val rate=c.model.measuredRate().eventCount
                c.interpolation(true);val image=render(c,r)
                val oldX=997.5-interval/10.0;val newX=997.5;val oldY=200.0/1023*512;val newY=800.0/1023*512
                save(image,"confirmed-large-jump-${interval}ms-connected-image-ridge")
                assertTrue("Verified canonical anchors must produce a four-connected image ridge even for ${interval}ms",connectedRidge(image,oldX,oldY,newX,newY))
                assertEquals(depth,c.model.depth());assertEquals(rate,c.model.measuredRate().eventCount);assertEquals(2,c.model.ring.count)
                assertArrayEquals(first,c.model.ring.atAge(1)!!.samples);assertArrayEquals(last,c.model.ring.atAge(0)!!.samples)
                c.model.setFreeze(true);c.refresh();val frozen=render(c,r)
                appendCanonical(c,3,now+100,500.0);val liveContinues=render(c,r)
                assertArrayEquals("Registered display interpolation is frozen with its original context",pixels(frozen),pixels(liveContinues))
                image.recycle();frozen.recycle();liveContinues.recycle()
            }finally{r.dispose();c.dispose()}
        }
    }
    @Test fun absentOrNonmatchingCanonicalBottomEvidenceNeverBecomesAnImageAnchor() {
        val cache=SonarTemporalImageCache()
        try {
            for(reason in listOf("missing","searching","wrong-ping","unverified")) {
                val ring=SonarRingBuffer()
                for(i in 0..1) {
                    val id=i+1L;val p=if(reason=="missing")null else SonarPresentation(if(reason=="wrong-ping")999 else id,
                        if(reason=="searching")SonarAnalysisQuality.SEARCHING else SonarAnalysisQuality.LOCKED,null,"auto","auto_raw",false,false,null,false)
                    val g=if(reason=="unverified")SonarGeometry(null,SonarTxReference.UNVERIFIED)else SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED)
                    ring.push(SonarFrame(ShortArray(1024),id,1_000_000+i*100L),bottom=SonarBottom(if(i==0)200.0 else 800.0,.9,source=SonarBottomSource.M1_RAW),geometry=g,
                        amplitudeFullScale=4095,presentation=p,pingSource=SonarPingSource.OWN)
                }
                assertTrue("The image interval is otherwise compatible",cache.compatible(ring,0))
                assertFalse("No guessed anchor for $reason",cache.hasBottomAnchors(ring,0))
            }
            val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
            try {
                appendCanonical(c,1,now-100,200.0,SonarAnalysisQuality.SEARCHING)
                appendCanonical(c,2,now,800.0,SonarAnalysisQuality.SEARCHING)
                c.interpolation(true);val image=render(c,r)
                assertNull("SEARCHING never advertises a current depth",c.model.depth())
                assertFalse(connectedRidge(image,987.5,200.0/1023*512,997.5,800.0/1023*512))
                save(image,"unconfirmed-large-jump-stays-crossfade");image.recycle()
            }finally{r.dispose();c.dispose()}
        }finally{cache.release()}
    }

    @Test fun pairCacheHandlesRingWrapAndInvalidatesAnEpochWithoutReusingOldPixels() {
        val cache=SonarTemporalImageCache()
        try {
            val ring=SonarRingBuffer();val raw=ShortArray(1024){1234}
            for(i in 0..2048) {
                raw[0]=i.toShort()
                ring.push(SonarFrame(raw,i.toLong(),1_000_000L+i*100L),geometry=SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),
                    amplitudeFullScale=4095,pingSource=SonarPingSource.OWN)
            }
            val colors=IntArray(65536){it or (0xff shl 24)}
            assertTrue(cache.compatible(ring,0));assertTrue(cache.begin(ring,SonarEpoch("synthetic-a",1),"colors"))
            val slot=cache.prepare(ring,0,colors);val rect=android.graphics.Rect();cache.sourceRect(slot,rect)
            assertEquals(colors[2047],cache.bitmap!!.getPixel(rect.left,rect.top))
            assertEquals(colors[2048],cache.bitmap!!.getPixel(rect.left+1,rect.top))
            cache.prepare(ring,0,colors);assertEquals(1L,cache.preparedPairs)
            assertTrue(cache.begin(ring,SonarEpoch("synthetic-b",2),"colors"));cache.prepare(ring,0,colors)
            assertEquals(2L,cache.preparedPairs)
        }finally{cache.release()}
    }
    @Test fun atlasAllocationFailureIsBoundedAndCanBeRetriedOnlyAfterExplicitRelease() {
        var attempts=0
        val cache=SonarTemporalImageCache{attempts++;throw OutOfMemoryError("synthetic allocation failure")}
        val ring=SonarRingBuffer()
        assertFalse(cache.begin(ring,null,"test"));assertTrue(cache.unavailable)
        assertFalse(cache.begin(ring,null,"test"));assertEquals(1,attempts)
        cache.release();assertFalse(cache.begin(ring,null,"test"));assertEquals(2,attempts)
    }
}
