package com.no5.helm.chart

/** Diagnostic instrumentation has overhead. The fixed Boston A/B run compares it. */
data class RendererCaptureConfig(
    val durationSeconds: Int = 20,
    val maxFrames: Int = 600,
    val detailed: Boolean = true,
    val slowFrameMs: Double = 16.7,
) {
    init {
        require(durationSeconds in 5..60)
        require(maxFrames in 30..1200)
        require(slowFrameMs.isFinite() && slowFrameMs in 1.0..1000.0)
    }
}
data class RendererCaptureStatus(
    val active: Boolean = false, val frames: Int = 0,
    val elapsedMs: Double = 0.0, val stopReason: String = "",
)
