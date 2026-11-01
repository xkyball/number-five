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
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.floor

/** Synthetic IMAGE filtering evidence. It does not establish acoustic quality or device FPS. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarDisplaySmoothingTest {
    private val epoch=SonarEpoch("synthetic-display-smoothing",1,1)
    private val geometry=SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED)
    private val amplitude=SonarAmplitude(4095,12,true)
    private val actualHighGainDb=24.14184240658
    private val dimSeventhCode=9362
    private val dimSeventhColor=0xff023269.toInt() // RGB(2,50,105), not saturated red.
    private fun intensity(raw:Int,d:SonarDisplay=SonarDisplay()):Double =
        ((raw/4095.0*10.0.pow(d.gainDb/20)-d.threshold)/(1-d.threshold)).coerceIn(0.0,1.0).pow(1/d.gamma)
    private fun code(raw:Int,d:SonarDisplay=SonarDisplay())=(intensity(raw,d)*65535).roundToInt()
    private fun assertCode(expected:Int,actual:Int){assertTrue("Expected intensity code $expected +/- 1, actual $actual",abs(expected-actual)<=1)}
    private fun push(ring:SonarRingBuffer,id:Int,value:Int,time:Long=1_000_000L+id*100,
                     g:SonarGeometry=geometry,source:SonarPingSource?=SonarPingSource.OWN,
                     segment:Long=0,context:SonarAmplitude=amplitude,raw:ShortArray=ShortArray(1024){value.toShort()}) {
        ring.push(SonarFrame(raw,id.toLong(),time),geometry=g,pingSource=source,imageSegment=segment,
            amplitudeFullScale=context.fullScale?:4095,amplitude=context)
    }
    @Test fun isolatedReceivedBrightPingIsOneSeventhDisplayIntensityBeforePaletteAndNeverMedianDeleted(){
        val ring=SonarRingBuffer();for(i in 0..6)push(ring,i,if(i==3)4095 else 0)
        val cache=SonarDisplaySmoothingCache()
        try {
            assertTrue(cache.begin(ring,epoch));cache.prepare(3)
            assertTrue(cache.qualifiedAt(3));assertEquals(dimSeventhCode,cache.intensityCodeAt(3,500))
            assertEquals("Original received bright sample is untouched",4095,ring.sampleAt(3,500))
            cache.prepare(0);assertFalse(cache.qualifiedAt(0));assertEquals(0,cache.intensityCodeAt(0,500))
        }finally{cache.release()}
    }
    @Test fun aBroadStableEchoRetainsItsShapeAndCacheDoesNotRedoUnchangedProfiles(){
        val raw=ShortArray(1024){(4095*exp(-.5*((it-400.0)/35).pow(2))).roundToInt().toShort()}
        val ring=SonarRingBuffer();for(i in 0..6)push(ring,i,0,raw=raw)
        val cache=SonarDisplaySmoothingCache()
        val d=SonarDisplay(gainDb=3.0,gamma=1.7,threshold=.13)
        try {
            cache.begin(ring,epoch,d);for(age in 0 until ring.count)cache.prepare(age)
            for(bin in 0..1023)assertCode(code(ring.sampleAt(3,bin),d),cache.intensityCodeAt(3,bin))
            val prepared=cache.preparedColumns
            repeat(5){cache.begin(ring,epoch,d);for(age in 0 until ring.count)cache.prepare(age)}
            assertEquals(prepared,cache.preparedColumns)
            push(ring,7,0,raw=raw);cache.begin(ring,epoch,d);for(age in 0 until ring.count)cache.prepare(age)
            assertEquals("Only the new profile and the newly complete window require preparation",prepared+2,cache.preparedColumns)
            val previous=cache.preparedColumns;cache.begin(ring,epoch.copy(generation=2),d);cache.prepare(4)
            assertEquals(previous+1,cache.preparedColumns)
        }finally{cache.release()}
    }
    @Test fun gainGammaAndThresholdInvalidateIntensityWhilePaletteReusesItAndFallbackIsTransformedTarget(){
        val ring=SonarRingBuffer();for(i in 0..6)push(ring,i,600)
        val cache=SonarDisplaySmoothingCache()
        try{
            var d=SonarDisplay();cache.begin(ring,epoch,d);cache.prepare(3)
            assertCode(code(600,d),cache.intensityCodeAt(3,500));var prepared=cache.preparedColumns
            for(next in listOf(d.copy(gainDb=6.0),d.copy(gainDb=6.0,gamma=1.8),d.copy(gainDb=6.0,gamma=1.8,threshold=.12))){
                d=next;cache.begin(ring,epoch,d);cache.prepare(3)
                assertEquals(prepared+1,cache.preparedColumns);prepared=cache.preparedColumns
                assertCode(code(600,d),cache.intensityCodeAt(3,500))
            }
            cache.begin(ring,epoch,d.copy(palette=SonarPalette.MONO_AMBER));cache.prepare(3)
            assertEquals("Palette is after the intensity cache",prepared,cache.preparedColumns)
            cache.prepare(0);assertFalse(cache.qualifiedAt(0))
            assertCode(code(600,d),cache.intensityCodeAt(0,500))
            assertEquals(600,ring.sampleAt(0,500))
        }finally{cache.release()}
    }
    @Test fun neighboursAreSampledAtTheSamePhysicalDepthAndMissingCoverageIsNeverZeroFilled(){
        val ring=SonarRingBuffer()
        for(i in 0..6){
            val center=i==3;val g=if(center)SonarGeometry(24.576,SonarTxReference.HOST_VERIFIED)else geometry
            val raw=ShortArray(1024);raw[if(center)100 else 800]=4095
            if(center)raw[700]=2345
            push(ring,i,0,g=g,raw=raw)
        }
        val cache=SonarDisplaySmoothingCache()
        try {
            cache.begin(ring,epoch);cache.prepare(3)
            assertEquals("100 long-range bins and 800 short-range bins are the same physical depth",65535,cache.intensityCodeAt(3,100))
            assertCode(code(2345),cache.intensityCodeAt(3,700))
            val fractional=SonarRingBuffer()
            for(i in 0..6){val center=i==3;val raw=ShortArray(1024)
                if(center)raw[101]=2000 else {raw[151]=1000;raw[152]=3000}
                push(fractional,i,0,g=SonarGeometry(if(center)6.0 else 4.0,SonarTxReference.HOST_VERIFIED),raw=raw)}
            val d=SonarDisplay(gamma=2.0)
            cache.begin(fractional,epoch,d);cache.prepare(3)
            val expected=((intensity(2000,d)+6*(intensity(1000,d)+intensity(3000,d))/2)/7*65535).roundToInt()
            assertCode(expected,cache.intensityCodeAt(3,101))
            assertTrue("Physical interpolation uses transformed intensities, not interpolated ADC before gamma",abs(expected-code(2000,d))>100)
        }finally{cache.release()}
    }
    @Test fun sourceSegmentGeometryValidityAmplitudeContextAndTimeBoundariesAllKeepOriginalSamples(){
        for(kind in listOf("source","unknown","segment","tx","validity","scale","context","gap","window")){
            val ring=SonarRingBuffer()
            for(i in 0..6){val boundary=i==4
                val context=when{boundary&&kind=="scale"->SonarAmplitude(65535,16,true);boundary&&kind=="context"->amplitude.copy(producer="different");else->amplitude}
                val g=when{boundary&&kind=="validity"->SonarGeometry(null,SonarTxReference.UNVERIFIED);boundary&&kind=="tx"->SonarGeometry(3.072,SonarTxReference.EMPIRICAL_FALLBACK);else->geometry}
                val source=when{boundary&&kind=="source"->SonarPingSource.NATIVE;boundary&&kind=="unknown"->null;else->SonarPingSource.OWN}
                val time=1_000_000L+if(kind=="window")i*450L else i*100L+if(kind=="gap"&&i>=4)600 else 0
                push(ring,i,if(i==3)4095 else 0,time,g,source,if(boundary&&kind=="segment")1 else 0,context)
            }
            val cache=SonarDisplaySmoothingCache()
            try{cache.begin(ring,epoch);cache.prepare(3);assertFalse(kind,cache.qualifiedAt(3));assertEquals(kind,65535,cache.intensityCodeAt(3,500))}finally{cache.release()}
        }
    }
    @Test fun sevenLongHistoryImageColumnsAreAveragedWithoutRelabellingThemAsPingsOrChangingPeaks(){
        val history=SonarTimeImageHistory()
        for(i in 0..6)history.observe(SonarFrame(ShortArray(1024){if(i==3)4095 else 0},i.toLong(),1_000_000L+i*300),geometry,geometry.metersPerBin,4095,SonarPingSource.OWN,0,amplitude)
        val center=(0 until history.count).first{history.occupiedAt(it)&&history.firstTimestampAt(it)==1_000_900L}
        val cache=SonarDisplaySmoothingCache()
        try{
            cache.begin(history,epoch);cache.prepare(center);assertTrue(cache.qualifiedAt(center))
            assertEquals(dimSeventhCode,cache.intensityCodeAt(center,500));assertEquals(4095,history.sampleAt(center,500))
            assertEquals("A peak column does not acquire a fictitious measured ping ID",null,history.measuredPingIdAt(center))
            assertEquals(1,history.contributionCountAt(center))
            val version=cache.preparedColumns;cache.prepare(center);assertEquals(version,cache.preparedColumns)
        }finally{cache.release()}
        val gap=SonarTimeImageHistory()
        for(i in 0..6){val offset=if(i>=4)900 else 0
            gap.observe(SonarFrame(ShortArray(1024){if(i==3)4095 else 0},i.toLong(),1_000_000L+i*300+offset),geometry,geometry.metersPerBin,4095,SonarPingSource.OWN,0,amplitude)}
        val gapCenter=(0 until gap.count).first{gap.occupiedAt(it)&&gap.firstTimestampAt(it)==1_000_900L}
        val bounded=SonarDisplaySmoothingCache()
        try{bounded.begin(gap,epoch);bounded.prepare(gapCenter);assertFalse(bounded.qualifiedAt(gapCenter));assertEquals(65535,bounded.intensityCodeAt(gapCenter,500))}finally{bounded.release()}
    }
    private var now=1_000_000L
    private fun controller()=SonarNativeController(DeterministicMockSonarSource(now={now})){now}.also{
        it.model.connect("SYNTHETIC SMOOTHED IMAGE");it.model.acquisition(true);it.model.updateContext(epoch,SonarPingSource.OWN)
        it.model.setDisplay(it.model.display.copy(historySeconds=10.0,palette=SonarPalette.SUN_HIGH_CONTRAST,gainDb=actualHighGainDb,gamma=1.0,threshold=0.0))
    }
    private fun append(c:SonarNativeController,id:Int,value:Int,intervalMs:Long=100){
        val captured=1_000_000L+id*intervalMs;now=maxOf(now,captured)
        c.model.setAnalysis(geometry,SonarBottom(500.0,.9),null,true,amplitude=amplitude)
        c.model.frame(SonarFrame(ShortArray(1024){value.toShort()},id.toLong(),captured),pingSource=SonarPingSource.OWN);c.refresh()
    }
    private fun render(c:SonarNativeController,r:SonarNativeRenderer,night:Boolean=false)=Bitmap.createBitmap(1000,512,Bitmap.Config.ARGB_8888).also{r.drawPreview(Canvas(it),c,night,RectF(0f,0f,1000f,512f))}
    private fun pixels(b:Bitmap)=IntArray(b.width*b.height).also{b.getPixels(it,0,b.width,0,0,b.width,b.height)}
    @Test fun linearNativeCacheAppliesTheTargetColumnTransferAfterMeanAndExactBypassesEverything(){
        val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication());val backing=IntArray(2048*1024)
        try{
            for(i in 0..6)append(c,i,if(i==3)4095 else 0)
            val raw=c.model.ring.atAge(3)!!.samples;val depth=c.model.depth();val rate=c.model.measuredRate();val version=c.model.ring.version
            c.interpolation(true);val image=render(c,r);r.readUploadedPixels(backing)
            val slot=c.model.ring.storageSlotAt(3)
            assertEquals(actualHighGainDb,c.model.viewDisplay.gainDb,0.0)
            val linearCode=(65535.0/7).roundToInt()
            val expected=SonarNativeMath.palette(c.model.viewRing.imageValuesAt(3,c.model.viewDisplay).amplitude(linearCode/65535.0),c.model.viewDisplay.palette,false)
            assertEquals(expected,backing[500*2048+slot]);assertTrue(r.preparedSmoothedColumns>0)
            assertNotEquals("Canonical linear smoothing precedes transfer, rather than averaging separately clipped neighbours",dimSeventhColor,backing[500*2048+slot])
            val prepared=r.preparedSmoothedColumns;val repeat=render(c,r);assertEquals(prepared,r.preparedSmoothedColumns);assertEquals(0,r.mainUploadedColumns)
            c.setGain(-3.0);val recolored=render(c,r)
            assertEquals("A deliberate gain change recolors without rebuilding linear means",prepared,r.preparedSmoothedColumns)
            val transferred=r.preparedSmoothedColumns
            c.setPalette(SonarPalette.MONO_AMBER);val palette=render(c,r)
            assertEquals("Palette does not reapply the transfer",transferred,r.preparedSmoothedColumns)
            val night=render(c,r,true);assertEquals("Night is colour only",transferred,r.preparedSmoothedColumns)
            assertEquals(version,c.model.ring.version);assertEquals(depth,c.model.depth());assertEquals(rate,c.model.measuredRate());assertArrayEquals(raw,c.model.ring.atAge(3)!!.samples)
            c.exactPingProfiles(true);val exact=render(c,r);r.readUploadedPixels(backing)
            assertEquals(SonarNativeMath.palette(c.model.displayAmplitude(4095,4095,c.model.viewDisplay),c.model.viewDisplay.palette,false),backing[500*2048+slot])
            assertEquals("Exact never calls the smoothing cache",transferred,r.preparedSmoothedColumns)
            c.interpolation(false);val noLinear=render(c,r);assertArrayEquals(pixels(exact),pixels(noLinear))
            image.recycle();repeat.recycle();recolored.recycle();palette.recycle();night.recycle();exact.recycle();noLinear.recycle()
        }finally{r.dispose();c.dispose()}
    }
    @Test fun highGainLongHistoryNativeUploadUsesSevenImageIntensitiesAndKeepsTheStoredPeak(){
        val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication());val backing=IntArray(2048*1024)
        try{
            c.model.setDisplay(c.model.display.copy(historySeconds=120.0))
            for(i in 0 until 300)append(c,i,if(i==296)4095 else 0,300)
            assertTrue(c.model.imageHistoryAggregated);val columns=c.model.viewImageColumns
            val age=(0 until columns.count).first{columns.occupiedAt(it)&&columns.firstTimestampAt(it)==1_088_800L}
            val revision=columns.version;val raw=c.model.ring.atAge(3)!!.samples
            assertEquals(4095,columns.sampleAt(age,500));assertNull(columns.measuredPingIdAt(age))
            c.interpolation(true);val image=render(c,r)
            try{
                r.readUploadedPixels(backing);val slot=columns.storageSlotAt(age);val row=(500*255.0/1023).roundToInt()
                assertEquals(dimSeventhColor,backing[((slot/2048)*256+row)*2048+slot%2048])
                assertEquals(revision,columns.version);assertEquals(4095,columns.sampleAt(age,500))
                assertArrayEquals(raw,c.model.ring.atAge(3)!!.samples)
            }finally{image.recycle()}
        }finally{r.dispose();c.dispose()}
    }
    @Test fun longHistoryAveragesSixActualPingIntensitiesBeforeItsSevenColumnMeanInsteadOfAveragingStoredPeaks(){
        val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication());val backing=IntArray(2048*1024)
        val expectedCode=10923;val expectedColor=0xff013775.toInt() // round(65535/6), RGB(1,55,117).
        try{
            c.model.setDisplay(c.model.display.copy(historySeconds=120.0))
            // Index4 starts exactly on a 300-ms boundary. Each bucket receives SIX real profiles.
            for(index in 4 until 4+48*6)append(c,index,if((index-4)%6==0)4095 else 0,50)
            assertTrue("Exercise the model-selected aggregate path",c.model.imageHistoryAggregated)
            assertEquals(288,c.model.ring.count)
            val columns=c.model.viewImageColumns;val centerIndex=4+44*6;val centerTime=1_000_000L+centerIndex*50
            val age=(0 until columns.count).first{columns.occupiedAt(it)&&columns.firstTimestampAt(it)==centerTime}
            assertEquals(6L,columns.contributionCountAt(age));assertEquals(centerTime+250,columns.lastTimestampAt(age))
            assertEquals(4095,columns.sampleAt(age,500));assertNull(columns.measuredPingIdAt(age))
            val rawAge=(0 until c.model.ring.count).first{c.model.ring.pingIdAt(it)==centerIndex.toLong()}
            val raw=c.model.ring.atAge(rawAge)!!.samples;val peakRevision=columns.version;val rawRevision=c.model.ring.version
            val slot=columns.storageSlotAt(age);val row=(500*255.0/1023).roundToInt()
            fun uploadedAggregate()=backing[((slot/2048)*256+row)*2048+slot%2048]
            val nearest=render(c,r)
            try{r.readUploadedPixels(backing);assertEquals(SonarNativeMath.palette(1.0,c.model.viewDisplay.palette,false),uploadedAggregate())}finally{nearest.recycle()}
            val cache=SonarDisplaySmoothingCache()
            try{
                assertTrue(cache.begin(columns,epoch,c.model.viewDisplay));cache.prepare(age)
                assertTrue(cache.qualifiedAt(age));assertEquals(expectedCode,cache.intensityCodeAt(age,500))
                assertEquals(7L,cache.intensityMeanProfilePreparations)
                val adjacent=(0 until columns.count).first{columns.occupiedAt(it)&&columns.firstTimestampAt(it)==centerTime-300}
                cache.prepare(adjacent);assertTrue(cache.qualifiedAt(adjacent))
                assertEquals("Adjacent windows share six already transformed actual-ping profiles",8L,cache.intensityMeanProfilePreparations)
                assertEquals(expectedCode,cache.intensityCodeAt(adjacent,500))
            }finally{cache.release()}
            c.interpolation(true);val smoothed=render(c,r)
            try{
                r.readUploadedPixels(backing)
                assertEquals(expectedColor,SonarNativeMath.palette(expectedCode/65535.0,c.model.viewDisplay.palette,false))
                assertEquals("Seven bright MAX aggregates must not replace the original1/6 ping intensity",expectedColor,uploadedAggregate())
                assertNotEquals(SonarNativeMath.palette(1.0,c.model.viewDisplay.palette,false),uploadedAggregate())
                assertEquals(peakRevision,columns.version);assertEquals(4095,columns.sampleAt(age,500))
                assertEquals(rawRevision,c.model.ring.version);assertArrayEquals(raw,c.model.ring.atAge(rawAge)!!.samples)
            }finally{smoothed.recycle()}
            val prepared=r.preparedSmoothedColumns;c.exactPingProfiles(true)
            val exact=render(c,r)
            try{
                r.readUploadedPixels(backing);assertFalse(c.model.imageHistoryAggregated)
                assertEquals(SonarNativeMath.palette(1.0,c.model.viewDisplay.palette,false),backing[500*2048+c.model.ring.storageSlotAt(rawAge)])
                assertEquals(prepared,r.preparedSmoothedColumns);assertArrayEquals(raw,c.model.ring.atAge(rawAge)!!.samples)
            }finally{exact.recycle()}
        }finally{r.dispose();c.dispose()}
    }
    @Test fun actualBucketMeanOverflowOrIncompleteMetricCoverageIsNeverClaimedAsACompleteMean(){
        for(mode in listOf("overflow","partial-coverage")){
            val history=SonarTimeImageHistory();var id=0L
            for(bucket in 0..6){
                val count=if(mode=="overflow"&&bucket==3)17 else 6
                for(ping in 0 until count){
                    val g=if(mode=="partial-coverage"&&bucket==3&&ping>0)geometry else SonarGeometry(24.576,SonarTxReference.HOST_VERIFIED)
                    history.observe(SonarFrame(ShortArray(1024){if(ping==0)4095 else 0},id++,900_000L+bucket*300+ping*10),
                        g,g.metersPerBin,4095,SonarPingSource.OWN,0,amplitude)
                }
            }
            val center=(0 until history.count).first{history.occupiedAt(it)&&history.firstTimestampAt(it)==900_900L}
            val version=history.version;val peak=history.sampleAt(center,500);val cache=SonarDisplaySmoothingCache()
            try{
                cache.begin(history,epoch,SonarDisplay(gainDb=actualHighGainDb));cache.prepare(center)
                assertFalse("$mode must not claim complete actual-ping mean coverage",cache.qualifiedAt(center))
                assertEquals("Unavailable coverage retains the transformed real target peak",65535,cache.intensityCodeAt(center,500))
                assertTrue(cache.fallbackRows>0)
                if(mode=="overflow")assertEquals(0,cache.qualifiedRowsAt(center))
                else{
                    assertTrue(cache.qualifiedRowsAt(center) in 1 until SonarTimeImageHistory.DEPTH_BINS)
                    assertEquals("Only genuinely covered depths use the original six-ping denominator",10923,cache.intensityCodeAt(center,50))
                }
                assertEquals(version,history.version);assertEquals(peak,history.sampleAt(center,500))
            }finally{cache.release()}
        }
    }
    @Test fun frozenSmoothedPixelsStayFixedWhileLivePingsAnalysisAndRateContinue(){
        val c=controller();val r=SonarNativeRenderer(RuntimeEnvironment.getApplication())
        try{
            for(i in 0..6)append(c,i,if(i==3)4095 else 0)
            now+=500;c.refresh() // Flush the existing 2-Hz rate display cache before the baseline.
            c.interpolation(true);c.freeze();val held=render(c,r);val raw=c.model.viewRing.atAge(3)!!.samples;val frozenDepth=c.model.depth()
            val rate=c.model.measuredRate().eventCount
            for(i in 7..10)append(c,i,3000)
            val continued=render(c,r);assertArrayEquals(pixels(held),pixels(continued));assertEquals(0,r.mainUploadedColumns)
            assertArrayEquals(raw,c.model.viewRing.atAge(3)!!.samples);assertEquals(frozenDepth,c.model.depth())
            now+=500;c.refresh();assertEquals(rate+4,c.model.measuredRate().eventCount)
            held.recycle();continued.recycle()
        }finally{r.dispose();c.dispose()}
    }
    @Test fun allocationFailureIsLatchedUntilExplicitReleaseAndDoesNotAlterTheInput(){
        val ring=SonarRingBuffer();push(ring,0,4095);var attempts=0
        val cache=SonarDisplaySmoothingCache{attempts++;throw OutOfMemoryError("synthetic display cache failure")}
        assertFalse(cache.begin(ring,epoch));assertTrue(cache.unavailable);assertFalse(cache.begin(ring,epoch));assertEquals(1,attempts)
        assertEquals(4095,ring.sampleAt(0,500));cache.release();assertFalse(cache.begin(ring,epoch));assertEquals(2,attempts)
    }
    private class CountedColumns(private val original:SonarImageColumns):SonarImageColumns by original {
        var copies=0;var meanCopies=0;var samples=0;var mappings=0;var meters=0;var geometries=0;var slots=0
        override fun copyDisplayProfileAt(age:Int,destination:ShortArray,offset:Int){copies++;original.copyDisplayProfileAt(age,destination,offset)}
        override fun copyMeanIntensityProfileAt(age:Int,lut:FloatArray,destination:FloatArray,offset:Int):Boolean {
            meanCopies++;return original.copyMeanIntensityProfileAt(age,lut,destination,offset)
        }
        override fun sampleAt(age:Int,bin:Int):Int {samples++;return original.sampleAt(age,bin)}
        override fun sourceBinAtAxis(age:Int,axisBin:Double,axisMetersPerBin:Double):Double {mappings++;return original.sourceBinAtAxis(age,axisBin,axisMetersPerBin)}
        override fun metersPerBinValueAt(age:Int):Double {meters++;return original.metersPerBinValueAt(age)}
        override fun geometryAt(age:Int):SonarGeometry? {geometries++;return original.geometryAt(age)}
        override fun storageSlotAt(age:Int):Int {slots++;return original.storageSlotAt(age)}
        fun reset(){copies=0;meanCopies=0;samples=0;mappings=0;meters=0;geometries=0;slots=0}
    }
    /** Previous virtual-access arithmetic, deliberately independent of the new profile scratch. */
    private fun virtualReference(columns:SonarImageColumns,center:Int,members:IntArray,d:SonarDisplay):IntArray {
        val rows=if(columns.aggregated)256 else 1024;val spacing=columns.metersPerBinValueAt(center)
        val lut=FloatArray(65536);SonarTransferLut().fillIntensities(lut,columns.amplitudeFullScaleAt(center),d)
        return IntArray(rows){row->
            val bin=row*1023.0/(rows-1);var sum=0.0;var covered=true
            for(member in members){
                val position=columns.sourceBinAtAxis(member,bin,spacing)
                if(!position.isFinite()){covered=false;break}
                val grid=position*(rows-1)/1023;val low=floor(grid).toInt();val high=(low+1).coerceAtMost(rows-1);val weight=grid-low
                sum+=lut[columns.sampleAt(member,(low*1023.0/(rows-1)).roundToInt())]*(1-weight)+
                    lut[columns.sampleAt(member,(high*1023.0/(rows-1)).roundToInt())]*weight
            }
            val value=if(covered)sum/7 else lut[columns.sampleAt(center,bin.roundToInt())].toDouble()
            (value*65535).roundToInt().coerceIn(0,65535)
        }
    }
    private fun assertBoundedPreparation(original:SonarImageColumns,center:Int,members:IntArray) {
        val d=SonarDisplay(gainDb=3.5,gamma=1.6,threshold=.08)
        val counted=CountedColumns(original);val cache=SonarDisplaySmoothingCache()
        val rows=if(original.aggregated)256 else 1024
        fun parity(display:SonarDisplay){
            val expected=virtualReference(original,center,members,display)
            for(row in 0 until rows)assertEquals("UInt16 parity row=$row aggregated=${original.aggregated}",expected[row],cache.intensityCodeAt(center,(row*1023.0/(rows-1)).roundToInt()))
        }
        fun bounded(copies:Int,meanCopies:Int=0){
            assertEquals("One native copy per required profile",copies,counted.copies)
            assertEquals("Each retained actual-ping mean is copied only once per required column",meanCopies,counted.meanCopies)
            assertEquals("No sampleAt in the production row loop",0,counted.samples)
            assertEquals("No per-row virtual geometry mapping",0,counted.mappings)
            assertTrue("Geometry accesses independent of depth-row count: ${counted.geometries}",counted.geometries<=32)
            assertTrue("Spacing accesses independent of depth-row count: ${counted.meters}",counted.meters<=32)
            assertTrue("Slot accesses independent of depth-row count: ${counted.slots}",counted.slots<=64)
        }
        try{
            val copies=if(original.aggregated)1 else 7;val means=if(original.aggregated)7 else 0
            cache.begin(counted,epoch,d);cache.prepare(center)
            if(original.aggregated){
                assertFalse("Mixed metric coverage cannot claim an entirely mean-covered column",cache.qualifiedAt(center))
                assertTrue(cache.qualifiedRowsAt(center) in 1 until rows)
            }else assertTrue(cache.qualifiedAt(center))
            bounded(copies,means);parity(d)
            counted.reset();cache.begin(counted,epoch,d);cache.prepare(center);bounded(0)
            val changed=d.copy(gainDb=4.5);counted.reset();cache.begin(counted,epoch,changed);cache.prepare(center);bounded(copies,means);parity(changed)
            val boundary=members.first();counted.reset();cache.prepare(boundary);assertFalse(cache.qualifiedAt(boundary));bounded(1)
            val lut=FloatArray(65536);SonarTransferLut().fillIntensities(lut,original.amplitudeFullScaleAt(boundary),changed)
            for(row in 0 until rows){val bin=(row*1023.0/(rows-1)).roundToInt()
                assertEquals((lut[original.sampleAt(boundary,bin)]*65535.0).roundToInt(),cache.intensityCodeAt(boundary,bin))
            }
        }finally{cache.release()}
    }
    @Test fun qualifiedRawPreparationCopiesSevenProfilesInsteadOfCallingVirtualSourcesPerDepthRow(){
        val ring=SonarRingBuffer()
        for(index in 0..6){val max=if(index==3)24.576 else 3.072*(1 shl(index%3))
            push(ring,index,0,g=SonarGeometry(max,SonarTxReference.HOST_VERIFIED),raw=ShortArray(1024){((index*239+it*89)and 4095).toShort()})
        }
        val original=ring.atAge(3)!!.samples;val version=ring.version
        assertBoundedPreparation(ring,3,IntArray(7){it})
        assertEquals(version,ring.version);assertArrayEquals(original,ring.atAge(3)!!.samples)
    }
    @Test fun qualifiedAggregatePreparationCopiesSevenPoolPlaneProfilesWithBitExactOldGeometryAndIntensityMapping(){
        val history=SonarTimeImageHistory();val sources=arrayOf(SonarPingSource.NATIVE,null,SonarPingSource.ALL,SonarPingSource.OWN)
        for(index in 0..6)for(plane in 0..3){
            val max=if(index==3)24.576 else 3.072*(1 shl(index%3));val g=SonarGeometry(max,SonarTxReference.HOST_VERIFIED)
            history.observe(SonarFrame(ShortArray(1024){((index*239+it*89+plane*41)and 4095).toShort()},index*4L+plane,900_000L+index*300+plane*10),g,g.metersPerBin,4095,sources[plane],0,amplitude)
        }
        assertEquals(4,history.allocatedPoolCount);val center=15
        assertEquals(3,history.storageSlotAt(center)/2048)
        val version=history.version;val peak=IntArray(1024){history.sampleAt(center,it)}
        assertBoundedPreparation(history,center,IntArray(7){it*4+3})
        assertEquals(version,history.version);assertArrayEquals(peak,IntArray(1024){history.sampleAt(center,it)})
    }
}
