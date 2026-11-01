package com.no5.preparation.ui

import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import com.no5.ui.no5Clickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.min

/**
 * Actual HELM drawHeaderFallback + shipped header nodes at xkyball/no5 Build28.
 * No Plotter HTML, invented logo, clock, temperature or connected-state value.
 */
@Composable internal fun No5Header(state: ShellState, onEvent: (ShellEvent) -> Unit,
    mediaHeaderSlot: @Composable (Modifier, Boolean) -> Unit = { _, _ -> },
    headerControlsSlot: (@Composable (Modifier, Boolean, (Boolean) -> Unit) -> Unit)? = null) {
    val context = LocalContext.current
    val assets = context.assets
    val resources = LocalResources.current
    val artwork = remember(assets, resources, state.redNight) { HelmHeaderArtwork(context, assets, resources, state.redNight) }
    val sourceLabel = if (state.simulated) com.no5.ui.No5Text.text(context, "LOKALE SIMULATION") else state.sourceLabel
    val sourceDetail = state.sourceStatus.detail
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // HELM's whole-stage width transform; the source strip is 2000 × 112px.
        val unit = maxWidth / 2000f
        val headerHeight = unit * 130f
        Box(Modifier.fillMaxWidth().height(headerHeight).testTag("header")) {
            Canvas(Modifier.fillMaxSize()) {
                val canvas = drawContext.canvas.nativeCanvas
                canvas.save()
                canvas.scale(size.width / 2000f, size.width / 2000f)
                artwork.draw(canvas, sourceLabel, sourceDetail, headerControlsSlot == null)
                canvas.restore()
            }
            // AndroidView exports its whole interop bounds as a merging semantics node.
            // Keep this transparent full-width container below the actual header targets.
            if (headerControlsSlot != null) headerControlsSlot(Modifier.fillMaxWidth().height(headerHeight), state.redNight) {
                onEvent(ShellEvent.SetNight(it))
            } else {
                // Isolated shell consumers retain their existing theme controls. Product MAP
                // always injects the shared native build/WLAN controls from its Activity.
                Box(Modifier.offset(x=unit*1694f).width(unit*34f).fillMaxHeight()
                    .testTag("header-connection-mark").semantics {
                        contentDescription=com.no5.ui.No5Text.text(context,"WLAN unbekannt")
                    })
                listOf(false,true).forEach { night ->
                    Box(Modifier.offset(x=unit*if(night)1852f else 1741f)
                        .width(maxOf(unit*111f,48.dp)).height(unit*96f)
                        .testTag(if(night)"palette-toggle" else "palette-day")
                        .semantics { contentDescription=com.no5.ui.No5Text.text(context,if(night)"Nachtmodus" else "Tagmodus"); selected=state.redNight==night }
                        .no5Clickable(role=Role.Button) {onEvent(ShellEvent.SetNight(night))})
                }
            }
            mediaHeaderSlot(Modifier.offset(x = unit * 282f, y = unit * 4f)
                .width(unit * 1050f).height(unit * 104f).testTag("header-media"), state.redNight)
            Box(Modifier.offset(x = unit * 24f).width(unit * 238f).fillMaxHeight()
                .testTag("header-brand").semantics {
                    contentDescription = com.no5.ui.No5Text.text(context, "No5 HOME öffnen")
                }.no5Clickable(role = Role.Button, onClickLabel = com.no5.ui.No5Text.text(context, "No5 HOME öffnen")) { onEvent(ShellEvent.OpenHome) })
            Box(Modifier.offset(x = unit * 1392f).width(unit * 236f).fillMaxHeight()
                .testTag("source-mode").semantics { contentDescription = "$sourceLabel · $sourceDetail" }.no5Clickable(role=Role.Button){onEvent(ShellEvent.OpenControl)})

        }
    }
}

