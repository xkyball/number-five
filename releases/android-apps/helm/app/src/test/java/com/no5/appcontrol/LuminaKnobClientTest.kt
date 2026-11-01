package com.no5.appcontrol

import android.app.Application
import android.content.pm.*
import android.os.*
import com.no5.control.ClientDisposition
import com.no5.control.NormalizedKnobInput
import com.no5.gateway.LuminaGatewayClient
import com.no5.lumina.control.KnobLeaseProtocol as Wire
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowBinder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class LuminaKnobClientTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private class Controller : Binder() {
        @Volatile var session = ""
        @Volatile var id = ""
        @Volatile var mask = 0
        @Volatile var callback: IBinder? = null
        val releases = AtomicInteger()
        val requests = AtomicInteger()
        @Volatile var denyLease = false
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(Wire.DESCRIPTOR)
            when (code) {
                Wire.REQUEST_LEASE -> {
                    requests.incrementAndGet()
                    if (denyLease) {
                        reply!!.writeNoException(); reply.writeInt(Wire.RESULT_UNAVAILABLE)
                        reply.writeString("FOCUS_NOT_VISIBLE"); return true
                    }
                    session = data.readString()!!; mask = data.readInt(); callback = data.readStrongBinder()
                    assertEquals(0, data.dataAvail()); if (id.isEmpty()) id = UUID.randomUUID().toString()
                    reply!!.writeNoException(); reply.writeInt(Wire.RESULT_ACCEPTED)
                    reply.writeString(id); reply.writeLong(SystemClock.uptimeMillis() + 2000)
                }
                Wire.RELEASE_LEASE -> {
                    assertTrue(data.readString()!!.isNotEmpty()); UUID.fromString(data.readString())
                    assertEquals(0, data.dataAvail()); releases.incrementAndGet()
                    reply!!.writeNoException(); reply.writeInt(Wire.RESULT_ACCEPTED)
                }
                else -> fail("Unexpected wire transaction $code")
            }
            return true
        }
    }
    @Suppress("DEPRECATION")
    private fun install(controller: Controller) {
        val pm = shadowOf(app.packageManager)
        fun signing() = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature("release-test".toByteArray()))) }
        pm.installPackage(app.packageManager.getPackageInfo(app.packageName, 0).apply {
            signatures = arrayOf(Signature("release-test".toByteArray())); signingInfo = signing()
        })
        pm.installPackage(PackageInfo().apply {
            packageName = LuminaGatewayClient.CONTROLLER
            applicationInfo = ApplicationInfo().apply { packageName = LuminaGatewayClient.CONTROLLER; uid = 10999 }
            signatures = arrayOf(Signature("release-test".toByteArray())); signingInfo = signing()
        })
        shadowOf(app).setComponentNameAndServiceForBindService(LuminaGatewayClient.SERVICE, controller)
    }
    private fun await(condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Lease transition was not delivered")
    }
    private fun input(controller: Controller, sequence: Long, uid: Int = 10999, lease: String = controller.id,
                      physicalTime: Long = SystemClock.uptimeMillis(), malformed: Boolean = false): Pair<Int, String> {
        ShadowBinder.setCallingUid(uid)
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            if (!malformed) {
                data.writeInterfaceToken(Wire.INPUT_CALLBACK_DESCRIPTOR); data.writeString(controller.session)
                data.writeString(lease); data.writeLong(sequence); data.writeLong(physicalTime); data.writeInt(Wire.INPUT_LEFT_CW)
            }
            assertTrue(controller.callback!!.transact(Wire.DELIVER_INPUT, data, reply, 0))
            reply.readException(); return reply.readInt() to reply.readString()!!
        } finally { data.recycle(); reply.recycle(); ShadowBinder.reset() }
    }
    private fun dispatchAsync(action: () -> Pair<Int, String>): Pair<Int, String> {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result: Future<Pair<Int, String>> = executor.submit<Pair<Int, String>> { action() }
            await { result.isDone }; return result.get(1, TimeUnit.SECONDS)
        } finally { executor.shutdownNow() }
    }
    @Test fun focusReceiptGapIsReacquiredWithoutWaitingOneSecondAndPauseStopsRetry() {
        val controller = Controller(); install(controller)
        val client = LuminaKnobClient(app) { _, ticket -> ticket.complete(ClientDisposition.HANDLED) }
        try {
            client.update(setOf(NormalizedKnobInput.LEFT_CW)); client.onResume(); client.onWindowFocusChanged(true)
            await { client.reason == null }
            controller.denyLease = true
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            await { client.reason == "FOCUS_NOT_VISIBLE" }
            assertEquals(Wire.RESULT_ACCEPTED, dispatchAsync { input(controller, 1) }.first)
            controller.denyLease = false
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            await { client.reason == null }
            assertEquals(Wire.RESULT_ACCEPTED, dispatchAsync { input(controller, 2) }.first)
            client.onPause(); await { controller.releases.get() == 1 }
            val stoppedAt = controller.requests.get()
            shadowOf(Looper.getMainLooper()).idleFor(1500, TimeUnit.MILLISECONDS)
            assertEquals(stoppedAt, controller.requests.get())
        } finally { client.close() }
    }
    @Test fun validCallbackAcknowledgesOnceAndRejectsReplayStaleLeaseAndPhysicalTime() {
        val controller = Controller(); install(controller); val actions = AtomicInteger()
        val client = LuminaKnobClient(app) { _, ticket -> actions.incrementAndGet(); ticket.complete(ClientDisposition.HANDLED) }
        try {
            client.update(setOf(NormalizedKnobInput.LEFT_CW)); client.onResume(); client.onWindowFocusChanged(true)
            await { client.reason == null }
            assertEquals(Wire.RESULT_ACCEPTED, dispatchAsync { input(controller, 1) }.first)
            assertEquals(Wire.RESULT_UNAVAILABLE, input(controller, 1).first)
            assertEquals(Wire.RESULT_UNAVAILABLE, input(controller, 2, lease = UUID.randomUUID().toString()).first)
            assertEquals(Wire.RESULT_UNAVAILABLE, input(controller, 2, physicalTime = SystemClock.uptimeMillis() + 1).first)
            assertEquals(Wire.RESULT_UNAVAILABLE, input(controller, 2, malformed = true).first)
            assertEquals(1, actions.get())
            client.onWindowFocusChanged(false)
            assertEquals(Wire.RESULT_UNAVAILABLE, input(controller, 2).first)
            await { controller.releases.get() == 1 }
        } finally { client.close() }
    }
    @Test fun unauthorizedUidIsRejectedBeforeReadingMalformedParcel() {
        val controller = Controller(); install(controller); val actions = AtomicInteger()
        val client = LuminaKnobClient(app) { _, ticket -> actions.incrementAndGet(); ticket.complete(ClientDisposition.HANDLED) }
        try {
            client.update(setOf(NormalizedKnobInput.LEFT_CW)); client.onResume(); client.onWindowFocusChanged(true)
            await { client.reason == null }
            assertEquals(Wire.RESULT_REJECTED to "CALLER_NOT_AUTHORIZED", input(controller, 1, uid = 10001, malformed = true))
            assertEquals(0, actions.get())
        } finally { client.close() }
    }
    @Test fun mainThreadTimeoutCancelsQueuedActionInsteadOfMutatingAfterFallback() {
        val controller = Controller(); install(controller); val actions = AtomicInteger()
        val client = LuminaKnobClient(app) { _, ticket -> actions.incrementAndGet(); ticket.complete(ClientDisposition.HANDLED) }
        try {
            client.update(setOf(NormalizedKnobInput.LEFT_CW)); client.onResume(); client.onWindowFocusChanged(true)
            await { client.reason == null }
            // No Main drain while the physical callback waits: the queued action must be revoked.
            assertEquals(Wire.RESULT_UNAVAILABLE, input(controller, 1).first)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, actions.get())
        } finally { client.close() }
    }

    @Test fun bufferedAdmissionDoesNotWaitForMainAndRefusalDoesNotDispatchLegacyAction() {
        val controller=Controller(); install(controller); val legacy=AtomicInteger(); val queued=AtomicInteger()
        val client=LuminaKnobClient(app, buffered={ _,_,_ -> if(queued.incrementAndGet()==1) 47L else 0L }) { _,ticket -> legacy.incrementAndGet();ticket.complete(ClientDisposition.HANDLED) }
        try {
            client.update(setOf(NormalizedKnobInput.LEFT_CW));client.onResume();client.onWindowFocusChanged(true)
            await { client.reason==null }
            assertEquals(Wire.RESULT_ACCEPTED to "QUEUED:47",input(controller,1))
            assertEquals(Wire.RESULT_UNAVAILABLE to "ADMISSION_REJECTED",input(controller,2))
            shadowOf(Looper.getMainLooper()).idle();assertEquals(0,legacy.get())
        } finally { client.close() }
    }
    @Test fun renewalContinuesWithoutMainDraining() {
        val controller=Controller();install(controller)
        val client=LuminaKnobClient(app) { _,ticket -> ticket.complete(ClientDisposition.HANDLED) }
        try {
            client.update(setOf(NormalizedKnobInput.LEFT_CW));client.onResume();client.onWindowFocusChanged(true)
            await { client.reason==null };val before=controller.requests.get();val id=controller.id
            Thread.sleep(260)
            assertTrue(controller.requests.get()>before);assertEquals(id,controller.id)
            client.onPause()
        } finally { client.close() }
    }

    @Test fun delayedAlreadyAdmittedCommandReturnsUnknownWithIdInsteadOfAudioFallback() {
        val controller=Controller();install(controller);val actions=AtomicInteger()
        val client=LuminaKnobClient(app,buffered=BufferedKnobSubmit { _,_,_ ->
            shadowOf(Looper.getMainLooper()).idleFor(61,TimeUnit.MILLISECONDS);93L
        }) { _,ticket -> actions.incrementAndGet();ticket.complete(ClientDisposition.HANDLED) }
        try {
            client.update(setOf(NormalizedKnobInput.LEFT_CW));client.onResume();client.onWindowFocusChanged(true);await {client.reason==null}
            ShadowBinder.setCallingUid(10999);val data=Parcel.obtain();val reply=Parcel.obtain()
            try {
                data.writeInterfaceToken(Wire.INPUT_CALLBACK_DESCRIPTOR);data.writeString(controller.session);data.writeString(controller.id)
                data.writeLong(1);data.writeLong(SystemClock.uptimeMillis());data.writeInt(Wire.INPUT_LEFT_CW)
                assertTrue(controller.callback!!.transact(Wire.DELIVER_BUFFERED_INPUT,data,reply,0));reply.readException()
                assertEquals(Wire.RESULT_UNAVAILABLE,reply.readInt());assertEquals(93L,reply.readLong());assertEquals(Wire.ADMISSION_COMPLETION_UNKNOWN,reply.readInt());assertEquals(0,reply.dataAvail())
                assertEquals(0,actions.get())
            }finally{data.recycle();reply.recycle();ShadowBinder.reset()}
        }finally{client.close()}
    }
    @Test fun timedOutUnadmittedCommandReturnsStaleAndAllowsOnlySafeRetry() {
        val controller=Controller();install(controller);var calls=0
        val client=LuminaKnobClient(app,buffered=BufferedKnobSubmit { _,_,_ ->
            if(++calls==1){shadowOf(Looper.getMainLooper()).idleFor(61,TimeUnit.MILLISECONDS);0L}else 94L
        }) { _,_ -> fail("No legacy fallback") }
        try {
            client.update(setOf(NormalizedKnobInput.LEFT_CW));client.onResume();client.onWindowFocusChanged(true);await {client.reason==null}
            assertEquals(Wire.RESULT_UNAVAILABLE to "STALE_INPUT",input(controller,1))
            assertEquals(Wire.RESULT_ACCEPTED to "QUEUED:94",input(controller,1))
            assertEquals(Wire.RESULT_UNAVAILABLE,input(controller,1).first)
        }finally{client.close()}
    }
    @Test fun controllerLossStartsNewVisibleSessionWithIndependentSequence() {
        val controller=Controller();install(controller)
        val client=LuminaKnobClient(app,buffered=BufferedKnobSubmit { _,_,_ -> 95L }) { _,_ -> fail("No legacy fallback") }
        try {
            client.update(setOf(NormalizedKnobInput.LEFT_CW));client.onResume();client.onWindowFocusChanged(true);await {client.reason==null}
            assertEquals(Wire.RESULT_ACCEPTED,input(controller,50).first);val before=controller.session
            LuminaKnobClient::class.java.getDeclaredMethod("disconnect").apply {isAccessible=true}.invoke(client)
            shadowOf(Looper.getMainLooper()).idleFor(100,TimeUnit.MILLISECONDS)
            await {client.reason==null&&controller.session!=before}
            assertEquals(Wire.RESULT_ACCEPTED,input(controller,1).first)
        }finally{client.close()}
    }
    @Test fun partialReleaseChangesWireMaskAndPauseReleasesTheRemainingLease() {
        val controller = Controller(); install(controller); val client = ProductKnobClient(app)
        try {
            client.requestLease(mapOf(NormalizedKnobInput.LEFT_CW to { ClientDisposition.HANDLED },
                NormalizedKnobInput.RIGHT_CW to { ClientDisposition.HANDLED }))
            client.onResume(); client.onWindowFocusChanged(true)
            await { client.leaseReason == null }
            assertEquals(Wire.MASK_LEFT_CW or Wire.MASK_RIGHT_CW, controller.mask)
            client.releaseLease(setOf(NormalizedKnobInput.RIGHT_CW))
            await { controller.mask == Wire.MASK_LEFT_CW && client.leaseReason == null }
            client.onPause(); await { controller.releases.get() == 2 }
        } finally { client.close(); client.close() }
    }
}
