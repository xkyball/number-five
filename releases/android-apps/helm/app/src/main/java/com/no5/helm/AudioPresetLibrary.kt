package com.no5.helm

import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.no5.domain.*
import com.no5.ui.No5Text
import com.no5.ui.no5Clickable

/** Native primitives bound to approved P1. Saved relative response, never a live FFT. */
internal class PresetLibraryInk(private val context:android.content.Context,private val n:NativeCanvas,night:Boolean,private val font:Float) {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular=context.resources.getFont(R.font.barlow_condensed_regular)
    private val bold=context.resources.getFont(R.font.barlow_condensed_semibold)
    private val heavy=context.resources.getFont(R.font.barlow_condensed_extrabold)
    val ink=if(night)0xffed0000.toInt()else 0xff07121b.toInt()
    val muted=if(night)0xffa40000.toInt()else 0xff526b7b.toInt()
    val line=if(night)0xff550000.toInt()else 0xffb5c2c8.toInt()
    val bg=if(night)android.graphics.Color.BLACK else 0xfff6f9fa.toInt()
    val accent=if(night)0xffed0000.toInt()else 0xffff5b00.toInt()
    fun rect(x:Float,y:Float,w:Float,h:Float,color:Int=bg,stroke:Boolean=false){
        p.color=color;p.style=if(stroke)Paint.Style.STROKE else Paint.Style.FILL;p.strokeWidth=1.5f
        n.drawRoundRect(RectF(x,y,x+w,y+h),4f,4f,p);p.style=Paint.Style.FILL
    }
    fun text(label:String,x:Float,y:Float,size:Float=27f,width:Float=1312f,weight:Int=0,color:Int=ink,raw:Boolean=false,dynamic:Boolean=false){
        p.typeface=when(weight){2->heavy;1->bold;else->regular};p.textSize=size*font;p.color=color;p.style=Paint.Style.FILL
        val full=if(raw)label else No5Text.text(context,label)
        val shown=if(dynamic&&p.measureText(full)>width)full.take(p.breakText(full,true,(width-p.measureText("…")).coerceAtLeast(0f),null))+"…"else full
        n.drawText(shown,x,y,p)
        PresetLibraryDiagnostics.text?.invoke(full,p.measureText(shown),width)
    }
    fun thumbnail(model:SoundPresetPreview?,x:Float,y:Float,w:Float=176f,h:Float=72f){
        rect(x,y,w,h,line,true)
        if(model==null){text("—",x+w/2-6,y+h/2+8,22f,w,color=muted,raw=true);return}
        p.color=line;p.strokeWidth=1f;n.drawLine(x,y+h/2,x+w,y+h/2,p)
        for(output in listOf(false,true)){
            val path=Path();model.gainsDb.forEachIndexed{i,value->
                val db=if(output){if(model.eqEnabled)value-model.reserveDb else 0.0}else value
                val xx=x+i*w/(model.gainsDb.size-1);val yy=y+h/2-(db.coerceIn(-18.0,18.0)/36*h).toFloat()
                if(i==0)path.moveTo(xx,yy)else path.lineTo(xx,yy)
            }
            p.style=Paint.Style.STROKE;p.strokeWidth=if(output)2f else 2.5f;p.color=if(output)muted else accent
            p.pathEffect=if(output)android.graphics.DashPathEffect(floatArrayOf(4f,4f),0f)else null
            n.drawPath(path,p);p.pathEffect=null;p.style=Paint.Style.FILL
        }
    }
}
internal object PresetLibraryDiagnostics {var text:((String,Float,Float)->Unit)?=null}
private fun Modifier.presetRegion(x:Float,y:Float,w:Float,h:Float,s:Float)=offset((x*s).dp,(y*s).dp).size((w*s).dp,(h*s).dp)
@Composable private fun PresetCanvas(night:Boolean,modifier:Modifier=Modifier,originY:Float=0f,stageWidth:Float=2000f,draw:PresetLibraryInk.()->Unit){
    val context=LocalContext.current;val font=LocalDensity.current.fontScale
    Canvas(modifier){val n=drawContext.canvas.nativeCanvas;n.save();val u=size.width/stageWidth;n.scale(u,u);n.translate(0f,-originY);PresetLibraryInk(context,n,night,font).draw();n.restore()}
}

