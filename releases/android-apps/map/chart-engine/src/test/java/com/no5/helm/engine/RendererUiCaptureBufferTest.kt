package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RendererUiCaptureBufferTest {
    @Test fun primitiveStorageIsBoundedAndUnavailableCpuStaysNull() {
        val buffer = RendererUiCaptureBuffer(30, System.nanoTime())
        repeat(35) { buffer.record(RendererUiPhase.WINDOW_TOTAL, (it + 1) * 1_000_000L) }
        buffer.record(RendererUiPhase.WINDOW_GPU, -1)
        buffer.windowFrame(true, 3)
        val report = buffer.finishReport()
        val total = report.getValue("summary").jsonObject.getValue("window.total").jsonObject
        assertEquals(30, total.getValue("samples").jsonPrimitive.int)
        assertEquals(5, total.getValue("droppedAtCapacity").jsonPrimitive.int)
        assertEquals(JsonNull, total.getValue("threadCpuMs"))
        assertEquals(15.5, total.getValue("wallMs").jsonObject.getValue("median").jsonPrimitive.double, 0.0)
        assertEquals(3, report.getValue("platformDroppedReports").jsonPrimitive.int)
        assertEquals(1, report.getValue("windowFirstDraws").jsonPrimitive.int)
        assertEquals(JsonNull, report.getValue("summary").jsonObject.getValue("window.gpuApi31").jsonObject.getValue("wallMs"))
    }
    @Test fun stoppingFreezesOldCallbacksAndNewSessionUsesItsOwnStorage() {
        val old = RendererUiCaptureBuffer(30, System.nanoTime())
        val producer = Thread { repeat(100) { old.record(RendererUiPhase.CHART_NODE_DRAW_DISPATCH, 1000, 500) } }
        producer.start(); producer.join()
        val frozen = old.finishReport()
        val current = RendererUiCaptureBuffer(30, System.nanoTime())
        old.record(RendererUiPhase.WINDOW_TOTAL, 999999); old.windowFrame(false, 100); old.textureUpdated()
        current.record(RendererUiPhase.WINDOW_TOTAL, 2000000)
        assertEquals(frozen, old.finishReport())
        assertEquals(1, current.finishReport().getValue("summary").jsonObject.getValue("window.total").jsonObject.getValue("samples").jsonPrimitive.int)
        assertTrue(frozen.getValue("phasesNonAdditive").jsonPrimitive.boolean)
    }
    @Test fun callbacksFromSeveralThreadsHaveNoLostOrCrossPhaseSamples() {
        val buffer = RendererUiCaptureBuffer(1200, System.nanoTime())
        val threads = (1..4).map { Thread { repeat(100) { buffer.record(RendererUiPhase.WINDOW_TOTAL, 1000000); buffer.record(RendererUiPhase.CHART_NODE_MEASURE, 2000000, 500000) } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        val report = buffer.finishReport(); val summary = report.getValue("summary").jsonObject
        assertEquals(400, summary.getValue("window.total").jsonObject.getValue("samples").jsonPrimitive.int)
        assertEquals(400, summary.getValue("chartNode.measure").jsonObject.getValue("samples").jsonPrimitive.int)
        assertEquals(.5, summary.getValue("chartNode.measure").jsonObject.getValue("threadCpuMs").jsonObject.getValue("mean").jsonPrimitive.double, 0.0)
        assertFalse(report.toString().contains("latitude"))
    }
}
