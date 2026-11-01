package com.no5.helm

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic source contract only; the real controller owns all counters and admission. */
class SonarSourceRateEpochTest {
    private class Source : SonarSourcePort {
        override val identity = SourceIdentity("synthetic-source-rate", SourceMode.PI, "SONAR")
        val states = MutableSourceFeed(SonarSourceStatus(identity))
        override val status: SourceFeed<SonarSourceStatus> get() = states
        override val capabilities = MutableSourceFeed(SonarSourceCapabilities(identity, sourceSelection = true, diagnostics = true))
        val packets = MutableSourceFeed<SonarSourceFrame?>(null)
        override val frames: SourceFeed<SonarSourceFrame?> get() = packets
        val selections = MutableSourceFeed(SonarSourceSelectionState(identity))
        override val sourceState: SourceFeed<SonarSourceSelectionState> get() = selections
        val receipts = MutableSourceFeed<SonarSourceSelectionOutcome?>(null)
        override val sourceOutcomes: SourceFeed<SonarSourceSelectionOutcome?> get() = receipts
        val detail = MutableSourceFeed<SonarDiagnostics?>(null)
        override val diagnostics: SourceFeed<SonarDiagnostics?> get() = detail
        var latestRequest: SonarSourceSelectionRequest? = null
        override fun selectSource(request: SonarSourceSelectionRequest): SonarSourceSelectionOutcome {
            latestRequest = request
            return SonarSourceSelectionOutcome(identity.sessionId, request.requestId, request.requestId,
                request.source, SonarFilterCommandStatus.ACCEPTED, "NONE")
        }
        override fun start(request: SonarAcquisitionRequest) = SonarAcquisitionOutcome.Rejected(
            identity.sessionId, request.requestId, SonarRejection.UNSUPPORTED, "SYNTHETIC READ TEST")
        override fun stop(request: SonarAcquisitionRequest) = start(request)
        override fun tick() = Unit
        override fun close() = Unit
        fun status(source: SonarPingSource, generation: Long, selectionGeneration: Long, now: Long) {
            val selection = SonarDisplaySelection(source, selectionGeneration)
            selections.emit(SonarSourceSelectionState(identity, generation, true, selection))
            states.emit(SonarSourceStatus(identity, generation, true, true, serverSessionId = "synthetic-host",
                serverGeneration = generation, publishedAtEpochMs = now, displaySelection = selection))
        }
        fun diagnostic(source: SonarPingSource, generation: Long, selectionGeneration: Long, now: Long, count: Long) =
            SonarDiagnostics(identity, generation, "synthetic-host", null, null, source, listOf(
                SonarDiagnosticField("host_package", "name", "no5-sonard-openecho"),
                SonarDiagnosticField("host_package", "version", "0.4.15"),
                SonarDiagnosticField("display_selection", "source", source.name.lowercase()),
                SonarDiagnosticField("display_selection", "generation", selectionGeneration.toString()),
                SonarDiagnosticField("transmit", "rate_control.schema", "no5.depth-rate-control/1"),
                SonarDiagnosticField("transmit", "ping_rates.window_s", "5"),
                SonarDiagnosticField("transmit", "ping_rates.observed_s", "5"),
                SonarDiagnosticField("transmit", "ping_rates.complete", "true"),
                SonarDiagnosticField("transmit", "ping_rates.start_device_us", "1000000"),
                SonarDiagnosticField("transmit", "ping_rates.end_device_us", "6000000"),
                SonarDiagnosticField("transmit", "ping_rates.time_basis", "device_marker_us_host_extrapolated_now"),
                SonarDiagnosticField("transmit", "ping_rates.rates.usable.count", count.toString()),
                SonarDiagnosticField("transmit", "ping_rates.rates.usable.hz", (count / 5.0).toString())),
                available = true, stale = true, statusObservedAtEpochMs = now, statusStale = false,
                statusTimeBasis = "GATEWAY_STATUS_REQUEST_STARTED_AT_UTC")
    }

    @Test fun appliedReceiptDoesNotRelabelAnOldWindowAndConfirmedNativeAllOwnEpochsKeepCountersSeparate() {
        var now = 1_000_000L
        val source = Source()
        val controller = SonarNativeController(source) { now }
        try {
            controller.screenVisible(true)
            var selected = SonarPingSource.OWN
            var generation = 10L
            var selectionGeneration = 20L
            source.status(selected, generation, selectionGeneration, now)
            var previous = source.diagnostic(selected, generation, selectionGeneration, now, 97)
            source.detail.emit(previous)
            assertEquals(19.4, controller.usableRate.hz!!, 0.0)
            for ((next, count) in listOf(SonarPingSource.NATIVE to 97L, SonarPingSource.ALL to 95L, SonarPingSource.OWN to 83L)) {
                controller.selectSource(next)
                val request = source.latestRequest!!
                source.receipts.emit(SonarSourceSelectionOutcome(source.identity.sessionId, request.requestId,
                    request.requestId, next, SonarFilterCommandStatus.APPLIED, "NONE"))
                assertEquals(SonarFilterCommandStatus.APPLIED, controller.sourceOutcome!!.status)
                assertEquals("A receipt does not replace the authoritative stream epoch", selected, controller.model.selectedPingSource)
                assertEquals(previous.generation, controller.usableRate.epoch!!.generation)

                now += 1000; generation++; selectionGeneration++
                source.status(next, generation, selectionGeneration, now)
                assertEquals(next, controller.model.selectedPingSource)
                assertEquals(0, controller.model.ring.count)
                assertNull(controller.diagnostics); assertNull(controller.usableRate.hz)
                source.detail.emit(previous)
                assertNull("A delayed prior-epoch diagnostic cannot reappear", controller.diagnostics)
                assertNull(controller.usableRate.hz)

                val fresh = source.diagnostic(next, generation, selectionGeneration, now, count)
                source.detail.emit(fresh)
                assertEquals(SonarUsableRateState.LIVE, controller.usableRate.state)
                assertEquals("HOST_OWN_USABLE_5S", controller.usableRate.reason)
                assertEquals(count / 5.0, controller.usableRate.hz!!, 0.0)
                assertEquals(next, controller.usableRate.displayedSelection)
                source.packets.emit(SonarSourceFrame(source.identity, generation,
                    SonarFrame(ShortArray(SONAR_BINS), 1, now), serverSessionId = "synthetic-host",
                    displaySelection = SonarDisplaySelection(next, selectionGeneration),
                    pingSource = if (next == SonarPingSource.ALL) SonarPingSource.NATIVE else next,
                    serverGeneration = generation))
                assertEquals("The actual measured ping is admitted immediately", 1, controller.model.ring.count)
                // Rate digits/counts publish at most twice a second, independently
                // of immediate raw admission and the separately observed host window.
                now += 500; controller.refresh()
                assertEquals(1, controller.pingRate.eventCount)
                assertEquals(count, controller.usableRate.count!!)
                assertNotEquals(controller.pingRate.eventCount.toLong(), controller.usableRate.count)
                selected = next; previous = fresh
            }
            now += 5001; controller.refresh()
            assertEquals(SonarUsableRateState.STALE, controller.usableRate.state)
            assertNull(controller.usableRate.hz)
            assertEquals(SonarRateState.ZERO, controller.pingRate.state)
            assertEquals(0, controller.pingRate.eventCount)
            assertEquals(1, controller.model.ring.count)
        } finally { controller.dispose() }
    }
}
