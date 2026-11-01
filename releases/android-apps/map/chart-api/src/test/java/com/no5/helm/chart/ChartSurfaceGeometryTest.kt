package com.no5.helm.chart

import org.junit.Assert.*
import org.junit.Test

class ChartSurfaceGeometryTest {
    @Test fun targetSizesAreOneToOne() {
        for (height in listOf(1116, 1200)) {
            val geometry = ChartSurfaceGeometry.fit(2000, height)
            assertEquals(2000, geometry.frameWidth)
            assertEquals(height, geometry.frameHeight)
            assertEquals(1.0, geometry.scale, 0.0)
            assertEquals(ScreenPoint(123.0, 456.0), geometry.toFrame(ScreenPoint(123.0, 456.0)))
        }
    }

    @Test fun oversizedAndPortraitFramesStayBoundedAndIsotropic() {
        for ((w, h) in listOf(3840 to 2160, 1200 to 2000, 5001 to 1777, 4096 to 1499, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val g = ChartSurfaceGeometry.fit(w, h)
            assertTrue(g.frameWidth in 1..4096 && g.frameHeight in 1..1499)
            val origin = g.toSurface(ScreenPoint(0.0, 0.0))
            val x = g.toSurface(ScreenPoint(100.0, 0.0))
            val y = g.toSurface(ScreenPoint(0.0, 100.0))
            assertEquals(x.x - origin.x, y.y - origin.y, 1e-6)
            assertTrue(g.left >= 0 && g.top >= 0)
            val point = ScreenPoint(g.frameWidth * .37, g.frameHeight * .61)
            val roundTrip = g.toFrame(g.toSurface(point))
            assertEquals(point.x, roundTrip.x, 1e-8)
            assertEquals(point.y, roundTrip.y, 1e-8)
        }
    }

    @Test fun invalidSizesAreRejected() {
        for ((w, h) in listOf(0 to 1200, 2000 to 0, -1 to 1, 1 to -1)) {
            assertThrows(IllegalArgumentException::class.java) { ChartSurfaceGeometry.fit(w, h) }
        }
    }
}
