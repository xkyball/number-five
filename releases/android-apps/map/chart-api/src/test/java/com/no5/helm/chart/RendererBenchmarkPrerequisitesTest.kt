package com.no5.helm.chart

import org.junit.Assert.*
import org.junit.Test

class RendererBenchmarkPrerequisitesTest {
    private val ready = ChartEngineStatus(initialized = true, chartLoaded = true, surfaceAttached = true,
        loadedChartCells = RENDERER_BENCHMARK_CELLS.toList(), rendererBenchmarkDataAvailable = true)

    @Test fun noUserMapsAndConnecticutSelectionCanUseTheIndependentTestBundle() {
        assertNull(rendererBenchmarkStartBlocker(ready.copy(chartLoaded = false, loadedChartCells = emptyList())))
        assertNull(rendererBenchmarkStartBlocker(ready.copy(loadedChartCells = List(75) { "US5CT$it" })))
    }
    @Test fun missingTestBundleAndUnfinishedRecoveryExplainTheRequiredAction() {
        assertTrue(rendererBenchmarkStartBlocker(ready.copy(rendererBenchmarkDataAvailable = false))!!.contains("mitgelieferten Boston-Testkarten fehlen"))
        assertTrue(rendererBenchmarkStartBlocker(ready.copy(rendererBenchmarkRecoveryPending = true))!!.contains("Wiederherstellung erneut"))
    }
    @Test fun uninitializedDetachedPendingAndBusyEachHaveActionableBlocker() {
        assertTrue(rendererBenchmarkStartBlocker(ready.copy(initialized = false))!!.contains("vorbereitet"))
        assertTrue(rendererBenchmarkStartBlocker(ready.copy(surfaceAttached = false))!!.contains("Vordergrund"))
        assertTrue(rendererBenchmarkStartBlocker(ready.copy(presentationPending = true))!!.contains("Kartenstil"))
        assertTrue(rendererBenchmarkStartBlocker(ready, true)!!.contains("abschließen"))
        assertNotNull(rendererBenchmarkStartBlocker(ready.copy(rendererCapture = RendererCaptureStatus(active = true))))
        assertTrue(rendererBenchmarkStartBlocker(ready.copy(rendererBenchmark = RendererBenchmarkStatus(active = true)))!!.contains("bereits"))
    }
    @Test fun invalidViewportIsRejectedAndUserPositionNeedNotBeBoston() {
        assertNull(rendererBenchmarkStartBlocker(ready.copy(viewport = ChartViewport(48.0, 10.0, .2, 1.0))))
        for (view in listOf(ChartViewport(latitude = Double.NaN), ChartViewport(longitude = 181.0),
            ChartViewport(metresToPixels = 0.0), ChartViewport(rotationRadians = Double.NaN), ChartViewport(height = 1500))) {
            assertTrue(rendererBenchmarkStartBlocker(ready.copy(viewport = view))!!.contains("ungültig"))
        }
    }
}
