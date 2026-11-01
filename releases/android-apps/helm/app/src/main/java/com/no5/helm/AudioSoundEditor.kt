package com.no5.helm

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.focusable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.no5.domain.*
import com.no5.ui.No5Text
import com.no5.ui.no5Clickable
import com.no5.ui.No5ActionFeedback
import kotlin.math.*
import java.util.Locale

@Immutable internal class SoundEditorPalette(night:Boolean) {
    val background=Color(if(night)0xff000000 else 0xfff6f9fa)
    val ink=Color(if(night)0xffed0000 else 0xff07121b)
    val secondary=Color(if(night)0xffa40000 else 0xff526b7b)
    val outline=Color(if(night)0xff550000 else 0xffb5c2c8)
    val accent=Color(if(night)0xffed0000 else 0xffff5b00)
}
/** The same compact cabinet glyph is used for every speaker, including SUB. */
private fun drawSoundSpeakerGlyph(n:AndroidCanvas,paint:Paint,x:Float,y:Float,scale:Float,chosen:Boolean,p:SoundEditorPalette) {
    n.save();n.translate(x,y);n.scale(scale,scale)
    paint.alpha=255;paint.style=Paint.Style.FILL;paint.color=(if(chosen)p.accent else p.background).soundArgb()
    n.drawRoundRect(RectF(-26f,-33f,26f,33f),8f,8f,paint)
    paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;paint.color=(if(chosen)p.accent else p.ink).soundArgb()
    n.drawRoundRect(RectF(-26f,-33f,26f,33f),8f,8f,paint)
    paint.color=(if(chosen)p.background else p.ink).soundArgb();n.drawCircle(0f,9f,13f,paint)
    paint.style=Paint.Style.FILL;n.drawCircle(0f,-17f,4f,paint);n.restore()
}
/** Internal visual proof hook; null in production and never part of audio admission. */
internal data class SoundTextFit(val source:String,val fullText:String,val drawnText:String,val textSize:Float,
    val maxWidth:Float,val baseline:Float,val bounds:RectF)
internal data class SoundControlFit(val tag:String,val hit:RectF,val visual:RectF)
internal object SoundTemplateDiagnostics {
    var observe:((String,RectF)->Unit)?=null
    var observeFit:((SoundTextFit)->Unit)?=null
    var observeControl:((SoundControlFit)->Unit)?=null
    var observeSpeaker:((SoundSpeakerId,Boolean,Offset)->Unit)?=null
    var observeEqTarget:(()->Unit)?=null
}
private fun soundTextEvidence(source:String,full:String,drawn:String,paint:Paint,x:Float,baseline:Float,maxWidth:Float){
    if(SoundTemplateDiagnostics.observe==null&&SoundTemplateDiagnostics.observeFit==null)return
    val width=paint.measureText(drawn)
    val left=when(paint.textAlign){Paint.Align.RIGHT->x-width;Paint.Align.CENTER->x-width/2;else->x}
    val bounds=RectF(left,baseline+paint.ascent(),left+width,baseline+paint.descent())
    SoundTemplateDiagnostics.observe?.invoke(drawn,bounds)
    SoundTemplateDiagnostics.observeFit?.invoke(SoundTextFit(source,full,drawn,paint.textSize,maxWidth,baseline,bounds))
}
/** Exact design-space primitives from the approved revision-four SVG. */
private class SoundTemplateFonts(context:android.content.Context) {
    val semi=context.resources.getFont(R.font.barlow_condensed_semibold)
    val heavy=context.resources.getFont(R.font.barlow_condensed_extrabold)
}
private class SoundTemplatePainter(val canvas:AndroidCanvas,val context:android.content.Context,val palette:SoundEditorPalette,val fontScale:Float,
    fonts:SoundTemplateFonts,private val paint:Paint) {
    init {paint.reset();paint.isAntiAlias=true}
    private val semi=fonts.semi
    private val heavy=fonts.heavy
    fun text(value:String,x:Float,y:Float,size:Float,hero:Boolean=false,secondary:Boolean=false,translate:Boolean=true,align:Paint.Align=Paint.Align.LEFT,maxWidth:Float=Float.MAX_VALUE,accent:Boolean=false){
        paint.style=Paint.Style.FILL;paint.color=if(accent)palette.accent.soundArgb()else if(secondary)palette.secondary.soundArgb()else palette.ink.soundArgb();paint.typeface=if(hero)heavy else semi;paint.textSize=size*fontScale;paint.textAlign=align
        val full=if(translate)No5Text.text(context,value)else value
        var label=full
        if(paint.measureText(label)>maxWidth){while(label.isNotEmpty()&&paint.measureText(label+"…")>maxWidth)label=label.dropLast(1);label+="…"}
        canvas.drawText(label,x,y,paint)
        soundTextEvidence(value,full,label,paint,x,y,maxWidth)
    }
    fun centeredText(value:String,x:Float,centerY:Float,size:Float,maxWidth:Float){
        paint.typeface=semi;paint.textSize=size*fontScale
        val baseline=centerY-(paint.ascent()+paint.descent())/2
        text(value,x,baseline,size,translate=false,align=Paint.Align.CENTER,maxWidth=maxWidth)
    }
    fun rect(x:Float,y:Float,w:Float,h:Float,stroke:Boolean=false) {
        paint.style=if(stroke)Paint.Style.STROKE else Paint.Style.FILL;paint.strokeWidth=1.5f
        paint.color=(if(stroke)palette.outline else palette.background).soundArgb()
        canvas.drawRoundRect(RectF(x,y,x+w,y+h),4f,4f,paint);paint.style=Paint.Style.FILL
    }
    fun line(x:Float,y:Float,x2:Float,y2:Float,accent:Boolean=false,width:Float=1.4f,dashed:Boolean=false){paint.pathEffect=if(dashed)android.graphics.DashPathEffect(floatArrayOf(5f,8f),0f)else null;paint.color=if(accent)palette.accent.soundArgb()else palette.outline.soundArgb();paint.style=Paint.Style.STROKE;paint.strokeWidth=width;canvas.drawLine(x,y,x2,y2,paint);paint.pathEffect=null}
}
private fun Color.soundArgb()=android.graphics.Color.argb((alpha*255).roundToInt(),(red*255).roundToInt(),(green*255).roundToInt(),(blue*255).roundToInt())
@Composable private fun SoundTemplateCanvas(scale:Float,night:Boolean,draw:SoundTemplatePainter.()->Unit){
    val context=LocalContext.current;val fontScale=LocalDensity.current.fontScale;val p=remember(night){SoundEditorPalette(night)}
    val fonts=remember(context){SoundTemplateFonts(context)}
    val paint=remember{Paint(Paint.ANTI_ALIAS_FLAG)}
    Canvas(Modifier.fillMaxSize().graphicsLayer()){drawIntoCanvas{c->val n=c.nativeCanvas;n.save();val u=size.width/2000f;n.scale(u,u);n.clipRect(0f,130f,2000f,1058f);SoundTemplatePainter(n,context,p,fontScale,fonts,paint).draw();n.restore()}}
}
@Composable internal fun SoundTemplateButton(label:String,rect:AudioNativeRect,s:Float,p:SoundEditorPalette,onClick:()->Unit,enabled:Boolean=true,strong:Boolean=false,selected:Boolean?=null,plain:Boolean=false,testTag:String="",visualHeight:Float=rect.height,textSize:Float=26f,accessibilityLabel:String?=null,evidenceOrigin:Offset=Offset.Zero,neutralFill:Boolean=true,manualCenter:Boolean=false,accentUnderlay:Boolean=false){
    val context=LocalContext.current;val fontScale=LocalDensity.current.fontScale
    val buttonFont=remember(context){context.resources.getFont(R.font.barlow_condensed_semibold)}
    val translated=(if(label.startsWith("←  "))"←  "+No5Text.text(context,label.removePrefix("←  "))else No5Text.text(context,label))
    Canvas(Modifier.soundRegion(rect.x,rect.y,rect.width,rect.height,s).testTag(testTag).semantics{role=Role.Button;contentDescription=accessibilityLabel?.let{No5Text.text(context,it)}?:translated;selected?.let{this.selected=it}}.no5Clickable(enabled=enabled,feedbackColor=p.accent,onClick=onClick)){
        val u=size.width/rect.width
        drawIntoCanvas{c->val n=c.nativeCanvas;n.save();n.scale(u,u);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            val top=(rect.height-visualHeight)/2
            SoundTemplateDiagnostics.observeControl?.invoke(SoundControlFit(testTag,RectF(rect.x+evidenceOrigin.x,rect.y+evidenceOrigin.y,rect.right+evidenceOrigin.x,rect.bottom+evidenceOrigin.y),RectF(rect.x+evidenceOrigin.x,rect.y+top+evidenceOrigin.y,rect.right+evidenceOrigin.x,rect.y+top+visualHeight+evidenceOrigin.y)))
            if(!plain){
                paint.color=(if((strong&&enabled)||selected==true)Color(if(p.background.red<.1f)0xff300000 else 0xffffebde)else p.background).soundArgb()
                val origin=Offset(rect.x+evidenceOrigin.x,rect.y+evidenceOrigin.y)
                n.save();n.translate(-origin.x,-origin.y)
                if(accentUnderlay){
                    val fill=paint.color
                    paint.color=Color(if(p.background.red<.1f)0xff300000 else 0xffffebde).soundArgb()
                    n.drawRoundRect(RectF(origin.x,origin.y+top,origin.x+rect.width,origin.y+top+visualHeight),4f,4f,paint)
                    paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f;paint.color=p.accent.soundArgb()
                    n.drawRoundRect(RectF(origin.x,origin.y+top,origin.x+rect.width,origin.y+top+visualHeight),4f,4f,paint)
                    paint.style=Paint.Style.FILL;paint.color=fill
                }
                if(neutralFill || (strong&&enabled) || selected==true)n.drawRoundRect(RectF(origin.x,origin.y+top,origin.x+rect.width,origin.y+top+visualHeight),4f,4f,paint)
                paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f
                paint.color=(if((strong&&enabled)||selected==true)p.accent else p.outline).soundArgb()
                n.drawRoundRect(RectF(origin.x,origin.y+top,origin.x+rect.width,origin.y+top+visualHeight),4f,4f,paint);n.restore()
            }
            paint.style=Paint.Style.FILL;paint.typeface=buttonFont;paint.textSize=textSize*fontScale;paint.textAlign=if(plain||manualCenter)Paint.Align.LEFT else Paint.Align.CENTER;paint.color=(if(!enabled)p.secondary else if(plain&&selected==true)p.accent else p.ink).soundArgb()
            val x=if(plain)0f else if(manualCenter)(rect.width-paint.measureText(translated))/2 else rect.width/2;val baseline=if(rect.height==96f&&visualHeight==96f)58f else rect.height/2-(paint.ascent()+paint.descent())/2
            n.drawText(translated,x,baseline,paint)
            soundTextEvidence(label,translated,translated,paint,rect.x+x+evidenceOrigin.x,rect.y+baseline+evidenceOrigin.y,rect.width-20)
            n.restore()}
    }
}
@Composable private fun SoundTemplateTab(label:String,rect:AudioNativeRect,s:Float,p:SoundEditorPalette,selected:Boolean,onClick:()->Unit){
    val context=LocalContext.current;val fontScale=LocalDensity.current.fontScale
    Box(Modifier.soundRegion(rect.x,rect.y,rect.width,rect.height,s).semantics{role=Role.Tab;contentDescription=No5Text.text(context,label);this.selected=selected}.no5Clickable(feedbackColor=p.accent,onClick=onClick)){
        Canvas(Modifier.fillMaxSize()){val u=size.width/rect.width;drawIntoCanvas{c->val n=c.nativeCanvas;n.save();n.scale(u,u);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=context.resources.getFont(R.font.barlow_condensed_semibold);textSize=30f*fontScale;textAlign=Paint.Align.CENTER;color=(if(selected)p.ink else p.secondary).soundArgb()};val translated=No5Text.text(context,label);n.drawText(translated,rect.width/2,34f,paint);soundTextEvidence(label,translated,translated,paint,rect.x+rect.width/2,rect.y+34f,rect.width-16);if(selected){paint.color=p.accent.soundArgb();paint.strokeWidth=4f;n.drawLine(8f,55f,rect.width-8f,55f,paint)};n.restore()}}
    }
}
/** Only bounded public failure classes may appear in the status rail. */
internal fun audioSoundFooter(context:android.content.Context,ctl:AudioSoundUiController):String {
    if(ctl.experimentEnding)return No5Text.text(context,"Testende noch nicht bestätigt · Verbindung prüfen")
    if(ctl.eqBoundReason=="Grenze erreicht · EQ bleibt aktiv")return No5Text.text(context,"Grenze erreicht · EQ bleibt aktiv")
    if(ctl.eqTargetPending)return No5Text.text(context,if(ctl.eqTargetLimited)"Bandgrenze erreicht · EQ bleibt aktiv"else "Ziel ausstehend · durchgezogene Kurve: bestätigter Klang")
    if(ctl.eqActivationRejected)return No5Text.text(context,ctl.eqBoundReason?:"Vorschau nicht übernommen · Entwurf erhalten")
    if(ctl.eqBoundReason=="Bandbreiten angepasst · noch nicht gespeichert")return No5Text.text(context,ctl.eqBoundReason!!)
    if(ctl.experimental)return No5Text.text(context,"Testmodus · nur Probehören · Speichern gesperrt")
    if(!ctl.controlInk.available){
        val reason=ctl.controlInk.reason?.takeIf{it in setOf("NOT_STARTED","NO_SOUND_PORT","TRANSPORT","AUDIO_DECODE","SOUND_DECODE","BACKEND_UNAVAILABLE") || it.matches(Regex("HTTP_[1-5][0-9]{2}"))}?:"UNAVAILABLE"
        return No5Text.text(context,"Nicht verfügbar · %s",reason)
    }
    return No5Text.text(context,ctl.status?:when{
        ctl.conflicted->"Stand geändert · zuerst abgleichen"
        ctl.presetDirty||ctl.calibrationDirty->"Nicht gespeichert"
        !ctl.controlInk.writable->"Letzter bestätigter Stand · nicht bearbeitbar"
        else->"Konfiguration bestätigt"
    })
}
internal fun soundOperationError(ctl:AudioSoundUiController):String? = ctl.status?.takeIf{ctl.conflicted || it in setOf(
    "Abschluss unklar · zuerst abgleichen","Änderung nicht übernommen","Ziel nicht angenommen · keine Änderung bestätigt","Vorschau nicht übernommen · Entwurf erhalten")}
