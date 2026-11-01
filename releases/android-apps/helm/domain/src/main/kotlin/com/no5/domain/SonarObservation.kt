package com.no5.domain

/** Selected publication is independent of the acoustic producer of a single ping. */
enum class SonarPingSource { OWN, NATIVE, ALL }
data class SonarDisplaySelection(val source: SonarPingSource?, val generation: Long,
    val publishedHz: Double? = null, val observedAtEpochMs: Long? = null,
    val lastPublishedPingId: Long? = null, val selectionGeneration: Long? = null) {
    init {
        require(generation >= 0 && (lastPublishedPingId == null || lastPublishedPingId >= 0))
        require(selectionGeneration == null || selectionGeneration >= 0)
        require(publishedHz == null || publishedHz.isFinite() && publishedHz >= 0)
    }
}
data class SonarAmplitude(val fullScale: Int?, val adcBits: Int? = null,
    val verified: Boolean = false, val producer: String? = null,
    val displayTransform: String? = null, val noiseRevision: Long? = null,
    val noiseActive: Boolean? = null) {
    val hasDisplayContext: Boolean get() = displayTransform != null || noiseRevision != null || noiseActive != null
    /** Absent extension is legacy. A partial/contradictory extension cannot confirm a scale. */
    val displayContextValid: Boolean get() = !hasDisplayContext || noiseRevision != null && noiseRevision >= 0 &&
        when (noiseActive) {
            true -> displayTransform == "background_reduction_then_max_pool_or_interpolate"
            false -> displayTransform == "max_pool_or_interpolate_without_amplitude_scaling"
            null -> false
        }
    val usable: Boolean get() = verified && fullScale != null && fullScale in 1..65535 &&
        (adcBits == null || adcBits in 1..16 && fullScale == (1 shl adcBits) - 1) && displayContextValid
}
data class SonarEpoch(val serverSessionId: String?, val generation: Long,
    val selectionGeneration: Long? = null) {
    init { require(generation >= 0 && (selectionGeneration == null || selectionGeneration >= 0)) }
}
data class SonarSourceSelectionRequest(val sessionId: String, val requestId: Long, val source: SonarPingSource)
data class SonarSourceSelectionState(val identity: SourceIdentity, val generation: Long = 0,
    val available: Boolean = false, val selection: SonarDisplaySelection? = null)
data class SonarSourceSelectionOutcome(val sessionId: String, val requestId: Long, val commandId: Long?,
    val source: SonarPingSource, val status: SonarFilterCommandStatus, val reason: String)

/** A reported host condition. A null event time never becomes the websocket publication time. */
data class SonarObservationReason(val code: String, val detail: String?,
    val observedAtEpochMs: Long?, val statusObservedAtEpochMs: Long,
    val latched: Boolean, val statusFresh: Boolean, val version: Int = 1) {
    init {
        require(version == 1 && code.isNotBlank() && code.length <= 64 && code.none(Char::isISOControl))
        require(detail == null || detail.length <= 256 && detail.none(Char::isISOControl))
        require(observedAtEpochMs == null || observedAtEpochMs > 0)
        require(statusObservedAtEpochMs > 0)
    }
}

/** Exact textual representations retain integer IDs and never imply a numeric measurement. */
data class SonarDiagnosticField(val group: String, val name: String, val value: String, val unit: String? = null) {
    init { require(group.length <= 80 && name.length <= 80 && value.length <= 2048 && (unit == null || unit.length <= 40)) }
}
data class SonarDiagnostics(val identity: SourceIdentity, val generation: Long = 0,
    val serverSessionId: String? = null, val pingId: Long? = null, val observedAtEpochMs: Long? = null,
    val pingSource: SonarPingSource? = null, val fields: List<SonarDiagnosticField> = emptyList(),
    val trackTotal: Int = 0, val trackOmitted: Int = 0, val blockTotal: Int = 0, val blockOmitted: Int = 0,
    val available: Boolean = false, val stale: Boolean = false,
    /** Separate request-start evidence for status/rates; never a replacement ping timestamp. */
    val statusObservedAtEpochMs: Long? = null, val statusStale: Boolean? = null,
    val statusTimeBasis: String? = null) {
    init {
        require(statusObservedAtEpochMs == null || statusObservedAtEpochMs > 0)
        require(statusTimeBasis == null || statusTimeBasis.length <= 80 && statusTimeBasis.none(Char::isISOControl))
        require(generation >= 0 && (pingId == null || pingId >= 0) && fields.size <= 128)
        require(trackTotal >= 0 && trackOmitted in 0..trackTotal && blockTotal >= 0 && blockOmitted in 0..blockTotal)
        require(fields.sumOf { it.group.length + it.name.length + it.value.length + (it.unit?.length ?: 0) } <= 24_000)
    }
}

