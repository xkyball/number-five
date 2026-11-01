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
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Android OkHttp/socket cancellation/reconnect of ONLY the private loopback subscription. */
@RunWith(AndroidJUnit4::class)
class SonarReadReconnectDeviceTest {
    @Test fun actualOwnReadSocketClosesAndReconnectsWithoutHttpOrWriteReplay() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName=="com.no5.helm.sonarfixture")
        val report=JSONObject().put("qualifying_seconds",0).put("scope","Actual Android OkHttp/NetworkPiSonarSource against private simulated loopback WSS-protocol server; no physical source/production endpoint")
        val listener=ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"));listener.soTimeout=5000
        val accepted=AtomicInteger();val oldClosed=AtomicInteger();val fail=AtomicReference<Throwable?>();val owned=java.util.concurrent.CopyOnWriteArrayList<Socket>()
        fun state(revision:Int)=JSONObject().put("version",1).put("kind","sonar.state").put("source","PRIVATE_ANDROID_LOOPBACK")
            .put("sourceKind","SIMULATED").put("simulated",true).put("serverSessionId","private-server").put("generation",1)
            .put("format",JSONObject().put("bins",1024).put("sampleType","UINT16").put("byteOrder","LITTLE_ENDIAN"))
            .put("calibration",JSONObject().put("revision",0).put("valid",true).put("samplePeriodUs",20.0).put("soundSpeedMps",1500.0).put("depthReference","TRANSDUCER").put("transducerOffsetM",JSONObject.NULL).put("keelOffsetM",JSONObject.NULL))
            .put("revision",revision).put("publishedAtEpochMs",System.currentTimeMillis()).put("running",true).put("status","LIVE")
            .put("capabilities",JSONObject().put("canStart",true).put("canStop",true).put("bottomDetection",true)).put("cadenceHz",20.0).toString().toByteArray()
        val server=Thread({try{repeat(2){iteration->
            val socket=listener.accept();owned+=socket;socket.soTimeout=5000
            val input=socket.getInputStream();val header=java.io.ByteArrayOutputStream();var previous=0;var tail=0
            while(header.size()<8192){val value=input.read();check(value>=0);header.write(value);tail=(tail shl 8)or value;if(header.size()>=4&&tail==0x0d0a0d0a)break;previous=value}
            val text=header.toString("US-ASCII");check(text.startsWith("GET /v1/sonar/stream HTTP/1.1\r\n"))
            val key=text.lineSequence().first{it.startsWith("Sec-WebSocket-Key:",true)}.substringAfter(':').trim()
            val accept=android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),android.util.Base64.NO_WRAP)
            val output=socket.getOutputStream();output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: no5.sonar.v1\r\n\r\n").toByteArray(Charsets.US_ASCII))
            val body=state(iteration);check(body.size<65536);output.write(0x81);output.write(126);output.write(body.size ushr 8);output.write(body.size and 255);output.write(body);output.flush();accepted.incrementAndGet()
            if(iteration==0){assertEquals("only cancelled owned first socket reaches EOF",-1,input.read());oldClosed.incrementAndGet();socket.close()}
        }}catch(error:Throwable){fail.set(error)}},"private-sonar-loopback-server").apply{start()}
        var httpReads=0;var httpWrites=0
        val http=object:PiHttpTransport{override fun get(path:String):String{httpReads++;error("unexpected HTTP read")};override fun post(path:String,body:String):String{httpWrites++;error("unexpected HTTP write")};override fun close(){}}
        val source=NetworkPiSonarSource(SourceIdentity("private-android-rx",SourceMode.PI,"PRIVATE_LOOPBACK"),http,OkHttpSonarStreamTransport("http://127.0.0.1:${listener.localPort}"))
        fun await(message:String,limit:Long=2000,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+limit;while(!condition()&&SystemClock.elapsedRealtime()<end){source.tick();fail.get()?.let{throw AssertionError(it)};Thread.sleep(10)};source.tick();assertTrue(message,condition())}
        try {
            await("first actual private stream state"){source.status.value.connected&&accepted.get()==1}
            assertFalse("healthy subscription never reconnects",source.reconnectReadTransport())
            await("actual monotone receiver silence >=1s",1500){source.readReceiveSilenceNanos()>=1_000_000_000L}
            assertTrue(source.reconnectReadTransport());assertFalse("duplicate queued reset denied",source.reconnectReadTransport())
            source.tick();assertFalse(source.status.value.connected)
            await("only owned socket closed and new stream accepted"){oldClosed.get()==1&&accepted.get()==2&&source.status.value.connected}
            assertEquals(0,httpReads);assertEquals(0,httpWrites);assertTrue(source.readReceiveSilenceNanos() in 0 until 1_000_000_000L)
            report.put("first_owned_socket_eof",true).put("actual_connections",accepted.get()).put("http_reads",httpReads).put("write_calls",httpWrites).put("healthy_reset_denied",true).put("duplicate_reset_denied",true).put("passed",true)
        }finally{source.close();listener.close();owned.forEach{it.close()};server.join(2000);assertFalse("own loopback server terminated",server.isAlive);val root=File(context.filesDir,"sonar-private-proof");root.mkdirs();File(root,"read-reconnect-native1.json").writeText(report.toString(2))}
    }
}