private data class PresetDeletion(val entry:SoundPresetEntry,val basis:SoundRevisionBasis,val source:String,val replacement:String?)
@Composable internal fun AudioPresetPanel(scale:Float,night:Boolean,controller:AudioNativeController,onClose:()->Unit,modifier:Modifier){
    val sound=controller.soundEditor;val context=LocalContext.current
    var routing by remember{mutableStateOf(false)}
    var deleting by remember{mutableStateOf<PresetDeletion?>(null)}
    var deleteCompletion by remember{mutableStateOf(0L)}
    var alive by remember{mutableStateOf(true)}
    DisposableEffect(Unit){alive=true;onDispose{alive=false}}
    BackHandler {if(deleting!=null && !sound.busy)deleting=null else if(routing)routing=false else onClose()}
    LaunchedEffect(sound.libraryCompletion){if(deleting!=null && sound.libraryCompletion!=deleteCompletion)deleting=null}
    if(routing){
        val pending=(controller.state.pending?.command as? AudioCommand.Preset)?.id
        val rows=buildList {
            if(controller.presets.isEmpty())add(com.no5.header.artwork.No5WorkspaceView.Row("No validated preset catalog is available.",enabled=false,key="audio-routing-empty"))
            controller.presets.forEach{preset->add(com.no5.header.artwork.No5WorkspaceView.Row(preset.name,rawLabel=true,
                detail=if(sound.observed.routingProfiles.values.any{it.presetId==preset.id})"EQ und Raumwirkung"else"Technisches Grundprofil",
                value=if(pending==preset.id)"Wird aktiviert"else if(preset.active)"Geladen"else"action.apply",selected=preset.active,
                enabled=!preset.active && controller.canPreset(preset) && controller.state.pending==null && !sound.presetDirty && !sound.busy,
                key="audio-routing-${preset.id}",action={controller.applyPreset(preset)}))}
            controller.lastOutcome?.takeIf{it.command is AudioCommand.Preset}?.let{outcome->add(com.no5.header.artwork.No5WorkspaceView.Row("Status",when(outcome.status){
                AudioCommandStatus.PENDING->"Kanalverteilung wird aktiviert"
                AudioCommandStatus.VERIFIED->"Kanalverteilung aktiviert"
                AudioCommandStatus.FAILED->"Abschluss unklar · zuerst abgleichen"
                AudioCommandStatus.DROPPED,AudioCommandStatus.REJECTED->"Änderung nicht übernommen"},enabled=false,key="audio-routing-status"))}
        }
        com.no5.ui.No5Workspace("Kanalverteilung","Grundprofile · gespeicherte Klangpresets bleiben erhalten",rows,
            night,{routing=false},modifier.testTag("audio-routing-panel"));return
    }
    val p=remember(night){SoundEditorPalette(night)}
    val backdrop=remember(context){com.no5.header.artwork.No5WorkspaceBackdrop(context.assets)}
    DisposableEffect(backdrop){onDispose{backdrop.close()}}
    val catalog=sound.observed.presetCatalog
    val fontScale=LocalDensity.current.fontScale
    Box(modifier.testTag("audio-preset-panel")){
        Canvas(Modifier.fillMaxSize()){
            val n=drawContext.canvas.nativeCanvas;val u=size.width/2000f;n.save();n.scale(u,u);n.translate(0f,-130f);n.clipRect(0f,130f,2000f,1058f)
            n.save();n.clipRect(1060f,130f,2000f,184f)
            PresetLibraryInk(context,n,night,fontScale).rect(1060f,130f,940f,54f);backdrop.draw(n,night);n.restore()
            PresetLibraryInk(context,n,night,fontScale).apply{
                rect(0f,184f,2000f,874f)
                n.drawRect(58f,143f,358f,192f,Paint().apply{color=bg})
                text("KLANGPRESETS",344f,228f,40f,1000f,2)
                text("EQ-Kurve und Ausgang · gespeicherte Einstellungen",344f,266f,24f,color=muted)
                text(if(sound.observed.available && !sound.observed.capabilities.presetManagement)"Neu und Löschen benötigen die Gateway-Erweiterung."else"Grundprofile separat · kein zweiter Eintrag für denselben Klang",344f,864f,24f,color=muted)
                (controller.presetActivationStatus?:sound.status)?.let{text(it,344f,if(sound.hasPresetDraft)1032f else 936f,24f,color=muted)}
                if(!sound.observed.available)text(audioSoundFooter(context,sound),344f,992f,24f,color=muted,raw=true,dynamic=true)
                if(catalog.isEmpty())text(when{!sound.observed.available->"Presetliste noch nicht verfügbar.";!sound.observed.capabilities.presetLibrary->"Gateway unterstützt keine Presetbibliothek.";else->"Noch keine Presets. Mit Neu beginnen."},344f,420f,32f)
            };n.restore()
        }
        SoundTemplateButton("←  Zurück zu AUDIO",AudioNativeRect(58f,13f,580f,49f),scale,p,onClose,plain=true,testTag="audio-preset-back")
        SoundTemplateButton("Neu",AudioNativeRect(1444f,38f,212f,96f),scale,p,{
            if(sound.beginNewPreset())controller.openSoundEditor(false)
        },enabled=sound.canCreatePreset&&!controller.presetActivationBusy,textSize=27f,neutralFill=false,manualCenter=true,evidenceOrigin=androidx.compose.ui.geometry.Offset(0f,130f),testTag="audio-new-preset")
        Column(Modifier.presetRegion(0f,157f,2000f,362f,scale).clipToBounds().verticalScroll(rememberScrollState()).padding(top=scale.dp).testTag("audio-preset-rows")){
            catalog.forEachIndexed {index,entry->key(entry.id){
                val active=entry.id==sound.observed.preset?.id
                Box(Modifier.size((2000*scale).dp,(120*scale).dp).semantics{contentDescription=entry.name}){
                    val rowY=288f+index*120
                    PresetCanvas(night,Modifier.fillMaxSize(),originY=rowY){
                        thumbnail(sound.observed.presetPreviews[entry.id],344f,rowY+12f)
                        text(entry.name,548f,rowY+40f,30f,460f,1,raw=true,dynamic=true)
                        val routingLabel=when(entry.routingTemplate){"stereo_4_1","immersive_4_1"->"4.1";"front_stereo_fullrange"->"2.0";else->entry.routingTemplate}
                        text((if(active)No5Text.text(context,"Aktiv")+" · "else"")+routingLabel,548f,rowY+80f,24f,460f,color=if(active)accent else muted,raw=true)
                        rect(344f,rowY+112f,1312f,1.5f,line)
                    }
                    fun activate(edit:Boolean){
                        if(edit && sound.canResumeDraft(entry)){controller.openSoundEditor(false);return}
                        val epoch=controller.panelEpoch
                        controller.activateSoundPreset(entry){if(edit && alive && controller.panel=="preset" && controller.panelEpoch==epoch)controller.openSoundEditor(false)}
                    }
                    SoundTemplateButton(if(active)"Aktiv"else"Laden",AudioNativeRect(1028f,0f,176f,96f),scale,p,{activate(false)},enabled=!active&&controller.canActivateSoundPreset(entry),textSize=27f,neutralFill=false,manualCenter=true,evidenceOrigin=androidx.compose.ui.geometry.Offset(0f,288f+index*120),accessibilityLabel=No5Text.text(context,"Preset „%s“ aktivieren",entry.name),testTag="audio-load-${entry.id}")
                    SoundTemplateButton("Bearbeiten",AudioNativeRect(1220f,0f,208f,96f),scale,p,{activate(true)},enabled=controller.canActivateSoundPreset(entry)||sound.canResumeDraft(entry),textSize=27f,neutralFill=false,manualCenter=true,evidenceOrigin=androidx.compose.ui.geometry.Offset(0f,288f+index*120),accessibilityLabel=No5Text.text(context,"Preset „%s“ bearbeiten",entry.name),testTag="audio-edit-${entry.id}")
                    SoundTemplateButton("preset.delete",AudioNativeRect(1444f,0f,212f,96f),scale,p,{deleteCompletion=sound.libraryCompletion;deleting=PresetDeletion(entry,SoundRevisionBasis(sound.observed.configGeneration,sound.observed.presetRevision,sound.observed.calibrationRevision),sound.observed.sourceGeneration,sound.afterDeletion(entry))},enabled=sound.canManagePresets&&!controller.presetActivationBusy,textSize=27f,neutralFill=false,manualCenter=true,evidenceOrigin=androidx.compose.ui.geometry.Offset(0f,288f+index*120),accessibilityLabel=No5Text.text(context,"Preset „%s“ löschen",entry.name),testTag="audio-delete-${entry.id}")
                }
            }}
        }
        SoundTemplateButton("Kanalverteilung  ›",AudioNativeRect(344f,590f,1312f,96f),scale,p,{routing=true},enabled=!sound.busy&&!controller.presetActivationBusy,textSize=27f,neutralFill=false,manualCenter=true,evidenceOrigin=androidx.compose.ui.geometry.Offset(0f,130f),testTag="audio-routing")
        if(sound.hasPresetDraft)SoundTemplateButton("Entwurf fortsetzen",AudioNativeRect(344f,770f,1312f,96f),scale,p,{controller.openSoundEditor(false)},enabled=!sound.busy,textSize=27f,testTag="audio-resume-preset")
    }
    deleting?.let{prompt->
        val entry=prompt.entry
        val fresh=sound.observed.let{it.configGeneration==prompt.basis.configGeneration && it.presetRevision==prompt.basis.presetRevision && it.sourceGeneration==prompt.source && it.presetCatalog.any{value->value==entry}}
        PresetWorkflowDialog(night,"Preset löschen?",No5Text.text(context,"„%s“ wird aus der Liste entfernt.",entry.name),
            prompt.replacement?.let{No5Text.text(context,"Aktiver Klang danach: „%s“.",it)}?:No5Text.text(context,"Aktiver Klang danach: %s",No5Text.text(context,"Neutraler Klang.")),
            "Löschen",sound.busy,if(fresh)soundOperationError(sound) else "Stand geändert · Löschbestätigung erneut öffnen", {if(!sound.busy)deleting=null},{sound.deletePreset(entry,prompt.basis,prompt.source)},tag="audio-delete-preset-dialog",canConfirm=fresh&&sound.canManagePresets)
    }
}

