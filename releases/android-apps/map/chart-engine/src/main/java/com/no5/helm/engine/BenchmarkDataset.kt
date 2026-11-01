package com.no5.helm.engine

import com.no5.helm.library.S57Identification
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

/** Hashes immutable source cells and their updates before measurement, never exports private paths. */
internal fun benchmarkDataset(paths: List<String>): JsonObject {
    val bases = paths.map(::File).sortedBy { it.name }
    check(bases.size == 4 && bases.map { it.nameWithoutExtension }.toSet() == BostonBenchmarkRecipe.cells &&
        bases.all { it.extension == "000" }) { "Benchmark requires exactly the four Boston base cells" }
    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    val hashes = mutableListOf<String>()
    val cells = bases.map { base ->
        val files = base.parentFile!!.listFiles().orEmpty().filter {
            it.nameWithoutExtension == base.nameWithoutExtension && it.extension.matches(Regex("[0-9]{3}"))
        }.sortedBy { it.extension.toInt() }
        check(files.map { it.extension.toInt() } == files.indices.toList()) { "Incomplete Boston update chain" }
        val versions = files.map { S57Identification.read(it) }
        val edition = versions.first().edition
        check(versions.all { it.edition == edition } && versions.map { it.update } == files.indices.toList()) { "Conflicting Boston edition/update" }
        buildJsonObject {
            put("name", base.nameWithoutExtension); put("edition", edition); put("update", versions.last().update)
            put("issueDate", versions.last().issueDate)
            put("files", buildJsonArray { files.forEach { file ->
                val sha = hash(file); hashes += "${file.name}:$sha\n"
                add(buildJsonObject { put("name", file.name); put("sha256", sha); put("bytes", file.length()) })
            } })
        }
    }
    return buildJsonObject {
        put("id", "noaa-boston-harbour"); put("cells", JsonArray(cells))
        put("contentSha256", MessageDigest.getInstance("SHA-256").digest(hashes.joinToString("").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
    }
}
