package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RendererBenchmarkRunnerTest {
    private val geometry = ChartSurfaceGeometry.fit(1872, 1088)
    private class Fake : RendererBenchmarkBackend {
        val paths = mutableListOf<Pair<BenchmarkCase, MutableList<ChartViewport>>>()
        var restored = false
        var restoreFails = false
        var metadataFails = false
        var metadataReads = 0
        var applies = 0
        val reuseArms = mutableListOf<RendererViewportReuseVariant>()
        var failAt = -1
        var current: Pair<BenchmarkCase, MutableList<ChartViewport>>? = null
        override suspend fun metadata(): JsonObject {
            metadataReads++
            check(!metadataFails) { "Source hashes unavailable" }
            return buildJsonObject { put("sourceCommit", "test-commit"); put("dataset", "fixed-boston") }
        }
        override fun checkReady() = Unit
        override suspend fun apply(profile: ChartPresentation) { applies++; profile.validate() }
        override fun configureViewportReuse(variant: RendererViewportReuseVariant) { reuseArms += variant }
        override fun begin(case: BenchmarkCase, viewport: ChartViewport) {
            current = case to mutableListOf<ChartViewport>()
            paths += current!!
        }
        override fun frame(viewport: ChartViewport) {
            check(current!!.second.size != failAt) { "Surface changed" }
            current!!.second += viewport
        }
        override fun end(reason: String): JsonObject? {
            val group = current ?: return null
            current = null
            return buildJsonObject {
                put("frames", group.second.size); put("warmupFrames", 5); put("stopReason", reason); put("gpuSummary", JsonNull)
                put("summary", buildJsonObject { put("workerFrameWallMs", buildJsonObject { put("median", if (group.first.detailed) 6.0 else 4.0) }) })
            }
        }
        override suspend fun restore(): Boolean { check(!restoreFails) { "Restore preparation failed" }; restored = true; return true }
        override fun progress(completed: Int, total: Int, frame: Int, message: String) = Unit
    }
    @Test fun twoAbbaRepeatsHaveIdenticalCamerasAcrossStylesAndModes() = runBlocking {
        val fake = Fake()
        val report = RendererBenchmarkRunner(fake) { false }.run("run", RendererBenchmarkConfig(), geometry)
        assertEquals("complete", report.getValue("outcome").jsonPrimitive.content)
        assertEquals(32, fake.paths.size)
        assertEquals(listOf("glacier", "lagoon-sculpt", "lagoon-sculpt", "glacier", "glacier", "lagoon-sculpt", "lagoon-sculpt", "glacier"), fake.paths.chunked(4).map { it.first().first.profile.styleId })
        for (motion in listOf(false, true)) {
            val paths = fake.paths.filter { it.first.motion == motion }.map { it.second }
            assertTrue(paths.all { it.size == 35 && it == paths.first() })
            assertEquals(paths.first()[0], paths.first()[4])
            assertEquals(paths.first()[5], paths.first()[34])
            assertEquals(paths.first()[0], paths.first()[5])
            if (motion) assertTrue(paths.first().distinct().size >= 25) else assertEquals(1, paths.first().distinct().size)
        }
        assertTrue(fake.restored)
        assertEquals("test-commit", report.getValue("metadata").jsonObject.getValue("sourceCommit").jsonPrimitive.content)
        assertFalse(report.toString().contains("captureCountFps"))
    }
    @Test fun viewportReuseAbbaPreservesProfileAndCameraAndNeverClaimsActivation() = runBlocking {
        val fake = Fake(); val profile = ChartPresentation("redwater-focus")
        val report = RendererBenchmarkRunner(fake) { false }.run("reuse", RendererBenchmarkConfig(3,
            viewportReuseCandidate = RendererViewportReuseVariant.GPU), geometry, profile)
        assertEquals("no5-renderer-viewport-reuse-benchmark/1", report.getValue("schema").jsonPrimitive.content)
        assertEquals("complete", report.getValue("outcome").jsonPrimitive.content)
        assertEquals(48, fake.paths.size)
        val arms = fake.paths.chunked(4).map { it.first().first.viewportReuse }
        assertEquals(List(3) { listOf(RendererViewportReuseVariant.OFF, RendererViewportReuseVariant.GPU,
            RendererViewportReuseVariant.GPU, RendererViewportReuseVariant.OFF) }.flatten(), arms)
        assertTrue(fake.paths.all { it.first.profile == profile })
        for (motion in listOf(false, true)) {
            val paths = fake.paths.filter { it.first.motion == motion }.map { it.second }
            assertTrue(paths.all { it == paths.first() })
        }
        assertEquals(fake.paths.map { it.first.viewportReuse }, fake.reuseArms)
        assertFalse(report.getValue("recipe").jsonObject.getValue("automaticActivation").jsonPrimitive.boolean)
        assertTrue(fake.restored)
    }
    @Test fun cancelledViewportArmRetainsPartialEvidenceAndStillRestores() = runBlocking {
        val fake = Fake()
        val report = RendererBenchmarkRunner(fake) { fake.paths.firstOrNull()?.second?.size == 7 }.run("reuse-cancel",
            RendererBenchmarkConfig(1, viewportReuseCandidate = RendererViewportReuseVariant.ALL), geometry, BostonBenchmarkRecipe.glacier)
        assertEquals("cancelled", report.getValue("outcome").jsonPrimitive.content)
        assertEquals(listOf(RendererViewportReuseVariant.OFF), fake.reuseArms)
        assertEquals(7, report.getValue("groups").jsonArray.single().jsonObject.getValue("capture").jsonObject.getValue("frames").jsonPrimitive.int)
        assertTrue(fake.restored)
    }
    @Test fun failedFrameRetainsPartialCaptureAndRestores() = runBlocking {
        val fake = Fake().apply { failAt = 9 }
        val report = RendererBenchmarkRunner(fake) { false }.run("run", RendererBenchmarkConfig(1), geometry)
        assertEquals("failed", report.getValue("outcome").jsonPrimitive.content)
        assertEquals(9, report.getValue("groups").jsonArray.single().jsonObject.getValue("capture").jsonObject.getValue("frames").jsonPrimitive.int)
        assertEquals("Surface changed", report.getValue("error").jsonPrimitive.content)
        assertTrue(fake.restored)
    }
    @Test fun cancellationRetainsPartialCaptureAndRestores() = runBlocking {
        val fake = Fake()
        val report = RendererBenchmarkRunner(fake) { fake.paths.firstOrNull()?.second?.size == 7 }.run("run", RendererBenchmarkConfig(1), geometry)
        assertEquals("cancelled", report.getValue("outcome").jsonPrimitive.content)
        assertEquals(7, report.getValue("groups").jsonArray.single().jsonObject.getValue("capture").jsonObject.getValue("frames").jsonPrimitive.int)
        assertTrue(report.getValue("restored").jsonPrimitive.boolean)
    }
    @Test fun datasetFailureStillRestoresAndReportsError() = runBlocking {
        val fake = Fake().apply { metadataFails = true }
        val report = RendererBenchmarkRunner(fake) { false }.run("run", RendererBenchmarkConfig(1), geometry)
        assertEquals("failed", report.getValue("outcome").jsonPrimitive.content)
        assertTrue(report.getValue("groups").jsonArray.isEmpty()); assertTrue(fake.restored)
    }
    @Test fun cooperativeNativePreparationFailureAfterCancelIsCancelledAndRestores() = runBlocking {
        val fake = Fake().apply { metadataFails = true }
        val report = RendererBenchmarkRunner(fake) { fake.metadataReads > 0 }.run("native-cancel", RendererBenchmarkConfig(1), geometry)
        assertEquals("cancelled", report.getValue("outcome").jsonPrimitive.content)
        assertTrue(fake.restored); assertTrue(report.getValue("groups").jsonArray.isEmpty())
        assertEquals("Source hashes unavailable", report.getValue("error").jsonPrimitive.content)
    }
    @Test fun cancellationBeforePreparationSkipsSourceIoAndStyleChanges() = runBlocking {
        val fake = Fake()
        val report = RendererBenchmarkRunner(fake) { true }.run("run", RendererBenchmarkConfig(1), geometry)
        assertEquals("cancelled", report.getValue("outcome").jsonPrimitive.content)
        assertEquals(0, fake.metadataReads); assertEquals(0, fake.applies)
        assertTrue(fake.restored); assertTrue(report.getValue("groups").jsonArray.isEmpty())
    }
    @Test fun cancellationBetweenGroupsDoesNotPrepareAnotherStyle() = runBlocking {
        val fake = Fake()
        val report = RendererBenchmarkRunner(fake) { fake.paths.firstOrNull()?.second?.size == 35 }
            .run("run", RendererBenchmarkConfig(1), geometry)
        assertEquals("cancelled", report.getValue("outcome").jsonPrimitive.content)
        assertEquals(1, fake.applies); assertEquals(1, report.getValue("groups").jsonArray.size)
        assertTrue(fake.restored)
    }
    @Test fun cancelledRunCanRepeatWithFreshGroupsAndUnchangedRecipe() = runBlocking {
        val fake = Fake()
        var cancel = true
        val runner = RendererBenchmarkRunner(fake) { cancel && fake.paths.firstOrNull()?.second?.size == 7 }
        val first = runner.run("first", RendererBenchmarkConfig(1), geometry)
        cancel = false; fake.paths.clear(); fake.restored = false
        val second = runner.run("second", RendererBenchmarkConfig(1), geometry)
        assertEquals("cancelled", first.getValue("outcome").jsonPrimitive.content)
        assertEquals("complete", second.getValue("outcome").jsonPrimitive.content)
        assertEquals(first.getValue("recipe"), second.getValue("recipe"))
        assertEquals(1, first.getValue("groups").jsonArray.size); assertEquals(16, second.getValue("groups").jsonArray.size)
        assertEquals(560, fake.paths.sumOf { it.second.size }); assertTrue(fake.restored)
    }
    @Test fun restorationFailureNeverClaimsSuccess() = runBlocking {
        val fake = Fake().apply { restoreFails = true }
        val report = RendererBenchmarkRunner(fake) { true }.run("run", RendererBenchmarkConfig(1), geometry)
        assertEquals("failed", report.getValue("outcome").jsonPrimitive.content)
        assertFalse(report.getValue("restored").jsonPrimitive.boolean)
        assertEquals("Restore preparation failed", report.getValue("restorationError").jsonPrimitive.content)
    }
    @Test fun standardUsesCurrentProfileAndExactlyTwoMatchedNormalDeepGroups() = runBlocking {
        val fake = Fake()
        val profile = ChartPresentation("redwater-focus")
        val report = RendererBenchmarkRunner(fake) { false }.run("standard", RendererBenchmarkConfig(), geometry, profile)
        assertEquals("no5-renderer-ab/1", report.getValue("schema").jsonPrimitive.content)
        assertEquals("complete", report.getValue("outcome").jsonPrimitive.content)
        assertEquals(listOf(false, true), fake.paths.map { it.first.detailed })
        assertTrue(fake.paths.all { it.first.profile == profile && !it.first.motion && it.second.size == 35 })
        assertEquals(fake.paths[0].second, fake.paths[1].second)
        assertEquals(1, fake.paths[0].second.distinct().size)
        val recipe = report.getValue("recipe").jsonObject
        assertEquals("boston-standard-normal-deep/1", recipe.getValue("id").jsonPrimitive.content)
        assertEquals(70, recipe.getValue("totalSubmittedFrames").jsonPrimitive.int)
        assertEquals(5, report.getValue("warmupFramesPerGroup").jsonPrimitive.int)
        assertEquals(30, report.getValue("measuredFramesPerGroup").jsonPrimitive.int)
        assertEquals(1.5, report.getValue("observedMedianWallRatioDeepToNormal").jsonPrimitive.double, 0.0)
        assertEquals(35, report.getValue("groups").jsonArray.first().jsonObject.getValue("frames").jsonPrimitive.int)
        assertFalse(recipe.containsKey("motionCameraSteps")); assertTrue(fake.restored)
        assertFalse(recipe.getValue("caseOrder").toString().contains("abbaPosition"))
    }
    @Test fun standardCancellationBeforeSetupSkipsIoAndCanRepeat() = runBlocking {
        val fake = Fake()
        var cancel = true
        val runner = RendererBenchmarkRunner(fake) { cancel }
        val first = runner.run("first", RendererBenchmarkConfig(1), geometry, BostonBenchmarkRecipe.s01)
        assertEquals("cancelled", first.getValue("outcome").jsonPrimitive.content)
        assertEquals(0, fake.metadataReads); assertEquals(0, fake.applies); assertTrue(fake.restored)
        cancel = false
        val second = runner.run("second", RendererBenchmarkConfig(1), geometry, BostonBenchmarkRecipe.s01)
        assertEquals("complete", second.getValue("outcome").jsonPrimitive.content)
        assertEquals(2, second.getValue("groups").jsonArray.size); assertTrue(fake.restored)
        assertEquals(first.getValue("recipe"), second.getValue("recipe"))
    }
    @Test fun standardAbortAndSurfaceFailureRetainPartialReportsAndAlwaysRestore() = runBlocking {
        for (cancel in listOf(false, true)) {
            val fake = Fake().apply { if (!cancel) failAt = 9 }
            val report = RendererBenchmarkRunner(fake) { cancel && fake.paths.firstOrNull()?.second?.size == 7 }
                .run("partial", RendererBenchmarkConfig(1), geometry, BostonBenchmarkRecipe.glacier)
            assertEquals(if (cancel) "cancelled" else "failed", report.getValue("outcome").jsonPrimitive.content)
            assertEquals(if (cancel) 7 else 9, report.getValue("groups").jsonArray.single().jsonObject.getValue("frames").jsonPrimitive.int)
            assertEquals(JsonNull, report.getValue("observedMedianWallRatioDeepToNormal"))
            assertTrue(fake.restored)
        }
    }
    @Test fun standardMissingDatasetAndFailedRecoveryRemainExplicitFailures() = runBlocking {
        for (restoreFailure in listOf(false, true)) {
            val fake = Fake().apply { metadataFails = true; restoreFails = restoreFailure }
            val report = RendererBenchmarkRunner(fake) { false }.run("failed", RendererBenchmarkConfig(1), geometry, BostonBenchmarkRecipe.glacier)
            assertEquals("failed", report.getValue("outcome").jsonPrimitive.content)
            assertEquals("Source hashes unavailable", report.getValue("error").jsonPrimitive.content)
            assertEquals(!restoreFailure, report.getValue("restored").jsonPrimitive.boolean)
            assertTrue(report.getValue("groups").jsonArray.isEmpty())
        }
    }
    @Test fun dimensionsUseActualFboAndConfigIsBounded() {
        val fitted = ChartSurfaceGeometry.fit(6000, 3000)
        val camera = BostonBenchmarkRecipe.camera(true, 12, fitted)
        assertEquals(fitted.frameWidth, camera.width); assertEquals(fitted.frameHeight, camera.height)
        for (count in listOf(0, 5)) try { RendererBenchmarkConfig(count); fail("Must reject") } catch (_: IllegalArgumentException) {}
        try { BostonBenchmarkRecipe.camera(true, 35, geometry); fail("Must reject") } catch (_: IllegalArgumentException) {}
    }
}
