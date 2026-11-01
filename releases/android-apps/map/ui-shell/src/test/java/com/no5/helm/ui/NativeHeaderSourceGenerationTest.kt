package com.no5.helm.ui

import android.view.Surface
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.no5.helm.chart.AisProjection
import com.no5.helm.chart.ChartEngine
import com.no5.helm.chart.ChartEngineConfig
import com.no5.helm.chart.ChartEngineStatus
import com.no5.helm.chart.ChartFeature
import com.no5.helm.chart.ChartViewport
import com.no5.helm.chart.EngineResult
import com.no5.helm.chart.OwnshipProjection
import com.no5.helm.chart.RouteProjection
import com.no5.helm.chart.S52Settings
import com.no5.helm.chart.ScreenPoint
import com.no5.helm.chart.WaypointProjection
import com.no5.helm.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Regression through the actual production Composable; no copied generation/deduplication logic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], qualifiers = "w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeHeaderSourceGenerationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<No5ImmersiveActivity>
    private val engine = FakeChartEngine()
    private val disconnected = HelmState(connection = ConnectionState.DISCONNECTED)
    private val backend = MutableStateFlow(disconnected)
    private val positions = MapPositionController(ChartPositionCamera(engine))
    private val sourceGeneration = mutableLongStateOf(7L)
    private val endpoint = "http://fixture.invalid:5000"
    private val contexts = ArrayList<NativeHeaderContext>()
    private val connections = ArrayList<String>()
    private val initialViewport = engine.status.value.viewport

    @Before fun setup() {
        activity = Robolectric.buildActivity(No5ImmersiveActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup()
        activity.get().setContent {
            NativeHelmScreen(engine = engine, backend = backend, endpoint = endpoint,
                chartLabel = "Synthetic header-generation fixture", initialViewport = initialViewport,
                positionController = positions,
                onHeaderContext = { contexts.add(it) },
                headerSourceGeneration = sourceGeneration.longValue,
                onConnect = { connections.add(it) })
        }
        compose.waitForIdle()
    }

    @After fun close() {
        if (::activity.isInitialized) activity.pause().stop().destroy()
        engine.close()
    }

    @Test fun sourceGenerationRepublishesUnchangedDisconnectedContextExactlyOncePerGeneration() {
        compose.onNodeWithTag("header").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(7L), contexts.map { it.sourceGeneration })
            assertSame("Backend flow is the original immutable DISCONNECTED state", disconnected, backend.value)
            assertSame(disconnected, contexts.single().backend)
            assertEquals(ConnectionState.DISCONNECTED, contexts.single().backend.connection)
            assertEquals("CHART", contexts.single().page)
            assertEquals("OFFLINE", contexts.single().transport)
            assertEquals("Helm-Gateway nicht verbunden", contexts.single().sourceDetail)
            assertTrue(connections.isEmpty())
        }

        // Only Compose State changes. No backend emission, new flow, endpoint or Screen remount.
        compose.runOnIdle { sourceGeneration.longValue = 8L }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(7L, 8L), contexts.map { it.sourceGeneration })
            val first = contexts[0]; val second = contexts[1]
            assertEquals("All actual header data besides source generation must be identical",
                first.copy(sourceGeneration = second.sourceGeneration), second)
            assertSame(disconnected, backend.value)
            assertSame(disconnected, second.backend)
        }

        // A real engine-flow emission re-enters NativeHelmScreen. A changed visible body warning
        // proves it executed; all header inputs are unchanged, so SideEffect must deduplicate.
        val firstWarning = "fixture renderer recomposition one"
        compose.runOnIdle { engine.status.value = engine.status.value.copy(message = firstWarning) }
        compose.waitForIdle()
        compose.onNodeWithText("Kartenframe wird aktualisiert · $firstWarning", substring = true).assertExists()
        compose.runOnIdle {
            assertEquals(listOf(7L, 8L), contexts.map { it.sourceGeneration })
            assertSame(disconnected, backend.value)
            assertTrue(connections.isEmpty())
        }

        // Repeat after another real screen recomposition, not merely another test waitForIdle().
        val secondWarning = "fixture renderer recomposition two"
        compose.runOnIdle { engine.status.value = engine.status.value.copy(message = secondWarning) }
        compose.waitForIdle()
        compose.onNodeWithText("Kartenframe wird aktualisiert · $secondWarning", substring = true).assertExists()
        compose.runOnIdle {
            assertEquals("No third/fourth callback for the same actual header context", 2, contexts.size)
            assertEquals(8L, contexts.last().sourceGeneration)
            assertSame(disconnected, contexts.last().backend)
            assertTrue(connections.isEmpty())
        }
    }

    @Test fun connectedMixedTelemetryKeepsOriginalBackendFenceAndSeparatelySelectedRealGps() {
        lateinit var original: HelmState
        var observed = 0L
        compose.runOnIdle {
            observed = System.currentTimeMillis()
            val gps = PositionCandidate("synthetic.real.gps", "synthetic.real.gps", "synthetic.real.gps",
                PositionStatus.LIVE, observed, GeoPoint(0.0, 0.0))
            original = HelmState(ConnectionState.LIVE, Snapshot(serverId = "synthetic-connected-source",
                revision = observed, generatedAt = java.time.Instant.ofEpochMilli(observed).toString(),
                state = HelmProjection(Ownship(GeoPoint(1.0, 1.0), null, null, null, null),
                    emptyList(), emptyList(), emptyList()), gatewayV1 = true,
                sourceDetail = "synthetic-overlay · SIMULATED · Position LIVE",
                positionSources = PositionSources(primarySourceId = gps.sourceId, total = 1, sources = listOf(gps)),
                gatewayTelemetryProvenance = GatewayTelemetryProvenance("synthetic-overlay", true)))
            positions.update(original, observed)
            backend.value = original
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val actual = contexts.last()
            assertSame("Production callback must carry the exact immutable backend", original, actual.backend)
            assertTrue(actual.matches(sourceGeneration.longValue, backend.value, positions.state.value))
            assertFalse(actual.matches(sourceGeneration.longValue + 1, backend.value))
            assertEquals("synthetic.real.gps", actual.position!!.sourceId)
            assertEquals(observed, actual.position!!.observedAtEpochMs)
            assertEquals(PositionStatus.LIVE, actual.position!!.status)
            assertEquals(NativeGpsProvenance.REAL, actual.position!!.provenance)
            assertTrue(actual.sourceDetail.endsWith("HELM simuliert"))
            assertTrue(actual.backend.snapshot!!.gatewayTelemetryProvenance!!.simulated)
            assertEquals(GeoPoint(1.0, 1.0), actual.backend.snapshot!!.state.ownship!!.position)
            assertEquals(GeoPoint(0.0, 0.0), positions.state.value.ownship!!.position)
        }
        compose.runOnIdle { sourceGeneration.longValue++ }
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame(original, contexts.last().backend)
            assertTrue(contexts.last().matches(sourceGeneration.longValue, original))
            assertFalse(contexts[contexts.lastIndex - 1].matches(sourceGeneration.longValue, original))
        }
    }

    /** The engine records no GLES work. UI, flow collection and production header callback stay real. */
    private class FakeChartEngine : ChartEngine {
        override val status = MutableStateFlow(ChartEngineStatus(initialized = true,
            chartLoaded = false, viewport = ChartViewport(42.36, -71.04, .15)))
        override suspend fun initialize(config: ChartEngineConfig) = EngineResult(true)
        override fun attachSurface(surface: Surface, width: Int, height: Int) = Unit
        override fun detachSurface() = Unit
        override fun setViewport(viewport: ChartViewport) {
            if (viewport != status.value.viewport) status.value = status.value.copy(viewport = viewport)
        }
        override fun setS52Settings(settings: S52Settings) = Unit
        override suspend fun loadChartSet(id: String, paths: List<String>) = EngineResult(false, "No chart in fixture")
        override suspend fun queryFeature(point: ScreenPoint): List<ChartFeature> = emptyList()
        override fun setOwnship(state: OwnshipProjection) = Unit
        override fun setAisTargets(targets: List<AisProjection>) = Unit
        override fun setRouteProjection(route: RouteProjection?) = Unit
        override fun setWaypointProjection(waypoints: List<WaypointProjection>) = Unit
        override fun close() = Unit
    }
}
