package com.no5.helm.ui

import com.no5.helm.chart.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ChartStyleConfirmationTest {
    private val target = ChartPresentation("glacier", ChartSymbolSet.NO5)
    private fun initial() = ChartEngineStatus(initialized = true, chartLoaded = true,
        chartSourceGeneration = 7, surfaceGeneration = 3, presentationGeneration = 4,
        confirmedFrame = ChartFrame(10, "camera", ChartSurfaceGeometry.fit(2000, 1060), ChartViewport()))

    @Test fun queuedProfileWaitsForNewCompleteFrameAndDoesNotResubmit() = runBlocking {
        val status = MutableStateFlow(initial()); var calls = 0
        val done = async { confirmChartStyle(status, target) { calls++; EngineResult(false) } }
        yield()
        status.value = status.value.copy(presentation = target, presentationGeneration = 5)
        yield(); assertFalse("Old frame cannot confirm a new style", done.isCompleted)
        status.value = status.value.copy(presentationPending = true,
            confirmedFrame = status.value.confirmedFrame!!.copy(revision = 11))
        yield(); assertFalse("Incomplete relief cannot be saved", done.isCompleted)
        status.value = status.value.copy(presentationPending = false)
        assertTrue(done.await().success); assertEquals(1, calls)
    }
    @Test fun positiveApplyStillWaitsForACompleteNewImage() = runBlocking {
        val status = MutableStateFlow(initial())
        val done = async { confirmChartStyle(status, target) {
            status.value = status.value.copy(presentation = target, presentationGeneration = 5,
                presentationPending = true, confirmedFrame = status.value.confirmedFrame!!.copy(revision = 11))
            EngineResult(true)
        } }
        yield(); assertFalse("Positive apply is not a complete image", done.isCompleted)
        status.value = status.value.copy(presentationPending = false)
        assertTrue(done.await().success)
        val appliedOnly = MutableStateFlow(initial())
        assertFalse(confirmChartStyle(appliedOnly, target, 10) {
            appliedOnly.value = appliedOnly.value.copy(presentation = target, presentationGeneration = 5)
            EngineResult(true)
        }.success)
    }
    @Test fun explicitFailureIsNotReinterpreted() = runBlocking {
        val status = MutableStateFlow(initial())
        val result = confirmChartStyle(status, target) { EngineResult(false, "Superseded presentation request") }
        assertFalse(result.success); assertEquals("Superseded presentation request", result.message)
    }
    @Test fun sourceOrSurfaceReplacementCannotConfirmOldRequest() = runBlocking {
        for (source in listOf(true, false)) {
            val status = MutableStateFlow(initial())
            val result = confirmChartStyle(status, target) {
                status.value = status.value.copy(presentation = target, presentationGeneration = 5,
                    chartSourceGeneration = if (source) 8 else 7, surfaceGeneration = if (source) 3 else 4,
                    confirmedFrame = status.value.confirmedFrame!!.copy(revision = 11))
                EngineResult(false)
            }
            assertFalse(result.success)
        }
    }
    @Test fun appliedOnlyProfileTimesOutWithoutSuccess() = runBlocking {
        val status = MutableStateFlow(initial())
        assertFalse(confirmChartStyle(status, target, 10) {
            status.value = status.value.copy(presentation = target, presentationGeneration = 5)
            EngineResult(false)
        }.success)
    }
}
