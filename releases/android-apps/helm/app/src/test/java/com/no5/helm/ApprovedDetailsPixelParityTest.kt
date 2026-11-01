package com.no5.helm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.no5.header.artwork.No5WorkspaceView
import com.no5.ui.No5DetailsDialog
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
import org.robolectric.shadows.ShadowDialog
import java.io.File
import kotlin.math.max

/** Additional exact frozen detail-sheet component proof. Existing primary, renderer and
 * historical layout references remain unchanged. Data are confined to this native test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovedDetailsPixelParityTest {
    @get:Rule val ui=createComposeRule()
    private data class Frame(val spec:JSONObject,val lang:Int=0,val night:Boolean=false,val font:Float=1f,val epoch:Int=0)
    @Test fun frozenDetailsKeepExactNativePanelInk(){
        val root=File("../../design/app-unification-20261019")
        val model=JSONObject(File(root,"proposal.json").readText()).getJSONArray("screens")
        val specs=(0 until model.length()).map{model.getJSONObject(it)}.filter{it.getString("kind")=="detail"}
        val frame=mutableStateOf(Frame(specs.first()))
        ui.setContent{
            val f=frame.value;val language=listOf("de","en","af")[f.lang]
            UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),language)
            CompositionLocalProvider(LocalDensity provides Density(2f,f.font)){
                MaterialTheme { Box(Modifier.size(1000.dp,600.dp).background(Color(if(f.night)0xff000000 else 0xfff6f9fa))){key(f.epoch){
                    val data=f.spec.optJSONArray("rows")?:JSONArray()
                    val rows=(0 until data.length()).map{i->val r=data.getJSONObject(i)
                        No5WorkspaceView.Row(r.getJSONArray("label").getString(f.lang),r.optJSONArray("detail")?.getString(f.lang),r.optJSONArray("value")?.getString(f.lang),
                            selected=r.optBoolean("selected"),control=r.optString("control"),rawLabel=true,rawDetail=true,rawValue=true,key="row-$i",action={})}
                    val dataActions=f.spec.optJSONArray("actions")?:JSONArray()
                    val actions=(0 until dataActions.length()).map{i->val a=dataActions.getJSONObject(i)
                        No5WorkspaceView.Action(a.getJSONArray("label").getString(f.lang),enabled=a.optBoolean("enabled",true),key="action-$i",action={})}
                    No5DetailsDialog(f.spec.getJSONArray("title").getString(f.lang),f.night,{},
                        body=if(rows.isEmpty())f.spec.getJSONArray("subtitle").getString(f.lang)else null,
                        rows=rows,actions=actions,tag="approved-details")
                }}}
            }
        }
        val out=File(System.getProperty("no5.renderOutputDir"),"approved-details").apply{mkdirs()};val results=JSONArray()
        try{
            for(lang in 0..2)for(night in listOf(false,true))for(font in listOf(1f,1.25f))for(spec in specs){
                ui.runOnIdle{frame.value=Frame(spec,lang,night,font,frame.value.epoch+1)};ui.waitForIdle();focusHelmTestForegroundWindow(ui)
                val id=spec.getString("id");val name="$id-${listOf("de","en","af")[lang]}-${if(night)"night"else"day"}-font$font.png"
                val expected=BitmapFactory.decodeFile(File(root,"renders/$name").absolutePath);assertNotNull(expected)
                var changed=0
                ui.runOnIdle{
                    val view=ShadowDialog.getShownDialogs().last{it.isShowing}.window!!.decorView
                    val actual=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);actual.eraseColor(if(night)android.graphics.Color.BLACK else 0xfff6f9fa.toInt());view.draw(Canvas(actual))
                    assertEquals(2000,actual.width);assertEquals(1200,actual.height)
                    val h=max(410,246+104*(spec.optJSONArray("rows")?.length()?:0))
                    for(y in 240 until 240+h)for(x in 440 until 1560)if(actual.getPixel(x,y)!=expected.getPixel(x,y))changed++
                    if(changed>0&&lang==0&&!night&&font==1f)File(out,"actual-$name").outputStream().use{actual.compress(Bitmap.CompressFormat.PNG,100,it)}
                    actual.recycle()
                };expected.recycle()
                results.put(JSONObject().put("id",id).put("language",lang).put("night",night).put("font",font).put("differentPixels",changed))
            }
        }finally{File(out,"RESULTS.json").writeText(results.toString(2));UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")}
        assertTrue("Frozen detail-sheet mismatches: "+(0 until results.length()).map{results.getJSONObject(it)}.filter{it.getInt("differentPixels")>0}.take(8).joinToString(),(0 until results.length()).all{results.getJSONObject(it).getInt("differentPixels")==0})
    }
}
