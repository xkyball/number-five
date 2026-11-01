package com.no5.helm.library

import com.no5.helm.chart.ChartCellExtent
import com.no5.helm.chart.ChartNavigationBounds

/** The complete activated catalog, independent of the temporary resident selection. */
fun ChartPlanningCatalog.navigationBounds(): ChartNavigationBounds {
    val extents = cells.mapNotNull { cell -> cell.bounds?.let {
        ChartCellExtent(cell.key.name, it.south, it.north, it.west, it.east)
    } }
    return ChartNavigationBounds(0, if (extents.size == cells.size) extents else emptyList())
}
