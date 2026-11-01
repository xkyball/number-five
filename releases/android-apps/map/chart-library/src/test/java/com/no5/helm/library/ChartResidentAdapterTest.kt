package com.no5.helm.library

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlinx.serialization.json.*

class ChartResidentAdapterTest {
    private fun cell(name: String, update: Int = 0, prefix: String = "") = ChartCell(name, 1, update,
        "$prefix$name.000", "20261010", (0..update).associate { "$prefix$name.%03d".format(it) to
            (if (it == 0) "a" else "b").repeat(64) })
    private fun pack(cells: List<ChartCell>) = ChartPackage("f".repeat(36), "test", 0, cells)
    private fun planning(pack: ChartPackage) = ChartPlanningCatalog.fromPackage(pack, pack.cells.map { it.name }.toSet())

    @Test fun releaseCatalogOver100IsIndependentOfResidentBudgetAndDoesNoFileIO() {
        val pack = pack((0..149).map { cell("US5T%04d".format(it)) })
        val catalog = planning(pack)
        val root = File("/not-an-existing-package/data")
        val transport = ChartResidentAdapter.catalog(catalog, pack, root, ChartResidentBudget(cells = 2))
        assertEquals(150, transport.sources.size)
        assertEquals(2, transport.budget.cells)
        assertFalse(root.exists())
        assertEquals((0..149).toList(), transport.sources.map { it.order })
        val plan = ChartViewportPlanner(catalog).plan(ChartPlanCamera(42.36, -71.04, .15, .63, 2000, 1070))
        val demand = ChartResidentAdapter.demand(plan)
        assertEquals(150, demand.required.size)
        assertEquals(plan.tag.catalogGeneration, demand.catalogGeneration)
        assertEquals(plan.tag.cameraRevision, demand.cameraRevision)
        assertEquals(1070, demand.camera.height)
    }

    @Test fun fullOrderedChainIdentityMatchesFrozenNativeCanonicalVectorAndIgnoresDirectory() {
        val pack = pack(listOf(cell("US5TEST0", 1, "ENC_ROOT/")))
        val source = pack.cells.single()
        assertEquals("4b5bca158b5ec053708bec0842fe719523d1c6dc97b440d715bfb296d9413ac3",
            ChartMetadataContract.sourceKey(source))
        val planning = planning(pack)
        val first = ChartResidentAdapter.catalog(planning, pack, File("/first/data"), ChartResidentBudget())
        val second = ChartResidentAdapter.catalog(planning, pack, File("/second/data"), ChartResidentBudget())
        assertEquals(first.sources.single().sourceKey, second.sources.single().sourceKey)
        assertEquals(listOf("US5TEST0.000", "US5TEST0.001"), first.sources.single().files.map { File(it.path).name })
        assertTrue(first.sources.single().files.first().path.startsWith("/first/data/ENC_ROOT/"))
        val changed = source.copy(files = source.files + ("ENC_ROOT/US5TEST0.001" to "c".repeat(64)))
        assertNotEquals(ChartMetadataContract.sourceKey(source), ChartMetadataContract.sourceKey(changed))
        assertThrows(IllegalArgumentException::class.java) {
            ChartResidentAdapter.catalog(planning, pack.copy(cells = listOf(changed)), File("/first/data"), ChartResidentBudget())
        }
    }

    @Test fun pathTraversalAbsolutePathsAndIncompleteChainsAreRejectedBeforeNativeWork() {
        for (source in listOf(cell("US5TEST0", prefix = "../"), cell("US5TEST0", prefix = "/outside/"),
            cell("US5TEST0", 1).copy(files = mapOf("US5TEST0.000" to "a".repeat(64))))) {
            val pack = pack(listOf(source))
            assertThrows(IllegalArgumentException::class.java) {
                ChartResidentAdapter.catalog(planning(pack), pack, File("/data"), ChartResidentBudget())
            }
        }
    }

