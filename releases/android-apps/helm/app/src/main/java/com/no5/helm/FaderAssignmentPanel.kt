package com.no5.helm

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.no5.domain.AudioFaderControl
import com.no5.ui.No5Text
import com.no5.ui.no5Clickable

/** Native layout evidence only; no production observer or retained text history. */
internal data class FaderTextFit(val text:String,val width:Float,val available:Float,val fontScale:Float,val dynamic:Boolean)
internal object FaderTemplateDiagnostics { var observe:((FaderTextFit)->Unit)?=null }

/** D03's native 800×620 assignment grammar; no player/fader geometry is owned here. */
@Composable internal fun FaderAssignmentPanel(scale:Float,night:Boolean,controller:AudioNativeController,
    draft:AudioPresentationSettings,dirty:Boolean,error:String?,onClose:()->Unit,onSave:()->Unit,
    update:(AudioFaderControl,String?,Boolean)->Unit,modifier:Modifier) {
    val context=LocalContext.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var details by rememberSaveable { mutableStateOf(false) }
    val palette=remember(night){FaderDialogPalette(night)}
    val selected=controller.selectedOutput
    fun name(c:AudioFaderControl)=controller.displayName(c,draft)
    Box(modifier.testTag("sound-setup-panel")) {
        FaderCanvas(800f,620f,palette,Modifier.fillMaxSize()) {
            box(0f,0f,800f,620f,palette.bg);box(0f,0f,800f,620f,palette.line,true,6f)
            text("Fader zuordnen",28f,58f,35f,heavy=true,maxWidth=744f);line(28f,86f,772f)
            text("Kanal",28f,118f,23f,muted=true,maxWidth=300f);text("fader.visibility",416f,118f,23f,muted=true,maxWidth=150f)
            text("Knopf links",600f,118f,23f,muted=true,maxWidth=172f)
        }
        Column(Modifier.offset(y=(130*scale).dp).height((386*scale).dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).testTag("sound-setup-channel-table")) {
            error?.let { androidx.compose.material3.Text(it,color=palette.accent,
                modifier=Modifier.padding((28*scale).dp).testTag("sound-setup-error"),
                fontFamily=FontFamily(Font(R.font.barlow_condensed_semibold)),fontSize=(25*scale).sp) }
            if(controller.faderControls.isEmpty()) androidx.compose.material3.Text(
                No5Text.text(context,"No validated Camilla fader catalog is available. Reload the Pi connection; no local fallback mapping is used."),
                modifier=Modifier.padding((28*scale).dp).testTag("sound-setup-controls-empty"),color=palette.ink)
            controller.faderControls.forEach { c ->
                val shown=draft.visible(c.gatewayId);val chosen=selected==c.physicalIndex
                Box(Modifier.fillMaxWidth().height((96*scale).dp).testTag("sound-setup-fader-${c.gatewayId.name}")) {
                    FaderCanvas(800f,96f,palette,Modifier.fillMaxSize()) {
                        text(name(c),28f,54f,31f,translate=false,maxWidth=300f,bold=true)
                        text("›",356f,54f,31f,muted=true,maxWidth=50f);choice(416f,false,shown);choice(632f,true,chosen);line(28f,95f,772f)
                    }
                    FaderHit(28f,0f,388f,96f,scale,"sound-setup-alias-${c.gatewayId.name}",No5Text.text(context,"Name for %s",name(c)),palette) {editing=c.gatewayId.name}
                    FaderHit(416f,0f,96f,96f,scale,"sound-setup-visible-${c.gatewayId.name}",No5Text.text(context,"Show %s",name(c)),palette,
                        toggle=shown) {update(c,draft.alias(c.gatewayId),!shown)}
                    FaderHit(632f,0f,96f,96f,scale,"sound-setup-knob-${c.gatewayId.name}",No5Text.text(context,"Assign %s to left knob",name(c)),palette,
                        enabled=c.limit!=null,selected=chosen) {controller.selectKnobFader(c)}
                }
            }
        }
        FaderButton("Zurück",28f,516f,180f,scale,palette,"sound-setup-close",action=onClose)
        FaderCanvas(800f,620f,palette,Modifier.fillMaxSize()) {text("Knopf: sofort",232f,571f,23f,muted=true,maxWidth=226f)}
        FaderHit(232f,516f,226f,96f,scale,"sound-setup-details",No5Text.text(context,"Technical details"),palette) { details=true }
        FaderButton("Anzeige speichern",500f,516f,272f,scale,palette,"sound-setup-save",enabled=dirty,selected=dirty,action=onSave)
    }
    if(details) FaderDetailsDialog(palette,controller,draft) { details=false }
    val control=controller.faderControls.singleOrNull{it.gatewayId.name==editing}
    if(control!=null) FaderNameDialog(scale,palette,control,draft.alias(control.gatewayId).orEmpty(),
        {editing=null},{value->update(control,value.takeIf{it.isNotBlank()},draft.visible(control.gatewayId));editing=null})
}

