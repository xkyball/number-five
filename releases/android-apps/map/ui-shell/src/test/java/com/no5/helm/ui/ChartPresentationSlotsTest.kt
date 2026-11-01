package com.no5.helm.ui

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test

/** Endpoint ordering only; these host tests do not simulate the actual Android RenderThread. */
class ChartPresentationSlotsTest {
    private val geometry = ChartSurfaceGeometry.fit(1000, 600)
    private val home = ChartViewport(42.36, -71.04, .15, width = 1000, height = 600)
    private fun image(slot: Int, stamp: Long, camera: ChartViewport = home, generation: Long = 7) =
        ChartBufferSnapshot(stamp, generation, stamp, ChartFrame(stamp, "slot:$slot:$stamp", geometry, camera),
            producerCompleted = false, presentationSlot = slot)
    private fun slots() = ChartPresentationSlots().apply { reset(7, geometry) }

    @Test fun oldCallbackCannotRevealNewPixelsWithAnOldBasis() {
        val slots = slots()
        val initial = image(1, 10)
        assertEquals(initial, slots.acquire(1, initial))
        assertTrue(slots.committed(1, 10, 7))
        val target = transformChartViewport(home, pan = ScreenPoint(150.0, 0.0))
        val replacement = image(0, 11, target)
        // UPDATE_CALLBACK still reports the old image in the hidden slot; front stays frozen.
        assertNull(slots.acquire(0, image(0, 8)))
        assertEquals(initial, slots.visible)
        val before = chartBufferAffine(initial.frame.viewport, target, geometry)!!.map(ScreenPoint(500.0, 300.0))
        assertEquals(650.0, before.x, .000001)
        // Only the known acquired replacement becomes visible, together with its own basis.
        assertEquals(replacement, slots.acquire(0, replacement))
        val after = chartBufferAffine(slots.visible!!.frame.viewport, target, geometry)!!.map(ScreenPoint(650.0, 300.0))
        assertEquals(before.x, after.x, .000001)
    }

    @Test fun acquisitionDoesNotFreeOldSlotUntilExactTraversalCommit() {
        val slots = slots(); val first = image(1, 10)
        slots.acquire(1, first)
        assertNull(slots.acquire(0, image(0, 11)))
        assertFalse(slots.committed(0, 10, 7))
        assertFalse(slots.committed(1, 9, 7))
        assertFalse(slots.committed(1, 10, 6))
        assertTrue(slots.committed(1, 10, 7))
        assertFalse(slots.committed(1, 10, 7))
        assertNotNull(slots.acquire(0, image(0, 11)))
    }

    @Test fun visibleSlotCannotBeOverwrittenAndLateBackTimestampCannotMoveFrontBackwards() {
        val slots = slots()
        slots.acquire(1, image(1, 10)); slots.committed(1, 10, 7)
        assertNull(slots.acquire(1, image(1, 11)))
        slots.acquire(0, image(0, 12)); slots.committed(0, 12, 7)
        assertNull(slots.acquire(1, image(1, 10)))
        assertEquals(12L, slots.visible!!.stamp)
    }

    @Test fun resetInvalidatesStaleAcquisitionAndCommitWithoutReusingItsCamera() {
        val slots = slots(); slots.acquire(1, image(1, 10))
        slots.reset(8, geometry)
        assertNull(slots.acquire(1, image(1, 11)))
        assertFalse(slots.committed(1, 10, 7))
        assertNull(slots.visible)
        val wrongGeometry = image(1, 12, generation = 8).let {
            it.copy(frame = it.frame.copy(geometry = ChartSurfaceGeometry.fit(1000, 500)))
        }
        assertNull(slots.acquire(1, wrongGeometry))
        assertNull(slots.acquire(1, image(0, 12, generation = 8)))
        assertNotNull(slots.acquire(1, image(1, 13, generation = 8)))
    }

    @Test fun producerCompletionUpdatesMetadataWithoutMutatingCameraOrStage() {
        val slots = slots(); val first = image(1, 10)
        slots.acquire(1, first)
        assertTrue(slots.refresh(first.copy(producerCompleted = true, frame = first.frame.copy(scale = ChartScale(50.0, 100.0)))))
        assertTrue(slots.visible!!.producerCompleted)
        assertFalse(slots.refresh(first.copy(frame = first.frame.copy(viewport = home.copy(longitude = -70.0)))))
        assertFalse(slots.refresh(first.copy(stage = ChartContentStage.BASE_AREAS)))
        assertEquals(home, slots.visible!!.frame.viewport)
    }

    @Test fun generationChangeRetainsImmutableFrontAndAlreadyAcquiredCommitBarrier() {
        val slots = slots(); val first = image(1, 10)
        slots.acquire(1, first)
        slots.reset(8, geometry, preserveFront = true)
        assertEquals(first, slots.visible)
        assertEquals(1, slots.front)
        assertNull(slots.acquire(0, image(0, 11, generation = 8)))
        // An old exact commit still frees the old producer slot; it does not approve old-generation content.
        assertTrue(slots.committed(1, 10, 7))
        assertNull(slots.acquire(0, image(0, 12)))
        assertNotNull(slots.acquire(0, image(0, 13, generation = 8)))
    }

    @Test fun incompleteContentIsPresentableButStillHasItsExplicitStage() {
        val slots = slots()
        val areas = image(1, 10).copy(stage = ChartContentStage.REUSED_DETAILS_WITH_BASE)
        assertEquals(areas, slots.acquire(1, areas))
        assertEquals(ChartContentStage.REUSED_DETAILS_WITH_BASE, slots.visible!!.stage)
        assertFalse(slots.visible!!.producerCompleted)
    }
}
