package com.no5.helm.ui

import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.helm.chart.*
import com.no5.helm.model.HelmState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Full Compose shell and AndroidView routing; a recording engine replaces JNI/GLES only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1000dp-h600dp-land-mdpi")
class HelmScreenTouchTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val engine = RecordingEngine()

    @Before fun setup() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent { HelmScreen(engine, MutableStateFlow(HelmState()), "http://localhost:8765") {} }
        compose.waitForIdle()
    }

    @After fun close() { activity.pause().stop().destroy() }

    private fun pinch(from: Float, to: Float) {
        compose.onNodeWithTag("chart-input").performTouchInput {
            down(0, center + Offset(-from, 0f)); down(1, center + Offset(from, 0f))
            for (step in 1..12) {
                val distance = from + (to - from) * step / 12
                updatePointerTo(0, center + Offset(-distance, 0f))
                updatePointerTo(1, center + Offset(distance, 0f)); move()
            }
            up(0); up(1)
        }
        compose.waitForIdle()
    }

    @Test fun pinchTravelsThroughComposeIntoNativeView() {
        compose.onNodeWithTag("inspect-mode").performClick()
        pinch(80f, 160f)
        assertTrue(engine.status.value.viewport.metresToPixels > 0.28)
        pinch(160f, 80f)
        assertEquals(0.15, engine.status.value.viewport.metresToPixels, 1e-8)
        assertEquals(0, engine.queries)
        compose.onNodeWithText("Chart features").assertDoesNotExist()
    }

    @Test fun dragTravelsThroughComposeWithoutAnInfoDialog() {
        compose.onNodeWithTag("inspect-mode").performClick()
        compose.onNodeWithTag("chart-input").performTouchInput { swipe(center, center + Offset(120f, 0f), 300) }
        compose.waitForIdle()
        assertTrue(engine.status.value.viewport.longitude < -71.04)
        assertEquals(0.15, engine.status.value.viewport.metresToPixels, 1e-8)
        assertEquals(0, engine.queries)
        compose.onNodeWithText("Chart features").assertDoesNotExist()
    }

    @Test fun navigationTapsAreSilent() {
        repeat(3) { compose.onNodeWithTag("chart-input").performTouchInput { click(center) } }
        compose.waitForIdle()
        assertEquals(0, engine.queries)
        compose.onNodeWithText("Chart features").assertDoesNotExist()
    }

    @Test fun explicitObjectInfoIsOneShot() {
        compose.onNodeWithTag("inspect-mode").performClick()
        compose.onNodeWithTag("chart-input").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(1, engine.queries)
        compose.onNodeWithText("Chart features").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("chart-input").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(1, engine.queries)
        compose.onNodeWithText("Chart features").assertDoesNotExist()
    }

    @Test fun resetPreservesActualChartDimensions() {
        val before = engine.status.value.viewport
        assertTrue(before.width > 0 && before.height > 0)
        assertTrue(before.width != 2000 || before.height != 1200)
        pinch(80f, 160f)
        compose.onNodeWithText("Reset").performClick()
        compose.waitForIdle()
        val reset = engine.status.value.viewport
        assertEquals(before.width, reset.width); assertEquals(before.height, reset.height)
        assertEquals(.15, reset.metresToPixels, 1e-8)
    }

    @Test fun rendererProfileDelegatesOnceAndShowsUnsupportedError() {
        compose.onNodeWithTag("renderer-diagnostics").performClick()
        compose.onNodeWithTag("renderer-boston-profile").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, engine.profileRequests)
        compose.onNodeWithText("Renderer profiling is unavailable").assertExists()
        compose.onNodeWithText("Export profile JSON").assertDoesNotExist()
    }

    @Test fun successfulRendererProfileEnablesJsonExportWithoutQueryingChart() {
        engine.profileResult = EngineResult(true, "{\"schemaVersion\":1,\"measuredFrames\":30}")
        compose.onNodeWithTag("renderer-diagnostics").performClick()
        compose.onNodeWithText("Export profile JSON").assertDoesNotExist()
        compose.onNodeWithTag("renderer-boston-profile").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, engine.profileRequests)
        assertEquals(0, engine.queries)
        compose.onNodeWithText("Export profile JSON").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("renderer-boston-profile").assertIsEnabled()
    }

    private class RecordingEngine : ChartEngine {
        override val status = MutableStateFlow(ChartEngineStatus(initialized = true, chartLoaded = true,
            viewport = ChartViewport(42.36, -71.04, 0.15)))
        var queries = 0
        var profileRequests = 0
        var profileResult: EngineResult? = null
        override suspend fun profileRenderer(): EngineResult {
            profileRequests++
            return profileResult ?: super<ChartEngine>.profileRenderer()
        }
        override fun setViewport(viewport: ChartViewport) { status.value = status.value.copy(viewport = viewport) }
        override suspend fun queryFeature(point: ScreenPoint): List<ChartFeature> {
            queries++
            return listOf(ChartFeature("test-buoy", "Test buoy", mapOf("CATSPM" to "1")))
        }
        override suspend fun initialize(config: ChartEngineConfig) = EngineResult(true)
        override suspend fun loadChartSet(id: String, paths: List<String>) = EngineResult(true)
        override fun attachSurface(surface: Surface, width: Int, height: Int) = Unit
        override fun detachSurface() = Unit
        override fun setS52Settings(settings: S52Settings) = Unit
        override fun setOwnship(state: OwnshipProjection) = Unit
        override fun setAisTargets(targets: List<AisProjection>) = Unit
        override fun setRouteProjection(route: RouteProjection?) = Unit
        override fun setWaypointProjection(waypoints: List<WaypointProjection>) = Unit
        override fun close() = Unit
    }
}
