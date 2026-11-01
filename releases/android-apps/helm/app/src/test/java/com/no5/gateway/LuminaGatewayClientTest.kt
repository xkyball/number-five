package com.no5.gateway

import android.app.Application
import android.content.pm.*
import android.os.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowBuild
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class LuminaGatewayClientTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var clock = 100_000L
    private var elapsed = 100_000L
    private class Controller(private val withRoute: Boolean = false, private val clock: () -> Long) : Binder() {
        val calls = AtomicInteger()
        @Volatile var unavailable = false
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == LuminaGatewayClient.REQUEST_GATEWAY_SESSION_WITH_ROUTE && !withRoute) return false
            assertTrue(code == LuminaGatewayClient.REQUEST_GATEWAY_SESSION || code == LuminaGatewayClient.REQUEST_GATEWAY_SESSION_WITH_ROUTE)
            data.enforceInterface(LuminaGatewayClient.DESCRIPTOR)
            assertEquals("HELM", data.readString())
            assertEquals(0, data.dataAvail())
            calls.incrementAndGet()
            reply!!.writeNoException()
            if (unavailable) { reply.writeInt(3); reply.writeString("GATEWAY_NOT_PROVISIONED") }
            else {
                val now = clock(); val origin = "https://no5-marineos-lab.local:9443"
                reply.writeInt(1); reply.writeString(origin); reply.writeString(syntheticCaPem)
                reply.writeString(syntheticToken(GatewayAppRole.HELM, origin, now, now + 900_000))
                reply.writeString(SYNTHETIC_KEY_ID); reply.writeLong(now); reply.writeLong(now + 900_000)
                if (code == LuminaGatewayClient.REQUEST_GATEWAY_SESSION_WITH_ROUTE) reply.writeString("192.0.2.15")
            }
            return true
        }
    }
    @Test fun optionalLocalRouteIsReceivedWithoutChangingTokenOriginOrRole() {
        val controller = Controller(withRoute = true) { clock }; installController(controller)
        val client = client()
        try {
            await { client.status.phase == GatewaySessionPhase.READY }
            assertEquals("192.0.2.15", client.currentSession()!!.fallbackAddress)
            assertEquals("https://no5-marineos-lab.local:9443", client.currentSession()!!.origin.url)
            assertEquals(GatewayAppRole.HELM, client.currentSession()!!.role)
        } finally { client.close() }
    }
    @Test fun newClientFallsBackToLegacyControllerWithoutRoute() {
        val controller = Controller { clock }; installController(controller)
        val client = client()
        try {
            await { client.status.phase == GatewaySessionPhase.READY }
            assertNull(client.currentSession()!!.fallbackAddress)
            assertEquals(1,controller.calls.get())
        } finally { client.close() }
    }
    @Suppress("DEPRECATION")
    private fun installController(controller: Controller, matching: Boolean = true, sharedUid: Boolean = false) {
        ShadowBuild.setHardware("ranchu")
        val pm = shadowOf(app.packageManager)
        fun signing(signature: String) = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(signature.toByteArray()))) }
        pm.installPackage(app.packageManager.getPackageInfo(app.packageName, 0).apply {
            signatures = arrayOf(Signature("release-test".toByteArray())); signingInfo = signing("release-test")
        })
        pm.installPackage(PackageInfo().apply {
            packageName = LuminaGatewayClient.CONTROLLER
            applicationInfo = ApplicationInfo().apply { packageName = LuminaGatewayClient.CONTROLLER; uid = 10999 }
            signatures = arrayOf(Signature((if (matching) "release-test" else "other-signer").toByteArray()))
            signingInfo = signing(if (matching) "release-test" else "other-signer")
        })
        if (sharedUid) pm.installPackage(PackageInfo().apply {
            packageName = "com.no5.foreign"
            applicationInfo = ApplicationInfo().apply { packageName = "com.no5.foreign"; uid = 10999 }
        })
        shadowOf(app).setComponentNameAndServiceForBindService(LuminaGatewayClient.SERVICE, controller)
    }
    private fun client() = LuminaGatewayClient(app, wallClock = { clock }, elapsed = { elapsed })
    private fun await(condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Expected session transition was not delivered")
    }
    private fun advance(ms: Long) {
        clock += ms; elapsed += ms
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    }
    @Test fun exactWireRenewsBeforeExpiryAndWaitsOnlyThirtySecondsBeforeDisabling() {
        val controller = Controller { clock }; installController(controller)
        val client = client()
        try {
            await { client.status.phase == GatewaySessionPhase.READY }
            val first = client.currentSession()!!.identity
            advance(840_000)
            await { client.currentSession()?.identity != first }
            assertEquals(2, controller.calls.get())
            controller.unavailable = true
            advance(840_000)
            await { client.status.phase == GatewaySessionPhase.WAITING }
            assertNotNull(client.currentSession())
            advance(29_000)
            assertTrue(client.status.usable)
            advance(1000)
            assertEquals(GatewaySessionPhase.UNAVAILABLE, client.status.phase)
            assertNull(client.currentSession())
            controller.unavailable = false
            repeat(30) {
                if (client.status.phase != GatewaySessionPhase.READY) { advance(1000); Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle() }
            }
            await { client.status.phase == GatewaySessionPhase.READY }
            assertNotNull(client.currentSession())
        } finally { client.close() }
    }
    @Test fun wrongControllerCertificateNeverReceivesARequest() {
        val controller = Controller { clock }; installController(controller, matching = false)
        val client = client()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            advance(30_000)
            assertEquals(0, controller.calls.get())
            assertEquals(GatewaySessionPhase.UNAVAILABLE, client.status.phase)
        } finally { client.close() }
    }
    @Test fun controllerSharingItsUidIsRejectedEvenWithMatchingCertificate() {
        val controller = Controller { clock }; installController(controller, sharedUid = true)
        val client = client()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, controller.calls.get())
            assertNull(client.currentSession())
        } finally { client.close() }
    }
    @Test fun teardownIsIdempotentAndCannotReviveAfterCallbacks() {
        val controller = Controller { clock }; installController(controller)
        val client = client()
        client.close(); client.close()
        advance(900_000)
        assertEquals(GatewaySessionPhase.CLOSED, client.status.phase)
        assertNull(client.currentSession())
        assertEquals(0, controller.calls.get())
    }
}