/** Fixed P1 layout. Only a long body scrolls; the title and two 48dp actions remain. */
@Composable internal fun PresetWorkflowDialog(night:Boolean,title:String,body:String?,detail:String?,action:String,busy:Boolean,error:String?,onCancel:()->Unit,onConfirm:()->Unit,
    tag:String,name:String?=null,onName:((String)->Unit)?=null,canConfirm:Boolean=true){
    val context=LocalContext.current;val density=LocalDensity.current
    val titleKey=if(title=="Preset speichern")"preset.save-title"else title
    val latestCancel by rememberUpdatedState(onCancel)
    androidx.compose.ui.window.Dialog({if(!busy)latestCancel()},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        CompositionLocalProvider(LocalDensity provides density){
        val view=LocalView.current
        DisposableEffect(view){
            val window=(view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            window?.setDimAmount(0f)
            window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window?.let{androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it,false);androidx.core.view.WindowCompat.getInsetsController(it,it.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())}
            onDispose{}
        }
        var keyboard by remember{mutableStateOf<android.graphics.Rect?>(null)}
        DisposableEffect(context){val sub=com.no5.keyboard.SpecialKeyboardGeometry.Subscription(context){keyboard=it};onDispose{sub.close()}}
        BoxWithConstraints(Modifier.fillMaxSize().testTag("$tag-stage")){
            val s=maxWidth.value/2000f
            val origin=IntArray(2).also{view.getLocationOnScreen(it)}
            val special=keyboard?.let{with(density){(it.top-origin[1]).coerceAtLeast(0).toDp()}}?:maxHeight
            val ime=with(density){WindowInsets.ime.getBottom(this).toDp()}
            val available=minOf(maxHeight,special,(maxHeight-ime).coerceAtLeast(0.dp)).value/s
            val y=minOf(340f,(available-408f-20f).coerceAtLeast(0f))
            val palette=remember(night){SoundEditorPalette(night)}
            Canvas(Modifier.fillMaxSize()){drawRect(Color.Black.copy(alpha=80/255f))}
            Box(Modifier.fillMaxSize().no5Clickable(feedbackColor=Color.Transparent){if(!busy)onCancel()})
            Box(Modifier.presetRegion(510f,y,980f,408f,s).testTag(tag).semantics{paneTitle=No5Text.text(context,titleKey)}){
                Box(Modifier.fillMaxSize().no5Clickable(feedbackColor=Color.Transparent){})
                val font=density.fontScale
                Canvas(Modifier.fillMaxSize()){
                    val n=drawContext.canvas.nativeCanvas;n.save();n.scale(size.width/980f,size.width/980f);n.translate(-510f,-y)
                    PresetLibraryInk(context,n,night,font).apply{rect(510f,y,980f,408f);rect(510f,y,980f,408f,line,true);text(titleKey,548f,y+64f,34f,904f,1)
                        if(name!=null){text("Name",548f,y+112f,24f,904f,color=muted);rect(548f,y+130f,904f,96f,line,true)}
                    };n.restore()
                }
                if(name!=null){
                    BasicTextField(name,{onName?.invoke(it)},enabled=!busy,singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),
                        textStyle=TextStyle(color=palette.ink,fontFamily=FontFamily(Font(R.font.barlow_condensed_regular)),fontSize=(30*s).sp,letterSpacing=0.sp),cursorBrush=SolidColor(palette.accent),
                        modifier=Modifier.presetRegion(58f,130f,864f,96f,s).testTag("audio-preset-name").semantics{contentDescription=No5Text.text(context,"Name")},
                        decorationBox={field->Layout(content=field){measurable,constraints->val child=measurable.single().measure(constraints.copy(minHeight=0));val baseline=child[FirstBaseline];layout(constraints.maxWidth,constraints.maxHeight){child.place(0,(58*s*density.density-baseline).toInt())}}})
                }
                val first=body.orEmpty();val second=detail.orEmpty()
                val note=error?.takeIf{it!="Wird gespeichert"}?.let{No5Text.text(context,it)}
                Column(Modifier.presetRegion(38f,if(name!=null)244f else 100f,904f,if(name!=null)42f else 186f,s).clipToBounds().verticalScroll(rememberScrollState()).testTag("$tag-body").semantics{contentDescription=listOfNotNull(body,detail,note).joinToString("\n")}){
                    val paragraphs=if(name!=null)listOf(note?:second)else listOf(first,second)+listOfNotNull(note)
                    paragraphs.filter{it.isNotEmpty()}.forEachIndexed{i,paragraph->
                        val textSize=if(name==null&&i==0)29f else 27f
                        val metrics=remember(textSize,font){Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=context.resources.getFont(R.font.barlow_condensed_regular);this.textSize=textSize*font}}
                        val lines=remember(paragraph,textSize,font){buildList{var rest=paragraph;while(rest.isNotEmpty()){
                            val fit=metrics.breakText(rest,true,904f,null).coerceAtLeast(1)
                            val cut=if(fit<rest.length)rest.lastIndexOf(' ',fit).takeIf{it>0}?:fit else fit
                            add(rest.take(cut));rest=rest.drop(cut).trimStart()
                        }}}
                        val baseline=if(name!=null)26f else 30f;val step=textSize*font*1.35f
                        val h=maxOf(if(name!=null)42f else 56f,baseline+(lines.size-1)*step+metrics.descent()+4)
                        PresetCanvas(night,Modifier.size((904*s).dp,(h*s).dp),stageWidth=904f){
                            lines.forEachIndexed{lineIndex,lineText->text(lineText,0f,baseline+lineIndex*step,textSize,904f,
                                color=if(paragraph==note)accent else if(name==null&&i==0)ink else muted,raw=true)}
                        }
                    }
                }
                SoundTemplateButton("Abbrechen",AudioNativeRect(38f,294f,440f,96f),s,palette,onCancel,enabled=!busy,textSize=27f,neutralFill=false,manualCenter=true,evidenceOrigin=androidx.compose.ui.geometry.Offset(510f,y),testTag="$tag-cancel")
                SoundTemplateButton(when(action){"Verwerfen"->"preset.discard";"Löschen"->"preset.delete";else->action},AudioNativeRect(502f,294f,440f,96f),s,palette,onConfirm,enabled=!busy&&canConfirm,strong=true,textSize=27f,neutralFill=false,manualCenter=true,evidenceOrigin=androidx.compose.ui.geometry.Offset(510f,y),testTag="$tag-confirm")
            }
        }
        }
    }
}
