package com.no5.helm.network

import com.no5.helm.security.GatewaySecurityProfile
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A single feature owns one transport. Only explicit simulator paths are allowed; redirects/cache are refused. */
interface PiHttpTransport : AutoCloseable {
    fun get(path: String): String
    fun getBounded(path:String,timeoutMs:Long,maxBytes:Int):String {
        require(timeoutMs in 1..2000 && maxBytes in 1..2048)
        return get(path).also { require(it.toByteArray(Charsets.UTF_8).size<=maxBytes) }
    }
    fun getBytes(path: String): ByteArray = throw IOException("Binary reads are unavailable for this transport")
    fun post(path: String, body: String): String = throw IOException("Commands are unavailable for this transport")
    fun put(path: String, body: String): String = throw IOException("PUT commands are unavailable for this transport")
}

/** Safe numeric response classification for the explicit connection diagnostics, never a body. */
internal class PiHttpStatusException(val statusCode: Int) : IOException("Pi endpoint returned HTTP $statusCode")

data class PiNetworkPolicy(
    val pollIntervalMs: Long,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val requestTimeoutMs: Long,
    val maxResponseBytes: Int = 65_536,
) {
    init {
        require(pollIntervalMs > 0 && connectTimeoutMs > 0 && readTimeoutMs > 0)
        require(requestTimeoutMs > 0 && maxResponseBytes in 1..1_048_576)
    }
    companion object {
        val HELM = PiNetworkPolicy(50, 1_500, 1_500, 3_000)
        val WEATHER = PiNetworkPolicy(2_000, 2_000, 2_000, 4_000)
        // Receiver commands await actual player readback (gateway default 3 s).
        // Keep the HTTP deadline below the AUDIO command's 5 s expiry, with
        // room for that readback; a timeout still never replays a command.
        val AUDIO = PiNetworkPolicy(250, 1_500, 4_000, 4_500)
        val SONAR = PiNetworkPolicy(250, 1_500, 1_500, 3_000)
        val MEDIA = PiNetworkPolicy(1_000, 1_500, 4_000, 4_500, 131_072)
        val ARTWORK = PiNetworkPolicy(1_000, 1_500, 4_000, 4_500, 1_048_576)
        // Context preparation + actor receipt + actual player readback. Never replay a POST.
        val MEDIA_SELECTION = PiNetworkPolicy(250, 1_500, 10_000, 11_000, 131_072)
    }
}

/**
 * Explicit test-protocol URL supplied by SETTINGS; no production API inference.
 * Future.get provides the caller's hard deadline. HttpURLConnection.disconnect may itself
 * block behind a read lock, so only the owned I/O worker performs physical disconnect.
 * close marks cancelled and returns promptly; a socket still unwinding cannot publish.
 * One worker and one bounded handoff slot handle the worker's completion race. Cancelled
 * queued tasks are removed, so retries cannot accumulate behind a blocked connection.
 */
class UrlConnectionPiTransport(
    private val endpoint: String,
    policy: PiNetworkPolicy,
    private val security: GatewaySecurityProfile? = null,
    private val endpointResolver: GatewayEndpointResolver = GatewayEndpointResolver.NONE,
) : PiHttpTransport {
    private val client = BoundedHttpJsonClient(policy, security, endpointResolver)
    override fun get(path: String): String {
        require(path == "/v1/helm" || path == "/v1/weather" || path == "/v1/audio" || path == "/v1/sonar" ||
            path == "/v1/audio/sound" || path == "/v1/audio/media" || path == "/v1/audio/navigation" || path == "/v1/sonar/filter" || path == "/v1/sonar/source" || path == "/v1/sonar/diagnostics" ||
            Regex("/v1/sonar/commands/[0-9]{1,19}").matches(path) ||
            Regex("/v1/audio/commands/[A-Za-z0-9_-]{1,64}").matches(path) || (path.startsWith("/v1/audio/browse?") && path.length <= 2048 &&
                path.none { it == '#' || it == '\r' || it == '\n' })) { "Unsupported read-only endpoint" }
        return client.get(endpoint.trimEnd('/') + path)
    }
    override fun getBounded(path:String,timeoutMs:Long,maxBytes:Int):String {
        require((path.startsWith("/v1/sonar/origin-clock-proof?")||path.startsWith("/v1/sonar/recovery-input-proof?")) && path.length<=2048 &&
            path.none { it=='#'||it=='\r'||it=='\n' }) { "Unsupported bounded read endpoint" }
        require(timeoutMs in 1..2000 && maxBytes in 1..2048)
        return client.getBounded(endpoint.trimEnd('/')+path,timeoutMs,maxBytes)
    }
    override fun getBytes(path: String): ByteArray {
        require(Regex("/v1/audio/(artwork|playlist-artwork)/[a-f0-9]{64}").matches(path)) { "Unsupported artwork endpoint" }
        return client.getBytes(endpoint.trimEnd('/') + path)
    }
    override fun post(path: String, body: String): String {
        require(path == "/v1/audio/command" || path == "/v1/audio/commands" || path == "/v1/sonar/command" || path == "/v1/audio/select") { "Unsupported command endpoint" }
        return client.post(endpoint.trimEnd('/') + path, body)
    }
    override fun put(path: String, body: String): String {
        require(path == "/v1/sonar/filter" || path == "/v1/sonar/source") { "Unsupported PUT endpoint" }
        return client.put(endpoint.trimEnd('/') + path, body)
    }
    override fun close() = client.close()
}

