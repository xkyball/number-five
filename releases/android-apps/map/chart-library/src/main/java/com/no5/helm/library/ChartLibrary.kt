package com.no5.helm.library

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.Collections
import java.util.zip.CRC32
import java.util.zip.ZipFile
import com.no5.helm.chart.ValidatedChartCell
import com.no5.helm.chart.BACKGROUND_CACHE_PROVIDER

@Serializable data class ChartCell(val name: String, val edition: Int, val update: Int, val base: String,
    val issueDate: String, val files: Map<String, String>, val cancelled: Boolean = false,
    @kotlinx.serialization.Transient val selectionExtent: ChartSelectionExtent? = null,
    @kotlinx.serialization.Transient val catalogMetadata: ChartCatalogMetadata? = null,
    val provider: String = "noaa-s57",
    @kotlinx.serialization.Transient val piCache: PiCacheProvenance? = null)
@Serializable data class ChartPackage(val id: String, val label: String, val importedAt: Long, val cells: List<ChartCell>,
    val source: String = "NOAA S57 · https://www.charts.noaa.gov/ENCs/ENCs.shtml",
    val piCache: PiCacheProvenance? = null)
@Serializable data class ChartSelection(val packageId: String, val cells: List<String>)
data class ChartLibrarySnapshot(val packages: List<ChartPackage> = emptyList(), val active: ChartSelection? = null,
    val issues: List<String> = emptyList(), val activeRelease: ChartReleasedSelection? = null, val selectionVersion: Int? = null,
    val pendingCleanup: Boolean = false)
data class ImportLimits(val archiveBytes: Long = 512L * 1024 * 1024, val expandedBytes: Long = 2L * 1024 * 1024 * 1024,
    val fileBytes: Long = 128L * 1024 * 1024, val entries: Int = 20000, val cells: Int = 1500)

