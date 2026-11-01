package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Actual Python process, socket, bounded HTTP transport, decoder, async source and command ACK. */
class AudioSimulatorIntegrationTest {
    @Test fun actualGatewayFeedsArtworkAndConfirmsCommandsThroughIdenticalApi() {
        val python = System.getenv("NO5_SIMULATOR_PYTHON") ?: "python3"
        assumeTrue(runCatching { ProcessBuilder(python, "--version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false))
        val root = sequenceOf(File("."), File(".."), File("../..")).first { File(it, "simulator/server.py").isFile }.canonicalFile
        val port = ServerSocket(0).use { it.localPort }
        val endpoint = "http://127.0.0.1:$port"
        val log = File.createTempFile("no5-audio-integration", ".log")
        val process = ProcessBuilder(python, "-m", "simulator", "--host", "127.0.0.1", "--port", port.toString(), "--paused", "--weather-off")
            .directory(root).redirectErrorStream(true).redirectOutput(log).start()
        val transport = UrlConnectionPiTransport(endpoint, PiNetworkPolicy.AUDIO)
        var poll: (() -> Unit)? = null
        val source = NetworkPiAudioSource(SourceIdentity("actual-http-session", SourceMode.PI, "TEST"), transport,
            scheduler = PiPollScheduler { _, operation -> poll = operation; SourceSubscription {} })
        try {
            repeat(100) { if (source.state.value.source.quality.status != TelemetryStatus.LIVE) { poll!!(); if (source.state.value.source.quality.status != TelemetryStatus.LIVE) Thread.sleep(50) } }
            assertEquals(TelemetryStatus.LIVE, source.state.value.source.quality.status, log.readText())
            assertTrue(source.state.value.source.quality.simulated); assertNull(source.demoControls)
            assertEquals("tidal-lines", source.state.value.media.artworkId)
            val presetId=source.state.value.dsp.presets.single().id
            val actions = listOf(AudioCommand.Transport(TransportAction.PAUSE), AudioCommand.Transport(TransportAction.NEXT),
                AudioCommand.Transport(TransportAction.SEEK, 12_000), AudioCommand.Fader(AudioChannel.MASTER, -30.0),
                AudioCommand.Mute(AudioChannel.SUB, true), AudioCommand.Preset(presetId))
            for (command in actions) {
                assertEquals(AudioCommandStatus.PENDING, source.submit(command).status)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while(source.outcomes.value?.status==AudioCommandStatus.PENDING && System.nanoTime()<deadline) {
                    poll!!(); if(source.outcomes.value?.status==AudioCommandStatus.PENDING)Thread.sleep(20)
                }
                assertEquals(AudioCommandStatus.VERIFIED, source.outcomes.value!!.status)
            }
            assertEquals("harbor-lights", source.state.value.media.artworkId)
            assertEquals("Harbor Lights", source.state.value.media.title)
            assertEquals(12_000L, source.state.value.media.positionMs)
            assertEquals(-30.0, source.state.value.dsp.faders.getValue(AudioChannel.MASTER).db)
            assertTrue(source.state.value.dsp.faders.getValue(AudioChannel.SUB).muted)
            assertFalse(source.state.value.dsp.meters!!.routingVerified)
            assertEquals(listOf(0,1),source.state.value.dsp.meters!!.groups[AudioChannel.FRONT])
            // Same canonical request can target a future real gateway; provenance is response data.
            val direct = AudioV1Decoder.state(transport.get("/v1/audio"))
            val stale = AudioV1Decoder.command("other-client", "other-client:1", direct.serverSessionId, 0, AudioCommand.Transport(TransportAction.NEXT))
            assertEquals(AudioCommandStatus.REJECTED, AudioV1Decoder.ack(transport.post("/v1/audio/command", stale)).status)
        } finally {
            source.close(); process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS) }
            log.delete()
        }
    }
}
