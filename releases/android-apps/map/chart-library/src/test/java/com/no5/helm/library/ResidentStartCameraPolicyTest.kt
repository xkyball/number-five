package com.no5.helm.library

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test

class ResidentStartCameraPolicyTest {
    private fun cell(name: String, order: Int, south: Double = 42.0, west: Double = -72.0,
        north: Double = 43.0, east: Double = -70.0, metadata: Boolean = true): ChartCell {
        val bounds = ChartSelectionExtent(south, north, west, east)
        val raw = ChartCell(name, 1, 0, "$name.000", "20261011", mapOf("$name.000" to "a".repeat(64)),
            selectionExtent = bounds)
        if (!metadata) return raw
        val ring = listOf(ChartCellCoordinate(south, west), ChartCellCoordinate(south, east),
            ChartCellCoordinate(north, east), ChartCellCoordinate(north, west))
        val validated = ValidatedChartCell(raw.base, 1, "no5-s57-osenc201-metadata-v1",
            "b".repeat(64), "c".repeat(64), 1, 12000, 5,
            ChartCellBounds(south, west, north, east), listOf(ring), emptyList(), ChartCoverageCapabilities())
        return raw.copy(catalogMetadata = ChartMetadataContract.attach(raw, order, validated))
    }
    private fun catalog(cells: List<ChartCell>) = ChartPlanningCatalog.fromPackage(
        ChartPackage("f".repeat(36), "start-camera", 0, cells), cells.map { it.name }.toSet())
    private fun pose(ppm: Double = .006, longitude: Double = -71.04, rotation: Double = 0.0) =
        ChartViewport(42.36, longitude, ppm, rotation, 200, 100)
    private fun start(c: ChartPlanningCatalog, desired: ChartViewport = pose(), previous: ChartViewport? = null,
        width: Int = desired.width, height: Int = desired.height) = ChartResidentCameraStart(
        desired, previous, c.generation, 7, 3, width, height)

    @Test fun firstDesiredCameraMustPassEvenWhenItEqualsTheBasis() {
        val c = catalog(listOf(cell("ONE", 0), cell("TWO", 1), cell("THREE", 2)))
        val guard = ResidentStartCameraPolicy(c).bind(7)
        val request = start(c)
        val exact = guard.selectStart(request, ChartResidentBudget(cells = 3))
        assertEquals(request.desired, exact.acceptedCamera)
        assertEquals(3, exact.requiredUpperBound)
        assertFalse(exact.recoveryAboveBudget)
        val excess = guard.selectStart(request, ChartResidentBudget(cells = 2))
        assertNull(excess.acceptedCamera)
        assertEquals(ChartResidentCameraReason.BLOCKED, excess.reason)
    }

    @Test fun whole687CatalogStartSelectsOneProvedSmallerViewUnderLiveBudget() {
        val c = catalog((0 until 687).map { i ->
            val south = 24.0 + (i / 19) * .15
            val west = -84.0 + (i % 19) * .15
            cell("CELL$i", i, south, west, south + .12, west + .12)
        })
        val desired = ChartViewport(26.7, -82.6, .00001, .3, 200, 100)
        val guard = ResidentStartCameraPolicy(c).bind(7)
        val request = start(c, desired)
        val original = guard.evaluate(request.proposal(desired), ChartResidentBudget(cells = 20))
        assertEquals(687, original.requiredUpperBound)
        val result = guard.selectStart(request, ChartResidentBudget(cells = 20))
        val view = requireNotNull(result.acceptedCamera)
        assertTrue(result.requiredUpperBound!! in 1..20)
        assertTrue(view.metresToPixels > desired.metresToPixels)
        assertEquals(desired.latitude, view.latitude, 0.0)
        assertEquals(desired.longitude, view.longitude, 0.0)
        assertEquals(desired.rotationRadians, view.rotationRadians, 0.0)
        assertEquals(desired.width, view.width); assertEquals(desired.height, view.height)
        assertEquals(result, guard.evaluate(request.proposal(view), ChartResidentBudget(cells = 20)))
    }

    @Test fun fittingPreviousCameraSurvivesCatalogReplacementAndGetsCurrentVisibleSize() {
        val c = catalog(listOf(cell("LEFT", 0, east = -71.0), cell("RIGHT", 1, west = -71.0)))
        val previous = pose(.2, -71.02, .3).copy(width = 300, height = 150)
        val desired = pose(.002, -71.0)
        val result = ResidentStartCameraPolicy(c).bind(7).selectStart(start(c, desired, previous), ChartResidentBudget(cells = 1))
        assertEquals(previous.copy(width = desired.width, height = desired.height), result.acceptedCamera)
        assertEquals(1, result.requiredUpperBound)
    }

