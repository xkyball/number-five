package com.no5.helm.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A deliberately narrow mDNS bridge for the provisioned HTTPS gateway only.
 *
 * Android 10's normal getaddrinfo path does not necessarily consume the OEM mDNS daemon.  NSD is
 * not a general DNS replacement: a result is accepted only when an actually advertised
 * `_https._tcp` service has the exact expected service name, hostname and port.  The logical URL
 * is never changed to an IP address; the mapping is used only for the underlying socket.
 */
internal data class GatewayMdnsTarget(
    val origin: String,
    val host: String,
    val port: Int,
    val serviceName: String,
) {
    companion object {
        const val SERVICE_TYPE = "_https._tcp."

        fun fromUrl(url: String): GatewayMdnsTarget? = runCatching {
            val uri = URI(url)
            if (uri.scheme?.lowercase() != "https" || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
            val host = uri.host?.trimEnd('.')?.lowercase() ?: return null
            if (!host.endsWith(".local") || host.length <= ".local".length) return null
            val port = if (uri.port == -1) 443 else uri.port
            if (port !in 1..65_535) return null
            val serviceName = host.removeSuffix(".local")
            GatewayMdnsTarget("https://$host:$port", host, port, serviceName)
        }.getOrNull()
    }
}

internal data class GatewayMdnsServiceRecord(
    val serviceType: String,
    val serviceName: String,
    val host: String,
    val port: Int,
    val addresses: List<InetAddress>,
)

/** Pure acceptance policy, shared by NSD and JVM tests. A record is not a general DNS answer. */
internal object GatewayMdnsAdvertisement {
    fun accept(target: GatewayMdnsTarget, record: GatewayMdnsServiceRecord): GatewayEndpointResolution {
        if (!matches(target, record.serviceType, record.serviceName))
            return GatewayEndpointResolution.Failed(GatewayMdnsFailure.NO_MATCHING_SERVICE)
        if (!record.host.trimEnd('.').equals(target.host, ignoreCase = true))
            return GatewayEndpointResolution.Failed(GatewayMdnsFailure.HOST_MISMATCH)
        if (record.port != target.port) return GatewayEndpointResolution.Failed(GatewayMdnsFailure.PORT_MISMATCH)
        return GatewayEndpointResolution.Resolved(record.addresses)
    }

    /**
     * API-29 NSD has already resolved the advertised hostname before it calls the app, but
     * NsdServiceInfo exposes only the resulting numeric InetAddress. Do not fabricate a hostname
     * by reverse DNS and do not compare that address text to the logical `.local` origin. The
     * exact DNS-SD type, instance label and port remain mandatory; TLS still validates the
     * original logical hostname against the origin-bound CA before any gateway header is sent.
     */
    fun acceptAndroidResolved(
        target: GatewayMdnsTarget,
        serviceType: String?,
        serviceName: String?,
        port: Int,
        addresses: List<InetAddress>,
    ): GatewayEndpointResolution {
        if (!matches(target, serviceType, serviceName))
            return GatewayEndpointResolution.Failed(GatewayMdnsFailure.NO_MATCHING_SERVICE)
        if (port != target.port) return GatewayEndpointResolution.Failed(GatewayMdnsFailure.PORT_MISMATCH)
        return GatewayEndpointResolution.Resolved(addresses)
    }

    /**
     * Android NSD has two canonical callback shapes: a short instance name and a DNS-SD FQDN.
     * Android 10 also derives the resolved type from the FQDN's remainder, yielding
     * `._https._tcp.` (with one separator dot) instead of the discovery form `_https._tcp.`.
     * Normalize only those wire-equivalent forms; never accept a differently typed or merely
     * suffix-similar service.
     */
    fun matches(target: GatewayMdnsTarget, serviceType: String?, serviceName: String?): Boolean =
        normaliseType(serviceType) == GatewayMdnsTarget.SERVICE_TYPE &&
            normaliseServiceName(serviceName)?.equals(target.serviceName, ignoreCase = true) == true

    fun normaliseType(value: String?): String = value?.trim()?.lowercase()?.let { raw ->
        val withoutAndroid10Separator = raw.removePrefix(".")
        if (withoutAndroid10Separator.endsWith('.')) withoutAndroid10Separator else "$withoutAndroid10Separator."
    } ?: ""

    private fun normaliseServiceName(value: String?): String? {
        val name = value?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        val dnsSdSuffix = ".${GatewayMdnsTarget.SERVICE_TYPE.removeSuffix(".")}.local"
        return if (name.endsWith(dnsSdSuffix, ignoreCase = true)) {
            name.dropLast(dnsSdSuffix.length).takeIf { it.isNotEmpty() }
        } else name
    }
}

enum class GatewayMdnsFailure {
    SERVICE_UNAVAILABLE,
    NO_MATCHING_SERVICE,
    HOST_MISMATCH,
    PORT_MISMATCH,
    TIMEOUT,
    CANCELLED,
}

sealed interface GatewayEndpointResolution {
    data object NotApplicable : GatewayEndpointResolution
    data class Resolved(val addresses: List<InetAddress>) : GatewayEndpointResolution
    data class Failed(val failure: GatewayMdnsFailure) : GatewayEndpointResolution
}

/** Safe typed failure; no host, service record, certificate, header or response data is retained. */
internal class GatewayMdnsException(val failure: GatewayMdnsFailure) : IOException("Gateway mDNS resolution failed: ${failure.name}")

internal fun interface GatewayMdnsDiscovery {
    fun discover(target: GatewayMdnsTarget, timeoutMs: Long, cancelled: () -> Boolean): GatewayEndpointResolution
}

interface GatewayEndpointResolver : AutoCloseable {
    fun resolve(url: String, timeoutMs: Int): GatewayEndpointResolution
    fun invalidate()
    override fun close() = Unit

    companion object {
        val NONE = object : GatewayEndpointResolver {
            override fun resolve(url: String, timeoutMs: Int): GatewayEndpointResolution =
                if (GatewayMdnsTarget.fromUrl(url) == null) GatewayEndpointResolution.NotApplicable
                else GatewayEndpointResolution.Failed(GatewayMdnsFailure.SERVICE_UNAVAILABLE)
            override fun invalidate() = Unit
        }
    }
}

/**
 * Thread-safe bounded cache around a discovery implementation.  Both positive and negative
 * entries are cached so a 20 Hz poller cannot turn a missing service into discovery churn.
 */
internal class BoundedGatewayMdnsResolver(
    private val discovery: GatewayMdnsDiscovery,
    private val nowMs: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
    private val cancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
) : GatewayEndpointResolver {
    private data class CacheEntry(val result: GatewayEndpointResolution, val expiresAtMs: Long)
    private val lock = Any()
    private val cache = object : LinkedHashMap<String, CacheEntry>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean = size > MAX_CACHE_ENTRIES
    }

    override fun resolve(url: String, timeoutMs: Int): GatewayEndpointResolution {
        val target = GatewayMdnsTarget.fromUrl(url) ?: return GatewayEndpointResolution.NotApplicable
        if (cancelled()) return GatewayEndpointResolution.Failed(GatewayMdnsFailure.CANCELLED)
        val boundedTimeout = timeoutMs.toLong().coerceIn(MIN_DISCOVERY_TIMEOUT_MS, MAX_DISCOVERY_TIMEOUT_MS)
        // Serialise discovery and cache insertion. Four source adapters can start together; one
        // bounded NSD lookup per origin is enough and prevents a poller fan-out from multiplying
        // multicast work when a service is absent.
        synchronized(lock) {
            val now = nowMs()
            cache[target.origin]?.takeIf { it.expiresAtMs > now }?.let { return it.result }
            cache.remove(target.origin)
            val result = discovery.discover(target, boundedTimeout, cancelled).sanitised()
            if (result !is GatewayEndpointResolution.Failed || result.failure != GatewayMdnsFailure.CANCELLED) {
                val ttl = if (result is GatewayEndpointResolution.Resolved) POSITIVE_TTL_MS else NEGATIVE_TTL_MS
                cache[target.origin] = CacheEntry(result, nowMs() + ttl)
            }
            return result
        }
    }

    override fun invalidate() = synchronized(lock) { cache.clear() }

    private fun GatewayEndpointResolution.sanitised(): GatewayEndpointResolution = when (this) {
        is GatewayEndpointResolution.NotApplicable -> GatewayEndpointResolution.Failed(GatewayMdnsFailure.NO_MATCHING_SERVICE)
        is GatewayEndpointResolution.Failed -> this
        is GatewayEndpointResolution.Resolved -> {
            val usable = addresses.filter(::isUsable).distinct().sortedWith(compareBy<InetAddress> { if (it is Inet4Address) 0 else 1 }.thenBy { it.hostAddress })
            if (usable.isEmpty()) GatewayEndpointResolution.Failed(GatewayMdnsFailure.NO_MATCHING_SERVICE)
            else GatewayEndpointResolution.Resolved(usable.take(MAX_ADDRESSES))
        }
    }

    private fun isUsable(address: InetAddress): Boolean =
        !address.isAnyLocalAddress &&
            !address.isLoopbackAddress &&
            !address.isMulticastAddress &&
            // API-29 NSD can report a link-local v6 address without the interface scope a Socket
            // needs to route it. Keep globally routable/scoped v6 and IPv4; never let an
            // unusable v6 fallback hide the already verified logical TLS host.
            (address !is Inet6Address || !address.isLinkLocalAddress || address.scopeId != 0)

    companion object {
        const val MAX_CACHE_ENTRIES = 8
        const val MAX_ADDRESSES = 4
        const val MIN_DISCOVERY_TIMEOUT_MS = 150L
        const val MAX_DISCOVERY_TIMEOUT_MS = 1_200L
        const val POSITIVE_TTL_MS = 60_000L
        const val NEGATIVE_TTL_MS = 15_000L
    }
}

