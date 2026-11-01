package com.no5.helm.library

import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.Random
import java.util.zip.*

class ImportProgressTest {
    private lateinit var root: File
    private val base = File("../chart-engine/src/main/assets/charts/noaa/US5BOSCC.000")
    @Before fun setup() { root = Files.createTempDirectory("map-progress-test").toFile() }
    @After fun cleanup() { root.deleteRecursively() }
    private fun archive(entries: Map<String, ByteArray> = mapOf(base.name to base.readBytes())) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out -> entries.forEach { (name, content) ->
            out.putNextEntry(ZipEntry(name)); out.write(content); out.closeEntry()
        } }
    }.toByteArray()
    private fun events() = CopyOnWriteArrayList<ImportProgress>()
    private val validate: suspend (List<File>, File, (Int, File?) -> Unit) -> Unit = { paths, _, report ->
        paths.forEachIndexed { i, file -> report(i, file); report(i + 1, null) }
    }
    @Test fun smallArchiveHasRealPhaseTotalsAndCatalogIsNotAKartenzelle() = runBlocking {
        val zip = archive(linkedMapOf("ENC_ROOT/" to byteArrayOf(), "ENC_ROOT/catalog.031" to byteArrayOf(1, 2, 3),
            "ENC_ROOT/${base.name}" to base.readBytes()))
        val states = events()
        val stored = ChartLibrary(root).importZipWithProgress("small", zip.inputStream(), validate, states::add)
        assertEquals(1, stored.cells.size)
        assertEquals(listOf(ImportPhase.READING, ImportPhase.OPENING, ImportPhase.EXTRACTING, ImportPhase.CHECKING,
            ImportPhase.INDEXING, ImportPhase.SAVING), states.map { it.phase }.distinct())
        val reading = states.first { it.phase == ImportPhase.READING }
        assertNull(reading.totalBytes); assertNull(reading.byteFraction)
        val extracted = states.last { it.phase == ImportPhase.EXTRACTING }
        assertEquals(2, extracted.totalFiles); assertEquals(2, extracted.processedFiles)
        assertEquals(base.length() + 3, extracted.processedBytes); assertEquals(extracted.processedBytes, extracted.totalBytes)
        val checked = states.last { it.phase == ImportPhase.CHECKING }
        assertEquals(1, checked.totalFiles)
        assertEquals(ImportActivity.COMPLETED, states.last().activity)
        assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
    }
    @Test fun largeDiskArchiveCountsManyFilesAndExpandedBytesWithoutUiFlooding() = runBlocking {
        val archive = File(root, "large.zip")
        val random = Random(42)
        val block = ByteArray(65536)
        ZipOutputStream(archive.outputStream().buffered()).use { out ->
            out.setLevel(0)
            out.putNextEntry(ZipEntry("ENC_ROOT/catalog.031"))
            repeat(512) { random.nextBytes(block); out.write(block) } // 32 MiB, no user's ZIP needed.
            out.closeEntry()
            repeat(2500) { out.putNextEntry(ZipEntry("notes/$it.txt")); out.write(it % 256); out.closeEntry() }
            out.putNextEntry(ZipEntry(base.name)); base.inputStream().use { it.copyTo(out) }; out.closeEntry()
        }
        val states = events(); val begin = System.nanoTime()
        ChartLibrary(root).importZipWithProgress("large", archive.inputStream(), validate, states::add, archive.length())
        val elapsed = (System.nanoTime() - begin) / 200_000_000L
        val extracted = states.last { it.phase == ImportPhase.EXTRACTING }
        assertEquals(2502, extracted.processedFiles); assertEquals(2502, extracted.totalFiles)
        assertEquals(32L * 1024 * 1024 + 2500 + base.length(), extracted.processedBytes)
        assertEquals(extracted.processedBytes, extracted.totalBytes)
        assertTrue("Byte/file callbacks must be throttled", states.size < elapsed + 30)
    }
    @Test fun longSourceReadReportsBytesBeforeItFinishesAndClosesOnCancel() = runBlocking {
        val zip = archive(); val states = events()
        val entered = CompletableDeferred<Unit>(); val unblock = java.util.concurrent.CountDownLatch(1)
        var closed = false
        val input = object : ByteArrayInputStream(zip) {
            var first = true
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (first) { first = false; return super.read(buffer, offset, minOf(length, 4096)) }
                entered.complete(Unit); unblock.await(); return super.read(buffer, offset, length)
            }
            override fun close() { closed = true; super.close() }
        }
        val library = ChartLibrary(root)
        val task = launch { library.importZipWithProgress("slow source", input, validate, states::add) }
        entered.await()
        withTimeout(5000) { while (states.none { it.processedBytes == 4096L }) delay(10) }
        assertNull(states.last().totalBytes)
        task.cancel(); unblock.countDown(); task.join()
        assertTrue(closed); assertEquals(ImportActivity.CANCELLED, states.last().activity)
        assertTrue(library.snapshot().packages.isEmpty()); assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
    }
    @Test fun longNativeFilePublishesPendingFilenameWhileNoBytesAreMeasurable() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val states = events()
        val task = launch { ChartLibrary(root).importZipWithProgress("native", archive().inputStream(), { paths, _, report ->
            report(0, paths.single()); entered.complete(Unit); release.await(); report(1, null)
        }, states::add) }
        entered.await()
        withTimeout(5000) { while (states.none { it.phase == ImportPhase.INDEXING && it.currentFile == base.name }) delay(10) }
        val native = states.last()
        assertEquals(0, native.processedFiles); assertEquals(1, native.totalFiles)
        assertNull(native.processedBytes); assertNull(native.totalBytes); assertNull(native.byteFraction)
        release.complete(Unit); task.join(); assertEquals(ImportActivity.COMPLETED, states.last().activity)
    }
    @Test fun extractionCancellationCleansStagingAndPreservesActivePackage() = runBlocking {
        val library = ChartLibrary(root)
        val original = library.importZipWithProgress("old", archive().inputStream(), validate)
        val selection = ChartSelection(original.id, listOf("US5BOSCC")); library.activate(selection)
        val states = events()
        val task = launch { library.importZipWithProgress("cancel", archive().inputStream(), validate, {
            states.add(it)
            if (it.activity == ImportActivity.RUNNING && it.phase == ImportPhase.EXTRACTING && it.processedFiles == 0)
                throw CancellationException("cancel extraction")
        }) }
        task.join()
        assertEquals(selection, library.snapshot().active); assertEquals(listOf(original), library.snapshot().packages)
        assertEquals(ImportActivity.CANCELLED, states.last().activity)
        assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
    }
    @Test fun nativeFailureRetainsPhaseAndFilenameAndCleansStaging() = runBlocking {
        val states = events(); val library = ChartLibrary(root)
        try {
            library.importZipWithProgress("bad native", archive().inputStream(), { paths, _, report ->
                report(0, paths.single()); error("broken native geometry")
            }, states::add)
            fail("Expected native failure")
        } catch (expected: IllegalStateException) { assertEquals("broken native geometry", expected.message) }
        assertEquals(ImportActivity.FAILED, states.last().activity); assertEquals(ImportPhase.INDEXING, states.last().phase)
        assertEquals(base.name, states.last().currentFile); assertEquals("broken native geometry", states.last().error)
        assertTrue(library.snapshot().packages.isEmpty()); assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
    }
    @Test fun badZipReportsOpeningFailureAndUnknownTotals() = runBlocking {
        val states = events()
        try { ChartLibrary(root).importZipWithProgress("bad", byteArrayOf(1, 2, 3).inputStream(), validate, states::add); fail() }
        catch (_: ZipException) { }
        assertEquals(ImportPhase.OPENING, states.last().phase); assertEquals(ImportActivity.FAILED, states.last().activity)
        assertNull(states.last().totalFiles); assertNull(states.last().totalBytes)
    }
    @Test fun catalogCrcFailureIsReportedDuringExtractionAndNeverPublished() = runBlocking {
        val zip = archive(linkedMapOf("catalog.031" to byteArrayOf(1, 2, 3), base.name to base.readBytes()))
        // Alter the central-directory CRC, leaving compressed data intact.
        val directory = zip.indices.first { i -> i + 20 < zip.size && zip[i] == 0x50.toByte() && zip[i + 1] == 0x4b.toByte()
            && zip[i + 2] == 0x01.toByte() && zip[i + 3] == 0x02.toByte() }
        zip[directory + 16] = (zip[directory + 16].toInt() xor 1).toByte()
        val states = events(); val library = ChartLibrary(root)
        try { library.importZipWithProgress("bad catalog", zip.inputStream(), validate, states::add); fail() }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("catalog.031")) }
        assertEquals(ImportPhase.EXTRACTING, states.last().phase); assertEquals("catalog.031", states.last().currentFile)
        assertEquals(ImportActivity.FAILED, states.last().activity); assertEquals(0, states.last().processedFiles)
        assertTrue(library.snapshot().packages.isEmpty())
    }
    @Test fun reporterThrottlesOrdinaryChangesButFlushesPendingAndTransitionsImmediately() {
        var now = 0L; val states = mutableListOf<ImportProgress>()
        val reporter = ImportProgressReporter(states::add) { now }
        reporter.phase(ImportProgress(ImportPhase.READING))
        repeat(10000) { reporter.report(reporter.latest.copy(processedBytes = it.toLong())) }
        assertEquals(1, states.size)
        now = 200_000_000; reporter.flushPending()
        assertEquals(9999L, states.last().processedBytes); assertEquals(2, states.size)
        reporter.phase(ImportProgress(ImportPhase.EXTRACTING))
        assertEquals(3, states.size); assertEquals(now, states.last().phaseStartedNanos)
        reporter.report(reporter.latest.copy(activity = ImportActivity.FAILED, error = "error"))
        assertEquals(4, states.size)
    }
    @Test fun s57ReaderCheckpointsAllowCancellationInsideOneLargeFile() {
        var callbacks = 0
        try { S57Identification.read(base) { if (++callbacks == 5) throw CancellationException("cancel S57") }; fail() }
        catch (expected: CancellationException) { assertEquals("cancel S57", expected.message) }
        assertEquals(5, callbacks)
    }
}
