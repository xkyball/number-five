package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

internal fun currentResidentLiveFixture(): CurrentResidentLiveSnapshot {
    val id = "00000000-0000-4000-8000-000000000001"
    val geometry = ChartSurfaceGeometry.fit(1000, 600)
    val camera = ChartViewport(40.0, -70.0, .01, .2, geometry.frameWidth, geometry.frameHeight)
    val catalog = ChartResidentCatalog("a".repeat(64), listOf(ChartResidentSource("noaa-s57", "US5TEST0", "b".repeat(64), 1, 0, 0,
        listOf(ChartResidentFile("/synthetic/US5TEST0.000", "c".repeat(64))))), ChartResidentBudget(25))
    val presentation = ChartPresentation(); val settings = S52Settings(); val overlays = ChartOverlays()
    val identity = CurrentResidentLiveIdentity(id, catalog, 2, 4, 6, 8, 10, 12, "policy", presentation, settings, settings, overlays)
    val saved = BenchmarkUserState(id, "current", emptyList(), camera, presentation, settings, overlays,
        ChartResidentActivation("current", catalog, camera, ChartNavigationBounds(0, emptyList())), 2, "policy")
    return CurrentResidentLiveSnapshot(id, identity, saved, geometry, 14,
        ChartBufferSnapshot(16, 1, 14, ChartFrame(1, "owner", geometry, camera, sourceGeneration = 2, contentGeneration = 6), true))
}

class CurrentResidentLiveAdmissionTest {
    @Test fun currentLiveFullParityDefaultsToDenyBeforeNativeOrRunAdmission() = runBlocking {
        val engine = NativeChartEngine(CameraControlClock { 100L }, NativeCameraApplication { _, view -> view })
        try {
            val config = RendererBenchmarkConfig(1, runKind = RendererRunKind.LIVE_INPUT,
                currentResidentLive = CurrentResidentLiveArm("00000000-0000-4000-8000-000000000001"),
                liveReplay = false, liveDurationMs = 15_000, currentLiveParityOnly = true)
            val rejected = engine.startRendererBenchmark(config)
            assertFalse(rejected.success); assertTrue(rejected.message.contains("DUMP"))
            assertFalse(engine.status.value.rendererBenchmark.active)
        } finally { engine.close() }
    }
    @Test fun permissionRevocationRejectsEachNewExplicitParityAdmissionWithoutTouchingTheNativeOwner() = runBlocking {
        var checks = 0
        val engine = NativeChartEngine(CameraControlClock { 100L }, NativeCameraApplication { _, view -> view },
            diagnosticPermission = { checks++; false })
        try {
            val config = RendererBenchmarkConfig(1, runKind = RendererRunKind.LIVE_INPUT,
                currentResidentLive = CurrentResidentLiveArm("00000000-0000-4000-8000-000000000001"),
                liveReplay = false, liveDurationMs = 15_000, currentLiveParityOnly = true)
            repeat(2) { assertFalse(engine.startRendererBenchmark(config.copy(normalRenderAbort = it == 1)).success) }
            assertEquals(2, checks); assertFalse(engine.status.value.rendererBenchmark.active)
        } finally { engine.close() }
    }
    @Test fun singleSlotIsExactAndNeverTakesAReplacementSourceOrOldProcessRecord() {
        val slot = CurrentResidentLiveSnapshotSlot(); val first = currentResidentLiveFixture()
        slot.remember(first); assertSame(first, slot.get(first.id)); assertNull(slot.get(UUID.randomUUID().toString()))
        assertThrows(IllegalArgumentException::class.java) {
            first.copy(id = UUID.randomUUID().toString(), saved = first.saved.copy(runId = UUID.randomUUID().toString()))
        }
    }

    @Test fun snapshotAndJournalOwnersFullCameraGeometryAndSourceMustAllMatch() {
        val a = currentResidentLiveFixture()
        for (change in listOf<() -> Unit>(
            { a.copy(id = UUID.randomUUID().toString()) },
            { a.copy(identity = a.identity.copy(catalog = a.identity.catalog.copy(sources = emptyList()))) },
            { a.copy(full = a.full.copy(producerCompleted = false)) },
            { a.copy(full = a.full.copy(stage = ChartContentStage.BASE_AREAS)) },
            { a.copy(cameraRevision = 13) },
            { a.copy(full = a.full.copy(frame = a.full.frame.copy(sourceGeneration = 3))) },
            { a.copy(full = a.full.copy(frame = a.full.frame.copy(contentGeneration = 7))) },
            { a.copy(full = a.full.copy(frame = a.full.frame.copy(viewport = a.saved.viewport.copy(longitude = -69.0)))) },
            { a.copy(saved = a.saved.copy(bufferTransformMode = ChartBufferTransformMode.CONTROL)) })) {
            assertThrows(IllegalArgumentException::class.java) { change() }
        }
    }

