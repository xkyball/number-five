package com.no5.helm

import com.no5.domain.*
import com.no5.domain.SourceConfig
import com.no5.domain.SourceMode
import com.no5.domain.WeatherProviderMode
import org.junit.Assert.*
import org.junit.Test

class AndroidSourceSettingsStoreTest {
    @Test fun requestedLanSuggestionKeepsActiveSourceAndOnlyDisplacesOldestAtCapacity() {
        val backend = MemoryBackend()
        val old = AndroidSourceSettingsStore(backend)
        (1..5).forEach { old.save(SourceConfig(SourceMode.PI, "https://pi-$it.local:9443")) }
        val record = backend.record
        val prior = old.endpointHistory()
        val seeded = AndroidSourceSettingsStore(backend, includeKnownLanEndpoint = true)
        val active = seeded.load()
        assertEquals("https://pi-5.local:9443", active.endpoint)
        assertEquals(prior.take(4) + KnownLanEndpointHistory.PI_ADDRESS, seeded.endpointHistory())
        assertEquals("Suggestion does not write or reconnect on load", record, backend.record)
        seeded.save(active.copy(displayUnits = DisplayUnits(depth = DepthUnit.FEET)))
        val reopened = AndroidSourceSettingsStore(backend, includeKnownLanEndpoint = true)
        assertEquals(active.endpoint, reopened.load().endpoint)
        assertEquals(seeded.endpointHistory(), reopened.endpointHistory())
        assertEquals(5, reopened.endpointHistory().size)
    }

    @Test fun requestedLanSuggestionDoesNotActivateLegacyHttpOrRepairCorruptSettings() {
        val backend = MemoryBackend()
        val fresh = AndroidSourceSettingsStore(backend, includeKnownLanEndpoint = true)
        val original = fresh.load()
        assertNotEquals(KnownLanEndpointHistory.PI_ADDRESS, original.endpoint)
        assertEquals("https://lysmarine.local:9443", KnownLanEndpointHistory.PI_ADDRESS)
        assertEquals(listOf(KnownLanEndpointHistory.PI_ADDRESS), fresh.endpointHistory())
        assertEquals(0, backend.writes)
        val damaged = AndroidSourceSettingsStore(MemoryBackend("broken"), includeKnownLanEndpoint = true)
        assertFalse(damaged.load().connectionEnabled)
        assertTrue(damaged.endpointHistory().isEmpty())
        assertNotNull(damaged.warning)
    }

    @Test fun correctedGatewaySuggestionDoesNotReplaceAnAlreadySavedUserSource() {
        val backend = MemoryBackend()
        AndroidSourceSettingsStore(backend).save(SourceConfig(SourceMode.PI, "http://lysmarine.local:8789"))

        val seeded = AndroidSourceSettingsStore(backend, includeKnownLanEndpoint = true)
        assertEquals("http://lysmarine.local:8789", seeded.load().endpoint)
        assertTrue(KnownLanEndpointHistory.PI_ADDRESS in seeded.endpointHistory())
    }
    private class MemoryBackend(var record: String? = null) : SourcePreferencesBackend {
        var failRead = false
        var failWrite = false
        var writes = 0
        override fun read(): String? {
            if (failRead) throw ClassCastException("Unexpected preference type")
            return record
        }
        override fun write(value: String): Boolean {
            if (failWrite) return false
            record = value
            writes++
            return true
        }
    }

    @Test fun emptyStoreStartsAtExternalGatewayWithoutWarningOrPreferenceWrites() {
        val backend = MemoryBackend()
        val store = AndroidSourceSettingsStore(backend)
        assertEquals(SourceConfig(mode = SourceMode.PI, weatherProvider = WeatherProviderMode.OPEN_METEO, allowWeatherLocationSharing = true), store.load())
        assertTrue(store.endpointHistory().isEmpty())
        assertNull(store.warning)
        assertEquals(0, backend.writes)
    }

