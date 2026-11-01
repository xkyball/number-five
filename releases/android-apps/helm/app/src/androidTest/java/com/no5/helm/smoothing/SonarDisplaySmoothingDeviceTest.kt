package com.no5.helm.smoothing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.*
import com.no5.helm.SonarDisplaySmoothingCache
import com.no5.helm.SonarNativeController
import com.no5.helm.SonarNativeMath
import com.no5.helm.SonarNativeRenderer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Deterministic, test-only Android Canvas proof against the installed target APK.
 * No Activity, saved preferences, SourceSession, socket, Gateway, Sonard or Pico is used.
 * Offscreen Android bitmaps are not evidence of a live acoustic source, a displayed
 * Surface frame, GPU timing or FPS. Build this instrumentation against RELEASE when
 * the installed target is RELEASE: Kotlin internal method names are variant-specific.
 */
@RunWith(AndroidJUnit4::class)
class SonarDisplaySmoothingDeviceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context:Context get()=instrumentation.targetContext
    private val epoch=SonarEpoch("synthetic-device-smoothing",1,1)
    private val geometry=SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED)
    private val amplitude=SonarAmplitude(4095,12,true)
    private val actualHighGainDb=24.14184240658
    private val dimSeventhCode=9362
    private val dimSeventhColor=0xff023269.toInt() // RGB(2,50,105).
    private val proofId=InstrumentationRegistry.getArguments().getString("no5SmoothingProofId")
        ?.also{require(it.matches(Regex("[A-Za-z0-9_-]{1,80}")))}?:UUID.randomUUID().toString()

    /** Reuses the existing explicit in-memory fixture; no production mock source is installed. */
    private inner class Fixture(windowSeconds:Double=10.0):AutoCloseable {
        var now=1_000_000L
        val source=DeterministicMockSonarSource(now={now})
        val controller=SonarNativeController(source){now}.also {
            it.model.connect("SYNTHETIC ANDROID DEVICE TEST")
            it.model.acquisition(true)
            it.model.updateContext(epoch,SonarPingSource.OWN)
            it.model.setDisplay(it.model.display.copy(historySeconds=windowSeconds,
                palette=SonarPalette.SUN_HIGH_CONTRAST,gamma=1.0,gainDb=actualHighGainDb,threshold=0.0))
        }
        val renderer=SonarNativeRenderer(context)
        private val atlasPixels=IntArray(SONAR_CAPACITY*SONAR_BINS)
        fun append(index:Int,value:Int,intervalMs:Long=100) {
            val captured=1_000_000L+index*intervalMs
            now=maxOf(now,captured)
            controller.model.setAnalysis(geometry,SonarBottom(500.0,.9),null,true,amplitude=amplitude)
            controller.model.frame(SonarFrame(ShortArray(SONAR_BINS){value.toShort()},index+1L,captured),
                pingSource=SonarPingSource.OWN)
            controller.refresh()
        }
        fun flushRateCache(){now+=500;controller.refresh()}
        fun image():Bitmap=Bitmap.createBitmap(1000,512,Bitmap.Config.ARGB_8888).also {
            renderer.drawPreview(Canvas(it),controller,false,RectF(0f,0f,1000f,512f))
        }
        fun uploaded(slot:Int,bin:Int):Int {
            renderer.readUploadedPixels(atlasPixels)
            val columns=controller.model.viewImageColumns
            val x=if(columns.aggregated)slot%2048 else slot
            val y=if(columns.aggregated)(slot/2048)*256+(bin*255.0/1023).roundToInt()else bin
            return atlasPixels[y*SONAR_CAPACITY+x]
        }
        fun rawColor(raw:Int):Int {
            val d=controller.model.viewDisplay
            return SonarNativeMath.palette(controller.model.displayAmplitude(raw,4095,d),d.palette,false)
        }
        fun intensityColor(code:Int)=SonarNativeMath.palette(code/65535.0,controller.model.viewDisplay.palette,false)
        override fun close(){renderer.dispose();controller.dispose()}
    }

    @Test fun highGainReceivedImpulseBecomes9362IntensityAndDarkNativePixelWithoutChangingRawEvidence() {
        val f=Fixture()
        try {
            for(index in 0..6)f.append(index,if(index==3)4095 else 0)
            f.flushRateCache()
            val c=f.controller;val original=c.model.ring.atAge(3)!!.samples
            val revision=c.model.ring.version;val depth=c.model.depth();val rate=c.model.measuredRate()
            val slot=c.model.ring.storageSlotAt(3)
            val nearest=f.image()
            try{assertEquals(f.rawColor(4095),f.uploaded(slot,500));save(nearest,"raw-impulse-nearest")}finally{nearest.recycle()}
            val amplitudes=SonarDisplaySmoothingCache()
            val preparedMean=try {
                assertTrue(amplitudes.begin(c.model.viewImageColumns,epoch,c.model.viewDisplay));amplitudes.prepare(3)
                assertTrue(amplitudes.qualifiedAt(3));amplitudes.intensityCodeAt(3,500).also{assertEquals(dimSeventhCode,it)}
            }finally{amplitudes.release()}
            c.interpolation(true)
            val smoothed=f.image()
            try {
                val actual=f.uploaded(slot,500)
                assertEquals(actualHighGainDb,c.model.viewDisplay.gainDb,0.0)
                assertEquals(dimSeventhColor,f.intensityColor(dimSeventhCode))
                assertEquals("Intensity is averaged after transfer; gain must not saturate it again",dimSeventhColor,actual)
                assertNotEquals("The old ADC mean followed by this high gain saturates",f.rawColor(4095),actual)
                assertNotEquals("The selected non-linear transfer distinguishes colour averaging",
                    meanColor(f.rawColor(4095),f.rawColor(0)),actual)
                assertTrue(f.renderer.preparedSmoothedColumns>0)
                save(smoothed,"raw-impulse-linear")
                result("raw-impulse",JSONObject().put("inputCenter",4095).put("zeroNeighbours",6)
                    .put("gainDb",actualHighGainDb).put("gamma",1).put("threshold",0)
                    .put("expectedMeanIntensityCode",dimSeventhCode).put("actualPreparedMeanIntensityCode",preparedMean)
                    .put("uploadedColor",actual).put("expectedPaletteColor",dimSeventhColor)
                    .put("sourceRingVersion",revision).put("imageSha256",digest(pixels(smoothed))))
            }finally{smoothed.recycle()}
            assertEquals(revision,c.model.ring.version);assertEquals(depth,c.model.depth())
            assertEquals(rate,c.model.measuredRate());assertArrayEquals(original,c.model.ring.atAge(3)!!.samples)
        }finally{f.close()}
    }

    @Test fun exactProfilesBypassSmoothingAndRestore4095WithLinearPreferenceStillEnabled() {
        val f=Fixture()
        try {
            for(index in 0..6)f.append(index,if(index==3)4095 else 0)
            val c=f.controller;c.interpolation(true)
            val smoothed=f.image()
            try{assertEquals(dimSeventhColor,f.uploaded(c.model.ring.storageSlotAt(3),500))}finally{smoothed.recycle()}
            val prepared=f.renderer.preparedSmoothedColumns
            c.exactPingProfiles(true)
            assertTrue(c.linearInterpolation);assertTrue(c.model.viewDisplay.exactPingProfiles)
            val exact=f.image()
            try {
                assertEquals(f.rawColor(4095),f.uploaded(c.model.ring.storageSlotAt(3),500))
                assertEquals("Exact does not call the smoothing cache",prepared,f.renderer.preparedSmoothedColumns)
                assertFalse(c.model.imageHistoryAggregated)
                c.interpolation(false)
                val nearest=f.image()
                try{assertArrayEquals("Exact is independent of the saved linear choice",pixels(exact),pixels(nearest))}finally{nearest.recycle()}
                assertEquals(prepared,f.renderer.preparedSmoothedColumns)
                save(exact,"exact-original-impulse")
                result("exact-bypass",JSONObject().put("expectedOriginalAmplitude",4095)
                    .put("uploadedColor",f.uploaded(c.model.ring.storageSlotAt(3),500))
                    .put("preparedColumnsBeforeExact",prepared).put("preparedColumnsAfterExact",f.renderer.preparedSmoothedColumns)
                    .put("exactPingProfiles",true).put("imageSha256",digest(pixels(exact))))
            }finally{exact.recycle()}
        }finally{f.close()}
    }

    @Test fun longHistoryMeansSevenRealImageColumnsWhileOriginalPeakAndPingRingStayUnchanged() {
        val f=Fixture(120.0)
        try {
            // Explicit accelerated fixture clock: 300 received profiles, one per 300-ms bucket.
            // Three actual newer image columns exist after the isolated bright column.
            val centerIndex=296
            for(index in 0 until 300)f.append(index,if(index==centerIndex)4095 else 0,300)
            val c=f.controller;val columns=c.model.viewImageColumns
            assertTrue("Exercise the actual model-selected long-history path",c.model.imageHistoryAggregated)
            assertTrue(columns.aggregated);assertEquals(300,c.model.ring.count)
            val centerTime=1_000_000L+centerIndex*300
            val age=(0 until columns.count).first{columns.occupiedAt(it)&&columns.firstTimestampAt(it)==centerTime}
            assertNull("Image aggregates never acquire fictitious ping IDs",columns.measuredPingIdAt(age))
            val revision=columns.version;val peak=columns.sampleAt(age,500)
            val original=c.model.ring.atAge(3)!!.samples
            assertEquals(4095,peak)
            val amplitudes=SonarDisplaySmoothingCache()
            val preparedMean=try {
                assertTrue(amplitudes.begin(columns,epoch,c.model.viewDisplay));amplitudes.prepare(age)
                assertTrue(amplitudes.qualifiedAt(age));amplitudes.intensityCodeAt(age,500).also{assertEquals(dimSeventhCode,it)}
            }finally{amplitudes.release()}
            c.interpolation(true)
            val image=f.image()
            try {
                assertEquals(actualHighGainDb,c.model.viewDisplay.gainDb,0.0)
                assertEquals(dimSeventhColor,f.uploaded(columns.storageSlotAt(age),500))
                assertEquals(revision,columns.version);assertEquals(peak,columns.sampleAt(age,500))
                assertArrayEquals(original,c.model.ring.atAge(3)!!.samples)
                save(image,"long-history-seven-image-columns")
                result("seven-image-columns",JSONObject().put("aggregated",true).put("binDurationMs",300)
                    .put("averagedImageColumns",7).put("claimSevenOriginalPings",false)
                    .put("unchangedStoredPeak",peak).put("gainDb",actualHighGainDb).put("gamma",1).put("threshold",0)
                    .put("expectedMeanIntensityCode",dimSeventhCode).put("actualPreparedMeanIntensityCode",preparedMean)
                    .put("expectedPaletteColor",dimSeventhColor).put("uploadedColor",f.uploaded(columns.storageSlotAt(age),500))
                    .put("sourceImageVersion",revision).put("imageSha256",digest(pixels(image))))
            }finally{image.recycle()}
        }finally{f.close()}
    }

    @Test fun frozenSmoothedAndroidBitmapStaysIdenticalWhileSyntheticAcquisitionAndRateContinue() {
        val f=Fixture()
        try {
            for(index in 0..6)f.append(index,if(index==3)4095 else 0)
            f.flushRateCache()
            val c=f.controller;c.interpolation(true);c.freeze()
            assertTrue(c.model.display.freeze)
            val held=f.image()
            try {
                val hash=digest(pixels(held));val original=c.model.viewRing.atAge(3)!!.samples
                val rate=c.model.measuredRate().eventCount;val depth=c.model.depth()
                for(index in 7..10)f.append(index,3000)
                c.interpolation(false)
                assertTrue("A frozen image rejects a smoothing preference change",c.linearInterpolation)
                val continued=f.image()
                try {
                    assertArrayEquals(pixels(held),pixels(continued));assertEquals(hash,digest(pixels(continued)))
                    assertEquals(0,f.renderer.mainUploadedColumns)
                    assertEquals(7,c.model.viewRing.count);assertEquals(11,c.model.ring.count)
                    assertArrayEquals(original,c.model.viewRing.atAge(3)!!.samples);assertEquals(depth,c.model.depth())
                    f.flushRateCache();assertEquals(rate+4,c.model.measuredRate().eventCount)
                    save(held,"freeze-held");save(continued,"freeze-live-acquisition-continues")
                    result("freeze",JSONObject().put("heldImageSha256",hash)
                        .put("continuedImageSha256",digest(pixels(continued))).put("frozenProfiles",7)
                        .put("liveProfiles",11).put("additionalReceivedEvents",4))
                }finally{continued.recycle()}
            }finally{held.recycle()}
        }finally{f.close()}
    }

    @Test fun longHistoryUsesSixActualPingsPerBucketBeforeSevenColumnMeanWithoutChangingPeakOrExactEvidence() {
        val f=Fixture(120.0);val expectedCode=10923;val expectedColor=0xff013775.toInt()
        try {
            // First capture is aligned to the real300-ms bucket. No artificial peak-only source.
            for(index in 4 until 4+48*6)f.append(index,if((index-4)%6==0)4095 else 0,50)
            val c=f.controller;val columns=c.model.viewImageColumns
            assertTrue(c.model.imageHistoryAggregated);assertTrue(columns.aggregated)
            assertEquals(288,c.model.ring.count)
            val centerIndex=4+44*6;val centerTime=1_000_000L+centerIndex*50
            val age=(0 until columns.count).first{columns.occupiedAt(it)&&columns.firstTimestampAt(it)==centerTime}
            assertEquals(6L,columns.contributionCountAt(age));assertEquals(centerTime+250,columns.lastTimestampAt(age))
            assertEquals(4095,columns.sampleAt(age,500));assertNull(columns.measuredPingIdAt(age))
            val rawAge=(0 until c.model.ring.count).first{c.model.ring.pingIdAt(it)==centerIndex+1L}
            val raw=c.model.ring.atAge(rawAge)!!.samples
            val rawVersion=c.model.ring.version;val peakVersion=columns.version;val slot=columns.storageSlotAt(age)
            val nearest=f.image()
            try{assertEquals(f.rawColor(4095),f.uploaded(slot,500));save(nearest,"six-pings-per-bucket-nearest-peak")}finally{nearest.recycle()}
            val cache=SonarDisplaySmoothingCache()
            val actualCode=try {
                assertTrue(cache.begin(columns,epoch,c.model.viewDisplay));cache.prepare(age)
                assertTrue(cache.qualifiedAt(age));cache.intensityCodeAt(age,500).also{assertEquals(expectedCode,it)}
            }finally{cache.release()}
            c.interpolation(true);val smoothed=f.image()
            try {
                assertEquals(expectedColor,f.intensityColor(expectedCode));assertEquals(expectedColor,f.uploaded(slot,500))
                assertNotEquals("MAX peak aggregates are not the received ping mean",f.rawColor(4095),f.uploaded(slot,500))
                assertEquals(peakVersion,columns.version);assertEquals(4095,columns.sampleAt(age,500))
                assertEquals(rawVersion,c.model.ring.version);assertArrayEquals(raw,c.model.ring.atAge(rawAge)!!.samples)
                save(smoothed,"six-pings-per-bucket-intensity-mean")
                result("six-pings-per-bucket",JSONObject().put("aggregated",true).put("binDurationMs",300)
                    .put("actualReceivedPingsPerBucket",6).put("brightReceivedPingsPerBucket",1)
                    .put("averagedImageColumns",7).put("claimSevenOriginalPings",false)
                    .put("expectedMeanIntensityCode",expectedCode).put("actualPreparedMeanIntensityCode",actualCode)
                    .put("expectedPaletteColor",expectedColor).put("uploadedColor",f.uploaded(slot,500))
                    .put("unchangedStoredPeak",4095).put("sourceImageVersion",peakVersion)
                    .put("sourceRingVersion",rawVersion).put("gainDb",actualHighGainDb).put("gamma",1).put("threshold",0)
                    .put("imageSha256",digest(pixels(smoothed))))
            }finally{smoothed.recycle()}
            val prepared=f.renderer.preparedSmoothedColumns;c.exactPingProfiles(true)
            val exact=f.image()
            try {
                assertFalse(c.model.imageHistoryAggregated);assertEquals(f.rawColor(4095),f.uploaded(c.model.ring.storageSlotAt(rawAge),500))
                assertEquals(prepared,f.renderer.preparedSmoothedColumns)
                assertArrayEquals(raw,c.model.ring.atAge(rawAge)!!.samples)
                save(exact,"six-pings-per-bucket-exact-original")
                result("six-pings-per-bucket-exact",JSONObject().put("exactPingProfiles",true)
                    .put("expectedOriginalAmplitude",4095).put("uploadedColor",f.uploaded(c.model.ring.storageSlotAt(rawAge),500))
                    .put("preparedColumnsBeforeExact",prepared).put("preparedColumnsAfterExact",f.renderer.preparedSmoothedColumns)
                    .put("imageSha256",digest(pixels(exact))))
            }finally{exact.recycle()}
        }finally{f.close()}
    }

    private fun meanColor(bright:Int,zero:Int):Int {
        fun channel(shift:Int)=(((bright ushr shift)and 255)+6*((zero ushr shift)and 255)+3)/7
        return (255 shl 24)or(channel(16)shl 16)or(channel(8)shl 8)or channel(0)
    }
    private fun pixels(image:Bitmap)=IntArray(image.width*image.height).also{image.getPixels(it,0,image.width,0,0,image.width,image.height)}
    private fun digest(values:IntArray):String {
        val md=MessageDigest.getInstance("SHA-256");val bytes=ByteArray(4)
        for(value in values){bytes[0]=(value ushr 24).toByte();bytes[1]=(value ushr 16).toByte();bytes[2]=(value ushr 8).toByte();bytes[3]=value.toByte();md.update(bytes)}
        return md.digest().joinToString(""){"%02x".format(it.toInt()and 255)}
    }
    private fun output()=File(context.getExternalFilesDir(null)?:context.cacheDir,"synthetic-sonar-smoothing-device-proof/$proofId").apply{mkdirs()}
    private fun save(image:Bitmap,name:String){File(output(),"$name.png").outputStream().use{assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))}}
    private fun result(name:String,details:JSONObject) {
        val info=context.packageManager.getPackageInfo(context.packageName,0)
        val report=JSONObject().put("schema","no5.sonar-smoothing-device-test/1").put("scenario",name)
            .put("proofId",proofId)
            .put("scope","DETERMINISTIC_IN_MEMORY_ANDROID_CANVAS_ONLY").put("liveSonarProven",false)
            .put("displayedSurfaceFrameProven",false).put("gpuTimeProven",false)
            .put("targetPackage",context.packageName).put("targetVersionCode",info.longVersionCode)
            .put("targetVersionName",info.versionName).put("details",details)
        val file=File(output(),"$name.json");file.writeText(report.toString(2)+"\n")
        instrumentation.sendStatus(0,Bundle().apply{
            putString("no5SmoothingScenario",name);putString("no5SmoothingScope","DETERMINISTIC_IN_MEMORY_ANDROID_CANVAS_ONLY")
            putString("no5SmoothingReport",file.absolutePath)
        })
    }
}
