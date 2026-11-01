package com.no5.gateway

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.no5.helm.security.GatewaySecurityProfile
import com.no5.helm.security.GatewaySecurityProfileProvider
import com.no5.helm.security.GatewayOrigin as SecureOrigin
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

object HomeGatewayContract {
    const val AUTHORITY = "com.no5.home.gateway"
    const val PERMISSION = "com.no5.home.permission.GATEWAY_CONFIGURATION"
    val URI: Uri = Uri.parse("content://$AUTHORITY/configuration")
    const val READ = "read"
    const val PROFILE = "profile"
    const val OFFER_ORIGIN = "offer_origin"
    const val REQUEST_CHECK = "request_check"
    const val CONFIGURATION = "configuration"
    const val ORIGIN = "origin"
    const val CORRUPT = "corrupt"
    const val REVISION = "security_revision"
    const val KEY = "key"
    const val CA = "ca"
    const val KEY_PRESENT = "key_present"
    const val CA_PRESENT = "ca_present"
}

class HomeGatewayUnavailable : IllegalStateException("Home gateway configuration is unavailable")
data class HomeGatewayStatus(val installed: Boolean, val configuration: GatewayConfiguration?,
    val securityRevision: Long = 0, val keyPresent: Boolean = false, val caPresent: Boolean = false) {
    val identity: String get() = "$installed|${configuration?.routingKey}|$securityRevision|$keyPresent|$caPresent"
}

/** Profile material is exchanged only through verified, signature-protected IPC. Never logged. */
class HomeGatewayClient(context: Context) : GatewaySecurityProfileProvider {
    private val app = context.applicationContext
    private fun providerPresent(): Boolean {
        val provider = app.packageManager.resolveContentProvider(HomeGatewayContract.AUTHORITY, 0) ?: return false
        if (provider.packageName != "com.no5.home") throw HomeGatewayUnavailable()
        return true
    }
    private fun call(method: String, extras: Bundle? = null): Bundle? {
        if (!providerPresent()) return null
        if (app.packageManager.checkSignatures(app.packageName, "com.no5.home") != android.content.pm.PackageManager.SIGNATURE_MATCH)
            throw HomeGatewayUnavailable()
        return try { app.contentResolver.call(HomeGatewayContract.URI, method, null, extras) ?: throw HomeGatewayUnavailable() }
        catch (_: RuntimeException) { throw HomeGatewayUnavailable() }
    }
    fun status(): HomeGatewayStatus {
        val bundle = call(HomeGatewayContract.READ) ?: return HomeGatewayStatus(false, null)
        if (bundle.getBoolean(HomeGatewayContract.CORRUPT)) throw HomeGatewayUnavailable()
        val encoded = bundle.getString(HomeGatewayContract.CONFIGURATION)
        val record = if (encoded == null) null else GatewayConfigurationCodec.decode(encoded) ?: throw HomeGatewayUnavailable()
        if (record != null && !GatewayEnvironment.accepts(record.origin)) throw HomeGatewayUnavailable()
        return HomeGatewayStatus(true, record, bundle.getLong(HomeGatewayContract.REVISION),
            bundle.getBoolean(HomeGatewayContract.KEY_PRESENT), bundle.getBoolean(HomeGatewayContract.CA_PRESENT))
    }
    fun read(): GatewayConfiguration? = status().configuration
    override fun load(endpoint: String): GatewaySecurityProfile? {
        val origin = runCatching { SecureOrigin.fromEndpoint(endpoint) }.getOrNull() ?: return null
        val record = read()?.takeIf { it.usable && it.origin.url == origin.value } ?: return null
        val result = call(HomeGatewayContract.PROFILE, Bundle().apply { putString(HomeGatewayContract.ORIGIN, record.origin.url) }) ?: return null
        val key = result.getByteArray(HomeGatewayContract.KEY)
        val ca = result.getByteArray(HomeGatewayContract.CA)
        return try {
            if (key == null || ca == null) null else GatewaySecurityProfile.create(origin, key, ca)
        } catch (_: Exception) { null }
        finally { key?.fill(0); ca?.fill(0); result.clear() }
    }
    fun offerOrigin(origin: String) {
        val valid = GatewayOrigin.parse(origin)?.takeIf { GatewayEnvironment.accepts(it) } ?: return
        call(HomeGatewayContract.OFFER_ORIGIN, Bundle().apply { putString(HomeGatewayContract.ORIGIN, valid.url) })
    }
    fun requestCheck(origin: String) {
        val valid = GatewayOrigin.parse(origin) ?: return
        call(HomeGatewayContract.REQUEST_CHECK, Bundle().apply { putString(HomeGatewayContract.ORIGIN, valid.url) })
    }
    fun openConfiguration(origin: String? = null): Boolean {
        return try {
            if (!providerPresent()) return false
            if (app.packageManager.checkSignatures(app.packageName, "com.no5.home") != android.content.pm.PackageManager.SIGNATURE_MATCH) return false
            app.startActivity(Intent().setClassName("com.no5.home", "com.no5.home.GatewayConfigurationActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { GatewayOrigin.parse(origin.orEmpty())?.takeIf { GatewayEnvironment.accepts(it) }?.let { putExtra(HomeGatewayContract.ORIGIN, it.url) } })
            true
        } catch (_: RuntimeException) { false }
    }
}

/** Mapping changes routing only: URL, SNI, certificate hostname and origin-bound key stay intact. */
class HomeGatewayDns(private val record: GatewayConfiguration) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.equals(record.origin.host, true)) return Dns.SYSTEM.lookup(hostname)
        if (!record.usable || !GatewayEnvironment.accepts(record.origin)) throw UnknownHostException("Home gateway needs configuration")
        return listOf(gatewayAddress(record.address) ?: throw UnknownHostException("Home gateway address is invalid"))
    }
}
