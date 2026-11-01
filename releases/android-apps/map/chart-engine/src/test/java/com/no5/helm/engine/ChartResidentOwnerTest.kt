package com.no5.helm.engine

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test

class ChartResidentOwnerTest {
    private val fixture = ChartResidencyDecoder.decode(javaClass.getResource("/native-residency-status-v1.json")!!.readText())
    private val geometry = ChartSurfaceGeometry.fit(2000, 1070)
    private val camera = ChartViewport(42.36, -71.04, .15, .63, 2000, 1070)
    private fun catalog(count: Int = 2) = ChartResidentCatalog(fixture.catalogGeneration,
        (0 until count).map { i -> ChartResidentSource("noaa-s57", "US5TEST${i}",
            if (i < fixture.sources.size) fixture.sources[i] else i.toString(16).padStart(64, '0'),
            1, 0, i, listOf(ChartResidentFile("/chart/US5TEST$i.000", "a".repeat(64)))) }, ChartResidentBudget(2))
    private fun bounds(catalog: ChartResidentCatalog) = ChartNavigationBounds(7,
        catalog.sources.map { ChartCellExtent(it.name, 42.0, 43.0, -72.0, -70.0) })
    private fun frame(owner: ChartResidentOwner) = ChartFrame(1, "camera-9", geometry, camera,
        sourceGeneration = owner.sourceGeneration, contentGeneration = owner.contentGeneration)

