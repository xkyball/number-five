package com.no5.helm.ui

import android.view.Surface
import android.widget.FrameLayout
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.no5.helm.chart.*
import com.no5.preparation.ui.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect

/** The host remembers ONE adapter. All coordinates cross the checked native frame boundary. */
internal class NativeRendererAdapter(private val engine: ChartEngine, private val initialViewport: ChartViewport) : ChartRendererAdapter {
    private val cameraBounds = com.no5.helm.chart.ChartCameraBoundsCache()
    private var view: ChartSurfaceView? = null
    private var routeHandleView: RouteHandleView? = null
    var routeHandle by mutableStateOf<Pair<Int, GeoProjection>?>(null)
    var onRouteDrop: (Int, GeoProjection) -> Unit = { _, _ -> }
    var overlayObjects: List<OverlayHit> = emptyList()
    var onOverlayTap: (List<OverlayHit>, ChartObjectQuery) -> Unit = { _, _ -> }
    data class OverlayHit(val id: String, val title: String, val kind: String, val position: GeoProjection)
    private var presentationView: ChartPresentationView? = null
    private var outlineView:ChartOutlineView?=null
    private var attached: Surface? = null
    private var attachedReplacement: Surface? = null
    private var attachmentOwner: Any? = null
    private var attachedWidth = 0
    private var attachedHeight = 0
    private var started = false
    private var surfaceReady = false
    private var surfaceGeneration = Long.MIN_VALUE
    private var regions: List<Rect> = emptyList()
    private val visualScratch = VisualPresentationSnapshot()
    private val visualScaleProjector = VisualChartScaleProjector()
    var visualScaleProjection: VisualChartScale? by mutableStateOf(null)
        private set
    var distanceUnit = DistanceUnit.NAUTICAL_MILES
        set(value) { if (field != value) { field = value; refreshVisualScale() } }
    var onObjectTap: (ChartObjectQuery?, ChartObjectQueryResult) -> Unit = { _, _ -> }
    private var objectTaps = false
    private var tapSequence = 0L
    private var tapsEnabled = false
    fun enableTaps(enabled: Boolean, objects: Boolean = false) { if (enabled != tapsEnabled || objects != objectTaps) tapSequence++; tapsEnabled = enabled; objectTaps = objects; view?.inspectEnabled = enabled }
    fun setTouchOutlinesEnabled(enabled: Boolean): Boolean {
        if(!engine.setTouchOutlinesEnabled(enabled))return false
        view?.cancelGesture()
        syncViewState()
        return true
    }

