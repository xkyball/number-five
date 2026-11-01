package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

/** Synthetic measured clocks; no wall-clock hardware run or inferred metrical data. */
class SonarRetainedImageHistoryTest {
    private val base = 1_200_000L
    private var now = base
    private var id = 0L
    private val epoch = SonarEpoch("synthetic-retained-image", 1, 1)
    private val verified = SonarAmplitude(4095, 12, true)
    private val unconfirmed = SonarAmplitude(null, verified = false)
    private val metric = SonarGeometry(3.072, SonarTxReference.HOST_VERIFIED)
    private val unknown = SonarGeometry(null, SonarTxReference.UNVERIFIED)
    private fun model() = SonarModel { now }.also {
        it.connect("SYNTHETIC RETAINED IMAGE"); it.acquisition(true)
        it.updateContext(epoch, SonarPingSource.OWN)
        it.setDisplay(it.display.copy(historySeconds = 120.0))
    }
    private fun append(model: SonarModel, elapsed: Long, geometry: SonarGeometry = metric,
        amplitude: SonarAmplitude = verified, value: Int = 4095) {
        now = base + elapsed
        model.setAnalysis(geometry, SonarBottom(), null, false, amplitude = amplitude)
        model.frame(SonarFrame(ShortArray(SONAR_BINS) { value.toShort() }, ++id, now), pingSource = SonarPingSource.OWN)
    }
    private fun collect(model: SonarModel) { for (elapsed in 0L..120_000L step 50) append(model, elapsed) }

    @Test fun unconfirmedReferenceAndScaleGapKeepsEarlierImageAcrossTheChosenTimeRaster() {
        val m = model(); collect(m)
        assertTrue(m.imageHistoryAggregated)
        append(m, 120_050, unknown, unconfirmed, 0)
        assertEquals(1, m.ring.count)
        assertTrue("Earlier observed columns must remain selected immediately, without 2048 new raw pings", m.imageHistoryAggregated)
        assertNull(m.viewMetersPerBin); assertNull(m.depth())
        val h = m.viewImageColumns
        val ages = (0 until m.visibleImageCount()).filter { h.occupiedAt(it) }
        val oldest = ages.last()
        assertTrue("The 120-second raster must still reach its left edge", m.imageFractionAt(oldest) > .99)
        assertEquals(4095, h.sampleAt(oldest, 512))
        assertEquals(verified, h.amplitudeContextAt(oldest))
        assertEquals(metric, h.geometryAt(oldest))
        assertTrue("A metric old profile cannot be relabelled onto an unverified meter axis",
            h.sourceBinAtAxis(oldest, 100.0, Double.NaN).isNaN())
        val unconfirmedAge = ages.first { h.geometryAt(it)?.txReference == SonarTxReference.UNVERIFIED }
        assertNotEquals(h.imageSegmentAt(unconfirmedAge), h.imageSegmentAt(oldest))

        append(m, 120_100)
        assertEquals(1, m.ring.count)
        assertTrue(m.imageHistoryAggregated)
        assertEquals(120.1, m.imageAvailableSeconds(), 1e-9)
        val unknownAge = (0 until m.visibleImageCount()).first {
            m.viewImageColumns.occupiedAt(it) && m.viewImageColumns.geometryAt(it)?.txReference == SonarTxReference.UNVERIFIED
        }
        assertTrue("Unknown metric evidence must not acquire a meter scale after recovery",
            m.viewImageColumns.sourceBinAtAxis(unknownAge, 100.0, m.viewMetersPerBin!!).isNaN())
        val resumedAge = (0 until m.visibleImageCount()).first {
            m.viewImageColumns.occupiedAt(it) && m.viewImageColumns.lastTimestampAt(it) == now
        }
        assertNotEquals(m.viewImageColumns.imageSegmentAt(resumedAge), m.viewImageColumns.imageSegmentAt(unknownAge))
        assertEquals(epoch, m.epoch)
    }

    @Test fun fullScaleBoundaryUsesOlderImageOnlyWhileTheWindowExceedsCurrentRawCoverage() {
        val m = model(); collect(m)
        append(m, 120_050, amplitude = unconfirmed)
        assertEquals(1, m.ring.count)
        assertTrue(m.imageHistoryAggregated)
        append(m, 120_100)
        for (elapsed in 120_150L..132_100L step 50) append(m, elapsed)
        assertTrue(m.ring.count < m.ring.capacity)
        assertEquals(12.0, m.historyCoverage().availableSeconds, 1e-9)
        m.setDisplay(m.display.copy(historySeconds = 10.0))
        assertFalse("Sufficient recent raw measurements keep their full depth resolution", m.imageHistoryAggregated)
        assertSame(m.ring, m.viewImageColumns)
        m.setDisplay(m.display.copy(historySeconds = 120.0))
        assertTrue(m.imageHistoryAggregated)
        assertSame(m.imageHistory, m.viewImageColumns)
    }

