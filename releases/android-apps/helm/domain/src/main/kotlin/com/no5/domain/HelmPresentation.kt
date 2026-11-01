package com.no5.domain

/**
 * Frame-clock presentation of read-only HELM motion. The adapter snapshot stays authoritative.
 *
 * Each changed motion sample starts a finite linear transition from the value already on screen
 * to the new sample. A subsequent sample may retarget it without a jump. The last sample is
 * reached in [transitionDurationMs], even after a 1 Hz feed stops; there is no extrapolation,
 * per-frame easing factor, or fabricated source timestamp. A 20 Hz feed uses the same model.
 *
 * Arrival and render times must use the same monotonic clock, in milliseconds. Call [accept]
 * when a snapshot arrives, and [render] from the display frame clock. Pass freshness evaluated
 * from [latestRaw] into render, and retain raw snapshots for decisions, alerts, and source uses.
 * A new source session should get a new model. Direct touch edits and explicit lifecycle/reset
 * boundaries use [reset] or accept(immediate = true). This model performs no I/O or scheduling.
 */
class HelmPresentationModel(
    initial: HelmState,
    initialAtMonotonicMs: Long = 0L,
    val transitionDurationMs: Long = 100L,
    val maxInterpolationGapMs: Long = 1_500L,
) {
    init {
        require(transitionDurationMs in 1..1_000) { "Transition must take 1–1000 ms" }
        require(maxInterpolationGapMs >= transitionDurationMs) { "Gap must cover a transition" }
    }

    var latestRaw: HelmState = initial
        private set

    private var from = initial
    private var startedAtMs = initialAtMonotonicMs
    private var lastArrivalMs = initialAtMonotonicMs
    private var lastRenderMs = initialAtMonotonicMs
    private var lastRenderedStatus = initial.quality.status
    private var transitioning = false

    /**
     * Returns false for an older arrival/source timestamp in the same stream. A source or run
     * change and explicit immediate input establish a new stream and may reset its clocks.
     * Duplicate motion samples update raw metadata/critical fields without extending a deadline.
     */
    @Synchronized
    fun accept(snapshot: HelmState, arrivedAtMonotonicMs: Long, immediate: Boolean = false): Boolean {
        val previous = latestRaw
        val boundary = sourceChanged(previous, snapshot) || previous.simulationRunId != snapshot.simulationRunId || previous.sourceSessionId != snapshot.sourceSessionId
        if (!immediate && !boundary) {
            if (arrivedAtMonotonicMs < lastArrivalMs) return false
            val oldStamp = previous.quality.timestampEpochMs
            val newStamp = snapshot.quality.timestampEpochMs
            if (snapshot.telemetryFields.isEmpty() && oldStamp != null && newStamp != null && newStamp < oldStamp) return false
        }

        val mustSnap = immediate || boundary || !snapshot.running || previous.running != snapshot.running ||
            previous.quality.status != TelemetryStatus.LIVE || snapshot.quality.status != TelemetryStatus.LIVE ||
            lastRenderedStatus != TelemetryStatus.LIVE || snapshot.timeTick < previous.timeTick ||
            simulationTimeReversed(previous, snapshot) ||
            elapsed(arrivedAtMonotonicMs, lastArrivalMs) > maxInterpolationGapMs
        if (mustSnap) {
            reset(snapshot, arrivedAtMonotonicMs)
            return true
        }

        // A queued callback can arrive after the next render frame. Never rewind the visible
        // transition when its arrival time is still newer than the prior accepted sample.
        val start = maxOf(arrivedAtMonotonicMs, lastRenderMs)
        val visible = interpolateAt(start)
        val motionChanged = Motion.of(previous) != Motion.of(snapshot)
        latestRaw = snapshot
        lastArrivalMs = arrivedAtMonotonicMs
        if (motionChanged) {
            from = visible
            startedAtMs = start
            transitioning = Motion.of(from) != Motion.of(snapshot)
        }
        return true
    }

    /** Snap at direct-input, lifecycle, or reset boundaries; metadata remains exactly raw. */
    @Synchronized
    fun reset(snapshot: HelmState = latestRaw, nowMonotonicMs: Long) {
        latestRaw = snapshot
        from = snapshot
        startedAtMs = nowMonotonicMs
        lastArrivalMs = nowMonotonicMs
        lastRenderMs = nowMonotonicMs
        lastRenderedStatus = snapshot.quality.status
        transitioning = false
    }

    /**
     * Only LIVE data may move. A STALE/LOST/INVALID/UNAVAILABLE status immediately stops a
     * transition at the latest raw sample. Status itself is not written into raw quality:
     * the normal quality evaluator remains the sole authority for age and visibility.
     */
    @Synchronized
    fun render(
        nowMonotonicMs: Long,
        status: TelemetryStatus = latestRaw.quality.status,
    ): HelmState {
        if (nowMonotonicMs < lastRenderMs) {
            reset(latestRaw, nowMonotonicMs)
        }
        lastRenderMs = nowMonotonicMs
        lastRenderedStatus = status
        if (status != TelemetryStatus.LIVE || !latestRaw.running) {
            from = latestRaw
            transitioning = false
            return latestRaw
        }
        val result = interpolateAt(nowMonotonicMs)
        if (elapsed(nowMonotonicMs, startedAtMs) >= transitionDurationMs) transitioning = false
        return result
    }

    /** Scheduling hint only; interpolation and arrival semantics remain unchanged. */
    @Synchronized fun isTransitioning(): Boolean = transitioning

    private fun interpolateAt(now: Long): HelmState {
        if (!transitioning) return latestRaw
        val fraction = (elapsed(now, startedAtMs) / transitionDurationMs.toDouble()).coerceIn(0.0, 1.0)
        if (fraction >= 1.0) return latestRaw
        // Start with the latest snapshot. Nothing outside this explicit motion allowlist is
        // delayed: oil, coolant, voltage, fuel, depth, gears, clocks, flags and quality stay raw.
        fun live(name: String): Boolean = listOf(from, latestRaw).all { snapshot ->
            snapshot.telemetryFields.isEmpty() || snapshot.telemetryFields[name]?.let {
                it.value != null && it.value.isFinite() && it.quality.status == TelemetryStatus.LIVE
            } == true
        } && run {
            val a=from.telemetryFields[name]?.quality?.timestampEpochMs
            val b=latestRaw.telemetryFields[name]?.quality?.timestampEpochMs
            a==null || b==null || b.toDouble()-a.toDouble() <= maxInterpolationGapMs
        }
        return latestRaw.copy(
            portRpm = if(live("portRpm")) linear(from.portRpm, latestRaw.portRpm, fraction) else latestRaw.portRpm,
            starboardRpm = if(live("starboardRpm")) linear(from.starboardRpm, latestRaw.starboardRpm, fraction) else latestRaw.starboardRpm,
            portTrim = if(live("portTrim")) linear(from.portTrim, latestRaw.portTrim, fraction) else latestRaw.portTrim,
            starboardTrim = if(live("starboardTrim")) linear(from.starboardTrim, latestRaw.starboardTrim, fraction) else latestRaw.starboardTrim,
            speed = if(live("speed")) linear(from.speed, latestRaw.speed, fraction) else latestRaw.speed,
            heading = if(live("heading")) circular(from.heading, latestRaw.heading, fraction, 0.0) else latestRaw.heading,
            latitudeDegrees = if(live("latitudeDegrees")) nullableLinear(from.latitudeDegrees, latestRaw.latitudeDegrees, fraction) else latestRaw.latitudeDegrees,
            longitudeDegrees = if(live("longitudeDegrees")) nullableCircular(from.longitudeDegrees, latestRaw.longitudeDegrees, fraction, -180.0) else latestRaw.longitudeDegrees,
            courseOverGroundDegrees = if(live("courseOverGroundDegrees")) nullableCircular(from.courseOverGroundDegrees, latestRaw.courseOverGroundDegrees, fraction, 0.0) else latestRaw.courseOverGroundDegrees,
        )
    }

    private fun sourceChanged(a: HelmState, b: HelmState): Boolean =
        a.quality.source != b.quality.source || a.quality.demo != b.quality.demo ||
            a.quality.simulated != b.quality.simulated

    private fun simulationTimeReversed(a: HelmState, b: HelmState): Boolean {
        val first = a.simulationElapsedSeconds ?: return false
        val second = b.simulationElapsedSeconds ?: return false
        return first.isFinite() && second.isFinite() && second < first
    }

    // Subtract in floating point to avoid wrapping Long on a clock-boundary input.
    private fun elapsed(now: Long, before: Long) = now.toDouble() - before.toDouble()

    private fun linear(a: Double, b: Double, fraction: Double): Double =
        if (a == b) b else if (a.isFinite() && b.isFinite()) a * (1.0 - fraction) + b * fraction else b

    private fun nullableLinear(a: Double?, b: Double?, fraction: Double): Double? =
        if (a == null || b == null) b else linear(a, b, fraction)

    private fun circular(a: Double, b: Double, fraction: Double, minimum: Double): Double {
        if (a == b) return b
        if (!a.isFinite() || !b.isFinite()) return b
        val delta = normalize(b - a, -180.0)
        return normalize(a + delta * fraction, minimum)
    }

    private fun nullableCircular(a: Double?, b: Double?, fraction: Double, minimum: Double): Double? =
        if (a == null || b == null) b else circular(a, b, fraction, minimum)

    private fun normalize(value: Double, minimum: Double): Double =
        ((value - minimum) % 360.0 + 360.0) % 360.0 + minimum

    private data class Motion(
        val portRpm: Double, val starboardRpm: Double, val portTrim: Double, val starboardTrim: Double,
        val speed: Double, val heading: Double, val latitude: Double?, val longitude: Double?, val course: Double?,
    ) {
        companion object {
            fun of(value: HelmState) = Motion(value.portRpm, value.starboardRpm, value.portTrim,
                value.starboardTrim, value.speed, value.heading, value.latitudeDegrees,
                value.longitudeDegrees, value.courseOverGroundDegrees)
        }
    }
}
