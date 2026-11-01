package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.*
import com.no5.header.artwork.No5WorkspaceDiagnostics
import com.no5.header.artwork.No5WorkspaceTextFit
import com.no5.ui.*
import kotlinx.coroutines.Dispatchers
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Synthetic device UI proof. No SourceSession, socket, QF, DSP or transmitter is started. */
class UnifiedWorkspaceDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun views()=WindowInspector.getGlobalWindowViews().filter{it.isAttachedToWindow&&it.visibility==View.VISIBLE}.flatMap{all(it)}
    private fun tap(tag:String){ui.runOnIdle{val v=views().singleOrNull{it.tag==tag};assertNotNull("Missing $tag; native tags=${views().mapNotNull{it.tag}.joinToString()}",v);v!!;assertTrue("Disabled $tag",v.isEnabled);v.performClick()};ui.waitForIdle()}
    private fun field(tag:String,value:String){ui.runOnIdle{(views().single{it.tag==tag} as EditText).setText(value)};ui.waitForIdle()}
    private fun contains(value:String){ui.runOnIdle{assertTrue("Missing native value: $value",views().any{it.contentDescription?.contains(value)==true})}}
    private fun capture(name:String){ui.runOnUiThread{
        val root=WindowInspector.getGlobalWindowViews().last{it.isAttachedToWindow&&it.visibility==View.VISIBLE}
        val frame=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        try{root.draw(Canvas(frame));val dir=File(context.getExternalFilesDir(null),"unification-proof").apply{mkdirs()};File(dir,"$name.png").outputStream().use{frame.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{frame.recycle()}
    }}
    private class Media:AudioMediaPort{
        override val metadata=MutableSourceFeed(AudioMediaMetadata("device-proof",10000,true,"spotify:track:4uLU6hMCjMI75M1A2tKUQC",queue=AudioObservedQueue("01234567-89ab-4cde-8fab-0123456789ab",null,10000,emptyList(),emptyList()),navigationAvailable=true))
        override val artwork=MutableSourceFeed<AudioArtworkBytes?>(null)
        override val browsing=MutableSourceFeed(AudioBrowseResult())
        override val selection=MutableSourceFeed(AudioMediaSelectionResult())
        val queries=mutableListOf<AudioBrowseQuery>();val selections=mutableListOf<AudioMediaSelection>()
        override fun browse(query:AudioBrowseQuery){queries+=query}
        override fun select(request:AudioMediaSelection){selections+=request;selection.emit(AudioMediaSelectionResult(request.requestId,request.uri,AudioMediaSelectionStatus.PENDING,"synthetic pending"))}
        override fun cancelBrowse()=Unit
        override fun close()=Unit
    }
    private val disposeActions=mutableListOf<()->Unit>()
    @After fun cleanup(){InstrumentationRegistry.getInstrumentation().runOnMainSync{disposeActions.forEach{it()};disposeActions.clear();No5WorkspaceDiagnostics.observe=null};UiLanguage.acceptConfirmed(context,"de")}
    @Test fun ownDialogsRetainAppFocusButPauseAndCloseCannotReviveTheOwner(){
        var opened by mutableStateOf(false)
        var owner:android.app.Activity?=null
        var focus:No5OwnedWindowFocus?=null
        val observed=mutableListOf<Boolean>()
        ui.setContent{MaterialTheme{Box(Modifier.fillMaxSize()){
            if(opened)No5DetailsDialog("Version und WLAN",false,{opened=false},body="SYNTHETIC OWN WINDOW",tag="owned-focus-proof")
        }}}
        ui.runOnIdle{
            owner=(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity
            focus=No5OwnedWindowFocus(owner!!){observed+=it};disposeActions+={focus?.close()};focus!!.onResume()
            assertTrue(owner!!.hasWindowFocus());assertTrue(focus!!.focused());opened=true
        }
        ui.waitForIdle()
        ui.waitUntil(3000){var ready=false;InstrumentationRegistry.getInstrumentation().runOnMainSync{ready=!owner!!.hasWindowFocus()&&focus!!.focused()};ready}
        ui.runOnIdle{
            assertTrue("The popup, not the main window, is actually focused",WindowInspector.getGlobalWindowViews().any{it.hasWindowFocus()&&it!==owner!!.window.decorView})
            focus!!.onPause();assertFalse(focus!!.focused());focus!!.windowChanged();assertFalse(focus!!.focused());focus!!.onResume();assertTrue(focus!!.focused())
        }
        ui.onNodeWithTag("Schließen").performClick()
        ui.waitUntil(3000){var ready=false;InstrumentationRegistry.getInstrumentation().runOnMainSync{ready=owner!!.hasWindowFocus()&&focus!!.focused()};ready}
        // A different Activity in this very package may not keep the old page's bindings alive.
        var another:android.app.Activity?=null
        ui.runOnIdle{owner!!.startActivity(android.content.Intent(owner,androidx.activity.ComponentActivity::class.java))}
        ui.waitUntil(3000){var lost=false;InstrumentationRegistry.getInstrumentation().runOnMainSync{
            another=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).singleOrNull{it!==owner&&it.packageName==context.packageName}
            lost=another?.hasWindowFocus()==true&&!focus!!.focused()
        };lost}
        ui.runOnIdle{assertNotSame(owner,another);another!!.finish()}

        ui.waitUntil(3000){var ready=false;InstrumentationRegistry.getInstrumentation().runOnMainSync{ready=owner!!.hasWindowFocus()&&focus!!.focused()};ready}
        ui.runOnIdle{focus!!.close();assertFalse(focus!!.focused());val count=observed.size;focus!!.windowChanged();focus!!.onResume();assertFalse(focus!!.focused());assertEquals(count,observed.size)}
    }
    @Test fun actualMusicFilterSearchAndQueueKeepExactSourceRowsAndReadback(){
        UiLanguage.acceptConfirmed(context,"de");val media=Media();var writes=0
        val source=object:AudioSourcePort by MockAudioSource(now={10000}){
            override val mediaBrowser:AudioMediaPort=media
            override fun submit(command:AudioCommand):AudioCommandOutcome{writes++;error("No mixer writes during browse")}
        }
        val ctl=AudioNativeController(Dispatchers.Main.immediate,now={10000},automaticTicker=false,source=source).also{disposeActions+={it.dispose()}}
        ui.setContent{MaterialTheme{BoxWithConstraints(Modifier.fillMaxSize()){val scale=maxWidth.value/2000f;AudioScreen(scale,false,ctl)}}}
        ui.runOnIdle{ctl.openPanel("media")};tap("no5-row-music-playlists")
        val playlists=(0 until 26).map{AudioQueueItem("spotify:playlist:"+it.toString().padStart(22,'0'),"Playlist $it",null,AudioPlaylistDetails("Owner","Description $it",it,false,null))}
        ui.runOnIdle{media.browsing.emit(AudioBrowseResult(ctl.musicQuery,AudioBrowsePage("device-proof",0,"page",playlists,null,true,false)));media.queries.clear()}
        field("no5-field-playlist-filter","description 25");contains("1 Treffer · 26 Playlists geladen");tap("no5-row-spotify-item-${playlists.last().uri}")
        ui.runOnIdle{assertEquals(playlists.last().uri,media.queries.single().uri)}
        tap("no5-workspace-back");tap("no5-workspace-back");tap("no5-row-music-search");field("no5-field-spotify-search","Native title")
        ui.runOnIdle{assertEquals(2,media.queries.size)};tap("no5-action-music-search-submit")
        val song="spotify:track:4uLU6hMCjMI75M1A2tKUQC"
        ui.runOnIdle{
            assertEquals("Native title",media.queries.last().text)
            val old=ctl.musicQuery;media.browsing.emit(AudioBrowseResult(old,AudioBrowsePage("device-proof",0,"tracks",listOf(AudioQueueItem(song,"Native title","Artist")),null,true,false)))
            ctl.showMusicSearch();ctl.browseMedia(old.copy(text="New query"));assertFalse(ctl.canSelectMediaItem(0,AudioQueueItem(song,"Native title","Artist"),false))
            ctl.closePanel();media.metadata.emit(media.metadata.value.copy(queue=AudioObservedQueue("01234567-89ab-4cde-8fab-0123456789ab",null,10000,emptyList(),listOf(AudioQueueItem(song,"First",null),AudioQueueItem(song,"Duplicate",null)))));ctl.openPanel("queue")
        }
        tap("no5-row-queue-row-1");ui.runOnIdle{assertEquals(1,media.selections.single().index);assertEquals("queue",ctl.panel);media.selection.emit(AudioMediaSelectionResult(media.selections.single().requestId,song,AudioMediaSelectionStatus.UNKNOWN,"synthetic unknown"))}
        contains("Ausgang unklar · keine Wiederholung");capture("music-queue-unknown")
        ui.runOnIdle{media.selection.emit(AudioMediaSelectionResult(media.selections.single().requestId,song,AudioMediaSelectionStatus.VERIFIED,"synthetic verified"));assertNull(ctl.panel);assertEquals(0,writes);ctl.dispose()}
    }
    @Test fun sonarRealControlsKeepFreezeProfilesAndSafeBackNavigation(){
        UiLanguage.acceptConfirmed(context,"de");val source=DeterministicMockSonarSource(now={1_000_000L});val ctl=SonarNativeController(source,{1_000_000L}).also{disposeActions+={it.dispose()}}
        source.loadFixture(SonarFixture.CALIBRATED,SonarAcquisitionRequest(source.identity.sessionId,1))
        ui.setContent{MaterialTheme{BoxWithConstraints(Modifier.fillMaxSize()){SonarScreen(maxWidth.value/2000f,false,ctl)}}}
        ui.runOnIdle{ctl.open()};tap("no5-row-sonar-menu-image")
        ui.runOnIdle{
            val slider=views().single{it.tag=="no5-slider-sonar-gain"}
            assertTrue(slider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,android.os.Bundle().apply{putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,12f)}))
        }
        ui.runOnIdle{assertEquals(12.0,ctl.snapshot.display.gainDb,.001);assertEquals(SonarImageMode.MANUAL,ctl.snapshot.display.gainMode)}
        ui.runOnIdle{
            val slider=views().single{it.tag=="no5-slider-sonar-gain"};val down=android.os.SystemClock.uptimeMillis()
            fun event(action:Int,x:Float){android.view.MotionEvent.obtain(down,down+action,action,x,72f,0).also{try{slider.dispatchTouchEvent(it)}finally{it.recycle()}}}
            event(android.view.MotionEvent.ACTION_DOWN,180f);val before=ctl.snapshot.display.gainDb
            event(android.view.MotionEvent.ACTION_CANCEL,180f);event(android.view.MotionEvent.ACTION_UP,340f)
            assertFalse(slider.isPressed);assertEquals(before,ctl.snapshot.display.gainDb,.001)
        }
        tap("no5-row-sonar-gain")
        ui.onNodeWithTag("Sonar GAIN Manual").performClick();ui.runOnIdle{assertEquals(SonarImageMode.MANUAL,ctl.snapshot.display.gainMode)}
        ui.onNodeWithTag("Schließen").performClick();tap("no5-row-sonar-picture-details");tap("no5-row-sonar-smoothing")
        ui.onNodeWithTag("Sonar interpolation linear").performClick();ui.runOnIdle{assertTrue(ctl.linearInterpolation)}
        ui.onNodeWithTag("Sonar exact ping profiles").performClick();ui.onNodeWithTag("Sonar interpolation linear").assertIsNotEnabled()
        ui.runOnIdle{assertTrue(ctl.snapshot.display.exactPingProfiles);assertTrue(ctl.linearInterpolation)}
        ui.onNodeWithTag("Schließen").performClick();ui.runOnIdle{ctl.freeze();assertTrue(ctl.snapshot.display.freeze)};tap("no5-row-sonar-smoothing");ui.onNodeWithTag("Sonar exact ping profiles").assertIsNotEnabled();ui.onNodeWithTag("Sonar interpolation nearest").assertIsNotEnabled();ui.onNodeWithTag("Schließen").performClick();tap("no5-workspace-back");tap("no5-action-sonar-tab-diagnostics");tap("no5-row-sonar-geometry")
        ui.onNodeWithTag("Sonar fact Ping-ID").performScrollTo().assertExists();capture("sonar-frozen-geometry")
        ui.onNodeWithTag("Schließen").performClick();tap("no5-action-sonar-close");ui.runOnIdle{assertNull(ctl.drawer);ctl.dispose()}
    }
    @Test fun nativeMenuTextAndCaptionFitAllLanguagesThemesAndFontSizes(){
        val source=DeterministicMockSonarSource(now={1_000_000L});val ctl=SonarNativeController(source,{1_000_000L}).also{disposeActions+={it.dispose()}}
        source.loadFixture(SonarFixture.CALIBRATED,SonarAcquisitionRequest(source.identity.sessionId,1))
        var language by mutableStateOf("de");var night by mutableStateOf(false);var font by mutableFloatStateOf(1f)
        val fits=mutableListOf<No5WorkspaceTextFit>();No5WorkspaceDiagnostics.observe={fits+=it}
        ui.setContent{val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,font)){
            key(language){MaterialTheme{BoxWithConstraints(Modifier.fillMaxSize()){SonarScreen(maxWidth.value/2000f,night,ctl)}}}
        }}
        for(lang in listOf("de","en","af"))for(isNight in listOf(false,true))for(size in listOf(1f,1.25f)){
            ui.runOnIdle{UiLanguage.acceptConfirmed(context,lang);language=lang;night=isNight;font=size;ctl.close()};ui.waitForIdle();ui.runOnIdle{ctl.open()}
            for(page in listOf("menu","image","image-details","image-manual","analysis","diagnostics")){
                when(page){"image"->tap("no5-row-sonar-menu-image");"image-details"->tap("no5-row-sonar-picture-details");"image-manual"->tap("no5-row-sonar-image-manual");"analysis"->{tap("no5-workspace-back");tap("no5-workspace-back");tap("no5-action-sonar-tab-analysis")};"diagnostics"->tap("no5-action-sonar-tab-diagnostics")}
                ui.runOnIdle{fits.clear()};capture("sonar-$lang-$isNight-font$size-$page")
                ui.runOnIdle{assertTrue(fits.isNotEmpty());fits.forEach{assertEquals(size,it.fontScale,0f);assertTrue("Text overflow $lang/$page: $it",it.width<=it.available+.01f)}}
            }
        };ui.runOnIdle{ctl.dispose()}
    }
}
