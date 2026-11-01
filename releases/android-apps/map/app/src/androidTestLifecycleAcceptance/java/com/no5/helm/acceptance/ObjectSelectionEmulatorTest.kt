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
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.chart.*
import com.no5.helm.library.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Private input stays outside the APK/repository. Real window taps exercise the
 * production UI/JNI renderer in a dedicated test run; normal chart data is untouched. */
class ObjectSelectionEmulatorTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: LifecycleAcceptanceActivity
    private lateinit var scenario: ActivityScenario<LifecycleAcceptanceActivity>
    private val engine get() = activity.engine
    private val controller get() = activity.controller
    private lateinit var output: File
    private val observations = JSONArray()
    private fun idle() = compose.waitUntil(120_000) { !controller.state.value.busy }
    private fun settled(ppm: Double? = null) = compose.waitUntil(120_000) {
        val s = engine.status.value; val f = s.confirmedFrame
        s.chartLoaded && s.residentCameraReady && !s.presentationPending && f != null &&
            f.sourceGeneration == s.chartSourceGeneration && f.contentGeneration == s.chartContentGeneration &&
            (ppm == null || kotlin.math.abs(f.viewport.metresToPixels - ppm) < 1e-9) &&
            runBlocking { engine.readResidency() }.status?.complete == true
    }
    private fun capture(label: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$label.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        File(output, "observations.json").writeText(observations.toString(2))
        println("OBJECT_SELECTION_PHASE $label")
    }
    private fun windowTap(point: ScreenPoint) {
        var x = 0f; var y = 0f
        scenario.onActivity {
            fun find(v: View): TextureView? = if (v is TextureView && v.javaClass.simpleName == "ChartSurfaceView") v else if (v is ViewGroup)
                (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) } else null
            val view = requireNotNull(find(it.window.decorView)); val at = IntArray(2); view.getLocationOnScreen(at)
            x = at[0] + point.x.toFloat(); y = at[1] + point.y.toFloat()
        }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }; SystemClock.sleep(40)
        }
    }
    private fun choose(obj: ChartObject, total: Int) {
        if (total > 1) {
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("object-hit-count").fetchSemanticsNodes().isNotEmpty() }
            val tag = "object-pick-${obj.reference.sourceKey}-${obj.reference.featureIndex}"
            while (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithTag("object-more").performScrollTo().performClick(); compose.waitForIdle()
            }
            compose.onNodeWithTag(tag).performScrollTo().performClick()
        }
        compose.waitUntil(20_000) { engine.status.value.objectSelection.featureIndex == obj.reference.featureIndex && engine.status.value.objectSelection.visible }
        compose.onNodeWithTag("object-kind").assertIsDisplayed()
    }
    @Test fun sourceValuesAndAttachmentsSurviveRealTapsZoomStyleAndReload() {
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val target = instrumentation.targetContext
        assertEquals(LifecycleAcceptanceContext.APPLICATION_ID, target.packageName)
        val args = InstrumentationRegistry.getArguments()
        val zip = File(requireNotNull(args.getString("piImportPath"))).canonicalFile
        val fixtures = File(requireNotNull(args.getString("objectFixturePath"))).canonicalFile
        require(zip.toPath().startsWith(target.cacheDir.canonicalFile.toPath()) && fixtures.toPath().startsWith(target.cacheDir.canonicalFile.toPath()))
        scenario = ActivityScenario.launch(Intent(target, LifecycleAcceptanceActivity::class.java).putExtra(LifecycleAcceptanceContext.RUN_EXTRA, LifecycleAcceptanceContext.newRunId()))
        try {
            scenario.onActivity { activity = it }; compose.waitUntil(120_000) { activity.prepared || activity.failure != null }; assertNull(activity.failure)
            output = File(activity.acceptanceContext.runRoot, "object-selection-evidence").apply { mkdirs() }
            scenario.onActivity { controller.importPackage(Uri.fromFile(zip)) }; idle()
            assertEquals(ImportActivity.COMPLETED, controller.state.value.importProgress?.activity)
            val pack = controller.state.value.snapshot.packages.single { it.piCache != null }
            val samples = JSONArray(fixtures.readText())
            for (n in 0 until samples.length()) {
                val sample = samples.getJSONObject(n); val cell = sample.getString("cell"); val kind = sample.getString("class"); val index = sample.getInt("index")
                scenario.onActivity { controller.activate(ChartSelection(pack.id, listOf(cell))) }; idle(); settled()
                if (n == 0) { assertTrue(engine.setBufferTransformMode(ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE).success); settled() }
                val position = GeoProjection(sample.getDouble("lat"), sample.getDouble("lon"))
                engine.setViewport(engine.status.value.viewport.copy(latitude = position.latitude, longitude = position.longitude, metresToPixels = .25, rotationRadians = .0)); settled(.25)
                val frame = requireNotNull(engine.status.value.confirmedFrame)
                val point = runBlocking { engine.projectCoordinates(listOf(position), frame) }!!.single()
                val radius = 24.0 * target.resources.displayMetrics.density
                val request = ChartObjectQuery(point, radius, frame, engine.status.value.presentationGeneration)
                var query = runBlocking { engine.queryObjects(request) }; assertEquals(query.toString(), ChartObjectQueryState.READY, query.state)
                val found = query.objects.toMutableList()
                while (query.nextOffset != null) { query = runBlocking { engine.queryObjects(request.copy(offset = query.nextOffset!!)) }; found += query.objects }
                val obj = found.single { it.objectClass == kind && it.reference.featureIndex == index }
                val raw = sample.getJSONObject("attributes"); val expected = raw.keys().asSequence().associateWith { raw.getString(it) }
                assertEquals("SENC -> JNI attributes ${sample.getString("case")}", expected, obj.attributes)
                capture("${sample.getString("case")}-before")
                windowTap(point); choose(obj, found.size)
                compose.onNodeWithTag("object-raw-toggle").performScrollTo().performClick()
                expected.forEach { (key, value) -> compose.onNodeWithTag("object-raw-$key").assertTextEquals("$key: $value") }
                if (kind == "RESARE") {
                    val name = obj.attributes.getValue("TXTDSC")
                    val text = runBlocking { controller.readObjectText(obj.reference, cell, name) }
                    assertNotNull(text.text); assertTrue(text.text!!.isNotBlank())
                    compose.onNodeWithTag("object-attachment-TXTDSC").assertTextEquals(text.text!!)
                    observations.put(JSONObject().put("case", "attachment").put("name", name).put("characters", text.text!!.length))
                }
                observations.put(JSONObject().put("case", sample.getString("case")).put("class", kind).put("index", index).put("cell", cell)
                    .put("attributesMatch", true).put("hits", found.size).put("segments", engine.status.value.objectSelection.segments))
                capture("${sample.getString("case")}-selected")
                val sourceGeneration = engine.status.value.chartSourceGeneration
                val sourceKey = obj.reference.sourceKey
                compose.onNodeWithTag("zoom-in").performClick(); settled(.5)
                assertTrue(engine.status.value.objectSelection.visible)
                engine.setViewport(engine.status.value.viewport.copy(rotationRadians = .4)); settled(.5)
                assertEquals(sourceGeneration, engine.status.value.chartSourceGeneration); assertEquals(sourceKey, engine.status.value.objectSelection.sourceKey)
                assertTrue(engine.status.value.objectSelection.visible)
                val style = engine.status.value.presentation
                assertTrue(runBlocking { engine.applyPresentationProfile(style.copy(styleId = "paper", symbols = ChartSymbolSet.DEFAULT, landRelief = false)) }.success); settled()
                assertEquals(index, engine.status.value.objectSelection.featureIndex); assertTrue(engine.status.value.objectSelection.visible)
                assertTrue(runBlocking { engine.applyPresentationProfile(ChartPresentation("redline")) }.success); settled()
                assertTrue(engine.status.value.objectSelection.visible)
                assertTrue(runBlocking { engine.applyPresentationProfile(ChartPresentation("paper")) }.success); settled()
                compose.onNodeWithTag("zoom-in").assertIsDisplayed()
                assertEquals(ChartObjectQueryState.STALE, runBlocking { engine.queryObjects(request) }.state)
                // Pan away hides the marker, a return restores the same source-bound object.
                engine.setViewport(engine.status.value.viewport.copy(latitude = position.latitude + .5, longitude = position.longitude + .5)); settled()
                assertFalse(engine.status.value.objectSelection.visible)
                engine.setViewport(engine.status.value.viewport.copy(latitude = position.latitude, longitude = position.longitude)); settled()
                assertTrue(engine.status.value.objectSelection.visible)
                compose.onNodeWithTag("object-close").performClick(); compose.waitUntil(20_000) { engine.status.value.objectSelection.featureIndex == -1 }
                compose.onNodeWithTag("object-info-panel").assertDoesNotExist(); capture("${sample.getString("case")}-cleared")
            }
            // The map tap belongs exclusively to the route draft while that tool is active.
            compose.onNodeWithTag("map-dock-toggle").performClick()
            compose.onNodeWithTag("nav-ROUTES").performClick()
            compose.onNodeWithText("+ Neue Route").performScrollTo().performClick()
            compose.onNodeWithTag("panel-toggle").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("screen-panel").assertDoesNotExist()
            settled()
            capture("route-before-tap")
            val routeFrame = requireNotNull(engine.status.value.confirmedFrame)
            windowTap(ScreenPoint(routeFrame.geometry.surfaceWidth / 2.0, routeFrame.geometry.surfaceHeight / 2.0))
            compose.waitForIdle()
            capture("route-after-tap")
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("zoom-in").fetchSemanticsNodes().isNotEmpty() }
            if (compose.onAllNodesWithTag("nav-ROUTES").fetchSemanticsNodes().isEmpty())
                compose.onNodeWithTag("map-dock-toggle").performClick()
            compose.onNodeWithTag("nav-ROUTES").performClick()
            compose.waitForIdle()
            capture("route-panel-reopened")
            File(output, "route-semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
            compose.waitUntil(20_000) { compose.onAllNodesWithText("WGS84", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(-1, engine.status.value.objectSelection.featureIndex)
            compose.onNodeWithTag("object-info-panel").assertDoesNotExist()
            observations.put(JSONObject().put("case", "route-tap-exclusive").put("passed", true))
            capture("route-tap-exclusive")
            observations.put(JSONObject().put("passed", true).put("normalPackageUntouched", true)); capture("passed")
            println("OBJECT_SELECTION_EVIDENCE ${output.absolutePath}")
        } finally { scenario.close() }
    }
}
