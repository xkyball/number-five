package com.no5.helm.chart

import kotlin.math.*

/** Maps an immutable image's Surface pixels to the requested Mercator camera. */
data class ChartCameraAffine(val a: Double, val b: Double, val tx: Double,
    val c: Double, val d: Double, val ty: Double) {
    fun map(point: ScreenPoint) = ScreenPoint(a * point.x + b * point.y + tx, c * point.x + d * point.y + ty)
    fun matrixValues() = floatArrayOf(a.toFloat(), b.toFloat(), tx.toFloat(), c.toFloat(), d.toFloat(), ty.toFloat(), 0f, 0f, 1f)
    fun after(before: ChartCameraAffine) = ChartCameraAffine(a * before.a + b * before.c, a * before.b + b * before.d,
        a * before.tx + b * before.ty + tx, c * before.a + d * before.c, c * before.b + d * before.d,
        c * before.tx + d * before.ty + ty)
}

/** TextureView initially scales its whole BufferQueue into its unchanged visible View. Undo that
 * scaling and crop around the canonical camera centre, retaining extra source pixels for gestures. */
fun chartRasterCrop(raster: ChartRasterGeometry): ChartCameraAffine {
    val visible = raster.visible
    return ChartCameraAffine(visible.scale * raster.bufferWidth / visible.surfaceWidth, 0.0,
        visible.left - raster.cropLeft * visible.scale, 0.0,
        visible.scale * raster.bufferHeight / visible.surfaceHeight, visible.top - raster.cropTop * visible.scale)
}

fun chartBufferCameraAffine(base: ChartViewport, target: ChartViewport,
    geometry: ChartSurfaceGeometry, raster: ChartBufferRaster?): ChartCameraAffine? {
    val camera = chartCameraAffine(base, target, geometry) ?: return null
    if (raster == null) return camera
    if (raster.geometry.visible != geometry || raster.producerCamera.copy(width = base.width, height = base.height) != base) return null
    return camera.after(chartRasterCrop(raster.geometry))
}

/** Camera-only math: no Android View, producer completion, or chart-detail state. */
fun chartCameraAffine(base: ChartViewport, target: ChartViewport,
    geometry: ChartSurfaceGeometry): ChartCameraAffine? {
    fun valid(viewport: ChartViewport) = viewport.latitude.isFinite() && viewport.latitude in -85.0..85.0 &&
        viewport.longitude.isFinite() && viewport.metresToPixels.isFinite() && viewport.metresToPixels > 0 &&
        viewport.rotationRadians.isFinite() && viewport.width == geometry.frameWidth && viewport.height == geometry.frameHeight
    if (!valid(base) || !valid(target)) return null
    val radius = 6378137.0 * 0.9996
    val longitudeDelta = ((base.longitude - target.longitude + 180) % 360 + 360) % 360 - 180
    val east = Math.toRadians(longitudeDelta) * radius
    fun northing(latitude: Double) = radius * ln(tan(PI / 4 + Math.toRadians(latitude) / 2))
    val north = northing(base.latitude) - northing(target.latitude)
    val ratio = target.metresToPixels / base.metresToPixels
    val angle = target.rotationRadians - base.rotationRadians
    val a = ratio * cos(angle); val b = -ratio * sin(angle)
    val c = ratio * sin(angle); val d = ratio * cos(angle)
    val cx = geometry.left + geometry.scale * base.width / 2
    val cy = geometry.top + geometry.scale * base.height / 2
    val x = geometry.scale * target.metresToPixels * (east * cos(target.rotationRadians) + north * sin(target.rotationRadians))
    val y = geometry.scale * target.metresToPixels * (east * sin(target.rotationRadians) - north * cos(target.rotationRadians))
    val result = ChartCameraAffine(a, b, cx + x - a * cx - b * cy, c, d, cy + y - c * cx - d * cy)
    return result.takeIf { listOf(it.a, it.b, it.c, it.d, it.tx, it.ty).all { value -> value.isFinite() && abs(value) <= Float.MAX_VALUE } }
}

/**
 * Fraction of the requested Surface outside the transformed full image-buffer rectangle.
 * This describes geometric buffer coverage only: cached pixels can contain background or old
 * chart details. It makes no statement about ENC coverage, data validity, or portrayal quality.
 * Invalid cameras return null, never an invented coverage result.
 */
