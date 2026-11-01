package com.no5.helm.network

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.*
import com.no5.helm.HomeGatewayEndpointResolver
import com.no5.gateway.HomeGatewayClient
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
class OriginClockLiveDeviceTest {
 @Test fun actualConfiguredTlsSourceAndOriginProofUseSameOriginalPingAndHardTwoSeconds() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  require(context.packageName=="com.no5.helm")
  require(context.packageManager.getPackageInfo(context.packageName,0).longVersionCode==161L)
  require(InstrumentationRegistry.getArguments().getString("no5OriginLiveProof")=="true")
  val report=JSONObject().put("schema","no5.origin-clock-live-native/1").put("qualification_seconds",0)
  var source:NetworkPiSonarSource?=null
  var security:GatewaySecurityProfile?=null
  var resolver:HomeGatewayEndpointResolver?=null
  var passed=false
  try {
   // Actual production UID uses the existing signature-protected Home READ/PROFILE
   // contract. No credential export/import, permission change or settings write.
   val home=HomeGatewayClient(context)
   val record=checkNotNull(home.status().configuration);require(record.usable)
   val endpoint=record.origin.url
   security=checkNotNull(home.load(endpoint))
   resolver=HomeGatewayEndpointResolver(context)
   val transport=UrlConnectionPiTransport(endpoint,PiNetworkPolicy.SONAR,security,resolver)
   val stream=OkHttpSonarStreamTransport(endpoint,security,resolver)
   source=NetworkPiSonarSource(SourceIdentity("private-actual-origin-proof",SourceMode.PI,"Private read-only Sonar proof"),transport,stream)
   val deadline=SystemClock.elapsedRealtimeNanos()+8_000_000_000L
   var first:SonarSourceFrame?=null
   var current:SonarSourceFrame?=null
   while(SystemClock.elapsedRealtimeNanos()<deadline) {
    source.tick();val frame=source.frames.value
    if(frame!=null) {
     require(frame.hostPackageVersion=="0.4.32")
     if(first==null)first=frame
     if(frame.frame.pingId!=first!!.frame.pingId){current=frame;break}
    }
    SystemClock.sleep(10)
   }
   val actual=checkNotNull(current){"no advancing actual original source frame"}
   val request=SonarOriginClockRequest(checkNotNull(actual.serverSessionId),checkNotNull(actual.serverGeneration),checkNotNull(actual.displaySelection).generation,actual.frame.pingId,actual.frame.timestampEpochMs)
   var completed=false;var proof:SonarOriginClockProof?=null
   val before=SystemClock.elapsedRealtimeNanos()
   require(source.requestOriginClockProof(request){proof=it;completed=true})
   val enqueuedAfter=SystemClock.elapsedRealtimeNanos()
   while(!completed&&SystemClock.elapsedRealtimeNanos()-before<=2_000_000_000L){source.tick();SystemClock.sleep(5)}
   require(completed){"one actual proof did not complete within hard2s"}
   val result=checkNotNull(proof){"actual proof rejected; no retry"}
   val upper=checkNotNull(result.upperAgeMs(SystemClock.elapsedRealtimeNanos())){"actual full-enqueue age exceeded2s"}
   require(result.request==request&&result.requestStartedNanos in before..enqueuedAfter)
   report.put("actual_TLS_and_auth_via_production_transports",true).put("actual_Host_version","0.4.32").put("actual_Home_signature_protected_profile",true).put("UI_Activity_created",false)
    .put("same_original_ping_context",true).put("ping_id",request.pingId).put("hard_freshness_limit_ms",2000)
    .put("origin_upper_age_ms",upper).put("full_enqueue_monotonic_ns",SystemClock.elapsedRealtimeNanos()-result.requestStartedNanos)
    .put("original_UTC_modified",false).put("extra_Host_poll_or_TX_command",false).put("proof_requests",1)
   passed=true
  }catch(error:Throwable){report.put("error_class",error.javaClass.simpleName)}
  finally {
   try{source?.close();security?.close();resolver?.close();report.put("own_source_socket_and_HTTP_closed",true)}catch(error:Throwable){passed=false;report.put("cleanup_error_class",error.javaClass.simpleName)}
   report.put("passed",passed)
   val root=File(context.filesDir,"sonar-private-proof").apply{mkdirs()}
   File(root,"origin-live-native1.json").writeText(report.toString(2))
  }
  check(passed){report.toString()}
 }
}
