package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RendererLiveInputBufferTest {
    @Test fun reserveMetadataDistinguishesProducerPixelsFromVisibleCameraAndControlPadding() {
        val visible = ChartSurfaceGeometry.fit(2000, 1070)
        val geometry = ChartRasterGeometry.reserve(visible)
        val camera = ChartViewport(width = 2000, height = 1070)
        val frame = ChartFrame(1, "public", visible, camera)
        val b = RendererLiveInputBuffer("raster")
        b.buffer(ChartBufferSnapshot(1, 1, 1, frame, raster = ChartBufferRaster.capture(geometry, camera, false)), false)
        b.buffer(ChartBufferSnapshot(2, 1, 2, frame, raster = ChartBufferRaster.capture(geometry, camera, true)), false)
        val report = b.finishReport()
        val rows = report.getValue("rows").jsonArray.filter { it.jsonArray[0].jsonPrimitive.content == "BUFFER_RASTER" }
        assertEquals(2, rows.size)
        val control = rows[0].jsonArray[7].jsonArray.map { it.jsonPrimitive.double }
        val reserve = rows[1].jsonArray[7].jsonArray.map { it.jsonPrimitive.double }
        assertEquals(listOf(2500.0, 1338.0, 250.0, 134.0, 2000.0, 1070.0), control.take(6))
        assertEquals(listOf(2500.0, 1338.0, 0.0, 0.0, 2500.0, 1338.0), reserve.take(6))
        assertEquals(listOf(2000.0, 1070.0, 2000.0, 1070.0), reserve.takeLast(4))
        assertEquals("VISIBLE_CAMERA_AFFINE_BEFORE_STATIC_PRODUCER_QUEUE_CROP", report.getValue("transformSetConvention").jsonPrimitive.content)
    }
    private fun input(buffer: RendererLiveInputBuffer) = buffer.input(100, 200, 202, 101, 2, 7, -1, 1,
        intArrayOf(7), floatArrayOf(10f, 20f), RendererInputOrigin.VIEW_INJECTED)
    @Test fun capacityLossIsExplicitAndLateCallbacksCannotChangeFrozenReport() {
        val b = RendererLiveInputBuffer("run", maxRows = 3, maxInputs = 1)
        input(b); input(b); b.disposition(1, "COALESCED_CAMERA", 2); b.disposition(2, "LIMIT")
        val report = b.finishReport()
        assertTrue(report.getValue("dropped").jsonPrimitive.long > 0)
        assertFalse(report.getValue("storageCoverageComplete").jsonPrimitive.boolean)
        assertEquals(0L, input(b))
        b.replay("LATE")
        assertEquals(report, b.finishReport())
    }
    @Test fun textureJoinUsesExactSubmittedTagAndNeverPromotesUnknownToKnown() {
        val b = RendererLiveInputBuffer("run")
        val geometry = ChartSurfaceGeometry.fit(1000, 600)
        val buffer = ChartBufferSnapshot(123, 4, 9, ChartFrame(1, "x", geometry, ChartViewport(width=1000, height=600)))
        b.buffer(buffer, false); b.texture(456, true, "UPDATE_CALLBACK"); b.texture(123, true, "NEXT_ANIMATION"); b.texture(123, false, "UPDATE_CALLBACK")
        val report = b.finishReport()
        assertEquals(2L, report.getValue("unknownTags").jsonPrimitive.long)
        assertEquals(0L, b.uiBasisStamp)
        val uptake = report.getValue("rows").jsonArray.filter { it.jsonArray[0].jsonPrimitive.content == "TEXTURE_TAG_SAMPLE" }
        assertEquals(listOf("UPDATE_CALLBACK:false", "NEXT_ANIMATION:true", "UPDATE_CALLBACK:false"), uptake.map { it.jsonArray[6].jsonPrimitive.content })
        assertEquals("TIMESTAMP_SAMPLE_ONLY_NOT_ATOMIC_WITH_HWUI_DRAW", report.getValue("textureCameraAssociation").jsonPrimitive.content)
        assertFalse(report.getValue("physicalDisplayLatencyMeasured").jsonPrimitive.boolean)
        assertEquals(JsonNull, report.getValue("inputToVisibleP95Ms"))
    }
    @Test fun unknownNextAnimationSampleCannotBecomeAValidLiveComparison() {
        val b = RendererLiveInputBuffer("unknown")
        b.startLive(); val id = input(b); b.camera(id, 1, 1, ChartViewport(), false)
        b.texture(99, true, "NEXT_ANIMATION")
        assertEquals("Unknown live texture basis", b.liveValidityError())
    }
    @Test fun nestedInputContextAndReplayClaimAreBoundToOneRun() {
        val b = RendererLiveInputBuffer("run")
        assertTrue(b.claimReplay()); assertFalse(b.claimReplay())
        b.withInput(1) { b.withInput(2) { assertEquals(2L, b.currentInput()) }; assertEquals(1L, b.currentInput()) }
        assertEquals(0L, b.currentInput())
        try { b.withInput(3) { throw IllegalStateException("test") } } catch (_: IllegalStateException) { }
        assertEquals(0L, b.currentInput())
        b.finishReport(); assertFalse(b.claimReplay())
    }
    @Test fun storageOrRejectedLiveInputCannotBeAcceptedAsValidComparison() {
        val b = RendererLiveInputBuffer("run")
        b.startLive(); val id = input(b)
        b.camera(id, 1, 1, ChartViewport(), false)
        assertNull(b.liveValidityError())
        b.disposition(id, "BLOCKED_REGION")
        assertEquals("Input stream rejected or cancelled", b.liveValidityError())
        val lost = RendererLiveInputBuffer("lost", maxRows = 1)
        lost.startLive(); input(lost)
        assertEquals("Bounded observation capacity exceeded", lost.liveValidityError())
    }
}