    @Test fun validPiConfigSurvivesNewStoreInstanceAsOneNormalizedRecord() {
        val backend = MemoryBackend()
        AndroidSourceSettingsStore(backend).save(SourceConfig(SourceMode.PI, "HTTP://PI-TEST.LOCAL:8080"))
        val restored = AndroidSourceSettingsStore(backend)
        assertEquals(SourceConfig(SourceMode.PI, "http://pi-test.local:8080"), restored.load())
        assertNull(restored.warning)
        assertEquals(1, backend.writes)
    }

    @Test fun invalidSavedStatesAlwaysReturnUnavailablePiAndVisibleWarning() {
        val records = listOf(
            "", "PI", "2\nPI\nhttp://pi-test.local:8080", "1\nLIVE\nhttp://pi-test.local:8080",
            "1\nPI\n", "1\nPI\nhttp://user:secret@pi-test.local:8080",
            "1\nPI\nhttp://pi-test.local:8080?token=secret", "1\nPI\nftp://pi-test.local",
            "1\nPI\nhttp://pi-test.local:65536", "1\nPI\nhttp://pi-test.local\nextra",
        )
        records.forEach { encoded ->
            val backend = MemoryBackend(encoded)
            val store = AndroidSourceSettingsStore(backend)
            assertEquals("Unsafe record must not silently activate connections", SourceConfig(mode = SourceMode.PI, connectionEnabled = false), store.load())
            assertNotNull(store.warning)
            assertFalse(store.warning!!.contains("secret"))
            assertEquals("Keep unreadable record until explicit repair", encoded, backend.record)
            assertEquals(0, backend.writes)
        }
    }

    @Test fun unreadablePreferenceTypeIsSafeWithoutLeakingRawDetails() {
        val backend = MemoryBackend().apply { failRead = true }
        val store = AndroidSourceSettingsStore(backend)
        assertEquals(SourceMode.PI, store.load().mode)
        assertNotNull(store.warning)
        assertFalse(store.warning!!.contains("ClassCastException"))
    }

    @Test fun invalidSaveDoesNotReplaceExistingConfiguration() {
        val backend = MemoryBackend()
        val store = AndroidSourceSettingsStore(backend)
        store.save(SourceConfig())
        val oldRecord = backend.record
        try {
            store.save(SourceConfig(SourceMode.PI, "http://user:secret@pi-test.local"))
            fail("Expected validation to reject credentials")
        } catch (_: IllegalArgumentException) { }
        assertEquals(oldRecord, backend.record)
        assertEquals(1, backend.writes)
        assertEquals(SourceMode.PI, store.load().mode)
    }

    @Test fun failedDurableWriteDoesNotReportSuccessOrReplacePreviousSource() {
        val backend = MemoryBackend()
        val store = AndroidSourceSettingsStore(backend)
        store.save(SourceConfig())
        val previous = backend.record
        backend.failWrite = true
        try {
            store.save(SourceConfig(SourceMode.PI, "http://pi-test.local:8080"))
            fail("A failed persistence write must fail apply")
        } catch (_: IllegalStateException) { }
        assertEquals(previous, backend.record)
        assertEquals(SourceMode.PI, AndroidSourceSettingsStore(backend).load().mode)
    }

    @Test fun explicitRepairClearsWarningAndRestoresAfterRecreation() {
        val backend = MemoryBackend("broken")
        val store = AndroidSourceSettingsStore(backend)
        val safe = store.load()
        assertNotNull(store.warning)
        store.save(safe)
        assertNull(store.warning)
        val reopened = AndroidSourceSettingsStore(backend)
        assertEquals(safe, reopened.load())
        assertNull(reopened.warning)
    }

