package com.no5.helm.network

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.*
import com.no5.helm.watchdog.deliverOriginProofOnMain
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

/** Real existing single IO worker and API29 MainLooper; observed714ms read and
 * 1110ms sample delta. Synthetic response, no real TLS/GPU-cause assertion. */
@RunWith(AndroidJUnit4::class)
class OriginProofWorkerDeviceTest {
    @Test fun sameWorkerAdmittedReadAndMainGateRetainWholeEnqueueBound() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName=="com.no5.helm.sonarfixture")
        val rows=JSONArray();var passed=false
        SecureRandom().nextBytes(ByteArray(16));JSONObject("{\"warm\":1}")
        try {
            for(mode in listOf("legacy_periodic250","admitted_read","busy_worker_expired","queued_epoch_change","closed")) {
                val row=runCase(mode);rows.put(row)
                val expected=when(mode){"admitted_read"->"DELIVERED";"closed"->"CLOSED";else->"REJECTED"}
                assertEquals(mode,expected,row.getString("state"))
                if(mode in listOf("legacy_periodic250","busy_worker_expired"))assertEquals("BOUND_AFTER_READ",row.getString("phase"))
                if(mode=="queued_epoch_change")assertEquals("CONTEXT_AT_CONSUMER",row.getString("phase"))
                if(mode=="admitted_read")assertTrue(row.getLong("upper_at_gate_ms")<=2000)
                assertEquals(mode,1,row.getInt("IO_worker_threads"))
            }
            rows.put(mainHandoffCase());passed=true
        } finally {
            val report=JSONObject().put("schema","no5.origin-proof-worker-native/1").put("passed",passed)
                .put("qualification_seconds",0).put("hard_limit_ms",2000).put("cases",rows)
                .put("real_Android_MainLooper_and_existing_IO_worker",true)
                .put("synthetic_transport_observed_read714_sample1110",true)
                .put("real_TLS_or_presentation_stall_initiator_reproduced",false).put("UI_Activity_created",false)
            val root=File(context.filesDir,"sonar-private-proof").apply{mkdirs()}
            File(root,"origin-worker-native1.json").writeText(report.toString(2))
        }
    }
    private fun runCase(mode:String):JSONObject {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val main=Handler(Looper.getMainLooper())
        val request=SonarOriginClockRequest("private-observed-worker",3,7,42,100000)
        val initial=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1)
        val busy=CountDownLatch(1);val busyDone=CountDownLatch(1)
        val reads=AtomicInteger();val polls=AtomicInteger();val callbacks=AtomicInteger()
        val threads=java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        var valid=true;var upperAtGate:Long?=null;lateinit var client:OriginClockProofClient
        val wake=OriginProofConsumerWake(MainOriginProofConsumerDispatcher()){client.tick()}
        val http=object:PiHttpTransport {
            override fun get(path:String):String {
                threads.add(Thread.currentThread().id);reads.incrementAndGet();SystemClock.sleep(714)
                val query=path.substringAfter('?').split('&').associate{val part=it.split('=',limit=2);part[0] to URLDecoder.decode(part[1],"UTF-8")}
                return JSONObject().put("schema","no5.origin-clock-proof/1").put("nonce",query["nonce"])
                    .put("serverSessionId",request.serverSessionId).put("generation",3).put("displayGeneration",7)
                    .put("pingId",42).put("timeUtcNs",100000000123L).put("originClockUpperEpochMs",101110L)
                    .put("uncertaintyMs",1).toString()
            }
            override fun close()=Unit
        }
        client=OriginClockProofClient(http,onReady={
            if(mode=="queued_epoch_change")main.post{valid=false}
            wake.ready()
        }){valid&&it==request}
        val sub=BackgroundPiPollScheduler("private-origin-proof").schedule(250) {
            threads.add(Thread.currentThread().id)
            if(polls.incrementAndGet()==1){initial.countDown();release.await(2,TimeUnit.SECONDS)}
            else client.pump()
        } as ReadTaskPollSubscription
        try {
            check(initial.await(2,TimeUnit.SECONDS));release.countDown()
            // Both paths use the same250ms cadence and full monotonic request start.
            SystemClock.sleep(40)
            if(mode in listOf("busy_worker_expired","closed")) {
                check(sub.requestRead(Runnable{threads.add(Thread.currentThread().id);busy.countDown();SystemClock.sleep(240);busyDone.countDown()}))
                check(busy.await(2,TimeUnit.SECONDS))
            }
            instrumentation.runOnMainSync {
                check(client.request(request){proof->
                    val task=Runnable{
                        check(Looper.myLooper()==Looper.getMainLooper())
                        upperAtGate=proof?.upperAgeMs(SystemClock.elapsedRealtimeNanos())
                        callbacks.incrementAndGet();done.countDown()
                    }
                    check(deliverOriginProofOnMain(main,task))
                })
                if(mode!="legacy_periodic250")check(sub.requestRead(Runnable{client.pump()}))
                if(mode=="closed"){client.close();wake.close();done.countDown()}
            }
            check(done.await(4,TimeUnit.SECONDS)){"native proof timed out"}
            if(mode=="closed"){check(busyDone.await(2,TimeUnit.SECONDS));SystemClock.sleep(20)}
            instrumentation.runOnMainSync{}
            val d=client.diagnostics
            assertEquals(if(mode=="closed")0 else 1,callbacks.get())
            assertEquals(if(mode=="closed")0 else 1,reads.get());assertEquals(0,wake.failures)
            return JSONObject().put("mode",mode).put("state",d.state).put("phase",d.phase)
                .put("queue_ns",d.queueNanos).put("read_ns",d.readNanos).put("consumer_wait_ns",d.consumerWaitNanos)
                .put("full_ns",d.fullNanos).put("upper_at_gate_ms",upperAtGate?:JSONObject.NULL)
                .put("HTTP_reads",reads.get()).put("callbacks",callbacks.get()).put("IO_worker_threads",threads.size)
        } finally {release.countDown();sub.close();client.close();wake.close()}
    }
    private fun mainHandoffCase():JSONObject {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val main=Handler(Looper.getMainLooper())
        var inline=false;var marker=false;var inlineWait=0L
        instrumentation.runOnMainSync {
            main.post{marker=true};val at=SystemClock.elapsedRealtimeNanos()
            check(deliverOriginProofOnMain(main,Runnable{inline=!marker;inlineWait=SystemClock.elapsedRealtimeNanos()-at}))
            assertTrue(inline)
        }
        val blocked=CountDownLatch(1);val done=CountDownLatch(1)
        main.post{blocked.countDown();SystemClock.sleep(120)};check(blocked.await(2,TimeUnit.SECONDS))
        val at=SystemClock.elapsedRealtimeNanos();val request=SonarOriginClockRequest("native-main-negative",1,1,1,100000)
        val proof=SonarOriginClockProof(request,101950,at,at)
        var foreignOnMain=false;var expired=false;var foreignWait=0L
        check(deliverOriginProofOnMain(main,Runnable{
            foreignOnMain=Looper.myLooper()==Looper.getMainLooper();foreignWait=SystemClock.elapsedRealtimeNanos()-at
            expired=proof.upperAgeMs(SystemClock.elapsedRealtimeNanos())==null;done.countDown()
        }))
        check(done.await(2,TimeUnit.SECONDS));assertTrue(foreignOnMain);assertTrue(expired)
        return JSONObject().put("mode","Main_handoff").put("already_Main_inline",inline).put("inline_wait_ns",inlineWait)
            .put("foreign_callback_runs_on_Main",foreignOnMain).put("foreign_wait_ns",foreignWait)
            .put("expired_foreign_proof_rejected_at_gate",expired)
    }
}
