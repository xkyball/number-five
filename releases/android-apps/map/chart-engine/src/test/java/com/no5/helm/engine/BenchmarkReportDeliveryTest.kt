package com.no5.helm.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class BenchmarkReportDeliveryTest {
    @Test fun rawArchiveFailureStillDeliversCompleteReportAsLatest() {
        val directory = Files.createTempDirectory("benchmark-delivery").toFile()
        try {
            val store = RendererReportStore(directory)
            val id = "9b6a0377-1b5d-4491-8abc-cfa4f9e15c3f"
            val report = "{\"groups\":[1,2],\"outcome\":\"complete\"}"
            val result = saveBenchmarkReports(id, report, listOf("first", "second"), {
                if (it == "first") throw IOException("disk failure") else store.save(it)
            }, store::saveBenchmark)
            assertEquals("benchmark-$id.json", result.file)
            assertTrue(result.warning.contains("1 Rohgruppen"))
            assertEquals(report, store.latest())
            assertEquals(report, directory.resolve(result.file).readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun completeReportSaveFailureCannotClaimFileDelivery() {
        try {
            saveBenchmarkReports("run", "report retained by caller", emptyList(), {}, { _, _ -> throw IOException("full") })
            fail("Must report failure")
        } catch (expected: IOException) { assertEquals("full", expected.message) }
    }
}
