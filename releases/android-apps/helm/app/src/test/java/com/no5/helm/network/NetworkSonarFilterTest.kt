package com.no5.helm.network
import com.no5.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetworkSonarFilterTest {
    private val identity=SourceIdentity("helm-test",SourceMode.PI,"SONAR")
    private fun state(generation:Long=1)=SonarV1State(SonarV1Origin("SONARD",false,"server",generation),0,1_000_000,true,"LIVE",false,false,true,Calibration(),2.5,true,true)
    private class Http:PiHttpTransport {
        var puts=0;var fail=false;var mode="auto";var resultStatus="APPLIED";var request:JSONObject?=null
        var onPut:(()->Unit)?=null
        override fun put(path:String,body:String):String {
            assertEquals("/v1/sonar/filter",path);puts++;request=JSONObject(body);onPut?.invoke()
            if(fail)throw java.io.IOException("reply lost")
            return result("ACCEPTED")
        }
        private fun result(status:String):String=JSONObject().put("version",1).put("kind","sonar-command-result")
            .put("clientId","helm-test").put("requestId",request!!.getLong("requestId"))
            .put("serverSessionId","server").put("generation",request!!.getLong("generation"))
            .put("commandId",7).put("status",status).put("confirmedMode",request!!.getString("mode")).toString()
        override fun get(path:String):String=if(path=="/v1/sonar/filter")JSONObject().put("version",1).put("kind","sonar-filter")
            .put("serverSessionId","server").put("generation",1).put("mode",mode)
            .put("effectiveMode","auto_raw").put("active",false).put("reason","raw bottom selected").toString()else result(resultStatus)
        override fun close()=Unit
    }
    @Test fun newestUnsentModeWinsAndAcceptanceIsNotExecution() {
        val http=Http();val filter=NetworkSonarFilter(identity,http){1_000_000};filter.observe(state())
        val old=filter.submit(SonarFilterRequest(identity.sessionId,1,SonarFilterMode.OFF))
        val last=filter.submit(SonarFilterRequest(identity.sessionId,2,SonarFilterMode.ON))
        assertEquals(0,http.puts);assertEquals(SonarFilterCommandStatus.SUPERSEDED,filter.result(old.commandId!!)!!.status)
        filter.pump();filter.tick();assertEquals(1,http.puts)
        assertEquals(SonarFilterCommandStatus.RUNNING,filter.result(last.commandId!!)!!.status)
        filter.pump();filter.tick();assertEquals(SonarFilterCommandStatus.APPLIED,filter.result(last.commandId!!)!!.status)
        assertEquals(SonarFilterMode.ON,filter.state.value.mode);assertEquals(1,http.puts);filter.close()
    }
    @Test fun lostPutAnswerIsUnknownAndNeverReplayed() {
        val http=Http();http.fail=true;val filter=NetworkSonarFilter(identity,http){1_000_000};filter.observe(state())
        val result=filter.submit(SonarFilterRequest(identity.sessionId,1,SonarFilterMode.OFF));filter.pump();filter.pump()
        assertEquals(1,http.puts);assertEquals(SonarFilterCommandStatus.UNKNOWN,filter.result(result.commandId!!)!!.status);filter.close()
    }
    @Test fun aRemoteSupersededModeRemainsSupersededInThePublicResult() {
        val http=Http();val filter=NetworkSonarFilter(identity,http){1_000_000};filter.observe(state())
        val result=filter.submit(SonarFilterRequest(identity.sessionId,1,SonarFilterMode.OFF))
        filter.pump();http.resultStatus="SUPERSEDED";filter.pump()
        assertEquals(SonarFilterCommandStatus.SUPERSEDED,filter.result(result.commandId!!)!!.status)
        filter.pump();assertEquals(1,http.puts);filter.close()
    }
    @Test fun aLateResponseCannotReplaceTheNewSource() {
        val http=Http();val filter=NetworkSonarFilter(identity,http){1_000_000};filter.observe(state())
        val result=filter.submit(SonarFilterRequest(identity.sessionId,1,SonarFilterMode.OFF))
        http.onPut={filter.observe(state(2))};filter.pump();filter.tick()
        assertEquals(SonarFilterCommandStatus.UNKNOWN,filter.result(result.commandId!!)!!.status)
        assertNull(filter.state.value.mode);filter.close()
    }
    @Test fun effectiveFilterReasonComesFromTheReadbackRatherThanTheCommandAck() {
        val filter=NetworkSonarFilter(identity,Http()){1_000_000};filter.observe(state())
        filter.pump();filter.tick()
        assertEquals(SonarFilterMode.AUTO,filter.state.value.mode)
        assertEquals("auto_raw",filter.state.value.effectiveMode)
        assertEquals("raw bottom selected",filter.state.value.reason)
        filter.close()
    }
}
