package com.no5.gateway

import com.no5.helm.network.GatewayEndpointResolution
import com.no5.helm.network.GatewayEndpointResolver
import java.net.InetAddress

/** A local socket hint. Logical origin, certificate hostname and audience remain unchanged. */
object GatewayIpv4Fallback {
    fun parse(value: String?): InetAddress? {
        if (value.isNullOrEmpty()) return null
        require(value.length <= 15 && value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")))
        val octets = value.split('.').map { part ->
            require(part.length <= 3 && (part.length == 1 || !part.startsWith('0')))
            part.toInt().also { require(it in 0..255) }
        }
        require(octets[0] != 0 && octets[0] != 127 && octets[0] < 224)
        return InetAddress.getByAddress(octets.map(Int::toByte).toByteArray())
    }
}

class GatewaySocketRoute(val origin: GatewayOrigin, val fallbackAddress: String?) {
    init { GatewayIpv4Fallback.parse(fallbackAddress) }
    override fun toString() = "GatewaySocketRoute(fallbackConfigured=${fallbackAddress != null})"
}

class GatewayFallbackEndpointResolver(private val delegate: GatewayEndpointResolver,
    private val route: () -> GatewaySocketRoute?) : GatewayEndpointResolver {
    private var cached: GatewayEndpointResolution.Resolved? = null
    private var cachedOrigin: String? = null
    private var cachedAddress: String? = null
    private var cachedUntil = 0L
    @Synchronized override fun resolve(url: String, timeoutMs: Int): GatewayEndpointResolution {
        val configured = route()?.takeIf { it.origin.url == url && it.origin.host.endsWith(".local") }
        val now = System.nanoTime()/1_000_000
        if(configured != null && url == cachedOrigin && configured.fallbackAddress == cachedAddress && now < cachedUntil) return requireNotNull(cached)
        val resolved = delegate.resolve(url, timeoutMs)
        if (resolved !is GatewayEndpointResolution.Failed) return resolved
        if(configured == null) return resolved
        return GatewayIpv4Fallback.parse(configured.fallbackAddress)?.let {
            GatewayEndpointResolution.Resolved(listOf(it)).also { result -> cached=result;cachedOrigin=url;cachedAddress=configured.fallbackAddress;cachedUntil=now+30_000 }
        } ?: resolved
    }
    @Synchronized override fun invalidate() { cached=null;cachedUntil=0;delegate.invalidate() }
    override fun close() = delegate.close()
}
