package com.no5.helm

import androidx.compose.runtime.*
import com.no5.domain.*
import com.no5.ui.No5Text
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

enum class SonarKnobMode { SONAR, AUDIO }

enum class SonarDrawerTab { DISPLAY, ANALYSIS, DIAGNOSTICS }
data class SonarNativeBounds(val start: Double, val end: Double,
    val state: SonarViewportState = SonarViewportState.AVAILABLE) {
    val available: Boolean get() = state == SonarViewportState.AVAILABLE
}
data class SonarNativeCursor(val x: Float, val y: Float, val bin: Int, val amplitude: Int, val secondsAgo: Double, val depthM: Double?, val amplitudeFullScale: Int = 65535, val pingId: Long? = null, val timestampEpochMs: Long? = null, val aggregationCount:Long=1, val firstTimestampEpochMs:Long?=null, val pingSource:SonarPingSource?=null, val amplitudeContext:SonarAmplitude?=null, val imageMapping:SonarImageValues?=null)

/** Owns presentation and render history; all acquisition belongs to the injected source. */
@Stable
class SonarNativeController(
    private val source: SonarSourcePort,
    private val now: () -> Long = System::currentTimeMillis,
) {
    var knobMode by mutableStateOf(SonarKnobMode.SONAR); private set
    /** MODE boundaries remain observable even when two toggles coalesce back to the same mode. */
    internal var knobMappingRevision by mutableLongStateOf(0L); private set
    private val knobMappingSequence=AtomicLong()
    /** Presentation-only switch: history, calibration and acquisition are untouched. */
    fun toggleKnobMode() { if(!disposed)controls.execute(controls.mode()) }
    private val knobModeBoundaries=java.util.concurrent.CopyOnWriteArrayList<()->Unit>()
    internal fun observeKnobModeBoundary(listener:()->Unit):SourceSubscription {
        knobModeBoundaries+=listener
        return SourceSubscription{knobModeBoundaries-=listener}
    }
    /** Before MODE admission, outside the control-ring lock: retire both possible targets. */
    internal fun beginKnobModeChange(){
        controls.cancel();knobModeBoundaries.forEach{it()}
        knobMappingRevision=knobMappingSequence.incrementAndGet()
    }
    internal fun applyKnobMode() {
        if(disposed)return
        beginKnobModeChange()
        knobMode=if(knobMode==SonarKnobMode.SONAR)SonarKnobMode.AUDIO else SonarKnobMode.SONAR
        displayRevision++
    }
    fun requestOriginClockProof(pingId: Long?, originalEpochMs: Long?, completed: java.util.function.Consumer<com.no5.domain.SonarOriginClockProof?>): Boolean {
        if(disposed || pingId == null || originalEpochMs == null) return false
        val session = sourceStatus.serverSessionId ?: return false
        val generation = sourceStatus.serverGeneration ?: return false
        val selection = sourceStatus.displaySelection?.generation ?: return false
        return source.requestOriginClockProof(com.no5.domain.SonarOriginClockRequest(session,generation,selection,pingId,originalEpochMs)) {
            completed.accept(it)
        }
    }
    fun requestRecoveryOriginClockProof(pingId:Long?,originalEpochMs:Long?,completed:java.util.function.Consumer<com.no5.domain.SonarOriginClockProof?>):Boolean {
        if(disposed||pingId==null||originalEpochMs==null)return false
        val session=sourceStatus.serverSessionId?:return false
        val generation=sourceStatus.serverGeneration?:return false
        val selection=sourceStatus.displaySelection?.generation?:return false
        return source.requestRecoveryOriginClockProof(com.no5.domain.SonarOriginClockRequest(session,generation,selection,pingId,originalEpochMs)) {
            completed.accept(it)
        }
    }
    private val gpuViews=LinkedHashMap<String,java.lang.ref.WeakReference<com.no5.helm.gpu.SonarGpuView>>(2)
    private var gpuPreparationRevision by mutableLongStateOf(0L)
    internal val gpuPipelinePrepared:Boolean get() {
        gpuPreparationRevision
        return activeGpuViews().any{it.pipelinePrepared}
    }
    private fun gpuPreparationChanged(){
        if(!disposed){gpuPreparationRevision++;updateRenderHealth()}
    }
    internal fun attachGpuView(tag:String,view:com.no5.helm.gpu.SonarGpuView) {
        require(tag=="SONAR"||tag=="DASHBOARD")
        gpuViews[tag]?.get()?.takeIf{it!==view}?.close()
        gpuViews[tag]=java.lang.ref.WeakReference(view)
        view.onPreparationChanged={if(gpuViews[tag]?.get()===view)gpuPreparationChanged()}
        gpuPreparationChanged()
    }
    internal fun detachGpuView(tag:String,view:com.no5.helm.gpu.SonarGpuView) {
        if(gpuViews[tag]?.get()===view){view.onPreparationChanged=null;gpuViews.remove(tag);gpuPreparationChanged()}
    }
    private val gpuPresentations=java.util.concurrent.CopyOnWriteArrayList<(com.no5.helm.gpu.SonarGpuCompletion,Long)->Unit>()
    internal fun observeGpuPresentations(listener:(com.no5.helm.gpu.SonarGpuCompletion,Long)->Unit):SourceSubscription {
        gpuPresentations+=listener;return SourceSubscription{gpuPresentations-=listener}
    }
    internal var lastGpuPresentation by mutableStateOf<com.no5.helm.gpu.SonarGpuCompletion?>(null);private set
    // Buffered metadata becomes a drawing input only after THIS TextureView
    // consumed its actual buffer. The public presentation/revision is still
    // acknowledged solely by the later matching composite Window commit.
    private var bufferedGpuSlice:com.no5.helm.gpu.SonarGpuCompletion?=null
    internal fun gpuBuffered(stamp:com.no5.helm.gpu.SonarGpuCompletion){
        if(!disposed&&stamp.context==gpuSourceContext()&&stamp.ringGeneration==model.viewRing.lifecycleGeneration){bufferedGpuSlice=stamp;refreshCursor()}
    }
    internal fun gpuBufferDiscarded(stamp:com.no5.helm.gpu.SonarGpuCompletion?){if(bufferedGpuSlice===stamp)bufferedGpuSlice=null}
    internal fun displayedGpuSlice():com.no5.helm.gpu.SonarGpuCompletion? = (bufferedGpuSlice?:lastGpuPresentation)?.takeIf{
        it.context==gpuSourceContext()&&it.ringGeneration==model.viewRing.lifecycleGeneration&&it.display!=null
    }
    internal fun gpuPresented(stamp:com.no5.helm.gpu.SonarGpuCompletion){
        if(disposed||stamp.context!=gpuSourceContext()||stamp.ringGeneration!=model.viewRing.lifecycleGeneration)return
        lastGpuPresentation=stamp;gpuBufferDiscarded(stamp);presentedDisplayRevision=maxOf(presentedDisplayRevision,stamp.displayRevision)
        val now=System.nanoTime();gpuPresentations.forEach{it(stamp,now)};refreshCursor()
    }
    internal fun displayedGpuAnalysis():SonarPresentation? {
        val stamp=displayedGpuSlice()?:return null;val ring=model.viewRing
        return ring.readLocked {
            val age=(ring.head-1-stamp.newestSlot+ring.capacity)%ring.capacity
            if(age !in 0 until ring.count||ring.pingIdAt(age)!=stamp.pingId||ring.timestampAt(age)!=stamp.originalEpochMs)null
            else ring.presentationAt(age)
        }
    }
    internal fun displayedGpuRawFrame():SonarFrame? {
        val stamp=displayedGpuSlice()?:return null;val ring=model.viewRing
        return ring.readLocked {
            val age=(ring.head-1-stamp.newestSlot+ring.capacity)%ring.capacity
            if(age !in 0 until ring.count||ring.pingIdAt(age)!=stamp.pingId||ring.timestampAt(age)!=stamp.originalEpochMs)null else ring.atAge(age)
        }
    }
    private var reliefGpuView:java.lang.ref.WeakReference<com.no5.helm.sonar.gl.SonarReliefTextureView>?=null
    internal fun registerReliefView(view:com.no5.helm.sonar.gl.SonarReliefTextureView){reliefGpuView=java.lang.ref.WeakReference(view)}
    internal fun unregisterReliefView(view:com.no5.helm.sonar.gl.SonarReliefTextureView){if(reliefGpuView?.get()===view)reliefGpuView=null}
    internal fun activeGpuViews():List<com.no5.helm.gpu.SonarGpuView> = gpuViews.values.mapNotNull{it.get()}.filter{it.isActive()}
    internal fun gpuSourceContext():String = listOf(sourceIdentity.sessionId,sourceStatus.serverSessionId,
        sourceStatus.serverGeneration,sourceStatus.displaySelection?.generation,model.selectedPingSource).toString()
    internal fun gpuDiagnosticLine():String {
        val views=activeGpuViews();val view=views.singleOrNull();val shown=view?.presented
        val ring=model.viewRing
        val resources=runCatching { com.no5.helm.gpu.SonarGpuNative.resources() }.getOrNull()
        val same=shown!=null&&shown.context==gpuSourceContext()&&shown.ringGeneration==ring.lifecycleGeneration
        val ready=same&&shown!=null&&view!=null&&shown.width==view.width&&shown.height==view.height&&shown.displayRevision==displayRevision
        return "No5SonarGpu: v=1 owners=${views.size} errors=${views.count{it.hasFailure()}} ready=$ready contextMatches=$same "+
            "ping=${shown?.pingId?:-1} originalEpochMs=${shown?.originalEpochMs?:-1} width=${shown?.width?:0} height=${shown?.height?:0} "+
            "viewGeneration=${shown?.viewGeneration?:0} surfaceGeneration=${shown?.surfaceGeneration?:0} viewportGeneration=${shown?.viewportGeneration?:0} density=${shown?.density?:0f} rasterScale=${shown?.rasterScale?:0f} plotLeft=${shown?.plotLeft?:0f} plotTop=${shown?.plotTop?:0f} plotWidth=${shown?.plotWidth?:0f} plotHeight=${shown?.plotHeight?:0f} ringGeneration=${ring.lifecycleGeneration} historySeconds=${model.viewDisplay.historySeconds} count=${ring.count} capacity=${ring.capacity} rawSampleBytes=${ring.allocatedSampleBytes} "+
            "nativeOwners=${resources?.get(0)?:-1} nativeContexts=${resources?.get(1)?:-1} nativeSurfaces=${resources?.get(2)?:-1} nativeTextures=${resources?.get(3)?:-1} nativePayloadBytes=${resources?.get(4)?:-1} pipelinePrepared=${view?.pipelinePrepared?:false}\n" +
            "No5SonarRange: v=3 requestedMode=${rangePreference?.rangeMode?:model.display.rangeMode} requestedRangeM=${rangePreference?.rangeM} " +
            "startDepthM=${model.viewDisplay.startDepthM} viewportState=${bounds().state} shownViewportState=${shown?.viewportState} " +
            "shownTop=${shown?.top} shownBottom=${shown?.bottom} shownCropEnd=${shown?.display?.cropEnd} " + model.rangeDiagnosticLine() + "\n" +
            "No5SonarDepth: v=1 mode=${model.depthSettings.mode} windowSeconds=${model.depthSettings.windowSeconds} " +
            "state=${depthDisplay.state} valueM=${depthDisplay.valueM} rawValueM=${model.depth()} ping=${depthDisplay.pingId} " +
            "measuredAtEpochMs=${depthDisplay.measuredAtEpochMs} receivedAtNanos=${depthDisplay.receivedAtNanos} " +
            "sampleCount=${depthDisplay.sampleCount} complete=${depthDisplay.complete} frozen=${model.display.freeze}\n" +
            "No5SonarUi: v=1 symbol=${uiStatus.symbolLevel} reason=${uiStatus.reason} recovering=${uiStatus.recovering} detailsOpen=$statusDetailsOpen plotStatusOverlays=false cursorOutsidePlot=true"
    }
    internal fun originClockDiagnosticLine():String {
        val d=source.originClockDiagnostics()?:return "No5SonarOriginProof: v=1 state=UNAVAILABLE"
        fun value(v:Long?)=v?.toString()?:"unknown"
        return "No5SonarOriginProof: v=1 sequence=${d.sequence} state=${d.state} phase=${d.phase} "+
            "queueNs=${value(d.queueNanos)} readNs=${value(d.readNanos)} consumerWaitNs=${value(d.consumerWaitNanos)} fullNs=${value(d.fullNanos)} "+
            "sampleMinusOriginalMs=${value(d.sampleMinusOriginalMs)} responseBytes=${d.responseBytes} httpFailureStatus=${d.httpFailureStatus?:"unknown"} exception=${d.exceptionType} consumerWakeFailures=${d.consumerWakeFailures}"
    }
    internal var gpuRebuildRevision by mutableLongStateOf(0L);private set
    internal fun rebuildOwnedGpuViews(){if(!disposed)gpuRebuildRevision++}
    private val statusPolicy=SonarStatusPolicy()
    internal var uiStatus by mutableStateOf(SonarUiStatus());private set
    internal var statusDetailsOpen by mutableStateOf(false);private set
    internal var statusNowNanos by mutableLongStateOf(0L);private set
    internal var lastStatusMessage by mutableStateOf<String?>(null);private set
    internal fun openStatusDetails(){statusDetailsOpen=true;updateRenderHealth()}
    internal fun closeStatusDetails(){statusDetailsOpen=false}
    // Kept as an honest current-condition projection for existing diagnostics.
    // The stable symbol uses uiStatus; recovery never claims the old fault is still active.
    internal var renderWarning by mutableStateOf<String?>(null); private set
    private fun updateRenderHealth(){
        val active=sonarScreenVisible&&sourceStatus.connected&&sourceStatus.running&&!model.display.freeze
        val at=System.nanoTime();if(statusDetailsOpen)statusNowNanos=at;val views=activeGpuViews();val pressure=source.receiveDiagnostics()
        val relief=reliefGpuView?.get()?.takeIf{it.isActive()}
        val failure=views.any{it.hasFailure()}||relief?.hasFailure()==true
        val late=views.any{it.presentationOverdue(at)}||relief?.presentationOverdue(at)==true
        val reason=when {
            failure->SonarStatusReason.RENDER_ERROR
            sonarProfileIntentStatus(sourceStatus.error)!=null->sonarProfileIntentStatus(sourceStatus.error)!!
            sourceStatus.error!=null->SonarStatusReason.SOURCE_ERROR
            !sourceStatus.connected->SonarStatusReason.DISCONNECTED
            sourceStatus.stale->SonarStatusReason.STALE
            observationReasonLabel()!=null->SonarStatusReason.REFERENCE_LOST
            active&&pressure?.oldestPendingAgeNanos?.let{it>2_000_000_000L}==true->SonarStatusReason.QUEUE_LATE
            active&&late->SonarStatusReason.PRESENTATION_LATE
            model.display.freeze->SonarStatusReason.FROZEN
            views.any{!it.pipelinePrepared}&&model.viewRing.count==0->SonarStatusReason.PREPARING
            !sourceStatus.running||model.viewRing.count==0->SonarStatusReason.WAITING
            !bounds().available->SonarStatusReason.VIEWPORT_UNAVAILABLE
            pressure?.backpressured==true->SonarStatusReason.BACKPRESSURE
            else->SonarStatusReason.LIVE
        }
        uiStatus=statusPolicy.update(gpuSourceContext(),reason,at)
        renderWarning=reason.label.takeIf{reason.level!=SonarStatusLevel.NORMAL}
    }
    internal fun statusReceiveDiagnostics()=source.receiveDiagnostics()
    internal fun readReceiveSilenceNanos():Long=if(disposed)-1 else source.readReceiveSilenceNanos()
    internal fun reconnectOwnReadTransport():Boolean=!disposed&&source.reconnectReadTransport()
    val sourceIdentity = source.identity
    val model = SonarModel(now).apply{setDepthSettings(SonarDepthSettings());processingObserver=SonarTrace::section}
    var depthProcessingSettings by mutableStateOf(model.depthSettings); private set
    var depthDisplay by mutableStateOf(model.depthDisplay()); private set
    var snapshot by mutableStateOf(model.snapshot()); private set
    var drawer by mutableStateOf<SonarDrawerTab?>(null); private set
    internal var drawerRequest by mutableLongStateOf(0); private set
    var linearInterpolation by mutableStateOf(false); private set
    var reliefEnabled by mutableStateOf(false); private set
    var reliefReady by mutableStateOf(false); internal set
    var reliefFailure by mutableStateOf<String?>(null); internal set
    var reliefAligned by mutableStateOf(true); private set
    fun alignReliefRange(){reliefAligned=true;cursor=null}
    var reliefYaw by mutableStateOf(-.25f); private set
    var reliefPitch by mutableStateOf(.8f); private set
    var reliefZoom by mutableStateOf(.75f); private set
    fun relief(enabled:Boolean) {
        reliefEnabled=enabled
        cursor=null
        if(enabled && reliefFailure!=null)tell(reliefFailure!!)
    }
    fun reliefCamera(dx:Float,dy:Float,zoom:Float=1f) {
        if(!dx.isFinite()||!dy.isFinite()||!zoom.isFinite()||zoom<=0)return
        reliefAligned=false
        reliefYaw=((reliefYaw+dx+PI.toFloat())%(2*PI.toFloat())+2*PI.toFloat())%(2*PI.toFloat())-PI.toFloat()
        reliefPitch=(reliefPitch+dy).coerceIn(.2f,1.35f)
        reliefZoom=(reliefZoom*zoom).coerceIn(.45f,1.35f)
    }
    fun resetReliefCamera(){reliefAligned=false;reliefYaw=-.25f;reliefPitch=.8f;reliefZoom=.75f}

    var cursor by mutableStateOf<SonarNativeCursor?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    private var noticeUntil = 0L
    var capabilities by mutableStateOf(SonarSourceCapabilities(sourceIdentity)); private set
    var lastCommandOutcome by mutableStateOf<SonarAcquisitionOutcome?>(null); private set
    var filterState by mutableStateOf(SonarFilterState(sourceIdentity)); private set
    var filterOutcome by mutableStateOf<SonarFilterOutcome?>(null); private set
    @Volatile var displayRevision = 0L; private set
    @Volatile var presentedDisplayRevision = -1L; private set
    fun displayed() { presentedDisplayRevision = displayRevision }
    var knobLeaseReason by mutableStateOf<String?>("TRUSTED_FOCUS_UNAVAILABLE")
    fun filter(mode: SonarFilterMode) {
        if (disposed || !capabilities.filterControl) return
        filterRequestId = nextRequestId.incrementAndGet()
        filterOutcome = source.filter(SonarFilterRequest(sourceIdentity.sessionId, filterRequestId, mode))
    }
    var sourceStatus by mutableStateOf(SonarSourceStatus(sourceIdentity)); private set
    var selectedSource by mutableStateOf(SonarSourceSelectionState(sourceIdentity)); private set
    var sourceOutcome by mutableStateOf<SonarSourceSelectionOutcome?>(null); private set
    var diagnostics by mutableStateOf<SonarDiagnostics?>(null); private set
    /** Main rate: unique published pings of the confirmed selected source in the existing 5-s window. */
    var pingRate by mutableStateOf(model.measuredRate()); private set
    var usableRate by mutableStateOf(SonarUsableRate()); private set
    private var sonarScreenVisible = false
    private var sonarScreenVisibilityKnown = false
    private var usableDiagnosticInput: SonarDiagnostics? = null
    private var usableEpochInput: SonarEpoch? = null
    private var usableSelectionInput: SonarPingSource? = null
    private var usableLiveInput: Boolean? = null
    private var usableExpiredInput: Boolean? = null
    private var amplitudeHistoryInput: SonarImageColumns? = null
    private var amplitudeHistoryVersion = Long.MIN_VALUE
    private var amplitudeHistorySeconds = Double.NaN
    private var mixedAmplitudeHistory = false
    private var epoch: SonarEpoch? = null
    val liveEvidence: Boolean get() = sourceStatus.connected && !sourceStatus.stale &&
        snapshot.quality in setOf(SonarQuality.ACQUIRING, SonarQuality.FROZEN)
    fun sourceLabel(): String = sonarSourceLabel(model.selectedPingSource ?: sourceStatus.displaySelection?.source ?: selectedSource.selection?.source)
    fun observationReasonLabel(): String? {
        val reason=sourceStatus.observationReason ?: return null
        if(reason.version!=1 || reason.code!="ORIGINAL_REFERENCE_LOST")return null
        val age=now()-reason.statusObservedAtEpochMs
        val stale=!sourceStatus.connected || !reason.statusFresh || age !in -5000..5000
        return com.no5.ui.No5Text.ui("Originalreferenz verloren – Venterior/Versorgung und Kontakt prüfen") +
            (if(reason.latched) " · "+com.no5.ui.No5Text.ui("gemeldeter Hostzustand") else "")+
            (if(stale) " · "+com.no5.ui.No5Text.ui("Status veraltet") else "")
    }
    fun rateTitle(): String = com.no5.ui.No5Text.ui(if (pingRate.timeBasis == SonarRateTimeBasis.RECEPTION) "Empfangsrate" else "Pings")
    fun rateLabel(): String = publishedRateLabel()
    /** The reviewed host usable counter describes OWN only, even when its publication selects ALL/NATIVE. */
    fun selectedUsableRateLabel(): String = if (model.selectedPingSource == SonarPingSource.OWN)
        ownUsableRateLabel() else "— · " + com.no5.ui.No5Text.ui("Unbestätigt")
    /** Explicitly OWN evidence remains separately available in diagnostics. */
    fun ownUsableRateLabel(): String {
        val value = usableRate
        return when (value.state) {
            SonarUsableRateState.STALE -> "— · " + com.no5.ui.No5Text.ui("Veraltet")
            SonarUsableRateState.PARTIAL -> com.no5.ui.No5Text.ui("Einregeln")
            SonarUsableRateState.UNKNOWN -> "— · " + com.no5.ui.No5Text.ui("Unbestätigt")
            else -> sonarNumber(value.hz, 1) + "\u00a0Hz"
        }
    }
    fun publishedRateLabel(): String {
        val value = pingRate
        return when(value.state) {
            SonarRateState.STALE -> "— · " + com.no5.ui.No5Text.ui("Veraltet")
            SonarRateState.WARMING -> com.no5.ui.No5Text.ui("Einregeln")
            SonarRateState.INCOMPLETE -> "— · " + com.no5.ui.No5Text.ui("Fenster unvollständig")
            else -> sonarNumber(value.hz, 1) + "\u00a0Hz"
        }
    }
    /** Labels the actual inspected/frozen profile, never a newer polled noise policy. */
    fun profileInterpretationLabel(amplitude: SonarAmplitude? = if (cursor != null) cursor!!.amplitudeContext else model.viewAmplitudeContext,
        summarizeHistory: Boolean = true): String? {
        if (summarizeHistory && cursor == null && hasMixedAmplitudeHistory())
            return com.no5.ui.No5Text.ui("Anzeige · gemischte Kontexte")
        if (amplitude?.hasDisplayContext != true) return null
        return com.no5.ui.No5Text.ui(when {
            !amplitude.usable -> "Anzeigeamplitude · Einordnung unbestätigt"
            amplitude.noiseActive == true -> "ADC-Kontrast · bereinigt"
            else -> "ADC-Anzeige · unbearbeitet"
        })
    }
    private fun hasMixedAmplitudeHistory(): Boolean {
        val columns = model.viewImageColumns; val seconds = model.viewDisplay.historySeconds
        if (amplitudeHistoryInput !== columns || amplitudeHistoryVersion != columns.version || amplitudeHistorySeconds != seconds) {
            amplitudeHistoryInput = columns; amplitudeHistoryVersion = columns.version; amplitudeHistorySeconds = seconds
            mixedAmplitudeHistory = false
            var first: SonarAmplitude? = null; var found = false
            for (age in 0 until model.visibleImageCount()) {
                if (!columns.occupiedAt(age)) continue
                val context = columns.amplitudeContextAt(age)
                if (!found) { first = context; found = true }
                else if (context != first && (context?.hasDisplayContext == true || first?.hasDisplayContext == true)) {
                    mixedAmplitudeHistory = true; break
                }
            }
        }
        return mixedAmplitudeHistory
    }
    fun imageAutoLabel(): String = com.no5.ui.No5Text.ui(if(!model.amplitudeVerified) "Vollskala unbestätigt · Auto gesperrt" else if(listOf(model.display.gainMode,model.display.gammaMode,model.display.thresholdMode).none{it==SonarImageMode.AUTO}) "Manuelle Bildwerte" else when(model.imageAutoState) {
        SonarImageAutoState.WARMING -> "Automatik sammelt"
        SonarImageAutoState.ACTIVE -> "Auto · aktiv"
        SonarImageAutoState.WAITING -> "Auto · wartet auf frische Profile"
        SonarImageAutoState.INCOMPATIBLE -> "Vollskala unbestätigt · Auto gesperrt"
    })
    fun observationAgeMs(timestamp: Long?): Long? = timestamp?.let { (now() - it).coerceAtLeast(0) }
    private fun observeEpoch(value: SonarEpoch, pingSource: SonarPingSource? = null) {
        val old=epoch
        val sameGateway=old?.serverSessionId==value.serverSessionId && old?.generation==value.generation
        // Missing optional selection metadata is not a new selection event.
        val effective=if(sameGateway&&value.selectionGeneration==null) value.copy(selectionGeneration=old?.selectionGeneration) else value
        val selected=if(sameGateway&&value.selectionGeneration==null&&pingSource==null) model.selectedPingSource else pingSource
        if (effective != old) { cursor = null; zoomAnchorBin = null; epoch = effective; diagnostics = null; controls.cancel() }
        model.updateContext(effective, selected)
    }
    fun selectSource(value: SonarPingSource) {
        if (!disposed && capabilities.sourceSelection) {
            sourceRequestId = nextRequestId.incrementAndGet()
            sourceOutcome = source.selectSource(SonarSourceSelectionRequest(sourceIdentity.sessionId, sourceRequestId, value))
        }
    }
    fun imageAuto(enabled: Boolean = true) {
        if (disposed) return
        model.setImageAuto(enabled); displayRevision++; refresh()
    }
    fun imageMode(field: SonarImageField, mode: SonarImageMode) {
        if (disposed) return
        model.setImageMode(field, mode); displayRevision++; refresh()
    }
    fun manualImage(field: SonarImageField, value: Double) {
        if (field == SonarImageField.GAIN) setGain(value)
        else applyManualImage(field, value)
    }
    internal fun applyGainOffset(value: Double): Boolean {
        if(disposed || !value.isFinite() || model.display.gainMode!=SonarImageMode.AUTO)return false
        model.setAutoGainOffset(value); displayRevision++;refresh();return true
    }
    internal fun applyManualImage(field: SonarImageField, value: Double): Boolean {
        if (disposed || !value.isFinite()) return false
        return try { model.setManualImageValue(field, value); displayRevision++; refresh(); true }
        catch (_: IllegalArgumentException) { false }
    }
    val commandPending: Boolean get() = lastCommandOutcome is SonarAcquisitionOutcome.Pending
    private val subscriptions = mutableListOf<SourceSubscription>()
    private var disposed = false
    private var generation = -1L
    private var requestId = 0L
    private var filterRequestId = 0L
    private var sourceRequestId = 0L
    private var zoomAnchorBin: Double? = null
    // Operator intent survives missing geometry/capabilities and temporary source generations.
    private var rangePreference by mutableStateOf<SonarDisplay?>(null)
    private var bottomOverlayPreference by mutableStateOf(false)
    private var trackOverlayPreference by mutableStateOf(false)
    internal val controls = SonarOwnedControls(this)
    fun exportPresentationState(): SonarViewState {
        val live = snapshot.display
        val wanted = rangePreference
        val saved = live.copy(freeze = false,
            rangeM = if (wanted != null) wanted.rangeM else live.rangeM,
            rangeMode = wanted?.rangeMode ?: live.rangeMode,
            cropStart = wanted?.cropStart ?: live.cropStart,
            cropEnd = wanted?.cropEnd ?: live.cropEnd,
            zoom = wanted?.zoom ?: live.zoom,
            overlayBottom = bottomOverlayPreference, overlayTargets = trackOverlayPreference)
        return SonarViewState(saved, knobMode, linearInterpolation, depthSettings = model.depthSettings)
    }
    fun restorePresentationState(saved: SonarViewState) {
        if (disposed || saved.version !in 1..4) return
        model.setDepthSettings(saved.depthSettings)
        depthProcessingSettings = saved.depthSettings
        // A restored setting is not evidence of a calibrated source or retained frozen samples.
        val requested = if (saved.version < 4) saved.display.copy(startDepthM = 0.0) else saved.display
        if (requested.rangeM?.let { !it.isFinite() || it <= 0 } == true) return
        if (!applyDisplay(rememberPreference = false) { requested.copy(freeze = false, overlayBottom = false, overlayTargets = false,
            rangeM = null, rangeMode = SonarRangeMode.MANUAL) }) return
        rangePreference = requested.copy(freeze = false)
        bottomOverlayPreference = requested.overlayBottom
        trackOverlayPreference = requested.overlayTargets
        applyDisplayPreferencesWhenValid()
        refresh()
        if(knobMode!=saved.knobMode){beginKnobModeChange();knobMode=saved.knobMode}
        linearInterpolation = saved.linearInterpolation
        updateRenderHealth()
        controls.observe()
    }
    val canStart: Boolean get() = !disposed && !commandPending && capabilities.canStart && !running
    val canStop: Boolean get() = !disposed && !commandPending && capabilities.canStop && running
    val running: Boolean get() = snapshot.running
    val statusText: String get() = when {
        snapshot.quality == SonarQuality.ERROR -> "SONAR ERROR"
        snapshot.quality == SonarQuality.OFFLINE && snapshot.ringCount > 0 -> "SONAR · STREAM LOST"
        !snapshot.connected -> "SONAR SOURCE UNAVAILABLE"
        sourceStatus.stale -> "SONAR · STALE"
        snapshot.quality == SonarQuality.DEGRADED && sourceStatus.connected -> "SONAR · NO CURRENT PINGS"
        snapshot.running && snapshot.display.freeze -> "SONAR · FROZEN VIEW"
        snapshot.running -> "SONAR · MEASUREMENT RUN"
        else -> "SONAR · STOPPED"
    }
    init {
        subscriptions += source.sourceState.subscribe { value ->
            if (!disposed && value.identity == sourceIdentity) selectedSource = value
        }
        subscriptions += source.sourceOutcomes.subscribe { value ->
            if (!disposed && value != null && value.sessionId == sourceIdentity.sessionId && value.requestId == sourceRequestId) sourceOutcome = value
        }
        subscriptions += source.diagnostics.subscribe { value ->
            if (!disposed && (sonarScreenVisible || drawer == SonarDrawerTab.DIAGNOSTICS) && value?.identity == sourceIdentity && value.generation == (sourceStatus.serverGeneration ?: generation) &&
                (sourceStatus.serverSessionId == null || value.serverSessionId == sourceStatus.serverSessionId)) {
                diagnostics = value
                refresh()
            }
        }
        subscriptions += source.filterState.subscribe { value -> if (!disposed && value.identity == sourceIdentity) filterState = value }
        subscriptions += source.filterOutcomes.subscribe { value ->
            if (!disposed && value != null && value.sessionId == sourceIdentity.sessionId && value.requestId == filterRequestId) filterOutcome = value
        }
        subscriptions += source.status.subscribe { status ->
            if (!disposed && status.identity == sourceIdentity && status.generation >= generation) {
                val previousStatus=sourceStatus
                if (previousStatus.connected && !status.connected || status.queueDrops != previousStatus.queueDrops ||
                    !previousStatus.stale && status.stale || previousStatus.error == null && status.error != null)
                    model.markImageContinuityBoundary()
                sourceStatus = status
                if (status.generation > generation) {
                    generation = status.generation
                }
                observeEpoch(SonarEpoch(status.serverSessionId, status.serverGeneration ?: status.generation, status.displaySelection?.generation), status.displaySelection?.source)
                if (status.connected) {
                    model.connect((status.source ?: sourceIdentity.label).take(80), sourceIdentity.mode == SourceMode.DEMO, status.simulated)
                    model.acquisition(status.running)
                } else model.disconnect()
                model.setError(status.error)
                model.setSourceStale(status.stale)
                refresh()
            }
        }
        subscriptions += source.capabilities.subscribe { value ->
            if (!disposed && value.identity == sourceIdentity) {
                capabilities = value
                model.setBottom(model.bottom, capability = value.bottomDetection)
                refresh()
            }
        }
        subscriptions += source.outcomes.subscribe { outcome ->
            if (!disposed && outcome != null && outcome.sessionId == sourceIdentity.sessionId && outcome.requestId == requestId) {
                lastCommandOutcome = outcome
                if (outcome is SonarAcquisitionOutcome.Rejected) tell(outcome.message)
                refresh()
            }
        }
        val receiveBatch: (List<SonarSourceFrame>) -> Unit = { packets ->
            SonarTrace.phase("sonar.batch.adopt") {
            var changed = false
            for (packet in packets) {
            if (!disposed && packet.identity == sourceIdentity && packet.generation == generation && model.connected && model.running) {
                observeEpoch(SonarEpoch(packet.serverSessionId ?: sourceStatus.serverSessionId, sourceStatus.serverGeneration ?: packet.generation,
                    packet.displaySelection?.generation ?: sourceStatus.displaySelection?.generation), packet.displaySelection?.source ?: sourceStatus.displaySelection?.source ?: model.selectedPingSource)
                val previous = model.latest
                if (previous == null || packet.frame.pingId > previous.pingId && packet.frame.timestampEpochMs >= previous.timestampEpochMs) {
                    model.setCalibration(packet.calibration)
                    model.setAnalysis(packet.geometry, packet.bottom, packet.presentation, capabilities.bottomDetection, packet.hostPackageVersion, packet.amplitude)
                    SonarTrace.phase("sonar.auto_and_ring"){model.frame(packet.frame, receivedAtEpochMs = now(), pingSource = packet.pingSource,localReceivedAtNanos=packet.localReceivedAtNanos)}
                    changed = true
                }
            }
            }
            // Every accepted measurement is in the ring before publishing one UI snapshot.
            if (changed) { refreshCursor(); refresh() }
            }
        }
        subscriptions += source.frameBatches?.subscribe { packets -> packets?.let(receiveBatch) }
            ?: source.frames.subscribe { packet -> packet?.let { receiveBatch(listOf(it)) } }
    }
    private fun applyDisplayPreferencesWhenValid() {
        if (model.display.freeze) return
        var next = model.display
        // An epoch can remove geometry while retaining AUTO in the live display object.
        // Keep operator intent in rangePreference; the effective uncalibrated display must
        // be admissible so clearing stale overlays cannot be rejected together with AUTO.
        if(model.metersPerBin==null)next=next.copy(rangeMode=SonarRangeMode.MANUAL,rangeM=null)
        rangePreference?.let { requested ->
            val metersPerBin = model.metersPerBin
            val range = requested.rangeM
            val eligible = when {
                requested.rangeMode == SonarRangeMode.AUTO -> model.bottomValid
                range != null -> metersPerBin != null && range.isFinite() && range > 0
                else -> true
            }
            // Retain the requested physical viewport when a temporary capture is shorter.
            // Its effective end stops at real coverage; the saved metre target is unchanged.
            val effectiveRange = if (range != null && metersPerBin != null) range.coerceAtMost(metersPerBin * 1023) else range
            if (eligible) next = next.copy(rangeM = effectiveRange, rangeMode = requested.rangeMode,
                cropStart = if (range != null || requested.rangeMode == SonarRangeMode.AUTO) next.cropStart else requested.cropStart,
                cropEnd = if (range != null || requested.rangeMode == SonarRangeMode.AUTO) next.cropEnd else requested.cropEnd,
                zoom = requested.zoom)
        }
        val freshSource = sourceStatus.connected && !sourceStatus.stale
        next = next.copy(overlayBottom = bottomOverlayPreference && freshSource && model.bottomValid,
            overlayTargets = trackOverlayPreference && freshSource && capabilities.confirmedTracks && model.viewMetersPerBin != null)
        if (next != model.display) try {
            model.setDisplay(next)
            displayRevision++
        } catch (_: IllegalArgumentException) { /* Keep the preference; unavailable geometry is not a user edit. */ }
    }
    fun refresh() {
        applyDisplayPreferencesWhenValid(); depthDisplay = model.depthDisplay(); snapshot = model.snapshot(); pingRate = model.measuredRate()
        val at = now()
        // Raw-ping freshness controls depth/image separately. The independently
        // observed host window may legitimately report zero while raw profiles are stale.
        val live = sourceStatus.connected && sourceStatus.running && sourceStatus.error == null
        val expired = diagnostics?.statusObservedAtEpochMs?.let { at - it !in -5000..5000 } != false
        // A 1-Hz snapshot is parsed once. Ordinary raw-frame/UI ticks only
        // check its freshness/context; they do not rebuild maps or parse JSON numbers.
        if (usableDiagnosticInput !== diagnostics || usableEpochInput != model.epoch ||
            usableSelectionInput != model.selectedPingSource || usableLiveInput != live || usableExpiredInput != expired) {
            usableDiagnosticInput = diagnostics; usableEpochInput = model.epoch
            usableSelectionInput = model.selectedPingSource; usableLiveInput = live; usableExpiredInput = expired
            usableRate = SonarUsableRate.fromDiagnostics(diagnostics, sourceIdentity, model.epoch, model.selectedPingSource, at, live)
        }
        updateRenderHealth()
        controls.observe()
    }
    fun tell(message: String) { lastStatusMessage=message;notice = message; noticeUntil = now() + 4500 }
    private fun updateDiagnosticsDemand() {
        // The secondary host usable-depth rate consumes the same bounded 1-Hz
        // source feed while SONAR is visible, independently of its drawer.
        source.setDiagnosticsVisible(if (sonarScreenVisibilityKnown) sonarScreenVisible else drawer == SonarDrawerTab.DIAGNOSTICS)
    }
    fun open(tab: SonarDrawerTab = SonarDrawerTab.DISPLAY) {
        drawerRequest=if(drawerRequest==Long.MAX_VALUE)0 else drawerRequest+1
        selectDrawerTab(tab)
    }
    internal fun selectDrawerTab(tab:SonarDrawerTab) { drawer=tab;updateDiagnosticsDemand() }
    fun screenVisible(visible: Boolean) { sonarScreenVisibilityKnown = true; sonarScreenVisible = visible; source.setProfileDemand(visible); updateDiagnosticsDemand() }
    /** Close the drawer only; the visible secondary rate still needs its bounded host evidence. */
    fun close() { drawer = null; updateDiagnosticsDemand(); if (!sonarScreenVisible) diagnostics = null }
    private fun command(action: (SonarAcquisitionRequest) -> SonarAcquisitionOutcome) {
        if (disposed || commandPending) return
        requestId = nextRequestId.incrementAndGet()
        val request = SonarAcquisitionRequest(sourceIdentity.sessionId, requestId)
        val outcome = action(request)
        // Source state drives acquisition. An ACK alone never invents a running stream.
        if (disposed || outcome.sessionId != sourceIdentity.sessionId || outcome.requestId != request.requestId || requestId != request.requestId) return
        lastCommandOutcome = outcome
        if (outcome is SonarAcquisitionOutcome.Rejected) tell(outcome.message)
        refresh()
    }
    fun start() = command(source::start)
    fun stop() = command(source::stop)
    /** Called by an app-root effect, independent of whether SONAR is the selected page. */
    fun tick() {
        if (disposed) return
        source.tick()
        if (notice != null && now() >= noticeUntil) notice = null
        refreshCursor()
        refresh()
    }
    /** Source switches/process disposal must call this before creating the replacement session. */
    fun dispose() {
        if (disposed) return
        disposed = true
        reliefGpuView?.get()?.dispose();reliefGpuView=null
        activeGpuViews().forEach{it.close()};gpuViews.clear();gpuPresentations.clear();lastGpuPresentation=null
        sonarScreenVisible = false
        source.setDiagnosticsVisible(false)
        controls.close()
        knobModeBoundaries.clear()
        var failure: Throwable? = null
        fun retire(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        try {
            subscriptions.forEach { subscription -> retire { subscription.close() } }
            retire { source.close() }
        } finally {
            subscriptions.clear()
            model.resetAcquisition(); cursor = null; drawer = null; notice = null
            capabilities = SonarSourceCapabilities(sourceIdentity); lastCommandOutcome = null
            sourceStatus = SonarSourceStatus(sourceIdentity)
            refresh()
        }
        failure?.let { throw it }
    }
    fun display(change: (SonarDisplay) -> SonarDisplay) { applyDisplay(change = change) }
    internal fun applyDisplay(rememberPreference: Boolean = true, change: (SonarDisplay) -> SonarDisplay): Boolean {
        if (disposed) return false
        return try {
            val before = model.display
            val requested = change(before)
            val wasZoomed = before.zoom
            model.setDisplay(requested)
            if (rememberPreference) {
                if (requested.rangeM != before.rangeM || requested.rangeMode != before.rangeMode ||
                    requested.cropStart != before.cropStart || requested.cropEnd != before.cropEnd || requested.zoom != before.zoom)
                    rangePreference = model.display.copy(freeze = false)
                if (requested.overlayBottom != before.overlayBottom) bottomOverlayPreference = requested.overlayBottom
                if (requested.overlayTargets != before.overlayTargets) trackOverlayPreference = requested.overlayTargets
            }
            displayRevision++
            if (model.display.zoom && !wasZoomed) zoomAnchorBin = cursor?.bin?.toDouble() ?: (model.display.cropStart + model.display.cropEnd) / 2
            if (!model.display.zoom) zoomAnchorBin = null
            refreshCursor()
            refresh()
            true
        } catch (e: IllegalArgumentException) { tell(e.message ?: "Setting unavailable"); false }
    }
    fun gain(delta: Double) { controls.execute(controls.gainDelta(delta)) }
    fun setGain(value: Double) { controls.execute(controls.gain(value)) }
    fun adjustGain(steps: Int) { controls.execute(controls.adjustGain(steps)) }
    fun adjustRange(steps: Int) { controls.execute(controls.adjustRange(steps)) }
    fun toggleAScope() { controls.execute(controls.aScope()) }
    fun cyclePalette(night: Boolean = false) {
        display { it.copy(palette = SonarPalette.entries[(it.palette.ordinal + 1) % SonarPalette.entries.size]) }
        if (night) tell("NIGHT RED remains active. Day palette selection saved.")
    }
    fun setPalette(value: SonarPalette) = display { it.copy(palette = value) }
    fun interpolation(linear: Boolean) { if (!disposed && !model.display.freeze) linearInterpolation = linear }
    fun exactPingProfiles(enabled: Boolean) {
        if (!disposed && !model.display.freeze) {
            cursor=null
            if(enabled)reliefReady=false
            display { it.copy(exactPingProfiles=enabled) }
        }
    }
    fun freeze() { controls.execute(controls.freeze()) }
    internal fun applyFreeze(): Boolean {
        if (model.ring.count == 0) { tell("No acquired samples to freeze."); return false }
        return applyDisplay { it.copy(freeze = !it.freeze) }
    }
    fun crop(start: Double, end: Double) { controls.execute(controls.crop(start, end)) }
    internal fun applyCrop(start: Double, end: Double): Boolean {
        val metersPerBin = model.viewMetersPerBin
        return if (start == 0.0 && end.isFinite() && end > 0.0 && metersPerBin != null)
            applyRange(end.coerceIn(1.0, 1023.0) * metersPerBin)
        else applyBinCrop(start, end)
    }
    /** A command admitted in bins keeps that unit even if geometry appears before its effect. */
    internal fun applyBinCrop(start: Double, end: Double): Boolean {
        cursor = null
        val changed = applyDisplay { it.copy(rangeM = null, rangeMode = SonarRangeMode.MANUAL, cropStart = start, cropEnd = end, zoom = false) }
        if (changed) rangePreference = model.display.copy(freeze = false)
        return changed
    }
    fun range(meters: Double) { controls.execute(controls.range(meters)) }
    internal fun applyRange(meters: Double): Boolean {
        if (!meters.isFinite() || meters <= model.display.startDepthM) return false
        cursor = null
        val metersPerBin = model.viewMetersPerBin
        val effective = if (metersPerBin != null) meters.coerceAtMost(metersPerBin * 1023) else meters
        val changed = applyDisplay { it.copy(rangeM = effective, rangeMode = SonarRangeMode.MANUAL, zoom = false) }
        if (changed) rangePreference = model.display.copy(freeze = false, rangeM = meters)
        return changed
    }
    val requestedRangeMode: SonarRangeMode get() = rangePreference?.rangeMode ?: snapshot.display.rangeMode
    val requestedBottomOverlay: Boolean get() = bottomOverlayPreference
    val requestedTrackOverlay: Boolean get() = trackOverlayPreference
    fun bottomOverlay(enabled: Boolean) { bottomOverlayPreference = enabled; refresh() }
    fun trackOverlay(enabled: Boolean) { trackOverlayPreference = enabled; refresh() }
    fun depthProcessing(mode: SonarDepthMode) { if (!disposed) { model.setDepthSettings(model.depthSettings.copy(mode = mode)); depthProcessingSettings = model.depthSettings; refresh() } }
    fun depthWindowSeconds(seconds: Int) { if (!disposed) { model.setDepthSettings(model.depthSettings.copy(windowSeconds = seconds)); depthProcessingSettings = model.depthSettings; refresh() } }
    fun depthStatusLabel(): String = No5Text.ui(when(depthDisplay.state) {
        SonarDepthState.CURRENT -> "Aktuell"
        SonarDepthState.HELD -> "Messlücke überbrückt"
        SonarDepthState.UNAVAILABLE -> "Keine Tiefe"
    })
    fun autoRange(auto: Boolean) {
        if (disposed) return
        rangePreference = model.display.copy(freeze = false, rangeM = null,
            rangeMode = if (auto) SonarRangeMode.AUTO else SonarRangeMode.MANUAL)
        applyDisplayPreferencesWhenValid(); refresh()
    }
    // Enabling zoom captures the anchor. Inspecting within the zoomed viewport cannot move it.
    fun startDepth(meters: Double): Boolean {
        if (!meters.isFinite() || meters < 0.0 || disposed) return false
        cursor = null
        return applyDisplay { it.copy(startDepthM = meters, cropStart = 0.0) }
    }
    fun bounds(): SonarNativeBounds = SonarNativeMath.bounds(model.viewDisplay, zoomAnchorBin, model.viewMetersPerBin)
    internal fun viewportStatusLabel(): String = when(bounds().state) {
        SonarViewportState.AVAILABLE -> "Tiefenausschnitt verfügbar"
        SonarViewportState.UNCALIBRATED -> "Tiefenskalierung nicht verfügbar"
        SonarViewportState.OUTSIDE_CAPTURE -> "Anfangstiefe außerhalb des Messbereichs"
        SonarViewportState.EMPTY_MANUAL_RANGE -> "Endtiefe muss unterhalb der Anfangstiefe liegen"
    }
    private fun refreshCursor() { cursor?.let { inspect(it.x, it.y) } }
    fun inspect(x: Float, y: Float) {
        val shown=displayedGpuSlice()
        if (shown?.viewportState?.let { it != SonarViewportState.AVAILABLE } == true || shown == null && !bounds().available) {
            cursor = null; return
        }
        if(lastGpuPresentation!=null&&shown==null){cursor=null;return}
        val ring = model.viewImageColumns; val count = model.visibleImageCount()
        if (count == 0) { cursor = null; return }
        val newest=shown?.originalEpochMs?:model.imageNewestTime()
        val display=shown?.display?:model.viewDisplay
        val xx = x.coerceIn(0f, 1f); val yy = y.coerceIn(0f, 1f)
        val target = (1 - xx) * display.historySeconds * 1000
        val wanted=newest-target
        if(shown!=null&&(wanted<ring.timestampAt(count-1)||wanted>newest)){cursor=null;return}
        var age=-1
        var distance=Double.MAX_VALUE
        for(candidate in 0 until count) {
            if(!ring.occupiedAt(candidate)||shown!=null&&ring.timestampAt(candidate)>newest)continue
            val delta=kotlin.math.abs((newest-ring.timestampAt(candidate)).toDouble()-target)
            if(delta<distance){distance=delta;age=candidate}
        }
        if(age<0){cursor=null;return}
        if(ring.aggregated) {
            if(wanted<ring.firstTimestampAt(age)-SonarTimeImageHistory.BIN_MS/2 || wanted>ring.lastTimestampAt(age)+SonarTimeImageHistory.BIN_MS/2){cursor=null;return}
        }
        val b = if(shown!=null)SonarNativeBounds(shown.top,shown.bottom)else bounds(); val axisBin = b.start + yy * (b.end - b.start)
        val sourceBin = ring.sourceBinAtAxis(age, axisBin, (if(shown!=null)shown.metersPerBin else model.viewMetersPerBin) ?: Double.NaN)
        if (!sourceBin.isFinite()) { cursor = null; return }
        val bin = sourceBin.roundToInt().coerceIn(0, 1023)
        cursor = SonarNativeCursor(xx, yy, bin, ring.sampleAt(age, bin), (newest - ring.timestampAt(age)) / 1000.0, ring.metersPerBinValueAt(age).takeIf{it.isFinite()}?.times(bin), ring.amplitudeFullScaleAt(age), ring.measuredPingIdAt(age), ring.timestampAt(age), if(ring.aggregated)ring.contributionCountAt(age)else 1, if(ring.aggregated)ring.firstTimestampAt(age)else null,ring.pingSourceAt(age),ring.amplitudeContextAt(age),ring.imageValuesAt(age,display))
    }
    companion object { private val nextRequestId = AtomicLong() }
}

/** Only display preferences; excludes raw samples, measurements, pending commands and freeze. */
data class SonarViewState(val display: SonarDisplay = SonarDisplay(), val knobMode: SonarKnobMode = SonarKnobMode.SONAR,
                          val linearInterpolation: Boolean = false, val version: Int = 4, val depthSettings: SonarDepthSettings = SonarDepthSettings())

@Composable
fun SonarAcquisition(controller: SonarNativeController) {
    LaunchedEffect(controller) { while (true) { delay(100); controller.tick() } }
}

/** Pure, independently tested visual maths. Raw bins remain in SonarModel unchanged. */
object SonarNativeMath {
    fun bounds(display: SonarDisplay, cursorBin: Double?, metersPerBin: Double? = null): SonarNativeBounds {
        val view = sonarDepthViewport(display, metersPerBin, cursorBin)
        return SonarNativeBounds(view.startBin, view.endBin, view.state)
    }
    private val highContrastStops = arrayOf(
        intArrayOf(6,19,35), intArrayOf(0,62,133), intArrayOf(0,197,223),
        intArrayOf(242,222,32), intArrayOf(255,111,0), intArrayOf(235,28,26)
    )
    private fun rgb(r: Double, g: Double, b: Double): Int =
        (0xff shl 24) or (r.roundToInt() shl 16) or (g.roundToInt() shl 8) or b.roundToInt()
    fun palette(value: Double, palette: SonarPalette, night: Boolean): Int {
        val v = value.coerceIn(0.0, 1.0)
        if (night || palette == SonarPalette.NIGHT_RED) return rgb(5 + v * 135, 2 + v * v * 46, 2 + v * v * 10)
        if (palette == SonarPalette.MONO_AMBER) return rgb(8 + v * 247, 16 + v * 159, 22 + v * 13)
        if (palette == SonarPalette.SUN_WHITE) return rgb(246 - v * 175, 249 - v * 211, 250 - v * 240)
        val n = v * 5; val i = min(4, floor(n).toInt()); val t = n - i
        val a = highContrastStops[i]; val b = highContrastStops[i + 1]
        return rgb(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
    }
}
internal fun sonarNumber(value: Double?, digits: Int = 1): String = value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.${digits}f", it) } ?: "—"
internal fun sonarPaletteName(value: SonarPalette) = value.name.replace("SUN_HIGH_CONTRAST", "SUN · HIGH CONTRAST").replace('_', ' ')
