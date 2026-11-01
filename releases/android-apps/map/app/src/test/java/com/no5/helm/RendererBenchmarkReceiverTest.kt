package com.no5.helm

import android.content.ComponentName
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RendererBenchmarkReceiverTest {
    @Test fun currentResidentReferenceRequiresExactSourcePinAndCannotSupplyAReplacementCamera() {
        val id = "00000000-0000-4000-8000-000000000001"
        val request = Intent().putExtra("clientRunToken", "current-live").putExtra("deadlineElapsedRealtimeMs", 100_000L)
            .putExtra("runKind", "LIVE_INPUT").putExtra("repetitions", 1).putExtra("currentResidentSnapshotId", id)
        assertThrows(IllegalArgumentException::class.java) { rendererBenchmarkConfig(request) }
        request.putExtra("expectedSourceCommit", com.no5.helm.app.BuildConfig.SOURCE_COMMIT)
        assertEquals(id, rendererBenchmarkConfig(request).currentResidentLive!!.snapshotId)
        assertFalse(rendererBenchmarkConfig(request).normalRenderAbort)
        assertThrows(IllegalArgumentException::class.java) { rendererBenchmarkConfig(Intent(request).putExtra("currentResidentSnapshotId", "unknown")) }
        assertThrows(IllegalArgumentException::class.java) { rendererBenchmarkConfig(Intent(request).putExtra("runKind", "SCRIPTED")) }
        assertThrows(IllegalArgumentException::class.java) { rendererBenchmarkConfig(Intent(request).putExtra("liveMode", "TRANSFORM_ONLY")) }
    }
    private fun capacityIntent() = Intent("com.no5.map.benchmark.START")
        .putExtra("clientRunToken", "capacity-test").putExtra("deadlineElapsedRealtimeMs", 100_000L)
        .putExtra("runKind", "RESIDENT_CAPACITY").putExtra("capacityCells", 25)
        .putExtra("capacityBytes", 256L * 1024 * 1024).putExtra("capacityLoadingBytes", 128L * 1024 * 1024)
        .putExtra("capacityCacheMode", "EMPTY_GEOMETRY").putExtra("expectedCatalogGeneration", "a".repeat(64))
        .putExtra("expectedSourceGeneration", 3L).putExtra("capacityDurationMs", 10_000L)

    @Test fun capacityArmRequiresExplicitSourcePinsAndBudgets() {
        val config = rendererBenchmarkConfig(capacityIntent())
        assertEquals(com.no5.helm.chart.RendererRunKind.RESIDENT_CAPACITY, config.runKind)
        assertEquals(1, config.repetitions)
        assertEquals(25, config.capacityArm!!.budget.cells)
        assertEquals(3L, config.capacityArm!!.expectedSourceGeneration)
        listOf("capacityCells", "capacityBytes", "capacityLoadingBytes", "capacityCacheMode",
            "expectedCatalogGeneration", "expectedSourceGeneration", "capacityDurationMs").forEach { extra ->
            val missing = capacityIntent().apply { removeExtra(extra) }
            assertThrows(IllegalArgumentException::class.java) { rendererBenchmarkConfig(missing) }
        }
    }

    @Test fun capacityArmRejectsOversizedCellsInvalidSourceAndParallelWorkers() {
        listOf(capacityIntent().putExtra("capacityCells", 101),
            capacityIntent().putExtra("expectedSourceGeneration", -1L),
            capacityIntent().putExtra("expectedCatalogGeneration", "unknown"),
            capacityIntent().putExtra("requestedWorkers", 2),
            capacityIntent().putExtra("capacityDurationMs", 90_001L)).forEach {
            assertThrows(IllegalArgumentException::class.java) { rendererBenchmarkConfig(it) }
        }
    }

    @Test fun scriptedDefaultsRemainIndependentOfCapacityArm() {
        val config = rendererBenchmarkConfig(Intent().putExtra("clientRunToken", "scripted-test")
            .putExtra("deadlineElapsedRealtimeMs", 100_000L))
        assertEquals(2, config.repetitions)
        assertEquals(com.no5.helm.chart.RendererRunKind.SCRIPTED, config.runKind)
        assertNull(config.capacityArm)
        assertNull(config.capacityRecipeId)
        assertNull(config.viewportReuseCandidate)
    }

    @Test fun viewportReuseCandidateIsTypedAndCannotBecomeAUserFeatureSetting() {
        val request = Intent().putExtra("clientRunToken", "reuse-test").putExtra("deadlineElapsedRealtimeMs", 100_000L)
            .putExtra("viewportReuseCandidate", "ALL").putExtra("repetitions", 3)
        assertEquals(com.no5.helm.chart.RendererViewportReuseVariant.ALL, rendererBenchmarkConfig(request).viewportReuseCandidate)
        assertThrows(IllegalArgumentException::class.java) { rendererBenchmarkConfig(Intent(request).putExtra("viewportReuseCandidate", "unknown")) }
        assertThrows(IllegalArgumentException::class.java) { rendererBenchmarkConfig(Intent(request).putExtra("requestedWorkers", 2)) }
        val current=capacityIntent().putExtra("capacityRecipeId","CURRENT_VIEW_REUSE").putExtra("capacityCacheMode","WARM")
            .putExtra("viewportReuseCandidate","GPU").putExtra("viewportReuseDetailed",true)
        assertEquals("CURRENT_VIEW_REUSE",rendererBenchmarkConfig(current).capacityRecipeId)
        assertTrue(rendererBenchmarkConfig(current).viewportReuseDetailed)
        assertThrows(IllegalArgumentException::class.java) { rendererBenchmarkConfig(Intent(current).putExtra("capacityCacheMode","EMPTY_GEOMETRY")) }
    }

    @Test fun fixedCapacityRecipeRequiresTheCapacityArmAndBoundedDuration() {
        val request = capacityIntent().putExtra("capacityRecipeId", "CURRENT_VIEWPORT_V1")
        assertEquals("CURRENT_VIEWPORT_V1", rendererBenchmarkConfig(request).capacityRecipeId)
        listOf(capacityIntent().putExtra("capacityRecipeId", "unknown"),
            capacityIntent().putExtra("capacityRecipeId", "CURRENT_VIEWPORT_V1").putExtra("capacityDurationMs", 9_999L),
            Intent().putExtra("clientRunToken", "scripted-recipe").putExtra("deadlineElapsedRealtimeMs", 100_000L)
                .putExtra("capacityRecipeId", "CURRENT_VIEWPORT_V1")).forEach {
            assertThrows(IllegalArgumentException::class.java) { rendererBenchmarkConfig(it) }
        }
        // Five-second manual arms remain possible; no arbitrary pose JSON or files are accepted.
        assertNull(rendererBenchmarkConfig(capacityIntent().putExtra("capacityDurationMs", 5_000L)).capacityRecipeId)
    }

    @Test fun parityIsAnExplicitSeparateOffAllDiagnosticAndCannotLeakIntoNormalOrDetailedRuns() {
        val request = capacityIntent().putExtra("capacityRecipeId", "CURRENT_VIEW_REUSE").putExtra("capacityCacheMode", "WARM")
            .putExtra("viewportReuseCandidate", "ALL").putExtra("viewportReuseParity", true)
        assertTrue(rendererBenchmarkConfig(request).viewportReuseParity)
        assertFalse(rendererBenchmarkConfig(Intent(request).apply { removeExtra("viewportReuseParity") }).viewportReuseParity)
        assertThrows(IllegalArgumentException::class.java) { rendererBenchmarkConfig(Intent(request).putExtra("viewportReuseDetailed", true)) }
        assertThrows(IllegalArgumentException::class.java) { rendererBenchmarkConfig(Intent(request).putExtra("viewportReuseCandidate", "SPATIAL")) }
        assertThrows(IllegalArgumentException::class.java) { rendererBenchmarkConfig(Intent(request).apply { removeExtra("capacityRecipeId") }) }
    }
    @Test fun normalAbortRequiresExplicitLiveInputControlAndNeverExtendsTheThreeReuseFlags() {
        val request = Intent().putExtra("clientRunToken", "normal-abort-test").putExtra("deadlineElapsedRealtimeMs", 100_000L)
            .putExtra("runKind", "LIVE_INPUT").putExtra("repetitions", 1)
            .putExtra("liveMode", "CONTROL").putExtra("normalRenderAbort", true)
        assertTrue(rendererBenchmarkConfig(request).normalRenderAbort)
        assertFalse(rendererBenchmarkConfig(Intent(request).apply { removeExtra("normalRenderAbort") }).normalRenderAbort)
        assertThrows(IllegalArgumentException::class.java) { rendererBenchmarkConfig(Intent(request).putExtra("liveMode", "TRANSFORM_AREAS_FIRST")) }
        assertThrows(IllegalArgumentException::class.java) { rendererBenchmarkConfig(Intent(request).putExtra("runKind", "SCRIPTED")) }
        assertThrows(IllegalArgumentException::class.java) { rendererBenchmarkConfig(Intent(request).putExtra("viewportReuseCandidate", "ALL")) }
    }

    @Test fun debugControlRequiresDumpPermissionAndNoHostIsCreated() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("com.no5.map", com.no5.helm.app.BuildConfig.APPLICATION_ID)
        val info = context.packageManager.getReceiverInfo(ComponentName(context, RendererBenchmarkReceiver::class.java), 0)
        assertTrue(info.exported)
        assertEquals("android.permission.DUMP", info.permission)
        val actions = listOf("START", "CANCEL", "STATUS")
        actions.forEach { suffix ->
            val receivers = context.packageManager.queryBroadcastReceivers(Intent("com.no5.map.benchmark.$suffix"), 0)
            assertTrue("Missing MAP benchmark action $suffix", receivers.any { it.activityInfo?.name == RendererBenchmarkReceiver::class.java.name })
        }
        assertNull(MainActivity.benchmarkHost.get())
    }
}
