package com.no5.header.nativeui

import com.no5.header.HeaderClearReason
import com.no5.header.HeaderProfile
import com.no5.header.HeaderQuality
import com.no5.header.HeaderSnapshotV1
import com.no5.header.HeaderTheme
import com.no5.header.SystemNavigationAction

/** One reference transform serves both native drawing and touch/accessibility bounds. */
object No5HeaderGeometry {
    const val WIDTH = 2000f
    const val HEIGHT = 130f

    // Existing HELM header-brand and two palette-hit regions, now system-owned navigation.
    val navigation: List<HeaderNavigationRegion> = java.util.Collections.unmodifiableList(listOf(
        HeaderNavigationRegion(1, SystemNavigationAction.LOGO_HOME, "Systemstartseite", DesignRect(24f, 4f, 262f, 108f)),
        HeaderNavigationRegion(2, SystemNavigationAction.BACK, "Zurück", DesignRect(1741f, 4f, 1852f, 108f)),
        HeaderNavigationRegion(3, SystemNavigationAction.RECENTS, "Übersicht", DesignRect(1852f, 4f, 1963f, 108f)),
    ))

    fun scale(actualWidthPx: Int): Float = actualWidthPx.coerceAtLeast(0) / WIDTH
    fun hit(xPx: Float, yPx: Float, actualWidthPx: Int, actualHeightPx: Int): HeaderNavigationRegion? {
        val scale = scale(actualWidthPx)
        if (scale <= 0f || !xPx.isFinite() || !yPx.isFinite() ||
            xPx < 0f || yPx < 0f || xPx >= actualWidthPx || yPx >= actualHeightPx) return null
        return navigation.firstOrNull { it.bounds.contains(xPx / scale, yPx / scale) }
    }
}

data class DesignRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    // Half-open bounds eliminate an ambiguous owner at the BACK/RECENTS boundary.
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

data class HeaderNavigationRegion(val virtualId: Int, val action: SystemNavigationAction,
    val description: String, val bounds: DesignRect)

enum class HeaderFont { HERO, LABEL }
enum class HeaderInk { PRIMARY, SECONDARY }
enum class HeaderTextAlign { LEFT, CENTER, RIGHT }
enum class HeaderTextRenderer { TEXT, RPM, SPEED }

/** An inspection model, not an IPC payload or another measurement source. */
data class HeaderDrawText(
    val id: String,
    val value: String,
    val x: Float,
    val baseline: Float,
    val size: Float,
    val font: HeaderFont = HeaderFont.LABEL,
    val ink: HeaderInk = HeaderInk.SECONDARY,
    val scaleX: Float = 1f,
    val maxWidth: Float = Float.MAX_VALUE,
    val align: HeaderTextAlign = HeaderTextAlign.LEFT,
    val renderer: HeaderTextRenderer = HeaderTextRenderer.TEXT,
)

data class HeaderFieldReadout(val id: String, val displayed: String,
    val quality: HeaderQuality, val sourceId: String?, val sourceObservedAtEpochMs: Long?)

data class No5HeaderRenderModel(
    val theme: HeaderTheme,
    val profile: HeaderProfile,
    val clearReason: HeaderClearReason,
    /** Accepted, age-degraded data; observation/publication timestamps remain original. */
    val snapshot: HeaderSnapshotV1?,
    val texts: List<HeaderDrawText>,
    val fields: List<HeaderFieldReadout>,
    val usesCloudBackground: Boolean,
) {
    /** Kept for existing diagnostic readers of the original MAP-only inspection model. */
    val usesMapOcean: Boolean get() = usesCloudBackground
    val navigation: List<HeaderNavigationRegion> get() = No5HeaderGeometry.navigation
}
