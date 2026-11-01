package com.no5.helm.library

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.*

/** Content identity, never a file path, resident slot or camera revision. */
data class ChartCellKey(val provider: String, val name: String, val sourceKey: String)
data class ChartPlanCell(val key: ChartCellKey, val order: Int, val bounds: ChartSelectionExtent?,
    val metadata: ChartCatalogMetadata?)

/** Immutable metadata generation. Construction hashes metadata, queries do no IO or native work. */
class ChartPlanningCatalog private constructor(val generation: String, cells: List<ChartPlanCell>) {
    val cells: List<ChartPlanCell> = Collections.unmodifiableList(cells.toList())
    companion object {
        /** Does not activate, verify chart bytes, remove the existing cap or load any cell. */
        fun fromPackage(pack: ChartPackage, releasedNames: Set<String>): ChartPlanningCatalog {
            require(pack.cells.map { it.name }.distinct().size == pack.cells.size)
            require(pack.cells.map { it.name }.containsAll(releasedNames))
            val json = Json { encodeDefaults = true }
            val digest = MessageDigest.getInstance("SHA-256")
            fun field(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update("${bytes.size}:".toByteArray()); digest.update(bytes)
            }
            field("no5.planning-catalog.v1"); field(pack.id)
            val cells = pack.cells.mapIndexedNotNull { order, cell ->
                if (cell.name !in releasedNames || cell.cancelled) return@mapIndexedNotNull null
                val sourceKey = ChartMetadataContract.sourceKey(cell, pack.piCache)
                // A caller may supply an in-memory package. Apply the same sidecar contract
                // as readPackage; malformed/stale metadata becomes unknown, never absence.
                val metadata = cell.catalogMetadata?.takeIf { ChartMetadataContract.matches(it, cell, order, pack.piCache) }
                    ?.let { it.copy(files = Collections.unmodifiableMap(it.files.toMap()),
                        coverage = freezeRings(it.coverage), exclusions = freezeRings(it.exclusions)) }
                val bounds = metadata?.conservativeBounds ?: cell.selectionExtent?.takeIf { it.valid }
                val key = ChartCellKey(ChartMetadataContract.provider(pack.piCache, cell.provider), cell.name, sourceKey)
                field(order.toString()); field(sourceKey); field(json.encodeToString(bounds)); field(json.encodeToString(metadata))
                ChartPlanCell(key, order, bounds, metadata)
            }
            return ChartPlanningCatalog(digest.digest().joinToString("") { "%02x".format(it) }, cells)
        }
    }
}
private fun freezeRings(rings: List<List<CatalogCoordinate>>): List<List<CatalogCoordinate>> =
    Collections.unmodifiableList(rings.map { Collections.unmodifiableList(it.toList()) })

/** Existing candidate-index contract: ordered unique indices, inclusive bounds, dateline aliases.
 * Additional bbox candidates are permitted. This is not a native coverage/quilting oracle. */
interface ChartCellCandidateIndex {
    val generation: String
    fun query(bounds: ChartSelectionExtent): List<Int>
}
class FullScanChartCellIndex(private val catalog: ChartPlanningCatalog) : ChartCellCandidateIndex {
    override val generation = catalog.generation
    override fun query(bounds: ChartSelectionExtent): List<Int> {
        require(bounds.valid)
        val query = splitBounds(bounds)
        return catalog.cells.indices.filter { i ->
            val box = catalog.cells[i].bounds
            box == null || splitBounds(box).any { part -> query.any { overlaps(part, it) } }
        }
    }
}
private fun overlaps(a: ChartSelectionExtent, b: ChartSelectionExtent) =
    a.south <= b.north && a.north >= b.south && a.west <= b.east && a.east >= b.west
private fun splitBounds(b: ChartSelectionExtent): List<ChartSelectionExtent> = when {
    b.west > b.east -> listOf(b.copy(east = 180.0), b.copy(west = -180.0))
    b.west == -180.0 && b.east < 180.0 -> listOf(b, b.copy(west = 180.0, east = 180.0))
    b.east == 180.0 && b.west > -180.0 -> listOf(b, b.copy(west = -180.0, east = -180.0))
    else -> listOf(b)
}

