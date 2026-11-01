package com.no5.home

import android.content.Context
import com.no5.helm.security.GatewayCertificateFormat
import com.no5.helm.security.GatewayKnownAuthorities
import com.no5.helm.security.GatewayOrigin
import com.no5.helm.security.GatewayTrustStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29,35])
class GatewayCaStoreTest {
    private fun ca() = java.util.Base64.getDecoder().decode(javaClass.getResource("/gateway-synthetic-ca.b64")!!.readText().trim())
    @Test fun readinessRequiresValidMaterialAndAutomaticLabelRequiresMatchingPinAndProvenance() {
        val bytes = ca()
        val hash = GatewayKnownAuthorities.sha256(GatewayCertificateFormat.parseCa(bytes))
        assertEquals(GatewayCaState.AUTOMATIC, GatewayCaState.inspect(bytes, hash, "automatic:$hash"))
        assertEquals(GatewayCaState.EXISTING, GatewayCaState.inspect(bytes, hash, null))
        assertEquals(GatewayCaState.EXISTING, GatewayCaState.inspect(bytes, hash, "automatic:old-fingerprint"))
        assertEquals(GatewayCaState.MANUAL, GatewayCaState.inspect(bytes, "wrong-pin", "automatic:$hash"))
        assertEquals(GatewayCaState.MANUAL, GatewayCaState.inspect(bytes, hash, "manual:$hash"))
        assertEquals(GatewayCaState.UNAVAILABLE, GatewayCaState.inspect("broken".toByteArray(), hash, "automatic:$hash"))
    }
    @Test fun provenanceIsOriginBoundAndOnlyRecordedAfterSuccessfulStorage() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("no5_gateway_ca_status", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val origin = GatewayOrigin.fromEndpoint("https://lysmarine.local:9443")
        val other = GatewayOrigin.fromEndpoint("https://other.invalid:9443")
        var reject = true
        val values = mutableMapOf<GatewayOrigin,ByteArray>()
        val trust = object : GatewayTrustStore {
            override fun read(origin: GatewayOrigin) = values[origin]?.copyOf()
            override fun save(origin: GatewayOrigin, caCertificateDer: ByteArray) {
                check(!reject) { "Synthetic storage failure" }; values[origin] = caCertificateDer.copyOf()
            }
            override fun remove(origin: GatewayOrigin) { values.remove(origin) }
        }
        val store = GatewayCaStore(context, trust)
        try { store.save(origin, ca()); fail("Expected write failure") } catch (_: IllegalStateException) { }
        assertNull(prefs.getString(origin.value, null))
        assertEquals(GatewayCaState.UNAVAILABLE, store.status(origin))
        reject = false
        store.save(origin, ca())
        assertTrue(prefs.getString(origin.value, "")!!.startsWith("automatic:"))
        assertNull(prefs.getString(other.value, null))
        assertEquals(GatewayCaState.UNAVAILABLE, store.status(other))
        store.imported(origin)
        assertTrue(prefs.getString(origin.value, "")!!.startsWith("manual:"))
        values[origin] = "corrupt".toByteArray()
        assertEquals(GatewayCaState.UNAVAILABLE, store.status(origin))
    }
}
