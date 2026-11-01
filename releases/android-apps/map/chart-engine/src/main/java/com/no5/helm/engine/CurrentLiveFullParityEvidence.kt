package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.serialization.json.*

/** Registry/camera counters belong to the Kotlin owner, never to the native frame counter. */
internal data class CurrentLiveFullParityAuthority(val native: ViewportReuseParityAuthority,
    val captureId: String, val bufferGeneration: Long, val presentationSlot: Int,
    val normalAbortEnabled: Boolean, val appliedAuthorityEpoch: Long?) {
    init {
        require(java.util.UUID.fromString(captureId).toString() == captureId)
        require(bufferGeneration > 0 && presentationSlot in 0..1)
        require(if (normalAbortEnabled) appliedAuthorityEpoch != null && appliedAuthorityEpoch > 0 else appliedAuthorityEpoch == null)
    }
    fun json(): JsonObject = buildJsonObject {
        native.json().forEach { (key, value) -> put(key, value) }
        put("captureId", captureId); put("bufferGeneration", bufferGeneration); put("presentationSlot", presentationSlot)
        put("normalAbortEnabled", normalAbortEnabled); put("contentStage", "FULL"); put("producerCompleted", true)
        put("appliedAuthorityEpoch", appliedAuthorityEpoch?.let(::JsonPrimitive) ?: JsonNull)
        put("ownerDomainJoin", OWNER_DOMAIN_JOIN)
    }
    companion object {
        const val OWNER_DOMAIN_JOIN = "Kotlin exact completed FULL registry reference before/after; registry/camera/source/surface are not nativeFrame domains"
    }
}

/** New opt-in evidence, isolated from Capacity/OFF readback and every timed input arm. */
internal class CurrentLiveFullParityEvidence(private val runId: String, val captureId: String,
    private val normalAbortEnabled: Boolean) {
    private val frames = ArrayList<JsonObject>(ViewportReuseParityEvidence.MAX_FRAMES)
    private var retainedBytes = 0
    private var failure = ""

    @Synchronized fun capture(index: Int, before: CurrentLiveFullParityAuthority, deadlineNs: Long,
        nowNs: () -> Long, allowed: () -> Boolean, nativeCapture: () -> String,
        readAfter: () -> CurrentLiveFullParityAuthority): JsonObject {
        try {
            check(failure.isEmpty()) { "Current-live parity already failed" }
            check(index == frames.size && index in 0 until ViewportReuseParityEvidence.MAX_FRAMES) { "Current-live parity frame missing, repeated or over limit" }
            check(before.native.runId == runId && before.captureId == captureId && before.normalAbortEnabled == normalAbortEnabled &&
                allowed() && nowNs() < deadlineNs) { "Current-live parity owner or deadline expired" }
            val raw = nativeCapture() // Exactly once, never capture a replacement for a failed stamp.
            check(raw.toByteArray(Charsets.UTF_8).size <= ViewportReuseParityEvidence.MAX_NATIVE_JSON_BYTES) { "Current-live parity frame exceeds byte cap" }
            val native = Json.parseToJsonElement(raw).jsonObject
            ViewportReuseParityEvidence.validateNative(native, before.native, "no5.current-live-full-parity/1")
            check(native.getValue("captureId").jsonPrimitive.content == captureId &&
                native.getValue("normalAbortEnabled").jsonPrimitive.boolean == normalAbortEnabled &&
                native.getValue("fullBufferStamp").jsonPrimitive.long == before.native.fullBufferStamp &&
                native.getValue("ownerCameraRevision").jsonPrimitive.long == before.native.cameraRevision &&
                native.getValue("bufferGeneration").jsonPrimitive.long == before.bufferGeneration &&
                native.getValue("ownerSourceGeneration").jsonPrimitive.long == before.native.sourceGeneration &&
                native.getValue("ownerSurfaceGeneration").jsonPrimitive.long == before.native.surfaceGeneration &&
                native.getValue("ownerDomainJoin").jsonPrimitive.content == CurrentLiveFullParityAuthority.OWNER_DOMAIN_JOIN &&
                native.getValue("presentationSlot").jsonPrimitive.int == before.presentationSlot) { "Current-live parity owner-domain join mismatch" }
            val applied = native.getValue("nativeAppliedAuthority")
            if (normalAbortEnabled) {
                val authority = applied.jsonObject
                check(authority.getValue("epoch").jsonPrimitive.long == before.appliedAuthorityEpoch &&
                    authority.getValue("cameraRevision").jsonPrimitive.long == before.native.cameraRevision &&
                    authority.getValue("sourceGeneration").jsonPrimitive.long == before.native.sourceGeneration &&
                    authority.getValue("surfaceGeneration").jsonPrimitive.long == before.native.surfaceGeneration) { "Current-live applied native authority mismatch" }
            } else check(applied == JsonNull) { "OFF has no native applied camera-authority counter" }
            val after = readAfter()
            check(allowed() && nowNs() < deadlineNs && after == before) { "Current-live parity exact FULL changed or capture overran" }
            val frame = buildJsonObject {
                put("stepIndex", index); put("authorityBefore", before.json()); put("native", native)
                put("authorityAfter", after.json()); put("sameAuthority", true)
            }
            val bytes = frame.toString().toByteArray(Charsets.UTF_8).size
            check(retainedBytes + bytes <= ViewportReuseParityEvidence.MAX_REPORT_BYTES) { "Current-live parity set exceeds byte cap" }
            frames += frame; retainedBytes += bytes
            return frame
        } catch (error: Exception) {
            if (failure.isEmpty()) failure = (error.message ?: error.javaClass.simpleName).take(256)
            throw error
        }
    }
    @Synchronized fun fail(reason: String) { if (failure.isEmpty()) failure = reason.take(256).ifBlank { "Current-live parity failed" } }
    @Synchronized fun snapshot(): JsonObject = buildJsonObject {
        put("schema", "no5.current-live-full-parity-set/1"); put("runId", runId); put("captureId", captureId)
        put("normalAbortEnabled", normalAbortEnabled); put("requiredFrames", ViewportReuseParityEvidence.MAX_FRAMES)
        put("frameCount", frames.size); put("complete", frames.size == ViewportReuseParityEvidence.MAX_FRAMES && failure.isEmpty())
        put("error", failure); put("retainedBytes", retainedBytes); put("maxNativeJsonBytes", ViewportReuseParityEvidence.MAX_NATIVE_JSON_BYTES)
        put("maxReportBytes", ViewportReuseParityEvidence.MAX_REPORT_BYTES); put("frames", JsonArray(frames.toList()))
        put("queriesPerFrame", 5); put("timingComparable", false); put("rawRgbaPersisted", false); put("normalization", "NONE")
        put("extraDraws", 0); put("extraSwaps", 0); put("consumerAcknowledgements", 0)
        put("endpoint", "Exact native FULL output FBO and unmodified native queries; not Android display")
        put("physicalInputToDisplay", "NOT_MEASURED")
    }
}
