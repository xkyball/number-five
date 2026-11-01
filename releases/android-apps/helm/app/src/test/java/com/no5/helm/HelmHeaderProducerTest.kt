package com.no5.helm

import com.no5.domain.*
import com.no5.header.*
import com.no5.header.android.HeaderAdmission
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

/** Synthetic raw model/admission/Main queue. No Binder, network, render state or physical source. */
class HelmHeaderProducerTest {
    private fun raw(speed: Double = 10.0, stamp: Long = 20_000) = HelmState(
        quality = TelemetryQuality(TelemetryStatus.LIVE, 20_000, "fixture-source", demo = false),
        telemetryFields = mapOf(
            "speed" to TelemetryValue(speed, TelemetryQuality(TelemetryStatus.LIVE, stamp, "fixture-speed", demo = false)),
            "portRpm" to TelemetryValue(0.0, TelemetryQuality(TelemetryStatus.LIVE, 14_000, "fixture-rpm", demo = false))),
        sourceSessionId = "fixture-backend-session")

    @Test fun admissionReadsActualRawFieldsWithSeparateClientAndBackendIdentities() {
        val f = Fixture(); f.ready("AUDIO")
        val s = f.client.snapshots.last()
        assertEquals(HeaderPage.AUDIO, s.pageId); assertEquals(HeaderProfile.MARINE_SAFETY, s.profile)
        assertEquals("client-source-1", s.publication.sourceSessionId)
        assertEquals("fixture-backend-session", s.source.backendSessionId)
        assertEquals(10.0, s.extras.sog!!.value!!, 0.0)
        assertEquals(0.0, s.extras.portRpm!!.value!!, 0.0)
        assertEquals(14_000L, s.extras.portRpm!!.sourceObservedAtEpochMs)
        assertEquals(HeaderQuality.STALE, s.extras.portRpm!!.quality)
        assertEquals(HeaderTransport.UNKNOWN, s.source.transport)
        assertEquals(20_000L, s.sourceObservedAtEpochMs)
    }

    @Test fun heartbeatRenewsOnlyPublicationLeaseAndDegradesOriginalMeasurements() {
        val f = Fixture(); f.ready("AUDIO"); val first = f.client.snapshots.last()
        f.epoch = 50_001; f.main.runPulse()
        val next = f.client.snapshots.last()
        assertTrue(next.publication.sequence > first.publication.sequence)
        assertEquals(first.sourceObservedAtEpochMs, next.sourceObservedAtEpochMs)
        assertEquals(14_000L, next.extras.portRpm!!.sourceObservedAtEpochMs)
        assertEquals(20_000L, next.extras.sog!!.sourceObservedAtEpochMs)
        assertEquals(HeaderQuality.LOST, next.extras.sog!!.quality); assertNull(next.extras.sog!!.value)
    }

    @Test fun pageSwitchDropsCapturedOldPageAndOnlyNewAdmissionCanSampleCurrentFeed() {
        val f = Fixture(); f.ready("AUDIO"); f.client.snapshots.clear()
        val oldGeneration = f.client.generation
        f.main.background { f.feed.emit(raw(30.0)) }
        f.producer.updateUi("SONAR", false, "actual-sonar-status")
        f.main.drain()
        assertTrue(f.client.snapshots.isEmpty()); assertTrue(f.client.generation > oldGeneration)
        f.admit()
        assertEquals(1, f.client.snapshots.size)
        assertEquals(HeaderPage.SONAR, f.client.snapshots.single().pageId)
        assertEquals("actual-sonar-status", f.client.snapshots.single().context.pageStatus)
        assertEquals(20_000L, f.client.snapshots.single().extras.sog!!.sourceObservedAtEpochMs)
    }

    @Test fun sourceReplacementCannotRelabelAnOldAsyncCallbackOrOldBackendSession() {
        val f = Fixture(); f.ready("AUDIO"); f.client.snapshots.clear()
        f.main.background { f.feed.emit(raw(99.0)) }
        val replacement = MutableSourceFeed(raw(4.0).copy(sourceSessionId = "backend-2"))
        f.sources.replace(HelmHeaderProducer.Source(Any(), "client-source-2", replacement,
            QualityEvaluator.SHARED, DisplayUnits()))
        f.main.drain(); assertTrue(f.client.snapshots.isEmpty())
        f.admit()
        val s = f.client.snapshots.single()
        assertEquals("client-source-2", s.publication.sourceSessionId)
        assertEquals("backend-2", s.source.backendSessionId); assertEquals(4.0, s.extras.sog!!.value!!, 0.0)
    }

