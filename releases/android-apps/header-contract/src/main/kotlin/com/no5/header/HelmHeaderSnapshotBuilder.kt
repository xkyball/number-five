package com.no5.header

import com.no5.domain.DisplayUnits
import com.no5.domain.FreshnessPolicy
import com.no5.domain.HelmState
import com.no5.domain.QualityEvaluator
import com.no5.domain.SpeedUnit
import com.no5.domain.TelemetryStatus
import com.no5.domain.fieldStatus

/** Adapter over the existing raw HELM/session model; never over a render/interpolation fixture. */
object HelmHeaderSnapshotBuilder {
    fun build(
        publication: HeaderPublication,
        page: HeaderPage,
        raw: HelmState,
        nowEpochMs: Long,
        confirmedTheme: HeaderTheme,
        policy: FreshnessPolicy = QualityEvaluator.SHARED,
        units: DisplayUnits = DisplayUnits(),
        pageStatus: String? = null,
        transport: HeaderTransport = HeaderTransport.UNKNOWN,
    ): HeaderSnapshotV1 {
        require(publication.packageName == HeaderApps.HELM)
        val profile = requireNotNull(HeaderApps.profile(HeaderApps.HELM, page))
        val aggregate = QualityEvaluator.evaluate(raw.quality, nowEpochMs, policy)
        val unavailable = aggregate in setOf(TelemetryStatus.LOST, TelemetryStatus.INVALID, TelemetryStatus.UNAVAILABLE)
        val staleField = raw.telemetryFields.keys.any { raw.fieldStatus(it, nowEpochMs, policy) == TelemetryStatus.STALE }
        val sourceLabel = when {
            staleField -> "* STALE FIELD"
            raw.quality.simulated -> "NETWORK SIMULATION"
            else -> "SOURCE TELEMETRY"
        }
        val detail = when {
            unavailable -> aggregate.name
            raw.quality.demo -> if (raw.running) "SIMULATOR RUNNING" else "SIMULATOR PAUSED"
            else -> "${aggregate.name} · ${if (raw.running) raw.simulationPhase ?: "RECEIVING" else "PAUSED"}"
        }
        val freshness = HeaderFreshness(policy.staleAfterMs, policy.lostAfterMs)
        val provenance = if (raw.quality.simulated) HeaderProvenance.SIMULATED
            else if (raw.quality.source.isNullOrBlank()) HeaderProvenance.UNKNOWN else HeaderProvenance.REAL

        fun field(name: String, unit: HeaderUnit, convert: (Double) -> Double = { it }): HeaderMeasurement {
            val observation = raw.telemetryFields[name]
            // Never export HelmState()'s default demo scalars or fill an absent field from a
            // source-envelope timestamp. Production gateway fields have their own metadata.
            if (observation == null) return HeaderMeasurement(unit = unit)
            val q = observation.quality
            val status = raw.fieldStatus(name, nowEpochMs, policy)
            return HeaderMeasurement(observation.value?.let(convert), unit, q.source,
                q.timestampEpochMs, status.headerQuality(),
                if (q.simulated) HeaderProvenance.SIMULATED else if (q.source.isNullOrBlank()) HeaderProvenance.UNKNOWN else HeaderProvenance.REAL,
                freshness).at(nowEpochMs)
        }
        val sogUnit = when (units.speed) {
            SpeedUnit.KNOTS -> HeaderUnit.KNOTS
            SpeedUnit.KMH -> HeaderUnit.KMH
            SpeedUnit.MPH -> HeaderUnit.MPH
        }
        val extras = if (profile == HeaderProfile.MARINE_SAFETY) HeaderExtras(
            field("portRpm", HeaderUnit.RPM), field("starboardRpm", HeaderUnit.RPM),
            field("speed", sogUnit, units.speed::fromKnots),
        ) else HeaderExtras()
        val context = if (profile == HeaderProfile.MARINE_SAFETY) HeaderContext(page.name, pageStatus = pageStatus)
            else HeaderContext("EXPLORE FURTHER", "BY WATER")
        return HeaderSnapshotV1(publication, page, confirmedTheme, profile, context,
            HeaderSource(sourceLabel, detail, raw.quality.source, raw.sourceSessionId, transport,
                aggregate.headerQuality(), provenance, freshness), raw.quality.timestampEpochMs, extras).at(nowEpochMs)
    }

    private fun TelemetryStatus.headerQuality() = when (this) {
        TelemetryStatus.LIVE -> HeaderQuality.LIVE
        TelemetryStatus.STALE -> HeaderQuality.STALE
        TelemetryStatus.LOST -> HeaderQuality.LOST
        TelemetryStatus.INVALID -> HeaderQuality.INVALID
        TelemetryStatus.UNAVAILABLE -> HeaderQuality.UNAVAILABLE
    }
}
