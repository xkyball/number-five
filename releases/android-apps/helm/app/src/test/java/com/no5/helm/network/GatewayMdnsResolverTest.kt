package com.no5.helm.network

import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayMdnsResolverTest {
    private val endpoint = "https://lysmarine.local:9443"
    private fun address(value: String) = InetAddress.getByName(value)
    private fun target() = requireNotNull(GatewayMdnsTarget.fromUrl(endpoint))
    private fun record(
        host: String = "lysmarine.local",
        port: Int = 9443,
        type: String = "_https._tcp.",
        name: String = "lysmarine",
        addresses: List<InetAddress> = listOf(address("192.168.68.80")),
    ) = GatewayMdnsServiceRecord(type, name, host, port, addresses)

    @Test fun onlyCanonicalHttpsLocalOriginsCanEnterMdns() {
        assertEquals("lysmarine", target().serviceName)
        assertNull(GatewayMdnsTarget.fromUrl("http://lysmarine.local:9443"))
        assertNull(GatewayMdnsTarget.fromUrl("https://192.168.68.80:9443"))
        assertNull(GatewayMdnsTarget.fromUrl("https://gateway.example:9443"))
        assertNull(GatewayMdnsTarget.fromUrl("https://user@lysmarine.local:9443"))
        assertNull(GatewayMdnsTarget.fromUrl("https://lysmarine.local:9443?key=forbidden"))
    }

    @Test fun onlyActualExactHttpsAdvertisementIsAccepted() {
        assertIs<GatewayEndpointResolution.Resolved>(GatewayMdnsAdvertisement.accept(target(), record()))
        // Android callbacks can use either this DNS-SD FQDN or the short `lysmarine` instance.
        assertIs<GatewayEndpointResolution.Resolved>(GatewayMdnsAdvertisement.accept(target(), record(name = "lysmarine._https._tcp.local.")))
        assertTrue(GatewayMdnsAdvertisement.matches(target(), "_https._tcp.", "lysmarine._https._tcp.local."))
        // Android 10 derives this leading separator from the resolved FQDN. It is exactly the
        // same service type as the discovery callback's `_https._tcp.`.
        assertIs<GatewayEndpointResolution.Resolved>(GatewayMdnsAdvertisement.accept(target(), record(type = "._https._tcp.")))
        assertTrue(GatewayMdnsAdvertisement.matches(target(), "._https._tcp.", "lysmarine"))
        assertEquals(GatewayMdnsFailure.HOST_MISMATCH, (GatewayMdnsAdvertisement.accept(target(), record(host = "other.local")) as GatewayEndpointResolution.Failed).failure)
        assertEquals(GatewayMdnsFailure.PORT_MISMATCH, (GatewayMdnsAdvertisement.accept(target(), record(port = 3000)) as GatewayEndpointResolution.Failed).failure)
        assertEquals(GatewayMdnsFailure.NO_MATCHING_SERVICE, (GatewayMdnsAdvertisement.accept(target(), record(type = "_http._tcp.")) as GatewayEndpointResolution.Failed).failure)
        assertEquals(GatewayMdnsFailure.NO_MATCHING_SERVICE, (GatewayMdnsAdvertisement.accept(target(), record(name = "other")) as GatewayEndpointResolution.Failed).failure)
        assertEquals(GatewayMdnsFailure.NO_MATCHING_SERVICE, (GatewayMdnsAdvertisement.accept(target(), record(name = "other._https._tcp.local.")) as GatewayEndpointResolution.Failed).failure)
        assertEquals(GatewayMdnsFailure.NO_MATCHING_SERVICE, (GatewayMdnsAdvertisement.accept(target(), record(name = "lysmarine._http._tcp.local.")) as GatewayEndpointResolution.Failed).failure)
        assertEquals(GatewayMdnsFailure.NO_MATCHING_SERVICE, (GatewayMdnsAdvertisement.accept(target(), record(type = ".._https._tcp.")) as GatewayEndpointResolution.Failed).failure)
    }

    @Test fun android10ResolvedNumericHostStillRequiresTheExactDnsSdIdentityAndPort() {
        val resolved = GatewayMdnsAdvertisement.acceptAndroidResolved(
            target(), "._https._tcp.", "lysmarine", 9443, listOf(address("192.168.68.80")),
        )
        assertIs<GatewayEndpointResolution.Resolved>(resolved)
        assertEquals(GatewayMdnsFailure.NO_MATCHING_SERVICE, (
            GatewayMdnsAdvertisement.acceptAndroidResolved(target(), "._http._tcp.", "lysmarine", 9443, listOf(address("192.168.68.80")))
                as GatewayEndpointResolution.Failed
            ).failure)
        assertEquals(GatewayMdnsFailure.NO_MATCHING_SERVICE, (
            GatewayMdnsAdvertisement.acceptAndroidResolved(target(), "._https._tcp.", "other", 9443, listOf(address("192.168.68.80")))
                as GatewayEndpointResolution.Failed
            ).failure)
        assertEquals(GatewayMdnsFailure.PORT_MISMATCH, (
            GatewayMdnsAdvertisement.acceptAndroidResolved(target(), "._https._tcp.", "lysmarine", 3000, listOf(address("192.168.68.80")))
                as GatewayEndpointResolution.Failed
            ).failure)
        // Records that do expose a hostname remain strict; only Android's API-29 numeric callback
        // uses the narrow path above.
        assertEquals(GatewayMdnsFailure.HOST_MISMATCH, (
            GatewayMdnsAdvertisement.accept(target(), record(host = "192.168.68.80")) as GatewayEndpointResolution.Failed
            ).failure)
    }

    @Test fun resolverBoundsAddressesCachesAndInvalidatesOnNetworkChange() {
        var clock = 1_000L
        val calls = AtomicInteger()
        val discovery = object : GatewayMdnsDiscovery {
            override fun discover(target: GatewayMdnsTarget, timeoutMs: Long, cancelled: () -> Boolean): GatewayEndpointResolution {
                calls.incrementAndGet()
                assertEquals(1_200L, timeoutMs)
                return GatewayEndpointResolution.Resolved(listOf(
                    address("::1"), address("192.168.68.81"), address("192.168.68.80"), address("ff02::fb"), address("192.168.68.82"), address("192.168.68.83"), address("192.168.68.84"),
                ))
            }
        }
        val resolver = BoundedGatewayMdnsResolver(discovery, nowMs = { clock })
        val first = assertIs<GatewayEndpointResolution.Resolved>(resolver.resolve(endpoint, 9_999))
        assertEquals(listOf("192.168.68.80", "192.168.68.81", "192.168.68.82", "192.168.68.83"), first.addresses.map { requireNotNull(it.hostAddress) })
        val cached = assertIs<GatewayEndpointResolution.Resolved>(resolver.resolve(endpoint, 1))
        assertEquals(first, cached)
        assertEquals(1, calls.get())
        clock += BoundedGatewayMdnsResolver.POSITIVE_TTL_MS + 1
        resolver.resolve(endpoint, 1_200)
        assertEquals(2, calls.get())
        resolver.invalidate()
        resolver.resolve(endpoint, 1_200)
        assertEquals(3, calls.get())
    }

    @Test fun negativeResultIsShortCachedAndCancellationNeverCaches() {
        var clock = 1_000L
        val calls = AtomicInteger()
        val discovery = GatewayMdnsDiscovery { _, _, _ ->
            calls.incrementAndGet()
            GatewayEndpointResolution.Failed(GatewayMdnsFailure.NO_MATCHING_SERVICE)
        }
        val resolver = BoundedGatewayMdnsResolver(discovery, nowMs = { clock })
        assertEquals(GatewayMdnsFailure.NO_MATCHING_SERVICE, (resolver.resolve(endpoint, 500) as GatewayEndpointResolution.Failed).failure)
        resolver.resolve(endpoint, 500)
        assertEquals(1, calls.get())
        clock += BoundedGatewayMdnsResolver.NEGATIVE_TTL_MS + 1
        resolver.resolve(endpoint, 500)
        assertEquals(2, calls.get())

        val cancelledCalls = AtomicInteger()
        val cancelled = BoundedGatewayMdnsResolver(
            GatewayMdnsDiscovery { _, _, _ -> cancelledCalls.incrementAndGet(); GatewayEndpointResolution.Resolved(listOf(address("192.168.68.80"))) },
            cancelled = { true },
        )
        assertEquals(GatewayMdnsFailure.CANCELLED, (cancelled.resolve(endpoint, 500) as GatewayEndpointResolution.Failed).failure)
        assertEquals(0, cancelledCalls.get())

        val timeout = BoundedGatewayMdnsResolver(GatewayMdnsDiscovery { _, timeoutMs, _ ->
            assertTrue(timeoutMs in BoundedGatewayMdnsResolver.MIN_DISCOVERY_TIMEOUT_MS..BoundedGatewayMdnsResolver.MAX_DISCOVERY_TIMEOUT_MS)
            GatewayEndpointResolution.Failed(GatewayMdnsFailure.TIMEOUT)
        })
        assertEquals(GatewayMdnsFailure.TIMEOUT, (timeout.resolve(endpoint, 99_999) as GatewayEndpointResolution.Failed).failure)
        assertEquals(GatewayEndpointResolution.NotApplicable, timeout.resolve("https://gateway.example:9443", 500))
    }

    @Test fun unscopedIpv6LinkLocalAddressCannotBecomeAMdnsSocketFallback() {
        val unscoped = requireNotNull(InetAddress.getByName("fe80::1") as? Inet6Address)
        val scoped = Inet6Address.getByAddress(null, unscoped.address, 7)
        val resolver = BoundedGatewayMdnsResolver(GatewayMdnsDiscovery { _, _, _ ->
            GatewayEndpointResolution.Resolved(listOf(unscoped, scoped))
        })
        val result = assertIs<GatewayEndpointResolution.Resolved>(resolver.resolve(endpoint, 500))
        assertEquals(listOf(requireNotNull(scoped.hostAddress)), result.addresses.map { requireNotNull(it.hostAddress) })
    }

    @Test fun okHttpUsesMappedAddressOnlyForTheExactLogicalHost() {
        val calls = mutableListOf<String>()
        val resolver = object : GatewayEndpointResolver {
            override fun resolve(url: String, timeoutMs: Int): GatewayEndpointResolution {
                calls += url
                return GatewayEndpointResolution.Resolved(listOf(address("192.168.68.80")))
            }
            override fun invalidate() = Unit
        }
        val dns = GatewayOkHttpDns(endpoint, resolver, 250)
        assertEquals(listOf("192.168.68.80"), dns.lookup("lysmarine.local").map { requireNotNull(it.hostAddress) })
        assertEquals(listOf("https://lysmarine.local:9443"), calls)
    }

}
