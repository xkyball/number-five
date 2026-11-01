package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class OriginClockProofClientTest {
    private val request=SonarOriginClockRequest("gateway",3,7,42,100000)
    private var now=1_000_000_000L
    private var valid=true
    private var reads=0
    private var alter: (JSONObject)->Unit = {}
    private val http=object:PiHttpTransport {
        override fun get(path:String):String {
            reads++
            val query=path.substringAfter('?').split('&').associate {
                val parts=it.split('=',limit=2);parts[0] to URLDecoder.decode(parts[1],"UTF-8")
            }
            assertEquals("100000",query["observedAtEpochMs"])
            return JSONObject().put("schema","no5.origin-clock-proof/1").put("nonce",query["nonce"])
                .put("serverSessionId","gateway").put("generation",3).put("displayGeneration",7)
                .put("pingId",42).put("timeUtcNs",100000000123L)
                .put("originClockUpperEpochMs",100050).put("uncertaintyMs",1).also(alter).toString()
        }
        override fun post(path:String,body:String):String=error("READ ONLY")
        override fun put(path:String,body:String):String=error("READ ONLY")
        override fun close()=Unit
    }
    private fun client()=OriginClockProofClient(http,{now}){valid&&it==request}
    @Test fun singlePendingRequestConsumerHandoffAndNoBackgroundProofPolling() {
        val client=client();var calls=0;var result:SonarOriginClockProof?=null
        assertTrue(client.request(request){calls++;result=it})
        assertFalse(client.request(request){fail("duplicate")})
        client.pump();assertEquals(0,calls);assertEquals(1,reads)
        client.tick();assertEquals(1,calls);assertEquals(50L,result!!.upperAgeMs(now))
        client.pump();client.tick();assertEquals(1,reads);assertEquals(1,calls)
    }
    @Test fun queuedTimeCountsAndDeadlineNeverRenews() {
        val client=client();var result:SonarOriginClockProof?=null
        client.request(request){result=it};now+=1_800_000_000;client.pump()
        now+=200_000_000;client.tick();assertNull(result)
        assertEquals(1,reads)
    }
    @Test fun expiredQueuedRequestDoesNotReadNetwork() {
        val client=client();var calls=0
        client.request(request){calls++;assertNull(it)};now+=2_000_000_001;client.pump();client.tick()
        assertEquals(1,calls);assertEquals(0,reads)
    }
    @Test fun nonceContextTimestampPrecisionAndBoundsReject() {
        for(change in listOf<(JSONObject)->Unit>(
            {it.put("nonce","wrong")},{it.put("serverSessionId","foreign")},
            {it.put("generation",4)},{it.put("displayGeneration",8)},{it.put("pingId",41)},
            {it.put("timeUtcNs",100001000000L)},{it.put("originClockUpperEpochMs",99999)},
            {it.put("uncertaintyMs",0)},{it.put("originClockUpperEpochMs",100050.5)},
            {it.put("padding","x".repeat(2048))})) {
            alter=change;val client=client();var calls=0
            client.request(request){calls++;assertNull(it)};client.pump();client.tick();assertEquals(1,calls)
        }
    }
    @Test fun contextChangeAfterNetworkBeforeConsumerRejects() {
        val client=client();var calls=0
        client.request(request){calls++;assertNull(it)};client.pump();valid=false;client.tick()
        assertEquals(1,calls)
    }
    @Test fun localReconnectEpochChangeRejectsEvenWhenServerIdentityIsUnchanged() {
        var epoch=7L
        val client=OriginClockProofClient(http,{now},{epoch}){valid&&it==request}
        var calls=0;client.request(request){calls++;assertNull(it)};client.pump();epoch++;client.tick()
        assertEquals(1,calls)
    }
    @Test fun closeReleasesCallbackAndCannotRequeue() {
        val client=client();client.request(request){fail("closed callback")};client.close()
        client.pump();client.tick();assertEquals(0,reads)
        assertFalse(client.request(request){fail("closed")})
    }
    @Test fun atomicContextCaptureDoesNotFallBackWhenRejected() {
        val client=OriginClockProofClient(http,{now},{error("non-atomic token must not run")},
            captureContext={null}){true}
        assertFalse(client.request(request){fail("rejected")});assertEquals(0,reads)
    }
    @Test fun contextAdmissionTimeIsInsideUnrenewedMonotonicBracket() {
        val client=OriginClockProofClient(http,{now},captureContext={now+=2_000_000_001L;7L}){true}
        assertTrue(client.request(request){assertNull(it)});client.pump();client.tick();assertEquals(0,reads)
    }
    @Test fun choosingTheReceivedAnchorIncludesItsEntireDrainAndDoesNotRenewTheDeadline() {
        val c=client();var calls=0
        assertTrue(c.requestFrom({now+=1_950_000_001L;request}){calls++;assertNull(it)})
        c.pump();c.tick()
        assertEquals(1,calls);assertEquals(1,reads)
        assertEquals("BOUND_AFTER_READ",c.diagnostics.phase)
        assertEquals(1_950_000_001L,c.diagnostics.fullNanos)
        val late=client()
        assertTrue(late.requestFrom({now+=2_000_000_001L;request}){calls++;assertNull(it)})
        late.pump();late.tick();assertEquals(2,calls);assertEquals(1,reads)
        assertEquals("QUEUE_DEADLINE",late.diagnostics.phase)
    }
    @Test fun missingAnchorAndSecondPendingNeverStartAReadOrConsumeAnotherBatch() {
        val c=client()
        assertFalse(c.requestFrom({null}){fail("missing")})
        assertTrue(c.request(request){assertNotNull(it)})
        assertFalse(c.requestFrom({fail("second pending must not drain");request}){fail("duplicate")})
        c.pump();c.tick();assertEquals(1,reads)
    }
    @Test fun freshRecoveryInputHasSeparateContractAndIncludesTheOriginalEnqueueWait() {
        alter={it.put("schema","no5.recovery-input-proof/1").put("minimumPingId",42).put("minimumTimeUtcNs",100000000123L)
            .put("pingId",43).put("timeUtcNs",100001000123L).put("actualSource","own").put("validTxReference",true)}
        val c=OriginClockProofClient(http,{now}){it.serverSessionId==request.serverSessionId&&it.generation==request.generation&&it.displayGeneration==request.displayGeneration};var result:SonarOriginClockProof?=null
        assertTrue(c.requestRecoveryFrom({request}){result=it});now+=900_000_001L;c.pump();now+=100_000_000;c.tick()
        assertEquals(43L,result!!.request.pingId);assertEquals(100001L,result!!.request.originalEpochMs)
        assertEquals(1050L,result!!.upperAgeMs(now));assertEquals(1_000_000_001L,c.diagnostics.fullNanos)
        c.pump();assertEquals(1,reads)
    }
    @Test fun recoveryInputCannotChangeMinimumSourceEpochOrBypassHardDeadline() {
        val valid:(JSONObject)->Unit={it.put("schema","no5.recovery-input-proof/1").put("minimumPingId",42).put("minimumTimeUtcNs",100000000123L)
            .put("pingId",43).put("timeUtcNs",100001000123L).put("actualSource","own").put("validTxReference",true)}
        for(change in listOf<(JSONObject)->Unit>(
            {it.put("minimumPingId",41)},{it.put("minimumTimeUtcNs",99999000123L)},{it.put("pingId",41)},
            {it.put("timeUtcNs",99999000123L)},{it.put("actualSource","native")},{it.put("validTxReference",false)},
            {it.put("generation",4)},{it.put("displayGeneration",8)},{it.put("nonce","wrong")})){
            alter={valid(it);change(it)};val c=client();var calls=0
            assertTrue(c.requestRecoveryFrom({request}){calls++;assertNull(it)});c.pump();c.tick();assertEquals(1,calls)
        }
        alter=valid;val expired=client();var calls=0
        assertTrue(expired.requestRecoveryFrom({request}){calls++;assertNull(it)})
        now+=2_000_000_001;expired.pump();expired.tick();assertEquals(1,calls);assertEquals("QUEUE_DEADLINE",expired.diagnostics.phase)
    }

    @Test fun rejectedConsumerDelayRetainsBothTimesWithoutChangingTwoSecondGate() {
        val c=client();var result:SonarOriginClockProof?=null
        c.request(request){result=it};now+=100_000_000;c.pump();now+=1_900_000_001;c.tick()
        assertNull(result);val d=c.diagnostics
        assertEquals("REJECTED",d.state);assertEquals("BOUND_AT_CONSUMER",d.phase)
        assertEquals(100_000_000L,d.queueNanos);assertEquals(0L,d.readNanos)
        assertEquals(1_900_000_001L,d.consumerWaitNanos);assertEquals(2_000_000_001L,d.fullNanos)
        assertEquals(50L,d.sampleMinusOriginalMs)
    }
    @Test fun rejectedQueueAndContextHaveDistinctStages() {
        val c=client();c.request(request){assertNull(it)};now+=2_000_000_001;c.pump();c.tick()
        assertEquals("QUEUE_DEADLINE",c.diagnostics.phase);assertEquals(0,c.diagnostics.responseBytes)
        val other=client();valid=true;other.request(request){assertNull(it)};other.pump();valid=false;other.tick()
        assertEquals("CONTEXT_AT_CONSUMER",other.diagnostics.phase)
    }
    @Test fun actualHttpFailureStatusIsSafeButNoResponseBodyIsRetained() {
        val denied=object:PiHttpTransport {override fun get(path:String):String=throw PiHttpStatusException(503);override fun close()=Unit}
        val c=OriginClockProofClient(denied,{now}){true};c.request(request){assertNull(it)};c.pump();c.tick()
        assertEquals("HTTP_READ",c.diagnostics.phase);assertEquals(503,c.diagnostics.httpFailureStatus)
        assertEquals("PiHttpStatusException",c.diagnostics.exceptionType);assertEquals(0,c.diagnostics.responseBytes)
    }
    @Test fun diagnosticsCannotAdmitAnInvalidNonceOrBecomeAProofCache() {
        alter={it.put("nonce","wrong")};val c=client();c.request(request){assertNull(it)};c.pump();c.tick()
        assertEquals("SCHEMA_NONCE",c.diagnostics.phase);assertTrue(c.diagnostics.responseBytes>0)
        c.pump();c.tick();assertEquals(1,reads);c.close();assertEquals("CLOSED",c.diagnostics.state)
    }

    @Test fun observedRead1943ThenConsumer2028RejectsButReadyDeliveryKeepsEntireQueue() {
        alter={it.put("originClockUpperEpochMs",101121L)}
        val delayed=client();var old:SonarOriginClockProof?=null
        delayed.request(request){old=it};now+=15_400_625L+806_591_958L;delayed.pump()
        now+=84_745_209L;delayed.tick();assertNull(old)
        assertEquals(906_737_792L,delayed.diagnostics.fullNanos)
        assertEquals("BOUND_AT_CONSUMER",delayed.diagnostics.phase)
        now=1_000_000_000L;lateinit var prompt:OriginClockProofClient;var result:SonarOriginClockProof?=null
        prompt=OriginClockProofClient(http,{now},onReady={prompt.tick()}){valid&&it==request}
        prompt.request(request){result=it};now+=15_400_625L+806_591_958L;prompt.pump()
        assertEquals(1943L,result!!.upperAgeMs(now));assertEquals(821_992_583L,prompt.diagnostics.fullNanos)
        prompt.tick();assertEquals(2,reads)
    }
    @Test fun readyNotificationCannotGrantExpiredOrChangedContextProof() {
        var notices=0;val c=OriginClockProofClient(http,{now},onReady={notices++}){valid&&it==request}
        c.request(request){assertNull(it)};now+=2_000_000_001L;c.pump();c.tick()
        assertEquals(1,notices);assertEquals(0,reads)
        valid=true;val other=OriginClockProofClient(http,{now},onReady={notices++;valid=false}){valid&&it==request}
        other.request(request){assertNull(it)};other.pump();other.tick()
        assertEquals("CONTEXT_AT_CONSUMER",other.diagnostics.phase);assertEquals(2,notices)
    }

}
