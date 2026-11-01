package com.no5.helm.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import com.no5.helm.model.MapPositionState
import com.no5.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Design-audit captures of the actual unavailable-source dialog; no fake fix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativePositionReviewCaptureTest {
    @get:Rule val ui=createEmptyComposeRule()
    @Test fun actualPositionDialogHasAllTwelveReviewVariants(){
        val original=UiLanguage.current(RuntimeEnvironment.getApplication())
        var language by mutableStateOf("de");var night by mutableStateOf(false);var font by mutableFloatStateOf(1f);var epoch by mutableIntStateOf(0)
        Robolectric.buildActivity(ComponentActivity::class.java).apply{get().setTheme(android.R.style.Theme_Material_NoActionBar)}.setup().visible().windowFocusChanged(true).use{host->
            host.get().setContent{CompositionLocalProvider(LocalDensity provides Density(2f,font)){No5LocaleScope(language,{}){
                MaterialTheme(colorScheme=if(night)darkColorScheme(surface=Color.Black)else lightColorScheme(surface=Color(0xfff6f9fa))){key(epoch){MapPositionDialog(MapPositionState(),{}, {_,_->})}}
            }}}
            try{for(l in listOf("de","en","af"))for(n in listOf(false,true))for(f in listOf(1f,1.25f)){
                ui.runOnIdle{UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),l);language=l;night=n;font=f;epoch++}
                ui.waitForIdle();focusHostInputWindow(host.get(),ui)
                val button=ui.onNodeWithTag("position-close").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(button.height>=96f);assertTrue(button.width>=96f)
                ui.runOnIdle{
                    val view=ShadowDialog.getShownDialogs().last{it.isShowing}.window!!.decorView
                    val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                    try{view.draw(Canvas(image));val dir=File(System.getProperty("no5.renderOutputDir"),"native-position-variants").apply{mkdirs()}
                        File(dir,"position-$l-${if(n)"night"else"day"}-font$f.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
                    }finally{image.recycle()}
                }
            }}finally{UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),original)}
        }
    }
}
