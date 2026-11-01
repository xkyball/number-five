package com.no5.domain

import kotlin.math.*

/** A display column is not necessarily a measured ping. No invented measurement IDs. */
interface SonarImageColumns {
    fun imageMappingAt(age: Int): SonarImageValues? = null
    val imageMappingVersion: Long get() = 0
    val capacity:Int
    val count:Int
    val version:Long
    val aggregated:Boolean get()=false
    fun occupiedAt(age:Int):Boolean=true
    fun storageSlotAt(age:Int):Int
    fun sampleAt(age:Int,bin:Int):Int
    /** Copy native IMAGE rows without allocation: 1024 raw, 256 aggregated.
     * UInt16 bit patterns are retained; this is neither normalization nor peak reduction.
     * Default follows the existing virtual-bin projection; concrete stores copy contiguous rows.
     */
    fun copyDisplayProfileAt(age:Int,destination:ShortArray,offset:Int=0) {
        val rows=if(aggregated)SonarTimeImageHistory.DEPTH_BINS else SONAR_BINS
        require(age in 0 until count&&occupiedAt(age))
        require(offset>=0&&offset<=destination.size-rows)
        for(row in 0 until rows)
            destination[offset+row]=sampleAt(age,(row*1023.0/(rows-1)).roundToInt()).toShort()
    }
    /** Actual-ping intensity mean for an aggregate, not a transformed ADC peak.
     * False means no complete, retained proof; partial physical coverage is NaN per row.
     */
    fun copyMeanIntensityProfileAt(age:Int,lut:FloatArray,destination:FloatArray,offset:Int=0):Boolean=false
    /** Separate from peak content: backing-profile loss also invalidates an old mean. */
    fun meanIntensityContentVersionAt(age:Int):Long=contentVersionAt(age)
    fun timestampAt(age:Int):Long
    fun firstTimestampAt(age:Int):Long=timestampAt(age)
    fun lastTimestampAt(age:Int):Long=timestampAt(age)
    fun contributionCountAt(age:Int):Long=1
    fun contentVersionAt(age:Int):Long
    fun geometryAt(age:Int):SonarGeometry?
    fun metersPerBinValueAt(age:Int):Double
    fun amplitudeFullScaleAt(age:Int):Int
    fun amplitudeContextAt(age:Int):SonarAmplitude?=null
    fun pingSourceAt(age:Int):SonarPingSource?
    fun imageSegmentAt(age:Int):Long
    fun measuredPingIdAt(age:Int):Long?=null
    fun axisBinAt(age:Int,sourceBin:Double,axisMetersPerBin:Double):Double {
        val meters=metersPerBinValueAt(age)
        return when {meters==axisMetersPerBin || meters.isNaN()&&axisMetersPerBin.isNaN()->sourceBin
            meters>0&&axisMetersPerBin>0&&meters.isFinite()&&axisMetersPerBin.isFinite()->sourceBin*(meters/axisMetersPerBin)
            else->Double.NaN}
    }
    fun sourceBinAtAxis(age:Int,axisBin:Double,axisMetersPerBin:Double):Double {
        val meters=metersPerBinValueAt(age)
        val bin=when {meters==axisMetersPerBin || meters.isNaN()&&axisMetersPerBin.isNaN()->axisBin
            meters>0&&axisMetersPerBin>0&&meters.isFinite()&&axisMetersPerBin.isFinite()->axisBin*(axisMetersPerBin/meters)
            else->Double.NaN}
        return if(bin in 0.0..1023.0)bin else Double.NaN
    }
}

/** 614.4 s of continuously collected IMAGE peak bins; never replaces the measured ping ring.
 * Four bounded context contributions per bin keep ALL-source and observed discontinuities apart.
 * Overflow is counted explicitly, never relabelled or blended into another source.
 */
