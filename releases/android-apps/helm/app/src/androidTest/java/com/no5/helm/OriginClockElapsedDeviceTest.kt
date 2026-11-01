package com.no5.helm
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.*
import com.no5.helm.network.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URLDecoder

@RunWith(AndroidJUnit4::class)
class OriginClockElapsedDeviceTest {
    @Test fun productionClockUsesElapsedRealtimeBracketAndUnchangedTwoSecondAge() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName=="com.no5.helm.sonarfixture")
        val request=SonarOriginClockRequest("synthetic-private-source",2,3,42,100000)
        val http=object:PiHttpTransport {
            override fun get(path:String):String {
                val query=path.substringAfter('?').split('&').associate {val fields=it.split('=',limit=2);fields[0]to URLDecoder.decode(fields[1],"UTF-8")}
                return JSONObject().put("schema","no5.origin-clock-proof/1").put("nonce",query["nonce"])
                    .put("serverSessionId",request.serverSessionId).put("generation",request.generation)
                    .put("displayGeneration",request.displayGeneration).put("pingId",request.pingId)
                    .put("timeUtcNs",request.originalEpochMs*1_000_000L).put("originClockUpperEpochMs",100050L).put("uncertaintyMs",1).toString()
            }
            override fun close()=Unit
        }
        var proof:SonarOriginClockProof?=null
        val before=SystemClock.elapsedRealtimeNanos()
        val client=OriginClockProofClient(http){it==request}
        try {
            assertTrue(client.request(request){proof=it});val after=SystemClock.elapsedRealtimeNanos()
            client.pump();client.tick();val result=checkNotNull(proof)
            assertTrue(result.requestStartedNanos in before..after)
            assertNotNull(result.upperAgeMs(SystemClock.elapsedRealtimeNanos()))
            assertEquals(2000L,result.upperAgeMs(result.requestStartedNanos+1_950_000_000L))
            assertNull(result.upperAgeMs(result.requestStartedNanos+1_950_000_001L))
            val root=File(context.filesDir,"sonar-private-proof").apply{mkdirs()}
            File(root,"origin-elapsed-native1.json").writeText(JSONObject().put("passed",true).put("clock","SystemClock.elapsedRealtimeNanos")
                .put("native_enqueue_bracket_verified",true).put("hard_age_limit_ms",2000).put("sleep_NOT_INJECTED",true).put("synthetic_transport",true).put("qualification_seconds",0).toString(2))
        }finally{client.close()}
    }
}
