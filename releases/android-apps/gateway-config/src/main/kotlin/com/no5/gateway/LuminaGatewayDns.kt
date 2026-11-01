package com.no5.gateway

import com.no5.helm.network.GatewayEndpointResolution
import com.no5.helm.network.GatewayEndpointResolver
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/** Socket resolution only; HTTPS URL, certificate hostname, SNI and audience retain the origin. */
class LuminaGatewayDns(private val origin: GatewayOrigin, resolver: GatewayEndpointResolver, fallbackAddress: String? = null) : Dns {
    private val resolver = GatewayFallbackEndpointResolver(resolver) { GatewaySocketRoute(origin, fallbackAddress) }
    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.equals(origin.host, true)) throw UnknownHostException("GATEWAY_ORIGIN_MISMATCH")
        if (!GatewayEnvironment.accepts(origin)) throw UnknownHostException("GATEWAY_DEVICE_MISMATCH")
        return when (val resolved = resolver.resolve(origin.url, 1500)) {
            GatewayEndpointResolution.NotApplicable -> Dns.SYSTEM.lookup(hostname)
            is GatewayEndpointResolution.Resolved -> resolved.addresses
            is GatewayEndpointResolution.Failed -> throw UnknownHostException("GATEWAY_NAME_UNAVAILABLE")
        }
    }
}
