package com.no5.header.android

import android.content.*
import android.os.*
import com.no5.control.*
import com.no5.header.*
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

/** Native lifecycle SDK. Every remote call uses the shared bounded IPC worker, never Main waits. */
class HeaderAndroidClient internal constructor(
    context: Context,
    initialPage: HeaderPage,
    private val onAdmission: (HeaderAdmission?) -> Unit,
    private val handler: Handler,
    private val verifyService: () -> VerifiedService,
    private val callbackUid: () -> Int,
    private val clock: HeaderClock,
    private val ipc: BoundedControlIpc,
) : AutoCloseable {
    constructor(context: Context, initialPage: HeaderPage, onAdmission: (HeaderAdmission?) -> Unit = {}) : this(
        context, initialPage, onAdmission, Handler(Looper.getMainLooper()), PackageServiceVerifier(context)::verify,
        Binder::getCallingUid, AndroidHeaderClock, ProcessControlIpc.worker)

    private val app = context.applicationContext
    @Volatile private var resumed = false
    @Volatile private var focused = false
    @Volatile private var closed = false
    private var connectionGeneration = 0L
    private var page = HeaderPageState(initialPage, 1, null, false, false)
    @Volatile private var binding: Binding? = null
    private val jobs = mutableSetOf<FutureTask<*>>()

    init { require(HeaderApps.profile(app.packageName, initialPage) != null); require(handler.looper == Looper.getMainLooper()) }
    fun onResume() { mainThread(); if (!closed) { resumed = true; reconcile() } }
    fun onPause() { mainThread(); resumed = false; binding?.let(::disconnect) }
    fun onWindowFocusChanged(value: Boolean) {
        mainThread(); if (!closed) { focused = value; reconcile() }
    }

    /** Increase generation before page OR source ownership changes; unchanged health is not a switch. */
    fun onPageSourceChanged(pageId: HeaderPage, pageGeneration: Long, sourceSessionId: String?) {
        mainThread(); require(HeaderApps.profile(app.packageName, pageId) != null)
        require(pageGeneration >= page.pageGeneration)
        if (pageId != page.pageId || sourceSessionId != page.sourceSessionId) require(pageGeneration > page.pageGeneration)
        val next = HeaderPageState(pageId, pageGeneration, sourceSessionId, resumed, focused)
        if (closed || next == page) return
        page = next
        binding?.let { row -> row.admission = null; onAdmission(null); refresh(row) }
    }

    /** Explicit retry after regained OS scope or queue failure; no background poller/rebinder. */
    fun retryAdmission() { mainThread(); if (!closed) { reconcile(); binding?.let(::refresh) } }
    fun captureScope(): HeaderAdmission? { mainThread(); return binding?.let { if (current(it)) it.admission?.takeIf { v -> v.ready } else null } }
    fun nextPublication(capturedScope: HeaderAdmission): HeaderPublication? {
        mainThread()
        val row = binding ?: return null
        if (!current(row) || capturedScope != row.admission || !capturedScope.ready) return null
        return row.publisher?.nextPublication(capturedScope.foregroundEpoch, capturedScope.pageGeneration, capturedScope.sourceSessionId)
    }

    /** True means queued, NEVER server acceptance. Exactly one result callback runs on Main. */
    fun publish(capturedScope: HeaderAdmission, snapshot: HeaderSnapshotV1,
        onResult: (HeaderPublishResult) -> Unit = {}): Boolean {
        mainThread()
        val row = binding
        if (row == null || !current(row) || row.admission != capturedScope || !matches(snapshot, capturedScope)) {
            onResult(HeaderPublishResult(false, HeaderClearReason.WRONG_FOREGROUND)); return false
        }
        return work(row, {
            // Per-call SDK transport captures the ORIGINAL immutable grant. No Main-shared SDK lock
            // is held during a remote call, so even a stalled Binder cannot block nextPublication.
            NativeHeaderPublisher(app.packageName, capturedScope.producerSession, clock,
                NativeHeaderTransport { requireNotNull(row.remote).publish(capturedScope, it) }).use { it.publish(snapshot) }
        }) { value ->
            onResult(if (current(row) && row.admission == capturedScope)
                value ?: HeaderPublishResult(false, HeaderClearReason.PRODUCER_DEAD)
                else HeaderPublishResult(false, HeaderClearReason.WRONG_FOREGROUND))
        }
    }

    private fun matches(v: HeaderSnapshotV1, a: HeaderAdmission): Boolean {
        val p = v.publication
        return p.packageName == app.packageName && p.producerSession == a.producerSession &&
            p.foregroundEpoch == a.foregroundEpoch && v.pageId == a.pageId &&
            p.pageGeneration == a.pageGeneration && p.sourceSessionId == a.sourceSessionId
    }
    private fun mainThread() { check(Looper.myLooper() === handler.looper) }
    private fun state() = page.copy(resumed = resumed, focused = focused)
    private fun current(row: Binding) = binding === row && row.alive.get() && !closed && resumed && focused
    private fun reconcile() {
        if (closed || !resumed || !focused) { binding?.let(::disconnect); return }
        if (binding != null) return
        val verified = try { verifyService() } catch (_: RuntimeException) { return }
        check(connectionGeneration < Long.MAX_VALUE)
        val row = Binding(++connectionGeneration, verified.uid); binding = row
        try {
            row.bound = app.bindService(Intent(HeaderAndroidContract.BIND_ACTION).setComponent(SystemControlContract.SERVICE),
                row.connection, Context.BIND_AUTO_CREATE)
            if (!row.bound) disconnect(row)
        } catch (_: RuntimeException) { disconnect(row) }
    }

    private inner class Binding(val generation: Long, val serverUid: Int) {
        val alive = AtomicBoolean(true)
        @Volatile var admission: HeaderAdmission? = null
        var registration: HeaderAdmission? = null
        var lastRevision = 0L
        var server: IBinder? = null
        var remote: HeaderRemote? = null
        var publisher: NativeHeaderPublisher? = null
        var bound = false
        val death = IBinder.DeathRecipient { alive.set(false); handler.post { disconnect(this) } }
        val callback: IBinder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(HeaderAndroidContract.CALLBACK_DESCRIPTOR); return true }
                if (code != HeaderAndroidContract.CALLBACK_STATE) return false
                // Service identity from verified Component/UID, before ANY Parcel field is read.
                if (callbackUid() != serverUid) throw SecurityException("Header callback UID refused")
                requireNotNull(reply); require(flags and IBinder.FLAG_ONEWAY == 0)
                HeaderParcel.size(data); data.enforceInterface(HeaderAndroidContract.CALLBACK_DESCRIPTOR)
                val value = HeaderParcel.readAdmission(data); HeaderParcel.end(data)
                val posted = current(this@Binding) && handler.post { accept(this@Binding, value) }
                reply.writeNoException(); HeaderParcel.boolean(reply, posted); return true
            }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                mainThread()
                if (!current(this@Binding) || name != SystemControlContract.SERVICE) { disconnect(this@Binding); return }
                try {
                    if (verifyService().uid != serverUid) throw SecurityException("Header service changed UID")
                    server = service; service.linkToDeath(death, 0); remote = HeaderRemote(service)
                    val initial = state()
                    work(this@Binding, { requireNotNull(remote).register(app.packageName, callback, initial) }) { first ->
                        if (first == null || !current(this@Binding)) { disconnect(this@Binding); return@work }
                        registration = first
                        publisher = NativeHeaderPublisher(app.packageName, first.producerSession, clock,
                            NativeHeaderTransport { throw IllegalStateException("Use publish with a captured scope") })
                        accept(this@Binding, first)
                        if (initial != state()) refresh(this@Binding)
                    }
                } catch (_: RemoteException) { disconnect(this@Binding) }
                catch (_: RuntimeException) { disconnect(this@Binding) }
            }
            override fun onServiceDisconnected(name: ComponentName) { mainThread(); disconnect(this@Binding) }
            override fun onBindingDied(name: ComponentName) { mainThread(); disconnect(this@Binding) }
            override fun onNullBinding(name: ComponentName) { mainThread(); disconnect(this@Binding) }
        }
    }

    private fun accept(row: Binding, value: HeaderAdmission) {
        mainThread()
        val registered = row.registration ?: return // Callback cannot establish registration identity.
        if (!current(row) || value.controllerSession != registered.controllerSession ||
            value.producerSession != registered.producerSession || value.processGeneration != registered.processGeneration ||
            value.revision <= row.lastRevision) return
        if (value.pageId != page.pageId || value.pageGeneration != page.pageGeneration || value.sourceSessionId != page.sourceSessionId) return
        row.lastRevision = value.revision; row.admission = value; onAdmission(value)
    }
    private fun refresh(row: Binding) {
        if (!current(row)) return
        val key = row.registration ?: return
        val requested = state()
        work(row, {
            val remote = requireNotNull(row.remote)
            val fresh = remote.admission(key, row.callback) // read-only, cannot clear the newer owner
            remote.state(fresh, row.callback, requested)
        }) { result ->
            if (result == null) disconnect(row) else accept(row, result)
        }
    }

    private fun <T> work(row: Binding?, operation: () -> T, completion: (T?) -> Unit): Boolean {
        val completed = AtomicBoolean(false)
        var task: FutureTask<T?>? = null
        lateinit var timeout: Runnable
        val finish: (T?) -> Unit = { value ->
            if (completed.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout); task?.let(ipc::cancel)
                handler.post { completion(value) }
            }
        }
        timeout = Runnable { finish(null) }
        val pending = object : FutureTask<T?>(Callable {
            if (row != null && !current(row)) null else try { operation() } catch (_: RemoteException) { null } catch (_: RuntimeException) { null }
        }) {
            override fun done() {
                synchronized(jobs) { jobs.remove(this) }
                val result = try { get() } catch (_: Exception) { null }; finish(result)
            }
        }
        task = pending
        synchronized(jobs) { jobs.add(pending) }
        handler.postDelayed(timeout, 250)
        if (!ipc.execute(pending)) { ipc.cancel(pending); return false }
        if (completed.get()) ipc.cancel(pending)
        return true
    }
    private fun disconnect(row: Binding) {
        mainThread(); row.alive.set(false); row.admission = null
        if (binding === row) { binding = null; onAdmission(null) }
        val key = row.registration; val remote = row.remote
        if (key != null && remote != null) work(null, { remote.deregister(key, row.callback) }) { }
        row.registration = null; row.publisher?.close(); row.publisher = null
        row.server?.let { try { it.unlinkToDeath(row.death, 0) } catch (_: RuntimeException) { } }
        row.server = null; row.remote = null
        if (row.bound) { row.bound = false; try { app.unbindService(row.connection) } catch (_: RuntimeException) { } }
    }
    override fun close() {
        mainThread(); if (closed) return
        closed = true; resumed = false; focused = false
        synchronized(jobs) { jobs.toList().forEach(ipc::cancel) }
        // Keep the final bounded deregistration: cancelling it would leave the old context until TTL.
        binding?.let(::disconnect)
    }
}
