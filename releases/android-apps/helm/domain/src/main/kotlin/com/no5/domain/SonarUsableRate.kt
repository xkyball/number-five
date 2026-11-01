package com.no5.domain

import kotlin.math.abs
import kotlin.math.max

enum class SonarUsableRateState { UNKNOWN, PARTIAL, LIVE, ZERO, STALE }

/** Own LOCKED events counted by Sonard, distinct from selected raw publication and run averages. */
data class SonarUsableRate(
    val hz: Double? = null,
    val state: SonarUsableRateState = SonarUsableRateState.UNKNOWN,
    val count: Long? = null,
    val observedSeconds: Double? = null,
    val complete: Boolean? = null,
    val windowSeconds: Double? = null,
    val startDeviceUs: Long? = null,
    val endDeviceUs: Long? = null,
    val timeBasis: String? = null,
    val observedAtEpochMs: Long? = null,
    val epoch: SonarEpoch? = null,
    val displayedSelection: SonarPingSource? = null,
    val reason: String = "HOST_USABLE_RATE_UNAVAILABLE",
    val statusObservedAtEpochMs: Long? = null,
    val statusTimeBasis: String? = null,
) {
    companion object {
        /** Missing/old evidence never borrows the local raw counter, published Hz or Ø run rate. */
        fun fromDiagnostics(value: SonarDiagnostics?, identity: SourceIdentity, expectedEpoch: SonarEpoch?,
            selectedSource: SonarPingSource?, nowEpochMs: Long, connected: Boolean): SonarUsableRate {
            fun unknown(reason: String, state: SonarUsableRateState = SonarUsableRateState.UNKNOWN) =
                SonarUsableRate(state = state, reason = reason)
            if (!connected) return unknown("SOURCE_STALE", SonarUsableRateState.STALE)
            if (value == null || !value.available) return unknown("HOST_USABLE_RATE_UNAVAILABLE")
            // Analysis may be absent, old or one ping behind the raw stream.
            // Only an independently time-qualified status request proves this host window fresh.
            if (value.statusObservedAtEpochMs == null || value.statusStale == null ||
                value.statusTimeBasis != "GATEWAY_STATUS_REQUEST_STARTED_AT_UTC")
                return unknown("HOST_STATUS_OBSERVATION_UNCONFIRMED")
            if (value.statusStale || nowEpochMs - value.statusObservedAtEpochMs !in -5000..5000)
                return unknown("HOST_STATUS_OBSERVATION_STALE", SonarUsableRateState.STALE)
            if (value.identity != identity || expectedEpoch == null || expectedEpoch.serverSessionId == null ||
                value.serverSessionId != expectedEpoch.serverSessionId || value.generation != expectedEpoch.generation)
                return unknown("HOST_USABLE_RATE_EPOCH_UNCONFIRMED")
            // Duplicated field keys are ambiguous, not a reason to pick whichever arrived last.
            val fields = value.fields.associateBy { "${it.group}.${it.name}" }
            if (fields.size != value.fields.size) return unknown("HOST_USABLE_RATE_AMBIGUOUS_FIELDS")
            fun text(key: String) = fields[key]?.value
            fun number(key: String) = text(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }
            fun integer(key: String) = text(key)?.toLongOrNull()?.takeIf { it >= 0 }
            val selection = text("display_selection.source")?.let { source ->
                SonarPingSource.entries.firstOrNull { it.name.equals(source, ignoreCase = true) }
            }
            val selectionGeneration = integer("display_selection.generation")
            if (selectedSource == null || selection != selectedSource || value.pingSource != selectedSource ||
                expectedEpoch.selectionGeneration == null || selectionGeneration != expectedEpoch.selectionGeneration)
                return unknown("HOST_USABLE_RATE_SELECTION_UNCONFIRMED")
            if (text("host_package.name") != "no5-sonard-openecho" || text("host_package.version") !in setOf("0.4.11", "0.4.12", "0.4.13", "0.4.14", "0.4.15", "0.4.16", "0.4.17", "0.4.18", "0.4.19", "0.4.20", "0.4.21", "0.4.22", "0.4.23", "0.4.24", "0.4.25", "0.4.26", "0.4.27", "0.4.28", "0.4.29", "0.4.30", "0.4.31", "0.4.32", "0.4.33") ||
                text("transmit.rate_control.schema") != "no5.depth-rate-control/1")
                return unknown("HOST_USABLE_RATE_PRODUCER_UNCONFIRMED")
            val prefix = "transmit.ping_rates."
            val window = number(prefix + "window_s")
            val observed = number(prefix + "observed_s")
            val complete = text(prefix + "complete")?.toBooleanStrictOrNull()
            val start = integer(prefix + "start_device_us")
            val end = integer(prefix + "end_device_us")
            val basis = text(prefix + "time_basis")
            val count = integer(prefix + "rates.usable.count")
            val hz = number(prefix + "rates.usable.hz")
            if (window != 5.0 || observed == null || observed <= 0 || observed > 5.00001 || complete == null ||
                start == null || end == null || end < start ||
                abs((end - start) / 1_000_000.0 - observed) > .000002 ||
                basis != "device_marker_us_host_extrapolated_now" || count == null || hz == null || hz < 0 ||
                abs(hz - count / observed) > .000001 * max(1.0, hz))
                return unknown("HOST_USABLE_RATE_WINDOW_UNCONFIRMED")
            val full = complete && observed >= 5.0 - .000001
            return SonarUsableRate(if (full) hz else null,
                if (!full) SonarUsableRateState.PARTIAL else if (count == 0L) SonarUsableRateState.ZERO else SonarUsableRateState.LIVE,
                count, observed, complete, window, start, end, basis, value.observedAtEpochMs,
                expectedEpoch, selectedSource, if (full) "HOST_OWN_USABLE_5S" else "HOST_USABLE_RATE_PARTIAL_WINDOW",
                value.statusObservedAtEpochMs, value.statusTimeBasis)
        }
    }
}
