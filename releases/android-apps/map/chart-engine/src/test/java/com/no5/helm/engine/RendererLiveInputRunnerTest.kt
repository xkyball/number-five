package com.no5.helm.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RendererLiveInputRunnerTest {
    private class Fake : RendererLiveInputBackend {
        val calls = mutableListOf<String>(); var ready = false; var cancel = false; var restoreFails = false; var completionError = ""
        var endFailure: Exception? = null
        override suspend fun prepare() = buildJsonObject { calls += "prepare" }
        override fun checkReady() { if (cancel) error("cancelled") }
        override fun baseReady() = ready
        override fun begin() { calls += "begin" }
        override fun end() = buildJsonObject { calls += "end"; endFailure?.let { throw it }; put("completionError", completionError) }
        override suspend fun restore(): Boolean { calls += "restore"; if (restoreFails) error("restore failed"); return true }
    }
    @Test fun dedicatedParityMeasurementKeepsOriginalWarmEndAndRecoveryButDoesNotRunATimingWindow() = runBlocking {
        val b = Fake(); b.ready = true; var now = 0L; var measurementCalls = 0
        val report = RendererLiveInputRunner(b, { now }) { now += 25 }.run(15_000) {
            measurementCalls++; b.calls += "parity"
        }
        assertEquals(1, measurementCalls); assertEquals(0L, now)
        assertEquals(listOf("prepare", "begin", "parity", "end", "restore"), b.calls)
        assertEquals("completed", report.getValue("outcome").jsonPrimitive.content)
    }
    @Test fun dedicatedParityReadFailureStillEndsCaptureAndRestoresWithoutAnotherAttempt() = runBlocking {
        val b = Fake(); b.ready = true; var calls = 0
        val report = RendererLiveInputRunner(b, { 0L }).run(15_000) { calls++; error("exact FULL changed") }
        assertEquals(1, calls); assertEquals("aborted", report.getValue("outcome").jsonPrimitive.content)
        assertEquals("exact FULL changed", report.getValue("error").jsonPrimitive.content)
        assertEquals(listOf("prepare", "begin", "end", "restore"), b.calls)
        assertTrue(report.getValue("restored").jsonPrimitive.boolean)
    }
    @Test fun aFreezeLostAtEndCanNeverProduceACompletedPartialReportAndStillRestores() = runBlocking {
        val b = Fake(); b.ready = true; b.completionError = "SOURCE_FREEZE_LOST"; var now = 0L
        val report = RendererLiveInputRunner(b, { now }) { now += 25 }.run(50)
        assertEquals("aborted", report.getValue("outcome").jsonPrimitive.content)
        assertEquals("SOURCE_FREEZE_LOST", report.getValue("error").jsonPrimitive.content)
        assertTrue(report.getValue("restored").jsonPrimitive.boolean)
        assertEquals(listOf("prepare", "begin", "end", "restore"), b.calls)
    }
    @Test fun waitsForActualWarmBasisAndRestoresAfterLiveWindow() = runBlocking {
        val b = Fake(); var now = 0L
        val report = RendererLiveInputRunner(b, { now }) { now += 25; b.ready = now >= 100 }.run(50)
        assertEquals(150L, now)
        assertEquals(listOf("prepare", "begin", "end", "restore"), b.calls)
        assertEquals("completed", report.getValue("outcome").jsonPrimitive.content)
        assertTrue(report.getValue("restored").jsonPrimitive.boolean)
    }
    @Test fun anEndExceptionWithoutAMessageStillAbortsAndRestores() = runBlocking {
        val b = Fake(); b.ready = true; b.endFailure = IllegalStateException(); var now = 0L
        val report = RendererLiveInputRunner(b, { now }) { now += 25 }.run(50)
        assertEquals("aborted", report.getValue("outcome").jsonPrimitive.content)
        assertEquals("IllegalStateException", report.getValue("error").jsonPrimitive.content)
        assertTrue(report.getValue("restored").jsonPrimitive.boolean)
        assertEquals(listOf("prepare", "begin", "end", "restore"), b.calls)
    }
    @Test fun cancellationBeforeLiveStillEndsCaptureAndRestores() = runBlocking {
        val b = Fake(); b.cancel = true
        val report = RendererLiveInputRunner(b, { 0L }).run(50)
        assertEquals(listOf("prepare", "end", "restore"), b.calls)
        assertEquals("aborted", report.getValue("outcome").jsonPrimitive.content)
        assertTrue(report.getValue("restored").jsonPrimitive.boolean)
    }
    @Test fun warmTimeoutAndRecoveryFailureAreReportedSeparately() = runBlocking {
        val b = Fake(); b.restoreFails = true; var now = 0L
        val report = RendererLiveInputRunner(b, { now }) { now += 5000 }.run(50)
        assertEquals(listOf("prepare", "end", "restore"), b.calls)
        assertFalse(report.getValue("restored").jsonPrimitive.boolean)
        assertTrue(report.getValue("error").jsonPrimitive.content.contains("15 seconds"))
        assertEquals("restore failed", report.getValue("restorationError").jsonPrimitive.content)
    }
}
