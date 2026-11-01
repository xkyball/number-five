package com.no5.helm.ui
import com.no5.helm.chart.ChartViewport
import com.no5.preparation.ui.*
import org.junit.Assert.*
import org.junit.Test
class RouteGeometryTest {
    @Test fun equatorUsesWgs84AndAntimeridianUsesShortArc() {
        assertEquals(111319.490793, routeLegMetres(RoutePoint(0.0, 0.0), RoutePoint(0.0, 1.0)), .001)
        assertEquals(22263.898159, routeLegMetres(RoutePoint(0.0, 179.9), RoutePoint(0.0, -179.9)), .001)
        assertEquals(0.0, routeLegMetres(RoutePoint(30.0, 180.0), RoutePoint(30.0, -180.0)), 0.0)
    }
    @Test fun fitDoesNotZoomOutAcrossWholeWorldAtDateLine() {
        val route = RouteDocument("r", "R", frozenListOf(RoutePoint(20.0, 179.9), RoutePoint(20.1, -179.9)))
        val fitted = routeFitViewport(route, ChartViewport(0.0, 0.0, .1, width = 1000, height = 600))!!
        assertTrue(kotlin.math.abs(fitted.longitude) > 179.9); assertTrue(fitted.metresToPixels > .01)
    }
}
