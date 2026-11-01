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

/** Exact public synthetic Gateway projections; no live device, TX or host lifecycle. */
class SonarReviewed0414ReadTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fixture(): JSONObject {
        val root = sequenceOf(File("."), File(".."), File("../..")).first {
            File(it, "contracts/fixtures/sonard-0414-reviewed-gateway-v1.json").isFile
        }
        val raw = File(root, "contracts/fixtures/sonard-0414-reviewed-gateway-v1.json").readBytes()
        assertEquals("81ddbc4f6c01da5cb0144484e81646c3708e7872188298379741bf06d63bb679", sha(raw))
        return JSONObject(String(raw, Charsets.UTF_8)).also {
            assertEquals("89114aac6059689254eb003176ff213442149a6a4e9c7a8cab4f80627ad9f4dc", it.getString("inputFixtureSha256"))
        }
    }
    @Test fun theThreeReviewed0414ProfilesUseTheUnchangedBoundedAppDecoderAndPreserveSamples() {
        val cases = fixture().getJSONArray("cases")
        assertEquals(3, cases.length())
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertTrue(case.getBoolean("synthetic"))
            val state = SonarV1Decoder.state(case.getJSONObject("state").toString())
            val binary = Base64.getDecoder().decode(case.getString("frameBase64"))
            assertEquals(case.getString("frameSha256"), sha(binary))
            val metadataBytes = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN).int
            assertTrue(metadataBytes in 1..4096)
            assertEquals(4 + metadataBytes + SONAR_BINS * 2, binary.size)
            val ping = SonarV1Decoder.ping(binary)
            assertEquals(state.origin, ping.origin)
            assertEquals("0.4.14", ping.hostPackageVersion)
            assertEquals(SonarPingSource.OWN, ping.pingSource)
            assertTrue(ping.amplitude!!.usable)
            assertEquals(4095, ping.amplitude!!.fullScale)
            assertFalse("A read compatibility approval cannot create product TX control", state.canStart)
            assertFalse(state.canStop)
            val decoded = ByteBuffer.allocate(SONAR_BINS * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in ping.frame.samples) decoded.putShort(sample)
            assertArrayEquals(binary.copyOfRange(4 + metadataBytes, binary.size), decoded.array())
        }
    }
    @Test fun reviewedWindowGeometryKeepsPhysicalDepthAndAnUnverifiedFrameNeverGetsAMeterAxis() {
        val cases = fixture().getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val ping = SonarV1Decoder.ping(Base64.getDecoder().decode(case.getString("frameBase64")))
            if (case.getString("name") == "auto-unverified") {
                assertEquals(SonarTxReference.UNVERIFIED, ping.geometry!!.txReference)
                assertNull(ping.geometry!!.metersPerBin); assertNull(ping.bottom.bin)
                assertEquals(SonarAnalysisQuality.UNVERIFIED_TX, ping.presentation!!.quality)
                assertTrue(ping.presentation!!.tracks.isEmpty())
                assertFalse(ping.presentation!!.followBottom)
                assertFalse(ping.amplitude!!.noiseActive!!)
            } else {
                assertEquals(SonarTxReference.HOST_VERIFIED, ping.geometry!!.txReference)
                assertEquals(if (case.getString("name") == "off-verified") 24.576 else 3.072,
                    ping.geometry!!.displayedMaxDepthM!!, 1e-6)
                assertEquals(1.215, ping.bottom.bin!! * ping.geometry!!.metersPerBin!!, 1e-9)
                assertTrue(ping.amplitude!!.noiseActive!!)
            }
        }
    }
}
