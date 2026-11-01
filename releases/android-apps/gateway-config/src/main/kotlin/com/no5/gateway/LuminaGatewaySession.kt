package com.no5.gateway

import com.no5.helm.security.GatewayCertificateFormat
import com.no5.helm.security.GatewayOrigin as SecureOrigin
import com.no5.helm.security.GatewaySecurityProfile

enum class GatewayAppRole(val packageName: String) {
    HOME("com.no5.home"), HELM("com.no5.helm"), MAP("com.no5.map");
    companion object {
        fun forPackage(name: String) = entries.singleOrNull { it.packageName == name }
            ?: throw SecurityException("CALLER_NOT_AUTHORIZED")
    }
}

/** A short-lived credential from the verified controller. No credential is persisted or printable. */
class LuminaGatewaySession(
    val origin: GatewayOrigin,
    caPem: String,
    token: String,
    val keyId: String,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val role: GatewayAppRole,
    private val now: () -> Long = System::currentTimeMillis,
    val fallbackAddress: String? = null,
) : AutoCloseable {
    private val security: GatewaySecurityProfile
    private var closed = false
    val identity: String get() = "${origin.url}|$keyId|$issuedAtMs|$expiresAtMs"
    val valid: Boolean get() = !closed && now() < expiresAtMs
    init {
        require(GatewayEnvironment.accepts(origin)) { "GATEWAY_DEVICE_MISMATCH" }
        GatewayIpv4Fallback.parse(fallbackAddress)
        require(keyId.matches(Regex("[0-9a-f]{32}"))) { "INVALID_GATEWAY_SESSION" }
        require(issuedAtMs >= 0 && issuedAtMs <= now() + 30_000 && expiresAtMs - issuedAtMs == 900_000L && expiresAtMs > now())
        require(token.length in 64..16_384 && token.matches(Regex("N5T2\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")))
        val payload = String(java.util.Base64.getUrlDecoder().decode(token.split('.')[1]), Charsets.UTF_8)
        val lines = payload.removeSuffix("\n").split('\n')
        require(payload.endsWith('\n') && lines.size == 8)
        val claims = lines.associate { line -> line.substringBefore('=') to line.substringAfter('=') }
        require(claims.keys == setOf("v", "kid", "aud", "role", "scope", "iat", "exp", "jti"))
        require(claims["v"] == "no5-gateway-token-v2" && claims["kid"] == keyId && claims["aud"] == origin.url && claims["role"] == role.name)
        require(claims["iat"] == issuedAtMs.toString() && claims["exp"] == expiresAtMs.toString())
        val scopes = when (role) {
            GatewayAppRole.HOME -> setOf("configuration.read")
            GatewayAppRole.MAP -> setOf("map.read", "map.write")
            GatewayAppRole.HELM -> setOf("audio.read", "audio.command", "sonar.read", "sonar.command")
        }
        val actual = claims.getValue("scope").split(',')
        require(actual.size == scopes.size && actual.toSet() == scopes)
        require(java.util.Base64.getUrlDecoder().decode(claims.getValue("jti")).size == 16)
        val ca = caPem.toByteArray(Charsets.US_ASCII)
        val credential = ("Bearer $token").toByteArray(Charsets.US_ASCII)
        try {
            val certificate = GatewayCertificateFormat.parseCa(ca)
            val caIdentity = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val authority = "${origin.url}|$keyId|${role.name}|$caIdentity|${fallbackAddress.orEmpty()}"
            security = GatewaySecurityProfile.createBearer(SecureOrigin.fromEndpoint(origin.url), credential, ca, expiresAtMs, now, authority)
        } finally { ca.fill(0); credential.fill(0) }
    }
    fun profile(): GatewaySecurityProfile {
        check(valid) { "GATEWAY_SESSION_EXPIRED" }
        return security.copyForSession()
    }
    override fun close() { if (!closed) { closed = true; security.close() } }
    override fun toString() = "LuminaGatewaySession(role=$role, origin=${origin.url}, expiresAtMs=$expiresAtMs)"
}
