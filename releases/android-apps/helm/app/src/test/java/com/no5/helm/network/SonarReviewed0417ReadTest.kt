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

/** Display DSP changes samples upstream; Android still consumes the unchanged Gateway V1. */
class SonarReviewed0417ReadTest {
    private fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun fixture(): JSONObject {
        val root = sequenceOf(File("."), File(".."), File("../..")).first {
            File(it, "contracts/fixtures/sonard-0417-reviewed-gateway-v1.json").isFile
        }
        val bytes = File(root, "contracts/fixtures/sonard-0417-reviewed-gateway-v1.json").readBytes()
        assertEquals("ea61a1fefc048fe7dbb23aa8f6b7c60ae411692cabde70a723bea92034c89dc4", sha(bytes))
        return JSONObject(String(bytes, Charsets.UTF_8)).also {
            assertEquals("4609690327ec7284fc3ac979a83bf53fe799bc9c67701e6ea600a6d820813250", it.getString("inputFixtureSha256"))
            assertEquals("6c7ddf80c7a894937f3109bae96c8477cade368789b79432adc8c8c40e4d311a", it.getString("wheelSha256"))
            assertFalse(it.getBoolean("hardwareOpened"))
        }
    }
    private fun caseMap(): Map<String, JSONObject> {
        val rows = fixture().getJSONArray("cases")
        assertEquals(9, rows.length())
        return (0 until rows.length()).associate { rows.getJSONObject(it).let { row -> row.getString("name") to row } }
    }
    private fun decodedSamples(ping: SonarV1Ping): ByteArray {
        val output = ByteBuffer.allocate(SONAR_BINS * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in ping.frame.samples) output.putShort(sample)
        return output.array()
    }

