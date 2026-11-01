package com.no5.helm.ui

import com.no5.helm.library.ChartCell
import com.no5.helm.library.ChartSelectionExtent
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/** Explicit 1° midpoint grid. It groups local envelopes, not NOAA IDs or official coverage zones. */
internal data class ChartRegionKey(val latitude: Int, val longitude: Int) {
    val id: String get() = "$latitude:$longitude"
    fun adjacent(other: ChartRegionKey): Boolean {
        val longitudeDistance = abs(longitude - other.longitude)
        return this != other && abs(latitude - other.latitude) <= 1 && min(longitudeDistance, 360 - longitudeDistance) <= 1
    }
}
internal data class ChartRegion(val key: ChartRegionKey?, val cells: List<ChartCell>) {
    val id: String get() = key?.id ?: "unlocated"
    val selectable: Set<String> get() = cells.filterNot { it.cancelled }.mapTo(linkedSetOf()) { it.name }
}
internal fun chartRegionKey(extent: ChartSelectionExtent?): ChartRegionKey? = extent?.takeIf { it.valid }?.let {
    ChartRegionKey(floor(it.centerLatitude).toInt().coerceIn(-90, 89), floor(it.centerLongitude).toInt().coerceIn(-180, 179))
}
internal fun chartRegions(cells: List<ChartCell>): List<ChartRegion> = cells.groupBy { chartRegionKey(it.selectionExtent) }
    .map { (key, entries) -> ChartRegion(key, entries.sortedBy { it.name }) }
    .sortedWith(compareBy<ChartRegion> { it.key == null }.thenByDescending { it.key?.latitude }.thenBy { it.key?.longitude })

/** Adds one geographic ring only. Success does not silently select a subset at the limit. */
internal fun chartNeighborNames(cells: List<ChartCell>, seeds: List<ChartCell>): Set<String> {
    val keys = seeds.mapNotNull { chartRegionKey(it.selectionExtent) }.toSet()
    if (keys.isEmpty()) return emptySet()
    return chartRegions(cells).filter { region -> region.key?.let { key -> keys.any { it.adjacent(key) } } == true }
        .flatMapTo(linkedSetOf()) { it.selectable }
}
internal data class ChartBulkSelection(val selection: Set<String>, val exceededLimit: Boolean = false)
internal fun chartAddSelection(current: Set<String>, requested: Set<String>, selectable: Set<String>, limit: Int = 100): ChartBulkSelection {
    val clean = current.intersect(selectable)
    val candidate = clean + requested.intersect(selectable)
    return if (candidate.size <= limit) ChartBulkSelection(candidate) else ChartBulkSelection(clean, exceededLimit = true)
}
internal fun chartRemoveSelection(current: Set<String>, requested: Set<String>): Set<String> = current - requested
