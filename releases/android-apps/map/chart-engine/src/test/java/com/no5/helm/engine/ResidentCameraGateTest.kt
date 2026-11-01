package com.no5.helm.engine

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test

class ResidentCameraGateTest {
    private val basis = ChartViewport(40.0, -70.0, .01, .2, 1000, 600)
    private val wanted = basis.copy(metresToPixels = .005)
    private val budget = ChartResidentBudget(25)
    private val proposal = ChartResidentCameraProposal(basis, wanted, basis, false, "a".repeat(64), 7, 3)
    private fun decision(camera: ChartViewport? = wanted, reason: ChartResidentCameraReason = ChartResidentCameraReason.ACCEPTED,
        count: Int? = 20, recovery: Boolean = false) = ChartResidentCameraDecision(camera, reason, count, 25,
        proposal.expectedCatalogGeneration, 7, 3, recoveryAboveBudget = recovery)

    @Test fun admissionAcceptsOnlyExactCountedCameraAndAuthority() {
        assertTrue(residentCameraDecisionValid(proposal, budget, decision()))
        assertTrue(residentCameraDecisionValid(proposal, budget, decision(basis, ChartResidentCameraReason.NO_ZOOM_ALTERNATIVE)))
        assertFalse(residentCameraDecisionValid(proposal, budget, decision(wanted.copy(longitude = -69.0))))
        assertFalse(residentCameraDecisionValid(proposal, budget, decision().copy(sourceGeneration = 8)))
        assertFalse(residentCameraDecisionValid(proposal, budget, decision().copy(surfaceGeneration = 4)))
        assertFalse(residentCameraDecisionValid(proposal, budget, decision().copy(cellBudget = 100)))
        assertFalse(residentCameraDecisionValid(proposal, budget, decision(count = null)))
        assertFalse(residentCameraDecisionValid(proposal, budget, decision(count = 26)))
        assertFalse(residentCameraDecisionValid(proposal, budget, decision().copy(conservative = false)))
    }

    @Test fun aboveBudgetRecoveryNeedsExplicitIntentAndReportedSpecialCase() {
        val zoomIn = proposal.copy(wanted = basis.copy(metresToPixels = .02), pureZoomInRecovery = true)
        val recovery = decision(zoomIn.wanted, ChartResidentCameraReason.RECOVERY_ABOVE_BUDGET, 30, true)
        assertTrue(residentCameraDecisionValid(zoomIn, budget, recovery))
        assertFalse(residentCameraDecisionValid(zoomIn.copy(pureZoomInRecovery = false), budget, recovery))
        assertFalse(residentCameraDecisionValid(zoomIn, budget, recovery.copy(recoveryAboveBudget = false)))
        assertFalse(residentCameraDecisionValid(zoomIn, budget, recovery.copy(reason = ChartResidentCameraReason.ACCEPTED)))
        assertFalse(residentCameraDecisionValid(zoomIn, budget, recovery.copy(requiredUpperBound = 20)))
    }

    @Test fun ordinaryOffCameraGetsOnlyExactCapacitySamplingAllowance() {
        val normal = ChartEngineStatus()
        assertTrue(residentNormalCameraAllowed(normal, false, false))
        assertFalse(residentNormalCameraAllowed(normal, true, false))
        val sampling = normal.copy(rendererBenchmark = RendererBenchmarkStatus(active = true,
            runKind = RendererRunKind.RESIDENT_CAPACITY, phase = "sampling", liveInputEnabled = true))
        assertTrue(residentNormalCameraAllowed(sampling, true, true))
        assertFalse(residentNormalCameraAllowed(sampling, true, false))
        for (phase in listOf("preparing", "restoring", "recovery")) assertFalse(residentNormalCameraAllowed(
            sampling.copy(rendererBenchmark = sampling.rendererBenchmark.copy(phase = phase)), true, true))
        for (kind in listOf(RendererRunKind.SCRIPTED, RendererRunKind.LIVE_INPUT)) assertFalse(residentNormalCameraAllowed(
            sampling.copy(rendererBenchmark = sampling.rendererBenchmark.copy(runKind = kind)), true, true))
        assertFalse(residentNormalCameraAllowed(sampling.copy(rendererBenchmark = sampling.rendererBenchmark.copy(liveInputEnabled = false)), true, true))
    }

    @Test fun nativeAnchorMathPreservesPanRotateWhenZoomIsRemoved() {
        val anchor = ScreenPoint(230.0, 370.0)
        val pan = ScreenPoint(70.0, -15.0)
        val full = residentTransformedCamera(basis, anchor, pan, .5, .3)
        val alternative = residentTransformedCamera(basis, anchor, pan, 1.0, .3)
        assertEquals(.005, full.metresToPixels, 0.0)
        assertEquals(basis.metresToPixels, alternative.metresToPixels, 0.0)
        assertEquals(full.rotationRadians, alternative.rotationRadians, 0.0)
        assertNotEquals(basis.longitude, alternative.longitude)
        val stationary = residentTransformedCamera(basis, ScreenPoint(500.0, 300.0))
        assertEquals(basis.latitude, stationary.latitude, 1e-12)
        assertEquals(basis.longitude, stationary.longitude, 1e-12)
        assertEquals(basis.metresToPixels, stationary.metresToPixels, 0.0)
        assertEquals(basis.rotationRadians, stationary.rotationRadians, 1e-12)
        assertEquals(basis, stationary)
        val clamped = basis.copy(metresToPixels = 10.0)
        assertEquals(clamped, residentTransformedCamera(clamped, anchor, zoom = 2.0))
    }
}
