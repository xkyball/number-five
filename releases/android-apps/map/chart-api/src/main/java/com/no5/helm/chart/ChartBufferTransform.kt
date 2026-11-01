package com.no5.helm.chart

/** Opt-in T0 experiment. OFF preserves the existing product touch path. */
enum class ChartBufferTransformMode {
    OFF, CONTROL, TRANSFORM_ONLY, TRANSFORM_WITH_RENDER, TRANSFORM_RENDER_WITHHELD, TRANSFORM_AREAS_FIRST, TRANSFORM_AREAS_REUSE, TRANSFORM_AREAS_PROGRESS, TRANSFORM_AREAS_PROGRESS_FIXED,
    TRANSFORM_AREAS_PROGRESS_PADDED_CONTROL, TRANSFORM_AREAS_PROGRESS_RESERVE;
    val transforms: Boolean get() = this != OFF && this != CONTROL
}

enum class ChartCameraMotionState { IDLE, DRAG, FLING }

enum class ChartContentStage { BASE_AREAS, REUSED_DETAILS_WITH_BASE, FULL }

/** Camera of an exactly tagged TextureView buffer; this is not a Window/Display endpoint. */
data class ChartBufferSnapshot(val stamp: Long, val generation: Long, val cameraRevision: Long,
    val frame: ChartFrame, val producerCompleted: Boolean = false, val stage: ChartContentStage = ChartContentStage.FULL,
    val presentationSlot: Int = -1, val raster: ChartBufferRaster? = null)
