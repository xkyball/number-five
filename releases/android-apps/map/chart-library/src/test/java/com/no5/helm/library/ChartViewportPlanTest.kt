package com.no5.helm.library

import com.no5.helm.chart.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ChartViewportPlanTest {
    private fun box(s: Double, n: Double, w: Double, e: Double) = ChartSelectionExtent(s, n, w, e)
    private fun rectangle(b: ChartSelectionExtent) = listOf(CatalogCoordinate(b.south, b.west),
        CatalogCoordinate(b.south, b.east), CatalogCoordinate(b.north, b.east), CatalogCoordinate(b.north, b.west))
    private fun cell(name: String, b: ChartSelectionExtent?, scale: Int? = 12000,
        exclusions: List<List<CatalogCoordinate>> = emptyList(), withMetadata: Boolean = true): ChartCell {
        val raw = ChartCell(name, 1, 0, "$name.000", "20261010", mapOf("$name.000" to "a".repeat(64)), selectionExtent = b)
        if (!withMetadata) return raw
        val value = ValidatedChartCell(raw.base, 1, "no5-s57-osenc201-metadata-v1", "b".repeat(64), "c".repeat(64),
            1, scale, 5, b?.let { ChartCellBounds(it.south, it.west, it.north, it.east) },
            b?.let { listOf(rectangle(it).map { p -> ChartCellCoordinate(p.latitude, p.longitude) }) }.orEmpty(),
            exclusions.map { ring -> ring.map { ChartCellCoordinate(it.latitude, it.longitude) } }, ChartCoverageCapabilities())
        return raw.copy(catalogMetadata = ChartMetadataContract.attach(raw, 0, value))
    }
    private fun pack(cells: List<ChartCell>) = ChartPackage("f".repeat(36), "test", 0,
        cells.mapIndexed { order, c -> c.copy(catalogMetadata = c.catalogMetadata?.copy(order = order)) })
    private fun catalog(cells: List<ChartCell>) = pack(cells).let { ChartPlanningCatalog.fromPackage(it, it.cells.map { c -> c.name }.toSet()) }
    private fun names(keys: Collection<ChartCellKey>) = keys.map { it.name }
    private fun camera(lat: Double = 0.0, lon: Double = 0.0, ppm: Double = .1, rotation: Double = 0.0,
        w: Int = 2000, h: Int = 1070) = ChartPlanCamera(lat, lon, ppm, rotation, w, h)

    @Test fun inlandProviderSurvivesUnknownMetadataAndChangesThePlanningIdentity() {
        val raw = cell("1W7UH010", box(-.01, .01, -.01, .01), withMetadata = false).copy(provider = S57Profiles.WSV)
        val inland = catalog(listOf(raw)); val noaa = catalog(listOf(raw.copy(provider = S57Profiles.NOAA)))
        assertEquals(S57Profiles.WSV, inland.cells.single().key.provider)
        assertNotEquals(inland.generation, noaa.generation)
        val plan = ChartViewportPlanner(inland).plan(camera())
        assertEquals(S57Profiles.WSV, plan.required.single().provider)
        assertEquals(plan.required, plan.metadataRequired)
    }

    @Test fun wholeViewportIncludesNeighbourAndOverviewWithRemoteCentreAcrossScales() {
        val cells = listOf(cell("detail", box(-.01, .01, -.01, .01), 5000),
            cell("neighbour", box(-.01, .01, .02, .03), 50000),
            cell("overview", box(-10.0, 10.0, .03, 10.0), 500000), cell("remote", box(20.0, 21.0, 20.0, 21.0)))
        val plan = ChartViewportPlanner(catalog(cells)).plan(camera())
        assertEquals(listOf("detail", "neighbour", "overview"), names(plan.required))
        assertEquals(listOf(5000, 50000, 500000), plan.scaleCandidates.map { it.nativeScale })
        // Overlap candidates remain together. A planner cannot invent a per-pixel quilt rule.
        assertEquals(listOf("detail", "overview"), names(ChartViewportPlanner(catalog(listOf(cells[0],
            cells[2].copy(selectionExtent = box(-10.0, 10.0, -10.0, 10.0), catalogMetadata = null)))).plan(camera()).required))
    }
    @Test fun rotatedQuadUsesEveryCornerAndActualProducerHeight() {
        val camera = camera(ppm = 1.0, rotation = Math.PI / 4, w = 2000, h = 1338)
        val corners = camera.corners()
        assertEquals(4, corners.size)
        val diagonal = corners.maxBy { it.latitude }
        val target = cell("corner", box(diagonal.latitude - .000001, diagonal.latitude + .000001,
            diagonal.longitude - .000001, diagonal.longitude + .000001))
        assertEquals(listOf("corner"), names(ChartViewportPlanner(catalog(listOf(target))).plan(camera).required))
        val smaller = camera.copy(height = 600)
        assertTrue(ChartViewportPlanner(catalog(listOf(target))).plan(smaller).required.isEmpty())
    }
    @Test fun mercatorCornerRotationAndLongitudeStayContinuous() {
        val plain = camera(lon = 179.999, ppm = 1.0, w = 2000, h = 1000).corners()
        assertTrue(plain[1].longitude > 180.0); assertTrue(plain[0].longitude < 180.0)
        assertEquals(plain[0].latitude, plain[1].latitude, 1e-12)
        val quarter = camera(rotation = Math.PI / 2, ppm = 1.0, w = 2000, h = 1000).corners()
        assertEquals(quarter[0].longitude, quarter[1].longitude, 1e-12)
        assertTrue(quarter[1].latitude > quarter[0].latitude)
        assertTrue(camera().corners().all { it.latitude.isFinite() && it.longitude.isFinite() })
    }
    @Test fun hundredCamerasMatchProductionNativeFromSMCornersIncludingRotationReserveAndExtremeZoom() {
        val fixture = Json.parseToJsonElement(javaClass.getResource("/native-viewport-corners-v1.json")!!.readText()).jsonArray
        assertEquals(100, fixture.size)
        for (value in fixture) {
            val entry = value.jsonObject; val pose = entry.getValue("camera").jsonArray
            val camera = camera(pose[0].jsonPrimitive.double, pose[1].jsonPrimitive.double,
                pose[2].jsonPrimitive.double, pose[3].jsonPrimitive.double, pose[4].jsonPrimitive.int, pose[5].jsonPrimitive.int)
            val corners = camera.corners()
            for ((i, point) in entry.getValue("corners").jsonArray.withIndex()) {
                val p = point.jsonArray
                assertEquals("Native latitude $camera corner $i", p[0].jsonPrimitive.double, corners[i].latitude, 1e-10)
                assertEquals("Native longitude $camera corner $i", p[1].jsonPrimitive.double, corners[i].longitude, 1e-8)
            }
        }
    }
    @Test fun datelineAliasesWrappedLegacyBoundsAndWorldViewportStayConservative() {
        val c = catalog(listOf(cell("east", box(-1.0, 1.0, 179.0, 180.0), withMetadata = false),
            cell("west", box(-1.0, 1.0, -180.0, -179.0), withMetadata = false),
            cell("wrapped", box(-1.0, 1.0, 179.0, -179.0), withMetadata = false)))
        val index = FullScanChartCellIndex(c)
        assertEquals(listOf(0, 1, 2), index.query(box(0.0, 0.0, -180.0, -180.0)))
        assertEquals(listOf(0, 1, 2), index.query(box(0.0, 0.0, 180.0, 180.0)))
        assertEquals(listOf("east", "west", "wrapped"), names(ChartViewportPlanner(c).plan(camera(lon = 180.0)).required))
        assertEquals(3, ChartViewportPlanner(c).plan(camera(ppm = .00001)).required.size)
    }
    @Test fun unknownStaleAndUnlocatedMetadataNeverDisappearOrGuessScale() {
        val valid = cell("stale", box(-.1, .1, -.1, .1))
        val stale = valid.copy(catalogMetadata = valid.catalogMetadata!!.copy(sourceKey = "d".repeat(64)))
        val c = catalog(listOf(stale, cell("unlocated", null, withMetadata = false),
            cell("legacy", box(-.1, .1, -.1, .1), withMetadata = false), cell("scaleUnknown", box(-.1, .1, -.1, .1), null)))
        val plan = ChartViewportPlanner(c).plan(camera())
        assertEquals(4, plan.required.size); assertEquals(plan.required, plan.metadataRequired)
        assertEquals(listOf<Int?>(null), plan.scaleCandidates.map { it.nativeScale })
        assertEquals(listOf("unlocated"), names(ChartViewportPlanner(c).plan(camera(lat = 40.0, lon = 40.0)).required))
    }
    @Test fun rectangularExclusionPrunesOnlyProvenInteriorAndRetainsEdgesPartialAndConcaveCoverage() {
        val outer = box(-1.0, 1.0, -1.0, 1.0); val hole = box(-.2, .2, -.2, .2)
        val c = catalog(listOf(cell("hole", outer, exclusions = listOf(rectangle(hole)))))
        assertTrue(ChartViewportPlanner(c).plan(camera()).required.isEmpty())
        assertEquals(1, ChartViewportPlanner(c).plan(camera(lon = .2)).required.size)
        assertEquals(1, ChartViewportPlanner(c).plan(camera(ppm = .01)).required.size)
        val bowtie = rectangle(hole).let { listOf(it[0], it[2], it[1], it[3]) }
        val uncertain = catalog(listOf(cell("uncertain", outer, exclusions = listOf(bowtie))))
        assertEquals(1, ChartViewportPlanner(uncertain).plan(camera()).required.size)
        val concave = rectangle(hole).toMutableList().apply { add(2, CatalogCoordinate(0.0, 0.0)) }
        assertEquals(1, ChartViewportPlanner(catalog(listOf(cell("concave", outer, exclusions = listOf(concave)))))
            .plan(camera()).required.size)
    }
    @Test fun exactDatelineEndpointWithPartialExclusionAndAdjacentControlsRetainsVisibleCell() {
        val ppm = 0.017973495080422598
        for (sign in listOf(1.0, -1.0)) {
            val outer = if (sign > 0) box(-1.0, 1.0, 178.0, 180.0) else box(-1.0, 1.0, -180.0, -178.0)
            val hole = if (sign > 0) box(-.8, .8, 178.0, 179.75) else box(-.8, .8, -179.75, -178.0)
            val c = catalog(listOf(cell("partial", outer, exclusions = listOf(rectangle(hole)))))
            val exact = camera(lon = sign * 179.5, ppm = ppm)
            val edge = if (sign > 0) exact.corners()[1].longitude else exact.corners()[0].longitude
            assertEquals(sign * 180.0, edge, 0.0)
            for (longitude in listOf(179.49, 179.5, 179.51)) {
                val pose = exact.copy(longitude = sign * longitude)
                assertEquals("Visible cell at exact/adjacent dateline endpoint $pose", listOf("partial"),
                    names(ChartViewportPlanner(c).plan(pose).required))
            }
        }
    }
    @Test fun bboxEdgesTinySliversAndScaleChangesDoNotLoseCandidates() {
        val c = catalog(listOf(cell("thin", box(-.0000001, .0000001, .01, .0100001), 500000)))
        assertEquals(listOf(0), FullScanChartCellIndex(c).query(box(-.0000001, -.0000001, .01, .01)))
        assertEquals(listOf("thin"), names(ChartViewportPlanner(c).plan(camera()).required))
    }
    @Test fun releaseCatalogHasNo100CellTruncationButDoesNotChangeActivationSemantics() {
        val cells = (0 until 350).map { cell("cell$it", box(-1.0, 1.0, -1.0, 1.0), if (it % 2 == 0) 5000 else 50000) }
        val c = catalog(cells)
        assertEquals(350, c.cells.size); assertEquals(350, ChartViewportPlanner(c).plan(camera()).required.size)
        assertEquals(cells.map { it.name }, names(ChartViewportPlanner(c).plan(camera()).required))
        val p = pack(cells)
        assertEquals(1, ChartPlanningCatalog.fromPackage(p, setOf("cell349")).cells.size)
    }
    @Test fun catalogueGenerationChangesWithSourcesCoverageCapabilitiesAndOrderButNotCamera() {
        val a = cell("a", box(-1.0, 1.0, -1.0, 1.0)); val b = cell("b", box(-1.0, 1.0, -1.0, 1.0))
        val c = catalog(listOf(a, b)); assertEquals(c.generation, catalog(listOf(a, b)).generation)
        assertNotEquals(c.generation, catalog(listOf(b, a)).generation)
        assertNotEquals(c.generation, catalog(listOf(a.copy(update = 1), b)).generation)
        assertNotEquals(c.generation, catalog(listOf(a.copy(catalogMetadata = a.catalogMetadata!!.copy(
            exclusions = listOf(rectangle(box(-.1, .1, -.1, .1))))), b)).generation)
        assertNotEquals(c.generation, catalog(listOf(a.copy(catalogMetadata = a.catalogMetadata!!.copy(
            capabilities = a.catalogMetadata.capabilities.copy(polygonHoles = true))), b)).generation)
    }
    @Test fun currentViewWorkExpiresWhileStillUsefulPreparationSurvivesAndRevisitIsComplete() {
        val c = catalog(listOf(cell("a", box(-.1, .1, -.1, .1)), cell("b", box(40.0, 41.0, 40.0, 41.0))))
        val planner = ChartViewportPlanner(c); val first = planner.plan(camera())
        assertEquals(first.tag, planner.plan(camera()).tag)
        val moved = planner.plan(camera(lon = .01), first.required.toSet())
        assertFalse(planner.isCurrent(first.tag)); assertTrue(planner.isCurrent(moved.tag))
        assertEquals(first.required, moved.retain); assertTrue(moved.drop.isEmpty())
        assertTrue(moved.usefulPreparationSources.containsAll(first.required))
        val far = planner.plan(camera(lat = 40.5, lon = 40.5), first.required.toSet())
        assertEquals(first.required, far.drop); assertTrue(far.retain.isEmpty())
        val revisit = planner.plan(camera()); assertEquals(first.required, revisit.required)
        assertFalse(planner.isCurrent(far.tag)); assertTrue(planner.isCurrent(revisit.tag))
        val differentCatalog = ChartViewportPlanner(catalog(listOf(cell("a", box(-.1, .1, -.1, .1)).copy(update = 1))))
        differentCatalog.plan(camera()); assertFalse(differentCatalog.isCurrent(revisit.tag))
    }
    @Test fun boundedDirectionalPrefetchCannotDisplaceRequiredAndResidencyRecommendationsArePure() {
        val c = catalog(listOf(cell("required", box(-.01, .01, -.01, .01)),
            cell("ahead1", box(-.01, .01, .10, .11)), cell("ahead2", box(-.01, .01, .12, .13)),
            cell("behind", box(-.01, .01, -.19, -.18))))
        val policy = ChartPlanPolicy(prefetchPixels = 500.0, leadX = 500.0, maxPrefetchCells = 1)
        val resident = c.cells.map { it.key }.toSet(); val plan = ChartViewportPlanner(c, policy = policy).plan(camera(), resident)
        assertEquals(listOf("required"), names(plan.required)); assertEquals(listOf("ahead1"), names(plan.prefetch))
        assertEquals(listOf("ahead1", "required"), names(plan.retain)); assertEquals(listOf("ahead2", "behind"), names(plan.drop))
        assertEquals(4, resident.size); assertEquals(4, c.cells.size)
    }
    @Test fun generationAndIndexContractMismatchesFailExplicitly() {
        val c = catalog(listOf(cell("a", box(-1.0, 1.0, -1.0, 1.0))))
        try { ChartViewportPlanner(c, object : ChartCellCandidateIndex {
            override val generation = "stale"; override fun query(bounds: ChartSelectionExtent) = listOf(0)
        }); fail("Stale generation") } catch (_: IllegalArgumentException) {}
        val bad = object : ChartCellCandidateIndex {
            override val generation = c.generation; override fun query(bounds: ChartSelectionExtent) = listOf(0, 0)
        }
        try { ChartViewportPlanner(c, bad).plan(camera()); fail("Duplicate index output") } catch (_: IllegalArgumentException) {}
    }
    @Test fun samePoseWithDifferentPolicyCannotAcceptAnotherPlannersTag() {
        val c = catalog(listOf(cell("a", box(-1.0, 1.0, -1.0, 1.0))))
        val default = ChartViewportPlanner(c).plan(camera())
        val padded = ChartViewportPlanner(c, policy = ChartPlanPolicy(contextPixels = 100.0))
        padded.plan(camera()); assertFalse(padded.isCurrent(default.tag))
        val moved = ChartViewportPlanner(c); moved.plan(camera(lon = 1.0))
        assertFalse(moved.isCurrent(default.tag)) // Same revision number in a new planner is not the same pose.
    }
    @Test fun indexedUnknownsRemainCandidatesAndFailedQueryPreservesLastCommittedPlan() {
        val c = catalog(listOf(cell("unlocated", null, withMetadata = false)))
        var invalid = false
        val index = object : ChartCellCandidateIndex {
            override val generation = c.generation
            override fun query(bounds: ChartSelectionExtent) = if (invalid) listOf(0, 0) else emptyList()
        }
        val planner = ChartViewportPlanner(c, index); val first = planner.plan(camera())
        assertEquals(listOf("unlocated"), names(first.required)); invalid = true
        try { planner.plan(camera(lon = 1.0)); fail("Invalid candidate query") } catch (_: IllegalArgumentException) {}
        assertTrue(planner.isCurrent(first.tag)); assertEquals(first, planner.plan(camera()))
    }
    @Test fun pinnedGenerationCopiesMutableInputAndRepeatedPoseDoesNotRequeryIndex() {
        val ring = rectangle(box(-1.0, 1.0, -1.0, 1.0)).toMutableList()
        val exclusions = mutableListOf<List<CatalogCoordinate>>(ring)
        val original = cell("a", box(-10.0, 10.0, -10.0, 10.0)).let {
            it.copy(catalogMetadata = it.catalogMetadata!!.copy(exclusions = exclusions))
        }
        val c = catalog(listOf(original)); val generation = c.generation
        ring.clear(); exclusions.clear()
        assertEquals(generation, c.generation); assertEquals(4, c.cells.single().metadata!!.exclusions.single().size)
        var calls = 0; val full = FullScanChartCellIndex(c)
        val index = object : ChartCellCandidateIndex {
            override val generation = c.generation
            override fun query(bounds: ChartSelectionExtent): List<Int> { calls++; return full.query(bounds) }
        }
        val planner = ChartViewportPlanner(c, index); val first = planner.plan(camera())
        assertTrue(first.required.isEmpty()); planner.plan(camera(), c.cells.map { it.key }.toSet())
        assertEquals(1, calls)
    }
    @Test fun twoHundredQueriesOn600CellsMatchExistingPythonFullScanOracleIncludingDatelineAndPoints() {
        val fixture = Json.parseToJsonElement(javaClass.getResource("/fullscan-candidates-v1.json")!!.readText()).jsonObject
        fun bounds(array: JsonArray) = box(array[0].jsonPrimitive.double, array[2].jsonPrimitive.double,
            array[1].jsonPrimitive.double, array[3].jsonPrimitive.double)
        val c = catalog(fixture.getValue("cells").jsonArray.map { value -> value.jsonObject.let {
            cell(it.getValue("id").jsonPrimitive.content, bounds(it.getValue("box").jsonArray), withMetadata = false)
        } })
        val index = FullScanChartCellIndex(c)
        assertEquals(600, c.cells.size); assertEquals(200, fixture.getValue("queries").jsonArray.size)
        for (value in fixture.getValue("queries").jsonArray) {
            val q = value.jsonObject; val b = bounds(q.getValue("box").jsonArray)
            assertEquals("FullScan query $b", q.getValue("expected").jsonArray.map { it.jsonPrimitive.int }, index.query(b))
        }
    }
    @Test fun cancelledCellsAreNotReleasedAndUnknownNamesFailWithoutSilentSubset() {
        val p = pack(listOf(cell("cancelled", null).copy(cancelled = true), cell("live", null)))
        assertEquals(listOf("live"), ChartPlanningCatalog.fromPackage(p, setOf("cancelled", "live")).cells.map { it.key.name })
        try { ChartPlanningCatalog.fromPackage(p, setOf("missing")); fail("Unknown cell") } catch (_: IllegalArgumentException) {}
    }

    private fun boston(): ChartPackage {
        val doc = Json.parseToJsonElement(javaClass.getResource("/native-cell-metadata-v1.json")!!.readText()).jsonObject
        return pack(doc.getValue("cells").jsonArray.mapIndexed { order, element ->
            val entry = element.jsonObject; val name = entry.getValue("cell").jsonPrimitive.content
            val files = entry.getValue("sourceFiles").jsonObject.mapValues { it.value.jsonPrimitive.content }
            for ((file, hash) in files) assertEquals(hash, ChartLibrary.sha256(File("../chart-engine/src/main/assets/charts/noaa", file)))
            val identification = S57Identification.read(File("../chart-engine/src/main/assets/charts/noaa", "$name.000"))
            val cell = ChartCell(name, identification.edition, files.size - 1, "$name.000", identification.issueDate, files)
            val metadata = ChartCellMetadataDecoder.decode(cell.base, entry.getValue("metadata").toString())
            assertEquals(entry.getValue("catalogSourceKey").jsonPrimitive.content, ChartMetadataContract.sourceKey(cell))
            cell.copy(catalogMetadata = ChartMetadataContract.attach(cell, order, metadata))
        })
    }
    @Test fun nineReproducedBostonNeighbourLossesAreRequiredAcrossMixedScaleZoomAndRotation() {
        val source = boston()
        // Synthetic scale metadata only: the four real NOAA cells are all native 1:12000.
        val mixed = source.copy(cells = source.cells.mapIndexed { i, c -> c.copy(catalogMetadata =
            c.catalogMetadata!!.copy(nativeScale = if (i == 0) 5000 else 50000)) })
        val c = ChartPlanningCatalog.fromPackage(mixed, mixed.cells.map { it.name }.toSet())
        val planner = ChartViewportPlanner(c)
        val expected = listOf("US5BOSCC", "US5BOSCD", "US5BOSDC", "US5BOSDD")
        val first = planner.plan(camera(42.36, -71.04, .06))
        assertEquals(expected, names(first.required))
        for (ppm in listOf(.3, 1.2, .3, .15, .06)) planner.plan(camera(42.36, -71.04, ppm))
        val returned = planner.plan(camera(42.36, -71.04, .06))
        val rotated = planner.plan(camera(42.36, -71.04, .06, .63, 2500, 1338))
        for (plan in listOf(first, returned, rotated)) for (name in expected.drop(1))
            assertTrue("Previously missing verified neighbour $name at ${plan.camera}", name in names(plan.required))
        assertEquals(first.required, returned.required); assertEquals(listOf(5000, 50000), first.scaleCandidates.map { it.nativeScale })
    }
    @Test fun realBostonEmptyCentreWarmRevisitAndWideRotatedCanvasHaveSamePlanAsFresh() {
        val source = boston(); val c = ChartPlanningCatalog.fromPackage(source, source.cells.map { it.name }.toSet())
        assertTrue(c.cells.all { it.metadata!!.nativeScale == 12000 })
        for (lat in listOf(42.37, 42.36)) for ((w, h, r) in listOf(Triple(2000, 1070, 0.0), Triple(2500, 1338, .63))) {
            val warm = ChartViewportPlanner(c)
            for (ppm in listOf(.06, .3, 1.2, .3, .15, .06)) warm.plan(camera(lat, -71.025, ppm, r, w, h))
            val terminal = warm.plan(camera(lat, -71.025, .06, r, w, h))
            val fresh = ChartViewportPlanner(c).plan(camera(lat, -71.025, .06, r, w, h))
            assertEquals(fresh.required, terminal.required); assertEquals(4, terminal.required.size)
        }
    }
}
