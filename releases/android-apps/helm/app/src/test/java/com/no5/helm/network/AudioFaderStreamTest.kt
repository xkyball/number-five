package com.no5.helm.network

import com.no5.domain.*
import com.no5.helm.AudioNativeController
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

private class FaderScheduler : PiPollScheduler {
    val jobs = mutableMapOf<Long, () -> Unit>()
    override fun schedule(intervalMs: Long, poll: () -> Unit): SourceSubscription {
        jobs[intervalMs] = poll
        return SourceSubscription { jobs.remove(intervalMs) }
    }
}
private class FaderSocket : AudioFaderStreamTransport {
    lateinit var listener: AudioFaderStreamTransport.Listener
    val sent = mutableListOf<String>()
    var connections = 0
    var closed = false
    override fun connect(listener: AudioFaderStreamTransport.Listener): SourceSubscription {
        connections++; this.listener = listener
        return SourceSubscription {}
    }
    override fun send(value: String): Boolean { sent += value; return true }
    override fun close() { closed = true }
}
class AudioFaderStreamTest {
    @Test fun aThrowingStreamCloseStillRetiresBothPollersAndTheHttpWorker() {
        val scheduler = FaderScheduler(); var httpClosed = false
        val http = object : PiHttpTransport {
            override fun get(path: String) = error("No request in this lifecycle test")
            override fun close() { httpClosed = true }
        }
        val stream = object : AudioFaderStreamTransport by FaderSocket() {
            override fun close() { throw IllegalStateException("retired socket failure") }
        }
        val source = NetworkPiAudioSource(SourceIdentity("retire", SourceMode.PI, "TEST"), http,
            scheduler = scheduler, streamTransport = stream)
        assertEquals(2, scheduler.jobs.size)
        assertFailsWith<IllegalStateException> { source.close() }
        assertTrue(httpClosed); assertTrue(scheduler.jobs.isEmpty())
        source.close()
    }

