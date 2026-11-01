package com.no5.helm.gpu
import android.view.Surface

/** No software path. Each handle owns only Sonar EGL/context/textures. */
internal object BaselineGpuNative {
    init { System.loadLibrary("sonar_quiet_baseline") }
    external fun create(surface: Surface, capacity: Int): Long
    external fun destroy(handle: Long)
    external fun upload(handle: Long, firstSlot: Int, count: Int, samples: ShortArray)
    external fun metadataRows(handle: Long, firstSlot: Int, count: Int, values: FloatArray)
    external fun configure(handle: Long, gainDb: Float, gamma: Float, threshold: Float, palette: IntArray)
    external fun effects(handle: Long, scope: Boolean, bottom: Boolean, trackBins: FloatArray)
    external fun metrics(handle: Long): LongArray
    external fun intensity(handle: Long, firstSlot: Int, count: Int, samples: ShortArray)
    external fun temporalPair(handle:Long,slot:Int,colors:IntArray,spacing:Double,oldBottom:Double,newBottom:Double,oldColor:Int,newColor:Int)
    external fun plot(handle:Long,left:Float,top:Float,right:Float,bottom:Float,designWidth:Float)
    external fun viewportInfo(handle:Long,output:FloatArray)
    external fun rasterScale(handle: Long, value: Float)
    external fun metadata(handle: Long, values: FloatArray)
    external fun geometry(handle:Long, values:DoubleArray)
    external fun lut(handle: Long, raw: IntArray, intensityColors: IntArray, transfer: FloatArray)
    external fun draw(handle: Long, count: Int, head: Int, newest: Float, seconds: Float,
        top: Float, bottom: Float, meters: Float, mode: Int, smooth: Int, background: Int): Long
    external fun dimensions(handle: Long, out: IntArray)
    /** Bounded pure CPU arithmetic, no EGL/driver or retained raw arrays. */
    external fun mean7Exact(profiles:ShortArray,intensities:FloatArray,destination:ShortArray,offset:Int)
    external fun pairColorsExact(codes:ShortArray,colors:IntArray,output:IntArray,oldSpacing:Double,newSpacing:Double,spacing:Double,oldBottom:Double,newBottom:Double):Long
    external fun resources(): LongArray
}
