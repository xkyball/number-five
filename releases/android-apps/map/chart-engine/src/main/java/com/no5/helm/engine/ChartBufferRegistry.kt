package com.no5.helm.engine

import com.no5.helm.chart.ChartBufferSnapshot
import com.no5.helm.chart.ChartFrame
import com.no5.helm.chart.ChartSurfaceGeometry
import com.no5.helm.chart.ChartViewport
import com.no5.helm.chart.ChartContentStage
import com.no5.helm.chart.ChartBufferRaster

/** Bounded immutable camera associations, installed before JNI so an early consumer ACK is exact. */
internal class ChartBufferRegistry(private val capacity: Int = 32) {
    init { require(capacity > 0) }
    private val buffers = linkedMapOf<Long, ChartBufferSnapshot>()
    private var epoch = 0L
    private var lastStamp = 0L
    private var observedTag = 0L
    @Synchronized fun generation(): Long = epoch
    @Synchronized fun invalidate(): Long { epoch++; buffers.clear(); observedTag = 0; return epoch }
    @Synchronized fun observedStamp(): Long = observedTag
    /** Select only the sampled timestamp; producer completion cannot select a newer or older UI basis. */
    @Synchronized fun observe(stamp: Long): ChartBufferSnapshot? { observedTag = stamp; return lookup(stamp) }
    @Synchronized fun submit(expectedGeneration: Long, timeNanos: Long, cameraRevision: Long,
        geometry: ChartSurfaceGeometry, camera: ChartViewport, session: String,
        stage: ChartContentStage = ChartContentStage.FULL, presentationSlot: Int = -1, raster: ChartBufferRaster? = null,
        sourceGeneration: Long = 0, contentGeneration: Long = 0): ChartBufferSnapshot? {
        if (expectedGeneration != epoch) return null
        if (raster != null && (raster.geometry.visible != geometry ||
            raster.producerCamera.copy(width = camera.width, height = camera.height) != camera)) return null
        val stamp = maxOf(timeNanos, lastStamp + 1); lastStamp = stamp
        val frame = ChartFrame(stamp, "t0:$session:$epoch:$cameraRevision", geometry, camera, null,
            sourceGeneration, contentGeneration)
        val buffer = ChartBufferSnapshot(stamp, epoch, cameraRevision, frame, stage = stage, presentationSlot = presentationSlot, raster = raster)
        buffers[stamp] = buffer
        while (buffers.size > capacity) buffers.remove(buffers.keys.first())
        return buffer
    }
    @Synchronized fun lookup(stamp: Long): ChartBufferSnapshot? = buffers[stamp]?.takeIf { it.generation == epoch }
    @Synchronized fun complete(stamp: Long, frame: ChartFrame): ChartBufferSnapshot? {
        val old = lookup(stamp) ?: return null
        if (frame.geometry != old.frame.geometry || frame.viewport != old.frame.viewport ||
            frame.sourceGeneration != old.frame.sourceGeneration || frame.contentGeneration != old.frame.contentGeneration) return null
        return old.copy(frame = frame.copy(revision = old.stamp, cameraToken = old.frame.cameraToken),
            producerCompleted = true).also { buffers[stamp] = it }
    }
    @Synchronized fun discard(stamp: Long) { buffers.remove(stamp) }
}
