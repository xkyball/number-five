package com.no5.helm.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import com.no5.helm.chart.ChartViewport
import org.junit.Assert.*
import org.junit.Test

class ResidentCapacityMeasurementTest {
    @Test fun currentViewReuseUsesSixStableCamerasFromTheCapturedRealAnchor() {
        val anchor=ChartViewport(42.0,-71.0,.15,.3,1872,1088)
        val authority=buildJsonObject {put("catalogGeneration","a".repeat(64));put("sourceGeneration",3);put("surfaceGeneration",4)
            put("budget",buildJsonObject {put("cells",25);put("bytes",100_000_000);put("loadingBytes",20_000_000)})}
        val first=ResidentCapacityPoseRecipe.currentViewReuse(anchor,12_000,authority)
        val second=ResidentCapacityPoseRecipe.currentViewReuse(anchor,12_000,authority)
        assertEquals("CURRENT_VIEW_REUSE",first.id);assertEquals(6,first.steps.size)
        assertEquals(anchor,first.steps.first().viewport);assertEquals(anchor,first.steps.last().viewport)
        assertEquals(first.sha256,second.sha256);assertEquals(first.steps,second.steps)
        assertTrue(first.steps.all {it.viewport.width==anchor.width&&it.viewport.height==anchor.height})
        assertTrue(first.steps.zipWithNext().all {it.first.offsetMs<it.second.offsetMs})
        assertTrue(first.steps.any {it.viewport.rotationRadians!=anchor.rotationRadians})
        assertTrue(first.steps.any {it.viewport.metresToPixels>anchor.metresToPixels})
        assertTrue(first.steps.any {it.viewport.metresToPixels<anchor.metresToPixels})
        assertTrue(first.steps.any {it.viewport.latitude!=anchor.latitude||it.viewport.longitude!=anchor.longitude})
    }
    private class Fake : ResidentCapacityBackend {
        val calls = mutableListOf<String>()
        var prepareFailure: Exception? = null
        var observeFailure: Exception? = null
        var cancelled = false
        var restoreFailure: Exception? = null
        var restored = true
        override suspend fun prepare(): JsonObject {
            calls += "prepare"
            prepareFailure?.let { throw it }
            return buildJsonObject { put("catalog", "frozen-whole-catalog") }
        }
        override fun checkReady() { if (cancelled) throw CancellationException("run cancelled") }
        override suspend fun observe(): JsonObject {
            calls += "observe"
            observeFailure?.let { throw it }
            return buildJsonObject { put("required", 3); put("resident", 3); put("complete", true) }
        }
        override fun end() = buildJsonObject { calls += "end" }
        override suspend fun restore(): Boolean {
            calls += "restore"
            restoreFailure?.let { throw it }
            return restored
        }
    }

    @Test fun pollsAtBoundedRateAndAlwaysRestoresWithoutClaimingDisplayPerformance() = runBlocking {
        val backend = Fake()
        var now = 0L
        val report = ResidentCapacityMeasurement(backend, { now }) { now += 25 }.run(5_000)
        assertEquals(50, report.getValue("sampleCount").jsonPrimitive.int)
        assertEquals(5_000L, now)
        assertEquals("completed", report.getValue("outcome").jsonPrimitive.content)
        assertEquals(listOf("end", "restore"), backend.calls.takeLast(2))
        assertEquals("notMeasured", report.getValue("physicalInputToDisplay").jsonPrimitive.content)
        assertEquals("notMeasured", report.getValue("visibleDistinctFramesPerSecond").jsonPrimitive.content)
        assertEquals("NOT_MEASURED", report.getValue("softwareDurationEndpoint").jsonPrimitive.content)
        assertEquals("NOT_MEASURED", report.getValue("pollingDurationQuantiles").jsonPrimitive.content)
        assertTrue(report.getValue("restored").jsonPrimitive.boolean)
    }
    @Test fun anIncompleteRequiredRealCameraPathCannotBecomeAPassedMeasurement() = runBlocking {
        var now=0L;var restored=false
        val backend=object:ResidentCapacityBackend {
            override suspend fun prepare()=buildJsonObject {}
            override fun checkReady()=Unit
            override suspend fun observe()=buildJsonObject {}
            override fun end()=buildJsonObject {put("requiredInputPathValid",false)}
            override suspend fun restore():Boolean {restored=true;return true}
        }
        val result=ResidentCapacityMeasurement(backend,{now}) {now+=100}.run(5_000)
        assertEquals("aborted",result.getValue("outcome").jsonPrimitive.content)
        assertTrue(result.getValue("error").jsonPrimitive.content.contains("current-view path"));assertTrue(restored)
    }

