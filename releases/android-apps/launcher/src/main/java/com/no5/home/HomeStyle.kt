package com.no5.home

import android.graphics.Color

/** AudioNativeScene palette; geometry is identical in day and red-night modes. */
internal class HomeStyle(night: Boolean) {
    val ink = Color.parseColor(if (night) "#d40000" else "#07121b")
    val secondary = Color.parseColor(if (night) "#b00000" else "#344b59")
    val structural = Color.parseColor(if (night) "#700000" else "#90a0a7")
    val accent = Color.parseColor(if (night) "#d40000" else "#ff5b00")
    val surface = Color.parseColor(if (night) "#090000" else "#f6f8f7")
    val control = Color.parseColor(if (night) "#140000" else "#ffffff")
    val shell = Color.parseColor(if (night) "#000000" else "#09202a")
    val shellText = Color.parseColor(if (night) "#d40000" else "#f6f8f7")
    companion object {
        const val GUTTER_DP = 24
        const val TOUCH_DP = 56
        const val ROW_DP = 72
        const val RADIUS_DP = 4
    }
}