/** Native editor lives entirely above the existing footer and outside physical knob clearances. */
internal object AudioSoundGeometry {
    val chart=AudioNativeRect(140f,410f,1718f,410f)
    val work=AudioNativeRect(62f,146f,1876f,880f)
    val actions=AudioNativeRect(380f,948f,1240f,80f)
    fun safe()=listOf(chart,actions).all{KnobGeometry.safe(it.x,it.y,it.right,it.bottom)}
}
/** Optical60px row and physical48dp targets are deliberately independent at320dpi. */
internal object SoundReserveGeometry {
    const val CENTER_Y=928f
    const val VISUAL_HEIGHT=60f
    val down=AudioNativeRect(1420f,880f,96f,96f)
    val value=AudioNativeRect(1516f,880f,108f,96f)
    val up=AudioNativeRect(1624f,880f,96f,96f)
    fun visual(hit:AudioNativeRect)=AudioNativeRect(hit.x,898f,hit.width,VISUAL_HEIGHT)
}
private fun Modifier.soundRegion(x:Float,y:Float,w:Float,h:Float,s:Float)=offset((x*s).dp,(y*s).dp).size((w*s).dp,(h*s).dp)
@Composable internal fun AudioSoundEditor(scale:Float,night:Boolean,audio:AudioNativeController,onClose:()->Unit,visible:Boolean=true,registerClose:((()->Unit)?)->Unit,registerGuard:((((()->Unit)->Unit))?)->Unit) {
    val ctl=audio.soundEditor;val context=LocalContext.current;val fontScale=LocalDensity.current.fontScale;val p=remember(night){SoundEditorPalette(night)}
    val outputClips by remember(audio){derivedStateOf{
        audio.state.dsp.let{dsp->dsp.clippedSamples.takeIf{dsp.quality.status==TelemetryStatus.LIVE && dsp.hardwareAvailable==true}}
    }}
    val font=remember{FontFamily(Font(R.font.barlow_condensed_semibold))}
    val bodySize=(30*scale).coerceAtLeast(14f).sp
    val backdrop=remember(context){com.no5.header.artwork.No5WorkspaceBackdrop(context.assets)}
    DisposableEffect(backdrop){onDispose{backdrop.close()}}
    val speaker=audio.panel=="sound-speakers"
    val presetName by remember(ctl){derivedStateOf{ctl.preset?.name.orEmpty()}}
    val modelsAvailable by remember(ctl){derivedStateOf{ctl.preset!=null&&ctl.calibration!=null}}
    val area=if(speaker)SoundScope.CALIBRATION else SoundScope.PRESET
    fun close(){ctl.requestClose(area){
        if(speaker && ctl.calibrationReturn=="room"){audio.openSoundEditor(false,true);ctl.changeSection("room")}else onClose()
    }}
    DisposableEffect(ctl,speaker){ctl.attachStorage(context);ctl.visibility(true);registerClose(::close);registerGuard{done->ctl.requestClose(area,done)}
        onDispose{registerClose(null);registerGuard(null);ctl.visibility(false)} }
    LaunchedEffect(visible,ctl.section){ctl.visibility(visible)}
    BackHandler{close()}
    Box(Modifier.fillMaxSize().testTag("audio-sound-editor")){
        Canvas(Modifier.fillMaxSize()){
            val pixelsPerStage=size.width/2000f
            drawRect(p.background,topLeft=Offset(0f,130f*pixelsPerStage),size=androidx.compose.ui.geometry.Size(size.width,928f*pixelsPerStage))
            val native=drawContext.canvas.nativeCanvas;native.save();native.scale(pixelsPerStage,pixelsPerStage)
            backdrop.draw(native,night);native.restore()
        }
        SoundTemplateCanvas(scale,night){
            if(speaker) {
                text("Lautsprecher abgleichen",360f,208f,40f,hero=true,maxWidth=1280f)
                text("Stingher 800 GT · separat vom Preset",360f,246f,24f,secondary=true,maxWidth=1280f)
            } else {
                if(ctl.section!="room")rect(48f,190f,710f,58f)
                text(if(ctl.newPresetDraft)No5Text.text(context,"NEUES PRESET")else presetName,58f,if(ctl.section=="room")236f else 234f,if(ctl.section=="room")49f else 40f,hero=true,translate=false,maxWidth=690f)
                line(58f,268f,1940f,268f)
                text("EQ UND RAUM IM SELBEN PRESET",1375f,166f,22f,secondary=true)
            }
            line(344f,990f,1656f,990f)
            if(ctl.newPresetDraft && ctl.status in setOf(null,"Probehören · noch nicht gespeichert")) {
                PresetLibraryInk(context,canvas,night,fontScale).text("Neu · ungespeichert · Verwerfen aktiviert den vorherigen Klang",344f,1032f,24f,color=PresetLibraryInk(context,canvas,night,fontScale).muted)
            }else text(audioSoundFooter(context,ctl),344f,1032f,if(speaker)22f else 24f,secondary=true,accent=!speaker&&(ctl.experimental||ctl.experimentEnding),translate=false,maxWidth=1590f)
        }
        SoundTemplateButton(if(speaker&&ctl.calibrationReturn=="room")"←  Zurück zu Raum"else if(speaker)"‹ Audio"else"←  Zurück zu AUDIO",
            if(speaker)AudioNativeRect(58f,146f,238f,96f)else AudioNativeRect(58f,143f,580f,49f),scale,p,onClick=::close,plain=!speaker)
        val dirty=if(speaker)ctl.calibrationDirty else ctl.presetDirty
        fun saveCurrent() {
            if(dirty)ctl.save(area){
                if(speaker&&ctl.calibrationReturn=="room"){audio.openSoundEditor(false,true);ctl.changeSection("room")}else onClose()
            } else close()
        }
        if(!speaker){
            SoundTemplateButton("Speichern & zurück",AudioNativeRect(1540f,170f,400f,96f),scale,p,
                enabled=ctl.editable&&ctl.canSavePreset,visualHeight=66f,strong=ctl.canSavePreset,accentUnderlay=!ctl.canSavePreset,testTag="sound-save",onClick=::saveCurrent)
            SoundTemplateTab("EQ",AudioNativeRect(779f,193f,140f,55f),scale,p,ctl.section=="eq"){ctl.changeSection("eq")}
            SoundTemplateTab("Raum",AudioNativeRect(945f,193f,171f,55f),scale,p,ctl.section=="room"){ctl.changeSection("room")}
        }
        if(!ctl.controlInk.available || !modelsAvailable){
            Column(Modifier.soundRegion(380f,380f,1240f,350f,scale),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                Text(No5Text.text(context,"Klangbearbeitung nicht verfügbar"),fontFamily=font,fontSize=(42*scale).sp,color=p.ink)
                Text(No5Text.text(context,"Es werden keine Messwerte oder Lautsprechermaße ersetzt."),fontFamily=font,fontSize=bodySize,color=p.secondary)
                SoundOutlinedButton(p,onClick=ctl::refresh){Text(No5Text.text(context,"Erneut abgleichen"),color=p.ink)}
            }
        }else{
            when(ctl.section){
                "eq"->SoundEqPage(scale,night,ctl,p,font,outputClips)
                "room"->SoundRoomPage(scale,night,ctl,p,font){audio.openSoundEditor(true,true)}
                "measure"->SoundMeasurements(scale,night,ctl,p,font)
                "place"->SoundPositions(scale,night,ctl,p,font)
                "positions"->SoundExactPosition(scale,night,ctl,p,font)
                else->SoundAlignmentPage(scale,night,ctl,p,font,::saveCurrent)
            }
        }
        if(ctl.presetNameRequested){
            PresetWorkflowDialog(night,"Preset speichern",null,No5Text.text(context,"Wird erst jetzt dauerhaft angelegt."),"Speichern",ctl.busy,
                soundOperationError(ctl)?:if(ctl.presetName.isNotBlank()&&!SoundPresetName.valid(ctl.presetName))"Name ist zu lang."else null,ctl::cancelPresetName,ctl::confirmPresetName,tag="audio-save-new-preset-dialog",name=ctl.presetName,onName=ctl::editPresetName,canConfirm=SoundPresetName.valid(ctl.presetName)&&ctl.editable)
        }else if(ctl.closeDecision==SoundScope.PRESET && ctl.newPresetDraft){
            PresetWorkflowDialog(night,"Neues Preset verwerfen?",No5Text.text(context,"Der leere Entwurf wird entfernt."),
                No5Text.text(context,if(ctl.conflicted)"Stand geändert · Verwerfen entfernt nur den lokalen Entwurf."else"Danach ist der vorherige Preset wieder aktiv."),"Verwerfen",ctl.busy,soundOperationError(ctl),ctl::continueEditing,ctl::discard,tag="audio-discard-new-preset-dialog")
        }else if(ctl.closeDecision!=null){
            val caption=if(ctl.closeDecision==SoundScope.PRESET)"EQ oder Raumwirkung wurden geändert."
                else if(ctl.measurementDirty)"Die Abstände sind noch nicht übernommen."else"Der Lautsprecherabgleich wurde geändert."
            com.no5.ui.No5DecisionDialog(title="Änderungen behalten?",body=caption,night=night,
                font=remember{FontFamily(Font(R.font.barlow_condensed_regular))},
                onSave=ctl::saveAndClose,onDiscard=ctl::discard,onContinue=ctl::continueEditing,
                busy=ctl.busy,canSave=ctl.editable&&ctl.canSavePreset&&!ctl.hasInvalidGeometry&&(!ctl.measurementDirty||ctl.measurementDraft?.dimensions()!=null),
                error=when {
                    ctl.closeDecision==SoundScope.CALIBRATION&&ctl.hasInvalidGeometry -> "Maße unvollständig oder ungültig"
                    ctl.closeDecision==SoundScope.CALIBRATION&&ctl.measurementDirty&&ctl.measurementDraft?.dimensions()==null ->
                        ctl.measurementError?:"Beide Abstände zwischen 0,05 und 50 Metern eingeben"
                    else -> ctl.status?.takeIf{it in setOf("Änderung nicht übernommen","Abschluss unklar · zuerst abgleichen","Ziel nicht angenommen · keine Änderung bestätigt")}
                },
                prefix="sound-unsaved",dialogTag="sound-unsaved-decision")
        }
    }
}
/** Stable subscriptions: value-only curve updates are read in draw/placement,
 * while the control tree observes only IDs, availability, selection and limits.
 */