/** Pose is the actual render canvas, including any producer reserve, not a guessed screen height. */
data class ChartPlanCamera(val latitude: Double, val longitude: Double, val pixelsPerMetre: Double,
    val rotation: Double, val width: Int, val height: Int) {
    init {
        require(latitude.isFinite() && latitude in -85.0..85.0 && longitude.isFinite() && longitude in -180.0..180.0)
        require(pixelsPerMetre.isFinite() && pixelsPerMetre in 0.00001..10.0 && rotation.isFinite())
        require(width in 1..4096 && height in 1..1499)
    }
    fun canonical() = copy(rotation = atan2(sin(rotation), cos(rotation)),
        longitude = ChartSelectionExtent.normalizeLongitude(longitude))
    /** Same spherical Mercator and rotation as native fromSM_plib/viewport. Longitude stays continuous. */
    fun corners(paddingPixels: Double = 0.0, leadX: Double = 0.0, leadY: Double = 0.0): List<CatalogCoordinate> {
        require(listOf(paddingPixels, leadX, leadY).all { it.isFinite() } && paddingPixels >= 0)
        val z = 6378137.0 * 0.9996
        val radians = Math.PI / 180.0
        val s0 = sin(latitude * radians)
        val y0 = 0.5 * ln((1 + s0) / (1 - s0)) * z
        val c = cos(rotation); val s = sin(rotation)
        val left = -paddingPixels + min(0.0, leadX); val right = width + paddingPixels + max(0.0, leadX)
        val top = -paddingPixels + min(0.0, leadY); val bottom = height + paddingPixels + max(0.0, leadY)
        return listOf(left to top, right to top, right to bottom, left to bottom).map { (x, y) ->
            val east = x - width / 2.0; val north = height / 2.0 - y
            val e = (east * c - north * s) / pixelsPerMetre
            val n = (north * c + east * s) / pixelsPerMetre
            CatalogCoordinate((2 * atan(exp((y0 + n) / z)) - Math.PI / 2) / radians,
                longitude + e / (radians * z))
        }
    }
}

/** Padding/lead are explicit caller policy. No fixed nautical detail rule or velocity prediction. */
data class ChartPlanPolicy(val contextPixels: Double = 0.0, val prefetchPixels: Double = 0.0,
    val leadX: Double = 0.0, val leadY: Double = 0.0, val maxPrefetchCells: Int = 0) {
    init {
        require(listOf(contextPixels, prefetchPixels, leadX, leadY).all { it.isFinite() })
        require(contextPixels in 0.0..2048.0 && prefetchPixels in 0.0..2048.0)
        require(abs(leadX) <= prefetchPixels && abs(leadY) <= prefetchPixels && maxPrefetchCells in 0..128)
    }
}
data class ChartPlanTag(val catalogGeneration: String, val cameraRevision: Long,
    val cameraKey: String, val selectionPolicyKey: String, val selectionPolicyVersion: Int = 1)
data class ChartScaleCandidates(val nativeScale: Int?, val cells: List<ChartCellKey>)
data class ChartViewportPlan(val tag: ChartPlanTag, val camera: ChartPlanCamera,
    val corners: List<CatalogCoordinate>, val required: List<ChartCellKey>, val prefetch: List<ChartCellKey>,
    val retain: List<ChartCellKey>, val drop: List<ChartCellKey>, val usefulPreparationSources: Set<ChartCellKey>,
    val metadataRequired: List<ChartCellKey>, val scaleCandidates: List<ChartScaleCandidates>)

/** Pure owner-side planning. It never touches S57/S52, files, residency, gestures or device state.
 * Candidate scales remain available together; final overlap clipping/SCAMIN belongs to native drawing. */
