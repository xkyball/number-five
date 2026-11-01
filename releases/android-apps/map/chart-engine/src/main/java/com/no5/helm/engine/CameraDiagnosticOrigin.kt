package com.no5.helm.engine

/** Diagnostic origin only: never a command, lease, transport or completion owner.
 * The exact immutable capture instance is the generation key; numeric IDs alone are not unique. */
internal class CameraDiagnosticOrigin private constructor(
    val capture: RendererLiveInputBuffer, val runId: String, val inputId: Long,
    val source: Long, val surface: Long,
) {
    fun matches(current: RendererLiveInputBuffer?, expectedSource: Long, expectedSurface: Long): Boolean =
        current === capture && current?.runId == runId && source == expectedSource && surface == expectedSurface &&
            capture.diagnosticOriginOpen(inputId)
    companion object {
        /** Read ThreadLocal exactly here, on the original caller thread, before Actor enqueue. */
        fun capture(current: RendererLiveInputBuffer?, source: Long, surface: Long): CameraDiagnosticOrigin? {
            val sink = current ?: return null
            val id = sink.currentInput()
            return if (sink.diagnosticOriginOpen(id)) CameraDiagnosticOrigin(sink, sink.runId, id, source, surface) else null
        }
    }
}
