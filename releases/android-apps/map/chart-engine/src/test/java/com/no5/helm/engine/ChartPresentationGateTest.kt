package com.no5.helm.engine
import org.junit.Assert.*
import org.junit.Test

class ChartPresentationGateTest {
    @Test fun reservationSnapshotIsReadOnlyAndNamesOnlyTheExactUnacquiredBackSlot() {
        val gate = ChartPresentationGate(); gate.reset(7)
        assertNull(gate.reservation(7, 1, 100))
        assertTrue(gate.reserve(7, 1, 100))
        val permit = gate.reservation(7, 1, 100)!!
        assertEquals(ChartPresentationGate.Reservation(7, 100, 1, 0), permit)
        assertNull(gate.reservation(6, 1, 100)); assertNull(gate.reservation(7, 0, 100))
        assertNull(gate.reservation(7, 1, 99)); assertNull(gate.reservation(7, 1, 0))
        assertEquals(permit, gate.reservation(7, 1, 100))
        assertTrue(gate.busy()); assertNull(gate.writableSlot(7))
        // Merely inspecting the permit cannot acquire/commit it: discard still releases this backslot.
        gate.discard(7, 100); assertFalse(gate.busy()); assertEquals(1, gate.writableSlot(7))
        assertNull(gate.reservation(7, 1, 100)); assertFalse(gate.acquired(7, 1, 100))
        assertTrue(gate.reserve(7, 1, 101)); assertTrue(gate.acquired(7, 1, 101))
        assertNull(gate.reservation(7, 1, 101))
        gate.discard(7, 101); assertTrue(gate.busy()) // An actually acquired image is not unpublished by discard.
        assertTrue(gate.committed(1, 101)); assertEquals(0, gate.writableSlot(7))
        assertNull(gate.reservation(7, 1, 101))
    }

    @Test fun oldReservationCannotBeRevivedAcrossDiscardEpochChangeOrAReplacementStamp() {
        val gate = ChartPresentationGate(); gate.reset(7)
        assertTrue(gate.reserve(7, 1, 100)); assertTrue(gate.acknowledge(7, 1, 100))
        assertTrue(gate.reserve(7, 0, 101)); val old = gate.reservation(7, 0, 101)!!
        gate.discard(7, 101)
        assertTrue(gate.reserve(7, 0, 102)); val replacement = gate.reservation(7, 0, 102)!!
        gate.discard(old.generation, old.stamp)
        assertEquals(replacement, gate.reservation(7, 0, 102)); assertTrue(gate.busy())
        gate.reset(8, keepFront = true)
        assertNull(gate.reservation(7, 0, 102)); assertNull(gate.reservation(8, 0, 102))
        assertEquals(0, gate.writableSlot(8)) // Same actual front; new generation may use only its backslot.
        assertTrue(gate.reserve(8, 0, 103)); val current = gate.reservation(8, 0, 103)!!
        gate.discard(7, 102)
        assertEquals(ChartPresentationGate.Reservation(8, 103, 0, 1), current)
        assertEquals(current, gate.reservation(8, 0, 103))
        assertFalse(gate.acquired(7, 0, 102)); assertFalse(gate.acknowledge(7, 0, 102))
        assertTrue(gate.acquired(8, 0, 103)); assertTrue(gate.committed(0, 103))
        assertEquals(1, gate.writableSlot(8)); assertNull(gate.reservation(8, 0, 103))
    }

    @Test fun invalidationBetweenPromotionAndFrameCommitCannotOverwriteEitherImage() {
        val gate = ChartPresentationGate(); gate.reserve(0, 1, 100)
        assertTrue(gate.acquired(0, 1, 100)); gate.reset(1, keepFront = true)
        assertTrue(gate.busy()); assertNull(gate.writableSlot(1))
        assertFalse(gate.acquired(1, 1, 100)); gate.discard(0, 100); assertTrue(gate.busy())
        assertTrue(gate.committed(1, 100)); assertEquals(0, gate.writableSlot(1))
        assertFalse(gate.committed(1, 100))
    }
    @Test fun styleInvalidationRetainsActualFrontUntilNewGenerationCanReplaceIt() {
        val gate = ChartPresentationGate(); gate.reserve(0, 1, 100); gate.acknowledge(0, 1, 100)
        gate.reserve(0, 0, 101)
        gate.reset(1, keepFront = true)
        assertEquals(0, gate.writableSlot(1)); assertFalse(gate.acknowledge(0, 0, 101))
        assertFalse(gate.reserve(1, 1, 102)); assertTrue(gate.reserve(1, 0, 102))
    }
    @Test fun visibleSlotCannotBeOverwrittenBeforeExactPromotion() {
        val gate = ChartPresentationGate()
        assertEquals(1, gate.writableSlot(0))
        assertFalse(gate.reserve(0, 0, 100))
        assertTrue(gate.reserve(0, 1, 100)); assertNull(gate.writableSlot(0))
        assertFalse(gate.acknowledge(0, 0, 100)); assertFalse(gate.acknowledge(0, 1, 99))
        assertTrue(gate.acknowledge(0, 1, 100)); assertEquals(0, gate.writableSlot(0))
        assertTrue(gate.reserve(0, 0, 101)); assertFalse(gate.acknowledge(0, 1, 100))
        assertNull(gate.writableSlot(0))
    }
    @Test fun lateAckOrFailureCannotReleaseAReplacementFromAnotherGeneration() {
        val gate = ChartPresentationGate(); gate.reserve(0, 1, 100)
        gate.reset(1); assertTrue(gate.reserve(1, 1, 101))
        assertFalse(gate.acknowledge(0, 1, 100)); gate.discard(0, 100)
        assertTrue(gate.busy()); gate.discard(1, 101); assertFalse(gate.busy())
    }
    @Test fun consumerCanPromoteBeforeProducerCallbackWithoutASecondPendingImage() {
        val gate = ChartPresentationGate(); gate.reserve(0, 1, 100)
        assertTrue(gate.acknowledge(0, 1, 100))
        assertTrue(gate.reserve(0, 0, 101)); gate.discard(0, 100)
        assertTrue(gate.busy()); assertNull(gate.writableSlot(0))
    }
}
