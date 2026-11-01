package com.no5.helm.ui

import android.util.AtomicFile
import com.no5.preparation.ui.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class LocalRouteStore(val ready: Boolean = false, val routes: FrozenList<RouteDocument> = frozenListOf(),
    val recoveryDraft: RouteDocument? = null, val issue: String? = null)

/** Separate from chart data and the read-only Helm projection. Acknowledgement follows finishWrite. */
class LocalRouteRepository(directory: File, private val beforeCommit: (() -> Unit)? = null) {
    private val file = AtomicFile(File(directory, "store-v1.json"))
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(LocalRouteStore())
    val state = mutable.asStateFlow()
    suspend fun prepare() = withContext(Dispatchers.IO) { mutex.withLock {
        if (mutable.value.ready) return@withLock
        try {
            val value = if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) LocalRouteStore(ready = true)
                else file.openRead().use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val read = input.read(chunk); if (read < 0) break
                        require(buffer.size() + read <= 4 * 1024 * 1024) { "Routenbestand zu groß" }
                        buffer.write(chunk, 0, read)
                    }
                    val bytes = buffer.toByteArray()
                    require(bytes.size <= 4 * 1024 * 1024) { "Routenbestand zu groß" }
                    val json = JSONObject(bytes.toString(Charsets.UTF_8))
                    require(json.getInt("schema") == 1) { "Unbekannte Version des Routenbestands" }
                    val entries = json.getJSONArray("routes")
                    require(entries.length() <= 512)
                    val routes = (0 until entries.length()).map { decode(entries.getJSONObject(it), saved = true) }
                    require(routes.map { it.source to it.id }.distinct().size == routes.size)
                    LocalRouteStore(true, FrozenList.copyOf(routes), json.optJSONObject("draft")?.let { decode(it, false) })
                }
            mutable.value = value
        } catch (failure: Exception) { mutable.value = LocalRouteStore(issue = "Routenbestand bleibt erhalten: ${failure.message}") }
    } }
    suspend fun save(request: RouteSaveRequest): RouteDocument = withContext(Dispatchers.IO) { mutex.withLock {
        val current = writable()
        val value = request.snapshot
        validate(value, true)
        val old = current.routes.find { it.id == value.id && it.source == value.source }
        if (old == value) return@withLock value // Retry after a durable commit.
        if (request.remoteConfirmed) {
            require(!value.source.isNullOrBlank() && !value.signalKToken.isNullOrBlank()) { "Signal-K-Bestätigung fehlt" }
            // A failed offline commit may leave this cache several confirmed
            // remote revisions behind. Its owner is Signal K, not this file.
            require(value.revision > (old?.revision ?: 0)) { "Routenrevision hat sich geändert" }
        } else require(value.revision == (old?.revision ?: 0) + 1) { "Routenrevision hat sich geändert" }
        val next = current.copy(routes = FrozenList.copyOf(current.routes.filterNot { it.id == value.id && it.source == value.source } + value),
            recoveryDraft = current.recoveryDraft?.takeUnless { it.id == value.id })
        require(next.routes.size <= 512) { "Routenbestand voll" }
        commit(next); value
    } }
    suspend fun cache(origin: String, routes: List<RouteDocument>) = withContext(Dispatchers.IO) { mutex.withLock {
        val current = writable(); routes.forEach { validate(it, true); require(it.source == origin && it.signalKToken != null) }
        val next = current.copy(routes = FrozenList.copyOf(current.routes.filterNot { it.source == origin } + routes))
        require(next.routes.size <= 512)
        if (next != current) commit(next)
    } }
    suspend fun draft(value: RouteDocument?) = withContext(Dispatchers.IO) { mutex.withLock {
        val current = writable(); value?.let { validate(it, false) }
        if (current.recoveryDraft != value) commit(current.copy(recoveryDraft = value))
    } }
    suspend fun delete(id: String, revision: Long, source: String? = null) = withContext(Dispatchers.IO) { mutex.withLock {
        val current = writable(); val old = current.routes.find { it.id == id && it.source == source } ?: return@withLock
        require(old.revision == revision) { "Routenrevision hat sich geändert" }
        commit(current.copy(routes = FrozenList.copyOf(current.routes.filterNot { it.id == id && it.source == source }),
            recoveryDraft = current.recoveryDraft?.takeUnless { it.id == id }))
    } }
    private fun writable() = mutable.value.also { check(it.ready && it.issue == null) { it.issue ?: "Routenbestand wird geladen" } }
    private fun commit(value: LocalRouteStore) {
        val json = JSONObject().put("schema", 1).put("routes", JSONArray(value.routes.map(::encode)))
            .put("draft", value.recoveryDraft?.let(::encode) ?: JSONObject.NULL)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4 * 1024 * 1024) { "Routenbestand zu groß" }
        val stream = file.startWrite()
        try { stream.write(bytes); beforeCommit?.invoke(); stream.fd.sync(); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
        mutable.value = value
    }
    private fun validate(value: RouteDocument, saved: Boolean) {
        require(value.id.startsWith("local-") && value.id.length <= 100 && !value.external) { "Keine lokale Route" }
        require(value.name.length <= 200 && value.revision >= 0 && value.points.size <= 5000)
        require(value.points.all { it.valid && it.latitude in -85.0..85.0 && it.id.isNotBlank() && it.id.length <= 150 && it.name.length <= 200 })
        require(value.points.map { it.id }.distinct().size == value.points.size)
        if (saved) require(value.name.isNotBlank() && value.points.size >= 2 && value.points.zipWithNext().none { (a, b) ->
            a.latitude == b.latitude && (a.longitude == b.longitude || kotlin.math.abs(a.longitude - b.longitude) == 360.0)
        }) { "Name und mindestens zwei unterschiedliche Wegpunkte erforderlich" }
        if (saved) require(!value.offlineCopyPending) { "Offline-Kopie noch nicht bestätigt" }
    }
    private fun encode(value: RouteDocument) = JSONObject().put("id", value.id).put("name", value.name).put("revision", value.revision)
        .put("source", value.source ?: JSONObject.NULL).put("signalKToken", value.signalKToken ?: JSONObject.NULL).put("signalKResource", value.signalKResource ?: JSONObject.NULL)
        .put("offlineCopyPending", value.offlineCopyPending)
        .put("points", JSONArray(value.points.map { JSONObject().put("id", it.id).put("name", it.name)
            .put("lat", it.latitude).put("lon", it.longitude) }))
    private fun decode(json: JSONObject, saved: Boolean): RouteDocument {
        val points = json.getJSONArray("points"); require(points.length() <= 5000)
        return RouteDocument(json.getString("id"), json.getString("name"), FrozenList.copyOf((0 until points.length()).map {
            val point = points.getJSONObject(it); RoutePoint(point.getDouble("lat"), point.getDouble("lon"), point.getString("id"), point.getString("name"))
        }), json.getLong("revision"), source = json.optString("source").takeUnless { it.isBlank() || it == "null" },
            signalKToken = json.optString("signalKToken").takeUnless { it.isBlank() || it == "null" }, signalKResource = json.optString("signalKResource").takeUnless { it.isBlank() || it == "null" },
            offlineCopyPending = json.optBoolean("offlineCopyPending", false)).also { validate(it, saved) }
    }
}
