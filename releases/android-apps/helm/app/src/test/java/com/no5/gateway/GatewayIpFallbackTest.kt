package com.no5.gateway

import com.no5.helm.network.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class GatewayIpFallbackTest {
    private val origin = requireNotNull(GatewayOrigin.parse("https://lysmarine.local:9443"))
    private class Resolver(var result: GatewayEndpointResolution) : GatewayEndpointResolver {
        override fun resolve(url: String, timeoutMs: Int) = result
        override fun invalidate() = Unit
    }
    @Test fun mdnsFailureUsesOnlyLocallyConfiguredAddressForTheExactOrigin() {
        val failed = GatewayEndpointResolution.Failed(GatewayMdnsFailure.NO_MATCHING_SERVICE)
        val delegate = Resolver(failed)
        val resolver = GatewayFallbackEndpointResolver(delegate) { GatewaySocketRoute(origin,"192.0.2.15") }
        val result = resolver.resolve(origin.url,1500) as GatewayEndpointResolution.Resolved
        assertEquals("192.0.2.15",result.addresses.single().hostAddress)
        assertSame(failed,resolver.resolve("https://other.local:9443",1500))
        assertEquals("https://lysmarine.local:9443",origin.url)
    }
    @Test fun successfulMdnsKeepsItsAddressAndMissingFallbackStaysUnavailable() {
        val success = GatewayEndpointResolution.Resolved(listOf(InetAddress.getByAddress(byteArrayOf(10,0,0,7))))
        val delegate = Resolver(success)
        assertSame(success,GatewayFallbackEndpointResolver(delegate) { GatewaySocketRoute(origin,"192.0.2.15") }.resolve(origin.url,1500))
        val failed = GatewayEndpointResolution.Failed(GatewayMdnsFailure.NO_MATCHING_SERVICE);delegate.result=failed
        assertSame(failed,GatewayFallbackEndpointResolver(delegate) { GatewaySocketRoute(origin,null) }.resolve(origin.url,1500))
    }
    @Test fun addressMustBeLiteralAndCannotContainPortsUrlsOrLoopback() {
        for(value in listOf("gateway.local","127.0.0.1","0.0.0.0","224.0.0.1","192.0.2.999","192.000.2.15","https://192.0.2.15","192.0.2.15:9443")) {
            try { GatewayIpv4Fallback.parse(value);fail("Invalid fallback accepted") } catch(_:IllegalArgumentException) {}
        }
    }
}