class SonarTimeImageHistory(private val allocatePool:(()->ShortArray)?=null) : SonarImageColumns {
    private var allocateMeanProfiles:(()->ShortArray)?=null
    constructor(allocatePool:()->ShortArray,allocateMeanProfiles:()->ShortArray):this(allocatePool){
        this.allocateMeanProfiles=allocateMeanProfiles
    }
    companion object {
        const val BIN_MS=300L;const val TIME_BINS=2048;const val CONTEXTS=4;const val DEPTH_BINS=256;const val SLOTS=TIME_BINS*CONTEXTS
        const val MEAN_PROFILE_CAPACITY=16384
        const val MEAN_REFS_PER_SLOT=16
    }
    override val capacity:Int get()=SLOTS
    override val aggregated:Boolean get()=true
    override var count=0;private set
    override var version=0L;private set
    var omittedContributions=0L;private set
    private val pools=arrayOfNulls<SonarShortStorage>(CONTEXTS)
    val allocatedSampleBytes:Long get()=pools.sumOf{it?.allocatedBytes?:0L}+(meanProfiles?.allocatedBytes?:0L)
    var unavailable=false;private set
    val allocatedPoolCount:Int get()=pools.count{it!=null}
    private fun raw(slot:Int,bin:Int):Int {val pool=pools[slot/TIME_BINS]?:return 0;return pool[(slot%TIME_BINS)*DEPTH_BINS+bin].toInt()and 65535}
    private fun put(slot:Int,bin:Int,value:Int){pools[slot/TIME_BINS]!![(slot%TIME_BINS)*DEPTH_BINS+bin]=value.toShort()}
    private val buckets=LongArray(SLOTS){Long.MIN_VALUE}
    private val starts=LongArray(SLOTS)
    private val ends=LongArray(SLOTS)
    private val counts=LongArray(SLOTS)
    private val revisions=LongArray(SLOTS)
    private val sources=arrayOfNulls<SonarPingSource>(SLOTS)
    private val segments=LongArray(SLOTS)
    private val geometries=arrayOfNulls<SonarGeometry>(SLOTS)
    private val spacings=DoubleArray(SLOTS){Double.NaN}
    private val scales=IntArray(SLOTS)
    private val amplitudes=arrayOfNulls<SonarAmplitude>(SLOTS)
    private val scratch=ShortArray(DEPTH_BINS)
    // One bounded8-MiB profile address space across every source/context.
    // Only touched32-KiB pages allocate; frozen snapshots use copy-before-write.
    // References are bounded independently; no temporal ADC maximum enters this pool.
    private var meanProfiles:SonarShortStorage?=null
    private val meanSerials=LongArray(MEAN_PROFILE_CAPACITY)
    private val meanSpacings=DoubleArray(MEAN_PROFILE_CAPACITY){Double.NaN}
    private val meanOwnerSlots=IntArray(MEAN_PROFILE_CAPACITY){-1}
    private val meanOwnerBuckets=LongArray(MEAN_PROFILE_CAPACITY){Long.MIN_VALUE}
    private val meanReferences=LongArray(SLOTS*MEAN_REFS_PER_SLOT)
    private val meanReferenceCounts=IntArray(SLOTS)
    private val meanUsable=BooleanArray(SLOTS)
    private val meanOverflow=BooleanArray(SLOTS)
    private val meanVersions=LongArray(SLOTS)
    private val meanScratch=ShortArray(DEPTH_BINS)
    private val meanCopyOffsets=IntArray(MEAN_REFS_PER_SLOT)
    private val meanCopyPages=arrayOfNulls<ShortArray>(MEAN_REFS_PER_SLOT)
    private val meanCopyRatios=DoubleArray(MEAN_REFS_PER_SLOT)
    private val meanCopySameGeometry=BooleanArray(MEAN_REFS_PER_SLOT)
    private var meanSerial=0L
    private var meanVersion=0L
    var meanProfileStorageUnavailable=false;private set
    var meanOverflowColumns=0L;private set
    var meanOverwrittenColumns=0L;private set
    private var newestBucket=Long.MIN_VALUE
    private var oldestBucket=Long.MAX_VALUE
    private var oldestObservedTime=Long.MAX_VALUE
    var newestTime=0L;private set
    private fun slot(bucket:Long,context:Int)=Math.floorMod(bucket,TIME_BINS.toLong()).toInt()+context*TIME_BINS
    override fun storageSlotAt(age:Int):Int {
        require(age in 0 until count)
        return slot(newestBucket-age/CONTEXTS,age%CONTEXTS)
    }
    private fun expectedBucket(age:Int)=newestBucket-age/CONTEXTS
    override fun occupiedAt(age:Int):Boolean=age in 0 until count&&buckets[storageSlotAt(age)]==expectedBucket(age)
    override fun timestampAt(age:Int):Long {val s=storageSlotAt(age);return if(occupiedAt(age))ends[s] else expectedBucket(age)*BIN_MS}
    override fun firstTimestampAt(age:Int)=starts[storageSlotAt(age)]
    override fun lastTimestampAt(age:Int)=ends[storageSlotAt(age)]
    override fun contributionCountAt(age:Int)=counts[storageSlotAt(age)]
    override fun contentVersionAt(age:Int)=revisions[storageSlotAt(age)]
    override fun meanIntensityContentVersionAt(age:Int)=meanVersions[storageSlotAt(age)]
    override fun sampleAt(age:Int,bin:Int):Int {require(bin in 0 until SONAR_BINS);return raw(storageSlotAt(age),(bin*(DEPTH_BINS-1)/1023.0).roundToInt())}
    override fun copyDisplayProfileAt(age:Int,destination:ShortArray,offset:Int) {
        require(age in 0 until count&&occupiedAt(age))
        require(offset>=0&&offset<=destination.size-DEPTH_BINS)
        val slot=storageSlotAt(age)
        val start=(slot%TIME_BINS)*DEPTH_BINS
        checkNotNull(pools[slot/TIME_BINS]).copyInto(destination,offset,start,start+DEPTH_BINS)
    }
    override fun copyMeanIntensityProfileAt(age:Int,lut:FloatArray,destination:FloatArray,offset:Int):Boolean {
        require(lut.size==65536&&offset>=0&&offset<=destination.size-DEPTH_BINS)
        if(age !in 0 until count||!occupiedAt(age))return false
        val slot=storageSlotAt(age)
        val number=meanReferenceCounts[slot]
        val data=meanProfiles?:return false
        if(!meanUsable[slot]||meanOverflow[slot]||number !in 1..MEAN_REFS_PER_SLOT||counts[slot]!=number.toLong())return false
        val references=slot*MEAN_REFS_PER_SLOT
        val axisSpacing=spacings[slot]
        // Validate every reference before touching the output. Slot/bucket ownership
        // prevents a reused time slot or wrapped global profile from becoming proof.
        for(index in 0 until number){
            val serial=meanReferences[references+index]
            if(serial<=0)return false
            val profile=((serial-1)%MEAN_PROFILE_CAPACITY).toInt()
            if(meanSerials[profile]!=serial||meanOwnerSlots[profile]!=slot||meanOwnerBuckets[profile]!=buckets[slot])return false
            meanCopyOffsets[index]=data.offset(profile*DEPTH_BINS)
            meanCopyPages[index]=data.page(profile*DEPTH_BINS)?:return false
            val meters=meanSpacings[profile]
            val same=meters==axisSpacing||meters.isNaN()&&axisSpacing.isNaN()
            meanCopySameGeometry[index]=same
            meanCopyRatios[index]=if(same)1.0 else
                if(meters>0&&axisSpacing>0&&meters.isFinite()&&axisSpacing.isFinite())axisSpacing/meters else Double.NaN
        }
        for(row in 0 until DEPTH_BINS){
            val axisBin=row*1023.0/(DEPTH_BINS-1)
            var sum=0.0;var covered=true
            for(index in 0 until number){
                val sourceBin=if(meanCopySameGeometry[index])axisBin else axisBin*meanCopyRatios[index]
                if(sourceBin !in 0.0..1023.0){covered=false;break}
                val grid=sourceBin*(DEPTH_BINS-1)/1023
                val low=floor(grid).toInt();val high=(low+1).coerceAtMost(DEPTH_BINS-1);val weight=grid-low
                val start=meanCopyOffsets[index]
                val page=checkNotNull(meanCopyPages[index])
                sum+=lut[page[start+low].toInt()and 65535]*(1-weight)+lut[page[start+high].toInt()and 65535]*weight
            }
            destination[offset+row]=if(covered)(sum/number).toFloat()else Float.NaN
        }
        return true
    }