private class FaderDialogPalette(night:Boolean) {
    val bg=Color(if(night)0xff000000 else 0xfff6f9fa)
    val ink=Color(if(night)0xffed0000 else 0xff07121b)
    val muted=Color(if(night)0xffa40000 else 0xff526b7b)
    val line=Color(if(night)0xff550000 else 0xffb5c2c8)
    val accent=Color(if(night)0xffed0000 else 0xffff5b00)
    val selection=Color(if(night)0xff300000 else 0xffffebde)
}
private fun Color.argb()=android.graphics.Color.argb((alpha*255).toInt(),(red*255).toInt(),(green*255).toInt(),(blue*255).toInt())
private class FaderPainter(val c:android.graphics.Canvas,val p:Paint,val palette:FaderDialogPalette,
    val context:android.content.Context,val fontScale:Float,val origin:Offset=Offset.Zero) {
    private inline fun stage(draw:(Float,Float)->Unit){c.save();c.translate(-origin.x,-origin.y);try{draw(origin.x,origin.y)}finally{c.restore()}}
    fun box(x:Float,y:Float,w:Float,h:Float,color:Color,stroke:Boolean=false,r:Float=4f) {
        p.style=if(stroke)Paint.Style.STROKE else Paint.Style.FILL;p.strokeWidth=1.5f;p.color=color.argb()
        stage{ox,oy->c.drawRoundRect(RectF(ox+x,oy+y,ox+x+w,oy+y+h),r,r,p)};p.style=Paint.Style.FILL
    }
    fun line(x:Float,y:Float,end:Float){p.color=palette.line.argb();p.strokeWidth=1.5f;c.drawLine(x,y,end,y,p)}
    fun text(source:String,x:Float,y:Float,size:Float,heavy:Boolean=false,muted:Boolean=false,translate:Boolean=true,maxWidth:Float=Float.MAX_VALUE,bold:Boolean=false,dynamic:Boolean=!translate) {
        p.typeface=context.resources.getFont(if(heavy)R.font.barlow_condensed_extrabold else if(bold)R.font.barlow_condensed_semibold else R.font.barlow_condensed_regular)
        p.textSize=size*fontScale;p.color=(if(muted)palette.muted else palette.ink).argb();p.style=Paint.Style.FILL
        val value=if(translate)No5Text.text(context,source)else source
        FaderTemplateDiagnostics.observe?.invoke(FaderTextFit(value,p.measureText(value),maxWidth,fontScale,dynamic))
        if(p.measureText(value)<=maxWidth)c.drawText(value,x,y,p)
        else {
            // User names may wrap. Fixed labels must fit their approved font size.
            val first=p.breakText(value,true,maxWidth,null)
            c.drawText(value.substring(0,first),x,y-12f,p)
            val rest=value.substring(first);val limit=p.breakText(rest,true,maxWidth-p.measureText("…"),null)
            c.drawText(if(limit<rest.length)rest.substring(0,limit)+"…"else rest,x,y+25f,p)
        }
    }
    fun choice(x:Float,radio:Boolean,selected:Boolean) = stage{ox,oy->
        val cx=ox+x+48;val cy=oy+48f;p.color=(if(selected)palette.accent else palette.line).argb();p.style=Paint.Style.STROKE;p.strokeWidth=2.5f
        if(radio)c.drawCircle(cx,cy,16f,p)else c.drawRoundRect(RectF(cx-16,cy-16,cx+16,cy+16),3f,3f,p)
        p.style=Paint.Style.FILL
        if(selected)if(radio)c.drawCircle(cx,cy,8f,p)else{p.strokeWidth=3.5f;c.drawLine(cx-9,cy,cx-2,cy+8,p);c.drawLine(cx-2,cy+8,cx+10,cy-9,p)}
    }
}
@Composable private fun FaderCanvas(w:Float,h:Float,palette:FaderDialogPalette,modifier:Modifier,draw:FaderPainter.()->Unit) {
    val context=LocalContext.current;val font=LocalDensity.current.fontScale
    val paint=remember{Paint(Paint.ANTI_ALIAS_FLAG)}
    var location by remember{mutableStateOf(Offset.Zero)}
    Canvas(modifier.onGloballyPositioned{val next=it.positionInRoot();if(location!=next)location=next}){
        val n=drawContext.canvas.nativeCanvas;val u=size.width/w;n.save();n.scale(u,size.height/h)
        try{FaderPainter(n,paint,palette,context,font,Offset(location.x/u,location.y/u)).draw()}finally{n.restore()}}
}
@Composable private fun FaderHit(x:Float,y:Float,w:Float,h:Float,s:Float,tag:String,label:String,p:FaderDialogPalette,
    enabled:Boolean=true,selected:Boolean?=null,toggle:Boolean?=null,action:()->Unit) {
    Box(Modifier.offset((x*s).dp,(y*s).dp).size((w*s).dp,(h*s).dp).testTag(tag)
        .semantics {contentDescription=label;text=AnnotatedString(label);role=if(toggle!=null)Role.Checkbox else if(selected!=null)Role.RadioButton else Role.Button
            selected?.let{this.selected=it};toggle?.let{toggleableState=if(it)androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off}}
        .no5Clickable(enabled=enabled,feedbackColor=p.accent,onClick=action))
}
@Composable private fun FaderButton(label:String,x:Float,y:Float,w:Float,s:Float,palette:FaderDialogPalette,tag:String,
    enabled:Boolean=true,selected:Boolean=false,action:()->Unit) {
    Box(Modifier.offset((x*s).dp,(y*s).dp).size((w*s).dp,(96*s).dp)) {
        FaderCanvas(w,96f,palette,Modifier.fillMaxSize()) {
            if(selected)box(0f,0f,w,96f,palette.selection)
            box(0f,0f,w,96f,if(selected)palette.accent else palette.line,true)
            this.p.typeface=context.resources.getFont(R.font.barlow_condensed_semibold);this.p.textSize=27*fontScale
            val value=No5Text.text(context,label)
            text(value,(w-this.p.measureText(value))/2,58f,27f,muted=!enabled,translate=false,bold=true,maxWidth=w-20,dynamic=false)
        }
        FaderHit(0f,0f,w,96f,s,tag,No5Text.text(LocalContext.current,label),palette,enabled=enabled,action=action)
    }
}
@Composable private fun FaderNameDialog(s:Float,p:FaderDialogPalette,c:AudioFaderControl,initial:String,onCancel:()->Unit,onApply:(String)->Unit) {
    var value by rememberSaveable(c.gatewayId.name){mutableStateOf(initial)}
    var leave by remember{mutableStateOf(false)}
    fun close(){if(value!=initial)leave=true else onCancel()}
    val density=LocalDensity.current
    Dialog(onDismissRequest=::close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        CompositionLocalProvider(LocalDensity provides density){
        Box(Modifier.fillMaxSize()) {
            ImeFormViewport(s,440f,240f,1120f,400f,viewportTag="sound-setup-name-viewport") {
                BoxWithConstraints(Modifier.fillMaxSize().testTag("sound-setup-name-dialog")) {
                    val height=maxHeight.value/s
                    FaderCanvas(1120f,height,p,Modifier.fillMaxSize()) {
                        box(0f,0f,1120f,height,p.bg);box(0f,0f,1120f,height,p.line,true,6f)
                        text("Name ändern",28f,58f,35f,heavy=true);line(28f,86f,1092f)
                    }
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.height((106*s).dp))
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("sound-setup-name-body")) {
                            Box(Modifier.fillMaxWidth().height((138*s).dp)) {
                                FaderCanvas(1120f,138f,p,Modifier.fillMaxSize()) {
                                    text("Anzeigename",28f,24f,25f,muted=true);box(28f,42f,1064f,96f,p.accent,true)
                                }
                                com.no5.ui.No5DraftTextField(value,
                                    {if(it.length<=AudioFaderPresentation.MAX_ALIAS_LENGTH&&!it.any(Char::isISOControl))value=it},
                                    "sound-setup-name-input",true,androidx.compose.foundation.text.KeyboardOptions.Default,p.bg.red<.1f,s,
                                    Modifier.offset((48*s).dp,(42*s).dp).size((1024*s).dp,(96*s).dp),
                                    designWidth=1024f,fontPx=31f,baselineY=62f,description=No5Text.text(LocalContext.current,"Anzeigename"))
                            }
                        }
                        Box(Modifier.fillMaxWidth().height((116*s).dp).testTag("sound-setup-name-actions")) {
                            FaderButton("Zurück",28f,0f,180f,s,p,"sound-setup-name-cancel",action=::close)
                            FaderButton("action.apply",820f,0f,272f,s,p,"sound-setup-name-apply",selected=true,action={onApply(value)})
                        }
                    }
                }
            }
        }
        }
    }
    if(leave) UnsavedChangesDialog(onSave={onApply(value)},onDiscard=onCancel,onContinue={leave=false},
        night=p.bg.red<.1f)
}

