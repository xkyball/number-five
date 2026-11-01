package com.no5.helm.security

import com.no5.gateway.GatewayConfiguration
import com.no5.gateway.HomeGatewayDns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object GatewayPasswordKdf {
    const val ITERATIONS = 600_000
    fun derive(password: CharArray, salt: String): String {
        require(password.size in 1..512 && salt.matches(Regex("[0-9a-f]{64}")))
        val bytes = salt.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val spec = PBEKeySpec(password, bytes, ITERATIONS, 256)
        try {
            val value = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return try { value.joinToString("") { "%02x".format(it.toInt() and 255) } } finally { value.fill(0) }
        } finally { spec.clearPassword(); bytes.fill(0) }
    }
}

/** Invoked on an I/O worker. Password chars are cleared by the UI transaction. */
class GatewayPasswordClient(private val record: GatewayConfiguration, private val trust: GatewayTrustStore,
                            private val credentials: AndroidGatewayCredentialStore) {
    fun useCredential(password: CharArray) {
        val origin = GatewayOrigin.fromEndpoint(record.origin.url)
        GatewayCaBootstrap(trust).ensure(record)
        val ca = requireNotNull(trust.read(origin)) { "Gateway CA is unavailable" }
        // Parameters and login transport carry no credential until strict TLS has succeeded.
        val tlsProfile = GatewaySecurityProfile.create(origin, ByteArray(32) { 65 }, ca)
        val client = OkHttpClient.Builder().apply { tlsProfile.configureOkHttp { f, m -> sslSocketFactory(f, m) } }
            .dns(HomeGatewayDns(record)).followRedirects(false).followSslRedirects(false)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
        try {
            val parameters = client.newCall(Request.Builder().url("${origin.value}/auth/v1/parameters").build()).execute().use { response ->
                require(response.code == 200)
                val body = requireNotNull(response.body)
                require(body.contentLength() <= 1024)
                val output = java.io.ByteArrayOutputStream()
                body.byteStream().use { stream ->
                    val chunk = ByteArray(512)
                    while (true) { val count = stream.read(chunk); if (count < 0) break
                        require(output.size() + count <= 1024); output.write(chunk, 0, count) }
                }
                val input = output.toByteArray()
                JSONObject(input.toString(Charsets.UTF_8))
            }
            require(parameters.getInt("version") == 1 && parameters.getString("algorithm") == "PBKDF2-HMAC-SHA256"
                && parameters.getInt("iterations") == GatewayPasswordKdf.ITERATIONS)
            val prehash = GatewayPasswordKdf.derive(password, parameters.getString("salt"))
            val request = Request.Builder().url("${origin.value}/auth/v1/check").header(GatewaySecurityProfile.PASSWORD_HEADER, prehash).build()
            client.newCall(request).execute().use { require(it.code == 204) { "Gateway password was rejected" } }
            credentials.savePassword(origin, prehash)
        } finally { ca.fill(0); tlsProfile.close(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
}
