package com.no5.helm.network

import android.os.SystemClock
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.*
import com.no5.helm.LuminaSourceSettingsStore
import com.no5.helm.security.GatewayOrigin
import com.no5.helm.security.GatewaySecurityProfile
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

/** Explicit private post-activation read-only proof, using actual configured TLS/auth.
 * No UI, settings, source command, trust bypass, clock offset or retry. */
@RunWith(AndroidJUnit4::class)
class OriginLuminaClockAdmittedWorkerDeviceTest {
 @Test fun actualConfiguredTlsSourceAndOriginProofUseSameOriginalPingAndHardTwoSeconds() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  require(context.packageName=="com.no5.helm")
  require(context.packageManager.getPackageInfo(context.packageName,0).longVersionCode==175L)
  require(InstrumentationRegistry.getArguments().getString("no5OriginLiveProof")=="true")
  val report=JSONObject().put("schema","no5.origin-clock-live-native/1").put("qualification_seconds",0)
  var source:NetworkPiSonarSource?=null
  var security:GatewaySecurityProfile?=null
  var store:LuminaSourceSettingsStore?=null
  var phase="ACTUAL_LUMINA_ADAPTER"
  var passed=false
  try {
   // Same production Lumina SSO owner/route; historical Home is not used.
   val instrument=InstrumentationRegistry.getInstrumentation()
   instrument.runOnMainSync{store=LuminaSourceSettingsStore(context)}
   var endpoint:String?=null
   val setupDeadline=SystemClock.elapsedRealtime()+8000
   while(endpoint==null&&SystemClock.elapsedRealtime()<setupDeadline){
    instrument.runOnMainSync{
     val actual=store!!.load()
     if(actual.connectionEnabled){security=store!!.cachedSecurityProfiles.load(actual.endpoint);if(security!=null)endpoint=actual.endpoint}
    }
    if(endpoint==null)SystemClock.sleep(20)
   }
   phase="LUMINA_SESSION_READY";val actualEndpoint=checkNotNull(endpoint){"actual Lumina session unavailable"}
   val resolver=store!!.endpointResolver
   val transport=UrlConnectionPiTransport(actualEndpoint,PiNetworkPolicy.SONAR,security,resolver)
   val stream=OkHttpSonarStreamTransport(actualEndpoint,security,resolver)
   val live=NetworkPiSonarSource(SourceIdentity("private-actual-origin-proof",SourceMode.PI,"Private read-only Sonar proof"),transport,stream,originProofDispatcher=MainOriginProofConsumerDispatcher())
   source=live
   phase="READ_ACTUAL_ADVANCING_SOURCE"
   val deadline=SystemClock.elapsedRealtimeNanos()+8_000_000_000L
   var first:SonarSourceFrame?=null
   var current:SonarSourceFrame?=null
   while(SystemClock.elapsedRealtimeNanos()<deadline) {
    var frame:SonarSourceFrame?=null
    instrument.runOnMainSync{live.tick();frame=live.frames.value}
    if(frame!=null) {
     val captured=checkNotNull(frame)
     require(captured.hostPackageVersion=="0.4.32")
     if(first==null)first=captured
     if(captured.frame.pingId!=first!!.frame.pingId){current=captured;break}
    }
    SystemClock.sleep(10)
   }
   val actual=checkNotNull(current){"no advancing actual original source frame"}
   val request=SonarOriginClockRequest(checkNotNull(actual.serverSessionId),checkNotNull(actual.serverGeneration),checkNotNull(actual.displaySelection).generation,actual.frame.pingId,actual.frame.timestampEpochMs)
   val completed=CountDownLatch(1);val proof=AtomicReference<SonarOriginClockProof?>()
   phase="ONE_ORIGIN_PROOF"
   val before=SystemClock.elapsedRealtimeNanos()
   instrument.runOnMainSync{require(live.requestOriginClockProof(request){check(Looper.myLooper()==Looper.getMainLooper());proof.set(it);completed.countDown()})}
   val enqueuedAfter=SystemClock.elapsedRealtimeNanos()
   val remaining=2_000_000_000L-(SystemClock.elapsedRealtimeNanos()-before)
   require(remaining>0&&completed.await(remaining,TimeUnit.NANOSECONDS)){"one actual ready-delivered proof did not complete within hard2s"}
   val result=checkNotNull(proof.get()){"actual ready proof rejected; no retry"}
   val upper=checkNotNull(result.upperAgeMs(SystemClock.elapsedRealtimeNanos())){"actual full-enqueue age exceeded2s"}
   require(result.request==request&&result.requestStartedNanos in before..enqueuedAfter)
   report.put("actual_TLS_and_auth_via_production_transports",true).put("actual_Host_version","0.4.32").put("actual_Lumina_signature_protected_profile",true).put("UI_Activity_created",false)
    .put("same_original_ping_context",true).put("ping_id",request.pingId).put("hard_freshness_limit_ms",2000)
    .put("origin_upper_age_ms",upper).put("full_enqueue_monotonic_ns",SystemClock.elapsedRealtimeNanos()-result.requestStartedNanos)
    .put("ready_event_without_periodic_consumer_poll",true).put("consumer_wait_ns",live.originClockDiagnostics().consumerWaitNanos)
    .put("consumer_wake_failures",live.originClockDiagnostics().consumerWakeFailures)
    .put("original_UTC_modified",false).put("extra_Host_poll_or_TX_command",false).put("proof_requests",1)
   passed=true
  }catch(error:Throwable){report.put("error_class",error.javaClass.simpleName).put("phase",phase)}
  finally {
   try{source?.close();security?.close();InstrumentationRegistry.getInstrumentation().runOnMainSync{store?.close()};report.put("own_source_socket_and_HTTP_closed",true)}catch(error:Throwable){passed=false;report.put("cleanup_error_class",error.javaClass.simpleName)}
   report.put("passed",passed)
   val root=File(context.filesDir,"sonar-private-proof").apply{mkdirs()}
   File(root,"origin-lumina-admitted-worker-native1.json").writeText(report.toString(2))
  }
  check(passed){report.toString()}
 }
}
