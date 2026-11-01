package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class SonarAmplitudeContextTest {
    private fun amplitude(active: Boolean, revision: Long = 1) = SonarAmplitude(4095, 12, true,
        "HELLO_12bit_checked_host_contract", if (active) "background_reduction_then_max_pool_or_interpolate"
        else "max_pool_or_interpolate_without_amplitude_scaling", revision, active)

    @Test fun sameAdcCodeScaleDoesNotClaimThatDerivedContrastIsOriginalAdc() {
        val original = amplitude(false); val derived = amplitude(true)
        assertTrue(original.usable); assertTrue(derived.usable)
        assertEquals(original.fullScale, derived.fullScale)
        assertFalse(original.noiseActive!!); assertTrue(derived.noiseActive!!)
        assertNotEquals(original, derived)
        assertTrue(SonarAmplitude(4095, 12, true).usable) // Existing optional contract remains compatible.
    }
    @Test fun incompleteContradictoryUnknownOrNegativeContextCannotConfirmAmplitude() {
        for (invalid in listOf(amplitude(true).copy(noiseRevision = null),
            amplitude(true).copy(noiseRevision = -1), amplitude(true).copy(noiseActive = null),
            amplitude(true).copy(displayTransform = "future_transform"),
            amplitude(true).copy(noiseActive = false), amplitude(false).copy(noiseActive = true))) {
            assertFalse(invalid.usable)
        }
    }
    @Test fun freezeOwnsInterpretationWhileLiveAndAggregatedColumnsKeepDistinctContexts() {
        var now = 900_000L
        val model = SonarModel { now }; model.connect("SYNTHETIC CONTEXT"); model.acquisition(true)
        model.updateContext(SonarEpoch("synthetic", 1, 1), SonarPingSource.OWN)
        val samples = ShortArray(1024) { it.toShort() }
        fun next(id: Long, context: SonarAmplitude) {
            model.setAnalysis(null, SonarBottom(), null, false, "0.4.13", context)
            model.frame(SonarFrame(samples, id, now), pingSource = SonarPingSource.OWN)
        }
        next(1, amplitude(false)); val oldSegment = model.ring.imageSegmentAt(0)
        model.setFreeze(true); now += 100; next(2, amplitude(true))
        assertEquals(amplitude(false), model.viewAmplitudeContext)
        assertEquals(amplitude(false), model.viewRing.amplitudeContextAt(0))
        assertEquals(amplitude(true), model.ring.amplitudeContextAt(0))
        assertNotEquals(oldSegment, model.ring.imageSegmentAt(0))
        val image = model.imageHistory
        val contexts = (0 until image.count).filter { image.occupiedAt(it) }.map { image.amplitudeContextAt(it) }
        assertTrue(contexts.containsAll(listOf(amplitude(false), amplitude(true))))
        assertEquals(2, contexts.size) // Both pings occupy the same 300-ms time bin, in distinct contexts.
        assertTrue((0 until image.count).filter { image.occupiedAt(it) }.all { image.contributionCountAt(it) == 1L })
        assertArrayEquals(samples, model.ring.atAge(0)!!.samples)
        assertArrayEquals(samples, model.ring.atAge(1)!!.samples)
        model.setFreeze(false)
        assertEquals(amplitude(true), model.viewAmplitudeContext)
        now += 500
        assertEquals(2, model.measuredRate().eventCount)
    }
    @Test fun revisionAndDecisionChangesRestartImageStatisticsWithoutDiscardingMeasurements() {
        var now = 900_000L
        val model = SonarModel { now }; model.connect("SYNTHETIC CONTEXT"); model.acquisition(true)
        model.updateContext(SonarEpoch("normalization",1,1),SonarPingSource.OWN)
        model.setImageAuto(true)
        fun next(id: Long, context: SonarAmplitude) {
            model.setAnalysis(null, SonarBottom(), null, false, "0.4.13", context)
            model.frame(SonarFrame(ShortArray(1024) { if (it in 400..500) 900 else 100 }, id, now),pingSource=SonarPingSource.OWN)
        }
        for (id in 1L..20L) { now += 100; next(id, amplitude(true)) }
        assertEquals(SonarImageAutoState.ACTIVE, model.imageAutoState)
        val gain = model.display.gainDb; val segment = model.ring.imageSegmentAt(0)
        now += 100; next(21, amplitude(true, 2))
        assertEquals(SonarImageAutoState.WARMING, model.imageAutoState)
        assertEquals(gain, model.display.gainDb, 0.0)
        assertEquals(21, model.ring.count)
        assertNotEquals(segment, model.ring.imageSegmentAt(0))
        val revisedSegment = model.ring.imageSegmentAt(0)
        now += 100; next(22, amplitude(false, 2)) // Auto decision changes; policy revision does not.
        assertEquals(SonarImageAutoState.WARMING, model.imageAutoState)
        assertEquals(gain, model.display.gainDb, 0.0)
        assertEquals(22, model.ring.count)
        assertNotEquals(revisedSegment, model.ring.imageSegmentAt(0))
    }
}
