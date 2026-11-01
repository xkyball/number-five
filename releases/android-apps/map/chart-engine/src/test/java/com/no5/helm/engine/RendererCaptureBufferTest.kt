package com.no5.helm.engine

import com.no5.helm.chart.RendererCaptureConfig
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class RendererCaptureBufferTest {
    private val empty = buildJsonObject {}
    private fun report(buffer: RendererCaptureBuffer) = buffer.report(empty, "test", 1000.0)
    @Test fun configurationRejectsUnboundedOrInvalidCaptures() {
        for (config in listOf<() -> Unit>({ RendererCaptureConfig(durationSeconds = 0) }, { RendererCaptureConfig(maxFrames = 1000000) }, { RendererCaptureConfig(slowFrameMs = Double.NaN) })) {
            try { config(); fail("must reject") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun summaryExcludesWarmupAndComputesNearestRankTails() {
        val buffer = RendererCaptureBuffer(RendererCaptureConfig(maxFrames = 30), 0, warmupFrames = 2)
        for (n in listOf(900.0, 800.0, 1.0, 2.0, 3.0, 4.0)) buffer.record(mapOf("workerFrameWallMs" to n), empty)
        val metric = report(buffer).getValue("summary").jsonObject.getValue("workerFrameWallMs").jsonObject
        assertEquals(4, metric.getValue("samples").jsonPrimitive.int)
        assertEquals(2.5, metric.getValue("median").jsonPrimitive.double, 0.0)
        assertEquals(4.0, metric.getValue("p95").jsonPrimitive.double, 0.0)
        assertEquals(4.0, metric.getValue("p99").jsonPrimitive.double, 0.0)
    }
    @Test fun missingCpuIsAbsentAndNestedDetailsRemainSeparate() {
        val buffer = RendererCaptureBuffer(RendererCaptureConfig(), 0)
        val native = Json.parseToJsonElement("""{"stages":{"objects":{"wallMs":5,"cpuMs":null}},"details":{"text":{"wallMs":4,"cpuMs":2},"symbols":{"wallMs":3,"cpuMs":1}}}""").jsonObject
        buffer.record(mapOf("workerFrameWallMs" to 7.0, "bad" to Double.NaN), native)
        val result = report(buffer); val metrics = result.getValue("summary").jsonObject
        assertFalse(metrics.containsKey("native.stages.objects.cpuMs")); assertFalse(metrics.containsKey("bad"))
        assertEquals(5.0, metrics.getValue("native.stages.objects.wallMs").jsonObject.getValue("mean").jsonPrimitive.double, 0.0)
        assertTrue(result.getValue("detailsNonAdditive").jsonPrimitive.boolean)
    }
    @Test fun s01TransitionDetailsSurviveCaptureWithSparseNormalAndDeepCpuSamples() {
        val names = listOf("S01 active texture", "S01 input texture bind", "S01 input sampling filter",
            "S01 output object creation", "S01 output framebuffer bind", "S01 output storage/attachment")
        val buffer = RendererCaptureBuffer(RendererCaptureConfig(), 0)
        fun native(cpu: JsonElement) = buildJsonObject {
            put("details", buildJsonObject { names.forEach { name -> put(name, buildJsonObject {
                put("wallMs", 2.0); put("cpuMs", cpu); put("calls", 1)
            }) } })
        }
        buffer.record(emptyMap(), native(JsonNull)) // Normal detail sample: wall only.
        buffer.record(emptyMap(), buildJsonObject { put("details", buildJsonObject {}) }) // Unsampled.
        buffer.record(emptyMap(), native(JsonPrimitive(0.5))) // Deep detail sample.
        val result = report(buffer); val summary = result.getValue("summary").jsonObject
        names.forEach { name ->
            assertEquals(2, summary.getValue("native.details.$name.wallMs").jsonObject.getValue("samples").jsonPrimitive.int)
            assertEquals(1, summary.getValue("native.details.$name.cpuMs").jsonObject.getValue("samples").jsonPrimitive.int)
            assertEquals(0.5, summary.getValue("native.details.$name.cpuMs").jsonObject.getValue("mean").jsonPrimitive.double, 0.0)
            assertEquals(1.0, summary.getValue("native.details.$name.calls").jsonObject.getValue("mean").jsonPrimitive.double, 0.0)
        }
        assertTrue(result.getValue("detailsNonAdditive").jsonPrimitive.boolean)
    }
    @Test fun fullNativeDeepSampleAndWorkerMetricsDoNotTruncateNewS01Details() {
        // Real host GLES deep sample; device MAP42 worker metric names, with synthetic values.
        val sample = checkNotNull(javaClass.getResource("/renderer-s01-deep-sample.json")).readText()
        val native = buildJsonObject {
            Json.parseToJsonElement(sample).jsonObject.forEach { (key, value) -> put(key, value) }
            put("landWorker", buildJsonObject { put("dropped", 0); put("jobs", buildJsonArray {}) })
        }
        val names = listOf("betweenWorkerFramesWallMs", "cameraSamplesCoalesced", "cameraApplyWallMs",
            "cameraApplyCpuMs", "overlayApplyWallMs", "overlayApplyCpuMs", "jniRenderWallMs", "jniRenderCpuMs",
            "statusReadWallMs", "statusReadCpuMs", "statusParseWallMs", "statusParseCpuMs", "scaleReadWallMs",
            "scaleReadCpuMs", "statePublishWallMs", "statePublishCpuMs", "workerFrameWallMs", "workerFrameCpuMs",
            "outsideNativeWallMs", "workerUnattributedWallMs", "jniOutsideNativeFrameWallMs",
            "diagnosticsReadWallMs", "diagnosticsReadCpuMs")
        val buffer = RendererCaptureBuffer(RendererCaptureConfig(detailed = true), 0)
        buffer.record(names.associateWith { 1.0 }, native)
        buffer.updateLast(mapOf("captureProbeWallMs" to 1.0, "captureProbeCpuMs" to 1.0))
        val result = report(buffer); val summary = result.getValue("summary").jsonObject
        assertEquals(0, result.getValue("droppedMetricValues").jsonPrimitive.int)
        assertTrue(summary.size > 256)
        for (name in listOf("S01 active texture", "S01 input texture bind", "S01 input sampling filter",
            "S01 output object creation", "S01 output framebuffer bind", "S01 output storage/attachment")) {
            for (unit in listOf("wallMs", "cpuMs", "calls")) assertTrue(summary.containsKey("native.details.$name.$unit"))
        }
        assertTrue(summary.containsKey("captureProbeWallMs")); assertTrue(summary.containsKey("captureProbeCpuMs"))
    }
    @Test fun storageStopsAtFrameCapAndRetainsOnlyFiveSlowestFrames() {
        val buffer = RendererCaptureBuffer(RendererCaptureConfig(maxFrames = 30, slowFrameMs = 1.0), 0)
        repeat(30) { buffer.record(mapOf("workerFrameWallMs" to it.toDouble()), empty) }
        assertTrue(buffer.full)
        try { buffer.record(emptyMap(), empty); fail("must stop at cap") } catch (_: IllegalStateException) {}
        val slow = report(buffer).getValue("slowFrames").jsonArray
        assertEquals(5, slow.size); assertEquals(29, slow.first().jsonObject.getValue("sequence").jsonPrimitive.int)
    }
    @Test fun timeoutProbeOverheadAndEventLimitsAreExplicit() {
        val buffer = RendererCaptureBuffer(RendererCaptureConfig(durationSeconds = 5), 100)
        assertFalse(buffer.expired(5_000_000_099)); assertTrue(buffer.expired(5_000_000_100))
        repeat(70) { buffer.event("ENC open", 1.0, .5) }
        buffer.record(mapOf("workerFrameWallMs" to 2.0), empty)
        buffer.updateLast(mapOf("captureProbeWallMs" to .25))
        val result = report(buffer)
        assertEquals(64, result.getValue("events").jsonArray.size); assertEquals(6, result.getValue("droppedEvents").jsonPrimitive.int)
        assertEquals(.25, result.getValue("summary").jsonObject.getValue("captureProbeWallMs").jsonObject.getValue("median").jsonPrimitive.double, 0.0)
    }
    @Test fun anotherThreadCannotMutateTheWorkerOwnedBuffer() {
        val buffer = RendererCaptureBuffer(RendererCaptureConfig(), 0)
        val failure = AtomicReference<Throwable?>()
        val thread = Thread { try { buffer.record(emptyMap(), empty) } catch (e: Throwable) { failure.set(e) } }
        thread.start(); thread.join()
        assertTrue(failure.get() is IllegalStateException); assertEquals(0, buffer.frames)
    }
    @Test fun delayedGpuResultsUseSourceFramesAndExcludeWarmup() {
        val buffer = RendererCaptureBuffer(RendererCaptureConfig(maxFrames = 30), 0, warmupFrames = 1)
        fun native(frame: Long, samples: String = "[]") = Json.parseToJsonElement("""{"frame":$frame,"gpuSamples":$samples}""").jsonObject
        buffer.record(emptyMap(), native(10))
        buffer.record(emptyMap(), native(11, """[{"frame":10,"durationMs":100}]"""))
        buffer.record(emptyMap(), native(12, """[{"frame":11,"durationMs":5},{"frame":12,"durationMs":7},{"frame":999,"durationMs":9}]"""))
        buffer.record(emptyMap(), native(13, """[{"frame":11,"durationMs":5}]"""))
        val result = report(buffer)
        val samples = result.getValue("gpuSamples").jsonArray
        assertEquals(2, samples.size)
        assertEquals(11L, samples[0].jsonObject.getValue("frame").jsonPrimitive.long)
        assertEquals(1, samples[0].jsonObject.getValue("sourceSequence").jsonPrimitive.int)
        assertEquals(2, samples[0].jsonObject.getValue("receivedSequence").jsonPrimitive.int)
        assertEquals(6.0, result.getValue("gpuSummary").jsonObject.getValue("median").jsonPrimitive.double, 0.0)
        assertEquals(1, result.getValue("droppedGpuSamples").jsonPrimitive.int)
        assertFalse(result.getValue("summary").jsonObject.containsKey("native.gpuSamples.durationMs"))
    }
    @Test fun unavailableGpuResultsStayNullAndInvalidSamplesAreIgnored() {
        val buffer = RendererCaptureBuffer(RendererCaptureConfig(maxFrames = 30), 0)
        buffer.record(emptyMap(), Json.parseToJsonElement("""{"frame":1,"gpuDurationMs":null,"gpuSamples":[{"frame":1,"durationMs":-2},{"frame":1,"durationMs":"NaN"},{}]}""").jsonObject)
        val result = report(buffer)
        assertEquals(JsonNull, result.getValue("gpuSummary"))
        assertTrue(result.getValue("gpuSamples").jsonArray.isEmpty())
        assertFalse(result.getValue("summary").jsonObject.containsKey("native.gpuDurationMs"))
        assertEquals("unavailable", result.getValue("gpuAvailability").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(0, result.getValue("gpuAvailability").jsonObject.getValue("validMeasuredSamples").jsonPrimitive.int)
    }

    @Test fun landJobsAreIndependentAndSeparateCancelledWorkFromCompletedWork() {
        val buffer = RendererCaptureBuffer(RendererCaptureConfig(maxFrames = 30), 0)
        fun record(id: Int, outcome: String, wall: Double, calls: Int = 1) {
            buffer.record(emptyMap(), Json.parseToJsonElement("""{"landWorker":{"dropped":2,"jobs":[{"jobId":$id,"outcome":"$outcome","phases":{"total":{"calls":1,"wallMs":$wall,"cpuMs":null},"raster":{"calls":$calls,"wallMs":1,"cpuMs":0.5}}}]}}""").jsonObject)
        }
        record(1, "complete", 10.0); record(1, "complete", 10.0); record(2, "cancelled", 2.0, 0)
        val result = report(buffer); val summary = result.getValue("landSummary").jsonObject
        assertEquals(2, result.getValue("landJobs").jsonArray.size)
        assertEquals(1, summary.getValue("complete.total.wallMs").jsonObject.getValue("samples").jsonPrimitive.int)
        assertEquals(2.0, summary.getValue("cancelled.total.wallMs").jsonObject.getValue("mean").jsonPrimitive.double, 0.0)
        assertFalse(summary.containsKey("cancelled.raster.wallMs")); assertFalse(summary.containsKey("complete.total.cpuMs"))
        assertEquals(2, result.getValue("nativeDroppedLandJobs").jsonPrimitive.int)
        assertFalse(result.getValue("summary").jsonObject.containsKey("native.landWorker.jobs.total.wallMs"))
    }
    @Test fun landCompletionStorageIsBoundedAcrossManyJobs() {
        val buffer = RendererCaptureBuffer(RendererCaptureConfig(maxFrames = 30), 0)
        val jobs = (1..70).joinToString(",") { """{"jobId":$it,"outcome":"complete","phases":{}}""" }
        buffer.record(emptyMap(), Json.parseToJsonElement("""{"landWorker":{"jobs":[$jobs]}}""").jsonObject)
        val result = report(buffer)
        assertEquals(64, result.getValue("landJobs").jsonArray.size)
        assertEquals(6, result.getValue("droppedLandJobs").jsonPrimitive.int)
    }

}
