package com.no5.helm.ui

import com.no5.helm.chart.ChartBufferSnapshot
import com.no5.helm.chart.ChartSurfaceGeometry

/** Acquisition and window-commit are different endpoints. No camera state lives here. */
internal class ChartPresentationSlots {
    var front = 0
        private set
    private var generation = 0L
    private var geometry: ChartSurfaceGeometry? = null
    private val images = arrayOfNulls<ChartBufferSnapshot>(2)
    private var pendingCommit: ChartBufferSnapshot? = null
    val visible: ChartBufferSnapshot? get() = images[front]
    fun image(slot: Int): ChartBufferSnapshot? = images.getOrNull(slot)

    fun reset(nextGeneration: Long, nextGeometry: ChartSurfaceGeometry?, preserveFront: Boolean = false) {
        val retain = preserveFront && geometry == nextGeometry && visible != null
        generation = nextGeneration; geometry = nextGeometry
        if (retain) images[1 - front] = null
        else { front = 0; images.fill(null); pendingCommit = null }
    }

    fun eligible(slot: Int, image: ChartBufferSnapshot?): Boolean {
        if (slot !in 0..1 || image == null || image.presentationSlot != slot ||
            image.generation != generation || image.frame.geometry != geometry || image.stamp <= 0) return false
        if (pendingCommit != null || image.stamp <= (visible?.stamp ?: 0L)) return false
        // A visible slot must remain immutable until the replacement traversal commits.
        return slot != front || visible == null
    }

    /** The hidden image was actually acquired by HWUI. It may now be revealed with its own basis. */
    fun acquire(slot: Int, image: ChartBufferSnapshot?): ChartBufferSnapshot? {
        if (!eligible(slot, image)) return null
        images[slot] = image; front = slot; pendingCommit = image
        return image
    }

    /** Only metadata completion can change while a slot is visible; its camera and pixels are fixed. */
    fun refresh(image: ChartBufferSnapshot?): Boolean {
        val current = visible ?: return false
        if (image == null || image.stamp != current.stamp || image.generation != current.generation ||
            image.presentationSlot != front || image.frame.copy(scale = current.frame.scale) != current.frame || image.cameraRevision != current.cameraRevision ||
            image.stage != current.stage || image.raster != current.raster) return false
        images[front] = image
        return true
    }

    fun committed(slot: Int, stamp: Long, expectedGeneration: Long): Boolean {
        val pending = pendingCommit ?: return false
        if (expectedGeneration != pending.generation || pending.presentationSlot != slot || pending.stamp != stamp || front != slot) return false
        pendingCommit = null
        return true
    }
}
