package com.no5.helm.engine

import com.no5.helm.chart.*
import java.util.Collections
import java.io.File
import java.security.MessageDigest

/** Owner-confined source/content authority. Old pixels may remain affine-transformable,
 * but they cannot authorize queries against a different geometry generation. */
internal class ChartResidentOwner {
    @Volatile var sourceGeneration = 0L; private set
    @Volatile var contentGeneration = 0L; private set
    var checkpointRevision = -1L; private set
    @Volatile var catalog: ChartResidentCatalog? = null; private set
    var navigationBounds: ChartNavigationBounds? = null; private set
    private var nativeRevision = -1L
    val active: Boolean get() = catalog != null
    fun activate(catalog: ChartResidentCatalog, bounds: ChartNavigationBounds?, status: ChartResidencyStatus) {
        require(status.enabled && status.catalogGeneration == catalog.generation)
        this.catalog = catalog; navigationBounds = bounds
        sourceGeneration++; contentGeneration++; nativeRevision = status.contentRevision; checkpointRevision = -1
    }
    fun eager() {
        catalog = null; navigationBounds = null; nativeRevision = -1
        sourceGeneration++; contentGeneration++; checkpointRevision = -1
    }
    /** Budget arms change no source identity. Immutable descriptors retain their authority. */
    fun budget(value: ChartResidentBudget) { catalog = checkNotNull(catalog).copy(budget = value) }
    fun update(status: ChartResidencyStatus): Boolean {
        require(status.enabled && status.catalogGeneration == catalog?.generation) { "Resident source authority mismatch" }
        if (status.contentRevision == nativeRevision) return false
        nativeRevision = status.contentRevision; contentGeneration++; checkpointRevision = -1
        return true
    }
    fun frameMatches(frame: ChartFrame): Boolean = frame.sourceGeneration == sourceGeneration &&
        frame.contentGeneration == contentGeneration
    /** A finished FULL producer image, not an acquired texture or physical-display claim. */
    fun completed(frame: ChartFrame, full: Boolean) {
        if (active && full && frameMatches(frame)) checkpointRevision = nativeRevision
    }
    fun completed(buffer: ChartBufferSnapshot) {
        if (buffer.producerCompleted) completed(buffer.frame, buffer.stage == ChartContentStage.FULL)
    }
    fun invalidateCheckpoint() { checkpointRevision = -1 }
    fun loadedNames(status: ChartResidencyStatus): List<String> {
        val names = catalog?.sources?.associate { it.sourceKey to it.name }.orEmpty()
        return status.sources.map { checkNotNull(names[it]) { "Unknown resident source" } }
    }
}

/** Validate and freeze before any native source mutation. No chart byte IO happens here. */
internal fun residentActivationSnapshot(value: ChartResidentActivation): ChartResidentActivation {
    val view = value.initialViewport
    require(value.id.isNotBlank() && '\u0000' !in value.id)
    require(view.latitude.isFinite() && view.latitude in -85.0..85.0 && view.longitude.isFinite() &&
        view.longitude in -180.0..180.0 && view.metresToPixels.isFinite() && view.metresToPixels > 0 &&
        view.rotationRadians.isFinite() && view.width > 0 && view.height > 0) { "Invalid initial chart camera" }
    val sources = value.catalog.sources.map {
        require(it.provider == "noaa-s57" || (it.provider == BACKGROUND_CACHE_PROVIDER && validBackgroundResidentSource(it)) || (it.provider == PI_CACHE_PROVIDER && validPiResidentSource(it)) || (it.provider == "wsv-inland-s57" &&
            it.name.matches(Regex("1W[7-9][A-Z0-9]{5}")))) { "Unsupported resident source provider/profile" }
        it.copy(files = Collections.unmodifiableList(it.files.toList()))
    }
    val bounds = value.navigationBounds.cells.toList()
    require(bounds.map { it.cellId }.toSet().size == bounds.size && (bounds.isEmpty() ||
        bounds.map { it.cellId }.toSet() == sources.map { it.name }.toSet())) { "Navigation bounds must include the whole released catalog or be explicitly unknown" }
    for (cell in bounds) require(cell.south.isFinite() && cell.north.isFinite() && cell.west.isFinite() &&
        cell.east.isFinite() && cell.south in -90.0..90.0 && cell.north in -90.0..90.0 &&
        cell.south <= cell.north && cell.west in -180.0..180.0 && cell.east in -180.0..180.0) { "Invalid navigation bounds" }
    return value.copy(catalog = value.catalog.copy(sources = Collections.unmodifiableList(sources)),
        navigationBounds = value.navigationBounds.copy(cells = Collections.unmodifiableList(bounds)))
}