@Stable private class SoundEqPageState(ctl:AudioSoundUiController) {
    val model=derivedStateOf(structuralEqualityPolicy()){ctl.preset}
    private val points=derivedStateOf(structuralEqualityPolicy()){model.value?.eqPoints.orEmpty()}
    val pointIds=derivedStateOf(structuralEqualityPolicy()){points.value.map{it.id}}
    val selectedId=derivedStateOf(structuralEqualityPolicy()){ctl.selectedPoint}
    val selected=derivedStateOf(structuralEqualityPolicy()){points.value.firstOrNull{it.id==selectedId.value}}
    val reserve=derivedStateOf(structuralEqualityPolicy()){model.value?.reserveDb?:12.0}
    val requestedEnabled=derivedStateOf(structuralEqualityPolicy()){model.value?.eqEnabled==true}
    private val frequencies=DoubleArray(512){i->20.0*1000.0.pow(i/511.0)}
    val response=derivedStateOf(structuralEqualityPolicy()){SoundEqResponse.gainsDb(points.value,frequencies)}
    private val automaticRange=derivedStateOf(structuralEqualityPolicy()){max(12.0,ceil(response.value.maxOf{abs(it)}/6.0)*6.0)}
    val range=derivedStateOf(structuralEqualityPolicy()){ctl.eqGestureRange?:automaticRange.value}
}
@Composable private fun SoundEqPage(scale:Float,night:Boolean,ctl:AudioSoundUiController,p:SoundEditorPalette,font:FontFamily,outputClips:Long?){
    val context=LocalContext.current
    val page=remember(ctl){SoundEqPageState(ctl)}
    val pointIds by page.pointIds
    val reserve by page.reserve
    val selectedId by page.selectedId
    val requestedEnabled by page.requestedEnabled
    val chartRange by page.range
    val pixelsPerStage=scale*LocalDensity.current.density
    val spectrumAvailable by remember(ctl){derivedStateOf{ctl.spectrumInk!=null}}
    val chart=AudioSoundGeometry.chart
    fun stageX(f:Double)=(chart.x+ln(f.coerceIn(20.0,20000.0)/20)/ln(1000.0)*chart.width).toFloat()
    fun stageY(g:Double)=(chart.y+(chartRange-g)/(2*chartRange)*chart.height).toFloat()
    SoundTemplateCanvas(scale,night){
        val model=page.model.value?:return@SoundTemplateCanvas
        val selected=page.selected.value
        if(selected!=null){text("AUSGEWÄHLTER PUNKT",58f,310f,22f,secondary=true,maxWidth=290f);text("FREQUENZ",365f,307f,22f,secondary=true);text("${soundNumber(selected.frequencyHz)} Hz",365f,349f,48f,hero=true,translate=false,maxWidth=280f)
            text("ANHEBUNG",667f,307f,22f,secondary=true);text("${soundSigned(selected.gainDb)} dB",667f,349f,48f,hero=true,translate=false,maxWidth=290f)
            text("WIRKUNGSBREITE",978f,307f,22f,secondary=true,maxWidth=285f);text("${soundNumber(selected.widthHz)} Hz",978f,349f,48f,hero=true,translate=false,maxWidth=280f)
        }else PresetLibraryInk(context,canvas,night,fontScale).apply{
            text("KEIN PUNKT AUSGEWÄHLT",58f,310f,22f,1130f,color=muted)
            text("Mit + Punkt eine EQ-Stelle anlegen.",58f,350f,28f,1130f)
        }
        text(if(!spectrumAvailable)"SPEKTRUM · NICHT VERFÜGBAR"else"LIVE · NACH EQ, VOR RAUM",140f,390f,22f,secondary=true,maxWidth=470f)
        line(620f,383f,650f,383f,accent=true,width=4f);text(if(ctl.eqActuallyEnabled)"EQ-KURVE · RESERVE SEPARAT"else"EQ AUS · KURVE GESPEICHERT",666f,390f,22f,secondary=true,maxWidth=460f)
        text("Wirksame Reserve: %s dB".let{No5Text.text(context,it,soundNumber(ctl.observationInk.headroomDb))},1858f,390f,22f,secondary=true,translate=false,align=Paint.Align.RIGHT,maxWidth=710f)
        text("dBFS",58f,390f,24f,translate=false);text("dB",1915f,390f,24f,translate=false,align=Paint.Align.RIGHT)
        repeat(7){k->val y=410f+k*410f/6;text(if(k==0)"0"else"−${k*15}",115f,y+8,24f,secondary=true,translate=false,align=Paint.Align.RIGHT);val v=chartRange-k*chartRange/3;text(if(v==0.0)"0"else soundSigned(v,0),1884f,y+8,24f,secondary=true,translate=false)}
        listOf(20.0,50.0,100.0,200.0,500.0,1000.0,2000.0,5000.0,10000.0,20000.0).forEach{f->text(if(f>=1000)"${soundNumber(f/1000).removeSuffix(".0")}k"else f.toInt().toString(),stageX(f),860f,24f,secondary=true,translate=false,align=Paint.Align.CENTER)}
        canvas.drawRect(1010f,358f,1540f,402f,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=palette.background.soundArgb()})
        text("Reglermaximum: +%s dB".let{No5Text.text(context,it,soundReserveNumber(ctl.eqPointMaximum))},1040f,390f,22f,secondary=true,translate=false,maxWidth=500f)
        text(SoundEqBands.firstOverlap(model.eqPoints)?.let{(a,b)->No5Text.text(context,"Punkte %s und %s überlappen · Breiten verkleinern",(a+1).toString(),(b+1).toString())}?:ctl.eqBoundReason?.takeUnless{it=="Grenze erreicht · EQ bleibt aktiv"}?:if(ctl.addingPoint)"Jetzt die gewünschte Frequenz im Diagramm antippen."else if(night)"Rotnacht: Spektrum rot · Tagesauswahl bleibt erhalten."else"Breite direkt an den Griffen ändern · Schloss fixiert die Breite.",344f,984f,22f,secondary=true,maxWidth=1000f)
    }
    Canvas(Modifier.soundRegion(chart.x,chart.y,chart.width,chart.height,scale).testTag("sound-eq-chart").clipToBounds()
        .semantics{contentDescription=No5Text.text(context,"Equalizer: + Punkt und Frequenz antippen oder doppelt tippen; Punkte und Breitengriffe ziehen")}
){
        val w=size.width;val h=size.height;val unit=w/chart.width;val grid=if(night)Color(0xff390000)else Color(0xffdce4e6)
        listOf(20.0,50.0,100.0,200.0,500.0,1000.0,2000.0,5000.0,10000.0,20000.0).forEach{f->val x=(ln(f/20)/ln(1000.0)*w).toFloat();drawLine(grid,Offset(x,0f),Offset(x,h),1.3f*unit)}
        repeat(7){k->drawLine(if(k==3)p.outline else grid,Offset(0f,k*h/6),Offset(w,k*h/6),if(k==3)1.7f*unit else 1.2f*unit,pathEffect=if(k==3)androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(7*unit,7*unit))else null)}
    }
    SoundEqSpectrum(scale,night,ctl,p,chart)
    Canvas(Modifier.soundRegion(chart.x,chart.y,chart.width,chart.height,scale).clipToBounds()){
        val model=page.model.value?:return@Canvas
        val selected=page.selected.value
        val response=page.response.value
        val w=size.width;val h=size.height;val unit=w/chart.width
        model.eqPoints.filter{it.gainDb>0&&it.id!=selected?.id}.forEach{point->
            val l=(ln(point.lowHz.coerceIn(20.0,20000.0)/20)/ln(1000.0)*w).toFloat();val r=(ln(point.highHz.coerceIn(20.0,20000.0)/20)/ln(1000.0)*w).toFloat();val y=((chartRange-point.gainDb)/(2*chartRange)*h).toFloat()
            drawRect(p.accent.copy(alpha=.025f),Offset(l,0f),androidx.compose.ui.geometry.Size(r-l,h))
            drawLine(p.accent.copy(alpha=.3f),Offset(l,y),Offset(r,y),1.3f*unit)
            listOf(l,r).forEach{x->drawLine(p.accent.copy(alpha=.3f),Offset(x,y-7*unit),Offset(x,y+7*unit),1.3f*unit)}
        }
        selected?.let{point->val l=(ln(point.lowHz.coerceIn(20.0,20000.0)/20)/ln(1000.0)*w).toFloat();val r=(ln(point.highHz.coerceIn(20.0,20000.0)/20)/ln(1000.0)*w).toFloat();val y=((chartRange-point.gainDb)/(2*chartRange)*h).toFloat();drawRect(p.accent.copy(alpha=.04f),Offset(l,0f),androidx.compose.ui.geometry.Size(r-l,h))
            val effect=if(point.widthLocked)androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4*unit,3*unit))else null
            drawLine(p.accent,Offset(l,y),Offset(r,y),1.5f*unit,pathEffect=effect);listOf(l,r).forEach{x->drawLine(p.accent,Offset(x,y-21*unit),Offset(x,y+21*unit),1.5f*unit,pathEffect=effect)}
        }
        val curve=Path();response.forEachIndexed{i,g->val y=((chartRange-g)/(2*chartRange)*h).toFloat();if(i==0)curve.moveTo(0f,y)else curve.lineTo(i/511f*w,y)};drawPath(curve,p.accent.copy(alpha=if(ctl.eqActuallyEnabled)1f else .42f),style=Stroke(4*unit,pathEffect=if(ctl.eqActuallyEnabled)null else androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8*unit,6*unit))))
        model.eqPoints.forEach{point->val x=(ln(point.frequencyHz/20)/ln(1000.0)*w).toFloat();val y=((chartRange-point.gainDb)/(2*chartRange)*h).toFloat();val chosen=point.id==ctl.selectedPoint
            if(chosen){drawLine(p.accent.copy(alpha=.2f),Offset(x,0f),Offset(x,h),1.5f*unit,pathEffect=androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4*unit,6*unit)));drawCircle(p.accent.copy(alpha=.12f),25*unit,Offset(x,y))}
            drawCircle(if(chosen)p.accent else p.background,if(chosen)11*unit else 7*unit,Offset(x,y));drawCircle(p.accent,if(chosen)11*unit else 7*unit,Offset(x,y),style=Stroke(3*unit))
        }
        selected?.let{point->val y=((chartRange-point.gainDb)/(2*chartRange)*h).toFloat();listOf(point.lowHz,point.highHz).forEach{f->val x=(ln(f.coerceIn(20.0,20000.0)/20)/ln(1000.0)*w).toFloat();drawRoundRect(if(point.widthLocked)p.secondary else p.background,Offset(x-(if(point.widthLocked)4 else 5)*unit,y-(if(point.widthLocked)7 else 12)*unit),androidx.compose.ui.geometry.Size((if(point.widthLocked)8 else 10)*unit,(if(point.widthLocked)14 else 24)*unit),androidx.compose.ui.geometry.CornerRadius(3*unit));if(!point.widthLocked)drawRoundRect(p.accent,Offset(x-5*unit,y-12*unit),androidx.compose.ui.geometry.Size(10*unit,24*unit),androidx.compose.ui.geometry.CornerRadius(3*unit),style=Stroke(2*unit))}}
    }
    // DIR1: this layer alone follows local pointer intent; the actual curve above
    // continues to use AUDIO_BLOCK_READBACK. No full-curve recomputation per move.
    // This unconfirmed target changes at pointer cadence. Give it its own
    // display list so it cannot invalidate the editor's entire root surface.
    // Default Auto compositing preserves the existing pixels and overflow.
    Canvas(Modifier.soundRegion(chart.x,chart.y,chart.width,chart.height,scale).graphicsLayer()){
        traceEqStage("No5.EQ.TargetDraw"){
            val target=ctl.eqTarget?:return@traceEqStage
            val actual=page.model.value?.eqPoints?.firstOrNull{it.id==target.id}?:return@traceEqStage
            val u=size.width/chart.width
            fun x(f:Double)=(ln(f/20)/ln(1000.0)*size.width).toFloat()
            fun y(g:Double)=((chartRange-g)/(2*chartRange)*size.height).toFloat()
            val from=Offset(x(actual.frequencyHz),y(actual.gainDb));val to=Offset(x(target.frequencyHz),y(target.gainDb))
            drawLine(p.accent.copy(alpha=100f/255),from,to,3f*u,pathEffect=androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f*u,5f*u)))
            drawCircle(p.accent,14f*u,to,style=Stroke(3f*u));drawCircle(p.accent,3f*u,to)
            SoundTemplateDiagnostics.observeEqTarget?.invoke()
        }
    }
    Canvas(Modifier.soundRegion(131f,410f,4f,410f,scale)){drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(if(night)listOf(Color(0xffef190b),Color(0xff350000))else if(ctl.spectrumStyle=="gray")listOf(Color(0xff5b6970),Color(0xffb1bec2))else listOf(Color(0xffdc442d),Color(0xffe5be4a),Color(0xff75ab88))))}
    // One touch owner chooses the nearest target at DOWN and keeps it for the whole gesture.
    // Semantics and keyboard remain separate; overlapping transparent boxes never intercept touch.
    Box(Modifier.soundRegion(chart.x-48f,chart.y-48f,chart.width+96f,chart.height+96f,scale)
        .soundEqTouch(ctl,chart.width,chart.height,pixelsPerStage,chartRange))
    // Semantics/keyboard use the same point owner as touch; no second value or Q slider exists.
    pointIds.forEachIndexed { index,id -> key(id) { SoundEqPointNode(index,id,scale,ctl,page) } }
    SoundEqWidthControls(scale,night,ctl,p,page)
    SoundTemplateButton(if(ctl.eqActuallyEnabled)"EQ ein"else if(ctl.eqActivationRejected||requestedEnabled)"EQ nicht aktiv"else"EQ aus",AudioNativeRect(1150f,185f,340f,66f),scale,p,{ctl.setEqEnabled(!requestedEnabled)},enabled=ctl.editable,selected=ctl.eqActuallyEnabled,testTag="sound-eq-enabled")
    SoundTemplateButton("EQ neutral",AudioNativeRect(1632f,296f,308f,60f),scale,p,ctl::neutral,enabled=ctl.editable)
    SoundTemplateButton(if(ctl.addingPoint)"Punkt setzen …"else"+ Punkt",AudioNativeRect(344f,898f,190f,60f),scale,p,ctl::togglePointPlacement,enabled=ctl.editable&&pointIds.size<16,selected=ctl.addingPoint,testTag="sound-add-point")
    SoundTemplateButton("Punkt entfernen",AudioNativeRect(552f,898f,240f,60f),scale,p,ctl::removePoint,enabled=ctl.editable&&selectedId!=null,testTag="sound-remove-point")
    listOf("gray" to "Grau","color" to "Pegel","line" to "Linie").forEachIndexed{i,(style,label)->SoundTemplateButton(label,AudioNativeRect(820f+i*120,880f,100f,96f),scale,p,{ctl.spectrumStyle=style},selected=ctl.spectrumStyle==style,visualHeight=60f,textSize=26f)}
    if(ctl.controlInk.capabilities.experimentalEq)SoundTemplateButton("Test +3 dB",AudioNativeRect(1180f,880f,200f,96f),scale,p,
        {ctl.useExperimental(!ctl.experimental)},enabled=ctl.editable&&!ctl.experimentEnding&&!ctl.eqPending,selected=ctl.experimental,
        visualHeight=60f,textSize=26f,testTag="sound-eq-experimental",accessibilityLabel="Experimentelle EQ-Anhebung bis 3 dB über Reserve")
    val clipText=if(outputClips==null)No5Text.text(context,"Ausgangs-Clips gesamt: unbekannt")else No5Text.text(context,"Ausgangs-Clips gesamt: %s",outputClips.toString())
    SoundEqPeakCaption(scale,night,ctl,clipText)
    SoundTemplateCanvas(scale,night){
        text("RESERVE",1570f,884f,22f,secondary=true,align=Paint.Align.CENTER,maxWidth=300f)
        centeredText("${soundReserveNumber(reserve)} dB",1570f,SoundReserveGeometry.CENTER_Y,32f,maxWidth=108f)
    }
    SoundTemplateButton("−",SoundReserveGeometry.down,scale,p,{ctl.adjustReserve(-1)},enabled=ctl.editable&&reserve>6.0,testTag="sound-reserve-down",visualHeight=SoundReserveGeometry.VISUAL_HEIGHT,textSize=32f,accessibilityLabel="Pegelreserve verringern")
    SoundTemplateButton("+",SoundReserveGeometry.up,scale,p,{ctl.adjustReserve(1)},enabled=ctl.editable&&reserve<18.0,testTag="sound-reserve-up",visualHeight=SoundReserveGeometry.VISUAL_HEIGHT,textSize=32f,accessibilityLabel="Pegelreserve erhöhen")
    val reserveTarget=SoundReserveGeometry.value
    Box(Modifier.soundRegion(reserveTarget.x,reserveTarget.y,reserveTarget.width,reserveTarget.height,scale).testTag("sound-reserve-value").semantics{
        contentDescription=No5Text.text(context,"Pegelreserve %s dB",soundNumber(reserve));progressBarRangeInfo=androidx.compose.ui.semantics.ProgressBarRangeInfo(reserve.toFloat(),6f..18f,11)
        setProgress{value->if(ctl.editable){ctl.setReserve(value.toDouble());true}else false}
    }.onKeyEvent{event->if(event.type!=KeyEventType.KeyDown||!ctl.editable)false else when(event.key){Key.DirectionLeft,Key.DirectionDown->{ctl.adjustReserve(-1);true};Key.DirectionRight,Key.DirectionUp->{ctl.adjustReserve(1);true};else->false}}.focusable())
}
@Composable private fun SoundEqPointNode(index:Int,id:String,scale:Float,ctl:AudioSoundUiController,page:SoundEqPageState) {
    val context=LocalContext.current
    val density=LocalDensity.current.density
    val chart=AudioSoundGeometry.chart
    val current=remember(page,id){derivedStateOf{page.model.value?.eqPoints?.firstOrNull{it.id==id}}}
    Box(Modifier.offset {
        val point=current.value
        if(point==null)androidx.compose.ui.unit.IntOffset.Zero else {
            val x=(chart.x+ln(point.frequencyHz.coerceIn(20.0,20000.0)/20)/ln(1000.0)*chart.width).toFloat()-26
            val range=page.range.value
            val y=(chart.y+(range-point.gainDb)/(2*range)*chart.height).toFloat()-26
            // Same Float multiply order and pixel rounding as the original Dp offset.
            androidx.compose.ui.unit.IntOffset(((x*scale)*density).roundToInt(),((y*scale)*density).roundToInt())
        }
    }.size((52f*scale).dp,(52f*scale).dp).testTag("sound-eq-point-$id").semantics {
        val point=current.value?:return@semantics
        contentDescription=No5Text.text(context,"EQ-Punkt %s: %s Hz, %s dB",(index+1).toString(),soundNumber(point.frequencyHz),soundNumber(point.gainDb))
        stateDescription=if(point.widthLocked)No5Text.text(context,"Breite gesperrt")else"${soundNumber(point.widthHz)} Hz"
        onClick{ctl.choosePoint(point.id);true}
        customActions=listOf(CustomAccessibilityAction(No5Text.text(context,"Anheben")){ctl.updatePoint(point.move(point.frequencyHz,point.gainDb+.1));true},CustomAccessibilityAction(No5Text.text(context,"Absenken")){ctl.updatePoint(point.move(point.frequencyHz,point.gainDb-.1));true})
    }.onKeyEvent { event ->
        val point=current.value
        if(point==null||event.type!=KeyEventType.KeyDown||!ctl.editable)false else when(event.key) {
            Key.DirectionLeft->{ctl.updatePoint(point.move(point.frequencyHz/1.02,point.gainDb));true}
            Key.DirectionRight->{ctl.updatePoint(point.move(point.frequencyHz*1.02,point.gainDb));true}
            Key.DirectionUp->{ctl.updatePoint(point.move(point.frequencyHz,point.gainDb+.1));true}
            Key.DirectionDown->{ctl.updatePoint(point.move(point.frequencyHz,point.gainDb-.1));true}
            else->false
        }
    }.focusable())
}
@Composable private fun SoundEqWidthControls(scale:Float,night:Boolean,ctl:AudioSoundUiController,p:SoundEditorPalette,page:SoundEqPageState) {
    val context=LocalContext.current
    val point=page.selected.value?:return
    val chart=AudioSoundGeometry.chart
    val chartRange by page.range
    fun stageX(f:Double)=(chart.x+ln(f.coerceIn(20.0,20000.0)/20)/ln(1000.0)*chart.width).toFloat()
    fun stageY(g:Double)=(chart.y+(chartRange-g)/(2*chartRange)*chart.height).toFloat()
    listOf(true to point.lowHz,false to point.highHz).forEach{(low,f)->Box(Modifier.soundRegion(stageX(f)-26,stageY(point.gainDb)-30,52f,60f,scale).testTag(if(low)"sound-width-low"else"sound-width-high").semantics{contentDescription=No5Text.text(context,if(low)"Untere Breitengrenze"else"Obere Breitengrenze");stateDescription=if(point.widthLocked)No5Text.text(context,"Breite gesperrt")else"${soundNumber(f)} Hz";customActions=if(point.widthLocked)emptyList()else listOf(CustomAccessibilityAction(No5Text.text(context,"Breiter")){ctl.updatePoint(point.withBandEdge(f*if(low).95 else 1.05,low));true},CustomAccessibilityAction(No5Text.text(context,"Schmaler")){ctl.updatePoint(point.withBandEdge(f*if(low)1.05 else .95,low));true})}
        .onKeyEvent{e->if(e.type!=KeyEventType.KeyDown||point.widthLocked||!ctl.editable)false else when(e.key){Key.DirectionLeft->{ctl.updatePoint(point.withBandEdge(f/1.03,low));true};Key.DirectionRight->{ctl.updatePoint(point.withBandEdge(f*1.03,low));true};else->false}}.focusable())}
    SoundTemplateButton(if(point.widthLocked)"Breite gesperrt"else"Breite sperren",AudioNativeRect(1275f,296f,320f,60f),scale,p,{ctl.updatePoint(point.copy(widthLocked=!point.widthLocked))},enabled=ctl.editable,selected=point.widthLocked,testTag="sound-width-lock")
    val lockX=stageX(point.frequencyHz);val lockY=stageY(point.gainDb)-49
    if(lockY-26>=chart.y)Canvas(Modifier.soundRegion(lockX-25,lockY-26,50f,47f,scale).testTag("sound-width-curve-lock").semantics{role=Role.Button;contentDescription=No5Text.text(context,if(point.widthLocked)"Breite lösen"else"Breite sperren");onClick{if(ctl.editable)ctl.updatePoint(point.copy(widthLocked=!point.widthLocked));ctl.editable}}){
        drawIntoCanvas{c->val n=c.nativeCanvas;val u=size.width/50;n.save();n.scale(u,u);n.translate(25f,26f);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=p.accent.soundArgb();style=Paint.Style.STROKE;strokeWidth=2.2f};val shackle=AndroidPath().apply{moveTo(-7f,-2f);lineTo(-7f,-8f);cubicTo(-7f,-17f,7f,-17f,7f,-8f);if(point.widthLocked)lineTo(7f,-2f)};n.drawPath(shackle,paint);paint.style=Paint.Style.FILL;paint.color=p.background.soundArgb();n.drawRoundRect(RectF(-11f,-2f,11f,16f),3f,3f,paint);paint.color=p.accent.soundArgb();paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;n.drawRoundRect(RectF(-11f,-2f,11f,16f),3f,3f,paint);paint.style=Paint.Style.FILL;n.drawCircle(0f,6f,2f,paint);n.restore()}
    }

}
/** FFT snapshots never recompose the gesture owner or invalidate the static plot layers. */
@Composable private fun SoundEqSpectrum(scale:Float,night:Boolean,ctl:AudioSoundUiController,p:SoundEditorPalette,chart:AudioNativeRect){
    Canvas(Modifier.soundRegion(chart.x,chart.y,chart.width,chart.height,scale).clipToBounds()){
        val w=size.width;val h=size.height;val unit=w/chart.width
        val colors=if(night)listOf(Color(0xffef190b),Color(0xff9d0800),Color(0xff350000))else if(ctl.spectrumStyle=="gray")listOf(Color(0xff5b6970),Color(0xffb1bec2))else listOf(Color(0xffdc442d),Color(0xffe5be4a),Color(0xff75ab88))
        val brush=androidx.compose.ui.graphics.Brush.verticalGradient(colors)
        ctl.spectrumInk?.let{frame->val path=Path();val edge=Path();path.moveTo(0f,h)
            frame.frequenciesHz.forEachIndexed{i,f->val x=(ln(f/20)/ln(1000.0)*w).toFloat();val y=((-frame.binsDbfs[i]/90).coerceIn(0.0,1.0)*h).toFloat();path.lineTo(x,y);if(i==0)edge.moveTo(x,y)else edge.lineTo(x,y)};path.lineTo(w,h);path.close()
            if(ctl.spectrumStyle!="line")drawPath(path,brush,alpha=.32f);drawPath(edge,brush,alpha=.9f,style=Stroke(1.7f*unit))
        }
    }
}
@Composable private fun SoundEqPeakCaption(scale:Float,night:Boolean,ctl:AudioSoundUiController,clipText:String){
    val context=LocalContext.current
    val peaks=ctl.spectrumInk?.let{No5Text.text(context,"Spitzenpegel: %s dBFS · Über 0 dBFS: %s Samples",soundNumber(it.peaksDbfs.max()),it.overRangeSamples.toString())}
    SoundTemplateCanvas(scale,night){
        text(listOfNotNull(peaks,clipText).joinToString(" · "),1371f,884f,20f,secondary=true,translate=false,align=Paint.Align.RIGHT,maxWidth=1030f)
    }
}
@Composable private fun SoundRoomPage(s:Float,night:Boolean,ctl:AudioSoundUiController,p:SoundEditorPalette,font:FontFamily,onListener:()->Unit){
    val preset=ctl.preset?:return;val room=preset.room;val enabled=ctl.editable&&ctl.controlInk.capabilities.room
    val context=LocalContext.current;val fontScale=LocalDensity.current.fontScale
    SoundTemplateCanvas(s,night){
        if(!ctl.controlInk.capabilities.room)text("Dieses Routing hat keine freigegebenen Rear-Kanäle.",344f,958f,24f,secondary=true,maxWidth=1300f)
        line(58f,772f,1940f,772f);text("RAUMWIRKUNG",58f,822f,22f,secondary=true);text("${(room.amount*100).roundToInt()} %",899f,828f,48f,hero=true,translate=false,align=Paint.Align.RIGHT)
        text("zurückhaltend",60f,900f,22f,secondary=true);text("umhüllend",895f,900f,22f,secondary=true,align=Paint.Align.RIGHT);line(1000f,803f,1000f,935f)
        text("RAUMGRÖSSE",1103f,822f,22f,secondary=true);text("${(room.size*100).roundToInt()} %",1940f,828f,48f,hero=true,translate=false,align=Paint.Align.RIGHT)
        text("kompakt",1105f,900f,22f,secondary=true);text("weitläufig",1940f,900f,22f,secondary=true,align=Paint.Align.RIGHT)
    }
    val frame=SoundBoatFrame(1000f,565f,4.1f,clockwise=true)
    val layout=ctl.calibration?.let{soundBoatLayout(it,frame)}
    if(layout!=null)SoundRoomIllustration(s,night,frame,layout.first,layout.second,onListener,
        ctl.status?.takeIf{it!="Probehören · noch nicht gespeichert"})
    SoundTemplateSlider("Raumwirkung",room.amount,40f,836f,875f,64f,s,p,enabled){ctl.preset?.let{current->ctl.changePreset(current.copy(room=current.room.copy(amount=it)))}}
    SoundTemplateSlider("Raumgröße",room.size,1085f,836f,875f,64f,s,p,enabled){ctl.preset?.let{current->ctl.changePreset(current.copy(room=current.room.copy(size=it)))}}
}
@Composable private fun SoundTemplateSlider(label:String,value:Double,x:Float,y:Float,w:Float,h:Float,s:Float,p:SoundEditorPalette,enabled:Boolean,change:(Double)->Unit){
    val context=LocalContext.current;val current by rememberUpdatedState(value)
    Canvas(Modifier.soundRegion(x,y,w,h,s).testTag("sound-slider-$label").semantics{contentDescription=No5Text.text(context,label);progressBarRangeInfo=androidx.compose.ui.semantics.ProgressBarRangeInfo(value.toFloat(),0f..1f);setProgress{if(enabled){change(it.coerceIn(0f,1f).toDouble());true}else false}}
        .onKeyEvent{e->if(e.type!=KeyEventType.KeyDown||!enabled)false else when(e.key){Key.DirectionLeft->{change((current-.01).coerceAtLeast(0.0));true};Key.DirectionRight->{change((current+.01).coerceAtMost(1.0));true};else->false}}.focusable()
        .pointerInput(enabled){detectTapGestures{if(enabled)change(((it.x/size.width*w-20)/(w-40)).toDouble().coerceIn(0.0,1.0))}}
        .pointerInput(enabled){detectDragGestures{c,_->if(enabled){change(((c.position.x/size.width*w-20)/(w-40)).toDouble().coerceIn(0.0,1.0));c.consume()}}}){
        val u=size.width/w;val center=size.height/2;val start=20*u;val end=size.width-20*u;val thumb=start+(end-start)*value.toFloat();drawLine(p.outline,Offset(start,center),Offset(end,center),6*u,androidx.compose.ui.graphics.StrokeCap.Round);drawLine(if(enabled)p.accent else p.secondary,Offset(start,center),Offset(thumb,center),6*u,androidx.compose.ui.graphics.StrokeCap.Round);drawCircle(p.background,17*u,Offset(thumb,center));drawCircle(if(enabled)p.accent else p.secondary,17*u,Offset(thumb,center),style=Stroke(3*u));drawCircle(if(enabled)p.accent else p.secondary,6*u,Offset(thumb,center))
    }
}
private fun soundNumber(value:Double)=String.format(Locale.ROOT,if(abs(value)>=100)"%.0f"else"%.1f",value)
private fun soundReserveNumber(value:Double)=String.format(Locale.ROOT,if(value%1.0==0.0)"%.0f"else"%.1f",value)
private fun soundSigned(value:Double,precision:Int=1)=(if(value>0)"+"else"")+String.format(Locale.ROOT,"%.${precision}f",value).replace('-','−')

