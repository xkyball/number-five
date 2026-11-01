package com.no5.helm

import android.view.View
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.junit.Assert.*

internal fun sonarWorkspaceViews():List<View> = WindowInspector.getGlobalWindowViews()
    .filter{it.isAttachedToWindow&&it.visibility==View.VISIBLE}.flatMap(::nativeWorkspaceViews)

/** Real native row/action, followed by the host's explicit foreground-window delivery. */
internal fun sonarWorkspaceTap(ui:ComposeContentTestRule,key:String){
    ui.runOnIdle{val target=sonarWorkspaceViews().singleOrNull{it.tag==key};assertNotNull("Missing native $key",target);assertTrue("Disabled native $key",target!!.isEnabled);target.performClick()}
    ui.waitForIdle();focusHelmTestForegroundWindow(ui)
}
internal fun sonarWorkspaceCloseSheet(ui:ComposeContentTestRule){
    ui.onNodeWithTag("Schließen").performClick();ui.waitForIdle();focusHelmTestForegroundWindow(ui)
}
internal fun sonarImagePage(ui:ComposeContentTestRule){sonarWorkspaceTap(ui,"no5-row-sonar-menu-image")}
internal fun sonarSmoothingSheet(ui:ComposeContentTestRule){
    sonarImagePage(ui);sonarWorkspaceTap(ui,"no5-row-sonar-picture-details");sonarWorkspaceTap(ui,"no5-row-sonar-smoothing")
}
