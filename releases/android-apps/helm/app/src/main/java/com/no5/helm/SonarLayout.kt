package com.no5.helm

/** One native stage geometry for paint, touch and accessibility. */
internal object SonarLayout {
    const val LEFT = 0f
    const val RIGHT = 2000f
    const val TOP = 174f
    const val BOTTOM = 860f
    const val HEIGHT = BOTTOM - TOP
    const val PLOT_LEFT = 94f
    const val SCOPE_LEFT = 1744f
    const val RAIL_TOP = 950f
    const val RAIL_HEIGHT = 96f
    fun plotRight(scope: Boolean) = if (scope) SCOPE_LEFT else RIGHT
    fun plotWidth(scope: Boolean) = plotRight(scope) - PLOT_LEFT
}
