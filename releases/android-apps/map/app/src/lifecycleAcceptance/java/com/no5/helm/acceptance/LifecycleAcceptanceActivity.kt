package com.no5.helm.acceptance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.no5.helm.PersistentChartController
import com.no5.helm.app.BuildConfig
import com.no5.helm.chart.ChartEngineConfig
import com.no5.helm.engine.NativeChartEngine
import com.no5.helm.library.ChartLibrary
import com.no5.helm.library.ChartSelection
import com.no5.helm.model.HelmState
import com.no5.helm.ui.NativeHelmScreen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Separately installed test host. Network fixtures require explicit emulator-only opt-in. */
class LifecycleAcceptanceActivity : ComponentActivity() {
    lateinit var acceptanceContext: LifecycleAcceptanceContext
        private set
    lateinit var engine: LifecycleAcceptanceEngine
        private set
    lateinit var controller: PersistentChartController
        private set
    var prepared by mutableStateOf(false)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    private val offline = MutableStateFlow(HelmState(issue = "Isolated offline lifecycle fixture"))
    private var native: NativeChartEngine? = null
    var routeClient: com.no5.helm.client.HelmClient? = null
        private set
    fun fixtureSnapshot(value: HelmState) { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")); offline.value = value }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        require(packageName == LifecycleAcceptanceContext.APPLICATION_ID)
        val hostPreferences = getSharedPreferences("lifecycle-acceptance-host", MODE_PRIVATE)
        val runId = savedInstanceState?.getString(LifecycleAcceptanceContext.RUN_EXTRA)
            ?: intent.getStringExtra(LifecycleAcceptanceContext.RUN_EXTRA)
            ?: hostPreferences.getString("lastRun", null)
            ?: LifecycleAcceptanceContext.newRunId()
        acceptanceContext = LifecycleAcceptanceContext(this, runId)
        check(hostPreferences.edit().putString("lastRun", runId).commit())
        setContent { Text("LIFECYCLE TEST · eigener Testbestand wird vorbereitet") }
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { prepareRenderingAssets() }
                val actual = NativeChartEngine().also { native = it }
                val initialized = actual.initialize(ChartEngineConfig(data.absolutePath,
                    "LIFECYCLE ACCEPTANCE · ${BuildConfig.VERSION_NAME}", resources.displayMetrics.densityDpi,
                    BuildConfig.SOURCE_COMMIT, false))
                check(initialized.success) { initialized.message }
                intent.getStringExtra("routeFixtureCa")?.let { path ->
                    check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
                    val ca = File(path).canonicalFile
                    require(ca.toPath().startsWith(cacheDir.canonicalFile.toPath()))
                    val access = intent.getStringExtra("routeFixtureAccess")?.let { accessPath ->
                        val file = File(accessPath).canonicalFile
                        require(file.toPath().startsWith(cacheDir.canonicalFile.toPath()) && file.length() <= 16384)
                        org.json.JSONObject(file.readText()).also { require(it.getInt("port") == 9443) }
                    }
                    val origin = com.no5.gateway.GatewayOrigin("no5-marineos-lab.local", access?.getInt("port") ?: 19443)
                    val record = com.no5.gateway.GatewayConfiguration(origin, access?.getString("address") ?: "10.0.2.2", com.no5.gateway.GatewayAddressSource.MANUAL, checkedAt = System.currentTimeMillis())
                    val key = access?.getString("keyBase64")?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
                        ?: "map-route-acceptance-key".toByteArray()
                    val profile = com.no5.helm.security.GatewaySecurityProfile.create(com.no5.helm.security.GatewayOrigin.fromEndpoint(origin.url),
                        key, ca.readBytes())
                    routeClient = com.no5.helm.client.HelmClient(lifecycleScope).also { it.connectGateway(record, profile) }
                }
                engine = LifecycleAcceptanceEngine(actual, acceptanceContext)
                controller = PersistentChartController(acceptanceContext, engine, lifecycleScope)
                // Surface can appear before prepare completes; no production Activity startup is involved.
                setContent {
                    CompositionLocalProvider(LocalContext provides acceptanceContext) {
                        val state by controller.state.collectAsState()
                        val barrier by engine.barrier.collectAsState()
                        MaterialTheme {
                            Column(Modifier.fillMaxSize()) {
                                Text("LIFECYCLE TEST · ${acceptanceContext.runId}", Modifier.testTag("lifecycle-test-marker"))
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FixtureButton("NOAA Basis", "fixture-seed", prepared && !state.busy) { importFixture(LifecycleFixture.SEED) }
                                    FixtureButton("Aktivieren", "fixture-activate", prepared && !state.busy && state.snapshot.packages.isNotEmpty()) { activateNoaaFixture() }
                                    FixtureButton("Update", "fixture-update", prepared && !state.busy && state.snapshot.active != null) { updateNoaaFixture() }
                                    FixtureButton("Gap-Fehler", "fixture-gap", prepared && !state.busy && state.snapshot.active != null) { updateNoaaFixture(LifecycleFixture.GAP) }
                                    FixtureButton("Langsamer Import", "fixture-slow", prepared && !state.busy) { importFixture(LifecycleFixture.SLOW_SEED) }
                                    FixtureButton("WSV Basis", "fixture-wsv", prepared && !state.busy) { importFixture(LifecycleFixture.WSV) }
                                    FixtureButton("K1 Update", "fixture-k1", prepared && !state.busy && state.snapshot.active != null) { beginBarrierUpdate(LifecycleCrashPoint.SOURCE_COMMITTED_BEFORE_SELECTION) }
                                    FixtureButton("K2 Update", "fixture-k2", prepared && !state.busy && state.snapshot.active != null) { beginBarrierUpdate(LifecycleCrashPoint.SELECTION_PUBLISHED_RELEASE_ACKED_BEFORE_FILES) }
                                    FixtureButton("Fortsetzen", "fixture-continue", barrier != null) { barrier?.let { engine.continueAfterObservation(it.token) } }
                                }
                                barrier?.let { Text("TESTBARRIERE ${it.point} · ${it.file.name}", Modifier.testTag("lifecycle-test-barrier")) }
                                failure?.let { Text(it, Modifier.testTag("lifecycle-test-error")) }
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    val active = state.snapshot.active
                                    NativeHelmScreen(engine, offline, "Offline lifecycle fixture",
                                        "TEST · ${active?.cells?.size ?: 0} aktive Zellen", engine.status.value.viewport,
                                        controller, routeActions = routeClient, onConnect = {})
                                }
                            }
                        }
                    }
                }
                controller.prepare()
                engine.recordSnapshot("prepared")
                prepared = true
            } catch (problem: Exception) {
                failure = problem.message ?: problem.javaClass.simpleName
                if (!::controller.isInitialized) setContent { Text("LIFECYCLE TEST fehlgeschlagen: $failure") }
            }
        }
    }
    @Composable private fun FixtureButton(text: String, tag: String, enabled: Boolean, action: () -> Unit) {
        TextButton(onClick = { runCatching(action).onFailure { failure = it.message ?: "Testaktion fehlgeschlagen" } },
            enabled = enabled, modifier = Modifier.testTag(tag)) { Text(text) }
    }
    fun importFixture(fixture: LifecycleFixture) {
        check(prepared && !controller.state.value.busy)
        controller.importPackage(LifecycleAcceptanceFixtures.uri(acceptanceContext, fixture))
    }
    fun activateNoaaFixture() {
        val pack = controller.state.value.snapshot.packages.firstOrNull { it.label.startsWith("D1-NOAA-SEED") }
            ?: error("Zuerst eigene NOAA-Basis importieren")
        check(pack.cells.map { it.name }.toSet() == setOf("US5BOSCC", "US5BOSDD"))
        controller.activate(ChartSelection(pack.id, listOf("US5BOSCC", "US5BOSDD")))
    }
    fun updateNoaaFixture(fixture: LifecycleFixture = LifecycleFixture.UPDATE) {
        check(fixture == LifecycleFixture.UPDATE || fixture == LifecycleFixture.GAP)
        val active = controller.state.value.snapshot.active ?: error("Keine aktive Testbasis")
        check(active.cells.toSet() == setOf("US5BOSCC", "US5BOSDD"))
        controller.updatePackage(active.packageId, LifecycleAcceptanceFixtures.uri(acceptanceContext, fixture))
    }
    fun beginBarrierUpdate(point: LifecycleCrashPoint): String {
        check(prepared && !controller.state.value.busy)
        val active = controller.state.value.snapshot.active ?: error("Keine aktive Testbasis")
        val pack = controller.state.value.snapshot.packages.single { it.id == active.packageId }
        check(pack.cells.single { it.name == "US5BOSCC" }.update == 2) { "K1/K2 benötigen unveränderte Seed-Update2-Basis" }
        val token = engine.arm(point, LifecycleAcceptanceFixtures.updatedNoaa(acceptanceContext))
        try { updateNoaaFixture() } catch (failure: Exception) { engine.abortBarrier(); throw failure }
        return token
    }
    fun library() = ChartLibrary(acceptanceContext.libraryRoot)
    private fun prepareRenderingAssets(): File {
        val data = acceptanceContext.requireOwned(File(acceptanceContext.filesDir, "chart-data")).apply { require(mkdirs() || isDirectory) }
        fun copy(path: String) {
            if (path == "charts" || path.startsWith("charts/") || path.startsWith("renderer-benchmark/") || path.startsWith("lifecycle-fixtures/")) return
            val entries = assets.list(path).orEmpty()
            if (entries.isNotEmpty()) { entries.forEach { copy(if (path.isEmpty()) it else "$path/$it") }; return }
            if (path.substringAfterLast('.', "").lowercase() !in setOf("csv", "xml", "rle", "png", "ttf", "txf", "json")) return
            val destination = acceptanceContext.requireOwned(File(data, path)).apply { parentFile?.mkdirs() }
            assets.open(path).use { input -> destination.outputStream().use { input.copyTo(it) } }
        }
        copy("")
        return data
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(LifecycleAcceptanceContext.RUN_EXTRA, acceptanceContext.runId)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        routeClient?.close(); routeClient = null
        if (::controller.isInitialized) controller.close()
        if (::engine.isInitialized) engine.close() else native?.close()
        super.onDestroy()
    }
}
