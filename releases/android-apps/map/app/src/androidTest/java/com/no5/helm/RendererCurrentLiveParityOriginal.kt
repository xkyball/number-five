package com.no5.helm

import com.no5.helm.chart.*
import com.no5.helm.model.MapPositionController
import com.no5.helm.ui.rendererDiagnosticCameraStartBlocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Test-only exact current-owner original. No preparation, replacement source, retry or raw JNI read. */
internal class RendererCurrentLiveParityOriginal private constructor(
    val outer: JSONObject,
    val state: ChartEngineStatus,
    val identity: Any,
    val catalog: ChartResidentCatalog,
    val source: Long,
    val surface: Long,
    val content: Long,
    val nativeData: Long,
    val nativeContent: Long,
    val presentationGeneration: Long,
) {
    fun pins(): JSONObject = JSONObject().put("snapshotId", outer.getString("snapshotId"))
        .put("rendererSession", readCurrentLiveParityProperty(identity, "RendererSession"))
        .put("catalogGeneration", catalog.generation).put("catalogSourceCount", catalog.sources.size)
        .put("loadedCellCount", state.loadedChartCells.size).put("sourceGeneration", source)
        .put("surfaceGeneration", surface).put("contentGeneration", content)
        .put("nativeDataGeneration", nativeData).put("nativeContentRevision", nativeContent)
        .put("presentationGeneration", presentationGeneration)
        .put("oracle", "SINGLE_TYPED_OWNER_SNAPSHOT_THEN_EXACT_STATUS_CAS; no preparation or retry")

    fun savedState(): String = JSONObject().also { saved ->
        val values = outer.getJSONObject("state")
        for (key in listOf("chartId", "paths", "viewport", "presentation", "settings", "overlays", "bufferTransformMode",
            "residentSourceGeneration", "residentPolicyKey", "residentActivation")) saved.put(key, values.get(key))
    }.toString()

    companion object {
        suspend fun capture(activity: MainActivity, engine: ChartEngine): RendererCurrentLiveParityOriginal {
            val bound = rendererSnapshotBoundOriginal(snapshot = {
                val outer = JSONObject(invokeCurrentLiveParityHost<String>(activity, "snapshotOuterState", UUID.randomUUID().toString()))
                check(outer.getString("schema") == "no5-capacity-outer-state/1" &&
                    outer.getString("scope") == "CURRENT_ENTRY_CATALOG_ONLY")
                val slot = engine.javaClass.getDeclaredField("currentResidentLiveSnapshots").apply { isAccessible = true }.get(engine)
                val getter = slot.javaClass.declaredMethods.single {
                    (it.name == "get" || it.name.startsWith("get\$")) && it.parameterTypes.contentEquals(arrayOf(String::class.java))
                }.apply { isAccessible = true }
                val id = outer.getString("snapshotId")
                val typed = checkNotNull(getter.invoke(slot, id)) { "UNAVAILABLE_TYPED_CURRENT_RESIDENT_SNAPSHOT" }
                check(readCurrentLiveParityProperty(typed, "Id") == id)
                val identity = readCurrentLiveParityProperty(typed, "Identity")
                fun field(name: String) = readCurrentLiveParityProperty(identity, name)
                val catalog = field("Catalog") as ChartResidentCatalog
                val source = field("Source") as Long; val surface = field("Surface") as Long
                val content = field("Content") as Long; val nativeData = field("NativeData") as Long
                val nativeContent = field("NativeContent") as Long
                val presentation = field("PresentationGeneration") as Long
                val full = readCurrentLiveParityProperty(typed, "Full") as ChartBufferSnapshot
                val authority = outer.getJSONObject("authority"); val saved = outer.getJSONObject("state")
                val residency = outer.getJSONObject("residency"); val producer = outer.getJSONObject("fullProducerBuffer")
                check(catalog.sources.isNotEmpty() && catalog.generation == authority.getString("catalogGeneration") &&
                    catalog.generation == saved.getJSONObject("residentActivation").getJSONObject("catalog").getString("generation"))
                check(outer.getString("rendererSession") == field("RendererSession") &&
                    source == saved.getLong("residentSourceGeneration") && source == authority.getLong("sourceGeneration") &&
                    surface == authority.getLong("surfaceGeneration") && content == authority.getLong("contentGeneration") &&
                    presentation == authority.getLong("presentationGeneration") && nativeContent == residency.getLong("contentRevision"))
                check(saved.getString("residentPolicyKey") == field("PolicyKey") && residency.getString("policyKey") == field("PolicyKey"))
                check(listOf(source, surface, content, nativeData, nativeContent, presentation).all { it >= 0 })
                check(readCurrentLiveParityProperty(typed, "Geometry") == full.frame.geometry &&
                    readCurrentLiveParityProperty(typed, "CameraRevision") == full.cameraRevision &&
                    producer.getLong("stamp") == full.stamp && producer.getLong("cameraRevision") == full.cameraRevision &&
                    producer.getLong("sourceGeneration") == full.frame.sourceGeneration &&
                    producer.getLong("contentGeneration") == full.frame.contentGeneration && producer.getBoolean("producerCompleted"))
                val keys = residency.getJSONArray("sources")
                val pins = RendererOriginalSnapshotPins(field("RendererSession") as String, catalog.generation, source, content,
                    surface, presentation, field("Presentation") as ChartPresentation,
                    (field("Settings") as S52Settings).objectFilters, full,
                    rendererSnapshotLoadedNames(catalog, List(keys.length()) { keys.getString(it) }))
                Captured(outer, identity, catalog, source, surface, content, nativeData, nativeContent, presentation) to pins
            }, status = { engine.status.value })
            val capture = bound.snapshot
            return RendererCurrentLiveParityOriginal(capture.outer, bound.state, capture.identity, capture.catalog,
                capture.source, capture.surface, capture.content, capture.nativeData, capture.nativeContent, capture.presentation)
        }
        private data class Captured(val outer: JSONObject, val identity: Any, val catalog: ChartResidentCatalog,
            val source: Long, val surface: Long, val content: Long, val nativeData: Long, val nativeContent: Long,
            val presentation: Long)
    }
}

