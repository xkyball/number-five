package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.no5.ui.no5Clickable
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

/** Synthetic plain action/selection fixture; no reference images or device claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], qualifiers = "w400dp-h300dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class No5ActionFeedbackTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val enabled = mutableStateOf(true)
    private val present = mutableStateOf(true)
    private var clicks = 0
    private var input: InputModeManager? = null
    private fun launch() {
        host = Robolectric.buildActivity(ComponentActivity::class.java)
        host.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        host.setup().visible().windowFocusChanged(true)
        host.get().setContent {
            input = LocalInputModeManager.current
            if (present.value) Box(Modifier.size(120.dp, 80.dp).background(Color.White)
                .testTag("action").semantics { selected = true }
                .no5Clickable(enabled = enabled.value, feedbackColor = Color.Blue) { clicks++ })
        }
        settle()
    }
    private fun settle() { ui.waitForIdle(); ui.mainClock.advanceTimeBy(32); ui.waitForIdle() }
    private fun centreColor(edge: Boolean = false): Int {
        val bounds = ui.onNodeWithTag("action").fetchSemanticsNode().boundsInRoot
        var color = 0
        ui.runOnIdle {
            val decor = host.get().window.decorView
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            try { decor.draw(Canvas(bitmap)); color = bitmap.getPixel(if(edge)bounds.left.toInt()+1 else bounds.center.x.toInt(), bounds.center.y.toInt()) }
            finally { bitmap.recycle() }
        }
        return color
    }
    @After fun close() { if (::host.isInitialized) host.pause().stop().destroy() }

    @Test fun pressIsTransientAndDoesNotReplaceRealSelection() {
        launch()
        val neutral = centreColor()
        ui.onNodeWithTag("action").performTouchInput { down(center) }; settle()
        assertNotEquals(neutral, centreColor())
        ui.onNodeWithTag("action").performTouchInput { up() }; settle()
        assertEquals(1, clicks)
        assertEquals(neutral, centreColor())
        ui.onNodeWithTag("action").assertIsSelected()
    }
    @Test fun dragOutCancelAndSecondPointerNeverActivateOrLatchPress() {
        launch(); val neutral = centreColor()
        ui.onNodeWithTag("action").performTouchInput {
            down(center); moveTo(Offset(width + 20f, center.y)); up()
        }; settle()
        assertEquals(0, clicks); assertEquals(neutral, centreColor())
        ui.onNodeWithTag("action").performTouchInput { down(center); cancel() }; settle()
        assertEquals(0, clicks); assertEquals(neutral, centreColor())
        ui.onNodeWithTag("action").performTouchInput {
            down(0, center); down(1, center + Offset(8f, 8f)); up(1); up(0)
        }; settle()
        assertEquals(0, clicks); assertEquals(neutral, centreColor())
        ui.onNodeWithTag("action").assertIsSelected()
    }
    @Test fun disablingOrRemovingDuringPressCannotCompleteTheOldAction() {
        launch()
        ui.onNodeWithTag("action").performTouchInput { down(center) }
        ui.runOnIdle { enabled.value = false }; settle()
        ui.runOnIdle { enabled.value = true }; settle()
        ui.onNodeWithTag("action").performTouchInput { up() }; settle()
        assertEquals(0, clicks)
        ui.onNodeWithTag("action").performTouchInput { down(center) }
        ui.runOnIdle { present.value = false }; settle()
        ui.runOnIdle { present.value = true }; settle()
        ui.onNodeWithTag("action").performTouchInput { up() }; settle()
        assertEquals(0, clicks)
        ui.onNodeWithTag("action").performTouchInput { click(center) }; settle()
        assertEquals(1, clicks)
    }
    @Test fun windowLossCancelsPressAndReturningDoesNotReplayIt() {
        launch(); val neutral = centreColor()
        ui.onNodeWithTag("action").performTouchInput { down(center) }
        ui.runOnIdle { host.windowFocusChanged(false) }; settle()
        assertEquals(neutral, centreColor())
        ui.runOnIdle { host.windowFocusChanged(true) }; settle()
        ui.onNodeWithTag("action").performTouchInput { up() }; settle()
        assertEquals(0, clicks)
        ui.onNodeWithTag("action").assertIsSelected()
        ui.onNodeWithTag("action").performTouchInput { click(center) }; settle()
        assertEquals(1, clicks)
    }
    @Test fun keyboardFocusHasAnOutlineWithoutASecondSelectionFill() {
        launch(); val neutral = centreColor()
        ui.runOnIdle { input!!.requestInputMode(InputMode.Keyboard) }
        ui.onNodeWithTag("action").performSemanticsAction(SemanticsActions.RequestFocus) { it() }; settle()
        ui.onNodeWithTag("action").assertIsFocused().assertIsSelected()
        assertEquals(neutral, centreColor())
        assertNotEquals(neutral, centreColor(edge = true))
    }
    @Test fun pauseCancelsOnlyTheTransientPressAndKeepsSelection() {
        launch()
        ui.onNodeWithTag("action").performTouchInput { down(center) }
        ui.runOnIdle { host.pause() }; settle()
        ui.runOnIdle { host.resume().windowFocusChanged(true) }; settle()
        ui.onNodeWithTag("action").performTouchInput { up() }; settle()
        assertEquals(0, clicks); ui.onNodeWithTag("action").assertIsSelected()
        ui.onNodeWithTag("action").performTouchInput { click(center) }; settle()
        assertEquals(1, clicks)
    }
}
