package com.no5.helm.library

import java.util.Collections

/** Charged native geometry units, not file size, GPU time or process RSS.
 * A result under an unmeasured profile does not authorize a product camera limit. */
data class CameraCapacityProfile(
    val id: String,
    val revision: Long,
    val maxResidentCells: Int,
    val maxChargedBytes: Long,
    val loadingReservationBytes: Long,
    val measuredOnDevice: Boolean = false,
) {
    init {
        require(id.isNotBlank() && revision >= 0)
        require(maxResidentCells in 1..100)
        require(loadingReservationBytes > 0 && maxChargedBytes >= loadingReservationBytes)
    }
}

/** The camera key includes the actual producer dimensions/reserve. Scale belongs
 * to this proposed camera and a pinned native calibration, never the previous view. */
data class CameraCapacityTag(
    val catalogGeneration: String,
    val sourceGeneration: Long,
    val cameraKey: String,
    val cameraRevision: Long,
    val surfaceGeneration: Long,
    val candidateRevision: Long,
    val selectionPolicyKey: String,
    val scaleIdentity: String,
    val chartScaleDenominator: Double?,
    val profileId: String,
    val profileRevision: Long,
) {
    init {
        require(listOf(catalogGeneration, cameraKey, selectionPolicyKey, scaleIdentity, profileId).all { it.isNotBlank() })
        require(listOf(sourceGeneration, cameraRevision, surfaceGeneration, candidateRevision, profileRevision).all { it >= 0 })
    }
}

data class CameraCapacitySource(val sourceKey: String, val chargedBytesUpperBound: Long?) {
    init { require(sourceKey.isNotBlank() && (chargedBytesUpperBound == null || chargedBytesUpperBound > 0)) }
}

/** Required sources are already selected, in native load order, including mask
 * witnesses. Conservative complete supersets are valid. Prefetch is not required. */
class CameraCapacityTarget(
    val tag: CameraCapacityTag,
    required: List<CameraCapacitySource>,
    val metadataComplete: Boolean = true,
    val candidateCount: Int = required.size,
) {
    val required: List<CameraCapacitySource> = Collections.unmodifiableList(required.toList())
    init {
        require(this.required.map { it.sourceKey }.distinct().size == this.required.size)
        require(candidateCount >= this.required.size)
    }
}

/** One entry per physical held arena, including retired generations. A pin on a
 * required arena does not add a second charge or slot. Only active, source-identical
 * geometry marked reusable can satisfy a required source. */
data class CameraCapacityResidentGeometry(
    val geometryId: String,
    val sourceKey: String,
    val chargedBytes: Long,
    val pinned: Boolean,
    val reusableForTargetCatalog: Boolean,
) {
    init { require(geometryId.isNotBlank() && sourceKey.isNotBlank() && chargedBytes > 0) }
}

data class CameraCapacityActiveLoad(val sourceKey: String, val reservationBytes: Long) {
    init { require(sourceKey.isNotBlank() && reservationBytes > 0) }
}

class CameraCapacityResidency(
    val catalogGeneration: String,
    val sourceGeneration: Long,
    val revision: Long,
    geometries: List<CameraCapacityResidentGeometry> = emptyList(),
    val activeLoad: CameraCapacityActiveLoad? = null,
) {
    val geometries: List<CameraCapacityResidentGeometry> = Collections.unmodifiableList(geometries.toList())
    init {
        require(catalogGeneration.isNotBlank() && sourceGeneration >= 0 && revision >= 0)
        require(this.geometries.map { it.geometryId }.distinct().size == this.geometries.size)
        val reusable = this.geometries.filter { it.reusableForTargetCatalog }
        require(reusable.map { it.sourceKey }.distinct().size == reusable.size)
    }
}

