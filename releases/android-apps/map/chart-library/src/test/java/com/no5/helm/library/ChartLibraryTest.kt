package com.no5.helm.library

import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.nio.file.Files
import java.util.zip.*

class ChartLibraryTest {
    private lateinit var root: File
    private val assets = File("../chart-engine/src/main/assets/charts/noaa")
    @Before fun before() { root = Files.createTempDirectory("chart-library-test").toFile() }
    @After fun after() { root.deleteRecursively() }
    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out -> files.forEach { (path, value) -> out.putNextEntry(ZipEntry(path)); out.write(value); out.closeEntry() } }
    }.toByteArray()
    private fun boston(updates: Int = 4) = (0..updates).associate { "ENC_ROOT/US5BOSCC/US5BOSCC.%03d".format(it) to File(assets, "US5BOSCC.%03d".format(it)).readBytes() }
    private fun import(library: ChartLibrary, files: Map<String, ByteArray>, validate: suspend (List<File>, File) -> Unit = { _, _ -> }) = runBlocking {
        library.importZip("NOAA test", zip(files).inputStream(), validate)
    }
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }
    @Test fun genuineNoaaBaseAndUpdatesExposeIdentification() {
        val base = S57Identification.read(File(assets, "US5BOSCC.000")); assertEquals(5, base.edition); assertEquals(0, base.update)
        val update = S57Identification.read(File(assets, "US5BOSCC.004")); assertEquals(4, update.update); assertEquals(2, update.exchangePurpose)
    }
    @Test fun importDoesNotActivateAndSelectionSurvivesRepositoryRecreation() {
        val library = ChartLibrary(root); val stored = import(library, boston())
        assertNull(library.snapshot().active); assertEquals(4, stored.cells.single().update)
        val selected = ChartSelection(stored.id, listOf("US5BOSCC")); runBlocking { library.activate(selected) }
        val reopened = ChartLibrary(root); assertEquals(selected, reopened.snapshot().active); assertEquals(1, reopened.paths(selected).size)
    }
    @Test fun updateGapAndMissingBaseAreRejected() {
        val library = ChartLibrary(root)
        rejected { import(library, boston().filterKeys { !it.endsWith(".002") }) }
        rejected { import(library, boston().filterKeys { !it.endsWith(".000") }) }
        assertTrue(library.snapshot().packages.isEmpty())
    }
    @Test fun incrementalUpdatesCopyExistingBaseWithoutModifyingIt() {
        val library = ChartLibrary(root); val old = import(library, boston(2))
        val selection = ChartSelection(old.id, listOf("US5BOSCC")); runBlocking { library.activate(selection) }
        val updated = import(library, boston().filterKeys { it.endsWith(".003") || it.endsWith(".004") })
        assertEquals(4, updated.cells.single().update); assertEquals(2, old.cells.single().update)
        assertEquals(selection, library.snapshot().active)
        runBlocking { library.activate(ChartSelection(updated.id, selection.cells)) }
        assertEquals(2, library.snapshot().packages.size)
        assertEquals(1, library.paths(selection).size)
    }
    @Test fun incrementalGapAndMismatchedEditionPreserveOldSelection() {
        val library = ChartLibrary(root); val old = import(library, boston(1))
        val selection = ChartSelection(old.id, listOf("US5BOSCC")); runBlocking { library.activate(selection) }
        rejected { import(library, boston().filterKeys { it.endsWith(".004") }) }
        val wrong = boston().filterKeys { it.endsWith(".002") }.mapValues { (_, bytes) ->
            bytes.clone().also { b -> val pattern = "US5BOSCC.002\u001f5\u001f".toByteArray(); val position = b.indices.first { i -> i + pattern.size < b.size && b.copyOfRange(i, i + pattern.size).contentEquals(pattern) }; b[position + 13] = '6'.code.toByte() }
        }
        rejected { import(library, wrong) }; assertEquals(selection, library.snapshot().active)
    }
    @Test fun completeNewEditionIsSeparateAndOldBytesAreRetained() {
        val library = ChartLibrary(root); val old = import(library, boston())
        val newer = boston().mapValues { (path, bytes) -> bytes.clone().also { b ->
            val pattern = (File(path).name + "\u001f5\u001f").toByteArray()
            val position = b.indices.first { i -> i + pattern.size < b.size && b.copyOfRange(i, i + pattern.size).contentEquals(pattern) }
            b[position + 13] = '6'.code.toByte()
        } }
        val next = import(library, newer); assertEquals(6, next.cells.single().edition); assertEquals(5, old.cells.single().edition)
        assertEquals(1, library.paths(ChartSelection(old.id, listOf("US5BOSCC"))).size)
        assertEquals(2, library.snapshot().packages.size)
    }
    @Test fun cancellationIsRecordedNotIngestedAndPreventsOldEditionReactivation() {
        val library = ChartLibrary(root); val old = import(library, boston())
        val cancelled = File(assets, "US5BOSCC.004").readBytes()
        val pattern = "US5BOSCC.004\u001f5\u001f4\u001f".toByteArray()
        val position = cancelled.indices.first { i -> i + pattern.size < cancelled.size && cancelled.copyOfRange(i, i + pattern.size).contentEquals(pattern) }
        "US5BOSCC.005\u001f0\u001f5\u001f".toByteArray().copyInto(cancelled, position)
        var ingested = -1
        val next = import(library, mapOf("US5BOSCC.005" to cancelled)) { paths, _ -> ingested = paths.size }
        assertTrue(next.cells.single().cancelled); assertEquals(0, ingested)
        rejected { library.paths(ChartSelection(old.id, listOf("US5BOSCC"))) }
        rejected { library.paths(ChartSelection(next.id, listOf("US5BOSCC"))) }
    }
    @Test fun unreadableCatalogAndSelectionReportIssuesWithoutDeletingFiles() {
        val library = ChartLibrary(root); val stored = import(library, boston())
        val file = File(root, "packages/${stored.id}/package.json").apply { writeText("broken") }
        File(root, "active.json").writeText("broken")
        assertEquals(2, library.snapshot().issues.size); assertTrue(file.isFile); assertNull(library.snapshot().active)
    }
    @Test fun editionMismatchAndFileNameMismatchAreRejected() {
        val library = ChartLibrary(root)
        val wrongEdition = boston().toMutableMap()
        wrongEdition["ENC_ROOT/US5BOSCC/US5BOSCC.001"] = File(assets, "US5BOSCD.001").readBytes()
        rejected { import(library, wrongEdition) }
        rejected { import(library, boston().mapKeys { it.key.replace("US5BOSCC", "US5BOSCD") }) }
    }
    @Test fun traversalAbsoluteWindowsAndControlPathsAreRejectedBeforePublication() {
        for (path in listOf("../escape", "/escape", "a/../../escape", "a\\escape", "C:/escape", "a/./b", "a//b", "x\u0000y")) {
            rejected { import(ChartLibrary(root), mapOf(path to byteArrayOf(1))) }
        }
        assertFalse(File(root.parentFile, "escape").exists())
    }
    @Test fun caseInsensitiveDuplicatePathsAreRejected() {
        rejected { import(ChartLibrary(root), mapOf("a.txt" to byteArrayOf(1), "A.TXT" to byteArrayOf(2))) }
    }
    @Test fun duplicateCellsInDifferentDirectoriesAreRejected() {
        val files = boston(0) + mapOf("elsewhere/US5BOSCC.000" to File(assets, "US5BOSCC.000").readBytes())
        rejected { import(ChartLibrary(root), files) }
    }
    @Test fun compressedExpandedFileAndEntryLimitsAreEnforced() {
        rejected { import(ChartLibrary(root, ImportLimits(archiveBytes = 20)), boston(0)) }
        rejected { import(ChartLibrary(root, ImportLimits(expandedBytes = 20)), boston(0)) }
        rejected { import(ChartLibrary(root, ImportLimits(fileBytes = 20)), boston(0)) }
        rejected { import(ChartLibrary(root, ImportLimits(entries = 1)), boston(1)) }
        assertTrue(ChartLibrary(root).snapshot().packages.isEmpty())
    }
    @Test fun nativeFailureKeepsExistingPackageAndSelection() {
        val library = ChartLibrary(root); val stored = import(library, boston())
        val selection = ChartSelection(stored.id, listOf("US5BOSCC")); runBlocking { library.activate(selection) }
        rejected { import(library, boston()) { _, _ -> error("native corrupt geometry") } }
        assertEquals(listOf(stored), library.snapshot().packages); assertEquals(selection, library.snapshot().active)
        assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
    }
    @Test fun cancelBeforePublicationRemovesStagingAndKeepsSelection() = runBlocking {
        val library = ChartLibrary(root); val entered = CompletableDeferred<Unit>()
        val task = launch(Dispatchers.Default) { library.importZip("cancel", zip(boston()).inputStream(), { _, _ -> entered.complete(Unit); awaitCancellation() }) }
        entered.await(); task.cancelAndJoin()
        assertTrue(library.snapshot().packages.isEmpty()); assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
    }
    @Test fun rollbackAndDifferentBytesForSameEditionAreRejected() {
        val library = ChartLibrary(root); import(library, boston())
        rejected { import(library, boston(3)) }
        val changed = boston().toMutableMap(); val key = changed.keys.first(); val bytes = changed.getValue(key).clone()
        // A feature attribute change leaves DSID valid but violates the stored same-edition base identity.
        val index = bytes.indices.first { it > 500 && bytes[it] == 'a'.code.toByte() }; bytes[index] = 'b'.code.toByte(); changed[key] = bytes
        rejected { import(library, changed) }
        assertEquals(1, library.snapshot().packages.size)
    }
    @Test fun tamperedStoredCellCannotActivate() {
        val library = ChartLibrary(root); val stored = import(library, boston())
        File(root, "packages/${stored.id}/data/${stored.cells.single().base}").appendText("tampered")
        rejected { runBlocking { library.activate(ChartSelection(stored.id, listOf("US5BOSCC"))) } }
        assertNull(library.snapshot().active)
    }
    @Test fun invalidSelectionDoesNotReplaceValidSelection() {
        val library = ChartLibrary(root); val stored = import(library, boston()); val selection = ChartSelection(stored.id, listOf("US5BOSCC"))
        runBlocking { library.activate(selection) }
        rejected { runBlocking { library.activate(selection.copy(cells = emptyList())) } }
        rejected { runBlocking { library.activate(selection.copy(cells = listOf("US5BOSDD"))) } }
        rejected { runBlocking { library.activate(selection.copy(cells = List(101) { "US5BOSCC" })) } }
        assertEquals(selection, library.snapshot().active)
    }
    @Test fun incompleteStagingIsNeverExposedAsInstalledOrActive() {
        File(root, "staging/interrupted/data").mkdirs(); File(root, "staging/interrupted/data/x").writeText("partial")
        assertTrue(ChartLibrary(root).snapshot().packages.isEmpty()); assertNull(ChartLibrary(root).snapshot().active)
    }
    @Test fun wsvImportPassesOriginalIdentifierAndBytesToNativeValidationAndStoresOwnProvider() {
        val original = S57TestBytes.cell()
        var validations = 0
        val library = ChartLibrary(root)
        val stored = import(library, mapOf("ENC_ROOT/1W7UH010.000" to original)) { paths, _ ->
            validations++
            assertEquals("1W7UH010.000", paths.single().name)
            assertArrayEquals(original, paths.single().readBytes())
            assertEquals(S57Profiles.WSV, S57Identification.read(paths.single()).provider)
        }
        assertEquals(1, validations); assertEquals(S57Profiles.WSV, stored.cells.single().provider)
        assertTrue(stored.source.startsWith("WSV Inland ENC 2.4")); assertFalse(stored.source.contains("NOAA"))
        assertNull(library.snapshot().active)
        val reopened = ChartLibrary(root).snapshot().packages.single()
        assertEquals(S57Profiles.WSV, reopened.cells.single().provider)
        assertArrayEquals(original, File(root, "packages/${stored.id}/data/${stored.cells.single().base}").readBytes())
    }
    @Test fun wsvUpdateOnlyUsesSameProducerFullChainWithoutMutatingOldPackage() {
        val library = ChartLibrary(root)
        val old = import(library, mapOf("1W7UH010.000" to S57TestBytes.cell()))
        val oldHash = ChartLibrary.sha256(File(root, "packages/${old.id}/data/1W7UH010.000"))
        val next = import(library, mapOf("1W7UH010.001" to S57TestBytes.cell("1W7UH010.001", update = 1)))
        assertEquals(1, next.cells.single().update); assertEquals(2, next.cells.single().files.size)
        assertEquals(oldHash, ChartLibrary.sha256(File(root, "packages/${old.id}/data/1W7UH010.000")))
        assertEquals(S57Profiles.WSV, next.cells.single().provider)
        rejected { import(library, mapOf("1W7UH010.003" to S57TestBytes.cell("1W7UH010.003", update = 3))) }
    }
    @Test fun wrongWsvProducerFilenameAndNativeFailureNeverPublish() {
        val library = ChartLibrary(root); var nativeCalls = 0
        rejected { import(library, mapOf("1W7UH010.000" to S57TestBytes.cell(agency = 550))) { _, _ -> nativeCalls++ } }
        rejected { import(library, mapOf("1W7UH020.000" to S57TestBytes.cell())) { _, _ -> nativeCalls++ } }
        assertEquals(0, nativeCalls)
        rejected { import(library, mapOf("1W7UH010.000" to S57TestBytes.cell())) { _, _ -> error("Native source/portrayal fit refused") } }
        assertTrue(library.snapshot().packages.isEmpty()); assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
    }
    @Test fun oldNoaaManifestWithoutProviderRetainsTheSameSourcePin() {
        val library = ChartLibrary(root); val stored = import(library, boston())
        val before = ChartMetadataContract.sourceKey(stored.cells.single())
        val manifest = File(root, "packages/${stored.id}/package.json")
        manifest.writeText(manifest.readText().replace(",\"provider\":\"noaa-s57\"", ""))
        val reopened = ChartLibrary(root).snapshot().packages.single().cells.single()
        assertEquals(S57Profiles.NOAA, reopened.provider)
        assertEquals(before, ChartMetadataContract.sourceKey(reopened))
    }
}