@Composable private fun FaderDetailsDialog(p:FaderDialogPalette,controller:AudioNativeController,
    draft:AudioPresentationSettings,onClose:()->Unit) {
    val chosen=controller.faderControls.firstOrNull{it.physicalIndex==controller.selectedOutput}
    val rows=listOf(
        com.no5.header.artwork.No5WorkspaceView.Row("Anzeigename",value=chosen?.let{controller.displayName(it,draft)}?:No5Text.ui("Unbekannt"),rawValue=true,key="sound-setup-technical-${chosen?.gatewayId?.name?:"UNKNOWN"}"),
        com.no5.header.artwork.No5WorkspaceView.Row("Stabile Kanal-ID",value=chosen?.gatewayId?.name?.lowercase(java.util.Locale.ROOT)?:"—",rawValue=true,key="sound-setup-technical-id"),
        com.no5.header.artwork.No5WorkspaceView.Row("Knopfzuordnung","Wirkt sofort; getrennt von lokalen Anzeigenamen",key="sound-setup-technical-assignment"))
    com.no5.ui.No5DetailsDialog("Kanaldetails",p.bg.red<.1f,onClose,rows=rows,tag="sound-setup-details-dialog",
        actions=listOf(com.no5.header.artwork.No5WorkspaceView.Action("Schließen",key="sound-setup-details-close",action=onClose)))
}