enum class CameraCapacityOutcome { FITS, LIMIT_EXCEEDED, WAIT_TRANSITION, UNKNOWN, STALE }
enum class CameraCapacityReason {
    WITHIN_BUDGET, TARGET_CELL_LIMIT, TARGET_BYTE_LIMIT, SOURCE_LOADING_RESERVATION,
    LOAD_CELL_PEAK, LOAD_BYTE_PEAK, TRANSITION_PINS, ACTIVE_LOAD,
    UNKNOWN_SCALE, UNKNOWN_METADATA, UNKNOWN_BYTE_BOUND, INCONSISTENT_BYTE_BOUND,
    INCONSISTENT_LOADING_RESERVATION, ARITHMETIC_OVERFLOW,
    OBSOLETE_TAG, OBSOLETE_PROFILE, OBSOLETE_RESIDENCY,
}

data class CameraCapacityPeak(val cells: Int, val chargedBytes: Long)

/** FITS concerns only this snapshot/load scenario. To establish cold reload,
 * evaluate the same target with an empty snapshot and known per-source bounds.
 * A conservative LIMIT is not proof that every possible load order must fail. */
class CameraCapacityDecision internal constructor(
    val outcome: CameraCapacityOutcome,
    val reason: CameraCapacityReason,
    val tag: CameraCapacityTag,
    val residencyRevision: Long,
    val measuredOnDevice: Boolean,
    val targetCells: Int,
    val targetChargedBytes: Long?,
    val loadPeakWithoutTransition: CameraCapacityPeak?,
    val loadPeakWithTransition: CameraCapacityPeak?,
    unknownByteBounds: List<String>,
) {
    val unknownByteBounds: List<String> = Collections.unmodifiableList(unknownByteBounds.toList())
    fun isCurrent(expectedTag: CameraCapacityTag, expectedResidencyRevision: Long): Boolean =
        outcome != CameraCapacityOutcome.STALE && tag == expectedTag && residencyRevision == expectedResidencyRevision
}

/** Stateless accounting only: no IO, native/GL work, camera mutations, retry loop,
 * detail substitution or monotonic assumption about a sequence of zoom targets. */
