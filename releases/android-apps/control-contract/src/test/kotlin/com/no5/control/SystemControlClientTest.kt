package com.no5.control

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SystemControlClientTest {
    private class BindingContext(private val productPackage: String = "com.no5.home") : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val connections = mutableListOf<ServiceConnection>()
        var unbound = 0
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = productPackage
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            assertEquals(SystemControlContract.SERVICE, service.component)
            connections += connection
            return true
        }
        override fun unbindService(connection: ServiceConnection) { unbound++ }
        fun connect(service: IBinder) { connections.last().onServiceConnected(SystemControlContract.SERVICE, service) }
    }

    private class Service : Binder() {
        lateinit var callback: IBinder
        lateinit var admission: ClientAdmission
        var deregisters = 0
        var registrations = 0L
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            requireNotNull(reply)
            data.enforceInterface(SystemControlContract.SERVICE_DESCRIPTOR)
            ControlParcel.version(data)
            when (code) {
                SystemControlContract.REGISTER -> {
                    assertEquals("com.no5.home", ControlParcel.string(data, 128))
                    callback = ControlParcel.binder(data)
                    val state = ControlParcel.readState(data)
                    admission = ClientAdmission("server-session", ++registrations, state.bindingsEpoch, 1, 1, true)
                    ControlParcel.end(data); reply.writeNoException(); ControlParcel.writeAdmission(reply, admission)
                }
                SystemControlContract.STATE -> {
                    assertEquals(admission.controllerSession, ControlParcel.string(data, 80))
                    assertEquals(admission.clientEpoch, ControlParcel.long(data))
                    assertEquals(callback, ControlParcel.binder(data))
                    val state = ControlParcel.readState(data)
                    admission = admission.copy(bindingsEpoch = state.bindingsEpoch,
                        revision = admission.revision + 1, ready = state.resumed && state.focused)
                    ControlParcel.end(data); reply.writeNoException(); ControlParcel.writeAdmission(reply, admission)
                }
                SystemControlContract.DEREGISTER -> {
                    ControlParcel.string(data, 80); ControlParcel.long(data); ControlParcel.binder(data)
                    ControlParcel.end(data); deregisters++; reply.writeNoException(); reply.writeInt(1)
                }
                else -> return false
            }
            return true
        }
        fun envelope(sequence: Long = 1) = KnobEnvelope(controllerSession = admission.controllerSession,
            sequence = sequence, foregroundEpoch = admission.foregroundEpoch, clientEpoch = admission.clientEpoch,
            bindingsEpoch = admission.bindingsEpoch, input = NormalizedKnobInput.RIGHT_PUSH)
    }

    private fun client(context: BindingContext, serverUid: () -> Int = { 20002 }, timeout: Long = 250,
                       consume: (KnobEnvelope) -> ClientDisposition) = SystemControlClient(context, consume,
        Handler(Looper.getMainLooper()), { VerifiedService(20002) }, serverUid, timeout)

    private fun activate(client: SystemControlClient, context: BindingContext, server: IBinder) {
        client.onResume(); client.onWindowFocusChanged(true); context.connect(server)
    }

    @Test fun bindsOnlyResumedAndFocusedAndPauseUnregisters() {
        val context = BindingContext()
        val service = Service()
        client(context) { ClientDisposition.HANDLED }.use { client ->
            client.onResume(); assertTrue(context.connections.isEmpty())
            client.onWindowFocusChanged(true); assertEquals(1, context.connections.size)
            context.connect(service)
            client.onPause(); assertEquals(1, service.deregisters); assertEquals(1, context.unbound)
            client.onResume(); assertEquals(2, context.connections.size)
            context.connect(service)
            client.onWindowFocusChanged(false); assertEquals(2, context.unbound)
        }
    }

    @Test fun consumesExactlyOnceAndRejectsOldSessionScopeAndBindings() {
        val context = BindingContext(); val service = Service(); var count = 0
        client(context) { count++; ClientDisposition.AUDIO_DEFAULT }.use { client ->
            activate(client, context, service)
            val remote = CallbackRemote(service.callback); val value = service.envelope()
            assertEquals(ClientDisposition.AUDIO_DEFAULT, remote.input(value))
            assertEquals(ClientDisposition.REFUSED, remote.input(value))
            assertEquals(ClientDisposition.REFUSED, remote.input(value.copy(sequence = 2, controllerSession = "old")))
            assertEquals(ClientDisposition.REFUSED, remote.input(value.copy(sequence = 2, foregroundEpoch = 2)))
            client.updateBindingsEpoch(2)
            assertEquals(ClientDisposition.REFUSED, remote.input(value.copy(sequence = 2)))
            assertEquals(ClientDisposition.AUDIO_DEFAULT, remote.input(service.envelope(2)))
            assertEquals(2, count)
        }
    }

    @Test fun oldConnectionCallbackAndStateCannotReachNewClientGeneration() {
        val context = BindingContext(); val service = Service(); var count = 0
        client(context) { count++; ClientDisposition.HANDLED }.use { client ->
            activate(client, context, service)
            val old = CallbackRemote(service.callback); val value = service.envelope()
            client.onPause(); client.onResume(); context.connect(service)
            assertEquals(ClientDisposition.REFUSED, old.input(value))
            assertFalse(old.state(service.admission))
            assertEquals(ClientDisposition.HANDLED, CallbackRemote(service.callback).input(service.envelope()))
            assertEquals(1, count)
        }
    }

    @Test fun callbackRequiresExpectedHelmUID() {
        val context = BindingContext(); val service = Service(); var uid = 20002; var count = 0
        client(context, { uid }) { count++; ClientDisposition.HANDLED }.use { client ->
            activate(client, context, service)
            uid = 30003
            assertThrows(SecurityException::class.java) { CallbackRemote(service.callback).input(service.envelope()) }
            assertEquals(0, count)
        }
    }

    @Test fun staleAdmissionRevisionCannotUndoRevocation() {
        val context = BindingContext(); val service = Service(); var count = 0
        client(context) { count++; ClientDisposition.HANDLED }.use { client ->
            activate(client, context, service)
            val remote = CallbackRemote(service.callback)
            remote.state(service.admission.copy(revision = 2, ready = false))
            remote.state(service.admission)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(ClientDisposition.REFUSED, remote.input(service.envelope()))
            assertEquals(0, count)
        }
    }

    @Test fun queuedTimeoutDropsActionEvenWhenMainLooperRunsLater() {
        val context = BindingContext(); val service = Service(); var count = 0
        client(context, timeout = 30) { count++; ClientDisposition.HANDLED }.use { client ->
            activate(client, context, service)
            val remote = CallbackRemote(service.callback); val value = service.envelope()
            val result = AtomicReference<ClientDisposition>()
            val worker = Thread { result.set(remote.input(value)) }.also { it.start() }
            worker.join(1000); assertFalse(worker.isAlive)
            assertEquals(ClientDisposition.REFUSED, result.get())
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, count)
        }
    }

    @Test fun startedTimeoutReturnsRefusedAndDoesNotExecuteAgain() {
        val context = BindingContext(); val service = Service(); var count = 0
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        client(context, timeout = 30) {
            count++; started.countDown(); check(release.await(1, TimeUnit.SECONDS)); ClientDisposition.AUDIO_DEFAULT
        }.use { client ->
            activate(client, context, service)
            val remote = CallbackRemote(service.callback); val value = service.envelope()
            val result = AtomicReference<ClientDisposition>()
            val worker = Thread { result.set(remote.input(value)) }.also { it.start() }
            val releaser = Thread {
                check(started.await(1, TimeUnit.SECONDS)); worker.join(1000); release.countDown()
            }.also { it.start() }
            // Worker may not have posted yet. Idle until the actual action starts, within a bound.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (started.count > 0 && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.yield()
            }
            worker.join(1000); releaser.join(1000)
            assertEquals(ClientDisposition.REFUSED, result.get())
            assertEquals(1, count)
            assertEquals(ClientDisposition.REFUSED, remote.input(value))
            assertEquals(1, count)
        }
    }

    @Test fun serverDeathImmediatelyInvalidatesCallbacksAndAllowsExplicitFreshRetry() {
        val context = BindingContext(); val service = Service(); var count = 0
        val server = DeathBinder(service)
        client(context) { count++; ClientDisposition.HANDLED }.use { client ->
            activate(client, context, server)
            val old = CallbackRemote(service.callback); val oldEnvelope = service.envelope()
            server.die()
            assertEquals(ClientDisposition.REFUSED, old.input(oldEnvelope))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, context.unbound)
            client.retryConnection(); assertEquals(2, context.connections.size)
            context.connect(service)
            assertEquals(ClientDisposition.HANDLED, CallbackRemote(service.callback).input(service.envelope()))
            assertEquals(1, count)
        }
    }

    @Test fun consumerNavigationInvalidatesLateAudioDisposition() {
        val context = BindingContext(); val service = Service()
        lateinit var current: SystemControlClient
        current = client(context) { current.updateBindingsEpoch(2); ClientDisposition.AUDIO_DEFAULT }
        current.use { client ->
            activate(client, context, service)
            val value = service.envelope()
            assertEquals(ClientDisposition.REFUSED, CallbackRemote(service.callback).input(value))
        }
    }

    @Test fun expiredCallbackCannotEnterConsumerBeforeOrAfterMainQueue() {
        val context = BindingContext(); val service = Service(); val now = AtomicLong(10); var count = 0
        var expireOnMain = false
        val clock = { if (expireOnMain && Looper.myLooper() == Looper.getMainLooper()) 50L else now.get() }
        SystemControlClient(context, { count++; ClientDisposition.HANDLED }, Handler(Looper.getMainLooper()),
            { VerifiedService(20002) }, { 20002 }, 250, clock).use { client ->
            activate(client, context, service)
            val remote = CallbackRemote(service.callback)
            val value = service.envelope().copy(issuedAtUptimeMs = 10, expiresAtUptimeMs = 50)
            now.set(50)
            assertEquals(ClientDisposition.REFUSED, remote.input(value))
            assertEquals(0, count)
            now.set(10)
            expireOnMain = true // Binder's prequeue clock is fresh; Main's preconsumer clock has expired.
            val result = AtomicReference<ClientDisposition>()
            val worker = Thread { result.set(remote.input(value.copy(sequence = 2))) }
                .also { it.start() }
            val wallDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (result.get() == null && System.nanoTime() < wallDeadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.yield()
            }
            worker.join(1000); assertFalse(worker.isAlive)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(ClientDisposition.REFUSED, result.get()); assertEquals(0, count)
        }
    }

    @Test fun actualSameMainHelmClientReceivesAsyncDispositionExactlyOnce() {
        listOf(ClientDisposition.HANDLED, ClientDisposition.AUDIO_DEFAULT).forEach { expected ->
            BoundedControlIpc().use { pool ->
                val context = BindingContext("com.no5.helm"); var count = 0
                val now = AtomicLong(10)
                ControlBinderHost("local-helm", 0,
                    { uid -> CallerAdmissionPolicy.verify(uid, 0, listOf("com.no5.helm"), true, true) },
                    { 20002 }, ipc = pool, clock = now::get).use { host ->
                    host.observeForeground(ObservedForeground("com.no5.helm", 1, true, 0))
                    SystemControlClient(context, {
                        assertEquals(Looper.getMainLooper(), Looper.myLooper())
                        count++; expected
                    }, Handler(Looper.getMainLooper()), { VerifiedService(20002) }, { 20002 },
                        250, now::get).use { client ->
                        activate(client, context, host.binder)
                        val ready = requireNotNull(host.findReadyClient("com.no5.helm", 1))
                        val envelope = KnobEnvelope(controllerSession = ready.controllerSession, sequence = 1,
                            foregroundEpoch = ready.foregroundEpoch, clientEpoch = ready.clientEpoch,
                            bindingsEpoch = ready.bindingsEpoch, input = NormalizedKnobInput.LEFT_CW,
                            issuedAtUptimeMs = 10, expiresAtUptimeMs = 250)
                        val results = mutableListOf<ClientDisposition>()
                        host.dispatchAsync(envelope) {
                            assertEquals(Looper.getMainLooper(), Looper.myLooper())
                            results.add(it)
                        }
                        // Host returns without waiting on this Main, which can now consume its callback.
                        assertTrue(results.isEmpty())
                        val wallDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                        while (results.isEmpty() && System.nanoTime() < wallDeadline) {
                            Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.yield()
                        }
                        assertEquals(listOf(expected), results); assertEquals(1, count)
                        host.dispatchAsync(envelope) { results.add(it) }
                        Shadows.shadowOf(Looper.getMainLooper()).idle()
                        assertEquals(listOf(expected, ClientDisposition.REFUSED), results)
                        assertEquals(1, count)
                    }
                }
            }
        }
    }
}
