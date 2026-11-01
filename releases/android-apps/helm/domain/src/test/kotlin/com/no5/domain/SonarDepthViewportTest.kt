package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class SonarDepthViewportTest {
    @Test fun defaultPreservesLegacyBinCropsAndMetricBounds() {
        val d=SonarDisplay(cropStart=512.0,cropEnd=1023.0)
        assertEquals(SonarDepthViewport(512.0,1023.0),sonarDepthViewport(d,null))
        assertEquals(sonarDepthViewport(d,null),sonarDepthViewport(d,.01))
    }
    @Test fun centimetersAndTwentyMetersArePhysicalUpperEdges() {
        val d=SonarDisplay(startDepthM=.3,cropStart=512.0,cropEnd=800.0)
        assertEquals(SonarDepthViewport(30.0,800.0),sonarDepthViewport(d,.01))
        val deep=sonarDepthViewport(d.copy(startDepthM=20.0),.05)
        assertEquals(400.0,deep.startBin,1e-9);assertEquals(800.0,deep.endBin,0.0)
        assertEquals(200.0,sonarDepthViewport(d.copy(startDepthM=20.0),.1).startBin,1e-9)
    }
    @Test fun autoKeepsMinimumSpanBelowFixedStartAndHonorsAvailableTail() {
        val d=SonarDisplay(rangeMode=SonarRangeMode.AUTO,startDepthM=20.0,cropEnd=100.0)
        assertEquals(SonarDepthViewport(400.0,432.0),sonarDepthViewport(d,.05))
        assertEquals(SonarDepthViewport(1010.0,1023.0),sonarDepthViewport(d.copy(startDepthM=50.5),.05))
        assertEquals(.3,sonarDepthViewport(d.copy(startDepthM=.3,cropEnd=900.0),.01).startBin*.01,1e-12)
    }
    @Test fun missingCalibrationAndInsufficientCaptureAreExplicitNotShallowFallbacks() {
        val d=SonarDisplay(startDepthM=20.0)
        assertEquals(SonarViewportState.UNCALIBRATED,sonarDepthViewport(d,null).state)
        assertEquals(SonarViewportState.UNCALIBRATED,sonarDepthViewport(d,Double.NaN).state)
        assertEquals(SonarViewportState.OUTSIDE_CAPTURE,sonarDepthViewport(d,.01).state)
        assertEquals(SonarViewportState.OUTSIDE_CAPTURE,sonarDepthViewport(d.copy(startDepthM=10.23),.01).state)
        assertTrue(sonarDepthViewport(d,.05).available)
    }
    @Test fun manualEndCannotReverseTheAxisAndZoomNeverCrossesStart() {
        val d=SonarDisplay(startDepthM=2.0,cropEnd=150.0)
        assertEquals(SonarViewportState.EMPTY_MANUAL_RANGE,sonarDepthViewport(d,.01).state)
        val zoom=sonarDepthViewport(d.copy(cropEnd=800.0,zoom=true),.01,0.0)
        assertEquals(SonarDepthViewport(200.0,500.0),zoom)
        val lower=sonarDepthViewport(d.copy(cropEnd=800.0,zoom=true),.01,1023.0)
        assertEquals(SonarDepthViewport(500.0,800.0),lower)
    }
    @Test fun invalidStartValuesAreRejectedBeforeMutatingDisplay() {
        for(v in listOf(-.01,Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY))
            assertThrows(IllegalArgumentException::class.java){SonarDisplay(startDepthM=v)}
    }
    @Test fun changingViewportKeepsRawDepthAnalysisAndFreezeHistory() {
        val m=SonarModel{100000L};m.fixture(SonarFixture.CALIBRATED)
        val ring=m.ring;val samples=m.latest!!.samples;val depth=m.depth();val bottom=m.bottom
        val count=ring.count;val bytes=ring.allocatedSampleBytes
        m.setFreeze(true);val held=m.viewRing.pingIdAt(0)
        m.setDisplay(m.display.copy(startDepthM=.3))
        assertEquals(.3,m.viewDisplay.startDepthM,0.0);assertSame(ring,m.ring)
        assertEquals(count,ring.count);assertEquals(bytes,ring.allocatedSampleBytes)
        assertEquals(held,m.viewRing.pingIdAt(0));assertArrayEquals(samples,m.latest!!.samples)
        assertEquals(depth,m.depth());assertEquals(bottom,m.bottom)
        m.setFreeze(false);assertEquals(.3,m.display.startDepthM,0.0)
    }
}
