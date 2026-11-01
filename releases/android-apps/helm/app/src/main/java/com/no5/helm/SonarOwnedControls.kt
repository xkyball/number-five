package com.no5.helm

import android.os.Handler
import android.os.Looper
import android.os.Parcel
import com.no5.domain.*
import org.lumina.control.commands.v1.CommandRing
import com.no5.header.artwork.No5MomentFeedback
import kotlin.math.pow

/** SONAR presentation controls, shared by touch, dashboard and physical input. No source writes. */
internal class SonarOwnedControls(private val owner: SonarNativeController,
                                  private val uptime: () -> Long = { System.nanoTime() / 1_000_000 }) : AutoCloseable {
    private val ring = CommandRing()
    private val lock = Any()
    private val work = CommandRing.Entry()
    private val receipt = CommandRing.Entry()
    private val admission = CommandRing.Entry()
    private val diagnostics = LongArray(64)
    private var diagnosticHead = 0
    private var diagnosticCount = 0
    private var closed = false
    private var queued = false
    private var draining = false
    private var running = false
    private var generation = 0L
    private var sourceGeneration = owner.sourceStatus.generation
    private var snapshot = owner.snapshot
    private var spanGoal = Double.NaN
    private var spanGoalKind = RANGE
    // Published by the UI owner; admission never queries model geometry.
    private var metersPerBin = Double.NaN
    private var gainGoal = Double.NaN
    private var handler: Handler? = null
    private val drainTask = Runnable { drain() }
    private val retireFeedback = Runnable {
        val epoch=synchronized(lock){generation}
        No5MomentFeedback.invalidate(this,epoch)
    }
    fun prepareBindings() { if (handler == null) handler = Handler(Looper.getMainLooper()) }
    fun observe() = synchronized(lock) {
        val next = owner.sourceStatus.generation
        if (next != sourceGeneration) { sourceGeneration = next; cancelLocked(CommandRing.Reason.SOURCE_CHANGED) }
        snapshot = owner.snapshot
        metersPerBin = owner.model.viewMetersPerBin ?: Double.NaN
        if (!running && ring.pending() == 0) resetGoals()
    }
    fun adjustRange(steps: Int, sequence: Long = 0, deferred: Boolean = false): Long = synchronized(lock) {
        if (!availableDisplay(deferred)) return 0
        val kind = if (metersPerBin.isFinite()) METERS else RANGE
        val unit = if (kind == METERS) metersPerBin else 1.0
        val start = snapshot.display.startDepthM
        if (start > 0.0 && (kind != METERS || start >= 1023.0 * unit)) return 0
        val minimumEnd = if (start > 0.0) minOf(1023.0 * unit, start + 32.0 * unit) else 32.0 * unit
        val span = if (!spanGoal.isNaN() && spanGoalKind == kind) spanGoal
            else maxOf(minimumEnd, (snapshot.display.cropEnd - snapshot.display.cropStart) * unit)
        val target = (span * .8.pow(steps.toDouble())).coerceIn(minimumEnd, 1023.0 * unit)
        val id = enqueue(kind, target, 0.0, sequence, deferred)
        if (pending(id)) { spanGoal = target; spanGoalKind = kind }
        id
    }
    fun adjustGain(steps: Int, sequence: Long = 0, deferred: Boolean = false): Long = gainDelta(steps.toDouble(), sequence, deferred)
    fun gainDelta(delta: Double, sequence: Long = 0, deferred: Boolean = false): Long = synchronized(lock) {
        if (!delta.isFinite() || !availableDisplay(deferred)) return 0
        val baseline = if (gainGoal.isNaN()) (if(snapshot.display.gainMode==SonarImageMode.AUTO)snapshot.display.autoGainOffsetDb else snapshot.display.gainDb) else gainGoal
        gain((baseline + delta).coerceIn(-24.0, 36.0), sequence, deferred)
    }
    fun gain(value: Double, sequence: Long = 0, deferred: Boolean = false): Long = synchronized(lock) {
        if (!value.isFinite() || !availableDisplay(deferred)) return 0
        val auto=snapshot.display.gainMode==SonarImageMode.AUTO
        val target = value.coerceIn(-24.0, if(auto)24.0 else 36.0)
        // Bind offset/manual meaning at admission, before a later mode change.
        val id = enqueue(if(auto)GAIN_OFFSET else GAIN, target, 0.0, sequence, deferred)
        if (pending(id)) gainGoal = target
        id
    }
    fun range(meters: Double): Long = synchronized(lock) {
        if (!meters.isFinite() || meters <= snapshot.display.startDepthM) return 0
        val id = enqueue(METERS, meters, 0.0, 0, false)
        if (pending(id)) { spanGoal = meters; spanGoalKind = METERS }
        id
    }
    fun crop(start: Double, end: Double): Long = synchronized(lock) {
        if (!start.isFinite() || !end.isFinite()) return 0
        // Bind the unit before effect: a later capture change cannot reinterpret these bins.
        if (start == 0.0 && end > 0.0 && metersPerBin.isFinite()) {
            val target = end.coerceIn(1.0, 1023.0) * metersPerBin
            val id = enqueue(METERS, target, 0.0, 0, false)
            if (pending(id)) { spanGoal = target; spanGoalKind = METERS }
            id
        } else {
            val id = enqueue(CROP, start, end, 0, false)
            if (pending(id)) spanGoal = Double.NaN
            id
        }
    }
    fun freeze(sequence: Long = 0, deferred: Boolean = false) = synchronized(lock) { enqueue(FREEZE, 0.0, 0.0, sequence, deferred) }
    fun aScope(sequence: Long = 0, deferred: Boolean = false) = synchronized(lock) { enqueue(A_SCOPE, 0.0, 0.0, sequence, deferred) }
    fun mode(sequence: Long = 0, deferred: Boolean = false):Long {
        synchronized(lock){if(closed)return 0}
        // Do not invoke the routing owner while holding this ring's admission lock.
        // A simultaneous Binder admission takes the routing lock before entering a ring.
        owner.beginKnobModeChange()
        return synchronized(lock){enqueue(MODE,0.0,0.0,sequence,deferred)}
    }
    fun open(sequence: Long = 0, deferred: Boolean = false) = synchronized(lock) { enqueue(OPEN, 0.0, 0.0, sequence, deferred) }
    private fun availableDisplay(deferred: Boolean) = !closed && (!deferred || snapshot.connected && snapshot.ringCount > 0)
    private fun enqueue(kind: Int, a: Double, b: Double, sequence: Long, deferred: Boolean): Long {
        if (closed) return 0
        val now = uptime()
        val key = when (kind) { GAIN, GAIN_OFFSET -> GAIN; RANGE, METERS, CROP -> RANGE; else -> -1 }
        val id = ring.submit(key, kind, sequence, generation, sourceGeneration, a, b,
            if (deferred) 1.0 else 0.0, now + 2_000.0, now)
        diagnostics[diagnosticHead] = id; diagnosticHead = (diagnosticHead + 1) % diagnostics.size
        if (diagnosticCount < diagnostics.size) diagnosticCount++
        if (deferred) {
            if (!queued) { queued = true; handler?.post(drainTask) ?: run { queued = false; ring.cancelPending(CommandRing.Reason.CLOSED, now) } }
        }
        return id
    }
    fun execute(id: Long) { if (id > 0) drain() }
    private fun pending(id: Long) = ring.read(id, admission) &&
        (admission.state == CommandRing.State.ACCEPTED || admission.state == CommandRing.State.RUNNING)
    private fun drain() {
        synchronized(lock) { if (draining || closed) return; draining = true; queued = false }
        try {
            while (ring.take(work)) {
                val allowed = synchronized(lock) {
                    running = true
                    if (closed || work.generation != generation || work.surface != sourceGeneration ||
                        work.surface != owner.sourceStatus.generation || work.c != 0.0 && uptime() >= work.d) {
                        ring.finish(work.id, CommandRing.State.CANCELLED, CommandRing.Reason.SOURCE_CHANGED, -1, uptime())
                        false
                    } else ring.beginEffect(work.id)
                }
                if (allowed) {
                    // All model effects run on the UI owner; costly freeze copies never hold admission's lock.
                    val before = owner.snapshot.display
                    val ticket=if(work.c!=0.0 && work.kind in FREEZE..OPEN) No5MomentFeedback.begin(this,
                        when(work.kind) { FREEZE -> "Freeze"; A_SCOPE -> "A-Scope"; MODE -> "Knopfmodus"; else -> "Bild" },work.generation) else 0L
                    try {
                        val applied = when (work.kind) {
                            GAIN -> if(before.gainMode==SonarImageMode.MANUAL)owner.applyManualImage(SonarImageField.GAIN, work.a)else false
                            GAIN_OFFSET -> owner.applyGainOffset(work.a)
                            RANGE -> owner.applyBinCrop(0.0, work.a)
                            METERS -> owner.applyRange(work.a)
                            CROP -> owner.applyBinCrop(work.a, work.b)
                            FREEZE -> owner.applyFreeze()
                            A_SCOPE -> owner.applyDisplay { it.copy(aScope = !it.aScope) }
                            MODE -> { owner.applyKnobMode(); true }
                            else -> { owner.open(); true }
                        }
                        val boundary = work.kind in GAIN..CROP && before == owner.snapshot.display
                        ring.finish(work.id, if (applied) CommandRing.State.APPLIED else CommandRing.State.REJECTED,
                            if (!applied) CommandRing.Reason.UNAVAILABLE else if (boundary) CommandRing.Reason.BOUND_REACHED else CommandRing.Reason.NONE,
                            owner.displayRevision, uptime())
                        if(ticket!=0L) No5MomentFeedback.complete(ticket,
                            if(applied)No5MomentFeedback.Phase.EXECUTED else No5MomentFeedback.Phase.FAILED)
                    } catch (_: Exception) {
                        ring.finish(work.id, CommandRing.State.UNKNOWN, CommandRing.Reason.COMPLETION_UNKNOWN, -1, uptime())
                        if(ticket!=0L) No5MomentFeedback.complete(ticket,No5MomentFeedback.Phase.UNKNOWN)
                    }
                }
                if (handler != null && ring.read(work.id, work)) android.util.Log.i("No5SonarKnob",
                    "seq=${work.sequence} id=${work.id} state=${work.state} reason=${work.reason} waitMs=${uptime() - work.acceptedMs} revision=${owner.displayRevision} gc=${android.os.Debug.getRuntimeStat("art.gc.gc-count")}")
                synchronized(lock) { running = false; snapshot = owner.snapshot }
            }
        } finally {
            synchronized(lock) { draining = false; if (ring.pending() == 0) resetGoals() }
        }
    }
    private fun resetGoals() { gainGoal = Double.NaN; spanGoal = Double.NaN }
    private fun cancelLocked(reason: CommandRing.Reason) { generation++; handler?.post(retireFeedback); ring.cancelPending(reason, uptime()); resetGoals() }
    fun cancel() = synchronized(lock) { cancelLocked(CommandRing.Reason.FOCUS_CHANGED) }
    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; cancelLocked(CommandRing.Reason.CLOSED) }
        handler?.removeCallbacks(drainTask)
    }
    fun writeResult(commandId: Long, reply: Parcel): Boolean = synchronized(receipt) {
        if (!ring.read(commandId, receipt)) return false
        reply.writeInt(1); reply.writeInt(receipt.state.ordinal); reply.writeString(receipt.reason.name); reply.writeLong(receipt.sequence)
        reply.writeLong(receipt.revision); reply.writeLong(receipt.revision)
        reply.writeLong(if (receipt.revision >= 0 && owner.presentedDisplayRevision >= receipt.revision) receipt.revision else -1)
        reply.writeDouble(Double.NaN); reply.writeDouble(Double.NaN)
        true
    }
    fun diagnostic(age: Int, destination: CommandRing.Entry): Boolean = synchronized(lock) {
        age in 0 until diagnosticCount && ring.read(diagnostics[(diagnosticHead - 1 - age + diagnostics.size) % diagnostics.size], destination)
    }
    companion object {
        private const val GAIN = 0; private const val RANGE = 1; private const val METERS = 2
        private const val CROP = 3; private const val FREEZE = 4; private const val A_SCOPE = 5
        private const val GAIN_OFFSET = 8
        private const val MODE = 6; private const val OPEN = 7
    }
}