enum class SonarRateState { WARMING, LIVE, ZERO, STALE, INCOMPLETE }
enum class SonarRateTimeBasis { MEASUREMENT, RECEPTION }
data class SonarPingRate(val hz: Double?, val state: SonarRateState, val eventCount: Int,
    val coverageMs: Long, val windowStartEpochMs: Long, val windowEndEpochMs: Long,
    val timeBasis: SonarRateTimeBasis, val selectedSource: SonarPingSource?, val windowMs: Long = 5000)

/** Bounded unique-event counter; timestamps are acquisition times, never status publications. */
class SonarPingRateWindow(private val capacity: Int = 8192) {
    init { require(capacity >= 2) }
    private val ids = LongArray(capacity)
    private val times = LongArray(capacity)
    private var head = 0
    private var count = 0
    private var startedAt: Long? = null
    private var basis = SonarRateTimeBasis.MEASUREMENT
    private var lastResult: SonarPingRate? = null
    private var lastDisplayAt = Long.MIN_VALUE
    private var lostBefore: Long? = null
    fun start(atEpochMs: Long) { if (startedAt == null) startedAt = atEpochMs }
    fun clear() { head = 0; count = 0; startedAt = null; lastResult = null; lastDisplayAt = Long.MIN_VALUE; lostBefore = null }
    fun observe(pingId: Long, measuredAtEpochMs: Long?, receivedAtEpochMs: Long): Boolean {
        require(pingId >= 0)
        val nextBasis = if (measuredAtEpochMs == null) SonarRateTimeBasis.RECEPTION else SonarRateTimeBasis.MEASUREMENT
        if (startedAt != null && basis != nextBasis) clear()
        basis = nextBasis
        val time = measuredAtEpochMs ?: receivedAtEpochMs
        if (startedAt == null) startedAt = minOf(time, receivedAtEpochMs)
        // Check IDs, not adjacent deltas: interleaved native/own IDs need not be contiguous.
        for (age in 0 until count) if (ids[(head - 1 - age + capacity) % capacity] == pingId) return false
        if (count == capacity) lostBefore = times[head]
        ids[head] = pingId; times[head] = time; head = (head + 1) % capacity; count = minOf(capacity, count + 1)
        return true
    }
    fun read(nowEpochMs: Long, live: Boolean, selectedSource: SonarPingSource? = null): SonarPingRate {
        val start = nowEpochMs - 5000
        var events = 0
        for (age in 0 until count) if (times[(head - 1 - age + capacity) % capacity] in (start + 1)..nowEpochMs) events++
        val coverage = (nowEpochMs - (startedAt ?: nowEpochMs)).coerceIn(0, 5000)
        val state = when {
            !live -> SonarRateState.STALE
            lostBefore?.let { it > start } == true -> SonarRateState.INCOMPLETE
            coverage < 5000 -> SonarRateState.WARMING
            events == 0 -> SonarRateState.ZERO
            else -> SonarRateState.LIVE
        }
        val result = SonarPingRate(if (state == SonarRateState.STALE || state == SonarRateState.INCOMPLETE) null else
            if (coverage == 0L) 0.0 else events * 1000.0 / coverage, state, events, coverage,
            maxOf(start, startedAt ?: nowEpochMs), nowEpochMs, basis, selectedSource)
        val previous = lastResult
        // State/zero/error changes are immediate; live digits update at most twice a second.
        if (previous != null && previous.state == state && nowEpochMs >= lastDisplayAt && nowEpochMs - lastDisplayAt < 500)
            return previous
        lastDisplayAt = nowEpochMs; lastResult = result
        return result
    }
}

data class SonarHistoryCoverage(val requestedSeconds: Double, val availableSeconds: Double,
    val visibleSeconds: Double, val capacityLimited: Boolean)

data class SonarHistoricalDepth(val depthM: Double, val pingId: Long, val observedAtEpochMs: Long,
    val pingSource: SonarPingSource?, val epoch: SonarEpoch?) {
    fun ageMs(nowEpochMs: Long): Long = (nowEpochMs - observedAtEpochMs).coerceAtLeast(0)
}
