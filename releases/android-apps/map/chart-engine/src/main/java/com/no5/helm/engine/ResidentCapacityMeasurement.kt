package com.no5.helm.engine

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import com.no5.helm.chart.ChartViewport
import com.no5.helm.chart.MAX_CHART_HEIGHT
import com.no5.helm.chart.MAX_CHART_WIDTH
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.*

/** Only actual work calls enter this buffer. Polling observations are deliberately separate. */
internal class ResidentCapacitySoftwareEvents(private val runId: String, private val limit: Int = 16_384) {
    private val events = mutableListOf<JsonObject>()
    private var overflow = false
    private var invalid = 0
    init { require(runId.isNotBlank() && limit in 1..16_384) }

    @Synchronized fun recordDuration(kind: String, startNs: Long, endNs: Long,
        authority: JsonObject, details: JsonObject = buildJsonObject {}) {
        val duration = runCatching { Math.subtractExact(endNs, startNs) }.getOrNull()
        if (duration == null || duration < 0) { invalid++; return }
        record(kind, startNs, endNs, duration, authority, details)
    }

    @Synchronized fun recordInstant(kind: String, atNs: Long,
        authority: JsonObject, details: JsonObject = buildJsonObject {}) =
        record(kind, atNs, null, null, authority, details)

    private fun record(kind: String, startNs: Long, endNs: Long?, durationNs: Long?,
        authority: JsonObject, details: JsonObject) {
        if (kind.isBlank() || !validAuthority(authority)) { invalid++; return }
        if (events.size == limit) { overflow = true; return }
        events += buildJsonObject {
            put("eventId", "$runId:${events.size + 1}"); put("kind", kind)
            put("clock", "MONOTONIC-System.nanoTime"); put("startNs", startNs)
            put("endNs", endNs?.let(::JsonPrimitive) ?: JsonNull)
            put("durationNs", durationNs?.let(::JsonPrimitive) ?: JsonNull)
            put("authority", frozen(authority)); put("details", frozen(details))
        }
    }

    @Synchronized fun snapshot(): JsonObject = buildJsonObject {
        put("schema", "no5-resident-software-events/1"); put("runId", runId)
        put("clock", "MONOTONIC-System.nanoTime"); put("limit", limit)
        put("eventCount", events.size); put("overflow", overflow); put("invalidEvents", invalid)
        put("valid", !overflow && invalid == 0)
        put("durationEndpoint", if (!overflow && invalid == 0 && events.any { it["durationNs"] != JsonNull })
            "SOFTWARE_WORK_CALLS" else "NOT_MEASURED")
        put("events", JsonArray(events.toList()))
        put("physicalInputToDisplay", "NOT_MEASURED")
        put("visibleDistinctFramesPerSecond", "NOT_MEASURED")
    }

    private fun validAuthority(a: JsonObject): Boolean {
        fun number(key: String) = (a[key] as? JsonPrimitive)?.longOrNull
        if ((a["catalogGeneration"] as? JsonPrimitive)?.content?.matches(Regex("[a-f0-9]{64}")) != true ||
            listOf("sourceGeneration", "contentGeneration", "surfaceGeneration", "cameraRevision").any { (number(it) ?: -1) < 0 }) return false
        val b = a["budget"] as? JsonObject ?: return false
        val cells = (b["cells"] as? JsonPrimitive)?.intOrNull ?: return false
        val bytes = (b["bytes"] as? JsonPrimitive)?.longOrNull ?: return false
        val loading = (b["loadingBytes"] as? JsonPrimitive)?.longOrNull ?: return false
        return cells in 1..100 && loading > 0 && bytes >= loading
    }
}

private fun frozen(value: JsonObject): JsonObject = Json.parseToJsonElement(value.toString()).jsonObject

internal data class ResidentCapacityPoseStep(val index: Int, val windowId: String,
    val offsetMs: Long, val viewport: ChartViewport)

