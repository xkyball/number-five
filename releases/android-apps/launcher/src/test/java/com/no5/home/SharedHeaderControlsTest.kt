package com.no5.home

import android.app.Activity
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.no5.header.artwork.No5HeaderControlsView
import com.no5.releaseinfo.ReleaseInfo
import com.no5.ui.UiLanguage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35], qualifiers="w1000dp-h600dp-land-xhdpi")
class SharedHeaderControlsTest {
    @org.junit.After fun restoreLanguage() { UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de") }
    private fun children(view: ViewGroup) = (0 until view.childCount).map(view::getChildAt)
    @Test fun threeLocalesKeepVersionInsideHeaderAndNativeTargetsSeparate() {
        for (language in listOf("de", "en", "af")) for (night in listOf(false,true)) {
            UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),language)
            Robolectric.buildActivity(Activity::class.java).setup().use { host ->
                val activity=host.get();val controls=No5HeaderControlsView(activity)
                activity.setContentView(controls)
                val build=ReleaseInfo.shortBuildLabel(activity,"HOME")
                var selected:Boolean?=null;var details:String?=null
                controls.render(build,night,true,{selected=it},{details=it})
                controls.measure(View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(130,View.MeasureSpec.EXACTLY))
                controls.layout(0,0,2000,130)
                val items=children(controls)
                val version=items.filterIsInstance<TextView>().single {it.tag=="installed-app-version"}
                assertEquals(build,version.text.toString())
                assertTrue(build.startsWith("HOME · B")); assertEquals(82,version.top)
                assertTrue(version.bottom<=130);assertFalse(version.isClickable)
                assertTrue(version.paint.measureText(build)<=version.width)
                val targets=items.filter {it.isClickable}
                assertEquals(3,targets.size)
                val bounds=targets.map {Rect(it.left,it.top,it.right,it.bottom)}
                bounds.forEach {assertTrue(it.width()>=96);assertTrue(it.height()>=96)}
                bounds.forEachIndexed {i,a->bounds.drop(i+1).forEach {b->assertFalse(Rect.intersects(a,b))}}
                items.single {it.tag=="header-day"}.performClick();assertEquals(false,selected)
                items.single {it.tag=="header-night"}.performClick();assertEquals(true,selected)
                items.single {it.tag=="header-network-info"}.performClick()
                assertFalse(details.isNullOrBlank());assertTrue(details!!.contains("RSSI"))
                controls.setForeground(false)
            }
        }
    }
    @Test fun explicitInfoIsALayerAndDoesNotNavigateAway() {
        Robolectric.buildActivity(Activity::class.java).setup().use {host->
            val activity=host.get()
            val dialog=ReleaseInfo.showDetails(activity,"RSSI: −63 dBm",true)
            assertTrue(dialog.isShowing);assertFalse(activity.isFinishing)
            assertNull(org.robolectric.Shadows.shadowOf(activity).nextStartedActivity)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun actualInfoCloseHasTransientPressAndUnfilledFocusAndCancelNeverClosesIt() {
        for (night in listOf(false, true)) {
            Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true).use { host ->
                val activity = host.get()
                val dialog = ReleaseInfo.showDetails(activity, "TEST FIXTURE", night)
                try {
                    val close = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    // Robolectric 4.15 does not attach this native Dialog to a ViewRoot.
                    // Exercise its real constructed button/callback in an attached host fixture;
                    // the separate secure Dialog window is checked by Android instrumentation.
                    (close.parent as ViewGroup).removeView(close)
                    val fixture = android.widget.FrameLayout(activity).apply {
                        addView(close, android.widget.FrameLayout.LayoutParams(640, 96))
                    }
                    activity.setContentView(fixture)
                    host.visible().windowFocusChanged(true)
                    fixture.measure(View.MeasureSpec.makeMeasureSpec(680, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY))
                    fixture.layout(0, 0, 680, 180)
                    shadowOf(Looper.getMainLooper()).idle()
                    assertTrue(close.isAttachedToWindow); assertTrue(close.hasWindowFocus())
                    assertTrue(close.width > 0); assertTrue(close.height > 0)
                    close.isSelected = true // Caller-owned state must survive transient cleanup.
                    close.isFocusableInTouchMode = true
                    assertTrue(close.requestFocus()); assertTrue(close.hasFocus())
                    fun pixel(edge: Boolean = false): Int {
                        val image = Bitmap.createBitmap(close.width, close.height, Bitmap.Config.ARGB_8888)
                        try {
                            close.background.setBounds(0, 0, close.width, close.height)
                            close.background.draw(Canvas(image))
                            return image.getPixel(if (edge) 0 else close.width / 2,
                                close.height / 2)
                        } finally { image.recycle() }
                    }
                    val surface = Color.parseColor(if (night) "#000000" else "#f6f9fa")
                    // Exact straight-edge pixel in the frozen D03 focused face (1.5px stroke).
                    // The 5px outside contour belongs to the actual dialog parent, not this detached button fixture.
                    val ink = Color.parseColor(if (night) "#b20000" else "#fd823e")
                    assertEquals(surface, pixel()); assertEquals(ink, pixel(edge = true))
                    var downTime = 0L
                    fun event(action: Int) {
                        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
                        val input = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                            close.width / 2f, close.height / 2f, 0)
                        try { close.dispatchTouchEvent(input) } finally { input.recycle() }
                        shadowOf(Looper.getMainLooper()).idle()
                    }
                    event(MotionEvent.ACTION_DOWN)
                    assertTrue(close.isPressed); assertNotEquals(surface, pixel())
                    event(MotionEvent.ACTION_CANCEL)
                    assertFalse(close.isPressed); assertEquals(surface, pixel())
                    assertTrue(dialog.isShowing); assertTrue(close.isSelected); assertTrue(close.hasFocus())
                    event(MotionEvent.ACTION_DOWN); event(MotionEvent.ACTION_UP)
                    assertFalse(close.isPressed); assertFalse(dialog.isShowing)
                    assertTrue(close.isSelected); assertFalse(activity.isFinishing)
                    assertNull(shadowOf(activity).nextStartedActivity)
                } finally { if (dialog.isShowing) dialog.dismiss() }
            }
        }
    }
}
