package com.no5.helm

import com.no5.helm.chart.*
import com.no5.helm.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import java.util.ArrayDeque

class ResidentChartSessionTest {
    private lateinit var dispatcher: QueuedDispatcher
    private lateinit var scope: CoroutineScope
    private val sessions = mutableListOf<ResidentChartSession>()
    private fun catalog(): ChartPlanningCatalog {
        val cells = (0..2).map { index ->
            val name = "US5T%04d".format(index)
            ChartCell(name, 1, 0, "$name.000", "20261010", mapOf("$name.000" to "a".repeat(64)))
        }
        return ChartPlanningCatalog.fromPackage(ChartPackage("f".repeat(36), "session", 0, cells), cells.map { it.name }.toSet())
    }
    @Before fun setup() {
        dispatcher = QueuedDispatcher()
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }
    @After fun cleanup() {
        sessions.forEach { it.close() }; scope.cancel(); dispatcher.drain()
    }
    private fun session(engine: RecordingEngine, publish: (ChartResidentUpdate) -> Unit = {}) =
        ResidentChartSession(engine, engine.catalog, scope, publish = publish).also { sessions += it }

    @Test fun noSourceLoadOccursUntilARealSurfaceIsAttached() {
        val engine = RecordingEngine(catalog(), attached = false)
        session(engine); dispatcher.drain()
        assertEquals(0, engine.poseReads); assertEquals(0, engine.demands.size); assertEquals(0, engine.loads)
        engine.status.value = engine.status.value.copy(viewport = engine.status.value.viewport.copy(longitude = -71.01))
        dispatcher.drain(); assertEquals(0, engine.loads)
        engine.status.value = engine.status.value.copy(surfaceAttached = true, surfaceGeneration = 1)
        dispatcher.drain(); assertEquals(1, engine.loads)
    }

    @Test fun attachedButUnprovedCameraNeverStartsDemandOrLoading() {
        val engine = RecordingEngine(catalog())
        engine.status.value = engine.status.value.copy(residentCameraReady = false,
            residentStartState = ChartResidentStartState.UNKNOWN)
        session(engine); dispatcher.drain()
        assertEquals(0, engine.poseReads); assertTrue(engine.demands.isEmpty()); assertEquals(0, engine.loads)
        engine.status.value = engine.status.value.copy(residentCameraReady = true,
            residentStartState = ChartResidentStartState.READY)
        dispatcher.drain(); assertEquals(1, engine.loads)
    }

    @Test fun conflatedCameraWakesUseOnlyTheNewestPoseAndDoNotCreateCellJobsPerTouch() {
        val engine = RecordingEngine(catalog())
        val gate = CompletableDeferred<Unit>(); engine.loadGate = gate
        session(engine); dispatcher.drain(); assertEquals(1, engine.loads)
        for (index in 1..100) engine.status.value = engine.status.value.copy(
            viewport = engine.status.value.viewport.copy(longitude = -71.0 + index * .001))
        dispatcher.drain(); assertEquals(1, engine.loads)
        gate.complete(Unit); dispatcher.drain()
        // The initial load must get a full producer checkpoint before any new job.
        assertEquals(1, engine.loads); assertEquals(1, engine.demands.size)
        engine.fullCheckpoint(1); dispatcher.drain()
        assertEquals(2, engine.loads); assertEquals(2, engine.demands.size)
        assertEquals(-70.9, engine.demands.last().camera.longitude, 1e-12)
        assertEquals(2L, engine.demands.last().cameraRevision)
    }

    @Test fun anotherCellRequiresFullCheckpointAndEachCheckpointStartsAtMostOneJob() {
        val engine = RecordingEngine(catalog())
        session(engine); dispatcher.drain(); assertEquals(1, engine.loads)
        engine.status.value = engine.status.value.copy(presentationGeneration = 1)
        dispatcher.drain(); assertEquals(1, engine.loads) // Preview/presentation alone is insufficient.
        engine.fullCheckpoint(0); dispatcher.drain(); assertEquals(1, engine.loads)
        engine.fullCheckpoint(1); dispatcher.drain(); assertEquals(2, engine.loads)
        repeat(20) {
            engine.status.value = engine.status.value.copy(presentationGeneration = engine.status.value.presentationGeneration + 1)
            dispatcher.drain()
        }
        assertEquals(2, engine.loads)
        engine.fullCheckpoint(2); dispatcher.drain(); assertEquals(3, engine.loads)
    }

