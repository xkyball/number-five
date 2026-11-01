package com.no5.helm

import com.no5.domain.*
import com.no5.gateway.*
import org.junit.Test
import kotlin.test.*

class HomeSourceSettingsStoreTest {
    private class Local : SourceSettingsStore {
        var saved = SourceConfig(mode = SourceMode.PI, endpoint = "https://old.local:9443", weatherProvider = WeatherProviderMode.DISABLED)
        override fun load() = saved
        override fun save(config: SourceConfig) { saved = config }
    }
    private val record = GatewayConfiguration(GatewayOrigin.parse("https://no5-marineos-lab.local:9443")!!,
        "192.168.68.88", GatewayAddressSource.MANUAL, checkedAt = 1)
    @Test fun oneHomeConfigurationOverridesOldAppAddressAndPreservesWeatherAndUnits() {
        val local = Local(); val store = HomeSourceSettingsStore(local, { HomeGatewayStatus(true, record, 1, true, true) })
        val config = store.load()
        assertEquals(record.origin.url, config.endpoint); assertTrue(config.connectionEnabled)
        assertEquals(WeatherProviderMode.DISABLED, config.weatherProvider)
        store.save(config.copy(displayUnits = DisplayUnits(depth = DepthUnit.FEET)))
        assertEquals("https://old.local:9443", local.saved.endpoint)
        assertEquals(DepthUnit.FEET, store.load().displayUnits.depth)
    }
    @Test fun healthTimestampsDoNotReplaceAnActiveSessionButRoutingAndMaterialDo() {
        val status = HomeGatewayStatus(true, record, 1, true, true)
        assertEquals(status.identity, status.copy(configuration = record.copy(checkedAt = 500)).identity)
        assertNotEquals(status.identity, status.copy(securityRevision = 2).identity)
        assertNotEquals(status.identity, status.copy(configuration = record.copy(address = "192.168.68.89")).identity)
        assertNotEquals(status.identity, status.copy(configuration = record.copy(state = GatewayAddressState.NEEDS_INPUT)).identity)
        assertNotEquals(status.identity, status.copy(keyPresent = false).identity)
    }
    @Test fun absentCorruptOrIncompleteHomeCannotUseOldAppEndpoint() {
        val local = Local()
        listOf(HomeGatewayStatus(false, null), HomeGatewayStatus(true, null), HomeGatewayStatus(true, record),
            HomeGatewayStatus(true, record.copy(state = GatewayAddressState.NEEDS_INPUT), 1, true, true)).forEach { status ->
            assertFalse(HomeSourceSettingsStore(local, { status }).load().connectionEnabled)
        }
        assertFalse(HomeSourceSettingsStore(local, { throw HomeGatewayUnavailable() }).load().connectionEnabled)
        assertFalse(HomeSourceSettingsStore(local, { HomeGatewayStatus(true, record, 1, true, true) }, localWarning = { "corrupt" }).load().connectionEnabled)
    }
    @Test fun appCannotChangeTheCentralGatewayOrPersistAnAddressFailureAsPermanentDisable() {
        val local = Local(); var status = HomeGatewayStatus(true, record, 1, true, true)
        val store = HomeSourceSettingsStore(local, { status })
        assertFailsWith<IllegalArgumentException> { store.save(store.load().copy(endpoint = "https://different.local:9443")) }
        status = status.copy(configuration = record.copy(state = GatewayAddressState.NEEDS_INPUT))
        store.save(store.load()); assertTrue(local.saved.connectionEnabled)
        status = status.copy(configuration = record)
        assertTrue(store.load().connectionEnabled)
    }
    @Test fun localCorruptionWarningSurvivesUnavailableHomeAndOnlyExplicitSaveRepairsIt() {
        val local = Local(); var corrupt = true; var unavailable = true
        val store = HomeSourceSettingsStore(local, {
            if (unavailable) throw HomeGatewayUnavailable()
            HomeGatewayStatus(true, record, 1, true, true)
        }, localWarning = { if (corrupt) "synthetic damaged local record" else null })
        val original = local.saved
        assertFalse(store.load().connectionEnabled); assertTrue(store.localSettingsBlocked)
        assertEquals("synthetic damaged local record", store.warning)
        unavailable = false
        val validHome = store.load()
        assertEquals(record.origin.url, validHome.endpoint)
        assertFalse(validHome.connectionEnabled); assertTrue(store.localSettingsBlocked)
        assertEquals(original, local.saved)
        // This is the existing deliberate Apply persistence path, never the automatic observer.
        corrupt = false; store.save(validHome)
        assertTrue(store.load().connectionEnabled); assertFalse(store.localSettingsBlocked)
    }
}
