package com.no5.helm.client

import com.no5.helm.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class PositionSourcesProjectionTest {
    private val now=10000L
    private fun candidate(id:String="synthetic.gps.1",stamp:Long=now,status:String="LIVE") = JSONObject()
        .put("sourceId",id).put("sourceRef",id).put("label",id).put("status",status).put("observedAtEpochMs",stamp)
        .put("position",JSONObject().put("latitudeDegrees",0.0).put("longitudeDegrees",0.0).put("altitudeMetres",JSONObject.NULL)).put("gnss",JSONObject())
    private fun block(vararg c:JSONObject)=JSONObject().put("version",1).put("sourceKind","REAL").put("simulated",false)
        .put("primarySourceId",c.firstOrNull()?.getString("sourceId") ?: JSONObject.NULL).put("total",c.size).put("omitted",0)
        .put("freshness",JSONObject().put("liveForMs",5000).put("lostAfterMs",30000))
        .put("sources",JSONArray(c.toList())).put("combination",JSONObject().put("available",false).put("reason","FUSION_NOT_IMPLEMENTED"))
    private fun decode(b:JSONObject)=PositionSourcesProjection.decode(JSONObject().put("positionSources",b).toString(),now)!!
    @Test fun strictOptionalProjectionRetainsExactRefsIndependentTimesAndRealZero() {
        val a=candidate("synthetic.a.129029");val b=candidate("synthetic.b.129029",now-1)
        val result=decode(block(a,b));assertTrue(result.valid);assertEquals(2,result.sources.size)
        assertEquals(GeoPoint(0.0,0.0),result.sources.first().position)
        assertEquals(now-1,result.sources.last().observedAtEpochMs!!)
        assertEquals("synthetic.b.129029",result.sources.last().sourceRef)
        assertFalse(result.combinationAvailable)
    }
    @Test fun publicationOfAnotherSensorDoesNotExpireGPSAndEnvelopeCannotFreshenGPS() {
        val payload=JSONObject(File("../../helm/contracts/fixtures/real-helm-v1.json").readText())
        val published=payload.getLong("publishedAtEpochMs")
        payload.put("positionSources",block(candidate(stamp=published+2000)))
        val decoded=GatewayV1Projection.decode(payload.toString(),published+2000)
        assertEquals(PositionStatus.LIVE,decoded.positionSources!!.sources.single().status)
        assertEquals(published+2000,decoded.positionSources!!.sources.single().observedAtEpochMs!!)
        payload.put("positionSources",block(candidate(stamp=published-6000)))
        assertEquals(PositionStatus.STALE,GatewayV1Projection.decode(payload.toString(),published).positionSources!!.sources.single().status)
    }
    @Test fun unknownVersionOrMalformedBlockDoesNotFallBackToPrimaryLegacy() {
        assertFalse(decode(block(candidate()).put("version",2)).valid)
        assertFalse(decode(block(candidate()).put("version",1.5)).valid)
        assertFalse(decode(block(candidate()).put("sourceKind","UNKNOWN")).valid)
        assertFalse(decode(block(candidate()).put("simulated",true)).valid)
        assertNull(PositionSourcesProjection.decode("{}",now))
    }
    @Test fun futureInvalidAndLostCoordinatesNeverBecomeFreshDefaults() {
        assertEquals(PositionStatus.INVALID,decode(block(candidate(stamp=now+1))).sources.single().status)
        assertEquals(PositionStatus.INVALID,decode(block(candidate().put("position",JSONObject.NULL))).sources.single().status)
        val lost=candidate(status="LOST").put("position",JSONObject.NULL)
        assertEquals(PositionStatus.LOST,decode(block(lost)).sources.single().status)
        val missing=candidate(status="UNAVAILABLE").put("position",JSONObject.NULL).put("observedAtEpochMs",JSONObject.NULL)
        assertEquals(PositionStatus.UNAVAILABLE,decode(block(missing)).sources.single().status)
    }
    @Test fun boundsAndDuplicateOrContradictorySourceIdsFailClosed() {
        assertFalse(decode(block(candidate(),candidate())).valid)
        assertFalse(decode(block(candidate().put("sourceRef","different"))).valid)
        assertFalse(decode(block(candidate().put("label","Invented device"))).valid)
        assertFalse(decode(block(candidate("x".repeat(201)))).valid)
        assertFalse(decode(block(candidate()).put("total",999999)).valid)
        assertFalse(decode(block(candidate()).put("omitted",1)).valid)
        assertFalse(decode(block(*(0..150).map { candidate("synthetic.$it".padEnd(200,'x')) }.toTypedArray())).valid)
    }
    @Test fun gnssKeepsIndependentTimeStatusAndExactSourceAndDOPIsNotMetres() {
        val c=candidate();val g=JSONObject().put("value",1.3).put("sourceRef",c.getString("sourceId"))
            .put("observedAtEpochMs",now-6000).put("status","LIVE")
        c.getJSONObject("gnss").put("horizontalDilution",g)
        val result=decode(block(c)).sources.single()
        assertEquals(PositionStatus.LIVE,result.status)
        assertEquals(PositionStatus.STALE,result.gnss["horizontalDilution"]!!.status)
        assertEquals("1.3",result.gnss["horizontalDilution"]!!.value)
        g.put("sourceRef","other");assertFalse(decode(block(c)).valid)
    }
    private fun withCombination(b:JSONObject, mode:String="CONSENSUS", reason:String="OK", status:String="LIVE"): JSONObject {
        val sources=b.getJSONArray("sources")
        val contributors=JSONArray((0 until sources.length()).map { index ->
            val c=sources.getJSONObject(index)
            JSONObject().put("sourceId",c.getString("sourceId")).put("observedAtEpochMs",c.getLong("observedAtEpochMs"))
        })
        return b.put("combination",JSONObject().put("available",true).put("version",1).put("method","EQUAL_WEIGHT_SPHERICAL_V1")
            .put("mode",mode).put("reason",reason).put("status",status).put("observedAtEpochMs",now)
            .put("contributors",contributors).put("position", if(status=="LIVE") JSONObject().put("latitudeDegrees",0.0).put("longitudeDegrees",0.00005) else JSONObject.NULL))
    }
    @Test fun gatewayConsensusAndConflictPreserveDerivedProvenance() {
        val r=decode(withCombination(block(candidate("synthetic.a"),candidate("synthetic.b"))))
        assertTrue(r.valid);assertTrue(r.combinationAvailable);assertEquals("CONSENSUS",r.combination!!.mode)
        assertEquals(2,r.combination!!.contributors.size);assertEquals(0.00005,r.combination!!.position!!.longitude,0.0)
        assertEquals(now,r.combination!!.observedAtEpochMs!!)
        val conflict=decode(withCombination(block(candidate("synthetic.a"),candidate("synthetic.b")),"NONE","SOURCE_DISAGREEMENT","INVALID"))
        assertTrue(conflict.valid);assertTrue(conflict.combinationAvailable);assertNull(conflict.combination!!.position)
        assertEquals("SOURCE_DISAGREEMENT",conflict.combinationReason)
    }
    @Test fun singleSourceIsExplicitAndContradictoryCombinationFailsClosed() {
        val single=withCombination(block(candidate()),"SINGLE_SOURCE","SINGLE_SOURCE")
        assertEquals("SINGLE_SOURCE",decode(single).combination!!.mode)
        single.getJSONObject("combination").put("method","UNKNOWN");assertFalse(decode(single).valid)
        assertFalse(decode(withCombination(block(candidate()))).valid)
        val future=withCombination(block(candidate()),"SINGLE_SOURCE","SINGLE_SOURCE")
        future.getJSONObject("combination").put("observedAtEpochMs",now+1);assertFalse(decode(future).valid)
        val unrelated=withCombination(block(candidate()),"SINGLE_SOURCE","SINGLE_SOURCE")
        unrelated.getJSONObject("combination").getJSONArray("contributors").getJSONObject(0).put("sourceId","not-present")
        assertFalse(decode(unrelated).valid)
    }
    @Test fun genericNUsesTheByteBudgetAndKeepsExactContributorIdentity() {
        for (count in listOf(3,20,40)) {
            val value=withCombination(block(*(0 until count).map { candidate("synthetic.$it") }.toTypedArray()))
            value.getJSONObject("combination").put("filterMethod","FRESH_MOTION_MAJORITY_V1")
                .put("motion",JSONObject().put("method","SIGNALK_MEAN_V1").put("speedOverGroundMetresPerSecond",0.0))
            val result=decode(value);assertTrue(result.valid);assertEquals(count,result.sources.size)
            assertEquals(count,result.combination!!.contributors.size)
            assertEquals(count,result.combination!!.contributors.map { it.sourceId }.toSet().size)
            value.getJSONObject("combination").getJSONArray("contributors").getJSONObject(1).put("sourceId","synthetic.0")
            assertFalse(decode(value).valid)
        }
    }
    @Test fun genericNOldestOriginalTimeRemainsConservativeAndCannotBeRefreshed() {
        val value=withCombination(block(*(0 until 20).map { candidate("synthetic.$it",if(it==0) now-1 else now) }.toTypedArray()))
        assertFalse(decode(value).valid)
        value.getJSONObject("combination").put("observedAtEpochMs",now-1)
        assertEquals(now-1,decode(value).combination!!.observedAtEpochMs!!)
        value.getJSONObject("combination").getJSONArray("contributors").getJSONObject(2).put("observedAtEpochMs",now-1)
        assertFalse(decode(value).valid)
    }
    @Test fun additiveBlockUsesExistingStrictWholeResponseReaderForDuplicates() {
        val payload=JSONObject(File("../../helm/contracts/fixtures/real-helm-v1.json").readText()).put("positionSources",block(candidate()))
        val duplicate=payload.toString().replaceFirst("\"sourceKind\":\"REAL\"", "\"sourceKind\":\"REAL\",\"sourceKind\":\"REAL\"")
        try { GatewayV1Projection.decode(duplicate,now);fail("Duplicate keys must remain rejected") } catch (_:IllegalArgumentException) {}
    }
}