    @Test fun failedWriteRollbackOfWrongPreferenceTypeRemainsUnavailableAfterReload() {
        listOf<Any?>(true, 42, 1.5f, setOf("invalid"), null).forEach { corruptValue ->
            val rolledBack = sourceRecordForRollback(corruptValue, wasPresent = true)
            assertNotNull("An existing invalid value must never become an absent preference", rolledBack)
            val restored = AndroidSourceSettingsStore(MemoryBackend(rolledBack))
            assertEquals(SourceMode.PI, restored.load().mode)
            assertNotNull(restored.warning)
        }
    }

    @Test fun rollbackPreservesValidRecordAndOnlyFreshInstallRemainsAbsent() {
        val backend = MemoryBackend()
        val expected = SourceConfig(SourceMode.PI, "http://pi-test.local:8080")
        AndroidSourceSettingsStore(backend).save(expected)
        val restored = AndroidSourceSettingsStore(MemoryBackend(sourceRecordForRollback(backend.record, wasPresent = true)))
        assertEquals(expected, restored.load())
        assertNull(restored.warning)
        val fresh = AndroidSourceSettingsStore(MemoryBackend(sourceRecordForRollback(null, wasPresent = false)))
        assertEquals(SourceMode.PI, fresh.load().mode)
        assertNull(fresh.warning)
    }
    @Test fun legacyApprovedInstallMigratesWeatherWithoutChangingBoatEndpoint() {
        val store=AndroidSourceSettingsStore(MemoryBackend("1\nPI\nhttp://pi-test.local:8080"))
        val c=store.load()
        assertEquals(SourceMode.PI,c.mode);assertEquals("http://pi-test.local:8080",c.endpoint)
        assertEquals(WeatherProviderMode.OPEN_METEO,c.weatherProvider);assertTrue(c.allowWeatherLocationSharing)
    }
    @Test fun explicitWeatherChoiceAndRevokedSharingSurviveRestart() {
        val backend=MemoryBackend();val expected=SourceConfig(SourceMode.PI,"http://pi-test.local:8080",WeatherProviderMode.OPEN_METEO,false)
        AndroidSourceSettingsStore(backend).save(expected)
        assertEquals(expected,AndroidSourceSettingsStore(backend).load())
        val off=expected.copy(weatherProvider=WeatherProviderMode.DISABLED)
        AndroidSourceSettingsStore(backend).save(off)
        assertEquals(off,AndroidSourceSettingsStore(backend).load())
    }
    @Test fun allDisplayUnitsRoundTripInAtomicV3Record() {
        SpeedUnit.entries.forEach { speed -> ConsumptionUnit.entries.forEach { consumption -> DepthUnit.entries.forEach { depth ->
            val backend = MemoryBackend()
            val expected = SourceConfig(SourceMode.PI, "https://gateway.local:8443", WeatherProviderMode.DISABLED,
                displayUnits = DisplayUnits(speed, consumption, depth))
            AndroidSourceSettingsStore(backend).save(expected)
            assertEquals(expected, AndroidSourceSettingsStore(backend).load())
            assertEquals("4", backend.record!!.lineSequence().first())
            assertEquals(1, backend.writes)
        } } }
    }

    @Test fun legacyDemoSelectionsMigrateToGatewayAndDefaultUnitsWithoutWrites() {
        listOf("1\nDEMO\nhttp://pi-test.local:8080", "2\nDEMO\nhttp://pi-test.local:8080\nDISABLED\nfalse\ntrue").forEach { record ->
            val backend = MemoryBackend(record)
            val restored = AndroidSourceSettingsStore(backend).load()
            assertEquals(SourceMode.PI, restored.mode)
            assertEquals("http://pi-test.local:8080", restored.endpoint)
            assertEquals(DisplayUnits(), restored.displayUnits)
            assertEquals(0, backend.writes)
            if (record.startsWith("2")) {
                assertEquals(WeatherProviderMode.DISABLED, restored.weatherProvider)
                assertFalse(restored.allowWeatherLocationSharing)
            }
        }
    }

