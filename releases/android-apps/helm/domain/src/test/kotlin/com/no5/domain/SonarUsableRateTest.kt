package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class SonarUsableRateTest {
    private val now = 1_000_000L
    private val identity = SourceIdentity("sonar-test", SourceMode.PI, "SONAR")
    private val epoch = SonarEpoch("host", 4, 2)
    private fun diagnostics(source: SonarPingSource = SonarPingSource.OWN) = SonarDiagnostics(identity, 4, "host", 7, now, source,
        listOf(
            SonarDiagnosticField("host_package", "name", "no5-sonard-openecho"),
            SonarDiagnosticField("host_package", "version", "0.4.11"),
            SonarDiagnosticField("display_selection", "source", source.name.lowercase()),
            SonarDiagnosticField("display_selection", "generation", "2"),
            SonarDiagnosticField("display_selection", "published_hz", "32"),
            SonarDiagnosticField("transmit", "run.usable_hz", "29"),
            SonarDiagnosticField("transmit", "rate_control.schema", "no5.depth-rate-control/1"),
            SonarDiagnosticField("transmit", "ping_rates.window_s", "5"),
            SonarDiagnosticField("transmit", "ping_rates.observed_s", "5"),
            SonarDiagnosticField("transmit", "ping_rates.complete", "true"),
            SonarDiagnosticField("transmit", "ping_rates.start_device_us", "1000000"),
            SonarDiagnosticField("transmit", "ping_rates.end_device_us", "6000000"),
            SonarDiagnosticField("transmit", "ping_rates.time_basis", "device_marker_us_host_extrapolated_now"),
            SonarDiagnosticField("transmit", "ping_rates.rates.own.hz", "18"),
            SonarDiagnosticField("transmit", "ping_rates.rates.raw.hz", "17"),
            SonarDiagnosticField("transmit", "ping_rates.rates.usable.count", "30"),
            SonarDiagnosticField("transmit", "ping_rates.rates.usable.hz", "6")), available = true,
        statusObservedAtEpochMs = now, statusStale = false, statusTimeBasis = "GATEWAY_STATUS_REQUEST_STARTED_AT_UTC")
    private fun change(value: SonarDiagnostics, name: String, replacement: String?) = value.copy(fields = value.fields.mapNotNull {
        if (it.name != name) it else replacement?.let { text -> it.copy(value = text) }
    })
    private fun project(value: SonarDiagnostics? = diagnostics(), expected: SonarEpoch = epoch,
        selection: SonarPingSource = SonarPingSource.OWN, at: Long = now, connected: Boolean = true) =
        SonarUsableRate.fromDiagnostics(value, identity, expected, selection, at, connected)

    @Test fun hostOwnUsableValueIsNotPublishedOwnRawOrRunAverageAndHasNoTenHzThreshold() {
        val value = project()
        assertEquals(SonarUsableRateState.LIVE, value.state)
        assertEquals(6.0, value.hz!!, 0.0)
        assertEquals(30L, value.count!!)
        assertEquals(5.0, value.observedSeconds!!, 0.0)
        assertTrue(value.complete!!)
        assertEquals(epoch, value.epoch)
    }
    @Test fun exactReviewed0412Through0423AreAdditiveButNextUnknownProducerStaysUnconfirmed() {
        for (version in listOf("0.4.12", "0.4.13", "0.4.14", "0.4.15", "0.4.16", "0.4.17", "0.4.18", "0.4.19", "0.4.20", "0.4.21", "0.4.22", "0.4.23", "0.4.24", "0.4.25", "0.4.26", "0.4.27", "0.4.28", "0.4.29", "0.4.30", "0.4.31")) {
            val checked = project(change(diagnostics(), "version", version))
            assertEquals(SonarUsableRateState.LIVE, checked.state)
            assertEquals(6.0, checked.hz!!, 0.0)
        }
        val unknown = project(change(diagnostics(), "version", "0.4.32"))
        assertEquals(SonarUsableRateState.UNKNOWN, unknown.state)
        assertNull(unknown.hz)
        assertEquals("HOST_USABLE_RATE_PRODUCER_UNCONFIRMED", unknown.reason)
    }
    @Test fun reviewed0414DoesNotApproveVersionSuffixesContradictoryProvenanceOrUnprovenWindow() {
        val reviewed = change(diagnostics(), "version", "0.4.14")
        for (invalid in listOf(change(reviewed, "version", "0.4.14+unreviewed"),
            change(reviewed, "version", "0.4.140"), change(reviewed, "name", "another-producer"))) {
            val value = project(invalid)
            assertEquals(SonarUsableRateState.UNKNOWN, value.state)
            assertNull(value.hz)
            assertEquals("HOST_USABLE_RATE_PRODUCER_UNCONFIRMED", value.reason)
        }
        val conflicting = reviewed.copy(fields = reviewed.fields + SonarDiagnosticField("host_package", "version", "0.4.15"))
        assertEquals("HOST_USABLE_RATE_AMBIGUOUS_FIELDS", project(conflicting).reason)
        assertNull(project(conflicting).hz)
        assertEquals(SonarUsableRateState.UNKNOWN, project(change(reviewed, "ping_rates.rates.usable.hz", "7")).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(reviewed.copy(statusTimeBasis = null)).state)
        assertEquals(SonarUsableRateState.STALE, project(reviewed, at = now + 5001).state)
        val model = SonarModel { now }
        model.setAnalysis(null, SonarBottom(), null, false, "0.4.14")
        assertFalse("A reviewed rate version does not invent legacy ADC normalization", model.amplitudeVerified)
        assertEquals(65535, model.amplitudeFullScale)
    }
    @Test fun exactReviewed0415KeepsProvenanceWindowAndExplicitScaleGatesWithoutApprovingFutureVersions() {
        val reviewed = change(diagnostics(), "version", "0.4.15")
        assertEquals(6.0, project(reviewed).hz!!, 0.0)
        for (invalid in listOf(change(reviewed, "version", "0.4.32"),
            change(reviewed, "version", "0.4.15+unreviewed"), change(reviewed, "version", "0.4.150"),
            change(reviewed, "name", "another-producer"))) {
            assertEquals("HOST_USABLE_RATE_PRODUCER_UNCONFIRMED", project(invalid).reason)
            assertNull(project(invalid).hz)
        }
        assertEquals("HOST_USABLE_RATE_AMBIGUOUS_FIELDS", project(reviewed.copy(fields = reviewed.fields +
            SonarDiagnosticField("host_package", "version", "0.4.32"))).reason)
        assertEquals(SonarUsableRateState.UNKNOWN, project(change(reviewed, "ping_rates.rates.usable.hz", "7")).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(reviewed.copy(statusTimeBasis = null)).state)
        assertEquals(SonarUsableRateState.STALE, project(reviewed, at = now + 5001).state)
        val model = SonarModel { now }
        model.setAnalysis(null, SonarBottom(), null, false, "0.4.15")
        assertFalse(model.amplitudeVerified)
        assertEquals(65535, model.amplitudeFullScale)
    }
    @Test fun exactReviewed0416OnlyApprovesTheExistingSourceBoundFiveSecondReadContract() {
        val reviewed = change(diagnostics(), "version", "0.4.16")
        assertEquals(6.0, project(reviewed).hz!!, 0.0)
        for (invalid in listOf(change(reviewed, "version", "0.4.32"),
            change(reviewed, "version", "0.4.16+unreviewed"), change(reviewed, "version", "0.4.160"),
            change(reviewed, "name", "another-producer"))) {
            assertEquals("HOST_USABLE_RATE_PRODUCER_UNCONFIRMED", project(invalid).reason)
            assertNull(project(invalid).hz)
        }
        assertEquals("HOST_USABLE_RATE_AMBIGUOUS_FIELDS", project(reviewed.copy(fields = reviewed.fields +
            SonarDiagnosticField("host_package", "version", "0.4.32"))).reason)
        assertEquals(SonarUsableRateState.UNKNOWN, project(change(reviewed, "ping_rates.rates.usable.hz", "7")).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(reviewed.copy(statusTimeBasis = null)).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(reviewed.copy(generation = 3)).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(reviewed, selection = SonarPingSource.NATIVE).state)
        assertEquals(SonarUsableRateState.STALE, project(reviewed, at = now + 5001).state)
        val model = SonarModel { now }
        model.setAnalysis(null, SonarBottom(), null, false, "0.4.16")
        assertFalse("Exact read review never grants a legacy amplitude fallback", model.amplitudeVerified)
        assertEquals(65535, model.amplitudeFullScale)
    }
    @Test fun exactReviewed0417PreservesReadProvenanceFreshnessSourceAndWindowWithoutAmplitudeFallback() {
        val reviewed = change(diagnostics(), "version", "0.4.17")
        assertEquals(6.0, project(reviewed).hz!!, 0.0)
        for (invalid in listOf(change(reviewed, "version", "0.4.32"),
            change(reviewed, "version", "0.4.17+unreviewed"), change(reviewed, "version", "0.4.170"),
            change(reviewed, "name", "another-producer"))) {
            assertEquals("HOST_USABLE_RATE_PRODUCER_UNCONFIRMED", project(invalid).reason)
            assertNull(project(invalid).hz)
        }
        assertEquals("HOST_USABLE_RATE_AMBIGUOUS_FIELDS", project(reviewed.copy(fields = reviewed.fields +
            SonarDiagnosticField("host_package", "version", "0.4.32"))).reason)
        assertEquals(SonarUsableRateState.UNKNOWN, project(change(reviewed, "ping_rates.rates.usable.hz", "7")).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(reviewed.copy(statusTimeBasis = null)).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(reviewed.copy(generation = 3)).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(reviewed, selection = SonarPingSource.NATIVE).state)
        assertEquals(SonarUsableRateState.STALE, project(reviewed, at = now + 5001).state)
        val model = SonarModel { now }
        model.setAnalysis(null, SonarBottom(), null, false, "0.4.17")
        assertFalse("Exact READ review never grants legacy ADC normalization", model.amplitudeVerified)
        assertEquals(65535, model.amplitudeFullScale)
    }
    @Test fun exactReviewed0418OnlyAddsTheReviewedProducerAndPreservesSourceWindowAndScaleGuards() {
        val reviewed = change(diagnostics(), "version", "0.4.18")
        assertEquals(6.0, project(reviewed).hz!!, 0.0)
        for (invalid in listOf(change(reviewed, "version", "0.4.32"),
            change(reviewed, "version", "0.4.18+unreviewed"), change(reviewed, "version", "0.4.180"),
            change(reviewed, "name", "another-producer"))) {
            assertEquals("HOST_USABLE_RATE_PRODUCER_UNCONFIRMED", project(invalid).reason)
            assertNull(project(invalid).hz)
        }
        assertEquals("HOST_USABLE_RATE_AMBIGUOUS_FIELDS", project(reviewed.copy(fields = reviewed.fields +
            SonarDiagnosticField("host_package", "version", "0.4.32"))).reason)
        assertEquals(SonarUsableRateState.UNKNOWN, project(change(reviewed, "ping_rates.rates.usable.hz", "7")).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(reviewed.copy(statusTimeBasis = null)).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(reviewed.copy(generation = 3)).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(reviewed, selection = SonarPingSource.NATIVE).state)
        assertEquals(SonarUsableRateState.STALE, project(reviewed, at = now + 5001).state)
        val model = SonarModel { now }
        model.setAnalysis(null, SonarBottom(), null, false, "0.4.18")
        assertFalse("Exact READ review never grants legacy ADC normalization", model.amplitudeVerified)
        assertEquals(65535, model.amplitudeFullScale)
    }
    @Test fun exactReviewed0419OnlyAddsTheReviewedProducerAndPreservesSourceWindowAndScaleGuards() {
        val reviewed = change(diagnostics(), "version", "0.4.19")
        assertEquals(6.0, project(reviewed).hz!!, 0.0)
        for (invalid in listOf(change(reviewed, "version", "0.4.32"),
            change(reviewed, "version", "0.4.19+unreviewed"), change(reviewed, "version", "0.4.190"),
            change(reviewed, "name", "another-producer"))) {
            assertEquals("HOST_USABLE_RATE_PRODUCER_UNCONFIRMED", project(invalid).reason)
            assertNull(project(invalid).hz)
        }
        assertEquals("HOST_USABLE_RATE_AMBIGUOUS_FIELDS", project(reviewed.copy(fields = reviewed.fields +
            SonarDiagnosticField("host_package", "version", "0.4.32"))).reason)
        assertEquals(SonarUsableRateState.UNKNOWN, project(change(reviewed, "ping_rates.rates.usable.hz", "7")).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(reviewed.copy(statusTimeBasis = null)).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(reviewed.copy(generation = 3)).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(reviewed, selection = SonarPingSource.NATIVE).state)
        assertEquals(SonarUsableRateState.STALE, project(reviewed, at = now + 5001).state)
        val model = SonarModel { now }
        model.setAnalysis(null, SonarBottom(), null, false, "0.4.19")
        assertFalse("Exact READ review never grants legacy ADC normalization", model.amplitudeVerified)
        assertEquals(65535, model.amplitudeFullScale)
    }
    @Test fun exactReviewed0420OnlyAddsTheReviewedProducerAndPreservesSourceWindowAndScaleGuards() {
        val reviewed = change(diagnostics(), "version", "0.4.20")
        assertEquals(6.0, project(reviewed).hz!!, 0.0)
        for (invalid in listOf(change(reviewed, "version", "0.4.32"),
            change(reviewed, "version", "0.4.20+unreviewed"), change(reviewed, "version", "0.4.200"),
            change(reviewed, "name", "another-producer"))) {
            assertEquals("HOST_USABLE_RATE_PRODUCER_UNCONFIRMED", project(invalid).reason)
            assertNull(project(invalid).hz)
        }
        assertEquals("HOST_USABLE_RATE_AMBIGUOUS_FIELDS", project(reviewed.copy(fields = reviewed.fields +
            SonarDiagnosticField("host_package", "version", "0.4.32"))).reason)
        assertEquals(SonarUsableRateState.UNKNOWN, project(change(reviewed, "ping_rates.rates.usable.hz", "7")).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(reviewed.copy(statusTimeBasis = null)).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(reviewed.copy(generation = 3)).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(reviewed, selection = SonarPingSource.NATIVE).state)
        assertEquals(SonarUsableRateState.STALE, project(reviewed, at = now + 5001).state)
        val model = SonarModel { now }
        model.setAnalysis(null, SonarBottom(), null, false, "0.4.20")
        assertFalse("Exact READ review never grants legacy ADC normalization", model.amplitudeVerified)
        assertEquals(65535, model.amplitudeFullScale)
    }
    @Test fun absentAndUnprovenUsableCannotBorrowOtherRates() {
        for (invalid in listOf(null, change(diagnostics(), "ping_rates.rates.usable.count", null),
            change(diagnostics(), "ping_rates.rates.usable.hz", null),
            change(diagnostics(), "ping_rates.rates.usable.count", "30.0"),
            change(diagnostics(), "ping_rates.rates.usable.hz", "32"),
            change(diagnostics(), "ping_rates.time_basis", "host_receipt"),
            change(diagnostics(), "version", "0.4.10"))) {
            val value = project(invalid)
            assertEquals(SonarUsableRateState.UNKNOWN, value.state); assertNull(value.hz)
        }
    }
    @Test fun partialWindowPreservesCountCoverageButNeverClaimsTheCompleteWindowRate() {
        var input = change(diagnostics(), "ping_rates.observed_s", "2")
        input = change(input, "ping_rates.end_device_us", "3000000")
        input = change(input, "ping_rates.complete", "false")
        input = change(input, "ping_rates.rates.usable.count", "12")
        val value = project(input)
        assertEquals(SonarUsableRateState.PARTIAL, value.state); assertNull(value.hz)
        assertEquals(12L, value.count!!); assertEquals(2.0, value.observedSeconds!!, 0.0)
    }
    @Test fun explicitCompleteZeroIsZeroWhereMissingEventsRemainUnknown() {
        var input = change(diagnostics(), "ping_rates.rates.usable.count", "0")
        input = change(input, "ping_rates.rates.usable.hz", "0")
        assertEquals(SonarUsableRateState.ZERO, project(input).state)
        assertEquals(0.0, project(input).hz!!, 0.0)
        assertEquals(SonarUsableRateState.UNKNOWN, project(change(input, "ping_rates.rates.usable.hz", null)).state)
    }
    @Test fun transportStaleOldStatusObservationAndExplicitlyStaleStatusInvalidateRate() {
        assertEquals(SonarUsableRateState.STALE, project(connected = false).state)
        assertEquals(SonarUsableRateState.STALE, project(at = now + 5001).state)
        assertEquals(SonarUsableRateState.STALE, project(diagnostics().copy(statusStale = true)).state)
        assertEquals(SonarUsableRateState.STALE, project(diagnostics().copy(statusObservedAtEpochMs = now - 5001)).state)
    }
    @Test fun missingStatusProofNeverBorrowsFreshOrHistoricalAnalysisTime() {
        for (input in listOf(diagnostics().copy(statusObservedAtEpochMs = null),
            diagnostics().copy(statusStale = null), diagnostics().copy(statusTimeBasis = null),
            diagnostics().copy(statusTimeBasis = "ANALYSIS_PING_TIME"))) {
            assertEquals(SonarUsableRateState.UNKNOWN, project(input).state)
            assertNull(project(input).hz)
        }
    }
    @Test fun oldOrAbsentAnalysisDoesNotInvalidateSeparatelyObservedFreshHostWindow() {
        val old = diagnostics().copy(pingId = 3, observedAtEpochMs = now - 60_000, stale = true)
        val projected = project(old)
        assertEquals(SonarUsableRateState.LIVE, projected.state)
        assertEquals(6.0, projected.hz!!, 0.0)
        assertEquals(now - 60_000, projected.observedAtEpochMs!!)
        assertEquals(now, projected.statusObservedAtEpochMs!!)
        val missing = project(old.copy(pingId = null, observedAtEpochMs = null))
        assertEquals(6.0, missing.hz!!, 0.0)
        assertNull(missing.observedAtEpochMs)
    }
    @Test fun wrongGatewayOrSelectionEpochCannotReusePreviouslyValidHostWindow() {
        for (input in listOf(diagnostics().copy(generation = 3), diagnostics().copy(serverSessionId = "old"),
            change(diagnostics(), "generation", "1"), diagnostics().copy(pingSource = SonarPingSource.NATIVE))) {
            assertEquals(SonarUsableRateState.UNKNOWN, project(input).state)
        }
        assertEquals(SonarUsableRateState.UNKNOWN, project(expected = epoch.copy(selectionGeneration = 3)).state)
        assertEquals(SonarUsableRateState.UNKNOWN, project(selection = SonarPingSource.NATIVE).state)
        // The host usable count always describes OWN pings, even in an ALL display.
        assertEquals(6.0, project(diagnostics(SonarPingSource.ALL), selection = SonarPingSource.ALL).hz!!, 0.0)
    }
    @Test fun malformedWindowAndDuplicateProvenanceFieldsStayUnknown() {
        for (invalid in listOf(change(diagnostics(), "ping_rates.end_device_us", "9000000"),
            change(diagnostics(), "ping_rates.window_s", "1"), change(diagnostics(), "ping_rates.complete", null),
            diagnostics().let { it.copy(fields = it.fields + it.fields.first()) })) {
            assertEquals(SonarUsableRateState.UNKNOWN, project(invalid).state)
        }
    }
}
