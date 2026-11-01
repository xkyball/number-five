package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class DepthHistoryTest {
    private fun raw(stamp: Long, depth: Double? = 5.0, status: TelemetryStatus = TelemetryStatus.LIVE,
                    source: String = "PI", run: String = "run-a", elapsed: Double? = null) = HelmState(
        depth = depth,
        quality = TelemetryQuality(status, stamp, source, simulated = true),
        simulationRunId = run,
        simulationElapsedSeconds = elapsed,
    )

    @Test fun usesDepthObservationTimestampAndDoesNotRegraphRetainedFields() {
        val history = DepthHistory()
        val field=TelemetryValue(8.0,TelemetryQuality(TelemetryStatus.LIVE,1000,"DEPTH_SENSOR",simulated=true))
        val sample=raw(1500,99.0).copy(telemetryFields=mapOf("depth" to field))
        assertTrue(history.accept(sample,1500,"a"))
        assertFalse(history.accept(sample.copy(quality=sample.quality.copy(timestampEpochMs=1600)),1600,"a"))
        val point=history.snapshot(1600).points.single()
        assertEquals(1000L,point.timestampEpochMs)
        assertEquals(8.0,point.depthMeters,0.0)
        assertFalse(history.accept(sample.copy(quality=sample.quality.copy(timestampEpochMs=7000)),7000,"a"))
    }

    @Test fun absentDepthFieldIsUnknownAndUnrelatedStaleFieldDoesNotSpoilDepth() {
        val history = DepthHistory()
        val fresh=TelemetryValue(4.0,TelemetryQuality(TelemetryStatus.LIVE,1000,"DEPTH_SENSOR"))
        assertFalse(history.accept(raw(1000).copy(telemetryFields=mapOf("speed" to fresh)),1000,"a"))
        val independentlyFresh=raw(1000,status=TelemetryStatus.STALE).copy(telemetryFields=mapOf("depth" to fresh))
        assertEquals(TelemetryStatus.LIVE,DepthHistory.status(independentlyFresh,1000))
        assertTrue(history.accept(independentlyFresh,1000,"a"))
        assertFalse(history.accept(independentlyFresh.copy(quality=independentlyFresh.quality.copy(status=TelemetryStatus.LOST)),1100,"a"))
    }

    @Test fun zeroIsRealAndFramesNeverInventSamples() {
        val history = DepthHistory()
        assertTrue(history.accept(raw(1000, 0.0), 1000, "a"))
        repeat(100) { history.snapshot(1000L + it) }
        val points = history.snapshot(1200).points
        assertEquals(1, points.size)
        assertEquals(0.0, points.single().depthMeters, 0.0)
        assertEquals(1000L, points.single().timestampEpochMs)
    }

    @Test fun displayFramesReuseAnImmutableSourcePointList() {
        val history = DepthHistory()
        history.accept(raw(1000), 1000, "a")
        val first = history.snapshot(1000)
        assertSame(first.points, history.snapshot(1016).points)
        assertSame(first.points, history.snapshot(2000).points)
        history.accept(raw(2050), 2050, "a")
        assertNotSame(first.points, history.snapshot(2050).points)
        assertEquals(1, first.points.size)
    }

    @Test fun duplicateAndOutOfOrderSourceTimestampsAreNotRecorded() {
        val history = DepthHistory()
        history.accept(raw(1000), 1000, "a")
        assertFalse(history.accept(raw(1000, 8.0), 1100, "a"))
        assertFalse(history.accept(raw(900, 9.0), 1100, "a"))
        assertEquals(5.0, history.snapshot(1100).points.single().depthMeters, 0.0)
    }

    @Test fun sourceSessionAndRunChangesClearOldHistory() {
        val history = DepthHistory()
        history.accept(raw(1000), 1000, "a")
        history.accept(raw(1100), 1100, "b")
        assertEquals(listOf(1100L), history.snapshot(1100).points.map { it.timestampEpochMs })
        history.accept(raw(1200, source = "OTHER"), 1200, "b")
        assertEquals(1, history.snapshot(1200).points.size)
        history.accept(raw(900, source = "OTHER", run = "run-b"), 900, "b")
        assertEquals(listOf(900L), history.snapshot(900).points.map { it.timestampEpochMs })
    }

    @Test fun gatewaySessionChangeClearsHistoryEvenWhenAppSessionAndRunAreUnchanged() {
        val history=DepthHistory()
        history.accept(raw(1000).copy(sourceSessionId="server-a"),1000,"app-a")
        history.accept(raw(1100).copy(sourceSessionId="server-b"),1100,"app-a")
        assertEquals(listOf(1100L),history.snapshot(1100).points.map{it.timestampEpochMs})
    }

    @Test fun simulationResetIsDetectedEvenWithoutChangedRunId() {
        val history = DepthHistory()
        history.accept(raw(1000, elapsed = 20.0), 1000, "a")
        history.accept(raw(1100, elapsed = 0.0), 1100, "a")
        assertEquals(listOf(1100L), history.snapshot(1100).points.map { it.timestampEpochMs })
    }

    @Test fun staleLostInvalidAndMissingDepthAllBreakTheTrace() {
        for (status in listOf(TelemetryStatus.STALE, TelemetryStatus.LOST, TelemetryStatus.INVALID, TelemetryStatus.UNAVAILABLE)) {
            val history = DepthHistory()
            history.accept(raw(1000), 1000, "a")
            assertFalse(history.accept(raw(1100, status = status), 1100, "a"))
            history.accept(raw(1200), 1200, "a")
            assertTrue(history.snapshot(1200).points.last().breakBefore)
        }
        for (depth in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 12001.0)) {
            val history = DepthHistory()
            history.accept(raw(1000), 1000, "a")
            assertFalse(history.accept(raw(1100, depth), 1100, "a"))
            history.accept(raw(1200), 1200, "a")
            assertTrue(history.snapshot(1200).points.last().breakBefore)
        }
    }

    @Test fun observedExpiryAndMissingResponseGapNeverReconnectLines() {
        val history = DepthHistory()
        history.accept(raw(1000), 1000, "a")
        history.snapshot(6100, TelemetryStatus.STALE)
        history.accept(raw(6200), 6200, "a")
        assertTrue(history.snapshot(6200).points.last().breakBefore)
        history.accept(raw(6250), 6250, "a")
        assertFalse(history.snapshot(6250).points.last().breakBefore)
        history.accept(raw(8250), 8250, "a")
        assertTrue(history.snapshot(8250).points.last().breakBefore)
    }

    @Test fun oldNetworkSimulationAndMissingMetadataCannotAppend() {
        val history = DepthHistory()
        assertFalse(history.accept(raw(1000), 7000, "a"))
        assertFalse(history.accept(raw(1000).copy(quality = raw(1000).quality.copy(demo = true)), 7000, "a"))
        assertFalse(history.accept(raw(1000), 2500, "a", FreshnessPolicy(1000, 2000)))
        assertFalse(history.accept(raw(1000).copy(quality = TelemetryQuality(TelemetryStatus.LIVE, source = "PI")), 1000, "a"))
        assertFalse(history.accept(raw(1000, source = ""), 1000, "a"))
        assertTrue(history.snapshot(7000).points.isEmpty())
    }

    @Test fun retainsAtMostTwoMinutesAndBoundedPointCount() {
        val history = DepthHistory()
        for (stamp in 0L..130_000L step 50L) history.accept(raw(stamp), stamp, "a")
        val result = history.snapshot(130_000)
        assertEquals(2401, result.points.size)
        assertEquals(10_000L, result.points.first().timestampEpochMs)
        assertEquals(130_000L, result.endEpochMs)
        assertTrue(history.snapshot(250_001).points.isEmpty())
        val bounded = DepthHistory(maxPoints = 3)
        for (stamp in 1000L..1005L) bounded.accept(raw(stamp), stamp, "a")
        assertEquals(listOf(1003L, 1004L, 1005L), bounded.snapshot(1005).points.map { it.timestampEpochMs })
    }

    @Test fun lifecycleGapAndFutureTimestampAreNotDrawnAsFreshContinuousHistory() {
        val history = DepthHistory()
        history.accept(raw(1000), 1000, "a")
        history.markGap()
        history.accept(raw(1100), 1100, "a")
        assertTrue(history.snapshot(1100).points.last().breakBefore)
        history.accept(raw(1200), 1100, "a")
        assertEquals(2, history.snapshot(1100).points.size)
        assertEquals(3, history.snapshot(1200).points.size)
    }
    @Test fun repeatedClockSnapshotReusesWrapperButNewPointsClearAndFutureArrivalInvalidate() {
        val history=DepthHistory()
        history.accept(raw(1000),1000,"a")
        val first=history.snapshot(1000)
        assertSame(first,history.snapshot(1000))
        assertNotSame(first,history.snapshot(1001))
        history.accept(raw(1100),1000,"a")
        assertEquals(1,history.snapshot(1000).points.size)
        assertEquals(2,history.snapshot(1100).points.size)
        assertEquals(1,history.snapshot(1000).points.size)
        history.clear()
        assertTrue(history.snapshot(1000).points.isEmpty())
        assertEquals(1,first.points.size)
    }

}
