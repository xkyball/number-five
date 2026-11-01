package com.no5.helm.library

import com.no5.helm.chart.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExperimentalCoveragePacketSelectorTest {
    private fun catalog(cells: List<ChartCell>) = ChartPlanningCatalog.fromPackage(
        ChartPackage("f".repeat(36), "p5-shadow-test", 0, cells), cells.map { it.name }.toSet())
    private fun real(): ChartPlanningCatalog {
        val inputs=Json.parseToJsonElement(javaClass.getResource("/noaa-118-inputs-v1.json")!!.readText()).jsonObject.getValue("cells").jsonArray
        val native=javaClass.getResource("/noaa-118-native-metadata-v1.jsonl")!!.readText().lines().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }.associateBy { it.getValue("name").jsonPrimitive.content }
        val cells=inputs.mapIndexed { order,v ->
            val input=v.jsonObject;val name=input.getValue("name").jsonPrimitive.content;val m=native.getValue(name).getValue("metadata").jsonObject
            val b=m.getValue("bounds").jsonObject
            val bounds=ChartSelectionExtent(b.getValue("south").jsonPrimitive.double,b.getValue("north").jsonPrimitive.double,
                b.getValue("west").jsonPrimitive.double,b.getValue("east").jsonPrimitive.double)
            val files=input.getValue("files").jsonArray.associate { f -> f.jsonObject.getValue("name").jsonPrimitive.content to f.jsonObject.getValue("sha256").jsonPrimitive.content }
            val raw=ChartCell(name,input.getValue("edition").jsonPrimitive.int,input.getValue("update").jsonPrimitive.int,
                "$name.000","20261009",files,selectionExtent=bounds)
            fun rings(key:String)=m.getValue(key).jsonArray.map { r -> r.jsonArray.map { p -> p.jsonArray.let { ChartCellCoordinate(it[0].jsonPrimitive.double,it[1].jsonPrimitive.double) } } }
            val validated=ValidatedChartCell(raw.base,1,m.getValue("parserVersion").jsonPrimitive.content,
                m.getValue("parserIdentity").jsonPrimitive.content,m.getValue("preparedSourceKey").jsonPrimitive.content,1,
                m.getValue("nativeScale").jsonPrimitive.int,m.getValue("usage").jsonPrimitive.int,
                ChartCellBounds(bounds.south,bounds.west,bounds.north,bounds.east),rings("coverage"),rings("exclusions"),ChartCoverageCapabilities())
            assertEquals(input.getValue("sourceKey").jsonPrimitive.content,ChartMetadataContract.sourceKey(raw))
            raw.copy(catalogMetadata=ChartMetadataContract.attach(raw,order,validated))
        }
        assertEquals(118,cells.size);return catalog(cells)
    }

    private fun bounds(catalog: ChartPlanningCatalog): Map<ChartCellKey, ExperimentalPacketBound> {
        val native = javaClass.getResource("/noaa-118-native-metadata-v1.jsonl")!!.readText().lines()
            .filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
            .associateBy { it.getValue("name").jsonPrimitive.content }
        return catalog.cells.associate { c ->
            val metadata = requireNotNull(c.metadata)
            c.key to ExperimentalPacketBound(metadata.preparedSourceKey, metadata.parserIdentity,
                native.getValue(c.key.name).getValue("geometryAdmissionBytes").jsonPrimitive.long) }
    }
    @Test fun boston50000OffersSmallerCoveredDemandButNeverSubmitsIt() {
        val c = real(); val plan = ChartViewportPlanner(c).plan(ChartPlanCamera(42.33,-70.99,.06,0.0,2000,1200))
        val p = ExperimentalCoveragePacketSelector(c,bounds(c)).propose(plan,50000.0)
        assertTrue(p.reason,p.offered)
        assertEquals(0,p.original.uncoveredPixels); assertEquals(0,p.candidate!!.uncoveredPixels)
        assertTrue(p.candidate!!.plan.required.size < p.original.plan.required.size)
        assertTrue(p.candidateReservationBounds!! <= p.originalReservationBounds!!)
        assertNotEquals(p.original.plan.tag.selectionPolicyKey,p.candidate!!.plan.tag.selectionPolicyKey)
    }
    @Test fun overviewRejectsCoarseGreedyRegressionAndPreservesOriginal() {
        val c=real();val plan=ChartViewportPlanner(c).plan(ChartPlanCamera(42.36,-71.04,.01,0.0,2000,1200))
        val p=ExperimentalCoveragePacketSelector(c,bounds(c)).propose(plan,300000.0)
        assertFalse(p.offered);assertEquals(6,p.original.plan.required.size)
    }
    @Test fun rotatedReserveWithKnownGapIsNotOffered() {
        val c=real();val plan=ChartViewportPlanner(c).plan(ChartPlanCamera(42.36,-71.04,.01,.63,2500,1338))
        val p=ExperimentalCoveragePacketSelector(c,bounds(c)).propose(plan,300000.0)
        assertFalse(p.offered);assertEquals(118542,p.original.uncoveredPixels)
    }
    @Test fun absentOrStaleReservationIdentityNeverBecomesFits() {
        val c=real();val plan=ChartViewportPlanner(c).plan(ChartPlanCamera(42.33,-70.99,.06,0.0,2000,1200))
        assertFalse(ExperimentalCoveragePacketSelector(c,emptyMap()).propose(plan,50000.0).offered)
        val stale=bounds(c).mapValues { (_,v)->v.copy(preparedSourceKey="0".repeat(64)) }
        assertFalse(ExperimentalCoveragePacketSelector(c,stale).propose(plan,50000.0).offered)
    }
    @Test fun boundsAreFrozenAgainstCallerMutation() {
        val c=real();val mutable=bounds(c).toMutableMap();val selector=ExperimentalCoveragePacketSelector(c,mutable)
        mutable.clear();val plan=ChartViewportPlanner(c).plan(ChartPlanCamera(42.33,-70.99,.06,0.0,2000,1200))
        assertTrue(selector.propose(plan,50000.0).offered)
    }
}
