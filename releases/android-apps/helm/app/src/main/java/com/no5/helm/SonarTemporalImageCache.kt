package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.LinearGradient
import android.graphics.Shader
import com.no5.domain.*
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.min

/** Two physical-neighbour columns per slot; image pixels only, never inferred pings/depths. */
internal class SonarTemporalImageCache(
    private val allocate:()->Bitmap={Bitmap.createBitmap(2048,2048,Bitmap.Config.ARGB_8888)}
) {
    private var atlas:Bitmap?=null
    private var source:SonarRingBuffer?=null
    private var epoch:SonarEpoch?=null
    private var transfer=""
    private val valid=BooleanArray(SONAR_CAPACITY)
    private val newerIds=LongArray(SONAR_CAPACITY)
    private val olderIds=LongArray(SONAR_CAPACITY)
    private val newerTimes=LongArray(SONAR_CAPACITY)
    private val olderTimes=LongArray(SONAR_CAPACITY)
    private val segments=LongArray(SONAR_CAPACITY)
    private val newerSampleVersions=LongArray(SONAR_CAPACITY)
    private val olderSampleVersions=LongArray(SONAR_CAPACITY)
    private val spacings=DoubleArray(SONAR_CAPACITY)
    private val oldBottoms=DoubleArray(SONAR_CAPACITY)
    private val newBottoms=DoubleArray(SONAR_CAPACITY)
    private val ridgeColors=IntArray(SONAR_CAPACITY)
    private val ridgeGradients=arrayOfNulls<LinearGradient>(SONAR_CAPACITY)
    private val pairPixels=IntArray(SONAR_BINS*2)
    var preparedPairs=0L; private set
    var prepareNanos=0L; private set
    var prepareMisses=0L; private set
    var unavailable=false; private set
    val allocated:Boolean get()=atlas!=null
    val bitmap:Bitmap? get()=atlas

    fun begin(ring:SonarRingBuffer,currentEpoch:SonarEpoch?,transferKey:String):Boolean {
        if(unavailable)return false
        if(atlas?.isRecycled==true){release(false);return false}
        if(atlas==null)try {atlas=allocate()}catch(_:OutOfMemoryError){release(false);return false}
        if(source!==ring || epoch!=currentEpoch || transfer!=transferKey) {
            valid.fill(false);source=ring;epoch=currentEpoch;transfer=transferKey
        }
        return true
    }
    /** No interpolation across unproven source identity, observed loss or a real time gap. */
    fun compatible(ring:SonarRingBuffer,newerAge:Int):Boolean {
        if(newerAge+1>=ring.count)return false
        val olderAge=newerAge+1
        val source=ring.pingSourceAt(newerAge)
        if(source==null || source==SonarPingSource.ALL || source!=ring.pingSourceAt(olderAge))return false
        if(ring.imageSegmentAt(newerAge)!=ring.imageSegmentAt(olderAge))return false
        val elapsed=ring.timestampAt(newerAge)-ring.timestampAt(olderAge)
        if(elapsed !in 1..500)return false
        if(ring.geometryAt(newerAge)?.txReference!=ring.geometryAt(olderAge)?.txReference ||
            ring.amplitudeFullScaleAt(newerAge)!=ring.amplitudeFullScaleAt(olderAge))return false
        val amplitude=ring.amplitudeContextAt(newerAge)
        if(amplitude?.usable==false||amplitude!=ring.amplitudeContextAt(olderAge))return false
        val newer=ring.metersPerBinValueAt(newerAge);val older=ring.metersPerBinValueAt(olderAge)
        return newer.isFinite() && older.isFinite() || newer.isNaN() && older.isNaN()
    }
    private fun confirmedBottom(ring:SonarRingBuffer,age:Int):Double {
        val presentation=ring.presentationAt(age)?:return Double.NaN
        val bottom=ring.bottomAt(age)
        val meters=ring.metersPerBinValueAt(age)
        if(presentation.quality!=SonarAnalysisQuality.LOCKED || presentation.pingId!=ring.pingIdAt(age) ||
            !bottom.validated || bottom.source==null || !meters.isFinite())return Double.NaN
        val depth=bottom.bin!!*meters
        return if(depth>0 && depth.isFinite())depth else Double.NaN
    }
    /** Only two actual canonical depth selections may register the IMAGE profiles. */
    fun hasBottomAnchors(ring:SonarRingBuffer,newerAge:Int):Boolean {
        if(!compatible(ring,newerAge))return false
        val old=confirmedBottom(ring,newerAge+1);val next=confirmedBottom(ring,newerAge)
        if(!old.isFinite() || !next.isFinite())return false
        val coverage=min(ring.metersPerBinValueAt(newerAge),ring.metersPerBinValueAt(newerAge+1))*1023
        return old<coverage && next<coverage && old!=next
    }
    fun prepare(ring:SonarRingBuffer,newerAge:Int,colors:IntArray,smoothing:SonarDisplaySmoothingCache?=null):Int {
        val olderAge=newerAge+1
        val slot=(ring.head-1-newerAge+ring.capacity)%ring.capacity
        val newerId=ring.pingIdAt(newerAge);val olderId=ring.pingIdAt(olderAge)
        val newerTime=ring.timestampAt(newerAge);val olderTime=ring.timestampAt(olderAge)
        val segment=ring.imageSegmentAt(newerAge)
        val newerSample=smoothing?.prepare(newerAge)?:newerId;val olderSample=smoothing?.prepare(olderAge)?:olderId
        if(valid[slot] && newerIds[slot]==newerId && olderIds[slot]==olderId && newerTimes[slot]==newerTime && olderTimes[slot]==olderTime && segments[slot]==segment&&newerSampleVersions[slot]==newerSample&&olderSampleVersions[slot]==olderSample)return slot
        prepareMisses++;val prepareStarted=System.nanoTime()
        try {
        val newerSpacing=ring.metersPerBinValueAt(newerAge);val olderSpacing=ring.metersPerBinValueAt(olderAge)
        // Only the intersection has measured samples at BOTH ends of the interval.
        val spacing=if(newerSpacing.isNaN())Double.NaN else min(newerSpacing,olderSpacing)
        val anchored=hasBottomAnchors(ring,newerAge)
        val oldBottom=if(anchored)confirmedBottom(ring,olderAge)else Double.NaN
        val newBottom=if(anchored)confirmedBottom(ring,newerAge)else Double.NaN
        val reference=if(anchored)(oldBottom+newBottom)/2 else 0.0
        for(bin in 0 until SONAR_BINS) {
            val oldBin=when {spacing.isNaN()->bin.toDouble();anchored->(bin*spacing+oldBottom-reference)/olderSpacing;else->bin*(spacing/olderSpacing)}
            val newBin=when {spacing.isNaN()->bin.toDouble();anchored->(bin*spacing+newBottom-reference)/newerSpacing;else->bin*(spacing/newerSpacing)}
            if(oldBin !in 0.0..1023.0 || newBin !in 0.0..1023.0) {
                // Neither endpoint extrapolates a sample beyond either capture.
                pairPixels[bin*2]=0;pairPixels[bin*2+1]=0
            } else {
                pairPixels[bin*2]=sampleColor(ring,olderAge,oldBin,colors,smoothing)
                pairPixels[bin*2+1]=sampleColor(ring,newerAge,newBin,colors,smoothing)
            }
        }
        val x=(slot%1024)*2;val y=(slot/1024)*1024
        try {atlas!!.setPixels(pairPixels,0,2,x,y,2,1024)}catch(_:OutOfMemoryError){release(false);return -1}
        newerIds[slot]=newerId;olderIds[slot]=olderId;newerTimes[slot]=newerTime;olderTimes[slot]=olderTime
        if(anchored) {
            val oldColor=sampleColor(ring,olderAge,oldBottom/olderSpacing,colors,smoothing)
            val newColor=sampleColor(ring,newerAge,newBottom/newerSpacing,colors,smoothing)
            ridgeColors[slot]=oldColor
            try {
                ridgeGradients[slot]=if(oldColor==newColor)null else LinearGradient(0f,0f,1f,0f,oldColor,newColor,Shader.TileMode.CLAMP)
            } catch(_:OutOfMemoryError) { release(false); return -1 }
        } else ridgeGradients[slot]=null
        segments[slot]=segment;newerSampleVersions[slot]=newerSample;olderSampleVersions[slot]=olderSample;spacings[slot]=spacing;oldBottoms[slot]=oldBottom;newBottoms[slot]=newBottom;valid[slot]=true;preparedPairs++
        return slot
        } finally {prepareNanos+=System.nanoTime()-prepareStarted}
    }
    private fun sampleColor(ring:SonarRingBuffer,age:Int,bin:Double,colors:IntArray,smoothing:SonarDisplaySmoothingCache?):Int {
        val low=floor(bin).toInt().coerceIn(0,1023);val high=(low+1).coerceAtMost(1023)
        if(smoothing!=null){
            // Physical-depth alignment stays in already transformed intensity,
            // before the nonlinear palette; a bright code is never gained again.
            val weight=(bin-low).coerceIn(0.0,1.0)
            val a=smoothing.intensityCodeAt(age,low);val b=smoothing.intensityCodeAt(age,high)
            return colors[(a*(1-weight)+b*weight).roundToInt().coerceIn(0,65535)]
        }
        // Preserve the established nonsmoothed RGB interpolation pixel for pixel.
        val a=colors[ring.sampleAt(age,low)];val b=colors[ring.sampleAt(age,high)]
        val weight=((bin-low).coerceIn(0.0,1.0)*65536).toInt()
        if(weight==0 || a==b)return a
        val inverse=65536-weight
        val red=((a ushr 16 and 255)*inverse+(b ushr 16 and 255)*weight+32768) ushr 16
        val green=((a ushr 8 and 255)*inverse+(b ushr 8 and 255)*weight+32768) ushr 16
        val blue=((a and 255)*inverse+(b and 255)*weight+32768) ushr 16
        return (0xff shl 24)or(red shl 16)or(green shl 8)or blue
    }
    fun spacing(slot:Int):Double=spacings[slot]
    fun oldBottom(slot:Int):Double=oldBottoms[slot]
    fun newBottom(slot:Int):Double=newBottoms[slot]
    fun ridgeColor(slot:Int):Int=ridgeColors[slot]
    fun ridgeGradient(slot:Int):LinearGradient?=ridgeGradients[slot]
    fun sourceRect(slot:Int,out:Rect) {val x=(slot%1024)*2;val y=(slot/1024)*1024;out.set(x,y,x+2,y+1024)}
    /** Memory loss blocks automatic allocation retries until explicitly released/re-enabled. */
    fun release(retry:Boolean=true) {
        atlas?.let{if(!it.isRecycled)it.recycle()};atlas=null;source=null;valid.fill(false);ridgeGradients.fill(null);unavailable=!retry
    }
}
