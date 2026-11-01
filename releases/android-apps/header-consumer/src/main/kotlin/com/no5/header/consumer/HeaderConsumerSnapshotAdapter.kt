package com.no5.header.consumer

import com.no5.header.*
import org.lumina.header.consumer.v1.HeaderConsumerContract as Wire

/** Scalar-only conversion of one accepted ORIGINAL. This function never samples/ages a source. */
object HeaderConsumerSnapshotAdapter {
    fun original(value: HeaderSnapshotV1): Wire.Snapshot {
        val p = value.publication
        return Wire.Snapshot(
            Wire.Publication(p.packageName, p.producerSession, p.foregroundEpoch, p.pageGeneration,
                p.sourceSessionId, p.sequence, p.publishedAtElapsedMs, p.expiresAtElapsedMs),
            Wire.Page.valueOf(value.pageId.name), Wire.Theme.valueOf(value.theme.name),
            Wire.Profile.valueOf(value.profile.name),
            Wire.Context(value.context.title, value.context.subtitle, value.context.pageStatus),
            value.source.let { Wire.Source(it.label, it.detail, it.sourceId, it.backendSessionId,
                Wire.Transport.valueOf(it.transport.name), Wire.Quality.valueOf(it.quality.name),
                Wire.Provenance.valueOf(it.provenance.name), freshness(it.freshness)) },
            value.sourceObservedAtEpochMs,
            Wire.Extras(measurement(value.extras.portRpm), measurement(value.extras.starboardRpm), measurement(value.extras.sog)),
            value.schemaVersion,
        )
    }
    fun reason(value: HeaderClearReason): Wire.Reason = Wire.Reason.valueOf(value.name)
    private fun freshness(value: HeaderFreshness) = Wire.Freshness(value.staleAfterMs, value.lostAfterMs)
    private fun measurement(value: HeaderMeasurement?) = value?.let {
        Wire.Measurement(it.value, Wire.Unit.valueOf(it.unit.name), it.sourceId, it.sourceObservedAtEpochMs,
            Wire.Quality.valueOf(it.quality.name), Wire.Provenance.valueOf(it.provenance.name), freshness(it.freshness))
    }
}
