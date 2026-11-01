package com.no5.helm.chart

/** Bound to the source authority which produced the immutable attribute snapshot. */
data class ChartObjectReference(val sourceKey: String, val featureIndex: Int, val sourceGeneration: Long) {
    init { require(sourceKey.isNotBlank() && featureIndex >= 0 && sourceGeneration >= 0) }
}
data class ChartObject(val reference: ChartObjectReference, val id: String, val objectClass: String,
    val primitive: String, val cellName: String, val position: GeoProjection,
    val distancePixels: Double, val attributes: Map<String, String>)
data class ChartObjectQuery(val point: ScreenPoint, val radiusSurfacePixels: Double, val frame: ChartFrame,
    val presentationGeneration: Long, val offset: Int = 0, val filterRevision: Long = 0)
enum class ChartObjectQueryState { READY, EMPTY, PENDING, STALE, UNAVAILABLE, FAILED }
data class ChartObjectQueryResult(val state: ChartObjectQueryState, val objects: List<ChartObject> = emptyList(),
    val total: Int = 0, val nextOffset: Int? = null, val message: String = "")
data class ChartObjectHighlight(val sourceKey: String = "", val featureIndex: Int = -1,
    val visible: Boolean = false, val segments: Int = 0)

data class ChartObjectText(val name: String, val text: String? = null, val message: String = "Zusatztext nicht verfügbar")
