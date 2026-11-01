package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

/** Real and simulated gateways share these field-age rules. These are host tests, not UI/FPS tests. */
class HelmFieldQualityTest {
    private fun observation(value: Double?, stamp: Long? = 20_000L,
                            status: TelemetryStatus = TelemetryStatus.LIVE) =
        TelemetryValue(value, TelemetryQuality(status, stamp, "TEST_GATEWAY", simulated = true))

    private fun state(vararg fields: Pair<String, TelemetryValue>, publication: Long = 20_000L) = HelmState(
        quality = TelemetryQuality(TelemetryStatus.LIVE, publication, "TEST_GATEWAY", simulated = true),
        telemetryFields = fields.toMap(), sourceSessionId = "server-a",
    )

    @Test fun freshGpsDoesNotRefreshOldEngineOrStopItsOwnMotion() {
        val raw = state("speed" to observation(20.0), "portRpm" to observation(3_400.0, 14_000))
        assertEquals(TelemetryStatus.LIVE, raw.fieldStatus("speed", 20_000))
        assertEquals(TelemetryStatus.STALE, raw.fieldStatus("portRpm", 20_000))
        assertEquals(15.0, raw.displayField("speed", 15.0, 20_000)!!, 0.0)
        assertEquals(3_400.0, raw.displayField("portRpm", 1_700.0, 20_000)!!, 0.0)
        assertEquals(14_000L, raw.telemetryFields.getValue("portRpm").quality.timestampEpochMs)
    }

    @Test fun missingNullAndNonfiniteFieldsNeverBecomeAProjectedZero() {
        val raw = state("portRpm" to observation(null, null, TelemetryStatus.UNAVAILABLE),
            "depth" to observation(Double.NaN), "speed" to observation(0.0))
        for (name in listOf("portRpm", "starboardRpm", "depth")) {
            assertEquals(name, TelemetryStatus.UNAVAILABLE, raw.fieldStatus(name, 20_000))
            assertNull(name, raw.displayField(name, 0.0, 20_000))
        }
        assertNull(raw.fieldObservation("starboardRpm", 3_420.0).value)
        assertEquals(0.0, raw.displayField("speed", 0.0, 20_000)!!, 0.0)
    }

    @Test fun unusableFieldDoesNotHideAnUnrelatedFreshField() {
        for (status in listOf(TelemetryStatus.LOST, TelemetryStatus.INVALID, TelemetryStatus.UNAVAILABLE)) {
            val raw = state("portRpm" to observation(2_000.0, status = status), "speed" to observation(11.0))
            assertNull(raw.displayField("portRpm", 2_000.0, 20_000))
            assertEquals(11.0, raw.displayField("speed", 11.0, 20_000)!!, 0.0)
        }
    }

    @Test fun simulatedNetworkFieldsExpireEvenAsPublicationRemainsFresh() {
        val raw = state("speed" to observation(10.0, 1_000), publication = 40_000)
        assertEquals(TelemetryStatus.LIVE, QualityEvaluator.evaluate(raw.quality, 40_000))
        assertEquals(TelemetryStatus.LOST, raw.fieldStatus("speed", 40_000))
        assertNull(raw.displayField("speed", 10.0, 40_000))
    }

    @Test fun eachFieldRequiresItsOwnSourceAndTimestamp() {
        val missingStamp = observation(10.0, null)
        val missingSource = observation(10.0).let { it.copy(quality = it.quality.copy(source = null)) }
        for (field in listOf(missingStamp, missingSource)) {
            val raw = state("speed" to field)
            assertEquals(TelemetryStatus.INVALID, raw.fieldStatus("speed", 20_000))
            assertNull(raw.displayField("speed", 10.0, 20_000))
        }
    }

    @Test fun transportFailureHidesEveryFieldWithoutRewritingItsEvidence() {
        val original = state("speed" to observation(10.0))
        for (status in listOf(TelemetryStatus.LOST, TelemetryStatus.INVALID, TelemetryStatus.UNAVAILABLE)) {
            val failed = original.copy(quality = original.quality.copy(status = status))
            assertEquals(status, failed.fieldStatus("speed", 20_000))
            assertNull(failed.displayField("speed", 10.0, 20_000))
            assertSame(original.telemetryFields, failed.telemetryFields)
        }
    }

