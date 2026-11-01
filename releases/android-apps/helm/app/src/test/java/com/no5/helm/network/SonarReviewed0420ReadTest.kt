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

/** The .20 near-field display changes samples upstream, not the Android V1 interpretation. */
class SonarReviewed0420ReadTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun cases(): Map<String, JSONObject> {
        val root = sequenceOf(File("."), File(".."), File("../..")).first {
            File(it, "contracts/fixtures/sonard-0420-reviewed-gateway-v1.json").isFile
        }
        val bytes = File(root, "contracts/fixtures/sonard-0420-reviewed-gateway-v1.json").readBytes()
        assertEquals("eb705c12a44cea4da2afe4394d81441c9aa7c982d4b99d125db7261c3c5890b1", sha(bytes))
        val fixture = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals("19cc2fd3a328935a6fa4d6826ed186e1352341edb0c76515009327f59905f75c", fixture.getString("inputFixtureSha256"))
        assertEquals("bb1b88654821b3eb443f66f7ffbb5b8834a5a98c15367a533e4979b43fa327b8", fixture.getString("wheelSha256"))
        assertFalse(fixture.getBoolean("hardwareOpened"))
        val values = fixture.getJSONArray("cases")
        assertEquals(3, values.length())
        return (0 until values.length()).associate { values.getJSONObject(it).let { row -> row.getString("name") to row } }
    }
    private fun samples(ping: SonarV1Ping): ByteArray = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN).also {
        for (sample in ping.frame.samples) it.putShort(sample)
    }.array()

    @Test fun actualThreeStageAtomicGatewayAndDecoderKeepDerivedSamplesSourceTimeAndPhysicalAxisExact() {
        val cases = cases()
        assertEquals(setOf("reference", "mask_only", "early_exclusion"), cases.keys)
        for ((name, case) in cases) {
            assertTrue(case.getBoolean("synthetic"))
            val original = Base64.getDecoder().decode(case.getString("originalM1FrameBase64"))
            assertEquals(2088, original.size); assertEquals(case.getString("originalM1Sha256"), sha(original))
            val header = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(40, header.getShort(2).toInt()); assertEquals(1024, header.getShort(36).toInt())
            val atomic = Base64.getDecoder().decode(case.getString("sourceEnvelopeBase64"))
            assertEquals(case.getString("sourceEnvelopeSha256"), sha(atomic))
            assertArrayEquals(byteArrayOf(78, 53, 69, 67), atomic.copyOfRange(0, 4))
            val envelope = ByteBuffer.wrap(atomic).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1, envelope.getShort(4).toInt()); assertEquals(0, envelope.getShort(6).toInt())
            val contextBytes = envelope.getInt(8)
            assertTrue(contextBytes in 1..4096); assertTrue(atomic.size <= 6200)
            assertArrayEquals(original, atomic.copyOfRange(16 + contextBytes, atomic.size))
            val context = JSONObject(String(atomic, 16, contextBytes, Charsets.UTF_8))
            val host = context.getJSONObject("published_ping")
            val wire = Base64.getDecoder().decode(case.getString("frameBase64"))
            assertEquals(case.getString("frameSha256"), sha(wire))
            val metadataBytes = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN).int
            assertTrue(metadataBytes in 1..4096)
            val state = SonarV1Decoder.state(case.getJSONObject("state").toString())
            val ping = SonarV1Decoder.ping(wire)
            assertEquals("0.4.20", ping.hostPackageVersion)
            assertEquals(state.origin, ping.origin)
            assertEquals(header.getLong(4), ping.frame.pingId)
            assertEquals(header.getLong(12) / 1_000_000, ping.frame.timestampEpochMs)
            assertEquals(host.getString("ping_id").toLong(), ping.frame.pingId)
            assertEquals(host.getString("time_utc_ns").toLong() / 1_000_000, ping.frame.timestampEpochMs)
            assertArrayEquals(original.copyOfRange(40, original.size), wire.copyOfRange(4 + metadataBytes, wire.size))
            assertArrayEquals(original.copyOfRange(40, original.size), samples(ping))
            assertEquals(SonarPingSource.OWN, ping.pingSource)
            assertEquals(SonarPingSource.OWN, ping.displaySelection!!.source)
            assertEquals(host.getLong("display_generation"), ping.displaySelection!!.generation)
            assertEquals(host.getLong("selection_generation"), ping.displaySelection!!.selectionGeneration)
            assertEquals(SonarTxReference.HOST_VERIFIED, ping.geometry!!.txReference)
            assertEquals(header.getFloat(20).toDouble(), ping.geometry!!.displayedMaxDepthM!!, 0.0)
            assertEquals(3.072000026702881, ping.geometry!!.displayedMaxDepthM!!, 0.0)
            assertEquals(2.0, ping.geometry!!.adcSamplePeriodUs!!, 0.0)
            assertTrue(ping.amplitude!!.usable); assertEquals(4095, ping.amplitude!!.fullScale)
            assertEquals(host.getLong("noise_revision"), ping.amplitude!!.noiseRevision)
            assertEquals(host.getBoolean("noise_active"), ping.amplitude!!.noiseActive)
            assertEquals("background_reduction_then_max_pool_or_interpolate", ping.amplitude!!.displayTransform)
            assertFalse(state.canStart); assertFalse(state.canStop)
            if (name != "reference") assertArrayEquals(ByteArray(334), samples(ping).copyOfRange(0, 334))
        }
    }

    @Test fun nearFieldMaskAndEarlyExclusionAreDifferentDerivedViewsOfTheSameRawInputWithoutAxisOffset() {
        val cases = cases()
        val raw = Base64.getDecoder().decode(cases.getValue("reference").getString("rawAdcBase64"))
        val outputs = cases.mapValues { (_, case) ->
            val originalRaw = Base64.getDecoder().decode(case.getString("rawAdcBase64"))
            assertArrayEquals(raw, originalRaw); assertEquals(case.getString("rawAdcSha256"), sha(originalRaw))
            samples(SonarV1Decoder.ping(Base64.getDecoder().decode(case.getString("frameBase64"))))
        }
        assertFalse(outputs.getValue("reference").contentEquals(outputs.getValue("mask_only")))
        assertFalse(outputs.getValue("mask_only").contentEquals(outputs.getValue("early_exclusion")))
        assertEquals(.5, cases.getValue("mask_only").getDouble("displayMaskOnlyM"), 0.0)
        assertEquals(0.0, cases.getValue("mask_only").getJSONObject("hostNoiseReduction").getJSONObject("policy").getDouble("near_field_m"), 0.0)
        assertEquals(.5, cases.getValue("early_exclusion").getJSONObject("hostNoiseReduction").getJSONObject("policy").getDouble("near_field_m"), 0.0)
        // A new host feature never grants an unconfirmed legacy scale to Android.
        val model = SonarModel()
        model.setAnalysis(null, SonarBottom(), null, false, "0.4.20")
        assertFalse(model.amplitudeVerified); assertEquals(65535, model.amplitudeFullScale)
    }

    @Test fun exact20NeverTurnsMissingOrUnconfirmedScaleIntoLegacy4095AndKeepsFrameBytes() {
        val original = Base64.getDecoder().decode(cases().getValue("early_exclusion").getString("frameBase64"))
        val metadataBytes = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).int
        val sampleBytes = original.copyOfRange(4 + metadataBytes, original.size)
        for (mode in listOf("absent", "unconfirmed", "wrong-scale")) {
            val root = JSONObject(String(original, 4, metadataBytes, Charsets.UTF_8))
            when (mode) {
                "absent" -> root.remove("amplitude")
                "wrong-scale" -> root.getJSONObject("amplitude").put("fullScaleCode", 65535)
                else -> root.getJSONObject("amplitude").put("fullScaleCode", JSONObject.NULL)
                    .put("significantBits", JSONObject.NULL).put("normalizationSource", "UNCONFIRMED")
                    .put("rawSamplesModified", JSONObject.NULL)
            }
            val json = root.toString().toByteArray(Charsets.UTF_8)
            val wire = ByteBuffer.allocate(4 + json.size + sampleBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(json.size).put(json).put(sampleBytes).array()
            val ping = SonarV1Decoder.ping(wire)
            assertArrayEquals(sampleBytes, samples(ping))
            assertFalse(ping.amplitude?.usable == true)
            val model = SonarModel()
            model.setAnalysis(ping.geometry, ping.bottom, ping.presentation, false, ping.hostPackageVersion, ping.amplitude)
            assertFalse(model.amplitudeVerified); assertEquals(65535, model.amplitudeFullScale)
        }
    }
}
