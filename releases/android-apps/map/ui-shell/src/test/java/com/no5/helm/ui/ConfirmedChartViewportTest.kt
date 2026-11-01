package com.no5.helm.ui

import com.no5.helm.chart.ChartFrame
import com.no5.helm.chart.ChartScale
import com.no5.helm.chart.ChartSurfaceGeometry
import com.no5.helm.chart.ChartViewport
import org.junit.Assert.*
import org.junit.Test

class ConfirmedChartViewportTest {
    private fun frame(revision: Long, token: String = "camera:1",
        geometry: ChartSurfaceGeometry = ChartSurfaceGeometry.fit(2000, 1070)) =
        ChartFrame(revision, token, geometry, ChartViewport(), ChartScale(120.0, 1000.0))

    @Test fun querySnapshotUsesTheExactConfirmedFrameRevisionAndCameraToken() {
        val first = confirmedChartViewport(frame(3))!!
        val next = confirmedChartViewport(frame(4, "camera:2"))!!
        assertEquals(3L, first.revision)
        assertEquals("camera:1", first.cameraToken)
        assertEquals(4L, next.revision)
        assertEquals("camera:2", next.cameraToken)
        assertNotEquals(first, next)
    }

    @Test fun replacementSurfaceUsesItsActualSurfacePixels() {
        val current = confirmedChartViewport(frame(5, geometry = ChartSurfaceGeometry.fit(1872, 816)))!!
        assertEquals(1872, current.widthPx)
        assertEquals(816, current.heightPx)
    }

    @Test fun pendingFrameHasNoQueryAuthorityAndMissingScaleDoesNotInventOrRevokeIt() {
        assertNull(confirmedChartViewport(null))
        assertEquals(confirmedChartViewport(frame(1)), confirmedChartViewport(frame(1).copy(scale = null)))
    }
}
