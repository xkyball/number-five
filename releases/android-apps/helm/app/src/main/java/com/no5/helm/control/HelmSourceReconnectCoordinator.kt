package com.no5.helm.control

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Factory admission is distinct from a proven connected Audio/HELM/SONAR source. */
data class HelmSourceRecoveryStatus(val phase: String, val reason: String, val attempt: Long,
    val startedUptimeMs: Long, val finishedUptimeMs: Long, val deadlineUptimeMs: Long,
    val nextRetryUptimeMs: Long?, val sourceAccepted: Boolean, val refreshInFlight: Boolean)

/** One Main-owned timer and one posted reconciliation, with no worker or source transport. */
internal class HelmSourceReconnectCoordinator(
    private val retry: () -> Unit,
    private val reconcile: () -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val uptime: () -> Long = SystemClock::uptimeMillis,
) : AutoCloseable {
    private var closed = false
    private var failures = 0
    private var timer: Runnable? = null
    private var observation: Runnable? = null
    var nextRetryUptimeMs: Long? = null; private set
    fun requested() { main(); cancelTimer() }
    fun observed() {
        main(); if (closed || observation != null) return
        val task = Runnable { observation = null; if (!closed) reconcile() }
        observation = task
        if (!handler.post(task)) observation = null
    }
    fun completed(accepted: Boolean, inFlight: Boolean, blocked: Boolean = false) {
        main(); if (closed) return
        if (accepted || inFlight || blocked) {
            if (accepted) failures = 0
            cancelTimer(); return
        }
        if (timer != null) return
        val delay = DELAYS[failures.coerceAtMost(DELAYS.lastIndex)]
        if (failures < DELAYS.size) failures++
        val now = uptime()
        check(now >= 0 && now <= Long.MAX_VALUE - delay)
        val task = Runnable { timer = null; nextRetryUptimeMs = null; if (!closed) retry() }
        timer = task; nextRetryUptimeMs = now + delay
        if (!handler.postDelayed(task, delay)) { timer = null; nextRetryUptimeMs = null }
    }
    private fun cancelTimer() { timer?.let(handler::removeCallbacks); timer = null; nextRetryUptimeMs = null }
    private fun main() { check(Looper.myLooper() === handler.looper) }
    override fun close() {
        main(); if (closed) return
        closed = true; cancelTimer(); observation?.let(handler::removeCallbacks); observation = null
    }
    private companion object { val DELAYS = longArrayOf(2_000, 5_000, 10_000, 30_000) }
}
