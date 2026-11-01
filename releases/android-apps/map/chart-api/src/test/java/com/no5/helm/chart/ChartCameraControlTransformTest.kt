package com.no5.helm.chart

import org.junit.Assert.*
import org.junit.Test

class ChartCameraControlTransformTest {
    @Test fun sharedTransformKeepsTheFingerAnchorFixedAcrossSurfaceSizesAndRotation() {
        for(geometry in listOf(ChartSurfaceGeometry.fit(2000,1200),ChartSurfaceGeometry.fit(1200,2000))) {
            val camera=ChartViewport(-33.9,18.4,.05,.7,geometry.frameWidth,geometry.frameHeight)
            val anchor=ScreenPoint(camera.width*.37,camera.height*.42)
            val pan=ScreenPoint(84.0,-37.0)
            val target=transformChartCamera(camera,anchor,pan,1.25,-.2)
            val displayed=chartCameraAffine(camera,target,geometry)!!.map(geometry.toSurface(anchor))
            val expected=geometry.toSurface(ScreenPoint(anchor.x+pan.x,anchor.y+pan.y))
            assertEquals(expected.x,displayed.x,1e-6);assertEquals(expected.y,displayed.y,1e-6)
        }
    }
    @Test fun repeatedBoundedZoomDoesNotDriftAndDoesNotNeedAnErrorResult() {
        for((scale,factor) in listOf(10.0 to 1.25,.00001 to .8)) {
            val home=ChartViewport(-33.9,18.4,scale,.7)
            var camera=home
            repeat(1000){camera=transformChartCamera(camera,ScreenPoint(123.0,426.0),zoom=factor)}
            assertSame(home,camera)
        }
    }
}
