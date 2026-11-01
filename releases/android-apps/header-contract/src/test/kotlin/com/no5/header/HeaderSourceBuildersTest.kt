package com.no5.header

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test

/** Source parity and quality fixtures, not backend readings or screenshots. */
class HeaderSourceBuildersTest {
    private fun publication(packageName: String = HeaderApps.HELM, sequence: Long = 0) =
        HeaderPublication(packageName, "fixture-process", 1, 1, "fixture-client-session", sequence, 1_000, 2_500)
    private fun observation(value: Double?, stamp: Long? = 20_000, simulated: Boolean = false) =
        TelemetryValue(value, TelemetryQuality(TelemetryStatus.LIVE, stamp, "fixture-sensor", demo = false, simulated = simulated))
    private fun raw(vararg fields: Pair<String, TelemetryValue>) = HelmState(
        quality = TelemetryQuality(TelemetryStatus.LIVE, 20_000, "fixture-source", demo = false),
        telemetryFields = fields.toMap(), sourceSessionId = "fixture-server-session",
    )
    private fun build(raw: HelmState, page: HeaderPage = HeaderPage.AUDIO, now: Long = 20_000,
                      units: DisplayUnits = DisplayUnits()) = HelmHeaderSnapshotBuilder.build(
        publication(), page, raw, now, HeaderTheme.DAY, units = units, pageStatus = "fixture-audio-status")

    @Test fun homePreservesLiteralContextAndNoInventedSourceValues() {
        val home = HomeHeaderSnapshotBuilder.build(publication(HeaderApps.HOME), HeaderTheme.RED_NIGHT)
        assertEquals("NO5 · HOME", home.context.title)
        assertEquals("STELLENBOSCH · AN BORD", home.context.subtitle)
        assertEquals(HeaderProfile.HOME_CONTEXT, home.profile)
        assertEquals(HeaderQuality.UNKNOWN, home.source.quality)
        assertTrue(home.extras.isEmpty()); assertNull(home.sourceObservedAtEpochMs)
    }

    @Test fun homeGridHasDistinctPageWithoutMovingHeaderContext() {
        val home = HomeHeaderSnapshotBuilder.build(publication(HeaderApps.HOME), HeaderTheme.DAY, appsVisible = true)
        assertEquals(HeaderPage.APPS, home.pageId)
        assertEquals("NO5 · HOME", home.context.title)
    }

    @Test fun mapReadyTransportDoesNotInventLiveQualityRealProvenanceOrTime() {
        val map = MapHeaderSnapshotBuilder.build(publication(HeaderApps.MAP), HeaderPage.CHART, HeaderTheme.DAY,
            MapHeaderSourceInput("fixture-server", "Helm-Transport live · Messalter, Fixqualität und Simulationsherkunft unbekannt", HeaderTransport.READY))
        assertEquals("fixture-server", map.source.label)
        assertEquals(HeaderTransport.READY, map.source.transport)
        assertEquals(HeaderQuality.UNKNOWN, map.source.quality)
        assertEquals(HeaderProvenance.UNKNOWN, map.source.provenance)
        assertNull(map.sourceObservedAtEpochMs); assertTrue(map.extras.isEmpty())
        assertEquals("EXPLORE FURTHER", map.context.title); assertEquals("BY WATER", map.context.subtitle)
    }

    @Test fun mapNoSnapshotAndExplicitLocalSimulationLabelsMatchCurrentSource() {
        fun map(simulated: Boolean) = MapHeaderSnapshotBuilder.build(publication(HeaderApps.MAP), HeaderPage.SETTINGS, HeaderTheme.RED_NIGHT,
            MapHeaderSourceInput(null, "fixture-unavailable", HeaderTransport.OFFLINE, localSimulation = simulated))
        assertEquals("Kein Helm-Snapshot", map(false).source.label)
        assertEquals("LOKALE SIMULATION", map(true).source.label)
        assertEquals(HeaderProvenance.SIMULATED, map(true).source.provenance)
        assertEquals(HeaderQuality.UNKNOWN, map(true).source.quality)
    }

    @Test fun mainHelmUsesBrandSourceButAudioSonarSettingsAndDashboardKeepTheirSafetyExtras() {
        val r = raw("portRpm" to observation(0.0), "starboardRpm" to observation(2_000.0), "speed" to observation(3.0))
        val main = build(r, HeaderPage.HELM)
        assertEquals(HeaderProfile.BRAND_SOURCE, main.profile); assertTrue(main.extras.isEmpty())
        assertEquals("EXPLORE FURTHER", main.context.title)
        for (page in listOf(HeaderPage.AUDIO, HeaderPage.SONAR, HeaderPage.SETTINGS, HeaderPage.DASHBOARD)) {
            val s = build(r, page)
            assertEquals(HeaderProfile.MARINE_SAFETY, s.profile); assertEquals(page.name, s.context.title)
            assertEquals(0.0, s.extras.portRpm!!.value!!, 0.0)
            assertEquals(2_000.0, s.extras.starboardRpm!!.value!!, 0.0)
            assertEquals(3.0, s.extras.sog!!.value!!, 0.0)
        }
    }

