package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class SonardPresentationTest {
    @Test fun trackEvidenceIsBoundedAndFrozenWithItsOwnPing() {
        val track=SonarTrack(7,40.0,.4,"fish_like","rules_v1","cfar")
        val first=SonarPresentation(1,SonarAnalysisQuality.SEARCHING,null,"auto","auto_raw",false,false,null,false,listOf(track),2,1)
        val model=SonarModel{1_000_000L};model.connect("SONARD");model.acquisition(true)
        model.setAnalysis(SonarGeometry(30.0,SonarTxReference.HOST_VERIFIED,1),SonarBottom(),first,true,"0.3.6")
        model.frame(SonarFrame(ShortArray(1024),1,1_000_000L))
        assertFalse(model.display.overlayTargets)
        model.setDisplay(model.display.copy(overlayTargets=true,freeze=true))
        val next=first.copy(pingId=2,tracks=emptyList(),trackTotal=0,trackOmitted=0)
        model.setAnalysis(SonarGeometry(30.0,SonarTxReference.HOST_VERIFIED,1),SonarBottom(),next,true,"0.3.6")
        model.frame(SonarFrame(ShortArray(1024),2,1_000_001L))
        assertEquals(first,model.viewPresentation);assertEquals(next,model.presentation)
        model.resetAcquisition();assertNull(model.viewPresentation)
        assertThrows(IllegalArgumentException::class.java){first.copy(tracks=List(9){track},trackTotal=9,trackOmitted=0)}
    }
    private fun model() = SonarModel { 100_000L }.also {
        it.connect("SONARD"); it.acquisition(true); it.setCalibration(Calibration(true, 4.0))
    }
    private fun geometry(maximum: Double = 27.0) = SonarGeometry(maximum, SonarTxReference.EMPIRICAL_FALLBACK, 129, 4.0)
    private fun bottom(maximum: Double = 27.0) = SonarBottom(12 / maximum * 1023, .55, .6,
        SonarBottomSource.DIRECT_ENVELOPE_TRACK_RULES_V1, 4, "bottom_like")
    private fun presentation(ping: Long, quality: SonarAnalysisQuality = SonarAnalysisQuality.LOCKED) =
        SonarPresentation(ping, quality, null, "auto", "auto_filter", quality == SonarAnalysisQuality.LOCKED,
            quality == SonarAnalysisQuality.LOCKED, if (quality == SonarAnalysisQuality.LOCKED) 12 * 1.3 / 27 * 1023 else null,
            quality == SonarAnalysisQuality.UNSTABLE)
    private fun frame(ping: Long) = SonarFrame(ShortArray(1024) { 1234 }, ping, 100_000L)

    @Test fun resampledDisplaySpacingIsSeparateFromAdcSpacingAndConfirmedTrackHasNoRawConfidenceThreshold() {
        val m = model()
        assertEquals(.003, m.calibration.metersPerBin!!, 1e-10)
        m.setAnalysis(geometry(), bottom(), presentation(0), true, "0.3.5"); m.frame(frame(0))
        assertEquals(27 / 1023.0, m.metersPerBin!!, 1e-10)
        assertEquals(12.0, m.depth()!!, 1e-10)
        assertTrue(m.bottomValid); assertEquals("0.3.5", m.hostPackageVersion)
        assertEquals(4.0, m.geometry!!.adcSamplePeriodUs!!, .0)
    }

    @Test fun empiricalReferenceIsNotChangedIntoCalibratedTimingEvidence() {
        val m = model(); m.setCalibration(Calibration())
        m.setAnalysis(geometry(), bottom(), presentation(0), true); m.frame(frame(0))
        assertFalse(m.calibration.valid)
        assertEquals(SonarTxReference.EMPIRICAL_FALLBACK, m.geometry!!.txReference)
        assertEquals(12.0, m.depth()!!, 1e-10)
    }

    @Test fun unverifiedGeometryNeverFallsBackToAnOtherwiseValidAdcCalibration() {
        val m = model()
        m.setAnalysis(SonarGeometry(null, SonarTxReference.UNVERIFIED), SonarBottom(), null, true)
        m.frame(frame(0))
        assertNull(m.metersPerBin); assertNull(m.depth()); assertFalse(m.calibrated)
        assertEquals(1234, m.latest!!.sample(400))
        assertThrows(IllegalArgumentException::class.java) { m.setDisplay(m.display.copy(rangeM = 3.0)) }
    }

    @Test fun unstableAnalysisHoldsAutoRangeButDoesNotFreezeOrStopRawAcquisition() {
        val m = model(); m.setAnalysis(geometry(), bottom(), presentation(0), true); m.frame(frame(0))
        m.setDisplay(m.display.copy(rangeMode = SonarRangeMode.AUTO, overlayBottom = true))
        val end = m.display.cropEnd
        m.setAnalysis(geometry(), SonarBottom(), presentation(1, SonarAnalysisQuality.UNSTABLE), true); m.frame(frame(1))
        assertEquals(end, m.display.cropEnd, 0.0); assertEquals(SonarRangeMode.AUTO, m.display.rangeMode)
        assertNull(m.depth()); assertTrue(m.running); assertFalse(m.display.freeze)
        assertEquals(2, m.ring.count)
        m.setDisplay(m.display.copy(gainDb = 6.0)); assertEquals(end, m.display.cropEnd, .0)
        assertTrue(m.display.overlayBottom)
    }

    @Test fun frozenHistoryRetainsItsOwnDisplayGeometryWhenLiveRangeChanges() {
        val m = model(); m.setAnalysis(geometry(), bottom(), presentation(0), true); m.frame(frame(0))
        m.setDisplay(m.display.copy(rangeM = 10.0)); m.setFreeze(true)
        m.setAnalysis(geometry(30.0), bottom(30.0), presentation(1), true); m.frame(frame(1))
        assertEquals(27 / 1023.0, m.viewMetersPerBin!!, 1e-10)
        assertEquals(30 / 1023.0, m.metersPerBin!!, 1e-10)
        assertTrue(m.viewBottomValid); assertEquals(1, m.viewRing.count); assertEquals(2, m.ring.count)
        m.setFreeze(false)
        assertNull(m.display.rangeM); assertEquals(1023.0, m.display.cropEnd, .0)
        assertEquals(30 / 1023.0, m.viewMetersPerBin!!, 1e-10)
    }

    @Test fun aPresentationCannotBeReusedUnderANewPingId() {
        val m = model(); m.setAnalysis(geometry(), bottom(), presentation(0), true)
        assertThrows(IllegalArgumentException::class.java) { m.frame(frame(1)) }
        assertEquals(0, m.ring.count)
    }

    @Test fun unknownAnalysisDoesNotRetainThePreviousBottomOrPhysicalDepth() {
        val m = model(); m.setAnalysis(geometry(), bottom(), presentation(0), true); m.frame(frame(0))
        m.setAnalysis(geometry(), SonarBottom(), null, true); m.frame(frame(1))
        assertNull(m.depth()); assertNull(m.presentation); assertFalse(m.bottomValid)
        assertEquals(27 / 1023.0, m.metersPerBin!!, 1e-10)
    }
}
