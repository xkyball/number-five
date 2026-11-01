package com.no5.helm.ui

import com.no5.helm.chart.ChartEngineStatus
import com.no5.helm.chart.ChartPresentation
import com.no5.helm.chart.EngineResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The fast presentation path may return before its queued image is acknowledged.
 * A positive apply result also confirms metadata only. Neither it nor an empty
 * negative result permits saving an applied-only
 * profile. Keep the UI operation busy until this source/surface has a newer complete
 * frame and presentation generation, without sending the request a second time. */
internal suspend fun confirmChartStyle(
    status: StateFlow<ChartEngineStatus>,
    requested: ChartPresentation,
    timeoutMillis: Long = 20_000,
    apply: suspend () -> EngineResult,
): EngineResult {
    val before = status.value
    val previousFrameRevision = before.confirmedFrame?.revision
    val result = apply()
    if (!result.success && result.message.isNotBlank()) return result
    val completed = withTimeoutOrNull(timeoutMillis) {
        status.first { current ->
            val frame = current.confirmedFrame
            current.chartSourceGeneration != before.chartSourceGeneration ||
                current.surfaceGeneration != before.surfaceGeneration ||
                current.presentation == requested &&
                current.presentationGeneration > before.presentationGeneration &&
                !current.presentationPending && frame != null &&
                (previousFrameRevision == null || frame.revision > previousFrameRevision)
        }
    }
    return if (completed != null && completed.chartSourceGeneration == before.chartSourceGeneration &&
        completed.surfaceGeneration == before.surfaceGeneration) EngineResult(true, result.message)
    else EngineResult(false, "Speichern fehlgeschlagen. Die Änderungen bleiben erhalten.")
}