    @Test fun cancellationAndPrepareOrObservationFailureAllRestore() = runBlocking {
        for (failure in listOf("prepare", "cancel", "observe")) {
            val backend = Fake().apply {
                when (failure) {
                    "prepare" -> prepareFailure = IllegalStateException("journal rejected")
                    "cancel" -> cancelled = true
                    else -> observeFailure = IllegalStateException("owner changed")
                }
            }
            val report = ResidentCapacityMeasurement(backend, { 0 }).run(5_000)
            assertEquals("aborted", report.getValue("outcome").jsonPrimitive.content)
            assertTrue(report.getValue("restored").jsonPrimitive.boolean)
            assertEquals(listOf("end", "restore"), backend.calls.takeLast(2))
        }
    }

    @Test fun restorationFailureNeverLooksLikeACompletedMeasurement() = runBlocking {
        for (throws in listOf(false, true)) {
            val backend = Fake().apply {
                restored = false
                if (throws) restoreFailure = IllegalStateException("pins changed")
            }
            var now = 0L
            val report = ResidentCapacityMeasurement(backend, { now }) { now += 100 }.run(5_000)
            assertEquals("failed", report.getValue("outcome").jsonPrimitive.content)
            assertFalse(report.getValue("restored").jsonPrimitive.boolean)
            assertTrue(report.getValue("restorationError").jsonPrimitive.content.isNotEmpty())
        }
    }

    @Test fun slowOwnerCallsAreBracketedAndNeverDescribedAsCompleteEventTraces() = runBlocking {
        var now = 0L
        val backend = object : ResidentCapacityBackend {
            override suspend fun prepare() = buildJsonObject {}
            override fun checkReady() = Unit
            override suspend fun observe() = buildJsonObject { now += 2_000; put("loading", 0) }
            override suspend fun restore() = true
        }
        val report = ResidentCapacityMeasurement(backend, { now }) { now += 100 }.run(5_000)
        val samples = report.getValue("samples").jsonArray
        assertEquals(3, samples.size)
        assertEquals(0L, samples.first().jsonObject.getValue("sampleStartedElapsedRealtimeMs").jsonPrimitive.long)
        assertEquals(2_000L, samples.first().jsonObject.getValue("sampleFinishedElapsedRealtimeMs").jsonPrimitive.long)
        assertEquals("owner-bound-polling", report.getValue("observationKind").jsonPrimitive.content)
    }

    @Test fun backwardClockAbortsAndStillRestores() = runBlocking {
        val backend = Fake()
        var now = 100L
        val report = ResidentCapacityMeasurement(backend, { now }) { now-- }.run(5_000)
        assertEquals("aborted", report.getValue("outcome").jsonPrimitive.content)
        assertTrue(report.getValue("error").jsonPrimitive.content.contains("backwards"))
        assertEquals("restore", backend.calls.last())
    }

