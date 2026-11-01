package com.no5.helm.engine

import com.no5.helm.chart.ChartViewport
import org.junit.Assert.*
import org.junit.Test

class ChartResidentPoseTest {
    @Test fun producerReserveExtentIsRequiredInsteadOfVisibleCanvas() {
        val producer = ChartViewport(42.36, -71.04, .06, .63, 2500, 1338)
        assertTrue(residentPoseMatches(producer, producer))
        assertFalse(residentPoseMatches(producer.copy(width = 2000, height = 1070), producer))
    }
    @Test fun equivalentLongitudeRotationAndFloatingPointRepresentationsAreAccepted() {
        val producer = ChartViewport(42.36, 180.0, .06, .63 + 2 * Math.PI, 2500, 1338)
        val planned = producer.copy(longitude = -180.0, rotationRadians = .63)
        assertTrue(residentPoseMatches(planned, producer))
        assertTrue(residentPoseMatches(planned.copy(rotationRadians = Math.nextUp(.63)), planned))
    }
    @Test fun changedPanZoomRotationOrCanvasRejectsObsoletePlan() {
        val producer = ChartViewport(42.36, -71.04, .06, .63, 2500, 1338)
        for (old in listOf(producer.copy(latitude = 42.360001), producer.copy(longitude = -71.040001),
            producer.copy(metresToPixels = .060001), producer.copy(rotationRadians = .630001),
            producer.copy(width = 2499), producer.copy(height = 1337))) assertFalse(residentPoseMatches(old, producer))
    }
}
