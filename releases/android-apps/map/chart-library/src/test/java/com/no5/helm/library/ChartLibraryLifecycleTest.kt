package com.no5.helm.library

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ChartLibraryLifecycleTest {
    private lateinit var root: File
    private val assets = File("../chart-engine/src/main/assets/charts/noaa")
    @Before fun setup() { root = Files.createTempDirectory("chart-lifecycle").toFile() }
    @After fun cleanup() { root.deleteRecursively() }
    private fun bytes(name: String) = File(assets, name).readBytes()
    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> files.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
        } }
    }.toByteArray()
    private suspend fun import(library: ChartLibrary, files: Map<String, ByteArray>) =
        library.importZip("NOAA", zip(files).inputStream(), { _, _ -> })
    private fun boston(updates: Int = 4) = (0..updates).associate {
        "ENC_ROOT/US5BOSCC/US5BOSCC.%03d".format(it) to bytes("US5BOSCC.%03d".format(it))
    }
    private fun cancellation(): ByteArray {
        val value = bytes("US5BOSCC.004")
        val pattern = "US5BOSCC.004\u001f5\u001f4\u001f".toByteArray()
        val position = value.indices.first { i -> i + pattern.size < value.size && value.copyOfRange(i, i + pattern.size).contentEquals(pattern) }
        "US5BOSCC.005\u001f0\u001f5\u001f".toByteArray().copyInto(value, position)
        return value
    }
    private fun directory(pack: ChartPackage) = File(root, "packages/${pack.id}")
    private fun filesUnder(directory: File) = directory.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(directory).invariantSeparatorsPath to ChartLibrary.sha256(it)
    }

    @Test fun verifiedWsvCancellationDiscardsPayloadButPinsProducerSpecificReceipt() = runBlocking {
        val library = ChartLibrary(root)
        val original = import(library, mapOf("1W7UH010.000" to S57TestBytes.cell()))
        val selection = ChartSelection(original.id, listOf("1W7UH010"))
        library.activateResident(library.residentSelection(selection))
        val withdrawn = import(library, mapOf("1W7UH010.001" to S57TestBytes.cell("1W7UH010.001", edition = 0, update = 1)))
        assertEquals(S57Profiles.WSV, withdrawn.cells.single().provider)
        val mutation = library.prepareCancellationCleanup(true)!!
        library.publishMutation(mutation)
        assertNull(library.snapshot().active)
        assertTrue(library.finishCleanup(true).complete)
        assertFalse(directory(original).exists()); assertFalse(directory(withdrawn).exists())
        val state = File(root, "lifecycle-state-v1.json").readText()
        assertTrue(state.contains("wsv-inland-s57")); assertTrue(state.contains("1W7UH010"))
        try { import(ChartLibrary(root), mapOf("1W7UH010.000" to S57TestBytes.cell())); fail("Withdrawn edition must stay blocked") }
        catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
        val newer = import(ChartLibrary(root), mapOf("1W7UH010.000" to S57TestBytes.cell(edition = 20)))
        assertEquals(20, newer.cells.single().edition)
    }

    @Test fun cancellationReceiptIsScopedToItsValidatedProducer() {
        val cell = ChartCell("1W7UH010", 19, 1, "1W7UH010.000", "20260714", emptyMap(), provider = S57Profiles.WSV)
        val receipt = ChartCancellationReceipt(cell.name, cell.edition, cell.update, cell.issueDate,
            "a".repeat(36), "b".repeat(64), S57Profiles.WSV)
        assertTrue(receipt.blocks(cell)); assertFalse(receipt.blocks(cell.copy(provider = S57Profiles.NOAA)))
    }

    @Test fun inactivePackageDeletionPreservesOtherPackageAndLegacyOriginals() = runBlocking {
        val library = ChartLibrary(root)
        val first = import(library, boston())
        val second = import(library, mapOf("US5BOSDD.000" to bytes("US5BOSDD.000")))
        val original = File(root.parentFile, "legacy-${root.name}").apply { mkdirs() }
        try {
            File(original, "US5BOSCC.000").writeBytes(bytes("US5BOSCC.000"))
            val before = filesUnder(directory(second)); val legacy = filesUnder(original)
            val plan = library.prepareDeletePackage(first.id, false)
            library.publishMutation(plan)
            assertFalse(library.finishCleanup(false).complete)
            assertTrue(directory(first).isDirectory)
            assertTrue(library.finishCleanup(true).complete)
            assertFalse(directory(first).exists())
            assertEquals(before, filesUnder(directory(second))); assertEquals(legacy, filesUnder(original))
            assertEquals(second.id, ChartLibrary(root).snapshot().packages.single().id)
        } finally { original.deleteRecursively() }
    }
    @Test fun deactivationKeepsBytesAndPersistsExplicitEmptyWithoutLegacyFallback() = runBlocking {
        val library = ChartLibrary(root); val pack = import(library, boston())
        val selection = ChartSelection(pack.id, listOf("US5BOSCC")); library.activate(selection)
        val previousActiveBytes = File(root, "active.json").readBytes(); val before = filesUnder(directory(pack))
        library.publishMutation(library.prepareDeactivate(selection, false))
        val restored = ChartLibrary(root).snapshot()
        assertNull(restored.active); assertEquals(1, restored.selectionVersion)
        assertTrue(ChartLibrary(root).hasPersistedSelection()); assertFalse(restored.pendingCleanup)
        assertEquals(before, filesUnder(directory(pack)))
        assertArrayEquals(previousActiveBytes, File(root, "active.json").readBytes())
        assertTrue(restored.issues.isEmpty())
    }
    @Test fun residentPartialDeactivationRetainsPinnedFullSourceIdentity() = runBlocking {
        val library = ChartLibrary(root)
        val pack = import(library, boston() + mapOf("US5BOSDD.000" to bytes("US5BOSDD.000")))
        val original = library.residentSelection(ChartSelection(pack.id, listOf("US5BOSCC", "US5BOSDD")))
        library.activateResident(original)
        library.publishMutation(library.prepareDeactivate(ChartSelection(pack.id, listOf("US5BOSCC")), true))
        val snapshot = ChartLibrary(root).snapshot()
        assertEquals(listOf("US5BOSDD"), snapshot.active!!.cells)
        assertEquals(original.release.cells.single { it.name == "US5BOSDD" }, snapshot.activeRelease!!.cells.single())
        assertEquals(2, snapshot.selectionVersion); assertEquals(2, snapshot.packages.single().cells.size)
    }
    @Test fun releaseRefusalNeverDeletesFilesAndRestartCompletesTheSameCleanup() = runBlocking {
        val library = ChartLibrary(root); val pack = import(library, boston())
        library.activate(ChartSelection(pack.id, listOf("US5BOSCC")))
        val before = filesUnder(directory(pack))
        library.publishMutation(library.prepareDeletePackage(pack.id, false))
        assertNull(library.snapshot().active); assertTrue(library.snapshot().packages.isEmpty())
        assertFalse(library.finishCleanup(false).complete); assertEquals(before, filesUnder(directory(pack)))
        val restarted = ChartLibrary(root)
        assertTrue(restarted.snapshot().pendingCleanup)
        assertTrue(restarted.finishCleanup(true).complete)
        assertTrue(restarted.finishCleanup(true).complete)
        assertFalse(directory(pack).exists()); assertFalse(restarted.snapshot().pendingCleanup)
    }
    @Test fun publicationFailureKeepsPreviousSourcesAndSelection() = runBlocking {
        val initial = ChartLibrary(root); val pack = import(initial, boston())
        val selection = ChartSelection(pack.id, listOf("US5BOSCC")); initial.activate(selection)
        val before = filesUnder(root)
        val failing = ChartLibrary(root, lifecycleCheckpoint = { if (it == "before-publication") throw IOException("disk failure") })
        val plan = failing.prepareDeletePackage(pack.id, false)
        assertThrows(IOException::class.java) { runBlocking { failing.publishMutation(plan) } }
        assertEquals(before, filesUnder(root)); assertEquals(selection, ChartLibrary(root).snapshot().active)
    }
    @Test fun cleanupFailureAfterPublicationCannotReactivatePreviousSources() = runBlocking {
        val initial = ChartLibrary(root); val pack = import(initial, boston())
        initial.activate(ChartSelection(pack.id, listOf("US5BOSCC")))
        val failing = ChartLibrary(root, lifecycleCheckpoint = { if (it == "before-cleanup") throw IOException("busy filesystem") })
        failing.publishMutation(failing.prepareDeletePackage(pack.id, false))
        assertFalse(failing.finishCleanup(true).complete); assertTrue(directory(pack).exists())
        val restarted = ChartLibrary(root)
        assertNull(restarted.snapshot().active); assertTrue(restarted.snapshot().packages.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { restarted.activate(ChartSelection(pack.id, listOf("US5BOSCC"))) } }
        assertTrue(restarted.finishCleanup(true).complete)
    }
    @Test fun staleMutationCannotPublishAfterAnotherSelectionWasReleased() = runBlocking {
        val library = ChartLibrary(root); val first = import(library, boston())
        val second = import(library, mapOf("US5BOSDD.000" to bytes("US5BOSDD.000")))
        library.activate(ChartSelection(first.id, listOf("US5BOSCC")))
        val stale = library.prepareDeletePackage(first.id, false)
        val next = ChartSelection(second.id, listOf("US5BOSDD")); library.activate(next)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { library.publishMutation(stale) } }
        assertEquals(next, library.snapshot().active); assertTrue(directory(first).exists())
    }
    @Test fun cancellationReceiptSurvivesAllPayloadDeletionAndBlocksOldReimport() = runBlocking {
        val library = ChartLibrary(root); val old = import(library, boston())
        library.activate(ChartSelection(old.id, listOf("US5BOSCC")))
        val withdrawn = import(library, mapOf("US5BOSCC.005" to cancellation()))
        val plan = library.prepareCancellationCleanup(false)!!
        assertTrue(plan.confirmedCancellation); assertNull(plan.selection)
        library.publishMutation(plan)
        assertFalse(library.finishCleanup(false).complete)
        assertTrue(library.finishCleanup(true).complete)
        assertFalse(directory(old).exists()); assertFalse(directory(withdrawn).exists())
        val receiptBytes = File(root, "lifecycle-state-v1.json").readBytes()
        val restarted = ChartLibrary(root)
        assertNull(restarted.snapshot().active); assertTrue(restarted.snapshot().packages.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { import(restarted, boston()) } }
        assertArrayEquals(receiptBytes, File(root, "lifecycle-state-v1.json").readBytes())
    }
    @Test fun higherCompleteEditionAfterCancellationIsPermitted() = runBlocking {
        val library = ChartLibrary(root); import(library, boston())
        import(library, mapOf("US5BOSCC.005" to cancellation()))
        library.publishMutation(library.prepareCancellationCleanup(true)!!); library.finishCleanup(true)
        val next = bytes("US5BOSCC.000")
        val pattern = "US5BOSCC.000\u001f5\u001f0\u001f".toByteArray()
        val position = next.indices.first { i -> i + pattern.size < next.size && next.copyOfRange(i, i + pattern.size).contentEquals(pattern) }
        "US5BOSCC.000\u001f6\u001f0\u001f".toByteArray().copyInto(next, position)
        val replacement = import(library, mapOf("US5BOSCC.000" to next))
        val bound = library.residentSelection(ChartSelection(replacement.id, listOf("US5BOSCC")))
        library.activateResident(bound)
        assertEquals(6, library.snapshot().packages.single().cells.single().edition)
        assertEquals(replacement.id, ChartLibrary(root).snapshot().active!!.packageId)
    }
    @Test fun mixedPackageCancellationPreservesRemainingCellBytesAndSelection() = runBlocking {
        val library = ChartLibrary(root)
        val pack = import(library, boston() + mapOf("US5BOSDD.000" to bytes("US5BOSDD.000")))
        val other = pack.cells.single { it.name == "US5BOSDD" }
        library.activateResident(library.residentSelection(ChartSelection(pack.id, listOf("US5BOSCC", "US5BOSDD"))))
        import(library, mapOf("US5BOSCC.005" to cancellation()))
        library.publishMutation(library.prepareCancellationCleanup(true)!!); library.finishCleanup(true)
        val restored = ChartLibrary(root).snapshot()
        assertEquals(listOf("US5BOSDD"), restored.active!!.cells)
        assertEquals(listOf("US5BOSDD"), restored.packages.single().cells.map { it.name })
        other.files.forEach { (path, hash) -> assertEquals(hash, ChartLibrary.sha256(File(directory(pack), "data/$path"))) }
        assertTrue(pack.cells.single { it.name == "US5BOSCC" }.files.keys.none { File(directory(pack), "data/$it").exists() })
    }
    @Test fun partialUpdatePreservesUnchangedCellsAndAutomaticallyTransfersTheSelection() = runBlocking {
        val library = ChartLibrary(root)
        val old = import(library, boston(2) + mapOf("US5BOSDD.000" to bytes("US5BOSDD.000")))
        val selection = ChartSelection(old.id, listOf("US5BOSCC", "US5BOSDD")); library.activateResident(library.residentSelection(selection))
        val before = filesUnder(directory(old))
        val update = import(library, boston().filterKeys { it.endsWith(".003") || it.endsWith(".004") })
        val merged = library.mergeUpdatePackage(old.id, update.id)
        assertEquals(2, merged.cells.size); assertEquals(4, merged.cells.single { it.name == "US5BOSCC" }.update)
        assertEquals(before, filesUnder(directory(old)))
        val plan = library.prepareUpdatePackage(old.id, merged.id, update.id, true)
        assertEquals(selection.cells, plan.selection!!.cells)
        library.publishMutation(plan); library.finishCleanup(true)
        val restored = ChartLibrary(root).snapshot()
        assertEquals(merged.id, restored.active!!.packageId); assertEquals(selection.cells, restored.active!!.cells)
        assertEquals(merged.id, restored.packages.single().id)
        assertFalse(directory(old).exists()); assertFalse(directory(update).exists())
        val unchanged = old.cells.single { it.name == "US5BOSDD" }
        unchanged.files.forEach { (path, hash) -> assertEquals(hash, ChartLibrary.sha256(File(directory(merged), "data/$path"))) }
    }
    @Test fun unknownLifecycleFormatNeverFallsBackToOldActiveJson() = runBlocking {
        val library = ChartLibrary(root); val pack = import(library, boston())
        library.activate(ChartSelection(pack.id, listOf("US5BOSCC")))
        val file = File(root, "lifecycle-state-v1.json").apply { writeText("{\"version\":99,\"future\":true}") }
        val saved = file.readBytes()
        assertNull(library.snapshot().active); assertTrue(library.snapshot().issues.isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { library.prepareDeletePackage(pack.id, true) } }
        assertArrayEquals(saved, file.readBytes()); assertTrue(directory(pack).exists())
    }
    @Test fun obsoleteLabelUnknownMetadataAndCancelledJobDoNotWithdrawACell() = runBlocking {
        val library = ChartLibrary(root)
        val pack = library.importZip("obsolete label is not provider status", zip(boston()).inputStream(), { _, _ -> })
        assertFalse(pack.cells.single().cancelled); assertNull(library.prepareCancellationCleanup(true))
        val entered = CompletableDeferred<Unit>()
        val task = launch(Dispatchers.Default) { library.importZip("cancel job", zip(boston()).inputStream(), { _, _ -> entered.complete(Unit); awaitCancellation() }) }
        entered.await(); task.cancelAndJoin()
        assertNull(library.prepareCancellationCleanup(true)); assertFalse(File(root, "lifecycle-state-v1.json").exists())
        assertEquals(pack.id, library.snapshot().packages.single().id)
    }
    @Test fun forgedCancelledManifestFlagWithoutEdtnZeroCannotDeletePayload() = runBlocking {
        val library = ChartLibrary(root); val pack = import(library, boston())
        File(directory(pack), "package.json").writeText(Json.encodeToString(pack.copy(cells = pack.cells.map { it.copy(cancelled = true) })))
        val before = filesUnder(root)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { library.prepareCancellationCleanup(true) } }
        assertEquals(before, filesUnder(root)); assertFalse(File(root, "lifecycle-state-v1.json").exists())
    }
    @Test fun residentExplicitEmptyCannotBeDowngradedByLegacyWriter() = runBlocking {
        val library = ChartLibrary(root); val pack = import(library, boston())
        val selection = ChartSelection(pack.id, listOf("US5BOSCC"))
        library.activateResident(library.residentSelection(selection))
        library.publishMutation(library.prepareDeactivate(selection, true))
        val before = File(root, "lifecycle-state-v1.json").readBytes()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { library.activate(selection) } }
        assertArrayEquals(before, File(root, "lifecycle-state-v1.json").readBytes())
        assertNull(ChartLibrary(root).snapshot().active)
    }
}
