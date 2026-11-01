package com.no5.helm.engine

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test

class ResidentStartAdmissionTest {
    private val geometry = ChartSurfaceGeometry.fit(1000, 600)
    private val camera = ChartViewport(40.0, -70.0, .01, 0.0, 1000, 600)
    private val start = ChartResidentCameraStart(camera, camera, "a".repeat(64), 7, 3, 1250, 750)
    private val budget = ChartResidentBudget(25)
    private fun decision(view: ChartViewport? = camera, count: Int? = 10) = ChartResidentCameraDecision(view,
        if (view == null) ChartResidentCameraReason.BLOCKED else ChartResidentCameraReason.ACCEPTED,
        count, 25, start.expectedCatalogGeneration, 7, 3)

    @Test fun unchangedPoseIsRecountedUsingActualReserveBeforeCommit() {
        val plans = mutableListOf<ChartResidentCameraProposal>()
        val guard = ChartResidentCameraGuard { p, _ -> plans += p; decision(p.wanted) }
        assertEquals(camera, residentStartAdmission(start, budget, geometry, false, guard).acceptedCamera)
        assertEquals(2, plans.size)
        assertTrue(plans.all { it.planningWidth == 1250 && it.planningHeight == 750 })
    }
    @Test fun selectedCandidateMustSurviveRecountWithIdenticalAuthorityAndCount() {
        val closer = camera.copy(metresToPixels = .02)
        var changed = false
        val guard = object : ChartResidentCameraGuard {
            override fun selectStart(s: ChartResidentCameraStart, b: ChartResidentBudget) = decision(closer, 10)
            override fun evaluate(p: ChartResidentCameraProposal, b: ChartResidentBudget) =
                decision(p.wanted, if (changed) 26 else 10)
        }
        assertEquals(closer, residentStartAdmission(start, budget, geometry, false, guard).acceptedCamera)
        changed = true
        assertNull(residentStartAdmission(start, budget, geometry, false, guard).acceptedCamera)
    }
    @Test fun transitionDuringPolicyCallCannotCommitItsOldResult() {
        var epoch = 3L
        val guard = ChartResidentCameraGuard { p, _ -> epoch++; decision(p.wanted) }
        val result = residentStartAdmission(start, budget, geometry, false, guard) { epoch == 3L }
        assertNull(result.acceptedCamera)
        assertEquals(ChartResidentCameraReason.STALE_AUTHORITY, result.reason)
    }
    @Test fun unknownAndOutsideDataZeroCountsNeverProveNonemptyStart() {
        for (guard in listOf<ChartResidentCameraGuard?>(null,
            ChartResidentCameraGuard { p, _ -> decision(p.wanted, null) },
            ChartResidentCameraGuard { p, _ -> decision(p.wanted, 0) })) {
            assertNull(residentStartAdmission(start, budget, geometry, false, guard).acceptedCamera)
        }
        assertEquals(0, residentStartAdmission(start, budget, geometry, true, null).requiredUpperBound)
    }
    @Test fun journalRevalidationPreservesExactCameraAndDoesNotChooseANewFit() {
        val guard = object : ChartResidentCameraGuard {
            override fun selectStart(s: ChartResidentCameraStart, b: ChartResidentBudget): ChartResidentCameraDecision =
                error("journal restore must not choose a new camera")
            override fun evaluate(p: ChartResidentCameraProposal, b: ChartResidentBudget) = decision(p.wanted)
        }
        assertEquals(camera, residentStartAdmission(start, budget, geometry, false, guard, select = false).acceptedCamera)
    }
    @Test fun startupRejectsAboveBudgetRecoveryAndStaleBudgetEvenAtIdenticalPose() {
        val recovery = ChartResidentCameraGuard { p, _ -> decision(p.wanted, 30).copy(
            reason = ChartResidentCameraReason.RECOVERY_ABOVE_BUDGET, recoveryAboveBudget = true) }
        assertNull(residentStartAdmission(start, budget, geometry, false, recovery).acceptedCamera)
        val stale = ChartResidentCameraGuard { p, _ -> decision(p.wanted).copy(cellBudget = 100) }
        assertNull(residentStartAdmission(start, budget, geometry, false, stale).acceptedCamera)
    }
    @Test fun newerInwardInboxSupersedesSavedAboveBudgetPoseUntilItReachesBudget() {
        val inward = camera.copy(metresToPixels = .02)
        val safe = camera.copy(metresToPixels = .04)
        val evaluated = mutableListOf<ChartViewport>()
        val guard = object : ChartResidentCameraGuard {
            override fun selectStart(s: ChartResidentCameraStart, b: ChartResidentBudget): ChartResidentCameraDecision =
                error("A newer explicit user camera must not run startup fitting")
            override fun evaluate(p: ChartResidentCameraProposal, b: ChartResidentBudget): ChartResidentCameraDecision {
                evaluated += p.wanted
                return decision(p.wanted, if (p.wanted == inward) 30 else 20).let {
                    if (it.requiredUpperBound!! > b.cells) it.copy(reason = ChartResidentCameraReason.RECOVERY_ABOVE_BUDGET,
                        recoveryAboveBudget = true) else it
                }
            }
        }
        val first = start.proposal(inward).copy(pureZoomInRecovery = true)
        val progress = residentInboxAdmission(first, budget, geometry, false, guard) { true }
        assertEquals(inward, progress.acceptedCamera); assertTrue(progress.recoveryAboveBudget)
        val reached = residentInboxAdmission(first.copy(basis = inward, wanted = safe), budget, geometry, false, guard) { true }
        assertEquals(safe, reached.acceptedCamera); assertFalse(reached.recoveryAboveBudget)
        assertEquals(listOf(inward, safe), evaluated)
    }
    @Test fun reserveModeChangeDuringExactInboxRecountRevokesOldProof() {
        var mode = ChartBufferTransformMode.OFF
        val guard = ChartResidentCameraGuard { p, _ ->
            mode = ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE
            decision(p.wanted, 25)
        }
        val result = residentInboxAdmission(start.proposal(camera), budget, geometry, false, guard) {
            mode == ChartBufferTransformMode.OFF
        }
        assertNull(result.acceptedCamera); assertEquals(ChartResidentCameraReason.STALE_AUTHORITY, result.reason)
        val reserveOverBudget = ChartResidentCameraGuard { p, _ ->
            assertEquals(1250, p.planningWidth); assertEquals(750, p.planningHeight)
            decision(null, 26)
        }
        assertNull(residentStartAdmission(start, budget, geometry, false, reserveOverBudget).acceptedCamera)
    }
    @Test fun emptyCatalogAdmitsExactNewInboxWithoutADataGuard() {
        assertEquals(camera, residentInboxAdmission(start.proposal(camera), budget, geometry, true, null) { true }.acceptedCamera)
    }
}
