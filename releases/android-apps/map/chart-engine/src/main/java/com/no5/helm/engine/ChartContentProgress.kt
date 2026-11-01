package com.no5.helm.engine

/** Bounded checkpoints during motion. New camera events cannot postpone useful full work forever. */
internal class ChartContentProgress(private val fullIntervalNanos: Long = 600_000_000L,
    private val maxPartialFrames: Int = 6) {
    init { require(fullIntervalNanos > 0 && maxPartialFrames > 0) }
    private var fullCompletedAt = Long.MIN_VALUE
    private var partialFrames = 0
    // The renderer owner writes this; the UI reads it after the loan image's ACK
    // to queue the owed checkpoint even when no further touch event arrives.
    @Volatile private var checkpointDebt = false
    val hasCheckpointDebt: Boolean get() = checkpointDebt
    fun reset() { fullCompletedAt = Long.MIN_VALUE; partialFrames = 0; checkpointDebt = false }
    /** Call only when the renderer has admitted an image slot for this job. */
    fun useReuse(moving: Boolean, nowNanos: Long, coverageGap: Boolean = false): Boolean {
        if (!moving || fullCompletedAt == Long.MIN_VALUE || checkpointDebt) return false
        if (partialFrames < maxPartialFrames && nowNanos - fullCompletedAt < fullIntervalNanos) return true
        // One current-area image may precede a due FULL. Its completion and
        // newer cameras cannot forgive this debt or borrow another area image.
        if (coverageGap) { checkpointDebt = true; return true }
        return false
    }
    fun completed(full: Boolean, nowNanos: Long) {
        if (full) { fullCompletedAt = nowNanos; partialFrames = 0; checkpointDebt = false } else partialFrames++
    }
}
