package com.no5.helm

import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.no5.helm.video.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import com.no5.ui.No5Text
import com.no5.ui.No5ActionFeedback
import com.no5.ui.no5Clickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.no5.domain.*
import com.no5.helm.network.NetworkSourceSessionFactory
import com.no5.helm.numeric.*
import com.no5.helm.ocean.*
import com.no5.helm.ocean.gl.*
import com.no5.helm.security.AndroidGatewaySecurityStore
import com.no5.helm.security.AndroidGatewayTrustStore
import com.no5.helm.security.GatewayKeyImportResult
import com.no5.helm.security.GatewayKeyImporter
import com.no5.helm.security.GatewayKeyOpenDocumentContract
import com.no5.helm.security.SafGatewayKeyDocument
import com.no5.helm.security.GatewayTrustImportResult
import com.no5.helm.security.GatewayTrustImporter
import com.no5.helm.security.GatewayTrustOpenDocumentContract
import com.no5.helm.security.SafGatewayTrustDocument
import com.no5.helm.security.StoredGatewaySecurityProfileProvider
import com.no5.helm.diagnostics.GatewayDiagnosticExportFormat
import androidx.compose.ui.platform.LocalUriHandler
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import java.nio.charset.StandardCharsets
import kotlin.math.*

/** Binding-generation fence around the existing shared controllers; no audio or sonar commands here. */
internal class HelmKnobRouting(private val audio:AudioControlActor,private val sonar:SonarControlActor,
                               private val controller:SonarNativeController):AutoCloseable {
    private val lock=Any()
    private var epoch=0L
    private var closed=false
    private val audioReceipts=audio.action(com.no5.control.NormalizedKnobInput.LEFT_CW)
    private val sonarReceipts=sonar.action(com.no5.control.NormalizedKnobInput.LEFT_CW)
    private val modeBoundary=controller.observeKnobModeBoundary{retire()}
    private fun cancelTargets(){audio.cancel();sonar.cancel()}
    /** MODE invalidation retires old callback objects even before Compose republishes its lease. */
    fun retire()=synchronized(lock){epoch++;cancelTargets()}
    fun bind(page:String,active:Boolean=true,sourceGeneration:Long=1L):Map<com.no5.control.NormalizedKnobInput,com.no5.appcontrol.BufferedKnobAction> = synchronized(lock){
        epoch++;cancelTargets()
        if(closed||!active||page !in setOf("SONAR","AUDIO","HELM","DASHBOARD"))return emptyMap()
        audio.observe(sourceGeneration,true);sonar.observe()
        val bindingEpoch=epoch
        val mode=controller.knobMode
        val useSonar=page=="SONAR"&&mode==SonarKnobMode.SONAR
        com.no5.control.NormalizedKnobInput.entries.associateWith{input->
            val action=if(useSonar || page=="SONAR"&&input==com.no5.control.NormalizedKnobInput.LEFT_LONG_PUSH)sonar.action(input)else audio.action(input)
            object:com.no5.appcontrol.BufferedKnobAction {
                override fun submit(sequence:Long,physicalUptimeMs:Long):Long=synchronized(lock){
                    if(closed||epoch!=bindingEpoch||page=="SONAR"&&controller.knobMode!=mode)0L else action.submit(sequence,physicalUptimeMs)
                }
                // A mode/lease replacement must not strand the receipt of the previous target.
                override fun writeResult(commandId:Long,reply:android.os.Parcel)=
                    if(commandId>=AudioControlActor.PREFIX)audioReceipts.writeResult(commandId,reply)else sonarReceipts.writeResult(commandId,reply)
                override fun cancel()=synchronized(lock){
                    // Focus loss cancels work while retaining this mapping for a fresh trusted
                    // lease on return. Replacement mappings have their own newer epoch.
                    if(epoch==bindingEpoch)cancelTargets()
                }
            }
        }
    }
    override fun close(){synchronized(lock){if(closed)return;closed=true;epoch++;cancelTargets()};modeBoundary.close()}
}

class MainActivity : ComponentActivity() {
    internal var sonarWatchdogForDiagnostics: com.no5.helm.watchdog.SonarWatchdogBinding? = null
    internal var windowRecoveryForDiagnostics: no5.sonar.windowrecovery.WindowRecoveryBinding? = null
    override fun dump(prefix: String, fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        if (args?.contains("--sonar-window-recovery") == true) {
            writer.println(prefix + (windowRecoveryForDiagnostics?.diagnosticLine() ?: "No5WindowRecovery: NO_ACTIVE_BINDING"))
            writer.println(prefix + (sonarWatchdogForDiagnostics?.diagnosticLine() ?: "No5SonarWatchdog: NO_ACTIVE_BINDING"))
            writer.println(prefix + (sonarWatchdogForDiagnostics?.gpuDiagnosticLine() ?: "No5SonarGpu: NO_ACTIVE_BINDING"))
            return
        }
        super.dump(prefix, fd, writer, args)
    }

