package com.no5.helm.acceptance

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.chart.*
import com.no5.helm.library.*
import com.no5.helm.model.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Signal K + the production TLS gateway client + imported ENC/SENC. No production data or device access. */
class RoutesOverlaysFiltersEmulatorTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: LifecycleAcceptanceActivity
    private lateinit var scenario: ActivityScenario<LifecycleAcceptanceActivity>
    private val engine get() = activity.engine
    private val controller get() = activity.controller
    private lateinit var output: File
    private fun settle() = compose.waitUntil(120_000) {
        val s = engine.status.value
        s.chartLoaded && s.residentCameraReady && s.confirmedFrame?.viewport == s.viewport &&
            (s.bufferTransformMode == ChartBufferTransformMode.OFF || s.bufferTransformBaseReady) &&
            !s.presentationPending && !controller.state.value.busy
    }
    private fun capture(label: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$label.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        File(output, "$label-semantics.txt").writeText(compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE))
        File(output, "$label-engine.txt").writeText(engine.status.value.toString())
        println("ROUTES_OVERLAYS_FILTERS_PHASE $label")
    }
    private fun nav(destination: String) {
        if (compose.onAllNodesWithTag("nav-$destination").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("map-dock-toggle").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("nav-$destination").performSemanticsAction(SemanticsActions.OnClick) { it() }; compose.waitForIdle()
    }
    private fun layer(id: String, visible: Boolean) {
        compose.onNodeWithTag("layer-toggle-$id").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(30_000) {
            activity.acceptanceContext.getSharedPreferences("native-screens-v1", android.content.Context.MODE_PRIVATE)
                .getBoolean("overlay.$id", !visible) == visible
        }
        settle()
    }
    private fun overlays(position: GeoProjection) {
        val point = GeoPoint(position.latitude, position.longitude)
        val projection = HelmProjection(Ownship(point, null, null, null, null),
            listOf(AisTarget(123456789, "AIS Fixture", point, Double.NaN, Double.NaN, 0.0)),
            emptyList(), listOf(Waypoint("fixture-waypoint", "Waypoint Fixture", point)))
        val snapshot = Snapshot(serverId = "EMULATOR overlay fixture", revision = 1,
            generatedAt = "2026-10-12T22:00:00Z", state = projection)
        scenario.onActivity { it.fixtureSnapshot(HelmState(ConnectionState.LIVE, snapshot)) }
        compose.waitForIdle(); settle(); SystemClock.sleep(250)
    }
    private fun closeKeyboard() {
        // Dismiss the active IME with the same Back action a user uses. The
        // emulator may run No5's keyboard, whose hide transition is asynchronous.
        androidx.test.espresso.Espresso.pressBack()
        scenario.onActivity {
            val token = it.currentFocus?.windowToken ?: it.window.decorView.windowToken
            it.currentFocus?.clearFocus()
            (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
        }
        compose.waitForIdle(); SystemClock.sleep(700)
    }
    private fun pointer(from: ScreenPoint, to: ScreenPoint? = null) {
        var x = 0; var y = 0
        scenario.onActivity {
            fun find(v: View): TextureView? = if (v is TextureView && v.javaClass.simpleName == "ChartSurfaceView") v else if (v is ViewGroup)
                (0 until v.childCount).firstNotNullOfOrNull { n -> find(v.getChildAt(n)) } else null
            val view = requireNotNull(find(it.window.decorView)); val at = IntArray(2); view.getLocationOnScreen(at); x = at[0]; y = at[1]
        }
        val start = SystemClock.uptimeMillis()
        val events = if (to == null) listOf(MotionEvent.ACTION_DOWN to from, MotionEvent.ACTION_UP to from)
            else listOf(MotionEvent.ACTION_DOWN to from, MotionEvent.ACTION_MOVE to ScreenPoint((from.x + to.x) / 2, (from.y + to.y) / 2), MotionEvent.ACTION_MOVE to to, MotionEvent.ACTION_UP to to)
        events.forEach { (action, p) ->
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, (x + p.x).toFloat(), (y + p.y).toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }; SystemClock.sleep(60)
        }
    }
    private fun query(position: GeoProjection): ChartObjectQuery {
        var result: ChartObjectQuery? = null
        compose.waitUntil(10_000) {
            val s = engine.status.value; val frame = s.confirmedFrame
            val point = frame?.let { runBlocking { engine.projectCoordinates(listOf(position), it) }?.singleOrNull() }
            if (frame != null && point != null && engine.status.value.confirmedFrame == frame) {
                result = ChartObjectQuery(point, 24.0 * activity.resources.displayMetrics.density, frame, s.presentationGeneration, filterRevision = s.objectFilters.revision)
            }
            result != null
        }
        return requireNotNull(result)
    }
    @Test fun routeSignalKRoundtripEditingOverlaysAndFilterRestart() {
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val target = instrumentation.targetContext
        assertEquals(LifecycleAcceptanceContext.APPLICATION_ID, target.packageName)
        val args = InstrumentationRegistry.getArguments()
        val zip = File(requireNotNull(args.getString("piImportPath"))).canonicalFile
        val fixture = File(requireNotNull(args.getString("objectFixturePath"))).canonicalFile
        val ca = File(requireNotNull(args.getString("routeFixtureCa"))).canonicalFile
        listOf(zip, fixture, ca).forEach { require(it.toPath().startsWith(target.cacheDir.canonicalFile.toPath())) }
        val runId = LifecycleAcceptanceContext.newRunId()
        scenario = ActivityScenario.launch(Intent(target, LifecycleAcceptanceActivity::class.java)
            .putExtra(LifecycleAcceptanceContext.RUN_EXTRA, runId).putExtra("routeFixtureCa", ca.path)
            .apply { args.getString("routeFixtureAccess")?.let { access ->
                val file = File(access).canonicalFile; require(file.toPath().startsWith(target.cacheDir.canonicalFile.toPath()))
                putExtra("routeFixtureAccess", file.path)
            } })
        try {
            scenario.onActivity { activity = it }
            compose.waitUntil(120_000) { activity.prepared || activity.failure != null }; assertNull(activity.failure)
            output = File(activity.acceptanceContext.runRoot, "routes-overlays-filters-evidence").apply { mkdirs() }
            compose.waitUntil(30_000) { activity.routeClient?.routeResources?.value?.ready == true }
            scenario.onActivity { controller.importPackage(Uri.fromFile(zip)) }
            compose.waitUntil(120_000) { !controller.state.value.busy }
            assertEquals(ImportActivity.COMPLETED, controller.state.value.importProgress?.activity)
            val pack = controller.state.value.snapshot.packages.single { it.piCache != null }
            val samples = JSONArray(fixture.readText()); val buoy = samples.getJSONObject(0)
            scenario.onActivity { controller.activate(ChartSelection(pack.id, listOf(buoy.getString("cell")))) }
            settle()
            val position = GeoProjection(buoy.getDouble("lat"), buoy.getDouble("lon"))
            engine.setViewport(engine.status.value.viewport.copy(latitude = position.latitude, longitude = position.longitude, metresToPixels = .25)); settle()
            val originalSource = engine.status.value.chartSourceGeneration
            nav("ROUTES"); compose.onNodeWithTag("route-new").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("route-name").performScrollTo().performTextReplacement("EMULATOR · $runId")
            closeKeyboard()
            compose.onNodeWithTag("panel-toggle").performSemanticsAction(SemanticsActions.OnClick) { it() }; compose.waitForIdle(); settle(); compose.onNodeWithTag("screen-panel").assertDoesNotExist()
            compose.onNodeWithTag("screen-panel").assertDoesNotExist()
            val f = requireNotNull(engine.status.value.confirmedFrame)
            val a = ScreenPoint(f.geometry.surfaceWidth * .40, f.geometry.surfaceHeight * .48)
            val b = ScreenPoint(f.geometry.surfaceWidth * .58, f.geometry.surfaceHeight * .60)
            val expectedA = runBlocking { engine.unprojectCoordinate(a, f) }!!
            pointer(a); settle(); pointer(b); settle(); capture("draft-two-points")
            nav("ROUTES"); compose.onNodeWithTag("route-save").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(30_000) { activity.routeClient!!.routeResources.value.routes.any { it.name == "EMULATOR · $runId" } }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("route-shown-name").fetchSemanticsNodes().isNotEmpty() }
            val stored = activity.routeClient!!.routeResources.value.routes.single { it.name == "EMULATOR · $runId" }
            assertEquals(2, stored.points.size); assertEquals(expectedA.latitude, stored.points[0].latitude, 1e-9); assertEquals(expectedA.longitude, stored.points[0].longitude, 1e-9)
            assertTrue(stored.points.all { it.id.isNotBlank() }); capture("signal-k-saved")
            compose.onNodeWithTag("route-edit").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("route-insert-1").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }; compose.onNodeWithTag("panel-toggle").performSemanticsAction(SemanticsActions.OnClick) { it() }; compose.waitForIdle(); settle(); compose.onNodeWithTag("screen-panel").assertDoesNotExist()
            pointer(ScreenPoint(f.geometry.surfaceWidth * .50, f.geometry.surfaceHeight * .56)); settle()
            nav("ROUTES"); compose.onNodeWithTag("route-replace-0").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }; compose.onNodeWithTag("panel-toggle").performSemanticsAction(SemanticsActions.OnClick) { it() }; compose.waitForIdle(); settle(); compose.onNodeWithTag("screen-panel").assertDoesNotExist()
            val handle = query(GeoProjection(stored.points[0].latitude, stored.points[0].longitude)).point
            val dropped = ScreenPoint(handle.x + 50, handle.y + 35)
            val expectedDrop = runBlocking { engine.unprojectCoordinate(dropped, requireNotNull(engine.status.value.confirmedFrame)) }!!
            pointer(handle, dropped); settle(); capture("handle-drag")
            nav("ROUTES"); compose.onNodeWithTag("route-save").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(30_000) { activity.routeClient!!.routeResources.value.routes.find { it.id == stored.id }?.token != stored.token }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("route-shown-name").fetchSemanticsNodes().isNotEmpty() }
            val edited = activity.routeClient!!.routeResources.value.routes.single { it.id == stored.id }
            assertEquals(3, edited.points.size); assertEquals(stored.points[0].id, edited.points[0].id)
            assertEquals(expectedDrop.latitude, edited.points[0].latitude, 1e-9); assertEquals(expectedDrop.longitude, edited.points[0].longitude, 1e-9)
            // Opening a saved route leaves map taps in read-only preview.
            compose.onNodeWithTag("panel-toggle").performSemanticsAction(SemanticsActions.OnClick) { it() }; compose.waitForIdle(); settle(); compose.onNodeWithTag("screen-panel").assertDoesNotExist(); pointer(a); settle()
            nav("ROUTES"); compose.onNodeWithTag("route-name").assertDoesNotExist(); assertEquals(3, edited.points.size)
            capture("preview-no-edit")
            nav("CHART"); overlays(position)
            pointer(query(position).point)
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("overlay-hit-panel").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("AIS Fixture").assertExists(); compose.onNodeWithText("Waypoint Fixture").assertExists()
            capture("overlay-overlap-picker")
            compose.onNodeWithTag("overlay-query-enc").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("object-info-panel").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("object-close").performSemanticsAction(SemanticsActions.OnClick) { it() }
            nav("LAYERS"); listOf("ownship", "ais", "waypoints", "route").forEach { layer(it, false) }
            capture("overlays-hidden")
            compose.onNodeWithTag("s52-category-CUSTOM").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(30_000) { engine.status.value.objectFilters.effective.category == S52DisplayCategory.CUSTOM }; settle()
            compose.onNodeWithTag("s52-search").performScrollTo().performTextReplacement("BOYLAT")
            closeKeyboard()
            compose.onNodeWithTag("s52-class-BOYLAT").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(30_000) { "BOYLAT" in engine.status.value.objectFilters.effective.hiddenClasses }; settle()
            val hiddenRequest = query(position)
            assertFalse(runBlocking { engine.queryObjects(hiddenRequest) }.objects.any { it.objectClass == "BOYLAT" })
            capture("buoy-filtered")
            compose.onNodeWithTag("s52-text").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(30_000) { !engine.status.value.objectFilters.effective.text }; settle()
            assertEquals(ChartObjectQueryState.STALE, runBlocking { engine.queryObjects(hiddenRequest) }.state)
            compose.onNodeWithTag("s52-soundings").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(30_000) { !engine.status.value.objectFilters.effective.soundings }; settle()
            val desired = engine.status.value.objectFilters.effective
            assertTrue(runBlocking { engine.applyPresentationProfile(ChartPresentation("redline")) }.success); settle()
            assertEquals(desired, engine.status.value.objectFilters.effective)
            capture("red-style-preserves-filters")
            // Restart reloads real Signal K and the persisted filter configuration.
            scenario.recreate(); scenario.onActivity { activity = it }
            compose.waitUntil(120_000) { activity.prepared || activity.failure != null }; assertNull(activity.failure); settle()
            compose.waitUntil(30_000) { engine.status.value.objectFilters.effective == desired && activity.routeClient!!.routeResources.value.ready }
            assertEquals(edited, activity.routeClient!!.routeResources.value.routes.single { it.id == stored.id })
            nav("ROUTES"); compose.onNodeWithTag("route-open-local-${stored.id}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("route-shown-name").assertTextEquals("EMULATOR · $runId"); capture("restart-route-and-filters")
            nav("LAYERS")
            listOf("ownship", "ais", "waypoints", "route").forEach { id ->
                compose.onNodeWithTag("layer-toggle-$id").performScrollTo().assertTextContains("Einblenden")
                layer(id, true)
            }
            nav("CHART"); overlays(position); pointer(query(position).point)
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("overlay-hit-panel").fetchSemanticsNodes().isNotEmpty() }
            capture("restart-overlays-restored")
            compose.onNodeWithText("Schließen").performSemanticsAction(SemanticsActions.OnClick) { it() }
            // Zoom, rotation and pan must keep georeferenced overlays and the saved route.
            assertTrue(engine.setBufferTransformMode(ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE).success); settle()
            engine.setViewport(engine.status.value.viewport.copy(latitude = position.latitude, longitude = position.longitude, metresToPixels = .40, rotationRadians = .3)); settle()
            compose.waitForIdle(); capture("zoom-rotate-before-tap")
            pointer(query(position).point)
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("overlay-hit-panel").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("AIS Fixture").assertExists(); capture("zoom-rotate-overlays")
            compose.onNodeWithText("Schließen").performSemanticsAction(SemanticsActions.OnClick) { it() }
            engine.setViewport(engine.status.value.viewport.copy(latitude = position.latitude + .005, longitude = position.longitude + .005)); settle()
            engine.setViewport(engine.status.value.viewport.copy(latitude = position.latitude, longitude = position.longitude, rotationRadians = 0.0)); settle()
            nav("LAYERS"); compose.onNodeWithTag("s52-reset").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(30_000) { engine.status.value.objectFilters.effective == ChartObjectFilters() }; settle()
            val source = engine.status.value.chartSourceGeneration
            engine.setViewport(engine.status.value.viewport.copy(latitude = position.latitude, longitude = position.longitude, metresToPixels = .25)); settle()
            assertTrue(runBlocking { engine.queryObjects(query(position)) }.objects.any { it.objectClass == "BOYLAT" })
            assertEquals(source, engine.status.value.chartSourceGeneration)
            capture("reset-visible")
            // Real deep wreck and protected-area objects remain byte-for-byte attributes of the imported source.
            val classChecks = JSONArray()
            for (n in listOf(1, 4)) {
                val sample = samples.getJSONObject(n); val name = sample.getString("class")
                scenario.onActivity { controller.activate(ChartSelection(pack.id, listOf(sample.getString("cell")))) }; settle()
                val geo = GeoProjection(sample.getDouble("lat"), sample.getDouble("lon"))
                engine.setViewport(engine.status.value.viewport.copy(latitude = geo.latitude, longitude = geo.longitude, metresToPixels = .25)); settle()
                val before = runBlocking { engine.queryObjects(query(geo)) }.objects.single { it.objectClass == name && it.reference.featureIndex == sample.getInt("index") }
                val unchangedSource = engine.status.value.chartSourceGeneration
                assertTrue(runBlocking { engine.applyObjectFilters(ChartObjectFilters(S52DisplayCategory.CUSTOM, setOf(name))) }.success); settle()
                assertFalse(runBlocking { engine.queryObjects(query(geo)) }.objects.any { it.objectClass == name && it.reference.featureIndex == sample.getInt("index") })
                assertTrue(runBlocking { engine.applyObjectFilters(ChartObjectFilters(text = false, soundings = false)) }.success); settle()
                val after = runBlocking { engine.queryObjects(query(geo)) }.objects.single { it.reference == before.reference }
                assertEquals(before.attributes, after.attributes); assertEquals(unchangedSource, engine.status.value.chartSourceGeneration)
                if (name == "WRECKS") assertEquals("36", after.attributes["VALSOU"])
                classChecks.put(JSONObject().put("class", name).put("featureIndex", before.reference.featureIndex).put("attributesPreserved", true)); capture("$name-filter-and-source")
            }
            assertTrue(runBlocking { engine.applyObjectFilters(ChartObjectFilters()) }.success); settle()
            nav("ROUTES"); compose.onNodeWithTag("route-delete").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("route-delete-confirm").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(30_000) { activity.routeClient!!.routeResources.value.routes.none { it.id == stored.id } }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("route-delete-confirm").fetchSemanticsNodes().isEmpty() && compose.onAllNodesWithTag("route-shown-name").fetchSemanticsNodes().isEmpty() }
            runBlocking { activity.routeClient!!.refreshRoutes() }
            assertTrue(activity.routeClient!!.routeResources.value.ready)
            assertFalse(activity.routeClient!!.routeResources.value.routes.any { it.id == stored.id }); capture("signal-k-delete-confirmed")
            // A disconnected client permits a local draft without a second route collection.
            activity.routeClient!!.close(); compose.waitForIdle()
            compose.onNodeWithTag("route-new").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("panel-toggle").performSemanticsAction(SemanticsActions.OnClick) { it() }; compose.waitForIdle(); settle()
            val recoveryFrame = requireNotNull(engine.status.value.confirmedFrame)
            pointer(ScreenPoint(recoveryFrame.geometry.surfaceWidth * .4, recoveryFrame.geometry.surfaceHeight * .4)); settle()
            pointer(ScreenPoint(recoveryFrame.geometry.surfaceWidth * .6, recoveryFrame.geometry.surfaceHeight * .5)); settle()
            val routeFile = File(activity.acceptanceContext.filesDir, "route-data/store-v1.json")
            compose.waitUntil(20_000) { runCatching { JSONObject(routeFile.readText()).getJSONObject("draft").getJSONArray("points").length() == 2 }.getOrDefault(false) }
            val recovery = JSONObject(routeFile.readText()).getJSONObject("draft")
            scenario.recreate(); scenario.onActivity { activity = it }
            compose.waitUntil(120_000) { activity.prepared || activity.failure != null }; assertNull(activity.failure); settle()
            compose.waitUntil(30_000) { activity.routeClient!!.routeResources.value.ready }
            nav("ROUTES"); compose.onNodeWithTag("route-recovery").assertExists()
            compose.onNodeWithTag("route-recover").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("route-name").assertTextContains("Neue Route")
            assertEquals(recovery.toString(), JSONObject(routeFile.readText()).getJSONObject("draft").toString())
            assertFalse(activity.routeClient!!.routeResources.value.routes.any { it.id == recovery.getString("id").removePrefix("local-") })
            capture("offline-draft-recovered")
            File(output, "verification.json").writeText(JSONObject().put("status", "PASS").put("runId", runId).put("routeId", stored.id)
                .put("routePoints", edited.points.size).put("signalKReadback", true).put("restart", true).put("classFilter", true).put("textAndSoundings", true)
                .put("overlays", true).put("overlayRestart", true).put("zoomRotationPan", true).put("deleteReadback", true).put("offlineDraftRecovery", true).put("classChecks", classChecks)
                .put("sourceBeforeRestart", originalSource).put("referencesChanged", false).toString(2))
            File(output, "gateway-origin.txt").writeText(activity.routeClient!!.routeResources.value.origin.orEmpty())
        } catch (failure: Throwable) {
            failure.printStackTrace()
            println("ROUTE_CLIENT_DIAGNOSTIC ${activity.routeClient?.routeResources?.value}")
            capture("failure")
            throw failure
        } finally { scenario.close() }
    }
}
