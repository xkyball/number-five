package com.no5.helm.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.util.Log

/** Immutable callback admission: never read a later attachment's owner from a release worker. */
internal data class ChartSurfaceLoan(val surface: Surface, val generation: Long, val attachmentOwner: Any?)

/** Exact producer ownership survives a replacement until its own destruction callback. */
internal class ChartSurfaceLoans {
    private val textures = arrayOfNulls<SurfaceTexture>(64)
    private val surfaces = arrayOfNulls<Surface>(64)
    private val generations = LongArray(64)
    private val owners = arrayOfNulls<Any>(64)
    private var nextGeneration = 0L
    @Synchronized fun find(texture: SurfaceTexture): Surface? {
        for (index in textures.indices) if (textures[index] === texture) return surfaces[index]
        return null
    }
    @Synchronized fun generation(texture: SurfaceTexture): Long {
        for (index in textures.indices) if (textures[index] === texture) return generations[index]
        return -1L
    }
    @Synchronized fun create(texture: SurfaceTexture): Surface? {
        find(texture)?.let { return it }
        if (texture.isReleased) return null
        val index = textures.indexOfFirst { it == null }
        if (index < 0) { ChartSurfaceLifecycleTrace.record("LOAN_LIMIT"); return null }
        return Surface(texture).also {
            textures[index] = texture; surfaces[index] = it; generations[index] = ++nextGeneration
        }
    }
    @Synchronized fun bind(surface: Surface, attachmentOwner: Any) {
        for (index in surfaces.indices) if (surfaces[index] === surface) { owners[index] = attachmentOwner; return }
    }
    @Synchronized fun remove(texture: SurfaceTexture): ChartSurfaceLoan? {
        for (index in textures.indices) if (textures[index] === texture) {
            val surface = surfaces[index] ?: return null
            val loan = ChartSurfaceLoan(surface, generations[index], owners[index])
            textures[index] = null; surfaces[index] = null; owners[index] = null; return loan
        }
        return null
    }
    /** The worker barrier has ended. A reused Texture must stay alive for its newer producer. */
    @Synchronized fun release(texture: SurfaceTexture, loan: ChartSurfaceLoan) {
        loan.surface.release()
        if (find(texture) == null) texture.release()
    }
}

/** Last 64 lifecycle events only; no Surface handles, session IDs, chart names or credentials. */
internal object ChartSurfaceLifecycleTrace {
    private val events = arrayOfNulls<String>(64)
    private var count = 0
    private var sequence = 0L
    @Synchronized fun record(event: String, slot: Int = -1, current: Boolean = true,
        width: Int = 0, height: Int = 0, paired: Boolean = false) {
        val row = "sequence=${++sequence} event=$event slot=$slot current=$current size=${width}x$height paired=$paired"
        events[((sequence - 1) % events.size).toInt()] = row
        count = minOf(count + 1, events.size)
        Log.i("No5MapSurface", row)
    }
    @Synchronized fun consumer(event: String, slot: Int, stamp: Long, currentStamp: Long,
        buffer: com.no5.helm.chart.ChartBufferSnapshot?, uiGeneration: Long, windowEpoch: Long,
        attachmentEpoch: Long, mode: String, visibility: Int, shown: Boolean, available: Boolean,
        currentTexture: Boolean, front: Int, cover: Boolean, scaleX: Float, scaleY: Float,
        translateX: Float, translateY: Float) {
        val row = "sequence=${++sequence} event=$event slot=$slot stamp=$stamp currentStamp=$currentStamp known=${buffer!=null}" +
            " bufferGeneration=${buffer?.generation?:-1} uiGeneration=$uiGeneration cameraRevision=${buffer?.cameraRevision?:-1}" +
            " sourceGeneration=${buffer?.frame?.sourceGeneration?:-1} contentGeneration=${buffer?.frame?.contentGeneration?:-1}" +
            " producerCompleted=${buffer?.producerCompleted==true} producerSlot=${buffer?.presentationSlot?:-2}" +
            " windowEpoch=$windowEpoch attachmentEpoch=$attachmentEpoch mode=$mode windowVisibility=$visibility" +
            " shown=$shown available=$available currentTexture=$currentTexture front=$front cover=$cover" +
            " scale=${scaleX},${scaleY} offset=${translateX},${translateY}"
        events[((sequence - 1) % events.size).toInt()] = row
        count = minOf(count + 1, events.size)
        Log.i("No5MapSurface", row)
    }
    @Synchronized fun snapshot(): List<String> = (sequence - count until sequence).mapNotNull {
        events[(it % events.size).toInt()]
    }
}
