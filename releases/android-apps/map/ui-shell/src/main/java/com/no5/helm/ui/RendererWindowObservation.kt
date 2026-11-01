package com.no5.helm.ui

import com.no5.helm.chart.ChartBufferSnapshot
import com.no5.helm.chart.ChartBufferTransformMode
import com.no5.helm.chart.ChartContentStage
import com.no5.helm.chart.ChartSurfaceGeometry
import com.no5.helm.chart.ChartViewport

internal data class RendererWindowRect(val left: Int, val top: Int, val width: Int, val height: Int)

/** All nine actual TextureView coefficients, including skew and perspective. */
internal data class RendererWindowMatrix(
    val scaleX: Float = 1f, val skewX: Float = 0f, val translateX: Float = 0f,
    val skewY: Float = 0f, val scaleY: Float = 1f, val translateY: Float = 0f,
    val perspectiveX: Float = 0f, val perspectiveY: Float = 0f, val perspectiveScale: Float = 1f,
) {
    fun finite() = scaleX.isFinite() && skewX.isFinite() && translateX.isFinite() &&
        skewY.isFinite() && scaleY.isFinite() && translateY.isFinite() &&
        perspectiveX.isFinite() && perspectiveY.isFinite() && perspectiveScale.isFinite()
}

internal enum class RendererWindowUnavailable {
    NONE, WRONG_THREAD, NOT_VISIBLE, PARTIALLY_VISIBLE, COVERED, NO_FRONT, NO_SURFACE,
    STALE_TEXTURE, UNKNOWN_BUFFER, NOT_FULL, GENERATION_CHANGED, SOURCE_CHANGED,
    SLOT_CHANGED, GEOMETRY_CHANGED, CAMERA_NOT_FULL, INVALID_MATRIX, COPY_FAILED,
    IDENTITY_CHANGED, CANCELLED, CAPACITY, STALE_TICKET, INVALID_TIME,
}

/**
 * Passive UI-thread observation. Object references are compared by identity and never exported.
 * A timestamp is looked up exactly; this object never substitutes the latest camera or frame.
 * Equality deliberately includes the requested viewport, matrix and actual Window coordinates.
 */
internal data class RendererWindowIdentity(
    val presenterOwner: Any? = null,
    val textureOwner: Any? = null,
    val surfaceOwner: Any? = null,
    val readOnMain: Boolean = false,
    val visible: Boolean = false,
    val fullyVisible: Boolean = false,
    val covered: Boolean = true,
    val currentTexture: Boolean = false,
    val frontSlot: Int = -1,
    val stamp: Long = 0,
    val buffer: ChartBufferSnapshot? = null,
    val presenterGeneration: Long = -1,
    val producerGeneration: Long = -1,
    val sourceAuthority: Long? = null,
    val windowEpoch: Long = -1,
    val attachmentEpoch: Long = -1,
    val mode: ChartBufferTransformMode = ChartBufferTransformMode.OFF,
    val geometry: ChartSurfaceGeometry? = null,
    val requestedViewport: ChartViewport? = null,
    val touchActive: Boolean = false,
    val matrix: RendererWindowMatrix = RendererWindowMatrix(),
    val windowRect: RendererWindowRect = RendererWindowRect(0, 0, 0, 0),
    val screenRect: RendererWindowRect = RendererWindowRect(0, 0, 0, 0),
) {
    val unavailableReason: RendererWindowUnavailable get() {
        if (!readOnMain) return RendererWindowUnavailable.WRONG_THREAD
        if (!visible) return RendererWindowUnavailable.NOT_VISIBLE
        if (!fullyVisible) return RendererWindowUnavailable.PARTIALLY_VISIBLE
        if (covered) return RendererWindowUnavailable.COVERED
        if (frontSlot !in 0..1) return RendererWindowUnavailable.NO_FRONT
        if (presenterOwner == null || textureOwner == null || surfaceOwner == null || producerGeneration < 0)
            return RendererWindowUnavailable.NO_SURFACE
        if (!currentTexture || stamp <= 0) return RendererWindowUnavailable.STALE_TEXTURE
        val exact = buffer ?: return RendererWindowUnavailable.UNKNOWN_BUFFER
        if (exact.stamp != stamp) return RendererWindowUnavailable.UNKNOWN_BUFFER
        if (!exact.producerCompleted || exact.stage != ChartContentStage.FULL) return RendererWindowUnavailable.NOT_FULL
        if (exact.generation != presenterGeneration) return RendererWindowUnavailable.GENERATION_CHANGED
        if (sourceAuthority == null || exact.frame.sourceGeneration != sourceAuthority) return RendererWindowUnavailable.SOURCE_CHANGED
        if (exact.presentationSlot >= 0 && exact.presentationSlot != frontSlot) return RendererWindowUnavailable.SLOT_CHANGED
        if (geometry == null || exact.frame.geometry != geometry || windowRect.width != geometry.surfaceWidth ||
            windowRect.height != geometry.surfaceHeight || screenRect.width != windowRect.width || screenRect.height != windowRect.height)
            return RendererWindowUnavailable.GEOMETRY_CHANGED
        if (touchActive || exact.frame.viewport != requestedViewport) return RendererWindowUnavailable.CAMERA_NOT_FULL
        if (!matrix.finite()) return RendererWindowUnavailable.INVALID_MATRIX
        return RendererWindowUnavailable.NONE
    }
}

