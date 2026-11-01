package com.no5.helm.ui

import android.view.View
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual production Activity superclass lifecycle, without loading the JNI engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@Suppress("DEPRECATION")
class ImmersiveActivityTest {
    private fun createActivity() = Robolectric.buildActivity(No5ImmersiveActivity::class.java).apply {
        get().setTheme(android.R.style.Theme_Material_NoActionBar)
        setup()
    }
    private fun visibleBars(activity: No5ImmersiveActivity) {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }
    private fun assertFullscreen(activity: No5ImmersiveActivity) {
        val flags = activity.window.attributes.flags
        assertTrue(flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0)
        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            activity.window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        val visibility = activity.window.decorView.systemUiVisibility
        assertTrue(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
        assertTrue(visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
        assertTrue(visibility and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY != 0)
        assertTrue(visibility and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN != 0)
    }
    @Test fun creationUsesAndroid10FullscreenWindowAndImmersiveFlags() {
        val activity = createActivity()
        try { assertFullscreen(activity.get()) } finally { activity.pause().stop().destroy() }
    }
    @Test fun resumeRestoresBarsChangedWhileAppWasInBackground() {
        val activity = createActivity()
        try {
            activity.pause().stop()
            visibleBars(activity.get())
            activity.start().resume()
            assertFullscreen(activity.get())
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun windowFocusRestoresFullscreenAfterExternalWindow() {
        val activity = createActivity()
        try {
            activity.get().onWindowFocusChanged(false)
            visibleBars(activity.get())
            activity.get().onWindowFocusChanged(true)
            assertFullscreen(activity.get())
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun lostFocusDoesNotRehideBarsOfAnExternalActivity() {
        val activity = createActivity()
        try {
            visibleBars(activity.get())
            activity.get().onWindowFocusChanged(false)
            assertTrue(activity.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN != 0)
            assertEquals(View.SYSTEM_UI_FLAG_VISIBLE, activity.get().window.decorView.systemUiVisibility)
        } finally { activity.pause().stop().destroy() }
    }
}