    @Test fun serverNewForegroundAdmissionDoesNotRetagAlreadyQueuedRawCallback() {
        val f = Fixture(); f.ready("AUDIO")
        f.main.background { f.feed.emit(raw(8.0)) }
        f.admit(foregroundEpoch = 2)
        val afterNewRead = f.client.snapshots.size
        f.main.drain()
        assertEquals(afterNewRead, f.client.snapshots.size)
        assertEquals(2L, f.client.snapshots.last().publication.foregroundEpoch)
        assertEquals(20_000L, f.client.snapshots.last().extras.sog!!.sourceObservedAtEpochMs)
    }

    @Test fun unavailableRawWithdrawsSourceIdentityAndNeverExportsDefaultScalars() {
        val f = Fixture(); f.ready("AUDIO"); f.client.snapshots.clear()
        val previous = f.client.generation
        f.feed.emit(HelmState.unavailable(source = "fixture-source"))
        assertNull(f.client.sourceSessionId); assertTrue(f.client.generation > previous)
        assertTrue(f.client.snapshots.isEmpty())
        f.admit()
        val s = f.client.snapshots.single()
        assertNull(s.publication.sourceSessionId); assertNull(s.extras.sog!!.value)
        assertNull(s.extras.portRpm!!.value); assertNull(s.sourceObservedAtEpochMs)
    }

    @Test fun ownerDisposalSynchronouslyWithdrawsEvenWhenLeaseHadCachedLiveData() {
        val f = Fixture(); f.ready("AUDIO")
        val previous = f.client.generation
        f.sources.replace(null)
        assertNull(f.client.sourceSessionId); assertTrue(f.client.generation > previous)
        val count = f.client.snapshots.size
        f.feed.emit(raw(70.0)); f.main.runPulse(); f.main.drain()
        assertEquals(count, f.client.snapshots.size)
    }

    @Test fun unitsAndThemeUseCurrentRealSettingsWithoutRenewingSensorTimes() {
        val f = Fixture(); f.ready("AUDIO")
        f.sources.replace(f.sources.source!!.copy(units = DisplayUnits(speed = SpeedUnit.KMH)))
        f.producer.updateUi("AUDIO", true)
        val s = f.client.snapshots.last()
        assertEquals(HeaderTheme.RED_NIGHT, s.theme); assertEquals(HeaderUnit.KMH, s.extras.sog!!.unit)
        assertEquals(18.52, s.extras.sog!!.value!!, 1e-9)
        assertEquals(20_000L, s.extras.sog!!.sourceObservedAtEpochMs)
    }

    @Test fun dashboardPublishesRealSafetyValuesOnlyAfterItsOwnAdmission() {
        val f = Fixture(); f.ready("AUDIO")
        val old = f.client.admission!!; val previous = f.client.generation
        f.client.snapshots.clear()
        f.main.background { f.feed.emit(raw(90.0)) }
        f.producer.updateUi("DASHBOARD", false)
        assertEquals(0, f.client.pauses)
        assertEquals(HeaderPage.DASHBOARD, f.client.page)
        assertTrue(f.client.generation > previous)
        f.producer.onAdmission(old); f.main.drain(); f.main.runPulse()
        assertTrue(f.client.snapshots.isEmpty())
        f.admit()
        val snapshot = f.client.snapshots.single()
        assertEquals(HeaderPage.DASHBOARD, snapshot.pageId)
        assertEquals(HeaderProfile.MARINE_SAFETY, snapshot.profile)
        assertEquals("DASHBOARD", snapshot.context.title)
        assertEquals(90.0, snapshot.extras.sog!!.value!!, 0.0)
        assertEquals(20_000L, snapshot.extras.sog!!.sourceObservedAtEpochMs)
        assertEquals(0.0, snapshot.extras.portRpm!!.value!!, 0.0)
        assertEquals(14_000L, snapshot.extras.portRpm!!.sourceObservedAtEpochMs)
        assertEquals(HeaderQuality.STALE, snapshot.extras.portRpm!!.quality)
    }

