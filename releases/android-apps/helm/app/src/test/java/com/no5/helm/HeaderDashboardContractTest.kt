package com.no5.helm

import com.no5.domain.HelmState
import com.no5.domain.TelemetryQuality
import com.no5.domain.TelemetryStatus
import com.no5.domain.TelemetryValue
import com.no5.header.*
import com.no5.header.consumer.HeaderConsumerSnapshotAdapter
import org.junit.Assert.*
import org.junit.Test
import org.lumina.header.consumer.v1.HeaderConsumerContract as Wire

class HeaderDashboardContractTest {
    @Test fun dashboardAppendsWithoutShiftingAnyExistingPageCode() {
        val previous = listOf("HOME", "APPS", "HELM", "AUDIO", "SONAR", "SETTINGS", "CHART", "ROUTES", "AIS", "LAYERS")
        assertEquals(previous, HeaderPage.entries.take(previous.size).map { it.name })
        assertEquals(previous, Wire.Page.values().take(previous.size).map { it.name })
        assertEquals(10, HeaderPage.DASHBOARD.ordinal)
        assertEquals(10, Wire.Page.DASHBOARD.ordinal)
        assertEquals(HeaderProfile.MARINE_SAFETY, HeaderApps.profile(HeaderApps.HELM, HeaderPage.DASHBOARD))
        assertNull(HeaderApps.profile(HeaderApps.HOME, HeaderPage.DASHBOARD))
        assertNull(HeaderApps.profile(HeaderApps.MAP, HeaderPage.DASHBOARD))
        assertEquals(Wire.Profile.MARINE_SAFETY, Wire.expectedProfile(HeaderApps.HELM, Wire.Page.DASHBOARD))
        assertNull(Wire.expectedProfile(HeaderApps.MAP, Wire.Page.DASHBOARD))
    }

    @Test fun dashboardReadOnlyConsumerKeepsActualSafetyMeasurementsAndTheirOriginalTimes() {
        val raw = HelmState(quality = TelemetryQuality(TelemetryStatus.LIVE, 20_000, "fixture-source", demo = false),
            telemetryFields = mapOf(
                "portRpm" to TelemetryValue(0.0, TelemetryQuality(TelemetryStatus.LIVE, 19_000, "fixture-port", demo = false)),
                "speed" to TelemetryValue(3.0, TelemetryQuality(TelemetryStatus.LIVE, 18_000, "fixture-gps", demo = false))),
            sourceSessionId = "fixture-backend")
        val source = HelmHeaderSnapshotBuilder.build(HeaderPublication(HeaderApps.HELM, "fixture-process", 1,
            2, "fixture-session", 1, 1000, 2000), HeaderPage.DASHBOARD, raw, 20_000, HeaderTheme.DAY)
        val exported = HeaderConsumerSnapshotAdapter.original(source)
        assertEquals(Wire.Page.DASHBOARD, exported.pageId)
        assertEquals(Wire.Profile.MARINE_SAFETY, exported.profile)
        assertEquals(0.0, exported.extras.portRpm.value!!, 0.0)
        assertEquals(19_000L, exported.extras.portRpm.sourceObservedAtEpochMs)
        assertEquals(3.0, exported.extras.sog.value!!, 0.0)
        assertEquals(18_000L, exported.extras.sog.sourceObservedAtEpochMs)
        assertEquals(20_000L, exported.sourceObservedAtEpochMs)
        assertEquals("DASHBOARD", exported.context.title)
    }
}
