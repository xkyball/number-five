package com.no5.helm.library

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ChartSpatialIndexTest {
    private lateinit var root: File
    private val base = File("../chart-engine/src/main/assets/charts/noaa/US5BOSCC.000")
    @Before fun before() { root = Files.createTempDirectory("chart-spatial-test").toFile() }
    @After fun after() { root.deleteRecursively() }
    private fun import(): Pair<ChartLibrary, ChartPackage> {
        val zip = ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use { out ->
            out.putNextEntry(ZipEntry(base.name)); out.write(base.readBytes()); out.closeEntry()
        } }.toByteArray()
        val library = ChartLibrary(root)
        return library to runBlocking { library.importZip("Boston", zip.inputStream(), { _, _ -> }) }
    }
    private fun record(fields: List<Pair<String, ByteArray>>): ByteArray {
        val width = 14; val start = 25 + fields.size * width; val length = start + fields.sumOf { it.second.size + 1 }
        val leader = "00000 D     00000   5504".toByteArray().copyOf(24)
        "%05d".format(length).toByteArray().copyInto(leader, 0)
        "%05d".format(start).toByteArray().copyInto(leader, 12)
        val output = ByteArrayOutputStream(); output.write(leader); var position = 0
        fields.forEach { (tag, bytes) -> output.write((tag + "%05d%05d".format(bytes.size + 1, position)).toByteArray()); position += bytes.size + 1 }
        output.write(0x1e); fields.forEach { (_, bytes) -> output.write(bytes); output.write(0x1e) }
        return output.toByteArray()
    }
    private fun parameters() = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply { put(15, 1); putInt(16, 1_000_000) }.array()
    private fun coordinates(vararg points: Pair<Double, Double>) = ByteBuffer.allocate(points.size * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
        points.forEach { (lat, lon) -> putInt((lat * 1_000_000).toInt()); putInt((lon * 1_000_000).toInt()) }
    }.array()
    @Test fun genuineBaseBoundsMatchKnownLocalCoordinateExtremes() {
        val bounds = S57SelectionExtent.read(listOf(base))!!
        assertEquals(42.3, bounds.south, 1e-7); assertEquals(42.375, bounds.north, 1e-7)
        assertEquals(-71.1, bounds.west, 1e-7); assertEquals(-71.025, bounds.east, 1e-7)
    }
    @Test fun updateCoordinatesUseBaseMultiplierAndDatelineKeepsShortEnvelope() {
        val first = File(root, "one.000").apply { writeBytes(record(listOf("DSPM" to parameters(), "SG2D" to coordinates(0.0 to 179.0, 1.0 to 179.5)))) }
        val update = File(root, "one.001").apply { writeBytes(record(listOf("SG2D" to coordinates(2.0 to -179.0)))) }
        val bounds = S57SelectionExtent.read(listOf(first, update))!!
        assertEquals(2.0, bounds.north, 0.0); assertEquals(2.0, bounds.longitudeSpan, 0.0)
        assertEquals(-180.0, bounds.centerLongitude, 0.0)
    }
    @Test fun missingMultiplierInvalidCoordinatesAndTruncationDoNotInventBounds() {
        val missing = File(root, "missing").apply { writeBytes(record(listOf("SG2D" to coordinates(0.0 to 1.0)))) }
        assertNull(S57SelectionExtent.read(listOf(missing)))
        val invalid = File(root, "invalid").apply { writeBytes(record(listOf("DSPM" to parameters(), "SG2D" to coordinates(91.0 to 1.0)))) }
        assertNull(S57SelectionExtent.read(listOf(invalid)))
        assertNull(S57SelectionExtent.read(listOf(File(root, "short").apply { writeBytes(byteArrayOf(1, 2)) })))
    }
    @Test fun manifestRemainsReadableByOldStrictSchemaAndSidecarPersistsBounds() {
        val (library, stored) = import(); val directory = File(root, "packages/${stored.id}")
        val manifest = File(directory, "package.json").readText()
        assertFalse(manifest.contains("selectionExtent")); assertFalse(manifest.contains("south"))
        val old = Json { ignoreUnknownKeys = false }.decodeFromString<ChartPackage>(manifest)
        assertNull(old.cells.single().selectionExtent); assertTrue(File(directory, "spatial-index-v1.json").isFile)
        assertEquals(stored, library.snapshot().packages.single())
        assertNotNull(ChartLibrary(root).snapshot().packages.single().cells.single().selectionExtent)
    }
    @Test fun oldPackageIndexBackfillDoesNotChangeManifestBytesOrActiveSelection() = runBlocking {
        val (library, stored) = import(); val directory = File(root, "packages/${stored.id}")
        val selection = ChartSelection(stored.id, listOf("US5BOSCC")); library.activate(selection)
        val before = File(directory, "package.json").readBytes(); val activeBefore = File(root, "active.json").readBytes()
        File(directory, "spatial-index-v1.json").delete()
        assertNull(library.snapshot().packages.single().cells.single().selectionExtent)
        library.ensureSpatialIndexes()
        assertArrayEquals(before, File(directory, "package.json").readBytes()); assertArrayEquals(activeBefore, File(root, "active.json").readBytes())
        assertEquals(selection, library.snapshot().active); assertNotNull(library.snapshot().packages.single().cells.single().selectionExtent)
    }
    @Test fun mismatchedOrBrokenSidecarLeavesPackageReadableWithoutSpatialGuess() {
        val (library, stored) = import(); val indexFile = File(root, "packages/${stored.id}/spatial-index-v1.json")
        val value = ChartSpatialIndex(cells = listOf(ChartSpatialEntry("US5BOSCC", emptyMap(), ChartSelectionExtent(0.0, 1.0, 0.0, 1.0))))
        indexFile.writeText(Json.encodeToString(value))
        assertNull(library.snapshot().packages.single().cells.single().selectionExtent); assertTrue(library.snapshot().issues.isEmpty())
        indexFile.writeText("broken"); assertEquals(1, library.snapshot().packages.size); assertNull(library.snapshot().packages.single().cells.single().selectionExtent)
    }
    @Test fun cancelledCoroutineCannotBeSwallowedByOptionalExtentReader() {
        try { S57SelectionExtent.read(listOf(base)) { throw kotlinx.coroutines.CancellationException("stop") }; fail() }
        catch (_: kotlinx.coroutines.CancellationException) { }
    }
}
