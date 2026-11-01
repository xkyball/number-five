package com.no5.domain

import kotlin.math.*

const val SONAR_BINS = 1024
const val SONAR_CAPACITY = 2048 // Legacy reference renderer width; not the live history budget.
const val SONAR_MAX_VISIBLE_PINGS = 20 * 600
fun sonarVisiblePingBudget(seconds: Double): Int {
    require(seconds.isFinite() && seconds in 10.0..600.0)
    return kotlin.math.ceil(seconds * 20.0).toInt()
}
private val CHECKED_AMPLITUDE_PRODUCERS = setOf("0.3.5", "0.3.6", "0.4.5", "0.4.6", "0.4.7", "0.4.8", "0.4.9", "0.4.10")
/** Samples store uint16 bit patterns in JVM shorts. No boxing and no unsigned opt-in. */
class SonarFrame(samples: ShortArray, val pingId: Long, val timestampEpochMs: Long) {
    private val data = samples.copyOf()
    init { require(data.size == SONAR_BINS) { "Exactly 1024 uint16 samples required" }; require(pingId >= 0) }
    val samples: ShortArray get() = data.copyOf()
    internal fun sampleAt(bin: Int): Int = data[bin].toInt() and 0xffff
    fun sample(index: Int): Int = data[index].toInt() and 0xffff
    internal fun copyInto(target: ShortArray, offset: Int) { data.copyInto(target, offset) }
    companion object {
        fun fromUnsigned(samples: IntArray, pingId: Long, timestampEpochMs: Long): SonarFrame {
            require(samples.size == SONAR_BINS && samples.all { it in 0..65535 })
            return SonarFrame(ShortArray(SONAR_BINS) { samples[it].toShort() }, pingId, timestampEpochMs)
        }
        fun fromLittleEndian(bytes: ByteArray, pingId: Long, timestampEpochMs: Long): SonarFrame {
            require(bytes.size == SONAR_BINS * 2) { "Exactly 2048 binary bytes required" }
            return SonarFrame(ShortArray(SONAR_BINS) { ((bytes[it * 2].toInt() and 255) or ((bytes[it * 2 + 1].toInt() and 255) shl 8)).toShort() }, pingId, timestampEpochMs)
        }
    }
}
/** One bounded raw ring. Its owner chooses ceil(20 * displayed seconds), never a second frozen ring. */
class SonarRingBuffer(override val capacity: Int = SONAR_CAPACITY) : SonarImageColumns {
    init { require(capacity in 1..SONAR_MAX_VISIBLE_PINGS) }
    // Each immutable input owns one sample array. History and latest share that SAME object.
    // Overwrite/eviction drops the reference; no primitive shadow history or frozen copy.
    private val frames = arrayOfNulls<SonarFrame>(capacity)
    val allocatedSampleBytes:Long get()=count.toLong()*SONAR_BINS*2
    private val ids = LongArray(capacity)
    private val times = LongArray(capacity)
    private val receivedTimes = LongArray(capacity)
    private val calibrations = arrayOfNulls<Calibration>(capacity)
    private val bottoms = arrayOfNulls<SonarBottom>(capacity)
    private val geometries = arrayOfNulls<SonarGeometry>(capacity)
    private val metersPerBins = DoubleArray(capacity) { Double.NaN }
    private val amplitudeFullScales = IntArray(capacity)
    private val amplitudes = arrayOfNulls<SonarAmplitude>(capacity)
    private val presentations = arrayOfNulls<SonarPresentation>(capacity)
    private val pingSources = arrayOfNulls<SonarPingSource>(capacity)
    private val imageSegments = LongArray(capacity)
    private val imageMappings = FloatArray(capacity * 3) { Float.NaN }
    override var imageMappingVersion: Long = 0; private set
    override fun imageMappingAt(age: Int): SonarImageValues? {
        val at = slot(age) * 3
        return if (imageMappings[at].isNaN()) null else SonarImageValues(imageMappings[at].toDouble(), imageMappings[at+1].toDouble(), imageMappings[at+2].toDouble())
    }
    @Synchronized fun rebaseImageMapping(field: SonarImageField, value: Double) {
        for (age in 0 until count) {
            val at=slot(age)*3
            if(imageMappings[at].isNaN()){imageMappings[at]=0f;imageMappings[at+1]=1f;imageMappings[at+2]=0f}
            imageMappings[at + field.ordinal] = value.toFloat()
        }
        imageMappingVersion++
    }
    var head: Int = 0; private set
    override var count: Int = 0; private set
    override var version: Long = 0; private set
    @Synchronized
    fun push(frame: SonarFrame, calibration: Calibration = Calibration(), bottom: SonarBottom = SonarBottom(), geometry: SonarGeometry? = null, amplitudeFullScale: Int = 65535, presentation: SonarPresentation? = null, pingSource: SonarPingSource? = null, imageSegment: Long = 0, amplitude: SonarAmplitude? = null, imageMapping: SonarImageValues? = null, localReceivedAtNanos: Long = 0) {
        require(amplitudeFullScale in 1..65535)
        val toneAt = head * 3
        imageMappings[toneAt] = imageMapping?.gainDb?.toFloat() ?: Float.NaN
        imageMappings[toneAt+1] = imageMapping?.gamma?.toFloat() ?: Float.NaN
        imageMappings[toneAt+2] = imageMapping?.threshold?.toFloat() ?: Float.NaN
        calibrations[head] = calibration; bottoms[head] = bottom
        geometries[head] = geometry
        metersPerBins[head] = (if (geometry == null) calibration.metersPerBin else geometry.metersPerBin) ?: Double.NaN
        amplitudeFullScales[head] = amplitudeFullScale
        amplitudes[head] = amplitude
        presentations[head] = presentation; pingSources[head] = pingSource; imageSegments[head] = imageSegment
        receivedTimes[head] = localReceivedAtNanos
        frames[head] = frame; ids[head] = frame.pingId; times[head] = frame.timestampEpochMs
        head = (head + 1) % capacity; count = min(count + 1, capacity); version++
    }
    fun <T> readLocked(block: (SonarRingBuffer) -> T): T = synchronized(this) { block(this) }
    var lifecycleGeneration: Long = 0; private set
    override fun storageSlotAt(age:Int):Int=slot(age)
    override fun contentVersionAt(age:Int):Long=pingIdAt(age)
    override fun measuredPingIdAt(age:Int):Long=pingIdAt(age)
    private fun slot(age: Int): Int { require(age in 0 until count); return (head - 1 - age + capacity) % capacity }
    fun atAge(age: Int): SonarFrame? {
        if (age !in 0 until count) return null
        return frames[slot(age)]
    }
    override fun sampleAt(age: Int, bin: Int): Int { require(bin in 0 until SONAR_BINS); return frames[slot(age)]!!.sampleAt(bin) }
    override fun copyDisplayProfileAt(age:Int,destination:ShortArray,offset:Int) {
        require(offset>=0&&offset<=destination.size-SONAR_BINS)
        frames[slot(age)]!!.copyInto(destination, offset)
    }
    override fun timestampAt(age: Int): Long = times[slot(age)]
    fun localReceivedAtNanosAt(age: Int): Long = receivedTimes[slot(age)]
    /** Metadata-only view evidence: avoids copying 1024 raw samples just to read an ID. */
    fun pingIdAt(age: Int): Long = ids[slot(age)]
    fun calibrationAt(age: Int): Calibration = requireNotNull(calibrations[slot(age)])
    fun bottomAt(age: Int): SonarBottom = requireNotNull(bottoms[slot(age)])
    override fun geometryAt(age: Int): SonarGeometry? = geometries[slot(age)]
    fun presentationAt(age: Int): SonarPresentation? = presentations[slot(age)]
    override fun pingSourceAt(age: Int): SonarPingSource? = pingSources[slot(age)]
    override fun imageSegmentAt(age: Int): Long = imageSegments[slot(age)]
    override fun amplitudeFullScaleAt(age: Int): Int = amplitudeFullScales[slot(age)]
    override fun amplitudeContextAt(age: Int): SonarAmplitude? = amplitudes[slot(age)]
    fun metersPerBinAt(age: Int): Double? = metersPerBinValueAt(age).takeIf { it.isFinite() }
    /** Primitive geometry for render loops. NaN means sample bins without metric timing. */
    override fun metersPerBinValueAt(age: Int): Double = metersPerBins[slot(age)]
    override fun axisBinAt(age: Int, sourceBin: Double, axisMetersPerBin: Double): Double {
        val meters = metersPerBinValueAt(age)
        return when {
            meters == axisMetersPerBin || meters.isNaN() && axisMetersPerBin.isNaN() -> sourceBin
            meters > 0 && axisMetersPerBin > 0 && meters.isFinite() && axisMetersPerBin.isFinite() -> sourceBin * (meters / axisMetersPerBin)
            else -> Double.NaN
        }
    }
    /** No fabricated sample beyond this capture's measured coverage. */
    override fun sourceBinAtAxis(age: Int, axisBin: Double, axisMetersPerBin: Double): Double {
        val meters = metersPerBinValueAt(age)
        val bin = when {
            meters == axisMetersPerBin || meters.isNaN() && axisMetersPerBin.isNaN() -> axisBin
            meters > 0 && axisMetersPerBin > 0 && meters.isFinite() && axisMetersPerBin.isFinite() -> axisBin * (axisMetersPerBin / meters)
            else -> Double.NaN
        }
        return if (bin in 0.0..1023.0) bin else Double.NaN
    }
    @Synchronized
    fun copy(): SonarRingBuffer = SonarRingBuffer(capacity).also { frames.copyInto(it.frames); ids.copyInto(it.ids); times.copyInto(it.times); receivedTimes.copyInto(it.receivedTimes); calibrations.copyInto(it.calibrations); bottoms.copyInto(it.bottoms); geometries.copyInto(it.geometries); metersPerBins.copyInto(it.metersPerBins); amplitudeFullScales.copyInto(it.amplitudeFullScales); amplitudes.copyInto(it.amplitudes); presentations.copyInto(it.presentations); pingSources.copyInto(it.pingSources); imageSegments.copyInto(it.imageSegments); imageMappings.copyInto(it.imageMappings); it.imageMappingVersion=imageMappingVersion; it.head = head; it.count = count; it.version = version }
    /** Remove payload and metadata outside the displayed time horizon as well as the count cap. */
    @Synchronized
    fun evictBefore(oldestEpochMs: Double) {
        var changed = false
        while (count > 0 && timestampAt(count - 1) < oldestEpochMs) {
            val index = slot(count - 1)
            calibrations[index] = null; bottoms[index] = null; geometries[index] = null
            amplitudes[index] = null; presentations[index] = null; pingSources[index] = null
            ids[index] = 0; times[index] = 0; receivedTimes[index] = 0; metersPerBins[index] = Double.NaN
            amplitudeFullScales[index] = 0; imageSegments[index] = 0; imageMappings.fill(Float.NaN, index*3, index*3+3)
            frames[index] = null
            count--; changed = true
        }
        if (changed) version++
    }
    /** Retain newest columns in order, then release the entire old owner's payload/metadata. */
    @Synchronized
    fun moveToCapacity(nextCapacity: Int): SonarRingBuffer {
        if (nextCapacity == capacity) return this
        val next = SonarRingBuffer(nextCapacity)
        for (age in minOf(count, nextCapacity) - 1 downTo 0) {
            next.push(atAge(age)!!, calibrationAt(age), bottomAt(age), geometryAt(age),
                amplitudeFullScaleAt(age), presentationAt(age), pingSourceAt(age), imageSegmentAt(age), amplitudeContextAt(age), imageMappingAt(age), localReceivedAtNanosAt(age))
        }
        next.imageMappingVersion = imageMappingVersion; next.version = version + 1
        releaseStorage()
        return next
    }
    @Synchronized
    fun releaseStorage(){clear()}
    @Synchronized
    fun clear() { lifecycleGeneration++; receivedTimes.fill(0); imageMappings.fill(Float.NaN); frames.fill(null); head = 0; count = 0; calibrations.fill(null); bottoms.fill(null); geometries.fill(null); metersPerBins.fill(Double.NaN); amplitudes.fill(null); presentations.fill(null); pingSources.fill(null); version++ }
}
enum class DepthReference { TRANSDUCER, SURFACE, KEEL }
data class Calibration(val valid: Boolean = false, val samplePeriodUs: Double? = null, val soundSpeedMps: Double = 1500.0, val transducerOffsetM: Double? = null, val keelOffsetM: Double? = null, val depthReference: DepthReference = DepthReference.TRANSDUCER, val revision: Long = 0) {
    init { require(revision >= 0); require(samplePeriodUs == null || samplePeriodUs.isFinite() && samplePeriodUs > 0 && samplePeriodUs <= 1000); require(soundSpeedMps.isFinite() && soundSpeedMps in 1300.0..1800.0); listOf(transducerOffsetM, keelOffsetM).forEach { require(it == null || it.isFinite() && abs(it) <= 50) } }
    val calibrated: Boolean get() = valid && samplePeriodUs != null
    val metersPerBin: Double? get() = if (calibrated) samplePeriodUs!! * 1e-6 * soundSpeedMps / 2 else null
}
/** Host-referenced resampled display geometry is distinct from ADC calibration. */
enum class SonarTxReference { UNVERIFIED, HOST_VERIFIED, EMPIRICAL_FALLBACK }
data class SonarGeometry(val displayedMaxDepthM: Double?, val txReference: SonarTxReference, val flags: Long = 0, val adcSamplePeriodUs: Double? = null) {
    init {
        require(displayedMaxDepthM == null || displayedMaxDepthM.isFinite() && displayedMaxDepthM > 0)
        require(flags in 0..0xffffffffL)
        require((displayedMaxDepthM != null) == (txReference != SonarTxReference.UNVERIFIED))
        require(adcSamplePeriodUs == null || adcSamplePeriodUs.isFinite() && adcSamplePeriodUs > 0 && adcSamplePeriodUs <= 1000)
    }
    val metersPerBin: Double? get() = displayedMaxDepthM?.div(SONAR_BINS - 1)
}
enum class SonarBottomSource { M1_RAW, CFAR_TRACK_RULES_V1, DIRECT_ENVELOPE_TRACK_RULES_V1 }
data class SonarBottom(val bin: Double? = null, val confidence: Double? = null, val strength: Double? = null,
    val source: SonarBottomSource? = null, val trackId: Long? = null, val classification: String? = null) {
    init {
        require(listOf(bin, confidence, strength).all { it == null || it.isFinite() })
        require(confidence == null || confidence in 0.0..1.0); require(strength == null || strength in 0.0..1.0)
        require(trackId == null || trackId > 0)
    }
    val validated: Boolean get() = bin?.let { it in 0.0..1023.0 } == true && confidence != null &&
        if (source == SonarBottomSource.CFAR_TRACK_RULES_V1 || source == SonarBottomSource.DIRECT_ENVELOPE_TRACK_RULES_V1)
            trackId != null else confidence >= .75
}
enum class SonarAnalysisQuality { WAITING, SEARCHING, LOCKED, UNSTABLE, UNVERIFIED_TX }
data class SonarTrack(val trackId: Long, val bin: Double, val confidence: Double,
    val classification: String, val classificationSource: String, val detector: String) {
    init {
        require(trackId > 0 && bin.isFinite() && bin in 0.0..1023.0 && confidence.isFinite() && confidence in 0.0..1.0)
        require(classification in setOf("bottom_like", "fish_like", "weed_like", "bubble_like", "multipath_like", "unknown"))
        require(classificationSource == "rules_v1" && detector in setOf("cfar", "direct_envelope"))
    }
}
data class SonarPresentation(val pingId: Long, val quality: SonarAnalysisQuality, val reason: String?,
    val requestedFilter: String, val effectiveFilter: String, val filterActive: Boolean,
    val followBottom: Boolean, val rangeEndBin: Double?, val recommendFreeze: Boolean,
    val tracks: List<SonarTrack> = emptyList(), val trackTotal: Int = tracks.size, val trackOmitted: Int = 0) {
    init {
        require(pingId >= 0); require(reason == null || reason.length <= 200)
        require(requestedFilter in setOf("off", "auto", "on"))
        require(effectiveFilter in setOf("off", "on", "auto_raw", "auto_filter"))
        require(rangeEndBin == null || rangeEndBin.isFinite() && rangeEndBin in 32.0..1023.0)
        require(tracks.size <= 8 && tracks.map { it.trackId }.distinct().size == tracks.size)
        require(trackTotal >= tracks.size && trackOmitted == trackTotal - tracks.size)
        require(quality != SonarAnalysisQuality.UNVERIFIED_TX || tracks.isEmpty())
    }
}
enum class SonarPalette { SUN_HIGH_CONTRAST, SUN_WHITE, MONO_AMBER, NIGHT_RED }
enum class SonarRangeMode { MANUAL, AUTO }
enum class SonarQuality { OFFLINE, IDLE, ACQUIRING, FROZEN, DEGRADED, ERROR }
data class SonarDisplay(val palette: SonarPalette = SonarPalette.SUN_HIGH_CONTRAST, val gainDb: Double = 0.0, val gamma: Double = 1.0, val threshold: Double = 0.0, val rangeMode: SonarRangeMode = SonarRangeMode.MANUAL, val rangeM: Double? = null, val cropStart: Double = 0.0, val cropEnd: Double = 1023.0, val zoom: Boolean = false, val scrollSpeed: Double = 1.0, val historySeconds: Double = 120.0, val aScope: Boolean = true, val freeze: Boolean = false, val overlayBottom: Boolean = false, val overlayTargets: Boolean = false,
    val gainMode: SonarImageMode = SonarImageMode.MANUAL,
    val gammaMode: SonarImageMode = SonarImageMode.MANUAL,
    val thresholdMode: SonarImageMode = SonarImageMode.MANUAL,
    val manualGainDb: Double? = null, val manualGamma: Double? = null, val manualThreshold: Double? = null,
    val exactPingProfiles: Boolean = false, val autoGainOffsetDb: Double = 0.0,
    val startDepthM: Double = 0.0) {
    init {
        require(listOf(manualGainDb, manualGamma, manualThreshold).all { it == null || it.isFinite() })
        require(startDepthM.isFinite() && startDepthM >= 0.0)
    }
}
data class SonarSnapshot(val quality: SonarQuality, val demo: Boolean, val connected: Boolean, val running: Boolean, val source: String?, val pingId: Long?, val timestampEpochMs: Long?, val depthM: Double?, val calibration: Calibration, val bottom: SonarBottom, val display: SonarDisplay, val ringCount: Int, val frozenCount: Int, val droppedFrames: Long, val frameRateHz: Double?, val latencyMs: Long?, val simulated: Boolean = false,
    val measuredRate: SonarPingRate? = null, val historyCoverage: SonarHistoryCoverage? = null,
    val imageAutoState: SonarImageAutoState = SonarImageAutoState.WAITING)
