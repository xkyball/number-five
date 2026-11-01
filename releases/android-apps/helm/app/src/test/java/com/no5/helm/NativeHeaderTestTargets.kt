package com.no5.helm

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.junit.Assert.*

/** Native AndroidView children are not Compose virtual nodes; inspect the actual exported target. */
internal fun nativeHeaderTarget(composeRoot: View, tag: String): View {
    fun find(view: View): View? = if (view.tag == tag) view else if (view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
    return checkNotNull(find(composeRoot.rootView)) { "Actual native header target missing: $tag" }
}

internal fun assertNativeHeaderAccessibility(composeRoot: View, tag: String,
    description: String, clickable: Boolean) {
    val target = nativeHeaderTarget(composeRoot, tag)
    assertTrue("Actual native target is attached: $tag", target.isAttachedToWindow)
    assertTrue("Actual native target is shown: $tag", target.isShown)
    val visible = Rect()
    assertTrue("Native target retains visible bounds: $tag", target.getGlobalVisibleRect(visible))
    assertFalse("Native target has nonempty bounds: $tag", visible.isEmpty)
    val node = target.createAccessibilityNodeInfo()
    try {
        assertEquals("Actual native description: $tag", description, node.contentDescription?.toString())
        assertEquals("Actual native action state: $tag", clickable, node.isClickable)
        assertTrue("Actual native target is enabled: $tag", node.isEnabled)
    } finally { node.recycle() }
}

/** Real window dispatch exercises native interop hit testing instead of a removed Compose action. */
internal fun tapNativeHeaderTarget(ui: ComposeTestRule, composeRoot: View, tag: String) {
    ui.waitForIdle()
    val (decor, x, y) = ui.runOnIdle {
        val target = nativeHeaderTarget(composeRoot, tag)
        val root = target.rootView
        root.dispatchWindowFocusChanged(true)
        assertTrue("Native input target must be shown: $tag", target.isShown)
        val targetLocation = IntArray(2).also(target::getLocationInWindow)
        val rootLocation = IntArray(2).also(root::getLocationInWindow)
        Triple(root, targetLocation[0] + target.width / 2f - rootLocation[0],
            targetLocation[1] + target.height / 2f - rootLocation[1])
    }
    val now = SystemClock.uptimeMillis()
    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
        val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 32 else 0, action, x, y, 0)
        try { ui.runOnIdle { assertTrue(decor.dispatchTouchEvent(event)) } }
        finally { event.recycle() }
    }
    ui.waitForIdle()
}
