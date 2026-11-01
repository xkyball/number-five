package com.no5.helm.library

import com.no5.helm.chart.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.nio.file.Files
import java.util.zip.*

class ChartCatalogMetadataTest {
    private lateinit var root: File
    private val assets = File("../chart-engine/src/main/assets/charts/noaa")
    private val json = Json { encodeDefaults = true }
    @Before fun setup() { root = Files.createTempDirectory("chart-catalog-metadata").toFile() }
    @After fun cleanup() { root.deleteRecursively() }
    private fun archive(updates: Int = 4): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> for (i in 0..updates) {
            val name = "US5BOSCC.%03d".format(i)
            zip.putNextEntry(ZipEntry("ENC_ROOT/$name")); zip.write(File(assets, name).readBytes()); zip.closeEntry()
        } }
    }.toByteArray()
    private fun value(path: File) = ValidatedChartCell(path.absolutePath, 1, "no5-s57-osenc201-metadata-v1",
        "a".repeat(64), "b".repeat(64), 1, 12000, 5,
        ChartCellBounds(42.3, -71.1, 42.4, -71.0),
        listOf(listOf(ChartCellCoordinate(42.3, -71.1), ChartCellCoordinate(42.3, -71.0), ChartCellCoordinate(42.4, -71.0))),
        emptyList(), ChartCoverageCapabilities())
    private fun import(library: ChartLibrary, updates: Int = 4, metadata: Boolean = true) = runBlocking {
        library.importZipWithMetadata("Boston", archive(updates).inputStream(), { paths, _, _ ->
            if (metadata) paths.map(::value) else emptyList()
        })
    }
    private fun directory(pack: ChartPackage) = File(root, "packages/${pack.id}")
    private fun sidecar(pack: ChartPackage) = File(directory(pack), "spatial-index-v2.json")
    private fun reject(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }

    @Test fun nativeMetadataPublishesBesideImmutableManifestAndReopens() {
        val library = ChartLibrary(root); val pack = import(library); val meta = pack.cells.single().catalogMetadata!!
        assertEquals(12000, meta.nativeScale); assertEquals(5, meta.usage); assertEquals(0, meta.order)
        assertEquals(pack.cells.single().files, meta.files)
        assertEquals(meta, ChartLibrary(root).snapshot().packages.single().cells.single().catalogMetadata)
        assertFalse(File(directory(pack), "package.json").readText().contains("catalogMetadata"))
        for ((path, hash) in pack.cells.single().files) assertEquals(hash, ChartLibrary.sha256(File(directory(pack), "data/$path")))
        assertNull(library.snapshot().active)
    }
    @Test fun baseAndUpdateGenerationKeysDifferButLocationDoesNotDefineIdentity() {
        val library = ChartLibrary(root); val base = import(library, 0); val updated = import(library)
        assertNotEquals(base.cells.single().catalogMetadata!!.sourceKey, updated.cells.single().catalogMetadata!!.sourceKey)
        val cell = updated.cells.single(); val moved = cell.copy(files = cell.files.mapKeys { "other/${File(it.key).name}" })
        assertEquals(ChartMetadataContract.sourceKey(cell), ChartMetadataContract.sourceKey(moved))
    }
    @Test fun staleHashMapAndSourceKeyBecomeUnknownWithoutDroppingTheCell() {
        val library = ChartLibrary(root); val pack = import(library); val original = pack.cells.single().catalogMetadata!!
        for (bad in listOf(original.copy(files = original.files.mapValues { "c".repeat(64) }), original.copy(sourceKey = "c".repeat(64)))) {
            sidecar(pack).writeText(json.encodeToString(ChartCatalogIndex(cells = listOf(bad))))
            val cell = library.snapshot().packages.single().cells.single()
            assertNull(cell.catalogMetadata); assertNotNull(cell.selectionExtent)
            assertEquals(1, library.paths(ChartSelection(pack.id, listOf(cell.name))).size)
        }
    }
    @Test fun corruptFutureDuplicateAndInvalidCoverageSidecarsFallBackToV1() {
        val library = ChartLibrary(root); val pack = import(library); val original = pack.cells.single().catalogMetadata!!
        val invalidRing = original.copy(coverage = listOf(listOf(CatalogCoordinate(99.0, 1.0))))
        val versions = listOf("broken", json.encodeToString(ChartCatalogIndex(99, listOf(original))),
            json.encodeToString(ChartCatalogIndex(cells = listOf(original, original))),
            json.encodeToString(ChartCatalogIndex(cells = listOf(invalidRing))),
            json.encodeToString(ChartCatalogIndex(cells = listOf(original.copy(order = 1)))),
            json.encodeToString(ChartCatalogIndex(cells = listOf(original.copy(nativeScale = 0)))))
        for (text in versions) {
            sidecar(pack).writeText(text)
            val cell = library.snapshot().packages.single().cells.single()
            assertNull(cell.catalogMetadata); assertNotNull(cell.selectionExtent)
        }
    }
    @Test fun oldPackageBackfillsOneCellWithoutChangingChartManifestOrSelection() = runBlocking {
        val library = ChartLibrary(root); val pack = import(library, metadata = false)
        sidecar(pack).delete(); val manifest = File(directory(pack), "package.json").readBytes()
        val selection = ChartSelection(pack.id, listOf("US5BOSCC")); library.activate(selection)
        var calls = 0
        val metadata = library.ensureCellMetadata(pack.id, "US5BOSCC") { paths, _ -> calls++; assertEquals(1, paths.size); paths.map(::value) }
        assertNotNull(metadata); assertEquals(selection, library.snapshot().active)
        assertArrayEquals(manifest, File(directory(pack), "package.json").readBytes())
        library.ensureCellMetadata(pack.id, "US5BOSCC") { _, _ -> error("Already verified metadata must be reused") }
        assertEquals(1, calls); assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
    }
    @Test fun cancelledBackfillLeavesPreviousSidecarAndCleansStaging() = runBlocking {
        val library = ChartLibrary(root); val pack = import(library, metadata = false); val before = sidecar(pack).readBytes()
        try { library.ensureCellMetadata(pack.id, "US5BOSCC") { _, _ -> throw CancellationException("cancel") }; fail() }
        catch (_: CancellationException) {}
        assertArrayEquals(before, sidecar(pack).readBytes()); assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
    }
    @Test fun tamperedChartBytesCannotReuseExistingMetadata() = runBlocking {
        val library = ChartLibrary(root); val pack = import(library); val cell = pack.cells.single()
        val file = File(directory(pack), "data/${cell.files.keys.last()}"); file.appendBytes(byteArrayOf(1))
        reject { runBlocking { library.ensureCellMetadata(pack.id, cell.name) { _, _ -> error("Must reject before native parse") } } }
    }
    @Test fun nativeMutationBeforeMetadataPublicationFailsImportAtomically() {
        val library = ChartLibrary(root); val old = import(library)
        reject { runBlocking { library.importZipWithMetadata("mutating", archive().inputStream(), { paths, _, _ ->
            val result = paths.map(::value); paths.single().appendBytes(byteArrayOf(1)); result
        }) } }
        assertEquals(listOf(old), library.snapshot().packages); assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
    }
    @Test fun unknownScaleAndCoverageAreRepresentedWithoutGuessing() {
        val library = ChartLibrary(root)
        val pack = runBlocking { library.importZipWithMetadata("unknown", archive().inputStream(), { paths, _, _ ->
            paths.map { value(it).copy(nativeScale = null, usage = null, bounds = null, coverage = emptyList()) }
        }) }
        val meta = pack.cells.single().catalogMetadata!!
        assertNull(meta.nativeScale); assertNull(meta.usage); assertNull(meta.nativeBounds)
        assertNotNull(meta.conservativeBounds); assertEquals(1, library.paths(ChartSelection(pack.id, listOf(meta.name))).size)
    }
    @Test fun nativeCapabilityLimitsRejectWrappedBoundsAndFalseHoleSupport() {
        val ordinary = value(File("US5BOSCC.000"))
        assertFalse(ChartMetadataContract.usable(ordinary.copy(bounds = ChartCellBounds(0.0, 179.0, 1.0, -179.0))))
        assertFalse(ChartMetadataContract.usable(ordinary.copy(bounds = ChartCellBounds(86.0, 1.0, 87.0, 2.0))))
        assertFalse(ChartMetadataContract.usable(ordinary.copy(capabilities = ChartCoverageCapabilities(polygonHoles = true))))
        val cell = ChartCell("US5BOSCC", 1, 0, "US5BOSCC.000", "20261010", mapOf("US5BOSCC.000" to "a".repeat(64)),
            selectionExtent = ChartSelectionExtent(0.0, 1.0, 179.0, -179.0))
        val metadata = ChartMetadataContract.attach(cell, 0, ordinary.copy(bounds = null, coverage = emptyList()))
        assertEquals(-180.0, metadata.conservativeBounds!!.west, 0.0); assertEquals(180.0, metadata.conservativeBounds.east, 0.0)
    }
    @Test fun cancelledCellNeverAcquiresOrReusesMetadata() {
        val ordinary = value(File("US5BOSCC.000"))
        val cell = ChartCell("US5BOSCC", 1, 0, "US5BOSCC.000", "20261010", mapOf("US5BOSCC.000" to "a".repeat(64)))
        val meta = ChartMetadataContract.attach(cell, 0, ordinary)
        assertFalse(ChartMetadataContract.matches(meta, cell.copy(cancelled = true), 0))
    }
    @Test fun legacyValidationStillImportsUnknownMetadata() {
        val library = ChartLibrary(root)
        val pack = runBlocking { library.importZip("legacy", archive().inputStream(), { _, _ -> }) }
        assertNull(pack.cells.single().catalogMetadata); assertEquals(1, library.paths(ChartSelection(pack.id, listOf("US5BOSCC"))).size)
    }
    @Test fun explicitExclusionRingSurvivesTheSidecarWithoutBecomingAHoleCapability() {
        val library = ChartLibrary(root)
        val ring = listOf(ChartCellCoordinate(42.31, -71.09), ChartCellCoordinate(42.31, -71.08), ChartCellCoordinate(42.32, -71.08))
        val pack = runBlocking { library.importZipWithMetadata("exclusion", archive().inputStream(), { paths, _, _ ->
            paths.map { value(it).copy(exclusions = listOf(ring)) }
        }) }
        val meta = ChartLibrary(root).snapshot().packages.single().cells.single().catalogMetadata!!
        assertEquals(ring, meta.validated(pack.cells.single().base).exclusions.single())
        assertFalse(meta.capabilities.polygonHoles)
    }
    @Test fun wsvNativeMetadataIsProducerBoundAndKeepsInlandUsageWithoutWeakeningNoaa() {
        val cell = ChartCell("1W7UH010", 19, 0, "1W7UH010.000", "20260714",
            mapOf("1W7UH010.000" to "a".repeat(64)), provider = S57Profiles.WSV)
        val native = value(File(cell.base)).copy(usage = 7)
        assertFalse(ChartMetadataContract.usable(native))
        assertTrue(ChartMetadataContract.usable(native, S57Profiles.WSV))
        assertFalse(ChartMetadataContract.usable(native.copy(usage = 5), S57Profiles.WSV))
        val meta = ChartMetadataContract.attach(cell, 0, native)
        assertEquals(S57Profiles.WSV, meta.provider); assertEquals(7, meta.usage)
        assertTrue(ChartMetadataContract.matches(meta, cell, 0))
        assertFalse(ChartMetadataContract.matches(meta.copy(provider = S57Profiles.NOAA), cell, 0))
        assertNotEquals(ChartMetadataContract.sourceKey(cell), ChartMetadataContract.sourceKey(cell.copy(provider = S57Profiles.NOAA)))
    }
    @Test fun metadataDecoderRequiresExplicitVerifiedProviderForInlandUsage() {
        val fixture = Json.parseToJsonElement(javaClass.getResource("/native-cell-metadata-v1.json")!!.readText()).jsonObject
        val ordinary = fixture.getValue("cells").jsonArray.first().jsonObject.getValue("metadata").jsonObject
        val inland = JsonObject(ordinary + ("usage" to JsonPrimitive(7))).toString()
        reject { ChartCellMetadataDecoder.decode("1W7UH010.000", inland) }
        val decoded = ChartCellMetadataDecoder.decode("1W7UH010.000", inland, S57Profiles.WSV)
        assertEquals(7, decoded.usage)
        reject { ChartCellMetadataDecoder.decode("1W7UH010.000", inland, "unknown-s57") }
    }
}
