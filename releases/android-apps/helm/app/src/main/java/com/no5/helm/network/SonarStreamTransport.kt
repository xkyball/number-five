package com.no5.helm.network

import com.no5.domain.SourceSubscription
import com.no5.helm.security.GatewaySecurityProfile
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A source owns one connection. Listener callbacks are I/O-only and must never mutate UI state. */
interface SonarStreamTransport : AutoCloseable {
    interface Listener {
        fun text(value: String)
        fun binary(value: ByteArray)
        fun failure(message: String)
    }
    fun connect(listener: Listener): SourceSubscription
}

class OkHttpSonarStreamTransport(
    endpoint: String,
    private val security: GatewaySecurityProfile? = null,
    private val endpointResolver: GatewayEndpointResolver = GatewayEndpointResolver.NONE,
) : SonarStreamTransport {
    private val url = endpoint.trimEnd('/') + "/v1/sonar/stream"
    private val client = OkHttpClient.Builder().apply {
        security?.configureOkHttp { factory, manager -> sslSocketFactory(factory, manager) }
        // OkHttp keeps the URL host for SNI, Host and hostname verification. This DNS adapter
        // changes only the socket address after a matching NSD service has been verified.
        if (security != null) dns(GatewayOkHttpDns(endpoint, endpointResolver, 1_500))
    }.connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).writeTimeout(1500, TimeUnit.MILLISECONDS)
        .pingInterval(5, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val lock = Any()
    private var active: WebSocket? = null
    private var closed = false
    override fun connect(listener: SonarStreamTransport.Listener): SourceSubscription = synchronized(lock) {
        check(!closed && active == null)
        val cancelled = AtomicBoolean(false)
        var socket: WebSocket? = null
        fun failure(message: String) {
            if (cancelled.compareAndSet(false, true)) {
                socket?.cancel()
                synchronized(lock) { if (active === socket) active = null }
                listener.failure(message)
            }
        }
        val request = sonarRequest(url, security)
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (response.header("Sec-WebSocket-Protocol") != SonarV1Decoder.SUBPROTOCOL)
                    failure("SONAR stream subprotocol was not confirmed")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (cancelled.get()) return
                if (text.toByteArray(Charsets.UTF_8).size > SonarV1Decoder.MAX_STATE_BYTES) failure("SONAR state exceeds size limit")
                else listener.text(text)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (cancelled.get()) return
                if (bytes.size > SonarV1Decoder.MAX_BINARY_BYTES) failure("SONAR frame exceeds size limit")
                else listener.binary(bytes.toByteArray())
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = failure("SONAR stream closed")
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = failure("SONAR stream closed")
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = failure("SONAR stream unavailable")
        })
        active = socket
        SourceSubscription {
            cancelled.set(true)
            socket?.cancel()
            synchronized(lock) { if (active === socket) active = null }
        }
    }
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            active?.cancel(); active = null
        }
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }
}

/** Exact-origin DNS adapter for WSS. It never returns a mDNS address for another hostname. */
internal class GatewayOkHttpDns(
    endpoint: String,
    private val resolver: GatewayEndpointResolver,
    private val timeoutMs: Int,
) : Dns {
    private val target = runCatching { java.net.URI(endpoint).takeIf { it.scheme == "https" && it.rawUserInfo == null } }.getOrNull()

    override fun lookup(hostname: String): List<java.net.InetAddress> {
        val expected = target
        if (expected == null || !hostname.trimEnd('.').equals(expected.host, ignoreCase = true)) return Dns.SYSTEM.lookup(hostname)
        return when (val resolution = resolver.resolve("https://${expected.rawAuthority}", timeoutMs)) {
            is GatewayEndpointResolution.Resolved -> resolution.addresses
            GatewayEndpointResolution.NotApplicable -> Dns.SYSTEM.lookup(hostname)
            is GatewayEndpointResolution.Failed -> throw GatewayMdnsException(resolution.failure)
        }
    }
}

/** Kept separate so WSS header/origin rules are testable without opening a socket. */
internal fun sonarRequest(url: String, security: GatewaySecurityProfile?): Request {
    val builder = Request.Builder().url(url).header("Sec-WebSocket-Protocol", SonarV1Decoder.SUBPROTOCOL)
        .header("Cache-Control", "no-cache, no-store")
    security?.configureHttps(url, configureTls = { }, setHeader = builder::header)
    return builder.build()
}
