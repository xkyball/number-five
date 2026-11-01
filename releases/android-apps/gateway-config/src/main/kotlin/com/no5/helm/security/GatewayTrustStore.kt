package com.no5.helm.security

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate origin-bound persistence for an explicitly selected public CA certificate. */
interface GatewayTrustStore {
    fun save(origin: GatewayOrigin, caCertificateDer: ByteArray)
    fun read(origin: GatewayOrigin): ByteArray?
    fun remove(origin: GatewayOrigin)
}

class AndroidGatewayTrustStore(context: Context) : GatewayTrustStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @SuppressLint("UseKtx")
    override fun save(origin: GatewayOrigin, caCertificateDer: ByteArray) {
        // Parse first so a corrupt document can never replace a known-good trust anchor.
        val encodedCertificate = GatewayCertificateFormat.parseCa(caCertificateDer).encoded
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(origin.value.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(encodedCertificate)
        val packed = ByteArray(cipher.iv.size + encrypted.size)
        try {
            cipher.iv.copyInto(packed)
            encrypted.copyInto(packed, cipher.iv.size)
            check(preferences.edit().putString(storageKey(origin), Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
                "Gateway trust storage write failed"
            }
        } finally {
            encodedCertificate.fill(0); encrypted.fill(0); packed.fill(0)
        }
    }

    override fun read(origin: GatewayOrigin): ByteArray? {
        val encoded = preferences.getString(storageKey(origin), null) ?: return null
        val packed = try { Base64.decode(encoded, Base64.NO_WRAP) } catch (_: IllegalArgumentException) { return null }
        if (packed.size <= IV_BYTES) { packed.fill(0); return null }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, packed.copyOfRange(0, IV_BYTES)))
            cipher.updateAAD(origin.value.toByteArray(Charsets.UTF_8))
            cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES).also { GatewayCertificateFormat.parseCa(it) }
        } catch (_: Exception) {
            null
        } finally {
            packed.fill(0)
        }
    }

    @SuppressLint("UseKtx")
    override fun remove(origin: GatewayOrigin) {
        check(preferences.edit().remove(storageKey(origin)).commit()) { "Gateway trust storage delete failed" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    private fun storageKey(origin: GatewayOrigin): String = "ca." + MessageDigest.getInstance("SHA-256")
        .digest(origin.value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val PREFERENCES = "no5_gateway_trust"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "no5.gateway.trust.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

interface GatewayTrustDocument { fun open(): InputStream? }

sealed interface GatewayTrustImportResult {
    data object Cancelled : GatewayTrustImportResult
    data object Imported : GatewayTrustImportResult
    data class Rejected(val message: String) : GatewayTrustImportResult
}

/** Explicit, no-network CA-import transaction. UI wiring is intentionally separate from SETTINGS layout. */
class GatewayTrustImporter(private val store: GatewayTrustStore) {
    fun import(document: GatewayTrustDocument?, expectedEndpoint: String): GatewayTrustImportResult {
        if (document == null) return GatewayTrustImportResult.Cancelled
        val origin = runCatching { GatewayOrigin.fromEndpoint(expectedEndpoint) }.getOrElse {
            return GatewayTrustImportResult.Rejected("Save a valid HTTPS gateway address before importing a CA certificate")
        }
        val raw = try { document.open()?.let(::readBounded) ?: return GatewayTrustImportResult.Cancelled }
        catch (_: Exception) { return GatewayTrustImportResult.Rejected("The selected CA certificate could not be read") }
        return try {
            val certificate = GatewayCertificateFormat.parseCa(raw)
            val canonical = certificate.encoded
            try { store.save(origin, canonical) } finally { canonical.fill(0) }
            GatewayTrustImportResult.Imported
        } catch (failure: IllegalArgumentException) {
            GatewayTrustImportResult.Rejected(failure.message ?: "Gateway CA certificate was rejected")
        } catch (_: Exception) {
            GatewayTrustImportResult.Rejected("Gateway CA certificate could not be saved securely")
        } finally { raw.fill(0) }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val bytes = ByteArray(GatewayCertificateFormat.MAX_CERTIFICATE_BYTES)
        val buffer = ByteArray(4_096)
        var size = 0
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (size + count > bytes.size) throw IOException("Gateway CA certificate is too large")
                buffer.copyInto(bytes, size, 0, count)
                size += count
            }
            return bytes.copyOf(size)
        } finally { bytes.fill(0); buffer.fill(0); input.close() }
    }
}
