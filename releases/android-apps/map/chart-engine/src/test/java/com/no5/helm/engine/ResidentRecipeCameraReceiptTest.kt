package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.lumina.control.commands.v1.CommandRing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ResidentRecipeCameraReceiptTest {
    private val catalog = "a".repeat(64)
    private val old = ChartViewport(40.0, -70.0, .01, 0.0, 1000, 600)
    private val wanted = old.copy(longitude = -69.9)
    private fun decision(camera: ChartViewport = wanted) = ChartResidentCameraDecision(camera,
        ChartResidentCameraReason.ACCEPTED, 10, 25, catalog, 1, 2)
    private fun status(camera: ChartViewport = wanted) = ChartEngineStatus(initialized = true,
        chartLoaded = true, surfaceAttached = true, residentCatalogActive = true,
        residentCatalogGeneration = catalog, chartSourceGeneration = 1, surfaceGeneration = 2,
        viewport = camera, residentCameraReady = true, residentCameraDecision = decision(camera))
    private fun authority(state: ChartEngineStatus, revision: Long, cells: Int = 25) = buildJsonObject {
        put("catalogGeneration", state.residentCatalogGeneration)
        put("sourceGeneration", state.chartSourceGeneration); put("surfaceGeneration", state.surfaceGeneration)
        put("cameraRevision", revision); put("requestedCameraRevision", revision)
        put("viewport", BostonBenchmarkRecipe.cameraJson(state.viewport))
        put("budget", buildJsonObject { put("cells", cells); put("bytes", 100); put("loadingBytes", 50) })
    }
    private val expected get() = authority(status(old), 7)
    private fun observe(state: ChartEngineStatus = status(), revision: Long = 8,
        resultState: CommandRing.State = CommandRing.State.RUNNING, resultRevision: Long = 8,
        allowed: Boolean = true, found: Boolean = true, cells: Int = 25) =
        captureResidentRecipeCameraReceipt(Any(), 42, wanted, expected, {
            it.id = 42; it.state = resultState.ordinal; it.revision = resultRevision; it.reason = "test"
            found
        }, { state }, { revision }, { captured, rev -> authority(captured, rev, cells) }, { allowed })

    @Test fun preparedBeforeViewportPublicationCannotEscapeTheSharedOwnerLock() {
        val lock = Any()
        val state = AtomicReference(status(old))
        val revision = AtomicLong(7)
        val prepared = CountDownLatch(1); val publish = CountDownLatch(1); val readerStarted = CountDownLatch(1)
        val reads = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val result = AtomicReference<ResidentRecipeCameraReceipt?>()
        lateinit var actor: CameraControlActor
        actor = CameraControlActor({ state.get() }, { revision.get() }, { 100L }, false) { id, _ ->
            synchronized(lock) {
                val next = revision.incrementAndGet()
                actor.prepared(id, next, wanted)
                prepared.countDown()
                check(publish.await(2, TimeUnit.SECONDS))
                state.set(status(wanted))
                true
            }
        }
        var producer: Thread? = null; var reader: Thread? = null
        try {
            val id = actor.absolute(wanted, 1, 100, expectedSource = 1, expectedSurface = 2)
            assertTrue(id > 0)
            producer = thread(name = "recipe-test-producer") { try { actor.drainOne() } catch (t: Throwable) { failure.set(t) } }
            assertTrue(prepared.await(2, TimeUnit.SECONDS))
            val early = ChartControlResult()
            assertTrue(actor.read(id, early)); assertEquals(8L, early.revision)
            assertEquals(old, state.get().viewport)
            reader = thread(name = "recipe-test-reader") {
                try {
                    readerStarted.countDown()
                    result.set(captureResidentRecipeCameraReceipt(lock, id, wanted, expected,
                        { actor.read(id, it) }, { reads.incrementAndGet(); state.get() }, { revision.get() },
                        { captured, rev -> authority(captured, rev) }, { true }))
                } catch (t: Throwable) { failure.set(t) }
            }
            assertTrue(readerStarted.await(2, TimeUnit.SECONDS))
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (reader.state != Thread.State.BLOCKED && reader.isAlive && System.nanoTime() < until) Thread.yield()
            assertEquals(Thread.State.BLOCKED, reader.state)
            assertEquals(0, reads.get())
            publish.countDown()
            producer.join(2000); reader.join(2000)
            assertFalse(producer.isAlive); assertFalse(reader.isAlive); assertNull(failure.get())
            assertEquals(1, reads.get())
            assertTrue(checkNotNull(result.get()).accepted)
            assertEquals(wanted, result.get()!!.realizedViewport)
            assertEquals(BostonBenchmarkRecipe.cameraJson(wanted), result.get()!!.authority["viewport"])
        } finally {
            publish.countDown(); producer?.join(2000); reader?.join(2000); actor.close()
        }
    }

    @Test fun pendingPublicationIsNotAFalseTerminalFailureAndCanBeObservedAgain() {
        val pending = observe(status(old))
        assertFalse(pending.accepted); assertFalse(pending.terminal)
        assertTrue(observe(status(wanted)).accepted)
    }

    @Test fun serializationUsesOnlyTheCapturedObservationAfterTheOwnerChanges() {
        var state = status()
        var reads = 0
        val snapshot = captureResidentRecipeCameraReceipt(Any(), 42, wanted, expected, {
            it.id = 42; it.state = 1; it.revision = 8; true
        }, { reads++; state }, { 8 }, { captured, rev -> authority(captured, rev) }, { true })
        state = status(old).copy(chartSourceGeneration = 9, surfaceGeneration = 10)
        val response = snapshot.response(true)
        assertTrue(response.getValue("accepted").jsonPrimitive.boolean)
        assertEquals(BostonBenchmarkRecipe.cameraJson(wanted), response["realizedViewport"])
        assertEquals(BostonBenchmarkRecipe.cameraJson(wanted), response.getValue("authority").jsonObject["viewport"])
        assertEquals(1, response.getValue("authority").jsonObject.getValue("sourceGeneration").jsonPrimitive.int)
        assertEquals(1, reads)
    }

    @Test fun terminalRefusalCancelUnknownAndSupersessionNeverBecomeAccepted() {
        for (terminal in listOf(CommandRing.State.REJECTED, CommandRing.State.CANCELLED,
            CommandRing.State.UNKNOWN, CommandRing.State.SUPERSEDED)) {
            val snapshot = observe(resultState = terminal)
            assertFalse(snapshot.accepted); assertTrue(snapshot.terminal)
            assertFalse(snapshot.response(true).getValue("accepted").jsonPrimitive.boolean)
        }
        assertTrue(observe(allowed = false).terminal)
        assertFalse(observe(allowed = false).accepted)
        assertTrue(observe(found = false).terminal)
        assertFalse(observe(found = false).accepted)
    }

    @Test fun sourceSurfaceCatalogBudgetAndCameraRevisionChangesFailClosed() {
        for (changed in listOf(status().copy(chartSourceGeneration = 2), status().copy(surfaceGeneration = 3),
            status().copy(residentCatalogGeneration = "b".repeat(64)))) {
            assertFalse(observe(changed).accepted); assertTrue(observe(changed).terminal)
        }
        assertFalse(observe(cells = 26).accepted); assertTrue(observe(cells = 26).terminal)
        assertFalse(observe(revision = 9).accepted); assertTrue(observe(revision = 9).terminal)
    }

    @Test fun admissionAndPoseRemainExactAndAnExpiredDeadlineCannotBeOverridden() {
        assertFalse(observe(status().copy(residentCameraDecision = null)).accepted)
        assertFalse(observe(status().copy(residentCameraDecision = decision().copy(sourceGeneration = 2))).accepted)
        assertFalse(observe(status(wanted.copy(longitude = wanted.longitude + 1e-12))).accepted)
        val ready = observe()
        assertTrue(ready.accepted)
        assertFalse(ready.response(false).getValue("accepted").jsonPrimitive.boolean)
    }
}
