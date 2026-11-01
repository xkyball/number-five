package com.no5.helm.ui

import com.no5.helm.chart.ChartCellExtent
import com.no5.helm.chart.ChartNavigationBounds
import com.no5.helm.chart.ChartSurfaceGeometry
import com.no5.helm.chart.ChartViewport
import com.no5.helm.chart.ScreenPoint
import kotlin.math.*

internal data class ChartBoundedPan(val offset: ScreenPoint, val stoppedAtBoundary: Boolean)

/**
 * Clips a pan segment at its first exit from the union of active ENC cell extents.
 * Longitude intervals are periodic; latitude crossings divide a segment into bands whose
 * longitude union is constant. This catches skipped gaps without sampling or allocating
 * rectangles for every crossed world wrap. A buffer edge does not enter this calculation.
 */
internal class ChartFlingBoundary(bounds: ChartNavigationBounds, private val basis: ChartViewport,
    private val geometry: ChartSurfaceGeometry) {
    private data class Interval(val low: Double, val high: Double)
    private data class Cell(val south: Double, val north: Double, val longitude: List<Interval>)
    private data class Position(val longitude: Double, val northing: Double)
    private val radius = 6378137.0 * .9996
    private val cells = bounds.cells.mapNotNull(::cell)
    private val originNorthing = northing(basis.latitude)
    private val cosine = cos(basis.rotationRadians)
    private val sine = sin(basis.rotationRadians)
    private val pixelScale = geometry.scale * basis.metresToPixels

    private fun northing(latitude: Double) = radius * ln(tan(PI / 4 + Math.toRadians(latitude) / 2))

    private fun cell(extent: ChartCellExtent): Cell? {
        if (!listOf(extent.south, extent.north, extent.west, extent.east).all(Double::isFinite) ||
            extent.south !in -90.0..90.0 || extent.north !in -90.0..90.0 || extent.south >= extent.north ||
            extent.west !in -180.0..180.0 || extent.east !in -180.0..180.0 || extent.west == extent.east) return null
        val south = extent.south.coerceAtLeast(-85.0)
        val north = extent.north.coerceAtMost(85.0)
        if (south >= north) return null
        val longitudeSpan = if (extent.west <= extent.east) extent.east - extent.west else 360.0 - extent.west + extent.east
        if (longitudeSpan <= 0.0) return null
        val west = extent.west + 180.0; val east = extent.east + 180.0
        val longitude = if (extent.west <= extent.east) listOf(Interval(west, east))
            else listOf(Interval(west, 360.0), Interval(0.0, east))
        return Cell(northing(south), northing(north), longitude)
    }

    private fun position(offset: ScreenPoint): Position {
        val east = -(offset.x * cosine + offset.y * sine) / pixelScale
        val north = (-offset.x * sine + offset.y * cosine) / pixelScale
        return Position(basis.longitude + 180.0 + Math.toDegrees(east / radius), originNorthing + north)
    }

    private fun normalizeLongitude(value: Double) = ((value % 360.0) + 360.0) % 360.0
    fun contains(offset: ScreenPoint = ScreenPoint(0.0, 0.0)): Boolean {
        val point = position(offset)
        if (!point.longitude.isFinite() || !point.northing.isFinite()) return false
        val longitude = normalizeLongitude(point.longitude)
        return cells.any { cell -> point.northing >= cell.south - METRE_EPSILON &&
            point.northing <= cell.north + METRE_EPSILON &&
            cell.longitude.any { interval -> listOf(longitude, longitude + 360.0).any {
                it >= interval.low - DEGREE_EPSILON && it <= interval.high + DEGREE_EPSILON } } }
    }

    fun constrain(from: ScreenPoint, wanted: ScreenPoint): ChartBoundedPan {
        if (!contains(from) || !listOf(from.x, from.y, wanted.x, wanted.y).all(Double::isFinite))
            return ChartBoundedPan(from, true)
        val start = position(from); val end = position(wanted)
        val longitudeDelta = end.longitude - start.longitude
        val northDelta = end.northing - start.northing
        if (!longitudeDelta.isFinite() || !northDelta.isFinite()) return ChartBoundedPan(from, true)
        val breaks = mutableListOf(0.0, 1.0)
        if (northDelta != 0.0) {
            for (cell in cells) for (edge in listOf(cell.south, cell.north)) {
                val t = (edge - start.northing) / northDelta
                if (t > 0.0 && t < 1.0) breaks += t
            }
        }
        val ordered = breaks.distinct().sorted()
        for (index in 0 until ordered.lastIndex) {
            val begin = ordered[index]; val finish = ordered[index + 1]
            val middleNorth = start.northing + northDelta * ((begin + finish) / 2.0)
            val active = cells.filter { middleNorth >= it.south && middleNorth <= it.north }
            val exit = firstLongitudeExit(active.flatMap { it.longitude },
                start.longitude + longitudeDelta * begin, longitudeDelta, begin, finish)
            if (exit != null) {
                val fraction = exit.coerceIn(0.0, 1.0)
                return ChartBoundedPan(ScreenPoint(from.x + (wanted.x - from.x) * fraction,
                    from.y + (wanted.y - from.y) * fraction), true)
            }
        }
        return ChartBoundedPan(wanted, false)
    }

    private fun firstLongitudeExit(intervals: List<Interval>, longitude: Double, delta: Double,
        begin: Double, finish: Double): Double? {
        if (intervals.isEmpty()) return begin
        val union = merge(intervals)
        if (union.size == 1 && union[0].low <= DEGREE_EPSILON && union[0].high >= 360.0 - DEGREE_EPSILON) return null
        // Three copies join dateline components; the component containing the normalized start
        // is at most one world wide unless the entire longitude circle is covered above.
        val periodic = merge(union.flatMap { interval -> (-1..1).map { turn ->
            Interval(interval.low + turn * 360.0, interval.high + turn * 360.0) } })
        val normalized = normalizeLongitude(longitude)
        val component = periodic.firstOrNull { normalized >= it.low - DEGREE_EPSILON && normalized <= it.high + DEGREE_EPSILON }
            ?: return begin
        if (delta == 0.0) return null
        val remaining = if (delta > 0.0) component.high - normalized else normalized - component.low
        val exit = begin + remaining.coerceAtLeast(0.0) / abs(delta)
        return exit.takeIf { it < finish - TIME_EPSILON }
    }

    private fun merge(intervals: List<Interval>): List<Interval> {
        val result = ArrayList<Interval>()
        for (next in intervals.sortedBy { it.low }) {
            val last = result.lastOrNull()
            if (last == null || next.low > last.high + DEGREE_EPSILON) result += next
            else result[result.lastIndex] = Interval(last.low, maxOf(last.high, next.high))
        }
        return result
    }

    private companion object {
        const val METRE_EPSILON = 0.000001
        const val DEGREE_EPSILON = 0.000000001
        const val TIME_EPSILON = 0.000000000001
    }
}
