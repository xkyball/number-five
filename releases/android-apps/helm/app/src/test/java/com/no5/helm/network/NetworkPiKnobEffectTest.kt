package com.no5.helm.network

import com.no5.domain.*
import com.no5.helm.control.HelmAudioEffectContext
import com.no5.helm.control.HelmAudioEffectLease
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Actual unchanged encoder/decoder + changed original queue, with explicit fake HTTP only. */
class NetworkPiKnobEffectTest {
    private class Scheduler : PiPollScheduler {
        lateinit var poll: () -> Unit
        var stream: (() -> Unit)? = null
        override fun schedule(intervalMs: Long, poll: () -> Unit): SourceSubscription {
            if (intervalMs == 20L) stream = poll else this.poll = poll
            return SourceSubscription {}
        }
    }
    private class Transport : PiHttpTransport {
        val fixture = wireFixture("audio")
        var posts = 0
        var onGet: (() -> Unit)? = null
        var onPost: (() -> Unit)? = null
        override fun get(path: String): String { onGet?.invoke(); return fixture }
        override fun post(path: String, body: String): String {
            posts++; onPost?.invoke()
            val input = JSONObject(body)
            val state = JSONObject(fixture).put("revision", input.getLong("expectedRevision") + 1)
            return JSONObject().put("version", 1).put("kind", "audio-command-result").put("commandId","fixture-command")
                .put("source", state.getString("source")).put("sourceKind", state.getString("sourceKind"))
                .put("simulated", state.getBoolean("simulated")).put("sessionId", input.getString("sessionId"))
                .put("requestId", input.getString("requestId")).put("serverSessionId", state.getString("serverSessionId"))
                .put("revision", state.getLong("revision")).put("status", "APPLIED").put("message", "Fixture ACK")
                .put("state", state).toString()
        }
        override fun close() = Unit
    }
    private class Stream : AudioFaderStreamTransport {
        lateinit var listener: AudioFaderStreamTransport.Listener
        var writes = 0
        val sent = mutableListOf<String>()
        override fun connect(listener: AudioFaderStreamTransport.Listener): SourceSubscription {
            this.listener = listener; return SourceSubscription {}
        }
        override fun send(value: String): Boolean { writes++; sent += value; return true }
        override fun close() = Unit
    }
    private class Fixture(withStream: Boolean = false, encoder: ((String, String, String, Long, AudioCommand) -> String)? = null) : AutoCloseable {
        val scheduler = Scheduler(); val transport = Transport()
        val uptime = AtomicLong(100); val scope = AtomicBoolean(true); val owner = AtomicBoolean(true)
        val wall = AtomicLong(JSONObject(transport.fixture).getLong("timestampEpochMs"))
        val stream = if (withStream) Stream() else null
        val identity = SourceIdentity("knob-queue-fixture", SourceMode.PI, "TEST ONLY")
        val source = NetworkPiAudioSource(identity, transport,
            wall::get, scheduler, streamTransport = stream,
            encodeCommand = encoder ?: AudioV1Decoder::command)
        val effect = HelmAudioEffectLease(350, 1, identity.sessionId, 100, uptime::get, scope::get)
        val next = AudioCommand.Transport(TransportAction.NEXT)
        init {
            if (stream != null) { scheduler.stream!!.invoke(); stream.listener.text(transport.fixture) }
            else scheduler.poll()
            assertTrue(effect.bindOwner(1, identity.sessionId, owner::get))
        }
        fun queue() = HelmAudioEffectContext.withLease(effect) { source.submit(next) }
        fun fader() = HelmAudioEffectContext.withLease(effect) { source.submit(AudioCommand.Fader(AudioChannel.MASTER, -25.0)) }
        fun ack(db: Double = -25.0, wrongId: Boolean = false) {
            val request = JSONObject(stream!!.sent.last())
            val state = JSONObject(transport.fixture).put("revision", request.getLong("expectedRevision") + 1)
            state.getJSONObject("dsp").getJSONObject("faders").getJSONObject("MASTER").put("db", db)
            val controls = state.getJSONObject("dsp").getJSONArray("faderControls")
            for (i in 0 until controls.length()) if (controls.getJSONObject(i).getString("id") == "MASTER") controls.getJSONObject(i).put("db", db)
            stream.listener.text(JSONObject().put("version", 1).put("kind", "audio-ack")
                .put("source", state.getString("source")).put("sourceKind", state.getString("sourceKind"))
                .put("simulated", state.getBoolean("simulated")).put("sessionId", request.getString("sessionId"))
                .put("requestId", if (wrongId) "retired:1" else request.getString("requestId"))
                .put("serverSessionId", state.getString("serverSessionId")).put("revision", state.getLong("revision"))
                .put("status", "VERIFIED").put("message", "Fixture Camilla readback").put("state", state).toString())
        }
        override fun close() = source.close()
    }
    @Test fun exactOriginalRootExpiryDropsActualQueueWithFreshGatewayData() {
        Fixture().use { f ->
            assertEquals(AudioCommandStatus.PENDING, f.queue().status)
            f.uptime.set(350); f.scheduler.poll()
            assertEquals(0, f.transport.posts); assertEquals(HelmAudioEffectLease.State.LOCAL_DENIED, f.effect.state)
            assertEquals(AudioCommandStatus.DROPPED, f.source.outcomes.value!!.status)
        }
    }
    @Test fun focusOrBorrowedOwnerRetirementDropsQueueWithoutReplay() {
        Fixture().use { f -> f.queue(); f.scope.set(false); f.scheduler.poll(); assertEquals(0, f.transport.posts) }
        Fixture().use { f -> f.queue(); f.owner.set(false); f.scheduler.poll(); assertEquals(0, f.transport.posts) }
    }
    @Test fun blockedOriginalEncodingCannotRenewTheSendLease() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture { session, id, server, revision, command ->
            val body = AudioV1Decoder.command(session, id, server, revision, command)
            entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); body
        }.use { f ->
            f.queue(); val worker = thread { f.scheduler.poll() }
            try { assertTrue(entered.await(2, TimeUnit.SECONDS)); f.uptime.set(350) }
            finally { release.countDown(); worker.join(2_000) }
            assertFalse(worker.isAlive); assertEquals(0, f.transport.posts)
            assertEquals(HelmAudioEffectLease.State.LOCAL_DENIED, f.effect.state)
        }
    }
    @Test fun cancelAfterPostEntryRemainsUnknownAndNeverReplays() {
        Fixture().use { f ->
            f.queue(); f.transport.onPost = { f.effect.cancel(); throw IOException("Completion unknown") }
            f.scheduler.poll(); f.scheduler.poll()
            assertEquals(1, f.transport.posts); assertEquals(HelmAudioEffectLease.State.COMPLETION_UNKNOWN, f.effect.state)
        }
    }
    @Test fun ordinaryUiRetainsOriginalPostAndRootLeaseIsNotLeaked() {
        Fixture().use { f ->
            assertNull(HelmAudioEffectContext.current())
            assertEquals(AudioCommandStatus.PENDING, f.source.submit(f.next).status)
            f.scheduler.poll(); assertEquals(1, f.transport.posts)
            assertEquals(AudioCommandStatus.VERIFIED, f.source.outcomes.value!!.status)
            assertEquals(HelmAudioEffectLease.State.CREATED, f.effect.state)
        }
    }
    @Test fun queuedRootEffectWakesActualOriginalWorkerBeforeThe250msPeriodicSlot() {
        val transport = Transport(); val ready = CountDownLatch(1); val posted = CountDownLatch(1)
        val origin = System.nanoTime(); val uptime = { 100L + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - origin) }
        val identity = SourceIdentity("real-worker-fixture", SourceMode.PI, "FAKE HTTP ONLY")
        val threads = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        transport.onGet = { threads.add(Thread.currentThread().id) }
        transport.onPost = { threads.add(Thread.currentThread().id); posted.countDown() }
        val source = NetworkPiAudioSource(identity, transport,
            { JSONObject(transport.fixture).getLong("timestampEpochMs") }, BackgroundPiPollScheduler("knob-wake-test"))
        val command = AudioCommand.Transport(TransportAction.NEXT)
        // Feed callbacks use their delivered snapshot; check the adapter gate outside its feed monitor.
        val observation = source.state.subscribe { snapshot -> if (AudioModel.available(snapshot, command)) ready.countDown() }
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            assertTrue(source.available(command))
            val captured = uptime(); val effect = HelmAudioEffectLease(captured + 250, 1, identity.sessionId, captured, uptime, { true })
            assertTrue(effect.bindOwner(1, identity.sessionId) { true })
            val submittedAt = System.nanoTime()
            assertTrue(HelmAudioEffectContext.withLease(effect) { source.submit(command) }.accepted)
            assertTrue("Wake must send before original Root expiry", posted.await(240, TimeUnit.MILLISECONDS))
            assertTrue(System.nanoTime() - submittedAt < TimeUnit.MILLISECONDS.toNanos(250))
            assertEquals(1, transport.posts); assertEquals(1, threads.size)
        } finally { observation.close(); source.close() }
    }

    @Test fun blockedExistingWorkerDropsExpiredWakeInsteadOfPostingOrReplaying() {
        val transport = Transport(); val ready = CountDownLatch(1); val blocked = CountDownLatch(1)
        val release = CountDownLatch(1); val dropped = CountDownLatch(1)
        val gets = java.util.concurrent.atomic.AtomicInteger(); val uptime = AtomicLong(100)
        transport.onGet = {
            if (gets.incrementAndGet() == 2) { blocked.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
        }
        val identity = SourceIdentity("blocked-worker-fixture", SourceMode.PI, "FAKE HTTP ONLY")
        val source = NetworkPiAudioSource(identity, transport,
            { JSONObject(transport.fixture).getLong("timestampEpochMs") }, BackgroundPiPollScheduler("blocked-knob-wake"))
        val command = AudioCommand.Transport(TransportAction.NEXT)
        // Avoid re-entering the adapter from its feed callback; retain the real gate check below.
        val state = source.state.subscribe { snapshot -> if (AudioModel.available(snapshot, command)) ready.countDown() }
        val outcomes = source.outcomes.subscribe { if (it?.status == AudioCommandStatus.DROPPED) dropped.countDown() }
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            assertTrue(source.available(command))
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            val effect = HelmAudioEffectLease(350, 1, identity.sessionId, 100, uptime::get, { true })
            assertTrue(effect.bindOwner(1, identity.sessionId) { true })
            assertTrue(HelmAudioEffectContext.withLease(effect) { source.submit(command) }.accepted)
            uptime.set(350); release.countDown(); assertTrue(dropped.await(2, TimeUnit.SECONDS))
            assertEquals(0, transport.posts); assertEquals(HelmAudioEffectLease.State.LOCAL_DENIED, effect.state)
        } finally { release.countDown(); outcomes.close(); state.close(); source.close() }
    }

    @Test fun explicitWakeRejectionDropsOnlyQueuedRootCommandAndOrdinaryUiDoesNotWake() {
        var wakes = 0
        lateinit var poll: () -> Unit
        val scheduler = PiPollScheduler { _, task ->
            poll = task
            object : WakeablePollSubscription {
                override fun requestNow(): Boolean { wakes++; return false }
                override fun close() = Unit
            }
        }
        val transport = Transport(); val identity = SourceIdentity("rejected-wake-fixture", SourceMode.PI, "FAKE")
        val source = NetworkPiAudioSource(identity, transport,
            { JSONObject(transport.fixture).getLong("timestampEpochMs") }, scheduler)
        try {
            poll()
            val effect = HelmAudioEffectLease(350, 1, identity.sessionId, 100, { 100 }, { true })
            assertTrue(effect.bindOwner(1, identity.sessionId) { true })
            val command = AudioCommand.Transport(TransportAction.NEXT)
            assertEquals(AudioCommandStatus.DROPPED, HelmAudioEffectContext.withLease(effect) { source.submit(command) }.status)
            assertEquals(1, wakes); poll(); assertEquals(0, transport.posts)
            assertTrue(source.submit(command).accepted); assertEquals(1, wakes)
            poll(); assertEquals(1, transport.posts)
        } finally { source.close() }
    }
    @Test fun rootMasterAndOrdinaryUiUseStreamWithoutHttpFallback() {
        Fixture(withStream = true).use { f ->
            val control = f.source.state.value.dsp.faderControls.single { it.gatewayId == AudioChannel.MASTER }
            val command = AudioCommand.Fader(AudioChannel.MASTER, control.db!! - 1.0)
            assertTrue(HelmAudioEffectContext.withLease(f.effect) { f.source.submit(command) }.accepted)
            assertEquals(HelmAudioEffectLease.State.QUEUED, f.effect.state)
            assertEquals(0, f.stream!!.writes); f.scheduler.stream!!.invoke()
            assertEquals(1, f.stream.writes); assertEquals(0, f.transport.posts)
            assertEquals(HelmAudioEffectLease.State.SEND_STARTED, f.effect.state)
            f.ack(); assertEquals(HelmAudioEffectLease.State.VERIFIED_RECEIPT, f.effect.state)
            assertEquals(AudioCommandStatus.VERIFIED, f.source.outcomes.value!!.status)
        }
        Fixture(withStream = true).use { f ->
            val control = f.source.state.value.dsp.faderControls.single { it.gatewayId == AudioChannel.MASTER }
            assertTrue(f.source.submit(AudioCommand.Fader(AudioChannel.MASTER, control.db!! - 1.0)).accepted)
            // Ordinary UI finish() queues the end value; the original 20ms pump sends it.
            assertEquals(0, f.stream!!.writes); assertEquals(0, f.transport.posts)
            f.scheduler.stream!!.invoke()
            assertEquals(1, f.stream.writes); assertEquals(0, f.transport.posts)
        }
    }
    @Test fun streamedKnobRetirementExpiryAndClockRollbackPreventSend() {
        for (retire in listOf<(Fixture) -> Unit>({ it.scope.set(false) }, { it.owner.set(false) },
                { it.effect.cancel() }, { it.uptime.set(350) }, { it.uptime.set(99) })) {
            Fixture(withStream = true).use { f ->
                assertTrue(f.fader().accepted); retire(f); f.scheduler.stream!!.invoke(); f.scheduler.poll()
                assertEquals(0, f.stream!!.writes); assertEquals(0, f.transport.posts)
                assertEquals(HelmAudioEffectLease.State.LOCAL_DENIED, f.effect.state)
                assertEquals(AudioCommandStatus.DROPPED, f.source.outcomes.value!!.status)
            }
        }
    }
    @Test fun blockedStreamEncodingCannotRenewTheOriginalLease() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture(withStream = true, encoder = { session, id, server, revision, command ->
            val body = AudioV1Decoder.command(session, id, server, revision, command)
            entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); body
        }).use { f ->
            assertTrue(f.fader().accepted); val worker = thread { f.scheduler.stream!!.invoke() }
            try { assertTrue(entered.await(2, TimeUnit.SECONDS)); f.uptime.set(350) }
            finally { release.countDown(); worker.join(2_000) }
            assertFalse(worker.isAlive); assertEquals(0, f.stream!!.writes); assertEquals(0, f.transport.posts)
            assertEquals(HelmAudioEffectLease.State.LOCAL_DENIED, f.effect.state)
        }
    }
    @Test fun lostOrWrongReplyDoesNotReplayStreamedKnobOverHttp() {
        for (wrongId in listOf(false, true)) Fixture(withStream = true).use { f ->
            assertTrue(f.fader().accepted); f.scheduler.stream!!.invoke()
            if (wrongId) f.ack(wrongId = true) else f.stream!!.listener.failure()
            assertEquals(HelmAudioEffectLease.State.COMPLETION_UNKNOWN, f.effect.state)
            assertEquals(AudioCommandStatus.DROPPED, f.source.outcomes.value!!.status)
            f.scheduler.poll(); f.scheduler.stream!!.invoke()
            assertEquals(1, f.stream!!.writes); assertEquals(0, f.transport.posts)
            assertFalse(f.fader().accepted)
        }
    }
    @Test fun wrongFaderReadbackCannotVerifyKnobLease() {
        Fixture(withStream = true).use { f ->
            f.fader(); f.scheduler.stream!!.invoke(); f.ack(db = -24.0)
            assertEquals(AudioCommandStatus.REJECTED, f.source.outcomes.value!!.status)
            assertEquals(HelmAudioEffectLease.State.COMPLETION_UNKNOWN, f.effect.state)
            assertEquals(0, f.transport.posts)
        }
    }
    @Test fun sourceReplacementBeforeStreamSendDropsKnobAndDoesNotRebaseItsSession() {
        Fixture(withStream = true).use { f ->
            f.fader()
            f.stream!!.listener.text(JSONObject(f.transport.fixture).put("serverSessionId", "replacement-server").toString())
            f.scheduler.stream!!.invoke(); assertEquals(0, f.stream.writes); assertEquals(0, f.transport.posts)
            assertEquals(HelmAudioEffectLease.State.LOCAL_DENIED, f.effect.state)
        }
    }
    @Test fun streamedKnobKeepsOriginalGatewayRevisionAndRequiresBoundOwner() {
        Fixture(withStream = true).use { f ->
            val originalRevision = JSONObject(f.transport.fixture).getLong("revision")
            f.fader()
            f.stream!!.listener.text(JSONObject(f.transport.fixture).put("revision", originalRevision + 10).toString())
            f.scheduler.stream!!.invoke()
            assertEquals(originalRevision, JSONObject(f.stream.sent.single()).getLong("expectedRevision"))
        }
        Fixture(withStream = true).use { f ->
            val unbound = HelmAudioEffectLease(350, 1, f.identity.sessionId, 100, f.uptime::get, f.scope::get)
            val command = AudioCommand.Fader(AudioChannel.MASTER, -25.0)
            assertEquals(AudioCommandStatus.REJECTED, HelmAudioEffectContext.withLease(unbound) { f.source.submit(command) }.status)
            f.scheduler.stream!!.invoke(); assertEquals(0, f.stream!!.writes); assertEquals(0, f.transport.posts)
        }
    }
    @Test fun timeoutRetiresStreamedKnobAndLateAckCannotConfirmOrReplay() {
        Fixture(withStream = true).use { f ->
            f.fader(); f.scheduler.stream!!.invoke(); val old = f.stream!!.listener
            f.wall.addAndGet(1_500); f.scheduler.stream!!.invoke()
            assertEquals(AudioCommandStatus.DROPPED, f.source.outcomes.value!!.status)
            assertEquals(HelmAudioEffectLease.State.COMPLETION_UNKNOWN, f.effect.state)
            f.ack(); assertEquals(AudioCommandStatus.DROPPED, f.source.outcomes.value!!.status)
            f.wall.addAndGet(2_000); f.scheduler.stream!!.invoke()
            old.text(f.transport.fixture); f.scheduler.stream!!.invoke(); f.scheduler.poll()
            assertEquals(1, f.stream.writes); assertEquals(0, f.transport.posts)
        }
    }
    @Test fun missingOwnerBindingCannotQueueAndClockRollbackCannotSend() {
        Fixture().use { f ->
            val unbound = HelmAudioEffectLease(350, 1, f.identity.sessionId, 100, f.uptime::get, f.scope::get)
            assertEquals(AudioCommandStatus.REJECTED, HelmAudioEffectContext.withLease(unbound) { f.source.submit(f.next) }.status)
            f.queue(); f.uptime.set(99); f.scheduler.poll(); assertEquals(0, f.transport.posts)
        }
    }
}
