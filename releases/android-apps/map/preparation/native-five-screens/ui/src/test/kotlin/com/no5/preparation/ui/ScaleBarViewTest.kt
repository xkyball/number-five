package com.no5.preparation.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
class ScaleBarViewTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val viewport = ViewportSnapshot(7, 2000, 1070, "confirmed")
    @Before fun before() {
        // Use the same fullscreen geometry as the product and ApprovedMapShellTest.
        // System/action bars otherwise shrink this fixture below its pinned frame.
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup()
        activity.get().window.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            @Suppress("DEPRECATION")
            val immersive = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = decorView.systemUiVisibility or immersive
            WindowCompat.getInsetsController(this, decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    @After fun after() { activity.pause().stop().destroy() }

    @Test fun selectedUnitChoosesNiceDistanceAndPreservesMeasuredProjection() {
        val nautical = scaleBarForUnit(120f, 1000.0, DistanceUnit.NAUTICAL_MILES, viewport)!!
        assertEquals("0,5 sm", nautical.label)
        assertEquals(120f * 926f / 1000f, nautical.lengthPx, .001f)
        val metric = scaleBarForUnit(120f, 1000.0, DistanceUnit.KILOMETRES, viewport)!!
        assertEquals("1 km", metric.label)
        assertEquals(120f, metric.lengthPx, .001f)
        val metres = scaleBarForUnit(120f, 700.0, DistanceUnit.KILOMETRES, viewport)!!
        assertEquals("500 m", metres.label)
        assertEquals(120f * 500f / 700f, metres.lengthPx, .001f)
        assertEquals(viewport, nautical.viewport)
    }

    @Test fun niceValuesNeverUpscaleBeyondReferenceAndDoNotAcquireTrailingZeros() {
        for (unit in DistanceUnit.entries) for (metres in listOf(.1, 1.0, 10.0, 100.0, 370.4, 925.99, 1852.0, 3704.0, 10000.0)) {
            val scale = scaleBarForUnit(120f, metres, unit, viewport)!!
            assertTrue(scale.lengthPx > 0 && scale.lengthPx <= 120f)
            val parts = scale.label.split(' ')
            val labelled = parts[0].replace(',', '.').toDouble() * when (parts[1]) {
                "sm" -> 1852.0; "km" -> 1000.0; else -> 1.0
            }
            assertTrue("${scale.label} ≤ $metres m", labelled <= metres)
            assertEquals(labelled / metres * 120, scale.lengthPx.toDouble(), .0001)
            assertFalse(parts[0].endsWith(",0"))
        }
    }

    @Test fun rejectsInvalidDistancesAndLengths() {
        for (length in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertNull(scaleBarForUnit(length, 1000.0, DistanceUnit.NAUTICAL_MILES, viewport))
        for (metres in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertNull(scaleBarForUnit(120f, metres, DistanceUnit.KILOMETRES, viewport))
    }

    @Test fun physicalLengthConvertsThroughCalibratedDensityWithoutChangingItsPixels() {
        val scale = ScaleBar(120f, "0,5 sm", viewport)
        for (density in listOf(.5f, 1f, 2f, 3f)) {
            val dp = scaleBarLengthDp(scale, viewport, 2000f, 1070f, density, 400f)!!
            assertEquals(120f, dp * density, .0001f)
        }
        val resized = viewport.copy(widthPx = 1000, heightPx = 535)
        assertEquals(60f, scaleBarLengthDp(ScaleBar(60f, "1 km", resized), resized, 1000f, 535f, 1f, 200f)!!, .0001f)
    }

    @Test fun staleTokenRevisionResizeAndTooSmallPlacementHideTheBar() {
        val scale = ScaleBar(120f, "1 km", viewport)
        assertNull(scaleBarLengthDp(scale, viewport.copy(cameraToken = "new"), 2000f, 1070f, 1f, 400f))
        assertNull(scaleBarLengthDp(scale, viewport.copy(revision = 8), 2000f, 1070f, 1f, 400f))
        assertNull(scaleBarLengthDp(scale, viewport, 1000f, 535f, 1f, 400f))
        assertNull(scaleBarLengthDp(scale, null, 2000f, 1070f, 1f, 400f))
        assertNull(scaleBarLengthDp(scale, viewport, 2000f, 1070f, 1f, 119f))
    }

    @Test fun canvasDrawsMeasuredBarWithTicksAndRemovesItWhenAuthorityIsInvalidated() {
        val active = mutableStateOf<ViewportSnapshot?>(viewport)
        activity.get().setContent { No5ControlTheme(false) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.width(500.dp)) { ScaleBarView(ScaleBar(120f, "0,5 sm", viewport), active.value, 2000f, 1070f) }
            }
        } }
        compose.onNodeWithTag("chart-scale-label").assertTextEquals("0 · 0,5 sm")
        val bar = compose.onNodeWithTag("chart-scale-drawing")
        bar.assertContentDescriptionEquals("Maßstabsbalken von 0 bis 0,5 sm, fünf Teilstriche")
        assertEquals(120f, bar.fetchSemanticsNode().boundsInRoot.width, .5f)
        save("scale-bar-nautical-ticks")
        compose.runOnIdle { active.value = null }
        bar.assertDoesNotExist()
        compose.onNodeWithTag("chart-scale-label").assertDoesNotExist()
    }

    @Test fun frameAuthorityChangesHideValuesWithoutChangingScaleExclusionDuringGesture() {
        val chart = ChartUiState(status = DataStatus(Availability.READY, "Bestätigt"), viewport = viewport,
            scale = ScaleBar(120f, "0,5 sm", viewport))
        val current = mutableStateOf(chart)
        var blocked = emptyList<Rect>()
        var gestureBlocked = emptyList<Rect>()
        activity.get().setContent {
            No5Shell(ShellState(chart = current.value), {}, rendererSlot = { Box(it) },
                onBlockedRegions = { blocked = it }, onGestureBlockedRegions = { gestureBlocked = it })
        }
        val renderer = compose.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.widthPx.toFloat(), renderer.width, .5f)
        assertEquals(viewport.heightPx.toFloat(), renderer.height, .5f)
        val scale = compose.onNodeWithTag("chart-scale-bar").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            .translate(-renderer.left, -renderer.top)
        assertTrue(blocked.contains(scale)); assertTrue(gestureBlocked.contains(scale))
        val changedViewport = viewport.copy(revision = 8, cameraToken = "next")
        compose.runOnIdle { current.value = chart.copy(touchActive = true, viewport = changedViewport) }
        compose.onNodeWithTag("chart-scale-bar").assertDoesNotExist()
        assertTrue("Stale values must not remove this gesture's scale reservation", blocked.contains(scale))
        assertTrue(gestureBlocked.contains(scale))
        compose.runOnIdle { current.value = chart.copy(touchActive = true) }
        compose.onNodeWithTag("chart-scale-bar").assertIsDisplayed()
        assertTrue("A restored frame must not add a new exclusion", blocked.contains(scale))
        compose.runOnIdle { current.value = chart.copy(viewport = changedViewport) }
        compose.onNodeWithTag("chart-scale-bar").assertDoesNotExist()
        assertFalse(blocked.contains(scale)); assertFalse(gestureBlocked.contains(scale))
        compose.runOnIdle { current.value = chart.copy(touchActive = true, viewport = changedViewport) }
        compose.runOnIdle { current.value = chart.copy(touchActive = true) }
        compose.onNodeWithTag("chart-scale-bar").assertDoesNotExist()
        assertFalse("A gesture begun without a bar must not acquire one", blocked.contains(scale))
        assertFalse(gestureBlocked.contains(scale))
        compose.runOnIdle { current.value = chart }
        compose.onNodeWithTag("chart-scale-bar").assertIsDisplayed()
        assertTrue(blocked.contains(scale)); assertTrue(gestureBlocked.contains(scale))
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
