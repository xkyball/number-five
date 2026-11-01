package com.no5.helm.library

import org.junit.Assert.*
import org.junit.Test

class CameraCapacityPolicyTest {
    private fun profile(cells: Int = 3, bytes: Long = 100, loading: Long = 40, measured: Boolean = false) =
        CameraCapacityProfile("profile", 7, cells, bytes, loading, measured)
    private fun tag(scale: Double? = 30_000.0) = CameraCapacityTag("catalog", 4, "camera", 10, 3, 8,
        "selection", "native-calibration", scale, "profile", 7)
    private fun source(key: String, bytes: Long? = 20) = CameraCapacitySource(key, bytes)
    private fun geometry(key: String, bytes: Long = 20, pin: Boolean = false, reusable: Boolean = true,
        id: String = key) = CameraCapacityResidentGeometry(id, key, bytes, pin, reusable)
    private fun residency(geometries: List<CameraCapacityResidentGeometry> = emptyList(),
        activeLoad: CameraCapacityActiveLoad? = null, revision: Long = 11) =
        CameraCapacityResidency("catalog", 4, revision, geometries, activeLoad)
    private fun evaluate(required: List<CameraCapacitySource>, budget: CameraCapacityProfile = profile(),
        held: CameraCapacityResidency = residency(), camera: CameraCapacityTag = tag(),
        expected: CameraCapacityTag = camera, metadata: Boolean = true, candidates: Int = required.size) =
        CameraCapacityPolicy.evaluate(budget, CameraCapacityTarget(camera, required, metadata, candidates), held, expected)
    private fun assertOutcome(outcome: CameraCapacityOutcome, decision: CameraCapacityDecision,
        reason: CameraCapacityReason? = null) {
        assertEquals(outcome, decision.outcome)
        if (reason != null) assertEquals(reason, decision.reason)
    }

    @Test fun exactCellLimitIncludesAllRequiredSourcesButNotBboxCandidates() {
        val atLimit = evaluate(listOf(source("one"), source("two"), source("mask-witness")), candidates = 301)
        assertOutcome(CameraCapacityOutcome.FITS, atLimit)
        assertEquals(3, atLimit.targetCells)
        assertEquals(CameraCapacityPeak(3, 80), atLimit.loadPeakWithoutTransition)
        val over = evaluate(listOf(source("one"), source("two"), source("three"), source("mask-witness")),
            budget = profile(bytes = 200))
        assertOutcome(CameraCapacityOutcome.LIMIT_EXCEEDED, over, CameraCapacityReason.TARGET_CELL_LIMIT)
    }

    @Test fun sameCellCountCanFitOrExceedBytesAndProfileOriginIsVisible() {
        val light = evaluate(listOf(source("one", 10), source("two", 10)), budget = profile(measured = true))
        assertOutcome(CameraCapacityOutcome.FITS, light)
        assertTrue(light.measuredOnDevice)
        val heavy = evaluate(listOf(source("one", 60), source("two", 60)))
        assertOutcome(CameraCapacityOutcome.LIMIT_EXCEEDED, heavy, CameraCapacityReason.TARGET_BYTE_LIMIT)
        assertFalse(heavy.measuredOnDevice)
        assertEquals(120L, heavy.targetChargedBytes)
    }

    @Test fun targetSumCanFitWhileRequiredWarmPrefixAndLoaderReservationDoNot() {
        val result = evaluate(listOf(source("old", 70), source("next", 20)),
            held = residency(listOf(geometry("old", 70))))
        assertEquals(90L, result.targetChargedBytes)
        assertEquals(CameraCapacityPeak(2, 110), result.loadPeakWithoutTransition)
        assertOutcome(CameraCapacityOutcome.LIMIT_EXCEEDED, result, CameraCapacityReason.LOAD_BYTE_PEAK)
    }

    @Test fun missingSourceMustFitItsReservationAndEqualityIsAllowed() {
        assertOutcome(CameraCapacityOutcome.LIMIT_EXCEEDED, evaluate(listOf(source("large", 41))),
            CameraCapacityReason.SOURCE_LOADING_RESERVATION)
        val equality = evaluate(listOf(source("one", 40), source("two", 40)), budget = profile(bytes = 80))
        assertOutcome(CameraCapacityOutcome.FITS, equality)
        assertEquals(CameraCapacityPeak(2, 80), equality.loadPeakWithoutTransition)
    }

