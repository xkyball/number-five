package com.no5.helm.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.no5.helm.chart.ChartControlResult
import com.no5.helm.chart.ChartEngine
import com.no5.helm.model.MapPositionController
import org.lumina.control.commands.v1.CommandRing

/** Bounded intent receipts for the shared touch/knob positioning cycle.
 * OFF is an actual intent completion, not a fabricated camera command. Camera receipts remain
 * owned by the engine and are correlated separately. No camera or preference work in admission.
 */
internal class MapPositionControls(
    private val positions: MapPositionController,
    private val engine: ChartEngine,
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val post: ((() -> Unit) -> Unit)? = null,
) : AutoCloseable {
    companion object { const val PREFIX = 1L shl 60 }
    private val lock = Any()
    private val ring = CommandRing()
    private val work = CommandRing.Entry()
    private val receipt = CommandRing.Entry()
    private val admission = CommandRing.Entry()
    // Exact Long fences for the at-most-64 queued actions; ID tags prevent slot reuse aliases.
    private val pendingIds = LongArray(64)
    private val pendingEpochs = LongArray(64)
    private val pendingSources = LongArray(64)
    private val cameraReceipt = ChartControlResult()
    private val resultLock = Any()
    private val ids = LongArray(256)
    private val cameraIds = LongArray(256)
    private val handler = if (post == null) Handler(Looper.getMainLooper()) else null
    private var epoch = 0L
    private var positionGeneration = positions.state.value.generation
    private var chartSource = engine.status.value.chartSourceGeneration
    private var surface = engine.status.value.surfaceGeneration
    private var revision = 0L
    private var queued = false
    private var draining = false
    private var closed = false
    private val drainTask = Runnable { drain() }

    fun observe() = synchronized(lock) {
        val next = positions.state.value.generation
        val state = engine.status.value
        if (next != positionGeneration || state.chartSourceGeneration != chartSource || state.surfaceGeneration != surface) {
            epoch++; ring.cancelPending(CommandRing.Reason.SOURCE_CHANGED, clock()); pendingIds.fill(0)
            positionGeneration = next; chartSource = state.chartSourceGeneration; surface = state.surfaceGeneration
        }
    }
    fun cycle(sequence: Long = 0, physicalUptimeMs: Long = clock()): Long {
        var schedule = false
        val id = synchronized(lock) {
            val now = clock()
            if (closed || sequence < 0 || physicalUptimeMs < 0 || physicalUptimeMs > now || now - physicalUptimeMs > 250) return 0
            val slot = pendingIds.indexOf(0L)
            val accepted = ring.submit(-1, 0, sequence, positionGeneration, surface, (physicalUptimeMs + 2000).toDouble(),
                physicalUptimeMs.toDouble(), 0.0, 0.0, now)
            if (ring.read(accepted, admission) && admission.state == CommandRing.State.ACCEPTED) {
                check(slot >= 0) { "Position ring metadata accounting" }
                pendingIds[slot] = accepted; pendingEpochs[slot] = epoch; pendingSources[slot] = chartSource
            }
            if (!queued) { queued = true; schedule = true }
            PREFIX + accepted
        }
        if (schedule) {
            if (post != null) post.invoke { drain() } else if (!handler!!.post(drainTask)) synchronized(lock) {
                queued = false; ring.cancelPending(CommandRing.Reason.CLOSED, clock()); pendingIds.fill(0)
            }
        }
        return id
    }
    fun drain() {
        synchronized(lock) { if (closed || draining) return; draining = true; queued = false }
        try {
            while (true) {
                var effectEpoch = -1L
                var effectSource = -1L
                val step = synchronized(lock) {
                    if (!ring.take(work)) return@synchronized 0
                    val slot = pendingIds.indexOf(work.id)
                    if (slot >= 0) {
                        effectEpoch = pendingEpochs[slot]; effectSource = pendingSources[slot]; pendingIds[slot] = 0
                    }
                    val state = engine.status.value
                    if (closed || slot < 0 || effectEpoch != epoch || work.generation != positions.state.value.generation ||
                        effectSource != state.chartSourceGeneration || work.surface != state.surfaceGeneration || clock() >= work.a) {
                        val reason=when {closed->CommandRing.Reason.CLOSED;effectEpoch!=epoch->CommandRing.Reason.FOCUS_CHANGED
                            clock()>=work.a->CommandRing.Reason.UNAVAILABLE;else->CommandRing.Reason.SOURCE_CHANGED}
                        ring.finish(work.id, CommandRing.State.CANCELLED, reason, -1, clock())
                        1
                    } else if (ring.beginEffect(work.id)) 2 else 1
                }
                if (step == 0) break
                if (step != 2) continue
                try {
                    val camera = positions.cyclePosition(wallClock(), work.sequence, work.b.toLong(), work.generation, effectSource, work.surface)
                    val slot = (work.id % ids.size).toInt()
                    synchronized(resultLock) { ids[slot] = work.id; cameraIds[slot] = camera }
                    val uncertain=synchronized(lock){closed||effectEpoch!=epoch}
                    ring.finish(work.id, if(uncertain)CommandRing.State.UNKNOWN else if (camera == 0L) CommandRing.State.REJECTED else CommandRing.State.APPLIED,
                        if(uncertain)CommandRing.Reason.COMPLETION_UNKNOWN else if (camera == 0L) CommandRing.Reason.UNAVAILABLE else CommandRing.Reason.NONE, ++revision, clock())
                } catch (_: Exception) {
                    // State/camera admission may have begun: never replay or invite another audio action.
                    ring.finish(work.id, CommandRing.State.UNKNOWN, CommandRing.Reason.COMPLETION_UNKNOWN, -1, clock())
                }
                observe()
            }
        } finally { synchronized(lock) { draining = false } }
    }
    fun result(id: Long, destination: ChartControlResult): Boolean = synchronized(resultLock) {
        if (id <= PREFIX || !ring.read(id - PREFIX, receipt)) return false
        destination.id = id; destination.sequence = receipt.sequence; destination.replacementId = 0
        destination.state = receipt.state.ordinal; destination.reason = receipt.reason.name
        destination.revision = receipt.revision; destination.appliedRevision = -1; destination.presentedRevision = -1
        destination.desiredScale = Double.NaN; destination.actualScale = Double.NaN
        val slot = (receipt.id % ids.size).toInt()
        val camera = if (ids[slot] == receipt.id) cameraIds[slot] else 0
        if (receipt.state == CommandRing.State.APPLIED && camera > 0) {
            if (!engine.cameraControlResult(camera, cameraReceipt) || cameraReceipt.state == CommandRing.State.UNKNOWN.ordinal ||
                cameraReceipt.state == CommandRing.State.CANCELLED.ordinal || cameraReceipt.state == CommandRing.State.REJECTED.ordinal) {
                destination.state = CommandRing.State.UNKNOWN.ordinal
                destination.reason = CommandRing.Reason.COMPLETION_UNKNOWN.name
            } else {
                destination.revision = cameraReceipt.revision
                destination.appliedRevision = cameraReceipt.appliedRevision
                destination.presentedRevision = cameraReceipt.presentedRevision
                destination.desiredScale = cameraReceipt.desiredScale; destination.actualScale = cameraReceipt.actualScale
            }
        }
        true
    }
    /** OFF completed in the intent owner and deliberately has no camera command. */
    fun intentOnly(id:Long):Boolean = synchronized(resultLock) {
        if(id<=PREFIX)return false
        val local=id-PREFIX;val slot=(local%ids.size).toInt()
        ids[slot]==local&&cameraIds[slot]==-1L
    }
    fun cancel() {
        synchronized(lock) { epoch++; ring.cancelPending(CommandRing.Reason.FOCUS_CHANGED, clock()); pendingIds.fill(0) }
        engine.cancelCameraControl()
    }
    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; epoch++; ring.cancelPending(CommandRing.Reason.CLOSED, clock()); pendingIds.fill(0) }
        handler?.removeCallbacks(drainTask)
    }
}
