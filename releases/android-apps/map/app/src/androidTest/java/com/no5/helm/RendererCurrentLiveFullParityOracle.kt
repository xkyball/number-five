package com.no5.helm

import com.no5.helm.chart.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.util.UUID

/** The unchanged native whole-FBO/query/model oracle, applied to the new explicit capture scope.
 * Frame/registry/camera/native counters remain distinct. Only content enters OFF/ON equality. */
internal fun currentLiveFullParitySignature(report: JSONObject, original: RendererCurrentLiveParityOriginal,
    config: RendererBenchmarkConfig): JSONObject {
    val enabled = config.normalRenderAbort
    val owner = report.getString("runId")
    assertEquals(UUID.fromString(owner).toString(), owner)
    assertEquals("no5-renderer-live-input/1", report.getString("schema"))
    assertEquals("LIVE_INPUT", report.getString("runKind"))
    assertEquals("completed", report.getString("outcome")); assertTrue(report.getBoolean("restored"))
    assertEquals("", report.getString("error")); assertEquals("", report.getString("restorationError"))
    val control = report.getJSONObject("measurementControl")
    assertEquals("no5.measurement-control/1", control.getString("schema")); assertEquals(owner, control.getString("runId"))
    assertEquals(config.clientRunToken, control.getString("clientRunToken"))
    assertEquals(config.deadlineElapsedRealtimeMs, control.getLong("deadlineElapsedRealtimeMs"))
    assertEquals(config.recoveryBudgetMs, control.getLong("recoveryBudgetMs"))
    assertEquals(1, control.getInt("requestedWorkers")); assertEquals("", control.getString("cancelReason"))
    assertFalse(control.getBoolean("recoveryDeadlineExceeded"))
    val recoveryStart = control.getLong("recoveryStartedElapsedRealtimeMs")
    val recoveryEnd = control.getLong("recoveryFinishedElapsedRealtimeMs")
    check(recoveryStart >= 0 && recoveryEnd >= recoveryStart && recoveryEnd - recoveryStart <= config.recoveryBudgetMs)
    val metadata = report.getJSONObject("metadata")
    assertEquals(owner, metadata.getString("runId")); assertEquals("CONTROL", metadata.getString("mode"))
    assertTrue(metadata.getBoolean("currentLiveParityOnly")); assertFalse(metadata.getBoolean("timingComparable"))
    assertFalse(metadata.getBoolean("replayEnabled")); assertEquals(15_000L, metadata.getLong("liveDurationMs"))
    assertEquals(enabled, metadata.getBoolean("normalRenderAbort"))
    assertEquals(original.outer.getString("snapshotId"), metadata.getString("currentResidentSnapshotId"))
    assertFalse(metadata.has("viewportReuseDiagnostic")); assertFalse(metadata.has("publicPresetViewport"))
    val dataset = metadata.getJSONObject("dataset")
    assertEquals("CURRENT_RESIDENT_FROZEN_DATA", dataset.getString("scope")); assertTrue(dataset.getBoolean("private"))
    assertFalse(dataset.getBoolean("catalogSwitch")); assertFalse(dataset.getBoolean("geometryReset"))
    assertEquals(original.catalog.generation, dataset.getString("catalogGeneration"))
    assertEquals(original.catalog.sources.size, dataset.getInt("catalogSourceCount"))
    for ((key, value) in listOf("sourceGeneration" to original.source, "surfaceGeneration" to original.surface,
        "contentGeneration" to original.content, "nativeDataGeneration" to original.nativeData,
        "nativeContentRevision" to original.nativeContent)) assertEquals(key, value, metadata.getLong(key))
    val presentation = readCurrentLiveParityProperty(original.identity, "Presentation") as ChartPresentation
    val displayed = metadata.getJSONObject("presentation")
    assertEquals(presentation.styleId, displayed.getString("style"))
    assertEquals(presentation.symbols.id, displayed.getString("symbols")); assertEquals(presentation.landRelief, displayed.getBoolean("relief"))
    val observations = report.getJSONObject("observations")
    assertTrue(observations.getJSONObject("currentResidentFreeze").getBoolean("valid"))
    assertTrue(observations.getJSONObject("currentResidentFreeze").isNull("reason"))
    assertEquals("", observations.getString("completionError"))
    val input = observations.getJSONObject("input")
    assertEquals("no5.renderer-live-input-observations/2", input.getString("schema"))
    assertEquals(owner, input.getString("runId")); assertEquals("CURRENT_RESIDENT_FROZEN_DATA", input.getString("sourceScope"))
    assertEquals(0L, input.getLong("dropped")); assertEquals(0L, input.getLong("unknownTags"))
    assertTrue(input.getBoolean("storageCoverageComplete"))
    val nativeCapture = observations.getJSONObject("nativeCapture")
    assertFalse(nativeCapture.getBoolean("detailed"))
    for (key in listOf("droppedEvents", "droppedMetricValues", "droppedLandJobs", "nativeDroppedLandJobs", "droppedGpuSamples"))
        assertEquals("Raw loss cannot pass: $key", 0L, nativeCapture.getLong(key))
    val restoration = report.getJSONObject("normalRenderAbortRestoration")
    if (enabled) {
        for (state in listOf(metadata.getJSONObject("normalRenderAbortDiagnostic"),
            observations.getJSONObject("normalRenderAbort"), restoration)) {
            assertEquals("no5.normal-render-abort/1", state.getString("schema"))
            assertEquals(owner, state.getString("runId")); assertTrue(state.getBoolean("available"))
            assertFalse(state.getBoolean("defaultEnabled")); assertFalse(state.getBoolean("originalEnabled"))
            assertEquals(2, state.getInt("maxCameraAborts")); assertEquals(64, state.getInt("eventLimit"))
            assertEquals(0L, state.getLong("droppedEvents")); assertTrue(state.getJSONArray("events").length() <= 64)
        }
        assertTrue(metadata.getJSONObject("normalRenderAbortDiagnostic").getBoolean("enabled"))
        assertTrue(observations.getJSONObject("normalRenderAbort").getBoolean("enabled"))
        assertFalse(restoration.getBoolean("active")); assertFalse(restoration.getBoolean("enabled"))
        assertTrue(observations.getJSONObject("normalRenderAbort").getLong("completedCount") > 0)
        // Static parity need not provoke supersession. The separate timed task owns that proof.
    } else {
        assertFalse(metadata.getJSONObject("normalRenderAbortDiagnostic").getBoolean("configured"))
        assertFalse(metadata.getJSONObject("normalRenderAbortDiagnostic").getBoolean("defaultEnabled"))
        assertFalse(restoration.getBoolean("configured")); assertFalse(observations.has("normalRenderAbort"))
    }
    val set = observations.getJSONObject("currentLiveFullParity")
    assertEquals("no5.current-live-full-parity-set/1", set.getString("schema")); assertEquals(owner, set.getString("runId"))
    val captureId = set.getString("captureId"); assertEquals(UUID.fromString(captureId).toString(), captureId)
    assertEquals(enabled, set.getBoolean("normalAbortEnabled"))
    assertEquals(6, set.getInt("requiredFrames")); assertEquals(6, set.getInt("frameCount"))
    assertTrue(set.getBoolean("complete")); assertEquals("", set.getString("error")); assertEquals(5, set.getInt("queriesPerFrame"))
    assertEquals(16 * 1024, set.getInt("maxNativeJsonBytes")); assertEquals(256 * 1024, set.getInt("maxReportBytes"))
    assertTrue(set.getInt("retainedBytes") in 1..(256 * 1024))
    assertTrue(set.toString().toByteArray(Charsets.UTF_8).size <= 256 * 1024)
    assertFalse(set.getBoolean("timingComparable")); assertFalse(set.getBoolean("rawRgbaPersisted"))
    assertEquals("NONE", set.getString("normalization")); assertEquals("NOT_MEASURED", set.getString("physicalInputToDisplay"))
    for (key in listOf("extraDraws", "extraSwaps", "consumerAcknowledgements")) assertEquals(0L, set.getLong(key))
    val recipe = set.getJSONObject("recipe")
    assertEquals("no5.resident-camera-recipe/1", recipe.getString("schema")); assertEquals("CURRENT_VIEW_REUSE", recipe.getString("id"))
    assertEquals(15_000L, recipe.getLong("durationMs")); assertEquals("completed", recipe.getString("phase"))
    assertEquals("", recipe.getString("error")); assertTrue(recipe.getBoolean("comparableRealizedPath"))
    assertEquals("CAMERA_PROPOSALS_NOT_TOUCH_REPLAY", recipe.getString("inputKind"))
    val steps = recipe.getJSONArray("steps"); val results = recipe.getJSONArray("results")
    val frames = set.getJSONArray("frames")
    assertEquals(6, steps.length()); assertEquals(6, results.length()); assertEquals(6, frames.length())
    val signatures = JSONArray(); val unique = mutableSetOf<Pair<Long, Long>>()
    var initialBufferGeneration: Long? = null
    for (index in 0 until 6) {
        val frame = frames.getJSONObject(index); val step = steps.getJSONObject(index); val result = results.getJSONObject(index)
        assertEquals(index, frame.getInt("stepIndex")); assertEquals(index, step.getInt("index")); assertEquals(index, result.getInt("stepIndex"))
        assertEquals(index * 2_800L, step.getLong("offsetMs")); assertTrue(frame.getBoolean("sameAuthority"))
        assertEquals(step.getLong("offsetMs"), result.getLong("plannedOffsetMs"))
        assertEquals(step.getString("windowId"), result.getString("windowId"))
        assertTrue(result.getBoolean("sameRealizedPose")); assertTrue(result.getBoolean("sameAuthority"))
        assertTrue(result.getJSONObject("result").getBoolean("accepted"))
        val before = frame.getJSONObject("authorityBefore"); val after = frame.getJSONObject("authorityAfter")
        assertEquals("Exact completed NativeFrame+registry authority before/after read", before.toString(), after.toString())
        assertEquals(owner, before.getString("runId")); assertEquals(captureId, before.getString("captureId"))
        assertEquals(original.catalog.generation, before.getString("catalogGeneration"))
        assertEquals(original.source, before.getLong("sourceGeneration")); assertEquals(original.surface, before.getLong("surfaceGeneration"))
        assertEquals(original.content, before.getLong("contentGeneration"))
        assertEquals(original.nativeData, before.getLong("nativeDataGeneration"))
        assertEquals(original.nativeContent, before.getLong("nativeContentRevision"))
        assertEquals(original.presentationGeneration, before.getLong("presentationGeneration"))
        assertEquals(enabled, before.getBoolean("normalAbortEnabled")); assertEquals("FULL", before.getString("contentStage"))
        assertTrue(before.getBoolean("producerCompleted")); assertTrue(before.getLong("fullBufferStamp") > 0)
        assertTrue(before.getLong("nativeFrame") > 0); assertTrue(before.getLong("bufferGeneration") > 0)
        assertTrue(before.getLong("cameraRevision") >= 0); assertTrue(before.getInt("presentationSlot") in 0..1)
        assertEquals(OWNER_DOMAIN_JOIN, before.getString("ownerDomainJoin"))
        val receipt = result.getJSONObject("result").getJSONObject("authority")
        assertEquals(receipt.getLong("cameraRevision"), before.getLong("cameraRevision"))
        val viewport = before.getJSONObject("viewport")
        assertEquals(step.getJSONObject("viewport").toString(), viewport.toString())
        assertEquals(result.getJSONObject("wantedViewport").toString(), viewport.toString())
        if (index == 0 || index == 5) {
            val savedView = original.outer.getJSONObject("state").getJSONObject("viewport")
            for (key in listOf("latitude", "longitude", "metresToPixels", "rotationRadians", "width", "height"))
                assertEquals("Initial and warm-return poses remain the actual saved camera", savedView.getDouble(key), viewport.getDouble(key), 0.0)
        }
        val native = frame.getJSONObject("native")
        assertTrue(native.toString().toByteArray(Charsets.UTF_8).size <= 16 * 1024)
        assertEquals("no5.current-live-full-parity/1", native.getString("schema"))
        assertEquals(owner, native.getString("runId")); assertEquals(captureId, native.getString("captureId"))
        assertEquals(enabled, native.getBoolean("normalAbortEnabled")); assertEquals(OWNER_DOMAIN_JOIN, native.getString("ownerDomainJoin"))
        for ((nativeKey, ownerKey) in listOf("frame" to "nativeFrame", "fullBufferStamp" to "fullBufferStamp",
            "ownerCameraRevision" to "cameraRevision", "bufferGeneration" to "bufferGeneration",
            "ownerSourceGeneration" to "sourceGeneration", "ownerSurfaceGeneration" to "surfaceGeneration",
            "dataGeneration" to "nativeDataGeneration", "contentRevision" to "nativeContentRevision",
            "presentationGeneration" to "presentationGeneration", "presentationSlot" to "presentationSlot"))
            assertEquals(nativeKey, before.getLong(ownerKey), native.getLong(nativeKey))
        assertEquals(original.catalog.generation, native.getString("catalogGeneration"))
        assertTrue(unique.add(native.getLong("fullBufferStamp") to native.getLong("frame")))
        assertEquals(index, native.getInt("captureFrameIndex")); assertEquals(6, native.getInt("captureFrameLimit"))
        assertEquals(16 * 1024, native.getInt("captureFrameByteLimit")); assertEquals(256 * 1024, native.getInt("captureSetByteLimit"))
        val initial = native.getLong("initialOwnerBufferGeneration"); assertTrue(initial > 0)
        if (initialBufferGeneration == null) initialBufferGeneration = initial else assertEquals(initialBufferGeneration, initial)
        if (enabled) {
            val applied = native.getJSONObject("nativeAppliedAuthority")
            assertTrue(before.getLong("appliedAuthorityEpoch") > 0)
            assertEquals(before.getLong("appliedAuthorityEpoch"), applied.getLong("epoch"))
            for ((key, ownerKey) in listOf("cameraRevision" to "cameraRevision", "sourceGeneration" to "sourceGeneration",
                "surfaceGeneration" to "surfaceGeneration")) assertEquals(before.getLong(ownerKey), applied.getLong(key))
        } else {
            assertTrue(before.isNull("appliedAuthorityEpoch")); assertTrue(native.isNull("nativeAppliedAuthority"))
        }
        signatures.put(currentLiveNativeContentSignature(native, viewport))
    }
    return JSONObject().put("catalogGeneration", original.catalog.generation)
        .put("sourceGeneration", original.source).put("nativeDataGeneration", original.nativeData)
        .put("nativeContentRevision", original.nativeContent).put("loadedCellCount", original.state.loadedChartCells.size)
        .put("recipeSha256", recipe.getString("sha256")).put("frames", signatures)
}

