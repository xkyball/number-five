package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.serialization.json.*
import org.lumina.control.commands.v1.CommandRing

/** One diagnostic observation, copied while the camera owner is locked. */
internal data class ResidentRecipeCameraReceipt(val accepted: Boolean, val terminal: Boolean,
    val realizedViewport: ChartViewport, val reason: String, val authority: JsonObject) {
    fun response(acceptedWithinDeadline: Boolean): JsonObject = buildJsonObject {
        put("accepted", acceptedWithinDeadline && accepted)
        put("realizedViewport", BostonBenchmarkRecipe.cameraJson(realizedViewport))
        put("reason", reason)
        put("authority", authority)
    }
}

/** A prepared revision and its viewport are published inside the same owner lock.
 * Never observe that revision through the result ring before the viewport publication,
 * or combine a copied viewport with a later, separately read authority.
 */
internal fun captureResidentRecipeCameraReceipt(cameraLock: Any, targetId: Long,
    wanted: ChartViewport, expectedAuthority: JsonObject,
    readReceipt: (ChartControlResult) -> Boolean, readState: () -> ChartEngineStatus,
    readRequestedRevision: () -> Long, readAuthority: (ChartEngineStatus, Long) -> JsonObject,
    stillAllowed: () -> Boolean): ResidentRecipeCameraReceipt = synchronized(cameraLock) {
    val receipt = ChartControlResult()
    val found = targetId > 0 && readReceipt(receipt)
    val state = readState()
    val revision = readRequestedRevision()
    val authority = readAuthority(state, revision)
    val allowed = stillAllowed()
    val sameScope = listOf("catalogGeneration", "sourceGeneration", "surfaceGeneration", "budget").all {
        expectedAuthority[it] != null && expectedAuthority[it] != JsonNull && authority[it] == expectedAuthority[it]
    } && authority["catalogGeneration"]?.jsonPrimitive?.contentOrNull == state.residentCatalogGeneration &&
        authority["sourceGeneration"]?.jsonPrimitive?.longOrNull == state.chartSourceGeneration &&
        authority["surfaceGeneration"]?.jsonPrimitive?.longOrNull == state.surfaceGeneration &&
        authority["cameraRevision"]?.jsonPrimitive?.longOrNull == revision &&
        authority["requestedCameraRevision"]?.jsonPrimitive?.longOrNull == revision
    val owned = found && receipt.id == targetId && receipt.state in 1..2 && receipt.revision >= 0
    val decision = state.residentCameraDecision
    val sameAdmission = decision != null && decision.acceptedCamera == wanted &&
        decision.catalogGeneration == state.residentCatalogGeneration &&
        decision.sourceGeneration == state.chartSourceGeneration && decision.surfaceGeneration == state.surfaceGeneration
    val accepted = allowed && sameScope && owned && receipt.revision == revision &&
        state.residentCameraReady && state.viewport == wanted && sameAdmission
    val terminal = !allowed || !found || receipt.id != targetId || !sameScope ||
        receipt.state >= CommandRing.State.SUPERSEDED.ordinal || owned && receipt.revision != revision
    val reason = when {
        !allowed -> "CANCELLED"
        !sameScope -> "STALE_AUTHORITY"
        terminal -> receipt.reason.ifBlank { "UNKNOWN" }
        else -> decision?.reason?.name ?: "UNKNOWN"
    }
    ResidentRecipeCameraReceipt(accepted, terminal, state.viewport, reason, authority)
}
