package com.no5.helm.ui

import com.no5.helm.chart.ChartBufferSnapshot
import com.no5.helm.chart.ChartContentStage
import com.no5.helm.chart.ChartSurfaceGeometry
import com.no5.preparation.ui.DistanceUnit
import com.no5.preparation.ui.ScaleBar
import com.no5.preparation.ui.ViewportSnapshot
import com.no5.preparation.ui.scaleBarForUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** UI instrument authority only. Its VISUAL token can never authorize an engine coordinate query. */
internal data class VisualChartScale(val viewport: ViewportSnapshot, val scale: ScaleBar,
    val bufferStamp: Long, val cameraRevision: Long, val sourceGeneration: Long,
    val surfaceGeneration: Long, val bufferGeneration: Long, val contentStage: ChartContentStage)

/** One reusable main-thread scratch record; never retained as an asynchronous callback payload. */
internal class VisualPresentationSnapshot {
    var available = false
    var presenterOwner: Any? = null
    var textureOwner: Any? = null
    var surfaceOwner: Any? = null
    var observedStamp = 0L
    var buffer: ChartBufferSnapshot? = null
    /** The Presenter proved this same immutable image is still its actual owned front. */
    var pinnedFront: ChartBufferSnapshot? = null
    var generation = -1L
    var producerGeneration = -1L
    var sourceAuthority: Long? = null
    var attachmentEpoch = -1L
    var windowEpoch = -1L
    var frontSlot = -1
    var geometry: ChartSurfaceGeometry? = null
    var cropX = 1.0
    var cropY = 1.0
    val matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    fun clear() {
        available = false; buffer = null; pinnedFront = null; presenterOwner = null; textureOwner = null; surfaceOwner = null
    }
}

/**
 * One cached projection, recomputed from the actual front's measured scale only.
 * Crop normalization converts TextureView's padded-queue coordinates back to the measured
 * visible-Surface coordinate system. It does not derive metres from a desired/latest camera.
 */
internal class VisualChartScaleProjector {
    private var projection: VisualChartScale? = null
    private var buffer: ChartBufferSnapshot? = null
    private var presenter: Any? = null
    private var texture: Any? = null
    private var surface: Any? = null
    private var producerGeneration = -1L
    private var surfaceGeneration = -1L
    private var attachmentEpoch = -1L
    private var windowEpoch = -1L
    private var frontSlot = -1
    private var unit: DistanceUnit? = null
    private var cropX = Double.NaN
    private var cropY = Double.NaN
    private val matrix = FloatArray(9)
    private var visualRevision = 0L

    fun clear(): VisualChartScale? {
        projection = null; buffer = null; presenter = null; texture = null; surface = null
        return null
    }

    fun update(observed: VisualPresentationSnapshot, expectedSource: Long, observedSurface: Long,
        expectedSurface: Long, expectedGeneration: Long, distanceUnit: DistanceUnit): VisualChartScale? {
        val exact = observed.buffer
        val geometry = observed.geometry
        // Camera/query cancellation retires a producer epoch without replacing its visible pixels.
        // Only the Presenter's exact, owner-checked front pin may outlive that producer epoch.
        val ownedFront = exact != null && observed.pinnedFront === exact
        if (!observed.available || exact == null || geometry == null || observed.observedStamp <= 0 ||
            exact.stamp != observed.observedStamp || !exact.producerCompleted ||
            observed.presenterOwner == null || observed.textureOwner == null || observed.surfaceOwner == null ||
            observed.producerGeneration < 0 || observed.frontSlot !in 0..1 ||
            exact.presentationSlot >= 0 && exact.presentationSlot != observed.frontSlot ||
            exact.presentationSlot < 0 && observed.frontSlot != 0 ||
            (!ownedFront && (exact.generation != observed.generation || exact.generation != expectedGeneration)) ||
            observed.sourceAuthority != expectedSource || exact.frame.sourceGeneration != expectedSource ||
            observedSurface < 0 || observedSurface != expectedSurface || exact.frame.geometry != geometry ||
            observed.attachmentEpoch < 0 || observed.windowEpoch < 0 ||
            !observed.cropX.isFinite() || !observed.cropY.isFinite() || observed.cropX <= 0 || observed.cropY <= 0)
            return clear()
        val measured = exact.frame.scale ?: return clear()
        if (!measured.lengthPixels.isFinite() || measured.lengthPixels <= 0 || !measured.distanceMetres.isFinite() || measured.distanceMetres <= 0)
            return clear()
        val values = observed.matrix
        for (value in values) if (!value.isFinite()) return clear()
        if (values[6] != 0f || values[7] != 0f || values[8] != 1f) return clear()
        // Actual matrix AFTER inverse static crop. The reference distance was measured in
        // visible Surface pixels, so multiplying by the padded crop itself would be wrong.
        val a = values[0] / observed.cropX; val b = values[1] / observed.cropY
        val c = values[3] / observed.cropX; val d = values[4] / observed.cropY
        val x = hypot(a, c); val y = hypot(b, d); val determinant = a * d - b * c
        // Float32 matrix roundoff only; not a rendering/reference-image tolerance.
        if (!x.isFinite() || !y.isFinite() || x <= 0 || y <= 0 || !determinant.isFinite() || determinant <= 0 ||
            abs(x - y) > max(x, y) * SIMILARITY_EPSILON || abs(a * b + c * d) > x * y * SIMILARITY_EPSILON)
            return clear()
        if (buffer === exact && presenter === observed.presenterOwner && texture === observed.textureOwner &&
            surface === observed.surfaceOwner && producerGeneration == observed.producerGeneration &&
            surfaceGeneration == observedSurface && attachmentEpoch == observed.attachmentEpoch && windowEpoch == observed.windowEpoch &&
            frontSlot == observed.frontSlot && unit == distanceUnit && cropX == observed.cropX && cropY == observed.cropY &&
            matrix.contentEquals(values)) return projection
        val length = (measured.lengthPixels * x).toFloat()
        if (!length.isFinite() || length <= 0) return clear()
        val viewport = ViewportSnapshot(++visualRevision, geometry.surfaceWidth, geometry.surfaceHeight,
            "VISUAL:$visualRevision:$expectedSource:$observedSurface:${exact.generation}:${exact.stamp}")
        val bar = scaleBarForUnit(length, measured.distanceMetres, distanceUnit, viewport) ?: return clear()
        buffer = exact; presenter = observed.presenterOwner; texture = observed.textureOwner; surface = observed.surfaceOwner
        producerGeneration = observed.producerGeneration; surfaceGeneration = observedSurface
        attachmentEpoch = observed.attachmentEpoch; windowEpoch = observed.windowEpoch; frontSlot = observed.frontSlot
        unit = distanceUnit; cropX = observed.cropX; cropY = observed.cropY; values.copyInto(matrix)
        return VisualChartScale(viewport, bar, exact.stamp, exact.cameraRevision, expectedSource, observedSurface, exact.generation, exact.stage)
            .also { projection = it }
    }

    companion object { private val SIMILARITY_EPSILON = 16.0 * Math.ulp(1.0f) }
}
