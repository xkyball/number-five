package com.no5.helm.control

import com.no5.header.*
import org.lumina.header.consumer.v1.HeaderConsumerContract as Wire
import org.lumina.header.consumer.v1.HeaderFrameCodec
import org.lumina.header.consumer.v1.HeaderPeerAdmission
import org.lumina.header.consumer.v1.HeaderReadOnlyPublisher
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Deterministic clock/peer/queue fixtures. No Android kernel/PM/SELinux/runtime admission proof. */
class HelmHeaderConsumerExporterTest {
    private val rootSession = "0123456789abcdef0123456789abcdef"
    private val consumerSession = "abcdef0123456789abcdef0123456789"
    private class Clock : Wire.Clock {
        @Volatile var elapsed = 100L
        @Volatile var uptime = 100L
        var wall = 20_000L
        var blockPublisher = false
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        override fun elapsedRealtimeMs(): Long {
            if (blockPublisher && Thread.currentThread().name == "publisher-block-fixture") {
                entered.countDown(); check(release.await(2, TimeUnit.SECONDS))
            }
            return elapsed
        }
        override fun uptimeMs() = uptime
        override fun epochMs() = wall
    }
    private class Main : HelmHeaderConsumerExporter.MainPort {
        val immediate = ArrayDeque<Runnable>(); val delayed = ArrayDeque<Runnable>()
        override fun checkMain() { /* Explicit serial fixture caller, not OS-main admission. */ }
        override fun post(task: Runnable): Boolean { synchronized(immediate) { immediate.addLast(task) }; return true }
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean { delayed.addLast(task); return true }
        override fun remove(task: Runnable) { synchronized(immediate) { immediate.remove(task) }; delayed.remove(task) }
        fun run() { repeat(100) { val task = synchronized(immediate) { if (immediate.isEmpty()) return; immediate.removeFirst() }; task.run() }; error("unbounded fixture main") }
        fun tick() { assertFalse(delayed.isEmpty()); delayed.removeFirst().run() }
    }
    private class Work : HelmHeaderConsumerExporter.WorkPort {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(task: Runnable): Boolean { tasks.addLast(task); return true }
        fun one() { assertFalse(tasks.isEmpty()); tasks.removeFirst().run() }
        fun all() { repeat(100) { if (tasks.isEmpty()) return; one() }; error("unbounded fixture worker") }
    }
    private inner class Connection : HelmHeaderExportTransport {
        lateinit var publisher: HeaderReadOnlyPublisher
        val frames = mutableListOf<Wire.Frame>()
        var closes = 0
        var rootControl: HeaderReadOnlyPublisher.AuthenticatedRootControlPort? = null
        override fun start(clock: Wire.Clock, existingControllerSession: String,
            control: HeaderReadOnlyPublisher.AuthenticatedRootControlPort): HeaderReadOnlyPublisher {
            rootControl = control
            val fakePeer = HeaderPeerAdmission.root(object : HeaderPeerAdmission.KernelPeerProbe {
                override fun uid() = 0; override fun gid() = 0; override fun pid() = 123
            })
            return HeaderReadOnlyPublisher(clock, fakePeer,
                HeaderFrameCodec.Hello(rootSession, consumerSession, existingControllerSession),
                existingControllerSession, control).also { publisher = it }
        }
        override fun sendHeartbeat(): Boolean {
            val frame = publisher.heartbeat() ?: return false
            frames.add(frame); return true
        }
        override fun close() { closes++; if (::publisher.isInitialized) publisher.close() }
    }
    private inner class Fixture(enabled: Boolean = true) {
        val clock = Clock(); val main = Main(); val worker = Work(); val closer = Work()
        val connection = Connection(); var opens = 0
        val exporter = HelmHeaderConsumerExporter("host-a",
            if (enabled) HelmHeaderTransportFactory { opens++; connection } else null,
            clock, main, worker, closer)
        fun mapping(rootEpoch: Long = 71, localEpoch: Long = 3, pkg: String = HeaderApps.HELM,
            observed: Long = clock.uptime) = HelmHeaderFocusMapping(rootSession,
            Wire.RootFocus(rootSession, rootEpoch, "window-$rootEpoch", pkg, 0, 0, observed), localEpoch)
        fun raw(sequence: Long = 0) = HeaderSnapshotV1(
            HeaderPublication(HeaderApps.HELM, "producer-a", 3, 2, "source-a", sequence, 100, 1100),
            HeaderPage.AUDIO, HeaderTheme.RED_NIGHT, HeaderProfile.MARINE_SAFETY,
            HeaderContext("AUDIO", "real origin fixture", "paused"),
            HeaderSource("engine", "CAN", "engine-id", "backend-a", HeaderTransport.READY,
                HeaderQuality.LIVE, HeaderProvenance.REAL, HeaderFreshness(5000, 30_000)),
            20_000, HeaderExtras(portRpm = HeaderMeasurement(0.0, HeaderUnit.RPM, "port-id", 19_900,
                HeaderQuality.LIVE, HeaderProvenance.REAL, HeaderFreshness(1000, 2000)),
                sog = HeaderMeasurement(2.0, HeaderUnit.KNOTS, "gps-id", 19_800, HeaderQuality.LIVE)),
        )
        fun origin(rev: Long, snapshot: HeaderSnapshotV1? = raw(), reason: HeaderClearReason = HeaderClearReason.NONE) =
            HeaderRegistryOrigin(rev, HeaderScope(HeaderApps.HELM, "producer-a", 3, HeaderPage.AUDIO, 2, "source-a"), snapshot, reason)
        fun settle() { repeat(5) { worker.all(); main.run() } }
        fun live(pkg: String = HeaderApps.HELM) {
            exporter.start(); exporter.observeRoot(mapping(pkg = pkg)); settle()
            assertEquals(HelmHeaderConsumerExporter.Status.LIVE, exporter.status)
        }
        fun tick(now: Long) {
            clock.elapsed = now; clock.uptime = now
            exporter.observeRoot(mapping(observed = now)); main.tick(); settle()
        }
        fun finish() { exporter.close(); closer.all() }
    }

