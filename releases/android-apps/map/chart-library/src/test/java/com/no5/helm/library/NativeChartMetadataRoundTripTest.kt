package com.no5.helm.library

import com.no5.helm.chart.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.nio.file.Files
import java.util.zip.*

/** Frozen output of the production native validation; source hashes are verified before reuse. */
class NativeChartMetadataRoundTripTest {
    private val assets = File("../chart-engine/src/main/assets/charts/noaa")
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/native-cell-metadata-v1.json")!!.readText()).jsonObject
        .getValue("cells").jsonArray.map { it.jsonObject }
    private lateinit var root: File
    @Before fun setup() { root = Files.createTempDirectory("native-cell-roundtrip").toFile() }
    @After fun cleanup() { root.deleteRecursively() }
    private fun payload(path: File): ValidatedChartCell {
        val entry = fixture.single { it.getValue("cell").jsonPrimitive.content == path.nameWithoutExtension }
        return ChartCellMetadataDecoder.decode(path.absolutePath, entry.getValue("metadata").toString())
    }
    private fun archive(): InputStream = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> for (cell in fixture) for ((name, hash) in cell.getValue("sourceFiles").jsonObject) {
            val file = File(assets, name); assertEquals(hash.jsonPrimitive.content, ChartLibrary.sha256(file))
            zip.putNextEntry(ZipEntry(name)); zip.write(file.readBytes()); zip.closeEntry()
        } }
    }.toByteArray().inputStream()
    @Test fun allNativeFieldsRoundTripThroughPublishedSidecarOnSameFourByteChains() = runBlocking {
        val library = ChartLibrary(root)
        val pack = library.importZipWithMetadata("Real native Boston metadata", archive(), { paths, _, _ -> paths.map(::payload) })
        assertEquals(4, pack.cells.size)
        val reopened = ChartLibrary(root).snapshot().packages.single()
        for ((order, cell) in reopened.cells.withIndex()) {
            val base = File(root, "packages/${pack.id}/data/${cell.base}")
            val native = payload(base); val catalog = cell.catalogMetadata!!
            assertEquals(native, catalog.validated(base.absolutePath))
            assertEquals(fixture.single { it.getValue("cell").jsonPrimitive.content == cell.name }.getValue("catalogSourceKey").jsonPrimitive.content, catalog.sourceKey)
            assertEquals(order, catalog.order); assertEquals(cell.files, catalog.files)
            assertEquals(12000, catalog.nativeScale); assertEquals(5, catalog.usage)
            assertTrue(catalog.coverage.isNotEmpty()); assertTrue(catalog.capabilities.exteriorRingsOnly)
            assertFalse(catalog.capabilities.polygonHoles); assertFalse(catalog.capabilities.multiPolygons)
        }
        assertNull(reopened.cells.firstOrNull { it.catalogMetadata == null }); assertNull(library.snapshot().active)
    }
    @Test fun oldFourCellPackageBackfillsOnlyRequestedCellAndRetainsOtherUnknowns() = runBlocking {
        val library = ChartLibrary(root)
        val pack = library.importZipWithProgress("Old package", archive(), { _, _, _ -> })
        var calls = 0
        library.ensureCellMetadata(pack.id, "US5BOSCD") { paths, _ -> calls++; assertEquals(listOf("US5BOSCD.000"), paths.map { it.name }); paths.map(::payload) }
        val cells = library.snapshot().packages.single().cells
        assertEquals(1, calls); assertEquals(4, cells.size); assertEquals(listOf("US5BOSCD"), cells.filter { it.catalogMetadata != null }.map { it.name })
        assertTrue(cells.all { it.selectionExtent != null })
    }
    @Test fun decoderRejectsFutureContractAndInvalidCoordinateCapabilities() {
        val payload = fixture.first().getValue("metadata").toString()
        for (bad in listOf(payload.replace("\"metadataVersion\":1", "\"metadataVersion\":99"),
            payload.replace("\"wrappedDateline\":false", "\"wrappedDateline\":true"),
            payload.replace("\"latitudeLimit\":85", "\"latitudeLimit\":90"))) {
            try { ChartCellMetadataDecoder.decode("cell.000", bad); fail("Unsupported native contract") }
            catch (_: IllegalArgumentException) {}
        }
    }
}
