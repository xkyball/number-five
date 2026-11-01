package com.no5.helm.chart

import kotlin.math.min
import kotlin.math.ceil

/** Limits of the retained S52 viewport (including its 1500-entry edge arrays). */
const val MAX_CHART_WIDTH = 4096
const val MAX_CHART_HEIGHT = 1499

/** Uniform FBO-to-Surface mapping; integer FBO rounding leaves only narrow borders. */
data class ChartSurfaceGeometry(val surfaceWidth: Int, val surfaceHeight: Int, val frameWidth: Int, val frameHeight: Int) {
    init {
        require(surfaceWidth > 0 && surfaceHeight > 0)
        require(frameWidth in 1..MAX_CHART_WIDTH && frameHeight in 1..MAX_CHART_HEIGHT)
    }
    val scale = min(surfaceWidth.toDouble() / frameWidth, surfaceHeight.toDouble() / frameHeight)
    val left = (surfaceWidth - frameWidth * scale) / 2.0
    val top = (surfaceHeight - frameHeight * scale) / 2.0
    fun toFrame(point: ScreenPoint) = ScreenPoint((point.x - left) / scale, (point.y - top) / scale)
    fun toSurface(point: ScreenPoint) = ScreenPoint(point.x * scale + left, point.y * scale + top)
    fun contains(point: ScreenPoint) = point.x >= left && point.x < surfaceWidth - left &&
        point.y >= top && point.y < surfaceHeight - top

    companion object {
        fun fit(width: Int, height: Int): ChartSurfaceGeometry {
            require(width > 0 && height > 0) { "Invalid Surface size" }
            val reduction = min(1.0, min(MAX_CHART_WIDTH.toDouble() / width, MAX_CHART_HEIGHT.toDouble() / height))
            return ChartSurfaceGeometry(width, height,
                (width * reduction).toInt().coerceIn(1, MAX_CHART_WIDTH),
                (height * reduction).toInt().coerceIn(1, MAX_CHART_HEIGHT))
        }
    }
}

/** Stored producer pixels are distinct from the visible View and its input coordinates. */
data class ChartRasterGeometry(val visible: ChartSurfaceGeometry, val bufferWidth: Int, val bufferHeight: Int) {
    init {
        require(bufferWidth in visible.frameWidth..MAX_CHART_WIDTH && bufferHeight in visible.frameHeight..MAX_CHART_HEIGHT)
    }
    val cropLeft = (bufferWidth - visible.frameWidth) / 2.0
    val cropTop = (bufferHeight - visible.frameHeight) / 2.0
    val effectiveFactor = min(bufferWidth.toDouble() / visible.frameWidth, bufferHeight.toDouble() / visible.frameHeight)
    val queuePixelBytes: Long get() = bufferWidth.toLong() * bufferHeight * 4
    fun rasterCamera(visibleCamera: ChartViewport) = visibleCamera.copy(width = bufferWidth, height = bufferHeight)
    companion object {
        fun reserve(visible: ChartSurfaceGeometry): ChartRasterGeometry {
            val factor = min(1.25, min(MAX_CHART_WIDTH.toDouble() / visible.frameWidth, MAX_CHART_HEIGHT.toDouble() / visible.frameHeight))
            return ChartRasterGeometry(visible, ceil(visible.frameWidth * factor).toInt().coerceAtMost(MAX_CHART_WIDTH),
                ceil(visible.frameHeight * factor).toInt().coerceAtMost(MAX_CHART_HEIGHT))
        }
    }
}

/** Filled rectangle excludes padded background which has no prepared chart pixels. */
data class ChartBufferRaster(val geometry: ChartRasterGeometry, val producerCamera: ChartViewport,
    val filledLeft: Double, val filledTop: Double, val filledWidth: Int, val filledHeight: Int) {
    init {
        require(filledLeft.isFinite() && filledTop.isFinite() && filledLeft >= 0 && filledTop >= 0)
        require(filledWidth > 0 && filledHeight > 0 && filledLeft + filledWidth <= geometry.bufferWidth && filledTop + filledHeight <= geometry.bufferHeight)
        require(producerCamera.width == filledWidth && producerCamera.height == filledHeight)
    }
    companion object {
        fun capture(geometry: ChartRasterGeometry, visibleCamera: ChartViewport, reserve: Boolean): ChartBufferRaster =
            if (reserve) ChartBufferRaster(geometry, geometry.rasterCamera(visibleCamera), 0.0, 0.0, geometry.bufferWidth, geometry.bufferHeight)
            else ChartBufferRaster(geometry, visibleCamera, geometry.cropLeft, geometry.cropTop,
                geometry.visible.frameWidth, geometry.visible.frameHeight)
    }
}
