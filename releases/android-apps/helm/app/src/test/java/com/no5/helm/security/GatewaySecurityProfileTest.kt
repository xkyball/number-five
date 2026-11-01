package com.no5.helm.security

import com.no5.helm.network.sonarRequest
import com.no5.helm.network.NetworkSourceSessionFactory
import com.no5.helm.network.PiHttpTransport
import com.no5.helm.network.PiNetworkPolicy
import com.no5.helm.network.PiPollScheduler
import com.no5.helm.network.SonarStreamTransport
import com.no5.helm.network.GatewayConnectionTester
import com.no5.helm.network.GatewayProbeErrors
import com.no5.helm.network.GatewayProbeResult
import com.no5.helm.network.GatewayReadOnlyProbe
import com.no5.helm.network.NetworkGatewayReadOnlyProbe
import com.no5.helm.network.PiHttpStatusException
import com.no5.helm.network.GatewayMdnsException
import com.no5.helm.network.GatewayMdnsFailure
import com.no5.helm.network.GatewayEndpointResolution
import com.no5.helm.network.GatewayEndpointResolver
import com.no5.helm.network.GatewayMdnsAdvertisement
import com.no5.helm.network.UrlConnectionPiTransport
import com.no5.helm.network.BoundedGatewayMdnsResolver
import com.no5.helm.network.GatewayMdnsDiscovery
import com.no5.helm.network.GatewayMdnsServiceRecord
import com.no5.helm.network.GatewayMdnsTarget
import com.no5.helm.network.GatewayOkHttpDns
import com.no5.domain.SourceConfig
import com.no5.domain.SourceIdentity
import com.no5.domain.SourceMode
import com.no5.domain.SourceSubscription
import com.no5.domain.WeatherProviderMode
import com.no5.helm.diagnostics.GatewayDiagnosticError
import com.no5.helm.diagnostics.GatewayDiagnosticLog
import com.no5.helm.diagnostics.GatewayDiagnosticOutcome
import com.no5.helm.diagnostics.GatewayDiagnosticService
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.net.InetAddress
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** Generated, public-only test certificates. No production certificate, CA or key is present. */
internal const val TEST_CA_DER_B64 = "MIIDMTCCAhmgAwIBAgIUag3f7ebUa/5EvO02d4UzypMGoxEwDQYJKoZIhvcNAQELBQAwIDEeMBwGA1UEAwwVTm81IFN5bnRoZXRpYyBUZXN0IENBMB4XDTI2MTAwNjE4NTQ0MVoXDTM2MTAwMzE4NTQ0MVowIDEeMBwGA1UEAwwVTm81IFN5bnRoZXRpYyBUZXN0IENBMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApg6XuRxGC8OFl0tS7Y+KuyFLCWogsJImp7oaCXJ7GPSgsAKxHwBKYwoti5SL/KwUCTcCyPhfxtYR/U//VWHwTqACA3hq+Inu7VpvtpD5wXIWCHae3lf8OOK06tnJbaBKGuFxqDMxiEc9YdU+U4L97OGDVAQrEdP7GNgtPFBFUtdnTTQNzKBmUzGWnfqchV49lsP7zlf/EDPey7lCuhQr0cP/6Z1B/HHNTZkNumZUTk+If10UNI6/VCYbpdmNfDd8Gi4okss4dFo5SImJOlg8c8iPDOAuQuRXAMVT6qNCVlmzluKz96elrhlIm8/o8t/oWMu2/NfJCecjVUV/lmG1HwIDAQABo2MwYTAdBgNVHQ4EFgQUj1AePpDCOjiC4tlfDi8Xp+lxha8wHwYDVR0jBBgwFoAUj1AePpDCOjiC4tlfDi8Xp+lxha8wDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAQYwDQYJKoZIhvcNAQELBQADggEBAF25dzBoDFQ7cHmLUbYSKfMquPTKD9eQmTSH2J7nrlV7tgTQJp/eNuR7NYR/3mpA9GJZdEKXs0TtswcoQbsmQeZiIm4N+GCATZDa7I3doVp5JWqyfe/RpZY3x5fDk3Ozr8bAjMdzA5g1EP6eavPw2zDgKpmG3MeqmmVAXTGXpiP21hlu6ffdmTrBEzP9/p/zkse/HInA7NqddzOTpTTWVIpCjVRwdbLVn6lHuBETI0ZrfrIjXEQ1mMZD7MESp2odGF3eGukTuCFdAp4TvC4NWc7+DxQUR24Co26AV8ikWCKYA2NXrjwt++qmzrh+KetewQhykUvMx2TWnvZq3xQdwto="
private const val TEST_EXPIRED_CA_DER_B64 = "MIIDDzCCAfegAwIBAgIJAIFvTW4j+swuMA0GCSqGSIb3DQEBDAUAMCgxJjAkBgNVBAMTHU5vNSBFeHBpcmVkIFN5bnRoZXRpYyBUZXN0IENBMB4XDTE5MTIzMTIyMDAwMFoXDTIwMDEwMTIyMDAwMFowKDEmMCQGA1UEAxMdTm81IEV4cGlyZWQgU3ludGhldGljIFRlc3QgQ0EwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDGy9yzxznZB+XQpqLoqlM0M69Z4zjcwRhLD3VgS9v3EEuVuCRdjtCfwTBkFVOabGKFnlM4SIkVseOIqVDhbg7H+ClI0gT5vFGbAKOLtkbkgBfYojAKf3bHNiXdGBtqzlyG1BVJ/Pi/JOOe7pzLuc+0czZc3EEVxrOEOFGY40sjYFKgQ+MhxH/gDKfqEcU6AbeRecbm8W6DtnSD35XmnQuBjIWq/d889j3inogqJ07Lmq0k0yjzNxPU78DC10XYglDQBHSOROpfLFbws5IGkQt3lh7qv8w+50Jjj1KfM3uLJd323453MFN6tzuwww10T3pNRD36twDT7rSRDEvqHQbTAgMBAAGjPDA6MB0GA1UdDgQWBBTacwxfrJGGrBcB0kPWBzvpF3juPzALBgNVHQ8EBAMCAQYwDAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQwFAAOCAQEAJLXTtv1IMHrZQezGW3H5FlYL9SRVqBocqXjQBiMoS2I/K3t8yvIae92HZyyrnEOZ5benuxHS95IoLitHhymbjvU91ocK4FCk9y61bHD7RltEgnAjtYaAWnUXAaO3Me+HxNm7V/pf4OztueNIuMsN18xhf95+8X8N2XCWOzGhPV42FLpXqTWMdUlDaVjlNMBy8EocnjM5sApEqtIug7GlTsrpFgogtoS4qK1REib4/UhDA8bhJv8Bed2iOrPHuvWSu3k3UFY+zAOHHYSUB0P87kez+8CCfm4uBy0ytArC8BCutoOMf+AubtoKHvYBfmK+xwBW2lmX2fZLJE3xlA6dXw=="
private const val TEST_LEAF_DER_B64 = "MIIDTzCCAjegAwIBAgIUN5QhNkSd2KJJ0xF9xEUztUtsCGQwDQYJKoZIhvcNAQELBQAwIDEeMBwGA1UEAwwVTm81IFN5bnRoZXRpYyBUZXN0IENBMB4XDTI2MTAwNjE4NTQ0MVoXDTM2MTAwMzE4NTQ0MVowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArFfJA9nEynJqUeHbhuKPxB3K2hr0HfEVfY2ktw+hHRkbcCDhGudKXJ0vujU1F0pOiE0pgdaDyEdsdugUbUvGwlKievq2Ps3/Z7DB6LFR5UuPSqb0hdy9aLH9YwPHY6105XMP/M8p3rLdJgDHJhv5SzcRmaN3mm7HDQGgNBOdHeMgYVGPFsqG2Eg+N4xaKceoXTms5mADr0LAI5UOQYkHj0l1dnUHynXFi4JeHGeEbl5OKG0R0Z9JCBxgniZHJTEtq3rHjm/tBuVkoyGSS6qNheNWbCyjnPHY759L/QIlapbywAf3iUibWvl+kCf/PFALraw4eTTlzYv/x+OSfOE1ZwIDAQABo4GMMIGJMBQGA1UdEQQNMAuCCWxvY2FsaG9zdDAMBgNVHRMBAf8EAjAAMA4GA1UdDwEB/wQEAwIFoDATBgNVHSUEDDAKBggrBgEFBQcDATAdBgNVHQ4EFgQUuN4gaQBqq2O9Zb6kjH0ZDCiiuEcwHwYDVR0jBBgwFoAUj1AePpDCOjiC4tlfDi8Xp+lxha8wDQYJKoZIhvcNAQELBQADggEBAE2vVktiy4upqLIvwvbG/jE9+ICACO4oFxyuWUSdFvwcN8dNXOwzCrdyn+pAO/HGm0YU3jUwjcrB77PkvC6MslOO3nSdprlE1WxEyC/2Hu1oay6H0DY73DDyztZJrwD/4C8JmIoDhODxbj/sHi2NeY7lDMR8KUMdLplqrs2kuZ61GFLVJy5bLpSaR0O/uA4QKBAcv5igRhNlxgghXwA4VCuxlsoOJWl1Zm9Sef3W29WAlllDH+ROV0vlsgjR1c/4ndZYsXdc5/Cx7HxhsYmZFN2kCyfEn3MHzz+zyG/eyaJFswfXVknlPOj85XfWFyCgzfsOQQFQ+tfYbXSYn6bYxoE="

