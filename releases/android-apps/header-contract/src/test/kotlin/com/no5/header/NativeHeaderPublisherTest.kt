package com.no5.header

import org.junit.Assert.*
import org.junit.Test

class NativeHeaderPublisherTest {
    private class Clock(var elapsed: Long = 1_000, var epoch: Long = 20_000) : HeaderClock {
        override fun elapsedRealtimeMs() = elapsed
        override fun epochMs() = epoch
    }

    @Test fun publicationUsesMonotonicLeaseWithoutWritingMeasurementTime() {
        val clock = Clock()
        val publisher = NativeHeaderPublisher(HeaderApps.HOME, "fixture-session", clock, NativeHeaderTransport { HeaderPublishResult(true, HeaderClearReason.NONE) })
        val a = publisher.nextPublication(1, 1, null)
        clock.elapsed = 1_100; clock.epoch = 99_000
        val b = publisher.nextPublication(1, 1, null)
        assertEquals(0L, a.sequence); assertEquals(1L, b.sequence)
        assertEquals(1_100L, b.publishedAtElapsedMs); assertEquals(2_600L, b.expiresAtElapsedMs)
        val home = HomeHeaderSnapshotBuilder.build(b, HeaderTheme.DAY)
        assertNull(home.sourceObservedAtEpochMs)
    }

    @Test fun publisherCloseAndWrongInstanceNeverSendToTransport() {
        var sent = 0
        val publisher = NativeHeaderPublisher(HeaderApps.HOME, "fixture-session", Clock(), NativeHeaderTransport { sent++; HeaderPublishResult(true, HeaderClearReason.NONE) })
        val valid = HomeHeaderSnapshotBuilder.build(publisher.nextPublication(1, 1, null), HeaderTheme.DAY)
        val wrong = valid.copy(publication = valid.publication.copy(producerSession = "other"))
        assertFalse(publisher.publish(wrong).accepted); assertEquals(0, sent)
        assertTrue(publisher.publish(valid).accepted); assertEquals(1, sent)
        publisher.close(); assertEquals(HeaderClearReason.PRODUCER_DEAD, publisher.publish(valid).reason)
        assertEquals(1, sent)
    }

    @Test fun sdkAndRegistryCanExchangeTypedSnapshotWithoutHtmlOrAndroidDependencies() {
        val clock = Clock()
        val caller = AuthenticatedHeaderProducer(HeaderApps.HOME, "fixture-session")
        val registry = HeaderRegistry(); registry.producerStarted(caller)
        registry.onForegroundChanged(HeaderScope(HeaderApps.HOME, caller.producerSession, 1, HeaderPage.HOME, 1, null))
        val sdk = NativeHeaderPublisher(HeaderApps.HOME, caller.producerSession, clock,
            NativeHeaderTransport { registry.publish(caller, it, clock.elapsedRealtimeMs()) })
        assertTrue(sdk.publish(HomeHeaderSnapshotBuilder.build(sdk.nextPublication(1, 1, null), HeaderTheme.DAY)).accepted)
        assertEquals("NO5 · HOME", registry.current(clock.elapsed, clock.epoch).snapshot!!.context.title)
        clock.elapsed = 2_500
        assertNull(registry.current(clock.elapsed, clock.epoch).snapshot)
        assertEquals(187, registry.current(clock.elapsed, clock.epoch).navigation(SystemNavigationAction.RECENTS))
    }

    @Test(expected = ArithmeticException::class)
    fun leaseOverflowFailsBeforePublishing() {
        val sdk = NativeHeaderPublisher(HeaderApps.HOME, "fixture-session", Clock(Long.MAX_VALUE),
            NativeHeaderTransport { fail("transport must not be called"); HeaderPublishResult(false, HeaderClearReason.INVALID_ENVELOPE) })
        sdk.nextPublication(1, 1, null)
    }
}