    @Test fun defaultDenyHasNoSocketWorkOrNewSourceAndStillClosesCleanly() {
        val f = Fixture(false)
        f.exporter.start(); f.exporter.observeRoot(f.mapping()); f.exporter.captureOrigin(f.origin(1)); f.main.run()
        assertEquals(HelmHeaderConsumerExporter.Status.DISABLED, f.exporter.status)
        assertEquals(0, f.opens); assertTrue(f.worker.tasks.isEmpty()); assertTrue(f.main.delayed.isEmpty())
        f.finish()
    }
    @Test fun exactOriginalKeepsMeasurementPublicationAndSeparatedEpochsAcrossHeartbeats() {
        val f = Fixture(); f.live()
        f.exporter.captureOrigin(f.origin(1)); f.main.run(); f.tick(150)
        val first = f.connection.frames.last(); val raw = first.snapshot!!
        assertEquals(71L, first.scope.rootEpoch); assertEquals(3L, first.scope.hostForegroundEpoch)
        assertEquals(3L, raw.publication.foregroundEpoch); assertEquals(100L, raw.publication.publishedAtElapsedMs)
        assertEquals(1100L, raw.publication.expiresAtElapsedMs); assertEquals(19_900L, raw.extras.portRpm.sourceObservedAtEpochMs)
        assertEquals(0.0, raw.extras.portRpm.value!!, 0.0)
        f.clock.wall = 80_000; f.tick(200)
        val second = f.connection.frames.last(); assertSame(raw, second.snapshot)
        assertEquals(first.stateRevision, second.stateRevision)
        assertEquals(200L, second.scope.focusObservedUptime)
        assertEquals(Wire.Quality.LIVE, raw.extras.portRpm.quality) // Root performs aging, sender never rewrites original.
        assertTrue(second.transportSequence > first.transportSequence)
        f.finish()
    }
    @Test fun expiryDoesNotGainAnotherLeaseThroughTransportHeartbeat() {
        val f = Fixture(); f.live(); f.exporter.captureOrigin(f.origin(1)); f.main.run()
        for (now in 150L..1050L step 50L) f.tick(now)
        f.tick(1099)
        assertNotNull(f.connection.frames.last().snapshot)
        f.tick(1100)
        val expired = f.connection.frames.last(); assertNull(expired.snapshot)
        assertEquals(Wire.Reason.EXPIRED, expired.reason)
        assertTrue(expired.expiresAtElapsedMs > 1100) // Transport lease is separate; original context is gone.
        f.finish()
    }
    @Test fun oldOriginCapturedBeforeNewRootScopeCannotBeRelabelledByMainQueue() {
        val f = Fixture(); f.live(); f.exporter.captureOrigin(f.origin(1))
        f.exporter.observeRoot(f.mapping(72, 4)); f.main.run()
        f.main.tick(); f.settle()
        assertNull(f.connection.frames.last().snapshot)
        assertEquals(72L, f.connection.frames.last().scope.rootEpoch)
        assertEquals(4L, f.connection.frames.last().scope.hostForegroundEpoch)
        f.finish()
    }
    @Test fun originQueueExpiryAtExactly250AndClockRollbackRetire() {
        val f = Fixture(); f.live(); f.exporter.captureOrigin(f.origin(1))
        f.clock.elapsed = 350; f.clock.uptime = 350; f.main.run()
        assertEquals("ORIGIN_DEADLINE", f.exporter.terminalReason); f.finish()
        val other = Fixture(); other.live(); other.clock.elapsed = 99
        other.main.tick(); assertEquals(HelmHeaderConsumerExporter.Status.RETIRED, other.exporter.status)
        other.finish()
    }
    @Test fun cancelDisconnectAndServiceCloseRetireWithoutAutomaticReplayOrReconnect() {
        val f = Fixture(); f.live(); f.exporter.captureOrigin(f.origin(1)); f.main.run()
        f.exporter.observeRoot(HelmHeaderFocusMapping(rootSession, null, 4))
        assertEquals(HelmHeaderConsumerExporter.Status.RETIRED, f.exporter.status)
        assertFalse(f.connection.rootControl!!.currentRootControlSession(rootSession))
        f.exporter.observeRoot(f.mapping(72, 5)); f.exporter.captureOrigin(f.origin(2)); f.settle()
        assertEquals(1, f.opens); assertNull(f.connection.publisher.captureScopeForAcceptedState())
        f.finish(); assertEquals(HelmHeaderConsumerExporter.Status.CLOSED, f.exporter.status)
        assertEquals(1, f.connection.closes)
    }
    @Test fun foreignAppKeepsAuthenticatedScopeWithNullBaseSnapshot() {
        val f = Fixture(); f.live("com.android.settings")
        val state = f.connection.frames.last()
        assertEquals("com.android.settings", state.scope.packageName)
        assertNull(state.snapshot); assertEquals(Wire.Reason.NO_FOREGROUND_SCOPE, state.reason)
        f.finish()
    }
    @Test fun duplicateOriginalPublicationCannotBeRewrittenToNewQualitiesOrTimes() {
        val f = Fixture(); f.live(); f.exporter.captureOrigin(f.origin(1)); f.main.run()
        val changed = f.raw().copy(sourceObservedAtEpochMs = 20_001)
        f.exporter.captureOrigin(f.origin(2, changed)); f.main.run()
        assertEquals("ORIGINAL_REWRITE", f.exporter.terminalReason); f.finish()
    }
    @Test fun admittedReconnectDoesNotReplayPreAdmissionSnapshot() {
        val f = Fixture()
        f.exporter.observeRoot(f.mapping()); f.exporter.captureOrigin(f.origin(1)); f.main.run()
        f.exporter.start(); f.settle()
        assertNull(f.connection.frames.last().snapshot)
        f.exporter.captureOrigin(f.origin(2, f.raw(1))); f.main.run(); f.tick(150)
        assertEquals(1L, f.connection.frames.last().snapshot!!.publication.sequence)
        f.finish()
    }
    @Test fun queuedHandshakeCompletionCannotAdmitAnEarlierOriginOrExtendHeartbeatGap() {
        val f = Fixture(); f.exporter.start(); f.exporter.observeRoot(f.mapping())
        f.worker.one() // Admission completion is queued but has not reached Main.
        f.exporter.captureOrigin(f.origin(1)) // Captured BEFORE admission; drain is queued AFTER completion.
        f.settle(); assertNull(f.connection.frames.last().snapshot)
        f.clock.elapsed = 200; f.clock.uptime = 200; f.exporter.observeRoot(f.mapping())
        f.main.tick(); assertEquals("HEARTBEAT_GAP", f.exporter.terminalReason)
        f.finish()
    }
    @Test fun heartbeatHoldingPublisherMonitorCannotBlockRegistryOriginOverflowOrMainClose() {
        val f = Fixture(); f.live(); f.clock.blockPublisher = true
        f.main.tick(); val task = f.worker.tasks.removeFirst()
        val thread = Thread({ task.run() }, "publisher-block-fixture"); thread.start()
        assertTrue(f.clock.entered.await(1, TimeUnit.SECONDS)) // Worker now owns Publisher monitor.
        val originReturned = CountDownLatch(1)
        val originThread = Thread {
            val registry = HeaderRegistry { f.exporter.captureOrigin(it) }
            val p = AuthenticatedHeaderProducer(HeaderApps.HELM, "producer-a")
            registry.producerStarted(p)
            registry.onForegroundChanged(HeaderScope(HeaderApps.HELM, "producer-a", 3, HeaderPage.AUDIO, 2, "source-a"))
            repeat(HelmHeaderConsumerExporter.MAX_PENDING_ORIGINS + 1) { registry.publish(p, f.raw(it.toLong()), 100) }
            originReturned.countDown()
        }
        originThread.start()
        assertTrue(originReturned.await(1, TimeUnit.SECONDS)) // Does not wait for the Publisher monitor.
        assertEquals("ORIGIN_QUEUE_OVERFLOW", f.exporter.terminalReason)
        f.exporter.close() // Main also returns while the worker remains blocked.
        assertEquals(HelmHeaderConsumerExporter.Status.CLOSED, f.exporter.status)
        assertFalse(f.connection.rootControl!!.currentRootControlSession(rootSession))
        f.clock.release.countDown(); thread.join(1000); originThread.join(1000)
        assertFalse(thread.isAlive); assertFalse(originThread.isAlive)
        f.main.run(); f.closer.all(); assertEquals(1, f.connection.closes)
    }
}
