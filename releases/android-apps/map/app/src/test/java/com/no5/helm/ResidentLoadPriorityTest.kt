package com.no5.helm

import com.no5.helm.chart.ChartCoverageCapabilities
import com.no5.helm.library.*
import org.junit.Assert.*
import org.junit.Test

class ResidentLoadPriorityTest {
    private data class Case(val chartScale: Double, val nativeScales: List<Int?>, val order: List<Int>)
    // Frozen cross-language cases from native-coverage/SCALE_ORDER_FIXTURES.json.
    // Expectations are explicit so the test does not duplicate the comparator.
    private val cases = listOf(
        Case(50000.0, listOf(5000, 50000, 500000, 12000, null, 0, 50000), listOf(1, 6, 2, 3, 0, 4, 5)),
        Case(20000.0, listOf(5000, 50000, 500000, 12000, null, 0, 50000), listOf(0, 3, 1, 6, 2, 4, 5)),
        Case(20000.000001, listOf(5000, 50000, 12000), listOf(2, 1, 0)),
        Case(1000000.0, listOf(5000, 50000, 500000, 12000), listOf(2, 1, 3, 0)),
    )

    @Test fun frozenNativeScaleFixturesMatchAdmissionOrderWithoutDroppingCandidates() {
        for (case in cases) {
            val catalog = catalog(case.nativeScales)
            val required = catalog.cells.map { it.key.sourceKey }
            val actual = ResidentLoadPriority.order(required, catalog, case.chartScale)
            assertEquals(case.order.map { required[it] }, actual)
            assertEquals(required.toSet(), actual.toSet())
            assertEquals(required.size, actual.size)
        }
    }

    @Test fun thresholdIsInclusiveAndSuitableScalesComeBeforeDescendingUnderzoomedScales() {
        val catalog = catalog(listOf(5000, 50000, 12000))
        val keys = catalog.cells.map { it.key.sourceKey }
        assertEquals(listOf(keys[0], keys[2], keys[1]), ResidentLoadPriority.order(keys, catalog, 20000.0))
        assertEquals(listOf(keys[2], keys[1], keys[0]), ResidentLoadPriority.order(keys, catalog, 20000.000001))
    }

    @Test fun catalogOrderBreaksEqualScaleAndUnknownTiesEvenWhenRequiredOrderIsReversed() {
        val catalog = catalog(listOf(50000, null, 50000, 0, 5000))
        val keys = catalog.cells.map { it.key.sourceKey }
        val required = keys.reversed()
        assertEquals(listOf(keys[0], keys[2], keys[4], keys[1], keys[3]),
            ResidentLoadPriority.order(required, catalog, 50000.0))
        assertEquals(required.toSet(), ResidentLoadPriority.order(required, catalog, 50000.0).toSet())
    }

    @Test fun aSubsetAndAnUnknownExternalKeyArePreservedAsRequired() {
        val catalog = catalog(listOf(5000, 50000, 500000, null))
        val keys = catalog.cells.map { it.key.sourceKey }
        val external = "e".repeat(64)
        val required = listOf(external, keys[3], keys[2], keys[0])
        val actual = ResidentLoadPriority.order(required, catalog, 50000.0)
        assertEquals(listOf(keys[2], keys[0], keys[3], external), actual)
        assertEquals(required.toSet(), actual.toSet()); assertEquals(required.size, actual.size)
    }

    @Test fun unknownOrInvalidActualScalePreservesTheOriginalRequiredOrder() {
        val catalog = catalog(listOf(50000, 5000, null))
        val required = catalog.cells.map { it.key.sourceKey }.reversed()
        for (scale in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(required, ResidentLoadPriority.order(required, catalog, scale))
        }
    }

    private fun catalog(scales: List<Int?>): ChartPlanningCatalog {
        val capability = ChartCoverageCapabilities()
        val cells = scales.mapIndexed { order, scale ->
            val name = "US5T%04d".format(order)
            val cell = ChartCell(name, 1, 0, "$name.000", "20261010", mapOf("$name.000" to "a".repeat(64)))
            cell.copy(catalogMetadata = ChartCatalogMetadata(name, cell.files, ChartMetadataContract.sourceKey(cell), order,
                1, "no5-s57-osenc201-metadata-v1", "b".repeat(64), "c".repeat(64), 1,
                scale, 5, null, null, emptyList(), emptyList(),
                CatalogCoverageCapabilities(capability.exteriorRingsOnly, capability.polygonHoles, capability.multiPolygons,
                    capability.wrappedDateline, capability.latitudeLimit, capability.coordinatePrecision)))
        }
        return ChartPlanningCatalog.fromPackage(ChartPackage("f".repeat(36), "priority", 0, cells), cells.map { it.name }.toSet())
    }
}
