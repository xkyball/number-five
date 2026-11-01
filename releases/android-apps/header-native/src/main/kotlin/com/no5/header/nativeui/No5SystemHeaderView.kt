package com.no5.header.nativeui

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import com.no5.header.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Collections
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Native No5 header only. The host owns authentication, foreground scope, expiry, root actions
 * and overlay lifetime. Nothing here starts an overlay, opens an app or sends an Android key.
 * All mutating calls and callbacks run on the Android main thread.
 */
class No5SystemHeaderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = 0) : View(context, attrs, defStyleAttr) {

    private val hero = Typeface.createFromAsset(context.assets, "no5-header/barlow_condensed_extrabold.ttf")
    private val label = Typeface.createFromAsset(context.assets, "no5-header/barlow_condensed_semibold.ttf")
    // A service may own an unattached base header before it ever displays an RPM field.
    // Measure real glyphs only when that field is actually drawn; the original strict metrics
    // checks still run then. This also keeps LegacyGraphics lifecycle fixtures from claiming
    // native font metrics merely by constructing an otherwise passive View.
    private val rpm by lazy(LazyThreadSafetyMode.NONE) { NativeRpmReadout(hero) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backdrop = com.no5.header.artwork.No5HeaderBackdrop(context.assets)

    private data class TouchScope(val packageName: String?, val producerSession: String?,
        val foregroundEpoch: Long?, val page: HeaderPage?, val pageGeneration: Long?,
        val sourceSessionId: String?, val profile: HeaderProfile, val theme: HeaderTheme)
    private var touchScope: TouchScope? = null
    private var pointerId = -1
    private var armed: HeaderNavigationRegion? = null
    private var ownsStream = false
    private var closed = false

    var onSystemNavigationAction: ((SystemNavigationAction) -> Unit)? = null
        set(value) { mainThread(); cancelGesture(); field = value }

    /** Lists and snapshots in this inspection model are immutable; not a second IPC contract. */
    var renderModel: No5HeaderRenderModel = buildModel(
        HeaderConsumerState(null, HeaderClearReason.NO_PUBLICATION), HeaderTheme.RED_NIGHT, 0)
        private set

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = false // Three separately described virtual navigation buttons.
        contentDescription = "NO 5 · STELLENBOSCH"
    }

    /** Registry.current() supplies accepted/liveness-checked state. Render does not renew its lease. */
    @JvmOverloads fun render(state: HeaderConsumerState, baseTheme: HeaderTheme = HeaderTheme.DAY,
        nowEpochMs: Long = System.currentTimeMillis()) {
        mainThread()
        check(!closed) { "Header view is closed" }
        val next = buildModel(state, baseTheme, nowEpochMs)
        val p = next.snapshot?.publication
        val scope = TouchScope(p?.packageName, p?.producerSession, p?.foregroundEpoch,
            next.snapshot?.pageId, p?.pageGeneration, p?.sourceSessionId, next.profile, next.theme)
        if (scope != touchScope) cancelGesture()
        touchScope = scope
        renderModel = next
        invalidate()
    }

