package com.no5.helm.network

import com.no5.domain.*
import com.no5.helm.AudioNativeController
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AsyncAudioFaderTransportTest {
    private class Backend : PiHttpTransport {
        val state=JSONObject(wireFixture("audio"))
        val requests=mutableListOf<JSONObject>()
        private val completed=mutableMapOf<String,String>()
        @Synchronized override fun get(path:String):String =
            if(path=="/v1/audio")state.toString() else completed[path.substringAfterLast('/')]?:error("Missing admitted command")
        @Synchronized override fun post(path:String,body:String):String {
            assertEquals("/v1/audio/commands",path)
            val request=JSONObject(body);requests+=request
            assertEquals(state.getLong("revision"),request.getLong("expectedRevision"))
            val command=request.getJSONObject("command");val channel=command.getString("channel");val db=command.getDouble("valueDb")
            state.getJSONObject("dsp").getJSONObject("faders").getJSONObject(channel).put("db",db)
            val controls=state.getJSONObject("dsp").getJSONArray("faderControls")
            for(i in 0 until controls.length())if(controls.getJSONObject(i).getString("id")==channel)controls.getJSONObject(i).put("db",db)
            state.put("revision",state.getLong("revision")+1)
            val receipt=JSONObject().put("version",1).put("kind","audio-command-result").put("commandId","goal${requests.size}")
                .put("sessionId",request.getString("sessionId")).put("serverSessionId",request.getString("serverSessionId"))
                .put("requestId",request.getString("requestId")).put("status","APPLIED").put("state",JSONObject(state.toString()))
            completed[receipt.getString("commandId")]=receipt.toString()
            return JSONObject(receipt.toString()).put("status","ACCEPTED").also{it.remove("state")}.toString()
        }
        override fun close(){}
    }

    @Test fun actualAsyncTransportConfirmsAuxGestureAndKeepsMasterAcrossIntermediateState() {
        val backend=Backend();val jobs=mutableMapOf<Long,()->Unit>()
        var clock=backend.state.getLong("timestampEpochMs")
        val scheduler=PiPollScheduler { interval,operation->jobs[interval]=operation;SourceSubscription{jobs.remove(interval)} }
        val source=NetworkPiAudioSource(SourceIdentity("async-touch",SourceMode.PI,"TEST"),backend,{clock},scheduler,
            streamTransport=AsyncAudioFaderTransport(backend))
        val controller=AudioNativeController(Dispatchers.Unconfined,{clock},automaticTicker=false,automaticVerification=false,source=source)
        fun await(condition:()->Boolean) {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(!condition()&&System.nanoTime()<deadline){clock+=10;jobs[20]?.invoke();Thread.sleep(10)}
            assertTrue(condition(),source.faderStream!!.status)
        }
        try {
            jobs.getValue(20)();await{controller.faderControls.isNotEmpty()&&controller.canDrag(controller.faderControl(AudioChannel.AUX3)!!)}
            val aux=controller.faderControl(AudioChannel.AUX3)!!
            controller.previewLevel(aux,-13.0);jobs.getValue(20)()
            for(db in -14 downTo -17)controller.previewLevel(aux,db.toDouble())
            controller.commitPreview()
            await{controller.lastOutcome?.status==AudioCommandStatus.VERIFIED&&source.state.value.pending==null}
            assertEquals(-17.0,controller.level(AudioChannel.AUX3))
            assertEquals(-24.0,controller.level(AudioChannel.MASTER))
            assertTrue(backend.requests.all{it.getJSONObject("command").getString("channel")=="AUX3"})
            assertTrue(backend.requests.size in 1..3)
            assertFalse(source.faderStream!!.status.contains("confirmed 0"))
        } finally {controller.dispose();source.close()}
    }

    @Test fun callbackWaitingForItsOwnerCannotHoldTransportAdmissionLock() {
        val backend=Backend();val transport=AsyncAudioFaderTransport(backend)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val executor=Executors.newSingleThreadExecutor()
        val connection=transport.connect(object:AudioFaderStreamTransport.Listener {
            override fun text(value:String){entered.countDown();release.await(3,TimeUnit.SECONDS)}
            override fun failure(){}
        })
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val sent=executor.submit<Boolean>{transport.send(AudioV1Decoder.command("lock-test","lock-test:1",
                backend.state.getString("serverSessionId"),backend.state.getLong("revision"),AudioCommand.Fader(AudioChannel.AUX3,-15.0)))}
            assertTrue(sent.get(300,TimeUnit.MILLISECONDS))
        } finally {release.countDown();connection.close();transport.close();executor.shutdownNow()}
    }
}
