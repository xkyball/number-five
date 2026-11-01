package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Optional producer extensions must not take the valid raw echo stream down. */
class SonarOptionalAnalysisDecoderTest {
    private fun fixture(): JSONObject {
        val root = sequenceOf(File("."), File(".."), File("../..")).first {
            File(it, "contracts/fixtures/real-sonard-035-ping-v1.json").isFile
        }
        return JSONObject(File(root, "contracts/fixtures/real-sonard-035-ping-v1.json").readText())
    }
    private fun wire(root: JSONObject): ByteArray {
        val header = root.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4 + header.size + 2048).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(header.size).put(header).putShort(4000.toShort()).array()
    }
    private fun track(id: Long) = JSONObject().put("trackId", id).put("bin", 454.6).put("confidence", .55)
        .put("classification", "bottom_like").put("classificationSource", "rules_v1").put("detector", "direct_envelope")

    @Test fun unknownAnalysisQualityPreservesRawTimingAndGeometryButConfirmsNoDepth() {
        val json = fixture(); json.getJSONObject("presentation").put("quality", "FUTURE_ANALYSIS_STATE")
        val value = SonarV1Decoder.ping(wire(json))
        assertEquals(4000, value.frame.sample(0)); assertEquals(7L, value.frame.pingId)
        assertEquals(1700000000000L, value.frame.timestampEpochMs)
        assertEquals(27.0, value.geometry!!.displayedMaxDepthM!!, 0.0)
        assertEquals(4.0, value.geometry!!.adcSamplePeriodUs!!, 0.0)
        assertNull(value.presentation); assertNull(value.bottom.bin); assertFalse(value.bottom.validated)
    }
    @Test fun unknownOrIncompleteOptionalTrackDetailsOnlyRemoveTheirOwnOverlays() {
        val json = fixture()
        val tracks = JSONArray().put(track(1)).put(track(2).put("classification", "new_rule_class"))
            .put(track(3).put("detector", "new_detector"))
            .put(track(4).also { it.remove("confidence") })
            .put(track(5).put("classificationSource", "rules_v2"))
        json.getJSONObject("presentation").put("tracks", tracks).put("trackTotal", 9).put("trackOmitted", 4)
        val value = SonarV1Decoder.ping(wire(json))
        assertEquals(SonarAnalysisQuality.LOCKED, value.presentation!!.quality)
        assertEquals(listOf(1L), value.presentation!!.tracks.map { it.trackId })
        assertEquals(9, value.presentation!!.trackTotal); assertEquals(8, value.presentation!!.trackOmitted)
        assertEquals(12.0, value.bottom.bin!! * value.geometry!!.metersPerBin!!, 1e-9)
        assertEquals(4000, value.frame.sample(0))
    }
    @Test fun missingOptionalAnalysisAndUnknownBottomClassNeverInventDetailValues() {
        val json = fixture(); json.getJSONObject("bottom").put("classification", "new_bottom_class")
        var value = SonarV1Decoder.ping(wire(json))
        assertTrue(value.bottom.validated); assertNull(value.bottom.classification)
        json.getJSONObject("presentation").remove("filter")
        value = SonarV1Decoder.ping(wire(json))
        assertNull(value.presentation); assertNull(value.bottom.bin); assertEquals(4000, value.frame.sample(0))
        json.remove("presentation")
        value = SonarV1Decoder.ping(wire(json))
        assertNull(value.presentation); assertNull(value.bottom.bin)
    }
    @Test fun unknownCanonicalSourceIsUnconfirmedRatherThanAnInventedLegacyBottom() {
        val json = fixture(); json.getJSONObject("bottom").put("source", "future_depth_detector")
        val value = SonarV1Decoder.ping(wire(json))
        assertNull(value.presentation); assertNull(value.bottom.bin); assertNull(value.bottom.source)
        assertEquals(4000, value.frame.sample(0))
    }
    @Test fun wrongPingPayloadBoundsAndKnownInvalidGeometryRemainProtocolErrors() {
        val wrong = fixture().also { it.getJSONObject("presentation").put("pingId", 8) }
        assertThrows(PiProtocolException::class.java) { SonarV1Decoder.ping(wire(wrong)) }
        val tooMany = fixture().also { it.getJSONObject("presentation").put("tracks", JSONArray(List(9) { index -> track(index + 1L) })) }
        assertThrows(PiProtocolException::class.java) { SonarV1Decoder.ping(wire(tooMany)) }
        val invalidGeometry = fixture().also { it.getJSONObject("geometry").put("displayedMaxDepthM", -1.0) }
        assertThrows(PiProtocolException::class.java) { SonarV1Decoder.ping(wire(invalidGeometry)) }
        val badBytes = wire(fixture()) + byteArrayOf(0)
        assertThrows(PiProtocolException::class.java) { SonarV1Decoder.ping(badBytes) }
    }
}