@Composable private fun SoundAlignmentPage(s:Float,night:Boolean,ctl:AudioSoundUiController,p:SoundEditorPalette,font:FontFamily,onSave:()->Unit){
    val context=LocalContext.current;val cal=ctl.calibration?:return
    val speaker=cal.speakers.single{it.id==ctl.selectedSpeaker};val gain=ctl.section=="gain"
    val effective=ctl.observationInk.effectiveCalibration?.speakers?.firstOrNull{it.id==speaker.id}
    fun trim(direction:Int){
        val c=ctl.calibration?:return;val selected=c.speakers.single{it.id==ctl.selectedSpeaker}
        val v=if(gain)selected.manualGainDb else selected.manualDelayMs
        val target=(v+direction*if(gain).1 else .05).coerceIn(if(gain)-24.0 else -50.0,if(gain)24.0 else 50.0)
        ctl.changeCalibration(c.copy(speakers=c.speakers.map{if(it.id!=selected.id)it else if(gain)it.copy(manualGainDb=target)else it.copy(manualDelayMs=target)}))
    }
    SoundTemplateCanvas(s,night){
        text("BUG",737f,290f,21f,secondary=true,maxWidth=100f)
        text("HECK",710f,857f,21f,secondary=true,maxWidth=150f)
        line(1120f,290f,1640f,290f,width=1.5f)
        text(soundSpeakerLabel(speaker.id,context).uppercase(Locale.ROOT),1120f,340f,29f,translate=false,maxWidth=520f)
        text(effective?.let{if(gain)"${soundSigned(it.gainDb)} dB"else"${soundNumber(it.delayMs)} ms"}?:"—",
            1120f,417f,50f,hero=true,translate=false,maxWidth=520f)
        text("Manuell feinabstimmen",1120f,591f,26f,secondary=true,maxWidth=520f)
        text(if(!cal.completeGeometry)"Ohne Maße keine berechneten Werte · separat vom Klangpreset"
            else if(cal.approximate2d)"2D-Näherung · Sub weiterhin manuell prüfen"else"Geometrie: eingegebene 3D-Positionen",
            360f,936f,23f,secondary=true,maxWidth=1280f)
    }
    SoundBoat(s,night,ctl,p,room=false,frame=SoundBoatFrame(755f,280f,2.2f))
    SoundTemplateButton("Verzögerung",AudioNativeRect(1120f,450f,250f,96f),s,p,{ctl.changeSection("delay")},selected=!gain,testTag="sound-mode-delay",textSize=27f)
    SoundTemplateButton("Pegel",AudioNativeRect(1390f,450f,250f,96f),s,p,{ctl.changeSection("gain")},selected=gain,testTag="sound-mode-gain",textSize=27f)
    SoundTemplateButton("−",AudioNativeRect(1120f,616f,240f,96f),s,p,{trim(-1)},enabled=ctl.editable&&ctl.controlInk.capabilities.speakerAlignment,testTag="sound-trim-down",textSize=27f)
    SoundTemplateButton("+",AudioNativeRect(1380f,616f,260f,96f),s,p,{trim(1)},enabled=ctl.editable&&ctl.controlInk.capabilities.speakerAlignment,testTag="sound-trim-up",textSize=27f)
    SoundTemplateButton("Zwei Abstände eingeben",AudioNativeRect(1120f,724f,520f,96f),s,p,{ctl.changeSection("measure")},testTag="sound-open-measure",textSize=27f)
    SoundTemplateButton("Details",AudioNativeRect(1120f,824f,240f,96f),s,p,{ctl.changeSection("positions")},testTag="sound-open-exact-position",textSize=27f)
    SoundTemplateButton("Speichern",AudioNativeRect(1380f,824f,260f,96f),s,p,onSave,
        enabled=ctl.editable&&ctl.calibrationDirty&&!ctl.hasInvalidGeometry&&!ctl.measurementDirty,strong=true,testTag="sound-save",textSize=27f)
    SoundTemplateButton("Hörplatz ziehen",AudioNativeRect(380f,582f,340f,96f),s,p,{ctl.changeSection("place")},
        enabled=ctl.editable,testTag="sound-open-positions",plain=true,textSize=25f)
}
/** The two dimensions remain a local measurement draft until explicit Apply. */
@Composable private fun SoundMeasurements(s:Float,night:Boolean,ctl:AudioSoundUiController,p:SoundEditorPalette,font:FontFamily){
    val context=LocalContext.current;val cal=ctl.calibration?:return;val draft=ctl.measurementDraft?:return
    val replacesExact=SoundMeasuredGeometry.bounds(cal)!=null&&!SoundMeasuredGeometry.rectangular(cal)
    val extra=replacesExact||ctl.measurementError!=null
    ImeFormViewport(s,500f,220f,1000f,if(extra)558f else 454f,viewportTag="sound-measure-viewport") {
        SoundFormFrame(s,p,"Zwei Abstände messen","sound-measure-panel",body={
            SoundFormField("Links ↔ rechts (m)",draft.width,{ctl.editMeasurement(true,it)},"sound-measure-width",s,p,ctl.editable)
            SoundFormField("Vorne ↔ hinten (m)",draft.length,{ctl.editMeasurement(false,it)},"sound-measure-length",s,p,ctl.editable)
            if(extra) Text(No5Text.text(context,ctl.measurementError?:"Übernehmen ersetzt die vier genauen Hauptpositionen."),
                fontFamily=font,fontSize=(24*s).sp,lineHeight=(30*s).sp,color=p.secondary,
                modifier=Modifier.padding(horizontal=(28*s).dp,vertical=(12*s).dp).testTag("sound-measure-notice"))
        },actions={origin->
            SoundTemplateButton("Zurück",AudioNativeRect(28f,0f,462f,96f),s,p,ctl::cancelMeasurements,testTag="sound-measure-cancel",textSize=27f,evidenceOrigin=origin)
            SoundTemplateButton("Maße übernehmen",AudioNativeRect(510f,0f,462f,96f),s,p,{ctl.applyMeasurements()},
                enabled=ctl.editable&&draft.dimensions()!=null,testTag="sound-measure-apply",textSize=27f,evidenceOrigin=origin)
        },bodyTag="sound-measure-body",actionsTag="sound-measure-actions",stageOrigin=Offset(500f,220f))
    }
}

