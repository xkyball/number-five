package com.no5.helm.ocean

import com.no5.domain.FreshnessPolicy
import com.no5.domain.HelmState
import com.no5.domain.TelemetryStatus
import com.no5.domain.WeatherFreshness
import com.no5.domain.WeatherPresentation
import com.no5.domain.WeatherSnapshot
import com.no5.domain.fieldStatus
import com.no5.domain.fieldObservation

/** Raw canonical values only: the ocean owns its single heading interpolation. */
fun oceanSourceInputs(sessionId: String, raw: HelmState, helmPolicy: FreshnessPolicy,
    weather: WeatherSnapshot, weatherPolicy: FreshnessPolicy, nowEpochMs: Long): OceanInputs {
    val heading = if (raw.telemetryFields.isEmpty()) raw.heading else raw.telemetryFields["heading"]?.value
    val weatherStatus = WeatherFreshness.evaluate(weather, nowEpochMs, weatherPolicy)
    // Provider display can retain bounded STALE text. This compatibility flag does not
    // make stale weather eligible for scene lighting; OceanPresentation requires LIVE.
    val staleUsable = weatherStatus == TelemetryStatus.STALE &&
        WeatherPresentation.from(weather, nowEpochMs, weatherPolicy).windSpeed != "—"
    val positionLive=raw.fieldStatus("latitudeDegrees",nowEpochMs,helmPolicy)==TelemetryStatus.LIVE &&
        raw.fieldStatus("longitudeDegrees",nowEpochMs,helmPolicy)==TelemetryStatus.LIVE
    val latitude=raw.fieldObservation("latitudeDegrees",raw.latitudeDegrees).value?.takeIf { positionLive }
    val longitude=raw.fieldObservation("longitudeDegrees",raw.longitudeDegrees).value?.takeIf { positionLive }
    val scene = weather.scene.takeIf { weather.simulated && weatherStatus == TelemetryStatus.LIVE && positionLive }
    return OceanInputs(sessionId = sessionId, headingDegrees = heading,
        headingQuality = raw.fieldStatus("heading", nowEpochMs, helmPolicy).oceanQuality(),
        windSpeedKn = weather.values.windSpeedKn, weatherQuality = weatherStatus.oceanQuality(),
        windCapability = weather.capabilities.wind, staleWeatherUsable = staleUsable,
        headingEpoch = raw.sourceSessionId to raw.simulationRunId,
        windDirectionFromDegrees=weather.values.windDirectionDegrees,
        cloudCoverPercent=weather.values.cloudCoverPercent.takeIf { weather.capabilities.cloudCover },
        visibilityMeters=weather.values.visibilityMeters.takeIf { weather.capabilities.visibility },
        latitudeDegrees=scene?.location?.latitudeDegrees ?: latitude,
        longitudeDegrees=scene?.location?.longitudeDegrees ?: longitude,
        utcEpochMs=scene?.utcEpochMs ?: nowEpochMs)
}

private fun TelemetryStatus.oceanQuality(): OceanInputQuality = when (this) {
    TelemetryStatus.LIVE -> OceanInputQuality.LIVE
    TelemetryStatus.STALE -> OceanInputQuality.STALE
    TelemetryStatus.LOST -> OceanInputQuality.LOST
    TelemetryStatus.INVALID -> OceanInputQuality.INVALID
    TelemetryStatus.UNAVAILABLE -> OceanInputQuality.UNAVAILABLE
}
