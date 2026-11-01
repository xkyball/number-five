package com.no5.helm

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.chart.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * Separate, explicit OFF or ON arm of the existing LIVE_INPUT/CONTROL owner, not reuse timing.
 * Its current implementation is a pinned Boston replay and REFUSES an active resident catalog.
 * This test never clears/switches that catalog, sets T0_MODE, invents a current-map workload,
 * injects raw camera/JNI calls or captures Window pixels. An unavailable run is not acceptance.
 */
class RendererReleaseNormalAbortTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun explicitNormalAbortReplayOnExistingSupportedOwner() {
        assumeTrue("Explicit independent normal-abort opt-in required",
            InstrumentationRegistry.getArguments().getString("no5.renderer.normalAbort") == "true")
        val proof = RendererReleaseAcceptanceSupport()
        val enabled = requireNotNull(proof.arguments.getString("no5.renderer.normalAbort.enabled")).let {
            require(it == "true" || it == "false"); it == "true"
        }
        val duration = requireNotNull(proof.arguments.getString("no5.renderer.normalAbort.durationMs")).toLong()
        require(duration in 5_000..30_000)
        val replay = RendererReplayRecipe.valueOf(requireNotNull(proof.arguments.getString("no5.renderer.normalAbort.recipe")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val replayText = context.assets.open(RendererTouchReplay.asset(replay)).bufferedReader().use { it.readText() }
        require(replayText.length <= 128 * 1024)
        val steps = RendererTouchReplay.parse(replayText, replay) // Existing exact SHA and bounds, not a second replay implementation.
        require(steps.last().offsetMs <= duration) { "The pinned replay must finish within the requested live window" }
        val result = JSONObject().put("schema", "no5.renderer-release-normal-abort-acceptance/1")
            .put("run", proof.run).put("status", "FAIL").put("enabledRequested", enabled)
            .put("requestedLiveDurationMs", duration).put("replayRecipe", replay.name)
            .put("replaySha256", RendererTouchReplay.sha256(replay))
            .put("inputOrigin", "VIEW_INJECTED; not a physical knob/finger proof")
            .put("windowPixelCopy", "NOT_RUN").put("physicalDisplay", "NOT_MEASURED").put("gpuTimeOrFps", "NOT_MEASURED")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var current: ChartEngine? = null
                scenario.onActivity { current = proof.engine(it) }
                val engine = requireNotNull(current)
                proof.ready(engine); proof.uncovered(compose)
                val original = engine.status.value
                if (original.residentCatalogActive) {
                    result.put("status", "UNAVAILABLE").put("reason", "UNAVAILABLE_RESIDENT_CATALOG")
                        .put("ownerStarted", false).put("catalogSwitch", false)
                        .put("limitation", "Existing LIVE_INPUT owner cannot restore resident catalogs; product guard unchanged")
                    assumeTrue("Resident catalog remains intact; normal-abort requires a later owner/recovery implementation", false)
                }
                if (original.bufferTransformMode != ChartBufferTransformMode.OFF || !original.rendererBenchmarkDataAvailable) {
                    result.put("status", "UNAVAILABLE").put("reason", "EXISTING_NONRESIDENT_OFF_AND_PINNED_DATA_REQUIRED")
                        .put("ownerStarted", false).put("catalogSwitch", false)
                    assumeTrue("No mode override or substitute source is permitted from this test", false)
                }
                val config = RendererBenchmarkConfig(repetitions = 1, clientRunToken = UUID.randomUUID().toString(),
                    deadlineElapsedRealtimeMs = SystemClock.elapsedRealtime() + 60_000, recoveryBudgetMs = 30_000,
                    requestedWorkers = 1, runKind = RendererRunKind.LIVE_INPUT, liveMode = ChartBufferTransformMode.CONTROL,
                    liveDurationMs = duration, liveReplay = true, liveReplayRecipe = replay, liveRepaintTransforms = true,
                    normalRenderAbort = enabled)
                val report = proof.execute(engine, config, if (enabled) "NORMAL-ON" else "NORMAL-OFF")
                proof.original(original, engine.status.value, exactSource = false)
                assertEquals("no5-renderer-live-input/1", report.getString("schema"))
                assertEquals("LIVE_INPUT", report.getString("runKind"))
                val metadata = report.getJSONObject("metadata")
                assertEquals("CONTROL", metadata.getString("mode")); assertEquals(enabled, metadata.getBoolean("normalRenderAbort"))
                assertEquals(duration, metadata.getLong("liveDurationMs")); assertTrue(metadata.getBoolean("replayEnabled"))
                assertEquals(replay.name, metadata.getString("replayRecipe"))
                assertEquals(RendererTouchReplay.sha256(replay), metadata.getString("replaySha256"))
                assertEquals("noaa-boston-repo-2026-10-03/1", metadata.getJSONObject("dataset").getString("bundleId"))
                assertEquals("9c9dcb4cebe049f832dba60ef69ea40b4cebc4515f58d6adfac305bd780795bc",
                    metadata.getJSONObject("dataset").getString("contentSha256"))
                val observations = report.getJSONObject("observations")
                val input = observations.getJSONObject("input")
                assertEquals(report.getString("runId"), input.getString("runId"))
                assertEquals("COMPLETED", input.getString("replayState"))
                assertEquals(RendererTouchReplay.sha256(replay), input.getString("replaySha256"))
                assertTrue(input.getBoolean("storageCoverageComplete")); assertEquals(0L, input.getLong("dropped"))
                assertTrue(input.getLong("inputSamples") > 0)
                val rows = input.getJSONArray("rows"); require(rows.length() <= 8192)
                val kinds = mutableMapOf<String, Int>()
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONArray(index)
                    val kind = row.getString(0); kinds[kind] = (kinds[kind] ?: 0) + 1
                }
                // Preserve intrinsic input/revision/tag columns in the original report. Counts do not
                // relabel a pinned old FULL as the latest camera, a consumer ACK or a displayed frame.
                for (kind in listOf("INPUT", "CAMERA_ACCEPTED", "CAMERA_APPLIED", "BUFFER_COMPLETED", "NATIVE_FRAME"))
                    assertTrue("Actual production path missing $kind", (kinds[kind] ?: 0) > 0)
                result.put("productionRowsByKind", JSONObject(kinds))
                    .put("datasetScope", "EXISTING_OWNER_PINNED_BOSTON_ONLY; current resident maps not exercised")
                    .put("originalSelectionAndSettingsRestored", true).put("reuseVariant", "UNCHANGED_NOT_CONFIGURED")
                    .put("consumerAdoption", "NOT_DETERMINED; original timestamp/slot records retained without latest-revision substitution")
                if (enabled) {
                    val native = observations.getJSONObject("normalRenderAbort")
                    val restore = report.getJSONObject("normalRenderAbortRestoration")
                    for (part in listOf(native, restore)) validateNormal(part, report.getString("runId"))
                    assertTrue(native.getBoolean("enabled")); assertTrue(native.getLong("completedCount") > 0)
                    assertFalse(restore.getBoolean("active")); assertFalse(restore.getBoolean("originalEnabled"))
                    assertEquals(restore.getBoolean("originalEnabled"), restore.getBoolean("enabled"))
                    result.put("nativeDefaultRestoration", "CONFIRMED_INACTIVE_ORIGINAL_FALSE")
                        .put("abortedCount", native.getLong("abortedCount"))
                        .put("pinnedCompletedCount", native.getLong("pinnedCompletedCount"))
                        .put("supersessionActuallyExercised", native.getLong("abortedCount") > 0)
                } else {
                    val initial = metadata.getJSONObject("normalRenderAbortDiagnostic")
                    assertFalse(initial.getBoolean("configured")); assertFalse(initial.getBoolean("defaultEnabled"))
                    assertFalse(report.getJSONObject("normalRenderAbortRestoration").getBoolean("configured"))
                    result.put("nativeDefaultRestoration", "NO_FLAG_MUTATION; native readback unavailable for unconfigured OFF arm")
                        .put("supersessionActuallyExercised", false)
                }
                result.put("status", "PASS").put("independentDiagnosticCompleted", true)
                    .put("performanceBenefit", "NOT_ESTABLISHED").put("currentResidentMapAcceptance", "NOT_RUN")
            }
        } catch (failure: Throwable) {
            result.put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally { proof.write("result.json", result.toString(2), 32 * 1024) }
    }

    private fun validateNormal(value: JSONObject, owner: String) {
        assertEquals("no5.normal-render-abort/1", value.getString("schema")); assertEquals(owner, value.getString("runId"))
        assertTrue(value.getBoolean("available")); assertFalse(value.getBoolean("defaultEnabled"))
        assertEquals(2, value.getInt("maxCameraAborts")); assertEquals(64, value.getInt("eventLimit"))
        assertEquals("Short acceptance must retain every diagnostic event", 0L, value.getLong("droppedEvents"))
        val events = value.getJSONArray("events"); assertTrue(events.length() <= 64)
        assertTrue(value.getLong("abortedCount") >= 0 && value.getLong("completedCount") >= 0)
        assertTrue(value.getLong("pinnedCompletedCount") in 0..value.getLong("completedCount"))
        for (index in 0 until events.length()) {
            val event = events.getJSONObject(index)
            assertTrue(event.getLong("expectedEpoch") > 0 && event.getLong("latestEpoch") > 0)
            if (event.getBoolean("full")) assertEquals("FULL_COMPLETE", event.getString("reason"))
            else assertTrue(event.getString("reason") in setOf("NEW_CAMERA", "SOURCE_CHANGED", "SURFACE_CHANGED", "ENGINE_CLOSED", "APPLIED_AUTHORITY_UNBOUND"))
            assertTrue(event.getString("boundary").isNotBlank())
            event.getBoolean("cameraPinned") // Retain original pinned authority; never substitute the latest epoch.
        }
    }
}
