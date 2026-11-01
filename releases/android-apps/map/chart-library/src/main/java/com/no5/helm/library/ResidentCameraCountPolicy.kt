package com.no5.helm.library

import com.no5.helm.chart.*
import kotlin.math.*

/** A conservative, metadata-only P2 count guard. It is deliberately earlier than
 * P6's raster contributor reduction and proves neither byte capacity nor hardware
 * speed. The caller supplies the LIVE owner budget and rejects stale surface tags.
 * This private planner is not shared with the asynchronous geometry/load session. */
class ResidentCameraCountPolicy(
    private val catalog: ChartPlanningCatalog,
    private val sourceGeneration: Long,
) : ChartResidentCameraGuard {
    private val planner = ChartViewportPlanner(catalog, IndexedChartCellIndex(catalog))

    init { require(sourceGeneration >= 0) }

    @Synchronized override fun evaluate(
        proposal: ChartResidentCameraProposal,
        budget: ChartResidentBudget,
    ): ChartResidentCameraDecision {
        fun decision(camera: ChartViewport?, reason: ChartResidentCameraReason, count: Int? = null,
            recovery: Boolean = false) = ChartResidentCameraDecision(camera, reason, count, budget.cells,
            catalog.generation, sourceGeneration, proposal.expectedSurfaceGeneration,
            conservative = true, recoveryAboveBudget = recovery)
        if (proposal.expectedCatalogGeneration != catalog.generation ||
            proposal.expectedSourceGeneration != sourceGeneration || proposal.expectedSurfaceGeneration < 0)
            return decision(null, ChartResidentCameraReason.STALE_AUTHORITY)
        val wanted = plan(proposal.wanted, proposal) ?: return decision(null, ChartResidentCameraReason.UNKNOWN)
        val count = wanted.required.size
        if (wanted.metadataRequired.isEmpty() && count <= budget.cells)
            return decision(proposal.wanted, ChartResidentCameraReason.ACCEPTED, count)

        // The UI/engine computes this from the original anchored transform with
        // zoom=1. Merely replacing ppm on the wanted camera would move the anchor.
        val alternative = proposal.noZoomAlternative
        if (proposal.wanted.metresToPixels < proposal.basis.metresToPixels && alternative != null &&
            alternative.metresToPixels == proposal.basis.metresToPixels) {
            val noZoom = plan(alternative, proposal)
            if (noZoom != null && noZoom.metadataRequired.isEmpty() && noZoom.required.size <= budget.cells)
                return decision(alternative, ChartResidentCameraReason.NO_ZOOM_ALTERNATIVE, noZoom.required.size)
        }
        if (wanted.metadataRequired.isNotEmpty())
            return decision(null, ChartResidentCameraReason.UNKNOWN, count)

        // Existing/startup cameras can already be outside the count budget.
        // Explicit inward recovery is an honest exception until a valid camera
        // is reached, not a claim that this transitional view is within budget.
        if (proposal.pureZoomInRecovery && inwardContained(proposal.basis, proposal.wanted, proposal)) {
            val basis = plan(proposal.basis, proposal)
            if (basis != null && basis.metadataRequired.isEmpty() && basis.required.size > budget.cells &&
                wanted.required.toSet().let { keys -> basis.required.toSet().containsAll(keys) })
                return decision(proposal.wanted, ChartResidentCameraReason.RECOVERY_ABOVE_BUDGET, count, true)
        }
        return decision(null, ChartResidentCameraReason.BLOCKED, count)
    }

    private fun plan(view: ChartViewport, proposal: ChartResidentCameraProposal): ChartViewportPlan? = try {
        planner.plan(camera(view, proposal))
    } catch (_: IllegalArgumentException) { null }

    private fun camera(view: ChartViewport, proposal: ChartResidentCameraProposal): ChartPlanCamera {
        require(view.width > 0 && view.height > 0)
        return ChartPlanCamera(view.latitude, view.longitude, view.metresToPixels,
            view.rotationRadians, proposal.planningWidth, proposal.planningHeight)
    }

    /** Reject a falsely marked recovery that rotates, resizes or leaves the old
     * footprint. Scale-dependent chart contributor counts need not be monotone;
     * the separate required-source subset check is still necessary. */
    private fun inwardContained(basis: ChartViewport, wanted: ChartViewport, proposal: ChartResidentCameraProposal): Boolean {
        if (wanted.metresToPixels <= basis.metresToPixels || wanted.width != basis.width || wanted.height != basis.height)
            return false
        val angle = atan2(sin(wanted.rotationRadians - basis.rotationRadians), cos(wanted.rotationRadians - basis.rotationRadians))
        if (!angle.isFinite() || abs(angle) > 1e-12) return false
        return try {
            val before = camera(basis, proposal)
            val after = camera(wanted, proposal)
            val radius = 6378137.0 * .9996
            val radians = PI / 180.0
            fun northing(latitude: Double): Double {
                val phase = sin(latitude * radians)
                return .5 * ln((1 + phase) / (1 - phase)) * radius
            }
            val origin = northing(before.latitude)
            val cosine = cos(before.rotation); val sine = sin(before.rotation)
            after.corners().all { corner ->
                val longitudeDelta = ((corner.longitude - before.longitude + 540.0) % 360.0) - 180.0
                val east = longitudeDelta * radians * radius
                val north = northing(corner.latitude) - origin
                val x = before.width * .5 + before.pixelsPerMetre * (east * cosine + north * sine)
                val y = before.height * .5 + before.pixelsPerMetre * (east * sine - north * cosine)
                x.isFinite() && y.isFinite() && x >= -1e-7 && y >= -1e-7 &&
                    x <= before.width + 1e-7 && y <= before.height + 1e-7
            }
        } catch (_: IllegalArgumentException) { false }
    }
}
