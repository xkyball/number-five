package com.no5.helm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.no5.domain.SourceSubscription
import com.no5.gateway.HomeGatewayClient
import com.no5.gateway.HomeGatewayStatus
import com.no5.gateway.HomeGatewayUnavailable
import com.no5.helm.security.GatewaySecurityProfile
import com.no5.helm.security.GatewaySecurityProfileProvider
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class HomeGatewayReadPhase { IDLE, QUEUED, OFFER, STATUS_BEFORE, PROFILE, STATUS_AFTER, DELIVERY, CLOSED }
enum class HomeGatewayReadReason {
    NOT_STARTED, IN_FLIGHT, READY, PROVIDER_FAILURE, HOME_ABSENT, CONFIGURATION_UNUSABLE,
    KEY_MISSING, CA_MISSING, PROFILE_UNAVAILABLE, IDENTITY_CHANGED, ORIGIN_MISMATCH,
    TIMEOUT, CLOCK_ROLLBACK, WORKER_BUSY, CLOSED, GATEWAY_NOT_PROVISIONED,
}
/** Fixed codes and monotone timings only; no origin, credentials or exception payload. */
data class HomeGatewayReadDiagnostic(val phase: HomeGatewayReadPhase, val reason: HomeGatewayReadReason,
    val attempt: Long, val startedUptimeMs: Long, val finishedUptimeMs: Long, val deadlineUptimeMs: Long)

