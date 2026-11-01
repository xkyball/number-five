package com.no5.helm.engine

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test
import org.lumina.control.commands.v1.CommandRing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CurrentResidentLiveCameraAdmissionTest {
    private fun initial() = ChartEngineStatus(initialized = true, chartLoaded = true, surfaceAttached = true,
        viewport = ChartViewport(metresToPixels = .01))

    @Test fun alreadyAcceptedCenterIsBusyAndReachesItsOriginalResultWithoutRunActivation() {
        var status = initial(); var revision = 0L; lateinit var actor: CameraControlActor
        val registry = BenchmarkRunRegistry()
        actor = CameraControlActor({ status }, { revision }, { 100L }, false) { id, row ->
            revision++; status = status.copy(viewport = status.viewport.copy(latitude = row.a, longitude = row.b))
            actor.prepared(id, revision, status.viewport); true
        }
        try {
            val id = actor.submit(ChartCameraControl.CENTER, 0.0, 1, 100, 41.0, -71.0)
            assertTrue(id > 0); assertFalse(actor.idleForMeasurement())
            assertFalse(actor.admitMeasurementWhenIdle(Any()) { fail("Must not activate a run while camera work is accepted"); true })
            assertNull(registry.activeRun())
            val row = ChartControlResult(); assertTrue(actor.read(id, row))
            assertEquals(CommandRing.State.ACCEPTED.ordinal, row.state)
            actor.drainOne(); actor.beginNative(revision); actor.nativeApplied(revision, status.viewport)
            assertTrue(actor.read(id, row)); assertEquals(CommandRing.State.APPLIED.ordinal, row.state)
            assertEquals(41.0, status.viewport.latitude, 0.0); assertTrue(actor.idleForMeasurement())
        } finally { actor.close() }
    }

    @Test fun preparingPreparedAndNativeRunningAllStayBusyUntilTheActualResult() {
        var status = initial(); var revision = 0L; lateinit var actor: CameraControlActor
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        actor = CameraControlActor({ status }, { revision }, { 100L }, false) { id, _ ->
            entered.countDown(); check(release.await(2, TimeUnit.SECONDS))
            revision++; actor.prepared(id, revision, status.viewport); true
        }
        try {
            val id = actor.submit(ChartCameraControl.NORTH_UP, 0.0, 1, 100)
            val worker = thread { actor.drainOne() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(actor.idleForMeasurement())
            assertFalse(actor.admitMeasurementWhenIdle(Any()) { fail("Preparing must retain its owner"); true })
            release.countDown(); worker.join(2000); assertFalse(worker.isAlive)
            assertFalse(actor.idleForMeasurement()) // Prepared but not applied.
            actor.beginNative(revision); assertFalse(actor.idleForMeasurement())
            actor.nativeApplied(revision, status.viewport)
            val result = ChartControlResult(); assertTrue(actor.read(id, result))
            assertEquals(CommandRing.State.APPLIED.ordinal, result.state); assertTrue(actor.idleForMeasurement())
        } finally { release.countDown(); actor.close() }
    }

    @Test fun callerThatPassedItsEnginePrecheckCannotEnqueueAcrossTheIdleRunTransition() {
        var status = initial()
        val registry = BenchmarkRunRegistry { error("UUID generation must happen outside the actor lock") }
        val config = RendererBenchmarkConfig(repetitions = 1, clientRunToken = "current", deadlineElapsedRealtimeMs = 1000)
        val owner = Any(); val prechecked = CountDownLatch(1); val enterActor = CountDownLatch(1)
        val finished = CountDownLatch(1); var accepted = -1L
        val actor = CameraControlActor({ status }, { 0L }, { 100L }, false) { _, _ -> error("No old work existed") }
        val caller = thread {
            check(!status.rendererBenchmark.active) // The actual engine-side precheck preceded run admission.
            prechecked.countDown(); check(enterActor.await(2, TimeUnit.SECONDS))
            accepted = actor.submit(ChartCameraControl.CENTER, 0.0, 1, 100, 41.0, -71.0)
            finished.countDown()
        }
        try {
            assertTrue(prechecked.await(2, TimeUnit.SECONDS))
            assertTrue(actor.admitMeasurementWhenIdle(owner) {
                registry.admit(config, 100, "00000000-0000-4000-8000-000000000001") is BenchmarkRunRegistry.Admission.Started
            })
            // Registry active, observer status not yet published: the one-slot transition token closes this exact gap.
            assertNotNull(registry.activeRun()); assertFalse(status.rendererBenchmark.active)
            enterActor.countDown(); assertTrue(finished.await(2, TimeUnit.SECONDS)); assertEquals(0L, accepted)
            assertFalse(actor.releaseMeasurementAdmission(Any())); assertFalse(actor.idleForMeasurement())
            status = status.copy(rendererBenchmark = RendererBenchmarkStatus(active = true))
            assertTrue(actor.releaseMeasurementAdmission(owner))
            assertEquals(0L, actor.submit(ChartCameraControl.CENTER, 0.0, 2, 100, 41.0, -71.0))
            assertTrue(actor.idleForMeasurement())
        } finally { enterActor.countDown(); caller.join(2000); actor.close() }
    }
}
