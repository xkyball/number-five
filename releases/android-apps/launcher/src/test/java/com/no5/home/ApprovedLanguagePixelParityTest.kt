package com.no5.home

import android.app.Activity
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import com.no5.ui.UiLanguage
import com.no5.ui.No5Text
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovedLanguagePixelParityTest {
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    @Test fun actualNativeLanguageStatesMatchTheFrozenCompletePanel(){
        val root=File("../design/app-unification-20261019");val model=JSONObject(File(root,"proposal.json").readText())
        val out=File(System.getProperty("no5.renderOutputDir"),"approved-language").apply{mkdirs()};val results=JSONArray()
        try{
            for(language in listOf("de","en","af"))for(night in listOf(false,true))for(font in listOf(1f,1.25f))for(state in listOf("","pending","error")){
                RuntimeEnvironment.setFontScale(font);UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),language)
                Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true).use{host->
                    val source=when(state){"pending"->"Sprachwechsel angefragt · Bestätigung ausstehend";"error"->"Nicht bestätigt · bisherige Sprache bleibt aktiv";else->"Einmal wählen · alle Apps übernehmen die Sprache"}
                    val dialog=HomeLanguageDialog(host.get(),night,language,No5Text.text(host.get(),source),{})
                    try{
                        val stage=all(dialog.window!!.decorView).filterIsInstance<HomeStage>().single()
                        stage.measure(View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.EXACTLY));stage.layout(0,0,2000,1200)
                        val theme=if(night)"night"else"day";val id="home-language"+if(state.isBlank())""else"-$state";val name="$id-$language-$theme-font$font.png"
                        val input=BitmapFactory.decodeFile(File(model.getString("homeBaselineRoot"),"landing-$language-$theme-font$font-h1200.png").absolutePath)
                        val actual=input.copy(Bitmap.Config.ARGB_8888,true);input.recycle();stage.draw(Canvas(actual))
                        val expected=BitmapFactory.decodeFile(File(root,"renders/$name").absolutePath);assertNotNull(expected)
                        var changed=0;for(y in 240 until 860)for(x in 440 until 1560)if(actual.getPixel(x,y)!=expected.getPixel(x,y))changed++
                        if(changed>0&&language=="de"&&!night&&font==1f)File(out,"actual-$name").outputStream().use{actual.compress(Bitmap.CompressFormat.PNG,100,it)}
                        results.put(JSONObject().put("id",id).put("language",language).put("night",night).put("font",font).put("differentPixels",changed));actual.recycle();expected.recycle()
                    }finally{dialog.dismiss()}
                }
            }
        }finally{File(out,"RESULTS.json").writeText(results.toString(2));RuntimeEnvironment.setFontScale(1f);UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")}
        assertTrue("Frozen native HOME language panel mismatch: $results",(0 until results.length()).all{results.getJSONObject(it).getInt("differentPixels")==0})
    }
}
