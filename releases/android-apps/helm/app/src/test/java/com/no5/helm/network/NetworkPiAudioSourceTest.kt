package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

private class AudioScheduler : PiPollScheduler {
    lateinit var poll: () -> Unit
    var closed = false
    override fun schedule(intervalMs: Long, poll: () -> Unit): SourceSubscription {
        assertEquals(250L, intervalMs); this.poll = poll
        return SourceSubscription { closed = true }
    }
}
private class AudioTransport : PiHttpTransport {
    var body = wireFixture("audio")
    var fail = false
    var closed = false
    var posts = 0
    var onPost: ((String) -> String)? = null
    override fun get(path: String): String { if (fail) throw IOException("Offline"); return body }
    override fun post(path: String, body: String): String {
        assertEquals("/v1/audio/commands", path); posts++
        if (fail) throw IOException("Offline")
        return onPost?.invoke(body) ?: ack(body)
    }
    fun ack(request: String, status: String = "VERIFIED", alter: (JSONObject) -> Unit = {}): String {
        val input = JSONObject(request)
        val state = JSONObject(body).put("revision", input.getLong("expectedRevision") + 1)
        state.getJSONObject("media").put("title", "Acknowledged track")
        val root = JSONObject().put("version", 1).put("kind", "audio-command-result").put("commandId","fixture-command").put("source", state.getString("source"))
            .put("sourceKind", state.getString("sourceKind")).put("simulated", state.getBoolean("simulated"))
            .put("sessionId", input.getString("sessionId")).put("requestId", input.getString("requestId"))
            .put("serverSessionId", state.getString("serverSessionId")).put("revision", state.getLong("revision"))
            .put("status", if(status=="VERIFIED")"APPLIED" else status).put("message", "Gateway acknowledged").put("state", state)
        alter(root)
        return root.toString()
    }
    override fun close() { closed = true }
}

class NetworkPiAudioSourceTest {
    private val identity = SourceIdentity("audio-client", SourceMode.PI, "AUDIO TEST")
    private val stamp = JSONObject(wireFixture("audio")).getLong("timestampEpochMs")
    private val next = AudioCommand.Transport(TransportAction.NEXT)

    @Test fun knobCommandsKeepDisplayedMasterAndMediaConfirmedUntilGatewayAck() {
        val scheduler = AudioScheduler(); val transport = AudioTransport()
        val source = NetworkPiAudioSource(identity, transport, { stamp }, scheduler)
        scheduler.poll()
        val controller = com.no5.helm.AudioNativeController(kotlinx.coroutines.Dispatchers.Unconfined,
            now = { stamp }, automaticTicker = false, source = source)
        val knobs = com.no5.helm.input.KnobAudioCommands(source)
        val before = controller.state.dsp.faders.getValue(AudioChannel.MASTER)
        assertTrue(knobs.perform(com.no5.helm.input.KnobAction.MASTER_UP))
        assertEquals(before, controller.state.dsp.faders.getValue(AudioChannel.MASTER))
        assertFalse(knobs.perform(com.no5.helm.input.KnobAction.MASTER_MUTE))
        transport.onPost = { body -> transport.ack(body) { ack ->
            val master = ack.getJSONObject("state").getJSONObject("dsp").getJSONObject("faders").getJSONObject("MASTER")
            master.put("db", before.db!! + 1.0)
            ack.getJSONObject("state").getJSONObject("dsp").getJSONArray("faderControls").getJSONObject(0).put("db", before.db!! + 1.0)
        } }
        scheduler.poll()
        assertEquals(before.db!! + 1.0, controller.state.dsp.faders.getValue(AudioChannel.MASTER).db)
        val media = controller.state.media
        assertTrue(knobs.perform(com.no5.helm.input.KnobAction.PLAY_PAUSE))
        assertEquals(media, controller.state.media)
        transport.fail = true; scheduler.poll()
        assertEquals(media, controller.state.media)
        assertEquals(AudioCommandStatus.FAILED, source.outcomes.value!!.status)
        controller.dispose(); source.close()
    }

