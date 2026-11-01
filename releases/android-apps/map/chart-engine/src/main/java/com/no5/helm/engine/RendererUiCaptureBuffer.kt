package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.serialization.json.*
import kotlin.math.ceil

/** Short synchronized primitive writes from UI; final JSON/percentiles on No5Chart after close. */
internal class RendererUiCaptureBuffer(private val limit: Int, private val startedNanos: Long) : RendererUiSink {
    private val phases = RendererUiPhase.entries
    private val wall = Array(phases.size) { LongArray(limit) }
    private val cpu = Array(phases.size) { LongArray(limit) { -1 } }
    private val counts = IntArray(phases.size)
    private val drops = LongArray(phases.size)
    private val unavailable = LongArray(phases.size)
    private val first = LongArray(phases.size) { -1 }
    private val last = LongArray(phases.size) { -1 }
    private var closed = false
    private var windowFrames = 0L
    private var windowFirstDraws = 0L
    private var platformDrops = 0L
    private var textureUpdates = 0L
    private var windowState = RendererWindowState.NOT_REGISTERED
    init { require(limit in 30..1200) }
    @Synchronized override fun record(phase: RendererUiPhase, wallNanos: Long, cpuNanos: Long) {
        if (closed) return
        val i = phase.ordinal
        if (wallNanos < 0) { unavailable[i]++; return }
        val row = counts[i]
        if (row == limit) { drops[i]++; return }
        val elapsed = (System.nanoTime() - startedNanos).coerceAtLeast(0)
        if (row == 0) first[i] = elapsed
        last[i] = elapsed
        wall[i][row] = wallNanos; cpu[i][row] = cpuNanos
        counts[i]++
    }
    @Synchronized override fun windowFrame(firstDraw: Boolean, droppedReports: Int) {
        if (closed) return
        windowFrames++; if (firstDraw) windowFirstDraws++
        platformDrops += droppedReports.coerceAtLeast(0)
        windowState = RendererWindowState.CALLBACK_SEEN
    }
    @Synchronized override fun windowState(state: RendererWindowState) { if (!closed) windowState = state }
    @Synchronized override fun textureUpdated() { if (!closed) textureUpdates++ }
    /** Freezes first, so a stale UI callback cannot contaminate the next capture/session. */
    fun finishReport(): JsonObject {
        synchronized(this) { closed = true } // Arrays are now immutable; sorting never holds the UI producer lock.
        fun summary(values: List<Long>): JsonElement {
            val sorted = values.filter { it >= 0 }.map { it / 1e6 }.sorted()
            if (sorted.isEmpty()) return JsonNull
            return buildJsonObject {
                fun p(f: Double) = sorted[(ceil(f * sorted.size).toInt() - 1).coerceAtLeast(0)]
                put("samples", sorted.size); put("min", sorted.first()); put("max", sorted.last()); put("mean", sorted.average())
                put("median", if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2)
                put("p95", p(.95)); put("p99", p(.99))
            }
        }
        return buildJsonObject {
            put("maxSamplesPerPhase", limit); put("independentObservations", true); put("phasesNonAdditive", true)
            put("windowState", windowState.name); put("windowFrames", windowFrames); put("windowFirstDraws", windowFirstDraws)
            put("platformDroppedReports", platformDrops); put("textureUpdates", textureUpdates)
            put("summary", buildJsonObject {
                for ((i, phase) in phases.withIndex()) {
                    if (counts[i] == 0 && drops[i] == 0L && unavailable[i] == 0L) continue
                    put(phase.key, buildJsonObject {
                        put("samples", counts[i]); put("droppedAtCapacity", drops[i]); put("unavailable", unavailable[i])
                        put("firstAcceptedElapsedMs", if (first[i] >= 0) JsonPrimitive(first[i] / 1e6) else JsonNull)
                        put("lastAcceptedElapsedMs", if (last[i] >= 0) JsonPrimitive(last[i] / 1e6) else JsonNull)
                        put("wallMs", summary(wall[i].take(counts[i]))); put("threadCpuMs", summary(cpu[i].take(counts[i])))
                    })
                }
            })
            put("limitations", buildJsonArray {
                add("Activity Window/HWUI observations belong to independent window frames; they are not paired with S52 frame IDs or physical scanout.")
                add("Window spans may overlap and total need not equal their sum. Window thread CPU is unavailable.")
                add("Chart-node measure/place/draw dispatch and AndroidView update cover only those callbacks; full Compose recomposition and render-thread GPU work are not measured by them.")
                add("Texture update callback intervals include idle/queue/UI delay; they are not input-to-display or frame-to-display latency.")
                add("Window GPU duration requires API31 and an available platform value; it is separate from the S52 GL timer. Missing values are null.")
                add("Per-phase caps retain the first accepted samples; platform and capacity losses and coverage intervals are reported. No latest-frame attribution is inferred.")
            })
        }
    }
}