    @Test fun rollbackPrefersTheSavedCameraOverAnotherFittingCurrentCamera() {
        val c = catalog(listOf(cell("ONE", 0)))
        val saved = pose(.04, -71.04, .1)
        val switched = pose(.006, -71.03, .3)
        val request = start(c, saved, switched)
        assertEquals(switched, ResidentStartCameraPolicy(c).bind(7)
            .selectStart(request, ChartResidentBudget(cells = 1)).acceptedCamera)
        val rollback = ResidentStartCameraPolicy(c, preferPrevious = false).bind(7)
            .selectStart(request, ChartResidentBudget(cells = 1))
        assertEquals(saved, rollback.acceptedCamera)
        assertEquals(1, rollback.requiredUpperBound)
    }

    @Test fun previousOutsideNewCatalogCannotSucceedByCountingZeroCells() {
        val c = catalog(listOf(cell("FLORIDA", 0, 24.0, -82.0, 25.0, -81.0)))
        val outside = pose(.2)
        val result = ResidentStartCameraPolicy(c).bind(7).selectStart(start(c, outside, outside), ChartResidentBudget(cells = 1))
        val view = requireNotNull(result.acceptedCamera)
        assertTrue(view.latitude > 24.9 && view.latitude < 25.0)
        assertTrue(view.longitude > -81.1 && view.longitude < -81.0)
        val region=ChartCameraBounds.from(c.navigationBounds())!!
        val corners=ChartPlanCamera(view.latitude,view.longitude,view.metresToPixels,view.rotationRadians,view.width,view.height).corners()
        assertTrue(corners.all { region.contains(it.latitude,it.longitude) })
        assertEquals(1, result.requiredUpperBound)
    }

    @Test fun separatedAreasHaveAStableNearestFocusInsteadOfAnEmptyOverviewCenter() {
        val c = catalog(listOf(cell("WEST", 0, 42.0, -77.0, 43.0, -76.0),
            cell("EAST", 1, 42.0, -65.0, 43.0, -64.0)))
        val desired = pose(.2, -70.5)
        val previous = pose(.2, -66.0)
        val guard = ResidentStartCameraPolicy(c).bind(7)
        val result = guard.selectStart(start(c, desired, previous), ChartResidentBudget(cells = 1))
        assertEquals(-64.5, result.acceptedCamera!!.longitude, 0.0)
        assertEquals(result, guard.selectStart(start(c, desired, previous), ChartResidentBudget(cells = 1)))
    }

    @Test fun actualReserveAndResizeReproveTheSameGeographicCamera() {
        val c = catalog(listOf(cell("LEFT", 0, east = -71.0), cell("RIGHT", 1, west = -71.0)))
        val guard = ResidentStartCameraPolicy(c).bind(7)
        val narrow = pose(.02, -71.1).copy(width = 100)
        val accepted = guard.selectStart(start(c, narrow), ChartResidentBudget(cells = 1))
        assertEquals(narrow, accepted.acceptedCamera)
        val reserved = start(c, narrow, narrow, 1000, 200)
        assertEquals(2, guard.evaluate(reserved.proposal(narrow), ChartResidentBudget(cells = 1)).requiredUpperBound)
        val adapted = guard.selectStart(reserved, ChartResidentBudget(cells = 1))
        assertTrue(adapted.acceptedCamera!!.metresToPixels > narrow.metresToPixels)
        assertEquals(1, adapted.requiredUpperBound)
        assertEquals(narrow.width, adapted.acceptedCamera!!.width)
        val resized = narrow.copy(width = 1000, height = 200)
        val larger = guard.selectStart(start(c, resized, narrow), ChartResidentBudget(cells = 1))
        assertTrue(larger.acceptedCamera!!.metresToPixels > narrow.metresToPixels)
        assertEquals(resized.width, larger.acceptedCamera!!.width)
    }

    @Test fun unlocatedOrRequiredUnknownMetadataIsNeverAStartProof() {
        val c = catalog(listOf(cell("UNKNOWN", 0, metadata = false)))
        val result = ResidentStartCameraPolicy(c).bind(7).selectStart(start(c), ChartResidentBudget(cells = 100))
        assertEquals(ChartResidentCameraReason.UNKNOWN, result.reason)
        assertNull(result.acceptedCamera)
        val unlocated = catalog(listOf(cell("UNLOCATED", 0, metadata = false).copy(selectionExtent = null)))
        assertNull(ResidentStartCameraPolicy(unlocated).bind(7).selectStart(start(unlocated), ChartResidentBudget()).acceptedCamera)
    }

    @Test fun invalidGeometryAndStaleAuthorityCannotProduceFallbackCameras() {
        val c = catalog(listOf(cell("ONE", 0)))
        val guard = ResidentStartCameraPolicy(c).bind(7)
        val valid = start(c)
        for (invalid in listOf(valid.copy(planningWidth = 0), valid.copy(planningHeight = 1500),
            valid.copy(desired = valid.desired.copy(metresToPixels = Double.NaN)))) {
            val result = guard.selectStart(invalid, ChartResidentBudget())
            assertEquals(ChartResidentCameraReason.UNKNOWN, result.reason)
            assertNull(result.acceptedCamera)
        }
        for (stale in listOf(valid.copy(expectedCatalogGeneration = "other"),
            valid.copy(expectedSourceGeneration = 8), valid.copy(expectedSurfaceGeneration = -1))) {
            assertEquals(ChartResidentCameraReason.STALE_AUTHORITY, guard.selectStart(stale, ChartResidentBudget()).reason)
        }
    }

