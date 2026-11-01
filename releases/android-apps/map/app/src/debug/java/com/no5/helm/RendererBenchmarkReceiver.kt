package com.no5.helm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.no5.helm.engine.RendererOuterSnapshotExporter
import org.json.JSONObject
import com.no5.helm.chart.RendererBenchmarkConfig
import com.no5.helm.chart.ChartBufferTransformMode
import com.no5.helm.chart.RendererRunKind
import com.no5.helm.chart.RendererReplayRecipe
import com.no5.helm.chart.ChartResidentBudget
import com.no5.helm.chart.ResidentCapacityArm
import com.no5.helm.chart.ResidentCapacityCacheMode
import com.no5.helm.chart.RendererViewportReuseVariant
import com.no5.helm.chart.CurrentResidentLiveArm

/** No hardware profile is inferred from these requested experimental budgets. */
internal fun rendererBenchmarkConfig(intent: Intent): RendererBenchmarkConfig {
    require(intent.hasExtra("deadlineElapsedRealtimeMs")) { "START requires a device elapsedRealtime deadline" }
    val kind = RendererRunKind.valueOf(intent.getStringExtra("runKind") ?: "SCRIPTED")
    val arm = if (kind == RendererRunKind.RESIDENT_CAPACITY) {
        listOf("capacityCells", "capacityBytes", "capacityLoadingBytes", "capacityCacheMode",
            "expectedCatalogGeneration", "expectedSourceGeneration", "capacityDurationMs").forEach {
            require(intent.hasExtra(it)) { "RESIDENT_CAPACITY requires $it" }
        }
        ResidentCapacityArm(ChartResidentBudget(intent.getIntExtra("capacityCells", -1),
            intent.getLongExtra("capacityBytes", -1), intent.getLongExtra("capacityLoadingBytes", -1)),
            ResidentCapacityCacheMode.valueOf(requireNotNull(intent.getStringExtra("capacityCacheMode"))),
            requireNotNull(intent.getStringExtra("expectedCatalogGeneration")),
            intent.getLongExtra("expectedSourceGeneration", -1), intent.getLongExtra("capacityDurationMs", -1))
    } else null
    return RendererBenchmarkConfig(
        repetitions = intent.getIntExtra("repetitions", if (arm != null) 1 else 2),
        clientRunToken = requireNotNull(intent.getStringExtra("clientRunToken")) { "START requires clientRunToken" },
        deadlineElapsedRealtimeMs = intent.getLongExtra("deadlineElapsedRealtimeMs", -1),
        recoveryBudgetMs = intent.getLongExtra("recoveryBudgetMs", 120_000),
        requestedWorkers = intent.getIntExtra("requestedWorkers", 1), runKind = kind,
        liveMode = ChartBufferTransformMode.valueOf(intent.getStringExtra("liveMode") ?: "CONTROL"),
        liveDurationMs = intent.getLongExtra("liveDurationMs", 10_000), liveReplay = intent.getBooleanExtra("liveReplay", true),
        liveReplayRecipe = RendererReplayRecipe.valueOf(intent.getStringExtra("liveReplayRecipe") ?: "LEGACY"),
        liveRepaintTransforms = intent.getBooleanExtra("liveRepaintTransforms", true), capacityArm = arm,
        capacityRecipeId = intent.getStringExtra("capacityRecipeId"),
        viewportReuseCandidate = intent.getStringExtra("viewportReuseCandidate")?.let(RendererViewportReuseVariant::valueOf),
        viewportReuseDetailed = intent.getBooleanExtra("viewportReuseDetailed", false),
        viewportReuseParity = intent.getBooleanExtra("viewportReuseParity", false),
        normalRenderAbort = intent.getBooleanExtra("normalRenderAbort", false),
        currentLiveParityOnly = intent.getBooleanExtra("currentLiveParityOnly", false),
        currentResidentLive = intent.getStringExtra("currentResidentSnapshotId")?.let {
            require(intent.getStringExtra("expectedSourceCommit") == com.no5.helm.app.BuildConfig.SOURCE_COMMIT) { "Current-view source pin required" }
            CurrentResidentLiveArm(it)
        })
}

