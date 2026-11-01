package com.no5.helm.client

import com.no5.helm.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HelmClient(private val scope: CoroutineScope) : AutoCloseable, SignalKRouteActions {
    private val routeMutable = MutableStateFlow(SignalKRoutesState())
    override val routeResources = routeMutable.asStateFlow()
    @Volatile private var routeOrigin: String? = null
    @Volatile private var routeSession = 0L
    override suspend fun refreshRoutes() {
        val epoch = routeSession
        try {
            val data = routeRequest("GET").jsonObject
            val routes = data.getValue("routes").jsonObject.map { (id, value) -> SignalKRouteCodec.decode(id, value.jsonObject) }
            val unavailable = data["unavailableIds"]?.jsonArray.orEmpty()
            if (epoch == routeSession) routeMutable.value = SignalKRoutesState(routeOrigin, true, routes,
                if (unavailable.isEmpty()) null else "${unavailable.size} Signal-K-Routen nicht darstellbar; Geometrie oder Attribute prüfen.")
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            if (epoch == routeSession) routeMutable.value = routeMutable.value.copy(ready = false, issue = failure.message ?: "Signal K nicht verfügbar")
        }
    }
    override suspend fun saveRoute(origin: String, id: String, name: String, points: List<SignalKRoutePoint>, expectedToken: String?, resourceJson: String?): SignalKRouteResource {
        require(origin == routeOrigin) { "Gateway-Quelle hat sich geändert; Entwurf bleibt erhalten" }
        val epoch = routeSession
        val body = buildJsonObject {
            put("expectedToken", expectedToken?.let(::JsonPrimitive) ?: JsonNull)
            put("route", SignalKRouteCodec.encode(id, name, points, resourceJson))
        }
        val result = SignalKRouteCodec.decode(id, routeRequest("PUT", "/" + SignalKRouteCodec.id(id), body).jsonObject)
        check(epoch == routeSession) { "Gateway-Sitzung hat sich geändert; Signal-K-Bestand erneut laden" }
        routeMutable.value = routeMutable.value.copy(routes = routeMutable.value.routes.filterNot { it.id == id } + result)
        return result
    }
    override suspend fun deleteRoute(origin: String, id: String, expectedToken: String) {
        require(origin == routeOrigin) { "Gateway-Quelle geändert" }
        val epoch = routeSession
        val reply = routeRequest("DELETE", "/" + SignalKRouteCodec.id(id), buildJsonObject { put("expectedToken", expectedToken) }).jsonObject
        check(epoch == routeSession && reply["deleted"]?.jsonPrimitive?.boolean == true) { "Löschung nicht bestätigt" }
        routeMutable.value = routeMutable.value.copy(routes = routeMutable.value.routes.filterNot { it.id == id })
    }
    private suspend fun routeRequest(method: String, suffix: String = "", body: JsonObject? = null): JsonElement {
        val client = gatewayHttp ?: error("Gateway-Zugang in Lumina Control einrichten; Entwurf bleibt lokal")
        val profile = gatewayProfile ?: error("Gateway-Zugang fehlt")
        val origin = routeOrigin ?: error("Gateway-Quelle fehlt")
        val epoch = routeSession
        val url = "$origin/v1/map/routes$suffix"
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache, no-store")
        profile.configureHttps(url, configureTls = {}, setHeader = request::header)
        if (body != null) request.method(method, body.toString().toRequestBody("application/json".toMediaType()))
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request.build()); continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) { response.use {
                    try {
                        check(it.code == 200) { when (it.code) { 409 -> "Route wurde in Signal K geändert. Neu laden; Entwurf bleibt erhalten."; 401, 403 -> "Signal-K-Schreibzugriff nicht erlaubt. Zugang prüfen."; else -> "Signal-K-Routen nicht verfügbar (HTTP ${it.code}); Gateway-Service und Ressourcenanbieter prüfen." } }
                        val input = it.body?.byteStream() ?: error("Leere Signal-K-Antwort")
                        val bytes = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                        while (true) { val n = input.read(chunk); if (n < 0) break; require(bytes.size() + n <= 1024 * 1024); bytes.write(chunk, 0, n) }
                        check(epoch == routeSession) { "Gateway-Sitzung geändert" }
                        val result = Json.parseToJsonElement(bytes.toString("UTF-8"))
                        if (continuation.isActive) continuation.resume(result)
                    } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                } }
            })
        }
    }
    private val json = Json { ignoreUnknownKeys = false }
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).pingInterval(3, TimeUnit.SECONDS).build()
    private val mutable = MutableStateFlow(HelmState())
    val state: StateFlow<HelmState> = mutable.asStateFlow()
    init { scope.launch { state.map { it.connection to it.snapshot?.revision }.distinctUntilChanged().collect { android.util.Log.i("No5Helm", "state=${it.first} revision=${it.second}") } } }
    private var gatewayProfile: com.no5.helm.security.GatewaySecurityProfile? = null
    private var gatewayHttp: OkHttpClient? = null
    private var endpointResolver: com.no5.helm.network.AndroidGatewayEndpointResolver? = null
    private var session: Job? = null
    private var socket: WebSocket? = null
    fun connect(endpoint: String) {
        closeSession()
        // A new connection is a new source session; never show a previous endpoint's vessel data.
        mutable.value = HelmState(ConnectionState.CONNECTING)
        session = scope.launch {
            var attempt = 0
            while (isActive) {
                mutable.value = mutable.value.copy(connection = if (mutable.value.snapshot == null) ConnectionState.CONNECTING else ConnectionState.SYNCING)
                try { follow(endpoint.trimEnd('/')); attempt = 0 }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    mutable.value = HelmReducer.lost(mutable.value, error.message ?: "Backend unavailable")
                    delay((1000L shl attempt.coerceAtMost(4)).coerceAtMost(15000)); attempt++
                }
            }
        }
    }
    fun needsHomeConfiguration() {
        closeSession()
        mutable.value = HelmState(ConnectionState.DISCONNECTED, issue = "Gateway-Zugang in Lumina Control einrichten oder prüfen.")
    }
    fun connectGateway(record: com.no5.gateway.GatewayConfiguration, profile: com.no5.helm.security.GatewaySecurityProfile) {
        closeSession()
        if (!record.usable || !com.no5.gateway.GatewayEnvironment.accepts(record.origin) || profile.origin.value != record.origin.url) {
            profile.close(); needsHomeConfiguration(); return
        }
        openGateway(record.origin.url, profile, com.no5.gateway.HomeGatewayDns(record))
    }
    fun connectGateway(session: com.no5.gateway.LuminaGatewaySession, context: android.content.Context) {
        closeSession()
        val resolver = com.no5.helm.network.AndroidGatewayEndpointResolver(context)
        endpointResolver = resolver
        openGateway(session.origin.url, session.profile(), com.no5.gateway.LuminaGatewayDns(session.origin, resolver, session.fallbackAddress))
    }
    private fun openGateway(origin: String, profile: com.no5.helm.security.GatewaySecurityProfile, dns: Dns) {
        val client = OkHttpClient.Builder().dns(dns)
            .connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(2000, TimeUnit.MILLISECONDS)
            .callTimeout(3500, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).apply {
                profile.configureOkHttp { factory, manager -> sslSocketFactory(factory, manager) }
            }.build()
        gatewayProfile = profile; gatewayHttp = client
        routeOrigin = origin
        routeMutable.value = SignalKRoutesState(routeOrigin)
        scope.launch { refreshRoutes() }
        mutable.value = HelmState(ConnectionState.CONNECTING)
        session = scope.launch {
            while (isActive) {
                try {
                    val fresh = readGateway(client, origin, profile)
                    // Observation freshness is re-evaluated even for an unchanged publication revision.
                    val previous = mutable.value.snapshot
                    if (previous != null && previous.serverId == fresh.serverId && fresh.revision < previous.revision) {
                        mutable.value = HelmState(ConnectionState.DEGRADED, previous.copy(state = previous.state.copy(ownship = null)), "Gateway lieferte ältere Daten; Position unbekannt.")
                    } else mutable.value = HelmReducer.snapshot(mutable.value, fresh)
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    mutable.value = HelmState(ConnectionState.DEGRADED, mutable.value.snapshot?.let {
                        it.copy(state = it.state.copy(ownship = null))
                    }, "Gateway-Daten nicht verfügbar. Verbindung in Lumina Control prüfen.")
                }
                delay(1000)
            }
        }
    }
    private suspend fun readGateway(client: OkHttpClient, endpoint: String,
        profile: com.no5.helm.security.GatewaySecurityProfile): Snapshot = suspendCancellableCoroutine { continuation ->
        val url = "$endpoint/v1/helm"
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache, no-store")
        profile.configureHttps(url, configureTls = {}, setHeader = request::header)
        val call = client.newCall(request.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        check(it.code == 200) { "Gateway response unavailable" }
                        val input = it.body?.byteStream() ?: error("Gateway response empty")
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(4096)
                        while (true) {
                            val n = input.read(chunk); if (n < 0) break
                            require(buffer.size() + n <= 262144) { "Gateway response too large" }
                            buffer.write(chunk, 0, n)
                        }
                        val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(buffer.toByteArray())).toString()
                        val fresh = GatewayV1Projection.decode(text, System.currentTimeMillis())
                        if (continuation.isActive) continuation.resume(fresh)
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
            }
        })
    }
    private suspend fun readSnapshot(endpoint: String): Snapshot = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url("$endpoint/no5/helm/v1/snapshot").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        check(it.isSuccessful) { "Snapshot HTTP ${it.code}" }
                        val value = json.decodeFromString<Snapshot>(it.body?.string() ?: error("Empty snapshot"))
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
            }
        })
    }
    private suspend fun follow(endpoint: String) {
        mutable.value = mutable.value.copy(connection = ConnectionState.SYNCING)
        val fresh = readSnapshot(endpoint)
        mutable.value = HelmReducer.snapshot(mutable.value, fresh)
        // Replay from the snapshot revision eliminates the HTTP/WS opening race.
        val messages = Channel<String>(capacity = 256)
        val stream = endpoint.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/no5/helm/v1/stream?afterRevision=${fresh.revision}&serverId=${fresh.serverId}"
        val ws = http.newWebSocket(Request.Builder().url(stream).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { if (messages.trySend(text).isFailure) messages.close(java.io.IOException("Stream backlog")) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { messages.close(t) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { messages.close(java.io.IOException("Stream closed $code")) }
        })
        socket = ws
        try {
            while (currentCoroutineContext().isActive) {
                val text = withTimeout(6000) { messages.receive() }
                val envelope = json.parseToJsonElement(text).jsonObject
                when (envelope["type"]?.jsonPrimitive?.content) {
                    "delta" -> when (val reduced = HelmReducer.delta(mutable.value, json.decodeFromString<Delta>(text))) {
                        is Reduction.Applied -> mutable.value = reduced.state
                        is Reduction.Ignored -> Unit
                        is Reduction.Resync -> {
                            mutable.value = reduced.state
                            // Close and reopen stream against the atomic new snapshot.
                            mutable.value = HelmReducer.snapshot(mutable.value, readSnapshot(endpoint))
                            return
                        }
                    }
                    "heartbeat", "hello" -> {
                        if (envelope["serverId"]?.jsonPrimitive?.content != mutable.value.snapshot?.serverId) return
                    }
                    "resyncRequired" -> { mutable.value = mutable.value.copy(connection = ConnectionState.SYNCING); return }
                    else -> error("Unsupported stream event")
                }
            }
        } finally { ws.cancel(); messages.cancel(); if (socket === ws) socket = null }
    }
    private fun closeSession() { routeSession++; routeOrigin = null; routeMutable.value = SignalKRoutesState(issue = "Gateway-Zugang in Lumina Control einrichten")
        session?.cancel(); session = null; socket?.cancel(); socket = null
        val retiredClient = gatewayHttp; gatewayHttp = null
        // Closing a pooled TLS socket may perform network I/O. Activity teardown
        // runs on Main and must not own that work (or a scope being cancelled).
        if (retiredClient != null) CoroutineScope(Dispatchers.IO).launch {
            try { retiredClient.dispatcher.cancelAll(); retiredClient.connectionPool.evictAll() }
            finally { retiredClient.dispatcher.executorService.shutdown() }
        }
        gatewayProfile?.close(); gatewayProfile = null
        endpointResolver?.close(); endpointResolver = null
    }
    override fun close() { closeSession(); mutable.value = mutable.value.copy(connection = ConnectionState.DISCONNECTED) }
}
