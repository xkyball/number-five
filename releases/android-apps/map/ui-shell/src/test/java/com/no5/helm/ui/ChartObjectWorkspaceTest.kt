package com.no5.helm.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.helm.chart.*
import com.no5.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartObjectWorkspaceTest {
    @get:Rule val ui=createEmptyComposeRule()
    private fun all(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()

    @After fun reset(){No5DialogDiagnostics.observe=null;RuntimeEnvironment.setFontScale(1f);UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")}
    @Test fun actualObjectAttributesDocumentsAndProvenanceKeepRawDataAndReturnRoutes(){
        val fixture=ChartObject(ChartObjectReference("fixture",7,23),"7","WRECKS","POINT","RAW_CELL",GeoProjection(1.0,2.0),0.0,
            mapOf("OBJNAM" to "Speichern","VALSOU" to "3.1415926535897931","TXTDSC" to "publisher.txt","INFORM" to "Verwerfen"))
        var epoch by mutableIntStateOf(0);var night by mutableStateOf(false)
        var selected by mutableStateOf<ChartObject?>(null);var closed=0
        val fits=mutableListOf<No5DialogTextFit>();No5DialogDiagnostics.observe={fits+=it}
        Robolectric.buildActivity(ComponentActivity::class.java).apply{get().setTheme(android.R.style.Theme_Material_NoActionBar)}.setup().visible().windowFocusChanged(true).use{host->
            host.get().setContent{key(epoch){ChartObjectPanel(selected,listOf(fixture),1,null,false,ChartObjectHighlight(),ChartObjectDictionary("",""),null,
                listOf(ChartObjectText("publisher.txt","Verwerfen\nOriginal publisher document")),false,{selected=it},{},{closed++},workspace=true,night=night)}}
            for(font in listOf(1f,1.25f))for(language in listOf("de","en","af"))for(n in listOf(false,true)){
                ui.runOnIdle{RuntimeEnvironment.setFontScale(font);UiLanguage.acceptConfirmed(host.get(),language);night=n;selected=null;epoch++}
                focusHostInputWindow(host.get(),ui)
                ui.runOnIdle{all(host.get().window.decorView).single{it.tag=="no5-row-object-pick-fixture-7"}.performClick()};focusHostInputWindow(host.get(),ui)
                val title=ui.onNodeWithTag("object-info-panel-title").fetchSemanticsNode().boundsInRoot
                ui.onNodeWithContentDescription("Speichern").assertIsDisplayed()
                val panel=ui.onNodeWithTag("object-info-panel").fetchSemanticsNode().boundsInRoot
                assertEquals(1120f,panel.width,.01f);assertEquals(620f,panel.height,.01f)
                fun capture(kind:String){ui.runOnIdle{
                    fits.clear();val v=ShadowDialog.getShownDialogs().last{it.isShowing}.window!!.decorView
                    val out=File(System.getProperty("no5.renderOutputDir"),"objects-api${RuntimeEnvironment.getApiLevel()}").apply{mkdirs()}
                    val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)
                    try{v.draw(Canvas(b));File(out,"$kind-$language-$font-$n.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}
                    fits.forEach{assertTrue("Fixed object label clipped: $it",it.bounds.width()<=it.available+.01f)}
                }}
                capture("menu")
                ui.onNodeWithTag("object-attributes").performClick()
                ui.onNodeWithTag("object-facts").assertIsDisplayed().assertTextContains("3.1415926535897931",substring=true).assertTextContains("Verwerfen",substring=true)
                capture("attributes")
                ui.onNodeWithTag("object-raw-toggle").performScrollTo().performClick()
                ui.onNodeWithTag("object-facts").assertTextContains("VALSOU: 3.1415926535897931",substring=true)
                capture("raw");ui.onNodeWithTag("object-close").performClick();ui.onNodeWithTag("object-close").performClick();ui.onNodeWithTag("object-attachments").performClick()
                ui.onNodeWithTag("object-document-TXTDSC").performScrollTo().performClick()
                ui.onNodeWithTag("object-facts").assertTextEquals("Verwerfen\nOriginal publisher document")
                capture("document");ui.onNodeWithTag("object-close").performClick();ui.onNodeWithTag("object-close").performClick()
                ui.onNodeWithTag("object-provenance").performClick();ui.onNodeWithTag("object-facts").assertTextContains("RAW_CELL",substring=true)
                capture("provenance");ui.onNodeWithTag("object-close").performClick()
                assertEquals(title,ui.onNodeWithTag("object-info-panel-title").fetchSemanticsNode().boundsInRoot)
                assertEquals(0,closed)
                ui.onNodeWithTag("object-close").performClick();ui.runOnIdle{all(host.get().window.decorView).single{it.tag=="no5-workspace-back"}.performClick()};assertEquals(1,closed);closed=0
            }
        }
    }
}
