package com.no5.helm.engine

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test

class ChartBufferRegistryTest {
    private val geometry = ChartSurfaceGeometry.fit(2000, 1070)
    private val camera = ChartViewport(42.36, -71.04, .15, width = 2000, height = 1070)
    @Test fun hiddenSlotLookupCannotSelectVisibleBasisAndCompletionKeepsSlotIdentity() {
        val registry = ChartBufferRegistry()
        val front = registry.submit(0, 100, 1, geometry, camera, "test", presentationSlot = 0)!!
        registry.observe(front.stamp)
        val back = registry.submit(0, 101, 2, geometry, camera.copy(longitude = -71.03), "test", presentationSlot = 1)!!
        assertEquals(1, registry.lookup(back.stamp)!!.presentationSlot)
        assertEquals(front.stamp, registry.observedStamp())
        assertEquals(1, registry.complete(back.stamp, back.frame)!!.presentationSlot)
        assertEquals(front.stamp, registry.observedStamp())
    }
    @Test fun reusedDetailsStageCannotTurnIntoFullByCompletingItsProducer() {
        val registry = ChartBufferRegistry()
        val composite = registry.submit(0, 123, 9, geometry, camera, "test", ChartContentStage.REUSED_DETAILS_WITH_BASE)!!
        val completed = registry.complete(composite.stamp, composite.frame)!!
        assertTrue(completed.producerCompleted)
        assertEquals(ChartContentStage.REUSED_DETAILS_WITH_BASE, completed.stage)
        assertNotEquals(ChartContentStage.FULL, registry.observe(composite.stamp)!!.stage)
    }
    @Test fun baseAndFullStagesKeepDistinctTagsAndStageAfterCompletion() {
        val registry = ChartBufferRegistry()
        val base = registry.submit(0, 100, 9, geometry, camera, "test", ChartContentStage.BASE_AREAS)!!
        val full = registry.submit(0, 100, 9, geometry, camera, "test")!!
        assertNotEquals(base.stamp, full.stamp)
        assertEquals(base.frame.viewport, full.frame.viewport)
        val completedBase = registry.complete(base.stamp, base.frame)!!
        assertTrue(completedBase.producerCompleted)
        assertEquals(ChartContentStage.BASE_AREAS, completedBase.stage)
        assertEquals(ChartContentStage.FULL, full.stage)
        registry.invalidate()
        assertNull(registry.observe(base.stamp)); assertNull(registry.complete(full.stamp, full.frame))
    }
    @Test fun consumerCanResolveExactCameraBeforeProducerReturnsWithoutInventingCompletion() {
        val registry = ChartBufferRegistry()
        val b = registry.submit(0, 123, 9, geometry, camera, "test")!!
        assertEquals(camera, registry.lookup(123)!!.frame.viewport)
        assertFalse(registry.lookup(123)!!.producerCompleted)
        assertNull(registry.lookup(124))
        val completed = registry.complete(123, b.frame.copy(scale = ChartScale(100.0, 1000.0)))!!
        assertTrue(completed.producerCompleted)
        assertEquals(9L, completed.cameraRevision)
        assertEquals(123L, completed.stamp)
    }
    @Test fun invalidationRejectsLateSubmissionCompletionAndConsumerTags() {
        val registry = ChartBufferRegistry()
        val old = registry.submit(0, 123, 9, geometry, camera, "test")!!
        assertEquals(1L, registry.invalidate())
        assertNull(registry.submit(0, 124, 10, geometry, camera, "test"))
        assertNull(registry.complete(old.stamp, old.frame))
        assertNull(registry.lookup(old.stamp))
        assertEquals(1L, registry.submit(1, 125, 10, geometry, camera, "test")!!.generation)
    }
    @Test fun capEvictsOldTagsAndStampsRemainDistinctAtEqualClockTimes() {
        val registry = ChartBufferRegistry(2)
        val frames = (0L..2L).map { registry.submit(0, 100, it, geometry, camera, "test")!! }
        assertEquals(listOf(100L, 101L, 102L), frames.map { it.stamp })
        assertNull(registry.lookup(100))
        assertEquals(1L, registry.lookup(101)!!.cameraRevision)
        assertEquals(2L, registry.lookup(102)!!.cameraRevision)
    }
    @Test fun completionCannotRelabelAFrameWithAnotherCameraOrGeometry() {
        val registry = ChartBufferRegistry()
        val b = registry.submit(0, 100, 1, geometry, camera, "test")!!
        assertNull(registry.complete(100, b.frame.copy(viewport = camera.copy(longitude = -70.0))))
        assertFalse(registry.lookup(100)!!.producerCompleted)
        registry.discard(100)
        assertNull(registry.lookup(100))
    }
    @Test fun unknownNewerConsumerObservationCannotBeRelabelledByAnOldProducerCompletion() {
        val registry = ChartBufferRegistry()
        val old = registry.submit(0, 100, 1, geometry, camera, "test")!!
        assertEquals(old, registry.observe(100))
        assertNull(registry.observe(777))
        assertNotNull(registry.complete(100, old.frame))
        assertEquals(777L, registry.observedStamp())
        assertNull(registry.lookup(registry.observedStamp()))
        registry.invalidate(); assertEquals(0L, registry.observedStamp())
    }
    @Test fun producerCompletionAndLaterControlSubmissionKeepReserveRasterAssociationImmutable() {
        val registry = ChartBufferRegistry()
        val geometry = ChartRasterGeometry.reserve(this.geometry)
        val reserve = ChartBufferRaster.capture(geometry, camera, true)
        val old = registry.submit(0, 100, 1, this.geometry, camera, "test", presentationSlot = 1, raster = reserve)!!
        val completed = registry.complete(old.stamp, old.frame)!!
        assertEquals(reserve, completed.raster)
        val control = ChartBufferRaster.capture(geometry, camera, false)
        val next = registry.submit(0, 101, 2, this.geometry, camera, "test", presentationSlot = 0, raster = control)!!
        assertEquals(control, next.raster); assertEquals(reserve, registry.lookup(old.stamp)!!.raster)
        assertEquals(2500, completed.raster!!.producerCamera.width); assertEquals(2000, next.raster!!.producerCamera.width)
        registry.invalidate(); assertNull(registry.complete(old.stamp, old.frame))
    }
    @Test fun rasterCameraCannotBeRegisteredAgainstAnotherVisibleCamera() {
        val registry = ChartBufferRegistry()
        val wrong = ChartBufferRaster.capture(ChartRasterGeometry.reserve(geometry), camera.copy(longitude = -72.0), true)
        assertNull(registry.submit(0, 100, 1, geometry, camera, "test", raster = wrong))
    }
}
