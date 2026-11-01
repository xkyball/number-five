package com.no5.helm.network

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** Actual Python gateway → WebSocket/HTTP → canonical Android decoder/source. No device claim. */
class SonarSimulatorIntegrationTest {
    @Test fun actualGatewayStreamsCalibratedUint16AndConfirmsStopStartWithoutReplay() {
        val python = System.getenv("NO5_SIMULATOR_PYTHON") ?: "python3"
        assumeTrue(runCatching { ProcessBuilder(python, "--version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false))
        val root = sequenceOf(File("."), File(".."), File("../..")).first { File(it, "simulator/server.py").isFile }.canonicalFile
        val port = ServerSocket(0).use { it.localPort }
        val endpoint = "http://127.0.0.1:$port"
        val log = File.createTempFile("no5-sonar-integration", ".log")
        val process = ProcessBuilder(python, "-m", "simulator", "--host", "127.0.0.1", "--port", port.toString(), "--paused", "--weather-off")
            .directory(root).redirectErrorStream(true).redirectOutput(log).start()
        val http = UrlConnectionPiTransport(endpoint, PiNetworkPolicy.SONAR)
        var source: NetworkPiSonarSource? = null
        try {
            var ready = false
            val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (!ready && System.nanoTime() < readyDeadline) {
                ready = runCatching { SonarV1Decoder.state(http.get("/v1/sonar")); true }.getOrDefault(false)
                if (!ready) Thread.sleep(50)
            }
            assertTrue(log.readText(), ready)
            val identity = SourceIdentity("actual-sonar-client", SourceMode.PI, "TEST GATEWAY")
            val active = NetworkPiSonarSource(identity, http, OkHttpSonarStreamTransport(endpoint))
            source = active
            fun await(description: String, predicate: () -> Boolean) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                while (System.nanoTime() < deadline) {
                    active.tick()
                    if (predicate()) return
                    Thread.sleep(25)
                }
                fail("$description: ${active.status.value}; ${log.readText()}")
            }
            await("SONAR first packet") { active.frames.value != null }
            assertTrue(active.status.value.connected && active.status.value.running)
            assertTrue(active.status.value.simulated)
            val packet = active.frames.value!!
            assertEquals(1024, packet.frame.samples.size)
            assertEquals(40.0, packet.calibration.samplePeriodUs!!, .0)
            assertTrue(packet.calibration.calibrated)
            assertEquals(12.0, packet.bottom.bin!! * packet.calibration.metersPerBin!!, .001)
            assertTrue(packet.frame.timestampEpochMs > 0)
            assertTrue(active.stop(SonarAcquisitionRequest(identity.sessionId, 1)) is SonarAcquisitionOutcome.Pending)
            await("SONAR stop ACK") { active.outcomes.value is SonarAcquisitionOutcome.Acknowledged && !active.status.value.running }
            val held = active.frames.value!!.frame.pingId
            Thread.sleep(350); active.tick(); assertEquals(held, active.frames.value!!.frame.pingId)
            assertTrue(active.start(SonarAcquisitionRequest(identity.sessionId, 2)) is SonarAcquisitionOutcome.Pending)
            await("SONAR resumed packet") { active.outcomes.value?.requestId == 2L && active.outcomes.value is SonarAcquisitionOutcome.Acknowledged && active.frames.value!!.frame.pingId > held }
            assertTrue(active.status.value.connected && active.status.value.running)
            val sourcePort: SonarSourcePort = active
            assertFalse(sourcePort is SonarDemoControls)
            active.close(); active.tick()
            assertFalse(active.status.value.connected); assertNull(active.frames.value)
        } finally {
            source?.close() ?: http.close()
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS) }
            log.delete()
        }
    }
}
