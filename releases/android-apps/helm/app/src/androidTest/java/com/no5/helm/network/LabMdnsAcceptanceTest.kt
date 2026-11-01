package com.no5.helm.network

import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.security.AndroidGatewaySecurityStore
import com.no5.helm.security.AndroidGatewayTrustStore
import com.no5.helm.security.StoredGatewaySecurityProfileProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Explicit lab acceptance only: no imports, preference writes, commands or trust changes. */
class LabMdnsAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun requireLabOptIn() {
        assumeTrue("Enable this lab-only test with -e no5LabMdns true",
            InstrumentationRegistry.getArguments().getString("no5LabMdns") == "true")
        assertEquals("This acceptance targets the normal Android 10 AVD", 29, Build.VERSION.SDK_INT)
    }

    private fun report(stage: String, result: String) {
        // Deliberately omit credentials, certificates, response bodies and exception messages.
        instrumentation.sendStatus(0, Bundle().apply {
            putString("no5_mdns_stage", stage)
            putString("no5_mdns_result", result)
        })
    }

    @Test fun systemDnsIsAnIndependentObservation() {
        requireLabOptIn()
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "no5-lab-system-dns").apply { isDaemon = true }
        }
        val lookup = worker.submit<Boolean> { InetAddress.getAllByName(HOST).isNotEmpty() }
        try {
            val result = runCatching { lookup.get(8, TimeUnit.SECONDS) }.getOrNull()
            report("SYSTEM_DNS", if (result == true) "RESOLVED" else "UNAVAILABLE_OR_TIMEOUT")
        } finally {
            lookup.cancel(true)
            worker.shutdownNow()
        }
        // This observation is neither the NSD gate nor proof of the HTTP transport.
    }

    @Test fun productionNsdResolvesTheLabServiceWithinItsOwnBudget() {
        requireLabOptIn()
        AndroidGatewayEndpointResolver(context).use { resolver ->
            val result = resolver.resolve(ENDPOINT, PiNetworkPolicy.HELM.connectTimeoutMs)
            report("ANDROID_NSD", resolutionClass(result))
            assertTrue("Production Android NSD must discover the lab service", result is GatewayEndpointResolution.Resolved)
            val addresses = (result as GatewayEndpointResolution.Resolved).addresses
            assertTrue("NSD must yield a usable socket address", addresses.isNotEmpty())
            assertTrue(addresses.none { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress })
        }
    }

    @Test fun existingHelmReadKeepsTheLogicalHostAndValidatesItsImportedTrust() {
        requireLabOptIn()
        val profiles = StoredGatewaySecurityProfileProvider(
            AndroidGatewaySecurityStore(context), AndroidGatewayTrustStore(context),
        )
        val profile = profiles.load(ENDPOINT)
        assertTrue("Existing lab-origin key and public CA must already be imported through HELM", profile != null)
        requireNotNull(profile).use { security ->
            AndroidGatewayEndpointResolver(context).use { nsd ->
                var usedMdns = false
                val observedResolver = object : GatewayEndpointResolver {
                    override fun resolve(url: String, timeoutMs: Int): GatewayEndpointResolution {
                        assertEquals(ENDPOINT, url)
                        return nsd.resolve(url, timeoutMs).also {
                            usedMdns = it is GatewayEndpointResolution.Resolved
                            report("HTTP_MDNS", resolutionClass(it))
                        }
                    }
                    override fun invalidate() = nsd.invalidate()
                }
                UrlConnectionPiTransport(ENDPOINT, PiNetworkPolicy.HELM, security, observedResolver).use { transport ->
                    // The production transport retains ENDPOINT for Host, SNI and certificate
                    // hostname verification; only its socket address comes from Android NSD.
                    val payload = try {
                        transport.get("/v1/helm")
                    } catch (failure: Exception) {
                        report("HELM_HTTPS", GatewayProbeErrors.classify(failure).name)
                        throw AssertionError("Lab HELM HTTPS read failed; see safe stage classification")
                    }
                    assertTrue("The HTTPS read must actually use Android mDNS", usedMdns)
                    val envelope = try { JSONObject(payload) } catch (_: Exception) {
                        throw AssertionError("Lab HELM response must be a valid JSON envelope")
                    }
                    assertTrue("Lab HELM envelope must use protocol version 1", envelope.optInt("version") == 1)
                    assertTrue("Lab HELM envelope must have kind helm", envelope.optString("kind") == "helm")
                    report("HELM_HTTPS", "HTTP_200_LOGICAL_HOST_TLS_VERIFIED")
                }
            }
        }
    }

    private fun resolutionClass(result: GatewayEndpointResolution): String = when (result) {
        is GatewayEndpointResolution.Resolved -> "RESOLVED"
        is GatewayEndpointResolution.Failed -> result.failure.name
        GatewayEndpointResolution.NotApplicable -> "NOT_APPLICABLE"
    }

    companion object {
        private const val HOST = "no5-marineos-lab.local"
        private const val ENDPOINT = "https://$HOST:9443"
    }
}
