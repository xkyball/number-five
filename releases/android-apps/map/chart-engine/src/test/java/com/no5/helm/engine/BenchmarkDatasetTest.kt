package com.no5.helm.engine

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BenchmarkDatasetTest {
    private val assets = File("src/main/assets/charts/noaa")
    private fun bases(directory: File) = directory.listFiles()!!.filter { it.extension == "000" }.map { it.absolutePath }
    @Test fun realBostonCellsHaveOrderIndependentContentIdentityAndVersions() {
        val paths = bases(assets)
        val report = benchmarkDataset(paths)
        assertEquals(report, benchmarkDataset(paths.reversed()))
        assertEquals(BostonBenchmarkRecipe.cells, report.getValue("cells").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet())
        assertTrue(report.getValue("cells").jsonArray.all { it.jsonObject.getValue("edition").jsonPrimitive.int > 0 && it.jsonObject.getValue("files").jsonArray.isNotEmpty() })
        assertEquals(64, report.getValue("contentSha256").jsonPrimitive.content.length)
        assertFalse(report.toString().contains(assets.absolutePath))
    }
    @Test fun absentUpdateRejectsBenchmarkInsteadOfComparingDifferentData() {
        val directory = Files.createTempDirectory("benchmark-dataset").toFile()
        try {
            assets.listFiles()!!.forEach { it.copyTo(File(directory, it.name)) }
            assertTrue(File(directory, "US5BOSCC.002").delete())
            try { benchmarkDataset(bases(directory)); fail("Incomplete chain must fail") } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("chain")) }
        } finally { directory.deleteRecursively() }
    }
    @Test fun nonBostonSelectionCannotBeBenchmarkedAsBoston() {
        try { benchmarkDataset(listOf("/private/path/OTHER.000")); fail("Must reject") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("four Boston")) }
    }
}