    @Test fun commandIsAsynchronousBoundedAndOnlyAckChangesConfirmedValue() {
        val scheduler = AudioScheduler(); val transport = AudioTransport()
        val source = NetworkPiAudioSource(identity, transport, { stamp }, scheduler)
        assertFalse(source.available(next)); scheduler.poll()
        assertTrue(source.available(next)); assertNull(source.demoControls)
        val before = source.state.value.media.title
        val pending = source.submit(next)
        assertEquals(AudioCommandStatus.PENDING, pending.status)
        assertEquals(0, transport.posts); assertEquals(before, source.state.value.media.title)
        assertEquals(AudioCommandStatus.REJECTED, source.submit(next).status)
        scheduler.poll()
        assertEquals(1, transport.posts); assertEquals("Acknowledged track", source.state.value.media.title)
        assertEquals(AudioCommandStatus.VERIFIED, source.outcomes.value!!.status)
        assertNull(source.state.value.pending)
        source.close(); assertTrue(scheduler.closed && transport.closed)
    }

    @Test fun physicalFaderReleaseQueuesExactlyOneGatewayPostWithRevisionEvidence() {
        val scheduler = AudioScheduler(); val transport = AudioTransport()
        val source = NetworkPiAudioSource(identity, transport, { stamp }, scheduler)
        scheduler.poll()
        val controller = com.no5.helm.AudioNativeController(
            kotlinx.coroutines.Dispatchers.Unconfined, now = { stamp }, automaticTicker = false,
            automaticVerification = false, source = source,
        )
        val master = controller.masterControl()!!
        transport.onPost = { body -> transport.ack(body) { ack ->
            val dsp = ack.getJSONObject("state").getJSONObject("dsp")
            dsp.getJSONObject("faders").getJSONObject("MASTER").put("db", -30.0)
            dsp.getJSONArray("faderControls").getJSONObject(0).put("db", -30.0)
        } }

        controller.previewLevel(master, -30.0)
        controller.commitPreview()

        assertEquals(AudioCommandStatus.PENDING, controller.lastOutcome!!.status)
        assertEquals(-30.0, controller.pendingVisual!!.value, 0.0)
        assertEquals(0, transport.posts)
        scheduler.poll()

        val outcome = controller.lastOutcome!!
        assertEquals(1, transport.posts)
        assertEquals(AudioCommandStatus.VERIFIED, outcome.status)
        assertEquals(0L, outcome.expectedGatewayRevision)
        assertEquals(1L, outcome.acknowledgedGatewayRevision)
        assertEquals(-30.0, outcome.confirmedValueDb!!, 0.0)
        assertNull(controller.pendingVisual)
        assertTrue(controller.gatewayWriteDiagnostic.contains("rev 0 → 1"))
        controller.dispose()
    }

    @Test fun faderAckWithDifferentConfirmedValueFailsWithoutReplay() {
        val scheduler = AudioScheduler(); val transport = AudioTransport()
        val source = NetworkPiAudioSource(identity, transport, { stamp }, scheduler)
        scheduler.poll()
        val controller = com.no5.helm.AudioNativeController(kotlinx.coroutines.Dispatchers.Unconfined,
            now = { stamp }, automaticTicker = false, automaticVerification = false, source = source)

        controller.previewLevel(controller.masterControl()!!, -30.0)
        controller.commitPreview()
        scheduler.poll()

        assertEquals(1, transport.posts)
        assertEquals(AudioCommandStatus.FAILED, controller.lastOutcome!!.status)
        assertEquals(-24.0, controller.lastOutcome!!.confirmedValueDb!!, 0.0)
        assertTrue(controller.gatewayWriteDiagnostic.contains("did not confirm requested fader"))
        controller.dispose()
    }

    @Test fun blockedFaderGestureExposesTheLocalGateWithoutClaimingAPost() {
        val scheduler = AudioScheduler(); val transport = AudioTransport()
        val root = JSONObject(transport.body)
        root.getJSONObject("capabilities").put("faderWrite", false)
        transport.body = root.toString()
        val source = NetworkPiAudioSource(identity, transport, { stamp }, scheduler)
        scheduler.poll()
        val controller = com.no5.helm.AudioNativeController(
            kotlinx.coroutines.Dispatchers.Unconfined, now = { stamp }, automaticTicker = false,
            automaticVerification = false, source = source,
        )

        controller.previewLevel(controller.masterControl()!!, -30.0)

        assertEquals(0, transport.posts)
        assertEquals(AudioCommandStatus.REJECTED, controller.lastOutcome!!.status)
        assertTrue(controller.gatewayWriteDiagnostic.contains("AUDIO_GATE_FADER_WRITE_UNAVAILABLE"))
        assertTrue(controller.gatewayWriteDiagnostic.contains("no command sent"))
        controller.dispose()
    }

