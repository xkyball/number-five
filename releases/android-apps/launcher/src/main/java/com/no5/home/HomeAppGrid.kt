package com.no5.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Recycled native tiles measured in the same pixels as HomeStage. */
internal class HomeAppGrid(
    context: Context,
    apps: List<HomeController.App>,
    night: Boolean,
    icon: (HomeController.App) -> Drawable,
    open: (HomeController.App) -> Unit
) : LinearLayout(context) {
    val grid = GridView(context)

    init {
        orientation = VERTICAL
        val style = HomeStyle(night)
        addView(TextView(context).apply {
            text = com.no5.ui.No5Text.text(context, "ALLE APPS")
            typeface = resources.getFont(R.font.barlow_condensed_extrabold)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 34f)
            setTextColor(style.shellText)
            gravity = Gravity.CENTER_VERTICAL
        }, LayoutParams(LayoutParams.MATCH_PARENT, 56))
        if (apps.isEmpty()) {
            addView(TextView(context).apply {
                text = com.no5.ui.No5Text.text(context, "Keine weiteren startbaren Apps installiert.")
                typeface = resources.getFont(R.font.barlow_condensed_regular)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f)
                setTextColor(style.shellText)
            })
        } else {
            grid.apply {
                numColumns = COLUMNS
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                horizontalSpacing = GAP
                verticalSpacing = GAP
                setPadding(0, 0, 0, 30)
                clipToPadding = true
                isVerticalScrollBarEnabled = true
                scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
                selector = ColorDrawable(android.graphics.Color.TRANSPARENT)
                val icons = mutableMapOf<android.content.ComponentName, Drawable>()
                // The tile owns its whole touch area; no competing child click listeners.
                adapter = object : BaseAdapter() {
                    override fun getCount() = apps.size
                    override fun getItem(position: Int) = apps[position]
                    override fun getItemId(position: Int) = apps[position].component.flattenToString()
                        .fold(1125899906842597L) { hash, char -> 31 * hash + char.code }.and(Long.MAX_VALUE)
                    override fun hasStableIds() = true
                    override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                        val app = apps[position]
                        return (recycled as? Tile ?: Tile(context, style)).apply {
                            bind(app, icons.getOrPut(app.component) { icon(app) }, night) { open(app) }
                        }
                    }
                }
            }
            addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    internal class Tile(context: Context, style: HomeStyle) : LinearLayout(context) {
        val icon = ImageView(context)
        val name = TextView(context)
        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(12, 14, 12, 8)
            layoutParams = AbsListView.LayoutParams(LayoutParams.MATCH_PARENT, TILE_HEIGHT)
            isClickable = true
            isFocusable = true
            com.no5.ui.No5TouchFeedback.install(this,
                GradientDrawable().apply {
                    setColor(style.control)
                    cornerRadius = 24f
                    setStroke(2, style.structural)
                }, (style.accent and 0x00ffffff) or 0x33000000, style.structural)
            icon.apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(icon, LayoutParams(ICON_SIZE, ICON_SIZE))
            name.apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                typeface = resources.getFont(R.font.barlow_condensed_semibold)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f)
                setTextColor(style.ink)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(name, LayoutParams(LayoutParams.MATCH_PARENT, 72).apply { topMargin = 6 })
        }
        fun bind(app: HomeController.App, drawable: Drawable, night: Boolean, open: () -> Unit) {
            name.text = app.label
            contentDescription = app.label
            icon.setImageDrawable(drawable)
            // Preserve the real icon's shape/detail while respecting the existing red-night palette.
            icon.colorFilter = if (night) ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                .2126f, .7152f, .0722f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))) else null
            setOnClickListener { open() }
        }
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = Button::class.java.name
        }
    }

    companion object {
        const val COLUMNS = 5
        const val GAP = 24
        const val TILE_HEIGHT = 212
        const val ICON_SIZE = 112
    }
}
