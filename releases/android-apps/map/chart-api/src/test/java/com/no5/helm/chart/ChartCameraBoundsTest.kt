package com.no5.helm.chart

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ChartCameraBoundsTest {
    private fun region(s: Double=-40.0,n: Double=-10.0,w: Double=0.0,e: Double=50.0) =
        ChartCameraBounds.from(ChartNavigationBounds(1,listOf(ChartCellExtent("A",s,n,w,e))))!!
    private val view = ChartViewport(-25.0,25.0,.01,0.0,2000,1070)

    @Test fun everyRotationFitsWholeExtentAndCannotZoomPastOverview() {
        for (degrees in listOf(0.0,30.0,45.0,90.0)) {
            val r=region();val v=view.copy(rotationRadians=Math.toRadians(degrees));val full=r.overview(v)
            val limited=r.constrain(full.copy(metresToPixels=full.metresToPixels/4)).effective!!
            assertEquals(full,limited)
            val geometry=ChartSurfaceGeometry.fit(v.width,v.height)
            for ((lat,lon) in listOf(-40.0 to 0.0,-40.0 to 50.0,-10.0 to 0.0,-10.0 to 50.0)) {
                val point=chartCameraAffine(full.copy(latitude=lat,longitude=lon),full,geometry)!!.map(ScreenPoint(1000.0,535.0))
                assertTrue("$degrees: $point",point.x>=-1e-6&&point.x<=2000+1e-6&&point.y>=-1e-6&&point.y<=1070+1e-6)
            }
        }
    }
    @Test fun zoomAtNorthEdgeMovesCameraSouthWhileMoreMaterialExists() {
        val r=region();val edge=r.constrain(view.copy(latitude=-10.0)).effective!!
        val next=r.constrain(edge.copy(metresToPixels=edge.metresToPixels/2),edge).effective!!
        assertTrue(next.latitude<edge.latitude);assertEquals(edge.metresToPixels/2,next.metresToPixels,0.0)
    }
    @Test fun allEdgesClampIdempotentlyWithoutChangingRotationOrInteriorCamera() {
        val r=region()
        assertEquals(view,r.constrain(view).effective)
        for (angle in listOf(0.0,.4,PI/2)) for (lat in listOf(-80.0,-10.0,70.0)) for (lon in listOf(-100.0,25.0,150.0)) {
            val wanted=view.copy(latitude=lat,longitude=lon,rotationRadians=angle)
            val a=r.constrain(wanted).effective!!;assertEquals(angle,a.rotationRadians,0.0)
            assertEquals(a,r.constrain(a).effective)
        }
    }
    @Test fun overviewRotationRefitsButDetailRotationKeepsItsScale() {
        val r=region();val full=r.overview(view);val rotated=r.constrain(full.copy(rotationRadians=PI/2),full).effective!!
        assertEquals(r.overviewScale(2000,1070,PI/2),rotated.metresToPixels,0.0)
        val detail=view.copy(rotationRadians=PI/2);assertEquals(view.metresToPixels,r.constrain(detail,view).effective!!.metresToPixels,0.0)
    }
    @Test fun datelineAndCellOrderingDoNotInventWorldWideBounds() {
        val cells=listOf(ChartCellExtent("A",10.0,20.0,175.0,179.0),ChartCellExtent("B",10.0,20.0,-179.0,-175.0))
        val a=ChartCameraBounds.from(ChartNavigationBounds(1,cells))!!;val b=ChartCameraBounds.from(ChartNavigationBounds(1,cells.reversed()))!!
        assertEquals(a.overview(view),b.overview(view));assertEquals(180.0,abs(a.overview(view).longitude),1e-8)
        assertTrue(a.contains(15.0,179.5));assertTrue(a.contains(15.0,-179.5));assertFalse(a.contains(15.0,0.0))
    }
    @Test fun irregularFormatsAndUnknownBoundsDoNotUseADeviceAspectConstant() {
        assertNull(ChartCameraBounds.from(null));assertNull(ChartCameraBounds.from(ChartNavigationBounds(1,emptyList())))
        val r=region();val wide=r.overview(view);val tall=r.overview(view.copy(width=1070,height=2000))
        assertNotEquals(wide.metresToPixels,tall.metresToPixels)
        assertNull(r.constrain(view.copy(rotationRadians=Double.NaN)).effective)
    }
}
