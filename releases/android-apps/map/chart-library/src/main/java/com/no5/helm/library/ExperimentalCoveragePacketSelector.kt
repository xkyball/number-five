package com.no5.helm.library

import java.util.Collections

/** A verified conservative preparation bound, never actual held geometry bytes. */
data class ExperimentalPacketBound(val preparedSourceKey: String, val parserIdentity: String,
    val reservationBytes: Long)

/** This is a SHADOW/harness proposal. It must not be interpreted as native FITS,
 * image completeness, physical device capacity or automatic nautical detail approval. */
data class ExperimentalCoveragePacketProposal(val original: ChartCoverageAdmission,
    val candidate: ChartCoverageAdmission?, val reason: String,
    val originalReservationBounds: Long?, val candidateReservationBounds: Long?) {
    val offered: Boolean get() = candidate != null
}

/** Explicit screen-scale trial. Unchanged original objects, native scale ownership,
 * CS/SOUNDG and geometry quota remain authoritative. Default sessions do not use it. */
class ExperimentalCoveragePacketSelector(private val catalog: ChartPlanningCatalog,
    verifiedBounds: Map<ChartCellKey, ExperimentalPacketBound>) {
    private val bounds = Collections.unmodifiableMap(verifiedBounds.toMap())
    private val byKey = catalog.cells.associateBy { it.key }
    private val original = ChartCoveragePlanner(catalog)
    private val screenScale = ChartCoveragePlanner(catalog, experimentalScreenScale = true)

    fun propose(plan: ChartViewportPlan, actualChartScale: Double?,
        checkpoint: () -> Unit = {}): ExperimentalCoveragePacketProposal {
        // Full producer dimensions, exact actual scale and current metadata tag.
        val control = original.admit(plan, actualChartScale, captureRaster = true, checkpoint = checkpoint)
        fun rejected(reason: String, candidate: ChartCoverageAdmission? = null) =
            ExperimentalCoveragePacketProposal(control, null, reason,
                reservationSum(control), candidate?.let(::reservationSum))
        if (!control.proven || control.raster == null || control.uncoveredPixels != 0)
            return rejected("Original coverage unknown or existing producer raster gap")
        checkpoint()
        val preference = screenScale.admit(plan, actualChartScale, captureRaster = true, checkpoint = checkpoint)
        if (!preference.proven || preference.raster == null || preference.uncoveredPixels != 0)
            return rejected("Experimental coverage unknown or producer raster gap", preference)
        // Apply ORIGINAL native priority to the proposed source subset, never the
        // experimental ranking to S52. Keep its mode witness through the second proof.
        val candidate = original.admit(preference.plan, actualChartScale, captureRaster = true, checkpoint = checkpoint)
        if (!candidate.proven || candidate.raster == null || candidate.uncoveredPixels != 0 ||
            candidate.masked != control.masked)
            return rejected("Original native ownership/mask mode cannot validate subset", candidate)
        val a = control.raster!!; val b = candidate.raster!!
        if (a.pixels != b.pixels) return rejected("Producer dimensions differ", candidate)
        for (p in 0 until a.pixels) {
            if (p % 16384 == 0) checkpoint()
            if (a.get(p) != 0 && b.get(p) == 0) return rejected("New producer pixel-centre gap", candidate)
        }
        if (candidate.plan.required.size >= control.plan.required.size)
            return rejected("No strict source-count improvement", candidate)
        val originalBytes = reservationSum(control)
            ?: return rejected("Original verified reservation bounds unavailable", candidate)
        val candidateBytes = reservationSum(candidate)
            ?: return rejected("Candidate verified reservation bounds unavailable", candidate)
        if (candidateBytes > originalBytes) return rejected("Higher reservation bounds", candidate)
        return ExperimentalCoveragePacketProposal(control, candidate,
            "Smaller metadata demand; controlled native image trial still required", originalBytes, candidateBytes)
    }

    private fun reservationSum(admission: ChartCoverageAdmission): Long? {
        var total = 0L
        for (key in admission.plan.required) {
            val metadata = byKey[key]?.metadata ?: return null
            val bound = bounds[key] ?: return null
            if (bound.reservationBytes <= 0 || bound.preparedSourceKey != metadata.preparedSourceKey ||
                bound.parserIdentity != metadata.parserIdentity || total > Long.MAX_VALUE - bound.reservationBytes)
                return null
            total += bound.reservationBytes
        }
        return total
    }
}
