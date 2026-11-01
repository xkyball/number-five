package com.no5.helm.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.no5.helm.chart.*

/** Only an explicitly selected point owns a drag. Camera changes cancel its frame-bound transaction. */
internal class RouteHandleView(context: Context) : View(context) {
    data class Handle(val index: Int, val point: ScreenPoint, val frame: ChartFrame)
    var handle: Handle? = null
        set(value) { if (field != value) { cancelDrag(); field = value; invalidate() } }
    var blocked: List<Rect> = emptyList()
    var red = false
    var onDrop: (Handle, ScreenPoint) -> Unit = { _, _ -> }
    private var dragging = false
    private var position = ScreenPoint(0.0, 0.0)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var observedLifecycle: Lifecycle? = null
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) cancelDrag()
    }
    private fun cancelDrag() {
        if (dragging) { dragging = false; invalidate() }
        parent?.requestDisallowInterceptTouchEvent(false)
    }
    override fun onDraw(canvas: Canvas) {
        val current = handle ?: return
        val p = if (dragging) position else current.point
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f * resources.displayMetrics.density
        paint.color = if (red) 0xffef5555.toInt() else 0xff007096.toInt()
        canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 18f * resources.displayMetrics.density, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val current = handle ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (blocked.any { it.contains(androidx.compose.ui.geometry.Offset(event.x, event.y)) }) return false
            if (kotlin.math.hypot(event.x - current.point.x, event.y - current.point.y) > 24 * resources.displayMetrics.density) return false
            dragging = true; position = current.point; parent.requestDisallowInterceptTouchEvent(true); return true
        }
        if (!dragging) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> { position = ScreenPoint(event.x.toDouble(), event.y.toDouble()); invalidate() }
            MotionEvent.ACTION_UP -> { cancelDrag(); onDrop(current, ScreenPoint(event.x.toDouble(), event.y.toDouble())); performClick() }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelDrag()
        }
        return true
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { if (!hasWindowFocus) cancelDrag(); super.onWindowFocusChanged(hasWindowFocus) }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { if (visibility != VISIBLE) cancelDrag(); super.onVisibilityChanged(changedView, visibility) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); observedLifecycle = findViewTreeLifecycleOwner()?.lifecycle; observedLifecycle?.addObserver(lifecycleObserver) }
    override fun onDetachedFromWindow() { cancelDrag(); observedLifecycle?.removeObserver(lifecycleObserver); observedLifecycle = null; super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}
