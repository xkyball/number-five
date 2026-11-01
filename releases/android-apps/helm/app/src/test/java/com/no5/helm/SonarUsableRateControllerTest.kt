package com.no5.helm

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test

class SonarUsableRateControllerTest {
    @Test fun exactReviewed0423RetainsFreshOwnRateAndRejectsFutureOrSuffixedVersions() {
        val source = Source(); val controller = SonarNativeController(source) { now }
        try {
            controller.screenVisible(true); source.connect()
            fun version(value: String) = source.diagnostic(now).let { input -> input.copy(fields = input.fields.map {
                if (it.group == "host_package" && it.name == "version") it.copy(value = value) else it
            }) }
            source.detail.emit(version("0.4.31"))
            assertEquals(SonarUsableRateState.LIVE, controller.usableRate.state)
            assertEquals(6.0, controller.usableRate.hz!!, 0.0)
            for (unknown in listOf("0.4.32", "0.4.23+unreviewed", "0.4.230")) {
                source.detail.emit(version(unknown))
                assertNull(controller.usableRate.hz)
            }
            source.detail.emit(version("0.4.31"))
            now += 5001; controller.tick()
            assertEquals(SonarUsableRateState.STALE, controller.usableRate.state)
            assertNull(controller.usableRate.hz)
        } finally { controller.dispose() }
    }
    private var now = 1_000_000L
    private class Source : SonarSourcePort {
        override val identity = SourceIdentity("usable-test", SourceMode.PI, "SONAR")
        val state = MutableSourceFeed(SonarSourceStatus(identity))
        override val status: SourceFeed<SonarSourceStatus> get() = state
        override val capabilities = MutableSourceFeed(SonarSourceCapabilities(identity, diagnostics = true))
        val packets = MutableSourceFeed<SonarSourceFrame?>(null)
        override val frames: SourceFeed<SonarSourceFrame?> get() = packets
        val detail = MutableSourceFeed<SonarDiagnostics?>(null)
        override val diagnostics: SourceFeed<SonarDiagnostics?> get() = detail
        var demand = false
        override fun setDiagnosticsVisible(visible: Boolean) { demand = visible }
        override fun start(request: SonarAcquisitionRequest) = SonarAcquisitionOutcome.Rejected(identity.sessionId, request.requestId, SonarRejection.UNSUPPORTED, "UNSUPPORTED")
        override fun stop(request: SonarAcquisitionRequest) = start(request)
        override fun tick() = Unit
        override fun close() = Unit
        fun connect(selection: SonarPingSource = SonarPingSource.OWN, selectionGeneration: Long = 2) {
            state.emit(SonarSourceStatus(identity, 1, true, true, serverSessionId = "host", serverGeneration = 4,
                displaySelection = SonarDisplaySelection(selection, selectionGeneration, 32.0)))
        }
        fun diagnostic(at: Long) = SonarDiagnostics(identity, 4, "host", 20, at, SonarPingSource.OWN,
            listOf(
                SonarDiagnosticField("host_package", "name", "no5-sonard-openecho"),
                SonarDiagnosticField("host_package", "version", "0.4.11"),
                SonarDiagnosticField("display_selection", "source", "own"),
                SonarDiagnosticField("display_selection", "generation", "2"),
                SonarDiagnosticField("transmit", "rate_control.schema", "no5.depth-rate-control/1"),
                SonarDiagnosticField("transmit", "ping_rates.window_s", "5"),
                SonarDiagnosticField("transmit", "ping_rates.observed_s", "5"),
                SonarDiagnosticField("transmit", "ping_rates.complete", "true"),
                SonarDiagnosticField("transmit", "ping_rates.start_device_us", "1000000"),
                SonarDiagnosticField("transmit", "ping_rates.end_device_us", "6000000"),
                SonarDiagnosticField("transmit", "ping_rates.time_basis", "device_marker_us_host_extrapolated_now"),
                SonarDiagnosticField("transmit", "ping_rates.rates.usable.count", "30"),
                SonarDiagnosticField("transmit", "ping_rates.rates.usable.hz", "6")), available = true,
            statusObservedAtEpochMs = at, statusStale = false, statusTimeBasis = "GATEWAY_STATUS_REQUEST_STARTED_AT_UTC")
    }
    @Test fun hostOwnUsableRateNeedsActualHostEvidenceEvenWhenRawPingsAndPublishedRateAreAvailable() {
        val source = Source(); val controller = SonarNativeController(source) { now }
        controller.screenVisible(true); source.connect()
        repeat(20) { index ->
            now += 250
            source.packets.emit(SonarSourceFrame(source.identity, 1, SonarFrame(ShortArray(1024), index.toLong(), now),
                serverSessionId = "host", displaySelection = SonarDisplaySelection(SonarPingSource.OWN, 2, 32.0), pingSource = SonarPingSource.OWN))
        }
        assertNotNull(controller.pingRate.hz)
        assertEquals(SonarUsableRateState.UNKNOWN, controller.usableRate.state)
        assertNull(controller.usableRate.hz)
        source.detail.emit(source.diagnostic(now))
        assertEquals(6.0, controller.usableRate.hz!!, 0.0)
        assertNotEquals(controller.pingRate.hz, controller.usableRate.hz)
        assertEquals(SonarUsableRateState.LIVE, controller.usableRate.state)
        controller.dispose()
    }
    @Test fun hostOwnUsableRateRejectsOldSourceSelectionAndExpiresWithoutFallingBackToRaw() {
        val source = Source(); val controller = SonarNativeController(source) { now }
        controller.screenVisible(true); source.connect()
        source.detail.emit(source.diagnostic(now))
        assertEquals(6.0, controller.usableRate.hz!!, 0.0)
        now += 5001; controller.tick()
        assertEquals(SonarUsableRateState.STALE, controller.usableRate.state)
        assertNull(controller.usableRate.hz)
        source.detail.emit(source.diagnostic(now))
        assertEquals(6.0, controller.usableRate.hz!!, 0.0)
        source.connect(SonarPingSource.NATIVE, 3)
        assertNull(controller.usableRate.hz)
        source.detail.emit(source.diagnostic(now))
        assertNull(controller.usableRate.hz)
        controller.dispose()
    }
    @Test fun exactReviewed0414UsesTheExistingReadPathWhile0421AndConflictingVersionsStayUnknown() {
        val source = Source(); val controller = SonarNativeController(source) { now }
        try {
            controller.screenVisible(true); source.connect()
            fun version(value: String) = source.diagnostic(now).let { input -> input.copy(fields = input.fields.map {
                if (it.group == "host_package" && it.name == "version") it.copy(value = value) else it
            }) }
            source.detail.emit(version("0.4.14"))
            assertEquals(SonarUsableRateState.LIVE, controller.usableRate.state)
            assertEquals(6.0, controller.usableRate.hz!!, 0.0)
            source.detail.emit(version("0.4.32"))
            assertEquals(SonarUsableRateState.UNKNOWN, controller.usableRate.state)
            assertNull(controller.usableRate.hz)
            source.detail.emit(version("0.4.14").let { it.copy(fields = it.fields + SonarDiagnosticField("host_package", "version", "0.4.15")) })
            assertNull(controller.usableRate.hz)
            assertEquals("HOST_USABLE_RATE_AMBIGUOUS_FIELDS", controller.usableRate.reason)
            source.detail.emit(version("0.4.14"))
            assertEquals(6.0, controller.usableRate.hz!!, 0.0)
            now += 5001; controller.tick()
            assertEquals(SonarUsableRateState.STALE, controller.usableRate.state)
            assertNull(controller.usableRate.hz)
        } finally { controller.dispose() }
    }
    @Test fun exactReviewed0415UsesOnlyFreshSourceBoundWindowsAnd0421RemainsUnconfirmed() {
        val source = Source(); val controller = SonarNativeController(source) { now }
        try {
            controller.screenVisible(true); source.connect()
            fun version(value: String) = source.diagnostic(now).let { input -> input.copy(fields = input.fields.map {
                if (it.group == "host_package" && it.name == "version") it.copy(value = value) else it
            }) }
            source.detail.emit(version("0.4.15"))
            assertEquals(SonarUsableRateState.LIVE, controller.usableRate.state)
            assertEquals(6.0, controller.usableRate.hz!!, 0.0)
            for (unknown in listOf("0.4.32", "0.4.15+unreviewed")) {
                source.detail.emit(version(unknown))
                assertEquals(SonarUsableRateState.UNKNOWN, controller.usableRate.state)
                assertNull(controller.usableRate.hz)
            }
            source.detail.emit(version("0.4.15").let { it.copy(fields = it.fields + SonarDiagnosticField("host_package", "version", "0.4.32")) })
            assertEquals("HOST_USABLE_RATE_AMBIGUOUS_FIELDS", controller.usableRate.reason)
            assertNull(controller.usableRate.hz)
            source.detail.emit(version("0.4.15"))
            assertEquals(6.0, controller.usableRate.hz!!, 0.0)
            now += 5001; controller.tick()
            assertEquals(SonarUsableRateState.STALE, controller.usableRate.state)
            assertNull(controller.usableRate.hz)
        } finally { controller.dispose() }
    }
    @Test fun exactReviewed0416RemainsReadOnlyAndCannotBorrowForeignOrStaleWindows() {
        val source = Source(); val controller = SonarNativeController(source) { now }
        try {
            controller.screenVisible(true); source.connect()
            fun version(value: String) = source.diagnostic(now).let { input -> input.copy(fields = input.fields.map {
                if (it.group == "host_package" && it.name == "version") it.copy(value = value) else it
            }) }
            source.detail.emit(version("0.4.16"))
            assertEquals(SonarUsableRateState.LIVE, controller.usableRate.state)
            assertEquals(6.0, controller.usableRate.hz!!, 0.0)
            for (unknown in listOf("0.4.32", "0.4.16+unreviewed", "0.4.160")) {
                source.detail.emit(version(unknown))
                assertEquals(SonarUsableRateState.UNKNOWN, controller.usableRate.state)
                assertNull(controller.usableRate.hz)
            }
            source.detail.emit(version("0.4.16").let { it.copy(fields = it.fields + SonarDiagnosticField("host_package", "version", "0.4.32")) })
            assertEquals("HOST_USABLE_RATE_AMBIGUOUS_FIELDS", controller.usableRate.reason)
            source.detail.emit(version("0.4.16"))
            assertEquals(6.0, controller.usableRate.hz!!, 0.0)
            now += 5001; controller.tick()
            assertEquals(SonarUsableRateState.STALE, controller.usableRate.state)
            assertNull(controller.usableRate.hz)
        } finally { controller.dispose() }
    }
    @Test fun exactReviewed0417DoesNotApproveFutureVersionsOrUnprovenStatusAndSourceContexts() {
        val source = Source(); val controller = SonarNativeController(source) { now }
        try {
            controller.screenVisible(true); source.connect()
            fun version(value: String) = source.diagnostic(now).let { input -> input.copy(fields = input.fields.map {
                if (it.group == "host_package" && it.name == "version") it.copy(value = value) else it
            }) }
            source.detail.emit(version("0.4.17"))
            assertEquals(SonarUsableRateState.LIVE, controller.usableRate.state)
            assertEquals(6.0, controller.usableRate.hz!!, 0.0)
            for (unknown in listOf("0.4.32", "0.4.17+unreviewed", "0.4.170")) {
                source.detail.emit(version(unknown))
                assertEquals(SonarUsableRateState.UNKNOWN, controller.usableRate.state)
                assertNull(controller.usableRate.hz)
            }
            source.detail.emit(version("0.4.17").let { it.copy(fields = it.fields + SonarDiagnosticField("host_package", "version", "0.4.32")) })
            assertEquals("HOST_USABLE_RATE_AMBIGUOUS_FIELDS", controller.usableRate.reason)
            source.detail.emit(version("0.4.17").copy(statusTimeBasis = null))
            assertNull(controller.usableRate.hz)
            source.detail.emit(version("0.4.17").copy(generation = 3))
            assertNull(controller.usableRate.hz)
            source.detail.emit(version("0.4.17"))
            assertEquals(6.0, controller.usableRate.hz!!, 0.0)
            now += 5001; controller.tick()
            assertEquals(SonarUsableRateState.STALE, controller.usableRate.state)
            assertNull(controller.usableRate.hz)
        } finally { controller.dispose() }
    }
    @Test fun exactReviewed0418PreservesTheExistingSourceBoundReadAndFutureProducerRejection() {
        val source = Source(); val controller = SonarNativeController(source) { now }
        try {
            controller.screenVisible(true); source.connect()
            fun version(value: String) = source.diagnostic(now).let { input -> input.copy(fields = input.fields.map {
                if (it.group == "host_package" && it.name == "version") it.copy(value = value) else it
            }) }
            source.detail.emit(version("0.4.18"))
            assertEquals(SonarUsableRateState.LIVE, controller.usableRate.state)
            assertEquals(6.0, controller.usableRate.hz!!, 0.0)
            for (unknown in listOf("0.4.32", "0.4.18+unreviewed", "0.4.180")) {
                source.detail.emit(version(unknown))
                assertEquals(SonarUsableRateState.UNKNOWN, controller.usableRate.state)
                assertNull(controller.usableRate.hz)
            }
            source.detail.emit(version("0.4.18").let { it.copy(fields = it.fields + SonarDiagnosticField("host_package", "version", "0.4.32")) })
            assertEquals("HOST_USABLE_RATE_AMBIGUOUS_FIELDS", controller.usableRate.reason)
            source.detail.emit(version("0.4.18").copy(statusTimeBasis = null))
            assertNull(controller.usableRate.hz)
            source.detail.emit(version("0.4.18").copy(generation = 3))
            assertNull(controller.usableRate.hz)
            source.detail.emit(version("0.4.18"))
            assertEquals(6.0, controller.usableRate.hz!!, 0.0)
            now += 5001; controller.tick()
            assertEquals(SonarUsableRateState.STALE, controller.usableRate.state)
            assertNull(controller.usableRate.hz)
        } finally { controller.dispose() }
    }
    @Test fun exactReviewed0420PreservesTheExistingSourceBoundReadAndFutureProducerRejection() {
        val source = Source(); val controller = SonarNativeController(source) { now }
        try {
            controller.screenVisible(true); source.connect()
            fun version(value: String) = source.diagnostic(now).let { input -> input.copy(fields = input.fields.map {
                if (it.group == "host_package" && it.name == "version") it.copy(value = value) else it
            }) }
            source.detail.emit(version("0.4.20"))
            assertEquals(SonarUsableRateState.LIVE, controller.usableRate.state)
            assertEquals(6.0, controller.usableRate.hz!!, 0.0)
            for (unknown in listOf("0.4.32", "0.4.20+unreviewed", "0.4.200")) {
                source.detail.emit(version(unknown))
                assertEquals(SonarUsableRateState.UNKNOWN, controller.usableRate.state)
                assertNull(controller.usableRate.hz)
            }
            source.detail.emit(version("0.4.20").let { it.copy(fields = it.fields + SonarDiagnosticField("host_package", "version", "0.4.32")) })
            assertEquals("HOST_USABLE_RATE_AMBIGUOUS_FIELDS", controller.usableRate.reason)
            source.detail.emit(version("0.4.20").copy(statusTimeBasis = null))
            assertNull(controller.usableRate.hz)
            source.detail.emit(version("0.4.20").copy(generation = 3))
            assertNull(controller.usableRate.hz)
            source.detail.emit(version("0.4.20"))
            assertEquals(6.0, controller.usableRate.hz!!, 0.0)
            now += 5001; controller.tick()
            assertEquals(SonarUsableRateState.STALE, controller.usableRate.state)
            assertNull(controller.usableRate.hz)
        } finally { controller.dispose() }
    }
    @Test fun theExistingBoundedFeedRemainsDemandedForVisibleRateDetailsButStopsInBackground() {
        val source = Source(); val controller = SonarNativeController(source) { now }
        controller.screenVisible(true)
        assertTrue(source.demand)
        controller.open(SonarDrawerTab.DISPLAY); controller.close()
        assertTrue(source.demand)
        controller.open(SonarDrawerTab.DIAGNOSTICS)
        controller.screenVisible(false)
        assertFalse(source.demand)
        controller.screenVisible(true)
        assertTrue(source.demand)
        controller.dispose()
        assertFalse(source.demand)
    }
    @Test fun delayedAnalysisDoesNotEraseFreshStatusRateOrBecomeANewerPing() {
        val source = Source(); val controller = SonarNativeController(source) { now }
        controller.screenVisible(true); source.connect()
        source.detail.emit(source.diagnostic(now).copy(pingId = 3, observedAtEpochMs = now - 60_000, stale = true))
        assertEquals(6.0, controller.usableRate.hz!!, 0.0)
        assertEquals(now - 60_000, controller.diagnostics!!.observedAtEpochMs!!)
        assertTrue(controller.diagnostics!!.stale)
        source.detail.emit(source.diagnostic(now).copy(pingId = null, observedAtEpochMs = null, stale = true))
        assertEquals(6.0, controller.usableRate.hz!!, 0.0)
        assertNull(controller.diagnostics!!.pingId)
        assertNull(controller.diagnostics!!.observedAtEpochMs)
        source.detail.emit(source.diagnostic(now).copy(statusObservedAtEpochMs = null))
        assertNull(controller.usableRate.hz)
        controller.dispose()
    }
    @Test fun independentlyFreshZeroHostWindowDoesNotFreshenStaleRawDepthOrBecomeUnknown() {
        val source = Source(); val controller = SonarNativeController(source) { now }
        controller.screenVisible(true); source.connect()
        source.state.emit(source.state.value.copy(stale = true))
        val zero = source.diagnostic(now).copy(pingId = null, observedAtEpochMs = null, stale = true,
            fields = source.diagnostic(now).fields.map {
                if (it.name in setOf("ping_rates.rates.usable.count", "ping_rates.rates.usable.hz")) it.copy(value = "0") else it
            })
        source.detail.emit(zero)
        assertEquals(SonarUsableRateState.ZERO, controller.usableRate.state)
        assertEquals(0.0, controller.usableRate.hz!!, 0.0)
        assertEquals(SonarQuality.DEGRADED, controller.snapshot.quality)
        assertNull(controller.snapshot.depthM)
        assertTrue(controller.diagnostics!!.stale)
        controller.dispose()
    }
    @Test fun contrastLabelUsesFrozenAndInspectedFrameContextInsteadOfNewerLivePolicy() {
        val source = Source(); val controller = SonarNativeController(source) { now }
        controller.screenVisible(true); source.connect()
        val original = SonarAmplitude(4095, 12, true, "HELLO_12bit_checked_host_contract",
            "max_pool_or_interpolate_without_amplitude_scaling", 1, false)
        val derived = original.copy(displayTransform = "background_reduction_then_max_pool_or_interpolate", noiseActive = true)
        fun next(id: Long, amplitude: SonarAmplitude) {
            source.packets.emit(SonarSourceFrame(source.identity, 1, SonarFrame(ShortArray(1024), id, now),
                serverSessionId = "host", displaySelection = SonarDisplaySelection(SonarPingSource.OWN, 2, 32.0),
                pingSource = SonarPingSource.OWN, hostPackageVersion = "0.4.13", amplitude = amplitude))
        }
        next(1, original); controller.model.setFreeze(true)
        now += 100; next(2, derived)
        assertEquals(com.no5.ui.No5Text.ui("ADC-Anzeige · unbearbeitet"), controller.profileInterpretationLabel())
        controller.inspect(1f, .5f)
        assertEquals(original, controller.cursor!!.amplitudeContext)
        assertEquals(com.no5.ui.No5Text.ui("ADC-Anzeige · unbearbeitet"), controller.profileInterpretationLabel())
        controller.model.setFreeze(false); controller.applyCrop(0.0, 1023.0)
        assertEquals(com.no5.ui.No5Text.ui("Anzeige · gemischte Kontexte"), controller.profileInterpretationLabel())
        controller.inspect(1f, .5f)
        assertEquals(derived, controller.cursor!!.amplitudeContext)
        assertEquals(com.no5.ui.No5Text.ui("ADC-Kontrast · bereinigt"), controller.profileInterpretationLabel())
        assertEquals(1L, controller.cursor!!.amplitudeContext!!.noiseRevision)
        controller.dispose()
    }
}
