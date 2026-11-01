package com.no5.helm.acceptance

import android.os.Build
import android.os.SystemClock
import android.net.Uri
import android.content.Intent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.PersistentChartController
import com.no5.helm.chart.*
import com.no5.helm.library.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After

/** Explicit emulator run against an already imported private ZIP. No licensed
 * fixture is bundled, no data are cleared, and the previous selection is restored.
 * Setup may select/position charts; zoom and pan use real window touch input. */
class ChartResidencyEmulatorTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<LifecycleAcceptanceActivity>
    private lateinit var activity: LifecycleAcceptanceActivity
    private val engine: ChartEngine get() = activity.engine
    private val controller: PersistentChartController get() = activity.controller
    private var downTime = 0L
    private val observations = JSONArray()
    private lateinit var planning: ChartPlanningCatalog
    private lateinit var output: File

    @Before fun launchOfflineProductionHost() {
        assertTrue("This test is authorized only on an emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals(LifecycleAcceptanceContext.APPLICATION_ID, instrumentation.targetContext.packageName)
        scenario = ActivityScenario.launch(Intent(instrumentation.targetContext, LifecycleAcceptanceActivity::class.java)
            .putExtra(LifecycleAcceptanceContext.RUN_EXTRA, LifecycleAcceptanceContext.newRunId()))
        scenario.onActivity { activity = it }
        compose.waitUntil(120_000) { activity.prepared || activity.failure != null }
        assertNull(activity.failure)

    }
    @After fun closeOfflineHost() { if (::scenario.isInitialized) scenario.close() }

    private fun point(): Pair<Offset, Offset> {
        var result: Pair<Offset, Offset>? = null
        scenario.onActivity { activity ->
            fun find(v: View): TextureView? = if (v is TextureView && v.javaClass.simpleName == "ChartSurfaceView") v else if (v is ViewGroup)
                (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) } else null
            val view = requireNotNull(find(activity.window.decorView))
            val location = IntArray(2); view.getLocationOnScreen(location)
            assertTrue(view.isShown && view.width > 0 && view.height > 0)
            println("CHART_RELOAD_INPUT ${location.toList()} ${view.width}x${view.height}")
            result = Offset(location[0] + view.width / 2f, location[1] + view.height / 2f) to
                Offset(view.width.toFloat(), view.height.toFloat())
        }
        return requireNotNull(result)
    }
    private fun send(action: Int, vararg points: Pair<Int, Offset>) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val event = MotionEvent.obtain(downTime, now, action, points.size,
            points.map { (id, _) -> MotionEvent.PointerProperties().apply {
                this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray(), points.map { (_, p) -> MotionEvent.PointerCoords().apply {
                x = p.x; y = p.y; pressure = 1f; size = 1f
            } }.toTypedArray(), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        SystemClock.sleep(16)
    }
    private fun pinch(start: Float, end: Float) {
        val before = engine.status.value.viewport.metresToPixels
        val center = point().first
        send(MotionEvent.ACTION_DOWN, 7 to center + Offset(-start, 0f))
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            7 to center + Offset(-start, 0f), 31 to center + Offset(start, 0f))
        for (step in 1..16) {
            val radius = start + (end - start) * step / 16f
            send(MotionEvent.ACTION_MOVE, 7 to center + Offset(-radius, 0f), 31 to center + Offset(radius, 0f))
        }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            7 to center + Offset(-end, 0f), 31 to center + Offset(end, 0f))
        send(MotionEvent.ACTION_UP, 7 to center + Offset(-end, 0f))
        compose.waitUntil(30_000) {
            val current = engine.status.value.viewport.metresToPixels
            if (end > start) current > before * 1.5 else current < before / 1.5
        }
    }
    private fun drag(x: Float, y: Float) {
        val before = engine.status.value.viewport
        val (center, size) = point(); val delta = Offset(size.x * x, size.y * y)
        send(MotionEvent.ACTION_DOWN, 7 to center)
        for (step in 1..16) send(MotionEvent.ACTION_MOVE, 7 to center + delta * (step / 16f))
        send(MotionEvent.ACTION_UP, 7 to center + delta)
        compose.waitUntil(30_000) {
            val current = engine.status.value.viewport
            kotlin.math.abs(current.longitude - before.longitude) > .01 ||
                kotlin.math.abs(current.latitude - before.latitude) > .01
        }
    }
    private fun awaitIdle() = compose.waitUntil(120_000) { !controller.state.value.busy }
    @Test fun noaaConsentFollowsValidatedProviderAndSurvivesReopening() {
        scenario.onActivity { activity.importFixture(LifecycleFixture.SEED) }; awaitIdle()
        assertEquals(ImportActivity.COMPLETED, controller.state.value.importProgress?.activity)
        val noaa = controller.state.value.snapshot.packages.single()
        compose.onNodeWithTag("empty-chart-import").performClick()
        compose.onNodeWithTag("chart-package-import").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("chart-noaa-terms").assertDoesNotExist()
        compose.onNodeWithTag("chart-package-select-${noaa.id}").performScrollTo().performClick()
        compose.onNodeWithTag("chart-noaa-terms").assertIsOff()
        compose.onNodeWithTag("chart-package-activate").assertIsNotEnabled()
        compose.onNodeWithTag("chart-noaa-terms").performClick()
        compose.onNodeWithTag("chart-package-activate").assertIsEnabled()
        compose.onNodeWithTag("chart-region-close").performClick()
        compose.onNodeWithTag("chart-package-select-${noaa.id}").performScrollTo().performClick()
        compose.onNodeWithTag("chart-noaa-terms").assertIsOn()
        compose.onNodeWithTag("chart-region-close").performClick()
        scenario.onActivity { activity.importFixture(LifecycleFixture.WSV) }; awaitIdle()
        assertEquals(ImportActivity.COMPLETED, controller.state.value.importProgress?.activity)
        val wsv = controller.state.value.snapshot.packages.single { it.cells.all { cell -> cell.provider == S57Profiles.WSV } }
        compose.onNodeWithTag("chart-package-select-${wsv.id}").performScrollTo().performClick()
        compose.onNodeWithTag("chart-noaa-terms").assertDoesNotExist()
        compose.onNodeWithTag("chart-package-activate").assertIsEnabled()
    }
    private fun settled(label: String, requiredCell: String? = null): ChartCoverageAdmission {
        var selected: ChartCoverageAdmission? = null
        var residency: ChartResidencyStatus? = null
        compose.waitUntil(180_000) {
            val state = engine.status.value
            if (!state.chartLoaded || !state.residentCameraReady || state.confirmedFrame == null || state.presentationPending)
                return@waitUntil false
            val frame = requireNotNull(state.confirmedFrame)
            if (frame.sourceGeneration != state.chartSourceGeneration || frame.contentGeneration != state.chartContentGeneration ||
                frame.viewport.latitude != state.viewport.latitude || frame.viewport.longitude != state.viewport.longitude ||
                frame.viewport.metresToPixels != state.viewport.metresToPixels) return@waitUntil false
            val pose = runBlocking { engine.readResidentPlanningViewport() } ?: return@waitUntil false
            val original = ChartViewportPlanner(planning, IndexedChartCellIndex(planning),
                ChartPlanPolicy(prefetchPixels = 96.0, maxPrefetchCells = 1)).plan(ChartPlanCamera(
                pose.latitude, pose.longitude, pose.metresToPixels, pose.rotationRadians, pose.width, pose.height))
            val expected = ChartCoveragePlanner(planning).admit(original, state.chartScaleDenominator)
            val actual = runBlocking { engine.readResidency() }.status ?: return@waitUntil false
            val names = expected.plan.required.map { it.name }
            val ready = actual.complete && actual.loading == 0 && actual.cameraKey == expected.plan.tag.cameraKey &&
                actual.policyKey == expected.plan.tag.selectionPolicyKey && actual.required == names.size &&
                expected.plan.required.all { it.sourceKey in actual.sources } &&
                (requiredCell == null || requiredCell in names)
            if (ready) { selected = expected; residency = actual }
            ready
        }
        val actual = requireNotNull(residency); val result = requireNotNull(selected)
        assertEquals(0L, actual.failures); assertTrue(actual.chargedBytes <= requireNotNull(actual.budget).bytes)
        observations.put(JSONObject().put("phase", label).put("viewport", JSONObject()
            .put("latitude", result.plan.camera.latitude).put("longitude", result.plan.camera.longitude)
            .put("ppm", result.plan.camera.pixelsPerMetre)).put("cameraKey", actual.cameraKey)
            .put("policyKey", actual.policyKey).put("cameraRevision", actual.cameraRevision)
            .put("required", JSONArray(result.plan.required.map { it.name }))
            .put("loaded", JSONArray(engine.status.value.loadedChartCells)).put("missing", actual.missing)
            .put("loads", actual.loads).put("chargedBytes", actual.chargedBytes))
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$label.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        File(output, "observations.json").writeText(observations.toString(2))
        println("CHART_RELOAD_PHASE $label ${result.plan.required.map { it.name }}")
        return result
    }

    @Test fun privatePiChartsFollowWindowPinchPanAndRevisit() {
        assertTrue("This test is authorized only on an emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        awaitIdle()
        val target = instrumentation.targetContext
        output = File(activity.acceptanceContext.runRoot, "chart-reload-evidence").apply { mkdirs() }
        val library = ChartLibrary(activity.acceptanceContext.libraryRoot)
        val before = runBlocking { library.snapshot() }
        if (before.packages.none { it.piCache != null && it.cells.size == 56 }) {
            val path = requireNotNull(InstrumentationRegistry.getArguments().getString("piImportPath")) {
                "Import the private ZIP first, or explicitly provide an app-cache input path"
            }
            val input = File(path).canonicalFile
            require(input.isFile && input.toPath().startsWith(target.cacheDir.canonicalFile.toPath()))
            scenario.onActivity { controller.importPackage(Uri.fromFile(input)) }
            awaitIdle()
            assertEquals(ImportActivity.COMPLETED, controller.state.value.importProgress?.activity)
            assertEquals(before.active, runBlocking { library.snapshot() }.active)
        }
        val imported = runBlocking { library.snapshot() }
        val pack = imported.packages.single { it.piCache != null && it.cells.size == 56 }
        val selection = ChartSelection(pack.id, pack.cells.map { it.name })
        planning = ChartPlanningCatalog.fromPackage(pack, selection.cells.toSet())
        assertEquals(56, planning.cells.count { it.metadata != null })
        val old = engine.status.value
        var success = false
        try {
            compose.onNodeWithTag("empty-chart-import").performClick()
            compose.onNodeWithTag("chart-package-select-${pack.id}").performScrollTo().performClick()
            compose.onNodeWithTag("chart-noaa-terms").assertDoesNotExist()
            compose.onNodeWithTag("chart-package-activate").assertIsEnabled().performClick()
            awaitIdle()
            compose.onNodeWithTag("chart-region-close").performClick()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(120_000) { controller.state.value.snapshot.active == selection && engine.status.value.residentCameraReady }
            compose.waitUntil(120_000) { engine.status.value.chartLoaded && engine.status.value.confirmedFrame != null }
            assertTrue(engine.setBufferTransformMode(ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE).success)
            compose.waitForIdle()
            if (compose.onAllNodesWithText("SCHLIESSEN").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("SCHLIESSEN").performClick(); compose.waitForIdle()
            }
            compose.onNodeWithText("SCHLIESSEN").assertDoesNotExist()
            settled("country-overview")
            engine.setViewport(engine.status.value.viewport.copy(latitude = -33.9, longitude = 18.43,
                metresToPixels = .006, rotationRadians = 0.0))
            val overview = settled("cape-overview")
            val source = engine.status.value.chartSourceGeneration
            val initialPpm = engine.status.value.viewport.metresToPixels
            pinch(50f, 150f); settled("zoom-first")
            pinch(50f, 150f); val bay = settled("table-bay-detail", "OC-27-050004")
            assertTrue(engine.status.value.viewport.metresToPixels > initialPpm * 8)
            assertNotEquals(overview.plan.tag.cameraKey, bay.plan.tag.cameraKey)
            pinch(40f, 160f); val harbour = settled("harbour-detail", "OC-27-050005")
            assertNotEquals(bay.plan.required.toSet(), harbour.plan.required.toSet())
            pinch(160f, 40f); settled("zoom-out", "OC-27-050004")
            // Five physical drags reach the next area even when Android does
            // not add momentum to a release. Coverage assertions stay unchanged.
            repeat(5) { drag(-.2f, -.4f); settled("pan-${it + 1}") }
            val moved = settled("new-area")
            assertTrue(moved.plan.required.any { it !in bay.plan.required })
            assertFalse(moved.plan.required.any { it.name == "OC-27-050005" })
            assertTrue(moved.plan.camera.longitude > bay.plan.camera.longitude + .1)
            repeat(5) { drag(.2f, .4f); settled("return-${it + 1}") }
            settled("table-bay-revisit", "OC-27-050004")
            // Exercise the reported journey, including Settings remaining the
            // selected destination after the panel is collapsed.
            for ((mode, style) in listOf(PresentationMode.DAY to "glacier",
                PresentationMode.RED_NIGHT to "redline",
                PresentationMode.GRAY_COMPARISON to "redwater-focus-gray",
                PresentationMode.DAY to "default")) {
                compose.onNodeWithTag("map-dock-toggle").performTouchInput { click() }
                compose.onNodeWithTag("nav-SETTINGS").performTouchInput { click() }
                compose.onNodeWithText("Darstellung").performScrollTo().performTouchInput { click() }
                if (compose.onAllNodesWithTag("chart-style-settings").fetchSemanticsNodes().isEmpty()) {
                    File(output, "style-menu-missing.txt").writeText(compose.onRoot().printToString())
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(output, "style-menu-missing.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
                compose.onNodeWithText("Darstellung").assertIsSelected()
                compose.onNodeWithTag("chart-style-settings").performScrollTo().performTouchInput { click() }
                compose.onNodeWithTag("chart-style-mode-${mode.name}").performTouchInput { click() }
                compose.onNodeWithTag("chart-style-$style").performTouchInput { click() }
                compose.onNodeWithTag("chart-style-apply").performTouchInput { click() }
                compose.waitUntil(120_000) { engine.status.value.presentation.styleId == style && !engine.status.value.presentationPending }
                compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("chart-style-close") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("chart-style-close").performTouchInput { click() }
                compose.onNodeWithTag("panel-toggle").performTouchInput { click() }
                compose.onNodeWithTag("screen-panel").assertDoesNotExist()
                for (tag in listOf("north-up", "zoom-in", "zoom-out", "follow-boat"))
                    compose.onNodeWithTag(tag).assertIsDisplayed()
                compose.onNodeWithTag("dock-close").performTouchInput { click() }
                val opener = compose.onNodeWithTag("map-dock-toggle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val map = compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
                assertEquals(map.center.x, opener.center.x, 1f)
                assertTrue(opener.bottom <= map.bottom && opener.bottom >= map.bottom - 12f)
                settled("style-$style-ready")
                drag(.2f, 0f); settled("style-$style-pan")
                for (tag in listOf("north-up", "zoom-in", "zoom-out", "follow-boat"))
                    compose.onNodeWithTag(tag).assertIsDisplayed()
                drag(-.2f, 0f); settled("style-$style-return")
            }
            assertEquals(source, engine.status.value.chartSourceGeneration)
            val after = runBlocking { library.snapshot() }
            assertEquals(selection, after.active)
            assertEquals(imported.packages, after.packages)
            assertTrue(before.packages.all { it in after.packages })
            assertEquals(1, (0 until observations.length()).map { observations.getJSONObject(it).getString("policyKey") }.distinct().size)
            success = true
        } finally {
            if (!success) {
                File(output, "failure-engine-status.txt").writeText(engine.status.value.toString())
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(output, "failure-window.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            val previousSelection = before.active
            if (previousSelection != null) scenario.onActivity { controller.activate(previousSelection) }
            else scenario.onActivity { controller.deactivate(selection) }
            awaitIdle()
            engine.setBufferTransformMode(old.bufferTransformMode)
            engine.setViewport(old.viewport)
            File(output, "result.json").writeText(JSONObject().put("success", success)
                .put("device", Build.MODEL).put("api", Build.VERSION.SDK_INT)
                .put("sourceCommit", com.no5.helm.app.BuildConfig.SOURCE_COMMIT)
                .put("selectionRestored", runBlocking { library.snapshot() }.active == before.active)
                .put("privateCells", 56).put("windowGestures", true).toString(2))
            println("CHART_RELOAD_EVIDENCE ${output.absolutePath}")
        }
    }
}
