package com.no5.preparation.ui

import org.junit.Assert.*
import org.junit.Test

class AisTest {
    private val state = AisState(targets = frozenListOf(
        AisTarget(mmsi = "111111111", name = "SIM Mistral", watched = true, approaching = true),
        AisTarget(mmsi = "222222222", name = "SIM Nord Star", freshness = AisFreshness.STALE),
        AisTarget(mmsi = "333333333", name = "SIM Echo", freshness = AisFreshness.LOST),
    ))
    @Test fun searchMatchesNameAndMmsi() {
        assertEquals(listOf("111111111"), visibleAisTargets(state.copy(query = "  mistral ")).map { it.mmsi })
        assertEquals(listOf("222222222"), visibleAisTargets(state.copy(query = "2222")).map { it.mmsi })
    }
    @Test fun explicitListBackKeepsSelectionAndWatchWithoutCreatingMapRequest() {
        val selected = reduceAis(state, AisEvent.Select("111111111"))
        assertTrue(selected.detailsExpanded)
        val list = reduceAis(selected, AisEvent.ShowList)
        assertFalse(list.detailsExpanded)
        assertEquals(selected.selectedMmsi, list.selectedMmsi)
        assertSame(selected.targets, list.targets)
        assertFalse(reduceAis(selected, AisEvent.Search("Nord")).detailsExpanded)
    }
    @Test fun hiddenSelectionIsClearedAfterSearchAndFilters() {
        val selected = reduceAis(state, AisEvent.Select("111111111"))
        assertNull(reduceAis(selected, AisEvent.Search("Nord")).selectedMmsi)
        assertNull(reduceAis(selected, AisEvent.SetFilter(AisFilter.STALE_LOST)).selectedMmsi)
        assertNull(selectedAisTarget(selected.copy(query = "Echo")))
    }
    @Test fun watchRemovalCannotLeaveHiddenDetailsVisible() {
        val watched = state.copy(filter = AisFilter.WATCHED, selectedMmsi = "111111111")
        val removed = reduceAis(watched, AisEvent.ToggleWatch("111111111"))
        assertNull(removed.selectedMmsi)
        assertTrue(visibleAisTargets(removed).isEmpty())
        assertTrue(state.targets.first().watched)
    }
    @Test fun unknownApproachIsNotTreatedAsApproaching() {
        assertEquals(listOf("111111111"), visibleAisTargets(state.copy(filter = AisFilter.APPROACHES)).map { it.mmsi })
        assertEquals(listOf("222222222", "333333333"), visibleAisTargets(state.copy(filter = AisFilter.STALE_LOST)).map { it.mmsi })
    }
    @Test fun absentOrFutureMeasurementAgeIsUnknown() {
        assertNull(aisAgeSeconds(null, 1000))
        assertNull(aisAgeSeconds(2000, null))
        assertNull(aisAgeSeconds(1000, 2000))
        assertNull(aisAgeSeconds(Long.MAX_VALUE, Long.MIN_VALUE))
        assertEquals(4L, aisAgeSeconds(5000, 1000))
    }
    @Test fun receptionTimeCannotInventMeasurementAgeOrCpa() {
        val target = AisTarget("111111111", receptionEpochMillis = 12345)
        assertNull(aisAgeSeconds(15000, target.measurementEpochMillis))
        assertNull(target.cpaNauticalMiles)
        assertEquals(AisQuality.UNKNOWN, target.quality)
    }
    @Test fun mapRequestIsAnExplicitEventWithUnmodifiedActualPosition() {
        // Synthetic fixture exclusively in test sources.
        val position = AisPosition(-33.1, 18.2)
        val event = AisEvent.ShowOnMap("111111111", position)
        assertEquals(position, event.position)
        assertEquals(state, reduceAis(state, event))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidPositionIsRejected() {
        AisPosition(Double.NaN, 18.2)
    }
    @Test fun monotonicAgeIncludesMeasurementAgeAtReceipt() {
        assertEquals(7L, aisMonotonicAgeSeconds(15000, 10000, 2000))
    }
    @Test fun wallClockJumpsCannotChangeDisplayedAge() {
        val target = AisTarget("111111111", measurementEpochMillis = 1000, receptionEpochMillis = 2000,
            measurementAgeAtReceiptMillis = 1000, receptionMonotonicMillis = 5000)
        val initial = state.copy(nowEpochMillis = 4000, nowMonotonicMillis = 7000)
        assertEquals(3L, aisDisplayedAgeSeconds(initial, target))
        assertEquals(aisDisplayedAgeSeconds(initial, target), aisDisplayedAgeSeconds(initial.copy(nowEpochMillis = -99999999), target))
        assertEquals(aisDisplayedAgeSeconds(initial, target), aisDisplayedAgeSeconds(initial.copy(nowEpochMillis = Long.MAX_VALUE), target))
    }
    @Test fun missingMonotonicEvidenceNeverFallsBackToUtc() {
        val target = AisTarget("111111111", measurementEpochMillis = 1000, receptionEpochMillis = 2000)
        assertNull(aisDisplayedAgeSeconds(state.copy(nowEpochMillis = 5000), target))
        assertNull(aisMonotonicAgeSeconds(null, 1000, 0))
        assertNull(aisMonotonicAgeSeconds(2000, null, 0))
        assertNull(aisMonotonicAgeSeconds(2000, 1000, null))
    }
    @Test fun invalidMonotonicEvidenceIsUnknown() {
        assertNull(aisMonotonicAgeSeconds(1000, 2000, 0))
        assertNull(aisMonotonicAgeSeconds(2000, -1, 0))
        assertNull(aisMonotonicAgeSeconds(2000, 1000, -1))
        assertNull(aisMonotonicAgeSeconds(Long.MAX_VALUE, 0, 1))
    }
    @Test fun unitsConvertPresentationWithoutChangingSourceOrUnknownValues() {
        val target = AisTarget("111111111", speedKnots = 10.0, cpaNauticalMiles = 2.0)
        assertEquals(18.52, aisSpeedValue(target.speedKnots, SpeedUnit.KILOMETRES_PER_HOUR)!!, 0.000001)
        assertEquals(3.704, aisDistanceValue(target.cpaNauticalMiles, DistanceUnit.KILOMETRES)!!, 0.000001)
        assertEquals(10.0, aisSpeedValue(target.speedKnots, SpeedUnit.KNOTS)!!, 0.0)
        assertEquals(2.0, aisDistanceValue(target.cpaNauticalMiles, DistanceUnit.NAUTICAL_MILES)!!, 0.0)
        assertNull(aisSpeedValue(null, SpeedUnit.KILOMETRES_PER_HOUR))
        assertNull(aisDistanceValue(null, DistanceUnit.KILOMETRES))
        assertNull(aisDistanceValue(Double.POSITIVE_INFINITY, DistanceUnit.KILOMETRES))
        assertEquals(10.0, target.speedKnots!!, 0.0)
        assertEquals(2.0, target.cpaNauticalMiles!!, 0.0)
    }
}
