package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
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

/** Production captures for the approved DIR1 overlay/status scope. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EqDirectParityTest {
 @get:Rule val ui=createComposeRule()
 @Test fun nativeProposal(){
  val context=RuntimeEnvironment.getApplication();val lang=mutableStateOf("de");val night=mutableStateOf(false);val font=mutableStateOf(1f);val mode=mutableStateOf("pending")
  val fixture=soundFixture().let{it.copy(preset=it.preset!!.copy(name="EQ · DIR1",reserveDb=12.0,eqEnabled=true,eqPoints=listOf(SoundEqPoint("low",100.0,2.0,3.0),SoundEqPoint("mid",1000.0,4.0,3.0),SoundEqPoint("high",9000.0,-2.0,3.0))),headroomDb=12.0)}
  class Port:AudioSoundPort{
   override val state=MutableSourceFeed(fixture.copy(capabilities=fixture.capabilities.copy(eqProjection=true),eqEditing=SoundEqEditing()))
   override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null);override val results=MutableSourceFeed<AudioSoundResult?>(null)
   var command:AudioSoundCommand.Preview?=null
   override fun submit(command:AudioSoundCommand):Long{this.command=command as? AudioSoundCommand.Preview;return 1L}
   override fun setEditorVisible(visible:Boolean,eqVisible:Boolean){};override fun refresh(){};override fun close(){}
   fun applied(){val c=command!!;state.emit(state.value.copy(preset=c.preset,preview=SoundPreview(c.previewId,9999999999999,true,false)));results.emit(AudioSoundResult(1,"APPLIED"))}
  }
  fun make(m:String):AudioNativeController{
   val port=Port();val source=object:AudioSourcePort by MockAudioSource(now={10000}){override val sound=port}
   val audio=AudioNativeController(Dispatchers.Unconfined,{10000},false,false,source,eqValidator=FixtureEqValidator,eqValidationDispatcher=Dispatchers.Unconfined)
   audio.openSoundEditor(false);audio.soundEditor.choosePoint("mid")
   val point=audio.soundEditor.preset!!.eqPoints.first{it.id=="mid"}
   audio.soundEditor.updatePoint(point.copy(frequencyHz=1600.0,gainDb=if(m=="limited")18.0 else 7.0))
   if(m=="settled")port.applied()
   return audio
  }
  context.getSharedPreferences("no5-sound-drafts-v1",0).edit().clear().commit()
  val active=mutableStateOf(make("pending"))
  ui.setContent{
   com.no5.ui.UiLanguage.acceptConfirmed(context,lang.value)
   CompositionLocalProvider(LocalDensity provides Density(2f,font.value)){
    MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){
     Canvas(Modifier.size(1000.dp,600.dp)){HelmScene(context,night.value).drawChrome(drawContext.canvas.nativeCanvas,"AUDIO",renderValues(HelmState.unavailable()),"DIR1 · PROPOSAL")}
     AudioScreen(.5f,night.value,active.value)
     Canvas(Modifier.size(1000.dp,600.dp)){KnobOverlay(context).draw(drawContext.canvas.nativeCanvas,night.value,active.value,useSelectedAudioFader=true)}
    }}
   }
  }
  try{
   for(l in listOf("de","en","af"))for(dark in listOf(false,true))for(f in listOf(1f,1.25f))for(m in listOf("pending","limited","settled")){
    ui.runOnIdle{
     active.value.dispose();context.getSharedPreferences("no5-sound-drafts-v1",0).edit().clear().commit()
     lang.value=l;night.value=dark;font.value=f;mode.value=m;active.value=make(m)
    };ui.waitForIdle()
    ui.runOnUiThread{
     val view=(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity.window.decorView
     val b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(b))
     val dir=java.io.File(System.getProperty("no5.renderOutputDir"),"eq-direct-production").apply{mkdirs()}
     java.io.File(dir,"$l-${if(dark)"night"else"day"}-font$f-$m.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
    }
   }
  }finally{ui.runOnIdle{active.value.dispose()}}
 }
}