/** Android NSD implementation. It resolves one exact, actually advertised HTTPS service only. */
@Suppress("DEPRECATION") // Android 10 (API 29) supplies this NSD resolve API; newer callbacks are unavailable there.
internal class AndroidGatewayMdnsDiscovery(context: Context) : GatewayMdnsDiscovery {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    override fun discover(target: GatewayMdnsTarget, timeoutMs: Long, cancelled: () -> Boolean): GatewayEndpointResolution {
        if (cancelled()) return GatewayEndpointResolution.Failed(GatewayMdnsFailure.CANCELLED)
        val finished = CountDownLatch(1)
        val started = AtomicBoolean(false)
        val stopRequested = AtomicBoolean(false)
        val resolving = AtomicBoolean(false)
        val result = AtomicReference<GatewayEndpointResolution?>(null)
        lateinit var listener: NsdManager.DiscoveryListener
        fun finish(value: GatewayEndpointResolution) {
            if (result.compareAndSet(null, value)) {
                finished.countDown()
            }
        }
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { started.set(true) }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish(GatewayEndpointResolution.Failed(GatewayMdnsFailure.SERVICE_UNAVAILABLE))
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (cancelled() || !matchesCandidate(target, serviceInfo) || !resolving.compareAndSet(false, true)) return
                try {
                    manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            finish(GatewayEndpointResolution.Failed(GatewayMdnsFailure.NO_MATCHING_SERVICE))
                        }
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            finish(recordResolution(target, serviceInfo, resolved))
                        }
                    })
                } catch (_: IllegalArgumentException) {
                    finish(GatewayEndpointResolution.Failed(GatewayMdnsFailure.SERVICE_UNAVAILABLE))
                }
            }
        }
        try {
            manager.discoverServices(GatewayMdnsTarget.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            val completed = finished.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed && result.get() == null) finish(GatewayEndpointResolution.Failed(if (cancelled()) GatewayMdnsFailure.CANCELLED else GatewayMdnsFailure.TIMEOUT))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            finish(GatewayEndpointResolution.Failed(GatewayMdnsFailure.CANCELLED))
        } catch (_: IllegalArgumentException) {
            finish(GatewayEndpointResolution.Failed(GatewayMdnsFailure.SERVICE_UNAVAILABLE))
        } finally {
            if (started.get() && stopRequested.compareAndSet(false, true)) runCatching { manager.stopServiceDiscovery(listener) }
        }
        return result.get() ?: GatewayEndpointResolution.Failed(GatewayMdnsFailure.TIMEOUT)
    }

    private fun matchesCandidate(target: GatewayMdnsTarget, service: NsdServiceInfo): Boolean =
        GatewayMdnsAdvertisement.matches(target, service.serviceType, service.serviceName)

    private fun recordResolution(
        target: GatewayMdnsTarget,
        discovered: NsdServiceInfo,
        resolved: NsdServiceInfo,
    ): GatewayEndpointResolution {
        val address = resolved.host ?: return GatewayEndpointResolution.Failed(GatewayMdnsFailure.HOST_MISMATCH)
        return GatewayMdnsAdvertisement.acceptAndroidResolved(
            target = target,
            // The Android 10 resolve result can omit a type even though it belongs to the exact
            // candidate that NSD just reported. The candidate was matched before resolve; retain
            // that type rather than broadening the accepted service class. When present,
            // GatewayMdnsAdvertisement normalizes Android 10's `._https._tcp.` form.
            serviceType = resolved.serviceType?.takeIf { it.isNotBlank() } ?: discovered.serviceType.orEmpty(),
            serviceName = resolved.serviceName,
            port = resolved.port,
            addresses = listOf(address),
        )
    }
}

/** Owns Android network callbacks and clears the bounded cache when network identity changes. */
class AndroidGatewayEndpointResolver(context: Context) : GatewayEndpointResolver {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val resolver = BoundedGatewayMdnsResolver(
        AndroidGatewayMdnsDiscovery(appContext),
        cancelled = { closed.get() || Thread.currentThread().isInterrupted },
    )
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = resolver.invalidate()
        override fun onLost(network: Network) = resolver.invalidate()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = resolver.invalidate()
    }

    init { runCatching { connectivity.registerDefaultNetworkCallback(callback) } }

    override fun resolve(url: String, timeoutMs: Int): GatewayEndpointResolution =
        if (closed.get()) GatewayEndpointResolution.Failed(GatewayMdnsFailure.CANCELLED) else resolver.resolve(url, timeoutMs)

    override fun invalidate() = resolver.invalidate()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            resolver.invalidate()
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }
}
