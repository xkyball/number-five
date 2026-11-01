package com.no5.helm.engine

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test

class BenchmarkCurrentCameraAdmissionTest {
    private val geometry = ChartSurfaceGeometry.fit(1000, 600)
    private val camera = ChartViewport(40.0, -70.0, .01, 0.0, 1000, 600)
    private val start = ChartResidentCameraStart(camera, camera, "a".repeat(64), 7, 3, 1000, 600)
    private val budget = ChartResidentBudget(25)
    private fun accepted(p: ChartResidentCameraProposal) = ChartResidentCameraDecision(p.wanted,
        ChartResidentCameraReason.ACCEPTED, 10, 25, p.expectedCatalogGeneration,
        p.expectedSourceGeneration, p.expectedSurfaceGeneration)

    @Test fun reboundGuardReconfirmsOnlyTheExactRestoredCameraWithoutSelectingAStart() {
        val calls = mutableListOf<ChartResidentCameraProposal>()
        val guard = object : ChartResidentCameraGuard {
            override fun selectStart(start: ChartResidentCameraStart, budget: ChartResidentBudget): ChartResidentCameraDecision =
                error("A diagnostic must not select or move the restored camera")
            override fun evaluate(proposal: ChartResidentCameraProposal, budget: ChartResidentBudget): ChartResidentCameraDecision {
                calls += proposal
                return accepted(proposal)
            }
        }
        val decision = benchmarkCurrentCameraAdmission(start, budget, geometry, false, guard) { true }
        assertEquals(camera, decision?.acceptedCamera)
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.basis == camera && it.wanted == camera &&
            it.expectedSourceGeneration == 7L && it.expectedSurfaceGeneration == 3L })
    }

    @Test fun refusalUnknownCountAndChangedPoseNeverProduceAPublishableDecision() {
        for (bad in listOf(
            accepted(start.proposal(camera)).copy(acceptedCamera = null, reason = ChartResidentCameraReason.BLOCKED),
            accepted(start.proposal(camera)).copy(requiredUpperBound = null),
            accepted(start.proposal(camera)).copy(acceptedCamera = camera.copy(metresToPixels = .02)),
            accepted(start.proposal(camera)).copy(sourceGeneration = 8),
            accepted(start.proposal(camera)).copy(surfaceGeneration = 4),
            accepted(start.proposal(camera)).copy(cellBudget = 26),
        )) assertNull(benchmarkCurrentCameraAdmission(start, budget, geometry, false,
            ChartResidentCameraGuard { _, _ -> bad }) { true })
        assertNull(benchmarkCurrentCameraAdmission(start, budget, geometry, false, null) { true })
    }

    @Test fun sourceSurfaceOrCameraEpochChangeDuringRecountCannotPublish() {
        var current = true
        val guard = ChartResidentCameraGuard { p, _ -> current = false; accepted(p) }
        assertNull(benchmarkCurrentCameraAdmission(start, budget, geometry, false, guard) { current })
        var calls = 0
        assertNull(benchmarkCurrentCameraAdmission(start, budget, geometry, false,
            ChartResidentCameraGuard { p, _ -> calls++; accepted(p) }) { false })
        assertEquals(0, calls)
    }

    @Test fun aDifferentRequestedCameraCannotBecomeADiagnosticReconfirmation() {
        var calls = 0
        val moved = start.copy(desired = camera.copy(rotationRadians = .2))
        assertNull(benchmarkCurrentCameraAdmission(moved, budget, geometry, false,
            ChartResidentCameraGuard { p, _ -> calls++; accepted(p) }) { true })
        assertEquals(0, calls)
    }
}
