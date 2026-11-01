package com.no5.helm.chart

import kotlin.math.*

enum class ChartCameraLimit { NONE, PAN_EDGE, OVERVIEW, UNKNOWN_BOUNDS, OUTSIDE_ACTIVE_CHARTS }

/** Pure geographic decision. A resident image or cache edge never defines this region. */
data class ChartCameraBoundsDecision(val requested: ChartViewport, val effective: ChartViewport?,
    val limit: ChartCameraLimit, val overviewScale: Double?)

/** Catalog snapshots are immutable. Build their envelope once, not on every motion or GPS tick. */
class ChartCameraBoundsCache {
    private var snapshot: ChartNavigationBounds? = null
    private var value: ChartCameraBounds? = null
    @Synchronized fun get(bounds: ChartNavigationBounds?): ChartCameraBounds? {
        if (bounds !== snapshot) { snapshot = bounds; value = ChartCameraBounds.from(bounds) }
        return value
    }
}

/** Outer Mercator envelope of the complete activated catalog, not an ENC coverage assertion. */
class ChartCameraBounds private constructor(private val west: Double, private val span: Double,
    private val south: Double, private val north: Double) {
    private val middleX = west + span / 2
    private val middleY = (south + north) / 2
    private fun longitudeX(lon: Double): Double = middleX +
        Math.toRadians(((lon - Math.toDegrees(middleX) + 540.0) % 360.0 + 360.0) % 360.0 - 180.0)
    fun contains(latitude: Double, longitude: Double): Boolean {
        if (!latitude.isFinite() || latitude !in -85.0..85.0 || !longitude.isFinite()) return false
        val x = longitudeX(longitude); val y = mercator(latitude)
        return x >= west - EPS && x <= west + span + EPS && y >= south - EPS && y <= north + EPS
    }
    fun overviewScale(width: Int, height: Int, rotation: Double): Double {
        val c = abs(cos(rotation)); val s = abs(sin(rotation))
        val w = max(1.0, span * RADIUS); val h = max(1.0, (north - south) * RADIUS)
        return min(width / (w * c + h * s), height / (w * s + h * c)).coerceIn(.00001, 10.0)
    }
    fun overview(view: ChartViewport): ChartViewport = view.copy(latitude = latitude(middleY),
        longitude = normalize(Math.toDegrees(middleX)),
        metresToPixels = overviewScale(view.width, view.height, view.rotationRadians))

    fun constrain(wanted: ChartViewport, basis: ChartViewport = wanted): ChartCameraBoundsDecision {
        if (wanted.width <= 0 || wanted.height <= 0 || !wanted.metresToPixels.isFinite() ||
            wanted.metresToPixels <= 0 || !wanted.rotationRadians.isFinite() ||
            !wanted.latitude.isFinite() || wanted.latitude !in -85.0..85.0 || !wanted.longitude.isFinite())
            return ChartCameraBoundsDecision(wanted, null, ChartCameraLimit.UNKNOWN_BOUNDS, null)
        val minimum = overviewScale(wanted.width, wanted.height, wanted.rotationRadians)
        val rotatedOverview = wanted.rotationRadians != basis.rotationRadians &&
            abs(wanted.metresToPixels - basis.metresToPixels) <= basis.metresToPixels * 1e-10 &&
            basis.metresToPixels <= overviewScale(basis.width, basis.height, basis.rotationRadians) * (1 + 1e-9)
        val scale = if (rotatedOverview) minimum else wanted.metresToPixels.coerceIn(minimum, 10.0)
        val c = abs(cos(wanted.rotationRadians)); val s = abs(sin(wanted.rotationRadians))
        val halfX = (wanted.width * c + wanted.height * s) / (2 * scale * RADIUS)
        val halfY = (wanted.width * s + wanted.height * c) / (2 * scale * RADIUS)
        // If the view exceeds an axis, center that axis. Otherwise move only as far
        // as necessary to keep all four view corners within the available envelope.
        val x = if (2 * halfX >= span - EPS) middleX else longitudeX(wanted.longitude).coerceIn(west + halfX, west + span - halfX)
        val y = if (2 * halfY >= north - south - EPS) middleY else mercator(wanted.latitude).coerceIn(south + halfY, north - halfY)
        val lat = latitude(y); val lon = normalize(Math.toDegrees(x))
        val effective = wanted.copy(latitude = if (abs(lat - wanted.latitude) < 1e-11) wanted.latitude else lat,
            longitude = if (abs(normalize(lon - wanted.longitude)) < 1e-11) wanted.longitude else lon,
            metresToPixels = scale)
        val limit = when {
            scale != wanted.metresToPixels -> ChartCameraLimit.OVERVIEW
            effective.latitude != wanted.latitude || effective.longitude != wanted.longitude -> ChartCameraLimit.PAN_EDGE
            else -> ChartCameraLimit.NONE
        }
        return ChartCameraBoundsDecision(wanted, effective, limit, minimum)
    }

    companion object {
        private const val RADIUS = 6378137.0 * .9996
        private const val EPS = 1e-12
        private fun mercator(lat: Double) = ln(tan(PI / 4 + Math.toRadians(lat) / 2))
        private fun latitude(y: Double) = Math.toDegrees(2 * atan(exp(y)) - PI / 2)
        private fun normalize(lon: Double) = ((lon + 180) % 360 + 360) % 360 - 180
        fun from(bounds: ChartNavigationBounds?): ChartCameraBounds? {
            val cells = bounds?.cells?.takeIf { it.isNotEmpty() } ?: return null
            if (cells.any { !it.south.isFinite() || !it.north.isFinite() || !it.west.isFinite() || !it.east.isFinite() ||
                    it.south !in -85.0..85.0 || it.north !in -85.0..85.0 || it.south >= it.north ||
                    it.west !in -180.0..180.0 || it.east !in -180.0..180.0 || it.west == it.east }) return null
            // Merge periodic longitude intervals and cut through the largest empty gap.
            // This is independent of cell order and handles neighboring dateline cells.
            val intervals = cells.flatMap { cell ->
                val lo = cell.west + 180; val hi = cell.east + 180
                if (cell.west <= cell.east) listOf(lo to hi) else listOf(lo to 360.0, 0.0 to hi)
            }.sortedBy { it.first }
            val merged = ArrayList<Pair<Double, Double>>()
            for (item in intervals) {
                val last = merged.lastOrNull()
                if (last == null || item.first > last.second + EPS) merged += item
                else merged[merged.lastIndex] = last.first to max(last.second, item.second)
            }
            var gap = -1.0; var start = merged.first().first
            for (i in merged.indices) {
                val next = if (i == merged.lastIndex) merged.first().first + 360 else merged[i + 1].first
                if (next - merged[i].second > gap) { gap = next - merged[i].second; start = next % 360 }
            }
            return ChartCameraBounds(Math.toRadians(start - 180), Math.toRadians(360 - gap),
                mercator(cells.minOf { it.south }), mercator(cells.maxOf { it.north }))
        }
    }
}
