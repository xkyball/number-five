package com.no5.helm

import com.no5.helm.chart.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real suspend-boundary regression for the fixture's stale2→current3-cell original oracle. */
class RendererSnapshotBoundOriginalTest {
    private val session = "84a77273-8c08-4a16-a7ee-3949c523934e"
    private val geometry = ChartSurfaceGeometry.fit(1000, 600)
    private val viewport = ChartViewport(width = 1000, height = 600)
    private fun state(cells: List<String> = listOf("cell-a", "cell-b", "cell-c"), content: Long = 4): ChartEngineStatus =
        ChartEngineStatus(initialized = true, chartLoaded = true, surfaceAttached = true,
            viewport = viewport, loadedChartCells = cells, residentCatalogActive = true,
            residentCatalogGeneration = "a".repeat(64), residentCameraReady = true,
            residentStartState = ChartResidentStartState.READY, chartSourceGeneration = 1,
            chartContentGeneration = content, surfaceGeneration = 2, presentationGeneration = 3,
            confirmedFrame = ChartFrame(27, "$session:2:5", geometry, viewport,
                sourceGeneration = 1, contentGeneration = content))
    private fun pins(value: ChartEngineStatus = state()): RendererOriginalSnapshotPins =
        RendererOriginalSnapshotPins(session, "a".repeat(64), 1, value.chartContentGeneration, 2, 3,
            value.presentation, value.objectFilters.effective,
            ChartBufferSnapshot(1001, 9, 5, ChartFrame(1001, "t0:$session:9:5", geometry, viewport,
                sourceGeneration = 1, contentGeneration = value.chartContentGeneration), true),
            value.loadedChartCells)
    private fun refused(block: suspend () -> Unit) = runBlocking {
        try { block(); fail("A changed binding must fail without retry or writeback") }
        catch (_: IllegalStateException) { }
    }

    @Test fun loadingBeforeSnapshotReturnBindsThreeCellsInsteadOfTheEarlierTwo() = runBlocking {
        var current = state(listOf("cell-a", "cell-b"), content = 2)
        val old = current
        val entered = CompletableDeferred<Unit>(); val completed = CompletableDeferred<Unit>()
        var snapshots = 0; var statusReads = 0
        val captured = async {
            rendererSnapshotBoundOriginal(snapshot = {
                snapshots++; entered.complete(Unit); completed.await()
                "actual-snapshot" to pins(current)
            }, status = { statusReads++; current })
        }
        entered.await()
        assertEquals(0, statusReads) // No pre-IO status can become the restoration oracle.
        current = state()
        completed.complete(Unit)
        val original = captured.await()
        assertEquals(listOf("cell-a", "cell-b"), old.loadedChartCells)
        assertEquals(3, original.state.loadedChartCells.size)
        assertEquals(current, original.state)
        assertEquals("actual-snapshot", original.snapshot)
        assertEquals(1, snapshots); assertEquals(2, statusReads)
    }

    @Test fun foreignContentAfterSnapshotIsRefusedWithoutRetryOrOverwrite() {
        val captured = state(); val foreign = state(content = 6)
        var snapshots = 0; var current = captured
        refused {
            rendererSnapshotBoundOriginal(snapshot = {
                snapshots++; current = foreign; "old" to pins(captured)
            }, status = { current })
        }
        assertEquals(1, snapshots); assertSame(foreign, current)
    }

    @Test fun sameGenerationDoesNotHideDifferentLoadedCellMembershipOrOrder() {
        val original = state()
        for (cells in listOf(listOf("cell-a", "cell-b"), original.loadedChartCells.reversed())) {
            refused { rendererSnapshotBoundOriginal({ "bound" to pins(original) }, { original.copy(loadedChartCells = cells) }) }
        }
    }

