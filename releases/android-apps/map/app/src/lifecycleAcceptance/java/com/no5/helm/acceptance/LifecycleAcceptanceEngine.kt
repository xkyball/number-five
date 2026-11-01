package com.no5.helm.acceptance

import android.os.Process
import android.os.SystemClock
import com.no5.helm.chart.*
import com.no5.helm.library.ChartLibrary
import com.no5.helm.library.ChartSelection
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

enum class LifecycleCrashPoint { SOURCE_COMMITTED_BEFORE_SELECTION, SELECTION_PUBLISHED_RELEASE_ACKED_BEFORE_FILES }
data class LifecycleBarrierEvent(val token: String, val point: LifecycleCrashPoint, val file: File)

/** Real JNI delegate. Only return delivery is paused; no result, source, status or release ACK is fabricated. */
class LifecycleAcceptanceEngine(private val actual: ChartEngine, private val context: LifecycleAcceptanceContext) : ChartEngine by actual {
    private class Arm(val token: String, val operationId: String, val point: LifecycleCrashPoint,
        val targets: List<LifecycleExpectedSource>, val previousSelection: ChartSelection?, val previousDurable: String) {
        var catalog: String? = null
        var source: Long? = null
        var reached = false
        val resume = CompletableDeferred<Unit>()
    }
    private var armed: Arm? = null
    private var closed = false
    private val mutableBarrier = MutableStateFlow<LifecycleBarrierEvent?>(null)
    val barrier = mutableBarrier.asStateFlow()
    private val library get() = ChartLibrary(context.libraryRoot)

