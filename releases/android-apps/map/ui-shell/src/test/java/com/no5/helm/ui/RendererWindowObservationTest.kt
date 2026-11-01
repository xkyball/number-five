package com.no5.helm.ui

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test

/** Deterministic identity/race tests only. These are not Window, GPU or scanout measurements. */
class RendererWindowObservationTest {
    private val geometry = ChartSurfaceGeometry.fit(1000, 600)
    private val camera = ChartViewport(0.0, 0.0, .15, width = 1000, height = 600)
    private val presenter = Any()
    private val texture = Any()
    private val surface = Any()
    private fun identity(stamp: Long = 101) = RendererWindowIdentity(
        presenterOwner = presenter, textureOwner = texture, surfaceOwner = surface,
        readOnMain = true, visible = true, fullyVisible = true, covered = false, currentTexture = true,
        frontSlot = 0, stamp = stamp,
        buffer = ChartBufferSnapshot(stamp, 7, 100, ChartFrame(stamp, "public-fixture", geometry, camera,
            sourceGeneration = 11, contentGeneration = 13), producerCompleted = true, presentationSlot = 0),
        presenterGeneration = 7, producerGeneration = 2, sourceAuthority = 11,
        windowEpoch = 3, attachmentEpoch = 4, geometry = geometry, requestedViewport = camera,
        windowRect = RendererWindowRect(0, 100, 1000, 600), screenRect = RendererWindowRect(0, 100, 1000, 600),
    )
    private fun rejected(value: RendererWindowIdentity, reason: RendererWindowUnavailable) {
        val gate = RendererWindowProofGate()
        assertEquals(reason, value.unavailableReason)
        assertEquals(0L, gate.begin(value, 1))
        assertEquals(reason, gate.lastReason)
        assertEquals(0, gate.copyAttempts())
    }

