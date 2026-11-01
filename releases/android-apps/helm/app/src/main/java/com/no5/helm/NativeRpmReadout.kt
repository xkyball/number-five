package com.no5.helm

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

/** Font measurement is performed once per size, never once per telemetry value. */
class NativeRpmReadout(typeface: Typeface) {
    enum class Size(val pixels: Float, val scaleX: Float, val width: Float) {
        ENGINE(100f, 1.3397642f, 281.35f), HEADER(44f, 1.16f, 139.2f)
    }
    private class Style(typeface: Typeface, size: Size) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = size.pixels
            textScaleX = size.scaleX
        }
        val markerPaint = Paint(paint).apply { textSize = size.pixels * .28f; textAlign = Paint.Align.CENTER }
        val layout = TabularRpmLayout.Font(("0123456789>—").associateWith { character ->
            val value = character.toString()
            val bounds = Rect()
            paint.getTextBounds(value, 0, value.length, bounds)
            TabularRpmLayout.Glyph(paint.measureText(value), bounds.left.toFloat(), bounds.right.toFloat())
        }, size.width)
    }
    private val styles = Size.entries.associateWith { Style(typeface, it) }

    fun layout(raw: String, x: Float, baseline: Float, anchor: TabularRpmLayout.Anchor,
        size: Size = Size.ENGINE): TabularRpmLayout.Run = styles.getValue(size).layout.place(raw, x, baseline, anchor)

    fun draw(canvas: Canvas, raw: String, x: Float, baseline: Float, color: Int,
        anchor: TabularRpmLayout.Anchor, size: Size = Size.ENGINE) {
        val style = styles.getValue(size)
        val run = style.layout.place(raw, x, baseline, anchor)
        style.paint.color = color
        run.cells.forEach { cell ->
            canvas.save()
            canvas.translate(cell.origin, run.baseline)
            canvas.scale(run.glyphScale, run.glyphScale)
            canvas.drawText(cell.character.toString(), 0f, 0f, style.paint)
            canvas.restore()
        }
        if (run.stale) {
            style.markerPaint.color = color
            canvas.drawText("*", run.markerCenter, baseline - size.pixels * .45f, style.markerPaint)
        }
    }
}
