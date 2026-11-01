package com.no5.helm.ui

import android.content.Context
import android.view.MotionEvent
import android.view.TextureView
import android.view.Surface
import android.graphics.SurfaceTexture
import android.graphics.Matrix
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.util.Log
import com.no5.helm.chart.ChartViewport
import com.no5.helm.chart.ChartSurfaceGeometry
import com.no5.helm.chart.ScreenPoint
import com.no5.helm.chart.ChartMotion
import com.no5.helm.chart.ChartBufferSnapshot
import com.no5.helm.chart.ChartBufferTransformMode
import com.no5.helm.chart.RendererUiSink
import com.no5.helm.chart.RendererUiPhase
import androidx.compose.ui.geometry.Rect
import kotlin.math.pow

/** Input owner; fixed presentation slots keep acquired pixels and their camera basis together. */
internal class ChartSurfaceView(context: Context, private val touchPolicy: ChartTouchPolicy = ChartTouchPolicy.INDEPENDENT,
    flingController: ChartFlingController? = null) : TextureView(context) {
    internal var presentation: ChartPresentationView? = null
        set(value) { field = value; value?.configure(bufferTransformMode, bufferTransformGeneration, geometry) }
    var captureSink: () -> RendererUiSink? = { null }
    var inputSink: () -> com.no5.helm.chart.RendererLiveInputSink? = { null }
    var inputOrigin = com.no5.helm.chart.RendererInputOrigin.PLATFORM_DISPATCH
    var inputEnabled: () -> Boolean = { true }
    internal val touchReplay = RendererTouchReplayController(this)
    private var observedGesture = 0L
    private var observedHistoryIds = emptyList<Long>()
    private var transformRevision = 0L
    fun cancelReplayGesture() { finishTouch(); cancelGesture() }

    private var lastUiSink: RendererUiSink? = null
    private var lastTextureUpdateNanos = 0L
    var onSurfaceReady: (Surface, Int, Int) -> Unit = { _, _, _ -> }
    var onSurfaceRelease: (() -> Unit) -> Unit = { release -> release() }
    var onOwnedSurfaceRelease: ((ChartSurfaceLoan, () -> Unit) -> Unit)? = null
    private val producerLoans = ChartSurfaceLoans()
    private var renderTexture: SurfaceTexture? = null
    var renderSurface: Surface? = null
        private set
    var viewport = ChartViewport()
    var onViewport: (ChartViewport) -> Unit = {}
    var onTap: (ScreenPoint) -> Unit = {}
    var onSurfaceTap: ((ScreenPoint) -> Unit)? = null
    var inspectEnabled = false
    var nativeMotion: ((ChartMotion) -> Unit)? = null
    var nativeCameraBasis: (() -> ChartViewport)? = null
    var onGestureStarted: () -> Unit = {}
    var onPanStarted: () -> Unit = {}
    var onTouchActivity: (Boolean) -> Unit = {}
    private var touchActive=false
    val isTouchActive:Boolean get()=touchActive
    private fun touchActivity(active:Boolean) { if(touchActive!=active){touchActive=active;onTouchActivity(active)} }
    var onTouchBegin: () -> Boolean = { false }
    var onTouchViewport: (ChartViewport) -> Unit = {}
    /** Pure in-memory preflight runs before viewport and reused pixels change. */
    var admitCamera: (ChartViewport, ChartViewport, ChartViewport?, Boolean) -> ChartViewport? = { _, wanted, _, _ -> wanted }
    var onTouchFinish: () -> Unit = {}
    var onFlingStarted: () -> Unit = {}
    var chartNavigationBounds: () -> com.no5.helm.chart.ChartNavigationBounds? = { null }
    var momentumEnabled = false
    var onBufferConsumed: (Long) -> Unit = {}
    var onTaggedBuffer: (Long) -> ChartBufferSnapshot? = { null }
    /** Pure lookup only; diagnostics must never select/ack a camera buffer. */
    var onDiagnosticBuffer: (Long) -> ChartBufferSnapshot? = { null }
    var cameraRevision: () -> Long = { -1 }
    var bufferTransformMode = ChartBufferTransformMode.OFF
        set(value) { if (field != value) {
            val preserve = field != ChartBufferTransformMode.OFF && value != ChartBufferTransformMode.OFF && presentation?.slots?.visible != null
            field = value; resetBufferTransform(preserve)
            isOpaque = value == ChartBufferTransformMode.OFF && presentation?.usePairedPresentation != true && presentation?.worldUnderlayEnabled != true
        } }
    var bufferTransformGeneration = 0L
        set(value) { if (field != value) {
            field = value; resetBufferTransform(bufferTransformMode != ChartBufferTransformMode.OFF && presentation?.slots?.visible != null)
        } }
    private var transformBase: ChartBufferSnapshot? = null
    private var transformTarget: ChartViewport? = null
    private var transformTargetRevision = -1L
    private var lastCanonicalRevision = -1L
    private val transformMatrix = Matrix()
    var onDiagnosticMarker: (ScreenPoint?) -> Unit = {}
    private var diagnosticMarkerBasis: ChartBufferSnapshot? = null
    internal val bufferTransformBasis: ChartBufferSnapshot? get() = transformBase
    internal val bufferTransformTarget: ChartViewport? get() = transformTarget
    private fun resetBufferTransform(preserveBasis: Boolean = false) {
        if (fling.isRunning) finishTouch()
        fling.cancel(); removeCallbacks(animateFling)
        if (!preserveBasis) {
            transformBase = null; transformTarget = null; transformTargetRevision = -1; lastCanonicalRevision = -1
            diagnosticMarkerBasis = null; onDiagnosticMarker(null)
            transformMatrix.reset(); setTransform(transformMatrix)
        }
        presentation?.configure(bufferTransformMode, bufferTransformGeneration, geometry)
        invalidate()
    }
    internal fun clearPresentationBasisForSurfaceAttachment() { resetBufferTransform() }
    internal fun consumeTransformBuffer(buffer: ChartBufferSnapshot?) {
        if (bufferTransformMode == ChartBufferTransformMode.OFF) return
        if (buffer == null || buffer.generation != bufferTransformGeneration || buffer.frame.geometry != geometry) {
            if (presentation != null) return // A delayed hidden-slot sample cannot revoke camera intent.
            // Unknown tags never inherit the latest camera. Finish the owned gesture and fall back to a new full image.
            finishTouch(); tracker.reset(); streamCancelled = true; resetBufferTransform()
            return
        }
        transformBase = buffer
        if (inputSink()?.diagnosticMarkerEnabled == true && diagnosticMarkerBasis == null) diagnosticMarkerBasis = buffer
        if (transformTarget == null && !touchOwned && buffer.cameraRevision >= lastCanonicalRevision) {
            viewport = buffer.frame.viewport; lastCanonicalRevision = buffer.cameraRevision
        }
        applyBufferTransform()
    }
    private fun applyBufferTransform() {
        val observed = inputSink()
        val before = if (observed != null) System.nanoTime() else 0L
        val base = transformBase; val target = transformTarget; val fitted = geometry
        val targetRevision = if (transformTargetRevision >= 0) transformTargetRevision else base?.cameraRevision ?: -1L
        if (base != null && target != null && !touchOwned && base.producerCompleted &&
            base.stage == com.no5.helm.chart.ChartContentStage.FULL && base.cameraRevision == transformTargetRevision) {
            // The consumer has the exact requested camera; rebasing must not publish an older producer camera.
            viewport = target; transformTarget = null; transformTargetRevision = -1
        }
        val affine = if (bufferTransformMode.transforms && base != null && transformTarget != null && fitted != null)
            chartBufferAffine(base.frame.viewport, requireNotNull(transformTarget), fitted) else null
        if (affine == null) transformMatrix.reset() else transformMatrix.setValues(affine.matrixValues())
        presentation?.updateWorldCamera(transformTarget ?: base?.frame?.viewport ?: viewport)
        if (presentation?.usePairedPresentation != true) setTransform(transformMatrix)
        else presentation?.updateCamera(transformTarget ?: base?.frame?.viewport ?: viewport)
        // API29 setTransform dirties the parent. Also dirty this TextureView so
        // its cached display list applies the matrix without a producer update.
        if (observed?.repaintTransforms != false) invalidate()
        val markerBasis = diagnosticMarkerBasis
        if (observed?.diagnosticMarkerEnabled == true && markerBasis != null && fitted != null) {
            val marker = chartBufferAffine(markerBasis.frame.viewport, transformTarget ?: base?.frame?.viewport ?: viewport, fitted)
                ?.map(com.no5.helm.chart.rendererDiagnosticMarkerPoint(fitted))
            onDiagnosticMarker(marker); marker?.let(observed::markerTarget)
        } else onDiagnosticMarker(null)
        observed?.let { sink ->
            val values = FloatArray(9); transformMatrix.getValues(values)
            sink.transform(base, targetRevision, ++transformRevision, values, before, System.nanoTime())
        }
    }
    private var touchOwned=false
    private val fling = flingController ?: ChartFlingController(context)
    private val animateFling = Runnable { advanceFlingFrame() }
    internal fun advanceFlingFrame() {
        if (!touchOwned || !fling.isRunning) return
        fling.advanceFrame(chartNavigationBounds())?.let { camera ->
            val accepted = admitCamera(viewport, camera, null, false)
            if (accepted == null || accepted == viewport) {
                fling.cancel(); removeCallbacks(animateFling)
            } else {
                viewport = accepted; onTouchViewport(accepted); onOutlineCamera(accepted)
                transformTarget = accepted; transformTargetRevision = cameraRevision(); lastCanonicalRevision = transformTargetRevision; applyBufferTransform()
            }
        }
        if (fling.isRunning) postOnAnimation(animateFling) else { finishTouch(); applyBufferTransform() }
    }
    var onOutlineCamera: (ChartViewport) -> Unit = {}
    fun cancelInactivePreview() { if(touchOwned)cancelGesture() }
    fun cancelGesture() {
        fling.cancel();removeCallbacks(animateFling);tracker.reset();streamCancelled=true;touchOwned=false;touchActivity(false)
        if (presentation != null && bufferTransformMode != ChartBufferTransformMode.OFF && transformBase != null) {
            transformTarget = viewport
            transformTargetRevision = lastCanonicalRevision.takeIf { it >= 0 } ?: cameraRevision()
        } else transformTarget = null
        applyBufferTransform();parent?.requestDisallowInterceptTouchEvent(false)
    }
    fun cancelTouchForDock() {
        // The dock owns camera work, but this pointer sequence still lasts until
        // the final UP/CANCEL. Restoring buttons here would create stale hit targets.
        finishTouch();tracker.reset();streamCancelled=true
        parent?.requestDisallowInterceptTouchEvent(touchActive)
    }
    private fun finishTouch() { fling.cancel();removeCallbacks(animateFling);if(touchOwned){touchOwned=false;onTouchFinish()} }
    private var pendingTexture:SurfaceTexture?=null
    private val acknowledge=Runnable { acknowledgeTextureUpdate() }
    internal fun acknowledgeTextureUpdate() {
        val texture = pendingTexture; pendingTexture = null
        texture?.let {
            // API29's update callback schedules HWUI's acquire; its timestamp can still be the old image.
            // Sample again after that traversal, including the final image when no more producer callbacks follow.
            val tag = it.timestamp
            sampleTransformTexture(tag, "NEXT_ANIMATION")
            onBufferConsumed(tag)
        }
    }
    internal fun sampleTransformTexture(tag: Long, phase: String) {
        if (bufferTransformMode == ChartBufferTransformMode.OFF) return
        consumeTransformBuffer(onTaggedBuffer(tag))
        inputSink()?.texture(tag, transformBase?.stamp == tag, phase)
    }
    var blockedRegions: List<Rect> = emptyList()
        set(value) { if (field != value) { val added=value.any{it !in field};field=value;if(added&&touchActive){finishTouch();tracker.reset();streamCancelled=true;parent?.requestDisallowInterceptTouchEvent(touchActive)} } }
    var gestureBlockedRegions:List<Rect>?=null
    private var streamCancelled = false
    private var gestureReported = false
    private val touchConfiguration = ViewConfiguration.get(context)
    // The framework's minimum scale span can reject ordinary map pinches on large/dense
    // displays. Bound this experimental near-coincident-pointer guard by the touch tolerance.
    private val minimumTouchSpan = touchConfiguration.scaledMinimumScalingSpan.toDouble().coerceIn(
        touchConfiguration.scaledTouchSlop * 2.0, touchConfiguration.scaledTouchSlop * 4.0)
    private val tracker = ChartTouchTracker(touchConfiguration.scaledTouchSlop.toDouble(),
        ViewConfiguration.getLongPressTimeout().toLong(),
        minimumTouchSpan, touchPolicy)

    init {
        isClickable = true
        // TextureView participates in the same hardware window composition as
        // header/rail. GLES still renders directly into a native window Surface.
        isOpaque = true
        outlineProvider = ViewOutlineProvider.BOUNDS
        clipToOutline = true
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                // A late duplicate availability must not revive an older producer.
                if (producerLoans.find(texture) != null && renderTexture !== texture) return
                val surface = producerLoans.create(texture) ?: return
                presentation?.configureProducerTexture(texture,width,height) ?: texture.setDefaultBufferSize(width,height)
                renderTexture = texture; renderSurface = surface
                ChartSurfaceLifecycleTrace.record("AVAILABLE", 0, width=width, height=height, paired=presentation?.usePairedPresentation==true)
                resizeSurface(width, height)
                presentation?.available(0, surface, width, height, texture, producerLoans.generation(texture)) ?: onSurfaceReady(surface, width, height)
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                if (width <= 0 || height <= 0 || renderTexture !== texture) return
                presentation?.configureProducerTexture(texture,width,height) ?: texture.setDefaultBufferSize(width,height)
                resizeSurface(width, height)
                renderSurface?.let { surface -> presentation?.available(0, surface, width, height, texture, producerLoans.generation(texture)) ?: onSurfaceReady(surface, width, height) }
            }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val loan = producerLoans.remove(texture) ?: return true
                val current = renderTexture === texture
                ChartSurfaceLifecycleTrace.record("DESTROYED", 0, current=current)
                if (current) {
                    renderTexture = null; renderSurface = null
                    finishTouch();touchActivity(false);tracker.reset();pendingTexture=null;removeCallbacks(acknowledge)
                    resetBufferTransform()
                }
                // The exact retired producer releases only after its EGL loan
                // has ended. A stale callback must not free a replacement Surface.
                val release = { producerLoans.release(texture, loan) }
                val presenter = presentation
                if (presenter != null) presenter.destroyed(0, texture, loan, release)
                else onOwnedSurfaceRelease?.invoke(loan, release) ?: onSurfaceRelease(release)
                return false
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (renderTexture !== texture) return
                presentation?.takeIf { it.usePairedPresentation }?.let { it.updated(0, texture); return }
                // This is a timestamp sample, not an atomic observation of the image HWUI is about to draw.
                sampleTransformTexture(texture.timestamp, "UPDATE_CALLBACK")
                if(pendingTexture==null)postOnAnimation(acknowledge);pendingTexture=texture
                recordTextureUpdate()
            }
        }
    }
    internal fun bindSurfaceOwner(surface: Surface, attachmentOwner: Any) = producerLoans.bind(surface, attachmentOwner)

    internal fun recordTextureUpdate() {
        val sink = captureSink()
        if (sink == null) { lastUiSink = null; lastTextureUpdateNanos = 0L; return }
        val now = System.nanoTime(); sink.textureUpdated()
        if (lastUiSink === sink && lastTextureUpdateNanos > 0)
            sink.record(RendererUiPhase.TEXTURE_UPDATE_INTERVAL, now - lastTextureUpdateNanos)
        lastUiSink = sink; lastTextureUpdateNanos = now
    }
    private var lastLayoutDiagnostic = ""
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val at = IntArray(2); getLocationInWindow(at)
        val visible = android.graphics.Rect(); getGlobalVisibleRect(visible)
        val next = "window=${at[0]},${at[1]} ${width}x$height; visible=$visible; density=${resources.displayMetrics.density}; alpha=$alpha; viewZ=$z; hardwareWindow=$isHardwareAccelerated; composition=TextureView-in-app-window; clipToOutline=$clipToOutline; touchPolicy=$touchPolicy; touchSlop=${touchConfiguration.scaledTouchSlop}; minimumScalingSpan=$minimumTouchSpan; frameworkMinimumScalingSpan=${touchConfiguration.scaledMinimumScalingSpan}"
        if (next != lastLayoutDiagnostic) { lastLayoutDiagnostic = next; Log.i("No5ChartView", next) }
    }

    private var geometry: ChartSurfaceGeometry? = null
    fun resizeSurface(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val next = ChartSurfaceGeometry.fit(width, height)
        if (geometry == next) return
        val previousScale = geometry?.scale ?: 1.0
        geometry = next
        resetBufferTransform()
        finishTouch();touchActivity(false);tracker.reset();streamCancelled=true // Cannot span two coordinate systems.
        viewport = viewport.copy(width = next.frameWidth, height = next.frameHeight,
            metresToPixels = (viewport.metresToPixels * previousScale / next.scale).coerceIn(0.00001, 10.0))
        onViewport(viewport)
    }

    fun acceptViewport(value: ChartViewport, revision: Long = -1L) {
        if (touchOwned) return
        if (presentation?.usePairedPresentation == true && bufferTransformMode != ChartBufferTransformMode.OFF && revision >= 0) {
            if (revision < lastCanonicalRevision || (transformTarget != null && revision <= transformTargetRevision)) return
            val fitted = geometry
            viewport = if (fitted == null) value else value.copy(width = fitted.frameWidth, height = fitted.frameHeight)
            transformTarget = viewport; transformTargetRevision = revision; lastCanonicalRevision = revision
            applyBufferTransform(); return
        }
        if(transformTarget != null)return
        if (bufferTransformMode != ChartBufferTransformMode.OFF && transformBase != null) {
            viewport = requireNotNull(transformBase).frame.viewport; return
        }
        val fitted = geometry
        viewport = if (fitted == null) value else value.copy(width = fitted.frameWidth, height = fitted.frameHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resizeSurface(w, h)
    }

    private fun framePoint(point: ScreenPoint) = requireNotNull(geometry).toFrame(point)

    internal fun admittedMotion(anchor: ScreenPoint, pan: ScreenPoint = ScreenPoint(0.0, 0.0),
        zoom: Double = 1.0, rotation: Double = 0.0): ChartViewport? {
        val basis = viewport
        val wanted = transformChartViewport(basis, anchor = anchor, pan = pan, zoom = zoom, rotation = rotation)
        val withoutZoom = if (zoom != 1.0)
            transformChartViewport(basis, anchor = anchor, pan = pan, zoom = 1.0, rotation = rotation) else null
        return admitCamera(basis, wanted, withoutZoom,
            zoom > 1.0 && pan.x == 0.0 && pan.y == 0.0 && rotation == 0.0)
    }

    /** Native fallback retains native camera ownership and surface-pixel motion units. */
    private fun dispatchNativeMotion(anchor: ScreenPoint, pan: ScreenPoint = ScreenPoint(0.0, 0.0),
        zoom: Double = 1.0, rotation: Double = 0.0, handler: (ChartMotion) -> Unit) {
        val basis = nativeCameraBasis?.invoke() ?: viewport
        val scale = requireNotNull(geometry).scale
        val frameAnchor = framePoint(anchor)
        val framePan = ScreenPoint(pan.x / scale, pan.y / scale)
        val wanted = transformChartViewport(basis, frameAnchor, framePan, zoom, rotation)
        val withoutZoom = if (zoom != 1.0) transformChartViewport(basis, frameAnchor, framePan, 1.0, rotation) else null
        val accepted = admitCamera(basis, wanted, withoutZoom,
            zoom > 1.0 && pan.x == 0.0 && pan.y == 0.0 && rotation == 0.0) ?: return
        if (accepted == basis) return
        when (accepted) {
            wanted -> handler(ChartMotion.anchored(anchor, pan, zoom, rotation))
            withoutZoom -> handler(ChartMotion.anchored(anchor, pan, 1.0, rotation))
            // The canonical owner repeats the same geographic constraint before
            // admission; do not discard a valid third (edge-repositioned) camera.
            else -> handler(ChartMotion.anchored(anchor, pan, zoom, rotation))
        }
    }

    private fun handle(action: TouchAction, points: List<TouchPoint>, time: Long, changedId: Int) {
        val result = tracker.handle(action, points, time, changedId)
        result.transform?.let { move ->
            if (!gestureReported) { gestureReported = true; onGestureStarted() }
            if (result.panIntent) onPanStarted()
            val handler = nativeMotion
            if(touchOwned) {
                val scale=requireNotNull(geometry).scale
                val accepted = admittedMotion(framePoint(move.anchor), ScreenPoint(move.pan.x/scale,move.pan.y/scale),move.zoom,move.rotation)
                    ?: return@let
                if (accepted == viewport) return@let
                viewport = accepted
                onTouchViewport(viewport);onOutlineCamera(viewport)
                if (bufferTransformMode != ChartBufferTransformMode.OFF) {
                    transformTarget = viewport; transformTargetRevision = cameraRevision(); lastCanonicalRevision = transformTargetRevision; applyBufferTransform()
                }
            } else if (handler != null) {
                dispatchNativeMotion(move.anchor, move.pan, move.zoom, move.rotation, handler)
            } else {
            val scale = requireNotNull(geometry).scale
            val accepted = admittedMotion(framePoint(move.anchor), ScreenPoint(move.pan.x / scale, move.pan.y / scale),
                move.zoom, move.rotation) ?: return@let
            if (accepted == viewport) return@let
            viewport = accepted
            onViewport(viewport)
            }
        }
        result.tap?.let { point ->
            performClick()
            if (inspectEnabled && requireNotNull(geometry).contains(point)) {
                onSurfaceTap?.invoke(point) ?: onTap(framePoint(point))
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (inputOrigin == com.no5.helm.chart.RendererInputOrigin.PLATFORM_DISPATCH) touchReplay.externalInput()
        val sink = inputSink() ?: return handleTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) observedGesture++
        val before = System.nanoTime(); val uptime = android.os.SystemClock.uptimeMillis(); val after = System.nanoTime()
        observedHistoryIds = (0..event.historySize).map { sample ->
            val history = if (sample == event.historySize) -1 else sample
            val ids = IntArray(event.pointerCount) { event.getPointerId(it) }
            val xy = FloatArray(event.pointerCount * 2) { n ->
                val pointer = n / 2
                if (history < 0) { if (n % 2 == 0) event.getX(pointer) else event.getY(pointer) }
                else { if (n % 2 == 0) event.getHistoricalX(pointer, history) else event.getHistoricalY(pointer, history) }
            }
            sink.input(if (history < 0) event.eventTime else event.getHistoricalEventTime(history), before, after, uptime,
                event.actionMasked, event.getPointerId(event.actionIndex), history, observedGesture, ids, xy, inputOrigin)
        }
        try { return sink.withInput(observedHistoryIds.last()) { handleTouchEvent(event) } }
        finally { observedHistoryIds = emptyList() }
    }
    private fun disposition(reason: String) {
        val sink = inputSink() ?: return
        observedHistoryIds.forEach { sink.disposition(it, reason) }
    }
    private fun handleTouchEvent(event: MotionEvent): Boolean {
        if (!inputEnabled()) { disposition("RUN_CAMERA_DISABLED"); return false }
        if (width <= 0 || height <= 0 || geometry == null) { disposition("INVALID_GEOMETRY"); return false }
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchAction.DOWN
            MotionEvent.ACTION_POINTER_DOWN -> TouchAction.POINTER_DOWN
            MotionEvent.ACTION_MOVE -> TouchAction.MOVE
            MotionEvent.ACTION_POINTER_UP -> TouchAction.POINTER_UP
            MotionEvent.ACTION_UP -> TouchAction.UP
            MotionEvent.ACTION_CANCEL -> TouchAction.CANCEL
            else -> return super.onTouchEvent(event)
        }
        if (action == TouchAction.DOWN) {
            // Stop at the last camera actually published to this View, before anchoring the new pointer.
            if (fling.isRunning) { fling.stop(); removeCallbacks(animateFling); finishTouch() }
            streamCancelled = false; gestureReported = false
        }
        val effectiveRegions=if(touchActive)gestureBlockedRegions?:blockedRegions else blockedRegions
        val blocked = (0 until event.pointerCount).any { pointer ->
            effectiveRegions.any { rect -> rect.contains(androidx.compose.ui.geometry.Offset(event.getX(pointer), event.getY(pointer))) ||
                (0 until event.historySize).any { sample -> rect.contains(androidx.compose.ui.geometry.Offset(event.getHistoricalX(pointer, sample), event.getHistoricalY(pointer, sample))) } }
        }
        if (blocked) {
            disposition("BLOCKED_REGION")
            finishTouch()
            // A protected region cancels camera work, but accepted pointers
            // still hide controls until their terminal event, even inside it.
            if (action == TouchAction.DOWN || action == TouchAction.UP || action == TouchAction.CANCEL) touchActivity(false)
            tracker.reset(); streamCancelled = true
            parent?.requestDisallowInterceptTouchEvent(touchActive)
            return action != TouchAction.DOWN
        }
        if (streamCancelled) {
            disposition("CANCELLED_STREAM")
            if (action == TouchAction.UP || action == TouchAction.CANCEL) { touchActivity(false);parent?.requestDisallowInterceptTouchEvent(false) }
            return true
        }
        fling.observeAcceptedTouch(event)
        if (action == TouchAction.DOWN) { touchOwned=onTouchBegin();touchActivity(true);parent?.requestDisallowInterceptTouchEvent(true) }
        val changedId = event.getPointerId(event.actionIndex)
        // Android can batch MOVE samples; do not lose an excursion just because the finger returns.
        if (action != TouchAction.DOWN && action != TouchAction.CANCEL) {
            for (sample in 0 until event.historySize) {
                val process = { handle(TouchAction.MOVE, (0 until event.pointerCount).map { index ->
                    TouchPoint(event.getPointerId(index), event.getHistoricalX(index, sample).toDouble(), event.getHistoricalY(index, sample).toDouble())
                }, event.getHistoricalEventTime(sample), changedId) }
                val sink = inputSink()
                if (sink != null && sample < observedHistoryIds.size) sink.withInput(observedHistoryIds[sample], process) else process()
            }
        }
        handle(action, (0 until event.pointerCount).map { index ->
            TouchPoint(event.getPointerId(index), event.getX(index).toDouble(), event.getY(index).toDouble())
        }, event.eventTime, changedId)
        if (action == TouchAction.UP || action == TouchAction.CANCEL) {
            val keepMoving = momentumEnabled && action == TouchAction.UP && inputOrigin == com.no5.helm.chart.RendererInputOrigin.PLATFORM_DISPATCH &&
                bufferTransformMode.transforms && fling.start(viewport, requireNotNull(geometry), touchOwned && gestureReported, chartNavigationBounds())
            if (keepMoving) { onFlingStarted(); postOnAnimation(animateFling) } else finishTouch()
            touchActivity(false);parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (action == TouchAction.UP || action == TouchAction.CANCEL) applyBufferTransform()
        // Claim DOWN as well as MOVE: the View must retain the stream when another finger arrives.
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL || width <= 0 || height <= 0 || geometry == null) return super.onGenericMotionEvent(event)
        val delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (delta == 0f) return super.onGenericMotionEvent(event)
        val point = ScreenPoint(event.x.toDouble(), event.y.toDouble())
        if (blockedRegions.any { it.contains(androidx.compose.ui.geometry.Offset(event.x, event.y)) }) return false
        nativeMotion?.let { handler ->
            onGestureStarted(); dispatchNativeMotion(point, zoom = 2.0.pow((delta / 4.0).coerceIn(-3.0, 3.0)), handler = handler)
            return true
        }
        val accepted = admittedMotion(framePoint(ScreenPoint(event.x.toDouble(), event.y.toDouble())),
            zoom = 2.0.pow((delta / 4.0).coerceIn(-3.0, 3.0))) ?: return true
        if (accepted == viewport) return true
        viewport = accepted
        onViewport(viewport)
        return true
    }

    override fun onDetachedFromWindow() { touchReplay.stop(); finishTouch();touchActivity(false);pendingTexture=null;removeCallbacks(acknowledge);tracker.reset();resetBufferTransform(); super.onDetachedFromWindow() }
}
