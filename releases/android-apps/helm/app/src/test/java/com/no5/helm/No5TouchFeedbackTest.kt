package com.no5.helm

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.InputDevice
import android.widget.Button
import android.widget.FrameLayout
import com.no5.ui.No5TouchFeedback
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import android.os.SystemClock

/** Tests the shared Android View helper, independently of HOME/Control business models. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], qualifiers = "w400dp-h300dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class No5TouchFeedbackTest {
    private lateinit var host: ActivityController<Activity>
    private lateinit var root: FrameLayout
    private lateinit var button: Button
    private var clicks = 0
    private var downTime = 0L
    @Before fun setup() {
        host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        root = FrameLayout(host.get())
        button = Button(host.get()).apply {
            isSelected = true; isFocusableInTouchMode = false
            background = ColorDrawable(Color.WHITE)
            setOnClickListener { clicks++ }
        }
        root.addView(button, FrameLayout.LayoutParams(120, 80))
        host.get().setContentView(root)
        No5TouchFeedback.install(button, Color.BLUE, Color.GREEN)
        host.windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 300)
    }
    @After fun close() { host.pause().stop().destroy() }
    private fun send(action: Int, x: Float = 60f, y: Float = 40f) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try { button.dispatchTouchEvent(event) } finally { event.recycle() }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun pixel(x: Int = 60, y: Int = 40): Int {
        val image = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try { button.background.setBounds(0, 0, 120, 80); button.background.draw(Canvas(image)); return image.getPixel(x, y) }
        finally { image.recycle() }
    }
    @Test fun focusIsOutlineWhilePressFillEndsOnReleaseAndSelectionSurvives() {
        // Only the focus case enables touch-mode focus. A focusable-in-touch-mode Button
        // consumes its first tap after re-enabling to acquire focus, before activation.
        button.isFocusableInTouchMode = true
        button.requestFocus(); assertTrue(button.hasFocus())
        assertEquals(Color.WHITE, pixel())
        assertEquals(Color.GREEN, pixel(1, 40))
        send(MotionEvent.ACTION_DOWN); assertTrue(button.isPressed); assertNotEquals(Color.WHITE, pixel())
        send(MotionEvent.ACTION_UP)
        assertEquals(1, clicks); assertFalse(button.isPressed); assertEquals(Color.WHITE, pixel())
        assertTrue(button.isSelected); assertTrue(button.hasFocus())
    }
    @Test fun cancelDragOutDisableAndWindowLossSuppressOldRelease() {
        send(MotionEvent.ACTION_DOWN); send(MotionEvent.ACTION_CANCEL); assertEquals(0, clicks)
        send(MotionEvent.ACTION_DOWN); send(MotionEvent.ACTION_MOVE, 150f); send(MotionEvent.ACTION_UP); assertEquals(0, clicks)
        send(MotionEvent.ACTION_DOWN); button.isEnabled = false; button.isEnabled = true; send(MotionEvent.ACTION_UP); assertEquals(0, clicks)
        send(MotionEvent.ACTION_DOWN); host.windowFocusChanged(false); host.windowFocusChanged(true); send(MotionEvent.ACTION_UP)
        assertEquals(0, clicks); assertFalse(button.isPressed); assertTrue(button.isSelected)
        send(MotionEvent.ACTION_DOWN); send(MotionEvent.ACTION_UP); assertEquals(1, clicks)
    }
    @Test fun pauseAndDetachDoNotLeaveAnArmedAction() {
        send(MotionEvent.ACTION_DOWN); host.pause(); host.resume(); host.windowFocusChanged(true); send(MotionEvent.ACTION_UP)
        assertEquals(0, clicks); assertFalse(button.isPressed)
        send(MotionEvent.ACTION_DOWN); root.removeView(button); root.addView(button, FrameLayout.LayoutParams(120, 80)); send(MotionEvent.ACTION_UP)
        assertEquals(0, clicks); assertTrue(button.isSelected)
    }
    @Test fun secondPointerCancelsRatherThanActivatingTheMomentaryControl() {
        send(MotionEvent.ACTION_DOWN)
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id=index; toolType=MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = Array(2) { index -> MotionEvent.PointerCoords().apply { x=60f+index*8f; y=40f; pressure=1f; size=1f } }
        val event = MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,properties,coordinates,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
        try { button.dispatchTouchEvent(event) } finally { event.recycle() }
        send(MotionEvent.ACTION_UP)
        assertEquals(0, clicks); assertFalse(button.isPressed); assertTrue(button.isSelected)
    }
    @Test fun reinstallUpdatesFaceAndColorsWithoutDuplicateClickOwners() {
        No5TouchFeedback.install(button, ColorDrawable(Color.YELLOW), Color.RED, Color.CYAN)
        assertEquals(Color.YELLOW, pixel())
        send(MotionEvent.ACTION_DOWN); send(MotionEvent.ACTION_UP)
        assertEquals(1, clicks); assertEquals(Color.YELLOW, pixel()); assertTrue(button.isSelected)
    }
}
