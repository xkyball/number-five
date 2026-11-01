package com.no5.helm.chart

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class ChartRasterGeometryTest {
    private val visible = ChartSurfaceGeometry.fit(2000, 1070)
    private val raster = ChartRasterGeometry.reserve(visible)
    private val camera = ChartViewport(42.36, -71.04, .15, width = 2000, height = 1070)
    private fun texturePoint(x: Double, y: Double) = ScreenPoint(x * visible.surfaceWidth / raster.bufferWidth,
        y * visible.surfaceHeight / raster.bufferHeight)
    private fun assertPoint(expected: ScreenPoint, actual: ScreenPoint) {
        assertEquals(expected.x, actual.x, .000001); assertEquals(expected.y, actual.y, .000001)
    }
    @Test fun luminaKeepsVisibleGeometryAndUsesFullReserveWithoutStretching() {
        assertEquals(visible, raster.visible); assertEquals(2500, raster.bufferWidth); assertEquals(1338, raster.bufferHeight)
        val crop = chartRasterCrop(raster)
        assertPoint(ScreenPoint(0.0, 0.0), crop.map(texturePoint(250.0, 134.0)))
        assertPoint(ScreenPoint(1000.0, 535.0), crop.map(texturePoint(1250.0, 669.0)))
        assertPoint(ScreenPoint(2000.0, 1070.0), crop.map(texturePoint(2250.0, 1204.0)))
        // A one-raster-pixel symbol/gesture remains one screen pixel on both axes.
        assertPoint(ScreenPoint(1001.0, 536.0), crop.map(texturePoint(1251.0, 670.0)))
    }
    @Test fun maxHeightCapsFactorAndKeepsTheSamePixelsPerMetreOnBothAxes() {
        for (size in listOf(2000 to 1200, 4096 to 1499, 5000 to 2200)) {
            val fitted = ChartSurfaceGeometry.fit(size.first, size.second); val capped = ChartRasterGeometry.reserve(fitted)
            assertTrue(capped.bufferWidth <= 4096 && capped.bufferHeight <= 1499)
            assertTrue(capped.effectiveFactor <= 1.25 + 1.0 / minOf(fitted.frameWidth, fitted.frameHeight))
            val crop = chartRasterCrop(capped)
            val centre = ScreenPoint(fitted.surfaceWidth / 2.0, fitted.surfaceHeight / 2.0)
            val raw = ScreenPoint(capped.bufferWidth / 2.0, capped.bufferHeight / 2.0)
            fun sampled(p: ScreenPoint) = ScreenPoint(p.x * fitted.surfaceWidth / capped.bufferWidth, p.y * fitted.surfaceHeight / capped.bufferHeight)
            assertPoint(centre, crop.map(sampled(raw)))
            assertPoint(ScreenPoint(centre.x + fitted.scale, centre.y + fitted.scale), crop.map(sampled(ScreenPoint(raw.x + 1, raw.y + 1))))
        }
    }
    @Test fun panCanUseReservePixelsButPaddedBackgroundNeverCountsAsCoverage() {
        // Independent Mercator camera: a 200px rightward image shift moves the
        // target centre west by 200 / ppm metres. No UI gesture helper dependency.
        val radius = 6378137.0 * 0.9996
        val target = camera.copy(longitude = camera.longitude - Math.toDegrees(200.0 / camera.metresToPixels / radius))
        val control = ChartBufferRaster.capture(raster, camera, false)
        val reserve = ChartBufferRaster.capture(raster, camera, true)
        assertEquals(.1, chartBufferUncoveredFraction(camera, target, visible, control)!!, .000001)
        assertEquals(0.0, chartBufferUncoveredFraction(camera, target, visible, reserve)!!, .000001)
        assertEquals(2000, control.producerCamera.width); assertEquals(2500, reserve.producerCamera.width)
        assertEquals(camera, reserve.producerCamera.copy(width = 2000, height = 1070))
    }
    @Test fun cropAndCameraComposeForPanZoomRotationAroundSameVisibleLandmark() {
        val rawLandmark = ScreenPoint(1300.0, 690.0)
        val landmark = chartRasterCrop(raster).map(texturePoint(rawLandmark.x, rawLandmark.y))
        for (target in listOf(camera.copy(latitude = 42.361, longitude = -71.045),
            camera.copy(metresToPixels = .21),
            camera.copy(latitude = 42.359, longitude = -71.038, rotationRadians = PI / 5))) {
            val matrix = chartBufferCameraAffine(camera, target, visible, ChartBufferRaster.capture(raster, camera, true))!!
            assertPoint(chartCameraAffine(camera, target, visible)!!.map(landmark), matrix.map(texturePoint(rawLandmark.x, rawLandmark.y)))
        }
    }
    @Test fun wrongRasterBasisIsRejectedRatherThanAttachedToAnotherCamera() {
        val wrong = ChartBufferRaster.capture(raster, camera.copy(longitude = -72.0), true)
        assertNull(chartBufferCameraAffine(camera, camera, visible, wrong))
        assertNull(chartBufferUncoveredFraction(camera, camera, visible, wrong))
    }
}
