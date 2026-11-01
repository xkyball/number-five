package com.no5.helm

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.chart.ChartEngine
import com.no5.helm.model.MapPositionController
import com.no5.helm.model.MapPositionState
import com.no5.helm.model.PositionMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opt-in, matching-signer production-Activity proof. NOT RUN until separately built/signed and scheduled.
 * Run CHECKPOINT with mode CENTERED or FOLLOW, externally force-stop com.no5.map, then run VERIFY
 * with the same run UUID/APK hash. Instrumentation must start a fresh target process in both phases.
 * A second pair is required for the other mode. CHECKPOINT uses the visible production button;
 * no fake GPS, direct camera submission, client replacement, service, or network setting is used.
 * Journals are private and include the original position preference for separately coordinated,
 * compare-and-set restoration. The test never overwrites an intervening preference change.
 * Actual engine queue/result replay is not completely observable here; exact command-count proof
 * belongs to MapPositionRestartContractTest. An Activity recreate is explicitly NOT process loss.
 */
class MapPositionRestartAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16_384)
        file.inputStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun processIdentity() = "${Process.myPid()}:${Process.getStartElapsedRealtime()}"
    private fun positions(activity: MainActivity): MapPositionController {
        val runtime = MainActivity::class.java.getDeclaredMethod("getRuntime").apply { isAccessible = true }.invoke(activity)
        return runtime.javaClass.getDeclaredMethod("getPositions").apply { isAccessible = true }.invoke(runtime) as MapPositionController
    }
    private fun preference() = context.getSharedPreferences("map-position-v1", 0).getString("positionIntentV1", null)
    private fun preferenceHash() = hash((preference() ?: "<absent>").toByteArray(Charsets.UTF_8))
    private fun field(owner: MapPositionController, name: String): Any? = MapPositionController::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(owner)

    @Test fun checkpointOrVerifyActualPositionOwnerRestart() {
        assumeTrue("Explicit position restart opt-in required", arguments.getString("no5.position.restart") == "true")
        assertEquals("Only the installed production MAP target is permitted", "com.no5.map", context.packageName)
        assertEquals("Instrumentation and installed MAP must have the same signer", PackageManager.SIGNATURE_MATCH,
            context.packageManager.checkSignatures(instrumentation.context.packageName, context.packageName))
        val expectedApk = requireNotNull(arguments.getString("no5.position.apkSha256"))
        require(expectedApk.matches(Regex("[0-9a-f]{64}")))
        val actualApk = fileHash(File(context.applicationInfo.sourceDir))
        assertEquals("Bind this proof to the actual installed base APK", expectedApk, actualApk)
        val run = requireNotNull(arguments.getString("no5.position.run"))
        require(UUID.fromString(run).toString() == run)
        val mode = PositionMode.valueOf(requireNotNull(arguments.getString("no5.position.mode")))
        require(mode == PositionMode.CENTERED || mode == PositionMode.FOLLOW)
        val phase = arguments.getString("no5.position.phase")
        require(phase == "CHECKPOINT" || phase == "VERIFY")
        val root = File(context.filesDir, "position-restart-proof")
        require(!Files.isSymbolicLink(root.toPath()))
        check(root.isDirectory || root.mkdir())
        val checkpoint = File(root, "$run-checkpoint.json")
        val verified = File(root, "$run-verify.json")
        val app = context.applicationContext as Application
        val first = AtomicReference<MapPositionState?>()
        val latest = AtomicReference<MapPositionState?>()
        val owner = AtomicReference<MapPositionController?>()
        val engine = AtomicReference<ChartEngine?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val invalidFreshObservation = AtomicBoolean(false)
        val callbacks = AtomicInteger()
        val readiness = JSONObject().put("schema", "no5.map-position-control-readiness/1")
            .put("run", run).put("phase", phase).put("mode", mode.name).put("apkSha256", actualApk)
            .put("budgetMs", 60_000).put("jointGateUsed", false)
            .put("stage", "ACTIVITY_LAUNCH").put("nodeCount", JSONObject.NULL)
            .put("nodeDisplayed", JSONObject.NULL).put("nodeEnabled", JSONObject.NULL)
            .put("nodeHasClick", JSONObject.NULL).put("nativeShellCount", JSONObject.NULL)
            .put("preparedState", "NOT_EXPOSED_BY_PUBLIC_ACTIVITY_API")
            .put("preparedStateInferredFromShell", false)
            .put("modelAndSemanticsReadAtomically", false)
            .put("engineAndSemanticsReadAtomically", false)
            .put("composeLocalControllerBusy", "NOT_EXPOSED_BY_PUBLIC_ACTIVITY_API")
        val listener = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity !is MainActivity) return
                val model = positions(activity)
                if (!owner.compareAndSet(null, model)) return
                engine.set(RendererReleaseAcceptanceSupport.resolveEngine(activity))
                first.set(model.state.value)
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    model.state.collect { value ->
                        latest.set(value); callbacks.incrementAndGet()
                        if (value.ownship != null) {
                            val fence = field(model, "transportFence") as Long
                            val observed = value.observedAtEpochMs
                            if (observed == null || observed < fence || observed > System.currentTimeMillis())
                                invalidFreshObservation.set(true)
                        }
                    }
                }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        if (phase == "VERIFY") {
            require(Files.isRegularFile(checkpoint.toPath(), LinkOption.NOFOLLOW_LINKS))
            require(checkpoint.length() <= 16_384)
            val journal = JSONObject(checkpoint.readText())
            assertEquals(actualApk, journal.getString("apkSha256"))
            assertEquals(mode.name, journal.getString("mode"))
            assertNotEquals("Activity recreation is insufficient; a different process is required",
                journal.getString("processIdentity"), processIdentity())
            assertEquals("An intervening position preference must not be overwritten or hidden",
                journal.getString("checkpointPreferenceSha256"), preferenceHash())
        }
        app.registerActivityLifecycleCallbacks(listener)
        try {
            val originalPreference = preference()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(15_000) { first.get() != null }
                val initial = first.get()!!
                assertNull("New production owner begins without a persisted fix", initial.ownship)
                assertFalse("Saved FOLLOW is intent, not permission to replay an old fix", initial.followActive)
                if (phase == "VERIFY") {
                    assertEquals(if (mode == PositionMode.CENTERED) PositionMode.OFF else PositionMode.FOLLOW, initial.mode)
                    compose.waitUntil(60_000) {
                        val current = latest.get()
                        current?.ownship != null && (mode == PositionMode.CENTERED ||
                            field(owner.get()!!, "followObserved") == current.observedAtEpochMs)
                    }
                    scenario.onActivity {
                        assertFalse("Every observed usable fix must meet the unchanged new-owner fence", invalidFreshObservation.get())
                        assertEquals(if (mode == PositionMode.CENTERED) PositionMode.OFF else PositionMode.FOLLOW,
                            owner.get()!!.state.value.mode)
                        if (mode == PositionMode.CENTERED) assertNull(field(owner.get()!!, "followObserved"))
                    }
                    writeNew(verified, JSONObject().put("schema", "no5.map-position-restart-observation/1")
                        .put("apkSha256", actualApk).put("mode", mode.name).put("processIdentity", processIdentity())
                        .put("differentProcessObserved", true).put("initialMode", initial.mode.name)
                        .put("initialFixAbsent", true).put("newConfirmedFixObserved", true)
                        .put("observedStateCallbacks", callbacks.get()).put("engineQueueReplay", "NOT_OBSERVABLE"))
                } else {
                    require(!checkpoint.exists() && !verified.exists()) { "A run UUID cannot be reused" }
                    // One original 60-s budget covers real GPS AND the real production control.
                    // The retained model can receive GPS before MainActivity.prepared.await()
                    // has replaced its preparation screen with the native chart composition.
                    val positionDeadline = SystemClock.elapsedRealtime() + 60_000
                    readiness.put("jointGateUsed", true).put("startedElapsedRealtimeMs", positionDeadline - 60_000)
                        .put("deadlineElapsedRealtimeMs", positionDeadline)
                    awaitFreshPositionControl(latest, owner, engine, positionDeadline, readiness)
                    if (latest.get()!!.mode == PositionMode.CENTERED) repeat(2) { clickPosition(latest, owner, engine, positionDeadline, readiness) }
                    else if (latest.get()!!.mode == PositionMode.FOLLOW) clickPosition(latest, owner, engine, positionDeadline, readiness)
                    compose.runOnIdle { assertEquals(PositionMode.OFF, owner.get()!!.state.value.mode) }
                    clickPosition(latest, owner, engine, positionDeadline, readiness)
                    if (mode == PositionMode.FOLLOW) clickPosition(latest, owner, engine, positionDeadline, readiness)
                    compose.runOnIdle { assertEquals(mode, owner.get()!!.state.value.mode) }
                    val beforeRecreate = owner.get()!!
                    scenario.recreate()
                    scenario.onActivity { activity ->
                        assertSame("Activity recreation retains the real session owner", beforeRecreate, positions(activity))
                        assertEquals("An Activity recreate is not process loss", mode, positions(activity).state.value.mode)
                    }
                    writeNew(checkpoint, JSONObject().put("schema", "no5.map-position-restart-checkpoint/1")
                        .put("apkSha256", actualApk).put("mode", mode.name).put("processIdentity", processIdentity())
                        .put("originalPositionPreference", originalPreference ?: JSONObject.NULL)
                        .put("checkpointPreferenceSha256", preferenceHash()).put("activityRecreateSameOwner", true)
                        .put("processRestart", "NOT_RUN_UNTIL_VERIFY"))
                }
            }
        } catch (failure: Throwable) {
            // Keep only bounded model/semantics facts, never coordinates, labels or a guessed
            // preparation cause. A failed attempt is not a checkpoint or process-loss proof.
            try {
                recordPositionObservation(latest, owner, readiness)
                recordEngineObservation(engine, readiness)
                readiness.put("failureType", failure.javaClass.simpleName.take(80))
                    .put("failedAtElapsedRealtimeMs", SystemClock.elapsedRealtime())
                    .put("checkpointWritten", checkpoint.isFile).put("verifyWritten", verified.isFile)
                    .put("composeAutoAdvance", compose.mainClock.autoAdvance)
                    .put("composeClockAtFailureMs", compose.mainClock.currentTime)
                    .put("observedStateCallbacks", callbacks.get())
                check(readiness.toString(2).toByteArray(Charsets.UTF_8).size <= 16_384)
                writeNew(File(root, "$run-$phase-readiness-failure.json"), readiness)
            } catch (proofFailure: Throwable) { failure.addSuppressed(proofFailure) }
            throw failure
        } finally {
            scope.cancel()
            app.unregisterActivityLifecycleCallbacks(listener)
        }
    }
    private fun recordPositionObservation(latest: AtomicReference<MapPositionState?>,
        owner: AtomicReference<MapPositionController?>, evidence: JSONObject): Boolean {
        val current = latest.get()
        val model = owner.get()
        val observed = current?.observedAtEpochMs
        val fence = model?.let { field(it, "transportFence") as Long }
        val notBeforeFence = if (observed != null && fence != null) observed >= fence else null
        val notFuture = observed?.let { it <= System.currentTimeMillis() }
        val fresh = current?.ownship != null && !current.awaitingFreshFix && !current.touchActive &&
            notBeforeFence == true && notFuture == true
        evidence.put("modelObservedElapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("modelPresent", current != null).put("ownerPresent", model != null)
            .put("ownshipPresent", current?.ownship != null)
            .put("awaitingFreshFix", current?.awaitingFreshFix ?: JSONObject.NULL)
            .put("touchActive", current?.touchActive ?: JSONObject.NULL)
            .put("observedAtEpochMs", observed ?: JSONObject.NULL)
            .put("transportFenceEpochMs", fence ?: JSONObject.NULL)
            .put("observedNotBeforeTransportFence", notBeforeFence ?: JSONObject.NULL)
            .put("observedNotFuture", notFuture ?: JSONObject.NULL).put("freshPosition", fresh)
        return fresh
    }
    private fun recordEngineObservation(engine: AtomicReference<ChartEngine?>, evidence: JSONObject): Boolean {
        // Passive read of the actual Activity's existing engine. The button's enabled state
        // alone does not promise a loaded/attached/confirmed camera; FOLLOW also deliberately
        // leaves its OFF action enabled while other work is busy.
        val state = engine.get()?.status?.value
        val ready = state != null && state.initialized && state.chartLoaded && state.surfaceAttached &&
            !state.presentationPending && !state.sourcePresentationUnsupported && state.confirmedFrame != null &&
            (!state.residentCatalogActive || state.residentCameraReady) && !state.rendererBenchmark.active &&
            !state.rendererBenchmarkRecoveryPending && !state.rendererCapture.active
        evidence.put("engineObservedElapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("enginePresent", state != null).put("engineReadyAndIdle", ready)
            .put("initialized", state?.initialized ?: JSONObject.NULL)
            .put("chartLoaded", state?.chartLoaded ?: JSONObject.NULL)
            .put("surfaceAttached", state?.surfaceAttached ?: JSONObject.NULL)
            .put("presentationPending", state?.presentationPending ?: JSONObject.NULL)
            .put("sourcePresentationUnsupported", state?.sourcePresentationUnsupported ?: JSONObject.NULL)
            .put("confirmedFramePresent", state?.let { it.confirmedFrame != null } ?: JSONObject.NULL)
            .put("residentCatalogActive", state?.residentCatalogActive ?: JSONObject.NULL)
            .put("residentCameraReady", state?.residentCameraReady ?: JSONObject.NULL)
            .put("rendererBenchmarkActive", state?.rendererBenchmark?.active ?: JSONObject.NULL)
            .put("rendererBenchmarkRecoveryPending", state?.rendererBenchmarkRecoveryPending ?: JSONObject.NULL)
            .put("rendererCaptureActive", state?.rendererCapture?.active ?: JSONObject.NULL)
        return ready
    }
    private fun awaitFreshPositionControl(latest: AtomicReference<MapPositionState?>,
        owner: AtomicReference<MapPositionController?>, engine: AtomicReference<ChartEngine?>,
        deadline: Long, evidence: JSONObject) {
        evidence.put("stage", "BEFORE_IDLE").put("composeAutoAdvance", compose.mainClock.autoAdvance)
            .put("composeClockBeforeMs", compose.mainClock.currentTime)
        check(compose.mainClock.autoAdvance) { "UNAVAILABLE_TEST_CLOCK: existing Compose clock is not advancing" }
        fun withinBudget() = check(SystemClock.elapsedRealtime() < deadline) {
            "UNAVAILABLE_POSITION_CONTROL: original 60-s fresh-GPS/control-ready budget expired"
        }
        withinBudget()
        // Service the existing WindowRecomposer/Android scheduler; never create a UI root,
        // force a render, alter touch/navigation state, or invoke the controller directly.
        evidence.put("stage", "WAIT_FOR_IDLE")
        compose.waitForIdle()
        withinBudget()
        evidence.put("stage", "JOINT_READY_WAIT")
        compose.waitUntil(timeoutMillis = maxOf(1, deadline - SystemClock.elapsedRealtime())) {
            withinBudget()
            val nodes = compose.onAllNodesWithTag("position-cycle").fetchSemanticsNodes(atLeastOneRootRequired = false)
            val node = nodes.singleOrNull()
            val enabled = node?.let { !it.config.contains(SemanticsProperties.Disabled) }
            val hasClick = node?.config?.contains(SemanticsActions.OnClick)
            val displayed = if (node != null) compose.onNodeWithTag("position-cycle").isDisplayed() else null
            val shells = compose.onAllNodesWithTag("no5-shell").fetchSemanticsNodes(atLeastOneRootRequired = false)
            evidence.put("semanticsObservedElapsedRealtimeMs", SystemClock.elapsedRealtime())
                .put("nodeCount", nodes.size).put("nodeDisplayed", displayed ?: JSONObject.NULL)
                .put("nodeEnabled", enabled ?: JSONObject.NULL).put("nodeHasClick", hasClick ?: JSONObject.NULL)
                .put("nativeShellCount", shells.size).put("composeClockAfterMs", compose.mainClock.currentTime)
            val fresh = recordPositionObservation(latest, owner, evidence)
            val engineReady = recordEngineObservation(engine, evidence)
            withinBudget() // Synchronous UI work must not silently extend the original deadline.
            fresh && engineReady && enabled == true && hasClick == true && displayed == true
        }
        withinBudget()
    }
    private fun clickPosition(latest: AtomicReference<MapPositionState?>,
        owner: AtomicReference<MapPositionController?>, engine: AtomicReference<ChartEngine?>,
        deadline: Long, evidence: JSONObject) {
        awaitFreshPositionControl(latest, owner, engine, deadline, evidence)
        evidence.put("stage", "PRODUCTION_POSITION_CLICK")
        compose.onNodeWithTag("position-cycle").performClick()
        evidence.put("stage", "AFTER_CLICK_IDLE")
        compose.waitForIdle()
    }
    private fun writeNew(file: File, value: JSONObject) {
        require(!Files.isSymbolicLink(file.toPath()))
        check(file.createNewFile()) { "Proof file already exists" }
        android.system.Os.chmod(file.absolutePath, 0b110000000)
        file.outputStream().use { output -> output.write(value.toString(2).toByteArray(Charsets.UTF_8)); output.fd.sync() }
    }
}
