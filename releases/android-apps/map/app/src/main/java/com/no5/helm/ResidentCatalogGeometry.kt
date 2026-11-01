package com.no5.helm

import com.no5.helm.chart.*
import com.no5.helm.library.ChartPlanningCatalog
import com.no5.helm.library.navigationBounds
import kotlin.math.*

/** Navigation uses the released metadata catalog, never the temporary resident subset. */
internal object ResidentCatalogGeometry {
    fun bounds(catalog: ChartPlanningCatalog): ChartNavigationBounds {
        return catalog.navigationBounds()
    }

    /** Desired whole-catalog overview, not admission. The engine must select
     * and prove the start against its actual Surface/reserve and LIVE budget. */
    fun initial(catalog: ChartPlanningCatalog, previous: ChartViewport): ChartViewport {
        ChartCameraBounds.from(bounds(catalog))?.let { return it.overview(previous.copy(rotationRadians = 0.0)) }
        val extents = catalog.cells.mapNotNull { it.bounds }
        if (extents.isEmpty()) return previous
        val south = extents.minOf { it.south }; val north = extents.maxOf { it.north }
        // Work in a continuous longitude interval around one real metadata extent.
        val reference = extents.first().west
        fun unwrap(lon: Double): Double = reference + ((lon - reference + 540.0) % 360.0 - 180.0)
        val west = extents.minOf { unwrap(it.west) }
        val east = extents.maxOf { unwrap(it.west) + if (it.west > it.east) it.east + 360.0 - it.west else it.east - it.west }
        val latitude = ((south + north) / 2).coerceIn(-85.0, 85.0)
        val longitude = com.no5.helm.library.ChartSelectionExtent.normalizeLongitude((west + east) / 2)
        val radius = 6378137.0 * 0.9996
        fun mercator(lat: Double) = radius * ln(tan(Math.PI / 4 + lat.coerceIn(-85.0, 85.0) * Math.PI / 360))
        val widthMetres = max(1.0, (east - west) * Math.PI / 180.0 * radius)
        val heightMetres = max(1.0, mercator(north) - mercator(south))
        val ppm = min(previous.width * .9 / widthMetres, previous.height * .9 / heightMetres).coerceIn(.00001, 10.0)
        return previous.copy(latitude = latitude, longitude = longitude, metresToPixels = ppm, rotationRadians = 0.0)
    }
}
