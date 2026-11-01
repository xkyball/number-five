package com.no5.helm

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.chart.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * Separate exact native-output evidence on the regular same-signer release. Two 15s owners,
 * OFF then ALL, share one target process/catalog and each restores before the next starts.
 * No WindowPixelCopy, screenshots, raw JNI, reference normalization or timing comparison.
 */
class RendererReleaseParityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun currentCatalogOffAllExactNativeParity() {
        assumeTrue("Explicit separate parity opt-in required",
            InstrumentationRegistry.getArguments().getString("no5.renderer.parity") == "true")
        val proof = RendererReleaseAcceptanceSupport(compose) // Identity checks precede Activity launch.
        val result = JSONObject().put("schema", "no5.renderer-release-parity-acceptance/1")
            .put("run", proof.run).put("status", "FAIL").put("timingComparable", false)
            .put("windowPixelCopy", "NOT_RUN").put("physicalDisplay", "NOT_MEASURED")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var current: ChartEngine? = null
                scenario.onActivity { current = proof.engine(it) }
                val engine = requireNotNull(current)
                proof.ready(engine); proof.uncovered(compose)
                val entry = engine.status.value
                if (!entry.residentCatalogActive || entry.bufferTransformMode != ChartBufferTransformMode.OFF) {
                    result.put("status", "UNAVAILABLE").put("reason", "CURRENT_RESIDENT_CATALOG_AND_OFF_REQUIRED")
                    assumeTrue("No catalog switch or experiment-mode override is permitted", false)
                }
                val (original, preparation) = proof.prepareCurrentViewReuse(engine)
                result.put("preparation", preparation)
                var baseline: JSONObject? = null
                for (variant in listOf(RendererViewportReuseVariant.OFF, RendererViewportReuseVariant.ALL)) {
                    proof.ready(engine); proof.uncovered(compose)
                    if (baseline != null) proof.original(original, engine.status.value, exactSource = true)
                    val snapshot = runBlocking { requireNotNull(engine.snapshotRendererOuterState(UUID.randomUUID().toString())) }
                    proof.write("${variant.name}-outer.json", snapshot)
                    // Existing native-owner admission and exact six-pose builder. No copied camera calculations.
                    val configure = Class.forName("com.no5.helm.ui.RendererCurrentViewMeasurementKt").declaredMethods.single {
                        it.name.startsWith("currentViewReuseParityConfig") && it.parameterCount == 5
                    }.apply { isAccessible = true }
                    val config = configure.invoke(null, snapshot, engine.status.value, variant,
                        SystemClock.elapsedRealtime(), UUID.randomUUID().toString()) as RendererBenchmarkConfig
                    check(config.viewportReuseParity && !config.viewportReuseDetailed && !config.normalRenderAbort)
                    val report = proof.execute(engine, config, variant.name)
                    val signature = exactSignature(report, original, variant)
                    proof.original(original, engine.status.value, exactSource = true)
                    proof.uncovered(compose)
                    proof.write("${variant.name}-signature.json", signature.toString(2), 256 * 1024)
                    if (baseline == null) baseline = signature else assertEquals(
                        "All six complete RGBA/semantic/association outputs and ordered five queries must be exact; no masks/tolerances",
                        baseline!!.toString(), signature.toString())
                }
                result.put("status", "PASS").put("nativeArms", 2).put("framesPerArm", 6).put("queriesPerFrame", 5)
                    .put("strictNativePixelQueryParity", true).put("catalogUnchanged", true).put("originalStateRestored", true)
                    .put("nativeDefaultFlagsRestored", true).put("normalRenderAbort", "NOT_TESTED_BY_THIS_INDEPENDENT_REUSE_PAIR")
            }
        } catch (failure: Throwable) {
            result.put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally { proof.write("result.json", result.toString(2), 32 * 1024) }
    }

    private fun exactSignature(report: JSONObject, start: ChartEngineStatus, variant: RendererViewportReuseVariant): JSONObject {
        assertEquals("no5-resident-capacity/1", report.getString("schema"))
        val metadata = report.getJSONObject("metadata"); val owner = metadata.getString("runId")
        assertEquals(variant.name, metadata.getString("viewportReuse"))
        assertTrue(metadata.getBoolean("parity")); assertFalse(metadata.getBoolean("detailed"))
        assertFalse(metadata.getBoolean("timingComparable")); assertFalse(metadata.getBoolean("catalogSwap"))
        assertFalse(metadata.getBoolean("geometryReset")); assertFalse(metadata.getBoolean("automaticActivation"))
        assertEquals(start.residentCatalogGeneration, metadata.getString("catalogGeneration"))
        assertEquals(start.chartSourceGeneration, metadata.getLong("sourceGeneration"))
        val restored = report.getJSONObject("viewportReuseRestoration")
        assertEquals(owner, restored.getString("runId")); assertTrue(restored.getBoolean("available"))
        assertFalse(restored.getBoolean("active"))
        for (flag in listOf("soundings", "spatial", "gpu")) {
            assertFalse("The production baseline remains OFF", restored.getJSONObject("original").getBoolean(flag))
            assertEquals(restored.getJSONObject("original").getBoolean(flag), restored.getJSONObject("current").getBoolean(flag))
        }
        val events = report.getJSONObject("events")
        val recipe = events.getJSONObject("recipe")
        assertEquals("CURRENT_VIEW_REUSE", recipe.getString("id")); assertTrue(recipe.getBoolean("comparableRealizedPath"))
        val steps = recipe.getJSONArray("results"); assertEquals(6, steps.length())
        val set = events.getJSONObject("viewportReuseParity")
        assertEquals("no5.viewport-reuse-parity-set/1", set.getString("schema")); assertEquals(owner, set.getString("runId"))
        assertTrue(set.getBoolean("complete")); assertEquals(6, set.getInt("frameCount")); assertEquals(6, set.getInt("requiredFrames"))
        assertEquals(5, set.getInt("queriesPerFrame")); assertFalse(set.getBoolean("timingComparable"))
        assertEquals("NONE", set.getString("normalization")); assertFalse(set.getBoolean("rawRgbaPersisted"))
        val frames = set.getJSONArray("frames"); assertEquals(6, frames.length())
        val signatures = JSONArray()
        for (index in 0 until 6) {
            val capture = frames.getJSONObject(index); val step = steps.getJSONObject(index)
            assertEquals(index, capture.getInt("stepIndex")); assertEquals(index, step.getInt("stepIndex"))
            assertTrue(capture.getBoolean("sameAuthority")); assertTrue(step.getBoolean("sameAuthority")); assertTrue(step.getBoolean("sameRealizedPose"))
            val before = capture.getJSONObject("authorityBefore"); val after = capture.getJSONObject("authorityAfter")
            assertEquals(before.toString(), after.toString()); assertEquals(owner, before.getString("runId"))
            assertEquals(start.chartSourceGeneration, before.getLong("sourceGeneration"))
            assertEquals(start.surfaceGeneration, before.getLong("surfaceGeneration"))
            assertEquals(start.residentCatalogGeneration, before.getString("catalogGeneration"))
            assertEquals(step.getJSONObject("result").getJSONObject("authority").getLong("cameraRevision"), before.getLong("cameraRevision"))
            assertEquals(step.getJSONObject("wantedViewport").toString(), before.getJSONObject("viewport").toString())
            val native = capture.getJSONObject("native")
            assertEquals("no5.viewport-reuse-parity/1", native.getString("schema")); assertEquals(owner, native.getString("runId"))
            assertEquals(before.getLong("nativeFrame"), native.getLong("frame"))
            assertEquals(before.getLong("nativeDataGeneration"), native.getLong("dataGeneration"))
            assertEquals(before.getLong("nativeContentRevision"), native.getLong("contentRevision"))
            assertEquals(before.getLong("presentationGeneration"), native.getLong("presentationGeneration"))
            assertEquals(before.getString("catalogGeneration"), native.getString("catalogGeneration"))
            val viewport = before.getJSONObject("viewport")
            for ((key, nativeKey) in listOf("latitude" to "latitude", "longitude" to "longitude", "metresToPixels" to "ppm",
                "rotationRadians" to "rotation", "width" to "width", "height" to "height"))
                assertEquals(viewport.getDouble(key), native.getDouble(nativeKey), 0.0)
            assertEquals(native.getLong("width") * native.getLong("height") * 4, native.getLong("rgbaBytes"))
            assertEquals("NONE", native.getString("normalization"))
            for (key in listOf("extraDraws", "extraSwaps", "consumerAcknowledgements")) assertEquals(0L, native.getLong(key))
            assertEquals("completed native output FBO, full 1:1 RGBA; no post-swap window read", native.getString("pixelSource"))
            val signature = JSONObject().put("viewport", viewport).put("rgbaBytes", native.getLong("rgbaBytes"))
                .put("featureCount", native.getLong("featureCount"))
            for (key in listOf("rgbaSHA256", "semanticSHA256", "associationsSHA256")) {
                assertTrue(native.getString(key).matches(Regex("[a-f0-9]{64}"))); signature.put(key, native.getString(key))
            }
            val queries = native.getJSONArray("queries"); assertEquals(5, queries.length())
            assertEquals("viewport-center-five/1: x=w/2+(i-2)*w/8, y=h/2+(i%2)*h/8, radius=24, offset=0", native.getString("queryRecipe"))
            for (queryIndex in 0 until 5) {
                val query = queries.getJSONObject(queryIndex)
                assertEquals(native.getInt("width") * .5 + (queryIndex - 2) * native.getInt("width") * .125, query.getDouble("x"), 0.0)
                assertEquals(native.getInt("height") * .5 + (queryIndex % 2) * native.getInt("height") * .125, query.getDouble("y"), 0.0)
                assertEquals(24, query.getInt("radius")); assertEquals(0, query.getInt("offset"))
                assertTrue(query.getInt("jsonBytes") in 0..(256 * 1024))
                assertTrue(query.getString("sha256").matches(Regex("[a-f0-9]{64}")))
            }
            signature.put("orderedQueries", queries)
            val status = native.getJSONObject("status"); val oracle = JSONObject()
            for (key in listOf("filteredFeatures", "viewportSelectedCharts", "coverageUnknownCells", "coverageUncoveredPixels",
                "selectedCharts", "featureCount", "unsupportedFeatures", "coverageMasked", "objectSelection")) oracle.put(key, status.get(key))
            signatures.put(signature.put("unchangedStatusOracle", oracle))
        }
        return JSONObject().put("catalogGeneration", metadata.getString("catalogGeneration"))
            .put("sourceGeneration", metadata.getLong("sourceGeneration")).put("sourcePins", metadata.getJSONArray("sourcePins"))
            .put("cells", metadata.getInt("cells")).put("chargedByteBudget", metadata.getLong("chargedByteBudget"))
            .put("loadingByteReservation", metadata.getLong("loadingByteReservation"))
            .put("recipeSha256", recipe.getString("sha256")).put("frames", signatures)
    }
}
