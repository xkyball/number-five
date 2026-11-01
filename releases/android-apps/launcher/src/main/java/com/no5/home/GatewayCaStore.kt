package com.no5.home

import android.content.Context
import com.no5.helm.security.AndroidGatewayTrustStore
import com.no5.helm.security.GatewayCertificateFormat
import com.no5.helm.security.GatewayKnownAuthorities
import com.no5.helm.security.GatewayOrigin
import com.no5.helm.security.GatewayTrustStore

/** Public download provenance only. The certificate remains in the existing encrypted trust store. */
internal class GatewayCaStore(context: Context, private val trust: GatewayTrustStore = GatewayTrustFactory.create(context)) : GatewayTrustStore by trust {
    private val provenance = context.applicationContext.getSharedPreferences("no5_gateway_ca_status", Context.MODE_PRIVATE)

    override fun save(origin: GatewayOrigin, caCertificateDer: ByteArray) {
        trust.save(origin, caCertificateDer)
        remember(origin, caCertificateDer, "automatic")
    }

    fun imported(origin: GatewayOrigin) {
        val bytes = runCatching { trust.read(origin) }.getOrNull() ?: return
        try { remember(origin, bytes, "manual") } finally { bytes.fill(0) }
    }

    private fun remember(origin: GatewayOrigin, bytes: ByteArray, source: String) {
        val fingerprint = GatewayKnownAuthorities.sha256(GatewayCertificateFormat.parseCa(bytes))
        // A provenance write failure must not invalidate a successfully stored certificate.
        runCatching { provenance.edit().putString(origin.value, "$source:$fingerprint").commit() }
    }

    fun status(origin: GatewayOrigin): GatewayCaState {
        val bytes = runCatching { trust.read(origin) }.getOrNull() ?: return GatewayCaState.UNAVAILABLE
        return try {
            GatewayCaState.inspect(bytes, GatewayKnownAuthorities.fingerprint(origin),
                runCatching { provenance.getString(origin.value, null) }.getOrNull())
        } finally { bytes.fill(0) }
    }
}

internal enum class GatewayCaState(val message: String) {
    UNAVAILABLE("CA noch nicht verfügbar"),
    EXISTING("CA vorhanden und geprüft"),
    AUTOMATIC("CA automatisch geladen und geprüft"),
    MANUAL("CA manuell hinterlegt · Zertifikat gültig");

    companion object {
        fun inspect(bytes: ByteArray, knownFingerprint: String?, provenance: String?): GatewayCaState {
            val certificate = runCatching { GatewayCertificateFormat.parseCa(bytes) }.getOrNull() ?: return UNAVAILABLE
            val actual = GatewayKnownAuthorities.sha256(certificate)
            if (knownFingerprint != actual || provenance == "manual:$actual") return MANUAL
            return if (provenance == "automatic:$actual") AUTOMATIC else EXISTING
        }
    }
}

/** Host tests substitute public test material; production always uses the existing Android store. */
internal object GatewayTrustFactory {
    var testFactory: ((Context) -> GatewayTrustStore)? = null
    fun create(context: Context): GatewayTrustStore = testFactory?.invoke(context) ?: AndroidGatewayTrustStore(context)
}