class ChartViewportPlanner(private val catalog: ChartPlanningCatalog,
    private val index: ChartCellCandidateIndex = FullScanChartCellIndex(catalog),
    private val policy: ChartPlanPolicy = ChartPlanPolicy()) {
    init { require(index.generation == catalog.generation) }
    private var lastCamera: ChartPlanCamera? = null
    private var revision = 0L
    private var visible: List<Int> = emptyList()
    private var ahead: List<Int> = emptyList()
    private var actualCorners: List<CatalogCoordinate> = emptyList()
    private var cameraKey = ""
    // Unlocated cells cannot be represented by a bbox-only BVH/R-tree backend.
    private val unlocated = catalog.cells.indices.filter { catalog.cells[it].bounds == null }
    private val policyKey = MessageDigest.getInstance("SHA-256").digest(
        "no5.viewport-policy.v1:$policy".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun plan(pose: ChartPlanCamera, resident: Set<ChartCellKey> = emptySet()): ChartViewportPlan {
        val camera = pose.canonical()
        if (camera != lastCamera) {
            val nextRevision = Math.addExact(revision, 1)
            val nextCameraKey = MessageDigest.getInstance("SHA-256").digest(camera.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val nextCorners = camera.corners()
            val requiredCorners = camera.corners(policy.contextPixels)
            val nextVisible = candidates(requiredCorners).filterNot { safelyExcluded(catalog.cells[it], requiredCorners) }
            val nextAhead = if (policy.maxPrefetchCells == 0) emptyList() else {
                val requiredSet = nextVisible.toSet()
                val prefetchCorners = camera.corners(policy.contextPixels + policy.prefetchPixels, policy.leadX, policy.leadY)
                candidates(prefetchCorners).filter { it !in requiredSet &&
                    !safelyExcluded(catalog.cells[it], prefetchCorners) }.take(policy.maxPrefetchCells)
            }
            revision = nextRevision; cameraKey = nextCameraKey; actualCorners = nextCorners; visible = nextVisible; ahead = nextAhead
            lastCamera = camera
        }
        val required = visible.map { catalog.cells[it].key }
        val prefetch = ahead.map { catalog.cells[it].key }
        val useful = (required + prefetch).toSet()
        val orderedResident = resident.sortedWith(compareBy<ChartCellKey> { it.provider }.thenBy { it.name }.thenBy { it.sourceKey })
        return ChartViewportPlan(ChartPlanTag(catalog.generation, revision, cameraKey, policyKey), camera, actualCorners.toList(),
            required, prefetch, orderedResident.filter { it in useful }, orderedResident.filter { it !in useful }, useful,
            visible.filter { catalog.cells[it].metadata == null || catalog.cells[it].metadata?.nativeScale == null ||
                catalog.cells[it].metadata?.coverage.isNullOrEmpty() }.map { catalog.cells[it].key },
            visible.groupBy { catalog.cells[it].metadata?.nativeScale }.entries.sortedWith(
                compareBy<Map.Entry<Int?, List<Int>>> { it.key == null }.thenBy { it.key ?: Int.MAX_VALUE })
                .map { ChartScaleCandidates(it.key, it.value.map { i -> catalog.cells[i].key }) })
    }
    /** Old view work is obsolete; an unchanged source can still finish under a newer camera. */
    fun isCurrent(tag: ChartPlanTag): Boolean = lastCamera != null &&
        tag == ChartPlanTag(catalog.generation, revision, cameraKey, policyKey)

    private fun candidates(corners: List<CatalogCoordinate>): List<Int> {
        val indices = index.query(envelope(corners))
        require(indices == indices.distinct().sorted() && indices.all { it in catalog.cells.indices })
        return (indices + unlocated).distinct().sorted()
    }
}

private fun envelope(corners: List<CatalogCoordinate>): ChartSelectionExtent {
    val west = corners.minOf { it.longitude }; val east = corners.maxOf { it.longitude }
    return ChartSelectionExtent(corners.minOf { it.latitude }.coerceIn(-85.0, 85.0),
        corners.maxOf { it.latitude }.coerceIn(-85.0, 85.0),
        if (east - west >= 360.0) -180.0 else ChartSelectionExtent.normalizeLongitude(west),
        if (east - west >= 360.0) 180.0 else ChartSelectionExtent.normalizeLongitude(east))
}

/** Only a proven rectangular CATCOV2 interior can eliminate a whole viewport.
 * Arbitrary/concave rings and precision-sensitive edges remain conservative candidates.
 * Native metadata currently advertises exterior rings, not general polygon holes/MultiPolygon. */
private fun safelyExcluded(cell: ChartPlanCell, corners: List<CatalogCoordinate>): Boolean {
    val metadata = cell.metadata ?: return false
    if (metadata.capabilities.wrappedDateline || corners.any { it.longitude !in -180.0..180.0 }) return false
    val viewport = envelope(corners)
    // Exact +180 also normalizes to -180. Ordinary rectangle containment is
    // invalid for that wrapped envelope even when every original corner is canonical.
    if (viewport.west > viewport.east) return false
    return metadata.exclusions.any { ring ->
        val vertices = if (ring.size > 1 && ring.first() == ring.last()) ring.dropLast(1) else ring
        if (vertices.size != 4 || vertices.distinct().size != 4) return@any false
        val south = vertices.minOf { it.latitude }; val north = vertices.maxOf { it.latitude }
        val west = vertices.minOf { it.longitude }; val east = vertices.maxOf { it.longitude }
        // Verify each edge, not merely that four corner positions exist (bow ties fail).
        if (south >= north || west >= east || vertices.any { it.latitude !in listOf(south, north) ||
                it.longitude !in listOf(west, east) } || vertices.indices.any { i ->
                val a = vertices[i]; val b = vertices[(i + 1) % 4]
                (a.latitude == b.latitude) == (a.longitude == b.longitude) }) return@any false
        // Conservative native float32 edge margin in degrees; boundary touch is retained.
        val margin = listOf(south, north, west, east).maxOf { Math.ulp(it.toFloat()).toDouble() } * 4
        viewport.south > south + margin && viewport.north < north - margin &&
            viewport.west > west + margin && viewport.east < east - margin
    }
}
