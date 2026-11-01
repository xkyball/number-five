package com.no5.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.no5.gateway.*
import com.no5.helm.network.AndroidGatewayEndpointResolver
import com.no5.helm.network.PiV1Decoder
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** HOME is a read-only direct client. It cannot retrieve or distribute bootstrap credentials. */
internal class LuminaHomeGatewayMonitor(context: Context, private val onStatus: (String, Boolean) -> Unit) : AutoCloseable {
    val gateway = LuminaGatewayClient(context)
    private val uiContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val resolver = AndroidGatewayEndpointResolver(context)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "no5-home-status").apply { isDaemon = true } }
    private var foreground = false
    private var closed = false
    private var inFlight = false
    private var epoch = 0L
    private var identity: String? = null
    private val poll = Runnable { read() }
    private var displayedSessionStatus: GatewaySessionStatus? = null
    private val observation = gateway.observe { status ->
        if (identity != status.identity) { epoch++; identity = status.identity }
        displayedSessionStatus = status
        onStatus(homeGatewaySessionLabel(uiContext, status), false)
        if (foreground && status.usable) read()
    }
    /** Locale-only re-projection: no gateway refresh, session transition or network request. */
    fun refreshUiLabel() { if (!closed) displayedSessionStatus?.let { onStatus(homeGatewaySessionLabel(uiContext, it), false) } }
    fun start() { foreground = true; gateway.refresh(); read() }
    fun stop() { foreground = false; handler.removeCallbacks(poll) }
    private fun read() {
        handler.removeCallbacks(poll)
        if (closed || !foreground || inFlight) return
        val session = gateway.currentSession() ?: return
        val current = epoch
        val profile = session.profile()
        val origin = session.origin
        inFlight = true
        worker.execute {
            var client: OkHttpClient? = null
            val label = try {
                client = OkHttpClient.Builder().dns(LuminaGatewayDns(origin, resolver, session.fallbackAddress))
                    .connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(2000, TimeUnit.MILLISECONDS)
                    .callTimeout(5000, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false)
                    .apply { profile.configureOkHttp { socket, trust -> sslSocketFactory(socket, trust) } }.build()
                val url = origin.url + "/v1/status"
                val request = Request.Builder().url(url).header("Cache-Control", "no-store")
                profile.configureHttps(url, {}, request::header)
                client.newCall(request.build()).execute().use { reply ->
                    check(reply.code == 200)
                    val bytes = reply.body?.byteStream()?.use { input ->
                        val data = java.io.ByteArrayOutputStream(); val chunk = ByteArray(4096)
                        while (true) { val n = input.read(chunk); if (n < 0) break; require(data.size() + n <= 65536); data.write(chunk, 0, n) }
                        data.toByteArray()
                    } ?: error("Gateway-Antwort fehlt")
                    PiV1Decoder.gatewayStatus(bytes.toString(Charsets.UTF_8))
                }
                "Gateway · TLS und Status bestätigt"
            } catch (_: Exception) { "Gateway · Verbindung nicht bestätigt" }
            finally { profile.close(); client?.dispatcher?.executorService?.shutdownNow(); client?.connectionPool?.evictAll() }
            handler.post {
                inFlight = false
                if (!closed && foreground && current == epoch && gateway.currentSession() != null) {
                    displayedSessionStatus = null
                    onStatus(label, label == "Gateway · TLS und Status bestätigt")
                }
                if (!closed && foreground) handler.postDelayed(poll, if (current == epoch) 60_000 else 1000)
            }
        }
    }
    override fun close() {
        if (closed) return
        closed = true; epoch++; stop(); observation.close(); gateway.close(); resolver.close(); worker.shutdownNow()
    }
}
