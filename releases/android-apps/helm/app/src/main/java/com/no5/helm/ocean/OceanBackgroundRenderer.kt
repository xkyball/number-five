package com.no5.helm.ocean

import android.content.Context
import android.graphics.*
import android.util.Log
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.max
import kotlin.math.roundToInt

/** Caller owns stage transform and media-aperture clipping. No UI/source reads or writes here. */
class OceanBackgroundRenderer(private val context: Context, private val report: (String) -> Unit = {},
    private val shaderProgramOverride: String? = null) {
    private val target = RectF(400f, 0f, 1600f, 704f)
    private var attempted = false
    private var animated: AnimatedOcean? = null
    private var bitmap: Bitmap? = null
    private var imageAttempted = false
    private var animationFailed = false
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(400f, 0f, 1600f, 0f,
            intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, .08f, .92f, 1f), Shader.TileMode.CLAMP)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val neutral = Paint().apply { shader = LinearGradient(0f,0f,0f,704f,
        intArrayOf(Color.rgb(185,222,246),Color.rgb(60,128,173)),null,Shader.TileMode.CLAMP) }
    val shaderAvailable: Boolean get() = animated != null
    /** Run at renderer setup, never from Canvas.draw. Failed compilation is not retried. */
    fun prepare(mode: BackgroundMode) {
        if (mode == BackgroundMode.NONE) return
        if (!imageAttempted) {
            imageAttempted = true
            bitmap = runCatching { context.assets.open("helm/ocean.png").use { BitmapFactory.decodeStream(it) } }
                .onFailure { reportFailure("Image decode", it, "neutral gradient") }.getOrNull()
            bitmap?.let { b ->
                val scale = max(target.width()/b.width, target.height()/b.height)
                val w = target.width()/scale; val h = target.height()/scale
                source.set(((b.width-w)/2).roundToInt(),((b.height-h)/2).roundToInt(),((b.width+w)/2).roundToInt(),((b.height+h)/2).roundToInt())
            }
        }
        if (mode == BackgroundMode.ANIMATION && Build.VERSION.SDK_INT >= 33 && !attempted) {
            attempted = true
            animated = runCatching { Api33Ocean(shaderProgramOverride ?: context.assets.open("ocean/ocean.agsl").bufferedReader().use { it.readText() }) }
                .onFailure { animationFailed = true; reportFailure("Shader setup", it, "image fallback") }.getOrNull()
        }
    }
    private fun reportFailure(stage: String, failure: Throwable, fallback: String) {
        val detail = failure.message?.replace('\n', ' ')?.take(300) ?: "no exception detail"
        val message = "$stage failed · ${failure.javaClass.simpleName}: $detail · $fallback"
        Log.e("No5Ocean", message, failure)
        report(message)
    }
    fun effectiveLabel(mode: BackgroundMode, reducedMotion: Boolean, quality: OceanQuality, hardwareAccelerated: Boolean = true): String = when {
        mode != BackgroundMode.ANIMATION -> mode.label
        reducedMotion -> "Animation ausgewählt · Bild bei reduzierter Bewegung"
        !hardwareAccelerated -> "Animation ausgewählt · Bild bei Software-Rendering"
        animationFailed -> "Animation ausgewählt · Bild nach Shader-Fehler"
        quality == OceanQuality.STATIC -> "Animation ausgewählt · Bild wegen Gerätebelastung"
        Build.VERSION.SDK_INT < 33 -> "GLES-Animation · Bild-Rückfall ohne AGSL auf Android 10–12"
        !shaderAvailable -> "Animation ausgewählt · wird in HELM aktiviert"
        quality == OceanQuality.LOW -> "Animation · reduzierte Details und Bildrate"
        else -> "Animation · dekorative Landschaft"
    }
    fun draw(canvas: Canvas, mode: BackgroundMode, night: Boolean, reducedMotion: Boolean, frame: OceanFrame) {
        if (night || mode == BackgroundMode.NONE) return
        val renderer = animated
        if (mode == BackgroundMode.ANIMATION && !reducedMotion && frame.quality != OceanQuality.STATIC && renderer != null && canvas.isHardwareAccelerated) {
            canvas.save(); canvas.translate(target.left, target.top)
            try { renderer.draw(canvas, frame) } catch (failure: RuntimeException) {
                animated = null // terminal failure for this renderer; never recompile in the hot path.
                animationFailed = true
                reportFailure("Shader draw", failure, "image fallback")
            } finally { canvas.restore() }
            if (animated != null) return
        }
        val layer = canvas.saveLayer(target, null)
        val image = bitmap
        if (image == null) canvas.drawRect(target, neutral) else canvas.drawBitmap(image, source, target, imagePaint)
        canvas.drawRect(target, mask); canvas.restoreToCount(layer)
    }
}
private interface AnimatedOcean { fun draw(canvas: Canvas, frame: OceanFrame) }
@RequiresApi(33)
private class Api33Ocean(program: String) : AnimatedOcean {
    private val shader = RuntimeShader(program)
    private val paint = Paint().apply { shader = this@Api33Ocean.shader }
    // Reused scalar array reaches the same native validator on API33+ and host Skia.
    // Convenience scalar overloads are no-ops in Robolectric4.15.1; never allocate per frame.
    private val scalar = FloatArray(1)
    private fun uniform(name: String, value: Float) {
        scalar[0] = value
        try { shader.setFloatUniform(name, scalar) }
        catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Uniform $name: ${failure.message}", failure)
        }
    }
    init {
        shader.setFloatUniform("uResolution", floatArrayOf(1200f,704f))
        uniform("uHorizontalFovRadians", 1.35f)
        uniform("uHorizonY", .25f)
        shader.setFloatUniform("uLightDirection", floatArrayOf(-.35f,.82f,-.45f))
        shader.setColorUniform("uWaterColor", Color.rgb(24,113,169))
        shader.setColorUniform("uLightColor", Color.rgb(184,216,233))
        shader.setColorUniform("uMountainNearColor", Color.rgb(53,105,108))
        shader.setColorUniform("uMountainFarColor", Color.rgb(85,145,184))
    }
    override fun draw(canvas: Canvas, frame: OceanFrame) {
        uniform("uTimeSeconds", frame.timeSeconds)
        uniform("uHeadingRadians", frame.headingRadians)
        uniform("uWaveHeight", frame.waveHeight)
        uniform("uFoamIntensity", if (frame.quality == OceanQuality.HIGH) .15f else 0f)
        uniform("uDetail", if (frame.quality == OceanQuality.HIGH) 1f else 0f)
        canvas.drawRect(0f,0f,1200f,704f,paint)
    }
}
