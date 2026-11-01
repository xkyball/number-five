package com.no5.helm.chart

/**
 * Original geographic extent of one activated ENC cell, in degrees.
 * west > east crosses the dateline. These extents are navigation bounds, not M_COVR polygons
 * or a statement about safe navigation, available detail, or valid pixels in a cached image.
 */
data class ChartCellExtent(val cellId: String, val south: Double, val north: Double,
    val west: Double, val east: Double)

/** Individual activated cell extents form a union, even when their geometry is not resident. */
data class ChartNavigationBounds(val generation: Long, val cells: List<ChartCellExtent>)
