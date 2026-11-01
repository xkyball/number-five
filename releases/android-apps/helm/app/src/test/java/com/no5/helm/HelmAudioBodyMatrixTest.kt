package com.no5.helm

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import com.no5.helm.network.soundFixture
import com.no5.ui.No5Text
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private class BodyMediaPort:AudioMediaPort{
    override val metadata=MutableSourceFeed(AudioMediaMetadata("fixture",10000,true,navigationAvailable=true))
    override val artwork=MutableSourceFeed<AudioArtworkBytes?>(null)
    override val browsing=MutableSourceFeed(AudioBrowseResult())
    override val selection=MutableSourceFeed(AudioMediaSelectionResult())
    override val playlistArtwork=MutableSourceFeed<Map<String,AudioPlaylistArtworkBytes>>(emptyMap())
    val requested=mutableListOf<AudioMediaSelection>()
    override fun select(request:AudioMediaSelection){requested+=request;selection.emit(AudioMediaSelectionResult(request.requestId,request.uri,AudioMediaSelectionStatus.PENDING,"FIXTURE_PENDING"))}
    override fun browse(query:AudioBrowseQuery)=Unit
    override fun cancelBrowse()=Unit
    override fun close()=Unit
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HelmAudioBodyMatrixTest{
    @get:Rule val ui=createComposeRule()
    @Test fun everyReachableAudioPanelBodyFitsAtNormalFont()=matrix(1f)
    @Test fun everyReachableAudioPanelBodyFitsAtLargeFont()=matrix(1.25f)
    private fun matrix(font:Float){
        val h=HelmBodyMatrixSupport(ui,"audio-bodies")
        var audio:AudioNativeController?=null
        val cases=listOf("setup","setup-details","setup-long-alias","setup-empty","setup-save-error","setup-dirty-close","presets","presets-empty","presets-legacy-eq","queue","queue-empty","queue-unknown","library-playlists","library-playlist","library-liked","library-search","library-loading","library-empty","library-error","library-pending")
        val playlist="spotify:playlist:4uLU6hMCjMI75M1A2tKUQC"
        val song="spotify:track:4uLU6hMCjMI75M1A2tKUQC"
        try{
            h.run(font,cases,before={case->
                audio?.dispose()
                val media=BodyMediaPort()
                val delegate=MockAudioSource(now={10000L}).also{it.loadFixture(AudioFixture.DEMO)}
                val base=delegate.state.value
                val dsp=base.dsp.copy(presets=if(case=="presets-empty")emptyList()else listOf(AudioPreset("fixture","OBSERVED_PRESET",true),AudioPreset("other","OBSERVED_OTHER_PRESET")),
                    faderControls=if(case=="setup-empty")emptyList()else base.dsp.faderControls,
                    equalizer=if(case=="presets-legacy-eq")AudioEqualizer("master","a".repeat(64),AudioEqualizer.FREQUENCIES_HZ,listOf(0.0,0.0,0.0,0.0,0.0),-6.0,6.0,1.0,0.0,true,10000L)else null)
                val soundPort=object:AudioSoundPort{
                    override val state=MutableSourceFeed(soundFixture().let{if(case=="presets-legacy-eq")it.copy(capabilities=it.capabilities.copy(parametricEq=false))else it})
                    override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null)
                    override val results=MutableSourceFeed<AudioSoundResult?>(null)
                    override fun submit(command:AudioSoundCommand)=0L
                    override fun setEditorVisible(visible:Boolean,eqVisible:Boolean)=Unit
                    override fun refresh()=Unit
                    override fun close()=Unit
                }
                val source=object:AudioSourcePort by delegate{
                    override val state=MutableSourceFeed(base.copy(dsp=dsp,media=base.media.copy(title="OBSERVED_very long current track title that remains accessible",artists=listOf("OBSERVED_artist")),capabilities=base.capabilities.copy(equalizer=case=="presets-legacy-eq")))
                    override val mediaBrowser:AudioMediaPort=media
                    override val sound:AudioSoundPort=soundPort
                }
                val ctl=AudioNativeController(Dispatchers.Unconfined,now={10000L},automaticTicker=false,automaticVerification=false,source=source);audio=ctl
                if(case.startsWith("queue")){
                    media.metadata.emit(AudioMediaMetadata("fixture",10000,true,song,queue=AudioObservedQueue("revision",null,10000,emptyList(),if(case=="queue-empty")emptyList()else (0 until 20).map{AudioQueueItem(song,"OBSERVED_next_song_$it","OBSERVED_artist")}),navigationAvailable=true))
                    if(case=="queue-unknown")media.selection.emit(AudioMediaSelectionResult(status=AudioMediaSelectionStatus.UNKNOWN))
                }
                if(case.startsWith("library")){
                    val kind=when(case){"library-playlist"->AudioBrowseKind.PLAYLIST;"library-liked","library-pending"->AudioBrowseKind.LIKED;"library-search"->AudioBrowseKind.SEARCH;else->AudioBrowseKind.PLAYLISTS}
                    val query=AudioBrowseQuery(kind,uri=if(kind==AudioBrowseKind.PLAYLIST)playlist else null)
                    if(kind==AudioBrowseKind.PLAYLIST){
                        val item=AudioQueueItem(playlist,"OBSERVED_playlist_title",null,AudioPlaylistDetails("OBSERVED_owner","OBSERVED_description",23,true,null))
                        media.browsing.emit(AudioBrowseResult(AudioBrowseQuery(AudioBrowseKind.PLAYLISTS),AudioBrowsePage("fixture",0,"revision",listOf(item),null,true,false)))
                        ctl.openMusicPlaylist(item)
                    }else ctl.browseMedia(query)
                    val rows=if(case=="library-empty")emptyList()else listOf(AudioQueueItem(if(kind==AudioBrowseKind.PLAYLISTS)playlist else song,"OBSERVED_collection_or_song","OBSERVED_artist",if(kind==AudioBrowseKind.PLAYLISTS)AudioPlaylistDetails("OBSERVED_owner","OBSERVED_description",23,true,null)else null))
                    media.browsing.emit(if(case=="library-error")AudioBrowseResult(query,status="FIXTURE_READ_FAILURE")else AudioBrowseResult(query,AudioBrowsePage("fixture",0,"revision",rows,null,true,false),loading=case=="library-loading"))
                    if(case=="library-pending"){
                        media.metadata.emit(AudioMediaMetadata("fixture",10000,true,song,queue=AudioObservedQueue("revision",null,10000,emptyList(),emptyList()),navigationAvailable=true))
                        assertTrue(ctl.selectMedia(0,rows.first(),false))
                        assertEquals(1,media.requested.size)
                    }
                }
            },after={case,frame->
                if(case=="setup-details")ui.onNodeWithTag("sound-setup-details").performClick()
                if(case=="setup-save-error"||case=="setup-dirty-close"){
                    ui.onNodeWithTag("sound-setup-alias-MASTER").performTextReplacement("OBSERVED_changed")
                    ui.onNodeWithTag(if(case=="setup-save-error")"sound-setup-save"else"sound-setup-close").performClick()
                    if(case=="setup-dirty-close"){
                        focusHelmTestForegroundWindow(ui)
                        ui.onNodeWithTag("unsaved-changes-dialog").assertIsDisplayed()
                        h.stableBody("unsaved-changes-body","unsaved-changes-title","unsaved-changes-actions")
                    }else ui.onNodeWithTag("sound-setup-error").assertExists()
                }
                if(case=="library-pending")ui.onNodeWithText(No5Text.text(frame.language,"Wird bestätigt …"),useUnmergedTree=true).assertExists()
                h.inspect("${frame.language}/${frame.night}/$font/$case/actual-body",font)
                ui.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach{node->val b=node.boundsInRoot
                    if(b.width>0&&b.height>0)assertTrue("$case control behind physical knob: $b",KnobGeometry.safe(b.left,b.top,b.right,b.bottom))}
            }){f->
                val ctl=requireNotNull(audio)
                val b=when{f.case.startsWith("setup")->AudioNativeGeometry.soundSetup;f.case.startsWith("presets")->AudioNativeGeometry.drawer;f.case.startsWith("queue")->AudioNativeGeometry.queueOverlay;else->AudioNativeGeometry.musicPage}
                Box(Modifier.offset((b.x*.5f).dp,(b.y*.5f).dp).size((b.width*.5f).dp,(b.height*.5f).dp)){
                    when{
                        f.case.startsWith("setup")->SoundSetupPanel(.5f,f.night,ctl,
                            if(f.case=="setup-long-alias")AudioPresentationSettings.DEFAULT.update(AudioChannel.MASTER,"OBSERVED_"+"long_local_alias_".repeat(4),true)else AudioPresentationSettings.DEFAULT,
                            {if(f.case=="setup-save-error")error("FIXTURE_DISK_FAILURE")},{},Modifier.fillMaxSize())
                        f.case.startsWith("presets")->AudioPresetPanel(.5f,f.night,ctl,{},Modifier.fillMaxSize())
                        f.case.startsWith("queue")->AudioQueueOverlay(.5f,f.night,ctl,{},Modifier.fillMaxSize())
                        else->AudioMediaPanel(.5f,f.night,ctl,{},Modifier.fillMaxSize())
                    }
                }
            }
        }finally{ui.runOnIdle{audio?.dispose()}}
    }
}
