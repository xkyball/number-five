package com.no5.helm.control

import android.os.Handler
import android.os.Looper
import com.no5.header.*
import com.no5.header.consumer.HeaderConsumerSnapshotAdapter
import org.lumina.header.consumer.v1.AndroidHeaderSocketPort
import org.lumina.header.consumer.v1.HeaderConsumerContract as Wire
import org.lumina.header.consumer.v1.HeaderReadOnlyPublisher
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Service-owned read-only export. No feed, SourceFactory, navigation or control commands.
 * Registry callbacks capture an immutable Root/local scope BEFORE a bounded Main handoff.
 * Runtime is deliberately disabled without an explicitly reviewed connected transport. */
internal class HelmHeaderConsumerExporter(
    private val controllerSession: String,
    private val transportFactory: HelmHeaderTransportFactory? = null,
    private val clock: Wire.Clock = AndroidHeaderSocketPort.ANDROID_CLOCK,
    private val main: MainPort = AndroidMainPort(),
    private val worker: WorkPort = PROCESS_WORKER,
    private val closer: WorkPort = PROCESS_CLOSER,
) : AutoCloseable {
    interface MainPort {
        fun checkMain()
        fun post(task: Runnable): Boolean
        fun postDelayed(task: Runnable, delayMs: Long): Boolean
        fun remove(task: Runnable)
    }
    fun interface WorkPort { fun execute(task: Runnable): Boolean }
    enum class Status { DISABLED, WAITING_FOR_ROOT_FOCUS, CONNECTING, LIVE, RETIRED, CLOSED }
    private data class CapturedOrigin(val origin: HeaderRegistryOrigin, val mapping: HelmHeaderFocusMapping,
        val generation: Long, val admissionSerial: Long, val elapsedAtOrigin: Long, val uptimeAtOrigin: Long)
    private val lock = Any()
    private var mapping = HelmHeaderFocusMapping(null, null, 0)
    private val pending = ArrayDeque<CapturedOrigin>()
    private var drainPosted = false
    private var started = false
    private var attempted = false
    @Volatile private var closed = false
    @Volatile private var generation = 1L
    private var workerBusy = false
    @Volatile private var publisher: HeaderReadOnlyPublisher? = null
    @Volatile private var transport: HelmHeaderExportTransport? = null
    @Volatile private var admissionSerial = 0L
    private var lastOriginRevision = 0L
    private var lastElapsed = -1L
    private var lastUptime = -1L
    private var lastHeartbeatStartedElapsed = -1L
    private var cachedOriginal: HeaderSnapshotV1? = null
    private var cachedWire: Wire.Snapshot? = null
    @Volatile var status = if (transportFactory == null) Status.DISABLED else Status.WAITING_FOR_ROOT_FOCUS
        private set
    @Volatile var terminalReason: String? = null
        private set
    private val drainTask = Runnable { drainOrigins() }
    private val tickTask = Runnable { heartbeat() }

    fun start() {
        main.checkMain(); check(!closed && !started); started = true
        maybeOpen()
    }
    fun rootHookFailed() { main.checkMain(); retire("ROOT_ORIGIN_HOOK_FAILED") }

    /** Main actor: actual accepted HELLO/FOCUS/CANCEL/revoke, never derived from a header. */
    fun observeRoot(value: HelmHeaderFocusMapping) {
        main.checkMain()
        val previous = synchronized(lock) { if (closed) return; mapping.also { mapping = value } }
        if (attempted && (value.rootFocus == null || value.rootControlSession != previous.rootControlSession)) {
            retire("ROOT_SCOPE_REVOKED"); return
        }
        val active = publisher
        if (active != null) {
            if (!fresh(value) || !active.adoptFocus(value.rootFocus, value.hostForegroundEpoch)) {
                retire("ROOT_FOCUS_INVALID"); return
            }
            if (!value.sameScope(previous)) {
                val scope = active.captureScopeForAcceptedState()
                val why = if (HeaderApps.allowed(value.rootFocus!!.packageName)) Wire.Reason.NO_PUBLICATION else Wire.Reason.NO_FOREGROUND_SCOPE
                if (!active.acceptedState(scope, null, why, Wire.Theme.DAY)) retire("ROOT_MAPPING_REJECTED")
            }
        }
        maybeOpen()
    }

    /** May run on Binder/death threads while the registry lock is held. No I/O or source reads. */
    fun captureOrigin(origin: HeaderRegistryOrigin) {
        var overflow = false
        var post = false
        synchronized(lock) {
            if (closed || status == Status.RETIRED) return
            val captured = mapping
            if (captured.rootFocus == null) return
            if (pending.size >= MAX_PENDING_ORIGINS) { overflow = true }
            else {
                pending.addLast(CapturedOrigin(origin, captured, generation, admissionSerial,
                    clock.elapsedRealtimeMs(), clock.uptimeMs()))
                if (!drainPosted) { drainPosted = true; post = true }
            }
        }
        if (overflow) failFromOrigin("ORIGIN_QUEUE_OVERFLOW")
        else if (post && !main.post(drainTask)) failFromOrigin("MAIN_QUEUE_UNAVAILABLE")
    }

    private fun failFromOrigin(why: String) {
        // Pure state is revoked immediately, before scheduling any native/socket close.
        val toClose = synchronized(lock) {
            if (closed || status == Status.RETIRED) return
            status = Status.RETIRED; terminalReason = why; generation++
            val retiredPublisher = publisher; val retiredTransport = transport
            publisher = null; transport = null; pending.clear(); drainPosted = false
            retiredPublisher to retiredTransport
        }
        // No Publisher monitor while holding exporter/registry/host locks. In-flight publisher
        // live() now sees the retired control gate; closure itself runs only on the bounded closer.
        closeOffMain(toClose.first, toClose.second)
    }

    private fun drainOrigins() {
        main.checkMain()
        while (true) {
            val captured = synchronized(lock) {
                if (closed || status == Status.RETIRED) { pending.clear(); drainPosted = false; return }
                if (pending.isEmpty()) { drainPosted = false; return }
                pending.removeFirst()
            }
            if (captured.generation != generation || !captured.mapping.sameScope(currentMapping())) continue
            val nowElapsed = clock.elapsedRealtimeMs(); val nowUptime = clock.uptimeMs()
            if (nowElapsed < captured.elapsedAtOrigin || nowUptime < captured.uptimeAtOrigin ||
                nowElapsed >= Wire.deadline(captured.elapsedAtOrigin, Wire.FRAME_DEADLINE_MS) ||
                nowUptime >= Wire.deadline(captured.uptimeAtOrigin, Wire.FRAME_DEADLINE_MS) || !fresh(captured.mapping)) {
                retire("ORIGIN_DEADLINE"); return
            }
            val event = captured.origin
            if (event.revision <= lastOriginRevision) { retire("ORIGIN_ORDER"); return }
            lastOriginRevision = event.revision
            val raw = event.snapshot
            val eventScope = event.scope
            if (eventScope != null && (eventScope.foregroundEpoch != captured.mapping.hostForegroundEpoch ||
                eventScope.packageName != captured.mapping.rootFocus!!.packageName)) continue
            val dto = try {
                if (raw == null) null else {
                    val previous = cachedOriginal
                    if (previous?.publication == raw.publication) {
                        if (previous != raw) { retire("ORIGINAL_REWRITE"); return }
                        cachedWire
                    } else {
                        HeaderConsumerSnapshotAdapter.original(raw).also { cachedOriginal = raw; cachedWire = it }
                    }
                }
            } catch (_: RuntimeException) { retire("ORIGINAL_INVALID"); return }
            // Handshake completion cannot re-label a callback captured before its admission.
            if (captured.admissionSerial == 0L || captured.admissionSerial != admissionSerial) continue
            val active = publisher ?: continue
            val liveScope = active.captureScopeForAcceptedState() ?: run { retire("SCOPE_UNAVAILABLE"); return }
            // Preserve the origin's focus observation. Same-scope FOCUS refresh is independent data.
            val root = captured.mapping.rootFocus!!
            val originScope = Wire.ScopeEvidence(root.rootControlSession, liveScope.consumerSession,
                controllerSession, root.rootEpoch, root.rootWindowIdentity, captured.mapping.hostForegroundEpoch,
                root.packageName, root.user, root.display, root.observedUptimeMs)
            if (!active.acceptedState(originScope, dto, HeaderConsumerSnapshotAdapter.reason(event.reason),
                    dto?.theme ?: Wire.Theme.DAY)) { retire("ACCEPTED_STATE_REJECTED"); return }
        }
    }

    private fun maybeOpen() {
        if (!started || attempted || closed || transportFactory == null || status == Status.RETIRED) return
        val origin = currentMapping()
        if (origin.rootFocus == null || !fresh(origin)) return
        attempted = true; status = Status.CONNECTING; workerBusy = true
        val openedGeneration = generation
        if (!worker.execute(Runnable {
            var opened: HelmHeaderExportTransport? = null
            var accepted: HeaderReadOnlyPublisher? = null
            try {
                opened = transportFactory.open()
                accepted = opened.start(clock, controllerSession) { session ->
                    synchronized(lock) { !closed && status != Status.RETIRED && mapping.rootControlSession == session }
                }
            } catch (_: Exception) { /* Failed admission retires this one-shot export only. */ }
            val connection = opened; val candidate = accepted
            if (!main.post(Runnable {
                workerBusy = false
                val current = currentMapping()
                if (closed || status == Status.RETIRED || openedGeneration != generation ||
                    !origin.sameScope(current) || candidate == null || !fresh(current)) {
                    closeOffMain(candidate, connection)
                    if (!closed && status != Status.RETIRED) retire("TRANSPORT_ADMISSION_FAILED")
                    return@Runnable
                }
                val installed = synchronized(lock) {
                    if (closed || status == Status.RETIRED || openedGeneration != generation) false
                    else { publisher = candidate; transport = connection; admissionSerial = 1; status = Status.LIVE; true }
                }
                if (!installed) { closeOffMain(candidate, connection); return@Runnable }
                if (!candidate.adoptFocus(current.rootFocus, current.hostForegroundEpoch)) {
                    retire("TRANSPORT_FOCUS_FAILED"); return@Runnable
                }
                val scope = candidate.captureScopeForAcceptedState()
                if (!HeaderApps.allowed(current.rootFocus!!.packageName) &&
                    !candidate.acceptedState(scope, null, Wire.Reason.NO_FOREGROUND_SCOPE, Wire.Theme.DAY)) {
                    retire("TRANSPORT_BASE_FAILED"); return@Runnable
                }
                // Do not retag/replay cached publications captured before this admission.
                heartbeat()
            })) {
                closeOffMain(candidate, connection); failFromOrigin("MAIN_QUEUE_UNAVAILABLE")
            }
        })) { workerBusy = false; retire("WORKER_QUEUE_UNAVAILABLE") }
    }

    private fun heartbeat() {
        main.checkMain()
        if (closed || status != Status.LIVE) return
        val current = currentMapping()
        if (!fresh(current) || workerBusy) { retire("HEARTBEAT_FOCUS_OR_QUEUE"); return }
        val connection = transport ?: run { retire("TRANSPORT_UNAVAILABLE"); return }
        val serial = generation
        val began = clock.elapsedRealtimeMs()
        if (lastHeartbeatStartedElapsed >= 0 && (began < lastHeartbeatStartedElapsed ||
            began >= Wire.deadline(lastHeartbeatStartedElapsed, Wire.HEARTBEAT_MAX_GAP_MS))) {
            retire("HEARTBEAT_GAP"); return
        }
        lastHeartbeatStartedElapsed = began
        workerBusy = true
        if (!worker.execute(Runnable {
            val success = try { connection.sendHeartbeat() } catch (_: Exception) { false }
            val finished = clock.elapsedRealtimeMs()
            if (!main.post(Runnable {
                workerBusy = false
                if (closed || serial != generation || status != Status.LIVE) return@Runnable
                val admittedAt = clock.elapsedRealtimeMs()
                if (!success || finished < began || admittedAt < finished ||
                    admittedAt >= Wire.deadline(began, Wire.HEARTBEAT_MAX_GAP_MS) ||
                    !fresh(currentMapping())) { retire("HEARTBEAT_FAILED_OR_EXPIRED"); return@Runnable }
                if (!main.postDelayed(tickTask, HEARTBEAT_MS)) retire("MAIN_QUEUE_UNAVAILABLE")
            })) failFromOrigin("MAIN_QUEUE_UNAVAILABLE")
        })) { workerBusy = false; retire("WORKER_QUEUE_UNAVAILABLE") }
    }

    private fun currentMapping() = synchronized(lock) { mapping }
    private fun fresh(value: HelmHeaderFocusMapping): Boolean {
        val elapsed = clock.elapsedRealtimeMs(); val uptime = clock.uptimeMs()
        if (elapsed < 0 || uptime < 0 || elapsed < lastElapsed || uptime < lastUptime) return false
        lastElapsed = elapsed; lastUptime = uptime
        return value.hostForegroundEpoch > 0 && value.rootFocus?.let {
            it.rootControlSession == value.rootControlSession && Wire.freshUptime(it.observedUptimeMs, uptime)
        } == true
    }
    private fun retire(why: String) {
        main.checkMain(); failFromOrigin(why)
        main.remove(tickTask); main.remove(drainTask)
    }
    private fun closeOffMain(retiredPublisher: HeaderReadOnlyPublisher?, value: HelmHeaderExportTransport?) {
        if (retiredPublisher == null && value == null) return
        // One process closer, bounded queue; never replace a hung worker or wait on Main.
        closer.execute(Runnable {
            try { retiredPublisher?.close() } finally { try { value?.close() } catch (_: Exception) { } }
        })
    }
    override fun close() {
        main.checkMain()
        if (closed) return
        retire("SERVICE_CLOSED")
        synchronized(lock) { closed = true; status = Status.CLOSED; cachedOriginal = null; cachedWire = null }
    }
    private class AndroidMainPort : MainPort {
        private val handler = Handler(Looper.getMainLooper())
        override fun checkMain() { check(Looper.myLooper() === handler.looper) }
        override fun post(task: Runnable) = handler.post(task)
        override fun postDelayed(task: Runnable, delayMs: Long) = handler.postDelayed(task, delayMs)
        override fun remove(task: Runnable) { handler.removeCallbacks(task) }
    }
    companion object {
        internal const val MAX_PENDING_ORIGINS = 16
        internal const val HEARTBEAT_MS = 50L
        private fun boundedWorker(name: String): WorkPort {
            val pool = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
                { runnable -> Thread(runnable, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
            return WorkPort { task -> try { pool.execute(task); true } catch (_: RuntimeException) { false } }
        }
        private val PROCESS_WORKER = boundedWorker("helm-header-export")
        private val PROCESS_CLOSER = boundedWorker("helm-header-export-close")
    }
}