class GatewaySecurityProfileTest {
    private val syntheticKey = "synthetic-no5-key-material-0001".toByteArray()

    private class MemoryKeys : GatewaySecurityStore {
        val values = mutableMapOf<GatewayOrigin, ByteArray>()
        override fun save(origin: GatewayOrigin, key: ByteArray) { values[origin] = key.copyOf() }
        override fun read(origin: GatewayOrigin): ByteArray? = values[origin]?.copyOf()
        override fun remove(origin: GatewayOrigin) { values.remove(origin)?.fill(0) }
    }
    private class MemoryTrust : GatewayTrustStore {
        val values = mutableMapOf<GatewayOrigin, ByteArray>()
        override fun save(origin: GatewayOrigin, caCertificateDer: ByteArray) { values[origin] = caCertificateDer.copyOf() }
        override fun read(origin: GatewayOrigin): ByteArray? = values[origin]?.copyOf()
        override fun remove(origin: GatewayOrigin) { values.remove(origin)?.fill(0) }
    }
    private class CertificateDocument(private val bytes: ByteArray?) : GatewayTrustDocument { override fun open() = bytes?.let(::ByteArrayInputStream) }

    private fun ca(): ByteArray = java.util.Base64.getDecoder().decode(TEST_CA_DER_B64)
    private fun expiredCa(): ByteArray = java.util.Base64.getDecoder().decode(TEST_EXPIRED_CA_DER_B64)
    private fun leaf() = CertificateFactory.getInstance("X.509").generateCertificate(
        ByteArrayInputStream(java.util.Base64.getDecoder().decode(TEST_LEAF_DER_B64))
    ) as java.security.cert.X509Certificate
    private fun profile(endpoint: String): GatewaySecurityProfile = GatewaySecurityProfile.create(
        GatewayOrigin.fromEndpoint(endpoint), syntheticKey, ca()
    )

