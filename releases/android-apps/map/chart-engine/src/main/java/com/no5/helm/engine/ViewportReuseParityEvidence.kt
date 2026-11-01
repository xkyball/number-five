package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.serialization.json.*

internal fun viewportReuseParityFullMatches(buffer: ChartBufferSnapshot?, viewport: ChartViewport,
    revision: Long, geometry: ChartSurfaceGeometry?, sourceGeneration: Long, contentGeneration: Long): Boolean =
    buffer != null && buffer.stamp > 0 && buffer.producerCompleted && buffer.stage == ChartContentStage.FULL &&
        buffer.cameraRevision == revision && buffer.frame.viewport == viewport && buffer.frame.geometry == geometry &&
        buffer.frame.sourceGeneration == sourceGeneration && buffer.frame.contentGeneration == contentGeneration

internal fun viewportReuseParityDeadlineNs(nowNs: Long, recipeStartedNs: Long, nextOriginalOffsetMs: Long): Long {
    require(nextOriginalOffsetMs >= 0)
    return minOf(Math.addExact(recipeStartedNs, Math.multiplyExact(nextOriginalOffsetMs, 1_000_000L)),
        Math.addExact(nowNs, ViewportReuseParityEvidence.FULL_WAIT_MS * 1_000_000L))
}

/** Copied on the native owner; a native FULL is deliberately not an Android display receipt. */
internal data class ViewportReuseParityAuthority(
    val runId: String, val catalogGeneration: String, val sourceGeneration: Long,
    val surfaceGeneration: Long, val contentGeneration: Long, val cameraRevision: Long,
    val nativeFrame: Long, val nativeDataGeneration: Long, val nativeContentRevision: Long,
    val presentationGeneration: Long, val fullBufferStamp: Long, val viewport: ChartViewport,
) {
    init {
        require(runId.isNotBlank() && runId.length <= 128 && catalogGeneration.matches(Regex("[a-f0-9]{64}")))
        require(listOf(sourceGeneration, surfaceGeneration, contentGeneration, cameraRevision,
            nativeDataGeneration, nativeContentRevision, presentationGeneration).all { it >= 0 })
        require(nativeFrame > 0 && fullBufferStamp > 0)
        require(viewport.latitude.isFinite() && viewport.latitude in -85.0..85.0 &&
            viewport.longitude.isFinite() && viewport.longitude in -180.0..180.0 &&
            viewport.metresToPixels.isFinite() && viewport.metresToPixels > 0 && viewport.rotationRadians.isFinite() &&
            viewport.width in 1..MAX_CHART_WIDTH && viewport.height in 1..MAX_CHART_HEIGHT)
    }
    fun json(): JsonObject = buildJsonObject {
        put("runId", runId); put("catalogGeneration", catalogGeneration); put("sourceGeneration", sourceGeneration)
        put("surfaceGeneration", surfaceGeneration); put("contentGeneration", contentGeneration)
        put("cameraRevision", cameraRevision); put("nativeFrame", nativeFrame)
        put("nativeDataGeneration", nativeDataGeneration); put("nativeContentRevision", nativeContentRevision)
        put("presentationGeneration", presentationGeneration); put("fullBufferStamp", fullBufferStamp)
        put("viewport", BostonBenchmarkRecipe.cameraJson(viewport))
    }
}

/** Six exact, single-attempt observations only. No image bytes, retries or timing quantiles are retained. */
internal class ViewportReuseParityEvidence(private val runId: String) {
    private val captures = ArrayList<JsonObject>(MAX_FRAMES)
    private var failure = ""
    private var retainedBytes = 0

    @Synchronized fun capture(stepIndex: Int, before: ViewportReuseParityAuthority, deadlineNs: Long,
        nowNs: () -> Long, stillAllowed: () -> Boolean, nativeCapture: () -> String,
        readAfter: () -> ViewportReuseParityAuthority): JsonObject {
        try {
            check(failure.isEmpty()) { "Parity run already failed" }
            check(stepIndex == captures.size && stepIndex in 0 until MAX_FRAMES) { "Parity pose missing, repeated or over limit" }
            check(before.runId == runId && stillAllowed() && nowNs() < deadlineNs) { "Parity owner or deadline expired" }
            val raw = nativeCapture() // Exactly one native readback per pose, never a retry.
            check(raw.toByteArray(Charsets.UTF_8).size <= MAX_NATIVE_JSON_BYTES) { "Parity native report exceeds byte cap" }
            val native = Json.parseToJsonElement(raw).jsonObject
            validateNative(native, before)
            val after = readAfter()
            check(stillAllowed() && nowNs() < deadlineNs && before == after) { "Parity authority changed or capture overran" }
            val result = buildJsonObject {
                put("stepIndex", stepIndex); put("authorityBefore", before.json()); put("native", native)
                put("authorityAfter", after.json()); put("sameAuthority", true)
            }
            val bytes = result.toString().toByteArray(Charsets.UTF_8).size
            check(retainedBytes + bytes <= MAX_REPORT_BYTES) { "Parity evidence exceeds aggregate byte cap" }
            captures += result; retainedBytes += bytes
            return result
        } catch (error: Exception) {
            if (failure.isEmpty()) failure = (error.message ?: error.javaClass.simpleName).take(256)
            throw error
        }
    }

