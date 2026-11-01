package com.no5.helm.ui

import com.no5.helm.chart.ChartViewport
import com.no5.helm.chart.ScreenPoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ChartNavigationTest {
    private val initial = ChartViewport(latitude = -32.5, longitude = 60.98, metresToPixels = 1.0)

    // Independent screen projection of a fixed geographic feature.
    private fun project(view: ChartViewport, lat: Double, lon: Double): ScreenPoint {
        val radius = 6378137.0 * 0.9996
        val east = Math.toRadians(lon - view.longitude) * radius
        val north = (ln(tan(PI / 4 + Math.toRadians(lat) / 2)) - ln(tan(PI / 4 + Math.toRadians(view.latitude) / 2))) * radius
        return ScreenPoint(view.width / 2.0 + (east * cos(view.rotationRadians) + north * sin(view.rotationRadians)) * view.metresToPixels,
            view.height / 2.0 + (east * sin(view.rotationRadians) - north * cos(view.rotationRadians)) * view.metresToPixels)
    }

    @Test fun buttonZoomChangesScaleWithoutMovingCenter() {
        val zoomed = transformChartViewport(initial, zoom = 2.0)
        assertEquals(2.0, zoomed.metresToPixels, 0.0)
        assertEquals(initial.latitude, zoomed.latitude, 1e-9)
        assertEquals(initial.longitude, zoomed.longitude, 1e-9)
        val restored = transformChartViewport(zoomed, zoom = 0.5)
        assertEquals(initial.latitude, restored.latitude, 1e-9)
        assertEquals(initial.longitude, restored.longitude, 1e-9)
        assertEquals(initial.metresToPixels, restored.metresToPixels, 0.0)
    }

    @Test fun pinchAndWheelKeepFeatureAtPointer() {
        val lat = initial.latitude + 0.001
        val lon = initial.longitude + 0.002
        val anchor = project(initial, lat, lon)
        val zoomed = transformChartViewport(initial, anchor = anchor, zoom = 2.0)
        val after = project(zoomed, lat, lon)
        assertEquals(anchor.x, after.x, 1e-6)
        assertEquals(anchor.y, after.y, 1e-6)
    }

    @Test fun dragFollowsFingerAfterRotatedPinch() {
        val view = initial.copy(rotationRadians = PI / 3)
        val lat = initial.latitude + 0.001
        val lon = initial.longitude + 0.002
        val anchor = project(view, lat, lon)
        val after = project(transformChartViewport(view, anchor, ScreenPoint(120.0, -70.0), 1.6, PI / 4), lat, lon)
        assertEquals(anchor.x + 120.0, after.x, 1e-6)
        assertEquals(anchor.y - 70.0, after.y, 1e-6)
    }

    @Test fun zoomLimitDoesNotMovePointerAnchor() {
        val view = initial.copy(metresToPixels = 8.0)
        val anchor = project(view, -32.4998, 60.9803)
        val zoomed = transformChartViewport(view, anchor = anchor, zoom = 2.0)
        assertEquals(10.0, zoomed.metresToPixels, 0.0)
        val after = project(zoomed, -32.4998, 60.9803)
        assertEquals(anchor.x, after.x, 1e-6)
        assertEquals(anchor.y, after.y, 1e-6)
    }

    @Test fun repeatedZoomAtBothLimitsKeepsExactCameraWithoutDrift() {
        for ((scale, zoom) in listOf(10.0 to 2.0, 0.00001 to 0.5)) {
            val basis = initial.copy(metresToPixels = scale, rotationRadians = 0.317)
            var current = basis
            repeat(1000) { current = transformChartViewport(current, ScreenPoint(123.4, 567.8), zoom = zoom) }
            assertSame(basis, current)
        }
    }
}
