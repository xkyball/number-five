package com.no5.helm.ui

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ChartBufferAffineTest {
    // Independent geographic-feature projection, rather than checking the implementation's coefficients.
    private fun project(v: ChartViewport, lat: Double, lon: Double, g: ChartSurfaceGeometry): ScreenPoint {
        val radius = 6378137.0 * .9996
        val delta = atan2(sin(Math.toRadians(lon - v.longitude)), cos(Math.toRadians(lon - v.longitude)))
        val east = radius * delta
        val north = radius * ln(tan(PI / 4 + Math.toRadians(lat) / 2) / tan(PI / 4 + Math.toRadians(v.latitude) / 2))
        return g.toSurface(ScreenPoint(v.width / 2.0 + v.metresToPixels * (east * cos(v.rotationRadians) + north * sin(v.rotationRadians)),
            v.height / 2.0 + v.metresToPixels * (east * sin(v.rotationRadians) - north * cos(v.rotationRadians))))
    }
    @Test fun geographicFeaturesFollowCombinedPanZoomRotationIncludingCappedPortrait() {
        for (g in listOf(ChartSurfaceGeometry.fit(2000, 1070), ChartSurfaceGeometry.fit(1200, 2000))) {
            val base = ChartViewport(42.36, -71.04, .15, .24, g.frameWidth, g.frameHeight)
            val target = transformChartViewport(base, ScreenPoint(base.width * .37, base.height * .43), ScreenPoint(110.0, -55.0), 1.35, .63)
            val affine = chartBufferAffine(base, target, g)!!
            for ((lat, lon) in listOf(42.36 to -71.04, 42.361 to -71.038, 42.357 to -71.043)) {
                val expected = project(target, lat, lon, g)
                val actual = affine.map(project(base, lat, lon, g))
                assertEquals(expected.x, actual.x, 1e-6); assertEquals(expected.y, actual.y, 1e-6)
            }
        }
    }
    @Test fun newOlderBufferRebasesToCurrentTargetWithoutCameraJump() {
        val g = ChartSurfaceGeometry.fit(2000, 1070)
        val first = ChartViewport(42.36, -71.04, .15, width = g.frameWidth, height = g.frameHeight)
        val older = transformChartViewport(first, pan = ScreenPoint(80.0, -20.0), zoom = 1.1)
        val target = transformChartViewport(older, pan = ScreenPoint(-35.0, 100.0), zoom = 1.2, rotation = -.5)
        val geo = 42.362 to -71.042
        val fromFirst = chartBufferAffine(first, target, g)!!.map(project(first, geo.first, geo.second, g))
        val fromOlder = chartBufferAffine(older, target, g)!!.map(project(older, geo.first, geo.second, g))
        assertEquals(fromFirst.x, fromOlder.x, 1e-6); assertEquals(fromFirst.y, fromOlder.y, 1e-6)
    }
    @Test fun datelineUsesShortContinuousMercatorOffset() {
        val g = ChartSurfaceGeometry.fit(1000, 600)
        val base = ChartViewport(0.0, 179.99, .02, width = 1000, height = 600)
        val target = base.copy(longitude = -179.99, rotationRadians = PI / 2)
        val actual = chartBufferAffine(base, target, g)!!.map(project(base, .002, 179.999, g))
        val expected = project(target, .002, 179.999, g)
        assertEquals(expected.x, actual.x, 1e-6); assertEquals(expected.y, actual.y, 1e-6)
    }
    @Test fun equalCameraIsIdentityAndInvalidGeometryDoesNotProduceAMatrix() {
        val g = ChartSurfaceGeometry.fit(1000, 600)
        val v = ChartViewport(42.36, -71.04, .15, .4, 1000, 600)
        val point = ScreenPoint(17.0, 580.0)
        val mapped = chartBufferAffine(v, v, g)!!.map(point)
        assertEquals(point.x, mapped.x, 1e-9); assertEquals(point.y, mapped.y, 1e-9)
        assertNull(chartBufferAffine(v, v.copy(metresToPixels = Double.NaN), g))
        assertNull(chartBufferAffine(v, v.copy(height = 599), g))
    }
}