    @Test fun configurationBoundsRejectUnboundedRunBeforePreparing() = runBlocking {
        val backend = Fake()
        for (duration in listOf(4_999L, 90_001L)) {
            try { ResidentCapacityMeasurement(backend, { 0 }).run(duration); fail("Invalid duration accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun actualCoroutineCancellationKeepsRestorationNonCancellable() = runBlocking {
        val observed = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        var restoredWithActiveContext = false
        val backend = object : ResidentCapacityBackend {
            override suspend fun prepare() = buildJsonObject {}
            override fun checkReady() = Unit
            override suspend fun observe(): JsonObject {
                observed.complete(Unit)
                wait.await()
                return buildJsonObject {}
            }
            override suspend fun restore(): Boolean {
                restoredWithActiveContext = currentCoroutineContext()[Job]!!.isActive
                yield()
                return true
            }
        }
        val job = launch { ResidentCapacityMeasurement(backend, { 0 }).run(5_000) }
        observed.await(); job.cancel(); job.join()
        assertTrue(restoredWithActiveContext)
    }

    @Test fun maximumWindowStillFitsTheBoundedObservationBuffer() = runBlocking {
        val backend = Fake()
        var now = 0L
        val report = ResidentCapacityMeasurement(backend, { now }) { now += 100 }.run(90_000)
        assertEquals(900, report.getValue("sampleCount").jsonPrimitive.int)
        assertTrue(report.getValue("sampleCount").jsonPrimitive.int < ResidentCapacityMeasurement.MAX_SAMPLES)
        assertEquals("completed", report.getValue("outcome").jsonPrimitive.content)
    }

    private fun authority() = buildJsonObject {
        put("catalogGeneration", "a".repeat(64)); put("sourceGeneration", 3L)
        put("contentGeneration", 4L); put("surfaceGeneration", 5L); put("cameraRevision", 6L)
        put("budget", buildJsonObject { put("cells", 25); put("bytes", 256L * 1024 * 1024); put("loadingBytes", 128L * 1024 * 1024) })
    }

    @Test fun actualDurationEventsHaveUniqueIdsAndAnIndependentMonotonicClock() {
        val buffer = ResidentCapacitySoftwareEvents("arm")
        buffer.recordDuration("NATIVE_RENDER", -100, -50, authority(), buildJsonObject { put("stage", "FULL") })
        buffer.recordInstant("FULL_PRODUCED", -49, authority())
        val report = buffer.snapshot()
        val events = report.getValue("events").jsonArray
        assertEquals(2, events.size)
        assertEquals(50L, events.first().jsonObject.getValue("durationNs").jsonPrimitive.long)
        assertEquals(JsonNull, events.last().jsonObject.getValue("durationNs"))
        assertEquals(2, events.map { it.jsonObject.getValue("eventId").jsonPrimitive.content }.toSet().size)
        assertEquals("SOFTWARE_WORK_CALLS", report.getValue("durationEndpoint").jsonPrimitive.content)
        assertEquals("NOT_MEASURED", report.getValue("physicalInputToDisplay").jsonPrimitive.content)
    }

    @Test fun invalidAuthorityBackwardOrOverflowedDurationsDoNotBecomeTimingEvidence() {
        val buffer = ResidentCapacitySoftwareEvents("bad")
        buffer.recordDuration("NATIVE_RENDER", 5, 4, authority())
        buffer.recordDuration("NATIVE_RENDER", Long.MIN_VALUE, Long.MAX_VALUE, authority())
        buffer.recordDuration("RESIDENT_STEP", 0, 1, buildJsonObject { put("sourceGeneration", 0) })
        assertEquals(3, buffer.snapshot().getValue("invalidEvents").jsonPrimitive.int)
        assertFalse(buffer.snapshot().getValue("valid").jsonPrimitive.boolean)
        assertEquals("NOT_MEASURED", buffer.snapshot().getValue("durationEndpoint").jsonPrimitive.content)
        assertEquals(0, buffer.snapshot().getValue("eventCount").jsonPrimitive.int)
    }

    @Test fun boundedBufferMarksOverflowAndFreezesNestedAuthority() {
        val mutable = authority().toMutableMap()
        val original = JsonObject(mutable)
        val buffer = ResidentCapacitySoftwareEvents("bounded", 2)
        buffer.recordDuration("NATIVE_RENDER", 0, 1, original)
        mutable["sourceGeneration"] = JsonPrimitive(99)
        buffer.recordDuration("NATIVE_RENDER", 1, 2, authority())
        buffer.recordDuration("NATIVE_RENDER", 2, 3, authority())
        val report = buffer.snapshot()
        assertTrue(report.getValue("overflow").jsonPrimitive.boolean)
        assertFalse(report.getValue("valid").jsonPrimitive.boolean)
        assertEquals(2, report.getValue("eventCount").jsonPrimitive.int)
        assertEquals(3L, report.getValue("events").jsonArray.first().jsonObject.getValue("authority")
            .jsonObject.getValue("sourceGeneration").jsonPrimitive.long)
    }

    @Test fun softwareEvidenceBelongsToItsRunAndNeverTurnsRepeatedPollsIntoWorkDurations() = runBlocking {
        for (eventRunId in listOf("arm", "different-arm")) {
            val events = ResidentCapacitySoftwareEvents(eventRunId)
            events.recordDuration("NATIVE_RENDER", 0, 20, authority())
            var now = 0L
            val backend = object : ResidentCapacityBackend {
                override suspend fun prepare() = buildJsonObject { put("runId", "arm") }
                override fun checkReady() = Unit
                override suspend fun observe() = buildJsonObject { put("complete", true) }
                override fun end() = buildJsonObject { put("softwareEvents", events.snapshot()) }
                override suspend fun restore() = true
            }
            val report = ResidentCapacityMeasurement(backend, { now }) { now += 100 }.run(5_000)
            assertEquals(50, report.getValue("sampleCount").jsonPrimitive.int)
            assertEquals(if (eventRunId == "arm") "SOFTWARE_WORK_CALLS" else "NOT_MEASURED",
                report.getValue("softwareDurationEndpoint").jsonPrimitive.content)
            assertEquals("NOT_MEASURED", report.getValue("pollingDurationQuantiles").jsonPrimitive.content)
        }
    }

    private val anchor = ChartViewport(52.5, 13.2, .2, .1, 2000, 1200)

    @Test fun recipeFreezesEveryAbsolutePoseAndRepeatsExactlyTheSamePath() {
        for (duration in listOf(10_000L, 77_000L, 90_000L)) {
            val recipe = ResidentCapacityPoseRecipe.currentViewportV1(anchor, duration, authority())
            val first = recipe.steps.filter { it.windowId == "geometry-initial" }
            val second = recipe.steps.filter { it.windowId == "warm-revisit" }
            assertEquals(first.map { it.viewport }, second.map { it.viewport })
            assertTrue(recipe.steps.size <= 256)
            assertTrue(recipe.steps.zipWithNext().all { it.first.offsetMs < it.second.offsetMs })
            assertTrue(recipe.steps.all { it.offsetMs in 0 until duration && it.viewport.width == anchor.width && it.viewport.height == anchor.height })
            assertEquals(anchor, recipe.steps.last().viewport)
            assertEquals(recipe.sha256, ResidentCapacityPoseRecipe.currentViewportV1(anchor, duration, authority()).sha256)
            assertNotEquals(recipe.sha256, ResidentCapacityPoseRecipe.currentViewportV1(anchor.copy(longitude = 13.3), duration, authority()).sha256)
        }
        assertThrows(IllegalArgumentException::class.java) { ResidentCapacityPoseRecipe.currentViewportV1(anchor, 9_999) }
        assertThrows(IllegalArgumentException::class.java) { ResidentCapacityPoseRecipe.currentViewportV1(anchor.copy(width = 0), 10_000) }
    }

    @Test fun recipeUsesAbsoluteOffsetsAndReportsProposalDelayRatherThanStretchingTheTimeline() = runBlocking {
        val recipe = ResidentCapacityPoseRecipe.currentViewportV1(anchor, 10_000, authority())
        var now = 100L
        val report = recipe.run(nowNs = { now }, pauseMs = { now += it * 1_000_000 }, propose = { step ->
            if (step.index == 0) now += 2_000_000_000
            buildJsonObject { put("accepted", true); put("realizedViewport", BostonBenchmarkRecipe.cameraJson(step.viewport)); put("authority", authority()) }
        })
        assertTrue(report.getValue("comparableRealizedPath").jsonPrimitive.boolean)
        assertTrue(report.getValue("results").jsonArray.any { it.jsonObject.getValue("latenessNs").jsonPrimitive.long > 1_000_000 })
        assertEquals(9_000_000_100L, now)
    }

    @Test fun rejectedOrClampedPosesAreRetainedAndInvalidatePerformancePathComparison() = runBlocking {
        val recipe = ResidentCapacityPoseRecipe.currentViewportV1(anchor, 10_000, authority())
        var now = 0L
        val report = recipe.run(nowNs = { now }, pauseMs = { now += it * 1_000_000 }, propose = { step ->
            buildJsonObject { put("accepted", step.index != 1); put("reason", if (step.index == 1) "BLOCKED" else "ACCEPTED")
                put("realizedViewport", BostonBenchmarkRecipe.cameraJson(if (step.index == 2) anchor else step.viewport)); put("authority", authority()) }
        })
        assertEquals(recipe.steps.size, report.getValue("results").jsonArray.size)
        assertFalse(report.getValue("comparableRealizedPath").jsonPrimitive.boolean)
    }

    @Test fun changedStableAuthorityInvalidatesEvenAnIdenticalRealizedCamera() = runBlocking {
        val recipe = ResidentCapacityPoseRecipe.currentViewportV1(anchor, 10_000, authority())
        var now = 0L
        val report = recipe.run(nowNs = { now }, pauseMs = { now += it * 1_000_000 }, propose = { step ->
            val changed = JsonObject(authority().toMutableMap().apply { if (step.index == 2) put("sourceGeneration", JsonPrimitive(999)) })
            buildJsonObject { put("accepted", true); put("realizedViewport", BostonBenchmarkRecipe.cameraJson(step.viewport)); put("authority", changed) }
        })
        assertTrue(report.getValue("results").jsonArray.all { it.jsonObject.getValue("sameRealizedPose").jsonPrimitive.boolean })
        assertFalse(report.getValue("comparableRealizedPath").jsonPrimitive.boolean)
    }

    @Test fun cancelledRecipeKeepsPartialResultsForTheOwnerRestore() = runBlocking {
        val recipe = ResidentCapacityPoseRecipe.currentViewportV1(anchor, 10_000)
        var now = 0L
        try {
            recipe.run(nowNs = { now }, pauseMs = { now += it * 1_000_000 }, propose = { step ->
                if (step.index == 2) throw CancellationException("cancel requested")
                buildJsonObject { put("accepted", true); put("realizedViewport", BostonBenchmarkRecipe.cameraJson(step.viewport)) }
            })
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
        assertEquals("cancelled", recipe.snapshot().getValue("phase").jsonPrimitive.content)
        assertEquals(2, recipe.snapshot().getValue("results").jsonArray.size)
        assertFalse(recipe.snapshot().getValue("comparableRealizedPath").jsonPrimitive.boolean)
    }
}
