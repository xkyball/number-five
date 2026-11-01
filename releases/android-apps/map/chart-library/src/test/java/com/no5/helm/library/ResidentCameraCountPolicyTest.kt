package com.no5.helm.library

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test

class ResidentCameraCountPolicyTest {
    private fun cell(name: String, order: Int, south: Double = 42.0, west: Double = -72.0,
        north: Double = 43.0, east: Double = -70.0, scale: Int = 12000, metadata: Boolean = true): ChartCell {
        val extent = ChartSelectionExtent(south, north, west, east)
        val raw = ChartCell(name, 1, 0, "$name.000", "20261011", mapOf("$name.000" to "a".repeat(64)),
            selectionExtent = extent)
        if (!metadata) return raw
        val ring = listOf(ChartCellCoordinate(south, west), ChartCellCoordinate(south, east),
            ChartCellCoordinate(north, east), ChartCellCoordinate(north, west))
        val validated = ValidatedChartCell(raw.base, 1, "no5-s57-osenc201-metadata-v1",
            "b".repeat(64), "c".repeat(64), 1, scale, 5,
            ChartCellBounds(south, west, north, east), listOf(ring), emptyList(), ChartCoverageCapabilities())
        return raw.copy(catalogMetadata = ChartMetadataContract.attach(raw, order, validated))
    }
    private fun catalog(cells: List<ChartCell>) = ChartPlanningCatalog.fromPackage(
        ChartPackage("f".repeat(36), "camera-count", 0, cells), cells.map { it.name }.toSet())
    private fun pose(ppm: Double = .006, longitude: Double = -71.04, rotation: Double = 0.0) =
        ChartViewport(latitude = 42.36, longitude = longitude, metresToPixels = ppm,
            rotationRadians = rotation, width = 200, height = 100)
    private fun proposal(c: ChartPlanningCatalog, basis: ChartViewport = pose(), wanted: ChartViewport = basis,
        alternative: ChartViewport? = null, recovery: Boolean = false) = ChartResidentCameraProposal(
        basis, wanted, alternative, recovery, c.generation, 4, 3)
    private fun budget(cells: Int) = ChartResidentBudget(cells = cells)

    @Test fun exactLiveCellBudgetAndMaskConservativeCohortAreRespected() {
        val c = catalog(listOf(cell("ONE", 0), cell("TWO", 1), cell("THREE", 2)))
        val guard = ResidentCameraCountPolicy(c, 4)
        val target = proposal(c)
        val accepted = guard.evaluate(target, budget(3))
        assertEquals(ChartResidentCameraReason.ACCEPTED, accepted.reason)
        assertEquals(target.wanted, accepted.acceptedCamera)
        assertEquals(3, accepted.requiredUpperBound)
        assertTrue(accepted.conservative)
        val reduced = guard.evaluate(target, budget(2))
        assertEquals(ChartResidentCameraReason.BLOCKED, reduced.reason)
        assertNull(reduced.acceptedCamera)
        assertEquals(2, reduced.cellBudget)
        assertEquals(c.generation, reduced.catalogGeneration)
    }

    @Test fun largeActivatedCatalogIsNotTheCurrentViewportCount() {
        val cells = listOf(cell("LOCAL", 0)) + (1 until 687).map { i ->
            cell("FAR$i", i, south = 24.0, west = -82.0, north = 25.0, east = -81.0) }
        val c = catalog(cells)
        val decision = ResidentCameraCountPolicy(c, 4).evaluate(proposal(c), budget(1))
        assertEquals(687, c.cells.size)
        assertEquals(1, decision.requiredUpperBound)
        assertEquals(ChartResidentCameraReason.ACCEPTED, decision.reason)
    }

    @Test fun overlappingFineAndCoarseBoundsCanStopEarlierThanExactP6Contributors() {
        val c = catalog(listOf(cell("LEFT", 0, east = -71.0, scale = 12000),
            cell("RIGHT", 1, west = -71.0, scale = 12000), cell("COARSE", 2, scale = 200000)))
        val target = pose()
        val count = ResidentCameraCountPolicy(c, 4).evaluate(proposal(c, wanted = target), budget(2))
        assertEquals(3, count.requiredUpperBound)
        assertEquals(ChartResidentCameraReason.BLOCKED, count.reason)
        assertTrue(count.conservative)
        val p2 = ChartViewportPlanner(c).plan(ChartPlanCamera(target.latitude, target.longitude,
            target.metresToPixels, target.rotationRadians, target.width, target.height))
        val exact = ChartCoveragePlanner(c).admit(p2, 600000.0)
        assertTrue(exact.reason, exact.proven)
        assertEquals(2, exact.plan.required.size)
        // This inexpensive guard deliberately does not run P6 in the touch callback.
    }

