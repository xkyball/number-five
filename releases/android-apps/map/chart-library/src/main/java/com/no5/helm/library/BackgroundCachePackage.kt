package com.no5.helm.library

import com.no5.helm.chart.BACKGROUND_CACHE_PROVIDER
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

/** Public coarse geography, accompanying (never impersonating) the Pi export. */
@Serializable internal data class BackgroundCacheManifest(
    val format: String, val product: String, val gridDegrees: Int, val bounds: ChartSelectionExtent,
    val nauticalManifestSHA256: String, val backgroundProvider: String,
    val sourceUrl: String, val sourceSHA256: String, val sourceVersion: String,
    val license: String, val licenseUrl: String, val attribution: String,
    val sourceRepairs: Int, val cells: List<PiCacheCell>,
)

internal object BackgroundCachePackage {
    const val manifestName = "no5-map-cache-v2.json"
    private val json = Json { ignoreUnknownKeys = false }
    fun files(value: BackgroundCacheManifest) = setOf(manifestName) + value.cells.flatMap { it.files.keys }
    fun read(data: File, limits: ImportLimits, checkpoint: () -> Unit, hash: (File) -> String): BackgroundCacheManifest? {
        val file = File(data, manifestName)
        if (!file.exists()) return null
        require(file.length() in 1..4L * 1024 * 1024) { "Grundkartenmanifest ungültig" }
        val value = json.decodeFromString<BackgroundCacheManifest>(file.readText())
        require(value.format == "no5-map-cache-v2" && value.backgroundProvider == BACKGROUND_CACHE_PROVIDER &&
            value.gridDegrees in 1..90 && 180 % value.gridDegrees == 0 && value.bounds.valid &&
            value.sourceVersion == "5.1.1" && value.license == "Public Domain" && value.sourceRepairs >= 0 &&
            value.sourceUrl == "https://naciscdn.org/naturalearth/10m/physical/ne_10m_land.zip" &&
            value.sourceSHA256 == "e547d749445eaa0964aba76738090ec88f5e63c4585122170f98c67a7ea922dc" &&
            value.licenseUrl == "https://www.naturalearthdata.com/about/terms-of-use/" && value.attribution.isNotBlank()) {
            "Unbekannte Grundkartenquelle"
        }
        require(hash(File(data, PiCachePackage.manifestName)) == value.nauticalManifestSHA256) { "Pi-Manifest verändert" }
        require(value.cells.size in 1..limits.cells && value.cells.map { it.name }.distinct().size == value.cells.size)
        val tiles = mutableSetOf<Pair<Double, Double>>()
        for (cell in value.cells) {
            checkpoint()
            val e = cell.extent
            require(cell.name.matches(Regex("NO5BG_[NS][0-9]{2}_[EW][0-9]{3}")) &&
                cell.base == "cells/${cell.name}.senc" && cell.edition == 1 && cell.update == 0 &&
                cell.files.keys == setOf(cell.base, "provenance/${cell.name}.json") && cell.featureCount in 1..65535 &&
                cell.generation.matches(Regex("[a-f0-9]{64}")) && e.valid &&
                e.east - e.west == value.gridDegrees.toDouble() && e.north - e.south == value.gridDegrees.toDouble() &&
                e.west % value.gridDegrees == 0.0 && e.south % value.gridDegrees == 0.0 &&
                e.west >= value.bounds.west && e.east <= value.bounds.east &&
                e.south >= value.bounds.south && e.north <= value.bounds.north && tiles.add(e.west to e.south)) {
                "Grundkartenraster ungültig"
            }
            for ((path, expected) in cell.files) {
                require(expected.matches(Regex("[a-f0-9]{64}")) && hash(ChartLibrary.safePath(data, path)) == expected) {
                    "Grundkartendatei verändert: $path"
                }
            }
            val proof = ChartLibrary.safePath(data, "provenance/${cell.name}.json")
            require(proof.length() in 1..65536)
            val origin = json.parseToJsonElement(proof.readText()).jsonObject
            require("helperStatus" !in origin && origin["provider"]?.jsonPrimitive?.content == BACKGROUND_CACHE_PROVIDER &&
                origin["sourceSHA256"]?.jsonPrimitive?.content == value.sourceSHA256 &&
                origin["license"]?.jsonPrimitive?.content == "Public Domain") { "Grundkartenherkunft ungültig" }
            PiCachePackage.metadata(ChartLibrary.safePath(data, cell.base), cell, checkpoint)
        }
        require(value.cells.size.toDouble() == (value.bounds.east - value.bounds.west) *
            (value.bounds.north - value.bounds.south) / (value.gridDegrees * value.gridDegrees)) { "Grundkartenraster hat Lücken" }
        return value
    }
}
