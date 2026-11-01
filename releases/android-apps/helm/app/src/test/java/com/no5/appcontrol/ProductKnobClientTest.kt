package com.no5.appcontrol

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class ProductKnobClientTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private class Controller : Binder() {
        @Volatile var session = ""
        val id = UUID.randomUUID().toString()
        @Volatile var mask = 0
        @Volatile var callback: IBinder? = null
        val requests = AtomicInteger()
        val releases = AtomicInteger()
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(Wire.DESCRIPTOR)
            when (code) {
                Wire.REQUEST_LEASE -> {
                    session = requireNotNull(data.readString()); mask = data.readInt(); callback = data.readStrongBinder()
                    assertEquals(0, data.dataAvail())
                    reply!!.writeNoException(); reply.writeInt(Wire.RESULT_ACCEPTED)
                    reply.writeString(id); reply.writeLong(SystemClock.uptimeMillis() + 2000)
                    requests.incrementAndGet()
                }
                Wire.RELEASE_LEASE -> {
                    requireNotNull(data.readString()); assertEquals(id, data.readString()); assertEquals(0, data.dataAvail())
                    reply!!.writeNoException(); reply.writeInt(Wire.RESULT_ACCEPTED); releases.incrementAndGet()
                }
                else -> fail("Unexpected transaction $code")
            }
            return true
        }
    }
    private class Action(private val base: Long) : BufferedKnobAction {
        val submissions = AtomicInteger()
        val cancellations = AtomicInteger()
        @Volatile var cancelled = false
        override fun submit(sequence: Long, physicalUptimeMs: Long): Long {
            cancelled = false
            return base + submissions.incrementAndGet()
        }
        override fun writeResult(commandId: Long, reply: Parcel): Boolean {
            if (commandId !in (base + 1)..(base + submissions.get())) return false
            reply.writeInt(1); reply.writeInt(if (cancelled) 1 else 0)
            return true
        }
        override fun cancel() { cancellations.incrementAndGet(); cancelled = true }
    }
    private data class Admission(val result: Int, val id: Long, val reason: Int)
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
        // Android does not report onServiceDisconnected for our own unbind.
        // Robolectric's legacy default synthesizes that callback; after an
        // immediate return it would disconnect the newly rebound client.
        shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
        shadowOf(app).setComponentNameAndServiceForBindService(LuminaGatewayClient.SERVICE, controller)
    }
    private fun await(condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Lease transition not delivered")
    }
    private fun input(controller: Controller, sequence: Long, code: Int = Wire.INPUT_LEFT_CW,
                      session: String = controller.session): Admission {
        ShadowBinder.setCallingUid(10999)
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(Wire.INPUT_CALLBACK_DESCRIPTOR); data.writeString(session); data.writeString(controller.id)
            data.writeLong(sequence); data.writeLong(SystemClock.uptimeMillis()); data.writeInt(code)
            assertTrue(controller.callback!!.transact(Wire.DELIVER_BUFFERED_INPUT, data, reply, 0))
            reply.readException()
            return Admission(reply.readInt(), reply.readLong(), reply.readInt()).also { assertEquals(0, reply.dataAvail()) }
        } finally { data.recycle(); reply.recycle(); ShadowBinder.reset() }
    }
    private fun result(controller: Controller, id: Long): Int? {
        ShadowBinder.setCallingUid(10999)
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(Wire.INPUT_CALLBACK_DESCRIPTOR); data.writeLong(id)
            assertTrue(controller.callback!!.transact(Wire.READ_COMMAND_RESULT, data, reply, 0))
            reply.readException()
            return if (reply.readInt() == 0) null else reply.readInt()
        } finally { data.recycle(); reply.recycle(); ShadowBinder.reset() }
    }
    private fun ready(client: ProductKnobClient, controller: Controller) {
        client.onResume(); client.onWindowFocusChanged(true)
        renewedAfter(client, controller, 0)
    }
    private fun renewedAfter(client: ProductKnobClient, controller: Controller, previousRequests: Int) {
        // reason may still contain the preceding READY. The worker serializes
        // renewals: pass any old in-flight request and at least one completed
        // replacement renewal before delivering the test's physical impulse.
        await { controller.requests.get() >= previousRequests + 3 && client.leaseReason == null }
    }

    @Test fun pauseCancelsPendingWorkButReturnAdmitsWithoutRepublishingBindings() {
        val controller = Controller(); install(controller); val action = Action(1000)
        val client = ProductKnobClient(app)
        try {
            client.requestLeaseBuffered(mapOf(NormalizedKnobInput.LEFT_CW to action)); ready(client, controller)
            assertEquals(Admission(Wire.RESULT_ACCEPTED, 1001, Wire.ADMISSION_QUEUED), input(controller, 1))
            client.onPause()
            assertEquals(1, action.cancellations.get()); assertEquals(1, result(controller, 1001))
            assertNotEquals(Wire.RESULT_ACCEPTED, input(controller, 2).result)
            assertEquals(1, action.submissions.get())
            val requested = controller.requests.get()
            client.onResume() // No requestLeaseBuffered call and no recomposition.
            renewedAfter(client, controller, requested)
            assertEquals(1 shl (Wire.INPUT_LEFT_CW - 1), controller.mask)
            assertEquals(Admission(Wire.RESULT_ACCEPTED, 1002, Wire.ADMISSION_QUEUED), input(controller, 2))
            assertEquals(2, action.submissions.get())
        } finally { client.close() }
    }

    @Test fun focusLossCancelsWorkAndRepeatedVisibilityGapsKeepTheRegisteredOwner() {
        val controller = Controller(); install(controller); val action = Action(2000)
        val client = ProductKnobClient(app)
        try {
            client.requestLeaseBuffered(mapOf(NormalizedKnobInput.LEFT_CW to action)); ready(client, controller)
            for (sequence in 1L..3L) {
                assertEquals(Wire.RESULT_ACCEPTED, input(controller, sequence).result)
                client.onWindowFocusChanged(false)
                assertNotEquals(Wire.RESULT_ACCEPTED, input(controller, sequence + 1).result)
                val requested = controller.requests.get()
                client.onWindowFocusChanged(true)
                renewedAfter(client, controller, requested)
            }
            assertEquals(3, action.submissions.get()); assertEquals(3, action.cancellations.get())
        } finally { client.close() }
    }

    @Test fun replacingTheOwnerForTheSameKeysCancelsTheOldOwnerAndRejectsItsSession() {
        val controller = Controller(); install(controller); val first = Action(3000); val next = Action(4000)
        val client = ProductKnobClient(app)
        try {
            client.requestLeaseBuffered(mapOf(NormalizedKnobInput.LEFT_CW to first)); ready(client, controller)
            assertEquals(Wire.RESULT_ACCEPTED, input(controller, 1).result)
            val previousSession = controller.session; val requested = controller.requests.get()
            client.requestLeaseBuffered(mapOf(NormalizedKnobInput.LEFT_CW to next))
            renewedAfter(client, controller, requested)
            assertEquals(1, first.cancellations.get())
            assertNotEquals(Wire.RESULT_ACCEPTED, input(controller, 2, session = previousSession).result)
            assertEquals(Admission(Wire.RESULT_ACCEPTED, 4001, Wire.ADMISSION_QUEUED), input(controller, 2))
            assertEquals(1, first.submissions.get()); assertEquals(1, next.submissions.get())
        } finally { client.close() }
    }

    @Test fun partialReleaseRetainsOnlyUnreleasedBufferedKeysAndFullReleaseDoesNotReturnOnResume() {
        val controller = Controller(); install(controller); val left = Action(5000); val right = Action(6000)
        val client = ProductKnobClient(app)
        try {
            client.requestLeaseBuffered(mapOf(NormalizedKnobInput.LEFT_CW to left, NormalizedKnobInput.RIGHT_CW to right))
            ready(client, controller)
            val requested = controller.requests.get()
            client.releaseLease(setOf(NormalizedKnobInput.LEFT_CW))
            renewedAfter(client, controller, requested)
            assertEquals(1 shl (Wire.INPUT_RIGHT_CW - 1), controller.mask)
            assertNotEquals(Wire.RESULT_ACCEPTED, input(controller, 1).result)
            assertEquals(Admission(Wire.RESULT_ACCEPTED, 6001, Wire.ADMISSION_QUEUED), input(controller, 1, Wire.INPUT_RIGHT_CW))
            val releasedBefore = controller.releases.get()
            client.releaseLease()
            await { controller.releases.get() > releasedBefore }
            assertNull(result(controller, 6001))
            assertNotEquals(Wire.RESULT_ACCEPTED, input(controller, 2, Wire.INPUT_RIGHT_CW).result)
            val stoppedAt = controller.requests.get()
            client.onPause(); client.onResume(); client.onWindowFocusChanged(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(stoppedAt, controller.requests.get())
            assertEquals(1, right.submissions.get()); assertEquals(0, left.submissions.get())
        } finally { client.close() }
    }

    @Test fun explicitEmptyRebindAndCloseClearRegistrationsAndResultProvider() {
        val controller = Controller(); install(controller); val action = Action(7000)
        val client = ProductKnobClient(app)
        try {
            client.requestLeaseBuffered(mapOf(NormalizedKnobInput.LEFT_CW to action)); ready(client, controller)
            assertEquals(Wire.RESULT_ACCEPTED, input(controller, 1).result)
            client.requestLeaseBuffered(emptyMap())
            assertNull(result(controller, 7001))
            assertNotEquals(Wire.RESULT_ACCEPTED, input(controller, 2).result)
            assertEquals(1, action.cancellations.get())
            val requested = controller.requests.get()
            client.requestLeaseBuffered(mapOf(NormalizedKnobInput.LEFT_CW to action))
            renewedAfter(client, controller, requested)
            client.close()
            assertEquals(2, action.cancellations.get())
            assertNotEquals(Wire.RESULT_ACCEPTED, input(controller, 2).result)
            assertEquals(1, action.submissions.get())
        } finally { client.close() }
    }
}
