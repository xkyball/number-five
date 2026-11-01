package com.no5.helm

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Every uint16 color is compared to the unchanged domain transfer, not to a duplicated formula. */
class SonarTransferLutTest {
    private val original=SonarModel()
    private val settings=listOf(
        SonarDisplay(gainDb=-24.0,gamma=.2,threshold=.95),
        SonarDisplay(gainDb=-24.0,gamma=3.0,threshold=0.0),
        SonarDisplay(gainDb=-6.0,gamma=.65,threshold=.15),
        SonarDisplay(gainDb=0.0,gamma=1.0,threshold=0.0),
        SonarDisplay(gainDb=12.0,gamma=1.8,threshold=.3),
        SonarDisplay(gainDb=36.0,gamma=3.0,threshold=.95))
    private fun reference(out:IntArray,scale:Int,d:SonarDisplay,night:Boolean) {
        for(raw in out.indices)out[raw]=SonarNativeMath.palette(original.displayAmplitude(raw,scale,d),d.palette,night)
    }
    @Test fun allUint16PixelsMatchTheOriginalAcrossRepresentativeAndExtremeTransfers() {
        val expected=IntArray(65536);val actual=IntArray(65536);val lut=SonarTransferLut()
        var comparisons=0
        for(scale in listOf(1,255,4095,65535))for(setting in settings)for(palette in SonarPalette.entries)for(night in listOf(false,true)) {
            val d=setting.copy(palette=palette)
            reference(expected,scale,d,night);lut.fill(actual,scale,d,night)
            assertArrayEquals("Exact ARGB parity scale=$scale gain=${d.gainDb} gamma=${d.gamma} threshold=${d.threshold} palette=$palette night=$night",expected,actual)
            assertEquals(65536,lut.evaluatedEntries+lut.zeroEntries+lut.saturatedEntries)
            comparisons++
        }
        assertEquals(192,comparisons)
    }
    @Test fun intensityLutMatchesTheSameDomainTransferBeforeAnyColourMapping() {
        val values=FloatArray(65536);val lut=SonarTransferLut()
        for(scale in listOf(1,255,4095,65535))for(d in settings) {
            lut.fillIntensities(values,scale,d)
            for(raw in values.indices)assertEquals(
                "Intensity parity scale=$scale gain=${d.gainDb} gamma=${d.gamma} threshold=${d.threshold} raw=$raw",
                original.displayAmplitude(raw,scale,d).toFloat(),values[raw],0f)
        }
    }
    @Test fun intensityPaletteDoesNotApplyGainThresholdOrGammaAgain() {
        val colors=IntArray(65536);val lut=SonarTransferLut()
        for(palette in SonarPalette.entries)for(night in listOf(false,true)) {
            lut.fillIntensityColors(colors,palette,night)
            for(code in colors.indices)assertEquals(
                SonarNativeMath.palette(code/65535.0,palette,night),colors[code])
        }
        lut.fillIntensityColors(colors,SonarPalette.SUN_HIGH_CONTRAST,false)
        // One saturated displayed neighbour and six black ones: 1/7 intensity,
        // even with the observed +24.14 dB transfer applied to each neighbour.
        assertEquals(0xff023269.toInt(),colors[9362])
        assertNotEquals(colors[65535],colors[9362])
    }
    @Test fun negativeGainDoesNotTreatUnexpectedValuesAbove4095AsSaturated() {
        val d=SonarDisplay(gainDb=-24.0,gamma=1.0,threshold=0.0,palette=SonarPalette.MONO_AMBER)
        val colors=IntArray(65536);val lut=SonarTransferLut();lut.fill(colors,4095,d,false)
        assertNotEquals(SonarNativeMath.palette(1.0,d.palette,false),colors[4096])
        assertEquals(SonarNativeMath.palette(original.displayAmplitude(4096,4095,d),d.palette,false),colors[4096])
        assertEquals(SonarNativeMath.palette(original.displayAmplitude(65535,4095,d),d.palette,false),colors[65535])
        assertTrue("A genuine nonconstant uint16 tail is evaluated, not discarded",lut.evaluatedEntries>4096)
    }
    @Test fun hostCostSampleReportsOperationsAndExactParityWithoutAPerformanceThreshold() {
        val expected=IntArray(65536);val actual=IntArray(65536);val lut=SonarTransferLut()
        val d=SonarDisplay(gainDb=12.0,gamma=1.8,threshold=.3,palette=SonarPalette.SUN_HIGH_CONTRAST)
        repeat(2){reference(expected,4095,d,false);lut.fill(actual,4095,d,false)}
        val oldTimes=LongArray(5);val newTimes=LongArray(5)
        repeat(5){i->
            var start=System.nanoTime();reference(expected,4095,d,false);oldTimes[i]=System.nanoTime()-start
            start=System.nanoTime();lut.fill(actual,4095,d,false);newTimes[i]=System.nanoTime()-start
            assertArrayEquals(expected,actual)
        }
        assertTrue("Proven saturation avoids almost the entire nonconstant tail",lut.evaluatedEntries<4096)
        val file=File(System.getProperty("no5.renderOutputDir","build"),"transfer-lut-cost-host.json")
        file.parentFile!!.mkdirs()
        file.writeText("""{"scope":"HOST_MICROBENCHMARK_ONLY_NO_DEVICE_CAUSALITY","samples":5,"referenceNs":${oldTimes.joinToString(prefix="[",postfix="]")},"optimizedNs":${newTimes.joinToString(prefix="[",postfix="]")},"referenceEntries":65536,"optimizedGammaEntries":${lut.evaluatedEntries},"zeroFilled":${lut.zeroEntries},"saturatedFilled":${lut.saturatedEntries},"pixelParity":true,"performanceAssertion":false}"""+"\n")
    }
}
