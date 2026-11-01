package com.no5.header.artwork

/** Allocation-free, deterministic overflow scrolling in the caller's design coordinates. */
object No5Marquee {
    const val PAUSE_MS = 1200L
    const val SPEED_PX_PER_SECOND = 48f

    fun needsAnimation(textWidth: Float, viewportWidth: Float, enabled: Boolean = true): Boolean =
        enabled && textWidth.isFinite() && viewportWidth.isFinite() && viewportWidth > 0f &&
            textWidth > viewportWidth + .5f

    /** Ping-pong motion includes a reading pause at both ends; fitting text never moves. */
    fun offset(textWidth: Float, viewportWidth: Float, elapsedMs: Long, enabled: Boolean = true): Float {
        if (!needsAnimation(textWidth, viewportWidth, enabled)) return 0f
        val overflow = textWidth - viewportWidth
        val travelMs = (overflow.toDouble() * 1000.0 / SPEED_PX_PER_SECOND).coerceAtLeast(1.0)
        val phase = elapsedMs.coerceAtLeast(0).toDouble() % (2.0 * (PAUSE_MS + travelMs))
        return when {
            phase <= PAUSE_MS -> 0f
            phase < PAUSE_MS + travelMs -> ((phase - PAUSE_MS) / travelMs * overflow).toFloat()
            phase <= 2.0 * PAUSE_MS + travelMs -> overflow
            else -> ((2.0 * (PAUSE_MS + travelMs) - phase) / travelMs * overflow).toFloat()
        }.coerceIn(0f, overflow)
    }
}