    @Test fun nine0417HostM1ProfilesStayByteIdenticalThroughGatewayAndTheUnchangedAndroidDecoder() {
        val cases = caseMap()
        assertEquals(setOf("impulse_off", "impulse_auto", "impulse_on", "auto_no_signature", "auto_native",
            "auto_other_period", "auto_clipped", "auto_isolated", "auto_protected_weak"), cases.keys)
        for ((name, case) in cases) {
            assertTrue(case.getBoolean("synthetic"))
            val original = Base64.getDecoder().decode(case.getString("originalM1FrameBase64"))
            assertEquals(2088, original.size)
            assertEquals(case.getString("originalM1Sha256"), sha(original))
            val header = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1, original[0].toInt()); assertEquals(1, original[1].toInt())
            assertEquals(40, header.getShort(2).toInt())
            assertEquals(1024, header.getShort(36).toInt()); assertEquals(0, header.getShort(38).toInt())
            val envelope = Base64.getDecoder().decode(case.getString("sourceEnvelopeBase64"))
            assertEquals(case.getString("sourceEnvelopeSha256"), sha(envelope))
            val contextHeader = ByteBuffer.wrap(envelope).order(ByteOrder.LITTLE_ENDIAN)
            assertArrayEquals(byteArrayOf(78, 53, 69, 67), envelope.copyOfRange(0, 4))
            assertEquals(1, contextHeader.getShort(4).toInt()); assertEquals(0, contextHeader.getShort(6).toInt())
            val contextSize = contextHeader.getInt(8)
            assertTrue(contextSize in 1..4096); assertEquals(2088, contextHeader.getInt(12))
            assertEquals(16 + contextSize + original.size, envelope.size)
            assertArrayEquals(original, envelope.copyOfRange(16 + contextSize, envelope.size))
            val context = JSONObject(String(envelope, 16, contextSize, Charsets.UTF_8))
            assertEquals("no5.sonard-echogram-context/1", context.getString("schema"))
            val host = context.getJSONObject("published_ping")
            assertEquals(header.getLong(4), host.getString("ping_id").toLong())
            assertEquals(header.getLong(12), host.getString("time_utc_ns").toLong())
            val wire = Base64.getDecoder().decode(case.getString("frameBase64"))
            assertEquals(case.getString("frameSha256"), sha(wire))
            val metadataSize = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN).int
            assertTrue(metadataSize in 1..4096); assertEquals(4 + metadataSize + 2048, wire.size)
            val metadata = JSONObject(String(wire, 4, metadataSize, Charsets.UTF_8))
            val state = SonarV1Decoder.state(case.getJSONObject("state").toString())
            val ping = SonarV1Decoder.ping(wire)
            assertEquals("0.4.17", ping.hostPackageVersion)
            assertEquals(state.origin, ping.origin)
            assertFalse(state.canStart); assertFalse(state.canStop)
            assertEquals(header.getLong(4), ping.frame.pingId)
            assertEquals(header.getLong(12) / 1_000_000, ping.frame.timestampEpochMs)
            assertArrayEquals(original.copyOfRange(40, original.size), wire.copyOfRange(4 + metadataSize, wire.size))
            assertArrayEquals(original.copyOfRange(40, original.size), decodedSamples(ping))
            val expectedSource = if (name == "auto_native") SonarPingSource.NATIVE else SonarPingSource.OWN
            assertEquals(expectedSource, ping.pingSource)
            assertEquals(expectedSource, ping.displaySelection!!.source)
            assertEquals(context.getString("selected_source"), ping.displaySelection!!.source!!.name.lowercase())
            assertEquals(host.getString("actual_source"), ping.pingSource!!.name.lowercase())
            assertEquals(state.displaySelection!!.source, ping.displaySelection!!.source)
            assertEquals(state.displaySelection!!.generation, ping.displaySelection!!.generation)
            assertEquals(state.displaySelection!!.selectionGeneration, ping.displaySelection!!.selectionGeneration)
            assertEquals(2L, ping.displaySelection!!.generation)
            assertEquals(1L, ping.displaySelection!!.selectionGeneration)
            assertEquals(host.getLong("display_generation"), ping.displaySelection!!.generation)
            assertEquals(host.getLong("selection_generation"), ping.displaySelection!!.selectionGeneration)
            assertEquals(SonarTxReference.HOST_VERIFIED, ping.geometry!!.txReference)
            assertEquals(header.getFloat(20).toDouble(), ping.geometry!!.displayedMaxDepthM!!, 0.0)
            assertEquals(header.getInt(32).toLong() and 0xffffffffL, ping.geometry!!.flags)
            assertEquals(if (name == "auto_other_period") 4.0 else 2.0, ping.geometry!!.adcSamplePeriodUs!!, 0.0)
            assertTrue(ping.amplitude!!.usable); assertEquals(4095, ping.amplitude!!.fullScale)
            assertEquals(12, ping.amplitude!!.adcBits); assertEquals(2L, ping.amplitude!!.noiseRevision)
            assertEquals(host.getLong("noise_revision"), ping.amplitude!!.noiseRevision)
            assertEquals(host.getBoolean("noise_active"), ping.amplitude!!.noiseActive)
            assertEquals("background_reduction_then_max_pool_or_interpolate", ping.amplitude!!.displayTransform)
            assertTrue("noiseActive describes all DSP, including v2/off and conservative impulse non-matches", ping.amplitude!!.noiseActive!!)
            assertNull(ping.bottom.bin); assertNull(ping.presentation)
            val publication = metadata.getJSONObject("publication")
            assertEquals("1", publication.getString("sequence")); assertEquals(0, publication.getInt("sequenceGap"))
            assertEquals(context.getString("publication_sequence"), publication.getString("sequence"))
            assertEquals(if (expectedSource == SonarPingSource.NATIVE) "qualified_native_marker_raw_match" else
                "physical_own_marker_raw_match", publication.getString("sourceEvidence"))
        }
    }

    @Test fun impulsePolicyAndActualImpulseActivityDoNotRedefineTheGenericAmplitudeFlag() {
        val cases = caseMap()
        for ((name, case) in cases) {
            val noise = case.getJSONObject("hostNoiseReduction")
            val policy = noise.getJSONObject("policy")
            val latest = noise.getJSONObject("latest")
            val impulse = latest.getJSONObject("impulse_reduction")
            assertEquals(if (name == "impulse_off") "local_adc_background_v2" else "local_adc_background_v3",
                policy.getString("algorithm"))
            assertEquals(if (name == "impulse_off") "off" else if (name == "impulse_on") "on" else "auto",
                policy.getString("impulse_mode"))
            assertEquals(name in setOf("impulse_auto", "impulse_on", "auto_protected_weak"), impulse.getBoolean("active"))
            assertTrue(latest.getBoolean("active"))
            val ping = SonarV1Decoder.ping(Base64.getDecoder().decode(case.getString("frameBase64")))
            assertEquals(latest.getBoolean("active"), ping.amplitude!!.noiseActive)
            if (name == "auto_protected_weak") {
                assertTrue(impulse.getBoolean("detected_echo_samples_unchanged"))
                assertTrue(impulse.getBoolean("background_reestimated"))
            }
        }
        val off = decodedSamples(SonarV1Decoder.ping(Base64.getDecoder().decode(cases.getValue("impulse_off").getString("frameBase64"))))
        val auto = decodedSamples(SonarV1Decoder.ping(Base64.getDecoder().decode(cases.getValue("impulse_auto").getString("frameBase64"))))
        val on = decodedSamples(SonarV1Decoder.ping(Base64.getDecoder().decode(cases.getValue("impulse_on").getString("frameBase64"))))
        assertFalse("Matched impulse reduction intentionally changes the derived display samples", off.contentEquals(auto))
        assertArrayEquals(auto, on)
    }
}
