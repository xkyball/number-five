package com.no5.domain

/** One actual source observation. A break forbids drawing a line from the previous point. */
data class DepthHistoryPoint(
    val timestampEpochMs: Long,
    val depthMeters: Double,
    val breakBefore: Boolean,
)

data class DepthHistorySnapshot(
    val points: List<DepthHistoryPoint>,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val windowMs: Long,
) {
    companion object {
        fun empty() = DepthHistorySnapshot(emptyList(), 0L, 120_000L, 120_000L)
    }
}

/**
 * Bounded, raw source depth history, independent of display frames and speed interpolation.
 *
 * Only a fresh, timestamped source value can append a point. Clock age may remove points or
 * open a gap, but never invents a measurement. Source/session/run changes clear the history;
 * stale/lost/invalid data and reconnect gaps remain visibly disconnected. A 20 Hz stream fits
 * the full two-minute default window. Higher rates remain bounded by [maxPoints].
 */
class DepthHistory(
    val windowMs: Long = 120_000L,
    val maxPoints: Int = 2_401,
    val maxContinuousGapMs: Long = 1_500L,
) {
    init {
        require(windowMs > 0L)
        require(maxPoints >= 2)
        require(maxContinuousGapMs > 0L)
    }

    private data class StreamKey(val session: String, val gatewaySession: String?, val source: String?, val simulated: Boolean, val run: String?)
    private val points = ArrayDeque<DepthHistoryPoint>()
    private var stream: StreamKey? = null
    private var lastObservedStamp: Long? = null
    private var lastSimulationElapsedSeconds: Double? = null
    private var breakBeforeNext = true
    private var revision = 0L
    private var cachedRevision = -1L
    private var cachedPoints: List<DepthHistoryPoint> = emptyList()
    private var cachedAtMs = Long.MIN_VALUE
    private var nextFutureStamp: Long? = null
    private var cachedSnapshot: DepthHistorySnapshot? = null

    /** Returns true only when a new actual depth observation was appended. */
    @Synchronized
    fun accept(
        raw: HelmState,
        nowEpochMs: Long,
        sessionId: String,
        policy: FreshnessPolicy = QualityEvaluator.SHARED,
    ): Boolean {
        require(sessionId.isNotBlank())
        val field = depthField(raw)
        val key = StreamKey(sessionId, raw.sourceSessionId, field.quality.source, field.quality.simulated, raw.simulationRunId)
        val simulationElapsed = raw.simulationElapsedSeconds?.takeIf { it.isFinite() }
        val clockReversed = simulationElapsed != null && lastSimulationElapsedSeconds?.let { simulationElapsed < it } == true
        if (key != stream || clockReversed) {
            clear()
            stream = key
        }
        if (simulationElapsed != null) lastSimulationElapsedSeconds = simulationElapsed
        prune(nowEpochMs)
        val stamp = field.quality.timestampEpochMs
        // Even test/demo provenance needs a real raw stamp and source for a history point.
        if (stamp == null || stamp < 0L || field.quality.source.isNullOrBlank() || status(raw, nowEpochMs, policy) != TelemetryStatus.LIVE) {
            markGap()
            return false
        }
        val previousStamp = lastObservedStamp
        if (previousStamp != null && stamp <= previousStamp) return false
        lastObservedStamp = stamp
        val depth = field.value?.takeIf { it.isFinite() && it in 0.0..12_000.0 }
        if (depth == null) {
            markGap()
            return false
        }
        val previous = points.lastOrNull()
        val disconnected = breakBeforeNext || previous == null ||
            stamp.toDouble() - previous.timestampEpochMs.toDouble() > maxContinuousGapMs
        points.addLast(DepthHistoryPoint(stamp, depth, disconnected))
        revision++
        breakBeforeNext = false
        while (points.size > maxPoints) points.removeFirst()
        return true
    }

    /** Called on lifecycle discontinuities or observed source-quality loss. */
    @Synchronized
    fun markGap() { breakBeforeNext = true }

    @Synchronized
    fun clear() {
        points.clear()
        revision++
        stream = null
        lastObservedStamp = null
        lastSimulationElapsedSeconds = null
        breakBeforeNext = true
    }

    /** Wall clock only positions the window. No new points are created by rendering. */
    @Synchronized
    fun snapshot(nowEpochMs: Long, quality: TelemetryStatus = TelemetryStatus.LIVE): DepthHistorySnapshot {
        if (quality != TelemetryStatus.LIVE) markGap()
        prune(nowEpochMs)
        val start = if (nowEpochMs < Long.MIN_VALUE + windowMs) Long.MIN_VALUE else nowEpochMs - windowMs
        if (cachedRevision != revision || nowEpochMs < cachedAtMs || nextFutureStamp?.let { it <= nowEpochMs } == true) {
            cachedPoints = points.filter { it.timestampEpochMs <= nowEpochMs }
            nextFutureStamp = points.firstOrNull { it.timestampEpochMs > nowEpochMs }?.timestampEpochMs
            cachedRevision = revision
        }
        cachedAtMs = nowEpochMs
        val previous = cachedSnapshot
        if (previous != null && previous.points === cachedPoints && previous.endEpochMs == nowEpochMs) return previous
        return DepthHistorySnapshot(cachedPoints, start, nowEpochMs, windowMs).also { cachedSnapshot = it }
    }

    /** Scheduling metadata only: the snapshot's points need work only on revision/time boundaries. */
    @Synchronized fun version():Long=revision
    @Synchronized fun nextSnapshotChange(now:Long):Long? {
        val future=points.firstOrNull{it.timestampEpochMs>now}?.timestampEpochMs
        val first=points.firstOrNull()?.timestampEpochMs
        val expiry=first?.takeIf{it<=Long.MAX_VALUE-windowMs-1}?.let{it+windowMs+1}?.takeIf{it>now}
        return listOfNotNull(future,expiry).minOrNull()
    }

    companion object {
        /** Independent depth freshness. An absent field in a populated map is unknown. */
        fun status(raw: HelmState, nowEpochMs: Long, policy: FreshnessPolicy = QualityEvaluator.SHARED): TelemetryStatus {
            if (raw.quality.status == TelemetryStatus.LOST || raw.quality.status == TelemetryStatus.INVALID ||
                raw.quality.status == TelemetryStatus.UNAVAILABLE)
                return raw.quality.status
            val field = depthField(raw)
            if (field.value == null || !field.value.isFinite() || field.value !in 0.0..12_000.0)
                return TelemetryStatus.UNAVAILABLE
            val quality = if (field.quality.demo) field.quality.copy(demo = false) else field.quality
            return QualityEvaluator.evaluate(quality, nowEpochMs, policy)
        }

        private fun depthField(raw: HelmState): TelemetryValue = raw.fieldObservation("depth", raw.depth)
    }

    private fun prune(nowEpochMs: Long) {
        val start = nowEpochMs.toDouble() - windowMs.toDouble()
        while (points.firstOrNull()?.timestampEpochMs?.toDouble()?.let { it < start } == true) {
            points.removeFirst()
            revision++
        }
    }
}
