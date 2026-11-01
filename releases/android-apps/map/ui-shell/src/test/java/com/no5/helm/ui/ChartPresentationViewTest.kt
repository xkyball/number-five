package com.no5.helm.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.app.Activity
import android.os.Looper
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.Surface
import android.widget.FrameLayout
import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Controlled API29 callbacks and explicit commit; no GPU or physical-display assertion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ChartPresentationViewTest {
    private val home = ChartViewport(42.36, -71.04, .15, width = 1000, height = 600)
    private lateinit var chart: ChartSurfaceView
    private lateinit var presentation: ChartPresentationView
    private val packets = mutableMapOf<Long, ChartBufferSnapshot>()
    private val committed = mutableListOf<Pair<Int, Long>>()
    private var revision = 10L
    private class TaggedTexture : SurfaceTexture(0) {
        var tag = 0L
        override fun getTimestamp() = tag
        var defaultWidth = 0
        var defaultHeight = 0
        var defaultChanges = 0
        override fun setDefaultBufferSize(width: Int, height: Int) {
            defaultWidth=width;defaultHeight=height;defaultChanges++
            super.setDefaultBufferSize(width,height)
        }
    }
    private fun packet(slot: Int, stamp: Long, camera: ChartViewport = home, revision: Long = stamp) =
        ChartBufferSnapshot(stamp, 7, revision, ChartFrame(stamp, "slot:$slot:$stamp", ChartSurfaceGeometry.fit(1000, 600), camera),
            producerCompleted = true, presentationSlot = slot)

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        chart = ChartSurfaceView(context)
        presentation = ChartPresentationView(context, chart)
        presentation.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        presentation.layout(0, 0, 1000, 600)
        chart.viewport = home
        chart.bufferTransformMode = ChartBufferTransformMode.TRANSFORM_WITH_RENDER
        chart.bufferTransformGeneration = 7
        chart.onTaggedBuffer = packets::get
        chart.onTouchBegin = { true }; chart.onTouchViewport = { revision++ }; chart.cameraRevision = { revision }
        presentation.onPresented = { slot, stamp -> committed += slot to stamp }
        packets[10] = packet(1, 10)
        assertTrue(presentation.sample(1, 10, "CONTROLLED_ACQUIRE"))
        presentation.acknowledgeCommittedBuffer(1, 10, 7)
        committed.clear()
    }

    private fun texture(slot: Int): TextureView =
        (0 until presentation.childCount).map { presentation.getChildAt(it) }.filterIsInstance<FrameLayout>()
            .flatMap { wrapper -> (0 until wrapper.childCount).map { wrapper.getChildAt(it) } }
            .filterIsInstance<TextureView>().first { (it === chart) == (slot == 0) }
    private fun map(slot: Int, x: Float): Float {
        val matrix = texture(slot).getTransform(Matrix())
        return floatArrayOf(x, 300f).also { matrix.mapPoints(it) }[0]
    }
    private fun send(action: Int, time: Long, x: Float) {
        MotionEvent.obtain(1000, time, action, x, 300f, 0).let {
            try { assertTrue(presentation.dispatchTouchEvent(it)) } finally { it.recycle() }
        }
    }

    @Test fun worldCompositionRetainsOneViewAndHidesOldSlotsThroughTransparentHoles() {
        val wrappers=(0 until presentation.childCount).map{presentation.getChildAt(it)}.filterIsInstance<FrameLayout>()
        presentation.setWorldUnderlay(true,"default")
        val world=(0 until presentation.childCount).map{presentation.getChildAt(it)}.filterIsInstance<WorldUnderlayView>().single()
        assertFalse(world.isClickable);assertEquals(0,presentation.indexOfChild(world))
        assertEquals(1,wrappers.count{it.alpha==1f});assertEquals(1,wrappers.count{it.alpha==0f})
        presentation.setSourceAuthority(99)
        assertTrue(wrappers.all{it.alpha==0f});assertEquals(View.VISIBLE,world.visibility)
        assertFalse(presentation.sample(1,10,"OLD_SOURCE_MUST_NOT_SHOW"))
        val fresh=packet(0,20).let{it.copy(frame=it.frame.copy(sourceGeneration=99))};packets[20]=fresh
        assertTrue(presentation.sample(0,20,"NEW_SOURCE"))
        assertEquals(1,wrappers.count{it.alpha==1f})
        for(style in listOf("lagoon-sculpt","redwater-focus","default"))presentation.setWorldUnderlay(true,style)
        assertSame(world,(0 until presentation.childCount).map{presentation.getChildAt(it)}.filterIsInstance<WorldUnderlayView>().single())
        presentation.setWorldUnderlay(false,"default");assertEquals(View.GONE,world.visibility)
        assertTrue(wrappers.all{it.alpha==1f})
    }
    @Test fun knownTimestampDuringTextureDrawCannotPromoteOrAcknowledgeThatTraversal() {
        val target = transformChartViewport(home, pan = ScreenPoint(150.0, 0.0))
        presentation.updateCamera(target)
        assertEquals(650f, map(1, 500f), .001f)
        packets[11] = packet(0, 11, target)
        val texture = TaggedTexture().apply { tag = 11 }
        try {
            presentation.updated(0, texture)
            assertEquals(1, presentation.slots.front)
            assertTrue(committed.isEmpty())
            presentation.pollAcquisitions()
            assertEquals(0, presentation.slots.front)
            assertEquals(650f, map(0, 650f), .001f)
            assertTrue("Acquisition alone must not free the previous front", committed.isEmpty())
            presentation.acknowledgeCommittedBuffer(0, 11, 7)
            assertEquals(listOf(0 to 11L), committed)
        } finally { texture.release() }
    }

    @Test fun unknownHiddenCallbackKeepsGestureAndItsCanonicalCamera() {
        var finishes = 0; chart.onTouchFinish = { finishes++ }
        send(MotionEvent.ACTION_DOWN, 1000, 500f); send(MotionEvent.ACTION_MOVE, 1020, 650f)
        val target = chart.viewport
        val texture = TaggedTexture().apply { tag = 999 }
        try {
            presentation.updated(0, texture); presentation.pollAcquisitions()
            assertTrue(chart.isTouchActive); assertEquals(0, finishes)
            assertEquals(target, chart.viewport); assertEquals(1, presentation.slots.front)
            assertEquals(10L, chart.bufferTransformBasis!!.stamp)
            send(MotionEvent.ACTION_CANCEL, 1040, 900f)
            assertEquals(1, finishes); assertEquals(target, chart.viewport)
        } finally { texture.release() }
    }

    @Test fun rejectedAcquisitionHandshakeLeavesTheFrontImmutable() {
        packets[11] = packet(0, 11)
        presentation.onAcquired = { _, _ -> false }
        assertFalse(presentation.sample(0, 11, "CONTROLLED_ACQUIRE"))
        assertEquals(1, presentation.slots.front)
        assertEquals(10L, chart.bufferTransformBasis!!.stamp)
        assertTrue(committed.isEmpty())
    }

    @Test fun newGenerationRetainsAcquiredFrontUntilItsExactCommitAndNeverAcknowledgesStaleTwice() {
        packets[11] = packet(0, 11)
        presentation.sample(0, 11, "CONTROLLED_ACQUIRE")
        chart.bufferTransformGeneration = 8
        assertEquals(0, presentation.slots.front)
        assertEquals(11L, presentation.slots.visible!!.stamp)
        presentation.acknowledgeCommittedBuffer(0, 11, 7)
        presentation.acknowledgeCommittedBuffer(0, 11, 7)
        assertEquals(listOf(0 to 11L), committed)
        assertFalse(presentation.sample(1, 10, "STALE_ACQUIRE"))
    }

    @Test fun contextReattachmentResetsProducerFrontAndCoversImagesUntilAReplacementIsAcquired() {
        assertEquals(1, presentation.slots.front)
        presentation.resetForSurfaceAttachment()
        assertEquals(0, presentation.slots.front)
        assertNull(presentation.slots.visible)
        assertNull(chart.bufferTransformBasis)
        assertFalse("An opaque cover is above both producer wrappers", presentation.getChildAt(presentation.childCount - 1) is FrameLayout)
        packets[12] = packet(1, 12)
        assertTrue(presentation.sample(1, 12, "CONTROLLED_ACQUIRE"))
        assertTrue(presentation.getChildAt(presentation.childCount - 1) is FrameLayout)
        assertEquals(1, presentation.slots.front)
    }

    @Test fun cancellingOwnershipKeepsLatestIntentWhileTheVisibleImageIsStillOlder() {
        send(MotionEvent.ACTION_DOWN, 1000, 500f); send(MotionEvent.ACTION_MOVE, 1020, 650f)
        val target = chart.viewport
        chart.cancelGesture()
        assertFalse(chart.isTouchActive)
        assertEquals(home, presentation.slots.visible!!.frame.viewport)
        assertEquals(target, chart.viewport)
        assertEquals(target, chart.bufferTransformTarget)
    }

    @Test fun alreadyAcquiredImageBeforeGenerationDeliveryNeedsNoSecondProducerCallback() {
        chart.bufferTransformGeneration = 8
        packets[11] = packet(0, 11).copy(generation = 8)
        val texture = TaggedTexture().apply { tag = 11 }
        try {
            // The real reset obtains these candidates from each fixed TextureView.surfaceTexture.
            presentation.scheduleExistingAcquisitions(listOf(texture, null))
            presentation.pollAcquisitions()
            assertEquals(0, presentation.slots.front)
            assertEquals(11L, chart.bufferTransformBasis!!.stamp)
            presentation.acknowledgeCommittedBuffer(0, 11, 8)
            assertEquals(listOf(0 to 11L), committed)
        } finally { texture.release() }
    }

    @Test fun contextResetCanRecoverExistingAcquiredTimestampWithoutAnotherUpdateCallback() {
        presentation.resetForSurfaceAttachment()
        packets[12] = packet(1, 12)
        val texture = TaggedTexture().apply { tag = 12 }
        try {
            presentation.scheduleExistingAcquisitions(listOf(null, texture))
            presentation.pollAcquisitions()
            assertEquals(1, presentation.slots.front)
            assertEquals(12L, chart.bufferTransformBasis!!.stamp)
        } finally { texture.release() }
    }

    @Test fun sourceReplacementCoversOldPixelsUntilAnExactNewSourceBufferArrives() {
        val camera = chart.viewport
        presentation.setSourceAuthority(9)
        assertNull(presentation.slots.visible)
        assertNull(chart.bufferTransformBasis)
        assertEquals(camera, chart.viewport)
        assertFalse(presentation.sample(1, 10, "OLD_SOURCE"))
        assertFalse(presentation.getChildAt(presentation.childCount - 1) is FrameLayout)
        packets[12] = packet(0, 12).let { it.copy(frame = it.frame.copy(sourceGeneration = 9)) }
        assertTrue(presentation.sample(0, 12, "NEW_SOURCE"))
        assertEquals(12L, chart.bufferTransformBasis!!.stamp)
        assertTrue(presentation.getChildAt(presentation.childCount - 1) is FrameLayout)
        presentation.acknowledgeCommittedBuffer(1, 10, 7)
        assertTrue(committed.isEmpty())
    }

    @Test fun repeatedSourceAuthorityPreservesTheFrontAndLatestCameraIntent() {
        presentation.setSourceAuthority(0)
        assertTrue(presentation.sample(1, 10, "CURRENT_SOURCE"))
        val target = transformChartViewport(home, pan = ScreenPoint(150.0, 0.0))
        presentation.updateCamera(target)
        presentation.setSourceAuthority(0)
        assertEquals(10L, presentation.slots.visible!!.stamp)
        assertEquals(650f, map(1, 500f), .001f)
        assertTrue(presentation.getChildAt(presentation.childCount - 1) is FrameLayout)
    }

    @Test fun plainRenderingRequiresCompletedMatchingSourceAndSurfaceBeforeRevealing() {
        chart.bufferTransformMode = ChartBufferTransformMode.OFF
        presentation.setSourceAuthority(9)
        val exact = packet(-1, 20).let { it.copy(frame = it.frame.copy(sourceGeneration = 9)) }
        packets[20] = exact.copy(producerCompleted = false)
        assertFalse(presentation.sample(0, 20, "INCOMPLETE"))
        packets[20] = exact.copy(generation = 6)
        assertFalse(presentation.sample(0, 20, "OLD_SURFACE"))
        packets[20] = exact.copy(frame = exact.frame.copy(sourceGeneration = 8))
        assertFalse(presentation.sample(0, 20, "OLD_SOURCE"))
        assertFalse(presentation.getChildAt(presentation.childCount - 1) is FrameLayout)
        packets[20] = exact
        assertTrue(presentation.sample(0, 20, "EXACT_PLAIN_SOURCE"))
        assertTrue(presentation.getChildAt(presentation.childCount - 1) is FrameLayout)
        assertNull(presentation.slots.visible)
        assertTrue(committed.isEmpty())
    }

    @Test fun lateDestroyedSlotKeepsReplacementSurfacesAndReleasesOnlyItsOriginalProducer() {
        val old = TaggedTexture();val current = TaggedTexture();val other = TaggedTexture()
        val listener = chart.surfaceTextureListener!!
        val otherListener = texture(1).surfaceTextureListener!!
        val pending = mutableListOf<() -> Unit>();val released = mutableListOf<ChartSurfaceLoan>()
        val oldOwner = Any();val newOwner = Any()
        presentation.onOwnedSurfaceRelease = { loan,completion -> released += loan;pending += completion }
        try {
            listener.onSurfaceTextureAvailable(old,1000,600)
            val oldSurface = chart.renderSurface
            otherListener.onSurfaceTextureAvailable(other,1000,600)
            presentation.bindAttachmentOwner(requireNotNull(oldSurface),presentation.availableSurfaces()!!.second,oldOwner)
            listener.onSurfaceTextureAvailable(current,1000,600)
            val exact = requireNotNull(presentation.availableSurfaces())
            presentation.bindAttachmentOwner(exact.first,exact.second,newOwner)
            val visible = presentation.slots.visible
            assertFalse(listener.onSurfaceTextureDestroyed(old))
            assertSame(oldSurface,released.single().surface)
            assertSame(oldOwner,released.single().attachmentOwner)
            assertSame(exact.first,presentation.availableSurfaces()!!.first)
            assertSame(exact.second,presentation.availableSurfaces()!!.second)
            assertSame(exact.first,chart.renderSurface)
            assertSame("A stale release must not reset the new front",visible,presentation.slots.visible)
            pending.removeAt(0).invoke()
            assertSame(exact.first,chart.renderSurface)
        } finally {
            listener.onSurfaceTextureDestroyed(current);otherListener.onSurfaceTextureDestroyed(other)
            pending.forEach { it() }
        }
    }

    @Test fun consumerDiagnosticsAreBoundedAndDoNotSelectOrAcknowledgeTheOffModeBuffer() {
        chart.bufferTransformMode = ChartBufferTransformMode.OFF
        var authorityReads = 0;var diagnosticReads = 0;var acquired = 0
        chart.onTaggedBuffer = { authorityReads++;packets[it] }
        chart.onDiagnosticBuffer = { diagnosticReads++;null }
        presentation.onAcquired = { _, _ -> acquired++;true }
        val texture = TaggedTexture().apply { tag = 9012337L }
        val surface = Surface(texture)
        try {
            presentation.available(0,surface,1000,600,texture,1)
            presentation.beginAttachmentDiagnostics()
            repeat(20) { presentation.updated(0,texture) }
            val updates = ChartSurfaceLifecycleTrace.snapshot().count {
                it.contains("event=TEXTURE_UPDATED ") && it.contains("stamp=9012337 ")
            }
            assertEquals(8,updates)
            assertTrue(diagnosticReads>=8)
            assertEquals("Passive lookup must not select the buffer",0,authorityReads)
            assertEquals(0,acquired);assertTrue(committed.isEmpty())
            presentation.beginAttachmentDiagnostics()
            repeat(3) { presentation.updated(0,texture) }
            assertEquals(11,ChartSurfaceLifecycleTrace.snapshot().count {
                it.contains("event=TEXTURE_UPDATED ") && it.contains("stamp=9012337 ")
            })
            assertEquals(0,authorityReads);assertEquals(0,acquired);assertTrue(committed.isEmpty())
        } finally { surface.release();texture.release() }
    }

    /** Controlled software traversal tests the ordering; physical HWUI is a separate acceptance. */
    private fun withVisibleWakeSurface(block: (Surface, TaggedTexture, () -> Unit) -> Unit) {
        chart.bufferTransformMode=ChartBufferTransformMode.OFF
        val controller=Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        val texture=TaggedTexture();val surface=Surface(texture)
        try {
            controller.get().setContentView(presentation, FrameLayout.LayoutParams(1000, 600))
            // This helper explicitly exercises a software consumer traversal, not HWUI.
            // Robolectric attaches the View with mWindowVisibility=GONE and a hardware
            // flag despite the controlled software draw below. Restore both fixture
            // prerequisites before dispatch; the product wake guards remain unchanged.
            val attached = ReflectionHelpers.getField<Any>(presentation, "mAttachInfo")
            ReflectionHelpers.setField(attached, "mWindowVisibility", View.VISIBLE)
            ReflectionHelpers.setField(attached, "mHardwareAccelerated", false)
            presentation.dispatchWindowVisibilityChanged(View.VISIBLE)
            presentation.available(0,surface,1000,600,texture,1)
            assertEquals(View.VISIBLE, presentation.windowVisibility)
            assertTrue(presentation.isAttachedToWindow)
            assertTrue(presentation.isShown)
            assertFalse(presentation.isHardwareAccelerated)
            val traverse={
                presentation.viewTreeObserver.dispatchOnPreDraw()
                presentation.viewTreeObserver.dispatchOnDraw()
                shadowOf(Looper.getMainLooper()).idle()
                Unit
            }
            block(surface,texture,traverse)
        } finally {
            presentation.cancelVisibleFrameWake()
            controller.pause().stop().destroy()
            surface.release();texture.release()
        }
    }

    @Test fun quickVisibleReturnWithoutStopWakesOnlyAfterTraversalAndConflatesDuplicateVisibleEvents() =
        withVisibleWakeSurface { _, _, traverse ->
            val owner=Any();var wakes=0
            presentation.onWindowVisible={ presentation.scheduleVisibleFrameWake(owner) { wakes++ } }
            presentation.beginAttachmentDiagnostics()
            presentation.scheduleVisibleFrameWake(owner) { wakes++ }
            assertEquals(0,wakes);traverse();assertEquals(1,wakes)
            presentation.dispatchWindowVisibilityChanged(View.INVISIBLE)
            presentation.dispatchWindowVisibilityChanged(View.VISIBLE)
            presentation.dispatchWindowVisibilityChanged(View.VISIBLE)
            presentation.scheduleVisibleFrameWake(owner) { wakes++ }
            assertEquals("Visibility alone cannot wake before the consumer traversal",1,wakes)
            traverse();assertEquals(2,wakes)
            presentation.scheduleVisibleFrameWake(owner) { wakes++ };traverse();assertEquals(2,wakes)
            assertEquals("A consumer wake never invents a camera adjustment",home,chart.viewport)
        }

    @Test fun stopReturnCancelsOldWakeAndReusesTheProducerWithANewAttachmentOwner() =
        withVisibleWakeSurface { _, _, traverse ->
            var oldWakes=0;var newWakes=0
            presentation.beginAttachmentDiagnostics()
            presentation.scheduleVisibleFrameWake(Any()) { oldWakes++ }
            // ON_STOP cancels before its asynchronous EGL retirement, including
            // a commit already posted back to the UI queue.
            presentation.viewTreeObserver.dispatchOnPreDraw()
            presentation.viewTreeObserver.dispatchOnDraw()
            presentation.cancelVisibleFrameWake()
            presentation.beginAttachmentDiagnostics()
            presentation.scheduleVisibleFrameWake(Any()) { newWakes++ }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0,oldWakes);assertEquals(0,newWakes)
            traverse();assertEquals(0,oldWakes);assertEquals(1,newWakes)
        }

    @Test fun hiddenOrReplacedProducerCannotRunAnAlreadyPostedVisibleFrameWake() =
        withVisibleWakeSurface { _, _, traverse ->
            var wakes=0
            presentation.beginAttachmentDiagnostics()
            presentation.scheduleVisibleFrameWake(Any()) { wakes++ }
            presentation.viewTreeObserver.dispatchOnPreDraw()
            presentation.viewTreeObserver.dispatchOnDraw()
            presentation.dispatchWindowVisibilityChanged(View.INVISIBLE)
            shadowOf(Looper.getMainLooper()).idle();assertEquals(0,wakes)
            presentation.dispatchWindowVisibilityChanged(View.VISIBLE)
            presentation.scheduleVisibleFrameWake(Any()) { wakes++ }
            presentation.viewTreeObserver.dispatchOnPreDraw()
            presentation.viewTreeObserver.dispatchOnDraw()
            val replacementTexture=TaggedTexture();val replacementSurface=Surface(replacementTexture)
            try {
                presentation.available(0,replacementSurface,1000,600,replacementTexture,2)
                shadowOf(Looper.getMainLooper()).idle();assertEquals(0,wakes)
                presentation.beginAttachmentDiagnostics()
                presentation.scheduleVisibleFrameWake(Any()) { wakes++ }
                traverse();assertEquals(1,wakes)
            } finally { replacementSurface.release();replacementTexture.release() }
        }

    @Test fun retainedPairReappliesReserveDefaultsBeforeEveryAttachWithoutNewAvailability() {
        val firstTexture = TaggedTexture();val secondTexture = TaggedTexture()
        val firstSurface = Surface(firstTexture);val secondSurface = Surface(secondTexture)
        try {
            presentation.available(0,firstSurface,1000,600,firstTexture,1)
            presentation.available(1,secondSurface,1000,600,secondTexture,1)
            val raster = ChartRasterGeometry.reserve(ChartSurfaceGeometry.fit(1000,600))
            assertTrue(presentation.prepareSurfacesForAttachment(firstSurface,secondSurface,1000,600))
            assertEquals(raster.bufferWidth,firstTexture.defaultWidth);assertEquals(raster.bufferHeight,firstTexture.defaultHeight)
            assertEquals(raster.bufferWidth,secondTexture.defaultWidth);assertEquals(raster.bufferHeight,secondTexture.defaultHeight)
            // TextureView.getTextureLayer may reset these on window return even
            // though neither availability nor visible-size callbacks are sent.
            firstTexture.setDefaultBufferSize(1000,600);secondTexture.setDefaultBufferSize(1000,600)
            val firstChanges = firstTexture.defaultChanges;val secondChanges = secondTexture.defaultChanges
            assertTrue(presentation.prepareSurfacesForAttachment(firstSurface,secondSurface,1000,600))
            assertEquals(firstChanges+1,firstTexture.defaultChanges);assertEquals(secondChanges+1,secondTexture.defaultChanges)
            assertEquals(raster.bufferWidth,firstTexture.defaultWidth);assertEquals(raster.bufferHeight,firstTexture.defaultHeight)
            assertEquals(raster.bufferWidth,secondTexture.defaultWidth);assertEquals(raster.bufferHeight,secondTexture.defaultHeight)
            assertSame(firstSurface,presentation.availableSurfaces()!!.first)
            assertSame(secondSurface,presentation.availableSurfaces()!!.second)
            assertFalse("An old geometry must not prepare a current pair",presentation.prepareSurfacesForAttachment(firstSurface,secondSurface,2000,1070))
        } finally { firstSurface.release();secondSurface.release();firstTexture.release();secondTexture.release() }
    }

    @Test fun differentSlotBufferSizesNeverProduceAnEglPairDescriptor() {
        val firstTexture = TaggedTexture(); val secondTexture = TaggedTexture()
        val firstSurface = Surface(firstTexture); val secondSurface = Surface(secondTexture)
        try {
            presentation.available(0, firstSurface, 1000, 600)
            presentation.available(1, secondSurface, 1000, 600)
            assertEquals(1000, presentation.availableSurfaces()!!.width)
            assertEquals(600, presentation.availableSurfaces()!!.height)
            presentation.resized(0, 2000, 1070)
            assertNull(presentation.availableSurfaces())
            presentation.resized(1, 2000, 1070)
            assertEquals(2000, presentation.availableSurfaces()!!.width)
            assertEquals(1070, presentation.availableSurfaces()!!.height)
        } finally { firstSurface.release(); secondSurface.release(); firstTexture.release(); secondTexture.release() }
    }
}