    @Synchronized fun fail(reason: String) { if (failure.isEmpty()) failure = reason.take(256).ifBlank { "Parity capture failed" } }
    @Synchronized fun snapshot(): JsonObject = buildJsonObject {
        put("schema", "no5.viewport-reuse-parity-set/1"); put("runId", runId)
        put("requiredFrames", MAX_FRAMES); put("frameCount", captures.size)
        put("maxNativeJsonBytes", MAX_NATIVE_JSON_BYTES); put("maxReportBytes", MAX_REPORT_BYTES)
        put("queriesPerFrame", 5); put("retainedBytes", retainedBytes)
        put("complete", captures.size == MAX_FRAMES && failure.isEmpty()); put("error", failure)
        put("frames", JsonArray(captures.toList()))
        put("endpoint", "Exact native FULL output FBO and unmodified native queries; not Android display")
        put("timingComparable", false); put("physicalInputToDisplay", "NOT_MEASURED")
        put("rawRgbaPersisted", false); put("normalization", "NONE")
    }

    companion object {
        const val MAX_FRAMES = 6
        const val MAX_NATIVE_JSON_BYTES = 16 * 1024
        const val MAX_REPORT_BYTES = 256 * 1024
        const val FULL_WAIT_MS = 1_000L
        internal fun validateNative(value: JsonObject, a: ViewportReuseParityAuthority,
            expectedSchema: String = "no5.viewport-reuse-parity/1") {
            fun long(name: String) = value.getValue(name).jsonPrimitive.long
            fun number(name: String) = value.getValue(name).jsonPrimitive.double
            fun text(name: String) = value.getValue(name).jsonPrimitive.content
            check(text("schema") == expectedSchema && text("runId") == a.runId)
            check(text("catalogGeneration") == a.catalogGeneration && long("frame") == a.nativeFrame &&
                long("dataGeneration") == a.nativeDataGeneration && long("contentRevision") == a.nativeContentRevision &&
                long("presentationGeneration") == a.presentationGeneration) { "Native parity revision mismatch" }
            val v = a.viewport
            check(number("latitude") == v.latitude && number("longitude") == v.longitude && number("ppm") == v.metresToPixels &&
                number("rotation") == v.rotationRadians && long("width") == v.width.toLong() && long("height") == v.height.toLong()) {
                "Native parity viewport mismatch"
            }
            check(long("rgbaBytes") == v.width.toLong() * v.height * 4 &&
                long("rgbaBytes") <= MAX_CHART_WIDTH.toLong() * MAX_CHART_HEIGHT * 4) { "Parity RGBA size mismatch" }
            val hash = Regex("[a-f0-9]{64}")
            for (key in listOf("rgbaSHA256", "semanticSHA256", "associationsSHA256")) check(hash.matches(text(key)))
            check(long("featureCount") >= 0 && long("filterRevision") >= 0 && long("overlayRevision") >= 0 && long("presentationSlot") in 0L..1L)
            check(text("normalization") == "NONE" && long("extraDraws") == 0L && long("extraSwaps") == 0L &&
                long("consumerAcknowledgements") == 0L) { "Parity observation changed rendering" }
            check(text("pixelSource") == "completed native output FBO, full 1:1 RGBA; no post-swap window read")
            val status = value.getValue("status").jsonObject
            check(status.getValue("dataGeneration").jsonPrimitive.long == a.nativeDataGeneration &&
                status.getValue("latitude").jsonPrimitive.double == v.latitude &&
                status.getValue("longitude").jsonPrimitive.double == v.longitude &&
                status.getValue("ppm").jsonPrimitive.double == v.metresToPixels &&
                status.getValue("rotation").jsonPrimitive.double == v.rotationRadians &&
                status.getValue("width").jsonPrimitive.int == v.width && status.getValue("height").jsonPrimitive.int == v.height)
            // Preserve the legacy oracle fields exactly; never normalize or hash all status/cache counters.
            for (name in listOf("filteredFeatures", "viewportSelectedCharts", "coverageUnknownCells",
                "coverageUncoveredPixels", "selectedCharts", "featureCount", "unsupportedFeatures"))
                check(status.getValue(name).jsonPrimitive.long >= 0)
            check(status.getValue("featureCount").jsonPrimitive.long == long("featureCount"))
            status.getValue("coverageMasked").jsonPrimitive.boolean
            status.getValue("objectSelection").jsonObject.let { selection ->
                selection.getValue("featureIndex").jsonPrimitive.long
                selection.getValue("visible").jsonPrimitive.boolean
                check(selection.getValue("segments").jsonPrimitive.long >= 0)
            }
            check(text("queryRecipe") == "viewport-center-five/1: x=w/2+(i-2)*w/8, y=h/2+(i%2)*h/8, radius=24, offset=0")
            val queries = value.getValue("queries").jsonArray
            check(queries.size == 5) { "Parity requires all five unmodified query hashes" }
            queries.forEachIndexed { i, entry ->
                val q = entry.jsonObject
                check(q.getValue("x").jsonPrimitive.double == v.width * .5 + (i - 2) * v.width * .125 &&
                    q.getValue("y").jsonPrimitive.double == v.height * .5 + (i % 2) * v.height * .125 &&
                    q.getValue("radius").jsonPrimitive.int == 24 && q.getValue("offset").jsonPrimitive.int == 0 &&
                    q.getValue("jsonBytes").jsonPrimitive.int in 0..(256 * 1024) &&
                    hash.matches(q.getValue("sha256").jsonPrimitive.content)) { "Parity query recipe/hash invalid" }
            }
        }
    }
}
