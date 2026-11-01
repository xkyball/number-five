package com.no5.helm.engine

/** Render cancellation and camera APPLIED are independent identities. No viewport or JNI lives here. */
internal data class NormalRenderAuthority(val epoch: Long, val cameraRevision: Long,
    val sourceGeneration: Long, val surfaceGeneration: Long) {
    init { require(epoch in 1 until Long.MAX_VALUE && cameraRevision >= 0 && sourceGeneration >= 0 && surfaceGeneration >= 0) }
}

/** At most latest/applied tokens are retained; callers cannot install a forged or retired applied token. */
internal class NormalRenderAuthorityTracker {
    private var latest: NormalRenderAuthority? = null
    private var applied: NormalRenderAuthority? = null
    private var closed = false
    @Synchronized fun advance(camera: Long, source: Long, surface: Long): NormalRenderAuthority {
        check(!closed); require(camera >= 0 && source >= 0 && surface >= 0)
        val old = latest
        require(old == null || camera >= old.cameraRevision && source >= old.sourceGeneration && surface >= old.surfaceGeneration) {
            "Render authority cannot move backwards"
        }
        if (old != null && camera == old.cameraRevision && source == old.sourceGeneration && surface == old.surfaceGeneration) return old
        return NormalRenderAuthority(Math.addExact(old?.epoch ?: 0L, 1), camera, source, surface).also { latest = it }
    }
    @Synchronized fun markApplied(captured: NormalRenderAuthority, camera: Long, source: Long, surface: Long): Boolean {
        if (closed || captured !== latest && captured !== applied || captured.cameraRevision != camera ||
            captured.sourceGeneration != source || captured.surfaceGeneration != surface) return false
        applied = captured; return true
    }
    @Synchronized fun applied(): NormalRenderAuthority? = if (closed) null else applied
    @Synchronized fun latest(): NormalRenderAuthority? = if (closed) null else latest
    @Synchronized fun clearApplied() { applied = null }
    @Synchronized fun close() { closed = true; applied = null; latest = null }
}

internal enum class NormalRenderOutcome { FULL_COMPLETE, SUPERSEDED_RENDER }

/** A superseded render is an expected result, not a camera failure and not a FULL publication. */
internal fun normalRenderOutcome(code: Int, discard: () -> Unit, requeue: () -> Unit): NormalRenderOutcome = when (code) {
    0 -> NormalRenderOutcome.FULL_COMPLETE
    1 -> { discard(); requeue(); NormalRenderOutcome.SUPERSEDED_RENDER }
    else -> error("Invalid native normal-render outcome")
}

/** Cancellation can race owner binding as well as drawing; neither implies an Int1/native-no-FULL claim. */
internal inline fun normalRenderOperation(cancelledOrClosed: () -> Boolean, discard: () -> Unit,
    operation: () -> Boolean): Boolean = try { operation() }
    catch (failure: Exception) { if (cancelledOrClosed()) { discard(); false } else throw failure }
