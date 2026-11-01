package com.no5.helm.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.no5.helm.chart.*
import com.no5.ui.No5Text
import com.no5.ui.No5DetailsDialog
import com.no5.header.artwork.No5WorkspaceView
import com.no5.helm.model.*
import com.no5.preparation.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Production host: one engine supplied by the Activity, one renderer slot, five native screens. */
@Composable fun NativeHelmScreen(engine: ChartEngine, backend: StateFlow<HelmState>, endpoint: String,
    chartLabel: String, initialViewport: ChartViewport, chartLibrary: ChartLibraryActions? = null, onHomeGateway: (() -> Unit)? = null, onHeaderContext: ((NativeHeaderContext) -> Unit)? = null, headerSourceGeneration: Long = 0, routeActions: SignalKRouteActions? = null, versionLabel: String? = null, onVersionInfo: (() -> Unit)? = null, gatewayNotice: String? = null, onKnobBindings: ((Map<ChartKnobInput, (ChartKnobRequest) -> Unit>) -> Unit)? = null, onBufferedKnobBindings: ((Map<ChartKnobInput, ChartBufferedKnobAction>) -> Unit)? = null, mediaHeaderSlot: @Composable (Modifier, Boolean) -> Unit = { _, _ -> }, headerControlsSlot: (@Composable (Modifier, Boolean, (Boolean) -> Unit) -> Unit)? = null, positionController: MapPositionController? = null, onConnect: (String) -> Unit) {
    val context = LocalContext.current
    val uiLanguage = com.no5.ui.LocalNo5Language.current
    val scope = rememberCoroutineScope()
    val chart by engine.status.collectAsStateWithLifecycle()
    val navigationRegion = remember(chart.navigationBounds) { com.no5.helm.chart.ChartCameraBounds.from(chart.navigationBounds) }
    val libraryState = chartLibrary?.state?.collectAsStateWithLifecycle()?.value
    val helm by backend.collectAsStateWithLifecycle()
    val savedStyles = remember(engine) { SavedChartProfiles(context.getSharedPreferences("chart-presentation-v1", Context.MODE_PRIVATE)) }
    val preferences = remember { context.getSharedPreferences("native-screens-v1", Context.MODE_PRIVATE) }
    val localPositionPreferences = remember { MapPositionPreferences(context.getSharedPreferences("map-position-v1", Context.MODE_PRIVATE)) }
    val positions = positionController ?: remember(engine) { MapPositionController(ChartPositionCamera(engine), localPositionPreferences.load(), localPositionPreferences::save) }
    val position by positions.state.collectAsStateWithLifecycle()
    val positionControls = remember(positions, engine) { MapPositionControls(positions, engine) }
    SideEffect { positionControls.observe() }
    DisposableEffect(positionControls) { onDispose {
        positionControls.close(); positions.touchActivity(false, System.currentTimeMillis())
    } }
    LaunchedEffect(positions, positionController) {
        if (positionController == null) kotlinx.coroutines.coroutineScope {
            launch { backend.collect { positions.update(it, System.currentTimeMillis()) } }
            launch { while (true) { positions.tick(System.currentTimeMillis()); delay(1000) } }
        }
    }
    var routeMapPick by rememberSaveable{mutableStateOf(false)}
    val taskExit=remember{MapTaskExitGate()}
    var settingsLibraryRequested by rememberSaveable{mutableStateOf(false)}
    var showPositionSources by remember { mutableStateOf(false) }
    val workOverlayPreference = remember(preferences) { RendererWorkOverlayPreference(preferences) }
    var showWorkOverlay by remember(preferences) { mutableStateOf(workOverlayPreference.enabled) }
    var state by remember(engine) { mutableStateOf(NativeScreenState(shell = ShellState(
        destination = Destination.entries.firstOrNull { it.name == preferences.getString("lastDestination", "CHART") } ?: Destination.CHART,
        panelExpanded = preferences.getBoolean("panelExpanded", true)), settings = SettingsState(
        distanceUnit = runCatching { DistanceUnit.valueOf(preferences.getString("distanceUnit", "NAUTICAL_MILES")!!) }.getOrDefault(DistanceUnit.NAUTICAL_MILES),
        speedUnit = runCatching { SpeedUnit.valueOf(preferences.getString("speedUnit", "KNOTS")!!) }.getOrDefault(SpeedUnit.KNOTS),
        uiDimmingPercent = preferences.getInt("uiDimming", 100).coerceIn(30, 100)))) }
    LaunchedEffect(state.shell.destination, state.shell.panelExpanded) {
        preferences.edit().putString("lastDestination", state.shell.destination.name)
            .putBoolean("panelExpanded", state.shell.panelExpanded).apply()
    }
    val routeRepository = remember(context.filesDir) { LocalRouteRepository(File(context.filesDir, "route-data")) }
    val routeStore by routeRepository.state.collectAsStateWithLifecycle()
    val signalK = routeActions?.routeResources?.collectAsStateWithLifecycle()?.value ?: SignalKRoutesState(issue = "Signal-K-Routenservice nicht angeschlossen")
    fun remoteDocument(resource: SignalKRouteResource): RouteDocument {
        val id = "local-${resource.id}"
        val cached = routeStore.routes.find { it.id == id && it.source == signalK.origin }
        return RouteDocument(id, resource.name, FrozenList.copyOf(resource.points.map { RoutePoint(it.latitude, it.longitude, it.id, it.name) }),
            cached?.revision ?: 1, source = signalK.origin, signalKToken = resource.token, signalKResource = resource.resourceJson)
    }
    LaunchedEffect(signalK.origin, signalK.ready, signalK.routes, routeStore.ready) {
        if (signalK.ready && routeStore.ready && signalK.origin != null) {
            try { routeRepository.cache(requireNotNull(signalK.origin), signalK.routes.map(::remoteDocument)) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { android.util.Log.e("No5Routes", "Offline route cache unavailable", failure) }
        }
    }
    LaunchedEffect(routeRepository) { routeRepository.prepare() }
    val adapter = remember(engine) { NativeRendererAdapter(engine, initialViewport) }
    LaunchedEffect(engine) {
        // Retired user mode: a previously saved preference must never revive it.
        if(engine.status.value.touchOutlinesEnabled)adapter.setTouchOutlinesEnabled(false)
        if(preferences.contains("touchOutlinesEnabled"))preferences.edit().remove("touchOutlinesEnabled").apply()
    }
    var hasPresentedChart by remember(engine) { mutableStateOf(false) }
    LaunchedEffect(chart.chartLoaded, chart.surfaceAttached, chart.confirmedFrame, chart.sourcePresentationUnsupported,
        chart.residentCameraReady, chart.residentStartState) {
        if (!chart.chartLoaded || !chart.surfaceAttached || chart.sourcePresentationUnsupported || residentStartPending(chart)) hasPresentedChart = false
        else if (chart.confirmedFrame != null) hasPresentedChart = true
    }
    var layers by remember(engine) { mutableStateOf(actualLayers().let { initial -> initial.copy(layers = FrozenList.copyOf(initial.layers.map { layer ->
        if (layer.id in setOf("ownship", "ais", "waypoints", "route")) layer.copy(effective = preferences.getBoolean("overlay.${layer.id}", true)) else layer
    })) }) }
    val filterPreferences = remember(preferences) { ObjectFilterPreferences(preferences) }
    val dictionary = remember(context.assets) { runCatching { context.assets.open("s57objectclasses.csv").bufferedReader().use { filterDictionary(it.readText()) } }.getOrDefault(emptyMap()) }
    var filterBusy by remember(engine) { mutableStateOf(false) }
    var filterNotice by remember(engine) { mutableStateOf<String?>(null) }
    var filtersRestored by remember(engine) { mutableStateOf(false) }
    var savedProfiles by remember(engine) { mutableStateOf(savedStyles.profiles) }
    var styleBusy by remember { mutableStateOf(false) }
    var restoredStyle by remember(engine) { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }
    var showRenderer by remember { mutableStateOf(false) }
    var rendererSection by remember { mutableStateOf("menu") }
    LaunchedEffect(showRenderer){if(!showRenderer)rendererSection="menu"}
    var showBenchmarkSources by remember { mutableStateOf(false) }
    val benchmarkSourceDocuments = remember(context.assets) { runCatching {
        listOf("README.TXT", "USERAGREEMENT.TXT", "PROVENANCE.json").joinToString("\n\n") { name ->
            context.assets.open("renderer-benchmark/boston-v1/$name").bufferedReader().use { it.readText() }
        }
    }.getOrNull() }
    // Localize our caption on every confirmed language change; preserve original source documents.
    val benchmarkSources = benchmarkSourceDocuments?.let {
        No5Text.ui("Quelle: https://www.charts.noaa.gov/ENCs/ · Repository-Stand 03.10.2026")+"\n\n"+it
    } ?: No5Text.ui("NOAA-Quellenhinweise fehlen in dieser APK.")
    var showConnection by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(endpoint) }
    var showDetails by remember { mutableStateOf(false) }
    var selectedObject by remember(engine) { mutableStateOf<ChartObject?>(null) }
    var objectHits by remember(engine) { mutableStateOf(emptyList<ChartObject>()) }
    var objectTexts by remember(engine) { mutableStateOf(emptyList<ChartObjectText>()) }
    var objectQuery by remember(engine) { mutableStateOf<ChartObjectQuery?>(null) }
    var objectTotal by remember(engine) { mutableStateOf(0) }
    var objectNext by remember(engine) { mutableStateOf<Int?>(null) }
    var objectNotice by remember(engine) { mutableStateOf<String?>(null) }
    var objectNoticeRaw by remember(engine){mutableStateOf(false)}
    var objectBusy by remember(engine) { mutableStateOf(false) }
    var objectPanel by remember(engine) { mutableStateOf(false) }
    var objectInteraction by remember(engine) { mutableLongStateOf(0) }
    var overlayHits by remember(engine) { mutableStateOf(emptyList<NativeRendererAdapter.OverlayHit>()) }
    var overlayQuery by remember(engine) { mutableStateOf<ChartObjectQuery?>(null) }
    var routeDeleting by remember(engine) { mutableStateOf(false) }
    var routeDeleteError by remember(engine) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.routes.deleteCandidate?.id){routeDeleteError=null}
    fun closeObject() {
        objectInteraction++; selectedObject = null; objectHits = emptyList(); objectTexts = emptyList()
        objectPanel = false; objectBusy = false; objectQuery = null; objectNext = null; objectNotice = null
        scope.launch { engine.selectObject(null) }
    }
    fun chooseObject(obj: ChartObject) {
        val revision = objectInteraction
        objectBusy = true
        scope.launch {
            val accepted = engine.selectObject(obj.reference, objectQuery?.radiusSurfacePixels ?: 24.0 * context.resources.displayMetrics.density)
            if (revision != objectInteraction) return@launch
            objectBusy = false
            if (accepted) { selectedObject = obj; objectNotice = null; objectPanel = true; objectTexts = emptyList() }
            else {objectNoticeRaw=false;objectNotice = "Kartenstand hat sich geändert. Objekt erneut antippen."}
        }
    }
    fun receiveObjects(request: ChartObjectQuery?, result: ChartObjectQueryResult, append: Boolean = false) {
        if (!append) {
            objectInteraction++; selectedObject = null; objectTexts = emptyList(); objectHits = emptyList()
            objectQuery = request; objectPanel = true; objectTotal = 0; objectNext = null
            if (result.state != ChartObjectQueryState.PENDING || result.message != "Abfrage läuft") scope.launch { engine.selectObject(null) }
        }
        objectBusy = result.state == ChartObjectQueryState.PENDING && result.message == "Abfrage läuft"
        objectNoticeRaw=result.state==ChartObjectQueryState.FAILED&&result.message.isNotBlank()
        objectNotice = when (result.state) {
            ChartObjectQueryState.READY -> null
            ChartObjectQueryState.EMPTY -> "Keine auswählbare Tonne, kein Wrack oder Gebiet an diesem Kartenpunkt."
            ChartObjectQueryState.PENDING -> if (objectBusy) null else "Karte wird aktualisiert. Danach erneut antippen."
            ChartObjectQueryState.STALE -> "Der Kartenausschnitt oder Stil hat sich geändert. Erneut antippen."
            ChartObjectQueryState.UNAVAILABLE -> "Objektauswahl ist gerade nicht verfügbar."
            ChartObjectQueryState.FAILED -> result.message.ifBlank { "Objektabfrage fehlgeschlagen." }
        }
        if (result.state == ChartObjectQueryState.READY) {
            objectHits = if (append) (objectHits + result.objects).distinctBy { it.reference } else result.objects
            objectTotal = result.total; objectNext = result.nextOffset
            if (result.total == 1) chooseObject(result.objects.single())
        }
    }
    LaunchedEffect(chart.chartSourceGeneration, chart.rendererBenchmark.active) {
        if (chart.rendererBenchmark.active || (selectedObject?.reference?.sourceGeneration ?: objectQuery?.frame?.sourceGeneration)?.let { it != chart.chartSourceGeneration } == true) closeObject()
    }
    LaunchedEffect(selectedObject?.reference) {
        val obj = selectedObject ?: return@LaunchedEffect
        val names = listOfNotNull(obj.attributes["TXTDSC"], obj.attributes["NTXTDS"]).distinct()
        objectTexts = names.map { name -> chartLibrary?.readObjectText(obj.reference, obj.cellName, name) ?: ChartObjectText(name) }
    }
    var profiling by remember { mutableStateOf(false) }
    var benchmarkStarting by remember { mutableStateOf(false) }
    var currentViewVariant by remember { mutableStateOf(RendererViewportReuseVariant.OFF) }
    var currentViewDetailed by remember { mutableStateOf(false) }
    var currentViewUiRun by remember(engine) { mutableStateOf<String?>(null) }
    var currentViewReturnPanel by remember(engine) { mutableStateOf<Pair<Destination, Boolean>?>(null) }
    val measurementBusy = profiling || benchmarkStarting || chart.rendererBenchmark.active || chart.rendererBenchmarkRecoveryPending
    var restoredFastMode by remember(engine) { mutableStateOf(false) }
    LaunchedEffect(engine, chart.chartLoaded, chart.surfaceAttached, restoredStyle, measurementBusy, styleBusy) {
        if (!restoredFastMode && chart.chartLoaded && chart.surfaceAttached && restoredStyle && !measurementBusy && !styleBusy) {
            restoredFastMode = true
            if (preferences.getBoolean("fastChartModeEnabled", false) && android.os.Build.VERSION.SDK_INT >= 29) engine.setBufferTransformMode(ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE)
        }
    }
    var profileReport by remember { mutableStateOf("") }
    var reportBusy by remember { mutableStateOf(false) }
    var reportNotice by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<RendererReportExport?>(null) }
    var lastSource by remember { mutableStateOf<String?>(null) }
    fun message(value: String) { state = state.copy(shell = state.shell.copy(message = value.takeIf { it.isNotBlank() })) }
    LaunchedEffect(chart.cameraBoundsDecision) {
        if (chart.cameraBoundsDecision?.limit == com.no5.helm.chart.ChartCameraLimit.UNKNOWN_BOUNDS)
            message("Kartengrenzen sind unvollständig. Die bisherige Ansicht bleibt erhalten.")
    }
    fun routeEvent(event: RoutesEvent) {
        val oldDraft = state.routes.draft
        if (event == RoutesEvent.Save && state.routes.draft?.source == null) state = state.copy(routes = state.routes.copy(draft = state.routes.draft?.copy(source = signalK.origin)))
        state = state.route(event)
        // Only name a newly created document. Existing names and recovery drafts
        // are user data and must not change when the shared language changes.
        if (event == RoutesEvent.New && state.routes.draft?.id != oldDraft?.id) {
            state = state.copy(routes = state.routes.copy(draft = state.routes.draft?.copy(
                name = m1("Neue Route", "New route", "Nuwe roete"))))
        }
        if(event==RoutesEvent.KeepEditing)oldDraft?.let{taskExit.cancel("route:${it.id}")}
        if(oldDraft!=null&&state.routes.draft==null&&(event==RoutesEvent.Discard||event is RoutesEvent.Saved||event==RoutesEvent.CancelEdit)){routeMapPick=false;taskExit.complete("route:${oldDraft.id}"){}}
        if (event == RoutesEvent.Discard && oldDraft != null && state.routes.draft == null)
            scope.launch { try { routeRepository.draft(null) } catch (failure: Exception) { message(failure.message.orEmpty()) } }
    }
    state.routes.draft?.let{draft->CompositionLocalProvider(LocalMapTaskExit provides taskExit){RegisterMapTaskExit("route:${draft.id}"){routeEvent(RoutesEvent.CancelEdit)}}}
    LaunchedEffect(state.routes.saveRequest) {
        val request = state.routes.saveRequest ?: return@LaunchedEffect
        try {
            val completion = persistRouteSave(request, remoteSave = { document ->
                val actions = routeActions ?: error("Signal-K-Service fehlt; Entwurf bleibt lokal")
                val origin = document.source ?: error("Gateway-Zugang in Lumina Control einrichten")
                val remote = actions.saveRoute(origin, document.id.removePrefix("local-"), document.name,
                    document.points.map { SignalKRoutePoint(it.id, it.name, it.latitude, it.longitude) }, document.signalKToken, document.signalKResource)
                document.copy(signalKToken = remote.token, signalKResource = remote.resourceJson)
            }, cacheSave = { routeRepository.save(it) })
            when (completion) {
                is RouteSaveCompletion.Saved -> routeEvent(RoutesEvent.Saved(request.token, completion.document))
                is RouteSaveCompletion.CacheFailed -> routeEvent(RoutesEvent.SaveCacheFailed(request.token, completion.document, completion.reason))
            }
        }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { routeEvent(RoutesEvent.SaveFailed(request.token, failure.message ?: "Speichern fehlgeschlagen")) }
    }
    LaunchedEffect(state.routes.draft, state.routes.dirty, state.routes.saveRequest, routeStore.ready) {
        if (routeStore.ready && state.routes.saveRequest == null) {
            val draft = state.routes.draft?.takeIf { state.routes.dirty }
            if (draft != null) delay(300)
            // An offered recovery draft is retained until the user explicitly accepts/discards it.
            if (draft != null || routeStore.recoveryDraft == null || state.routes.message == "Route gespeichert. Navigation wurde nicht gestartet.") {
                try { routeRepository.draft(draft) }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { message(failure.message ?: "Entwurf konnte nicht gesichert werden") }
            }
        }
    }

    fun applyStyle(candidate: ChartPresentation, requestId: Long? = null, onConfirmed: ((Boolean) -> Unit)? = null) {
        if (styleBusy || filterBusy || measurementBusy || engine.status.value.rendererBenchmark.active || libraryState?.busy == true) { requestId?.let { state = state.copy(settings = reduceSettings(state.settings, SettingsEvent.AcknowledgePalette(it, false))) }; onConfirmed?.invoke(false); return }
        styleBusy = true
        scope.launch {
            var confirmed = false
            val previous = engine.status.value.presentation
            try {
                val result = confirmChartStyle(engine.status, candidate) { engine.applyPresentationProfile(candidate) }
                if (result.success) { savedStyles.confirmed(candidate); savedProfiles = savedStyles.profiles; confirmed = true }
                else message(result.message)
                requestId?.let { state = state.copy(settings = reduceSettings(state.settings, SettingsEvent.AcknowledgePalette(it, result.success))) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                engine.applyPresentationProfile(previous)
                message(failure.message ?: "Kartenstil konnte nicht gespeichert werden")
                requestId?.let { state = state.copy(settings = reduceSettings(state.settings, SettingsEvent.AcknowledgePalette(it, false))) }
            } finally { styleBusy = false; onConfirmed?.invoke(confirmed) }
        }
    }
    fun applyFilters(value: ChartObjectFilters) {
        if (filterBusy || styleBusy || measurementBusy || libraryState?.busy == true) return
        filterBusy = true; filterNotice = No5Text.ui("Filteränderung wird dargestellt …"); closeObject()
        scope.launch {
            try {
                val result = engine.applyObjectFilters(value)
                filterNotice = if (result.success) { filterPreferences.confirmed(value); No5Text.ui("Filter bestätigt · Revision %d", engine.status.value.objectFilters.revision) } else result.message
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { filterNotice = failure.message ?: No5Text.ui("Filteränderung fehlgeschlagen") }
            finally { filterBusy = false }
        }
    }
    LaunchedEffect(chart.objectFilters.revision) {
        if (objectQuery?.filterRevision?.let { it != chart.objectFilters.revision } == true) closeObject()
    }
    LaunchedEffect(chart.chartLoaded, chart.surfaceAttached, restoredStyle, styleBusy, measurementBusy) {
        if (!filtersRestored && chart.chartLoaded && chart.surfaceAttached && restoredStyle && !styleBusy && !measurementBusy && chart.objectFilters.supported) {
            filtersRestored = true
            try { filterPreferences.read()?.let(::applyFilters) }
            catch (failure: Exception) { filterNotice = No5Text.ui("Gesicherte Filter bleiben erhalten: %s", failure.message.orEmpty()) }
        }
    }
    fun settingsEvent(event: SettingsEvent) {
        val currentFrame = engine.status.value.confirmedFrame
        state = state.copy(settings = reduceSettings(state.settings.copy(orientationConfirmed = currentFrame != null && kotlin.math.abs(currentFrame.viewport.rotationRadians) < 1e-6), event))
        when (event) {
            is SettingsEvent.RequestPalette -> state.settings.paletteRequestId?.let { id ->
                applyStyle(savedProfiles.forMode(if (event.palette == UiPalette.DAY) PresentationMode.DAY else PresentationMode.RED_NIGHT), id)
            }
            is SettingsEvent.RequestOrientation -> state.settings.orientationRequestId?.let { id -> scope.launch {
                val view = engine.status.value.viewport
                val result = try { engine.centerCamera(GeoProjection(view.latitude, view.longitude), 0.0) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { message(failure.message ?: "Ausrichtung abgelehnt"); false }
                state = state.copy(settings = reduceSettings(state.settings, SettingsEvent.AcknowledgeOrientation(id, result)))
            } }
            is SettingsEvent.SetDistanceUnit -> preferences.edit().putString("distanceUnit", state.settings.distanceUnit.name).apply()
            is SettingsEvent.SetSpeedUnit -> preferences.edit().putString("speedUnit", state.settings.speedUnit.name).apply()
            is SettingsEvent.SetUiDimming -> preferences.edit().putInt("uiDimming", state.settings.uiDimmingPercent).apply()
            else -> Unit
        }
    }
    LaunchedEffect(engine, chart.chartLoaded, chart.surfaceAttached, chart.rendererBenchmark.active) {
        if (chart.rendererBenchmark.phase == "interrupted" && chart.rendererBenchmark.restored) restoredStyle = true
        else if (chart.chartLoaded && chart.surfaceAttached && !restoredStyle && !chart.rendererBenchmark.active && !chart.rendererBenchmarkRecoveryPending) { restoredStyle = true; applyStyle(savedProfiles.forMode(savedStyles.activeMode)) }
    }
    LaunchedEffect(chart.presentation, state.settings.paletteRequestId) {
        if (state.settings.paletteRequestId == null) state = state.copy(settings = state.settings.copy(
            palette = if (chart.presentation.mode == PresentationMode.RED_NIGHT) UiPalette.RED_NIGHT else UiPalette.DAY))
    }
    LaunchedEffect(helm, routeStore.routes, routeStore.ready, routeStore.issue, signalK) {
        val source = helm.snapshot?.serverId
        val changedSource = source != lastSource
        val watched = preferences.getStringSet("watch.${source.orEmpty()}", emptySet()).orEmpty()
        val routes = normalizeRoutes(helm.snapshot)
        state = state.copy(
            ais = state.ais.copy(targets = normalizeAis(helm.snapshot, watched), status = gatewayFeatureStatus(helm, "AIS-Ziele"), selectedMmsi = if (changedSource) null else state.ais.selectedMmsi,
                detailsExpanded = !changedSource && state.ais.detailsExpanded),
            routes = state.routes.copy(routes = FrozenList.copyOf((if (signalK.ready) signalK.routes.map(::remoteDocument) else routeStore.routes.filter { signalK.origin == null || it.source == signalK.origin }) + routes),
                saveSupported = routeStore.ready && routeStore.issue == null && signalK.origin != null && routeActions != null,
                status = DataStatus(if (routeStore.ready) Availability.READY else if (routeStore.issue != null) Availability.ERROR else Availability.LOADING,
                    routeStore.issue ?: signalK.issue ?: if (signalK.ready) No5Text.ui("Signal K · %d Routen", signalK.routes.size) else "Offline-Kopie · Signal-K-Bestand nicht bestätigt"),
                active = routes.find { it.id == "external-${source}-${helm.snapshot?.state?.activeRouteId}" }),
            shell = if (changedSource) state.shell.copy(chart = state.shell.chart.copy(selectedTarget = null)) else state.shell)
        if (changedSource) { showDetails = false }
        layers = layers.copy(layers = FrozenList.copyOf(layers.layers.map { layer ->
            if (layer.id == "ais") layer.copy(supported = helm.snapshot?.gatewayV1 != true,
                explanation = if (helm.snapshot?.gatewayV1 == true) "No5-Gateway V1 liefert keine AIS-Ziele." else "Letzte gemeldete Position · Messalter und Zielqualität unbekannt.") else layer
        }))
        lastSource = source
    }
    // Overlay requests are acknowledged only after all four GL projections have been replaced and drawn.
    val visible = layers.layers.associate { it.id to (it.requested ?: it.effective) }
    val overlay = overlaySnapshot(helm.snapshot, state.routes.draft ?: state.routes.shown, visible, state.routes.draft != null, ownship = position.ownship)
    val pendingLayers = layers.layers.filter { it.pendingRequestId != null }.map { it.id to it.pendingRequestId!! }
    val latestOverlay by rememberUpdatedState(overlay)
    LaunchedEffect(overlay, pendingLayers, chart.surfaceAttached, chart.surfaceGeneration,
        chart.chartSourceGeneration, chart.rendererBenchmark.active, chart.rendererBenchmarkRecoveryPending) {
        if (pendingLayers.isEmpty() && chart.surfaceAttached && !chart.rendererBenchmark.active && !chart.rendererBenchmarkRecoveryPending)
            engine.setOverlays(overlay)
    }
    // A new GPS sample must not cancel a user visibility command while its
    // frame is being confirmed. After ACK the live effect sends the latest
    // complete snapshot, including positions received during the command.
    LaunchedEffect(pendingLayers, chart.surfaceAttached, chart.surfaceGeneration,
        chart.chartSourceGeneration, position.selectedSourceId, chart.rendererBenchmark.active, chart.rendererBenchmarkRecoveryPending) {
        if (chart.surfaceAttached && !chart.rendererBenchmark.active && !chart.rendererBenchmarkRecoveryPending) {
            if (pendingLayers.isNotEmpty()) {
                val accepted = try { engine.applyOverlays(latestOverlay) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { message(failure.message ?: "Overlay-Anzeige fehlgeschlagen"); false }
                pendingLayers.forEach { (id, request) ->
                    val current = layers.layers.find { it.id == id }
                    layers = reduceLayers(layers, LayersEvent.Acknowledge(id, request, accepted, "Rendereränderung wurde nicht bestätigt."))
                    if (accepted && current?.pendingRequestId == request) preferences.edit().putBoolean("overlay.$id", layers.layers.first { it.id == id }.effective).apply()
                }
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val selectedReport = pendingExport
        pendingExport = null
        if (uri != null) scope.launch {
            reportBusy = true
            try { withContext(Dispatchers.IO) {
                val json = checkNotNull(selectedReport) { "Exportreport nicht mehr verfügbar. Bitte erneut exportieren." }.json
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: error("Exportziel nicht verfügbar")
            }; reportNotice = No5Text.ui("JSON-Datei exportiert") }
            catch (failure: Exception) { reportNotice = failure.message ?: No5Text.ui("Export fehlgeschlagen") }
            finally { reportBusy = false }
        }
    }
    fun retainResult(result: EngineResult) {
        if (result.success && result.message.isNotBlank()) {
            profileReport = result.message
            reportNotice = No5Text.ui("Aufnahme bereit · %d Bytes · JSON exportierbar", profileReport.toByteArray(Charsets.UTF_8).size)
        } else reportNotice = result.message.ifBlank { No5Text.ui("Keine abgeschlossene Aufnahme vorhanden") }
    }
    fun requestReportExport(){
        if(reportBusy||pendingExport!=null||measurementBusy||chart.rendererCapture.active||profileReport.isBlank()){return}
                    reportBusy = true
                    scope.launch {
                        try {
                            val result = if (profileReport.isNotBlank()) EngineResult(true, profileReport) else engine.readRendererReport()
                            retainResult(result)
                            if (result.success && profileReport.isNotBlank()) {
                                val selected = rendererReportExport(profileReport)
                                pendingExport = selected
                                export.launch(selected.fileName)
                            }
                        } catch (failure: Exception) { pendingExport = null; reportNotice = failure.message ?: No5Text.ui("Export fehlgeschlagen") }
                        finally { reportBusy = false }
                    }
    }
    fun evaluateReport() {
        if (reportBusy || measurementBusy) return
        reportBusy = true; reportNotice = No5Text.ui("Auswertung läuft …")
        scope.launch {
            try { retainResult(engine.stopRendererCapture()) }
            catch (failure: Exception) { reportNotice = failure.message ?: No5Text.ui("Auswertung fehlgeschlagen") }
            finally { reportBusy = false }
        }
    }
    LaunchedEffect(showRenderer) {
        if (showRenderer && !chart.rendererCapture.active && !measurementBusy) {
            reportBusy = true
            try {
                engine.readRendererDiagnostics()
                retainResult(engine.readRendererReport())
            }
            catch (failure: Exception) { reportNotice = failure.message ?: No5Text.ui("Aufnahme nicht lesbar") }
            finally { reportBusy = false }
        }
    }
    LaunchedEffect(chart.rendererBenchmark.runId, chart.rendererBenchmark.active, chart.rendererBenchmark.runKind, currentViewUiRun) {
        if (chart.rendererBenchmark.active) {
            // Resident runs use the visible real map; a modal would obscure the measured frames.
            showRenderer = chart.rendererBenchmark.runKind == RendererRunKind.SCRIPTED
            showDetails = false; showBenchmarkSources = false; showStyle = false
            profileReport = ""; reportNotice = null
        }
        else if (chart.rendererBenchmark.runId.isNotEmpty() && !chart.rendererBenchmarkRecoveryPending && chart.rendererBenchmark.phase != "interrupted") {
            reportBusy = true
            try { retainResult(engine.readRendererReport()) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { reportNotice = failure.message ?: No5Text.ui("Vergleichsreport nicht lesbar") }
            finally { reportBusy = false }
            if (chart.rendererBenchmark.runId == currentViewUiRun) {
                currentViewReturnPanel?.let { previous ->
                    // Do not replace an intervening user navigation with an old panel state.
                    if (state.shell.destination == previous.first && !state.shell.panelExpanded) {
                        state = state.copy(shell = state.shell.copy(panelExpanded = previous.second))
                        showRenderer = true
                    }
                }
                currentViewUiRun = null; currentViewReturnPanel = null
            }
        }
    }
    val startWork = residentStartWork(chart)?.let { No5Text.ui(it) }
    val residentLoading = chart.residentCatalogActive && (!chart.chartLoaded || startWork != null)
    val chartStatus = DataStatus(when {
        chart.sourcePresentationUnsupported -> Availability.ERROR
        residentLoading -> Availability.LOADING
        !chart.chartLoaded -> if (chartLibrary != null) Availability.EMPTY else Availability.LOADING
        !chart.surfaceAttached || chart.confirmedFrame == null && !hasPresentedChart -> Availability.LOADING
        else -> Availability.READY
    // A camera request invalidates coordinate queries, not the chart already displayed.
    // Do not mount/remove an opaque warning during each MOVE: region changes cancel touch streams.
    }, if (chart.sourcePresentationUnsupported) SOURCE_PRESENTATION_UNSUPPORTED_MESSAGE
        else if (startWork != null) startWork else if (residentLoading) No5Text.ui("Kartenbestand aktiv · Zellen werden geladen") else if (!chart.chartLoaded && chartLibrary != null) No5Text.ui("Keine aktive Karte · Karten separat importieren") else if (chart.confirmedFrame == null) No5Text.ui("Kartenframe wird aktualisiert · %s", chart.message) else No5Text.ui("GLES-Frame bestätigt"))
    val frame = chart.confirmedFrame?.takeUnless { chart.sourcePresentationUnsupported || startWork != null }
    // Query authority follows the exact confirmed frame. The separate visual scale below
    // belongs to the actually visible buffer, including an explicitly verified pending view.
    val snapshot = confirmedChartViewport(frame)
    val visualScale = adapter.visualScaleProjection
    val displayedViewport by rememberUpdatedState(snapshot)
    val liveFix = position.ownship
    val controlsBusy by rememberUpdatedState(styleBusy || filterBusy || measurementBusy || libraryState?.busy == true || chart.rendererBenchmark.active || chart.rendererCapture.active)
    LaunchedEffect(position.followActive, position.observedAtEpochMs, position.generation, position.touchActive, chart.chartLoaded, controlsBusy) {
        if (position.followActive && !position.touchActive && chart.chartLoaded && !controlsBusy)
            positions.follow(System.currentTimeMillis(), android.os.SystemClock.uptimeMillis())
    }
    val interactiveChart = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused &&
        state.shell.message == null && !state.shell.mobDialog &&
        (state.shell.destination == Destination.CHART || !state.shell.panelExpanded) &&
        chart.chartLoaded && chart.surfaceAttached && hasPresentedChart && !chart.sourcePresentationUnsupported && !controlsBusy && !objectPanel &&
        !showRenderer && !showStyle && !showConnection && !showBenchmarkSources && !showPositionSources
    fun submitMappedControl(input: ChartKnobInput, sequence: Long, stamp: Long): Long {
        if (input == ChartKnobInput.LEFT_LONG_PUSH) return positions.center(System.currentTimeMillis(), sequence, stamp)
        return submitChartKnobControl(engine, input, sequence, stamp, Double.NaN, Double.NaN, initialViewport.metresToPixels, positionControls)
    }
    val bufferedPublisher by rememberUpdatedState(onBufferedKnobBindings)
    DisposableEffect(engine,interactiveChart,onBufferedKnobBindings!=null) {
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val feedback=MapKnobFeedback({engine.status.value},{positions.state.value.generation},
            {id,destination->if(id>MapPositionControls.PREFIX)positionControls.result(id,destination)else engine.cameraControlResult(id,destination)},
            positionControls::intentOnly,{input->when(input){
                ChartKnobInput.LEFT_CW,ChartKnobInput.LEFT_CCW->"Kartenzoom"
                ChartKnobInput.LEFT_PUSH->when(positions.state.value.mode){PositionMode.OFF->"Folgen aus";PositionMode.CENTERED->"Einmal zentriert";PositionMode.FOLLOW->"Folgen an"}
                ChartKnobInput.LEFT_LONG_PUSH->"Zentrieren"
                ChartKnobInput.RIGHT_CW,ChartKnobInput.RIGHT_CCW->"Karte drehen"
                ChartKnobInput.RIGHT_PUSH->"Nordausrichtung"
                ChartKnobInput.RIGHT_LONG_PUSH->"Ansicht zurücksetzen"
            }})
        val bindings=ChartKnobInput.entries.associateWith { input -> object:ChartBufferedKnobAction {
            override fun submit(sequence:Long,physicalUptimeMs:Long):Long {
                if(!active.get())return 0
                // A covered/unavailable chart rejects the camera action. Keep its claim so
                // an unrelated audio fallback cannot turn a menu gesture into a volume change.
                val id=if(interactiveChart)submitMappedControl(input,sequence,physicalUptimeMs)else 0L
                feedback.submitted(input,id)
                return id
            }
            override fun result(id:Long,destination:ChartControlResult)=if(id>MapPositionControls.PREFIX)positionControls.result(id,destination)else engine.cameraControlResult(id,destination)
            override fun cancel(){if(active.get())positionControls.cancel()}
        } }
        bufferedPublisher?.invoke(bindings)
        onDispose{active.set(false);feedback.close();positionControls.cancel();bufferedPublisher?.invoke(emptyMap())}
    }
    val knobPublisher by rememberUpdatedState(onKnobBindings)
    DisposableEffect(engine,interactiveChart,onKnobBindings!=null) {
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val bindings=ChartKnobInput.entries.associateWith { input -> {request:ChartKnobRequest ->
            val now=android.os.SystemClock.uptimeMillis()
            request.complete(interactiveChart&&active.get()&&request.active()&&!controlsBusy&&now<request.deadlineUptimeMs&&submitMappedControl(input,0,now)>0)
        } }
        knobPublisher?.invoke(bindings)
        onDispose{active.set(false);if(onKnobBindings!=null)positionControls.cancel();knobPublisher?.invoke(emptyMap())}
    }
    val headerPosition = nativeHeaderPosition(position, uiLanguage)
    val shell = state.shell.copy(
        redNight = chart.presentation.mode == PresentationMode.RED_NIGHT,
        uiDimmingPercent = state.settings.uiDimmingPercent,
        sourceLabel = if (helm.snapshot == null) No5Text.ui("Kein Helm-Snapshot") else headerPosition.label,
        sourceStatus = nativeHeaderSourceStatus(helm, position, uiLanguage),
        chart = state.shell.chart.copy(status = chartStatus, ownFixValid = liveFix != null && position.chartBoundsKnown && !position.outsideChartBounds && !controlsBusy, following = position.followActive, followRequested = position.intent.follow,
            positionMode = when(position.mode) { PositionMode.OFF -> PositionButtonMode.OFF; PositionMode.CENTERED -> PositionButtonMode.CENTERED; PositionMode.FOLLOW -> PositionButtonMode.FOLLOW }, positionDetail = positionSummary(position, uiLanguage),
            viewport = snapshot, scale = visualScale?.scale, scaleViewport = visualScale?.viewport,
            zoomInAvailable = frame != null && frame.viewport.metresToPixels < 10.0,
            zoomOutAvailable = frame != null && frame.viewport.metresToPixels > (navigationRegion?.overviewScale(frame.viewport.width, frame.viewport.height, frame.viewport.rotationRadians) ?: .00001) * (1 + 1e-9),
            coverage = if (objectPanel && objectQuery == null && selectedObject == null && objectHits.isEmpty())
                m1("Kartenobjekt antippen", "Tap a chart object", "Tik op 'n kaartobjek")
            else No5Text.ui("%s · Darstellung nicht freigegeben", chartLabel)))
    val headerContext = NativeHeaderContext(headerSourceGeneration, state.shell.destination.name, shell.redNight, helm,
        shell.sourceStatus.detail, shell.sourceStatus.availability.name, headerPosition)
    val lastHeaderContext = remember { arrayOfNulls<NativeHeaderContext>(1) }
    SideEffect {
        if (lastHeaderContext[0] != headerContext) {
            lastHeaderContext[0] = headerContext
            onHeaderContext?.invoke(headerContext)
        }
    }
    val settings = state.settings.copy(chartStatus = chartStatus,
        orientationConfirmed = frame != null && kotlin.math.abs(frame.viewport.rotationRadians) < 1e-6,
        offlineStatus = DataStatus(when {
            startWork != null -> Availability.LOADING
            chart.chartLoaded -> Availability.READY
            residentLoading -> Availability.LOADING
            else -> Availability.EMPTY
        }, when {
            startWork != null -> "$chartLabel · $startWork"
            chart.chartLoaded -> No5Text.ui("%s · lokaler Kartenbestand; keine automatische Aktualitätsprüfung.", chartLabel)
            residentLoading -> No5Text.ui("%s · Kartenbestand aktiviert; Zellen werden geladen.", chartLabel)
            else -> No5Text.ui("Keine aktive Karte. Einstellungen → Karten → Kartenpaket importieren.")
        }),
        sources = frozenListOf(SourceInformation("Helm · $endpoint", sourceStatus(helm), if (helm.snapshot?.gatewayV1 == true) No5Text.ui("No5-Gateway V1 /v1/helm · Home-Zugang · nur lesbar.") else No5Text.ui("Snapshot / Stream v1, nur lesbar. Fixqualität und Simulationsherkunft nicht im Vertrag.")),
            SourceInformation("ENC · $chartLabel", chartStatus, No5Text.ui("Lokale S57-Geometrie direkt per GLES."))),
        safetyDepthSupported = true, safetyDepthDetail = "S52-Sicherheitstiefe und Kontur im Kern gekoppelt (3 m / nächste DEPCNT). Shallow/Deep-Bedienung noch nicht angeschlossen.")
    SideEffect {
        adapter.distanceUnit = state.settings.distanceUnit
        val chartTaps = state.shell.destination == Destination.CHART && !measurementBusy && !styleBusy && !filterBusy && libraryState?.busy != true
        adapter.enableTaps(state.shell.destination == Destination.ROUTES && state.routes.draft != null || chartTaps, chartTaps)
        adapter.onObjectTap = { request, result -> overlayHits = emptyList(); receiveObjects(request, result) }
        adapter.onOverlayTap = { hits, request -> closeObject(); overlayHits = hits; overlayQuery = request }
        adapter.overlayObjects = buildList {
            overlay.ownship?.let { add(NativeRendererAdapter.OverlayHit("ownship", "Bootsposition", "Letzte belegte Position", it)) }
            overlay.aisObjects.forEach { add(NativeRendererAdapter.OverlayHit(it.id, it.label, "AIS · Messalter unbekannt", it.position)) }
            overlay.waypointObjects.forEach { add(NativeRendererAdapter.OverlayHit(it.id, it.label, "Quell-Wegpunkt", it.position)) }
            overlay.route?.let { route -> route.points.forEachIndexed { index, point -> add(NativeRendererAdapter.OverlayHit(route.pointIds.getOrNull(index) ?: "${route.id}-$index",
                "${index + 1}. ${route.pointNames.getOrNull(index)?.ifBlank { No5Text.ui("Wegpunkt") } ?: No5Text.ui("Wegpunkt")}", "Route · ${route.role.name}", point)) } }
        }
        adapter.routeHandle = state.routes.replaceAt?.let { index -> state.routes.draft?.points?.getOrNull(index)?.let { index to GeoProjection(it.latitude, it.longitude) } }
            .takeIf { state.shell.destination == Destination.ROUTES && state.routes.saveRequest == null && !measurementBusy && !filterBusy }
        adapter.onRouteDrop = { index, position -> routeEvent(RoutesEvent.PlacePoint(index, RoutePoint(position.latitude, position.longitude)));if(routeMapPick){routeMapPick=false;state=state.copy(shell=state.shell.copy(panelExpanded=true))} }
    }
    fun rendererEvent(event: RendererEvent) {
        when (event) {
            is RendererEvent.FrameConfirmed -> state = state.copy(shell = state.shell.copy(chart = state.shell.chart.copy(viewport = event.viewport, scale = event.scale)))
            is RendererEvent.GeoTapped -> if (state.shell.destination == Destination.ROUTES && state.routes.draft != null &&
                event.viewport == engine.status.value.confirmedFrame?.let { ViewportSnapshot(it.revision, it.geometry.surfaceWidth, it.geometry.surfaceHeight, it.cameraToken) }) {
                // The native frame may be acknowledged before Compose has recomposed.
                // Validate against the engine authority that produced the coordinate.
                routeEvent(RoutesEvent.AddPoint(RoutePoint(event.position.latitude, event.position.longitude)))
                if(routeMapPick){routeMapPick=false;state=state.copy(shell=state.shell.copy(panelExpanded=true))}
            }
            RendererEvent.GestureStarted -> {
                if (objectBusy && selectedObject == null) { objectInteraction++; objectBusy = false; objectNoticeRaw=false;objectNotice = "Kartenausschnitt geändert. Erneut antippen."; scope.launch { engine.selectObject(null) } }
            }
            RendererEvent.PanStarted -> positions.gestureStarted(System.currentTimeMillis())
            is RendererEvent.TouchActivityChanged -> {
                positions.touchActivity(event.active, System.currentTimeMillis())
                state = state.copy(shell = state.shell.copy(chart = state.shell.chart.copy(touchActive=event.active)))
            }
            is RendererEvent.Failed -> message(event.message)
        }
    }
    fun shellEvent(event: ShellEvent) {
        when (event) {
            ShellEvent.OpenHome -> taskExit.leave{No5AppNavigation.open(context, No5AppNavigation.Target.HOME, shell.redNight)?.let { message(it) }}
            is ShellEvent.Navigate -> if(event.destination==state.shell.destination)state=state.navigate(event.destination)else taskExit.leave{state = state.navigate(event.destination)}
            ShellEvent.Back -> when {
                objectPanel -> closeObject()
                state.shell.message != null -> state = state.copy(shell = state.shell.copy(message = null))
                state.shell.mobDialog -> state = state.copy(shell = state.shell.copy(mobDialog = false))
                state.shell.chart.selectedTarget != null -> state = state.copy(shell = state.shell.copy(chart = state.shell.chart.copy(selectedTarget = null)))
                routeMapPick -> {routeMapPick=false;state=state.copy(shell=state.shell.copy(panelExpanded=true))}
                else -> taskExit.leave{state = state.navigate(Destination.CHART)}
            }
            ShellEvent.TogglePanel -> state = state.copy(shell = state.shell.copy(panelExpanded = !state.shell.panelExpanded))
            is ShellEvent.SetNight -> settingsEvent(SettingsEvent.RequestPalette(if (event.enabled) UiPalette.RED_NIGHT else UiPalette.DAY))
            ShellEvent.ZoomIn, ShellEvent.ZoomOut -> displayedViewport?.let { confirmed -> scope.launch {
                try { adapter.execute(RendererCommand.Zoom(if (event == ShellEvent.ZoomIn) 2.0 else .5,
                    ScreenPixel(confirmed.widthPx / 2f, confirmed.heightPx / 2f), confirmed)) }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { message(failure.message ?: "Zoom abgelehnt") }
            } }
            ShellEvent.NorthUp -> settingsEvent(SettingsEvent.RequestOrientation(MapOrientation.NORTH_UP))
            ShellEvent.PositionCycle -> if (!controlsBusy) { positionControls.observe(); positionControls.cycle(); positionControls.drain() }
            ShellEvent.CenterBoat -> if (!controlsBusy && positions.center(System.currentTimeMillis(), 0, android.os.SystemClock.uptimeMillis()) == 0L) message("Zentrieren benötigt eine aktuelle, bestätigte Bootsposition.")
            ShellEvent.FollowBoat -> if (!positions.toggleFollow(System.currentTimeMillis())) message("Folgen benötigt eine aktuelle, bestätigte Bootsposition.")
            ShellEvent.PositionSources -> taskExit.leave{showPositionSources = true}
            ShellEvent.OpenControl -> taskExit.leave{if(onHomeGateway!=null)onHomeGateway()else message(m1("Lumina Control ist nicht verfügbar.","Lumina Control is unavailable.","Lumina Control is onbeskikbaar."))}
            ShellEvent.CloseTarget -> { closeObject(); state = state.copy(shell = state.shell.copy(chart = state.shell.chart.copy(selectedTarget = null))) }
            ShellEvent.TargetDetails -> showDetails = true
            ShellEvent.RequestMob -> state = state.copy(shell = state.shell.copy(mobDialog = true))
            ShellEvent.DismissMob -> state = state.copy(shell = state.shell.copy(mobDialog = false))
            ShellEvent.ConfirmSimulatedMob -> Unit
            ShellEvent.DismissMessage -> state = state.copy(shell = state.shell.copy(message = null))
        }
    }
    CompositionLocalProvider(LocalMapTaskExit provides taskExit) {
    // The persistent renderer and hardware masks use the complete physical window.
    // Only editor panels inside No5Shell consume IME insets.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    No5Shell(shell, ::shellEvent, mediaHeaderSlot = mediaHeaderSlot, headerControlsSlot = headerControlsSlot, workspacePanels=true,
        rendererSlot = { modifier -> BoxWithConstraints(modifier) {
            // Attach before the first resident cell is loaded: its demand waits for this surface.
            key(engine) { adapter.Content(Modifier.fillMaxSize(), ::rendererEvent) }
            if (residentLoading && !chart.sourcePresentationUnsupported) Surface(Modifier.align(androidx.compose.ui.Alignment.Center).padding(24.dp).testTag("resident-chart-loading")) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(startWork ?: com.no5.ui.No5Text.ui("Karten werden geladen"), Modifier.testTag("resident-chart-loading-phase"))
                    Text(if (startWork != null) when (chart.residentStartState) {
                        ChartResidentStartState.BLOCKED -> com.no5.ui.No5Text.ui("Der Startausschnitt ist nicht zugelassen. Unter Karten die aktiven Bereiche prüfen.")
                        ChartResidentStartState.UNKNOWN -> com.no5.ui.No5Text.ui("Der Zellbedarf lässt sich noch nicht sicher bestimmen. Kartenmetadaten und Bestand prüfen.")
                        ChartResidentStartState.STALE_AUTHORITY -> com.no5.ui.No5Text.ui("Der Karten- oder Fensterstand hat sich geändert. Der Startausschnitt muss erneut geprüft werden.")
                        else -> com.no5.ui.No5Text.ui("Der Kartenbestand ist vorbereitet. Erst nach Prüfung des Startausschnitts werden die benötigten Zellen geladen.")
                    } else com.no5.ui.No5Text.ui("Der Kartenbestand ist aktiviert. Die benötigten Zellen werden nachgeladen."))
                }
            } else if (chartLibrary != null && !chart.chartLoaded && libraryState?.busy != true) No5ControlTheme(shell.redNight) { Surface(Modifier.align(androidx.compose.ui.Alignment.Center).padding(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(com.no5.ui.No5Text.ui("Keine Karten geladen"))
                    Text(com.no5.ui.No5Text.ui("Kartenpaket importieren und die gewünschten Karten aktivieren."))
                    No5CompactAction("Karten verwalten", { taskExit.leave{settingsLibraryRequested=true;state = state.navigate(Destination.SETTINGS).copy(settings = state.settings.copy(tab = SettingsTab.CHARTS))} }, Modifier.testTag("empty-chart-import"))
                }
            }
            }
            if (showWorkOverlay && (state.shell.destination == Destination.CHART || !state.shell.panelExpanded)) No5ControlTheme(shell.redNight) {
                RendererWorkOverlay(rendererWorkOverlayModel(chart, libraryState?.residency, styleBusy,
                    libraryState?.busy == true, libraryState?.progress.orEmpty()),
                    Modifier.align(Alignment.TopStart).padding(start = maxWidth * (24f / 2000f), top = maxWidth * (170f / 2000f))
                        .widthIn(max = minOf(330.dp, maxWidth * .42f)))
            }
        } }, onBlockedRegions = adapter::block,onGestureBlockedRegions=adapter::blockDuringGesture,onDockGestureClaimed=adapter::claimDockGesture,isChartTouchActive=adapter::hasActiveTouch,
        // Enter inspection on the exposed chart. A result workspace must not cover
        // the point the user still needs to pick; it opens only with a real query.
        objectPanelVisible = objectPanel && (objectQuery != null || selectedObject != null || objectHits.isNotEmpty()),
        objectPanel = { modifier ->
            val objectDictionary = remember(context) { ChartObjectDictionary.from(context.assets) }
            ChartObjectPanel(selectedObject, objectHits, objectTotal, objectNotice, objectBusy,
            chart.objectSelection, objectDictionary, libraryState?.snapshot, objectTexts, objectNext != null, ::chooseObject,
            onMore = {
                val request = objectQuery; val next = objectNext; val revision = objectInteraction
                if (request != null && next != null && !objectBusy) { objectBusy = true; scope.launch {
                    val result = engine.queryObjects(request.copy(offset = next))
                    if (revision == objectInteraction) receiveObjects(request, result, append = true)
                } }
            }, onClose = ::closeObject, modifier = modifier,workspace=true,night=shell.redNight,noticeRaw=objectNoticeRaw) },
        chartActions = { No5DockAction("OBJEKTINFO", { taskExit.leave{state=state.navigate(Destination.CHART);objectPanel = true; if (selectedObject == null && objectHits.isEmpty()){objectNoticeRaw=false;objectNotice = "Tonne, Wrack oder Gebiet auf der Karte antippen."} } }, Modifier.fillMaxWidth().testTag("inspect-mode"), frame != null) },
        panelContent = { destination, modifier -> when (destination) {
            Destination.CHART -> Unit
            Destination.ROUTES -> MapRoutesWorkspace(state.routes,shell.redNight,::routeEvent,{shellEvent(ShellEvent.TogglePanel)},chart.chartLoaded,
                onRefresh={scope.launch{routeActions?.refreshRoutes()}},onFit={selected->scope.launch {
                        val status = engine.status.value
                        val requested = routeFitViewport(selected, status.viewport)
                        val wanted = requested?.let { proposal ->
                            if (!status.residentCatalogActive) proposal else status.residentCatalogGeneration?.let { catalog -> engine.evaluateResidentCamera(
                                ChartResidentCameraProposal(status.viewport, proposal, proposal, false, catalog, status.chartSourceGeneration, status.surfaceGeneration))?.acceptedCamera }
                        }
                        if (wanted == null || wanted != requested) message("Routenausschnitt außerhalb der zugelassenen Kartenabdeckung.")
                        else { engine.setViewport(wanted); state = state.copy(shell = state.shell.copy(panelExpanded = false)) }
                    } },onMapEdit={routeMapPick=true;state=state.copy(shell=state.shell.copy(panelExpanded=false))},
                recovery=routeStore.recoveryDraft,onDiscardRecovery={scope.launch{try{routeRepository.draft(null)}catch(failure:Exception){message(failure.message?:m1("Entwurf konnte nicht entfernt werden","Could not discard draft","Konsep kon nie verwerp word nie"))}}})
            Destination.AIS -> MapAisWorkspace(state.ais.copy(distanceUnit = settings.distanceUnit, speedUnit = settings.speedUnit), { event ->
                if (event is AisEvent.ShowOnMap) {
                    if (state.routes.dirty) message("Routenentwurf zuerst abschließen oder verwerfen.") else scope.launch {
                        try {
                            adapter.execute(RendererCommand.Center(Wgs84(event.position.latitudeDegrees, event.position.longitudeDegrees), false))
                            state = state.navigate(Destination.CHART).copy(shell = state.shell.copy(destination = Destination.CHART, chart = state.shell.chart.copy(
                                selectedTarget = state.ais.targets.find { it.mmsi == event.mmsi }?.name ?: "MMSI ${event.mmsi}", targetSummary = "Letzte gemeldete Position · Messalter und Qualität unbekannt")))
                            closeObject()
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { message(failure.message ?: "Position nicht verfügbar") }
                    }
                } else {
                    state = state.copy(ais = reduceAis(state.ais, event))
                    if (event is AisEvent.ToggleWatch) preferences.edit().putStringSet("watch.${helm.snapshot?.serverId.orEmpty()}", state.ais.targets.filter { it.watched }.map { it.mmsi }.toSet()).apply()
                }
            },shell.redNight,{shellEvent(ShellEvent.TogglePanel)})
            Destination.LAYERS -> MapLayersWorkspace(layers.copy(status = chartStatus), { layers = reduceLayers(layers, it) },shell.redNight,
                {shellEvent(ShellEvent.TogglePanel)},modifier,s52Supported=chart.objectFilters.supported) {
                ChartObjectFilterPanel(chart.objectFilters, dictionary, filterBusy || styleBusy || measurementBusy || libraryState?.busy == true,
                    filterNotice, ::applyFilters)
            }
            Destination.SETTINGS -> MapSettingsWorkspace(settings,::settingsEvent,shell.redNight,
                onClose={shellEvent(ShellEvent.TogglePanel)},onPosition={showPositionSources=true},
                openLibrary=settingsLibraryRequested,onLibraryOpened={settingsLibraryRequested=false},
                onStyle={taskExit.leave{showStyle=true}},onRenderer={showRenderer=true},onConnection={showConnection=true},onInfo=onVersionInfo,
                library={back->ChartLibraryPanel(chartLibrary,measurementBusy||chart.rendererCapture.active,workspace=true,onBack=back)},
                layers={back->MapContentWorkspace(chart.objectFilters,dictionary,filterBusy||styleBusy||measurementBusy||libraryState?.busy==true,filterNotice,shell.redNight,back,::applyFilters){close->
                    MapLayersWorkspace(layers.copy(status=chartStatus),{layers=reduceLayers(layers,it)},shell.redNight,close,Modifier.fillMaxSize()){}
                }},modifier=modifier,
                displayContent = { Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(com.no5.ui.No5Text.ui("Schneller Kartenmodus"), Modifier.weight(1f))
                        MapControlSwitch(checked = chart.bufferTransformMode == ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE,
                            enabled = android.os.Build.VERSION.SDK_INT >= 29 && chart.chartLoaded && chart.surfaceAttached && !measurementBusy && !chart.rendererCapture.active && !styleBusy && libraryState?.busy != true,
                            modifier = Modifier.testTag("fast-chart-mode-switch").semantics { contentDescription = No5Text.ui("Schneller Kartenmodus") },
                            onCheckedChange = { enabled ->
                                val result = engine.setBufferTransformMode(if (enabled) ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE else ChartBufferTransformMode.OFF)
                                if (result.success) preferences.edit().putBoolean("fastChartModeEnabled", enabled).apply()
                                else message(result.message.ifBlank { "Bitte die laufende Geste beenden." })
                            })
                    }
                    Text(if (chart.bufferTransformMode == ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE)
                        if (preferences.getBoolean("fastChartModeEnabled", false)) com.no5.ui.No5Text.ui("An · Karte folgt sofort, Details werden ergänzt. Schnelles Wischen gleitet nach. Beim Neustart wieder an.")
                        else com.no5.ui.No5Text.ui("Testweise an · Karte folgt sofort, Details werden ergänzt. Nach Neustart aus. Eine hier gewählte Einstellung wird gespeichert.")
                        else com.no5.ui.No5Text.ui("Aus · zum Testen einschalten. Deine Auswahl bleibt nach Neustart erhalten."), Modifier.testTag("fast-chart-mode-status"))


                } },
                diagnosticsContent = {
                    No5CompactAction("GPS-Quelle und Folgen", { showPositionSources = true }, Modifier.testTag("position-source-settings"))
                    No5CompactAction(if (chart.rendererBenchmark.active) No5Text.ui("Benchmark · %d/%d", chart.rendererBenchmark.completedGroups, chart.rendererBenchmark.totalGroups) else if (chart.rendererCapture.active) No5Text.ui("Profiler · %d Frames", chart.rendererCapture.frames) else "Renderer-Diagnose", { showRenderer = true }, Modifier.testTag("renderer-diagnostics"))
                    No5CompactAction("Diagnose · Helm-Verbindung", { showConnection = true })
                })
        } })
        if (gatewayNotice != null) No5ControlTheme(shell.redNight) {
            Text(gatewayNotice, Modifier.align(Alignment.TopCenter).padding(top = maxWidth * (110f / 2000f))
                .testTag("gateway-session-status"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    // R1: MAP's footer starts at938px. Detail sheets inherit the same
    // available content area without changing the chart surface or camera.
    CompositionLocalProvider(com.no5.ui.LocalNo5ContentBottom provides 938f) {
    No5ControlTheme(shell.redNight) {
        if (overlayHits.isNotEmpty()) No5DetailsDialog("Overlays an diesem Punkt",shell.redNight,{overlayHits=emptyList()},
            body=overlayHits.joinToString("\n\n"){hit->
                val title=if(hit.id=="ownship")No5Text.ui(hit.title)else hit.title
                val kind=if(hit.kind.startsWith("Route · "))No5Text.ui("Route · %s",hit.kind.removePrefix("Route · "))else No5Text.ui(hit.kind)
                "$title\n$kind · ${hit.position.latitude}° / ${hit.position.longitude}°\nID: ${hit.id}"
            },tag="overlay-hit-panel",actions=listOf(
                No5WorkspaceView.Action("Schließen",key="overlay-close",action={overlayHits=emptyList()}),
                No5WorkspaceView.Action("Kartenobjekte hier prüfen",key="overlay-query-enc",action={
                    val request=overlayQuery;overlayHits=emptyList()
                    if(request!=null)scope.launch{receiveObjects(request,engine.queryObjects(request))}
                })))
        RoutesDialogs(state.routes,::routeEvent,shell.redNight)
        state.routes.deleteCandidate?.let{route->No5DetailsDialog("Route in Signal K löschen?",shell.redNight,
            {if(!routeDeleting)routeEvent(RoutesEvent.CancelDelete)},body=listOfNotNull(route.name,routeDeleteError).joinToString("\n"),tag="route-delete",
            actions=listOf(
                No5WorkspaceView.Action("Abbrechen",enabled=!routeDeleting,key="route-delete-cancel",action={routeEvent(RoutesEvent.CancelDelete)}),
                No5WorkspaceView.Action("Löschen",enabled=!routeDeleting,key="route-delete-confirm",action={scope.launch{
                if (routeDeleting) return@launch
                routeDeleting = true; routeDeleteError = null
                try {
                    val actions = routeActions ?: error("Signal-K-Service fehlt")
                    actions.deleteRoute(requireNotNull(route.source) { "Gateway-Quelle fehlt" }, route.id.removePrefix("local-"),
                        requireNotNull(route.signalKToken) { "Bestätigten Signal-K-Stand zuerst neu laden" })
                    try { routeRepository.delete(route.id, route.revision, route.source) }
                    catch (failure: Exception) { message(No5Text.ui("Signal K hat gelöscht; Offline-Kopie konnte nicht entfernt werden: %s", failure.message.orEmpty())) }
                    routeEvent(RoutesEvent.Deleted)
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { routeDeleteError = failure.message ?: No5Text.ui("Löschen fehlgeschlagen") }
                finally { routeDeleting = false }
                }}))) }

        if (showStyle) ChartStyleSettings(chart.presentation, savedProfiles, styleBusy || measurementBusy,
            { showStyle = false }, { applyStyle(it) }, onApplyConfirmed = { candidate, completed -> applyStyle(candidate, onConfirmed = completed) },workspace=true)
        if(showRenderer&&rendererSection=="menu")BoxWithConstraints(Modifier.fillMaxSize()){
            val u=maxWidth.value/2000f
            com.no5.ui.No5Workspace("Rendererdiagnose",rendererDiagnosticRuntimeTitle(),listOf(
                No5WorkspaceView.Row("Treiber / Fläche",value="${chart.viewport.width} × ${chart.viewport.height}",rawValue=true,key="renderer-driver",action={rendererSection="driver"}),
                No5WorkspaceView.Row("Messung",value=if(measurementBusy)"Beschäftigt"else"Bereit",key="renderer-measurements",action={rendererSection="measurement"}),
                No5WorkspaceView.Row("Originale Hinweise",key="renderer-notices",action={showBenchmarkSources=true})),
                shell.redNight,{if(!measurementBusy)showRenderer=false},Modifier.offset(y=(130*u).dp).fillMaxWidth().height((928*u).dp).testTag("renderer-workspace"),
                rawSubtitle=true,actions=listOf(
                    No5WorkspaceView.Action("Zurück",enabled=!measurementBusy,key="renderer-back",action={showRenderer=false}),
                    No5WorkspaceView.Action("Exportieren",enabled=profileReport.isNotBlank()&&pendingExport==null&&!measurementBusy&&!reportBusy&&!chart.rendererCapture.active,key="renderer-report-export",action=::requestReportExport)))
        }
        if(showRenderer&&rendererSection=="driver")No5DetailsDialog("Treiber / Fläche",shell.redNight,{rendererSection="menu"},
            body=rendererDiagnosticRuntimeTitle()+"\n"+chart.rendererDiagnostics+"\n"+"${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · ${chart.viewport.width} × ${chart.viewport.height} px",tag="renderer-driver-details")
        if(showRenderer&&rendererSection=="measurement")No5DetailsDialog("Messung",shell.redNight,{if(!measurementBusy)rendererSection="menu"},tag="renderer-dialog",
            actions=listOf(
                No5WorkspaceView.Action("Report exportieren",enabled=profileReport.isNotBlank()&&pendingExport==null&&!measurementBusy&&!reportBusy&&!chart.rendererCapture.active,
                    key="renderer-report-export",action={
                    requestReportExport()
                }),
                No5WorkspaceView.Action("Zurück",enabled=!measurementBusy,key="renderer-close",action={rendererSection="menu"}))){
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(rendererDiagnosticRuntimeTitle())
                reportNotice?.let { Text(it, Modifier.testTag("renderer-report-result")) }
                if (reportBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(com.no5.ui.No5Text.ui("Live: Aufnahme starten, Karte bewegen oder Stil wechseln, danach hier stoppen/exportieren. Automatisches Ende nach 20 Sekunden oder 600 Frames."))
                if (chart.rendererCapture.active && !chart.rendererBenchmark.active) {
                    Text(No5Text.ui("Aufnahme: %d Frames · %d s", chart.rendererCapture.frames, (chart.rendererCapture.elapsedMs / 1000).toInt()))
                    No5CompactAction(label = "Stoppen und auswerten", enabled = !reportBusy, onClick = ::evaluateReport, modifier = Modifier.testTag("renderer-capture-stop"))
                } else {
                    No5CompactAction(label = "20-s-Liveaufnahme starten", enabled = !measurementBusy && !styleBusy && !reportBusy && libraryState?.busy != true && chart.chartLoaded, onClick = { scope.launch {
                        val result = engine.startRendererCapture()
                        if (result.success) { profileReport = ""; showRenderer = false } else reportNotice = result.message
                    } }, modifier = Modifier.testTag("renderer-capture-start"))
                    if (chart.rendererCapture.frames > 0) No5CompactAction(label = "Letzte Aufnahme auswerten", enabled = !reportBusy && !measurementBusy, onClick = ::evaluateReport)
                }
                HorizontalDivider()
                Text(No5Text.ui("Aktuelle Karte · Wiederverwendung"), Modifier.testTag("renderer-current-view-title"))
                Text(No5Text.ui("Katalog %s · Quellgeneration %d · Renderfläche %d × %d",
                    chart.residentCatalogGeneration?.take(12) ?: "—", chart.chartSourceGeneration, chart.viewport.width, chart.viewport.height))
                Text(rendererWorkOverlayModel(chart, libraryState?.residency).budget)
                Text(No5Text.ui("15 Sekunden · gleicher Kartenbestand, Stil und Budget · sechs feste Ansichten. Keine dauerhafte Aktivierung."))
                RendererViewportReuseVariant.entries.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { variant ->
                            No5Selection(variant.name, currentViewVariant == variant,
                                { currentViewVariant = variant }, Modifier.weight(1f).testTag("renderer-current-view-${variant.name}"),
                                enabled = !measurementBusy && !reportBusy)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    No5Selection(No5Text.ui("Normal"), !currentViewDetailed,
                        { currentViewDetailed = false }, Modifier.weight(1f).testTag("renderer-current-view-normal"), !measurementBusy && !reportBusy)
                    No5Selection(No5Text.ui("Detailmessung"), currentViewDetailed,
                        { currentViewDetailed = true }, Modifier.weight(1f).testTag("renderer-current-view-detail"), !measurementBusy && !reportBusy)
                }
                val currentViewBlocker = rendererDiagnosticCameraStartBlocker(position,
                    measurementBusy || styleBusy || filterBusy || reportBusy || pendingExport != null || libraryState?.busy == true)
                    ?: currentViewReuseStartBlocker(chart, false)
                Text(No5Text.ui(currentViewBlocker ?: "Bereit: aktueller Kartenstand wird vom Renderer geprüft."),
                    Modifier.testTag("renderer-current-view-prerequisites"))
                Text(No5Text.ui("Abbrechen: Einstellungen → Renderer-Diagnose. Berichte enthalten private Karten- und Ansichtskennungen; nicht öffentlich teilen."))
                No5CompactAction("Aktuelle Karte messen", enabled = currentViewBlocker == null,
                    modifier = Modifier.testTag("renderer-current-view-start"), onClick = {
                        benchmarkStarting = true; profileReport = ""; reportNotice = null
                        scope.launch {
                            try {
                                check(rendererDiagnosticCameraStartBlocker(positions.state.value,
                                    styleBusy || filterBusy || reportBusy || pendingExport != null || chartLibrary?.state?.value?.busy == true) == null) {
                                    No5Text.ui("Andere Kartenarbeit zuerst abschließen.")
                                }
                                val snapshot = checkNotNull(engine.snapshotRendererOuterState(java.util.UUID.randomUUID().toString())) {
                                    No5Text.ui("Kartenstand für die Messung nicht bestätigt.")
                                }
                                val config = currentViewReuseConfig(snapshot, engine.status.value, currentViewVariant,
                                    currentViewDetailed, android.os.SystemClock.elapsedRealtime())
                                check(rendererDiagnosticCameraStartBlocker(positions.state.value,
                                    styleBusy || filterBusy || reportBusy || pendingExport != null || chartLibrary?.state?.value?.busy == true) == null) {
                                    No5Text.ui("Andere Kartenarbeit zuerst abschließen.")
                                }
                                val previousPanel = state.shell.destination to state.shell.panelExpanded
                                val result = engine.startRendererBenchmark(config)
                                if (result.success) {
                                    currentViewUiRun = result.message; currentViewReturnPanel = previousPanel
                                    state = state.copy(shell = state.shell.copy(panelExpanded = false)); showRenderer = false
                                } else reportNotice = result.message
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) { reportNotice = No5Text.ui("Messung konnte nicht starten: %s", failure.message ?: No5Text.ui("Rendererfehler")) }
                            finally { benchmarkStarting = false }
                        }
                    })
                HorizontalDivider()
                Text(No5Text.ui("Boston · Referenztests"))
                Text(com.no5.ui.No5Text.ui("Der Start verwendet vorübergehend die mitgelieferten Boston-Testkarten (NOAA, Repository-Stand 03.10.2026). Nutzerkarten und gespeicherte Auswahl bleiben unabhängig. Anschließend werden ursprüngliche Karten, Ansicht und Stil wiederhergestellt. Testdaten dienen nur dem Renderer-Test, nicht der Navigation."))
                No5CompactAction(label = "NOAA-Quellen und Nutzungshinweise", onClick = { showBenchmarkSources = true })
                Text(com.no5.ui.No5Text.ui("Mit dem Start bestätigst du die NOAA-Nutzungshinweise für diese Testdaten."))
                val blocker = rendererBenchmarkStartBlocker(chart, measurementBusy || styleBusy || reportBusy || pendingExport != null || libraryState?.busy == true)
                if (!chart.rendererBenchmark.active) Text(blocker ?: No5Text.ui("Bereit: festes Boston-Testpaket verfügbar. %s", if (chart.chartLoaded) No5Text.ui("Aktuell %d Nutzerzellen; Auswahl wird wiederhergestellt.", chart.loadedChartCells.size) else No5Text.ui("Keine Nutzerkarte aktiv; nach dem Test bleibt die Auswahl leer.")), Modifier.testTag("renderer-benchmark-prerequisites"))
                Text(com.no5.ui.No5Text.ui("Standardtest: aktueller Kartenstil und S52-Einstellungen, feste Boston-Kamera · normal, dann detailliert · jeweils 5 Warmup + 30 Messframes. Wallzeit und Renderthread-CPU sind keine GPU-Dauer."))
                No5CompactAction(label = if (profiling) "Standardtest läuft…" else "Boston · aktueller Stil", enabled = blocker == null, onClick = {
                    profiling = true; profileReport = ""; reportNotice = No5Text.ui("Standardtest wird gestartet …")
                    scope.launch {
                        try { retainResult(engine.profileRenderer()) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { reportNotice = No5Text.ui("Standardtest konnte nicht starten: %s", failure.message ?: No5Text.ui("Rendererfehler")) }
                        finally { profiling = false }
                    }
                }, modifier = Modifier.testTag("renderer-boston-profile"))
                Text(com.no5.ui.No5Text.ui("Stil-Benchmark: Glacier/Standard und S01/R04 · 2 × ABBA · feste Kamera und Bewegung · 5 Warmup + 30 Messframes pro Gruppe."))
                if (chart.rendererBenchmark.active) {
                    Text(if (chart.rendererBenchmark.runKind == RendererRunKind.RESIDENT_CAPACITY)
                        No5Text.ui("%s · aktueller Kartenbestand · %s", chart.rendererBenchmark.phase, chart.rendererBenchmark.message)
                        else "${chart.rendererBenchmark.phase} · ${chart.rendererBenchmark.completedGroups}/${chart.rendererBenchmark.totalGroups} · Frame ${chart.rendererBenchmark.groupFrame}/35 · ${chart.rendererBenchmark.message}", Modifier.testTag("renderer-benchmark-progress"))
                    No5CompactAction(label = "Benchmark abbrechen und wiederherstellen", onClick = { engine.cancelRendererBenchmark() }, modifier = Modifier.testTag("renderer-benchmark-cancel"))
                } else {
                    if (chart.rendererBenchmarkRecoveryPending) {
                        No5CompactAction(label = "Ursprüngliche Ansicht wiederherstellen", enabled = !profiling && !benchmarkStarting && !chart.rendererBenchmark.active && chart.surfaceAttached, onClick = { scope.launch {
                            val result = engine.restoreInterruptedRendererBenchmark()
                            if (!result.success) reportNotice = result.message
                        } }, modifier = Modifier.testTag("renderer-benchmark-recover"))
                    }
                    No5CompactAction(label = if (benchmarkStarting) "Vergleich wird gestartet …" else "Boston · Glacier / S01", enabled = blocker == null, onClick = {
                        benchmarkStarting = true; reportNotice = No5Text.ui("Stilvergleich wird gestartet …")
                        scope.launch {
                            try {
                                val result = engine.startRendererBenchmark()
                                if (!result.success) reportNotice = result.message.ifBlank { No5Text.ui("Stilvergleich konnte nicht starten. Bitte Voraussetzungen prüfen.") }
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) { reportNotice = No5Text.ui("Stilvergleich konnte nicht starten: %s", failure.message ?: No5Text.ui("Rendererfehler")) }
                            finally { benchmarkStarting = false }
                        }
                    }, modifier = Modifier.testTag("renderer-benchmark-start"))
                    if (chart.rendererBenchmark.runId.isNotBlank() || chart.rendererBenchmarkRecoveryPending) Text("${chart.rendererBenchmark.phase} · ${No5Text.ui(chart.rendererBenchmark.message)}\n${chart.rendererBenchmark.reportFile}", Modifier.testTag("renderer-benchmark-result"))
                }
                SelectionContainer { Text(chart.rendererDiagnostics) }
                Text(No5Text.ui("%s %s · Renderfläche %d × %d px",android.os.Build.MANUFACTURER,android.os.Build.MODEL,snapshot?.widthPx ?: 0,snapshot?.heightPx ?: 0))
            }
        }

        if(showBenchmarkSources)No5DetailsDialog("NOAA-Testkarten",shell.redNight,{showBenchmarkSources=false},body=benchmarkSources,tag="renderer-sources")
        if(showDetails)No5DetailsDialog("Objektdetails",shell.redNight,{showDetails=false},body=No5Text.ui(shell.chart.targetSummary),tag="map-object-details")
        if(showPositionSources)MapPositionDialog(position,onDismiss={showPositionSources=false},
            onChoice={choice,id->positions.select(choice,id,System.currentTimeMillis())})
        if(showConnection)No5DetailsDialog("Diagnose · Helm-Gateway",shell.redNight,{showConnection=false},tag="map-connection",
            body=if(onHomeGateway!=null)No5Text.ui("Gateway-Zugang wird für dieses Gerät einmal in Lumina Control eingerichtet.")+"\n"+endpoint else null,
            actions=listOf(
                No5WorkspaceView.Action("Schließen",key="map-connection-close",action={showConnection=false}),
                No5WorkspaceView.Action(if(onHomeGateway!=null)"In Lumina Control einrichten"else"Verbinden",key="map-connection-setup",action={
                    if(onHomeGateway!=null){onHomeGateway();showConnection=false}
                    else if(address.startsWith("http://")||address.startsWith("https://")){onConnect(address);showConnection=false}
                }))){
            if(onHomeGateway==null)OutlinedTextField(address,{address=it},label={Text("http://host:port")},singleLine=true,modifier=Modifier.fillMaxWidth())
        }


    }
    }
    }
}

/** Transient feedback is separate from the confirmed setting value. */
@Composable private fun MapControlSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    com.no5.ui.No5ActionFeedback(enabled = enabled, feedbackColor = MaterialTheme.colorScheme.primary) { feedback ->
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = feedback.enabled,
            modifier = modifier.then(feedback.modifier), interactionSource = feedback.interactionSource)
    }
}
