package com.no5.helm.network

import com.no5.helm.diagnostics.GatewayDiagnosticError
import com.no5.helm.diagnostics.GatewayDiagnosticLog
import com.no5.helm.diagnostics.GatewayDiagnosticNote
import com.no5.helm.diagnostics.GatewayDiagnosticOutcome
import com.no5.helm.diagnostics.GatewayDiagnosticPhase
import com.no5.helm.diagnostics.GatewayDiagnosticService
import com.no5.helm.security.GatewaySecurityProfile
import com.no5.helm.security.GatewaySecurityProfileProvider
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Result is intentionally metadata-only; no response payload is retained in diagnostics. */
data class GatewayProbeResult(val error: GatewayDiagnosticError = GatewayDiagnosticError.NONE, val httpStatus: Int? = null) {
    val successful get() = error == GatewayDiagnosticError.NONE
}

fun interface GatewayReadOnlyProbe {
    fun check(service: GatewayDiagnosticService, endpoint: String, profile: GatewaySecurityProfile): GatewayProbeResult
}

/** Explicit user-requested, read-only diagnostics. It never posts AUDIO/SONAR commands or starts acquisition. */
class GatewayConnectionTester(
    private val profiles: GatewaySecurityProfileProvider,
    private val endpointResolver: GatewayEndpointResolver = GatewayEndpointResolver.NONE,
    private val probe: GatewayReadOnlyProbe = NetworkGatewayReadOnlyProbe(endpointResolver),
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun test(savedEndpoint: String, log: GatewayDiagnosticLog, onProgress: () -> Unit = {}): List<GatewayProbeResult> {
        val attempt = log.newAttemptId()
        log.record(attempt, GatewayDiagnosticService.SECURITY, GatewayDiagnosticPhase.TEST_START,
            GatewayDiagnosticOutcome.STARTED, note = GatewayDiagnosticNote.READ_ONLY)
        onProgress()
        val profile = profiles.load(savedEndpoint)
        if (profile == null) {
            log.record(attempt, GatewayDiagnosticService.SECURITY, GatewayDiagnosticPhase.TEST_RESULT,
                GatewayDiagnosticOutcome.SKIPPED, error = GatewayDiagnosticError.PROFILE_MISSING)
            onProgress()
            return emptyList()
        }
        return profile.use {
            log.record(attempt, GatewayDiagnosticService.SECURITY, GatewayDiagnosticPhase.TEST_RESULT,
                GatewayDiagnosticOutcome.SUCCESS, note = GatewayDiagnosticNote.MATERIAL_READY)
            onProgress()
            GatewayDiagnosticService.entries.filter { it in READ_ONLY_SERVICES }.map { service ->
                val started = now()
                log.record(attempt, service, GatewayDiagnosticPhase.TEST_START, GatewayDiagnosticOutcome.STARTED,
                    note = GatewayDiagnosticNote.READ_ONLY)
                onProgress()
                val result = try { probe.check(service, savedEndpoint, profile) }
                catch (failure: Exception) { GatewayProbeResult(error = GatewayProbeErrors.classify(failure)) }
                log.record(attempt, service, GatewayDiagnosticPhase.TEST_RESULT,
                    if (result.successful) GatewayDiagnosticOutcome.SUCCESS else GatewayDiagnosticOutcome.FAILURE,
                    durationMs = (now() - started).coerceAtLeast(0), error = result.error, httpStatus = result.httpStatus,
                    note = GatewayDiagnosticNote.READ_ONLY)
                onProgress()
                result
            }
        }
    }

    companion object {
        private val READ_ONLY_SERVICES = setOf(
            GatewayDiagnosticService.HELM,
            GatewayDiagnosticService.WEATHER,
            GatewayDiagnosticService.AUDIO,
            GatewayDiagnosticService.SONAR_HTTP,
            GatewayDiagnosticService.SONAR_WS,
        )
    }
}

