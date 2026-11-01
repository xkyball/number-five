package com.no5.home

import android.content.Context
import com.no5.gateway.*

internal class GatewayConfigurationStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("no5_gateway_configuration", Context.MODE_PRIVATE)
    fun read(): GatewayConfiguration? = synchronized(lock) {
        GatewayConfigurationCodec.decode(try { prefs.getString(RECORD, null) } catch (_: RuntimeException) { null })
    }
    fun corrupt(): Boolean = synchronized(lock) { prefs.contains(RECORD) && read() == null }
    fun hint(): GatewayOrigin? = synchronized(lock) { if (corrupt()) null else read()?.origin ?: GatewayOrigin.parse(try { prefs.getString(HINT, "").orEmpty() } catch (_: RuntimeException) { "" }) }

    fun offer(origin: GatewayOrigin) = synchronized(lock) {
        require(GatewayEnvironment.accepts(origin))
        if (prefs.contains(RECORD) || prefs.contains(HINT)) return@synchronized
        check(write(HINT, origin.url))
        notifyClients()
    }

    fun save(record: GatewayConfiguration) = synchronized(lock) {
        require(GatewayEnvironment.accepts(record.origin))
        require(GatewayConfigurationCodec.decode(GatewayConfigurationCodec.encode(record)) != null)
        val previous = read()?.routingKey
        check(write(RECORD, GatewayConfigurationCodec.encode(record))) { "Gateway configuration could not be saved" }
        if (previous != record.routingKey) notifyClients()
    }

    fun requestCheck(origin: GatewayOrigin) = synchronized(lock) {
        val record = read()?.takeIf { it.origin == origin && !it.checkRequested } ?: return@synchronized
        save(record.copy(checkRequested = true))
    }

    fun securityRevision(): Long = synchronized(lock) { try { prefs.getLong("security_revision", 0) } catch (_: RuntimeException) { 0 } }
    fun materialChanged() = synchronized(lock) {
        check(prefs.edit().putLong("security_revision", securityRevision() + 1).commit())
        notifyClients()
    }

    private fun write(key: String, value: String): Boolean {
        val previous = try { prefs.getString(key, null) } catch (_: RuntimeException) { "invalid-gateway-configuration" }
        if (prefs.edit().putString(key, value).commit()) return true
        val rollback = prefs.edit()
        if (previous == null) rollback.remove(key) else rollback.putString(key, previous)
        rollback.commit()
        return false
    }
    private fun notifyClients() = app.contentResolver.notifyChange(HomeGatewayContract.URI, null)
    companion object {
        private val lock = Any()
        private const val RECORD = "configuration"
        private const val HINT = "origin_hint"
    }
}
