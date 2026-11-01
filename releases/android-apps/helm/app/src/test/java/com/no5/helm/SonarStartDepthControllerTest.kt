package com.no5.helm

import com.no5.domain.*
import com.no5.helm.gpu.sonarSceneRequest
import org.junit.Assert.*
import org.junit.Test

class SonarStartDepthControllerTest {
    @Test fun rangeKnobStartsAtTheEffectiveEndAndCannotMoveAboveTheFixedStart() {
        val source=DeterministicMockSonarSource(now={1000000L});val c=SonarNativeController(source){1000000L}
        try {
            c.model.connect("Range knob fixture");c.model.acquisition(true)
            c.model.setAnalysis(SonarGeometry(30.69,SonarTxReference.HOST_VERIFIED),SonarBottom(33.0,.9),null,true,
                amplitude=SonarAmplitude(4095,12,true))
            c.model.frame(SonarFrame(ShortArray(1024),1,1000000L));c.refresh()
            c.autoRange(true);c.startDepth(20.0);c.display{it.copy(cropEnd=50.0)}
            assertEquals(20.96,c.bounds().end*c.model.viewMetersPerBin!!,1e-9)
            c.adjustRange(-1)
            assertEquals(26.2,c.bounds().end*c.model.viewMetersPerBin!!,1e-9)
            c.adjustRange(20)
            assertEquals(20.96,c.bounds().end*c.model.viewMetersPerBin!!,1e-9)
            assertEquals(0L,c.controls.range(20.0));assertEquals(20.0,c.snapshot.display.startDepthM,0.0)
            c.startDepth(40.0);assertEquals(0L,c.controls.adjustRange(-1))
        }finally{c.dispose();source.close()}
    }
    @Test fun bothSceneSizesUseOnePhysicalViewportAndKeepRawDepthAndAutoIntent() {
        val source=DeterministicMockSonarSource(now={1000000L});val c=SonarNativeController(source){1000000L}
        source.loadFixture(SonarFixture.CALIBRATED,SonarAcquisitionRequest(source.identity.sessionId,11))
        try {
            val raw=c.model.latest!!.samples;val ring=c.model.ring;val depth=c.depthDisplay.valueM
            c.autoRange(true);assertTrue(c.startDepth(.3))
            val a=sonarSceneRequest(c,false,null).frame
            val b=sonarSceneRequest(c,false,null,dashboardPreview=true).frame
            assertEquals(a.top,b.top,0.0);assertEquals(a.bottom,b.bottom,0.0)
            assertEquals(.3,a.top*a.metersPerBin!!,1e-9)
            assertSame(a.ring,b.ring);assertSame(ring,a.ring)
            assertEquals(SonarRangeMode.AUTO,c.requestedRangeMode)
            assertEquals(depth,c.depthDisplay.valueM);assertArrayEquals(raw,c.model.latest!!.samples)
            assertFalse(c.startDepth(Double.NaN));assertFalse(c.startDepth(-1.0))
            assertFalse(c.applyRange(.2));assertEquals(.3,c.snapshot.display.startDepthM,0.0)
            c.startDepth(20.0)
            assertEquals(SonarViewportState.OUTSIDE_CAPTURE,sonarSceneRequest(c,false,null).frame.viewportState)
            c.inspect(.5f,.5f);assertNull(c.cursor)
            assertEquals(20.0,c.exportPresentationState().display.startDepthM,0.0)
            c.startDepth(0.0);assertTrue(c.bounds().available)
        }finally{c.dispose();source.close()}
    }
    @Test fun freezeCanBeRecroppedAndRestoredIntentNeedsNoCalibrationOrNewSamples() {
        val source=DeterministicMockSonarSource(now={1000000L});val c=SonarNativeController(source){1000000L}
        source.loadFixture(SonarFixture.CALIBRATED,SonarAcquisitionRequest(source.identity.sessionId,12))
        val offline=SonarNativeController(UnavailablePiSonarSource(SourceIdentity("next",SourceMode.PI,"test"))){1000000L}
        try {
            c.freeze();val ping=c.model.viewRing.pingIdAt(0);c.startDepth(.3)
            assertTrue(c.snapshot.display.freeze);assertEquals(ping,c.model.viewRing.pingIdAt(0))
            assertEquals(.3,c.bounds().start*c.model.viewMetersPerBin!!,1e-9)
            val saved=c.exportPresentationState();assertEquals(4,saved.version)
            offline.restorePresentationState(saved);assertEquals(.3,offline.snapshot.display.startDepthM,0.0)
            assertFalse(offline.snapshot.display.freeze);assertEquals(0,offline.model.ring.count)
            assertEquals(SonarViewportState.UNCALIBRATED,offline.bounds().state)
            for(version in 1..3){offline.restorePresentationState(saved.copy(version=version));assertEquals(0.0,offline.snapshot.display.startDepthM,0.0)}
        }finally{c.dispose();source.close();offline.dispose()}
    }
}