/** Production-only implementation, deliberately constructed only from the explicit Test button. */
class NetworkGatewayReadOnlyProbe(
    private val endpointResolver: GatewayEndpointResolver = GatewayEndpointResolver.NONE,
    private val transportFactory: (String, GatewaySecurityProfile) -> PiHttpTransport = { endpoint, profile ->
        UrlConnectionPiTransport(endpoint, PiNetworkPolicy.SONAR, profile, endpointResolver)
    },
) : GatewayReadOnlyProbe {
    override fun check(service: GatewayDiagnosticService, endpoint: String, profile: GatewaySecurityProfile): GatewayProbeResult = when (service) {
        GatewayDiagnosticService.HELM -> http(endpoint, "/v1/helm", profile) { PiV1Decoder.helm(it) }
        GatewayDiagnosticService.WEATHER -> http(endpoint, "/v1/weather", profile) { PiV1Decoder.weather(it) }
        GatewayDiagnosticService.AUDIO -> http(endpoint, "/v1/audio", profile) { AudioV1Decoder.state(it) }
        GatewayDiagnosticService.SONAR_HTTP -> http(endpoint, "/v1/sonar", profile) { SonarV1Decoder.state(it) }
        GatewayDiagnosticService.SONAR_WS -> websocket(endpoint, profile)
        else -> GatewayProbeResult(GatewayDiagnosticError.INTERNAL)
    }

    private fun http(endpoint: String, path: String, profile: GatewaySecurityProfile, decode: (String) -> Unit): GatewayProbeResult {
        val transport = transportFactory(endpoint, profile)
        var httpStatus: Int? = null
        return try {
            val body = transport.get(path)
            httpStatus = 200 // The transport returns only HTTP_OK; preserve it even if decoding fails.
            decode(body)
            GatewayProbeResult(httpStatus = httpStatus)
        } catch (failure: Exception) {
            val status = (failure as? PiHttpStatusException)?.statusCode ?: httpStatus
            GatewayProbeResult(GatewayProbeErrors.classify(failure), status)
        } finally { transport.close() }
    }

    private fun websocket(endpoint: String, profile: GatewaySecurityProfile): GatewayProbeResult {
        val finished = CountDownLatch(1)
        var result = GatewayProbeResult(GatewayDiagnosticError.TIMEOUT)
        val client = OkHttpClient.Builder().apply {
            profile.configureOkHttp { factory, manager -> sslSocketFactory(factory, manager) }
            dns(GatewayOkHttpDns(endpoint, endpointResolver, 1_500))
        }.connectTimeout(1_500, TimeUnit.MILLISECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(1_500, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).build()
        var socket: WebSocket? = null
        try {
            socket = client.newWebSocket(sonarRequest(endpoint.trimEnd('/') + "/v1/sonar/stream", profile), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    result = if (response.header("Sec-WebSocket-Protocol") == SonarV1Decoder.SUBPROTOCOL) GatewayProbeResult(httpStatus = response.code)
                    else GatewayProbeResult(GatewayDiagnosticError.WEBSOCKET, response.code)
                    finished.countDown()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    result = GatewayProbeResult(GatewayProbeErrors.classify(t), response?.code)
                    finished.countDown()
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    result = GatewayProbeResult(GatewayDiagnosticError.WEBSOCKET, code.takeIf { it in 100..599 })
                    finished.countDown()
                }
            })
            finished.await(3, TimeUnit.SECONDS)
            return result
        } catch (failure: Exception) {
            return GatewayProbeResult(GatewayProbeErrors.classify(failure))
        } finally {
            socket?.cancel()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}

object GatewayProbeErrors {
    fun classify(failure: Throwable): GatewayDiagnosticError = when (failure) {
        is GatewayMdnsException -> when (failure.failure) {
            GatewayMdnsFailure.SERVICE_UNAVAILABLE -> GatewayDiagnosticError.MDNS_UNAVAILABLE
            GatewayMdnsFailure.NO_MATCHING_SERVICE -> GatewayDiagnosticError.MDNS_SERVICE
            GatewayMdnsFailure.HOST_MISMATCH -> GatewayDiagnosticError.MDNS_HOST
            GatewayMdnsFailure.PORT_MISMATCH -> GatewayDiagnosticError.MDNS_PORT
            GatewayMdnsFailure.TIMEOUT -> GatewayDiagnosticError.MDNS_TIMEOUT
            GatewayMdnsFailure.CANCELLED -> GatewayDiagnosticError.CANCELLED
        }
        is PiHttpStatusException -> when (failure.statusCode) {
            401 -> GatewayDiagnosticError.HTTP_401
            403 -> GatewayDiagnosticError.HTTP_403
            else -> GatewayDiagnosticError.HTTP_OTHER
        }
        is UnknownHostException -> GatewayDiagnosticError.DNS
        is ConnectException -> GatewayDiagnosticError.CONNECT
        is SocketTimeoutException -> GatewayDiagnosticError.TIMEOUT
        is SSLPeerUnverifiedException -> GatewayDiagnosticError.TLS_HOSTNAME
        is SSLHandshakeException -> GatewayDiagnosticError.TLS_CA
        is SSLException -> GatewayDiagnosticError.TLS_HANDSHAKE
        is PiProtocolException -> GatewayDiagnosticError.SCHEMA
        is IOException -> GatewayDiagnosticError.SOURCE_UNAVAILABLE
        else -> GatewayDiagnosticError.INTERNAL
    }
}
