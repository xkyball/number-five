package com.no5.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Native View counterpart of MAP's No5ControlTheme and CompactControls. */
internal class GatewaySettingsStyle(private val context: Context, private val night: Boolean) {
    private val colors = HomeStyle(night)
    val surface = colors.surface
    val accent = Color.parseColor(if (night) "#ff1a0a" else "#ff5b00")
    private fun dp(n: Int) = (context.resources.displayMetrics.density * n).toInt()
    fun text(value: String, size: Float = 14f, font: Int = R.font.barlow_condensed_regular) = TextView(context).apply {
        text = value; textSize = size; typeface = resources.getFont(font)
        setTextColor(colors.ink); setPadding(0, dp(4), 0, dp(4))
    }
    fun divider() = View(context).apply {
        setBackgroundColor(colors.structural); layoutParams = LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1))
    }
    fun panelBackground() = outline(surface)
    private fun outline(fill: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(4).toFloat(); setStroke(dp(1).coerceAtLeast(1), this@GatewaySettingsStyle.colors.structural)
    }
    fun input() = EditText(context).apply {
        textSize = 18f; typeface = resources.getFont(R.font.barlow_condensed_regular)
        setTextColor(colors.ink); setHintTextColor(colors.secondary)
        isSingleLine = true; minHeight = dp(64); minimumHeight = dp(64); background = outline(colors.control)
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }
    fun button(title: String, action: () -> Unit) = Button(context).apply {
        text = title; styleButton(this); setOnClickListener { action() }
    }
    fun styleButton(button: Button) = button.apply {
        isAllCaps = false; textSize = 14f; typeface = resources.getFont(R.font.barlow_condensed_semibold)
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(if (night) accent else Color.WHITE, colors.ink)))
        minHeight = dp(64); minimumHeight = dp(64); minWidth = dp(64); minimumWidth = dp(64)
        setPadding(dp(16), 0, dp(16), 0); background = ControlFace(this)
        stateListAnimator = null
    }
    fun row(title: String, child: View, detail: String? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 14f))
            detail?.let { addView(text(it, 11f).apply { setTextColor(colors.secondary) }) }
        }, LinearLayout.LayoutParams(dp(140), -2))
        addView(child, LinearLayout.LayoutParams(0, -2, 1f))
    }
    private inner class ControlFace(private val button: Button) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val rect = RectF(bounds).apply { inset(dp(4).toFloat(), dp(10).toFloat()) }
            val selected = button.isSelected
            paint.style = Paint.Style.FILL
            paint.color = if (selected) { if (night) colors.structural else colors.ink } else colors.control
            canvas.drawRoundRect(rect, dp(4).toFloat(), dp(4).toFloat(), paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).coerceAtLeast(1).toFloat(); paint.color = colors.structural
            canvas.drawRoundRect(rect, dp(4).toFloat(), dp(4).toFloat(), paint)
            if (selected) {
                paint.style = Paint.Style.FILL; paint.color = accent
                canvas.drawRect(rect.left + dp(8), rect.bottom - dp(2), rect.right - dp(8), rect.bottom, paint)
            }
        }
        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
