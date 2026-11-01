package com.no5.helm

import android.view.View
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.robolectric.shadows.ShadowDialog

/** Robolectric does not deliver WindowManager focus when a Dialog/Popup window opens. */
internal fun focusHelmTestForegroundWindow(ui:ComposeContentTestRule){
    ui.runOnIdle{
        val windows=WindowInspector.getGlobalWindowViews().filter{it.isAttachedToWindow&&it.visibility==View.VISIBLE}
        val target=ShadowDialog.getLatestDialog()?.takeIf{it.isShowing}?.window?.decorView?:windows.lastOrNull()
        windows.forEach{it.dispatchWindowFocusChanged(it===target)}
        target?.dispatchWindowFocusChanged(true)
    }
    ui.waitForIdle()
}
