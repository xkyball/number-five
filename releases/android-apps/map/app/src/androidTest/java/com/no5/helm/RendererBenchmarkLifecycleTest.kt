package com.no5.helm

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.no5.helm.chart.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Compiled here; actual JNI/GLES execution belongs to the separately authorized device run. */
class RendererBenchmarkLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val engine get() = MainActivity::class.java.getDeclaredField("engine").let {
        it.isAccessible = true; it.get(compose.activity) as ChartEngine
    }
    private fun awaitReady() = compose.waitUntil(120_000) {
        val state = engine.status.value
        state.initialized && state.surfaceAttached && state.rendererBenchmarkDataAvailable &&
            !state.presentationPending && !state.rendererBenchmark.active && !state.rendererBenchmarkRecoveryPending
    }
    private fun assertOriginal(original: ChartEngineStatus) {
        val state = engine.status.value
        assertEquals(original.chartLoaded, state.chartLoaded)
        assertEquals(original.loadedChartCells, state.loadedChartCells)
        assertEquals(original.presentation, state.presentation)
        assertEquals(original.viewport.latitude, state.viewport.latitude, 1e-12)
        assertEquals(original.viewport.longitude, state.viewport.longitude, 1e-12)
        assertEquals(original.viewport.metresToPixels, state.viewport.metresToPixels, 1e-12)
        assertEquals(original.viewport.rotationRadians, state.viewport.rotationRadians, 1e-12)
        assertTrue(state.rendererBenchmark.message, state.rendererBenchmark.restored)
    }
    @Test fun bundledStartCancellationAndRepeatRestoreUserMapsOrEmptySelection() {
        awaitReady(); val original = engine.status.value
        repeat(2) {
            val start = runBlocking { engine.startRendererBenchmark(RendererBenchmarkConfig(1)) }
            assertTrue(start.message, start.success)
            compose.waitUntil(120_000) { engine.status.value.rendererCapture.frames >= 6 || !engine.status.value.rendererBenchmark.active }
            assertTrue(engine.status.value.rendererBenchmark.active)
            engine.cancelRendererBenchmark()
            compose.waitUntil(120_000) { !engine.status.value.rendererBenchmark.active }
            assertOriginal(original)
            val report = JSONObject(runBlocking { engine.readRendererReport() }.message)
            assertEquals("cancelled", report.getString("outcome"))
            assertEquals("noaa-boston-repo-2026-10-03/1", report.getJSONObject("metadata").getJSONObject("dataset").getString("bundleId"))
            assertEquals("9c9dcb4cebe049f832dba60ef69ea40b4cebc4515f58d6adfac305bd780795bc",
                report.getJSONObject("metadata").getJSONObject("dataset").getString("contentSha256"))
        }
    }
    @Test fun activityRecreationRecoversJournalBeforeAllowingAnotherBenchmark() {
        awaitReady(); val original = engine.status.value
        assertTrue(runBlocking { engine.startRendererBenchmark(RendererBenchmarkConfig(1)) }.success)
        compose.waitUntil(120_000) { engine.status.value.rendererCapture.frames >= 6 }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(120_000) { engine.status.value.rendererBenchmark.phase == "interrupted" && !engine.status.value.rendererBenchmark.active }
        assertOriginal(original)
        assertFalse(engine.status.value.rendererBenchmarkRecoveryPending)
    }
    @Test fun standardCancellationAndRepeatRestoreOriginalSelectionAndCamera() {
        awaitReady(); val original = engine.status.value
        val calls = CoroutineScope(Dispatchers.Default)
        try {
            repeat(2) {
                val call = calls.async { engine.profileRenderer() }
                compose.waitUntil(120_000) { engine.status.value.rendererBenchmark.active }
                assertEquals(2, engine.status.value.rendererBenchmark.totalGroups)
                engine.cancelRendererBenchmark()
                val result = runBlocking { call.await() }
                assertTrue(result.message, result.success)
                assertOriginal(original)
                val report = JSONObject(result.message)
                assertEquals("no5-renderer-ab/1", report.getString("schema"))
                assertEquals("cancelled", report.getString("outcome"))
                assertEquals(70, report.getJSONObject("recipe").getInt("totalSubmittedFrames"))
                assertEquals(result.message, runBlocking { engine.readRendererReport() }.message)
            }
        } finally { calls.cancel() }
    }
    @Test fun standardRecreationRecoversSameJournalAndDoesNotChangeUserStyle() {
        awaitReady(); val original = engine.status.value
        val calls = CoroutineScope(Dispatchers.Default)
        try {
            val measuredEngine = engine
            val call = calls.async { measuredEngine.profileRenderer() }
            compose.waitUntil(120_000) { measuredEngine.status.value.rendererBenchmark.active && measuredEngine.status.value.rendererCapture.frames >= 6 }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(120_000) { engine.status.value.rendererBenchmark.phase == "interrupted" && !engine.status.value.rendererBenchmark.active }
            runBlocking { call.await() }
            assertOriginal(original)
            assertFalse(engine.status.value.rendererBenchmarkRecoveryPending)
        } finally { calls.cancel() }
    }
}
