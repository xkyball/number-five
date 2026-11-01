package com.no5.helm.engine

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.RandomAccessFile
import kotlinx.serialization.json.*

class BenchmarkRecoveryStoreTest {
    @Test fun compositeRecoveryPreservesBackgroundRolesAndRejectsForeignPaths() {
        val directory=Files.createTempDirectory("background-recovery").toFile()
        try {
            val file=directory.resolve("state.json")
            val paths=listOf("/private/data/cells/ZA.senc","/private/data/cells/NO5BG_S50_E000.senc")
            val value=saved.copy(paths=paths,backgroundPaths=listOf(paths[1]))
            BenchmarkRecoveryStore(file).save(value)
            assertEquals(value,BenchmarkRecoveryStore(file).read())
            file.writeText(file.readText().replace("\"backgroundPaths\":[\"${paths[1]}\"]",
                "\"backgroundPaths\":[\"/foreign.senc\"]"))
            assertThrows(IllegalStateException::class.java) { BenchmarkRecoveryStore(file).read() }
        } finally { directory.deleteRecursively() }
    }
    private val saved = BenchmarkUserState("9b6a0377-1b5d-4491-8abc-cfa4f9e15c3f", "imported-connecticut",
        listOf("/private/user/US5CT111.000"), ChartViewport(41.1234567890123, -72.3456789012345, .123456789012, .234567890123),
        ChartPresentation("redwater-focus"), S52Settings(ChartPalette.DUSK, 5.0), ChartOverlays(
            GeoProjection(41.1, -72.2, .3), listOf(GeoProjection(41.2, -72.1)), listOf(GeoProjection(41.3, -72.4)),
            RouteProjection(listOf(GeoProjection(41.4, -72.3)))))
    @Test fun filtersAndRichOverlayIdentitySurviveRecovery() {
        val directory = Files.createTempDirectory("rich-recovery").toFile()
        try {
            val value = saved.copy(settings = saved.settings.copy(objectFilters = ChartObjectFilters(S52DisplayCategory.CUSTOM, setOf("BOYLAT"), text = false)),
                overlays = saved.overlays.copy(aisObjects = listOf(ChartOverlayObject("ais-1", GeoProjection(42.0, -71.0), "Target", 1.2)),
                    route = saved.overlays.route!!.copy(id = "route-1", revision = 3, role = ChartRouteRole.DRAFT, pointIds = listOf("p1"), pointNames = listOf("A"))))
            BenchmarkRecoveryStore(directory.resolve("state.json")).save(value)
            assertEquals(value, BenchmarkRecoveryStore(directory.resolve("state.json")).read())
        } finally { directory.deleteRecursively() }
    }
    @Test fun processRestartRetainsOriginalPrivateStateWithFullCameraPrecision() {
        val directory = Files.createTempDirectory("benchmark-recovery").toFile()
        try {
            val file = directory.resolve("recovery.json")
            BenchmarkRecoveryStore(file).save(saved)
            val reopened = BenchmarkRecoveryStore(file)
            assertEquals(saved, reopened.read())
            try { reopened.save(saved.copy(runId = "another")); fail("Must not replace recovery") } catch (_: IllegalStateException) {}
            assertEquals(saved, reopened.read())
            // Failed restoration deliberately leaves the file; only a successful backend clears it.
            assertTrue(file.exists()); reopened.clear(); assertNull(reopened.read())
            reopened.save(saved.copy(paths = emptyList(), chartId = null)); assertTrue(reopened.read()!!.paths.isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun incompleteWriteAndCorruptJournalNeverLookLikeSuccessfulRecovery() {
        val directory = Files.createTempDirectory("benchmark-recovery").toFile()
        try {
            val file = directory.resolve("recovery.json")
            directory.resolve("recovery.json.tmp").writeText("partial")
            assertNull(BenchmarkRecoveryStore(file).read())
            file.writeText("partial")
            try { BenchmarkRecoveryStore(file).read(); fail("Corruption visible") }
            catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("Nutzerkarten bleiben")) }
            assertEquals("partial", file.readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun standardJournalRestoresFloridaOrEmptySelectionWithStyleSafetyAndOverlays() {
        val directory = Files.createTempDirectory("standard-recovery").toFile()
        try {
            val file = directory.resolve("recovery.json")
            val florida = saved.copy(chartId = "florida", paths = listOf("/private/user/US5FL111.000"),
                viewport = saved.viewport.copy(latitude = 25.773123456789, longitude = -80.193987654321))
            for (original in listOf(florida, florida.copy(chartId = null, paths = emptyList()))) {
                BenchmarkRecoveryStore(file).save(original)
                assertEquals(original, BenchmarkRecoveryStore(file).read())
                BenchmarkRecoveryStore(file).clear()
                assertNull(BenchmarkRecoveryStore(file).read())
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun settingsSnapshotUsesTheNativePaletteAfterAStyleChange() {
        for (palette in ChartPalette.entries) {
            val status = buildJsonObject { put("presentation", buildJsonObject { put("mode", palette.name) }) }
            assertEquals(S52Settings(palette, 5.0), benchmarkUserSettings(S52Settings(ChartPalette.DAY, 5.0), status))
        }
        try {
            benchmarkUserSettings(S52Settings(), buildJsonObject { put("presentation", buildJsonObject { put("mode", "unknown") }) })
            fail("Cannot claim a recoverable snapshot with an unknown native palette")
        } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("gesichert")) }
    }

    private fun residentState(): BenchmarkUserState {
        val sources = (0 until 150).map { index ->
            val name = "US5T%04d".format(index)
            val files = (0..1).map { update -> ChartResidentFile("/private/catalog/$name." +
                update.toString().padStart(3, '0'), (if (update == 0) "a" else "b").repeat(64)) }
            val source = ChartResidentSource("noaa-s57", name, "", 1, 1, index, files)
            source.copy(sourceKey = BenchmarkRecoveryStore.residentPinIdentity(source))
        }
        val activation = ChartResidentActivation("whole-noaa", ChartResidentCatalog("c".repeat(64), sources,
            ChartResidentBudget(25)), saved.viewport, ChartNavigationBounds(7, sources.map {
                ChartCellExtent(it.name, 41.0, 42.0, -73.0, -71.0)
            }))
        return saved.copy(chartId = "whole-noaa", paths = emptyList(), residentActivation = activation,
            residentSourceGeneration = 11, residentPolicyKey = "coverage-policy-v1",
            bufferTransformMode = ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE)
    }
    @Test fun currentViewOnlyRecoveryIsExplicitAndCannotBecomeACatalogSwap() {
        val directory=Files.createTempDirectory("current-view-recovery").toFile()
        try {
            val file=directory.resolve("state.json");val original=residentState().copy(currentViewOnly=true)
            BenchmarkRecoveryStore(file).save(original)
            assertEquals(original,BenchmarkRecoveryStore(file).read())
            BenchmarkRecoveryStore(file).clear()
            assertThrows(IllegalStateException::class.java) { BenchmarkRecoveryStore(file).save(saved.copy(currentViewOnly=true)) }
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun privateResidentJournalRetainsHostAndBothPinsAcrossRestartAndRejectsChangedOrigin() {
        val directory = Files.createTempDirectory("pi-resident-recovery").toFile()
        try {
            val senc = directory.resolve("data/cells/ZA-TEST.senc").apply { parentFile.mkdirs(); writeText("private geometry") }
            val provenance = directory.resolve("data/provenance/ZA-TEST.json").apply { parentFile.mkdirs(); writeText("private provenance") }
            fun pin(file: java.io.File) = ChartResidentFile(file.absolutePath,
                java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
            val raw = ChartResidentSource(PI_CACHE_PROVIDER, "ZA-TEST", "", 1, 1200, 0,
                listOf(pin(senc), pin(provenance)), "South Africa", "No5")
            val source = raw.copy(sourceKey = BenchmarkRecoveryStore.residentPinIdentity(raw))
            val public = residentState()
            val activation = public.residentActivation!!.copy(catalog = public.residentActivation.catalog.copy(sources = listOf(source)),
                navigationBounds = ChartNavigationBounds(0, emptyList()))
            val state = public.copy(residentActivation = activation)
            val file = directory.resolve("recovery.json")
            BenchmarkRecoveryStore(file).save(state)
            assertEquals(state, BenchmarkRecoveryStore(file).read())
            verifyResidentSourcePins(activation)
            provenance.appendText("changed")
            assertThrows(IllegalStateException::class.java) { verifyResidentSourcePins(activation) }
            assertEquals(state, BenchmarkRecoveryStore(file).read())
            file.writeText(file.readText().replace("\"licenseHost\":\"No5\"", "\"licenseHost\":\"Other\""))
            assertThrows(IllegalStateException::class.java) { BenchmarkRecoveryStore(file).read() }
            assertTrue(file.exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun outerSnapshotRetainsWholeCatalogAndCameraWithoutWritingThePrivateJournal() {
        val directory = Files.createTempDirectory("outer-snapshot").toFile()
        try {
            val file = directory.resolve("recovery.json")
            val store = BenchmarkRecoveryStore(file)
            for (original in listOf(residentState(), wsvResidentState())) {
                val encoded = store.snapshot(original)
                assertFalse("Read-only export must not publish a recovery journal", file.exists())
                file.writeText(encoded.toString()) // Synthetic host reserve placed back into the recovery slot.
                assertEquals(original, BenchmarkRecoveryStore(file).read())
                assertEquals(original.viewport, BenchmarkRecoveryStore(file).read()!!.viewport)
                assertEquals(original.residentActivation!!.catalog.sources.size,
                    encoded.getValue("residentActivation").jsonObject.getValue("catalog").jsonObject.getValue("sources").jsonArray.size)
                store.clear()
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun outerSnapshotRejectsInvalidPinsAndDoesNotReplaceAnotherOwnersJournal() {
        val directory = Files.createTempDirectory("outer-snapshot-guard").toFile()
        try {
            val file = directory.resolve("recovery.json")
            val store = BenchmarkRecoveryStore(file); val original = residentState()
            store.save(original); val bytes = file.readBytes()
            val activation = original.residentActivation!!
            val invalid = original.copy(residentActivation = activation.copy(catalog = activation.catalog.copy(
                sources = activation.catalog.sources.map { it.copy(provider = "unknown-provider") })))
            try { store.snapshot(invalid); fail("Unknown source pins cannot be exported as recoverable") }
            catch (_: IllegalStateException) { }
            assertArrayEquals(bytes, file.readBytes())
            store.snapshot(wsvResidentState())
            assertArrayEquals("Export cannot replace an existing owner", bytes, file.readBytes())
            assertEquals(original, store.read())
        } finally { directory.deleteRecursively() }
    }

    private val outerVisibleGeometry = ChartSurfaceGeometry(2000, 1000, 2000, 1000)
    private val outerRasterGeometry = ChartRasterGeometry(outerVisibleGeometry, 2500, 1250)
    private val outerVisibleCamera = ChartViewport(52.4, 13.1, .2, .3, 2000, 1000)
    private fun outerFull(raster: ChartBufferRaster? = null) = ChartBufferSnapshot(10, 1, 8,
        ChartFrame(3, "session:surface:8", outerVisibleGeometry, outerVisibleCamera, sourceGeneration = 2, contentGeneration = 4),
        producerCompleted = true, raster = raster)

    @Test fun reserveOuterSnapshotUsesTheRegistered2500ProducerAndRetainsThe2000VisibleCamera() {
        val raster = ChartBufferRaster.capture(outerRasterGeometry, outerVisibleCamera, true)
        val producer = outerRasterGeometry.rasterCamera(outerVisibleCamera)
        assertEquals(producer, benchmarkOuterProducerCamera(outerFull(raster), outerVisibleCamera,
            outerVisibleGeometry, outerRasterGeometry, producer, ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE))
        assertEquals(2000, outerFull(raster).frame.viewport.width)
        assertEquals(2500, producer.width)
        val exported = benchmarkOuterRaster(raster)
        assertEquals(2500, exported.getValue("producerCamera").jsonObject.getValue("width").jsonPrimitive.int)
        assertEquals(2000, exported.getValue("geometry").jsonObject.getValue("visible").jsonObject.getValue("frameWidth").jsonPrimitive.int)
    }

    @Test fun nonReserveOuterSnapshotUsesOnlyTheFilledVisibleRectangle() {
        val mode = ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_PADDED_CONTROL
        val raster = ChartBufferRaster.capture(outerRasterGeometry, outerVisibleCamera, false)
        assertEquals(outerVisibleCamera, benchmarkOuterProducerCamera(outerFull(raster), outerVisibleCamera,
            outerVisibleGeometry, outerRasterGeometry, outerVisibleCamera, mode))
        assertEquals(250.0, raster.filledLeft, 0.0)
        assertEquals(2000, raster.filledWidth)
        assertNull(benchmarkOuterProducerCamera(outerFull(raster), outerVisibleCamera, outerVisibleGeometry,
            outerRasterGeometry, outerRasterGeometry.rasterCamera(outerVisibleCamera), mode))
        assertEquals(outerVisibleCamera, benchmarkOuterProducerCamera(outerFull(), outerVisibleCamera,
            outerVisibleGeometry, null, outerVisibleCamera, ChartBufferTransformMode.OFF))
        assertEquals(outerVisibleCamera, benchmarkOuterProducerCamera(outerFull(), outerVisibleCamera,
            outerVisibleGeometry, outerRasterGeometry, outerVisibleCamera, ChartBufferTransformMode.OFF))
    }

    @Test fun missingWrongAndPartialReserveRastersFailClosed() {
        val mode = ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE
        val producer = outerRasterGeometry.rasterCamera(outerVisibleCamera)
        val raster = ChartBufferRaster.capture(outerRasterGeometry, outerVisibleCamera, true)
        assertNull(benchmarkOuterProducerCamera(outerFull(), outerVisibleCamera, outerVisibleGeometry, outerRasterGeometry, producer, mode))
        assertNull(benchmarkOuterProducerCamera(outerFull(raster), outerVisibleCamera, outerVisibleGeometry, null, producer, mode))
        assertNull(benchmarkOuterProducerCamera(outerFull(raster), outerVisibleCamera, outerVisibleGeometry,
            outerRasterGeometry.copy(bufferWidth = 2499), producer, mode))
        assertNull(benchmarkOuterProducerCamera(outerFull(ChartBufferRaster.capture(outerRasterGeometry, outerVisibleCamera, false)),
            outerVisibleCamera, outerVisibleGeometry, outerRasterGeometry, producer, mode))
        assertNull(benchmarkOuterProducerCamera(outerFull(raster.copy(producerCamera = producer.copy(longitude = 13.2))),
            outerVisibleCamera, outerVisibleGeometry, outerRasterGeometry, producer, mode))
        assertNull(benchmarkOuterProducerCamera(outerFull(raster), outerVisibleCamera, outerVisibleGeometry,
            outerRasterGeometry, outerVisibleCamera, mode))
    }

    @Test fun staleVisibleCameraGeometryAndNonFullProducerRemainBlocked() {
        val mode = ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE
        val raster = ChartBufferRaster.capture(outerRasterGeometry, outerVisibleCamera, true)
        val producer = raster.producerCamera
        assertNull(benchmarkOuterProducerCamera(outerFull(raster), outerVisibleCamera.copy(latitude = 52.5),
            outerVisibleGeometry, outerRasterGeometry, producer, mode))
        assertNull(benchmarkOuterProducerCamera(outerFull(raster), outerVisibleCamera,
            outerVisibleGeometry.copy(surfaceWidth = 1999), outerRasterGeometry, producer, mode))
        assertNull(benchmarkOuterProducerCamera(outerFull(raster).copy(producerCompleted = false), outerVisibleCamera,
            outerVisibleGeometry, outerRasterGeometry, producer, mode))
        assertNull(benchmarkOuterProducerCamera(outerFull(raster).copy(stage = ChartContentStage.BASE_AREAS), outerVisibleCamera,
            outerVisibleGeometry, outerRasterGeometry, producer, mode))
    }

    private fun outerExportFixture(snapshotId: String = saved.runId): JsonObject {
        val directory = Files.createTempDirectory("outer-export-fixture").toFile()
        try {
            val state = BenchmarkRecoveryStore(directory.resolve("unused-journal.json")).snapshot(residentState().copy(runId = snapshotId))
            val activation = state.getValue("residentActivation").jsonObject
            val catalog = activation.getValue("catalog").jsonObject
            return buildJsonObject {
                put("schema", "no5-capacity-outer-state/1"); put("scope", "CURRENT_ENTRY_CATALOG_ONLY")
                put("snapshotId", snapshotId); put("rendererSession", "a80c481e-2c6e-46a3-8db7-589501d2c069")
                put("state", state); put("visibleViewport", state.getValue("viewport")); put("producerViewport", state.getValue("viewport"))
                put("authority", buildJsonObject {
                    put("catalogGeneration", catalog.getValue("generation")); put("budget", catalog.getValue("budget"))
                    put("sourceGeneration", 11); put("contentGeneration", 3); put("cameraRevision", 8); put("requestedCameraRevision", 8)
                    put("viewportAuthority", "REGISTERED_BUFFER_FRAME"); put("viewport", state.getValue("viewport"))
                })
                put("fullProducerBuffer", buildJsonObject {
                    put("stamp", 17); put("producerCompleted", true); put("sourceGeneration", 11)
                    put("contentGeneration", 3); put("cameraRevision", 8)
                })
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun outerSnapshotExportPreservesCompleteUtf8BytesAndReturnsOnlyBoundedOwnerReference() {
        val directory = Files.createTempDirectory("outer-export").toFile()
        try {
            val payload = outerExportFixture().toMutableMap().also { it["note"] = JsonPrimitive("synthetic äußerer Zustand") }
                .let(::JsonObject).toString()
            val exporter = RendererOuterSnapshotExporter(directory)
            val ref = exporter.export(payload, saved.runId, "a".repeat(40))
            val bytes = payload.toByteArray(Charsets.UTF_8)
            assertEquals("files/renderer-outer-snapshots/${saved.runId}.json", ref.relativePath)
            assertArrayEquals(bytes, directory.resolve(ref.relativePath.removePrefix("files/")).readBytes())
            assertEquals(bytes.size.toLong(), ref.byteLength)
            assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, ref.sha256)
            assertEquals(saved.runId, ref.snapshotId); assertEquals(11L, ref.sourceGeneration)
            assertEquals("c".repeat(64), ref.catalogGeneration)
            assertTrue(ref.asJsonString().toByteArray().size < 1024)
            assertFalse(directory.resolve("renderer-benchmark-recovery.json").exists())
            assertFalse(directory.resolve("chart-library-v1").exists())
            assertEquals(ref, exporter.export(payload, saved.runId, "a".repeat(40)))
        } finally { directory.deleteRecursively() }
    }

    @Test fun exportOwnerMismatchDoesNotOverwritePublishedArtifact() {
        val directory = Files.createTempDirectory("outer-export-owner").toFile()
        try {
            val original = outerExportFixture(); val exporter = RendererOuterSnapshotExporter(directory)
            val ref = exporter.export(original.toString(), saved.runId, "a".repeat(40))
            val file = directory.resolve(ref.relativePath.removePrefix("files/")); val bytes = file.readBytes()
            val changed = JsonObject(original.toMutableMap().also { it["rendererSession"] = JsonPrimitive("205515e9-a488-40ac-b8f9-d901148f360c") })
            try { exporter.export(changed.toString(), saved.runId, "a".repeat(40)); fail("Owner collision") }
            catch (_: IllegalStateException) { }
            assertArrayEquals(bytes, file.readBytes())
            try { exporter.export(original.toString(), "another-owner", "a".repeat(40)); fail("Invalid UUID") }
            catch (_: Exception) { }
            try { exporter.export(original.toString(), saved.runId, "unknown"); fail("Unpinned build") }
            catch (_: IllegalStateException) { }
            assertArrayEquals(bytes, file.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun exportFilesystemErrorsAndCorruptExistingSlotFailWithoutLibraryOrJournalMutation() {
        val directory = Files.createTempDirectory("outer-export-fs").toFile()
        try {
            val blocked = directory.resolve(RendererOuterSnapshotExporter.DIRECTORY).also { it.writeText("unrelated blocker") }
            try { RendererOuterSnapshotExporter(directory).export(outerExportFixture().toString(), saved.runId, "a".repeat(40)); fail("Not a directory") }
            catch (_: IllegalStateException) { }
            assertEquals("unrelated blocker", blocked.readText()); assertTrue(blocked.delete()); assertTrue(blocked.mkdir())
            val target = blocked.resolve("${saved.runId}.json").also { it.writeText("corrupt artifact") }
            try { RendererOuterSnapshotExporter(directory).export(outerExportFixture().toString(), saved.runId, "a".repeat(40)); fail("Do not replace corrupt owner") }
            catch (_: Exception) { }
            assertEquals("corrupt artifact", target.readText())
            assertFalse(directory.resolve("renderer-benchmark-recovery.json").exists())
            assertFalse(directory.resolve("chart-library-v1").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun snapshotExportBoundsAndSymlinkGuardFailClosed() {
        val directory = Files.createTempDirectory("outer-export-bound").toFile()
        val other = Files.createTempDirectory("outer-export-other").toFile()
        try {
            val exporter = RendererOuterSnapshotExporter(directory)
            try { exporter.export("x".repeat(RendererOuterSnapshotExporter.MAX_EXPORT_BYTES + 1), saved.runId, "a".repeat(40)); fail("Oversized snapshot") }
            catch (_: IllegalStateException) { }
            Files.createSymbolicLink(directory.resolve(RendererOuterSnapshotExporter.DIRECTORY).toPath(), other.toPath())
            try { exporter.export(outerExportFixture().toString(), saved.runId, "a".repeat(40)); fail("Symlink export directory") }
            catch (_: IllegalStateException) { }
            assertTrue(other.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively(); other.deleteRecursively() }
    }

    @Test fun separateExporterInstancesSerializeTheWholeOwnerPublicationTransaction() {
        val directory = Files.createTempDirectory("outer-export-parallel").toFile()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val original = outerExportFixture()
            val changed = JsonObject(original.toMutableMap().also { it["rendererSession"] = JsonPrimitive("205515e9-a488-40ac-b8f9-d901148f360c") })
            val gate = java.util.concurrent.CountDownLatch(1)
            val futures = listOf(original, changed).map { snapshot -> executor.submit(java.util.concurrent.Callable {
                gate.await()
                try { RendererOuterSnapshotExporter(directory).export(snapshot.toString(), saved.runId, "a".repeat(40)); true }
                catch (_: IllegalStateException) { false }
            }) }
            gate.countDown()
            assertEquals(1, futures.count { it.get(10, java.util.concurrent.TimeUnit.SECONDS) })
            val artifacts = directory.resolve(RendererOuterSnapshotExporter.DIRECTORY).listFiles()!!
            assertEquals(1, artifacts.size)
            assertTrue(artifacts.single().readText() in listOf(original.toString(), changed.toString()))
        } finally { executor.shutdownNow(); directory.deleteRecursively() }
    }

    @Test fun ninthSnapshotSlotIsRefusedWithoutRemovingExistingArtifacts() {
        val directory = Files.createTempDirectory("outer-export-slots").toFile()
        try {
            val exporter = RendererOuterSnapshotExporter(directory)
            repeat(8) {
                val id = java.util.UUID.randomUUID().toString()
                exporter.export(outerExportFixture(id).toString(), id, "a".repeat(40))
            }
            val ninth = java.util.UUID.randomUUID().toString()
            try { exporter.export(outerExportFixture(ninth).toString(), ninth, "a".repeat(40)); fail("Bounded slots") }
            catch (_: IllegalStateException) { }
            assertEquals(8, directory.resolve(RendererOuterSnapshotExporter.DIRECTORY).listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun descriptorIdentityMatchesThePinnedNativeCanonicalVector() {
        // Independent digest of the native provider/cell/edition/update/files JSON contract.
        val source = residentState().residentActivation!!.catalog.sources.first()
        assertEquals("0e9932daab33f5c7beee1984175d538e6affb48043b66cf336d14d6cd1f6bccc",
            BenchmarkRecoveryStore.residentPinIdentity(source))
    }

    @Test fun wholeReleasedCatalogSurvivesRestartIndependentlyOfResidentBudgetAndIsFrozen() {
        val directory = Files.createTempDirectory("resident-recovery").toFile()
        try {
            val file = directory.resolve("recovery.json")
            val original = residentState()
            BenchmarkRecoveryStore(file).save(original)
            val restored = BenchmarkRecoveryStore(file).read()!!
            assertEquals(original, restored)
            assertEquals(150, restored.residentActivation!!.catalog.sources.size)
            assertEquals(25, restored.residentActivation.catalog.budget.cells)
            assertTrue(restored.paths.isEmpty()) // Resident selection is not an empty eager selection.
            assertEquals(ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE, restored.bufferTransformMode)
            try { (restored.residentActivation.catalog.sources as MutableList).clear(); fail("Mutable source snapshot") }
            catch (_: UnsupportedOperationException) { }
            try { (restored.residentActivation.catalog.sources.first().files as MutableList).clear(); fail("Mutable pins") }
            catch (_: UnsupportedOperationException) { }
        } finally { directory.deleteRecursively() }
    }

    @Test fun legacyV1JournalStillReadsWithExplicitlyAbsentResidentState() {
        val directory = Files.createTempDirectory("v1-recovery").toFile()
        try {
            val file = directory.resolve("recovery.json")
            BenchmarkRecoveryStore(file).save(saved)
            val encoded = Json.parseToJsonElement(file.readText()).jsonObject
            file.writeText(JsonObject(encoded.toMutableMap().apply {
                put("schema", JsonPrimitive("no5-benchmark-recovery/1"))
                remove("residentActivation"); remove("residentSourceGeneration"); remove("residentPolicyKey")
                remove("bufferTransformMode")
            }).toString())
            assertEquals(saved, BenchmarkRecoveryStore(file).read())
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidResidentDescriptorsCannotBePublishedAsRecoverableState() {
        val directory = Files.createTempDirectory("invalid-resident-recovery").toFile()
        try {
            val file = directory.resolve("recovery.json")
            val original = residentState()
            val activation = original.residentActivation!!
            val source = activation.catalog.sources.first()
            val badSources = listOf(
                source.copy(sourceKey = "d".repeat(64)),
                source.copy(files = source.files.map { it.copy(sha256 = "unknown") }),
                source.copy(files = source.files.map { it.copy(path = it.path.replace(".000", ".002")) }),
                source.copy(provider = "unknown-provider"),
            )
            for (bad in badSources) {
                val invalid = original.copy(residentActivation = activation.copy(catalog = activation.catalog.copy(
                    sources = listOf(bad) + activation.catalog.sources.drop(1))))
                try { BenchmarkRecoveryStore(file).save(invalid); fail("Invalid pinned source accepted") }
                catch (_: IllegalStateException) { }
                assertFalse(file.exists())
            }
            for (invalid in listOf(original.copy(paths = saved.paths), original.copy(residentSourceGeneration = null),
                original.copy(residentPolicyKey = null), original.copy(residentActivation = null))) {
                try { BenchmarkRecoveryStore(file).save(invalid); fail("Ambiguous restore accepted") }
                catch (_: IllegalStateException) { }
                assertFalse(file.exists())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun unknownSchemaOrOversizedJournalIsRetainedAndNeverStartsRecovery() {
        val directory = Files.createTempDirectory("bounded-recovery").toFile()
        try {
            val file = directory.resolve("recovery.json")
            BenchmarkRecoveryStore(file).save(residentState())
            val encoded = Json.parseToJsonElement(file.readText()).jsonObject
            file.writeText(JsonObject(encoded.toMutableMap().apply {
                put("schema", JsonPrimitive("no5-benchmark-recovery/999"))
            }).toString())
            val unknown = file.readText()
            try { BenchmarkRecoveryStore(file).read(); fail("Unknown schema accepted") }
            catch (_: IllegalStateException) { }
            assertEquals(unknown, file.readText())
            RandomAccessFile(file, "rw").use { it.setLength(BenchmarkRecoveryStore.MAX_JOURNAL_BYTES.toLong() + 1) }
            try { BenchmarkRecoveryStore(file).read(); fail("Oversize state accepted") }
            catch (_: IllegalStateException) { }
            assertEquals(BenchmarkRecoveryStore.MAX_JOURNAL_BYTES.toLong() + 1, file.length())
        } finally { directory.deleteRecursively() }
    }

    private fun wsvResidentState(): BenchmarkUserState {
        val original = residentState()
        val activation = original.residentActivation!!
        val sources = listOf("1W7UH010", "1W9UH011").mapIndexed { index, name ->
            val files = (0..1).map { update -> ChartResidentFile("/private/wsv/$name." +
                update.toString().padStart(3, '0'), (if (update == 0) "a" else "b").repeat(64)) }
            val descriptor = ChartResidentSource("wsv-inland-s57", name, "", 3, 1, index, files)
            descriptor.copy(sourceKey = BenchmarkRecoveryStore.residentPinIdentity(descriptor))
        }
        return original.copy(chartId = "wsv-catalog", residentActivation = activation.copy(id = "wsv-catalog",
            catalog = activation.catalog.copy(generation = "e".repeat(64), sources = sources),
            navigationBounds = ChartNavigationBounds(8, sources.map { ChartCellExtent(it.name, 51.0, 51.1, 6.0, 6.1) })))
    }

    @Test fun explicitWsvProfileRoundtripsWholePinsWithoutChangingNoaaIdentities() {
        val directory = Files.createTempDirectory("wsv-recovery").toFile()
        try {
            val file = directory.resolve("recovery.json")
            val original = wsvResidentState()
            BenchmarkRecoveryStore(file).save(original)
            val recovered = BenchmarkRecoveryStore(file).read()!!
            assertEquals(original, recovered)
            assertEquals(listOf("wsv-inland-s57", "wsv-inland-s57"),
                recovered.residentActivation!!.catalog.sources.map { it.provider })
            assertEquals("0e9932daab33f5c7beee1984175d538e6affb48043b66cf336d14d6cd1f6bccc",
                residentState().residentActivation!!.catalog.sources.first().sourceKey)
            val source = original.residentActivation!!.catalog.sources.first()
            assertNotEquals(source.sourceKey,
                BenchmarkRecoveryStore.residentPinIdentity(source.copy(provider = "noaa-s57")))
        } finally { directory.deleteRecursively() }
    }

    @Test fun unknownOrMisnamedWsvProviderCannotPublishOrReadARecoveryJournal() {
        val directory = Files.createTempDirectory("unsupported-wsv-recovery").toFile()
        try {
            val file = directory.resolve("recovery.json")
            val original = wsvResidentState()
            val activation = original.residentActivation!!
            val source = activation.catalog.sources.first()
            for ((provider, name) in listOf("wsv-s57" to source.name, "other-s57" to source.name,
                "wsv-inland-s57" to "US5T0000", "wsv-inland-s57" to "1W6UH010")) {
                val descriptor = source.copy(provider = provider, name = name,
                    files = source.files.mapIndexed { index, entry -> entry.copy(path =
                        "/private/wsv/$name." + index.toString().padStart(3, '0')) })
                val pinned = descriptor.copy(sourceKey = BenchmarkRecoveryStore.residentPinIdentity(descriptor))
                val invalid = original.copy(residentActivation = activation.copy(
                    catalog = activation.catalog.copy(sources = listOf(pinned))))
                try { BenchmarkRecoveryStore(file).save(invalid); fail("Unreleased profile accepted") }
                catch (_: IllegalStateException) { }
                assertFalse(file.exists())
            }
            BenchmarkRecoveryStore(file).save(original)
            val valid = file.readText()
            val unsupported = valid.replace("wsv-inland-s57", "other-s57")
            file.writeText(unsupported)
            try { BenchmarkRecoveryStore(file).read(); fail("Unknown journal provider accepted") }
            catch (_: IllegalStateException) { }
            assertEquals(unsupported, file.readText())
        } finally { directory.deleteRecursively() }
    }
}