    @Test fun freezeAfterABoundaryHoldsTheSelectedImageAndTrueEpochOrSourceChangesClearBothHistories() {
        val m = model(); collect(m)
        append(m, 120_050, unknown, unconfirmed, 0)
        append(m, 120_100)
        assertTrue(m.imageHistoryAggregated)
        m.setFreeze(true)
        val frozen = m.viewImageColumns
        val version = frozen.version
        val coverage = m.imageAvailableSeconds()
        val firstTime = m.imageNewestTime()
        for (elapsed in 120_150L..123_000L step 50) append(m, elapsed)
        assertSame(frozen, m.viewImageColumns)
        assertNotSame(m.imageHistory, frozen)
        assertEquals(version, frozen.version)
        assertEquals(firstTime, m.imageNewestTime())
        assertEquals(coverage, m.imageAvailableSeconds(), 0.0)
        assertTrue(m.imageHistory.version > version)
        m.updateContext(epoch, SonarPingSource.NATIVE)
        assertEquals(0, m.ring.count); assertEquals(0, m.imageHistory.count); assertFalse(m.display.freeze)
        m.updateContext(epoch, SonarPingSource.OWN)
        append(m, 0)
        m.updateContext(epoch.copy(generation = 2), SonarPingSource.OWN)
        assertEquals(0, m.ring.count); assertEquals(0, m.imageHistory.count)
        assertFalse(m.imageHistoryAggregated)
    }

    @Test fun anUnfilledRawRingWithoutOlderImageEvidenceStaysRawIncludingAfterFreeze() {
        val m = model()
        for (elapsed in 0L..5000L step 50) append(m, elapsed)
        assertFalse(m.imageHistoryAggregated)
        m.setFreeze(true)
        val held = m.viewRing
        append(m, 5050, unknown, unconfirmed)
        append(m, 5100)
        assertSame(held, m.viewImageColumns)
        assertFalse(m.imageHistoryAggregated)
        assertEquals(101, m.viewRing.count)
        m.setFreeze(false)
        assertTrue(m.imageHistoryAggregated)
    }

    @Test fun cachedOldestEvidenceExpiresAcrossSparseWrapAndSurvivesCopyAndClear() {
        val h = SonarTimeImageHistory()
        fun observe(time: Long) = h.observe(SonarFrame(ShortArray(SONAR_BINS), 1, time), metric,
            metric.metersPerBin, 4095, SonarPingSource.OWN, 1, verified)
        assertNull(h.oldestObservedTimestamp())
        observe(base); observe(base + 100)
        repeat(1000) { assertEquals(base, h.oldestObservedTimestamp()) }
        assertEquals(.1, h.availableSeconds(), 1e-9)
        val newer = base + SonarTimeImageHistory.BIN_MS * (SonarTimeImageHistory.TIME_BINS + 5)
        observe(newer)
        assertEquals(newer, h.oldestObservedTimestamp())
        assertEquals(0.0, h.availableSeconds(), 0.0)
        observe(newer - 50)
        assertEquals(newer - 50, h.oldestObservedTimestamp())
        val frozen = h.copy()
        assertEquals(newer - 50, frozen.oldestObservedTimestamp())
        h.clear()
        assertNull(h.oldestObservedTimestamp()); assertEquals(0.0, h.availableSeconds(), 0.0)
        assertEquals(newer - 50, frozen.oldestObservedTimestamp())
    }

    @Test fun aFullRawRingRetainsTheEstablishedLongWindowAggregatePath() {
        val m = model()
        for (elapsed in 0L until SONAR_CAPACITY.toLong()) append(m, elapsed)
        assertEquals(m.ring.capacity, m.ring.count)
        assertEquals(base, m.ring.timestampAt(m.ring.count - 1))
        assertEquals(base, m.imageHistory.oldestObservedTimestamp())
        assertTrue(m.imageHistoryAggregated)
        assertSame(m.imageHistory, m.viewImageColumns)
    }
}