/** A deterministic camera workload, not a physical touch or fling replay. The caller owns scheduling. */
internal class ResidentCapacityPoseRecipe private constructor(
    steps: List<ResidentCapacityPoseStep>, val durationMs: Long, val frozenAuthority: JsonObject,
    val id: String = "CURRENT_VIEWPORT_V1",
) {
    val steps: List<ResidentCapacityPoseStep> = Collections.unmodifiableList(steps.toList())
    private val planned = JsonArray(steps.map { stepJson(it) })
    val sha256: String = MessageDigest.getInstance("SHA-256")
        .digest(buildJsonObject { put("id", id); put("steps", planned) }.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private val results = mutableListOf<JsonObject>()
    private var phase = "prepared"
    private var error = ""
    private var startedNs: Long? = null

    /** Run on Main/an independent scheduler, never on the renderer's serial native owner. */
    suspend fun run(nowNs: () -> Long = System::nanoTime,
        pauseMs: suspend (Long) -> Unit = { delay(it) },
        propose: suspend (ResidentCapacityPoseStep) -> JsonObject): JsonObject {
        synchronized(this) { check(phase == "prepared"); phase = "running" }
        var previousClock: Long? = null
        fun clock() = nowNs().also { value ->
            check(previousClock == null || value >= previousClock!!) { "Recipe MONOTONIC clock moved backwards" }
            previousClock = value
        }
        try {
            val start = clock()
            synchronized(this) { startedNs = start }
            for (step in steps) {
                val target = Math.addExact(start, Math.multiplyExact(step.offsetMs, 1_000_000L))
                while (true) {
                    val remaining = Math.subtractExact(target, clock())
                    if (remaining <= 0) break
                    pauseMs(remaining / 1_000_000 + if (remaining % 1_000_000 == 0L) 0 else 1)
                }
                val before = clock()
                val result = frozen(propose(step))
                val after = clock()
                synchronized(this) { results += buildJsonObject {
                    put("stepIndex", step.index); put("windowId", step.windowId); put("plannedOffsetMs", step.offsetMs)
                    put("proposedAtNs", before); put("proposalReturnedAtNs", after)
                    put("actualOffsetNs", Math.subtractExact(before, start)); put("latenessNs", Math.subtractExact(before, target))
                    put("wantedViewport", BostonBenchmarkRecipe.cameraJson(step.viewport)); put("result", result)
                    put("sameRealizedPose", sameViewport(result["realizedViewport"], step.viewport) &&
                        (result["accepted"] as? JsonPrimitive)?.booleanOrNull == true)
                    put("sameAuthority", sameAuthority(result["authority"]))
                } }
            }
            synchronized(this) { phase = "completed" }
        } catch (cancel: CancellationException) {
            synchronized(this) { phase = "cancelled"; error = cancel.message ?: "Recipe cancelled" }
            throw cancel
        } catch (failure: Exception) {
            synchronized(this) { phase = "failed"; error = failure.message ?: failure.javaClass.simpleName }
        }
        return snapshot()
    }

    @Synchronized fun snapshot(): JsonObject = buildJsonObject {
        put("schema", "no5.resident-camera-recipe/1"); put("id", id); put("sha256", sha256)
        put("durationMs", durationMs); put("clock", "MONOTONIC-System.nanoTime")
        put("inputKind", "CAMERA_PROPOSALS_NOT_TOUCH_REPLAY"); put("cacheWindowMeaning", "second pass is actual warm revisit, not OSCold/guaranteed-hot")
        put("frozenAuthority", frozenAuthority); put("steps", planned)
        put("startedNs", startedNs?.let(::JsonPrimitive) ?: JsonNull)
        put("phase", phase); put("error", error); put("results", JsonArray(results.toList()))
        put("comparableRealizedPath", phase == "completed" && results.size == steps.size &&
            results.all { it["sameRealizedPose"]?.jsonPrimitive?.booleanOrNull == true &&
                it["sameAuthority"]?.jsonPrimitive?.booleanOrNull == true })
        put("physicalInputToDisplay", "NOT_MEASURED")
    }

    private fun sameAuthority(value: JsonElement?): Boolean {
        val actual = value as? JsonObject ?: return false
        return listOf("catalogGeneration", "sourceGeneration", "surfaceGeneration", "budget").all { key ->
            frozenAuthority[key] != null && frozenAuthority[key] != JsonNull && actual[key] == frozenAuthority[key]
        }
    }

    companion object {
        /** Six fixed real-chart cameras, expanded once. No catalog/budget or style replacement. */
        fun currentViewReuse(anchor: ChartViewport, durationMs: Long, frozenAuthority: JsonObject): ResidentCapacityPoseRecipe {
            require(durationMs in 10_000..90_000 && validViewport(anchor))
            val center = com.no5.helm.chart.ScreenPoint(anchor.width / 2.0, anchor.height / 2.0)
            val cameras = listOf(anchor,
                residentTransformedCamera(anchor, center, com.no5.helm.chart.ScreenPoint(anchor.width * .15, anchor.height * .1)),
                residentTransformedCamera(anchor, center, zoom = 1.25),
                residentTransformedCamera(anchor, center, zoom = .8),
                residentTransformedCamera(anchor, center, rotation = .2), anchor)
            require(cameras.all(::validViewport)) { "Current-view recipe exceeds supported geography" }
            val steps = cameras.mapIndexed { index, view ->
                ResidentCapacityPoseStep(index, if(index==0)"initial-existing-cache" else if(index==5)"warm-return" else "pan-zoom-rotation",
                    index * (durationMs - 1_000) / 5, view)
            }
            return ResidentCapacityPoseRecipe(steps,durationMs,frozen(frozenAuthority),"CURRENT_VIEW_REUSE")
        }
        /** Relative path is expanded once from the caller's frozen real camera, with fixed dimensions. */
        fun currentViewportV1(anchor: ChartViewport, durationMs: Long,
            frozenAuthority: JsonObject = buildJsonObject {}): ResidentCapacityPoseRecipe {
            require(durationMs in 10_000..90_000)
            require(validViewport(anchor)) { "Recipe anchor is invalid" }
            val passMs = min(38_000L, (durationMs - 1_000) / 2)
            // Seconds in the canonical 38-s pass, east/north screen fractions, zoom, relative rotation.
            val knots = listOf(
                doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0), doubleArrayOf(4.0, 0.0, 0.0, 1.0, 0.0),
                doubleArrayOf(7.0, 0.0, 0.0, 2.0, 0.0), doubleArrayOf(10.0, 0.0, 0.0, .75, 0.0),
                doubleArrayOf(16.0, .45, .1, 1.0, 0.0), doubleArrayOf(20.0, .45, .1, 1.0, .6),
                doubleArrayOf(26.0, 0.0, 0.0, 1.0, 0.0), doubleArrayOf(30.0, .65, 0.0, 1.0, 0.0),
                doubleArrayOf(32.0, -.25, 0.0, 1.0, 0.0), doubleArrayOf(34.0, 0.0, 0.0, 1.0, 0.0),
                doubleArrayOf(38.0, 0.0, 0.0, 1.0, 0.0))
            val radius = 6378137.0 * .9996 // Same Mercator radius as ChartCameraAffine.
            val north0 = radius * ln(tan(PI / 4 + Math.toRadians(anchor.latitude) / 2))
            fun pose(k: DoubleArray): ChartViewport {
                if (k[1] == 0.0 && k[2] == 0.0 && k[3] == 1.0 && k[4] == 0.0) return anchor
                val east = k[1] * anchor.width / anchor.metresToPixels
                val north = north0 + k[2] * anchor.height / anchor.metresToPixels
                val lon = anchor.longitude + Math.toDegrees(east / radius)
                val view = anchor.copy(latitude = Math.toDegrees(2 * atan(exp(north / radius)) - PI / 2),
                    longitude = ((lon + 180) % 360 + 360) % 360 - 180,
                    metresToPixels = (anchor.metresToPixels * k[3]).coerceIn(.00001, 10.0),
                    rotationRadians = anchor.rotationRadians + k[4])
                require(validViewport(view)) { "Recipe path leaves the supported camera extent" }
                return view
            }
            val path = mutableListOf<Pair<Long, ChartViewport>>()
            path += 0L to anchor
            for (segment in 1 until knots.size) {
                val a = knots[segment - 1]; val b = knots[segment]
                val segments = max(1, ceil((b[0] - a[0]) * passMs / 38.0 / 500).toInt())
                for (sample in 1..segments) {
                    val fraction = sample.toDouble() / segments
                    val point = DoubleArray(5) { a[it] + (b[it] - a[it]) * fraction }
                    // A smooth decelerating camera path is still not a gesture-recognition test.
                    if (segment == 7) point[1] = a[1] + (b[1] - a[1]) * (1 - (1 - fraction).pow(3))
                    val offset = (point[0] / 38 * passMs).roundToLong().coerceAtMost(passMs - 1)
                    path += offset to if (segment == knots.lastIndex && sample == segments) anchor else pose(point)
                }
            }
            val steps = buildList {
                repeat(2) { pass -> path.forEach { (offset, view) ->
                    add(ResidentCapacityPoseStep(size, if (pass == 0) "geometry-initial" else "warm-revisit", offset + pass * passMs, view))
                } }
                add(ResidentCapacityPoseStep(size, "final-hold", durationMs - 1_000, anchor))
            }
            require(steps.size <= 256 && steps.zipWithNext().all { it.first.offsetMs < it.second.offsetMs })
            return ResidentCapacityPoseRecipe(steps.toList(), durationMs, frozen(frozenAuthority))
        }

        private fun validViewport(v: ChartViewport) = v.latitude.isFinite() && v.latitude in -85.0..85.0 &&
            v.longitude.isFinite() && v.longitude in -180.0..180.0 && v.metresToPixels.isFinite() &&
            v.metresToPixels in .00001..10.0 && v.rotationRadians.isFinite() &&
            v.width in 1..MAX_CHART_WIDTH && v.height in 1..MAX_CHART_HEIGHT

        private fun stepJson(step: ResidentCapacityPoseStep) = buildJsonObject {
            put("index", step.index); put("windowId", step.windowId); put("offsetMs", step.offsetMs)
            put("viewport", BostonBenchmarkRecipe.cameraJson(step.viewport))
        }

        private fun sameViewport(value: JsonElement?, expected: ChartViewport): Boolean {
            val obj = value as? JsonObject ?: return false
            val wanted = BostonBenchmarkRecipe.cameraJson(expected)
            return wanted.all { (key, number) -> (obj[key] as? JsonPrimitive)?.doubleOrNull == number.jsonPrimitive.doubleOrNull }
        }
    }
}

