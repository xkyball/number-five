package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*
import java.io.IOException

internal fun soundFixture()=AudioSoundState(true,true,"a".repeat(64),"source",3,7,11,
    SoundPreset("stereo","My sound","stereo_4_1",listOf(SoundEqPoint("point",1000.0))),SoundCalibration(),capabilities=SoundCapabilities(true,true,true,true))
internal fun soundWire(state:AudioSoundState=soundFixture())=JSONObject().put("version",2).put("kind","audio-sound").put("available",state.available).put("writable",state.writable).put("configGeneration",state.configGeneration).put("sourceGeneration",state.sourceGeneration).put("presetRevision",state.presetRevision).put("calibrationRevision",state.calibrationRevision).put("configurationRevision",state.configurationRevision).put("preset",AudioSoundCodec.preset(requireNotNull(state.preset))).put("calibration",AudioSoundCodec.calibration(requireNotNull(state.calibration))).put("headroomDb",state.headroomDb).put("eqPolicy",JSONObject().put("minReserveDb",6).put("maxReserveDb",18).put("defaultReserveDb",12).put("positiveBandsMustNotOverlap",true)).put("preview",JSONObject.NULL).put("capabilities",JSONObject().put("parametricEq",true).put("room",true).put("speakerAlignment",true).put("spectrum",true))
private class SoundScheduler:PiPollScheduler {lateinit var pump:()->Unit;override fun schedule(intervalMs:Long,poll:()->Unit):SourceSubscription {pump=poll;return SourceSubscription{}}}
private class SoundTransport:PiHttpTransport{
    var gets=0;var posts=0;var failPost=false;var sound=soundWire();var last:JSONObject?=null
    override fun get(path:String):String {gets++;return when(path){"/v1/audio"->wireFixture("audio");"/v1/audio/sound"->sound.toString();else->receipt("APPLIED")}}
    override fun post(path:String,body:String):String {posts++;last=JSONObject(body);if(failPost)throw IOException("Uncertain delivery");return receipt("ACCEPTED")}
    private fun receipt(status:String)=JSONObject().put("version",1).put("kind","audio-command-result").put("commandId","sound-${posts}").put("requestId",last!!.getString("requestId")).put("sessionId",last!!.getString("sessionId")).put("serverSessionId",last!!.getString("serverSessionId")).put("status",status).put("verification","AUDIO_BLOCK_READBACK").toString()
    override fun close()=Unit
}
class AudioSoundNetworkTest {
    @Test fun verifiedReceiptAvoidsRedundantReadsAndRenewalPrecedesPeriodicRead(){
        var time=10000L;var request:JSONObject?=null;val calls=mutableListOf<String>()
        val preview=soundFixture().copy(preview=SoundPreview("preview",15000,true,false))
        val transport=object:PiHttpTransport{
            override fun get(path:String):String{calls+=path;return if(path=="/v1/audio")wireFixture("audio")else soundWire().toString()}
            override fun post(path:String,body:String):String {
                calls+=path;request=JSONObject(body)
                return JSONObject().put("version",1).put("kind","audio-command-result").put("commandId","command")
                    .put("requestId",request!!.getString("requestId")).put("sessionId",request!!.getString("sessionId"))
                    .put("serverSessionId",request!!.getString("serverSessionId")).put("status","APPLIED").put("verification","AUDIO_BLOCK_READBACK")
                    .put("state",JSONObject(wireFixture("audio")))
                    .put("soundReadback",JSONObject().put("schema","no5.sound-command-readback/1").put("ageMs",0).put("remainingLeaseMs",5000)
                        .put("state",soundWire(preview).put("preview",JSONObject().put("id","preview").put("expiresAtEpochMs",time+5000).put("presetDirty",true).put("calibrationDirty",false)))).toString()
            }
            override fun close(){}
        }
        val scheduler=SoundScheduler();val port=NetworkAudioSound(identity,transport,scheduler,now={time},readExecutor=java.util.concurrent.Executor{it.run()})
        port.setEditorVisible(true,false);scheduler.pump();calls.clear()
        port.submit(AudioSoundCommand.Preview("preview",SoundScope.PRESET,preset=preview.preset));scheduler.pump()
        assertEquals(listOf("/v1/audio/commands"),calls);assertEquals("APPLIED",port.results.value!!.status)
        time+=1001;calls.clear();scheduler.pump()
        assertEquals(listOf("/v1/audio/commands"),calls);assertEquals("soundKeepalive",request!!.getJSONObject("command").getString("type"))
        port.close()
    }
    @Test fun staleOrInvalidReadbackFallsBackWithoutReplayingTheWrite(){
        for(failure in listOf("age","lease","source","config","revision","server","malformed","delay","verification","kind","control_revision")){
            var time=10000L;var request:JSONObject?=null;var gets=0;var posts=0
            val transport=object:PiHttpTransport{
                override fun get(path:String):String{gets++;return if(path=="/v1/audio")wireFixture("audio")else soundWire().toString()}
                override fun post(path:String,body:String):String{
                    posts++;request=JSONObject(body);if(failure=="delay")time+=5001
                    val next=soundWire().apply{when(failure){"source"->put("sourceGeneration","foreign");"config"->put("configGeneration","foreign");"revision"->put("presetRevision",0)}}
                    val meta=JSONObject().put("schema",if(failure=="malformed")"wrong"else"no5.sound-command-readback/1")
                        .put("ageMs",if(failure=="age")2000 else 0).put("remainingLeaseMs",JSONObject.NULL).put("state",next)
                    if(failure=="lease") {meta.put("remainingLeaseMs",10);next.put("preview",JSONObject().put("id","preview").put("expiresAtEpochMs",10001).put("presetDirty",true).put("calibrationDirty",false))}
                    return JSONObject().put("version",1).put("kind","audio-command-result").put("commandId","command")
                        .put("requestId",request!!.getString("requestId")).put("sessionId",request!!.getString("sessionId"))
                        .put("serverSessionId",request!!.getString("serverSessionId")).put("status","APPLIED").put("verification",if(failure=="verification")"NOT_VERIFIED"else"AUDIO_BLOCK_READBACK")
                        .put("state",JSONObject(wireFixture("audio")).apply{when(failure){"server"->put("serverSessionId","foreign");"kind"->put("kind","foreign");"control_revision"->put("revision",.5)}}).put("soundReadback",meta).toString()
                }
                override fun close(){}
            }
            val scheduler=SoundScheduler();val port=NetworkAudioSound(identity,transport,scheduler,now={time},readExecutor=java.util.concurrent.Executor{it.run()})
            port.setEditorVisible(true,false);scheduler.pump();gets=0
            port.submit(if(failure=="verification")AudioSoundCommand.Keepalive("preview")else AudioSoundCommand.Preview("preview",SoundScope.PRESET,preset=soundFixture().preset));scheduler.pump()
            assertEquals(2,gets,failure);assertEquals(1,posts,failure);assertEquals("source",port.state.value.sourceGeneration);port.close()
        }
    }
    @Test fun thumbnailHashesKeepExactlyTheSameLowercaseBytesWithoutPerByteFormatting(){
        for(group in 0..7){
            val digest=ByteArray(32){(group*32+it).toByte()};val wire=soundWire()
            wire.getJSONObject("capabilities").put("presetLibrary",true).put("presetManagement",true)
            wire.put("presetCatalog",org.json.JSONArray().put(JSONObject().put("id","stereo").put("name","My sound").put("routingTemplate","stereo_4_1")))
            wire.put("presetPreviews",JSONObject().put("stereo",JSONObject().put("sha256b64",java.util.Base64.getEncoder().encodeToString(digest))
                .put("db4i8",java.util.Base64.getEncoder().encodeToString(ByteArray(48))).put("eqEnabled",false).put("reserveDb",12)))
            assertEquals(digest.joinToString(""){"%02x".format(it.toInt() and 255)},AudioSoundCodec.state(wire.toString()).presetPreviews.getValue("stereo").modelSha256)
        }
    }
    @Test fun pointerWorkWakesOnlyTheExistingExecutorAndStillCoalescesBeforeIo(){
        class WakeScheduler:PiPollScheduler {
            lateinit var pump:()->Unit;var wakes=0
            override fun schedule(intervalMs:Long,poll:()->Unit):SourceSubscription {pump=poll;return object:WakeablePollSubscription{
                override fun requestNow():Boolean{wakes++;return true};override fun close(){}
            }}
        }
        val s=WakeScheduler();val t=SoundTransport();val p=NetworkAudioSound(identity,t,s,now={10000},readExecutor=java.util.concurrent.Executor{it.run()})
        p.setEditorVisible(true,false);s.pump();val reads=t.gets;val initialWakes=s.wakes
        repeat(50){p.submit(AudioSoundCommand.Preview("preview",SoundScope.PRESET,preset=soundFixture().preset!!.copy(room=SoundRoom(it/100.0,.5))))}
        assertEquals(initialWakes+50,s.wakes);assertEquals(0,t.posts);assertEquals(reads,t.gets)
        s.pump();assertEquals(1,t.posts);assertEquals(.49,t.last!!.getJSONObject("command").getJSONObject("model").getJSONObject("room").getDouble("amount"));p.close()
    }
    private val identity=SourceIdentity("sound-test",SourceMode.PI,"SOUND")
    @Test fun typedRoundtripPreservesUnmeasuredPositionsAndOnlyOwnedFields(){val decoded=AudioSoundCodec.state(soundWire().toString());assertEquals(soundFixture(),decoded)
        val command=AudioSoundCommand.Preview("preview",SoundScope.PRESET,preset=decoded.preset)
        val wire=JSONObject(AudioSoundCodec.command("session","session:1","server",55,decoded,command));assertEquals("soundPreview",wire.getJSONObject("command").getString("type"));assertEquals(3,wire.getJSONObject("command").getLong("expectedPresetRevision"));assertNull(decoded.calibration!!.listenerM)
    }
    @Test fun soundVersionTwoRequiresExplicitEqPolicyEnableAndReserveWhileLocalOldDraftsRemainOff(){
        val base=soundFixture();val on=base.copy(preset=base.preset!!.copy(eqEnabled=true,reserveDb=18.0),headroomDb=18.0)
        val decoded=AudioSoundCodec.state(soundWire(on).toString());assertTrue(decoded.preset!!.eqEnabled);assertEquals(18.0,decoded.preset!!.reserveDb)
        assertEquals(SoundEqPolicy(),decoded.eqPolicy)
        assertFails{AudioSoundCodec.state(soundWire(on).put("version",1).toString())}
        val missingPolicy=soundWire(on);missingPolicy.remove("eqPolicy");assertFails{AudioSoundCodec.state(missingPolicy.toString())}
        val oldPreset=AudioSoundCodec.preset(on.preset!!);oldPreset.remove("eqEnabled");oldPreset.remove("reserveDb")
        assertFails{AudioSoundCodec.preset(oldPreset)}
        val migrated=AudioSoundCodec.presetDraft(oldPreset);assertFalse(migrated.eqEnabled);assertEquals(12.0,migrated.reserveDb);assertEquals(on.preset!!.eqPoints,migrated.eqPoints)
        for(field in listOf("eqEnabled","reserveDb")){val missing=AudioSoundCodec.preset(on.preset!!);missing.remove(field);assertFails{AudioSoundCodec.preset(missing)}}
        assertFails{AudioSoundCodec.state(soundWire(on).put("headroomDb",0).toString())}
        assertFails{AudioSoundCodec.state(soundWire(base).put("headroomDb",12).toString())}
        val malformed=AudioSoundCodec.preset(on.preset!!).put("eqEnabled","true");assertFails{AudioSoundCodec.preset(malformed)}
    }
    @Test fun absolutePreviewCoalescesBeforeAnyIoAndDiscreteWorkIsBounded(){
        val t=SoundTransport();val s=SoundScheduler();var time=10000L;val p=NetworkAudioSound(identity,t,s,now={time},readExecutor=java.util.concurrent.Executor{it.run()});p.setEditorVisible(true,false);s.pump();val gets=t.gets
        val results=mutableListOf<AudioSoundResult>();p.results.subscribe{it?.let(results::add)}
        repeat(100){p.submit(AudioSoundCommand.Preview("preview",SoundScope.PRESET,preset=soundFixture().preset!!.copy(room=SoundRoom(it/100.0,.5))))}
        assertEquals(gets,t.gets);assertEquals(0,t.posts);assertEquals(99,results.count{it.status=="SUPERSEDED"})
        s.pump();assertEquals(1,t.posts);assertEquals(.99,t.last!!.getJSONObject("command").getJSONObject("model").getJSONObject("room").getDouble("amount"))
        repeat(64){assertTrue(p.submit(AudioSoundCommand.Keepalive("preview"))>0)};assertEquals(0,p.submit(AudioSoundCommand.Keepalive("preview")))
        p.close()
    }
    @Test fun uncertainPostIsNeverRepeatedAndLateResultsCannotReplayIt(){
        val t=SoundTransport();val s=SoundScheduler();val p=NetworkAudioSound(identity,t,s,now={10000},readExecutor=java.util.concurrent.Executor{it.run()});p.setEditorVisible(true,false);s.pump();t.failPost=true
        val id=p.submit(AudioSoundCommand.Preview("preview",SoundScope.PRESET,preset=soundFixture().preset))
        s.pump();assertEquals(1,t.posts);assertEquals(AudioSoundResult(id,"UNKNOWN","COMPLETION_UNKNOWN"),p.results.value)
        repeat(10){s.pump()};assertEquals(1,t.posts);p.close()
    }
    @Test fun publicSoundRejectionReasonSurvivesBothImmediateAndPolledResults(){
        for(reason in listOf("EQ_COMBINED_GAIN_LIMIT","EQ_POSITIVE_BANDS_OVERLAP"))for(immediate in listOf(true,false)){
            var request:JSONObject?=null
            val transport=object:PiHttpTransport{
                fun receipt(status:String)=JSONObject().put("version",1).put("kind","audio-command-result").put("commandId","sound-rejected")
                    .put("requestId",request!!.getString("requestId")).put("sessionId",request!!.getString("sessionId"))
                    .put("serverSessionId",request!!.getString("serverSessionId")).put("status",status).put("reason",reason).toString()
                override fun get(path:String)=when(path){"/v1/audio"->wireFixture("audio");"/v1/audio/sound"->soundWire().toString();else->receipt("REJECTED")}
                override fun post(path:String,body:String):String {request=JSONObject(body);return receipt(if(immediate)"REJECTED"else"ACCEPTED")}
                override fun close()=Unit
            }
            val scheduler=SoundScheduler();val port=NetworkAudioSound(identity,transport,scheduler,now={10000},readExecutor=java.util.concurrent.Executor{it.run()})
            port.setEditorVisible(true,false);scheduler.pump()
            val id=port.submit(AudioSoundCommand.Preview("preview",SoundScope.PRESET,preset=soundFixture().preset))
            scheduler.pump();if(!immediate)scheduler.pump()
            assertEquals(AudioSoundResult(id,"REJECTED",reason),port.results.value)
            port.close()
        }
    }
    @Test fun readFailuresExposeOnlyFixedStagesAndRetryWithoutLosingTheSource(){
        var failure="";val validSound=soundWire().toString()
        val transport=object:PiHttpTransport{
            override fun get(path:String):String {
                if(failure=="http")throw PiHttpStatusException(401)
                if(failure=="transport")throw IOException("Do not publish exception text or private endpoints")
                return if(path=="/v1/audio")if(failure=="audio")"{}"else wireFixture("audio")
                else if(failure=="sound")"{}"else if(failure=="backend")"""{"version":2,"kind":"audio-sound","available":false,"reason":"private backend detail"}"""else validSound
            }
            override fun close()=Unit
        }
        val scheduler=SoundScheduler();val p=NetworkAudioSound(identity,transport,scheduler,now={10000},readExecutor=java.util.concurrent.Executor{it.run()})
        p.setEditorVisible(true,false)
        for((trigger,reason)in listOf("http" to "HTTP_401","transport" to "TRANSPORT","audio" to "AUDIO_DECODE","sound" to "SOUND_DECODE","backend" to "BACKEND_UNAVAILABLE")){
            failure=trigger;p.refresh();scheduler.pump()
            assertFalse(p.state.value.available);assertFalse(p.state.value.writable);assertEquals(reason,p.state.value.reason)
            failure="";p.refresh();scheduler.pump();assertTrue(p.state.value.available);assertNull(p.state.value.reason)
        }
        p.close()
    }
    @Test fun immediateInitialSchedulerRunsOnlyAfterEveryPumpFieldHasBeenInitialized(){
        var next:(()->Unit)?=null;var initialCalls=0
        val scheduler=PiPollScheduler{_,poll->initialCalls++;poll();next=poll;SourceSubscription{}}
        val transport=SoundTransport();val p=NetworkAudioSound(identity,transport,scheduler,now={10000},readExecutor=java.util.concurrent.Executor{it.run()})
        assertEquals(1,initialCalls);assertEquals(0,transport.gets);assertEquals("NOT_STARTED",p.state.value.reason)
        p.setEditorVisible(true,false);next!!()
        assertTrue(p.state.value.available);assertEquals(2,transport.gets)
        p.close()
    }
    @Test fun originalCaptureClockSurvivesJsonWithoutDoubleEpochConversion(){
        val frame="""{"schema":"no5.audio-spectrum/1","sequence":1,"sourceGeneration":"source-1","samplePosition":8192,"sampleTimeNs":10000000000000000,"timebase":"CAPTURE_MONOTONIC_NS","sampleRateHz":48000,"fftSize":8192,"window":"HANN","tap":"POST_EQ_PRE_ROOM","frequenciesHz":[20,1000,20000],"binsDbfs":[-80,-12,-90],"peaksDbfs":[-6,-6],"overRangeSamples":0}"""
        assertEquals(10000000000000000L,AudioSoundCodec.spectrum(frame)!!.sampleTimeNs)
        assertEquals("config-1",AudioSoundCodec.state(soundWire().put("configGeneration","config-1").put("headroomDb",0.0).toString()).configGeneration)
        assertFailsWith<PiProtocolException>{AudioSoundCodec.spectrum(frame.replace("-80","25"))}
    }
    @Test fun malformedSpectrumAndFutureExtensionCannotMasqueradeAsRealSamples(){
        assertNull(AudioSoundCodec.spectrum("""{"version":1,"kind":"audio-spectrum-status","available":false,"reason":"WAITING"}"""))
        assertFailsWith<PiProtocolException>{AudioSoundCodec.spectrum("""{"schema":"no5.audio-spectrum/1","window":"HANN","tap":"POST_ROOM"}""")}
        assertFailsWith<PiProtocolException>{AudioSoundCodec.state(soundWire().put("presetRevision",-1).toString())}
    }
    private fun projectionWire(experimental:Boolean=false,normal:SoundPreset?=null)=soundWire().apply{
        getJSONObject("capabilities").put("eqProjection",true).put("experimentalEq",true)
        put("eqEditing",JSONObject().put("schema","no5.eq-editing/1").put("previewProjection",true).put("maxExperimentalBoostDb",3)
            .put("experimental",experimental).put("constrained",false).put("normalPreset",normal?.let(AudioSoundCodec::preset)?:JSONObject.NULL))
    }
    @Test fun experimentalReadbackRejectsForeignFallbackAndChangedGeometryOrCuts(){
        val model=soundFixture().preset!!
        assertNotNull(AudioSoundCodec.state(projectionWire(true,model).toString()).eqEditing)
        for(bad in listOf(model.copy(id="foreign"),model.copy(room=SoundRoom(.2,.5)),model.copy(eqPoints=listOf(model.eqPoints[0].copy(frequencyHz=2000.0))),model.copy(eqPoints=listOf(model.eqPoints[0].copy(gainDb=-1.0))))){
            assertFails{AudioSoundCodec.state(projectionWire(true,bad).toString())}
        }
    }
    @Test fun experimentAdmissionAndQueuedPostRequireTheLiveVisiblePermission(){
        val t=SoundTransport();t.sound=projectionWire();val s=SoundScheduler();val p=NetworkAudioSound(identity,t,s,now={10000},readExecutor=java.util.concurrent.Executor{it.run()})
        p.setEditorVisible(true,false);s.pump()
        val request=AudioSoundCommand.Preview("preview",SoundScope.PRESET,preset=soundFixture().preset,eqEditing=SoundEqEditIntent(true))
        assertEquals(0,p.submit(request));p.setExperimentalAllowed(true);val id=p.submit(request);assertTrue(id>0)
        p.setExperimentalAllowed(false);s.pump();assertEquals(0,t.posts);assertEquals("SUPERSEDED",p.results.value!!.status);p.close()
    }
    @Test fun gatewayAppliedWithoutTheActualExperimentalModeIsUnknown(){
        val t=SoundTransport();t.sound=projectionWire();val s=SoundScheduler();val p=NetworkAudioSound(identity,t,s,now={10000},readExecutor=java.util.concurrent.Executor{it.run()})
        p.setEditorVisible(true,false);s.pump();p.setExperimentalAllowed(true)
        p.submit(AudioSoundCommand.Preview("preview",SoundScope.PRESET,preset=soundFixture().preset,eqEditing=SoundEqEditIntent(true)))
        s.pump();s.pump();assertEquals("UNKNOWN",p.results.value!!.status);assertFalse(p.state.value.eqEditing!!.experimental);p.close()
    }

