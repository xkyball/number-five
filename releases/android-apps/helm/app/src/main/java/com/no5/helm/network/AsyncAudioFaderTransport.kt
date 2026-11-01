package com.no5.helm.network

import com.no5.domain.SourceSubscription
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Keeps the native gesture contract; wire writes use the existing asynchronous command actor. */
internal class AsyncAudioFaderTransport(private val http: PiHttpTransport) : AudioFaderStreamTransport {
    override val label = "ASYNC COMMANDS"
    private val lock = Any()
    private val pending = arrayOfNulls<String>(64)
    private var head = 0; private var size = 0
    private var listener: AudioFaderStreamTransport.Listener? = null
    private var epoch = 0L
    private var flight: String? = null
    private var gatewayId: String? = null
    private var deadline = 0L
    private var nextState = 0L
    private var closed = false
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"no5-audio-goals").apply { isDaemon=true } }
    private val job = worker.scheduleWithFixedDelay({ pump() },0,50,TimeUnit.MILLISECONDS)
    override fun connect(listener: AudioFaderStreamTransport.Listener): SourceSubscription = synchronized(lock) {
        check(!closed && this.listener==null);this.listener=listener;epoch++
        val captured=epoch
        SourceSubscription { synchronized(lock) { if(epoch==captured){epoch++;this.listener=null;flight=null;gatewayId=null;pending.fill(null);head=0;size=0} } }
    }
    override fun send(value:String):Boolean = synchronized(lock) {
        if(closed||listener==null||size==pending.size||value.length>4096)return false
        pending[(head+size)%pending.size]=value;size++;true
    }
    private fun pump() {
        val captured=synchronized(lock) {
            if(closed||listener==null)return
            if(flight==null&&size>0){flight=pending[head];pending[head]=null;head=(head+1)%pending.size;size--;gatewayId=null;deadline=System.currentTimeMillis()+5000}
            Triple(epoch,flight,gatewayId)
        }
        try {
            if(captured.second!=null) {
                val request=JSONObject(captured.second!!)
                val payload=captured.third?.let{http.get("/v1/audio/commands/$it")}?:http.post("/v1/audio/commands",captured.second!!)
                val (id,ack)=audioCommandResult(payload,request.getString("requestId"),request.getString("serverSessionId"))
                if(captured.third!=null&&captured.third!=id)throw PiProtocolException("Audio result identity changed")
                val delivery=synchronized(lock) {
                    if(closed||epoch!=captured.first)return
                    gatewayId=id
                    if(ack!=null) {
                        val state=JSONObject(payload).getJSONObject("state")
                        val response=JSONObject().put("version",1).put("kind","audio-ack").put("sessionId",ack.sessionId)
                            .put("requestId",ack.requestId).put("serverSessionId",ack.snapshot.serverSessionId)
                            .put("revision",ack.snapshot.state.revision).put("source",ack.snapshot.state.source.quality.source)
                            .put("sourceKind",state.getString("sourceKind"))
                            .put("simulated",ack.snapshot.state.source.quality.simulated).put("status",ack.status.name)
                            .put("message",ack.message).put("state",state).toString()
                        flight=null;gatewayId=null;listener?.let { it to response }
                    } else {
                        if(System.currentTimeMillis()>=deadline)throw PiProtocolException("Audio completion unknown")
                        null
                    }
                }
                delivery?.let { (receiver,value)->receiver.text(value) }
            } else if(System.currentTimeMillis()>=nextState) {
                nextState=System.currentTimeMillis()+250
                val state=http.get("/v1/audio")
                AudioV1Decoder.state(state)
                val receiver=synchronized(lock){if(!closed&&epoch==captured.first)listener else null}
                receiver?.text(state)
            }
        }catch(_:Exception){
            val retired=synchronized(lock){if(epoch!=captured.first)return;val old=listener;listener=null;epoch++;flight=null;gatewayId=null;pending.fill(null);size=0;head=0;old}
            retired?.failure()
        }
    }
    override fun close(){synchronized(lock){if(closed)return;closed=true;epoch++;listener=null;pending.fill(null);flight=null};job.cancel(false);worker.shutdownNow();http.close()}
}
