package com.no5.helm

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.no5.domain.*
import com.no5.helm.network.soundFixture
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

/** Starts cold, like the real adapter: no capability is available before a visible read. */
private class ColdMenuSoundPort(library:Boolean=false) : AudioSoundPort {
    override val state=MutableSourceFeed(AudioSoundState(reason="NOT_STARTED"))
    override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null)
    override val results=MutableSourceFeed<AudioSoundResult?>(null)
    var visible=false
    var eqVisible=false
    val commands=mutableListOf<AudioSoundCommand>()
    var confirmed=soundFixture().let{it.copy(capabilities=it.capabilities.copy(presetLibrary=library,presetManagement=library),
        preset=it.preset!!.copy(id="existing"),presetCatalog=if(library)listOf(SoundPresetEntry("existing","Existing sound","stereo_4_1"))else emptyList())}
    override fun setEditorVisible(visible:Boolean,eqVisible:Boolean){this.visible=visible;this.eqVisible=eqVisible}
    override fun refresh(){if(visible)state.emit(confirmed)}
    override fun submit(command:AudioSoundCommand):Long {commands+=command;return commands.size.toLong()}
    override fun close()=Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="de-w2000dp-h1200dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioMenuEntryTest {
    @get:Rule val ui=createComposeRule()
    private fun nativeRow(key:String):View {
        val activity=(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity
        return requireNotNull(activity.window.decorView.findViewWithTag<View>("no5-row-$key"))
    }
    @Test fun fullWorkspaceHidesCoveredAudioActionsButTheCompactMixerKeepsMaster(){
        com.no5.ui.UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")
        val sound=ColdMenuSoundPort(library=true)
        val delegate=MockAudioSource(now={10000}).also{it.loadFixture(AudioFixture.DEMO)}
        val source=object:AudioSourcePort by delegate{override val sound=sound}
        val c=AudioNativeController(Dispatchers.Unconfined,{10000},false,false,source)
        ui.setContent{MaterialTheme{Box(Modifier.size(2000.dp,1200.dp)){AudioScreen(1f,false,c)}}}
        try{
            ui.onNodeWithContentDescription("Master-Lautstärke senken").assertExists()
            for(panel in listOf("audio-menu","preset")){
                ui.runOnIdle{c.openPanel(panel)};ui.waitForIdle()
                ui.onNodeWithContentDescription("Master-Lautstärke senken").assertDoesNotExist()
                ui.onNodeWithContentDescription("Audio einrichten").assertDoesNotExist()
            }
            ui.runOnIdle{c.openPanel("mixer")};ui.waitForIdle()
            ui.onNodeWithContentDescription("Master-Lautstärke senken").assertExists()
        }finally{ui.runOnIdle{c.dispose()}}
    }
    @Test fun presetNameFormFitsAllLanguagesThemesAndFontSizesWithoutChangingItsDraft(){
        val sound=ColdMenuSoundPort(library=true)
        val source=object:AudioSourcePort by MockAudioSource(now={10000}){override val sound=sound}
        val c=AudioNativeController(Dispatchers.Unconfined,{10000},false,false,source)
        var language by mutableStateOf("de");var night by mutableStateOf(false);var font by mutableStateOf(1f)
        val captions=mutableSetOf<String>()
        PresetLibraryDiagnostics.text={text,width,available->assertTrue(width<=available+.5f,"Fixed text does not fit: $text");captions+=text}
        ui.setContent{com.no5.ui.No5LocaleScope(language){
            CompositionLocalProvider(LocalDensity provides Density(1f,font)){
                MaterialTheme{Box(Modifier.size(2000.dp,1200.dp)){AudioScreen(1f,night,c)}}
            }
        }}
        try{
            ui.onNodeWithContentDescription("Audio einrichten").performClick()
            ui.runOnIdle{nativeRow("audio-menu-presets").performClick()}
            ui.waitForIdle()
            ui.onNodeWithTag("audio-new-preset").assertIsEnabled().performClick()
            ui.onNodeWithTag("sound-save").assertIsEnabled().performClick()
            ui.waitForIdle()
            ui.runOnIdle{requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView.dispatchWindowFocusChanged(true)}
            ui.onNodeWithTag("audio-preset-name").performTextReplacement("Abend · Evening · Aand")
            for(lang in listOf("de","en","af"))for(dark in listOf(false,true))for(size in listOf(1f,1.25f)){
                ui.runOnIdle{com.no5.ui.UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),lang);language=lang;night=dark;font=size}
                ui.waitForIdle()
                // Robolectric does not dispatch WM focus when Dialog is recreated
                // for a new Density/locale; mirror Android's real focus delivery.
                ui.runOnIdle{requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView.dispatchWindowFocusChanged(true)}
                ui.onNodeWithTag("audio-preset-name").assertTextContains("Abend · Evening · Aand")
                ui.onNodeWithTag("audio-save-new-preset-dialog-confirm").assertIsDisplayed().assertIsEnabled()
                ui.onNodeWithTag("audio-save-new-preset-dialog-cancel").assertIsDisplayed().assertIsEnabled()
                ui.runOnIdle{
                    val view=requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
                    val image=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(image))
                    val dir=java.io.File(System.getProperty("no5.renderOutputDir"),"audio-menu-forms").apply{mkdirs()}
                    java.io.File(dir,"$lang-${if(dark)"night"else"day"}-${if(size>1f)125 else 100}.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                    image.recycle()
                }
            }
            ui.runOnIdle{assertTrue(sound.commands.all{it is AudioSoundCommand.Preview});assertTrue(captions.contains("Preset speichern"));assertTrue(captions.contains("Save preset"));assertTrue(captions.contains("Stoor voorinstelling"))}
        }finally{PresetLibraryDiagnostics.text=null;ui.runOnIdle{c.dispose();RuntimeEnvironment.getApplication().getSharedPreferences("no5-sound-drafts-v1",0).edit().clear().commit()}}
    }
    @Test fun createPresetTakesANameUsesADiscreteCommandAndRetainsTheFormOnFailure(){
        com.no5.ui.UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")
        val sound=ColdMenuSoundPort(library=true)
        val source=object:AudioSourcePort by MockAudioSource(now={10000}){override val sound=sound}
        val c=AudioNativeController(Dispatchers.Unconfined,{10000},false,false,source)
        ui.setContent{MaterialTheme{Box(Modifier.size(2000.dp,1200.dp)){AudioScreen(1f,false,c)}}}
        try{
            ui.onNodeWithContentDescription("Audio einrichten").performClick()
            ui.runOnIdle{nativeRow("audio-menu-presets").performClick()}
            ui.waitForIdle()
            ui.onNodeWithTag("audio-new-preset").assertIsEnabled().performClick()
            ui.onNodeWithTag("sound-save").assertIsEnabled().performClick()
            ui.waitForIdle()
            ui.runOnIdle{requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView.dispatchWindowFocusChanged(true)}
            ui.onNodeWithTag("audio-preset-name").performTextReplacement("Abend an Bord")
            ui.onNodeWithTag("audio-save-new-preset-dialog-confirm").assertIsEnabled().performClick()
            ui.runOnIdle{
                assertEquals("Abend an Bord",c.soundEditor.preset!!.name)
                assertIs<AudioSoundCommand.Save>(sound.commands.last())
                assertTrue(sound.commands.none{it is AudioSoundCommand.SaveAs})
                assertTrue(c.soundEditor.busy)
                sound.results.emit(AudioSoundResult(sound.commands.size.toLong(),"FAILED"))
            }
            ui.onNodeWithTag("audio-preset-name").assertTextContains("Abend an Bord")
            ui.onNodeWithTag("audio-save-new-preset-dialog").assertIsDisplayed()
            val afterFailure=sound.commands.size
            ui.waitForIdle()
            ui.runOnIdle{assertEquals(afterFailure,sound.commands.size,"Failure must not replay")}
            ui.onNodeWithTag("audio-save-new-preset-dialog-confirm").performClick()
            ui.runOnIdle{
                val saved=requireNotNull(c.soundEditor.preset)
                assertTrue(saved.eqPoints.isEmpty());assertEquals(SoundRoom(0.0,.5),saved.room)
                sound.confirmed=sound.confirmed.copy(preset=saved,presetRevision=sound.confirmed.presetRevision+1,preview=null,
                    presetCatalog=sound.confirmed.presetCatalog+SoundPresetEntry(saved.id,saved.name,saved.routingTemplate))
                sound.state.emit(sound.confirmed)
                sound.results.emit(AudioSoundResult(sound.commands.size.toLong(),"APPLIED"))
            }
            ui.onNodeWithTag("audio-save-new-preset-dialog").assertDoesNotExist()
            ui.onNodeWithTag("audio-load-${sound.confirmed.preset!!.id}").assertIsNotEnabled()
            ui.onNodeWithTag("audio-load-existing").assertIsEnabled().performClick()
            ui.runOnIdle{assertEquals("existing",(sound.commands.last() as AudioSoundCommand.LoadPreset).presetId)}
        }finally{ui.runOnIdle{c.dispose();RuntimeEnvironment.getApplication().getSharedPreferences("no5-sound-drafts-v1",0).edit().clear().commit()}}
    }
    @Test fun singleSetupEntryLoadsColdCapabilitiesAndOpensEverySoundAndFaderDestination(){
        com.no5.ui.UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")
        val sound=ColdMenuSoundPort(library=true)
        val source=object:AudioSourcePort by MockAudioSource(now={10000}){override val sound=sound}
        val c=AudioNativeController(Dispatchers.Unconfined,{10000},false,false,source)
        ui.setContent{MaterialTheme{Box(Modifier.size(2000.dp,1200.dp)){AudioScreen(1f,false,c)}}}
        try{
            ui.onNodeWithContentDescription("Audio einrichten").assertIsDisplayed().performClick()
            ui.onNodeWithContentDescription(com.no5.ui.No5Text.ui("Open sound setup")).assertDoesNotExist()
            ui.waitForIdle()
            ui.runOnIdle{
                assertEquals("audio-menu",c.panel)
                assertTrue(sound.visible);assertFalse(sound.eqVisible,"Menu must not start the spectrum")
                assertTrue(nativeRow("audio-menu-speakers").isEnabled)
                nativeRow("audio-menu-speakers").performClick()
            }
            ui.onNodeWithTag("audio-sound-editor").assertExists()
            ui.runOnIdle{assertEquals("sound-speakers",c.panel);assertEquals("delay",c.soundEditor.section);c.closeSoundPanel()}
            ui.waitForIdle()
            ui.runOnIdle{
                assertEquals("audio-menu",c.panel);assertTrue(sound.visible);assertFalse(sound.eqVisible)
                nativeRow("audio-menu-faders").performClick()
            }
            ui.onNodeWithTag("sound-setup-panel").assertExists()
            ui.runOnIdle{assertEquals("mixer",c.panel);c.closePresentationPanel()}
            ui.waitForIdle()
            ui.runOnIdle{nativeRow("audio-menu-presets").performClick()}
            ui.waitForIdle()
            ui.onNodeWithTag("audio-open-preset-editor").assertDoesNotExist()
            ui.onNodeWithTag("audio-edit-existing").assertIsEnabled().performClick()
            ui.onNodeWithTag("audio-sound-editor").assertExists()
            ui.runOnIdle{assertEquals("eq",c.soundEditor.section);assertTrue(sound.eqVisible);assertTrue(sound.commands.isEmpty())}
        }finally{ui.runOnIdle{c.dispose();RuntimeEnvironment.getApplication().getSharedPreferences("no5-sound-drafts-v1",0).edit().clear().commit()}}
    }
}
