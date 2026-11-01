package com.no5.helm.engine

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test

class CurrentResidentLiveLeaseTest {
    private val a = currentResidentLiveFixture()
    private val capture = Any()
    private val state = ChartEngineStatus(rendererCapture = RendererCaptureStatus(active = true),
        rendererBenchmark = RendererBenchmarkStatus(active = true, runId = "run", runKind = RendererRunKind.LIVE_INPUT, phase = "warming"))
    @Test fun onlyExactOwnedCaptureRunAndWarmingLivePhaseComposeWithResidentWork() {
        val lease = CurrentResidentLiveLease("run", a, capture)
        fun allowed(s: ChartEngineStatus = state, id: String? = "run", owned: Any? = capture,
            exact: Boolean = true, closed: Boolean = false, profiling: Boolean = false, cancelled: Boolean = false) =
            lease.allows(s, id, owned, exact, closed, profiling, cancelled)
        assertTrue(allowed()); assertFalse(allowed(id = "other")); assertFalse(allowed(owned = Any()))
        assertFalse(allowed(exact = false)); assertFalse(allowed(closed = true)); assertFalse(allowed(profiling = true)); assertFalse(allowed(cancelled = true))
        assertFalse(allowed(s = state.copy(rendererCapture = RendererCaptureStatus())))
        assertFalse(allowed(s = state.copy(rendererBenchmarkRecoveryPending = true)))
        assertFalse(allowed(s = state.copy(rendererBenchmark = state.rendererBenchmark.copy(runKind = RendererRunKind.RESIDENT_CAPACITY))))
        assertFalse(lease.cameraAllowed(state))
        lease.phase = "live_input"
        val live = state.copy(rendererBenchmark = state.rendererBenchmark.copy(phase = "live_input", liveInputEnabled = true))
        assertTrue(allowed(live)); assertTrue(lease.cameraAllowed(live))
        assertTrue(residentNormalCameraAllowed(live, true, false, true))
        assertTrue(residentLiveCameraAllowed(live, RendererRunKind.LIVE_INPUT, false, true))
        assertFalse(residentNormalCameraAllowed(live, true, false, false))
        lease.phase = "restoring"; assertFalse(allowed(live))
    }

    @Test fun everyFrozenDataSourcePolicyStyleAndBudgetChangeFailsWithoutRelabellingCamera() {
        val i = a.identity
        for (changed in listOf(i.copy(rendererSession = "00000000-0000-4000-8000-000000000002"),
            i.copy(catalog = i.catalog.copy(generation = "b".repeat(64))), i.copy(catalog = i.catalog.copy(budget = ChartResidentBudget(26))),
            i.copy(source = 3), i.copy(surface = 5), i.copy(content = 7), i.copy(nativeData = 9), i.copy(nativeContent = 11),
            i.copy(presentationGeneration = 13), i.copy(policyKey = "new-policy"), i.copy(settings = i.settings.copy(safetyContourMetres = 4.0)),
            i.copy(requestedSettings = i.requestedSettings.copy(safetyContourMetres = 4.0)))) {
            val lease = CurrentResidentLiveLease("run", a, capture)
            assertTrue(lease.matches(i)); assertFalse(lease.matches(changed)); lease.invalidate()
            assertFalse(lease.matches(i)); assertEquals("SOURCE_FREEZE_LOST", lease.failure)
            assertEquals(14L, a.cameraRevision); assertEquals(2L, a.identity.source)
        }
    }
}
