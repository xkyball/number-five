package com.no5.helm.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.no5.helm.chart.ScreenPoint

/** Optional public-test target; sibling of the texture, so its camera matrix cannot move this ring. */
internal class RendererDiagnosticMarkerView(context: Context) : View(context) {
    var point: ScreenPoint? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 3f }
    init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) { point?.let { canvas.drawCircle(it.x.toFloat(), it.y.toFloat(), 25f, paint) } }
}