    @Test fun blockedPoseHasNoTimedRetryButAChangedCameraCanPlanAgain() {
        val engine = RecordingEngine(catalog()).apply { steps += ChartResidentStep.BLOCKED }
        session(engine); dispatcher.drain(); assertEquals(1, engine.loads)
        engine.fullCheckpoint(0); dispatcher.drain(); assertEquals(1, engine.loads)
        engine.status.value = engine.status.value.copy(surfaceGeneration = 1)
        dispatcher.drain(); assertEquals(1, engine.loads)
        engine.status.value = engine.status.value.copy(viewport = engine.status.value.viewport.copy(longitude = -70.99))
        dispatcher.drain()
        assertEquals(2, engine.loads)
        assertNotEquals(engine.demands.first().cameraKey, engine.demands.last().cameraKey)
        assertEquals(2L, engine.demands.last().cameraRevision)
    }

    @Test fun successfulAdmissionRevisionReplansBlockedSamePoseEvenWhenReadyTransitionsCoalesce() {
        val engine = RecordingEngine(catalog()).apply { steps += ChartResidentStep.BLOCKED }
        session(engine); dispatcher.drain()
        assertEquals(1, engine.loads); assertEquals(1, engine.demands.size)
        val blockedDemand = engine.demands.single()
        val pose = engine.status.value.viewport
        engine.status.value = engine.status.value.copy(residentCameraReady = false,
            residentStartState = ChartResidentStartState.BLOCKED)
        // Do not drain between these updates: the StateFlow observer can miss the false edge.
        engine.status.value = engine.status.value.copy(residentCameraReady = true,
            residentStartState = ChartResidentStartState.READY, residentCameraAdmissionRevision = 1)
        dispatcher.drain()
        assertEquals(pose, engine.status.value.viewport)
        assertEquals(2, engine.loads); assertEquals(2, engine.demands.size)
        assertEquals(blockedDemand.cameraKey, engine.demands.last().cameraKey)
        assertEquals(blockedDemand.cameraRevision, engine.demands.last().cameraRevision)
        repeat(10) { engine.fullCheckpoint(0); dispatcher.drain() }
        assertEquals(2, engine.loads); assertEquals(2, engine.demands.size)
    }

    @Test fun successfulAdmissionRevisionDiscardsCheckpointFromPreviousCameraAuthority() {
        val engine = RecordingEngine(catalog())
        session(engine); dispatcher.drain(); assertEquals(1, engine.loads)
        // The first geometry is still awaiting its full producer checkpoint.
        engine.status.value = engine.status.value.copy(residentCameraAdmissionRevision = 1)
        dispatcher.drain()
        assertEquals(2, engine.loads); assertEquals(2, engine.demands.size)
        assertEquals(engine.demands.first().cameraKey, engine.demands.last().cameraKey)
        engine.fullCheckpoint(1); dispatcher.drain()
        assertEquals(2, engine.loads) // New authority now awaits its own revision 2.
    }

    @Test fun admissionChangingDuringUsefulCellLoadCannotPublishOrKeepOldBlockedResult() {
        val engine = RecordingEngine(catalog()).apply { steps += ChartResidentStep.BLOCKED }
        val gate = CompletableDeferred<Unit>(); engine.loadGate = gate
        val published = mutableListOf<ChartResidentUpdate>()
        session(engine) { published += it }; dispatcher.drain(); assertEquals(1, engine.loads)
        engine.status.value = engine.status.value.copy(residentCameraAdmissionRevision = 1)
        dispatcher.drain(); assertEquals(1, engine.loads) // The existing cell work is allowed to finish.
        gate.complete(Unit); dispatcher.drain()
        assertEquals(2, engine.loads); assertEquals(2, engine.demands.size)
        assertFalse(published.any { it.step == ChartResidentStep.BLOCKED })
        assertEquals(1, published.count { it.step == ChartResidentStep.LOADED })
    }

    @Test fun sourceEpochSwitchLeavesOldSessionUnableToLoadOrPublishForNewSource() {
        val engine = RecordingEngine(catalog())
        val published = mutableListOf<ChartResidentUpdate>()
        session(engine) { published += it }; dispatcher.drain()
        val loads = engine.loads; val reports = published.size
        engine.status.value = engine.status.value.copy(chartSourceGeneration = 2)
        engine.fullCheckpoint(1)
        engine.status.value = engine.status.value.copy(viewport = engine.status.value.viewport.copy(longitude = -70.98))
        dispatcher.drain()
        assertEquals(loads, engine.loads); assertEquals(reports, published.size)
    }

