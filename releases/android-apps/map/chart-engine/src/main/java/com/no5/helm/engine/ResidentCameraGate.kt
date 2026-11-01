package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlin.math.*

internal fun residentCameraValid(camera: ChartViewport, geometry: ChartSurfaceGeometry): Boolean =
    camera.latitude.isFinite() && camera.latitude in -85.0..85.0 && camera.longitude.isFinite() &&
        camera.longitude in -180.0..180.0 && camera.metresToPixels.isFinite() && camera.metresToPixels in .00001..10.0 &&
        camera.rotationRadians.isFinite() && camera.width == geometry.frameWidth && camera.height == geometry.frameHeight

/** Selection is advisory. Recount its exact camera under the same complete authority
 * before any source/camera commit; startup never uses the above-budget recovery exception. */
internal fun residentStartAdmission(start: ChartResidentCameraStart, budget: ChartResidentBudget,
    geometry: ChartSurfaceGeometry, empty: Boolean, guard: ChartResidentCameraGuard?,
    select: Boolean = true, stillCurrent: () -> Boolean = { true }): ChartResidentCameraDecision {
    fun rejected(reason: ChartResidentCameraReason) = ChartResidentCameraDecision(null, reason, null,
        budget.cells, start.expectedCatalogGeneration, start.expectedSourceGeneration, start.expectedSurfaceGeneration)
    if (!stillCurrent()) return rejected(ChartResidentCameraReason.STALE_AUTHORITY)
    if (start.planningWidth < geometry.frameWidth || start.planningHeight < geometry.frameHeight ||
        !residentCameraValid(start.desired, geometry)) return rejected(ChartResidentCameraReason.UNKNOWN)
    if (empty) return if (stillCurrent()) ChartResidentCameraDecision(start.desired,
        ChartResidentCameraReason.ACCEPTED, 0, budget.cells, start.expectedCatalogGeneration,
        start.expectedSourceGeneration, start.expectedSurfaceGeneration) else rejected(ChartResidentCameraReason.STALE_AUTHORITY)
    if (guard == null) return rejected(ChartResidentCameraReason.UNKNOWN)
    val proposed = runCatching {
        if (select) guard.selectStart(start, budget) else guard.evaluate(start.proposal(start.desired), budget)
    }.getOrElse { return rejected(ChartResidentCameraReason.UNKNOWN) }
    if (!stillCurrent()) return rejected(ChartResidentCameraReason.STALE_AUTHORITY)
    val camera = proposed.acceptedCamera ?: return proposed.takeIf {
        residentCameraDecisionValid(start.proposal(start.desired), budget, it)
    } ?: rejected(ChartResidentCameraReason.UNKNOWN)
    if (!residentCameraValid(camera, geometry) || proposed.recoveryAboveBudget) return rejected(ChartResidentCameraReason.UNKNOWN)
    val proposal = start.proposal(camera)
    val checked = runCatching { guard.evaluate(proposal, budget) }.getOrElse { return rejected(ChartResidentCameraReason.UNKNOWN) }
    if (!stillCurrent()) return rejected(ChartResidentCameraReason.STALE_AUTHORITY)
    if (!residentCameraDecisionValid(proposal, budget, proposed) ||
        !residentCameraDecisionValid(proposal, budget, checked) || checked.acceptedCamera != camera ||
        checked.reason != ChartResidentCameraReason.ACCEPTED || checked.recoveryAboveBudget ||
        (checked.requiredUpperBound ?: 0) <= 0 || checked.requiredUpperBound != proposed.requiredUpperBound)
        return rejected(ChartResidentCameraReason.UNKNOWN)
    return checked
}

