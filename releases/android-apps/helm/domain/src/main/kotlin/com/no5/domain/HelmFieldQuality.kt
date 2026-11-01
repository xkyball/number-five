package com.no5.domain

/** Missing legacy metadata is permitted only for explicit fixture snapshots. */
fun HelmState.fieldObservation(name: String, legacyValue: Double?): TelemetryValue =
    telemetryFields[name] ?: if (telemetryFields.isEmpty()) TelemetryValue(legacyValue, quality)
    else TelemetryValue(null, quality.copy(status = TelemetryStatus.UNAVAILABLE))

fun HelmState.fieldStatus(name: String, nowEpochMs: Long, policy: FreshnessPolicy = QualityEvaluator.SHARED): TelemetryStatus {
    if (quality.status in setOf(TelemetryStatus.LOST, TelemetryStatus.INVALID, TelemetryStatus.UNAVAILABLE)) return quality.status
    val observation = telemetryFields[name] ?: return if (telemetryFields.isEmpty()) QualityEvaluator.evaluate(quality, nowEpochMs, policy) else TelemetryStatus.UNAVAILABLE
    if (observation.value == null || !observation.value.isFinite()) return TelemetryStatus.UNAVAILABLE
    return QualityEvaluator.evaluate(observation.quality, nowEpochMs, policy)
}

/** The projection can be an interpolated visual value; validity always belongs to the raw field. */
fun HelmState.displayField(name: String, projection: Double?, nowEpochMs: Long, policy: FreshnessPolicy = QualityEvaluator.SHARED): Double? {
    val status = fieldStatus(name, nowEpochMs, policy)
    val value = if(status == TelemetryStatus.STALE && telemetryFields.isNotEmpty()) telemetryFields[name]?.value else projection
    return value?.takeIf { it.isFinite() && status in setOf(TelemetryStatus.LIVE, TelemetryStatus.STALE) }
}
