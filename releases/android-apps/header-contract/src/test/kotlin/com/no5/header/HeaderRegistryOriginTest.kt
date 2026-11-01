package com.no5.header

import org.junit.Assert.*
import org.junit.Test

/** Native origin tests: these do not establish an Android Binder/kernel/focus proof. */
class HeaderRegistryOriginTest {
    private val producer = AuthenticatedHeaderProducer(HeaderApps.HELM, "origin-producer")
    private val scope = HeaderScope(HeaderApps.HELM, producer.producerSession, 3, HeaderPage.AUDIO, 2, "source-a")
    private fun snapshot(seq: Long = 0) = HeaderSnapshotV1(
        HeaderPublication(scope.packageName, scope.producerSession, 3, 2, "source-a", seq, 100, 1100),
        HeaderPage.AUDIO, HeaderTheme.RED_NIGHT, HeaderProfile.MARINE_SAFETY,
        source = HeaderSource(sourceId = "engine", quality = HeaderQuality.LIVE),
        sourceObservedAtEpochMs = 20_000,
        extras = HeaderExtras(portRpm = HeaderMeasurement(0.0, HeaderUnit.RPM, "engine", 20_000, HeaderQuality.LIVE)),
    )
    @Test fun acceptedOriginIsOriginalAndAgedReadsNeverRewriteOrEmitIt() {
        val events = mutableListOf<HeaderRegistryOrigin>()
        val r = HeaderRegistry(events::add); r.producerStarted(producer); r.onForegroundChanged(scope)
        val raw = snapshot(); assertTrue(r.publish(producer, raw, 100).accepted)
        val accepted = events.last(); assertSame(raw, accepted.snapshot)
        val count = events.size
        assertEquals(HeaderQuality.LOST, r.current(200, 60_000).snapshot!!.extras.portRpm!!.quality)
        assertEquals(count, events.size)
        assertSame(raw, accepted.snapshot); assertEquals(HeaderQuality.LIVE, raw.extras.portRpm!!.quality)
        assertEquals(20_000L, raw.extras.portRpm!!.sourceObservedAtEpochMs)
        assertEquals(100L, raw.publication.publishedAtElapsedMs)
    }
    @Test fun expiryClearAndDeathEmitNullAtOriginWithoutResettingSequence() {
        val events = mutableListOf<HeaderRegistryOrigin>()
        val r = HeaderRegistry(events::add); r.producerStarted(producer); r.onForegroundChanged(scope)
        r.publish(producer, snapshot(4), 100)
        assertNotNull(r.current(1099, 20_000).snapshot)
        assertNull(r.current(1100, 20_000).snapshot)
        assertNull(events.last().snapshot); assertEquals(HeaderClearReason.EXPIRED, events.last().reason)
        assertEquals(HeaderClearReason.OUT_OF_ORDER, r.publish(producer, snapshot(4), 200).reason)
        r.producerDied(producer)
        assertEquals(HeaderClearReason.PRODUCER_DEAD, events.last().reason)
        assertTrue(events.zipWithNext().all { (a, b) -> a.revision < b.revision })
    }
    @Test fun pageScopeClearPrecedesNewPublicationAndBackgroundDenialCannotEraseIt() {
        val events = mutableListOf<HeaderRegistryOrigin>()
        val r = HeaderRegistry(events::add); r.producerStarted(producer); r.onForegroundChanged(scope)
        r.publish(producer, snapshot(), 100)
        val next = scope.copy(pageId = HeaderPage.SONAR, pageGeneration = 3)
        r.onForegroundChanged(next)
        assertEquals(next, events.last().scope); assertNull(events.last().snapshot)
        val valid = snapshot(1).copy(pageId = HeaderPage.SONAR,
            publication = snapshot(1).publication.copy(pageGeneration = 3))
        assertTrue(r.publish(producer, valid, 200).accepted)
        val count = events.size
        val foreign = AuthenticatedHeaderProducer(HeaderApps.MAP, "different-producer")
        assertFalse(r.publish(foreign, valid, 200).accepted)
        assertEquals(count, events.size); assertSame(valid, events.last().snapshot)
    }
    @Test fun optionalReadOnlyObserverFailureNeverChangesAdmissionOrNativeState() {
        val r = HeaderRegistry { throw IllegalStateException("broken export fixture") }
        assertTrue(r.producerStarted(producer)); r.onForegroundChanged(scope)
        val raw = snapshot(); assertTrue(r.publish(producer, raw, 100).accepted)
        assertEquals(raw, r.current(100, 20_000).snapshot)
        r.onForegroundChanged(null); assertNull(r.current(100, 20_000).snapshot)
    }
}
