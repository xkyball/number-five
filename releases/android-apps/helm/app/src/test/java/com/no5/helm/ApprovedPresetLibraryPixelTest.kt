package com.no5.helm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import com.no5.helm.network.soundFixture
import com.no5.ui.UiLanguage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovedPresetLibraryPixelTest {
    @get:Rule val ui=createComposeRule()
    data class Frame(val lang:String="de",val night:Boolean=false,val font:Float=1f,val epoch:Int=0)
    /** Approved E1 components are reusable in identical states; every pixel still compares at zero tolerance. */
    private fun approvedReference(root:File,name:String):File {
        val correction=File(root.parentFile,"preset-workflow-P1-E1")
        val manifestFile=File(correction,"MANIFEST.json")
        fun hash(file:File)=java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
        val approved=JSONObject(File(correction,"APPROVAL.json").readText())
        assertEquals("APPROVED",approved.getString("status"))
        assertEquals("9ae4b496aea76c1f42ab7a9264f8283c7730cfd24eb333c8f9d1efe819fd5552",hash(manifestFile))
        assertEquals(hash(manifestFile),approved.getString("referenceManifestSha256"))
        val rows=JSONObject(manifestFile.readText()).getJSONArray("frames")
        for(i in 0 until rows.length())rows.getJSONObject(i).let{row->if(row.getString("name")==name){
            val original=File(root,"assets/$name");assertEquals(row.getString("beforeSha256"),hash(original))
            return File(correction,row.getString("after")).also{assertEquals(row.getString("afterSha256"),hash(it))}
        }}
        if(name in setOf("empty-af-day-font1.25.png","empty-af-night-font1.25.png")){
            // The empty catalog shares this exact caption with the populated
            // catalog. Reuse its 31 approved descender pixels, never actual
            // render output, a mask, a new font or a relaxed expectation.
            val frozen=File(root,"MANIFEST.json")
            assertEquals("3d65d0e21327caa103cf0efbaa597fb3475c458de07e233510a7fce8d2486f75",hash(frozen))
            val images=JSONObject(frozen.readText()).getJSONArray("images")
            fun original(n:String):Bitmap {
                val f=File(root,"assets/$n")
                val record=(0 until images.length()).map{images.getJSONObject(it)}.single{it.getString("path")=="assets/$n"}
                assertEquals(record.getString("sha256"),hash(f));return BitmapFactory.decodeFile(f.absolutePath)
            }
            val catalogName=name.replace("empty-","catalog-")
            val before=original(catalogName);val after=BitmapFactory.decodeFile(approvedReference(root,catalogName).absolutePath)
            val empty=original(name);val derived=empty.copy(Bitmap.Config.ARGB_8888,true)
            var changes=0
            for(y in 0 until before.height)for(x in 0 until before.width){
                if(before.getPixel(x,y)!=after.getPixel(x,y)){
                    assertEquals("Caption component must have identical original pixels",before.getPixel(x,y),empty.getPixel(x,y))
                    derived.setPixel(x,y,after.getPixel(x,y));changes++
                }
            }
            assertEquals(31,changes)
            val file=File(System.getProperty("no5.renderOutputDir"),"p1-approved-caption-reuse/$name");file.parentFile!!.mkdirs()
            file.outputStream().use{derived.compress(Bitmap.CompressFormat.PNG,100,it)}
            before.recycle();after.recycle();empty.recycle();derived.recycle()
            return file
        }
        return File(root,"assets/$name")
    }
    @Test fun approvedP1CatalogUsesTheRealNativePanel()=catalogPixels(false)
    @Test fun approvedP1EmptyCatalogUsesTheRealNativePanel()=catalogPixels(true)
    private fun catalogPixels(empty:Boolean){
        val root=File("../../design/app-unification-20261019/preset-workflow-P1")
        val fixtures=JSONObject(File(root,"fixtures.json").readText()).getJSONArray("presets")
        val catalog=List(if(empty)0 else fixtures.length()){i->SoundPresetEntry("fixture_$i",fixtures.getJSONObject(i).getString("name"),"immersive_4_1")}
        val previews=catalog.mapIndexed{i,entry->entry.id to fixtures.getJSONObject(i).let{o->SoundPresetPreview("a".repeat(64),List(48){o.getJSONArray("gainsDb").getDouble(it)},o.getBoolean("eqEnabled"),o.getDouble("reserveDb"))}}.toMap()
        val sound=object:AudioSoundPort{
            override val state=MutableSourceFeed(soundFixture().let{it.copy(preset=it.preset!!.copy(id=catalog.firstOrNull()?.id?:"neutral",routingTemplate="immersive_4_1"),capabilities=it.capabilities.copy(presetLibrary=true,presetManagement=true),presetCatalog=catalog,presetPreviews=previews)})
            override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null);override val results=MutableSourceFeed<AudioSoundResult?>(null)
            override fun setEditorVisible(visible:Boolean,eqVisible:Boolean)=Unit
            override fun submit(command:AudioSoundCommand)=0L
            override fun refresh()=Unit
            override fun close()=Unit
        }
        val source=object:AudioSourcePort by MockAudioSource(now={10000L}){override val sound=sound}
        val controller=AudioNativeController(automaticTicker=false,automaticVerification=false,source=source)
        val frame=mutableStateOf(Frame());val out=File(System.getProperty("no5.renderOutputDir"),"p1-catalog").apply{mkdirs()}
        val result=JSONArray();val failures=mutableListOf<String>()
        ui.setContent{
            val f=frame.value;UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),f.lang)
            CompositionLocalProvider(LocalDensity provides Density(2f,f.font)){
                MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){
                    val base=remember(f){BitmapFactory.decodeFile(File(root.parentFile,"native-assembly-review-R1/assets/sound-room-${f.lang}-${if(f.night)"night"else"day"}-font${f.font}.png").absolutePath)}
                    DisposableEffect(base){onDispose{base.recycle()}}
                    Canvas(Modifier.fillMaxSize()){drawContext.canvas.nativeCanvas.drawBitmap(base,0f,0f,null)}
                    key(f.epoch){AudioPresetPanel(.5f,f.night,controller,{},Modifier.offset(y=65.dp).size(1000.dp,464.dp))}
                }}
            }
        }
        try {
            for(lang in listOf("de","en","af"))for(night in listOf(false,true))for(font in listOf(1f,1.25f)){
                ui.runOnIdle{frame.value=Frame(lang,night,font,frame.value.epoch+1)};ui.waitForIdle()
                val name="${if(empty)"empty"else"catalog"}-$lang-${if(night)"night"else"day"}-font$font.png"
                val expected=BitmapFactory.decodeFile(approvedReference(root,name).absolutePath)
                var count=0
                ui.runOnIdle{
                    val decor=(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity.window.decorView
                    val actual=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);decor.draw(android.graphics.Canvas(actual))
                    val repeated=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);decor.draw(android.graphics.Canvas(repeated));assertTrue("Static native frame changed between captures",actual.sameAs(repeated));repeated.recycle()
                    val a=IntArray(2400000);val e=IntArray(2400000);actual.getPixels(a,0,2000,0,0,2000,1200);expected.getPixels(e,0,2000,0,0,2000,1200)
                    count=a.indices.count{a[it]!=e[it]}
                    File(out,name).outputStream().use{actual.compress(Bitmap.CompressFormat.PNG,100,it)};actual.recycle()
                }
                expected.recycle();result.put(JSONObject().put("name",name).put("differentPixels",count));if(count!=0)failures+="$name: $count"
            }
        }finally{ui.runOnIdle{controller.dispose()};File(out,if(empty)"EMPTY_RESULT.json"else"RESULT.json").writeText(result.toString(2))}
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }
    @Test fun approvedP1HorizontalRoomUsesTheProductionBoatInkAndListenerAction(){
        val root=File("../../design/app-unification-20261019/preset-workflow-P1")
        val frame=mutableStateOf(Frame());val out=File(System.getProperty("no5.renderOutputDir"),"p1-room").apply{mkdirs()}
        val result=JSONArray();val failures=mutableListOf<String>()
        val geometry=SoundBoatFrame(1000f,565f,4.1f,true)
        val positions=SoundSpeakerId.entries.zip(listOf(29f to 105f,71f to 105f,27f to 224f,73f to 224f,58f to 200f)).associate{(id,p)->id to geometry.point(p.first.toDouble(),p.second.toDouble())}
        ui.setContent{
            val f=frame.value;UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),f.lang)
            CompositionLocalProvider(LocalDensity provides Density(2f,f.font)){
                MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){
                    val base=remember(f){BitmapFactory.decodeFile(File(root.parentFile,"native-assembly-review-R1/assets/sound-room-${f.lang}-${if(f.night)"night"else"day"}-font${f.font}.png").absolutePath)}
                    DisposableEffect(base){onDispose{base.recycle()}}
                    Canvas(Modifier.fillMaxSize()){drawContext.canvas.nativeCanvas.drawBitmap(base,0f,0f,null)}
                    SoundRoomIllustration(.5f,f.night,geometry,positions,geometry.point(50.0,150.0),{})
                }}
            }
        }
        try{
            for(lang in listOf("de","en","af"))for(night in listOf(false,true))for(font in listOf(1f,1.25f)){
                ui.runOnIdle{frame.value=Frame(lang,night,font,frame.value.epoch+1)};ui.waitForIdle()
                val name="room-$lang-${if(night)"night"else"day"}-font$font.png"
                val expected=BitmapFactory.decodeFile(approvedReference(root,name).absolutePath)
                var count=0
                ui.runOnIdle{
                    val decor=(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity.window.decorView
                    val actual=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);decor.draw(android.graphics.Canvas(actual))
                    val repeated=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);decor.draw(android.graphics.Canvas(repeated));assertTrue("Static native frame changed between captures",actual.sameAs(repeated));repeated.recycle()
                    val a=IntArray(2400000);val e=IntArray(2400000);actual.getPixels(a,0,2000,0,0,2000,1200);expected.getPixels(e,0,2000,0,0,2000,1200)
                    count=a.indices.count{a[it]!=e[it]};File(out,name).outputStream().use{actual.compress(Bitmap.CompressFormat.PNG,100,it)};actual.recycle()
                }
                expected.recycle();result.put(JSONObject().put("name",name).put("differentPixels",count));if(count!=0)failures+="$name: $count"
            }
        }finally{File(out,"RESULT.json").writeText(result.toString(2))}
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }

    @Test fun approvedP1SaveDiscardAndDeleteUseTheRealCompactDialog(){
        val root=File("../../design/app-unification-20261019/preset-workflow-P1")
        data class DialogFrame(val kind:String="save",val frame:Frame=Frame())
        val data=mutableStateOf(DialogFrame());val out=File(System.getProperty("no5.renderOutputDir"),"p1-dialogs").apply{mkdirs()}
        val results=JSONArray();val failures=mutableListOf<String>()
        ui.setContent{
            val d=data.value;val f=d.frame;val context=RuntimeEnvironment.getApplication()
            UiLanguage.acceptConfirmed(context,f.lang)
            CompositionLocalProvider(LocalDensity provides Density(2f,f.font)){
                MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){
                    key(f.epoch){
                        val title=when(d.kind){"save"->"Preset speichern";"discard"->"Neues Preset verwerfen?";else->"Preset löschen?"}
                        val body=when(d.kind){"discard"->com.no5.ui.No5Text.text(context,"Der leere Entwurf wird entfernt.");"delete"->com.no5.ui.No5Text.text(context,"„%s“ wird aus der Liste entfernt.","Evening");else->null}
                        val detail=when(d.kind){"save"->"Wird erst jetzt dauerhaft angelegt.";"discard"->"Danach ist der vorherige Preset wieder aktiv.";else->null}
                        PresetWorkflowDialog(f.night,title,body,detail?.let{com.no5.ui.No5Text.text(context,it)}?:com.no5.ui.No5Text.text(context,"Aktiver Klang danach: „%s“.","Immersive"),
                            when(d.kind){"save"->"Speichern";"discard"->"Verwerfen";else->"Löschen"},false,null,{},{},tag="p1-dialog",name=if(d.kind=="save")when(f.lang){"de"->"Mein Klang";"en"->"My sound";else->"My klank"}else null,onName={})
                    }
                }}
            }
        }
        try{
            for(kind in listOf("save","discard","delete"))for(lang in listOf("de","en","af"))for(night in listOf(false,true))for(font in listOf(1f,1.25f)){
                ui.runOnIdle{data.value=DialogFrame(kind,Frame(lang,night,font,data.value.frame.epoch+1))};ui.waitForIdle();focusHelmTestForegroundWindow(ui)
                val name="$kind-$lang-${if(night)"night"else"day"}-font$font.png"
                val expected=BitmapFactory.decodeFile(approvedReference(root,name).absolutePath)
                val underlay=BitmapFactory.decodeFile(File(root,"assets/${if(kind=="delete")"catalog"else"new"}-$lang-${if(night)"night"else"day"}-font$font.png").absolutePath)
                var count=0
                ui.runOnIdle{
                    val view=org.robolectric.shadows.ShadowDialog.getShownDialogs().last{it.isShowing}.window!!.decorView
                    val actual=underlay.copy(Bitmap.Config.ARGB_8888,true);view.draw(android.graphics.Canvas(actual))
                    assertEquals(2000,view.width);assertEquals(1200,view.height)
                    val a=IntArray(2400000);val e=IntArray(2400000);actual.getPixels(a,0,2000,0,0,2000,1200);expected.getPixels(e,0,2000,0,0,2000,1200)
                    count=a.indices.count{a[it]!=e[it]};File(out,name).outputStream().use{actual.compress(Bitmap.CompressFormat.PNG,100,it)};actual.recycle()
                }
                expected.recycle();underlay.recycle();results.put(JSONObject().put("name",name).put("differentPixels",count));if(count!=0)failures+="$name: $count"
            }
        }finally{File(out,"RESULT.json").writeText(results.toString(2))}
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }

    @Test @Config(sdk=[29]) fun newEditorMustMatchP1AndExposeOnlyValidPointActions()=editorPixels(true,29)
    // R1 specialty bodies originate from AudioSoundEditorBodyMatrixTest's API29 runtime.
    @Test @Config(sdk=[29]) fun existingEditorMustMatchP1WithTheConfirmedSelectedPoint()=editorPixels(false,29)
    private fun editorPixels(createNew:Boolean,sdk:Int=35){
        val scene=if(createNew)"new"else"edit"
        val root=File("../../design/app-unification-20261019/preset-workflow-P1")
        val frame=mutableStateOf(Frame());val out=File(System.getProperty("no5.renderOutputDir"),"p1-$scene-editor${if(sdk==29)"-api29"else""}").apply{mkdirs()}
        val result=JSONArray();val failures=mutableListOf<String>();var owner:AudioNativeController?=null
        var soundFeed:MutableSourceFeed<AudioSoundState>?=null;var soundResults:MutableSourceFeed<AudioSoundResult?>?=null;var preview:AudioSoundCommand.Preview?=null
        ui.setContent{
            val f=frame.value;UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),f.lang)
            CompositionLocalProvider(LocalDensity provides Density(2f,f.font)){
                MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){
                    val base=remember(f){BitmapFactory.decodeFile(File(root.parentFile,"native-assembly-review-R1/assets/sound-eq-${f.lang}-${if(f.night)"night"else"day"}-font${f.font}.png").absolutePath)}
                    DisposableEffect(base){onDispose{base.recycle()}}
                    Canvas(Modifier.fillMaxSize()){drawContext.canvas.nativeCanvas.drawBitmap(base,0f,0f,null)}
                    key(f.epoch){
                        val controller=remember{
                            RuntimeEnvironment.getApplication().getSharedPreferences("no5-sound-drafts-v1",0).edit().clear().commit()
                            val state=soundFixture().let{it.copy(preset=it.preset!!.copy(name=if(createNew)"OBSERVED_PRESET"else"IMMERSIVE",eqEnabled=true,eqPoints=listOf(SoundEqPoint("point",1000.0,3.0,1.0))),headroomDb=12.0,capabilities=it.capabilities.copy(presetLibrary=true,presetManagement=true))}
                            val sound=object:AudioSoundPort {
                                override val state=MutableSourceFeed(state);override val results=MutableSourceFeed<AudioSoundResult?>(null);override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null)
                                override fun refresh()=Unit;override fun setEditorVisible(visible:Boolean,eqVisible:Boolean)=Unit
                                override fun submit(command:AudioSoundCommand):Long{if(command is AudioSoundCommand.Preview)preview=command;return 1L}
                                override fun close()=Unit
                            }
                            soundFeed=sound.state;soundResults=sound.results
                            val delegate=MockAudioSource(now={10000L}).also{it.loadFixture(AudioFixture.DEMO)}
                            val source=object:AudioSourcePort by delegate{override val sound=sound;override val state=MutableSourceFeed(delegate.state.value.let{it.copy(dsp=it.dsp.copy(hardwareAvailable=true,clippedSamples=0))})}
                            AudioNativeController(kotlinx.coroutines.Dispatchers.Unconfined,{10000L},false,false,source).also{if(createNew)it.soundEditor.beginNewPreset();it.openSoundEditor(false);if(!createNew)it.soundEditor.choosePoint(it.soundEditor.preset!!.eqPoints.first().id);owner=it}
                        }
                        DisposableEffect(controller){onDispose{controller.dispose()}}
                        AudioSoundEditor(.5f,f.night,controller,{},registerClose={},registerGuard={})
                    }
                }}
            }
        }
        try{
            for(lang in listOf("de","en","af"))for(night in listOf(false,true))for(font in listOf(1f,1.25f)){
                ui.runOnIdle{owner?.dispose();RuntimeEnvironment.getApplication().getSharedPreferences("no5-sound-drafts-v1",0).edit().clear().commit();frame.value=Frame(lang,night,font,frame.value.epoch+1)};ui.waitForIdle()
                if(createNew)ui.runOnIdle{val p=preview!!;soundFeed!!.emit(soundFeed!!.value.copy(preset=p.preset,preview=SoundPreview(p.previewId,15000,true,false)));soundResults!!.emit(AudioSoundResult(1L,"APPLIED"))};ui.waitForIdle()
                if(createNew){ui.onNodeWithTag("sound-width-lock").assertDoesNotExist();ui.onNodeWithTag("sound-remove-point").assertIsNotEnabled();ui.onNodeWithTag("sound-add-point").assertHasClickAction()}
                val name="$scene-$lang-${if(night)"night"else"day"}-font$font.png"
                val expected=BitmapFactory.decodeFile(approvedReference(root,name).absolutePath)
                var count=0
                ui.runOnIdle{
                    if(createNew){assertTrue(owner!!.soundEditor.preset!!.eqPoints.isEmpty());assertTrue(owner!!.soundEditor.newPresetDraft)}else assertFalse(owner!!.soundEditor.newPresetDraft)
                    val decor=(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity.window.decorView
                    val actual=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);decor.draw(android.graphics.Canvas(actual))
                    val repeated=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);decor.draw(android.graphics.Canvas(repeated));assertTrue("Static native frame changed between captures",actual.sameAs(repeated));repeated.recycle()
                    val a=IntArray(2400000);val e=IntArray(2400000);actual.getPixels(a,0,2000,0,0,2000,1200);expected.getPixels(e,0,2000,0,0,2000,1200)
                    count=a.indices.count{a[it]!=e[it]};File(out,name).outputStream().use{actual.compress(Bitmap.CompressFormat.PNG,100,it)};actual.recycle()
                }
                expected.recycle();result.put(JSONObject().put("name",name).put("differentPixels",count));if(count!=0)failures+="$name: $count"
            }
        }finally{File(out,"RESULT.json").writeText(result.toString(2));RuntimeEnvironment.getApplication().getSharedPreferences("no5-sound-drafts-v1",0).edit().clear().commit()}
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }

}
