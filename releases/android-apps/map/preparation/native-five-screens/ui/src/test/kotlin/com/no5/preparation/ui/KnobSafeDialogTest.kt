package com.no5.preparation.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KnobSafeDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @After fun close() { if (::activity.isInitialized) activity.pause().stop().destroy() }

    @Test fun platformDialogKeepsFullLuminaStageAndNeverPaintsInKnobExclusions() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup().visible().windowFocusChanged(true)
        activity.get().setContent {
            No5KnobSafeDialog({}) { Box(Modifier.fillMaxSize().background(Color.Magenta)) }
        }
        compose.waitForIdle()
        val window = compose.onNodeWithTag("knob-safe-dialog-window").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("knob-safe-dialog-content").fetchSemanticsNode().boundsInRoot
        assertEquals(2000f, window.width, .1f)
        assertEquals(1200f, window.height, .1f)
        assertTrue(KnobGeometry.safe(panel.left, panel.top, panel.right, panel.bottom))
        compose.runOnIdle {
            val view = requireNotNull(requireNotNull(ShadowDialog.getLatestDialog()).window).decorView
            val location = IntArray(2).also { view.getLocationOnScreen(it) }
            assertEquals("No inset may offset the calibrated stage", 0, location[0])
            assertEquals("No inset may offset the calibrated stage", 0, location[1])
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                assertEquals(android.graphics.Color.MAGENTA, bitmap.getPixel(1000, 1100))
                for (y in 900 until 1200 step 12) for (x in 0 until 2000 step 12) {
                    if (KnobGeometry.blocked(x.toFloat(), y.toFloat())) {
                        assertNotEquals("Panel paint in physical knob exclusion at $x,$y", android.graphics.Color.MAGENTA, bitmap.getPixel(x, y))
                    }
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun onlyAnOutsideTapRequestsCloseAndOutsideDraggingDoesNot() {
        var closes = 0
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup().visible().windowFocusChanged(true)
        activity.get().setContent {
            No5KnobSafeDialog({ closes++ }) { Box(Modifier.fillMaxSize().background(Color.Magenta)) }
        }
        compose.onNodeWithTag("knob-safe-dialog-content").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(0, closes) }
        compose.onNodeWithTag("knob-safe-dialog-window").performTouchInput {
            swipe(Offset(1f, 1f), Offset(90f, 90f))
        }
        compose.runOnIdle { assertEquals("A drag is not a close gesture", 0, closes) }
        compose.onNodeWithTag("knob-safe-dialog-window").performTouchInput {
            down(0, Offset(1f, 1f)); down(1, Offset(30f, 1f)); up(0); up(1)
        }
        compose.runOnIdle { assertEquals("A multiple-pointer gesture is not a close tap", 0, closes) }
        compose.onNodeWithTag("knob-safe-dialog-window").performTouchInput { click(Offset(1f, 1f)) }
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test fun outsideTapUsesLatestLocalDismissFunctionInsteadOfItsInitialCleanSnapshot() {
        val dirty = mutableStateOf(false)
        var confirmations = 0
        var cleanCloses = 0
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup().visible().windowFocusChanged(true)
        activity.get().setContent {
            val capturedDirty = dirty.value
            fun close() { if (capturedDirty) confirmations++ else cleanCloses++ }
            No5KnobSafeDialog(::close) { Box(Modifier.fillMaxSize().background(Color.Magenta)) }
        }
        compose.runOnIdle { dirty.value = true }
        compose.onNodeWithTag("knob-safe-dialog-window").performTouchInput { click(Offset(1f, 1f)) }
        compose.runOnIdle {
            assertEquals(1, confirmations)
            assertEquals(0, cleanCloses)
        }
    }

    @Test fun aShortPanelClosesOnBlankSpaceInsideTheSafeCorridor() {
        var closes = 0
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup().visible().windowFocusChanged(true)
        activity.get().setContent {
            No5Theme(false) {
                No5PanelDialog({ closes++ }, confirmButton = { Text("Close") },
                    modifier = Modifier.testTag("short-panel"), title = { Text("Title") }, text = { Text("Body") })
            }
        }
        val panel = compose.onNodeWithTag("short-panel").fetchSemanticsNode().boundsInRoot
        assertTrue("Test needs visible scrim above the actual panel", panel.top > 10f)
        compose.onNodeWithTag("knob-safe-dialog-window").performTouchInput {
            click(Offset(panel.center.x, panel.top - 5f))
        }
        compose.runOnIdle { assertEquals(1, closes) }
    }
}
