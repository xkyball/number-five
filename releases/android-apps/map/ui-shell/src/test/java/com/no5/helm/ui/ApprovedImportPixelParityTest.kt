package com.no5.helm.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.robolectric.Robolectric
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.no5.helm.library.*
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

/** Exact actual import panel against the unchanged D03 fixtures. Archive/cell names,
 * counters and errors are explicit test data; no import or device connection occurs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovedImportPixelParityTest {
    @get:Rule val ui=createEmptyComposeRule()
    private data class Frame(val spec:JSONObject,val lang:Int=0,val night:Boolean=false,val font:Float=1f,val epoch:Int=0)
    @Test fun everyObservedImportStateMatchesFrozenPanelAndKeepsDistinctActions(){
        val root=File("../../design/app-unification-20261019")
        val model=JSONObject(File(root,"proposal.json").readText());val screens=model.getJSONArray("screens")
        val specs=(0 until screens.length()).map{screens.getJSONObject(it)}.filter{it.getString("kind")=="import"}
        assertEquals(10,specs.size)
        val frame=mutableStateOf(Frame(specs.first()));var backs=0;var cancels=0;var choices=0
        val host=Robolectric.buildActivity(ComponentActivity::class.java).apply{get().setTheme(android.R.style.Theme_Material_NoActionBar)}.setup().visible().windowFocusChanged(true)
        host.get().setContent{
            val f=frame.value;UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),listOf("de","en","af")[f.lang])
            CompositionLocalProvider(LocalDensity provides Density(2f,f.font)){
                Box(Modifier.size(1000.dp,600.dp)){
                    val underlay=remember(f.night){BitmapFactory.decodeFile(File(model.getString("baselineRoot"),"AUDIO-${if(f.night)"night"else"day"}.png").absolutePath)}
                    DisposableEffect(underlay){onDispose{underlay.recycle()}}
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()){drawContext.canvas.nativeCanvas.drawBitmap(underlay,0f,0f,Paint())}
                    key(f.epoch){Box(Modifier.offset(y=65.dp).size(1000.dp,464.dp)){
                        val activity=when(f.spec.getString("importState")){
                            "cancelling"->ImportActivity.CANCELLING;"cancelled"->ImportActivity.CANCELLED
                            "failed"->ImportActivity.FAILED;"completed"->ImportActivity.COMPLETED;else->ImportActivity.RUNNING}
                        val phase=ImportPhase.valueOf(f.spec.getString("phase").uppercase())
                        val progress=ImportProgress(phase=phase,currentFile="SAMPLE001.000",processedFiles=2,totalFiles=7,
                            processedBytes=if(phase==ImportPhase.READING)460 else null,totalBytes=1280,activity=activity,
                            error=if(activity==ImportActivity.FAILED)"Prüfsumme stimmt nicht. Bisheriger Bestand bleibt erhalten."else null)
                        MapImportWorkspace(progress,activity in listOf(ImportActivity.RUNNING,ImportActivity.CANCELLING),f.night,
                            {backs++},{cancels++},{choices++},null,sourceLabel="sample-S57.zip")
                    }}
                }
            }
        }
        val out=File(System.getProperty("no5.renderOutputDir"),"approved-import").apply{mkdirs()};val results=JSONArray()
        try{
            for(lang in 0..2)for(night in listOf(false,true))for(font in listOf(1f,1.25f))for(spec in specs){
                ui.runOnIdle{frame.value=Frame(spec,lang,night,font,frame.value.epoch+1)};ui.waitForIdle()
                val name="${spec.getString("id")}-${listOf("de","en","af")[lang]}-${if(night)"night"else"day"}-font$font.png"
                val expected=BitmapFactory.decodeFile(File(root,"renders/$name").absolutePath);assertNotNull(expected)
                var changed=0
                ui.runOnIdle{
                    val view=host.get().window.decorView;view.dispatchWindowFocusChanged(true)
                    val actual=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(actual))
                    assertEquals(2000,actual.width);assertEquals(1200,actual.height)
                    // Complete import-specific content and actions. The shared heading
                    // uses truthful runtime text, rather than the fixture's draft caption.
                    for(y in 290 until 926)for(x in 350 until 1650)if(actual.getPixel(x,y)!=expected.getPixel(x,y))changed++
                    if(changed>0&&lang==0&&!night)File(out,"actual-$name").outputStream().use{actual.compress(Bitmap.CompressFormat.PNG,100,it)}
                    actual.recycle()
                };expected.recycle()
                val back=ui.onNodeWithTag("no5-action-chart-import-library").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals(360f,back.left,.01f);assertEquals(824f,back.top,.01f);assertEquals(620f,back.width,.01f)
                ui.onNodeWithTag("no5-action-chart-import-library").performClick()
                if(spec.getString("importState")=="completed")ui.onNodeWithTag("no5-action-chart-import-select").performClick()
                else if(spec.optBoolean("canCancel"))ui.onNodeWithTag("no5-action-chart-import-cancel").performClick()
                results.put(JSONObject().put("id",spec.getString("id")).put("language",lang).put("night",night).put("font",font).put("differentPixels",changed))
            }
            assertEquals(120,backs);assertEquals(60,cancels);assertEquals(12,choices)
        }finally{host.close();File(out,"RESULTS.json").writeText(results.toString(2));UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")}
        assertTrue("Exact approved import mismatch: "+(0 until results.length()).map{results.getJSONObject(it)}.filter{it.getInt("differentPixels")>0}.take(10),
            (0 until results.length()).all{results.getJSONObject(it).getInt("differentPixels")==0})
    }
}
