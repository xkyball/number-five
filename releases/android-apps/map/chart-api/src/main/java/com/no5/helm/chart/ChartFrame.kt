package com.no5.helm.chart

/** A successfully presented frame. Coordinates are pixels local to its actual Surface. */
data class ChartFrame(
    val revision: Long,
    val cameraToken: String,
    val geometry: ChartSurfaceGeometry,
    val viewport: ChartViewport,
    val scale: ChartScale? = null,
    /** Source activation and geometry mutations are independent of the camera token. */
    val sourceGeneration: Long = 0,
    val contentGeneration: Long = 0,
)
data class ChartScale(val lengthPixels: Double, val distanceMetres: Double)

/** Local Surface gesture. The renderer owns its projection and bounded anchor transformation. */
data class ChartMotion(val anchor: ScreenPoint, val pan: ScreenPoint = ScreenPoint(0.0, 0.0), val zoom: Double = 1.0, val rotation: Double = 0.0) {
    init { require(listOf(anchor.x, anchor.y, pan.x, pan.y, zoom, rotation).all { it.isFinite() } && zoom > 0.0) }
    companion object {
        fun anchored(anchor: ScreenPoint, pan: ScreenPoint = ScreenPoint(0.0, 0.0), zoom: Double = 1.0, rotation: Double = 0.0) = ChartMotion(anchor, pan, zoom, rotation)
    }
}
