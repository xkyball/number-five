package com.no5.helm.ui

import com.no5.helm.chart.*
import com.no5.preparation.ui.DistanceUnit
import com.no5.preparation.ui.ViewportSnapshot
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

/** Exercises the product projector; no latest-camera substitute, invented metres or display claims. */
class VisualChartScaleTest {
    private val geometry = ChartSurfaceGeometry.fit(2000, 1070)
    private val camera = ChartViewport(metresToPixels = .12, width = geometry.frameWidth, height = geometry.frameHeight)
    private val presenter = Any(); private val texture = Any(); private val surface = Any()
    private fun observed(slot: Int = 0, stamp: Long = 101, source: Long = 5, distance: Double = 1000.0,
        padded: Boolean = false, off: Boolean = false): VisualPresentationSnapshot {
        val raster = if (padded) ChartRasterGeometry.reserve(geometry) else null
        val frame = ChartFrame(stamp, "query:$stamp", geometry, camera, ChartScale(120.0, distance), source, 11)
        return VisualPresentationSnapshot().apply {
            available = true; presenterOwner = presenter; textureOwner = texture; surfaceOwner = surface
            observedStamp = stamp; generation = 7; producerGeneration = 2; sourceAuthority = source
            attachmentEpoch = 3; windowEpoch = 4; frontSlot = slot; this.geometry = this@VisualChartScaleTest.geometry
            buffer = ChartBufferSnapshot(stamp, 7, stamp - 1, frame, true, presentationSlot = if (off) -1 else slot,
                raster = raster?.takeUnless { off }?.let { ChartBufferRaster.capture(it, camera, true) })
            if (raster != null) {
                val crop = chartRasterCrop(raster)
                cropX = crop.a; cropY = crop.d; crop.matrixValues().copyInto(matrix)
            }
        }
    }
    private fun project(projector: VisualChartScaleProjector, observation: VisualPresentationSnapshot,
        source: Long = 5, observedSurface: Long = 9, expectedSurface: Long = 9, generation: Long = 7,
        unit: DistanceUnit = DistanceUnit.KILOMETRES) =
        projector.update(observation, source, observedSurface, expectedSurface, generation, unit)

