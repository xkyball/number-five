package com.no5.helm.ocean

/** Display-clock gate targeting about 30 LOW layer updates per second, not measured GPU FPS. */
class OceanFrameCadence {
    private var lastPublishedNanos: Long? = null

    fun reset() { lastPublishedNanos = null }

    fun shouldPublish(frameNanos: Long, quality: OceanQuality): Boolean {
        if (quality == OceanQuality.STATIC) return false
        val previous = lastPublishedNanos
        if (quality == OceanQuality.LOW && previous != null && frameNanos >= previous &&
            frameNanos - previous < LOW_INTERVAL_NANOS - ROUNDING_TOLERANCE_NANOS) return false
        lastPublishedNanos = frameNanos
        return true
    }

    companion object {
        const val LOW_INTERVAL_NANOS = 1_000_000_000L / 30L
        // Rounded 60/120 Hz frame intervals can miss the deadline by a few nanoseconds.
        // A bounded 0.5 ms tolerance avoids aliasing to 20 Hz without allowing catch-up bursts.
        const val ROUNDING_TOLERANCE_NANOS = 500_000L
    }
}
