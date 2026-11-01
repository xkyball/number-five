package com.no5.helm.security

import android.annotation.SuppressLint
import android.content.Context
import java.security.MessageDigest

/** The selected credential is explicit; importing a key preserves the derived credential. */
class AndroidGatewayCredentialStore(context: Context,
    private val keys: GatewaySecurityStore = AndroidGatewaySecurityStore(context),
    private val passwords: GatewaySecurityStore = AndroidGatewaySecurityStore(context, passwordStore = true)
) : GatewaySecurityStore {
    private val modes = context.applicationContext.getSharedPreferences("no5_gateway_auth_mode", Context.MODE_PRIVATE)
    private fun id(origin: GatewayOrigin) = MessageDigest.getInstance("SHA-256").digest(origin.value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    override fun read(origin: GatewayOrigin): ByteArray? =
        if (modes.getBoolean(id(origin), false)) passwords.read(origin) else keys.read(origin)
    override fun save(origin: GatewayOrigin, key: ByteArray) {
        // Import rollback may restore the previously selected derived profile.
        if (key.toString(Charsets.US_ASCII).startsWith(GatewaySecurityProfile.PASSWORD_PREFIX)) {
            val value = key.toString(Charsets.US_ASCII).removePrefix(GatewaySecurityProfile.PASSWORD_PREFIX)
            require(value.matches(Regex("[0-9a-f]{64}")))
            writeConfirmed(passwords, origin, key); select(origin, true)
        } else { writeConfirmed(keys, origin, key); selectKey(origin) }
    }
    private fun writeConfirmed(store: GatewaySecurityStore, origin: GatewayOrigin, value: ByteArray) {
        val previous = store.read(origin)
        try {
            store.save(origin, value)
            val readback = store.read(origin)
            try { check(readback != null && MessageDigest.isEqual(value, readback)) { "Gateway credential readback failed" } }
            finally { readback?.fill(0) }
        } catch (failure: Exception) {
            runCatching { if (previous == null) store.remove(origin) else store.save(origin, previous) }
            throw failure
        } finally { previous?.fill(0) }
    }
    override fun remove(origin: GatewayOrigin) {
        if (modes.getBoolean(id(origin), false)) passwords.remove(origin) else keys.remove(origin)
    }
    @SuppressLint("UseKtx")
    fun savePassword(origin: GatewayOrigin, prehash: String) {
        require(prehash.matches(Regex("[0-9a-f]{64}")))
        val bytes = (GatewaySecurityProfile.PASSWORD_PREFIX + prehash).toByteArray(Charsets.US_ASCII)
        try { save(origin, bytes) } finally { bytes.fill(0) }
    }
    @SuppressLint("UseKtx")
    fun selectKey(origin: GatewayOrigin) {
        select(origin, false)
    }
    @SuppressLint("UseKtx")
    private fun select(origin: GatewayOrigin, derived: Boolean) {
        check(modes.edit().putBoolean(id(origin), derived).commit()) { "Gateway authentication mode could not be saved" }
    }
}
