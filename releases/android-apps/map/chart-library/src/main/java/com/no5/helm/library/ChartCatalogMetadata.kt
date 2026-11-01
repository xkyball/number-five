package com.no5.helm.library

import com.no5.helm.chart.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable data class CatalogCoordinate(val latitude: Double, val longitude: Double)
@Serializable data class CatalogCoverageCapabilities(
    val exteriorRingsOnly: Boolean, val polygonHoles: Boolean, val multiPolygons: Boolean,
    val wrappedDateline: Boolean, val latitudeLimit: Double, val coordinatePrecision: String,
)
/** Sidecar metadata is optional. Absence/invalidity must never mean that a cell is absent. */
@Serializable data class ChartCatalogMetadata(
    val name: String, val files: Map<String, String>, val sourceKey: String, val order: Int,
    val metadataVersion: Int, val parserVersion: String, val parserIdentity: String,
    val preparedSourceKey: String, val coverageVersion: Int,
    val nativeScale: Int?, val usage: Int?, val nativeBounds: ChartSelectionExtent?,
    val conservativeBounds: ChartSelectionExtent?,
    val coverage: List<List<CatalogCoordinate>>, val exclusions: List<List<CatalogCoordinate>>,
    val capabilities: CatalogCoverageCapabilities,
    val provider: String = "noaa-s57",
) {
    fun validated(path: String) = ValidatedChartCell(path, metadataVersion, parserVersion, parserIdentity,
        preparedSourceKey, coverageVersion, nativeScale, usage,
        nativeBounds?.let { ChartCellBounds(it.south, it.west, it.north, it.east) },
        coverage.map { ring -> ring.map { ChartCellCoordinate(it.latitude, it.longitude) } },
        exclusions.map { ring -> ring.map { ChartCellCoordinate(it.latitude, it.longitude) } },
        capabilities.let { ChartCoverageCapabilities(it.exteriorRingsOnly, it.polygonHoles, it.multiPolygons,
            it.wrappedDateline, it.latitudeLimit, it.coordinatePrecision) })
}
@Serializable internal data class ChartCatalogIndex(val version: Int = 2, val cells: List<ChartCatalogMetadata>)
@Serializable private data class SourceFile(val name: String, val sha256: String)
@Serializable private data class SourceIdentity(val provider: String = "noaa-s57", val cell: String,
    val edition: Int, val update: Int, val files: List<SourceFile>)
@Serializable private data class PiSourceIdentity(val provider: String, val product: String,
    val licenseHost: String, val cell: String, val edition: Int, val update: Int, val files: List<SourceFile>)

