package com.no5.helm.network

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real API29 MainLooper/elapsed-realtime delivery. Source replies are synthetic
 * observed-timing fixtures, not a reproduction of the live GPU-stall initiator. */
@RunWith(AndroidJUnit4::class)
class OriginProofConsumerDeviceTest {
    @Test fun observedConsumerExpiryAndReadyDeliveryUseSameUnchangedBound() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName=="com.no5.helm.sonarfixture")
        val report=JSONObject().put("schema","no5.origin-proof-consumer-native/1")
            .put("qualification_seconds",0).put("hard_limit_ms",2000)
            .put("actual_Android_MainLooper",true).put("actual_monotonic_clock",true)
            .put("synthetic_transport_observed_timing_fixture",true)
            .put("real_origin_TLS_or_GPU_stall_initiator_reproduced",false)
            .put("UI_Activity_created",false)
        val rows=JSONArray();var passed=false
        try {
            // Setup only: class initialization/entropy does not alter a proof clock.
            SecureRandom().nextBytes(ByteArray(16));JSONObject("{\"warm\":1}")
            for(mode in listOf("legacy_periodic85","ready","busy_main_expired","queued_context_change","closed")) {
                val row=runCase(mode);rows.put(row)
                val expected=when(mode){"ready"->"DELIVERED";"closed"->"CLOSED";else->"REJECTED"}
                assertEquals(mode,expected,row.getString("state"))
                if(mode=="legacy_periodic85") assertEquals("BOUND_AT_CONSUMER",row.getString("phase"))
                if(mode=="busy_main_expired") assertEquals("BOUND_AT_CONSUMER",row.getString("phase"))
                if(mode=="queued_context_change") assertEquals("CONTEXT_AT_CONSUMER",row.getString("phase"))
                if(mode=="ready") assertTrue(row.getLong("upper_at_consumer_ms")<=2000)
            }
            passed=true
        } finally {
            report.put("passed",passed).put("cases",rows)
            val root=File(context.filesDir,"sonar-private-proof").apply{mkdirs()}
            File(root,"origin-consumer-native1.json").writeText(report.toString(2))
        }
        check(passed)
    }
    private fun runCase(mode:String):JSONObject {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val main=Handler(Looper.getMainLooper())
        val request=SonarOriginClockRequest("private-observed-origin",3,7,42,100000)
        val done=CountDownLatch(1);val go=CountDownLatch(1);val workerReady=CountDownLatch(1)
        val calls=AtomicInteger();var valid=true;var delivered:SonarOriginClockProof?=null
        var readyUpper:Long?=null;var consumerUpper:Long?=null
        lateinit var client:OriginClockProofClient
        val wake=OriginProofConsumerWake(MainOriginProofConsumerDispatcher()){client.tick()}
        val http=object:PiHttpTransport {
            override fun get(path:String):String {
                SystemClock.sleep(806)
                val query=path.substringAfter('?').split('&').associate {
                    val part=it.split('=',limit=2);part[0]to URLDecoder.decode(part[1],"UTF-8")
                }
                return JSONObject().put("schema","no5.origin-clock-proof/1").put("nonce",query["nonce"])
                    .put("serverSessionId",request.serverSessionId).put("generation",3).put("displayGeneration",7)
                    .put("pingId",42).put("timeUtcNs",100000000123L)
                    .put("originClockUpperEpochMs",101121L).put("uncertaintyMs",1).toString()
            }
            override fun close()=Unit
        }
        client=OriginClockProofClient(http,onReady={
            readyUpper=client.diagnostics.fullNanos?.let{1121L+(it+999999)/1000000}
            when(mode){
                "legacy_periodic85" -> main.postDelayed({client.tick()},85)
                "busy_main_expired" -> {main.post{SystemClock.sleep(100)};wake.ready()}
                "queued_context_change" -> {main.post{valid=false};wake.ready()}
                "closed" -> {main.post{SystemClock.sleep(40)};wake.ready();client.close();wake.close();done.countDown()}
                else -> repeat(20){wake.ready()}
            }
        }){valid&&it==request}
        val worker=Thread({workerReady.countDown();go.await();SystemClock.sleep(15);client.pump()},"private-proof-http")
        worker.start();check(workerReady.await(2,TimeUnit.SECONDS))
        try {
            instrumentation.runOnMainSync {
                check(client.request(request){proof->
                    check(Looper.myLooper()==Looper.getMainLooper())
                    delivered=proof;consumerUpper=proof?.upperAgeMs(SystemClock.elapsedRealtimeNanos())
                    calls.incrementAndGet();done.countDown()
                })
            }
            go.countDown();check(done.await(4,TimeUnit.SECONDS)){"bounded native consumer not delivered"}
            worker.join(1000);check(!worker.isAlive)
            instrumentation.runOnMainSync { /* all queued negative controls are drained */ }
            val d=client.diagnostics
            if(mode=="closed")assertEquals(0,calls.get()) else assertEquals(1,calls.get())
            return JSONObject().put("mode",mode).put("state",d.state).put("phase",d.phase)
                .put("queue_ns",d.queueNanos).put("read_ns",d.readNanos).put("consumer_wait_ns",d.consumerWaitNanos)
                .put("full_ns",d.fullNanos).put("upper_at_read_ms",readyUpper)
                .put("upper_at_consumer_ms",consumerUpper).put("callback_count",calls.get())
                .put("proof_delivered",delivered!=null).put("wake_failures",wake.failures)
        } finally {client.close();wake.close();http.close();check(!worker.isAlive)}
    }
}
