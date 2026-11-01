package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SonarObservationDecoderTest {
    private fun amplitude() = JSONObject().put("version", 1).put("encoding", "UINT16_LE").put("unit", "ADC_CODE")
        .put("significantBits", 12).put("fullScaleCode", 4095).put("normalizationSource", "HELLO_12bit_checked_host_contract")
        .put("rawSamplesModified", false)
    @Test fun explicitAmplitudeIsVerifiedAndAnUnknownOrMalformedContractNeverGetsLegacyNormalization() {
        fun decode(value: JSONObject) = SonarV1Decoder.amplitude(JSONObject().put("amplitude", value))!!
        assertTrue(decode(amplitude()).usable)
        for (bad in listOf(amplitude().put("version", 2), amplitude().put("fullScaleCode", 65535),
            amplitude().put("fullScaleCode", 65535).put("significantBits", 16),
            amplitude().put("fullScaleCode", 4095.5), amplitude().put("rawSamplesModified", true),
            amplitude().put("fullScaleCode", JSONObject.NULL).put("significantBits", JSONObject.NULL)
                .put("rawSamplesModified", JSONObject.NULL).put("normalizationSource", "UNCONFIRMED"))) {
            assertFalse(decode(bad).usable)
        }
        assertNull(SonarV1Decoder.amplitude(JSONObject()))
        assertFalse(SonarV1Decoder.amplitude(JSONObject().put("amplitude", JSONObject.NULL))!!.usable)
        assertFalse(SonarV1Decoder.amplitude(JSONObject().put("amplitude", "invalid"))!!.usable)
        assertFalse(decode(amplitude().put("normalizationSource", "future-unchecked-producer")).usable)
    }
    @Test fun unknownSelectionEnumKeepsItsEpochWithoutKillingTheCompatibleStream() {
        val root = JSONObject().put("displaySelection", JSONObject().put("version", 1).put("source", "future-source")
            .put("generation", 9).put("publishedHz", JSONObject.NULL).put("observedAtEpochMs", JSONObject.NULL))
        val value = SonarV1Decoder.displaySelection(root)!!
        assertNull(value.source); assertEquals(9L, value.generation)
        root.getJSONObject("displaySelection").put("version", 2)
        assertNull(SonarV1Decoder.displaySelection(root))
    }
    @Test fun derivedContrastKeepsExactScaleAndRequiresCompleteConsistentPerFrameContext() {
        val original = amplitude().put("displayTransform", "max_pool_or_interpolate_without_amplitude_scaling")
            .put("noiseRevision", 4).put("noiseActive", false)
        val derived = JSONObject(original.toString()).put("displayTransform", "background_reduction_then_max_pool_or_interpolate")
            .put("noiseActive", true)
        fun decode(value: JSONObject) = SonarV1Decoder.amplitude(JSONObject().put("amplitude", value))!!
        assertTrue(decode(original).usable); assertTrue(decode(derived).usable)
        assertEquals(4095, decode(derived).fullScale); assertEquals(4L, decode(derived).noiseRevision)
        assertTrue(decode(derived).noiseActive!!)
        for (invalid in listOf(JSONObject(derived.toString()).put("noiseRevision", 4.5),
            JSONObject(derived.toString()).put("noiseRevision", -1), JSONObject(derived.toString()).put("noiseActive", false),
            JSONObject(derived.toString()).put("noiseActive", JSONObject.NULL),
            JSONObject(derived.toString()).put("displayTransform", "future_transform"))) assertFalse(decode(invalid).usable)
        val selected = JSONObject().put("displaySelection", JSONObject().put("version", 1).put("source", "own")
            .put("generation", 12).put("selectionGeneration", 3))
        assertEquals(12L, SonarV1Decoder.displaySelection(selected)!!.generation)
        assertEquals(3L, SonarV1Decoder.displaySelection(selected)!!.selectionGeneration)
    }
    private val identity = SourceIdentity("test-sonar", SourceMode.PI, "SONAR")
    private fun diagnostics() = JSONObject().put("version", 1).put("kind", "sonar-diagnostics")
        .put("serverSessionId", "server").put("generation", 0).put("pingId", Long.MAX_VALUE)
        .put("observedAtEpochMs", 1_000_000).put("source", "own").put("available", true).put("stale", false)
        .put("fields", JSONArray().put(JSONObject().put("group", "transmit").put("name", "markers")
            .put("value", Long.MAX_VALUE.toString()).put("unit", "count")))
    @Test fun diagnosticsPreservesExactIdsAndRejectsOversizedAndInvalidStructures() {
        val value = SonarDiagnosticsDecoder.decode(diagnostics().toString(), identity)
        assertEquals(Long.MAX_VALUE, value.pingId); assertEquals(Long.MAX_VALUE.toString(), value.fields.single().value)
        assertEquals(SonarPingSource.OWN, value.pingSource)
        assertThrows(IllegalArgumentException::class.java) { SonarDiagnosticsDecoder.decode("x".repeat(32769), identity) }
        assertThrows(IllegalArgumentException::class.java) {
            SonarDiagnosticsDecoder.decode(diagnostics().put("fields", JSONArray(List(129) {
                JSONObject().put("group", "x").put("name", "x").put("value", "x") })).toString(), identity)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SonarDiagnosticsDecoder.decode(diagnostics().put("trackTotal", 1).put("trackOmitted", 2).toString(), identity)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SonarDiagnosticsDecoder.decode(diagnostics().put("pingId", "9223372036854775807").toString(), identity)
        }
    }
    @Test fun diagnosticStatusReceiptAndAnalysisTimeRemainIndependentAndOptional() {
        val body = diagnostics().put("pingId", JSONObject.NULL).put("observedAtEpochMs", JSONObject.NULL)
            .put("stale", true).put("statusObservedAtEpochMs", 1_000_000).put("statusStale", false)
            .put("statusTimeBasis", "GATEWAY_STATUS_REQUEST_STARTED_AT_UTC")
        val value = SonarDiagnosticsDecoder.decode(body.toString(), identity)
        assertNull(value.pingId); assertNull(value.observedAtEpochMs); assertTrue(value.stale)
        assertEquals(1_000_000L, value.statusObservedAtEpochMs!!); assertFalse(value.statusStale!!)
        assertEquals("GATEWAY_STATUS_REQUEST_STARTED_AT_UTC", value.statusTimeBasis)
        val old = SonarDiagnosticsDecoder.decode(diagnostics().toString(), identity)
        assertNull(old.statusObservedAtEpochMs); assertNull(old.statusStale); assertNull(old.statusTimeBasis)
    }
}
