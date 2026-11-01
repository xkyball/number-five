package com.no5.home

import com.no5.helm.network.PiProtocolException
import com.no5.helm.network.PiV1Decoder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
class GatewayStatusContractTest {
    private fun real() = JSONObject(File("../helm/contracts/fixtures/real-status-v1.json").readText())
    private fun rejects(root: JSONObject) = assertThrows(PiProtocolException::class.java) { PiV1Decoder.gatewayStatus(root.toString()) }

    @Test fun configuredGatewayWithoutHelmSensorsCanConfirmItsAddress() {
        val root = real().put("availableEndpoints", JSONArray(listOf("/v1/status", "/v1/audio", "/v1/sonar")))
        root.getJSONObject("capabilities").put("helm", false).put("weather", false).put("sonar", true)
        PiV1Decoder.gatewayStatus(root.toString())
    }
    @Test fun canonicalRealAndSimulatedStatusFixturesConform() {
        PiV1Decoder.gatewayStatus(real().toString())
        PiV1Decoder.gatewayStatus(File("../helm/simulator/tests/fixtures/status-v1.json").readText())
    }
    @Test fun wrongFeatureVersionOrProvenanceCannotConfirmGateway() {
        rejects(real().put("kind", "helm"))
        rejects(real().put("version", 2))
        rejects(real().put("sourceKind", "REAL").put("simulated", true))
        rejects(real().put("timestampEpochMs", 0))
    }
    @Test fun arbitraryHtmlExtensionsOrDuplicateJsonKeysAreRejected() {
        for (payload in listOf("<html>OK</html>", "{kind:'status'}", real().toString().dropLast(1) + ",\"version\":1}"))
            assertThrows(PiProtocolException::class.java) { PiV1Decoder.gatewayStatus(payload) }
    }
    @Test fun malformedCapabilitiesAndEndpointListsAreRejected() {
        val missing = real(); missing.getJSONObject("capabilities").remove("hardwareWrites"); rejects(missing)
        val typed = real(); typed.getJSONObject("capabilities").put("helm", "false"); rejects(typed)
        rejects(real().put("availableEndpoints", JSONArray(listOf("/v1/status", "/v1/status"))))
        rejects(real().put("availableEndpoints", JSONArray(listOf("https://example.invalid/v1/status"))))
    }
    @Test fun syntheticClockOrUnboundedJsonCannotConfirmRealGateway() {
        rejects(real().put("simulation", JSONObject().put("elapsedSeconds", 1)))
        assertThrows(PiProtocolException::class.java) { PiV1Decoder.gatewayStatus(" ".repeat(65537) + real()) }
    }
}