    @Test fun clientAndBackendSessionsStayDistinct() {
        val s = build(raw("speed" to observation(0.0)))
        assertEquals("fixture-client-session", s.publication.sourceSessionId)
        assertEquals("fixture-server-session", s.source.backendSessionId)
    }

    @Test fun eachFieldUsesOriginalMeasurementClockAndQuality() {
        val s = build(raw("portRpm" to observation(2_000.0, 14_000), "speed" to observation(0.0)))
        assertEquals(HeaderQuality.STALE, s.extras.portRpm!!.quality)
        assertEquals(14_000L, s.extras.portRpm!!.sourceObservedAtEpochMs)
        assertEquals(HeaderQuality.LIVE, s.extras.sog!!.quality)
        assertEquals(0.0, s.extras.sog!!.value!!, 0.0)
        assertEquals("* STALE FIELD", s.source.label)
    }

    @Test fun freshEnvelopeDoesNotRefreshExpiredSimulatedField() {
        val s = build(raw("speed" to observation(10.0, 1_000, simulated = true)))
        assertEquals(10.0, s.extras.sog!!.value!!, 0.0)
        assertEquals(HeaderQuality.STALE, s.extras.sog!!.quality)
        // 19000ms old is STALE; another fresh envelope at40000ms cannot make it LIVE.
        val later = build(raw("speed" to observation(10.0, 1_000, simulated = true)), now = 40_000)
        assertNull(later.extras.sog!!.value); assertEquals(HeaderQuality.LOST, later.extras.sog!!.quality)
        assertEquals(HeaderProvenance.SIMULATED, later.extras.sog!!.provenance)
    }

    @Test fun absentNullNonfiniteAndMissingTimestampNeverFallBackToDefaultScalars() {
        val s = build(raw("portRpm" to observation(null), "starboardRpm" to observation(Double.NaN), "speed" to observation(12.0, null)))
        assertNull(s.extras.portRpm!!.value); assertNull(s.extras.starboardRpm!!.value); assertNull(s.extras.sog!!.value)
        val defaults = build(HelmState())
        assertNull(defaults.extras.portRpm!!.value); assertNull(defaults.extras.starboardRpm!!.value); assertNull(defaults.extras.sog!!.value)
        assertEquals(HeaderQuality.UNKNOWN, defaults.extras.portRpm!!.quality)
    }

    @Test fun speedUnitChangesValuesButNotSourceObservation() {
        val s = build(raw("speed" to observation(10.0)), units = DisplayUnits(speed = SpeedUnit.KMH))
        assertEquals(18.52, s.extras.sog!!.value!!, 1e-10)
        assertEquals(HeaderUnit.KMH, s.extras.sog!!.unit)
        assertEquals(20_000L, s.extras.sog!!.sourceObservedAtEpochMs)
    }

    @Test fun missingFieldSourceAndNonfiniteValueCannotProduceVisibleMeasurement() {
        val missing = HeaderMeasurement(0.0, HeaderUnit.RPM, null, 20_000, HeaderQuality.LIVE).at(20_000)
        assertNull(missing.value); assertEquals(HeaderQuality.UNKNOWN, missing.quality)
        val nan = missing.copy(value = Double.NaN).at(20_000)
        assertNull(nan.value); assertEquals(HeaderQuality.INVALID, nan.quality)
        val nil = missing.copy(value = null, sourceId = "fixture-sensor").at(20_000)
        assertNull(nil.value); assertEquals(HeaderQuality.UNKNOWN, nil.quality)
    }

    @Test fun futureMeasurementAndFreshnessThresholdsFollowExistingDomain() {
        val m = HeaderMeasurement(0.0, HeaderUnit.RPM, "fixture-sensor", 20_000, HeaderQuality.LIVE)
        assertEquals(HeaderQuality.LIVE, m.at(25_000).quality)
        assertEquals(HeaderQuality.STALE, m.at(25_001).quality)
        assertEquals(HeaderQuality.STALE, m.at(50_000).quality)
        assertEquals(HeaderQuality.LOST, m.at(50_001).quality)
        assertEquals(HeaderQuality.STALE, m.at(14_999).quality)
        assertEquals(HeaderQuality.STALE, m.copy(quality = HeaderQuality.STALE).at(20_000).quality)
    }

    @Test fun inputMapMutationCannotChangeBuiltSnapshot() {
        val fields = mutableMapOf("speed" to observation(0.0))
        val input = raw().copy(telemetryFields = fields)
        val snapshot = build(input)
        fields["speed"] = observation(99.0)
        assertEquals(0.0, snapshot.extras.sog!!.value!!, 0.0)
        assertEquals(20_000L, snapshot.extras.sog!!.sourceObservedAtEpochMs)
    }
}
