package com.no5.helm.library

import com.no5.helm.chart.ChartResidentBudget
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ChartResidentSelectionTest {
    private lateinit var root: File
    private val json = Json { encodeDefaults = true }
    private val packageId = "f".repeat(36)
    @Before fun setup() { root = Files.createTempDirectory("chart-resident-selection").toFile() }
    @After fun cleanup() { root.deleteRecursively() }
    private fun directory(id: String = packageId) = File(root, "packages/$id").apply { mkdirs() }
    private fun selection(pack: ChartPackage) = ChartSelection(pack.id, pack.cells.map { it.name })
    private fun metadataFixture() = Json.parseToJsonElement(javaClass.getResource("/native-cell-metadata-v1.json")!!.readText())
        .jsonObject.getValue("cells").jsonArray.first().jsonObject
    private fun packageFixture(count: Int, metadata: Boolean = true): ChartPackage {
        val fixture = metadataFixture()
        val template = ChartCellMetadataDecoder.decode("US5BOSCC.000", fixture.getValue("metadata").toString())
        // Scale/coverage are a real P1 schema fixture; the 150/400 source descriptors
        // are synthetic cardinality tests, not 400 real NOAA charts or memory trials.
        val cells = (0 until count).map { order ->
            val name = "US5T%04d".format(order)
            val cell = ChartCell(name, 1, 1, "$name.000", "20261010",
                linkedMapOf("$name.000" to "a".repeat(64), "$name.001" to "b".repeat(64)))
            if (metadata) cell.copy(catalogMetadata = ChartMetadataContract.attach(cell, order, template.copy(path = cell.base))) else cell
        }
        return ChartPackage(packageId, "descriptor test", 0, cells).also(::writePack)
    }
    private fun writePack(pack: ChartPackage) {
        File(directory(pack.id), "package.json").writeText(json.encodeToString(pack))
        File(directory(pack.id), "spatial-index-v2.json").writeText(json.encodeToString(
            ChartCatalogIndex(cells = pack.cells.mapNotNull { it.catalogMetadata })))
    }

    @Test fun selection150And400SurvivesRestartWithResidentBudgetTwoAndNoChartByteIO() = runBlocking {
        for (count in listOf(150, 400)) {
            val pack = packageFixture(count)
            val library = ChartLibrary(root)
            val bound = library.residentSelection(selection(pack))
            assertFalse(bound.dataDirectory.exists()) // No S57 contents are available to scan.
            assertEquals(count, bound.planning.cells.size)
            assertTrue(bound.planning.cells.all { it.metadata != null })
            val transport = ChartResidentAdapter.catalog(bound.planning, bound.pack, bound.dataDirectory, ChartResidentBudget(cells = 2))
            assertEquals(count, transport.sources.size); assertEquals(2, transport.budget.cells)
            library.activateResident(bound)
            val reopened = ChartLibrary(root)
            val snapshot = reopened.snapshot()
            assertEquals(selection(pack), snapshot.active); assertEquals(2, snapshot.selectionVersion)
            assertEquals(bound.release, snapshot.activeRelease)
            val restored = reopened.residentSelection(snapshot.active!!, snapshot.activeRelease)
            assertEquals(count, restored.planning.cells.size)
            assertEquals(bound.planning.generation, restored.planning.generation)
        }
    }

    @Test fun realBostonFullFiveFileSourceAndP1IdentityCanBeBoundWithoutReadingS57() = runBlocking {
        val fixture = metadataFixture()
        val files = fixture.getValue("sourceFiles").jsonObject.mapValues { it.value.jsonPrimitive.content }
        val original = ChartCell("US5BOSCC", 5, 4, "US5BOSCC.000", "20261010", files)
        val metadata = ChartMetadataContract.attach(original, 0, ChartCellMetadataDecoder.decode(original.base,
            fixture.getValue("metadata").toString()))
        val pack = ChartPackage(packageId, "real Boston descriptor", 0, listOf(original.copy(catalogMetadata = metadata)))
        writePack(pack)
        val bound = ChartLibrary(root).residentSelection(selection(pack))
        assertFalse(bound.dataDirectory.exists())
        assertEquals(fixture.getValue("catalogSourceKey").jsonPrimitive.content, bound.release.cells.single().sourceKey)
        assertEquals(5, ChartResidentAdapter.catalog(bound.planning, bound.pack, bound.dataDirectory,
            ChartResidentBudget(cells = 2)).sources.single().files.size)
    }

    @Test fun v1MigrationIsReadOnlyUntilPublicationThenPreservesExactOriginalBytes() = runBlocking {
        val pack = packageFixture(150)
        val old = ChartSelection(pack.id, listOf(pack.cells.first().name))
        val bytes = (" \n" + json.encodeToString(old) + "\n\t").toByteArray()
        File(root, "active.json").writeBytes(bytes)
        val library = ChartLibrary(root)
        val snapshot = library.snapshot()
        assertEquals(old, snapshot.active); assertEquals(1, snapshot.selectionVersion); assertNull(snapshot.activeRelease)
        val bound = library.residentSelection(selection(pack))
        assertArrayEquals(bytes, File(root, "active.json").readBytes())
        assertFalse(File(root, "active-v1-preserved.json").exists())
        library.activateResident(bound)
        assertArrayEquals(bytes, File(root, "active-v1-preserved.json").readBytes())
        assertEquals(150, library.snapshot().active!!.cells.size)
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<ChartSelection>(File(root, "active.json").readText())
        } // The old strict decoder must fail rather than load a partial selection.
        library.activateResident(library.residentSelection(old))
        assertArrayEquals(bytes, File(root, "active-v1-preserved.json").readBytes())
        assertEquals(2, library.snapshot().selectionVersion)
    }

    @Test fun legacyWriterCannotDowngradeOrTruncateAnExistingV2Selection() = runBlocking {
        val pack = packageFixture(150); val library = ChartLibrary(root)
        library.activateResident(library.residentSelection(selection(pack)))
        val before = File(root, "active.json").readBytes()
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            library.activate(ChartSelection(pack.id, listOf(pack.cells.first().name)))
        } }
        assertThrows(IllegalArgumentException::class.java) { library.paths(selection(pack)) }
        assertArrayEquals(before, File(root, "active.json").readBytes())
        assertEquals(150, library.snapshot().active!!.cells.size)
    }

    @Test fun unknownSelectionVersionsRemainIntactAndBlockBothWriters() = runBlocking {
        val pack = packageFixture(2); val library = ChartLibrary(root)
        val bound = library.residentSelection(selection(pack))
        for (version in listOf("3", "\"newer\"", "null")) {
            val text = "{\"version\":$version,\"packageId\":\"${pack.id}\",\"cells\":[],\"future\":true}"
            File(root, "active.json").writeText(text)
            val snapshot = library.snapshot()
            assertNull(snapshot.active); assertTrue(snapshot.issues.single().contains("App-Version"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { library.activateResident(bound) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { library.activate(selection(pack)) } }
            assertEquals(text, File(root, "active.json").readText())
        }
    }

    @Test fun sourceReplacementInvalidatesStaleBindingAndPinnedRestartWithoutChangingActiveFile() = runBlocking {
        val pack = packageFixture(2); val library = ChartLibrary(root)
        val bound = library.residentSelection(selection(pack)); library.activateResident(bound)
        val before = File(root, "active.json").readBytes()
        val replaced = pack.copy(cells = pack.cells.mapIndexed { i, cell -> if (i == 0)
            cell.copy(files = cell.files + (cell.base to "c".repeat(64)), catalogMetadata = null) else cell })
        writePack(replaced)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { library.activateResident(bound) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { library.residentSelection(bound.selection, bound.release) } }
        val snapshot = library.snapshot(); assertNull(snapshot.active); assertEquals(2, snapshot.selectionVersion)
        assertArrayEquals(before, File(root, "active.json").readBytes())
        assertEquals(2, library.residentSelection(selection(replaced)).planning.cells.size) // Explicit new selection can be rebound.
    }

    @Test fun manipulatedMetadataCannotDropSourcesAndInvalidatesAnAlreadyBoundPlanningGeneration() = runBlocking {
        val pack = packageFixture(2); val library = ChartLibrary(root)
        val bound = library.residentSelection(selection(pack)); library.activateResident(bound)
        val before = File(root, "active.json").readBytes()
        val invalid = pack.cells.map { it.catalogMetadata!!.copy(sourceKey = "d".repeat(64)) }
        File(directory(), "spatial-index-v2.json").writeText(json.encodeToString(ChartCatalogIndex(cells = invalid)))
        val conservative = library.residentSelection(bound.selection, bound.release)
        assertEquals(2, conservative.planning.cells.size)
        assertTrue(conservative.planning.cells.all { it.metadata == null && it.bounds == null })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { library.activateResident(bound) } }
        assertArrayEquals(before, File(root, "active.json").readBytes())
    }

    @Test fun v1ExtentFallbackRemainsAvailableWithoutMetadataBackfillOrGeometry() = runBlocking {
        val pack = packageFixture(2, metadata = false)
        val extent = ChartSelectionExtent(42.3, 42.4, -71.1, -71.0)
        File(directory(), "spatial-index-v1.json").writeText(json.encodeToString(ChartSpatialIndex(cells =
            pack.cells.map { ChartSpatialEntry(it.name, it.files, extent) })))
        val bound = ChartLibrary(root).residentSelection(selection(pack))
        assertTrue(bound.planning.cells.all { it.metadata == null && it.bounds == extent })
        assertFalse(bound.dataDirectory.exists())
    }

    @Test fun malformedChainsAndUnsafePathsFailBeforeAnyGeometryOrSelectionPublication() = runBlocking {
        val pack = packageFixture(1); val original = pack.cells.single()
        val invalid = listOf(original.copy(files = mapOf(original.base to "a".repeat(64))),
            original.copy(base = "../${original.base}", files = original.files.mapKeys { "../${it.key}" }),
            original.copy(files = original.files.mapValues { "invalid" }))
        for (cell in invalid) {
            writePack(pack.copy(cells = listOf(cell)))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { ChartLibrary(root).residentSelection(selection(pack)) } }
            assertFalse(File(root, "active.json").exists())
        }
    }

    @Test fun symlinkedDataRootCannotEscapeTheExistingPackage() = runBlocking {
        val pack = packageFixture(1)
        val external = File(root, "other-data").apply { mkdirs() }
        Files.createSymbolicLink(File(directory(), "data").toPath(), external.toPath())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { ChartLibrary(root).residentSelection(selection(pack)) } }
        assertFalse(File(root, "active.json").exists())
    }

    @Test fun cancellationAndAtomicWriteFailureRetainPreviousSelectionAndV1Backup() = runBlocking {
        val pack = packageFixture(150); val library = ChartLibrary(root)
        val old = ChartSelection(pack.id, listOf(pack.cells.first().name))
        val before = json.encodeToString(old).toByteArray(); File(root, "active.json").writeBytes(before)
        val bound = library.residentSelection(selection(pack))
        val cancelled = async(start = CoroutineStart.LAZY) { library.activateResident(bound) }
        cancelled.cancel()
        assertThrows(CancellationException::class.java) { runBlocking { cancelled.await() } }
        assertArrayEquals(before, File(root, "active.json").readBytes())
        val blocked = File(root, "active.json.tmp").apply { mkdirs(); File(this, "occupied").writeText("keep") }
        assertThrows(IOException::class.java) { runBlocking { library.activateResident(bound) } }
        assertArrayEquals(before, File(root, "active.json").readBytes())
        assertArrayEquals(before, File(root, "active-v1-preserved.json").readBytes())
        blocked.deleteRecursively()
        library.activateResident(bound)
        assertEquals(150, ChartLibrary(root).snapshot().active!!.cells.size)
    }

    @Test fun newestNoaaCancellationPreventsBindingOldReleaseAndPreservesSelection() = runBlocking {
        val pack = packageFixture(2); val library = ChartLibrary(root)
        val bound = library.residentSelection(selection(pack)); library.activateResident(bound)
        val before = File(root, "active.json").readBytes()
        writePack(pack.copy(id = "e".repeat(36), cells = listOf(pack.cells.first().copy(update = 2, cancelled = true)), importedAt = 1))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { library.residentSelection(bound.selection, bound.release) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { library.activateResident(bound) } }
        assertArrayEquals(before, File(root, "active.json").readBytes())
    }

    @Test fun bindingCollectionsCannotBeChangedViaAliases() = runBlocking<Unit> {
        val pack = packageFixture(2); val binding = ChartLibrary(root).residentSelection(selection(pack))
        assertThrows(UnsupportedOperationException::class.java) { (binding.selection.cells as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (binding.pack.cells as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (binding.pack.cells.first().files as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (binding.release.cells as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (binding.pack.cells.first().catalogMetadata!!.coverage.first() as MutableList).clear() }
    }
}
