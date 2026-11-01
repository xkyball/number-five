package com.no5.helm.engine

import com.no5.helm.chart.RendererBenchmarkConfig
import com.no5.helm.chart.RendererBenchmarkStatus
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class BenchmarkRunRegistryTest {
    @Test fun residentBudgetArmsUseTheSameLeaseAndCannotReuseTokenWithChangedPinsOrBudget() {
        val registry = BenchmarkRunRegistry()
        val arm = com.no5.helm.chart.ResidentCapacityArm(com.no5.helm.chart.ChartResidentBudget(25),
            com.no5.helm.chart.ResidentCapacityCacheMode.EMPTY_GEOMETRY, "a".repeat(64), 7, 5_000)
        val config = RendererBenchmarkConfig(1, "resident", 1000, 100,
            runKind = com.no5.helm.chart.RendererRunKind.RESIDENT_CAPACITY, capacityArm = arm)
        val run = started(registry, config)
        assertTrue(registry.admit(config, 1) is BenchmarkRunRegistry.Admission.Reused)
        for (changed in listOf(arm.copy(budget = com.no5.helm.chart.ChartResidentBudget(50)),
            arm.copy(cacheMode = com.no5.helm.chart.ResidentCapacityCacheMode.WARM),
            arm.copy(expectedCatalogGeneration = "b".repeat(64)), arm.copy(expectedSourceGeneration = 8))) {
            assertTrue(registry.admit(config.copy(capacityArm = changed), 1) is BenchmarkRunRegistry.Admission.Rejected)
        }
        registry.beginRecovery(run.id, 50)
        assertFalse(registry.cancel(run.id, "late cancel"))
        assertTrue(registry.admit(RendererBenchmarkConfig(1, "legacy", 1000, 100), 1) is BenchmarkRunRegistry.Admission.Rejected)
        registry.endRecovery(run.id, 80)
        registry.finish(run.id, RendererBenchmarkStatus(runId = run.id, restored = true))
        assertEquals(com.no5.helm.chart.RendererRunKind.RESIDENT_CAPACITY,
            registry.status(RendererBenchmarkStatus(), "resident").runKind)
    }
    @Test fun liveAndScriptedRunsShareOwnershipAndTokenIncludesModeAndReplay() {
        val registry = BenchmarkRunRegistry()
        val live = RendererBenchmarkConfig(1, "live", 1000, 100,
            runKind = com.no5.helm.chart.RendererRunKind.LIVE_INPUT)
        val run = (registry.admit(live, 0) as BenchmarkRunRegistry.Admission.Started).run
        assertTrue(registry.admit(live, 1) is BenchmarkRunRegistry.Admission.Reused)
        assertTrue(registry.admit(live.copy(liveReplay = false), 1) is BenchmarkRunRegistry.Admission.Rejected)
        assertTrue(registry.admit(live.copy(liveMode = com.no5.helm.chart.ChartBufferTransformMode.TRANSFORM_ONLY), 1) is BenchmarkRunRegistry.Admission.Rejected)
        assertTrue(registry.admit(RendererBenchmarkConfig(1, "scripted", 1000, 100), 1) is BenchmarkRunRegistry.Admission.Rejected)
        registry.beginRecovery(run.id, 50)
        assertFalse(registry.cancel(run.id, "late cancel"))
        registry.finish(run.id, RendererBenchmarkStatus(runId=run.id, restored=true))
        assertEquals(com.no5.helm.chart.RendererRunKind.LIVE_INPUT, registry.status(RendererBenchmarkStatus(), "live").runKind)
        assertFalse(registry.status(RendererBenchmarkStatus(), "live").liveInputEnabled)
    }
    private fun config(token: String, deadline: Long = 1000, recovery: Long = 100) =
        RendererBenchmarkConfig(1, token, deadline, recovery)
    private fun started(registry: BenchmarkRunRegistry, config: RendererBenchmarkConfig, now: Long = 0) =
        (registry.admit(config, now) as BenchmarkRunRegistry.Admission.Started).run

    @Test fun lostStartReplyAndConcurrentRetriesLaunchExactlyOneRun() {
        val registry = BenchmarkRunRegistry(); val config = config("lost-response")
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll((0 until 24).map { Callable { registry.admit(config, 0) } }).map { it.get() }
            assertEquals(1, results.count { it is BenchmarkRunRegistry.Admission.Started })
            assertEquals(23, results.count { it is BenchmarkRunRegistry.Admission.Reused })
            assertEquals(1, results.map { when (it) {
                is BenchmarkRunRegistry.Admission.Started -> it.run.id
                is BenchmarkRunRegistry.Admission.Reused -> it.run.id
                else -> error("Unexpected rejection")
            } }.distinct().size)
        } finally { pool.shutdownNow() }
    }
    @Test fun completedTokenRemainsIdempotentAfterDeadlineAndNewRun() {
        val registry = BenchmarkRunRegistry(); val first = started(registry, config("first"))
        registry.finish(first.id, RendererBenchmarkStatus(runId = first.id, phase = "complete", restored = true))
        val second = started(registry, config("second", 3000), 2000)
        assertTrue(registry.admit(config("first"), 2000) is BenchmarkRunRegistry.Admission.Reused)
        assertEquals(first.id, registry.status(RendererBenchmarkStatus(runId = second.id), "first").runId)
        assertTrue(registry.status(RendererBenchmarkStatus(), "first").restored)
    }
    @Test fun staleCancelAndStaleFinishNeverTouchReplacementRun() {
        val registry = BenchmarkRunRegistry(); val first = started(registry, config("first"))
        registry.finish(first.id, RendererBenchmarkStatus(runId = first.id))
        val next = started(registry, config("next"))
        assertFalse(registry.cancel(first.id, "late cancel"))
        assertFalse(registry.finish(first.id, RendererBenchmarkStatus()))
        assertFalse(registry.cancelled(next.id)); assertEquals(next.id, registry.activeRun()?.id)
    }
    @Test fun changedTokenParametersAreRejectedEvenAfterCompletion() {
        val registry = BenchmarkRunRegistry(); val config = config("stable")
        val run = started(registry, config)
        registry.finish(run.id, RendererBenchmarkStatus())
        assertTrue(registry.admit(config.copy(recoveryBudgetMs = 50), 0) is BenchmarkRunRegistry.Admission.Rejected)
        assertTrue(registry.admit(config.copy(requestedWorkers = 2), 0) is BenchmarkRunRegistry.Admission.Rejected)
    }
    @Test fun deadlineSignalsOnceAndRecoveryHasSeparateBudget() {
        val registry = BenchmarkRunRegistry(); val run = started(registry, config("budget"))
        assertNull(registry.tick(999)); assertEquals(run, registry.tick(1000)); assertNull(registry.tick(1001))
        registry.beginRecovery(run.id, 1050)
        assertFalse(registry.cancel(run.id, "must not interrupt restore"))
        registry.tick(1149); assertFalse(registry.control(run.id)!!.recoveryDeadlineExceeded)
        registry.tick(1150); assertTrue(registry.control(run.id)!!.recoveryDeadlineExceeded)
        assertTrue(registry.isActive(run.id)) // No fabricated restoration/termination while a native call remains blocked.
    }
    @Test fun recoveryThatReturnsLateRetainsBudgetViolation() {
        val registry = BenchmarkRunRegistry(); val run = started(registry, config("late"))
        registry.beginRecovery(run.id, 10); registry.endRecovery(run.id, 111)
        assertTrue(registry.control(run.id)!!.recoveryDeadlineExceeded)
    }
    @Test fun closedOrReplacedRegistryCannotReuseOldRunIdentity() {
        val old = BenchmarkRunRegistry(); val run = started(old, config("same-token"))
        val replacement = BenchmarkRunRegistry(); val next = started(replacement, config("same-token"))
        assertNotEquals(run.id, next.id); assertFalse(replacement.cancel(run.id, "old process"))
    }
    @Test fun invalidPastOrOverlongDeviceDeadlineCannotOwnRenderer() {
        val registry = BenchmarkRunRegistry()
        assertTrue(registry.admit(config("past", 100), 100) is BenchmarkRunRegistry.Admission.Rejected)
        assertTrue(registry.admit(config("long", 600101), 100) is BenchmarkRunRegistry.Admission.Rejected)
        assertNull(registry.activeRun())
    }
    @Test fun finalObservationSealKeepsEarlierCancelAndRejectsEveryLaterCancel() {
        val registry = BenchmarkRunRegistry(); val run = started(registry, config("sealed-cancel"))
        assertTrue(registry.cancel(run.id, "client cancel"))
        assertNull(registry.sealMeasurementForRecovery(run.id, 500))
        assertEquals("client cancel", registry.control(run.id)!!.cancelReason)
        assertEquals(500L, registry.control(run.id)!!.recoveryStartedMs)
        assertFalse(registry.cancel(run.id, "late cancel"))
        assertNull(registry.sealMeasurementForRecovery(run.id, 600))
        assertEquals(500L, registry.control(run.id)!!.recoveryStartedMs)
    }
    @Test fun finalObservationSealAtomicallyAccountsForDeadlineAndSeparatesRecovery() {
        val registry = BenchmarkRunRegistry(); val run = started(registry, config("sealed-deadline"))
        assertEquals(run, registry.sealMeasurementForRecovery(run.id, 1000))
        assertEquals("device deadline exceeded", registry.control(run.id)!!.cancelReason)
        assertTrue(registry.cancelled(run.id)); assertFalse(registry.cancel(run.id, "late client cancel"))
        assertNull(registry.tick(1001))
        val success = BenchmarkRunRegistry(); val done = started(success, config("sealed-success"))
        assertNull(success.sealMeasurementForRecovery(done.id, 999))
        assertFalse(success.cancelled(done.id)); assertFalse(success.cancel(done.id, "after measurement boundary"))
        assertEquals("", success.control(done.id)!!.cancelReason)
    }
}
