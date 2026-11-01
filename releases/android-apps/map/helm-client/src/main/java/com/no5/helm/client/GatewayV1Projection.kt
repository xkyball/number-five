package com.no5.helm.client

import com.no5.domain.QualityEvaluator
import com.no5.domain.TelemetryStatus
import com.no5.domain.fieldStatus
import com.no5.helm.model.*
import com.no5.helm.network.PiV1Decoder
import java.time.Instant

/** Uses the existing strict decoder and observation times, never projected fallback zeros. */
internal object GatewayV1Projection {
    fun decode(payload: String, now: Long): Snapshot {
        val raw = PiV1Decoder.helm(payload)
        fun live(name: String) = raw.telemetryFields[name]?.value?.takeIf {
            raw.fieldStatus(name, now) == TelemetryStatus.LIVE
        }
        val lat = live("latitudeDegrees")?.takeIf { it in -85.0..85.0 }
        val lon = live("longitudeDegrees")?.takeIf { it in -180.0..180.0 }
        val position = if (lat != null && lon != null) GeoPoint(lat, lon) else null
        val ownship = position?.let { Ownship(it, live("heading")?.let(Math::toRadians),
            live("courseOverGroundDegrees")?.let(Math::toRadians), live("speed")?.div(1.9438444924406), live("depth")) }
        val fixTime = if (position != null) listOfNotNull(raw.telemetryFields["latitudeDegrees"]?.quality?.timestampEpochMs,
            raw.telemetryFields["longitudeDegrees"]?.quality?.timestampEpochMs).minOrNull() else null
        val published = requireNotNull(raw.quality.timestampEpochMs)
        val source = requireNotNull(raw.sourceSessionId)
        val detail = "${raw.quality.source} · ${if (raw.quality.simulated) "SIMULATED" else "REAL"} · ${if (position != null) "Position LIVE" else "Position unbekannt oder veraltet"}"
        return Snapshot(serverId = source, revision = published, generatedAt = Instant.ofEpochMilli(published).toString(),
            state = HelmProjection(ownship, emptyList(), emptyList(), emptyList()), gatewayV1 = true, sourceDetail = detail,
            fixObservedAtEpochMs = fixTime, positionSources = PositionSourcesProjection.decode(payload, now),
            gatewayTelemetryProvenance = GatewayTelemetryProvenance(requireNotNull(raw.quality.source), raw.quality.simulated))
    }
}
