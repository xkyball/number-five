package com.no5.helm.ui

import com.no5.helm.chart.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RendererCurrentViewMeasurementTest {
    @Test fun currentLiveFullParityKeepsTheExactSnapshotAndOnlyAddsSeparateReadOnlyEvidence() {
        val id = "00000000-0000-4000-8000-000000000001"
        val captured = snapshot().put("snapshotId", id)
        for (on in listOf(false, true)) {
            val config = currentResidentLiveFullParityConfig(captured.toString(), state, on, 1_000, "parity-token")
            assertEquals(id, config.currentResidentLive!!.snapshotId)
            assertTrue(config.currentLiveParityOnly); assertFalse(config.liveReplay)
            assertEquals(15_000L, config.liveDurationMs); assertEquals(61_000L, config.deadlineElapsedRealtimeMs)
            assertEquals(on, config.normalRenderAbort); assertEquals("parity-token", config.clientRunToken)
            assertNull(config.capacityArm); assertNull(config.capacityRecipeId); assertNull(config.viewportReuseCandidate)
            assertFalse(config.viewportReuseParity); assertFalse(config.viewportReuseDetailed)
        }
        assertThrows(IllegalArgumentException::class.java) {
            currentResidentLiveFullParityConfig(captured.toString(), state.copy(chartSourceGeneration = 8), true, 1_000)
        }
        assertFalse(currentResidentLiveInputConfig(captured.toString(), state, true, 1_000).currentLiveParityOnly)
    }
    @Test fun explicitCurrentLiveUsesSameSnapshotAdmissionWithoutBostonOrReuseVariants() {
        val id = "00000000-0000-4000-8000-000000000001"
        val captured = snapshot().put("snapshotId", id)
        for (candidate in listOf(false, true)) {
            val config = currentResidentLiveInputConfig(captured.toString(), state, candidate, 1_000)
            assertEquals(id, config.currentResidentLive!!.snapshotId)
            assertEquals(RendererRunKind.LIVE_INPUT, config.runKind); assertEquals(ChartBufferTransformMode.CONTROL, config.liveMode)
            assertEquals(candidate, config.normalRenderAbort); assertNull(config.capacityArm); assertNull(config.viewportReuseCandidate)
        }
        assertThrows(IllegalArgumentException::class.java) {
            currentResidentLiveInputConfig(captured.toString(), state.copy(chartSourceGeneration = 8), true, 1_000)
        }
        assertThrows(IllegalArgumentException::class.java) { currentResidentLiveInputConfig(captured.toString(), state, true, 1_000, 30_001) }
    }
    private val state = ChartEngineStatus(initialized = true, chartLoaded = true, surfaceAttached = true,
        residentCatalogActive = true, residentCatalogGeneration = "a".repeat(64), chartSourceGeneration = 7,
        surfaceGeneration = 9, rendererBenchmarkDataAvailable = false)
    private fun snapshot() = JSONObject().put("schema", "no5-capacity-outer-state/1").put("scope", "CURRENT_ENTRY_CATALOG_ONLY")
        .put("state", JSONObject().put("bufferTransformMode", "OFF").put("residentSourceGeneration", 7)
            .put("residentActivation", JSONObject().put("catalog", JSONObject().put("generation", "a".repeat(64))
                .put("budget", JSONObject().put("cells", 25).put("bytes", 268_435_456L).put("loadingBytes", 134_217_728L)))))
        .put("authority", JSONObject().put("catalogGeneration", "a".repeat(64)).put("sourceGeneration", 7).put("surfaceGeneration", 9)
            .put("budget", JSONObject().put("cells", 25).put("bytes", 268_435_456L).put("loadingBytes", 134_217_728L)))

    @Test fun allFiveReleaseArmsUseTheExactCurrentOwnerBudgetWithoutBostonData() {
        for (variant in RendererViewportReuseVariant.entries) for (detailed in listOf(false, true)) {
            val config = currentViewReuseConfig(snapshot().toString(), state, variant, detailed, 1_000, "ui-$variant-$detailed")
            assertEquals("CURRENT_VIEW_REUSE", config.capacityRecipeId)
            assertEquals(RendererRunKind.RESIDENT_CAPACITY, config.runKind)
            assertEquals(ResidentCapacityCacheMode.WARM, config.capacityArm!!.cacheMode)
            assertEquals(ChartResidentBudget(25, 268_435_456L, 134_217_728L), config.capacityArm!!.budget)
            assertEquals(7L, config.capacityArm!!.expectedSourceGeneration)
            assertEquals(15_000L, config.capacityArm!!.durationMs)
            assertEquals(61_000L, config.deadlineElapsedRealtimeMs)
            assertEquals(variant, config.viewportReuseCandidate); assertEquals(detailed, config.viewportReuseDetailed)
            assertFalse(config.viewportReuseParity)
        }
        assertFalse(state.rendererBenchmarkDataAvailable) // Not a Boston prerequisite.
    }
    @Test fun separateParityConfigKeepsTheExistingRecipeBudgetAndTimingSignatureButOnlyPermitsOffAll() {
        for (variant in listOf(RendererViewportReuseVariant.OFF, RendererViewportReuseVariant.ALL)) {
            val config = currentViewReuseParityConfig(snapshot().toString(), state, variant, 1_000, "parity-$variant")
            assertTrue(config.viewportReuseParity); assertFalse(config.viewportReuseDetailed)
            assertEquals(15_000L, config.capacityArm!!.durationMs)
            assertEquals("CURRENT_VIEW_REUSE", config.capacityRecipeId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            currentViewReuseParityConfig(snapshot().toString(), state, RendererViewportReuseVariant.GPU, 1_000)
        }
    }
    @Test fun changedSourceSurfaceBudgetOrFastModeRefusesTheMeasurement() {
        for (changed in listOf(state.copy(chartSourceGeneration = 8), state.copy(surfaceGeneration = 10),
            state.copy(residentCatalogGeneration = "b".repeat(64)),
            state.copy(bufferTransformMode = ChartBufferTransformMode.CONTROL))) {
            assertThrows(IllegalArgumentException::class.java) {
                currentViewReuseConfig(snapshot().toString(), changed, RendererViewportReuseVariant.ALL, false, 1_000)
            }
        }
        val changedBudget = snapshot().apply { getJSONObject("authority").getJSONObject("budget").put("cells", 26) }
        assertThrows(IllegalArgumentException::class.java) {
            currentViewReuseConfig(changedBudget.toString(), state, RendererViewportReuseVariant.OFF, false, 1_000)
        }
    }
    @Test fun unconfirmedViewAndRecoveryHaveNoImplicitFallbackOrModeChange() {
        val confirmed = state.copy(confirmedFrame = ChartFrame(1, "test", ChartSurfaceGeometry.fit(2000, 1200), state.viewport))
        assertNull(currentViewReuseStartBlocker(confirmed, false))
        assertNotNull(currentViewReuseStartBlocker(state, false)) // No confirmed producer frame.
        assertNotNull(currentViewReuseStartBlocker(state.copy(rendererBenchmarkRecoveryPending = true), false))
        assertNotNull(currentViewReuseStartBlocker(state, true))
        assertEquals(ChartBufferTransformMode.OFF, state.bufferTransformMode)
    }
    @Test fun privateCurrentChartExportNamesBindVariantModeAndRun() {
        val id = "5e7f146e-7664-4dc3-8c32-8d0e12ed344e"
        val report = JSONObject().put("schema", "no5-resident-capacity/1").put("metadata", JSONObject()
            .put("inputRecipe", "CURRENT_VIEW_REUSE").put("runId", id).put("viewportReuse", "SOUNDINGS").put("detailed", true)).toString()
        val export = rendererReportExport(report)
        assertEquals("current-map-soundings-detail-$id.json", export.fileName)
        assertEquals(report, export.json)
        val parity = JSONObject(report).apply { getJSONObject("metadata").put("viewportReuse", "ALL").put("parity", true).put("detailed", false) }.toString()
        assertEquals("current-map-all-parity-$id.json", rendererReportExport(parity).fileName)
    }
}
