// Frozen pre-optimization native36 algorithm, independent bit-equivalence oracle.
package com.no5.helm

import com.no5.domain.*
import kotlin.math.floor
import kotlin.math.roundToInt

/** Optional IMAGE intensity codes only. Raw pings and canonical analysis never enter this storage.
 * Raw columns use seven real pings; long history combines the actual ping-intensity means
 * of seven neighbouring 300-ms bins, never the bins' ADC peaks.
 * Until all three actual neighbours on both sides exist, the original target profile is retained.
 * Each actual sample passes gain/threshold/clamp/gamma before spatial alignment and the mean.
 * The UInt16 result is an intensity, never another ADC value to pass through gain again.
 * Recomputable LRU working sets (including metadata/LUT) stay below2MiB. All payload
 * allocation occurs in begin(); prepare/eviction only reuse those fixed arrays.
 */
internal class FrozenSonarDisplaySmoothingCache36(
    private val allocate:(()->ShortArray)?=null
) {
    companion object { const val RAW_CACHE_COLUMNS=512;const val IMAGE_CACHE_COLUMNS=1024;const val MEAN_CACHE_COLUMNS=512 }
    private var samples:ShortArray?=null
    private var intensities:FloatArray?=null
    private var meanProfiles:FloatArray?=null
    private val meanValid=BooleanArray(MEAN_CACHE_COLUMNS)
    private val meanAvailable=BooleanArray(MEAN_CACHE_COLUMNS)
    private val meanVersions=LongArray(MEAN_CACHE_COLUMNS)
    private val meanOffsets=IntArray(7)
    private val meanEntryBySource=IntArray(maxOf(SonarTimeImageHistory.SLOTS,SONAR_MAX_VISIBLE_PINGS)){-1}
    private val meanOwners=IntArray(MEAN_CACHE_COLUMNS){-1}
    private val meanAccess=LongArray(MEAN_CACHE_COLUMNS)
    private val entryBySource=IntArray(maxOf(SonarTimeImageHistory.SLOTS,SONAR_MAX_VISIBLE_PINGS)){-1}
    private val owners=IntArray(IMAGE_CACHE_COLUMNS){-1}
    private val access=LongArray(IMAGE_CACHE_COLUMNS)
    private var accessClock=0L
    private var outputCapacity=RAW_CACHE_COLUMNS
    val allocatedSampleBytes:Long get()=(samples?.size?:0)*2L+(meanProfiles?.size?:0)*4L+(intensities?.size?:0)*4L
    /** Primitive working data + all fixed cache metadata, excluding small object/array headers. */
    val workingStorageBytes:Long get()=allocatedSampleBytes +
        (meanValid.size+meanAvailable.size+valid.size+qualified.size).toLong()+
        (meanVersions.size+meanAccess.size+access.size+revisions.size+checkedVersions.size+memberVersions.size)*8L+
        (meanOffsets.size+meanEntryBySource.size+meanOwners.size+entryBySource.size+owners.size+qualifiedRows.size+memberSlots.size+window.size)*4L+
        profiles.size*2L+geometryRatios.size*8L+sameGeometry.size
    private val transferLut=SonarTransferLut()
    private var transfer=SonarDisplay()
    private var transferScale=-1
    private var source:SonarImageColumns?=null
    private var epoch:SonarEpoch?=null
    private var sourceVersion=-1L
    private var height=SONAR_BINS
    private var serial=0L
    private val valid=BooleanArray(IMAGE_CACHE_COLUMNS)
    private val qualified=BooleanArray(IMAGE_CACHE_COLUMNS)
    private val qualifiedRows=IntArray(IMAGE_CACHE_COLUMNS)
    private val revisions=LongArray(IMAGE_CACHE_COLUMNS)
    private val checkedVersions=LongArray(IMAGE_CACHE_COLUMNS)
    private val memberSlots=IntArray(IMAGE_CACHE_COLUMNS*7){-1}
    private val memberVersions=LongArray(IMAGE_CACHE_COLUMNS*7)
    private val window=IntArray(7)
    private val profiles=ShortArray(7*SONAR_BINS)
    private val geometryRatios=DoubleArray(7)
    private val sameGeometry=BooleanArray(7)
    var outputEvictions=0L;private set
    var meanEvictions=0L;private set
    var preparedColumns=0L;private set
    var prepareNanos=0L;private set
    var intensityMeanProfilePreparations=0L;private set
    var fallbackRows=0L;private set
    var unavailable=false;private set

    fun begin(columns:SonarImageColumns,currentEpoch:SonarEpoch?,display:SonarDisplay=SonarDisplay()):Boolean {
        if(unavailable)return false
        val nextHeight=if(columns.aggregated)SonarTimeImageHistory.DEPTH_BINS else SONAR_BINS
        val nextCapacity=if(columns.aggregated)IMAGE_CACHE_COLUMNS else RAW_CACHE_COLUMNS
        try{
            if(height!=nextHeight){samples=null;meanProfiles=null}
            if(samples==null)samples=(allocate?.invoke()?:ShortArray(nextCapacity*nextHeight)).also{require(it.size==nextCapacity*nextHeight)}
            if(intensities==null)intensities=FloatArray(65536)
            if(columns.aggregated&&meanProfiles==null)meanProfiles=FloatArray(MEAN_CACHE_COLUMNS*SonarTimeImageHistory.DEPTH_BINS)
        }catch(_:OutOfMemoryError){release(false);return false}
        val sourceChanged=source!==columns||epoch!=currentEpoch||columns.version<sourceVersion
        val changedTransfer=transfer.gainDb!=display.gainDb||transfer.gamma!=display.gamma||transfer.threshold!=display.threshold
        if(source!==columns||epoch!=currentEpoch||columns.version<sourceVersion||changedTransfer){
            valid.fill(false);meanValid.fill(false);source=columns;epoch=currentEpoch
            // Transfer changes reuse the same bounded entry bindings; source/epoch changes cannot.
            if(height!=nextHeight||outputCapacity!=nextCapacity||sourceChanged){
                entryBySource.fill(-1);owners.fill(-1);meanEntryBySource.fill(-1);meanOwners.fill(-1)
            }
            height=nextHeight;outputCapacity=nextCapacity
        }
        if(changedTransfer)transferScale=-1
        transfer=display
        sourceVersion=columns.version
        return true
    }
    private fun content(columns:SonarImageColumns,age:Int)=when {
        columns.aggregated->columns.meanIntensityContentVersionAt(age)
        columns is SonarRingBuffer->columns.version-age
        else->columns.contentVersionAt(age)
    }
    private fun entry(sourceSlot:Int,mean:Boolean):Int {
        val map=if(mean)meanEntryBySource else entryBySource
        val old=map[sourceSlot]
        val used=if(mean)meanAccess else access
        if(old>=0){used[old]=++accessClock;return old}
        val bindings=if(mean)meanOwners else owners
        val limit=if(mean)MEAN_CACHE_COLUMNS else outputCapacity
        var selected=0
        for(index in 0 until limit){
            if(bindings[index]<0){selected=index;break}
            if(used[index]<used[selected])selected=index
        }
        val previous=bindings[selected]
        if(previous>=0){map[previous]=-1;if(mean)meanEvictions++else outputEvictions++}
        bindings[selected]=sourceSlot;map[sourceSlot]=selected;used[selected]=++accessClock
        if(mean)meanValid[selected]=false else valid[selected]=false
        return selected
    }
    private fun meanProfile(columns:SonarImageColumns,age:Int,lut:FloatArray):Int {
        val slot=entry(columns.storageSlotAt(age),true);val revision=content(columns,age)
        if(!meanValid[slot]||meanVersions[slot]!=revision){
            meanAvailable[slot]=columns.copyMeanIntensityProfileAt(age,lut,checkNotNull(meanProfiles),slot*height)
            meanVersions[slot]=revision;meanValid[slot]=true;intensityMeanProfilePreparations++
        }
        return if(meanAvailable[slot])slot*height else -1
    }
    private fun compatible(columns:SonarImageColumns,newer:Int,older:Int):Boolean {
        val identity=columns.pingSourceAt(newer)
        if(identity==null||identity==SonarPingSource.ALL||identity!=columns.pingSourceAt(older))return false
        if(columns.imageSegmentAt(newer)!=columns.imageSegmentAt(older) ||
            columns.geometryAt(newer)?.txReference!=columns.geometryAt(older)?.txReference ||
            columns.amplitudeFullScaleAt(newer)!=columns.amplitudeFullScaleAt(older))return false
        val amplitude=columns.amplitudeContextAt(newer)
        if(amplitude?.usable==false||amplitude!=columns.amplitudeContextAt(older))return false
        val gap=columns.firstTimestampAt(newer)-columns.lastTimestampAt(older)
        if(gap !in 1..500)return false
        val a=columns.metersPerBinValueAt(newer);val b=columns.metersPerBinValueAt(older)
        return a>0&&b>0&&a.isFinite()&&b.isFinite() || a.isNaN()&&b.isNaN()
    }
    /** A context change in the nearest same-source column is a boundary, not a column to skip. */
    private fun neighbour(columns:SonarImageColumns,age:Int,direction:Int):Int {
        if(!columns.aggregated){val next=age+direction
            if(next !in 0 until columns.count)return -1
            return if(if(direction>0)compatible(columns,age,next)else compatible(columns,next,age))next else -1
        }
        val contexts=SonarTimeImageHistory.CONTEXTS
        val bucket=age/contexts
        for(distance in 1..2){
            val nextBucket=bucket+direction*distance
            if(nextBucket<0)return -1
            val start=nextBucket*contexts
            if(start>=columns.count)return -1
            var next=-1;var stamp=if(direction>0)Long.MIN_VALUE else Long.MAX_VALUE
            for(candidate in start until minOf(start+contexts,columns.count)){
                if(!columns.occupiedAt(candidate)||columns.pingSourceAt(candidate)!=columns.pingSourceAt(age))continue
                val time=if(direction>0)columns.lastTimestampAt(candidate)else columns.firstTimestampAt(candidate)
                if(if(direction>0)time>stamp else time<stamp){next=candidate;stamp=time}
            }
            if(next>=0)return if(if(direction>0)compatible(columns,age,next)else compatible(columns,next,age))next else -1
        }
        return -1
    }
    fun prepare(age:Int):Long {
        val columns=checkNotNull(source);require(columns.occupiedAt(age))
        val slot=entry(columns.storageSlotAt(age),false)
        if(valid[slot]&&checkedVersions[slot]==columns.version)return revisions[slot]
        window.fill(-1);window[3]=age
        var next=age
        for(index in 4..6){next=neighbour(columns,next,1);if(next<0)break;window[index]=next}
        next=age
        for(index in 2 downTo 0){next=neighbour(columns,next,-1);if(next<0)break;window[index]=next}
        val complete=window[0]>=0&&window[6]>=0&&
            columns.lastTimestampAt(window[0])-columns.firstTimestampAt(window[6])<=2500
        val base=slot*7
        var changed=!valid[slot]||qualified[slot]!=complete
        for(index in 0..6){if(!complete&&index!=3)continue
            val member=window[index];val memberSlot=if(member>=0)columns.storageSlotAt(member)else -1
            val revision=if(member>=0)content(columns,member)else -1L
            if(memberSlots[base+index]!=memberSlot||memberVersions[base+index]!=revision)changed=true
        }
        checkedVersions[slot]=columns.version
        if(!changed)return revisions[slot]
        val started=System.nanoTime()
        val data=checkNotNull(samples);val target=slot*height;val spacing=columns.metersPerBinValueAt(age)
        val fullScale=columns.amplitudeFullScaleAt(age)
        if(transferScale!=fullScale){
            transferLut.fillIntensities(checkNotNull(intensities),fullScale,transfer)
            transferScale=fullScale
        }
        val lut=checkNotNull(intensities)
        // Resolve each backing-store slot and geometry once, never per output row.
        // Target index3 is also the unchanged-profile fallback: no eighth copy.
        meanOffsets.fill(-1)
        for(index in 0..6){
            if(!complete&&index!=3)continue
            val member=window[index]
            if(!columns.aggregated||index==3)columns.copyDisplayProfileAt(member,profiles,index*SONAR_BINS)
            if(complete){
                if(columns.aggregated)meanOffsets[index]=meanProfile(columns,member,lut)
                val meters=columns.metersPerBinValueAt(member)
                val same=meters==spacing||meters.isNaN()&&spacing.isNaN()
                sameGeometry[index]=same
                geometryRatios[index]=if(same)1.0 else
                    if(meters>0&&spacing>0&&meters.isFinite()&&spacing.isFinite())spacing/meters else Double.NaN
            }
        }
        val last=height-1
        var coveredRows=0
        for(row in 0 until height){
            val bin=row*1023.0/(height-1)
            var sum=0.0;var covered=complete
            if(complete)for(index in 0..6){
                val sourceBin=if(sameGeometry[index])bin else bin*geometryRatios[index]
                if(sourceBin !in 0.0..1023.0){covered=false;break}
                // Preserve the original source-bin -> native-row arithmetic and
                // Double accumulation, but fetch the copied primitive rows directly.
                val grid=sourceBin*last/1023
                val low=floor(grid).toInt();val high=(low+1).coerceAtMost(last);val weight=grid-low
                if(columns.aggregated){
                    val offset=meanOffsets[index]
                    if(offset<0){covered=false;break}
                    val mean=checkNotNull(meanProfiles);val lower=mean[offset+low];val upper=mean[offset+high]
                    if(!lower.isFinite()||weight!=0.0&&!upper.isFinite()){covered=false;break}
                    sum+=lower*(1-weight)+if(weight==0.0)0.0 else upper*weight
                }else{
                    // Keep the established raw-ping arithmetic, including UInt16 and Double order.
                    val base=index*SONAR_BINS
                    sum+=lut[profiles[base+low].toInt()and 65535]*(1-weight)+
                        lut[profiles[base+high].toInt()and 65535]*weight
                }
            }
            // Missing coverage never supplies a zero or a smaller averaging denominator.
            if(covered)coveredRows++ else fallbackRows++
            val value=if(covered)sum/7 else lut[profiles[3*SONAR_BINS+row].toInt()and 65535].toDouble()
            data[target+row]=(value*65535).roundToInt().coerceIn(0,65535).toShort()
        }
        for(index in 0..6){val member=window[index]
            memberSlots[base+index]=if(member>=0)columns.storageSlotAt(member)else -1
            memberVersions[base+index]=if(member>=0)content(columns,member)else -1L
        }
        qualified[slot]=complete;qualifiedRows[slot]=coveredRows;valid[slot]=true;revisions[slot]=++serial;preparedColumns++
        prepareNanos+=System.nanoTime()-started
        return revisions[slot]
    }
    private fun preparedEntry(age:Int):Int {
        val columns=checkNotNull(source);val sourceSlot=columns.storageSlotAt(age)
        var slot=entryBySource[sourceSlot]
        if(slot<0||!valid[slot]||checkedVersions[slot]!=columns.version){prepare(age);slot=entryBySource[sourceSlot]}
        access[slot]=++accessClock
        return slot
    }
    fun intensityCodeAt(age:Int,bin:Int):Int {
        val slot=preparedEntry(age);val row=(bin*(height-1)/1023.0).roundToInt()
        return checkNotNull(samples)[slot*height+row].toInt()and 65535
    }
    fun qualifiedAt(age:Int):Boolean {
        val slot=preparedEntry(age)
        return qualified[slot]&&(!checkNotNull(source).aggregated||qualifiedRows[slot]==height)
    }
    fun qualifiedRowsAt(age:Int)=qualifiedRows[preparedEntry(age)]
    fun release(retry:Boolean=true){samples=null;intensities=null;meanProfiles=null;transferScale=-1;source=null;
        entryBySource.fill(-1);owners.fill(-1);meanEntryBySource.fill(-1);meanOwners.fill(-1)
        valid.fill(false);meanValid.fill(false);unavailable=!retry}
}
