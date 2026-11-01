package com.no5.helm.security

import java.net.URI
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The only production object allowed to hold one gateway credential. It is deliberately scoped
 * to a canonical HTTPS origin and supplies it directly to transports; UI/domain code never sees
 * the header value. The caller must close it when the owning source session retires.
 */
class GatewaySecurityProfile private constructor(
    val origin: GatewayOrigin,
    private var gatewayKey: ByteArray,
    private val socketFactory: SSLSocketFactory,
    private val trustManager: X509TrustManager,
    private val headerName: String = HEADER,
    private var expiresAtMs: Long? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val renewalAuthority: String? = null,
) : AutoCloseable {
    private var closed = false

    @Synchronized internal fun configureHttps(url: String, configureTls: (SSLSocketFactory) -> Unit, setHeader: (String, String) -> Unit) {
        check(!closed) { "Gateway security profile is closed" }
        val expiry = expiresAtMs
        check(expiry == null || now() < expiry) { "GATEWAY_SESSION_EXPIRED" }
        require(origin.matchesHttpsUrl(url)) { "Gateway credential may only be sent to its exact HTTPS origin" }
        configureTls(socketFactory)
        // The protocol deliberately needs a String at this boundary. It is not retained by this
        // profile, URL, SourceConfig, analytics or error text.
        setHeader(headerName, gatewayKey.toString(Charsets.US_ASCII))
    }

    @Synchronized internal fun configureOkHttp(configureTls: (SSLSocketFactory, X509TrustManager) -> Unit) {
        check(!closed) { "Gateway security profile is closed" }
        configureTls(socketFactory, trustManager)
    }

    /** Each source owns and zeroizes its own key; immutable TLS trust objects may be shared. */
    @Synchronized internal fun copyForSession(): GatewaySecurityProfile {
        check(!closed) { "Gateway security profile is closed" }
        return GatewaySecurityProfile(origin, gatewayKey.copyOf(), socketFactory, trustManager, headerName, expiresAtMs, now, renewalAuthority)
    }

    private class Renewal(val origin: GatewayOrigin, val authority: String, val expiry: Long, val key: ByteArray)
    @Synchronized private fun renewal(): Renewal? {
        val expiry = expiresAtMs ?: return null
        val authority = renewalAuthority ?: return null
        if (closed || headerName != "Authorization" || now() >= expiry) return null
        return Renewal(origin, authority, expiry, gatewayKey.copyOf())
    }

    /** Opt-in only for an unchanged validated Lumina issuer, role, CA and route. */
    internal fun renewBearerFrom(candidate: GatewaySecurityProfile): Boolean {
        // Snapshot before taking our lock: profiles never acquire each other's locks.
        val fresh = candidate.renewal() ?: return false
        try {
            return synchronized(this) {
                val expiry = expiresAtMs
                if (closed || headerName != "Authorization" || renewalAuthority == null ||
                    renewalAuthority != fresh.authority || origin != fresh.origin || expiry == null ||
                    now() >= expiry || fresh.expiry <= expiry || now() >= fresh.expiry) false
                else {
                    val previous = gatewayKey
                    gatewayKey = fresh.key.copyOf(); expiresAtMs = fresh.expiry
                    previous.fill(0)
                    true
                }
            }
        } finally { fresh.key.fill(0) }
    }

    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            gatewayKey.fill(0)
        }
    }

    override fun toString(): String = "GatewaySecurityProfile(origin=${origin.value})"

    companion object {
        const val HEADER = "X-No5-Gateway-Key"
        const val PASSWORD_HEADER = "X-No5-Gateway-Password"
        const val PASSWORD_PREFIX = "NO5-PASSWORD-V1:"

        fun createBearer(origin: GatewayOrigin, bearer: ByteArray, ca: ByteArray, expiresAtMs: Long,
            now: () -> Long = System::currentTimeMillis, renewalAuthority: String? = null): GatewaySecurityProfile {
            require(bearer.size in 64..16_400 && bearer.toString(Charsets.US_ASCII).matches(Regex("Bearer N5T2\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")))
            require(expiresAtMs > now())
            val certificate = GatewayCertificateFormat.parseCa(ca)
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("no5-gateway-ca", certificate) }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(manager), SecureRandom()) }
            return GatewaySecurityProfile(origin, bearer.copyOf(), tls.socketFactory, manager, "Authorization", expiresAtMs, now, renewalAuthority)
        }

        /** Builds a trust store containing only the explicitly imported CA. Never trust-all. */
        fun create(origin: GatewayOrigin, key: ByteArray, caCertificateDer: ByteArray): GatewaySecurityProfile {
            require(key.size in 16..512) { "Gateway credential is unavailable" }
            val encoded = key.toString(Charsets.US_ASCII)
            val passwordMode = encoded.startsWith(PASSWORD_PREFIX)
            val credential = if (passwordMode) encoded.removePrefix(PASSWORD_PREFIX) else encoded
            if (passwordMode) require(credential.matches(Regex("[0-9a-f]{64}"))) { "Gateway password credential is unavailable" }
            val certificate = GatewayCertificateFormat.parseCa(caCertificateDer)
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("no5-gateway-ca", certificate)
            }
            val trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
            val trustManager = trustFactory.trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
                ?: throw IllegalStateException("Gateway CA trust manager is unavailable")
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            return GatewaySecurityProfile(origin, credential.toByteArray(Charsets.US_ASCII), context.socketFactory, trustManager,
                if (passwordMode) PASSWORD_HEADER else HEADER)
        }
    }
}