object ChartMetadataContract {
    private val hash = Regex("[a-f0-9]{64}")
    private val sourceJson = Json { encodeDefaults = true }
    fun usable(value: ValidatedChartCell, provider: String = "noaa-s57"): Boolean {
        if (provider != PI_CACHE_PROVIDER && provider != BACKGROUND_CACHE_PROVIDER && !S57Profiles.supportedProvider(provider)) return false
        val c = value.capabilities
        if (value.metadataVersion != 1 || value.coverageVersion != 1 ||
            value.parserVersion != "no5-s57-osenc201-metadata-v1" || !hash.matches(value.parserIdentity) ||
            !hash.matches(value.preparedSourceKey) || c != ChartCoverageCapabilities()) return false
        if (value.nativeScale != null && value.nativeScale !in 1..100_000_000 ||
            value.usage != null && value.usage !in (if (provider == S57Profiles.WSV) 7..9 else 1..6)) return false
        val b = value.bounds
        if (b != null && (!listOf(b.south, b.west, b.north, b.east).all { it.isFinite() } ||
            b.south !in -85.0..85.0 || b.north !in b.south..85.0 || b.west !in -180.0..180.0 || b.east !in b.west..180.0)) return false
        return (value.coverage + value.exclusions).all { ring -> ring.size >= 3 && ring.all {
            it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -85.0..85.0 && it.longitude in -180.0..180.0
        } }
    }
    internal fun provider(provenance: PiCacheProvenance?, publicProvider: String = "noaa-s57") =
        if (publicProvider == BACKGROUND_CACHE_PROVIDER || provenance == null) publicProvider else PI_CACHE_PROVIDER
    fun sourceKey(cell: ChartCell, provenance: PiCacheProvenance? = cell.piCache): String {
        val origin = provenance.takeUnless { cell.provider == BACKGROUND_CACHE_PROVIDER }
        val files = (if (origin != null || cell.provider == BACKGROUND_CACHE_PROVIDER) PiCachePackage.residentFiles(cell) else
            cell.files.entries.sortedBy { File(it.key).extension.toIntOrNull() ?: Int.MAX_VALUE })
            .map { SourceFile(File(it.key).name, it.value) }
        // Preserve existing NOAA keys. Private generations also bind to their licensed product/host;
        // re-exporting identical bytes at a different time does not create a new source identity.
        val identity = if (origin == null)
            sourceJson.encodeToString(SourceIdentity(provider = cell.provider, cell = cell.name, edition = cell.edition, update = cell.update, files = files))
        else sourceJson.encodeToString(PiSourceIdentity(provider(origin), origin.product,
            origin.licenseHost, cell.name, cell.edition, cell.update, files))
        val bytes = identity.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    internal fun matches(metadata: ChartCatalogMetadata, cell: ChartCell, order: Int, provenance: PiCacheProvenance? = cell.piCache): Boolean =
        !cell.cancelled && metadata.provider == provider(provenance, cell.provider) && metadata.name == cell.name && metadata.files == cell.files && metadata.order == order &&
            metadata.sourceKey == sourceKey(cell, provenance) && cell.files.values.all { hash.matches(it) } &&
            usable(metadata.validated(cell.base), provider(provenance, cell.provider)) &&
            (metadata.conservativeBounds?.let { it.valid && contains(it, metadata.nativeBounds) && contains(it, cell.selectionExtent) }
                ?: (metadata.nativeBounds == null && cell.selectionExtent == null))

    private fun contains(outer: ChartSelectionExtent, inner: ChartSelectionExtent?): Boolean = inner == null ||
        outer.south <= inner.south && outer.north >= inner.north && outer.west <= inner.west && outer.east >= inner.east

    internal fun attach(cell: ChartCell, order: Int, value: ValidatedChartCell, provenance: PiCacheProvenance? = cell.piCache): ChartCatalogMetadata {
        require(usable(value, provider(provenance, cell.provider)))
        val bounds = value.bounds?.let { ChartSelectionExtent(it.south, it.north, it.west, it.east) }
        // The v1 envelope includes deleted/update coordinates. Keep that conservative
        // information; a wrapped legacy envelope falls back to a whole-world longitude.
        val extents = listOfNotNull(bounds, cell.selectionExtent).filter { it.valid }
        val conservative = extents.takeIf { it.isNotEmpty() }?.let { all ->
            val wrapped = all.any { it.west > it.east }
            ChartSelectionExtent(all.minOf { it.south }, all.maxOf { it.north },
                if (wrapped) -180.0 else all.minOf { it.west }, if (wrapped) 180.0 else all.maxOf { it.east })
        }
        return ChartCatalogMetadata(cell.name, cell.files, sourceKey(cell, provenance), order, value.metadataVersion,
            value.parserVersion, value.parserIdentity, value.preparedSourceKey, value.coverageVersion,
            value.nativeScale, value.usage, bounds, conservative,
            value.coverage.map { r -> r.map { CatalogCoordinate(it.latitude, it.longitude) } },
            value.exclusions.map { r -> r.map { CatalogCoordinate(it.latitude, it.longitude) } },
            value.capabilities.let { CatalogCoverageCapabilities(it.exteriorRingsOnly, it.polygonHoles,
                it.multiPolygons, it.wrappedDateline, it.latitudeLimit, it.coordinatePrecision) }, provider(provenance, cell.provider))
    }
}