    private val root = JSONObject(wireFixture("audio"))
    private var clock = root.getLong("timestampEpochMs")
    private val scheduler = FaderScheduler()
    private val socket = FaderSocket()
    private var posts = 0
    private val http = object : PiHttpTransport {
        override fun get(path: String) = root.toString()
        override fun post(path: String, body: String): String { posts++; error("Faders must use WSS") }
        override fun close() {}
    }
    private fun source(): NetworkPiAudioSource {
        val source = NetworkPiAudioSource(SourceIdentity("stream-test", SourceMode.PI, "AUDIO TEST"), http,
            { clock }, scheduler, streamTransport = socket)
        scheduler.jobs.getValue(20)()
        socket.listener.text(root.toString())
        return source
    }
    private fun acknowledge(index: Int = socket.sent.lastIndex, db: Double? = null) {
        val request = JSONObject(socket.sent[index])
        val command = request.getJSONObject("command")
        val channel = command.getString("channel")
        val value = db ?: command.getDouble("valueDb")
        root.put("revision", request.getLong("expectedRevision") + 1).put("timestampEpochMs", clock)
        root.getJSONObject("dsp").getJSONObject("faders").getJSONObject(channel).put("db", value)
        val controls = root.getJSONObject("dsp").getJSONArray("faderControls")
        for (i in 0 until controls.length()) if (controls.getJSONObject(i).getString("id") == channel) controls.getJSONObject(i).put("db", value)
        socket.listener.text(JSONObject().put("version", 1).put("kind", "audio-ack").put("source", root.getString("source"))
            .put("sourceKind", root.getString("sourceKind")).put("simulated", root.getBoolean("simulated"))
            .put("sessionId", request.getString("sessionId")).put("requestId", request.getString("requestId"))
            .put("serverSessionId", root.getString("serverSessionId")).put("revision", root.getLong("revision"))
            .put("status", "VERIFIED").put("message", "Camilla readback verified").put("state", root).toString())
    }
    @Test fun drag_sends_before_release_coalesces_burst_and_confirms_final_value() {
        val source = source()
        val controller = AudioNativeController(Dispatchers.Unconfined, now = { clock }, automaticTicker = false, source = source)
        val master = controller.masterControl()!!
        controller.previewLevel(master, -25.0); scheduler.jobs.getValue(20)()
        assertEquals(1, socket.sent.size); assertEquals(0, posts)
        assertNull(source.state.value.pending)
        assertEquals(-24.0, source.state.value.dsp.faderControls[0].db)
        for (db in -26 downTo -40) controller.previewLevel(master, db.toDouble())
        scheduler.jobs.getValue(20)(); assertEquals(1, socket.sent.size)
        clock += 40; acknowledge(); scheduler.jobs.getValue(20)()
        assertEquals(2, socket.sent.size)
        assertEquals(-40.0, JSONObject(socket.sent[1]).getJSONObject("command").getDouble("valueDb"))
        controller.previewLevel(master, -41.0); controller.commitPreview()
        assertNotNull(source.state.value.pending)
        clock += 40; acknowledge(); scheduler.jobs.getValue(20)()
        assertEquals(3, socket.sent.size)
        clock += 10; acknowledge()
        assertNull(source.state.value.pending)
        assertEquals(AudioCommandStatus.VERIFIED, controller.lastOutcome!!.status)
        assertEquals(-41.0, controller.lastOutcome!!.confirmedValueDb)
        controller.dispose(); assertTrue(socket.closed && scheduler.jobs.isEmpty())
    }
    @Test fun release_after_last_intermediate_ack_still_confirms_and_releases_gesture() {
        val source = source(); val controller = AudioNativeController(Dispatchers.Unconfined, now = { clock }, automaticTicker = false, source = source)
        controller.previewLevel(controller.masterControl()!!, -25.0); scheduler.jobs.getValue(20)(); acknowledge()
        assertTrue(source.faderStream!!.active); assertNull(source.state.value.pending)
        clock += 100; controller.commitPreview(); assertFalse(source.faderStream!!.active); assertNotNull(source.state.value.pending)
        scheduler.jobs.getValue(20)(); assertEquals(2, socket.sent.size); acknowledge()
        assertEquals(AudioCommandStatus.VERIFIED, controller.lastOutcome!!.status); assertNull(source.state.value.pending)
        assertTrue(source.available(AudioCommand.Fader(AudioChannel.MASTER, -24.0))); controller.dispose()
    }
    @Test fun live_slider_stays_admitted_across_ack_while_other_commands_stay_locked() {
        val source = source()
        val controller = AudioNativeController(Dispatchers.Unconfined, now = { clock }, automaticTicker = false, source = source)
        assertTrue(controller.canDrag(controller.masterControl()!!))
        assertTrue(controller.canDrag(controller.faderControl(AudioChannel.SUB)!!))
        controller.previewLevel(controller.masterControl()!!, -25.0)
        scheduler.jobs.getValue(20)()
        assertFalse(controller.canAdjust(controller.masterControl()!!))
        assertTrue(controller.canDrag(controller.masterControl()!!))
        assertFalse(controller.canDrag(controller.faderControl(AudioChannel.SUB)!!))
        clock += 40; acknowledge()
        assertTrue(controller.canDrag(controller.masterControl()!!))
        controller.previewLevel(controller.masterControl()!!, -30.0)
        scheduler.jobs.getValue(20)()
        assertEquals(-30.0, JSONObject(socket.sent.last()).getJSONObject("command").getDouble("valueDb"))
        controller.commitPreview()
        assertFalse(controller.canDrag(controller.masterControl()!!))
        clock += 40; acknowledge(); scheduler.jobs.getValue(20)(); acknowledge()
        assertTrue(controller.canDrag(controller.masterControl()!!))
        socket.listener.failure()
        assertFalse(controller.canDrag(controller.masterControl()!!))
        assertEquals(0, posts)
        controller.dispose()
    }
    @Test fun disconnect_drops_gesture_without_http_fallback_or_replay() {
        val source = source()
        val controller = AudioNativeController(Dispatchers.Unconfined, now = { clock }, automaticTicker = false, source = source)
        controller.previewLevel(controller.masterControl()!!, -25.0); scheduler.jobs.getValue(20)()
        controller.previewLevel(controller.masterControl()!!, -40.0)
        socket.listener.failure(); controller.commitPreview()
        assertEquals(0, posts); assertNull(source.state.value.pending)
        clock += 2_000; scheduler.jobs.getValue(20)(); socket.listener.text(root.put("timestampEpochMs", clock).toString())
        scheduler.jobs.getValue(20)(); assertEquals(1, socket.sent.size)
        controller.dispose()
    }
    @Test fun late_old_connection_callback_cannot_enable_or_drop_new_stream() {
        val source = source(); val old = socket.listener
        old.failure(); clock += 2_000; scheduler.jobs.getValue(20)()
        old.text(root.put("timestampEpochMs", clock).toString())
        assertFalse(source.faderStream!!.available(AudioChannel.MASTER, -25.0))
        socket.listener.text(root.toString()); assertTrue(source.faderStream!!.available(AudioChannel.MASTER, -25.0))
        old.failure(); assertTrue(source.faderStream!!.available(AudioChannel.MASTER, -25.0))
        source.close()
    }
    @Test fun wrong_readback_rejects_end_value_and_cancels_queued_targets() {
        val source = source()
        val stream = source.faderStream!!
        assertTrue(stream.preview(AudioChannel.MASTER, -25.0)); stream.finish(); scheduler.jobs.getValue(20)()
        acknowledge(db = -24.0)
        assertEquals(AudioCommandStatus.REJECTED, source.outcomes.value!!.status)
        assertNull(source.state.value.pending); assertFalse(stream.active)
        clock += 40; scheduler.jobs.getValue(20)(); assertEquals(1, socket.sent.size)
        source.close()
    }
    @Test fun knob_semantic_fader_write_uses_fast_stream_and_keeps_readback_authority() {
        val source = source()
        val before = source.state.value.dsp.faderControls[0].db
        assertTrue(com.no5.helm.input.KnobAudioCommands(source).perform(com.no5.helm.input.KnobAction.MASTER_UP))
        assertEquals(before, source.state.value.dsp.faderControls[0].db)
        scheduler.jobs.getValue(20)(); assertEquals(1, socket.sent.size); assertEquals(0, posts)
        acknowledge(); assertEquals(before!! + 1.0, source.state.value.dsp.faderControls[0].db)
        assertEquals(AudioCommandStatus.VERIFIED, source.outcomes.value!!.status)
        source.close()
    }
}
