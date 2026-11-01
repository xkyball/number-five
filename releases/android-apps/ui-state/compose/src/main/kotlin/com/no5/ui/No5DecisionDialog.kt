package com.no5.ui

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clipToBounds
import com.no5.keyboard.SpecialKeyboardGeometry
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.no5.header.artwork.No5WorkspaceBackdrop

/** Draw-time metrics, unused in production and never a retained notification log. */
data class No5DialogTextFit(val tag:String,val text:String,val size:Float,val fontScale:Float,
    val available:Float,val bounds:RectF,val color:Int)
object No5DialogDiagnostics { var observe:((No5DialogTextFit)->Unit)?=null }
private fun dialogText(tag:String,text:String,paint:Paint,x:Float,y:Float,width:Float,fontScale:Float) {
    No5DialogDiagnostics.observe?.invoke(No5DialogTextFit(tag,text,paint.textSize,fontScale,width,
        RectF(x,y+paint.ascent(),x+paint.measureText(text),y+paint.descent()),paint.color))
}

/** Approved D03 decision grammar. Only its body scrolls; all actions retain 96px targets. */
@Composable
fun No5DecisionDialog(title:String,body:String,night:Boolean,font:FontFamily,
    onSave:()->Unit,onDiscard:()->Unit,onContinue:()->Unit,
    busy:Boolean=false,canSave:Boolean=true,error:String?=null,prefix:String="unsaved-changes",
    saveLabel:String="Speichern",discardLabel:String="Verwerfen",continueLabel:String="Zurück",dialogTag:String="$prefix-dialog") {
    val context=LocalContext.current
    val density=LocalDensity.current
    val ink=if(night)0xffed0000.toInt()else 0xff07121b.toInt()
    val background=if(night)0xff000000.toInt()else 0xfff6f9fa.toInt()
    val line=if(night)0xff550000.toInt()else 0xffb5c2c8.toInt()
    val muted=if(night)0xffa40000.toInt()else 0xff526b7b.toInt()
    val accent=if(night)0xffed0000.toInt()else 0xffff5b00.toInt()
    val heavy=remember(context){Typeface.createFromAsset(context.assets,"no5-header/barlow_condensed_extrabold.ttf")}
    val bold=remember(context){Typeface.createFromAsset(context.assets,"no5-header/barlow_condensed_semibold.ttf")}
    val regular=remember(context){Typeface.createFromAsset(context.assets,"no5-header/barlow_condensed_regular.ttf")}
    val backdrop=remember(context){No5WorkspaceBackdrop(context.assets)}
    DisposableEffect(backdrop){onDispose{backdrop.close()}}
    val currentContinue by rememberUpdatedState(onContinue)
    Dialog(onDismissRequest={if(!busy)currentContinue()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        CompositionLocalProvider(LocalDensity provides density){
        val dialogView=LocalView.current
        var keyboard by remember{mutableStateOf<android.graphics.Rect?>(null)}
        DisposableEffect(context){val subscription=SpecialKeyboardGeometry.Subscription(context){keyboard=it};onDispose{subscription.close()}}
        SideEffect{(dialogView.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)}
        BoxWithConstraints(Modifier.fillMaxSize().testTag("$prefix-stage")) {
            // Insets shorten the body budget, never the design font or touch targets.
            val scale=maxWidth.value/2000f
            val origin=IntArray(2);dialogView.getLocationOnScreen(origin)
            val keyboardTop=keyboard?.let{with(density){(it.top-origin[1]).coerceAtLeast(0).toDp()}}?:maxHeight
            val available=minOf(maxHeight,keyboardTop)
            val height=available.value/scale
            val left=0f;val top=0f
            val panelX=460f;val panelW=1080f
            val panelH=minOf(390f,(height-48f).coerceAtLeast(222f))
            val panelY=minOf(330f,(height-panelH-24f).coerceAtLeast(0f))
            Box(Modifier.fillMaxWidth().height(available).clipToBounds().testTag("$prefix-viewport")) {
            Box(Modifier.fillMaxSize().pointerInput(busy,scale,left,top){
                detectTapGestures{point->
                    val x=(point.x/density.density-left)/scale
                    val y=(point.y/density.density-top)/scale
                    if(!busy&&(x !in panelX..panelX+panelW || y !in panelY..panelY+panelH))currentContinue()
                }
            })
            Canvas(Modifier.offset(left.dp,top.dp).size((2000*scale).dp,(1200*scale).dp)) {
                val n=drawContext.canvas.nativeCanvas;val u=size.width/2000f;n.save();n.scale(u,u)
                val p=Paint(Paint.ANTI_ALIAS_FLAG)
                p.color=(background and 0x00ffffff) or (241 shl 24);n.drawRect(0f,130f,2000f,1058f,p)
                backdrop.draw(n,night)
                p.color=background;n.drawRoundRect(RectF(panelX,panelY,panelX+panelW,panelY+panelH),4f,4f,p)
                p.color=line;p.strokeWidth=1.5f;p.style=Paint.Style.STROKE
                n.drawRoundRect(RectF(panelX,panelY,panelX+panelW,panelY+panelH),6f,6f,p)
                p.style=Paint.Style.FILL;p.typeface=heavy;p.textSize=35f*density.fontScale;p.color=ink
                val heading=No5Text.text(context,title)
                n.drawText(heading,panelX+28,panelY+58,p)
                dialogText("$prefix-title",heading,p,panelX+28,panelY+58,panelW-56,density.fontScale)
                p.color=line;n.drawLine(panelX+28,panelY+86,panelX+panelW-28,panelY+86,p);n.restore()
            }
            Box(Modifier.offset((left+panelX*scale).dp,(top+panelY*scale).dp)
                .size((panelW*scale).dp,(panelH*scale).dp).testTag(dialogTag)) {
                Box(Modifier.offset((28*scale).dp,0.dp).size((1024*scale).dp,(86*scale).dp)
                    .testTag("$prefix-title").semantics{contentDescription=No5Text.text(context,title)})
                Column(Modifier.offset((28*scale).dp,(106*scale).dp)
                    .size((1024*scale).dp,((panelH-222f)*scale).dp).verticalScroll(rememberScrollState()).testTag("$prefix-body")) {
                    val bodyText=No5Text.text(context,body)
                    val paragraphPaint=remember{Paint(Paint.ANTI_ALIAS_FLAG)}
                    val paragraphLines=remember(bodyText,density.fontScale){
                        paragraphPaint.typeface=regular;paragraphPaint.textSize=29f*density.fontScale
                        buildList{
                            bodyText.split('\n').forEach{paragraph->
                                var row=""
                                paragraph.split(' ').forEach{word->
                                    val candidate=if(row.isEmpty())word else "$row $word"
                                    if(paragraphPaint.measureText(candidate)>1024f&&row.isNotEmpty()){add(row);row=word}else row=candidate
                                }
                                add(row)
                            }
                        }
                    }
                    Canvas(Modifier.fillMaxWidth().height(((34f+(paragraphLines.size-1)*29f*density.fontScale*1.35f)*scale).dp)
                        .semantics{text=androidx.compose.ui.text.AnnotatedString(bodyText)}){
                        val n=drawContext.canvas.nativeCanvas;val u=size.width/1024f;n.save();n.scale(u,u)
                        paragraphPaint.typeface=regular;paragraphPaint.textSize=29f*density.fontScale;paragraphPaint.color=ink
                        paragraphLines.forEachIndexed{i,value->
                            val y=34f+i*29f*density.fontScale*1.35f
                            n.drawText(value,0f,y,paragraphPaint)
                            dialogText("$prefix-body-line-$i",value,paragraphPaint,panelX+28,panelY+106+y,1024f,density.fontScale)
                        };n.restore()
                    }
                    if(busy) Text(No5Text.text(context,"Speichern wird bestätigt …"),fontFamily=font,fontSize=(25*scale).sp,
                        lineHeight=(33.75f*scale).sp,color=Color(muted))
                    error?.let{Text(No5Text.text(context,it),fontFamily=font,fontSize=(25*scale).sp,lineHeight=(33.75f*scale).sp,
                        color=Color(ink),modifier=Modifier.testTag("$prefix-error").semantics{liveRegion=LiveRegionMode.Polite})}
                }
                Box(Modifier.offset(y=((panelH-116f)*scale).dp).size((panelW*scale).dp,(96*scale).dp).testTag("$prefix-actions")) {
                    @Composable fun action(label:String,x:Float,width:Float,tag:String,enabled:Boolean,strong:Boolean=false,click:()->Unit){
                        No5ActionCanvas(Modifier.offset((x*scale).dp).size((width*scale).dp,(96*scale).dp).testTag("$prefix-$tag")
                            .semantics{role=Role.Button;contentDescription=No5Text.text(context,when(tag){"save"->"Speichern und schließen";"continue"->"Weiter bearbeiten";else->label})},
                            enabled=enabled,selected=strong&&enabled,night=night,designWidth=width,designOrigin=Offset(panelX+x,panelY+panelH-116f),onClick=click) {
                            val n=drawContext.canvas.nativeCanvas;val u=size.width/width;n.save();n.scale(u,u)
                            val p=Paint(Paint.ANTI_ALIAS_FLAG)
                            p.style=Paint.Style.FILL;p.typeface=bold;p.textSize=27f*density.fontScale;p.color=if(enabled)ink else muted
                            val value=No5Text.text(context,label);val textX=(width-p.measureText(value))/2
                            n.drawText(value,textX,58f,p)
                            dialogText("$prefix-$tag",value,p,panelX+x+textX,panelY+panelH-116f+58f,width-20,density.fontScale)
                            n.restore()
                        }
                    }
                    action(continueLabel,28f,180f,"continue",!busy,click=onContinue)
                    action(discardLabel,232f,230f,"discard",!busy,click=onDiscard)
                    action(saveLabel,810f,242f,"save",!busy&&canSave,strong=true,click=onSave)
                }
            }
            }
        }
    }
    }
}