/** Fixed title/actions with only the data rows scrolling, including under the signed IME. */
@Composable internal fun SoundFormFrame(s:Float,p:SoundEditorPalette,title:String,tag:String,
    body:@Composable ColumnScope.()->Unit,actions:@Composable (Offset)->Unit,
    bodyTag:String,actionsTag:String,stageOrigin:Offset) {
    val context=LocalContext.current;val fontScale=LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize().testTag(tag)) {
        val height=maxHeight.value/s
        Canvas(Modifier.fillMaxSize()) {
            val n=drawContext.canvas.nativeCanvas;val u=size.width/1000f;n.save();n.scale(u,u)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=p.background.soundArgb()}
            n.save();n.translate(-stageOrigin.x,-stageOrigin.y)
            n.drawRoundRect(RectF(stageOrigin.x,stageOrigin.y,stageOrigin.x+1000f,stageOrigin.y+height),4f,4f,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f;paint.color=p.outline.soundArgb()
            n.drawRoundRect(RectF(stageOrigin.x,stageOrigin.y,stageOrigin.x+1000f,stageOrigin.y+height),4f,4f,paint);n.restore()
            paint.style=Paint.Style.FILL;paint.typeface=context.resources.getFont(R.font.barlow_condensed_extrabold)
            paint.textSize=35f*fontScale;paint.color=p.ink.soundArgb()
            val label=No5Text.text(context,title);n.drawText(label,28f,62f,paint)
            soundTextEvidence(title,label,label,paint,stageOrigin.x+28,stageOrigin.y+62,944f)
            paint.color=p.outline.soundArgb();paint.strokeWidth=1.5f;n.drawLine(28f,90f,972f,90f,paint);n.restore()
        }
        Column(Modifier.fillMaxSize()) {
            // Keep the first 1.5px field border inside the scroll viewport, at its
            // unchanged design position. Title and action geometry stay fixed.
            Spacer(Modifier.height((105*s).dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top=(1*s).dp).testTag(bodyTag),content=body)
            Box(Modifier.fillMaxWidth().height((116*s).dp).testTag(actionsTag)) {
                actions(stageOrigin+Offset(0f,height-116f))
            }
        }
    }
}
@Composable internal fun SoundFormField(label:String,value:String,onValue:(String)->Unit,tag:String,s:Float,p:SoundEditorPalette,enabled:Boolean){
    com.no5.ui.No5FormFieldRow(com.no5.ui.No5FormField(label,value,tag,enabled,
        KeyboardOptions(keyboardType=KeyboardType.Decimal),onValue),p.background.red<.1f,s,
        designWidth=1000f,labelX=28f,fieldX=610f)
}

