package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import com.no5.ui.No5Text
import com.no5.ui.UiLanguage
import kotlinx.coroutines.Dispatchers
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
@Config(sdk=[29],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioFixedCaptionTest {
    @get:Rule val ui=createComposeRule()
    @Test fun actualAudioCaptionUsesShortTranslationsAtFixedFontWhileAccessibilityKeepsTheCompleteAction(){
        val context=RuntimeEnvironment.getApplication();val original=UiLanguage.current(context)
        val source=MockAudioSource(now={10_000L}).also{it.loadFixture(AudioFixture.DEMO)}
        val audio=AudioNativeController(Dispatchers.Unconfined,now={10_000L},automaticTicker=false,source=source)
        val renderEpoch=mutableStateOf(0)
        val language=mutableStateOf("de");val night=mutableStateOf(false);val font=mutableStateOf(1f)
        ui.setContent{
            UiLanguage.acceptConfirmed(context,language.value)
            CompositionLocalProvider(LocalDensity provides Density(2f,font.value)){
                key(renderEpoch.value){MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){
                    val chrome=HelmScene(context,night.value)
                    Canvas(Modifier.size(1000.dp,600.dp)){drawIntoCanvas{chrome.drawChrome(it.nativeCanvas,"AUDIO",renderValues(HelmState.unavailable()),"TEST FIXTURE")}}
                    AudioScreen(.5f,night.value,audio)
                    Canvas(Modifier.size(1000.dp,600.dp)){drawIntoCanvas{KnobOverlay(context).draw(it.nativeCanvas,night.value,audio,useSelectedAudioFader=true)}}
                }}}
            }
        }
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=context.resources.getFont(R.font.barlow_condensed_semibold);textSize=21f}
        try{
            for(lang in listOf("de","en","af"))for(isNight in listOf(false,true))for(scale in listOf(1f,1.25f)){
                ui.runOnIdle{language.value=lang;night.value=isNight;font.value=scale;renderEpoch.value++}
                ui.waitForIdle()
                ui.onNodeWithContentDescription(No5Text.text(lang,"Zufallswiedergabe")).assertExists()
                val caption=audioShuffleCaption(context)
                assertEquals(mapOf("de" to "ZUFALL","en" to "SHUFFLE","af" to "SKOMMEL").getValue(lang),caption)
                assertTrue("Fixed21px Barlow caption exceeds its150px button: $lang",paint.measureText(caption)<=AudioNativeGeometry.transport.first().width-20)
                ui.runOnUiThread{
                    val view=(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity.window.decorView
                    val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(AndroidCanvas(image))
                    val folder=File(System.getProperty("no5.renderOutputDir"),"audio-textfit").apply{mkdirs()}
                    File(folder,"$lang-audio-${if(isNight)"night"else"day"}-font$scale.png").outputStream().use{assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))}
                    image.recycle()
                }
            }
        }finally{ui.runOnIdle{audio.dispose();UiLanguage.acceptConfirmed(context,original)}}
    }
}
