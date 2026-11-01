package com.no5.helm.security

import java.security.MessageDigest
import java.security.cert.X509Certificate

/** Non-secret, read-only material state for the explicit Pi-connection diagnostics surface. */
enum class GatewayMaterialState { PRESENT, MISSING, UNREADABLE }
enum class GatewayProfileState { READY, KEY_MISSING, CA_MISSING, MATERIAL_UNREADABLE, PROFILE_UNAVAILABLE, NOT_HTTPS }
enum class GatewayDraftOriginState { MATCHES_SAVED, DIFFERENT_FROM_SAVED, INVALID }

data class GatewayCertificateSummary(
    val sha256Fingerprint: String,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long,
)

data class GatewaySecurityInspection(
    val savedEndpoint: String,
    val savedOrigin: String?,
    val draftEndpoint: String,
    val draftOriginState: GatewayDraftOriginState,
    val key: GatewayMaterialState,
    val ca: GatewayMaterialState,
    val certificate: GatewayCertificateSummary?,
    val profile: GatewayProfileState,
)

/**
 * Reads only app-private stores for the already saved origin. Key bytes are used exclusively to
 * answer presence and are cleared immediately; no key or certificate body crosses this boundary.
 */
class GatewaySecurityInspector(
    private val keys: GatewaySecurityStore,
    private val trust: GatewayTrustStore,
    private val profiles: GatewaySecurityProfileProvider = StoredGatewaySecurityProfileProvider(keys, trust),
) {
    fun inspect(savedEndpoint: String, draftEndpoint: String = savedEndpoint): GatewaySecurityInspection {
        val savedOrigin = runCatching { GatewayOrigin.fromEndpoint(savedEndpoint) }.getOrNull()
        val draftOrigin = runCatching { GatewayOrigin.fromEndpoint(draftEndpoint) }.getOrNull()
        val draftState = when {
            draftOrigin == null -> GatewayDraftOriginState.INVALID
            draftOrigin == savedOrigin -> GatewayDraftOriginState.MATCHES_SAVED
            else -> GatewayDraftOriginState.DIFFERENT_FROM_SAVED
        }
        if (savedOrigin == null) return GatewaySecurityInspection(
            savedEndpoint, null, draftEndpoint, draftState,
            GatewayMaterialState.MISSING, GatewayMaterialState.MISSING, null, GatewayProfileState.NOT_HTTPS,
        )

        var keyBytes: ByteArray? = null
        var caBytes: ByteArray? = null
        var certificate: X509Certificate? = null
        return try {
            val keyState = try {
                keyBytes = keys.read(savedOrigin)
                if (keyBytes == null) GatewayMaterialState.MISSING else GatewayMaterialState.PRESENT
            } catch (_: Exception) { GatewayMaterialState.UNREADABLE }
            val caState = try {
                caBytes = trust.read(savedOrigin)
                certificate = caBytes?.let(GatewayCertificateFormat::parseCa)
                if (caBytes == null) GatewayMaterialState.MISSING else GatewayMaterialState.PRESENT
            } catch (_: Exception) { GatewayMaterialState.UNREADABLE }
            val summary = certificate?.let(::summary)
            val profileState = when {
                keyState == GatewayMaterialState.UNREADABLE || caState == GatewayMaterialState.UNREADABLE -> GatewayProfileState.MATERIAL_UNREADABLE
                keyState == GatewayMaterialState.MISSING -> GatewayProfileState.KEY_MISSING
                caState == GatewayMaterialState.MISSING -> GatewayProfileState.CA_MISSING
                else -> try {
                    profiles.load(savedEndpoint)?.use { GatewayProfileState.READY } ?: GatewayProfileState.PROFILE_UNAVAILABLE
                } catch (_: Exception) { GatewayProfileState.PROFILE_UNAVAILABLE }
            }
            GatewaySecurityInspection(savedEndpoint, savedOrigin.value, draftEndpoint, draftState, keyState, caState, summary, profileState)
        } finally {
            keyBytes?.fill(0)
            caBytes?.fill(0)
        }
    }

    private fun summary(certificate: X509Certificate): GatewayCertificateSummary {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return try {
            GatewayCertificateSummary(
                sha256Fingerprint = digest.joinToString(":") { "%02X".format(it.toInt() and 0xff) },
                validFromEpochMs = certificate.notBefore.time,
                validUntilEpochMs = certificate.notAfter.time,
            )
        } finally { digest.fill(0) }
    }
}