    @Test fun normalMixedGestureAndExplicitRecoveryKeepExistingCountPolicySemantics() {
        val c = catalog(listOf(cell("LEFT", 0, east = -71.0), cell("RIGHT", 1, west = -71.0)))
        val guard = ResidentStartCameraPolicy(c).bind(7)
        val basis = pose(.2, -71.02)
        val wanted = pose(.002, -70.99, .2)
        val alternative = pose(.2, -71.018, .2)
        val proposal = ChartResidentCameraProposal(basis, wanted, alternative,
            expectedCatalogGeneration = c.generation, expectedSourceGeneration = 7, expectedSurfaceGeneration = 3)
        assertEquals(alternative, guard.evaluate(proposal, ChartResidentBudget(cells = 1)).acceptedCamera)
        val overlap = catalog(listOf(cell("ONE", 0), cell("TWO", 1)))
        val recoveryGuard = ResidentStartCameraPolicy(overlap).bind(7)
        val previous = pose()
        val closer = previous.copy(metresToPixels = .012)
        val recovery = recoveryGuard.evaluate(start(overlap, previous).proposal(closer).copy(pureZoomInRecovery = true),
            ChartResidentBudget(cells = 1))
        assertTrue(recovery.recoveryAboveBudget)
        assertNull(recoveryGuard.selectStart(start(overlap, closer, previous), ChartResidentBudget(cells = 1)).acceptedCamera)
    }

    @Test fun countProofDoesNotClaimDifferentByteBudgetsAreEquivalentCapacity() {
        val c = catalog(listOf(cell("ONE", 0)))
        val guard = ResidentStartCameraPolicy(c).bind(7)
        val normal = guard.selectStart(start(c), ChartResidentBudget(cells = 1))
        val tiny = guard.selectStart(start(c), ChartResidentBudget(cells = 1, bytes = 2, loadingBytes = 1))
        assertEquals(normal, tiny) // Only a count claim; native byte guards remain authoritative.
        assertTrue(tiny.conservative)
        assertFalse(tiny.recoveryAboveBudget)
    }

    @Test fun datelineEdgeFocusStaysInTheActualKnownCatalog() {
        val raw = cell("WRAP", 0, 10.0, 179.0, 11.0, 180.0)
        // A native-supported rectangle next to the dateline, with an unrelated
        // initial position on its other side. Focus still needs a count proof.
        val c = catalog(listOf(raw))
        val desired = ChartViewport(10.5, -170.0, .2, 0.0, 200, 100)
        val result = ResidentStartCameraPolicy(c).bind(7).selectStart(start(c, desired), ChartResidentBudget(cells = 1))
        val camera=checkNotNull(result.acceptedCamera)
        assertTrue(camera.longitude > 179.9 && camera.longitude < 180.0)
        val region=ChartCameraBounds.from(c.navigationBounds())!!
        assertTrue(ChartPlanCamera(camera.latitude,camera.longitude,camera.metresToPixels,camera.rotationRadians,
            camera.width,camera.height).corners().all { region.contains(it.latitude,it.longitude) })
        assertEquals(1, result.requiredUpperBound)
    }

    @Test fun wrappedButUnknownCoverageCannotBeInventedByTheFocusFallback() {
        val raw = cell("UNKNOWN_WRAP", 0, metadata = false).copy(
            selectionExtent = ChartSelectionExtent(10.0, 11.0, 179.0, -179.0))
        val c = catalog(listOf(raw))
        val desired = ChartViewport(10.5, 179.8, .2, 0.0, 200, 100)
        val result = ResidentStartCameraPolicy(c).bind(7).selectStart(start(c, desired), ChartResidentBudget())
        assertNull(result.acceptedCamera)
        assertEquals(ChartResidentCameraReason.UNKNOWN, result.reason)
    }

    @Test fun explicitEmptyAuthorityIsFreeButNeverAcceptsAStaleEpoch() {
        val generation = "e".repeat(64)
        val guard = ResidentStartCameraPolicy.empty(generation).bind(7)
        val request = ChartResidentCameraStart(pose(), null, generation, 7, 3, 250, 125)
        val result = guard.selectStart(request, ChartResidentBudget(cells = 1))
        assertEquals(request.desired, result.acceptedCamera)
        assertEquals(0, result.requiredUpperBound)
        assertEquals(ChartResidentCameraReason.STALE_AUTHORITY,
            guard.selectStart(request.copy(expectedSourceGeneration = 8), ChartResidentBudget()).reason)
        assertNull(guard.selectStart(request.copy(planningHeight = 1500), ChartResidentBudget()).acceptedCamera)
    }
}