    @Test fun sourceEpochSwitchDuringSuspendedPoseReadStopsBeforeDemandAndLoad() {
        val engine = RecordingEngine(catalog())
        val gate = CompletableDeferred<Unit>(); engine.poseGate = gate
        val published = mutableListOf<ChartResidentUpdate>()
        session(engine) { published += it }; dispatcher.drain(); assertEquals(1, engine.poseReads)
        engine.status.value = engine.status.value.copy(chartSourceGeneration = 2)
        dispatcher.drain(); gate.complete(Unit); dispatcher.drain()
        assertEquals(0, engine.demands.size); assertEquals(0, engine.loads); assertTrue(published.isEmpty())
    }

    @Test fun capturesBenchmarksAndRecoveryBlockJobsUntilTheirOwnerClearsTheGate() {
        val engine = RecordingEngine(catalog())
        engine.status.value = engine.status.value.copy(rendererCapture = RendererCaptureStatus(active = true))
        session(engine); dispatcher.drain(); assertEquals(0, engine.loads)
        engine.status.value = engine.status.value.copy(rendererCapture = RendererCaptureStatus(),
            rendererBenchmark = RendererBenchmarkStatus(active = true))
        dispatcher.drain(); assertEquals(0, engine.loads)
        engine.status.value = engine.status.value.copy(rendererBenchmark = RendererBenchmarkStatus(), rendererBenchmarkRecoveryPending = true)
        dispatcher.drain(); assertEquals(0, engine.loads)
        engine.status.value = engine.status.value.copy(rendererBenchmarkRecoveryPending = false)
        dispatcher.drain(); assertEquals(1, engine.loads)
    }

    @Test fun captureBeginningDuringPoseReadPreventsStartingTheCellJob() {
        val engine = RecordingEngine(catalog())
        val gate = CompletableDeferred<Unit>(); engine.poseGate = gate
        session(engine); dispatcher.drain()
        engine.status.value = engine.status.value.copy(rendererCapture = RendererCaptureStatus(active = true))
        dispatcher.drain(); gate.complete(Unit); dispatcher.drain()
        assertEquals(0, engine.demands.size); assertEquals(0, engine.loads)
    }

    @Test fun onlyTheSamplingPhaseOfResidentCapacityAllowsTheExistingPlannerToContinue() {
        val engine = RecordingEngine(catalog())
        engine.status.value = engine.status.value.copy(rendererBenchmark = RendererBenchmarkStatus(
            active = true, runId = "arm-1", runKind = RendererRunKind.RESIDENT_CAPACITY, phase = "preparing"))
        session(engine); dispatcher.drain(); assertEquals(0, engine.loads)
        engine.status.value = engine.status.value.copy(rendererBenchmark = engine.status.value.rendererBenchmark.copy(phase = "sampling"))
        dispatcher.drain(); assertEquals(1, engine.loads)
        engine.status.value = engine.status.value.copy(rendererBenchmarkRecoveryPending = true)
        engine.fullCheckpoint(1); dispatcher.drain(); assertEquals(1, engine.loads)
        engine.status.value = engine.status.value.copy(rendererBenchmarkRecoveryPending = false,
            rendererBenchmark = engine.status.value.rendererBenchmark.copy(phase = "restoring"))
        dispatcher.drain(); assertEquals(1, engine.loads)
        engine.status.value = engine.status.value.copy(rendererBenchmark = engine.status.value.rendererBenchmark.copy(
            runKind = RendererRunKind.LIVE_INPUT, phase = "sampling"))
        dispatcher.drain(); assertEquals(1, engine.loads)
    }

    @Test fun newCapacityLeaseReplansIdenticalPoseAfterBlockedArmOrEmptyGeometryReset() {
        val engine = RecordingEngine(catalog()).apply { steps += ChartResidentStep.BLOCKED }
        engine.status.value = engine.status.value.copy(rendererBenchmark = RendererBenchmarkStatus(
            active = true, runId = "arm-1", runKind = RendererRunKind.RESIDENT_CAPACITY, phase = "sampling"))
        session(engine); dispatcher.drain(); assertEquals(1, engine.loads)
        engine.fullCheckpoint(0); dispatcher.drain(); assertEquals(1, engine.loads)
        engine.status.value = engine.status.value.copy(rendererBenchmark = engine.status.value.rendererBenchmark.copy(runId = "arm-2"))
        dispatcher.drain(); assertEquals(2, engine.loads); assertEquals(2, engine.demands.size)
        // Source authority still dominates the measurement permission.
        engine.status.value = engine.status.value.copy(chartSourceGeneration = 2)
        engine.fullCheckpoint(1); dispatcher.drain(); assertEquals(2, engine.loads)
    }