/** Origin matching is exact after the same canonicalisation used by SETTINGS. */
internal fun GatewayOrigin.matchesHttpsUrl(url: String): Boolean = runCatching {
    val request = URI(url)
    require(request.rawUserInfo == null && request.rawFragment == null)
    // Browse parameters belong to a resource URL, not the persisted endpoint.
    // Keep its existing canonical scheme/host/port and path validation intact.
    GatewayOrigin.fromEndpoint(url.substringBefore('?')) == this
}.getOrDefault(false)

/** Public CA material, bounded and validated before persistence or trust-manager construction. */
object GatewayCertificateFormat {
    const val MAX_CERTIFICATE_BYTES = 65_536

    fun parseCa(raw: ByteArray): X509Certificate {
        require(raw.size in 1..MAX_CERTIFICATE_BYTES) { "Gateway CA certificate has an invalid size" }
        val certificate = try {
            CertificateFactory.getInstance("X.509").generateCertificate(raw.inputStream()) as X509Certificate
        } catch (_: Exception) {
            throw IllegalArgumentException("Gateway CA certificate is not a valid X.509 certificate")
        }
        try {
            certificate.checkValidity()
        } catch (_: Exception) {
            throw IllegalArgumentException("Gateway CA certificate is not currently valid")
        }
        require(certificate.basicConstraints >= 0) { "Gateway trust material must be a CA certificate" }
        return certificate
    }
}

/** Source-session composition asks for a profile once; missing or damaged material fails closed. */
fun interface GatewaySecurityProfileProvider {
    fun load(endpoint: String): GatewaySecurityProfile?

    companion object {
        val NONE = GatewaySecurityProfileProvider { null }
    }
}

class StoredGatewaySecurityProfileProvider(
    private val keys: GatewaySecurityStore,
    private val trust: GatewayTrustStore,
) : GatewaySecurityProfileProvider {
    override fun load(endpoint: String): GatewaySecurityProfile? {
        val origin = runCatching { GatewayOrigin.fromEndpoint(endpoint) }.getOrNull() ?: return null
        var key: ByteArray? = null
        var certificate: ByteArray? = null
        return try {
            key = keys.read(origin) ?: return null
            certificate = trust.read(origin) ?: return null
            GatewaySecurityProfile.create(origin, key, certificate)
        } catch (_: Exception) {
            null
        } finally {
            key?.fill(0)
            certificate?.fill(0)
        }
    }
}
