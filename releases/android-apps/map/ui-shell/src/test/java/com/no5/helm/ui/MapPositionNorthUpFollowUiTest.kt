package com.no5.helm.ui

import android.view.Surface
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.helm.chart.*
import com.no5.helm.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual production North-up UI binding, recording camera owner; no GPS/GLES/device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapPositionNorthUpFollowUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<No5ImmersiveActivity>

    @After fun close() {
        if (::activity.isInitialized) activity.pause().stop().destroy()
    }

    @Test fun explicitNorthUpUsesTheExistingCameraOwnerAndKeepsFollowForTheNextNewFix() {
        val engine = NorthUpEngine()
        val positions = MapPositionController(ChartPositionCamera(engine), PositionIntent(follow = true))
        fun fix(stamp: Long) = HelmState(ConnectionState.LIVE, Snapshot(serverId = "synthetic-north-up",
            revision = stamp, generatedAt = "synthetic", gatewayV1 = true,
            state = HelmProjection(null, emptyList(), emptyList(), emptyList()),
            positionSources = PositionSources(primarySourceId = "synthetic.gps", total = 1, sources = listOf(
                PositionCandidate("synthetic.gps", "synthetic.gps", "synthetic.gps", PositionStatus.LIVE,
                    stamp, GeoPoint(0.0, 0.0))))))
        var firstTime = 0L
        val backend = MutableStateFlow(HelmState())
        activity = Robolectric.buildActivity(No5ImmersiveActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup().visible().windowFocusChanged(true)
        val initialViewport=engine.status.value.viewport
        activity.get().setContent {
            NativeHelmScreen(engine, backend, "http://localhost", "Synthetic test chart", initialViewport,
                positionController = positions, onConnect = {})
        }
        compose.waitForIdle()
        compose.runOnIdle {
            firstTime = System.currentTimeMillis()
            backend.value = fix(firstTime)
            positions.update(backend.value, firstTime)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(PositionMode.FOLLOW, positions.state.value.mode)
            assertTrue(positions.state.value.followActive)
            assertEquals(1, engine.followCenters)
        }
        compose.onNodeWithTag("north-up").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, engine.northUps)
            assertEquals(0.0, engine.status.value.viewport.rotationRadians, 0.0)
            assertEquals(PositionMode.FOLLOW, positions.state.value.mode)
            assertTrue(positions.state.value.intent.follow)
            assertTrue(positions.state.value.followActive)
            assertEquals("North-up must not manufacture another positional fix", 1, engine.followCenters)
            val next = maxOf(firstTime + 1, System.currentTimeMillis())
            backend.value = fix(next)
            positions.update(backend.value, next)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("A new confirmed fix still follows after explicit North-up", 2, engine.followCenters)
            assertEquals(PositionMode.FOLLOW, positions.state.value.mode)
        }
    }

    private class NorthUpEngine : ChartEngine {
        private val initialViewport = ChartViewport(rotationRadians = 0.7)
        override val status = MutableStateFlow(ChartEngineStatus(initialized = true, chartLoaded = true,
            viewport = initialViewport, confirmedFrame = ChartFrame(1, "synthetic-north-up:1",
                ChartSurfaceGeometry.fit(2000, 1070), initialViewport, ChartScale(120.0, 500.0))))
        var northUps = 0
        var followCenters = 0
        override suspend fun centerCamera(position: GeoProjection, rotationRadians: Double?): Boolean {
            assertEquals(0.0, rotationRadians!!, 0.0)
            northUps++
            val viewport = status.value.viewport.copy(rotationRadians = rotationRadians)
            status.value = status.value.copy(viewport = viewport,
                confirmedFrame = status.value.confirmedFrame!!.copy(viewport = viewport))
            return true
        }
        override fun submitCameraControl(control: ChartCameraControl, amount: Double, sequence: Long,
            physicalUptimeMs: Long, latitude: Double, longitude: Double, homeScale: Double): Long {
            assertEquals(ChartCameraControl.CENTER, control)
            followCenters++
            return followCenters.toLong()
        }
        override fun cancelCameraControl() = Unit
        override suspend fun initialize(config: ChartEngineConfig) = EngineResult(true)
        override fun attachSurface(surface: Surface, width: Int, height: Int) = Unit
        override fun detachSurface() = Unit
        override fun setViewport(viewport: ChartViewport) { status.value = status.value.copy(viewport = viewport) }
        override suspend fun loadChartSet(id: String, paths: List<String>) = EngineResult(true)
        override suspend fun queryFeature(point: ScreenPoint) = emptyList<ChartFeature>()
        override fun setS52Settings(settings: S52Settings) = Unit
        override fun setOwnship(state: OwnshipProjection) = Unit
        override fun setAisTargets(targets: List<AisProjection>) = Unit
        override fun setRouteProjection(route: RouteProjection?) = Unit
        override fun setWaypointProjection(waypoints: List<WaypointProjection>) = Unit
        override fun close() = Unit
    }
}
