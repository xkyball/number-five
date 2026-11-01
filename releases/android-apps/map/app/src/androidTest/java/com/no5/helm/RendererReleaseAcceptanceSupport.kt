package com.no5.helm

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.system.Os
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.chart.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Test-APK only. No exported control, raw JNI, producer ACK, camera math or setting override. */
internal class RendererReleaseAcceptanceSupport(private val compose: ComposeTestRule? = null) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val arguments = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext
    val run: String = requireNotNull(arguments.getString("no5.renderer.run")).also {
        require(UUID.fromString(it).toString() == it)
    }
    private val root: File
    val directory: File
    val identity: JSONObject

    init {
        assertEquals("Installed production MAP only", "com.no5.map", context.packageName)
        assertEquals("Same-signer test APK required", PackageManager.SIGNATURE_MATCH,
            context.packageManager.checkSignatures(instrumentation.context.packageName, context.packageName))
        assertEquals("Regular release must remain nondebuggable", 0,
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val actualApk = File(context.applicationInfo.sourceDir).inputStream().use(::hash)
        val actualTestApk = File(instrumentation.context.applicationInfo.sourceDir).inputStream().use(::hash)
        assertEquals("Actual installed APK pin", pin("apkSha256"), actualApk)
        assertEquals("Actual same-signer test APK pin", pin("testApkSha256"), actualTestApk)
        val signers = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo!!.apkContentsSigners
        require(signers.size == 1) { "Exactly one current signer required" }
        val signer = signers.single().toByteArray().inputStream().use(::hash)
        assertEquals("Actual current signer pin", pin("signerSha256"), signer)
        val source = Class.forName("com.no5.helm.app.BuildConfig").getField("SOURCE_COMMIT").get(null) as String
        val expectedSource = requireNotNull(arguments.getString("no5.renderer.sourceCommit"))
        require(expectedSource.matches(Regex("[a-f0-9]{40}")))
        assertEquals("Actual release BuildConfig source pin", expectedSource, source)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        assertEquals("Actual release version pin", requireNotNull(arguments.getString("no5.renderer.versionCode")).toLong(), version)
        val native = ZipFile(context.applicationInfo.sourceDir).use { apk ->
            val entries = apk.entries().asSequence().filter { it.name.matches(Regex("lib/[^/]+/libno5_chart\\.so")) }.toList()
            require(entries.size == 1) { "One explicit native ABI required; no inferred loaded-library identity" }
            apk.getInputStream(entries.single()).use(::hash)
        }
        assertEquals("Actual APK native-library bytes pin", pin("nativeSha256"), native)
        val nativeDirectory = requireNotNull(context.applicationInfo.nativeLibraryDir).also {
            require(it.length in 1..4096 && it.startsWith("/") && it.none(Char::isISOControl)) {
                "Actual ApplicationInfo nativeLibraryDir unavailable or outside bound"
            }
        }
        root = File(context.filesDir, "renderer-release-proof")
        require(!Files.isSymbolicLink(root.toPath()))
        check(root.isDirectory || root.mkdir())
        Os.chmod(root.absolutePath, 0b111000000)
        directory = File(root, run)
        require(!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS) && directory.mkdir()) { "Never reuse a proof UUID" }
        Os.chmod(directory.absolutePath, 0b111000000)
        identity = JSONObject().put("schema", "no5.renderer-release-identity/1").put("run", run)
            .put("apkSha256", actualApk).put("testApkSha256", actualTestApk).put("signerSha256", signer)
            .put("sourceCommit", source).put("versionCode", version).put("nativeApkEntrySha256", native)
            .put("nativeLibraryDir", nativeDirectory)
            .put("debuggable", false).put("sameSigner", true).put("processId", Process.myPid())
            .put("processStartElapsedRealtimeMs", Process.getStartElapsedRealtime())
            .put("processStartMeasurement", "NOT_MEASURED; instrumentation may restart the target process")
        write("identity.json", identity.toString(2), 16 * 1024)
    }

    private fun pin(name: String) = requireNotNull(arguments.getString("no5.renderer.$name")).also {
        require(it.matches(Regex("[a-f0-9]{64}"))) { "Invalid $name" }
    }
    fun engine(activity: MainActivity): ChartEngine = resolveEngine(activity)
    private var readinessAttempt = 0
    /** Initialization/restoration only: never called from the timed owner polling loop. */
    fun ready(engine: ChartEngine) {
        check(++readinessAttempt <= 64) { "Readiness proof bound exceeded" }
        val started = SystemClock.elapsedRealtime()
        val gate = RendererReleaseReadinessGate(started)
        val clockBefore = compose?.mainClock?.currentTime
        var checks = 0
        var before = engine.status.value
        var after = before
        var ui: RendererReleaseReadinessUi? = null
        var uiSampleCompleted = false
        fun readyNow(): Boolean {
            before = engine.status.value
            after = before; ui = null; uiSampleCompleted = false
            checks++
            gate.engineReady(before, SystemClock.elapsedRealtime()) // Refuse owner/deadline before UI synchronization.
            ui = compose?.let(::rendererReleaseReadinessUi)
            after = engine.status.value
            uiSampleCompleted = compose != null
            return if (compose == null) gate.engineReady(after, SystemClock.elapsedRealtime())
                else gate.ready(before, requireNotNull(ui), after, SystemClock.elapsedRealtime())
        }
        fun evidence(status: String, failure: Throwable? = null): JSONObject {
            val now = SystemClock.elapsedRealtime()
            return JSONObject().put("schema", "no5.renderer-release-readiness/2").put("run", run)
                .put("status", status).put("attempt", readinessAttempt).put("budgetMs", 60_000)
                .put("startedElapsedRealtimeMs", started).put("deadlineElapsedRealtimeMs", gate.deadline)
                .put("observedElapsedRealtimeMs", now).put("elapsedMs", now - started).put("conditionChecks", checks)
                .put("composeProgress", if (compose == null) "LEGACY_WALL_SLEEP" else "EXISTING_RULE_IDLE_THEN_JOINT_ENGINE_SEMANTICS_WAIT")
                .put("composeAutoAdvance", compose?.mainClock?.autoAdvance ?: JSONObject.NULL)
                .put("composeClockBeforeMs", clockBefore ?: JSONObject.NULL)
                .put("composeClockAfterMs", compose?.mainClock?.currentTime ?: JSONObject.NULL)
                .put("failureType", failure?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("engineBeforeUi", readinessStatus(before)).put("engineAfterUi", readinessStatus(after))
                .put("sameEngineAuthorityAcrossUiSample", if (uiSampleCompleted) gate.sameAuthority(before, after) else JSONObject.NULL)
                .put("uiSampleCompleted", uiSampleCompleted).put("engineAtEvidence", readinessStatus(engine.status.value))
                .put("uiSampling", if (compose == null) "NOT_SAMPLED_LEGACY" else "ONE_MATCHER_QUERY_NOT_WINDOW_ATOMIC")
                .put("rendererSlotPresent", ui?.rendererPresent ?: JSONObject.NULL)
                .put("blockingTags", org.json.JSONArray(ui?.blockingTags?.sorted() ?: emptyList<String>()))
                .put("dialogPresent", ui?.dialogPresent ?: JSONObject.NULL)
                .put("semanticNodeLimitExceeded", ui?.boundsExceeded ?: false)
                .put("warningTexts", org.json.JSONArray(ui?.warningTexts ?: emptyList<String>()))
                .put("warningTextCoverage", "AT_MOST_8_TEXTS_X_160_CHARS_AND_32_DESCENDANTS; EVIDENCE_ONLY")
                .put("ownerStartedByReadiness", false).put("timingComparable", false)
                .put("physicalDisplay", "NOT_MEASURED")
        }
        try {
            if (compose != null) {
                check(compose.mainClock.autoAdvance) { "UNAVAILABLE_TEST_CLOCK: existing Compose clock is not advancing" }
                gate.engineReady(engine.status.value, SystemClock.elapsedRealtime())
                // Service the existing rule only. No Looper, engine flag, draw or acknowledgement is replaced.
                compose.waitForIdle()
                gate.engineReady(engine.status.value, SystemClock.elapsedRealtime())
                compose.waitUntil(timeoutMillis = maxOf(1, gate.deadline - SystemClock.elapsedRealtime()), condition = ::readyNow)
            } else {
                while (!readyNow()) SystemClock.sleep(25)
            }
            // A later legitimate source/UI transition is refused, never repaired or hidden by this fixture.
            check(readyNow()) { "UNAVAILABLE_CURRENT_FULL: authority or UI changed at the readiness boundary" }
            write("ready-success-$readinessAttempt.json", evidence(if (compose == null) "READY_ENGINE_ONLY_LEGACY" else "READY_ENGINE_AND_UI_OBSERVED").toString(2), 16 * 1024)
        } catch (failure: Throwable) {
            // Never acquire/replace an owner to collect evidence, nor extend the original deadline.
            try { write("ready-failure-$readinessAttempt.json", evidence("UNAVAILABLE", failure).toString(2), 16 * 1024) }
            catch (proofFailure: Throwable) { failure.addSuppressed(proofFailure) }
            throw failure
        }
    }
    private fun readinessStatus(value: ChartEngineStatus) = JSONObject()
        .put("initialized", value.initialized).put("chartLoaded", value.chartLoaded)
        .put("surfaceAttached", value.surfaceAttached).put("presentationPending", value.presentationPending)
        .put("sourcePresentationUnsupported", value.sourcePresentationUnsupported)
        .put("confirmedFramePresent", value.confirmedFrame != null)
        .put("residentCatalogActive", value.residentCatalogActive).put("residentCameraReady", value.residentCameraReady)
        .put("residentStartState", value.residentStartState.name)
        .put("rendererBenchmarkActive", value.rendererBenchmark.active)
        .put("rendererBenchmarkRecoveryPending", value.rendererBenchmarkRecoveryPending)
        .put("rendererCaptureActive", value.rendererCapture.active)
        .put("sourceGeneration", value.chartSourceGeneration).put("surfaceGeneration", value.surfaceGeneration)
        .put("contentGeneration", value.chartContentGeneration).put("presentationGeneration", value.presentationGeneration)
        .put("confirmedFrameRevision", value.confirmedFrame?.revision ?: JSONObject.NULL)
        .put("confirmedFrameSourceGeneration", value.confirmedFrame?.sourceGeneration ?: JSONObject.NULL)
        .put("confirmedFrameContentGeneration", value.confirmedFrame?.contentGeneration ?: JSONObject.NULL)
    fun uncovered(compose: ComposeTestRule) {
        listOf("screen-panel", "object-info-panel", "resident-chart-loading", "chart-warning", "map-dock", "app-switcher")
            .forEach { tag -> check(compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) { "Chart covered: $tag" } }
        check(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()) { "Chart covered by a dialog; no draft is discarded" }
    }
    fun write(name: String, text: String, limit: Int = MAX_REPORT_BYTES): String {
        require(name.matches(Regex("[A-Za-z0-9._-]{1,100}")))
        require(text.length <= limit) { "Proof exceeds character bound" }
        val file = File(directory, name)
        require(!Files.isSymbolicLink(file.toPath()) && file.createNewFile())
        Os.chmod(file.absolutePath, 0b110000000)
        // Chunk the UTF-8 encoding: do not retain a second multi-MiB report byte array.
        var bytes = 0L
        file.outputStream().use { stream ->
            val counting = object : java.io.FilterOutputStream(stream) {
                override fun write(value: Int) { check(++bytes <= limit); out.write(value) }
                override fun write(value: ByteArray, offset: Int, length: Int) {
                    bytes += length; check(bytes <= limit); out.write(value, offset, length)
                }
            }
            val writer = counting.bufferedWriter(Charsets.UTF_8)
            writer.write(text); writer.flush(); stream.fd.sync()
        }
        return file.inputStream().use(::hash)
    }
    fun unavailable(reason: String, scope: String) {
        write("result.json", JSONObject().put("schema", "no5.renderer-release-acceptance/1").put("status", "UNAVAILABLE")
            .put("reason", reason).put("scope", scope).put("run", run).put("ownerStarted", false)
            .put("catalogSwitch", false).put("physicalDisplay", "NOT_MEASURED").toString(2))
    }
    /** Native owner supplies its existing deadlines/recovery. Never cancel an unmatching run. */
    fun execute(engine: ChartEngine, config: RendererBenchmarkConfig, prefix: String,
        admission: (suspend (RendererBenchmarkConfig) -> EngineResult)? = null): JSONObject {
        require(prefix.matches(Regex("[A-Z-]{1,40}")))
        val started = runBlocking { admission?.invoke(config) ?: engine.startRendererBenchmark(config) }
        write("$prefix-start.json", JSONObject().put("success", started.success).put("message", started.message)
            .put("clientRunToken", config.clientRunToken).put("deadlineElapsedRealtimeMs", config.deadlineElapsedRealtimeMs)
            .put("recoveryBudgetMs", config.recoveryBudgetMs).toString(2), 16 * 1024)
        check(started.success) { "UNAVAILABLE_OWNER_REFUSED: ${started.message}" }
        val owner = started.message
        require(owner.isNotBlank() && owner.length <= 128)
        var failure: Throwable? = null
        try {
            val until = config.deadlineElapsedRealtimeMs + config.recoveryBudgetMs + 5_000
            while (true) {
                val state = engine.status.value.rendererBenchmark
                check(state.runId == owner) { "Run owner replaced; no cancellation of the replacement" }
                if (!state.active) break
                check(SystemClock.elapsedRealtime() < until) { "Owned run exceeded its bounded deadline/recovery" }
                SystemClock.sleep(25)
            }
        } catch (caught: Throwable) { failure = caught }
        finally {
            if (engine.status.value.rendererBenchmark.let { it.active && it.runId == owner }) {
                engine.cancelRendererBenchmark(owner)
                val until = SystemClock.elapsedRealtime() + config.recoveryBudgetMs + 5_000
                while (engine.status.value.rendererBenchmark.let { it.active && it.runId == owner } && SystemClock.elapsedRealtime() < until)
                    SystemClock.sleep(25)
            }
        }
        val terminal = engine.status.value
        val read = runBlocking { engine.readRendererReport() }
        check(read.success && read.message.length <= MAX_REPORT_BYTES) { "Bounded matching report unavailable" }
        write("$prefix-native.json", read.message)
        val report = JSONObject(read.message)
        val reportOwner = report.optString("runId").ifEmpty { report.getJSONObject("metadata").getString("runId") }
        check(reportOwner == owner && terminal.rendererBenchmark.runId == owner) { "Stale or replaced report owner" }
        write("$prefix-terminal.json", JSONObject().put("runId", owner).put("phase", terminal.rendererBenchmark.phase)
            .put("active", terminal.rendererBenchmark.active).put("restored", terminal.rendererBenchmark.restored)
            .put("recoveryPending", terminal.rendererBenchmarkRecoveryPending)
            .put("failure", failure?.javaClass?.simpleName ?: JSONObject.NULL).toString(2), 16 * 1024)
        failure?.let { throw it }
        check(!terminal.rendererBenchmark.active && !terminal.rendererBenchmarkRecoveryPending && terminal.rendererBenchmark.restored)
        check(report.getString("outcome") == "completed" && report.getBoolean("restored")) {
            "Run did not complete/restore; retained original report is authoritative"
        }
        return report
    }
    private var currentViewPreparationStarted = false
    /** Exactly one ordinary OFF owner before comparative original capture. Its raw report is never
     * edited or included among comparative arms; geometry/cache reuse follows the ordinary owner. */
    fun prepareCurrentViewReuse(engine: ChartEngine,
        run: ((RendererBenchmarkConfig, String) -> JSONObject)? = null): Pair<ChartEngineStatus, JSONObject> {
        check(!currentViewPreparationStarted) { "Preparatory run cannot be retried or appended" }
        currentViewPreparationStarted = true
        val rule = requireNotNull(compose) { "Preparation requires the existing UI/coverage observer" }
        val prefix = "PREPARATION-OFF"
        val summary = JSONObject().put("schema", "no5.renderer-release-preparation/1")
            .put("scope", "UNPAIRED_PREPARATION_NOT_ADOPTION_TIMING").put("status", "IN_PROGRESS")
            .put("variant", "OFF").put("requestedDurationMs", 15_000).put("preparationCount", 1)
            .put("includedInComparativeArms", false).put("performanceBenefit", "NOT_EVALUATED")
            .put("rawReport", "$prefix-native.json").put("rawReportModified", false)
            .put("catalogSwitch", false).put("geometryReset", false).put("cacheToggle", false)
        var failed: Throwable? = null
        try {
            ready(engine); uncovered(rule)
            val entry = engine.status.value
            check(entry.residentCatalogActive && entry.bufferTransformMode == ChartBufferTransformMode.OFF)
            val pid = Process.myPid(); val processStart = Process.getStartElapsedRealtime()
            val outer = runBlocking { requireNotNull(engine.snapshotRendererOuterState(UUID.randomUUID().toString())) }
            write("$prefix-outer-before.json", outer)
            val configure = Class.forName("com.no5.helm.ui.RendererCurrentViewMeasurementKt").declaredMethods.single {
                it.name.startsWith("currentViewReuseConfig") && it.parameterCount == 6
            }.apply { isAccessible = true }
            val config = configure.invoke(null, outer, engine.status.value, RendererViewportReuseVariant.OFF, false,
                SystemClock.elapsedRealtime(), UUID.randomUUID().toString()) as RendererBenchmarkConfig
            check(config.capacityRecipeId == "CURRENT_VIEW_REUSE" && config.capacityArm?.durationMs == 15_000L)
            check(config.viewportReuseCandidate == RendererViewportReuseVariant.OFF && !config.viewportReuseParity &&
                !config.viewportReuseDetailed && !config.normalRenderAbort && config.currentResidentLive == null)
            val report = run?.invoke(config, prefix) ?: execute(engine, config, prefix)
            check(report.getString("outcome") == "completed" && report.getBoolean("restored"))
            val metadata = report.getJSONObject("metadata")
            check(metadata.getString("viewportReuse") == "OFF" && !metadata.getBoolean("parity") && !metadata.getBoolean("detailed"))
            val events = report.getJSONObject("events")
            check(events.getBoolean("requiredInputPathValid") && !events.has("viewportReuseParity"))
            val recipe = events.getJSONObject("recipe"); val poses = recipe.getJSONArray("results")
            check(recipe.getString("id") == "CURRENT_VIEW_REUSE" && poses.length() == 6)
            for (index in 0 until 6) {
                val pose = poses.getJSONObject(index)
                check(pose.getInt("stepIndex") == index && pose.getBoolean("sameAuthority") && pose.getBoolean("sameRealizedPose") &&
                    pose.getJSONObject("result").getBoolean("accepted"))
            }
            val restoration = report.getJSONObject("viewportReuseRestoration")
            check(restoration.getBoolean("available") && !restoration.getBoolean("active"))
            for (flag in listOf("soundings", "spatial", "gpu")) {
                check(!restoration.getJSONObject("original").getBoolean(flag))
                check(restoration.getJSONObject("original").getBoolean(flag) == restoration.getJSONObject("current").getBoolean(flag))
            }
            ready(engine); uncovered(rule)
            // This existing owner API checks FULL/loader/camera authority AND recovery.read()==null.
            // No fixture-owned journal deletion, cache reset or readiness flag is introduced.
            val restored = runBlocking { requireNotNull(engine.snapshotRendererOuterState(UUID.randomUUID().toString())) }
            write("$prefix-outer-after.json", restored)
            val after = engine.status.value
            assertEquals(pid, Process.myPid()); assertEquals(processStart, Process.getStartElapsedRealtime())
            assertEquals(entry.viewport, after.viewport); assertEquals(entry.presentation, after.presentation)
            assertEquals(entry.objectFilters.supported, after.objectFilters.supported)
            assertEquals(entry.objectFilters.effective, after.objectFilters.effective)
            assertEquals(entry.chartLoaded, after.chartLoaded); assertEquals(entry.bufferTransformMode, after.bufferTransformMode)
            assertEquals(entry.chartSourceGeneration, after.chartSourceGeneration); assertEquals(entry.surfaceGeneration, after.surfaceGeneration)
            assertEquals(entry.residentCatalogActive, after.residentCatalogActive)
            assertEquals(entry.residentCatalogGeneration, after.residentCatalogGeneration)
            fun savedState(text: String): JSONObject {
                val value = JSONObject(text)
                check(value.getString("schema") == "no5-capacity-outer-state/1" && value.getString("scope") == "CURRENT_ENTRY_CATALOG_ONLY")
                val state = value.getJSONObject("state")
                return JSONObject().also { signature ->
                    // Same persisted fields as the existing Timing fixture; snapshot/run UUIDs are not saved settings.
                    for (key in listOf("chartId", "paths", "viewport", "presentation", "settings", "overlays", "bufferTransformMode",
                        "residentSourceGeneration", "residentPolicyKey", "residentActivation")) signature.put(key, state.get(key))
                }
            }
            assertEquals("Entry catalog/camera/settings restored exactly after preparation", savedState(outer).toString(), savedState(restored).toString())
            summary.put("status", "COMPLETED_UNPAIRED_PREPARATION").put("completePoses", 6)
                .put("loadedCellCountBefore", entry.loadedChartCells.size).put("loadedCellCountAfter", after.loadedChartCells.size)
                .put("loadedCellSetChanged", entry.loadedChartCells != after.loadedChartCells)
                .put("nativeMetadataTimingComparableRetained", metadata.getBoolean("timingComparable"))
                .put("rawReportSha256", File(directory, "$prefix-native.json").inputStream().use(::hash))
                .put("sameProcess", true).put("originalStateRestored", true)
                .put("recoveryJournalCheckedAbsentByExistingOuterSnapshot", true)
            return after to summary
        } catch (failure: Throwable) {
            failed = failure; summary.put("status", "FAILED_PREPARATION").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally {
            try { write("$prefix-classification.json", summary.toString(2), 32 * 1024) }
            catch (proofFailure: Throwable) { if (failed != null) failed.addSuppressed(proofFailure) else throw proofFailure }
        }
    }
    fun original(before: ChartEngineStatus, after: ChartEngineStatus, exactSource: Boolean) {
        assertEquals(before.viewport, after.viewport)
        assertEquals(before.presentation, after.presentation)
        assertEquals(before.objectFilters.supported, after.objectFilters.supported)
        assertEquals(before.objectFilters.effective, after.objectFilters.effective)
        assertEquals(before.bufferTransformMode, after.bufferTransformMode)
        assertEquals(before.chartLoaded, after.chartLoaded)
        assertEquals(before.loadedChartCells, after.loadedChartCells)
        assertEquals(before.residentCatalogActive, after.residentCatalogActive)
        assertEquals(before.residentCatalogGeneration, after.residentCatalogGeneration)
        if (exactSource) {
            assertEquals(before.chartSourceGeneration, after.chartSourceGeneration)
            assertEquals(before.surfaceGeneration, after.surfaceGeneration)
        }
        assertFalse(after.rendererBenchmarkRecoveryPending)
        assertFalse(after.rendererBenchmark.active)
        assertTrue(after.rendererBenchmark.restored)
    }
    companion object {
        const val MAX_REPORT_BYTES = 16 * 1024 * 1024
        /** Passive resolver also usable by image tests; never constructs a proof/identity owner. */
        fun resolveEngine(activity: MainActivity): ChartEngine {
            val getters = MainActivity::class.java.declaredMethods.filter {
                it.name.startsWith("getEngine") && it.parameterCount == 0 &&
                    ChartEngine::class.java.isAssignableFrom(it.returnType)
            }
            if (getters.isNotEmpty()) return getters.single().apply { isAccessible = true }.invoke(activity) as ChartEngine
            return MainActivity::class.java.getDeclaredField("engine").let { // Older field-backed target only.
                it.isAccessible = true; it.get(activity) as ChartEngine
            }
        }
        private fun hash(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(16_384)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
