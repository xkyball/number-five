package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CurrentLiveFullParityEvidenceTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val view = ChartViewport(40.0, -70.0, .01, .2, 1000, 600)
    private fun authority(on: Boolean = false) = CurrentLiveFullParityAuthority(
        ViewportReuseParityAuthority("actual-run", "a".repeat(64), 1, 2, 3, 4, 5, 6, 7, 8, 9, view),
        id, 10, 0, on, if (on) 11 else null)
    private fun native(a: CurrentLiveFullParityAuthority) = buildJsonObject {
        val b = a.native
        put("schema", "no5.current-live-full-parity/1"); put("runId", b.runId); put("frame", b.nativeFrame)
        put("catalogGeneration", b.catalogGeneration); put("contentRevision", b.nativeContentRevision)
        put("dataGeneration", b.nativeDataGeneration); put("presentationGeneration", b.presentationGeneration)
        put("filterRevision", 10); put("overlayRevision", 11); put("presentationSlot", a.presentationSlot)
        put("latitude", view.latitude); put("longitude", view.longitude); put("ppm", view.metresToPixels)
        put("rotation", view.rotationRadians); put("width", view.width); put("height", view.height)
        put("rgbaSHA256", "1".repeat(64)); put("rgbaBytes", view.width.toLong() * view.height * 4)
        put("semanticSHA256", "2".repeat(64)); put("associationsSHA256", "3".repeat(64)); put("featureCount", 42)
        put("normalization", "NONE"); put("extraDraws", 0); put("extraSwaps", 0); put("consumerAcknowledgements", 0)
        put("pixelSource", "completed native output FBO, full 1:1 RGBA; no post-swap window read")
        put("queryRecipe", "viewport-center-five/1: x=w/2+(i-2)*w/8, y=h/2+(i%2)*h/8, radius=24, offset=0")
        put("queries", JsonArray((0..4).map { i -> buildJsonObject {
            put("x", view.width * .5 + (i - 2) * view.width * .125); put("y", view.height * .5 + (i % 2) * view.height * .125)
            put("radius", 24); put("offset", 0); put("jsonBytes", 2); put("sha256", "4".repeat(64))
        } }))
        put("status", buildJsonObject {
            put("dataGeneration", b.nativeDataGeneration); put("latitude", view.latitude); put("longitude", view.longitude)
            put("ppm", view.metresToPixels); put("rotation", view.rotationRadians)
            put("width", view.width); put("height", view.height); put("coverageMasked", false)
            for (key in listOf("filteredFeatures", "viewportSelectedCharts", "coverageUnknownCells", "coverageUncoveredPixels",
                "selectedCharts", "featureCount", "unsupportedFeatures")) put(key, 0)
            put("featureCount", 42)
            put("objectSelection", buildJsonObject { put("featureIndex", -1); put("visible", false); put("segments", 0) })
        })
        put("captureId", id); put("normalAbortEnabled", a.normalAbortEnabled); put("fullBufferStamp", b.fullBufferStamp)
        put("ownerCameraRevision", b.cameraRevision); put("bufferGeneration", a.bufferGeneration)
        put("ownerSourceGeneration", b.sourceGeneration); put("ownerSurfaceGeneration", b.surfaceGeneration)
        put("ownerDomainJoin", CurrentLiveFullParityAuthority.OWNER_DOMAIN_JOIN)
        put("nativeAppliedAuthority", if (a.normalAbortEnabled) buildJsonObject {
            put("epoch", a.appliedAuthorityEpoch); put("cameraRevision", b.cameraRevision)
            put("sourceGeneration", b.sourceGeneration); put("surfaceGeneration", b.surfaceGeneration)
        } else JsonNull)
    }
    private fun capture(e: CurrentLiveFullParityEvidence, a: CurrentLiveFullParityAuthority = authority(), index: Int = 0,
        after: CurrentLiveFullParityAuthority = a, raw: String = native(a).toString()): JsonObject =
        e.capture(index, a, 1000, { 100 }, { true }, { raw }, { after })

    @Test fun sixExactNativeFramesRetainAllOriginalOraclesAndSeparateOwnerDomainsForOffAndOn() {
        for (on in listOf(false, true)) {
            val first = authority(on); val e = CurrentLiveFullParityEvidence(first.native.runId, id, on)
            repeat(6) { i ->
                val a = first.copy(native = first.native.copy(nativeFrame = 5L + i, fullBufferStamp = 9L + i,
                    cameraRevision = 4L + i), bufferGeneration = 10L + i, presentationSlot = i % 2,
                    appliedAuthorityEpoch = if (on) 11L + i else null)
                assertEquals(native(a), capture(e, a, i).getValue("native"))
            }
            val set = e.snapshot()
            assertTrue(set.getValue("complete").jsonPrimitive.boolean); assertEquals(6, set.getValue("frameCount").jsonPrimitive.int)
            assertFalse(set.getValue("timingComparable").jsonPrimitive.boolean)
            assertEquals("NOT_MEASURED", set.getValue("physicalInputToDisplay").jsonPrimitive.content)
            assertEquals(0, set.getValue("extraDraws").jsonPrimitive.int)
            assertEquals(0, set.getValue("extraSwaps").jsonPrimitive.int)
            assertEquals(0, set.getValue("consumerAcknowledgements").jsonPrimitive.int)
        }
    }
    @Test fun anyChangedExactOwnerOrNativeFullRejectsWithoutSubstitutingTheLatestFrame() {
        val a = authority(true)
        val changed = listOf(a.copy(bufferGeneration = 12), a.copy(presentationSlot = 1),
            a.copy(appliedAuthorityEpoch = 12), a.copy(native = a.native.copy(fullBufferStamp = 10)),
            a.copy(native = a.native.copy(nativeFrame = 6)), a.copy(native = a.native.copy(cameraRevision = 5)),
            a.copy(native = a.native.copy(sourceGeneration = 3)), a.copy(native = a.native.copy(surfaceGeneration = 3)),
            a.copy(native = a.native.copy(contentGeneration = 5)))
        for (after in changed) {
            val e = CurrentLiveFullParityEvidence(a.native.runId, id, true); var reads = 0
            assertThrows(IllegalStateException::class.java) {
                e.capture(0, a, 1000, { 100 }, { true }, { reads++; native(a).toString() }, { after })
            }
            assertEquals(1, reads); assertEquals(0, e.snapshot().getValue("frameCount").jsonPrimitive.int)
            assertFalse(e.snapshot().getValue("complete").jsonPrimitive.boolean)
        }
    }
    @Test fun nativeOwnerJoinAndUnchangedPixelOraclesAreStrictAndNeverNormalized() {
        val a = authority(true)
        val changes = listOf("captureId" to JsonPrimitive("00000000-0000-4000-8000-000000000002"),
            "normalAbortEnabled" to JsonPrimitive(false), "fullBufferStamp" to JsonPrimitive(99),
            "ownerCameraRevision" to JsonPrimitive(99), "bufferGeneration" to JsonPrimitive(99),
            "ownerSourceGeneration" to JsonPrimitive(99), "ownerSurfaceGeneration" to JsonPrimitive(99),
            "ownerDomainJoin" to JsonPrimitive("nativeFrame equals camera"), "presentationSlot" to JsonPrimitive(1),
            "nativeAppliedAuthority" to JsonNull, "frame" to JsonPrimitive(99), "extraDraws" to JsonPrimitive(1),
            "extraSwaps" to JsonPrimitive(1), "consumerAcknowledgements" to JsonPrimitive(1),
            "queries" to JsonArray(emptyList()), "schema" to JsonPrimitive("no5.viewport-reuse-parity/1"))
        for ((key, value) in changes) {
            val e = CurrentLiveFullParityEvidence(a.native.runId, id, true)
            val corrupt = JsonObject(native(a).toMutableMap().apply { put(key, value) })
            assertThrows(Exception::class.java) { capture(e, a, raw = corrupt.toString()) }
            assertEquals(0, e.snapshot().getValue("frameCount").jsonPrimitive.int)
        }
    }
    @Test fun offCannotInventNativeAppliedCameraAuthority() {
        val a = authority(); val e = CurrentLiveFullParityEvidence(a.native.runId, id, false)
        val corrupt = JsonObject(native(a).toMutableMap().apply {
            put("nativeAppliedAuthority", buildJsonObject { put("epoch", 11) })
        })
        assertThrows(IllegalStateException::class.java) { capture(e, a, raw = corrupt.toString()) }
    }
    @Test fun missingDuplicateSeventhOrOversizeFramesFailBoundedlyAndNeverRetry() {
        val a = authority()
        for (index in listOf(1, 5, 6)) {
            val e = CurrentLiveFullParityEvidence(a.native.runId, id, false); var reads = 0
            assertThrows(IllegalStateException::class.java) {
                e.capture(index, a, 1000, { 100 }, { true }, { reads++; native(a).toString() }, { a })
            }
            assertEquals(0, reads)
        }
        val e = CurrentLiveFullParityEvidence(a.native.runId, id, false); capture(e)
        assertThrows(IllegalStateException::class.java) { capture(e, index = 0) }
        assertEquals(1, e.snapshot().getValue("frameCount").jsonPrimitive.int)
        val oversized = CurrentLiveFullParityEvidence(a.native.runId, id, false)
        assertThrows(IllegalStateException::class.java) { capture(oversized, raw = " ".repeat(16 * 1024 + 1)) }
        val seventh = CurrentLiveFullParityEvidence(a.native.runId, id, false)
        repeat(6) { capture(seventh, index = it) }
        assertThrows(IllegalStateException::class.java) { capture(seventh, index = 6) }
        assertEquals(6, seventh.snapshot().getValue("frameCount").jsonPrimitive.int)
        assertFalse(seventh.snapshot().getValue("complete").jsonPrimitive.boolean)
    }
    @Test fun permissionCancellationAndDeadlinePreventReadbackAndLateResultsAreNotRetained() {
        val a = authority()
        for (allowed in listOf(false, true)) {
            val e = CurrentLiveFullParityEvidence(a.native.runId, id, false); var reads = 0
            assertThrows(IllegalStateException::class.java) {
                e.capture(0, a, 100, { 100 }, { allowed }, { reads++; native(a).toString() }, { a })
            }
            assertEquals(0, reads)
        }
        val e = CurrentLiveFullParityEvidence(a.native.runId, id, false); var allowed = true
        assertThrows(IllegalStateException::class.java) {
            e.capture(0, a, 1000, { 100 }, { allowed }, { allowed = false; native(a).toString() }, { a })
        }
        assertEquals(0, e.snapshot().getValue("frameCount").jsonPrimitive.int)
    }
}