/** Existing native scalar/query oracle fields, in their original order; cache/timing counters excluded. */
private fun currentLiveNativeContentSignature(native: JSONObject, viewport: JSONObject): JSONObject {
    for ((key, nativeKey) in listOf("latitude" to "latitude", "longitude" to "longitude", "metresToPixels" to "ppm",
        "rotationRadians" to "rotation", "width" to "width", "height" to "height"))
        assertEquals(viewport.getDouble(key), native.getDouble(nativeKey), 0.0)
    val width = native.getLong("width"); val height = native.getLong("height")
    assertTrue(width in 1..MAX_CHART_WIDTH.toLong() && height in 1..MAX_CHART_HEIGHT.toLong())
    assertEquals(width * height * 4, native.getLong("rgbaBytes")); assertEquals("NONE", native.getString("normalization"))
    for (key in listOf("extraDraws", "extraSwaps", "consumerAcknowledgements")) assertEquals(0L, native.getLong(key))
    assertEquals("completed native output FBO, full 1:1 RGBA; no post-swap window read", native.getString("pixelSource"))
    val signature = JSONObject().put("viewport", viewport).put("rgbaBytes", native.getLong("rgbaBytes"))
        .put("featureCount", native.getLong("featureCount"))
    for (key in listOf("rgbaSHA256", "semanticSHA256", "associationsSHA256")) {
        assertTrue(native.getString(key).matches(Regex("[a-f0-9]{64}"))); signature.put(key, native.getString(key))
    }
    assertEquals("viewport-center-five/1: x=w/2+(i-2)*w/8, y=h/2+(i%2)*h/8, radius=24, offset=0", native.getString("queryRecipe"))
    val queries = native.getJSONArray("queries"); assertEquals(5, queries.length())
    for (i in 0 until 5) {
        val query = queries.getJSONObject(i)
        assertEquals(width * .5 + (i - 2) * width * .125, query.getDouble("x"), 0.0)
        assertEquals(height * .5 + (i % 2) * height * .125, query.getDouble("y"), 0.0)
        assertEquals(24, query.getInt("radius")); assertEquals(0, query.getInt("offset"))
        assertTrue(query.getInt("jsonBytes") in 0..(256 * 1024)); assertTrue(query.getString("sha256").matches(Regex("[a-f0-9]{64}")))
    }
    val status = native.getJSONObject("status")
    assertEquals(native.getLong("dataGeneration"), status.getLong("dataGeneration"))
    for (key in listOf("latitude", "longitude", "ppm", "rotation", "width", "height"))
        assertEquals(native.getDouble(key), status.getDouble(key), 0.0)
    val oracle = JSONObject()
    for (key in listOf("filteredFeatures", "viewportSelectedCharts", "coverageUnknownCells", "coverageUncoveredPixels",
        "selectedCharts", "featureCount", "unsupportedFeatures", "coverageMasked", "objectSelection")) oracle.put(key, status.get(key))
    return signature.put("orderedQueries", queries).put("unchangedStatusOracle", oracle)
}

private const val OWNER_DOMAIN_JOIN =
    "Kotlin exact completed FULL registry reference before/after; registry/camera/source/surface are not nativeFrame domains"
