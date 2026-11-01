package com.no5.helm.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.header.artwork.No5WorkspaceDiagnostics
import com.no5.header.artwork.No5WorkspaceTextFit
import com.no5.helm.chart.*
import com.no5.preparation.ui.No5ControlTheme
import com.no5.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapStyleWorkspaceTest {
    @get:Rule val ui=createEmptyComposeRule()
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    @After fun reset(){No5WorkspaceDiagnostics.observe=null;RuntimeEnvironment.setFontScale(1f);UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")}
    @Test fun candidateAndFailedSaveSurviveReturnWhileOnlyConfirmedApplyCanFinish(){
        var completion:((Boolean)->Unit)?=null;var submitted:ChartPresentation?=null;var closes=0
        Robolectric.buildActivity(ComponentActivity::class.java).apply{get().setTheme(android.R.style.Theme_Material_NoActionBar)}.setup().visible().windowFocusChanged(true).use{host->
            host.get().setContent{No5ControlTheme(false){ChartStyleSettings(ChartPresentation(),PresentationProfiles(),false,{closes++},{error("Unconfirmed fallback must not run")},
                onApplyConfirmed={value,done->submitted=value;completion=done},workspace=true)}}
            fun target(tag:String)=all(host.get().window.decorView).single{it.tag==tag}
            fun tap(tag:String){ui.runOnIdle{assertTrue(target(tag).isEnabled);target(tag).performClick()};ui.waitForIdle()}
            tap("no5-row-chart-style-paper");assertNull(submitted)
            tap("no5-action-chart-style-apply");assertEquals("paper",submitted!!.styleId);assertEquals(0,closes)
            ui.runOnIdle{assertFalse(target("no5-row-chart-style-default").isEnabled);completion!!(false)}
            ui.runOnIdle{assertTrue(target("no5-row-chart-style-paper").isSelected);assertTrue(target("map-m1-workspace").contentDescription.toString().contains("Speichern fehlgeschlagen"))}
            tap("no5-action-chart-style-close");focusHostInputWindow(host.get(),ui)
            ui.onNodeWithTag("unsaved-changes-continue").performClick();focusHostInputWindow(host.get(),ui)
            ui.runOnIdle{assertTrue(target("no5-row-chart-style-paper").isSelected)}
            tap("no5-action-chart-style-close");focusHostInputWindow(host.get(),ui)
            ui.onNodeWithTag("unsaved-changes-save").performClick();assertEquals(0,closes)
            ui.runOnIdle{completion!!(true)};assertEquals(1,closes)
        }
    }
    @Test fun approvedNativeStyleLabelsFitAllLanguagesThemesAndFontSizes(){
        var epoch by mutableIntStateOf(0);var night by mutableStateOf(false)
        val fits=mutableListOf<No5WorkspaceTextFit>();No5WorkspaceDiagnostics.observe={fits+=it}
        Robolectric.buildActivity(ComponentActivity::class.java).apply{get().setTheme(android.R.style.Theme_Material_NoActionBar)}.setup().visible().windowFocusChanged(true).use{host->
            host.get().setContent{key(epoch){No5ControlTheme(night){ChartStyleSettings(ChartPresentation(),PresentationProfiles(),false,{},{},workspace=true)}}}
            for(font in listOf(1f,1.25f))for(language in listOf("de","en","af"))for(n in listOf(false,true)){
                ui.runOnIdle{RuntimeEnvironment.setFontScale(font);UiLanguage.acceptConfirmed(host.get(),language);night=n;epoch++}
                ui.waitForIdle();ui.runOnIdle{
                    fits.clear();val root=host.get().window.decorView
                    // Draw every scroll row directly as well as the visible viewport.
                    val image=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
                    try{root.draw(Canvas(image));val rowImage=Bitmap.createBitmap(1280,104,Bitmap.Config.ARGB_8888)
                        try{all(root).filter{it.tag?.toString()?.startsWith("no5-row-")==true}.forEach{it.draw(Canvas(rowImage))}}finally{rowImage.recycle()}
                        val dir=File(System.getProperty("no5.renderOutputDir"),"style-workspace-api${RuntimeEnvironment.getApiLevel()}").apply{mkdirs()}
                        File(dir,"$language-$n-$font.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
                    }finally{image.recycle()}
                    assertTrue(fits.isNotEmpty());fits.filterNot{it.dynamic}.forEach{assertTrue("Fixed native style label clipped: $it",it.width<=it.available+.01f)}
                }
            }
        }
    }
}