    @Test fun savingLegacyDemoRequestCannotPersistAnAppDemoMode() {
        val backend = MemoryBackend()
        AndroidSourceSettingsStore(backend).save(SourceConfig(mode = SourceMode.DEMO))
        assertEquals("PI", backend.record!!.split('\n')[1])
        assertEquals(SourceMode.PI, AndroidSourceSettingsStore(backend).load().mode)
    }

    @Test fun corruptedUnitFieldsDisableConnectionsAndWeatherSharing() {
        val valid = "3\nPI\nhttp://pi-test.local:8080\nOPEN_METEO\ntrue\ntrue\nKNOTS\nLPH\nMETERS"
        listOf(6, 7, 8).forEach { index ->
            val fields = valid.split('\n').toMutableList().apply { this[index] = "UNKNOWN" }
            val store = AndroidSourceSettingsStore(MemoryBackend(fields.joinToString("\n")))
            val config = store.load()
            assertFalse(config.connectionEnabled)
            assertFalse(config.allowWeatherLocationSharing)
            assertEquals(SourceMode.PI, config.mode)
            assertNotNull(store.warning)
        }
    }

    @Test fun failedUnitWriteKeepsPreviouslySavedUnitsAfterRestart() {
        val backend = MemoryBackend()
        val store = AndroidSourceSettingsStore(backend)
        val original = SourceConfig(mode = SourceMode.PI, displayUnits = DisplayUnits(SpeedUnit.KMH, ConsumptionUnit.KM_PER_L, DepthUnit.FEET))
        store.save(original)
        backend.failWrite = true
        assertThrows(IllegalStateException::class.java) { store.save(original.copy(displayUnits = DisplayUnits())) }
        assertEquals(original, AndroidSourceSettingsStore(backend).load())
    }

    @Test fun confirmedEndpointHistoryPersistsOneToFiveAndReplacesTheOldestSixth() {
        val backend = MemoryBackend()
        val store = AndroidSourceSettingsStore(backend)
        (1..6).forEach { index -> store.save(SourceConfig(SourceMode.PI, "HTTP://PI-$index.LOCAL:8080/")) }
        val expected = listOf(
            "http://pi-6.local:8080", "http://pi-5.local:8080", "http://pi-4.local:8080",
            "http://pi-3.local:8080", "http://pi-2.local:8080",
        )
        assertEquals(expected, store.endpointHistory())
        val reopened = AndroidSourceSettingsStore(backend)
        assertEquals("http://pi-6.local:8080", reopened.load().endpoint)
        assertEquals(expected, reopened.endpointHistory())
    }

    @Test fun selectingCanonicalDuplicateMovesItToFrontAndInvalidSaveDoesNotChangeHistory() {
        val backend = MemoryBackend()
        val store = AndroidSourceSettingsStore(backend)
        store.save(SourceConfig(SourceMode.PI, "http://one.local:8080"))
        store.save(SourceConfig(SourceMode.PI, "https://two.local"))
        store.save(SourceConfig(SourceMode.PI, "HTTP://ONE.LOCAL:8080/"))
        assertEquals(listOf("http://one.local:8080", "https://two.local"), store.endpointHistory())
        val before = backend.record
        assertThrows(IllegalArgumentException::class.java) {
            store.save(SourceConfig(SourceMode.PI, "http://user:secret@not-allowed.local"))
        }
        assertEquals(before, backend.record)
        assertEquals(listOf("http://one.local:8080", "https://two.local"), store.endpointHistory())
    }

    @Test fun v3MigrationKeepsConfiguredEndpointAsTheOnlyConfirmedHistoryEntry() {
        val backend = MemoryBackend("3\nPI\nhttps://pi-test.local:9443\nOPEN_METEO\ntrue\ntrue\nKNOTS\nLPH\nMETERS")
        val store = AndroidSourceSettingsStore(backend)
        assertEquals("https://pi-test.local:9443", store.load().endpoint)
        assertEquals(listOf("https://pi-test.local:9443"), store.endpointHistory())
        assertEquals(0, backend.writes)
    }

}