internal fun readCurrentLiveParityProperty(target: Any, name: String): Any = checkNotNull(target.javaClass.declaredMethods.single {
    (it.name == "get$name" || it.name.startsWith("get$name\$")) && it.parameterCount == 0
}.apply { isAccessible = true }.invoke(target)) { "UNAVAILABLE_REQUIRED_PROPERTY_$name" }

internal fun currentLiveParityPositions(activity: MainActivity): MapPositionController =
    readCurrentLiveParityProperty(readCurrentLiveParityProperty(activity, "Runtime"), "Positions") as MapPositionController

internal suspend fun requireCurrentLiveParityHostReady(activity: MainActivity) = withContext(Dispatchers.Main.immediate) {
    check(!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()) { "UNAVAILABLE_FOREGROUND_WINDOW" }
    val positions = currentLiveParityPositions(activity)
    val charts = MainActivity::class.java.getDeclaredField("charts").apply { isAccessible = true }
        .get(activity) as? PersistentChartController
    check(rendererDiagnosticCameraStartBlocker(positions.state.value, charts == null || charts.state.value.busy) == null) {
        "UNAVAILABLE_HOST_CAMERA_OWNER"
    }
}

/** The real Activity Host, resolved by actual suspend shape, not a raw engine/JNI bypass. */
internal suspend fun <T> invokeCurrentLiveParityHost(activity: MainActivity, name: String, argument: Any): T =
    withContext(Dispatchers.Main.immediate) {
        val method = MainActivity::class.java.declaredMethods.single {
            (it.name == name || it.name.startsWith("$name\$")) && it.parameterCount == 2 &&
                it.parameterTypes[0].isInstance(argument) && it.parameterTypes[1] == Continuation::class.java
        }.apply { isAccessible = true }
        suspendCoroutine { continuation: Continuation<T> ->
            try {
                val value = method.invoke(activity, argument, continuation)
                @Suppress("UNCHECKED_CAST")
                if (value !== COROUTINE_SUSPENDED) continuation.resume(value as T)
            } catch (failure: InvocationTargetException) { continuation.resumeWithException(failure.targetException) }
            catch (failure: Throwable) { continuation.resumeWithException(failure) }
        }
    }
