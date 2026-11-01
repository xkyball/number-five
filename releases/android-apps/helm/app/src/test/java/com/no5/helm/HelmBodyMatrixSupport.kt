package com.no5.helm

import android.graphics.Bitmap
import android.view.View
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.no5.ui.No5LocaleScope
import com.no5.ui.UiLanguage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Real product text layout evidence. Fixtures never create runtime network clients. */
internal class HelmBodyMatrixSupport(private val ui:ComposeContentTestRule,private val name:String){
    val problems=mutableListOf<String>()
    private val evidence=JSONArray()
    private val completed=JSONArray()
    data class Frame(val language:String="de",val night:Boolean=false,val case:String="",val epoch:Int=0)
    fun run(font:Float,cases:List<String>,nativeCanvasBody:Boolean=false,before:(String)->Unit={},after:(String,Frame)->Unit={_,_->},
        content:@Composable (Frame)->Unit){
        val context=RuntimeEnvironment.getApplication();val original=UiLanguage.current(context);val originalFont=RuntimeEnvironment.getFontScale()
        RuntimeEnvironment.setFontScale(font)
        val frame=mutableStateOf(Frame(case=cases.first()))
        ui.runOnIdle{before(cases.first())}
        ui.setContent{
            val f=frame.value
            UiLanguage.acceptConfirmed(context,f.language)
            No5LocaleScope(f.language,{}){key(f.epoch){
                MaterialTheme(colorScheme=if(f.night)darkColorScheme(primary=Color(0xffdc160c),surface=Color.Black,onSurface=Color(0xffd40000),secondary=Color(0xffb00000))else lightColorScheme(primary=Color(0xff005daf),surface=Color(0xfff6f9fa),onSurface=Color(0xff071022))){
                    Box(Modifier.size(1000.dp,600.dp)){content(f)}
                }
            }}
        }
        try{
            for(lang in listOf("de","en","af"))for(night in listOf(false,true))for(case in cases){
                ui.runOnIdle{before(case);frame.value=Frame(lang,night,case,frame.value.epoch+1)}
                ui.waitForIdle()
                focusHelmTestForegroundWindow(ui)
                inspect("$lang/$night/$font/$case",font,requireText=!nativeCanvasBody)
                if(nativeCanvasBody)capture("$lang-${if(night)"night"else"day"}-font$font-$case")
                after(case,frame.value)
                if(!nativeCanvasBody)capture("$lang-${if(night)"night"else"day"}-font$font-$case")
                completed.put(JSONObject().put("language",lang).put("night",night).put("fontScale",font).put("case",case).put("complete",true))
            }
        }finally{
            ui.runOnIdle{RuntimeEnvironment.setFontScale(originalFont);UiLanguage.acceptConfirmed(context,original)}
            val folder=File(System.getProperty("no5.renderOutputDir"),name).apply{mkdirs()}
            File(folder,"measured-text-font$font.json").writeText(evidence.toString(2))
            File(folder,"completed-cases-font$font.json").writeText(completed.toString(2))
        }
        assertEquals("Every named actual body must complete all language/theme cases",cases.size*6,completed.length())
        assertEquals("Actual body/title/action text must remain complete at fixed fonts:\n${problems.joinToString("\n")}",emptyList<String>(),problems)
    }
    fun inspect(label:String,font:Float,requireText:Boolean=true,allowTruncation:(String)->Boolean={it.startsWith("OBSERVED_")}){
        val nodes=ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),useUnmergedTree=true).fetchSemanticsNodes()
        if(requireText)assertTrue("$label has no actual product TextLayoutResult",nodes.isNotEmpty())
        ui.runOnIdle{
            for(node in nodes){
                val layouts=mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                for(layout in layouts){
                    val text=layout.layoutInput.text.text
                    val editable=node.config.getOrNull(SemanticsProperties.EditableText)!=null
                    val clipped=layout.lineCount>0&&(layout.getLineEnd(layout.lineCount-1,visibleEnd=true)<text.trimEnd().length||(0 until layout.lineCount).any{layout.isLineEllipsized(it)})
                    val overflow=(0 until layout.lineCount).any{layout.getLineLeft(it)<0f||layout.getLineRight(it)>layout.size.width}
                    val verticalOverflow=(0 until layout.lineCount).any{layout.getLineTop(it)<0f||layout.getLineBottom(it)>layout.size.height}
                    // StableNumericText deliberately paints one inline numeric cell with Visible
                    // overflow. Its font-size placeholder is not a clipping boundary. The outer
                    // complete numeric run and all normal fixed captions remain checked above.
                    val visibleNumericCell=layout.layoutInput.overflow==androidx.compose.ui.text.style.TextOverflow.Visible &&
                        (text.isEmpty() || (text.length==1 && text[0] in "0123456789.,−+-—"))
                    val row=JSONObject().put("case",label).put("text",text).put("fontSp",layout.layoutInput.style.fontSize.value)
                        .put("fontScale",layout.layoutInput.density.fontScale).put("width",layout.size.width).put("height",layout.size.height).put("lines",layout.lineCount).put("clipped",clipped).put("lineOverflow",overflow).put("heightOverflow",verticalOverflow).put("editable",editable).put("visibleNumericCell",visibleNumericCell).put("overflowMode",layout.layoutInput.overflow.toString())
                        .put("lineTop",JSONArray((0 until layout.lineCount).map{layout.getLineTop(it)}))
                        .put("lineBottom",JSONArray((0 until layout.lineCount).map{layout.getLineBottom(it)}))
                        .put("glyphBounds",JSONArray(text.indices.filter{!text[it].isWhitespace()}.map{val b=layout.getBoundingBox(it);JSONArray(listOf(b.left,b.top,b.right,b.bottom))}))
                    evidence.put(row)
                    if(!editable&&!allowTruncation(text)&&(clipped||overflow||(verticalOverflow&&!visibleNumericCell)))problems+="$label clipped=$clipped overflow=$overflow heightOverflow=$verticalOverflow ${row}"
                    if(layout.layoutInput.density.fontScale!=font)problems+="$label wrong actual fontScale ${layout.layoutInput.density.fontScale} text=$text"
                    assertTrue("$label invalid fixed font for $text",layout.layoutInput.style.fontSize.value>0)
                }
            }
        }
    }
    fun stableBody(bodyTag:String,titleTag:String,actionsTag:String){
        val title=ui.onNodeWithTag(titleTag).fetchSemanticsNode().boundsInRoot
        val actions=ui.onNodeWithTag(actionsTag).fetchSemanticsNode().boundsInRoot
        val body=ui.onNodeWithTag(bodyTag)
        val range=body.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        if(range!=null&&range.maxValue()>0){
            body.performSemanticsAction(SemanticsActions.ScrollBy){it(0f,100000f)}
            assertEquals("Title scrolled with body",title,ui.onNodeWithTag(titleTag).fetchSemanticsNode().boundsInRoot)
            assertEquals("Actions scrolled with body",actions,ui.onNodeWithTag(actionsTag).fetchSemanticsNode().boundsInRoot)
        }
    }
    fun capture(label:String){
        ui.runOnUiThread{
            val dialog=ShadowDialog.getLatestDialog()?.takeIf{it.isShowing}
            val view=dialog?.window?.decorView?:WindowInspector.getGlobalWindowViews().lastOrNull{it.isAttachedToWindow&&it.visibility==View.VISIBLE}
                ?:(ui as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*,*>).activity.window.decorView
            val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            try{
                view.draw(android.graphics.Canvas(image))
                val dir=File(System.getProperty("no5.renderOutputDir"),name).apply{mkdirs()}
                File(dir,"$label.png").outputStream().use{assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))}
            }finally{image.recycle()}
        }
    }
}
