package com.no5.header.artwork

import android.content.res.AssetManager
import android.graphics.*
import kotlin.math.max
import kotlin.math.roundToInt

/** Shared static MAP sky crop. Decorative artwork, independent of weather and body media settings. */
class No5HeaderBackdrop(private val assets: AssetManager) {
    private var ocean: Bitmap? = null
    private val target = RectF(400f, 0f, 1600f, 704f)
    private val source = Rect()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(400f, 0f, 1600f, 0f,
            intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, .08f, .92f, 1f), Shader.TileMode.CLAMP)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val seam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val aperture = Path().apply {
        moveTo(0f, 110f); cubicTo(105f, 117f, 190f, 114f, 287f, 124f)
        cubicTo(351f, 129f, 399f, 132f, 436f, 141f); cubicTo(467f, 149f, 478f, 177f, 490f, 214f)
        cubicTo(514f, 288f, 535f, 397f, 563f, 486f); cubicTo(583f, 552f, 599f, 621f, 621f, 662f)
        cubicTo(637f, 692f, 656f, 703f, 695f, 704f); lineTo(695f, 704f)
        cubicTo(858f, 705f, 1143f, 705f, 1305f, 704f); cubicTo(1344f, 703f, 1363f, 692f, 1379f, 662f)
        cubicTo(1401f, 621f, 1417f, 552f, 1437f, 486f); cubicTo(1465f, 397f, 1486f, 288f, 1510f, 214f)
        cubicTo(1522f, 179f, 1535f, 149f, 1564f, 141f); cubicTo(1598f, 133f, 1644f, 130f, 1713f, 124f)
        cubicTo(1814f, 115f, 1888f, 116f, 2000f, 110f); lineTo(2000f, 0f); lineTo(0f, 0f); close()
    }
    private val port = Path().apply {
        moveTo(0f, 110f); cubicTo(105f, 117f, 190f, 114f, 287f, 124f)
        cubicTo(351f, 129f, 399f, 132f, 436f, 141f)
    }
    private val starboard = Path().apply {
        moveTo(2000f, 110f); cubicTo(1888f, 116f, 1814f, 115f, 1713f, 124f)
        cubicTo(1644f, 130f, 1598f, 133f, 1564f, 141f)
    }

    /** Draws only the existing 2000 × 130 header; the host retains viewport and touch ownership. */
    fun draw(canvas: Canvas, night: Boolean) {
        drawBackground(canvas, night)
        drawOutline(canvas, night)
    }

    /** Opaque, telemetry-independent sky. Hosts may retain this small surface. */
    fun drawBackground(canvas: Canvas, night: Boolean) {
        val saved = canvas.save()
        canvas.clipRect(0f, 0f, 2000f, 130f)
        canvas.drawColor(if (night) Color.BLACK else Color.parseColor("#f6f9fa"))
        if (!night) {
            val sky = canvas.save()
            canvas.clipRect(0f, 0f, 2000f, 130f)
            canvas.clipPath(aperture)
            canvas.drawColor(Color.parseColor("#b9def6"))
            val bitmap = ocean ?: assets.open("no5-header/ocean.png").use {
                checkNotNull(BitmapFactory.decodeStream(it)) { "Missing original No5 header artwork" }
            }.also {
                ocean = it
                val scale = max(target.width() / it.width, target.height() / it.height)
                val width = target.width() / scale; val height = target.height() / scale
                source.set(((it.width - width) / 2).roundToInt(), ((it.height - height) / 2).roundToInt(),
                    ((it.width + width) / 2).roundToInt(), ((it.height + height) / 2).roundToInt())
            }
            val layer = canvas.saveLayer(target, null)
            canvas.drawBitmap(bitmap, source, target, imagePaint)
            canvas.drawRect(target, mask)
            canvas.restoreToCount(layer)
            canvas.restoreToCount(sky)
        }
        canvas.restoreToCount(saved)
    }

    /** Keep stroke rasterization in the final target, rather than a cached texture. */
    fun drawOutline(canvas: Canvas, night: Boolean) {
        val saved = canvas.save()
        canvas.clipRect(0f, 0f, 2000f, 130f)
        seam.color = Color.parseColor(if (night) "#700000" else "#526b82")
        val shoulder = canvas.save()
        canvas.clipRect(0f, 0f, 2000f, 130f)
        canvas.drawPath(port, seam); canvas.drawPath(starboard, seam)
        canvas.restoreToCount(shoulder)
        seam.strokeWidth = 1.5f
        canvas.drawLine(251f, 18f, 251f, 85f, seam)
        seam.strokeWidth = 2.5f
        canvas.restoreToCount(saved)
    }

    fun close() { ocean?.recycle(); ocean = null }
}
