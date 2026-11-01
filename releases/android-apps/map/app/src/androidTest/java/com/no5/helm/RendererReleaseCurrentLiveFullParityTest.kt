package com.no5.helm

import android.Manifest
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

/** Separate native FULL evidence. No captures enter the six timed OFF/ON arms.
 * Existing current catalog/geometry/S52/overlays and the existing six-pose recipe are retained.
 * This is neither physical input nor Window/scanout/GPU/FPS evidence. */
class RendererReleaseCurrentLiveFullParityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun currentCatalogNormalAbortOffOnExactNativeFullParity() {
        assumeTrue("Explicit separate current-live FULL parity opt-in required", InstrumentationRegistry.getArguments()
            .getString("no5.renderer.currentLiveFullParity") == "true")
        val proof = RendererReleaseAcceptanceSupport(compose)
        val catalogPin = requireNotNull(proof.arguments.getString("no5.renderer.currentLiveFullParity.catalogGeneration"))
        require(catalogPin.matches(Regex("[a-f0-9]{64}")))
        val result = JSONObject().put("schema", "no5.renderer-release-current-live-full-parity-acceptance/1")
            .put("run", proof.run).put("status", "NOT_ACCEPTED").put("identity", proof.identity)
            .put("timingComparable", false).put("performanceBenefit", "NOT_ESTABLISHED")
            .put("physicalInput", "NOT_TESTED; existing owner camera proposals")
            .put("windowPixelCopy", "NOT_RUN").put("physicalDisplay", "NOT_MEASURED").put("gpuTimeOrFps", "NOT_MEASURED")
            .put("loadedNativeOrigin", "EXTERNAL_PROCESS_MAP_PROOF_REQUIRED")
            .put("catalogSwitch", false).put("geometryReset", false).put("automaticPreparation", false)
            .put("ownerStarted", false)
        val owners = JSONArray(); result.put("owners", owners)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var activity: MainActivity
                lateinit var engine: ChartEngine
                scenario.onActivity { activity = it; engine = proof.engine(it) }
                proof.ready(engine); proof.uncovered(compose)
                runBlocking { requireCurrentLiveParityHostReady(activity) }
                val eligibility = engine.status.value
                if (!eligibility.residentCatalogActive || eligibility.bufferTransformMode != ChartBufferTransformMode.OFF) {
                    result.put("status", "UNAVAILABLE").put("reason", "CURRENT_RESIDENT_CATALOG_AND_OFF_REQUIRED")
                    assumeTrue("No source replacement or mode override is permitted", false)
                }
                val originalIntent = currentLiveParityPositions(activity).state.value.intent
                var original: RendererCurrentLiveParityOriginal? = null
                var baseline: JSONObject? = null
                val ownerIds = mutableSetOf<String>()
                for (enabled in listOf(false, true)) {
                    val label = if (enabled) "ON" else "OFF"
                    proof.ready(engine); proof.uncovered(compose)
                    runBlocking { requireCurrentLiveParityHostReady(activity) }
                    val before = runBlocking { RendererCurrentLiveParityOriginal.capture(activity, engine) }
                    assertEquals("Explicit actual current catalog", catalogPin, before.catalog.generation)
                    if (original == null) {
                        original = before
                        result.put("loadedCellCount", before.state.loadedChartCells.size)
                    } else {
                        assertEquals("Same immutable source/native data/S52/overlays between parity arms", original!!.identity, before.identity)
                        assertEquals("Same saved original camera/catalog/budget/overlays between parity arms", original!!.savedState(), before.savedState())
                        assertEquals("Same ordered retained cells; no implicit preparation", original!!.state.loadedChartCells, before.state.loadedChartCells)
                    }
                    proof.write("$label-outer-before.json", before.outer.toString())
                    proof.write("$label-original-pins.json", before.pins().toString(2), 64 * 1024)
                    val configure = Class.forName("com.no5.helm.ui.RendererCurrentViewMeasurementKt").declaredMethods.single {
                        it.name.startsWith("currentResidentLiveFullParityConfig") && it.parameterTypes.contentEquals(arrayOf(
                            String::class.java, ChartEngineStatus::class.java, Boolean::class.javaPrimitiveType,
                            Long::class.javaPrimitiveType, String::class.java))
                    }.apply { isAccessible = true }
                    val config = configure.invoke(null, before.outer.toString(), engine.status.value, enabled,
                        SystemClock.elapsedRealtime(), UUID.randomUUID().toString()) as RendererBenchmarkConfig
                    assertTrue(config.currentLiveParityOnly); assertFalse(config.liveReplay)
                    assertEquals(15_000L, config.liveDurationMs); assertEquals(enabled, config.normalRenderAbort)
                    assertEquals(RendererRunKind.LIVE_INPUT, config.runKind); assertEquals(ChartBufferTransformMode.CONTROL, config.liveMode)
                    assertEquals(before.outer.getString("snapshotId"), requireNotNull(config.currentResidentLive).snapshotId)
                    assertNull(config.capacityArm); assertNull(config.capacityRecipeId); assertNull(config.viewportReuseCandidate)
                    assertFalse(config.viewportReuseParity); assertFalse(config.viewportReuseDetailed)
                    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                    automation.adoptShellPermissionIdentity(Manifest.permission.DUMP)
                    val report = try {
                        proof.execute(engine, config, label) { requested ->
                            requireCurrentLiveParityHostReady(activity)
                            invokeCurrentLiveParityHost<EngineResult>(activity, "startBenchmark", requested).also { started ->
                                owners.put(JSONObject().put("arm", label).put("ownerStarted", started.success)
                                    .put("runId", if (started.success) started.message else JSONObject.NULL))
                                if (started.success) result.put("ownerStarted", true)
                            }
                        }
                    } finally { automation.dropShellPermissionIdentity() }
                    assertTrue("Each arm must have its own actual owner", ownerIds.add(report.getString("runId")))
                    val signature = currentLiveFullParitySignature(report, before, config)
                    proof.write("$label-signature.json", signature.toString(2), 256 * 1024)
                    if (baseline == null) baseline = signature else assertEquals(
                        "Exact six native FULL RGBA/semantic/association and ordered five-query parity; no masks/tolerances",
                        baseline!!.toString(), signature.toString())
                    proof.ready(engine); proof.uncovered(compose)
                    runBlocking { requireCurrentLiveParityHostReady(activity) }
                    proof.original(before.state, engine.status.value, exactSource = true)
                    assertEquals("Position intent is untouched", originalIntent, currentLiveParityPositions(activity).state.value.intent)
                    val after = runBlocking { RendererCurrentLiveParityOriginal.capture(activity, engine) }
                    proof.write("$label-outer-after.json", after.outer.toString())
                    proof.write("$label-restored-pins.json", after.pins().toString(2), 64 * 1024)
                    assertEquals("Exact original data/interpretation restored", before.identity, after.identity)
                    assertEquals("Exact original saved camera/catalog/budget/settings/overlays restored", before.savedState(), after.savedState())
                    assertEquals("Exact original ordered retained cells restored", before.state.loadedChartCells, after.state.loadedChartCells)
                }
                result.put("status", "PASS").put("nativeArms", 2).put("framesPerArm", 6).put("queriesPerFrame", 5)
                    .put("strictNativePixelQueryParity", true).put("sourceFreezeValidated", true)
                    .put("originalStateRestored", true).put("nativeDefaultFlagsRestored", true)
                    .put("normalAbortCandidateParity", true).put("supersessionTiming", "NOT_MEASURED_BY_THIS_SEPARATE_PARITY_RUN")
                    .put("allRawCounterexamplesRetained", true)
            }
        } catch (failure: Throwable) {
            result.put("failureType", failure.javaClass.simpleName)
                .put("failureMessage", failure.message?.take(512) ?: JSONObject.NULL)
            throw failure
        } finally { proof.write("result.json", result.toString(2), 64 * 1024) }
    }
}