fun chartBufferUncoveredFraction(base: ChartViewport, target: ChartViewport,
    geometry: ChartSurfaceGeometry, raster: ChartBufferRaster? = null): Double? {
    val affine = chartBufferCameraAffine(base, target, geometry, raster) ?: return null
    val width = geometry.surfaceWidth.toDouble(); val height = geometry.surfaceHeight.toDouble()
    val x0 = raster?.let { it.filledLeft * width / it.geometry.bufferWidth } ?: 0.0
    val y0 = raster?.let { it.filledTop * height / it.geometry.bufferHeight } ?: 0.0
    val x1 = raster?.let { (it.filledLeft + it.filledWidth) * width / it.geometry.bufferWidth } ?: width
    val y1 = raster?.let { (it.filledTop + it.filledHeight) * height / it.geometry.bufferHeight } ?: height
    var polygon = listOf(ScreenPoint(x0, y0), ScreenPoint(x1, y0),
        ScreenPoint(x1, y1), ScreenPoint(x0, y1)).map(affine::map)
    if (polygon.any { !it.x.isFinite() || !it.y.isFinite() }) return null

    // Sutherland-Hodgman: clip a convex transformed rectangle against four Surface half-planes.
    fun clip(points: List<ScreenPoint>, horizontalAxis: Boolean, boundary: Double,
        keepGreater: Boolean): List<ScreenPoint> {
        if (points.isEmpty()) return emptyList()
        fun coordinate(point: ScreenPoint) = if (horizontalAxis) point.x else point.y
        fun inside(point: ScreenPoint) = if (keepGreater) coordinate(point) >= boundary else coordinate(point) <= boundary
        val result = ArrayList<ScreenPoint>(points.size + 1)
        var previous = points.last(); var previousInside = inside(previous)
        for (current in points) {
            val currentInside = inside(current)
            if (previousInside != currentInside) {
                val fraction = (boundary - coordinate(previous)) / (coordinate(current) - coordinate(previous))
                result += if (horizontalAxis) ScreenPoint(boundary, previous.y + fraction * (current.y - previous.y))
                    else ScreenPoint(previous.x + fraction * (current.x - previous.x), boundary)
            }
            if (currentInside) result += current
            previous = current; previousInside = currentInside
        }
        return result
    }
    polygon = clip(polygon, true, 0.0, true)
    polygon = clip(polygon, true, width, false)
    polygon = clip(polygon, false, 0.0, true)
    polygon = clip(polygon, false, height, false)
    if (polygon.any { !it.x.isFinite() || !it.y.isFinite() }) return null
    if (polygon.size < 3) return 1.0
    var twiceArea = 0.0
    var previous = polygon.last()
    for (current in polygon) {
        twiceArea += previous.x * current.y - previous.y * current.x
        previous = current
    }
    val coveredFraction = abs(twiceArea) / (2.0 * width * height)
    return (1.0 - coveredFraction).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
}

/** Shared Mercator camera transform for gesture preview and the canonical camera owner. */
fun transformChartCamera(view: ChartViewport, anchor: ScreenPoint = ScreenPoint(view.width / 2.0, view.height / 2.0),
    pan: ScreenPoint = ScreenPoint(0.0, 0.0), zoom: Double = 1.0, rotation: Double = 0.0): ChartViewport {
    require(zoom.isFinite() && zoom > 0 && rotation.isFinite() && anchor.x.isFinite() && anchor.y.isFinite() && pan.x.isFinite() && pan.y.isFinite())
    val ppm = (view.metresToPixels * zoom).coerceIn(.00001, 10.0)
    if (ppm == view.metresToPixels && pan.x == 0.0 && pan.y == 0.0 && rotation == 0.0) return view
    val angle = atan2(sin(view.rotationRadians + rotation), cos(view.rotationRadians + rotation))
    fun offset(point: ScreenPoint, scale: Double, radians: Double): ScreenPoint {
        val dx = point.x - view.width / 2.0; val dy = point.y - view.height / 2.0
        return ScreenPoint((dx * cos(radians) + dy * sin(radians)) / scale,
            (dx * sin(radians) - dy * cos(radians)) / scale)
    }
    val before = offset(anchor, view.metresToPixels, view.rotationRadians)
    val after = offset(ScreenPoint(anchor.x + pan.x, anchor.y + pan.y), ppm, angle)
    val radius = 6378137.0 * .9996
    val northing = ln(tan(PI / 4 + Math.toRadians(view.latitude) / 2)) + (before.y - after.y) / radius
    val longitude = view.longitude + Math.toDegrees((before.x - after.x) / radius)
    return view.copy(latitude = Math.toDegrees(2 * atan(exp(northing)) - PI / 2).coerceIn(-85.0, 85.0),
        longitude = ((longitude + 180) % 360 + 360) % 360 - 180, metresToPixels = ppm, rotationRadians = angle)
}
