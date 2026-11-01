package com.no5.ui

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.WindowManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.no5.header.artwork.No5WorkspaceBackdrop
import com.no5.header.artwork.No5WorkspaceView
import com.no5.keyboard.SpecialKeyboardGeometry
import kotlin.math.max

/** App-specific content edge in calibrated2000x1200 coordinates. Other apps
 *retain1058px; MAP supplies its approved938px footer edge. */
val LocalNo5ContentBottom = staticCompositionLocalOf {1058f}

/** D03 compact detail sheet. Its native ink and targets share one calibrated geometry.
 * Long content scrolls independently of the title and actions. No data or command owner lives here. */
@Composable fun No5DetailsDialog(title:String,night:Boolean,onDismiss:()->Unit,
    body:String?=null,rows:List<No5WorkspaceView.Row> = emptyList(),
    actions:List<No5WorkspaceView.Action> = listOf(No5WorkspaceView.Action("Schließen",action=onDismiss)),
    tag:String="no5-details",preferredHeight:Float?=null,partsTag:String=tag,form:Boolean=false,bodyKey:Any?=null,
    compactClose:Boolean=false,content:(@Composable ()->Unit)?=null) {
    require(actions.size in 1..3)
    val context=LocalContext.current
    val density=LocalDensity.current
    val contentBottom=LocalNo5ContentBottom.current.coerceIn(130f,1200f)
    fun report(key:String,value:String,paint:Paint,available:Float){
        No5DialogDiagnostics.observe?.invoke(No5DialogTextFit(key,value,paint.textSize,density.fontScale,available,
            RectF(0f,paint.ascent(),paint.measureText(value),paint.descent()),paint.color))
    }
    val heavy=remember(context){Typeface.createFromAsset(context.assets,"no5-header/barlow_condensed_extrabold.ttf")}
    val bold=remember(context){Typeface.createFromAsset(context.assets,"no5-header/barlow_condensed_semibold.ttf")}
    val regular=remember(context){Typeface.createFromAsset(context.assets,"no5-header/barlow_condensed_regular.ttf")}
    val bg=if(night)0xff000000.toInt()else 0xfff6f9fa.toInt()
    val ink=if(night)0xffed0000.toInt()else 0xff07121b.toInt()
    val line=if(night)0xff550000.toInt()else 0xffb5c2c8.toInt()
    val muted=if(night)0xffa40000.toInt()else 0xff526b7b.toInt()
    val accent=if(night)0xffed0000.toInt()else 0xffff5b00.toInt()
    val backdrop=remember(context){No5WorkspaceBackdrop(context.assets)}
    DisposableEffect(backdrop){onDispose{backdrop.close()}}
    val latestDismiss by rememberUpdatedState(onDismiss)
    Dialog(onDismissRequest={latestDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        CompositionLocalProvider(LocalDensity provides density){
        val view=LocalView.current
        val window=(view.parent as? DialogWindowProvider)?.window
        DisposableEffect(window){
            window?.apply{
                setDimAmount(0f);clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
                addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                WindowCompat.setDecorFitsSystemWindows(this,false)
                if(Build.VERSION.SDK_INT<30){@Suppress("DEPRECATION")
                    decorView.systemUiVisibility=decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY}
                WindowCompat.getInsetsController(this,decorView).apply{systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;hide(WindowInsetsCompat.Type.systemBars())}
            };onDispose{}
        }
        var keyboard by remember{mutableStateOf<android.graphics.Rect?>(null)}
        DisposableEffect(context){val sub=SpecialKeyboardGeometry.Subscription(context){keyboard=it};onDispose{sub.close()}}
        BoxWithConstraints(Modifier.fillMaxSize().testTag("$tag-stage")) {
            val scale=maxWidth.value/2000f
            val origin=IntArray(2).also{view.getLocationOnScreen(it)}
            val special=keyboard?.let{with(density){(it.top-origin[1]).coerceAtLeast(0).toDp()}}?:maxHeight
            val ime=with(density){WindowInsets.ime.getBottom(this).toDp()}
            val available=minOf(maxHeight,special,(maxHeight-ime).coerceAtLeast(0.dp))
            val height=available.value/scale
            // Body is already formatted by its owner; never translate user names or document lines.
            val bodyText=body
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=regular;textSize=29f*density.fontScale}
            val lines=remember(bodyText,density.fontScale){
                buildList{
                    bodyText?.split('\n')?.forEach{paragraph->
                        var rest=paragraph
                        if(rest.isEmpty())add("")
                        while(rest.isNotEmpty()){
                            val fit=p.breakText(rest,true,1064f,null).coerceAtLeast(1)
                            val split=if(fit<rest.length)rest.lastIndexOf(' ',fit).takeIf{it>0}?:fit else fit
                            add(rest.take(split));rest=rest.drop(split).trimStart()
                        }
                    }
                }
            }
            val panelW=if(form)1000f else 1120f
            val bodyW=panelW-56f
            val textHeight=if(lines.isEmpty())0f else 34f+(lines.size-1)*29f*density.fontScale*1.35f
            var slotHeight by remember { mutableFloatStateOf(0f) }
            val contentHeight=textHeight+rows.size*104f+slotHeight
            val wanted=preferredHeight?:max(if(form)246f else 410f,246f+contentHeight)
            // Canonical content ends before the preserved footer. More facts scroll;
            // a long diagnostic may not grow across the header or navigation.
            val bottom=minOf(contentBottom,height)
            val panelH=minOf(wanted,(bottom-178f).coerceAtLeast(222f))
            val panelY=minOf(if(form)220f else 240f,(bottom-panelH-24f).coerceAtLeast(0f))
            val panelX=(2000f-panelW)/2
            // Outside hits use the same cancellation-aware feedback path as other moment actions.
            Box(Modifier.fillMaxWidth().height(available).clipToBounds()) {
                Box(Modifier.fillMaxSize().no5Clickable(feedbackColor=Color.Transparent,onClick={latestDismiss()}))
                Canvas(Modifier.fillMaxSize()) {
                    val n=drawContext.canvas.nativeCanvas;val u=size.width/2000f;n.save();n.scale(u,u)
                    p.style=Paint.Style.FILL;p.color=if(compactClose)(bg and 0x00ffffff)or(241 shl 24)else bg;n.drawRect(0f,130f,2000f,bottom,p);backdrop.draw(n,night)
                    p.color=bg;n.drawRoundRect(RectF(panelX,panelY,panelX+panelW,panelY+panelH),4f,4f,p)
                    p.style=Paint.Style.STROKE;p.color=line;p.strokeWidth=1.5f;val radius=if(form)4f else 6f;n.drawRoundRect(RectF(panelX,panelY,panelX+panelW,panelY+panelH),radius,radius,p)
                    p.style=Paint.Style.FILL;p.typeface=heavy;p.textSize=35f*density.fontScale;p.color=ink
                    val heading=No5Text.text(context,title);n.drawText(heading,panelX+28,panelY+(if(form)62f else 58f),p)
                    No5DialogDiagnostics.observe?.invoke(No5DialogTextFit("$tag-title",heading,p.textSize,density.fontScale,bodyW,RectF(panelX+28,panelY+(if(form)62f else 58f)+p.ascent(),panelX+28+p.measureText(heading),panelY+(if(form)62f else 58f)+p.descent()),p.color))
                    p.color=line;n.drawLine(panelX+28,panelY+(if(form)90f else 86f),panelX+panelW-28,panelY+(if(form)90f else 86f),p);n.restore()
                }
                Box(Modifier.offset((panelX*scale).dp,(panelY*scale).dp).size((panelW*scale).dp,(panelH*scale).dp)
                    .testTag(tag).semantics{paneTitle=No5Text.text(context,title)}) {
                    // Consume the sheet's blank areas; they are not outside-dismiss targets.
                    Box(Modifier.fillMaxSize().no5Clickable(feedbackColor=Color.Transparent,onClick={}))
                    Box(Modifier.offset((28*scale).dp).size((bodyW*scale).dp,(86*scale).dp).testTag("$partsTag-title")
                        .semantics{heading();text=androidx.compose.ui.text.AnnotatedString(No5Text.text(context,title))})
                    key(bodyKey){Column(Modifier.offset((28*scale).dp,((if(form)105f else 106f)*scale).dp)
                        .size((bodyW*scale).dp,((panelH-(if(form)221f else 222f))*scale).dp).clipToBounds().verticalScroll(rememberScrollState()).padding(top=(if(form)scale else 0f).dp).testTag("$partsTag-body")) {
                        if(lines.isNotEmpty())Canvas(Modifier.fillMaxWidth().height((textHeight*scale).dp).semantics{contentDescription=bodyText.orEmpty()}){
                            val n=drawContext.canvas.nativeCanvas;val u=size.width/1064f;n.save();n.scale(u,u)
                            p.style=Paint.Style.FILL;p.color=ink;p.typeface=regular;p.textSize=29f*density.fontScale
                            lines.forEachIndexed{i,text->n.drawText(text,0f,34f+i*29f*density.fontScale*1.35f,p)};n.restore()
                        }
                        if(content!=null) {
                            val face=FontFamily(regular)
                            fun style(size:Float,weight:FontWeight=FontWeight.Normal)=TextStyle(fontFamily=face,
                                fontWeight=weight,fontSize=(size*scale).sp,lineHeight=(size*1.35f*scale).sp,letterSpacing=0.sp)
                            val colors=no5WorkspaceColorScheme(night,MaterialTheme.colorScheme)
                            MaterialTheme(colorScheme=colors,typography=Typography(
                                headlineLarge=style(40f,FontWeight.ExtraBold),headlineMedium=style(35f,FontWeight.ExtraBold),headlineSmall=style(35f,FontWeight.ExtraBold),
                                titleLarge=style(34f,FontWeight.SemiBold),titleMedium=style(30f,FontWeight.SemiBold),titleSmall=style(27f,FontWeight.SemiBold),
                                bodyLarge=style(29f),bodyMedium=style(29f),bodySmall=style(25f),
                                labelLarge=style(27f,FontWeight.SemiBold),labelMedium=style(25f),labelSmall=style(23f))) {
                                CompositionLocalProvider(LocalNo5ActionFeedbackColor provides Color(accent),
                                    androidx.compose.foundation.text.selection.LocalTextSelectionColors provides androidx.compose.foundation.text.selection.TextSelectionColors(Color(accent),Color(accent).copy(alpha=.33f))){
                                    Column(Modifier.fillMaxWidth().onSizeChanged{size->
                                        val next=size.height/(scale*density.density)
                                        if(kotlin.math.abs(next-slotHeight)>.01f)slotHeight=next
                                    }){content()}
                                }
                            }
                        }
                        rows.forEachIndexed{rowIndex,row->key(row.key){
                            val label=if(row.rawLabel)row.label else No5Text.text(context,row.label)
                            val detail=row.detail?.let{if(row.rawDetail)it else No5Text.text(context,it)}
                            val value=row.value?.let{if(row.rawValue)it else No5Text.text(context,it)}?:if(row.action!=null)"›"else""
                            Canvas(Modifier.fillMaxWidth().height((104*scale).dp).testTag(row.key)
                                .semantics{contentDescription=listOfNotNull(label,detail,value).joinToString(". ");if(row.control in listOf("check","toggle")){selected=row.selected;role=if(row.control=="toggle")Role.Switch else Role.Checkbox}}
                                .no5Clickable(enabled=row.enabled&&row.action!=null,feedbackColor=Color(accent),onClick={row.action?.invoke()})){
                                val n=drawContext.canvas.nativeCanvas;val u=size.width/1064f;n.save();n.scale(u,u)
                                p.style=Paint.Style.FILL;p.typeface=bold;p.textSize=30f*density.fontScale;p.color=if(row.enabled)ink else muted
                                fun shown(value:String,available:Float,raw:Boolean):String {
                                    if(!raw){report(row.key,value,p,available);return value}
                                    val count=p.breakText(value,true,available,null)
                                    return if(count<value.length)value.take((count-1).coerceAtLeast(0))+"…"else value
                                }
                                n.drawText(shown(label,624f,row.rawLabel),20f,43f,p)
                                if(detail!=null){p.typeface=regular;p.textSize=23f*density.fontScale;p.color=muted;n.drawText(shown(detail,884f,row.rawDetail),20f,78f,p)}
                                p.typeface=regular;p.textSize=28f*density.fontScale;p.color=if(row.selected)accent else muted;val actualValue=shown(value,400f,row.rawValue);n.drawText(actualValue,1044f-p.measureText(actualValue),49f,p)
                                if(row.control in listOf("check","toggle")){
                                    val ox=panelX+28f;val oy=panelY+106f+textHeight+slotHeight+104f*rowIndex
                                    n.save();n.translate(-ox,-oy)
                                    val cx=ox+696f;val cy=oy+48f;p.style=Paint.Style.STROKE;p.strokeWidth=2.5f;p.color=if(row.selected)accent else line
                                    if(row.control=="toggle")n.drawCircle(cx,cy,16f,p)else n.drawRoundRect(RectF(cx-16,cy-16,cx+16,cy+16),3f,3f,p)
                                    p.style=Paint.Style.FILL;if(row.selected){if(row.control=="toggle")n.drawCircle(cx,cy,8f,p)else{p.strokeWidth=3.5f;n.drawLine(cx-9,cy,cx-2,cy+8,p);n.drawLine(cx-2,cy+8,cx+10,cy-9,p)}};n.restore()}
                                p.color=line;p.strokeWidth=1.5f;n.drawLine(0f,95f,1064f,95f,p);n.restore()
                            }
                        }}
                    }
                    }
                    val actionW=if(compactClose&&!form&&actions.size==1)312f else (panelW-76f)/actions.size
                    val actionStart=if(compactClose&&!form&&actions.size==1)panelW-340f else 28f
                    Box(Modifier.offset(y=((panelH-116f)*scale).dp).size((panelW*scale).dp,(96*scale).dp).testTag("$partsTag-actions")){
                    actions.forEachIndexed{i,action->
                        No5ActionCanvas(Modifier.offset(((actionStart+i*(actionW+20))*scale).dp)
                            .size((actionW*scale).dp,(96*scale).dp).testTag(action.key)
                            .semantics{role=Role.Button;contentDescription=No5Text.text(context,action.description?:action.label)},
                            enabled=action.enabled,selected=action.selected,night=night,designWidth=actionW,designOrigin=androidx.compose.ui.geometry.Offset(panelX+actionStart+i*(actionW+20),panelY+panelH-116f),onClick=action.action){
                            val n=drawContext.canvas.nativeCanvas;val u=size.width/actionW;n.save();n.scale(u,u)
                            p.style=Paint.Style.FILL;p.typeface=bold;p.textSize=27f*density.fontScale;p.color=if(action.enabled)ink else muted
                            val label=No5Text.text(context,action.label);report(action.key,label,p,actionW-20f);n.drawText(label,(actionW-p.measureText(label))/2,58f,p);n.restore()
                        }
                    }
                    }
                }
            }
        }
    }
    }
}