    @Test fun transportSnapshotsCannotBeMutatedThroughCallerAliases() {
        val files = linkedMapOf("US5TEST0.000" to "a".repeat(64))
        val pack = pack(listOf(cell("US5TEST0").copy(files = files)))
        val planning = planning(pack)
        val transport = ChartResidentAdapter.catalog(planning, pack, File("/data"), ChartResidentBudget())
        files["US5TEST0.000"] = "b".repeat(64)
        assertEquals("a".repeat(64), transport.sources.single().files.single().sha256)
        assertThrows(UnsupportedOperationException::class.java) {
            (transport.sources as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (transport.sources.single().files as MutableList).clear()
        }
    }

    @Test fun realP1BostonSourcesFeedTheWholeViewportP2PlanWithoutPreparedAbiGuessing() {
        val fixture = Json.parseToJsonElement(javaClass.getResource("/native-cell-metadata-v1.json")!!.readText()).jsonObject
        val data = File("../chart-engine/src/main/assets/charts/noaa")
        val cells = fixture.getValue("cells").jsonArray.mapIndexed { order, entry ->
            val value = entry.jsonObject
            val name = value.getValue("cell").jsonPrimitive.content
            val files = value.getValue("sourceFiles").jsonObject.mapValues { it.value.jsonPrimitive.content }
            files.forEach { (path, hash) -> assertEquals(hash, ChartLibrary.sha256(File(data, path))) }
            val identification = S57Identification.read(File(data, "$name.000"))
            val cell = ChartCell(name, identification.edition, files.size - 1, "$name.000", identification.issueDate, files)
            val metadata = ChartCellMetadataDecoder.decode(cell.base, value.getValue("metadata").toString())
            cell.copy(catalogMetadata = ChartMetadataContract.attach(cell, order, metadata))
        }
        val pack = pack(cells)
        val planning = planning(pack)
        val bound = ChartResidentAdapter.catalog(planning, pack, data, ChartResidentBudget(cells = 2))
        assertEquals(18, bound.sources.sumOf { it.files.size })
        assertEquals(fixture.getValue("cells").jsonArray.map { it.jsonObject.getValue("catalogSourceKey").jsonPrimitive.content },
            bound.sources.map { it.sourceKey })
        val plan = ChartViewportPlanner(planning).plan(ChartPlanCamera(42.36, -71.04, .06, .63, 2500, 1338))
        assertEquals(bound.sources.map { it.sourceKey }, ChartResidentAdapter.demand(plan).required)
        assertEquals(4, plan.required.size)
        // Sidecar prepared keys refer to their original parser/build. Native load
        // verifies the content source, then computes its own current ABI key.
        assertTrue(cells.all { val metadata = it.catalogMetadata!!; metadata.preparedSourceKey != metadata.sourceKey })
    }
    @Test fun wsvResidentPinsMatchNativeCanonicalVectorWithoutPretendingToBeNoaa() {
        val source = cell("1W7EST00", 1, "ENC_ROOT/").copy(provider = S57Profiles.WSV)
        assertEquals("822c57454f42dbb0cad2a5aa20c87010f898d9883790b5767b02954afe9e60e3", ChartMetadataContract.sourceKey(source))
        val pack = pack(listOf(source)); val planning = planning(pack)
        val transport = ChartResidentAdapter.catalog(planning, pack, File("/not-existing/wsv"), ChartResidentBudget(cells = 2))
        assertEquals(S57Profiles.WSV, transport.sources.single().provider)
        assertEquals(listOf("1W7EST00.000", "1W7EST00.001"), transport.sources.single().files.map { File(it.path).name })
        val changed = source.copy(provider = S57Profiles.NOAA)
        assertThrows(IllegalArgumentException::class.java) {
            ChartResidentAdapter.catalog(planning, pack.copy(cells = listOf(changed)), File("/data"), ChartResidentBudget())
        }
    }
    @Test fun privateDescriptorMatchesNativeCanonicalIdentityAndPinsProvenanceAfterSenc() {
        val provenance = PiCacheProvenance("South Africa", "No5", "2026-10-11T12:00:00Z")
        val cell = ChartCell("ZA-TEST", 1, 1200, "cells/ZA-TEST.senc", "20261011",
            linkedMapOf("provenance/ZA-TEST.json" to "b".repeat(64), "cells/ZA-TEST.senc" to "a".repeat(64)),
            provider = PI_CACHE_PROVIDER, piCache = provenance)
        val pack = pack(listOf(cell)).copy(piCache = provenance)
        val source = ChartResidentAdapter.catalog(planning(pack), pack, File("/private/data"), ChartResidentBudget()).sources.single()
        assertEquals("4adad6c83d5867a84d6a5f2dc0d7d2b3c0db1796f249b58459daf8e63243636b", source.sourceKey)
        assertEquals(listOf("ZA-TEST.senc", "ZA-TEST.json"), source.files.map { File(it.path).name })
        assertEquals("No5", source.licenseHost); assertEquals("South Africa", source.product)
    }

}
