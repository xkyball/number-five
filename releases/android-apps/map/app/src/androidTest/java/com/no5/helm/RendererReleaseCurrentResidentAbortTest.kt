package com.no5.helm

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.chart.*
import com.no5.helm.model.MapPositionController
import com.no5.helm.ui.rendererDiagnosticCameraStartBlocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Independent, opt-in LIVE_INPUT/CONTROL evidence on the exact current resident catalog.
 * The regular Activity Host and its Follow/touch guard admit the run. No source/Follow toggle,
 * copied camera recipe, raw JNI, producer ACK, Window capture or extra timed draw is used.
 * APK-entry identity is not loaded-ELF evidence; the external before/after maps proof is required.
 */
class RendererReleaseCurrentResidentAbortTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun explicitCurrentResidentNormalAbortReplayRestoresOriginalOwner() {
        assumeTrue("Explicit independent current-resident opt-in required", InstrumentationRegistry.getArguments()
            .getString("no5.renderer.currentResidentAbort") == "true")
        val proof = RendererReleaseAcceptanceSupport(compose)
        val enabled = requireNotNull(proof.arguments.getString("no5.renderer.currentResidentAbort.enabled")).let {
            require(it == "true" || it == "false"); it == "true"
        }
        val duration = requireNotNull(proof.arguments.getString("no5.renderer.currentResidentAbort.durationMs")).toLong()
        require(duration in 5_000..30_000)
        val recipe = RendererReplayRecipe.valueOf(requireNotNull(proof.arguments.getString("no5.renderer.currentResidentAbort.recipe")))
        val catalogPin = requireNotNull(proof.arguments.getString("no5.renderer.currentResidentAbort.catalogGeneration"))
        require(catalogPin.matches(Regex("[a-f0-9]{64}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = context.assets.open(RendererTouchReplay.asset(recipe)).bufferedReader().use { it.readText() }
        require(text.length <= 128 * 1024)
        val replay = RendererTouchReplay.parse(text, recipe) // Existing exact SHA/bounds, not a second replay implementation.
        require(replay.last().offsetMs <= duration)
        val result = JSONObject().put("schema", "no5.renderer-release-current-resident-abort-acceptance/1")
            .put("run", proof.run).put("status", "NOT_ACCEPTED").put("identity", proof.identity)
            .put("normalAbortRequested", enabled).put("requestedLiveDurationMs", duration)
            .put("replayRecipe", recipe.name).put("replaySha256", RendererTouchReplay.sha256(recipe))
            .put("passScope", "EXACT_CURRENT_RESIDENT_EXECUTION_AND_RESTORATION_ONLY")
            .put("inputOrigin", "VIEW_INJECTED; not a physical knob/finger proof")
            .put("physicalDisplay", "NOT_MEASURED").put("gpuTimeOrFps", "NOT_MEASURED")
            .put("windowPixelCopy", "NOT_RUN").put("performanceBenefit", "NOT_ESTABLISHED")
            .put("loadedNativeOrigin", "EXTERNAL_PROCESS_MAP_PROOF_REQUIRED")
            .put("ownerStarted", false).put("catalogSwitch", false).put("geometryReset", false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var activity: MainActivity
                lateinit var engine: ChartEngine
                lateinit var positions: MapPositionController
                scenario.onActivity {
                    activity = it; engine = proof.engine(it)
                    positions = read(read(it, "Runtime"), "Positions") as MapPositionController
                }
                proof.ready(engine); proof.uncovered(compose)
                requireHostReady(activity)
                val entry = engine.status.value
                if (!entry.residentCatalogActive || entry.bufferTransformMode != ChartBufferTransformMode.OFF) {
                    result.put("status", "UNAVAILABLE").put("reason", "CURRENT_RESIDENT_CATALOG_AND_OFF_REQUIRED")
                    assumeTrue("No replacement source or mode override is permitted", false)
                }
                assertEquals("Explicit current catalog pin", catalogPin, entry.residentCatalogGeneration)
                val originalIntent = positions.state.value.intent
                // Awaiting the snapshot may allow ordinary loading to finish before capture. The entry
                // status is eligibility only; it cannot be the later snapshot's restoration oracle.
                val bound = runBlocking {
                    rendererSnapshotBoundOriginal(snapshot = {
                        val outer = snapshot(activity)
                        val typed = typedSnapshot(engine, outer)
                        val frozen = frozen(outer, typed)
                        CapturedOriginal(outer, frozen) to originalPins(outer, typed, frozen)
                    }, status = { engine.status.value })
                }
                val original = bound.state
                val before = bound.snapshot.outer
                val expected = bound.snapshot.frozen
                proof.write("outer-before.json", before.toString())
                assertEquals(catalogPin, expected.catalog.generation)
                proof.write("frozen-before.json", expected.pins().toString(2), 64 * 1024)
                result.put("originalStatusBinding", "SINGLE_TYPED_SNAPSHOT_THEN_EXACT_STATUS_CAS; no preparation or retry")
                    .put("entryLoadedCellCount", entry.loadedChartCells.size)
                    .put("snapshotBoundLoadedCellCount", original.loadedChartCells.size)
                val configure = Class.forName("com.no5.helm.ui.RendererCurrentViewMeasurementKt").declaredMethods.single {
                    it.name.startsWith("currentResidentLiveInputConfig") && it.parameterTypes.contentEquals(arrayOf(
                        String::class.java, ChartEngineStatus::class.java, Boolean::class.javaPrimitiveType,
                        Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, String::class.java))
                }.apply { isAccessible = true }
                val config = (configure.invoke(null, before.toString(), engine.status.value, enabled,
                    SystemClock.elapsedRealtime(), duration, UUID.randomUUID().toString()) as RendererBenchmarkConfig)
                    .copy(liveReplayRecipe = recipe) // Only the existing exact-pinned replay choice changes.
                assertEquals(before.getString("snapshotId"), requireNotNull(config.currentResidentLive).snapshotId)
                assertEquals(RendererRunKind.LIVE_INPUT, config.runKind)
                assertEquals(ChartBufferTransformMode.CONTROL, config.liveMode)
                assertNull(config.capacityArm); assertNull(config.capacityRecipeId); assertNull(config.viewportReuseCandidate)
                assertFalse(config.viewportReuseDetailed); assertFalse(config.viewportReuseParity)
                assertEquals(enabled, config.normalRenderAbort); assertTrue(config.liveReplay)
                val report = proof.execute(engine, config, if (enabled) "CURRENT-ON" else "CURRENT-OFF") { requested ->
                    requireHostReady(activity) // Actual shared guard again, immediately before the real Host path.
                    invokeHost<EngineResult>(activity, "startBenchmark", requested).also { started ->
                        result.put("ownerStarted", started.success)
                        if (started.success) result.put("ownerRunId", started.message)
                    }
                }
                result.put("ownerStarted", true).put("ownerRunId", report.getString("runId"))
                validateReport(report, expected, before.getString("snapshotId"), config, result)
                proof.ready(engine); proof.uncovered(compose); requireHostReady(activity)
                proof.original(original, engine.status.value, exactSource = true)
                assertEquals("The original position intent remains untouched", originalIntent, positions.state.value.intent)
                val after = snapshot(activity)
                proof.write("outer-after.json", after.toString())
                val restored = frozen(engine, after)
                proof.write("frozen-after.json", restored.pins().toString(2), 64 * 1024)
                assertEquals("Exact saved catalog, budget, settings, overlays and camera restored",
                    savedState(before).toString(), savedState(after).toString())
                assertEquals("Original immutable data/interpretation authority restored", expected, restored)
                result.put("status", "PASS").put("independentDiagnosticCompleted", true)
                    .put("originalStateRestored", true).put("sourceFreezeValidated", true)
                    .put("allRawCounterexamplesRetained", true)
            }
        } catch (failure: Throwable) {
            result.put("failureType", failure.javaClass.simpleName)
                .put("failureMessage", failure.message?.take(512) ?: JSONObject.NULL)
            throw failure
        } finally { proof.write("result.json", result.toString(2), 64 * 1024) }
    }

    /** Host guard is read on Main, including a paused Follow request. Nothing is disabled to admit a run. */
    private fun requireHostReady(activity: MainActivity) = runBlocking { withContext(Dispatchers.Main.immediate) {
        check(!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()) { "UNAVAILABLE_FOREGROUND_WINDOW" }
        val positions = read(read(activity, "Runtime"), "Positions") as MapPositionController
        val charts = MainActivity::class.java.getDeclaredField("charts").apply { isAccessible = true }
            .get(activity) as? PersistentChartController
        val blocker = rendererDiagnosticCameraStartBlocker(positions.state.value, charts == null || charts.state.value.busy)
        check(blocker == null) { "UNAVAILABLE_HOST_CAMERA_OWNER: $blocker" }
    } }

    private fun snapshot(activity: MainActivity): JSONObject = JSONObject(runBlocking {
        invokeHost<String>(activity, "snapshotOuterState", UUID.randomUUID().toString())
    }).also {
        assertEquals("no5-capacity-outer-state/1", it.getString("schema"))
        assertEquals("CURRENT_ENTRY_CATALOG_ONLY", it.getString("scope"))
    }

    /** Kotlin internal JVM suffixes vary by target variant. Resolve the actual suspend method by shape. */
    private suspend fun <T> invokeHost(activity: MainActivity, name: String, argument: Any): T =
        withContext(Dispatchers.Main.immediate) {
            val method = MainActivity::class.java.declaredMethods.single {
                (it.name == name || it.name.startsWith("$name\$")) && it.parameterCount == 2 &&
                    it.parameterTypes[0].isInstance(argument) && it.parameterTypes[1] == Continuation::class.java
            }.apply { isAccessible = true }
            suspendCoroutine { continuation: Continuation<T> ->
                try {
                    val value = method.invoke(activity, argument, continuation)
                    @Suppress("UNCHECKED_CAST")
                    if (value !== COROUTINE_SUSPENDED) continuation.resume(value as T)
                } catch (failure: InvocationTargetException) { continuation.resumeWithException(failure.targetException) }
                catch (failure: Throwable) { continuation.resumeWithException(failure) }
            }
        }

    private data class Frozen(val session: String, val catalog: ChartResidentCatalog, val source: Long,
        val surface: Long, val content: Long, val nativeData: Long, val nativeContent: Long,
        val presentationGeneration: Long, val policy: String, val presentation: Any, val settings: Any,
        val requestedSettings: Any, val overlays: Any) {
        fun pins() = JSONObject().put("rendererSession", session).put("catalogGeneration", catalog.generation)
            .put("catalogSourceCount", catalog.sources.size).put("sourceGeneration", source)
            .put("surfaceGeneration", surface).put("contentGeneration", content).put("nativeDataGeneration", nativeData)
            .put("nativeContentRevision", nativeContent).put("presentationGeneration", presentationGeneration)
            .put("policyKey", policy).put("oracle", "ORIGINAL_SYNCHRONIZED_TYPED_OWNER_SNAPSHOT; not completion-report values")
    }

    private data class CapturedOriginal(val outer: JSONObject, val frozen: Frozen)

    /** Passive immutable slot read; no JNI or owner mutation. A missing slot is never inferred from the report. */
    private fun typedSnapshot(engine: ChartEngine, outer: JSONObject): Any {
        val slot = engine.javaClass.getDeclaredField("currentResidentLiveSnapshots").apply { isAccessible = true }.get(engine)
        val id = outer.getString("snapshotId")
        val getter = slot.javaClass.declaredMethods.single {
            (it.name == "get" || it.name.startsWith("get\$")) && it.parameterTypes.contentEquals(arrayOf(String::class.java))
        }.apply { isAccessible = true }
        val snapshot = checkNotNull(getter.invoke(slot, id)) { "UNAVAILABLE_TYPED_CURRENT_RESIDENT_SNAPSHOT" }
        assertEquals(id, read(snapshot, "Id"))
        return snapshot
    }

    private fun frozen(engine: ChartEngine, outer: JSONObject): Frozen = frozen(outer, typedSnapshot(engine, outer))

    private fun frozen(outer: JSONObject, snapshot: Any): Frozen {
        val identity = read(snapshot, "Identity")
        val value = Frozen(read(identity, "RendererSession") as String, read(identity, "Catalog") as ChartResidentCatalog,
            read(identity, "Source") as Long, read(identity, "Surface") as Long, read(identity, "Content") as Long,
            read(identity, "NativeData") as Long, read(identity, "NativeContent") as Long,
            read(identity, "PresentationGeneration") as Long, read(identity, "PolicyKey") as String,
            read(identity, "Presentation"), read(identity, "Settings"), read(identity, "RequestedSettings"), read(identity, "Overlays"))
        val state = outer.getJSONObject("state"); val authority = outer.getJSONObject("authority")
        val residency = outer.getJSONObject("residency")
        assertEquals(outer.getString("rendererSession"), value.session)
        assertEquals(state.getJSONObject("residentActivation").getJSONObject("catalog").getString("generation"), value.catalog.generation)
        assertEquals(authority.getString("catalogGeneration"), value.catalog.generation)
        assertEquals(state.getLong("residentSourceGeneration"), value.source)
        assertEquals(authority.getLong("sourceGeneration"), value.source)
        assertEquals(authority.getLong("surfaceGeneration"), value.surface)
        assertEquals(authority.getLong("contentGeneration"), value.content)
        assertEquals(authority.getLong("presentationGeneration"), value.presentationGeneration)
        assertEquals(residency.getLong("contentRevision"), value.nativeContent)
        assertEquals(state.getString("residentPolicyKey"), value.policy)
        assertEquals(residency.getString("policyKey"), value.policy)
        check(value.catalog.sources.isNotEmpty())
        check(listOf(value.source, value.surface, value.content, value.nativeData, value.nativeContent, value.presentationGeneration).all { it >= 0 })
        return value
    }

    /** Field origins are the exact typed slot plus its same-call native residency projection.
     * Source keys are resolved through that frozen catalog, preserving native order, never guessed
     * from filenames or a newer status. All remaining identity/restoration checks stay unchanged. */
    private fun originalPins(outer: JSONObject, snapshot: Any, frozen: Frozen): RendererOriginalSnapshotPins {
        val full = read(snapshot, "Full") as ChartBufferSnapshot
        assertEquals(read(snapshot, "Geometry"), full.frame.geometry)
        assertEquals(read(snapshot, "CameraRevision"), full.cameraRevision)
        val producer = outer.getJSONObject("fullProducerBuffer")
        assertEquals(producer.getLong("stamp"), full.stamp)
        assertEquals(producer.getLong("cameraRevision"), full.cameraRevision)
        assertEquals(producer.getLong("sourceGeneration"), full.frame.sourceGeneration)
        assertEquals(producer.getLong("contentGeneration"), full.frame.contentGeneration)
        assertTrue(producer.getBoolean("producerCompleted"))
        val sources = outer.getJSONObject("residency").getJSONArray("sources")
        val keys = List(sources.length()) { sources.getString(it) }
        return RendererOriginalSnapshotPins(frozen.session, frozen.catalog.generation, frozen.source,
            frozen.content, frozen.surface, frozen.presentationGeneration,
            frozen.presentation as ChartPresentation, (frozen.settings as S52Settings).objectFilters, full,
            rendererSnapshotLoadedNames(frozen.catalog, keys))
    }

    private fun read(target: Any, name: String): Any = checkNotNull(target.javaClass.declaredMethods.single {
        (it.name == "get$name" || it.name.startsWith("get$name\$")) && it.parameterCount == 0
    }.apply { isAccessible = true }.invoke(target)) { "UNAVAILABLE_REQUIRED_PROPERTY_$name" }

    private fun savedState(outer: JSONObject) = JSONObject().also { saved ->
        val state = outer.getJSONObject("state")
        for (key in listOf("chartId", "paths", "viewport", "presentation", "settings", "overlays", "bufferTransformMode",
            "residentSourceGeneration", "residentPolicyKey", "residentActivation")) saved.put(key, state.get(key))
    }

    private fun validateReport(report: JSONObject, expected: Frozen, snapshotId: String,
        config: RendererBenchmarkConfig, result: JSONObject) {
        assertEquals("no5-renderer-live-input/1", report.getString("schema"))
        assertEquals("LIVE_INPUT", report.getString("runKind")); assertEquals("", report.getString("error"))
        assertEquals("", report.getString("restorationError"))
        val owner = report.getString("runId")
        val control = report.getJSONObject("measurementControl")
        assertEquals("no5.measurement-control/1", control.getString("schema")); assertEquals(owner, control.getString("runId"))
        assertEquals(config.clientRunToken, control.getString("clientRunToken"))
        assertEquals(config.deadlineElapsedRealtimeMs, control.getLong("deadlineElapsedRealtimeMs"))
        assertEquals(config.recoveryBudgetMs, control.getLong("recoveryBudgetMs")); assertEquals(1, control.getInt("requestedWorkers"))
        assertEquals("", control.getString("cancelReason")); assertFalse(control.getBoolean("recoveryDeadlineExceeded"))
        val recoveryStart = control.getLong("recoveryStartedElapsedRealtimeMs")
        val recoveryEnd = control.getLong("recoveryFinishedElapsedRealtimeMs")
        check(recoveryStart >= 0 && recoveryEnd >= recoveryStart && recoveryEnd - recoveryStart <= config.recoveryBudgetMs)
        val metadata = report.getJSONObject("metadata"); val dataset = metadata.getJSONObject("dataset")
        assertEquals(owner, metadata.getString("runId")); assertEquals("CONTROL", metadata.getString("mode"))
        assertEquals("CURRENT_RESIDENT_FROZEN_DATA", dataset.getString("scope")); assertTrue(dataset.getBoolean("private"))
        assertEquals(expected.catalog.generation, dataset.getString("catalogGeneration"))
        assertEquals(expected.catalog.sources.size, dataset.getInt("catalogSourceCount"))
        assertFalse(dataset.getBoolean("geometryReset")); assertFalse(dataset.getBoolean("catalogSwitch"))
        assertFalse(metadata.has("publicPresetViewport")); assertEquals(snapshotId, metadata.getString("currentResidentSnapshotId"))
        assertEquals("PRIVATE_CURRENT_VIEW", metadata.getString("coordinatePrivacy"))
        for ((key, value) in listOf("sourceGeneration" to expected.source, "surfaceGeneration" to expected.surface,
            "contentGeneration" to expected.content, "nativeDataGeneration" to expected.nativeData,
            "nativeContentRevision" to expected.nativeContent)) assertEquals(key, value, metadata.getLong(key))
        assertEquals("Immutable current resident data interval; loading changes abort", metadata.getString("sourceFreezeScope"))
        assertEquals(config.normalRenderAbort, metadata.getBoolean("normalRenderAbort"))
        assertEquals(config.liveDurationMs, metadata.getLong("liveDurationMs")); assertTrue(metadata.getBoolean("replayEnabled"))
        assertEquals(config.liveReplayRecipe.name, metadata.getString("replayRecipe"))
        assertEquals(RendererTouchReplay.sha256(config.liveReplayRecipe), metadata.getString("replaySha256"))
        val observations = report.getJSONObject("observations")
        val freeze = observations.getJSONObject("currentResidentFreeze")
        assertTrue(freeze.getBoolean("valid")); assertTrue(freeze.isNull("reason"))
        assertEquals("", observations.getString("completionError"))
        val input = observations.getJSONObject("input")
        assertEquals(owner, input.getString("runId")); assertEquals("CURRENT_RESIDENT_FROZEN_DATA", input.getString("sourceScope"))
        assertTrue(input.getString("coordinates").startsWith("PRIVATE_CURRENT_VIEW;"))
        assertEquals("COMPLETED", input.getString("replayState"))
        assertEquals(config.liveReplayRecipe.name, input.getString("replayRecipe"))
        assertEquals(RendererTouchReplay.sha256(config.liveReplayRecipe), input.getString("replaySha256"))
        assertTrue(input.getBoolean("storageCoverageComplete")); assertEquals(0L, input.getLong("dropped"))
        check(input.getInt("maxRows") <= 8192 && input.getInt("maxInputs") <= 2048 && input.getInt("maxBuffers") <= 1024)
        check(input.getLong("inputSamples") > 0)
        validateProductionRows(input, result)
        val capture = observations.getJSONObject("nativeCapture")
        assertEquals("no5-renderer-capture/1", capture.getString("schema")); assertTrue(capture.getLong("frames") > 0)
        assertTrue(capture.getJSONArray("events").length() <= 64)
        for (key in listOf("droppedEvents", "droppedMetricValues", "droppedLandJobs", "nativeDroppedLandJobs", "droppedGpuSamples"))
            assertEquals("Counterexample/overflow retained in raw report: $key", 0L, capture.getLong(key))
        if (config.normalRenderAbort) {
            val native = observations.getJSONObject("normalRenderAbort")
            val restored = report.getJSONObject("normalRenderAbortRestoration")
            validateNormal(native, owner); validateNormal(restored, owner)
            assertTrue(native.getBoolean("enabled")); assertTrue(native.getLong("completedCount") > 0)
            assertFalse(restored.getBoolean("active")); assertFalse(restored.getBoolean("originalEnabled"))
            assertEquals(restored.getBoolean("originalEnabled"), restored.getBoolean("enabled"))
            result.put("nativeDefaultRestoration", "CONFIRMED_INACTIVE_ORIGINAL_FALSE")
                .put("abortedCount", native.getLong("abortedCount")).put("pinnedCompletedCount", native.getLong("pinnedCompletedCount"))
                .put("supersessionActuallyExercised", native.getLong("abortedCount") > 0)
            check(native.getLong("abortedCount") > 0) { "NOT_ACCEPTED_SUPERSESSION_NOT_EXERCISED; raw execution remains retained" }
        } else {
            val initial = metadata.getJSONObject("normalRenderAbortDiagnostic")
            assertFalse(initial.getBoolean("configured")); assertFalse(initial.getBoolean("defaultEnabled"))
            assertFalse(report.getJSONObject("normalRenderAbortRestoration").getBoolean("configured"))
            result.put("nativeDefaultRestoration", "NO_FLAG_MUTATION; unconfigured OFF has no native flag readback")
                .put("supersessionActuallyExercised", false)
        }
    }

    /** Correlate original input/revision/stamp records only; never replace a pinned old FULL with latest revision. */
    private fun validateProductionRows(input: JSONObject, result: JSONObject) {
        val rows = input.getJSONArray("rows"); check(rows.length() <= input.getInt("maxRows"))
        val start = input.getLong("liveStartMonoNs"); val end = input.getLong("liveEndMonoNs")
        check(start > 0 && end > start)
        val accepted = mutableSetOf<Pair<Long, Long>>()
        val applied = mutableSetOf<Pair<Long, Long>>()
        val completed = mutableSetOf<Long>(); val full = mutableMapOf<Long, Long>(); val sampled = mutableSetOf<Long>()
        val counts = mutableMapOf<String, Int>(); var replayCompleted = false; var maxLatenessMs = 0L
        for (index in 0 until rows.length()) {
            val row = rows.getJSONArray(index); check(row.length() == 8)
            val kind = row.getString(0); val at = row.getLong(1); val inputId = row.getLong(2)
            if (at !in start..end) continue
            counts[kind] = (counts[kind] ?: 0) + 1
            when (kind) {
                "CAMERA_ACCEPTED" -> if (inputId > 0) accepted += inputId to row.getLong(3)
                "CAMERA_APPLIED" -> if (inputId > 0) applied += inputId to row.getLong(3)
                "BUFFER_COMPLETED" -> if (row.getString(6) == "true") completed += row.getLong(3)
                "BUFFER_STAGE" -> if (row.getString(6) == "FULL") full[row.getLong(3)] = row.getLong(4)
                "TEXTURE_TAG_SAMPLE" -> {
                    check(row.getString(6).endsWith(":true")) { "Unknown live texture basis; raw counterexample retained" }
                    sampled += row.getLong(3)
                }
                "DISPOSITION" -> check(row.getString(6) !in setOf("BLOCKED_REGION", "CANCELLED_STREAM", "RUN_CAMERA_DISABLED", "INVALID_GEOMETRY"))
                "REPLAY" -> if (row.getString(6) == "COMPLETED") {
                    replayCompleted = true; maxLatenessMs = row.getLong(4); check(maxLatenessMs >= 0)
                }
            }
        }
        val adoptedCommands = accepted.intersect(applied)
        check(adoptedCommands.isNotEmpty()) { "No same-input/revision production camera acceptance and application" }
        val appliedRevisions = adoptedCommands.map { it.second }.toSet()
        val sampledFull = completed.intersect(sampled).filter { full[it] in appliedRevisions }
        check(sampledFull.isNotEmpty()) { "No exact completed FULL tag associated with an applied input revision and sampled texture" }
        for (kind in listOf("INPUT", "NATIVE_FRAME")) check((counts[kind] ?: 0) > 0)
        check(replayCompleted)
        result.put("productionRowsByKind", JSONObject(counts)).put("inputCameraAppliedPairs", adoptedCommands.size)
            .put("completedFullTagsWithMatchingInputRevisionAndTextureSample", sampledFull.size)
            .put("replayRecordedMaximumLatenessMs", maxLatenessMs)
            .put("textureObservation", "EXACT_RECORDED_STAMP_ASSOCIATION; not atomic Window content, scanout or latest-revision substitution")
            .put("replayTiming", "Existing replay scheduling and native deadline contract; recorded lateness retained without a new tolerance")
    }

    private fun validateNormal(value: JSONObject, owner: String) {
        assertEquals("no5.normal-render-abort/1", value.getString("schema")); assertEquals(owner, value.getString("runId"))
        assertTrue(value.getBoolean("available")); assertFalse(value.getBoolean("defaultEnabled"))
        assertEquals(2, value.getInt("maxCameraAborts")); assertEquals(64, value.getInt("eventLimit"))
        assertEquals("Raw overflow is not acceptance", 0L, value.getLong("droppedEvents"))
        val events = value.getJSONArray("events"); check(events.length() <= 64)
        check(value.getLong("abortedCount") >= 0 && value.getLong("completedCount") >= 0)
        check(value.getLong("pinnedCompletedCount") in 0..value.getLong("completedCount"))
        for (index in 0 until events.length()) {
            val event = events.getJSONObject(index)
            check(event.getLong("expectedEpoch") > 0 && event.getLong("latestEpoch") > 0)
            if (event.getBoolean("full")) assertEquals("FULL_COMPLETE", event.getString("reason"))
            else check(event.getString("reason") in setOf("NEW_CAMERA", "SOURCE_CHANGED", "SURFACE_CHANGED", "ENGINE_CLOSED", "APPLIED_AUTHORITY_UNBOUND"))
            check(event.getString("boundary").isNotBlank()); event.getBoolean("cameraPinned")
        }
    }
}
