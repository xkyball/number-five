package com.no5.header.android

import android.content.Context
import android.os.*
import com.no5.control.*
import com.no5.header.*
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

/** Authenticated native header adapter. App payloads can never establish OS foreground. */
class HeaderBinderHost internal constructor(
    val controllerSession: String,
    private val hostUser: Int,
    private val verifyCaller: (Int) -> CallerIdentity,
    private val callingUid: () -> Int,
    private val callingPid: () -> Int,
    private val clock: HeaderClock,
    private val handler: Handler,
    private val ipc: BoundedControlIpc,
    private val onState: (HeaderConsumerState) -> Unit,
    private val onExportOrigin: (HeaderRegistryOrigin) -> Unit = {},
) : AutoCloseable {
    constructor(context: Context, controllerSession: String = UUID.randomUUID().toString(),
        onState: (HeaderConsumerState) -> Unit) : this(controllerSession,
        Process.myUid() / SystemControlContract.USER_UID_RANGE, PackageCallerVerifier(context)::verify,
        Binder::getCallingUid, Binder::getCallingPid, AndroidHeaderClock,
        Handler(Looper.getMainLooper()), ProcessControlIpc.worker, onState)

    /** Origin callback may run on a Binder/death thread, under the host lock. No I/O/re-entry. */
    constructor(context: Context, controllerSession: String,
        onState: (HeaderConsumerState) -> Unit, onExportOrigin: (HeaderRegistryOrigin) -> Unit) :
        this(controllerSession, Process.myUid() / SystemControlContract.USER_UID_RANGE,
            PackageCallerVerifier(context)::verify, Binder::getCallingUid, Binder::getCallingPid,
            AndroidHeaderClock, Handler(Looper.getMainLooper()), ProcessControlIpc.worker, onState, onExportOrigin)

    init { requireHeaderId(controllerSession); require(hostUser >= 0); require(handler.looper == Looper.getMainLooper()) }
    private val lock = Any()
    private val registry = HeaderRegistry(onExportOrigin)
    private var closed = false
    private var generation = 0L
    private var revision = 0L
    private var foreground = ObservedForeground(null, 0, false, hostUser)
    private val rows = mutableMapOf<String, Registration>()
    private val jobs = mutableSetOf<FutureTask<*>>()
    private var stateQueued = false
    private var lastState: HeaderConsumerState? = null

    private inner class Registration(val caller: CallerIdentity, val pid: Int, val callback: IBinder,
        val processGeneration: Long, var page: HeaderPageState) {
        val producer = AuthenticatedHeaderProducer(caller.packageName, UUID.randomUUID().toString())
        val dead = AtomicBoolean(false)
        var grant = newGrant(this)
        var callbackQueued = false
        var outgoing: HeaderAdmission? = null
        var acceptedScopeToken: String? = null
        var acceptedSequence = -1L
        val death = IBinder.DeathRecipient { dead.set(true); retire(this) }
    }

    val binder: IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(HeaderAndroidContract.DESCRIPTOR); return true }
            if (code !in HeaderAndroidContract.REGISTER..HeaderAndroidContract.ADMISSION) return false
            // Kernel identity FIRST, before size, interface token, Parcel fields, or asynchronous work.
            val caller = verifyCaller(callingUid()); val pid = callingPid()
            if (pid <= 0 || caller.userId != hostUser) throw SecurityException("Header process refused")
            requireNotNull(reply); require(flags and IBinder.FLAG_ONEWAY == 0)
            HeaderParcel.size(data); data.enforceInterface(HeaderAndroidContract.DESCRIPTOR)
            when (code) {
                HeaderAndroidContract.REGISTER -> {
                    HeaderParcel.version(data)
                    if (HeaderParcel.string(data, 128) != caller.packageName) throw SecurityException("Header package claim refused")
                    val callback = HeaderParcel.binder(data); val page = HeaderParcel.readPage(data); HeaderParcel.end(data)
                    val grant = register(caller, pid, callback, page)
                    reply.writeNoException(); HeaderParcel.writeAdmission(reply, grant)
                }
                HeaderAndroidContract.STATE -> {
                    val prior = HeaderParcel.readAdmission(data); val callback = HeaderParcel.binder(data)
                    val page = HeaderParcel.readPage(data); HeaderParcel.end(data)
                    val grant = state(caller, pid, prior, callback, page)
                    reply.writeNoException(); HeaderParcel.writeAdmission(reply, grant)
                }
                HeaderAndroidContract.PUBLISH -> {
                    val grant = HeaderParcel.readAdmission(data); val snapshot = HeaderParcel.readSnapshot(data); HeaderParcel.end(data)
                    val result = publish(caller, pid, grant, snapshot)
                    reply.writeNoException(); HeaderParcel.writeResult(reply, result)
                }
                HeaderAndroidContract.DEREGISTER -> {
                    val grant = HeaderParcel.readAdmission(data); val callback = HeaderParcel.binder(data); HeaderParcel.end(data)
                    val row = synchronized(lock) { owned(caller, pid, grant, callback) }
                    val removed = row != null && retire(row)
                    reply.writeNoException(); HeaderParcel.boolean(reply, removed)
                }
                HeaderAndroidContract.ADMISSION -> {
                    val prior = HeaderParcel.readAdmission(data); val callback = HeaderParcel.binder(data); HeaderParcel.end(data)
                    val grant = synchronized(lock) {
                        owned(caller, pid, prior, callback)?.grant ?: throw SecurityException("Stale header registration")
                    }
                    reply.writeNoException(); HeaderParcel.writeAdmission(reply, grant)
                }
            }
            return true
        }
    }

    /** In-process Root observer only; there is intentionally no foreground transaction. */
    fun observeForeground(value: ObservedForeground): Boolean {
        mainThread()
        val changes = synchronized(lock) {
            if (closed || value.epoch < foreground.epoch) return false
            if (value.epoch == foreground.epoch) { require(value == foreground); return false }
            foreground = value
            rows.values.map { it.grant = newGrant(it); it to it.grant }.also { reconcileScope() }
        }
        changes.forEach { notifyAdmission(it.first, it.second) }; requestState()
        return true
    }

    fun current(): HeaderConsumerState { mainThread(); return synchronized(lock) { registry.current(clock.elapsedRealtimeMs(), clock.epochMs()) } }

    private fun register(caller: CallerIdentity, pid: Int, callback: IBinder, page: HeaderPageState): HeaderAdmission {
        require(HeaderApps.profile(caller.packageName, page.pageId) != null)
        val row = synchronized(lock) {
            check(!closed); check(generation < Long.MAX_VALUE)
            Registration(caller, pid, callback, ++generation, page)
        }
        callback.linkToDeath(row.death, 0)
        val previous: Registration?
        val grant: HeaderAdmission
        try {
            synchronized(lock) {
                check(!closed && !row.dead.get() && callback.isBinderAlive)
                previous = rows.put(caller.packageName, row)
                previous?.let { it.dead.set(true); registry.producerDied(it.producer) }
                check(registry.producerStarted(row.producer))
                row.grant = newGrant(row); reconcileScope(); grant = row.grant
            }
        } catch (failure: RuntimeException) { unlink(row); throw failure }
        previous?.let(::unlink); requestState()
        return grant
    }

    private fun state(caller: CallerIdentity, pid: Int, prior: HeaderAdmission, callback: IBinder,
        page: HeaderPageState): HeaderAdmission {
        val row: Registration
        val grant = synchronized(lock) {
            row = owned(caller, pid, prior, callback) ?: throw SecurityException("Stale header registration")
            require(prior.scopeToken == row.grant.scopeToken && prior.revision == row.grant.revision) { "Stale header lifecycle scope" }
            require(HeaderApps.profile(caller.packageName, page.pageId) != null)
            require(page.pageGeneration >= row.page.pageGeneration)
            if (page.pageGeneration == row.page.pageGeneration) require(page.pageId == row.page.pageId && page.sourceSessionId == row.page.sourceSessionId)
            // Same-session re-entry must not resurrect a scope the registry already retired.
            if ((!row.page.resumed || !row.page.focused) && page.resumed && page.focused)
                require(page.pageGeneration > row.page.pageGeneration)
            if (page == row.page) return@synchronized row.grant
            row.page = page; row.grant = newGrant(row); reconcileScope(); row.grant
        }
        notifyAdmission(row, grant); requestState(); return grant
    }

    private fun publish(caller: CallerIdentity, pid: Int, grant: HeaderAdmission,
        snapshot: HeaderSnapshotV1): HeaderPublishResult {
        val result = synchronized(lock) {
            val row = owned(caller, pid, grant, null)
                ?: return@synchronized HeaderPublishResult(false, HeaderClearReason.PRODUCER_DEAD)
            // Reject stale tokens outside the registry: they must not clear a newer valid frame.
            if (row.grant != grant || !grant.ready || !ready(row))
                return@synchronized HeaderPublishResult(false, HeaderClearReason.WRONG_FOREGROUND)
            val p = snapshot.publication
            if (p.packageName != caller.packageName || p.producerSession != grant.producerSession)
                return@synchronized HeaderPublishResult(false, HeaderClearReason.CALLER_MISMATCH)
            if (p.foregroundEpoch != grant.foregroundEpoch) return@synchronized HeaderPublishResult(false, HeaderClearReason.WRONG_FOREGROUND)
            if (p.pageGeneration != grant.pageGeneration || snapshot.pageId != grant.pageId)
                return@synchronized HeaderPublishResult(false, HeaderClearReason.WRONG_PAGE)
            if (p.sourceSessionId != grant.sourceSessionId)
                return@synchronized HeaderPublishResult(false, HeaderClearReason.WRONG_SOURCE_SESSION)
            if (row.acceptedScopeToken == grant.scopeToken && p.sequence <= row.acceptedSequence)
                return@synchronized HeaderPublishResult(false, HeaderClearReason.OUT_OF_ORDER)
            registry.publish(row.producer, snapshot, clock.elapsedRealtimeMs()).also {
                if (it.accepted) { row.acceptedScopeToken = grant.scopeToken; row.acceptedSequence = p.sequence }
            }
        }
        requestState(); return result
    }

    private fun owned(caller: CallerIdentity, pid: Int, grant: HeaderAdmission, callback: IBinder?): Registration? =
        rows[caller.packageName]?.takeIf { !closed && !it.dead.get() && it.caller == caller && it.pid == pid &&
            grant.controllerSession == controllerSession && grant.producerSession == it.producer.producerSession &&
            grant.processGeneration == it.processGeneration && (callback == null || callback == it.callback) }
    private fun ready(row: Registration) = !closed && !row.dead.get() && rows[row.caller.packageName] === row &&
        row.page.resumed && row.page.focused && foreground.resumed && foreground.epoch > 0 &&
        foreground.packageName == row.caller.packageName && foreground.userId == hostUser && foreground.displayId == 0
    private fun newGrant(row: Registration): HeaderAdmission {
        check(revision < Long.MAX_VALUE)
        return HeaderAdmission(controllerSession, row.producer.producerSession, row.processGeneration,
            UUID.randomUUID().toString(), foreground.epoch, row.page.pageId, row.page.pageGeneration,
            row.page.sourceSessionId, ++revision, ready(row))
    }
    private fun reconcileScope() {
        val row = rows[foreground.packageName]?.takeIf(::ready)
        registry.onForegroundChanged(row?.let { HeaderScope(it.caller.packageName, it.producer.producerSession,
            foreground.epoch, it.page.pageId, it.page.pageGeneration, it.page.sourceSessionId) })
    }
    private fun retire(row: Registration): Boolean {
        val removed = synchronized(lock) {
            if (rows[row.caller.packageName] !== row) false
            else { rows.remove(row.caller.packageName); row.dead.set(true); registry.producerDied(row.producer); reconcileScope(); true }
        }
        unlink(row); if (removed) requestState(); return removed
    }
    private fun unlink(row: Registration) { try { row.callback.unlinkToDeath(row.death, 0) } catch (_: RuntimeException) { } }

    private fun notifyAdmission(row: Registration, value: HeaderAdmission) {
        synchronized(lock) {
            if (closed || row.dead.get()) return
            row.outgoing = value; if (row.callbackQueued) return; row.callbackQueued = true
        }
        val task = object : FutureTask<Unit>(Callable {
            while (true) {
                val next = synchronized(lock) {
                    if (closed || row.dead.get()) { row.callbackQueued = false; return@Callable Unit }
                    row.outgoing.also { row.outgoing = null; if (it == null) row.callbackQueued = false }
                }
                if (next == null) return@Callable Unit
                try {
                    headerTransact(row.callback, HeaderAndroidContract.CALLBACK_DESCRIPTOR, HeaderAndroidContract.CALLBACK_STATE,
                        { HeaderParcel.writeAdmission(it, next) }, HeaderParcel::bool)
                } catch (_: RemoteException) { retire(row); return@Callable Unit }
                catch (_: RuntimeException) { retire(row); return@Callable Unit }
            }
        }) {
            override fun done() { synchronized(lock) { jobs.remove(this) } }
        }
        synchronized(lock) { if (closed) { row.callbackQueued = false; return }; jobs.add(task) }
        if (!ipc.execute(task)) { synchronized(lock) { row.callbackQueued = false }; ipc.cancel(task) }
    }

    private val stateTask = Runnable {
        val value = synchronized(lock) { stateQueued = false; registry.current(clock.elapsedRealtimeMs(), clock.epochMs()) }
        handler.removeCallbacks(ttlTask)
        val snapshot = value.snapshot
        if (snapshot != null && !synchronized(lock) { closed }) {
            val until = snapshot.publication.expiresAtElapsedMs - clock.elapsedRealtimeMs()
            handler.postDelayed(ttlTask, until.coerceIn(1, 250))
        }
        if (value != lastState) { lastState = value; onState(value) }
    }
    private val ttlTask = Runnable { requestState() }
    private fun requestState() {
        synchronized(lock) { if (stateQueued) return; stateQueued = true }
        if (!handler.post(stateTask)) synchronized(lock) { stateQueued = false }
    }
    private fun mainThread() { check(Looper.myLooper() === handler.looper) }
    override fun close() {
        mainThread()
        val retired = synchronized(lock) {
            if (closed) return
            closed = true
            rows.values.toList().also { list ->
                rows.clear(); list.forEach { it.dead.set(true); registry.producerDied(it.producer) }
                registry.onForegroundChanged(null); jobs.toList().forEach(ipc::cancel); jobs.clear()
            }
        }
        retired.forEach(::unlink); handler.removeCallbacks(ttlTask); handler.removeCallbacks(stateTask)
        synchronized(lock) { stateQueued = false }; requestState()
    }
}
