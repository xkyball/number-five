package com.no5.helm.ui

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.no5.helm.chart.ChartFrame
import com.no5.helm.chart.ChartSurfaceGeometry
import com.no5.helm.chart.ChartViewport
import com.no5.helm.chart.ScreenPoint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RouteHandleViewLifecycleTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    @Test fun cancellingInteractionNeverDropsTheSelectedRoutePoint() {
        val activity = Robolectric.buildActivity(No5ImmersiveActivity::class.java)
        // Match MainActivity's manifest theme before the immersive window is created.
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup().visible().windowFocusChanged(true).use { host ->
            val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
            val root = FrameLayout(host.get()).apply { setViewTreeLifecycleOwner(owner) }
            val view = RouteHandleView(host.get())
            root.addView(view, FrameLayout.LayoutParams(1000, 600))
            host.get().setContentView(root)
            root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1000, 600)
            val camera = ChartViewport(42.36, -71.04, .15, width = 1000, height = 600)
            val handle = RouteHandleView.Handle(2, ScreenPoint(400.0, 300.0),
                ChartFrame(1, "synthetic-route-touch", ChartSurfaceGeometry.fit(1000, 600), camera))
            view.handle = handle
            var drops = 0
            view.onDrop = { _, _ -> drops++ }
            var time = 1000L
            fun send(action: Int, x: Float = 400f): Boolean {
                val event = MotionEvent.obtain(1000, time.also { time += 16 }, action, x, 300f, 0)
                return try { view.onTouchEvent(event) } finally { event.recycle() }
            }
            val cancellations: List<() -> Unit> = listOf(
                { send(MotionEvent.ACTION_CANCEL) },
                { view.onWindowFocusChanged(false) },
                { view.visibility = View.INVISIBLE; view.visibility = View.VISIBLE },
                { owner.registry.currentState = Lifecycle.State.STARTED; owner.registry.currentState = Lifecycle.State.RESUMED },
                { root.removeView(view); root.addView(view, FrameLayout.LayoutParams(1000, 600)) },
                { view.handle = handle.copy(frame = handle.frame.copy(revision = 2)); view.handle = handle },
            )
            cancellations.forEachIndexed { index, cancel ->
                assertTrue("fixture must begin a drag: $index", send(MotionEvent.ACTION_DOWN))
                send(MotionEvent.ACTION_MOVE, 410f); cancel()
                assertFalse("old release must not commit after cancellation: $index", send(MotionEvent.ACTION_UP, 410f))
                assertEquals(0, drops); assertSame(handle, view.handle)
            }
            assertTrue(send(MotionEvent.ACTION_DOWN)); send(MotionEvent.ACTION_MOVE, 415f); send(MotionEvent.ACTION_UP, 415f)
            assertEquals(1, drops); assertSame(handle, view.handle)
        }
    }
}
