package com.no5.helm.network

import com.no5.domain.SourceSubscription
import com.no5.helm.security.GatewaySecurityProfile
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface AudioFaderStreamTransport : AutoCloseable {
    val label: String get() = "WSS"
    interface Listener { fun text(value: String); fun failure() }
    fun connect(listener: Listener): SourceSubscription
    fun send(value: String): Boolean
}

class OkHttpAudioFaderStreamTransport(
    endpoint: String,
    private val security: GatewaySecurityProfile,
    endpointResolver: GatewayEndpointResolver = GatewayEndpointResolver.NONE,
) : AudioFaderStreamTransport {
    private val url = endpoint.trimEnd('/') + "/v1/audio/stream"
    private val client = OkHttpClient.Builder().apply {
        security.configureOkHttp { factory, manager -> sslSocketFactory(factory, manager) }
        dns(GatewayOkHttpDns(endpoint, endpointResolver, 1_500))
    }.connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS).pingInterval(5, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val lock = Any()
    private var socket: WebSocket? = null
    private var opened = false
    private var closed = false
    override fun connect(listener: AudioFaderStreamTransport.Listener): SourceSubscription = synchronized(lock) {
        check(!closed && socket == null)
        val cancelled = AtomicBoolean(false)
        val builder = Request.Builder().url(url).header("Sec-WebSocket-Protocol", SUBPROTOCOL)
            .header("Cache-Control", "no-cache, no-store")
        security.configureHttps(url, configureTls = {}, setHeader = builder::header)
        fun fail() {
            if (cancelled.compareAndSet(false, true)) {
                synchronized(lock) { socket?.cancel(); socket = null; opened = false }
                listener.failure()
            }
        }
        socket = client.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (response.header("Sec-WebSocket-Protocol") != SUBPROTOCOL) fail()
                else synchronized(lock) { if (!cancelled.get()) opened = true }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (cancelled.get()) return
                if (text.toByteArray(Charsets.UTF_8).size > 65_536) fail() else listener.text(text)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = fail()
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = fail()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = fail()
        })
        SourceSubscription { cancelled.set(true); synchronized(lock) { socket?.cancel(); socket = null; opened = false } }
    }
    override fun send(value: String): Boolean = synchronized(lock) {
        !closed && opened && value.toByteArray(Charsets.UTF_8).size <= 4096 && socket?.send(value) == true
    }
    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; opened = false; socket?.cancel(); socket = null }
        client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
    }
    companion object { const val SUBPROTOCOL = "no5.audio.fader.v1" }
}
