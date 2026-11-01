package com.no5.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max

/** Static native background from the supplied photograph; no animation or separate hit surface. */
internal class HomePhotoBackground(private val photo: Bitmap, private val night: Boolean) : Drawable() {
    // The inspected 2556x1179 source has black side mattes. Keep the resource byte-identical;
    // crop only during drawing, preserving the photographed scene's aspect ratio.
    private val source = Rect(230, 0, 2326, photo.height)
    private val destination = RectF()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(if (night) ColorMatrix(floatArrayOf(
            .02126f, .07152f, .00722f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )) else ColorMatrix().apply { setSaturation(.28f) })
    }
    private val veil = Paint()
    private val edges = Paint()

    override fun onBoundsChange(bounds: Rect) {
        val scale = max(bounds.width().toFloat() / source.width(), bounds.height().toFloat() / source.height())
        val width = source.width() * scale
        val height = source.height() * scale
        destination.set(bounds.exactCenterX() - width / 2, bounds.exactCenterY() - height / 2,
            bounds.exactCenterX() + width / 2, bounds.exactCenterY() + height / 2)
        val ink = if (night) Color.BLACK else Color.rgb(9, 32, 42)
        fun alpha(value: Int) = (ink and 0xffffff) or (value shl 24)
        veil.shader = LinearGradient(bounds.left.toFloat(), 0f, bounds.right.toFloat(), 0f,
            intArrayOf(alpha(if (night) 120 else 170), alpha(if (night) 35 else 70)),
            null, Shader.TileMode.CLAMP)
        edges.shader = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
            intArrayOf(alpha(190), alpha(0), alpha(0), alpha(180)),
            floatArrayOf(0f, .25f, .73f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(bounds)
        canvas.drawColor(if (night) Color.BLACK else Color.rgb(9, 32, 42))
        canvas.drawBitmap(photo, source, destination, imagePaint)
        canvas.drawRect(bounds, veil)
        canvas.drawRect(bounds, edges)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { imagePaint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { /* Theme filter is intrinsic. */ }
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.OPAQUE
}