    @Test fun pendingFollowKeepsOnlyTheKnownStillVisibleFrontMeasurement() {
        val projector = VisualChartScaleProjector(); val visible = observed(off = true)
        val first = requireNotNull(project(projector, visible))
        val pending = ChartEngineStatus(viewport = camera.copy(latitude = .01), confirmedFrame = null,
            chartSourceGeneration = 5, surfaceGeneration = 9, bufferTransformGeneration = 7)
        val retained = project(projector, visible, pending.chartSourceGeneration, pending.surfaceGeneration,
            pending.surfaceGeneration, pending.bufferTransformGeneration)
        assertSame(first, retained)
        assertNull(pending.confirmedFrame)
        assertEquals("1 km", retained!!.scale.label)
        assertTrue(retained.viewport.cameraToken.startsWith("VISUAL:"))
        assertNotEquals(ViewportSnapshot(visible.buffer!!.frame.revision, 2000, 1070, visible.buffer!!.frame.cameraToken), retained.viewport)
    }
    @Test fun gpsRevisionAtTheSamePoseKeepsBothDisplaysAvailableWithOneVisualAuthority() {
        val projector = VisualChartScaleProjector()
        var last: VisualChartScale? = null
        for (revision in 101L..140L) {
            val current = requireNotNull(project(projector, observed(stamp = revision)))
            assertEquals("1 km", current.scale.label); assertEquals(120f, current.scale.lengthPx, 0f)
            assertEquals(current.viewport, current.scale.viewport)
            if (last != null) assertNotEquals(last.viewport, current.viewport)
            last = current
        }
    }
    @Test fun actualNewZoomUsesItsOwnMeasurementRatherThanKeepingTheOldLabel() {
        val projector = VisualChartScaleProjector()
        val original = requireNotNull(project(projector, observed()))
        val zoomed = requireNotNull(project(projector, observed(stamp = 102, distance = 500.0)))
        assertEquals("1 km", original.scale.label); assertEquals("500 m", zoomed.scale.label)
        assertEquals(120f, zoomed.scale.lengthPx, 0f)
        assertNotEquals(original.viewport, zoomed.viewport)
    }
    @Test fun unknownNewTextureStampImmediatelyClearsWithoutOldMeasurementSubstitution() {
        val projector = VisualChartScaleProjector(); val visible = observed()
        assertNotNull(project(projector, visible))
        visible.observedStamp = 102
        assertNull(project(projector, visible))
        visible.buffer = null
        assertNull(project(projector, visible))
        visible.buffer = observed(stamp = 102, distance = 500.0).buffer
        assertEquals("500 m", project(projector, visible)!!.scale.label)
    }
    @Test fun completionAndScaleMetadataCanArriveAfterTheTextureUpdateForTheSameStamp() {
        val projector = VisualChartScaleProjector(); val visible = observed()
        val complete = visible.buffer!!
        visible.buffer = complete.copy(producerCompleted = false, frame = complete.frame.copy(scale = null))
        assertNull(project(projector, visible))
        visible.buffer = complete.copy(frame = complete.frame.copy(scale = null))
        assertNull(project(projector, visible))
        visible.buffer = complete
        assertEquals(101L, project(projector, visible)!!.bufferStamp)
        visible.buffer = complete.copy(frame = complete.frame.copy(scale = ChartScale(120.0, 500.0)))
        assertEquals("500 m", project(projector, visible)!!.scale.label)
    }
    @Test fun incompleteAndInvalidMeasurementsAlwaysClearTheCurrentProjection() {
        val projector = VisualChartScaleProjector(); val visible = observed(); val complete = visible.buffer!!
        assertNotNull(project(projector, visible))
        for (measurement in listOf<ChartScale?>(null, ChartScale(0.0, 1000.0), ChartScale(120.0, Double.NaN),
            ChartScale(Double.POSITIVE_INFINITY, 1000.0), ChartScale(120.0, -1.0))) {
            visible.buffer = complete.copy(frame = complete.frame.copy(scale = measurement))
            assertNull(project(projector, visible))
        }
        visible.buffer = complete.copy(stage = ChartContentStage.BASE_AREAS, frame = complete.frame.copy(scale = null))
        assertNull(project(projector, visible))
    }
    @Test fun completedMeasuredStagesKeepTheirActualContentStageWithoutInventingFullContent() {
        for (stage in ChartContentStage.entries) {
            val visible = observed()
            visible.buffer = visible.buffer!!.copy(stage = stage)
            val result = requireNotNull(project(VisualChartScaleProjector(), visible))
            assertEquals(stage, result.contentStage)
            assertEquals("1 km", result.scale.label)
            visible.buffer = visible.buffer!!.copy(producerCompleted = false)
            assertNull(project(VisualChartScaleProjector(), visible))
        }
    }
    @Test fun onlyAnExactPresenterOwnedFrontCanOutliveTheCurrentProducerEpoch() {
        val projector = VisualChartScaleProjector(); val visible = observed()
        assertNotNull(project(projector, visible))
        visible.generation = 8
        assertNull(project(projector, visible, generation = 8))
        visible.pinnedFront = visible.buffer!!.copy()
        assertNull(project(projector, visible, generation = 8))
        visible.pinnedFront = visible.buffer
        val result = requireNotNull(project(projector, visible, generation = 8))
        assertEquals(7L, result.bufferGeneration)
        assertEquals(101L, result.bufferStamp)
        assertNull(project(projector, visible, source = 6, generation = 8))
        assertNull(project(projector, visible, expectedSurface = 10, generation = 8))
        visible.observedStamp = 102
        assertNull(project(projector, visible, generation = 8))
    }
    @Test fun explicitSourceAndSurfaceFencesPreventPreviousAuthorityReuse() {
        val projector = VisualChartScaleProjector(); val visible = observed()
        assertNotNull(project(projector, visible))
        assertNull(project(projector, visible, source = 6))
        assertNull(project(projector, visible, expectedSurface = 10))
        assertNull(project(projector, visible, generation = 8))
        val replacement = observed(source = 6, stamp = 102)
        assertEquals(6L, project(projector, replacement, source = 6, observedSurface = 10, expectedSurface = 10)!!.sourceGeneration)
    }
    @Test fun twoSlotsUseTheActuallyObservedFrontAndNeverThePendingBack() {
        val projector = VisualChartScaleProjector()
        val first = observed(slot = 0); val second = observed(slot = 1, stamp = 102, distance = 500.0)
        assertEquals("1 km", project(projector, first)!!.scale.label)
        val back = second.buffer
        second.buffer = first.buffer
        assertNull(project(projector, second))
        second.buffer = back
        assertEquals("500 m", project(projector, second)!!.scale.label)
        second.buffer = back!!.copy(presentationSlot = -1)
        assertNull(project(projector, second))
    }
    @Test fun offPlainBufferAndReserveCropPreserveTheMeasuredVisibleSurfaceLength() {
        for (off in listOf(false, true)) {
            val plain = requireNotNull(project(VisualChartScaleProjector(), observed(off = off)))
            val padded = observed(padded = true, off = off)
            assertTrue(padded.cropX > 1.0 && padded.cropY > 1.0)
            val cropped = requireNotNull(project(VisualChartScaleProjector(), padded))
            assertEquals(plain.scale.lengthPx, cropped.scale.lengthPx, .0001f)
            assertEquals(plain.scale.label, cropped.scale.label)
        }
    }
    @Test fun uniformZoomRotationAndStaticCropTransformPixelsButPreserveMeasuredMetres() {
        val visible = observed(padded = true)
        val image = visible.buffer!!
        val target = camera.copy(metresToPixels = camera.metresToPixels * 2, rotationRadians = PI / 2)
        chartBufferCameraAffine(camera, target, geometry, image.raster)!!.matrixValues().copyInto(visible.matrix)
        val result = requireNotNull(project(VisualChartScaleProjector(), visible))
        assertEquals("1 km", result.scale.label)
        assertEquals(240f, result.scale.lengthPx, .0001f)
    }
    @Test fun reflectionShearNonuniformAndPerspectiveMatricesAreUnavailable() {
        val invalid = listOf(floatArrayOf(-1f,0f,0f,0f,1f,0f,0f,0f,1f),
            floatArrayOf(1f,.1f,0f,0f,1f,0f,0f,0f,1f), floatArrayOf(2f,0f,0f,0f,1f,0f,0f,0f,1f),
            floatArrayOf(1f,0f,0f,0f,1f,0f,.001f,0f,1f), floatArrayOf(0f,0f,0f,0f,0f,0f,0f,0f,1f),
            floatArrayOf(Float.NaN,0f,0f,0f,1f,0f,0f,0f,1f))
        invalid.forEach { values ->
            val projector = VisualChartScaleProjector(); val visible = observed()
            assertNotNull(project(projector, visible)); values.copyInto(visible.matrix)
            assertNull(project(projector, visible))
        }
    }
    @Test fun positiveScaleWithHalfTurnIsRotationNotAMirroredCoordinateSystem() {
        val visible = observed()
        floatArrayOf(-1f,0f,0f,0f,-1f,0f,0f,0f,1f).copyInto(visible.matrix)
        assertEquals(120f, project(VisualChartScaleProjector(), visible)!!.scale.lengthPx, 0f)
    }
    @Test fun unitChangeReprojectsTheSameMeasuredStampWithoutAnyNewNativeFrame() {
        val projector = VisualChartScaleProjector(); val visible = observed()
        val nautical = requireNotNull(project(projector, visible, unit = DistanceUnit.NAUTICAL_MILES))
        val metric = requireNotNull(project(projector, visible, unit = DistanceUnit.KILOMETRES))
        assertEquals(nautical.bufferStamp, metric.bufferStamp)
        assertEquals("0,5 sm", nautical.scale.label); assertEquals("1 km", metric.scale.label)
        assertEquals(metric.viewport, metric.scale.viewport)
    }
    @Test fun repeatedEventsReuseTheProjectionWhileChangedMatrixGetsNewVisualAuthority() {
        val projector = VisualChartScaleProjector(); val visible = observed()
        val original = requireNotNull(project(projector, visible))
        repeat(100) { assertSame(original, project(projector, visible)) }
        visible.matrix[2] = 20f
        val moved = requireNotNull(project(projector, visible))
        assertNotEquals(original.viewport, moved.viewport)
        assertEquals(original.scale.lengthPx, moved.scale.lengthPx, 0f)
    }
    @Test fun coverDetachOrUnknownGeometryImmediatelyClearsAndDoesNotResurrectCachedData() {
        val projector = VisualChartScaleProjector(); val visible = observed()
        assertNotNull(project(projector, visible)); visible.clear()
        assertNull(project(projector, visible))
        val restored = observed(); restored.geometry = null
        assertNull(project(projector, restored))
        restored.geometry = geometry; restored.cropX = Double.NaN
        assertNull(project(projector, restored))
    }
}
