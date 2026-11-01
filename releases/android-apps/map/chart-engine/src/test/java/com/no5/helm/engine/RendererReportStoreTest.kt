package com.no5.helm.engine

import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files

class RendererReportStoreTest {
    private lateinit var directory: File
    @Before fun setup() { directory = Files.createTempDirectory("renderer-report-test").toFile() }
    @After fun cleanup() { directory.deleteRecursively() }
    @Test fun newStoreHasNoReportAndRejectsEmptyOverwrite() {
        val store = RendererReportStore(directory); assertNull(store.latest())
        try { store.save(""); fail("Empty report rejected") } catch (_: IllegalArgumentException) {}
        assertNull(store.latest())
    }
    @Test fun completedReportsSurviveNewStoreAndEachArchiveIsRetained() {
        val store = RendererReportStore(directory); store.save("{\"frames\":9}"); store.save("{\"frames\":30}")
        assertEquals("{\"frames\":30}", RendererReportStore(directory).latest())
        val archived = directory.listFiles()!!.filter { it.name.startsWith("report-") }
        assertEquals(2, archived.size); assertEquals(setOf("{\"frames\":9}", "{\"frames\":30}"), archived.map { it.readText() }.toSet())
        assertFalse(directory.listFiles()!!.any { it.extension == "tmp" })
    }
    @Test fun incompleteWriterFileDoesNotReplaceLastCompletedReport() {
        val store = RendererReportStore(directory); store.save("{\"complete\":true}")
        File(directory, "latest.json.tmp").writeText("incomplete")
        assertEquals("{\"complete\":true}", RendererReportStore(directory).latest())
    }
    @Test fun benchmarkHasStableRunAsFilenameAndCannotOverwriteAnotherRun() {
        val store = RendererReportStore(directory)
        val id = "9b6a0377-1b5d-4491-8abc-cfa4f9e15c3f"
        val name = store.saveBenchmark(id, "{\"outcome\":\"cancelled\"}")
        assertEquals("benchmark-$id.json", name)
        assertEquals(store.latest(), File(directory, name).readText())
        try { store.saveBenchmark(id, "overwrite"); fail("Duplicate rejected") } catch (_: IllegalStateException) {}
        try { store.saveBenchmark("../escape", "invalid"); fail("Path rejected") } catch (_: IllegalArgumentException) {}
        assertFalse(directory.listFiles()!!.any { it.extension == "tmp" })
    }
}