    @Test fun suppliedNativeLoadOrderChangesColdPeakWithoutClaimingEveryOrderMustFail() {
        val largeFirst = evaluate(listOf(source("large-a", 30), source("large-b", 30), source("small", 10)),
            budget = profile(bytes = 80))
        assertEquals(70L, largeFirst.targetChargedBytes)
        assertEquals(CameraCapacityPeak(3, 100), largeFirst.loadPeakWithoutTransition)
        assertOutcome(CameraCapacityOutcome.LIMIT_EXCEEDED, largeFirst, CameraCapacityReason.LOAD_BYTE_PEAK)
        val smallFirst = evaluate(listOf(source("small", 10), source("large-a", 30), source("large-b", 30)),
            budget = profile(bytes = 80))
        assertEquals(largeFirst.targetChargedBytes, smallFirst.targetChargedBytes)
        assertEquals(CameraCapacityPeak(3, 80), smallFirst.loadPeakWithoutTransition)
        assertOutcome(CameraCapacityOutcome.FITS, smallFirst)
    }

    @Test fun completelyWarmSetNeedsNoExtraLoaderSlotOrReservation() {
        val result = evaluate(listOf(source("one", 80)), budget = profile(cells = 1, bytes = 80),
            held = residency(listOf(geometry("one", 80, pin = true))))
        assertOutcome(CameraCapacityOutcome.FITS, result)
        assertEquals(CameraCapacityPeak(1, 80), result.loadPeakWithoutTransition)
        assertEquals(result.loadPeakWithoutTransition, result.loadPeakWithTransition)
    }

    @Test fun actualWarmChargeProvesCurrentScenarioButUnknownColdReloadDoesNot() {
        val required = listOf(source("one", null))
        val warm = evaluate(required, held = residency(listOf(geometry("one", 20))))
        assertOutcome(CameraCapacityOutcome.FITS, warm)
        assertEquals(listOf("one"), warm.unknownByteBounds)
        assertEquals(20L, warm.targetChargedBytes)
        assertOutcome(CameraCapacityOutcome.UNKNOWN, evaluate(required), CameraCapacityReason.UNKNOWN_BYTE_BOUND)
    }

