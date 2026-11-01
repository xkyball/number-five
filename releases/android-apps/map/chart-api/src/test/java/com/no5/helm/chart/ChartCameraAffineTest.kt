package com.no5.helm.chart

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

/** Analytic footprint cases; no clipping algorithm is duplicated in these expectations. */
class ChartCameraAffineTest {
    private val geometry = ChartSurfaceGeometry.fit(1000, 600)
    private val home = ChartViewport(42.36, -71.04, .15, width = 1000, height = 600)
    private val mercatorRadius = 6378137.0 * .9996

    @Test fun identicalPoseCoversFullBufferIncludingCappedPortraitSurface() {
        for (fitted in listOf(geometry, ChartSurfaceGeometry.fit(2000, 1070), ChartSurfaceGeometry.fit(1200, 2000))) {
            val camera = home.copy(width = fitted.frameWidth, height = fitted.frameHeight, rotationRadians = .6)
            assertEquals(0.0, chartBufferUncoveredFraction(camera, camera, fitted)!!, 1e-12)
            val point = ScreenPoint(21.0, fitted.surfaceHeight - 17.0)
            val mapped = chartCameraAffine(camera, camera, fitted)!!.map(point)
            assertEquals(point.x, mapped.x, 1e-9); assertEquals(point.y, mapped.y, 1e-9)
        }
    }

    @Test fun centeredZoomOutHasTheKnownSquaredAreaRatioAndZoomInCoversTheViewport() {
        assertEquals(.5775, chartBufferUncoveredFraction(home, home.copy(metresToPixels = home.metresToPixels * .65), geometry)!!, 1e-12)
        assertEquals(0.0, chartBufferUncoveredFraction(home, home.copy(metresToPixels = home.metresToPixels * 2.0), geometry)!!, 1e-12)
    }

    @Test fun horizontalPanLeavesOneKnownStripAndPanBeyondWidthLeavesNoBuffer() {
        val shift200 = Math.toDegrees(200.0 / (home.metresToPixels * mercatorRadius))
        val target = home.copy(longitude = home.longitude - shift200)
        assertEquals(.2, chartBufferUncoveredFraction(home, target, geometry)!!, 1e-10)
        val shift1200 = Math.toDegrees(1200.0 / (home.metresToPixels * mercatorRadius))
        assertEquals(1.0, chartBufferUncoveredFraction(home, home.copy(longitude = home.longitude - shift1200), geometry)!!, 1e-12)
        // With a quarter-turn camera the same eastward world displacement exposes a vertical strip.
        val rotated = home.copy(rotationRadians = PI / 2)
        assertEquals(200.0 / 600.0, chartBufferUncoveredFraction(rotated, target.copy(rotationRadians = PI / 2), geometry)!!, 1e-10)
    }

    @Test fun quarterTurnRectangleAndDiagonalSquareHaveAnalyticIntersectionAreas() {
        // The 600×1000 rotated rectangle intersects the 1000×600 viewport in a 600×600 square.
        assertEquals(.4, chartBufferUncoveredFraction(home, home.copy(rotationRadians = PI / 2), geometry)!!, 1e-12)
        assertEquals(0.0, chartBufferUncoveredFraction(home, home.copy(rotationRadians = PI), geometry)!!, 1e-12)
        val square = ChartSurfaceGeometry.fit(1000, 1000)
        val camera = home.copy(width = 1000, height = 1000)
        // Four equal corner triangles are uncovered by a centered square turned through 45 degrees.
        assertEquals(3.0 - 2.0 * sqrt(2.0), chartBufferUncoveredFraction(camera,
            camera.copy(rotationRadians = PI / 4), square)!!, 1e-12)
    }

    @Test fun datelineCrossingUsesTheShortWorldDistanceInsteadOfDiscardingTheBuffer() {
        val camera = home.copy(longitude = 179.99, metresToPixels = .02)
        val wrapped = camera.copy(longitude = -179.99)
        val continuous = camera.copy(longitude = 180.01)
        val expectedStrip = .02 * mercatorRadius * Math.toRadians(.02) / geometry.surfaceWidth
        assertEquals(expectedStrip, chartBufferUncoveredFraction(camera, wrapped, geometry)!!, 1e-10)
        assertEquals(chartBufferUncoveredFraction(camera, continuous, geometry)!!,
            chartBufferUncoveredFraction(camera, wrapped, geometry)!!, 1e-10)
    }

    @Test fun invalidCameraAndUnrepresentableTransformHaveNoCoverageResult() {
        for (invalid in listOf(home.copy(latitude = 90.0), home.copy(latitude = Double.NaN),
            home.copy(longitude = Double.POSITIVE_INFINITY), home.copy(metresToPixels = 0.0),
            home.copy(rotationRadians = Double.NaN), home.copy(width = 999))) {
            assertNull(chartCameraAffine(home, invalid, geometry))
            assertNull(chartBufferUncoveredFraction(home, invalid, geometry))
            assertNull(chartBufferUncoveredFraction(invalid, home, geometry))
        }
        assertNull(chartBufferUncoveredFraction(home.copy(metresToPixels = 1e-300), home.copy(metresToPixels = 1e300), geometry))
    }
}
