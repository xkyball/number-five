package com.no5.helm.ui

import com.no5.helm.library.ChartCell
import com.no5.helm.library.ChartSelectionExtent
import org.junit.Assert.*
import org.junit.Test

class ChartRegionSelectionTest {
    private fun cell(name: String, lat: Double?, lon: Double? = null, cancelled: Boolean = false): ChartCell =
        ChartCell(name, 1, 0, "$name.000", "20261010", emptyMap(), cancelled,
            lat?.let { ChartSelectionExtent(it, it + .1, lon!!, lon + .1) })
    @Test fun groupsByActualCoordinatesAndNeverInfersGeographyFromNames() {
        val regions = chartRegions(listOf(cell("US5BOSCC", 25.2, -80.2), cell("US5MIACC", 42.2, -71.2), cell("US5BOSCD", null)))
        assertEquals(listOf("42:-72", "25:-81", "unlocated"), regions.map { it.id })
        assertEquals(listOf("US5MIACC"), regions[0].cells.map { it.name })
        assertNull(chartRegionKey(ChartSelectionExtent(Double.NaN, 42.0, -72.0, -71.0)))
    }
    @Test fun disconnectedIslandsAreSeparateZonesInsteadOfOneCoveringRectangle() {
        val regions = chartRegions(listOf(cell("one", 21.3, -157.9), cell("two", 18.3, -66.1)))
        assertEquals(2, regions.size); assertNotEquals(regions[0].key, regions[1].key)
        assertEquals(listOf(1, 1), regions.map { it.cells.size })
    }
    @Test fun oneRingNeighborsIncludesDiagonalButNotSelfSecondRingOrUnlocated() {
        val seed = cell("seed", 42.2, -71.8)
        val inventory = listOf(seed, cell("same", 42.3, -71.7), cell("east", 42.2, -70.7),
            cell("diagonal", 43.2, -70.7), cell("twoAway", 44.2, -71.7), cell("unknown", null), cell("cancelled", 42.3, -70.6, true))
        assertEquals(setOf("east", "diagonal"), chartNeighborNames(inventory, listOf(seed)))
        assertTrue(chartNeighborNames(inventory, emptyList()).isEmpty())
        assertTrue(chartNeighborNames(inventory, listOf(cell("unknown", null))).isEmpty())
    }
    @Test fun neighborsCanStartFromActiveCellsInAnotherPackage() {
        val active = cell("old", 42.2, -71.8)
        assertEquals(setOf("new"), chartNeighborNames(listOf(cell("new", 42.2, -70.6)), listOf(active)))
    }
    @Test fun datelineNeighborsWrapAndExtentMidpointUsesShortArc() {
        val seed = cell("seed", 0.2, 179.2)
        assertEquals(setOf("across"), chartNeighborNames(listOf(cell("across", .2, -179.8), cell("far", .2, -177.8)), listOf(seed)))
        assertEquals(ChartRegionKey(0, -180), chartRegionKey(ChartSelectionExtent(0.0, 1.0, 179.0, -179.0)))
    }
    @Test fun bulkSelectionIsAtomicAtLimitAndFiltersInvalidNames() {
        val available = (0 until 110).mapTo(linkedSetOf()) { "c$it" }
        val selected = (0 until 99).mapTo(linkedSetOf()) { "c$it" }
        val rejected = chartAddSelection(selected, setOf("c99", "c100"), available)
        assertTrue(rejected.exceededLimit); assertEquals(selected, rejected.selection)
        val accepted = chartAddSelection(selected + "notStored", setOf("c99", "notStored"), available)
        assertFalse(accepted.exceededLimit); assertEquals(100, accepted.selection.size); assertFalse("notStored" in accepted.selection)
    }
    @Test fun filteredBulkOperationsPreserveSelectionOutsideTheirScope() {
        val available = setOf("a", "b", "c")
        assertEquals(setOf("a", "b", "c"), chartAddSelection(setOf("c"), setOf("a", "b"), available).selection)
        assertEquals(setOf("c"), chartRemoveSelection(available, setOf("a", "b")))
        assertEquals(emptySet<String>(), chartRemoveSelection(available, available))
    }
    @Test fun residentReleaseLimitAcceptsWhole400CellInventoryWithoutTruncatingLegacyLimit() {
        val available = (0 until 400).mapTo(linkedSetOf()) { "c$it" }
        val resident = chartAddSelection(emptySet(), available, available, Int.MAX_VALUE)
        assertFalse(resident.exceededLimit)
        assertEquals(available, resident.selection)
        val legacy = chartAddSelection(emptySet(), available, available, 100)
        assertTrue(legacy.exceededLimit)
        assertTrue(legacy.selection.isEmpty())
    }
    @Test fun cancelledCellsRemainVisibleButAreExcludedFromZoneBulkSelection() {
        val region = chartRegions(listOf(cell("okay", 42.2, -71.8), cell("cancelled", 42.3, -71.7, true))).single()
        assertEquals(2, region.cells.size); assertEquals(setOf("okay"), region.selectable)
    }
}
