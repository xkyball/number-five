package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class SonarTest {
    private fun frame(value: Int = 0, id: Long = 0, time: Long = 100_000) = SonarFrame(ShortArray(SONAR_BINS) { value.toShort() }, id, time)
    @Test fun canonicalUnavailableHasNoPhysicalDepth() {
        val m = SonarModel { 100_000 }; assertEquals(SonarQuality.OFFLINE, m.quality()); assertNull(m.depth()); assertFalse(m.calibrated); assertEquals(0, m.ring.count)
    }
    @Test fun exactUint16ShapeAndEndianAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) { SonarFrame(ShortArray(1023), 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { SonarFrame.fromUnsigned(IntArray(1024) { 65536 }, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { SonarFrame.fromLittleEndian(ByteArray(2049), 0, 0) }
        val bytes = ByteArray(2048); bytes[0] = 0xff.toByte(); bytes[1] = 0xff.toByte(); bytes[2] = 0x34; bytes[3] = 0x12
        val f = SonarFrame.fromLittleEndian(bytes, 0, 0); assertEquals(65535, f.sample(0)); assertEquals(0x1234, f.sample(1))
        bytes[0] = 0; val samples = f.samples; samples[0] = 0; assertEquals(65535, f.sample(0))
    }
    @Test fun ringWrapsAndStaysBoundedWithoutAliasing() {
        val r = SonarRingBuffer(3); for (i in 0..4) r.push(frame(i, i.toLong()))
        assertEquals(3, r.count); assertEquals(4, r.atAge(0)!!.sample(0)); assertEquals(2, r.atAge(2)!!.sample(0)); assertNull(r.atAge(3))
        val snapshot = r.copy(); r.push(frame(9, 9)); assertEquals(4, snapshot.sampleAt(0, 0))
        assertThrows(IllegalArgumentException::class.java) { SonarRingBuffer(2049) }
        val full = SonarRingBuffer(); repeat(2050) { full.push(frame(it, it.toLong())) }; assertEquals(2048, full.count); assertEquals(2049L, full.atAge(0)!!.pingId)
    }
    @Test fun gainGammaThresholdDoNotModifyAcquiredSamples() {
        val m = SonarModel { 100_000 }; m.fixture(SonarFixture.SIGNAL); val data = m.latest!!.samples; val head = m.ring.head
        m.setDisplay(m.display.copy(gainDb = 12.0, gamma = .5, threshold = .2))
        assertArrayEquals(data, m.latest!!.samples); assertEquals(head, m.ring.head); assertTrue(m.displayAmplitude(65000) <= 1)
    }
    @Test fun sonard035UsesAdcFullScaleWithoutChangingRawSamplesOrGrantingDepth() {
        val m = SonarModel { 100_000 }
        m.connect("M1"); m.acquisition(true)
        m.setAnalysis(SonarGeometry(null, SonarTxReference.UNVERIFIED), SonarBottom(), null, false, "0.3.5")
        val raw = frame(161, 1)
        m.frame(raw)
        assertEquals(4095, m.amplitudeFullScale)
        assertEquals(161.0 / 4095, m.displayAmplitude(161), 1e-12)
        assertEquals(1.0, m.displayAmplitude(4095), 0.0)
        assertEquals(161, m.ring.sampleAt(0, 0))
        assertArrayEquals(raw.samples, m.latest!!.samples)
        assertNull(m.depth()); assertNull(m.viewMetersPerBin)
    }
    @Test fun frozenAmplitudeScaleSurvivesHostVersionChangeAndLiveHistoryStartsFresh() {
        val m = SonarModel { 100_000 }
        m.connect("M1"); m.acquisition(true)
        m.setAnalysis(null, SonarBottom(), null, false, "0.3.5"); m.frame(frame(4095, 1))
        m.setFreeze(true)
        m.setAnalysis(null, SonarBottom(), null, false, "unknown"); m.frame(frame(4095, 2))
        assertEquals(65535, m.amplitudeFullScale)
        assertEquals(4095, m.viewAmplitudeFullScale)
        assertEquals(1, m.ring.count)
        assertEquals(1.0, m.displayAmplitude(4095, m.viewAmplitudeFullScale), 0.0)
        assertEquals(4095.0 / 65535, m.displayAmplitude(4095), 1e-12)
        m.setFreeze(false)
        assertEquals(65535, m.viewAmplitudeFullScale)
    }
    @Test fun markerOnly036PreservesAdcScaleAndDoesNotRestart035History() {
        val m = SonarModel { 100_000 }
        m.connect("M1"); m.acquisition(true)
        val geometry = SonarGeometry(null, SonarTxReference.UNVERIFIED)
        m.setAnalysis(geometry, SonarBottom(), null, false, "0.3.5"); m.frame(frame(161, 1))
        m.setAnalysis(geometry, SonarBottom(), null, false, "0.3.6"); m.frame(frame(163, 2))
        assertEquals(4095, m.amplitudeFullScale)
        assertEquals(163.0 / 4095, m.displayAmplitude(163), 1e-12)
        assertEquals(2, m.ring.count)
        assertNull(m.depth())
    }
    @Test fun checkedClipReleasesKeepTheOriginalAdcScaleAndRawHistory() {
        val m = SonarModel { 100_000 }
        m.connect("M1"); m.acquisition(true)
        for ((index, version) in listOf("0.3.6", "0.4.5", "0.4.6", "0.4.7", "0.4.8").withIndex()) {
            m.setAnalysis(null, SonarBottom(), null, false, version)
            m.frame(frame(161, (index+1).toLong()))
            assertEquals(4095, m.amplitudeFullScale)
            assertEquals(161.0 / 4095, m.displayAmplitude(161), 1e-12)
            assertEquals(index+1, m.ring.count)
            assertEquals(161, m.ring.sampleAt(0, 0))
        }
        m.setAnalysis(null, SonarBottom(), null, false, "0.4.999")
        assertEquals(65535, m.amplitudeFullScale)
    }
    @Test fun checked049And0410KeepTwelveBitScaleAndDoNotGrantUnknownVersionsThatScale() {
        val model = SonarModel { 100_000 }
        model.connect("M1"); model.acquisition(true)
        for ((index, version) in listOf("0.4.9", "0.4.10").withIndex()) {
            model.setAnalysis(null, SonarBottom(), null, false, version)
            val raw = frame(4095, (index + 1).toLong())
            model.frame(raw)
            assertEquals(4095, model.amplitudeFullScale)
            assertEquals(1.0, model.displayAmplitude(4095), 0.0)
            assertArrayEquals(raw.samples, model.latest!!.samples)
            assertEquals(index + 1, model.ring.count)
            assertNull(model.depth())
        }
        model.setAnalysis(null, SonarBottom(), null, false, "0.4.11")
        assertEquals(65535, model.amplitudeFullScale)
        assertEquals(4095.0 / 65535, model.displayAmplitude(4095), 1e-12)
    }
    @Test fun frozenViewStableWhileAcquisitionAndAScopeContinue() {
        val m = SonarModel { 100_000 }; m.fixture(SonarFixture.SIGNAL); m.setFreeze(true)
        val count = m.viewRing.count; val frozenValue = m.viewRing.sampleAt(0, 0)
        m.frame(frame(4321, 800)); assertEquals(count, m.viewRing.count); assertEquals(count + 1, m.ring.count)
        assertEquals(4321, m.latest!!.sample(0)); assertEquals(frozenValue, m.viewRing.sampleAt(0, 0))
        m.setFreeze(false); assertEquals(count + 1, m.viewRing.count)
    }
    @Test fun stopAndFreezeAreIndependent() {
        val m = SonarModel { 100_000 }; m.fixture(SonarFixture.SIGNAL); m.setFreeze(true); m.acquisition(false)
        assertTrue(m.display.freeze); assertFalse(m.running); assertThrows(IllegalStateException::class.java) { m.frame(frame(id = 800)) }
    }
    @Test fun calibrationDetectorAndConfidenceGateDepthAndAutoRange() {
        val m = SonarModel { 100_000 }; m.fixture(SonarFixture.SIGNAL)
        assertThrows(IllegalArgumentException::class.java) { m.setDisplay(m.display.copy(rangeM = 10.0)) }
        assertThrows(IllegalArgumentException::class.java) { m.setDisplay(m.display.copy(rangeMode = SonarRangeMode.AUTO)) }
        m.fixture(SonarFixture.CALIBRATED); assertEquals(7.645524430762218, m.depth()!!, .00000001)
        m.setDisplay(m.display.copy(rangeMode = SonarRangeMode.AUTO)); m.setBottom(SonarBottom(100.0, .99))
        // A newly shallower result must not snap the axis inward. Missing
        // canonical depth holds this range, rather than inventing a bottom.
        assertTrue(m.display.cropEnd > 130.0)
        val heldRange = m.display.cropEnd
        m.setBottom(SonarBottom(100.0, .4)); assertNull(m.depth())
        assertEquals(SonarRangeMode.AUTO, m.display.rangeMode)
        assertEquals(heldRange, m.display.cropEnd, 0.0)
    }
    @Test fun physicalReferencesRequireTheirExplicitOffsets() {
        val m = SonarModel { 100_000 }; m.fixture(SonarFixture.CALIBRATED); m.setBottom(SonarBottom(100.0, .99))
        m.setCalibration(Calibration(true, 20.0, depthReference = DepthReference.SURFACE)); assertNull(m.depth())
        m.setCalibration(Calibration(true, 20.0, transducerOffsetM = .5, depthReference = DepthReference.SURFACE)); assertEquals(2.0, m.depth()!!, .00001)
        m.setCalibration(Calibration(true, 20.0, keelOffsetM = 2.0, depthReference = DepthReference.KEEL)); assertNull(m.depth())
    }
    @Test fun stoppedStaleAndFutureProfilesNeverLookLikeLiveDepth() {
        var now = 100_000L; val m = SonarModel { now }; m.fixture(SonarFixture.CALIBRATED); assertNotNull(m.depth())
        now += 3000; assertEquals(SonarQuality.DEGRADED, m.quality()); assertNull(m.depth())
        m.acquisition(false); assertNull(m.depth())
        now = 80_000; m.acquisition(true); assertEquals(SonarQuality.DEGRADED, m.quality())
    }
    @Test fun zeroPingTimestampAmplitudeAndDisplayNumbersRetained() {
        val m = SonarModel { 0 }; m.connect("ADAPTER"); m.acquisition(true); m.frame(frame(0, 0, 0))
        assertEquals(0, m.latest!!.sample(0)); assertEquals(0L, m.latest!!.pingId); assertEquals(0L, m.latest!!.timestampEpochMs)
        m.setDisplay(m.display.copy(gainDb = 0.0, threshold = 0.0, cropStart = 0.0)); assertEquals(0.0, m.display.gainDb, 0.0)
    }
    @Test fun duplicateFramesRejectedAndReconnectStartsNewSequence() {
        val m = SonarModel { 100_000 }; m.fixture(SonarFixture.SIGNAL)
        assertThrows(IllegalArgumentException::class.java) { m.frame(frame(id = 799)) }; assertEquals(800, m.ring.count)
        m.frame(frame(id = 803)); assertEquals(3L, m.droppedFrames)
        m.disconnect(); m.connect("ADAPTER"); m.acquisition(true); m.frame(frame(id = 0)); assertEquals(1, m.ring.count)
    }
    @Test fun deterministicFixtureMatchesOriginalJavascriptAndClears() {
        val m = SonarModel { 100_000 }; m.fixture(SonarFixture.SIGNAL)
        assertArrayEquals(intArrayOf(2385, 3322, 6134, 3066), intArrayOf(0, 285, 530, 1023).map { m.latest!!.sample(it) }.toIntArray())
        assertTrue(m.demo); assertFalse(m.calibrated); m.fixture(SonarFixture.CLEAR)
        assertFalse(m.demo); assertEquals(0, m.ring.count); assertEquals(SonarQuality.OFFLINE, m.quality())
    }
    @Test fun invalidCalibrationAndUnsupportedTargetsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { Calibration(true, -1.0) }
        assertThrows(IllegalArgumentException::class.java) { Calibration(soundSpeedMps = 0.0) }
        val m = SonarModel { 0 }
        assertThrows(IllegalArgumentException::class.java) { m.setDisplay(m.display.copy(gainDb = Double.NaN)) }
        assertFalse(m.display.overlayTargets)
        m.setDisplay(m.display.copy(overlayTargets = true))
        assertTrue(m.display.overlayTargets)
        assertNull(m.viewPresentation) // Enabling a display flag cannot invent targets.
    }

    @Test fun changingMetricSpacingPreservesLiveColumnsAndFrozenColumnCalibration() {
        val m = SonarModel { 100_000L }
        m.connect("WIRE", simulated = true); m.acquisition(true)
        val first = Calibration(true, 20.0, revision = 7)
        m.setCalibration(first); m.frame(frame(id = 0))
        m.setDisplay(m.display.copy(rangeM = 10.0)); m.setFreeze(true)
        val frozenEnd = m.display.cropEnd
        m.setCalibration(Calibration(true, 10.0, revision = 8)); m.frame(frame(id = 1))
        assertEquals(2, m.ring.count); assertEquals(1, m.viewRing.count)
        assertEquals(first, m.viewRing.calibrationAt(0))
        assertEquals(.015, m.viewMetersPerBin!!, 0.0)
        assertEquals(.0075, m.metersPerBin!!, 0.0)
        assertEquals(frozenEnd, m.display.cropEnd, 0.0)
        assertTrue(m.snapshot().simulated); assertFalse(m.snapshot().demo)
        m.setFreeze(false)
        assertNull(m.display.rangeM); assertEquals(1023.0, m.display.cropEnd, 0.0)
        assertEquals(8L, m.viewRing.calibrationAt(0).revision)
    }
    @Test fun invalidLiveCalibrationCannotRelabelFrozenCalibratedHistory() {
        val m = SonarModel { 100_000L }; m.fixture(SonarFixture.CALIBRATED)
        m.setDisplay(m.display.copy(rangeM = 5.0)); m.setFreeze(true)
        m.setCalibration(Calibration(revision = 1)); m.frame(frame(id = 800))
        assertFalse(m.calibrated); assertTrue(m.viewCalibration.calibrated)
        assertEquals(.015, m.viewRing.calibrationAt(0).metersPerBin!!, .000001)
        m.setFreeze(false)
        assertFalse(m.viewCalibration.calibrated); assertNull(m.display.rangeM)
    }
    @Test fun perColumnBottomMetadataIsImmutableAndCopiesWithFreeze() {
        val r = SonarRingBuffer(2)
        val c = Calibration(true, 20.0, revision = 1)
        val b = SonarBottom(123.0, .9, .5)
        r.push(frame(), c, b); val frozen = r.copy()
        r.push(frame(id = 1), Calibration(revision = 2), SonarBottom())
        assertEquals(c, frozen.calibrationAt(0)); assertEquals(b, frozen.bottomAt(0))
        assertEquals(c, r.calibrationAt(1)); r.clear(); assertEquals(0, r.count)
        assertEquals(c, frozen.calibrationAt(0))
    }
    @Test fun lostLiveBottomCapabilityCannotTrapOrRelabelAFrozenAutoView() {
        val m = SonarModel { 100_000L }; m.fixture(SonarFixture.CALIBRATED)
        m.setDisplay(m.display.copy(rangeMode = SonarRangeMode.AUTO, overlayBottom = true)); m.setFreeze(true)
        val bounds = m.display.cropEnd
        m.setBottom(SonarBottom(), capability = false)
        assertFalse(m.bottomValid); assertTrue(m.viewBottomValid)
        m.setDisplay(m.display.copy(gainDb = 3.0))
        assertEquals(bounds, m.display.cropEnd, .0)
        m.setFreeze(false)
        assertFalse(m.display.freeze); assertFalse(m.display.overlayBottom)
        assertEquals(SonarRangeMode.MANUAL, m.display.rangeMode)
    }
    @Test fun explicitStaleSourceBeforeItsFirstPingCannotClaimFreshAcquisition() {
        val m = SonarModel { 100_000L }; m.connect("NETWORK"); m.acquisition(true)
        m.setSourceStale(true)
        assertEquals(SonarQuality.DEGRADED, m.quality()); assertNull(m.depth())
        m.setSourceStale(false); assertEquals(SonarQuality.IDLE, m.quality())
    }
}
