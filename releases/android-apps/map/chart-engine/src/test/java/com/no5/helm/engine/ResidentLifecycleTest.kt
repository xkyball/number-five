package com.no5.helm.engine

import com.no5.helm.chart.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ResidentLifecycleTest {
    private val generation = "a".repeat(64)
    private val budget = ChartResidentBudget(25)
    private val catalog = ChartResidentCatalog(generation, emptyList(), budget)
    private val arm = ResidentCapacityArm(budget, ResidentCapacityCacheMode.EMPTY_GEOMETRY, generation, 7, 5_000)
    private val state = ChartEngineStatus(initialized = true, residentCatalogActive = true,
        rendererBenchmark = RendererBenchmarkStatus(active = true, runId = "run", phase = "sampling", runKind = RendererRunKind.RESIDENT_CAPACITY))

    @Test fun capacityWorkRequiresExactRunSourceCatalogBudgetAndSamplingPhase() {
        fun allowed(s: ChartEngineStatus = state, id: String? = "run", sampling: String? = "run",
            kind: RendererRunKind = RendererRunKind.RESIDENT_CAPACITY, a: ResidentCapacityArm? = arm,
            source: Long = 7, c: ChartResidentCatalog? = catalog, cancelled: Boolean = false) =
            residentCapacityLeaseMatches(s, id, sampling, kind, a, source, c, false, true, false, cancelled)
        assertTrue(allowed())
        assertFalse(allowed(id = "other")); assertFalse(allowed(sampling = null)); assertFalse(allowed(a = null))
        assertFalse(allowed(source = 8)); assertFalse(allowed(c = catalog.copy(generation = "b".repeat(64))))
        assertFalse(allowed(c = catalog.copy(budget = ChartResidentBudget(50))))
        assertFalse(allowed(kind = RendererRunKind.LIVE_INPUT)); assertFalse(allowed(kind = RendererRunKind.SCRIPTED))
        assertFalse(allowed(s = state.copy(rendererBenchmark = state.rendererBenchmark.copy(phase = "preparing"))))
        assertFalse(allowed(s = state.copy(rendererBenchmarkRecoveryPending = true))); assertFalse(allowed(cancelled = true))
    }

    @Test fun calibrationUsesPinnedActualDensityAndNativePpmClamp() {
        val status = ChartResidencyDecoder.decode(javaClass.getResource("/native-residency-status-v1.json")!!.readText())
        val camera = ChartViewport()
        val binding = ChartResidentBinding(ChartResidentActivation("test", catalog, camera, ChartNavigationBounds(1, emptyList())),
            7, 9, 3, camera, status, 4.25)
        assertEquals(42_500.0, binding.chartScaleFor(.1)!!, 1e-9)
        assertEquals(425.0, binding.chartScaleFor(100.0)!!, 1e-9)
        assertEquals(425_000_000.0, binding.chartScaleFor(.0000001)!!, 1e-4)
        assertNull(binding.chartScaleFor(Double.NaN)); assertNull(binding.chartScaleFor(0.0)); assertNull(binding.chartScaleFor(-1.0))
        assertNull(binding.copy(pixelsPerMillimetre = Double.NaN).chartScaleFor(.1))
    }

    @Test fun wholeCatalogPinsRejectChangedSourceAndUnpinnedUpdateWithoutDeletingFiles() {
        val directory = Files.createTempDirectory("resident-pins").toFile()
        try {
            val base = File(directory, "US5TEST0.000").also { it.writeText("base") }
            val sha = MessageDigest.getInstance("SHA-256").digest(base.readBytes()).joinToString("") { "%02x".format(it) }
            val source = ChartResidentSource("noaa-s57", "US5TEST0", "c".repeat(64), 1, 0, 0,
                listOf(ChartResidentFile(base.path, sha)))
            val activation = ChartResidentActivation("test", catalog.copy(sources = listOf(source)), ChartViewport(), ChartNavigationBounds(1, emptyList()))
            verifyResidentSourcePins(activation)
            base.writeText("changed")
            assertThrows(IllegalStateException::class.java) { verifyResidentSourcePins(activation) }
            assertTrue(base.isFile)
            base.writeText("base")
            val update = File(directory, "US5TEST0.001").also { it.writeText("update") }
            assertThrows(IllegalStateException::class.java) { verifyResidentSourcePins(activation) }
            assertTrue(update.isFile); assertTrue(base.isFile)
        } finally { directory.deleteRecursively() }
    }
}