    @Test fun blockedPauseNamesTheClientGateAndSendsNoPost() {
        val scheduler = AudioScheduler(); val transport = AudioTransport()
        val root = JSONObject(transport.body)
        root.getJSONObject("capabilities").put("playPause", false)
        transport.body = root.toString()
        val source = NetworkPiAudioSource(identity, transport, { stamp }, scheduler)
        scheduler.poll()

        val outcome = source.submit(AudioCommand.Transport(TransportAction.PAUSE))

        assertEquals(AudioCommandStatus.REJECTED, outcome.status)
        assertEquals("AUDIO_GATE_PLAY_PAUSE_UNAVAILABLE; no command sent", outcome.message)
        assertEquals(0, transport.posts)
        source.close()
    }

    @Test fun freshnessOfflineFailureAndRecoveryNeverReplay() {
        var now = stamp; val scheduler = AudioScheduler(); val transport = AudioTransport()
        val source = NetworkPiAudioSource(identity, transport, { now }, scheduler)
        scheduler.poll(); source.submit(next); now += 3_000; scheduler.poll()
        assertEquals(0, transport.posts); assertEquals(AudioCommandStatus.DROPPED, source.outcomes.value!!.status)
        assertFalse(source.available(next))
        now = stamp; scheduler.poll(); source.submit(next); transport.fail = true; scheduler.poll()
        assertEquals(AudioCommandStatus.FAILED, source.outcomes.value!!.status)
        assertFalse(source.available(next)); transport.fail = false; scheduler.poll()
        assertTrue(source.available(next)); assertEquals(1, transport.posts)
        source.close()
    }

    @Test fun oldAcknowledgementCannotChangeValuesOrVerifyNewCommand() {
        val scheduler = AudioScheduler(); val transport = AudioTransport()
        val source = NetworkPiAudioSource(identity, transport, { stamp }, scheduler)
        scheduler.poll(); source.submit(next)
        transport.onPost = { transport.ack(it) { ack -> ack.put("requestId", "audio-client:999") } }
        scheduler.poll()
        assertEquals(AudioCommandStatus.FAILED, source.outcomes.value!!.status)
        assertEquals("Tidal Lines", source.state.value.media.title)
        assertEquals(TelemetryStatus.INVALID, source.state.value.source.quality.status)
        scheduler.poll(); assertTrue(source.available(next)); assertEquals(1, transport.posts)
        source.close()
    }

    @Test fun backwardsRevisionInvalidatesButNewServerSessionCanRecover() {
        val scheduler = AudioScheduler(); val transport = AudioTransport()
        transport.body = JSONObject(transport.body).put("revision", 10).toString()
        val source = NetworkPiAudioSource(identity, transport, { stamp }, scheduler)
        scheduler.poll(); val clientRevision = source.state.value.revision
        transport.body = JSONObject(transport.body).put("revision", 0).toString(); scheduler.poll()
        assertEquals(TelemetryStatus.INVALID, source.state.value.source.quality.status)
        transport.body = JSONObject(transport.body).put("serverSessionId", "restarted-server").toString(); scheduler.poll()
        assertTrue(source.available(next)); assertTrue(source.state.value.revision > clientRevision)
        source.close()
    }

    @Test fun newerEnvelopeCannotRefreshRegressedComponentObservation() {
        var now = stamp
        val scheduler = AudioScheduler(); val transport = AudioTransport()
        val source = NetworkPiAudioSource(identity, transport, { now }, scheduler)
        scheduler.poll()
        now += 1000
        val regressed = JSONObject(transport.body).put("timestampEpochMs", now)
        regressed.getJSONObject("dsp").getJSONObject("quality").put("timestampEpochMs", stamp - 1)
        transport.body = regressed.toString(); scheduler.poll()
        assertEquals(TelemetryStatus.INVALID, source.state.value.dsp.quality.status)
        assertFalse(source.available(AudioCommand.Fader(AudioChannel.MASTER, -30.0)))
        regressed.getJSONObject("dsp").getJSONObject("quality").put("timestampEpochMs", stamp)
        transport.body = regressed.toString(); scheduler.poll()
        assertTrue(source.available(AudioCommand.Fader(AudioChannel.MASTER, -30.0)))
        source.close()
    }

