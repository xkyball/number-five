package com.no5.helm.engine

import com.no5.helm.chart.RendererBenchmarkConfig
import com.no5.helm.chart.RendererBenchmarkStatus
import java.util.UUID

/** Only short state transitions use this lock; no native calls, IO or suspension occurs here. */
internal class BenchmarkRunRegistry(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    internal data class Run(val id: String, val config: RendererBenchmarkConfig, val deadlineMs: Long)
    internal sealed interface Admission {
        data class Started(val run: Run) : Admission
        data class Reused(val run: Run) : Admission
        data class Rejected(val message: String) : Admission
    }
    internal data class Control(val run: Run, val cancelReason: String, val recoveryStartedMs: Long?,
        val recoveryFinishedMs: Long?, val recoveryDeadlineExceeded: Boolean, val finished: Boolean)
    private data class Record(val run: Run, var cancelReason: String = "", var recoveryStartedMs: Long? = null,
        var recoveryFinishedMs: Long? = null, var recoveryDeadlineExceeded: Boolean = false,
        var terminal: RendererBenchmarkStatus? = null)
    private val lock = Any()
    private val tokens = linkedMapOf<String, Record>()
    private var active: Record? = null

    fun admit(config: RendererBenchmarkConfig, nowMs: Long, preparedRunId: String? = null): Admission = synchronized(lock) {
        tokens[config.clientRunToken]?.let {
            return@synchronized if (it.run.config == config) Admission.Reused(it.run)
                else Admission.Rejected("Client run token already belongs to a different configuration")
        }
        if (active != null) return@synchronized Admission.Rejected("A measurement or its recovery already owns the renderer")
        // Do not evict old tokens and accidentally execute a delayed duplicate START as a new run.
        if (tokens.size >= 1024) return@synchronized Admission.Rejected("Run-token capacity reached; reopen the app after recovery")
        val deadline = if (config.deadlineElapsedRealtimeMs == 0L) nowMs + MAX_MEASUREMENT_MS else config.deadlineElapsedRealtimeMs
        if (deadline <= nowMs || deadline - nowMs > MAX_MEASUREMENT_MS)
            return@synchronized Admission.Rejected("Device elapsedRealtime deadline must be future and at most 600 seconds away")
        val run = Run(preparedRunId ?: newId(), config, deadline)
        val record = Record(run)
        tokens[config.clientRunToken] = record; active = record
        Admission.Started(run)
    }
    fun activeRun(): Run? = synchronized(lock) { active?.run }
    fun isActive(id: String): Boolean = synchronized(lock) { active?.run?.id == id }
    fun cancel(id: String, reason: String): Boolean = synchronized(lock) {
        val record = active?.takeIf { it.run.id == id && it.recoveryStartedMs == null } ?: return@synchronized false
        if (record.cancelReason.isEmpty()) record.cancelReason = reason
        true
    }
    /** Returns a newly expired measurement for signaling outside the registry lock. */
    fun tick(nowMs: Long): Run? = synchronized(lock) {
        val record = active ?: return@synchronized null
        val recovery = record.recoveryStartedMs
        if (recovery != null) {
            if (record.recoveryFinishedMs == null && nowMs - recovery >= record.run.config.recoveryBudgetMs)
                record.recoveryDeadlineExceeded = true
            null
        } else if (nowMs >= record.run.deadlineMs && record.cancelReason.isEmpty()) {
            record.cancelReason = "device deadline exceeded"; record.run
        } else null
    }
    fun cancelled(id: String): Boolean = synchronized(lock) {
        active?.takeIf { it.run.id == id }?.cancelReason?.isNotEmpty() ?: true
    }
    fun beginRecovery(id: String, nowMs: Long) = synchronized(lock) {
        active?.takeIf { it.run.id == id }?.let { if (it.recoveryStartedMs == null) it.recoveryStartedMs = nowMs }
    }
    /** Atomically closes measurement cancellation at the final observation freeze.
     * A newly expired deadline is returned for native signaling outside this lock. */
    fun sealMeasurementForRecovery(id: String, nowMs: Long): Run? = synchronized(lock) {
        val record = active?.takeIf { it.run.id == id } ?: return@synchronized null
        if (record.recoveryStartedMs != null) return@synchronized null
        val expired = nowMs >= record.run.deadlineMs && record.cancelReason.isEmpty()
        if (expired) record.cancelReason = "device deadline exceeded"
        record.recoveryStartedMs = nowMs
        if (expired) record.run else null
    }
    fun endRecovery(id: String, nowMs: Long) = synchronized(lock) {
        active?.takeIf { it.run.id == id }?.let {
            it.recoveryFinishedMs = nowMs
            it.recoveryStartedMs?.let { start -> if (nowMs - start >= it.run.config.recoveryBudgetMs) it.recoveryDeadlineExceeded = true }
        }
    }
    fun finish(id: String, status: RendererBenchmarkStatus): Boolean = synchronized(lock) {
        val record = active?.takeIf { it.run.id == id } ?: return@synchronized false
        record.terminal = status.copy(active = false); active = null; true
    }
    fun control(id: String): Control? = synchronized(lock) {
        tokens.values.firstOrNull { it.run.id == id }?.let {
            Control(it.run, it.cancelReason, it.recoveryStartedMs, it.recoveryFinishedMs, it.recoveryDeadlineExceeded, it.terminal != null)
        }
    }
    fun status(current: RendererBenchmarkStatus, token: String? = null): RendererBenchmarkStatus = synchronized(lock) {
        val record = if (token != null) tokens[token] else active ?: tokens.values.lastOrNull()
        if (record == null) return@synchronized if (token == null) current else RendererBenchmarkStatus(message = "Unknown client run token")
        val base = record.terminal ?: current.takeIf { it.runId == record.run.id }
            ?: RendererBenchmarkStatus(active = true, runId = record.run.id, phase = "accepted")
        base.copy(clientRunToken = record.run.config.clientRunToken, deadlineElapsedRealtimeMs = record.run.deadlineMs,
            recoveryBudgetMs = record.run.config.recoveryBudgetMs, cancelReason = record.cancelReason,
            recoveryDeadlineExceeded = record.recoveryDeadlineExceeded, runKind = record.run.config.runKind,
            liveReplay = record.run.config.liveReplay && record.run.config.runKind == com.no5.helm.chart.RendererRunKind.LIVE_INPUT)
    }
    companion object { const val MAX_MEASUREMENT_MS = 600_000L }
}
