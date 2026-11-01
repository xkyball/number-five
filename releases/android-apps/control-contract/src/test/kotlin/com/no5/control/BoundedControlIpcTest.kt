package com.no5.control

import android.os.Binder
import android.os.Parcel
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BoundedControlIpcTest {
    private class HangingStateCallback : Binder() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val inputs = AtomicInteger()
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            requireNotNull(reply)
            data.enforceInterface(SystemControlContract.CALLBACK_DESCRIPTOR)
            when (code) {
                SystemControlContract.CALLBACK_STATE -> {
                    ControlParcel.readAdmission(data); ControlParcel.end(data)
                    entered.countDown(); check(release.await(2, TimeUnit.SECONDS))
                    reply.writeNoException(); reply.writeInt(1)
                }
                SystemControlContract.CALLBACK_INPUT -> {
                    ControlParcel.readEnvelope(data); ControlParcel.end(data); inputs.incrementAndGet()
                    reply.writeNoException(); reply.writeInt(ClientDisposition.AUDIO_DEFAULT.wireCode)
                }
                else -> return false
            }
            return true
        }
    }

    private fun host(pool: BoundedControlIpc, clock: AtomicLong) = ControlBinderHost("ipc-session", 0,
        { uid -> CallerAdmissionPolicy.verify(uid, 0, listOf("com.no5.home"), true, true) },
        { 10001 }, ipc = pool, clock = clock::get)

    private fun envelope(ack: ClientAdmission) = KnobEnvelope(controllerSession = ack.controllerSession,
        sequence = 1, foregroundEpoch = 1, clientEpoch = ack.clientEpoch, bindingsEpoch = 1,
        input = NormalizedKnobInput.LEFT_CW, issuedAtUptimeMs = 0, expiresAtUptimeMs = 100)

    private fun drain(pool: BoundedControlIpc) {
        val barrier = FutureTask(Callable { true })
        assertTrue(pool.execute(barrier)); assertTrue(barrier.get(1, TimeUnit.SECONDS))
    }

    @Test fun hungStatePublishDoesNotBlockMainAndDeadlineRemovesLateQueuedInput() {
        BoundedControlIpc().use { pool ->
            val now = AtomicLong(0); val callback = HangingStateCallback()
            host(pool, now).use { host ->
                val ack = ControlRemote(host.binder).register("com.no5.home", callback, ClientState(1, true, true))
                assertTrue(host.observeForeground(ObservedForeground("com.no5.home", 1, true, 0)))
                assertTrue(callback.entered.await(1, TimeUnit.SECONDS))
                val results = mutableListOf<ClientDisposition>()
                host.dispatchAsync(envelope(ack)) { results.add(it) }
                assertTrue(results.isEmpty())
                now.set(100)
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                assertEquals(listOf(ClientDisposition.REFUSED), results)
                callback.release.countDown(); drain(pool)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(0, callback.inputs.get())
                assertEquals(1, results.size)
            }
        }
    }

    @Test fun closeCompletesOnceWithoutExecutingQueuedAction() {
        BoundedControlIpc().use { pool ->
            val now = AtomicLong(0); val callback = HangingStateCallback(); val host = host(pool, now)
            val ack = ControlRemote(host.binder).register("com.no5.home", callback, ClientState(1, true, true))
            host.observeForeground(ObservedForeground("com.no5.home", 1, true, 0))
            assertTrue(callback.entered.await(1, TimeUnit.SECONDS))
            val results = mutableListOf<ClientDisposition>()
            host.dispatchAsync(envelope(ack)) { results.add(it) }
            host.close()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(ClientDisposition.REFUSED), results)
            now.set(200); Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            callback.release.countDown(); drain(pool)
            assertEquals(0, callback.inputs.get()); assertEquals(1, results.size)
        }
    }

    @Test fun boundedFullQueueRefusesInsteadOfStartingAnotherWorker() {
        BoundedControlIpc().use { pool ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val blocker = FutureTask(Callable { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); true })
            assertTrue(pool.execute(blocker)); assertTrue(entered.await(1, TimeUnit.SECONDS))
            val queued = (1..3).map { FutureTask(Callable { true }) }
            queued.forEach { assertTrue(pool.execute(it)) }
            val overflow = FutureTask(Callable { fail("overflow task must not run"); false })
            assertFalse(pool.execute(overflow))
            val now = AtomicLong(0); val callback = HangingStateCallback().also { it.release.countDown() }
            host(pool, now).use { host ->
                val ack = ControlRemote(host.binder).register("com.no5.home", callback, ClientState(1, true, true))
                host.observeForeground(ObservedForeground("com.no5.home", 1, true, 0))
                val results = mutableListOf<ClientDisposition>()
                host.dispatchAsync(envelope(ack)) { results.add(it) }
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(listOf(ClientDisposition.REFUSED), results)
                assertEquals(0, callback.inputs.get())
            }
            queued.forEach(pool::cancel); release.countDown(); assertTrue(blocker.get(1, TimeUnit.SECONDS))
        }
    }
}
