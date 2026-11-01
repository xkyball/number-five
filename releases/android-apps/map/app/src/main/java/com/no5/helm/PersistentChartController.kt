package com.no5.helm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.no5.helm.chart.*
import com.no5.helm.library.*
import com.no5.helm.ui.ChartLibraryActions
import com.no5.helm.ui.ChartLibraryUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One Activity-owned operation. Confirmed cancellation and explicit update/delete have a release barrier. */
class PersistentChartController(private val context: Context, private val engine: ChartEngine,
    private val scope: CoroutineScope, private val restoredCamera: (ChartSelection) -> ChartViewport? = { null }) : ChartLibraryActions {
    private val libraryRoot = File(context.filesDir, "chart-library-v1")
    private val library = ChartLibrary(libraryRoot)
    private val mutable = MutableStateFlow(ChartLibraryUiState())
    override val state = mutable.asStateFlow()
    override suspend fun readObjectText(reference: ChartObjectReference, cellName: String, name: String): ChartObjectText {
        val current = engine.status.value.chartSourceGeneration
        if (reference.sourceGeneration != current) return ChartObjectText(name, message = "Kartenquelle hat sich geändert")
        val pack = mutable.value.snapshot.active?.packageId ?: return ChartObjectText(name, message = "Keine aktive Kartenquelle")
        val result = library.readObjectText(pack, cellName, reference.sourceKey, name)
        return if (engine.status.value.chartSourceGeneration == current) result else ChartObjectText(name, message = "Kartenquelle hat sich geändert")
    }
    override val supportsResidentSelection get() = engine.supportsResidentCatalog
    override val supportsLibraryManagement get() = true
    private var operation: Job? = null
    private var residentSession: ResidentChartSession? = null
    private var residentBinding: ChartResidentSelection? = null
    private var emptyCatalogGeneration: String? = null
    private var bindingEpoch = 0L
    @Volatile private var closed = false

    private suspend fun stopResidentSession() {
        bindingEpoch++
        residentSession?.stop()
        residentSession = null
    }
    private fun startResidentSession(binding: ChartResidentSelection) {
        if (closed) return
        val epoch = ++bindingEpoch
        residentSession = ResidentChartSession(engine, binding.planning, scope) { update ->
            if (!closed && epoch == bindingEpoch) mutable.update { it.copy(
                residency = update.status ?: it.residency,
                residentMessage = when {
                    !update.result.success -> update.result.message
                    update.step == ChartResidentStep.FAILED -> update.status?.message ?: "Zelle konnte nicht geladen werden"
                    update.step == ChartResidentStep.BLOCKED && (update.status?.missing ?: 0) > 0 ->
                        "${update.status?.missing} benötigte Zellen noch nicht geladen: ${update.status?.message.orEmpty()}"
                    else -> null
                }) }
        }
    }
    private fun residentCamera(binding: ChartResidentSelection, savedCamera: ChartViewport?): ChartViewport =
        savedCamera ?: if (engineId(binding.selection, binding.pack) == "noaa-boston-harbour") engine.status.value.viewport.copy(
            latitude = 42.36, longitude = -71.04, metresToPixels = 0.15, rotationRadians = 0.0)
        else ResidentCatalogGeometry.initial(binding.planning, engine.status.value.viewport)

    /** Validate metadata first; source switch and durable publication have one owner.
     * Rollback uses the whole previous immutable binding, never its resident subset. */
    private suspend fun activateResident(selection: ChartSelection, expected: ChartReleasedSelection? = null,
        persist: Boolean = true) {
        val snapshot = withContext(Dispatchers.IO) { library.snapshot() }
        check(!library.hasPersistedSelection() || snapshot.selectionVersion != null) {
            "Gespeicherte Auswahlversion nicht lesbar; unverändert erhalten"
        }
        val binding = library.residentSelection(selection, expected)
        val previous = residentBinding
        var previousView = engine.status.value.viewport
        // Native ownership may commit before withContext can deliver its result.
        // Keep the source/publication/rollback boundary immune to prompt cancellation.
        withContext(NonCancellable) {
            val previousGeneration = engine.status.value.chartSourceGeneration
            val budget = engine.readResidentBinding()?.activation?.catalog?.budget ?: ChartResidentBudget()
            stopResidentSession()
            previousView = engine.status.value.viewport // Include gestures made during metadata preparation.
            var switched = false
            try {
                check(!closed) { "Kartenverwaltung geschlossen" }
                val savedCamera=restoredCamera(binding.selection)
                val activation = ChartResidentActivation(engineId(selection, binding.pack),
                    ChartResidentAdapter.catalog(binding.planning, binding.pack, binding.dataDirectory, budget),
                    residentCamera(binding,savedCamera), ResidentCatalogGeometry.bounds(binding.planning),
                    ResidentStartCameraPolicy(binding.planning, preferPrevious = savedCamera == null))
                val update = engine.activateResidentCatalog(activation)
                check(update.result.success) { update.result.message }
                switched = true
                if (persist) library.activateResident(binding)
                residentBinding = binding
                emptyCatalogGeneration = null
                mutable.update { it.copy(residency = update.status, residentMessage = null) }
                startResidentSession(binding)
            } catch (failure: Exception) {
                if (previous != null && !closed) {
                    if (switched || engine.status.value.chartSourceGeneration != previousGeneration) {
                        val restored = engine.activateResidentCatalog(ChartResidentActivation(engineId(previous.selection, previous.pack),
                            ChartResidentAdapter.catalog(previous.planning, previous.pack, previous.dataDirectory, budget),
                            previousView, ResidentCatalogGeometry.bounds(previous.planning),
                            ResidentStartCameraPolicy(previous.planning, preferPrevious = false)))
                        check(restored.result.success) { "${failure.message}; vorheriger Rendererbestand konnte nicht wiederhergestellt werden: ${restored.result.message}" }
                        mutable.update { it.copy(residency = restored.status) }
                    }
                    startResidentSession(previous)
                }
                throw failure
            }
        }
    }
    private suspend fun validate(paths: List<File>, cache: File) {
        val result = engine.validateChartFiles(paths.map { it.absolutePath }, cache.absolutePath)
        check(result.success) { result.message }
    }
    private fun importProgress(value: ImportProgress) {
        mutable.update { state ->
            val cancelling = state.importProgress?.activity == ImportActivity.CANCELLING
            state.copy(importProgress = if (cancelling && value.activity == ImportActivity.RUNNING)
                value.copy(activity = ImportActivity.CANCELLING) else value)
        }
    }
    private suspend fun validateImport(paths: List<File>, cache: File, progress: (Int, File?) -> Unit): List<ValidatedChartCell> {
        // One worker invocation per cell exposes actual completion, and gives cancellation a boundary.
        val metadata = mutableListOf<ValidatedChartCell>()
        for ((index, path) in paths.withIndex()) {
            currentCoroutineContext().ensureActive()
            progress(index, path)
            val nativeCache = File(cache, index.toString()).absolutePath
            // ChartLibrary admits .senc only after checking the Pi manifest, provenance and both file pins.
            val result = if (path.extension == "senc")
                engine.validatePiCacheFilesWithMetadata(listOf(path.absolutePath), nativeCache)
            else engine.validateChartFilesWithMetadata(listOf(path.absolutePath), nativeCache)
            check(result.result.success) { "${path.name}: ${result.result.message}" }
            metadata += result.cells
            currentCoroutineContext().ensureActive()
            progress(index + 1, null)
        }
        return metadata
    }
    suspend fun prepare() = withContext(Dispatchers.IO) {
        var notice: String? = null
        var loadedDuringMigration: ChartSelection? = null
        if (engine.status.value.rendererBenchmarkRecoveryPending) {
            mutable.update { it.copy(snapshot = library.snapshot(), message = "Messwiederherstellung ausstehend; gespeicherte Kartenauswahl wird erst danach geladen.") }
            return@withContext
        }
        val initial = library.snapshot()
        if (library.hasPersistedSelection() && initial.selectionVersion == null) {
            mutable.update { it.copy(snapshot = initial, message = "Gespeicherte Kartenverwaltung nicht lesbar; Dateien und Auswahl unverändert erhalten.") }
            return@withContext
        }
        if (!library.migrated()) {
            val legacy = File(context.filesDir, "chart-data/charts/noaa")
            if (legacy.isDirectory && legacy.listFiles().orEmpty().any { it.extension == "000" }) {
                try {
                    var migrated = library.snapshot().packages.find { it.label == "Boston · aus vorheriger Installation gesichert" }
                    if (migrated == null) {
                        val archive = File(context.cacheDir, "no5-legacy-migration.zip")
                        try {
                            ZipOutputStream(archive.outputStream()).use { zip ->
                                legacy.walkTopDown().filter { it.isFile }.forEach { file ->
                                    currentCoroutineContext().ensureActive()
                                    zip.putNextEntry(ZipEntry(file.relativeTo(legacy).invariantSeparatorsPath)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                                }
                            }
                            migrated = library.importZip("Boston · aus vorheriger Installation gesichert", archive.inputStream(), ::validate)
                        } finally { archive.delete() }
                    }
                    if (library.snapshot().active == null && !library.hasPersistedSelection()) {
                        val selection = ChartSelection(migrated.id, migrated.cells.filterNot { it.cancelled }.map { it.name })
                        if (supportsResidentSelection) activateResident(selection)
                        else { loadSelection(selection); library.activate(selection) }
                        loadedDuringMigration = selection
                    }
                    library.markMigrated()
                    notice = "Vorhandene Boston-Karten gesichert. Originaldateien bleiben erhalten."
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { notice = "Migration fehlgeschlagen: ${failure.message}. Originalbestand erhalten; Import bleibt möglich." }
            } else library.markMigrated()
        }
        // Optional region previews must not delay loading the active map. Old packages without a
        // spatial sidecar stay selectable as unlocated; new imports already carry their sidecar.
        // Received EDTN0 receipts are applied before restoring any old active source.
        library.prepareCancellationCleanup(supportsResidentSelection)?.let { plan ->
            try { applyMutation(plan); notice = plan.description }
            catch (failure: Exception) { notice = "Abgekündigte Quellen werden nicht geladen; Bereinigung noch offen: ${failure.message}" }
        }
        val snapshot = library.snapshot()
        snapshot.active?.let { selection ->
            try { if (selection != loadedDuringMigration) {
                if (supportsResidentSelection) activateResident(selection, snapshot.activeRelease, persist = snapshot.selectionVersion != 2)
                else loadSelection(selection)
            } }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice = "Gespeicherte Auswahl nicht geladen: ${failure.message}. Kartenbestand erhalten." }
        }
        if (library.hasPendingCleanup()) {
            try {
                stopResidentSession()
                val released = releaseCheckpoint()
                val result = library.finishCleanup(released)
                if (!result.complete) notice = result.message
            } catch (failure: Exception) { notice = "Dateibereinigung noch offen: ${failure.message}" }
            finally { residentBinding?.let { if (residentSession == null) startResidentSession(it) } }
        }
        mutable.update { it.copy(snapshot = library.snapshot(), message = notice) }
    }
    private fun engineId(selection: ChartSelection, pack: ChartPackage): String =
        if (pack.piCache == null && pack.cells.all { it.provider == "noaa-s57" } &&
            selection.cells.toSet() == setOf("US5BOSCC", "US5BOSCD", "US5BOSDC", "US5BOSDD"))
            "noaa-boston-harbour" else "imported-${selection.packageId}"
    private fun engineId(selection: ChartSelection, source: ChartSource): String =
        if (source is ChartSource.PublicFiles && selection.cells.toSet() == setOf("US5BOSCC", "US5BOSCD", "US5BOSDC", "US5BOSDD"))
            "noaa-boston-harbour" else "imported-${selection.packageId}"
    private suspend fun loadSelection(selection: ChartSelection, cameraOverride: ChartViewport? = null) {
        val source = withContext(Dispatchers.IO) {
            val paths = library.paths(selection).map { it.absolutePath }
            val pack = library.snapshot().packages.single { it.id == selection.packageId }
            val provenance = pack.piCache
            val background = pack.cells.filter { it.name in selection.cells && it.provider == com.no5.helm.chart.BACKGROUND_CACHE_PROVIDER }
                .map { cell -> paths.single { File(it).name == "${cell.name}.senc" } }
            if (provenance != null) ChartSource.PiCacheFiles(paths, provenance.licenseHost, provenance.exportedUtc, background)
            else ChartSource.PublicFiles(paths)
        }
        val result = engine.loadChartSource(engineId(selection, source), source)
        check(result.success) { result.message }
        val camera = cameraOverride ?: restoredCamera(selection) ?: if (engineId(selection, source) == "noaa-boston-harbour")
            ChartViewport(latitude = 42.36, longitude = -71.04, metresToPixels = 0.15) else null
        if(camera!=null)takeCameraOwnership(camera)
    }
    /** Source publication/rollback needs ownership, not merely a place in the asynchronous inbox. */
    private suspend fun takeCameraOwnership(camera:ChartViewport) {
        val expected=engine.status.value
        val owned=if(!expected.surfaceAttached)engine.restoreCameraViewport(camera,expected.chartSourceGeneration)
        else {
            val id=engine.submitCameraViewport(camera,0,android.os.SystemClock.uptimeMillis())
            if(id<=0)false else {
                val result=ChartControlResult()
                withTimeoutOrNull(1000) {
                    while(engine.cameraControlResult(id,result)&&result.state<2&&result.revision<0)delay(1)
                    result.id==id&&result.revision>=0&&result.state in 1..2
                }==true
            }
        }
        val actual=engine.status.value
        check(owned&&actual.chartSourceGeneration==expected.chartSourceGeneration&&
            kotlin.math.abs(actual.viewport.latitude-camera.latitude)<1e-9&&
            kotlin.math.abs(actual.viewport.longitude-camera.longitude)<1e-9&&
            kotlin.math.abs(actual.viewport.metresToPixels-camera.metresToPixels)<1e-9&&
            kotlin.math.abs(kotlin.math.atan2(kotlin.math.sin(actual.viewport.rotationRadians-camera.rotationRadians),
                kotlin.math.cos(actual.viewport.rotationRadians-camera.rotationRadians)))<1e-9) {
            "Kartenausschnitt nicht vom aktuellen Kamera-Owner übernommen; Quelldateien bleiben erhalten"
        }
    }
    private fun actionAllowed() = !closed && !mutable.value.busy && engine.status.value.let {
        !it.rendererCapture.active && !it.rendererBenchmark.active && !it.rendererBenchmarkRecoveryPending
    }
    /** Only the durable whole-catalog binding is exported. It contains no mutable resident subset. */
    suspend fun captureResidentBinding(): ChartResidentSelection? {
        check(!closed && !mutable.value.busy) { "Kartenverwaltung beschäftigt" }
        return residentBinding
    }
    /** Called after the engine journal reports recovery complete. It never activates a source,
     * republishes persistence, or moves the journal-restored camera. */
    suspend fun reconcileAfterBenchmarkRecovery(): Boolean {
        if (!actionAllowed()) return false
        return try {
            val snapshot = library.snapshot()
            val active = snapshot.active
            if (engine.status.value.residentCatalogActive) {
                check(supportsResidentSelection && active != null) { "Wiederhergestellter Renderer passt nicht zur gespeicherten Auswahl" }
                val binding = library.residentSelection(active, snapshot.activeRelease)
                val restored = engine.readResidency()
                check(restored.result.success && restored.status?.catalogGeneration == binding.planning.generation) {
                    "Wiederhergestellte Quellen stimmen nicht mit den gespeicherten Quellpins überein"
                }
                stopResidentSession(); residentBinding = binding
                check(installCameraGuard(binding)) { "Wiederhergestellte Kameragrenze konnte nicht gebunden werden" }
                mutable.update { it.copy(snapshot = snapshot, residency = restored.status,
                    message = "Kartenauswahl und Renderer nach der Messung wiederhergestellt.") }
                startResidentSession(binding)
            } else {
                check((active == null) == !engine.status.value.chartLoaded) { "Messwiederherstellung und gespeicherte Auswahl stimmen nicht überein" }
                stopResidentSession(); residentBinding = null
                mutable.update { it.copy(snapshot = snapshot, residency = null,
                    message = "Kartenauswahl und Renderer nach der Messung wiederhergestellt.") }
            }
            true
        } catch (failure: Exception) {
            mutable.update { it.copy(message = "Renderer wiederhergestellt; Abgleich des Kartenbestands noch offen: ${failure.message}") }
            false
        }
    }
    /** Engine measurement recovery owns the renderer journal. Reconcile the Activity owner without
     * publishing a selection or replacing its restored camera/settings from an old UI snapshot. */
    suspend fun restoreResidentBinding(expected: ChartResidentSelection, viewport: ChartViewport) {
        val current = library.residentSelection(expected.selection, expected.release)
        require(current.planning.generation == expected.planning.generation) { "Messquellen seit dem Beginn verändert" }
        val status = engine.readResidency()
        check(status.result.success && status.status?.catalogGeneration == expected.planning.generation &&
            !engine.status.value.rendererBenchmarkRecoveryPending) { "Renderer-Messwiederherstellung noch nicht vollständig" }
        stopResidentSession()
        residentBinding = current
        // The engine journal already restored its camera; do not move it a second time.
        require(engine.status.value.viewport == viewport) { "Wiederhergestellte Kamera stimmt nicht mit dem Messjournal überein" }
        check(installCameraGuard(current)) { "Wiederhergestellte Kameragrenze konnte nicht gebunden werden" }
        mutable.update { it.copy(snapshot = library.snapshot(), residency = status.status) }
        startResidentSession(current)
    }
    // Journal callbacks are not serialized. Rebind its actual runtime epoch
    // without selecting a start or moving the journal-restored camera again.
    private fun installCameraGuard(binding: ChartResidentSelection) = engine.installResidentCameraGuard(
        binding.planning.generation, ResidentStartCameraPolicy(binding.planning).bind(engine.status.value.chartSourceGeneration))
    private suspend fun releaseCheckpoint(): Boolean {
        val binding = residentBinding
        return if (engine.status.value.residentCatalogActive) {
            val generation = binding?.planning?.generation ?: emptyCatalogGeneration ?: engine.readResidency().status?.catalogGeneration
            generation != null && engine.releaseRetiredChartSources(generation).success
        }
        else {
            val selected = library.snapshot().active
            val view = engine.status.value.viewport
            val cleared = engine.clearChartSelection(engine.status.value.chartSourceGeneration)
            if (cleared.success && selected != null) { loadSelection(selected,view) }
            cleared.success
        }
    }
    private suspend fun activateEmptyResident(revision: Long, viewport: ChartViewport): String {
        val current = engine.readResidentBinding()
        val generation = MessageDigest.getInstance("SHA-256")
            .digest("no5.empty-chart-selection.v1:$revision:${engine.status.value.chartSourceGeneration}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val activation = ChartResidentActivation("empty-chart-selection", ChartResidentCatalog(generation, emptyList(),
            current?.activation?.catalog?.budget ?: ChartResidentBudget()), viewport, ChartNavigationBounds(0, emptyList()),
            ResidentStartCameraPolicy.empty(generation))
        val result = engine.activateResidentCatalog(activation)
        check(result.result.success) { result.result.message }
        emptyCatalogGeneration = generation
        mutable.update { it.copy(residency = result.status, residentMessage = null) }
        return generation
    }
    private suspend fun applyMutation(plan: ChartLibraryMutation) {
        val previousBinding = residentBinding
        val previousSelection = library.snapshot().active
        var previousView = engine.status.value.viewport
        val previousGeneration = engine.status.value.chartSourceGeneration
        val binding = if (supportsResidentSelection && plan.selection != null)
            library.residentSelection(plan.selection!!, plan.release) else null
        withContext(NonCancellable) {
            val budget = engine.readResidentBinding()?.activation?.catalog?.budget ?: ChartResidentBudget()
            stopResidentSession()
            previousView = engine.status.value.viewport // Keep gestures made during metadata preparation.
            var switched = false
            var published = false
            var newCatalogGeneration: String? = null
            try {
                check(!closed) { "Kartenverwaltung geschlossen" }
                if (binding != null) {
                    val activated = engine.activateResidentCatalog(ChartResidentActivation(engineId(binding.selection, binding.pack),
                        ChartResidentAdapter.catalog(binding.planning, binding.pack, binding.dataDirectory, budget),
                        previousView, ResidentCatalogGeometry.bounds(binding.planning),
                        ResidentStartCameraPolicy(binding.planning)))
                    check(activated.result.success) { activated.result.message }
                    switched = true
                    newCatalogGeneration = binding.planning.generation
                    mutable.update { it.copy(residency = activated.status, residentMessage = null) }
                } else if (supportsResidentSelection && plan.selection == null) {
                    newCatalogGeneration = activateEmptyResident(plan.revision, previousView)
                    switched = true
                } else {
                    val cleared = engine.clearChartSelection(engine.status.value.chartSourceGeneration)
                    check(cleared.success) { cleared.message.ifBlank { "Renderer hat alte Quellen noch nicht freigegeben" } }
                    switched = true
                    // Eager replacement is opened only after the old owner/file release.
                    plan.selection?.let { loadSelection(it,previousView) }
                    mutable.update { it.copy(residency = null, residentMessage = null) }
                }
                switched = true
                library.publishMutation(plan)
                published = true
                residentBinding = binding
                if (binding != null) emptyCatalogGeneration = null
                val release = newCatalogGeneration?.let { engine.releaseRetiredChartSources(it).success } ?: true
                val cleanup = library.finishCleanup(release)
                mutable.update { it.copy(snapshot = library.snapshot(), activationRevision = it.activationRevision + 1,
                    message = plan.description + if (!cleanup.complete) " ${cleanup.message}" else "") }
            } catch (failure: Exception) {
                if (!published) {
                    if (plan.confirmedCancellation) {
                        // A verified withdrawal is never a permissible rollback target.
                        if (supportsResidentSelection) activateEmptyResident(plan.revision, previousView)
                        else engine.clearChartSelection(engine.status.value.chartSourceGeneration)
                        residentBinding = null
                    } else if ((switched || engine.status.value.chartSourceGeneration != previousGeneration) && !closed) {
                        if (previousBinding != null) {
                            val restored = engine.activateResidentCatalog(ChartResidentActivation(engineId(previousBinding.selection, previousBinding.pack),
                                ChartResidentAdapter.catalog(previousBinding.planning, previousBinding.pack,
                                    previousBinding.dataDirectory, budget), previousView,
                                ResidentCatalogGeometry.bounds(previousBinding.planning),
                                ResidentStartCameraPolicy(previousBinding.planning, preferPrevious = false)))
                            check(restored.result.success) { "${failure.message}; vorheriger Rendererbestand konnte nicht wiederhergestellt werden: ${restored.result.message}" }
                            residentBinding = previousBinding
                            emptyCatalogGeneration = null
                            mutable.update { it.copy(residency = restored.status) }
                        } else if (previousSelection != null) {
                            loadSelection(previousSelection,previousView)
                            residentBinding = null
                        }
                    }
                }
                mutable.update { it.copy(snapshot = library.snapshot(), message = if (published)
                    "Auswahl umgestellt; Dateibereinigung noch offen: ${failure.message}" else "Aktion nicht abgeschlossen: ${failure.message}. Quelldateien erhalten.") }
                throw failure
            } finally {
                residentBinding?.let { if (residentSession == null) startResidentSession(it) }
            }
        }
    }
    private fun management(progress: String, prepare: suspend () -> ChartLibraryMutation) {
        if (!actionAllowed()) return
        mutable.update { it.copy(busy = true, progress = progress, importProgress = null, message = null) }
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { applyMutation(prepare()) }
            catch (cancel: CancellationException) { mutable.update { it.copy(message = "Aktion abgebrochen; Dateien erhalten.") }; throw cancel }
            catch (failure: Exception) { mutable.update { it.copy(message = it.message ?: "Aktion fehlgeschlagen: ${failure.message}. Dateien erhalten.") } }
            finally { mutable.update { it.copy(busy = false, progress = "") } }
        }
    }
    override fun deactivate(selection: ChartSelection) = management("Zellen werden aus der aktiven Auswahl entfernt …") {
        library.prepareDeactivate(selection, supportsResidentSelection)
    }
    override fun deletePackage(packageId: String) = management("Kartenpaket wird sicher freigegeben und gelöscht …") {
        library.prepareDeletePackage(packageId, supportsResidentSelection)
    }
    override fun importPackage(uri: Uri) = importPackageInternal(uri, null)
    override fun updatePackage(packageId: String, uri: Uri) = importPackageInternal(uri, packageId)
    private fun importPackageInternal(uri: Uri, updatePackageId: String?) {
        if (!actionAllowed()) return
        if (updatePackageId != null && mutable.value.snapshot.packages.none { it.id == updatePackageId }) return
        mutable.update { it.copy(busy = true, progress = "", importProgress = ImportProgress(), message = null, importSourceLabel = null) }
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // Import validation and resident ingestion share one native owner.
                stopResidentSession()
                val (imported, snapshot) = withContext(Dispatchers.IO) {
                    var name = "S57-Kartenpaket"
                    var observedName: String? = null
                    var bytes: Long? = null
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                                observedName = cursor.getString(nameColumn).takeIf { it.isNotBlank() }
                                observedName?.let { name = it }
                            }
                            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) bytes = cursor.getLong(sizeColumn).takeIf { it >= 0 }
                        }
                    }
                    // Preserve the observed archive name separately from the current cell.
                    // It is raw user/provider data, never a translated example filename.
                    mutable.update { it.copy(importSourceLabel = observedName) }
                    val input = context.contentResolver.openInputStream(uri) ?: error("Datei nicht lesbar")
                    val stored = library.importZipWithMetadata(name, input, ::validateImport, ::importProgress, bytes)
                    stored to library.snapshot()
                }
                if (updatePackageId != null) {
                    val replacement = library.mergeUpdatePackage(updatePackageId, imported.id)
                    applyMutation(library.prepareUpdatePackage(updatePackageId, replacement.id, imported.id, supportsResidentSelection))
                } else {
                    val cancellation = library.prepareCancellationCleanup(supportsResidentSelection)
                    if (cancellation != null) applyMutation(cancellation)
                    else mutable.update { it.copy(snapshot = snapshot,
                        message = "${imported.cells.size} Zellen gespeichert. Zellen auswählen und aktivieren.") }
                }
            } catch (cancel: CancellationException) {
                val published = mutable.value.importProgress?.activity == ImportActivity.COMPLETED
                val snapshot = if (published) withContext(NonCancellable + Dispatchers.IO) { library.snapshot() } else mutable.value.snapshot
                mutable.update { it.copy(snapshot = snapshot,
                    importProgress = it.importProgress?.copy(activity = if (published) ImportActivity.COMPLETED else ImportActivity.CANCELLED),
                    message = if (published) it.message ?: "Kartenpaket gespeichert; vorbereitete Bestandsaktion noch nicht abgeschlossen." else "Import abgebrochen; aktiver Bestand unverändert.") }
                throw cancel
            }
            catch (failure: Exception) {
                val stored = mutable.value.importProgress?.activity == ImportActivity.COMPLETED
                mutable.update { it.copy(snapshot = if (stored) library.snapshot() else it.snapshot,
                    importProgress = it.importProgress?.copy(activity = if (stored) ImportActivity.COMPLETED else ImportActivity.FAILED,
                        error = it.importProgress?.error ?: failure.message ?: "Datei nicht lesbar"),
                    message = if (stored) it.message ?: "Geprüfter Import separat gespeichert; Zielaktion nicht abgeschlossen: ${failure.message}. Dateien erhalten."
                        else "Import fehlgeschlagen: ${failure.message}. Bestand unverändert.") }
            }
            finally {
                residentBinding?.let { if (residentSession == null) startResidentSession(it) }
                mutable.update { it.copy(busy = false, progress = "") }
            }
        }
    }
    override fun activate(selection: ChartSelection) {
        if (closed || mutable.value.busy || engine.status.value.rendererCapture.active || engine.status.value.rendererBenchmark.active || engine.status.value.rendererBenchmarkRecoveryPending) return
        mutable.update { it.copy(busy = true, progress = "Karten werden aktiviert …", importProgress = null, message = null) }
        operation = scope.launch {
            val previous = mutable.value.snapshot.active
            val previousView = engine.status.value.viewport
            try {
                // Load and publish as one non-cancellable transaction. Old immutable versions stay available.
                withContext(NonCancellable) {
                    try {
                        if (supportsResidentSelection) activateResident(selection)
                        else { loadSelection(selection); withContext(Dispatchers.IO) { library.activate(selection) } }
                    } catch (failure: Exception) {
                        if (!supportsResidentSelection && previous != null) { loadSelection(previous,previousView) }
                        throw failure
                    }
                    mutable.update { it.copy(snapshot = library.snapshot(), activationRevision = it.activationRevision + 1,
                        message = "${selection.cells.size} Zellen aktiviert · offline verfügbar.") }
                }
            } catch (failure: Exception) { mutable.update { it.copy(message = "Aktivierung fehlgeschlagen: ${failure.message}. Gespeicherter Bestand bleibt erhalten.") } }
            finally { mutable.update { it.copy(busy = false, progress = "") } }
        }
    }
    override fun cancelImport() {
        if (mutable.value.importProgress?.canCancel != true) return
        mutable.update { it.copy(importProgress = it.importProgress?.copy(activity = ImportActivity.CANCELLING)) }
        operation?.cancel()
    }
    fun close() {
        closed = true; bindingEpoch++
        (residentBinding?.planning?.generation ?: emptyCatalogGeneration)?.let { engine.installResidentCameraGuard(it, null) }
        operation?.cancel()
        residentSession?.close(); residentSession = null
    }
}
