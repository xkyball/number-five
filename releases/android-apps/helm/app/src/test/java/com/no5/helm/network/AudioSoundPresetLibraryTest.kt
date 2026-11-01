package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

class AudioSoundPresetLibraryTest {
    @Test fun currentAudioReleaseNotesAreTranslatedIncludingTheDocumentedSonarRelease(){
        val root=sequenceOf(java.io.File("."),java.io.File(".."),java.io.File("../.."))
            .first{java.io.File(it,"releases.json").isFile}
        val releases=JSONObject(java.io.File(root,"releases.json").readText()).getJSONArray("releases")
        val notes=(0 until releases.length()).map{releases.getJSONObject(it)}.filter{it.getInt("versionCode")>=130}
            .flatMap{release->release.getJSONArray("changes").let{a->(0 until a.length()).map{a.getJSONObject(it).getString("text")}}}
        assertTrue(notes.size>=4)
        notes.forEach{note->
            assertTrue(com.no5.ui.No5Text.hasTranslation(note),note)
            assertNotEquals(note,com.no5.ui.No5Text.text("en",note))
            assertNotEquals(note,com.no5.ui.No5Text.text("af",note))
        }
    }
    private fun wire():JSONObject=soundWire().apply{
        getJSONObject("capabilities").put("presetLibrary",true)
        put("presetCatalog",JSONArray().put(JSONObject().put("id","saved").put("name","Abend").put("routingTemplate","stereo_4_1")))
    }
    @Test fun oldGatewayRemainsReadableButCannotAdvertiseAUserPresetLibrary(){
        val old=AudioSoundCodec.state(soundWire().toString())
        assertFalse(old.capabilities.presetLibrary);assertTrue(old.presetCatalog.isEmpty())
        val next=AudioSoundCodec.state(wire().toString())
        assertTrue(next.capabilities.presetLibrary);assertEquals("saved",next.presetCatalog.single().id)
        for(makeInvalid in listOf<(JSONObject)->Unit>(
            {it.getJSONObject("capabilities").put("presetLibrary","true")},
            {it.remove("presetCatalog")},
            {it.getJSONArray("presetCatalog").put(it.getJSONArray("presetCatalog").getJSONObject(0))},
            {it.getJSONArray("presetCatalog").getJSONObject(0).put("path","/untrusted")}
        )){val bad=wire();makeInvalid(bad);assertFails{AudioSoundCodec.state(bad.toString())}}
    }
    @Test fun createAndLoadUseBoundedTypedCommandsWithIndependentRevisionBasis(){
        val state=AudioSoundCodec.state(wire().toString())
        for(command in listOf(AudioSoundCommand.SaveAs("edit","Abend"),AudioSoundCommand.LoadPreset("edit","saved"))){
            val json=JSONObject(AudioSoundCodec.command("s","s:1","g",55,state,command))
            val action=json.getJSONObject("command")
            assertEquals("preset",action.getString("scope"));assertEquals(3,action.getInt("expectedPresetRevision"))
            assertEquals(7,action.getInt("expectedCalibrationRevision"));assertFalse(action.has("model"));assertFalse(action.has("path"))
            assertEquals(if(command is AudioSoundCommand.SaveAs)"soundSaveAs"else"soundLoadPreset",action.getString("type"))
            assertTrue(json.toString().toByteArray().size<4096)
        }
        for(name in listOf("", " ", "bad\nname", "x".repeat(101),"ä".repeat(65)))assertFails{AudioSoundCommand.SaveAs("edit",name)}
    }
}
