package com.no5.helm.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import com.no5.ui.No5DialogDiagnostics
import com.no5.ui.No5DialogTextFit
import org.junit.Assert.*
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Approved calibrated sheets. The historical stock-dialog probe remains unchanged.
 * Every action, title and glyph assertion is against the real production layout. */
internal class D03MapDialogLayoutProbe(private val ui:ComposeTestRule,private val activity:Activity,
    private val case:String,private val language:String,private val font:Float,private val night:Boolean){
    fun verify(title:String,labels:List<String>,raw:Set<String> = emptySet()){
        val tag=when{
            case=="native-renderer"->"renderer-driver-details"
            case=="native-renderer-measurement"->"renderer-dialog"
            case.startsWith("native-gateway")->"map-connection"
            case=="native-noaa-sources"->"renderer-sources"
            case=="native-object-details"->"map-object-details"
            case=="native-route-delete"->"route-delete"
            case=="native-overlay-hits"->"overlay-hit-panel"
            else->error("Unmapped concrete dialog $case")
        }
        focusHostInputWindow(activity,ui)
        val paneNode=ui.onNode(hasTestTag(tag) and SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).assertIsDisplayed().fetchSemanticsNode()
        val pane=paneNode.boundsInRoot
        val heading=ui.onNodeWithTag("$tag-title").fetchSemanticsNode().boundsInRoot
        assertTrue(pane.left>=440f&&pane.right<=1560f)
        assertTrue(pane.top>=0f&&pane.bottom<=1058f)
        assertTrue("Approved R1 MAP dialog leaves its actual938px footer clear",pane.bottom<=938f)
        ui.onNodeWithText(title,useUnmergedTree=true).assertExists()
        val actions=labels.map { label->
            ui.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag("$tag-actions")),useUnmergedTree=true)
                .assertExists().fetchSemanticsNode().boundsInRoot.also{r->
                    assertTrue("48dp action $case/$label $r",r.width>=96f&&r.height>=96f)
                    contained(pane,r);assertTrue(heading.bottom<=r.top)
                }
        }
        fun glyphs(){
            ui.onAllNodes(SemanticsMatcher("actual owned D03 pane"){node->generateSequence(node.parent){it.parent}.any{it.id==paneNode.id}},useUnmergedTree=true).fetchSemanticsNodes().forEach{node->
                val layouts=mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                layouts.forEach{layout->
                    assertEquals(2f,layout.layoutInput.density.density,0f)
                    assertEquals(font,layout.layoutInput.density.fontScale,0f)
                    // Exactly the D03 body/control tokens at the calibrated 0.5 scale.
                    assertTrue("No font shrinking ${layout.layoutInput.text.text}",layout.layoutInput.style.fontSize.value in setOf(11.5f,12.5f,13.5f,14.5f,15f,17f,17.5f,20f))
                    if(!node.config.contains(SemanticsActions.SetText)){
                        assertFalse(layout.didOverflowHeight)
                        for(i in 0 until layout.lineCount){assertFalse(layout.isLineEllipsized(i));assertTrue(layout.getLineLeft(i)>=-.1f&&layout.getLineRight(i)<=layout.size.width+.1f)}
                        if(layout.lineCount>0)assertTrue(layout.getLineEnd(layout.lineCount-1,true)>=layout.layoutInput.text.text.trimEnd().length)
                    }
                }
            }
        }
        glyphs()
        // Retain every initial real dialog state for the complete design audit,
        // rather than overwriting it with only the last scrolled AF/night case.
        ui.runOnIdle {
            val view=ShadowDialog.getShownDialogs().last{it.isShowing}.window!!.decorView
            val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(image))
                val dir=File(System.getProperty("no5.renderOutputDir"),"native-dialog-variants").apply{mkdirs()}
                File(dir,"$case-$language-${if(night)"night"else"day"}-font$font-top.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
            } finally {image.recycle()}
        }
        val body=ui.onNodeWithTag("$tag-body",useUnmergedTree=true).fetchSemanticsNode()
        val range=body.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        var steps=0
        while(range!=null&&range.value()<range.maxValue()&&steps++<64){
            ui.onNodeWithTag("$tag-body",useUnmergedTree=true).performSemanticsAction(SemanticsActions.ScrollBy){it(0f,(body.boundsInRoot.height*.8f).coerceAtLeast(80f))}
            ui.waitForIdle();glyphs()
            assertEquals(heading,ui.onNodeWithTag("$tag-title").fetchSemanticsNode().boundsInRoot)
            labels.forEachIndexed{i,label->assertEquals(actions[i],ui.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag("$tag-actions")),useUnmergedTree=true).fetchSemanticsNode().boundsInRoot)}
        }
        if(range!=null)assertTrue("Bounded body reaches end $case",range.value()>=range.maxValue())
        val ink=mutableListOf<No5DialogTextFit>();No5DialogDiagnostics.observe={ink+=it}
        try{ui.runOnIdle{
            val view=ShadowDialog.getShownDialogs().last{it.isShowing}.window!!.decorView
            val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            try{
                view.draw(Canvas(image))
                if(night)for(y in pane.top.toInt()+8 until pane.bottom.toInt()-8)for(x in pane.left.toInt()+8 until pane.right.toInt()-8){
                    val color=image.getPixel(x,y)
                    assertTrue("Red-night contains inherited non-red color in $case at $x,$y: $color",android.graphics.Color.green(color)==0&&android.graphics.Color.blue(color)==0)
                }
                val dir=File(System.getProperty("no5.renderOutputDir"),"d03-map-dialogs").apply{mkdirs()}
                File(dir,"$case-$language-${if(night)"night"else"day"}-font$font-end.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
                if(language=="af"&&font==1.25f&&night)File(dir,"$case.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
            }finally{image.recycle()}
        }}finally{No5DialogDiagnostics.observe=null}
        assertTrue("Native title and action ink measured $case",ink.isNotEmpty())
        ink.forEach{assertEquals(font,it.fontScale,0f);assertTrue("Fixed native ink fits: $it",it.bounds.width()<=it.available+.01f)}
        assertEquals(35f*font,ink.last{it.tag=="$tag-title"}.size,0f)
        // Body strings declared raw are original third-party source documents, never substituted.
        raw.forEach{assertTrue(it.isNotEmpty())}
    }
    private fun contained(p:Rect,r:Rect){assertTrue(r.left>=p.left&&r.right<=p.right&&r.top>=p.top&&r.bottom<=p.bottom)}
}
