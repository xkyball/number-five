package com.no5.control

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Activity lifecycle adapter. All public lifecycle methods and onInput use the main Looper. */
class SystemControlClient internal constructor(
    context: Context,
    private val onInput: (KnobEnvelope) -> ClientDisposition,
    private val handler: Handler,
    private val verifyService: () -> VerifiedService,
    private val callbackCallingUid: () -> Int,
    private val consumeTimeoutMillis: Long,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) : AutoCloseable {
    constructor(context: Context, onInput: (KnobEnvelope) -> ClientDisposition) : this(
        context, onInput, Handler(Looper.getMainLooper()), PackageServiceVerifier(context)::verify,
        Binder::getCallingUid, 250,
    )

    private val app = context.applicationContext
    private var resumed = false
    private var focused = false
    private var closed = false
    private var sourceGeneration = 0L
    private var currentBindingsEpoch = 1L
    private var claimedInputs: Set<NormalizedKnobInput> = emptySet()
    @Volatile private var binding: Binding? = null

    init {
        require(app.packageName in SystemControlContract.CLIENT_PACKAGES)
        require(handler.looper == Looper.getMainLooper())
        require(consumeTimeoutMillis in 1..250)
    }

    val bindingsEpoch: Long get() = currentBindingsEpoch

    fun onResume() { mainThread(); if (!closed) { resumed = true; reconcile() } }
    fun onPause() { mainThread(); resumed = false; reconcile() }
    fun onWindowFocusChanged(hasFocus: Boolean) {
        mainThread()
        if (!closed) { focused = hasFocus; reconcile() }
    }

    /** Advance before changing a screen/user binding; queued old callbacks are then refused. */
    fun updateBindingsEpoch(value: Long, inputs: Set<NormalizedKnobInput> = claimedInputs) {
        mainThread()
        require(value >= currentBindingsEpoch && value > 0)
        val exact = inputs.toSet()
        require(value > currentBindingsEpoch || exact == claimedInputs) { "Changed input lease requires a new bindings epoch" }
        if ((value == currentBindingsEpoch && exact == claimedInputs) || closed) return
        currentBindingsEpoch = value
        claimedInputs = exact
        val active = binding ?: return
        val previous = active.admission
        active.admission = null
        if (previous != null) {
            try {
                val next = requireNotNull(active.remote).state(previous.controllerSession,
                    previous.clientEpoch, active.callback, state())
                acceptAdmission(active, next)
            } catch (_: RemoteException) { disconnect(active) }
            catch (_: RuntimeException) { disconnect(active) }
        }
    }

    /** A health/lifecycle owner may retry a failed bind; no background polling loop is installed. */
    fun retryConnection() { mainThread(); reconcile() }

    private fun mainThread() { check(Looper.myLooper() == handler.looper) { "Control lifecycle must run on main" } }
    private fun state() = ClientState(currentBindingsEpoch, resumed, focused, claimedInputs)
    private fun current(row: Binding): Boolean = binding === row && row.alive &&
        row.generation == sourceGeneration && !closed && resumed && focused

    private fun reconcile() {
        if (closed || !resumed || !focused) { binding?.let(::disconnect); return }
        if (binding != null) return
        val verified = try { verifyService() } catch (_: RuntimeException) { return }
        check(sourceGeneration < Long.MAX_VALUE)
        val row = Binding(++sourceGeneration, verified.uid)
        binding = row
        try {
            row.bound = app.bindService(Intent().setComponent(SystemControlContract.SERVICE),
                row.connection, Context.BIND_AUTO_CREATE)
            if (!row.bound) disconnect(row)
        } catch (_: RuntimeException) { disconnect(row) }
    }

    private inner class Binding(val generation: Long, val serverUid: Int) {
        @Volatile var alive = true
        @Volatile var admission: ClientAdmission? = null
        var registrationIdentity: Pair<String, Long>? = null
        var lastAdmissionRevision = 0L
        var lastSequence = 0L
        val receivedSequence = AtomicLong(0)
        var server: IBinder? = null
        var remote: ControlRemote? = null
        var bound = false
        val death = IBinder.DeathRecipient {
            alive = false // refuse queued work immediately, even while the main Looper is busy
            handler.post { disconnect(this) }
        }
        val callback: IBinder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == IBinder.INTERFACE_TRANSACTION) {
                    reply?.writeString(SystemControlContract.CALLBACK_DESCRIPTOR)
                    return true
                }
                if (code != SystemControlContract.CALLBACK_STATE && code != SystemControlContract.CALLBACK_INPUT) return false
                if (callbackCallingUid() != serverUid) throw SecurityException("Unexpected control server UID")
                requireNotNull(reply)
                require(flags and IBinder.FLAG_ONEWAY == 0)
                ControlParcel.checkSize(data)
                data.enforceInterface(SystemControlContract.CALLBACK_DESCRIPTOR)
                when (code) {
                    SystemControlContract.CALLBACK_STATE -> {
                        val value = ControlParcel.readAdmission(data)
                        ControlParcel.end(data)
                        val accepted = current(this@Binding) && handler.post { acceptAdmission(this@Binding, value) }
                        reply.writeNoException()
                        reply.writeInt(if (accepted) 1 else 0)
                    }
                    SystemControlContract.CALLBACK_INPUT -> {
                        val value = ControlParcel.readEnvelope(data)
                        ControlParcel.end(data)
                        val result = consumeOnMain(this@Binding, value)
                        reply.writeNoException()
                        reply.writeInt(result.wireCode)
                    }
                }
                return true
            }
        }
        val connection: ServiceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                mainThread()
                if (!current(this@Binding) || name != SystemControlContract.SERVICE) {
                    disconnect(this@Binding)
                    return
                }
                try {
                    if (verifyService().uid != serverUid) throw SecurityException("Control service UID changed")
                    server = service
                    service.linkToDeath(death, 0)
                    remote = ControlRemote(service)
                    val first = requireNotNull(remote).register(app.packageName, callback, state())
                    require(first.bindingsEpoch == currentBindingsEpoch)
                    registrationIdentity = first.controllerSession to first.clientEpoch
                    acceptAdmission(this@Binding, first)
                } catch (_: RemoteException) { disconnect(this@Binding) }
                catch (_: RuntimeException) { disconnect(this@Binding) }
            }
            override fun onServiceDisconnected(name: ComponentName) { disconnect(this@Binding) }
            override fun onBindingDied(name: ComponentName) { disconnect(this@Binding) }
            override fun onNullBinding(name: ComponentName) { disconnect(this@Binding) }
        }
    }

    private fun acceptAdmission(row: Binding, value: ClientAdmission) {
        mainThread()
        if (!current(row) || value.bindingsEpoch != currentBindingsEpoch ||
            value.revision <= row.lastAdmissionRevision) return
        val identity = value.controllerSession to value.clientEpoch
        val prior = row.registrationIdentity ?: return // only REGISTER reply establishes this identity
        if (prior != identity) { disconnect(row); return }
        row.lastAdmissionRevision = value.revision
        row.admission = value
    }

    private fun consume(row: Binding, value: KnobEnvelope): ClientDisposition {
        mainThread()
        val scope = row.admission ?: return ClientDisposition.REFUSED
        if (!fresh(value) || !current(row) || !scope.ready || scope.controllerSession != value.controllerSession ||
            scope.clientEpoch != value.clientEpoch || scope.foregroundEpoch != value.foregroundEpoch ||
            scope.bindingsEpoch != value.bindingsEpoch || value.bindingsEpoch != currentBindingsEpoch ||
            value.sequence <= row.lastSequence) return ClientDisposition.REFUSED
        row.lastSequence = value.sequence // a refusal/exception must also consume this replay token
        val result = try { onInput(value) } catch (_: RuntimeException) { ClientDisposition.REFUSED }
        // The handler itself may navigate/change bindings/close. Its late disposition is then void.
        return if (fresh(value) && current(row) && row.admission == scope && currentBindingsEpoch == value.bindingsEpoch)
            result else ClientDisposition.REFUSED
    }

    private fun consumeOnMain(row: Binding, value: KnobEnvelope): ClientDisposition {
        val scope = row.admission ?: return ClientDisposition.REFUSED
        if (!fresh(value) || !current(row) || !scope.ready || value.controllerSession != scope.controllerSession ||
            value.clientEpoch != scope.clientEpoch || value.foregroundEpoch != scope.foregroundEpoch ||
            value.bindingsEpoch != scope.bindingsEpoch) return ClientDisposition.REFUSED
        while (true) {
            val received = row.receivedSequence.get()
            if (value.sequence <= received) return ClientDisposition.REFUSED
            if (row.receivedSequence.compareAndSet(received, value.sequence)) break
        }
        if (Looper.myLooper() == handler.looper) return consume(row, value)
        if (!current(row)) return ClientDisposition.REFUSED
        val job = MainConsume { consume(row, value) }
        if (!handler.post(job)) return ClientDisposition.REFUSED
        return job.await(minOf(consumeTimeoutMillis, value.expiresAtUptimeMs - clock()).coerceAtLeast(1))
    }

    private fun fresh(value: KnobEnvelope): Boolean {
        val now = clock()
        return now >= value.issuedAtUptimeMs && now < value.expiresAtUptimeMs
    }

    /** Timeout cancels only work that has not started. Started work never causes a second target. */
    private class MainConsume(private val action: () -> ClientDisposition) : Runnable {
        private val phase = AtomicInteger(0) // queued=0, running=1, completed=2, cancelled=3
        private val done = CountDownLatch(1)
        @Volatile private var result = ClientDisposition.REFUSED
        override fun run() {
            if (!phase.compareAndSet(0, 1)) return
            try { result = action() }
            finally { phase.set(2); done.countDown() }
        }
        fun await(timeoutMillis: Long): ClientDisposition {
            try {
                if (done.await(timeoutMillis, TimeUnit.MILLISECONDS)) return result
            } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            phase.compareAndSet(0, 3)
            return ClientDisposition.REFUSED
        }
    }

    private fun disconnect(row: Binding) {
        mainThread()
        row.alive = false
        val prior = row.admission
        row.admission = null
        if (binding === row) binding = null
        try {
            if (prior != null) row.remote?.deregister(prior.controllerSession, prior.clientEpoch, row.callback)
        } catch (_: RemoteException) { /* service gone */ }
        catch (_: RuntimeException) { /* stale registration; local generation is already invalid */ }
        row.server?.let { server ->
            try { server.unlinkToDeath(row.death, 0) } catch (_: RuntimeException) { /* already dead */ }
        }
        row.server = null
        row.remote = null
        if (row.bound) {
            row.bound = false
            try { app.unbindService(row.connection) } catch (_: RuntimeException) { /* already disconnected */ }
        }
    }

    override fun close() { mainThread(); closed = true; resumed = false; focused = false; binding?.let(::disconnect) }
}
