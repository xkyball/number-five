package com.no5.helm.chart

/** Immutable load-time coastline segments in Mercator metres. */
class ChartOutlines(val origin: GeoProjection, val coast: FloatArray,
    val toleranceMetres: Double, val fingerprint: String) {
    init { require(coast.size % 4 == 0 && coast.size / 4 <= 50_000)
        require(coast.all { it.isFinite() }) }
}
data class ChartTouchPreview(val camera: ChartViewport, val held: Boolean, val showing: Boolean,
    val generation: Long, val baseFrame: ChartFrame?)
