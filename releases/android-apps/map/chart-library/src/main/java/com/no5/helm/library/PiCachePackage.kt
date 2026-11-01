package com.no5.helm.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.OffsetDateTime

/** Export provenance, not a fresh licence check on Android. No keys or helper binaries. */
@Serializable data class PiCacheProvenance(val product: String, val licenseHost: String, val exportedUtc: String)
@Serializable internal data class PiCacheCell(val name: String, val base: String, val edition: Int, val update: Int,
    val issueDate: String, val generation: String, val featureCount: Int, val extent: ChartSelectionExtent,
    val files: Map<String, String>)
@Serializable internal data class PiCacheManifest(val format: String, val product: String, val licenseHost: String,
    val exportedUtc: String, val cells: List<PiCacheCell>, val authorization: String)

internal object PiCachePackage {
    const val provider = com.no5.helm.chart.PI_CACHE_PROVIDER
    const val manifestName = "no5-pi-map-cache.json"
    private val json = Json { ignoreUnknownKeys = false }
    /** Metadata-only descriptor validation. Native admission verifies both file pins before loading. */
    fun residentFiles(cell: ChartCell): List<Map.Entry<String, String>> {
        require(cell.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")) &&
            cell.edition in 1..65535 && cell.update in 0..65535 && cell.base == "cells/${cell.name}.senc")
        require(cell.files.keys == setOf(cell.base, "provenance/${cell.name}.json"))
        return cell.files.entries.sortedBy { if (it.key == cell.base) 0 else 1 }
    }
    fun read(data: File, limits: ImportLimits, checkpoint: () -> Unit, additionalFiles: Set<String> = emptySet(), hash: (File) -> String): PiCacheManifest? {
        val manifest = File(data, manifestName)
        if (!manifest.exists()) return null
        require(manifest.length() in 1..4L * 1024 * 1024) { "Pi-Exportmanifest ungültig" }
        val value = json.decodeFromString<PiCacheManifest>(manifest.readText())
        require(value.format == "no5-pi-map-cache-v1" && value.product.isNotBlank() && value.product.length <= 200 &&
            value.licenseHost.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) { "Unbekanntes Pi-Exportformat" }
        OffsetDateTime.parse(value.exportedUtc)
        require(value.cells.size in 1..limits.cells && value.cells.map { it.name.lowercase() }.distinct().size == value.cells.size) { "Pi-Exportzellen ungültig" }
        val declared = (setOf(manifestName) + additionalFiles).toMutableSet()
        for (cell in value.cells) {
            checkpoint()
            require(cell.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")) && cell.base == "cells/${cell.name}.senc" &&
                cell.edition in 1..65535 && cell.update in 0..65535 && cell.featureCount in 1..65536 && cell.extent.valid &&
                cell.generation.matches(Regex("[a-f0-9]{64}")) && cell.files.keys == setOf(cell.base, "provenance/${cell.name}.json")) { "Pi-Zellmanifest ungültig" }
            for ((path, digest) in cell.files) {
                require(declared.add(path) && digest.matches(Regex("[a-f0-9]{64}"))) { "Pi-Dateimanifest ungültig" }
                require(hash(ChartLibrary.safePath(data, path)) == digest) { "Pi-Exportdatei verändert: $path" }
            }
            val provenance = File(data, "provenance/${cell.name}.json")
            require(provenance.length() <= 8L * 1024 * 1024) { "Pi-Herkunftsdaten zu groß" }
            val status = json.parseToJsonElement(provenance.readText()).jsonObject.getValue("helperStatus").jsonObject
            require(status.getValue("decrypt_status").jsonPrimitive.int != 0 && status.getValue("expire_status").jsonPrimitive.int != 0) { "Pi-Export ohne erfolgreichen Helper-Prüfstatus" }
            metadata(ChartLibrary.safePath(data, cell.base), cell, checkpoint)
        }
        require(data.walkTopDown().filter { it.isFile }.map { it.relativeTo(data).invariantSeparatorsPath }.toSet() == declared) { "Unerwartete Dateien im Pi-Export" }
        return value
    }

    /** Bind manifest identity, count and geographic extent to the actual SENC; native validates geometry. */
    internal fun metadata(file: File, expected: PiCacheCell, checkpoint: () -> Unit) = RandomAccessFile(file, "r").use { input ->
        var version: Int? = null; var name: String? = null; var edition: Int? = null; var update: Int? = null
        var extent: ChartSelectionExtent? = null; var features = 0; var records = 0
        while (input.filePointer < input.length()) {
            checkpoint(); require(++records <= 2_000_000 && input.length() - input.filePointer >= 6)
            val header = ByteBuffer.wrap(ByteArray(6).also(input::readFully)).order(ByteOrder.LITTLE_ENDIAN)
            val type = header.short.toInt() and 65535; val size = header.int
            require(size >= 6 && size - 6L <= input.length() - input.filePointer)
            require(records != 1 || type == 1) { "Pi-SENC beginnt ohne Version" }
            if (type in setOf(1, 2, 4, 6, 100)) {
                require(size <= 1024) { "Pi-SENC-Kopf zu groß" }
                val bytes = ByteArray(size - 6).also(input::readFully)
                val body = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                when (type) {
                    1 -> { require(version == null && bytes.size == 2); version = body.short.toInt() and 65535 }
                    2 -> { require(name == null && bytes.isNotEmpty() && bytes.last() == 0.toByte()); name = String(bytes, 0, bytes.size - 1, Charsets.UTF_8) }
                    4 -> { require(edition == null && bytes.size == 2); edition = body.short.toInt() and 65535 }
                    6 -> { require(update == null && bytes.size == 2); update = body.short.toInt() and 65535 }
                    100 -> { require(extent == null && bytes.size == 64); val corners = List(8) { body.double }
                        extent = ChartSelectionExtent(corners[6], corners[2], corners[3], corners[7]) }
                }
            } else { if (type == 64) features++; input.seek(input.filePointer + size - 6) }
        }
        require(version == 201 && name == expected.name && edition == expected.edition && update == expected.update &&
            extent == expected.extent && features == expected.featureCount) { "Pi-SENC widerspricht Exportmanifest" }
    }
}
