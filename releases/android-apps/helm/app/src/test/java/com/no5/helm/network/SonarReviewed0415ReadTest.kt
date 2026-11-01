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

/** Public synthetic .15 Gateway output, independently bound to the reviewed host fixture. */
class SonarReviewed0415ReadTest {
    private fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    @Test fun exact0415ReadProfilesPreserveTheExistingLimitsSamplesGeometryAndUnverifiedCase() {
        val root = sequenceOf(File("."), File(".."), File("../..")).first {
            File(it, "contracts/fixtures/sonard-0415-reviewed-gateway-v1.json").isFile
        }
        val bytes = File(root, "contracts/fixtures/sonard-0415-reviewed-gateway-v1.json").readBytes()
        assertEquals("6ca4c7806f4d567670a1c6661df7abd6d2909b590c3b4809c723f7b2698fe42d", sha(bytes))
        val fixture = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals("5cf612538f8e760016586e14d3d371facb4e4c97dfc0e75bb870f255759fd4a1", fixture.getString("inputFixtureSha256"))
        val cases = fixture.getJSONArray("cases")
        assertEquals(3, cases.length())
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertTrue(case.getBoolean("synthetic"))
            val state = SonarV1Decoder.state(case.getJSONObject("state").toString())
            val wire = Base64.getDecoder().decode(case.getString("frameBase64"))
            assertEquals(case.getString("frameSha256"), sha(wire))
            val size = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN).int
            assertTrue(size in 1..4096); assertEquals(4 + size + SONAR_BINS * 2, wire.size)
            val ping = SonarV1Decoder.ping(wire)
            assertEquals("0.4.15", ping.hostPackageVersion)
            assertEquals(state.origin, ping.origin)
            assertEquals(SonarPingSource.OWN, ping.pingSource)
            assertTrue(ping.amplitude!!.usable); assertEquals(4095, ping.amplitude!!.fullScale)
            assertFalse(state.canStart); assertFalse(state.canStop)
            val decoded = ByteBuffer.allocate(SONAR_BINS * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in ping.frame.samples) decoded.putShort(sample)
            assertArrayEquals(wire.copyOfRange(4 + size, wire.size), decoded.array())
            if (case.getString("name") == "auto-unverified") {
                assertEquals(SonarTxReference.UNVERIFIED, ping.geometry!!.txReference)
                assertNull(ping.geometry!!.metersPerBin); assertNull(ping.bottom.bin)
                assertEquals(SonarAnalysisQuality.UNVERIFIED_TX, ping.presentation!!.quality)
                assertTrue(ping.presentation!!.tracks.isEmpty()); assertFalse(ping.presentation!!.followBottom)
            } else {
                assertEquals(SonarTxReference.HOST_VERIFIED, ping.geometry!!.txReference)
                assertEquals(if (case.getString("name") == "off-verified") 24.576 else 3.072,
                    ping.geometry!!.displayedMaxDepthM!!, 1e-6)
                assertEquals(1.215, ping.bottom.bin!! * ping.geometry!!.metersPerBin!!, 1e-9)
            }
        }
    }
}
