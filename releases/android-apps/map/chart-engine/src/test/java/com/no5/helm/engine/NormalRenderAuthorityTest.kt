package com.no5.helm.engine

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NormalRenderAuthorityTest {
    /** Actual Kotlin publication primitives, canonical CONTROL fill inside the existing padded pair.
     * This fixture is not a JNI/GL/physical-Texture proof. */
    private class PublicationFixture {
        val geometry = ChartSurfaceGeometry.fit(2000, 1070)
        val raster = ChartBufferRaster.capture(ChartRasterGeometry.reserve(geometry),
            ChartViewport(42.36, -71.04, .15, width = 2000, height = 1070), reserve = false)
        val registry = ChartBufferRegistry()
        val gate = ChartPresentationGate()
        var generation = registry.invalidate()
        init { gate.reset(generation) }
        fun submit(time: Long, revision: Long): ChartBufferSnapshot {
            val slot = checkNotNull(gate.writableSlot(generation))
            val packet = checkNotNull(registry.submit(generation, time, revision, geometry,
                raster.producerCamera, "normal-abort-publication", ChartContentStage.FULL, slot, raster,
                sourceGeneration = 11, contentGeneration = 17))
            check(gate.reserve(generation, slot, packet.stamp)); return packet
        }
        fun present(packet: ChartBufferSnapshot): ChartBufferSnapshot {
            val completed = checkNotNull(registry.complete(packet.stamp,
                packet.frame.copy(scale = ChartScale(100.0, 1000.0))))
            check(gate.acquired(packet.generation, packet.presentationSlot, packet.stamp))
            check(gate.committed(packet.presentationSlot, packet.stamp))
            check(registry.observe(packet.stamp) === completed); return completed
        }
        fun discard(packet: ChartBufferSnapshot) {
            gate.discard(packet.generation, packet.stamp); registry.discard(packet.stamp)
        }
    }

    @Test fun supersededPairedBackSlotRetainsExactFrontAndAppliedAuthorityUntilFreshFullPresentation() {
        val f = PublicationFixture(); val authority = NormalRenderAuthorityTracker()
        val first = authority.advance(7, 11, 13); assertTrue(authority.markApplied(first, 7, 11, 13))
        val front = f.present(f.submit(100, 7))
        assertEquals(2500, f.raster.geometry.bufferWidth); assertEquals(1338, f.raster.geometry.bufferHeight)
        assertEquals(2000, f.raster.filledWidth); assertEquals(1070, f.raster.filledHeight)
        assertEquals(250.0, f.raster.filledLeft, 0.0); assertEquals(134.0, f.raster.filledTop, 0.0)
        assertEquals(ChartContentStage.FULL, front.stage); assertTrue(front.producerCompleted)
        val applied = authority.advance(8, 11, 13); assertTrue(authority.markApplied(applied, 8, 11, 13))
        val newest = authority.advance(9, 11, 13)
        val abandoned = f.submit(101, 8)
        val reservation = f.gate.reservation(f.generation, abandoned.presentationSlot, abandoned.stamp)!!
        assertEquals(front.presentationSlot, reservation.currentFrontSlot)
        assertNotEquals(front.presentationSlot, reservation.reservedSlot)
        var queued = 0
        assertEquals(NormalRenderOutcome.SUPERSEDED_RENDER,
            normalRenderOutcome(1, { f.discard(abandoned) }, { queued++ }))
        assertEquals(1, queued)
        assertSame(front, f.registry.lookup(front.stamp)); assertEquals(front.stamp, f.registry.observedStamp())
        assertSame(applied, authority.applied()); assertSame(newest, authority.latest())
        assertNull(f.registry.lookup(abandoned.stamp)); assertNull(f.registry.complete(abandoned.stamp, abandoned.frame))
        assertNull(f.gate.reservation(abandoned.generation, abandoned.presentationSlot, abandoned.stamp))
        assertFalse(f.gate.acquired(abandoned.generation, abandoned.presentationSlot, abandoned.stamp))
        assertFalse(f.gate.acknowledge(abandoned.generation, abandoned.presentationSlot, abandoned.stamp))
        assertEquals(abandoned.presentationSlot, f.gate.writableSlot(f.generation))
        val fresh = f.submit(102, 9)
        assertNotEquals(abandoned.stamp, fresh.stamp); assertSame(front, f.registry.lookup(front.stamp))
        assertEquals(front.stamp, f.registry.observedStamp()) // Submission is not a presentation.
        assertEquals(NormalRenderOutcome.FULL_COMPLETE, normalRenderOutcome(0, { f.discard(fresh) }, { queued++ }))
        assertTrue(authority.markApplied(newest, 9, 11, 13))
        val displayed = f.present(fresh)
        assertTrue(displayed.producerCompleted); assertEquals(ChartContentStage.FULL, displayed.stage)
        assertEquals(fresh.stamp, f.registry.observedStamp()); assertSame(displayed, f.registry.lookup(fresh.stamp))
        assertEquals(front.presentationSlot, f.gate.writableSlot(f.generation)); assertEquals(1, queued)
        assertSame(newest, authority.applied())
    }

    @Test fun lateAbortedStampCannotCompleteOrReleaseTheCurrentGenerationBackSlot() {
        val f = PublicationFixture(); val front = f.present(f.submit(100, 7))
        val abandoned = f.submit(101, 8)
        assertNotNull(f.gate.reservation(abandoned.generation, abandoned.presentationSlot, abandoned.stamp))
        f.generation = f.registry.invalidate(); f.gate.reset(f.generation, keepFront = true)
        assertNull(f.registry.lookup(front.stamp)); assertEquals(0L, f.registry.observedStamp()) // Honest epoch fence.
        assertEquals(abandoned.presentationSlot, f.gate.writableSlot(f.generation)) // Actual gate front is retained.
        val fresh = f.submit(102, 9)
        val currentPermit = f.gate.reservation(fresh.generation, fresh.presentationSlot, fresh.stamp)!!
        assertEquals(NormalRenderOutcome.SUPERSEDED_RENDER, normalRenderOutcome(1, { f.discard(abandoned) }, {}))
        assertEquals(currentPermit, f.gate.reservation(fresh.generation, fresh.presentationSlot, fresh.stamp))
        assertSame(fresh, f.registry.lookup(fresh.stamp)); assertNull(f.registry.complete(abandoned.stamp, abandoned.frame))
        assertFalse(f.gate.acquired(abandoned.generation, abandoned.presentationSlot, abandoned.stamp))
        assertFalse(f.gate.acknowledge(abandoned.generation, abandoned.presentationSlot, abandoned.stamp))
        assertEquals(0L, f.registry.observedStamp()); assertTrue(f.gate.busy())
        val displayed = f.present(fresh)
        assertEquals(fresh.stamp, f.registry.observedStamp()); assertSame(displayed, f.registry.lookup(fresh.stamp))
    }

    @Test fun renderEpochAdvancesIndependentlyForSourceAndSurfaceWithTheSameCameraRevision() {
        val tracker = NormalRenderAuthorityTracker()
        val a = tracker.advance(7, 1, 2)
        val source = tracker.advance(7, 2, 2)
        val surface = tracker.advance(7, 2, 3)
        val camera = tracker.advance(8, 2, 3)
        assertEquals(listOf(1L, 2L, 3L, 4L), listOf(a, source, surface, camera).map { it.epoch })
        assertEquals(listOf(7L, 7L, 7L, 8L), listOf(a, source, surface, camera).map { it.cameraRevision })
        assertSame(camera, tracker.advance(8, 2, 3))
    }

    @Test fun staleOrReorderedGenerationsNeverChangeCurrentAuthority() {
        val tracker = NormalRenderAuthorityTracker(); val latest = tracker.advance(9, 3, 4)
        for (tuple in listOf(longArrayOf(8, 3, 4), longArrayOf(9, 2, 4), longArrayOf(9, 3, 2))) {
            assertThrows(IllegalArgumentException::class.java) { tracker.advance(tuple[0], tuple[1], tuple[2]) }
            assertSame(latest, tracker.latest())
        }
    }

    @Test fun newestAnnouncementIsNeverUsedToRelabelAnAlreadyAppliedOlderCamera() {
        val tracker = NormalRenderAuthorityTracker(); val a = tracker.advance(7, 1, 2)
        assertTrue(tracker.markApplied(a, 7, 1, 2))
        val b = tracker.advance(8, 1, 2)
        assertSame(a, tracker.applied()); assertSame(b, tracker.latest())
        assertFalse(tracker.markApplied(b, 7, 1, 2))
        assertTrue(tracker.markApplied(a, 7, 1, 2)) // Rebinding the actually applied A remains honest.
        assertTrue(tracker.markApplied(b, 8, 1, 2))
        assertFalse(tracker.markApplied(a, 7, 1, 2)) // Once B applied, a late A caller is retired.
        assertSame(b, tracker.applied())
    }

    @Test fun concurrentTargetCannotReplaceTheAuthorityDrainedUnderTheOwnerApplyLock() {
        val tracker = NormalRenderAuthorityTracker(); val ownerLock = Any()
        val attempting = CountDownLatch(1); val completed = CountDownLatch(1)
        val newer = AtomicReference<NormalRenderAuthority>()
        val failure = AtomicReference<Throwable>()
        lateinit var a: NormalRenderAuthority
        val worker = Thread {
            attempting.countDown()
            try { synchronized(ownerLock) { newer.set(tracker.advance(8, 1, 2)) } }
            catch (caught: Throwable) { failure.set(caught) }
            finally { completed.countDown() }
        }.apply { isDaemon = true }
        synchronized(ownerLock) {
            a = tracker.advance(7, 1, 2) // Same boundary as viewport/inbox drain.
            worker.start(); assertTrue(attempting.await(2, TimeUnit.SECONDS))
            assertEquals(1L, completed.count) // B cannot enter the native-applied A transaction.
            assertTrue(tracker.markApplied(a, 7, 1, 2))
        }
        assertTrue(completed.await(2, TimeUnit.SECONDS)); worker.join(2_000)
        assertFalse(worker.isAlive); assertNull(failure.get())
        assertSame(newer.get(), tracker.latest()); assertSame(a, tracker.applied())
        assertTrue(tracker.markApplied(a, 7, 1, 2))
        assertFalse(tracker.markApplied(newer.get(), 7, 1, 2))
    }

    @Test fun forgedTokensOrSourceSurfaceMismatchesCannotBecomeApplied() {
        val tracker = NormalRenderAuthorityTracker(); val a = tracker.advance(7, 1, 2)
        assertFalse(tracker.markApplied(a.copy(), 7, 1, 2))
        assertFalse(tracker.markApplied(a, 7, 2, 2))
        assertFalse(tracker.markApplied(a, 7, 1, 3))
        assertNull(tracker.applied()); assertTrue(tracker.markApplied(a, 7, 1, 2))
    }

    @Test fun closeRevokesCapturedTokensAndClearAppliedDoesNotRollBackEpoch() {
        val tracker = NormalRenderAuthorityTracker(); val a = tracker.advance(7, 1, 2)
        assertTrue(tracker.markApplied(a, 7, 1, 2)); tracker.clearApplied()
        assertNull(tracker.applied()); assertSame(a, tracker.latest())
        val b = tracker.advance(8, 1, 2); assertEquals(a.epoch + 1, b.epoch)
        tracker.close(); assertNull(tracker.latest()); assertNull(tracker.applied())
        assertFalse(tracker.markApplied(b, 8, 1, 2))
        assertThrows(IllegalStateException::class.java) { tracker.advance(9, 1, 2) }
    }

    @Test fun supersededOutcomeDiscardsAndQueuesWithoutChangingCameraAppliedOrPublishingFull() {
        var appliedCamera = 7L; var full = 0; var discarded = 0; var queued = 0
        val result = normalRenderOutcome(1, { discarded++ }, { queued++ })
        if (result == NormalRenderOutcome.FULL_COMPLETE) full++
        assertEquals(NormalRenderOutcome.SUPERSEDED_RENDER, result)
        assertEquals(1, discarded); assertEquals(1, queued); assertEquals(0, full); assertEquals(7L, appliedCamera)
        val completed = normalRenderOutcome(0, { discarded++ }, { queued++ })
        if (completed == NormalRenderOutcome.FULL_COMPLETE) full++
        assertEquals(1, full); assertEquals(1, discarded); assertEquals(1, queued)
        assertThrows(IllegalStateException::class.java) { normalRenderOutcome(-1, {}, {}) }
    }

    @Test fun invalidEpochCannotWrapOrUseUnknownSourceSurface() {
        assertThrows(IllegalArgumentException::class.java) { NormalRenderAuthority(Long.MAX_VALUE, 0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { NormalRenderAuthority(0, 0, 0, 0) }
        for (tuple in listOf(longArrayOf(-1, 0, 0), longArrayOf(0, -1, 0), longArrayOf(0, 0, -1)))
            assertThrows(IllegalArgumentException::class.java) { NormalRenderAuthorityTracker().advance(tuple[0], tuple[1], tuple[2]) }
    }

    @Test fun cancellationOrCloseDuringLookupBindingOrDrawDiscardsWithoutInventingTypedSupersession() {
        val tracker = NormalRenderAuthorityTracker(); val applied = tracker.advance(7, 1, 2)
        assertTrue(tracker.markApplied(applied, 7, 1, 2)); tracker.close()
        var discarded = 0
        assertFalse(normalRenderOperation({ true }, { discarded++ }) {
            checkNotNull(tracker.applied()); true
        })
        var cancelled = false
        assertFalse(normalRenderOperation({ cancelled }, { discarded++ }) {
            cancelled = true; error("native apply interrupted")
        })
        var nativeAlreadyPublished = false
        assertFalse(normalRenderOperation({ true }, { discarded++ }) {
            nativeAlreadyPublished = true; error("cancellation first observed after native endFrame")
        })
        assertTrue(nativeAlreadyPublished) // No claim that a generic exception erased native publication.
        assertEquals(3, discarded)
        val genuine = IllegalStateException("actual native error")
        assertSame(genuine, assertThrows(IllegalStateException::class.java) {
            normalRenderOperation({ false }, { discarded++ }) { throw genuine }
        })
        assertEquals(3, discarded)
        assertTrue(normalRenderOperation({ false }, { discarded++ }) { true })
    }
}