object CameraCapacityPolicy {
    fun evaluate(
        profile: CameraCapacityProfile,
        target: CameraCapacityTarget,
        residency: CameraCapacityResidency,
        expectedTag: CameraCapacityTag,
    ): CameraCapacityDecision {
        var targetBytes: Long? = null
        var baseline: CameraCapacityPeak? = null
        var transition: CameraCapacityPeak? = null
        val reusable = residency.geometries.filter { it.reusableForTargetCatalog }.associateBy { it.sourceKey }
        val unknownBounds = target.required.filter { it.chargedBytesUpperBound == null }.map { it.sourceKey }
        fun result(outcome: CameraCapacityOutcome, reason: CameraCapacityReason) = CameraCapacityDecision(
            outcome, reason, target.tag, residency.revision, profile.measuredOnDevice,
            target.required.size, targetBytes, baseline, transition, unknownBounds,
        )
        if (target.tag != expectedTag) return result(CameraCapacityOutcome.STALE, CameraCapacityReason.OBSOLETE_TAG)
        if (profile.id != target.tag.profileId || profile.revision != target.tag.profileRevision)
            return result(CameraCapacityOutcome.STALE, CameraCapacityReason.OBSOLETE_PROFILE)
        if (residency.catalogGeneration != target.tag.catalogGeneration || residency.sourceGeneration != target.tag.sourceGeneration)
            return result(CameraCapacityOutcome.STALE, CameraCapacityReason.OBSOLETE_RESIDENCY)
        val scale = target.tag.chartScaleDenominator
        if (scale == null || !scale.isFinite() || scale <= 0)
            return result(CameraCapacityOutcome.UNKNOWN, CameraCapacityReason.UNKNOWN_SCALE)
        if (!target.metadataComplete) return result(CameraCapacityOutcome.UNKNOWN, CameraCapacityReason.UNKNOWN_METADATA)
        if (target.required.any { it.chargedBytesUpperBound == null && it.sourceKey !in reusable })
            return result(CameraCapacityOutcome.UNKNOWN, CameraCapacityReason.UNKNOWN_BYTE_BOUND)
        if (target.required.any { source -> source.chargedBytesUpperBound?.let { bound ->
                reusable[source.sourceKey]?.chargedBytes?.let { it > bound } } == true })
            return result(CameraCapacityOutcome.UNKNOWN, CameraCapacityReason.INCONSISTENT_BYTE_BOUND)
        val load = residency.activeLoad
        if (load != null && load.reservationBytes != profile.loadingReservationBytes)
            return result(CameraCapacityOutcome.UNKNOWN, CameraCapacityReason.INCONSISTENT_LOADING_RESERVATION)
        try {
            val requiredKeys = target.required.map { it.sourceKey }.toSet()
            val heldRequired = residency.geometries.filter { it.reusableForTargetCatalog && it.sourceKey in requiredKeys }
            val missing = target.required.filter { it.sourceKey !in reusable }
            val total = target.required.fold(0L) { bytes, source ->
                Math.addExact(bytes, reusable[source.sourceKey]?.chargedBytes ?: source.chargedBytesUpperBound!!) }
            targetBytes = total
            val baselinePeak = simulate(profile, heldRequired, missing)
            baseline = baselinePeak
            if (target.required.size > profile.maxResidentCells)
                return result(CameraCapacityOutcome.LIMIT_EXCEEDED, CameraCapacityReason.TARGET_CELL_LIMIT)
            if (total > profile.maxChargedBytes)
                return result(CameraCapacityOutcome.LIMIT_EXCEEDED, CameraCapacityReason.TARGET_BYTE_LIMIT)
            if (missing.any { it.chargedBytesUpperBound!! > profile.loadingReservationBytes })
                return result(CameraCapacityOutcome.LIMIT_EXCEEDED, CameraCapacityReason.SOURCE_LOADING_RESERVATION)
            if (baselinePeak.cells > profile.maxResidentCells)
                return result(CameraCapacityOutcome.LIMIT_EXCEEDED, CameraCapacityReason.LOAD_CELL_PEAK)
            if (baselinePeak.chargedBytes > profile.maxChargedBytes)
                return result(CameraCapacityOutcome.LIMIT_EXCEEDED, CameraCapacityReason.LOAD_BYTE_PEAK)
            // The owner cannot start a second job. Report the actual current job
            // once; do not predict two simultaneous loader reservations.
            if (load != null) {
                transition = CameraCapacityPeak(Math.addExact(residency.geometries.size, 1),
                    Math.addExact(sum(residency.geometries), load.reservationBytes))
                return result(CameraCapacityOutcome.WAIT_TRANSITION, CameraCapacityReason.ACTIVE_LOAD)
            }
            val heldIds = heldRequired.map { it.geometryId }.toSet()
            val oldPins = residency.geometries.filter { it.pinned && it.geometryId !in heldIds }
            val transitionPeak = simulate(profile, heldRequired + oldPins, missing)
            transition = transitionPeak
            if (transitionPeak.cells > profile.maxResidentCells || transitionPeak.chargedBytes > profile.maxChargedBytes)
                return result(CameraCapacityOutcome.WAIT_TRANSITION, CameraCapacityReason.TRANSITION_PINS)
            return result(CameraCapacityOutcome.FITS, CameraCapacityReason.WITHIN_BUDGET)
        } catch (_: ArithmeticException) {
            return result(CameraCapacityOutcome.UNKNOWN, CameraCapacityReason.ARITHMETIC_OVERFLOW)
        }
    }

    private fun sum(held: List<CameraCapacityResidentGeometry>) = held.fold(0L) { bytes, geometry ->
        Math.addExact(bytes, geometry.chargedBytes) }

    private fun simulate(profile: CameraCapacityProfile, held: List<CameraCapacityResidentGeometry>,
        missing: List<CameraCapacitySource>): CameraCapacityPeak {
        var cells = held.size
        var bytes = sum(held)
        var peakCells = cells
        var peakBytes = bytes
        for (source in missing) {
            cells = Math.addExact(cells, 1)
            peakCells = maxOf(peakCells, cells)
            peakBytes = maxOf(peakBytes, Math.addExact(bytes, profile.loadingReservationBytes))
            bytes = Math.addExact(bytes, source.chargedBytesUpperBound!!)
            peakBytes = maxOf(peakBytes, bytes)
        }
        return CameraCapacityPeak(peakCells, peakBytes)
    }
}
