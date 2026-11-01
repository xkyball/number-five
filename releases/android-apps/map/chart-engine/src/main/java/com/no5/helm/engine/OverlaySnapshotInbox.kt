package com.no5.helm.engine

import com.no5.helm.chart.ChartOverlays
import java.util.concurrent.atomic.AtomicReference

/**
 * Cross-thread mailbox for live overlay state. Rendering consumes at most one snapshot per
 * frame; producers can replace an unconsumed value with the newest complete snapshot.
 */
internal fun ChartOverlays.frozenSnapshot() = copy(ais = ais.toList(), waypoints = waypoints.toList(),
    route = route?.let { it.copy(points = it.points.toList(), pointIds = it.pointIds.toList(), pointNames = it.pointNames.toList()) },
    aisObjects = aisObjects.toList(), waypointObjects = waypointObjects.toList())
internal class OverlaySnapshotInbox(initial: ChartOverlays = ChartOverlays()) {
    private val pending = AtomicReference<ChartOverlays?>(null)
    private val inFlight = AtomicReference<ChartOverlays?>(null)
    private val applied = AtomicReference(initial.frozenSnapshot())

    /** Returns true only when a render needs to consume a newer snapshot. */
    fun offer(input: ChartOverlays): Boolean {
        val value = input.frozenSnapshot()
        while (true) {
            val queued = pending.get()
            if (queued != null) {
                if (queued == value) return false
                if (pending.compareAndSet(queued, value)) return true
                continue
            }
            if (inFlight.get() == value || applied.get() == value) return false
            if (pending.compareAndSet(null, value)) return true
        }
    }

    fun take(frozen: Boolean = false): ChartOverlays? {
        // A value offered just before a benchmark starts must not enter its fixed empty overlays.
        if (frozen) return null
        val value = pending.getAndSet(null) ?: return null
        inFlight.set(value)
        return value
    }

    fun markApplied(value: ChartOverlays) {
        inFlight.updateAndGet { if (it == value) null else it }
        applied.set(value.frozenSnapshot())
        pending.updateAndGet { if (it == value) null else it }
    }

    fun markRejected(value: ChartOverlays) {
        inFlight.updateAndGet { if (it == value) null else it }
    }
}
