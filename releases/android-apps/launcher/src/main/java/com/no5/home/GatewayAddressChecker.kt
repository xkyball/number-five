package com.no5.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.no5.gateway.*
import com.no5.helm.security.AndroidGatewayCredentialStore
import com.no5.helm.security.GatewayCaBootstrap
import com.no5.helm.security.GatewayKnownAuthorities
import com.no5.helm.security.StoredGatewaySecurityProfileProvider
import com.no5.helm.network.PiV1Decoder
import com.no5.helm.network.PiProtocolException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal interface GatewayAddressChecker {
    fun online(): Boolean
    fun discover(origin: GatewayOrigin): String?
    fun reachable(origin: GatewayOrigin, address: String): Boolean
    fun failure(): GatewayCheckFailure? = null
    fun diagnostic(): GatewayCheckDiagnostic? = null
}

/** Only fixed UI messages cross this boundary; never exception text or response bodies. */
internal enum class GatewayCheckFailure(val message: String) {
    OFFLINE("Kein Netzwerk. Bitte die Netzwerkverbindung des Geräts prüfen."),
    NETWORK_PERMISSION("Android verweigert Home den Netzwerkzugriff. Bitte Home erneut starten."),
    CONNECTION("Keine Verbindung zum Gateway. Bitte Verbindungsadresse und Netzwerk prüfen."),
    TLS("Zertifikatsprüfung fehlgeschlagen. Bitte Gatewayname und öffentliche Gateway-CA prüfen."),
    AUTHENTICATION("Gateway-Zugang abgewiesen. Bitte Passwort oder Gateway-Schlüssel in Home prüfen."),
    RESPONSE("Der Gateway liefert noch keinen bestätigten Status. Bitte den Gateway-Dienst prüfen."),
    DATA("Die Gateway-Antwort entspricht nicht dem Statusvertrag. Bitte den Gateway-Dienst prüfen.");

    companion object {
        fun from(error: Exception): GatewayCheckFailure = when {
            error is SecurityException -> NETWORK_PERMISSION
            error is javax.net.ssl.SSLException || error is java.security.cert.CertificateException -> TLS
            error is PiProtocolException || error is java.nio.charset.CharacterCodingException || error is IllegalArgumentException -> DATA
            error is java.net.SocketException && (error.message.orEmpty().contains("permission denied", true) ||
                error.message.orEmpty().contains("operation not permitted", true)) -> NETWORK_PERMISSION
            else -> CONNECTION
        }
    }
}