    @Test fun mixedGestureKeepsTheExactAnchoredNoZoomAlternative() {
        val c = catalog(listOf(cell("LOCAL", 0, east = -71.0),
            cell("NEIGHBOR", 1, west = -71.0)))
        val basis = pose(ppm = .2, longitude = -71.02)
        // Both include a pan/rotation. No-zoom geography was computed at the
        // original anchor, not by copying ppm onto the already zoomed target.
        val wanted = pose(ppm = .002, longitude = -70.99, rotation = .2)
        val noZoom = pose(ppm = .2, longitude = -71.018, rotation = .2)
        val decision = ResidentCameraCountPolicy(c, 4).evaluate(proposal(c, basis, wanted, noZoom), budget(1))
        assertEquals(ChartResidentCameraReason.NO_ZOOM_ALTERNATIVE, decision.reason)
        assertEquals(noZoom, decision.acceptedCamera)
        assertNotEquals(wanted.copy(metresToPixels = basis.metresToPixels), decision.acceptedCamera)
        assertEquals(1, decision.requiredUpperBound)
    }

    @Test fun overBudgetAlternativeIsAlsoHeldAndPanRotationCannotBypassTheCount() {
        val c = catalog(listOf(cell("LEFT", 0, east = -71.0), cell("RIGHT", 1, west = -71.0)))
        val guard = ResidentCameraCountPolicy(c, 4)
        val basis = pose(ppm = .2, longitude = -71.02)
        val rotatedAcrossSeam = pose(ppm = .2, longitude = -71.0, rotation = .8)
        val attempted = proposal(c, basis, rotatedAcrossSeam.copy(metresToPixels = .002), rotatedAcrossSeam)
        assertNull(guard.evaluate(attempted, budget(1)).acceptedCamera)
        assertEquals(ChartResidentCameraReason.BLOCKED, guard.evaluate(proposal(c, basis, rotatedAcrossSeam), budget(1)).reason)
    }

    @Test fun initialOverBudgetViewAllowsOnlyExplicitInwardRecoveryAndMarksTheException() {
        val c = catalog((0 until 4).map { cell("CELL$it", it) })
        val guard = ResidentCameraCountPolicy(c, 4)
        val basis = pose()
        val closer = basis.copy(metresToPixels = basis.metresToPixels * 2)
        val recovery = guard.evaluate(proposal(c, basis, closer, recovery = true), budget(2))
        assertEquals(ChartResidentCameraReason.RECOVERY_ABOVE_BUDGET, recovery.reason)
        assertEquals(closer, recovery.acceptedCamera)
        assertTrue(recovery.recoveryAboveBudget)
        assertEquals(4, recovery.requiredUpperBound)
        assertNull(guard.evaluate(proposal(c, basis, closer), budget(2)).acceptedCamera)
        assertNull(guard.evaluate(proposal(c, basis, basis.copy(metresToPixels = .003), recovery = true), budget(2)).acceptedCamera)
    }

    @Test fun recoveryFlagCannotHideRotationResizeOrLeavingTheOldFootprint() {
        val c = catalog((0 until 4).map { cell("CELL$it", it) })
        val guard = ResidentCameraCountPolicy(c, 4)
        val basis = pose()
        for (wanted in listOf(basis.copy(metresToPixels = .012, rotationRadians = .2),
            basis.copy(metresToPixels = .012, width = 400),
            basis.copy(metresToPixels = .012, longitude = -70.5))) {
            val decision = guard.evaluate(proposal(c, basis, wanted, recovery = true), budget(2))
            assertNull(decision.acceptedCamera)
            assertFalse(decision.recoveryAboveBudget)
        }
    }

    @Test fun ordinaryWithinBudgetZoomInIsNotMarkedAsAnOverBudgetRecovery() {
        val c = catalog(listOf(cell("ONE", 0)))
        val basis = pose()
        val decision = ResidentCameraCountPolicy(c, 4).evaluate(proposal(c, basis,
            basis.copy(metresToPixels = .012), recovery = true), budget(1))
        assertEquals(ChartResidentCameraReason.ACCEPTED, decision.reason)
        assertFalse(decision.recoveryAboveBudget)
    }

    @Test fun absentOrStaleRequiredMetadataIsUnknownNotSuccessfulCapacity() {
        val c = catalog(listOf(cell("UNKNOWN", 0, metadata = false)))
        val decision = ResidentCameraCountPolicy(c, 4).evaluate(proposal(c), budget(1))
        assertEquals(ChartResidentCameraReason.UNKNOWN, decision.reason)
        assertEquals(1, decision.requiredUpperBound)
        assertNull(decision.acceptedCamera)
        val invalid = ResidentCameraCountPolicy(c, 4).evaluate(proposal(c, wanted = pose().copy(metresToPixels = Double.NaN)), budget(1))
        assertEquals(ChartResidentCameraReason.UNKNOWN, invalid.reason)
        assertNull(invalid.requiredUpperBound)
    }

