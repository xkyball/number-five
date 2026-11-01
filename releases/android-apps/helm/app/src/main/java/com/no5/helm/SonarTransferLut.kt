package com.no5.helm

import com.no5.domain.SonarDisplay
import com.no5.domain.SonarPalette
import kotlin.math.pow

/** Exact uint16 display transfer. Constant ranges are proved using the original Double formula. */
internal open class SonarTransferLut {
    var evaluatedEntries=0;private set
    var zeroEntries=0;private set
    var saturatedEntries=0;private set
    open fun fill(colors:IntArray,fullScale:Int,display:SonarDisplay,night:Boolean) {
        require(colors.size==65536&&fullScale in 1..65535)
        // Preserve raw / fullScale * multiplier and the original threshold division:
        // replacing either division with multiplication by its inverse can alter pixels.
        val scale=fullScale.toDouble()
        val multiplier=10.0.pow(display.gainDb/20)
        val inverseGamma=1/display.gamma
        val threshold=display.threshold
        val span=1-threshold
        val positive=firstMatching(scale,multiplier,threshold,span,false)
        val saturated=firstMatching(scale,multiplier,threshold,span,true)
        val zeroColor=SonarNativeMath.palette(0.0,display.palette,night)
        val saturatedColor=SonarNativeMath.palette(1.0,display.palette,night)
        java.util.Arrays.fill(colors,0,positive,zeroColor)
        for(raw in positive until saturated) {
            val value=normalized(raw,scale,multiplier,threshold,span).pow(inverseGamma)
            colors[raw]=SonarNativeMath.palette(value,display.palette,night)
        }
        java.util.Arrays.fill(colors,saturated,colors.size,saturatedColor)
        evaluatedEntries=saturated-positive;zeroEntries=positive;saturatedEntries=colors.size-saturated
    }
    /** Complete display transfer before image smoothing; no colour-space averaging. */
    open fun fillIntensities(values:FloatArray,fullScale:Int,display:SonarDisplay) {
        require(values.size==65536&&fullScale in 1..65535)
        val scale=fullScale.toDouble()
        val multiplier=10.0.pow(display.gainDb/20)
        val inverseGamma=1/display.gamma
        val threshold=display.threshold
        val span=1-threshold
        val positive=firstMatching(scale,multiplier,threshold,span,false)
        val saturated=firstMatching(scale,multiplier,threshold,span,true)
        java.util.Arrays.fill(values,0,positive,0f)
        for(raw in positive until saturated)
            values[raw]=normalized(raw,scale,multiplier,threshold,span).pow(inverseGamma).toFloat()
        java.util.Arrays.fill(values,saturated,values.size,1f)
    }
    /** UInt16 encodes already transformed intensity. Never apply gain a second time. */
    open fun fillIntensityColors(colors:IntArray,palette:SonarPalette,night:Boolean) {
        require(colors.size==65536)
        for(code in colors.indices)colors[code]=SonarNativeMath.palette(code/65535.0,palette,night)
    }
    private fun normalized(raw:Int,scale:Double,multiplier:Double,threshold:Double,span:Double):Double =
        ((raw/scale*multiplier-threshold)/span).coerceIn(0.0,1.0)
    /** Monotonic transfer; 65536 is the sentinel for an empty constant tail. */
    private fun firstMatching(scale:Double,multiplier:Double,threshold:Double,span:Double,saturated:Boolean):Int {
        var low=0;var high=65536
        while(low<high) {
            val middle=(low+high) ushr 1
            val value=normalized(middle,scale,multiplier,threshold,span)
            val matches=if(saturated)value>=1.0 else value>0.0
            if(matches)high=middle else low=middle+1
        }
        return low
    }
}