/** Debug-only, DUMP-protected shell control. Requires an already resumed app; never launches it. */
class RendererBenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val host = MainActivity.benchmarkHost.get()
        if (host == null) {
            resultCode = 1
            resultData = JSONObject().put("success", false).put("message", "Open No5 and keep it in the foreground first").toString()
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val token = intent.getStringExtra("clientRunToken")
                var outerSnapshotExport: JSONObject? = null
                val result = when (intent.action) {
                    "com.no5.map.benchmark.START" -> {
                        val expectedSource = intent.getStringExtra("expectedSourceCommit")
                        require(expectedSource == null || expectedSource == com.no5.helm.app.BuildConfig.SOURCE_COMMIT) { "Source commit pin mismatch" }
                        host.startBenchmark(rendererBenchmarkConfig(intent))
                    }
                    "com.no5.map.benchmark.OUTER_SNAPSHOT" -> {
                        require(intent.getStringExtra("expectedSourceCommit") == com.no5.helm.app.BuildConfig.SOURCE_COMMIT) { "Source pin required" }
                        val id = requireNotNull(intent.getStringExtra("snapshotId"))
                        val snapshot = host.snapshotOuterState(id)
                        val exported = withContext(Dispatchers.IO) {
                            RendererOuterSnapshotExporter(context.filesDir).export(snapshot, id,
                                com.no5.helm.app.BuildConfig.SOURCE_COMMIT)
                        }
                        outerSnapshotExport = JSONObject(exported.asJsonString())
                        com.no5.helm.chart.EngineResult(true, id)
                    }
                    "com.no5.map.benchmark.RESTORE" -> {
                        require(intent.getStringExtra("expectedSourceCommit") == com.no5.helm.app.BuildConfig.SOURCE_COMMIT) { "Source pin required" }
                        host.restoreOuterState(requireNotNull(intent.getStringExtra("expectedSnapshotId")))
                    }
                    "com.no5.map.benchmark.CANCEL" -> host.cancelBenchmark(requireNotNull(intent.getStringExtra("expectedRunId")) { "CANCEL requires expectedRunId" })
                    "com.no5.map.benchmark.STATUS" -> com.no5.helm.chart.EngineResult(true)
                    "com.no5.map.benchmark.T0_MODE" -> host.setBufferTransformMode(ChartBufferTransformMode.valueOf(
                        requireNotNull(intent.getStringExtra("mode")) { "T0_MODE requires mode" }))
                    else -> com.no5.helm.chart.EngineResult(false, "Unknown benchmark command")
                }
                check(MainActivity.benchmarkHost.get() === host) { "Foreground owner changed during command" }
                val status = host.benchmarkStatus(if (intent.action == "com.no5.map.benchmark.CANCEL") null else token)
                outerSnapshotExport?.let { ref ->
                    check(status.getLong("chartSourceGeneration") == ref.getLong("sourceGeneration") &&
                        status.getBoolean("residentCapacityEnabled") && !status.getBoolean("active")) {
                        "Snapshot source/run owner changed during file export"
                    }
                }
                val reply = status.put("success", result.success).put("commandMessage", result.message.take(256))
                    .put("outerSnapshotExport", outerSnapshotExport ?: JSONObject.NULL).toString()
                check(reply.toByteArray(Charsets.UTF_8).size <= 8_192) { "Debug result envelope exceeds transport bound" }
                pending.resultCode = if (result.success) 0 else 1
                pending.resultData = reply
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                pending.resultCode = 1
                pending.resultData = JSONObject().put("success", false).put("commandMessage", failure.message?.take(256))
                    .put("commit", com.no5.helm.app.BuildConfig.SOURCE_COMMIT).put("processId", android.os.Process.myPid()).toString()
            } finally { pending.finish() }
        }
    }
}
