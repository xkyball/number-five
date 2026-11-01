package com.no5.domain

/** Wall-clock timestamps come from the adapter. Host time never becomes vessel telemetry. */
enum class TelemetryStatus { LIVE, STALE, LOST, INVALID, UNAVAILABLE }
data class TelemetryQuality(
    val status: TelemetryStatus = TelemetryStatus.UNAVAILABLE,
    val timestampEpochMs: Long? = null,
    val source: String? = null,
    val demo: Boolean = false,
    /** Provenance independent of freshness: network simulations still expire normally. */
    val simulated: Boolean = demo,
)
data class FreshnessPolicy(val staleAfterMs: Long = 5_000, val lostAfterMs: Long = 30_000) {
    init { require(staleAfterMs > 0 && lostAfterMs >= staleAfterMs) }
}
object QualityEvaluator {
    val SHARED = FreshnessPolicy()
    val AUDIO = FreshnessPolicy(staleAfterMs = 2_500)
    fun evaluate(quality: TelemetryQuality, nowEpochMs: Long, policy: FreshnessPolicy = SHARED): TelemetryStatus {
        if (quality.status in setOf(TelemetryStatus.INVALID, TelemetryStatus.UNAVAILABLE, TelemetryStatus.LOST)) return quality.status
        // Explicit simulation is labeled and never represented as gateway freshness.
        if (quality.demo) return quality.status
        val stamp = quality.timestampEpochMs ?: return TelemetryStatus.INVALID
        if (quality.source.isNullOrBlank()) return TelemetryStatus.INVALID
        val age = nowEpochMs.toDouble() - stamp.toDouble()
        if (age < -5_000) return TelemetryStatus.STALE
        if (age > policy.lostAfterMs) return TelemetryStatus.LOST
        if (age > policy.staleAfterMs || quality.status == TelemetryStatus.STALE) return TelemetryStatus.STALE
        return TelemetryStatus.LIVE
    }
}
data class TelemetryValue(
    val value: Double?,
    val quality: TelemetryQuality = TelemetryQuality(),
) {
    fun displayValue(nowEpochMs: Long, range: ClosedFloatingPointRange<Double>, policy: FreshnessPolicy = QualityEvaluator.SHARED): Double? =
        value?.takeIf { it.isFinite() && it in range && QualityEvaluator.evaluate(quality, nowEpochMs, policy) in setOf(TelemetryStatus.LIVE, TelemetryStatus.STALE) }
}
enum class Theme { DAY, NIGHT }
enum class OperatingMode { UNAVAILABLE, DEMO, LIVE }