/** Immutable packages plus an atomically replaced active selection. No app asset copier writes here. */
class ChartLibrary(private val root: File, private val limits: ImportLimits = ImportLimits(),
    private val lifecycleCheckpoint: (String) -> Unit = {}) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val packages = File(root, "packages")
    private val staging = File(root, "staging")
    init { require(packages.mkdirs() || packages.isDirectory); require(staging.mkdirs() || staging.isDirectory) }
    private fun manifest(directory: File) = File(directory, "package.json")
    private fun packageDirectory(id: String): File { require(id.matches(Regex("[a-f0-9-]{36}"))); return File(packages, id) }
    private fun readPackage(directory: File, checkpoint: () -> Unit = {}): ChartPackage {
        require(directory.canonicalPath.startsWith(packages.canonicalPath + File.separator)) { "Paketverzeichnis außerhalb des Kartenbestands" }
        val stored = json.decodeFromString<ChartPackage>(readMetadata(manifest(directory), MANIFEST_BYTES, checkpoint))
        require(stored.id == directory.name && stored.cells.size <= MAX_CATALOG_CELLS &&
            stored.cells.map { it.name }.distinct().size == stored.cells.size) { "Kartenmanifest ungültig" }
        val index = try { File(directory, "spatial-index-v1.json").takeIf { it.isFile }?.let {
            json.decodeFromString<ChartSpatialIndex>(readMetadata(it, SIDECAR_BYTES, checkpoint)).takeIf { value -> value.version == 1 }
        } } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
        val catalog = try { File(directory, "spatial-index-v2.json").takeIf { it.isFile }?.let {
            json.decodeFromString<ChartCatalogIndex>(readMetadata(it, SIDECAR_BYTES, checkpoint)).takeIf { value -> value.version == 2 }
        } } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
        val extents = index?.cells?.groupBy { it.name }
        val metadata = catalog?.cells?.groupBy { it.name }
        return stored.copy(cells = stored.cells.mapIndexed { order, cell ->
            checkpoint()
            val background = cell.provider == BACKGROUND_CACHE_PROVIDER
            val located = cell.copy(provider = if (stored.piCache != null && !background) PiCachePackage.provider else cell.provider, piCache = stored.piCache.takeUnless { background }, selectionExtent = extents?.get(cell.name)?.singleOrNull()?.takeIf { it.files == cell.files }
                ?.extent?.takeIf { it.valid })
            located.copy(catalogMetadata = metadata?.get(cell.name)?.singleOrNull()
                ?.takeIf { ChartMetadataContract.matches(it, located, order, stored.piCache) })
        })
    }
    private data class StoredSelection(val selection: ChartSelection, val version: Int,
        val release: ChartReleasedSelection? = null)
    private class UnsupportedSelectionVersion : IllegalArgumentException("Gespeicherte Auswahl verwendet eine unbekannte Version; unverändert erhalten")
    private fun readLegacyActiveSelection(): StoredSelection? = File(root, "active.json").also {
        require(!it.exists() || it.isFile) { "Gespeicherte Auswahl nicht lesbar; unverändert erhalten" }
    }.takeIf { it.isFile }?.let { file ->
        val text = readMetadata(file, SELECTION_BYTES)
        val version = json.parseToJsonElement(text).jsonObject["version"]
        if (version == null) StoredSelection(json.decodeFromString<ChartSelection>(text), 1)
        else {
            if (version.jsonPrimitive.intOrNull != 2) throw UnsupportedSelectionVersion()
            val release = json.decodeFromString<ChartReleasedSelection>(text)
            StoredSelection(release.selection(), 2, freezeRelease(release))
        }
    }
    private fun lifecycleFile() = File(root, "lifecycle-state-v1.json")
    private fun readLifecycle(): ChartLifecycleState? {
        val file = lifecycleFile()
        if (!file.exists()) return null
        require(file.isFile) { "Kartenverwaltung nicht lesbar; Bestand unverändert erhalten" }
        val value = json.decodeFromString<ChartLifecycleState>(readMetadata(file, LIFECYCLE_BYTES))
        require(value.version == 1 && value.revision >= 0 && value.selectionVersion in 1..2 &&
            (value.active != null || value.release == null) &&
            (value.release == null || value.selectionVersion == 2 && value.release.selection() == value.active) &&
            (value.active == null || value.active.cells.isNotEmpty() && value.active.cells.distinct().size == value.active.cells.size) &&
            value.removedPackages.distinct().size == value.removedPackages.size &&
            value.removedPackages.all { it.matches(Regex("[a-f0-9-]{36}")) } &&
            value.cancellations.all { S57Profiles.supportedProvider(it.provider) && it.name.matches(Regex("[A-Z0-9]{1,64}")) &&
                it.edition > 0 && it.update in 0..999 && it.sourceKey.matches(Regex("[a-f0-9]{64}")) &&
                it.packageId.matches(Regex("[a-f0-9-]{36}")) } &&
            value.cleanup.all { it.packageId.matches(Regex("[a-f0-9-]{36}")) &&
                (it.paths == null || it.paths.distinct().size == it.paths.size) }) {
            "Unbekannte oder beschädigte Kartenverwaltung; kein Rückbau auf ältere Auswahl"
        }
        value.cleanup.forEach { item -> item.paths?.forEach { safePath(File(packageDirectory(item.packageId), "data"), it) } }
        return value
    }
    private fun readActiveSelection(): StoredSelection? {
        val lifecycle = readLifecycle() ?: return readLegacyActiveSelection()
        return lifecycle.active?.let { StoredSelection(it, lifecycle.selectionVersion, lifecycle.release?.let(::freezeRelease)) }
    }
    private fun currentLifecycle(resident: Boolean): ChartLifecycleState {
        readLifecycle()?.let { stored ->
            require(resident || stored.selectionVersion != 2) { "Residenter Kartenbestand erfordert den residenten Renderer" }
            return stored
        }
        val saved = readLegacyActiveSelection() // Unknown versions are never overwritten.
        require(resident || saved?.version != 2) { "Eine V2-Auswahl darf nicht durch einen alten Vollrenderer ersetzt werden" }
        return ChartLifecycleState(selectionVersion = if (resident) 2 else 1,
            active = saved?.selection, release = if (resident) saved?.release else null)
    }
    private fun availablePackage(pack: ChartPackage, lifecycle: ChartLifecycleState?): ChartPackage {
        val cells = pack.cells.filterNot { cell -> lifecycle?.cancellations.orEmpty().any { it.blocks(cell) } }
        return if (cells.size == pack.cells.size) pack else pack.copy(cells = cells.mapIndexed { order, cell ->
            cell.copy(catalogMetadata = cell.catalogMetadata?.copy(order = order))
        })
    }
    fun hasPersistedSelection() = lifecycleFile().exists() || File(root, "active.json").exists()
    fun hasPendingCleanup() = readLifecycle()?.cleanup?.isNotEmpty() == true
    /** Optional selection metadata, kept separate from immutable package manifests and chart bytes. */
    suspend fun ensureSpatialIndexes() = withContext(Dispatchers.IO) { lock.withLock {
        val job = currentCoroutineContext()
        for (directory in packages.listFiles().orEmpty().filter { it.isDirectory }) {
            job.ensureActive()
            if (File(directory, "spatial-index-v1.json").isFile) continue
            try {
                val stored = readPackage(directory)
                val index = spatialIndex(stored.cells, File(directory, "data")) { job.ensureActive() }
                atomicWrite(File(directory, "spatial-index-v1.json"), json.encodeToString(index))
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Unlocated cells remain selectable; activation still verifies all bytes. */ }
        }
    } }
    private fun spatialIndex(cells: List<ChartCell>, data: File, verifyBytes: Boolean = true, checkpoint: () -> Unit): ChartSpatialIndex =
        ChartSpatialIndex(cells = cells.map { cell ->
            checkpoint()
            val files = cell.files.keys.sortedBy { File(it).extension.toIntOrNull() ?: Int.MAX_VALUE }
                .map { path -> safePath(data, path).also { if (verifyBytes) require(sha256(it) == cell.files[path]) } }
            val extent = S57SelectionExtent.read(files) { checkpoint() }
            ChartSpatialEntry(cell.name, cell.files, extent)
        })
    /** Reads only a hash-pinned sidecar of the currently selected cell. Never resolves a UI filename as a filesystem path. */
    suspend fun readObjectText(packageId: String, cellName: String, sourceKey: String, name: String): com.no5.helm.chart.ChartObjectText = withContext(Dispatchers.IO) { lock.withLock {
        fun unavailable(message: String) = com.no5.helm.chart.ChartObjectText(name, message = message)
        if (name.isBlank() || name.length > 240 || name.any { it.code < 32 } || name.contains('/') || name.contains('\\') || name.contains(':') || name in setOf(".", ".."))
            return@withLock unavailable("Ungültiger Zusatztextverweis")
        val snapshot = snapshot()
        if (snapshot.active?.packageId != packageId || cellName !in snapshot.active.cells)
            return@withLock unavailable("Kartenquelle nicht mehr aktiv")
        val pack = snapshot.packages.singleOrNull { it.id == packageId } ?: return@withLock unavailable("Kartenquelle fehlt")
        val cell = pack.cells.singleOrNull { it.name == cellName } ?: return@withLock unavailable("Kartenzelle fehlt")
        if (ChartMetadataContract.sourceKey(cell, pack.piCache) != sourceKey) return@withLock unavailable("Kartenquelle hat sich geändert")
        if (pack.piCache == null) return@withLock unavailable("Kein verifizierter Zusatztext in dieser Kartenquelle")
        val relative = "provenance/$cellName.json"
        val expected = cell.files[relative] ?: return@withLock unavailable("Herkunftsdatei fehlt im Import")
        val file = safePath(File(packageDirectory(packageId), "data"), relative)
        if (!file.isFile || file.length() > 8L * 1024 * 1024 || sha256(file) != expected)
            return@withLock unavailable("Herkunftsdatei fehlt oder ist verändert")
        try {
            val text = json.parseToJsonElement(file.readText()).jsonObject["text"]?.jsonObject?.get(name)?.jsonPrimitive?.content
                ?: return@withLock unavailable("Zusatztext fehlt bereits im Exportbestand")
            if (sha256(file) != expected) return@withLock unavailable("Herkunftsdatei wurde beim Lesen verändert")
            com.no5.helm.chart.ChartObjectText(name, text, "")
        } catch (_: Exception) { unavailable("Zusatztext konnte nicht gelesen werden") }
    } }

    fun snapshot(): ChartLibrarySnapshot {
        val issues = mutableListOf<String>()
        val lifecycle = try { readLifecycle() } catch (_: Exception) {
            issues += "Kartenverwaltung beschädigt oder aus einer anderen Version; gespeicherte Auswahl unverändert erhalten."
            null
        }
        val stored = packages.listFiles().orEmpty().filter { it.isDirectory && it.name !in lifecycle?.removedPackages.orEmpty() }.mapNotNull { directory ->
            try { availablePackage(readPackage(directory), lifecycle).also { require(it.id == directory.name) } }
            catch (_: Exception) { issues += "Ein Kartenpaket konnte nicht gelesen werden. Seine Dateien bleiben erhalten."; null }
        }.sortedByDescending { it.importedAt }
        val active = try { readActiveSelection() }
            catch (_: UnsupportedSelectionVersion) { issues += "Gespeicherte Kartenauswahl stammt aus einer anderen App-Version. Auswahl unverändert erhalten; kein automatischer Rückbau."; null }
            catch (_: Exception) { issues += "Gespeicherte Kartenauswahl beschädigt. Bestand erhalten; erneut auswählen."; null }
        val validActive = active?.takeIf { saved -> stored.any { pack ->
            val selection = saved.selection
            pack.id == selection.packageId && selection.cells.isNotEmpty() &&
                (saved.version == 2 || selection.cells.size <= 100) &&
                selection.cells.distinct().size == selection.cells.size && pack.cells.map { it.name }.containsAll(selection.cells) &&
                (saved.release == null || runCatching { releaseFor(pack, selection) == saved.release }.getOrDefault(false))
        } }
        if (active != null && validActive == null) issues += "Gespeicherte Kartenauswahl nicht verfügbar. Bestand erhalten; erneut auswählen."
        if (lifecycle?.cleanup?.isNotEmpty() == true) issues += "Dateibereinigung noch offen; ausgeschlossene Quellen werden nicht erneut aktiviert."
        return ChartLibrarySnapshot(stored.filter { it.cells.isNotEmpty() }, validActive?.selection, issues, validActive?.release,
            active?.version ?: lifecycle?.selectionVersion, lifecycle?.cleanup?.isNotEmpty() == true)
    }
    fun paths(selection: ChartSelection): List<File> {
        val stored = readPackage(packageDirectory(selection.packageId))
        require(selection.cells.isNotEmpty() && selection.cells.size <= 100 && selection.cells.distinct().size == selection.cells.size) { "1 bis 100 verschiedene Zellen aktivieren" }
        val newestCells = snapshot().packages.flatMap { it.cells }.groupBy { it.name }.mapValues { (_, versions) -> versions.maxWithOrNull(compareBy<ChartCell> { it.edition }.thenBy { it.update }) }
        return selection.cells.map { name ->
            val cell = stored.cells.singleOrNull { it.name == name } ?: error("Zelle nicht im Paket: $name")
            val newest = newestCells[name]
            val lifecycle = readLifecycle()
            require(stored.id !in lifecycle?.removedPackages.orEmpty()) { "Paket wurde aus dem gespeicherten Bestand entfernt" }
            require(!cell.cancelled && newest?.cancelled != true && lifecycle?.cancellations.orEmpty().none { it.blocks(cell) }) { "Der Herausgeber hat die Zelle $name abgekündigt; sie wird nicht aktiviert" }
            val base = safePath(File(packageDirectory(stored.id), "data"), cell.base)
            cell.files.forEach { (path, hash) -> require(sha256(safePath(File(packageDirectory(stored.id), "data"), path)) == hash) { "Gespeicherte Zelle verändert: $name" } }
            base
        }
    }
    /** Retained for migrations and local Java probes. */
    suspend fun importZip(label: String, input: InputStream, validate: suspend (List<File>, File) -> Unit,
        progress: (String) -> Unit = {}): ChartPackage = importZipWithProgress(label, input,
        { paths, cache, _ -> validate(paths, cache) }, { progress("${it.phase.title} · ${it.detail}") })

    suspend fun importZipWithProgress(label: String, input: InputStream,
        validate: suspend (List<File>, File, (Int, File?) -> Unit) -> Unit,
        progress: (ImportProgress) -> Unit = {}, archiveBytes: Long? = null): ChartPackage =
        importZipWithMetadata(label, input, { paths, cache, done -> validate(paths, cache, done); emptyList() }, progress, archiveBytes)

    suspend fun importZipWithMetadata(label: String, input: InputStream,
        validate: suspend (List<File>, File, (Int, File?) -> Unit) -> List<ValidatedChartCell>,
        progress: (ImportProgress) -> Unit = {}, archiveBytes: Long? = null): ChartPackage {
        // All filesystem work stays off the UI thread, including hashing and staging cleanup.
        try { return withContext(Dispatchers.IO) { lock.withLock {
            val job = currentCoroutineContext()
            val reporter = ImportProgressReporter(progress)
            val id = UUID.randomUUID().toString()
            val stage = File(staging, id).apply { check(mkdirs()) { "Importverzeichnis nicht anlegbar" } }
            val progressFlush = launch(Dispatchers.Default) {
                while (isActive) { delay(200); reporter.flushPending() }
            }
            var published = false
            fun reading(file: File, detail: String, done: Int? = null, total: Int? = null) {
                reporter.report(ImportProgress(ImportPhase.CHECKING, detail, file.relativeTo(File(stage, "data")).invariantSeparatorsPath,
                    done, total, currentFileBytes = 0, currentFileTotalBytes = file.length()))
            }
            fun checkpoint(bytes: Long) {
                job.ensureActive()
                reporter.report(reporter.latest.copy(currentFileBytes = bytes))
            }
            fun hash(file: File): String = sha256(file, ::checkpoint)
            try {
                val archive = File(stage, "source.zip")
                reporter.phase(ImportProgress(ImportPhase.READING, "ZIP wird lokal eingelesen", label,
                    processedBytes = 0, totalBytes = archiveBytes?.takeIf { it >= 0 }))
                FileOutputStream(archive).use { out ->
                    val source = input
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) {
                        job.ensureActive(); val n = source.read(buffer); if (n < 0) break
                        total += n; require(total <= limits.archiveBytes) { "ZIP größer als ${limits.archiveBytes / 1024 / 1024} MiB" }
                        require(stage.usableSpace > n + 20L * 1024 * 1024) { "Nicht genug freier App-Speicher" }
                        out.write(buffer, 0, n)
                        // Some document providers report stale lengths. Stop displaying their total if exceeded.
                        reporter.report(reporter.latest.copy(processedBytes = total,
                            totalBytes = reporter.latest.totalBytes?.takeIf { total <= it }))
                    }
                    out.fd.sync()
                    reporter.report(reporter.latest.copy(processedBytes = total, totalBytes = total), force = true)
                }
                reporter.phase(ImportProgress(ImportPhase.OPENING, "ZIP-Verzeichnis wird geöffnet"))
                val data = File(stage, "data").apply { check(mkdirs()) }
                val seen = mutableSetOf<String>(); var expanded = 0L; var count = 0; var completed = 0
                ZipFile(archive).use { zip ->
                    // Read the local central directory before announcing a known extraction total.
                    var fileCount = 0; var byteCount = 0L; var knownBytes = true; var entryCount = 0
                    val inventory = zip.entries()
                    while (inventory.hasMoreElements()) {
                        job.ensureActive(); val entry = inventory.nextElement()
                        require(++entryCount <= limits.entries) { "Zu viele ZIP-Einträge" }
                        if (!entry.isDirectory) {
                            fileCount++
                            if (entry.size < 0) knownBytes = false
                            else { require(entry.size <= limits.fileBytes) { "ZIP-Dateigröße ungültig: ${entry.name}" }
                                byteCount += entry.size
                                require(byteCount <= limits.expandedBytes) { "Entpacktes Paket größer als ${limits.expandedBytes / 1024 / 1024} MiB" } }
                        }
                    }
                    reporter.phase(ImportProgress(ImportPhase.EXTRACTING, "ZIP-Dateien und CRC-Prüfsummen",
                        processedFiles = 0, totalFiles = fileCount, processedBytes = 0, totalBytes = byteCount.takeIf { knownBytes }))
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        job.ensureActive(); val entry = entries.nextElement()
                        require(++count <= limits.entries) { "Zu viele ZIP-Einträge" }
                        val path = entry.name.removeSuffix("/")
                        if (!entry.isDirectory) reporter.report(reporter.latest.copy(currentFile = path, currentFileBytes = 0,
                            currentFileTotalBytes = entry.size.takeIf { it >= 0 }))
                        val target = safePath(data, path)
                        require(seen.add(path.lowercase(Locale.ROOT))) { "Doppelter ZIP-Pfad: $path" }
                        if (entry.isDirectory) { require(target.mkdirs() || target.isDirectory); continue }
                        require(entry.size in 0..limits.fileBytes && entry.crc >= 0) { "ZIP-Dateigröße oder Prüfsumme ungültig: $path" }
                        require(stage.usableSpace > entry.size + 20L * 1024 * 1024) { "Nicht genug freier App-Speicher" }
                        reporter.report(reporter.latest.copy(currentFile = path, currentFileBytes = 0, currentFileTotalBytes = entry.size))
                        require(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                        val crc = CRC32(); var size = 0L
                        zip.getInputStream(entry).use { source -> FileOutputStream(target).use { out ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                job.ensureActive(); val n = source.read(buffer); if (n < 0) break
                                size += n; expanded += n
                                require(size <= limits.fileBytes && size <= entry.size && expanded <= limits.expandedBytes) { "ZIP-Größenlimit überschritten" }
                                crc.update(buffer, 0, n); out.write(buffer, 0, n)
                                reporter.report(reporter.latest.copy(processedBytes = expanded, currentFileBytes = size))
                            }
                            out.fd.sync()
                        } }
                        require(size == entry.size && crc.value == entry.crc) { "ZIP-Datei beschädigt: $path" }
                        reporter.report(reporter.latest.copy(processedFiles = ++completed))
                    }
                    reporter.report(reporter.latest, force = true)
                }
                check(archive.delete()) { "Temporäre ZIP-Datei konnte nicht entfernt werden" }
                reporter.phase(ImportProgress(ImportPhase.CHECKING, "Kartenformat und vorhandener Bestand werden ermittelt"))
                val backgroundInput = BackgroundCachePackage.read(data, limits, { job.ensureActive() }) { file ->
                    reading(file, "Freie Grundkartenzellen prüfen"); hash(file)
                }
                val piInput = PiCachePackage.read(data, limits, { job.ensureActive() },
                    backgroundInput?.let(BackgroundCachePackage::files).orEmpty()) { file ->
                    reading(file, "Pi-Exportdatei prüfen"); hash(file)
                }
                require(backgroundInput == null || piInput != null && backgroundInput.product == piInput.product &&
                    backgroundInput.cells.size + piInput.cells.size <= limits.cells && piInput.cells.all { cell ->
                        cell.extent.south >= backgroundInput.bounds.south && cell.extent.north <= backgroundInput.bounds.north &&
                        cell.extent.west >= backgroundInput.bounds.west && cell.extent.east <= backgroundInput.bounds.east
                    }) { "Gemeinsamer Kartenbestand ungültig" }
                val installed = snapshot().packages
                val cells = if (piInput != null) {
                    reporter.phase(ImportProgress(ImportPhase.CHECKING, "Pi-Export, Herkunft und Dateiprüfsummen"))
                    val previous = installed.filter { it.piCache?.product == piInput.product && it.piCache.licenseHost == piInput.licenseHost }.flatMap { it.cells }
                    piInput.cells.map { cell ->
                        previous.filter { it.name == cell.name }.forEach { old ->
                            require(cell.edition >= old.edition && (cell.edition != old.edition || cell.update >= old.update)) { "Veralteter Pi-Zellstand: ${cell.name}" }
                            require(cell.edition != old.edition || cell.update != old.update || cell.files[cell.base] == old.files[old.base]) { "Widersprüchlicher Pi-Zellstand: ${cell.name}" }
                        }
                        ChartCell(cell.name, cell.edition, cell.update, cell.base, cell.issueDate, cell.files, selectionExtent = cell.extent, provider = PiCachePackage.provider, piCache = PiCacheProvenance(piInput.product, piInput.licenseHost, piInput.exportedUtc))
                    }.let { nautical -> nautical + backgroundInput?.cells.orEmpty().map { cell ->
                        ChartCell(cell.name, cell.edition, cell.update, cell.base, cell.issueDate, cell.files,
                            selectionExtent = cell.extent, provider = BACKGROUND_CACHE_PROVIDER)
                    } }.sortedBy { it.name }
                } else {
                    fun chartFiles() = data.walkTopDown().filter {
                        job.ensureActive()
                        it.isFile && it.extension.matches(Regex("[0-9]{3}")) && !it.name.equals("CATALOG.031", true)
                    }.toList()
                    var files = chartFiles()
                    require(files.isNotEmpty()) { "Keine unterstützten S57-Zellen (.000) im ZIP" }
                    // Extend update-only archives from immutable installed files, never in place.
                    files.groupBy { it.parentFile!!.path + "/" + it.nameWithoutExtension }.values
                        .filter { group -> group.none { it.extension == "000" } }.forEach { group ->
                        job.ensureActive()
                        reading(group.first(), "Updatebasis bestimmen")
                        val header = S57Identification.read(group.first(), ::checkpoint)
                        val candidates = installed.filter { it.piCache == null }.flatMap { pack -> pack.cells.filter {
                            it.name == header.name.substringBefore('.') && it.provider == header.provider
                        }.map { pack to it } }
                        val previous = candidates.maxWithOrNull(compareBy<Pair<ChartPackage, ChartCell>> { it.second.edition }.thenBy { it.second.update })
                            ?: error("Basiszelle .000 fehlt; zuerst vollständiges Paket importieren: ${header.name}")
                        require(previous.second.edition == header.edition || header.edition == 0) { "Neue Edition erfordert eine vollständige .000-Basiszelle: ${header.name}" }
                        for ((path, expectedHash) in previous.second.files) {
                            job.ensureActive()
                            val source = safePath(File(packageDirectory(previous.first.id), "data"), path)
                            reporter.report(ImportProgress(ImportPhase.CHECKING, "Gespeicherte Updatebasis prüfen", path,
                                currentFileBytes = 0, currentFileTotalBytes = source.length()))
                            require(hash(source) == expectedHash) { "Gespeicherte Updatebasis verändert" }
                            val target = File(group.first().parentFile, source.name)
                            if (!target.exists()) {
                                expanded += source.length()
                                require(expanded <= limits.expandedBytes && stage.usableSpace > source.length() + 20L * 1024 * 1024) { "Nicht genug Speicher für vollständige Updatekette" }
                                reporter.report(reporter.latest.copy(detail = "Gespeicherte Updatebasis übernehmen", currentFileBytes = 0))
                                source.inputStream().use { inputFile -> FileOutputStream(target).use { outputFile ->
                                    val buffer = ByteArray(65536); var copied = 0L
                                    while (true) { job.ensureActive(); val n = inputFile.read(buffer); if (n < 0) break
                                        outputFile.write(buffer, 0, n); copied += n; checkpoint(copied) }
                                    outputFile.fd.sync()
                                } }
                            }
                        }
                    }
                    files = chartFiles()
                    val groups = files.groupBy { it.parentFile!!.relativeTo(data).path + "/" + it.nameWithoutExtension }
                    require(groups.size <= limits.cells) { "Zu viele Karten: maximal ${limits.cells}" }
                    var checked = 0
                    reporter.phase(ImportProgress(ImportPhase.CHECKING, "S57-Struktur, Updateketten und Dateiprüfsummen", processedFiles = 0, totalFiles = files.size))
                    val cells = groups.map { (_, group) ->
                        job.ensureActive()
                        val ordered = group.sortedBy { it.extension.toInt() }
                        require(ordered.first().extension == "000") { "Basiszelle .000 fehlt: ${ordered.first().name}" }
                        require(ordered.map { it.extension.toInt() } == ordered.indices.toList()) { "Updatekette hat Lücken: ${ordered.first().name}" }
                        val hashes = mutableMapOf<String, String>()
                        val identity = ordered.map { file ->
                            reading(file, "S57-Struktur lesen", checked, files.size)
                            val metadata = S57Identification.read(file, ::checkpoint)
                            reading(file, "SHA-256-Dateiprüfsumme", checked, files.size)
                            hashes[file.relativeTo(data).invariantSeparatorsPath] = hash(file)
                            reporter.report(reporter.latest.copy(processedFiles = ++checked))
                            metadata
                        }
                        val base = identity.first()
                        require(base.update == 0 && base.edition > 0 && base.exchangePurpose == 1) { "Vollständige Basiszelle mit UPDN=0 erforderlich" }
                        identity.forEachIndexed { index, header ->
                            require(header.name == ordered[index].name && header.name.substringBefore('.') == base.name.substringBefore('.') &&
                                header.provider == base.provider && header.producingAgency == base.producingAgency &&
                                header.productSpecification == base.productSpecification && header.productEdition == base.productEdition &&
                                (header.edition == base.edition || index == identity.lastIndex && index > 0 && header.edition == 0) && header.update == index && (index == 0 || header.exchangePurpose == 2)) { "Zellname, Herausgeber, Edition oder Updatenummer widersprechen Dateikette: ${ordered[index].name}" }
                        }
                        ChartCell(base.name.substringBefore('.'), base.edition, identity.last().update,
                            ordered.first().relativeTo(data).invariantSeparatorsPath, identity.last().issueDate, hashes, identity.last().edition == 0,
                            provider = base.provider)
                    }.sortedBy { it.name }
                    reporter.report(reporter.latest, force = true)
                    require(cells.map { it.name }.distinct().size == cells.size) { "Doppelte Zelle in verschiedenen ZIP-Verzeichnissen" }
                    reporter.report(reporter.latest.copy(detail = "Editionen mit gespeichertem Bestand vergleichen", currentFile = null,
                        currentFileBytes = null, currentFileTotalBytes = null), force = true)
                    val previous = installed.filter { it.piCache == null }.flatMap { it.cells }.groupBy { it.provider to it.name }
                    cells.forEach { cell -> job.ensureActive(); previous[cell.provider to cell.name]?.forEach { old ->
                        require(cell.edition >= old.edition && (cell.edition != old.edition || cell.update >= old.update)) { "Veraltete Edition/Update für ${cell.name}; Bestand unverändert" }
                        if (cell.edition == old.edition) old.files.forEach { (path, expectedHash) ->
                            val corresponding = cell.files.entries.singleOrNull { File(it.key).name == File(path).name }
                            require(corresponding != null && corresponding.value == expectedHash) { "Bereits vorhandene Ausgabe widersprüchlich: ${cell.name}; neue Edition erforderlich" }
                        }
                    } }
                    val receipts = readLifecycle()?.cancellations.orEmpty()
                    cells.filterNot { it.cancelled }.forEach { cell ->
                        require(receipts.none { it.blocks(cell) }) { "Der Herausgeber hat die Edition von ${cell.name} abgekündigt; höhere vollständige Edition erforderlich" }
                    }
                    cells
                }
                val paths = cells.filterNot { it.cancelled }.map { safePath(data, it.base) }
                reporter.phase(ImportProgress(ImportPhase.INDEXING, if (piInput != null) "Native SENC-Geometrie prüfen; Geometrieindex erstellen"
                    else "Native S57-Geometrie und Updates prüfen; Geometrieindex erstellen",
                    processedFiles = 0, totalFiles = paths.size))
                val metadata = validate(paths, File(stage, "validation")) { done, current ->
                    job.ensureActive()
                    reporter.report(reporter.latest.copy(processedFiles = done,
                        currentFile = current?.relativeTo(data)?.invariantSeparatorsPath))
                }
                // Bounds summarize actual local base/update coordinates, never names or guessed provider zones.
                val spatial = if (piInput != null) ChartSpatialIndex(cells = cells.map { ChartSpatialEntry(it.name, it.files, it.selectionExtent) })
                    else spatialIndex(cells, data, verifyBytes = false) { job.ensureActive() }
                atomicWrite(File(stage, "spatial-index-v1.json"), json.encodeToString(spatial))
                val locatedCells = cells.map { cell -> cell.copy(selectionExtent = spatial.cells.single { it.name == cell.name }.extent) }
                val provenance = piInput?.let { PiCacheProvenance(it.product, it.licenseHost, it.exportedUtc) }
                val catalog = catalogIndex(locatedCells, data, metadata, provenance) { job.ensureActive() }
                atomicWrite(File(stage, "spatial-index-v2.json"), json.encodeToString(catalog))
                job.ensureActive()
                reporter.report(reporter.latest.copy(processedFiles = paths.size, currentFile = null), force = true)
                reporter.phase(ImportProgress(ImportPhase.SAVING, "Prüfdaten entfernen und Kartenpaket atomar speichern"))
                check(File(stage, "validation").deleteRecursively()) { "Temporäre Prüfdaten konnten nicht entfernt werden" }
                job.ensureActive()
                val stored = ChartPackage(id, label.take(120).ifBlank { piInput?.product ?: "S57-Kartenpaket" }, System.currentTimeMillis(),
                    locatedCells.map { cell -> cell.copy(catalogMetadata = catalog.cells.singleOrNull { it.name == cell.name }) },
                    source = piInput?.let { "${it.product} · Pi ${it.licenseHost} · Export ${it.exportedUtc}" +
                        if (backgroundInput != null) " · Grundkarte: Natural Earth (Public Domain)" else "" }
                        ?: cells.map { it.provider }.distinct().joinToString(" · ") { S57Profiles.sourceLabel(it) }, piCache = provenance)
                atomicWrite(manifest(stage), json.encodeToString(stored))
                job.ensureActive()
                Files.move(stage.toPath(), packageDirectory(id).toPath(), StandardCopyOption.ATOMIC_MOVE)
                published = true
                reporter.report(reporter.latest.copy(activity = ImportActivity.COMPLETED, detail = "Kartenpaket gespeichert"), force = true)
                stored
            } catch (cancel: CancellationException) {
                reporter.report(reporter.latest.copy(activity = if (published) ImportActivity.COMPLETED else ImportActivity.CANCELLING,
                    detail = if (published) "Kartenpaket gespeichert" else "Temporäre Importdateien werden entfernt"), force = true)
                throw cancel
            } catch (failure: Exception) {
                reporter.report(reporter.latest.copy(activity = ImportActivity.FAILED, error = failure.message ?: "Unbekannter Importfehler"), force = true)
                throw failure
            } finally {
                progressFlush.cancel()
                val cleaned = stage.deleteRecursively()
                if (reporter.latest.activity == ImportActivity.CANCELLING)
                    reporter.report(reporter.latest.copy(activity = ImportActivity.CANCELLED,
                        detail = if (cleaned) "Import abgebrochen; temporäre Dateien entfernt" else "Import abgebrochen; temporäre Dateien nicht vollständig entfernt"), force = true)
                else if (!cleaned && !published)
                    reporter.report(reporter.latest.copy(error = listOfNotNull(reporter.latest.error,
                        "Temporäre Importdateien konnten nicht vollständig entfernt werden").joinToString(" · ")), force = true)
            }
        } } } finally { input.close() }
    }
    private fun verifyCellBytes(cell: ChartCell, data: File, checkpoint: () -> Unit) {
        cell.files.forEach { (path, expected) ->
            checkpoint()
            require(sha256(safePath(data, path)) { checkpoint() } == expected) { "Gespeicherte Zelle verändert: ${cell.name}" }
        }
    }
    private fun releaseFor(pack: ChartPackage, selection: ChartSelection): ChartReleasedSelection {
        require(pack.id == selection.packageId && selection.cells.isNotEmpty() &&
            selection.cells.distinct().size == selection.cells.size && selection.cells.size <= pack.cells.size) { "Verschiedene Zellen eines gespeicherten Pakets auswählen" }
        val cells = pack.cells.associateBy { it.name }
        return freezeRelease(ChartReleasedSelection(packageId = pack.id, cells = selection.cells.map { name ->
            val cell = cells[name] ?: error("Zelle nicht im Paket: $name")
            require(!cell.cancelled) { "Der Herausgeber hat die Zelle $name abgekündigt; sie wird nicht aktiviert" }
            ChartReleasedCell(name, ChartMetadataContract.sourceKey(cell))
        }))
    }
    private fun bindResidentSelection(selection: ChartSelection, expectedRelease: ChartReleasedSelection?,
        checkpoint: () -> Unit): ChartResidentSelection {
        checkpoint()
        val directory = packageDirectory(selection.packageId)
        val lifecycle = readLifecycle()
        require(selection.packageId !in lifecycle?.removedPackages.orEmpty()) { "Paket wurde aus dem gespeicherten Bestand entfernt" }
        val pack = availablePackage(readPackage(directory, checkpoint), lifecycle)
        val data = File(directory, "data")
        require(data.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "Kartendaten außerhalb des Paketverzeichnisses" }
        val release = releaseFor(pack, selection)
        if (expectedRelease != null) require(release == expectedRelease) { "Gespeicherte Quellenidentität verändert; Auswahl unverändert erhalten" }
        val selectedNames = selection.cells.toSet()
        val newest = pack.cells.filter { it.name in selectedNames }.associateBy { it.name }.toMutableMap()
        // Cancellation state is metadata IO only. Chart byte hashes are checked by
        // native per-cell admission, never by a catalog scan on camera changes.
        for (installed in packages.listFiles().orEmpty().filter { it.isDirectory }) {
            checkpoint()
            if (installed == directory) continue
            val candidate = try { readPackage(installed, checkpoint) }
                catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { continue }
            if (candidate.piCache?.product != pack.piCache?.product || candidate.piCache?.licenseHost != pack.piCache?.licenseHost) continue
            for (cell in candidate.cells) if (cell.name in selectedNames && cell.provider == newest[cell.name]?.provider) {
                val previous = newest[cell.name]
                val comparison = previous?.let { compareValuesBy(cell, it, { value -> value.edition }, { value -> value.update }) }
                if (comparison == null || comparison > 0 || comparison == 0 && cell.cancelled) newest[cell.name] = cell
            }
        }
        val selectedCells = pack.cells.filter { it.name in selectedNames }
        for (cell in selectedCells) {
            checkpoint()
            require(newest[cell.name]?.cancelled != true && lifecycle?.cancellations.orEmpty().none { it.blocks(cell) }) { "Der Herausgeber hat die Zelle ${cell.name} abgekündigt; sie wird nicht aktiviert" }
            if (pack.piCache == null) require(cell.name.matches(Regex("[A-Z0-9]{1,64}")) && cell.edition > 0 && cell.update in 0..999) { "Ungültige Zellenidentität" }
            val ordered = if (pack.piCache != null) PiCachePackage.residentFiles(cell) else
                cell.files.entries.sortedBy { File(it.key).extension.toIntOrNull() ?: Int.MAX_VALUE }
            require((pack.piCache != null || ordered.size == cell.update + 1) && ordered.first().key == cell.base) { "Unvollständige Updatekette: ${cell.name}" }
            val parent = File(cell.base).parent
            ordered.forEachIndexed { index, (path, hash) ->
                checkpoint()
                require((pack.piCache != null || File(path).parent == parent && File(path).name == "%s.%03d".format(cell.name, index)) &&
                    hash.matches(Regex("[a-f0-9]{64}"))) { "Ungebundene Updatekette: ${cell.name}" }
                safePath(data, path) // Guard paths/symlinks without opening chart contents.
            }
        }
        val frozenPack = freezePackage(pack)
        val frozenSelection = selection.copy(cells = Collections.unmodifiableList(selection.cells.toList()))
        val planning = ChartPlanningCatalog.fromPackage(frozenPack, selectedNames)
        checkpoint()
        return ChartResidentSelection(frozenSelection, frozenPack, planning, data, release)
    }
    /** One metadata-only IO operation per release/restore. The caller retains the
     * binding/planner across cameras. Pass activeRelease on restore to pin its sources. */
    suspend fun residentSelection(selection: ChartSelection, expectedRelease: ChartReleasedSelection? = null): ChartResidentSelection =
        withContext(Dispatchers.IO) { lock.withLock {
            val job = currentCoroutineContext()
            bindResidentSelection(selection, expectedRelease) { job.ensureActive() }
        } }

    /** Publish all released sources, independent of the resident geometry quota.
     * The caller has configured the explicit resident engine before publication.
     * This does not assert that every cell has been loaded or every pixel covered. */
    suspend fun activateResident(binding: ChartResidentSelection) = withContext(Dispatchers.IO) { lock.withLock {
        val job = currentCoroutineContext()
        val previous = readActiveSelection() // Unknown/newer/corrupt formats are never overwritten.
        val current = bindResidentSelection(binding.selection, binding.release) { job.ensureActive() }
        require(current.planning.generation == binding.planning.generation &&
            current.dataDirectory.canonicalFile == binding.dataDirectory.canonicalFile) { "Katalog während Aktivierung verändert; Auswahl erhalten" }
        if (previous?.version == 1) {
            val backup = File(root, "active-v1-preserved.json")
            require(!backup.exists() || backup.isFile) { "V1-Wiederherstellungsreserve nicht verfügbar; Auswahl erhalten" }
            if (!backup.exists()) {
                job.ensureActive()
                atomicWrite(backup, readMetadataBytes(File(root, "active.json"), SELECTION_BYTES) { job.ensureActive() })
            }
        }
        job.ensureActive()
        val lifecycle = readLifecycle()
        if (lifecycle == null) atomicWrite(File(root, "active.json"), json.encodeToString(current.release))
        else writeLifecycle(lifecycle.copy(revision = lifecycle.revision + 1, selectionVersion = 2,
            active = current.selection, release = current.release))
    } }
    private fun catalogIndex(cells: List<ChartCell>, data: File, metadata: List<ValidatedChartCell>,
        provenance: PiCacheProvenance? = null, checkpoint: () -> Unit): ChartCatalogIndex {
        val expected = cells.filterNot { it.cancelled }.associateBy { safePath(data, it.base).canonicalPath }
        val byPath = metadata.associateBy { File(it.path).canonicalPath }
        require(byPath.size == metadata.size && expected.keys.containsAll(byPath.keys)) { "Native Metadaten widersprechen dem geprüften Zellensatz" }
        return ChartCatalogIndex(cells = cells.mapIndexedNotNull { order, cell ->
            byPath[safePath(data, cell.base).canonicalPath]?.let { value ->
                // Bind to the bytes after native parsing, not merely their earlier manifest.
                verifyCellBytes(cell, data, checkpoint)
                ChartMetadataContract.attach(cell, order, value, provenance)
            }
        })
    }
    /** Explicit per-cell backfill for old packages. Never called as a startup-wide scan. */
    suspend fun ensureCellMetadata(packageId: String, name: String,
        validate: suspend (List<File>, File) -> List<ValidatedChartCell>): ChartCatalogMetadata? = withContext(Dispatchers.IO) { lock.withLock {
        val job = currentCoroutineContext()
        val directory = packageDirectory(packageId)
        val pack = readPackage(directory)
        val cell = pack.cells.singleOrNull { it.name == name } ?: error("Zelle nicht im Paket: $name")
        if (cell.cancelled) return@withLock null
        val data = File(directory, "data")
        verifyCellBytes(cell, data) { job.ensureActive() }
        cell.catalogMetadata?.let { return@withLock it }
        val cache = File(staging, "metadata-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val values = validate(listOf(safePath(data, cell.base)), cache)
            job.ensureActive()
            require(values.size <= 1 && values.all { File(it.path).canonicalPath == safePath(data, cell.base).canonicalPath })
            val added = catalogIndex(pack.cells, data, values, pack.piCache) { job.ensureActive() }.cells
            if (added.isEmpty()) return@withLock null // Older engines keep the cell conservatively unknown.
            val merged = ChartCatalogIndex(cells = (pack.cells.mapNotNull { it.catalogMetadata }.filter { it.name != name } + added).sortedBy { it.order })
            job.ensureActive()
            atomicWrite(File(directory, "spatial-index-v2.json"), json.encodeToString(merged))
            added.singleOrNull { it.name == name }
        } finally { cache.deleteRecursively() }
    } }
    /** Caller has loaded the selection successfully. Publication never replaces any chart files. */
    suspend fun activate(selection: ChartSelection) = lock.withLock {
        require(readLifecycle()?.selectionVersion != 2 && readActiveSelection()?.version != 2) { "Eine V2-Auswahl darf nicht durch einen alten Vollrenderer oder eine Teilmenge ersetzt werden; expliziter Rückbau erforderlich" }
        paths(selection)
        val lifecycle = readLifecycle()
        if (lifecycle == null) atomicWrite(File(root, "active.json"), json.encodeToString(selection))
        else writeLifecycle(lifecycle.copy(revision = lifecycle.revision + 1, selectionVersion = 1, active = selection, release = null))
    }

    private fun writeLifecycle(value: ChartLifecycleState) {
        lifecycleCheckpoint("before-publication")
        atomicWrite(lifecycleFile(), json.encodeToString(value))
    }
    private fun rawPackages(): List<ChartPackage> = packages.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull {
        try { readPackage(it) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
    }
    private fun cancellationReceipts(stored: List<ChartPackage>, previous: List<ChartCancellationReceipt>): List<ChartCancellationReceipt> {
        val received = stored.flatMap { pack -> pack.cells.filter { it.cancelled }.mapNotNull { cell ->
            if (previous.any { it.blocks(cell) }) return@mapNotNull null // Payload may already be removed.
            val data = File(packageDirectory(pack.id), "data")
            require(cell.name.matches(Regex("[A-Z0-9]{1,64}")) && cell.edition > 0 && cell.update in 1..999) { "Ungültiger Abkündigungsbeleg" }
            verifyCellBytes(cell, data) {}
            val ordered = cell.files.keys.sortedBy { File(it).extension.toIntOrNull() ?: Int.MAX_VALUE }
            require(ordered.size == cell.update + 1 && ordered.first() == cell.base) { "Unvollständiger Abkündigungsbeleg" }
            ordered.forEachIndexed { index, path ->
                require(File(path).parent == File(cell.base).parent && File(path).name == "%s.%03d".format(cell.name, index)) { "Ungebundene Abkündigungskette" }
            }
            val base = S57Identification.read(safePath(data, cell.base))
            val last = S57Identification.read(safePath(data, ordered.last()))
            require(base.name == "${cell.name}.000" && base.edition == cell.edition && base.update == 0 && base.exchangePurpose == 1 &&
                base.provider == cell.provider && last.provider == cell.provider && base.producingAgency == last.producingAgency &&
                last.edition == 0 && last.update == cell.update &&
                last.name == "%s.%03d".format(cell.name, cell.update) && last.exchangePurpose == 2) { "Keine bestätigte EDTN0-Abkündigung: ${cell.name}" }
            ChartCancellationReceipt(cell.name, cell.edition, cell.update, cell.issueDate, pack.id, ChartMetadataContract.sourceKey(cell), cell.provider)
        } }
        return (previous + received).groupBy { it.provider to it.name }.values.map { versions ->
            versions.maxWith(compareBy<ChartCancellationReceipt> { it.edition }.thenBy { it.update })
        }.sortedWith(compareBy<ChartCancellationReceipt> { it.provider }.thenBy { it.name })
    }
    private fun filteredSelection(selection: ChartSelection?, state: ChartLifecycleState): ChartSelection? {
        if (selection == null || selection.packageId in state.removedPackages) return null
        val pack = availablePackage(readPackage(packageDirectory(selection.packageId)), state)
        val names = pack.cells.filterNot { it.cancelled }.map { it.name }.toSet()
        return selection.copy(cells = selection.cells.filter { it in names }).takeIf { it.cells.isNotEmpty() }
    }
    private fun cancellationCleanup(stored: List<ChartPackage>, state: ChartLifecycleState): ChartLifecycleState {
        val removed = state.removedPackages.toMutableSet()
        val cleanup = state.cleanup.toMutableList()
        for (pack in stored) {
            val blocked = pack.cells.filter { cell -> cell.cancelled || state.cancellations.any { it.blocks(cell) } }
            if (blocked.isEmpty()) continue
            if (blocked.size == pack.cells.size) {
                removed += pack.id
                cleanup += ChartPayloadCleanup(pack.id)
            } else {
                val data = File(packageDirectory(pack.id), "data")
                val retainedPaths = (pack.cells - blocked.toSet()).flatMap { it.files.keys }.toSet()
                val paths = blocked.flatMap { it.files.keys }.distinct().filter { it !in retainedPaths && safePath(data, it).exists() }
                if (paths.isNotEmpty()) cleanup += ChartPayloadCleanup(pack.id, paths.sorted())
            }
        }
        // Whole-package cleanup subsumes its per-cell records; duplicate work is idempotent.
        val whole = cleanup.filter { it.paths == null }.map { it.packageId }.toSet()
        val consolidated = whole.sorted().map { ChartPayloadCleanup(it) } + cleanup.filter { it.packageId !in whole }
            .groupBy { it.packageId }.map { (id, entries) -> ChartPayloadCleanup(id, entries.flatMap { it.paths.orEmpty() }.distinct().sorted()) }
        return state.copy(removedPackages = removed.sorted(), cleanup = consolidated.sortedBy { it.packageId })
    }
    private fun mutation(previous: ChartLifecycleState, proposed: ChartLifecycleState, resident: Boolean,
        description: String, cancellation: Boolean = false): ChartLibraryMutation {
        val selected = filteredSelection(proposed.active, proposed)
        val release = if (resident && selected != null) releaseFor(availablePackage(readPackage(packageDirectory(selected.packageId)), proposed), selected) else null
        val target = proposed.copy(revision = previous.revision + 1, selectionVersion = if (resident) 2 else 1,
            active = selected?.copy(cells = Collections.unmodifiableList(selected.cells.toList())),
            release = release?.let(::freezeRelease), cancellations = Collections.unmodifiableList(proposed.cancellations.toList()),
            removedPackages = Collections.unmodifiableList(proposed.removedPackages.toList()),
            cleanup = Collections.unmodifiableList(proposed.cleanup.map { item ->
                item.copy(paths = item.paths?.let { Collections.unmodifiableList(it.toList()) })
            }))
        return ChartLibraryMutation(previous.revision, previous.active, previous.release, target, description, cancellation)
    }
    suspend fun prepareDeactivate(selection: ChartSelection, resident: Boolean): ChartLibraryMutation =
        withContext(Dispatchers.IO) { lock.withLock {
            val previous = currentLifecycle(resident)
            val active = previous.active ?: error("Keine aktive Kartenauswahl")
            require(selection.packageId == active.packageId && selection.cells.isNotEmpty() &&
                selection.cells.distinct().size == selection.cells.size) { "Aktive Zellen des aktuellen Pakets auswählen" }
            val names = active.cells.filterNot { it in selection.cells.toSet() }
            require(names.size < active.cells.size) { "Keine ausgewählte Zelle ist aktiv" }
            mutation(previous, previous.copy(active = active.copy(cells = names).takeIf { names.isNotEmpty() }),
                resident, "${active.cells.size - names.size} Zellen deaktiviert; Dateien bleiben gespeichert.")
        } }
    suspend fun prepareDeletePackage(packageId: String, resident: Boolean): ChartLibraryMutation =
        withContext(Dispatchers.IO) { lock.withLock {
            val previous = currentLifecycle(resident)
            require(packageId !in previous.removedPackages) { "Paket bereits entfernt" }
            readPackage(packageDirectory(packageId)) // Require a known, guarded target.
            val stored = rawPackages()
            val receipts = cancellationReceipts(stored, previous.cancellations)
            val target = cancellationCleanup(stored, previous.copy(cancellations = receipts,
                removedPackages = (previous.removedPackages + packageId).distinct(),
                cleanup = previous.cleanup + ChartPayloadCleanup(packageId)))
            mutation(previous, target, resident, "Kartenpaket gelöscht.", receipts != previous.cancellations)
        } }
    suspend fun prepareCancellationCleanup(resident: Boolean): ChartLibraryMutation? =
        withContext(Dispatchers.IO) { lock.withLock {
            val previous = currentLifecycle(resident)
            val stored = rawPackages()
            val receipts = cancellationReceipts(stored, previous.cancellations)
            val target = cancellationCleanup(stored, previous.copy(cancellations = receipts))
            if (receipts == previous.cancellations && target.cleanup == previous.cleanup &&
                filteredSelection(previous.active, target) == previous.active) null
            else mutation(previous, target, resident, "Bestätigte abgekündigte Zellen verworfen; Sperrbelege bleiben gespeichert.", true)
        } }
    /** Imports a partial ZIP independently, then builds a complete immutable replacement of one package.
     * Unchanged chart chains and auxiliary files retain their exact bytes. No active source is changed here. */
    suspend fun mergeUpdatePackage(packageId: String, importedId: String): ChartPackage =
        withContext(Dispatchers.IO) { lock.withLock {
            val job = currentCoroutineContext()
            val lifecycle = readLifecycle()
            require(packageId !in lifecycle?.removedPackages.orEmpty() && packageId != importedId) { "Zielpaket nicht verfügbar" }
            val old = availablePackage(readPackage(packageDirectory(packageId)), lifecycle)
            val incoming = readPackage(packageDirectory(importedId))
            require((old.cells + incoming.cells).none { it.provider == BACKGROUND_CACHE_PROVIDER }) {
                "Paket mit Grundkarte als neuen Kartenbestand importieren; der bisherige Bestand bleibt erhalten"
            }
            require(old.piCache?.product == incoming.piCache?.product && old.piCache?.licenseHost == incoming.piCache?.licenseHost) { "Update gehört zu einer anderen Kartenherkunft" }
            require(incoming.cells.all { replacement -> old.cells.none { it.name == replacement.name && it.provider != replacement.provider } }) { "Update gehört zu einem anderen Kartenherausgeber" }
            require(incoming.cells.any { replacement -> old.cells.any { it.name == replacement.name } }) { "Update enthält keine Zelle des gewählten Pakets" }
            val replacingNames = incoming.cells.map { it.name }.toSet()
            val cells = (old.cells.filterNot { it.name in replacingNames } + incoming.cells).sortedBy { it.name }
            val id = UUID.randomUUID().toString()
            val stage = File(staging, id).apply { check(mkdirs()) }
            val data = File(stage, "data").apply { check(mkdirs()) }
            try {
                var copied = 0L
                fun copyFile(source: File, path: String) {
                    job.ensureActive()
                    val target = safePath(data, path)
                    require(source.isFile && !Files.isSymbolicLink(source.toPath())) { "Ungültige gespeicherte Updatedatei" }
                    if (target.exists()) {
                        require(sha256(source) { job.ensureActive() } == sha256(target) { job.ensureActive() }) { "Widersprüchliche Zusatzdatei im Update: $path" }
                        return
                    }
                    copied += source.length()
                    require(copied <= limits.expandedBytes && stage.usableSpace > source.length() + 20L * 1024 * 1024) { "Nicht genug Speicher für vollständigen aktualisierten Paketstand" }
                    check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    source.inputStream().use { input -> FileOutputStream(target).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) { job.ensureActive(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
                        output.fd.sync()
                    } }
                }
                for (pack in listOf(old, incoming)) {
                    val sourceData = File(packageDirectory(pack.id), "data")
                    val carried = if (pack.id == old.id) pack.cells.filterNot { it.name in replacingNames } else pack.cells
                    for (cell in carried) for ((path, hash) in cell.files) {
                        job.ensureActive()
                        val source = safePath(sourceData, path)
                        require(sha256(source) { job.ensureActive() } == hash) { "Gespeicherte Zelle verändert: ${cell.name}" }
                        copyFile(source, path)
                        require(sha256(safePath(data, path)) { job.ensureActive() } == hash) { "Updatedatei während der Übernahme verändert: ${cell.name}" }
                    }
                    // Keep referenced auxiliary files conservatively. Conflicting equal paths fail rather than overwrite.
                    for (source in sourceData.walkTopDown().onEnter { directory ->
                        require(!Files.isSymbolicLink(directory.toPath())) { "Verlinktes Paketverzeichnis" }; true
                    }.filter { it.isFile && !it.extension.matches(Regex("[0-9]{3}")) }) {
                        if (old.piCache != null) continue // Private payloads are copied solely from their pinned cell descriptors.
                        copyFile(source, source.relativeTo(sourceData).invariantSeparatorsPath)
                    }
                }
                val located = cells.mapIndexed { order, cell -> cell.copy(catalogMetadata = cell.catalogMetadata?.copy(order = order)) }
                val provenance = incoming.piCache
                if (provenance != null) {
                    val oldManifest = json.decodeFromString<PiCacheManifest>(File(packageDirectory(old.id), "data/${PiCachePackage.manifestName}").readText())
                    val incomingManifest = json.decodeFromString<PiCacheManifest>(File(packageDirectory(incoming.id), "data/${PiCachePackage.manifestName}").readText())
                    val entries = (oldManifest.cells.filterNot { it.name in replacingNames } + incomingManifest.cells).sortedBy { it.name }
                    require(entries.map { it.name } == located.map { it.name }) { "Pi-Updatemanifest unvollständig" }
                    atomicWrite(File(data, PiCachePackage.manifestName), json.encodeToString(incomingManifest.copy(cells = entries)))
                }
                val result = ChartPackage(id, old.label, System.currentTimeMillis(), located,
                    provenance?.let { "${it.product} · Pi ${it.licenseHost} · Export ${it.exportedUtc}" }
                        ?: located.map { it.provider }.distinct().joinToString(" · ") { S57Profiles.sourceLabel(it) }, provenance)
                atomicWrite(File(stage, "spatial-index-v1.json"), json.encodeToString(ChartSpatialIndex(cells = located.map { ChartSpatialEntry(it.name, it.files, it.selectionExtent) })))
                atomicWrite(File(stage, "spatial-index-v2.json"), json.encodeToString(ChartCatalogIndex(cells = located.mapNotNull { it.catalogMetadata })))
                atomicWrite(manifest(stage), json.encodeToString(result))
                job.ensureActive()
                Files.move(stage.toPath(), packageDirectory(id).toPath(), StandardCopyOption.ATOMIC_MOVE)
                result
            } finally { stage.deleteRecursively() }
        } }
    suspend fun prepareUpdatePackage(packageId: String, replacementId: String, importedId: String,
        resident: Boolean): ChartLibraryMutation = withContext(Dispatchers.IO) { lock.withLock {
        val previous = currentLifecycle(resident)
        require(packageId !in previous.removedPackages && replacementId != packageId && replacementId != importedId) { "Ungültiger Updateplan" }
        readPackage(packageDirectory(packageId)); readPackage(packageDirectory(replacementId)); readPackage(packageDirectory(importedId))
        val active = previous.active?.let { if (it.packageId == packageId) it.copy(packageId = replacementId) else it }
        val stored = rawPackages()
        val receipts = cancellationReceipts(stored, previous.cancellations)
        val target = cancellationCleanup(stored, previous.copy(active = active, cancellations = receipts,
            removedPackages = (previous.removedPackages + packageId + importedId).distinct(),
            cleanup = previous.cleanup + listOf(ChartPayloadCleanup(packageId), ChartPayloadCleanup(importedId))))
        mutation(previous, target, resident, "Kartenpaket aktualisiert; bisherige Auswahl übernommen.", receipts != previous.cancellations)
    } }
    /** The controller has committed the engine source transition; one atomic file publishes its pins. */
    suspend fun publishMutation(value: ChartLibraryMutation) = withContext(Dispatchers.IO) { lock.withLock {
        val previous = currentLifecycle(value.target.selectionVersion == 2)
        require(previous.revision == value.expectedRevision && previous.active == value.expectedActive &&
            previous.release == value.expectedRelease) { "Kartenbestand während der Aktion verändert; neuer Plan erforderlich" }
        value.target.active?.let { selected ->
            val pack = availablePackage(readPackage(packageDirectory(selected.packageId)), value.target)
            require(value.target.release == null || releaseFor(pack, selected) == value.target.release) { "Zielquellen während der Aktion verändert" }
        }
        writeLifecycle(value.target)
    } }
    /** Caller must provide an actual engine release ack. False never deletes even one file. */
    suspend fun finishCleanup(sourcesReleased: Boolean): ChartCleanupResult = withContext(Dispatchers.IO) { lock.withLock {
        val state = readLifecycle() ?: return@withLock ChartCleanupResult(true)
        if (state.cleanup.isEmpty()) return@withLock ChartCleanupResult(true)
        if (!sourcesReleased) return@withLock ChartCleanupResult(false, message = "Auswahl umgestellt; Dateibereinigung wartet auf die Freigabe des Renderers.")
        var removed = 0
        val pending = mutableListOf<ChartPayloadCleanup>()
        for (item in state.cleanup) {
            try {
                lifecycleCheckpoint("before-cleanup")
                val directory = packageDirectory(item.packageId)
                require(!Files.isSymbolicLink(directory.toPath())) { "Verlinktes Paket bleibt erhalten" }
                if (item.paths == null) {
                    require(state.active?.packageId != item.packageId && item.packageId in state.removedPackages) { "Aktives Paket darf nicht gelöscht werden" }
                    if (directory.exists()) {
                        directory.walkTopDown().onEnter { require(!Files.isSymbolicLink(it.toPath())) { "Verlinkte Paketdatei bleibt erhalten" }; true }
                            .forEach { require(!Files.isSymbolicLink(it.toPath())) { "Verlinkte Paketdatei bleibt erhalten" } }
                        val files = directory.walkTopDown().count { it.isFile }
                        check(directory.deleteRecursively()) { "Paketdateien konnten nicht vollständig gelöscht werden" }
                        removed += files
                    }
                } else {
                    val pack = if (directory.exists()) readPackage(directory) else null
                    val activePaths = if (state.active?.packageId == item.packageId) pack?.cells.orEmpty()
                        .filter { it.name in state.active?.cells.orEmpty() }.flatMap { it.files.keys }.toSet() else emptySet()
                    val data = File(directory, "data")
                    for (path in item.paths) {
                        require(path !in activePaths) { "Aktive Quelldatei darf nicht gelöscht werden" }
                        val file = safePath(data, path)
                        require(!Files.isSymbolicLink(file.toPath())) { "Verlinkte Quelldatei bleibt erhalten" }
                        if (file.exists()) { check(file.isFile && file.delete()) { "Quelldatei konnte nicht gelöscht werden" }; removed++ }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { pending += item }
        }
        writeLifecycle(state.copy(revision = state.revision + 1, cleanup = pending))
        ChartCleanupResult(pending.isEmpty(), removed, if (pending.isEmpty()) "Dateien gelöscht." else "Auswahl umgestellt; Dateibereinigung noch offen und wird beim nächsten Start fortgesetzt.")
    } }
    fun migrated() = File(root, "legacy-migrated").isFile
    fun markMigrated() = atomicWrite(File(root, "legacy-migrated"), "1\n")
    companion object {
        private const val MAX_CATALOG_CELLS = 20000
        private const val MANIFEST_BYTES = 8 * 1024 * 1024
        private const val SIDECAR_BYTES = 64 * 1024 * 1024
        private const val SELECTION_BYTES = 4 * 1024 * 1024
        private const val LIFECYCLE_BYTES = 32 * 1024 * 1024
        private fun readMetadata(file: File, limit: Int, checkpoint: () -> Unit = {}): String =
            readMetadataBytes(file, limit, checkpoint).toString(Charsets.UTF_8)
        private fun readMetadataBytes(file: File, limit: Int, checkpoint: () -> Unit = {}): ByteArray {
            require(file.length() <= limit) { "Kartenmetadaten überschreiten das Leselimit" }
            val bytes = ByteArrayOutputStream()
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    checkpoint()
                    val size = input.read(buffer)
                    if (size < 0) break
                    require(bytes.size() <= limit - size) { "Kartenmetadaten überschreiten das Leselimit" }
                    bytes.write(buffer, 0, size)
                }
            }
            return bytes.toByteArray()
        }
        private fun freezeRelease(value: ChartReleasedSelection) = value.copy(cells = Collections.unmodifiableList(value.cells.toList()))
        private fun freezePackage(pack: ChartPackage) = pack.copy(cells = Collections.unmodifiableList(pack.cells.map { cell ->
            cell.copy(files = Collections.unmodifiableMap(cell.files.toMap()), catalogMetadata = cell.catalogMetadata?.let { metadata ->
                metadata.copy(files = Collections.unmodifiableMap(metadata.files.toMap()),
                    coverage = Collections.unmodifiableList(metadata.coverage.map { Collections.unmodifiableList(it.toList()) }),
                    exclusions = Collections.unmodifiableList(metadata.exclusions.map { Collections.unmodifiableList(it.toList()) }))
            })
        }))
        fun safePath(root: File, path: String): File {
            require(path.isNotEmpty() && path.length <= 240 && !path.startsWith('/') && !path.contains('\\') && !path.contains(':') &&
                path.none { it.code < 32 } && path.split('/').size <= 20 && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Unsicherer ZIP-Pfad" }
            val file = File(root, path)
            require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "ZIP-Pfad außerhalb des Zielverzeichnisses" }
            return file
        }
        fun sha256(file: File): String = sha256(file) {}
        private fun sha256(file: File, checkpoint: (Long) -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); var read = 0L; checkpoint(0)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); read += n; checkpoint(read) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun atomicWrite(file: File, text: String) = atomicWrite(file, text.toByteArray(Charsets.UTF_8))
        private fun atomicWrite(file: File, bytes: ByteArray) {
            val temp = File(file.parentFile, file.name + ".tmp")
            try {
                FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }
    }
}
