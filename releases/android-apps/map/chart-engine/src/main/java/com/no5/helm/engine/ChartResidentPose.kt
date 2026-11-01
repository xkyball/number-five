package com.no5.helm.engine

import com.no5.helm.chart.ChartViewport
import com.no5.helm.library.ChartPlanCamera
import kotlin.math.*

/** Physical pose check at owner acceptance, separate from the P2 generation tag. */
internal fun residentPoseMatches(planned: ChartViewport, producer: ChartViewport): Boolean {
    fun canonical(view: ChartViewport) = ChartPlanCamera(view.latitude, view.longitude, view.metresToPixels,
        view.rotationRadians, view.width, view.height).canonical()
    val a = canonical(planned); val b = canonical(producer)
    val angle = atan2(sin(a.rotation - b.rotation), cos(a.rotation - b.rotation))
    // Canonical atan2/sin/cos can differ by an ULP after a second normalization.
    // This tolerance is only angle representation, far below a screen pixel.
    return a.latitude == b.latitude && a.longitude == b.longitude && a.pixelsPerMetre == b.pixelsPerMetre &&
        a.width == b.width && a.height == b.height && abs(angle) <= 1e-12
}
