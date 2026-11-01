package com.no5.helm.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*

class BundledBenchmarkDatasetTest {
    @Test fun bundledAssetsAreVerbatimAndHavePinnedContentIdentityIncludingAllUpdates() {
        val assets = File("../app/src/main/assets/renderer-benchmark/boston-v1")
        val directory = Files.createTempDirectory("bundled-boston").toFile()
        try {
            assets.listFiles()!!.forEach { it.copyTo(File(directory, it.name.removeSuffix(".s57"))) }
            val paths = BostonBenchmarkRecipe.cells.sorted().map { File(directory, "$it.000").absolutePath }
            val report = bundledBenchmarkDataset(paths)
            assertEquals(BOSTON_BUNDLE_SHA256, report.getValue("contentSha256").jsonPrimitive.content)
            assertEquals(BOSTON_BUNDLE_ID, report.getValue("bundleId").jsonPrimitive.content)
            assertFalse(report.toString().contains(directory.absolutePath))
            File(directory, "US5BOSCD.006").delete()
            try { bundledBenchmarkDataset(paths); fail("Changed update version rejected") }
            catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("festen Testpaket")) }
        } finally { directory.deleteRecursively() }
    }
    @Test fun missingBundledDataExplainsRepairWithoutExportingPrivatePaths() {
        try { bundledBenchmarkDataset(bundledBenchmarkPaths("/private/user-data")); fail("Missing bundle rejected") }
        catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("vollständige APK")); assertFalse(expected.message!!.contains("/private"))
        }
    }
}