/** Adapter owns the existing engine lease and private journal. This runner owns neither charts nor IO. */
internal interface ResidentCapacityBackend {
    suspend fun prepare(): JsonObject
    fun checkReady()
    suspend fun observe(): JsonObject
    fun end(): JsonObject = buildJsonObject {}
    suspend fun restore(): Boolean
}

/** Short owner-bound polling window. Samples cannot establish physical presentation or input latency. */
internal class ResidentCapacityMeasurement(
    private val backend: ResidentCapacityBackend,
    private val nowMs: () -> Long,
    private val pause: suspend () -> Unit = { delay(SAMPLE_INTERVAL_MS) },
) {
    suspend fun run(durationMs: Long): JsonObject {
        require(durationMs in 5_000..90_000)
        var metadata = buildJsonObject {}
        var events = buildJsonObject {}
        val samples = mutableListOf<JsonObject>()
        var error = ""
        var restorationError = ""
        var restored = false
        var started: Long? = null
        var lastClock: Long? = null
        fun clock(): Long = nowMs().also { value ->
            check(value >= 0 && (lastClock == null || value >= lastClock!!)) { "Device clock moved backwards" }
            lastClock = value
        }
        try {
            metadata = backend.prepare()
            backend.checkReady()
            val start = clock()
            started = start
            check(start <= Long.MAX_VALUE - durationMs) { "Device clock overflow" }
            val until = start + durationMs
            var nextSample = start
            while (clock() < until) {
                backend.checkReady()
                val before = clock()
                if (before >= nextSample) {
                    check(samples.size < MAX_SAMPLES) { "Capacity sample limit exceeded" }
                    val observation = backend.observe()
                    val after = clock()
                    samples += buildJsonObject {
                        put("sampleStartedElapsedRealtimeMs", before)
                        put("sampleFinishedElapsedRealtimeMs", after)
                        put("state", observation)
                    }
                    check(after <= Long.MAX_VALUE - SAMPLE_INTERVAL_MS) { "Device clock overflow" }
                    nextSample = after + SAMPLE_INTERVAL_MS
                }
                pause()
            }
            backend.checkReady()
        } catch (failure: Exception) {
            error = failure.message ?: failure.javaClass.simpleName
        } finally {
            withContext(NonCancellable) {
                try { events = backend.end() }
                catch (failure: Exception) { if (error.isEmpty()) error = failure.message ?: failure.javaClass.simpleName }
                if(events["requiredInputPathValid"]?.jsonPrimitive?.booleanOrNull==false && error.isEmpty())
                    error="Required current-view path was not accepted completely under the captured authority"
                try {
                    restored = backend.restore()
                    if (!restored) restorationError = "Restoration not confirmed"
                } catch (failure: Exception) {
                    restorationError = failure.message ?: "Restoration failed"
                }
            }
        }
        return buildJsonObject {
            put("schema", "no5-resident-capacity/1")
            put("metadata", metadata)
            put("clock", "device-elapsedRealtime-BOOTTIME")
            put("requestedDurationMs", durationMs)
            put("startedElapsedRealtimeMs", started?.let(::JsonPrimitive) ?: JsonNull)
            put("lastObservedElapsedRealtimeMs", lastClock?.let(::JsonPrimitive) ?: JsonNull)
            put("sampleIntervalMs", SAMPLE_INTERVAL_MS)
            put("sampleCount", samples.size)
            put("samples", JsonArray(samples))
            put("events", events)
            val software = events["softwareEvents"] as? JsonObject
            val durationEndpoint = if (software != null && (software["schema"] as? JsonPrimitive)?.contentOrNull == "no5-resident-software-events/1" &&
                (software["valid"] as? JsonPrimitive)?.booleanOrNull == true &&
                (metadata["runId"] as? JsonPrimitive)?.contentOrNull != null && software["runId"] == metadata["runId"])
                (software["durationEndpoint"] as? JsonPrimitive)?.contentOrNull ?: "NOT_MEASURED" else "NOT_MEASURED"
            put("softwareDurationEndpoint", durationEndpoint)
            put("pollingDurationQuantiles", "NOT_MEASURED")
            put("observationKind", "owner-bound-polling")
            put("physicalInputToDisplay", "notMeasured")
            put("visibleDistinctFramesPerSecond", "notMeasured")
            put("outcome", when {
                !restored || restorationError.isNotEmpty() -> "failed"
                error.isNotEmpty() -> "aborted"
                else -> "completed"
            })
            put("error", error)
            put("restored", restored)
            put("restorationError", restorationError)
        }
    }

    companion object {
        const val SAMPLE_INTERVAL_MS = 100L
        const val MAX_SAMPLES = 1_024
    }
}