/** Before provisioning, check TCP only. With a Home profile, validate TLS and the gateway status contract. */
internal class AndroidGatewayAddressChecker(context: Context) : GatewayAddressChecker {
    private val app = context.applicationContext
    @Volatile private var lastFailure: GatewayCheckFailure? = null
    @Volatile private var lastDiagnostic: GatewayCheckDiagnostic? = null
    override fun failure() = lastFailure
    override fun diagnostic() = lastDiagnostic
    private fun failed(stage: GatewayCheckStage, error: Exception): Boolean {
        lastFailure = GatewayCheckFailure.from(error)
        lastDiagnostic = GatewayCheckDiagnostic.from(stage, error)
        android.util.Log.i("No5GatewayCheck", lastDiagnostic!!.publicRecord())
        return false
    }
    override fun online() = (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork != null
    override fun reachable(origin: GatewayOrigin, address: String): Boolean {
        lastFailure = null; lastDiagnostic = null
        val numeric = gatewayAddress(address) ?: return false
        val trust = GatewayCaStore(app)
        val record = GatewayConfiguration(origin, numeric.hostAddress!!, GatewayAddressSource.MANUAL, checkedAt = 0)
        val secureOrigin = com.no5.helm.security.GatewayOrigin.fromEndpoint(origin.url)
        if (GatewayKnownAuthorities.fingerprint(secureOrigin) != null) {
            try {
                if (GatewayCaBootstrap(trust).ensure(record)) GatewayConfigurationStore(app).materialChanged()
            } catch (_: Exception) { lastFailure = GatewayCheckFailure.TLS; return false }
        }
        val profiles = StoredGatewaySecurityProfileProvider(AndroidGatewayCredentialStore(app), trust)
        val profile = profiles.load(origin.url)
        if (profile != null) return profile.use { security ->
            val client = OkHttpClient.Builder().dns(HomeGatewayDns(record)).followRedirects(false).followSslRedirects(false)
                .connectTimeout(1200, TimeUnit.MILLISECONDS).readTimeout(1200, TimeUnit.MILLISECONDS).callTimeout(2500, TimeUnit.MILLISECONDS).apply {
                    security.configureOkHttp { factory, manager -> sslSocketFactory(factory, manager) }
                }.build()
            try {
                val url = "${origin.url}/v1/status"
                val request = Request.Builder().url(url).header("Cache-Control", "no-cache, no-store")
                security.configureHttps(url, {}, request::header)
                client.newCall(request.build()).execute().use { response ->
                    if (response.code != 200) {
                        lastFailure = if (response.code == 401 || response.code == 403) GatewayCheckFailure.AUTHENTICATION else GatewayCheckFailure.RESPONSE
                        return@use false
                    }
                    val stream = requireNotNull(response.body).byteStream()
                    val bytes = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(4096)
                    while (true) { val n = stream.read(chunk); if (n < 0) break; require(bytes.size() + n <= 65536); bytes.write(chunk, 0, n) }
                    val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
                    PiV1Decoder.gatewayStatus(text)
                    true
                }
            } catch (error: Exception) { failed(GatewayCheckStage.HTTPS_STATUS, error) }
            finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
        return try { Socket().use { it.connect(InetSocketAddress(numeric, origin.port), 1200) }; true }
        catch (error: Exception) { failed(GatewayCheckStage.TCP_CONNECT, error) }
    }

    @Suppress("DEPRECATION") // NsdServiceInfo.host and ResolveListener exist on API29.
    override fun discover(origin: GatewayOrigin): String? {
        val instance = origin.mdnsInstance ?: return null
        val manager = app.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = AtomicReference<String?>(null)
        val finished = AtomicBoolean(false)
        val resolving = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { if (finished.get()) runCatching { manager.stopServiceDiscovery(this) } }
            override fun onStartDiscoveryFailed(type: String, code: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                if (finished.get() || !matches(service.serviceName, service.serviceType, instance) || !resolving.compareAndSet(false, true)) return
                try { manager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) { latch.countDown() }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        if (!finished.get() && matches(info.serviceName ?: service.serviceName, info.serviceType ?: service.serviceType, instance) && info.port == origin.port)
                            info.host?.hostAddress?.let(::gatewayAddress)?.hostAddress?.let(found::set)
                        latch.countDown()
                    }
                }) } catch (_: RuntimeException) { latch.countDown() }
            }
        }
        try {
            manager.discoverServices("_https._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(2500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        catch (_: RuntimeException) { return null }
        finally { finished.set(true); runCatching { manager.stopServiceDiscovery(listener) } }
        return found.get()
    }

    companion object {
        fun matches(name: String?, type: String?, instance: String): Boolean {
            val normalizedType = type?.trim()?.lowercase()?.removePrefix(".")?.trimEnd('.')
            val trimmedName = name?.trim()?.trimEnd('.')
            val suffix = "._https._tcp.local"
            val normalizedName = if (trimmedName?.endsWith(suffix, true) == true) trimmedName.dropLast(suffix.length) else trimmedName
            return normalizedType == "_https._tcp" && normalizedName.equals(instance, true)
        }
    }
}

internal class GatewayCheckPolicy(private val checker: GatewayAddressChecker) {
    fun check(origin: GatewayOrigin, previous: GatewayConfiguration?, now: Long): GatewayConfiguration? {
        require(GatewayEnvironment.accepts(origin))
        if (!checker.online()) return previous?.takeIf { it.origin == origin }
        val discovered = checker.discover(origin)
        if (discovered != null && gatewayAddress(discovered) != null && checker.reachable(origin, discovered))
            return GatewayConfiguration(origin, gatewayAddress(discovered)!!.hostAddress!!, GatewayAddressSource.MDNS, checkedAt = now)
        val current = previous?.takeIf { it.origin == origin } ?: return null
        return current.checked(checker.reachable(origin, current.address), true, now)
    }

    fun manual(origin: GatewayOrigin, address: String, now: Long): GatewayConfiguration? {
        require(GatewayEnvironment.accepts(origin))
        val numeric = gatewayAddress(address)?.hostAddress ?: return null
        if (!checker.online() || !checker.reachable(origin, numeric)) return null
        return GatewayConfiguration(origin, numeric, GatewayAddressSource.MANUAL, checkedAt = now)
    }
}
