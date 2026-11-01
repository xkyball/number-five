package com.no5.helm.gpu

import com.no5.domain.*
import com.no5.helm.SonarDisplaySmoothingCache
import kotlin.math.floor
import kotlin.math.roundToInt

/** Recomputable display colors, never another RAW/mean history. One reusable 8-KiB
 * upload row; all retained pair colors live in a capacity-bounded GPU texture. */
internal object SonarGpuTemporalPairs {
    data class Geometry(val spacing:Double,val oldBottom:Double,val newBottom:Double,val oldColor:Int,val newColor:Int)
    private fun bottom(r:SonarRingBuffer,age:Int):Double {
        val p=r.presentationAt(age)?:return Double.NaN
        val b=r.bottomAt(age);val s=r.metersPerBinValueAt(age)
        if(p.quality!=SonarAnalysisQuality.LOCKED||p.pingId!=r.pingIdAt(age)||!b.validated||b.source==null||!s.isFinite())return Double.NaN
        val depth=b.bin!!*s
        return if(depth>0&&depth.isFinite())depth else Double.NaN
    }
    fun anchored(r:SonarRingBuffer,newer:Int):Boolean {
        val a=bottom(r,newer+1);val b=bottom(r,newer)
        val coverage=minOf(r.metersPerBinValueAt(newer),r.metersPerBinValueAt(newer+1))*1023
        return a.isFinite()&&b.isFinite()&&a<coverage&&b<coverage&&a!=b
    }
    fun prepare(r:SonarRingBuffer,newer:Int,colors:IntArray,smoothing:SonarDisplaySmoothingCache,out:IntArray,codes:ShortArray,nativeExact:Boolean=false):Geometry {
        require(out.size==2048&&codes.size==2048)
        try {
        val older=newer+1;val oldSpacing=r.metersPerBinValueAt(older);val newSpacing=r.metersPerBinValueAt(newer)
        val spacing=if(newSpacing.isNaN())Double.NaN else minOf(newSpacing,oldSpacing)
        val anchored=anchored(r,newer);val a=if(anchored)bottom(r,older)else Double.NaN;val b=if(anchored)bottom(r,newer)else Double.NaN
        val reference=if(anchored)(a+b)/2 else 0.0
        smoothing.copyRawCodes(older,codes,0);smoothing.copyRawCodes(newer,codes,1024)
        if(nativeExact){
            val packed=SonarGpuNative.pairColorsExact(codes,colors,out,oldSpacing,newSpacing,spacing,a,b)
            return Geometry(spacing,a,b,(packed ushr 32).toInt(),packed.toInt())
        }
        fun color(age:Int,bin:Double):Int {
            val low=floor(bin).toInt().coerceIn(0,1023);val high=(low+1).coerceAtMost(1023)
            val weight=(bin-low).coerceIn(0.0,1.0)
            val offset=if(age==older)0 else 1024
            val first=codes[offset+low].toInt()and 65535;val second=codes[offset+high].toInt()and 65535
            return colors[(first*(1-weight)+second*weight).roundToInt().coerceIn(0,65535)]
        }
        for(bin in 0 until 1024){
            val x=when {spacing.isNaN()->bin.toDouble();anchored->(bin*spacing+a-reference)/oldSpacing;else->bin*(spacing/oldSpacing)}
            val y=when {spacing.isNaN()->bin.toDouble();anchored->(bin*spacing+b-reference)/newSpacing;else->bin*(spacing/newSpacing)}
            if(x !in 0.0..1023.0||y !in 0.0..1023.0){out[bin*2]=0;out[bin*2+1]=0}
            else{out[bin*2]=color(older,x);out[bin*2+1]=color(newer,y)}
        }
        return Geometry(spacing,a,b,if(anchored)color(older,a/oldSpacing)else 0,if(anchored)color(newer,b/newSpacing)else 0)
        }finally{codes.fill(0)}
    }
}
