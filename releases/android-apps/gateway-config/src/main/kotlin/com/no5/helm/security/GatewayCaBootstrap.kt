package com.no5.helm.security

import com.no5.gateway.GatewayConfiguration
import com.no5.gateway.HomeGatewayDns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Public trust identities read through the existing trusted administrative connections. */
object GatewayKnownAuthorities {
    fun fingerprint(origin: GatewayOrigin): String? = when (origin.value) {
        "https://lysmarine.local:9443" -> "63d2a7840f794686b5777a345903458c835a5401a0629d29f667c1458a52ff59"
        "https://no5-marineos-lab.local:9443" -> "a5e5e53d68895a7ed66576641cdfb704e1f26072dc73a3b4b5edb4329faf0fd4"
        else -> null
    }
    fun sha256(certificate: X509Certificate): String = MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** A narrowly scoped bootstrap trusts only the pinned root, then uses normal PKIX + hostname checks. */
class GatewayPinnedCaTrustManager(private val fingerprint: String) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        throw java.security.cert.CertificateException("Client certificates are unavailable")
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val root = chain.singleOrNull { GatewayKnownAuthorities.sha256(it) == fingerprint }
            ?: throw java.security.cert.CertificateException("Gateway CA identity does not match")
        root.checkValidity()
        if (root.basicConstraints < 0) throw java.security.cert.CertificateException("Gateway certificate is not a CA")
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("root", root) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        factory.trustManagers.filterIsInstance<X509TrustManager>().single().checkServerTrusted(chain, authType)
    }
}

class GatewayCaBootstrap(private val trust: GatewayTrustStore) {
    fun ensure(record: GatewayConfiguration): Boolean {
        val origin = GatewayOrigin.fromEndpoint(record.origin.url)
        val existing = try { trust.read(origin) } catch (_: Exception) { null }
        if (existing != null) {
            try {
                GatewayCertificateFormat.parseCa(existing)
                return false
            } catch (_: IllegalArgumentException) {
                // An unreadable or invalid local copy is recovered through the same pinned download.
            } finally { existing.fill(0) }
        }
        val pin = GatewayKnownAuthorities.fingerprint(origin)
            ?: throw IllegalStateException("Gateway CA identity is not configured; import its public CA")
        val manager = GatewayPinnedCaTrustManager(pin)
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(manager), SecureRandom()) }
        val client = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, manager).dns(HomeGatewayDns(record))
            .followRedirects(false).followSslRedirects(false).connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()
        try {
            client.newCall(Request.Builder().url("${origin.value}/auth/v1/ca")
                .header("Accept-Encoding", "identity").header("Cache-Control", "no-store").build()).execute().use { response ->
                require(response.code == 200 && response.header("Content-Encoding").let { it == null || it == "identity" })
                val body = requireNotNull(response.body)
                require(body.contentLength() <= GatewayCertificateFormat.MAX_CERTIFICATE_BYTES)
                val bytes = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val chunk = ByteArray(4096)
                    while (true) {
                        val n = input.read(chunk); if (n < 0) break
                        require(bytes.size() + n <= GatewayCertificateFormat.MAX_CERTIFICATE_BYTES)
                        bytes.write(chunk, 0, n)
                    }
                }
                val raw = bytes.toByteArray()
                try {
                    val ca = GatewayCertificateFormat.parseCa(raw)
                    require(GatewayKnownAuthorities.sha256(ca) == pin) { "Gateway CA identity does not match" }
                    trust.save(origin, ca.encoded)
                } finally { raw.fill(0) }
            }
            return true
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
}
