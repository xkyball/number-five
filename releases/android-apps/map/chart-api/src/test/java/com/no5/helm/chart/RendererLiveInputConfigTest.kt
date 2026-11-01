package com.no5.helm.chart

import org.junit.Assert.*
import org.junit.Test

class RendererLiveInputConfigTest {
    @Test fun currentLiveParityIsExplicitSeparateAndCannotEnterGenericReuseOrTimingAdmission() {
        assertFalse(RendererBenchmarkConfig().currentLiveParityOnly)
        val arm = CurrentResidentLiveArm("00000000-0000-4000-8000-000000000001")
        val parity = RendererBenchmarkConfig(1, runKind = RendererRunKind.LIVE_INPUT,
            currentResidentLive = arm, liveReplay = false, liveDurationMs = 15_000, currentLiveParityOnly = true)
        assertTrue(parity.currentLiveParityOnly)
        assertTrue(parity.copy(normalRenderAbort = true).normalRenderAbort)
        assertThrows(IllegalArgumentException::class.java) { parity.copy(currentResidentLive = null) }
        assertThrows(IllegalArgumentException::class.java) { parity.copy(liveReplay = true) }
        assertThrows(IllegalArgumentException::class.java) { parity.copy(liveDurationMs = 10_000) }
        assertThrows(IllegalArgumentException::class.java) { parity.copy(viewportReuseParity = true) }
        assertThrows(IllegalArgumentException::class.java) { parity.copy(viewportReuseDetailed = true) }
        assertThrows(IllegalArgumentException::class.java) { parity.copy(viewportReuseCandidate = RendererViewportReuseVariant.OFF) }
        assertThrows(IllegalArgumentException::class.java) { parity.copy(liveMode = ChartBufferTransformMode.OFF) }
    }
    @Test fun currentResidentArmIsAnExplicitOpaqueReferenceAndDoesNotChangeDefaults() {
        assertNull(RendererBenchmarkConfig().currentResidentLive)
        val arm = CurrentResidentLiveArm("00000000-0000-4000-8000-000000000001")
        val current = RendererBenchmarkConfig(1, runKind = RendererRunKind.LIVE_INPUT, currentResidentLive = arm)
        assertFalse(current.normalRenderAbort); assertEquals(arm, current.copy(normalRenderAbort = true).currentResidentLive)
        assertThrows(IllegalArgumentException::class.java) { CurrentResidentLiveArm("unknown") }
        assertThrows(IllegalArgumentException::class.java) { RendererBenchmarkConfig(currentResidentLive = arm) }
        assertThrows(IllegalArgumentException::class.java) { current.copy(liveMode = ChartBufferTransformMode.TRANSFORM_ONLY) }
        assertThrows(IllegalArgumentException::class.java) { current.copy(viewportReuseCandidate = RendererViewportReuseVariant.ALL) }
        assertThrows(IllegalArgumentException::class.java) { current.copy(liveDurationMs = 30_001) }
    }
    @Test fun reuseAreaArmKeepsContinuousReplaySingleWorkerAndImmediateTransform() {
        val run = RendererBenchmarkConfig(1, runKind = RendererRunKind.LIVE_INPUT,
            liveMode = ChartBufferTransformMode.TRANSFORM_AREAS_REUSE,
            liveReplayRecipe = RendererReplayRecipe.P0_CONTINUOUS)
        assertTrue(run.liveMode.transforms)
        assertEquals(1, run.requestedWorkers)
        assertTrue(run.liveRepaintTransforms)
    }
    @Test fun areasFirstUsesTheSameLiveInputContractAndTransforms() {
        val run = RendererBenchmarkConfig(1, runKind = RendererRunKind.LIVE_INPUT,
            liveMode = ChartBufferTransformMode.TRANSFORM_AREAS_FIRST)
        assertTrue(run.liveMode.transforms)
        assertEquals(1, run.requestedWorkers)
        assertTrue(run.liveRepaintTransforms)
    }
    @Test fun scriptedDefaultsArePreservedAndPublicLiveRunRequiresSingleWorker() {
        val legacy = RendererBenchmarkConfig()
        assertEquals(RendererRunKind.SCRIPTED, legacy.runKind)
        assertFalse(legacy.normalRenderAbort)
        val live = RendererBenchmarkConfig(1, runKind = RendererRunKind.LIVE_INPUT)
        assertEquals(ChartBufferTransformMode.CONTROL, live.liveMode)
        assertTrue(live.liveReplay)
        assertFalse(live.normalRenderAbort)
        try { live.copy(requestedWorkers = 2); fail("T0 must not combine worker sweep") } catch (_: IllegalArgumentException) { }
        try { live.copy(repetitions = 2); fail("One live window per run token") } catch (_: IllegalArgumentException) { }
    }
    @Test fun normalAbortIsASeparateExplicitDefaultOffPlainFullControlDiagnostic() {
        val run = RendererBenchmarkConfig(1, runKind = RendererRunKind.LIVE_INPUT,
            liveMode = ChartBufferTransformMode.CONTROL, normalRenderAbort = true)
        assertTrue(run.normalRenderAbort); assertNull(run.viewportReuseCandidate)
        for (mode in ChartBufferTransformMode.entries.filter { it != ChartBufferTransformMode.CONTROL })
            assertThrows(IllegalArgumentException::class.java) { run.copy(liveMode = mode) }
        assertThrows(IllegalArgumentException::class.java) { RendererBenchmarkConfig(normalRenderAbort = true) }
        assertThrows(IllegalArgumentException::class.java) { run.copy(liveDurationMs = 30_001) }
        assertThrows(IllegalArgumentException::class.java) { run.copy(viewportReuseCandidate = RendererViewportReuseVariant.ALL) }
    }
    @Test fun offModeAndUnboundedLiveWindowsCannotEnterRunControl() {
        val live = RendererBenchmarkConfig(1, runKind = RendererRunKind.LIVE_INPUT)
        try { live.copy(liveMode = ChartBufferTransformMode.OFF); fail("OFF is not a T0 arm") } catch (_: IllegalArgumentException) { }
        try { live.copy(liveDurationMs = 30_001); fail("Window must be bounded") } catch (_: IllegalArgumentException) { }
    }
    @Test fun continuousReplayChoiceIsExplicitAndWithheldArmStillTransforms() {
        val run = RendererBenchmarkConfig(1, runKind = RendererRunKind.LIVE_INPUT,
            liveMode = ChartBufferTransformMode.TRANSFORM_RENDER_WITHHELD,
            liveReplayRecipe = RendererReplayRecipe.P0_CONTINUOUS)
        assertTrue(run.liveMode.transforms)
        assertEquals(RendererReplayRecipe.LEGACY, RendererBenchmarkConfig().liveReplayRecipe)
        assertNotEquals(RendererTouchReplay.SHA256, RendererTouchReplay.sha256(run.liveReplayRecipe))
        assertNotEquals(RendererTouchReplay.ASSET, RendererTouchReplay.asset(run.liveReplayRecipe))
    }
}
