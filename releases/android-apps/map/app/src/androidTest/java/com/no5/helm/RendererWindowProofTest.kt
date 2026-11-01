package com.no5.helm

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.chart.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Explicitly separate image-evidence execution. The Activity rule may start/recreate the app;
 * neither test supplies a cached-process-start result or normal A/B latency measurement.
 */
class RendererWindowProofTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val engine get() = RendererReleaseAcceptanceSupport.resolveEngine(compose.activity)
    private fun awaitChart() {
        compose.waitUntil(120_000) { engine.status.value.let { it.chartLoaded && it.surfaceAttached &&
            !it.presentationPending && it.residentCameraReady && it.confirmedFrame != null } }
    }
    private fun output() = requireNotNull(instrumentation.targetContext.getExternalFilesDir("renderer-window-proof"))
    private fun requireUncoveredChart() {
        // These checks never dismiss a dialog or discard somebody's draft.
        listOf("screen-panel", "object-info-panel", "resident-chart-loading", "chart-warning", "map-dock", "app-switcher")
            .forEach { tag -> check(compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) { "Chart covered: $tag" } }
        check(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()) { "Chart covered by a dialog" }
    }

    @Test fun currentStableFullWindowEvidence() {
        assumeTrue("Explicit image-evidence opt-in required", arguments.getString("no5.windowProof") == "true")
        awaitChart(); requireUncoveredChart()
        val proof = RendererWindowProof(compose.activity, instrumentation, output())
        var result = JSONObject()
        try { result = proof.capture("current-full"); requireUncoveredChart() }
        finally { proof.finish(JSONObject().put("processStart", "NOT_MEASURED").put("recipe", "CURRENT_STABLE_VIEW")) }
        assertEquals("Window capture with stable known FULL identity required", "WINDOW_CAPTURED_WHILE_FULL_IDENTITY_STABLE", result.optString("status"))
        assertEquals("Captured Window/composite pixels must match; this is not native FULL/reference parity", "WINDOW_CHART_PIXELS_MATCH_DISPLAY_COMPOSITE",
            result.getJSONObject("displayComposite").optString("status"))
    }

    @Test fun currentViewSixPoseWindowEvidence() {
        assumeTrue("Explicit separate CURRENT_VIEW_REUSE evidence opt-in required",
            arguments.getString("no5.windowProof") == "true" && arguments.getString("no5.windowProof.recipe") == "CURRENT_VIEW_REUSE")
        awaitChart(); requireUncoveredChart()
        val start = engine.status.value
        assertTrue("Current resident catalog required; no fixture substitution", start.residentCatalogActive)
        assertEquals("Operator must preserve and separately restore their existing mode; this test changes no setting",
            ChartBufferTransformMode.OFF, start.bufferTransformMode)
        val proof = RendererWindowProof(compose.activity, instrumentation, output())
        val initialBuffer = requireNotNull(proof.peekBuffer()) { "Initial exact FULL buffer unavailable" }
        val variant = RendererViewportReuseVariant.valueOf(arguments.getString("no5.windowProof.variant") ?: "OFF")
        val snapshot = runBlocking { requireNotNull(engine.snapshotRendererOuterState(UUID.randomUUID().toString())) }
        // Reuse the existing UI admission/config builder; no copied camera math, budget or recipe.
        val configure = Class.forName("com.no5.helm.ui.RendererCurrentViewMeasurementKt").declaredMethods.single {
            it.name.startsWith("currentViewReuseConfig") && it.parameterCount == 6
        }.apply { isAccessible = true }
        val config = configure.invoke(null, snapshot, engine.status.value, variant, true,
            SystemClock.elapsedRealtime(), UUID.randomUUID().toString()) as RendererBenchmarkConfig
        val started = runBlocking { engine.startRendererBenchmark(config) }
        if (!started.success) proof.finish(JSONObject().put("status", "UNAVAILABLE").put("reason", "OWNER_REFUSED"))
        assertTrue("Existing owner must admit and journal this exact current-catalog run", started.success)
        val nativeRunId = started.message
        val images = JSONArray()
        var nativeReport: JSONObject? = null
        var nativeText = ""
        var failure: Throwable? = null
        try {
            var previous: ChartViewport? = null
            val until = SystemClock.elapsedRealtime() + 60_000
            while (SystemClock.elapsedRealtime() < until) {
                val status = engine.status.value.rendererBenchmark
                check(status.runId == nativeRunId) { "Evidence owner changed" }
                if (!status.active) break
                if (status.phase == "sampling" && images.length() < 6) {
                    val visible = proof.peekBuffer()
                    // This is the actual observed Buffer, not the latest submitted camera.
                    if (visible != null && visible.cameraRevision > initialBuffer.cameraRevision &&
                        visible.frame.sourceGeneration == start.chartSourceGeneration && visible.frame.viewport != previous) {
                        requireUncoveredChart()
                        val image = proof.capture("pose-${images.length()}", visible.frame.viewport)
                        requireUncoveredChart()
                        if (image.optString("status") == "WINDOW_CAPTURED_WHILE_FULL_IDENTITY_STABLE") {
                            previous = visible.frame.viewport; images.put(image)
                        } else check(image.optString("reason") !in setOf("CAPACITY", "CANCELLED", "COPY_TIMEOUT")) {
                            "Bounded Window proof unavailable: ${image.optString("reason")}" }
                    }
                }
                SystemClock.sleep(25)
            }
            check(!engine.status.value.rendererBenchmark.active) { "Evidence run exceeded bounded wait" }
        } catch (caught: Throwable) { failure = caught }
        finally {
            if (engine.status.value.rendererBenchmark.let { it.active && it.runId == nativeRunId }) {
                proof.cancel(); engine.cancelRendererBenchmark(nativeRunId)
            }
            val restoreUntil = SystemClock.elapsedRealtime() + 30_000
            while (engine.status.value.rendererBenchmark.runId == nativeRunId && engine.status.value.rendererBenchmark.active &&
                SystemClock.elapsedRealtime() < restoreUntil) SystemClock.sleep(25)
            val report = runCatching { runBlocking { engine.readRendererReport() } }.getOrNull()
            if (report?.success == true) {
                nativeText = report.message
                nativeReport = runCatching { JSONObject(nativeText) }.getOrNull()
            }
            val matched = nativeReport?.let { runCatching { correlate(images, it, nativeRunId) }.getOrDefault(false) } ?: false
            val file = proof.finish(JSONObject().put("nativeRunId", nativeRunId).put("variant", variant.name)
                .put("processStart", "NOT_MEASURED").put("recipe", "CURRENT_VIEW_REUSE")
                .put("nativeReportSHA256", sha256(nativeText.toByteArray(Charsets.UTF_8)))
                .put("allSixStableIdentityCaptureAssociations", matched).put("failure", failure?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("restorationConfirmed", engine.status.value.let { !it.rendererBenchmarkRecoveryPending &&
                    !it.rendererBenchmark.active && it.rendererBenchmark.runId == nativeRunId && it.rendererBenchmark.restored })
                .put("normalTimingComparable", false))
            if (nativeText.isNotEmpty()) File(file.parentFile, "native-report.json").writeText(nativeText)
        }
        failure?.let { throw it }
        val report = requireNotNull(nativeReport)
        assertEquals("The same owner's recipe must complete", "completed", report.getString("outcome"))
        assertTrue("Native owner must confirm restoration", report.getBoolean("restored"))
        assertTrue("No pending recovery may be hidden", !engine.status.value.rendererBenchmarkRecoveryPending)
        assertEquals("Current catalog preserved", start.residentCatalogGeneration, engine.status.value.residentCatalogGeneration)
        assertEquals("Current presentation preserved", start.presentation, engine.status.value.presentation)
        assertEquals("Original camera restored", start.viewport, engine.status.value.viewport)
        assertTrue("Missing, changed, early or ambiguous observations remain UNAVAILABLE", runCatching { correlate(images, report, nativeRunId) }.getOrDefault(false))
    }

    /** Post-hoc association uses original revision+pose+timestamps from this exact owner's receipt. */
    private fun correlate(images: JSONArray, report: JSONObject, nativeRunId: String): Boolean {
        if (images.length() != 6 || report.optJSONObject("metadata")?.optString("runId") != nativeRunId) return false
        val recipe = report.optJSONObject("events")?.optJSONObject("recipe") ?: return false
        if (recipe.optString("id") != "CURRENT_VIEW_REUSE" || !recipe.optBoolean("comparableRealizedPath")) return false
        val steps = recipe.optJSONArray("results") ?: return false
        if (steps.length() != 6) return false
        for (index in 0 until 6) {
            val image = images.getJSONObject(index); val identity = image.getJSONObject("identity")
            val step = steps.getJSONObject(index); val result = step.getJSONObject("result")
            val composite = image.getJSONObject("displayComposite")
            val lastAllowedNs = if (index < 5) steps.getJSONObject(index + 1).getLong("proposedAtNs")
                else recipe.getLong("startedNs") + recipe.getLong("durationMs") * 1_000_000
            if (!step.optBoolean("sameRealizedPose") || !step.optBoolean("sameAuthority") ||
                identity.getLong("cameraRevision") != result.getJSONObject("authority").getLong("cameraRevision") ||
                identity.getLong("sourceGeneration") != result.getJSONObject("authority").getLong("sourceGeneration") ||
                !sameCamera(identity.getJSONObject("viewport"), step.getJSONObject("wantedViewport")) ||
                image.getLong("captureStartedMonoNs") < step.getLong("proposalReturnedAtNs") ||
                image.getLong("captureCompletedMonoNs") >= lastAllowedNs ||
                composite.optString("status") != "WINDOW_CHART_PIXELS_MATCH_DISPLAY_COMPOSITE" ||
                composite.getLong("captureCompletedMonoNs") >= lastAllowedNs) return false
            image.put("identityRecipeStepIndex", index)
        }
        return true
    }
    private fun sameCamera(actual: JSONObject, expected: JSONObject): Boolean =
        listOf("latitude", "longitude", "metresToPixels", "rotationRadians", "width", "height").all { actual.getDouble(it) == expected.getDouble(it) }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