    @Test fun metadataAndNativeTargetScaleMustBeKnown() {
        assertOutcome(CameraCapacityOutcome.UNKNOWN, evaluate(listOf(source("one")), metadata = false),
            CameraCapacityReason.UNKNOWN_METADATA)
        for (scale in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0))
            assertOutcome(CameraCapacityOutcome.UNKNOWN, evaluate(listOf(source("one")), camera = tag(scale)),
                CameraCapacityReason.UNKNOWN_SCALE)
        // A complete conservative cohort needs no exact raster-proof flag.
        assertOutcome(CameraCapacityOutcome.FITS, evaluate(listOf(source("one")), candidates = 10))
    }

    @Test fun boundContradictingCurrentMeasuredChargeIsNeverAccepted() {
        assertOutcome(CameraCapacityOutcome.UNKNOWN, evaluate(listOf(source("one", 19)),
            held = residency(listOf(geometry("one", 20)))), CameraCapacityReason.INCONSISTENT_BYTE_BOUND)
    }

    @Test fun unusedUnpinnedGeometriesAreEvictableAndDoNotCreatePermanentExcess() {
        val result = evaluate(listOf(source("next", 20)), budget = profile(cells = 1),
            held = residency(listOf(geometry("old", 90, reusable = false))))
        assertOutcome(CameraCapacityOutcome.FITS, result)
        assertEquals(CameraCapacityPeak(1, 40), result.loadPeakWithTransition)
    }

    @Test fun oldPinsCauseTemporaryWaitThenFitWithoutChangingTarget() {
        val required = listOf(source("next", 20))
        val waiting = evaluate(required, budget = profile(cells = 1),
            held = residency(listOf(geometry("old", pin = true, reusable = false))))
        assertOutcome(CameraCapacityOutcome.WAIT_TRANSITION, waiting, CameraCapacityReason.TRANSITION_PINS)
        assertEquals(CameraCapacityPeak(1, 40), waiting.loadPeakWithoutTransition)
        assertEquals(CameraCapacityPeak(2, 60), waiting.loadPeakWithTransition)
        val released = evaluate(required, budget = profile(cells = 1), held = residency(revision = 12))
        assertOutcome(CameraCapacityOutcome.FITS, released)
        assertEquals(waiting.tag, released.tag)
        assertFalse(waiting.isCurrent(released.tag, released.residencyRevision))
    }

    @Test fun bytePinsAlsoWaitAndRequiredPinIsNotDoubleCounted() {
        val required = listOf(source("one", 20), source("two", 20))
        val waiting = evaluate(required, held = residency(listOf(geometry("one", pin = true),
            geometry("old", 70, pin = true, reusable = false))))
        assertOutcome(CameraCapacityOutcome.WAIT_TRANSITION, waiting)
        assertEquals(CameraCapacityPeak(2, 60), waiting.loadPeakWithoutTransition)
        assertEquals(CameraCapacityPeak(3, 130), waiting.loadPeakWithTransition)
        val fitting = evaluate(required, held = residency(listOf(geometry("one", pin = true))))
        assertOutcome(CameraCapacityOutcome.FITS, fitting)
        assertEquals(CameraCapacityPeak(2, 60), fitting.loadPeakWithTransition)
    }

    @Test fun retiredNonReusableArenaOfSameSourceIsAnAdditionalPinNotAReusableCell() {
        val result = evaluate(listOf(source("one", 20)), budget = profile(cells = 1),
            held = residency(listOf(geometry("one", 20, pin = true, reusable = false, id = "retired-arena"))))
        assertOutcome(CameraCapacityOutcome.WAIT_TRANSITION, result)
        assertEquals(CameraCapacityPeak(2, 60), result.loadPeakWithTransition)
    }

    @Test fun actualActiveLoadIsCountedOnceAndNeverStartsAnImaginarySecondJob() {
        val result = evaluate(listOf(source("one", 20), source("two", 20)),
            held = residency(listOf(geometry("one", 20)), CameraCapacityActiveLoad("two", 40)))
        assertOutcome(CameraCapacityOutcome.WAIT_TRANSITION, result, CameraCapacityReason.ACTIVE_LOAD)
        assertEquals(CameraCapacityPeak(2, 60), result.loadPeakWithTransition)
        val incompatible = evaluate(listOf(source("two", 20)),
            held = residency(activeLoad = CameraCapacityActiveLoad("two", 50)))
        assertOutcome(CameraCapacityOutcome.UNKNOWN, incompatible, CameraCapacityReason.INCONSISTENT_LOADING_RESERVATION)
    }

    @Test fun targetExcessRemainsPermanentEvenWhenOldPinsAlsoExist() {
        val result = evaluate(listOf(source("one"), source("two")), budget = profile(cells = 1),
            held = residency(listOf(geometry("old", pin = true, reusable = false))))
        assertOutcome(CameraCapacityOutcome.LIMIT_EXCEEDED, result, CameraCapacityReason.TARGET_CELL_LIMIT)
    }

    @Test fun allTagComponentsInvalidateOldWorkAndResidencyHasItsOwnAuthority() {
        val original = tag()
        val changes = listOf(original.copy(catalogGeneration = "new"), original.copy(sourceGeneration = 5),
            original.copy(cameraKey = "other"), original.copy(cameraRevision = 11),
            original.copy(surfaceGeneration = 4), original.copy(candidateRevision = 9),
            original.copy(selectionPolicyKey = "new-policy"), original.copy(scaleIdentity = "other-density"),
            original.copy(chartScaleDenominator = 60_000.0), original.copy(profileId = "other-profile"),
            original.copy(profileRevision = 8))
        for (expected in changes) {
            val stale = evaluate(listOf(source("one")), expected = expected)
            assertOutcome(CameraCapacityOutcome.STALE, stale, CameraCapacityReason.OBSOLETE_TAG)
            assertFalse(stale.isCurrent(original, 11))
        }
        assertOutcome(CameraCapacityOutcome.STALE, evaluate(listOf(source("one")),
            held = CameraCapacityResidency("other", 4, 11)), CameraCapacityReason.OBSOLETE_RESIDENCY)
        assertOutcome(CameraCapacityOutcome.STALE, evaluate(listOf(source("one")),
            held = CameraCapacityResidency("catalog", 5, 11)), CameraCapacityReason.OBSOLETE_RESIDENCY)
        assertOutcome(CameraCapacityOutcome.STALE, evaluate(listOf(source("one")), budget = profile().copy(revision = 8)),
            CameraCapacityReason.OBSOLETE_PROFILE)
        val current = evaluate(listOf(source("one")))
        assertTrue(current.isCurrent(original, 11))
        assertFalse(current.isCurrent(original, 12))
    }

    @Test fun independentZoomTargetsNeedNotHaveMonotoneDemand() {
        val outcomes = listOf(12, 31, 9).mapIndexed { i, count ->
            evaluate((0 until count).map { source("cell$it", 1) }, budget = profile(cells = 25, bytes = 1000),
                camera = tag().copy(cameraKey = "zoom$i", cameraRevision = i.toLong(), chartScaleDenominator = (i + 1) * 1000.0)).outcome
        }
        assertEquals(listOf(CameraCapacityOutcome.FITS, CameraCapacityOutcome.LIMIT_EXCEEDED,
            CameraCapacityOutcome.FITS), outcomes)
    }

    @Test fun emptyTargetFitsUnlessOwnerIsStillLoadingOrPinsExceedCurrentBudget() {
        assertOutcome(CameraCapacityOutcome.FITS, evaluate(emptyList()))
        assertEquals(CameraCapacityPeak(0, 0), evaluate(emptyList()).loadPeakWithoutTransition)
        assertOutcome(CameraCapacityOutcome.WAIT_TRANSITION, evaluate(emptyList(),
            held = residency(activeLoad = CameraCapacityActiveLoad("old", 40))))
    }

    @Test fun overflowingChargesCannotWrapIntoAnApparentFit() {
        val overflow = evaluate(listOf(source("one", Long.MAX_VALUE), source("two", 1)),
            budget = profile(bytes = Long.MAX_VALUE, loading = Long.MAX_VALUE))
        assertOutcome(CameraCapacityOutcome.UNKNOWN, overflow, CameraCapacityReason.ARITHMETIC_OVERFLOW)
    }

    @Test fun callerListMutationsCannotAlterTargetsSnapshotsOrPreviousDecisions() {
        val sources = mutableListOf(source("one", null))
        val held = mutableListOf(geometry("one"))
        val target = CameraCapacityTarget(tag(), sources)
        val snapshot = residency(held)
        val result = CameraCapacityPolicy.evaluate(profile(), target, snapshot, tag())
        sources.clear(); held.clear()
        assertEquals(1, target.required.size)
        assertEquals(1, snapshot.geometries.size)
        assertOutcome(CameraCapacityOutcome.FITS, result)
        assertEquals(listOf("one"), result.unknownByteBounds)
        assertEquals(20L, result.targetChargedBytes)
        assertOutcome(CameraCapacityOutcome.FITS, CameraCapacityPolicy.evaluate(profile(), target, snapshot, tag()))
        try { (target.required as MutableList<CameraCapacitySource>).clear(); fail("Mutable target") }
        catch (_: UnsupportedOperationException) { }
        try { (result.unknownByteBounds as MutableList<String>).clear(); fail("Mutable decision") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun malformedIdentityAndBudgetInputsAreRejected() {
        fun invalid(block: () -> Unit) {
            try { block(); fail("Malformed input accepted") } catch (_: IllegalArgumentException) { }
        }
        invalid { profile(cells = 0) }; invalid { profile(cells = 101) }
        invalid { profile(bytes = 39, loading = 40) }; invalid { profile(loading = 0) }
        invalid { CameraCapacityTarget(tag(), listOf(source("same"), source("same"))) }
        invalid { residency(listOf(geometry("same"), geometry("same"))) }
        invalid { residency(listOf(geometry("same", id = "a"), geometry("same", id = "b"))) }
        invalid { source("one", 0) }; invalid { geometry("one", 0) }
        invalid { CameraCapacityTarget(tag(), listOf(source("one")), candidateCount = 0) }
    }
}
