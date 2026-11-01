package com.no5.helm.chart

/** Metadata from the same public S57/OSENC validation as import, never a draw selection. */
data class ChartCellBounds(val south: Double, val west: Double, val north: Double, val east: Double)
data class ChartCellCoordinate(val latitude: Double, val longitude: Double)
data class ChartCoverageCapabilities(
    val exteriorRingsOnly: Boolean = true,
    val polygonHoles: Boolean = false,
    val multiPolygons: Boolean = false,
    val wrappedDateline: Boolean = false,
    val latitudeLimit: Double = 85.0,
    val coordinatePrecision: String = "float32",
)
data class ValidatedChartCell(
    val path: String,
    val metadataVersion: Int,
    val parserVersion: String,
    val parserIdentity: String,
    val preparedSourceKey: String,
    val coverageVersion: Int,
    val nativeScale: Int?,
    val usage: Int?,
    val bounds: ChartCellBounds?,
    val coverage: List<List<ChartCellCoordinate>>,
    val exclusions: List<List<ChartCellCoordinate>>,
    val capabilities: ChartCoverageCapabilities,
)
data class ChartValidationResult(val result: EngineResult, val cells: List<ValidatedChartCell> = emptyList())