/** A policy may accept only the exact cameras it was asked to count, under those same tags. */
internal fun residentCameraDecisionValid(proposal: ChartResidentCameraProposal, budget: ChartResidentBudget,
    decision: ChartResidentCameraDecision): Boolean {
    if (decision.catalogGeneration != proposal.expectedCatalogGeneration ||
        decision.sourceGeneration != proposal.expectedSourceGeneration ||
        decision.surfaceGeneration != proposal.expectedSurfaceGeneration || decision.cellBudget != budget.cells ||
        !decision.conservative || decision.requiredUpperBound?.let { it < 0 } == true) return false
    val accepted = decision.acceptedCamera ?: return !decision.recoveryAboveBudget
    if (accepted != proposal.wanted && accepted != proposal.noZoomAlternative) return false
    val count = decision.requiredUpperBound ?: return false
    if (count <= budget.cells) return !decision.recoveryAboveBudget && decision.reason in setOf(
        ChartResidentCameraReason.ACCEPTED, ChartResidentCameraReason.NO_ZOOM_ALTERNATIVE)
    return proposal.pureZoomInRecovery && accepted == proposal.wanted &&
        decision.reason == ChartResidentCameraReason.RECOVERY_ABOVE_BUDGET && decision.recoveryAboveBudget
}

/** Revalidate the exact newer user inbox; startup fitting must not overwrite an
 * explicitly admitted inward recovery from a restored above-budget camera. */
internal fun residentInboxAdmission(proposal: ChartResidentCameraProposal, budget: ChartResidentBudget,
    geometry: ChartSurfaceGeometry, empty: Boolean, guard: ChartResidentCameraGuard?,
    stillCurrent: () -> Boolean): ChartResidentCameraDecision {
    fun rejected(reason: ChartResidentCameraReason) = ChartResidentCameraDecision(null, reason, null,
        budget.cells, proposal.expectedCatalogGeneration, proposal.expectedSourceGeneration, proposal.expectedSurfaceGeneration)
    if (!stillCurrent()) return rejected(ChartResidentCameraReason.STALE_AUTHORITY)
    if (!residentCameraValid(proposal.basis, geometry) || !residentCameraValid(proposal.wanted, geometry) ||
        proposal.planningWidth < geometry.frameWidth || proposal.planningHeight < geometry.frameHeight)
        return rejected(ChartResidentCameraReason.UNKNOWN)
    val checked = if (empty) ChartResidentCameraDecision(proposal.wanted, ChartResidentCameraReason.ACCEPTED,
        0, budget.cells, proposal.expectedCatalogGeneration, proposal.expectedSourceGeneration, proposal.expectedSurfaceGeneration)
    else runCatching { guard?.evaluate(proposal, budget) }.getOrNull() ?: return rejected(ChartResidentCameraReason.UNKNOWN)
    if (!stillCurrent()) return rejected(ChartResidentCameraReason.STALE_AUTHORITY)
    return checked.takeIf { residentCameraDecisionValid(proposal, budget, it) && it.acceptedCamera == proposal.wanted }
        ?: rejected(ChartResidentCameraReason.UNKNOWN)
}

/** Same Mercator anchor math and bounds as the existing native transformCamera path. */
internal fun residentTransformedCamera(view: ChartViewport, anchor: ScreenPoint,
    pan: ScreenPoint = ScreenPoint(0.0, 0.0), zoom: Double = 1.0, rotation: Double = 0.0): ChartViewport =
    transformChartCamera(view, anchor, pan, zoom, rotation)

internal fun residentNormalCameraAllowed(state: ChartEngineStatus, registryActive: Boolean,
    capacityLease: Boolean, currentLiveLease: Boolean = false): Boolean = (!state.rendererBenchmark.active && !registryActive) ||
    (currentLiveLease && state.rendererBenchmark.active && state.rendererBenchmark.liveInputEnabled &&
        state.rendererBenchmark.runKind == RendererRunKind.LIVE_INPUT && state.rendererBenchmark.phase == "live_input") ||
    (capacityLease && state.rendererBenchmark.active && state.rendererBenchmark.liveInputEnabled &&
        state.rendererBenchmark.runKind == RendererRunKind.RESIDENT_CAPACITY && state.rendererBenchmark.phase == "sampling")

internal fun residentLiveCameraAllowed(state: ChartEngineStatus, registryKind: RendererRunKind?,
    capacityLease: Boolean, currentLiveLease: Boolean = false): Boolean = if (currentLiveLease)
        residentNormalCameraAllowed(state, true, capacityLease, currentLiveLease)
    else if (registryKind == RendererRunKind.RESIDENT_CAPACITY)
        residentNormalCameraAllowed(state, true, capacityLease)
    else !state.rendererBenchmark.active || state.rendererBenchmark.liveInputEnabled
