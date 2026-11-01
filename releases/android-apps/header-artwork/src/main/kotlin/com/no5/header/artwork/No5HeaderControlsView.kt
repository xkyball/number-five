package com.no5.header.artwork

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.no5.ui.No5Text
import com.no5.ui.No5TouchFeedback
import com.no5.ui.WifiObserver
import com.no5.ui.WifiSignal

/** Same visual and non-overlapping native targets on the shared 2000 × 130 header. */
class No5HeaderControlsView(context: Context) : FrameLayout(context) {
    private val face = Typeface.createFromAsset(context.assets, "no5-header/barlow_condensed_semibold.ttf")
    private var night = false
    private var active = false
    private var signal: WifiSignal? = null
    private var subscription: WifiObserver.Subscription? = null
    private var onTheme: ((Boolean) -> Unit)? = null
    private var onInfo: ((String) -> Unit)? = null
    private var feedbackNight: Boolean? = null
    private var lastBuild: String? = null
    private var lastLanguage: String? = null
    private var momentSubscription: AutoCloseable? = null
    private var moment: No5MomentFeedback.Moment? = null
    private val expireMoment = Runnable { moment = null; showVersion() }
    private fun showVersion() {
        val event = moment?.takeIf { it.expiresAt > android.os.SystemClock.uptimeMillis() }
        val language = lastLanguage ?: com.no5.ui.UiLanguage.current(context)
        val action = event?.let { No5Text.text(language, it.action) }
        version.text = if (event == null) lastBuild else action + when(event.phase) {
            No5MomentFeedback.Phase.ACCEPTED -> "…"
            No5MomentFeedback.Phase.EXECUTED -> " ✓"
            No5MomentFeedback.Phase.FAILED -> " ×"
            No5MomentFeedback.Phase.UNKNOWN -> " ?"
        }
        version.contentDescription = if(event == null) lastBuild else No5Text.text(language,
            when(event.phase) {
                No5MomentFeedback.Phase.ACCEPTED -> "%s · angenommen"
                No5MomentFeedback.Phase.EXECUTED -> "%s · ausgeführt"
                No5MomentFeedback.Phase.FAILED -> "%s · fehlgeschlagen"
                No5MomentFeedback.Phase.UNKNOWN -> "%s · Abschluss unklar"
            }, action)
        version.gravity=if(event==null)Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.LEFT
        version.setTextColor(Color.parseColor(if(event==null) { if(night)"#d40000"else"#122653" }
            else { if(night)"#990000"else"#526b7b" }))
        version.setTextSize(TypedValue.COMPLEX_UNIT_PX, (if(event == null)23f else 20f)*width/2000f)
    }
    private val day = label().apply { tag = "header-day"; setOnClickListener { onTheme?.invoke(false) } }
    private val dark = label().apply { tag = "header-night"; setOnClickListener { onTheme?.invoke(true) } }
    private val version = label().apply { tag = "installed-app-version"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    private val info = object : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glyph = com.no5.ui.WifiSignalDrawable()
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val ink = Color.parseColor(if (night) "#d40000" else "#122653")
            glyph.setBounds(0,0,width,height);glyph.render(signal,ink);glyph.draw(canvas)
            val save = canvas.save(); canvas.scale(width / 96f, height / 96f)
            p.color=ink;p.style=Paint.Style.FILL
            p.typeface = face; p.textSize = 19f; p.textAlign = Paint.Align.CENTER
            canvas.drawText("i", 77f, 22f, p)
            canvas.restoreToCount(save)
        }
    }.apply { tag = "header-network-info"; isFocusable = true; setOnClickListener { onInfo?.invoke(wifiDetails()) } }

    init {
        clipChildren = false; clipToPadding = false
        addView(day); addView(dark); addView(version); addView(info)
    }
    private fun label() = TextView(context).apply {
        typeface = face; gravity = Gravity.CENTER; includeFontPadding = false
        setPadding(0, 0, 0, 0); maxLines = 1
    }

    fun render(buildLabel: String, night: Boolean, visible: Boolean,
        onTheme: (Boolean) -> Unit, onInfo: (String) -> Unit) {
        render(buildLabel, night, visible, onTheme, com.no5.ui.UiLanguage.current(context), onInfo)
    }

    /** Explicit confirmed locale is an AndroidView render input, not an unobserved process cache read. */
    fun render(buildLabel: String, night: Boolean, visible: Boolean,
        onTheme: (Boolean) -> Unit, language: String, onInfo: (String) -> Unit) {
        val changed = lastBuild != buildLabel || lastLanguage != language || this.night != night
        this.night = night; this.active = visible; this.onTheme = onTheme; this.onInfo = onInfo
        if (!changed) { observe(); return }
        lastBuild = buildLabel; lastLanguage = language
        val ink = Color.parseColor(if (night) "#d40000" else "#122653")
        val inverse = Color.parseColor(if (night) "#ff0000" else "#f6f9fa")
        val pressed = Color.parseColor(if (night) "#33700000" else "#33122653")
        day.text = No5Text.text(language, "Tag"); dark.text = No5Text.text(language, "Nacht")
        day.contentDescription = No5Text.text(language, "Tagmodus")
        dark.contentDescription = No5Text.text(language, "Nachtmodus")
        day.isSelected = !night; dark.isSelected = night
        day.setTextColor(if (!night) inverse else ink); dark.setTextColor(if (night) inverse else ink)
        if (feedbackNight != night || day.background == null) {
            for ((view, selected) in listOf(day to !night, dark to night)) {
                val scale = (resources.displayMetrics.widthPixels / 2000f).coerceAtLeast(.1f)
                val shape = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (selected) Color.parseColor(if (night) "#700000" else "#071022") else Color.TRANSPARENT)
                    cornerRadius = 25.5f * scale
                }
                val neutral = android.graphics.drawable.InsetDrawable(shape, 0, (15f*scale).toInt(), 0, (30f*scale).toInt())
                No5TouchFeedback.install(view, neutral, pressed, ink)
            }
            No5TouchFeedback.install(info, pressed, ink)
            feedbackNight = night
        }
        showVersion()
        updateDescription(); info.invalidate(); requestLayout(); observe()
    }

    fun wifiDetails(): String = com.no5.ui.WifiUiText.details(lastLanguage ?: com.no5.ui.UiLanguage.current(context), signal)
    fun setForeground(visible: Boolean) { active = visible; observe() }
    private fun updateDescription() { info.contentDescription = No5Text.text(lastLanguage ?: com.no5.ui.UiLanguage.current(context), "Version und WLAN-Info") + ". " + wifiDetails() }
    private fun observe() {
        val shouldObserve = active && isAttachedToWindow && windowVisibility == VISIBLE && isShown
        if (shouldObserve && momentSubscription == null) {
            momentSubscription = No5MomentFeedback.observe { value ->
                removeCallbacks(expireMoment); moment = value; showVersion()
                value?.let { postDelayed(expireMoment, (it.expiresAt-android.os.SystemClock.uptimeMillis()).coerceAtLeast(0)) }
            }
        } else if (!shouldObserve && momentSubscription != null) {
            momentSubscription?.close(); momentSubscription = null
            removeCallbacks(expireMoment); moment = null; showVersion()
        }
        if (shouldObserve && subscription == null) {
            subscription = WifiObserver.subscribe(context) { next -> signal = next; updateDescription(); info.invalidate() }
        } else if (!shouldObserve) { subscription?.close(); subscription = null }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); observe() }
    override fun onDetachedFromWindow() {
        subscription?.close(); subscription = null
        momentSubscription?.close(); momentSubscription = null
        removeCallbacks(expireMoment); moment = null
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); observe() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); observe() }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val scale = w / 2000f
        fun measure(v: View, dw: Float, dh: Float) = v.measure(
            MeasureSpec.makeMeasureSpec((dw*scale).toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((dh*scale).toInt(), MeasureSpec.EXACTLY))
        measure(day,111f,96f); measure(dark,111f,96f); measure(info,96f,96f); measure(version,222f,28f)
        setMeasuredDimension(w, resolveSize((130f*scale).toInt(),heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val scale = width / 2000f
        fun put(v: View, x: Float, y: Float, w: Float, h: Float) {
            v.layout((x*scale).toInt(), (y*scale).toInt(), ((x+w)*scale).toInt(), ((y+h)*scale).toInt())
        }
        put(day, 1741f, 4f, 111f, 96f); put(dark, 1852f, 4f, 111f, 96f)
        put(version, 1741f, 82f, 222f, 28f); put(info, 1638f, 4f, 96f, 96f)
        day.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f*scale)
        dark.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f*scale)
        showVersion(); observe()
    }
}
