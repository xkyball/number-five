package com.no5.helm

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test

/** Pure test-APK predicate tests: no Activity, clock replacement, engine setter or native call. */
class RendererReleaseReadinessGateTest {
    private val start = 1_000L
    private val gate get() = RendererReleaseReadinessGate(start)
    private fun ready(): ChartEngineStatus {
        val view = ChartViewport(width = 1000, height = 600)
        return ChartEngineStatus(initialized = true, chartLoaded = true, surfaceAttached = true,
            viewport = view, confirmedFrame = ChartFrame(12, "fixture", ChartSurfaceGeometry.fit(1000,600), view,
                sourceGeneration = 3, contentGeneration = 4), chartSourceGeneration = 3, chartContentGeneration = 4,
            surfaceGeneration = 7, presentationGeneration = 8, residentCatalogActive = true,
            residentCatalogGeneration = "fixture-catalog", residentCameraReady = true, residentStartState = ChartResidentStartState.READY)
    }
    private fun refused(block: () -> Unit) {
        try { block(); fail("Must refuse without acquiring/cancelling an owner") } catch (_: IllegalStateException) { }
    }
    @Test fun engineFullBeforeCompositionIsNotEnoughAndSameBudgetAllowsActualWarningRemoval() {
        val g = gate; val state = ready()
        assertTrue(g.engineReady(state, start + 100))
        val warning = RendererReleaseReadinessUi(true, setOf("chart-warning"), warningTexts = listOf("Loading"))
        assertFalse(g.ready(state, warning, state, start + 100))
        assertTrue(g.ready(state, RendererReleaseReadinessUi(rendererPresent = true), state, start + 116))
        assertEquals(start + 60_000, g.deadline)
    }
    @Test fun trueCameraReadyWithPendingStartStateRemainsRefusedWithoutChangingFlags() {
        val g = gate
        for (kind in listOf(ChartResidentStartState.WAITING_SURFACE, ChartResidentStartState.BLOCKED,
            ChartResidentStartState.UNKNOWN, ChartResidentStartState.STALE_AUTHORITY)) {
            val state = ready().copy(residentStartState = kind)
            assertFalse(g.engineReady(state, start + 1))
            assertFalse(g.ready(state, RendererReleaseReadinessUi(true), state, start + 1))
            assertTrue(state.residentCameraReady)
        }
        assertTrue(g.engineReady(ready().copy(residentStartState = ChartResidentStartState.NOT_REQUIRED), start + 1))
    }
    @Test fun persistentWarningAndLoadingOrNoRendererNeverBecomeUncoveredAtDeadline() {
        val g = gate; val state = ready()
        for (tag in listOf("chart-warning", "resident-chart-loading")) {
            val ui = RendererReleaseReadinessUi(true, setOf(tag))
            assertFalse(g.ready(state, ui, state, g.deadline - 1))
            refused { g.ready(state, ui, state, g.deadline) }
        }
        assertFalse(g.ready(state, RendererReleaseReadinessUi(), state, start + 1))
    }
    @Test fun foreignOwnerRecoveryOrCaptureAtEitherSideIsImmediateRefusal() {
        val g = gate; val state = ready(); val ui = RendererReleaseReadinessUi(true)
        for (foreign in listOf(state.copy(rendererBenchmark = state.rendererBenchmark.copy(active = true)),
            state.copy(rendererBenchmarkRecoveryPending = true), state.copy(rendererCapture = state.rendererCapture.copy(active = true)))) {
            refused { g.ready(foreign, ui, state, start + 1) }
            refused { g.ready(state, ui, foreign, start + 1) }
        }
    }
    @Test fun userDialogPanelDockAndSwitcherCannotBeWaitedAwayOrClosed() {
        val g = gate; val state = ready()
        for (tag in listOf("screen-panel", "object-info-panel", "map-dock", "app-switcher"))
            refused { g.ready(state, RendererReleaseReadinessUi(true, setOf(tag)), state, start + 1) }
        refused { g.ready(state, RendererReleaseReadinessUi(true, dialogPresent = true), state, start + 1) }
    }
    @Test fun deadlinePassedDuringUiDrainKeepsOriginalDeadlineAndNeverRenewsBudget() {
        val g = gate; val state = ready()
        assertTrue(g.engineReady(state, g.deadline - 1))
        refused { g.ready(state, RendererReleaseReadinessUi(true), state, g.deadline + 1) }
        assertEquals(start + 60_000, g.deadline)
    }
    @Test fun sourceSurfaceContentPresentationViewportOrFrameChangesCannotReuseEarlierReadyState() {
        val g = gate; val before = ready(); val ui = RendererReleaseReadinessUi(true)
        val changes = listOf(before.copy(chartSourceGeneration = 5), before.copy(surfaceGeneration = 9),
            before.copy(chartContentGeneration = 6), before.copy(presentationGeneration = 10),
            before.copy(residentCatalogGeneration = "new-catalog"), before.copy(viewport = before.viewport.copy(metresToPixels = .1)),
            before.copy(confirmedFrame = before.confirmedFrame!!.copy(revision = 13)))
        changes.forEach { after -> assertFalse(g.ready(before, ui, after, start + 1)) }
        assertTrue(g.ready(before, ui, before, start + 1))
    }
    @Test fun oversizedOrUnknownSemanticSnapshotFailsClosedWithoutLooseningCoverList() {
        val g = gate; val state = ready()
        refused { g.ready(state, RendererReleaseReadinessUi(true, boundsExceeded = true), state, start + 1) }
        refused { g.ready(state, RendererReleaseReadinessUi(true, setOf("unknown")), state, start + 1) }
    }
}
