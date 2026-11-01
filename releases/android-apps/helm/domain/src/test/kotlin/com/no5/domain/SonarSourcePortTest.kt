package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class SonarSourcePortTest {
    private fun request(source: SonarSourcePort, id: Long) = SonarAcquisitionRequest(source.identity.sessionId, id)

    @Test fun identicalClockAndRequestsProduceIdenticalSamplesTimestampsAndOutcomes() {
        var now = 1_000_000L
        val identity = SourceIdentity("deterministic", SourceMode.DEMO, "SONAR DEMO")
        val first = DeterministicMockSonarSource(identity) { now }
        val second = DeterministicMockSonarSource(identity) { now }
        var received = 0
        first.frames.subscribe { if (it != null) received++ }
        assertNull(first.frames.value)
        assertEquals(first.start(request(first, 1)), second.start(request(second, 1)))
        assertEquals(800, received)
        assertEquals(799L, first.frames.value!!.frame.pingId)
        assertEquals(now, first.frames.value!!.frame.timestampEpochMs)
        assertArrayEquals(first.frames.value!!.frame.samples, second.frames.value!!.frame.samples)
        now += 100
        first.tick(); second.tick()
        assertEquals(800L, first.frames.value!!.frame.pingId)
        assertArrayEquals(first.frames.value!!.frame.samples, second.frames.value!!.frame.samples)
        assertEquals(first.frames.value!!.frame.timestampEpochMs, second.frames.value!!.frame.timestampEpochMs)
    }

    @Test fun isolatedInstancesNeverShareAcquisitionOrCalibration() {
        val first = DeterministicMockSonarSource(SourceIdentity("a", SourceMode.DEMO, "A"))
        val second = DeterministicMockSonarSource(SourceIdentity("b", SourceMode.DEMO, "B"))
        first.loadFixture(SonarFixture.CALIBRATED, request(first, 1))
        assertTrue(first.frames.value!!.calibration.calibrated)
        assertFalse(second.status.value.running); assertNull(second.frames.value)
        second.start(request(second, 1))
        assertFalse(second.frames.value!!.calibration.calibrated)
        first.stop(request(first, 2)); second.tick()
        assertEquals(799L, first.frames.value!!.frame.pingId)
        assertEquals(800L, second.frames.value!!.frame.pingId)
        first.close(); second.tick()
        assertEquals(801L, second.frames.value!!.frame.pingId)
    }

    @Test fun closeAndUnsubscribePreventFutureEmissionsAndClosedRequestsAreRejected() {
        val source = DeterministicMockSonarSource()
        var observations = 0
        var frameCount = 0
        val subscription = source.frames.subscribe { observations++; if (it != null) frameCount++ }
        assertEquals(1, observations)
        source.start(request(source, 1)); assertEquals(800, frameCount)
        val beforeUnsubscribe = observations
        subscription.close(); source.tick(); assertEquals(beforeUnsubscribe, observations)
        source.close()
        val frozenStatus = source.status.value
        val closedResult = source.start(request(source, 2)) as SonarAcquisitionOutcome.Rejected
        assertEquals(SonarRejection.CLOSED, closedResult.reason)
        source.tick(); source.close()
        assertEquals(frozenStatus, source.status.value); assertNull(source.frames.value)
        assertFalse(source.capabilities.value.canStart)
    }

    @Test fun wrongSessionAndRepeatedCommandsCannotSeedOrResetTheStream() {
        val source = DeterministicMockSonarSource()
        val wrong = source.start(SonarAcquisitionRequest("old-session", 1)) as SonarAcquisitionOutcome.Rejected
        assertEquals(SonarRejection.WRONG_SESSION, wrong.reason); assertNull(source.frames.value)
        source.start(request(source, 1))
        val before = source.frames.value
        val stale = source.loadFixture(SonarFixture.CLEAR, request(source, 1)) as SonarAcquisitionOutcome.Rejected
        assertEquals(SonarRejection.STALE_REQUEST, stale.reason); assertSame(before, source.frames.value)
        val generation = source.status.value.generation
        source.loadFixture(SonarFixture.CLEAR, request(source, 2))
        assertTrue(source.status.value.generation > generation); assertNull(source.frames.value)
        assertFalse(source.status.value.running)
    }

    @Test fun piUnavailableHasNoSamplesCapabilitiesOrDemoSurface() {
        val source: SonarSourcePort = UnavailablePiSonarSource()
        assertFalse(source is SonarDemoControls)
        assertFalse(source.status.value.connected); assertFalse(source.status.value.running)
        assertFalse(source.capabilities.value.canStart); assertFalse(source.capabilities.value.bottomDetection)
        val start = source.start(request(source, 1)) as SonarAcquisitionOutcome.Rejected
        val stop = source.stop(request(source, 2)) as SonarAcquisitionOutcome.Rejected
        assertEquals(SonarRejection.UNAVAILABLE, start.reason); assertEquals(SonarRejection.UNAVAILABLE, stop.reason)
        repeat(10) { source.tick() }; assertNull(source.frames.value)
    }

    @Test fun throwingCloseObserverCannotLeaveAnyFeedOpenOrSamplesRetained() {
        for (throwingFeed in 0..2) {
            val source = DeterministicMockSonarSource()
            source.start(request(source, 1))
            var armed = false
            val fail: () -> Unit = { if (armed) throw IllegalStateException("observer failed") }
            when (throwingFeed) {
                0 -> source.status.subscribe { fail() }
                1 -> source.capabilities.subscribe { fail() }
                else -> source.frames.subscribe { fail() }
            }
            armed = true
            assertThrows(IllegalStateException::class.java) { source.close() }
            assertFalse(source.status.value.connected); assertFalse(source.status.value.running)
            assertFalse(source.capabilities.value.canStart); assertNull(source.frames.value)
            var lateCallbacks = 0
            source.status.subscribe { lateCallbacks++ }
            source.capabilities.subscribe { lateCallbacks++ }
            source.frames.subscribe { lateCallbacks++ }
            source.tick(); source.close()
            assertEquals(0, lateCallbacks)
            assertEquals(SonarRejection.CLOSED, (source.start(request(source, 2)) as SonarAcquisitionOutcome.Rejected).reason)
        }
        val pi = UnavailablePiSonarSource()
        pi.close()
        var piCallbacks = 0
        pi.status.subscribe { piCallbacks++ }; pi.capabilities.subscribe { piCallbacks++ }; pi.frames.subscribe { piCallbacks++ }
        assertEquals(0, piCallbacks)
    }
}
