package com.no5.helm

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.no5.helm.chart.ChartBufferSnapshot
import com.no5.helm.chart.ChartContentStage
import com.no5.helm.chart.ChartEngine
import com.no5.helm.chart.ChartEngineStatus
import com.no5.helm.chart.ChartResidencyStatus
import com.no5.helm.chart.ChartViewport
import com.no5.helm.ui.ChartLibraryActions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Instrumented process-start evidence with cached package/resources, NOT normal production
 * startup performance. No Activity rule launches before the observer. Producer completion,
 * first physical display, native-FULL-to-Window pixel join and scanout remain unmeasured.
 */
class RendererStartupProofTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun instrumentedCachedResourcesProcessStartToFirstStableWindowEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit separate startup and Window evidence opt-ins required",
            arguments.getString("no5.renderer.startupProof") == "true" && arguments.getString("no5.windowProof") == "true")
        val testEnteredNs = SystemClock.elapsedRealtimeNanos()
        val testDeadlineMs = SystemClock.elapsedRealtime() + TEST_BUDGET_MS
        val pid = Process.myPid()
        val processStartMs = Process.getStartElapsedRealtime()
        val proof = RendererReleaseAcceptanceSupport() // Real target/test/source/signer pins, before launch.
        val result = JSONObject().put("schema", "no5.renderer-startup-window-proof/1")
            .put("run", proof.run).put("status", "FAIL")
            .put("scope", "INSTRUMENTED_CACHED_RESOURCES_PROCESS_START_SEPARATE_WINDOW_EVIDENCE")
            .put("normalProductionStartupComparable", false).put("normalRendererTimingComparable", false)
            .put("processId", pid).put("processStartElapsedRealtimeMs", processStartMs)
            .put("processStartClock", "Android Process.getStartElapsedRealtime / CLOCK_BOOTTIME, millisecond API mark")
            .put("testEnteredElapsedRealtimeNs", testEnteredNs)
            .put("javaRuntime", JSONObject().put("release", Build.VERSION.RELEASE).put("sdkInt", Build.VERSION.SDK_INT))
            .put("hostColdProcessAbsenceBeforeInstrumentation", "NOT_VERIFIED_IN_TEST; requires separate host evidence")
            .put("producerFirstFullCompletedAt", "NOT_MEASURED; no producer event hook")
            .put("firstPhysicalDisplay", "NOT_MEASURED").put("physicalScanout", "NOT_MEASURED")
            .put("nativeFullPixelJoin", "NOT_MEASURED").put("gpuTimeOrFps", "NOT_MEASURED")
            .put("observationBudgetMs", FIRST_OBSERVATION_BUDGET_MS).put("testBudgetMs", TEST_BUDGET_MS)
        val application = instrumentation.targetContext.applicationContext as Application
        var observer: StartupObserver? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var window: RendererWindowProof? = null
        var failure: Throwable? = null
        var completeReadiness: RendererStartupFirstObservation? = null
        var readinessBefore: StartupReadinessSample? = null
        var readinessAfter: StartupReadinessSample? = null
        var readinessUi: RendererReleaseReadinessUi? = null
        try {
            check(processStartMs > 0 && processStartMs * 1_000_000L <= testEnteredNs) { "Process start clock unavailable" }
            check(compose.mainClock.autoAdvance) { "UNAVAILABLE_TEST_CLOCK: existing Compose clock must advance" }
            onMain {
                val existing = Stage.values().filter { it != Stage.DESTROYED }
                    .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.distinct()
                val host = MainActivity::class.java.getDeclaredField("benchmarkHost").apply { isAccessible = true }
                    .get(null) as WeakReference<*>
                result.put("preLaunchLiveTargetActivityCount", existing.size).put("preLaunchBenchmarkHostPresent", host.get() != null)
                check(existing.isEmpty() && host.get() == null) { "UNAVAILABLE_EXISTING_ACTIVITY_OR_BENCHMARK_HOST; no warm-session startup claim" }
                val absentAnchorNs = SystemClock.elapsedRealtimeNanos()
                observer = StartupObserver(absentAnchorNs, SystemClock.elapsedRealtime() + FIRST_OBSERVATION_BUDGET_MS)
                application.registerActivityLifecycleCallbacks(observer!!)
                observer!!.start()
                result.put("observedAbsentPreLaunchAnchorElapsedRealtimeNs", absentAnchorNs)
                    .put("lifecycleObserverRegisteredElapsedRealtimeNs", SystemClock.elapsedRealtimeNanos())
            }
            val launchRequestedNs = SystemClock.elapsedRealtimeNanos()
            result.put("activityLaunchRequestedElapsedRealtimeNs", launchRequestedNs)
            val launched = ActivityScenario.launch(MainActivity::class.java)
            scenario = launched
            result.put("activityLaunchReturnedElapsedRealtimeNs", SystemClock.elapsedRealtimeNanos())
            val activeObserver = requireNotNull(observer)
            val remainingMs = minOf(activeObserver.deadlineMs, testDeadlineMs - WINDOW_AND_CLEANUP_RESERVE_MS) - SystemClock.elapsedRealtime()
            check(remainingMs > 0) { "UNAVAILABLE_STARTUP_BUDGET_EXHAUSTED_DURING_LAUNCH" }
            // This services only the existing test clock. The read observer uses real elapsedRealtime,
            // not a synthetic engine flag or virtual timestamp. Instrumentation overhead stays explicit.
            compose.waitUntil(remainingMs) { activeObserver.done.get() }
            activeObserver.failure.get()?.let { throw it }
            val activity = requireNotNull(activeObserver.activity.get())
            launched.onActivity { check(it === activity) { "Startup Activity replaced" } }
            // FULL can appear at 2/3 required cells. Wait independently for current complete
            // required-geometry residency plus the existing joint engine/UI gate. No JNI/readResidency.
            val gate = RendererReleaseReadinessGate(activeObserver.absentAnchorNs / 1_000_000)
            val complete = RendererStartupFirstObservation(activeObserver.absentAnchorNs)
            completeReadiness = complete
            val completeDeadlineMs = minOf(activeObserver.deadlineMs, testDeadlineMs - WINDOW_AND_CLEANUP_RESERVE_MS)
            val completeRemainingMs = completeDeadlineMs - SystemClock.elapsedRealtime()
            check(completeRemainingMs > 0) { "UNAVAILABLE_REQUIRED_GEOMETRY_READINESS_BUDGET" }
            compose.waitUntil(completeRemainingMs) {
                check(SystemClock.elapsedRealtime() < completeDeadlineMs) { "UNAVAILABLE_REQUIRED_GEOMETRY_READINESS_BUDGET" }
                val readStarted = SystemClock.elapsedRealtimeNanos()
                val beforeState = onMain { activeObserver.readinessSample() }
                val ui = rendererReleaseReadinessUi(compose)
                val afterState = onMain { activeObserver.readinessSample() }
                readinessBefore = beforeState; readinessAfter = afterState; readinessUi = ui
                val ready = gate.ready(beforeState.engine, ui, afterState.engine, SystemClock.elapsedRealtime()) &&
                    beforeState.ready && afterState.ready && sameRequiredGeometry(beforeState.residency, afterState.residency)
                complete.observe(readStarted, SystemClock.elapsedRealtimeNanos(), ready)
                ready
            }
            proof.uncovered(compose) // Never dismiss a dialog, loading panel or somebody's draft.
            val observation = onMain { activeObserver.snapshot() }
            result.put("startupObservation", observation)
            check(SystemClock.elapsedRealtime() < testDeadlineMs - 10_000) { "UNAVAILABLE_WINDOW_CAPTURE_BUDGET_EXHAUSTED" }
            val full = requireNotNull(activeObserver.firstFullIdentity.get())
            val external = requireNotNull(instrumentation.targetContext.getExternalFilesDir("renderer-window-proof"))
            val capture = RendererWindowProof(activity, instrumentation, external, proof.run)
            window = capture
            val before = clockPair()
            val image = capture.capture("startup-first-stable")
            val after = clockPair()
            // Existing Window proof uses CLOCK_MONOTONIC. Never subtract its nanoTime values
            // directly from the BOOTTIME process mark. This conservative outer bracket includes PNG/hash work.
            result.put("windowCaptureClockPairBefore", before).put("windowCaptureClockPairAfter", after)
                .put("confirmedWindowObservationOuterBracketElapsedRealtimeNs", JSONObject()
                    .put("afterOrAtNs", before.getLong("elapsedBeforeNs"))
                    .put("byNs", after.getLong("elapsedAfterNs"))
                    .put("includesCopyCompositeEncodingAndTestWork", true)
                    .put("firstDisplayTime", "NOT_MEASURED"))
                .put("windowObservation", image)
                .put("windowIdentityMatchesFirstObservedPresenterFull", sameFullIdentity(full, image.optJSONObject("identity")))
                .put("earliestFullBufferToWindowAssociation", if (sameFullIdentity(full, image.optJSONObject("identity")))
                    "IDENTITY_FIELDS_MATCH; NATIVE_PIXEL_JOIN_NOT_MEASURED" else "NOT_JOINED; DIFFERENT_OBSERVED_FULL_IDENTITIES")
                .put("windowIdentityAssociationIsNativePixelJoin", false)
                .put("windowProofUuid", proof.run)
            check(SystemClock.elapsedRealtime() < testDeadlineMs) { "UNAVAILABLE_TEST_BUDGET_EXCEEDED_AFTER_WINDOW" }
            assertEquals("One genuine stable Window capture required", "WINDOW_CAPTURED_WHILE_FULL_IDENTITY_STABLE", image.optString("status"))
            assertEquals("Visible chart crop must match the separately captured composite", "WINDOW_CHART_PIXELS_MATCH_DISPLAY_COMPOSITE",
                image.getJSONObject("displayComposite").optString("status"))
            val completeSample = requireNotNull(readinessAfter)
            val captured = onMain { activeObserver.capturedWindowSample(image.getJSONObject("identity")) }
            val afterWindow = captured.after
            result.put("readinessAfterWindow", afterWindow.json())
                .put("capturedWindowRegistryBinding", JSONObject()
                    .put("lookup", "PASSIVE_EXACT_CAPTURED_STAMP; NO_OBSERVE_ACK_OR_LATEST_SUBSTITUTION")
                    .put("registeredBufferFound", captured.registered != null)
                    .put("capturedBufferFieldsMatch", captured.fieldsMatch)
                    .put("capturedRegistryFrameRevision", captured.registered?.frame?.revision ?: JSONObject.NULL)
                    .put("cameraAuthorityStableAcrossRegistryRead", captured.before.authority != null && captured.before.authority == afterWindow.authority)
                    .put("capturedCameraAuthorityMatchesCompleteReadiness", captured.authority != null && captured.authority == completeSample.authority)
                    .put("nativePixelJoin", "NOT_MEASURED"))
            assertTrue("Window must remain at the separately observed complete required-geometry authority",
                captured.before.ready && afterWindow.ready && gate.engineReady(afterWindow.engine, SystemClock.elapsedRealtime()) &&
                    completeSample.authority != null && completeSample.authority == captured.before.authority &&
                    completeSample.authority == afterWindow.authority &&
                    sameRequiredGeometry(completeSample.residency, captured.before.residency) &&
                    sameRequiredGeometry(completeSample.residency, afterWindow.residency))
            assertTrue("The exact captured Window stamp must join its retained FULL registry buffer and the unchanged complete camera authority",
                captured.fieldsMatch && captured.authority != null && captured.authority == completeSample.authority)
            assertEquals(pid, Process.myPid())
            assertEquals(processStartMs, Process.getStartElapsedRealtime())
            result.put("nativeMapsPassiveObservation", nativeMaps())
                .put("sameProcessThroughWindowObservation", true).put("status", "PASS")
                .put("claim", "Process API start mark, separate Activity lifecycle, first observed Presenter FULL-buffer read bracket, separately complete current required-geometry plus engine/UI readiness, then the first stable Window/composite capture attempted by this test. Poll/capture brackets are upper bounds, not the first producer/display event or nautical coverage.")
        } catch (caught: Throwable) {
            failure = caught
            result.put("status", if (caught.message?.startsWith("UNAVAILABLE_") == true) "UNAVAILABLE" else "FAIL")
                .put("failureType", caught.javaClass.simpleName)
                .put("failureMessage", caught.message?.take(1000) ?: JSONObject.NULL)
            throw caught
        } finally {
            var cleanupFailure: Throwable? = null
            fun retain(caught: Throwable) {
                result.put("status", "FAIL").put("cleanupFailureType", caught.javaClass.simpleName)
                if (failure != null) failure!!.addSuppressed(caught)
                else if (cleanupFailure == null) cleanupFailure = caught else cleanupFailure!!.addSuppressed(caught)
            }
            try {
                observer?.let { active -> onMain {
                    active.stop(); application.unregisterActivityLifecycleCallbacks(active)
                    result.put("startupObservation", active.snapshot()).put("lifecycleObserverRemoved", true)
                } }
            } catch (caught: Throwable) { retain(caught) }
            try {
                window?.finish(JSONObject().put("processStart", "INSTRUMENTED_API_MARK_SEPARATE_FROM_ACTIVITY_START")
                    .put("recipe", "INSTRUMENTED_STARTUP_CURRENT_VIEW").put("normalTimingComparable", false))
            } catch (caught: Throwable) { retain(caught) }
            try { scenario?.close() } catch (caught: Throwable) { retain(caught) }
            result.put("completeRequiredGeometryReadiness", JSONObject()
                .put("bracket", bracket(completeReadiness?.first)).put("conditionReads", completeReadiness?.reads ?: 0)
                .put("beforeUi", readinessBefore?.json() ?: JSONObject.NULL).put("afterUi", readinessAfter?.json() ?: JSONObject.NULL)
                .put("rendererPresent", readinessUi?.rendererPresent ?: JSONObject.NULL)
                .put("blockingTags", readinessUi?.let { JSONArray(it.blockingTags.sorted()) } ?: JSONObject.NULL)
                .put("dialogPresent", readinessUi?.dialogPresent ?: JSONObject.NULL)
                .put("scope", "Required geometry residency only; no nautical/reference/pixel completeness inference"))
            result.put("testFinishedElapsedRealtimeNs", SystemClock.elapsedRealtimeNanos())
                .put("elapsedTestMs", (SystemClock.elapsedRealtimeNanos() - testEnteredNs) / 1_000_000.0)
                .put("explicitSettingsCameraCatalogOrSourceCommandsIssuedByTest", false)
            proof.write("startup-window-result.json", result.toString(2), 128 * 1024)
            cleanupFailure?.let { throw it }
        }
    }

    private class StartupObserver(val absentAnchorNs: Long, val deadlineMs: Long) : Application.ActivityLifecycleCallbacks, Runnable {
        private val handler = Handler(Looper.getMainLooper())
        private val full = RendererStartupFirstObservation(absentAnchorNs)
        private val eligible = RendererStartupFirstObservation(absentAnchorNs)
        private var resolvedEngine: ChartEngine? = null
        private var rendererSession: String? = null
        val activity = AtomicReference<MainActivity?>()
        val firstFullIdentity = AtomicReference<JSONObject?>()
        private val modelAfterFirstFullRead = AtomicReference<JSONObject?>()
        private val firstEligibleIdentity = AtomicReference<JSONObject?>()
        val failure = AtomicReference<Throwable?>()
        val done = AtomicBoolean(false)
        private var stopped = false
        private var createdNs: Long? = null
        private var startedNs: Long? = null
        private var resumedNs: Long? = null
        private var lastUnavailableReason = "NO_ACTIVITY"
        private var createdCount = 0

        fun start() { check(Looper.myLooper() == Looper.getMainLooper()); handler.post(this) }
        fun stop() { stopped = true; handler.removeCallbacks(this) }
        override fun run() {
            if (stopped || done.get()) return
            try {
                check(SystemClock.elapsedRealtime() < deadlineMs) { "UNAVAILABLE_FIRST_PRESENTER_FULL_WITHIN_BUDGET" }
                val readStarted = SystemClock.elapsedRealtimeNanos()
                val identity = activity.get()?.let(::presenterIdentity)
                val buffer = identity?.let { property(it, "buffer") as? ChartBufferSnapshot }
                val reason = identity?.let { property(it, "unavailableReason").toString() } ?: "NO_PRESENTER"
                // A completed FULL from a stale source/generation/slot is not startup readiness.
                val isFull = buffer != null && identity != null && buffer.producerCompleted && buffer.stage == ChartContentStage.FULL &&
                    buffer.stamp == property(identity, "stamp") && buffer.generation == property(identity, "presenterGeneration") &&
                    buffer.frame.sourceGeneration == property(identity, "sourceAuthority") &&
                    (buffer.presentationSlot < 0 || buffer.presentationSlot == property(identity, "frontSlot"))
                val readCompleted = SystemClock.elapsedRealtimeNanos()
                val hadFull = full.first != null; val hadEligible = eligible.first != null
                full.observe(readStarted, readCompleted, isFull)
                eligible.observe(readStarted, readCompleted, isFull && reason == "NONE")
                if (!hadFull && full.first != null) {
                    firstFullIdentity.set(bufferIdentity(requireNotNull(buffer), requireNotNull(identity)))
                    val modelReadStarted = SystemClock.elapsedRealtimeNanos()
                    modelAfterFirstFullRead.set(readinessSample().json()
                        .put("modelReadStartedElapsedRealtimeNs", modelReadStarted)
                        .put("modelReadCompletedElapsedRealtimeNs", SystemClock.elapsedRealtimeNanos())
                        .put("atomicWithFirstPresenterFullRead", false))
                }
                if (!hadEligible && eligible.first != null) firstEligibleIdentity.set(bufferIdentity(requireNotNull(buffer), requireNotNull(identity)))
                lastUnavailableReason = reason
                if (eligible.first != null) { done.set(true); return }
                handler.postDelayed(this, 8)
            } catch (caught: Throwable) { failure.compareAndSet(null, caught); done.set(true) }
        }

        override fun onActivityCreated(value: Activity, savedInstanceState: Bundle?) {
            if (value !is MainActivity) return
            if (++createdCount != 1 || !activity.compareAndSet(null, value)) {
                failure.compareAndSet(null, IllegalStateException("Startup MainActivity recreated/replaced")); done.set(true); return
            }
            createdNs = SystemClock.elapsedRealtimeNanos()
        }
        override fun onActivityStarted(value: Activity) { if (value === activity.get() && startedNs == null) startedNs = SystemClock.elapsedRealtimeNanos() }
        override fun onActivityResumed(value: Activity) { if (value === activity.get() && resumedNs == null) resumedNs = SystemClock.elapsedRealtimeNanos() }
        override fun onActivityPaused(value: Activity) = Unit
        override fun onActivityStopped(value: Activity) = Unit
        override fun onActivitySaveInstanceState(value: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(value: Activity) {
            if (value === activity.get() && !done.get()) {
                failure.compareAndSet(null, IllegalStateException("Startup Activity destroyed before observation")); done.set(true)
            }
        }

        fun readinessSample(): StartupReadinessSample {
            check(Looper.myLooper() == Looper.getMainLooper())
            check(firstFullIdentity.get() != null) { "UNAVAILABLE_ENGINE_RESOLUTION_BEFORE_ACTUAL_FULL_BUFFER" }
            val current = requireNotNull(activity.get())
            // A real timestamp-joined FULL buffer already exists. This reads the existing engine;
            // it is never used to construct/initialize a ViewModel or native engine before startup.
            val engine = resolvedEngine ?: RendererReleaseAcceptanceSupport.resolveEngine(current).also { resolvedEngine = it }
            val session = rendererSession ?: (engine.javaClass.getDeclaredField("rendererSession").apply { isAccessible = true }
                .get(engine) as String).also { rendererSession = it }
            val requestedBefore = engine.bufferTransformCameraRevision() // Passive camera token, not a frame counter.
            val state = engine.status.value
            val library = (MainActivity::class.java.getDeclaredField("charts").apply { isAccessible = true }
                .get(current) as? ChartLibraryActions)?.state?.value
            val residency = library?.residency
            val coverage = RendererStartupGeometryCoverage(state.residentCatalogActive,
                residency != null && residency.catalogGeneration == state.residentCatalogGeneration,
                residency?.enabled == true, residency?.complete == true,
                residency?.resident ?: -1, residency?.required ?: -1, residency?.missing ?: -1, residency?.loading ?: -1)
            val presenter = presenterIdentity(current)
            val buffer = presenter?.let { property(it, "buffer") as? ChartBufferSnapshot }
            val requestedAfter = engine.bufferTransformCameraRevision()
            val authority = buffer?.takeIf { requestedBefore == requestedAfter }?.let {
                rendererStartupCameraAuthority(session, requestedAfter, state, it)
            }
            val exact = authority != null && presenter != null && property(presenter, "unavailableReason").toString() == "NONE"
            return StartupReadinessSample(state, residency, coverage, library?.busy != false, exact,
                if (buffer != null && presenter != null) bufferIdentity(buffer, presenter) else null, authority,
                requestedBefore, requestedAfter, buffer?.frame?.revision)
        }

        fun capturedWindowSample(identity: JSONObject): StartupCapturedWindow {
            check(Looper.myLooper() == Looper.getMainLooper())
            val recorded = recordedBuffer(identity)
            val before = readinessSample()
            val engine = requireNotNull(resolvedEngine)
            // Exact timestamp lookup is passive; chartBufferForTimestamp would observe/ACK and is forbidden here.
            val registered = engine.chartBufferDiagnostics(recorded.stamp)
            val after = readinessSample()
            val authority = registered?.takeIf { after.requestedBefore == after.requestedAfter }?.let {
                rendererStartupCameraAuthority(requireNotNull(rendererSession), after.requestedAfter, after.engine, it)
            }
            return StartupCapturedWindow(before, after, registered, registered?.let(recorded::matches) == true, authority)
        }

        fun snapshot(): JSONObject {
            check(Looper.myLooper() == Looper.getMainLooper())
            return JSONObject().put("clock", "SystemClock.elapsedRealtimeNanos / CLOCK_BOOTTIME")
            .put("readPollIntervalRequestedMs", 8).put("fullReadCount", full.reads).put("eligibleReadCount", eligible.reads)
            .put("activityCreatedElapsedRealtimeNs", createdNs ?: JSONObject.NULL)
            .put("activityStartedElapsedRealtimeNs", startedNs ?: JSONObject.NULL)
            .put("activityResumedElapsedRealtimeNs", resumedNs ?: JSONObject.NULL)
            .put("mainActivityCreatedCount", createdCount).put("lastUnavailableReason", lastUnavailableReason)
            .put("firstPresenterFullReadBracket", bracket(full.first))
            .put("firstGeometryEligiblePresenterReadBracket", bracket(eligible.first))
            .put("firstPresenterFullIdentity", firstFullIdentity.get() ?: JSONObject.NULL)
            .put("modelAfterFirstPresenterFullRead", modelAfterFirstFullRead.get() ?: JSONObject.NULL)
            .put("firstGeometryEligiblePresenterIdentity", firstEligibleIdentity.get() ?: JSONObject.NULL)
            .put("earlyFullBufferImpliesCompleteRequiredGeometry", false)
            .put("firstProducerFullEvent", "NOT_MEASURED").put("firstDisplayEvent", "NOT_MEASURED")
            .put("bracketMeaning", "Conservative prior negative-read START through first positive-read END. Identity was observed during that read; no exact production, texture visibility or display timestamp is inferred.")
        }
    }

    private data class StartupReadinessSample(
        val engine: ChartEngineStatus, val residency: ChartResidencyStatus?, val coverage: RendererStartupGeometryCoverage,
        val libraryBusy: Boolean, val exactPresenterForEngine: Boolean, val identity: JSONObject?,
        val authority: RendererStartupCameraAuthority?, val requestedBefore: Long, val requestedAfter: Long,
        val registryFrameRevision: Long?,
    ) {
        val ready: Boolean get() = coverage.ready && !libraryBusy && exactPresenterForEngine
        fun json() = JSONObject().put("initialized", engine.initialized).put("chartLoaded", engine.chartLoaded)
            .put("surfaceAttached", engine.surfaceAttached).put("presentationPending", engine.presentationPending)
            .put("residentCameraReady", engine.residentCameraReady).put("residentStartState", engine.residentStartState.name)
            .put("sourcePresentationUnsupported", engine.sourcePresentationUnsupported)
            .put("sourceGeneration", engine.chartSourceGeneration).put("contentGeneration", engine.chartContentGeneration)
            .put("surfaceGeneration", engine.surfaceGeneration).put("presentationGeneration", engine.presentationGeneration)
            .put("confirmedFrameRevision", engine.confirmedFrame?.revision ?: JSONObject.NULL)
            .put("registryFrameRevision", registryFrameRevision ?: JSONObject.NULL)
            .put("requestedCameraRevisionBefore", requestedBefore).put("requestedCameraRevisionAfter", requestedAfter)
            .put("revisionDomains", "OFF confirmedFrame revision is a counter; registry frame revision is its stamp. Both bind through their exact session/surface-or-registry-generation/camera tokens, geometry, viewport, source and content.")
            .put("cameraAuthorityBound", authority != null)
            .put("residentCatalogActive", engine.residentCatalogActive).put("knownCurrentCatalogResidency", coverage.knownCurrentCatalog)
            .put("residencyEnabled", coverage.enabled).put("residencyComplete", coverage.complete)
            .put("residentCells", coverage.resident).put("requiredCells", coverage.required)
            .put("missingRequiredCells", coverage.missing).put("loadingCells", coverage.loading)
            .put("requiredGeometryReady", coverage.ready).put("libraryBusyOrUnknown", libraryBusy)
            .put("exactPresenterForEngine", exactPresenterForEngine).put("presenterIdentity", identity ?: JSONObject.NULL)
            .put("engineResolutionPrerequisite", "ACTUAL_TIMESTAMP_JOINED_PRESENTER_FULL_BUFFER_ALREADY_OBSERVED")
            .put("residencyRead", "EXISTING_CHART_CONTROLLER_STATE_ONLY; NO_NATIVE_READ_OR_COMMAND")
    }

    private data class StartupCapturedWindow(val before: StartupReadinessSample, val after: StartupReadinessSample,
        val registered: ChartBufferSnapshot?, val fieldsMatch: Boolean, val authority: RendererStartupCameraAuthority?)

    companion object {
        private const val FIRST_OBSERVATION_BUDGET_MS = 30_000L
        private const val TEST_BUDGET_MS = 55_000L
        private const val WINDOW_AND_CLEANUP_RESERVE_MS = 20_000L
        private const val MAX_VIEW_NODES = 512

        private fun bracket(value: RendererStartupObservationBracket?): Any = value?.let {
            JSONObject().put("possibleAppearanceAfterNs", it.possibleAppearanceAfterNs)
                .put("positiveReadStartedNs", it.positiveReadStartedNs).put("observedByNs", it.positiveReadCompletedNs)
                .put("precedingNegativeReads", it.precedingNegativeReads).put("exactEventTime", "NOT_MEASURED")
        } ?: JSONObject.NULL

        private fun presenterIdentity(activity: MainActivity): Any? {
            var nodes = 0; var presenter: View? = null
            fun visit(view: View) {
                check(++nodes <= MAX_VIEW_NODES) { "Startup View traversal exceeds bound" }
                if (view.javaClass.name == "com.no5.helm.ui.ChartPresentationView" && view.isShown) {
                    check(presenter == null) { "Ambiguous visible chart presenters" }; presenter = view
                } else if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(activity.window.decorView)
            val view = presenter ?: return null
            return view.javaClass.declaredMethods.single { it.name.startsWith("readWindowIdentity") && it.parameterCount == 0 }
                .apply { isAccessible = true }.invoke(view)
        }

        private fun property(value: Any, name: String): Any? = value.javaClass.declaredMethods.single {
            it.parameterCount == 0 && it.name == "get" + name.replaceFirstChar(Char::uppercaseChar)
        }.apply { isAccessible = true }.invoke(value)

        private fun bufferIdentity(buffer: ChartBufferSnapshot, identity: Any) = JSONObject()
            .put("stamp", buffer.stamp).put("cameraRevision", buffer.cameraRevision).put("bufferGeneration", buffer.generation)
            .put("sourceGeneration", buffer.frame.sourceGeneration).put("contentGeneration", buffer.frame.contentGeneration)
            .put("producerCompleted", buffer.producerCompleted).put("stage", buffer.stage.name).put("producerSlot", buffer.presentationSlot)
            .put("presenterGeneration", property(identity, "presenterGeneration")).put("producerGeneration", property(identity, "producerGeneration"))
            .put("windowEpoch", property(identity, "windowEpoch")).put("attachmentEpoch", property(identity, "attachmentEpoch"))

        private fun sameFullIdentity(first: JSONObject, window: JSONObject?): Boolean = window != null &&
            listOf("stamp", "cameraRevision", "bufferGeneration", "sourceGeneration", "contentGeneration", "producerCompleted", "stage",
                "producerSlot", "presenterGeneration", "producerGeneration", "windowEpoch", "attachmentEpoch")
                .all { first.get(it) == window.opt(it) }

        private fun sameRequiredGeometry(a: ChartResidencyStatus?, b: ChartResidencyStatus?): Boolean = a != null && b != null &&
            a.catalogGeneration == b.catalogGeneration && a.cameraKey == b.cameraKey && a.policyKey == b.policyKey &&
            a.cameraRevision == b.cameraRevision && a.contentRevision == b.contentRevision && a.enabled == b.enabled &&
            a.complete == b.complete && a.resident == b.resident && a.required == b.required && a.missing == b.missing && a.loading == b.loading

        private fun recordedBuffer(identity: JSONObject): RendererStartupRecordedBuffer {
            val view = identity.getJSONObject("viewport")
            return RendererStartupRecordedBuffer(identity.getLong("stamp"), identity.getLong("cameraRevision"),
                identity.getLong("bufferGeneration"), identity.getLong("sourceGeneration"), identity.getLong("contentGeneration"),
                identity.getBoolean("producerCompleted"), identity.getString("stage"), identity.getInt("producerSlot"),
                ChartViewport(view.getDouble("latitude"), view.getDouble("longitude"), view.getDouble("metresToPixels"),
                    view.getDouble("rotationRadians"), view.getInt("width"), view.getInt("height")))
        }

        private fun clockPair(): JSONObject {
            val before = SystemClock.elapsedRealtimeNanos(); val mono = System.nanoTime(); val after = SystemClock.elapsedRealtimeNanos()
            return JSONObject().put("elapsedBeforeNs", before).put("nanoTimeNs", mono).put("elapsedAfterNs", after)
                .put("clockJoin", "BOOTTIME brackets one MONOTONIC read; clocks are not assumed identical")
        }

        private fun nativeMaps(): JSONObject {
            val result = JSONObject().put("source", "/proc/self/maps").put("status", "UNAVAILABLE")
                .put("startedElapsedRealtimeNs", SystemClock.elapsedRealtimeNanos())
                .put("loadedElfSha256", "NOT_MEASURED; host must bind marker/file/hash separately")
            try {
                val matches = JSONArray(); var lines = 0; var chars = 0; var truncated = false
                File("/proc/self/maps").bufferedReader().use { input ->
                    while (true) {
                        val line = input.readLine() ?: break
                        if (++lines > 4096 || line.length > 4096 || chars + line.length > 1_048_576) { truncated = true; break }
                        chars += line.length
                        if (line.contains("libno5_chart")) {
                            if (matches.length() >= 64) { truncated = true; break }; matches.put(line)
                        }
                    }
                }
                result.put("status", "READ").put("matchingLines", matches).put("linesRead", lines)
                    .put("charactersRead", chars).put("truncated", truncated)
                    .put("matchingNativePathObserved", matches.length() > 0)
            } catch (caught: Exception) { result.put("failureType", caught.javaClass.simpleName) }
            return result.put("completedElapsedRealtimeNs", SystemClock.elapsedRealtimeNanos())
        }

        private fun <T> onMain(read: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return read()
            val result = AtomicReference<T>(); val failure = AtomicReference<Throwable?>(); val abandoned = AtomicBoolean(false)
            val done = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                if (abandoned.get()) { done.countDown(); return@post }
                try { result.set(read()) } catch (caught: Throwable) { failure.set(caught) } finally { done.countDown() }
            }
            if (!done.await(2, TimeUnit.SECONDS)) { abandoned.set(true); error("Startup main-thread read unavailable") }
            failure.get()?.let { throw it }; return result.get()
        }
    }
}
