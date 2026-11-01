package com.no5.helm
import android.os.Bundle
import com.no5.gateway.*
import com.no5.header.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.no5.helm.chart.ChartEngineConfig
import com.no5.helm.chart.EngineResult
import com.no5.helm.chart.RendererBenchmarkConfig
import com.no5.helm.client.HelmClient
import com.no5.helm.engine.NativeChartEngine
import com.no5.helm.ui.NativeHelmScreen
import com.no5.helm.ui.No5ImmersiveActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import com.no5.helm.chart.ChartBufferTransformMode
import org.json.JSONObject

class MainActivity : No5ImmersiveActivity() {
    internal val systemKnobs by lazy { com.no5.appcontrol.ProductKnobClient(this) }
    /** Loading/error content owns MAP's keys too; it cannot accidentally alter background audio. */
    private val waitingKnobs by lazy { com.no5.control.NormalizedKnobInput.entries.associateWith {
        object:com.no5.appcontrol.BufferedKnobAction {
            override fun submit(sequence:Long,physicalUptimeMs:Long)=0L
            override fun writeResult(commandId:Long,reply:android.os.Parcel)=false
            override fun cancel()=Unit
        }
    } }
    internal val systemHeader by lazy { com.no5.appcontrol.ProductHeaderProducer(this, HeaderPage.CHART) }
    private val ownedWindows by lazy { com.no5.ui.No5OwnedWindowFocus(this) { focused ->
        systemKnobs.onWindowFocusChanged(focused); systemHeader.onWindowFocusChanged(focused)
    } }
    companion object { internal var benchmarkHost = WeakReference<MainActivity>(null) }
    private lateinit var uiStateClient: com.no5.ui.No5UiClient
    private var uiSnapshot by mutableStateOf<com.no5.ui.UiStateSnapshot?>(null)
    private var uiLanguage by mutableStateOf("de")
    private var uiConnected by mutableStateOf(false)
    private var uiResumed by mutableStateOf(false)
    private val uiObserver = com.no5.ui.No5UiClient.Observer { snapshot ->
        uiSnapshot = snapshot; uiLanguage = snapshot.language; uiConnected = snapshot.connected
    }
    private val installedVersion by lazy { com.no5.releaseinfo.ReleaseInfo.shortBuildLabel(this, "MAP") }
    private fun preparationStatus(message: String) = setContent {
        Column {
            Text(com.no5.ui.No5Text.ui(message))
        }
    }
    private val runtime by lazy { androidx.lifecycle.ViewModelProvider(this)[MapSessionViewModel::class.java] }
    private val engine get() = runtime.engine
    private lateinit var client: HelmClient
    private lateinit var gateway: LuminaGatewayClient
    private var gatewaySubscription: AutoCloseable? = null
    private var gatewayIdentity = ""
    private var headerSourceGeneration by mutableStateOf(0L)
    private var gatewayEndpoint by mutableStateOf("Zugang in Lumina Control einrichten")
    private var gatewayNotice by mutableStateOf<String?>(null)
    private fun refreshGateway(status: GatewaySessionStatus = gateway.status) {
        gatewayEndpoint = status.origin ?: status.label
        gatewayNotice = status.takeUnless { it.phase == GatewaySessionPhase.READY }?.label
        val identity = status.identity ?: "unavailable"
        if (identity == gatewayIdentity) return
        systemHeader.clear()
        check(headerSourceGeneration < Long.MAX_VALUE); headerSourceGeneration++
        gatewayIdentity = identity
        // The retained session owner applies gateway changes exactly once.
    }
    private var charts: PersistentChartController? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        systemKnobs.requestLeaseBuffered(waitingKnobs)
        uiStateClient = com.no5.ui.No5UiClient(this)
        uiSnapshot = uiStateClient.snapshot; uiLanguage = uiSnapshot!!.language
        uiStateClient.addObserver(uiObserver)
        client = runtime.client
        gateway = runtime.gateway
        gatewaySubscription = gateway.observe(::refreshGateway)
        if (!runtime.prepared.isCompleted) preparationStatus(com.no5.ui.No5Text.ui("No 5 · Kartenbestand wird vorbereitet …"))
        lifecycleScope.launch {
            try {
                runtime.prepared.await()
                val controller = runtime.charts.also { charts = it }
                val home = engine.status.value.viewport
                setContent {
                    val confirmedUiLanguage = uiLanguage
                    com.no5.ui.No5LocaleScope(confirmedUiLanguage, onLanguageChange = if (uiConnected) uiStateClient::setLanguage else null) {
                    val state = controller.state.collectAsStateWithLifecycle().value
                    val active = state.snapshot.active
                    val stored = state.snapshot.packages.find { it.id == active?.packageId }
                    val label = if (active == null) com.no5.ui.No5Text.ui("Keine aktive Karte") else com.no5.ui.No5Text.ui("%s · %d Zellen", stored?.label ?: "NOAA S57", active.cells.size)
                    NativeHelmScreen(engine, client.state, gatewayEndpoint, label, home, controller,
                        onHomeGateway = { gateway.openSetup() }, onHeaderContext = ::publishHeaderContext, headerSourceGeneration = headerSourceGeneration, routeActions = client,
                        positionController = runtime.positions, versionLabel = installedVersion,
                        gatewayNotice = gatewayNotice,
                        onBufferedKnobBindings = { bindings ->
                            systemKnobs.requestLeaseBuffered(if(bindings.isEmpty())waitingKnobs else bindings.mapKeys { (input,_) -> com.no5.control.NormalizedKnobInput.valueOf(input.name) }
                                .mapValues { (_,action) -> object:com.no5.appcontrol.BufferedKnobAction {
                                    override fun submit(sequence:Long,physicalUptimeMs:Long)=action.submit(sequence,physicalUptimeMs)
                                    override fun cancel()=action.cancel()
                                    override fun writeResult(commandId:Long,reply:android.os.Parcel):Boolean {
                                        val result=com.no5.helm.chart.ChartControlResult();if(!action.result(commandId,result))return false
                                        reply.writeInt(1);reply.writeInt(result.state);reply.writeString(result.reason);reply.writeLong(result.sequence)
                                        reply.writeLong(result.revision);reply.writeLong(result.appliedRevision);reply.writeLong(result.presentedRevision)
                                        reply.writeDouble(result.desiredScale);reply.writeDouble(result.actualScale);return true
                                    }
                                } })
                        },
                        headerControlsSlot = { modifier, night, selectNight ->
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { com.no5.header.artwork.No5HeaderControlsView(it) }, modifier = modifier,
                                update = { view -> view.render(installedVersion, night, uiResumed, selectNight, language = confirmedUiLanguage) { details ->
                                    com.no5.releaseinfo.ReleaseInfo.showDetailsWithWifi(this@MainActivity, night)
                                } })
                        },
                        mediaHeaderSlot = { modifier, night ->
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { com.no5.header.artwork.No5HeaderMediaView(it).apply {
                                    onOpenAudio = { com.no5.helm.ui.openNo5Audio(this@MainActivity) }
                                } }, modifier = modifier,
                                update = { view -> uiSnapshot?.let { view.render(it, night, uiResumed,
                                    !android.animation.ValueAnimator.areAnimatorsEnabled()) } })
                        },
                        onVersionInfo = { com.no5.releaseinfo.ReleaseInfo.showDetailsWithWifi(this@MainActivity, engine.status.value.presentation.mode == com.no5.helm.chart.PresentationMode.RED_NIGHT) }, onConnect = { gateway.openSetup() })
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (error: Exception) { preparationStatus(com.no5.ui.No5Text.ui("No 5 konnte nicht starten: %s", error.message ?: "")) }
        }
    }
    private fun publishHeaderContext(model: com.no5.helm.ui.NativeHeaderContext) {
        if (!::client.isInitialized || !model.matches(headerSourceGeneration, client.state.value, runtime.positions.state.value)) return
        val page = HeaderPage.valueOf(model.page)
        val theme = if (model.redNight) HeaderTheme.RED_NIGHT else HeaderTheme.DAY
        val raw = model.backend.snapshot
        val gps = model.position
        val input = mapHeaderSourceInput(model)
        val sourceScope = raw?.let { snapshot ->
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(snapshot.serverId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            "map-$headerSourceGeneration-${hash.take(32)}-${gps?.generation ?: 0}"
        }
        systemHeader.update(page, sourceScope) { publication ->
            MapHeaderSnapshotBuilder.build(publication, page, theme, input)
        }
    }
    override fun onResume() { super.onResume(); uiResumed = true; if (::uiStateClient.isInitialized) uiStateClient.start(); systemKnobs.onResume(); systemHeader.onResume(); ownedWindows.onResume(); if (::client.isInitialized) { refreshGateway() }; benchmarkHost = WeakReference(this) }
    override fun onPause() { ownedWindows.onPause(); uiResumed = false; if (::uiStateClient.isInitialized) uiStateClient.stop(); systemHeader.onPause(); systemKnobs.onPause(); if (benchmarkHost.get() === this) benchmarkHost.clear(); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); ownedWindows.windowChanged() }
    internal suspend fun startBenchmark(config: RendererBenchmarkConfig): EngineResult {
        if (config.currentLiveParityOnly) enforceCallingOrSelfPermission("android.permission.DUMP",
            "Current-live FULL parity requires DUMP")
        if (engine.rendererBenchmarkStatus(config.clientRunToken).runId.isNotBlank())
            return engine.startRendererBenchmark(config) // Lost START response: reuse before transient host prerequisites.
        if (config.currentResidentLive != null) {
            val blocker = com.no5.helm.ui.rendererDiagnosticCameraStartBlocker(runtime.positions.state.value,
                charts == null || charts?.state?.value?.busy == true)
            if (blocker != null) return EngineResult(false, blocker)
        }
        if (charts == null || charts?.state?.value?.busy == true) return EngineResult(false, "Chart preparation/import/activation must finish first")
        return engine.startRendererBenchmark(config)
    }
    internal suspend fun snapshotOuterState(snapshotId: String): String {
        check(charts != null && charts?.state?.value?.busy == false) { "Chart preparation must finish first" }
        val snapshot = checkNotNull(engine.snapshotRendererOuterState(snapshotId)) { "Outer snapshot unavailable" }
        check(charts?.state?.value?.busy == false) { "Chart preparation changed during snapshot" }
        return snapshot
    }
    internal suspend fun restoreOuterState(snapshotId: String): EngineResult {
        check(charts != null && charts?.state?.value?.busy == false) { "Chart preparation must finish first" }
        val restored = engine.restoreRendererOuterState(snapshotId)
        if (!restored.success) return restored
        return EngineResult(charts?.reconcileAfterBenchmarkRecovery() == true, "Outer engine restore and controller reconciliation")
    }
    internal fun cancelBenchmark(expectedRunId: String): EngineResult = engine.cancelRendererBenchmark(expectedRunId)
    internal fun setBufferTransformMode(mode: ChartBufferTransformMode): EngineResult = engine.setBufferTransformMode(mode)
    internal fun benchmarkStatus(clientRunToken: String? = null): JSONObject {
        val state = engine.rendererBenchmarkStatus(clientRunToken)
        val memory = android.app.ActivityManager.MemoryInfo().also {
            getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
        }
        return JSONObject().put("build", com.no5.helm.app.BuildConfig.VERSION_NAME).put("commit", com.no5.helm.app.BuildConfig.SOURCE_COMMIT)
            .put("measurementInterfaceVersion", "u02b/1").put("deviceElapsedRealtimeMs", android.os.SystemClock.elapsedRealtime())
            .put("residentCapacityInterfaceVersion", "no5-resident-capacity/1")
            .put("residentCapacityOuterStateInterfaceVersion", "no5-capacity-outer-state/1")
            .put("residentCapacityOuterStateScope", "CURRENT_ENTRY_CATALOG_ONLY")
            .put("residentCapacityEnabled", engine.status.value.residentCatalogActive)
            .put("residentCapacityWorkers", 1).put("residentCapacityDisplayEndpoint", "NOT_MEASURED")
            .put("chartSourceGeneration", engine.status.value.chartSourceGeneration)
            .put("processId", android.os.Process.myPid())
            .put("osMemory", JSONObject().put("availableBytes", memory.availMem)
                .put("lowMemoryThresholdBytes", memory.threshold).put("lowMemory", memory.lowMemory)
                .put("source", "ActivityManager.MemoryInfo"))
            .put("workerSweepImplemented", true).put("requestedWorkersAvailable", org.json.JSONArray(listOf(1, 2, 4, 6)))
            .put("workerSweepScope", "PreparedCache::identities initial_generation/generation_recheck hashing; parser and GL serial")
            .put("bufferTransformInterfaceVersion", "t0-buffer-transform/1")
            .put("bufferTransformMode", engine.status.value.bufferTransformMode.name)
            .put("bufferTransformBaseReady", engine.status.value.bufferTransformBaseReady)
            .put("bufferTransformDisplayEndpoint", "NOT_MEASURED")
            .put("liveInputInterfaceVersion", "t0-live-input/1")
            .put("runKind", state.runKind.name).put("liveInputEnabled", state.liveInputEnabled).put("liveReplay", state.liveReplay)
            .put("touchReplaySha256", com.no5.helm.chart.RendererTouchReplay.SHA256)
            .put("liveReplayRecipe", state.liveReplayRecipe.name)
            .put("selectedTouchReplaySha256", com.no5.helm.chart.RendererTouchReplay.sha256(state.liveReplayRecipe))
            .put("presentationIsolationInterfaceVersion", "p0-withheld/1")
            .put("baseAreaPresentationInterfaceVersion", "c0-areas/1")
            .put("areaReuseInterfaceVersion", "r0-gap-fill/1")
            .put("liveRepaintTransforms", state.liveRepaintTransforms)
            .put("active", state.active).put("runId", state.runId).put("phase", state.phase)
            .put("clientRunToken", state.clientRunToken).put("deadlineElapsedRealtimeMs", state.deadlineElapsedRealtimeMs)
            .put("recoveryBudgetMs", state.recoveryBudgetMs).put("cancelReason", state.cancelReason)
            .put("recoveryDeadlineExceeded", state.recoveryDeadlineExceeded)
            .put("completedGroups", state.completedGroups).put("totalGroups", state.totalGroups)
            .put("groupFrame", state.groupFrame).put("message", state.message).put("restored", state.restored).put("reportFile", state.reportFile)
    }
    override fun onDestroy() {
        if (::uiStateClient.isInitialized) { uiStateClient.removeObserver(uiObserver); uiStateClient.close() }
        ownedWindows.close(); systemHeader.close(); systemKnobs.close()
        gatewaySubscription?.close(); gatewaySubscription = null
        // ViewModel owns clients/library/engine; an Activity only releases its own UI bindings.
        super.onDestroy()
    }
}

/** Only the named selected GPS owns these quality/provenance fields, never mixed HELM telemetry. */
internal fun mapHeaderSourceInput(model: com.no5.helm.ui.NativeHeaderContext): MapHeaderSourceInput {
    val gps = model.position
    return MapHeaderSourceInput(model.backend.snapshot?.serverId, model.sourceDetail,
        HeaderTransport.valueOf(model.transport), sourceId = gps?.sourceId,
        observedAtEpochMs = gps?.observedAtEpochMs,
        quality = when {
            gps?.sourceId == null -> HeaderQuality.UNKNOWN
            model.backend.connection != com.no5.helm.model.ConnectionState.LIVE -> HeaderQuality.LOST
            else -> HeaderQuality.valueOf(gps.status.name)
        },
        provenance = if (gps?.sourceId != null && gps.provenance == com.no5.helm.ui.NativeGpsProvenance.REAL)
            HeaderProvenance.REAL else HeaderProvenance.UNKNOWN,
        sourceLabel = gps?.label)
}