    @Test fun replacingOrClearingTheSingleSlotCannotRevokeSomebodyElsesSnapshot() {
        val slot = CurrentResidentLiveSnapshotSlot(); val a = currentResidentLiveFixture()
        val id = UUID.randomUUID().toString(); val b = a.copy(id = id, saved = a.saved.copy(runId = id))
        slot.remember(a); slot.remember(b); assertNull(slot.get(a.id)); assertSame(b, slot.get(b.id))
        slot.clear(a.id); assertSame(b, slot.get(b.id)); slot.clear(b.id); assertNull(slot.get(b.id))
        slot.remember(a); slot.close(); assertNull(slot.get(a.id))
    }

    @Test fun sourceRouterHasNoBostonFallbackOnCurrentSuccessOrFailure() = runBlocking {
        val a = currentResidentLiveFixture(); val calls = mutableListOf<String>()
        assertEquals(a.id, prepareLiveInputSource(a, { calls += "verify-current"; it.id }, {
            calls += "open-boston/style/filter/overlay/reset"; "boston"
        }))
        assertEquals(listOf("verify-current"), calls)
        val failed = IllegalStateException("SOURCE_FREEZE_LOST")
        try { prepareLiveInputSource(a, { throw failed }, { calls += "forbidden-fallback"; "boston" }); fail() }
        catch (actual: IllegalStateException) { assertSame(failed, actual) }
        assertEquals(listOf("verify-current"), calls)
        assertEquals("boston", prepareLiveInputSource(null, { error("unexpected") }, { calls += "legacy-boston"; "boston" }))
        assertEquals(listOf("verify-current", "legacy-boston"), calls)
    }

    @Test fun currentViewReportIsPrivateAndDoesNotCreateTheContinuousBostonMarker() {
        val private = RendererLiveInputBuffer("test", replayRecipe = RendererReplayRecipe.P0_CONTINUOUS, currentPrivateView = true)
        assertFalse(private.diagnosticMarkerEnabled)
        val report = private.finishReport()
        assertEquals("CURRENT_RESIDENT_FROZEN_DATA", report.getValue("sourceScope").jsonPrimitive.content)
        assertTrue(report.getValue("coordinates").jsonPrimitive.content.startsWith("PRIVATE_CURRENT_VIEW"))
        val legacy = RendererLiveInputBuffer("legacy", replayRecipe = RendererReplayRecipe.P0_CONTINUOUS)
        assertTrue(legacy.diagnosticMarkerEnabled)
        assertEquals("PUBLIC_BOSTON", legacy.finishReport().getValue("sourceScope").jsonPrimitive.content)
    }

    @Test fun finalFreezeIncludesObservationsArrivingAfterTheEarlierValidityCheck() {
        for ((late, expected) in listOf<Pair<(RendererLiveInputBuffer, Long) -> Unit, String>>(
            ({ buffer: RendererLiveInputBuffer, _: Long -> buffer.texture(99, true, "UPDATE_CALLBACK") }) to "Unknown live texture basis",
            ({ buffer: RendererLiveInputBuffer, id: Long -> buffer.disposition(id, "RUN_CAMERA_DISABLED") }) to "Input stream rejected or cancelled",
            ({ buffer: RendererLiveInputBuffer, id: Long -> repeat(9) { buffer.disposition(id, "COALESCED_CAMERA") } }) to "Bounded observation capacity exceeded")) {
            val b = RendererLiveInputBuffer("final", maxRows = 8, currentPrivateView = true)
            b.startLive()
            val id = b.input(100, 200, 202, 101, 2, 7, -1, 1,
                intArrayOf(7), floatArrayOf(10f, 20f), RendererInputOrigin.VIEW_INJECTED)
            b.camera(id, 1, 1, ChartViewport(), false)
            assertNull(b.liveValidityError()) // Earlier checkFinished boundary.
            late(b, id); b.finishLive(); b.finishReport()
            assertEquals(expected, currentResidentLiveCompletionError(false, null, null, b.liveValidityError()))
        }
    }

    @Test fun finalCancelDeadlineAndFreezeLossCannotCompleteSuccessfully() {
        assertEquals("client cancelled", currentResidentLiveCompletionError(true, "client cancelled", null, null))
        assertEquals("device deadline exceeded", currentResidentLiveCompletionError(true, "device deadline exceeded", null, null))
        assertEquals("Messung abgebrochen", currentResidentLiveCompletionError(true, "", null, null))
        assertEquals("SOURCE_FREEZE_LOST", currentResidentLiveCompletionError(false, null, "SOURCE_FREEZE_LOST", null))
        assertEquals("", currentResidentLiveCompletionError(false, null, null, null))
    }
}
