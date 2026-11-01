package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class SonarOperationalModelTest {
    private fun frame(id: Long, time: Long, value: Int = 100) = SonarFrame(ShortArray(1024) { value.toShort() }, id, time)
    private fun active(now: () -> Long) = SonarModel(now).also { it.connect("Gateway"); it.acquisition(true) }
    private fun analysis(model: SonarModel, id: Long, bin: Double? = 100.0) {
        model.setAnalysis(SonarGeometry(30.0, SonarTxReference.HOST_VERIFIED),
            if (bin == null) SonarBottom() else SonarBottom(bin, .9),
            SonarPresentation(id, if (bin == null) SonarAnalysisQuality.SEARCHING else SonarAnalysisQuality.LOCKED,
                null, "auto", "auto_raw", true, bin != null, bin?.let { (it * 1.3).coerceIn(32.0, 1023.0) }, false), true, "0.4.10")
    }
    @Test fun confirmedEpochResetAcceptsSmallPingIdAndDropsFreezeAndStatisticsButKeepsPreferences() {
        val model = active { 10_000 }
        model.updateContext(SonarEpoch("a", 1, 1), SonarPingSource.OWN)
        model.setManualImageValue(SonarImageField.GAMMA, 1.4)
        model.setImageMode(SonarImageField.GAIN, SonarImageMode.AUTO)
        analysis(model, 900); model.frame(frame(900, 10_000), pingSource = SonarPingSource.OWN)
        model.setFreeze(true)
        model.updateContext(SonarEpoch("b", 2, 1), SonarPingSource.OWN)
        assertEquals(0, model.ring.count); assertFalse(model.display.freeze); assertNull(model.viewPresentation)
        assertEquals(SonarImageMode.AUTO, model.display.gainMode)
        assertEquals(1.4, model.display.gamma, 0.0)
        analysis(model, 1); model.frame(frame(1, 10_000), pingSource = SonarPingSource.OWN)
        assertEquals(1L, model.latest!!.pingId)
        assertEquals(1, model.measuredRate().eventCount)
    }
    @Test fun selectionGenerationClearsOldHistoryEvenWhenGatewaySessionIsSame() {
        val model = active { 1000 }
        model.updateContext(SonarEpoch("a", 1, 1), SonarPingSource.OWN)
        model.frame(frame(1, 1000), pingSource = SonarPingSource.OWN)
        model.updateContext(SonarEpoch("a", 1, 2), SonarPingSource.NATIVE)
        assertEquals(0, model.ring.count)
        model.frame(frame(2, 1000), pingSource = SonarPingSource.NATIVE)
        assertEquals(SonarPingSource.NATIVE, model.ring.pingSourceAt(0))
        assertThrows(IllegalArgumentException::class.java) { model.frame(frame(3, 1000), pingSource = SonarPingSource.OWN) }
    }
    @Test fun reconnectOfSameConfirmedEpochKeepsHistoryAndItsRealTimeGap() {
        var now = 1000L
        val model = active { now }
        model.updateContext(SonarEpoch("a", 1, 1), SonarPingSource.OWN)
        model.frame(frame(1, now), pingSource = SonarPingSource.OWN)
        model.disconnect()
        now += 3000
        model.updateContext(SonarEpoch("a", 1, 1), SonarPingSource.OWN)
        model.connect("Gateway"); model.acquisition(true)
        model.frame(frame(2, now), pingSource = SonarPingSource.OWN)
        assertEquals(2, model.ring.count)
        assertEquals(3.0, model.historyCoverage().availableSeconds, 0.0)
        assertEquals(3.0 / 120, model.historyFractionAt(1), 1e-12)
    }
    @Test fun deliberatelyUnpublishedNativeIdsAreNotInventedAsOwnQueueDrops() {
        val model = active { 1000 }
        model.updateContext(SonarEpoch("a", 1, 1), SonarPingSource.OWN)
        model.frame(frame(1, 1000), pingSource = SonarPingSource.OWN)
        model.frame(frame(9, 1000), pingSource = SonarPingSource.OWN)
        assertEquals(0L, model.droppedFrames)
        assertEquals(2, model.ring.count)
    }
    @Test fun sameEpochGapRebuildsImageStatisticsWithoutDroppingHistoryOrPreferences() {
        for (disconnect in listOf(false, true)) {
            var now = 1000L
            val model = active { now }
            model.updateContext(SonarEpoch("a", 1, 1), SonarPingSource.OWN)
            model.setImageAuto(true)
            model.setManualImageValue(SonarImageField.GAMMA, 1.8)
            fun next(id: Long, level: Int) {
                analysis(model, id)
                model.frame(SonarFrame(ShortArray(1024) { if (it in 400..500) level.toShort() else (level / 10).toShort() }, id, now),
                    pingSource = SonarPingSource.OWN)
            }
            for (id in 1L..50L) { now += 100; next(id, 400) }
            val previousGain = model.display.gainDb
            assertEquals(SonarImageAutoState.ACTIVE, model.imageAutoState)
            if (disconnect) model.disconnect()
            now += 60_000
            if (disconnect) { model.connect("Gateway"); model.acquisition(true) }
            next(51, 40)
            assertEquals(SonarImageAutoState.WARMING, model.imageAutoState)
            assertEquals(previousGain, model.display.gainDb, 0.0)
            assertEquals(51, model.ring.count)
            assertEquals(SonarImageMode.AUTO, model.display.gainMode)
            assertEquals(SonarImageMode.MANUAL, model.display.gammaMode)
            assertEquals(1.8, model.display.gamma, 0.0)
        }
    }
    @Test fun explicitlyStaleSourceRestartsAutoStatisticsEvenWithoutDisconnect() {
        var now = 1000L
        val model = active { now }
        model.updateContext(SonarEpoch("auto", 1, 1), SonarPingSource.OWN)
        model.setImageAuto(true)
        for (id in 1L..20L) {
            now += 100; analysis(model, id); model.frame(frame(id, now), pingSource=SonarPingSource.OWN)
        }
        assertEquals(SonarImageAutoState.ACTIVE, model.imageAutoState)
        val gain = model.display.gainDb
        model.setSourceStale(true)
        assertEquals(SonarImageAutoState.WAITING, model.imageAutoState)
        model.setSourceStale(false)
        now += 100; analysis(model, 21); model.frame(frame(21, now), pingSource=SonarPingSource.OWN)
        assertEquals(SonarImageAutoState.WARMING, model.imageAutoState)
        assertEquals(gain, model.display.gainDb, 0.0)
        assertEquals(21, model.ring.count)
    }
    @Test fun unknownAcousticSourceCannotBorrowSelectedOwnProofForDepthOrRate() {
        val model = active { 1000 }
        model.updateContext(SonarEpoch("a", 1, 1), SonarPingSource.OWN)
        analysis(model, 1)
        model.frame(frame(1, 1000))
        assertNull(model.depth()); assertNull(model.ring.bottomAt(0).bin)
        assertNull(model.viewPresentation); assertNull(model.currentPingSource)
        assertNull(model.measuredRate().hz)
        assertEquals(SonarRateState.INCOMPLETE, model.measuredRate().state)
    }
    @Test fun explicitBadAmplitudeSuppressesCheckedVersionFallbackAndAutoCompensation() {
        val model = active { 1000 }
        model.setImageAuto(true)
        model.setAnalysis(null, SonarBottom(), null, false, "0.4.10", SonarAmplitude(null, null, false, "UNCONFIRMED"))
        repeat(10) { model.frame(frame(it.toLong(), 1000)) }
        assertEquals(65535, model.amplitudeFullScale); assertFalse(model.amplitudeVerified)
        assertEquals(SonarImageAutoState.INCOMPATIBLE, model.imageAutoState)
        assertEquals(0.0, model.display.gainDb, 0.0)
    }
    @Test fun reverifiedUint16ScaleRebuildsStatisticsEvenWhenNumericFallbackScaleIsUnchanged() {
        var now = 1000L
        val model = active { now }
        model.updateContext(SonarEpoch("auto", 1, 1), SonarPingSource.OWN)
        model.setImageAuto(true)
        for (id in 1L..20L) {
            now += 100
            model.setAnalysis(null, SonarBottom(), null, false, "future",
                SonarAmplitude(65535, 16, true, "EXPLICIT"))
            model.frame(frame(id, now, 1000), pingSource=SonarPingSource.OWN)
        }
        assertEquals(SonarImageAutoState.ACTIVE, model.imageAutoState)
        model.setAnalysis(null, SonarBottom(), null, false, "future", SonarAmplitude(null))
        assertEquals(SonarImageAutoState.INCOMPATIBLE, model.imageAutoState)
        now += 100
        model.setAnalysis(null, SonarBottom(), null, false, "future",
            SonarAmplitude(65535, 16, true, "EXPLICIT"))
        model.frame(frame(21, now, 100), pingSource=SonarPingSource.OWN)
        assertEquals(SonarImageAutoState.WARMING, model.imageAutoState)
    }
    @Test fun individualManualOverridePreservesOtherModesAndLastManualValue() {
        val model = active { 1000 }
        model.setManualImageValue(SonarImageField.GAIN, 4.0)
        model.setImageAuto(true)
        model.setManualImageValue(SonarImageField.GAMMA, 1.8)
        assertEquals(SonarImageMode.AUTO, model.display.gainMode)
        assertEquals(SonarImageMode.AUTO, model.display.thresholdMode)
        assertEquals(SonarImageMode.MANUAL, model.display.gammaMode)
        model.setImageMode(SonarImageField.GAIN, SonarImageMode.MANUAL)
        assertEquals(4.0, model.display.gainDb, 0.0)
        assertEquals(1.8, model.display.gamma, 0.0)
    }
    @Test fun automaticDisplayChangesNeitherSamplesNorAcquisitionOrCanonicalAnalysis() {
        var now = 1000L
        val model = active { now }
        model.updateContext(SonarEpoch("auto", 1, 1), SonarPingSource.OWN)
        model.setImageAuto(true)
        for (id in 1L..50L) {
            now += 100
            val raw = SonarFrame(ShortArray(1024) { if (it in 400..500) 400 else 35 }, id, now)
            val before = raw.samples
            analysis(model, id)
            model.frame(raw, pingSource=SonarPingSource.OWN)
            assertArrayEquals(before, raw.samples)
            assertArrayEquals(before, model.latest!!.samples)
            assertEquals(400, model.ring.sampleAt(0, 450))
            assertEquals(id, model.presentation!!.pingId)
            assertTrue(model.running)
        }
        assertTrue(model.display.gainDb > 0)
        assertEquals(SonarImageAutoState.ACTIVE, model.imageAutoState)
    }
    @Test fun actualHistoryCoverageIsShownWhenRingCapacityCannotHoldRequestedTime() {
        val model = active { 100_000 }
        for (id in 0L..2100L) model.frame(frame(id, 100_000 + id * 10))
        assertEquals(2048, model.ring.count)
        val coverage = model.historyCoverage()
        assertEquals(20.47, coverage.availableSeconds, 1e-12)
        assertTrue(coverage.capacityLimited)
        assertEquals(20.47, coverage.visibleSeconds, 1e-12)
        assertEquals(20.47 / 120, model.historyFractionAt(2047), 1e-12)
        assertEquals(2048, model.visibleCount())
    }
    @Test fun freezeCapturesParametersAndAnalysisWhileRateAndLiveSamplesContinue() {
        var now = 1000L
        val model = active { now }
        model.setManualImageValue(SonarImageField.GAIN, 4.0)
        analysis(model, 1); model.frame(frame(1, now)); model.setFreeze(true)
        val frozen = model.viewDisplay
        now += 100
        analysis(model, 2, null); model.frame(frame(2, now))
        model.setManualImageValue(SonarImageField.GAIN, 6.0)
        assertEquals(4.0, frozen.gainDb, 0.0)
        assertEquals(4.0, model.viewDisplay.gainDb, 0.0)
        assertEquals(1L, model.viewPresentation!!.pingId)
        assertEquals(2L, model.latest!!.pingId)
        assertEquals(2, model.measuredRate(now + 500).eventCount)
        assertEquals(100, model.ring.sampleAt(0, 0))
    }
    @Test fun staleDepthCannotBeLiveButHistoricalDepthRetainsOriginalTimeAndAge() {
        var now = 1000L
        val model = active { now }
        analysis(model, 1); model.frame(frame(1, now))
        val old = model.lastConfirmedDepth!!
        now = 5000
        model.setSourceStale(true)
        assertNull(model.depth()); assertEquals(4000L, old.ageMs(now))
        assertEquals(SonarRateState.STALE, model.measuredRate().state)
        assertEquals(1000L, model.lastConfirmedDepth!!.observedAtEpochMs)
    }
    @Test fun autoRangeConfirmsDeeperEchoAndHoldsShallowerOrMissingDepth() {
        var now = 1000L
        val model = active { now }
        analysis(model, 1, 100.0); model.frame(frame(1, now))
        model.setDisplay(model.display.copy(rangeMode = SonarRangeMode.AUTO,cropEnd=200.0))
        assertEquals(200.0,model.display.cropEnd,0.0)
        for(id in 2L..3L){now+=100;analysis(model,id,400.0);model.frame(frame(id,now));assertEquals(200.0,model.display.cropEnd,0.0)}
        now+=100;analysis(model,4,400.0);model.frame(frame(4,now));assertEquals(480.0,model.display.cropEnd,1e-9)
        now+=100;analysis(model,5,100.0);model.frame(frame(5,now));assertEquals(480.0,model.display.cropEnd,0.0)
        val held = model.display.cropEnd
        now+=100;analysis(model,6,null);model.frame(frame(6,now))
        assertEquals(held,model.display.cropEnd,0.0);assertEquals(SonarRangeMode.AUTO,model.display.rangeMode);assertNull(model.depth())
    }
}
