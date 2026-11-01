package com.no5.helm

import android.app.Activity
import android.os.Looper
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33, 35], qualifiers = "w1000dp-h600dp-land-xhdpi")
@Suppress("DEPRECATION")
class ImmersiveWindowTest {
    class Host : Activity() {
        internal lateinit var immersive: ImmersiveWindow
        var attributeWrites=0
        override fun onWindowAttributesChanged(attributes:android.view.WindowManager.LayoutParams) {
            super.onWindowAttributesChanged(attributes); attributeWrites++
        }
        override fun onCreate(state: android.os.Bundle?) {
            super.onCreate(state)
            setContentView(View(this))
            immersive = ImmersiveWindow(this)
        }
        override fun onResume() { super.onResume(); immersive.onResume() }
        override fun onPause() { immersive.onPause(); super.onPause() }
        override fun onWindowFocusChanged(focus: Boolean) {
            super.onWindowFocusChanged(focus); immersive.onWindowFocusChanged(focus)
        }
        override fun onDestroy() { immersive.close(); super.onDestroy() }
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun assertHidden(activity: Host) {
        assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN != 0)
        assertEquals(0, activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
        assertEquals(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            activity.window.attributes.softInputMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        if (android.os.Build.VERSION.SDK_INT == 29) {
            assertEquals(0x1706, activity.window.decorView.systemUiVisibility and 0x1706)
        } else {
            val insets = activity.window.decorView.rootWindowInsets
            assertNotNull(insets)
            assertFalse(insets.isVisible(android.view.WindowInsets.Type.statusBars()))
            assertFalse(insets.isVisible(android.view.WindowInsets.Type.navigationBars()))
        }
    }
    @Test fun appliesAfterAttachmentAndFocus() {
        Robolectric.buildActivity(Host::class.java).setup().use { controller ->
            controller.windowFocusChanged(true); idle()
            assertHidden(controller.get())
        }
    }
    @Test @Config(sdk=[29]) fun alreadyClaimedWindowDoesNotRelayoutOnInPlaceResume() {
        Robolectric.buildActivity(Host::class.java).setup().use { controller ->
            controller.windowFocusChanged(true); idle()
            val activity=controller.get(); assertHidden(activity)
            activity.attributeWrites=0
            controller.pause().resume(); idle()
            assertHidden(activity)
            assertEquals("An unchanged fullscreen window must not issue fresh WindowManager attribute writes",0,activity.attributeWrites)
        }
    }
    @Test fun pickerStylePauseAndFocusReturnRestoresFullscreen() {
        Robolectric.buildActivity(Host::class.java).setup().use { controller ->
            controller.windowFocusChanged(true); idle()
            val activity = controller.get()
            controller.windowFocusChanged(false).pause().stop()
            activity.window.decorView.systemUiVisibility = 0x1500
            if (android.os.Build.VERSION.SDK_INT >= 30)
                activity.window.insetsController!!.show(android.view.WindowInsets.Type.systemBars())
            controller.restart().start().resume()
            idle() // Must not hide another window's bars while focus is still absent.
            if (android.os.Build.VERSION.SDK_INT == 29) {
                assertEquals(0x1500, activity.window.decorView.systemUiVisibility)
            } else {
                assertTrue(activity.window.decorView.rootWindowInsets.isVisible(
                    android.view.WindowInsets.Type.statusBars()))
                assertTrue(activity.window.decorView.rootWindowInsets.isVisible(
                    android.view.WindowInsets.Type.navigationBars()))
            }
            controller.windowFocusChanged(true); idle()
            assertHidden(activity)
        }
    }
    @Test fun focusOnlyReturnRestoresLayoutAndDoesNotContinuouslyOverrideClearing() {
        Robolectric.buildActivity(Host::class.java).setup().use { controller ->
            controller.windowFocusChanged(true); idle()
            controller.windowFocusChanged(false)
            controller.get().window.decorView.systemUiVisibility = 0x1500
            controller.windowFocusChanged(true); idle()
            assertHidden(controller.get())
            controller.get().window.decorView.systemUiVisibility = 0x1500
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(0x1500, controller.get().window.decorView.systemUiVisibility)
        }
    }
    @Test fun destructionCancelsQueuedRequest() {
        Robolectric.buildActivity(Host::class.java).setup().use { controller ->
            controller.windowFocusChanged(true)
            val activity = controller.get()
            activity.immersive.close()
            activity.window.decorView.systemUiVisibility = 0x1500
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(0x1500, activity.window.decorView.systemUiVisibility)
        }
    }

    @Test @Config(sdk = [29]) fun visibleInsetsReapplyWindowClaimOnceWithoutTouchingAnotherWindow() {
        Robolectric.buildActivity(Host::class.java).setup().use { controller ->
            controller.windowFocusChanged(true); idle()
            val activity = controller.get()
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            val insets = androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.of(0,130,0,0))
                .setVisible(androidx.core.view.WindowInsetsCompat.Type.systemBars(), true).build()
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets)
            idle(); assertHidden(activity)
            controller.windowFocusChanged(false).pause()
            activity.window.decorView.systemUiVisibility = 0x1500
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets)
            idle(); assertEquals(0x1500, activity.window.decorView.systemUiVisibility)
        }
    }
}
