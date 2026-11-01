package com.no5.home

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.content.pm.PackageManager
import com.no5.gateway.*
import com.no5.helm.security.AndroidGatewayCredentialStore
import com.no5.helm.security.AndroidGatewayTrustStore
import com.no5.helm.security.GatewayOrigin as SecureOrigin

/** No remote preference writes; clients may offer an origin or request a later health check. */
class GatewayConfigurationProvider : ContentProvider() {
    private lateinit var store: GatewayConfigurationStore
    override fun onCreate(): Boolean { store = GatewayConfigurationStore(requireNotNull(context)); return true }

    private fun requireClient() {
        val caller = Binder.getCallingUid()
        if (caller == Process.myUid()) return
        val pm = requireNotNull(context).packageManager
        val packages = pm.getPackagesForUid(caller).orEmpty()
        if (!gatewayCallerAllowed(caller, Process.myUid(), packages,
            pm.checkSignatures(caller, Process.myUid()) == PackageManager.SIGNATURE_MATCH))
            throw SecurityException("Gateway configuration caller is not authorized")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        requireClient()
        return when (method) {
            HomeGatewayContract.READ -> Bundle().apply {
                putBoolean(HomeGatewayContract.CORRUPT, store.corrupt())
                putLong(HomeGatewayContract.REVISION, store.securityRevision())
                val origin = store.read()?.origin?.url?.let { SecureOrigin.fromEndpoint(it) }
                if (origin != null) {
                    val key = runCatching { AndroidGatewayCredentialStore(requireNotNull(context)).read(origin) }.getOrNull()
                    val ca = runCatching { AndroidGatewayTrustStore(requireNotNull(context)).read(origin) }.getOrNull()
                    try { putBoolean(HomeGatewayContract.KEY_PRESENT, key != null); putBoolean(HomeGatewayContract.CA_PRESENT, ca != null) }
                    finally { key?.fill(0); ca?.fill(0) }
                }
                store.read()?.let { putString(HomeGatewayContract.CONFIGURATION, GatewayConfigurationCodec.encode(it)) }
            }
            HomeGatewayContract.PROFILE -> {
                val record = store.read() ?: return Bundle()
                require(record.usable && GatewayEnvironment.accepts(record.origin) && extras?.getString(HomeGatewayContract.ORIGIN) == record.origin.url) { "Gateway profile origin is not active" }
                val origin = SecureOrigin.fromEndpoint(record.origin.url)
                Bundle().apply {
                    putByteArray(HomeGatewayContract.KEY, AndroidGatewayCredentialStore(requireNotNull(context)).read(origin))
                    putByteArray(HomeGatewayContract.CA, AndroidGatewayTrustStore(requireNotNull(context)).read(origin))
                }
            }
            HomeGatewayContract.OFFER_ORIGIN -> {
                GatewayOrigin.parse(extras?.getString(HomeGatewayContract.ORIGIN).orEmpty())?.takeIf { GatewayEnvironment.accepts(it) }?.let(store::offer)
                Bundle()
            }
            HomeGatewayContract.REQUEST_CHECK -> {
                GatewayOrigin.parse(extras?.getString(HomeGatewayContract.ORIGIN).orEmpty())?.let(store::requestCheck)
                Bundle()
            }
            else -> throw IllegalArgumentException("Unsupported gateway configuration operation")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? { requireClient(); return null }
    override fun getType(uri: Uri): String { requireClient(); return "vnd.android.cursor.item/vnd.no5.gateway" }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Gateway settings are changed in Home")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Gateway settings are changed in Home")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Gateway settings are changed in Home")
}

internal fun gatewayCallerAllowed(caller: Int, homeUid: Int, packages: Array<out String>, matchingSignature: Boolean): Boolean =
    caller == homeUid || matchingSignature && packages.isNotEmpty() && packages.all { it in setOf("com.no5.helm", "com.no5.map") }