/** Static HELM IMAGE preference. Original pixels and crop/mask; no Ocean/GL renderer is copied. */
private class HelmHeaderArtwork(private val context: android.content.Context, assets: AssetManager, resources: Resources, private val night: Boolean) {
    private val hero = resources.getFont(R.font.barlow_condensed_extrabold)
    private val label = resources.getFont(R.font.barlow_condensed_semibold)
    private val ink = AndroidColor.parseColor(if (night) "#d40000" else "#071022")
    private val secondary = AndroidColor.parseColor(if (night) "#b00000" else "#122653")
    private val background = AndroidColor.parseColor(if (night) "#000000" else "#f6f9fa")
    private data class Mark(val path: Path, val transform: Matrix, val paint: Paint)
    private val marks = mutableListOf<Mark>()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (night) AndroidColor.parseColor("#700000") else ink
    }
    private val backdrop = com.no5.header.artwork.No5HeaderBackdrop(assets)

    init {
        val root = assets.open("helm-header/scene_${if (night) "night" else "day"}.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        val nodes = root.getJSONArray("nodes")
        repeat(nodes.length()) { collect(nodes.getJSONObject(it), Matrix()) }
    }

    private fun collect(node: JSONObject, parent: Matrix) {
        val attrs = node.getJSONObject("attrs")
        // Contours come from the shared backdrop. The old fixed sun has no weather source
        // and occupies the media slot; do not paint it through the transparent title view.
        if (attrs.optString("id") in setOf("port-outer-shoulder", "stbd-outer-shoulder", "brand-divider", "header-sun", "header-wifi-icon")) return
        val style = node.getJSONObject("style")
        val transform = Matrix(parent)
        val translation = Regex("translate\\(([-\\d.]+)\\s+([-\\d.]+)\\)").find(attrs.optString("transform"))
        translation?.let { transform.preTranslate(it.groupValues[1].toFloat(), it.groupValues[2].toFloat()) }
        fun f(key: String, default: Float = 0f) = attrs.optString(key).toFloatOrNull() ?: default
        val path = when (node.getString("tag")) {
            "path" -> PathParser().parsePathString(attrs.getString("d")).toPath().asAndroidPath()
            "circle" -> Path().apply { addCircle(f("cx"), f("cy"), f("r"), Path.Direction.CW) }
            "rect" -> Path().apply {
                addRoundRect(RectF(f("x"), f("y"), f("x") + f("width"), f("y") + f("height")),
                    f("rx"), f("ry", f("rx")), Path.Direction.CW)
            }
            else -> null
        }
        path?.let { p ->
            for (kind in listOf("fill", "stroke")) {
                val color = style.optString(kind, "none")
                if (color != "none") marks += Mark(p, Matrix(transform), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = AndroidColor.parseColor(color)
                    this.style = if (kind == "fill") Paint.Style.FILL else Paint.Style.STROKE
                    strokeWidth = style.optString("stroke-width", "1").toFloat()
                    strokeCap = when (style.optString("stroke-linecap")) {
                        "round" -> Paint.Cap.ROUND; "square" -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT
                    }
                    strokeJoin = when (style.optString("stroke-linejoin")) {
                        "round" -> Paint.Join.ROUND; "bevel" -> Paint.Join.BEVEL; else -> Paint.Join.MITER
                    }
                })
            }
        }
        val children = node.optJSONArray("children")
        if (children != null) repeat(children.length()) { collect(children.getJSONObject(it), transform) }
    }

    fun draw(c: AndroidCanvas, sourceLabel: String, sourceDetail: String, standaloneTheme: Boolean) {
        c.save()
        backdrop.draw(c, night)
        c.clipRect(0f, 0f, 2000f, 112f)
        marks.forEach { mark ->
            c.save(); c.concat(mark.transform); c.drawPath(mark.path, mark.paint); c.restore()
        }
        text(c, "NO 5", 35f, 62f, 64f, hero, ink, scaleX = 1.7f)
        text(c, "STELLENBOSCH", 38f, 85f, 22f, label, secondary, scaleX = 1.28f)
        text(c, sourceLabel, 1392f, 50f, 21f, label, secondary, maxWidth = 236f)
        text(c, sourceDetail, 1392f, 75f, 14f, label, secondary, maxWidth = 236f)
        if (standaloneTheme) {
            c.drawRoundRect(RectF(if(night)1852f else 1741f,19f,if(night)1963f else 1852f,70f),25.5f,25.5f,activePaint)
            text(c,com.no5.ui.No5Text.text(context,"Tag"),1796.5f,54f,22f,label,if(night)secondary else background,center=true)
            text(c,com.no5.ui.No5Text.text(context,"Nacht"),1907.5f,54f,22f,label,if(night)AndroidColor.parseColor("#ff1a0a") else secondary,center=true)
        }
        c.restore()
    }

    /** Exact nonnumeric HELM text fitting: horizontal transform, unchanged baseline/height. */
    private fun text(c: AndroidCanvas, value: String, x: Float, y: Float, size: Float,
        font: Typeface, color: Int, center: Boolean = false, scaleX: Float = 1f, maxWidth: Float = Float.MAX_VALUE) {
        textPaint.textSize = size; textPaint.typeface = font; textPaint.color = color
        textPaint.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        val width = textPaint.measureText(value)
        val fit = if (width > 0) min(scaleX, maxWidth / width) else scaleX
        c.save(); c.translate(x, y); c.scale(fit.coerceAtLeast(.0001f), 1f)
        c.drawText(value, 0f, 0f, textPaint); c.restore()
    }
}
