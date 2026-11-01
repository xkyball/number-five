package com.no5.domain

/** Equality fence for the exact measured ping. Original UTC is never rewritten. */
data class SonarOriginClockRequest(val serverSessionId: String, val generation: Long,
    val displayGeneration: Long, val pingId: Long, val originalEpochMs: Long) {
    init {
        require(serverSessionId.isNotBlank() && serverSessionId.length <= 128)
        require(generation >= 0 && displayGeneration >= 0 && pingId >= 0 && originalEpochMs > 0)
    }
}

/** Source-clock upper bound, including source sample quantization and the entire request queue.
 * Monotonic nanos use the same injected clock for request, response and consumption.
 * No guest wall clock, offset estimate, negative clamp or stale-value fallback.
 */
data class SonarOriginClockProof(val request: SonarOriginClockRequest,
    val originClockUpperEpochMs: Long, val requestStartedNanos: Long, val receivedNanos: Long) {
    fun upperAgeMs(nowNanos: Long): Long? = try {
        if (requestStartedNanos < 0 || receivedNanos < requestStartedNanos || nowNanos < receivedNanos ||
            originClockUpperEpochMs < request.originalEpochMs) null
        else {
            val elapsed = Math.subtractExact(nowNanos, requestStartedNanos)
            // Round elapsed UP, including sub-millisecond network/queue/wait time.
            val elapsedMs = elapsed / 1_000_000 + if (elapsed % 1_000_000 == 0L) 0 else 1
            Math.addExact(Math.subtractExact(originClockUpperEpochMs, request.originalEpochMs), elapsedMs)
                .takeIf { it in 0..2000 }
        }
    } catch (_: ArithmeticException) { null }
}

/** Last optional proof only: scalar timings, never a ping, token, callback or body.
 * Unknown intervals remain null. These diagnostics never grant admission. */
data class SonarOriginClockDiagnostics(val sequence:Long=0, val state:String="IDLE",
    val phase:String="NONE", val queueNanos:Long?=null, val readNanos:Long?=null,
    val consumerWaitNanos:Long?=null, val fullNanos:Long?=null,
    val sampleMinusOriginalMs:Long?=null, val responseBytes:Int=0,
    val httpFailureStatus:Int?=null, val exceptionType:String="NONE",
    val consumerWakeFailures:Int=0)
