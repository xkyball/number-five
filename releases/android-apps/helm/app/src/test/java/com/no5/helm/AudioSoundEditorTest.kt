package com.no5.helm

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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

private class SoundUiPort:AudioSoundPort {
    override val state=MutableSourceFeed(soundFixture())
    override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null)
    override val results=MutableSourceFeed<AudioSoundResult?>(null)
    val commands=mutableListOf<AudioSoundCommand>();var visible=false;var eqVisible=false
    override fun submit(command:AudioSoundCommand):Long {commands+=command;return commands.size.toLong()}
    override fun setEditorVisible(visible:Boolean,eqVisible:Boolean){this.visible=visible;this.eqVisible=eqVisible}
    override fun refresh()=Unit
    override fun close()=Unit
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioSoundEditorTest {
    @get:Rule val ui=createComposeRule()
    @Test fun roomAndCalibrationExposeTheSameFiveSpeakerLocationsIncludingSub(){
        val port=SoundUiPort()
        val calibration=SoundCalibration(SoundSpeakerId.entries.mapIndexed{i,id->SoundSpeaker(id,
            when(i){0->SoundPosition(0.0,0.0);1->SoundPosition(2.0,0.0);2->SoundPosition(0.0,5.0);3->SoundPosition(2.0,5.0);else->SoundPosition(1.2,3.0)})},SoundPosition(.8,2.8),true)
        port.state.emit(soundFixture().copy(calibration=calibration))
        val source=object:AudioSourcePort by MockAudioSource(now={10000}){override val sound=port}
        val audio=AudioNativeController(Dispatchers.Unconfined,{10000},false,false,source)
        val roomInk=mutableMapOf<SoundSpeakerId,androidx.compose.ui.geometry.Offset>()
        val setupInk=mutableMapOf<SoundSpeakerId,androidx.compose.ui.geometry.Offset>()
        SoundTemplateDiagnostics.observeSpeaker={id,room,at->if(room)roomInk[id]=at else setupInk[id]=at}
        fun draw(){ui.runOnIdle{
            val root=(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity.window.decorView
            val image=android.graphics.Bitmap.createBitmap(root.width,root.height,android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(image));image.recycle()
        }}
        ui.runOnIdle{audio.openSoundEditor(false);audio.soundEditor.changeSection("room")}
        ui.setContent{MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){
            AudioSoundEditor(.5f,false,audio,{},registerClose={},registerGuard={})
        }}}
        try{
            draw()
            SoundSpeakerId.entries.forEach{id->ui.onNodeWithTag("sound-room-speaker-${id.name}").assertIsDisplayed()}
            assertEquals(SoundSpeakerId.entries.toSet(),roomInk.keys)
            ui.runOnIdle{audio.openSoundEditor(true)}
            ui.waitForIdle();draw()
            assertEquals(SoundSpeakerId.entries.toSet(),setupInk.keys)
            SoundSpeakerId.entries.forEach{id->
                val position=ui.onNodeWithTag("sound-speaker-${id.name}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
                val ink=setupInk.getValue(id)
                // Compose hit bounds round to integral pixels; the actual cabinet ink is
                // compared in continuous design coordinates without that quantisation.
                assertTrue(kotlin.math.abs(position.x-ink.x)<=.5f)
                assertTrue(kotlin.math.abs(position.y-ink.y)<=.5f)
                // Invert the approved clockwise display rotation before comparing the shared metre mapping.
                assertEquals((roomInk.getValue(id).y-565)/4.1f,(ink.x-755)/2.2f,.001f)
                assertEquals(125-(roomInk.getValue(id).x-1000)/4.1f,(ink.y-280)/2.2f,.001f)
            }
            ui.runOnIdle{assertEquals(calibration,audio.soundEditor.calibration);assertTrue(port.commands.isEmpty())}
        }finally{SoundTemplateDiagnostics.observeSpeaker=null;ui.runOnIdle{audio.dispose()}}
    }
    @Test fun incompleteDistancesCannotSilentlySaveAndTheDecisionRetainsItsDraft(){
        val context=RuntimeEnvironment.getApplication()
        com.no5.ui.UiLanguage.acceptConfirmed(context,"de")
        val port=SoundUiPort()
        val source=object:AudioSourcePort by MockAudioSource(now={10000}){override val sound=port}
        val audio=AudioNativeController(Dispatchers.Unconfined,now={10000},automaticTicker=false,source=source)
        try {
            ui.runOnIdle {audio.openSoundEditor(true);audio.soundEditor.changeSection("measure")}
            ui.setContent{MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){
                AudioSoundEditor(.5f,false,audio,{},registerClose={},registerGuard={})
            }}}
            ui.onNodeWithTag("sound-measure-width").performTextReplacement("1,8")
            ui.onNodeWithTag("sound-measure-length").performTextReplacement("")
            ui.runOnIdle {audio.soundEditor.requestClose(SoundScope.CALIBRATION){}}
            ui.onNodeWithTag("sound-unsaved-save").assertIsNotEnabled()
            ui.onNodeWithText("Beide Abstände zwischen 0,05 und 50 Metern eingeben").assertIsDisplayed()
            ui.onNodeWithTag("sound-unsaved-continue").performClick()
            ui.onNodeWithTag("sound-measure-width").assertTextContains("1,8")
            ui.runOnIdle {assertTrue(port.commands.isEmpty());assertTrue(audio.soundEditor.measurementDirty)}
        } finally {ui.runOnIdle{audio.dispose()}}
    }
    @Test fun nativeViewsRenderInAllLanguagesAndKeepRealKnobClearances(){
        assertTrue(AudioSoundGeometry.safe())
        val port=SoundUiPort()
        // Explicit render fixture: these EQ values are not saved or claimed as boot measurements.
        port.state.emit(soundFixture().copy(preset=SoundPreset("render_fixture","IMMERSIVE","stereo_4_1",listOf(SoundEqPoint("p0",65.0,2.5),SoundEqPoint("p1",250.0,-2.0),SoundEqPoint("point",1200.0,3.0,1.05),SoundEqPoint("p3",5500.0,1.3),SoundEqPoint("p4",14000.0,-1.0)),SoundRoom(.68,.42),eqEnabled=true),headroomDb=12.0))
        val source=object:AudioSourcePort by MockAudioSource(now={10000}){override val sound=port}
        val audio=AudioNativeController(Dispatchers.Unconfined,now={10000},automaticTicker=false,source=source)
        val night=mutableStateOf(false);val language=mutableStateOf("de")
        var currentView="";val textBounds=mutableListOf<org.json.JSONObject>()
        SoundTemplateDiagnostics.observe={label,b->
            assertTrue(KnobGeometry.safe(b.left,b.top,b.right,b.bottom),"$currentView text overlaps physical clearance: $label $b")
            assertTrue(b.left>=0&&b.right<=2000,"$currentView text outside stage: $label $b")
            textBounds+=org.json.JSONObject().put("view",currentView).put("text",label).put("rect",org.json.JSONArray(listOf(b.left,b.top,b.right,b.bottom)))
        }
        ui.runOnIdle {audio.openSoundEditor(false);audio.soundEditor.choosePoint("point")}
        ui.setContent{com.no5.ui.UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),language.value);MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){
            val scene=HelmScene(RuntimeEnvironment.getApplication(),night.value)
            Canvas(Modifier.size(1000.dp,600.dp)){drawIntoCanvas{c->c.nativeCanvas.drawColor(if(night.value)android.graphics.Color.BLACK else android.graphics.Color.rgb(246,249,250));scene.drawChrome(c.nativeCanvas,"AUDIO",renderValues(HelmState.unavailable()),"TEST FIXTURE")}}
            AudioScreen(.5f,night.value,audio)
            Canvas(Modifier.size(1000.dp,600.dp)){drawIntoCanvas{c->KnobOverlay(RuntimeEnvironment.getApplication()).draw(c.nativeCanvas,night.value,audio,useSelectedAudioFader=true)}}
        }}}
        for(lang in listOf("de","en","af")){
            ui.runOnIdle {language.value=lang}
            for(page in listOf("eq","room","delay","gain","measure","place")){
                currentView="$lang-$page"
                ui.runOnIdle {if(page in listOf("delay","gain","measure","place"))audio.openSoundEditor(true)else audio.openSoundEditor(false);audio.soundEditor.changeSection(page)}
                ui.onNodeWithTag("audio-sound-editor").assertExists()
                if(page in listOf("delay","gain"))ui.onNodeWithTag("sound-speaker-SUB").assertIsDisplayed()
                ui.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach{node->val b=node.boundsInRoot
                    if(b.width>0 && b.height>0)assertTrue(KnobGeometry.safe(b.left,b.top,b.right,b.bottom),"$lang/$page clickable overlaps knob: $b")}

                ui.runOnUiThread {val view=(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity.window.decorView
                    val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(bitmap))
                    val dir=java.io.File(System.getProperty("no5.renderOutputDir"),"audio-sound").apply{mkdirs()};java.io.File(dir,"$lang-$page.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            }
        }
        for(lang in listOf("de","en","af"))for(page in listOf("eq","room","delay","gain")){
            currentView="$lang-$page-night"
            ui.runOnIdle {language.value=lang;night.value=true;if(page in listOf("delay","gain"))audio.openSoundEditor(true)else audio.openSoundEditor(false);audio.soundEditor.changeSection(page)}
            ui.onNodeWithTag("audio-sound-editor").assertExists()
            ui.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach{node->val b=node.boundsInRoot
                if(b.width>0&&b.height>0)assertTrue(KnobGeometry.safe(b.left,b.top,b.right,b.bottom),"$currentView action overlaps knob: $b")}
            ui.runOnUiThread {val view=(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity.window.decorView
                val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(bitmap))
                val dir=java.io.File(System.getProperty("no5.renderOutputDir"),"audio-sound").apply{mkdirs()};java.io.File(dir,"$lang-$page-night.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        }
        SoundTemplateDiagnostics.observe=null
        java.io.File(System.getProperty("no5.renderOutputDir"),"audio-sound/text-bounds.json").writeText(org.json.JSONArray(textBounds).toString(2))
        ui.runOnIdle {audio.openSoundEditor(false);assertTrue(port.commands.isEmpty());audio.soundEditor.updatePoint(requireNotNull(audio.soundEditor.preset).eqPoints.first().copy(gainDb=3.0));audio.soundEditor.requestClose(SoundScope.PRESET){}}
        ui.onNodeWithTag("sound-unsaved-decision").assertIsDisplayed()
        val before=port.commands.size
        ui.onNodeWithTag("audio-sound-editor").performTouchInput{click(androidx.compose.ui.geometry.Offset(160f,500f))}
        ui.runOnIdle {assertEquals(before,port.commands.size);audio.dispose()}
    }
    @Test fun nativeEqSwitchAndCompactReserveControlShareThePresetOwner(){
        val port=SoundUiPort();val source=object:AudioSourcePort by MockAudioSource(now={10000}){override val sound=port}
        val audio=AudioNativeController(Dispatchers.Unconfined,now={10000},automaticTicker=false,source=source)
        ui.runOnIdle{com.no5.ui.UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de");audio.openSoundEditor(false)}
        var actualDensity=Float.NaN
        ui.setContent{actualDensity=LocalDensity.current.density;MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){AudioSoundEditor(.5f,false,audio,{},registerClose={},registerGuard={})}}}
        ui.runOnIdle{assertEquals(2f,actualDensity,"The real 320-dpi device geometry must be tested")}
        for(tag in listOf("sound-reserve-down","sound-reserve-up","sound-reserve-value")){
            val bounds=ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width/actualDensity>=48f&&bounds.height/actualDensity>=48f,"Reserve target must be at least 48 dp at actual density: $tag $bounds")
        }
        ui.onNodeWithTag("sound-eq-enabled").performClick();ui.runOnIdle{assertTrue(audio.soundEditor.preset!!.eqEnabled)}
        ui.onNodeWithTag("sound-reserve-up").performClick();ui.runOnIdle{assertEquals(13.0,audio.soundEditor.preset!!.reserveDb)}
        ui.runOnIdle{audio.soundEditor.updatePoint(audio.soundEditor.preset!!.eqPoints.first().copy(gainDb=4.0));assertEquals(13.0,audio.soundEditor.preset!!.reserveDb)}
        val curve=audio.soundEditor.preset!!.eqPoints
        ui.onNodeWithTag("sound-eq-enabled").performClick();ui.runOnIdle{assertFalse(audio.soundEditor.preset!!.eqEnabled);assertEquals(curve,audio.soundEditor.preset!!.eqPoints)}
        ui.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach{node->val b=node.boundsInRoot;if(b.width>0&&b.height>0)assertTrue(KnobGeometry.safe(b.left,b.top,b.right,b.bottom),"EQ controls overlap physical knob clearance: $b")}
        ui.runOnIdle{audio.dispose()}
    }
    @Test fun fixedEqLabelsKeepTheirFontsAndAlignedSixtyPixelReserveInsideIndependentPhysicalTargets(){
        val context=RuntimeEnvironment.getApplication();val original=com.no5.ui.UiLanguage.current(context)
        val port=SoundUiPort();val source=object:AudioSourcePort by MockAudioSource(now={10000}){override val sound=port}
        val audio=AudioNativeController(Dispatchers.Unconfined,now={10000},automaticTicker=false,source=source)
        val renderEpoch=mutableStateOf(0)
        val language=mutableStateOf("de");val night=mutableStateOf(false);val fontScale=mutableStateOf(1f)
        val texts=mutableListOf<SoundTextFit>();val controls=mutableListOf<SoundControlFit>()
        val displayEvidence=org.json.JSONArray()
        SoundTemplateDiagnostics.observeFit={texts+=it};SoundTemplateDiagnostics.observeControl={controls+=it}
        ui.runOnIdle{audio.openSoundEditor(false);audio.soundEditor.choosePoint("point")}
        ui.setContent{
            com.no5.ui.UiLanguage.acceptConfirmed(context,language.value)
            CompositionLocalProvider(LocalDensity provides Density(2f,fontScale.value)){
                key(renderEpoch.value){MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){
                    val scene=HelmScene(context,night.value)
                    Canvas(Modifier.size(1000.dp,600.dp)){drawIntoCanvas{scene.drawChrome(it.nativeCanvas,"AUDIO",renderValues(HelmState.unavailable()),"TEST FIXTURE")}}
                    AudioScreen(.5f,night.value,audio)
                    Canvas(Modifier.size(1000.dp,600.dp)){drawIntoCanvas{KnobOverlay(context).draw(it.nativeCanvas,night.value,audio,useSelectedAudioFader=true)}}
                }}}
            }
        }
        val labels=setOf("+ Punkt","Punkt entfernen","Grau","Pegel","Linie","EQ aus","EQ ein","EQ neutral","Breite sperren","Breite gesperrt","Speichern & zurück","EQ","Raum","RESERVE")
        labels.filterNot{it=="EQ"}.forEach{assertTrue(com.no5.ui.No5Text.hasTranslation(it),"Fixed label has no DE/EN/AF catalog key: $it")} // EQ is the language-neutral technical abbreviation.
        try{
            for(lang in listOf("de","en","af"))for(isNight in listOf(false,true))for(font in listOf(1f,1.25f)){
                ui.runOnIdle{texts.clear();controls.clear();language.value=lang;night.value=isNight;fontScale.value=font;renderEpoch.value++}
                ui.waitForIdle()
                // Robolectric records Compose Canvas commands only when the real View draws.
                // Inspect the same rendered frame that supplies the diagnostic font/bounds.
                ui.runOnUiThread{
                    val view=(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity.window.decorView
                    val image=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(image))
                    val dir=java.io.File(System.getProperty("no5.renderOutputDir"),"audio-textfit").apply{mkdirs()}
                    java.io.File(dir,"$lang-eq-${if(isNight)"night"else"day"}-font$font.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                    image.recycle()
                }
                for(tag in listOf("sound-reserve-down","sound-reserve-up","sound-reserve-value")){
                    val hit=ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                    assertTrue(hit.width/2>=48&&hit.height/2>=48,"$lang/$font/$tag lost its actual48dp target: $hit")
                    assertTrue(KnobGeometry.safe(hit.left,hit.top,hit.right,hit.bottom))
                    assertTrue(hit.bottom<990,"The target must remain above the existing divider")
                }
                for(tag in listOf("sound-reserve-down","sound-reserve-up")){
                    val drawn=controls.lastOrNull{it.tag==tag} ?: error("No actual draw for $lang/$isNight/$font/$tag: ${controls.map{it.tag}}")
                    assertEquals(898f,drawn.visual.top);assertEquals(60f,drawn.visual.height())
                    assertEquals(96f,drawn.hit.height());assertEquals(928f,drawn.visual.centerY())
                    assertTrue(drawn.visual.bottom<990)
                }
                fun drawn(source:String)=texts.lastOrNull{it.source==source} ?: error("No actual text draw for $lang/$isNight/$font/$source: ${texts.map{it.source}}")
                val expectedMaximum=mapOf("de" to "Reglermaximum: +12 dB","en" to "Point maximum: +12 dB","af" to "Beheermaximum: +12 dB").getValue(lang)
                val display=texts.lastOrNull{it.fullText==expectedMaximum} ?: error("Missing EXP1 maximum: $expectedMaximum")
                assertEquals(expectedMaximum,display.drawnText)
                assertEquals(22f*font,display.textSize);assertEquals(1040f,display.bounds.left);assertEquals(390f,display.baseline)
                assertTrue(display.bounds.width()<=500f,"Maximum caption must fit at its approved font: $lang $display")
                assertTrue(KnobGeometry.safe(display.bounds.left,display.bounds.top,display.bounds.right,display.bounds.bottom))
                displayEvidence.put(org.json.JSONObject().put("language",lang).put("night",isNight).put("fontScale",font)
                    .put("fullText",display.fullText).put("drawnText",display.drawnText).put("textSize",display.textSize)
                    .put("baseline",display.baseline).put("left",display.bounds.left).put("right",display.bounds.right))
                val minus=drawn("−");val plus=drawn("+")
                val value=drawn("12 dB")
                assertEquals(32f*font,minus.textSize);assertEquals(minus.textSize,plus.textSize);assertEquals(minus.textSize,value.textSize)
                assertEquals(minus.baseline,plus.baseline,.001f);assertEquals(minus.baseline,value.baseline,.001f)
                assertTrue(texts.any{it.source=="RESERVE"})
                for(item in texts.filter{it.source in labels||it.source in setOf("−","+","12 dB")}){
                    assertEquals(item.fullText,item.drawnText,"Fixed action was truncated: $lang/$font ${item.source}")
                    assertTrue(item.bounds.width()<=item.maxWidth+.5f,"Fixed text does not fit at its intended font: $lang/$font $item")
                }

            }
            java.io.File(System.getProperty("no5.renderOutputDir"),"audio-textfit/eq-display-label-fit.json").writeText(displayEvidence.toString(2))
        }finally{SoundTemplateDiagnostics.observeFit=null;SoundTemplateDiagnostics.observeControl=null;ui.runOnIdle{audio.dispose();com.no5.ui.UiLanguage.acceptConfirmed(context,original)}}
    }
    @Test fun presetLibraryUsesDirectNativeActionsAndKeepsFixedFonts(){
        val context=RuntimeEnvironment.getApplication();val original=com.no5.ui.UiLanguage.current(context)
        val port=SoundUiPort();val saved=port.state.value.preset!!
        port.state.emit(port.state.value.copy(capabilities=port.state.value.capabilities.copy(presetLibrary=true,presetManagement=true),
            presetCatalog=listOf(SoundPresetEntry(saved.id,saved.name,saved.routingTemplate))))
        val source=object:AudioSourcePort by MockAudioSource(now={10000}){override val sound=port}
        val audio=AudioNativeController(Dispatchers.Unconfined,now={10000},automaticTicker=false,source=source)
        val language=mutableStateOf("de");val night=mutableStateOf(false);val fontScale=mutableStateOf(1f)
        val fits=mutableListOf<SoundTextFit>();val libraryFits=mutableListOf<Triple<String,Float,Float>>()
        SoundTemplateDiagnostics.observeFit={fits+=it};PresetLibraryDiagnostics.text={text,width,available->libraryFits+=Triple(text,width,available)}
        ui.setContent{
            com.no5.ui.UiLanguage.acceptConfirmed(context,language.value)
            CompositionLocalProvider(LocalDensity provides Density(2f,fontScale.value)){
                MaterialTheme{Box(Modifier.size(1000.dp,464.dp)){
                    AudioPresetPanel(.5f,night.value,audio,{},Modifier.fillMaxSize())
                }}
            }
        }
        try{
            for(lang in listOf("de","en","af"))for(isNight in listOf(false,true))for(font in listOf(1f,1.25f)){
                ui.runOnIdle{fits.clear();libraryFits.clear();language.value=lang;night.value=isNight;fontScale.value=font}
                ui.waitForIdle()
                ui.onNodeWithTag("audio-preset-panel").assertIsDisplayed()
                ui.onNodeWithTag("audio-edit-${saved.id}").assertIsEnabled().assertHasClickAction()
                ui.onNodeWithTag("audio-load-${saved.id}").assertIsNotEnabled()
                ui.onNodeWithTag("audio-delete-${saved.id}").assertIsEnabled().assertHasClickAction()
                ui.onNodeWithTag("audio-new-preset").assertIsEnabled().assertHasClickAction()
                ui.onNodeWithTag("audio-open-preset-editor").assertDoesNotExist()
                ui.runOnIdle{
                    val root=(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity.window.decorView
                    val image=android.graphics.Bitmap.createBitmap(root.width,root.height,android.graphics.Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(image));image.recycle()
                    assertTrue(libraryFits.isNotEmpty());assertTrue(fits.isNotEmpty())
                    libraryFits.forEach{(text,width,available)->assertTrue(width<=available+.01f,"Preset label clipped: $lang/$font $text")}
                    fits.forEach{fit->assertEquals(fit.fullText,fit.drawnText);assertTrue(fit.bounds.width()<=fit.maxWidth+.5f,"Native fixed label clipped: $fit")}
                    assertEquals(27f*font,fits.last{it.source=="Bearbeiten"}.textSize)
                    assertTrue(port.commands.isEmpty())
                }
            }
        }finally{SoundTemplateDiagnostics.observeFit=null;PresetLibraryDiagnostics.text=null;ui.runOnIdle{audio.dispose();com.no5.ui.UiLanguage.acceptConfirmed(context,original)}}
    }
    @Test fun simpleMeasuredFormKeepsBoatVisibleAndOnlyAppliesTwoLocalDistancesExplicitly(){
        val port=SoundUiPort()
        val exact=SoundCalibration(SoundSpeakerId.entries.mapIndexed{i,id->SoundSpeaker(id,SoundPosition(i*.13,1.0+i*.85,.2),manualGainDb=-i.toDouble())},SoundPosition(.21,2.4,1.0),true)
        port.state.emit(soundFixture().copy(calibration=exact))
        val source=object:AudioSourcePort by MockAudioSource(now={10000}){override val sound=port}
        val audio=AudioNativeController(Dispatchers.Unconfined,now={10000},automaticTicker=false,source=source)
        ui.runOnIdle{com.no5.ui.UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de");audio.openSoundEditor(true,true);audio.soundEditor.changeSection("measure")}
        ui.setContent{MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){AudioSoundEditor(.5f,false,audio,{},registerClose={},registerGuard={})}}}
        ui.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        ui.onNodeWithTag("sound-measure-width").performTextReplacement("1,7")
        ui.onNodeWithTag("sound-measure-length").performTextReplacement("4,6")
        ui.runOnIdle{assertEquals(exact,audio.soundEditor.calibration);assertTrue(port.commands.isEmpty());audio.soundEditor.changeSection("gain");audio.soundEditor.changeSection("measure");assertEquals("1,7",audio.soundEditor.measurementDraft!!.width)}
        ui.onNodeWithTag("sound-measure-cancel").performClick()
        ui.runOnIdle{assertEquals("gain",audio.soundEditor.section);assertEquals(exact,audio.soundEditor.calibration);assertTrue(port.commands.isEmpty());audio.soundEditor.changeSection("measure")}
        ui.onNodeWithTag("sound-measure-width").performTextReplacement("1.7")
        ui.onNodeWithTag("sound-measure-length").performTextReplacement("4.6")
        ui.onNodeWithTag("sound-measure-apply").performClick()
        ui.runOnIdle{val cal=audio.soundEditor.calibration!!;assertTrue(SoundMeasuredGeometry.rectangular(cal));assertEquals(exact.listenerM,cal.listenerM);assertEquals(exact.speakers.last(),cal.speakers.last());assertEquals(1,port.commands.size);assertEquals("room",audio.soundEditor.calibrationReturn);audio.dispose()}
    }
    @Test fun bootPlacementRequiresRealDistancesAndExplicitlyPlacesListenerAndSub(){
        val port=SoundUiPort();val source=object:AudioSourcePort by MockAudioSource(now={10000}){override val sound=port}
        val audio=AudioNativeController(Dispatchers.Unconfined,now={10000},automaticTicker=false,source=source)
        ui.runOnIdle{com.no5.ui.UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de");audio.openSoundEditor(true);audio.soundEditor.changeSection("place");audio.soundEditor.beginPositioning(SoundPositionTarget.LISTENER);assertNull(audio.soundEditor.placingPosition)}
        ui.setContent{MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){AudioSoundEditor(.5f,false,audio,{},registerClose={},registerGuard={})}}}
        ui.onNodeWithTag("sound-place-listener").assertIsNotEnabled()
        ui.runOnIdle{audio.soundEditor.changeSection("measure");audio.soundEditor.editMeasurement(true,"1.7");audio.soundEditor.editMeasurement(false,"4.6");assertTrue(audio.soundEditor.applyMeasurements());audio.soundEditor.changeSection("place");assertNull(audio.soundEditor.calibration!!.listenerM);assertNull(audio.soundEditor.calibration!!.speakers.last().positionM)}
        ui.onNodeWithTag("sound-place-listener").performClick()
        ui.onNodeWithTag("sound-position-target").performTouchInput{click(percentOffset(.5f,.5f))}
        ui.runOnIdle{val pos=audio.soundEditor.calibration!!.listenerM!!;assertEquals(.85,pos.x,.02);assertEquals(2.3,pos.y,.02);assertNull(audio.soundEditor.calibration!!.speakers.last().positionM)}
        ui.onNodeWithTag("sound-place-sub").performClick()
        ui.onNodeWithTag("sound-position-target").performTouchInput{click(percentOffset(.65f,.70f))}
        lateinit var before:SoundPosition
        ui.runOnIdle{before=audio.soundEditor.calibration!!.speakers.last().positionM!!}
        ui.onNodeWithTag("sound-sub-position").performTouchInput{swipe(center,center+androidx.compose.ui.geometry.Offset(0f,20f),250)}
        ui.runOnIdle{
            val after=audio.soundEditor.calibration!!.speakers.last().positionM!!
            assertTrue(after.y>before.y,"Selected Sub marker must accept a small precision drag: before=$before after=$after")
            assertTrue(audio.soundEditor.calibration!!.completeGeometry,"Both explicit placements must complete the five-channel geometry")
            assertFalse(audio.soundEditor.calibration!!.geometryEnabled)
            audio.soundEditor.useGeometry(true)
            assertTrue(audio.soundEditor.calibration!!.geometryEnabled,"Complete measured geometry must be explicitly activatable")
            audio.dispose()
        }
    }
    @Test fun localDimensionsSurviveProcessLossWithoutPreviewAndRequireExplicitCloseDecision(){
        val context=RuntimeEnvironment.getApplication();context.getSharedPreferences("no5-sound-drafts-v1",0).edit().clear().commit()
        val port=SoundUiPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.attachStorage(context);c.openSpeakers();c.changeSection("measure");c.editMeasurement(true,"1.8");c.editMeasurement(false,"4.9");c.close()
        assertTrue(port.commands.isEmpty())
        val restored=AudioSoundUiController(port,Dispatchers.Unconfined);restored.attachStorage(context);restored.openSpeakers();assertEquals("1.8",restored.measurementDraft!!.width);assertTrue(restored.measurementDirty)
        assertTrue(port.commands.isEmpty());restored.requestClose(SoundScope.CALIBRATION){};assertEquals(SoundScope.CALIBRATION,restored.closeDecision);restored.continueEditing();assertNotNull(restored.measurementDraft);restored.cancelMeasurements();assertFalse(restored.calibrationDirty);assertTrue(port.commands.isEmpty());restored.close()
        context.getSharedPreferences("no5-sound-drafts-v1",0).edit().clear().commit()
    }
    @Test fun foreignCalibrationChangeBlocksAnUnappliedMeasurementDraftWithoutOverwritingIt(){
        val port=SoundUiPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.openSpeakers();c.changeSection("measure");c.editMeasurement(true,"1.6");c.editMeasurement(false,"4.0")
        val foreign=SoundMeasuredGeometry.rectangle(SoundCalibration(),1.9,5.1)
        port.state.emit(port.state.value.copy(calibrationRevision=8,calibration=foreign))
        assertTrue(c.conflicted);assertFalse(c.applyMeasurements());assertTrue(port.commands.isEmpty());assertEquals("1.6",c.measurementDraft!!.width)
        c.cancelMeasurements();assertFalse(c.conflicted);assertEquals(foreign,c.calibration);assertTrue(port.commands.isEmpty());c.close()
    }
    @Test fun partialLocalMeasurementsHaveUnsavedDecisionAndDiscardNeverSendsGeometry(){
        val port=SoundUiPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.openSpeakers();c.changeSection("measure");c.editMeasurement(true,"1,")
        assertTrue(c.calibrationDirty);assertFalse(c.applyMeasurements());assertNotNull(c.measurementError);assertTrue(port.commands.isEmpty())
        var closed=false;c.requestClose(SoundScope.CALIBRATION){closed=true};assertEquals(SoundScope.CALIBRATION,c.closeDecision);c.discard();assertTrue(closed);assertNull(c.measurementDraft);assertFalse(c.calibrationDirty);assertTrue(port.commands.isEmpty());c.close()
    }
    @Test fun approvedEqGesturesPlaceNeutralPointsAndDirectlyChangeBandwidth(){
        val port=SoundUiPort();val source=object:AudioSourcePort by MockAudioSource(now={10000}){override val sound=port}
        val audio=AudioNativeController(Dispatchers.Unconfined,now={10000},automaticTicker=false,source=source)
        ui.runOnIdle {com.no5.ui.UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de");audio.openSoundEditor(false);audio.soundEditor.choosePoint("point")}
        ui.setContent{MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){AudioSoundEditor(.5f,false,audio,{},registerClose={},registerGuard={})}}}
        val chart=ui.onNodeWithTag("sound-eq-chart")
        chart.performTouchInput{click(androidx.compose.ui.geometry.Offset(400f,80f))}
        ui.mainClock.advanceTimeBy(400)
        ui.runOnIdle {assertEquals(1,audio.soundEditor.preset!!.eqPoints.size);assertTrue(port.commands.isEmpty())}
        ui.onNodeWithTag("sound-add-point").performClick()
        chart.performTouchInput{click(androidx.compose.ui.geometry.Offset(400f,80f))}
        ui.mainClock.advanceTimeBy(400)
        ui.runOnIdle {val added=audio.soundEditor.preset!!.eqPoints.last();assertEquals(0.0,added.gainDb);assertEquals(20.0*Math.pow(1000.0,400.0/1718),added.frequencyHz,.01)}
        chart.performTouchInput{doubleClick(androidx.compose.ui.geometry.Offset(700f,330f))}
        ui.mainClock.advanceTimeBy(400)
        ui.runOnIdle {assertEquals(3,audio.soundEditor.preset!!.eqPoints.size);assertEquals(0.0,audio.soundEditor.preset!!.eqPoints.last().gainDb);audio.soundEditor.choosePoint("point")}
        val before=audio.soundEditor.preset!!.eqPoints.first()
        // The hit is on the visible handle at the selected gain, not on a separate slider.
        val lowX=(kotlin.math.ln(before.lowHz/20)/kotlin.math.ln(1000.0)*1718).toFloat()
        chart.performTouchInput{swipe(androidx.compose.ui.geometry.Offset(lowX,205f),androidx.compose.ui.geometry.Offset(lowX-90,205f),300)}
        ui.runOnIdle {assertTrue(audio.soundEditor.preset!!.eqPoints.first().widthHz>before.widthHz)}
        ui.onNodeWithTag("sound-width-lock").performClick()
        val locked=audio.soundEditor.preset!!.eqPoints.first()
        val lockedLow=(kotlin.math.ln(locked.lowHz/20)/kotlin.math.ln(1000.0)*1718).toFloat()
        chart.performTouchInput{swipe(androidx.compose.ui.geometry.Offset(lockedLow,205f),androidx.compose.ui.geometry.Offset(lockedLow-90,205f),300)}
        ui.runOnIdle {assertEquals(locked,audio.soundEditor.preset!!.eqPoints.first());audio.dispose()}
    }
    @Test fun sharedMeasuredBoatPreservesFiveChannelsAndListenerAcrossRoomReturn(){
        val port=SoundUiPort();val coords=listOf(SoundPosition(0.0,0.0),SoundPosition(1.7,0.0),SoundPosition(0.0,4.6),SoundPosition(1.7,4.6),SoundPosition(.9,3.0))
        val cal=SoundCalibration(SoundSpeakerId.entries.mapIndexed{i,id->SoundSpeaker(id,coords[i])},SoundPosition(1.08,3.0),true)
        port.state.emit(soundFixture().copy(calibration=cal))
        val source=object:AudioSourcePort by MockAudioSource(now={10000}){override val sound=port}
        val audio=AudioNativeController(Dispatchers.Unconfined,now={10000},automaticTicker=false,source=source)
        ui.runOnIdle{com.no5.ui.UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de");audio.openSoundEditor(true,true)}
        ui.setContent{MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){AudioSoundEditor(.5f,false,audio,{},registerClose={},registerGuard={})}}}
        ui.onNodeWithTag("sound-speaker-SUB").performClick();ui.onNodeWithTag("sound-trim-up").performClick()
        ui.runOnIdle{assertEquals(SoundSpeakerId.SUB,audio.soundEditor.selectedSpeaker);assertEquals(.05,audio.soundEditor.calibration!!.speakers.single{it.id==SoundSpeakerId.SUB}.manualDelayMs);assertTrue(audio.soundEditor.calibration!!.speakers.filter{it.id!=SoundSpeakerId.SUB}.all{it.manualDelayMs==0.0})}
        ui.runOnIdle{audio.soundEditor.changeSection("place")}
        ui.onNodeWithTag("sound-listener").performTouchInput{swipe(center,center+androidx.compose.ui.geometry.Offset(0f,35f),300)}
        val moved=audio.soundEditor.calibration!!.listenerM!!;assertTrue(moved.y>3.0)
        ui.runOnIdle{audio.openSoundEditor(false,true);audio.soundEditor.changeSection("room");assertEquals(moved,audio.soundEditor.calibration!!.listenerM)}
        // No simplified second boat model: all approved literal SVG paths are used by both views.
        val root=sequenceOf(java.io.File("."),java.io.File(".."),java.io.File("../..")).first{java.io.File(it,"design/camilla-presets-20261016/prototype.template.html").exists()}
        val template=java.io.File(root,"design/camilla-presets-20261016/prototype.template.html").readText()
        SoundBoatPaths.all.forEach{assertTrue(template.contains(it),"Native boat changed an approved path: $it")}
        ui.runOnIdle{audio.dispose()}
    }
    @Test fun dirtyCloseWaitsForConfirmedOwnSaveAndDoesNotUndoAudioActions(){
        val port=SoundUiPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined)
        c.openPreset();c.addPoint(500.0,3.0);assertTrue(c.presetDirty);assertEquals(1,port.commands.size)
        var closed=0;c.requestClose(SoundScope.PRESET){closed++};assertEquals(SoundScope.PRESET,c.closeDecision);assertEquals(0,closed)
        c.saveAndClose();assertTrue(c.busy);val id=port.commands.size.toLong()
        port.results.emit(AudioSoundResult(id,"UNKNOWN"));assertFalse(c.busy);assertEquals(0,closed);assertTrue(c.presetDirty)
        c.continueEditing();assertNull(c.closeDecision);c.requestClose(SoundScope.PRESET){closed++}
        val count=port.commands.size;c.saveAndClose();assertEquals(count,port.commands.size,"UNKNOWN requires a fresh read before another write")
        port.state.emit(port.state.value);c.saveAndClose();assertTrue(c.busy)
        assertIs<AudioSoundCommand.Preview>(port.commands[port.commands.lastIndex-1]);assertIs<AudioSoundCommand.Save>(port.commands.last())
        port.state.emit(port.state.value.copy(preset=c.preset,presetRevision=port.state.value.presetRevision+1,preview=null))
        port.results.emit(AudioSoundResult(port.commands.size.toLong(),"APPLIED"))
        assertFalse(c.presetDirty);assertEquals(1,closed);c.close()
    }
    @Test fun lateOwnPreviewCannotTurnAnOriginalTargetIntoTheSavedBaseline(){
        val port=SoundUiPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.openPreset()
        val original=requireNotNull(c.preset);val boosted=original.copy(eqPoints=original.eqPoints.map{it.copy(gainDb=6.0)})
        c.changePreset(boosted);val preview=(port.commands.first() as AudioSoundCommand.Preview).previewId
        c.changePreset(original);assertFalse(c.presetDirty)
        // This is the old boost reaching DSP/readback after the newer neutral target was admitted.
        port.state.emit(port.state.value.copy(preset=boosted,preview=SoundPreview(preview,10000,true,false)))
        assertEquals(original,c.preset);assertFalse(c.presetDirty)
        c.changePreset(boosted);assertTrue(c.presetDirty);c.openSpeakers(true);assertEquals(boosted,c.preset)
        c.save(SoundScope.PRESET);port.results.emit(AudioSoundResult(port.commands.size.toLong(),"UNKNOWN"))
        assertTrue(c.presetDirty);assertEquals(boosted,c.preset);c.close()
    }
    @Test fun processDraftRestorationNeverResubmitsOrRestoresAnOldPreview(){
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("no5-sound-drafts-v1",0).edit().clear().commit()
        val port=SoundUiPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.attachStorage(context);c.openPreset();c.addPoint(550.0,2.0);val draft=c.preset;c.close()
        val count=port.commands.size;val restored=AudioSoundUiController(port,Dispatchers.Unconfined);restored.attachStorage(context);restored.openPreset()
        assertEquals(draft,restored.preset);assertTrue(restored.presetDirty);assertEquals(count,port.commands.size);restored.close()
        context.getSharedPreferences("no5-sound-drafts-v1",0).edit().clear().commit()
    }
    @Test fun invalidMeasurementInputMustBeDiscardedOrCorrectedBeforeSaving(){
        val port=SoundUiPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.openSpeakers();c.markGeometryInput("FRONT_LEFT",true)
        assertTrue(c.calibrationDirty);c.save(SoundScope.CALIBRATION);assertTrue(port.commands.isEmpty())
        var closed=false;c.requestClose(SoundScope.CALIBRATION){closed=true};assertEquals(SoundScope.CALIBRATION,c.closeDecision);c.discard();assertTrue(closed);assertFalse(c.hasInvalidGeometry);c.close()
    }
    @Test fun roomToAlignmentPreservesPresetDraftAndForeignChangeCannotBeOverwritten(){
        val port=SoundUiPort();val c=AudioSoundUiController(port,Dispatchers.Unconfined);c.openPreset();c.addPoint(500.0,3.0);val draft=c.preset
        c.openSpeakers(true);assertEquals("room",c.calibrationReturn);assertEquals(draft,c.preset)
        port.state.emit(port.state.value.copy(presetRevision=10));assertTrue(c.conflicted);val count=port.commands.size
        c.save(SoundScope.PRESET);assertEquals(count,port.commands.size)
        var closed=false;c.requestClose(SoundScope.PRESET){closed=true};c.discard();assertTrue(closed);assertFalse(c.presetDirty);c.close()
    }
}
