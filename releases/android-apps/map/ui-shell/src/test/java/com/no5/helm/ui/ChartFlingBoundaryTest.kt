package com.no5.helm.ui

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ChartFlingBoundaryTest {
    private val geometry = ChartSurfaceGeometry.fit(1000, 600)
    private val basis = ChartViewport(0.0, 0.0, .02, .4, 1000, 600)
    private val origin = ScreenPoint(0.0, 0.0)

    private fun cell(id: String, west: Double, east: Double, south: Double = -10.0, north: Double = 10.0) =
        ChartCellExtent(id, south, north, west, east)
    private fun boundary(vararg cells: ChartCellExtent, view: ChartViewport = basis, fitted: ChartSurfaceGeometry = geometry) =
        ChartFlingBoundary(ChartNavigationBounds(1, cells.toList()), view, fitted)

    /** Independent geographic target converted to the surface displacement of its old camera centre. */
    private fun offset(longitude: Double, latitude: Double = 0.0, view: ChartViewport = basis,
        fitted: ChartSurfaceGeometry = geometry): ScreenPoint {
        val radius = 6378137.0 * .9996
        val east = radius * Math.toRadians(longitude - view.longitude)
        val north = radius * ln(tan(PI / 4 + Math.toRadians(latitude) / 2) /
            tan(PI / 4 + Math.toRadians(view.latitude) / 2))
        val scale = view.metresToPixels * fitted.scale
        return ScreenPoint(-scale * (east * cos(view.rotationRadians) + north * sin(view.rotationRadians)),
            scale * (-east * sin(view.rotationRadians) + north * cos(view.rotationRadians)))
    }

    private fun camera(pan: ChartBoundedPan, view: ChartViewport = basis, fitted: ChartSurfaceGeometry = geometry) =
        ChartFlingCamera(view, fitted).at(pan.offset.x, pan.offset.y)

    @Test fun skippedFrameCannotLeapAcrossGapToAnotherIsland() {
        val limit = boundary(cell("near", -2.0, 2.0), cell("remote", 8.0, 12.0))
        val result = limit.constrain(origin, offset(10.0))
        assertTrue(result.stoppedAtBoundary)
        assertEquals(2.0, camera(result).longitude, 1e-9)
        assertEquals(0.0, camera(result).latitude, 1e-9)
    }

    @Test fun touchingAndOverlappingCellsContinueThroughTheirUnion() {
        val limit = boundary(cell("left", -2.0, 1.0), cell("middle", 1.0, 3.0), cell("right", 2.0, 4.0))
        val inside = limit.constrain(origin, offset(3.0))
        assertFalse(inside.stoppedAtBoundary)
        assertEquals(3.0, camera(inside).longitude, 1e-9)
        val outside = limit.constrain(inside.offset, offset(10.0))
        assertTrue(outside.stoppedAtBoundary)
        assertEquals(4.0, camera(outside).longitude, 1e-9)
    }

    @Test fun diagonalRotatedAndCappedPortraitPanStopsAtTheFirstLatitudeEdge() {
        val fitted = ChartSurfaceGeometry.fit(1200, 2000)
        val view = basis.copy(rotationRadians = -1.2, metresToPixels = .3,
            width = fitted.frameWidth, height = fitted.frameHeight)
        val limit = boundary(cell("box", -10.0, 10.0, -1.0, 1.0), view = view, fitted = fitted)
        val result = limit.constrain(origin, offset(5.0, 5.0, view, fitted))
        assertTrue(result.stoppedAtBoundary)
        val stopped = camera(result, view, fitted)
        assertEquals(1.0, stopped.latitude, 1e-9)
        assertTrue(stopped.longitude in .9..1.1)
        assertEquals(view.rotationRadians, stopped.rotationRadians, 1e-12)
        assertEquals(view.metresToPixels, stopped.metresToPixels, 0.0)
    }

    @Test fun missingEmptyAndInvalidExtentsDoNotInventARegion() {
        assertFalse(boundary().contains())
        assertTrue(boundary().constrain(origin, offset(5.0)).stoppedAtBoundary)
        assertFalse(boundary(cell("nan", Double.NaN, 5.0), cell("line", 0.0, 0.0),
            cell("reversed-lat", -1.0, 1.0, 5.0, -5.0)).contains())
        val from = offset(8.0)
        val outside = boundary(cell("valid", -1.0, 1.0)).constrain(from, offset(9.0))
        assertEquals(from, outside.offset) // Do not pull a camera that is already outside back to the map.
    }

    @Test fun northSouthGapStopsEvenWhenBothEndpointsHaveData() {
        val limit = boundary(cell("near", -10.0, 10.0, -2.0, 2.0), cell("north", -10.0, 10.0, 8.0, 12.0))
        val result = limit.constrain(origin, offset(0.0, 10.0))
        assertTrue(result.stoppedAtBoundary)
        assertEquals(2.0, camera(result).latitude, 1e-9)
    }

    @Test fun diagonalUnionIsClippedAlongTheSegmentRatherThanEachAxisSeparately() {
        val limit = boundary(cell("start", -1.0, 1.0, -1.0, 1.0), cell("upper", 0.5, 3.0, 2.0, 4.0))
        val result = limit.constrain(origin, offset(2.0, 3.0))
        assertTrue(result.stoppedAtBoundary)
        val stopped = camera(result)
        assertEquals(1.0, stopped.latitude, 1e-9)
        assertTrue(stopped.longitude < 1.0) // Independent x clamping would incorrectly advance beyond this gap.
    }

    @Test fun datelineUnionContinuesAcrossWrapAndStopsAtItsActualEnd() {
        val view = basis.copy(longitude = 179.0)
        val limit = boundary(cell("crossing", 170.0, -170.0), view = view)
        assertTrue(limit.contains())
        val inside = limit.constrain(origin, offset(181.0, view = view))
        assertFalse(inside.stoppedAtBoundary)
        assertEquals(-179.0, camera(inside, view).longitude, 1e-9)
        val outside = limit.constrain(inside.offset, offset(200.0, view = view))
        assertTrue(outside.stoppedAtBoundary)
        assertEquals(-170.0, camera(outside, view).longitude, 1e-9)
        assertTrue(boundary(cell("east-edge", 170.0, 180.0), view = view.copy(longitude = -180.0)).contains())
    }

    @Test fun completeLongitudeBandDoesNotAllocateOrStopAtArtificialWorldWraps() {
        val limit = boundary(cell("west", -180.0, 0.0), cell("east", 0.0, 180.0))
        val result = limit.constrain(origin, offset(1080.0))
        assertFalse(result.stoppedAtBoundary)
        assertEquals(0.0, camera(result).longitude, 1e-8)
    }

    @Test fun boundaryAllowsInwardMotionButStopsOutwardWithoutSpringback() {
        val view = basis.copy(longitude = 2.0)
        val limit = boundary(cell("box", -2.0, 2.0), view = view)
        val inward = limit.constrain(origin, offset(1.0, view = view))
        assertFalse(inward.stoppedAtBoundary)
        val outward = limit.constrain(origin, offset(3.0, view = view))
        assertTrue(outward.stoppedAtBoundary)
        assertEquals(origin.x, outward.offset.x, 1e-9)
        assertEquals(origin.y, outward.offset.y, 1e-9)
    }
}
