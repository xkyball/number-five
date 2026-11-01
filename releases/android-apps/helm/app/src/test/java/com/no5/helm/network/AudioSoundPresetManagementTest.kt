package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.util.Base64
import kotlin.test.*

class AudioSoundPresetManagementTest {
    private fun wire():JSONObject=soundWire().apply {
        getJSONObject("capabilities").put("presetLibrary",true).put("presetManagement",true)
        put("presetCatalog",JSONArray().put(JSONObject().put("id","saved").put("name","Abend").put("routingTemplate","stereo_4_1")))
        val bytes=ByteArray(48){(it-24).toByte()}
        put("presetPreviews",JSONObject().put("saved",JSONObject().put("sha256b64",Base64.getEncoder().encodeToString(ByteArray(32){0xaa.toByte()}))
            .put("db4i8",Base64.getEncoder().encodeToString(bytes)).put("eqEnabled",true).put("reserveDb",12)))
    }
    @Test fun savedThumbnailDecodeIsBoundedTypedAndIndependentFromTheLivePreset(){
        val state=AudioSoundCodec.state(wire().toString())
        assertTrue(state.capabilities.presetManagement)
        val preview=state.presetPreviews.getValue("saved")
        assertEquals(48,preview.gainsDb.size);assertEquals(-6.0,preview.gainsDb.first());assertEquals(5.75,preview.gainsDb.last())
        assertTrue(preview.eqEnabled);assertEquals(12.0,preview.reserveDb);assertEquals("a".repeat(64),preview.modelSha256)
        assertEquals("stereo",state.preset!!.id)
        val old=AudioSoundCodec.state(soundWire().toString())
        assertFalse(old.capabilities.presetManagement);assertTrue(old.presetPreviews.isEmpty())
    }
    @Test fun malformedThumbnailCannotSupplyAFileUrlUnboundedImageOrUnknownPreset(){
        val mutations=listOf<(JSONObject)->Unit>(
            {it.getJSONObject("capabilities").put("presetManagement","true")},
            {it.getJSONObject("capabilities").put("presetLibrary",false)},
            {it.remove("presetPreviews")},
            {it.getJSONObject("presetPreviews").put("unknown",it.getJSONObject("presetPreviews").getJSONObject("saved"))},
            {it.getJSONObject("presetPreviews").getJSONObject("saved").put("db4i8","file:///private")},
            {it.getJSONObject("presetPreviews").getJSONObject("saved").put("db4i8","A".repeat(68))},
            {it.getJSONObject("presetPreviews").getJSONObject("saved").put("db4i8",Base64.getEncoder().encodeToString(ByteArray(48){127}))},
            {it.getJSONObject("presetPreviews").getJSONObject("saved").put("sha256b64","../image")},
            {it.getJSONObject("presetPreviews").getJSONObject("saved").put("eqEnabled",1)},
            {it.getJSONObject("presetPreviews").getJSONObject("saved").put("path","/arbitrary")}
        )
        mutations.forEach{change->val value=wire();change(value);assertFailsWith<PiProtocolException>{AudioSoundCodec.state(value.toString())}}
    }
    @Test fun verifiedRoutingLinksRemainBoundedIdentifiersRatherThanPaths(){
        val good=wire().put("routingProfiles",JSONObject().put("immersive_4_1","verified_profile"))
        assertEquals("verified_profile",AudioSoundCodec.state(good.toString()).routingProfiles.getValue("immersive_4_1").presetId)
        for(bad in listOf(JSONObject().put("immersive_4_1","/arbitrary/path"),JSONObject().put("unknown","id"),
            JSONObject().put("stereo_4_1","same").put("immersive_4_1","same"),JSONObject().put("immersive_4_1",JSONObject().put("path","x")))){
            assertFails{AudioSoundCodec.state(wire().put("routingProfiles",bad).toString())}
        }
    }
    @Test fun deletionUsesPresetRevisionAndIdWithoutAnyRawDspOrPath(){
        val state=AudioSoundCodec.state(wire().toString())
        val encoded=JSONObject(AudioSoundCodec.command("s","s:1","g",55,state,AudioSoundCommand.DeletePreset("edit","saved"))).getJSONObject("command")
        assertEquals("soundDeletePreset",encoded.getString("type"));assertEquals("saved",encoded.getString("presetId"))
        assertEquals(3,encoded.getInt("expectedPresetRevision"));assertEquals(7,encoded.getInt("expectedCalibrationRevision"))
        assertEquals(setOf("type","previewId","scope","presetId","configGeneration","expectedPresetRevision","expectedCalibrationRevision"),encoded.keys().asSequence().toSet())
        assertFails{AudioSoundCommand.DeletePreset("edit","../unsafe")}
    }
    @Test fun deletionRequiresCapabilityAndDurableStateReceiptRatherThanClaimingAnAudioWrite(){
        for(enabled in listOf(false,true))for(receipt in listOf("SOUND_STATE_COMMIT","AUDIO_BLOCK_READBACK")){
            var pump:(()->Unit)?=null;var sent:JSONObject?=null;var posts=0
            val scheduler=PiPollScheduler{_,poll->pump=poll;SourceSubscription{}}
            val transport=object:PiHttpTransport {
                override fun get(path:String)=if(path=="/v1/audio")wireFixture("audio")else if(enabled)wire().toString()else soundWire().toString()
                override fun post(path:String,body:String):String {
                    posts++;sent=JSONObject(body)
                    return JSONObject().put("version",1).put("kind","audio-command-result").put("commandId","deleted")
                        .put("requestId",sent!!.getString("requestId")).put("sessionId",sent!!.getString("sessionId"))
                        .put("serverSessionId",sent!!.getString("serverSessionId")).put("status","APPLIED").put("verification",receipt).toString()
                }
                override fun close()=Unit
            }
            val port=NetworkAudioSound(SourceIdentity("management-test",SourceMode.PI,"SOUND"),transport,scheduler,now={10000},readExecutor=java.util.concurrent.Executor{it.run()})
            try{
                port.setEditorVisible(true,false);pump!!()
                val id=port.submit(AudioSoundCommand.DeletePreset("edit","saved"))
                if(!enabled){assertEquals(0L,id);assertEquals(0,posts)}else{
                    assertTrue(id>0);pump!!();assertEquals(1,posts)
                    assertEquals(if(receipt=="SOUND_STATE_COMMIT")"APPLIED"else"UNKNOWN",port.results.value!!.status)
                    repeat(4){pump!!()};assertEquals(1,posts,"An unknown result must not replay deletion")
                }
            }finally{port.close()}
        }
    }
    @Test fun saveAcceptsOnlyTheAdjacentNumericReadbackOfItsConfirmedPreview(){
        for(steps in listOf(1,2)){
            var pump:(()->Unit)?=null;var posts=0
            val target=soundFixture().preset!!.copy(eqPoints=listOf(SoundEqPoint("p",1000.0,1.9816084653139114)))
            var gain=target.eqPoints.single().gainDb;repeat(steps){gain=Math.nextAfter(gain,0.0)}
            var current=soundFixture()
            val transport=object:PiHttpTransport {
                override fun get(path:String)=if(path=="/v1/audio")wireFixture("audio")else soundWire(current).toString()
                override fun post(path:String,body:String):String {
                    posts++;val r=JSONObject(body)
                    if(r.getJSONObject("command").getString("type")=="soundPreview")current=current.copy(preset=target.copy(eqPoints=listOf(target.eqPoints.single().copy(gainDb=gain))))
                    return JSONObject().put("version",1).put("kind","audio-command-result").put("commandId","c$posts")
                        .put("requestId",r.getString("requestId")).put("sessionId",r.getString("sessionId"))
                        .put("serverSessionId",r.getString("serverSessionId")).put("status","APPLIED").put("verification","AUDIO_BLOCK_READBACK").toString()
                }
                override fun close()=Unit
            }
            val port=NetworkAudioSound(SourceIdentity("numeric-readback",SourceMode.PI,"SOUND"),transport,PiPollScheduler{_,p->pump=p;SourceSubscription{}},now={10000L},readExecutor=java.util.concurrent.Executor{it.run()})
            try{
                port.setEditorVisible(true,false);pump!!()
                port.submit(AudioSoundCommand.Preview("p",SoundScope.PRESET,preset=target));port.submit(AudioSoundCommand.Save("p",SoundScope.PRESET))
                pump!!();pump!!();assertEquals(if(steps==1)2 else 1,posts)
                assertEquals(if(steps==1)"APPLIED"else"REJECTED",port.results.value!!.status)
            }finally{port.close()}
        }
    }
    @Test fun saveCannotCommitAnOlderDraftAfterARejectedOrUnverifiedPreview(){
        for(status in listOf("REJECTED","UNKNOWN","APPLIED"))for(readbackMatches in listOf(false,true)){
            var pump:(()->Unit)?=null;var request:JSONObject?=null;var posts=0
            val target=soundFixture().preset!!.copy(name="New name")
            var current=soundFixture()
            val transport=object:PiHttpTransport {
                override fun get(path:String)=if(path=="/v1/audio")wireFixture("audio")else soundWire(current).toString()
                override fun post(path:String,body:String):String {
                    posts++;request=JSONObject(body)
                    val type=request!!.getJSONObject("command").getString("type")
                    if(type=="soundPreview"&&status=="APPLIED"&&readbackMatches)current=current.copy(preset=target)
                    return JSONObject().put("version",1).put("kind","audio-command-result").put("commandId","c$posts")
                        .put("requestId",request!!.getString("requestId")).put("sessionId",request!!.getString("sessionId"))
                        .put("serverSessionId",request!!.getString("serverSessionId")).put("status",if(type=="soundPreview")status else "APPLIED").put("verification","AUDIO_BLOCK_READBACK").toString()
                }
                override fun close()=Unit
            }
            val scheduler=PiPollScheduler{_,poll->pump=poll;SourceSubscription{}}
            val port=NetworkAudioSound(SourceIdentity("save-dependency",SourceMode.PI,"SOUND"),transport,scheduler,now={10000L},readExecutor=java.util.concurrent.Executor{it.run()})
            try{
                port.setEditorVisible(true,false);pump!!()
                port.submit(AudioSoundCommand.Preview("p",SoundScope.PRESET,preset=target))
                val save=port.submit(AudioSoundCommand.Save("p",SoundScope.PRESET))
                pump!!();pump!!()
                assertEquals(if(status=="APPLIED"&&readbackMatches)2 else 1,posts)
                assertEquals(if(status=="APPLIED"&&readbackMatches)"APPLIED"else"REJECTED",port.results.value!!.status)
                assertEquals(save,port.results.value!!.id)
            }finally{port.close()}
        }
    }

}