/** Main owns the snapshot; only the one bounded worker may call the Home provider. */
internal class HomeGatewayStatusReader internal constructor(
    private val status: () -> HomeGatewayStatus,
    private val offer: (String) -> Unit,
    private val profiles: GatewaySecurityProfileProvider,
    private val handler: Handler,
    private val worker: Worker,
    private val uptime: () -> Long = SystemClock::uptimeMillis,
    private val deadlineMs: Long = DEADLINE_MS,
) : AutoCloseable {
    init { require(deadlineMs in 1..DEADLINE_MS) }
    constructor(context: Context) : this(HomeGatewayClient(context)::status,
        HomeGatewayClient(context)::offerOrigin, homeSecurityProfiles(context),
        Handler(Looper.getMainLooper()), ProcessHomeGatewayWorker)

    internal fun interface Cancellation { fun cancel() }
    internal fun interface Worker { fun execute(task: Runnable): Cancellation? }
    private data class Snapshot(val status: HomeGatewayStatus, val profile: GatewaySecurityProfile?) : AutoCloseable {
        override fun close() { profile?.close() }
    }
    private var snapshot: Snapshot? = null
    private var generation = 0L
    private var closed = false
    private var listener: (() -> Unit)? = null
    private var pending: Cancellation? = null
    private var timeout: Runnable? = null
    private var attempts = 0L
    private class ReadProgress { @Volatile var phase = HomeGatewayReadPhase.QUEUED }
    private var progress: ReadProgress? = null
    private var lastDiagnostic = HomeGatewayReadDiagnostic(HomeGatewayReadPhase.IDLE,
        HomeGatewayReadReason.NOT_STARTED, 0, 0, 0, 0)
    val isRefreshing: Boolean get() { main(); return timeout != null }
    val diagnostic: HomeGatewayReadDiagnostic get() {
        main(); return if (isRefreshing) lastDiagnostic.copy(phase = progress?.phase ?: HomeGatewayReadPhase.QUEUED) else lastDiagnostic
    }

    fun cachedStatus(): HomeGatewayStatus {
        main(); if (closed) throw HomeGatewayUnavailable()
        return snapshot?.status ?: throw HomeGatewayUnavailable()
    }
    val cachedProfiles = GatewaySecurityProfileProvider { endpoint ->
        main()
        if (closed) null else snapshot?.takeIf { it.status.configuration?.origin?.url == endpoint }
            ?.profile?.copyForSession()
    }
    fun observe(onChange: () -> Unit): SourceSubscription {
        main(); check(!closed && listener == null)
        listener = onChange
        return SourceSubscription { main(); if (listener === onChange) listener = null }
    }

    /** Superseded, timed-out and closed requests never become the current snapshot. */
    fun refresh(endpoint: String) {
        main(); if (closed) return
        check(generation < Long.MAX_VALUE)
        val captured = ++generation
        val started = uptime()
        check(started >= 0 && started <= Long.MAX_VALUE - deadlineMs)
        val expires = started + deadlineMs
        check(attempts < Long.MAX_VALUE); attempts++
        val attempt = attempts
        val capturedProgress = ReadProgress(); progress = capturedProgress
        lastDiagnostic = HomeGatewayReadDiagnostic(HomeGatewayReadPhase.QUEUED, HomeGatewayReadReason.IN_FLIGHT,
            attempt, started, 0, expires)
        pending?.cancel(); pending = null
        timeout?.let(handler::removeCallbacks)
        val expiry = Runnable {
            if (!closed && captured == generation) {
                pending?.cancel(); pending = null; timeout = null
                // Retire the generation before the stuck Binder eventually returns.
                check(generation < Long.MAX_VALUE); generation++
                record(capturedProgress.phase, HomeGatewayReadReason.TIMEOUT, attempt, started, expires)
                replace(null)
            }
        }
        timeout = expiry
        handler.postDelayed(expiry, deadlineMs)
        val task = Runnable {
            val (result, reason, phase) = readSnapshot(endpoint) { capturedProgress.phase = it }
            // No secret-bearing result is abandoned when a queued delivery is rejected.
            if (!handler.post {
                if (closed || captured != generation) result?.close()
                else {
                    timeout?.let(handler::removeCallbacks); timeout = null; pending = null
                    val delivered = uptime()
                    if (delivered < started || delivered >= expires) {
                        check(generation < Long.MAX_VALUE); generation++
                        result?.close()
                        record(HomeGatewayReadPhase.DELIVERY, if (delivered < started)
                            HomeGatewayReadReason.CLOCK_ROLLBACK else HomeGatewayReadReason.TIMEOUT,
                            attempt, started, expires)
                        replace(null)
                    } else {
                        record(phase, reason, attempt, started, expires)
                        replace(result)
                    }
                }
            }) result?.close()
        }
        pending = worker.execute(task)
        if (pending == null) {
            handler.removeCallbacks(expiry); timeout = null
            check(generation < Long.MAX_VALUE); generation++
            record(HomeGatewayReadPhase.QUEUED, HomeGatewayReadReason.WORKER_BUSY, attempt, started, expires)
            replace(null)
        }
    }
    private data class ReadResult(val snapshot: Snapshot?, val reason: HomeGatewayReadReason, val phase: HomeGatewayReadPhase)
    private fun readSnapshot(endpoint: String, phaseChanged: (HomeGatewayReadPhase) -> Unit): ReadResult {
        var profile: GatewaySecurityProfile? = null
        var phase = HomeGatewayReadPhase.OFFER
        fun stage(next: HomeGatewayReadPhase) { phase = next; phaseChanged(next) }
        return try {
            stage(HomeGatewayReadPhase.OFFER)
            offer(endpoint)
            stage(HomeGatewayReadPhase.STATUS_BEFORE)
            val before = status()
            val record = before.configuration
            val needsProfile = before.installed && record?.usable == true && before.keyPresent && before.caPresent
            if (needsProfile) { stage(HomeGatewayReadPhase.PROFILE); profile = profiles.load(requireNotNull(record).origin.url) }
            stage(HomeGatewayReadPhase.STATUS_AFTER)
            val after = status()
            val reason = when {
                before.identity != after.identity -> HomeGatewayReadReason.IDENTITY_CHANGED
                needsProfile && profile == null -> HomeGatewayReadReason.PROFILE_UNAVAILABLE
                needsProfile && profile?.origin?.value != record?.origin?.url -> HomeGatewayReadReason.ORIGIN_MISMATCH
                !after.installed -> HomeGatewayReadReason.HOME_ABSENT
                after.configuration?.usable != true -> HomeGatewayReadReason.CONFIGURATION_UNUSABLE
                !after.keyPresent -> HomeGatewayReadReason.KEY_MISSING
                !after.caPresent -> HomeGatewayReadReason.CA_MISSING
                else -> HomeGatewayReadReason.READY
            }
            if (reason in setOf(HomeGatewayReadReason.IDENTITY_CHANGED, HomeGatewayReadReason.PROFILE_UNAVAILABLE,
                    HomeGatewayReadReason.ORIGIN_MISMATCH)) {
                profile?.close(); ReadResult(null, reason, phase)
            } else ReadResult(Snapshot(after, profile), reason, phase)
        } catch (_: RuntimeException) { profile?.close(); ReadResult(null, HomeGatewayReadReason.PROVIDER_FAILURE, phase) }
    }
    private fun record(phase: HomeGatewayReadPhase, reason: HomeGatewayReadReason,
        attempt: Long, started: Long, expires: Long) {
        val finished = uptime()
        lastDiagnostic = HomeGatewayReadDiagnostic(phase, reason, attempt, started, finished, expires)
        Log.i("No5SourceRecovery", "phase=${phase.name} reason=${reason.name} attempt=$attempt elapsedMs=${(finished - started).coerceAtLeast(0)}")
    }
    private fun replace(next: Snapshot?) {
        val previous = snapshot; snapshot = next
        previous?.close()
        listener?.invoke()
    }
    private fun main() { check(Looper.myLooper() === handler.looper) }
    override fun close() {
        main(); if (closed) return
        closed = true; listener = null
        pending?.cancel(); pending = null
        timeout?.let(handler::removeCallbacks); timeout = null
        snapshot?.close(); snapshot = null
        lastDiagnostic = lastDiagnostic.copy(phase = HomeGatewayReadPhase.CLOSED, reason = HomeGatewayReadReason.CLOSED,
            finishedUptimeMs = uptime())
    }
    companion object { internal const val DEADLINE_MS = 5_000L }
}

/** One running Binder call and one queue slot across owner recreation; never spawn around a stall. */
private object ProcessHomeGatewayWorker : HomeGatewayStatusReader.Worker {
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1), { work -> Thread(work, "no5-home-status-ipc").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    override fun execute(task: Runnable): HomeGatewayStatusReader.Cancellation? = try {
        executor.execute(task)
        HomeGatewayStatusReader.Cancellation { executor.remove(task) }
    } catch (_: RejectedExecutionException) { null }
}
