package com.no5.helm.ui

import android.view.Surface
import android.graphics.SurfaceTexture
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.InputDevice
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.helm.chart.*
import com.no5.helm.model.HelmState
import com.no5.helm.model.*
import com.no5.preparation.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.net.Uri
import com.no5.helm.library.*
import org.robolectric.shadows.ShadowDialog

/** Android 10 Compose/TextureView plumbing; recording engine deliberately replaces GLES. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeHelmScreenTest {
    @Test fun everyDisplayChoiceIsReachableAndWritesTheSelectedValue() {
        com.no5.ui.UiLanguage.acceptConfirmed(activity.get(), "de")
        tap("nav-SETTINGS")
        fun target(key: String): View {
            fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
            return all(activity.get().window.decorView).single { it.tag == key }
        }
        fun click(key: String) { compose.runOnIdle { val v=target(key);assertTrue(v.isEnabled);v.performClick() };compose.waitForIdle() }
        val prefs=activity.get().getSharedPreferences("native-screens-v1",Context.MODE_PRIVATE)
        click("no5-row-map-settings-display")
        for ((page,key,values) in listOf(
            Triple("distance","distanceUnit",DistanceUnit.entries.map{it.name}),
            Triple("speed","speedUnit",SpeedUnit.entries.map{it.name}),
            Triple("dimming","uiDimming",listOf("30","60","100")))) {
            click("no5-row-map-display-$page")
            for(value in values){
                val tag=if(page=="dimming")"no5-row-map-dimming-$value"else"no5-row-map-unit-$value"
                click(tag)
                compose.runOnIdle {
                    assertTrue(target(tag).isSelected)
                    assertEquals(value,if(page=="dimming")prefs.getInt(key,-1).toString()else prefs.getString(key,null))
                }
            }
            click("no5-workspace-back")
        }
    }
    @Test fun newRouteUsesCreationLanguageAndKeepsItsNameWhenLanguageChanges() {
        com.no5.ui.UiLanguage.acceptConfirmed(activity.get(), "en")
        tap("nav-ROUTES")
        compose.waitForIdle()
        fun target(key: String): View {
            fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
            return all(activity.get().window.decorView).single { it.tag == key }
        }
        compose.runOnIdle { target("no5-action-route-new").performClick() }
        compose.waitForIdle()
        compose.runOnIdle {
            val name = target("no5-field-route-name") as android.widget.EditText
            assertEquals("New route", name.text.toString())
            name.setText("User name remains unchanged")
            com.no5.ui.UiLanguage.acceptConfirmed(activity.get(), "af")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("User name remains unchanged", (target("no5-field-route-name") as android.widget.EditText).text.toString())
            com.no5.ui.UiLanguage.acceptConfirmed(activity.get(), "de")
        }
    }
    @Test fun objectInfoEntryKeepsTheChartPickableThenOpensRealHits() {
        // ui-shell does not package chart-engine assets. Mount the exact production
        // dictionaries rather than replacing the real object-info implementation.
        val host = activity.get()
        val archive = host.cacheDir.resolve("object-info-production-dictionaries.zip")
        java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
            for (name in listOf("s57attributes.csv", "s57expectedinput.csv")) {
                zip.putNextEntry(java.util.zip.ZipEntry("assets/$name"))
                File("../chart-engine/src/main/assets/$name").inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val addAssetPath = android.content.res.AssetManager::class.java
            .getDeclaredMethod("addAssetPath", String::class.java).apply { isAccessible = true }
        assertTrue((addAssetPath.invoke(host.assets, archive.absolutePath) as Int) > 0)
        val generation = engine.status.value.chartSourceGeneration
        engine.objectResults = (1..2).map { index -> ChartObject(
            ChartObjectReference("entry-fixture", index, generation), "$index", "WRECKS", "POINT",
            "SYNTHETIC_CELL", GeoProjection(42.36, -71.04), 0.0, mapOf("OBJNAM" to "Fixture $index")) }
        openDock()
        tap("inspect-mode")
        compose.waitForIdle()
        fun tagged(key: String): List<View> {
            fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
            return all(activity.get().window.decorView).filter { it.tag == key }
        }
        compose.runOnIdle {
            assertTrue("A query prompt must not cover the chart before the point is picked",
                tagged("no5-row-object-query-status").isEmpty())
        }
        val point = mapPoint(.65f, .5f)
        dispatch(MotionEvent.ACTION_DOWN, 7 to point)
        dispatch(MotionEvent.ACTION_UP, 7 to point)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, engine.objectQueries)
            assertEquals(1, tagged("no5-row-object-pick-entry-fixture-1").size)
            assertEquals(1, tagged("no5-row-object-pick-entry-fixture-2").size)
            assertTrue("The open hit page must block underlying chart input",
                nativeView.blockedRegions.any { it.contains(Offset(nativeView.width * .65f, nativeView.height * .5f)) })
            tagged("no5-row-object-pick-entry-fixture-1").single().performClick()
        }
        focusHostInputWindow(activity.get(), compose)
        compose.onNodeWithTag("object-info-panel-title").assertIsDisplayed()
        compose.onNodeWithContentDescription("Fixture 1").assertIsDisplayed()
        compose.onNodeWithTag("object-close").performClick()
        focusHostInputWindow(activity.get(), compose)
        compose.runOnIdle { tagged("no5-row-object-pick-entry-fixture-1").single().performClick() }
        compose.onNodeWithTag("object-info-panel-title").assertIsDisplayed()
        compose.onNodeWithContentDescription("Fixture 1").assertIsDisplayed()
    }
    @Test fun fastChartChoiceCanBeSelectedAndRestoredWithoutChangingTheDefault() {
        val prefs = activity.get().getSharedPreferences("native-screens-v1", Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("fastChartModeEnabled", false))
        tap("nav-SETTINGS")
        compose.onNodeWithTag("fast-chart-mode-switch").performScrollTo().assertIsOff().performClick()
        compose.waitForIdle()
        assertEquals(ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE, engine.status.value.bufferTransformMode)
        assertTrue(prefs.getBoolean("fastChartModeEnabled", false))
        compose.onNodeWithTag("touch-outlines-switch").performScrollTo().assertIsNotEnabled()
        val replacement = RecordingEngine()
        compose.runOnIdle {
            val initialViewport = replacement.status.value.viewport
            activity.get().setContent { NativeHelmScreen(replacement, helm, "http://localhost", "NOAA Boston", initialViewport, libraryActions) {} }
        }
        compose.waitForIdle()
        attachNativeSurface()
        compose.waitForIdle()
        assertEquals(ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE, replacement.status.value.bufferTransformMode)
        tap("nav-SETTINGS")
        compose.onNodeWithTag("fast-chart-mode-switch").performScrollTo().assertIsOn().performClick()
        compose.waitForIdle(); assertFalse(prefs.getBoolean("fastChartModeEnabled", true))
        assertEquals(ChartBufferTransformMode.OFF, replacement.status.value.bufferTransformMode)
    }
    @Test fun captureAndMeasurementDisableFastChartChoiceWithoutSavingIt() {
        tap("nav-SETTINGS")
        compose.onNodeWithTag("fast-chart-mode-switch").performScrollTo().assertIsOff()
        compose.runOnIdle { engine.status.value = engine.status.value.copy(rendererCapture = RendererCaptureStatus(active = true)) }
        compose.onNodeWithTag("fast-chart-mode-switch").assertIsNotEnabled()
        assertFalse(activity.get().getSharedPreferences("native-screens-v1", Context.MODE_PRIVATE).getBoolean("fastChartModeEnabled", false))
    }
    @Test fun touchRenderingChoiceIsVisibleSavedAndRestoredForReplacementEngine() {
        tap("nav-SETTINGS")
        compose.onNodeWithTag("touch-outlines-switch").performScrollTo().assertIsOn().assertContentDescriptionEquals("Touch-Outlines an/aus")
        savePanelEvidence("touch-outlines-on47")
        compose.onNodeWithTag("touch-outlines-switch").performClick();compose.waitForIdle()
        assertFalse(engine.status.value.touchOutlinesEnabled)
        val prefs=activity.get().getSharedPreferences("native-screens-v1",Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("touchOutlinesEnabled",true))
        compose.onNodeWithTag("touch-outlines-mode").assertTextEquals("Aus · vollständige Karte während Touch.")
        savePanelEvidence("touch-outlines-off47")
        val replacement=RecordingEngine()
        val replacementViewport=replacement.status.value.viewport
        compose.runOnIdle { activity.get().setContent { NativeHelmScreen(replacement,helm,"http://localhost","NOAA Boston",replacementViewport,libraryActions) {} } }
        compose.waitForIdle();assertFalse(replacement.status.value.touchOutlinesEnabled)
        tap("nav-SETTINGS");compose.onNodeWithTag("touch-outlines-switch").performScrollTo().assertIsOff().performClick();compose.waitForIdle()
        assertTrue(replacement.status.value.touchOutlinesEnabled);assertTrue(prefs.getBoolean("touchOutlinesEnabled",false))
    }
    @Test fun activeCaptureAndBenchmarkDisableTheTouchModeSwitchWithoutChangingPreference() {
        tap("nav-SETTINGS")
        compose.onNodeWithTag("touch-outlines-switch").performScrollTo().assertIsOn()
        compose.runOnIdle { engine.status.value=engine.status.value.copy(rendererCapture=RendererCaptureStatus(active=true)) }
        compose.onNodeWithTag("touch-outlines-switch").assertIsNotEnabled()
        compose.runOnIdle { engine.status.value=engine.status.value.copy(rendererCapture=RendererCaptureStatus(),rendererBenchmark=RendererBenchmarkStatus(active=true)) }
        compose.onNodeWithTag("touch-outlines-switch").assertIsNotEnabled()
        assertTrue(engine.status.value.touchOutlinesEnabled)
    }
    @Test fun nonTouchZoomCommandsStillUseTheConfirmedFrameInBothTouchModes() {
        for(enabled in listOf(false,true)) {
            tap("nav-SETTINGS");compose.onNodeWithTag("touch-outlines-switch").performScrollTo().performClick();compose.waitForIdle()
            assertEquals(enabled,engine.status.value.touchOutlinesEnabled)
            tap("nav-CHART");tap("zoom-in")
        }
        assertEquals(2,engine.zooms)
    }
    @Test fun density320SettingsImportAndDialogKeepNativeTextAndTouchSizes() {
        val phase = System.getenv("NO5_DENSITY_CAPTURE_PHASE") ?: "current"
        // Current tests must not overwrite committed historical Build37 evidence.
        val output = File("build/verification/renders/$phase").apply { mkdirs() }
        fun capture(name: String, view: View = activity.get().window.decorView) {
            compose.runOnIdle {
                val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(image))
                File(output, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                image.recycle()
            }
        }
        val measurements = org.json.JSONObject().put("density",activity.get().resources.displayMetrics.density)
            .put("fontScale",activity.get().resources.configuration.fontScale)
        tap("nav-SETTINGS")
        capture("settings-day")
        compose.onNodeWithText("Karten", substring=false).performClick()
        compose.onNodeWithTag("chart-package-import").performScrollTo().assertIsDisplayed()
        val importBounds = compose.onNodeWithTag("chart-package-import").fetchSemanticsNode().boundsInRoot
        measurements.put("import_button_px",importBounds.height)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Kartenpaket importieren",useUnmergedTree=true).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        measurements.put("import_label_height_px",layouts.single().size.height)
        capture("import-day")
        compose.runOnIdle { libraryActions.state.value = ChartLibraryUiState(busy=true,
            importProgress=ImportProgress(ImportPhase.INDEXING,"Native S57-Geometrie und Updates prüfen","US5BOSCC.000",30,79)) }
        compose.onNodeWithTag("chart-import-cancel").performScrollTo().assertIsDisplayed()
        val cancelBounds = compose.onNodeWithTag("chart-import-cancel").fetchSemanticsNode().boundsInRoot
        measurements.put("cancel_button_px",cancelBounds.height)
        capture("import-progress-day")
        compose.runOnIdle { libraryActions.state.value = ChartLibraryUiState() }
        compose.onNodeWithText("Karte / Offline").performScrollTo().performClick()
        compose.onNodeWithTag("renderer-diagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("renderer-report-export").assertExists()
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        capture("renderer-dialog-day",requireNotNull(dialog.window).decorView)
        File(output,"sizes.json").writeText(measurements.toString(2))
        if (phase == "build37") {
            assertEquals(2f,activity.get().resources.displayMetrics.density,0f)
            assertTrue("Import keeps 48 Android dp / 96 physical px",importBounds.height>=96f)
            assertTrue("Cancel keeps 48 Android dp / 96 physical px",cancelBounds.height>=96f)
            assertTrue("14sp button label is readable at density320",layouts.single().size.height>=30)
        }
        assertEquals("Panels/dialogs never remount the chart",1,engine.attaches)
    }
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<No5ImmersiveActivity>
    private val engine = RecordingEngine()
    private val helm = MutableStateFlow(HelmState())
    private val libraryActions = object : ChartLibraryActions {
        override val state = MutableStateFlow(ChartLibraryUiState())
        var selected: ChartSelection? = null
        var cancels = 0
        override fun importPackage(uri: Uri) = Unit
        override fun activate(selection: ChartSelection) { selected = selection }
        override fun cancelImport() { cancels++ }
    }
    private lateinit var nativeView: ChartSurfaceView
    private lateinit var testTexture: RecordingSurfaceTexture
    // Robolectric does not implement SurfaceTexture.isReleased as real driver
    // state. Observe release invocation, with no claim of device GPU execution.
    private class RecordingSurfaceTexture : SurfaceTexture(0) {
        var releases = 0
        override fun release() { releases++; super.release() }
    }
    @Before fun setup() {
        activity = Robolectric.buildActivity(No5ImmersiveActivity::class.java)
        // Match MainActivity's manifest theme before lifecycle window setup.
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup().visible().windowFocusChanged(true)
        installContent()
        compose.waitForIdle()
        attachNativeSurface()
    }
    private fun installContent() {
        // Keep the same Compose call site across both Activity instances, as
        // MainActivity.onCreate does. Saveable keys include the composition path.
        val viewport=engine.status.value.viewport
        activity.get().setContent { NativeHelmScreen(engine,helm,"http://localhost","NOAA Boston",viewport,libraryActions) {} }
    }
    private fun attachNativeSurface() {
        // Host tests do not generate an actual display/GLES producer.
        compose.runOnIdle {
            fun find(view: View): ChartSurfaceView? = if (view is ChartSurfaceView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val native = requireNotNull(find(activity.get().window.decorView))
            nativeView = native
            testTexture = RecordingSurfaceTexture()
            native.surfaceTextureListener!!.onSurfaceTextureAvailable(testTexture, native.width, native.height)
        }
        compose.waitForIdle()
    }
    @After fun close() {
        if (::nativeView.isInitialized && nativeView.renderSurface != null)
            nativeView.surfaceTextureListener!!.onSurfaceTextureDestroyed(testTexture)
        if (::activity.isInitialized) activity.pause().stop().destroy()
    }
    @Test fun headerAndRailHaveDrawnPixelsBesideTheAttachedSurface() {
        val header = compose.onNodeWithTag("header").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        openDock()
        val rail = compose.onNodeWithTag("map-dock").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val map = compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        assertTrue("Header must stay above map bounds", header.bottom <= map.top)
        val scale=map.width/2000f
        assertEquals(1070f*scale,map.height,1f)
        assertTrue("Dock overlays map inside the central safe area",(rail.left-map.left)/scale>=344f-1 && (rail.right-map.left)/scale<=1656f+1)
        compose.runOnIdle {
            val location = IntArray(2); nativeView.getLocationInWindow(location)
            assertEquals("Native Surface View left matches map", map.left, location[0].toFloat(), .5f)
            assertEquals("Native Surface View top matches map", map.top, location[1].toFloat(), .5f)
            assertEquals(map.width.toInt(), nativeView.width); assertEquals(map.height.toInt(), nativeView.height)
            val decor = activity.get().window.decorView
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            try {
                decor.draw(Canvas(bitmap))
                val output = File("build/ui-evidence/native-shell-${decor.width}x${decor.height}.png")
                output.parentFile!!.mkdirs(); output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                fun colors(bounds: androidx.compose.ui.geometry.Rect): Int {
                    val values = mutableSetOf<Int>()
                    for (y in bounds.top.toInt() until bounds.bottom.toInt() step 2)
                        for (x in bounds.left.toInt() until bounds.right.toInt() step 2) values += bitmap.getPixel(x, y)
                    return values.size
                }
                assertTrue("Header artwork/text must produce visible pixels", colors(header) > 8)
                assertTrue("Rail icons/text must produce visible pixels", colors(rail) > 8)
            } finally { bitmap.recycle() }
        }
    }
    @Test @Config(qualifiers = "w1000dp-h464dp-land-xhdpi")
    fun oemSizedWindowKeepsHeaderAndRailBesideTheAttachedSurface() {
        headerAndRailHaveDrawnPixelsBesideTheAttachedSurface()
        val map = compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        assertEquals(1546.67f,map.width,1f);assertEquals(827.47f,map.height,1f)
        Destination.entries.forEach { destination -> compose.onNodeWithTag("nav-${destination.name}").assertIsDisplayed() }
    }
    private var touchTime = 1000L
    private var downTime = 1000L
    private fun dispatchNow(action:Int,vararg points:Pair<Int,Offset>,requireHandled:Boolean=true) {
        touchTime+=32
        if(action==MotionEvent.ACTION_DOWN)downTime=touchTime
        val event=MotionEvent.obtain(downTime,touchTime,action,points.size,
            points.map{(id,_)->MotionEvent.PointerProperties().apply{this.id=id;toolType=MotionEvent.TOOL_TYPE_FINGER}}.toTypedArray(),
            points.map{(_,p)->MotionEvent.PointerCoords().apply{x=p.x;y=p.y;pressure=1f;size=1f}}.toTypedArray(),
            0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
        try {
            val accepted=activity.get().dispatchTouchEvent(event)
            if(requireHandled&&action!=MotionEvent.ACTION_CANCEL)assertTrue("Activity must dispatch the touchscreen stream",accepted)
        } finally { event.recycle() }
    }
    private fun dispatch(action:Int,vararg points:Pair<Int,Offset>,requireHandled:Boolean=true) {
        compose.runOnIdle{dispatchNow(action,*points,requireHandled=requireHandled)}
        compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
    }
    private fun mapPoint(x: Float = .5f, y: Float = .5f): Offset {
        val b = compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        return Offset(b.left + b.width * x, b.top + b.height * y)
    }
    private fun edgePoint():Offset {
        val root=compose.onNodeWithTag("no5-shell").fetchSemanticsNode().boundsInRoot
        return Offset(root.center.x,root.bottom-2f)
    }
    private fun openDock() {
        if(compose.onAllNodesWithTag("map-dock").fetchSemanticsNodes().isEmpty()) {
            val p=edgePoint()
            dispatch(MotionEvent.ACTION_DOWN,7 to p)
            dispatch(MotionEvent.ACTION_MOVE,7 to p+Offset(0f,-160f))
            dispatch(MotionEvent.ACTION_UP,7 to p+Offset(0f,-160f))
            compose.onNodeWithTag("map-dock").assertIsDisplayed()
        }
    }
    private fun tap(tag: String) {
        if(tag.startsWith("nav-") && compose.onAllNodesWithTag("map-dock").fetchSemanticsNodes().isEmpty())openDock()
        val p = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center
        dispatch(MotionEvent.ACTION_DOWN, 7 to p); dispatch(MotionEvent.ACTION_UP, 7 to p)
    }
    private fun drag(p: Offset = mapPoint(.875f,.6f)) {
        val before = engine.motions.sumOf { it.pan.x }
        dispatch(MotionEvent.ACTION_DOWN, 7 to p)
        for (step in 1..4) dispatch(MotionEvent.ACTION_MOVE, 7 to p + Offset(30f * step, 0f))
        dispatch(MotionEvent.ACTION_UP, 7 to p + Offset(120f, 0f))
        assertEquals("Free chart pixels must retain the full drag", before + 120.0, engine.motions.sumOf { it.pan.x }, .001)
    }
    @Test fun bottomEdgeOpensDockWithoutMovingTheMapAndKeepsTheOpenerHiddenUntilUp() {
        val before=engine.motions.size
        compose.onNodeWithTag("map-dock-toggle").assertIsDisplayed()
        val p=edgePoint()
        dispatch(MotionEvent.ACTION_DOWN,7 to p)
        compose.onNodeWithTag("map-dock-toggle").assertDoesNotExist()
        dispatch(MotionEvent.ACTION_MOVE,7 to p-Offset(0f,160f))
        compose.onNodeWithTag("map-dock-toggle").assertDoesNotExist()
        assertTrue(nativeView.isTouchActive)
        dispatch(MotionEvent.ACTION_UP,7 to p-Offset(0f,160f))
        compose.onNodeWithTag("map-dock").assertIsDisplayed()
        compose.onNodeWithTag("map-dock-toggle").assertDoesNotExist()
        assertEquals(before,engine.motions.size)
        compose.onNodeWithTag("dock-close").performClick();compose.onNodeWithTag("map-dock").assertDoesNotExist()
        compose.onNodeWithTag("map-dock-toggle").assertIsDisplayed()
        drag()
    }
    @Test fun dockButtonKeepsItsOwnDownAndOpensOnPhysicalUpWithoutChartMotion() {
        val p=compose.onNodeWithTag("map-dock-toggle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
        val before=engine.motions.size
        dispatch(MotionEvent.ACTION_DOWN,7 to p)
        compose.onNodeWithTag("map-dock-toggle").assertIsDisplayed()
        assertFalse(nativeView.isTouchActive)
        dispatch(MotionEvent.ACTION_UP,7 to p)
        compose.onNodeWithTag("map-dock").assertIsDisplayed()
        compose.onNodeWithTag("map-dock-toggle").assertDoesNotExist()
        assertEquals(before,engine.motions.size);assertEquals(0,engine.queries)
        tap("dock-close");compose.onNodeWithTag("map-dock-toggle").assertIsDisplayed()
        tap("map-dock-toggle");compose.onNodeWithTag("map-dock").assertIsDisplayed()
    }
    @Test @Config(qualifiers="w1000dp-h800dp-land-xhdpi")
    fun bottomEdgeStillOpensWhenTheMapStageIsLetterboxed() {
        val map=compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        val root=compose.onNodeWithTag("no5-shell").fetchSemanticsNode().boundsInRoot
        assertTrue(root.bottom>map.bottom)
        openDock();assertTrue(engine.motions.isEmpty())
    }
    @Test fun ordinaryAndDiagonalChartDragsDoNotOpenTheDock() {
        // The new bottom-center opener occupies the middle of this row.
        // Ordinary chart gestures start in the free map beside that button.
        val edge=edgePoint()-Offset(160f,0f)
        for((start,delta) in listOf(edge-Offset(0f,50f) to Offset(0f,-160f),edge to Offset(120f,-100f))) {
            val before=engine.motions.size
            dispatch(MotionEvent.ACTION_DOWN,7 to start);dispatch(MotionEvent.ACTION_MOVE,7 to start+delta);dispatch(MotionEvent.ACTION_UP,7 to start+delta)
            compose.onNodeWithTag("map-dock").assertDoesNotExist();assertTrue(engine.motions.size>before)
        }
    }
    @Test fun cancelledAndMultitouchEdgeSequencesDoNotOpenAndNextDownRecovers() {
        val p=edgePoint()
        dispatch(MotionEvent.ACTION_DOWN,7 to p);dispatch(MotionEvent.ACTION_MOVE,7 to p-Offset(0f,160f));dispatch(MotionEvent.ACTION_CANCEL,7 to p-Offset(0f,160f))
        compose.onNodeWithTag("map-dock").assertDoesNotExist();compose.onNodeWithTag("north-up").assertExists()
        dispatch(MotionEvent.ACTION_DOWN,7 to p)
        dispatch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),7 to p,31 to p+Offset(60f,-40f))
        dispatch(MotionEvent.ACTION_MOVE,7 to p-Offset(0f,160f),31 to p+Offset(60f,-200f))
        dispatch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),7 to p-Offset(0f,160f),31 to p+Offset(60f,-200f))
        dispatch(MotionEvent.ACTION_UP,7 to p-Offset(0f,160f));compose.onNodeWithTag("map-dock").assertDoesNotExist()
        openDock()
    }
    @Test fun chartButtonsAndTheirMasksStayAbsentUntilTheLastPointerEnds() {
        val oldZoom=compose.onNodeWithTag("zoom-in").fetchSemanticsNode().boundsInRoot.center
        val oldOpener=compose.onNodeWithTag("map-dock-toggle").fetchSemanticsNode().boundsInRoot.center
        val p=mapPoint(.5f,.4f)
        dispatch(MotionEvent.ACTION_DOWN,7 to p)
        for(tag in listOf("north-up","zoom-in","zoom-out","position-cycle","map-dock-toggle"))compose.onNodeWithTag(tag).assertDoesNotExist()
        assertFalse(nativeView.blockedRegions.any { it.contains(Offset(oldZoom.x,oldZoom.y-130f)) })
        assertFalse(nativeView.blockedRegions.any { it.contains(Offset(oldOpener.x,oldOpener.y-130f)) })
        dispatch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),7 to p,31 to oldZoom)
        dispatch(MotionEvent.ACTION_POINTER_UP,7 to p,31 to oldZoom)
        compose.onNodeWithTag("zoom-in").assertDoesNotExist()
        compose.onNodeWithTag("map-dock-toggle").assertDoesNotExist()
        val before=engine.motions.size
        dispatch(MotionEvent.ACTION_MOVE,31 to oldZoom+Offset(0f,40f));assertTrue(engine.motions.size>before)
        dispatch(MotionEvent.ACTION_UP,31 to oldZoom+Offset(0f,40f));compose.onNodeWithTag("zoom-in").assertExists()
        compose.onNodeWithTag("map-dock-toggle").assertIsDisplayed()
    }
    @Test fun enteringTheProtectedLegendKeepsTheOpenerHiddenUntilUpOrCancel() {
        val legend=compose.onNodeWithTag("chart-legend").fetchSemanticsNode().boundsInRoot.center
        for(terminal in listOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL)) {
            val p=mapPoint(.5f,.4f)
            dispatch(MotionEvent.ACTION_DOWN,7 to p)
            val before=engine.motions.size
            dispatch(MotionEvent.ACTION_MOVE,7 to legend)
            compose.onNodeWithTag("map-dock-toggle").assertDoesNotExist()
            assertTrue(nativeView.isTouchActive)
            dispatch(MotionEvent.ACTION_MOVE,7 to p+Offset(60f,0f))
            assertEquals(before,engine.motions.size)
            dispatch(terminal,7 to legend)
            compose.onNodeWithTag("map-dock-toggle").assertIsDisplayed()
        }
        drag()
    }
    @Test fun aSecondFingerBeforeComposeRedrawStillUsesFormerButtonPixelsForTheChart() {
        val p=mapPoint(.5f,.4f)
        for(tag in listOf("zoom-in","map-dock-toggle")) {
            val button=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center
            val start=engine.motions.size
            // Deliberate combined movement crosses each intent threshold. Tiny coupled
            // deviations are tested separately as noise, not required to activate all components.
            val before=button-p;val secondMove=Offset(20f,40f);val angle=-.3
            val after=Offset((1.3*(before.x*kotlin.math.cos(angle)-before.y*kotlin.math.sin(angle))).toFloat(),
                (1.3*(before.x*kotlin.math.sin(angle)+before.y*kotlin.math.cos(angle))).toFloat())
            val firstMove=before+secondMove-after
            compose.runOnIdle {
                dispatchNow(MotionEvent.ACTION_DOWN,7 to p)
                dispatchNow(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),7 to p,31 to button)
                dispatchNow(MotionEvent.ACTION_MOVE,7 to p+firstMove,31 to button+secondMove)
                dispatchNow(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),7 to p+firstMove,31 to button+secondMove)
                dispatchNow(MotionEvent.ACTION_UP,7 to p+firstMove)
            };compose.waitForIdle()
            val motions=engine.motions.drop(start)
            assertTrue(motions.isNotEmpty());assertEquals(0,engine.zooms);assertEquals(0,engine.queries)
            assertEquals(1.3,motions.fold(1.0){z,m->z*m.zoom},.001)
            assertEquals(angle,motions.sumOf{it.rotation},.001)
            assertEquals((firstMove.x+secondMove.x)/2.0,motions.sumOf{it.pan.x},.001)
            assertEquals((firstMove.y+secondMove.y)/2.0,motions.sumOf{it.pan.y},.001)
            compose.onNodeWithText("NO 5").assertDoesNotExist()
            compose.onNodeWithTag("map-dock").assertDoesNotExist()
            compose.onNodeWithTag("map-dock-toggle").assertIsDisplayed()
        }
    }
    @Test fun openDockTemporarilyHidesDuringChartDragAndRestoresItsNavigationState() {
        openDock();val dock=compose.onNodeWithTag("map-dock").fetchSemanticsNode().boundsInRoot
        val p=mapPoint(.5f,.45f)
        dispatch(MotionEvent.ACTION_DOWN,7 to p);compose.onNodeWithTag("map-dock").assertDoesNotExist()
        dispatch(MotionEvent.ACTION_MOVE,7 to dock.center);assertTrue(engine.motions.isNotEmpty())
        dispatch(MotionEvent.ACTION_CANCEL,7 to dock.center)
        assertEquals(dock,compose.onNodeWithTag("map-dock").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("active-nav-CHART",useUnmergedTree=true).assertExists()
        tap("nav-SETTINGS");tap("nav-CHART");drag(mapPoint())
    }
    @Test fun stoppedActivityRestoresButtonsAndDoesNotKeepAnEdgeCandidate() {
        val p=edgePoint();dispatch(MotionEvent.ACTION_DOWN,7 to p)
        compose.onNodeWithTag("zoom-in").assertDoesNotExist()
        compose.runOnIdle { activity.pause().stop() };compose.waitForIdle()
        compose.runOnIdle { activity.start().resume() };compose.waitForIdle()
        compose.onNodeWithTag("zoom-in").assertExists();compose.onNodeWithTag("map-dock").assertDoesNotExist()
        compose.onNodeWithTag("map-dock-toggle").assertIsDisplayed()
        drag();openDock()
    }
    private fun recreateHost() {
        val saved=android.os.Bundle()
        compose.runOnIdle {
            activity.pause().saveInstanceState(saved).stop()
            if(nativeView.renderSurface!=null)nativeView.surfaceTextureListener!!.onSurfaceTextureDestroyed(testTexture)
            activity.destroy()
            activity=Robolectric.buildActivity(No5ImmersiveActivity::class.java)
            activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
            activity.create(saved)
            installContent()
            activity.start().resume().visible()
        }
        compose.waitForIdle();attachNativeSurface()
    }
    @Test fun recreationDuringClosedDockTouchRestoresTheOpenerAndFreshGestureDispatch() {
        val bounds=compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        val p=mapPoint(.5f,.4f)
        dispatch(MotionEvent.ACTION_DOWN,7 to p)
        compose.onNodeWithTag("map-dock-toggle").assertDoesNotExist()
        recreateHost()
        assertFalse(nativeView.isTouchActive)
        compose.onNodeWithTag("map-dock").assertDoesNotExist()
        compose.onNodeWithTag("map-dock-toggle").assertIsDisplayed()
        assertEquals(bounds,compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot)
        tap("map-dock-toggle");tap("dock-close");drag()
    }
    @Test fun recreationDuringOpenDockTouchRestoresTheOpenDockState() {
        openDock();val bounds=compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        val p=mapPoint(.5f,.4f)
        dispatch(MotionEvent.ACTION_DOWN,7 to p)
        compose.onNodeWithTag("map-dock").assertDoesNotExist()
        recreateHost()
        assertFalse(nativeView.isTouchActive)
        compose.onNodeWithTag("map-dock").assertIsDisplayed()
        compose.onNodeWithTag("map-dock-toggle").assertDoesNotExist()
        compose.onNodeWithTag("active-nav-CHART",useUnmergedTree=true).assertExists()
        assertEquals(bounds,compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot)
        tap("dock-close");compose.onNodeWithTag("map-dock-toggle").assertIsDisplayed();drag()
    }
    @Test fun activityDispatchContinuesDragWhileNativeFrameIsPending() {
        engine.deferMotionConfirmation = true
        val p = mapPoint()
        dispatch(MotionEvent.ACTION_DOWN, 7 to p)
        for (step in 1..4) dispatch(MotionEvent.ACTION_MOVE, 7 to p + Offset(30f * step, 0f))
        dispatch(MotionEvent.ACTION_UP, 7 to p + Offset(120f, 0f))
        assertEquals("All accepted samples must survive the pending native frame", 120.0, engine.motions.sumOf { it.pan.x }, .001)
        assertNull("The test must exercise a genuinely pending native frame", engine.status.value.confirmedFrame)
        compose.onNodeWithTag("chart-warning").assertDoesNotExist()
    }
    @Test fun activityDispatchContinuesPinchAndRotationWhileNativeFrameIsPending() {
        engine.deferMotionConfirmation = true
        val p = mapPoint()
        dispatch(MotionEvent.ACTION_DOWN, 7 to p + Offset(-80f, 0f))
        dispatch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 7 to p + Offset(-80f, 0f), 31 to p + Offset(80f, 0f))
        for (step in 1..8) {
            val angle = kotlin.math.PI * step / 16
            val radius = 80.0 + step * 10
            val delta = Offset((radius * kotlin.math.cos(angle)).toFloat(), (radius * kotlin.math.sin(angle)).toFloat())
            dispatch(MotionEvent.ACTION_MOVE, 7 to p - delta, 31 to p + delta)
        }
        dispatch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 7 to p + Offset(0f, -160f), 31 to p + Offset(0f, 160f))
        dispatch(MotionEvent.ACTION_UP, 7 to p + Offset(0f, -160f))
        assertEquals(2.0, engine.motions.fold(1.0) { zoom, motion -> zoom * motion.zoom }, .001)
        assertEquals(kotlin.math.PI / 2, engine.motions.sumOf { it.rotation }, .001)
        assertEquals(0, engine.queries)
    }
    @Test fun activityDispatchTracksRemainingPointerAfterIndexChangeAndRecoversAfterCancel() {
        engine.deferMotionConfirmation = true
        val p = mapPoint()
        dispatch(MotionEvent.ACTION_DOWN, 7 to p + Offset(-80f, 0f))
        dispatch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 7 to p + Offset(-80f, 0f), 31 to p + Offset(80f, 0f))
        dispatch(MotionEvent.ACTION_MOVE, 7 to p + Offset(-120f, 0f), 31 to p + Offset(120f, 0f))
        dispatch(MotionEvent.ACTION_POINTER_UP, 7 to p + Offset(-120f, 0f), 31 to p + Offset(120f, 0f))
        dispatch(MotionEvent.ACTION_MOVE, 31 to p + Offset(160f, 0f))
        dispatch(MotionEvent.ACTION_CANCEL, 31 to p + Offset(160f, 0f))
        assertEquals(1.5, engine.motions.fold(1.0) { z, m -> z * m.zoom }, .001)
        assertEquals(40.0, engine.motions.sumOf { it.pan.x }, .001)
        drag()
        assertEquals(0, engine.queries)
    }
    @Test fun actualPanelDispatchShieldsPanelAndKeepsFreeChartInteractive() {
        engine.deferMotionConfirmation = true
        tap("nav-ROUTES")
        assertTrue("Measured panel bounds must reach the native View before a new chart gesture", nativeView.blockedRegions.any { it.left == 344f && it.height >= 710f })
        val before = engine.motions.size
        val p = mapPoint(.3f, .6f)
        dispatch(MotionEvent.ACTION_DOWN, 7 to p)
        dispatch(MotionEvent.ACTION_MOVE, 7 to p + Offset(60f, 0f))
        dispatch(MotionEvent.ACTION_UP, 7 to p + Offset(60f, 0f))
        assertEquals("Opaque panel must not forward touch to the chart", before, engine.motions.size)
        drag()
        tap("panel-toggle"); drag(); tap("nav-ROUTES"); drag()
    }
    @Test fun destinationsCollapseAndPlatformBackPreserveActivityGestureDispatch() {
        engine.deferMotionConfirmation = true
        Destination.entries.filter { it != Destination.CHART }.forEach { destination ->
            tap("nav-${destination.name}"); tap("panel-toggle"); drag()
            compose.runOnIdle { activity.get().onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
            compose.onNodeWithTag("active-nav-CHART", useUnmergedTree = true).assertExists()
            drag()
        }
        assertEquals(1, engine.attaches); assertEquals(0, engine.detaches)
    }
    @Test fun collapsedPanelHasNoPhantomButtonRegionAndRailReopensWithoutRemounting() {
        engine.deferMotionConfirmation = true
        val renderer = compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        Destination.entries.filter { it != Destination.CHART }.forEach { destination ->
            // Each preceding deferred drag invalidates its frame. Confirm the next
            // fixture frame before checking this genuinely visible scale exclusion.
            compose.runOnIdle { engine.confirm() }
            tap("nav-${destination.name}")
            val panel = compose.onNodeWithTag("screen-panel").fetchSemanticsNode().boundsInRoot
            assertEquals(1312f,panel.width,.5f)
            tap("panel-toggle")
            compose.onNodeWithTag("panel-toggle").assertDoesNotExist()
            compose.onNodeWithTag("screen-panel").assertDoesNotExist()
            val scale = compose.onNodeWithTag("chart-scale-bar").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                .translate(-renderer.left, -renderer.top)
            assertTrue("The former free point is now occupied by the visible scale bar", scale.contains(Offset(70f, 107f)))
            assertTrue("The real scale bar remains protected", nativeView.blockedRegions.contains(scale))
            val freePoint = mapPoint(.04f, .3f)
            val freeLocal = freePoint - renderer.topLeft
            assertFalse("Collapsed panels must not leave a phantom exclusion at a free map point",
                nativeView.blockedRegions.any { it.contains(freeLocal) })
            drag(freePoint)
            tap("nav-${destination.name}")
            compose.onNodeWithTag("screen-panel").assertIsDisplayed()
            assertEquals(renderer, compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot)
        }
        assertEquals(1, engine.attaches); assertEquals(0, engine.detaches)
    }
    @Test fun styleChangeThenCollapsingSettingsRestoresUsableMapControls() {
        tap("nav-SETTINGS")
        compose.onNodeWithTag("chart-style-settings").performScrollTo(); tap("chart-style-settings")
        compose.onNodeWithTag("chart-style-glacier").performClick()
        compose.onNodeWithTag("chart-style-apply").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("chart-style-close").performClick()
        tap("panel-toggle")
        compose.onNodeWithTag("screen-panel").assertDoesNotExist()
        for(tag in listOf("zoom-in","zoom-out","north-up","position-cycle"))
            compose.onNodeWithTag(tag).assertIsDisplayed()
        drag()
        compose.onNodeWithTag("zoom-in").assertIsDisplayed()
        compose.onNodeWithTag("north-up").assertIsDisplayed().performClick()
        assertEquals("Style/panel changes preserve renderer",1,engine.attaches)
    }
    @Test fun styleAndRendererDialogDismissRestoreActualMapTouchDispatch() {
        engine.deferMotionConfirmation = true
        tap("nav-SETTINGS")
        compose.onNodeWithTag("chart-style-settings").performScrollTo(); tap("chart-style-settings")
        compose.onNodeWithText("Schließen").performTouchInput { click(center) }; compose.waitForIdle()
        drag()
        compose.onNodeWithText("Karte / Offline").performTouchInput { click(center) }; compose.waitForIdle()
        compose.onNodeWithTag("renderer-diagnostics").performScrollTo(); tap("renderer-diagnostics")
        compose.onNodeWithText("Schließen").performTouchInput { click(center) }; compose.waitForIdle()
        drag(); tap("nav-CHART"); drag()
        assertEquals(1, engine.attaches)
    }
    @Test @Config(qualifiers = "w1000dp-h464dp-land-xhdpi")
    fun brandReportsMissingHomeWithoutChooserAndPreservesCaptureSurfaceAndGestures() {
        val surface = nativeView.renderSurface
        runBlocking { engine.startRendererCapture() }
        compose.waitForIdle()
        tap("header-brand")
        compose.onNodeWithTag("app-switcher").assertDoesNotExist()
        compose.onNodeWithText("No5 Home ist nicht installiert oder nicht verfügbar.").assertExists()
        assertSame(surface, nativeView.renderSurface)
        assertTrue(engine.status.value.rendererCapture.active)
        assertEquals(1, engine.attaches); assertEquals(0, engine.detaches)
        drag()
    }
    @Test fun brandStartsVerifiedHomeDirectlyAndRetainsTheCurrentPanel() {
        val target = No5AppNavigation.Target.HOME
        shadowOf(activity.get().packageManager).addOrUpdateActivity(ActivityInfo().apply {
            packageName = target.packageName; name = target.activityName; enabled = true; exported = true
            applicationInfo = ApplicationInfo().apply { packageName = target.packageName; enabled = true }
        })
        tap("nav-ROUTES"); tap("header-brand"); compose.waitForIdle()
        val intent = requireNotNull(shadowOf(activity.get()).nextStartedActivity)
        assertEquals(ComponentName("com.no5.home", "com.no5.home.HomeActivity"), intent.component)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals(setOf(Intent.CATEGORY_LAUNCHER), intent.categories)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED, intent.flags)
        assertFalse(intent.getBooleanExtra(No5AppNavigation.NIGHT, true))
        compose.onNodeWithTag("app-switcher").assertDoesNotExist()
        compose.onNodeWithTag("active-nav-ROUTES", useUnmergedTree = true).assertExists()
        assertEquals(1, engine.attaches); assertEquals(0, engine.detaches)
        drag()
    }
    @Test fun realPanelChangeCancelsActiveStreamAndNewDownRecovers() {
        engine.deferMotionConfirmation = true
        val p = mapPoint(.73f)
        dispatch(MotionEvent.ACTION_DOWN, 7 to p)
        dispatch(MotionEvent.ACTION_MOVE, 7 to p + Offset(60f, 0f))
        val before = engine.motions.size
        // Accessibility can cancel camera work and request the dock. The current
        // pointer sequence still hides controls until its terminal UP/CANCEL.
        val menuAction=compose.onNodeWithTag("no5-shell").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].first()
        compose.runOnIdle { assertTrue(menuAction.action()) }
        compose.waitForIdle()
        compose.onNodeWithTag("map-dock").assertDoesNotExist()
        compose.onNodeWithTag("map-dock-toggle").assertDoesNotExist()
        assertTrue(nativeView.isTouchActive)
        dispatch(MotionEvent.ACTION_MOVE, 7 to p + Offset(120f, 0f), requireHandled=false)
        dispatch(MotionEvent.ACTION_UP, 7 to p + Offset(120f, 0f), requireHandled=false)
        assertEquals(before, engine.motions.size)
        compose.onNodeWithTag("nav-SETTINGS").performClick();compose.waitForIdle()
        drag()
    }
    @Test fun surfaceLossStillShowsWarningAndCancelsActiveTouch() {
        engine.deferMotionConfirmation = true
        val p = mapPoint()
        dispatch(MotionEvent.ACTION_DOWN, 7 to p); dispatch(MotionEvent.ACTION_MOVE, 7 to p + Offset(60f, 0f))
        val before = engine.motions.size
        compose.runOnIdle { engine.detachSurface() }; compose.waitForIdle()
        compose.onNodeWithTag("chart-warning").assertExists()
        dispatch(MotionEvent.ACTION_MOVE, 7 to p + Offset(120f, 0f)); dispatch(MotionEvent.ACTION_UP, 7 to p + Offset(120f, 0f))
        assertEquals(before, engine.motions.size)
    }
    @Test fun actualAndroid10SlotAndPersistentSurfaceSurviveAllDestinations() {
        val map = compose.onNodeWithTag("map-area").fetchSemanticsNode().boundsInRoot
        assertEquals(2000f,map.width,.5f);assertEquals(1070f,map.height,.5f)
        compose.onNodeWithTag("footer").assertDoesNotExist()
        assertEquals(1, engine.attaches)
        Destination.entries.filter { it != Destination.CHART }.forEach { destination ->
            tap("nav-${destination.name}")
            compose.onNodeWithTag("panel-toggle").performClick(); tap("nav-${destination.name}")
            compose.waitForIdle()
            assertEquals(1, engine.attaches); assertEquals(0, engine.detaches)
        }
        assertEquals(2000, engine.status.value.confirmedFrame!!.geometry.surfaceWidth)
        assertEquals(1070, engine.status.value.confirmedFrame!!.geometry.surfaceHeight)
    }
    @Test fun nativeGesturesAndZoomUseCheckedFramesWithoutLegacyViewportMath() {
        compose.onNodeWithTag("renderer-slot").performTouchInput { swipe(center, center + Offset(120f, 0f), 300) }
        compose.waitForIdle(); assertTrue(engine.motions.isNotEmpty())
        assertEquals(.15, engine.status.value.viewport.metresToPixels, .00001)
        compose.onNodeWithTag("zoom-in").assertIsEnabled().performClick(); compose.waitForIdle()
        assertEquals(compose.onRoot().printToString(), 1, engine.zooms); assertEquals(0, engine.queries)
    }
    @Test fun settingsUseExistingProfilesAndExposeRendererWithoutSecondSurface() {
        tap("nav-SETTINGS")
        compose.onNodeWithTag("chart-style-settings").performScrollTo().performClick()
        compose.onNodeWithText("Kartenstil", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Schließen").performClick()
        compose.onNodeWithText("Karte / Offline").performClick()
        compose.onNodeWithTag("renderer-diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Renderer · ARM64 · Android 10").assertExists()
        assertEquals("Renderer diagnostics are read only when the dialog opens", 1, engine.diagnosticsReads)
        assertEquals(1, engine.attaches)
    }
    @Test fun unchangedHelmSnapshotDoesNotScheduleAnOverlayRenderButNewProjectionDoes() {
        fun snapshot(revision: Long, latitude: Double) = Snapshot(
            serverId = "lumina", revision = revision, generatedAt = "2026-10-06T00:00:00Z",
            state = HelmProjection(
                Ownship(GeoPoint(42.0, -71.0), 0.2, 0.2, 2.0, 4.0),
                // Exercise live overlay equality independently of the separate
                // ownship freshness/controller contract.
                listOf(com.no5.helm.model.AisTarget(123456789L,"Target",GeoPoint(latitude,-71.0),.3,.3,3.0)),
                emptyList(), emptyList(),
            ),
        )
        val baselineQueued = engine.queuedOverlays
        val baselineConfirmed = engine.confirmedOverlays
        val baselineSubmissions = engine.overlaySubmissions

        compose.runOnIdle { helm.value = HelmState(ConnectionState.LIVE, snapshot(1, 42.0)) }
        compose.waitForIdle()
        assertEquals(baselineQueued + 1, engine.queuedOverlays)
        assertEquals(baselineConfirmed, engine.confirmedOverlays)
        assertEquals(baselineSubmissions + 1, engine.overlaySubmissions)

        compose.runOnIdle { helm.value = HelmState(ConnectionState.LIVE, snapshot(2, 42.0)) }
        compose.waitForIdle()
        assertEquals("Revision-only updates keep the equal overlay key throttled", baselineQueued + 1, engine.queuedOverlays)
        assertEquals("The Compose effect does not resubmit structurally equal overlays", baselineSubmissions + 1, engine.overlaySubmissions)

        compose.runOnIdle { helm.value = HelmState(ConnectionState.LIVE, snapshot(3, 42.1)) }
        compose.waitForIdle()
        assertEquals(baselineQueued + 2, engine.queuedOverlays)
        assertEquals(baselineSubmissions + 2, engine.overlaySubmissions)
        assertEquals("Live deltas must not take the synchronous layer-confirmation path", baselineConfirmed, engine.confirmedOverlays)
    }
    @Test fun livePositionsDoNotCancelPendingLayerConfirmationAndLatestDataResumeAfterAck() {
        fun snapshot(revision: Long) = Snapshot(serverId="lumina", revision=revision,
            generatedAt="2026-10-25T00:00:00Z", state=HelmProjection(
                Ownship(GeoPoint(42.0,-71.0),.2,.2,2.0,4.0),
                listOf(com.no5.helm.model.AisTarget(123456789L,"Target",GeoPoint(42.0+revision/100.0,-71.0),.3,.3,3.0)),
                emptyList(),emptyList()))
        compose.runOnIdle { helm.value=HelmState(ConnectionState.LIVE,snapshot(1)) }
        compose.waitForIdle();tap("nav-LAYERS")
        fun target():View {
            fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
            return all(activity.get().window.decorView).single{it.tag=="no5-row-map-layer-ownship"}
        }
        val before=engine.confirmedOverlays
        val completion=CompletableDeferred<Boolean>()
        compose.runOnIdle { engine.overlayCompletion=completion;target().performClick() }
        compose.waitForIdle();assertEquals(before+1,engine.confirmedOverlays)
        for(revision in 2L..10L){
            compose.runOnIdle { helm.value=HelmState(ConnectionState.LIVE,snapshot(revision)) }
            compose.waitForIdle()
        }
        assertEquals("Live data must not restart a user command",before+1,engine.confirmedOverlays)
        assertEquals("No cancellation while the frame is pending",0,engine.cancelledOverlays)
        compose.runOnIdle { engine.overlayCompletion=null;completion.complete(true) }
        compose.waitForIdle()
        val prefs=activity.get().getSharedPreferences("native-screens-v1",Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("overlay.ownship",true))
        assertEquals("Latest live target resumes after ACK",42.1,engine.latestOverlay.aisObjects.single().position.latitude,.000001)
        compose.runOnIdle { target().performClick() };compose.waitForIdle()
        assertTrue("Opposite request remains usable after ACK",prefs.getBoolean("overlay.ownship",false))
    }
    @Test fun layerVisibilityKeepsSynchronousOverlayConfirmation() {
        val snapshot = Snapshot(
            serverId = "lumina", revision = 1, generatedAt = "2026-10-06T00:00:00Z",
            state = HelmProjection(
                Ownship(GeoPoint(42.0, -71.0), 0.2, 0.2, 2.0, 4.0),
                listOf(com.no5.helm.model.AisTarget(123456789L, "Target", GeoPoint(42.1, -71.1), 0.3, 0.3, 3.0)),
                emptyList(), emptyList(),
            ),
        )
        compose.runOnIdle { helm.value = HelmState(ConnectionState.LIVE, snapshot) }
        compose.waitForIdle()
        val baselineQueued = engine.queuedOverlays
        val baselineConfirmed = engine.confirmedOverlays

        tap("nav-LAYERS")
        compose.onAllNodes(hasText("Ausblenden") and isEnabled()).onFirst().performClick()
        compose.waitForIdle()

        assertEquals(baselineQueued, engine.queuedOverlays)
        assertEquals(baselineConfirmed + 1, engine.confirmedOverlays)
    }
    @Test fun routeDiscardDialogRemainsAvailableAfterCollapsingPanel() {
        tap("nav-ROUTES")
        compose.onNodeWithText("+ Neue Route").performScrollTo().performClick()
        compose.onNodeWithTag("panel-toggle").performClick()
        tap("nav-SETTINGS")
        compose.onNodeWithText("Änderungen speichern?").assertExists()
        compose.onNodeWithText("Weiter bearbeiten").performClick()
        compose.onNodeWithTag("active-nav-ROUTES", useUnmergedTree = true).assertExists()
        tap("nav-SETTINGS")
        compose.onNodeWithText("Verwerfen").performClick()
        compose.onNodeWithTag("active-nav-SETTINGS", useUnmergedTree = true).assertExists()
    }
    @Test fun adapterRejectsStaleProjectionAndZoomBeforeCallingEngine() = runBlocking {
        val adapter = NativeRendererAdapter(engine, engine.status.value.viewport)
        val old = engine.status.value.confirmedFrame!!
        val viewport = ViewportSnapshot(old.revision, old.geometry.surfaceWidth, old.geometry.surfaceHeight, old.cameraToken)
        engine.confirm()
        assertNull(adapter.project(frozenListOf(GeoObject("p", Wgs84(42.0, -71.0))), viewport))
        assertNull(adapter.unproject(ScreenPixel(100f, 100f), viewport))
        var rejected = false
        try { adapter.execute(RendererCommand.Zoom(2.0, ScreenPixel(100f, 100f), viewport)) } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected); assertEquals(0, engine.zooms); assertEquals(0, engine.projections)
    }
    @Test fun stopAndResumeReattachesSameSlotAndKeepsEngine() {
        val old = engine.status.value.confirmedFrame!!
        compose.runOnIdle { activity.pause().stop() }
        assertEquals(1, engine.detaches); assertNull(engine.status.value.confirmedFrame)
        activity.start().resume(); compose.waitForIdle()
        assertEquals(2, engine.attaches)
        assertNotEquals(old, engine.status.value.confirmedFrame)
        compose.onNodeWithTag("renderer-slot").assertExists()
    }
    @Test fun residentReturnFromSettingsReattachesBeforeCameraReadmission() {
        assertResidentReturnReattachesBeforeCameraReadmission(Destination.SETTINGS)
    }
    @Test fun residentReturnFromRoutesReattachesBeforeCameraReadmission() {
        assertResidentReturnReattachesBeforeCameraReadmission(Destination.ROUTES)
    }
    private fun assertResidentReturnReattachesBeforeCameraReadmission(destination: Destination) {
        compose.runOnIdle {
            engine.status.value = engine.status.value.copy(residentCatalogActive = true,
                residentCameraReady = true, residentStartState = ChartResidentStartState.READY)
        }
        compose.waitForIdle();tap("nav-${destination.name}")
        val retainedSurface = nativeView.renderSurface
        val retainedCamera = engine.status.value.viewport
        val previousAttaches = engine.attaches
        compose.runOnIdle {
            activity.pause().stop()
            // Real resident detach revokes camera admission until a Surface has
            // been attached again. The old non-resident fake did not model this.
            engine.status.value = engine.status.value.copy(residentCameraReady = false,
                residentStartState = ChartResidentStartState.WAITING_SURFACE)
        }
        assertFalse(engine.status.value.surfaceAttached)
        assertFalse(engine.status.value.residentCameraReady)
        assertNull(engine.status.value.confirmedFrame)
        activity.start().resume();compose.waitForIdle()
        assertEquals("Reattach must not wait for resident camera admission",previousAttaches+1,engine.attaches)
        assertSame("A retained TextureView does not need a second availability callback",retainedSurface,nativeView.renderSurface)
        assertTrue(engine.status.value.surfaceAttached)
        assertFalse("UI must not invent camera admission",engine.status.value.residentCameraReady)
        assertEquals(retainedCamera,engine.status.value.viewport)
        assertEquals(destination.name,activity.get().getSharedPreferences("native-screens-v1",Context.MODE_PRIVATE).getString("lastDestination",null))
        compose.onNodeWithTag("renderer-slot").assertExists()
        compose.onNodeWithTag("resident-chart-loading").assertExists()
        compose.runOnIdle {
            engine.status.value = engine.status.value.copy(residentCameraReady = true,
                residentStartState = ChartResidentStartState.READY)
            engine.confirm()
        }
        compose.waitForIdle();compose.onNodeWithTag("resident-chart-loading").assertDoesNotExist()
        assertEquals("Camera admission must not remount the renderer",previousAttaches+1,engine.attaches)
    }
    @Test fun liveCaptureClosesDialogAndKeepsChartGestureDispatch() {
        openRenderer()
        compose.onNodeWithTag("renderer-capture-start").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(engine.status.value.rendererCapture.active)
        compose.onNodeWithText("Renderer · ARM64 · Android 10").assertDoesNotExist()
        tap("nav-CHART"); drag()
        assertEquals(1, engine.attaches)
    }
    @Test fun emptyChartOffersImportThroughActualSettingsTabWithoutReplacingSurface() {
        compose.runOnIdle { engine.status.value = engine.status.value.copy(chartLoaded = false, confirmedFrame = null) }
        compose.onNodeWithTag("empty-chart-import").performClick()
        compose.onNodeWithTag("chart-library-empty").assertExists()
        compose.onNodeWithTag("chart-package-import").assertIsEnabled()
        assertEquals(1, engine.attaches)
    }
    @Test fun activeResidentCatalogAttachesSurfaceBeforeFirstCellAndShowsLoadingInsteadOfImport() {
        val pending = RecordingEngine()
        pending.status.value = pending.status.value.copy(chartLoaded = false, loadedChartCells = emptyList(),
            residentCatalogActive = true, surfaceAttached = false, confirmedFrame = null)
        val viewport = pending.status.value.viewport
        compose.runOnIdle {
            activity.get().setContent { NativeHelmScreen(pending, helm, "http://localhost", "NOAA Boston", viewport, libraryActions) {} }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("renderer-slot").assertExists()
        compose.onNodeWithTag("resident-chart-loading").assertIsDisplayed()
        compose.onNodeWithText("Karten werden geladen").assertIsDisplayed()
        compose.onNodeWithTag("empty-chart-import").assertDoesNotExist()
        assertEquals(0, pending.attaches)
        attachNativeSurface()
        assertEquals(1, pending.attaches)
        assertTrue(pending.status.value.surfaceAttached)
        assertFalse(pending.status.value.chartLoaded)
        assertTrue(pending.status.value.loadedChartCells.isEmpty())
        compose.onNodeWithTag("resident-chart-loading").assertIsDisplayed()
        compose.onNodeWithTag("empty-chart-import").assertDoesNotExist()
        tap("nav-SETTINGS")
        compose.onNodeWithText("Karte / Offline").performScrollTo().performClick()
        compose.onNodeWithText("WIRD GELADEN · NOAA Boston · Kartenbestand aktiviert; Zellen werden geladen.").performScrollTo().assertIsDisplayed()
    }
    @Test fun packagePickerDoesNotRequireNoaaTermsBeforeProviderIsKnown() {
        tap("nav-SETTINGS"); compose.onNodeWithText("Karten", substring = false).performClick()
        compose.onNodeWithTag("chart-noaa-terms").assertDoesNotExist()
        compose.onNodeWithTag("chart-package-import").performScrollTo().performClick()
        val started = shadowOf(activity.get()).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.action)
        assertEquals("*/*", started.type)
        assertTrue(started.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.contains("application/zip"))
        assertEquals(1, engine.attaches)
    }
    @Test fun importProgressAndCancelRemainUsableInActualSettingsPanel() {
        libraryActions.state.value = ChartLibraryUiState(busy = true, importProgress = ImportProgress(ImportPhase.INDEXING,
            "Native S57-Geometrie und Updates prüfen; Geometrieindex erstellen", "ENC_ROOT/US5BOSCC/US5BOSCC.000", 30, 79))
        tap("nav-SETTINGS"); compose.onNodeWithText("Karten", substring = false).performClick()
        compose.onNodeWithText("Zelle 31 / 79 wird geprüft und indexiert").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chart-import-cancel").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, libraryActions.cancels)
        compose.onNodeWithTag("chart-package-import").assertIsNotEnabled()
        assertEquals(1, engine.attaches)
        savePanelEvidence("settings-import-indexing-day")
        compose.runOnIdle { libraryActions.state.value = libraryActions.state.value.copy(
            importProgress = libraryActions.state.value.importProgress!!.copy(activity = ImportActivity.CANCELLING)) }
        compose.onNodeWithText("Abbruch angefordert").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chart-import-cancel").assertDoesNotExist()
        assertEquals(1, engine.attaches)
    }
    @Test fun storedPackageSelectionActivatesOnlySelectedCellNames() {
        val stored = ChartPackage("11111111-1111-1111-1111-111111111111", "NOAA example", 1,
            listOf(ChartCell("US5BOSCC", 5, 4, "US5BOSCC.000", "20260821", emptyMap())))
        libraryActions.state.value = ChartLibraryUiState(ChartLibrarySnapshot(listOf(stored)))
        tap("nav-SETTINGS"); compose.onNodeWithText("Karten", substring = false).performClick()
        compose.onNodeWithText("Zellen auswählen").performScrollTo().performClick()
        compose.onNodeWithTag("chart-package-activate").assertIsNotEnabled()
        compose.onNodeWithTag("chart-noaa-terms").performClick()
        compose.onNodeWithTag("chart-region-close").performClick()
        compose.onNodeWithText("Zellen auswählen").performScrollTo().performClick()
        compose.onNodeWithTag("chart-noaa-terms").assertIsOn()
        compose.onNodeWithTag("chart-package-activate").assertIsDisplayed().performClick()
        assertEquals(ChartSelection(stored.id, listOf("US5BOSCC")), libraryActions.selected)
        assertEquals(1, engine.attaches)
    }
    @Test fun nonNoaaPackageActivatesWithoutNoaaConsentEvenWithMisleadingLabel() {
        val stored = ChartPackage("22222222-2222-2222-2222-222222222222", "NOAA in filename only", 1,
            listOf(ChartCell("1W7RHEIN", 1, 0, "1W7RHEIN.000", "20260821", emptyMap(), provider = S57Profiles.WSV)))
        libraryActions.state.value = ChartLibraryUiState(ChartLibrarySnapshot(listOf(stored)))
        tap("nav-SETTINGS"); compose.onNodeWithText("Karten", substring = false).performClick()
        compose.onNodeWithText("Zellen auswählen").performScrollTo().performClick()
        compose.onNodeWithTag("chart-noaa-terms").assertDoesNotExist()
        compose.onNodeWithTag("chart-package-activate").assertIsEnabled().performClick()
        assertEquals(ChartSelection(stored.id, listOf("1W7RHEIN")), libraryActions.selected)
    }
    @Test fun chartLibraryFilterAndActivationPreserveMeasurementGuardWithCompactControls() {
        val stored = ChartPackage("11111111-1111-1111-1111-111111111111", "NOAA Florida · Testbestand", 1,
            listOf(ChartCell("US5MIACC", 5, 0, "US5MIACC.000", "20260821", emptyMap()),
                ChartCell("US5BOSCC", 5, 0, "US5BOSCC.000", "20260821", emptyMap())))
        libraryActions.state.value = ChartLibraryUiState(ChartLibrarySnapshot(listOf(stored)))
        tap("nav-SETTINGS"); compose.onNodeWithText("Karten", substring = false).performClick()
        compose.onNodeWithText("Zellen auswählen").performScrollTo().performClick()
        compose.onNodeWithText("Zellen suchen · Kennung, z. B. BOS").assertIsDisplayed().performTextInput("mia")
        compose.onNodeWithText("Alle abwählen").assertIsDisplayed().performClick()
        compose.onNodeWithText("Gefilterte auswählen").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chart-noaa-terms").performClick()
        compose.onNodeWithText("US5BOSCC", substring = true).assertDoesNotExist()
        compose.runOnIdle { engine.status.value = engine.status.value.copy(rendererCapture = RendererCaptureStatus(active = true)) }
        compose.onNodeWithTag("chart-package-activate").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { engine.status.value = engine.status.value.copy(rendererCapture = RendererCaptureStatus()) }
        compose.onNodeWithTag("chart-package-activate").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(ChartSelection(stored.id, listOf("US5MIACC")), libraryActions.selected)
        assertEquals(1, engine.attaches)
        savePanelEvidence("settings-cards-filter-day")
    }
    @Test fun integratedPanelsProduceNativeDayAndRedNightEvidenceWithoutChangingRendererBounds() {
        val map = compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            helm.value = HelmState(ConnectionState.LIVE, Snapshot(serverId = "ui-fixture", revision = 1,
                generatedAt = "2026-10-05T12:00:00Z", state = HelmProjection(
                    Ownship(GeoPoint(42.36, -71.04), 0.0, 0.0, 0.0, 3.0),
                    listOf(com.no5.helm.model.AisTarget(211123456, "LUMINA · NATIVE HOST TEST", GeoPoint(42.36, -71.04), 0.0, 1.0, 2.0)),
                    emptyList(), emptyList())))
        }
        compose.waitForIdle()
        listOf(false, true).forEach { night ->
            tap(if (night) "palette-toggle" else "palette-day")
            val suffix = if (night) "red-night" else "day"
            tap("nav-AIS")
            // Returning from the previous pass must be explicit while retaining the selected MMSI.
            if (night) tap("ais-list-back")
            compose.onNodeWithTag("ais-target-211123456").assertIsDisplayed()
            savePanelEvidence("ais-list-$suffix")
            tap("ais-target-211123456")
            compose.onNodeWithTag("ais-list-back").assertIsDisplayed()
            compose.onNodeWithText("Risiko unbekannt").assertExists()
            savePanelEvidence("ais-details-$suffix")
            tap("nav-SETTINGS"); compose.onNodeWithText("Darstellung").performScrollTo().assertIsDisplayed().performClick()
            // These choices also have a horizontal scroller. Scroll the containing vertical
            // panel explicitly so larger native dp/sp sizes remain reachable by the user.
            val scroll = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Nord oben")) and
                SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            compose.onNodeWithText("Ausrichtung", substring = true).performScrollTo()
            compose.onNodeWithText("Nord oben", substring = false).assertIsDisplayed()
            // The added touch-mode row increases content height. Exercise an actual vertical
            // scroll to the bottom; nested horizontal choice rows must not own this scroll.
            scroll.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f,10000f) }
            compose.onNodeWithText("30 %").assertIsDisplayed()
            savePanelEvidence("settings-display-$suffix")
            compose.onNodeWithText("Karten", substring = false).performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("chart-package-import").assertExists()
            savePanelEvidence("settings-cards-$suffix")
            assertEquals(map, compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot)
        }
        assertEquals(1, engine.attaches); assertEquals(0, engine.detaches)
    }
    private fun savePanelEvidence(name: String) = compose.runOnIdle {
        val decor = activity.get().window.decorView
        assertEquals(2000, decor.width); assertEquals(1200, decor.height)
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        try {
            decor.draw(Canvas(bitmap))
            val output = File("build/ui-evidence/integrated-$name.png"); output.parentFile!!.mkdirs()
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    @Test fun liveCaptureCanStopAndExposeExportWithoutReplacingSurface() {
        engine.status.value = engine.status.value.copy(rendererCapture = RendererCaptureStatus(active = true, frames = 9))
        openRenderer()
        compose.onNodeWithTag("renderer-capture-stop").performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(engine.status.value.rendererCapture.active)
        compose.onNodeWithTag("renderer-report-export").assertIsDisplayed().assertIsEnabled().assertContentDescriptionEquals("Report exportieren")
        assertEquals(1, engine.attaches)
    }
    @Test fun currentChartReleaseDiagnosticsStartsTheTypedArmAndRevealsTheSameMap() {
        compose.runOnIdle {
            engine.status.value = engine.status.value.copy(residentCatalogActive = true,
                residentCatalogGeneration = "a".repeat(64), chartSourceGeneration = 7, surfaceGeneration = 9,
                rendererBenchmarkDataAvailable = false)
            engine.currentViewSnapshot = org.json.JSONObject().put("schema", "no5-capacity-outer-state/1")
                .put("scope", "CURRENT_ENTRY_CATALOG_ONLY")
                .put("state", org.json.JSONObject().put("bufferTransformMode", "OFF").put("residentSourceGeneration", 7)
                    .put("residentActivation", org.json.JSONObject().put("catalog", org.json.JSONObject().put("generation", "a".repeat(64))
                        .put("budget", org.json.JSONObject().put("cells", 25).put("bytes", 268_435_456L).put("loadingBytes", 134_217_728L)))))
                .put("authority", org.json.JSONObject().put("catalogGeneration", "a".repeat(64)).put("sourceGeneration", 7)
                    .put("surfaceGeneration", 9).put("budget", org.json.JSONObject().put("cells", 25)
                        .put("bytes", 268_435_456L).put("loadingBytes", 134_217_728L))).toString()
        }
        val before = engine.status.value.viewport
        tap("nav-SETTINGS"); compose.onNodeWithText("Karte / Offline").performClick()
        compose.onNodeWithTag("renderer-diagnostics").performScrollTo().performClick()
        focusHostInputWindow(activity.get(), compose)
        compose.onNodeWithTag("renderer-current-view-OFF").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("renderer-current-view-normal").performScrollTo().assertIsSelected()
        dialogPointerTap(compose.onNodeWithTag("renderer-current-view-GPU").performScrollTo())
        dialogPointerTap(compose.onNodeWithTag("renderer-current-view-detail").performScrollTo())
        dialogPointerTap(compose.onNodeWithTag("renderer-current-view-start").performScrollTo().assertIsEnabled())
        assertEquals(RendererViewportReuseVariant.GPU, engine.lastBenchmarkConfig?.viewportReuseCandidate)
        assertTrue(engine.lastBenchmarkConfig!!.viewportReuseDetailed)
        assertEquals("CURRENT_VIEW_REUSE", engine.lastBenchmarkConfig?.capacityRecipeId)
        assertEquals(ResidentCapacityCacheMode.WARM, engine.lastBenchmarkConfig?.capacityArm?.cacheMode)
        assertEquals(before, engine.status.value.viewport)
        compose.onNodeWithTag("renderer-current-view-start").assertDoesNotExist()
        assertEquals(1, engine.attaches)
        compose.runOnIdle { engine.finishBenchmark("completed") }
        focusHostInputWindow(activity.get(), compose)
        compose.onNodeWithTag("renderer-current-view-start").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("renderer-report-export").assertIsEnabled()
        assertEquals(before, engine.status.value.viewport)
    }
    @Test @Config(qualifiers = "w1000dp-h464dp-land-xhdpi")
    fun completedCapturePointerTapShowsResultAndKeepsExportVisibleInOemDialog() {
        engine.status.value = engine.status.value.copy(rendererCapture = RendererCaptureStatus(frames = 9))
        tap("nav-SETTINGS")
        compose.onNodeWithText("Karte / Offline").performClick()
        compose.onNodeWithTag("renderer-diagnostics").performScrollTo().performClick()
        val evaluate = compose.onNodeWithText("Letzte Aufnahme auswerten").performScrollTo()
        val p = evaluate.fetchSemanticsNode().boundsInRoot.center
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        val now = android.os.SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 30 else 0, action, p.x, p.y, 0)
            try { compose.runOnIdle { assertTrue(dialog.window!!.decorView.dispatchTouchEvent(event)) } }
            finally { event.recycle() }
        }
        compose.waitForIdle()
        assertEquals(1, engine.reportStops)
        compose.onNodeWithText("Report exportieren").assertIsDisplayed()
        compose.onNodeWithTag("renderer-report-result").assertIsDisplayed()
        compose.onNodeWithTag("renderer-report-export").assertIsEnabled()
        assertEquals(1, engine.attaches)
    }
    private fun dialogPointerTap(node: SemanticsNodeInteraction) {
        focusHostInputWindow(activity.get(), compose)
        val p = node.fetchSemanticsNode().boundsInRoot.center
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        val now = android.os.SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 30 else 0, action, p.x, p.y, 0)
            try { compose.runOnIdle { assertTrue(dialog.window!!.decorView.dispatchTouchEvent(event)) } }
            finally { event.recycle() }
        }
        compose.waitForIdle()
    }
    @Test @Config(qualifiers = "w1000dp-h464dp-land-xhdpi")
    fun evaluationFailureIsVisibleInDialogAfterActualPointerTap() {
        engine.stopFailure = "Auswertung Testfehler"
        engine.status.value = engine.status.value.copy(rendererCapture = RendererCaptureStatus(frames = 9))
        tap("nav-SETTINGS"); compose.onNodeWithText("Karte / Offline").performClick()
        compose.onNodeWithTag("renderer-diagnostics").performScrollTo().performClick()
        dialogPointerTap(compose.onNodeWithText("Letzte Aufnahme auswerten").performScrollTo())
        compose.onNodeWithText("Auswertung Testfehler").assertIsDisplayed()
        compose.onNodeWithTag("renderer-report-export").assertIsNotEnabled()
        assertEquals(1, engine.attaches)
    }
    @Test @Config(qualifiers = "w1000dp-h464dp-land-xhdpi")
    fun savedReportCanExportByPointerWithoutStartingAnotherMeasurement() {
        engine.savedReport = "{\"saved\":true}"
        tap("nav-SETTINGS"); compose.onNodeWithText("Karte / Offline").performClick()
        compose.onNodeWithTag("renderer-diagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("renderer-report-export").assertIsDisplayed().assertIsEnabled()
        dialogPointerTap(compose.onNodeWithTag("renderer-report-export"))
        val intent = shadowOf(activity.get()).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("application/json", intent.type)
        assertEquals(0, engine.captureStarts); assertEquals(0, engine.reportStops)
        assertEquals(1, engine.attaches)
    }
    @Test fun textureIsReleasedOnlyAfterNativeDetachCompletes() {
        engine.deferRelease = true
        compose.runOnIdle {
            assertFalse(nativeView.surfaceTextureListener!!.onSurfaceTextureDestroyed(testTexture))
            assertNull(nativeView.renderSurface)
            assertEquals(0, testTexture.releases)
            assertEquals(1, engine.pendingReleases.size)
            engine.pendingReleases.removeAt(0).invoke()
            assertEquals(1, testTexture.releases)
        }
    }
    private fun nativeWorkspaceClick(key:String){
        fun nodes(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{nodes(v.getChildAt(it))}else emptyList()
        compose.runOnIdle{val target=nodes(activity.get().window.decorView).single{it.tag=="no5-row-$key"};assertTrue(target.isEnabled);target.performClick()};compose.waitForIdle()
    }
    private fun openRenderer(){
        tap("nav-SETTINGS");nativeWorkspaceClick("map-settings-diagnostics");nativeWorkspaceClick("map-diagnostics-renderer");nativeWorkspaceClick("renderer-measurements")
        focusHostInputWindow(activity.get(),compose)
    }
    @Test fun benchmarkWithoutUserMapsExplainsEmptySelectionAndCanStart() {
        compose.runOnIdle { engine.status.value = engine.status.value.copy(chartLoaded = false, loadedChartCells = emptyList()) }
        openRenderer()
        compose.onNodeWithTag("renderer-benchmark-start").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("renderer-benchmark-prerequisites").assertTextContains("Keine Nutzerkarte aktiv", substring = true)
        dialogPointerTap(compose.onNodeWithTag("renderer-benchmark-start"))
        assertEquals(1, engine.benchmarkStarts)
        dialogPointerTap(compose.onNodeWithTag("renderer-benchmark-cancel").performScrollTo())
        assertFalse(engine.status.value.chartLoaded); assertEquals(1, engine.attaches)
    }
    @Test fun connecticutSelectionCanStartBundledBostonComparisonAndKeepsUserSelection() {
        val cells = List(75) { "US5CT$it" }
        compose.runOnIdle { engine.status.value = engine.status.value.copy(loadedChartCells = cells) }
        openRenderer()
        compose.onNodeWithTag("renderer-benchmark-start").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("renderer-benchmark-prerequisites").assertTextContains("75 Nutzerzellen", substring = true)
        dialogPointerTap(compose.onNodeWithTag("renderer-benchmark-start"))
        dialogPointerTap(compose.onNodeWithTag("renderer-benchmark-cancel").performScrollTo())
        assertEquals(cells, engine.status.value.loadedChartCells)
    }
    @Test fun missingTestBundleShowsActionableErrorAndRecoveryFailureDoesNotClaimRestored() {
        compose.runOnIdle { engine.status.value = engine.status.value.copy(rendererBenchmarkDataAvailable = false) }
        openRenderer()
        compose.onNodeWithTag("renderer-benchmark-start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("renderer-benchmark-prerequisites").assertTextContains("Boston-Testkarten fehlen", substring = true)
        compose.runOnIdle { engine.status.value = engine.status.value.copy(rendererBenchmarkDataAvailable = true,
            rendererBenchmarkRecoveryPending = true, rendererBenchmark = RendererBenchmarkStatus(phase = "failed", runId = "recovery", restored = false, message = "Rückkehr fehlgeschlagen")) }
        compose.onNodeWithTag("renderer-benchmark-start").assertIsNotEnabled()
        compose.onNodeWithTag("renderer-benchmark-recover").performScrollTo().performClick()
        assertFalse(engine.status.value.rendererBenchmark.restored)
        compose.onNodeWithTag("renderer-report-result").assertTextContains("Keine unterbrochene", substring = true)
    }
    @Test fun benchmarkStartCancelEndAndRepeatKeepSurfaceAndExposeReport() {
        openRenderer()
        dialogPointerTap(compose.onNodeWithTag("renderer-benchmark-start").performScrollTo().assertIsEnabled())
        compose.onNodeWithTag("renderer-benchmark-progress").assertExists()
        compose.onNodeWithTag("renderer-report-export").assertIsNotEnabled()
        dialogPointerTap(compose.onNodeWithTag("renderer-benchmark-cancel").performScrollTo())
        assertEquals(1, engine.benchmarkStarts); assertEquals(1, engine.benchmarkCancels)
        compose.onNodeWithTag("renderer-benchmark-result").assertTextContains("cancelled", substring = true)
        compose.onNodeWithTag("renderer-report-export").assertIsEnabled()
        val first = engine.savedReport
        dialogPointerTap(compose.onNodeWithTag("renderer-benchmark-start").performScrollTo().assertIsEnabled())
        compose.runOnIdle { engine.finishBenchmark("complete") }
        compose.onNodeWithTag("renderer-benchmark-result").assertTextContains("complete", substring = true)
        compose.onNodeWithTag("renderer-report-export").assertIsEnabled()
        assertNotEquals(first, engine.savedReport); assertEquals(2, engine.benchmarkStarts)
        assertEquals(1, engine.attaches); assertEquals(0, engine.detaches)
    }
    @Test fun benchmarkStartFailureIsVisibleAndRetryRemainsAvailable() {
        engine.benchmarkFailure = "Boston-Kartendatei fehlt. Paket erneut importieren."
        openRenderer()
        dialogPointerTap(compose.onNodeWithTag("renderer-benchmark-start").performScrollTo())
        compose.onNodeWithTag("renderer-report-result").assertTextContains(engine.benchmarkFailure!!)
        compose.onNodeWithTag("renderer-benchmark-start").assertIsEnabled()
        engine.benchmarkFailure = null; engine.benchmarkThrows = true
        dialogPointerTap(compose.onNodeWithTag("renderer-benchmark-start").performScrollTo())
        compose.onNodeWithTag("renderer-report-result").assertTextContains("Renderer Testfehler", substring = true)
        compose.onNodeWithTag("renderer-benchmark-start").assertIsEnabled()
    }
    @Test fun benchmarkExportKeepsSelectedRunEvenWhenAnotherReportCompletesInPicker() {
        openRenderer()
        compose.onNodeWithTag("renderer-benchmark-start").performScrollTo().assertTextContains("Boston · Glacier / S01")
        dialogPointerTap(compose.onNodeWithTag("renderer-benchmark-start"))
        compose.runOnIdle { engine.finishBenchmark("complete") }
        val first = engine.savedReport!!
        val firstId = engine.status.value.rendererBenchmark.runId
        dialogPointerTap(compose.onNodeWithTag("renderer-report-export"))
        val picker = shadowOf(activity.get()).nextStartedActivityForResult
        assertEquals("boston-s01-abba-$firstId.json", picker.intent.getStringExtra(Intent.EXTRA_TITLE))
        val target = File.createTempFile("export-benchmark", ".json")
        try {
            compose.runOnIdle { runBlocking { engine.startRendererBenchmark() }; engine.finishBenchmark("complete") }
            compose.runOnIdle { activity.get().activityResultRegistry.dispatchResult(picker.requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(target))) }
            waitForRendererExportCompletion(target, first)
            assertNotEquals(first, engine.savedReport)
        } finally { target.delete() }
    }
    @Test fun standardStartsWithoutUserMapsAndWithFloridaThenCancelsAndRepeats() {
        compose.runOnIdle { engine.status.value = engine.status.value.copy(chartLoaded = false, loadedChartCells = emptyList()) }
        openRenderer()
        val florida = listOf("US5FL111", "US5FL112")
        for (cells in listOf(emptyList(), florida)) {
            compose.runOnIdle { engine.status.value = engine.status.value.copy(chartLoaded = cells.isNotEmpty(), loadedChartCells = cells) }
            dialogPointerTap(compose.onNodeWithTag("renderer-boston-profile").performScrollTo().assertIsEnabled())
            assertEquals(2, engine.status.value.rendererBenchmark.totalGroups)
            compose.onNodeWithTag("renderer-report-export").assertIsNotEnabled()
            dialogPointerTap(compose.onNodeWithTag("renderer-benchmark-cancel").performScrollTo())
            compose.onNodeWithTag("renderer-benchmark-result").assertTextContains("cancelled", substring = true)
            compose.onNodeWithTag("renderer-boston-profile").assertIsEnabled()
            compose.onNodeWithTag("renderer-report-export").assertIsEnabled()
            assertEquals(cells, engine.status.value.loadedChartCells)
            assertEquals(cells.isNotEmpty(), engine.status.value.chartLoaded)
        }
        assertEquals(2, engine.standardStarts); assertEquals(2, engine.benchmarkCancels)
        assertEquals(1, engine.attaches); assertEquals(0, engine.detaches)
    }
    @Test fun standardMissingBundleRecoveryAndStartErrorsAreVisible() {
        compose.runOnIdle { engine.status.value = engine.status.value.copy(rendererBenchmarkDataAvailable = false) }
        openRenderer()
        compose.onNodeWithTag("renderer-boston-profile").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("renderer-benchmark-prerequisites").assertTextContains("Boston-Testkarten fehlen", substring = true)
        compose.runOnIdle { engine.status.value = engine.status.value.copy(rendererBenchmarkDataAvailable = true, rendererBenchmarkRecoveryPending = true) }
        compose.onNodeWithTag("renderer-boston-profile").assertIsNotEnabled()
        compose.onNodeWithTag("renderer-benchmark-prerequisites").assertTextContains("wiederhergestellt", substring = true)
        compose.runOnIdle { engine.status.value = engine.status.value.copy(rendererBenchmarkRecoveryPending = false) }
        engine.benchmarkFailure = "Boston-Testkarten konnten nicht geladen werden"
        dialogPointerTap(compose.onNodeWithTag("renderer-boston-profile").performScrollTo())
        compose.onNodeWithTag("renderer-report-result").assertTextContains(engine.benchmarkFailure!!)
        compose.onNodeWithTag("renderer-boston-profile").assertIsEnabled()
        engine.benchmarkFailure = null; engine.benchmarkThrows = true
        dialogPointerTap(compose.onNodeWithTag("renderer-boston-profile"))
        compose.onNodeWithTag("renderer-report-result").assertTextContains("Renderer Testfehler", substring = true)
        compose.onNodeWithTag("renderer-boston-profile").assertIsEnabled()
    }
    @Test fun standardCompletionAndRepeatKeepRunSpecificExportFrozenInPicker() {
        openRenderer()
        compose.onNodeWithTag("renderer-boston-profile").performScrollTo().assertTextContains("Boston · aktueller Stil")
        dialogPointerTap(compose.onNodeWithTag("renderer-boston-profile"))
        compose.runOnIdle { engine.finishBenchmark("complete") }
        compose.onNodeWithTag("renderer-benchmark-result").assertTextContains("complete", substring = true)
        val first = engine.savedReport!!
        val firstId = engine.status.value.rendererBenchmark.runId
        assertTrue(first.contains("no5-renderer-ab/1"))
        dialogPointerTap(compose.onNodeWithTag("renderer-report-export"))
        val picker = shadowOf(activity.get()).nextStartedActivityForResult
        assertEquals("boston-standard-$firstId.json", picker.intent.getStringExtra(Intent.EXTRA_TITLE))
        compose.onNodeWithTag("renderer-boston-profile").assertIsNotEnabled()
        val target = File.createTempFile("export-standard", ".json")
        try {
            compose.runOnIdle { runBlocking { engine.startRendererBenchmark() }; engine.finishBenchmark("complete") }
            compose.runOnIdle { activity.get().activityResultRegistry.dispatchResult(picker.requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(target))) }
            waitForRendererExportCompletion(target, first)
            dialogPointerTap(compose.onNodeWithTag("renderer-boston-profile").performScrollTo().assertIsEnabled())
            compose.runOnIdle { engine.finishBenchmark("complete") }
            assertNotEquals(first, engine.savedReport); assertEquals(2, engine.standardStarts)
        } finally { target.delete() }
    }
    @Test fun completeExportBytesDoNotReleaseAdmissionBeforeOutputStreamClose() {
        openRenderer()
        dialogPointerTap(compose.onNodeWithTag("renderer-boston-profile").performScrollTo().assertIsEnabled())
        compose.runOnIdle { engine.finishBenchmark("complete") }
        val frozen = engine.savedReport!!
        val frozenId = engine.status.value.rendererBenchmark.runId
        dialogPointerTap(compose.onNodeWithTag("renderer-report-export"))
        val picker = shadowOf(activity.get()).nextStartedActivityForResult
        assertEquals("boston-standard-$frozenId.json", picker.intent.getStringExtra(Intent.EXTRA_TITLE))
        compose.onNodeWithTag("renderer-boston-profile").assertIsNotEnabled()
        val target = File.createTempFile("export-close-barrier", ".json")
        val writer = ExportCloseBarrier(FileOutputStream(target))
        val uri = Uri.parse("content://no5.test/renderer-export/${target.name}")
        shadowOf(activity.get().contentResolver).registerOutputStream(uri, writer)
        var exportDispatched = false
        try {
            compose.runOnIdle { runBlocking { engine.startRendererBenchmark() }; engine.finishBenchmark("complete") }
            assertNotEquals(frozen, engine.savedReport)
            compose.runOnIdle {
                exportDispatched = true
                activity.get().activityResultRegistry.dispatchResult(picker.requestCode, Activity.RESULT_OK, Intent().setData(uri))
            }
            compose.waitUntil(10_000) { writer.closeEntered.count == 0L }
            // All exact frozen bytes are already visible, while the IO transaction is held.
            assertEquals(frozen, target.readText())
            compose.onNodeWithTag("renderer-boston-profile").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("renderer-report-export").assertIsNotEnabled()
            writer.releaseClose.countDown()
            waitForRendererExportCompletion(target, frozen)
            dialogPointerTap(compose.onNodeWithTag("renderer-boston-profile").performScrollTo().assertIsEnabled())
            compose.runOnIdle { engine.finishBenchmark("complete") }
            assertEquals(2, engine.standardStarts)
            assertNotEquals(frozen, engine.savedReport)
            assertEquals(frozen, target.readText())
        } finally {
            writer.releaseClose.countDown()
            // Release even after an assertion failure and await the actual dispatched IO close.
            if (exportDispatched) assertTrue(writer.closed.await(10, TimeUnit.SECONDS))
            else writer.disposeUnopened()
            target.delete()
        }
    }
    private class ExportCloseBarrier(private val output: OutputStream) : OutputStream() {
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun write(value: Int) = output.write(value)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)
        override fun flush() = output.flush()
        override fun close() {
            try {
                output.flush()
                closeEntered.countDown()
                check(releaseClose.await(10, TimeUnit.SECONDS)) { "Test export close barrier was not released" }
            } finally { output.close(); closed.countDown() }
        }
        fun disposeUnopened() { output.close(); closed.countDown() }
    }
    private fun waitForRendererExportCompletion(target: File, frozenJson: String) {
        // IO can expose all bytes before its coroutine resumes and clears reportBusy.
        // Repeat/export admission is the UI completion boundary, not the file write.
        compose.waitUntil(10_000) {
            target.readText() == frozenJson && compose.onAllNodes(
                hasTestTag("renderer-boston-profile") and isEnabled(),
            ).fetchSemanticsNodes().size == 1
        }
        assertEquals(frozenJson, target.readText())
    }
    @Test fun textureDestructionAfterStopStillWaitsForNativeWorkerBarrier() {
        engine.deferRelease = true
        compose.runOnIdle {
            activity.pause().stop()
            assertEquals(1, engine.detaches)
            assertEquals(1, engine.pendingReleases.size) // earlier detach completion
            assertFalse(nativeView.surfaceTextureListener!!.onSurfaceTextureDestroyed(testTexture))
            assertEquals(0, testTexture.releases)
            assertEquals(2, engine.pendingReleases.size)
            engine.pendingReleases.removeAt(0).invoke()
            assertEquals(0, testTexture.releases)
            engine.pendingReleases.removeAt(0).invoke()
            assertEquals(1, testTexture.releases)
        }
    }
    @Test fun oldTextureDestructionAfterReplacementKeepsTheNewSurfaceAndItsReleaseBarrier() {
        val oldTexture = testTexture
        val oldSurface = nativeView.renderSurface
        val replacement = RecordingSurfaceTexture()
        compose.runOnIdle {
            nativeView.surfaceTextureListener!!.onSurfaceTextureAvailable(replacement, nativeView.width, nativeView.height)
            val currentSurface = nativeView.renderSurface
            assertNotSame(oldSurface,currentSurface)
            val previousDetaches = engine.detaches
            engine.deferRelease = true
            assertFalse(nativeView.surfaceTextureListener!!.onSurfaceTextureDestroyed(oldTexture))
            assertSame(currentSurface,nativeView.renderSurface)
            assertSame("Only the old producer may be released",oldSurface,engine.scopedDetaches.last())
            assertEquals("A stale producer must not detach its replacement",previousDetaches,engine.detaches)
            assertTrue(engine.status.value.surfaceAttached)
            assertEquals(0,oldTexture.releases);assertEquals(0,replacement.releases)
            engine.pendingReleases.removeAt(0).invoke()
            assertEquals(1,oldTexture.releases);assertEquals(0,replacement.releases)
            assertSame(currentSurface,nativeView.renderSurface)
            // Repeated callbacks for the retired Texture go back to framework
            // ownership, without touching the current producer or detaching it.
            assertTrue(nativeView.surfaceTextureListener!!.onSurfaceTextureDestroyed(oldTexture))
            assertEquals(previousDetaches,engine.detaches)
            testTexture = replacement;engine.deferRelease = false
        }
    }
    @Test fun stopReturnReusesProducerWithANewAttachmentTokenWithoutReleasingItsTexture() {
        val surface = nativeView.renderSurface
        val firstOwner = engine.attachedOwner
        engine.deferRelease = true
        compose.runOnIdle { activity.pause().stop() }
        activity.start().resume();compose.waitForIdle()
        val secondOwner = engine.attachedOwner
        assertNotNull(firstOwner);assertNotNull(secondOwner);assertNotSame(firstOwner,secondOwner)
        assertSame(surface,nativeView.renderSurface)
        compose.runOnIdle { engine.pendingReleases.removeAt(0).invoke() }
        assertEquals("A STOP fence never releases a retained producer",0,testTexture.releases)
        assertSame(secondOwner,engine.attachedOwner)
        assertTrue(engine.status.value.surfaceAttached)
        compose.runOnIdle {
            val presenter=requireNotNull(nativeView.presentation)
            presenter.viewTreeObserver.dispatchOnPreDraw();presenter.viewTreeObserver.dispatchOnDraw()
        }
        compose.waitForIdle()
        assertTrue("The retained producer is refreshed for its current binding",engine.surfaceRefreshOwners.any { it===secondOwner })
        engine.deferRelease = false
    }
    @Test fun windowReturnWithoutStopRefreshesTheCurrentCameraWithoutReattachment() {
        val attaches=engine.attaches;val detaches=engine.detaches
        val owner=engine.attachedOwner;val frame=engine.status.value.confirmedFrame
        val before=engine.surfaceRefreshOwners.size
        compose.runOnIdle {
            val presenter=requireNotNull(nativeView.presentation)
            presenter.dispatchWindowVisibilityChanged(View.INVISIBLE)
            presenter.dispatchWindowVisibilityChanged(View.VISIBLE)
            presenter.dispatchWindowVisibilityChanged(View.VISIBLE)
            assertEquals(before,engine.surfaceRefreshOwners.size)
            presenter.viewTreeObserver.dispatchOnPreDraw();presenter.viewTreeObserver.dispatchOnDraw()
        }
        compose.waitForIdle()
        assertEquals(before+1,engine.surfaceRefreshOwners.size)
        assertSame(owner,engine.surfaceRefreshOwners.last())
        assertEquals(attaches,engine.attaches);assertEquals(detaches,engine.detaches)
        assertEquals("The refresh is no synthetic camera adjustment",frame,engine.status.value.confirmedFrame)
    }
    @Test fun pendingOldReleaseCannotAbandonAReusedTextureOrReadItsNewOwner() {
        val texture = testTexture
        val firstOwner = engine.attachedOwner
        val firstSurface = nativeView.renderSurface
        compose.runOnIdle {
            engine.deferRelease = true
            assertFalse(nativeView.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture))
            assertSame(firstOwner,engine.scopedOwners.last())
            nativeView.surfaceTextureListener!!.onSurfaceTextureAvailable(texture,nativeView.width,nativeView.height)
            val secondOwner = engine.attachedOwner
            val secondSurface = nativeView.renderSurface
            assertNotSame(firstOwner,secondOwner);assertNotSame(firstSurface,secondSurface)
            engine.pendingReleases.removeAt(0).invoke()
            assertEquals("The old barrier must leave a reused Texture alive",0,texture.releases)
            assertSame(secondOwner,engine.attachedOwner)
            assertSame(secondSurface,nativeView.renderSurface)
            assertTrue(engine.status.value.surfaceAttached)
            assertFalse(nativeView.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture))
            assertSame(secondOwner,engine.scopedOwners.last())
            engine.pendingReleases.removeAt(0).invoke()
            assertEquals(1,texture.releases)
            engine.deferRelease = false
        }
    }
    @Test fun textureResizeUsesActualNewBufferDimensionsAndConfirmsNewFrame() {
        val previous = engine.status.value.confirmedFrame!!
        compose.runOnIdle { nativeView.surfaceTextureListener!!.onSurfaceTextureSizeChanged(testTexture, 1872, 816) }
        assertEquals(2, engine.attaches)
        assertEquals(1872, engine.geometry.surfaceWidth); assertEquals(816, engine.geometry.surfaceHeight)
        assertNotEquals(previous, engine.status.value.confirmedFrame)
    }
    private class RecordingEngine : ChartEngine {
        var objectResults = emptyList<ChartObject>()
        var objectQueries = 0
        override suspend fun queryObjects(request: ChartObjectQuery): ChartObjectQueryResult {
            objectQueries++
            return ChartObjectQueryResult(ChartObjectQueryState.READY, objectResults, objectResults.size)
        }
        override suspend fun selectObject(reference: ChartObjectReference?, radiusSurfacePixels: Double): Boolean =
            reference == null || objectResults.any { it.reference == reference }
        private val uiSink = object : RendererUiSink {
            override fun record(phase: RendererUiPhase, wallNanos: Long, cpuNanos: Long) = Unit
            override fun windowFrame(firstDraw: Boolean, droppedReports: Int) = Unit
            override fun windowState(state: RendererWindowState) = Unit
            override fun textureUpdated() = Unit
        }
        override fun rendererUiSink(): RendererUiSink? = if (status.value.rendererCapture.active) uiSink else null
        override suspend fun startRendererCapture(config: RendererCaptureConfig): EngineResult {
            captureStarts++
            status.value = status.value.copy(rendererCapture = RendererCaptureStatus(active = true))
            return EngineResult(true)
        }
        override suspend fun stopRendererCapture(): EngineResult {
            reportStops++
            stopFailure?.let { return EngineResult(false, it) }
            status.value = status.value.copy(rendererCapture = status.value.rendererCapture.copy(active = false, stopReason = "user stop"))
            return EngineResult(true, "{\"schema\":\"no5-renderer-capture/1\"}")
        }
        override suspend fun readRendererReport(): EngineResult = savedReport?.let { EngineResult(true, it) } ?: EngineResult(false, "Keine abgeschlossene Aufnahme vorhanden")
        override suspend fun readRendererDiagnostics(): EngineResult { diagnosticsReads++; return EngineResult(true, status.value.rendererDiagnostics) }
        override val status = MutableStateFlow(ChartEngineStatus(initialized = true, chartLoaded = true, viewport = ChartViewport(42.36, -71.04, .15), loadedChartCells = RENDERER_BENCHMARK_CELLS.toList(), rendererBenchmarkDataAvailable = true))
        var benchmarkStarts = 0
        var standardStarts = 0
        private var standardCompletion: CompletableDeferred<EngineResult>? = null
        var benchmarkCancels = 0
        var benchmarkFailure: String? = null
        var benchmarkThrows = false
        var currentViewSnapshot: String? = null
        var lastBenchmarkConfig: RendererBenchmarkConfig? = null
        override suspend fun snapshotRendererOuterState(snapshotId: String): String? = currentViewSnapshot
        override suspend fun startRendererBenchmark(config: RendererBenchmarkConfig): EngineResult {
            benchmarkStarts++
            lastBenchmarkConfig = config
            val result = startBenchmark(if (config.runKind == RendererRunKind.RESIDENT_CAPACITY) 1 else 32)
            if (result.success) status.value = status.value.copy(rendererBenchmark = status.value.rendererBenchmark.copy(runKind = config.runKind))
            return result
        }
        override suspend fun profileRenderer(): EngineResult {
            standardStarts++
            val completion = CompletableDeferred<EngineResult>()
            val start = startBenchmark(2)
            if (!start.success) return start
            standardCompletion = completion
            try { return completion.await() }
            catch (cancel: CancellationException) { finishBenchmark("cancelled"); throw cancel }
        }
        private fun startBenchmark(groups: Int): EngineResult {
            benchmarkFailure?.let { return EngineResult(false, it) }
            check(!benchmarkThrows) { "Renderer Testfehler" }
            val id = java.util.UUID.randomUUID().toString()
            status.value = status.value.copy(rendererBenchmark = RendererBenchmarkStatus(active = true, runId = id, phase = "preparing", totalGroups = groups))
            return EngineResult(true, id)
        }
        override fun cancelRendererBenchmark() { benchmarkCancels++; finishBenchmark("cancelled") }
        fun finishBenchmark(outcome: String) {
            val id = status.value.rendererBenchmark.runId
            val schema = if (status.value.rendererBenchmark.totalGroups == 2) "no5-renderer-ab/1" else "no5-renderer-style-benchmark/1"
            savedReport = "{\"schema\":\"$schema\",\"runId\":\"$id\",\"outcome\":\"$outcome\",\"restored\":true}"
            status.value = status.value.copy(rendererBenchmark = status.value.rendererBenchmark.copy(active = false, phase = outcome,
                restored = true, reportFile = "benchmark-$id.json", message = "Ursprüngliche Ansicht wiederhergestellt"))
            standardCompletion?.complete(EngineResult(true, savedReport!!)); standardCompletion = null
        }
        var attaches = 0; var detaches = 0; var zooms = 0; var queries = 0; var projections = 0
        private var attachedSurface: Surface? = null
        var attachedOwner: Any? = null
            private set
        val surfaceRefreshOwners=mutableListOf<Any>()
        val scopedDetaches = mutableListOf<Surface>()
        val scopedOwners = mutableListOf<Any>()
        var reportStops = 0
        var captureStarts = 0
        var diagnosticsReads = 0
        var overlaySubmissions = 0
        var queuedOverlays = 0
        var confirmedOverlays = 0
        var cancelledOverlays = 0
        var overlayCompletion: CompletableDeferred<Boolean>? = null
        var latestOverlay = ChartOverlays()
        var stopFailure: String? = null
        var savedReport: String? = null
        var geometry = ChartSurfaceGeometry.fit(1872, 1088)
        private var revision = 0L
        val motions = mutableListOf<ChartMotion>()
        var deferMotionConfirmation = false
        var deferRelease = false
        val pendingReleases = mutableListOf<() -> Unit>()
        fun confirm() { status.value = status.value.copy(confirmedFrame = ChartFrame(++revision, "recording:$revision", geometry, status.value.viewport, ChartScale(120.0, 500.0))) }
        override fun attachSurface(surface: Surface, width: Int, height: Int) { attachedSurface = surface;attachedOwner = null;attaches++; geometry = ChartSurfaceGeometry.fit(width, height); status.value = status.value.copy(surfaceAttached = true); confirm() }
        override fun attachSurface(surface: Surface, width: Int, height: Int, attachmentOwner: Any) {
            attachSurface(surface,width,height);attachedOwner = attachmentOwner
        }
        override fun refreshSurface(expectedSurface: Surface, attachmentOwner: Any): Boolean {
            if(expectedSurface!==attachedSurface || attachmentOwner!==attachedOwner) return false
            surfaceRefreshOwners+=attachmentOwner
            return true
        }
        override fun detachSurface() { attachedSurface = null;attachedOwner = null;detaches++; status.value = status.value.copy(surfaceAttached = false, confirmedFrame = null) }
        override fun detachSurface(onDetached: () -> Unit) {
            detachSurface()
            if (deferRelease) pendingReleases += onDetached else onDetached()
        }
        override fun detachSurface(expectedSurface: Surface, onDetached: () -> Unit) {
            scopedDetaches += expectedSurface
            if (expectedSurface === attachedSurface) detachSurface(onDetached)
            else if (deferRelease) pendingReleases += onDetached else onDetached()
        }
        override fun detachSurface(expectedSurface: Surface, attachmentOwner: Any, onDetached: () -> Unit) {
            scopedDetaches += expectedSurface;scopedOwners += attachmentOwner
            if (expectedSurface === attachedSurface && attachedOwner === attachmentOwner) detachSurface(onDetached)
            else if (deferRelease) pendingReleases += onDetached else onDetached()
        }
        override fun setViewport(viewport: ChartViewport) { status.value = status.value.copy(viewport = viewport) }
        override fun setBufferTransformMode(mode: ChartBufferTransformMode): EngineResult {
            if (status.value.rendererCapture.active || status.value.rendererBenchmark.active) return EngineResult(false, "measurement active")
            status.value = status.value.copy(bufferTransformMode = mode); return EngineResult(true)
        }
        override fun setTouchOutlinesEnabled(enabled:Boolean):Boolean {
            if(status.value.rendererCapture.active||status.value.rendererBenchmark.active||status.value.rendererBenchmarkRecoveryPending)return false
            status.value=status.value.copy(touchOutlinesEnabled=enabled,touchPreview=null);confirm();return true
        }
        override fun moveCamera(motion: ChartMotion) { motions += motion; if (deferMotionConfirmation) status.value = status.value.copy(confirmedFrame = null) else confirm() }
        override suspend fun zoomCamera(factor: Double, anchor: ScreenPoint, frame: ChartFrame): Boolean { assertEquals(status.value.confirmedFrame, frame); zooms++; confirm(); return true }
        override fun setOverlays(value: ChartOverlays) {
            overlaySubmissions++
            if (value != latestOverlay) { latestOverlay = value; queuedOverlays++; confirm() }
        }
        override suspend fun applyOverlays(value: ChartOverlays): Boolean {
            latestOverlay=value;confirmedOverlays++
            try { if(overlayCompletion?.await()==false)return false }
            catch(cancel:CancellationException){cancelledOverlays++;throw cancel}
            confirm();return true
        }
        override suspend fun projectCoordinates(positions: List<GeoProjection>, frame: ChartFrame): List<ScreenPoint> { projections++; return positions.map { ScreenPoint(100.0, 100.0) } }
        override suspend fun applyPresentationProfile(value: ChartPresentation): EngineResult { status.value = status.value.copy(presentation = value); confirm(); return EngineResult(true) }
        override suspend fun initialize(config: ChartEngineConfig) = EngineResult(true)
        override suspend fun loadChartSet(id: String, paths: List<String>) = EngineResult(true)
        override suspend fun queryFeature(point: ScreenPoint): List<ChartFeature> { queries++; return emptyList() }
        override fun setS52Settings(settings: S52Settings) = Unit
        override fun setOwnship(state: OwnshipProjection) = Unit
        override fun setAisTargets(targets: List<AisProjection>) = Unit
        override fun setRouteProjection(route: RouteProjection?) = Unit
        override fun setWaypointProjection(waypoints: List<WaypointProjection>) = Unit
        override fun close() = Unit
    }
}
