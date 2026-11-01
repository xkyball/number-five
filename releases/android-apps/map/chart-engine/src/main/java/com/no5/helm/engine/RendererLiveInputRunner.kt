package com.no5.helm.engine

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal interface RendererLiveInputBackend {
    suspend fun prepare(): JsonObject
    fun checkReady()
    fun baseReady(): Boolean
    fun begin()
    fun checkFinished() = Unit
    fun end(): JsonObject
    suspend fun restore(): Boolean
}

/** The public live window has the same mandatory private recovery boundary as a scripted run. */
internal class RendererLiveInputRunner(private val backend: RendererLiveInputBackend,
    private val nowMs: () -> Long, private val pause: suspend () -> Unit = { delay(25) }) {
    suspend fun run(durationMs: Long, measurement: (suspend () -> Unit)? = null): JsonObject {
        var metadata = buildJsonObject {}; var observations = buildJsonObject {}
        var error = ""; var restorationError = ""; var restored = false
        try {
            metadata = backend.prepare()
            val warmDeadline = nowMs() + 15_000
            while (!backend.baseReady()) { backend.checkReady(); check(nowMs() < warmDeadline) { "Exact warm buffer not consumed within 15 seconds" }; pause() }
            backend.checkReady(); backend.begin()
            if (measurement == null) {
                val until = nowMs() + durationMs
                while (nowMs() < until) { backend.checkReady(); pause() }
            } else measurement() // Dedicated parity owner: no replay window, percentiles or timing claim.
            backend.checkReady()
            backend.checkFinished()
        } catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
        finally {
            withContext(NonCancellable) {
                try {
                    observations = backend.end()
                    if (error.isEmpty()) error = observations["completionError"]?.jsonPrimitive?.contentOrNull.orEmpty()
                } catch (failure: Exception) { if (error.isEmpty()) error = failure.message ?: failure.javaClass.simpleName }
                try { restored = backend.restore(); if (!restored) restorationError = "Restoration not confirmed" }
                catch (failure: Exception) { restorationError = failure.message ?: "Restoration failed" }
            }
        }
        return buildJsonObject {
            put("schema", "no5-renderer-live-input/1"); put("metadata", metadata); put("observations", observations)
            put("outcome", if (error.isEmpty()) "completed" else "aborted"); put("error", error)
            put("restored", restored); put("restorationError", restorationError)
        }
    }
}
