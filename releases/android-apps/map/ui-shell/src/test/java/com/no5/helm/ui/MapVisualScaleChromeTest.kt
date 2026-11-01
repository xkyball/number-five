package com.no5.helm.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.preparation.ui.*
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

/** Actual approved chrome, including the lower-right native Canvas label; no renderer substitute claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapVisualScaleChromeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<No5ImmersiveActivity>? = null
    @After fun close() { activity?.pause()?.stop()?.destroy() }

    @Test fun bothScaleDisplaysFollowTheVisibleBufferWhileQueryAuthorityChangesAndClearWhenUnknown() {
        val host = Robolectric.buildActivity(No5ImmersiveActivity::class.java)
        activity = host
        host.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        host.setup().visible().windowFocusChanged(true)
        val visual = ViewportSnapshot(11, 2000, 1070, "VISUAL:observed-buffer")
        val query = ViewportSnapshot(21, 2000, 1070, "confirmed-query-camera")
        val model = mutableStateOf(ChartUiState(status = DataStatus(Availability.READY, ""), viewport = query,
            scale = ScaleBar(120f, "500 m", visual), scaleViewport = visual))
        host.get().setContent {
            No5Shell(ShellState(chart = model.value), {}, rendererSlot = { modifier ->
                Box(modifier.background(Color(0xFF537A77)))
            })
        }
        compose.onNodeWithTag("chart-scale-label").assertTextEquals("0 · 500 m")
        compose.onNodeWithTag("north-up").assertIsEnabled()
        val scaleBounds = compose.onNodeWithTag("chart-scale-bar").fetchSemanticsNode().boundsInRoot
        val first = capture()
        try {
            // A real pending FOLLOW request has no confirmed query frame; the visible buffer remains measured.
            compose.runOnIdle { model.value = model.value.copy(viewport = null) }
            compose.onNodeWithTag("north-up").assertIsNotEnabled()
            assertSameScalePixels(first, scaleBounds)

            // A newly confirmed target must not relabel the still-observed visual buffer.
            compose.runOnIdle { model.value = model.value.copy(viewport = query.copy(revision = 22, cameraToken = "new-query-camera")) }
            compose.onNodeWithTag("north-up").assertIsEnabled()
            assertSameScalePixels(first, scaleBounds)

            // Unknown visual authority clears both instruments even when query authority is valid.
            compose.runOnIdle { model.value = model.value.copy(scale = null, scaleViewport = null) }
            compose.onNodeWithTag("chart-scale-bar").assertDoesNotExist()
            compose.onNodeWithTag("north-up").assertIsEnabled()
            val cleared = capture()
            try {
                assertFalse("Upper scale pixels must clear", sameCrop(first, cleared,
                    scaleBounds.left.toInt(), scaleBounds.top.toInt(), scaleBounds.width.toInt(), scaleBounds.height.toInt()))
                assertFalse("Lower-right scale Canvas pixels must clear", sameCrop(first, cleared, 1898, 1120, 92, 50))
            } finally { cleared.recycle() }
        } finally { first.recycle() }
    }

    private fun assertSameScalePixels(first: Bitmap, upper: androidx.compose.ui.geometry.Rect) {
        compose.onNodeWithTag("chart-scale-label").assertTextEquals("0 · 500 m")
        assertEquals(upper, compose.onNodeWithTag("chart-scale-bar").fetchSemanticsNode().boundsInRoot)
        val current = capture()
        try {
            assertTrue("Upper scale chrome stays pixel-identical", sameCrop(first, current,
                upper.left.toInt(), upper.top.toInt(), upper.width.toInt(), upper.height.toInt()))
            assertTrue("Lower-right native Canvas scale stays pixel-identical", sameCrop(first, current, 1898, 1120, 92, 50))
        } finally { current.recycle() }
    }

    private fun capture(): Bitmap {
        compose.waitForIdle()
        val view = checkNotNull(activity).get().window.decorView
        assertEquals(2000, view.width); assertEquals(1200, view.height)
        val result = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnIdle { view.draw(Canvas(result)) }
        return result
    }

    private fun sameCrop(a: Bitmap, b: Bitmap, x: Int, y: Int, width: Int, height: Int): Boolean {
        assertTrue(x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= a.width && y + height <= a.height)
        val first = Bitmap.createBitmap(a, x, y, width, height)
        val second = Bitmap.createBitmap(b, x, y, width, height)
        return try { first.sameAs(second) } finally { first.recycle(); second.recycle() }
    }
}
