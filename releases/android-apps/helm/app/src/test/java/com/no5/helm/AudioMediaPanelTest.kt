package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import com.no5.ui.No5Text
import com.no5.ui.UiLanguage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real native D03 rows and fields; assertions retain the original media/session/readback contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,33,35],qualifiers="de-w2000dp-h1200dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioMediaPanelTest {
    @get:Rule val ui=createComposeRule()
    private val playlist="spotify:playlist:4uLU6hMCjMI75M1A2tKUQC"
    private val song="spotify:track:4uLU6hMCjMI75M1A2tKUQC"
    private class Media:AudioMediaPort {
        override val metadata=MutableSourceFeed(AudioMediaMetadata("receiver",1,true))
        override val artwork=MutableSourceFeed<AudioArtworkBytes?>(null)
        override val browsing=MutableSourceFeed(AudioBrowseResult())
        override val selection=MutableSourceFeed(AudioMediaSelectionResult())
        override val playlistArtwork=MutableSourceFeed<Map<String,AudioPlaylistArtworkBytes>>(emptyMap())
        val selections=mutableListOf<AudioMediaSelection>();val requests=mutableListOf<AudioBrowseQuery>()
        override fun select(request:AudioMediaSelection){selections+=request;selection.emit(AudioMediaSelectionResult(request.requestId,request.uri,AudioMediaSelectionStatus.PENDING,"Pending"))}
        override fun browse(query:AudioBrowseQuery){requests+=query}
        override fun cancelBrowse()=Unit
        override fun close()=Unit
    }
    private fun source(media:Media)=object:AudioSourcePort by (MockAudioSource(now={10000}).also{it.loadFixture(AudioFixture.DEMO)}) {override val mediaBrowser:AudioMediaPort=media}
    private fun controller(media:Media)=AudioNativeController(kotlinx.coroutines.Dispatchers.Unconfined,now={10000},automaticTicker=false,source=source(media))
    private fun views()=nativeWorkspaceViews((ui as AndroidComposeTestRule<*,*>).activity.window.decorView)
    private fun click(tag:String){ui.runOnIdle{views().single{it.tag==tag}.also{assertTrue("Disabled $tag",it.isEnabled)}.performClick()};ui.waitForIdle()}
    private fun field(tag:String,value:String){ui.runOnIdle{(views().single{it.tag==tag} as EditText).setText(value)};ui.waitForIdle()}
    private fun contains(text:String,expected:Boolean=true){ui.runOnIdle{assertEquals("Native ink/a11y: $text",expected,views().any{it.contentDescription?.contains(text)==true})}}
    private fun panel(ctl:AudioNativeController,night:androidx.compose.runtime.State<Boolean> = mutableStateOf(false)){
        UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")
        ui.setContent{MaterialTheme{Box(Modifier.size(2000.dp,928.dp)){AudioMediaPanel(1f,night.value,ctl,{},Modifier.fillMaxSize())}}}
    }
    private fun screen(ctl:AudioNativeController){UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de");ui.setContent{MaterialTheme{Box(Modifier.size(2000.dp,1200.dp)){AudioScreen(1f,false,ctl)}}}}
    private fun queue(media:Media,rows:List<AudioQueueItem>){media.metadata.emit(AudioMediaMetadata("receiver",10000,true,song,queue=AudioObservedQueue("01234567-89ab-4cde-8fab-0123456789ab",null,10000,emptyList(),rows),navigationAvailable=true))}
    private fun page(media:Media,query:AudioBrowseQuery,items:List<AudioQueueItem>,next:Int?=null,complete:Boolean=true,unresolved:Boolean=false){media.browsing.emit(AudioBrowseResult(query,AudioBrowsePage("receiver",query.offset,"page",items,next,complete,unresolved)))}
    private fun capture(name:String){ui.runOnUiThread{
        val root=(ui as AndroidComposeTestRule<*,*>).activity.window.decorView
        val frame=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(frame))
        val dir=java.io.File(System.getProperty("no5.renderOutputDir"),"spotify-media").apply{mkdirs()}
        java.io.File(dir,name).outputStream().use{frame.compress(Bitmap.CompressFormat.PNG,100,it)}
    }}
    @Test fun playlistAndSearchAreNativeReadsWithNoPlaybackWrites(){
        val media=Media();var writes=0
        val port=object:AudioSourcePort by source(media){override fun submit(command:AudioCommand):AudioCommandOutcome{writes++;error("Unexpected write")}}
        val ctl=AudioNativeController(kotlinx.coroutines.Dispatchers.Unconfined,now={10000},automaticTicker=false,source=port);val night=mutableStateOf(false)
        panel(ctl,night);click("no5-row-music-playlists")
        ui.runOnIdle{assertEquals(AudioBrowseKind.PLAYLISTS,media.requests.single().kind);page(media,ctl.musicQuery,listOf(AudioQueueItem(playlist,"Actual playlist",null)),complete=false,unresolved=true)}
        contains("Weitere Titel sind derzeit nicht verfügbar");capture("browser-day.png")
        ui.runOnIdle{night.value=true};contains("Actual playlist");capture("browser-night.png")
        click("no5-row-spotify-item-$playlist");ui.runOnIdle{assertEquals(playlist,media.requests.last().uri)}
        click("no5-workspace-back");click("no5-workspace-back");click("no5-row-music-search")
        field("no5-field-spotify-search","Requested title");click("no5-action-music-search-submit")
        ui.runOnIdle{assertEquals(AudioBrowseKind.SEARCH,media.requests.last().kind);assertEquals("Requested title",media.requests.last().text);assertEquals(0,writes);assertTrue(media.selections.isEmpty());ctl.dispose()}
    }
    @Test fun failedBrowseIsVisibleAndNeverPresentedAsAnEmptyCollection(){
        val media=Media();val ctl=controller(media);panel(ctl);click("no5-row-music-playlists")
        ui.runOnIdle{media.browsing.emit(AudioBrowseResult(ctl.musicQuery,status="Existing-session access unavailable; no replay"))}
        contains("Musikliste konnte nicht geladen werden");contains("Keine Titel verfügbar",false)
        ui.runOnIdle{page(media,ctl.musicQuery,emptyList())}
        contains("Keine Titel verfügbar");contains("Musikliste konnte nicht geladen werden",false)
        ui.runOnIdle{assertTrue(media.selections.isEmpty());ctl.dispose()}
    }
    @Test fun queueSelectsExactDuplicateRowAndClosesOnlyOnVerifiedReadback(){
        val media=Media();queue(media,listOf(AudioQueueItem(song,"First occurrence","Artist"),AudioQueueItem(song,"Second occurrence","Artist"),AudioQueueItem(song,null,null)))
        val ctl=controller(media);ctl.openPanel("queue");screen(ctl)
        contains("Titelinformationen fehlen");click("no5-row-queue-row-1")
        ui.runOnIdle{assertEquals(1,media.selections.single().index);assertEquals(song,media.selections.single().uri);assertEquals("queue",ctl.panel)}
        // D03 queue covers the mixer. The common knob controller remains active; no covered touch hits.
        ui.onNodeWithContentDescription(No5Text.ui("Master volume down")).assertDoesNotExist()
        contains("Wird bestätigt …")
        ui.runOnIdle{media.selection.emit(AudioMediaSelectionResult(media.selections.single().requestId,song,AudioMediaSelectionStatus.UNKNOWN,"Unknown"))}
        contains("Ausgang unklar · keine Wiederholung")
        ui.runOnIdle{ctl.closePanel();ctl.openPanel("queue");assertEquals(1,media.selections.size)}
        contains("Ausgang unklar · keine Wiederholung")
        ui.runOnIdle{media.selection.emit(AudioMediaSelectionResult(media.selections.single().requestId,song,AudioMediaSelectionStatus.VERIFIED,"Verified"));assertNull(ctl.panel);ctl.dispose()}
    }
    @Test fun allTwentyObservedNextSongsAreScrollableWithoutPlaybackOrMixerWrites(){
        val media=Media();queue(media,(0 until 20).map{AudioQueueItem(song,"Observed next song ${it+1}","Artist")});val ctl=controller(media)
        ctl.openPanel("queue");screen(ctl);contains("20 nächste Titel · beobachteter Ausschnitt")
        ui.runOnIdle{val last=views().single{it.tag=="no5-row-queue-row-19"};var p=last.parent;while(p!=null&&p !is ScrollView)p=p.parent;(p as ScrollView).scrollTo(0,19*104)}
        ui.runOnIdle{val last=views().single{it.tag=="no5-row-queue-row-19"};val r=android.graphics.Rect();assertTrue(last.getGlobalVisibleRect(r));assertTrue(r.height()>48)}
        contains("Observed next song 20");ui.onNodeWithContentDescription(No5Text.ui("Master volume down")).assertDoesNotExist()
        ui.runOnIdle{assertTrue(media.selections.isEmpty());assertNull(ctl.state.pending);ctl.dispose()}
    }
    @Test fun musicContainsNoVolumeOrBrandingAndPreservesSearchUntilSessionChanges(){
        val media=Media();val ctl=controller(media);ctl.openPanel("media");screen(ctl)
        ui.onNodeWithContentDescription(No5Text.ui("Master volume down")).assertDoesNotExist();ui.onNodeWithText("OPEN").assertDoesNotExist();ui.onNodeWithContentDescription("Spotify").assertDoesNotExist()
        click("no5-row-music-search");field("no5-field-spotify-search","Requested title")
        ui.runOnIdle{ctl.closePanel()};ui.waitForIdle()
        ui.runOnIdle{ctl.openPanel("media");assertEquals("Requested title",ctl.musicSearch)}
        click("no5-row-music-search");ui.runOnIdle{assertEquals("Requested title",(views().single{it.tag=="no5-field-spotify-search"} as EditText).text.toString())}
        ui.runOnIdle{media.metadata.emit(AudioMediaMetadata("new-receiver",10001,true));assertNull(ctl.panel);assertEquals("",ctl.musicSearch);assertEquals(AudioBrowseKind.PLAYLISTS,ctl.musicQuery.kind);ctl.dispose()}
    }
    @Test fun musicReturnAndSelectionStatusClearThePhysicalKnobsInBothThemes(){
        val media=Media();val ctl=controller(media);val night=mutableStateOf(false);panel(ctl,night)
        click("no5-row-music-liked")
        for(theme in listOf(false,true)){
            ui.runOnIdle{night.value=theme;media.selection.emit(AudioMediaSelectionResult("test",null,AudioMediaSelectionStatus.REJECTED,"Rejected"))}
            contains("Auswahl abgewiesen · Liste aktualisieren")
            ui.runOnIdle{for(v in views().filter{it.tag in listOf("no5-workspace-back","no5-row-music-outcome")}){
                val xy=IntArray(2);v.getLocationOnScreen(xy);assertTrue("Behind knob ${v.tag}",KnobGeometry.safe(xy[0].toFloat(),xy[1].toFloat(),(xy[0]+v.width).toFloat(),(xy[1]+v.height).toFloat()))
            }}
        };ui.runOnIdle{ctl.dispose()}
    }
    @Test fun audioRetainsOnlyTheTwoApprovedMusicEntryButtons(){
        val ctl=controller(Media());screen(ctl);ui.onNodeWithContentDescription("Als Nächstes").assertExists();ui.onNodeWithContentDescription("Musik").assertExists();ui.onNodeWithContentDescription("Browse Spotify and observed queue").assertDoesNotExist();ui.runOnIdle{ctl.dispose()}
    }
    @Test fun playlistRowsShowActualDetailsAndFilterLocallyIncludingAcrossLoadedPages(){
        val media=Media();val ctl=controller(media);val rows=(0 until 26).map{AudioQueueItem("spotify:playlist:"+it.toString().padStart(22,'0'),"Playlist $it",null,AudioPlaylistDetails("Actual owner","Description $it",it,false,null))}
        panel(ctl);click("no5-row-music-playlists");ui.runOnIdle{page(media,ctl.musicQuery,rows);media.requests.clear()}
        contains("26 Playlists");contains("0 Titel · Von Actual owner");field("no5-field-playlist-filter","description 25")
        contains("1 Treffer · 26 Playlists geladen");contains("Playlist 25");contains("Playlist 0",false)
        ui.runOnIdle{assertTrue(media.requests.isEmpty());assertTrue(media.selections.isEmpty())}
        click("no5-row-spotify-item-${rows[25].uri}");ui.runOnIdle{assertEquals(rows[25].uri,media.requests.single().uri);assertEquals(rows[25],ctl.musicPlaylistItem)}
        click("no5-row-playlist-information");ui.onNodeWithText("Description 25").assertExists();ui.onNodeWithText("Von Actual owner").assertExists()
        ui.runOnIdle{ctl.dispose()}
    }
    @Test fun playlistStartButtonsSendExactlyOneExplicitOrderedOrShuffleRequest(){
        val media=Media();queue(media,emptyList());val ctl=controller(media);panel(ctl)
        click("no5-row-music-playlists");ui.runOnIdle{page(media,ctl.musicQuery,listOf(AudioQueueItem(playlist,"Playlist",null)))};click("no5-row-spotify-item-$playlist")
        ui.runOnIdle{page(media,ctl.musicQuery,listOf(AudioQueueItem(song,"Actual song",null)),next=20,complete=false)}
        click("no5-action-music-playlist-play")
        ui.runOnIdle{assertEquals(false,media.selections.single().playlistShuffle);assertEquals(playlist,media.selections.single().uri);assertFalse(ctl.startPlaylist(true));media.selection.emit(AudioMediaSelectionResult(status=AudioMediaSelectionStatus.VERIFIED))}
        click("no5-action-music-playlist-shuffle")
        ui.runOnIdle{assertEquals(2,media.selections.size);assertEquals(true,media.selections.last().playlistShuffle);media.selection.emit(AudioMediaSelectionResult(status=AudioMediaSelectionStatus.VERIFIED))}
        click("no5-action-music-next");ui.runOnIdle{page(media,ctl.musicQuery,listOf(AudioQueueItem(song,"Page two",null)));assertFalse(ctl.canSelectMediaItem(0,AudioQueueItem(song,"Actual song",null),false))}
        ui.runOnIdle{assertFalse(views().any{it.tag=="no5-action-music-playlist-play"});assertEquals(2,media.selections.size);ctl.dispose()}
    }
    @Test fun actualPlaylistCoverDecodesOffTheUiAndNightRetainsGeometryWithoutWhitePixels(){
        val media=Media();val ctl=controller(media);val descriptor=AudioPlaylistArtworkDescriptor("a".repeat(64),"/v1/audio/playlist-artwork/"+"a".repeat(64))
        val item=AudioQueueItem(playlist,"Actual playlist",null,AudioPlaylistDetails("Owner",null,12,false,descriptor));val night=mutableStateOf(false)
        val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)}
        val bytes=java.io.ByteArrayOutputStream().apply{bitmap.compress(Bitmap.CompressFormat.PNG,100,this)}.toByteArray()
        panel(ctl,night);click("no5-row-music-playlists");ui.runOnIdle{page(media,ctl.musicQuery,listOf(item));assertNotNull(decodePlaylistArtwork(bytes));media.playlistArtwork.emit(mapOf(playlist to AudioPlaylistArtworkBytes("receiver",playlist,descriptor.id,bytes)))}
        ui.waitUntil(5000){ctl.playlistBitmap(item)!=null};click("no5-row-spotify-item-$playlist");click("no5-row-playlist-information")
        val cover=ui.onNodeWithTag("playlist-cover-$playlist",useUnmergedTree=true);val day=cover.fetchSemanticsNode().boundsInRoot
        fun pixel():Int{val b=cover.fetchSemanticsNode().boundsInRoot;var pixel=0;ui.runOnUiThread{
            // Dialog has its own native root; capture the actual dialog, not the obscured activity.
            val root=android.view.inspector.WindowInspector.getGlobalWindowViews().last{it.isAttachedToWindow&&it.visibility==View.VISIBLE}
            val frame=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(frame));pixel=frame.getPixel(b.center.x.toInt(),b.center.y.toInt())
        };return pixel}
        val dayCenter=pixel();assertTrue(Color.red(dayCenter)>252&&Color.green(dayCenter)>252&&Color.blue(dayCenter)>252)
        ui.runOnIdle{night.value=true};assertEquals(day,cover.fetchSemanticsNode().boundsInRoot);val center=pixel();assertTrue(Color.green(center)<20&&Color.blue(center)<20)
        ui.runOnIdle{media.metadata.emit(AudioMediaMetadata("other",10000,true));assertNull(ctl.playlistBitmap(item));ctl.dispose()}
    }
}
