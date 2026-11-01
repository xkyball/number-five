package com.no5.helm.engine

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test
import org.lumina.control.commands.v1.CommandRing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Real bounded Actor/CommandRing, with only the camera-owner callback supplied by the test. */
class CameraControlDiagnosticOriginTest {
    private class Fixture : AutoCloseable {
        var status = ChartEngineStatus(initialized = true, chartLoaded = true, surfaceAttached = true,
            chartSourceGeneration = 3, surfaceGeneration = 7, viewport = ChartViewport(metresToPixels = .01))
        var revision = 0L
        val observed = mutableListOf<CameraDiagnosticOrigin?>()
        var beforeCommit: (() -> Unit)? = null
        lateinit var actor: CameraControlActor
        init { actor = CameraControlActor({ status }, { revision }, { 100L }, false) { id, row ->
            beforeCommit?.invoke()
            if (!actor.canCommit(id) || row.generation != status.chartSourceGeneration || row.surface != status.surfaceGeneration) false
            else {
                observed += actor.runningInputOrigin(id)
                revision++
                status = status.copy(viewport = status.viewport.copy(latitude = row.a, longitude = row.b,
                    metresToPixels = row.c, rotationRadians = row.d))
                actor.prepared(id, revision, status.viewport)
                true
            }
        } }
        fun absolute(origin: CameraDiagnosticOrigin?, scale: Double = .02, preview: Boolean = true): Long =
            actor.absolute(status.viewport.copy(metresToPixels = scale), 0, 100, preview, 3, 7, origin)
        fun result(id: Long) = ChartControlResult().also { assertTrue(actor.read(id, it)) }
        fun apply() { actor.beginNative(revision); actor.nativeApplied(revision, status.viewport) }
        override fun close() = actor.close()
    }
    private fun origin(buffer: RendererLiveInputBuffer, source: Long = 3, surface: Long = 7): CameraDiagnosticOrigin {
        val id = buffer.input(100, 200, 202, 101, 2, 7, -1, 1,
            intArrayOf(7), floatArrayOf(10f, 20f), RendererInputOrigin.VIEW_INJECTED)
        return buffer.withInput(id) { CameraDiagnosticOrigin.capture(buffer, source, surface) }!!
    }

    @Test fun crossThreadCommitRetainsOriginalInputAfterUiThreadLocalHasEnded() = Fixture().use { f ->
        val buffer = RendererLiveInputBuffer("cross-thread")
        val origin = origin(buffer)
        val id = f.absolute(origin, preview = false) // Finger UP may end preview before this worker runs.
        val worker = thread { assertEquals(0L, buffer.currentInput()); f.actor.drainOne() }
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertSame(origin, f.observed.single())
        f.actor.beginNative(f.revision)
        assertSame(origin, f.actor.nativeInputOrigin(f.revision))
        f.actor.nativeApplied(f.revision, f.status.viewport)
        assertEquals(CommandRing.State.APPLIED.ordinal, f.result(id).state)
        assertNull(f.actor.nativeInputOrigin(f.revision))
    }

    @Test fun queuedAbsoluteCoalescenceKeepsOnlyActualLatestTargetOriginAndOriginalRingResult() = Fixture().use { f ->
        val buffer = RendererLiveInputBuffer("coalesced")
        val old = origin(buffer); val latest = origin(buffer)
        val a = f.absolute(old, .02); val b = f.absolute(latest, .03)
        assertEquals(CommandRing.State.SUPERSEDED.ordinal, f.result(a).state)
        assertEquals(b, f.result(a).replacementId)
        f.actor.drainOne()
        assertSame(latest, f.observed.single())
        f.actor.beginNative(f.revision)
        assertSame(latest, f.actor.nativeInputOrigin(f.revision))
        f.actor.nativeApplied(f.revision, f.status.viewport)
        assertEquals(CommandRing.State.APPLIED.ordinal, f.result(b).state)
        assertEquals(CommandRing.State.SUPERSEDED.ordinal, f.result(a).state)
    }