@Composable private fun SoundPositions(s:Float,night:Boolean,ctl:AudioSoundUiController,p:SoundEditorPalette,font:FontFamily){
    val context=LocalContext.current;val cal=ctl.calibration?:return;val bounds=SoundMeasuredGeometry.bounds(cal)
    val selected=ctl.placingPosition
    SoundTemplateCanvas(s,night){
        text("STINGHER 800 GT · ORIENTIERUNG",58f,313f,22f,secondary=true)
        text("BUG",680f,346f,22f,secondary=true,align=Paint.Align.CENTER);text("HECK",680f,965f,22f,secondary=true,align=Paint.Align.CENTER)
        line(1260f,360f,1260f,949f);text("HÖRPLATZ UND SUB",1320f,383f,30f,hero=true,maxWidth=620f)
        text(if(bounds==null)"Zuerst die Lautsprecherabstände eingeben."else if(selected==SoundPositionTarget.LISTENER)"Auf dem Boot den Hörplatz setzen."else if(selected==SoundPositionTarget.SUB)"Auf dem Boot den Sub setzen."else "Ziel wählen, dann auf das Boot tippen.",1320f,435f,26f,secondary=true,maxWidth=620f)
        text("Positionen danach direkt verschieben.",1320f,477f,26f,secondary=true,maxWidth=620f)
        text("Meterreferenz: ab linker / vorderer Lautsprecherreihe",1320f,685f,22f,secondary=true,maxWidth=620f)
        fun point(label:String,pos:SoundPosition?,y:Float){text(label,1320f,y,26f,maxWidth=270f);text(if(pos==null||bounds==null)"—"else"${soundNumber(pos.x-bounds.minX)} / ${soundNumber(pos.y-bounds.minY)} m",1940f,y,26f,translate=false,align=Paint.Align.RIGHT,maxWidth=340f)}
        point("Hörplatz",cal.listenerM,730f);point("Sub · Cockpitsitz",cal.speakers.single{it.id==SoundSpeakerId.SUB}.positionM,772f)
        text(if(!cal.completeGeometry)"Fehlende Positionen bleiben unbekannt."else if(cal.approximate2d)"Geometrie: zweidimensionale Näherung"else"Geometrie: eingegebene 3D-Positionen",1320f,816f,23f,secondary=true,maxWidth=620f)
    }
    SoundBoat(s,night,ctl,p,room=false,frame=SoundBoatFrame(755f,280f,2.2f))
    SoundTemplateButton("Hörplatz setzen",AudioNativeRect(1320f,525f,620f,64f),s,p,{ctl.beginPositioning(SoundPositionTarget.LISTENER)},enabled=ctl.editable&&bounds!=null,selected=ctl.selectedPosition==SoundPositionTarget.LISTENER,testTag="sound-place-listener")
    SoundTemplateButton("Sub setzen",AudioNativeRect(1320f,605f,620f,64f),s,p,{ctl.beginPositioning(SoundPositionTarget.SUB)},enabled=ctl.editable&&bounds!=null,selected=ctl.selectedPosition==SoundPositionTarget.SUB,testTag="sound-place-sub")
    Row(Modifier.offset((1320*s).dp,(844*s).dp).width((620*s).dp).heightIn(min=(60*s).dp),verticalAlignment=Alignment.CenterVertically){
        Checkbox(cal.geometryEnabled,{ctl.useGeometry(it)},enabled=ctl.editable&&cal.completeGeometry,colors=CheckboxDefaults.colors(checkedColor=p.accent,uncheckedColor=p.outline,checkmarkColor=p.background))
        Text(No5Text.text(context,"Geometrischen Abgleich verwenden"),fontFamily=font,fontSize=(26*s).sp,color=p.ink)
    }
    SoundTemplateButton("Zurück zum Abgleich",AudioNativeRect(1320f,922f,400f,58f),s,p,{ctl.changeSection("delay")},testTag="sound-position-back")
}
@Composable private fun SoundExactPosition(s:Float,night:Boolean,ctl:AudioSoundUiController,p:SoundEditorPalette,font:FontFamily){
    val context=LocalContext.current;val fontScale=LocalDensity.current.fontScale;val target=ctl.exactTarget
    val input=ctl.coordinateInput(target)
    var targetsOpen by remember{mutableStateOf(false)}
    fun name(id:String)=if(id=="listener")No5Text.text(context,"Hörplatz")else soundSpeakerLabel(SoundSpeakerId.valueOf(id),context)
    ImeFormViewport(s,500f,220f,1000f,662f,viewportTag="sound-exact-viewport") {
        SoundFormFrame(s,p,"Einzelposition · Details","sound-exact-panel",body={
            Box(Modifier.fillMaxWidth().height((104*s).dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val n=drawContext.canvas.nativeCanvas;val u=size.width/1000f;n.save();n.scale(u,u)
                    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color=p.ink.soundArgb();typeface=context.resources.getFont(R.font.barlow_condensed_semibold)
                        textSize=28f*fontScale
                    }
                    n.drawText(No5Text.text(context,"Lautsprecher"),28f,53f,paint);n.restore()
                }
                SoundTemplateButton(name(target),AudioNativeRect(610f,0f,360f,96f),s,p,{targetsOpen=true},
                    textSize=28f,testTag="sound-exact-target",evidenceOrigin=Offset(500f,326f))
            }
            listOf(Triple("x","Querabstand x (m)",input.x),Triple("y","Längsabstand y (m)",input.y),Triple("z","Höhe z (m) · optional",input.z)).forEach{(axis,label,value)->
                SoundFormField(label,value,{ctl.editCoordinate(target,axis,it)},"sound-exact-$target-$axis",s,p,ctl.editable)
            }
        },actions={origin->
            SoundTemplateButton("Zurück",AudioNativeRect(28f,0f,462f,96f),s,p,{ctl.changeSection("place")},
                textSize=27f,testTag="sound-exact-back",evidenceOrigin=origin)
            SoundTemplateButton("action.apply",AudioNativeRect(510f,0f,462f,96f),s,p,{ctl.changeSection("place")},
                textSize=27f,enabled=ctl.editable&&!ctl.hasInvalidGeometry,testTag="sound-exact-apply",evidenceOrigin=origin)
        },bodyTag="sound-exact-body",actionsTag="sound-exact-actions",stageOrigin=Offset(500f,220f))
    }
    if(targetsOpen){
        BackHandler{targetsOpen=false}
        Box(Modifier.fillMaxSize().pointerInput(Unit){detectTapGestures{targetsOpen=false}})
        SoundTemplateCanvas(s,night){
            rect(500f,240f,1000f,782f);rect(500f,240f,1000f,782f,true)
            text("Lautsprecher",528f,298f,35f,hero=true,maxWidth=944f);line(528f,326f,1472f,326f)
            repeat(6){i->line(528f,346f+i*96f+95f,1472f,346f+i*96f+95f)}
        }
        (SoundSpeakerId.entries.map{it.name}+"listener").forEachIndexed{i,id->
            SoundTemplateButton(name(id),AudioNativeRect(528f,346f+i*96f,944f,96f),s,p,{ctl.chooseExactTarget(id);targetsOpen=false},
                plain=true,selected=target==id,textSize=28f,testTag="sound-exact-choice-$id")
        }
        SoundTemplateButton("Schließen",AudioNativeRect(1160f,926f,312f,96f),s,p,{targetsOpen=false},textSize=27f,testTag="sound-exact-target-close")
    }
}
private fun soundSpeakerLabel(id:SoundSpeakerId,c:android.content.Context)=No5Text.text(c,when(id){SoundSpeakerId.FRONT_LEFT->"Vorne links";SoundSpeakerId.FRONT_RIGHT->"Vorne rechts";SoundSpeakerId.REAR_LEFT->"Hinten links";SoundSpeakerId.REAR_RIGHT->"Hinten rechts";SoundSpeakerId.SUB->"Sub · Cockpitsitz"})
/** Literal native port of boatOutline() in the approved revision-four template. */
internal object SoundBoatPaths {
    val tubes="M14 241Q6 240 6 230V83C6 53 20 29 35 15L41 13H59L65 15C80 29 94 53 94 83V230Q94 240 86 241"
    val hull="M16 230V84C16 59 26 40 40 29H60C74 40 84 59 84 84V230Z"
    val bow="M35 10H65L68 24H32Z"
    val bowSegments="M38 13V21M44 13V21M50 13V21M56 13V21M62 13V21"
    val forwardLocker="M22 101V80L36 45H64L78 80V101Z"
    val forwardDetails="M36 45L50 70L64 45M22 80H78M50 70V101"
    val forwardRear="M21 101H79V109H21Z"
    val console="M33 131L37 122H63L67 131V171H33Z"
    val windscreen="M35 135H65V153H35Z"
    val consoleSides="M30 139V166M70 139V166M30 139L33 133M70 139L67 133"
    val instrument="M39 125H61V133H39Z"
    val wheelDetails="M38 162H46M42 158V166M59 158V166"
    val seatDetails="M34 187H66M50 187V201"
    val aftDetails="M22 222H78M40 222V232M60 222V232M19 235H81"
    val stern="M17 228H9V244H27V234M83 228H91V244H73V234M29 237H71V245H29Z"
    val tubeDetails="M4 88H13M4 153H13M87 88H96M87 153H96"
    val all=listOf(tubes,hull,bow,bowSegments,forwardLocker,forwardDetails,forwardRear,console,windscreen,consoleSides,instrument,wheelDetails,seatDetails,aftDetails,stern,tubeDetails)
    val parsed by lazy {all.associateWith{androidx.compose.ui.graphics.vector.PathParser().parsePathString(it).toPath().asAndroidPath()}}
}
internal data class SoundBoatFrame(val cx:Float,val top:Float,val unit:Float,val clockwise:Boolean=false){
    fun point(x:Double,y:Double)=if(clockwise)Offset(cx+(125f-y.toFloat())*unit,top+(x.toFloat()-50f)*unit)
        else Offset((cx+(x-50)*unit).toFloat(),(top+y*unit).toFloat())
}
/** The schematic is orientation only. Real positions are mapped together; absent measurements stay absent. */
internal data class SoundBoatMapping(val minX:Double,val maxX:Double,val minY:Double,val maxY:Double){
    val factor=min(72/(maxX-minX),126/(maxY-minY))
    val left=50-(maxX-minX)*factor/2;val top=157-(maxY-minY)*factor/2
    fun local(p:SoundPosition)=Offset((left+(p.x-minX)*factor).toFloat(),(top+(p.y-minY)*factor).toFloat())
    fun metres(local:Offset,z:Double?=null)=SoundPosition((minX+(local.x-left)/factor).coerceIn(minX,maxX),(minY+(local.y-top)/factor).coerceIn(minY,maxY),z)
}
private fun soundBoatMapping(c:SoundCalibration):SoundBoatMapping? {
    val pts=c.speakers.filter{it.id!=SoundSpeakerId.SUB}.mapNotNull{it.positionM};if(pts.size!=4)return null
    val a=pts.minOf{it.x};val b=pts.maxOf{it.x};val d=pts.minOf{it.y};val e=pts.maxOf{it.y}
    return if(b-a<.01||e-d<.01)null else SoundBoatMapping(a,b,d,e)
}
internal fun soundBoatLayout(cal:SoundCalibration,frame:SoundBoatFrame):Pair<Map<SoundSpeakerId,Offset>,Offset?> {
    val mapping=soundBoatMapping(cal)
    val fallback=mapOf(SoundSpeakerId.FRONT_LEFT to Offset(29f,105f),SoundSpeakerId.FRONT_RIGHT to Offset(71f,105f),SoundSpeakerId.REAR_LEFT to Offset(27f,224f),SoundSpeakerId.REAR_RIGHT to Offset(73f,224f),SoundSpeakerId.SUB to Offset(58f,200f))
    val locations=cal.speakers.associate{speaker->val local=speaker.positionM?.let{mapping?.local(it)}?:fallback.getValue(speaker.id);speaker.id to frame.point(local.x.toDouble(),local.y.toDouble())}
    val listener=cal.listenerM?.let{mapping?.local(it)}?.let{frame.point(it.x.toDouble(),it.y.toDouble())}
    return locations to listener
}
/** Pure native presentation; the calibration owner supplies its measured display positions. */
@Composable internal fun SoundRoomIllustration(s:Float,night:Boolean,frame:SoundBoatFrame,locations:Map<SoundSpeakerId,Offset>,listener:Offset?,onListener:()->Unit,status:String?=null){
    val context=LocalContext.current;val font=LocalDensity.current.fontScale;val p=remember(night){SoundEditorPalette(night)}
    SoundTemplateCanvas(s,night){
        PresetLibraryInk(context,canvas,night,font).apply{
            rect(0f,280f,2000f,490f)
            text("STINGHER 800 GT",58f,325f,32f,420f,2,raw=true)
            text("Virtuelle Reflexionen · schematisch",1100f,320f,24f,840f,color=muted)
            text("Direktsignal",58f,520f,24f,220f,color=muted);text("Reflexionen",58f,600f,24f,220f,color=muted)
            text("Heck",340f,575f,24f,142f,color=muted);text("Bug",1530f,575f,24f,130f,color=muted)
            rect(344f,1000f,1312f,55f);text(status?:"Hörplatz und Sub bleiben separat abgeglichen.",344f,1032f,24f,color=muted)
        }
        line(58f,544f,225f,544f,width=1.5f);line(58f,624f,225f,624f,accent=true,width=1.5f,dashed=true)
        drawSoundRoomBoat(canvas,p,frame,locations,listener,context,night,font)
    }
    SoundTemplateButton("preset.listener",AudioNativeRect(1450f,345f,490f,96f),s,p,onListener,textSize=27f,manualCenter=true,neutralFill=false,testTag="sound-room-listener")
    locations.forEach{(id,pos)->Box(Modifier.soundRegion(pos.x-48,pos.y-48,96f,96f,s).testTag("sound-room-speaker-${id.name}")
        .semantics{contentDescription=soundSpeakerLabel(id,context)})}
}
/** The approved room ink accepts display positions; calibration alone maps metres. */
internal fun drawSoundRoomBoat(n:AndroidCanvas,p:SoundEditorPalette,frame:SoundBoatFrame,
    locations:Map<SoundSpeakerId,Offset>,listener:Offset?,context:android.content.Context,night:Boolean,fontScale:Float){
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeJoin=Paint.Join.MITER}
            n.save();n.translate(frame.cx,frame.top);n.rotate(90f);n.scale(frame.unit,frame.unit);n.translate(-50f,-125f)
            SoundBoatPaths.all.forEachIndexed{i,data->paint.color=p.outline.soundArgb();paint.alpha=255;paint.strokeWidth=if(i==0)8f else .6f;paint.style=Paint.Style.STROKE;n.drawPath(SoundBoatPaths.parsed.getValue(data),paint)}
            n.restore()
            locations.forEach{(id,pos)->
                if(listener!=null){paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f;paint.color=p.outline.soundArgb();n.drawLine(pos.x,pos.y,listener.x,listener.y,paint)}
                drawSoundSpeakerGlyph(n,paint,pos.x,pos.y,.68f,false,p);SoundTemplateDiagnostics.observeSpeaker?.invoke(id,true,pos)
                if(id==SoundSpeakerId.SUB)PresetLibraryInk(context,n,night,fontScale).apply{text("Sub",pos.x+26,pos.y+9,22f,100f,color=muted,raw=true)}
            }
            listener?.let{at->
                paint.color=p.accent.soundArgb();paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f
                n.drawCircle(at.x,at.y,26f,paint);n.drawCircle(at.x,at.y-7,7f,paint)
                val person=AndroidPath().apply{moveTo(at.x-11,at.y+15);quadTo(at.x-11,at.y+4,at.x,at.y+4);quadTo(at.x+11,at.y+4,at.x+11,at.y+15)}
                n.drawPath(person,paint)
            }

}
@Composable private fun SoundBoat(s:Float,night:Boolean,ctl:AudioSoundUiController,p:SoundEditorPalette,room:Boolean,frame:SoundBoatFrame,interactive:Boolean=true){
    val context=LocalContext.current;val cal=ctl.calibration?:return;val mapping=soundBoatMapping(cal);val current by rememberUpdatedState(cal)
    val (locations,listener)=soundBoatLayout(cal,frame)
    SoundTemplateCanvas(s,night){val n=canvas;val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=p.outline.soundArgb();strokeWidth=.7f;style=Paint.Style.STROKE;strokeJoin=Paint.Join.ROUND}
        if(room){drawSoundRoomBoat(n,p,frame,locations,listener,context,night,fontScale);return@SoundTemplateCanvas}
        n.save();n.translate(frame.cx-50*frame.unit,frame.top);n.scale(frame.unit,frame.unit)
        fun path(data:String,fill:Boolean=false,alpha:Int=255,width:Float=.7f){paint.alpha=alpha;paint.strokeWidth=width;if(fill){paint.style=Paint.Style.FILL;paint.color=(if(night)Color(0xff0c0000)else Color.White).soundArgb();n.drawPath(SoundBoatPaths.parsed.getValue(data),paint)};paint.color=p.outline.soundArgb();paint.alpha=alpha;paint.style=Paint.Style.STROKE;n.drawPath(SoundBoatPaths.parsed.getValue(data),paint)}
        fun rect(x:Float,y:Float,w:Float,h:Float,r:Float,fill:Boolean=false){if(fill){paint.style=Paint.Style.FILL;paint.color=(if(night)Color(0xff0c0000)else Color.White).soundArgb();n.drawRoundRect(RectF(x,y,x+w,y+h),r,r,paint)};paint.style=Paint.Style.STROKE;paint.color=p.outline.soundArgb();paint.strokeWidth=.7f;n.drawRoundRect(RectF(x,y,x+w,y+h),r,r,paint)}
        path(SoundBoatPaths.tubes,alpha=128,width=9f);path(SoundBoatPaths.hull);path(SoundBoatPaths.bow,true);path(SoundBoatPaths.bowSegments,alpha=166)
        path(SoundBoatPaths.forwardLocker,true);path(SoundBoatPaths.forwardDetails,alpha=179);path(SoundBoatPaths.forwardRear,true);n.drawCircle(29f,105f,2.8f,paint);n.drawCircle(71f,105f,2.8f,paint)
        path(SoundBoatPaths.console,true,217);path(SoundBoatPaths.windscreen,alpha=217);path(SoundBoatPaths.consoleSides,alpha=217);path(SoundBoatPaths.instrument,alpha=217);rect(36f,154f,28f,14f,3f);n.drawCircle(42f,162f,4.3f,paint);path(SoundBoatPaths.wheelDetails,alpha=217)
        rect(32f,183f,36f,19f,3f,true);path(SoundBoatPaths.seatDetails,alpha=217);rect(20f,216f,60f,17f,4f,true);path(SoundBoatPaths.aftDetails);path(SoundBoatPaths.stern);path(SoundBoatPaths.tubeDetails,alpha=140)
        paint.style=Paint.Style.FILL;paint.alpha=255;n.restore()
        // Same shared mapping in room and calibration. No invented listener position or metre labels.
        val main=locations.filterKeys{it!=SoundSpeakerId.SUB}.values;val l=main.minOf{it.x};val r=main.maxOf{it.x};val t=main.minOf{it.y};val b=main.maxOf{it.y}
        paint.style=Paint.Style.FILL;paint.color=p.accent.copy(alpha=.025f).soundArgb();n.drawRect(l,t,r,b,paint);paint.style=Paint.Style.STROKE;paint.strokeWidth=1.3f;paint.color=p.secondary.copy(alpha=.4f).soundArgb();paint.pathEffect=android.graphics.DashPathEffect(floatArrayOf(5f,6f),0f);n.drawRect(l,t,r,b,paint);paint.pathEffect=null
        locations.forEach{(id,pos)->
            if(listener!=null){paint.color=(if(!room&&ctl.selectedSpeaker==id)p.accent.copy(alpha=.7f)else p.secondary.copy(alpha=.35f)).soundArgb();paint.strokeWidth=1.5f;n.drawLine(pos.x,pos.y,listener.x,listener.y,paint)
                if(room&&id in setOf(SoundSpeakerId.REAR_LEFT,SoundSpeakerId.REAR_RIGHT)&&(ctl.preset?.room?.amount?:0.0)>0){paint.color=p.accent.copy(alpha=(.15+.25*(ctl.preset?.room?.amount?:0.0)).toFloat()).soundArgb();paint.pathEffect=android.graphics.DashPathEffect(floatArrayOf(5f,8f),0f);val curve=AndroidPath().apply{moveTo(pos.x,pos.y);quadTo(pos.x+if(id==SoundSpeakerId.REAR_LEFT)-135 else 135,(pos.y+listener.y)/2,listener.x,listener.y)};n.drawPath(curve,paint);paint.pathEffect=null}}
            drawSoundSpeakerGlyph(n,paint,pos.x,pos.y,.68f,!room&&ctl.selectedSpeaker==id,p)
            SoundTemplateDiagnostics.observeSpeaker?.invoke(id,room,pos)
            if(room&&id==SoundSpeakerId.SUB)text("Sub",pos.x+22f,pos.y+8f,22f,secondary=true,translate=false,maxWidth=90f)
            if(!room&&id!=SoundSpeakerId.SUB){val left=id==SoundSpeakerId.FRONT_LEFT||id==SoundSpeakerId.REAR_LEFT;val tx=if(left)380f else 905f
                text(soundSpeakerLabel(id,context),tx,pos.y+3,25f,secondary=ctl.selectedSpeaker!=id,translate=false,align=Paint.Align.LEFT,maxWidth=210f,accent=ctl.selectedSpeaker==id)
                val effective=ctl.observationInk.effectiveCalibration?.speakers?.firstOrNull{it.id==id};val value=effective?.let{if(ctl.section=="gain")"${soundSigned(it.gainDb)} dB"else"${soundNumber(it.delayMs)} ms"}?:"—"
                text(value,tx,pos.y+35,27f,translate=false,align=Paint.Align.LEFT,accent=ctl.selectedSpeaker==id,maxWidth=210f)
            }
        }
        if(listener!=null){if(room){paint.style=Paint.Style.STROKE;paint.color=p.secondary.copy(alpha=.16f).soundArgb();repeat(3){i->val radius=25+(i+1)*(20+(ctl.preset?.room?.size?:.5)*18);n.drawOval(RectF(listener.x-(radius*.9).toFloat(),listener.y-(radius*1.1).toFloat(),listener.x+(radius*.9).toFloat(),listener.y+(radius*1.1).toFloat()),paint)}}
            n.save();n.translate(listener.x,listener.y);val q=if(room).68f else 1f;n.scale(q,q);paint.style=Paint.Style.FILL;paint.color=p.accent.copy(alpha=.08f).soundArgb();n.drawCircle(0f,0f,37f,paint);paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f;paint.color=p.accent.soundArgb();n.drawCircle(0f,0f,37f,paint);paint.strokeWidth=2.5f;n.drawCircle(0f,-7f,10f,paint);val person=AndroidPath().apply{moveTo(-16f,20f);quadTo(-16f,6f,0f,6f);quadTo(16f,6f,16f,20f);moveTo(-45f,0f);lineTo(-39f,0f);moveTo(39f,0f);lineTo(45f,0f);moveTo(0f,-45f);lineTo(0f,-39f);moveTo(0f,39f);lineTo(0f,45f)};n.drawPath(person,paint);n.restore()

        }
        if(!room){
            val sub=locations.getValue(SoundSpeakerId.SUB)
            text("Sub · Cockpitsitz",905f,sub.y+8f,25f,maxWidth=330f)
        }
    }
    if(room)locations.forEach{(id,pos)->
        Box(Modifier.soundRegion(pos.x-48,pos.y-48,96f,96f,s).testTag("sound-room-speaker-${id.name}")
            .semantics{contentDescription=soundSpeakerLabel(id,context)})
    }
    if(!room&&interactive){
        val placing=ctl.placingPosition
        if(placing!=null&&mapping!=null){
            val l=frame.point(mapping.left, mapping.top).x;val top=frame.point(mapping.left,mapping.top).y
            val w=(mapping.maxX-mapping.minX)*mapping.factor*frame.unit;val h=(mapping.maxY-mapping.minY)*mapping.factor*frame.unit
            Box(Modifier.soundRegion(l,top,w.toFloat(),h.toFloat(),s).testTag("sound-position-target").semantics{contentDescription=No5Text.text(context,if(placing==SoundPositionTarget.LISTENER)"Auf dem Boot den Hörplatz setzen."else"Auf dem Boot den Sub setzen.")}.pointerInput(placing,ctl.editable,mapping){detectTapGestures{point->
                if(ctl.editable){val density=context.resources.displayMetrics.density;val stage=Offset(l+point.x/(s*density),top+point.y/(s*density));val local=Offset(50f+(stage.x-frame.cx)/frame.unit,(stage.y-frame.top)/frame.unit);ctl.placePosition(mapping.metres(local))}
            }})
        }
        if(ctl.placingPosition==null)locations.forEach{(id,pos)->Box(Modifier.soundRegion(pos.x-48,pos.y-48,96f,96f,s).testTag("sound-speaker-${id.name}").semantics{contentDescription=soundSpeakerLabel(id,context);stateDescription=if(ctl.selectedSpeaker==id)No5Text.text(context,"Ausgewählt")else""}.no5Clickable(feedbackColor=p.accent){ctl.chooseSpeaker(id)})}
        if(ctl.placingPosition==null && ctl.section in setOf("place","delay","gain")){
            @Composable fun movable(target:SoundPositionTarget,pos:Offset,tag:String,label:String){
                Box(Modifier.soundRegion(pos.x-48,pos.y-48,96f,96f,s).testTag(tag).semantics{contentDescription=No5Text.text(context,label)}.pointerInput(ctl,ctl.editable,target){
                    // A deliberately selected marker is a precision control. Claim it on down,
                    // not after scrolling touch slop, so small positioning moves are not lost.
                    awaitEachGesture {
                        val down=awaitFirstDown(requireUnconsumed=false)
                        val u=s*context.resources.displayMetrics.density
                        val hit=pos+Offset(down.position.x/u-48f,down.position.y/u-48f)
                        val closest=locations.minByOrNull{(_,location)->(location-hit).getDistance()}
                        if(target==SoundPositionTarget.LISTENER && closest!=null &&
                            (closest.value-hit).getDistance()<(pos-hit).getDistance() &&
                            (closest.value-hit).getDistance()<24f){
                            // Adjacent Sub/listener symbols may be physically closer than one
                            // touch target. A tap selects the nearer cabinet; it must not move
                            // either position through the listener's transparent drag area.
                            down.consume()
                            if(waitForUpOrCancellation()!=null)ctl.chooseSpeaker(closest.key)
                            return@awaitEachGesture
                        }
                        val c=ctl.calibration
                        val m=c?.let(::soundBoatMapping)
                        var at=if(target==SoundPositionTarget.LISTENER)c?.listenerM else c?.speakers?.single{it.id==SoundSpeakerId.SUB}?.positionM
                        if(!ctl.editable||m==null||at==null)return@awaitEachGesture
                        val source=ctl.observed.sourceGeneration
                        val config=ctl.observed.configGeneration
                        down.consume()
                        do {
                            val event=awaitPointerEvent()
                            val change=event.changes.firstOrNull{it.id==down.id}?:break
                            if(!change.pressed)break
                            if(!ctl.editable||ctl.observed.sourceGeneration!=source||ctl.observed.configGeneration!=config||ctl.calibration?.let(::soundBoatMapping)!=m)break
                            val delta=change.position-change.previousPosition
                            if(delta!=Offset.Zero){
                                val stagePerPixel=1/(s*context.resources.displayMetrics.density)
                                val previous=at!!
                                at=SoundPosition((previous.x+delta.x*stagePerPixel/frame.unit/m.factor).coerceIn(m.minX,m.maxX),(previous.y+delta.y*stagePerPixel/frame.unit/m.factor).coerceIn(m.minY,m.maxY),previous.z)
                                ctl.movePosition(target,at)
                            }
                            change.consume()
                        }while(true)
                    }
                })
            }
            if(ctl.selectedPosition==SoundPositionTarget.LISTENER)listener?.let{movable(SoundPositionTarget.LISTENER,it,"sound-listener","Hörplatz verschieben")}
            if(ctl.selectedPosition==SoundPositionTarget.SUB&&cal.speakers.single{it.id==SoundSpeakerId.SUB}.positionM!=null)movable(SoundPositionTarget.SUB,locations.getValue(SoundSpeakerId.SUB),"sound-sub-position","Sub verschieben")
        }
    }
}
private fun androidx.compose.ui.graphics.Path.asAndroidPathForSound():AndroidPath = this.asAndroidPath()

@Composable private fun SoundOutlinedButton(palette:SoundEditorPalette,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,content:@Composable RowScope.()->Unit){
    No5ActionFeedback(enabled,palette.accent){feedback->OutlinedButton(onClick=onClick,modifier=modifier.then(feedback.modifier),enabled=feedback.enabled,interactionSource=feedback.interactionSource,border=BorderStroke(1.dp,palette.outline),colors=ButtonDefaults.outlinedButtonColors(contentColor=palette.ink,disabledContentColor=palette.secondary),content=content)}
}