    @Test fun capacityLeaseChangingDuringPoseReadCannotSubmitTheOldRunAdmission() {
        val engine = RecordingEngine(catalog())
        val gate = CompletableDeferred<Unit>(); engine.poseGate = gate
        engine.status.value = engine.status.value.copy(rendererBenchmark = RendererBenchmarkStatus(
            active = true, runId = "arm-1", runKind = RendererRunKind.RESIDENT_CAPACITY, phase = "sampling"))
        session(engine); dispatcher.drain(); assertEquals(1, engine.poseReads)
        engine.status.value = engine.status.value.copy(rendererBenchmark = engine.status.value.rendererBenchmark.copy(runId = "arm-2"))
        // The next wake also suspends, so the old work's rejection can be inspected independently.
        engine.poseGate = CompletableDeferred()
        gate.complete(Unit); dispatcher.drain()
        assertTrue(engine.demands.isEmpty()); assertEquals(0, engine.loads)
    }

    @Test fun closingTheSessionCancelsPendingWorkAndRejectsFurtherWakes() {
        val engine = RecordingEngine(catalog())
        val gate = CompletableDeferred<Unit>(); engine.poseGate = gate
        val value = session(engine); dispatcher.drain(); assertEquals(1, engine.poseReads)
        value.close(); dispatcher.drain()
        gate.complete(Unit)
        engine.status.value = engine.status.value.copy(surfaceGeneration = 1)
        dispatcher.drain(); assertEquals(0, engine.loads); assertTrue(engine.demands.isEmpty())
    }

    private fun knownCatalog(): ChartPlanningCatalog {
        val bounds=ChartSelectionExtent(42.0,43.0,-72.0,-70.0)
        val ring=listOf(ChartCellCoordinate(42.0,-72.0),ChartCellCoordinate(42.0,-70.0),ChartCellCoordinate(43.0,-70.0),ChartCellCoordinate(43.0,-72.0))
        val cells=listOf(12000,350000).mapIndexed { index,scale ->
            val name="known$index";val raw=ChartCell(name,1,0,"$name.000","20261010",mapOf("$name.000" to "a".repeat(64)),selectionExtent=bounds)
            val metadata=ValidatedChartCell(raw.base,1,"no5-s57-osenc201-metadata-v1","b".repeat(64),"c".repeat(64),1,scale,5,
                ChartCellBounds(42.0,-72.0,43.0,-70.0),listOf(ring),emptyList(),ChartCoverageCapabilities())
            raw.copy(catalogMetadata=ChartCatalogMetadata(raw.name,raw.files,ChartMetadataContract.sourceKey(raw),index,
                metadata.metadataVersion,metadata.parserVersion,metadata.parserIdentity,metadata.preparedSourceKey,metadata.coverageVersion,
                metadata.nativeScale,metadata.usage,bounds,bounds,
                listOf(ring.map { CatalogCoordinate(it.latitude,it.longitude) }),emptyList(),
                metadata.capabilities.let { CatalogCoverageCapabilities(it.exteriorRingsOnly,it.polygonHoles,it.multiPolygons,
                    it.wrappedDateline,it.latitudeLimit,it.coordinatePrecision) }))
        }
        return ChartPlanningCatalog.fromPackage(ChartPackage("f".repeat(36),"known",0,cells),cells.map { it.name }.toSet())
    }

    @Test fun cameraScaleAndSurfaceChangesBeforeWorkerReturnNeverSubmitOldAdmission() {
        for(change in listOf("camera","scale","surface","source","admission")) {
            val worker=QueuedDispatcher();val engine=RecordingEngine(knownCatalog())
            engine.status.value=engine.status.value.copy(chartScaleDenominator=15000.0)
            val value=ResidentChartSession(engine,engine.catalog,scope,worker) {};sessions+=value
            dispatcher.drain();assertEquals(0,engine.demands.size)
            worker.drain() // Calculation finished, owner continuation is still queued.
            engine.status.value=when(change) {
                "camera"->engine.status.value.copy(viewport=engine.status.value.viewport.copy(longitude=-70.99))
                "scale"->engine.status.value.copy(chartScaleDenominator=300000.0)
                "surface"->engine.status.value.copy(surfaceGeneration=1)
                "admission"->engine.status.value.copy(residentCameraAdmissionRevision=1)
                else->engine.status.value.copy(chartSourceGeneration=2)
            }
            dispatcher.drain();assertTrue("Old $change admission was submitted",engine.demands.isEmpty())
            worker.drain();dispatcher.drain()
            if(change=="source")assertEquals(0,engine.loads) else {
                assertEquals(1,engine.loads);assertEquals(1,engine.demands.size)
                assertEquals(ChartSelectionExtent.normalizeLongitude(engine.status.value.viewport.longitude),engine.demands.single().camera.longitude,0.0)
            }
            value.close();dispatcher.drain()
        }
    }