    @Test fun timeoutAndCloseDiscardInFlightAcknowledgementWithoutReplay() {
        var now = stamp; val scheduler = AudioScheduler(); val transport = AudioTransport()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        transport.onPost = { input -> entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); transport.ack(input) }
        val source = NetworkPiAudioSource(identity, transport, { now }, scheduler)
        scheduler.poll(); source.submit(next)
        val worker = thread { scheduler.poll() }; assertTrue(entered.await(1, TimeUnit.SECONDS))
        now += 5_001; source.tick()
        assertEquals(AudioCommandStatus.FAILED, source.outcomes.value!!.status)
        source.close(); source.close(); release.countDown(); worker.join(2_000)
        assertFalse(worker.isAlive)
        assertEquals("Tidal Lines", source.state.value.media.title)
        scheduler.poll(); assertEquals(1, transport.posts)
    }

    @Test fun closingPendingThenSwitchingCreatesIndependentSessionAndNoOldUpdates() {
        val oldTimer = AudioScheduler(); val oldTransport = AudioTransport()
        val old = NetworkPiAudioSource(identity, oldTransport, { stamp }, oldTimer)
        oldTimer.poll(); old.submit(next); old.close(); oldTimer.poll()
        assertEquals(AudioCommandStatus.DROPPED, old.outcomes.value!!.status); assertEquals(0, oldTransport.posts)
        val newTimer = AudioScheduler(); val newTransport = AudioTransport()
        val fresh = NetworkPiAudioSource(identity.copy(sessionId = "new-session"), newTransport, { stamp }, newTimer)
        newTimer.poll(); val pending = fresh.submit(next)
        assertTrue(pending.requestId!!.startsWith("new-session:")); newTimer.poll()
        assertEquals(AudioCommandStatus.VERIFIED, fresh.outcomes.value!!.status)
        fresh.close()
    }

    @Test fun capabilityAndMasterAuthorityRemainGatewayControlled() {
        val scheduler = AudioScheduler(); val transport = AudioTransport()
        val root = JSONObject(transport.body).put("authority", "NONE")
        root.getJSONObject("capabilities").put("nextPrevious", false)
        transport.body = root.toString()
        val source = NetworkPiAudioSource(identity, transport, { stamp }, scheduler)
        scheduler.poll()
        assertFalse(source.available(next))
        assertFalse(source.available(AudioCommand.Fader(AudioChannel.MASTER, -30.0)))
        assertTrue(source.available(AudioCommand.Transport(TransportAction.PAUSE)))
        source.close()
    }
    @Test fun lostSpotifySourceDoesNotDisableLiveCamillaFader() {
        val scheduler=AudioScheduler();val transport=AudioTransport();val root=JSONObject(transport.body)
        root.getJSONObject("audioSource").put("connected",false).put("active",true)
            .getJSONObject("quality").put("status","LOST")
        root.getJSONObject("capabilities").put("playPause",false).put("nextPrevious",false).put("seek",false)
        transport.body=root.toString()
        val source=NetworkPiAudioSource(identity,transport,{stamp},scheduler)
        scheduler.poll()
        assertFalse(source.available(AudioCommand.Transport(TransportAction.PLAY)))
        assertTrue(source.available(AudioCommand.Fader(AudioChannel.MASTER,-30.0)))
        source.close()
    }
    @Test fun absentOrReadOnlyCatalogNeverAuthorizesLegacyDspWrites() {
        val scheduler=AudioScheduler();val transport=AudioTransport();val root=JSONObject(transport.body)
        root.getJSONObject("dsp").let { dsp ->
            dsp.remove("faderControls")
            dsp.remove("presets")
        }
        transport.body=root.toString()
        val source=NetworkPiAudioSource(identity,transport,{stamp},scheduler)
        scheduler.poll()
        assertFalse(source.available(AudioCommand.Fader(AudioChannel.MASTER,-30.0)))
        assertFalse(source.available(AudioCommand.Mute(AudioChannel.MASTER,true)))
        assertFalse(source.available(AudioCommand.Preset("sim-stereo-4-1")))
        assertTrue(source.available(next))
        source.close()
    }
}