/**
 * Separate image-evidence run, never installed in the ordinary render/timing path.
 * At most six copy attempts and 64 primitive records; only the in-flight ticket retains an identity.
 * Successful Window copies are observations, not hardware scanout or first-display timestamps.
 */
internal class RendererWindowProofGate {
    private val records = LongArray(MAX_EVENTS * COLUMNS)
    private var eventCount = 0
    private var dropped = 0L
    private var nextTicket = 0L
    private var pendingTicket = 0L
    private var pendingIdentity: RendererWindowIdentity? = null
    private var pendingAt = 0L
    private var attempts = 0
    private var accepted = 0
    private var cancelled = false
    var lastReason: RendererWindowUnavailable = RendererWindowUnavailable.NONE
        private set

    @Synchronized fun begin(identity: RendererWindowIdentity, atMonoNs: Long): Long {
        val reason = when {
            cancelled -> RendererWindowUnavailable.CANCELLED
            atMonoNs <= 0 -> RendererWindowUnavailable.INVALID_TIME
            eventCount >= MAX_EVENTS || attempts >= MAX_IMAGES -> RendererWindowUnavailable.CAPACITY
            pendingTicket != 0L -> RendererWindowUnavailable.STALE_TICKET
            else -> identity.unavailableReason
        }
        lastReason = reason
        if (reason != RendererWindowUnavailable.NONE) { record(REJECTED, 0, identity, atMonoNs, reason); return 0 }
        val ticket = ++nextTicket
        attempts++; pendingTicket = ticket; pendingIdentity = identity; pendingAt = atMonoNs
        record(ARMED, ticket, identity, atMonoNs, RendererWindowUnavailable.NONE)
        return ticket
    }

    @Synchronized fun complete(ticket: Long, after: RendererWindowIdentity, copySucceeded: Boolean, atMonoNs: Long): Boolean {
        val before = pendingIdentity
        val reason = when {
            cancelled -> RendererWindowUnavailable.CANCELLED
            ticket <= 0 || ticket != pendingTicket || before == null -> RendererWindowUnavailable.STALE_TICKET
            atMonoNs < pendingAt -> RendererWindowUnavailable.INVALID_TIME
            eventCount >= MAX_EVENTS -> RendererWindowUnavailable.CAPACITY
            !copySucceeded -> RendererWindowUnavailable.COPY_FAILED
            !stable(before, after) -> RendererWindowUnavailable.IDENTITY_CHANGED
            else -> RendererWindowUnavailable.NONE
        }
        // A delayed callback for an older ticket must not revoke a newer copy.
        if (ticket == pendingTicket) { pendingTicket = 0; pendingIdentity = null }
        lastReason = reason
        if (reason == RendererWindowUnavailable.NONE) accepted++
        record(if (reason == RendererWindowUnavailable.NONE) WINDOW_COPY_OBSERVED else REJECTED, ticket, after, atMonoNs, reason)
        return reason == RendererWindowUnavailable.NONE
    }

    @Synchronized fun cancel(atMonoNs: Long) {
        if (cancelled) return
        cancelled = true
        record(CANCEL, pendingTicket, pendingIdentity, atMonoNs, RendererWindowUnavailable.CANCELLED)
        pendingTicket = 0; pendingIdentity = null; lastReason = RendererWindowUnavailable.CANCELLED
    }

    fun stable(before: RendererWindowIdentity, after: RendererWindowIdentity): Boolean =
        before.unavailableReason == RendererWindowUnavailable.NONE && after.unavailableReason == RendererWindowUnavailable.NONE &&
            before.presenterOwner === after.presenterOwner && before.textureOwner === after.textureOwner &&
            before.surfaceOwner === after.surfaceOwner && before == after

    @Synchronized fun events(): LongArray = records.copyOf(eventCount * COLUMNS)
    @Synchronized fun droppedEvents(): Long = dropped
    @Synchronized fun copyAttempts(): Int = attempts
    @Synchronized fun acceptedCopies(): Int = accepted

    private fun record(kind: Int, ticket: Long, identity: RendererWindowIdentity?, at: Long, reason: RendererWindowUnavailable) {
        if (eventCount >= MAX_EVENTS) { dropped++; return }
        val offset = eventCount++ * COLUMNS
        records[offset] = kind.toLong(); records[offset + 1] = at; records[offset + 2] = ticket
        records[offset + 3] = identity?.stamp ?: 0; records[offset + 4] = identity?.buffer?.cameraRevision ?: -1
        records[offset + 5] = reason.ordinal.toLong()
    }

    companion object {
        const val MAX_IMAGES = 6
        const val MAX_EVENTS = 64
        const val COLUMNS = 6
        const val ARMED = 1
        const val WINDOW_COPY_OBSERVED = 2
        const val REJECTED = 3
        const val CANCEL = 4
    }
}
