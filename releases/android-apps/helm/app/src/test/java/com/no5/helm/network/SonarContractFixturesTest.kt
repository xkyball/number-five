package com.no5.helm.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import com.no5.domain.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Independently decode canonical files emitted/validated by the Python contract tool. */
class SonarContractFixturesTest {
    private fun fixtureRoot() = sequenceOf(File("."), File(".."), File("../..")).first { File(it, "contracts/no5-sonar-v1.schema.json").isFile }.canonicalFile
    private fun wire(root: JSONObject): ByteArray {
        val header=root.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4+header.size+2048).order(ByteOrder.LITTLE_ENDIAN).putInt(header.size).put(header).array()
    }
    @Test fun sonard035GatewayFixtureUsesCanonicalTrackAndPostTxDisplayRange() {
        val root=fixtureRoot()
        val ping=SonarV1Decoder.ping(File(root,"contracts/fixtures/real-sonard-035-ping-v1.bin").readBytes())
        assertFalse(ping.calibration.valid)
        assertEquals(27/1023.0,ping.geometry!!.metersPerBin!!,1e-10)
        assertEquals(4.0,ping.geometry!!.adcSamplePeriodUs!!,.0)
        assertEquals(12.0,ping.bottom.bin!!*ping.geometry!!.metersPerBin!!,1e-10)
        assertEquals(.55,ping.bottom.confidence!!,1e-10)
        assertEquals(SonarBottomSource.DIRECT_ENVELOPE_TRACK_RULES_V1,ping.bottom.source)
        assertEquals(SonarAnalysisQuality.LOCKED,ping.presentation!!.quality)
        assertEquals("0.3.5",ping.hostPackageVersion)
        assertEquals(511,ping.frame.sample(511))
    }
    @Test fun sonard035RejectsMismatchedPresentationPingAndUnverifiedSelectedDepth() {
        val json=File(fixtureRoot(),"contracts/fixtures/real-sonard-035-ping-v1.json").readText()
        val stale=JSONObject(json).also{it.getJSONObject("presentation").put("pingId",6)}
        assertThrows(PiProtocolException::class.java){SonarV1Decoder.ping(wire(stale))}
        val invalid=JSONObject(json).also{it.getJSONObject("geometry").put("txReference","UNVERIFIED").put("displayedMaxDepthM",JSONObject.NULL)}
        assertThrows(PiProtocolException::class.java){SonarV1Decoder.ping(wire(invalid))}
        val falseLock=JSONObject(json).also{it.getJSONObject("presentation").put("quality","SEARCHING")}
        assertThrows(PiProtocolException::class.java){SonarV1Decoder.ping(wire(falseLock))}
        val track=JSONObject(json).also{it.getJSONObject("bottom").put("trackId",JSONObject.NULL)}
        assertThrows(PiProtocolException::class.java){SonarV1Decoder.ping(wire(track))}
    }
    @Test fun realAndSimulatedConformanceFixturesShareOneAndroidDecoder() {
        val root = sequenceOf(File("."), File(".."), File("../..")).first { File(it, "contracts/no5-sonar-v1.schema.json").isFile }.canonicalFile
        for (prefix in listOf("real", "simulated")) {
            val state = SonarV1Decoder.state(File(root, "contracts/fixtures/$prefix-sonar-state-v1.json").readText())
            val pingJson = JSONObject(File(root, "contracts/fixtures/$prefix-sonar-ping-v1.json").readText())
            val header = pingJson.toString().toByteArray(Charsets.UTF_8)
            val wire = ByteBuffer.allocate(4 + header.size + 2048).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(header.size).put(header).array()
            val ping = SonarV1Decoder.ping(wire)
            assertEquals(state.origin, ping.origin)
            assertEquals(prefix == "simulated", ping.origin.simulated)
            assertEquals(state.calibration, ping.calibration)
            assertEquals(1024, ping.frame.samples.size)
        }
        val binary = SonarV1Decoder.ping(File(root, "contracts/fixtures/simulated-sonar-ping-v1.bin").readBytes())
        assertTrue(binary.origin.simulated)
        assertTrue(binary.calibration.calibrated)
        assertEquals(1024, binary.frame.samples.size)
    }
}