    @Test fun runningAndPreparedOldTargetCannotStealNewPendingOrigin() = Fixture().use { f ->
        val buffer = RendererLiveInputBuffer("separate-phases")
        val aOrigin = origin(buffer); val bOrigin = origin(buffer)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.beforeCommit = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
        val a = f.absolute(aOrigin, .02)
        val worker = thread { f.actor.drainOne() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val b = f.absolute(bOrigin, .03)
        release.countDown(); worker.join(2000)
        assertFalse(worker.isAlive)
        f.beforeCommit = null
        val aRevision = f.revision; val aView = f.status.viewport
        f.actor.beginNative(aRevision)
        assertSame(aOrigin, f.actor.nativeInputOrigin(aRevision))
        f.actor.drainOne()
        assertEquals(listOf(aOrigin, bOrigin), f.observed)
        // B is prepared while A's actual native operation remains owned by A.
        assertSame(aOrigin, f.actor.nativeInputOrigin(aRevision))
        f.actor.nativeApplied(aRevision, aView)
        assertEquals(CommandRing.State.APPLIED.ordinal, f.result(a).state)
        f.actor.beginNative(f.revision)
        assertSame(bOrigin, f.actor.nativeInputOrigin(f.revision))
        f.actor.nativeApplied(f.revision, f.status.viewport)
        assertEquals(CommandRing.State.APPLIED.ordinal, f.result(b).state)
    }

    @Test fun retiringOldCaptureNeverCancelsItsCommandOrReassignsReusedIdToNewCapture() = Fixture().use { f ->
        val old = RendererLiveInputBuffer("same-run"); val current = RendererLiveInputBuffer("same-run")
        val oldOrigin = origin(old); val currentOrigin = origin(current)
        assertEquals(oldOrigin.inputId, currentOrigin.inputId)
        val a = f.absolute(oldOrigin)
        f.actor.drainOne(); f.actor.beginNative(f.revision)
        f.actor.retireInputOrigins(old); old.finishReport()
        assertNull(f.actor.nativeInputOrigin(f.revision))
        assertFalse(oldOrigin.matches(current, 3, 7))
        f.actor.nativeApplied(f.revision, f.status.viewport)
        assertEquals(CommandRing.State.APPLIED.ordinal, f.result(a).state)
        val b = f.absolute(currentOrigin, .03); f.actor.drainOne(); f.actor.beginNative(f.revision)
        assertSame(currentOrigin, f.actor.nativeInputOrigin(f.revision))
        f.actor.nativeApplied(f.revision, f.status.viewport)
        assertEquals(CommandRing.State.APPLIED.ordinal, f.result(b).state)
    }

    @Test fun diagnosticAbsenceAndWrongSourceNeverRejectOtherwiseValidBusinessCommand() = Fixture().use { f ->
        val buffer = RendererLiveInputBuffer("wrong-source")
        val wrong = origin(buffer, source = 99)
        val first = f.absolute(wrong); f.actor.drainOne()
        assertNull(f.observed.single()); f.apply()
        assertEquals(CommandRing.State.APPLIED.ordinal, f.result(first).state)
        val second = f.absolute(null, .03); f.actor.drainOne(); f.apply()
        assertEquals(CommandRing.State.APPLIED.ordinal, f.result(second).state)
        assertNull(f.observed.last())
    }

    @Test fun unattributedNewTargetCannotInheritCoalescedOldTargetOrigin() = Fixture().use { f ->
        val old = f.absolute(origin(RendererLiveInputBuffer("old")), .02)
        val current = f.absolute(null, .03)
        assertEquals(CommandRing.State.SUPERSEDED.ordinal, f.result(old).state)
        f.actor.drainOne(); f.actor.beginNative(f.revision)
        assertNull(f.observed.single())
        assertNull(f.actor.nativeInputOrigin(f.revision))
        f.actor.nativeApplied(f.revision, f.status.viewport)
        assertEquals(CommandRing.State.APPLIED.ordinal, f.result(current).state)
    }

    @Test fun ordinaryPhysicalAdmissionDoesNotReadTheDiagnosticProbeOrCreateOrigins() = Fixture().use { f ->
        val id = f.actor.submit(ChartCameraControl.ZOOM_STEPS, 1.0, 1, 100)
        val probe = CameraControlActor::class.java.getDeclaredField("diagnosticProbe").apply { isAccessible = true }
            .get(f.actor) as CommandRing.Entry
        assertNull(probe.state)
        f.actor.drainOne(); f.actor.beginNative(f.revision)
        assertNull(f.observed.single())
        assertNull(f.actor.nativeInputOrigin(f.revision))
        f.actor.nativeApplied(f.revision, f.status.viewport)
        assertNull(probe.state)
        assertEquals(CommandRing.State.APPLIED.ordinal, f.result(id).state)
    }

    @Test fun focusCancelSourceSurfaceAndClosePreserveExistingBusinessOutcomes() = Fixture().use { f ->
        val buffer = RendererLiveInputBuffer("cancel")
        val origin = origin(buffer)
        val cancelled = f.absolute(origin); f.actor.cancel(); f.actor.drainOne()
        assertEquals(CommandRing.State.CANCELLED.ordinal, f.result(cancelled).state)
        assertTrue(f.observed.isEmpty())
        val changed = f.absolute(origin)
        f.status = f.status.copy(surfaceGeneration = 8)
        f.actor.drainOne()
        assertEquals(CommandRing.State.REJECTED.ordinal, f.result(changed).state)
        assertEquals("SOURCE_CHANGED", f.result(changed).reason)
        f.status = f.status.copy(surfaceGeneration = 7)
        val afterChange = f.absolute(origin); f.actor.drainOne(); f.actor.beginNative(f.revision)
        f.actor.cancel() // Already-started native work is not changed into a replayable cancellation.
        f.actor.nativeApplied(f.revision, f.status.viewport)
        assertEquals(CommandRing.State.APPLIED.ordinal, f.result(afterChange).state)
        val closed = f.absolute(origin, .03); f.actor.close(); f.actor.drainOne()
        assertEquals(CommandRing.State.CANCELLED.ordinal, f.result(closed).state)
    }

    @Test fun sourceChangeRejectsOldTargetWithoutPublishingItsOriginInNewSource() = Fixture().use { f ->
        val id = f.absolute(origin(RendererLiveInputBuffer("source")))
        f.status = f.status.copy(chartSourceGeneration = 4)
        f.actor.drainOne()
        assertTrue(f.observed.isEmpty())
        assertEquals(CommandRing.State.REJECTED.ordinal, f.result(id).state)
        assertEquals("SOURCE_CHANGED", f.result(id).reason)
        assertNull(f.actor.nativeInputOrigin(f.revision))
    }
}
