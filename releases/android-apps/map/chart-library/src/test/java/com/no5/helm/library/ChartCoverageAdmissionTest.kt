package com.no5.helm.library

import com.no5.helm.chart.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

class ChartCoverageAdmissionTest {
    @Test fun nauticalCoverageAlwaysWinsOverBackgroundAndItsExclusionsRemainFillable() {
        val broad = cell("coarse",0,1000,rectangle(42.0,-72.0,43.0,-70.0))
        val bgName = "NO5BG_N40_W080"
        val bgRaw = broad.copy(name=bgName,base="cells/$bgName.senc",provider=BACKGROUND_CACHE_PROVIDER,
            files=mapOf("cells/$bgName.senc" to "a".repeat(64),"provenance/$bgName.json" to "d".repeat(64)))
        val background = bgRaw.copy(catalogMetadata=ChartMetadataContract.attach(bgRaw,0,broad.catalogMetadata!!.validated(bgRaw.base)))
        val fine = cell("nautical",1,50000,rectangle(42.33,-71.07,42.39,-71.01),
            listOf(rectangle(42.355,-71.045,42.365,-71.035)))
        val c = catalog(listOf(background,fine))
        val pose = ChartPlanCamera(42.36,-71.04,.04,.13,211,137)
        val admitted = ChartCoveragePlanner(c).admit(ChartViewportPlanner(c).plan(pose),75000.0,true)
        assertTrue(admitted.proven); assertTrue(admitted.masked); assertEquals(0,admitted.uncoveredPixels)
        val owners = (0 until 211*137).map { admitted.raster!!.get(it) }
        assertTrue(owners.any { admitted.rasterKeys[it-1].name == "nautical" })
        assertEquals(bgName,admitted.rasterKeys[admitted.raster!!.get(68*211+105)-1].name)
    }
    private fun pack(cells: List<ChartCell>) = ChartPackage("f".repeat(36), "coverage-test", 0, cells)
    private fun catalog(cells: List<ChartCell>) = pack(cells).let { ChartPlanningCatalog.fromPackage(it, cells.map { c -> c.name }.toSet()) }
    private fun rectangle(s: Double, w: Double, n: Double, e: Double) = listOf(ChartCellCoordinate(s,w),
        ChartCellCoordinate(s,e),ChartCellCoordinate(n,e),ChartCellCoordinate(n,w))
    private fun cell(name: String, order: Int, scale: Int, ring: List<ChartCellCoordinate>,
        exclusions: List<List<ChartCellCoordinate>> = emptyList()): ChartCell {
        val bounds = ChartSelectionExtent(ring.minOf { it.latitude },ring.maxOf { it.latitude },ring.minOf { it.longitude },ring.maxOf { it.longitude })
        val raw=ChartCell(name,1,0,"$name.000","20261010",mapOf("$name.000" to "a".repeat(64)),selectionExtent=bounds)
        val validated=ValidatedChartCell(raw.base,1,"no5-s57-osenc201-metadata-v1","b".repeat(64),"c".repeat(64),1,scale,5,
            ChartCellBounds(bounds.south,bounds.west,bounds.north,bounds.east),listOf(ring),exclusions,ChartCoverageCapabilities())
        return raw.copy(catalogMetadata=ChartMetadataContract.attach(raw,order,validated))
    }
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
    @Test fun indexedQueriesAndActualPlansEqualTheExistingScanForRealDataAndZoomRoundtrips() {
        val c=real();val indexed=ChartViewportPlanner(c,IndexedChartCellIndex(c));val scan=ChartViewportPlanner(c)
        val random=Random(5419)
        repeat(100) {
            val camera=ChartPlanCamera(42.0+random.nextDouble(),-71.5+random.nextDouble(),
                listOf(.006,.01,.03,.15,1.2)[it%5],random.nextDouble(-3.0,3.0),2000,1200)
            assertEquals(scan.plan(camera),indexed.plan(camera))
        }
        for(ppm in listOf(.01,1.2,.3,.15,.03,.01)) {
            val camera=ChartPlanCamera(42.36,-71.04,ppm,0.0,2000,1200)
            assertEquals(scan.plan(camera),indexed.plan(camera))
        }
    }
    @Test fun indexedEdgesUnknownExtentsDatelineAndSevenThousandDescriptorsMatchScan() {
        val random=Random(7123)
        val cells=(0 until 7114).map { i ->
            val s=random.nextDouble(-85.0,84.0);val w=random.nextDouble(-180.0,179.0)
            ChartCell("cell$i",1,0,"cell$i.000","20261010",mapOf("cell$i.000" to "a".repeat(64)),
                selectionExtent=if(i%100==0)null else ChartSelectionExtent(s,s+.1,w,w+.1))
        }+listOf(ChartCell("wrap",1,0,"wrap.000","20261010",mapOf("wrap.000" to "a".repeat(64)),selectionExtent=ChartSelectionExtent(-1.0,1.0,179.0,-179.0)))
        val c=catalog(cells);val a=FullScanChartCellIndex(c);val b=IndexedChartCellIndex(c)
        repeat(200) {
            val s=random.nextDouble(-85.0,84.0);val w=random.nextDouble(-180.0,179.0)
            val q=ChartSelectionExtent(s,s+1,w,w+1);assertEquals(a.query(q),b.query(q))
        }
        for(q in listOf(ChartSelectionExtent(-1.0,1.0,-180.0,-180.0),ChartSelectionExtent(-1.0,1.0,180.0,180.0),
            ChartSelectionExtent(-1.0,1.0,179.0,-179.0),ChartSelectionExtent(-85.0,85.0,-180.0,180.0)))assertEquals(a.query(q),b.query(q))
    }
    @Test fun occludedScaleWitnessPreservesNativeMaskedModeWithoutDroppingSameScaleCohort() {
        val c=catalog(listOf(cell("fine",0,12000,rectangle(42.0,-72.0,43.0,-70.0)),
            cell("coarse",1,350000,rectangle(42.0,-72.0,43.0,-70.0))))
        val pose=ChartPlanCamera(42.36,-71.04,.06,0.0,211,137)
        val admitted=ChartCoveragePlanner(c).admit(ChartViewportPlanner(c).plan(pose),15000.0,true)
        assertTrue(admitted.proven);assertTrue(admitted.masked);assertEquals(2,admitted.plan.required.size)
        val same=catalog(listOf(cell("one",0,12000,rectangle(42.0,-72.0,43.0,-70.0)),cell("two",1,12000,rectangle(42.0,-72.0,43.0,-70.0))))
        val original=ChartViewportPlanner(same).plan(pose)
        assertEquals(original,ChartCoveragePlanner(same).admit(original,15000.0).plan)
    }
    @Test fun cacheUnchangedPoseCancellationAndUnknownMetadataRemainSafe() {
        val c=real();val p=ChartViewportPlanner(c);val planner=ChartCoveragePlanner(c)
        val pose=ChartPlanCamera(42.36,-71.04,.01,0.0,2000,1200);val original=p.plan(pose)
        val a=planner.admit(original,300000.0);assertSame(a,planner.admit(p.plan(pose),300000.0))
        try { planner.admit(original,300000.0) { error("superseded") };fail() } catch(e:IllegalStateException){assertEquals("superseded",e.message)}
        val stale=catalog(listOf(ChartCell("unknown",1,0,"unknown.000","20261010",mapOf("unknown.000" to "a".repeat(64)))))
        val unknown=ChartViewportPlanner(stale).plan(pose);val result=ChartCoveragePlanner(stale).admit(unknown,300000.0)
        assertFalse(result.proven);assertEquals(unknown,result.plan)
    }
    @Test fun originalPolicyAuthoritySurvivesZoomPanAndConservativeTransitions() {
        val c=catalog(listOf(cell("overview",0,350000,rectangle(42.0,-72.0,43.0,-70.0)),
            cell("west-detail",1,22000,rectangle(42.0,-72.0,43.0,-71.0)),
            cell("east-harbour",2,8000,rectangle(42.0,-71.0,43.0,-70.0))))
        val viewport=ChartViewportPlanner(c);val coverage=ChartCoveragePlanner(c)
        val west=ChartPlanCamera(42.36,-71.1,.15,.031,211,137)
        val firstPlan=viewport.plan(west)
        val pendingScale=coverage.admit(firstPlan,null)
        val first=coverage.admit(firstPlan,20000.0)
        val zoom=coverage.admit(viewport.plan(west.copy(pixelsPerMetre=.3)),10000.0)
        val pan=coverage.admit(viewport.plan(west.copy(longitude=-70.9,pixelsPerMetre=.3)),10000.0)
        val empty=coverage.admit(viewport.plan(west.copy(longitude=-69.0)),20000.0)
        assertFalse(pendingScale.proven);assertTrue(first.proven);assertTrue(zoom.proven);assertTrue(pan.proven)
        assertTrue(first.plan.required.any { it.name=="west-detail" })
        assertFalse(first.plan.required.any { it.name=="east-harbour" })
        assertTrue(pan.plan.required.any { it.name=="east-harbour" })
        assertFalse(pan.plan.required.any { it.name=="west-detail" })
        assertTrue(empty.plan.required.isEmpty())
        for(result in listOf(pendingScale,first,zoom,pan,empty))
            assertEquals("Native fixed policy must accept changing camera results",firstPlan.tag.selectionPolicyKey,
                result.plan.tag.selectionPolicyKey)
        assertNotEquals(first.plan.tag.cameraKey,zoom.plan.tag.cameraKey)
        assertNotEquals(zoom.plan.tag.cameraKey,pan.plan.tag.cameraKey)
        assertTrue(pan.plan.tag.cameraRevision>first.plan.tag.cameraRevision)
    }
    @Test fun changingActualPlanningRulesStillChangesPolicyAuthority() {
        val c=catalog(listOf(cell("fine",0,12000,rectangle(42.0,-72.0,43.0,-70.0)),
            cell("coarse",1,350000,rectangle(42.0,-72.0,43.0,-70.0))))
        val pose=ChartPlanCamera(42.36,-71.04,.06,.031,211,137)
        val a=ChartCoveragePlanner(c).admit(ChartViewportPlanner(c).plan(pose),15000.0)
        val b=ChartCoveragePlanner(c).admit(ChartViewportPlanner(c,policy=ChartPlanPolicy(contextPixels=16.0)).plan(pose),15000.0)
        assertNotEquals(a.plan.tag.selectionPolicyKey,b.plan.tag.selectionPolicyKey)
    }
    @Test fun everyRealPixelMatchesTheUnchangedNativeCoverageOracleAndJointMode() {
        val c=real();val planner=ChartViewportPlanner(c,IndexedChartCellIndex(c))
        val oracle=Json.parseToJsonElement(javaClass.getResource("/noaa-118-coverage-oracle-v1.json")!!.readText()).jsonArray
        for(v in oracle) {
            val o=v.jsonObject;val a=o.getValue("camera").jsonArray
            val camera=ChartPlanCamera(a[0].jsonPrimitive.double,a[1].jsonPrimitive.double,a[2].jsonPrimitive.double,
                a[3].jsonPrimitive.double,a[4].jsonPrimitive.int,a[5].jsonPrimitive.int)
            val result=ChartCoveragePlanner(c).admit(planner.plan(camera),o.getValue("chartScale").jsonPrimitive.double,true)
            assertTrue("No coverage proof for $camera: ${result.reason}",result.proven)
            val raster=result.raster!!;val digest=MessageDigest.getInstance("SHA-256");val global=c.cells.mapIndexed { i,cell-> cell.key to i+1 }.toMap()
            val block=ByteArray(4096);var at=0
            for(p in 0 until raster.pixels){val id=raster.get(p);val owner=if(id==0)0 else global.getValue(result.rasterKeys[id-1])
                repeat(4){b->block[at++]=(owner ushr (b*8)).toByte()};if(at==block.size){digest.update(block);at=0}}
            if(at>0)digest.update(block,0,at)
            assertEquals("Full native owner raster $camera",o.getValue("ownerSha256").jsonPrimitive.content,digest.digest().joinToString(""){"%02x".format(it)})
            assertEquals(o.getValue("uncoveredPixels").jsonPrimitive.int,result.uncoveredPixels)
            assertEquals(o.getValue("requiredWithWitness").jsonArray.map{it.jsonPrimitive.content},result.plan.required.map{it.name})
            assertEquals(o.getValue("masked").jsonPrimitive.boolean,result.masked)
        }
    }
    @Test fun cachedCoverageRebindsCurrentResidencyExactlyLikeFreshAdmission() {
        val c=real();val viewport=ChartViewportPlanner(c);val coverage=ChartCoveragePlanner(c)
        val camera=ChartPlanCamera(42.36,-71.04,.01,0.0,2000,1200)
        val first=coverage.admit(viewport.plan(camera),300000.0);assertTrue(first.proven)
        val occluded=c.cells.first { it.key !in first.plan.required }.key
        val original=viewport.plan(camera,setOf(first.plan.required.first(),occluded))
        val cached=coverage.admit(original,300000.0)
        val fresh=ChartCoveragePlanner(c).admit(original,300000.0)
        assertEquals(fresh.plan,cached.plan)
        assertEquals(listOf(first.plan.required.first()),cached.plan.retain)
        assertTrue(occluded in cached.plan.drop)
        assertEquals(fresh.loadOrder,cached.loadOrder)
    }
    @Test fun loadOrderUsesAdditionalContributedPixelsAndLeavesOccludedWitnessLast() {
        val c=catalog(listOf(cell("fine",0,12000,rectangle(42.0,-72.0,43.0,-71.04)),
            cell("overlap",1,24000,rectangle(42.0,-72.0,43.0,-70.95)),
            cell("new-area",2,24000,rectangle(42.0,-70.95,43.0,-70.0)),
            cell("witness",3,350000,rectangle(42.0,-72.0,43.0,-70.0))))
        val camera=ChartPlanCamera(42.36,-71.04,.0037,.031,211,137)
        val result=ChartCoveragePlanner(c).admit(ChartViewportPlanner(c).plan(camera),15000.0,true)
        assertTrue(result.reason,result.proven)
        // Calculate contribution independently from the final owner raster.
        val counts=result.rasterKeys.indices.associateWith { i -> (0 until result.raster!!.pixels).count { result.raster.get(it)==i+1 } }
        val expected=result.plan.required.sortedWith(compareByDescending<ChartCellKey> {
            counts.getValue(result.rasterKeys.indexOf(it)) }.thenBy { c.cells.first { cell -> cell.key==it }.order })
        assertEquals(expected,result.loadOrder)
        assertTrue(result.loadOrder.indexOfFirst { it.name=="new-area" } < result.loadOrder.indexOfFirst { it.name=="overlap" })
        val witness=catalog(listOf(cell("fine",0,12000,rectangle(42.0,-72.0,43.0,-70.0)),
            cell("witness",1,350000,rectangle(42.0,-72.0,43.0,-70.0))))
        assertEquals(listOf("fine","witness"),ChartCoveragePlanner(witness).admit(ChartViewportPlanner(witness).plan(camera),15000.0).loadOrder.map { it.name })
    }
    @Test fun slantedFloat32EdgeAtPixelCentreConservativelyRetainsBothCoverageAndExclusionCohorts() {
        // Diagonal through camera centre: its endpoints avoid half-pixel
        // centres, while its interpolated crossing hits the centre exactly.
        val diagonal=listOf(ChartCellCoordinate(-.001,-.001),ChartCellCoordinate(.001,.001),ChartCellCoordinate(.001,-.001))
        val broad=rectangle(-.01,-.01,.01,.01)
        for(exclusion in listOf(false,true))for(rotation in listOf(0.0,.63)) {
            val c=catalog(listOf(cell("fine",0,12000,if(exclusion)broad else diagonal,if(exclusion)listOf(diagonal) else emptyList()),
                cell("coarse",1,350000,broad)))
            val original=ChartViewportPlanner(c).plan(ChartPlanCamera(0.0,0.0,.2,rotation,101,99))
            val result=ChartCoveragePlanner(c).admit(original,15000.0,true)
            assertFalse("Precision-sensitive edge was used to prove absence",result.proven)
            assertEquals(original,result.plan)
        }
    }
    @Test fun slantedCoverageAndExclusionMatchNativeOnBothSidesOfPixelCentres() {
        val cases=Json.parseToJsonElement(javaClass.getResource("/slanted-native-coverage-oracle-v1.json")!!.readText()).jsonArray
        val diagonal=listOf(ChartCellCoordinate(-.001,-.001),ChartCellCoordinate(.001,.001),ChartCellCoordinate(.001,-.001))
        val broad=rectangle(-.01,-.01,.01,.01)
        for(value in cases) {
            val v=value.jsonObject;val exclusion=v.getValue("exclusion").jsonPrimitive.boolean;val o=v.getValue("oracle").jsonObject
            val a=o.getValue("camera").jsonArray
            val camera=ChartPlanCamera(a[0].jsonPrimitive.double,a[1].jsonPrimitive.double,a[2].jsonPrimitive.double,a[3].jsonPrimitive.double,a[4].jsonPrimitive.int,a[5].jsonPrimitive.int)
            val c=catalog(listOf(cell("fine",0,12000,if(exclusion)broad else diagonal,if(exclusion)listOf(diagonal) else emptyList()),cell("coarse",1,350000,broad)))
            val original=ChartViewportPlanner(c).plan(camera);val r=ChartCoveragePlanner(c).admit(original,15000.0,true)
            if(v.getValue("atCentre").jsonPrimitive.boolean){assertFalse(r.proven);assertEquals(original,r.plan);continue}
            assertTrue("${r.reason}: $camera",r.proven)
            val hash=MessageDigest.getInstance("SHA-256")
            for(p in 0 until r.raster!!.pixels){val id=r.raster.get(p);val global=if(id==0)0 else c.cells.indexOfFirst { it.key==r.rasterKeys[id-1] }+1
                hash.update(byteArrayOf(global.toByte(),(global ushr 8).toByte(),(global ushr 16).toByte(),(global ushr 24).toByte()))}
            assertEquals(o.getValue("ownerSha256").jsonPrimitive.content,hash.digest().joinToString(""){"%02x".format(it)})
            assertEquals(o.getValue("uncoveredPixels").jsonPrimitive.int,r.uncoveredPixels)
            assertEquals(o.getValue("requiredWithWitness").jsonArray.map { it.jsonPrimitive.content },r.plan.required.map { it.name })
            assertEquals(o.getValue("masked").jsonPrimitive.boolean,r.masked)
        }
    }
    @Test fun hostTimingSamplesUseChangedPosesAndReportMemoryWithoutHardwareClaims() {
        val c=real();val old=ChartViewportPlanner(c);val indexed=ChartViewportPlanner(c,IndexedChartCellIndex(c));val admission=ChartCoveragePlanner(c)
        val oldTimes=mutableListOf<Double>();val indexedTimes=mutableListOf<Double>();val coverageTimes=mutableListOf<Double>()
        var rasterBytes=0L;var conservative=0;var selected=0
        repeat(40) { i ->
            val camera=ChartPlanCamera(42.36,-71.04+i*.00001,.01,.0001*i,2000,1200)
            var start=System.nanoTime();val a=old.plan(camera);val tA=(System.nanoTime()-start)/1e6
            start=System.nanoTime();val b=indexed.plan(camera);val tIndex=(System.nanoTime()-start)/1e6;assertEquals(a,b)
            start=System.nanoTime();val r=admission.admit(b,300000.0,true);val tCover=(System.nanoTime()-start)/1e6
            assertTrue(r.reason,r.proven);rasterBytes=r.raster!!.bytes;conservative=r.conservativeCount;selected=r.plan.required.size
            if(i>=10){oldTimes+=tA;indexedTimes+=tIndex;coverageTimes+=tCover}
        }
        fun stats(times:List<Double>):String {val s=times.sorted();return "p50=${s[(s.size*.5).toInt()-1]},p95=${s[kotlin.math.ceil(s.size*.95).toInt()-1]},p99=${s[kotlin.math.ceil(s.size*.99).toInt()-1]}"}
        println("P6_HOST_CPU samples=30 warmup=10 changedPose=true canvas=2000x1200 catalog=118 oldPlannerMs=${stats(oldTimes)} indexedPlannerMs=${stats(indexedTimes)} coverageMs=${stats(coverageTimes)} rasterBytes=$rasterBytes conservative=$conservative selected=$selected; host JVM only, no Lumina/RSS/GPU/display claim")
    }
    @Test fun globalOrdinalAbove255AndEarlyCompletionKeepTheNativeFirstWitness() {
        val c=catalog((0 until 301).map { i -> cell(if(i==300)"fine" else "coarse$i",i,
            if(i==300)12000 else 350000,rectangle(42.0,-72.0,43.0,-70.0)) })
        val camera=ChartPlanCamera(42.36,-71.04,.06,0.0,211,137)
        val original=ChartViewportPlanner(c).plan(camera);assertEquals(301,original.required.size)
        var checkpoints=0
        val r=ChartCoveragePlanner(c).admit(original,15000.0,true){checkpoints++}
        assertTrue(r.reason,r.proven);assertTrue(r.masked)
        assertEquals(listOf("coarse0","fine"),r.plan.required.map { it.name })
        assertEquals(listOf("fine","coarse0"),r.loadOrder.map { it.name })
        assertEquals(4L*211*137,r.raster!!.bytes)
        assertTrue("Zero-contribution tails still scan every candidate",checkpoints<100)
        val oracle=Json.parseToJsonElement(javaClass.getResource("/large-ordinal-native-coverage-oracle-v1.json")!!.readText()).jsonObject
        val digest=MessageDigest.getInstance("SHA-256")
        for(p in 0 until r.raster.pixels){val owner=r.raster.get(p);assertEquals(301,owner)
            digest.update(byteArrayOf(owner.toByte(),(owner ushr 8).toByte(),(owner ushr 16).toByte(),(owner ushr 24).toByte()))}
        assertEquals(oracle.getValue("ownerSha256").jsonPrimitive.content,digest.digest().joinToString(""){"%02x".format(it)})
        assertEquals(oracle.getValue("requiredWithWitness").jsonArray.map { it.jsonPrimitive.content },r.plan.required.map { it.name })
    }
}
