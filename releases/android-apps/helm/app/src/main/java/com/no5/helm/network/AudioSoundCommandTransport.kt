package com.no5.helm.network

import com.no5.domain.SourceSubscription
import com.no5.helm.security.GatewaySecurityProfile
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded duplex transport only. The SOUND owner validates admission and every readback. */
internal interface AudioSoundCommandTransport:AutoCloseable {
    fun connect(text:(String)->Unit,failure:()->Unit):SourceSubscription
    fun send(value:String):Boolean
}

internal class OkHttpAudioSoundCommandTransport(endpoint:String,private val security:GatewaySecurityProfile,
    resolver:GatewayEndpointResolver=GatewayEndpointResolver.NONE):AudioSoundCommandTransport {
    private val url=endpoint.trimEnd('/')+"/v1/audio/sound/stream"
    private val client=OkHttpClient.Builder().apply {
        security.configureOkHttp{factory,manager->sslSocketFactory(factory,manager)}
        dns(GatewayOkHttpDns(endpoint,resolver,1500))
    }.connectTimeout(1500,TimeUnit.MILLISECONDS).writeTimeout(1500,TimeUnit.MILLISECONDS)
        .readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(5,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val lock=Any();private var socket:WebSocket?=null;private var opened=false;private var closed=false
    override fun connect(text:(String)->Unit,failure:()->Unit):SourceSubscription=synchronized(lock){
        check(!closed&&socket==null)
        val deliverText=text
        val cancelled=AtomicBoolean(false)
        fun fail(){if(cancelled.compareAndSet(false,true)){
            synchronized(lock){socket?.cancel();socket=null;opened=false};failure()
        }}
        val request=Request.Builder().url(url).header("Sec-WebSocket-Protocol",PROTOCOL).header("Cache-Control","no-cache, no-store")
        security.configureHttps(url,configureTls={},setHeader=request::header)
        socket=client.newWebSocket(request.build(),object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){
                if(response.header("Sec-WebSocket-Protocol")!=PROTOCOL){soundDiagnostic("WSS_BAD_PROTOCOL");fail()}
                else synchronized(lock){if(!cancelled.get())opened=true}
            }
            override fun onMessage(webSocket:WebSocket,text:String){if(!cancelled.get()){
                if(text.toByteArray(Charsets.UTF_8).size>131072){soundDiagnostic("WSS_MESSAGE_TOO_LARGE");fail()}else deliverText(text)
            }}
            override fun onMessage(webSocket:WebSocket,bytes:ByteString){soundDiagnostic("WSS_BINARY_MESSAGE");fail()}
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){
                if(!cancelled.get())soundDiagnostic("WSS_FAILURE_${AudioSoundWssDiagnostics.failureClass(t)}_HTTP_${response?.code?:0}");fail()
            }
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){if(!cancelled.get())soundDiagnostic("WSS_CLOSE_BEGIN_${code}_${AudioSoundWssDiagnostics.closeClass(code,reason)}");fail()}
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String){if(!cancelled.get())soundDiagnostic("WSS_CLOSE_COMPLETE_${code}_${AudioSoundWssDiagnostics.closeClass(code,reason)}");fail()}
        })
        SourceSubscription{cancelled.set(true);synchronized(lock){socket?.cancel();socket=null;opened=false}}
    }
    override fun send(value:String):Boolean=synchronized(lock){
        !closed&&opened&&value.toByteArray(Charsets.UTF_8).size<=16384&&
            (socket?.queueSize()?:Long.MAX_VALUE)<=65536&&socket?.send(value)==true
    }
    override fun close(){synchronized(lock){if(closed)return;closed=true;socket?.cancel();socket=null;opened=false}
        client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
    companion object{const val PROTOCOL="no5.audio.sound.v1"}
}

/** Only fixed categories are logged; never emit peer text, URLs, credentials or command bodies. */
internal object AudioSoundWssDiagnostics {
    fun closeClass(code:Int,reason:String):String=when {
        code==1008&&reason=="sound stream authority ended"->"AUTHORITY_ENDED"
        code==1008&&reason=="sound stream unavailable"->"STREAM_UNAVAILABLE"
        code==1008&&reason=="sound request unconfirmed; reconcile without replay"->"REQUEST_UNCONFIRMED"
        code==1008->"POLICY_CLOSE_OTHER"
        code==1000->"NORMAL"
        else->"PEER_CLOSE"
    }
    fun failureClass(error:Throwable):String=when(error){
        is javax.net.ssl.SSLException->"TLS"
        is java.net.SocketTimeoutException->"TIMEOUT"
        is java.net.UnknownHostException->"DNS"
        is java.io.IOException->"IO"
        else->"OTHER"
    }
}
