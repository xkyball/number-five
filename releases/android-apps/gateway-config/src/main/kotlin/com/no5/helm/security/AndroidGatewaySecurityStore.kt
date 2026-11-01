package com.no5.helm.security

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private encrypted store; no caller receives another origin's ciphertext or plaintext. */
class AndroidGatewaySecurityStore(context: Context, private val passwordStore: Boolean = false) : GatewaySecurityStore {
    private val preferences = context.applicationContext.getSharedPreferences(if (passwordStore) "no5_gateway_password" else PREFERENCES, Context.MODE_PRIVATE)

    @SuppressLint("UseKtx") // The import transaction requires a synchronous commit result before readback.
    override fun save(origin: GatewayOrigin, key: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(origin.value.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(key)
        val packed = ByteArray(cipher.iv.size + encrypted.size)
        try {
            System.arraycopy(cipher.iv, 0, packed, 0, cipher.iv.size)
            System.arraycopy(encrypted, 0, packed, cipher.iv.size, encrypted.size)
            check(preferences.edit().putString(storageKey(origin), Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
                "Gateway security storage write failed"
            }
        } finally {
            encrypted.fill(0); packed.fill(0)
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
            cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES)
        } catch (_: Exception) {
            null
        } finally {
            packed.fill(0)
        }
    }

    @SuppressLint("UseKtx") // Rollback must know whether app-private persistence completed.
    override fun remove(origin: GatewayOrigin) {
        check(preferences.edit().remove(storageKey(origin)).commit()) { "Gateway security storage delete failed" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val alias = if (passwordStore) "no5.gateway.password.v1" else KEY_ALIAS
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    private fun storageKey(origin: GatewayOrigin): String = "key." + MessageDigest.getInstance("SHA-256")
        .digest(origin.value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val PREFERENCES = "no5_gateway_security"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "no5.gateway.security.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
