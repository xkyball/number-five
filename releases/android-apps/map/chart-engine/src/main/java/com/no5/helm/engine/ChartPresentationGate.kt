package com.no5.helm.engine

/** One pending replacement. The displayed slot is never a writable producer destination. */
internal class ChartPresentationGate {
    /** Read-only proof of the exact still-exclusive backslot; never marks it acquired or committed. */
    data class Reservation(val generation: Long, val stamp: Long, val reservedSlot: Int, val currentFrontSlot: Int)
    private var generation = 0L
    private var front = 0
    private var pendingSlot = -1
    private var pendingStamp = 0L
    private var pendingGeneration = 0L
    private var acquired = false
    @Synchronized fun reset(epoch: Long, keepFront: Boolean = false) {
        generation = epoch
        if (!keepFront) front = 0
        if (!keepFront || !acquired) { pendingSlot = -1; pendingStamp = 0; acquired = false }
    }
    @Synchronized fun busy(): Boolean = pendingSlot >= 0
    @Synchronized fun writableSlot(epoch: Long): Int? = if (epoch == generation && !busy()) 1 - front else null
    @Synchronized fun reserve(epoch: Long, slot: Int, stamp: Long): Boolean {
        if (epoch != generation || busy() || slot != 1 - front || stamp <= 0) return false
        pendingSlot = slot; pendingStamp = stamp; pendingGeneration = epoch; acquired = false
        return true
    }
    @Synchronized fun reservation(epoch: Long, slot: Int, stamp: Long): Reservation? {
        if (epoch != generation || epoch != pendingGeneration || stamp <= 0 || stamp != pendingStamp ||
            slot != pendingSlot || slot !in 0..1 || slot != 1 - front || acquired) return null
        return Reservation(generation, pendingStamp, pendingSlot, front)
    }
    @Synchronized fun acquired(epoch: Long, slot: Int, stamp: Long): Boolean {
        if (epoch != generation || epoch != pendingGeneration || slot != pendingSlot || stamp != pendingStamp) return false
        acquired = true; return true
    }
    @Synchronized fun acknowledge(epoch: Long, slot: Int, stamp: Long): Boolean {
        if (epoch != pendingGeneration || (epoch != generation && !acquired) || slot != pendingSlot || stamp != pendingStamp) return false
        front = slot; pendingSlot = -1; pendingStamp = 0; acquired = false
        return true
    }
    @Synchronized fun committed(slot: Int, stamp: Long): Boolean = acquired && acknowledge(pendingGeneration, slot, stamp)
    @Synchronized fun discard(epoch: Long, stamp: Long) {
        if (epoch == pendingGeneration && stamp == pendingStamp && !acquired) { pendingSlot = -1; pendingStamp = 0 }
    }
}
