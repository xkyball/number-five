package com.no5.helm.ui

import android.app.Activity
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.robolectric.shadows.ShadowDialog

/** Model the visible window that Android would focus, including nested Dialogs. */
internal fun focusHostInputWindow(activity: Activity, compose: ComposeTestRule) {
    compose.waitForIdle()
    compose.runOnIdle {
        val dialog = ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }
        requireNotNull(dialog?.window ?: activity.window).decorView.dispatchWindowFocusChanged(true)
    }
    compose.waitForIdle()
}
