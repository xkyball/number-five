package com.no5.helm

import com.no5.helm.chart.*
import com.no5.helm.library.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class ResidentCatalogGeometryTest {
    private val previous = ChartViewport(0.0, 0.0, .02, .63, 2000, 1070)
    private fun cell(name: String, extent: ChartSelectionExtent?) = ChartCell(name, 1, 0,
        "$name.000", "20261010", mapOf("$name.000" to "a".repeat(64)), selectionExtent = extent)
    private fun catalog(cells: List<ChartCell>, names: Set<String> = cells.map { it.name }.toSet()) =
        ChartPlanningCatalog.fromPackage(ChartPackage("f".repeat(36), "geometry", 0, cells), names)

    @Test fun genuineBostonMetadataCentersNearBostonAndFitsTheCompleteReleasedExtent() {
        val catalog = genuineBostonCatalog()
        assertEquals(4, catalog.cells.size); assertTrue(catalog.cells.all { it.metadata != null })
        val view = ResidentCatalogGeometry.initial(catalog, previous)
        val extents = catalog.cells.map { it.bounds!! }
        val south = extents.minOf { it.south }; val north = extents.maxOf { it.north }
        val west = extents.minOf { it.west }; val east = extents.maxOf { it.east }
        assertEquals(mercatorMiddle(south, north), view.latitude, 1e-10)
        assertEquals((west + east) / 2, view.longitude, 1e-10)
        assertTrue(view.longitude in -71.2..-70.8)
        assertEquals(previous.width, view.width); assertEquals(previous.height, view.height)
        assertEquals(0.0, view.rotationRadians, 0.0)
        val corners = ChartPlanCamera(view.latitude, view.longitude, view.metresToPixels,
            view.rotationRadians, view.width, view.height).corners()
        assertTrue(corners.minOf { it.latitude } <= south + 1e-9)
        assertTrue(corners.maxOf { it.latitude } >= north - 1e-9)
        assertTrue(corners.minOf { it.longitude } <= west + 1e-9)
        assertTrue(corners.maxOf { it.longitude } >= east - 1e-9)
        assertEquals(catalog.cells.map { it.key.name }, ResidentCatalogGeometry.bounds(catalog).cells.map { it.cellId })
    }

    @Test fun wrappedDatelineExtentKeepsShortLongitudeSpanAndNavigationWrap() {
        val extent = ChartSelectionExtent(10.0, 11.0, 179.0, -179.0)
        val catalog = catalog(listOf(cell("US5WRAP0", extent)))
        val bounds = ResidentCatalogGeometry.bounds(catalog)
        assertEquals(179.0, bounds.cells.single().west, 0.0)
        assertEquals(-179.0, bounds.cells.single().east, 0.0)
        val view = ResidentCatalogGeometry.initial(catalog, previous)
        assertEquals(mercatorMiddle(10.0, 11.0), view.latitude, 1e-10)
        assertEquals(180.0, abs(view.longitude), 1e-10)
        assertTrue(view.metresToPixels > .001) // A two-degree span, not a 358-degree/world fit.
        assertTrue(view.metresToPixels.isFinite())
    }

    @Test fun neighboringDatelineCellsCenterAtTheDateLineInEitherCatalogOrder() {
        val west = cell("US5WEST0", ChartSelectionExtent(10.0, 11.0, 175.0, 179.0))
        val east = cell("US5EAST0", ChartSelectionExtent(10.0, 11.0, -179.0, -175.0))
        for (cells in listOf(listOf(west, east), listOf(east, west))) {
            val view = ResidentCatalogGeometry.initial(catalog(cells), previous)
            assertEquals(180.0, abs(view.longitude), 1e-10)
            assertTrue(view.metresToPixels > .001)
        }
    }

    @Test fun unknownBoundsDisableClaimedWholeCatalogEdgeAndKeepUnknownOnlyCamera() {
        val known = cell("US5KNOWN", ChartSelectionExtent(42.3, 42.4, -71.1, -71.0))
        val unknown = cell("US5UNK00", null)
        assertTrue(ResidentCatalogGeometry.bounds(catalog(listOf(known, unknown))).cells.isEmpty())
        val unknownOnly = catalog(listOf(unknown))
        assertEquals(previous, ResidentCatalogGeometry.initial(unknownOnly, previous))
        assertTrue(ResidentCatalogGeometry.bounds(unknownOnly).cells.isEmpty())
    }

    @Test fun navigationUsesAllReleasedMetadataAndNeverUnreleasedPackageCells() {
        val cells = listOf(cell("US5A0000", ChartSelectionExtent(42.3, 42.4, -71.1, -71.0)),
            cell("US5B0000", ChartSelectionExtent(42.4, 42.5, -71.0, -70.9)),
            cell("US5C0000", ChartSelectionExtent(60.0, 61.0, 10.0, 11.0)))
        val released = catalog(cells, setOf("US5A0000", "US5B0000"))
        assertEquals(listOf("US5A0000", "US5B0000"), ResidentCatalogGeometry.bounds(released).cells.map { it.cellId })
        val view = ResidentCatalogGeometry.initial(released, previous)
        assertEquals(mercatorMiddle(42.3, 42.5), view.latitude, 1e-10); assertEquals(-71.0, view.longitude, 1e-10)
    }

    @Test fun degenerateValidExtentProducesFiniteBoundedCameraWithoutChangingCanvasSize() {
        val catalog = catalog(listOf(cell("US5POINT", ChartSelectionExtent(42.36, 42.36, -71.04, -71.04))))
        val view = ResidentCatalogGeometry.initial(catalog, previous)
        assertEquals(42.36, view.latitude, 1e-10); assertEquals(-71.04, view.longitude, 1e-10)
        assertTrue(view.metresToPixels.isFinite() && view.metresToPixels in .00001..10.0)
        assertEquals(previous.width, view.width); assertEquals(previous.height, view.height)
    }

    private fun mercatorMiddle(south: Double, north: Double): Double {
        fun y(lat: Double) = kotlin.math.ln(kotlin.math.tan(Math.PI / 4 + Math.toRadians(lat) / 2))
        return Math.toDegrees(2 * kotlin.math.atan(kotlin.math.exp((y(south) + y(north)) / 2)) - Math.PI / 2)
    }

    private fun genuineBostonCatalog(): ChartPlanningCatalog {
        val fixture = File("../chart-library/src/test/resources/native-cell-metadata-v1.json").readText()
        val assets = File("../chart-engine/src/main/assets/charts/noaa")
        val cells = Regex("\"cell\"\\s*:\\s*\"([A-Z0-9]+)\"").findAll(fixture).mapIndexed { order, match ->
            val name = match.groupValues[1]
            val metadataStart = fixture.indexOf("{", fixture.indexOf("\"metadata\"", match.range.last))
            val value = ChartCellMetadataDecoder.decode("$name.000", jsonObjectAt(fixture, metadataStart))
            val files = assets.listFiles()!!.filter { it.nameWithoutExtension == name && it.extension.matches(Regex("[0-9]{3}")) }
                .sortedBy { it.extension.toInt() }.associate { it.name to ChartLibrary.sha256(it) }
            val identity = S57Identification.read(File(assets, "$name.000"))
            val cell = ChartCell(name, identity.edition, files.size - 1, "$name.000", identity.issueDate, files)
            val bounds = value.bounds?.let { ChartSelectionExtent(it.south, it.north, it.west, it.east) }
            val capabilities = value.capabilities
            val metadata = ChartCatalogMetadata(name, files, ChartMetadataContract.sourceKey(cell), order,
                value.metadataVersion, value.parserVersion, value.parserIdentity, value.preparedSourceKey,
                value.coverageVersion, value.nativeScale, value.usage, bounds, bounds,
                value.coverage.map { ring -> ring.map { CatalogCoordinate(it.latitude, it.longitude) } },
                value.exclusions.map { ring -> ring.map { CatalogCoordinate(it.latitude, it.longitude) } },
                CatalogCoverageCapabilities(capabilities.exteriorRingsOnly, capabilities.polygonHoles,
                    capabilities.multiPolygons, capabilities.wrappedDateline, capabilities.latitudeLimit,
                    capabilities.coordinatePrecision))
            cell.copy(catalogMetadata = metadata)
        }.toList()
        return catalog(cells)
    }

    /** Extract an existing fixture object without adding an app JSON dependency. */
    private fun jsonObjectAt(text: String, start: Int): String {
        require(start >= 0 && text[start] == '{')
        var depth = 0; var quoted = false; var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(start, index + 1)
            }
        }
        error("Unclosed native metadata fixture")
    }
}