    @Test fun unsupportedPagePausesProducerAndReturnHasNewGeneration() {
        val f = Fixture(); f.ready("AUDIO")
        val old = f.client.admission!!; val previous = f.client.generation
        f.producer.updateUi("FUTURE_PAGE", false)
        assertEquals(1, f.client.pauses)
        val count = f.client.snapshots.size
        f.producer.onAdmission(old); f.feed.emit(raw(90.0)); f.main.runPulse()
        assertEquals(count, f.client.snapshots.size)
        f.producer.updateUi("AUDIO", false)
        assertTrue(f.client.generation > previous); assertTrue(f.client.resumed); assertTrue(f.client.focused)
        f.admit(); assertEquals(HeaderPage.AUDIO, f.client.snapshots.last().pageId)
    }

    @Test fun pauseAndFocusLossDiscardPendingDataAndRequireFreshAdmission() {
        val f = Fixture(); f.ready("AUDIO")
        f.main.background { f.feed.emit(raw(12.0)) }
        f.producer.onPause(); val count = f.client.snapshots.size
        f.main.drain(); f.main.runPulse(); assertEquals(count, f.client.snapshots.size)
        f.producer.onResume(); f.admit(); val resumedCount = f.client.snapshots.size
        f.main.background { f.feed.emit(raw(14.0)) }
        f.producer.onWindowFocusChanged(false); f.main.drain()
        assertEquals(resumedCount, f.client.snapshots.size)
        f.producer.onWindowFocusChanged(true); f.admit(foregroundEpoch = 3)
        assertEquals(3L, f.client.snapshots.last().publication.foregroundEpoch)
    }

    @Test fun rawBackgroundBurstUsesOneMainPostAndOneInFlightPublication() {
        val f = Fixture(); f.ready("AUDIO")
        f.client.autoComplete = false; f.client.snapshots.clear()
        val postsBefore = f.main.posts
        f.main.background { repeat(100) { f.feed.emit(raw(it.toDouble())) } }
        assertEquals(postsBefore + 1, f.main.posts)
        assertEquals(1, f.main.pendingTasks)
        f.main.drain(); assertEquals(1, f.client.snapshots.size)
        repeat(100) { f.feed.emit(raw(it.toDouble())) }; f.main.runPulse()
        assertEquals(1, f.client.snapshots.size)
        f.client.complete(); f.main.runPulse(); assertEquals(2, f.client.snapshots.size)
    }

    @Test fun lateCallbackOfFailedQueueSlotCannotReleaseANewerInFlightPublication() {
        val f = Fixture(); f.ready("AUDIO")
        f.client.autoComplete = false; f.client.snapshots.clear()
        f.client.nextQueued = false
        f.feed.emit(raw(1.0)) // SDK returns false now but still owns one later result callback.
        f.feed.emit(raw(2.0)) // A new, accepted asynchronous publication is now in flight.
        assertEquals(2, f.client.snapshots.size)
        f.client.complete() // Result of the failed FIRST attempt, not the current one.
        f.feed.emit(raw(3.0)); f.main.runPulse()
        assertEquals(2, f.client.snapshots.size)
        f.client.complete(); f.feed.emit(raw(4.0))
        assertEquals(3, f.client.snapshots.size)
    }

    @Test fun sourceCallbackCopiesFieldMapBeforeMainAndNeverReadsRendererDefaults() {
        val f = Fixture(); f.ready("AUDIO"); f.client.snapshots.clear()
        val fields = raw(6.0).telemetryFields.toMutableMap()
        f.main.background { f.feed.emit(raw().copy(speed = 777.0, telemetryFields = fields)) }
        fields["speed"] = TelemetryValue(55.0, TelemetryQuality(TelemetryStatus.LIVE, 20_000, "fixture-speed", demo = false))
        f.main.drain()
        assertEquals(6.0, f.client.snapshots.single().extras.sog!!.value!!, 0.0)
    }

    @Test fun mainHelmHasBrandSourceAndNoSafetyExtras() {
        val f = Fixture(); f.ready("HELM")
        val s = f.client.snapshots.last()
        assertEquals(HeaderProfile.BRAND_SOURCE, s.profile); assertTrue(s.extras.isEmpty())
    }

    @Test fun closeIsIdempotentRetiresOnlySubscriptionsAndNoLateCallbacksPublish() {
        val f = Fixture(); f.ready("AUDIO")
        f.main.background { f.feed.emit(raw(10.0)) }
        f.producer.close(); f.producer.close()
        val count = f.client.snapshots.size
        f.feed.emit(raw(30.0)); f.sources.replace(null); f.main.drain(); f.main.runPulse()
        assertEquals(count, f.client.snapshots.size); assertEquals(1, f.client.closes)
        assertEquals(0, f.main.delayed.size)
    }