    @Test fun obsoleteInProgressCoverageAndClosedSessionDoNotCancelUsefulCellJobs() {
        val worker=QueuedDispatcher();val engine=RecordingEngine(knownCatalog())
        engine.status.value=engine.status.value.copy(chartScaleDenominator=15000.0)
        val value=ResidentChartSession(engine,engine.catalog,scope,worker) {};sessions+=value
        dispatcher.drain();engine.status.value=engine.status.value.copy(viewport=engine.status.value.viewport.copy(longitude=-70.99))
        worker.drain();dispatcher.drain();assertTrue(engine.demands.isEmpty())
        worker.drain();dispatcher.drain();assertEquals(1,engine.loads)
        engine.fullCheckpoint(1);dispatcher.drain();value.close();worker.drain();dispatcher.drain()
        assertEquals(1,engine.loads)
    }

    /** A single deterministic owner queue, without coroutines-test or sleeps. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun drain() {
            var remaining = 10000
            while (queue.isNotEmpty()) {
                check(remaining-- > 0) { "Session generated unbounded immediate work" }
                queue.removeFirst().run()
            }
        }
    }

    private class RecordingEngine(val catalog: ChartPlanningCatalog, attached: Boolean = true) : ChartEngine {
        override val status = MutableStateFlow(ChartEngineStatus(initialized = true, residentCatalogActive = true,
            chartSourceGeneration = 1, surfaceAttached = attached,
            viewport = ChartViewport(42.36, -71.0, .15, .63, 2000, 1070)))
        val demands = mutableListOf<ChartResidentDemand>()
        val steps = ArrayDeque<ChartResidentStep>()
        var poseReads = 0
        var loads = 0
        var contentRevision = 0L
        var poseGate: CompletableDeferred<Unit>? = null
        var loadGate: CompletableDeferred<Unit>? = null
        private fun residency() = ChartResidencyStatus(enabled = true, complete = false,
            catalogGeneration = catalog.generation, cameraKey = demands.lastOrNull()?.cameraKey ?: "",
            policyKey = demands.lastOrNull()?.policyKey ?: "", cameraRevision = demands.lastOrNull()?.cameraRevision ?: 0,
            contentRevision = contentRevision, resident = 0, loading = 0, pinned = 0, required = catalog.cells.size,
            missing = catalog.cells.size, chargedBytes = 0, geometryBytes = 0, loadingBytes = 0, peakCells = 0,
            peakChargedBytes = 0, loads = loads.toLong(), retains = 0, evictions = 0, failures = 0, message = "", sources = emptyList())
        override suspend fun readResidentPlanningViewport(): ChartViewport {
            poseReads++; poseGate?.await(); return status.value.viewport
        }
        override suspend fun readResidency() = ChartResidentUpdate(EngineResult(true), residency())
        override suspend fun submitResidentDemand(demand: ChartResidentDemand): ChartResidentUpdate {
            demands += demand; return ChartResidentUpdate(EngineResult(true), residency())
        }
        override suspend fun advanceResidentLoading(): ChartResidentUpdate {
            loads++; loadGate?.await()
            val step = if (steps.isEmpty()) ChartResidentStep.LOADED else steps.removeFirst()
            if (step == ChartResidentStep.LOADED) contentRevision++
            return ChartResidentUpdate(EngineResult(true), residency(), step)
        }
        fun fullCheckpoint(revision: Long) { status.value = status.value.copy(residentCheckpointRevision = revision,
            presentationGeneration = status.value.presentationGeneration + 1) }
        override suspend fun initialize(config: ChartEngineConfig) = EngineResult(true)
        override fun attachSurface(surface: android.view.Surface, width: Int, height: Int) = Unit
        override fun detachSurface() = Unit
        override fun setViewport(viewport: ChartViewport) { status.value = status.value.copy(viewport = viewport) }
        override fun setS52Settings(settings: S52Settings) = Unit
        override suspend fun loadChartSet(id: String, paths: List<String>) = EngineResult(true)
        override suspend fun queryFeature(point: ScreenPoint): List<ChartFeature> = emptyList()
        override fun setOwnship(state: OwnshipProjection) = Unit
        override fun setAisTargets(targets: List<AisProjection>) = Unit
        override fun setRouteProjection(route: RouteProjection?) = Unit
        override fun setWaypointProjection(waypoints: List<WaypointProjection>) = Unit
        override fun close() = Unit
    }
}