class SonarModel(private val nowEpochMs: () -> Long = System::currentTimeMillis) {
    private var depthNowNanos: () -> Long = System::nanoTime
    constructor(nowEpochMs: () -> Long, depthNowNanos: () -> Long): this(nowEpochMs) { this.depthNowNanos = depthNowNanos }
    private val depthWindow = SonarDepthWindow()
    var depthSettings = SonarDepthSettings(SonarDepthMode.DIRECT); private set
    private var frozenDepthDisplay: SonarDepthDisplay? = null
    fun setDepthSettings(value: SonarDepthSettings) { depthSettings = value }
    fun liveDepthDisplay(): SonarDepthDisplay = depthWindow.read(depthSettings, depthNowNanos(),
        connected && running && !sourceStale && error == null && quality() in setOf(SonarQuality.ACQUIRING, SonarQuality.FROZEN))
    fun depthDisplay(): SonarDepthDisplay = if (display.freeze) frozenDepthDisplay ?: SonarDepthDisplay() else liveDepthDisplay()
    private fun displayBottomBin(): Double? {
        if (depthSettings.mode == SonarDepthMode.DIRECT) return bottom.bin
        var value = liveDepthDisplay().valueM ?: return null
        when (calibration.depthReference) {
            DepthReference.TRANSDUCER -> Unit
            DepthReference.SURFACE -> value -= calibration.transducerOffsetM ?: return null
            DepthReference.KEEL -> value += calibration.keelOffsetM ?: return null
        }
        return metersPerBin?.let { value / it }?.takeIf { it.isFinite() && it >= 0 }
    }
    var ring = SonarRingBuffer(sonarVisiblePingBudget(120.0)); private set
    private var legacyImageHistory: SonarTimeImageHistory? = null
    /** Explicit reference/test access only. The production ring never feeds this historical aggregator. */
    val imageHistory: SonarTimeImageHistory get() = legacyImageHistory ?: SonarTimeImageHistory().also { legacyImageHistory = it }
    private var frozenImageHistory:SonarTimeImageHistory?=null
    private var imageFreezeUnavailable=false
    val imageHistoryUnavailable:Boolean get()=false
    private var frozenRing: SonarRingBuffer? = null
    private var frozenBottomCapability = false
    private var frozenPresentation: SonarPresentation? = null
    private var frozenDisplay: SonarDisplay? = null
    private val pingRate = SonarPingRateWindow()
    private val imageAuto = SonarImageAuto()
    var processingObserver:((String,Boolean)->Unit)?=null
    private inline fun <T> processing(name:String,block:()->T):T {
        processingObserver?.invoke(name,true)
        try{return block()}finally{processingObserver?.invoke(name,false)}
    }
    private var explicitAmplitude: SonarAmplitude? = null
    private var rangeUpdatedAt: Long? = null
    private var rangeFollowed = false
    private var rangeConfirmations = 0
    private var rangeShrinkConfirmedMs = 0L
    private var rangeShrinkLastConfirmedAt: Long? = null
    private var rangeLastObservation: Long? = null
    private var unprovenSourceAtEpochMs: Long? = null
    private var imageSegment = 0L
    /** An observed transport discontinuity affects image interpolation only. */
    fun markImageContinuityBoundary() { imageSegment++ }
    var epoch: SonarEpoch? = null; private set
    var selectedPingSource: SonarPingSource? = null; private set
    var currentPingSource: SonarPingSource? = null; private set
    var lastConfirmedDepth: SonarHistoricalDepth? = null; private set
    val imageAutoState: SonarImageAutoState get() = if (!amplitudeVerified) SonarImageAutoState.INCOMPATIBLE else
        if (sourceStale || !connected || latest == null || nowEpochMs() - latest!!.timestampEpochMs > 2500) SonarImageAutoState.WAITING else imageAuto.state
    val viewDisplay: SonarDisplay get() = if (display.freeze) frozenDisplay ?: display else display
    val viewPresentation: SonarPresentation? get() = if (display.freeze) frozenPresentation else presentation
    val viewRing: SonarRingBuffer get() = ring
    /** Raw measurements stay separate from the bounded, explicitly aggregated image history. */
    val viewTimeImageHistory:SonarTimeImageHistory get()=if(display.freeze)frozenImageHistory?:imageHistory else imageHistory
    private var liveImageAggregated=false
    private var imageBudgetLatched=false
    private var imageBudgetWindowSeconds=Double.NaN
    /** Display LOD is selected when data/preferences change, never from a render/getter loop. */
    val imageHistoryAggregated:Boolean get() = false
    private fun clearImageHistorySelection() {
        liveImageAggregated=false;imageBudgetLatched=false;imageBudgetWindowSeconds=Double.NaN
    }
    private fun selectImageHistory(historySeconds:Double=display.historySeconds) {
        // The raw ring owns all displayed history. No independent mean/peak history is fed.
        liveImageAggregated = false
        val horizonSeconds = if (display.freeze) viewDisplay.historySeconds else historySeconds
        ring = ring.moveToCapacity(sonarVisiblePingBudget(horizonSeconds))
        if (ring.count > 0) ring.evictBefore(ring.timestampAt(0) - horizonSeconds * 1000)
    }
    val viewImageColumns:SonarImageColumns get()=if(imageHistoryAggregated)viewTimeImageHistory else viewRing
    fun visibleImageCount():Int {
        val r=viewImageColumns;if(!r.aggregated)return visibleCount()
        val horizon=viewTimeImageHistory.newestTime-viewDisplay.historySeconds*1000
        var last=0
        for(age in 0 until r.count)if(r.occupiedAt(age)&&r.lastTimestampAt(age)>=horizon)last=age+1
        return last
    }
    fun imageFractionAt(age:Int):Double = ((imageNewestTime()-viewImageColumns.timestampAt(age))/(viewDisplay.historySeconds*1000)).coerceIn(0.0,1.0)
    fun imageNewestTime():Long=if(imageHistoryAggregated)viewTimeImageHistory.newestTime else viewRing.takeIf{it.count>0}?.timestampAt(0)?:0L
    fun imageAvailableSeconds():Double=if(imageHistoryAggregated)viewTimeImageHistory.availableSeconds()else historyCoverage().availableSeconds
    /** Frozen history keeps its own axis calibration; live A-scope uses calibration. */
    val viewCalibration: Calibration get() = if (display.freeze && viewRing.count > 0) viewRing.calibrationAt(0) else calibration
    val viewMetersPerBin: Double? get() = if (display.freeze && viewRing.count > 0) viewRing.metersPerBinAt(0) else metersPerBin
    val viewAmplitudeFullScale: Int get() = if (viewRing.count > 0) viewRing.amplitudeFullScaleAt(0) else amplitudeFullScale
    /** Every frozen/displayed column keeps its own original-versus-derived interpretation. */
    val viewAmplitudeContext: SonarAmplitude? get() = if (viewRing.count > 0) viewRing.amplitudeContextAt(0) else explicitAmplitude
    val viewBottom: SonarBottom get() = if (display.freeze && viewRing.count > 0) viewRing.bottomAt(0) else bottom
    val viewBottomValid: Boolean get() = if (!display.freeze) bottomValid else
        viewMetersPerBin != null && frozenBottomCapability && viewBottom.validated &&
            (frozenPresentation == null || frozenPresentation!!.quality == SonarAnalysisQuality.LOCKED)
    var latest: SonarFrame? = null; private set
    var connected = false; private set
    var running = false; private set
    var demo = false; private set
    var simulated = false; private set
    var source: String? = null; private set
    var error: String? = null; private set
    var sourceStale = false; private set
    var calibration = Calibration(); private set
    var bottom = SonarBottom(); private set
    var geometry: SonarGeometry? = null; private set
    var presentation: SonarPresentation? = null; private set
    var hostPackageVersion: String? = null; private set
    /** Explicitly verified releases carry unchanged 12-bit ADC counts.
     * Unknown producers retain the UINT16 fallback; package version is never
     * inferred from a small observed signal or a calibrated timing flag. */
    private val checkedAmplitudeProducer: Boolean get() = hostPackageVersion in CHECKED_AMPLITUDE_PRODUCERS
    val amplitudeVerified: Boolean get() = explicitAmplitude?.usable ?: checkedAmplitudeProducer
    val amplitudeFullScale: Int get() = explicitAmplitude?.let { if (it.usable) it.fullScale!! else 65535 } ?: if (checkedAmplitudeProducer) 4095 else 65535
    var bottomCapability = false; private set
    var display = SonarDisplay(); private set
    var droppedFrames = 0L; private set
    var frameRateHz: Double? = null; private set
    var latencyMs: Long? = null; private set
    val calibrated: Boolean get() = metersPerBin != null
    val metersPerBin: Double? get() = geometry.let { if (it == null) calibration.metersPerBin else it.metersPerBin }
    val bottomValid: Boolean get() = calibrated && bottomCapability && bottom.validated &&
        (presentation == null || presentation!!.quality == SonarAnalysisQuality.LOCKED)
    fun connect(source: String, demo: Boolean = false, simulated: Boolean = false) {
        require(source.isNotBlank() && source.length <= 80)
        if (!connected && epoch == null) clearEvidence()
        this.source = source; this.demo = demo; this.simulated = simulated; connected = true; error = null
        pingRate.start(nowEpochMs())
    }
    fun disconnect() { connected = false; running = false; imageAuto.reset(); resetRangeFollow() }
    /** A confirmed gateway/selection epoch change clears evidence, never user preferences. */
    fun updateContext(epoch: SonarEpoch, selectedSource: SonarPingSource? = selectedPingSource) {
        if (this.epoch != epoch || selectedPingSource != selectedSource) {
            clearEvidence()
            this.epoch = epoch
            selectedPingSource = selectedSource
            if (connected && running) pingRate.start(nowEpochMs())
        }
    }
    private fun clearEvidence() {
        depthWindow.clear(); frozenDepthDisplay = null
        markImageContinuityBoundary()
        ring.clear(); legacyImageHistory?.clear(); clearImageHistorySelection(); frozenImageHistory?.releaseStorage();frozenRing?.releaseStorage(); frozenImageHistory=null; frozenRing = null; frozenPresentation = null; frozenDisplay = null; frozenBottomCapability = false
        latest = null; lastConfirmedDepth = null; currentPingSource = null; bottom = SonarBottom(); presentation = null; geometry = null; calibration = Calibration()
        bottomCapability = false; explicitAmplitude = null; hostPackageVersion = null
        display = display.copy(freeze = false, rangeM = null, cropStart = 0.0, cropEnd = 1023.0)
        pingRate.clear(); imageAuto.reset(); frameRateHz = null; latencyMs = null; droppedFrames = 0; resetRangeFollow(); unprovenSourceAtEpochMs = null
    }
    fun measuredRate(nowMs: Long = nowEpochMs()): SonarPingRate {
        val result = pingRate.read(nowMs, connected && running && !sourceStale && error == null, selectedPingSource)
        return if (result.state != SonarRateState.STALE && unprovenSourceAtEpochMs?.let { nowMs - it < 5000 } == true)
            result.copy(hz = null, state = SonarRateState.INCOMPLETE) else result
    }
    fun historyCoverage(): SonarHistoryCoverage {
        val r = viewRing
        val available = if (r.count < 2) 0.0 else (r.timestampAt(0) - r.timestampAt(r.count - 1)).coerceAtLeast(0) / 1000.0
        return SonarHistoryCoverage(viewDisplay.historySeconds, available, min(viewDisplay.historySeconds, available),
            r.count == r.capacity && available < viewDisplay.historySeconds)
    }
    /** 0 is newest, 1 is the requested history horizon; gaps remain real elapsed-time gaps. */
    fun historyFractionAt(age: Int): Double =
        ((viewRing.timestampAt(0) - viewRing.timestampAt(age)).toDouble() / (viewDisplay.historySeconds * 1000)).coerceIn(0.0, 1.0)
    /** The current optional relief renderer has one bin axis for every column. */
    fun viewHistoryHasUniformGeometry(): Boolean {
        val r = viewRing
        if (r.count < 2) return true
        val meters = r.metersPerBinValueAt(0)
        for (age in 1 until visibleCount()) {
            val other = r.metersPerBinValueAt(age)
            if (other != meters && !(other.isNaN() && meters.isNaN())) return false
        }
        return true
    }
    /** Drop all acquisition/session evidence, including any frozen view, before a new source run. */
    fun resetAcquisition() {
        depthWindow.clear(); frozenDepthDisplay = null
        markImageContinuityBoundary()
        disconnect(); ring.releaseStorage(); legacyImageHistory?.releaseStorage(); legacyImageHistory=null; clearImageHistorySelection(); frozenImageHistory?.releaseStorage();frozenRing?.releaseStorage(); frozenImageHistory=null; frozenRing = null; frozenBottomCapability = false; latest = null; display = SonarDisplay(exactPingProfiles=display.exactPingProfiles)
        ring = ring.moveToCapacity(sonarVisiblePingBudget(display.historySeconds))
        calibration = Calibration(); bottom = SonarBottom(); bottomCapability = false
        geometry = null; presentation = null; frozenPresentation = null; frozenDisplay = null; hostPackageVersion = null
        epoch = null; selectedPingSource = null; currentPingSource = null; lastConfirmedDepth = null; explicitAmplitude = null; pingRate.clear(); imageAuto.reset(); resetRangeFollow(); unprovenSourceAtEpochMs = null
        droppedFrames = 0; frameRateHz = null; latencyMs = null; source = null; demo = false; simulated = false; error = null; sourceStale = false
    }
    fun setSourceStale(value: Boolean) {
        if (value && !sourceStale) { imageAuto.reset(); resetRangeFollow() }
        sourceStale = value
    }
    fun setError(message: String?) { error = message?.take(140); if (error != null) resetRangeFollow() }
    fun acquisition(start: Boolean) {
        if (start) { check(connected) { "SONAR source unavailable" }; pingRate.start(nowEpochMs()) }
        if (!start) resetRangeFollow()
        running = start
    }
    fun frame(frame: SonarFrame, receivedAtEpochMs: Long = nowEpochMs(), measurementTimeVerified: Boolean = true, pingSource: SonarPingSource? = null, localReceivedAtNanos: Long = 0): SonarSnapshot {
        check(connected && running) { "Start a connected acquisition before providing frames" }
        require(selectedPingSource == null || selectedPingSource == SonarPingSource.ALL || pingSource == null || pingSource == selectedPingSource) {
            "Ping source does not match the selected publication"
        }
        val old = latest
        if (old != null) {
            require(frame.pingId > old.pingId) { "Out-of-order frame discarded; newest frame wins" }
            require(frame.timestampEpochMs >= old.timestampEpochMs) { "Out-of-order timestamp discarded" }
            // In a selected own/native publication, skipped global IDs can be
            // intentionally hidden pings. Actual queue losses come from status.
            if (selectedPingSource == null) droppedFrames += frame.pingId - old.pingId - 1
            if (frame.timestampEpochMs - old.timestampEpochMs > 2500) imageAuto.reset()
        }
        require(presentation == null || presentation!!.pingId == frame.pingId) { "Analysis must describe this exact ping" }
        // Each column owns its physical spacing. Adaptive capture ranges may
        // change that spacing without changing the measured time reference.
        // Crossing a reference/normalization boundary still starts a new segment;
        // an existing frozen copy keeps its original samples and interpretation.
        if (!display.freeze && ring.count > 0 && ((ring.metersPerBinAt(0) == null) != (metersPerBin == null) ||
                ring.geometryAt(0)?.txReference != geometry?.txReference || ring.amplitudeFullScaleAt(0) != amplitudeFullScale)) {markImageContinuityBoundary();ring.clear()}
        if(currentPingSource!=pingSource)imageAuto.reset()
        currentPingSource = pingSource
        val sourceProven = selectedPingSource == null || selectedPingSource == SonarPingSource.ALL || pingSource == selectedPingSource
        if (!sourceProven) {
            bottom = SonarBottom(); presentation = null
            unprovenSourceAtEpochMs = receivedAtEpochMs
        }
        if (!display.freeze) processing("sonar.ring.adopt"){ring.push(frame, calibration, bottom, geometry, amplitudeFullScale, presentation, pingSource, imageSegment, explicitAmplitude, display.imageValues(), localReceivedAtNanos)}
        latest = frame
        val depthAt = depthNowNanos()
        depthWindow.observe(if(connected && running && !sourceStale && error == null) measuredDepth() else null, frame.pingId, frame.timestampEpochMs,
            if (localReceivedAtNanos > 0) localReceivedAtNanos else depthAt, depthAt, depthSettings)
        reconcileGates()
        depth()?.let { lastConfirmedDepth = SonarHistoricalDepth(it, frame.pingId, frame.timestampEpochMs, pingSource, epoch) }
        if (sourceProven) pingRate.observe(frame.pingId, frame.timestampEpochMs.takeIf { measurementTimeVerified }, receivedAtEpochMs)
        frameRateHz = measuredRate(receivedAtEpochMs).hz
        processing("sonar.auto"){if(sourceProven && measurementTimeVerified && pingSource!=null && pingSource!=SonarPingSource.ALL)updateImageAuto(frame)else imageAuto.reset()}
        selectImageHistory()
        latencyMs = (nowEpochMs().toDouble() - frame.timestampEpochMs.toDouble()).coerceIn(0.0, Long.MAX_VALUE.toDouble()).toLong()
        return snapshot()
    }
    fun quality(): SonarQuality {
        if (error != null) return SonarQuality.ERROR
        if (!connected) return SonarQuality.OFFLINE
        if (!running) return SonarQuality.IDLE
        if (sourceStale) return SonarQuality.DEGRADED
        val frame = latest ?: return SonarQuality.IDLE
        val age = nowEpochMs().toDouble() - frame.timestampEpochMs.toDouble()
        if (age > 2500 || age < -5000) return SonarQuality.DEGRADED
        return if (display.freeze) SonarQuality.FROZEN else SonarQuality.ACQUIRING
    }
    fun depth(): Double? {
        if (quality() !in setOf(SonarQuality.ACQUIRING, SonarQuality.FROZEN)) return null
        return measuredDepth()
    }
    /** Per-ping semantic value; display-window expiry uses original source/reception times. */
    private fun measuredDepth(): Double? {
        if (!bottomValid) return null
        var depth = bottom.bin!! * metersPerBin!!
        when (calibration.depthReference) {
            DepthReference.TRANSDUCER -> Unit
            DepthReference.SURFACE -> depth += calibration.transducerOffsetM ?: return null
            DepthReference.KEEL -> depth -= calibration.keelOffsetM ?: return null
        }
        return depth.takeIf { it >= 0 }
    }
    fun setCalibration(calibration: Calibration) {
        if (this.calibration.depthReference != calibration.depthReference ||
            this.calibration.transducerOffsetM != calibration.transducerOffsetM ||
            this.calibration.keelOffsetM != calibration.keelOffsetM) depthWindow.clear()
        if (geometry == null && !display.freeze && !sameGeometry(this.calibration, calibration))
            display = display.copy(rangeM = null, rangeMode = SonarRangeMode.MANUAL, cropStart = 0.0, cropEnd = 1023.0)
        this.calibration = calibration; reconcileGates()
    }
    fun setBottom(bottom: SonarBottom, capability: Boolean = bottomCapability) { this.bottom = bottom; bottomCapability = capability; reconcileGates() }
    /** Atomically adopt interpretation and the geometry belonging to the next frame. */
    fun setAnalysis(geometry: SonarGeometry?, bottom: SonarBottom, presentation: SonarPresentation?, capability: Boolean, hostPackageVersion: String? = null, amplitude: SonarAmplitude? = null) {
        val oldMeters = metersPerBin
        val oldReference = this.geometry?.txReference
        if (oldReference != geometry?.txReference) depthWindow.clear()
        this.geometry = geometry; this.bottom = bottom; this.presentation = presentation; bottomCapability = capability
        val oldScale = amplitudeFullScale
        val oldAmplitudeVerified = amplitudeVerified
        val oldAmplitude = explicitAmplitude
        this.hostPackageVersion = hostPackageVersion
        explicitAmplitude = amplitude
        if (oldScale != amplitudeFullScale || oldAmplitudeVerified != amplitudeVerified || oldAmplitude != explicitAmplitude) {
            imageAuto.reset()
            // Auto can change its actual per-ping decision without a policy change.
            // Such profiles remain distinct even if an upstream display epoch is unchanged.
            markImageContinuityBoundary()
        }
        if (!display.freeze && (oldMeters != metersPerBin || oldReference != geometry?.txReference)) {
            val newMeters = metersPerBin
            display = if (display.rangeMode == SonarRangeMode.AUTO && oldMeters != null && newMeters != null)
                display.copy(rangeM = null, cropStart = 0.0, cropEnd = (display.cropEnd * oldMeters / newMeters).coerceIn(32.0, 1023.0))
            else display.copy(rangeM = null, rangeMode = SonarRangeMode.MANUAL, cropStart = 0.0, cropEnd = 1023.0)
        }
        reconcileGates()
    }
    private fun sameGeometry(a: Calibration, b: Calibration): Boolean =
        a.calibrated == b.calibrated && a.samplePeriodUs == b.samplePeriodUs && a.soundSpeedMps == b.soundSpeedMps
    private fun reconcileGates() {
        if (display.freeze) return
        val confirmedRangeObservation = bottomValid && connected && running && !sourceStale && error == null &&
            (geometry == null || presentation?.let { it.followBottom && it.rangeEndBin != null } == true)
        if (!confirmedRangeObservation) {
            rangeConfirmations = 0
            // HOLD supplies no evidence for a smaller range. Pause the series;
            // do not erase already confirmed time on every one-ping dropout.
            rangeShrinkLastConfirmedAt = null
        }
        if (!calibrated) display = display.copy(rangeM = null, rangeMode = SonarRangeMode.MANUAL)
        if (geometry != null) {
            if (!bottomCapability) display = display.copy(overlayBottom = false)
            // HOLD and late analysis retain the operator's range selection.
            // A freeze recommendation never stops the producer or freezes its samples.
            if (display.rangeMode == SonarRangeMode.AUTO && confirmedRangeObservation && presentation?.followBottom == true)
                presentation?.rangeEndBin?.let { displayBottomBin()?.let { followRange((it * 1.2).coerceIn(32.0, 1023.0)) } }
        } else if (!bottomValid) {
            if (!bottomCapability) display = display.copy(overlayBottom = false)
        } else if (display.rangeMode == SonarRangeMode.AUTO && confirmedRangeObservation) displayBottomBin()?.let { followRange((it * 1.2).coerceIn(32.0, 1023.0)) }
    }
    private fun followRange(target: Double) {
        if (presentation != null && presentation!!.pingId != latest?.pingId) return
        val at = presentation?.pingId ?: latest?.pingId ?: return
        if (rangeLastObservation == at) return
        rangeLastObservation = at
        val now = latest?.timestampEpochMs ?: nowEpochMs()
        if (rangeUpdatedAt?.let { now - it > 2000 || now < it } == true) {
            rangeConfirmations = 0
            resetRangeShrink()
        }
        val current = display.cropEnd
        when {
            target > current * 1.1 -> {
                resetRangeShrink(); rangeConfirmations++
                if (rangeConfirmations >= 3) {
                    display = display.copy(rangeM = null, cropStart = 0.0, cropEnd = target)
                    rangeConfirmations = 0
                }
            }
            target < current * .9 -> {
                rangeConfirmations = 0
                rangeShrinkLastConfirmedAt?.let { previous ->
                    rangeShrinkConfirmedMs += (now - previous).coerceAtLeast(0)
                }
                rangeShrinkLastConfirmedAt = now
                if (rangeShrinkConfirmedMs >= 30000) {
                    display = display.copy(rangeM = null, cropStart = 0.0, cropEnd = target)
                    resetRangeShrink()
                }
            }
            else -> { rangeConfirmations = 0; resetRangeShrink() }
        }
        rangeUpdatedAt = now; rangeFollowed = true
    }
    private fun resetRangeShrink() {
        rangeShrinkConfirmedMs = 0
        rangeShrinkLastConfirmedAt = null
    }
    private fun resetRangeFollow() {
        rangeUpdatedAt = null
        rangeFollowed = false
        rangeConfirmations = 0
        rangeLastObservation = null
        resetRangeShrink()
    }
    /** Explicit diagnostics only; reading must not advance confirmation or source time. */
    fun rangeDiagnosticLine(): String = "mode=${display.rangeMode} freeze=${display.freeze} cropStart=${display.cropStart} cropEnd=${display.cropEnd} " +
        "metersPerBin=$metersPerBin bottomValid=$bottomValid bottomBin=${bottom.bin} quality=${presentation?.quality} " +
        "latestPing=${latest?.pingId} presentationPing=${presentation?.pingId} followBottom=${presentation?.followBottom} " +
        "targetEnd=${bottom.bin?.let{(it*1.2).coerceIn(32.0,1023.0)}} confirmations=$rangeConfirmations " +
        "shrinkConfirmedMs=$rangeShrinkConfirmedMs lastConfirmedAt=$rangeShrinkLastConfirmedAt lastObservation=$rangeLastObservation"
    private fun updateImageAuto(frame: SonarFrame) {
        if (!amplitudeVerified || sourceStale || !connected) { imageAuto.waiting(amplitudeVerified); return }
        if (display.gainMode != SonarImageMode.AUTO && display.gammaMode != SonarImageMode.AUTO && display.thresholdMode != SonarImageMode.AUTO) return
        val now = nowEpochMs()
        if (now - frame.timestampEpochMs !in -5000..2500) { imageAuto.waiting(true); return }
        val value = imageAuto.observe(frame, amplitudeFullScale, frame.timestampEpochMs,
            SonarImageValues(display.gainDb, display.gamma, display.threshold), display.gainMode == SonarImageMode.AUTO, display.historySeconds) ?: return
        if(display.freeze)return // statistics continue; the held image and controls do not change
        val gain = if (display.gainMode == SonarImageMode.AUTO) value.gainDb else display.gainDb
        val gamma = if (display.gammaMode == SonarImageMode.AUTO) value.gamma else display.gamma
        val threshold = if (display.thresholdMode == SonarImageMode.AUTO) value.threshold else display.threshold
        if (gain != display.gainDb || gamma != display.gamma || threshold != display.threshold)
            display = display.copy(gainDb = gain, gamma = gamma, threshold = threshold)
    }
    fun setImageAuto(enabled: Boolean) {
        for (field in SonarImageField.entries) setImageMode(field, if (enabled) SonarImageMode.AUTO else SonarImageMode.MANUAL)
    }
    fun setImageMode(field: SonarImageField, mode: SonarImageMode) {
        val before = display
        val priorMode = when(field) { SonarImageField.GAIN -> before.gainMode; SonarImageField.GAMMA -> before.gammaMode; SonarImageField.THRESHOLD -> before.thresholdMode }
        if (priorMode == mode) return
        if (mode == SonarImageMode.AUTO) ring.rebaseImageMapping(field, when(field) {
            SonarImageField.GAIN -> before.gainDb; SonarImageField.GAMMA -> before.gamma; SonarImageField.THRESHOLD -> before.threshold })
        display = when (field) {
            SonarImageField.GAIN -> display.copy(gainMode = mode,
                manualGainDb = if (display.gainMode == SonarImageMode.MANUAL) display.gainDb else display.manualGainDb,
                gainDb = if (mode == SonarImageMode.MANUAL) display.manualGainDb ?: display.gainDb else display.gainDb)
            SonarImageField.GAMMA -> display.copy(gammaMode = mode,
                manualGamma = if (display.gammaMode == SonarImageMode.MANUAL) display.gamma else display.manualGamma,
                gamma = if (mode == SonarImageMode.MANUAL) display.manualGamma ?: display.gamma else display.gamma)
            SonarImageField.THRESHOLD -> display.copy(thresholdMode = mode,
                manualThreshold = if (display.thresholdMode == SonarImageMode.MANUAL) display.threshold else display.manualThreshold,
                threshold = if (mode == SonarImageMode.MANUAL) display.manualThreshold ?: display.threshold else display.threshold)
        }
        imageAuto.reseed(display.imageValues())
        if(display.freeze)frozenDisplay=display
    }
    fun setAutoGainOffset(value: Double) {
        require(value.isFinite())
        setDisplay(display.copy(autoGainOffsetDb = value.coerceIn(-24.0,24.0)))
    }
    fun setManualImageValue(field: SonarImageField, value: Double) {
        require(value.isFinite())
        setDisplay(when (field) {
            SonarImageField.GAIN -> display.copy(gainDb = value, manualGainDb = value.coerceIn(-24.0, 36.0), gainMode = SonarImageMode.MANUAL)
            SonarImageField.GAMMA -> display.copy(gamma = value, manualGamma = value.coerceIn(.2, 3.0), gammaMode = SonarImageMode.MANUAL)
            SonarImageField.THRESHOLD -> display.copy(threshold = value, manualThreshold = value.coerceIn(0.0, .95), thresholdMode = SonarImageMode.MANUAL)
        })
    }
    /** Pure presentation settings: gain, gamma and threshold never change acquired samples. */
    fun setDisplay(requested: SonarDisplay) {
        val geometryChanged = display.freeze && !requested.freeze && (viewMetersPerBin != metersPerBin ||
            viewRing.count > 0 && viewRing.geometryAt(0)?.txReference != geometry?.txReference)
        val value = if (display.freeze && !requested.freeze) requested.copy(
            overlayBottom = requested.overlayBottom && bottomCapability,
            rangeMode = if (geometryChanged || !bottomValid) SonarRangeMode.MANUAL else requested.rangeMode,
            rangeM = if (geometryChanged) null else requested.rangeM,
            cropStart = if (geometryChanged) 0.0 else requested.cropStart,
            cropEnd = if (geometryChanged) 1023.0 else requested.cropEnd)
        else requested
        require(listOf(value.gainDb, value.gamma, value.threshold, value.cropStart, value.cropEnd, value.scrollSpeed, value.historySeconds, value.autoGainOffsetDb).all { it.isFinite() })
        require(!value.overlayBottom || (if (display.freeze && value.freeze) frozenBottomCapability else bottomCapability)) { "Bottom analysis unavailable" }
        require(value.rangeMode != SonarRangeMode.AUTO || (if (display.freeze && value.freeze) viewBottomValid else bottomValid) ||
            display.rangeMode == SonarRangeMode.AUTO && (if (value.freeze) viewMetersPerBin else metersPerBin) != null) { "Auto range requires calibrated, validated bottom" }
        var next = value.copy(autoGainOffsetDb = value.autoGainOffsetDb.coerceIn(-24.0,24.0), gainDb = value.gainDb.coerceIn(-24.0, 36.0), gamma = value.gamma.coerceIn(.2, 3.0), threshold = value.threshold.coerceIn(0.0, .95),
            manualGainDb = value.manualGainDb?.coerceIn(-24.0, 36.0), manualGamma = value.manualGamma?.coerceIn(.2, 3.0), manualThreshold = value.manualThreshold?.coerceIn(0.0, .95), scrollSpeed = value.scrollSpeed.coerceIn(.5, 4.0), historySeconds = value.historySeconds.coerceIn(10.0, 600.0), cropStart = value.cropStart.coerceIn(0.0, 1022.0), cropEnd = value.cropEnd.coerceIn(1.0, 1023.0))
        val rangeM = next.rangeM
        if (rangeM != null) {
            val meters = (if (value.freeze) viewMetersPerBin else metersPerBin) ?: throw IllegalArgumentException("Meter range requires time-referenced geometry")
            require(rangeM.isFinite() && rangeM > 0 && rangeM <= meters * 1023)
            next = next.copy(cropStart = 0.0, cropEnd = rangeM / meters)
        }
        if (next.cropStart >= next.cropEnd) next = next.copy(cropStart = max(0.0, next.cropEnd - 1))
        if (next.freeze && !display.freeze) {
            rangeConfirmations=0;rangeShrinkLastConfirmedAt=null
            selectImageHistory(next.historySeconds)
            imageFreezeUnavailable = false
            frozenDepthDisplay = liveDepthDisplay()
            frozenBottomCapability = bottomCapability; frozenPresentation = presentation; frozenDisplay = next
        } else if (next.freeze) {
            // Editing the horizon while frozen changes the same ring's budget immediately.
            frozenDisplay = next
            ring = ring.moveToCapacity(sonarVisiblePingBudget(next.historySeconds))
            if (ring.count > 0) ring.evictBefore(ring.timestampAt(0) - next.historySeconds * 1000)
        }
        if (!next.freeze) {
            imageFreezeUnavailable = false; frozenImageHistory = null; frozenRing = null
            frozenBottomCapability = false; frozenPresentation = null; frozenDisplay = null; frozenDepthDisplay = null
            if (display.freeze) { markImageContinuityBoundary() }
        }
        if (next.rangeMode != display.rangeMode) resetRangeFollow()
        display = next; reconcileGates(); selectImageHistory()
    }
    fun setFreeze(freeze: Boolean) = setDisplay(display.copy(freeze = freeze))
    fun displayAmplitude(raw: Int, fullScale: Int = amplitudeFullScale, transfer: SonarDisplay = display): Double {
        require(raw in 0..65535 && fullScale in 1..65535)
        val v = raw / fullScale.toDouble() * 10.0.pow(transfer.gainDb / 20)
        return ((v - transfer.threshold) / (1 - transfer.threshold)).coerceIn(0.0, 1.0).pow(1 / transfer.gamma)
    }
    fun visibleCount(): Int { val r = viewRing; if (r.count == 0) return 0; val newest = r.timestampAt(0); var n = 0; while (n < r.count && newest.toDouble() - r.timestampAt(n).toDouble() <= viewDisplay.historySeconds * 1000) n++; return max(1, n) }
    fun snapshot(): SonarSnapshot = SonarSnapshot(quality(), demo, connected, running, source, latest?.pingId, latest?.timestampEpochMs, depth(), calibration, bottom, display, ring.count, if (display.freeze) ring.count else 0, droppedFrames, frameRateHz, latencyMs, simulated, measuredRate(), historyCoverage(), imageAutoState)
}
