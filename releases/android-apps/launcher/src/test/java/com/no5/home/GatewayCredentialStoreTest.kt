package com.no5.home

import android.content.Context
import com.no5.helm.security.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29,35])
class GatewayCredentialStoreTest {
    private class Memory : GatewaySecurityStore {
        var bytes: ByteArray? = null
        var rejectNextWrite = false
        override fun read(origin: GatewayOrigin) = bytes?.copyOf()
        override fun save(origin: GatewayOrigin, key: ByteArray) {
            bytes = if (rejectNextWrite) ByteArray(0).also { rejectNextWrite = false } else key.copyOf()
        }
        override fun remove(origin: GatewayOrigin) { bytes = null }
    }
    @Test fun failedKeyImportRestoresDerivedProfileAndKeepsLegacyKeySeparate() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("no5_gateway_auth_mode", Context.MODE_PRIVATE).edit().clear().commit()
        val origin = GatewayOrigin.fromEndpoint("https://no5-marineos-lab.local:9443")
        val keys = Memory(); val derived = Memory()
        val store = AndroidGatewayCredentialStore(context, keys, derived)
        val original = "synthetic-original-key".toByteArray()
        store.save(origin, original)
        store.savePassword(origin, "a".repeat(64))
        val previous = store.read(origin)!!
        assertArrayEquals(original, keys.bytes)
        val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("synthetic-replacement-key".toByteArray())
        val document = object : GatewayKeyDocument {
            override fun open() = "NO5-GATEWAY-KEY-V1\norigin=${origin.value}\nkey-base64url=$key\n".byteInputStream()
            override fun delete() = error("No deletion")
        }
        keys.rejectNextWrite = true
        assertTrue(GatewayKeyImporter(store).import(document, origin.value, false) is GatewayKeyImportResult.StoreFailure)
        assertArrayEquals(previous, store.read(origin))
        assertArrayEquals(original, keys.bytes)
        // A later successful import activates the key while retaining the derived value.
        assertTrue(GatewayKeyImporter(store).import(document, origin.value, false) is GatewayKeyImportResult.Imported)
        assertArrayEquals("synthetic-replacement-key".toByteArray(), store.read(origin))
        assertArrayEquals(previous, derived.bytes)
    }
}
