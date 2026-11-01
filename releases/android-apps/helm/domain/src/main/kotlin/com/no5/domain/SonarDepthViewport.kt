package com.no5.domain

import kotlin.math.max
import kotlin.math.min

enum class SonarViewportState { AVAILABLE, UNCALIBRATED, OUTSIDE_CAPTURE, EMPTY_MANUAL_RANGE }

/** Display coordinates only. Empty views retain safe native bounds, never substitute shallow echoes. */
data class SonarDepthViewport(val startBin: Double, val endBin: Double,
    val state: SonarViewportState = SonarViewportState.AVAILABLE) {
    val available: Boolean get() = state == SonarViewportState.AVAILABLE
}

fun sonarDepthViewport(display: SonarDisplay, metersPerBin: Double?, zoomAnchorBin: Double? = null): SonarDepthViewport {
    fun empty(state: SonarViewportState) = SonarDepthViewport(0.0, 1023.0, state)
    val metric = metersPerBin?.takeIf { it.isFinite() && it > 0.0 }
    if (display.startDepthM > 0.0 && metric == null) return empty(SonarViewportState.UNCALIBRATED)
    if (metric != null && display.startDepthM >= metric * 1023.0) return empty(SonarViewportState.OUTSIDE_CAPTURE)
    val requestedStart = if (metric != null) display.startDepthM / metric else 0.0
    var start = (if (display.startDepthM > 0.0) requestedStart else display.cropStart).coerceIn(0.0, 1023.0)
    var end = display.cropEnd.coerceIn(1.0, 1023.0)
    if (display.rangeMode == SonarRangeMode.AUTO) end = max(end, min(1023.0, start + 32.0))
    if (end <= start) return empty(SonarViewportState.EMPTY_MANUAL_RANGE)
    if (display.zoom) {
        val span = (end - start) / 2.0
        val center = zoomAnchorBin?.takeIf { it.isFinite() } ?: (start + end) / 2.0
        start = (center - span / 2.0).coerceIn(start, end - span)
        end = start + span
    }
    return SonarDepthViewport(start, end)
}