    @Test fun httpsDeadlineClosesTheStalledTlsSocketInsteadOfLeavingTheWorkerBlocked() {
        java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val eof = java.util.concurrent.CountDownLatch(1)
            val worker = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    try { while (socket.getInputStream().read() >= 0) { } }
                    finally { eof.countDown() }
                }
            }.apply { isDaemon = true; start() }
            val endpoint = "https://localhost:${server.localPort}"
            val policy = PiNetworkPolicy(50, 10_000, 10_000, 400)
            val started = System.nanoTime()
            UrlConnectionPiTransport(endpoint, policy, profile(endpoint)).use { transport ->
                assertFailsWith<java.io.IOException> { transport.get("/v1/helm") }
                assertTrue(eof.await(1_500, java.util.concurrent.TimeUnit.MILLISECONDS))
                assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
            }
            worker.join(2_000); assertFalse(worker.isAlive)
        }
    }

    @Test fun trustImportAcceptsOnlyBoundedCurrentCaCertificatesAndNeverNeedsNetwork() {
        val trust = MemoryTrust()
        val endpoint = "https://lysmarine.local:9443"
        assertEquals(GatewayTrustImportResult.Imported, GatewayTrustImporter(trust).import(CertificateDocument(ca()), endpoint))
        assertNotNull(trust.read(GatewayOrigin.fromEndpoint(endpoint)))
        val rejected = GatewayTrustImporter(MemoryTrust()).import(CertificateDocument(leaf().encoded), endpoint)
        assertIs<GatewayTrustImportResult.Rejected>(rejected)
        assertTrue(rejected.message.contains("CA certificate"))
        assertIs<GatewayTrustImportResult.Rejected>(GatewayTrustImporter(MemoryTrust()).import(CertificateDocument(ByteArray(0)), endpoint))
        assertIs<GatewayTrustImportResult.Rejected>(GatewayTrustImporter(MemoryTrust()).import(CertificateDocument(ca()), "http://lysmarine.local:9443"))
    }

    @Test fun profileWssAndHttpHeadersAreExactOriginBoundAndSecretFreeInMessages() {
        profile("https://lysmarine.local:9443").use { profile ->
            val headers = mutableMapOf<String, String>()
            profile.configureHttps("https://lysmarine.local:9443/v1/helm", {}, headers::put)
            assertEquals(syntheticKey.toString(Charsets.US_ASCII), headers[GatewaySecurityProfile.HEADER])
            assertEquals(syntheticKey.toString(Charsets.US_ASCII), sonarRequest("https://lysmarine.local:9443/v1/sonar/stream", profile).header(GatewaySecurityProfile.HEADER))
            listOf("http://lysmarine.local:9443/v1/helm", "https://lysmarine.local:443/v1/helm", "https://other.local:9443/v1/helm").forEach { url ->
                val failure = assertFailsWith<IllegalArgumentException> { profile.configureHttps(url, {}, headers::put) }
                assertFalse(failure.message.orEmpty().contains(syntheticKey.toString(Charsets.US_ASCII)))
            }
            assertFalse(profile.toString().contains(syntheticKey.toString(Charsets.US_ASCII)))
        }
    }

    @Test fun musicBrowseQueriesKeepExactOriginHeaderBinding() {
        val origin = "https://lysmarine.local:9443"
        profile(origin).use { profile ->
            val headers = mutableMapOf<String, String>()
            listOf(
                "$origin/v1/audio/browse?receiverSessionId=session-a&type=PLAYLISTS&offset=0&limit=20",
                "$origin/v1/audio/browse?receiverSessionId=session-a&type=LIKED&offset=20&limit=20",
                "$origin/v1/audio/browse?receiverSessionId=session-a&type=SEARCH&query=title%20%26%20artist&offset=0&limit=20",
            ).forEach { url ->
                profile.configureHttps(url, {}, headers::put)
                assertEquals(syntheticKey.toString(Charsets.US_ASCII), headers[GatewaySecurityProfile.HEADER])
            }
            listOf(
                "http://lysmarine.local:9443/v1/audio/browse?type=PLAYLISTS",
                "https://other.local:9443/v1/audio/browse?redirect=$origin",
                "https://lysmarine.local:443/v1/audio/browse?type=PLAYLISTS",
                "$origin/v1/audio/browse?type=PLAYLISTS#fragment",
                "https://user@lysmarine.local:9443/v1/audio/browse?type=PLAYLISTS",
            ).forEach { url ->
                var sent = false
                assertFailsWith<IllegalArgumentException> { profile.configureHttps(url, {}, { _, _ -> sent = true }) }
                assertFalse(sent, "Invalid request origin must not receive the gateway header")
            }
        }
    }

    @Test fun storedProviderRequiresBothMatchingOriginKeyAndCa() {
        val keys = MemoryKeys(); val trust = MemoryTrust(); val expected = GatewayOrigin.fromEndpoint("https://lysmarine.local:9443")
        keys.save(expected, syntheticKey); trust.save(expected, ca())
        val provider = StoredGatewaySecurityProfileProvider(keys, trust)
        assertNotNull(provider.load("https://lysmarine.local:9443"))?.close()
        assertNull(provider.load("https://lysmarine.local")); assertNull(provider.load("http://lysmarine.local:9443")); assertNull(provider.load("https://other.local:9443"))
        trust.remove(expected); assertNull(provider.load("https://lysmarine.local:9443"))
    }

    @Test fun inspectorReportsSavedOriginDraftAndPublicCertificateEvidenceWithoutExposingMaterial() {
        val endpoint = "https://lysmarine.local:9443"
        val keys = MemoryKeys(); val trust = MemoryTrust()
        val inspector = GatewaySecurityInspector(keys, trust, StoredGatewaySecurityProfileProvider(keys, trust))

        val missing = inspector.inspect(endpoint)
        assertEquals(GatewayMaterialState.MISSING, missing.key)
        assertEquals(GatewayMaterialState.MISSING, missing.ca)
        assertEquals(GatewayProfileState.KEY_MISSING, missing.profile)
        assertEquals(GatewayDraftOriginState.MATCHES_SAVED, missing.draftOriginState)

        val origin = GatewayOrigin.fromEndpoint(endpoint)
        keys.save(origin, syntheticKey); trust.save(origin, ca())
        val ready = inspector.inspect(endpoint, endpoint)
        assertEquals(GatewayMaterialState.PRESENT, ready.key)
        assertEquals(GatewayMaterialState.PRESENT, ready.ca)
        assertEquals(GatewayProfileState.READY, ready.profile)
        assertEquals(GatewayDraftOriginState.MATCHES_SAVED, ready.draftOriginState)
        assertNotNull(ready.certificate)
        assertTrue(ready.certificate!!.sha256Fingerprint.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
        assertFalse(ready.toString().contains(syntheticKey.toString(Charsets.US_ASCII)))

        assertEquals(GatewayDraftOriginState.DIFFERENT_FROM_SAVED,
            inspector.inspect(endpoint, "https://other.local:9443").draftOriginState)
        assertEquals(GatewayDraftOriginState.INVALID,
            inspector.inspect(endpoint, "http://lysmarine.local:9443").draftOriginState)
    }

    @Test fun publicTestLeafIsNotAcceptedAsCaAndHostnameMismatchCannotAcquireAProfile() {
        assertTrue(leaf().subjectAlternativeNames.any { it[1] == "localhost" })
        assertFailsWith<IllegalArgumentException> { GatewayCertificateFormat.parseCa(leaf().encoded) }
        val keys = MemoryKeys(); val trust = MemoryTrust()
        val localhost = GatewayOrigin.fromEndpoint("https://localhost:9443")
        keys.save(localhost, syntheticKey); trust.save(localhost, ca())
        val provider = StoredGatewaySecurityProfileProvider(keys, trust)
        assertNotNull(provider.load("https://localhost:9443"))?.close()
        // A certificate SAN does not broaden credential origin binding: no profile is handed to
        // transports for another hostname, so default HTTPS/WSS hostname verification remains in force.
        assertNull(provider.load("https://wrong.local:9443"))
    }

    @Test fun expiredCaHttpOriginAndRedirectTargetAreRejectedBeforeAnyNetworkRequest() {
        val endpoint = "https://lysmarine.local:9443"
        val trust = MemoryTrust()
        val expired = GatewayTrustImporter(trust).import(CertificateDocument(expiredCa()), endpoint)
        assertIs<GatewayTrustImportResult.Rejected>(expired)
        assertTrue(expired.message.contains("not currently valid"))
        assertNull(trust.read(GatewayOrigin.fromEndpoint(endpoint)))
        assertIs<GatewayTrustImportResult.Rejected>(GatewayTrustImporter(trust).import(CertificateDocument(ca()), "http://lysmarine.local:9443"))

        profile(endpoint).use { profile ->
            // A server redirect target cannot be used to construct the WSS request/header.
            assertFailsWith<IllegalArgumentException> {
                sonarRequest("https://redirect.invalid:9443/v1/sonar/stream", profile)
            }
        }
    }

    @Test fun httpsSessionReconnectRetiresThePreviousProfileBeforeCreatingTheNext() {
        val endpoint = "https://lysmarine.local:9443"
        val profiles = mutableListOf<GatewaySecurityProfile>()
        val idleScheduler = PiPollScheduler { _, _ -> SourceSubscription {} }
        val factory = NetworkSourceSessionFactory(
            now = { 1L },
            transport = { _, _ -> error("HTTPS must not use the unprotected transport") },
            scheduler = { idleScheduler },
            sonarStream = { error("HTTPS must not use the unprotected stream") },
            securityProfiles = GatewaySecurityProfileProvider { profile(endpoint).also(profiles::add) },
            securedTransport = { _, _, _ -> object : PiHttpTransport {
                override fun get(path: String): String = throw AssertionError("No synthetic network should start")
                override fun close() = Unit
            } },
            securedSonarStream = { _, _ -> object : SonarStreamTransport {
                override fun connect(listener: SonarStreamTransport.Listener): SourceSubscription = SourceSubscription {}
                override fun close() = Unit
            } },
        )
        val config = SourceConfig(mode = SourceMode.PI, endpoint = endpoint, weatherProvider = WeatherProviderMode.DISABLED)
        val first = factory.create(config, SourceIdentity("first", SourceMode.PI, "TEST"))
        assertEquals("WSS UNAVAILABLE · sent 0 · confirmed 0", first.audio.faderStream?.status,
            "The production HTTPS factory must select the persistent WSS fader transport")
        first.close()
        assertFailsWith<IllegalStateException> { profiles.single().configureHttps("$endpoint/v1/helm", {}, { _, _ -> }) }
        val second = factory.create(config, SourceIdentity("second", SourceMode.PI, "TEST"))
        assertEquals(2, profiles.size)
        assertEquals("WSS UNAVAILABLE · sent 0 · confirmed 0", second.audio.faderStream?.status)
        second.close()
    }

    @Test fun explicitTesterInvokesOnlyTheFiveReadOnlyServicesAndRecordsPerServiceEvidence() {
        val endpoint = "https://lysmarine.local:9443"
        val invoked = mutableListOf<GatewayDiagnosticService>()
        var clock = 0L
        val tester = GatewayConnectionTester(
            profiles = GatewaySecurityProfileProvider { profile(it) },
            probe = GatewayReadOnlyProbe { service, _, _ ->
                invoked += service
                if (service == GatewayDiagnosticService.SONAR_WS) {
                    GatewayProbeResult(GatewayDiagnosticError.HTTP_403, 403)
                } else GatewayProbeResult()
            },
            now = { ++clock },
        )
        val journal = GatewayDiagnosticLog(now = { ++clock })

        val result = tester.test(endpoint, journal)

        assertEquals(listOf(
            GatewayDiagnosticService.HELM,
            GatewayDiagnosticService.WEATHER,
            GatewayDiagnosticService.AUDIO,
            GatewayDiagnosticService.SONAR_HTTP,
            GatewayDiagnosticService.SONAR_WS,
        ), invoked)
        assertEquals(5, result.size)
        assertEquals(GatewayDiagnosticError.HTTP_403, result.last().error)
        val serviceResults = journal.snapshot().entries.filter { it.outcome != GatewayDiagnosticOutcome.STARTED && it.service != GatewayDiagnosticService.SECURITY }
        assertEquals(5, serviceResults.size)
        assertTrue(serviceResults.dropLast(1).all { it.outcome == GatewayDiagnosticOutcome.SUCCESS && it.durationMs != null })
        assertEquals(GatewayDiagnosticOutcome.FAILURE, serviceResults.last().outcome)
        assertEquals(403, serviceResults.last().httpStatus)
    }

    @Test fun readOnlyProbeRetainsHttpStatusForInvalidResponseAndUnavailableService() {
        val endpoint = "https://localhost:9443"
        var calls = 0
        var closes = 0
        val probe = NetworkGatewayReadOnlyProbe(transportFactory = { actualEndpoint, _ ->
            assertEquals(endpoint, actualEndpoint)
            object : PiHttpTransport {
                override fun get(path: String): String {
                    assertEquals("/v1/helm",path)
                    calls++
                    if(calls == 2) throw PiHttpStatusException(503)
                    return "{}"
                }
                override fun close() { closes++ }
            }
        })
        profile(endpoint).use { profile ->
            assertEquals(GatewayProbeResult(GatewayDiagnosticError.SCHEMA,200),probe.check(GatewayDiagnosticService.HELM,endpoint,profile))
            assertEquals(GatewayProbeResult(GatewayDiagnosticError.HTTP_OTHER,503),probe.check(GatewayDiagnosticService.HELM,endpoint,profile))
        }
        assertEquals(2, calls)
        assertEquals(2, closes)
    }

    @Test fun probeErrorClassificationPreservesActionableSafeClasses() {
        assertEquals(GatewayDiagnosticError.DNS, GatewayProbeErrors.classify(UnknownHostException()))
        assertEquals(GatewayDiagnosticError.MDNS_SERVICE, GatewayProbeErrors.classify(GatewayMdnsException(GatewayMdnsFailure.NO_MATCHING_SERVICE)))
        assertEquals(GatewayDiagnosticError.MDNS_HOST, GatewayProbeErrors.classify(GatewayMdnsException(GatewayMdnsFailure.HOST_MISMATCH)))
        assertEquals(GatewayDiagnosticError.MDNS_PORT, GatewayProbeErrors.classify(GatewayMdnsException(GatewayMdnsFailure.PORT_MISMATCH)))
        assertEquals(GatewayDiagnosticError.MDNS_TIMEOUT, GatewayProbeErrors.classify(GatewayMdnsException(GatewayMdnsFailure.TIMEOUT)))
        assertEquals(GatewayDiagnosticError.TIMEOUT, GatewayProbeErrors.classify(SocketTimeoutException()))
        assertEquals(GatewayDiagnosticError.TLS_HOSTNAME, GatewayProbeErrors.classify(SSLPeerUnverifiedException("test")))
        assertEquals(GatewayDiagnosticError.HTTP_401, GatewayProbeErrors.classify(PiHttpStatusException(401)))
        assertEquals(GatewayDiagnosticError.HTTP_403, GatewayProbeErrors.classify(PiHttpStatusException(403)))
        assertEquals(GatewayDiagnosticError.HTTP_OTHER, GatewayProbeErrors.classify(PiHttpStatusException(503)))
    }

    @Test fun securedLocalOriginFailsAtTypedMdnsGateBeforeAnyHttpRequest() {
        val endpoint = "https://lysmarine.local:9443"
        val resolver = object : GatewayEndpointResolver {
            var requestedUrl: String? = null
            override fun resolve(url: String, timeoutMs: Int): GatewayEndpointResolution {
                requestedUrl = url
                return GatewayEndpointResolution.Failed(GatewayMdnsFailure.NO_MATCHING_SERVICE)
            }
            override fun invalidate() = Unit
        }
        profile(endpoint).use { profile ->
            UrlConnectionPiTransport(endpoint, PiNetworkPolicy.HELM, profile, resolver).use { transport ->
                val failure = assertFailsWith<GatewayMdnsException> { transport.get("/v1/helm") }
                assertEquals(GatewayMdnsFailure.NO_MATCHING_SERVICE, failure.failure)
            }
        }
        // The shared OkHttp DNS adapter canonicalises the path to its exact logical origin.
        assertEquals(endpoint, resolver.requestedUrl)
    }

    @Test fun android10NsdVariantsKeepOneLogicalTlsAndHeaderOriginAcrossCacheRestart() {
        val endpoint = "https://lysmarine.local:9443"
        val target = requireNotNull(GatewayMdnsTarget.fromUrl(endpoint))
        val gatewayAddress = InetAddress.getByName("192.168.68.80")
        val variants: List<(GatewayMdnsTarget) -> GatewayEndpointResolution> = listOf(
            // Build43 investigation: an OEM may expose the qualified DNS-SD instance name.
            { expected -> GatewayMdnsAdvertisement.accept(expected, GatewayMdnsServiceRecord(
                "_https._tcp.", "lysmarine._https._tcp.local.", "lysmarine.local.", 9443, listOf(gatewayAddress),
            )) },
            // Build44 investigation: Android 10 returns the resolved service type with one dot.
            { expected -> GatewayMdnsAdvertisement.accept(expected, GatewayMdnsServiceRecord(
                "._https._tcp.", "lysmarine", "lysmarine.local.", 9443, listOf(gatewayAddress),
            )) },
            // Build45 investigation: API-29 exposes only the numeric resolved InetAddress.
            { expected -> GatewayMdnsAdvertisement.acceptAndroidResolved(
                expected, "._https._tcp.", "lysmarine", 9443, listOf(gatewayAddress),
            ) },
        )
        var discoveryCalls = 0
        val resolver = BoundedGatewayMdnsResolver(GatewayMdnsDiscovery { expected, _, _ ->
            variants[discoveryCalls++ % variants.size](expected)
        })

        profile(endpoint).use { security ->
            variants.indices.forEach { index ->
                resolver.invalidate() // model a network/session restart; no stale negative cache survives.
                val addresses = GatewayOkHttpDns(endpoint, resolver, 1_200).lookup(target.host)
                assertEquals(listOf("192.168.68.80"), addresses.map(::requireNumericHost), "NSD variant $index")

                val headerNames = mutableListOf<String>()
                security.configureHttps("$endpoint/v1/helm", {}, { name, _ -> headerNames += name })
                assertEquals(listOf(GatewaySecurityProfile.HEADER), headerNames)
                // GatewayOkHttpDns supplies only the TCP address. The actual HTTP/WSS Request
                // still carries this logical URL host into OkHttp's default TLS/SNI verification.
                val wss = sonarRequest("$endpoint/v1/sonar/stream", security)
                assertEquals("lysmarine.local", wss.url.host)
                assertTrue(wss.header(GatewaySecurityProfile.HEADER) != null)
            }
            assertEquals(variants.size, discoveryCalls)
            // Positive cache remains valid until an explicit session/network invalidation.
            resolver.resolve(endpoint, 1_200)
            assertEquals(variants.size, discoveryCalls)
            resolver.invalidate()
            resolver.resolve(endpoint, 1_200)
            assertEquals(variants.size + 1, discoveryCalls)

            listOf("https://192.168.68.80:9443/v1/helm", "https://redirect.invalid:9443/v1/helm").forEach { wrongOrigin ->
                val unexpectedHeaders = mutableListOf<String>()
                assertFailsWith<IllegalArgumentException> {
                    security.configureHttps(wrongOrigin, {}, { name, _ -> unexpectedHeaders += name })
                }
                assertTrue(unexpectedHeaders.isEmpty())
            }
        }
    }

    @Test fun passwordProfileUsesTheSameOriginBoundaryForHttpAndWebSockets() {
        val origin = GatewayOrigin.fromEndpoint("https://lysmarine.local:9443")
        val prehash = "a".repeat(64)
        val bytes = (GatewaySecurityProfile.PASSWORD_PREFIX + prehash).toByteArray()
        GatewaySecurityProfile.create(origin, bytes, ca()).use { security ->
            val headers = mutableMapOf<String,String>()
            security.configureHttps("${origin.value}/v1/helm", {}, headers::put)
            assertEquals(mapOf(GatewaySecurityProfile.PASSWORD_HEADER to prehash), headers)
            val socket = sonarRequest("${origin.value}/v1/sonar/stream", security)
            assertEquals(prehash, socket.header(GatewaySecurityProfile.PASSWORD_HEADER))
            assertNull(socket.header(GatewaySecurityProfile.HEADER))
            assertFailsWith<IllegalArgumentException> { security.configureHttps("https://other.invalid/v1/helm", {}, headers::put) }
        }
        assertFailsWith<IllegalArgumentException> { GatewaySecurityProfile.create(origin,
            (GatewaySecurityProfile.PASSWORD_PREFIX + "broken").toByteArray(), ca()) }
    }

    @Test fun clientPasswordKdfAgreesWithIndependentPythonVectorsIncludingUnicode() {
        val salt = "00".repeat(32)
        assertEquals("429224273fdbb7bdf53d8ea4cc340e20a0961c691d8b355ab30d862f8ef8e4f5",
            GatewayPasswordKdf.derive("synthetic password only".toCharArray(), salt))
        assertEquals("d665dabd922f9b2bd4375fa845c9c887a73c369e44295daf845f85aa772c5ddb",
            GatewayPasswordKdf.derive("Grüße ⚓ Passwort".toCharArray(), salt))
    }

    @Test fun bootstrapAcceptsOnlyThePinnedCaAndStillVerifiesTheCertificateChain() {
        val root = GatewayCertificateFormat.parseCa(ca())
        val pin = GatewayKnownAuthorities.sha256(root)
        GatewayPinnedCaTrustManager(pin).checkServerTrusted(arrayOf(leaf(), root), "RSA")
        assertFailsWith<java.security.cert.CertificateException> {
            GatewayPinnedCaTrustManager("0".repeat(64)).checkServerTrusted(arrayOf(leaf(), root), "RSA")
        }
        assertFailsWith<java.security.cert.CertificateException> {
            GatewayPinnedCaTrustManager(pin).checkServerTrusted(arrayOf(leaf()), "RSA")
        }
        assertNull(GatewayKnownAuthorities.fingerprint(GatewayOrigin.fromEndpoint("https://other.invalid:9443")))
    }

    @Test fun bootstrapPreservesValidExistingTrust() {
        val origin = com.no5.gateway.GatewayOrigin.parse("https://lysmarine.local:9443")!!
        val record = com.no5.gateway.GatewayConfiguration(origin, "192.168.68.80",
            com.no5.gateway.GatewayAddressSource.MANUAL, checkedAt = 1)
        val existing = MemoryTrust().apply { save(GatewayOrigin.fromEndpoint(origin.url), ca()) }
        assertFalse(GatewayCaBootstrap(existing).ensure(record))
    }

    @Test fun damagedTrustCannotEnableAnUnpinnedDownloadOrPrematureReplacement() {
        val origin = com.no5.gateway.GatewayOrigin.parse("https://other.invalid:9443")!!
        val record = com.no5.gateway.GatewayConfiguration(origin, "127.0.0.1",
            com.no5.gateway.GatewayAddressSource.MANUAL, checkedAt = 1)
        for (damage in 0..2) {
            val corrupt = object : GatewayTrustStore {
                override fun read(origin: GatewayOrigin): ByteArray? = when (damage) {
                    0 -> null // Failed local decryption.
                    1 -> "broken certificate".toByteArray()
                    else -> throw ClassCastException("Damaged preferences")
                }
                override fun save(origin: GatewayOrigin, caCertificateDer: ByteArray) = error("Unverified CA must not be saved")
                override fun remove(origin: GatewayOrigin) = error("Existing entry must survive failed recovery")
            }
            val failure = assertFailsWith<IllegalStateException> { GatewayCaBootstrap(corrupt).ensure(record) }
            assertTrue(failure.message.orEmpty().contains("identity is not configured"))
        }
    }

    @Test fun cachedSessionCopiesPreserveCredentialModeAndIndependentLifetime() {
        val origin = GatewayOrigin.fromEndpoint("https://lysmarine.local:9443")
        val password = "a".repeat(64)
        for ((stored, header, value) in listOf(
            Triple("synthetic-gateway-key-for-copy", GatewaySecurityProfile.HEADER, "synthetic-gateway-key-for-copy"),
            Triple(GatewaySecurityProfile.PASSWORD_PREFIX + password, GatewaySecurityProfile.PASSWORD_HEADER, password),
        )) {
            val cached = GatewaySecurityProfile.create(origin, stored.toByteArray(), ca())
            val session = cached.copyForSession()
            cached.close()
            session.use {
                val headers = mutableMapOf<String, String>()
                it.configureHttps("${origin.value}/v1/audio", {}, headers::put)
                assertEquals(mapOf(header to value), headers)
                val request = sonarRequest("${origin.value}/v1/sonar/stream", it)
                assertEquals(value, request.header(header))
                assertNull(request.header(if (header == GatewaySecurityProfile.HEADER)
                    GatewaySecurityProfile.PASSWORD_HEADER else GatewaySecurityProfile.HEADER))
            }
            assertFailsWith<IllegalStateException> {
                session.configureHttps("${origin.value}/v1/audio", {}, { _, _ -> })
            }
        }
    }

    private fun requireNumericHost(address: InetAddress): String = requireNotNull(address.hostAddress)
}
