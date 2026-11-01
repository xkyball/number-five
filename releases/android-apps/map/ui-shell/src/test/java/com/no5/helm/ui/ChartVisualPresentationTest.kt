package com.no5.helm.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import com.no5.helm.chart.*
import com.no5.preparation.ui.DistanceUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Controlled real Presenter/Adapter reads; no GPU, Window-buffer or display-time assertion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ChartVisualPresentationTest {
    private val activity = Robolectric.buildActivity(No5ImmersiveActivity::class.java)
    private val geometry = ChartSurfaceGeometry.fit(1000, 600)
    private val camera = ChartViewport(metresToPixels = .12, width = 1000, height = 600)
    private lateinit var chart: ChartSurfaceView
    private lateinit var presenter: ChartPresentationView
    private lateinit var adapter: NativeRendererAdapter
    private lateinit var texture: TaggedTexture
    private lateinit var surface: Surface
    private val engine = ReadEngine()
    private var acquired = 0
    private var acknowledged = 0

    private class TaggedTexture : SurfaceTexture(0) {
        var stamp = 101L
        var changeAfterNextReadTo: Long? = null
        override fun getTimestamp(): Long {
            val observed = stamp
            changeAfterNextReadTo?.let { stamp = it; changeAfterNextReadTo = null }
            return observed
        }
    }
    private fun packet(stamp: Long = 101, complete: Boolean = true, distance: Double = 1000.0) =
        ChartBufferSnapshot(stamp, 7, stamp - 1, ChartFrame(stamp, "query:$stamp", geometry, camera,
            if (complete) ChartScale(120.0, distance) else null, 5, 11), complete, presentationSlot = -1)

    @Before fun setup() {
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup().visible().windowFocusChanged(true)
        chart = ChartSurfaceView(activity.get())
        presenter = ChartPresentationView(activity.get(), chart, pairingSupported = true)
        activity.get().setContentView(presenter, ViewGroup.LayoutParams(1000, 600))
        presenter.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        presenter.layout(0, 0, 1000, 600)
        // The controlled software ViewRoot has no real WindowManager visibility dispatch.
        // Set its attached framework field, then deliver the same callback; product guards stay intact.
        windowVisibility(View.VISIBLE)
        chart.bufferTransformGeneration = 7
        chart.viewport = camera
        texture = TaggedTexture(); chart.setSurfaceTexture(texture)
        surface = Surface(texture)
        presenter.available(0, surface, 1000, 600, texture, 2)
        presenter.configure(ChartBufferTransformMode.OFF, 7, geometry)
        engine.packets[101] = packet()
        chart.onDiagnosticBuffer = engine::chartBufferDiagnostics
        chart.onTaggedBuffer = engine::chartBufferDiagnostics
        presenter.onAcquired = { _, _ -> acquired++; true }
        presenter.onPresented = { _, _ -> acknowledged++ }
        presenter.setSourceAuthority(5)
        presenter.observeSourceBuffer(101)
        adapter = NativeRendererAdapter(engine, camera)
        set("view", chart); set("presentationView", presenter); set("started", true); set("surfaceReady", true)
        set("attachmentOwner", Any()); set("attached", surface); set("surfaceGeneration", 9L)
        presenter.onVisiblePresentationChanged = { refresh() }
        adapter.distanceUnit = DistanceUnit.KILOMETRES
        refresh()
        val observation = VisualPresentationSnapshot()
        assertTrue("Fixture must expose its exact visible texture: parent=${presenter.width}x${presenter.height}, shown=${presenter.isShown}, window=${presenter.windowVisibility}, alpha=${presenter.alpha}; child=${chart.width}x${chart.height}, shown=${chart.isShown}, alpha=${chart.alpha}; released=${texture.isReleased}; ${presenter.readWindowIdentity()}",
            presenter.readVisualPresentation(observation))
    }
    @After fun teardown() {
        presenter.onVisiblePresentationChanged = {}
        activity.pause().stop().destroy()
        surface.release()
        if (!texture.isReleased) texture.release()
    }
    private fun set(name: String, value: Any) = NativeRendererAdapter::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.set(adapter, value)
    }
    private fun refresh() = NativeRendererAdapter::class.java.getDeclaredMethod("refreshVisualScale").let {
        it.isAccessible = true; it.invoke(adapter)
    }
    private fun windowVisibility(value: Int) {
        val attached = ReflectionHelpers.getField<Any>(presenter, "mAttachInfo")
        ReflectionHelpers.setField(attached, "mWindowVisibility", value)
        presenter.dispatchWindowVisibilityChanged(value)
    }
    private fun acquireMeasuredFront(stage: ChartContentStage = ChartContentStage.FULL,
        complete: Boolean = true): ChartBufferSnapshot {
        val mode = ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE
        val image = packet(complete = complete).copy(presentationSlot = 0, stage = stage)
        engine.packets[101] = image
        engine.status.value = engine.status.value.copy(bufferTransformMode = mode)
        chart.bufferTransformMode = mode
        presenter.configure(mode, 7, geometry)
        assertTrue(presenter.sample(0, 101, "CONTROLLED_FRONT_ACQUIRE"))
        presenter.acknowledgeCommittedBuffer(0, 101, 7)
        refresh()
        return image
    }
    private fun revokeProducerEpoch(generation: Long = 8) {
        engine.packets.clear()
        engine.status.value = engine.status.value.copy(bufferTransformGeneration = generation,
            confirmedFrame = null, viewport = camera.copy(latitude = .01))
        chart.bufferTransformGeneration = generation
        presenter.configure(chart.bufferTransformMode, generation, geometry)
        refresh()
    }

    @Test fun pendingQueryCameraDoesNotEraseTheStillVisibleExactFrontScaleOrAcknowledgeAnything() {
        assertTrue(presenter.isAttachedToWindow)
        assertNotNull(adapter.visualScaleProjection)
        val first = adapter.visualScaleProjection
        engine.status.value = engine.status.value.copy(confirmedFrame = null, viewport = camera.copy(latitude = .01))
        chart.viewport = camera.copy(latitude = .01)
        refresh()
        assertSame(first, adapter.visualScaleProjection)
        assertEquals("1 km", adapter.visualScaleProjection!!.scale.label)
        assertEquals(0, acquired); assertEquals(0, acknowledged)
        assertEquals(.01, chart.viewport.latitude, 0.0)
    }
    @Test fun actualCameraGenerationResetKeepsTheExactHeldFrontAfterItsRegistryEntryIsRevoked() {
        val mode = ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE
        val front = packet().copy(presentationSlot = 0)
        engine.packets[101] = front
        chart.bufferTransformMode = mode
        presenter.configure(mode, 7, geometry)
        assertTrue(presenter.sample(0, 101, "CONTROLLED_FRONT_ACQUIRE"))
        presenter.acknowledgeCommittedBuffer(0, 101, 7)
        refresh()
        assertEquals(101L, adapter.visualScaleProjection!!.bufferStamp)
        val beforeAcquired = acquired; val beforeAcknowledged = acknowledged

        // The real non-preview camera path invalidates only the producer/query registry.
        // configure(gen8) deliberately keeps this same acquired Texture front visible.
        engine.packets.clear()
        engine.status.value = engine.status.value.copy(bufferTransformMode = mode,
            bufferTransformGeneration = 8, confirmedFrame = null, viewport = camera.copy(latitude = .01))
        chart.bufferTransformGeneration = 8
        presenter.configure(mode, 8, geometry)
        assertSame(front, presenter.slots.visible)
        assertEquals(101L, texture.timestamp)
        assertNull(engine.chartBufferDiagnostics(101))
        refresh()
        val visible = adapter.visualScaleProjection
        assertNotNull("Same physically held exact front must retain its measured VISUAL scale across producer epoch reset", visible)
        assertEquals(101L, visible!!.bufferStamp)
        assertEquals(7L, visible.bufferGeneration)
        assertEquals("1 km", visible.scale.label)
        assertTrue(visible.viewport.cameraToken.startsWith("VISUAL:"))
        assertEquals(beforeAcquired, acquired); assertEquals(beforeAcknowledged, acknowledged)
        assertNull(engine.status.value.confirmedFrame)
    }
    @Test fun heldFrontSurvivesRepeatedProducerEpochsAndUnitChangesWithoutAQueryOrNewAck() {
        val front = acquireMeasuredFront()
        val beforeAcquired = acquired; val beforeAcknowledged = acknowledged
        for (generation in 8L..12L) {
            revokeProducerEpoch(generation)
            assertSame(front, presenter.slots.visible)
            assertEquals(101L, adapter.visualScaleProjection!!.bufferStamp)
            assertEquals(7L, adapter.visualScaleProjection!!.bufferGeneration)
            assertNull(engine.chartBufferDiagnostics(101))
        }
        adapter.distanceUnit = DistanceUnit.NAUTICAL_MILES
        assertEquals("0,5 sm", adapter.visualScaleProjection!!.scale.label)
        assertEquals(beforeAcquired, acquired); assertEquals(beforeAcknowledged, acknowledged)
        assertNull(engine.status.value.confirmedFrame)
    }
    @Test fun actualUnknownFrontStampRetiresItsPinAndCannotResurrectItByReturningToTheOldStamp() {
        acquireMeasuredFront(); revokeProducerEpoch()
        assertNotNull(adapter.visualScaleProjection)
        texture.stamp = 102
        presenter.updated(0, texture)
        assertNull(adapter.visualScaleProjection)
        texture.stamp = 101
        refresh()
        assertNull("Unknown new image permanently retired the previous ownership pin", adapter.visualScaleProjection)
    }
    @Test fun aTimestampChangeDuringTheSecondStabilityReadAlsoPermanentlyRetiresThePin() {
        acquireMeasuredFront(); revokeProducerEpoch()
        assertNotNull(adapter.visualScaleProjection)
        texture.changeAfterNextReadTo = 102
        refresh()
        assertNull(adapter.visualScaleProjection)
        assertEquals(102L, texture.stamp)
        texture.stamp = 101
        refresh()
        assertNull("An unstable double read cannot later resurrect its retired old image", adapter.visualScaleProjection)
    }
    @Test fun heldFrontIsRetiredByAnAttachmentEpochBoundary() {
        acquireMeasuredFront(); revokeProducerEpoch()
        assertNotNull(adapter.visualScaleProjection)
        presenter.beginAttachmentDiagnostics()
        assertNull(adapter.visualScaleProjection)
        assertNull(engine.chartBufferDiagnostics(101))
    }
    @Test fun heldFrontIsRetiredAcrossWindowHideAndReturnWithoutFreshImageEvidence() {
        acquireMeasuredFront(); revokeProducerEpoch()
        windowVisibility(View.INVISIBLE)
        refresh(); assertNull(adapter.visualScaleProjection)
        windowVisibility(View.VISIBLE)
        refresh(); assertNull(adapter.visualScaleProjection)
    }
    @Test fun heldFrontIsRetiredWhenItsProducerOwnerChangesEvenWithTheSameTextureAndStamp() {
        acquireMeasuredFront(); revokeProducerEpoch()
        assertNotNull(adapter.visualScaleProjection)
        presenter.available(0, surface, 1000, 600, texture, 3)
        refresh(); assertNull(adapter.visualScaleProjection)
        assertEquals(101L, texture.timestamp)
    }
    @Test fun aRenderingModeChangeCannotReuseTheHeldFrontPin() {
        acquireMeasuredFront(); revokeProducerEpoch()
        chart.bufferTransformMode = ChartBufferTransformMode.CONTROL
        presenter.configure(ChartBufferTransformMode.CONTROL, 8, geometry)
        refresh(); assertNull(adapter.visualScaleProjection)
    }
    @Test fun aSourceReplacementCannotReuseTheHeldFrontPin() {
        acquireMeasuredFront(); revokeProducerEpoch()
        presenter.setSourceAuthority(6)
        engine.status.value = engine.status.value.copy(chartSourceGeneration = 6)
        refresh(); assertNull(adapter.visualScaleProjection)
        assertNull(presenter.slots.visible)
    }
    @Test fun aSurfaceOwnerReplacementWithTheSameTextureAndStampCannotBorrowTheOldPin() {
        acquireMeasuredFront(); revokeProducerEpoch()
        val replacement = Surface(texture)
        try {
            set("attached", replacement)
            presenter.available(0, replacement, 1000, 600, texture, 2)
            refresh(); assertNull(adapter.visualScaleProjection)
            assertEquals(101L, texture.timestamp)
        } finally { replacement.release() }
    }
    @Test fun aTextureOwnerReplacementWithTheSameStampCannotBorrowTheOldPin() {
        acquireMeasuredFront(); revokeProducerEpoch()
        val replacementTexture = TaggedTexture()
        val replacementSurface = Surface(replacementTexture)
        try {
            chart.setSurfaceTexture(replacementTexture)
            set("attached", replacementSurface)
            presenter.available(0, replacementSurface, 1000, 600, replacementTexture, 3)
            refresh(); assertNull(adapter.visualScaleProjection)
            assertEquals(101L, replacementTexture.timestamp)
        } finally {
            chart.setSurfaceTexture(texture)
            replacementSurface.release()
            if (!replacementTexture.isReleased) replacementTexture.release()
        }
    }
    @Test fun aCompletedMeasuredReuseFrontKeepsItsHonestPartialStageAcrossProducerReset() {
        acquireMeasuredFront(ChartContentStage.REUSED_DETAILS_WITH_BASE)
        assertEquals(ChartContentStage.REUSED_DETAILS_WITH_BASE, adapter.visualScaleProjection!!.contentStage)
        revokeProducerEpoch()
        assertEquals(101L, adapter.visualScaleProjection!!.bufferStamp)
        assertEquals(ChartContentStage.REUSED_DETAILS_WITH_BASE, adapter.visualScaleProjection!!.contentStage)
        assertEquals("1 km", adapter.visualScaleProjection!!.scale.label)
        assertNull(engine.status.value.confirmedFrame)
    }
    @Test fun anIncompleteOrUnmeasuredRealFrontStaysUnavailableAfterItsRegistryIsRevoked() {
        val incomplete = acquireMeasuredFront(ChartContentStage.REUSED_DETAILS_WITH_BASE, complete = false)
        assertNull(adapter.visualScaleProjection)
        revokeProducerEpoch()
        assertSame(incomplete, presenter.slots.visible)
        assertNull(adapter.visualScaleProjection)
    }
    @Test fun exactLateCompletionMetadataRemainsInTheOwnedSlotBeforeTheNextProducerReset() {
        val acquiredImage = acquireMeasuredFront(complete = false)
        assertNull(adapter.visualScaleProjection)
        val completed = acquiredImage.copy(producerCompleted = true,
            frame = acquiredImage.frame.copy(scale = ChartScale(120.0, 500.0)))
        engine.packets[101] = completed
        refresh()
        assertSame(completed, presenter.slots.visible)
        assertEquals("500 m", adapter.visualScaleProjection!!.scale.label)
        revokeProducerEpoch()
        assertSame(completed, presenter.slots.visible)
        assertEquals("500 m", adapter.visualScaleProjection!!.scale.label)
    }
    @Test fun unknownUpdatedTextureClearsThenLateMetadataRevalidatesTheSameStamp() {
        assertNotNull(adapter.visualScaleProjection)
        texture.stamp = 102
        presenter.updated(0, texture)
        assertNull(adapter.visualScaleProjection)
        engine.packets[102] = packet(102, complete = false)
        refresh()
        assertNull(adapter.visualScaleProjection)
        engine.packets[102] = packet(102, distance = 500.0)
        // Same endpoint as the existing source collector; no second Texture callback or poll.
        refresh()
        assertEquals(102L, adapter.visualScaleProjection!!.bufferStamp)
        assertEquals("500 m", adapter.visualScaleProjection!!.scale.label)
        assertEquals(0, acquired); assertEquals(0, acknowledged)
    }
    @Test fun unitChangesWithoutANativeFrameAndSourceOrSurfaceReplacementFailClosed() {
        val stamp = adapter.visualScaleProjection!!.bufferStamp
        adapter.distanceUnit = DistanceUnit.NAUTICAL_MILES
        assertEquals("0,5 sm", adapter.visualScaleProjection!!.scale.label)
        adapter.distanceUnit = DistanceUnit.KILOMETRES
        assertEquals("1 km", adapter.visualScaleProjection!!.scale.label)
        assertEquals(stamp, adapter.visualScaleProjection!!.bufferStamp)
        windowVisibility(View.INVISIBLE)
        refresh(); assertNull(adapter.visualScaleProjection)
        windowVisibility(View.VISIBLE)
        refresh(); assertEquals(stamp, adapter.visualScaleProjection!!.bufferStamp)
        engine.status.value = engine.status.value.copy(surfaceGeneration = 10)
        refresh(); assertNull(adapter.visualScaleProjection)
        engine.status.value = engine.status.value.copy(surfaceGeneration = 9, chartSourceGeneration = 6)
        refresh(); assertNull(adapter.visualScaleProjection)
        assertEquals(0, acquired); assertEquals(0, acknowledged)
    }

    private inner class ReadEngine : ChartEngine {
        val packets = mutableMapOf<Long, ChartBufferSnapshot>()
        override val status = MutableStateFlow(ChartEngineStatus(initialized = true, chartLoaded = true,
            surfaceAttached = true, viewport = camera, chartSourceGeneration = 5, surfaceGeneration = 9, bufferTransformGeneration = 7))
        override fun chartBufferDiagnostics(timestampNanos: Long) = packets[timestampNanos]
        override suspend fun initialize(config: ChartEngineConfig) = EngineResult(true)
        override fun attachSurface(surface: Surface, width: Int, height: Int) = Unit
        override fun detachSurface() = Unit
        override fun setViewport(viewport: ChartViewport) = Unit
        override suspend fun loadChartSet(id: String, paths: List<String>) = EngineResult(true)
        override suspend fun queryFeature(point: ScreenPoint) = emptyList<ChartFeature>()
        override fun setS52Settings(settings: S52Settings) = Unit
        override fun setOwnship(state: OwnshipProjection) = Unit
        override fun setAisTargets(targets: List<AisProjection>) = Unit
        override fun setRouteProjection(route: RouteProjection?) = Unit
        override fun setWaypointProjection(waypoints: List<WaypointProjection>) = Unit
        override fun close() = Unit
    }
}
