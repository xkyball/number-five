package com.no5.helm.library

import com.no5.helm.chart.*
import kotlin.math.*

/** Runtime factory for the whole immutable metadata catalog. Startup selection
 * proves only the conservative P2 cell count, never bytes or hardware speed. */
class ResidentStartCameraPolicy(private val catalog: ChartPlanningCatalog,
    private val preferPrevious: Boolean = true) : ChartResidentCameraPolicy {
    private val navigation = ChartCameraBounds.from(catalog.navigationBounds())
    override fun bind(sourceGeneration: Long): ChartResidentCameraGuard {
        require(sourceGeneration >= 0)
        return Bound(sourceGeneration)
    }

    private inner class Bound(sourceGeneration: Long) : ChartResidentCameraGuard {
        private val count = ResidentCameraCountPolicy(catalog, sourceGeneration)

        override fun evaluate(proposal: ChartResidentCameraProposal, budget: ChartResidentBudget) =
            count.evaluate(proposal, budget)

        @Synchronized override fun selectStart(start: ChartResidentCameraStart,
            budget: ChartResidentBudget): ChartResidentCameraDecision {
            fun check(view: ChartViewport): ChartResidentCameraDecision {
                val bounded = navigation?.constrain(view)?.effective ?: view
                val result = count.evaluate(start.proposal(bounded), budget)
                // A nonempty catalog must not "fit" by starting outside it.
                // Positive bbox demand is not a polygon/pixel coverage assertion.
                return if (catalog.cells.isNotEmpty() && result.acceptedCamera != null &&
                    result.requiredUpperBound == 0) result.copy(acceptedCamera = null,
                    reason = ChartResidentCameraReason.BLOCKED) else result
            }
            val desired = check(start.desired)
            if (desired.reason == ChartResidentCameraReason.STALE_AUTHORITY ||
                desired.reason == ChartResidentCameraReason.UNKNOWN && desired.requiredUpperBound == null) return desired

            // Resize normalizes the visible size but counts the actual producer
            // reserve supplied by the owner. Never reuse an old pose's count.
            start.previous?.takeIf { preferPrevious }?.copy(width = start.desired.width, height = start.desired.height)?.let {
                val previous = check(it)
                if (previous.acceptedCamera != null) return previous
            }
            if (desired.acceptedCamera != null) return desired
            var unknown = desired.takeIf { it.reason == ChartResidentCameraReason.UNKNOWN }
            fun closer(seed: ChartViewport): ChartResidentCameraDecision? {
                var view = seed
                // .00001 through 10 requires at most 20 doubling steps. Each
                // candidate is proved independently; no monotone/binary search.
                repeat(21) {
                    if (view.metresToPixels >= 10.0) return null
                    view = view.copy(metresToPixels = min(10.0, view.metresToPixels * 2))
                    val next = check(view)
                    if (next.acceptedCamera != null) return next
                    if (next.reason == ChartResidentCameraReason.UNKNOWN && unknown == null) unknown = next
                }
                return null
            }
            closer(start.desired)?.let { return it }

            // One stable geographic focus avoids the empty-space midpoint of
            // separated catalog areas. It is still subjected to the same gate.
            focus(start.previous?.takeIf { preferPrevious } ?: start.desired)?.let { point ->
                val focused = start.desired.copy(latitude = point.latitude, longitude = point.longitude)
                val first = check(focused)
                if (first.acceptedCamera != null) return first
                if (first.reason == ChartResidentCameraReason.UNKNOWN && unknown == null) unknown = first
                closer(focused)?.let { return it }
            }
            return unknown ?: desired.copy(acceptedCamera = null, reason = ChartResidentCameraReason.BLOCKED)
        }
    }

    private fun focus(previous: ChartViewport): CatalogCoordinate? {
        fun center(bounds: ChartSelectionExtent): CatalogCoordinate {
            val span = if (bounds.west > bounds.east) bounds.east + 360 - bounds.west else bounds.east - bounds.west
            return CatalogCoordinate((bounds.south + bounds.north) * .5,
                ChartSelectionExtent.normalizeLongitude(bounds.west + span * .5))
        }
        fun distance(cell: ChartPlanCell): Double {
            val point = center(checkNotNull(cell.bounds))
            val longitude = ChartSelectionExtent.normalizeLongitude(point.longitude - previous.longitude)
            val east = longitude * cos(Math.toRadians(previous.latitude.coerceIn(-85.0, 85.0)))
            val north = point.latitude - previous.latitude
            return east * east + north * north
        }
        return catalog.cells.filter { it.bounds?.valid == true }.minWithOrNull(
            compareBy<ChartPlanCell> { distance(it) }.thenBy { it.order }.thenBy { it.key.sourceKey })
            ?.bounds?.let(::center)
    }

    companion object {
        /** Explicit empty source authority, without inventing a metadata catalog. */
        fun empty(catalogGeneration: String): ChartResidentCameraPolicy = ChartResidentCameraPolicy { source ->
            require(source >= 0)
            ChartResidentCameraGuard { proposal, budget ->
                val stale = proposal.expectedCatalogGeneration != catalogGeneration ||
                    proposal.expectedSourceGeneration != source || proposal.expectedSurfaceGeneration < 0
                val valid = runCatching {
                    val view = proposal.wanted
                    require(view.width > 0 && view.height > 0)
                    ChartPlanCamera(view.latitude, view.longitude, view.metresToPixels, view.rotationRadians,
                        proposal.planningWidth, proposal.planningHeight)
                }.isSuccess
                ChartResidentCameraDecision(proposal.wanted.takeIf { !stale && valid }, when {
                    stale -> ChartResidentCameraReason.STALE_AUTHORITY
                    !valid -> ChartResidentCameraReason.UNKNOWN
                    else -> ChartResidentCameraReason.ACCEPTED
                }, if (!stale && valid) 0 else null, budget.cells, catalogGeneration, source,
                    proposal.expectedSurfaceGeneration)
            }
        }
    }
}
