package com.no5.helm

import android.graphics.Bitmap
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.InputDevice
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.chart.ChartEngine
import com.no5.helm.chart.ChartViewport
import com.no5.helm.chart.RendererCaptureConfig
import com.no5.helm.chart.RendererBenchmarkConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real production Activity, window-input dispatch, JNI camera and GLES Surface.
 * Buildable in CI; execution requires a connected Android device/emulator.
 */
class ChartTouchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    // Test-only read access; gestures never call the engine/controller directly.
    private val engine get() = MainActivity::class.java.getDeclaredField("engine").let {
        it.isAccessible = true; it.get(compose.activity) as ChartEngine
    }
    private var downTime = 0L

    private fun awaitChart() {
        compose.waitUntil(120_000) {
            val s = engine.status.value
            s.chartLoaded && s.surfaceAttached && s.confirmedFrame != null
        }
        compose.onNodeWithTag("renderer-slot").assertExists()
    }

    private fun point(x: Float = .5f, y: Float = .5f): Offset {
        var result: Offset? = null
        compose.activityRule.scenario.onActivity { activity ->
            fun find(v: View): TextureView? = if (v is TextureView) v else if (v is ViewGroup)
                (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) } else null
            val view = requireNotNull(find(activity.window.decorView))
            val location = IntArray(2); view.getLocationOnScreen(location)
            result = Offset(location[0] + view.width * x, location[1] + view.height * y)
        }
        return requireNotNull(result)
    }

    private fun send(action: Int, vararg points: Pair<Int, Offset>) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val event = MotionEvent.obtain(downTime, now, action, points.size,
            points.map { (id, _) -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray(),
            points.map { (_, p) -> MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = 1f; size = 1f } }.toTypedArray(),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        SystemClock.sleep(16) // Keep samples in one gesture while native frames render asynchronously.
    }

    private fun openDock() {
        if(compose.onAllNodesWithTag("map-dock").fetchSemanticsNodes().isEmpty()) {
            var reserved=0;var density=1f
            compose.activityRule.scenario.onActivity { activity ->
                density=activity.resources.displayMetrics.density
                reserved=androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.mandatorySystemGestures() or
                    androidx.core.view.WindowInsetsCompat.Type.navigationBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout())?.bottom?:0
            }
            // Inject inside the accessible app band; an outside-window Home swipe is OS-owned.
            compose.onNodeWithTag("no5-shell").performTouchInput {
                down(Offset(center.x,height-reserved-2f));moveBy(Offset(0f,-80f*density));up()
            }
            compose.onNodeWithTag("map-dock").assertIsDisplayed();compose.waitForIdle()
        }
    }
    private fun navigate(destination:String) {
        openDock();compose.onNodeWithTag("nav-$destination").performTouchInput { click(center) };compose.waitForIdle()
    }
    private fun drag(at: Offset = point(.875f,.6f)) {
        send(MotionEvent.ACTION_DOWN, 7 to at)
        for (step in 1..8) send(MotionEvent.ACTION_MOVE, 7 to at + Offset(step * 15f, 0f))
        send(MotionEvent.ACTION_UP, 7 to at + Offset(120f, 0f))
    }
    private fun waitForCamera(check: (ChartViewport) -> Boolean) {
        compose.waitUntil(30_000) { engine.status.value.confirmedFrame != null && check(engine.status.value.viewport) }
    }
    private fun screenshot(): Bitmap {
        val captured = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        return try { requireNotNull(captured.copy(Bitmap.Config.ARGB_8888, false)) } finally { captured.recycle() }
    }
    private fun chooseTouchOutlines(enabled:Boolean) {
        navigate("SETTINGS")
        val control=compose.onNodeWithTag("touch-outlines-switch").performScrollTo()
        if(engine.status.value.touchOutlinesEnabled!=enabled)control.performClick()
        compose.waitUntil(10_000){engine.status.value.touchOutlinesEnabled==enabled}
        if(enabled)control.assertIsOn()else control.assertIsOff()
        navigate("CHART");awaitChart()
    }
    @Test fun fullModeRendersWhileFingerIsHeldAndSurvivesActivityRecreation() {
        awaitChart();val original=engine.status.value.touchOutlinesEnabled
        try {
            chooseTouchOutlines(false)
            for(action in listOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL)) {
                val before=engine.status.value.confirmedFrame!!;val at=point(.5f,.4f)
                send(MotionEvent.ACTION_DOWN,7 to at)
                for(step in 1..4)send(MotionEvent.ACTION_MOVE,7 to at+Offset(step*15f,0f))
                // No last-UP yet: the ordinary full renderer must already publish a new camera frame.
                compose.waitUntil(60_000){val s=engine.status.value;s.confirmedFrame!=null&&s.confirmedFrame!=before&&s.touchPreview==null}
                assertFalse(engine.status.value.touchOutlinesEnabled)
                assertTrue(abs(engine.status.value.viewport.longitude-before.viewport.longitude)>1e-8)
                send(action,7 to at+Offset(60f,0f));awaitChart();assertNull(engine.status.value.touchPreview)
            }
            compose.activityRule.scenario.recreate();awaitChart()
            assertFalse(engine.status.value.touchOutlinesEnabled)
            chooseTouchOutlines(false)
        } finally { chooseTouchOutlines(original) }
    }
    @Test fun lastUpAndCancelRevealFullMapOnlyAfterConfirmedConsumerFrame() {
        awaitChart()
        val original=engine.status.value.touchOutlinesEnabled
        try {
        chooseTouchOutlines(true)
        assertTrue("This regression must exercise the actual outline/timestamp path",engine.status.value.touchOutlinesAvailable && engine.status.value.outlines!=null)
        fun outline(view:View):View? = if(view.javaClass.simpleName=="ChartOutlineView")view else if(view is ViewGroup)
            (0 until view.childCount).firstNotNullOfOrNull{outline(view.getChildAt(it))}else null
        for(action in listOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL)) {
            val p=point()
            send(MotionEvent.ACTION_DOWN,7 to p)
            for(step in 1..4)send(MotionEvent.ACTION_MOVE,7 to p+Offset(step*60f,0f))
            compose.waitUntil(10_000){engine.status.value.touchPreview?.let{it.held&&it.showing}==true}
            val desired=engine.status.value.viewport
            assertNull(engine.status.value.confirmedFrame)
            compose.activityRule.scenario.onActivity { assertEquals(View.VISIBLE,requireNotNull(outline(it.window.decorView)).visibility) }
            send(action,7 to p+Offset(240f,0f))
            compose.waitUntil(60_000){val s=engine.status.value;s.confirmedFrame!=null&&s.touchPreview==null&&!s.presentationPending}
            compose.waitForIdle()
            val actual=engine.status.value.confirmedFrame!!.viewport
            assertEquals(desired.latitude,actual.latitude,1e-9);assertEquals(desired.longitude,actual.longitude,1e-9)
            assertEquals(desired.metresToPixels,actual.metresToPixels,1e-12);assertEquals(desired.rotationRadians,actual.rotationRadians,1e-12)
            compose.activityRule.scenario.onActivity { assertEquals("Actual mounted overlay must no longer cover the consumed full frame",View.INVISIBLE,requireNotNull(outline(it.window.decorView)).visibility) }
        }
        } finally { chooseTouchOutlines(original) }
    }
    @Test fun brandAppSwitcherBackAndCloseKeepTheChartInteractive() {
        awaitChart()
        val originalEngine = engine
        val before = engine.status.value.viewport
        compose.onNodeWithTag("header-brand").performClick()
        compose.onNodeWithTag("app-switcher").assertIsDisplayed()
        listOf("HELM", "MAP", "HOME", "SETTINGS").forEach {
            compose.onNodeWithTag("app-switch-$it").assertIsDisplayed()
        }
        pressBack(); compose.waitForIdle()
        compose.onNodeWithTag("app-switcher").assertDoesNotExist()
        assertSame(originalEngine, engine)
        assertEquals(before, engine.status.value.viewport)
        compose.onNodeWithTag("header-brand").performClick()
        compose.onNodeWithTag("app-switcher-close").performClick()
        compose.onNodeWithTag("app-switcher").assertDoesNotExist()
        drag()
        waitForCamera { abs(it.longitude - before.longitude) > 1e-8 || abs(it.latitude - before.latitude) > 1e-8 }
        compose.onNodeWithTag("header").assertIsDisplayed()
        openDock()
        compose.onNodeWithTag("map-dock").assertIsDisplayed()
    }
    @Test fun headerAndRailSurviveRealWindowAndSurfaceCompositing() {
        awaitChart(); compose.waitForIdle()
        val header = compose.onNodeWithTag("header").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        openDock()
        val rail = compose.onNodeWithTag("map-dock").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val map = compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        assertTrue(header.bottom <= map.top); assertTrue(rail.left >= map.left && rail.right <= map.right && rail.bottom <= map.bottom)
        lateinit var windowPixels: Bitmap
        val screenOrigin = IntArray(2)
        val copied = CountDownLatch(1); val result = AtomicInteger(-1)
        compose.activityRule.scenario.onActivity { activity ->
            val decor = activity.window.decorView
            decor.getLocationOnScreen(screenOrigin)
            windowPixels = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity.window, windowPixels, { status -> result.set(status); copied.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue("Window PixelCopy finishes", copied.await(10, TimeUnit.SECONDS))
        assertEquals("Actual app window pixels available", PixelCopy.SUCCESS, result.get())
        val composed = screenshot()
        try {
            val output = requireNotNull(instrumentation.targetContext.getExternalFilesDir("ui-evidence"))
            File(output, "window.png").outputStream().use { windowPixels.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(output, "screen-composite.png").outputStream().use { composed.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Compare the complete app window against the displayed composite.
            // Both chrome and actual chart geometry must reach the app window.
            val brand = header.copy(right = header.left + header.width * .25f)
            val chartInterior = map.copy(left = map.left + map.width * .35f, right = map.left + map.width * .65f,
                top = map.top + map.height * .2f, bottom = map.top + map.height * .7f)
            for ((name, bounds) in listOf("header brand" to brand, "native map dock" to rail, "GLES chart inside app window" to chartInterior)) {
                var close = 0; var total = 0; val colors = mutableSetOf<Int>()
                for (y in bounds.top.toInt() until bounds.bottom.toInt() step 2)
                    for (x in bounds.left.toInt() until bounds.right.toInt() step 2) {
                        val expected = windowPixels.getPixel(x, y)
                        val actual = composed.getPixel(x + screenOrigin[0], y + screenOrigin[1])
                        colors += expected
                        val delta = maxOf(abs(android.graphics.Color.red(expected) - android.graphics.Color.red(actual)),
                            abs(android.graphics.Color.green(expected) - android.graphics.Color.green(actual)),
                            abs(android.graphics.Color.blue(expected) - android.graphics.Color.blue(actual)))
                        if (delta <= 12) close++; total++
                    }
                assertTrue("$name contains actual drawn content", colors.size > 8)
                assertTrue("$name reaches displayed composition: $close/$total", total > 0 && close.toDouble() / total > .95)
            }
        } finally { windowPixels.recycle(); composed.recycle() }
    }
    @Test fun androidStatusBarStaysHiddenAfterBackgroundAndResume() {
        awaitChart()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitChart()
        compose.waitUntil(30_000) {
            val view = compose.activity.window.decorView
            val insets = view.rootWindowInsets
            insets != null && WindowInsetsCompat.toWindowInsetsCompat(insets, view)
                .getInsets(WindowInsetsCompat.Type.statusBars()).top == 0
        }
        compose.activityRule.scenario.onActivity { activity ->
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0)
        }
        val header = compose.onNodeWithTag("header").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        openDock()
        val rail = compose.onNodeWithTag("map-dock").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val map = compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, header.top, .5f)
        assertTrue(header.bottom <= map.top); assertTrue(rail.left >= map.left && rail.right <= map.right && rail.bottom <= map.bottom)
    }
    @Test fun liveProfilerRecordsRealFramesAndStopsWhenSurfaceDetaches() {
        awaitChart()
        assertTrue(runBlocking { engine.startRendererCapture(RendererCaptureConfig(durationSeconds = 20)).success })
        drag()
        compose.waitUntil(30_000) { engine.status.value.rendererCapture.frames >= 2 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(30_000) { !engine.status.value.rendererCapture.active }
        val result = runBlocking { engine.stopRendererCapture() }
        assertTrue(result.success)
        val report = JSONObject(result.message)
        assertEquals("no5-renderer-capture/1", report.getString("schema"))
        val ui = report.getJSONObject("ui")
        assertTrue(ui.getBoolean("independentObservations"))
        assertTrue(ui.getBoolean("phasesNonAdditive"))
        val uiSummary = ui.getJSONObject("summary")
        if (uiSummary.has("window.total")) assertTrue(uiSummary.getJSONObject("window.total").isNull("threadCpuMs"))
        assertEquals("surface detached", report.getString("stopReason"))
        assertTrue(report.getInt("frames") >= 2)
        assertTrue(report.getJSONObject("summary").has("outsideNativeWallMs"))
        val viewport = report.getJSONObject("metadata").getJSONObject("viewport")
        assertFalse(viewport.has("latitude")); assertFalse(viewport.has("longitude"))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitChart()
    }
    private fun changedPixels(before: Bitmap, after: Bitmap): Int {
        val width = minOf(before.width, after.width) / 3
        val height = minOf(before.height, after.height) / 3
        val left = before.width / 3; val top = before.height / 3
        val a = IntArray(width * height); val b = IntArray(a.size)
        before.getPixels(a, 0, width, left, top, width, height)
        after.getPixels(b, 0, width, left, top, width, height)
        return a.indices.count { a[it] != b[it] }
    }

    @Test fun windowDispatchedDragMovesActualNativeCameraAndChartPixels() {
        awaitChart(); val beforeView = engine.status.value.viewport; val before = screenshot()
        try {
            drag()
            waitForCamera { abs(it.longitude - beforeView.longitude) > 1e-5 || abs(it.latitude - beforeView.latitude) > 1e-5 }
            assertEquals(beforeView.metresToPixels, engine.status.value.viewport.metresToPixels, 1e-6)
            compose.waitUntil(30_000) { val after = screenshot(); try { changedPixels(before, after) > 2000 } finally { after.recycle() } }
            compose.onNodeWithTag("target-overlay").assertDoesNotExist()
        } finally { before.recycle() }
    }

    @Test fun windowDispatchedPinchAndRotationChangeActualNativeCamera() {
        awaitChart(); val before = engine.status.value.viewport; val p = point()
        send(MotionEvent.ACTION_DOWN, 7 to p + Offset(-80f, 0f))
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 7 to p + Offset(-80f, 0f), 31 to p + Offset(80f, 0f))
        var d = Offset(80f, 0f)
        for (step in 1..12) {
            val angle = PI * step / 36; val radius = 80.0 + step * 4
            d = Offset((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
            send(MotionEvent.ACTION_MOVE, 7 to p - d, 31 to p + d)
        }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 7 to p - d, 31 to p + d)
        send(MotionEvent.ACTION_UP, 7 to p - d)
        waitForCamera { it.metresToPixels > before.metresToPixels * 1.5 && abs(atan2(sin(it.rotationRadians - before.rotationRadians), cos(it.rotationRadians - before.rotationRadians))) > .9 }
        assertEquals(before.metresToPixels * 1.6, engine.status.value.viewport.metresToPixels, .001)
    }

    @Test fun remainingPointerAndCancelDoNotPoisonNextNativeGesture() {
        awaitChart(); val before = engine.status.value.viewport; val p = point()
        send(MotionEvent.ACTION_DOWN, 7 to p + Offset(-80f, 0f))
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 7 to p + Offset(-80f, 0f), 31 to p + Offset(80f, 0f))
        send(MotionEvent.ACTION_MOVE, 7 to p + Offset(-120f, 0f), 31 to p + Offset(120f, 0f))
        send(MotionEvent.ACTION_POINTER_UP, 7 to p + Offset(-120f, 0f), 31 to p + Offset(120f, 0f))
        send(MotionEvent.ACTION_MOVE, 31 to p + Offset(160f, 0f))
        send(MotionEvent.ACTION_CANCEL, 31 to p + Offset(160f, 0f))
        waitForCamera { it.metresToPixels > before.metresToPixels * 1.4 }
        val cancelled = engine.status.value.viewport
        drag(); waitForCamera { abs(it.longitude - cancelled.longitude) > 1e-5 || abs(it.latitude - cancelled.latitude) > 1e-5 }
        assertEquals(before.metresToPixels * 1.5, engine.status.value.viewport.metresToPixels, .001)
    }

    @Test fun opaquePanelsAndScreenBackTransitionsKeepNativeTouchRouting() {
        awaitChart()
        navigate("ROUTES"); compose.waitForIdle(); awaitChart()
        val before = engine.status.value.viewport
        drag(point(.3f, .6f)); SystemClock.sleep(500); awaitChart()
        assertEquals(before, engine.status.value.viewport)
        drag(); waitForCamera { abs(it.longitude - before.longitude) > 1e-5 }
        pressBack(); compose.waitForIdle()
        for (destination in listOf("ROUTES", "AIS", "LAYERS", "SETTINGS")) {
            navigate(destination)
            compose.onNodeWithTag("panel-toggle").performTouchInput { click(center) }; compose.waitForIdle(); awaitChart()
            val old = engine.status.value.viewport
            drag(); waitForCamera { abs(it.longitude - old.longitude) > 1e-5 || abs(it.latitude - old.latitude) > 1e-5 }
            pressBack(); compose.waitForIdle()
            compose.onNodeWithTag("active-nav-CHART", useUnmergedTree = true).assertExists()
        }
    }

    @Test fun benchmarkCancellationRetainsReportAndRestoresUserCameraAndStyle() {
        awaitChart()
        val before = engine.status.value
        val start = runBlocking { engine.startRendererBenchmark(RendererBenchmarkConfig(1)) }
        assertTrue(start.message, start.success)
        compose.waitUntil(120_000) {
            val state = engine.status.value
            !state.rendererBenchmark.active || state.rendererCapture.frames >= 6
        }
        assertTrue(engine.status.value.rendererBenchmark.message, engine.status.value.rendererBenchmark.active)
        engine.cancelRendererBenchmark()
        compose.waitUntil(120_000) { !engine.status.value.rendererBenchmark.active }
        val after = engine.status.value
        assertEquals("cancelled", after.rendererBenchmark.phase)
        assertTrue(after.rendererBenchmark.message, after.rendererBenchmark.restored)
        assertEquals(before.presentation, after.presentation)
        assertEquals(before.viewport, after.viewport)
        val report = runBlocking { engine.readRendererReport() }
        assertTrue(report.message, report.success)
        val json = JSONObject(report.message)
        assertEquals("no5-renderer-style-benchmark/1", json.getString("schema"))
        assertEquals(after.rendererBenchmark.runId, json.getString("runId"))
        assertEquals("benchmark-${after.rendererBenchmark.runId}.json", after.rendererBenchmark.reportFile)
        assertEquals(com.no5.helm.app.BuildConfig.SOURCE_COMMIT, json.getJSONObject("build").getString("sourceCommit"))
    }

    @Test fun settingsStyleAndRendererDismissLeaveNativeMapInteractive() {
        awaitChart(); val presentation = engine.status.value.presentation
        navigate("SETTINGS")
        compose.onNodeWithTag("chart-style-settings").performScrollTo().performTouchInput { click(center) }
        compose.onNodeWithText("Schließen").performTouchInput { click(center) }; compose.waitForIdle()
        assertEquals(presentation, engine.status.value.presentation)
        compose.onNodeWithText("Karte / Offline").performTouchInput { click(center) }
        compose.onNodeWithTag("renderer-diagnostics").performScrollTo().performTouchInput { click(center) }
        compose.onNodeWithText("S52 direct GLES -> FBO -> Surface", substring = true).assertExists()
        compose.onNodeWithText("Schließen").performTouchInput { click(center) }; compose.waitForIdle(); awaitChart()
        val before = engine.status.value.viewport
        drag(); waitForCamera { abs(it.longitude - before.longitude) > 1e-5 || abs(it.latitude - before.latitude) > 1e-5 }
    }

    @Test fun silentNavigationTapsRetainActualSurfaceGeometry() {
        awaitChart(); val before = engine.status.value.viewport
        val frame = engine.status.value.confirmedFrame!!
        val size = compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().size
        assertEquals(size.width, frame.geometry.surfaceWidth); assertEquals(size.height, frame.geometry.surfaceHeight)
        repeat(3) { val p = point(); send(MotionEvent.ACTION_DOWN, 7 to p); send(MotionEvent.ACTION_UP, 7 to p) }
        compose.onNodeWithTag("target-overlay").assertDoesNotExist()
        assertEquals(before, engine.status.value.viewport)
    }
}