    private inner class Fixture {
        var epoch = 20_000L
        val main = FakeMain()
        val feed = MutableSourceFeed(raw())
        val sources = FakeSources(HelmHeaderProducer.Source(Any(), "client-source-1", feed,
            QualityEvaluator.SHARED, DisplayUnits()))
        val client = FakeClient()
        val producer = HelmHeaderProducer(client, sources, main, { epoch })
        fun ready(page: String) {
            producer.updateUi(page, false); producer.onWindowFocusChanged(true); producer.onResume(); admit()
        }
        fun admit(foregroundEpoch: Long = 1) {
            val grant = HeaderAdmission("fixture-root", "fixture-producer", 1, "scope-${++client.revision}",
                foregroundEpoch, client.page, client.generation, client.sourceSessionId, client.revision, true)
            client.admission = grant; producer.onAdmission(grant)
        }
    }
    private class FakeSources(var source: HelmHeaderProducer.Source?) : HelmHeaderProducer.Sources {
        private var revision = 1L
        override val changes = MutableSourceFeed(revision)
        override fun current() = source
        fun replace(next: HelmHeaderProducer.Source?) { source = next; changes.emit(++revision) }
    }
    private class FakeClient : HelmHeaderProducer.Client {
        var page = HeaderPage.HELM
        var generation = 1L
        var sourceSessionId: String? = null
        var admission: HeaderAdmission? = null
        var revision = 0L
        var sequence = 0L
        var resumed = false
        var focused = false
        var pauses = 0
        var closes = 0
        var autoComplete = true
        var nextQueued = true
        val snapshots = mutableListOf<HeaderSnapshotV1>()
        private val completions = ArrayDeque<(HeaderPublishResult) -> Unit>()
        override fun onResume() { resumed = true }
        override fun onPause() { pauses++; resumed = false; admission = null }
        override fun onWindowFocusChanged(focused: Boolean) { this.focused = focused; if (!focused) admission = null }
        override fun state(page: HeaderPage, generation: Long, sourceSessionId: String?) {
            assertTrue(generation >= this.generation)
            if (page != this.page || sourceSessionId != this.sourceSessionId) assertTrue(generation > this.generation)
            this.page = page; this.generation = generation; this.sourceSessionId = sourceSessionId; admission = null
        }
        override fun retryAdmission() = Unit
        override fun nextPublication(scope: HeaderAdmission): HeaderPublication? {
            if (scope != admission) return null
            return HeaderPublication(HeaderApps.HELM, scope.producerSession, scope.foregroundEpoch,
                scope.pageGeneration, scope.sourceSessionId, sequence++, 1_000, 2_500)
        }
        override fun publish(scope: HeaderAdmission, snapshot: HeaderSnapshotV1, result: (HeaderPublishResult) -> Unit): Boolean {
            assertEquals(admission, scope); snapshots += snapshot
            if (autoComplete) result(HeaderPublishResult(true, HeaderClearReason.NONE)) else completions.addLast(result)
            return nextQueued.also { nextQueued = true }
        }
        fun complete() { completions.removeFirst()(HeaderPublishResult(true, HeaderClearReason.NONE)) }
        override fun close() { closes++; resumed = false; admission = null }
    }
    private class FakeMain : HelmHeaderProducer.Main {
        private var onMain = true
        private val tasks = ArrayDeque<Runnable>()
        val delayed = ArrayDeque<Runnable>()
        var posts = 0
        val pendingTasks get() = tasks.size
        override fun isMainThread() = onMain
        override fun post(task: Runnable): Boolean { posts++; tasks.addLast(task); return true }
        override fun postDelayed(task: Runnable, milliseconds: Long) { assertEquals(500L, milliseconds); delayed.addLast(task) }
        override fun remove(task: Runnable) { tasks.removeIf { it === task }; delayed.removeIf { it === task } }
        fun background(action: () -> Unit) { onMain = false; try { action() } finally { onMain = true } }
        fun drain() { var count = 0; while (tasks.isNotEmpty()) { check(++count <= 100); tasks.removeFirst().run() } }
        fun runPulse() { if (delayed.isNotEmpty()) delayed.removeFirst().run() }
    }
}
