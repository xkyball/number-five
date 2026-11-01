package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

internal data class BenchmarkUserState(val runId: String, val chartId: String?, val paths: List<String>,
    val viewport: ChartViewport, val presentation: ChartPresentation, val settings: S52Settings, val overlays: ChartOverlays,
    val residentActivation: ChartResidentActivation? = null, val residentSourceGeneration: Long? = null,
    val residentPolicyKey: String? = null, val bufferTransformMode: ChartBufferTransformMode = ChartBufferTransformMode.OFF,
    val currentViewOnly: Boolean = false, val backgroundPaths: List<String> = emptyList())

/** Applying a presentation changes the native palette without going through setS52Settings. */
internal fun benchmarkUserSettings(requested: S52Settings, nativeStatus: JsonObject): S52Settings {
    val palette = nativeStatus.getValue("presentation").jsonObject.getValue("mode").jsonPrimitive.content
    val actual = ChartPalette.entries.firstOrNull { it.name == palette }
    checkNotNull(actual) { "Aktuelle S52-Darstellung konnte nicht gesichert werden. Test erneut versuchen." }
    return requested.copy(palette = actual)
}

/** Private recovery journal, never included in a benchmark/export. Library/preferences stay unchanged. */
internal class BenchmarkRecoveryStore(private val file: File) {
    /** Private DUMP-only host reserve: validate the same representation used by recovery. */
    fun snapshot(state: BenchmarkUserState): JsonObject = encode(state).also {
        decode(it)
        check(it.toString().toByteArray(Charsets.UTF_8).size <= MAX_JOURNAL_BYTES)
    }
    fun save(state: BenchmarkUserState) {
        check(!file.exists()) { "Unterbrochene Messung zuerst wiederherstellen" }
        val encoded = encode(state)
        // Never publish a journal that this version cannot validate and restore.
        decode(encoded)
        val bytes = encoded.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_JOURNAL_BYTES) { "Wiederherstellungszustand zu groß; Nutzeransicht bleibt erhalten" }
        file.parentFile!!.mkdirs()
        val temp = File(file.path + ".tmp")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { temp.delete() }
    }
    fun read(): BenchmarkUserState? = if (!file.exists()) null else try {
        check(file.length() <= MAX_JOURNAL_BYTES)
        val output = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val chunk = ByteArray(8_192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                check(output.size() <= MAX_JOURNAL_BYTES - count)
                output.write(chunk, 0, count)
            }
        }
        decode(Json.parseToJsonElement(output.toString(Charsets.UTF_8.name())).jsonObject)
    }
        catch (failure: Exception) { throw IllegalStateException("Gesicherte Ansicht nicht lesbar. Nutzerkarten bleiben im Kartenbestand erhalten.", failure) }
    fun clear() { check(!file.exists() || file.delete()) { "Wiederherstellungsdatei konnte nicht entfernt werden" } }

    private fun position(value: GeoProjection) = buildJsonObject {
        put("lat", value.latitude); put("lon", value.longitude); put("heading", value.headingRadians)
    }
    private fun encode(state: BenchmarkUserState) = buildJsonObject {
        put("schema", "no5-benchmark-recovery/2"); put("runId", state.runId)
        put("chartId", state.chartId?.let(::JsonPrimitive) ?: JsonNull)
        put("paths", JsonArray(state.paths.map(::JsonPrimitive)))
        if (state.backgroundPaths.isNotEmpty()) put("backgroundPaths", JsonArray(state.backgroundPaths.map(::JsonPrimitive)))
        put("viewport", BostonBenchmarkRecipe.cameraJson(state.viewport))
        put("presentation", buildJsonObject { put("style", state.presentation.styleId); put("symbols", state.presentation.symbols.id); put("relief", state.presentation.landRelief) })
        put("settings", buildJsonObject { put("palette", state.settings.palette.name); put("safety", state.settings.safetyContourMetres); put("objectFilters", encodeObjectFilters(state.settings.objectFilters)) })
        put("overlays", buildJsonObject {
            put("ownship", state.overlays.ownship?.let(::position) ?: JsonNull)
            put("ais", JsonArray(state.overlays.ais.map(::position))); put("waypoints", JsonArray(state.overlays.waypoints.map(::position)))
            put("route", state.overlays.route?.let { JsonArray(it.points.map(::position)) } ?: JsonNull)
            fun objects(values: List<ChartOverlayObject>) = JsonArray(values.map { value -> buildJsonObject {
                put("id", value.id); put("label", value.label); put("position", position(value.position)); put("heading", value.headingRadians?.let(::JsonPrimitive) ?: JsonNull)
            } })
            put("aisObjects", objects(state.overlays.aisObjects)); put("waypointObjects", objects(state.overlays.waypointObjects))
            put("routeMetadata", state.overlays.route?.let { route -> buildJsonObject {
                put("id", route.id); put("revision", route.revision); put("role", route.role.name)
                put("pointIds", JsonArray(route.pointIds.map(::JsonPrimitive))); put("pointNames", JsonArray(route.pointNames.map(::JsonPrimitive)))
            } } ?: JsonNull)
        })
        put("bufferTransformMode", state.bufferTransformMode.name)
        put("currentViewOnly", state.currentViewOnly)
        put("residentSourceGeneration", state.residentSourceGeneration?.let(::JsonPrimitive) ?: JsonNull)
        put("residentPolicyKey", state.residentPolicyKey?.let(::JsonPrimitive) ?: JsonNull)
        put("residentActivation", state.residentActivation?.let(::encodeActivation) ?: JsonNull)
    }
    private fun encodeActivation(value: ChartResidentActivation) = buildJsonObject {
        put("id", value.id)
        put("initialViewport", BostonBenchmarkRecipe.cameraJson(value.initialViewport))
        put("catalog", buildJsonObject {
            put("generation", value.catalog.generation)
            put("budget", buildJsonObject {
                put("cells", value.catalog.budget.cells); put("bytes", value.catalog.budget.bytes)
                put("loadingBytes", value.catalog.budget.loadingBytes)
            })
            put("sources", JsonArray(value.catalog.sources.map { source -> buildJsonObject {
                put("provider", source.provider); put("name", source.name); put("sourceKey", source.sourceKey)
                put("edition", source.edition); put("update", source.update); put("order", source.order)
                if (source.provider == PI_CACHE_PROVIDER) {
                    put("product", checkNotNull(source.product)); put("licenseHost", checkNotNull(source.licenseHost))
                }
                put("files", JsonArray(source.files.map { entry -> buildJsonObject {
                    put("path", entry.path); put("sha256", entry.sha256)
                } }))
            } }))
        })
        put("navigationBounds", buildJsonObject {
            put("generation", value.navigationBounds.generation)
            put("cells", JsonArray(value.navigationBounds.cells.map { cell -> buildJsonObject {
                put("cellId", cell.cellId); put("south", cell.south); put("north", cell.north)
                put("west", cell.west); put("east", cell.east)
            } }))
        })
    }
    private fun decodeViewport(view: JsonObject) = ChartViewport(
        view.getValue("latitude").jsonPrimitive.double, view.getValue("longitude").jsonPrimitive.double,
        view.getValue("metresToPixels").jsonPrimitive.double, view.getValue("rotationRadians").jsonPrimitive.double,
        view.getValue("width").jsonPrimitive.int, view.getValue("height").jsonPrimitive.int).also { viewport ->
        check(rendererBenchmarkStartBlocker(ChartEngineStatus(initialized = true, surfaceAttached = true,
            rendererBenchmarkDataAvailable = true, viewport = viewport)) == null)
    }
    private fun decodeActivation(value: JsonObject): ChartResidentActivation {
        val rawCatalog = value.getValue("catalog").jsonObject
        val generation = rawCatalog.getValue("generation").jsonPrimitive.content
        check(generation.matches(DIGEST))
        val rawBudget = rawCatalog.getValue("budget").jsonObject
        val budget = ChartResidentBudget(rawBudget.getValue("cells").jsonPrimitive.int,
            rawBudget.getValue("bytes").jsonPrimitive.long, rawBudget.getValue("loadingBytes").jsonPrimitive.long)
        val rawSources = rawCatalog.getValue("sources").jsonArray
        check(rawSources.size in 1..MAX_SOURCES)
        var pinnedFiles = 0
        val sources = rawSources.map { raw ->
            val source = raw.jsonObject
            val name = source.getValue("name").jsonPrimitive.content
            val edition = source.getValue("edition").jsonPrimitive.int
            val update = source.getValue("update").jsonPrimitive.int
            val order = source.getValue("order").jsonPrimitive.int
            val provider = source.getValue("provider").jsonPrimitive.content
            val key = source.getValue("sourceKey").jsonPrimitive.content
            // Released public profiles and a private Pi export retain separate descriptor identities.
            // DSID/IENC validation belongs to the source import/restore preflight; this
            // descriptor retains its exact provider-bound identity and ordered file pins.
            val supportedProfile = when (provider) {
                "noaa-s57" -> name.matches(Regex("[A-Z0-9]+")) // Existing NOAA pins unchanged.
                "wsv-inland-s57" -> name.matches(Regex("1W[7-9][A-Z0-9]{5}"))
                PI_CACHE_PROVIDER -> name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}"))
                BACKGROUND_CACHE_PROVIDER -> name.matches(Regex("NO5BG_[NS][0-9]{2}_[EW][0-9]{3}"))
                else -> false
            }
            check(supportedProfile && edition >= 0 &&
                update in 0..(if (provider == PI_CACHE_PROVIDER) 65535 else 999) && order >= 0 && key.matches(DIGEST))
            val rawFiles = source.getValue("files").jsonArray
            check(rawFiles.size == (if (provider == PI_CACHE_PROVIDER || provider == BACKGROUND_CACHE_PROVIDER) 2 else update + 1) && pinnedFiles <= MAX_PINNED_FILES - rawFiles.size)
            pinnedFiles += rawFiles.size
            val files = rawFiles.map { entry -> entry.jsonObject.let {
                ChartResidentFile(it.getValue("path").jsonPrimitive.content,
                    it.getValue("sha256").jsonPrimitive.content).also { pin ->
                    check(File(pin.path).isAbsolute && '\u0000' !in pin.path && pin.sha256.matches(DIGEST))
                }
            } }
            val parent = File(files.first().path).parentFile
            files.forEachIndexed { index, entry ->
                check(provider == PI_CACHE_PROVIDER || provider == BACKGROUND_CACHE_PROVIDER || File(entry.path).parentFile == parent && File(entry.path).name ==
                    name + "." + index.toString().padStart(3, '0'))
            }
            ChartResidentSource(provider, name, key, edition, update, order, files,
                source["product"]?.jsonPrimitive?.contentOrNull, source["licenseHost"]?.jsonPrimitive?.contentOrNull).also {
                check(provider != PI_CACHE_PROVIDER || validPiResidentSource(it)) { "Invalid Pi cache provenance descriptor" }
                check(provider != BACKGROUND_CACHE_PROVIDER || validBackgroundResidentSource(it)) { "Invalid public background descriptor" }
                check(residentPinIdentity(it) == key) { "Resident source identity mismatch" }
            }
        }
        check(sources.map { it.sourceKey }.distinct().size == sources.size &&
            sources.map { it.name }.distinct().size == sources.size && sources.map { it.order }.distinct().size == sources.size)
        val bounds = value.getValue("navigationBounds").jsonObject
        val rawCells = bounds.getValue("cells").jsonArray
        check(rawCells.size <= MAX_SOURCES)
        val navigation = ChartNavigationBounds(bounds.getValue("generation").jsonPrimitive.long.also { check(it >= 0) },
            rawCells.map { raw -> raw.jsonObject.let { cell ->
                ChartCellExtent(cell.getValue("cellId").jsonPrimitive.content, cell.getValue("south").jsonPrimitive.double,
                    cell.getValue("north").jsonPrimitive.double, cell.getValue("west").jsonPrimitive.double,
                    cell.getValue("east").jsonPrimitive.double)
            } })
        return residentActivationSnapshot(ChartResidentActivation(value.getValue("id").jsonPrimitive.content,
            ChartResidentCatalog(generation, sources, budget), decodeViewport(value.getValue("initialViewport").jsonObject), navigation))
    }
    private fun decode(json: JsonObject): BenchmarkUserState {
        val schema = json.getValue("schema").jsonPrimitive.content
        check(schema == "no5-benchmark-recovery/1" || schema == "no5-benchmark-recovery/2")
        val id = json.getValue("runId").jsonPrimitive.content
        check(UUID.fromString(id).toString() == id)
        val viewport = decodeViewport(json.getValue("viewport").jsonObject)
        val profile = json.getValue("presentation").jsonObject
        val presentation = ChartPresentation(profile.getValue("style").jsonPrimitive.content,
            ChartSymbolSet.entries.first { it.id == profile.getValue("symbols").jsonPrimitive.content }, profile.getValue("relief").jsonPrimitive.boolean).also { it.validate() }
        val settings = json.getValue("settings").jsonObject
        val s52 = S52Settings(ChartPalette.valueOf(settings.getValue("palette").jsonPrimitive.content), settings.getValue("safety").jsonPrimitive.double, settings["objectFilters"]?.jsonObject?.let(::decodeObjectFilters) ?: ChartObjectFilters())
        check(s52.safetyContourMetres.isFinite() && s52.safetyContourMetres >= 0)
        fun geo(value: JsonElement): GeoProjection {
            val p = value.jsonObject
            return GeoProjection(p.getValue("lat").jsonPrimitive.double, p.getValue("lon").jsonPrimitive.double,
                p.getValue("heading").jsonPrimitive.double).also {
                check(it.latitude.isFinite() && it.latitude in -85.0..85.0 && it.longitude.isFinite() && it.longitude in -180.0..180.0 && it.headingRadians.isFinite())
            }
        }
        val overlay = json.getValue("overlays").jsonObject
        fun objects(key: String) = overlay[key]?.jsonArray.orEmpty().map { entry ->
            val value = entry.jsonObject
            ChartOverlayObject(value.getValue("id").jsonPrimitive.content, geo(value.getValue("position")), value.getValue("label").jsonPrimitive.content,
                value["heading"]?.takeIf { it != JsonNull }?.jsonPrimitive?.double).also {
                val heading = it.headingRadians
                check(it.id.length <= 200 && it.label.length <= 200 && (heading == null || heading.isFinite() && heading >= 0 && heading < Math.PI * 2))
            }
        }
        val meta = overlay["routeMetadata"]?.takeIf { it != JsonNull }?.jsonObject
        val route = overlay.getValue("route").takeIf { it != JsonNull }?.jsonArray?.map(::geo)?.let { points ->
            RouteProjection(points, meta?.get("id")?.jsonPrimitive?.content.orEmpty(), meta?.get("revision")?.jsonPrimitive?.long ?: 0,
                meta?.get("role")?.jsonPrimitive?.content?.let(ChartRouteRole::valueOf) ?: ChartRouteRole.SAVED,
                meta?.get("pointIds")?.jsonArray.orEmpty().map { it.jsonPrimitive.content }, meta?.get("pointNames")?.jsonArray.orEmpty().map { it.jsonPrimitive.content })
        }
        val overlays = ChartOverlays(overlay.getValue("ownship").takeIf { it != JsonNull }?.let(::geo),
            overlay.getValue("ais").jsonArray.map(::geo), overlay.getValue("waypoints").jsonArray.map(::geo), route, objects("aisObjects"), objects("waypointObjects"))

        val paths = json.getValue("paths").jsonArray.map { it.jsonPrimitive.content }.also { entries ->
            check(entries.all { File(it).isAbsolute && '\u0000' !in it })
        }
        val v2 = schema.endsWith("/2")
        val activation = if (v2) json.getValue("residentActivation").takeIf { it != JsonNull }?.jsonObject?.let(::decodeActivation) else null
        val sourceGeneration = if (v2) json.getValue("residentSourceGeneration").takeIf { it != JsonNull }?.jsonPrimitive?.long else null
        val policyKey = if (v2) json.getValue("residentPolicyKey").jsonPrimitive.contentOrNull else null
        if (activation == null) check(sourceGeneration == null && policyKey == null)
        else check(paths.isEmpty() && sourceGeneration != null && sourceGeneration > 0 && policyKey != null &&
            policyKey.length <= 1_024 && '\u0000' !in policyKey)
        val mode = if (v2) ChartBufferTransformMode.valueOf(json.getValue("bufferTransformMode").jsonPrimitive.content)
            else ChartBufferTransformMode.OFF
        val currentViewOnly = json["currentViewOnly"]?.jsonPrimitive?.boolean ?: false
        val backgrounds = json["backgroundPaths"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        check(backgrounds.distinct().size == backgrounds.size && paths.containsAll(backgrounds))
        check(!currentViewOnly || activation != null) { "Current-view restore requires its original resident catalog" }
        return BenchmarkUserState(id, json.getValue("chartId").jsonPrimitive.contentOrNull,
            Collections.unmodifiableList(paths), viewport, presentation, s52, overlays, activation, sourceGeneration, policyKey, mode, currentViewOnly,
            Collections.unmodifiableList(backgrounds))
    }
    companion object {
        const val MAX_JOURNAL_BYTES = 16 * 1024 * 1024
        private const val MAX_SOURCES = 50_000
        private const val MAX_PINNED_FILES = 100_000
        private val DIGEST = Regex("[0-9a-f]{64}")

        /** Same descriptor identity as native residentSourceKey; does not inspect source bytes. */
        internal fun residentPinIdentity(source: ChartResidentSource): String {
            val canonical = buildJsonObject {
                put("provider", source.provider)
                if (source.provider == PI_CACHE_PROVIDER) {
                    put("product", checkNotNull(source.product)); put("licenseHost", checkNotNull(source.licenseHost))
                }
                put("cell", source.name)
                put("edition", source.edition); put("update", source.update)
                put("files", JsonArray(source.files.map { pin -> buildJsonObject {
                    put("name", File(pin.path).name); put("sha256", pin.sha256)
                } }))
            }.toString()
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}


/** Binds a registered FULL's visible camera and actual filled producer to the current attachment.
 * Reserve dimensions are never inferred from the visible frame or an unfilled padded rectangle. */
internal fun benchmarkOuterProducerCamera(full: ChartBufferSnapshot, visibleCamera: ChartViewport,
    geometry: ChartSurfaceGeometry, attachedRaster: ChartRasterGeometry?, nativeProducer: ChartViewport,
    mode: ChartBufferTransformMode): ChartViewport? {
    if (!full.producerCompleted || full.stage != ChartContentStage.FULL ||
        full.frame.geometry != geometry || full.frame.viewport != visibleCamera ||
        visibleCamera.width != geometry.frameWidth || visibleCamera.height != geometry.frameHeight) return null
    val reserve = mode == ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE
    val raster = full.raster
    if (reserve && (raster == null || attachedRaster == null)) return null
    if (raster != null) {
        val attached = attachedRaster ?: return null
        if (attached.visible != geometry || raster != ChartBufferRaster.capture(attached, visibleCamera, reserve)) return null
    } else if (mode != ChartBufferTransformMode.OFF && attachedRaster != null) return null
    val registeredProducer = raster?.producerCamera ?: full.frame.viewport
    return registeredProducer.takeIf { it == nativeProducer }
}

/** Geometry exported with the snapshot, independently of visible/requested camera dimensions. */
internal fun benchmarkOuterGeometry(geometry: ChartSurfaceGeometry) = buildJsonObject {
    put("surfaceWidth", geometry.surfaceWidth); put("surfaceHeight", geometry.surfaceHeight)
    put("frameWidth", geometry.frameWidth); put("frameHeight", geometry.frameHeight)
}
internal fun benchmarkOuterRasterGeometry(raster: ChartRasterGeometry) = buildJsonObject {
    put("visible", benchmarkOuterGeometry(raster.visible))
    put("bufferWidth", raster.bufferWidth); put("bufferHeight", raster.bufferHeight)
}
internal fun benchmarkOuterRaster(raster: ChartBufferRaster) = buildJsonObject {
    put("geometry", benchmarkOuterRasterGeometry(raster.geometry))
    put("producerCamera", BostonBenchmarkRecipe.cameraJson(raster.producerCamera))
    put("filledLeft", raster.filledLeft); put("filledTop", raster.filledTop)
    put("filledWidth", raster.filledWidth); put("filledHeight", raster.filledHeight)
}


/** Private debug artifact reference: no Library or recovery-journal write. */
data class RendererOuterSnapshotExport(val snapshotId: String, val relativePath: String,
    val byteLength: Long, val sha256: String, val catalogGeneration: String,
    val sourceGeneration: Long, val rendererSession: String, val sourceCommit: String) {
    fun asJsonString(): String = buildJsonObject {
        put("schema", "no5-capacity-outer-export/1"); put("snapshotId", snapshotId)
        put("relativePath", relativePath); put("byteLength", byteLength); put("sha256", sha256)
        put("catalogGeneration", catalogGeneration); put("sourceGeneration", sourceGeneration)
        put("rendererSession", rendererSession); put("sourceCommit", sourceCommit)
    }.toString()
}

/** Bounded app-private export. A snapshot UUID owns one atomic file slot.
 * Reusing that slot is allowed only for its same runtime/catalog/source owner.
 * Failed/colliding artifacts are preserved; no automatic cleanup of user data. */
class RendererOuterSnapshotExporter(private val filesDirectory: File) {
    private data class Owner(val snapshotId: String, val rendererSession: String,
        val catalogGeneration: String, val sourceGeneration: Long)
    private fun owner(bytes: ByteArray, expectedId: String): Owner {
        check(bytes.isNotEmpty() && bytes.size <= MAX_EXPORT_BYTES)
        check(UUID.fromString(expectedId).toString() == expectedId)
        val value = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        check(value.getValue("schema").jsonPrimitive.content == "no5-capacity-outer-state/1" &&
            value.getValue("scope").jsonPrimitive.content == "CURRENT_ENTRY_CATALOG_ONLY")
        check(value.getValue("snapshotId").jsonPrimitive.content == expectedId)
        val state = value.getValue("state").jsonObject
        check(state.getValue("schema").jsonPrimitive.content == "no5-benchmark-recovery/2" &&
            state.getValue("runId").jsonPrimitive.content == expectedId)
        val authority = value.getValue("authority").jsonObject
        val catalog = state.getValue("residentActivation").jsonObject.getValue("catalog").jsonObject
        val catalogGeneration = catalog.getValue("generation").jsonPrimitive.content
        val sourceGeneration = state.getValue("residentSourceGeneration").jsonPrimitive.long
        val session = value.getValue("rendererSession").jsonPrimitive.content
        check(UUID.fromString(session).toString() == session && catalogGeneration.matches(DIGEST) && sourceGeneration > 0)
        check(authority.getValue("catalogGeneration").jsonPrimitive.content == catalogGeneration &&
            authority.getValue("sourceGeneration").jsonPrimitive.long == sourceGeneration &&
            authority.getValue("budget") == catalog.getValue("budget"))
        val full = value.getValue("fullProducerBuffer").jsonObject
        check(authority.getValue("viewportAuthority").jsonPrimitive.content == "REGISTERED_BUFFER_FRAME" &&
            authority.getValue("viewport") == value.getValue("producerViewport") &&
            value.getValue("visibleViewport") == state.getValue("viewport") &&
            full.getValue("producerCompleted").jsonPrimitive.boolean && full.getValue("stamp").jsonPrimitive.long > 0 &&
            full.getValue("sourceGeneration") == authority.getValue("sourceGeneration") &&
            full.getValue("contentGeneration") == authority.getValue("contentGeneration") &&
            full.getValue("cameraRevision") == authority.getValue("cameraRevision") &&
            authority.getValue("cameraRevision") == authority.getValue("requestedCameraRevision"))
        return Owner(expectedId, session, catalogGeneration, sourceGeneration)
    }
    private fun readBounded(file: File): ByteArray {
        check(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..MAX_EXPORT_BYTES.toLong())
        val output = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val chunk = ByteArray(8_192)
            while (true) {
                val count = input.read(chunk); if (count < 0) break
                check(output.size() <= MAX_EXPORT_BYTES - count); output.write(chunk, 0, count)
            }
        }
        return output.toByteArray()
    }
    fun export(snapshot: String, snapshotId: String, sourceCommit: String): RendererOuterSnapshotExport =
        synchronized(EXPORT_LOCK) { exportLocked(snapshot, snapshotId, sourceCommit) }
    private fun exportLocked(snapshot: String, snapshotId: String, sourceCommit: String): RendererOuterSnapshotExport {
        check(sourceCommit.matches(Regex("[0-9a-f]{40}"))) { "Exact build source pin required" }
        val bytes = snapshot.toByteArray(Charsets.UTF_8)
        val identity = owner(bytes, snapshotId)
        val base = filesDirectory.canonicalFile
        check(base.isDirectory)
        val directory = File(base, DIRECTORY)
        check(!Files.isSymbolicLink(directory.toPath()))
        check(directory.exists() || directory.mkdir())
        check(directory.isDirectory && directory.canonicalFile.parentFile == base)
        val target = File(directory, "$snapshotId.json")
        check(target.canonicalFile.parentFile == directory.canonicalFile && !Files.isSymbolicLink(target.toPath()))
        val entries = checkNotNull(directory.listFiles())
        check(entries.size <= MAX_EXPORT_SLOTS && (target.exists() || entries.size < MAX_EXPORT_SLOTS)) {
            "Debug snapshot export slots full; no automatic cleanup"
        }
        if (target.exists()) check(owner(readBounded(target), snapshotId) == identity) { "Snapshot slot belongs to another owner" }
        val temp = File(directory, "$snapshotId.tmp-${UUID.randomUUID()}")
        check(!temp.exists())
        try {
            FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
            check(temp.setReadable(false, false) && temp.setReadable(true, true) &&
                temp.setWritable(false, false) && temp.setWritable(true, true)) { "Private export permissions failed" }
            check(readBounded(temp).contentEquals(bytes))
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            // Directory publication must be durable too; unsupported FS behavior fails closed.
            java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
            val published = readBounded(target)
            check(published.contentEquals(bytes) && owner(published, snapshotId) == identity)
            return RendererOuterSnapshotExport(snapshotId, "files/$DIRECTORY/$snapshotId.json", bytes.size.toLong(),
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
                identity.catalogGeneration, identity.sourceGeneration, identity.rendererSession, sourceCommit)
        } finally { temp.delete() }
    }
    companion object {
        const val DIRECTORY = "renderer-outer-snapshots"
        const val MAX_EXPORT_BYTES = 16 * 1024 * 1024
        private const val MAX_EXPORT_SLOTS = 8
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val EXPORT_LOCK = Any() // Shared by every Receiver/exporter instance in this app process.
    }
}
