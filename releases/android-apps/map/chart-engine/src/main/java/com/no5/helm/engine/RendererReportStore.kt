package com.no5.helm.engine

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Completed JSON only; called on IO after capture freezes. Never writes from a measured frame. */
internal class RendererReportStore(private val directory: File) {
    @Synchronized fun save(report: String) {
        require(report.isNotBlank())
        require(directory.mkdirs() || directory.isDirectory)
        // Keep every completed report; latest is a second copy for direct run-as recovery.
        val archive = File(directory, "report-${System.currentTimeMillis()}-${UUID.randomUUID()}.json")
        atomicWrite(archive, report)
        atomicWrite(File(directory, "latest.json"), report)
    }
    @Synchronized fun latest(): String? = File(directory, "latest.json").takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
    @Synchronized fun saveBenchmark(runId: String, report: String): String {
        require(UUID.fromString(runId).toString() == runId && report.isNotBlank())
        require(directory.mkdirs() || directory.isDirectory)
        val name = "benchmark-$runId.json"
        check(!File(directory, name).exists()) { "Benchmark ID already saved" }
        atomicWrite(File(directory, name), report)
        atomicWrite(File(directory, "latest.json"), report)
        return name
    }
    private fun atomicWrite(file: File, value: String) {
        val temp = File(directory, file.name + ".tmp")
        try {
            FileOutputStream(temp).use { it.write(value.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
}
