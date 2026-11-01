package com.no5.helm.network

import com.no5.domain.*
import com.no5.helm.AudioNativeController
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

internal fun equalizerWire(gains: List<Double> = List(5) { 0.0 }): JSONObject {
    val root = JSONObject(wireFixture("audio"))
    root.getJSONObject("capabilities").put("equalizer", true)
    root.getJSONObject("dsp").put("equalizer", JSONObject().put("bankId", "master").put("configGeneration", "a".repeat(64))
        .put("frequenciesHz", JSONArray(AudioEqualizer.FREQUENCIES_HZ)).put("gainsDb", JSONArray(gains))
        .put("minDb", -6).put("maxDb", 6).put("stepDb", 1).put("headroomDb", 0).put("writable", true)
        .put("observedAtEpochMs", root.getLong("timestampEpochMs")).put("verification", "ACTIVE_CONFIG_READBACK"))
    return root
}

private class EqualizerScheduler : PiPollScheduler {
    val jobs = mutableMapOf<Long, () -> Unit>()
    override fun schedule(intervalMs: Long, poll: () -> Unit): SourceSubscription {
        jobs[intervalMs] = poll
        return SourceSubscription { jobs.remove(intervalMs) }
    }
    fun poll() = jobs.getValue(250)()
}
private class EqualizerSocket : AudioFaderStreamTransport {
    lateinit var listener: AudioFaderStreamTransport.Listener
    val sent = mutableListOf<String>()
    override fun connect(listener: AudioFaderStreamTransport.Listener): SourceSubscription { this.listener = listener; return SourceSubscription {} }
    override fun send(value: String): Boolean { sent += value; return true }
    override fun close() = Unit
}
private class EqualizerTransport : PiHttpTransport {
    val root = equalizerWire()
    val posts = mutableListOf<JSONObject>()
    var terminal = "APPLIED"
    var readback: List<Double>? = null
    private var request: JSONObject? = null
    override fun get(path: String): String {
        if (path == "/v1/audio") return root.toString()
        assertEquals("/v1/audio/commands/eq-${posts.size}", path)
        return receipt(requireNotNull(request), terminal).toString()
    }
    override fun post(path: String, body: String): String {
        assertEquals("/v1/audio/commands", path)
        return JSONObject(body).also { request = it; posts += it }.let { receipt(it, "ACCEPTED").toString() }
    }
    private fun receipt(request: JSONObject, status: String): JSONObject {
        val result = JSONObject().put("version", 1).put("kind", "audio-command-result").put("commandId", "eq-${posts.size}")
            .put("requestId", request.getString("requestId")).put("sessionId", request.getString("sessionId"))
            .put("serverSessionId", root.getString("serverSessionId")).put("status", status)
        if (status == "APPLIED") {
            val target = request.getJSONObject("command").getJSONArray("gainsDb")
            root.put("revision", request.getLong("expectedRevision") + 1)
            root.getJSONObject("dsp").getJSONObject("equalizer").put("gainsDb", readback?.let(::JSONArray) ?: target)
            result.put("state", root).put("revision", root.getLong("revision")).put("verification", "ACTIVE_CONFIG_READBACK")
        }
        return result
    }
    override fun close() = Unit
}

