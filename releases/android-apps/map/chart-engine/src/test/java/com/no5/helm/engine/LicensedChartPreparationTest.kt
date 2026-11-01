package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LicensedChartPreparationTest {
    @Test fun compositePassesExplicitBackgroundRolesAndRejectsForeignOrDuplicatePaths() = runBlocking {
        var calls = 0
        val paths = listOf("/charts/ZA.senc", "/charts/NO5BG_S50_E000.senc")
        val loader = ChartSourceLoader(OChartsPreparationProvider(),
            loadPiCache = { _, _ -> fail("Background role lost"); EngineResult(false) },
            loadComposite = { _, source -> calls++; assertEquals(paths,source.paths); EngineResult(true) },
            loadPublic = { _, _ -> fail("Wrong route"); EngineResult(false) })
        val source = ChartSource.PiCacheFiles(paths,"No5","2026-10-25T00:00:00Z",listOf(paths[1]))
        assertTrue(loader.load("filled",source).success)
        assertFalse(loader.load("bad",source.copy(backgroundPaths=listOf("/foreign.senc"))).success)
        assertFalse(loader.load("bad",source.copy(backgroundPaths=listOf(paths[1],paths[1]))).success)
        assertEquals(1,calls)
    }
    @Test fun piCacheHasItsOwnRouteAndRejectsEncryptedOrPublicInputs() = runBlocking {
        var publicCalls = 0; var piCalls = 0
        val loader = ChartSourceLoader(OChartsPreparationProvider(), { _, paths ->
            piCalls++; assertEquals(listOf("/private/ZA.senc"), paths); EngineResult(true)
        }) { _, _ -> publicCalls++; EngineResult(true) }
        assertTrue(loader.load("za", ChartSource.PiCacheFiles(listOf("/private/ZA.senc"), "No5", "2026-10-11T12:00:00Z")).success)
        for (path in listOf("/private/ZA.000", "/private/ZA.oesu", "", "a\u0000.senc"))
            assertFalse(loader.load("bad", ChartSource.PiCacheFiles(listOf(path), "No5", "date")).success)
        assertEquals(1, piCalls); assertEquals(0, publicCalls)
        assertFalse(loader.load("bad", ChartSource.PublicFiles(listOf("ZA.oesu"))).success)
        assertEquals(0, publicCalls)
    }
    @Test fun publicCellsReachTheExistingLoaderUnchanged() = runBlocking {
        val expected = listOf("/charts/US5BOSCC.000", "/charts/US5BOSCD.000", "/charts/cache.senc", "/charts/cache.S57")
        var calls = 0
        val loader = ChartSourceLoader(OChartsPreparationProvider()) { id, paths ->
            calls++
            assertEquals("boston", id)
            assertEquals(expected, paths)
            EngineResult(true, "Public chart loaded")
        }
        assertTrue(loader.load("boston", ChartSource.PublicFiles(expected)).success)
        assertEquals(1, calls)
    }

    @Test fun protectedSuffixesNeverReachPublicCacheEvenInMixedRequests() = runBlocking {
        var calls = 0
        val loader = ChartSourceLoader(OChartsPreparationProvider()) { _, _ -> calls++; EngineResult(true) }
        for (extension in listOf("oesenc", "oeusenc", "oernc", "OEUSENC")) {
            val result = loader.load("mixed", ChartSource.PublicFiles(listOf("/public/cell.000", "/licensed/cell.$extension")))
            assertFalse(result.success)
            assertTrue(result.message.contains("BLOCKED"))
        }
        assertEquals(0, calls)
    }

    @Test fun invalidPublicRequestsAreRejectedBeforeNativeMutation() = runBlocking {
        var calls = 0
        val loader = ChartSourceLoader(OChartsPreparationProvider()) { _, _ -> calls++; EngineResult(true) }
        for (paths in listOf(emptyList(), listOf(""), listOf("cell.001"), listOf("bundle.zip"), listOf("cell\u0000.000"))) {
            assertFalse(loader.load("invalid", ChartSource.PublicFiles(paths)).success)
        }
        assertEquals(0, calls)
    }

    @Test fun typedLicensedPackageCannotFallBackToPublicLoading() = runBlocking {
        var calls = 0
        val provider = OChartsPreparationProvider()
        val loader = ChartSourceLoader(provider) { _, _ -> calls++; EngineResult(true) }
        // Even a public-looking location retains its explicitly licensed provenance.
        val result = loader.load("licensed", ChartSource.OChartsPackage("/licensed/renamed.000"))
        assertFalse(result.success)
        assertEquals(0, calls)
        for (blocker in LicensedChartBlocker.entries) assertTrue(result.message.contains(blocker.name))
        assertEquals(HelperInstallation.NOT_INSTALLED, provider.status.value.helperInstallation)
    }

    @Test fun blockedRequestDoesNotReplaceAnAlreadyLoadedPublicChart() = runBlocking {
        var activeChart = "none"
        val loader = ChartSourceLoader(OChartsPreparationProvider()) { id, _ -> activeChart = id; EngineResult(true) }
        assertTrue(loader.load("boston", ChartSource.PublicFiles(listOf("boston.000"))).success)
        assertFalse(loader.load("licensed", ChartSource.OChartsPackage("/licensed/package")).success)
        assertEquals("boston", activeChart)
        assertTrue(loader.load("fixture", ChartSource.PublicFiles(listOf("fixture.000"))).success)
        assertEquals("fixture", activeChart)
    }

    @Test fun closeIsIdempotentAndCannotEnableLicensedLoading() = runBlocking {
        val provider = OChartsPreparationProvider()
        provider.close()
        provider.close()
        assertEquals(LicensedProviderLifecycle.CLOSED, provider.status.value.lifecycle)
        assertFalse(provider.open("licensed", ChartSource.OChartsPackage("/package")).success)
        assertTrue(provider.status.value.blockers.contains(LicensedChartBlocker.LICENSED_DEVICE_TEST_BLOCKED))
        // A fresh engine/provider also starts blocked; no approval state is persisted.
        assertEquals(LicensedProviderLifecycle.BLOCKED, OChartsPreparationProvider().status.value.lifecycle)
    }
}