    fun block(regions: List<Rect>) { this.regions = regions; routeHandleView?.blocked = regions; view?.blockedRegions = regions;outlineView?.let{it.blocked=regions;it.invalidate()} }
    private var gestureRegions:List<Rect>?=null
    fun blockDuringGesture(regions:List<Rect>) { gestureRegions=regions;view?.gestureBlockedRegions=regions }
    fun claimDockGesture() { view?.cancelTouchForDock() }
    fun hasActiveTouch():Boolean = view?.isTouchActive==true
    private fun clearVisualScale() {
        visualScratch.clear(); visualScaleProjection = visualScaleProjector.clear()
    }
    /** Main-thread front-buffer instrument. No pending/latest camera can supply its measurement. */
    private fun refreshVisualScale() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return
        val presenter = presentationView
        val owner = attachmentOwner
        val authority = engine.status.value
        if (!started || !surfaceReady || owner == null || presenter == null || !authority.surfaceAttached ||
            authority.sourcePresentationUnsupported || !presenter.readVisualPresentation(visualScratch) ||
            visualScratch.surfaceOwner !== attached && visualScratch.surfaceOwner !== attachedReplacement) {
            clearVisualScale(); return
        }
        val next = visualScaleProjector.update(visualScratch, authority.chartSourceGeneration, surfaceGeneration,
            authority.surfaceGeneration, authority.bufferTransformGeneration, distanceUnit)
        val after = engine.status.value
        if (presentationView !== presenter || attachmentOwner !== owner ||
            after.chartSourceGeneration != authority.chartSourceGeneration || after.surfaceGeneration != authority.surfaceGeneration ||
            (after.bufferTransformGeneration != authority.bufferTransformGeneration &&
                visualScratch.pinnedFront !== visualScratch.buffer) || !after.surfaceAttached || after.sourcePresentationUnsupported) clearVisualScale()
        else visualScaleProjection = next
    }
    /** AndroidView.update may be skipped with stable parameters; phase transitions must still reach the Views. */
    private fun syncViewState() {
        val current=engine.status.value
        if (routeHandleView?.handle?.frame != current.confirmedFrame || current.presentationPending || current.touchPreview != null)
            routeHandleView?.handle = null
        view?.let { native ->
            presentationView?.setWorldUnderlay(current.worldUnderlayCandidate,current.presentation.styleId)
            presentationView?.setSourceAuthority(current.chartSourceGeneration)
            if (surfaceGeneration != current.surfaceGeneration) {
                if (surfaceGeneration != Long.MIN_VALUE) presentationView?.resetForSurfaceAttachment()
                surfaceGeneration = current.surfaceGeneration
            }
            native.bufferTransformMode = current.bufferTransformMode
            native.bufferTransformGeneration = current.bufferTransformGeneration
            presentationView?.refreshVisibleBuffer()
            if((current.bufferTransformMode == ChartBufferTransformMode.OFF && !current.touchOutlinesEnabled)||current.touchPreview==null)native.cancelInactivePreview()
            native.acceptViewport(current.viewport, engine.bufferTransformCameraRevision());native.blockedRegions=regions
            native.touchReplay.sync(current.rendererBenchmark.liveInputEnabled, current.rendererBenchmark.liveReplay)
            outlineView?.let { outline ->
                val preview=current.touchPreview.takeIf { current.touchOutlinesEnabled && current.bufferTransformMode == ChartBufferTransformMode.OFF }
                if(preview!=null) {
                    outline.geometry=if(native.width>0&&native.height>0)ChartSurfaceGeometry.fit(native.width,native.height)else null
                    outline.outlines=current.outlines;outline.blocked=regions;outline.preview=preview
                } else if(outline.preview!=null)outline.preview=null
            }
        }
        // Completion/scale metadata can follow the Texture update for the same exact stamp.
        refreshVisualScale()
    }
    private fun scheduleVisibleFrameWake(presenter: ChartPresentationView) {
        if(!started || !surfaceReady || presentationView!==presenter) return
        val expected=attached ?: return
        val replacement=attachedReplacement
        val owner=attachmentOwner ?: return
        presenter.scheduleVisibleFrameWake(owner) {
            if(started && surfaceReady && presentationView===presenter && attached===expected &&
                attachedReplacement===replacement && attachmentOwner===owner)
                engine.refreshSurface(expected,owner)
        }
    }
    private fun attach(surface: Surface, width: Int, height: Int) {
        if (!started || !surfaceReady || width <= 0 || height <= 0) return
        if (attached === surface && attachedWidth == width && attachedHeight == height) return
        view?.resizeSurface(width, height)
        val owner = Any()
        presentationView?.bindAttachmentOwner(surface, null, owner) ?: view?.bindSurfaceOwner(surface, owner)
        presentationView?.beginAttachmentDiagnostics()
        ChartSurfaceLifecycleTrace.record("ATTACH", width=width, height=height)
        engine.attachSurface(surface, width, height, owner)
        attached = surface; attachedReplacement = null; attachmentOwner = owner; attachedWidth = width; attachedHeight = height
        presentationView?.let(::scheduleVisibleFrameWake)
    }
    private fun attachPair(first: Surface, second: Surface, width: Int, height: Int) {
        if (!started || !surfaceReady || width <= 0 || height <= 0) return
        if (presentationView?.usePairedPresentation != true) { attach(first, width, height); return }
        if (attached === first && attachedReplacement === second && attachedWidth == width && attachedHeight == height) return
        view?.resizeSurface(width, height)
        val presenter = presentationView ?: return
        presenter.resetForSurfaceAttachment()
        if (!presenter.prepareSurfacesForAttachment(first, second, width, height)) return
        val owner = Any()
        presentationView?.bindAttachmentOwner(first, second, owner)
        presenter.beginAttachmentDiagnostics()
        ChartSurfaceLifecycleTrace.record("ATTACH_PAIR", width=width, height=height, paired=true)
        engine.attachPresentationSurfaces(first, second, width, height, owner)
        attached = first; attachedReplacement = second; attachmentOwner = owner; attachedWidth = width; attachedHeight = height
        scheduleVisibleFrameWake(presenter)
    }
    private fun detach(onDetached: () -> Unit = {}) {
        clearVisualScale()
        presentationView?.cancelVisibleFrameWake()
        val expected = attached; val owner = attachmentOwner
        if (expected != null && owner != null) {
            attached = null; attachedReplacement = null; attachmentOwner = null
            ChartSurfaceLifecycleTrace.record("DETACH", paired=presentationView?.usePairedPresentation==true)
            engine.detachSurface(expected, owner, onDetached)
        } else onDetached()
    }
    private fun ChartFrame.snapshot() = ViewportSnapshot(revision, geometry.surfaceWidth, geometry.surfaceHeight, cameraToken)
    private fun frame(viewport: ViewportSnapshot): ChartFrame? = engine.status.value.confirmedFrame?.takeIf { it.snapshot() == viewport }

    /** A redraw may replace a frame without changing its coordinate basis. Each
     * retry still calls the engine with its exact currently confirmed frame. */
    private suspend fun routeTap(point: ScreenPoint, initial: ChartFrame): Pair<GeoProjection, ChartFrame>? {
        val presentation = engine.status.value.presentationGeneration
        val filters = engine.status.value.objectFilters.revision
        var candidate = initial
        repeat(3) {
            val position = engine.unprojectCoordinate(point, candidate)
            val current = engine.status.value
            if (current.presentationGeneration != presentation || current.objectFilters.revision != filters) return null
            val confirmed = current.confirmedFrame ?: return null
            if (position != null && confirmed == candidate) return position to confirmed
            if (confirmed.viewport != initial.viewport || confirmed.geometry != initial.geometry ||
                confirmed.sourceGeneration != initial.sourceGeneration || confirmed.contentGeneration != initial.contentGeneration) return null
            candidate = confirmed
        }
        return null
    }

    @Composable override fun Content(modifier: Modifier, onEvent: (RendererEvent) -> Unit) {
        val status by engine.status.collectAsStateWithLifecycle()
        val event by rememberUpdatedState(onEvent)
        val scope = rememberCoroutineScope()
        val owner = LocalLifecycleOwner.current
        LaunchedEffect(engine,owner) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.status.collect {
                    // Surface changes arrive from the renderer worker; Android
                    // view state must always be applied on the UI thread.
                    withContext(Dispatchers.Main.immediate) { syncViewState() }
                }
            }
        }
        LaunchedEffect(status.confirmedFrame, status.presentationPending, status.touchPreview, routeHandle) {
            val selected = routeHandle
            val confirmed = status.confirmedFrame
            if (selected == null || confirmed == null || status.presentationPending || status.touchPreview != null) routeHandleView?.handle = null
            else {
                val projected = engine.projectCoordinates(listOf(selected.second), confirmed)?.singleOrNull()
                if (projected != null && engine.status.value.confirmedFrame == confirmed) {
                    routeHandleView?.red = status.presentation.mode == PresentationMode.RED_NIGHT
                    routeHandleView?.handle = RouteHandleView.Handle(selected.first, projected, confirmed)
                }
            }
        }
        val captureSink = if (status.rendererCapture.active) engine.rendererUiSink() else null
        val activity = LocalContext.current.captureActivity()
        DisposableEffect(owner, activity, captureSink) {
            val recorder = if (activity != null && captureSink != null)
                RendererWindowCapture(activity.window, captureSink) { engine.rendererUiSink() === captureSink } else null
            recorder?.let {
                owner.lifecycle.addObserver(it)
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) it.start()
            }
            onDispose { recorder?.let { owner.lifecycle.removeObserver(it); it.stop() } }
        }
        DisposableEffect(owner) {
            started = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val observer = LifecycleEventObserver { _, lifecycleEvent ->
                when (lifecycleEvent) {
                    Lifecycle.Event.ON_START -> {
                        started = true
                        ChartSurfaceLifecycleTrace.record("START", paired=presentationView?.usePairedPresentation==true)
                        val presenter = presentationView
                        if (presenter?.usePairedPresentation == true) {
                            presenter.availableSurfaces()?.let { pair ->
                                attachPair(pair.first, pair.second, pair.width, pair.height)
                            }
                        } else view?.let { native -> native.renderSurface?.let { attach(it, native.width, native.height) } }
                    }
                    Lifecycle.Event.ON_STOP -> { ChartSurfaceLifecycleTrace.record("STOP", paired=presentationView?.usePairedPresentation==true);started = false;view?.touchReplay?.stop();view?.cancelGesture();engine.interruptTouchGesture();detach() }
                    else -> Unit
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer); started = false; detach() }
        }
        LaunchedEffect(status.confirmedFrame) {
            status.confirmedFrame?.let { confirmed ->
                val viewport = confirmed.snapshot()
                // Legacy coordinate event only. Visual scale has its own actual-front authority.
                event(RendererEvent.FrameConfirmed(viewport, null))
            }
        }
        val measuredModifier = remember(modifier, engine) { modifier.layout { measurable, constraints ->
            val child = measureChartUi(engine, RendererUiPhase.CHART_NODE_MEASURE) { measurable.measure(constraints) }
            layout(child.width, child.height) {
                measureChartUi(engine, RendererUiPhase.CHART_NODE_PLACE) { child.placeRelative(0, 0) }
            }
        }.drawWithContent {
            measureChartUi(engine, RendererUiPhase.CHART_NODE_DRAW_DISPATCH) { drawContent() }
        } }
        AndroidView(modifier = measuredModifier, factory = { context ->
            val container=FrameLayout(context)
            val outline=ChartOutlineView(context);outlineView=outline
            val marker=RendererDiagnosticMarkerView(context)
            val chart=ChartSurfaceView(context).also { native ->
                view = native
                native.captureSink = engine::rendererUiSink
                native.inputSink = engine::rendererLiveInputSink
                native.onDiagnosticMarker = { marker.point = it }
                native.inputEnabled = {
                    val benchmark = engine.status.value.rendererBenchmark
                    !benchmark.active || benchmark.liveInputEnabled
                }
                native.chartNavigationBounds = { engine.status.value.navigationBounds }
                native.acceptViewport(initialViewport)
                native.onViewport = engine::setViewport
                native.nativeMotion = engine::moveCamera
                native.nativeCameraBasis = { engine.status.value.viewport }
                native.onTouchBegin=engine::beginTouchGesture
                native.admitCamera = { basis, wanted, withoutZoom, recovery ->
                    val state = engine.status.value
                    val region = cameraBounds.get(state.navigationBounds)
                    val limited = region?.constrain(wanted, basis)?.effective
                        ?: wanted.takeIf { !state.residentCatalogActive }
                    val alternative = withoutZoom?.let { region?.constrain(it, basis)?.effective ?: it }
                    if (limited == null) null
                    else if (!state.residentCatalogActive) limited
                    else state.residentCatalogGeneration?.let { catalog ->
                        engine.evaluateResidentCamera(ChartResidentCameraProposal(basis, limited, alternative, recovery,
                            catalog, state.chartSourceGeneration, state.surfaceGeneration))?.acceptedCamera
                    }
                }
                native.onTouchViewport=engine::updateTouchViewport
                native.onTouchFinish=engine::endTouchGesture
                native.onFlingStarted={ engine.setTouchMotionState(ChartCameraMotionState.FLING) }
                native.momentumEnabled=true
                native.onBufferConsumed = { timestamp ->
                    if (view === native) {
                        presentationView?.observeSourceBuffer(timestamp)
                        engine.bufferConsumed(timestamp)
                    }
                }
                native.onTaggedBuffer=engine::chartBufferForTimestamp
                native.onDiagnosticBuffer=engine::chartBufferDiagnostics
                native.cameraRevision=engine::bufferTransformCameraRevision
                native.onOutlineCamera={ camera -> if (engine.status.value.bufferTransformMode == ChartBufferTransformMode.OFF) {
                    outline.geometry=ChartSurfaceGeometry.fit(native.width,native.height);outline.outlines=engine.status.value.outlines;outline.preview=engine.status.value.touchPreview?.copy(camera=camera)
                } }
                native.onGestureStarted = { tapSequence++; event(RendererEvent.GestureStarted) }
                native.onPanStarted = { event(RendererEvent.PanStarted) }
                native.onTouchActivity = { event(RendererEvent.TouchActivityChanged(it)) }
                native.inspectEnabled = tapsEnabled
                native.blockedRegions = regions
                native.gestureBlockedRegions = gestureRegions
                native.onSurfaceTap = { point ->
                    val confirmed = engine.status.value.confirmedFrame
                    val sequence = ++tapSequence
                    val objects = objectTaps
                    val request = confirmed?.let { ChartObjectQuery(point, 24.0 * native.resources.displayMetrics.density,
                        it, engine.status.value.presentationGeneration, filterRevision = engine.status.value.objectFilters.revision) }

                    if (confirmed != null) scope.launch {
                        try {
                            if (objects) {
                                val candidates = overlayObjects.toList()
                                val positions = engine.projectCoordinates(candidates.map { it.position }, confirmed)
                                val hits = if (positions == null) emptyList() else candidates.zip(positions).filter { (_, p) -> kotlin.math.hypot(p.x - point.x, p.y - point.y) <= requireNotNull(request).radiusSurfacePixels }.map { it.first }
                                if (sequence != tapSequence || !objectTaps || engine.status.value.confirmedFrame != confirmed) return@launch
                                if (hits.isNotEmpty()) { onOverlayTap(hits, requireNotNull(request)); return@launch }
                                onObjectTap(request, ChartObjectQueryResult(ChartObjectQueryState.PENDING, message = "Abfrage läuft"))
                                val result = engine.queryObjects(requireNotNull(request))
                                if (sequence == tapSequence && objectTaps) onObjectTap(request, result)
                            } else {
                                val resolved = routeTap(point, confirmed)
                                if (sequence == tapSequence && resolved != null && engine.status.value.confirmedFrame == resolved.second)
                                    event(RendererEvent.GeoTapped(Wgs84(resolved.first.latitude, resolved.first.longitude), resolved.second.snapshot()))
                                else if (sequence == tapSequence) event(RendererEvent.Failed("Kartenstand wird aktualisiert. Wegpunkt erneut antippen."))
                            }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { if (sequence == tapSequence) event(RendererEvent.Failed(failure.message ?: "Kartenabfrage fehlgeschlagen")) }
                    }
                }
                native.onSurfaceReady = { surface, width, height ->
                    if (view === native) { surfaceReady = true; attach(surface, width, height) }
                    else ChartSurfaceLifecycleTrace.record("STALE_READY", 0, current=false)
                }
            }
            val presentation = ChartPresentationView(context, chart, pairingSupported = engine.supportsPresentationSlots).also { presenter ->
                presentationView = presenter
                presenter.onVisiblePresentationChanged = { if (presentationView === presenter) refreshVisualScale() }
                presenter.onWindowVisible = { scheduleVisibleFrameWake(presenter) }
                presenter.onSurfacesReady = { first, second, width, height ->
                    if (presentationView === presenter) { surfaceReady = true; attachPair(first, second, width, height) }
                    else ChartSurfaceLifecycleTrace.record("STALE_READY", current=false, paired=true)
                }
                presenter.onPresented = { slot, stamp -> if (presentationView === presenter) engine.presentationBufferConsumed(slot, stamp) }
                presenter.onAcquired = { slot, stamp -> presentationView === presenter && engine.presentationBufferAcquired(slot, stamp) }
                presenter.onOwnedSurfaceRelease = { loan, release ->
                    val owner = loan.attachmentOwner // frozen with the producer/Texture generation at callback admission
                    val current = presentationView === presenter && attachmentOwner === owner &&
                        (attached === loan.surface || attachedReplacement === loan.surface)
                    if (current) { presenter.cancelVisibleFrameWake();surfaceReady = false; attached = null; attachedReplacement = null; attachmentOwner = null; clearVisualScale() }
                    ChartSurfaceLifecycleTrace.record("RELEASE", current=current, paired=presenter.usePairedPresentation)
                    // An unbound producer never entered EGL. Every bound one
                    // retains its own barrier, including an old ABA attachment.
                    if (owner == null) release() else engine.detachSurface(loan.surface, owner, release)
                }
            }
            val handle = RouteHandleView(context).also { handle ->
                routeHandleView = handle; handle.blocked = regions
                handle.onDrop = { selected, point -> scope.launch {
                    if (engine.status.value.confirmedFrame == selected.frame && routeHandle?.first == selected.index) {
                        val position = engine.unprojectCoordinate(point, selected.frame)
                        if (position != null && engine.status.value.confirmedFrame == selected.frame) onRouteDrop(selected.index, position)
                    }
                } }
            }
            container.addView(presentation,FrameLayout.LayoutParams(-1,-1));container.addView(outline,FrameLayout.LayoutParams(-1,-1));container.addView(marker,FrameLayout.LayoutParams(-1,-1));container.addView(handle,FrameLayout.LayoutParams(-1,-1));container
        }, onRelease = { container -> if(presentationView?.parent===container) { view?.cancelGesture();engine.interruptTouchGesture();detach();surfaceReady=false;view=null;presentationView=null;outlineView=null;routeHandleView=null } }, update = { _ ->
            measureChartUi(engine, RendererUiPhase.ANDROID_VIEW_UPDATE) {
                syncViewState()
            }
        })
    }
    override suspend fun execute(command: RendererCommand) {
        val accepted=when(command) {
            is RendererCommand.Zoom -> {
                val base=engine.status.value.touchPreview?.baseFrame
                val confirmed=frame(command.viewport)?:base?.takeIf{it.snapshot()==command.viewport}
                if(confirmed==null||!command.factor.isFinite()||command.factor<=0)false
                else {
                    view?.cancelGesture();engine.interruptTouchGesture()
                    engine.zoomCamera(command.factor,ScreenPoint(command.anchor.x.toDouble(),command.anchor.y.toDouble()),confirmed)
                }
            }
            is RendererCommand.Center -> if(command.follow)false else {
                view?.cancelGesture();engine.interruptTouchGesture()
                engine.centerCamera(GeoProjection(command.position.latitude,command.position.longitude))
            }
        }
        // Rejection is side-effect free: never replace a newer queued goal with the old displayed camera.
        check(accepted){"Kartenbefehl abgelehnt: Frame veraltet oder Fähigkeit nicht verfügbar."}
    }
    override suspend fun project(objects: FrozenList<GeoObject>, viewport: ViewportSnapshot): ProjectionBatch? {
        val confirmed = frame(viewport) ?: return null
        val points = engine.projectCoordinates(objects.map { GeoProjection(it.position.latitude, it.position.longitude) }, confirmed) ?: return null
        if (frame(viewport) != confirmed || points.size != objects.size) return null
        return ProjectionBatch(viewport, FrozenList.copyOf(objects.zip(points).map { (obj, point) ->
            ProjectedPoint(obj.id, ScreenPixel(point.x.toFloat(), point.y.toFloat()))
        }))
    }
    override suspend fun unproject(point: ScreenPixel, viewport: ViewportSnapshot): Wgs84? {
        val confirmed = frame(viewport) ?: return null
        val position = engine.unprojectCoordinate(ScreenPoint(point.x.toDouble(), point.y.toDouble()), confirmed) ?: return null
        return if (frame(viewport) == confirmed) Wgs84(position.latitude, position.longitude) else null
    }
}

private tailrec fun Context.captureActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.captureActivity() else null
    else -> null
}
