package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.serialization.json.*

/** Typed, bounded observations; JSON is allocated only after stop, outside the UI producer lock. */
internal class RendererLiveInputBuffer(val runId: String, private val maxRows: Int = 8192,
    private val maxInputs: Int = 2048, private val maxBuffers: Int = 1024,
    override val replayRecipe: RendererReplayRecipe = RendererReplayRecipe.LEGACY,
    override val repaintTransforms: Boolean = true,
    private val currentPrivateView: Boolean = false) : RendererLiveInputSink {
    override val diagnosticMarkerEnabled: Boolean get() = !currentPrivateView && replayRecipe == RendererReplayRecipe.P0_CONTINUOUS
    init { require(maxRows in 1..8192 && maxInputs in 1..2048 && maxBuffers in 1..1024) }
    data class Row(val kind: String, val at: Long, val input: Long = 0, val a: Long = 0, val b: Long = 0,
        val c: Long = 0, val code: String = "", val values: List<Double> = emptyList())
    private val rows = ArrayList<Row>()
    private val tags = linkedSetOf<Long>()
    private val context = ThreadLocal.withInitial { 0L }
    @Volatile private var closed = false
    private var dropped = 0L
    @Volatile private var inputCount = 0L
    private var unknownTags = 0L
    private var liveStart = 0L
    private var liveEnd = 0L
    @Volatile var uiBasisStamp = 0L; private set
    private var replayClaimed = false
    private var pairedImagesObserved = false
    @Volatile var replayState = "NOT_STARTED"; private set
    @Synchronized private fun row(value: Row) {
        if (closed) return
        if (rows.size == maxRows) dropped++ else rows += value
    }
    // Pure memory predicate; retirement is visible before a later capture can be attributed.
    internal fun diagnosticOriginOpen(id: Long): Boolean = !closed && id > 0 && id <= maxInputs && id <= inputCount
    override fun currentInput(): Long = context.get() ?: 0L
    override fun <T> withInput(id: Long, block: () -> T): T {
        val previous = context.get(); context.set(id)
        try { return block() } finally { context.set(previous) }
    }
    @Synchronized override fun input(uptimeMs: Long, beforeMonoNs: Long, afterMonoNs: Long,
        dispatchUptimeMs: Long, action: Int, changedId: Int, historyIndex: Int, gesture: Long,
        pointerIds: IntArray, positions: FloatArray, origin: RendererInputOrigin): Long {
        if (closed) return 0
        val id = ++inputCount
        if (id > maxInputs || pointerIds.size !in 1..10 || positions.size != 2 * pointerIds.size) {
            dropped++; return id
        }
        row(Row("INPUT", afterMonoNs, id, uptimeMs, gesture, historyIndex.toLong(), "$action:$changedId:${origin.name}",
            pointerIds.indices.flatMap { listOf(pointerIds[it].toDouble(), positions[2 * it].toDouble(), positions[2 * it + 1].toDouble()) }))
        row(Row("CLOCK_ANCHOR", afterMonoNs, id, beforeMonoNs, dispatchUptimeMs))
        return id
    }
    override fun camera(inputId: Long, revision: Long, generation: Long, viewport: ChartViewport, applied: Boolean) =
        row(Row(if (applied) "CAMERA_APPLIED" else "CAMERA_ACCEPTED", System.nanoTime(), inputId, revision, generation,
            values = listOf(viewport.latitude, viewport.longitude, viewport.metresToPixels, viewport.rotationRadians,
                viewport.width.toDouble(), viewport.height.toDouble())))
    override fun disposition(inputId: Long, reason: String, replacement: Long) =
        row(Row("DISPOSITION", System.nanoTime(), inputId, replacement, code = reason))
    override fun markerTarget(point: ScreenPoint) = row(Row("MARKER_TARGET", System.nanoTime(), currentInput(), values = listOf(point.x, point.y)))
    fun nativeFrame(native: JsonObject, fullStamp: Long, revision: Long, input: Long) {
        row(Row("AREA_REUSE_FRAME", System.nanoTime(), input, fullStamp, c = revision,
            code = (native["areaReuse"]?.jsonObject?.get("hit")?.jsonPrimitive?.booleanOrNull == true).toString(),
            values = listOf(native["activeFrameWallMs"]?.jsonPrimitive?.doubleOrNull ?: -1.0)))
        val base = native["baseAreasPresentation"]?.jsonObject
        fun value(key: String) = native[key]?.jsonPrimitive?.doubleOrNull ?: -1.0
        fun baseValue(key: String) = base?.get(key)?.jsonPrimitive?.doubleOrNull ?: -1.0
        row(Row("NATIVE_FRAME", System.nanoTime(), input, fullStamp,
            base?.get("baseStamp")?.jsonPrimitive?.longOrNull ?: 0L, revision,
            "${base?.get("submitted")?.jsonPrimitive?.booleanOrNull == true}:${base?.get("decorativeLandPending")?.jsonPrimitive?.booleanOrNull == true}",
            listOf(value("activeFrameWallMs"), baseValue("readyFromRenderStartMs"),
                baseValue("swapEndFromRenderStartMs"), baseValue("extraPresentationWallMs"),
                baseValue("swapWallMs"), value("renderThreadCpuMs"), value("eglSwapThreadCpuMs"))))
    }
    @Synchronized override fun buffer(buffer: ChartBufferSnapshot, completed: Boolean) {
        if (closed) return
        if (!completed && tags.size >= maxBuffers) { dropped++; return }
        if (!completed) tags += buffer.stamp
        if (buffer.presentationSlot >= 0) pairedImagesObserved = true
        row(Row(if (completed) "BUFFER_COMPLETED" else "BUFFER_SUBMITTED", System.nanoTime(),
            a = buffer.stamp, b = buffer.cameraRevision, c = buffer.generation,
            code = buffer.producerCompleted.toString()))
        if (!completed) row(Row("BUFFER_STAGE", System.nanoTime(), a = buffer.stamp,
            b = buffer.cameraRevision, c = buffer.generation, code = buffer.stage.name,
            values = listOf(buffer.frame.viewport.latitude, buffer.frame.viewport.longitude,
                buffer.frame.viewport.metresToPixels, buffer.frame.viewport.rotationRadians)))
        if (!completed) row(Row("BUFFER_SLOT", System.nanoTime(), a = buffer.stamp,
            b = buffer.cameraRevision, c = buffer.generation, code = buffer.presentationSlot.toString()))
        if (!completed) buffer.raster?.let { raster ->
            val geometry = raster.geometry
            val camera = raster.producerCamera
            row(Row("BUFFER_RASTER", System.nanoTime(), a = buffer.stamp,
                b = buffer.cameraRevision, c = buffer.generation, code = "PRODUCER_PIXELS",
                values = listOf(geometry.bufferWidth.toDouble(), geometry.bufferHeight.toDouble(),
                    raster.filledLeft, raster.filledTop, raster.filledWidth.toDouble(), raster.filledHeight.toDouble(),
                    camera.latitude, camera.longitude, camera.metresToPixels, camera.rotationRadians,
                    geometry.visible.surfaceWidth.toDouble(), geometry.visible.surfaceHeight.toDouble(),
                    geometry.visible.frameWidth.toDouble(), geometry.visible.frameHeight.toDouble())))
        }
    }
    @Synchronized override fun texture(tag: Long, knownBasis: Boolean, phase: String) {
        if (closed) return
        val known = knownBasis && tag in tags
        uiBasisStamp = if (known) tag else 0L
        if (!known) unknownTags++
        row(Row("TEXTURE_TAG_SAMPLE", System.nanoTime(), a = tag, code = "$phase:$known"))
    }
    override fun transform(base: ChartBufferSnapshot?, targetRevision: Long, transformRevision: Long,
        matrix: FloatArray, beforeMonoNs: Long, afterMonoNs: Long) =
        row(Row("TRANSFORM_SET", afterMonoNs, currentInput(), base?.stamp ?: 0, targetRevision, transformRevision,
            beforeMonoNs.toString(), matrix.map(Float::toDouble)))
    @Synchronized override fun replay(state: String, delivered: Int, maxLatenessMs: Long) {
        if (closed) return
        replayState = state
        row(Row("REPLAY", System.nanoTime(), a = delivered.toLong(), b = maxLatenessMs, code = state))
    }
    @Synchronized override fun claimReplay(): Boolean {
        if (closed || replayClaimed) return false
        replayClaimed = true; return true
    }
    @Synchronized fun startLive() { liveStart = System.nanoTime(); row(Row("LIVE_START", liveStart)) }
    @Synchronized fun finishLive() { liveEnd = System.nanoTime(); row(Row("LIVE_END", liveEnd)) }
    @Synchronized fun liveValidityError(): String? = when {
        dropped > 0 -> "Bounded observation capacity exceeded"
        rows.none { it.kind == "CAMERA_ACCEPTED" && it.at >= liveStart && it.input > 0 } -> "No input-bound camera samples accepted"
        rows.any { it.kind == "DISPOSITION" && it.at >= liveStart && it.code in setOf("BLOCKED_REGION", "CANCELLED_STREAM", "RUN_CAMERA_DISABLED", "INVALID_GEOMETRY") } -> "Input stream rejected or cancelled"
        rows.any { it.kind == "TEXTURE_TAG_SAMPLE" && it.at >= liveStart && it.code.endsWith(":false") } -> "Unknown live texture basis"
        else -> null
    }
    fun finishReport(): JsonObject {
        val snapshot: List<Row>; val loss: Long; val unknown: Long; val inputs: Long
        synchronized(this) { closed = true; snapshot = rows.toList(); loss = dropped; unknown = unknownTags; inputs = inputCount }
        return buildJsonObject {
            put("schema", "no5.renderer-live-input-observations/2"); put("runId", runId)
            put("cameraOriginScope", "EXACT_CAPTURE_INSTANCE_RUN_INPUT_ID")
            put("cameraAcceptedScope", "OWNER_CAMERA_COMMIT_NOT_COMMAND_RING_RECEIPT")
            put("cameraAppliedScope", "NATIVE_SET_READBACK_RETURN_NOT_COMMAND_RESULT")
            put("cameraAppliedValues", "ACTUAL_NATIVE_READBACK")
            put("commandReceiptLatency", "NOT_MEASURED")
            put("maxRows", maxRows); put("maxInputs", maxInputs); put("maxBuffers", maxBuffers)
            put("dropped", loss); put("unknownTags", unknown); put("inputSamples", inputs)
            put("storageCoverageComplete", loss == 0L)
            put("liveStartMonoNs", liveStart); put("liveEndMonoNs", liveEnd)
            put("replayState", replayState)
            put("replayRecipe", replayRecipe.name)
            put("replaySha256", RendererTouchReplay.sha256(replayRecipe))
            put("observationClock", "System.nanoTime / CLOCK_MONOTONIC")
            put("inputClock", "MotionEvent eventTime uptimeMillis, 1ms quantization")
            put("clockMappingState", "ANCHORS_RECORDED_NOT_VALIDATED")
            put("coordinates", if (currentPrivateView) "PRIVATE_CURRENT_VIEW; keep coordinates and source pins outside public Git" else "public Boston camera and chart-local pointer pixels only")
            put("sourceScope", if (currentPrivateView) "CURRENT_RESIDENT_FROZEN_DATA" else "PUBLIC_BOSTON")
            put("physicalInputOriginVerified", false); put("physicalDisplayLatencyMeasured", false)
            put("windowPresentCorrelation", "NOT_MEASURED"); put("visibleFps", JsonNull); put("inputToVisibleP95Ms", JsonNull)
            put("textureCameraAssociation", if (pairedImagesObserved) "IMMUTABLE_SLOT_ACQUIRED_BEFORE_REVEAL_OLD_SLOT_RELEASED_AFTER_FRAME_COMMIT" else "TIMESTAMP_SAMPLE_ONLY_NOT_ATOMIC_WITH_HWUI_DRAW")
            put("transformSetConvention", "VISIBLE_CAMERA_AFFINE_BEFORE_STATIC_PRODUCER_QUEUE_CROP")
            put("bufferRasterColumns", buildJsonArray {
                listOf("bufferWidth", "bufferHeight", "filledLeft", "filledTop", "filledWidth", "filledHeight",
                    "producerLatitude", "producerLongitude", "metresToPixels", "rotationRadians",
                    "visibleSurfaceWidth", "visibleSurfaceHeight", "visibleFrameWidth", "visibleFrameHeight").forEach { add(it) }
            })
            put("columns", buildJsonArray { listOf("kind", "atMonoNs", "inputId", "a", "b", "c", "code", "values").forEach { add(it) } })
            put("rows", buildJsonArray { snapshot.forEach { r -> add(buildJsonArray {
                add(r.kind); add(r.at); add(r.input); add(r.a); add(r.b); add(r.c); add(r.code)
                add(buildJsonArray { r.values.forEach { add(it) } })
            }) } })
        }
    }
}
