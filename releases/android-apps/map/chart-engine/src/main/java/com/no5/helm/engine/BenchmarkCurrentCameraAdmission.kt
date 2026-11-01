package com.no5.helm.engine

import com.no5.helm.chart.*

/** Recount the already restored camera after the controller rebinds its guard.
 * This cannot select another start, move a camera or publish a rejected result.
 * The owner commits a returned decision only after its final snapshot checks.
 */
internal fun benchmarkCurrentCameraAdmission(start: ChartResidentCameraStart,
    budget: ChartResidentBudget, geometry: ChartSurfaceGeometry, empty: Boolean,
    guard: ChartResidentCameraGuard?, stillCurrent: () -> Boolean): ChartResidentCameraDecision? {
    if (start.previous != start.desired || !stillCurrent()) return null
    val decision = residentStartAdmission(start, budget, geometry, empty, guard,
        select = false, stillCurrent = stillCurrent)
    return decision.takeIf { stillCurrent() && it.acceptedCamera == start.desired &&
        it.reason == ChartResidentCameraReason.ACCEPTED && !it.recoveryAboveBudget }
}
