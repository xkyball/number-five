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

/** DIR1 isolated native proposal; never mounted by production navigation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EqDirectDesignTest {
 @get:Rule val ui=createComposeRule()
 @Test fun nativeProposal(){
  val context=RuntimeEnvironment.getApplication();val lang=mutableStateOf("de");val night=mutableStateOf(false);val font=mutableStateOf(1f);val mode=mutableStateOf("pending")
  val fixture=soundFixture().let{it.copy(preset=it.preset!!.copy(name="EQ · DIR1",reserveDb=12.0,eqEnabled=true,eqPoints=listOf(SoundEqPoint("low",100.0,2.0,3.0),SoundEqPoint("mid",1000.0,4.0,3.0),SoundEqPoint("high",9000.0,-2.0,3.0))),headroomDb=12.0)}
  val port=object:AudioSoundPort{
   override val state=MutableSourceFeed(fixture);override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null);override val results=MutableSourceFeed<AudioSoundResult?>(null)
   override fun submit(command:AudioSoundCommand)=0L
   override fun setEditorVisible(visible:Boolean,eqVisible:Boolean){};override fun refresh(){};override fun close(){}
  }
  val source=object:AudioSourcePort by MockAudioSource(now={10000}){override val sound=port}
  val audio=AudioNativeController(Dispatchers.Unconfined,{10000},false,false,source)
  ui.runOnIdle{audio.openSoundEditor(false);audio.soundEditor.choosePoint("mid")}
  ui.setContent{
   com.no5.ui.UiLanguage.acceptConfirmed(context,lang.value)
   CompositionLocalProvider(LocalDensity provides Density(2f,font.value)){
    MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){
     Canvas(Modifier.size(1000.dp,600.dp)){HelmScene(context,night.value).drawChrome(drawContext.canvas.nativeCanvas,"AUDIO",renderValues(HelmState.unavailable()),"DIR1 · PROPOSAL")}
     AudioScreen(.5f,night.value,audio)
     Canvas(Modifier.size(1000.dp,600.dp)){
      val n=drawContext.canvas.nativeCanvas;n.save();n.scale(size.width/2000f,size.width/2000f)
      val palette=SoundEditorPalette(night.value)
      val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=palette.accent.toArgb();style=Paint.Style.STROKE;strokeWidth=3f}
      if(mode.value!="settled"){
       val x=(140f+kotlin.math.ln(1600.0/20)/kotlin.math.ln(1000.0)*1718).toFloat();val y=410f+(12f-(if(mode.value=="limited")12f else 7f))/24f*410f
       val oldX=(140f+kotlin.math.ln(1000.0/20)/kotlin.math.ln(1000.0)*1718).toFloat();val oldY=410f+(12f-4f)/24f*410f
       paint.alpha=100;paint.pathEffect=android.graphics.DashPathEffect(floatArrayOf(5f,5f),0f);n.drawLine(oldX,oldY,x,y,paint)
       paint.alpha=255;paint.pathEffect=null;n.drawCircle(x,y,14f,paint)
       paint.style=Paint.Style.FILL;n.drawCircle(x,y,3f,paint)
      }
      paint.style=Paint.Style.FILL;paint.color=palette.background.toArgb();n.drawRect(340f,1000f,1940f,1056f,paint)
      val captions=when(lang.value){
       "en"->listOf("Target pending · solid curve: confirmed sound","Band boundary reached · EQ remains active","Target applied · sound confirmed")
       "af"->listOf("Teiken hangend · soliede kurwe: bevestigde klank","Bandgrens bereik · EQ bly aktief","Teiken toegepas · klank bevestig")
       else->listOf("Ziel ausstehend · durchgezogene Kurve: bestätigter Klang","Bandgrenze erreicht · EQ bleibt aktiv","Ziel übernommen · Klang bestätigt")
      }
      val text=captions[when(mode.value){"limited"->1;"settled"->2;else->0}]
      paint.typeface=context.resources.getFont(R.font.barlow_condensed_semibold);paint.textSize=24f*font.value;paint.color=palette.secondary.toArgb()
      check(paint.measureText(text)<1590f);n.drawText(text,344f,1032f,paint);n.restore()
     }
     Canvas(Modifier.size(1000.dp,600.dp)){KnobOverlay(context).draw(drawContext.canvas.nativeCanvas,night.value,audio,useSelectedAudioFader=true)}
    }}
   }
  }
  try{
   for(l in listOf("de","en","af"))for(dark in listOf(false,true))for(f in listOf(1f,1.25f))for(m in listOf("pending","limited","settled")){
    ui.runOnIdle{lang.value=l;night.value=dark;font.value=f;mode.value=m
     val model=fixture.preset!!
     port.state.emit(fixture.copy(preset=if(m=="settled")model.copy(eqPoints=model.eqPoints.map{if(it.id=="mid")it.copy(frequencyHz=1600.0,gainDb=7.0)else it})else model))
    };ui.waitForIdle()
    ui.runOnUiThread{
     val view=(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity.window.decorView
     val b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(b))
     val dir=java.io.File(System.getProperty("no5.renderOutputDir"),"eq-direct-DIR1").apply{mkdirs()}
     java.io.File(dir,"$l-${if(dark)"night"else"day"}-font$f-$m.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
    }
   }
  }finally{ui.runOnIdle{audio.dispose()}}
 }
}
