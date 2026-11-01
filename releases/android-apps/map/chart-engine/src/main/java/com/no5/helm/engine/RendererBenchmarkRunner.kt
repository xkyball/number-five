package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import kotlin.math.PI
import kotlin.math.sin

internal data class BenchmarkCase(val repetition: Int, val position: Int, val profile: ChartPresentation,
    val detailed: Boolean, val motion: Boolean, val viewportReuse: RendererViewportReuseVariant? = null)

internal object BostonBenchmarkRecipe {
    const val ID = "boston-abba-absolute-camera/1"
    const val WARMUP = 5
    const val MEASURED = 30
    const val FRAMES = WARMUP + MEASURED
    val cells = RENDERER_BENCHMARK_CELLS
    val glacier = ChartPresentation("glacier")
    val s01 = ChartPresentation("lagoon-sculpt", ChartSymbolSet.R04)
    fun standardCases(profile: ChartPresentation) = listOf(false, true).mapIndexed { index, deep ->
        BenchmarkCase(1, index + 1, profile, deep, motion = false)
    }
    fun cases(repetitions: Int) = (1..repetitions).flatMap { repetition ->
        listOf(glacier, s01, s01, glacier).flatMapIndexed { position, profile ->
            listOf(false, true).flatMap { deep -> listOf(false, true).map { motion ->
                BenchmarkCase(repetition, position + 1, profile, deep, motion)
            } }
        }
    }
    fun viewportReuseCases(repetitions: Int, profile: ChartPresentation, candidate: RendererViewportReuseVariant) =
        (1..repetitions).flatMap { repetition ->
            listOf(RendererViewportReuseVariant.OFF, candidate, candidate, RendererViewportReuseVariant.OFF).flatMapIndexed { position, variant ->
                listOf(false, true).flatMap { detailed -> listOf(false, true).map { motion ->
                    BenchmarkCase(repetition, position + 1, profile, detailed, motion, variant)
                } }
            }
        }
    fun camera(motion: Boolean, frame: Int, geometry: ChartSurfaceGeometry): ChartViewport {
        require(frame in 0 until FRAMES)
        // Warmup and measurement each follow a closed path; measurement does not inherit warmup drift.
        val index = if (frame < WARMUP) frame else frame - WARMUP
        val count = if (frame < WARMUP) WARMUP else MEASURED
        val phase = 2.0 * PI * index / (count - 1)
        val wave = if (!motion || index == 0 || index == count - 1) 0.0 else sin(phase)
        return ChartViewport(42.36 + .002 * wave, -71.04 + .003 * wave,
            .15 * (1.0 + .15 * wave), .15 * wave, geometry.frameWidth, geometry.frameHeight)
    }
    fun cameraJson(view: ChartViewport) = buildJsonObject {
        put("latitude", view.latitude); put("longitude", view.longitude)
        put("metresToPixels", view.metresToPixels); put("rotationRadians", view.rotationRadians)
        put("width", view.width); put("height", view.height)
    }
    fun caseJson(case: BenchmarkCase, standard: Boolean = false) = buildJsonObject {
        put("repetition", case.repetition); put(if (standard) "groupPosition" else "abbaPosition", case.position)
        put("style", case.profile.styleId); put("symbols", case.profile.symbols.id)
        put("relief", case.profile.landRelief); put("detailed", case.detailed)
        put("scenario", if (case.motion) "motion" else "stationary")
        case.viewportReuse?.let { put("viewportReuse", it.name) }
    }
}

/** All native calls stay on the owning worker. IO/preparation are outside measured frames. */
internal interface RendererBenchmarkBackend {
    suspend fun metadata(): JsonObject
    fun checkReady()
    suspend fun apply(profile: ChartPresentation)
    fun configureViewportReuse(variant: RendererViewportReuseVariant) { error("Viewport reuse diagnostics unavailable") }
    fun begin(case: BenchmarkCase, viewport: ChartViewport)
    fun frame(viewport: ChartViewport)
    fun end(reason: String): JsonObject?
    suspend fun restore(): Boolean
    fun progress(completed: Int, total: Int, frame: Int, message: String)
}