    @Test fun sixteenLongIdentityPointsFitTheExistingSoundHttpBodyBoundWithEditMetadata(){
        val points=List(16){SoundEqPoint(it.toString().padStart(2,'0')+"a".repeat(62),12345.123456789123,20.12345678912345,1.1234567891234567)}
        val model=SoundPreset("b".repeat(64),"ü".repeat(64),"immersive_4_1",points,SoundRoom(.12345678912345678,.12345678912345678),true,18.0)
        val base=soundFixture().copy(configGeneration="c".repeat(128))
        val body=AudioSoundCodec.command("s".repeat(36),"s".repeat(36)+":9223372036854775807","g".repeat(64),Long.MAX_VALUE,base,
            AudioSoundCommand.Preview("p".repeat(36),SoundScope.PRESET,preset=model,eqEditing=SoundEqEditIntent(true,points[0])))
        assertTrue(body.toByteArray().size in 4097..16384)
        assertEquals(16,JSONObject(body).getJSONObject("command").getJSONObject("model").getJSONArray("eqPoints").length())
    }

    @Test fun queuedExperimentalRenewalCannotOutlivePermissionOrSource(){
        val t=SoundTransport();t.sound=projectionWire(true,soundFixture().preset);val s=SoundScheduler();val p=NetworkAudioSound(identity,t,s,now={10000},readExecutor=java.util.concurrent.Executor{it.run()})
        p.setEditorVisible(true,false);s.pump();p.setExperimentalAllowed(true)
        val id=p.submit(AudioSoundCommand.Keepalive("preview",true));assertTrue(id>0)
        p.setExperimentalAllowed(false);s.pump();assertEquals(0,t.posts);assertEquals("SUPERSEDED",p.results.value!!.status)
        p.setExperimentalAllowed(true);t.sound.put("sourceGeneration","replacement");p.refresh();s.pump()
        assertEquals(0,p.submit(AudioSoundCommand.Keepalive("preview",true)));p.close()
    }

}
