package com.no5.helm.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.no5.preparation.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real Activity MotionEvent dispatch; host rendering at the specified 320 dpi, no GLES claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactControlsTouchTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<No5ImmersiveActivity>
    private var calls = 0
    private var time = 1000L
    private fun launch(night: Boolean = false, content: @Composable () -> Unit = {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            No5Selection("Tag", true, { calls++ }, Modifier.testTag("day"))
            No5Selection("Rotnacht", false, { calls++ }, Modifier.testTag("night"))
            No5CompactAction("Gesperrt", { calls++ }, Modifier.testTag("disabled"), enabled = false)
        }
    }) {
        controller = Robolectric.buildActivity(No5ImmersiveActivity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        controller.setup()
        controller.get().setContent { No5ControlTheme(night) { content() } }
        compose.waitForIdle()
        assertEquals(320, controller.get().resources.displayMetrics.densityDpi)
        assertEquals(2f, controller.get().resources.displayMetrics.density, 0f)
    }
    @After fun close() { if (::controller.isInitialized) controller.pause().stop().destroy() }
    private fun tap(point: Offset) {
        time += 32
        val down = time
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(down, time, action, point.x, point.y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { compose.runOnIdle { controller.get().dispatchTouchEvent(event) } } finally { event.recycle() }
            time += 32
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        }
    }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun compactGeometry() {
        val hits = listOf("day", "night", "disabled").map { tag ->
            val hit = bounds(tag)
            val face = compose.onNodeWithTag(tag, useUnmergedTree = true).onChildren().filter(hasTestTag("compact-control-face")).onFirst().fetchSemanticsNode().boundsInRoot
            assertEquals(96f, hit.height, .1f); assertTrue(hit.width >= 96f)
            assertEquals(64f, face.height, .1f)
            assertEquals(16f, face.top - hit.top, .1f); assertEquals(16f, hit.bottom - face.bottom, .1f)
            assertTrue(face.width <= hit.width)
            hit
        }
        hits.zipWithNext().forEach { (left, right) -> assertEquals(18f, right.left - left.right, .1f) }
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Rotnacht", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertFalse("Text size=${layout.single().size}, widthOverflow=${layout.single().didOverflowWidth}, heightOverflow=${layout.single().didOverflowHeight}, style=${layout.single().layoutInput.style}", layout.single().hasVisualOverflow)
    }
    @Test fun compactDayGeometryAndTransparentBandsHaveReal48DpClickBounds() {
        launch(); compactGeometry()
        compose.onNodeWithTag("day").assertIsEnabled().assertIsSelected()
        val b = bounds("day")
        tap(Offset(b.center.x, b.top + 2f)); assertEquals(1, calls)
        tap(Offset(b.center.x, b.bottom - 2f)); assertEquals(2, calls)
        tap(bounds("night").center); assertEquals(3, calls)
        tap(bounds("disabled").center); assertEquals(3, calls)
        save("compact-day")
    }
    @Test fun redNightHasTheSameTouchAndFaceGeometry() {
        launch(true); compactGeometry(); tap(bounds("night").center); assertEquals(1, calls)
        save("compact-red-night")
    }
    @Test fun aisRowDetailsAndBackUseRealTouchesAndRetainSelection() {
        var state by mutableStateOf(AisState(targets = frozenListOf(AisTarget("211123456", "LUMINA TEST"))))
        launch { AisScreen(state, { state = reduceAis(state, it) }, Modifier.width(400.dp).fillMaxHeight()) }
        val row = bounds("ais-target-211123456")
        assertTrue(row.height >= 120f)
        tap(row.center)
        compose.onNodeWithTag("ais-list-back").assertIsDisplayed()
        assertTrue(state.detailsExpanded)
        compose.onNodeWithText("CPA/TCPA unbekannt bedeutet keine bestätigte Entwarnung.").assertExists()
        compose.onNodeWithText("Risiko unbekannt").assertExists()
        compose.onNodeWithText("Messalter").assertExists()
        compose.onNodeWithText("Messzeit (UTC): Unbekannt").assertExists()
        tap(bounds("ais-list-back").center)
        assertFalse(state.detailsExpanded); assertEquals("211123456", state.selectedMmsi)
        compose.onNodeWithTag("ais-target-211123456").assertIsSelected()
        compose.onNodeWithTag("ais-filter-ALL").assertIsEnabled().assertIsSelected()
        tap(bounds("ais-filter-ALL").center)
        assertEquals(AisFilter.ALL, state.filter)
        save("ais-list-day")
    }
    private fun save(name: String) = compose.runOnIdle {
        val decor = controller.get().window.decorView
        assertEquals(2000, decor.width); assertEquals(1200, decor.height)
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        try {
            decor.draw(Canvas(bitmap))
            val output = File("build/ui-evidence/$name.png"); output.parentFile!!.mkdirs()
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
