package com.no5.helm.chart

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChartResidentCapabilitiesTest {
    private class ExistingEngine : ChartEngine {
        override val status = MutableStateFlow(ChartEngineStatus())
        override suspend fun initialize(config: ChartEngineConfig) = EngineResult(true)
        override fun attachSurface(surface: Surface, width: Int, height: Int) = Unit
        override fun detachSurface() = Unit
        override fun setViewport(viewport: ChartViewport) = Unit
        override fun setS52Settings(settings: S52Settings) = Unit
        override suspend fun loadChartSet(id: String, paths: List<String>) = EngineResult(true)
        override suspend fun queryFeature(point: ScreenPoint) = emptyList<ChartFeature>()
        override fun setOwnship(state: OwnshipProjection) = Unit
        override fun setAisTargets(targets: List<AisProjection>) = Unit
        override fun setRouteProjection(route: RouteProjection?) = Unit
        override fun setWaypointProjection(waypoints: List<WaypointProjection>) = Unit
        override fun close() = Unit
    }
    @Test fun anExistingEngineDoesNotOptIntoNativeResidencyOrChangeItsEagerLoading() = runBlocking {
        val engine = ExistingEngine()
        assertFalse(engine.supportsResidentCatalog)
        val activation = ChartResidentActivation("set", ChartResidentCatalog("generation", emptyList(), ChartResidentBudget()),
            ChartViewport(), ChartNavigationBounds(0, emptyList()))
        assertFalse(engine.activateResidentCatalog(activation).result.success)
        assertTrue(engine.loadChartSet("set", listOf("cell.000")).success)
        assertFalse(engine.status.value.residentCatalogActive)
        assertEquals(-1L, engine.status.value.residentCheckpointRevision)
        assertNull(engine.status.value.chartScaleDenominator)
    }
    @Test fun priorFrameConstructionPreservesCompatibleSourceAndContentDefaults() {
        val frame = ChartFrame(1, "camera", ChartSurfaceGeometry.fit(2000, 1070), ChartViewport())
        assertEquals(0L, frame.sourceGeneration); assertEquals(0L, frame.contentGeneration)
    }
}
