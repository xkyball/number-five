package com.no5.helm.library

import com.no5.helm.chart.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.*

class PiCacheImportTest {
    private lateinit var root: File
    @Before fun before() { root = Files.createTempDirectory("pi-cache-import").toFile() }
    @After fun after() { root.deleteRecursively() }
    private fun record(type: Int, body: ByteArray) = ByteBuffer.allocate(body.size + 6).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(type.toShort()).putInt(body.size + 6).put(body).array()
    private fun short(value: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    private fun fixture(name: String = "ZA-TEST", product: String = "South Africa", host: String = "No5",
        edition: Int = 1, update: Int = 0): Map<String, ByteArray> {
        val extent = ChartSelectionExtent(-30.0, -29.0, 20.0, 21.0)
        val corners = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        listOf(-30.0,20.0,-29.0,20.0,-29.0,21.0,-30.0,21.0).forEach { corners.putDouble(it) }
        val senc = record(1, short(201)) + record(2, "$name\u0000".toByteArray()) + record(4, short(edition)) + record(6, short(update)) + record(100, corners.array()) + record(64, byteArrayOf(42,0,1,0,0))
        val provenance = """{"originalHeader":{},"helperStatus":{"decrypt_status":1,"expire_status":1},"text":{"notice.txt":"Original notice"}}""".toByteArray()
        val files = mapOf("cells/$name.senc" to senc, "provenance/$name.json" to provenance)
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val manifest = PiCacheManifest("no5-pi-map-cache-v1", product, host, "2026-10-11T12:00:00Z",
            listOf(PiCacheCell(name, "cells/$name.senc", edition, update, "20261011", "a".repeat(64), 1, extent, files.mapValues { digest(it.value) })), "Export provenance only")
        return files + mapOf(PiCachePackage.manifestName to Json.encodeToString(manifest).toByteArray())
    }
    private fun zip(files: Map<String, ByteArray>): InputStream = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { output -> files.forEach { (name, body) -> output.putNextEntry(ZipEntry(name)); output.write(body); output.closeEntry() } }
    }.toByteArray().inputStream()
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }
    private fun metadata(path: File) = ValidatedChartCell(path.absolutePath, 1, "no5-s57-osenc201-metadata-v1",
        "b".repeat(64), "c".repeat(64), 1, 12000, 5, ChartCellBounds(-30.0, 20.0, -29.0, 21.0),
        emptyList(), emptyList(), ChartCoverageCapabilities())
    private fun composite(): Map<String, ByteArray> {
        val original = fixture(); val result = original.toMutableMap()
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val cells = mutableListOf<PiCacheCell>()
        for (south in -50..-20 step 10) for (west in 0..30 step 10) {
            val name = "NO5BG_S${-south}_E${west.toString().padStart(3, '0')}"
            val e = ChartSelectionExtent(south.toDouble(), south + 10.0, west.toDouble(), west + 10.0)
            val corners = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            listOf(e.south,e.west,e.north,e.west,e.north,e.east,e.south,e.east).forEach { corners.putDouble(it) }
            val senc = record(1, short(201)) + record(2, "$name\u0000".toByteArray()) + record(4, short(1)) +
                record(6, short(0)) + record(100, corners.array()) + record(64, byteArrayOf(119,0,1,0,0))
            val proof = """{"provider":"$BACKGROUND_CACHE_PROVIDER","sourceSHA256":"e547d749445eaa0964aba76738090ec88f5e63c4585122170f98c67a7ea922dc","license":"Public Domain"}""".toByteArray()
            val files = mapOf("cells/$name.senc" to senc, "provenance/$name.json" to proof)
            result.putAll(files)
            cells += PiCacheCell(name,"cells/$name.senc",1,0,"20261025","d".repeat(64),1,e,files.mapValues { digest(it.value) })
        }
        val manifest = BackgroundCacheManifest("no5-map-cache-v2","South Africa",10,
            ChartSelectionExtent(-50.0,-10.0,0.0,40.0),digest(original.getValue(PiCachePackage.manifestName)),
            BACKGROUND_CACHE_PROVIDER,"https://naciscdn.org/naturalearth/10m/physical/ne_10m_land.zip",
            "e547d749445eaa0964aba76738090ec88f5e63c4585122170f98c67a7ea922dc","5.1.1","Public Domain",
            "https://www.naturalearthdata.com/about/terms-of-use/","Natural Earth",0,cells)
        result[BackgroundCachePackage.manifestName] = Json.encodeToString(manifest).toByteArray()
        return result
    }
    @Test fun compositeRetainsOriginalBytesAndDistinctPublicOriginsWithoutActivation() = runBlocking {
        val library = ChartLibrary(root)
        val original = fixture(); val files = composite()
        val old = library.importZip("old", zip(original), { _, _ -> })
        val active = ChartSelection(old.id, listOf("ZA-TEST")); library.activate(active)
        val filled = library.importZipWithMetadata("filled", zip(files), { paths, _, _ -> paths.map(::metadata) })
        assertEquals(17,filled.cells.size); assertEquals(active,library.snapshot().active)
        assertEquals(16,filled.cells.count { it.provider == BACKGROUND_CACHE_PROVIDER && it.piCache == null })
        for ((name,body) in original) assertArrayEquals(body,File(root,"packages/${filled.id}/data/$name").readBytes())
        val selected = ChartSelection(filled.id,filled.cells.map { it.name })
        val reopened = ChartLibrary(root); val binding = reopened.residentSelection(selected)
        val sources = ChartResidentAdapter.catalog(binding.planning,binding.pack,binding.dataDirectory,ChartResidentBudget(32)).sources
        assertEquals(17,sources.size)
        for (source in sources.filter { it.provider == BACKGROUND_CACHE_PROVIDER }) {
            assertNull(source.product); assertNull(source.licenseHost); assertEquals(2,source.files.size)
            assertEquals("senc",File(source.files.first().path).extension)
        }
        assertEquals(1,sources.count { it.provider == PI_CACHE_PROVIDER && it.licenseHost == "No5" })
        reopened.activateResident(binding)
        assertEquals(selected,ChartLibrary(root).snapshot().active)
        rejected { runBlocking { reopened.mergeUpdatePackage(old.id,filled.id) } }
        assertEquals(selected,reopened.snapshot().active)
    }
    @Test fun compositeRejectsGridHolesAndChangedOriginsBeforeNativeAdmission() {
        val good = composite()
        val text = String(good.getValue(BackgroundCachePackage.manifestName))
        val manifest = Json.decodeFromString<BackgroundCacheManifest>(text)
        val bad = listOf(
            good + (BackgroundCachePackage.manifestName to Json.encodeToString(manifest.copy(cells=manifest.cells.dropLast(1))).toByteArray()),
            good + (BackgroundCachePackage.manifestName to text.replace("Public Domain","o-charts").toByteArray()),
            good + ("provenance/NO5BG_S50_E000.json" to "{\"helperStatus\":{}}".toByteArray()),
            good + (PiCachePackage.manifestName to "changed".toByteArray()))
        for (files in bad) rejected { runBlocking { ChartLibrary(root).importZip("bad",zip(files),{ _, _ -> fail("Native must not run") }) } }
        assertTrue(ChartLibrary(root).snapshot().packages.isEmpty())
    }
    /** Explicit private-fixture run; chart bytes are never embedded in the repository. */
    @Test fun actualFilledExportImportsAndReopensOfflineWithAll72NativeValidatedCells() = runBlocking {
        val archive = System.getenv("NO5_FILLED_CACHE_TEST")
        val native = System.getenv("NO5_FILLED_METADATA_TEST")
        Assume.assumeTrue(archive != null && native != null)
        val metadata = File(native!!).readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
            .associate { it.getValue("name").jsonPrimitive.content to it.getValue("metadata").toString() }
        val library = ChartLibrary(root); var checked = 0
        val start = System.nanoTime()
        val filled = library.importZipWithMetadata("South Africa",File(archive!!).inputStream(), { paths, _, _ ->
            paths.map { file -> checked++; ChartCellMetadataDecoder.decode(file.absolutePath,metadata.getValue(file.nameWithoutExtension),
                if (file.name.startsWith("NO5BG_")) BACKGROUND_CACHE_PROVIDER else PI_CACHE_PROVIDER) }
        })
        val elapsed = (System.nanoTime()-start)/1e6
        assertEquals(72,checked); assertEquals(72,filled.cells.size); assertNull(library.snapshot().active)
        val selection = ChartSelection(filled.id,filled.cells.map { it.name })
        val reopened = ChartLibrary(root); val binding = reopened.residentSelection(selection)
        val sources = ChartResidentAdapter.catalog(binding.planning,binding.pack,binding.dataDirectory,ChartResidentBudget(100)).sources
        assertEquals(72,sources.size); assertEquals(16,sources.count { it.provider == BACKGROUND_CACHE_PROVIDER })
        System.getenv("NO5_FILLED_CATALOG_TEST_OUT")?.let { output ->
            File(output).writeText(sources.joinToString("\n",postfix="\n") { source ->
                (listOf(source.provider,source.name,source.sourceKey,source.edition.toString(),source.update.toString(),source.order.toString(),
                    source.product.orEmpty(),source.licenseHost.orEmpty()) + source.files.flatMap { pin ->
                    listOf(File(pin.path).name,pin.sha256)
                }).joinToString("\t")
            })
        }
        System.getenv("NO5_FILLED_DEMANDS_TEST_OUT")?.let { output ->
            val poses = listOf("cape" to listOf(-33.91,18.43,.04,0.0),"granger" to listOf(-33.9,18.415,.8,0.0),
                "ocean-gap" to listOf(-42.0,20.0,.001,0.0),"land-gap" to listOf(-23.0,23.0,.001,0.0),
                "tile-seam" to listOf(-30.0,20.0,.001,.31),"overview" to listOf(-31.02,20.0,.00023,0.0))
            fun demands(pack: ChartPackage): String {
                val catalog=ChartPlanningCatalog.fromPackage(pack,pack.cells.map { it.name }.toSet())
                val planner=ChartViewportPlanner(catalog); val coverage=ChartCoveragePlanner(catalog)
                return poses.joinToString("\n",postfix="\n") { (name,p) ->
                    val admitted=coverage.admit(planner.plan(ChartPlanCamera(p[0],p[1],p[2],p[3],2000,1070)),3000.0/p[2])
                    println("ACTUAL_DEMAND=${pack.cells.size}:$name:${admitted.plan.required.size}:${admitted.reason}")
                    (listOf(name)+admitted.plan.required.map { it.sourceKey }).joinToString("\t")
                }
            }
            File(output).writeText(demands(filled))
            val original=filled.copy(cells=filled.cells.filter { it.provider != BACKGROUND_CACHE_PROVIDER }.mapIndexed { order,cell ->
                cell.copy(catalogMetadata=cell.catalogMetadata?.copy(order=order))
            })
            File(output+".baseline").writeText(demands(original))
        }
        assertEquals(72,reopened.paths(selection).size)
        reopened.activateResident(binding)
        assertEquals(selection,ChartLibrary(root).snapshot().active)
        println("ACTUAL_FILLED_IMPORT_MS=$elapsed CELLS=${sources.size}")
    }
    @Test fun objectTextResolvesExistingPinnedExportAndRejectsChangedOrForeignSources() = runBlocking {
        val library = ChartLibrary(root)
        val pack = library.importZipWithMetadata("Pi", zip(fixture()), { paths, _, _ -> paths.map(::metadata) })
        val cell = pack.cells.single(); val key = ChartMetadataContract.sourceKey(cell, pack.piCache)
        assertNull(library.readObjectText(pack.id, cell.name, key, "notice.txt").text)
        library.activateResident(library.residentSelection(ChartSelection(pack.id, listOf(cell.name))))
        assertEquals("Original notice", ChartLibrary(root).readObjectText(pack.id, cell.name, key, "notice.txt").text)
        assertNull(library.readObjectText(pack.id, cell.name, "foreign", "notice.txt").text)
        assertNull(library.readObjectText(pack.id, cell.name, key, "../notice.txt").text)
        assertNull(library.readObjectText(pack.id, cell.name, key, "absent.txt").text)
        File(root, "packages/${pack.id}/data/provenance/${cell.name}.json").appendText(" ")
        assertNull(library.readObjectText(pack.id, cell.name, key, "notice.txt").text)
    }
    @Test fun privateMetadataSurvivesRestartAndViewportPlanningWithDistinctOrigin() = runBlocking {
        val library = ChartLibrary(root)
        val stored = library.importZipWithMetadata("Pi", zip(fixture()), { paths, _, _ -> paths.map(::metadata) })
        val cell = ChartLibrary(root).snapshot().packages.single().cells.single()
        val catalog = cell.catalogMetadata!!
        assertEquals("no5-pi-map-cache-v1", catalog.provider)
        assertEquals(stored.cells.single().catalogMetadata, catalog)
        assertNotEquals(ChartMetadataContract.sourceKey(cell.copy(provider = "noaa-s57", piCache = null)), catalog.sourceKey)
        assertEquals(catalog.sourceKey, ChartMetadataContract.sourceKey(cell, stored.piCache))
        val plan = ChartPlanningCatalog.fromPackage(stored, setOf(cell.name)).cells.single()
        assertEquals(catalog, plan.metadata); assertEquals(catalog.provider, plan.key.provider)
        assertEquals(catalog.sourceKey, plan.key.sourceKey)
        val withoutMetadata = stored.copy(cells = listOf(cell.copy(catalogMetadata = null)))
        assertEquals(plan.key, ChartPlanningCatalog.fromPackage(withoutMetadata, setOf(cell.name)).cells.single().key)
        assertEquals(catalog.sourceKey, ChartMetadataContract.sourceKey(cell, stored.piCache!!.copy(exportedUtc = "2026-10-12T12:00:00Z")))
        assertNotEquals(catalog.sourceKey, ChartMetadataContract.sourceKey(cell, stored.piCache.copy(licenseHost = "Other")))
        assertNotEquals(catalog.sourceKey, ChartMetadataContract.sourceKey(cell, stored.piCache.copy(product = "Other")))
        // A stale NOAA-labelled sidecar must remain conservative and must not relabel the source.
        File(root, "packages/${stored.id}/spatial-index-v2.json").writeText(
            Json.encodeToString(ChartCatalogIndex(cells = listOf(catalog.copy(provider = "noaa-s57")))))
        assertNull(library.snapshot().packages.single().cells.single().catalogMetadata)
    }
    @Test fun residentReleaseRestoresTheWholePrivateCatalogWithBothPinsAndHostIdentity() = runBlocking {
        val library = ChartLibrary(root)
        val stored = library.importZipWithMetadata("Pi", zip(fixture(update = 1200)), { paths, _, _ -> paths.map(::metadata) })
        val selected = ChartSelection(stored.id, listOf("ZA-TEST"))
        val binding = library.residentSelection(selected)
        val source = ChartResidentAdapter.catalog(binding.planning, binding.pack, binding.dataDirectory, ChartResidentBudget(2)).sources.single()
        assertEquals(PI_CACHE_PROVIDER, source.provider)
        assertEquals("South Africa", source.product); assertEquals("No5", source.licenseHost)
        assertEquals(listOf("ZA-TEST.senc", "ZA-TEST.json"), source.files.map { File(it.path).name })
        assertEquals(binding.release.cells.single().sourceKey, source.sourceKey)
        library.activateResident(binding)
        val reopened = ChartLibrary(root)
        assertEquals(2, reopened.snapshot().selectionVersion)
        val restored = reopened.residentSelection(selected, reopened.snapshot().activeRelease)
        assertEquals(binding.release, restored.release)
        assertEquals(source, ChartResidentAdapter.catalog(restored.planning, restored.pack, restored.dataDirectory, ChartResidentBudget(2)).sources.single())
        // Metadata-only rebind leaves bytes to per-cell native admission. Eager verification still rejects mutation.
        File(source.files[1].path).appendText("changed")
        assertEquals(binding.release, reopened.residentSelection(selected, binding.release).release)
        rejected { reopened.paths(selected) }
        assertEquals(selected, reopened.snapshot().active)
    }
    @Test fun privateUpdatesKeepProvenanceAndRejectCrossHostReplacementWithoutChangingSelection() = runBlocking {
        val library = ChartLibrary(root)
        val old = library.importZipWithMetadata("Pi", zip(fixture()), { paths, _, _ -> paths.map(::metadata) })
        val selection = ChartSelection(old.id, listOf("ZA-TEST"))
        library.activateResident(library.residentSelection(selection))
        val incoming = library.importZipWithMetadata("Pi update", zip(fixture(update = 1)), { paths, _, _ -> paths.map(::metadata) })
        val replacement = library.mergeUpdatePackage(old.id, incoming.id)
        assertEquals(old.piCache, replacement.piCache)
        assertEquals(1, replacement.cells.single().update)
        assertNotNull(library.residentSelection(ChartSelection(replacement.id, listOf("ZA-TEST"))))
        val other = library.importZip("Other host", zip(fixture(host = "Other")), { _, _ -> })
        rejected { runBlocking { library.mergeUpdatePackage(old.id, other.id) } }
        assertEquals(selection, library.snapshot().active)
        assertEquals(0, library.snapshot().packages.single { it.id == old.id }.cells.single().update)
    }
    @Test fun privateMetadataBackfillUsesItsOriginalProvider() = runBlocking {
        val library = ChartLibrary(root)
        val stored = library.importZip("Pi", zip(fixture()), { _, _ -> })
        val before = File(root, "packages/${stored.id}/package.json").readBytes()
        val added = library.ensureCellMetadata(stored.id, "ZA-TEST") { paths, _ -> paths.map(::metadata) }
        assertEquals("no5-pi-map-cache-v1", added!!.provider)
        assertEquals(added, ChartLibrary(root).snapshot().packages.single().cells.single().catalogMetadata)
        assertArrayEquals(before, File(root, "packages/${stored.id}/package.json").readBytes())
    }
    @Test fun editionChecksStayWithinProductAndLicenseHost() = runBlocking {
        val library = ChartLibrary(root)
        library.importZip("new", zip(fixture(edition = 2)), { _, _ -> })
        rejected { runBlocking { library.importZip("old", zip(fixture()), { _, _ -> fail("Native must not run") }) } }
        library.importZip("different product", zip(fixture(product = "Other")), { _, _ -> })
        library.importZip("different host", zip(fixture(host = "Other")), { _, _ -> })
        assertEquals(3, library.snapshot().packages.size)
    }
    @Test fun privateCellWithSameNameCannotSupplyNoaaUpdateBasisOrBlockNoaaEdition() = runBlocking {
        val library = ChartLibrary(root)
        library.importZip("Pi", zip(fixture(name = "US5BOSCC", edition = 65535)), { _, _ -> })
        val assets = File("../chart-engine/src/main/assets/charts/noaa")
        val update = mapOf("US5BOSCC.001" to File(assets, "US5BOSCC.001").readBytes())
        rejected { runBlocking { library.importZip("no basis", zip(update), { _, _ -> fail("Native must not run") }) } }
        val noaa = library.importZip("NOAA", zip(mapOf("US5BOSCC.000" to File(assets, "US5BOSCC.000").readBytes())), { _, _ -> })
        assertNull(noaa.piCache)
        val updated = library.importZip("NOAA update", zip(update), { _, _ -> })
        assertEquals(1, updated.cells.single().update); assertNull(updated.piCache)
        assertEquals("000", library.paths(ChartSelection(updated.id, listOf("US5BOSCC"))).single().extension)
    }
    @Test fun importRetainsProvenanceAndExtentAndSurvivesRestartWithoutActivating() = runBlocking {
        val library = ChartLibrary(root); var checked = 0
        val stored = library.importZip("Pi vectors", zip(fixture()), { paths, _ ->
            assertEquals("senc", paths.single().extension); checked++
        })
        assertEquals(1, checked); assertEquals("No5", stored.piCache?.licenseHost); assertFalse(stored.source.contains("NOAA"))
        assertNull(library.snapshot().active)
        val selection = ChartSelection(stored.id, listOf("ZA-TEST")); library.activate(selection)
        val reopened = ChartLibrary(root)
        assertEquals(selection, reopened.snapshot().active)
        assertEquals(-29.5, reopened.snapshot().packages.single().cells.single().selectionExtent!!.centerLatitude, 0.0)
        assertEquals("senc", reopened.paths(selection).single().extension)
        val provenance = File(root, "packages/${stored.id}/data/provenance/ZA-TEST.json")
        provenance.appendText("changed")
        rejected { reopened.paths(selection) }
        assertEquals(selection, reopened.snapshot().active)
    }
    @Test fun missingManifestCannotMasqueradeAsNoaaS57() {
        rejected { runBlocking { ChartLibrary(root).importZip("invalid", zip(fixture().filterKeys { it != PiCachePackage.manifestName }), { _, _ -> fail("Native must not run") }) } }
        assertTrue(ChartLibrary(root).snapshot().packages.isEmpty())
    }
    @Test fun hashMetadataAndUnexpectedFilesRejectBeforeNativeValidation() {
        val original = fixture()
        val badPackages = listOf(original + ("cells/ZA-TEST.senc" to byteArrayOf(1)), original + ("key.XML" to byteArrayOf(1)),
            original + (PiCachePackage.manifestName to String(original.getValue(PiCachePackage.manifestName)).replace("\"featureCount\":1", "\"featureCount\":2").toByteArray()))
        for (files in badPackages) rejected { runBlocking { ChartLibrary(root).importZip("bad", zip(files), { _, _ -> fail("Native must not run") }) } }
        assertTrue(ChartLibrary(root).snapshot().packages.isEmpty()); assertTrue(File(root, "staging").listFiles()!!.isEmpty())
    }
    @Test fun failedNativeValidationPreservesPreviouslyActivePackage() = runBlocking {
        val library = ChartLibrary(root); val old = library.importZip("old", zip(fixture()), { _, _ -> })
        val selection = ChartSelection(old.id, listOf("ZA-TEST")); library.activate(selection)
        rejected { runBlocking { library.importZip("bad native", zip(fixture()), { _, _ -> error("geometry rejected") }) } }
        assertEquals(selection, library.snapshot().active); assertEquals(1, library.snapshot().packages.size)
        assertTrue(File(root, "staging").listFiles()!!.isEmpty())
    }
    @Test fun cancellationCleansPiStagingAndPublishesNothing() = runBlocking {
        val library = ChartLibrary(root)
        val operation = launch { library.importZip("cancel", zip(fixture()), { _, _ -> throw CancellationException("cancel") }) }
        operation.join()
        assertTrue(library.snapshot().packages.isEmpty()); assertTrue(File(root, "staging").listFiles()!!.isEmpty())
    }
}