    @Synchronized fun arm(point: LifecycleCrashPoint, targets: List<LifecycleExpectedSource>): String {
        check(!closed && armed == null && mutableBarrier.value == null) { "Acceptance barrier already owned" }
        require(targets.map { it.name }.distinct().size == targets.size)
        val token = UUID.randomUUID().toString()
        val previous = library.snapshot().active
        armed = Arm(token, UUID.randomUUID().toString(), point,
            targets.map { it.copy(hashes = it.hashes.toMap()) }, previous?.copy(cells = previous.cells.toList()), durableDigest())
        return token
    }
    @Synchronized fun continueAfterObservation(token: String) {
        val arm = checkNotNull(armed)
        require(arm.token == token && arm.reached && mutableBarrier.value?.token == token) { "Stale acceptance barrier" }
        arm.resume.complete(Unit)
    }
    @Synchronized fun abortBarrier() {
        armed?.resume?.completeExceptionally(IOException("Acceptance barrier closed"))
        armed = null; mutableBarrier.value = null
    }
    private fun matches(arm: Arm, catalog: ChartResidentCatalog): Boolean =
        catalog.sources.size == arm.targets.size && arm.targets.all { expected ->
            catalog.sources.singleOrNull { it.name == expected.name }?.let(expected::matches) == true
        }
    override suspend fun activateResidentCatalog(activation: ChartResidentActivation): ChartResidentUpdate {
        val before = actual.status.value.chartSourceGeneration
        val result = actual.activateResidentCatalog(activation)
        val arm = synchronized(this) { armed }
        if (arm == null || !result.result.success || !matches(arm, activation.catalog)) return result
        check(!closed && actual.status.value.chartSourceGeneration > before) { "Native source commit not observed" }
        check(actual.status.value.residentCatalogGeneration == activation.catalog.generation) { "Native target catalog not committed" }
        activation.catalog.sources.flatMap { it.files }.forEach { context.requireOwned(File(it.path)) }
        synchronized(this) {
            check(armed === arm); arm.catalog = activation.catalog.generation; arm.source = actual.status.value.chartSourceGeneration
        }
        if (arm.point == LifecycleCrashPoint.SOURCE_COMMITTED_BEFORE_SELECTION) {
            check(library.snapshot().active == arm.previousSelection && durableDigest() == arm.previousDurable) { "Library already published before K1" }
            pause(arm, "activateResidentCatalog", result.result)
        }
        return result
    }
    override suspend fun releaseRetiredChartSources(expectedCatalogGeneration: String): EngineResult {
        val result = actual.releaseRetiredChartSources(expectedCatalogGeneration)
        val arm = synchronized(this) { armed }
        if (arm == null || arm.point != LifecycleCrashPoint.SELECTION_PUBLISHED_RELEASE_ACKED_BEFORE_FILES ||
            arm.catalog != expectedCatalogGeneration) return result
        // False is returned unchanged and can never authorize either the barrier or physical IO cleanup.
        if (!result.success) { writeEvent(arm, "RELEASE_DEFERRED", "releaseRetiredChartSources", result); return result }
        check(actual.status.value.chartSourceGeneration == arm.source &&
            actual.status.value.residentCatalogGeneration == expectedCatalogGeneration) { "Stale native release ACK" }
        val snapshot = library.snapshot()
        check(snapshot.pendingCleanup && snapshot.active != arm.previousSelection && durableDigest() != arm.previousDurable) { "Library publication before K2 not observed" }
        check(snapshot.active?.cells?.toSet().orEmpty() == arm.targets.map { it.name }.toSet()) { "Published test selection disagrees with target" }
        pause(arm, "releaseRetiredChartSources", result)
        return result
    }
    private suspend fun pause(arm: Arm, method: String, result: EngineResult) {
        synchronized(this) { check(!closed && armed === arm && !arm.reached); arm.reached = true }
        val file = writeEvent(arm, arm.point.name, method, result)
        mutableBarrier.value = LifecycleBarrierEvent(arm.token, arm.point, file)
        try {
            // No timeout auto-commit. Failure travels through the genuine Controller transaction.
            withTimeout(30_000) { arm.resume.await() }
        } finally {
            synchronized(this) { if (armed === arm) armed = null }
            mutableBarrier.value = null
        }
    }
    private fun durableDigest(): String = listOf("lifecycle-state-v1.json", "active.json").joinToString(":") { name ->
        val file = context.requireOwned(File(context.libraryRoot, name))
        "$name=" + if (file.isFile) ChartLibrary.sha256(file) else "ABSENT"
    }
    private suspend fun writeEvent(arm: Arm, phase: String, method: String, result: EngineResult): File {
        val directory = context.requireOwned(File(context.filesDir, "lifecycle-events")).apply { require(mkdirs() || isDirectory) }
        val file = File(directory, "${arm.token}-$phase.json")
        val binding = checkNotNull(actual.readResidentBinding()) { "Actual native binding unavailable at barrier" }
        check(binding.activation.catalog.generation == arm.catalog && binding.sourceGeneration == arm.source)
        check(matches(arm, binding.activation.catalog))
        binding.activation.catalog.sources.flatMap { it.files }.forEach { context.requireOwned(File(it.path)) }
        val data = snapshotJson().put("schema", "no5-lifecycle-acceptance-event/1")
            .put("operationId", arm.operationId).put("barrierToken", arm.token).put("phase", phase)
            .put("nativeMethod", method).put("actualNativeResult", JSONObject().put("success", result.success).put("message", result.message))
            .put("expectedCatalog", arm.catalog).put("expectedSourceGeneration", arm.source)
            .put("actualNativeSources", nativeSources(binding))
            .put("previousSelection", arm.previousSelection?.let { JSONObject().put("packageId", it.packageId)
                .put("cells", JSONArray(it.cells)) } ?: JSONObject.NULL)
            .put("previousDurableDigest", arm.previousDurable).put("durableDigest", durableDigest())
            .put("syntheticWait", phase != "RELEASE_DEFERRED").put("physicalDisplayLatencyMeasured", false)
        return publishEvidence(file, data)
    }
    /** Read-only evidence after prepare/terminal state; a null binding stays null, never inferred. */
    suspend fun recordSnapshot(label: String): File {
        require(label.matches(Regex("[a-z0-9-]{1,80}")))
        val binding = actual.readResidentBinding()
        binding?.activation?.catalog?.sources?.flatMap { it.files }?.forEach { context.requireOwned(File(it.path)) }
        val directory = context.requireOwned(File(context.filesDir, "lifecycle-events")).apply { require(mkdirs() || isDirectory) }
        val data = snapshotJson().put("schema", "no5-lifecycle-acceptance-snapshot/1").put("label", label)
            .put("actualNativeSources", binding?.let(::nativeSources) ?: JSONObject.NULL)
            .put("actualNativeCatalogGeneration", binding?.activation?.catalog?.generation ?: JSONObject.NULL)
            .put("physicalDisplayLatencyMeasured", false)
        return publishEvidence(File(directory, "$label-${Process.myPid()}-${UUID.randomUUID()}.json"), data)
    }
    private fun nativeSources(binding: ChartResidentBinding) = JSONArray(binding.activation.catalog.sources.map { source -> JSONObject()
        .put("name", source.name).put("provider", source.provider).put("sourceKey", source.sourceKey)
        .put("edition", source.edition).put("update", source.update).put("order", source.order)
        .put("files", JSONArray(source.files.map { pin -> JSONObject().put("path", pin.path).put("sha256", pin.sha256) })) })
    private fun publishEvidence(file: File, data: JSONObject): File {
        context.requireOwned(file)
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(temp).use { it.write(data.toString(2).toByteArray()); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        return file
    }
    fun snapshotJson(): JSONObject {
        val status = actual.status.value
        val snapshot = library.snapshot()
        val packages = JSONArray(snapshot.packages.map { pack -> JSONObject().put("id", pack.id).put("label", pack.label)
            .put("cells", JSONArray(pack.cells.map { cell -> JSONObject().put("name", cell.name).put("provider", cell.provider)
                .put("edition", cell.edition).put("update", cell.update).put("files", JSONObject(cell.files)) })) })
        val selection = snapshot.active?.let { JSONObject().put("packageId", it.packageId).put("cells", JSONArray(it.cells)) }
        val release = snapshot.activeRelease?.let { JSONObject().put("packageId", it.packageId)
            .put("cells", JSONArray(it.cells.map { cell -> JSONObject().put("name", cell.name).put("sourceKey", cell.sourceKey) })) }
        val durableFile = File(context.libraryRoot, "lifecycle-state-v1.json")
        val durable = if (durableFile.isFile) JSONObject(durableFile.readText()) else null
        val files = JSONObject()
        if (context.libraryRoot.isDirectory) context.libraryRoot.walkTopDown().filter { it.isFile }.forEach {
            context.requireOwned(it); files.put(it.relativeTo(context.libraryRoot).invariantSeparatorsPath, ChartLibrary.sha256(it))
        }
        val frame = status.confirmedFrame
        return JSONObject().put("applicationId", context.packageName).put("runId", context.runId)
            .put("root", context.runRoot.canonicalPath).put("pid", Process.myPid()).put("uid", Process.myUid())
            .put("elapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos()).put("sourceCommit", BuildConfigSource.commit)
            .put("selection", selection ?: JSONObject.NULL).put("release", release ?: JSONObject.NULL)
            .put("selectionVersion", snapshot.selectionVersion).put("pendingCleanup", snapshot.pendingCleanup)
            .put("libraryRevision", durable?.optLong("revision")).put("durableState", durable ?: JSONObject.NULL)
            .put("packages", packages).put("libraryFiles", files).put("chartLoaded", status.chartLoaded)
            .put("catalogGeneration", status.residentCatalogGeneration).put("sourceGeneration", status.chartSourceGeneration)
            .put("contentGeneration", status.chartContentGeneration).put("surfaceGeneration", status.surfaceGeneration)
            .put("viewport", JSONObject().put("latitude", status.viewport.latitude).put("longitude", status.viewport.longitude)
                .put("ppm", status.viewport.metresToPixels).put("rotation", status.viewport.rotationRadians)
                .put("width", status.viewport.width).put("height", status.viewport.height))
            .put("presentation", status.presentation.toString()).put("bufferTransformMode", status.bufferTransformMode.name)
            .put("confirmedFrame", frame?.let { JSONObject().put("revision", it.revision).put("sourceGeneration", it.sourceGeneration)
                .put("contentGeneration", it.contentGeneration).put("cameraToken", it.cameraToken) } ?: JSONObject.NULL)
    }
    override fun close() {
        synchronized(this) { closed = true }
        abortBarrier(); actual.close()
    }
    private object BuildConfigSource { val commit = com.no5.helm.app.BuildConfig.SOURCE_COMMIT }
}
