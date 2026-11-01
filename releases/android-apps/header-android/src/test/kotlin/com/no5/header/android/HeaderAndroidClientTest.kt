package com.no5.header.android

import android.content.*
import android.os.*
import com.no5.control.*
import com.no5.header.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class HeaderAndroidClientTest {
    private class BindingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val connections = mutableListOf<ServiceConnection>()
        var unbound = 0
        override fun getApplicationContext(): Context = this
        override fun getPackageName() = HeaderApps.HELM
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            assertEquals(HeaderAndroidContract.BIND_ACTION, service.action)
            assertEquals(SystemControlContract.SERVICE, service.component)
            connections.add(connection); return true
        }
        override fun unbindService(connection: ServiceConnection) { unbound++ }
        fun connect(service: IBinder) { connections.last().onServiceConnected(SystemControlContract.SERVICE, service) }
    }
    private val ipc = BoundedControlIpc()
    private val clock = HeaderFixtureClock()
    private val clients = mutableListOf<HeaderAndroidClient>()
    private val hosts = mutableListOf<HeaderBinderHost>()
    private val context = BindingContext()
    private val admissions = mutableListOf<HeaderAdmission?>()
    private var callbackUid = 20002
    private fun client() = HeaderAndroidClient(context, HeaderPage.HELM,
        { assertEquals(Looper.getMainLooper(), Looper.myLooper()); admissions.add(it) },
        Handler(Looper.getMainLooper()), { VerifiedService(20002) }, { callbackUid }, clock, ipc)
        .also { clients.add(it) }
    private fun host() = HeaderBinderHost("local-helm", 0,
        { CallerAdmissionPolicy.verify(it, 0, listOf(HeaderApps.HELM), true, true) }, { 10001 }, { 111 },
        clock, Handler(Looper.getMainLooper()), ipc, { assertEquals(Looper.getMainLooper(), Looper.myLooper()) })
        .also { hosts.add(it); it.observeForeground(ObservedForeground(HeaderApps.HELM, 1, true, 0)) }
    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle(); assertTrue("Bounded asynchronous operation did not finish", condition())
    }
    private fun activate(client: HeaderAndroidClient, binder: IBinder) {
        client.onResume(); assertTrue(context.connections.isEmpty())
        client.onWindowFocusChanged(true); context.connect(binder)
        pumpUntil { client.captureScope() != null }
    }
    private fun frame(client: HeaderAndroidClient, scope: HeaderAdmission, title: String) = HeaderSnapshotV1(
        requireNotNull(client.nextPublication(scope)), scope.pageId, HeaderTheme.RED_NIGHT,
        requireNotNull(HeaderApps.profile(HeaderApps.HELM, scope.pageId)), HeaderContext(title))
    @After fun cleanup() {
        clients.forEach { it.close() }; hosts.forEach { it.close() }
        shadowOf(Looper.getMainLooper()).idle(); ipc.close()
    }

    @Test fun actualSameMainHelmClientRegistersAndPublishesAsynchronouslyWithoutAnotherSource() {
        val host = host(); val client = client(); activate(client, host.binder)
        val scope = requireNotNull(client.captureScope())
        val results = mutableListOf<HeaderPublishResult>()
        val snapshot = frame(client, scope, "HELM live")
        assertTrue(client.publish(scope, snapshot) {
            assertEquals(Looper.getMainLooper(), Looper.myLooper()); results.add(it)
        })
        assertTrue(results.isEmpty()) // Main did not synchronously wait for the service's Binder.
        pumpUntil { results.isNotEmpty() }
        assertEquals(listOf(HeaderPublishResult(true, HeaderClearReason.NONE)), results)
        assertEquals(snapshot, host.current().snapshot)
        assertEquals(1, context.connections.size)
    }

    @Test fun pageAndSourceSwitchRetireCapturedWorkWithoutErasingNewerFrame() {
        val host = host(); val client = client(); activate(client, host.binder)
        val original = requireNotNull(client.captureScope()); val oldFrame = frame(client, original, "old")
        client.onPageSourceChanged(HeaderPage.AUDIO, 2, "source-a")
        assertNull(client.captureScope())
        pumpUntil { client.captureScope()?.sourceSessionId == "source-a" }
        val sourceA = requireNotNull(client.captureScope()); val lateA = frame(client, sourceA, "late source a")
        client.onPageSourceChanged(HeaderPage.AUDIO, 3, "source-b")
        pumpUntil { client.captureScope()?.sourceSessionId == "source-b" }
        val sourceB = requireNotNull(client.captureScope())
        val results = mutableListOf<HeaderPublishResult>()
        assertTrue(client.publish(sourceB, frame(client, sourceB, "source b")) { results.add(it) })
        pumpUntil { results.isNotEmpty() }; assertTrue(results.single().accepted)
        assertFalse(client.publish(original, oldFrame) { results.add(it) })
        assertFalse(client.publish(sourceA, lateA) { results.add(it) })
        assertNull(client.nextPublication(sourceA))
        assertEquals("source b", host.current().snapshot!!.context.title)
        assertThrows(IllegalArgumentException::class.java) { client.onPageSourceChanged(HeaderPage.SONAR, 3, "source-b") }
    }

    @Test fun pauseCloseAndRegainedForegroundIssueNewProducerAndDoNotRetagData() {
        val host = host(); val client = client(); activate(client, host.binder)
        val initial = requireNotNull(client.captureScope()); val oldFrame = frame(client, initial, "first")
        val result = mutableListOf<HeaderPublishResult>()
        client.publish(initial, oldFrame) { result.add(it) }; pumpUntil { result.isNotEmpty() }
        client.onPause(); assertNull(client.captureScope()); assertEquals(1, context.unbound)
        pumpUntil { host.current().snapshot == null }
        host.observeForeground(ObservedForeground(HeaderApps.HELM, 2, true, 0))
        client.onResume(); assertEquals(2, context.connections.size); context.connect(host.binder)
        pumpUntil { client.captureScope()?.foregroundEpoch == 2L }
        val resumed = requireNotNull(client.captureScope())
        assertNotEquals(initial.producerSession, resumed.producerSession)
        assertTrue(resumed.processGeneration > initial.processGeneration)
        assertFalse(client.publish(initial, oldFrame))
        host.observeForeground(ObservedForeground(HeaderApps.HELM, 3, true, 0))
        client.retryAdmission()
        pumpUntil { client.captureScope()?.foregroundEpoch == 3L }
        val fresh = requireNotNull(client.captureScope())
        result.clear(); client.publish(fresh, frame(client, fresh, "current")) { result.add(it) }
        pumpUntil { result.isNotEmpty() }; assertTrue(result.single().accepted)
        client.close(); pumpUntil { host.current().snapshot == null }; assertNull(client.captureScope())
        assertEquals(2, context.unbound)
    }

    private class RecordingService : Binder() {
        lateinit var callback: IBinder
        lateinit var grant: HeaderAdmission
        val publishing = CountDownLatch(1)
        val release = CountDownLatch(1)
        var blockPublishing = false
        var deregisters = 0
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            requireNotNull(reply); data.enforceInterface(HeaderAndroidContract.DESCRIPTOR)
            when (code) {
                HeaderAndroidContract.REGISTER -> {
                    HeaderParcel.version(data); assertEquals(HeaderApps.HELM, HeaderParcel.string(data, 128))
                    callback = HeaderParcel.binder(data); val page = HeaderParcel.readPage(data); HeaderParcel.end(data)
                    grant = HeaderAdmission("controller", "producer", 1, "scope", 1, page.pageId,
                        page.pageGeneration, page.sourceSessionId, 1, true)
                    reply.writeNoException(); HeaderParcel.writeAdmission(reply, grant)
                }
                HeaderAndroidContract.PUBLISH -> {
                    assertEquals(grant, HeaderParcel.readAdmission(data)); HeaderParcel.readSnapshot(data); HeaderParcel.end(data)
                    publishing.countDown()
                    if (blockPublishing) check(release.await(2, TimeUnit.SECONDS))
                    reply.writeNoException(); HeaderParcel.writeResult(reply, HeaderPublishResult(true, HeaderClearReason.NONE))
                }
                HeaderAndroidContract.DEREGISTER -> {
                    HeaderParcel.readAdmission(data); HeaderParcel.binder(data); HeaderParcel.end(data); deregisters++
                    reply.writeNoException(); HeaderParcel.boolean(reply, true)
                }
                else -> return false
            }
            return true
        }
    }

    @Test fun callbackKernelUidIsCheckedBeforeMalformedParcelIsRead() {
        val server = RecordingService(); val client = client(); activate(client, server)
        callbackUid = 30003
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            val before = client.captureScope()
            assertTrue(server.callback.transact(HeaderAndroidContract.CALLBACK_STATE, data, reply, 0))
            val denial = assertThrows(SecurityException::class.java) { reply.readException() }
            assertEquals("Header callback UID refused", denial.message)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(before, client.captureScope())
            assertEquals(0, data.dataPosition()); assertNotNull(client.captureScope())
        } finally { data.recycle(); reply.recycle() }
    }

    @Test fun blockedRemotePublishLeavesMainResponsiveAndHasOneBoundedResult() {
        val server = RecordingService().also { it.blockPublishing = true }
        val client = client(); activate(client, server)
        val scope = requireNotNull(client.captureScope()); val results = mutableListOf<HeaderPublishResult>()
        try {
            assertTrue(client.publish(scope, frame(client, scope, "bounded")) { results.add(it) })
            assertTrue(server.publishing.await(1, TimeUnit.SECONDS))
            var marker = false; Handler(Looper.getMainLooper()).post { marker = true }
            shadowOf(Looper.getMainLooper()).idle(); assertTrue(marker); assertTrue(results.isEmpty())
            // Even nextPublication uses no monitor held by the blocked remote publish.
            assertNotNull(client.nextPublication(scope))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(251))
            pumpUntil { results.isNotEmpty() }; assertFalse(results.single().accepted)
            server.release.countDown(); Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, results.size)
        } finally { server.release.countDown() }
    }
}
