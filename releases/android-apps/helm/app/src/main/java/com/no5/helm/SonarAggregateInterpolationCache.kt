package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Rect
import com.no5.domain.*
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Display-only neighbouring aggregate profiles. No analysis, ping IDs or invented depth anchors. */
internal class SonarAggregateInterpolationCache(
    private val allocate:()->Bitmap={Bitmap.createBitmap(2048,512,Bitmap.Config.ARGB_8888)}
) {
    private val images=arrayOfNulls<Bitmap>(SonarTimeImageHistory.CONTEXTS)
    private val valid=BooleanArray(SonarTimeImageHistory.SLOTS)
    private val newerVersions=LongArray(SonarTimeImageHistory.SLOTS)
    private val olderVersions=LongArray(SonarTimeImageHistory.SLOTS)
    private val olderSlots=IntArray(SonarTimeImageHistory.SLOTS)
    private val spacings=DoubleArray(SonarTimeImageHistory.SLOTS)
    private val oldPixels=IntArray(256)
    private val newPixels=IntArray(256)
    private val pairPixels=IntArray(512)
    private var source:SonarImageColumns?=null
    private var transfer=""
    var unavailable=false;private set
    var preparedPairs=0L;private set
    var prepareNanos=0L;private set
    var prepareMisses=0L;private set
    val allocatedPools:Int get()=images.count{it!=null}
    fun begin(ring:SonarImageColumns,key:String):Boolean {
        if(unavailable)return false
        if(source!==ring||transfer!=key){valid.fill(false);source=ring;transfer=key}
        return true
    }
    fun compatible(ring:SonarImageColumns,newer:Int,older:Int):Boolean {
        if(!ring.occupiedAt(newer)||!ring.occupiedAt(older))return false
        val source=ring.pingSourceAt(newer)
        if(source==null||source==SonarPingSource.ALL||source!=ring.pingSourceAt(older))return false
        if(ring.imageSegmentAt(newer)!=ring.imageSegmentAt(older)||ring.geometryAt(newer)?.txReference!=ring.geometryAt(older)?.txReference||
            ring.amplitudeFullScaleAt(newer)!=ring.amplitudeFullScaleAt(older))return false
        val amplitude=ring.amplitudeContextAt(newer)
        if(amplitude!=ring.amplitudeContextAt(older)||amplitude?.usable==false)return false
        val gap=ring.firstTimestampAt(newer)-ring.lastTimestampAt(older)
        if(gap !in 1..500)return false
        val a=ring.metersPerBinValueAt(newer);val b=ring.metersPerBinValueAt(older)
        return a.isFinite()&&b.isFinite()||a.isNaN()&&b.isNaN()
    }
    /** Same-source predecessor, at most two older 300-ms buckets can be within 500 ms. */
    fun predecessor(ring:SonarImageColumns,newer:Int,visibleCount:Int):Int {
        val start=(newer/SonarTimeImageHistory.CONTEXTS+1)*SonarTimeImageHistory.CONTEXTS
        var chosen=-1;var latest=Long.MIN_VALUE
        for(older in start until min(visibleCount,start+SonarTimeImageHistory.CONTEXTS*2)) {
            if(compatible(ring,newer,older)&&ring.lastTimestampAt(older)>latest){chosen=older;latest=ring.lastTimestampAt(older)}
        }
        return chosen
    }
    fun prepare(ring:SonarImageColumns,newer:Int,older:Int,displayBitmap:Bitmap,smoothing:SonarDisplaySmoothingCache?=null,intensityColors:IntArray?=null):Int {
        if(unavailable)return -1
        if(smoothing!=null)require(intensityColors?.size==65536)
        val slot=ring.storageSlotAt(newer);val oldSlot=ring.storageSlotAt(older)
        val next=smoothing?.prepare(newer)?:ring.contentVersionAt(newer);val previous=smoothing?.prepare(older)?:ring.contentVersionAt(older)
        if(valid[slot]&&newerVersions[slot]==next&&olderVersions[slot]==previous&&olderSlots[slot]==oldSlot)return slot
        prepareMisses++;val prepareStarted=System.nanoTime()
        try {
        val pool=slot/2048
        if(images[pool]==null)try{images[pool]=allocate()}catch(_:OutOfMemoryError){release(false);return -1}
        displayBitmap.getPixels(oldPixels,0,1,oldSlot%2048,(oldSlot/2048)*256,1,256)
        displayBitmap.getPixels(newPixels,0,1,slot%2048,(slot/2048)*256,1,256)
        val oldSpacing=ring.metersPerBinValueAt(older);val newSpacing=ring.metersPerBinValueAt(newer)
        val spacing=if(oldSpacing.isNaN())Double.NaN else min(oldSpacing,newSpacing)
        for(bin in 0 until 256) {
            val oldBin=if(spacing.isNaN())bin.toDouble()else bin*spacing/oldSpacing
            val newBin=if(spacing.isNaN())bin.toDouble()else bin*spacing/newSpacing
            if(smoothing!=null){
                pairPixels[bin*2]=sampleIntensity(smoothing,older,oldBin,checkNotNull(intensityColors))
                pairPixels[bin*2+1]=sampleIntensity(smoothing,newer,newBin,checkNotNull(intensityColors))
            }else{
                pairPixels[bin*2]=sample(oldPixels,oldBin)
                pairPixels[bin*2+1]=sample(newPixels,newBin)
            }
        }
        val local=slot%2048;val x=(local%1024)*2;val y=(local/1024)*256
        try{images[pool]!!.setPixels(pairPixels,0,2,x,y,2,256)}catch(_:OutOfMemoryError){release(false);return -1}
        newerVersions[slot]=next;olderVersions[slot]=previous;olderSlots[slot]=oldSlot;spacings[slot]=spacing
        valid[slot]=true;preparedPairs++
        return slot
        } finally {prepareNanos+=System.nanoTime()-prepareStarted}
    }
    /** Smooth values stay intensities through physical vertical alignment; palette once. */
    private fun sampleIntensity(smoothing:SonarDisplaySmoothingCache,age:Int,position:Double,colors:IntArray):Int {
        val low=floor(position).toInt().coerceIn(0,255);val high=(low+1).coerceAtMost(255)
        val weight=(position-low).coerceIn(0.0,1.0)
        val a=smoothing.intensityCodeAt(age,(low*1023.0/255).roundToInt())
        val b=smoothing.intensityCodeAt(age,(high*1023.0/255).roundToInt())
        val code=(a*(1-weight)+b*weight).roundToInt().coerceIn(0,65535)
        return colors[code]
    }
    private fun sample(pixels:IntArray,position:Double):Int {
        val low=floor(position).toInt().coerceIn(0,255);val high=(low+1).coerceAtMost(255)
        val a=pixels[low];val b=pixels[high];val weight=((position-low).coerceIn(0.0,1.0)*65536).toInt()
        if(a==b||weight==0)return a
        val inverse=65536-weight
        val r=((a ushr 16 and 255)*inverse+(b ushr 16 and 255)*weight+32768) ushr 16
        val g=((a ushr 8 and 255)*inverse+(b ushr 8 and 255)*weight+32768) ushr 16
        val blue=((a and 255)*inverse+(b and 255)*weight+32768) ushr 16
        return (255 shl 24)or(r shl 16)or(g shl 8)or blue
    }
    fun spacing(slot:Int)=spacings[slot]
    fun bitmap(slot:Int)=images[slot/2048]!!
    fun sourceRect(slot:Int,out:Rect){val local=slot%2048;val x=(local%1024)*2;val y=(local/1024)*256;out.set(x,y,x+2,y+256)}
    fun release(retry:Boolean=true){for(i in images.indices){images[i]?.let{if(!it.isRecycled)it.recycle()};images[i]=null};valid.fill(false);source=null;unavailable=!retry}
}
