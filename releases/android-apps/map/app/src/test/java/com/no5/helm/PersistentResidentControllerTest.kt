package com.no5.helm

import com.no5.helm.chart.*
import com.no5.helm.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PersistentResidentControllerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val libraryRoot get() = File(context.filesDir, "chart-library-v1")
    private val active get() = File(libraryRoot, "active.json")
    private val assets = File("../chart-engine/src/main/assets/charts/noaa")
    private lateinit var dispatcher: ExecutorCoroutineDispatcher
    private lateinit var scope: CoroutineScope
    private val controllers = mutableListOf<PersistentChartController>()
    @Before fun setup() {
        libraryRoot.deleteRecursively(); File(context.filesDir, "chart-data").deleteRecursively()
        dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }
    @After fun cleanup() = runBlocking<Unit> {
        controllers.forEach { it.close() }; scope.cancel()
        try { withTimeout(5000) { scope.coroutineContext[Job]!!.join() } }
        finally { dispatcher.close() }
    }
    private fun controller(engine: RecordingEngine) = PersistentChartController(context, engine, scope).also { controllers += it }
    private fun legacy() = File(context.filesDir, "chart-data/charts/noaa").apply {
        mkdirs(); assets.listFiles()!!.forEach { it.copyTo(File(this, it.name), overwrite = true) }
    }
    private suspend fun settleOwner() = withContext(dispatcher) { yield() }
    private suspend fun awaitFinished(controller: PersistentChartController) = withTimeout(5000) {
        controller.state.first { !it.busy }
    }

    @Test fun genuineBostonMigrationPublishesV2AndDoesNotEagerlyLoadWithoutSurface() = runBlocking<Unit> {
        val original = legacy()
        val hashes = original.listFiles()!!.associate { it.name to ChartLibrary.sha256(it) }
        val engine = RecordingEngine(); val controller = controller(engine)
        controller.prepare(); settleOwner()
        val saved = controller.state.value.snapshot
        assertEquals(4, saved.active!!.cells.size); assertEquals(2, saved.selectionVersion)
        assertEquals(4, saved.activeRelease!!.cells.size)
        assertEquals(1, engine.activations.size); assertEquals("noaa-boston-harbour", engine.activations.single().id)
        assertEquals(4, engine.activations.single().catalog.sources.size)
        assertNotNull(engine.activations.single().cameraPolicy)
        assertNotNull(engine.cameraGuard)
        assertEquals(0, engine.guardInstalls.get()) // Bound atomically by the real activation contract.
        assertEquals(1, engine.validations.get()); assertEquals(0, engine.eagerLoads.get())
        assertEquals(0, engine.cellJobs.get()); assertEquals(0, engine.poseReads.get())
        assertEquals(42.36, engine.status.value.viewport.latitude, 0.0)
        assertEquals(-71.04, engine.status.value.viewport.longitude, 0.0)
        assertEquals(hashes, original.listFiles()!!.associate { it.name to ChartLibrary.sha256(it) })
        assertTrue(ChartLibrary(libraryRoot).migrated())
    }

    @Test fun v2RestartUsesSavedFullSourcePinsWithoutReimportEagerLoadOrEarlyCellJobs() = runBlocking<Unit> {
        val old = legacy()
        val first = controller(RecordingEngine()); first.prepare(); settleOwner()
        val saved = first.state.value.snapshot
        val bytes = active.readBytes()
        first.close()
        File(old, "US5BOSCC.000").writeText("old APK asset location changed")
        val engine = RecordingEngine(); val restarted = controller(engine)
        restarted.prepare(); settleOwner()
        assertEquals(saved.active, restarted.state.value.snapshot.active)
        assertEquals(saved.activeRelease, restarted.state.value.snapshot.activeRelease)
        assertArrayEquals(bytes, active.readBytes())
        assertEquals(saved.activeRelease!!.cells.map { it.sourceKey }.toSet(),
            engine.activations.single().catalog.sources.map { it.sourceKey }.toSet())
        assertNotNull(engine.activations.single().cameraPolicy)
        assertEquals(0, engine.guardInstalls.get())
        assertEquals(0, engine.validations.get()); assertEquals(0, engine.eagerLoads.get())
        assertEquals(0, engine.cellJobs.get()); assertEquals(0, engine.poseReads.get())
    }

    @Test fun unknownAndCorruptStoredSelectionsAreNeverOverwrittenOrSentToNativeActivation() = runBlocking<Unit> {
        val pack = seedSyntheticPackage(2)
        val selection = ChartSelection(pack.id, pack.cells.map { it.name })
        for (text in listOf("{\"version\":3,\"packageId\":\"${pack.id}\",\"cells\":[]}", "broken selection")) {
            active.writeText(text)
            val engine = RecordingEngine(); val controller = controller(engine)
            controller.prepare(); settleOwner()
            assertNull(controller.state.value.snapshot.active)
            assertTrue(controller.state.value.snapshot.issues.isNotEmpty())
            assertEquals(0, engine.activations.size)
            controller.activate(selection); val done = awaitFinished(controller); settleOwner()
            assertTrue(done.message!!.contains("fehlgeschlagen"))
            assertEquals(text, active.readText())
            assertEquals(0, engine.activations.size); assertEquals(0, engine.eagerLoads.get())
            controller.close()
        }
    }

    @Test fun changedV2ManifestSourcePinsPreventSilentRebindingOnRestart() = runBlocking<Unit> {
        val pack = seedSyntheticPackage(2); val selection = ChartSelection(pack.id, pack.cells.map { it.name })
        val library = ChartLibrary(libraryRoot)
        library.activateResident(library.residentSelection(selection))
        val before = active.readBytes()
        val manifest = File(libraryRoot, "packages/${pack.id}/package.json")
        manifest.writeText(manifest.readText().replace("a".repeat(64), "b".repeat(64)))
        val engine = RecordingEngine(); val controller = controller(engine)
        controller.prepare(); settleOwner()
        assertNull(controller.state.value.snapshot.active); assertEquals(2, controller.state.value.snapshot.selectionVersion)
        assertTrue(controller.state.value.snapshot.issues.isNotEmpty())
        assertEquals(0, engine.activations.size); assertEquals(0, engine.eagerLoads.get())
        assertArrayEquals(before, active.readBytes())
    }

    @Test fun failedAtomicNativeActivationRetainsThePreviousFullReleaseAndCamera() = runBlocking<Unit> {
        val pack = seedSyntheticPackage(3); val selection = ChartSelection(pack.id, pack.cells.map { it.name })
        val library = ChartLibrary(libraryRoot); library.activateResident(library.residentSelection(selection))
        val engine = RecordingEngine(); val controller = controller(engine); controller.prepare(); settleOwner()
        val before = active.readBytes(); val previous = engine.currentActivation!!
        val previousGuard = engine.cameraGuard
        val view = engine.status.value.viewport.copy(longitude = -70.95, rotationRadians = .3)
        engine.setViewport(view); engine.failNext = true
        controller.activate(selection.copy(cells = selection.cells.take(1)))
        val done = awaitFinished(controller); settleOwner()
        assertTrue(done.message!!.contains("fehlgeschlagen")); assertEquals(selection, done.snapshot.active)
        assertEquals(previous, engine.currentActivation); assertEquals(view, engine.status.value.viewport)
        assertSame(previousGuard, engine.cameraGuard)
        assertEquals(0, engine.guardInstalls.get())
        assertArrayEquals(before, active.readBytes()); assertEquals(2, engine.activations.size)
        assertEquals(0, engine.eagerLoads.get()); assertEquals(0, engine.cellJobs.get())
    }

    @Test fun publicationFailureAfterSourceSwitchRestoresWholePreviousCatalogAndViewport() = runBlocking<Unit> {
        val pack = seedSyntheticPackage(3); val selection = ChartSelection(pack.id, pack.cells.map { it.name })
        val library = ChartLibrary(libraryRoot); library.activateResident(library.residentSelection(selection))
        val engine = RecordingEngine(); val controller = controller(engine); controller.prepare(); settleOwner()
        val before = active.readBytes()
        val view = engine.status.value.viewport.copy(longitude = -70.95, rotationRadians = .3)
        engine.setViewport(view)
        File(libraryRoot, "active.json.tmp").apply { mkdirs(); File(this, "occupied").writeText("preserve") }
        controller.activate(selection.copy(cells = selection.cells.take(1)))
        val done = awaitFinished(controller); settleOwner()
        assertTrue(done.message!!.contains("fehlgeschlagen")); assertEquals(selection, done.snapshot.active)
        assertEquals(listOf(3, 1, 3), engine.activations.map { it.catalog.sources.size })
        assertTrue(engine.activations.all { it.cameraPolicy != null })
        assertEquals(0, engine.guardInstalls.get())
        assertEquals(view, engine.status.value.viewport)
        assertEquals(selection.cells, engine.currentActivation!!.catalog.sources.map { it.name })
        assertArrayEquals(before, active.readBytes()); assertEquals(0, engine.eagerLoads.get())
        assertEquals(0, engine.cellJobs.get())
    }

    @Test fun synthetic150SourceReleaseUsesControllerCatalog150WithIndependentBudget100() = runBlocking<Unit> {
        val pack = seedSyntheticPackage(150)
        val engine = RecordingEngine(); val controller = controller(engine); controller.prepare()
        val selection = ChartSelection(pack.id, pack.cells.map { it.name })
        controller.activate(selection); val done = awaitFinished(controller); settleOwner()
        assertEquals(150, done.snapshot.active!!.cells.size)
        assertEquals(150, done.snapshot.activeRelease!!.cells.size); assertEquals(2, done.snapshot.selectionVersion)
        val activation = engine.activations.single()
        assertEquals(150, activation.catalog.sources.size); assertEquals(100, activation.catalog.budget.cells)
        assertNotNull(activation.cameraPolicy)
        assertEquals(selection.cells, activation.catalog.sources.map { it.name })
        assertFalse(File(libraryRoot, "packages/${pack.id}/data").exists())
        assertEquals(0, engine.eagerLoads.get()); assertEquals(0, engine.cellJobs.get())
        assertEquals(0, engine.poseReads.get())
    }

    /** Explicit synthetic metadata descriptors: no fake file claims or real 150-cell acceptance. */
    private fun seedSyntheticPackage(count: Int): ChartPackage {
        val library = ChartLibrary(libraryRoot)
        val id = "f".repeat(36)
        val directory = File(libraryRoot, "packages/$id").apply { mkdirs() }
        val cells = (0 until count).joinToString(",") { index ->
            val name = "US5T%04d".format(index)
            "{\"name\":\"$name\",\"edition\":1,\"update\":0,\"base\":\"$name.000\",\"issueDate\":\"20261010\",\"files\":{\"$name.000\":\"${"a".repeat(64)}\"},\"cancelled\":false}"
        }
        File(directory, "package.json").writeText("{\"id\":\"$id\",\"label\":\"synthetic descriptor test\",\"importedAt\":0,\"cells\":[$cells]}")
        library.markMigrated()
        return library.snapshot().packages.single()
    }

    private class RecordingEngine : ChartEngine {
        override val supportsResidentCatalog = true
        override val status = MutableStateFlow(ChartEngineStatus(initialized = true, surfaceAttached = false,
            viewport = ChartViewport(42.36, -71.04, .15, 0.0, 2000, 1070)))
        val activations = Collections.synchronizedList(mutableListOf<ChartResidentActivation>())
        val validations = AtomicInteger()
        val eagerLoads = AtomicInteger()
        val cellJobs = AtomicInteger()
        val poseReads = AtomicInteger()
        val guardInstalls = AtomicInteger()
        @Volatile var currentActivation: ChartResidentActivation? = null
        @Volatile var cameraGuard: ChartResidentCameraGuard? = null
        @Volatile var failNext = false
        override suspend fun activateResidentCatalog(activation: ChartResidentActivation): ChartResidentUpdate {
            activations += activation
            if (failNext) { failNext = false; return ChartResidentUpdate(EngineResult(false, "native refusal")) }
            currentActivation = activation
            status.value = status.value.copy(viewport = activation.initialViewport, residentCatalogActive = true,
                chartSourceGeneration = status.value.chartSourceGeneration + 1,
                navigationBounds = activation.navigationBounds, residentCheckpointRevision = -1)
            cameraGuard = activation.cameraPolicy?.bind(status.value.chartSourceGeneration)
            return ChartResidentUpdate(EngineResult(true), residency())
        }
        private fun residency() = ChartResidencyStatus(enabled = true, complete = false,
            catalogGeneration = currentActivation!!.catalog.generation, cameraKey = "", policyKey = "", cameraRevision = 0,
            contentRevision = 0, resident = 0, loading = 0, pinned = 0, required = 0, missing = 0, chargedBytes = 0,
            geometryBytes = 0, loadingBytes = 0, peakCells = 0, peakChargedBytes = 0, loads = 0, retains = 0,
            evictions = 0, failures = 0, message = "", sources = emptyList())
        override suspend fun readResidentPlanningViewport(): ChartViewport { poseReads.incrementAndGet(); return status.value.viewport }
        override suspend fun readResidency() = if (currentActivation == null)
            ChartResidentUpdate(EngineResult(false, "No resident catalog"))
        else ChartResidentUpdate(EngineResult(true), residency())
        override fun installResidentCameraGuard(expectedCatalogGeneration: String, guard: ChartResidentCameraGuard?): Boolean {
            guardInstalls.incrementAndGet()
            if (currentActivation?.catalog?.generation != expectedCatalogGeneration) return false
            cameraGuard = guard
            return true
        }
        override suspend fun advanceResidentLoading(): ChartResidentUpdate {
            cellJobs.incrementAndGet(); return ChartResidentUpdate(EngineResult(true), residency(), ChartResidentStep.IDLE)
        }
        override suspend fun validateChartFiles(paths: List<String>, cacheDirectory: String): EngineResult {
            validations.incrementAndGet(); return EngineResult(true)
        }
        override suspend fun loadChartSet(id: String, paths: List<String>): EngineResult {
            eagerLoads.incrementAndGet(); return EngineResult(true)
        }
        override suspend fun initialize(config: ChartEngineConfig) = EngineResult(true)
        override fun attachSurface(surface: android.view.Surface, width: Int, height: Int) = Unit
        override fun detachSurface() = Unit
        override fun setViewport(viewport: ChartViewport) { status.value = status.value.copy(viewport = viewport) }
        override fun setS52Settings(settings: S52Settings) = Unit
        override suspend fun queryFeature(point: ScreenPoint): List<ChartFeature> = emptyList()
        override fun setOwnship(state: OwnshipProjection) = Unit
        override fun setAisTargets(targets: List<AisProjection>) = Unit
        override fun setRouteProjection(route: RouteProjection?) = Unit
        override fun setWaypointProjection(waypoints: List<WaypointProjection>) = Unit
        override fun close() = Unit
    }
}
