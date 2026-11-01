package com.no5.home

import android.content.Context
import android.os.Bundle
import com.no5.gateway.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
class GatewayConfigurationTest {
    private val origin = GatewayOrigin.parse("https://no5-marineos-lab.local:9443")!!
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun setUp() {
        ShadowBuild.setFingerprint("generic/test")
        ShadowBuild.setHardware("ranchu")
        app.getSharedPreferences("no5_gateway_configuration", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun tearDown() { ShadowBinder.reset(); GatewayCheckerFactory.testFactory = null }
    private class Checker : GatewayAddressChecker {
        var connected = true; var answer: String? = null; var reachable = true
        var discoveries = 0; var sockets = 0
        override fun online() = connected
        override fun discover(origin: GatewayOrigin): String? { discoveries++; return answer }
        override fun reachable(origin: GatewayOrigin, address: String): Boolean { sockets++; return reachable }
    }
    @Test fun deviceAssignmentCannotSwitchBetweenLabAndPhysicalPi() {
        assertTrue(GatewayEnvironment.accepts(origin, true)); assertFalse(GatewayEnvironment.accepts(origin, false))
        val physical = GatewayOrigin.parse("https://boat-gateway.local:9443")!!
        assertFalse(GatewayEnvironment.accepts(physical, true)); assertTrue(GatewayEnvironment.accepts(physical, false))
        assertTrue(GatewayEnvironment.isEmulator("generic/sdk", "test", "ranchu", "sdk"))
        assertFalse(GatewayEnvironment.isEmulator("vendor/release", "Lumina", "board", "boat"))
        assertFalse(GatewayEnvironment.isEmulator("generic/gsi", "Lumina", "board", "boat"))
    }
    @Test fun strictOriginsAndNumericAddressesDoNotTurnIntoDnsOrCredentials() {
        listOf("http://host.local", "https://user:pass@host.local", "https://host.local/v1/helm", "https://host.local?key=secret", "https://192.168.68.88:9443").forEach { assertNull(GatewayOrigin.parse(it)) }
        assertEquals("https://host.local", GatewayOrigin.parse("https://HOST.local:443/")!!.url)
        listOf("host.local", "127.0.0.1", "0.0.0.0", "224.0.0.251", "fe80::1", "10.0.0.999").forEach { assertNull(gatewayAddress(it)) }
    }
    @Test fun discoveryIsPreferredButOnlyAReachableAddressIsAdopted() {
        val checker = Checker().apply { answer = "192.168.68.88" }
        val record = GatewayCheckPolicy(checker).check(origin, null, 100)!!
        assertEquals(GatewayAddressSource.MDNS, record.source); assertTrue(record.usable)
        checker.reachable = false
        assertNull(GatewayCheckPolicy(checker).check(origin, null, 101))
    }
    @Test fun failedMdnsUsesExplicitMappingAndRepeatedFailureRequestsInput() {
        val checker = Checker(); val policy = GatewayCheckPolicy(checker)
        val manual = policy.manual(origin, "192.168.68.88", 1)!!
        assertEquals(GatewayAddressSource.MANUAL, policy.check(origin, manual, 2)!!.source)
        checker.reachable = false
        val first = policy.check(origin, manual, 3)!!; assertTrue(first.usable)
        val second = policy.check(origin, first, 4)!!; assertFalse(second.usable)
        checker.connected = false
        assertEquals(second, policy.check(origin, second, 5)); assertEquals(3, checker.discoveries)
    }
    @Test fun serviceInstanceAndPortNeverBecomeAnInventedHostname() {
        assertTrue(AndroidGatewayAddressChecker.matches("no5-marineos-lab._https._tcp.local.", "_https._tcp.", "no5-marineos-lab"))
        assertFalse(AndroidGatewayAddressChecker.matches("different", "_https._tcp.", "no5-marineos-lab"))
        assertFalse(AndroidGatewayAddressChecker.matches("no5-marineos-lab", "_http._tcp.", "no5-marineos-lab"))
    }
    @Test fun damagedStoreDoesNotActivateAnOriginHintOrReplacementAddress() {
        val store = GatewayConfigurationStore(app)
        store.offer(origin)
        app.getSharedPreferences("no5_gateway_configuration", Context.MODE_PRIVATE).edit().putInt("configuration", 5).commit()
        assertNull(store.read()); assertTrue(store.corrupt()); assertNull(store.hint())
        val encoded = "1\n${origin.url}\n192.168.68.88\nMANUAL\nAVAILABLE\n4\n0\nfalse"
        assertEquals(GatewayAddressSource.MANUAL, GatewayConfigurationCodec.decode(encoded)!!.source)
        assertNull(GatewayConfigurationCodec.decode(encoded + "\ncredential"))
    }
    @Test fun providerRejectsUnknownUidAndWrongProfileOrigin() {
        val provider = Robolectric.buildContentProvider(GatewayConfigurationProvider::class.java).create().get()
        val store = GatewayConfigurationStore(app)
        store.save(GatewayConfiguration(origin, "192.168.68.88", GatewayAddressSource.MANUAL, checkedAt = 1))
        assertFalse(provider.call(HomeGatewayContract.READ, null, null).containsKey(HomeGatewayContract.KEY))
        try {
            provider.call(HomeGatewayContract.PROFILE, null, Bundle().apply { putString(HomeGatewayContract.ORIGIN, "https://wrong.local:9443") })
            fail("Different origin must not receive profile material")
        } catch (_: IllegalArgumentException) { }
        ShadowBinder.setCallingUid(42424)
        try { provider.call(HomeGatewayContract.READ, null, null); fail("Unknown UID must be rejected") }
        catch (_: SecurityException) { }
    }
    @Test fun signatureAndExactPackageAreBothRequiredEvenForSharedUid() {
        assertTrue(gatewayCallerAllowed(20, 10, arrayOf("com.no5.helm"), true))
        assertTrue(gatewayCallerAllowed(21, 10, arrayOf("com.no5.map"), true))
        assertFalse(gatewayCallerAllowed(20, 10, arrayOf("com.no5.helm"), false))
        assertFalse(gatewayCallerAllowed(20, 10, arrayOf("com.no5.helm", "another.app"), true))
        assertFalse(gatewayCallerAllowed(20, 10, emptyArray(), true))
        assertFalse(gatewayCallerAllowed(20, 10, arrayOf("another.app"), true))
    }
    @Test fun homeDnsMapsOnlyTheLogicalNameAndFailsWhenInputIsRequired() {
        val record = GatewayConfiguration(origin, "192.168.68.88", GatewayAddressSource.MANUAL, checkedAt = 1)
        assertEquals("192.168.68.88", HomeGatewayDns(record).lookup(origin.host).single().hostAddress)
        assertEquals("https://no5-marineos-lab.local:9443", record.origin.url)
        try { HomeGatewayDns(record.copy(state = GatewayAddressState.NEEDS_INPUT)).lookup(origin.host); fail("Unusable mapping") }
        catch (_: java.net.UnknownHostException) { }
    }
}
