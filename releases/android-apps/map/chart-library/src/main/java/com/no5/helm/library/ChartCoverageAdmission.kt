package com.no5.helm.library

import java.security.MessageDigest
import java.util.Arrays
import kotlin.math.*
import com.no5.helm.chart.BACKGROUND_CACHE_PROVIDER

class ChartCoverageAdmission internal constructor(val plan: ChartViewportPlan, val proven: Boolean,
    val masked: Boolean, val conservativeCount: Int, val uncoveredPixels: Int?,
    val loadOrder: List<ChartCellKey>, val reason: String,
    internal val raster: CoverageRaster? = null, internal val rasterKeys: List<ChartCellKey> = emptyList())

/** Smallest ordinal storage for the candidate cohort; one raster, not one per cell. */
internal class CoverageRaster(count: Int, val pixels: Int) {
    private val small = if (count <= 255) ByteArray(pixels) else null
    private val large = if (small == null) IntArray(pixels) else null
    val bytes get() = pixels.toLong() * if (small == null) 4 else 1
    fun get(p: Int): Int = small?.let { it[p].toInt() and 255 } ?: large!![p]
    fun claim(p: Int, id: Int): Boolean {
        if (get(p) != 0) return false
        if (small != null) small[p] = id.toByte() else large!![p] = id
        return true
    }
}

/** Exact native quilt contributor reduction, with a mask-mode witness.
 * Never substitutes coarser content or invents a new detail acceptance rule.
 * Unknown topology/metadata and resource limits retain the conservative demand. */
