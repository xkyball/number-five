package com.no5.helm.chart

enum class RendererRunKind { SCRIPTED, LIVE_INPUT, RESIDENT_CAPACITY }
enum class RendererInputOrigin { PLATFORM_DISPATCH, VIEW_INJECTED }
enum class RendererReplayRecipe { LEGACY, P0_CONTINUOUS }
fun rendererDiagnosticMarkerPoint(geometry: ChartSurfaceGeometry): ScreenPoint {
    val edge = minOf(geometry.surfaceWidth, geometry.surfaceHeight)
    return ScreenPoint(geometry.surfaceWidth / 2.0 - edge * .18, geometry.surfaceHeight / 2.0 - edge * .10)
}

/** One bounded run. No callback here is a Window/Present observation. */
interface RendererLiveInputSink {
    val replayRecipe: RendererReplayRecipe get() = RendererReplayRecipe.LEGACY
    val diagnosticMarkerEnabled: Boolean get() = replayRecipe == RendererReplayRecipe.P0_CONTINUOUS
    val repaintTransforms: Boolean get() = true
    fun markerTarget(point: ScreenPoint) {}
    fun input(uptimeMs: Long, beforeMonoNs: Long, afterMonoNs: Long, dispatchUptimeMs: Long,
        action: Int, changedId: Int, historyIndex: Int, gesture: Long, pointerIds: IntArray,
        positions: FloatArray, origin: RendererInputOrigin): Long
    fun <T> withInput(id: Long, block: () -> T): T
    fun currentInput(): Long
    fun camera(inputId: Long, revision: Long, generation: Long, viewport: ChartViewport, applied: Boolean)
    fun disposition(inputId: Long, reason: String, replacement: Long = 0)
    fun buffer(buffer: ChartBufferSnapshot, completed: Boolean)
    /** A sampled SurfaceTexture timestamp. Neither phase proves an atomic image/matrix pair or display. */
    fun texture(tag: Long, knownBasis: Boolean, phase: String = "UNSPECIFIED")
    fun transform(base: ChartBufferSnapshot?, targetRevision: Long, transformRevision: Long,
        matrix: FloatArray, beforeMonoNs: Long, afterMonoNs: Long)
    fun replay(state: String, delivered: Int = 0, maxLatenessMs: Long = 0)
    fun claimReplay(): Boolean
}

/** Fixed public-input recipe: center-relative offsets in thousandths of the shorter surface edge. */
object RendererTouchReplay {
    const val ASSET = "renderer-benchmark/t0-touch-v1.csv"
    const val SHA256 = "5033759ff3f0e1a535ca60af3b4b9ff70a7f839c631a28c11b53eda18fc90f7e"
    fun asset(recipe: RendererReplayRecipe) = if (recipe == RendererReplayRecipe.LEGACY) ASSET else "renderer-benchmark/p0-continuous-v1.csv"
    fun sha256(recipe: RendererReplayRecipe) = if (recipe == RendererReplayRecipe.LEGACY) SHA256 else "0c45af267fb03e91536aadfc7e03381612d7b48a5f1f8dc46421056bcde73a5f"
    data class Pointer(val id: Int, val dx: Int, val dy: Int)
    data class Step(val offsetMs: Long, val action: Int, val pointers: List<Pointer>)
    fun parse(text: String, recipe: RendererReplayRecipe = RendererReplayRecipe.LEGACY): List<Step> {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        require(hash == sha256(recipe)) { "T0 touch replay asset pin mismatch" }
        return text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val parts = line.split(',')
            Step(parts[0].toLong(), parts[1].toInt(), parts.drop(2).map { part ->
                val p = part.split(':'); Pointer(p[0].toInt(), p[1].toInt(), p[2].toInt()) })
        }.toList().also { steps ->
            require(steps.isNotEmpty() && steps.size <= 512 && steps.last().offsetMs <= 10_000)
            require(steps.zipWithNext().all { (a, b) -> b.offsetMs > a.offsetMs })
            require(steps.all { it.pointers.size in 1..2 && it.pointers.map(Pointer::id).distinct().size == it.pointers.size &&
                it.pointers.all { p -> p.dx in -250..250 && p.dy in -250..250 } })
        }
    }
}