internal fun residentOperationBlocker(state: ChartEngineStatus, closed: Boolean, initialized: Boolean,
    profiling: Boolean, benchmarkAdmitted: Boolean): String? = when {
    closed || !initialized -> "Renderer nicht initialisiert"
    profiling || benchmarkAdmitted || state.rendererCapture.active || state.rendererBenchmark.active ||
        state.rendererBenchmarkRecoveryPending -> "Messung oder Wiederherstellung zuerst abschließen"
    else -> null
}

/** Narrow allowance for the single frozen capacity owner. Mutation APIs never use it. */
internal fun residentCapacityLeaseMatches(state: ChartEngineStatus, runId: String?, samplingRunId: String?,
    runKind: RendererRunKind?, arm: ResidentCapacityArm?, sourceGeneration: Long,
    catalog: ChartResidentCatalog?, closed: Boolean, initialized: Boolean, profiling: Boolean, cancelled: Boolean): Boolean =
    runId != null && runId == samplingRunId && runKind == RendererRunKind.RESIDENT_CAPACITY && arm != null &&
        !closed && initialized && !profiling && !cancelled && !state.rendererCapture.active &&
        !state.rendererBenchmarkRecoveryPending && state.rendererBenchmark.active &&
        state.rendererBenchmark.runId == runId && state.rendererBenchmark.runKind == runKind &&
        state.rendererBenchmark.phase == "sampling" && sourceGeneration == arm.expectedSourceGeneration &&
        catalog?.generation == arm.expectedCatalogGeneration && catalog?.budget == arm.budget

/** Whole-catalog recovery preflight. Run only at prepare/restore, never per camera or frame. */
internal fun verifyResidentSourcePins(activation: ChartResidentActivation, checkpoint: () -> Unit = {}) {
    for (source in activation.catalog.sources) {
        val background = source.provider == BACKGROUND_CACHE_PROVIDER
        val pi = source.provider == PI_CACHE_PROVIDER || background
        check(if (background) validBackgroundResidentSource(source) else if (pi) validPiResidentSource(source) else source.files.size == source.update + 1 && source.files.isNotEmpty())
        val first = File(source.files.first().path)
        for ((index, pinned) in source.files.withIndex()) {
            checkpoint()
            val file = File(pinned.path)
            check(file.isFile && (pi || file.parentFile == first.parentFile &&
                file.name == "%s.%03d".format(java.util.Locale.ROOT, source.name, index))) { "Kartenquelle fehlt oder Kette verändert" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkpoint()
                    val count = input.read(buffer); if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == pinned.sha256) { "Kartenquelle stimmt nicht mit dem gesicherten Stand überein" }
        }
        if (pi) continue
        val siblings = checkNotNull(first.parentFile.list()) { "Kartenverzeichnis nicht lesbar" }
        check(siblings.none { name ->
            name.startsWith(source.name + ".") && name.substringAfterLast('.').let {
                it.length == 3 && it.all(Char::isDigit) && it.toInt() > source.update
            }
        }) { "Ungepinntes Kartenupdate vorhanden" }
    }
}

/** A private export pins the SENC and its provenance, rather than a public numeric update chain. */
internal fun validPiResidentSource(source: ChartResidentSource): Boolean {
    val product = source.product
    if (product.isNullOrBlank() || product.length > 200 || '\u0000' in product ||
        source.licenseHost?.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) != true ||
        !source.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")) ||
        source.edition !in 1..65535 || source.update !in 0..65535 || source.files.size != 2) return false
    return validPreparedResidentFiles(source)
}

internal fun validBackgroundResidentSource(source: ChartResidentSource): Boolean =
    source.provider == BACKGROUND_CACHE_PROVIDER && source.product == null && source.licenseHost == null &&
        source.name.matches(Regex("NO5BG_[NS][0-9]{2}_[EW][0-9]{3}")) && source.edition == 1 &&
        source.update == 0 && source.files.size == 2 && validPreparedResidentFiles(source)

private fun validPreparedResidentFiles(source: ChartResidentSource): Boolean {
    val senc = File(source.files[0].path)
    val provenance = File(source.files[1].path)
    val cells = senc.parentFile ?: return false
    val proofs = provenance.parentFile ?: return false
    return senc.isAbsolute && provenance.isAbsolute && senc.name == "${source.name}.senc" &&
        provenance.name == "${source.name}.json" && cells.name == "cells" &&
        proofs.name == "provenance" && cells.parentFile == proofs.parentFile &&
        source.files.all { '\u0000' !in it.path && it.sha256.matches(Regex("[a-f0-9]{64}")) }
}
