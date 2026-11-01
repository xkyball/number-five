package com.no5.helm

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test

class RendererStartupObservationTest {
    private val session = "84a77273-8c08-4a16-a7ee-3949c523934e"
    private val geometry = ChartSurfaceGeometry.fit(1000, 600)
    private val viewport = ChartViewport(width = 1000, height = 600)
    private fun full(stamp: Long = 6935232550170, camera: Long = 5, slot: Int = -1) = ChartBufferSnapshot(stamp, 2, camera,
        ChartFrame(stamp, "t0:$session:2:$camera", geometry, viewport, sourceGeneration = 1, contentGeneration = 4),
        producerCompleted = true, presentationSlot = slot)
    private fun state(buffer: ChartBufferSnapshot = full(), mode: ChartBufferTransformMode = ChartBufferTransformMode.OFF) =
        ChartEngineStatus(initialized = true, chartLoaded = true, surfaceAttached = true, viewport = viewport,
            confirmedFrame = if (mode == ChartBufferTransformMode.OFF)
                ChartFrame(9, "$session:1:${buffer.cameraRevision}", geometry, viewport, sourceGeneration = 1, contentGeneration = 4)
                else buffer.frame,
            chartSourceGeneration = 1, chartContentGeneration = 4, surfaceGeneration = 1, presentationGeneration = 1,
            residentCatalogActive = true, residentCameraReady = true, residentStartState = ChartResidentStartState.READY,
            residentCatalogGeneration = "catalog", loadedChartCells = listOf("a", "b", "c"), bufferTransformMode = mode)
    @Test fun firstPositiveUsesObservedAbsentAnchorRatherThanInventedProductionTime() {
        val observed = RendererStartupFirstObservation(100)
        assertEquals(RendererStartupObservationBracket(100, 120, 130, 0), observed.observe(120, 130, true))
    }

    @Test fun negativeReadStartIsTheConservativeLowerBound() {
        val observed = RendererStartupFirstObservation(100)
        assertNull(observed.observe(120, 150, false))
        // Appearance can race the end of the negative read; 150 would be an invalid lower bound.
        assertEquals(RendererStartupObservationBracket(120, 160, 180, 1), observed.observe(160, 180, true))
    }

    @Test fun laterSamplesCannotReplaceTheFirstObservation() {
        val observed = RendererStartupFirstObservation(100)
        val first = observed.observe(110, 120, true)
        assertSame(first, observed.observe(130, 140, false))
        assertSame(first, observed.observe(150, 160, true))
        assertEquals(3, observed.reads)
    }

    @Test fun reversedClockAndUnboundedPollingAreRejected() {
        val observed = RendererStartupFirstObservation(100, maximumReads = 1)
        assertThrows(IllegalArgumentException::class.java) { observed.observe(99, 110, false) }
        observed.observe(110, 120, false)
        assertThrows(IllegalStateException::class.java) { observed.observe(130, 140, true) }
    }

    @Test fun anEarlyFullBufferCannotStandForIncompleteOrUnknownRequiredGeometry() {
        val complete = RendererStartupGeometryCoverage(true, true, true, true, resident = 3, required = 3, missing = 0, loading = 0)
        assertTrue(complete.ready)
        assertFalse(complete.copy(resident = 2, missing = 1, complete = false).ready)
        assertFalse(complete.copy(knownCurrentCatalog = false).ready)
        assertFalse(complete.copy(loading = 1).ready)
        assertFalse(complete.copy(residentCatalogActive = false).ready)
        assertFalse(complete.copy(complete = false).ready)
    }

    @Test fun offCounterRevisionAndTaggedStampJoinOnlyThroughTheirActualCameraAuthority() {
        val buffer = full(); val status = state(buffer)
        assertNotEquals(status.confirmedFrame, buffer.frame)
        assertEquals(9L, status.confirmedFrame!!.revision)
        assertEquals(6935232550170L, buffer.frame.revision)
        assertNotNull(rendererStartupCameraAuthority(session, 5, status, buffer))
        // A numerically equal frame counter cannot disguise a foreign token/domain.
        assertNull(rendererStartupCameraAuthority(session, 5, status.copy(confirmedFrame =
            status.confirmedFrame!!.copy(revision = buffer.stamp, cameraToken = buffer.frame.cameraToken)), buffer))
    }

    @Test fun overlayRepaintKeepsCameraAuthorityButEveryCapturedStampIsStillExact() {
        val first = full(1001, slot = 0); val next = full(2001, slot = 1)
        val mode = ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE
        val a = rendererStartupCameraAuthority(session, 5, state(first, mode), first)
        val b = rendererStartupCameraAuthority(session, 5, state(next, mode), next)
        assertNotNull(a); assertEquals(a, b)
        val recorded = RendererStartupRecordedBuffer(next.stamp, 5, 2, 1, 4, true, "FULL", 1, viewport)
        assertTrue(recorded.matches(next))
        assertFalse(recorded.matches(first)) // Never substitute a previous/latest buffer for the captured stamp.
        assertFalse(recorded.copy(presentationSlot = 0).matches(next))
        assertFalse(recorded.copy(viewport = viewport.copy(metresToPixels = .1)).matches(next))
    }

    @Test fun foreignSessionCameraSourceContentGeometryOrIncompleteBufferCannotJoin() {
        val buffer = full(); val status = state(buffer)
        assertNull(rendererStartupCameraAuthority("foreign", 5, status, buffer))
        assertNull(rendererStartupCameraAuthority(session, 6, status, buffer))
        for (foreign in listOf(status.copy(chartSourceGeneration = 2), status.copy(chartContentGeneration = 5),
            status.copy(surfaceGeneration = 2), status.copy(viewport = viewport.copy(rotationRadians = .5)),
            status.copy(confirmedFrame = status.confirmedFrame!!.copy(geometry = ChartSurfaceGeometry.fit(1200, 600)))))
            assertNull(rendererStartupCameraAuthority(session, 5, foreign, buffer))
        for (invalid in listOf(buffer.copy(producerCompleted = false), buffer.copy(stage = ChartContentStage.BASE_AREAS),
            buffer.copy(frame = buffer.frame.copy(revision = buffer.stamp + 1)),
            buffer.copy(frame = buffer.frame.copy(cameraToken = "foreign"))))
            assertNull(rendererStartupCameraAuthority(session, 5, status, invalid))
    }
}
