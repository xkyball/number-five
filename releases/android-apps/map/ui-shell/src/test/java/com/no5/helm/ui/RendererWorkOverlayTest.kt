package com.no5.helm.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.Surface
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.helm.chart.*
import com.no5.helm.library.ChartSelection
import com.no5.helm.model.HelmState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RendererWorkOverlayTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<No5ImmersiveActivity>
    private val camera = ChartViewport(42.2, -71.8, .1, width = 2000, height = 1070)
    private val frame = ChartFrame(7, "confirmed", ChartSurfaceGeometry.fit(2000, 1070), camera, ChartScale(120.0, 1000.0), sourceGeneration = 7)
    private val catalog = "a".repeat(64)
    private fun ready() = ChartEngineStatus(initialized = true, chartLoaded = true, surfaceAttached = true,
        presentationAvailable = true, confirmedFrame = frame, viewport = camera, residentCatalogActive = true,
        residentCatalogGeneration = catalog, chartSourceGeneration = 7, surfaceGeneration = 3)
    private fun resident() = ChartResidencyStatus(enabled = true, complete = true, catalogGeneration = catalog,
        cameraKey = "camera", policyKey = "policy", cameraRevision = 7, contentRevision = 1,
        resident = 3, loading = 0, pinned = 1, required = 3, missing = 0,
        chargedBytes = 24L * 1024 * 1024, geometryBytes = 24L * 1024 * 1024, loadingBytes = 0,
        peakCells = 3, peakChargedBytes = 24L * 1024 * 1024, loads = 3, retains = 1, evictions = 0,
        failures = 0, message = "", sources = emptyList(),
        budget = ChartResidentBudget(25, 128L * 1024 * 1024, 64L * 1024 * 1024), step = ChartResidentStep.IDLE)
    private fun decision(reason: ChartResidentCameraReason, required: Int? = null) = ChartResidentCameraDecision(
        acceptedCamera = null, reason = reason, requiredUpperBound = required, cellBudget = 25,
        catalogGeneration = catalog, sourceGeneration = 7, surfaceGeneration = 3)
    @Before fun before() {
        // Match MainActivity's real fullscreen host. A bare ComponentActivity leaves
        // system/action bars in the 2000×1200 fixture and invalidates its fixed frame.
        activity = Robolectric.buildActivity(No5ImmersiveActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup()
    }
    @After fun after() { activity.pause().stop().destroy() }

    @Test fun showsObservedLoadingAndTheActualBudgetWithoutInventingFramesOrObjectCompleteness() {
        val model = rendererWorkOverlayModel(ready().copy(featureCount = 999999), resident().copy(complete = false, missing = 2, loading = 1))
        assertEquals("Zellen nachladen", model.work)
        assertEquals("Aktuell: 3 geladen · 3 benötigt · 2 fehlen · 1 laden", model.cells)
        assertEquals("Budget: 25 Zellen · Geometrie 24.0/128.0 MiB", model.budget)
        assertFalse(model.toString().contains("999999"))
        assertFalse(model.toString().contains("FPS"))
    }

    @Test fun explicitNativeBlockAndFailureAreDistinctFromMissingCellsOrMessageText() {
        assertEquals("Nachladen wartet auf Budget", rendererWorkOverlayModel(ready(), resident().copy(step = ChartResidentStep.BLOCKED)).work)
        assertEquals("Fehler beim Zellenladen", rendererWorkOverlayModel(ready(), resident().copy(step = ChartResidentStep.FAILED)).work)
        val pending = resident().copy(step = null, complete = false, missing = 1, message = "blocked")
        assertEquals("Zellen nachladen", rendererWorkOverlayModel(ready(), pending).work)
    }

    @Test fun styleWorkImageWorkAndConfirmedIdleHaveSeparateObservableLabels() {
        assertEquals("Kartenstil aufbauen", rendererWorkOverlayModel(ready(), resident(), styleBusy = true).work)
        assertEquals("Kartenbild aufbauen", rendererWorkOverlayModel(ready().copy(presentationPending = true), resident()).work)
        assertEquals("Kartenbild aufbauen", rendererWorkOverlayModel(ready().copy(confirmedFrame = null), resident()).work)
        assertEquals("Aktueller Bildstand bestätigt", rendererWorkOverlayModel(ready(), resident()).work)
        assertEquals("Paket wird geprüft …", rendererWorkOverlayModel(ready(), resident(), libraryBusy = true, libraryProgress = "Paket wird geprüft …").work)
    }

    @Test fun unsupportedDriverNeverClaimsCompletionEvenWithAConfirmedFrameAndCompleteResidency() {
        val unsupported = ready().copy(sourcePresentationUnsupported = true)
        assertEquals("Grafiktreiber-Unterstützung fehlt", rendererWorkOverlayModel(unsupported, resident()).work)
        assertEquals("Grafiktreiber-Unterstützung fehlt", rendererWorkOverlayModel(unsupported, resident(), libraryBusy = true).work)
    }

    @Test fun unknownAndForeignCatalogStatisticsAreNeverPresentedAsAZeroOrDefault100Budget() {
        val unknown = rendererWorkOverlayModel(ready(), null)
        assertEquals("Zellenstatus noch unbekannt", unknown.work)
        assertEquals("Zellenstatus: unbekannt", unknown.cells)
        assertEquals("Budget: unbekannt", unknown.budget)
        assertFalse(unknown.toString().contains("100"))
        assertEquals(unknown, rendererWorkOverlayModel(ready(), resident().copy(catalogGeneration = "b".repeat(64))))
    }

    @Test fun clearingSourceOrLosingCatalogAuthorityRemovesOldResidencyCounters() {
        val missingAuthority = rendererWorkOverlayModel(ready().copy(residentCatalogGeneration = null), resident())
        assertEquals("Zellenstatus: unbekannt", missingAuthority.cells)
        assertEquals("Budget: unbekannt", missingAuthority.budget)
        val cleared = rendererWorkOverlayModel(ready().copy(chartLoaded = false, residentCatalogActive = false,
            residentCatalogGeneration = null, confirmedFrame = null, presentationAvailable = false), resident())
        assertEquals("Kein aktiver Kartenbestand", cleared.work)
        assertEquals("Zellenstatus: unbekannt", cleared.cells)
    }

    @Test fun cameraBlockRecoveryAndUnknownRemainTaggedAndUseNoInventedNeedCount() {
        val blocked = rendererWorkOverlayModel(ready().copy(residentCameraDecision = decision(ChartResidentCameraReason.BLOCKED, 31)), resident())
        assertEquals("Zoomgrenze: Bedarf > aktuelles Budget (konservativ)", blocked.work)
        val recovery = rendererWorkOverlayModel(ready().copy(residentCameraDecision = decision(ChartResidentCameraReason.RECOVERY_ABOVE_BUDGET).copy(recoveryAboveBudget = true)), resident())
        assertEquals("Noch über Zoom-Budget · weiter hineinzoomen", recovery.work)
        val unknown = rendererWorkOverlayModel(ready().copy(residentCameraDecision = decision(ChartResidentCameraReason.UNKNOWN)), null)
        assertEquals("Zoomprüfung: Zellbedarf unbekannt", unknown.work)
        assertEquals("Zellenstatus: unbekannt", unknown.cells)
        val stale = rendererWorkOverlayModel(ready().copy(residentCameraDecision = decision(ChartResidentCameraReason.BLOCKED, 31).copy(sourceGeneration = 6)), resident())
        assertEquals("Zoomprüfung: Status veraltet", stale.work)
        assertFalse(stale.work.contains("31"))
    }

    @Test fun pendingStartCannotClaimCompletionOrReuseOldDemandFromAConfirmedFrame() {
        val waiting = ready().copy(residentCameraReady = false, residentStartState = ChartResidentStartState.WAITING_SURFACE,
            residentCameraDecision = decision(ChartResidentCameraReason.ACCEPTED, 99))
        val model = rendererWorkOverlayModel(waiting, resident())
        assertEquals("Start: Zeichenfläche vorbereiten", model.work)
        assertEquals("Zellenstatus: unbekannt", model.cells)
        assertEquals("Budget: unbekannt", model.budget)
        assertFalse(model.toString().contains("99"))
        assertFalse(model.toString().contains("Fertig"))
        assertTrue(residentStartPending(waiting))
        val unsupported = waiting.copy(sourcePresentationUnsupported = true)
        assertEquals("Grafiktreiber-Unterstützung fehlt", rendererWorkOverlayModel(unsupported, resident()).work)
    }

    @Test fun startBlockShowsOnlyTheTaggedConservativeProposalAndItsActualCountBudget() {
        val blocked = ready().copy(residentCameraReady = false, residentStartState = ChartResidentStartState.BLOCKED,
            residentCameraDecision = decision(ChartResidentCameraReason.BLOCKED, 31))
        val model = rendererWorkOverlayModel(blocked, resident())
        assertEquals("Startausschnitt an Zellgrenze gesperrt", model.work)
        assertEquals("Vorgeschlagen: bis zu 31 Zellen (konservativ)", model.cells)
        assertEquals("Budget: 25 Zellen · Geometrie unbekannt", model.budget)
        for (wrong in listOf(decision(ChartResidentCameraReason.BLOCKED, 31).copy(sourceGeneration = 6),
            decision(ChartResidentCameraReason.BLOCKED, 31).copy(surfaceGeneration = 2),
            decision(ChartResidentCameraReason.BLOCKED, 31).copy(catalogGeneration = "b".repeat(64)))) {
            val stale = rendererWorkOverlayModel(blocked.copy(residentCameraDecision = wrong), resident())
            assertEquals("Zellenstatus: unbekannt", stale.cells)
            assertEquals("Budget: unbekannt", stale.budget)
        }
        val unknown = rendererWorkOverlayModel(blocked.copy(residentStartState = ChartResidentStartState.UNKNOWN,
            residentCameraDecision = decision(ChartResidentCameraReason.UNKNOWN, 31)), resident())
        assertEquals("Startausschnitt: Zellbedarf unbekannt", unknown.work)
        assertEquals("Zellenstatus: unbekannt", unknown.cells)
        assertEquals("Budget: 25 Zellen · Geometrie unbekannt", unknown.budget)
        val staleStart = rendererWorkOverlayModel(blocked.copy(residentStartState = ChartResidentStartState.STALE_AUTHORITY), resident())
        assertEquals("Startausschnitt: Prüfung veraltet", staleStart.work)
        assertEquals("Zellenstatus: unbekannt", staleStart.cells)
        assertEquals("Budget: unbekannt", staleStart.budget)
    }

    @Test fun readyStartStillRequiresLoadingAndImageConfirmationAndCompatibilityStaysIntact() {
        val admitted = ready().copy(residentCameraReady = true, residentStartState = ChartResidentStartState.READY)
        assertFalse(residentStartPending(admitted))
        assertNull(residentStartWork(admitted))
        assertEquals("Zellen nachladen", rendererWorkOverlayModel(admitted,
            resident().copy(complete = false, missing = 1, loading = 1)).work)
        assertEquals("Kartenbild aufbauen", rendererWorkOverlayModel(admitted.copy(confirmedFrame = null), resident()).work)
        assertEquals("Aktueller Bildstand bestätigt", rendererWorkOverlayModel(admitted, resident()).work)
        assertEquals("Startausschnitt noch nicht geprüft", rendererWorkOverlayModel(admitted.copy(residentCameraReady = false), resident()).work)
        assertEquals("Startausschnitt noch nicht geprüft", rendererWorkOverlayModel(admitted.copy(residentCameraReady = false,
            residentStartState = ChartResidentStartState.NOT_REQUIRED), resident()).work)
        assertEquals(rendererWorkOverlayModel(ready(), resident()), rendererWorkOverlayModel(admitted, resident()))
        assertFalse(residentStartPending(admitted.copy(residentCatalogActive = false, residentCameraReady = false,
            residentStartState = ChartResidentStartState.UNKNOWN)))
    }

    @Test fun pendingStartIsVisibleWithoutOverlayAndWithholdsOldScaleUntilAdmission() {
        activity.get().getSharedPreferences("native-screens-v1", Context.MODE_PRIVATE).edit()
            .putBoolean("rendererWorkOverlayEnabled", false).commit()
        val engine = OverlayEngine()
        activity.get().setContent {
            NativeHelmScreen(engine, MutableStateFlow(HelmState()), "http://localhost", "Havel", camera) {}
        }
        compose.onNodeWithTag("chart-scale-bar").assertIsDisplayed()
        compose.runOnIdle {
            engine.status.value = engine.status.value.copy(residentCameraReady = false,
                residentStartState = ChartResidentStartState.WAITING_SURFACE)
        }
        compose.onNodeWithTag("renderer-work-overlay").assertDoesNotExist()
        compose.onNodeWithTag("resident-chart-loading-phase").assertIsDisplayed().assertTextEquals("Start: Zeichenfläche vorbereiten")
        compose.onNodeWithTag("chart-scale-bar").assertDoesNotExist()
        compose.onNodeWithTag("map-dock-toggle").performClick()
        compose.onNodeWithTag("inspect-mode").assertIsNotEnabled()
        compose.onNodeWithText("LOADING · Start: Zeichenfläche vorbereiten").assertIsDisplayed()
        compose.runOnIdle {
            engine.status.value = engine.status.value.copy(residentCameraReady = true,
                residentStartState = ChartResidentStartState.READY)
        }
        compose.onNodeWithTag("resident-chart-loading").assertDoesNotExist()
        compose.onNodeWithTag("chart-warning").assertDoesNotExist()
        compose.onNodeWithTag("chart-scale-bar").assertIsDisplayed()
        compose.onNodeWithTag("inspect-mode").assertIsEnabled()
    }

    @Test fun settingsTogglePersistsAcrossScreenRecreationAndOverlayStaysBelowLegendAndScale() {
        val preferences = activity.get().getSharedPreferences("native-screens-v1", Context.MODE_PRIVATE)
        preferences.edit().remove("rendererWorkOverlayEnabled").commit()
        val engine = OverlayEngine()
        val library = object : ChartLibraryActions {
            override val state = MutableStateFlow(ChartLibraryUiState(residency = resident()))
            override fun importPackage(uri: Uri) = Unit
            override fun activate(selection: ChartSelection) = Unit
            override fun cancelImport() = Unit
        }
        val backend = MutableStateFlow(HelmState())
        activity.get().setContent { NativeHelmScreen(engine, backend, "http://localhost", "NOAA", camera, library) {} }
        val renderer = compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        assertEquals(frame.geometry.surfaceWidth.toFloat(), renderer.width, .5f)
        assertEquals(frame.geometry.surfaceHeight.toFloat(), renderer.height, .5f)
        compose.onNodeWithTag("renderer-work-overlay").assertDoesNotExist()
        navigate("nav-SETTINGS")
        compose.onNodeWithTag("renderer-work-overlay-switch").performScrollTo().assertIsOff().performClick()
        assertTrue(RendererWorkOverlayPreference(preferences).enabled)
        navigate("nav-CHART")
        compose.onNodeWithTag("renderer-work-overlay-phase").assertTextEquals("Aktueller Bildstand bestätigt")
        val overlay = compose.onNodeWithTag("renderer-work-overlay").fetchSemanticsNode().boundsInRoot
        val legend = compose.onNodeWithTag("chart-legend").fetchSemanticsNode().boundsInRoot
        assertTrue(overlay.top > legend.bottom)
        compose.onNodeWithTag("chart-scale-bar").assertExists()
        val scale = compose.onNodeWithTag("chart-scale-bar").fetchSemanticsNode().boundsInRoot
        assertTrue(overlay.top > scale.bottom)
        save("renderer-work-overlay-with-scale")
        compose.runOnIdle {
            activity.get().setContent { NativeHelmScreen(OverlayEngine(), backend, "http://localhost", "NOAA", camera, library) {} }
        }
        compose.onNodeWithTag("renderer-work-overlay").assertExists()
        navigate("nav-SETTINGS")
        compose.onNodeWithTag("renderer-work-overlay-switch").performScrollTo().assertIsOn()
    }

    @Test fun unsupportedDriverHasAnAlwaysVisibleKnobSafeWarningWithWorkOverlayDisabled() {
        val preferences = activity.get().getSharedPreferences("native-screens-v1", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("rendererWorkOverlayEnabled", false).commit()
        val engine = OverlayEngine()
        engine.status.value = engine.status.value.copy(sourcePresentationUnsupported = true)
        activity.get().setContent {
            NativeHelmScreen(engine, MutableStateFlow(HelmState()), "http://localhost", "NOAA", camera) {}
        }
        compose.onNodeWithTag("renderer-work-overlay").assertDoesNotExist()
        val warning = compose.onNodeWithTag("chart-warning")
        warning.assertIsDisplayed()
        compose.onNodeWithText("ERROR · $SOURCE_PRESENTATION_UNSUPPORTED_MESSAGE").assertIsDisplayed()
        compose.onNodeWithTag("chart-scale-bar").assertDoesNotExist()
        val bounds = warning.fetchSemanticsNode().boundsInRoot
        val map = compose.onNodeWithTag("map-area").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= map.left + map.width * .17f)
        assertTrue(bounds.right <= map.left + map.width * .83f)
        assertTrue(bounds.top >= map.top && bounds.bottom <= map.bottom)
        save("renderer-source-unsupported-warning-overlay-off")
    }

    private inner class OverlayEngine : ChartEngine {
        override val status = MutableStateFlow(ready())
        override suspend fun initialize(config: ChartEngineConfig) = EngineResult(true)
        override fun attachSurface(surface: Surface, width: Int, height: Int) {
            status.value = status.value.copy(surfaceAttached = true, confirmedFrame = frame.copy(
                geometry = ChartSurfaceGeometry.fit(width, height), viewport = camera.copy(width = width, height = height)))
        }
        override fun detachSurface() { status.value = status.value.copy(surfaceAttached = false) }
        override fun setViewport(viewport: ChartViewport) { status.value = status.value.copy(viewport = viewport) }
        override fun setS52Settings(settings: S52Settings) = Unit
        override suspend fun loadChartSet(id: String, paths: List<String>) = EngineResult(true)
        override suspend fun queryFeature(point: ScreenPoint): List<ChartFeature> = emptyList()
        override fun setOwnship(state: OwnshipProjection) = Unit
        override fun setAisTargets(targets: List<AisProjection>) = Unit
        override fun setRouteProjection(route: RouteProjection?) = Unit
        override fun setWaypointProjection(waypoints: List<WaypointProjection>) = Unit
        override suspend fun applyPresentationProfile(value: ChartPresentation): EngineResult {
            status.value = status.value.copy(presentation = value)
            return EngineResult(true)
        }
        override fun close() = Unit
    }

    private fun navigate(tag: String) {
        if (compose.onAllNodesWithTag("map-dock").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("map-dock-toggle").performClick()
        compose.onNodeWithTag(tag).performClick()
    }

    private fun save(name: String) = compose.runOnIdle {
        val view = activity.get().window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val output = File("build/ui-evidence/$name.png").apply { parentFile!!.mkdirs() }
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
