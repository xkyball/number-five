package com.no5.helm

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import com.no5.helm.network.soundFixture
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Separate native reference proposal. It never updates an approved reference or product source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="de-w2000dp-h1200dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioEntryApprovalCaptureTest {
    @get:Rule val ui=createComposeRule()
    data class Frame(val lang:String="de",val night:Boolean=false,val font:Float=1f,val menu:Boolean=false)
    @Test fun captureTheRequestedSingleEntryAndItsFourDestinations(){
        val frame=mutableStateOf(Frame());val controllers=mutableListOf<AudioNativeController>()
        val sound=object:AudioSoundPort {
            override val state=MutableSourceFeed(soundFixture());override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null)
            override val results=MutableSourceFeed<AudioSoundResult?>(null)
            override fun submit(command:AudioSoundCommand):Long=error("Capture must not submit sound commands")
            override fun refresh()=Unit;override fun setEditorVisible(visible:Boolean,eqVisible:Boolean)=Unit;override fun close()=Unit
        }
        ui.setContent{
            val f=frame.value
            com.no5.ui.No5LocaleScope(f.lang){CompositionLocalProvider(LocalDensity provides Density(1f,f.font)){
                key(f){
                    val delegate=remember{MockAudioSource(now={10000L}).also{it.loadFixture(AudioFixture.DEMO)}}
                    val source=remember{object:AudioSourcePort by delegate{override val sound=sound}}
                    val c=remember{AudioNativeController(Dispatchers.Unconfined,{10000L},false,false,source).also{controllers+=it;if(f.menu)it.openPanel("audio-menu")}}
                    DisposableEffect(c){onDispose{c.dispose()}}
                    MaterialTheme{Box(Modifier.size(2000.dp,1200.dp)){AudioScreen(1f,f.night,c)}}
                }
            }}
        }
        val out=File(System.getProperty("no5.renderOutputDir"),"audio-entry-A1-candidate").apply{mkdirs()}
        try{
            for(lang in listOf("de","en","af"))for(night in listOf(false,true))for(font in listOf(1f,1.25f))for(menu in listOf(false,true)){
                ui.runOnIdle{
                    val expected=mapOf("de" to "Klang, Hörplatz und Bedienung.","en" to "Sound, listening position and controls.","af" to "Klank, luisterposisie en bediening.")
                    assertTrue(com.no5.ui.No5Text.hasTranslation("Klang, Hörplatz und Bedienung."))
                    assertEquals(expected.getValue(lang),com.no5.ui.No5Text.text(lang,"Klang, Hörplatz und Bedienung."))
                    com.no5.ui.UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),lang);frame.value=Frame(lang,night,font,menu)
                }
                ui.waitForIdle()
                ui.runOnIdle{
                    val decor=(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity.window.decorView
                    val full=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);decor.draw(android.graphics.Canvas(full))
                    val repeat=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);decor.draw(android.graphics.Canvas(repeat));assertTrue("Static capture changed",full.sameAs(repeat));repeat.recycle()
                    val body=Bitmap.createBitmap(full,0,130,2000,928)
                    val name="${if(menu)"menu"else"audio"}-$lang-${if(night)"night"else"day"}-font$font.png"
                    File(out,name).outputStream().use{body.compress(Bitmap.CompressFormat.PNG,100,it)};body.recycle();full.recycle()
                }
            }
        }finally{controllers.forEach{it.dispose()}}
        assertEquals(24,out.listFiles{_,n->n.endsWith(".png")}!!.size)
    }
}
