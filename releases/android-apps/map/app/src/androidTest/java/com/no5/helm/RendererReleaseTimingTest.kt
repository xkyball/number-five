package com.no5.helm

import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.chart.*
import com.no5.helm.model.MapPositionController
import com.no5.helm.model.PositionMode
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Explicit, regular-release CURRENT_VIEW_REUSE timing only. Each requested 15s arm uses the
 * existing six-pose native owner and restores before the next arm; no source switch, settings
 * override, pixels, object query, extra draw, detailed profile or normal-abort experiment.
 * PASS means execution/restoration only. Raw counterexamples are never filtered by speed.
 * Each arm requests a 250ms pause between read-only UI/position/status checks. Compose fetching
 * may synchronize; actual check times/durations are retained, not claimed constant-rate or free.
 */
class RendererReleaseTimingTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun currentResidentRequestedReuseSequenceRestoresEveryArm() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit independent timing opt-in required", arguments.getString("no5.renderer.timing") == "true")
        val requested = requireNotNull(arguments.getString("no5.renderer.timing.variants"))
        require(requested.length <= 160)
        val variants = requested.split(',').map { RendererViewportReuseVariant.valueOf(it) }
        require(variants.size in 2..12 && variants.size % 2 == 0) { "Request 2–12 arms explicitly; never append/retry an arm" }
        val candidates = variants.filter { it != RendererViewportReuseVariant.OFF }.toSet()
        require(candidates.size == 1) { "One candidate per invocation, paired with OFF" }
        val candidate = candidates.single()
        require(variants.chunked(2).all { it.toSet() == setOf(RendererViewportReuseVariant.OFF, candidate) }) {
            "Every pair must contain OFF and the one candidate; explicit ABBA is also accepted"
        }
        val proof = RendererReleaseAcceptanceSupport(compose) // Actual target/test/signer/source/native-ZIP pins before launch.
        val summaries = JSONArray()
        val result = JSONObject().put("schema", "no5.renderer-release-timing-acceptance/1")
            .put("run", proof.run).put("status", "FAIL").put("identity", proof.identity)
            .put("requestedVariants", JSONArray(variants.map { it.name })).put("arms", summaries)
            .put("passScope", "EXECUTION_AND_EXACT_OWNER_RESTORATION_ONLY")
            .put("performanceBenefit", "NOT_ESTABLISHED").put("adoption", "NOT_ESTABLISHED")
            .put("physicalDisplay", "NOT_MEASURED").put("gpuTimeOrFps", "NOT_MEASURED")
            .put("startup", "NOT_MEASURED").put("windowPixelCopy", "NOT_RUN")
            .put("pixelQueryParity", "SEPARATE_RUN_REQUIRED")
            .put("loadedNativeOrigin", "EXTERNAL_PROCESS_MAP_PROOF_REQUIRED; APK entry pin is not loaded ELF proof")
            .put("observer", "Requested 250ms pause plus synchronous Compose/position/status checks; actual intervals/durations retained, not constant-rate or free; events between checks are not excluded")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var current: ChartEngine? = null
                var positions: MapPositionController? = null
                scenario.onActivity { current = proof.engine(it); positions = positionOwner(it) }
                val engine = requireNotNull(current)
                val position = requireNotNull(positions)
                proof.ready(engine); proof.uncovered(compose)
                val entry = engine.status.value
                if (!entry.residentCatalogActive || entry.bufferTransformMode != ChartBufferTransformMode.OFF) {
                    result.put("status", "UNAVAILABLE").put("reason", "CURRENT_RESIDENT_CATALOG_AND_OFF_REQUIRED")
                    assumeTrue("No catalog substitution or mode override is permitted", false)
                }
                val pid = Process.myPid()
                val processStart = Process.getStartElapsedRealtime()
                val initialIntent = position.state.value.intent
                checkContext(scenario, engine, position, entry, pid, processStart)
                val (original, preparation) = proof.prepareCurrentViewReuse(engine) { config, prefix ->
                    executeObserved(proof, scenario, engine, position, entry, config, prefix, pid, processStart)
                }
                result.put("preparation", preparation)
                assertEquals(initialIntent, position.state.value.intent)
                var originalState: JSONObject? = null
                var baseline: JSONObject? = null
                val owners = mutableSetOf<String>()
                for ((index, variant) in variants.withIndex()) {
                    val prefix = "ARM-${('A'.code + index).toChar()}-${variant.name}"
                    proof.ready(engine); proof.uncovered(compose)
                    checkContext(scenario, engine, position, original, pid, processStart)
                    assertEquals(initialIntent, position.state.value.intent)
                    if (index != 0) proof.original(original, engine.status.value, exactSource = true)
                    val before = snapshot(engine)
                    proof.write("$prefix-outer-before.json", before.toString())
                    val state = savedState(before)
                    if (originalState == null) originalState = state else assertEquals("Exact original source/catalog/settings before every arm",
                        originalState!!.toString(), state.toString())
                    val configure = Class.forName("com.no5.helm.ui.RendererCurrentViewMeasurementKt").declaredMethods.single {
                        it.name.startsWith("currentViewReuseConfig") && it.parameterCount == 6
                    }.apply { isAccessible = true }
                    val config = configure.invoke(null, before.toString(), engine.status.value, variant, false,
                        SystemClock.elapsedRealtime(), UUID.randomUUID().toString()) as RendererBenchmarkConfig
                    assertFalse(config.viewportReuseDetailed); assertFalse(config.viewportReuseParity); assertFalse(config.normalRenderAbort)
                    assertNull(config.currentResidentLive)
                    assertEquals("CURRENT_VIEW_REUSE", config.capacityRecipeId)
                    assertEquals(15_000L, requireNotNull(config.capacityArm).durationMs)
                    val armSummary = JSONObject().put("index", index).put("variant", variant.name).put("prefix", prefix)
                        .put("rawReport", "$prefix-native.json").put("executionStatus", "IN_PROGRESS")
                        .put("performanceGate", "NOT_EVALUATED")
                    summaries.put(armSummary)
                    val report = executeObserved(proof, scenario, engine, position, original, config, prefix, pid, processStart)
                    val signature = validateReport(report, original, variant)
                    val owner = report.getJSONObject("metadata").getString("runId")
                    check(owners.add(owner)) { "Repeated/stale owner report" }
                    if (baseline == null) baseline = signature else assertEquals("Frozen recipe/source pins/budget/settings authority across all arms",
                        baseline!!.toString(), signature.toString())
                    proof.original(original, engine.status.value, exactSource = true)
                    proof.ready(engine); proof.uncovered(compose)
                    checkContext(scenario, engine, position, original, pid, processStart)
                    assertEquals(initialIntent, position.state.value.intent)
                    val after = snapshot(engine)
                    proof.write("$prefix-outer-after.json", after.toString())
                    assertEquals("Exact saved source/catalog/settings/overlays restored after this arm", originalState!!.toString(), savedState(after).toString())
                    armSummary.put("ownerRunId", owner)
                        .put("rawReportSha256", fileHash(File(proof.directory, "$prefix-native.json")))
                        .put("completePoses", 6).put("changedPoseIndices", JSONArray(listOf(1, 2, 3, 4, 5)))
                        .put("restored", true).put("executionStatus", "PASS")
                    proof.write("$prefix-signature.json", signature.toString(2), 512 * 1024)
                }
                result.put("status", "PASS").put("completedArms", variants.size).put("sameProcess", true)
                    .put("allRawReportsRetained", true).put("originalStateRestoredAfterEveryArm", true)
                    .put("nativeFlagsRestoredAfterEveryArm", true).put("normalAbort", false)
                    .put("detailed", false).put("parity", false)
                    .put("latencyEndpoint", "Changed poses 1–5: proposedAtNs to matching FULL_PRODUCED.startNs; external paired analysis required, never FULL.endNs")
                    .put("counterSemantics", "Raw values retained. Sounding/GPU counters cumulative; spatial returned/rejected/boundsTested are last-query values, not subtraction deltas")
            }
        } catch (failure: Throwable) {
            result.put("failureType", failure.javaClass.simpleName)
            if (summaries.length() != 0) summaries.getJSONObject(summaries.length() - 1).let { last ->
                if (last.getString("executionStatus") != "PASS") last.put("executionStatus", "FAIL")
            }
            throw failure
        } finally { proof.write("result.json", result.toString(2), 64 * 1024) }
    }

    private fun positionOwner(activity: MainActivity): MapPositionController {
        val runtime = MainActivity::class.java.getDeclaredMethod("getRuntime").apply { isAccessible = true }.invoke(activity)
        return runtime.javaClass.getDeclaredMethod("getPositions").apply { isAccessible = true }.invoke(runtime) as MapPositionController
    }

    private fun snapshot(engine: ChartEngine) = JSONObject(runBlocking {
        requireNotNull(engine.snapshotRendererOuterState(UUID.randomUUID().toString()))
    })

    /** Snapshot IDs/continuing camera revisions differ; persisted semantic state must be exact. */
    private fun savedState(outer: JSONObject): JSONObject {
        assertEquals("no5-capacity-outer-state/1", outer.getString("schema"))
        assertEquals("CURRENT_ENTRY_CATALOG_ONLY", outer.getString("scope"))
        val state = outer.getJSONObject("state")
        return JSONObject().also { signature ->
            for (key in listOf("chartId", "paths", "viewport", "presentation", "settings", "overlays", "bufferTransformMode",
                "residentSourceGeneration", "residentPolicyKey", "residentActivation")) signature.put(key, state.get(key))
        }
    }

    private fun checkContext(scenario: ActivityScenario<MainActivity>, engine: ChartEngine, positions: MapPositionController,
        original: ChartEngineStatus, pid: Int, processStart: Long) {
        assertEquals(pid, Process.myPid()); assertEquals(processStart, Process.getStartElapsedRealtime())
        scenario.onActivity { activity ->
            check(!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()) { "Current chart Activity/window not foreground" }
        }
        val position = positions.state.value
        check(!position.followActive && !position.intent.follow && position.mode != PositionMode.FOLLOW && !position.touchActive) {
            "Active/requested Follow or touch: leave the existing position intent unchanged and refuse timing"
        }
        val state = engine.status.value
        check(state.surfaceAttached && state.chartLoaded && state.residentCatalogActive && !state.sourcePresentationUnsupported)
        assertEquals(original.chartSourceGeneration, state.chartSourceGeneration)
        assertEquals(original.surfaceGeneration, state.surfaceGeneration)
        assertEquals(original.residentCatalogGeneration, state.residentCatalogGeneration)
        assertEquals(original.presentation, state.presentation)
        assertEquals(original.objectFilters.effective, state.objectFilters.effective)
        assertEquals(ChartBufferTransformMode.OFF, state.bufferTransformMode)
        check(!state.rendererCapture.active) { "Another capture owner is active" }
    }

    /** Existing support alone owns start/cancel/recovery; observer failure never discards its raw report. */
    private fun executeObserved(proof: RendererReleaseAcceptanceSupport, scenario: ActivityScenario<MainActivity>, engine: ChartEngine,
        positions: MapPositionController, original: ChartEngineStatus, config: RendererBenchmarkConfig, prefix: String,
        pid: Int, processStart: Long): JSONObject {
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(Callable { proof.execute(engine, config, prefix) })
        var invalid: Throwable? = null
        var executionFailure: Throwable? = null
        var observations = 0
        val checks = JSONArray()
        try {
            while (!future.isDone) {
                if (invalid == null) {
                    val startedNs = System.nanoTime()
                    observations++
                    try {
                        check(checks.length() < 1024) { "Bounded observer log exhausted; arm invalid, raw owner report still retained" }
                        checkContext(scenario, engine, positions, original, pid, processStart)
                        proof.uncovered(compose)
                    } catch (failure: Throwable) { invalid = failure }
                    finally {
                        if (checks.length() < 1024) checks.put(JSONObject().put("startNs", startedNs).put("endNs", System.nanoTime())
                            .put("failureType", invalid?.javaClass?.simpleName ?: JSONObject.NULL))
                    }
                }
                SystemClock.sleep(250)
            }
            val report = try { future.get() } catch (failure: ExecutionException) { throw (failure.cause ?: failure) }
            invalid?.let { throw it }
            return report
        } catch (failure: Throwable) { executionFailure = failure; throw failure }
        finally {
            executor.shutdown()
            proof.write("$prefix-observer.json", JSONObject().put("requestedPauseMs", 250).put("observations", observations)
                .put("clock", "MONOTONIC-System.nanoTime").put("checks", checks).put("checkLimit", 1024)
                .put("valid", invalid == null).put("failureType", invalid?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("executionFailureType", executionFailure?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("scope", "Synchronous read-only samples can add observer cost; no fixed-rate, zero-cost, physical-input or display claim").toString(2), 256 * 1024)
        }
    }

    private fun validateReport(report: JSONObject, start: ChartEngineStatus, variant: RendererViewportReuseVariant): JSONObject {
        assertEquals("no5-resident-capacity/1", report.getString("schema"))
        assertEquals(15_000L, report.getLong("requestedDurationMs"))
        val metadata = report.getJSONObject("metadata")
        val owner = metadata.getString("runId")
        assertEquals(variant.name, metadata.getString("viewportReuse"))
        assertFalse(metadata.getBoolean("parity")); assertFalse(metadata.getBoolean("detailed")); assertTrue(metadata.getBoolean("timingComparable"))
        for (key in listOf("catalogSwap", "geometryReset", "automaticActivation")) assertFalse(metadata.getBoolean(key))
        assertEquals(start.residentCatalogGeneration, metadata.getString("catalogGeneration"))
        assertEquals(start.chartSourceGeneration, metadata.getLong("sourceGeneration"))
        assertEquals("WARM", metadata.getString("cacheMode"))
        val configured = metadata.getJSONObject("viewportReuseDiagnostic")
        assertEquals("no5.viewport-reuse-diagnostic/1", configured.getString("schema"))
        assertEquals(owner, configured.getString("runId")); assertTrue(configured.getBoolean("available")); assertTrue(configured.getBoolean("active"))
        assertEquals(variant.name, configured.getString("variant"))
        for ((flag, individual) in listOf("soundings" to RendererViewportReuseVariant.SOUNDINGS,
            "spatial" to RendererViewportReuseVariant.SPATIAL, "gpu" to RendererViewportReuseVariant.GPU)) {
            assertFalse(configured.getJSONObject("original").getBoolean(flag))
            assertEquals(variant == individual || variant == RendererViewportReuseVariant.ALL, configured.getJSONObject("current").getBoolean(flag))
        }
        val restored = report.getJSONObject("viewportReuseRestoration")
        assertEquals(owner, restored.getString("runId")); assertTrue(restored.getBoolean("available")); assertFalse(restored.getBoolean("active"))
        for (flag in listOf("soundings", "spatial", "gpu")) {
            assertFalse("Production defaults remain OFF", restored.getJSONObject("original").getBoolean(flag))
            assertEquals(restored.getJSONObject("original").getBoolean(flag), restored.getJSONObject("current").getBoolean(flag))
        }
        val events = report.getJSONObject("events")
        assertTrue(events.getBoolean("requiredInputPathValid"))
        assertFalse(events.has("viewportReuseParity"))
        assertEquals(0, events.getInt("nativeFrameReportsOmitted"))
        val recipe = events.getJSONObject("recipe")
        assertEquals("CURRENT_VIEW_REUSE", recipe.getString("id")); assertEquals("completed", recipe.getString("phase"))
        assertEquals(15_000L, recipe.getLong("durationMs")); assertTrue(recipe.getBoolean("comparableRealizedPath"))
        assertTrue(recipe.getString("sha256").matches(Regex("[a-f0-9]{64}")))
        val steps = recipe.getJSONArray("results"); assertEquals(6, steps.length())
        val software = events.getJSONObject("softwareEvents")
        assertEquals("no5-resident-software-events/1", software.getString("schema")); assertEquals(owner, software.getString("runId"))
        assertTrue(software.getBoolean("valid")); assertFalse(software.getBoolean("overflow")); assertEquals(0, software.getInt("invalidEvents"))
        assertEquals("MONOTONIC-System.nanoTime", software.getString("clock"))
        val fulls = software.getJSONArray("events")
        assertEquals(software.getInt("eventCount"), fulls.length())
        for (index in 0 until 6) {
            val step = steps.getJSONObject(index)
            assertEquals(index, step.getInt("stepIndex")); assertTrue(step.getBoolean("sameAuthority")); assertTrue(step.getBoolean("sameRealizedPose"))
            val response = step.getJSONObject("result"); assertTrue(response.getBoolean("accepted"))
            val authority = response.getJSONObject("authority")
            assertEquals(start.chartSourceGeneration, authority.getLong("sourceGeneration"))
            assertEquals(start.surfaceGeneration, authority.getLong("surfaceGeneration"))
            if (index == 0) continue // Pose zero is an already-existing cache baseline, not a changed-pose latency.
            val proposed = step.getLong("proposedAtNs")
            val matches = (0 until fulls.length()).map { fulls.getJSONObject(it) }.filter { event ->
                event.getString("kind") == "FULL_PRODUCED" && event.getJSONObject("authority").let { full ->
                    full.getLong("cameraRevision") == authority.getLong("cameraRevision") &&
                        full.getLong("sourceGeneration") == authority.getLong("sourceGeneration") &&
                        full.getLong("surfaceGeneration") == authority.getLong("surfaceGeneration") &&
                        full.getString("catalogGeneration") == authority.getString("catalogGeneration") &&
                        full.getJSONObject("viewport").toString() == step.getJSONObject("wantedViewport").toString()
                }
            }
            check(matches.isNotEmpty()) { "Changed pose $index has no matching actual FULL_PRODUCED" }
            check(matches.minOf { it.getLong("startNs") } >= proposed) { "Matching FULL predates this proposal" }
        }
        // Camera revisions and runtime counters are retained in raw reports, never relabelled or subtracted.
        val frozen = recipe.getJSONObject("frozenAuthority")
        val stable = JSONObject()
        for (key in listOf("catalogGeneration", "sourceGeneration", "surfaceGeneration", "budget", "viewport", "bufferTransformMode",
            "presentation", "visible", "reserve")) stable.put(key, frozen.get(key))
        return JSONObject().put("recipeSha256", recipe.getString("sha256")).put("steps", recipe.getJSONArray("steps"))
            .put("frozenAuthority", stable).put("sourcePins", metadata.getJSONArray("sourcePins"))
            .put("cells", metadata.getInt("cells")).put("chargedByteBudget", metadata.getLong("chargedByteBudget"))
            .put("loadingByteReservation", metadata.getLong("loadingByteReservation"))
    }

    private fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16_384)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