    @Test fun cellMutationKeepsNavigationAndOldAffineBufferButRevokesQueryAuthority() {
        val owner = ChartResidentOwner(); val catalog = catalog(); val bounds = bounds(catalog)
        owner.activate(catalog, bounds, fixture)
        val registry = ChartBufferRegistry()
        val old = registry.submit(0, 11, 9, geometry, camera, "test", sourceGeneration = owner.sourceGeneration,
            contentGeneration = owner.contentGeneration)!!
        owner.completed(old.frame, true); assertEquals(fixture.contentRevision, owner.checkpointRevision)
        assertTrue(owner.update(fixture.copy(contentRevision = fixture.contentRevision + 1, sources = emptyList(), resident = 0)))
        assertEquals(bounds, owner.navigationBounds)
        assertEquals(old, registry.lookup(11)); assertFalse(owner.frameMatches(old.frame))
        assertEquals(-1L, owner.checkpointRevision)
        owner.completed(old.frame, true); assertEquals(-1L, owner.checkpointRevision)
        val current = frame(owner)
        owner.completed(current, false); assertEquals(-1L, owner.checkpointRevision)
        owner.completed(current, true); assertEquals(fixture.contentRevision + 1, owner.checkpointRevision)
        assertTrue(owner.frameMatches(current))
    }
    @Test fun sourceSwitchAndEagerSwitchRejectFramesEvenAtIdenticalCameraAndNativeRevision() {
        val owner = ChartResidentOwner(); val catalog = catalog()
        owner.activate(catalog, bounds(catalog), fixture)
        val old = frame(owner)
        owner.activate(catalog, bounds(catalog).copy(generation = 8), fixture)
        assertFalse(owner.frameMatches(old)); assertEquals(camera, frame(owner).viewport)
        val resident = frame(owner); owner.eager()
        assertFalse(owner.active); assertNull(owner.navigationBounds); assertFalse(owner.frameMatches(resident))
    }
    @Test fun statusOnlyUpdatesDoNotRevokeFrameAndUnknownSourcesCannotBecomeLoadedNames() {
        val owner = ChartResidentOwner(); val catalog = catalog()
        owner.activate(catalog, bounds(catalog), fixture)
        val frame = frame(owner)
        assertFalse(owner.update(fixture.copy(cameraRevision = fixture.cameraRevision + 1)))
        assertTrue(owner.frameMatches(frame)); assertEquals(catalog.sources.map { it.name }, owner.loadedNames(fixture))
        try { owner.loadedNames(fixture.copy(sources = listOf("f".repeat(64)))); fail("unknown key accepted") }
        catch (_: IllegalStateException) { }
    }
    @Test fun activationFreezesEntire150CellCatalogWithoutExpandingTwoCellBudget() {
        val catalog = catalog(150)
        val source = catalog.sources.toMutableList(); val cells = bounds(catalog).cells.toMutableList()
        val activation = ChartResidentActivation("all-released", catalog.copy(sources = source), camera,
            ChartNavigationBounds(7, cells))
        val frozen = residentActivationSnapshot(activation)
        source.clear(); cells.clear()
        assertEquals(150, frozen.catalog.sources.size); assertEquals(150, frozen.navigationBounds.cells.size)
        assertEquals(2, frozen.catalog.budget.cells); assertEquals(camera, frozen.initialViewport)
    }
    @Test fun invalidCameraOrSubsetNavigationRejectsBeforeSourceMutation() {
        val catalog = catalog(); val valid = ChartResidentActivation("set", catalog, camera, bounds(catalog))
        val invalid = listOf(valid.copy(initialViewport = camera.copy(metresToPixels = Double.NaN)),
            valid.copy(initialViewport = camera.copy(width = 0)), valid.copy(initialViewport = camera.copy(latitude = 86.0)),
            valid.copy(navigationBounds = valid.navigationBounds.copy(cells = valid.navigationBounds.cells.take(1))),
            valid.copy(navigationBounds = valid.navigationBounds.copy(cells = valid.navigationBounds.cells.map { it.copy(north = Double.NaN) })))
        for (value in invalid) try { residentActivationSnapshot(value); fail("invalid activation accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun unknownWholeCatalogExtentsCanExplicitlyDisableEdgeStopping() {
        val catalog = catalog(150)
        val activation = ChartResidentActivation("legacy-release", catalog, camera, ChartNavigationBounds(8, emptyList()))
        val snapshot = residentActivationSnapshot(activation)
        assertTrue(snapshot.navigationBounds.cells.isEmpty()); assertEquals(150, snapshot.catalog.sources.size)
        assertEquals(2, snapshot.catalog.budget.cells)
    }
    @Test fun emptyActivationIsExplicitAndBudgetChangeKeepsSourceAuthority() {
        val empty = catalog(0)
        val frozen = residentActivationSnapshot(ChartResidentActivation("empty", empty, camera, ChartNavigationBounds(9, emptyList())))
        assertTrue(frozen.catalog.sources.isEmpty()); assertTrue(frozen.navigationBounds.cells.isEmpty())
        val owner = ChartResidentOwner(); val loaded = catalog()
        owner.activate(loaded, bounds(loaded), fixture)
        val source = owner.sourceGeneration; val content = owner.contentGeneration
        owner.budget(ChartResidentBudget(1))
        assertEquals(source, owner.sourceGeneration); assertEquals(content, owner.contentGeneration)
        assertEquals(1, owner.catalog!!.budget.cells)
    }
    @Test fun wsvDescriptorsUseSeparateProviderAndExactOneWProfile() {
        val wsv = ChartResidentSource("wsv-inland-s57", "1W7EST00",
            "822c57454f42dbb0cad2a5aa20c87010f898d9883790b5767b02954afe9e60e3", 1, 1, 0,
            listOf(ChartResidentFile("/chart/1W7EST00.000", "a".repeat(64)),
                ChartResidentFile("/chart/1W7EST00.001", "b".repeat(64))))
        val selected = catalog(1).copy(sources = listOf(wsv))
        val activation = ChartResidentActivation("wsv", selected, camera, bounds(selected))
        val frozen = residentActivationSnapshot(activation)
        assertEquals("wsv-inland-s57", frozen.catalog.sources.single().provider)
        assertEquals(wsv.files, frozen.catalog.sources.single().files)
        for (source in listOf(wsv.copy(provider = "unknown-s57"), wsv.copy(name = "US5TEST0"),
            wsv.copy(name = "1W6EST00"), wsv.copy(name = "1W7EST000"), wsv.copy(name = "1Wtest00"))) {
            val invalidCatalog = selected.copy(sources = listOf(source))
            try { residentActivationSnapshot(activation.copy(catalog = invalidCatalog, navigationBounds = bounds(invalidCatalog)))
                fail("unsupported provider/profile accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun admissionGuardsOwnerTeardownAndEveryMeasurementOrRecoveryOwner() {
        val state = ChartEngineStatus(initialized = true)
        assertNull(residentOperationBlocker(state, false, true, false, false))
        for (blocked in listOf(state.copy(rendererCapture = RendererCaptureStatus(active = true)),
            state.copy(rendererBenchmark = RendererBenchmarkStatus(active = true)), state.copy(rendererBenchmarkRecoveryPending = true)))
            assertNotNull(residentOperationBlocker(blocked, false, true, false, false))
        assertNotNull(residentOperationBlocker(state, true, true, false, false))
        assertNotNull(residentOperationBlocker(state, false, false, false, false))
        assertNotNull(residentOperationBlocker(state, false, true, true, false))
        assertNotNull(residentOperationBlocker(state, false, true, false, true))
    }
    @Test fun completedBufferCannotBorrowAnotherSourceOrContentTag() {
        val registry = ChartBufferRegistry()
        val buffer = registry.submit(0, 11, 9, geometry, camera, "test", sourceGeneration = 7, contentGeneration = 15)!!
        assertNull(registry.complete(11, buffer.frame.copy(sourceGeneration = 8)))
        assertNull(registry.complete(11, buffer.frame.copy(contentGeneration = 16)))
        assertFalse(registry.lookup(11)!!.producerCompleted)
        assertEquals(buffer.frame, registry.complete(11, buffer.frame)!!.frame)
    }
    @Test fun producerCheckpointRequiresRegisteredCompletionAndFullStage() {
        val owner = ChartResidentOwner(); val catalog = catalog()
        owner.activate(catalog, bounds(catalog), fixture)
        val registry = ChartBufferRegistry()
        val full = registry.submit(0, 11, 9, geometry, camera, "test", sourceGeneration = owner.sourceGeneration,
            contentGeneration = owner.contentGeneration)!!
        owner.completed(full); assertEquals(-1L, owner.checkpointRevision)
        val partial = registry.submit(0, 12, 9, geometry, camera, "test", ChartContentStage.REUSED_DETAILS_WITH_BASE,
            sourceGeneration = owner.sourceGeneration, contentGeneration = owner.contentGeneration)!!
        owner.completed(registry.complete(12, partial.frame)!!); assertEquals(-1L, owner.checkpointRevision)
        assertNull(registry.complete(11, full.frame.copy(contentGeneration = owner.contentGeneration + 1)))
        owner.completed(registry.lookup(11)!!); assertEquals(-1L, owner.checkpointRevision)
        owner.completed(registry.complete(11, full.frame)!!); assertEquals(fixture.contentRevision, owner.checkpointRevision)
        // Replacing the producer Surface invalidates its checkpoint and exact image tags.
        val content = owner.contentGeneration
        owner.invalidateCheckpoint(); registry.invalidate()
        assertEquals(-1L, owner.checkpointRevision); assertEquals(content, owner.contentGeneration)
        assertNull(registry.complete(11, full.frame))
        val replacement = registry.submit(1, 13, 9, geometry, camera, "test", sourceGeneration = owner.sourceGeneration,
            contentGeneration = owner.contentGeneration)!!
        owner.completed(registry.complete(13, replacement.frame)!!); assertEquals(fixture.contentRevision, owner.checkpointRevision)
    }
}