    /** Optional explicit resource disposal after the host removes its own header surface. */
    fun close() {
        mainThread()
        cancelGesture()
        onSystemNavigationAction = null
        backdrop.close()
        closed = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(No5HeaderGeometry.WIDTH.toInt(), widthMeasureSpec)
        val h = ceil(No5HeaderGeometry.HEIGHT * No5HeaderGeometry.scale(w)).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) cancelGesture()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelGesture()
    }

    override fun onDetachedFromWindow() {
        cancelGesture()
        super.onDetachedFromWindow()
    }

    override fun setEnabled(enabled: Boolean) {
        if (!enabled) cancelGesture()
        super.setEnabled(enabled)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        if (visibility != VISIBLE) cancelGesture()
        super.onVisibilityChanged(changedView, visibility)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val model = renderModel
        val palette = palette(model)
        canvas.drawColor(palette.background)
        if (closed || width <= 0) return
        val saved = canvas.save()
        val scale = No5HeaderGeometry.scale(width)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, No5HeaderGeometry.WIDTH, No5HeaderGeometry.HEIGHT)
        drawStructure(canvas, model, palette)
        model.texts.forEach { drawText(canvas, it, palette) }
        drawNavigation(canvas, palette)
        canvas.restoreToCount(saved)
    }

    private data class Palette(val background: Int, val ink: Int, val secondary: Int,
        val structural: Int, val accent: Int)

    private fun palette(m: No5HeaderRenderModel): Palette {
        if (m.theme == HeaderTheme.RED_NIGHT) return Palette(
            Color.parseColor(if (m.profile == HeaderProfile.HOME_CONTEXT) "#090000" else "#000000"),
            Color.parseColor("#d40000"), Color.parseColor("#b00000"), Color.parseColor("#700000"),
            Color.parseColor(if (m.profile == HeaderProfile.HOME_CONTEXT) "#d40000" else "#ff1a0a"))
        return Palette(Color.parseColor("#f6f9fa"), Color.parseColor("#071022"),
            Color.parseColor("#122653"), Color.parseColor("#526b82"), Color.parseColor("#005daf"))
    }

    private fun drawStructure(c: Canvas, m: No5HeaderRenderModel, colors: Palette) {
        backdrop.draw(c, m.theme == HeaderTheme.RED_NIGHT)
        paint.shader = null; paint.xfermode = null; paint.style = Paint.Style.STROKE
        if (m.profile == HeaderProfile.MARINE_SAFETY) {
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor(if (m.theme == HeaderTheme.RED_NIGHT) "#d40000" else "#D62A20")
            c.drawRect(540f, 13f, 545f, 29f, paint)
            paint.color = Color.parseColor(if (m.theme == HeaderTheme.RED_NIGHT) "#d40000" else "#008E4F")
            c.drawRect(780f, 13f, 785f, 29f, paint)
        }
    }

    private fun drawText(c: Canvas, text: HeaderDrawText, colors: Palette) {
        val color = if (text.ink == HeaderInk.PRIMARY) colors.ink else colors.secondary
        if (text.renderer == HeaderTextRenderer.RPM) {
            rpm.draw(c, text.value, text.x, text.baseline, color, TabularRpmLayout.Anchor.RIGHT, NativeRpmReadout.Size.HEADER)
            return
        }
        paint.reset(); paint.isAntiAlias = true
        paint.typeface = if (text.font == HeaderFont.HERO) hero else label
        paint.textSize = text.size; paint.color = color
        paint.textAlign = when (text.align) {
            HeaderTextAlign.LEFT -> Paint.Align.LEFT; HeaderTextAlign.CENTER -> Paint.Align.CENTER; HeaderTextAlign.RIGHT -> Paint.Align.RIGHT
        }
        if (text.renderer == HeaderTextRenderer.SPEED) {
            paint.textScaleX = text.scaleX
            NativeNumericText.draw(c, text.value, text.x, text.baseline, paint, NumericFormats.speed, text.maxWidth)
        } else {
            val w = paint.measureText(text.value)
            val fit = if (w > 0) min(text.scaleX, text.maxWidth / w) else text.scaleX
            c.save(); c.translate(text.x, text.baseline); c.scale(fit.coerceAtLeast(.0001f), 1f)
            c.drawText(text.value, 0f, 0f, paint); c.restore()
        }
    }

    /** Reuses the former theme pill. These controls are navigation, never a second HOME button. */
    private fun drawNavigation(c: Canvas, colors: Palette) {
        paint.reset(); paint.isAntiAlias = true; paint.color = colors.structural
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
        c.drawRoundRect(RectF(1741f, 19f, 1963f, 70f), 25.5f, 25.5f, paint)
        c.drawLine(1852f, 20f, 1852f, 69f, paint)
        paint.color = colors.ink; paint.strokeWidth = 3f; paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        c.drawPath(Path().apply { moveTo(1807f, 31f); lineTo(1787f, 44.5f); lineTo(1807f, 58f)
            moveTo(1787f, 44.5f); lineTo(1813f, 44.5f) }, paint)
        c.drawRoundRect(RectF(1892f, 34f, 1916f, 57f), 1f, 1f, paint)
        c.drawPath(Path().apply { moveTo(1898f, 28f); lineTo(1922f, 28f); lineTo(1922f, 51f) }, paint)
        drawText(c, HeaderDrawText("navigation-back", "Zurück", 1796.5f, 99f, 18f,
            align = HeaderTextAlign.CENTER, maxWidth = 101f), colors)
        drawText(c, HeaderDrawText("navigation-recents", "Übersicht", 1907.5f, 99f, 18f,
            align = HeaderTextAlign.CENTER, maxWidth = 101f), colors)
    }

    private fun buildModel(state: HeaderConsumerState, baseTheme: HeaderTheme, now: Long): No5HeaderRenderModel {
        val s = state.snapshot?.at(now)
        val profile = s?.profile ?: HeaderProfile.BASE_SYSTEM
        val theme = s?.theme ?: baseTheme
        val texts = ArrayList<HeaderDrawText>()
        val fields = ArrayList<HeaderFieldReadout>()
        fun text(id: String, value: String?, x: Float, y: Float, size: Float, font: HeaderFont = HeaderFont.LABEL,
            ink: HeaderInk = HeaderInk.SECONDARY, stretch: Float = 1f, maxWidth: Float = Float.MAX_VALUE,
            align: HeaderTextAlign = HeaderTextAlign.LEFT, renderer: HeaderTextRenderer = HeaderTextRenderer.TEXT) {
            texts += HeaderDrawText(id, value?.takeIf { it.isNotBlank() } ?: "—", x, y, size, font, ink,
                stretch, maxWidth, align, renderer)
        }
        text("brand", "NO 5", 35f, 62f, 64f, HeaderFont.HERO, HeaderInk.PRIMARY, 1.7f)
        text("brand-subtitle", "STELLENBOSCH", 38f, 85f, 22f, stretch = 1.28f)
        if (profile == HeaderProfile.HOME_CONTEXT) {
            text("context-title", s?.context?.title, 282f, 46f, 23f, stretch = 1.12f, maxWidth = 1050f)
            text("context-subtitle", s?.context?.subtitle, 282f, 69f, 23f, stretch = 1.12f, maxWidth = 1050f)
        } else {
            when (profile) {
                HeaderProfile.BRAND_SOURCE -> {
                    text("context-title", s?.context?.title, 282f, 46f, 23f, stretch = 1.12f, maxWidth = 480f)
                    text("context-subtitle", s?.context?.subtitle, 282f, 69f, 23f, stretch = 1.12f, maxWidth = 480f)
                    text("source-label", s?.source?.label, 1392f, 50f, 21f, maxWidth = 236f)
                    text("source-detail", sourceDetail(s), 1392f, 75f, 14f, maxWidth = 236f)
                }
                HeaderProfile.MARINE_SAFETY -> {
                    text("context-title", s?.context?.title, 282f, 58f, 32f, stretch = 1.12f, maxWidth = 228f)
                    fun safety(id: String, x: Float, title: String, m: HeaderMeasurement?, marker: Boolean = false) {
                        val shown = measurementText(m)
                        val speed = id == "sog"
                        val displayed = if (speed) shown else TabularRpmLayout.display(shown.removeSuffix("*")) +
                            if (shown.endsWith('*')) "*" else ""
                        fields += HeaderFieldReadout(id, displayed, m?.quality ?: HeaderQuality.UNKNOWN, m?.sourceId, m?.sourceObservedAtEpochMs)
                        val unit = if (!speed) "RPM" else when (m?.unit) {
                            HeaderUnit.KNOTS -> "kn"; HeaderUnit.KMH -> "km/h"; HeaderUnit.MPH -> "mi/h"; else -> "—"
                        }
                        val width = if (speed) 127.6f else 139.2f
                        text("$id-label", title, x + if (marker) 13f else 0f, 28f, 20f)
                        text("$id-value", shown, x + width, 77f, 44f, HeaderFont.HERO, HeaderInk.PRIMARY,
                            1.16f, width, HeaderTextAlign.RIGHT, if (speed) HeaderTextRenderer.SPEED else HeaderTextRenderer.RPM)
                        text("$id-unit", unit, x + if (unit == "kn") 142f else 154f, 78f, 21f)
                        val quality = m?.quality ?: HeaderQuality.UNKNOWN
                        val evidence = if (quality == HeaderQuality.UNKNOWN) "—" else
                            "${if (m?.provenance == HeaderProvenance.SIMULATED) "SIMULATED" else "SOURCE"} · $quality"
                        text("$id-quality", evidence, x, 101f, 16f, maxWidth = 210f)
                    }
                    safety("port-rpm", 540f, "PORT RPM", s?.extras?.portRpm, true)
                    safety("starboard-rpm", 780f, "STBD RPM", s?.extras?.starboardRpm, true)
                    safety("sog", 1020f, "SOG", s?.extras?.sog)
                    text("page-status", s?.context?.pageStatus?.let { if (it == "SIMULATED") "SIMULATED DATA" else it }, 1225f, 48f, 18f, maxWidth = 290f)
                    text("source-label", s?.source?.label, 1225f, 70f, 18f, maxWidth = 290f)
                    // No vessel-clock input exists; no fabricated time or boot/system status is drawn.
                }
                else -> Unit // A cleared state has brand and native system navigation only.
            }
        }
        return No5HeaderRenderModel(theme, profile, state.reason, s, Collections.unmodifiableList(texts),
            Collections.unmodifiableList(fields), theme == HeaderTheme.DAY)
    }

    private fun sourceDetail(s: HeaderSnapshotV1?): String? {
        if (s == null) return null
        // MAP's legacy detail is an explicit source/status contract, not a measurement claim.
        if (s.publication.packageName != HeaderApps.HELM) return s.source.detail
        return when (s.source.quality) {
            HeaderQuality.LIVE -> s.source.detail
            HeaderQuality.STALE -> s.source.detail?.replaceFirst(Regex("^LIVE\\b"), "STALE") ?: "STALE"
            HeaderQuality.LOST, HeaderQuality.INVALID, HeaderQuality.UNAVAILABLE -> s.source.quality.name
            HeaderQuality.UNKNOWN -> "—"
        }
    }

    private fun measurementText(m: HeaderMeasurement?): String {
        val value = m?.value ?: return "—"
        if (m.quality != HeaderQuality.LIVE && m.quality != HeaderQuality.STALE || !value.isFinite() || value < 0) return "—"
        val precision = if (m.unit == HeaderUnit.RPM) 0 else 1
        // Same fixed decimal precision as HELM, without saturating oversized RPM into an Int.
        val shown = BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).toPlainString()
        return shown + if (m.quality == HeaderQuality.STALE) "*" else ""
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || visibility != VISIBLE || closed) { cancelGesture(); return false }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelGesture(); ownsStream = false
                if (event.pointerCount != 1 || onSystemNavigationAction == null) return false
                val target = No5HeaderGeometry.hit(event.x, event.y, width, height) ?: return false
                ownsStream = true; armed = target; pointerId = event.getPointerId(0); isPressed = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!ownsStream) return false
                val index = event.findPointerIndex(pointerId)
                if (event.pointerCount != 1 || index < 0 ||
                    No5HeaderGeometry.hit(event.getX(index), event.getY(index), width, height) != armed) cancelGesture()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!ownsStream) return false
                val target = armed
                val matched = event.pointerCount == 1 && event.getPointerId(0) == pointerId &&
                    No5HeaderGeometry.hit(event.x, event.y, width, height) == target
                cancelGesture(); ownsStream = false
                if (matched && target != null) dispatch(target)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val owned = ownsStream; cancelGesture(); ownsStream = false; return owned
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                val owned = ownsStream; cancelGesture(); return owned
            }
        }
        return ownsStream
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    private fun dispatch(target: HeaderNavigationRegion) {
        if (!isEnabled || visibility != VISIBLE || closed) return
        val callback = onSystemNavigationAction ?: return
        callback(target.action)
        // Accessibility exposes the fixed action; application data cannot change the mapping.
        sendVirtualEvent(target.virtualId, AccessibilityEvent.TYPE_VIEW_CLICKED)
    }
    private fun cancelGesture() { armed = null; pointerId = -1; isPressed = false }
    private fun mainThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "Header access requires Android main thread" } }

    private var accessibilityFocus = -1
    private val accessibilityProvider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == HOST_VIEW_ID) return AccessibilityNodeInfo.obtain(this@No5SystemHeaderView).apply {
                onInitializeAccessibilityNodeInfo(this)
                className = "android.view.ViewGroup"
                No5HeaderGeometry.navigation.forEach { addChild(this@No5SystemHeaderView, it.virtualId) }
            }
            val region = No5HeaderGeometry.navigation.firstOrNull { it.virtualId == virtualViewId } ?: return null
            val factor = No5HeaderGeometry.scale(width)
            val bounds = Rect(floor(region.bounds.left * factor).toInt(), floor(region.bounds.top * factor).toInt(),
                ceil(region.bounds.right * factor).toInt(), ceil(region.bounds.bottom * factor).toInt())
            bounds.intersect(0, 0, width, height)
            val offset = IntArray(2); getLocationOnScreen(offset)
            return AccessibilityNodeInfo.obtain().apply {
                setSource(this@No5SystemHeaderView, virtualViewId); setParent(this@No5SystemHeaderView)
                packageName = context.packageName; className = "android.widget.Button"
                contentDescription = region.description; isEnabled = this@No5SystemHeaderView.isEnabled && !closed
                isClickable = true; isFocusable = true; isVisibleToUser = isShown && !bounds.isEmpty
                setBoundsInParent(bounds)
                setBoundsInScreen(Rect(bounds).apply { offset(offset[0], offset[1]) })
                isAccessibilityFocused = accessibilityFocus == virtualViewId
                addAction(AccessibilityNodeInfo.ACTION_CLICK)
                addAction(if (isAccessibilityFocused) AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS else AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            }
        }
        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val target = No5HeaderGeometry.navigation.firstOrNull { it.virtualId == virtualViewId } ?: return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    if (!isEnabled || visibility != VISIBLE || closed || onSystemNavigationAction == null) false
                    else { cancelGesture(); dispatch(target); true }
                }
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    accessibilityFocus = virtualViewId; sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED); true
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (accessibilityFocus != virtualViewId) false else {
                        accessibilityFocus = -1; sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED); true
                    }
                }
                else -> false
            }
        }
    }
    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibilityProvider
    private fun sendVirtualEvent(id: Int, type: Int) {
        val region = No5HeaderGeometry.navigation.first { it.virtualId == id }
        val event = AccessibilityEvent.obtain(type).apply {
            setSource(this@No5SystemHeaderView, id); className = "android.widget.Button"
            packageName = context.packageName; contentDescription = region.description
        }
        parent?.requestSendAccessibilityEvent(this, event)
    }

}
