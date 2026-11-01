package com.no5.helm.library

import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min

/** A local geometry envelope for choosing cells. This is NOT an ENC coverage polygon. */
@Serializable data class ChartSelectionExtent(val south: Double, val north: Double, val west: Double, val east: Double) {
    val longitudeSpan: Double get() = if (east >= west) east - west else east + 360.0 - west
    val centerLatitude: Double get() = (south + north) / 2.0
    val centerLongitude: Double get() = normalizeLongitude(west + longitudeSpan / 2.0)
    val valid: Boolean get() = listOf(south, north, west, east).all { it.isFinite() } &&
        south in -90.0..90.0 && north in south..90.0 && west in -180.0..180.0 && east in -180.0..180.0 && longitudeSpan <= 360.0
    companion object {
        fun normalizeLongitude(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }
}
@Serializable internal data class ChartSpatialEntry(val name: String, val files: Map<String, String>, val extent: ChartSelectionExtent?)
@Serializable internal data class ChartSpatialIndex(val version: Int = 1, val cells: List<ChartSpatialEntry>)

/**
 * S57 3.1 DSPM/SG2D/SG3D coordinate envelopes. Uses base COMF for updates, and includes
 * old/deleted coordinates conservatively. It does not resolve vector topology or M_COVR.
 * Bounded record-at-a-time IO; no geometry arrays are retained.
 */
internal object S57SelectionExtent {
    fun read(files: List<File>, checkpoint: (Long) -> Unit = {}): ChartSelectionExtent? {
        var multiplier: Int? = null
        var south = Double.POSITIVE_INFINITY; var north = Double.NEGATIVE_INFINITY
        var west = Double.POSITIVE_INFINITY; var east = Double.NEGATIVE_INFINITY
        var shiftedWest = Double.POSITIVE_INFINITY; var shiftedEast = Double.NEGATIVE_INFINITY
        var count = 0L
        try {
            for (file in files) RandomAccessFile(file, "r").use { input ->
                require(input.length() in 25..128L * 1024 * 1024)
                var records = 0
                while (input.filePointer < input.length()) {
                    checkpoint(input.filePointer)
                    require(++records <= 2_000_000 && input.length() - input.filePointer >= 24)
                    val leader = ByteArray(24).also(input::readFully)
                    fun number(offset: Int, size: Int) = String(leader, offset, size, Charsets.US_ASCII).toIntOrNull() ?: error("ISO8211 leader")
                    val length = number(0, 5); val base = number(12, 5)
                    val lw = number(20, 1); val pw = number(21, 1); val tw = number(23, 1); val width = lw + pw + tw
                    require(length in 25..99999 && base in 25..length && lw > 0 && pw > 0 && tw > 0 &&
                        (base - 25) % width == 0 && length - 24 <= input.length() - input.filePointer)
                    val record = ByteArray(length).also { leader.copyInto(it); input.readFully(it, 24, length - 24) }
                    require(record[base - 1] == 0x1e.toByte())
                    if (leader[6] != 'D'.code.toByte()) continue
                    for (offset in 24 until base - 1 step width) {
                        val tag = String(record, offset, tw, Charsets.US_ASCII)
                        val size = String(record, offset + tw, lw, Charsets.US_ASCII).toIntOrNull() ?: error("ISO8211 field")
                        val position = String(record, offset + tw + lw, pw, Charsets.US_ASCII).toIntOrNull() ?: error("ISO8211 field")
                        require(size > 0 && position >= 0 && position.toLong() + size <= length - base)
                        val start = base + position
                        if (tag != "DSPM" && tag != "SG2D" && tag != "SG3D") continue
                        require(record[start + size - 1] == 0x1e.toByte())
                        fun integer(at: Int): Int {
                            require(at >= 0 && at + 4 <= size - 1)
                            return (record[start + at].toInt() and 255) or ((record[start + at + 1].toInt() and 255) shl 8) or
                                ((record[start + at + 2].toInt() and 255) shl 16) or (record[start + at + 3].toInt() shl 24)
                        }
                        if (tag == "DSPM") {
                            // RCNM/RCID/HDAT/VDAT/SDAT/CSCL/DUNI/HUNI/PUNI/COUN precede COMF.
                            require(size >= 25 && record[start + 15] == 1.toByte()) // Geographic degrees only.
                            multiplier = integer(16).also { require(it > 0) }
                        }
                        if (tag == "SG2D" || tag == "SG3D") {
                            val factor = multiplier ?: return null
                            val stride = if (tag == "SG2D") 8 else 12
                            require((size - 1) % stride == 0)
                            for (at in 0 until size - 1 step stride) {
                                val lat = integer(at) / factor.toDouble(); val lon = integer(at + 4) / factor.toDouble()
                                require(lat in -90.0..90.0 && lon in -180.0..180.0)
                                south = min(south, lat); north = max(north, lat); west = min(west, lon); east = max(east, lon)
                                val shifted = if (lon < 0.0) lon + 360.0 else lon
                                shiftedWest = min(shiftedWest, shifted); shiftedEast = max(shiftedEast, shifted); count++
                            }
                        }
                    }
                }
                checkpoint(input.filePointer)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: IllegalArgumentException) { return null }
        catch (_: IllegalStateException) { return null }
        if (count == 0L) return null
        return if (shiftedEast - shiftedWest < east - west)
            ChartSelectionExtent(south, north, ChartSelectionExtent.normalizeLongitude(shiftedWest), ChartSelectionExtent.normalizeLongitude(shiftedEast))
        else ChartSelectionExtent(south, north, west, east)
    }
}