    @Test fun sourceAndCatalogMismatchCannotReuseAnOldGuardOrItsCachedPose() {
        val c = catalog(listOf(cell("ONE", 0)))
        val guard = ResidentCameraCountPolicy(c, 4)
        val original = proposal(c)
        assertNotNull(guard.evaluate(original, budget(1)).acceptedCamera)
        for (stale in listOf(original.copy(expectedSourceGeneration = 5),
            original.copy(expectedCatalogGeneration = "different"), original.copy(expectedSurfaceGeneration = -1))) {
            val result = guard.evaluate(stale, budget(1))
            assertEquals(ChartResidentCameraReason.STALE_AUTHORITY, result.reason)
            assertNull(result.acceptedCamera)
        }
        // Surface authority is verified before and after the callback by the engine.
        assertEquals(9, guard.evaluate(original.copy(expectedSurfaceGeneration = 9), budget(1)).surfaceGeneration)
    }

    @Test fun callerPackageAndNestedMetadataMutationCannotChangeTheFrozenGuard() {
        val original = cell("ONE", 0)
        val metadata = requireNotNull(original.catalogMetadata)
        val files = original.files.toMutableMap()
        val ring = metadata.coverage.single().toMutableList()
        val coverage = mutableListOf<List<CatalogCoordinate>>(ring)
        val exclusions = mutableListOf<List<CatalogCoordinate>>()
        val cells = mutableListOf(original.copy(files = files, catalogMetadata = metadata.copy(
            files = files, coverage = coverage, exclusions = exclusions)))
        val c = catalog(cells)
        val generation = c.generation
        val guard = ResidentCameraCountPolicy(c, 4)
        assertEquals(ChartResidentCameraReason.ACCEPTED, guard.evaluate(proposal(c), budget(1)).reason)
        val frozenRing = c.cells.single().metadata!!.coverage.single().toList()
        ring.clear(); coverage.clear(); exclusions.add(frozenRing); files.clear(); cells.clear()
        assertEquals(generation, c.generation)
        assertEquals(frozenRing, c.cells.single().metadata!!.coverage.single())
        assertTrue(c.cells.single().metadata!!.exclusions.isEmpty())
        assertEquals(1, c.cells.single().metadata!!.files.size)
        // Change camera as well, so this checks the frozen index/metadata rather
        // than relying only on a cached previous-pose answer.
        val result = guard.evaluate(proposal(c, wanted = pose(longitude = -71.03)), budget(1))
        assertEquals(ChartResidentCameraReason.ACCEPTED, result.reason)
        assertEquals(1, result.requiredUpperBound)
    }

    @Test fun resizeRecomputesDemandInsteadOfReusingAPreviousCameraCount() {
        val c = catalog(listOf(cell("LEFT", 0, east = -71.0), cell("RIGHT", 1, west = -71.0)))
        val guard = ResidentCameraCountPolicy(c, 4)
        val narrow = pose(ppm = .02, longitude = -71.1).copy(width = 100)
        assertEquals(ChartResidentCameraReason.ACCEPTED, guard.evaluate(proposal(c, wanted = narrow), budget(1)).reason)
        val wider = narrow.copy(width = 1000)
        assertEquals(ChartResidentCameraReason.BLOCKED, guard.evaluate(proposal(c, narrow, wider), budget(1)).reason)
    }

    @Test fun actualProducerReserveCanRequireMoreCellsThanTheVisibleView() {
        val c = catalog(listOf(cell("LEFT", 0, east = -71.0), cell("RIGHT", 1, west = -71.0)))
        val guard = ResidentCameraCountPolicy(c, 4)
        val narrow = pose(ppm = .02, longitude = -71.1).copy(width = 100)
        val visible = proposal(c, wanted = narrow)
        assertEquals(ChartResidentCameraReason.ACCEPTED, guard.evaluate(visible, budget(1)).reason)
        val reserved = visible.copy(planningWidth = 1000, planningHeight = 200)
        val decision = guard.evaluate(reserved, budget(1))
        assertEquals(2, decision.requiredUpperBound)
        assertEquals(ChartResidentCameraReason.BLOCKED, decision.reason)
        assertNull(decision.acceptedCamera)
        assertEquals(ChartResidentCameraReason.UNKNOWN,
            guard.evaluate(reserved.copy(planningHeight = 1500), budget(2)).reason)
    }

    @Test fun arbitraryProposalsAreEvaluatedInOrderWithoutAGlobalZoomSearch() {
        val c = catalog(listOf(cell("LEFT", 0, east = -71.0), cell("RIGHT", 1, west = -71.0)))
        val guard = ResidentCameraCountPolicy(c, 4)
        val narrow = pose(ppm = .2, longitude = -71.02)
        val wide = narrow.copy(metresToPixels = .002)
        val results = listOf(narrow, wide, narrow).map { guard.evaluate(proposal(c, wanted = it), budget(1)).reason }
        assertEquals(listOf(ChartResidentCameraReason.ACCEPTED, ChartResidentCameraReason.BLOCKED,
            ChartResidentCameraReason.ACCEPTED), results)
    }
}