    @Test fun mixedRatePresentationKeepsCriticalSamplesRawAndGpsMoving() {
        val first = state("speed" to observation(10.0, 19_950),
            "portRpm" to observation(3_000.0, 13_900, TelemetryStatus.STALE)).copy(speed = 10.0, portRpm = 3_000.0)
        val next = state("speed" to observation(20.0),
            "portRpm" to observation(3_200.0, 14_000, TelemetryStatus.STALE),
            "portOilBar" to observation(0.0), "portCoolantC" to observation(150.0),
            "portVoltage" to observation(8.0), "depth" to observation(0.0)).copy(
            speed = 20.0, portRpm = 3_200.0, portOilBar = 0.0, portCoolantC = 150.0, portVoltage = 8.0, depth = 0.0)
        val model = HelmPresentationModel(first)
        assertTrue(model.accept(next, 50))
        val visible = model.render(100, TelemetryStatus.LIVE)
        assertEquals(15.0, visible.speed, 0.0)
        assertEquals(3_200.0, visible.portRpm, 0.0)
        assertEquals(0.0, visible.portOilBar!!, 0.0)
        assertEquals(150.0, visible.portCoolantC!!, 0.0)
        assertEquals(8.0, visible.portVoltage, 0.0)
        assertEquals(0.0, visible.depth!!, 0.0)
        assertSame(next.quality, visible.quality)
        assertSame(next.telemetryFields, visible.telemetryFields)
        assertEquals(next, model.latestRaw)
    }

    @Test fun frameAgeStopsOnlyTheExpiredFieldEvenIfItsDeclaredStatusWasLive() {
        val first = state("speed" to observation(10.0, 19_950), "portRpm" to observation(1_000.0, 14_950))
            .copy(speed = 10.0, portRpm = 1_000.0)
        val next = state("speed" to observation(20.0), "portRpm" to observation(2_000.0, 15_000))
            .copy(speed = 20.0, portRpm = 2_000.0)
        val model = HelmPresentationModel(first)
        model.accept(next, 50)
        val frame = model.render(100, TelemetryStatus.LIVE)
        assertEquals(15.0, frame.displayField("speed", frame.speed, 20_001)!!, 0.0)
        assertEquals(2_000.0, frame.displayField("portRpm", frame.portRpm, 20_001)!!, 0.0)
        assertEquals(TelemetryStatus.LIVE, next.telemetryFields.getValue("portRpm").quality.status)
    }

    @Test fun becomingAvailableSnapsInsteadOfAnimatingFromAnUnknownScalar() {
        val first = state("portRpm" to observation(null, null, TelemetryStatus.UNAVAILABLE)).copy(portRpm = 0.0)
        val next = state("portRpm" to observation(1_200.0)).copy(portRpm = 1_200.0)
        val model = HelmPresentationModel(first)
        model.accept(next, 50)
        assertEquals(1_200.0, model.render(50).portRpm, 0.0)
    }

    @Test fun aboveSevenThousandRpmRemainsARealValue() {
        val first = state("portRpm" to observation(7_500.0, 19_950)).copy(portRpm = 7_500.0)
        val next = state("portRpm" to observation(9_500.0)).copy(portRpm = 9_500.0)
        val model = HelmPresentationModel(first)
        model.accept(next, 50)
        assertEquals(8_500.0, model.render(100).portRpm, 0.0)
        assertEquals(9_500.0, model.render(150).portRpm, 0.0)
        assertEquals(9_500.0, model.latestRaw.telemetryFields.getValue("portRpm").value!!, 0.0)
    }

    @Test fun gatewaySessionBoundarySnapsAndDoesNotBlendAcrossPublisherClocks() {
        val first = state("speed" to observation(30.0)).copy(speed = 30.0)
        val next = state("speed" to observation(1.0, 1_000), publication = 1_000)
            .copy(speed = 1.0, sourceSessionId = "server-b")
        val model = HelmPresentationModel(first)
        assertTrue(model.accept(next, 50))
        assertEquals(next, model.render(50))
        assertEquals(1_000L, model.latestRaw.quality.timestampEpochMs)
        assertEquals(1_000L, model.latestRaw.telemetryFields.getValue("speed").quality.timestampEpochMs)
    }

    @Test fun repeatedFramesNeverRefreshObservationOrPublicationTime() {
        val raw = state("speed" to observation(10.0, 19_900))
        val model = HelmPresentationModel(raw)
        for (frameTime in listOf(16L, 500L, 5_001L, 60_000L)) {
            val frame = model.render(frameTime)
            assertEquals(20_000L, frame.quality.timestampEpochMs)
            assertEquals(19_900L, frame.telemetryFields.getValue("speed").quality.timestampEpochMs)
        }
        assertEquals(TelemetryStatus.STALE, model.latestRaw.fieldStatus("speed", 25_001))
        assertEquals(TelemetryStatus.LOST, model.latestRaw.fieldStatus("speed", 50_001))
    }
}
