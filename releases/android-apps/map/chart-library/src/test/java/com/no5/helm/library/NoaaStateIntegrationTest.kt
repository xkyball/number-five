package com.no5.helm.library

import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/** Opt-in real NOAA data probe. A native executable uses exactly the production SencCache path. */
class NoaaStateIntegrationTest {
    @Test fun officialMassachusettsPackageImportsWithoutBundlingAndPersistsSelection() = runBlocking {
        val zipPath = System.getenv("NO5_NOAA_STATE_ZIP")
        Assume.assumeTrue("Real NOAA integration input explicitly required", !zipPath.isNullOrEmpty())
        val archive = File(zipPath!!); assertTrue(archive.isFile)
        val executable = System.getenv("NO5_S57_VALIDATOR")?.let(::File)
        assertTrue("Production native preflight executable required", executable?.isFile == true)
        val output = File(System.getenv("NO5_IMPORT_EVIDENCE") ?: "build/noaa-import-evidence").absoluteFile.apply { mkdirs() }
        val root = Files.createTempDirectory("no5-noaa-state").toFile()
        try {
            val library = ChartLibrary(root)
            val expected = ZipFile(archive).use { zip -> zip.entries().asSequence().count { it.name.endsWith(".000") } }
            var validated = 0
            val stored = library.importZip(archive.name, archive.inputStream(), { paths, cache ->
                val list = File(output, "native-paths.txt").apply { writeText(paths.joinToString("\n") { it.absolutePath }) }
                val report = File(output, "native-noaa-state.txt")
                val process = ProcessBuilder(executable!!.absolutePath, File("../chart-engine/src/main/assets").absolutePath,
                    cache.absolutePath, list.absolutePath, report.absolutePath).redirectErrorStream(true).start()
                val log = process.inputStream.bufferedReader().readText(); val result = process.waitFor()
                File(output, "native-noaa-state-console.txt").writeText(log)
                assertEquals(log, 0, result); validated = paths.size
            })
            assertEquals(expected, stored.cells.size); assertEquals(expected - stored.cells.count { it.cancelled }, validated); assertNull(library.snapshot().active)
            assertTrue("Large state probe exercises the 100-cell activation boundary", expected > 100)
            val selected = ChartSelection(stored.id, stored.cells.filterNot { it.cancelled }.take(4).map { it.name })
            library.activate(selected)
            assertEquals(selected, ChartLibrary(root).snapshot().active)
            assertEquals(4, ChartLibrary(root).paths(selected).size)
            File(output, "noaa-state-result.json").writeText("""{"source":"https://www.charts.noaa.gov/ENCs/MA_ENCs.zip","sha256":"${ChartLibrary.sha256(archive)}","cells":$expected,"nativeValidated":$validated,"updates":${stored.cells.sumOf { it.update }},"selected":4,"restartPreserved":true,"success":true}""")
        } finally { root.deleteRecursively() }
    }
}

class NoaaBostonUpdateIntegrationTest {
    @Test fun incrementalBostonUpdatesUseTheSameNativeGeometryAsCompleteChain() = runBlocking {
        val executable = System.getenv("NO5_S57_VALIDATOR")?.let(::File)
        Assume.assumeTrue("Native integration probe explicitly selected", executable?.isFile == true)
        val output = File(System.getenv("NO5_IMPORT_EVIDENCE") ?: "build/noaa-import-evidence").absoluteFile.apply { mkdirs() }
        val assets = File("../chart-engine/src/main/assets/charts/noaa")
        fun zip(range: IntRange): ByteArray = java.io.ByteArrayOutputStream().also { bytes ->
            java.util.zip.ZipOutputStream(bytes).use { zip -> range.forEach { index ->
                val file = File(assets, "US5BOSCC.%03d".format(index)); zip.putNextEntry(java.util.zip.ZipEntry(file.name)); zip.write(file.readBytes()); zip.closeEntry()
            } }
        }.toByteArray()
        var invocation = 0; var currentHash = ""
        suspend fun validate(paths: List<File>, cache: File) {
            val tag = "boston-update-${++invocation}"; val list = File(output, "$tag-paths.txt").apply { writeText(paths.joinToString("\n") { it.absolutePath }) }
            val process = ProcessBuilder(executable!!.absolutePath, File("../chart-engine/src/main/assets").absolutePath,
                cache.absolutePath, list.absolutePath, File(output, "$tag-native.txt").absolutePath).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText(); assertEquals(log, 0, process.waitFor())
            val prepared = cache.walkTopDown().filter { it.isFile && it.extension == "senc" }.toList()
            assertEquals("Single-cell native preflight must publish exactly one geometry cache", 1, prepared.size)
            currentHash = ChartLibrary.sha256(prepared.single())
        }
        val root = Files.createTempDirectory("no5-boston-update").toFile()
        val fullRoot = Files.createTempDirectory("no5-boston-full").toFile()
        try {
            val library = ChartLibrary(root)
            val first = library.importZip("Boston update 2", zip(0..2).inputStream(), ::validate)
            val selected = ChartSelection(first.id, listOf("US5BOSCC")); library.activate(selected)
            val updated = library.importZip("Boston updates 3+4", zip(3..4).inputStream(), ::validate)
            val incrementalHash = currentHash
            assertEquals(4, updated.cells.single().update); assertEquals(selected, library.snapshot().active)
            ChartLibrary(fullRoot).importZip("Boston complete update 4", zip(0..4).inputStream(), ::validate)
            assertEquals("Incremental copy must produce identical production SENC geometry", currentHash, incrementalHash)
            File(output, "boston-update-result.json").writeText("""{"baseEdition":5,"fromUpdate":2,"toUpdate":4,"oldSelectionPreserved":true,"nativeSencIdentical":true,"sha256":"$currentHash","success":true}""")
        } finally { root.deleteRecursively(); fullRoot.deleteRecursively() }
    }
}