    @Test fun exactStableCompletedFullCanBeObservedOnce() {
        val gate = RendererWindowProofGate(); val before = identity()
        val ticket = gate.begin(before, 10)
        assertTrue(ticket > 0)
        assertTrue(gate.complete(ticket, before.copy(), true, 20))
        assertEquals(1, gate.acceptedCopies())
        assertFalse(gate.complete(ticket, before, true, 21))
        assertEquals(RendererWindowUnavailable.STALE_TICKET, gate.lastReason)
    }
    @Test fun unknownStampCannotInheritLatestCameraOrBuffer() {
        rejected(identity().copy(buffer = null), RendererWindowUnavailable.UNKNOWN_BUFFER)
        rejected(identity().copy(stamp = 999), RendererWindowUnavailable.UNKNOWN_BUFFER)
    }
    @Test fun producerCompletionAndFullStageAreBothRequired() {
        val original = identity()
        rejected(original.copy(buffer = original.buffer!!.copy(producerCompleted = false)), RendererWindowUnavailable.NOT_FULL)
        rejected(original.copy(buffer = original.buffer!!.copy(stage = ChartContentStage.REUSED_DETAILS_WITH_BASE)), RendererWindowUnavailable.NOT_FULL)
    }
    @Test fun invisibleCoveredClippedOrWrongThreadNeverArmsPixelCopy() {
        val original = identity()
        rejected(original.copy(readOnMain = false), RendererWindowUnavailable.WRONG_THREAD)
        rejected(original.copy(visible = false), RendererWindowUnavailable.NOT_VISIBLE)
        rejected(original.copy(fullyVisible = false), RendererWindowUnavailable.PARTIALLY_VISIBLE)
        rejected(original.copy(covered = true), RendererWindowUnavailable.COVERED)
        rejected(original.copy(currentTexture = false), RendererWindowUnavailable.STALE_TEXTURE)
    }
    @Test fun mismatchingSourceGenerationSlotOrGeometryIsUnavailable() {
        val original = identity()
        rejected(original.copy(sourceAuthority = null), RendererWindowUnavailable.SOURCE_CHANGED)
        rejected(original.copy(sourceAuthority = 12), RendererWindowUnavailable.SOURCE_CHANGED)
        rejected(original.copy(presenterGeneration = 8), RendererWindowUnavailable.GENERATION_CHANGED)
        rejected(original.copy(frontSlot = 1), RendererWindowUnavailable.SLOT_CHANGED)
        rejected(original.copy(windowRect = original.windowRect.copy(width = 999)), RendererWindowUnavailable.GEOMETRY_CHANGED)
    }
    @Test fun transformedOldFullOrActiveGestureIsNotTheRequestedFullView() {
        rejected(identity().copy(requestedViewport = camera.copy(rotationRadians = .1)), RendererWindowUnavailable.CAMERA_NOT_FULL)
        rejected(identity().copy(touchActive = true), RendererWindowUnavailable.CAMERA_NOT_FULL)
        rejected(identity().copy(matrix = RendererWindowMatrix(perspectiveX = Float.NaN)), RendererWindowUnavailable.INVALID_MATRIX)
    }
    @Test fun everyOwnershipEpochMatrixAndPositionChangeDuringCopyFailsClosed() {
        val before = identity()
        val buffer = requireNotNull(before.buffer)
        val changes = listOf(before.copy(presenterOwner = Any()), before.copy(textureOwner = Any()),
            before.copy(surfaceOwner = Any()), before.copy(producerGeneration = 3), before.copy(windowEpoch = 4),
            before.copy(attachmentEpoch = 5), before.copy(matrix = RendererWindowMatrix(translateX = 1f)),
            before.copy(matrix = RendererWindowMatrix(skewY = .1f)), before.copy(screenRect = before.screenRect.copy(top = 101)),
            before.copy(buffer = buffer.copy(frame = buffer.frame.copy(contentGeneration = 14))),
            identity(102))
        changes.forEach { changed ->
            val gate = RendererWindowProofGate(); val ticket = gate.begin(before, 1)
            assertFalse(gate.complete(ticket, changed, true, 2))
            assertEquals(RendererWindowUnavailable.IDENTITY_CHANGED, gate.lastReason)
            assertEquals(0, gate.acceptedCopies())
        }
    }
    @Test fun referenceEqualityPreventsEqualLookingReplacementOwners() {
        class EqualOwner { override fun equals(other: Any?) = other is EqualOwner; override fun hashCode() = 1 }
        val gate = RendererWindowProofGate()
        val before = identity().copy(textureOwner = EqualOwner())
        assertFalse(gate.stable(before, before.copy(textureOwner = EqualOwner())))
    }
    @Test fun failedPixelCopyAndBackwardsCompletionTimeCannotProduceEvidence() {
        val gate = RendererWindowProofGate(); val value = identity()
        assertFalse(gate.complete(gate.begin(value, 10), value, false, 20))
        assertEquals(RendererWindowUnavailable.COPY_FAILED, gate.lastReason)
        assertFalse(gate.complete(gate.begin(value, 30), value, true, 29))
        assertEquals(RendererWindowUnavailable.INVALID_TIME, gate.lastReason)
        assertEquals(0, gate.acceptedCopies())
    }
    @Test fun delayedOlderCompletionDoesNotDeleteNewerPendingTicket() {
        val gate = RendererWindowProofGate(); val value = identity()
        val old = gate.begin(value, 1); assertTrue(gate.complete(old, value, true, 2))
        val current = gate.begin(value, 3)
        assertFalse(gate.complete(old, value, true, 4))
        assertTrue(gate.complete(current, value, true, 5))
        assertEquals(2, gate.acceptedCopies())
    }
    @Test fun oneOutstandingCopyRejectsAnotherWithoutLosingItsIdentity() {
        val gate = RendererWindowProofGate(); val value = identity()
        val first = gate.begin(value, 1)
        assertEquals(0L, gate.begin(identity(102), 2))
        assertEquals(1, gate.copyAttempts())
        assertEquals(RendererWindowUnavailable.STALE_TICKET, gate.lastReason)
        assertTrue(gate.complete(first, value, true, 3))
    }
    @Test fun cancellationRevokesThePendingCopyAndNeverRearms() {
        val gate = RendererWindowProofGate(); val value = identity()
        val ticket = gate.begin(value, 1); gate.cancel(2); gate.cancel(3)
        assertFalse(gate.complete(ticket, value, true, 4))
        assertEquals(0L, gate.begin(value, 5))
        assertEquals(RendererWindowUnavailable.CANCELLED, gate.lastReason)
        assertEquals(0, gate.acceptedCopies())
    }
    @Test fun sixAttemptsAndSixtyFourPrimitiveEventsAreHardBounds() {
        val gate = RendererWindowProofGate(); val value = identity()
        repeat(6) { index -> val ticket = gate.begin(value, index * 2L + 1); assertTrue(ticket > 0)
            assertTrue(gate.complete(ticket, value, true, index * 2L + 2)) }
        repeat(100) { assertEquals(0L, gate.begin(value, 20 + it.toLong())) }
        assertEquals(6, gate.copyAttempts()); assertEquals(6, gate.acceptedCopies())
        assertEquals(RendererWindowProofGate.MAX_EVENTS * RendererWindowProofGate.COLUMNS, gate.events().size)
        assertTrue(gate.droppedEvents() > 0)
        assertEquals(RendererWindowUnavailable.CAPACITY, gate.lastReason)
    }
    @Test fun exhaustedEventCapacityCannotArmAnUnrecordedCopy() {
        val gate = RendererWindowProofGate()
        repeat(64) { assertEquals(0L, gate.begin(identity().copy(buffer = null), it + 1L)) }
        assertEquals(0L, gate.begin(identity(), 65))
        assertEquals(RendererWindowUnavailable.CAPACITY, gate.lastReason)
        assertEquals(0, gate.copyAttempts())
        assertEquals(64 * 6, gate.events().size)
    }
}
