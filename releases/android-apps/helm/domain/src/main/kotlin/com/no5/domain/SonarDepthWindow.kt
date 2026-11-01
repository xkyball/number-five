package com.no5.domain

enum class SonarDepthMode { DIRECT, SMOOTHED }
enum class SonarDepthState { CURRENT, HELD, UNAVAILABLE }
data class SonarDepthSettings(val mode: SonarDepthMode = SonarDepthMode.SMOOTHED, val windowSeconds: Int = 5) {
    init { require(windowSeconds in 1..30) }
}
data class SonarDepthDisplay(val valueM: Double? = null, val state: SonarDepthState = SonarDepthState.UNAVAILABLE,
    val pingId: Long? = null, val measuredAtEpochMs: Long? = null, val receivedAtNanos: Long? = null,
    val sampleCount: Int = 0, val complete: Boolean = true)

/** Scalar display statistics only. Neither a profile history nor an acoustic validity decision. */
class SonarDepthWindow(private val capacity: Int = 8192) {
    init { require(capacity > 0) }
    private val values = DoubleArray(capacity)
    private val measured = LongArray(capacity)
    private val received = LongArray(capacity)
    private var head = 0
    private var count = 0
    private var sum = 0.0
    private var inputId = -1L
    private var frontier = Long.MIN_VALUE
    private var inputValid = false
    private var direct: SonarDepthDisplay = SonarDepthDisplay()
    private var binding: SonarDepthDisplay = SonarDepthDisplay()
    private var lostMeasured: Long? = null
    private var lostReceived: Long? = null
    val allocatedBytes: Int get() = capacity * 24
    fun clear() {
        head = 0; count = 0; sum = 0.0; inputId = -1; frontier = Long.MIN_VALUE
        inputValid = false; direct = SonarDepthDisplay(); binding = SonarDepthDisplay()
        lostMeasured = null; lostReceived = null
    }
    fun observe(valueM: Double?, pingId: Long, measuredAtEpochMs: Long, receivedAtNanos: Long,
                nowNanos: Long, settings: SonarDepthSettings): Boolean {
        if (pingId <= inputId || measuredAtEpochMs < frontier) return false
        inputId = pingId; frontier = measuredAtEpochMs
        evict(nowNanos, settings.windowSeconds)
        inputValid = valueM != null && valueM.isFinite() && valueM >= 0.0 && receivedAtNanos <= nowNanos
        direct = if (inputValid) SonarDepthDisplay(valueM, SonarDepthState.CURRENT, pingId,
            measuredAtEpochMs, receivedAtNanos, 1) else SonarDepthDisplay()
        if (!inputValid) return true
        if (nowNanos - receivedAtNanos >= settings.windowSeconds * 1_000_000_000L) { inputValid = false; direct = SonarDepthDisplay(); return true }
        if (count == capacity) {
            // Preserve truth about the incomplete derived window; RAW adoption is unaffected.
            lostMeasured = measured[head]; lostReceived = received[head]
            sum -= values[head]; head = (head + 1) % capacity; count--
        }
        val at = (head + count) % capacity
        values[at] = valueM!!; measured[at] = measuredAtEpochMs; received[at] = receivedAtNanos
        sum += valueM; count++; binding = direct
        return true
    }
    private fun evict(nowNanos: Long, seconds: Int) {
        while (count > 0 && (frontier - measured[head] >= seconds * 1000L ||
                    nowNanos - received[head] >= seconds * 1_000_000_000L)) {
            sum -= values[head]; head = (head + 1) % capacity; count--
        }
        if (count == 0) sum = 0.0
        if (lostMeasured?.let { frontier - it >= seconds * 1000L } == true ||
            lostReceived?.let { nowNanos - it >= seconds * 1_000_000_000L } == true) {
            lostMeasured = null; lostReceived = null
        }
    }
    fun read(settings: SonarDepthSettings, nowNanos: Long, sourceLive: Boolean): SonarDepthDisplay {
        evict(nowNanos, settings.windowSeconds)
        if (settings.mode == SonarDepthMode.DIRECT)
            return if (inputValid && sourceLive) direct else SonarDepthDisplay()
        if (count == 0 || lostMeasured != null) return SonarDepthDisplay(complete = lostMeasured == null)
        return binding.copy(valueM = (sum / count).coerceAtLeast(0.0),
            state = if (inputValid && sourceLive) SonarDepthState.CURRENT else SonarDepthState.HELD,
            sampleCount = count)
    }
}