class ChartCoveragePlanner(private val catalog: ChartPlanningCatalog,
    internal val experimentalScreenScale: Boolean = false) {
    private val byKey = catalog.cells.associateBy { it.key }
    private var cachedTag: ChartPlanTag? = null
    private var cachedScale: Double? = null
    private var cached: ChartCoverageAdmission? = null
    fun admit(plan: ChartViewportPlan, chartScale: Double?, captureRaster: Boolean = false,
        checkpoint: () -> Unit = {}): ChartCoverageAdmission {
        require(plan.tag.catalogGeneration == catalog.generation)
        checkpoint()
        if (!captureRaster && plan.tag == cachedTag && chartScale == cachedScale) cached?.let {
            val rebound = bind(plan, it.plan.required, it.plan.tag.selectionPolicyKey)
            if (rebound == it.plan) return it
            return ChartCoverageAdmission(rebound, it.proven, it.masked, it.conservativeCount,
                it.uncoveredPixels, it.loadOrder, it.reason).also { refreshed -> cached = refreshed }
        }
        fun conservative(reason: String) = ChartCoverageAdmission(plan, false, false, plan.required.size, null, plan.required, reason)
        if (chartScale == null || !chartScale.isFinite() || chartScale <= 0) return conservative("Unknown actual native scale")
        if (plan.metadataRequired.isNotEmpty()) return conservative("Unknown source coverage")
        val cells = plan.required.map { byKey.getValue(it) }
        if (cells.isEmpty()) return conservative("Empty conservative cohort")
        // The original same-scale/no-exclusion cohort is intentionally unmasked.
        if (!captureRaster && cells.map { it.metadata!!.nativeScale }.distinct().size == 1 &&
            cells.none { it.key.provider == BACKGROUND_CACHE_PROVIDER } && cells.all { it.metadata!!.exclusions.isEmpty() })
            return conservative("Original same-scale cohort retained")
        val camera = plan.camera
        if (camera.corners().any { it.longitude !in -180.0..180.0 }) return conservative("Wrapped viewport remains conservative")
        if (cells.sumOf { c -> (c.metadata!!.coverage + c.metadata.exclusions).sumOf { it.size.toLong() } } > 1_048_576)
            return conservative("Projection vertex bound")
        val pixels = camera.width * camera.height
        val owners = CoverageRaster(cells.size, pixels)
        val visible = BooleanArray(cells.size); val contributed = BooleanArray(cells.size)
        val processed = BooleanArray(cells.size)
        val counts = LongArray(cells.size); val row = ByteArray(camera.width)
        val scales = mutableSetOf<Int>(); var exclusions = false; var work = 0L; var occupied = 0
        // Explicit experiment only; original priority remains the default.
        val priority = if (experimentalScreenScale) cells.indices.sortedWith(compareBy<Int> {
            val s = cells[it].metadata!!.nativeScale!!
            if (s.toDouble() <= chartScale) 0 else if (chartScale <= s.toDouble() * 4) 1 else 2
        }.thenBy {
            val s = cells[it].metadata!!.nativeScale!!
            if (s.toDouble() <= chartScale) -s.toLong()
            else if (chartScale <= s.toDouble() * 4) s.toLong() else -s.toLong()
        }.thenBy { cells[it].order }) else cells.indices.sortedWith(compareBy<Int> {
            if (chartScale <= cells[it].metadata!!.nativeScale!!.toDouble() * 4) 0 else 1
        }.thenBy {
            val s = cells[it].metadata!!.nativeScale!!
            if (chartScale <= s.toDouble() * 4) s.toLong() else -s.toLong()
        }.thenBy { cells[it].order })
        val rolePriority = priority.sortedBy { cells[it].key.provider == BACKGROUND_CACHE_PROVIDER }
        for (id in rolePriority) {
            checkpoint()
            processed[id] = true
            val metadata = cells[id].metadata!!
            val covered = metadata.coverage.map { project(it, camera) ?: return conservative("Unsupported projected coverage") }
            val excluded = metadata.exclusions.map { project(it, camera) ?: return conservative("Unsupported projected exclusion") }
            val minX = covered.minOf { it.minX }; val maxX = covered.maxOf { it.maxX }
            val minY = covered.minOf { it.minY }; val maxY = covered.maxOf { it.maxY }
            if (maxX <= 0 || maxY <= 0 || minX >= camera.width || minY >= camera.height) continue
            val x0 = floor(minX.coerceIn(0.0, camera.width.toDouble())).toInt()
            val x1 = ceil(maxX.coerceIn(0.0, camera.width.toDouble())).toInt()
            val y0 = floor(minY.coerceIn(0.0, camera.height.toDouble())).toInt()
            val y1 = ceil(maxY.coerceIn(0.0, camera.height.toDouble())).toInt()
            for (y in y0 until y1) {
                if (y % 16 == 0) checkpoint()
                work += x1 - x0
                if (work > pixels.toLong() * 64) return conservative("Coverage work bound")
                Arrays.fill(row, x0, x1, 0.toByte())
                if (covered.any { !it.paint(y, x0, x1, row, 1) } ||
                    excluded.any { !it.paint(y, x0, x1, row, 0) })
                    return conservative("Interpolated edge near a native pixel centre")
                for (x in x0 until x1) if (row[x].toInt() != 0) {
                    visible[id] = true
                    if (owners.claim(y * camera.width + x, id + 1)) { contributed[id] = true; counts[id]++; occupied++ }
                }
            }
            if (visible[id]) { scales.add(metadata.nativeScale!!); exclusions = exclusions || excluded.isNotEmpty() }
            // Later native-priority cells cannot contribute once every pixel
            // has an owner. Finish only after the native masked mode and the
            // same first source-order witness are already established.
            if (occupied == pixels && (scales.size > 1 || exclusions || cells.indices.any { visible[it] && cells[it].key.provider == BACKGROUND_CACHE_PROVIDER })) {
                val contributors = cells.indices.filter { contributed[it] }
                val contributorMasked = contributors.any { cells[it].key.provider == BACKGROUND_CACHE_PROVIDER } ||
                    contributors.map { cells[it].metadata!!.nativeScale }.distinct().size > 1 ||
                    contributors.any { cells[it].metadata!!.exclusions.isNotEmpty() }
                if (contributorMasked) break
                val witness = cells.indices.firstOrNull { visible[it] && !contributed[it] &&
                    (cells[it].metadata!!.exclusions.isNotEmpty() || contributors.any { source ->
                        cells[source].metadata!!.nativeScale != cells[it].metadata!!.nativeScale ||
                            (cells[source].key.provider == BACKGROUND_CACHE_PROVIDER) != (cells[it].key.provider == BACKGROUND_CACHE_PROVIDER) }) }
                if (witness != null && (0 until witness).all { processed[it] }) break
            }
        }
        val masked = scales.size > 1 || exclusions || cells.indices.any { visible[it] && cells[it].key.provider == BACKGROUND_CACHE_PROVIDER }
        val selected = cells.indices.filter { visible[it] && (!masked || contributed[it]) }.toMutableSet()
        // Dropping an occluded scale/exclusion witness must not switch native
        // rendering back to its deliberately different unmasked same-scale path.
        val subsetMasked = selected.any { cells[it].key.provider == BACKGROUND_CACHE_PROVIDER } ||
            selected.map { cells[it].metadata!!.nativeScale }.distinct().size > 1 ||
            selected.any { cells[it].metadata!!.exclusions.isNotEmpty() }
        if (masked && !subsetMasked) {
            val witness = cells.indices.firstOrNull { visible[it] && it !in selected &&
                (cells[it].metadata!!.exclusions.isNotEmpty() || selected.any { s -> cells[s].metadata!!.nativeScale != cells[it].metadata!!.nativeScale ||
                    (cells[s].key.provider == BACKGROUND_CACHE_PROVIDER) != (cells[it].key.provider == BACKGROUND_CACHE_PROVIDER) }) }
                ?: return conservative("No safe mask-mode witness")
            selected.add(witness)
        }
        val required = cells.indices.filter { it in selected }.map { cells[it].key }
        // Native policy authority identifies fixed rules, not a camera's result.
        // Zoom/pan and conservative fallbacks must keep the same policy; camera
        // keys/revisions and required sources already describe the changing plan.
        val policyKey = if (!experimentalScreenScale) plan.tag.selectionPolicyKey else {
            // Preserve the separate, opt-in shadow experiment's identity.
            val keyBytes = ("no5.coverage-selection.v1:p5-screen-scale-shadow.v1:" +
                plan.tag.selectionPolicyKey + ":" + chartScale + ":" + required.joinToString { it.sourceKey }).toByteArray()
            MessageDigest.getInstance("SHA-256").digest(keyBytes).joinToString("") { "%02x".format(it) }
        }
        val admitted = bind(plan, required, policyKey)
        val load = selected.sortedWith(compareByDescending<Int> { counts[it] }.thenBy { cells[it].order }).map { cells[it].key }
        val result = ChartCoverageAdmission(admitted, true, masked, cells.size,
            (0 until pixels).count { owners.get(it) == 0 }, load, "Native contributors and mask witness; geometry/byte guards remain native",
            owners.takeIf { captureRaster }, cells.map { it.key }.takeIf { captureRaster }.orEmpty())
        if (!captureRaster) { cachedTag = plan.tag; cachedScale = chartScale; cached = result }
        return result
    }

    private fun bind(plan: ChartViewportPlan, required: List<ChartCellKey>, policyKey: String): ChartViewportPlan {
        val useful = (required + plan.prefetch).toSet()
        return plan.copy(tag = plan.tag.copy(selectionPolicyKey = policyKey), required = required,
            retain = plan.retain.filter { it in useful }, drop = (plan.drop + plan.retain.filter { it !in useful }).distinct(),
            usefulPreparationSources = useful, metadataRequired = emptyList(),
            scaleCandidates = plan.scaleCandidates.map { it.copy(cells = it.cells.filter { c -> c in useful }) }.filter { it.cells.isNotEmpty() })
    }

    private data class Edge(val ax: Double, val ay: Double, val bx: Double, val by: Double, val start: Int, val end: Int)
    private class Ring(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double, val edges: List<Edge>) {
        private var next = 0
        private val active = ArrayList<Edge>()
        private val hits = ArrayList<Double>()
        fun paint(y: Int, x0: Int, x1: Int, row: ByteArray, value: Int): Boolean {
            active.removeAll { it.end <= y }
            while (next < edges.size && edges[next].start <= y) { val e = edges[next++]; if (e.end > y) active.add(e) }
            val py = y + .5
            if (py < minY || py >= maxY) return true
            hits.clear()
            for (e in active) if ((e.ay <= py && e.by > py) || (e.by <= py && e.ay > py))
                hits.add(e.ax + (py - e.ay) * (e.bx - e.ax) / (e.by - e.ay))
            hits.sort()
            if (hits.any { it >= x0 && it <= x1 && abs(it - .5 - round(it - .5)) < 1e-7 }) return false
            var i = 0
            while (i + 1 < hits.size) {
                val begin = ceil((hits[i] - .5).coerceIn(x0.toDouble(), x1.toDouble())).toInt()
                val end = ceil((hits[i + 1] - .5).coerceIn(x0.toDouble(), x1.toDouble())).toInt()
                if (end > begin) Arrays.fill(row, begin, end, value.toByte())
                i += 2
            }
            return true
        }
    }
    private fun project(source: List<CatalogCoordinate>, camera: ChartPlanCamera): Ring? {
        if (source.size < 3 || source.maxOf { it.longitude } - source.minOf { it.longitude } > 180) return null
        val z = 6378137.0 * .9996; val r = Math.PI / 180; val s0 = sin(camera.latitude * r)
        val y0 = .5 * ln((1 + s0) / (1 - s0)) * z
        val c = cos(camera.rotation); val s = sin(camera.rotation)
        val points = source.map { p ->
            val lat = p.latitude.toFloat().toDouble(); val lon = p.longitude.toFloat().toDouble()
            if (!lat.isFinite() || !lon.isFinite() || abs(lat) > 85 || abs(lon) > 180) return null
            val phaseLongitude = if (lon * camera.longitude < 0 && abs(lon - camera.longitude) > 180)
                lon + if (lon < 0) 360.0 else -360.0 else lon
            val east = (phaseLongitude - camera.longitude) * r * z; val sl = sin(lat * r)
            val north = .5 * ln((1 + sl) / (1 - sl)) * z - y0
            (camera.width * .5 + camera.pixelsPerMetre * (east * c + north * s)) to
                (camera.height * .5 + camera.pixelsPerMetre * (east * s - north * c))
        }
        if (points.any { !it.first.isFinite() || !it.second.isFinite() }) return null
        // A half-pixel boundary can amplify tiny cross-runtime arithmetic
        // differences. Preserve the whole cohort rather than prove exclusion.
        if (points.any { p -> listOf(p.first, p.second).any { v ->
            v in -1.0..max(camera.width, camera.height).toDouble() + 1 && abs(v - .5 - round(v - .5)) < 1e-7
        } }) return null
        val edges = points.indices.mapNotNull { i ->
            val a = points[if (i == 0) points.lastIndex else i - 1]; val b = points[i]
            if (a.second == b.second) null else {
                val begin = ceil((min(a.second, b.second) - .5).coerceIn(0.0, camera.height.toDouble())).toInt()
                val end = ceil((max(a.second, b.second) - .5).coerceIn(0.0, camera.height.toDouble())).toInt()
                Edge(a.first, a.second, b.first, b.second, begin, end)
            }
        }.sortedBy { it.start }
        return Ring(points.minOf { it.first }, points.maxOf { it.first }, points.minOf { it.second }, points.maxOf { it.second }, edges)
    }
}
