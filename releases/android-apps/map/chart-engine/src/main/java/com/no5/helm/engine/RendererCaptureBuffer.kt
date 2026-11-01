package com.no5.helm.engine

import com.no5.helm.chart.RendererCaptureConfig
import kotlinx.serialization.json.*
import kotlin.math.ceil

/** Confined to No5Chart. Primitive storage is bounded by frames, metric keys and events. */
internal class RendererCaptureBuffer(val config: RendererCaptureConfig, val startedNanos: Long, val warmupFrames: Int = 0) {
    val ui = RendererUiCaptureBuffer(config.maxFrames, startedNanos)
    private val columns = linkedMapOf<String, DoubleArray>()
    private val slow = mutableListOf<Pair<Double, JsonObject>>()
    private val events = mutableListOf<JsonObject>()
    private val nativeFrameOrder = linkedMapOf<Long, Int>()
    private val gpu = linkedMapOf<Long, JsonObject>()
    private var lastNative = buildJsonObject {}
    var droppedGpuSamples = 0; private set
    private val landJobs = linkedMapOf<Long, JsonObject>()
    private val landColumns = linkedMapOf<String, DoubleArray>()
    var droppedLandJobs = 0; private set
    var nativeDroppedLandJobs = 0; private set
    private val owner = Thread.currentThread()
    private fun checkOwner() = check(Thread.currentThread() === owner) { "Capture buffer belongs to No5Chart" }
    var frames = 0; private set
    var droppedEvents = 0; private set
    var droppedMetrics = 0; private set
    fun expired(now: Long) = now - startedNanos >= config.durationSeconds * 1_000_000_000L
    val full get() = frames >= config.maxFrames
    fun updateLast(values: Map<String, Double>) {
        checkOwner()
        if (frames == 0) return
        values.forEach { (key, value) ->
            if (value.isFinite() && value >= 0) {
                val column = columns[key] ?: if (columns.size < 288)
                    DoubleArray(config.maxFrames) { Double.NaN }.also { columns[key] = it }
                else { droppedMetrics++; return@forEach }
                column[frames - 1] = value
            }
        }
    }
    fun event(name: String, wallMs: Double, cpuMs: Double) {
        checkOwner()
        if (events.size == 64) { droppedEvents++; return }
        events += buildJsonObject { put("phase", name); put("wallMs", wallMs); if (cpuMs >= 0) put("cpuMs", cpuMs) }
    }
    fun record(values: Map<String, Double>, native: JsonObject) {
        checkOwner()
        check(!full)
        lastNative = native
        fun add(key: String, value: Double) {
            if (!value.isFinite() || value < 0) return
            val column = columns[key] ?: if (columns.size < 288)
                DoubleArray(config.maxFrames) { Double.NaN }.also { columns[key] = it }
            else { droppedMetrics++; return }
            column[frames] = value
        }
        fun flatten(prefix: String, value: JsonElement) {
            when (value) {
                is JsonObject -> value.forEach { (key, child) -> flatten(if (prefix.isEmpty()) key else "$prefix.$key", child) }
                is JsonPrimitive -> value.doubleOrNull?.let { add("native.$prefix", it) }
                else -> Unit
            }
        }
        (native["landWorker"] as? JsonObject)?.let { worker ->
            nativeDroppedLandJobs = maxOf(nativeDroppedLandJobs, (worker["dropped"] as? JsonPrimitive)?.intOrNull ?: 0)
            (worker["jobs"] as? JsonArray)?.forEach { element ->
                val job = element as? JsonObject ?: return@forEach
                val id = (job["jobId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                if (landJobs.containsKey(id)) return@forEach
                if (landJobs.size >= 64) { droppedLandJobs++; return@forEach }
                val row = landJobs.size
                landJobs[id] = buildJsonObject {
                    job.forEach { (key, value) -> put(key, value) }
                    put("receivedSequence", frames); put("receivedDuringWarmup", frames < warmupFrames)
                }
                val outcome = (job["outcome"] as? JsonPrimitive)?.content?.takeIf { it in listOf("complete", "cancelled", "failed") } ?: "unknown"
                fun phase(name: String, data: JsonElement?) {
                    val metric = data as? JsonObject ?: return
                    if (((metric["calls"] as? JsonPrimitive)?.intOrNull ?: 0) <= 0) return
                    for (unit in listOf("wallMs", "cpuMs")) {
                        val value = (metric[unit] as? JsonPrimitive)?.doubleOrNull ?: continue
                        if (!value.isFinite() || value < 0) continue
                        val key = "$outcome.$name.$unit"
                        val column = landColumns[key] ?: if (landColumns.size < 128)
                            DoubleArray(64) { Double.NaN }.also { landColumns[key] = it } else continue
                        column[row] = value
                    }
                }
                phase("geometryHarvest", job["geometryHarvest"]); phase("futureWait", job["futureWait"])
                (job["phases"] as? JsonObject)?.forEach { (name, data) -> phase(name, data) }
            }
        }
        native["frame"]?.jsonPrimitive?.longOrNull?.let { nativeFrameOrder[it] = frames }
        (native["gpuSamples"] as? JsonArray)?.forEach { element ->
            val sample = element as? JsonObject ?: return@forEach
            val frame = (sample["frame"] as? JsonPrimitive)?.longOrNull ?: return@forEach
            val duration = (sample["durationMs"] as? JsonPrimitive)?.doubleOrNull ?: return@forEach
            val sourceSequence = nativeFrameOrder[frame]
            if (sourceSequence == null) { droppedGpuSamples++; return@forEach }
            if (sourceSequence < warmupFrames || gpu.containsKey(frame)) return@forEach
            if (!duration.isFinite() || duration < 0) return@forEach
            if (gpu.size >= config.maxFrames) { droppedGpuSamples++; return@forEach }
            gpu[frame] = buildJsonObject {
                put("frame", frame); put("sourceSequence", sourceSequence)
                put("receivedSequence", frames); put("durationMs", duration)
            }
        }
        values.forEach { (key, value) -> add(key, value) }
        flatten("", native)
        val duration = values["workerFrameWallMs"] ?: 0.0
        if (frames >= warmupFrames && duration >= config.slowFrameMs) {
            val detail = buildJsonObject {
                put("sequence", frames); put("workerFrameWallMs", duration)
                put("worker", buildJsonObject { values.forEach { (key, value) -> if (value.isFinite() && value >= 0) put(key, value) } })
                put("native", native)
            }
            slow += duration to detail
            slow.sortByDescending { it.first }; if (slow.size > 5) slow.removeAt(5)
        }
        frames++
    }
    fun report(metadata: JsonObject, reason: String, elapsedMs: Double): JsonObject {
        checkOwner()
        return buildJsonObject {
        put("schema", "no5-renderer-capture/1")
        put("ui", ui.finishReport())
        put("metadata", metadata); put("stopReason", reason); put("elapsedMs", elapsedMs)
        put("frames", frames); put("warmupFrames", warmupFrames); put("maxFrames", config.maxFrames); put("detailed", config.detailed)
        put("droppedEvents", droppedEvents); put("droppedMetricValues", droppedMetrics)
        put("droppedLandJobs", droppedLandJobs); put("nativeDroppedLandJobs", nativeDroppedLandJobs)
        put("landJobs", JsonArray(landJobs.values.toList()))
        put("landSummary", buildJsonObject {
            landColumns.forEach { (name, data) ->
                val sorted = data.take(landJobs.size).filter { it.isFinite() }.sorted()
                if (sorted.isNotEmpty()) put(name, buildJsonObject {
                    fun percentile(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceAtLeast(0)]
                    put("samples", sorted.size); put("min", sorted.first()); put("max", sorted.last()); put("mean", sorted.average())
                    put("median", if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2)
                    put("p95", percentile(.95)); put("p99", percentile(.99))
                })
            }
        })
        put("droppedGpuSamples", droppedGpuSamples)
        put("gpuSamples", JsonArray(gpu.values.toList()))
        put("gpuAvailability", buildJsonObject {
            val supported = lastNative["gpuSupported"]?.jsonPrimitive?.booleanOrNull == true
            put("supported", supported)
            put("state", if (!config.detailed) "not sampled" else if (!supported || gpu.isEmpty()) "unavailable" else "available")
            put("reason", if (!config.detailed) "Normal sampling disables GPU queries" else
                lastNative["gpuMeasurement"]?.jsonPrimitive?.contentOrNull ?: "GPU query capability unavailable")
            put("validMeasuredSamples", gpu.size)
        })
        val gpuSorted = gpu.values.map { it.getValue("durationMs").jsonPrimitive.double }.sorted()
        put("gpuSummary", if (gpuSorted.isEmpty()) JsonNull else buildJsonObject {
            fun percentile(p: Double) = gpuSorted[(ceil(p * gpuSorted.size).toInt() - 1).coerceAtLeast(0)]
            put("samples", gpuSorted.size); put("min", gpuSorted.first()); put("max", gpuSorted.last())
            put("mean", gpuSorted.average())
            put("median", if (gpuSorted.size % 2 == 1) gpuSorted[gpuSorted.size / 2] else (gpuSorted[gpuSorted.size / 2 - 1] + gpuSorted[gpuSorted.size / 2]) / 2)
            put("p95", percentile(.95)); put("p99", percentile(.99))
        })
        put("detailsNonAdditive", true)
        put("workerPhasesNonAdditive", true)
        put("summary", buildJsonObject {
            columns.forEach { (name, data) ->
                val sorted = data.take(frames).drop(warmupFrames).filter { it.isFinite() }.sorted()
                if (sorted.isNotEmpty()) put(name, buildJsonObject {
                    fun percentile(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceAtLeast(0)]
                    put("samples", sorted.size); put("min", sorted.first()); put("max", sorted.last())
                    put("mean", sorted.average()); put("median", if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2)
                    put("p95", percentile(.95)); put("p99", percentile(.99))
                })
            }
        })
        put("slowFrames", JsonArray(slow.map { it.second }))
        put("events", JsonArray(events))
        put("limitations", buildJsonArray {
            add("Wall and thread CPU are not GPU duration; wall minus CPU is not GPU time.")
            add("Nested native details overlap; never sum them with stages or parent details.")
            add("Queue timing starts at engine ingress, not hardware touch delivery or physical display scanout.")
            add("Between-frame gap contains idle time and other worker jobs; it is not all renderer work.")
            add("Deep per-call thread CPU instrumentation has overhead; use the fixed Boston A/B report.")
            add("App-window and chart-node UI callbacks are independent observations; physical display latency, full Compose recomposition and scheduling attribution remain unavailable.")
            add("Land jobs are independent worker observations; geometry/future wait belong to the chart thread. Worker total overlaps its subphases and must not be added to native frame wall.")
            add("Land summaries distinguish complete/cancelled/failed outcomes. Unexecuted phases and unavailable thread CPU are excluded; jobs received during warmup are flagged rather than silently removed.")
            add("Only land jobs begun during this deep capture session are measured; pending jobs at stop and initial ENC/style work are not retroactively attributed.")
            add("GPU command intervals are asynchronous independent observations, identified by original native frame, not by the frame that reads them.")
            add("GPU intervals may include CPU submission gaps and tile-memory work; they are not pure GPU busy time or physical display latency.")
            add("GPU timers require EXT_disjoint_timer_query, complete API and valid counter width; disjoint/possible overflow results are discarded. Pending tail results are not awaited at stop.")
            add("GPU issue overhead overlaps native frame scopes; polling overhead lies outside the active native frame and inside the JNI/worker boundary.")
            add("No live-user coordinates, ENC paths, object labels, credentials or connection addresses are exported. The fixed public Boston benchmark camera may be included.")
        })
    }
    }
}