    internal val presentationModels by lazy { ViewModelProvider(this)[HelmPresentationViewModel::class.java] }
    internal lateinit var uiStateClient: com.no5.ui.No5UiClient
        private set
    internal var uiSnapshot by mutableStateOf<com.no5.ui.UiStateSnapshot?>(null)
        private set
    internal var uiLanguage by mutableStateOf("de")
        private set
    internal var uiConnected by mutableStateOf(false)
        private set
    internal var requestedPage by mutableStateOf<String?>(null)
    // Main-owned committed route; it is not a second observable UI state.
    internal var committedPageForIntents:String?=null
    internal var samePageIntentNeedsRouting=false
    private val uiObserver = com.no5.ui.No5UiClient.Observer { snapshot ->
        val languageChanged = uiLanguage != snapshot.language
        uiSnapshot = snapshot; uiLanguage = snapshot.language; uiConnected = snapshot.connected
        if (languageChanged) com.no5.helm.control.HelmSourceService.refreshNotificationLanguage(this)
    }
    internal fun acceptPageIntent(value: Intent) {
        value.getStringExtra(com.no5.navigation.AppNavigation.OPEN_PAGE)?.takeIf {
            it in setOf("DASHBOARD", "HELM", "AUDIO", "SONAR", "SETTINGS")
        }?.let {
            // A repeated launcher/deep-link must not publish a transient root
            // route for a page already committed. Preserve a newer contrary
            // request and the existing close-panel behavior of SONAR/AUDIO.
            if(requestedPage!=null || it!=committedPageForIntents || samePageIntentNeedsRouting)
                requestedPage=it
        }
        value.removeExtra(com.no5.navigation.AppNavigation.OPEN_PAGE)
    }
    internal val systemKnobs by lazy { com.no5.appcontrol.ProductKnobClient(this) }
    private val ownedWindows by lazy { com.no5.ui.No5OwnedWindowFocus(this) { focused ->
        knobOwner.focus(focused); systemKnobs.onWindowFocusChanged(focused); headerProducer?.onWindowFocusChanged(focused)
    } }
    private val knobOwner = com.no5.helm.input.KnobForegroundOwner {
        com.no5.helm.input.HelmKnobInput.acquire(this, it)
    }
    internal fun bindKnobSource(source: com.no5.domain.AudioSourcePort): AutoCloseable = knobOwner.bind(source)
    val sourceSessions by lazy { ViewModelProvider(this)[SourceSessionViewModel::class.java] }
    internal val backgroundSettings by lazy { ViewModelProvider(this)[BackgroundSettingsViewModel::class.java] }
    private var headerProducer: HelmHeaderProducer? = null
    internal fun updateHeaderPage(page: String, night: Boolean, status: String?) =
        headerProducer?.updateUi(page, night, status)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiStateClient = com.no5.ui.No5UiClient(this)
        uiSnapshot = uiStateClient.snapshot; uiLanguage = uiSnapshot!!.language
        uiStateClient.addObserver(uiObserver)
        if (savedInstanceState == null) acceptPageIntent(intent)
        // Keep the first Activity turn cheap. Android must be able to run Service.onCreate
        // and promote it before attaching the complete Compose/semantics tree.
        setContentView(android.widget.TextView(this).apply {
            text = "No5 HELM · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.rgb(9, 20, 31))
            gravity = android.view.Gravity.CENTER
        })
        immersiveWindow = ImmersiveWindow(this)
        if (!com.no5.helm.control.HelmSourceService.startFromForeground(this)) {
            setContent { Text(com.no5.ui.No5Text.ui("No5 Systemsteuerung nicht verfügbar. HELM erneut öffnen.")) }
            return
        }
        lifecycleScope.launch {
            combine(com.no5.helm.control.HelmSourceService.foregroundReadiness,
                com.no5.helm.control.HelmSourceService.foregroundStartupFailure) { _, failed ->
                failed || com.no5.helm.control.HelmSourceService.isForegroundReady(this@MainActivity)
            }.first { it }
            // Finish the service callback before the expensive initial composition.
            yield()
            if (!com.no5.helm.control.HelmSourceService.isForegroundReady(this@MainActivity)) {
                setContent { Text(com.no5.ui.No5Text.ui("No5 Systemsteuerung nicht verfügbar. HELM erneut öffnen.")) }
                return@launch
            }
            showProductContent()
        }
    }
    private fun showProductContent() {
        val sessions = sourceSessions
        sessions.session // Borrow only from the initialized foreground owner.
        val headerClient = com.no5.header.android.HeaderAndroidClient(this, com.no5.header.HeaderPage.HELM) {
            headerProducer?.onAdmission(it)
        }
        headerProducer = HelmHeaderProducer(headerClient, sessions,
            getSharedPreferences("no5_demo", Context.MODE_PRIVATE).getBoolean("night", false))
        setContent { No5App(sessions, backgroundSettings) }
        // The Activity may already have resumed while waiting for the service.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            headerProducer?.onWindowFocusChanged(ownedWindows.focused())
            headerProducer?.onResume()
        }
    }
    private var immersiveWindow: ImmersiveWindow? = null
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Launcher-like repeated starts are routed here by singleTask. Retain the active page,
        // controllers and source session. Only an explicit page request changes the view.
        setIntent(intent)
        acceptPageIntent(intent)
    }
    override fun onResume() { super.onResume(); if (::uiStateClient.isInitialized) uiStateClient.start(); systemKnobs.onResume(); knobOwner.resume(false); immersiveWindow?.onResume(); headerProducer?.onResume(); ownedWindows.onResume() }
    override fun onPause() { ownedWindows.onPause(); headerProducer?.onPause(); systemKnobs.onPause(); knobOwner.stop(); immersiveWindow?.onPause(); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        ownedWindows.windowChanged()
        immersiveWindow?.onWindowFocusChanged(hasFocus)
    }
    override fun onStop() { if (::uiStateClient.isInitialized) uiStateClient.stop(); knobOwner.stop(); super.onStop() }
    override fun onDestroy() { if (::uiStateClient.isInitialized) { uiStateClient.removeObserver(uiObserver); uiStateClient.close() }; ownedWindows.close(); headerProducer?.close(); headerProducer = null; systemKnobs.close(); knobOwner.close(); immersiveWindow?.close(); super.onDestroy() }
}

@Composable
fun No5App(backgroundModel: BackgroundSettingsViewModel? = null) {
    val context=LocalContext.current
    val sourceOwner=remember(context.applicationContext) { CompositionSourceSessionOwner(context.applicationContext) }
    DisposableEffect(sourceOwner) { onDispose { sourceOwner.close() } }
    No5AppContent(sourceOwner, backgroundModel)
}

