package com.no5.control

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import android.os.Looper
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** One registration per verified product package. It has no audio/OEM/default-command access. */
class ControlBinderHost internal constructor(
    val controllerSession: String,
    private val hostUserId: Int,
    private val verifyCaller: (Int) -> CallerIdentity,
    private val callingUid: () -> Int,
    private val ipc: BoundedControlIpc = ProcessControlIpc.worker,
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val resultHandler: Handler = Handler(Looper.getMainLooper()),
    private val onClientStateChanged: () -> Unit = {},
) : AutoCloseable {
    constructor(context: Context, controllerSession: String = UUID.randomUUID().toString(),
                onClientStateChanged: () -> Unit = {}) : this(
        controllerSession, Process.myUid() / SystemControlContract.USER_UID_RANGE,
        PackageCallerVerifier(context)::verify, Binder::getCallingUid,
        onClientStateChanged = onClientStateChanged,
    )

    init { requireSession(controllerSession); require(hostUserId >= 0) }

    private val lock = Any()
    private var closed = false
    private var nextClientEpoch = 0L
    private var nextRevision = 0L
    private var foreground = ObservedForeground(null, 0, false, hostUserId)
    private val clients = mutableMapOf<String, Registration>()
    private val jobs = mutableSetOf<FutureTask<*>>()
    private val completions = mutableSetOf<(ClientDisposition) -> Unit>()

    private inner class Registration(val caller: CallerIdentity, val callback: IBinder,
                                     val clientEpoch: Long, var state: ClientState) {
        val dead = AtomicBoolean(false)
        var revision = 0L
        var lastSequence = 0L
        var pending: KnobEnvelope? = null
        var outgoingState: ClientAdmission? = null
        var stateQueued = false
        val death = IBinder.DeathRecipient { dead.set(true); retire(this) }
    }

    val binder: IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(SystemControlContract.SERVICE_DESCRIPTOR)
                return true
            }
            if (code !in SystemControlContract.REGISTER..SystemControlContract.DEREGISTER) return false
            // Capture/authorize on this Binder thread before parsing, posting or clearing identity.
            val caller = verifyCaller(callingUid())
            requireNotNull(reply)
            require(flags and IBinder.FLAG_ONEWAY == 0)
            ControlParcel.checkSize(data)
            data.enforceInterface(SystemControlContract.SERVICE_DESCRIPTOR)
            ControlParcel.version(data)
            when (code) {
                SystemControlContract.REGISTER -> {
                    val packageName = ControlParcel.string(data, 128)
                    if (packageName != caller.packageName) throw SecurityException("Package claim refused")
                    val callback = ControlParcel.binder(data)
                    val state = ControlParcel.readState(data)
                    ControlParcel.end(data)
                    val admission = register(caller, callback, state)
                    reply.writeNoException()
                    ControlParcel.writeAdmission(reply, admission)
                }
                SystemControlContract.STATE -> {
                    val session = ControlParcel.string(data, 80)
                    val epoch = ControlParcel.long(data)
                    val callback = ControlParcel.binder(data)
                    val state = ControlParcel.readState(data)
                    ControlParcel.end(data)
                    val admission = changeState(caller, session, epoch, callback, state)
                    reply.writeNoException()
                    ControlParcel.writeAdmission(reply, admission)
                }
                SystemControlContract.DEREGISTER -> {
                    val session = ControlParcel.string(data, 80)
                    val epoch = ControlParcel.long(data)
                    val callback = ControlParcel.binder(data)
                    ControlParcel.end(data)
                    val removed = deregister(caller, session, epoch, callback)
                    reply.writeNoException()
                    reply.writeInt(if (removed) 1 else 0)
                }
            }
            return true
        }
    }

    /** Root calls this with a verified OS snapshot; client STATE never writes this field. */
    fun observeForeground(value: ObservedForeground): Boolean {
        val states = synchronized(lock) {
            if (closed || value.epoch < foreground.epoch) return false
            if (value.epoch == foreground.epoch) {
                require(value == foreground) { "An OS scope change requires a fresh epoch" }
                return false
            }
            foreground = value
            clients.values.map { row ->
                row.pending = null
                row.revision = newRevision()
                row to admission(row)
            }
        }
        states.forEach { (row, state) -> publish(row, state) }
        return true
    }

    fun findReadyClient(packageName: String, observedOSforegroundEpoch: Long): ReadyClient? = synchronized(lock) {
        val row = clients[packageName] ?: return null
        if (!ready(row) || observedOSforegroundEpoch != foreground.epoch) return null
        ReadyClient(row.caller.packageName, row.caller.uid, controllerSession, row.clientEpoch,
            row.state.bindingsEpoch, foreground.epoch)
    }

    /** The durable router checks the exact partial lease before dispatching an app callback. */
    fun claimsInput(packageName: String, observedOSforegroundEpoch: Long, input: NormalizedKnobInput): Boolean = synchronized(lock) {
        val row = clients[packageName] ?: return false
        observedOSforegroundEpoch == foreground.epoch && ready(row) && input in row.state.claimedInputs
    }

    /**
     * Returns a single signed product that currently reports a focused, resumed window.
     *
     * The durable HELM host uses this only to publish its own fresh foreground epoch before
     * looking up a [ReadyClient].  Ambiguity is deliberately rejected: a stale app can never
     * inherit a later app's knob events merely because both processes are alive.
     */
    fun visibleSignedPackage(): String? = synchronized(lock) {
        if (closed) return null
        val values = clients.values.filter { row ->
            !row.dead.get() && row.state.resumed && row.state.focused &&
                row.caller.packageName in SystemControlContract.CLIENT_PACKAGES
        }
        values.singleOrNull()?.caller?.packageName
    }

    private fun prepare(envelope: KnobEnvelope): Registration? = synchronized(lock) {
            if (closed || envelope.controllerSession != controllerSession) return null
            val current = clients[foreground.packageName] ?: return null
            if (!matches(current, envelope) || current.pending != null ||
                envelope.sequence <= current.lastSequence || !fresh(envelope)) return null
            current.lastSequence = envelope.sequence
            current.pending = envelope // replay is consumed before crossing Binder
            current
    }

    /** Bounded synchronous form; UI owners should use dispatchAsync so same-process Main stays free. */
    fun dispatch(envelope: KnobEnvelope): ClientDisposition {
        val row = prepare(envelope) ?: return ClientDisposition.REFUSED
        val task = submit { deliver(row, envelope) }
        if (task == null) return finish(row, envelope, ClientDisposition.REFUSED)
        val result = try {
            task.get((envelope.expiresAtUptimeMs - clock()).coerceIn(1, 250), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) { ClientDisposition.REFUSED }
        catch (_: ExecutionException) { ClientDisposition.REFUSED }
        catch (_: java.util.concurrent.CancellationException) { ClientDisposition.REFUSED }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); ClientDisposition.REFUSED }
        ipc.cancel(task)
        return finish(row, envelope, result)
    }

    /** Exactly one result on Main. No Main wait and no additional target on refusal or timeout. */
    fun dispatchAsync(envelope: KnobEnvelope, onResult: (ClientDisposition) -> Unit) {
        val row = prepare(envelope)
        if (row == null) { resultHandler.post { onResult(ClientDisposition.REFUSED) }; return }
        val completed = AtomicBoolean(false)
        var task: FutureTask<ClientDisposition>? = null
        lateinit var timeout: Runnable
        lateinit var complete: (ClientDisposition) -> Unit
        complete = { disposition ->
            if (completed.compareAndSet(false, true)) {
                resultHandler.removeCallbacks(timeout)
                synchronized(lock) { completions.remove(complete) }
                task?.let(ipc::cancel)
                val value = finish(row, envelope, disposition)
                resultHandler.post {
                    val valid = synchronized(lock) { matches(row, envelope) && fresh(envelope) }
                    onResult(if (valid) value else ClientDisposition.REFUSED)
                }
            }
        }
        timeout = Runnable { complete(ClientDisposition.REFUSED) }
        synchronized(lock) { if (closed) { row.pending = null } else completions.add(complete) }
        if (synchronized(lock) { closed }) { complete(ClientDisposition.REFUSED); return }
        val delay = (envelope.expiresAtUptimeMs - clock()).coerceIn(1, 250)
        if (!resultHandler.postDelayed(timeout, delay)) { complete(ClientDisposition.REFUSED); return }
        task = submit { deliver(row, envelope).also(complete) }
        if (task == null) complete(ClientDisposition.REFUSED)
        // Completion/close can race with assignment; remove a then-late queued task as well.
        if (completed.get()) task?.let(ipc::cancel)
    }

    private fun deliver(row: Registration, envelope: KnobEnvelope): ClientDisposition {
        val state = synchronized(lock) {
            if (row.pending != envelope || !matches(row, envelope) || !fresh(envelope)) return ClientDisposition.REFUSED
            admission(row)
        }
        val result = try {
            // Repair a coalesced state whose earlier queue slot was busy; State is ordered before Input.
            CallbackRemote(row.callback).state(state)
            val valid = synchronized(lock) { row.pending == envelope && matches(row, envelope) && fresh(envelope) }
            if (!valid) return ClientDisposition.REFUSED
            CallbackRemote(row.callback).input(envelope)
        } catch (_: RemoteException) {
            retire(row)
            ClientDisposition.REFUSED
        } catch (_: RuntimeException) {
            retire(row)
            ClientDisposition.REFUSED
        }
        return result
    }

    private fun finish(row: Registration, envelope: KnobEnvelope, result: ClientDisposition): ClientDisposition =
        synchronized(lock) {
            val valid = row.pending == envelope && matches(row, envelope) && fresh(envelope)
            if (row.pending == envelope) row.pending = null
            if (valid) result else ClientDisposition.REFUSED
        }

    private fun fresh(value: KnobEnvelope): Boolean {
        val now = clock()
        return now >= value.issuedAtUptimeMs && now < value.expiresAtUptimeMs
    }

    private fun <T> submit(block: () -> T): FutureTask<T>? {
        val task = object : FutureTask<T>(Callable(block)) {
            override fun done() { synchronized(lock) { jobs.remove(this) } }
        }
        synchronized(lock) { if (closed) return null; jobs.add(task) }
        if (!ipc.execute(task)) { ipc.cancel(task); return null }
        if (task.isCancelled) ipc.cancel(task)
        return task
    }

    private fun register(caller: CallerIdentity, callback: IBinder, state: ClientState): ClientAdmission {
        val row = synchronized(lock) {
            check(!closed)
            check(nextClientEpoch < Long.MAX_VALUE)
            Registration(caller, callback, ++nextClientEpoch, state)
        }
        callback.linkToDeath(row.death, 0)
        var replaced: Registration? = null
        val result = try {
            synchronized(lock) {
                check(!closed && !row.dead.get() && callback.isBinderAlive)
                row.revision = newRevision()
                replaced = clients.put(caller.packageName, row)
                admission(row)
            }
        } catch (error: RuntimeException) {
            unlink(row)
            throw error
        }
        replaced?.let { old -> old.dead.set(true); unlink(old) }
        notifyClientStateChanged()
        return result
    }

    private fun changeState(caller: CallerIdentity, session: String, epoch: Long, callback: IBinder,
                            state: ClientState): ClientAdmission {
        val result = synchronized(lock) {
            val row = owned(caller, session, epoch, callback)
                ?: throw SecurityException("Stale control registration")
            require(state.bindingsEpoch >= row.state.bindingsEpoch) { "Bindings epoch regression" }
            row.state = state
            row.pending = null
            row.revision = newRevision()
            admission(row)
        }
        notifyClientStateChanged()
        return result
    }

    private fun deregister(caller: CallerIdentity, session: String, epoch: Long, callback: IBinder): Boolean {
        val row = synchronized(lock) {
            val owned = owned(caller, session, epoch, callback) ?: return false
            clients.remove(caller.packageName)
            owned.dead.set(true)
            owned.pending = null
            owned
        }
        unlink(row)
        notifyClientStateChanged()
        return true
    }

    private fun owned(caller: CallerIdentity, session: String, epoch: Long, callback: IBinder): Registration? {
        val row = clients[caller.packageName] ?: return null
        return row.takeIf { !closed && !it.dead.get() && it.caller == caller &&
            session == controllerSession && epoch == it.clientEpoch && callback == it.callback }
    }

    private fun ready(row: Registration): Boolean = !closed && !row.dead.get() &&
        clients[row.caller.packageName] === row && row.state.resumed && row.state.focused &&
        foreground.resumed && foreground.epoch > 0 && foreground.packageName == row.caller.packageName &&
        foreground.userId == hostUserId && foreground.displayId == 0

    private fun matches(row: Registration, value: KnobEnvelope): Boolean = ready(row) &&
        value.controllerSession == controllerSession && value.foregroundEpoch == foreground.epoch &&
        value.clientEpoch == row.clientEpoch && value.bindingsEpoch == row.state.bindingsEpoch

    private fun admission(row: Registration) = ClientAdmission(controllerSession, row.clientEpoch,
        row.state.bindingsEpoch, foreground.epoch, row.revision, ready(row))

    private fun newRevision(): Long { check(nextRevision < Long.MAX_VALUE); return ++nextRevision }

    private fun publish(row: Registration, value: ClientAdmission) {
        synchronized(lock) {
            if (closed || row.dead.get() || clients[row.caller.packageName] !== row) return
            row.outgoingState = value
            if (row.stateQueued) return
            row.stateQueued = true
        }
        val task = submit {
            val latest = synchronized(lock) {
                row.stateQueued = false
                val next = row.outgoingState
                row.outgoingState = null
                if (closed || row.dead.get() || clients[row.caller.packageName] !== row) null else next
            }
            if (latest != null) {
                try { CallbackRemote(row.callback).state(latest) }
                catch (_: RemoteException) { retire(row) }
                catch (_: RuntimeException) { retire(row) }
            }
        }
        if (task == null) synchronized(lock) { row.stateQueued = false }
    }

    private fun retire(row: Registration) {
        synchronized(lock) {
            row.dead.set(true)
            row.pending = null
            if (clients[row.caller.packageName] === row) clients.remove(row.caller.packageName)
        }
        unlink(row)
        notifyClientStateChanged()
    }

    /** Main-loop observer for a local durable host; Binder callers never receive this signal. */
    private fun notifyClientStateChanged() {
        resultHandler.post { if (!closed) onClientStateChanged() }
    }

    private fun unlink(row: Registration) {
        try { row.callback.unlinkToDeath(row.death, 0) } catch (_: RuntimeException) { /* already dead/unlinked */ }
    }

    override fun close() {
        val pendingCallbacks: List<(ClientDisposition) -> Unit>
        val pendingJobs: List<FutureTask<*>>
        val old = synchronized(lock) {
            if (closed) return
            closed = true
            pendingCallbacks = completions.toList()
            completions.clear()
            pendingJobs = jobs.toList()
            clients.values.toList().also { rows ->
                clients.clear()
                rows.forEach { it.dead.set(true); it.pending = null }
            }
        }
        pendingJobs.forEach(ipc::cancel)
        pendingCallbacks.forEach { it(ClientDisposition.REFUSED) }
        old.forEach(::unlink)
        notifyClientStateChanged()
    }
}
