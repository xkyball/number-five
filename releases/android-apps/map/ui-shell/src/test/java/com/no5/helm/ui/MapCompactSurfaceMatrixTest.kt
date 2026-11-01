package com.no5.helm.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.header.artwork.*
import com.no5.helm.library.*
import com.no5.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Native graphics matrix for compact library surfaces; explicit fixtures, no device/GLES claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapCompactSurfaceMatrixTest {
    @get:Rule val ui=createEmptyComposeRule()
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    @After fun reset(){No5DialogDiagnostics.observe=null;No5WorkspaceDiagnostics.observe=null;RuntimeEnvironment.setFontScale(1f);UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")}
    private fun capture(view:View,name:String){
        val out=File(System.getProperty("no5.renderOutputDir"),"compact-library-api${RuntimeEnvironment.getApiLevel()}").apply{mkdirs()}
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        try{view.draw(Canvas(image));File(out,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
    }
    @Test fun observedImportPhasesAndTerminalsFitEveryLanguageThemeAndFont(){
        val context=RuntimeEnvironment.getApplication()
        var epoch by mutableIntStateOf(0);var night by mutableStateOf(false)
        var observed by mutableStateOf(ImportProgress())
        var busy by mutableStateOf(true)
        val fits=mutableListOf<No5WorkspaceTextFit>();No5WorkspaceDiagnostics.observe={fits+=it}
        Robolectric.buildActivity(ComponentActivity::class.java).apply{get().setTheme(android.R.style.Theme_Material_NoActionBar)}.setup().visible().windowFocusChanged(true).use{host->
            host.get().setContent{key(epoch){Box(Modifier.fillMaxSize()){MapImportWorkspace(observed,busy,night,{},{},{},null)}}}
            for(font in listOf(1f,1.25f))for(language in listOf("de","en","af"))for(n in listOf(false,true)){
                ui.runOnIdle{RuntimeEnvironment.setFontScale(font);UiLanguage.acceptConfirmed(context,language);night=n;epoch++}
                val variants=ImportPhase.entries.map{ImportProgress(it,currentFile="ACTUAL001.000",processedFiles=2,totalFiles=7,
                    processedBytes=1024,totalBytes=4096,phaseStartedNanos=100L)}+
                    listOf(ImportActivity.CANCELLING,ImportActivity.CANCELLED,ImportActivity.FAILED,ImportActivity.COMPLETED).map{
                        ImportProgress(ImportPhase.INDEXING,currentFile="ACTUAL001.000",activity=it,error=if(it==ImportActivity.FAILED)"DSID fehlt"else null,phaseStartedNanos=100L)}
                for((index,value)in variants.withIndex()){
                    ui.runOnIdle{observed=value;busy=value.activity in listOf(ImportActivity.RUNNING,ImportActivity.CANCELLING)}
                    ui.onNodeWithTag("map-import-observed").assertIsDisplayed()
                    if(value.activity in listOf(ImportActivity.RUNNING,ImportActivity.CANCELLING))ui.onNodeWithTag("chart-import-file").assertTextEquals("ACTUAL001.000")
                    val back=ui.onNodeWithTag("no5-action-chart-import-library").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertEquals(620f,back.width,.01f);assertEquals(96f,back.height,.01f)
                    if(busy&&value.canCancel)ui.onNodeWithTag("no5-action-chart-import-cancel").assertIsDisplayed()
                    else ui.onNodeWithTag("no5-action-chart-import-cancel").assertDoesNotExist()
                    ui.runOnIdle{
                        fits.clear();capture(host.get().window.decorView,"import-$index-$language-$n-$font")
                        fits.filterNot{it.dynamic}.forEach{assertTrue("Fixed label clipped: $it",it.width<=it.available+.01f)}
                    }
                }
            }
        }
    }
    @Test fun compactDetailTitleRowsAndActionsFitAndLongContentCannotMoveTheActions(){
        val context=RuntimeEnvironment.getApplication()
        var epoch by mutableIntStateOf(0);var night by mutableStateOf(false);var long by mutableStateOf(false)
        val fits=mutableListOf<No5DialogTextFit>();No5DialogDiagnostics.observe={fits+=it}
        Robolectric.buildActivity(ComponentActivity::class.java).apply{get().setTheme(android.R.style.Theme_Material_NoActionBar)}.setup().visible().windowFocusChanged(true).use{host->
            host.get().setContent{key(epoch){No5DetailsDialog("Herkunft und Bedingungen",night,{},
                body=if(long)List(50){No5Text.ui("Originalhinweise bleiben vollständig lesbar")}.joinToString("\n")else null,
                rows=if(long)emptyList()else listOf(
                    No5WorkspaceView.Row("NOAA-Testkarten","Originalhinweise bleiben vollständig lesbar",key="provider"),
                    No5WorkspaceView.Row("Bedingungen gelesen",value="Aus",key="terms",control="check",action={}),
                    No5WorkspaceView.Row("Originaldokument öffnen",key="document",action={})),
                actions=listOf(No5WorkspaceView.Action("Schließen",key="details-close",action={})),tag="details-matrix")}}
            for(font in listOf(1f,1.25f))for(language in listOf("de","en","af"))for(n in listOf(false,true)){
                ui.runOnIdle{RuntimeEnvironment.setFontScale(font);UiLanguage.acceptConfirmed(context,language);night=n;long=false;epoch++}
                focusHostInputWindow(host.get(),ui)
                ui.runOnIdle{fits.clear();val window=ShadowDialog.getShownDialogs().last{it.isShowing}.window!!.decorView
                    capture(window,"details-$language-$n-$font");assertTrue(fits.isNotEmpty())
                    fits.forEach{assertTrue("Fixed detail text clipped: $it",it.bounds.width()<=it.available+.01f)}}
                val shortActions=ui.onNodeWithTag("details-close").fetchSemanticsNode().boundsInRoot
                assertTrue(shortActions.height>=96f)
                ui.runOnIdle{long=true}
                val body=ui.onNodeWithTag("details-matrix-body").fetchSemanticsNode().boundsInRoot
                val actions=ui.onNodeWithTag("details-close").fetchSemanticsNode().boundsInRoot
                assertTrue("Only body owns the available scroll height",body.bottom<=actions.top)
                assertTrue("Long details preserve the footer",actions.bottom<=1058f)
                assertTrue("Long details preserve the header",ui.onNodeWithTag("details-matrix").fetchSemanticsNode().boundsInRoot.top>=130f)
                ui.onNodeWithTag("details-close").assertIsDisplayed()
            }
        }
    }
}