internal class BenchmarkCancelled : IllegalStateException("Messung abgebrochen")

internal class RendererBenchmarkRunner(private val backend: RendererBenchmarkBackend,
    private val cancelled: () -> Boolean) {
    suspend fun run(runId: String, config: RendererBenchmarkConfig, geometry: ChartSurfaceGeometry,
        standardProfile: ChartPresentation? = null): JsonObject {
        val reuse = config.viewportReuseCandidate
        val standard = standardProfile != null && reuse == null
        val cases = if (reuse != null) BostonBenchmarkRecipe.viewportReuseCases(config.repetitions,
            checkNotNull(standardProfile) { "Viewport comparison requires the frozen current presentation" }, reuse)
            else standardProfile?.let(BostonBenchmarkRecipe::standardCases) ?: BostonBenchmarkRecipe.cases(config.repetitions)
        val groups = mutableListOf<JsonElement>()
        var metadata = buildJsonObject {}
        var outcome = "complete"
        var error = ""
        var restorationError = ""
        var restored = false
        var activeCase: BenchmarkCase? = null
        fun ready() { if (cancelled()) throw BenchmarkCancelled(); backend.checkReady() }
        fun append(case: BenchmarkCase, capture: JsonObject) {
            // Keep the original standard report's direct capture groups for existing consumers.
            groups += if (standard) capture else buildJsonObject {
                put("case", BostonBenchmarkRecipe.caseJson(case)); put("capture", capture)
            }
        }
        try {
            ready()
            metadata = backend.metadata()
            ready()
            for ((index, case) in cases.withIndex()) {
                ready()
                backend.progress(index, cases.size, 0, "${case.viewportReuse?.name?.let { "$it · " }.orEmpty()}${case.profile.styleId} · ${if (case.detailed) "deep" else "normal"} · ${if (case.motion) "motion" else "stationary"}")
                case.viewportReuse?.let(backend::configureViewportReuse)
                backend.apply(case.profile)
                ready()
                activeCase = case
                backend.begin(case, BostonBenchmarkRecipe.camera(case.motion, 0, geometry))
                repeat(BostonBenchmarkRecipe.FRAMES) { frame ->
                    ready()
                    backend.frame(BostonBenchmarkRecipe.camera(case.motion, frame, geometry))
                    backend.progress(index, cases.size, frame + 1, if (frame < BostonBenchmarkRecipe.WARMUP) "Warmup" else "Messung")
                    yield()
                }
                val capture = checkNotNull(backend.end("frame limit")) { "Capture report missing" }
                append(case, capture)
                activeCase = null
                check(capture.getValue("frames").jsonPrimitive.int == BostonBenchmarkRecipe.FRAMES &&
                    capture.getValue("stopReason").jsonPrimitive.content == "frame limit") { "Capture interrupted or timed out" }
            }
            backend.progress(cases.size, cases.size, 0, "Restoring previous state")
        } catch (failure: Exception) {
            outcome = if (failure is BenchmarkCancelled || cancelled()) "cancelled" else "failed"
            error = failure.message ?: failure.javaClass.simpleName
        } finally {
            withContext(NonCancellable) {
                try { backend.end(if (outcome == "complete") "complete" else error)?.let { capture -> activeCase?.let { append(it, capture) } } }
                catch (failure: Exception) { if (error.isEmpty()) error = failure.message ?: "Partial report unavailable"; outcome = "failed" }
                try { restored = backend.restore() }
                catch (failure: Exception) { restorationError = failure.message ?: "Restoration failed" }
                if (!restored) { if (restorationError.isEmpty()) restorationError = "Engine state unavailable for restoration"; outcome = "failed" }
            }
        }
        return buildJsonObject {
            put("schema", if (reuse != null) "no5-renderer-viewport-reuse-benchmark/1" else if (standard) "no5-renderer-ab/1" else "no5-renderer-style-benchmark/1"); put("runId", runId)
            put("outcome", outcome); put("error", error); put("restored", restored); put("restorationError", restorationError)
            put("metadata", metadata); put("recipe", buildJsonObject {
                put("id", if (reuse != null) "boston-viewport-reuse-abba/1" else if (standard) "boston-standard-normal-deep/1" else BostonBenchmarkRecipe.ID)
                put("repetitions", if (standard) 1 else config.repetitions)
                put("orderPerRepetition", if (standard) "normal,deep" else "ABBA")
                if (reuse != null) { put("A", "OFF"); put("B", reuse.name); put("presentationPolicy", "same frozen current presentation in every arm")
                    put("automaticActivation", false); put("activationGate", "Separate exact-frame/query parity and three paired physical Lumina runs: latency gain >=10% and above noise, other cases <=5% regression. Native frame times alone do not satisfy latency acceptance.") }
                else if (!standard) { put("A", "glacier/default/no relief"); put("B", "lagoon-sculpt/r04/no relief") }
                put("warmupFramesPerGroup", BostonBenchmarkRecipe.WARMUP); put("measuredFramesPerGroup", BostonBenchmarkRecipe.MEASURED)
                put("totalGroups", cases.size); put("totalSubmittedFrames", cases.size * BostonBenchmarkRecipe.FRAMES)
                put("timeoutSecondsPerGroup", 60); put("overlayPolicy", "empty during all cases; previous overlays restored")
                put("stationaryCameraSteps", JsonArray((0 until BostonBenchmarkRecipe.FRAMES).map { BostonBenchmarkRecipe.cameraJson(BostonBenchmarkRecipe.camera(false, it, geometry)) }))
                if (!standard) put("motionCameraSteps", JsonArray((0 until BostonBenchmarkRecipe.FRAMES).map { BostonBenchmarkRecipe.cameraJson(BostonBenchmarkRecipe.camera(true, it, geometry)) }))
                put("caseOrder", JsonArray(cases.map { BostonBenchmarkRecipe.caseJson(it, standard) }))
            })
            put("groups", JsonArray(groups))
            if (standard) {
                put("profile", "Matched fixed Boston view; current presentation and safety settings; normal sampling then deep instrumentation")
                put("warmupFramesPerGroup", BostonBenchmarkRecipe.WARMUP); put("measuredFramesPerGroup", BostonBenchmarkRecipe.MEASURED)
                fun median(index: Int) = groups.getOrNull(index)?.jsonObject?.get("summary")?.jsonObject
                    ?.get("workerFrameWallMs")?.jsonObject?.get("median")?.jsonPrimitive?.doubleOrNull
                val normal = median(0); val deep = median(1)
                put("observedMedianWallRatioDeepToNormal", if (outcome == "complete" && normal != null && normal > 0 &&
                    deep != null && normal.isFinite() && deep.isFinite()) JsonPrimitive(deep / normal) else JsonNull)
            }
            val limitations = buildJsonArray {
                if (!standard && reuse == null) add("Full presentation profiles are compared: Glacier does not support R04. This is not a palette-only comparison.")
                if (reuse != null) add("Only per-run reuse families differ. Exact full-frame/query parity and actual presented latency are separate required measurements; this report never enables a production optimization.")
                add("Native frame count is not capture FPS, display FPS or end-to-end interaction latency.")
                add("Normal wall/thread CPU samples are primary; deep instrumentation adds overhead and provides attribution.")
                add("Native/worker/UI/GPU scopes overlap. Never add nested scopes or infer GPU duration from wall minus CPU.")
                add("GPU availability and valid sample count are reported per case. Asynchronous pending GPU tail results are not awaited.")
                add(if (standard) "Sequential normal/deep sampling is affected by thermal/driver load; repeat it. This is instrumentation overhead, not a style comparison."
                    else "ABBA reduces ordering bias; thermal/driver load still affects results. No sleep-based or wall-clock-paced motion.")
                add("Only the fixed public Boston camera is exported; the previous user camera and connection data are not exported.")
            }
            put("limitations", if (standard) JsonPrimitive(limitations.joinToString(" ") { it.jsonPrimitive.content }) else limitations)
        }
    }
}