    @Test fun sourceSurfaceCatalogPresentationFiltersOrModeChangedAfterSnapshotAreRefused() {
        val original = state()
        val changes = listOf(original.copy(chartSourceGeneration = 9), original.copy(surfaceGeneration = 10),
            original.copy(residentCatalogGeneration = "b".repeat(64)), original.copy(presentationGeneration = 11),
            original.copy(presentation = original.presentation.copy(landRelief = true)),
            original.copy(objectFilters = original.objectFilters.copy(
                effective = original.objectFilters.effective.copy(soundings = false))),
            original.copy(bufferTransformMode = ChartBufferTransformMode.CONTROL),
            original.copy(confirmedFrame = null), original.copy(rendererBenchmarkRecoveryPending = true),
            original.copy(rendererCapture = original.rendererCapture.copy(active = true)),
            original.copy(rendererBenchmark = original.rendererBenchmark.copy(active = true)))
        for (foreign in changes) refused {
            rendererSnapshotBoundOriginal({ "bound" to pins(original) }, { foreign })
        }
    }

    @Test fun confirmedFrameAndCameraAuthorityCannotUseAnotherRevisionOrInterpretation() {
        val original = state(); val frame = original.confirmedFrame!!
        for (changed in listOf(frame.copy(cameraToken = "$session:2:6"), frame.copy(sourceGeneration = 8),
            frame.copy(contentGeneration = 9), frame.copy(viewport = viewport.copy(metresToPixels = .1)),
            frame.copy(geometry = ChartSurfaceGeometry.fit(1200, 600)))) {
            refused { rendererSnapshotBoundOriginal({ "bound" to pins(original) }, { original.copy(confirmedFrame = changed) }) }
        }
        runBlocking {
            // Confirmed frame revision27, camera revision5 and registry stamp1001 remain distinct.
            assertEquals(27L, rendererSnapshotBoundOriginal({ "bound" to pins(original) }, { original }).state.confirmedFrame!!.revision)
        }
    }

    @Test fun secondStatusReadRejectsNewFullWithoutInventingAnotherSnapshot() {
        val original = state(); val newer = original.copy(confirmedFrame = original.confirmedFrame!!.copy(revision = 28))
        var reads = 0; var snapshots = 0
        refused {
            rendererSnapshotBoundOriginal({ snapshots++; "bound" to pins(original) }, {
                if (++reads == 1) original else newer
            })
        }
        assertEquals(1, snapshots); assertEquals(2, reads)
    }

    @Test fun snapshotFailurePropagatesWithoutStatusReadRetryOrMutation() {
        val failure = IllegalStateException("actual snapshot refused")
        var reads = 0; var snapshots = 0
        runBlocking {
            try {
                rendererSnapshotBoundOriginal<String>({ snapshots++; throw failure }, { reads++; state() })
                fail("Original refusal must propagate")
            } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        }
        assertEquals(1, snapshots); assertEquals(0, reads)
    }

    @Test fun originalFullMustBeCompletedFullAtTheExactSourceAndContent() {
        val original = state(); val bound = pins(original); val full = bound.full
        for (invalid in listOf(full.copy(stamp = 0), full.copy(producerCompleted = false),
            full.copy(stage = ChartContentStage.BASE_AREAS),
            full.copy(frame = full.frame.copy(sourceGeneration = 8)),
            full.copy(frame = full.frame.copy(contentGeneration = 9)))) {
            refused { rendererSnapshotBoundOriginal({ "bound" to bound.copy(full = invalid) }, { original }) }
        }
    }

    @Test fun loadedNamesComeOnlyFromTheFrozenCatalogAndPreserveNativeKeyOrder() {
        val a = ChartResidentSource("synthetic", "cell-a", "key-a", 1, 0, 0, emptyList())
        val b = a.copy(name = "cell-b", sourceKey = "key-b", order = 1)
        val catalog = ChartResidentCatalog("a".repeat(64), listOf(a, b), ChartResidentBudget())
        assertEquals(listOf("cell-b", "cell-a"), rendererSnapshotLoadedNames(catalog, listOf("key-b", "key-a")))
        for (keys in listOf(listOf("unknown"), listOf("key-a", "key-a"))) {
            try { rendererSnapshotLoadedNames(catalog, keys); fail("Unproven resident keys must not become names") }
            catch (_: IllegalStateException) { }
        }
        try { rendererSnapshotLoadedNames(catalog.copy(sources = listOf(a, a)), listOf("key-a")); fail("Ambiguous source key") }
        catch (_: IllegalStateException) { }
    }
}