class AudioEqualizerTest {
    @Test fun legacyAndBrokenOptionalEqualizerNeverInventAControlOrDisableValidFaders() {
        val legacy = AudioV1Decoder.state(wireFixture("audio")).state
        assertFalse(legacy.capabilities.equalizer); assertNull(legacy.dsp.equalizer)
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("configGeneration", "bad") }, { it.put("gainsDb", JSONArray(listOf(0, 0, 0))) },
            { it.put("gainsDb", JSONArray(listOf(0, 0, 0, 0, 7))) }, { it.put("gainsDb", JSONArray(listOf(0, 0, 0, 0, 1.5))) },
            { it.put("frequenciesHz", JSONArray(listOf(1, 2, 3, 4, 5))) }, { it.remove("verification") },
            { it.put("verification", "SAMPLE_ACCURATE") }, { it.put("headroomDb", -1) }, { it.put("writable", "true") },
        )
        for (mutate in mutations) {
            val root = equalizerWire(); mutate(root.getJSONObject("dsp").getJSONObject("equalizer"))
            val decoded = AudioV1Decoder.state(root.toString()).state
            assertFalse(decoded.capabilities.equalizer); assertNull(decoded.dsp.equalizer)
            assertEquals(legacy.media, decoded.media); assertEquals(legacy.dsp.faderControls, decoded.dsp.faderControls)
        }
    }

    @Test fun bankGenerationStepAndOwnObservationTimeGateOnlyEqualizer() {
        val root = equalizerWire(); val stamp = root.getLong("timestampEpochMs")
        val state = AudioV1Decoder.state(root.toString()).state
        val eq = state.dsp.equalizer!!
        val command = AudioCommand.Equalizer(eq.bankId, eq.configGeneration, listOf(-6.0, -1.0, 0.0, 1.0, 6.0))
        assertTrue(AudioModel.available(state, command))
        assertFalse(AudioModel.available(state, command.copy(configGeneration = "b".repeat(64))))
        assertFalse(AudioModel.available(state, command.copy(gainsDb = listOf(0.0, 0.0, 0.0, .5, 0.0))))
        root.getJSONObject("dsp").getJSONObject("equalizer").put("observedAtEpochMs", stamp - 3000)
        val stale = AudioV1Decoder.state(root.toString()).state.withAudioFreshness(stamp)
        assertFalse(AudioModel.available(stale, command))
        assertTrue(AudioModel.available(stale, AudioCommand.Fader(AudioChannel.MASTER, -30.0)))
        val encoded = JSONObject(AudioV1Decoder.command("client", "client:1", "server", 4, command)).getJSONObject("command")
        assertEquals(setOf("type", "bankId", "configGeneration", "gainsDb"), encoded.keys().asSequence().toSet())
        assertEquals("equalizer", encoded.getString("type")); assertEquals(command.gainsDb, (0..4).map { encoded.getJSONArray("gainsDb").getDouble(it) })
    }

    @Test fun legacyWireCommandUsesHttp202AndReadbackWithoutFaderWss() {
        val scheduler = EqualizerScheduler(); val transport = EqualizerTransport(); val socket = EqualizerSocket()
        val stamp = transport.root.getLong("timestampEpochMs")
        val source = NetworkPiAudioSource(SourceIdentity("eq-wire", SourceMode.PI, "EQ TEST"), transport, { stamp }, scheduler, streamTransport = socket)
        scheduler.jobs.getValue(20)(); socket.listener.text(transport.root.toString())
        val controller = AudioNativeController(Dispatchers.Unconfined, { stamp }, false, false, source)
        try {
            assertEquals(List(5) { 0.0 }, controller.equalizer!!.gainsDb)
            assertTrue(controller.write(AudioCommand.Equalizer("master", "a".repeat(64), listOf(2.0,0.0,0.0,0.0,0.0))))
            assertTrue(transport.posts.isEmpty())
            scheduler.poll()
            assertEquals(1, transport.posts.size); assertTrue(socket.sent.isEmpty())
            assertEquals(AudioCommandStatus.PENDING, controller.lastOutcome!!.status)
            assertEquals(0.0, controller.equalizer!!.gainsDb[0])
            scheduler.poll()
            assertEquals(AudioCommandStatus.VERIFIED, controller.lastOutcome!!.status)
            assertEquals(2.0, controller.equalizer!!.gainsDb[0])
            assertEquals("ACTIVE_CONFIG_READBACK", controller.equalizer!!.verification)
        } finally { controller.dispose() }
    }

    @Test fun completeLatestFiveBandTargetSupersedesQueuedGoalsAndSurvivesAnOlderReadback() {
        val scheduler = EqualizerScheduler(); val transport = EqualizerTransport(); val stamp = transport.root.getLong("timestampEpochMs")
        val source = NetworkPiAudioSource(SourceIdentity("eq-latest", SourceMode.PI, "EQ TEST"), transport, { stamp }, scheduler)
        scheduler.poll()
        val controller = AudioNativeController(Dispatchers.Unconfined, { stamp }, false, false, source)
        try {
            assertTrue(controller.write(AudioCommand.Equalizer("master", "a".repeat(64), listOf(3.0,0.0,0.0,0.0,0.0))))
            val superseded = controller.controls.equalizer(AudioCommand.Equalizer("master", "a".repeat(64), listOf(3.0,2.0,0.0,0.0,0.0)))
            assertTrue(controller.write(AudioCommand.Equalizer("master", "a".repeat(64), listOf(-2.0,2.0,0.0,0.0,4.0))))
            val wanted = listOf(-2.0, 2.0, 0.0, 0.0, 4.0)
            assertEquals("SUPERSEDED", controller.controls.receiptState(superseded))
            scheduler.poll(); assertEquals(1, transport.posts.size)
            scheduler.poll() // The old readback is real, but cannot overwrite the newer local target.
            assertEquals(3.0, controller.equalizer!!.gainsDb[0])
            scheduler.poll(); assertEquals(2, transport.posts.size)
            val finalWire = transport.posts.last().getJSONObject("command").getJSONArray("gainsDb")
            assertEquals(wanted, (0..4).map(finalWire::getDouble))
            scheduler.poll()
            assertEquals(wanted, controller.equalizer!!.gainsDb)
            assertEquals(AudioCommandStatus.VERIFIED, controller.lastOutcome!!.status)
        } finally { controller.dispose() }
    }

    @Test fun unknownOrWrongReadbackNeverVerifiesOrReplaysTheCommand() {
        for (unknown in listOf(true, false)) {
            val scheduler = EqualizerScheduler(); val transport = EqualizerTransport(); val stamp = transport.root.getLong("timestampEpochMs")
            val source = NetworkPiAudioSource(SourceIdentity("eq-unclear-$unknown", SourceMode.PI, "EQ TEST"), transport, { stamp }, scheduler)
            scheduler.poll()
            val controller = AudioNativeController(Dispatchers.Unconfined, { stamp }, false, false, source)
            try {
                val command = controller.controls.equalizer(AudioCommand.Equalizer("master", "a".repeat(64), listOf(0.0,0.0,4.0,0.0,0.0)))
                assertTrue(controller.controls.accepted(command))
                scheduler.poll()
                if (unknown) transport.terminal = "UNKNOWN" else transport.readback = List(5) { 0.0 }
                scheduler.poll()
                assertNotEquals(AudioCommandStatus.VERIFIED, controller.lastOutcome!!.status)
                assertEquals("UNKNOWN", controller.controls.receiptState(command))
                repeat(3) { scheduler.poll() }
                assertEquals(1, transport.posts.size); assertEquals(0.0, controller.equalizer!!.gainsDb[2])
            } finally { controller.dispose() }
        }
    }

    @Test fun changedProfileGenerationBeforeTheEffectDropsTheOldTargetWithoutSending() {
        val scheduler = EqualizerScheduler(); val transport = EqualizerTransport(); val socket = EqualizerSocket()
        val stamp = transport.root.getLong("timestampEpochMs")
        val source = NetworkPiAudioSource(SourceIdentity("eq-profile", SourceMode.PI, "EQ TEST"), transport, { stamp }, scheduler, streamTransport = socket)
        scheduler.jobs.getValue(20)(); socket.listener.text(transport.root.toString())
        val controller = AudioNativeController(Dispatchers.Unconfined, { stamp }, false, false, source)
        try {
            assertTrue(controller.write(AudioCommand.Equalizer("master", "a".repeat(64), listOf(0.0,0.0,0.0,3.0,0.0))))
            transport.root.put("revision", transport.root.getLong("revision") + 1)
            transport.root.getJSONObject("dsp").getJSONObject("equalizer").put("configGeneration", "b".repeat(64))
            socket.listener.text(transport.root.toString())
            scheduler.poll()
            assertTrue(transport.posts.isEmpty()); assertTrue(socket.sent.isEmpty())
            assertNotEquals(AudioCommandStatus.VERIFIED, controller.lastOutcome!!.status)
            assertEquals("b".repeat(64), controller.equalizer!!.configGeneration)
        } finally { controller.dispose() }
    }
}