    private fun retainMeanProfile(slot:Int,frame:SonarFrame,inputSpacing:Double){
        if(counts[slot]>=MEAN_REFS_PER_SLOT){
            if(!meanOverflow[slot]){meanOverflow[slot]=true;meanOverflowColumns++}
            meanUsable[slot]=false;meanVersions[slot]=++meanVersion
            return
        }
        if(!meanUsable[slot]){meanVersions[slot]=++meanVersion;return}
        if(meanProfileStorageUnavailable){meanUsable[slot]=false;meanVersions[slot]=++meanVersion;return}
        if(meanProfiles==null)try{
            meanProfiles=allocateMeanProfiles?.invoke()?.let{
                require(it.size==MEAN_PROFILE_CAPACITY*DEPTH_BINS);SonarShortStorage.fromArray(it)
            }?:SonarShortStorage(MEAN_PROFILE_CAPACITY*DEPTH_BINS)
        }catch(_:OutOfMemoryError){
            meanProfileStorageUnavailable=true;meanUsable[slot]=false;meanVersions[slot]=++meanVersion;return
        }
        if(meanSerial==Long.MAX_VALUE){meanProfileStorageUnavailable=true;meanUsable[slot]=false;meanVersions[slot]=++meanVersion;return}
        val serial=meanSerial+1
        val profile=((serial-1)%MEAN_PROFILE_CAPACITY).toInt()
        val profileData=try{checkNotNull(meanProfiles).writablePage(profile*DEPTH_BINS)}catch(_:OutOfMemoryError){
            meanProfileStorageUnavailable=true;meanUsable[slot]=false;meanVersions[slot]=++meanVersion;return
        }
        meanSerial=serial
        val oldOwner=meanOwnerSlots[profile]
        if(oldOwner>=0&&meanOwnerBuckets[profile]==buckets[oldOwner]&&meanUsable[oldOwner]){
            // Invalidate the old aggregate BEFORE recycling any sample or serial.
            meanUsable[oldOwner]=false;meanVersions[oldOwner]=++meanVersion;meanOverwrittenColumns++
        }
        meanScratch.fill(0)
        for(bin in 0 until SONAR_BINS){
            val into=(bin*(DEPTH_BINS-1)/1023.0).roundToInt()
            val value=frame.sample(bin)
            if(value>(meanScratch[into].toInt()and 65535))meanScratch[into]=value.toShort()
        }
        meanScratch.copyInto(profileData,checkNotNull(meanProfiles).offset(profile*DEPTH_BINS))
        meanSerials[profile]=serial;meanSpacings[profile]=inputSpacing
        meanOwnerSlots[profile]=slot;meanOwnerBuckets[profile]=buckets[slot]
        val count=meanReferenceCounts[slot]
        meanReferences[slot*MEAN_REFS_PER_SLOT+count]=serial
        meanReferenceCounts[slot]=count+1;meanVersions[slot]=++meanVersion
    }
    override fun geometryAt(age:Int)=geometries[storageSlotAt(age)]
    override fun metersPerBinValueAt(age:Int)=spacings[storageSlotAt(age)]
    override fun amplitudeFullScaleAt(age:Int)=scales[storageSlotAt(age)]
    override fun amplitudeContextAt(age:Int)=amplitudes[storageSlotAt(age)]
    override fun pingSourceAt(age:Int)=sources[storageSlotAt(age)]
    override fun imageSegmentAt(age:Int)=segments[storageSlotAt(age)]
    fun observe(frame:SonarFrame,geometry:SonarGeometry?,metersPerBin:Double?,fullScale:Int,source:SonarPingSource?,segment:Long,amplitude:SonarAmplitude?=null) {
        if(unavailable)return
        val time=frame.timestampEpochMs;val bucket=Math.floorDiv(time,BIN_MS)
        if(newestBucket!=Long.MIN_VALUE&&bucket<newestBucket-TIME_BINS+1)return
        newestBucket=maxOf(newestBucket,bucket);oldestBucket=minOf(oldestBucket,bucket)
        newestTime=maxOf(newestTime,time)
        count=(minOf(TIME_BINS.toLong(),newestBucket-oldestBucket+1)*CONTEXTS).toInt()
        var target=-1;var empty=-1
        for(context in 0 until CONTEXTS) {
            val s=slot(bucket,context)
            if(buckets[s]!=bucket) {if(empty<0)empty=s;continue}
            // Unknown identities cannot prove continuity, even if both fields are absent.
            if(source!=null&&source!=SonarPingSource.ALL&&sources[s]==source&&segments[s]==segment&&scales[s]==fullScale&&amplitudes[s]==amplitude&&
                geometries[s]?.txReference==geometry?.txReference&&spacings[s].isFinite()==(metersPerBin!=null)) {target=s;break}
        }
        if(target<0)target=empty
        if(target<0){omittedContributions++;return}
        val pool=target/TIME_BINS
        try{
            if(pools[pool]==null)pools[pool]=allocatePool?.invoke()?.let{
                require(it.size==TIME_BINS*DEPTH_BINS);SonarShortStorage.fromArray(it)
            }?:SonarShortStorage(TIME_BINS*DEPTH_BINS)
            pools[pool]!!.writablePage((target%TIME_BINS)*DEPTH_BINS)
        }catch(_:OutOfMemoryError){unavailable=true;omittedContributions++;return}
        val fresh=buckets[target]!=bucket
        val inputSpacing=metersPerBin?:Double.NaN
        if(fresh) {
            pools[pool]!!.zero((target%TIME_BINS)*DEPTH_BINS,(target%TIME_BINS+1)*DEPTH_BINS)
            buckets[target]=bucket;starts[target]=time;ends[target]=time;counts[target]=0
            sources[target]=source;segments[target]=segment;scales[target]=fullScale;amplitudes[target]=amplitude
            geometries[target]=geometry;spacings[target]=inputSpacing
            meanReferenceCounts[target]=0;meanOverflow[target]=false
            meanUsable[target]=source!=null&&source!=SonarPingSource.ALL&&fullScale in 1..65535&&amplitude?.usable!=false
            meanVersions[target]=++meanVersion
        }
        val previousSpacing=spacings[target]
        val outputSpacing=if(inputSpacing.isFinite())maxOf(previousSpacing,inputSpacing)else Double.NaN
        if(!fresh&&outputSpacing!=previousSpacing&&outputSpacing.isFinite()) {
            // Coarsen only toward the largest genuinely measured coverage; peak-bin the prior image.
            scratch.fill(0)
            for(bin in 0 until DEPTH_BINS) {
                val into=(bin*previousSpacing/outputSpacing).roundToInt().coerceIn(0,DEPTH_BINS-1)
                val raw=raw(target,bin)
                if(raw>(scratch[into].toInt()and 65535))scratch[into]=raw.toShort()
            }
            pools[pool]!!.copyFrom(scratch,(target%TIME_BINS)*DEPTH_BINS)
            spacings[target]=outputSpacing;geometries[target]=geometry
        }
        for(bin in 0 until SONAR_BINS) {
            val virtual=if(outputSpacing.isFinite())bin*inputSpacing/outputSpacing else bin.toDouble()
            val into=(virtual*(DEPTH_BINS-1)/1023).roundToInt().coerceIn(0,DEPTH_BINS-1)
            val value=frame.sample(bin)
            if(value>raw(target,into))put(target,into,value)
        }
        retainMeanProfile(target,frame,inputSpacing)
        starts[target]=minOf(starts[target],time);ends[target]=maxOf(ends[target],time);counts[target]++
        oldestObservedTime=minOf(oldestObservedTime,time)
        version++;revisions[target]=version
    }
    /** O(1) while the oldest measured bucket is retained; scan only on its expiry.
     * Selectors/render loops never search all 8192 slots on each query or pixel. */
    fun oldestObservedTimestamp():Long? {
        if(count==0)return null
        if(oldestObservedTime!=Long.MAX_VALUE&&Math.floorDiv(oldestObservedTime,BIN_MS)<newestBucket-TIME_BINS+1) {
            oldestObservedTime=Long.MAX_VALUE
            for(age in 0 until count)if(occupiedAt(age))oldestObservedTime=minOf(oldestObservedTime,firstTimestampAt(age))
        }
        return oldestObservedTime.takeUnless{it==Long.MAX_VALUE}
    }
    fun availableSeconds():Double=oldestObservedTimestamp()?.let{(newestTime-it).coerceAtLeast(0)/1000.0}?:0.0
    fun copy():SonarTimeImageHistory=SonarTimeImageHistory().also {
        for(pool in pools.indices)pools[pool]?.let {data->it.pools[pool]=data.copy()}
        it.unavailable=unavailable;buckets.copyInto(it.buckets);starts.copyInto(it.starts);ends.copyInto(it.ends);counts.copyInto(it.counts)
        revisions.copyInto(it.revisions);sources.copyInto(it.sources);segments.copyInto(it.segments);geometries.copyInto(it.geometries)
        spacings.copyInto(it.spacings);scales.copyInto(it.scales);amplitudes.copyInto(it.amplitudes);it.count=count;it.version=version;it.newestBucket=newestBucket
        it.oldestBucket=oldestBucket;it.oldestObservedTime=oldestObservedTime;it.newestTime=newestTime;it.omittedContributions=omittedContributions
        it.meanProfiles=meanProfiles?.copy();meanSerials.copyInto(it.meanSerials);meanSpacings.copyInto(it.meanSpacings)
        meanOwnerSlots.copyInto(it.meanOwnerSlots);meanOwnerBuckets.copyInto(it.meanOwnerBuckets);meanReferences.copyInto(it.meanReferences)
        meanReferenceCounts.copyInto(it.meanReferenceCounts);meanUsable.copyInto(it.meanUsable);meanOverflow.copyInto(it.meanOverflow);meanVersions.copyInto(it.meanVersions)
        it.meanSerial=meanSerial;it.meanVersion=meanVersion;it.meanProfileStorageUnavailable=meanProfileStorageUnavailable
        it.meanOverflowColumns=meanOverflowColumns;it.meanOverwrittenColumns=meanOverwrittenColumns
    }
    fun releaseStorage(){clear();meanCopyPages.fill(null);for(pool in pools.indices){pools[pool]?.release();pools[pool]=null};meanProfiles?.release();meanProfiles=null}
    fun clear(){
        buckets.fill(Long.MIN_VALUE);amplitudes.fill(null);count=0;version++;newestBucket=Long.MIN_VALUE;oldestBucket=Long.MAX_VALUE;oldestObservedTime=Long.MAX_VALUE;newestTime=0;omittedContributions=0
        meanSerials.fill(0);meanOwnerSlots.fill(-1);meanOwnerBuckets.fill(Long.MIN_VALUE);meanReferences.fill(0)
        meanReferenceCounts.fill(0);meanUsable.fill(false);meanOverflow.fill(false);meanVersions.fill(++meanVersion)
        meanProfileStorageUnavailable=false;meanOverflowColumns=0;meanOverwrittenColumns=0
    }
}
