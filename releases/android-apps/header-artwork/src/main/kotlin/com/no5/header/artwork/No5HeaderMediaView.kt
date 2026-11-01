package com.no5.header.artwork

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Looper
import android.os.SystemClock
import android.view.View

/**
 * Two-line media button inside the existing header context slot. This view owns no connection,
 * session, freshness policy or app navigation. Its host supplies localized, age-checked text.
 * Place at design y=4 with height=104; its baselines are the existing header y=46 and y=69.
 */
class No5HeaderMediaView(context: Context) : View(context) {
    private val face = Typeface.createFromAsset(context.assets, "no5-header/barlow_condensed_semibold.ttf")
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = 23f }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = 18f }
    private var title = ""
    private var detail = ""
    private var generation = -1L
    private var titleWidth = 0f
    private var detailWidth = 0f
    private var foreground = false
    private var reducedMotion = true
    private var originElapsedMs = 0L
    private var scheduled = false
    private var lastSnapshot: com.no5.ui.UiStateSnapshot? = null
    private var lastSnapshotFresh = false
    private var lastNight = false
    // One final static redraw is needed if reduced motion becomes active between frames.
    private val frame = Runnable { scheduled = false; if (isAttachedToWindow && isShown) invalidate() }

    var onOpenAudio: (() -> Unit)? = null
        set(value) { mainThread(); field = value; isClickable = value != null }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
    }

    fun render(snapshot: com.no5.ui.UiStateSnapshot, night: Boolean,
        active: Boolean = true, reducedMotion: Boolean = false) {
        mainThread()
        val now = SystemClock.elapsedRealtime()
        val fresh = snapshot.connected && snapshot.media.isFresh(now)
        val previous = lastSnapshot
        if (previous != null && previous.language == snapshot.language && previous.connected == snapshot.connected &&
            previous.media.available == snapshot.media.available && previous.media.title == snapshot.media.title &&
            previous.media.artist == snapshot.media.artist && previous.media.playback == snapshot.media.playback &&
            previous.media.sourceGeneration == snapshot.media.sourceGeneration && fresh == lastSnapshotFresh &&
            night == lastNight && active == foreground && reducedMotion == this.reducedMotion) {
            lastSnapshot = snapshot
            return // Freshness-only audio updates need no formatting, text measurement or redraw.
        }
        val text = No5HeaderMediaText.from(snapshot, now)
        render(text.title, text.detail, text.accessibilityDescription, night, active, reducedMotion,
            text.sourceGeneration)
        lastSnapshot = snapshot
        lastSnapshotFresh = fresh
    }

    fun render(title: String, detail: String, accessibilityDescription: String, night: Boolean,
        active: Boolean = true, reducedMotion: Boolean = false, sourceGeneration: Long = 0L) {
        mainThread()
        lastSnapshot = null
        lastNight = night
        val nextTitle = singleLine(title)
        val nextDetail = singleLine(detail)
        if (this.title != nextTitle || this.detail != nextDetail || generation != sourceGeneration || foreground != active) {
            this.title = nextTitle; this.detail = nextDetail; generation = sourceGeneration
            titleWidth = titlePaint.measureText(nextTitle); detailWidth = detailPaint.measureText(nextDetail)
            originElapsedMs = SystemClock.elapsedRealtime()
        }
        foreground = active
        this.reducedMotion = reducedMotion
        titlePaint.color = Color.parseColor(if (night) "#d40000" else "#071022")
        detailPaint.color = Color.parseColor(if (night) "#b00000" else "#122653")
        // Two upstream fields may each contain 512 characters; keep both accessible in full.
        contentDescription = accessibilityDescription.take(2048)
        if (!canAnimate()) stopFrames()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val scale = height / DESIGN_HEIGHT
        val available = width / scale
        val animate = canAnimate()
        val elapsed = if (animate) SystemClock.elapsedRealtime() - originElapsedMs else 0L
        val saved = canvas.save()
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, available, DESIGN_HEIGHT)
        canvas.drawText(title, -No5Marquee.offset(titleWidth, available, elapsed, animate), 42f, titlePaint)
        canvas.drawText(detail, -No5Marquee.offset(detailWidth, available, elapsed, animate), 65f, detailPaint)
        canvas.restoreToCount(saved)
        if (animate && (No5Marquee.needsAnimation(titleWidth, available) ||
                    No5Marquee.needsAnimation(detailWidth, available)) && !scheduled) {
            scheduled = true
            postOnAnimation(frame)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (!isEnabled || !isShown || !foreground) return false
        val action = onOpenAudio ?: return false
        action()
        return true
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) stopFrames() else { originElapsedMs = SystemClock.elapsedRealtime(); invalidate() }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) stopFrames() else invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) stopFrames() else invalidate()
    }

    override fun onDetachedFromWindow() { stopFrames(); super.onDetachedFromWindow() }

    private fun canAnimate() = foreground && !reducedMotion && isAttachedToWindow &&
        isShown && hasWindowFocus() && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()
    private fun stopFrames() { removeCallbacks(frame); scheduled = false }
    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper()) { "Header media requires the main thread" }
    private fun singleLine(value: String) = value.take(1024).replace('\n', ' ').replace('\r', ' ')

    companion object { const val DESIGN_HEIGHT = 104f }
}
