package com.no5.helm

import com.no5.helm.chart.*
import com.no5.helm.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import android.net.Uri
import kotlinx.coroutines.flow.first
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PersistentChartControllerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var scope: CoroutineScope
    private val assets = File("../chart-engine/src/main/assets/charts/noaa")
    @Before fun setup() {
        File(context.filesDir, "chart-library-v1").deleteRecursively(); File(context.filesDir, "chart-data").deleteRecursively()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    @After fun cleanup() { scope.cancel() }
    private fun legacy(): File = File(context.filesDir, "chart-data/charts/noaa").apply {
        mkdirs(); assets.listFiles()!!.forEach { it.copyTo(File(this, it.name), overwrite = true) }
    }
    @Test fun freshInstallationHasEmptyImportableStateAndNoImplicitDemo() = runBlocking {
        val engine = RecordingEngine(); val controller = PersistentChartController(context, engine, scope)
        controller.prepare(); assertTrue(controller.state.value.snapshot.packages.isEmpty()); assertNull(controller.state.value.snapshot.active)
        assertEquals(0, engine.loads); assertFalse(engine.status.value.chartLoaded)
    }
    private suspend fun privateSelection(): ChartSelection {
        val libraryRoot = File(context.filesDir, "chart-library-v1")
        val id = java.util.UUID.randomUUID().toString()
        val directory = File(libraryRoot, "packages/$id").apply { mkdirs() }
        val names = listOf("US5BOSCC", "US5BOSCD", "US5BOSDC", "US5BOSDD")
        // Model an already validated immutable package; native geometry is checked separately.
        val cells = names.joinToString(",") { name ->
            val file = File(directory, "data/cells/$name.senc").apply { parentFile!!.mkdirs(); writeText("stored test cell $name") }
            val provenance = File(directory, "data/provenance/$name.json").apply { parentFile!!.mkdirs(); writeText("stored test provenance $name") }
            """{"name":"$name","edition":1,"update":0,"base":"cells/$name.senc","issueDate":"20261011","files":{"cells/$name.senc":"${ChartLibrary.sha256(file)}","provenance/$name.json":"${ChartLibrary.sha256(provenance)}"}}"""
        }
        File(directory, "package.json").writeText("""{"id":"$id","label":"Pi","importedAt":1,"cells":[$cells],"source":"Pi No5","piCache":{"product":"Test","licenseHost":"No5","exportedUtc":"2026-10-11T12:00:00Z"}}""")
        val selection = ChartSelection(id, names)
        ChartLibrary(libraryRoot).activate(selection)
        return selection
    }
    @Test fun restoredPrivateSelectionUsesPiRouteWithoutNoaaIdentityOrCameraPreset() = runBlocking {
        val selection = privateSelection(); val id = selection.packageId
        val viewport = ChartViewport(latitude = -29.5, longitude = 20.5, metresToPixels = .2)
        val engine = RecordingEngine().apply { status.value = status.value.copy(viewport = viewport) }
        val controller = PersistentChartController(context, engine, scope); controller.prepare()
        val source = engine.lastSource as ChartSource.PiCacheFiles
        assertEquals("No5", source.licenseHost); assertEquals("2026-10-11T12:00:00Z", source.exportedUtc)
        assertEquals(4, source.paths.size); assertTrue(source.paths.all { it.endsWith(".senc") })
        assertEquals("imported-$id", engine.lastId)
        assertEquals(1, engine.privateLoads); assertEquals(0, engine.loads); assertEquals(0, engine.validations)
        assertEquals(viewport, engine.status.value.viewport); assertEquals(selection, controller.state.value.snapshot.active)
    }
    @Test fun restoredPrivateSelectionUsesResidentCatalogAndRetainsOriginOnRestart() = runBlocking {
        val selection = privateSelection()
        val engine = RecordingEngine().apply { residentSupported = true }
        val controller = PersistentChartController(context, engine, scope); controller.prepare()
        assertEquals(selection, controller.state.value.snapshot.active)
        assertEquals(2, controller.state.value.snapshot.selectionVersion)
        val activation = engine.lastResidentActivation!!
        assertEquals("imported-${selection.packageId}", activation.id)
        assertEquals(4, activation.catalog.sources.size)
        assertTrue(activation.catalog.sources.all { it.provider == PI_CACHE_PROVIDER && it.product == "Test" &&
            it.licenseHost == "No5" && it.files.size == 2 })
        assertEquals(0, engine.loads); assertEquals(0, engine.privateLoads)
        val reopened = PersistentChartController(context, RecordingEngine().apply { residentSupported = true }, scope)
        reopened.prepare()
        assertEquals(controller.state.value.snapshot.activeRelease, reopened.state.value.snapshot.activeRelease)
        controller.close(); reopened.close()
    }

    @Test fun exactSavedCameraWinsOverDefaultCatalogOverviewOnColdRestore() = runBlocking {
        val selection = privateSelection()
        val saved = ChartViewport(latitude = -33.92, longitude = 18.44, metresToPixels = .31, rotationRadians = .2)
        val engine = RecordingEngine().apply { residentSupported = true }
        val controller = PersistentChartController(context, engine, scope, { if (it == selection) saved else null })
        controller.prepare()
        assertEquals(saved, engine.lastResidentActivation!!.initialViewport)
        assertEquals(selection, controller.state.value.snapshot.active)
        assertEquals(0, engine.loads)
    }
    @Test fun legacyBostonIsCopiedAndActivatedWithoutDeletingOriginals() = runBlocking {
        val old = legacy(); val hashes = old.listFiles()!!.associate { it.name to ChartLibrary.sha256(it) }
        val engine = RecordingEngine(); val controller = PersistentChartController(context, engine, scope)
        controller.prepare(); val snapshot = controller.state.value.snapshot
        assertEquals(4, snapshot.packages.single().cells.size); assertEquals(4, snapshot.active!!.cells.size)
        assertEquals(1, engine.validations); assertEquals("noaa-boston-harbour", engine.lastId)
        assertEquals(hashes, old.listFiles()!!.associate { it.name to ChartLibrary.sha256(it) })
        assertTrue(controller.state.value.message!!.contains("gesichert"))
    }
    @Test fun activityOrApkRecreationKeepsMigratedSelectionAndDoesNotReimportAssets() = runBlocking {
        val old = legacy(); val first = PersistentChartController(context, RecordingEngine(), scope); first.prepare()
        val before = first.state.value.snapshot
        File(old, "US5BOSCC.000").writeText("old asset location changes on an APK update")
        val engine = RecordingEngine(); val restarted = PersistentChartController(context, engine, scope); restarted.prepare()
        assertEquals(before, restarted.state.value.snapshot); assertEquals(0, engine.validations); assertTrue(engine.status.value.chartLoaded)
    }
    @Test fun startupLoadsActiveSelectionWithoutRequiringOrCreatingSpatialSidecars() = runBlocking {
        legacy()
        val initial = PersistentChartController(context, RecordingEngine(), scope); initial.prepare()
        val saved = initial.state.value.snapshot
        val libraryRoot = File(context.filesDir, "chart-library-v1")
        val packageDirectory = File(libraryRoot, "packages/${saved.packages.single().id}")
        val sidecar = File(packageDirectory, "spatial-index-v1.json")
        assertTrue(sidecar.delete()) // Existing package in the old strict manifest format.
        val manifestBefore = File(packageDirectory, "package.json").readBytes()
        val activeBefore = File(libraryRoot, "active.json").readBytes()
        val engine = RecordingEngine()
        val restarted = PersistentChartController(context, engine, scope); restarted.prepare()
        assertTrue(engine.status.value.chartLoaded); assertEquals(1, engine.loads); assertEquals(0, engine.validations)
        assertEquals(saved.active, restarted.state.value.snapshot.active)
        assertTrue(restarted.state.value.snapshot.packages.single().cells.all { it.selectionExtent == null })
        assertFalse(sidecar.exists())
        assertArrayEquals(manifestBefore, File(packageDirectory, "package.json").readBytes())
        assertArrayEquals(activeBefore, File(libraryRoot, "active.json").readBytes())
    }
    @Test fun brokenLegacyChainReportsFailureAndPreservesSourceFiles() = runBlocking {
        val old = legacy(); File(old, "US5BOSCC.002").delete()
        val controller = PersistentChartController(context, RecordingEngine(), scope); controller.prepare()
        assertTrue(controller.state.value.snapshot.packages.isEmpty()); assertTrue(controller.state.value.message!!.contains("Migration fehlgeschlagen"))
        assertTrue(File(old, "US5BOSCC.000").isFile); assertFalse(ChartLibrary(File(context.filesDir, "chart-library-v1")).migrated())
    }
    @Test fun failedActivationRestoresPreviousSelectionAndViewport() = runBlocking {
        legacy(); val engine = RecordingEngine(); val controller = PersistentChartController(context, engine, scope); controller.prepare()
        val before = controller.state.value.snapshot.active!!; val view = engine.status.value.viewport
        engine.failNext = true
        controller.activate(before.copy(cells = before.cells.take(1)))
        withTimeout(5000) { while (controller.state.value.busy) delay(10) }
        assertEquals(before, controller.state.value.snapshot.active); assertEquals(view, engine.status.value.viewport)
        assertTrue(engine.status.value.chartLoaded); assertTrue(controller.state.value.message!!.contains("fehlgeschlagen"))
    }
    @Test fun benchmarkPreparationAlsoBlocksChartActivation() = runBlocking {
        legacy(); val engine = RecordingEngine(); val controller = PersistentChartController(context, engine, scope); controller.prepare()
        val before = controller.state.value.snapshot.active!!
        val loads = engine.loads
        engine.status.value = engine.status.value.copy(rendererBenchmark = RendererBenchmarkStatus(active = true, phase = "preparing"))
        controller.activate(before.copy(cells = before.cells.take(1)))
        assertFalse(controller.state.value.busy); assertEquals(loads, engine.loads)
        assertEquals(before, controller.state.value.snapshot.active)
    }
    private fun importArchive(): Uri {
        val archive = File(context.cacheDir, "test-import.zip")
        ZipOutputStream(archive.outputStream()).use { out ->
            val file = File(assets, "US5BOSCC.000")
            out.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(out) }; out.closeEntry()
        }
        return Uri.fromFile(archive)
    }
    private fun piImportArchive(): Uri {
        fun record(type: Int, body: ByteArray) = java.nio.ByteBuffer.allocate(body.size + 6)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(type.toShort()).putInt(body.size + 6).put(body).array()
        fun short(value: Int) = java.nio.ByteBuffer.allocate(2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putShort(value.toShort()).array()
        val corners = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        listOf(-30.0, 20.0, -29.0, 20.0, -29.0, 21.0, -30.0, 21.0).forEach(corners::putDouble)
        val senc = record(1, short(201)) + record(2, "ZA-TEST\u0000".toByteArray()) + record(4, short(1)) +
            record(6, short(0)) + record(100, corners.array()) + record(64, byteArrayOf(42, 0, 1, 0, 0))
        val provenance = """{"helperStatus":{"decrypt_status":1,"expire_status":1}}""".toByteArray()
        fun digest(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val manifest = """{"format":"no5-pi-map-cache-v1","product":"South Africa","licenseHost":"No5","exportedUtc":"2026-10-11T12:00:00Z","authorization":"Test export provenance","cells":[{"name":"ZA-TEST","base":"cells/ZA-TEST.senc","edition":1,"update":0,"issueDate":"20261011","generation":"${"a".repeat(64)}","featureCount":1,"extent":{"south":-30.0,"north":-29.0,"west":20.0,"east":21.0},"files":{"cells/ZA-TEST.senc":"${digest(senc)}","provenance/ZA-TEST.json":"${digest(provenance)}"}}]}""".toByteArray()
        val target = File(context.cacheDir, "test-pi-import.zip")
        ZipOutputStream(target.outputStream()).use { zip ->
            mapOf("no5-pi-map-cache.json" to manifest, "cells/ZA-TEST.senc" to senc,
                "provenance/ZA-TEST.json" to provenance).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return Uri.fromFile(target)
    }
    @Test fun piImportUsesPrivateNativeMetadataInsteadOfIso8211Identification() = runBlocking {
        val engine = RecordingEngine()
        val controller = PersistentChartController(context, engine, scope); controller.prepare()
        controller.importPackage(piImportArchive())
        withTimeout(5000) { controller.state.first { !it.busy } }
        val stored = controller.state.value.snapshot.packages.single()
        assertEquals(1, engine.privateMetadataValidations); assertEquals(0, engine.validations)
        assertEquals(PI_CACHE_PROVIDER, stored.cells.single().catalogMetadata!!.provider)
        val provenance = requireNotNull(stored.piCache)
        assertEquals("South Africa", provenance.product); assertEquals("No5", provenance.licenseHost)
        assertNull(controller.state.value.snapshot.active); assertEquals(0, engine.loads)
        assertEquals(ImportActivity.COMPLETED, controller.state.value.importProgress!!.activity)
        assertEquals(stored, PersistentChartController(context, RecordingEngine(), scope).apply { prepare() }
            .state.value.snapshot.packages.single())
    }
    @Test fun failedPrivateNativeMetadataKeepsExistingActiveSelectionAndPublishesNothing() = runBlocking {
        legacy()
        val engine = RecordingEngine().apply { privateValidationResult = EngineResult(false, "private geometry refused") }
        val controller = PersistentChartController(context, engine, scope); controller.prepare()
        val before = controller.state.value.snapshot
        controller.importPackage(piImportArchive())
        withTimeout(5000) { controller.state.first { !it.busy } }
        assertEquals(1, engine.privateMetadataValidations)
        assertEquals(before, controller.state.value.snapshot)
        assertEquals(ImportActivity.FAILED, controller.state.value.importProgress!!.activity)
        assertTrue(controller.state.value.message!!.contains("private geometry refused"))
    }
    @Test fun importReportsPerCellIndexingAndSuccessfulStoredState() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val engine = RecordingEngine().apply {
            expectedValidationFiles = 1
            validateBlock = { entered.complete(Unit); release.await(); EngineResult(true) }
        }
        val controller = PersistentChartController(context, engine, scope); controller.prepare()
        controller.importPackage(importArchive()); withTimeout(5000) { entered.await() }
        val indexing = withTimeout(5000) { controller.state.first { it.importProgress?.currentFile == "US5BOSCC.000" && it.importProgress?.phase == ImportPhase.INDEXING } }
        assertTrue(indexing.busy); assertEquals(1, indexing.importProgress!!.totalFiles); assertNull(indexing.importProgress!!.processedBytes)
        release.complete(Unit)
        val done = withTimeout(5000) { controller.state.first { !it.busy } }
        assertEquals(ImportActivity.COMPLETED, done.importProgress!!.activity)
        assertEquals(1, done.snapshot.packages.size); assertNull(done.snapshot.active); assertEquals(0, engine.loads)
    }
    @Test fun cancelDuringUninterruptibleNativeFileStaysBusyUntilWorkerReturns() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val engine = RecordingEngine().apply {
            expectedValidationFiles = 1
            validateBlock = { entered.complete(Unit); release.await(); EngineResult(true) }
        }
        val controller = PersistentChartController(context, engine, scope); controller.prepare()
        controller.importPackage(importArchive())
        withTimeout(5000) { entered.await() }
        try {
            controller.cancelImport()
            assertTrue(controller.state.value.busy)
            assertEquals(ImportActivity.CANCELLING, controller.state.value.importProgress!!.activity)
            assertFalse(controller.state.value.importProgress!!.canCancel)
        } finally { release.countDown() }
        val done = withTimeout(5000) { controller.state.first { !it.busy } }
        assertEquals(ImportActivity.CANCELLED, done.importProgress!!.activity); assertTrue(done.message!!.contains("abgebrochen"))
        assertTrue(done.snapshot.packages.isEmpty())
        assertTrue(File(context.filesDir, "chart-library-v1/staging").listFiles().orEmpty().isEmpty())
    }
    @Test fun everyImportedCellUsesSeparateNativeValidationAndCacheDirectory() = runBlocking {
        val archive = File(context.cacheDir, "four-cell-import.zip")
        ZipOutputStream(archive.outputStream()).use { out ->
            assets.listFiles()!!.filter { it.extension == "000" }.forEach { file ->
                out.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(out) }; out.closeEntry()
            }
        }
        val engine = RecordingEngine().apply { expectedValidationFiles = 1 }
        val controller = PersistentChartController(context, engine, scope); controller.prepare()
        controller.importPackage(Uri.fromFile(archive))
        val done = withTimeout(5000) { controller.state.first { !it.busy } }
        assertEquals(ImportActivity.COMPLETED, done.importProgress!!.activity)
        assertEquals(4, engine.validations); assertEquals(4, engine.validationCaches.distinct().size)
        assertEquals(4, done.snapshot.packages.single().cells.size)
        assertEquals(0, engine.loads)
    }
    @Test fun immediateCancelCannotLeaveControllerBusyBeforeCoroutineStarts() = runBlocking {
        val controller = PersistentChartController(context, RecordingEngine(), scope); controller.prepare()
        controller.importPackage(importArchive()); controller.cancelImport()
        val done = withTimeout(5000) { controller.state.first { !it.busy } }
        assertEquals(ImportActivity.CANCELLED, done.importProgress!!.activity)
    }
    @Test fun nativeImportFailureKeepsFailureContextVisible() = runBlocking {
        val engine = RecordingEngine().apply { expectedValidationFiles = 1; validateBlock = { EngineResult(false, "bad geometry") } }
        val controller = PersistentChartController(context, engine, scope); controller.prepare()
        controller.importPackage(importArchive())
        val done = withTimeout(5000) { controller.state.first { !it.busy } }
        assertEquals(ImportActivity.FAILED, done.importProgress!!.activity)
        assertEquals(ImportPhase.INDEXING, done.importProgress!!.phase); assertEquals("US5BOSCC.000", done.importProgress!!.currentFile)
        assertTrue(done.importProgress!!.error!!.contains("bad geometry")); assertTrue(done.snapshot.packages.isEmpty())
    }
    @Test fun unreadableDocumentReportsOpeningFailureAndAllowsRetry() = runBlocking {
        val controller = PersistentChartController(context, RecordingEngine(), scope); controller.prepare()
        controller.importPackage(Uri.fromFile(File(context.cacheDir, "absent-import.zip")))
        val done = withTimeout(5000) { controller.state.first { !it.busy } }
        assertEquals(ImportPhase.OPENING, done.importProgress!!.phase); assertEquals(ImportActivity.FAILED, done.importProgress!!.activity)
        assertFalse(done.busy); assertNotNull(done.importProgress!!.error)
    }
    @Test fun importedNativeMetadataReachesSidecarWithoutLoadingOrActivatingThePackage() = runBlocking {
        val engine = RecordingEngine().apply { expectedValidationFiles = 1; emitMetadata = true }
        val controller = PersistentChartController(context, engine, scope); controller.prepare()
        controller.importPackage(importArchive())
        val done = withTimeout(5000) { controller.state.first { !it.busy } }
        assertEquals(ImportActivity.COMPLETED, done.importProgress!!.activity)
        assertEquals(12000, done.snapshot.packages.single().cells.single().catalogMetadata!!.nativeScale)
        val reopened = ChartLibrary(File(context.filesDir, "chart-library-v1")).snapshot()
        assertNotNull(reopened.packages.single().cells.single().catalogMetadata)
        assertNull(reopened.active); assertEquals(0, engine.loads); assertEquals(1, engine.validations)
    }
    private suspend fun idle(controller: PersistentChartController) = withTimeout(5000) { controller.state.first { !it.busy } }
    private fun archive(files: List<File>): Uri {
        val target = File(context.cacheDir, "lifecycle-update-${System.nanoTime()}.zip")
        ZipOutputStream(target.outputStream()).use { zip -> files.forEach { file ->
            zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
        } }
        return Uri.fromFile(target)
    }
    private fun storedFiles() = File(context.filesDir, "chart-library-v1").walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(File(context.filesDir, "chart-library-v1")).invariantSeparatorsPath to ChartLibrary.sha256(it)
    }
    @Test fun deletingLastActivePackagePersistsEmptyAndKeepsLegacyOriginals() = runBlocking {
        val original = legacy(); val originalHashes = original.listFiles()!!.associate { it.name to ChartLibrary.sha256(it) }
        val engine = RecordingEngine(); val controller = PersistentChartController(context, engine, scope); controller.prepare()
        controller.deletePackage(controller.state.value.snapshot.packages.single().id)
        val done = idle(controller)
        assertNull(done.snapshot.active); assertTrue(done.snapshot.packages.isEmpty()); assertFalse(engine.status.value.chartLoaded)
        assertEquals(1, engine.clears); assertEquals(originalHashes, original.listFiles()!!.associate { it.name to ChartLibrary.sha256(it) })
        controller.close()
        val restartedEngine = RecordingEngine(); val restarted = PersistentChartController(context, restartedEngine, scope); restarted.prepare()
        assertNull(restarted.state.value.snapshot.active); assertEquals(0, restartedEngine.loads)
        assertTrue(restarted.state.value.snapshot.packages.isEmpty())
    }
    @Test fun deactivateLastResidentCellKeepsFilesAndPreservesCamera() = runBlocking {
        legacy(); val engine = RecordingEngine().apply { residentSupported = true }
        val controller = PersistentChartController(context, engine, scope); controller.prepare()
        val before = controller.state.value.snapshot.active!!; val files = storedFiles()
        val view = ChartViewport(42.0, -71.0, .4, .3); engine.setViewport(view)
        controller.deactivate(before)
        val done = idle(controller)
        assertNull(done.snapshot.active); assertFalse(engine.status.value.chartLoaded); assertEquals(view, engine.status.value.viewport)
        assertNotNull(engine.cameraGuard)
        assertEquals(0, engine.guardInstallations)
        val emptyDecision = engine.cameraGuard!!.evaluate(ChartResidentCameraProposal(view, view,
            expectedCatalogGeneration = engine.lastResidentGeneration!!,
            expectedSourceGeneration = engine.status.value.chartSourceGeneration,
            expectedSurfaceGeneration = engine.status.value.surfaceGeneration), ChartResidentBudget())
        assertEquals(view, emptyDecision.acceptedCamera)
        assertEquals(0, emptyDecision.requiredUpperBound)
        val after = storedFiles().filterKeys { it != "lifecycle-state-v1.json" }
        assertEquals(files, after); assertEquals(4, done.snapshot.packages.single().cells.size)
        assertNull(controller.captureResidentBinding())
    }
    @Test fun refusingClearKeepsActiveFilesAndDurableSelection() = runBlocking {
        legacy(); val engine = RecordingEngine(); val controller = PersistentChartController(context, engine, scope); controller.prepare()
        val before = controller.state.value.snapshot; val files = storedFiles()
        engine.clearAllowed = false
        controller.deletePackage(before.packages.single().id)
        val done = idle(controller)
        assertEquals(before.active, done.snapshot.active); assertEquals(files, storedFiles()); assertTrue(engine.status.value.chartLoaded)
        assertTrue(done.message!!.contains("nicht abgeschlossen"))
    }
    @Test fun retiredReleaseAckIsRequiredBeforeInactivePackageFilesAreRemoved() = runBlocking {
        legacy(); val engine = RecordingEngine().apply { residentSupported = true }
        val controller = PersistentChartController(context, engine, scope); controller.prepare()
        val active = controller.state.value.snapshot.active!!
        engine.expectedValidationFiles = 1
        controller.importPackage(archive((0..4).map { File(assets, "US5BOSCC.%03d".format(it)) }))
        val imported = idle(controller).snapshot.packages.first { it.id != active.packageId }
        val directory = File(context.filesDir, "chart-library-v1/packages/${imported.id}")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<EngineResult>()
        engine.releaseBlock = { entered.complete(Unit); release.await() }
        controller.deletePackage(imported.id)
        withTimeout(5000) { entered.await() }
        assertTrue(controller.state.value.busy); assertTrue(directory.isDirectory)
        release.complete(EngineResult(false, "still pinned"))
        val deferred = idle(controller)
        assertTrue(directory.isDirectory); assertTrue(deferred.snapshot.pendingCleanup)
        assertEquals(active, deferred.snapshot.active); assertTrue(deferred.message!!.contains("wartet"))
        controller.close()
        val restarted = PersistentChartController(context, RecordingEngine().apply { residentSupported = true }, scope); restarted.prepare()
        assertFalse(directory.exists()); assertFalse(restarted.state.value.snapshot.pendingCleanup)
        assertEquals(active, restarted.state.value.snapshot.active)
    }
    @Test fun measurementRecoveryBlocksStartupRestoreAndAllManagementActions() = runBlocking {
        val original = legacy(); val engine = RecordingEngine().apply {
            status.value = status.value.copy(rendererBenchmarkRecoveryPending = true)
        }
        val controller = PersistentChartController(context, engine, scope); controller.prepare()
        assertEquals(0, engine.loads); assertEquals(0, engine.validations); assertTrue(original.isDirectory)
        controller.deletePackage("a".repeat(36)); controller.updatePackage("a".repeat(36), importArchive())
        controller.deactivate(ChartSelection("a".repeat(36), listOf("US5BOSCC")))
        assertFalse(controller.state.value.busy); assertEquals(0, engine.clears)
        assertTrue(controller.state.value.message!!.contains("Messwiederherstellung"))
    }
    @Test fun wholeResidentCaptureContainsAllSourcePinsAndRestoreNeverPublishesAnotherSelection() = runBlocking {
        legacy(); val engine = RecordingEngine().apply { residentSupported = true }
        val controller = PersistentChartController(context, engine, scope); controller.prepare()
        val captured = controller.captureResidentBinding()!!
        assertEquals(4, captured.planning.cells.size); assertEquals(4, captured.release.cells.size)
        val before = storedFiles(); val view = engine.status.value.viewport
        controller.restoreResidentBinding(captured, view)
        assertEquals(before, storedFiles()); assertEquals(captured.release, controller.state.value.snapshot.activeRelease)
        assertEquals(1, engine.residentActivations)
    }
    @Test fun invalidUpdateRetainsOriginalActivePackageAndBytes() = runBlocking {
        legacy(); val engine = RecordingEngine().apply { residentSupported = true }
        val controller = PersistentChartController(context, engine, scope); controller.prepare()
        val before = controller.state.value.snapshot; val files = storedFiles()
        engine.expectedValidationFiles = 1; engine.validateBlock = { EngineResult(false, "replacement invalid") }
        controller.updatePackage(before.packages.single().id, archive((0..4).map { File(assets, "US5BOSCC.%03d".format(it)) }))
        val done = idle(controller)
        assertEquals(before.active, done.snapshot.active); assertEquals(files, storedFiles())
        assertEquals(ImportActivity.FAILED, done.importProgress!!.activity)
        assertTrue(engine.status.value.residentCatalogActive)
    }
    @Test fun pickerCallbackRechecksBenchmarkGuardAfterThePickerWasOpened() = runBlocking {
        legacy(); val engine = RecordingEngine().apply { residentSupported = true }
        val controller = PersistentChartController(context, engine, scope); controller.prepare()
        val target = controller.state.value.snapshot.packages.single().id
        val chosen = archive((0..4).map { File(assets, "US5BOSCC.%03d".format(it)) })
        val before = storedFiles(); val validations = engine.validations
        engine.status.value = engine.status.value.copy(rendererBenchmark = RendererBenchmarkStatus(active = true, phase = "measuring"))
        controller.updatePackage(target, chosen)
        assertFalse(controller.state.value.busy); assertEquals(before, storedFiles()); assertEquals(validations, engine.validations)
    }
    @Test fun sourceSwitchPublicationFailureRestoresPreviousMapAndCameraWithoutDeletingFiles() = runBlocking {
        legacy(); val engine = RecordingEngine(); val controller = PersistentChartController(context, engine, scope); controller.prepare()
        val active = controller.state.value.snapshot.active!!; val before = storedFiles()
        val view = ChartViewport(42.0, -71.0, .4, .3); engine.setViewport(view)
        engine.afterClear = { File(context.filesDir, "chart-library-v1/lifecycle-state-v1.json.tmp").mkdir() }
        controller.deletePackage(active.packageId)
        val done = idle(controller)
        assertEquals(active, done.snapshot.active); assertEquals(view, engine.status.value.viewport)
        assertTrue(engine.status.value.chartLoaded); assertEquals(before, storedFiles())
        assertTrue(done.message!!.contains("nicht abgeschlossen"))
    }
    @Test fun journalRecoveryReconciliationDoesNotMoveTheRestoredCameraOrActivateSources() = runBlocking {
        legacy(); val engine = RecordingEngine().apply { residentSupported = true }
        val first = PersistentChartController(context, engine, scope); first.prepare(); first.close()
        val before = storedFiles(); val activations = engine.residentActivations
        val guardInstalls = engine.guardInstallations
        engine.setViewport(ChartViewport(42.123, -70.9, .05, .8))
        val restoredView = engine.status.value.viewport
        engine.status.value = engine.status.value.copy(rendererBenchmarkRecoveryPending = true)
        val afterActivityRecreation = PersistentChartController(context, engine, scope); afterActivityRecreation.prepare()
        engine.status.value = engine.status.value.copy(rendererBenchmarkRecoveryPending = false)
        assertTrue(afterActivityRecreation.reconcileAfterBenchmarkRecovery())
        assertEquals(activations, engine.residentActivations); assertEquals(restoredView, engine.status.value.viewport)
        assertEquals(guardInstalls + 1, engine.guardInstallations) // Rebind runtime callback; preserve camera.
        assertEquals(before, storedFiles()); assertEquals(4, afterActivityRecreation.captureResidentBinding()!!.release.cells.size)
    }
    @Test fun partialZipUpdateTransfersWholeSelectionAndPreservesFingerCamera() = runBlocking {
        val library = ChartLibrary(File(context.filesDir, "chart-library-v1"))
        val targetUri = archive((0..2).map { File(assets, "US5BOSCC.%03d".format(it)) } + File(assets, "US5BOSDD.000"))
        val old = library.importZip("selected package", context.contentResolver.openInputStream(targetUri)!!, { _, _ -> })
        library.activateResident(library.residentSelection(ChartSelection(old.id, listOf("US5BOSCC", "US5BOSDD"))))
        val engine = RecordingEngine().apply { residentSupported = true; expectedValidationFiles = 1 }
        val controller = PersistentChartController(context, engine, scope); controller.prepare()
        val view = ChartViewport(42.321, -71.222, .04, -.3); engine.setViewport(view)
        controller.updatePackage(old.id, archive(listOf(File(assets, "US5BOSCC.003"), File(assets, "US5BOSCC.004"))))
        val done = idle(controller)
        assertEquals(listOf("US5BOSCC", "US5BOSDD"), done.snapshot.active!!.cells)
        assertNotEquals(old.id, done.snapshot.active!!.packageId)
        assertEquals(view, engine.status.value.viewport); assertFalse(File(context.filesDir, "chart-library-v1/packages/${old.id}").exists())
        assertEquals(4, done.snapshot.packages.single().cells.single { it.name == "US5BOSCC" }.update)
        assertEquals(2, done.snapshot.packages.single().cells.size)
        assertNotNull(engine.cameraGuard)
        val generation = controller.captureResidentBinding()!!.planning.generation
        val current = engine.status.value
        val proposal = ChartResidentCameraProposal(current.viewport, current.viewport, expectedCatalogGeneration = generation,
            expectedSourceGeneration = current.chartSourceGeneration, expectedSurfaceGeneration = current.surfaceGeneration)
        assertNotEquals(ChartResidentCameraReason.STALE_AUTHORITY, engine.cameraGuard!!.evaluate(proposal, ChartResidentBudget()).reason)
        assertEquals(ChartResidentCameraReason.STALE_AUTHORITY,
            engine.cameraGuard!!.evaluate(proposal.copy(expectedSourceGeneration = current.chartSourceGeneration - 1), ChartResidentBudget()).reason)
    }
    @Test fun eagerColdRestoreUsesNoSurfaceOwnershipAndTheExactSelectionCamera()=runBlocking {
        val selection=privateSelection()
        val saved=ChartViewport(-33.91,18.42,.31,.4)
        val engine=RecordingEngine()
        val controller=PersistentChartController(context,engine,scope,{if(it==selection)saved else null})
        controller.prepare()
        assertEquals(saved,engine.status.value.viewport)
        assertEquals(listOf(saved),engine.restoreRequests)
        assertTrue(engine.cameraRequests.isEmpty())
        assertEquals(selection,controller.state.value.snapshot.active)
        controller.close()
    }
    @Test fun eagerWarmPreparationWaitsForOwnerRevisionRatherThanNumericAdmission()=runBlocking {
        val selection=privateSelection()
        val saved=ChartViewport(-33.91,18.42,.31,.4)
        val engine=RecordingEngine().apply {
            status.value=status.value.copy(surfaceAttached=true)
            deferCameraOwnership=true
        }
        val controller=PersistentChartController(context,engine,scope,{if(it==selection)saved else null})
        val preparation=async(Dispatchers.Default){controller.prepare()}
        withTimeout(1000){engine.cameraEntered.await()}
        assertFalse("A receipt alone cannot complete source preparation",preparation.isCompleted)
        assertNotEquals(saved,engine.status.value.viewport)
        engine.completeCameraOwnership();preparation.await()
        assertEquals(saved,engine.status.value.viewport)
        assertEquals(selection,controller.state.value.snapshot.active)
        assertTrue(engine.restoreRequests.isEmpty())
        controller.close()
    }
    @Test fun rejectedWarmCameraRestoresPreviousSelectionAndCameraBeforeReportingFailure()=runBlocking {
        legacy();val engine=RecordingEngine()
        val desired=ChartViewport(-33.91,18.42,.31,.4)
        val controller=PersistentChartController(context,engine,scope,{if(it.cells.size==1)desired else null})
        controller.prepare()
        val previous=controller.state.value.snapshot.active!!
        val files=storedFiles();val view=ChartViewport(42.1,-71.2,.4,-.3)
        engine.setViewport(view);engine.status.value=engine.status.value.copy(surfaceAttached=true)
        engine.rejectNextCamera=true
        controller.activate(previous.copy(cells=previous.cells.take(1)))
        val done=idle(controller)
        assertEquals(previous,done.snapshot.active)
        assertEquals(view,engine.status.value.viewport)
        assertEquals(listOf(desired,view),engine.cameraRequests)
        assertTrue(done.message!!.contains("Aktivierung fehlgeschlagen"))
        assertEquals(files,storedFiles())
        controller.close()
    }
    private class RecordingEngine : ChartEngine {
        override val status = MutableStateFlow(ChartEngineStatus(initialized = true))
        var validations = 0; var loads = 0; var lastId = ""; var failNext = false
        var privateLoads = 0; var lastSource: ChartSource? = null
        val restoreRequests=java.util.concurrent.CopyOnWriteArrayList<ChartViewport>()
        val cameraRequests=java.util.concurrent.CopyOnWriteArrayList<ChartViewport>()
        val cameraEntered=CompletableDeferred<Unit>()
        @Volatile var deferCameraOwnership=false
        @Volatile var rejectNextCamera=false
        @Volatile private var cameraId=0L
        @Volatile private var cameraRevision=0L
        @Volatile private var cameraState=0
        @Volatile private var pendingCamera:ChartViewport?=null
        var privateMetadataValidations = 0
        var privateValidationResult = EngineResult(true)
        var expectedValidationFiles = 4
        var emitMetadata = false
        var residentSupported = false
        override val supportsResidentCatalog get() = residentSupported
        var residentActivations = 0; var clears = 0
        var clearAllowed = true
        var afterClear: () -> Unit = {}
        var cameraGuard: ChartResidentCameraGuard? = null
        var guardInstallations = 0
        var lastResidentGeneration: String? = null
        var lastResidentActivation: ChartResidentActivation? = null
        private var residency: ChartResidencyStatus? = null
        var releaseBlock: suspend () -> EngineResult = { EngineResult(true) }
        var validateBlock: suspend () -> EngineResult = { EngineResult(true) }
        val validationCaches = mutableListOf<String>()
        override suspend fun validateChartFiles(paths: List<String>, cacheDirectory: String): EngineResult {
            validations++; validationCaches += cacheDirectory; assertEquals(expectedValidationFiles, paths.size); return validateBlock()
        }
        override suspend fun validateChartFilesWithMetadata(paths: List<String>, cacheDirectory: String): ChartValidationResult {
            check(paths.none { it.endsWith(".senc") }) { "Pi SENC reached public S57 metadata validation" }
            val result = validateChartFiles(paths, cacheDirectory)
            return ChartValidationResult(result, if (result.success && emitMetadata) paths.map { path ->
                ValidatedChartCell(path, 1, "no5-s57-osenc201-metadata-v1", "a".repeat(64), "b".repeat(64), 1,
                    12000, 5, ChartCellBounds(42.3, -71.1, 42.4, -71.0), emptyList(), emptyList(), ChartCoverageCapabilities())
            } else emptyList())
        }
        override suspend fun validatePiCacheFilesWithMetadata(paths: List<String>, cacheDirectory: String): ChartValidationResult {
            privateMetadataValidations++; assertEquals(1, paths.size); assertTrue(paths.single().endsWith(".senc"))
            return ChartValidationResult(privateValidationResult, if (privateValidationResult.success) paths.map { path ->
                ValidatedChartCell(path, 1, "no5-s57-osenc201-metadata-v1", "a".repeat(64), "b".repeat(64), 1,
                    12000, null, ChartCellBounds(-30.0, 20.0, -29.0, 21.0), emptyList(), emptyList(), ChartCoverageCapabilities())
            } else emptyList())
        }
        override suspend fun loadChartSet(id: String, paths: List<String>): EngineResult {
            loads++; lastId = id
            if (failNext) { failNext = false; status.value = status.value.copy(chartLoaded = false); return EngineResult(false, "native refusal") }
            status.value = status.value.copy(chartLoaded = true, viewport = ChartViewport(42.0, -71.0, .1)); return EngineResult(true)
        }
        override suspend fun loadChartSource(id: String, source: ChartSource): EngineResult {
            lastSource = source
            if (source is ChartSource.PiCacheFiles) {
                privateLoads++; lastId = id; status.value = status.value.copy(chartLoaded = true)
                return EngineResult(true)
            }
            return super.loadChartSource(id, source)
        }
        override suspend fun activateResidentCatalog(activation: ChartResidentActivation): ChartResidentUpdate {
            residentActivations++; lastId = activation.id; lastResidentActivation = activation
            status.value = status.value.copy(chartLoaded = activation.catalog.sources.isNotEmpty(), residentCatalogActive = true,
                chartSourceGeneration = status.value.chartSourceGeneration + 1, viewport = activation.initialViewport)
            cameraGuard = activation.cameraPolicy?.bind(status.value.chartSourceGeneration)
            lastResidentGeneration = activation.catalog.generation
            residency = ChartResidencyStatus(true, false, activation.catalog.generation, "", "", 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", emptyList())
            return ChartResidentUpdate(EngineResult(true), residency)
        }
        override suspend fun readResidency() = ChartResidentUpdate(EngineResult(residency != null), residency)
        override fun installResidentCameraGuard(expectedCatalogGeneration: String, guard: ChartResidentCameraGuard?): Boolean {
            if (residency?.catalogGeneration != expectedCatalogGeneration) return false
            guardInstallations++
            cameraGuard = guard; return true
        }
        override suspend fun releaseRetiredChartSources(expectedCatalogGeneration: String): EngineResult {
            assertEquals(residency!!.catalogGeneration, expectedCatalogGeneration)
            return releaseBlock()
        }
        override suspend fun clearChartSelection(expectedSourceGeneration: Long): EngineResult {
            assertEquals(status.value.chartSourceGeneration, expectedSourceGeneration)
            if (!clearAllowed) return EngineResult(false, "still pinned")
            clears++; residency = null
            cameraGuard = null
            status.value = status.value.copy(chartLoaded = false, residentCatalogActive = false,
                chartSourceGeneration = status.value.chartSourceGeneration + 1)
            afterClear()
            return EngineResult(true)
        }
        override suspend fun initialize(config: ChartEngineConfig) = EngineResult(true)
        override fun attachSurface(surface: android.view.Surface, width: Int, height: Int) = Unit
        override fun detachSurface() = Unit
        override fun setViewport(viewport: ChartViewport) { status.value = status.value.copy(viewport = viewport) }
        override suspend fun restoreCameraViewport(viewport: ChartViewport, expectedSourceGeneration: Long): Boolean {
            if(status.value.surfaceAttached||status.value.residentCatalogActive||status.value.chartSourceGeneration!=expectedSourceGeneration)return false
            restoreRequests+=viewport
            status.value=status.value.copy(viewport=viewport)
            return status.value.viewport==viewport
        }
        override fun submitCameraViewport(viewport:ChartViewport,sequence:Long,inputUptimeMs:Long):Long {
            check(status.value.surfaceAttached)
            cameraId++;cameraState=0;pendingCamera=viewport;cameraRequests+=viewport
            if(rejectNextCamera){rejectNextCamera=false;cameraState=5}
            else if(!deferCameraOwnership)completeCameraOwnership()
            cameraEntered.complete(Unit)
            return cameraId
        }
        fun completeCameraOwnership() {
            status.value=status.value.copy(viewport=checkNotNull(pendingCamera))
            cameraRevision++;cameraState=1
        }
        override fun cameraControlResult(id:Long,destination:ChartControlResult):Boolean {
            if(id!=cameraId)return false
            destination.id=id;destination.state=cameraState
            destination.revision=if(cameraState==1)cameraRevision else -1
            return true
        }
        override fun setS52Settings(settings: S52Settings) = Unit
        override suspend fun queryFeature(point: ScreenPoint): List<ChartFeature> = emptyList()
        override fun setOwnship(state: OwnshipProjection) = Unit
        override fun setAisTargets(targets: List<AisProjection>) = Unit
        override fun setRouteProjection(route: RouteProjection?) = Unit
        override fun setWaypointProjection(waypoints: List<WaypointProjection>) = Unit
        override fun close() = Unit
    }
}