/** Product entry point: source ownership survives recomposition and configuration recreation. */
@Composable
fun No5App(sourceSessions: SourceSessionViewModel, backgroundModel: BackgroundSettingsViewModel? = null) {
    val sourceOwner=remember(sourceSessions) { ViewModelSourceSessionOwner(sourceSessions) }
    No5AppContent(sourceOwner, backgroundModel)
}

private interface SourceSessionOwner : AutoCloseable {
    val changes: SourceFeed<Long>? get() = null
    val homeIdentity: String? get() = null
    val gateway: com.no5.gateway.LuminaGatewayClient? get() = null
    val config: SourceConfig
    val session: No5SourceSession
    val settingsWarning: String?
    val endpointHistory: List<String>
    fun apply(requested: SourceConfig): SourceApplyResult
    fun reload(): SourceApplyResult
    fun synchronizeHome(): Boolean = false
}

private class ViewModelSourceSessionOwner(private val model: SourceSessionViewModel) : SourceSessionOwner {
    override val changes get() = model.changes
    override val homeIdentity get() = model.homeIdentity
    override val gateway get() = model.gateway
    override val config get() = model.config
    override val session get() = model.session
    override val settingsWarning get() = model.settingsWarning
    override val endpointHistory get() = model.endpointHistory
    override fun apply(requested: SourceConfig) = model.apply(requested)
    override fun reload() = model.reload()
    override fun synchronizeHome() = model.synchronizeHome()
    override fun close() = Unit
}

/** Explicit test/preview fallback; production passes MainActivity's ViewModel above. */
private class CompositionSourceSessionOwner(context: android.content.Context) : SourceSessionOwner {
    private val store = AndroidSourceSettingsStore(context)
    private val factory = NetworkSourceSessionFactory(
        securityProfiles = StoredGatewaySecurityProfileProvider(
            AndroidGatewaySecurityStore(context), AndroidGatewayTrustStore(context),
        ),
        endpointResolver = com.no5.helm.network.AndroidGatewayEndpointResolver(context.applicationContext),
    )
    private val coordinator = SourceCoordinator(store, factory = factory)
    private var warning: String? = store.warning
    override val config get() = coordinator.config
    override val session get() = coordinator.session
    override val settingsWarning get() = warning
    override val endpointHistory get() = store.endpointHistory()
    override fun apply(requested: SourceConfig): SourceApplyResult {
        val result = coordinator.apply(requested)
        if (!result.applied) return result
        if (!result.changed && warning != null) {
            try { store.save(coordinator.config) }
            catch (failure: RuntimeException) {
                return SourceApplyResult(false, error = failure.message ?: "Cannot repair source settings")
            }
        }
        warning = result.warning ?: store.warning
        return result
    }
    override fun reload(): SourceApplyResult {
        val result = coordinator.reload()
        warning = result.warning ?: store.warning
        return result
    }
    override fun close() {
        try { coordinator.close() } finally { factory.close() }
    }
}