/** Shared bounded HTTP machinery; callers own and restrict their exact URL construction. */
internal class BoundedHttpJsonClient(
    private val policy: PiNetworkPolicy,
    private val security: GatewaySecurityProfile? = null,
    private val endpointResolver: GatewayEndpointResolver = GatewayEndpointResolver.NONE,
) : AutoCloseable {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private var active: FutureTask<ByteArray>? = null
    private val activeSecureCall = AtomicReference<Call?>(null)
    private var secureClient: OkHttpClient? = null
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1)) { task ->
        Thread(task, "no5-http-io").apply { isDaemon = true }
    }

    fun get(url: String): String = decode(request(url, "GET", null, setOf("application/json")))
    fun getBounded(url:String,timeoutMs:Long,maxBytes:Int):String =
        decode(request(url,"GET",null,setOf("application/json"),minOf(policy.requestTimeoutMs,timeoutMs),minOf(policy.maxResponseBytes,maxBytes)))
    fun getBytes(url: String): ByteArray = request(url, "GET", null, setOf("image/jpeg", "image/png"))
    private fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun post(url: String, body: String): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..4_096) { "Simulator command exceeds size limit" }
        return decode(request(url, "POST", bytes, setOf("application/json")))
    }
    fun put(url: String, body: String): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..4096)
        return decode(request(url, "PUT", bytes, setOf("application/json")))
    }

    private fun request(url: String, method: String, body: ByteArray?, accepted: Set<String>, deadlineMs:Long=policy.requestTimeoutMs, responseLimit:Int=policy.maxResponseBytes): ByteArray {
        val request = synchronized(lock) {
            if (closed.get()) throw IOException("Source transport is closed")
            check(active == null) { "Feature requests must be serialized" }
            val task = FutureTask { read(url, method, body, accepted,deadlineMs,responseLimit) }
            try { executor.execute(task) } catch (_: RejectedExecutionException) {
                throw IOException("Previous Pi request is still closing")
            }
            active = task
            task
        }
        try {
            return request.get(deadlineMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            request.cancel(true)
            activeSecureCall.get()?.cancel()
            throw SocketTimeoutException("Pi request deadline exceeded")
        } catch (_: InterruptedException) {
            request.cancel(true)
            activeSecureCall.get()?.cancel()
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Pi request cancelled")
        } catch (_: CancellationException) {
            throw InterruptedIOException("Pi request cancelled")
        } catch (failure: ExecutionException) {
            throw (failure.cause as? IOException ?: IOException("Pi request failed", failure.cause))
        } finally {
            executor.remove(request)
            synchronized(lock) { if (active === request) active = null }
        }
    }

    private fun read(url: String, method: String, body: ByteArray?, accepted: Set<String>,deadlineMs:Long,responseLimit:Int): ByteArray {
        val startedAt = System.nanoTime()
        fun checkActive() {
            if (closed.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Pi request cancelled")
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) >= deadlineMs)
                throw SocketTimeoutException("Pi request deadline exceeded")
        }
        checkActive()
        // Android's HttpsURLConnection resolves the logical hostname before it asks an
        // SSLSocketFactory for a socket.  A mapped factory therefore cannot bridge API-29 mDNS
        // for HTTP, even though it can look correct in a direct socket test.  Secured requests
        // use the same exact-origin OkHttp DNS adapter as WSS so resolution happens before the
        // TCP connection while the URL host remains the TLS/SNI/hostname-verification identity.
        if (security != null) return readSecure(url, method, body, accepted,responseLimit, ::checkActive)
        val connection = URL(url).openConnection() as HttpURLConnection
        var reusable = false
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = minOf(policy.connectTimeoutMs,deadlineMs.toInt())
            connection.readTimeout = minOf(policy.readTimeoutMs,deadlineMs.toInt())
            connection.setRequestProperty("Accept", accepted.joinToString(", "))
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                checkActive()
            }
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK && !(method in setOf("POST", "PUT") && responseCode == HttpURLConnection.HTTP_ACCEPTED)) throw PiHttpStatusException(responseCode)
            checkActive()
            val mediaType = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            if (mediaType !in accepted) throw IOException("Pi endpoint returned an unsupported media type")
            if (connection.contentEncoding?.let { it != "identity" } == true) throw IOException("Encoded responses are not supported")
            if (connection.contentLengthLong > responseLimit) throw IOException("Pi response exceeds size limit")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > responseLimit) throw IOException("Pi response exceeds size limit")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            checkActive()
            checkActive()
            reusable = true
            return bytes
        } finally {
            // Closing a fully consumed body returns a healthy socket to the platform pool.
            // disconnect() would tear down that reuse on every 50 ms HELM sample.
            // Failed/cancelled/partial responses are always discarded by this I/O worker.
            if (!reusable || closed.get() || Thread.currentThread().isInterrupted) connection.disconnect()
        }
    }

    private fun readSecure(
        url: String,
        method: String,
        body: ByteArray?,
        accepted: Set<String>,
        responseLimit:Int,
        checkActive: () -> Unit,
    ): ByteArray {
        val profile = checkNotNull(security)
        val request = Request.Builder().url(url)
            .header("Accept", accepted.joinToString(", "))
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache, no-store")
        // Origin validation occurs before a client/call is constructed, so neither a redirect
        // target nor an IP literal can receive this header.
        profile.configureHttps(url, configureTls = { }, setHeader = request::header)
        when (method) {
            "GET" -> request.get()
            "POST", "PUT" -> request.method(method, requireNotNull(body).toRequestBody("application/json".toMediaType()))
            else -> throw IOException("Unsupported HTTP method")
        }
        val call = secureClient(url).newCall(request.build())
        activeSecureCall.set(call)
        try {
            checkActive()
            call.execute().use { response ->
                if (response.code != HttpURLConnection.HTTP_OK && !(method in setOf("POST", "PUT") && response.code == HttpURLConnection.HTTP_ACCEPTED)) throw PiHttpStatusException(response.code)
                checkActive()
                val mediaType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                if (mediaType !in accepted) throw IOException("Pi endpoint returned an unsupported media type")
                if (response.header("Content-Encoding")?.let { it != "identity" } == true)
                    throw IOException("Encoded responses are not supported")
                val responseBody = response.body ?: throw IOException("Pi endpoint returned an empty response body")
                val input = responseBody.byteStream()
                if (responseBody.contentLength() > responseLimit) throw IOException("Pi response exceeds size limit")
                val bytes = input.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4_096)
                    while (true) {
                        checkActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > responseLimit) throw IOException("Pi response exceeds size limit")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                checkActive()
                return bytes
            }
        } finally {
            activeSecureCall.compareAndSet(call, null)
            if (closed.get() || Thread.currentThread().isInterrupted) call.cancel()
        }
    }

    private fun secureClient(url: String): OkHttpClient = synchronized(lock) {
        secureClient ?: OkHttpClient.Builder().apply {
            checkNotNull(security).configureOkHttp { factory, manager -> sslSocketFactory(factory, manager) }
            // GatewayOkHttpDns fails closed for a matching `.local` origin; it never returns a
            // discovered address for another hostname. OkHttp preserves the URL host for TLS.
            dns(GatewayOkHttpDns(url, endpointResolver, policy.connectTimeoutMs))
        }.connectTimeout(policy.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(policy.requestTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(policy.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(policy.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build().also { secureClient = it }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) { active?.cancel(true) }
        activeSecureCall.getAndSet(null)?.cancel()
        secureClient?.dispatcher?.executorService?.shutdownNow()
        secureClient?.connectionPool?.evictAll()
        executor.shutdownNow()
    }
}
