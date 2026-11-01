package com.no5.helm.ui
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.no5.helm.chart.*
import com.no5.helm.model.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable fun HelmScreen(engine: ChartEngine, backend: StateFlow<HelmState>, endpoint: String, chartLabel: String = "Public S57 test cell", initialViewport: ChartViewport = engine.status.value.viewport, onConnect: (String) -> Unit) {
    val chart by engine.status.collectAsStateWithLifecycle()
    val helm by backend.collectAsStateWithLifecycle()
    var viewport by remember { mutableStateOf(initialViewport) }
    var surfaceScale by remember { mutableDoubleStateOf(1.0) }
    val reset = remember { initialViewport }
    var features by remember { mutableStateOf<List<ChartFeature>>(emptyList()) }
    var inspect by remember { mutableStateOf(false) }
    var s52 by remember { mutableStateOf(S52Settings()) }
    var error by remember { mutableStateOf("") }
    var showStyles by remember { mutableStateOf(false) }
    var switchingStyle by remember { mutableStateOf(false) }
    var restoredStyle by remember { mutableStateOf(false) }
    var showRenderer by remember { mutableStateOf(false) }
    var profiling by remember { mutableStateOf(false) }
    var profileReport by remember { mutableStateOf("") }
    var showConnect by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(endpoint) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val savedStyles = remember { SavedChartProfiles(context.getSharedPreferences("chart-presentation-v1", android.content.Context.MODE_PRIVATE)) }
    var savedProfiles by remember { mutableStateOf(savedStyles.profiles) }
    LaunchedEffect(chart.chartLoaded, chart.surfaceAttached) {
        if (chart.chartLoaded && chart.surfaceAttached && !restoredStyle) {
            restoredStyle = true
            val result = engine.applyPresentationProfile(savedStyles.profiles.forMode(savedStyles.activeMode))
            if (!result.success) error = result.message
        }
    }
    val exportProfile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(profileReport.toByteArray(Charsets.UTF_8)) } ?: error("Cannot open export destination") } }
            catch (failure: Exception) { error = failure.message ?: "Profile export failed" }
        }
    }
    fun changeViewport(value: ChartViewport) { viewport = value; engine.setViewport(value) }
    LaunchedEffect(helm.snapshot) {
        helm.snapshot?.state?.let { state ->
            state.ownship?.let { engine.setOwnship(GeoProjection(it.position.latitude, it.position.longitude)) } ?: engine.clearOwnship()
            engine.setAisTargets(state.ais.map { GeoProjection(it.position.latitude, it.position.longitude, it.headingRadians) })
            engine.setWaypointProjection(state.waypoints.map { GeoProjection(it.position.latitude, it.position.longitude) })
            val route = state.routes.find { it.id == state.activeRouteId }
            engine.setRouteProjection(route?.let { RouteProjection(it.waypointIds.mapNotNull { id -> state.waypoints.find { it.id == id }?.let { wp -> GeoProjection(wp.position.latitude, wp.position.longitude) } }) })
        }
    }
    MaterialTheme {
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { context -> ChartSurfaceView(context).apply {
                onSurfaceReady = { surface, width, height ->
                    surfaceScale = ChartSurfaceGeometry.fit(width, height).scale
                    resizeSurface(width, height)
                    engine.attachSurface(surface, width, height)
                }
                onSurfaceRelease = { release -> engine.detachSurface(release) }
            } }, modifier = Modifier.fillMaxSize().testTag("chart-input"), update = { view ->
                view.onViewport = ::changeViewport
                view.acceptViewport(viewport)
                view.inspectEnabled = inspect
                view.onTap = { point ->
                    inspect = false
                    view.inspectEnabled = false
                    scope.launch { try { features = engine.queryFeature(point) } catch (e: Exception) { error = e.message ?: "Feature query failed" } }
                }
            })
            Surface(color = if (helm.connection == ConnectionState.LIVE) Color(0xE61D4E50) else Color(0xE6883B20), modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Text("No 5   ${helm.connection}   Revision ${helm.snapshot?.revision ?: "—"}", color = Color.White)
                    Text("$chartLabel · ${chart.featureCount} objects · Development preview", color = Color.White)
                    val ship = helm.snapshot?.state?.ownship
                    Text(if (ship == null) "Backend required" else "SOG %.1f kn   Depth %.1f m   Heading %.0f°   AIS %d".format(ship.sogMetresPerSecond?.times(1.94384), ship.depthMetres, ship.headingRadians?.let(Math::toDegrees), helm.snapshot!!.state.ais.size), color = Color.White)
                    if (chart.message.isNotEmpty()) Text(chart.message, color = Color.Yellow)
                }
            }
            Surface(Modifier.align(Alignment.CenterEnd).padding(12.dp), color = Color(0xED173D43), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Zoom %.1f×".format(Locale.ROOT, chart.viewport.metresToPixels * surfaceScale / reset.metresToPixels), color = Color.White, modifier = Modifier.testTag("chart-zoom"))
                    Button(onClick = { changeViewport(transformChartViewport(viewport, zoom = 2.0)) }, enabled = viewport.metresToPixels < 10.0,
                        modifier = Modifier.testTag("zoom-in").semantics { contentDescription = "Zoom in" }) { Text("+") }
                    Button(onClick = { changeViewport(transformChartViewport(viewport, zoom = 0.5)) }, enabled = viewport.metresToPixels > 0.00001,
                        modifier = Modifier.testTag("zoom-out").semantics { contentDescription = "Zoom out" }) { Text("−") }
                }
            }
            Row(Modifier.align(Alignment.BottomCenter).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { changeViewport(reset.copy(width = viewport.width, height = viewport.height,
                    metresToPixels = (reset.metresToPixels / surfaceScale).coerceIn(0.00001, 10.0))) }) { Text("Reset") }
                Button(onClick = { inspect = !inspect }, modifier = Modifier.testTag("inspect-mode")) { Text(if (inspect) "Info: tap chart" else "Object info") }
                Button(enabled = chart.presentationAvailable && !profiling, onClick = { showStyles = true }) { Text("Kartenstil") }
                Button(onClick = { showRenderer = true }, modifier = Modifier.testTag("renderer-diagnostics")) { Text("Renderer") }
                Button(onClick = { showConnect = true }) { Text("Connect") }
            }
            if (showStyles) ChartStyleSettings(chart.presentation, savedProfiles, switchingStyle,
                onDismiss = { showStyles = false }, onApply = { candidate ->
                    switchingStyle = true
                    scope.launch {
                        try {
                            val result = engine.applyPresentationProfile(candidate)
                            if (result.success) { savedStyles.confirmed(candidate); savedProfiles = savedStyles.profiles }
                            else error = result.message
                        } finally { switchingStyle = false }
                    }
                })
            if (showRenderer) AlertDialog(onDismissRequest = { if (!profiling) showRenderer = false },
                confirmButton = { TextButton(onClick = { showRenderer = false }, enabled = !profiling) { Text("Close") } },
                title = { Text("Renderer audit · ARM64") },
                text = { SelectionContainer { Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(chart.rendererDiagnostics)
                    Text("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; Android ${android.os.Build.VERSION.RELEASE}")
                    Text("Boston profile: current chart style and safety settings, fixed camera; 5 warmup + 30 measured frames. EGL/swap includes driver work; GPU duration unavailable.")
                    Button(enabled = !profiling, onClick = {
                        profiling = true; profileReport = ""
                        scope.launch {
                            try {
                                val result = engine.profileRenderer()
                                if (result.success) { profileReport = result.message; viewport = engine.status.value.viewport }
                                else error = result.message
                            } finally { viewport = engine.status.value.viewport; profiling = false }
                        }
                    }, modifier = Modifier.testTag("renderer-boston-profile")) { Text(if (profiling) "Profiling…" else "Run Boston profile") }
                    if (profileReport.isNotEmpty()) TextButton(onClick = { exportProfile.launch("no5-boston-renderer-profile.json") }) { Text("Export profile JSON") }
                } } })
            if (features.isNotEmpty()) AlertDialog(onDismissRequest = { features = emptyList() }, confirmButton = { TextButton(onClick = { features = emptyList() }) { Text("Close") } }, title = { Text("Chart features") }, text = { Text(features.take(6).joinToString("\n\n") { "${it.name} (${it.id})\n" + it.attributes.entries.take(10).joinToString("\n") { a -> "${a.key}: ${a.value}" } }) })
            if (error.isNotEmpty()) AlertDialog(onDismissRequest = { error = "" }, confirmButton = { TextButton(onClick = { error = "" }) { Text("Close") } }, text = { Text(error) })
            if (showConnect) AlertDialog(onDismissRequest = { showConnect = false }, title = { Text("Boat backend") }, text = { OutlinedTextField(address, { address = it }, label = { Text("http://host:port") }, singleLine = true) }, confirmButton = { TextButton(onClick = { if (address.startsWith("http://") || address.startsWith("https://")) { onConnect(address); showConnect = false } }) { Text("Connect") } })
        }
    }
}
