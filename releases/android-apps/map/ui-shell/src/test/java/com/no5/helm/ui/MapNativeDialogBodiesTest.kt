package com.no5.helm.ui

import android.content.Context
import android.content.res.AssetManager
import android.graphics.SurfaceTexture
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.helm.chart.*
import com.no5.helm.model.ConnectionState
import com.no5.helm.model.GeoPoint
import com.no5.helm.model.HelmProjection
import com.no5.helm.model.HelmState
import com.no5.helm.model.SignalKRouteActions
import com.no5.helm.model.SignalKRoutePoint
import com.no5.helm.model.SignalKRouteResource
import com.no5.helm.model.SignalKRoutesState
import com.no5.helm.model.Snapshot
import com.no5.ui.No5LocaleScope
import com.no5.ui.No5Text
import com.no5.ui.UiLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Opens the production NativeHelmScreen dialogs through their actual controls.
 * Only the ChartEngine and read-only backend are fixtures: no dialog body,
 * theme, font, bounds, or reference is replaced. Native host drawing is not a
 * claim about a physical IME, hardware buttons, GLES, or a navigation chart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapNativeDialogBodiesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<No5ImmersiveActivity>? = null
    private var nativeView: ChartSurfaceView? = null
    private var texture: SurfaceTexture? = null
    private var originalFont = 1f
    private lateinit var originalLanguage: String
    private lateinit var engine: DialogRecordingEngine
    private lateinit var routeActions: ReadOnlyRouteFixture
    private val useControlGateway = mutableStateOf(true)
    private var controlOpens = 0
    private var connectCalls = 0
    private var eventTime = 1_000L
    private var downTime = 1_000L

    @Before fun rememberConfiguration() {
        originalFont = RuntimeEnvironment.getFontScale()
        originalLanguage = UiLanguage.current(RuntimeEnvironment.getApplication())
    }

    @After fun restoreConfiguration() {
        closeHost()
        UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(), originalLanguage)
        RuntimeEnvironment.setFontScale(originalFont)
    }

    @Test fun nativeProductionDialogsFitAtNormalFont() = dialogMatrix(1f)
    @Test fun nativeProductionDialogsFitAtLargeFont() = dialogMatrix(1.25f)

    private fun dialogMatrix(fontScale: Float) {
        // A Dialog owns another AndroidComposeView. Set the actual Android
        // font configuration before either Activity or Dialog is constructed.
        RuntimeEnvironment.setFontScale(fontScale)
        STATIC_DETAIL_KEYS.forEach { key ->
            assertTrue("Every fixed native detail label must exist in the shared translation table: $key",
                No5Text.hasTranslation(key))
        }
        for (language in listOf("de", "en", "af")) for (redNight in listOf(false, true)) {
            try {
                launch(language)
                tapTag(if (redNight) "palette-toggle" else "palette-day")
                compose.waitUntil(5_000) {
                    (engine.status.value.presentation.mode == PresentationMode.RED_NIGHT) == redNight
                }
                assertEquals(fontScale, host.resources.configuration.fontScale, 0f)
                val rendererBounds = compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot

                openDiagnostics(language)
                tapNative("no5-row-map-diagnostics-renderer")
                compose.onNodeWithTag("renderer-workspace").assertIsDisplayed()
                compose.runOnIdle {
                    val workspace=nativeViews(host.window.decorView).last{it.tag=="no5-workspace"}
                    assertTrue(workspace.contentDescription.toString().contains(text(language,"Rendererdiagnose")))
                    assertTrue(workspace.contentDescription.toString().contains("ARM64"))
                }
                tapNative("no5-row-renderer-driver")
                probe("native-renderer", language, fontScale, redNight).verify(
                    text(language,"Treiber / Fläche"), listOf(text(language,"Schließen")))
                closeTopDialog(language)
                assertTrue("The actual renderer reader was exercised", engine.diagnosticsReads > 0)
                assertEquals("Layout inspection starts no measurement", 0, engine.measurementStarts)

                tapNative("no5-row-renderer-measurements")
                probe("native-renderer-measurement",language,fontScale,redNight).verify(
                    text(language,"Messung"),listOf(text(language,"Report exportieren"),text(language,"Zurück")))
                compose.onNodeWithContentDescription(text(language,"Zurück")).performClick()
                compose.waitForIdle()
                assertEquals("Viewing every actual measurement control starts no run",0,engine.measurementStarts)

                // The actual NOAA body reads the app's pinned assets. A missing
                // asset fixture fails here instead of passing its fallback text.
                tapNative("no5-row-renderer-notices")
                val provenance = readProductionNoaaBody()
                compose.onNodeWithContentDescription(provenance, useUnmergedTree = true).assertExists()
                probe("native-noaa-sources", language, fontScale, redNight).verify(
                    text(language, "NOAA-Testkarten"),
                    listOf(text(language, "Schließen")), setOf(provenance))
                closeTopDialog(language)
                compose.runOnIdle{assertTrue(nativeViews(host.window.decorView).any{it.tag=="no5-action-renderer-report-export"})}
                tapNative("no5-action-renderer-back")

                // MainActivity supplies onHomeGateway, so this is the ordinary
                // deployed route, without opening Control or sending a command.
                openGateway(language)
                probe("native-gateway-control", language, fontScale, redNight).verify(
                    text(language, "Diagnose · Helm-Gateway"),
                    listOf(text(language, "In Lumina Control einrichten")))
                dismissTopDialog()
                assertEquals(0, controlOpens)

                // The public component API also supports a local address. Use
                // the real edit field and signed keyboard geometry contract;
                // no private state hook or copied editor is involved.
                compose.runOnIdle { useControlGateway.value = false }
                openGateway(language)
                val draft = "https://synthetic-gateway.invalid:8443/retained-draft"
                compose.onNode(hasSetTextAction()).performTextReplacement(draft)
                probe("native-gateway-address", language, fontScale, redNight).verify(
                    text(language, "Diagnose · Helm-Gateway"), listOf(text(language, "Verbinden")))
                val fullHeight = topDialogContentBounds().bottom
                publishSpecialKeyboard(440)
                probe("native-gateway-address-special-keyboard", language, fontScale, redNight).verify(
                    text(language, "Diagnose · Helm-Gateway"), listOf(text(language, "Verbinden")))
                val keyboardTop=1200f-440f
                assertTrue("Every compact dialog action remains above the signed keyboard",topDialogContentBounds().bottom<=keyboardTop)
                if(fullHeight>keyboardTop)assertTrue("Only an obstructed dialog moves/shortens",topDialogContentBounds().bottom<fullHeight)
                else assertEquals("A compact dialog that already fits must not be squeezed",fullHeight,topDialogContentBounds().bottom,.1f)
                assertEquals("The physical renderer stage does not rescale for the keyboard", rendererBounds,
                    compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot)
                compose.onNode(hasSetTextAction()).assertTextContains(draft)
                publishSpecialKeyboard(0)
                compose.onNode(hasSetTextAction()).assertTextContains(draft)
                assertEquals("Keyboard dismissal restores available dialog height", fullHeight,
                    topDialogContentBounds().bottom, .1f)
                dismissTopDialog()
                assertEquals("Inspecting the address never connects", 0, connectCalls)

                verifyObjectDetails(language, fontScale, redNight)
                verifyOverlayHits(language, fontScale, redNight)
                verifyRouteDelete(language, fontScale, redNight)
                assertEquals("Opening dialogs keeps one native Surface owner", 1, engine.attaches)
                assertEquals("No layout check starts or stops a real renderer run", 0, engine.measurementStarts)
                assertEquals("No layout check sends a route write or deletion", 0, routeActions.writes)
            } finally {
                closeHost()
            }
        }
    }

    private fun launch(language: String) {
        UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(), language)
        val controller = Robolectric.buildActivity(No5ImmersiveActivity::class.java)
        activity = controller
        host.setTheme(android.R.style.Theme_Material_NoActionBar)
        controller.setup().visible().windowFocusChanged(true)
        // These are isolated Robolectric app directories, never installed MAP
        // state. Each matrix cell starts without another cell's saved page.
        listOf("native-screens-v1", "chart-presentation-v1", "map-position-v1").forEach {
            host.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        host.filesDir.resolve("route-data").deleteRecursively()
        attachProductionAssets()
        engine = DialogRecordingEngine()
        routeActions = ReadOnlyRouteFixture()
        useControlGateway.value = true
        controlOpens = 0
        connectCalls = 0
        val waypoints = List(12) { index -> com.no5.helm.model.Waypoint("synthetic-waypoint-$index",
            "SYNTHETIC · retained waypoint $index", GeoPoint(42.36, -71.04)) }
        val snapshot = Snapshot(serverId = "synthetic-dialog-fixture", revision = 1, generatedAt = "2026-10-18T00:00:00Z",
            state = HelmProjection(null, listOf(com.no5.helm.model.AisTarget(
                211123456L, "SYNTHETIC · DIALOG TARGET", GeoPoint(42.36, -71.04), 0.0, 0.0, 0.0)), emptyList(), waypoints))
        val backend = MutableStateFlow(HelmState(ConnectionState.LIVE, snapshot))
        val viewport = engine.status.value.viewport
        host.setContent { No5LocaleScope(language) {
            NativeHelmScreen(engine, backend, ENDPOINT, "SYNTHETIC · no chart data", viewport,
                onHomeGateway = if (useControlGateway.value) ({ controlOpens++ }) else null,
                routeActions = routeActions, onConnect = { connectCalls++ })
        } }
        compose.waitForIdle()
        compose.runOnIdle {
            fun find(view: View): ChartSurfaceView? = if (view is ChartSurfaceView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val surface = requireNotNull(find(host.window.decorView))
            nativeView = surface
            val producer = SurfaceTexture(0)
            texture = producer
            checkNotNull(surface.surfaceTextureListener).onSurfaceTextureAvailable(producer, surface.width, surface.height)
        }
        compose.waitForIdle()
    }

    private fun nativeViews(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{nativeViews(v.getChildAt(it))}else emptyList()
    private fun tapNative(tag:String){compose.runOnIdle{nativeViews(host.window.decorView).last{it.tag==tag}.also{assertTrue(it.isEnabled)}.performClick()};compose.waitForIdle()}

    private fun openDiagnostics(language: String) {
        navigate("SETTINGS")
        tapNative("no5-row-map-settings-diagnostics")
    }

    private fun openGateway(language: String) {
        tapNative("no5-row-map-diagnostics-gateway")
        compose.waitForIdle()
    }

    private fun verifyObjectDetails(language: String, fontScale: Float, redNight: Boolean) {
        navigate("AIS")
        compose.onNodeWithTag("ais-target-211123456").performScrollTo().performClick()
        compose.onNodeWithText(text(language, "Letzte Position auf Karte")).performScrollTo().performClick()
        compose.onNodeWithTag("target-overlay").assertIsDisplayed()
        compose.onNodeWithText(text(language, "DETAILS")).performClick()
        compose.onNode(hasContentDescription(text(language, "Letzte gemeldete Position · Messalter und Qualität unbekannt"),substring=true) and
            hasAnyAncestor(hasTestTag("map-object-details")), useUnmergedTree = true).assertExists()
        probe("native-object-details", language, fontScale, redNight).verify(
            text(language, "Objektdetails"), listOf(text(language, "Schließen")))
        closeTopDialog(language)
    }

    private fun verifyRouteDelete(language: String, fontScale: Float, redNight: Boolean) {
        navigate("ROUTES")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("route-open-local-dialog-route").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("route-open-local-dialog-route").performScrollTo().performClick()
        compose.onNodeWithTag("route-delete").performScrollTo().performClick()
        probe("native-route-delete", language, fontScale, redNight).verify(
            text(language, "Route in Signal K löschen?"),
            listOf(text(language, "Abbrechen"), text(language, "Löschen")))
        compose.onNodeWithContentDescription(text(language, "Abbrechen")).performClick()
        assertEquals(0, routeActions.writes)
        compose.onNodeWithTag("route-shown-name").assertTextEquals(ROUTE_NAME)
    }

    private fun verifyOverlayHits(language: String, fontScale: Float, redNight: Boolean) {
        // The recording engine projects the real backend overlay models to
        // the free center of the Surface. A real native tap invokes the production
        // adapter's hit test, not its private callback or remembered state.
        // (100,100) is under the actual scale bar at stage y196..258 and is
        // deliberately excluded from chart input; do not bypass that contract.
        val chart = compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        val local = Offset(OVERLAY_X.toFloat(), OVERLAY_Y.toFloat())
        val surface = requireNotNull(nativeView)
        assertTrue("The real chart is in object-inspection mode", surface.inspectEnabled)
        assertFalse("The fixture must tap free chart pixels, not a real UI exclusion",
            surface.blockedRegions.any { it.contains(local) })
        val point = Offset(chart.left + local.x, chart.top + local.y)
        val projectionsBefore = engine.projectionCalls
        dispatchTouch(MotionEvent.ACTION_DOWN, point)
        dispatchTouch(MotionEvent.ACTION_UP, point)
        assertTrue("The native tap must reach the adapter's actual overlay projection query",
            engine.projectionCalls > projectionsBefore)
        compose.onNodeWithTag("overlay-hit-panel").assertExists()
        listOf("AIS · Messalter unbekannt", "Quell-Wegpunkt").forEach { key ->
            assertTrue("The opened overlay body must contain its translated kind in $language: $key",
                compose.onAllNodes(hasContentDescription(text(language, key), substring = true) and
                    hasAnyAncestor(hasTestTag("overlay-hit-panel")), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty())
        }
        probe("native-overlay-hits", language, fontScale, redNight).verify(
            text(language, "Overlays an diesem Punkt"),
            listOf(text(language, "Schließen"), text(language, "Kartenobjekte hier prüfen")))
        closeTopDialog(language)
    }

    private fun navigate(destination: String) {
        if (compose.onAllNodesWithTag("map-dock").fetchSemanticsNodes().isEmpty()) {
            val action = compose.onNodeWithTag("no5-shell").fetchSemanticsNode()
                .config[SemanticsActions.CustomActions].single()
            compose.runOnIdle { assertTrue("The real shell accessibility menu action opens navigation", action.action()) }
            compose.waitForIdle()
        }
        tapTag("nav-$destination")
    }

    private fun tapTag(tag: String) {
        val scope=File(System.getProperty("no5.renderOutputDir"),"actual-window-input").apply{mkdirs()}
        val root=host.window.decorView
        File(scope,"focus.txt").appendText("$tag font=${host.resources.configuration.fontScale} activity=${host.hasWindowFocus()} root=${root.hasWindowFocus()} lifecycle=${host.lifecycle.currentState} showingDialogs=${ShadowDialog.getShownDialogs().count{it.isShowing}}\n")
        val point = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot.center
        dispatchTouch(MotionEvent.ACTION_DOWN, point)
        dispatchTouch(MotionEvent.ACTION_UP, point)
    }

    private fun dispatchTouch(action: Int, point: Offset) {
        compose.runOnIdle {
            eventTime += 32
            if (action == MotionEvent.ACTION_DOWN) downTime = eventTime
            val event = MotionEvent.obtain(downTime, eventTime, action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 7; toolType = MotionEvent.TOOL_TYPE_FINGER }),
                arrayOf(MotionEvent.PointerCoords().apply { x = point.x; y = point.y; pressure = 1f; size = 1f }),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue("Native Activity dispatches actual touchscreen events", host.dispatchTouchEvent(event)) }
            finally { event.recycle() }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun probe(case: String, language: String, fontScale: Float, redNight: Boolean) =
        D03MapDialogLayoutProbe(compose, host, case, language, fontScale, redNight)

    private fun topDialogContentBounds() = compose.onAllNodesWithTag("map-connection")
        .fetchSemanticsNodes().last().boundsInRoot

    private fun closeTopDialog(language: String) {
        compose.onAllNodesWithContentDescription(text(language, "Schließen")).onLast().performClick()
        compose.waitForIdle()
    }

    private fun dismissTopDialog() {
        compose.runOnIdle { ShadowDialog.getShownDialogs().last { it.isShowing }.onBackPressed() }
        compose.waitForIdle()
    }

    private fun publishSpecialKeyboard(bottom: Int) {
        compose.runOnIdle {
            com.no5.keyboard.SpecialKeyboardGeometry.publish(host, host.packageName,
                if (bottom == 0) null else android.graphics.Rect(353, 1200 - bottom, 1656, 1200))
        }
        compose.waitForIdle()
    }

    private fun attachProductionAssets() {
        val directory = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .flatMap { sequenceOf(it.resolve("app/src/main/assets"), it.resolve("map/app/src/main/assets")) }
            .firstOrNull { it.resolve("renderer-benchmark/boston-v1/PROVENANCE.json").isFile }
            ?: error("The real MAP app assets must be available for the native NOAA dialog")
        // :ui-shell does not package :app assets. API29's native resource host
        // accepts APK-shaped ZIPs, not loose asset directories. Package the
        // three original files byte-for-byte into this isolated host cache;
        // the production composable still opens them through AssetManager.
        val archive = host.cacheDir.resolve("native-dialog-noaa-assets.zip")
        val names = listOf("README.TXT", "USERAGREEMENT.TXT", "PROVENANCE.json")
        ZipOutputStream(archive.outputStream()).use { zip ->
            names.forEach { name ->
                val relative = "renderer-benchmark/boston-v1/$name"
                zip.putNextEntry(ZipEntry("assets/$relative"))
                directory.resolve(relative).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val add = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply { isAccessible = true }
        val cookie = add.invoke(host.assets, archive.absolutePath) as Int
        assertTrue("The original app assets must be installed", cookie > 0)
        names.forEach { name ->
            val relative = "renderer-benchmark/boston-v1/$name"
            assertArrayEquals("NOAA provenance must be byte-identical to the app source: $name",
                directory.resolve(relative).readBytes(), host.assets.open(relative).use { it.readBytes() })
        }
        readProductionNoaaBody() // Fail immediately if native assets cannot be opened.
    }

    private fun readProductionNoaaBody(): String =
        No5Text.text(host,"Quelle: https://www.charts.noaa.gov/ENCs/ · Repository-Stand 03.10.2026")+"\n\n" +
            listOf("README.TXT", "USERAGREEMENT.TXT", "PROVENANCE.json").joinToString("\n\n") { name ->
                host.assets.open("renderer-benchmark/boston-v1/$name").bufferedReader().use { it.readText() }
            }

    private fun closeHost() {
        val controller = activity ?: return
        compose.runOnIdle {
            com.no5.keyboard.SpecialKeyboardGeometry.publish(controller.get(), controller.get().packageName, null)
            val surface = nativeView
            val producer = texture
            if (surface?.renderSurface != null && producer != null)
                surface.surfaceTextureListener?.onSurfaceTextureDestroyed(producer)
        }
        controller.pause().stop().destroy()
        compose.waitForIdle()
        activity = null
        nativeView = null
        texture = null
    }

    private val host get() = checkNotNull(activity).get()
    private fun text(language: String, key: String) = No5Text.text(language, key)

    /** Records only documented ChartEngine operations; never calls JNI/GLES. */
    private class DialogRecordingEngine : ChartEngine {
        override val status = MutableStateFlow(ChartEngineStatus(initialized = true, chartLoaded = true,
            viewport = ChartViewport(42.36, -71.04, .15), rendererDiagnostics = "SYNTHETIC · native host dialog test",
            rendererBenchmarkDataAvailable = true))
        var attaches = 0
        var diagnosticsReads = 0
        var measurementStarts = 0
        var projectionCalls = 0
        private var revision = 0L
        private var geometry = ChartSurfaceGeometry.fit(2000, 1070)
        private fun confirm() {
            status.value = status.value.copy(confirmedFrame = ChartFrame(++revision, "synthetic:$revision",
                geometry, status.value.viewport, ChartScale(120.0, 500.0)))
        }
        override fun attachSurface(surface: Surface, width: Int, height: Int) {
            attaches++
            geometry = ChartSurfaceGeometry.fit(width, height)
            status.value = status.value.copy(surfaceAttached = true)
            confirm()
        }
        override fun detachSurface() { status.value = status.value.copy(surfaceAttached = false, confirmedFrame = null) }
        override fun setViewport(viewport: ChartViewport) { status.value = status.value.copy(viewport = viewport); confirm() }
        override suspend fun centerCamera(position: GeoProjection, rotationRadians: Double?): Boolean {
            setViewport(status.value.viewport.copy(latitude = position.latitude, longitude = position.longitude,
                rotationRadians = rotationRadians ?: status.value.viewport.rotationRadians))
            return true
        }
        override fun setTouchOutlinesEnabled(enabled: Boolean): Boolean {
            status.value = status.value.copy(touchOutlinesEnabled = enabled)
            return true
        }
        override suspend fun applyPresentationProfile(value: ChartPresentation): EngineResult {
            value.validate()
            status.value = status.value.copy(presentation = value)
            confirm()
            return EngineResult(true)
        }
        override suspend fun applyOverlays(value: ChartOverlays): Boolean = true
        override suspend fun projectCoordinates(positions: List<GeoProjection>, frame: ChartFrame): List<ScreenPoint> {
            projectionCalls++
            return positions.map { ScreenPoint(OVERLAY_X, OVERLAY_Y) }
        }
        override suspend fun readRendererDiagnostics(): EngineResult { diagnosticsReads++; return EngineResult(true, status.value.rendererDiagnostics) }
        override suspend fun readRendererReport() = EngineResult(false, No5Text.ui("Keine abgeschlossene Aufnahme vorhanden"))
        override suspend fun startRendererCapture(config: RendererCaptureConfig): EngineResult { measurementStarts++; return EngineResult(false) }
        override suspend fun startRendererBenchmark(config: RendererBenchmarkConfig): EngineResult { measurementStarts++; return EngineResult(false) }
        override suspend fun profileRenderer(): EngineResult { measurementStarts++; return EngineResult(false) }
        override suspend fun initialize(config: ChartEngineConfig) = EngineResult(true)
        override suspend fun loadChartSet(id: String, paths: List<String>) = EngineResult(true)
        override suspend fun queryFeature(point: ScreenPoint): List<ChartFeature> = emptyList()
        override fun setS52Settings(settings: S52Settings) = Unit
        override fun setOwnship(state: OwnshipProjection) = Unit
        override fun setAisTargets(targets: List<AisProjection>) = Unit
        override fun setRouteProjection(route: RouteProjection?) = Unit
        override fun setWaypointProjection(waypoints: List<WaypointProjection>) = Unit
        override fun close() = Unit
    }

    private class ReadOnlyRouteFixture : SignalKRouteActions {
        override val routeResources = MutableStateFlow(SignalKRoutesState(origin = ENDPOINT, ready = true,
            routes = listOf(SignalKRouteResource("dialog-route", ROUTE_NAME,
                listOf(SignalKRoutePoint("a", "Start", 42.36, -71.04), SignalKRoutePoint("b", "End", 42.37, -71.03)),
                "synthetic-token", "{\"name\":\"SYNTHETIC\"}"))))
        var writes = 0
        override suspend fun refreshRoutes() = Unit
        override suspend fun saveRoute(origin: String, id: String, name: String, points: List<SignalKRoutePoint>,
            expectedToken: String?, resourceJson: String?): SignalKRouteResource { writes++; error("Layout check must not save a route") }
        override suspend fun deleteRoute(origin: String, id: String, expectedToken: String) { writes++; error("Layout check must not delete a route") }
    }

    companion object {
        private const val ENDPOINT = "https://synthetic-gateway.invalid:8443"
        private const val ROUTE_NAME = "SYNTHETIC · retained route name"
        private const val OVERLAY_X = 900.0
        private const val OVERLAY_Y = 500.0
        private val STATIC_DETAIL_KEYS = listOf("Bootsposition", "Letzte belegte Position",
            "AIS · Messalter unbekannt", "Quell-Wegpunkt", "Route · %s",
            "Letzte gemeldete Position · Messalter und Qualität unbekannt")
    }
}
