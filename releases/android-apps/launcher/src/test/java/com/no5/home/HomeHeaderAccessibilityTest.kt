package com.no5.home

import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import com.no5.ui.UiLanguage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** Original HOME hierarchy: accessibility bounds must identify the existing brand touch ROI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], qualifiers = "w1000dp-h600dp-land-xhdpi")
class HomeHeaderAccessibilityTest {
    private lateinit var oldLanguage: String
    @Before fun language() {
        val app = RuntimeEnvironment.getApplication()
        oldLanguage = UiLanguage.current(app)
        UiLanguage.acceptConfirmed(app, "de")
    }
    @After fun restoreLanguage() { UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(), oldLanguage) }
    private fun all(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(all(view.getChildAt(index)))
    }
    private fun layout(view: View, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 2000, height)
    }

    @Test fun brandNodeBoundsCenteredTouchReturnsHomeAtFullAndReducedStageSizes() {
        for (height in listOf(1200, 928)) {
            Robolectric.buildActivity(HomeActivity::class.java).setup().visible().windowFocusChanged(true).use { host ->
                val activity = host.get()
                all(activity.window.decorView).filterIsInstance<Button>().single { it.text == "Alle Apps" }.performClick()
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                layout(content, height)
                assertEquals(1, all(content).filterIsInstance<HomeWorkspaceView>().count())
                val brand = all(content).filterIsInstance<HomeHeaderView>().single()
                val node = brand.createAccessibilityNodeInfo()
                val screen = Rect()
                try {
                    node.getBoundsInScreen(screen)
                    val scale = 1f
                    val stageLeft = ((2000 - 2000 * scale) / 2).toInt()
                    val location = IntArray(2); content.getLocationOnScreen(location)
                    assertEquals("Only the existing logo touch ROI is exported at stage height $height",
                        Rect(floor(location[0] + stageLeft + 24 * scale).toInt(),
                            floor(location[1] + 4 * scale).toInt(),
                            ceil(location[0] + stageLeft + 262 * scale).toInt(),
                            ceil(location[1] + 108 * scale).toInt()), screen)
                    assertTrue(node.isClickable); assertTrue(node.isFocusable)
                    assertEquals("No5 HOME öffnen", node.contentDescription.toString())
                    assertFalse("The brand export must not include the media header", screen.contains(
                        (location[0] + stageLeft + 800 * scale).toInt(), (location[1] + 50 * scale).toInt()))
                } finally { node.recycle() }
                val decor = activity.window.decorView
                val decorLocation = IntArray(2); decor.getLocationOnScreen(decorLocation)
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(down, down + if (action == MotionEvent.ACTION_UP) 32 else 0,
                        action, screen.exactCenterX() - decorLocation[0], screen.exactCenterY() - decorLocation[1], 0)
                    try { assertTrue(decor.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("Bounds-centered touch must use existing HOME navigation", 0,
                    all(activity.window.decorView).filterIsInstance<HomeWorkspaceView>().count())
                assertNull("Brand touch must not navigate to HELM", shadowOf(activity).nextStartedActivity)
            }
        }
    }

    @Test fun originalBrandAccessibilityClickUsesTheSameHomeOwner() {
        Robolectric.buildActivity(HomeActivity::class.java).setup().visible().windowFocusChanged(true).use { host ->
            val activity = host.get()
            all(activity.window.decorView).filterIsInstance<Button>().single { it.text == "Alle Apps" }.performClick()
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            layout(content, 1200)
            assertEquals(1, all(content).filterIsInstance<HomeWorkspaceView>().count())
            val brand = all(content).filterIsInstance<HomeHeaderView>().single()
            assertTrue(brand.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, all(activity.window.decorView).filterIsInstance<HomeWorkspaceView>().count())
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }
}
