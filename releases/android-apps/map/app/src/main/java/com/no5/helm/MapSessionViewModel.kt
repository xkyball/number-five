package com.no5.helm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.no5.gateway.LuminaGatewayClient
import com.no5.helm.chart.ChartEngineConfig
import com.no5.helm.client.HelmClient
import com.no5.helm.engine.NativeChartEngine
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One map/camera/library owner across Activity recreation. No Activity or Surface is retained. */
@OptIn(FlowPreview::class)
internal class MapSessionViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val engine = NativeChartEngine(diagnosticPermission = {
        context.checkCallingOrSelfPermission("android.permission.DUMP") == android.content.pm.PackageManager.PERMISSION_GRANTED
    })
    val client = HelmClient(scope)
    private val positionPreferences = com.no5.helm.ui.MapPositionPreferences(context.getSharedPreferences("map-position-v1", 0))
    val positions = com.no5.helm.model.MapPositionController(com.no5.helm.ui.ChartPositionCamera(engine),
        positionPreferences.load(), positionPreferences::save)
    init {
        scope.launch { client.state.collect { positions.update(it, System.currentTimeMillis()) } }
        scope.launch { while (isActive) { positions.tick(System.currentTimeMillis()); delay(1000) } }
    }
    val gateway = LuminaGatewayClient(context)
    private val cameras = ChartCameraStore(context.filesDir)
    val charts = PersistentChartController(context, engine, scope, cameras::load)
    private var gatewayIdentity = ""
    private val observation = gateway.observe {
        val identity = it.identity ?: "unavailable"
        if (identity != gatewayIdentity) {
            gatewayIdentity = identity
            gateway.currentSession()?.let { session -> client.connectGateway(session, context) }
                ?: client.needsHomeConfiguration()
        }
    }
    val prepared = scope.async<Unit>(start = CoroutineStart.LAZY) {
        val data = File(context.filesDir, "chart-data")
        withContext(Dispatchers.IO) { ChartAssetInstallation.prepare(context, data) }
        val outlines = context.getSharedPreferences("native-screens-v1", 0).getBoolean("touchOutlinesEnabled", true)
        val result = engine.initialize(ChartEngineConfig(data.absolutePath, com.no5.helm.app.BuildConfig.VERSION_NAME,
            context.resources.displayMetrics.densityDpi, com.no5.helm.app.BuildConfig.SOURCE_COMMIT, outlines))
        check(result.success) { result.message }
        charts.prepare()
        scope.launch {
            var pendingRecovery = engine.status.value.rendererBenchmarkRecoveryPending
            var reconciledRun = ""
            engine.status.collect { status ->
                pendingRecovery = pendingRecovery || status.rendererBenchmarkRecoveryPending
                val finished = status.rendererBenchmark
                if (!status.rendererBenchmarkRecoveryPending && !finished.active &&
                    (pendingRecovery || (finished.restored && finished.runId.isNotBlank() && finished.runId != reconciledRun))) {
                    charts.reconcileAfterBenchmarkRecovery(); pendingRecovery = false; reconciledRun = finished.runId
                }
            }
        }
        scope.launch {
            combine(engine.status, charts.state) { status, library ->
                val active = library.snapshot.active
                if (active == null || library.busy || !status.chartLoaded || !status.residentCameraReady ||
                    status.rendererBenchmark.active || status.rendererBenchmarkRecoveryPending || status.rendererCapture.active)
                    null else active to status.viewport
            }.debounce(150).filterNotNull().distinctUntilChanged().conflate().collect { (selection, camera) ->
                withContext(Dispatchers.IO) { runCatching { cameras.save(selection, camera) }.onFailure {
                    android.util.Log.w("No5CameraState", "Camera state could not be saved")
                } }
            }
        }
    }
    override fun onCleared() {
        observation.close(); gateway.close(); charts.close(); client.close(); engine.close(); scope.cancel()
        super.onCleared()
    }
}
