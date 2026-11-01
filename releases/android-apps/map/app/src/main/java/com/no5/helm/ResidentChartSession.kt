package com.no5.helm

import com.no5.helm.chart.*
import com.no5.helm.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect

/** One catalog/one planner. Camera wakes coalesce; native cell work stays on the engine owner. */
internal class ResidentChartSession(
    private val engine: ChartEngine,
    private val catalog: ChartPlanningCatalog,
    parent: CoroutineScope,
    private val planningDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val experimentalPacketSelector: ExperimentalCoveragePacketSelector? = null,
    private val publish: (ChartResidentUpdate) -> Unit,
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val wakes = Channel<Unit>(Channel.CONFLATED)
    // One optional source avoids cyclic replacement when prefetch itself exceeds the quota.
    private val planner = ChartViewportPlanner(catalog, IndexedChartCellIndex(catalog),
        policy = ChartPlanPolicy(prefetchPixels = 96.0, maxPrefetchCells = 1))
    private val coverage = ChartCoveragePlanner(catalog)
    private val bySource = catalog.cells.associateBy { it.key.sourceKey }
    private data class PacketShadow(val viewport: ChartViewport, val source: Long, val surface: Long,
        val admission: Long, val presentation: Long, val buffer: Long, val scale: Double?, val run: String?,
        val proposal: ExperimentalCoveragePacketProposal)
    @Volatile private var packetShadow: PacketShadow? = null
    internal val shadowPacketProposal: ExperimentalCoveragePacketProposal? get() {
        val s = packetShadow ?: return null
        val current = engine.status.value
        return s.proposal.takeIf { job.isActive && eligible() && current.viewport == s.viewport &&
            current.chartSourceGeneration == s.source && current.surfaceGeneration == s.surface &&
            current.residentCameraAdmissionRevision == s.admission && current.presentationGeneration == s.presentation &&
            current.bufferTransformGeneration == s.buffer && current.chartScaleDenominator == s.scale &&
            current.rendererBenchmark.runId == s.run }
    }
    private var lastDemand: ChartResidentDemand? = null
    private var checkpoint: Long? = null
    private var stoppedPose: String? = null
    private var capacityRunId: String? = null
    private var cameraAdmissionRevision: Long? = null
    private val sourceGeneration = engine.status.value.chartSourceGeneration
    private class ObsoletePlanning : RuntimeException()

    private fun eligible(): Boolean = engine.status.value.let {
        // Native owner additionally checks this run's frozen catalog/source/budget lease.
        val measurementAllowsPlanning = !it.rendererBenchmark.active ||
            (it.rendererBenchmark.runKind == RendererRunKind.RESIDENT_CAPACITY &&
                it.rendererBenchmark.phase == "sampling")
        it.surfaceAttached && it.residentCameraReady && it.residentCatalogActive && it.chartSourceGeneration == sourceGeneration &&
            !it.rendererCapture.active && measurementAllowsPlanning && !it.rendererBenchmarkRecoveryPending
    }

    private data class Wake(val viewport: ChartViewport, val scale: Double?, val surface: Long, val attached: Boolean,
        val source: Long, val resident: Boolean, val cameraReady: Boolean, val cameraAdmissionRevision: Long,
        val checkpoint: Long, val presentation: Long,
        val capture: Boolean, val benchmark: Boolean, val benchmarkKind: RendererRunKind,
        val benchmarkPhase: String, val benchmarkRunId: String, val recovery: Boolean)

    init {
        scope.launch {
            engine.status.map { Wake(it.viewport, it.chartScaleDenominator, it.surfaceGeneration, it.surfaceAttached,
                it.chartSourceGeneration, it.residentCatalogActive, it.residentCameraReady, it.residentCameraAdmissionRevision,
                it.residentCheckpointRevision,
                it.presentationGeneration, it.rendererCapture.active, it.rendererBenchmark.active,
                it.rendererBenchmark.runKind, it.rendererBenchmark.phase, it.rendererBenchmark.runId,
                it.rendererBenchmarkRecoveryPending) }.distinctUntilChanged().collect { wakes.trySend(Unit) }
        }
        scope.launch {
            for (wake in wakes) {
                try { advance() }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) {
                    if (eligible()) publish(ChartResidentUpdate(EngineResult(false, failure.message ?: "Karten konnten nicht nachgeladen werden")))
                    // No timed retry loop. A new camera/surface/owner event is required.
                }
            }
        }
    }

    private suspend fun advance() {
        packetShadow = null // New/unknown authority cannot expose an earlier proposal.
        val owner = engine.status.value
        if (!eligible()) return
        if (owner.residentCameraAdmissionRevision != cameraAdmissionRevision) {
            // A successful start/resize/budget revalidation can admit the same pose again.
            // The observer may coalesce Ready false/true, so the owner's revision is the wake authority.
            lastDemand = null; checkpoint = null; stoppedPose = null
            cameraAdmissionRevision = owner.residentCameraAdmissionRevision
        }
        val currentCapacityRun = owner.rendererBenchmark.takeIf {
            it.active && it.runKind == RendererRunKind.RESIDENT_CAPACITY && it.phase == "sampling"
        }?.runId
        if (currentCapacityRun != capacityRunId) {
            // Budget resets can clear native demand while leaving this camera/source unchanged.
            // A previous BLOCKED result must not prevent a new arm from measuring this pose.
            lastDemand = null; checkpoint = null; stoppedPose = null
            capacityRunId = currentCapacityRun
        }
        val awaiting = checkpoint
        if (awaiting != null && owner.residentCheckpointRevision < awaiting) return
        checkpoint = null
        fun sameMeasurementOwner() = engine.status.value.rendererBenchmark.let {
            it.runId == owner.rendererBenchmark.runId && it.active == owner.rendererBenchmark.active
        }
        fun sameAdmissionOwner() = engine.status.value.residentCameraAdmissionRevision == owner.residentCameraAdmissionRevision
        fun stillCurrent() = eligible() && engine.status.value.let {
            it.viewport == owner.viewport && it.surfaceGeneration == owner.surfaceGeneration &&
                it.chartScaleDenominator == owner.chartScaleDenominator && sameMeasurementOwner() && sameAdmissionOwner()
        }
        val pose = engine.readResidentPlanningViewport() ?: return
        if (!stillCurrent()) return
        val current = engine.readResidency()
        if (!stillCurrent()) return
        val status = current.status ?: return
        if (!status.enabled || status.catalogGeneration != catalog.generation) return
        val resident = status.sources.mapNotNull { bySource[it]?.key }.toSet()
        val plan = planner.plan(ChartPlanCamera(pose.latitude, pose.longitude, pose.metresToPixels,
            pose.rotationRadians, pose.width, pose.height), resident)
        val scale = owner.chartScaleDenominator
        val selectionPose = plan.tag.cameraKey + ":" + scale
        if (stoppedPose == selectionPose) return
        val context = currentCoroutineContext()
        val admitted = try {
            if (plan.metadataRequired.isNotEmpty() || scale == null) coverage.admit(plan, scale)
            else withContext(planningDispatcher) {
                // SHADOW ONLY: below, the unchanged Original admission submits demand.
                experimentalPacketSelector?.let { selector ->
                    val proposal = selector.propose(plan, scale) {
                        context.ensureActive()
                        if (!stillCurrent()) throw ObsoletePlanning()
                    }
                    context.ensureActive()
                    if (!stillCurrent()) throw ObsoletePlanning()
                    packetShadow = PacketShadow(owner.viewport, owner.chartSourceGeneration, owner.surfaceGeneration,
                        owner.residentCameraAdmissionRevision, owner.presentationGeneration, owner.bufferTransformGeneration,
                        owner.chartScaleDenominator, owner.rendererBenchmark.runId, proposal)
                }
                coverage.admit(plan, scale) {
                    context.ensureActive()
                    if (!stillCurrent()) throw ObsoletePlanning()
                }
            }
        } catch (_: ObsoletePlanning) { return }
        if (!stillCurrent()) return
        val original = ChartResidentAdapter.demand(admitted.plan)
        val demand = original.copy(required = if (admitted.proven) admitted.loadOrder.map { it.sourceKey }
            else ResidentLoadPriority.order(original.required, catalog, scale))
        if (demand != lastDemand) {
            val submitted = engine.submitResidentDemand(demand)
            if (!eligible() || !sameMeasurementOwner() || !sameAdmissionOwner()) return
            publish(submitted)
            if (!submitted.result.success) return // A newer actual camera will wake the actor.
            lastDemand = demand
        }
        currentCoroutineContext().ensureActive()
        if (!eligible() || !sameMeasurementOwner() || !sameAdmissionOwner()) return
        val update = engine.advanceResidentLoading()
        currentCoroutineContext().ensureActive()
        if (!eligible() || !sameMeasurementOwner() || !sameAdmissionOwner()) return
        publish(update)
        when (update.step) {
            ChartResidentStep.LOADED -> checkpoint = update.status?.contentRevision
            ChartResidentStep.BLOCKED, ChartResidentStep.FAILED, ChartResidentStep.CANCELLED -> stoppedPose = selectionPose
            else -> Unit
        }
        // No self-wake or delay loop: another cell needs a FULL producer checkpoint.
    }

    suspend fun stop() { packetShadow = null; job.cancelAndJoin(); wakes.close() }
    fun close() { packetShadow = null; job.cancel(); wakes.close() }
}
