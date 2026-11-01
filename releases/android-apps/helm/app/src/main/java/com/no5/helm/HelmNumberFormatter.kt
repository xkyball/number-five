package com.no5.helm

import java.util.Locale
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

/** Cache visible decimal bins, preserving Java Formatter including signed zero and halfway cases. */
internal object HelmNumberFormatter {
    private data class Key(val quantized:Long,val negative:Boolean,val decimals:Int)
    private val cache=object:LinkedHashMap<Key,String>(256,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Key,String>?)=size>256
    }
    private val powers=doubleArrayOf(1.0,10.0,100.0,1000.0,10000.0,100000.0,1000000.0)
    @Synchronized fun format(value:Double,decimals:Int):String {
        // Outside normal instrument magnitudes, preserve the full Formatter contract directly.
        if(!value.isFinite() || decimals !in powers.indices || abs(value)*powers[decimals]>=1e14)
            return String.format(Locale.US,"%.${decimals}f",value)
        val scaled=abs(value)*powers[decimals]
        val lower=floor(scaled)
        val nearHalf=abs(scaled-lower-.5)<=Math.ulp(scaled)*2
        val rounded=if(nearHalf) BigDecimal.valueOf(abs(value)).setScale(decimals,RoundingMode.HALF_UP)
            .movePointRight(decimals).longValueExact() else floor(scaled+.5).toLong()
        val key=Key(rounded,java.lang.Double.doubleToRawLongBits(value)<0,decimals)
        return cache[key] ?: String.format(Locale.US,"%.${decimals}f",value).also { cache[key]=it }
    }
    @Synchronized internal fun retainedEntries()=cache.size
}