@Composable
private fun No5AppContent(sourceOwner: SourceSessionOwner, backgroundModel: BackgroundSettingsViewModel?) {
    val context=LocalContext.current
    val activity = context as? MainActivity
    val uiLanguage = activity?.uiLanguage ?: com.no5.ui.UiLanguage.current(context)
    val retained = activity?.presentationModels
    // Keep live vessel telemetry scoped to the only pages that display it. AUDIO owns its
    // pointer loop and must not be invalidated by the 20 Hz instrument source.
    var page by rememberSaveable { mutableStateOf(retained?.page ?: "HELM") }
    val installedVersion=remember(context) { com.no5.releaseinfo.ReleaseInfo.shortBuildLabel(context, "HELM") }
    val preferences=remember{context.getSharedPreferences("no5_demo",android.content.Context.MODE_PRIVATE)}
    val audioPresentationStore = remember(context.applicationContext) { AndroidAudioPresentationStore(context.applicationContext) }
    var audioPresentation by remember(audioPresentationStore) { mutableStateOf(audioPresentationStore.load()) }
    var sourceConfig by remember { mutableStateOf(sourceOwner.config) }
    var endpointHistory by remember { mutableStateOf(sourceOwner.endpointHistory) }
    var settingsWarning by remember { mutableStateOf(sourceOwner.settingsWarning) }
    var showSettingsWarning by remember { mutableStateOf(settingsWarning != null) }
    var session by remember { mutableStateOf(sourceOwner.session) }
    DisposableEffect(sourceOwner) {
        val subscription = sourceOwner.changes?.subscribe {
            sourceConfig = sourceOwner.config; settingsWarning = sourceOwner.settingsWarning
            endpointHistory = sourceOwner.endpointHistory; session = sourceOwner.session
        }
        onDispose { subscription?.close() }
    }
    val gateway = sourceOwner.gateway
    var gatewayStatus by remember(gateway) { mutableStateOf(gateway?.status) }
    DisposableEffect(gateway) {
        val observation = gateway?.observe { gatewayStatus = it }
        onDispose { observation?.close() }
    }

    DisposableEffect(context, session) {
        val binding = (context as? MainActivity)?.bindKnobSource(session.audio)
        onDispose { binding?.close() }
    }
    val retainedControllers = remember(session, retained) { retained?.acquire(session, audioPresentation) }
    val audioController = remember(session) { retainedControllers?.audio ?: AudioNativeController(source = session.audio, initialPresentation = audioPresentation) }
    val sonarController = remember(session) { retainedControllers?.sonar ?: SonarNativeController(source = session.sonar) }
    DisposableEffect(audioController, sonarController) {
        onDispose {
            // Each controller retires independently; an external adapter cannot block its peers.
            if (retained == null) { runCatching { audioController.dispose() }; runCatching { sonarController.dispose() } }
        }
    }
    SonarAcquisition(sonarController)
    var helmNow by remember { mutableStateOf(System.currentTimeMillis()) }
    var state by remember(session) { mutableStateOf(session.helm.snapshots.value) }
    val presentation = remember(session) {
        HelmPresentationModel(session.helm.snapshots.value, System.nanoTime() / 1_000_000)
    }
    // Read this state in Canvas draw, so display frames do not recompose every control.
    val displayState = remember(session) { mutableStateOf(session.helm.snapshots.value) }
    val displayQuality = remember(session) { mutableStateOf(session.helm.snapshots.value.quality.status) }
    val depthHistory = remember(session) { retainedControllers?.depthHistory ?: DepthHistory() }
    val speedExtrema = remember(session) { retainedControllers?.speedExtrema ?: SpeedExtrema() }
    var historyRevision by remember(session) { mutableLongStateOf(depthHistory.version()) }
    val retainedHistory=remember(session) { mutableStateOf(depthHistory.snapshot(System.currentTimeMillis(),
        DepthHistory.status(state, System.currentTimeMillis(), session.helm.freshnessPolicy))) }
    var weather by remember(session) { mutableStateOf(session.weather.snapshots.value) }
    var weatherDetails by remember(session) { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()
    // Retain the child route across Activity recreation. It owns no transports itself; the
    // Activity ViewModel remains the sole source-session owner.
    var gatewayConnectionOpen by rememberSaveable { mutableStateOf(false) }
    val backgroundOwner = backgroundModel ?: (context as MainActivity).backgroundSettings
    val backgroundSelection = backgroundOwner.selection
    val videoState=remember(backgroundSelection.activeVideoUri){VideoState()}
    var videoPickError by remember {mutableStateOf<String?>(null)}
    val videoPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){
            try {
                require(uri.scheme=="content") { "Choose a document from the Android file picker" }
                context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                backgroundSelection.selectVideo(uri.toString());videoPickError=null
            } catch(e:Exception){videoPickError="Video could not be selected: ${e.message?.take(140)}"}
        }
    }
    val oceanDiagnostics = remember { Channel<String>(Channel.CONFLATED) }
    var oceanDiagnostic by remember { mutableStateOf<String?>(null) }
    // Renderer lifetime spans theme and page changes. The callback only enqueues diagnostics;
    // Compose state is updated later by the collector, never from native Canvas.draw.
    val oceanRenderer = remember(context) { OceanBackgroundRenderer(context, report = { oceanDiagnostics.trySend(it) }) }
    LaunchedEffect(oceanDiagnostics) {
        for (message in oceanDiagnostics) {
            oceanDiagnostic = message
            android.util.Log.w("No5Ocean", message)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var helmActive by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(session, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            helmActive=lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (event == Lifecycle.Event.ON_START) helmNow=System.currentTimeMillis()
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_START) {
                if(BuildConfig.DEBUG)android.util.Log.i("No5HelmRecords", "${event.name} ${HelmRecordCounters.report()}")
                presentation.reset(state, System.nanoTime() / 1_000_000)
                displayState.value = state
                depthHistory.markGap()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val instrumentTelemetryVisible = page == "HELM" || page == "DASHBOARD"
    DisposableEffect(session, helmActive, instrumentTelemetryVisible) {
        session.helm.setUiDemand(helmActive && instrumentTelemetryVisible)
        onDispose { session.helm.setUiDemand(false) }
    }
    DisposableEffect(session, instrumentTelemetryVisible) {
        val observedSession = session
        val active = AtomicBoolean(true)
        val sourceDelivery = MainSourceDelivery()
        val weatherDelivery = MainSourceDelivery()
        fun acceptHelm(next: HelmState) {
            if (active.get() && sourceOwner.session === observedSession) {
                if (presentation.accept(next, System.nanoTime() / 1_000_000)) {
                    // Publish the source and its display clock in the same main delivery.
                    // A subsequent effect must not recompose the entire host a second time.
                    state = next
                    helmNow = System.currentTimeMillis()
                    depthHistory.accept(next, System.currentTimeMillis(), session.identity.sessionId, session.helm.freshnessPolicy)
                    historyRevision=depthHistory.version()
                    speedExtrema.accept(next, System.currentTimeMillis(), session.identity.sessionId, session.helm.freshnessPolicy)
                }
            }
        }
        if (instrumentTelemetryVisible) acceptHelm(observedSession.helm.snapshots.value)
        val subscription = if (instrumentTelemetryVisible) {
            observedSession.helm.snapshots.subscribe {
                val next = it
                sourceDelivery.deliver { acceptHelm(next) }
            }
        } else null
        val weatherSubscription = if (instrumentTelemetryVisible) {
            observedSession.weather.snapshots.subscribe {
                val next = it
                weatherDelivery.deliver {
                    if (active.get() && sourceOwner.session === observedSession) {
                        weather = next
                        helmNow = System.currentTimeMillis()
                    }
                }
            }
        } else null
        onDispose {
            active.set(false); subscription?.close(); weatherSubscription?.close()
            sourceDelivery.close(); weatherDelivery.close()
        }
    }
    var night by remember { mutableStateOf(preferences.getBoolean("night", false)) }
    val samePageIntentNeedsRouting=(page=="SONAR"&&sonarController.drawer!=null)||
        (page=="AUDIO"&&audioController.panel!=null)
    SideEffect {
        activity?.committedPageForIntents=page
        activity?.samePageIntentNeedsRouting=samePageIntentNeedsRouting
    }
    var settingsNavigationGuard by remember { mutableStateOf<((() -> Unit) -> Unit)?>(null) }
    var audioNavigationGuard by remember { mutableStateOf<((() -> Unit) -> Unit)?>(null) }
    fun leavePage(change: () -> Unit) {
        val guard = when (page) { "SETTINGS" -> settingsNavigationGuard; "AUDIO" -> audioNavigationGuard; else -> null }
        if (guard != null) guard(change) else change()
    }
    fun navigatePage(next: String) {
        if (next != page) leavePage { audioController.closePanel(); sonarController.close(); page = next }
        else if(next=="AUDIO"&&audioController.panel!=null)leavePage{audioController.closePanel()}
        else if(next=="SONAR"&&sonarController.drawer!=null)sonarController.close()
    }
    // A physical long press opens the same controller panel as touch. Show that panel
    // on AUDIO without closing the freshly opened panel during the page transition.
    LaunchedEffect(page, audioController.panel) {
        if ((page == "HELM" || page == "DASHBOARD" || page == "SONAR") && audioController.panel != null) {
            leavePage { sonarController.close(); page = "AUDIO" }
        }
    }
    val wantsSonarProfiles = page == "SONAR" || page == "DASHBOARD"
    val profileDemand = remember(sonarController, lifecycle) {
        SonarProfileDemandBinding(lifecycle, sonarController::screenVisible)
    }
    val gpuPipelinePrepared=sonarController.gpuPipelinePrepared
    SideEffect { profileDemand.showProfiles(wantsSonarProfiles,gpuPipelinePrepared) }
    DisposableEffect(profileDemand) { onDispose { profileDemand.close() } }
    val incomingPage = activity?.requestedPage
    LaunchedEffect(incomingPage) {
        incomingPage?.let { navigatePage(it); activity?.requestedPage = null }
    }
    // Persist presentation changes, not the raw acoustic/telemetry clock. In particular,
    // an incoming SONAR ping must not recompose AUDIO and its local pointer target.
    val audioViewState by remember(audioController) { derivedStateOf(structuralEqualityPolicy()) { audioController.exportPresentationState() } }
    val sonarViewState by remember(sonarController) { retainedSonarPresentation(sonarController) }
    LaunchedEffect(page, audioViewState, sonarViewState) { retained?.save(page, audioViewState, sonarViewState) }
    // API29 Window compatibility only. Source/model/renderer output is untouched.
    val sonarWatchdog = remember(activity?.window) {
        if (android.os.Build.VERSION.SDK_INT == 29)
            activity?.let { com.no5.helm.watchdog.SonarWatchdogBinding(it,sonarController) }
        else null
    }
    val windowRecovery = sonarWatchdog?.window
    DisposableEffect(sonarWatchdog) { onDispose {
        sonarWatchdog?.close()
        activity?.let { if (it.windowRecoveryForDiagnostics === windowRecovery) it.windowRecoveryForDiagnostics = null }
    } }
    DisposableEffect(windowRecovery, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY)
                sonarWatchdog?.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); sonarWatchdog?.pause() }
    }
    // Read high-rate model state inside a coroutine Snapshot observer, not the root composition.
    SideEffect {
        activity?.windowRecoveryForDiagnostics = windowRecovery
        activity?.sonarWatchdogForDiagnostics = sonarWatchdog
        sonarWatchdog?.bindController(sonarController)
    }
    LaunchedEffect(sonarWatchdog, sonarController, wantsSonarProfiles, lifecycle) {
        if (sonarWatchdog == null || !wantsSonarProfiles) {
            sonarWatchdog?.pause()
        } else try {
            snapshotFlow {
                val snapshot = sonarController.snapshot
                val source = sonarController.sourceStatus
                val admitted = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    sonarController.liveEvidence && snapshot.pingId!=null && snapshot.timestampEpochMs!=null &&
                    snapshot.connected && snapshot.running &&
                    !snapshot.demo && !snapshot.simulated && !snapshot.display.freeze &&
                    sonarController.model.selectedPingSource == com.no5.domain.SonarPingSource.OWN
                val sourceKey = listOf(sonarController.sourceIdentity.sessionId,
                    source.serverSessionId, source.serverGeneration,
                    source.displaySelection?.generation, sonarController.model.selectedPingSource).toString()
                Triple(sourceKey, snapshot, admitted)
            }.collect { (source, snapshot, admitted) ->
                sonarWatchdog.progress(source, snapshot.pingId, admitted, snapshot.timestampEpochMs ?: -1L)
            }
        } finally { sonarWatchdog.pause() }
    }
    val audioKnobActor=remember(audioController){AudioControlActor(audioController)}
    val sonarKnobActor=remember(sonarController){SonarControlActor(sonarController)}
    val knobRouting=remember(audioKnobActor,sonarKnobActor){HelmKnobRouting(audioKnobActor,sonarKnobActor,sonarController)}
    val knobSourceGeneration=sourceOwner.changes?.value?:1L
    ObserveAudioKnobActor(audioKnobActor,audioController,page,knobSourceGeneration)
    SideEffect {sonarKnobActor.observe();sonarController.knobLeaseReason=(context as? MainActivity)?.systemKnobs?.leaseReason}
    DisposableEffect(knobRouting){onDispose{knobRouting.close();audioKnobActor.close();sonarKnobActor.close()}}
    DisposableEffect(page, sonarController.knobMappingRevision, knobSourceGeneration, helmActive, knobRouting, context) {
        val knobs=(context as? MainActivity)?.systemKnobs
        val bindings=knobRouting.bind(page,helmActive,knobSourceGeneration)
        knobs?.requestLeaseBuffered(bindings)
        onDispose {knobRouting.retire();knobs?.releaseLease()}
    }
    // Header consumes the original raw feed in HelmHeaderProducer, never displayState/renderValues.
    SideEffect {
        (context as? MainActivity)?.updateHeaderPage(page, night,
            when (page) { "AUDIO" -> audioController.statusLabel; "SONAR" -> sonarController.statusText; else -> null })
    }
    val reducedMotion = rememberOceanReducedMotion()
    val hardwareAccelerated = LocalView.current.isHardwareAccelerated
    val oceanQuality by rememberOceanQuality(page == "HELM" && !night &&
        (backgroundSelection.active == BackgroundMode.ANIMATION || backgroundSelection.active == BackgroundMode.VIDEO) && !reducedMotion && hardwareAccelerated)
    val oceanGl = remember { OceanGlState() }
    val videoActive=videoEligible(page=="HELM",night,reducedMotion,hardwareAccelerated && oceanQuality != OceanQuality.STATIC,
        backgroundSelection.active==BackgroundMode.VIDEO,backgroundSelection.activeVideoUri,videoState)

    val glEligible = oceanGlEligible(backgroundSelection.active, night, reducedMotion, hardwareAccelerated, oceanQuality, oceanGl) && page == "HELM"
    val oceanInputs = oceanSourceInputs(session.identity.sessionId, state, session.helm.freshnessPolicy,
        weather, session.weather.freshnessPolicy, helmNow)
    val oceanFrame = rememberOceanFrame(oceanInputs, backgroundSelection.active, night, page == "HELM",
        oceanRenderer, oceanQuality, reducedMotion, hardwareAccelerated, glEligible)
    fun selectTheme(selectedNight: Boolean) {
        night = selectedNight
        preferences.edit().putBoolean("night", night).apply()
    }
    LaunchedEffect(session,state,weather,helmActive) {
        if(helmActive)while(true) {
            val now=System.currentTimeMillis()
            val deadline=HelmFreshnessDeadline.next(state,weather,session.helm.freshnessPolicy,session.weather.freshnessPolicy,now)
                ?: break
            delay((deadline-now).coerceAtLeast(1))
            // Only a real freshness boundary needs a timed clock invalidation.
            helmNow=System.currentTimeMillis()
        }
    }
    LaunchedEffect(session,state,helmNow,helmActive,page) {
        if(helmActive) {
            fun update() {
                val status=QualityEvaluator.evaluate(state.quality,System.currentTimeMillis(),session.helm.freshnessPolicy)
                displayState.value=presentation.render(System.nanoTime()/1_000_000,status)
                displayQuality.value=status
            }
            update()
            while(presentation.isTransitioning())withFrameNanos { update() }
        }
    }
    val depthStatus=DepthHistory.status(state,helmNow,session.helm.freshnessPolicy)
    LaunchedEffect(session,historyRevision,depthStatus,helmActive) {
        if(helmActive)while(true) {
            val now=System.currentTimeMillis()
            retainedHistory.value=depthHistory.snapshot(now,depthStatus)
            val deadline=depthHistory.nextSnapshotChange(now)?:break
            delay((deadline-now).coerceAtLeast(1))
        }
    }
    // Detail panel and dashboard consume the same projections and the existing display clock.
    // Raw state remains the sole owner of observation times/field quality; motion changes no evidence.
    val slow=remember(state,weather,helmNow,sourceConfig.displayUnits,session) {
        slowPresentation(state,helmNow,session.helm.freshnessPolicy,weather,session.weather.freshnessPolicy,sourceConfig.displayUnits)
    }
    val motion=remember(displayState,sourceConfig.displayUnits,session) { derivedStateOf {
        displayQuality.value
        helmNow
        motionPresentation(displayState.value,System.currentTimeMillis(),session.helm.freshnessPolicy,sourceConfig.displayUnits)
    } }
    val reading=remember(state,helmNow,sourceConfig.displayUnits,session) {
        SpeedDepthInstrumentGeometry.reading(state,sourceConfig.displayUnits,slow.values.quality,helmNow,session.helm.freshnessPolicy)
    }
    val weatherView=remember(weather,helmNow,session) {WeatherPresentation.from(weather,helmNow,session.weather.freshnessPolicy)}
    val sharedPanel=rememberHelmPanelPresentation(state,helmNow,session.helm.freshnessPolicy,
        slow,motion,reading,weatherView)
    val audioChromeStatus=remember(audioController){derivedStateOf{audioController.statusLabel}}
    val sonarChromeStatus=remember(sonarController){derivedStateOf{sonarController.statusText}}
    val latestHeaderPanel=rememberUpdatedState(sharedPanel)
    val chromeValues=remember{derivedStateOf{headerChromeValues(latestHeaderPanel.value.value.headerValues)}}
    val historyClock=rememberInstrumentHistoryClock(retainedHistory.value,helmActive&&(page=="HELM"||page=="DASHBOARD"))
    val colors=rememberHelmColorScheme(night)
    com.no5.ui.No5LocaleScope(uiLanguage, onLanguageChange = if (activity?.uiConnected == true) ({ activity.uiStateClient.setLanguage(it) }) else null) {
    MaterialTheme(colorScheme=colors){
        val scene=remember(night, oceanRenderer, uiLanguage){HelmScene(context,night,oceanRenderer)}
        val knobOverlay=remember(context){KnobOverlay(context)}
        // A fixed physical design stage must never mirror its native hit regions in RTL locales.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr,
            com.no5.ui.LocalNo5ActionFeedbackColor provides colors.primary) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("helm-physical-stage")){
            val scale=DesignViewport(maxWidth.value,maxHeight.value).scale
            Box(Modifier.size((2000*scale).dp,(1200*scale).dp).align(Alignment.Center)){
                if (page == "HELM") OceanBackgroundLayer(scene, backgroundSelection.active,
                    reducedMotion, oceanFrame, Modifier.fillMaxSize(), (glEligible && oceanGl.ready) || (videoActive && videoState.ready))
                if (glEligible) OceanGlLayer(scene, oceanFrame, oceanGl, scale)
                if (videoActive) key(backgroundSelection.activeVideoUri,videoState.revision) {
                    VideoLayer(scene,oceanInputs,backgroundSelection.activeVideoUri!!,videoState,scale)
                }
                if(page=="HELM") {
                    HelmStaticLayer(scene,sourceConfig.displayUnits)
                    val history=retainedHistory.value
                    val extrema=remember(state) { speedExtrema.snapshot() }
                    HelmSlowLayer(scene,slow)
                    HelmInstrumentTelemetryLayer(scene,reading.copy(speed=if(reading.speed!=null)0.0 else null,fraction=null,aboveScale=false),sourceConfig.displayUnits,extrema,state.quality.simulated)
                    HelmHistoryLayer(scene,history,reading.depthStatus,helmActive,scale,historyClock)
                    HelmSpeedBandLayer(scene,motion)
                    HelmMotionLayer(scene,motion)
                    HelmStaticOverlay(scene,sourceConfig.displayUnits)
                } else if(page=="AUDIO" && audioController.panel in setOf("sound-preset","sound-speakers")) {
                    SoundHeaderLayer(scene,night,scale,chromeValues,{audioChromeStatus.value},
                        "NO5 $page native screen. Source ${sourceConfig.mode}.",LocalSoundHeaderCacheEnabled.current)
                } else Canvas(Modifier.fillMaxSize().graphicsLayer().semantics{contentDescription="NO5 $page native screen. Source ${sourceConfig.mode}."}){
                    // Observe raw age transitions even for stationary values/no new samples.
                    displayQuality.value
                    val frameRender = chromeValues.value
                    drawIntoCanvas { canvas ->
                        val c=canvas.nativeCanvas
                        c.save();c.scale(size.width/2000f,size.height/1200f);c.clipRect(0f,0f,2000f,1200f)
                        when (page) {
                            "DASHBOARD", "AUDIO", "SONAR", "SETTINGS" -> {
                                c.drawColor(if (night) android.graphics.Color.BLACK else android.graphics.Color.rgb(246,249,250))
                                scene.drawChrome(c, page, frameRender, when(page) { "AUDIO" -> audioChromeStatus.value; "SONAR" -> sonarChromeStatus.value; else -> "GATEWAY · ${frameRender.quality}" })
                            }
                            else -> scene.drawStaged(c, page)
                        }
                        c.restore()
                    }
                }
                if (page == "AUDIO") AudioScreen(
                    scale = scale,
                    night = night,
                    controller = audioController,
                    presentation = audioPresentation,
                    registerNavigationGuard = { audioNavigationGuard = it },
                    onOpenDiagnostics = { leavePage {audioController.closePanel();gatewayConnectionOpen=true;page="SETTINGS"} },
                    onSavePresentation = { requested ->
                        audioPresentationStore.save(requested)
                        audioPresentation = requested
                        audioController.updatePresentation(requested)
                    },
                )
                // The SAME AndroidView/TextureView/EGL history survives SONAR<->DASHBOARD.
                // Only its logical plot rectangle and Canvas overlays change.
                if (wantsSonarProfiles) com.no5.helm.gpu.SonarGpuScenePlot(sonarController,night,
                    sourceConfig.displayUnits.depth,audioController,Modifier.fillMaxSize(),dashboardPreview=page=="DASHBOARD")
                if (page == "DASHBOARD") DashboardScreen(scale, night, state, weather, retainedHistory.value,
                    audioController, sonarController, helmNow, session.helm.freshnessPolicy, session.weather.freshnessPolicy,
                    sourceConfig.displayUnits, helmActive, reducedMotion, ::navigatePage,
                    helmPresentation=sharedPanel,historyClock=historyClock,externalSonarScene=true)
                if (page == "SONAR") SonarScreen(scale, night, sonarController, sourceConfig.displayUnits.depth, audioController,externalScene=true)
                if (page == "SETTINGS") ImeFormViewport(scale, 0f, 130f, 2000f, 928f) {
                    val helmFreshness = QualityEvaluator.evaluate(state.quality, helmNow, session.helm.freshnessPolicy)
                    val helmProvenance = if (state.quality.simulated) "SIMULATED" else "SOURCE"
                    val weatherPresentation = WeatherPresentation.from(weather, helmNow, session.weather.freshnessPolicy)
                    val weatherProvenance = if (weather.simulated) "SIMULATED" else "SOURCE"
                    val connectionStatus = "HELM · ${No5Text.ui(helmProvenance)} · ${No5Text.ui(helmFreshness.name)} | ${No5Text.ui("WEATHER")} · ${No5Text.ui(weatherProvenance)} · ${No5Text.ui(weatherPresentation.statusLabel)}"
                    if (gatewayConnectionOpen) LuminaGatewayConnectionScreen(
                        scale, night, gatewayStatus, connectionStatus,
                        onSetup = { gateway?.openSetup() },
                        onBack = { gatewayConnectionOpen = false },
                        onRefresh = { sourceOwner.synchronizeHome(); Unit },
                    ) else SourceSettingsScreenWithHistory(
                        scale = scale,
                        night = night,
                        config = sourceConfig,
                        bodyModifier = Modifier.fillMaxSize(),
                        endpointHistory = endpointHistory,
                        onApply = { requested ->
                            val result = sourceOwner.apply(requested)
                            if (!result.applied) throw IllegalStateException(result.error ?: "Settings were not applied")
                            settingsWarning = sourceOwner.settingsWarning
                            sourceConfig = sourceOwner.config
                            endpointHistory = sourceOwner.endpointHistory
                            session = sourceOwner.session
                        },
                        warning = settingsWarning,
                        connectionStatus = connectionStatus,
                        onOpenGatewayConnection = { gatewayConnectionOpen = true },
                        onLanguageSelected = { activity?.uiStateClient?.setLanguage(it) },
                        registerNavigationGuard = { settingsNavigationGuard = it },
                        background = backgroundSelection,
                        backgroundDescription = No5Text.ui("Aktiv: %s", (if(night)No5Text.ui("Nachts aus")+" · "else"")+
                            No5Text.ui(oceanRenderer.effectiveLabel(backgroundSelection.active, reducedMotion, oceanQuality, hardwareAccelerated))) +
                            (oceanGl.diagnostic?.let { " · $it" } ?: oceanDiagnostic?.let { " · $it" } ?: ""),
                        onSaveBackground = backgroundOwner::save,
                        onChooseVideo = { videoPicker.launch(arrayOf("video/*")) },
                        onRetryVideo = videoState::retry,
                        videoDescription = videoPickError ?: if(backgroundSelection.activeVideoUri==null) "No licensed footage included. Choose your own local file." else videoState.diagnostic,
                    )
                }
                fun Modifier.region(x:Float,y:Float,w:Float,h:Float)=offset((x*scale).dp,(y*scale).dp).size((w*scale).dp,(h*scale).dp)
                // Android accessibility prunes earlier interop siblings by bounds, even with a transparent full-width view.
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { com.no5.header.artwork.No5HeaderControlsView(it) },
                    modifier = Modifier.region(0f,0f,2000f,130f),
                    update = { view -> view.render(installedVersion, night, helmActive, ::selectTheme, language = uiLanguage) { details ->
                        activity?.let { com.no5.releaseinfo.ReleaseInfo.showDetailsWithWifi(it, night) }
                    } },
                )
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { com.no5.header.artwork.No5HeaderMediaView(it).apply {
                        onOpenAudio = { navigatePage("AUDIO") }
                    } },
                    modifier = Modifier.offset((282*scale).dp, (4*scale).dp)
                        .size(((if(page == "HELM")1050 else 232)*scale).dp, (104*scale).dp),
                    update = { view -> activity?.uiSnapshot?.let { view.render(it, night, helmActive, reducedMotion) } },
                )
                val knobValues=remember(knobOverlay,audioController,sonarController,page,sourceConfig.displayUnits.depth){derivedStateOf{
                    knobOverlay.values(audioController,if(page=="SONAR")sonarController else null,sourceConfig.displayUnits.depth,useSelectedAudioFader=true)
                }}
                Canvas(Modifier.fillMaxSize().graphicsLayer()) {
                    drawIntoCanvas { canvas ->
                        val c=canvas.nativeCanvas;c.save();c.scale(size.width/2000f,size.height/1200f)
                        knobOverlay.draw(c,night,knobValues.value,showMain=page!="AUDIO"||audioController.panel!="media")
                        c.restore()
                    }
                }
                if (gatewayStatus?.phase != com.no5.gateway.GatewaySessionPhase.READY) Text(gatewayStatus?.label ?: com.no5.ui.No5Text.ui("Lumina Control nicht verfügbar"),
                    Modifier.region(1000f,105f,620f,24f), color = if (night) Color(0xffaa6666) else Color(0xff23394b),
                    fontSize = (17f * scale).sp, maxLines = 1)
                // Accessibility subtracts this group's bounds from earlier siblings.
                // Keep its semantics within the footer, using local tab coordinates.
                Box(Modifier.region(BottomNavigation.left, BottomNavigation.top,
                    BottomNavigation.cellWidth * BottomNavigation.pages.size, BottomNavigation.height).selectableGroup()) {
                    BottomNavigation.pages.forEachIndexed { i, name ->
                        Box(Modifier.region(BottomNavigation.cellLeft(i) - BottomNavigation.left, 0f,
                            BottomNavigation.hitWidth, BottomNavigation.height)
                            .semantics { contentDescription = com.no5.ui.No5Text.text(context, name) }
                            // The native footer paints selection from the active route. A default
                            // Material indication leaves a second shade on a focused inactive tab.
                            .semantics { selected = page == name }
                            .no5Clickable(role = Role.Tab) { navigatePage(name) })
                    }
                }
                Box(Modifier.region(24f,4f,238f,104f)
                    .semantics { contentDescription = com.no5.ui.No5Text.ui("No5 HOME öffnen") }
                    .no5Clickable {
                        leavePage {
                        com.no5.navigation.AppNavigation.openApp(context, com.no5.navigation.AppNavigation.HOME, night)?.let {
                            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                        }
                        }
                    })
                if (page == "HELM") Box(Modifier.region(353f,814f,647f,96f)
                    .semantics { contentDescription = com.no5.ui.No5Text.ui("Wetterdetails und Quellenhinweise") }
                    .no5Clickable { weatherDetails = true })
            }
        }
        }
        if (weatherDetails) WeatherDetailsDialog(weather, helmNow, session.weather.freshnessPolicy, night) { weatherDetails = false }
        if (showSettingsWarning) SettingsReviewDialog(settingsWarning,night) { showSettingsWarning=false;page="SETTINGS" }
    }
    }
}

private fun gatewayKeyImportMessage(result: GatewayKeyImportResult): String = when (result) {
    GatewayKeyImportResult.Cancelled -> "Gateway key import cancelled."
    is GatewayKeyImportResult.Rejected -> result.message
    is GatewayKeyImportResult.StoreFailure -> result.message
    is GatewayKeyImportResult.Imported -> result.message()
}

private fun gatewayTrustImportMessage(result: GatewayTrustImportResult): String = when (result) {
    GatewayTrustImportResult.Cancelled -> "Gateway CA import cancelled."
    is GatewayTrustImportResult.Rejected -> result.message
    GatewayTrustImportResult.Imported -> "Gateway CA saved. After both CA and key are saved, close and reopen HELM to activate them; connection not checked."
}

internal fun format(value:Double,decimals:Int=1)=HelmNumberFormatter.format(value,decimals)
