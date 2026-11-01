package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ViewportReuseParityEvidenceTest {
    private val viewport = ChartViewport(40.0, -70.0, .01, .2, 1000, 600)
    private val authority = ViewportReuseParityAuthority("test-run", "a".repeat(64), 1, 2, 3, 4, 5, 6, 7, 8, 9, viewport)
    private fun native(a: ViewportReuseParityAuthority = authority) = buildJsonObject {
        put("schema", "no5.viewport-reuse-parity/1"); put("runId", a.runId); put("frame", a.nativeFrame)
        put("catalogGeneration", a.catalogGeneration); put("contentRevision", a.nativeContentRevision)
        put("dataGeneration", a.nativeDataGeneration); put("presentationGeneration", a.presentationGeneration)
        put("filterRevision", 10); put("overlayRevision", 11); put("presentationSlot", 0)
        put("latitude", a.viewport.latitude); put("longitude", a.viewport.longitude); put("ppm", a.viewport.metresToPixels)
        put("rotation", a.viewport.rotationRadians); put("width", a.viewport.width); put("height", a.viewport.height)
        put("rgbaSHA256", "1".repeat(64)); put("rgbaBytes", a.viewport.width.toLong() * a.viewport.height * 4)
        put("semanticSHA256", "2".repeat(64)); put("associationsSHA256", "3".repeat(64)); put("featureCount", 42)
        put("normalization", "NONE"); put("extraDraws", 0); put("extraSwaps", 0); put("consumerAcknowledgements", 0)
        put("pixelSource", "completed native output FBO, full 1:1 RGBA; no post-swap window read")
        put("readback", "blocking CPU diagnostic outside timing; not display/GPU time")
        put("queryRecipe", "viewport-center-five/1: x=w/2+(i-2)*w/8, y=h/2+(i%2)*h/8, radius=24, offset=0")
        put("queries", JsonArray((0..4).map { i -> buildJsonObject {
            put("x", a.viewport.width * .5 + (i - 2) * a.viewport.width * .125)
            put("y", a.viewport.height * .5 + (i % 2) * a.viewport.height * .125)
            put("radius", 24); put("offset", 0); put("jsonBytes", 2); put("sha256", "4".repeat(64))
        } }))
        put("status", buildJsonObject {
            put("dataGeneration", a.nativeDataGeneration); put("latitude", a.viewport.latitude); put("longitude", a.viewport.longitude)
            put("ppm", a.viewport.metresToPixels); put("rotation", a.viewport.rotationRadians)
            put("width", a.viewport.width); put("height", a.viewport.height); put("coverageMasked", false)
            for (key in listOf("filteredFeatures", "viewportSelectedCharts", "coverageUnknownCells", "coverageUncoveredPixels",
                "selectedCharts", "featureCount", "unsupportedFeatures")) put(key, 0)
            put("featureCount", 42)
            put("objectSelection", buildJsonObject { put("sourceKey", "synthetic-exact-source"); put("featureIndex", -1); put("visible", false); put("segments", 0) })
        })
    }
    private fun capture(e: ViewportReuseParityEvidence, step: Int = 0, a: ViewportReuseParityAuthority = authority,
        after: ViewportReuseParityAuthority = a, raw: String = native(a).toString()) =
        e.capture(step, a, 1_000, { 100 }, { true }, { raw }, { after })

    @Test fun sixExactFramesRetainOriginalNativeHashesStatusAndFiveQueriesWithoutDisplayOrTimingClaim() {
        val evidence = ViewportReuseParityEvidence(authority.runId)
        repeat(6) { capture(evidence, it, authority.copy(nativeFrame = 5L + it, fullBufferStamp = 9L + it)) }
        val report = evidence.snapshot()
        assertTrue(report.getValue("complete").jsonPrimitive.boolean)
        assertEquals(6, report.getValue("frameCount").jsonPrimitive.int)
        assertFalse(report.getValue("timingComparable").jsonPrimitive.boolean)
        assertFalse(report.getValue("rawRgbaPersisted").jsonPrimitive.boolean)
        assertEquals("NOT_MEASURED", report.getValue("physicalInputToDisplay").jsonPrimitive.content)
        val first = report.getValue("frames").jsonArray.first().jsonObject.getValue("native").jsonObject
        assertEquals(native(), first)
        assertEquals(5, first.getValue("queries").jsonArray.size)
    }

    @Test fun stalePartialSourceContentCameraViewportOrSurfaceGeometryCannotBecomeFull() {
        val geometry = ChartSurfaceGeometry.fit(1000, 600)
        val frame = ChartFrame(1, "test", geometry, viewport, sourceGeneration = 1, contentGeneration = 3)
        val full = ChartBufferSnapshot(9, 1, 4, frame, true)
        fun accepted(b: ChartBufferSnapshot? = full, g: ChartSurfaceGeometry = geometry) =
            viewportReuseParityFullMatches(b, viewport, 4, g, 1, 3)
        assertTrue(accepted())
        assertFalse(accepted(null)); assertFalse(accepted(full.copy(producerCompleted = false)))
        assertFalse(accepted(full.copy(stage = ChartContentStage.BASE_AREAS)))
        assertFalse(accepted(full.copy(stage = ChartContentStage.REUSED_DETAILS_WITH_BASE)))
        assertFalse(accepted(full.copy(cameraRevision = 3)))
        assertFalse(accepted(full.copy(frame = frame.copy(sourceGeneration = 2))))
        assertFalse(accepted(full.copy(frame = frame.copy(contentGeneration = 4))))
        assertFalse(accepted(full.copy(frame = frame.copy(viewport = viewport.copy(longitude = -69.9)))))
        assertFalse(accepted(g = ChartSurfaceGeometry.fit(1200, 600)))
    }

    @Test fun anyAuthorityChangeDuringCaptureFailsInsteadOfPublishingOrRetrying() {
        val changed = listOf(authority.copy(runId = "other-run"), authority.copy(catalogGeneration = "b".repeat(64)),
            authority.copy(sourceGeneration = 2), authority.copy(surfaceGeneration = 3), authority.copy(contentGeneration = 4),
            authority.copy(cameraRevision = 5), authority.copy(nativeFrame = 6), authority.copy(nativeDataGeneration = 7),
            authority.copy(nativeContentRevision = 8), authority.copy(presentationGeneration = 9), authority.copy(fullBufferStamp = 10),
            authority.copy(viewport = viewport.copy(rotationRadians = .3)))
        for (after in changed) {
            val evidence = ViewportReuseParityEvidence(authority.runId); var calls = 0
            assertThrows(IllegalStateException::class.java) {
                evidence.capture(0, authority, 1_000, { 100 }, { true }, { calls++; native().toString() }, { after })
            }
            assertEquals(1, calls); assertFalse(evidence.snapshot().getValue("complete").jsonPrimitive.boolean)
            assertEquals(0, evidence.snapshot().getValue("frameCount").jsonPrimitive.int)
            assertThrows(IllegalStateException::class.java) { capture(evidence) }
        }
    }

    @Test fun nativeRevisionQueryPixelOrStatusMismatchIsNeverNormalized() {
        val changes = listOf("frame" to JsonPrimitive(99), "catalogGeneration" to JsonPrimitive("b".repeat(64)),
            "dataGeneration" to JsonPrimitive(99), "contentRevision" to JsonPrimitive(99), "presentationGeneration" to JsonPrimitive(99),
            "latitude" to JsonPrimitive(39), "width" to JsonPrimitive(999), "rgbaBytes" to JsonPrimitive(4),
            "rgbaSHA256" to JsonPrimitive("bad"), "normalization" to JsonPrimitive("NORMALIZED"), "extraDraws" to JsonPrimitive(1),
            "queries" to JsonArray(native().getValue("queries").jsonArray.take(4)), "status" to JsonObject(emptyMap()))
        for ((key, value) in changes) {
            val corrupt = JsonObject(native().toMutableMap().apply { put(key, value) })
            val evidence = ViewportReuseParityEvidence(authority.runId)
            assertThrows(Exception::class.java) { capture(evidence, raw = corrupt.toString()) }
            assertEquals(0, evidence.snapshot().getValue("frameCount").jsonPrimitive.int)
        }
    }

    @Test fun truncatedOversizeOrWrongSchemaReportsFailClosedAndPreservePartialEvidenceOnly() {
        for (raw in listOf(native().toString().dropLast(1), " ".repeat(ViewportReuseParityEvidence.MAX_NATIVE_JSON_BYTES + 1),
            JsonObject(native().toMutableMap().apply { put("schema", JsonPrimitive("unknown")) }).toString())) {
            val evidence = ViewportReuseParityEvidence(authority.runId); capture(evidence)
            assertThrows(Exception::class.java) { capture(evidence, 1, raw = raw) }
            assertFalse(evidence.snapshot().getValue("complete").jsonPrimitive.boolean)
            assertEquals(1, evidence.snapshot().getValue("frameCount").jsonPrimitive.int)
        }
    }

    @Test fun missingDuplicateSkippedOrSeventhPoseCannotProduceACompletePartialReport() {
        for (step in listOf(1, 5, 6)) {
            val evidence = ViewportReuseParityEvidence(authority.runId)
            assertThrows(IllegalStateException::class.java) { capture(evidence, step) }
            assertFalse(evidence.snapshot().getValue("complete").jsonPrimitive.boolean)
        }
        val evidence = ViewportReuseParityEvidence(authority.runId)
        repeat(6) { capture(evidence, it) }
        assertThrows(IllegalStateException::class.java) { capture(evidence, 6) }
        assertFalse(evidence.snapshot().getValue("complete").jsonPrimitive.boolean)
    }

    @Test fun preCancelledOrExpiredCaptureDoesNoNativeWorkAndLateOrCancelledResultIsNotRetained() {
        for (allowed in listOf(false, true)) {
            val evidence = ViewportReuseParityEvidence(authority.runId); var calls = 0
            assertThrows(IllegalStateException::class.java) {
                evidence.capture(0, authority, if (allowed) 100 else 1_000, { 100 }, { allowed },
                    { calls++; native().toString() }, { authority })
            }
            assertEquals(0, calls)
        }
        for (cancel in listOf(false, true)) {
            val evidence = ViewportReuseParityEvidence(authority.runId); var called = false
            assertThrows(IllegalStateException::class.java) {
                evidence.capture(0, authority, 1_000, { if (called && !cancel) 1_000 else 100 }, { !called || !cancel },
                    { called = true; native().toString() }, { authority })
            }
            assertFalse(evidence.snapshot().getValue("complete").jsonPrimitive.boolean)
            assertEquals(0, evidence.snapshot().getValue("frameCount").jsonPrimitive.int)
        }
    }

    @Test fun fullWaitCannotMoveTheNextOriginalPoseOrExceedOneSecond() {
        val start = 5_000_000_000L
        assertEquals(start + 1_000_000_000L, viewportReuseParityDeadlineNs(start, start, 2_800))
        assertEquals(start + 2_800_000_000L, viewportReuseParityDeadlineNs(start + 2_500_000_000L, start, 2_800))
        val expired = viewportReuseParityDeadlineNs(start + 3_000_000_000L, start, 2_800)
        assertTrue(expired < start + 3_000_000_000L)
        assertThrows(ArithmeticException::class.java) { viewportReuseParityDeadlineNs(Long.MAX_VALUE, start, 2_800) }
    }
}
