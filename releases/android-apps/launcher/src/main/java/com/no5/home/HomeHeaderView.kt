package com.no5.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.no5.header.artwork.No5HeaderBackdrop

/** Same native brand and sky as HELM/MAP, with HOME's own context. */
internal class HomeHeaderView(context: Context, private val night: Boolean,
    private val openHome: () -> Unit) : View(context) {
    private val backdrop = No5HeaderBackdrop(context.assets)
    private val hero = resources.getFont(R.font.barlow_condensed_extrabold)
    private val label = resources.getFont(R.font.barlow_condensed_semibold)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var armed = false
    private var ownsStream = false
    private var pointerId = -1

    init { contentDescription = com.no5.ui.No5Text.text(context, "No5 HOME öffnen"); isClickable = true; isFocusable = true }

    override fun onDraw(canvas: Canvas) {
        backdrop.draw(canvas, night)
        paint.style = Paint.Style.FILL
        fun text(value: String, x: Float, y: Float, size: Float, stretch: Float = 1f, brand: Boolean = false) {
            paint.typeface = if (brand) hero else label; paint.textSize = size
            paint.color = Color.parseColor(if (night) { if (brand) "#d40000" else "#b00000" }
                else { if (brand) "#071022" else "#122653" })
            canvas.save(); canvas.translate(x, y); canvas.scale(stretch, 1f)
            canvas.drawText(value, 0f, 0f, paint); canvas.restore()
        }
        text("NO 5", 35f, 62f, 64f, 1.7f, true)
        text("STELLENBOSCH", 38f, 85f, 22f, 1.28f)
    }

    private fun onBrand(event: MotionEvent) = event.x >= 24f && event.x < 262f && event.y >= 4f && event.y < 108f
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // The sky spans the header, but the existing HOME action owns only its logo ROI.
        val logo = RectF(24f, 4f, 262f, 108f)
        val parentBounds = RectF(logo)
        matrix.mapRect(parentBounds)
        parentBounds.offset(left.toFloat(), top.toFloat())
        info.setBoundsInParent(Rect().also(parentBounds::roundOut))
        val screenBounds = RectF(logo)
        val global = Matrix()
        transformMatrixToGlobal(global)
        global.mapRect(screenBounds)
        info.setBoundsInScreen(Rect().also(screenBounds::roundOut))
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || visibility != VISIBLE) { armed = false; ownsStream = false; return false }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ownsStream = event.pointerCount == 1 && onBrand(event)
                armed = ownsStream; pointerId = if (ownsStream) event.getPointerId(0) else -1
                ownsStream
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1 || event.findPointerIndex(pointerId) != 0 || !onBrand(event)) armed = false
                ownsStream
            }
            MotionEvent.ACTION_UP -> {
                val owned = ownsStream
                val click = armed && event.pointerCount == 1 && event.getPointerId(0) == pointerId && onBrand(event)
                armed = false; ownsStream = false
                if (click) performClick()
                owned
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> ownsStream.also { armed = false }
            MotionEvent.ACTION_CANCEL -> ownsStream.also { armed = false; ownsStream = false }
            else -> ownsStream
        }
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) { armed = false; ownsStream = false; pointerId = -1 }
        super.onWindowFocusChanged(hasWindowFocus)
    }
    override fun performClick(): Boolean { super.performClick(); openHome(); return true }
    override fun onDetachedFromWindow() { armed = false; ownsStream = false; pointerId = -1; backdrop.close(); super.onDetachedFromWindow() }
}
