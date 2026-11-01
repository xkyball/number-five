package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

/** Atomic host evidence terminates at Gateway; the actual Android V1 decoder remains unchanged. */
class SonarReviewed0416ReadTest {
    private fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    @Test fun exact0416AtomicGatewayProjectionsKeepSamplesSourcesGeometryAndUnconfirmedCasesHonest() {
        val root = sequenceOf(File("."), File(".."), File("../..")).first {
            File(it, "contracts/fixtures/sonard-0416-reviewed-gateway-v1.json").isFile
        }
        val bytes = File(root, "contracts/fixtures/sonard-0416-reviewed-gateway-v1.json").readBytes()
        assertEquals("4f121cf7abacc5517d122d152b6607b942f2827f0139a6a322939b9a835a380d", sha(bytes))
        val fixture = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals("b9ff41f7a72f09bfa7583f96e6bba99c3283119dd7bec197d67ba7f94d413769", fixture.getString("inputFixtureSha256"))
        assertEquals("590af7330c4ac5dbb9a23d91c91a47ebe1030fc45ffb96515f268bf13bf36ed3", fixture.getString("wheelSha256"))
        assertFalse(fixture.getBoolean("hardwareOpened"))
        val cases = fixture.getJSONArray("cases")
        assertEquals(5, cases.length())
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertTrue(case.getBoolean("synthetic"))
            val state = SonarV1Decoder.state(case.getJSONObject("state").toString())
            val wire = Base64.getDecoder().decode(case.getString("frameBase64"))
            assertEquals(case.getString("frameSha256"), sha(wire))
            val size = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN).int
            assertTrue(size in 1..4096)
            assertEquals(4 + size + SONAR_BINS * 2, wire.size)
            val metadata = JSONObject(String(wire, 4, size, Charsets.UTF_8))
            assertEquals(case.getJSONObject("pingMetadata").toString(), metadata.toString())
            val publication = metadata.getJSONObject("publication")
            assertEquals(1, publication.getInt("version"))
            assertEquals("1", publication.getString("sequence"))
            assertEquals(0, publication.getInt("sequenceGap"))
            val ping = SonarV1Decoder.ping(wire)
            assertEquals("0.4.16", ping.hostPackageVersion)
            assertEquals(state.origin, ping.origin)
            assertFalse(state.canStart); assertFalse(state.canStop)
            assertTrue(ping.amplitude!!.usable)
            assertEquals(4095, ping.amplitude!!.fullScale)
            assertEquals(2.0, ping.geometry!!.adcSamplePeriodUs!!, 0.0)
            val samples = ByteBuffer.allocate(SONAR_BINS * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in ping.frame.samples) samples.putShort(sample)
            assertArrayEquals(wire.copyOfRange(4 + size, wire.size), samples.array())
            // Publication origin is not a substitute for an absent same-ping analysis.
            assertNull(ping.bottom.bin); assertNull(ping.presentation)
            when (case.getString("name")) {
                "full_legacy_window", "qualified_display_window", "unverified_own" -> {
                    assertEquals(SonarPingSource.OWN, ping.pingSource)
                    assertEquals(SonarPingSource.OWN, ping.displaySelection!!.source)
                }
                "confirmed_native" -> {
                    assertEquals(SonarPingSource.NATIVE, ping.pingSource)
                    assertEquals(SonarPingSource.NATIVE, ping.displaySelection!!.source)
                }
                "unconfirmed_source" -> {
                    assertNull(ping.pingSource)
                    assertEquals(SonarPingSource.ALL, ping.displaySelection!!.source)
                    assertEquals("unconfirmed", publication.getString("sourceEvidence"))
                }
                else -> fail("Unreviewed fixture case")
            }
            if (case.getString("name") in setOf("unverified_own", "unconfirmed_source")) {
                assertEquals(SonarTxReference.UNVERIFIED, ping.geometry!!.txReference)
                assertNull(ping.geometry!!.metersPerBin)
                assertFalse(ping.amplitude!!.noiseActive!!)
            } else {
                assertEquals(SonarTxReference.HOST_VERIFIED, ping.geometry!!.txReference)
                assertEquals(if (case.getString("name") == "qualified_display_window") 3.072 else 24.576,
                    ping.geometry!!.displayedMaxDepthM!!, 1e-6)
                assertTrue(ping.amplitude!!.noiseActive!!)
            }
        }
        assertEquals(2, fixture.getJSONArray("unsupportedHostBoundaryCases").length())
    }
}
