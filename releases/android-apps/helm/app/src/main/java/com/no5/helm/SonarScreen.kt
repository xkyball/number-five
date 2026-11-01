package com.no5.helm

import com.no5.ui.No5Text
import com.no5.ui.no5Clickable
import com.no5.helm.numeric.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.no5.domain.*
import com.no5.helm.sonar.gl.SonarReliefLayer
import kotlin.math.roundToInt
import kotlin.math.ceil

/** Native 2000×1200 content, exclusively y130..1058. The common shell is owned by No5App. */
@Composable
fun SonarScreen(scale: Float, night: Boolean, controller: SonarNativeController, depthUnit: DepthUnit = DepthUnit.METERS, audio:AudioNativeController?=null, externalScene:Boolean=false) {
    val context=LocalContext.current
    val language=com.no5.ui.LocalNo5Language.current
    if(!externalScene) DisposableEffect(controller) { controller.screenVisible(true); onDispose { controller.screenVisible(false) } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val unit=maxWidth/2000f
        // A full-stage pixel buffer keeps the fractional plot origin/width in
        // the same coordinate system as the existing native Canvas overlay.
        // The unchanged hardware Canvas owns fascia/background edge coverage.
        // The GLES buffer contains only measured/derived data, never an opaque
        // rectangle over a fractionally covered stage edge.
        if(!externalScene) com.no5.helm.gpu.SonarGpuScenePlot(controller,night,depthUnit,audio,Modifier.fillMaxSize())
        SonarReliefLayer(scale,night,controller,depthUnit)
        SonarStatusButton(scale,night,controller,Modifier.sonarRegion(1221f,950f,96f,96f,scale))
        SonarStatusDetails(scale,night,controller,depthUnit)
        val drawer=controller.drawer
        if(drawer==null) SonarHitRegions(scale,controller,depthUnit,audio,sonarUiColors(night).accent)
        else {
            SonarWorkspace(scale,night,controller,drawer,depthUnit)
        }
        if(drawer==null)SonarRateSemantics(scale,controller,depthUnit)
    }
}
@Composable
private fun SonarRateSemantics(scale:Float,controller:SonarNativeController,depthUnit:DepthUnit) {
    // Read the confirmed publication state during composition. In particular,
    // NATIVE → ALL must update the source even if both secondary values are unknown.
    val source=sonarSourceLabel(controller.pingRate.selectedSource)
    val main="${controller.rateTitle()}: ${controller.rateLabel()} · $source"
    val secondary="${No5Text.ui("Brauchbare Tiefe · Auswahl")}: ${controller.selectedUsableRateLabel()} · $source"
    // Live observation labels remain accessible above the drawer and a frozen image.
    Box(Modifier.sonarRegion(390f,130f,395f,44f,scale).testTag("Sonar main published rate").semantics {
        contentDescription=main
    })
    Box(Modifier.sonarRegion(805f,130f,290f,44f,scale).testTag("Sonar selected usable depth rate").semantics {
        contentDescription=secondary
    })
    val depth=sonarPanelPresentation(controller,depthUnit)
    Box(Modifier.sonarRegion(1540f,130f,440f,44f,scale).testTag("Sonar displayed depth").semantics {
        contentDescription=depth.depthLabel
        stateDescription=depth.depthStatusLabel
    })
}
private fun Modifier.sonarRegion(x:Float,y:Float,w:Float,h:Float,scale:Float)=offset((x*scale).dp,(y*scale).dp).size((w*scale).dp,(h*scale).dp)

@Composable
private fun SonarHitRegions(scale:Float,controller:SonarNativeController,depthUnit:DepthUnit,audio:AudioNativeController?,feedbackColor:Color){
    // Raw IDs/counts and automatic contrast change for every batch. The touch
    // hierarchy depends only on these three display controls and sample presence.
    val d by remember(controller){derivedStateOf{controller.snapshot.display.let{
        SonarTouchDisplay(it.freeze,it.aScope,it.exactPingProfiles)
    }}}
    val hasSamples by remember(controller){derivedStateOf{controller.snapshot.ringCount>0}}
    @Composable fun hit(label:String,x:Float,y:Float,w:Float,h:Float,enabled:Boolean=true,reason:String="",pressed:Boolean?=null,tag:String=label,state:String?=null,action:()->Unit){
        Box(Modifier.sonarRegion(x,y,w,h,scale).testTag(tag).semantics {
            contentDescription=label
            if(pressed!=null)selected=pressed
            if(state!=null)stateDescription=state
            if(!enabled){disabled();stateDescription=reason}
        }.no5Clickable(enabled=enabled,role=Role.Button,feedbackColor=feedbackColor,onClick=action))
    }
    hit(No5Text.ui("Sonar freeze"),353f,950f,190f,96f,hasSamples,No5Text.ui("Acquire samples before freezing"),d.freeze){controller.freeze()}
    hit(No5Text.ui("Sonar Bild Auto"),559f,950f,160f,96f){controller.imageAuto()}
    hit(No5Text.ui("Sonar A-Scope"),735f,950f,180f,96f,pressed=d.aScope){controller.toggleAScope()}
    hit(No5Text.ui("Sonar advanced"),931f,950f,274f,96f,tag="Sonar menu"){controller.open()}
    val audioMode=controller.knobMode==SonarKnobMode.AUDIO
    val selectedFader=audio?.selectedFader()?.let { audio.displayName(it) }?:"—"
    val leftHint=if(audioMode)"$selectedFader / ${No5Text.ui("Stumm")}" else No5Text.ui("BEREICH / FREEZE")
    val rightHint=No5Text.ui(if(audioMode)"MEDIEN / PLAY / PAUSE" else "GAIN / A-SCOPE")
    hit(No5Text.ui("KNÖPFE: %s",No5Text.ui(if(audioMode)"AUDIO" else "SONAR")),1333f,950f,323f,96f,
        pressed=audioMode,tag="Sonar knob mode",state="$leftHint · $rightHint"){controller.toggleKnobMode()}
    if(controller.reliefReady&&!d.exactPingProfiles) {
        Box(Modifier.sonarRegion(SonarLayout.PLOT_LEFT,SonarLayout.TOP,SonarLayout.plotWidth(d.aScope),SonarLayout.HEIGHT,scale)
            .semantics {
                contentDescription=No5Text.ui("Sonar echo relief camera. Height represents amplitude, not terrain.")
                customActions=listOf(CustomAccessibilityAction(No5Text.ui("Align range with A-scope")){controller.alignReliefRange();true},CustomAccessibilityAction(No5Text.ui("Reset relief camera")){controller.resetReliefCamera();true},
                    CustomAccessibilityAction(No5Text.ui("Rotate relief left")){controller.reliefCamera(-.15f,0f);true},
                    CustomAccessibilityAction(No5Text.ui("Rotate relief right")){controller.reliefCamera(.15f,0f);true})
            }.pointerInput(controller){detectTransformGestures{_,pan,zoom,_->
                controller.reliefCamera(pan.x/size.width*3f,pan.y/size.height*2f,zoom)
            }})
        return
    }
    val cursor=controller.cursor
    Box(Modifier.sonarRegion(SonarLayout.PLOT_LEFT,SonarLayout.TOP,SonarLayout.plotWidth(d.aScope),SonarLayout.HEIGHT,scale)
        .semantics {
            contentDescription=No5Text.ui("Sonar echogram inspection")
            stateDescription=(cursor?.let{No5Text.ui("Bin %d, amplitude %d of %d, %s seconds ago",it.bin,it.amplitude,it.amplitudeFullScale,sonarNumber(it.secondsAgo))}?:No5Text.ui("%d pings; newest right; %s",controller.snapshot.ringCount,if(controller.model.viewMetersPerBin!=null)No5Text.ui("calibrated %s from transducer",depthUnit.symbol) else No5Text.ui("uncalibrated sample bins")))+(controller.profileInterpretationLabel()?.let{" · $it"}?:"")
            onClick(No5Text.ui("Inspect centre")){controller.inspect(.5f,.5f);true}
            customActions=listOf(CustomAccessibilityAction(No5Text.ui("Inspect newest profile")){controller.inspect(1f,.5f);true})
        }
        .onKeyEvent { event ->
            if(event.type!=KeyEventType.KeyDown)false else {
                val p=controller.cursor;val x=p?.x?:1f;val y=p?.y?:.5f
                when(event.key){Key.DirectionLeft->controller.inspect(x-.01f,y);Key.DirectionRight->controller.inspect(x+.01f,y);Key.DirectionUp->controller.inspect(x,y-.01f);Key.DirectionDown->controller.inspect(x,y+.01f);Key.Enter->controller.inspect(x,y);else->return@onKeyEvent false};true
            }
        }.focusable().pointerInput(controller){
            awaitEachGesture {
                val down=awaitFirstDown();down.consume()
                fun inspect(position:androidx.compose.ui.geometry.Offset){
                    controller.inspect(position.x/size.width,position.y/size.height)
                }
                inspect(down.position)
                do {val event=awaitPointerEvent();val change=event.changes.firstOrNull{it.id==down.id}?:break;inspect(change.position);change.consume()}while(change.pressed)
            }
        })
}

private data class SonarTouchDisplay(val freeze:Boolean,val aScope:Boolean,val exactPingProfiles:Boolean)

internal data class SonarUiColors(val background:Color,val ink:Color,val secondary:Color,val border:Color,val accent:Color,val soft:Color)
internal fun sonarUiColors(night:Boolean)=if(night)SonarUiColors(Color.Black,Color(0xffd40000),Color(0xffb00000),Color(0xff700000),Color(0xffff1a0a),Color(0xff130303))else SonarUiColors(Color(0xfff6f9fa),Color(0xff071022),Color(0xff122653),Color(0xff526b82),Color(0xffff5b00),Color(0xffe6edf2))
private val sonarFont=FontFamily(Font(R.font.barlow_condensed_regular),Font(R.font.barlow_condensed_semibold,FontWeight.SemiBold),Font(R.font.barlow_condensed_extrabold,FontWeight.ExtraBold))

private fun sonarTextStyle(size:Float,scale:Float,color:Color,bold:Boolean=false)=TextStyle(
    color=color,fontFamily=sonarFont,fontSize=(size*scale).sp,fontWeight=if(bold)FontWeight.ExtraBold else FontWeight.SemiBold,
    fontFeatureSettings="tnum",lineHeight=(size*1.2f*scale).sp)
@Composable
internal fun SonarText(text:String,size:Float,scale:Float,color:Color,bold:Boolean=false,modifier:Modifier=Modifier,wrap:Boolean=false){
    Text(text,modifier=modifier,style=sonarTextStyle(size,scale,color,bold),maxLines=if(wrap)Int.MAX_VALUE else 1,softWrap=wrap)
}
@Composable
internal fun SonarButton(text:String,label:String,scale:Float,col:SonarUiColors,onClick:()->Unit,enabled:Boolean=true,reason:String="",selected:Boolean=false,width:Float?=null,modifier:Modifier=Modifier){
    var mod=modifier.heightIn(min=(96*scale).dp)
    if(width!=null)mod=mod.width((width*scale).dp)
    Box(mod.background(if(selected)col.soft else col.background).border((1*scale).dp,if(selected)col.accent else col.border)
        .testTag(label).semantics{contentDescription=text;this.selected=selected;if(!enabled){disabled();stateDescription=reason}}
        .no5Clickable(enabled=enabled,role=Role.Button,feedbackColor=col.accent,onClick=onClick).padding(horizontal=(12*scale).dp,vertical=(8*scale).dp),contentAlignment=Alignment.Center) {
        SonarText(text,27f,scale,if(enabled)if(selected)col.accent else col.ink else col.border)
    }
}
@Composable internal fun SonarHint(text:String,scale:Float,col:SonarUiColors)=SonarText(text,20f,scale,col.secondary,wrap=true)
/** Labels and values have separate lines. Technical values remain fully readable horizontally. */
@Composable
internal fun SonarFact(label:String,value:String,scale:Float,col:SonarUiColors){
    val density=LocalDensity.current
    val measurer=rememberTextMeasurer()
    val valueStyle=sonarTextStyle(24f,scale,col.ink)
    val measured=measurer.measure(value,style=valueStyle,softWrap=false,maxLines=1)
    // Paragraphs keep fractional advances; an integer-rounded intrinsic width can
    // otherwise clip the last digit by a subpixel even in an unbounded scroller.
    val widthPx=ceil(maxOf(measured.multiParagraph.maxIntrinsicWidth,measured.multiParagraph.width).toDouble()).toInt()+2
    val valueWidth=with(density){widthPx.toDp()}
    Column(Modifier.fillMaxWidth().padding(vertical=(6*scale).dp)) {
        SonarText(No5Text.ui(label),19f,scale,col.secondary)
        Box(Modifier.fillMaxWidth().height((96*scale).dp)
            .horizontalScroll(rememberScrollState()).testTag("Sonar fact $label")
            .semantics(mergeDescendants=true){contentDescription="${No5Text.ui(label)}: $value"},contentAlignment=Alignment.CenterStart) {
            Text(value,style=valueStyle,modifier=Modifier.width(valueWidth),maxLines=1,softWrap=false)
        }
        HorizontalDivider(Modifier.padding(top=(8*scale).dp),color=col.border)
    }
}
@Composable
internal fun SonarFactPair(left:Pair<String,String>,right:Pair<String,String>,scale:Float,col:SonarUiColors){
    Row(horizontalArrangement=Arrangement.spacedBy((28*scale).dp)) {Box(Modifier.weight(1f)){SonarFact(left.first,left.second,scale,col)};Box(Modifier.weight(1f)){SonarFact(right.first,right.second,scale,col)}}
}
@Composable
internal fun SonarDisclosure(title:String,scale:Float,col:SonarUiColors,content:@Composable ColumnScope.()->Unit){
    var expanded by remember(title){mutableStateOf(false)}
    SonarButton(No5Text.ui(title)+if(expanded)" −" else " +","Sonar details $title",scale,col,{expanded=!expanded},modifier=Modifier.fillMaxWidth())
    if(expanded) Column(verticalArrangement=Arrangement.spacedBy((8*scale).dp),content=content)
}
@Composable
internal fun SonarSlider(label:String,value:Double,range:ClosedFloatingPointRange<Float>,step:Float,suffix:String,scale:Float,col:SonarUiColors,enabled:Boolean=true,onChange:(Double)->Unit){
    Column {
        SonarText(No5Text.ui(label)+"  "+sonarNumber(value,if(step>=1)0 else if(step<.1f)2 else 1)+suffix,22f,scale,col.ink)
        Box(Modifier.fillMaxWidth().height((96*scale).dp).semantics(mergeDescendants=true){contentDescription="Sonar · ${No5Text.ui(label)}";stateDescription="$value$suffix"},contentAlignment=Alignment.Center){
            Slider(enabled=enabled,value=value.toFloat().coerceIn(range),onValueChange={requested->onChange((range.start+((requested-range.start)/step).roundToInt()*step).coerceIn(range).toDouble())},valueRange=range,
                modifier=Modifier.fillMaxSize(),colors=SliderDefaults.colors(thumbColor=col.accent,activeTrackColor=col.accent,inactiveTrackColor=col.border))
        }
    }
}
@Composable
internal fun SonarImageField(controller:SonarNativeController,field:SonarImageField,label:String,value:Double,mode:SonarImageMode,range:ClosedFloatingPointRange<Float>,step:Float,suffix:String,scale:Float,col:SonarUiColors){
    Column(Modifier.fillMaxWidth().heightIn(min=(136*scale).dp)) {
        val valueLabel=when(field){SonarImageField.GAIN->"%+d".format(value.roundToInt());SonarImageField.GAMMA->sonarNumber(value,1);SonarImageField.THRESHOLD->sonarNumber(value,2)}+suffix
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            SonarText(No5Text.ui(label),30f,scale,col.secondary,modifier=Modifier.weight(1f))
            SonarText((if(field==SonarImageField.GAIN&&mode==SonarImageMode.AUTO)"AUTO · " else "")+valueLabel,28f,scale,col.ink,true)
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy((8*scale).dp)) {
            SonarButton(No5Text.ui("Auto"),"Sonar $field Auto",scale,col,{controller.imageMode(field,SonarImageMode.AUTO)},selected=mode==SonarImageMode.AUTO,width=128f)
            SonarButton(No5Text.ui("Manuell"),"Sonar $field Manual",scale,col,{controller.imageMode(field,SonarImageMode.MANUAL)},selected=mode==SonarImageMode.MANUAL,width=160f)
            Box(Modifier.weight(1f).height((96*scale).dp).testTag("Sonar $field value").semantics(mergeDescendants=true){contentDescription=No5Text.ui(label);stateDescription=valueLabel},contentAlignment=Alignment.Center){
                Slider(value=value.toFloat().coerceIn(range),onValueChange={requested->controller.manualImage(field,(range.start+((requested-range.start)/step).roundToInt()*step).coerceIn(range).toDouble())},valueRange=range,
                    modifier=Modifier.fillMaxSize(),colors=SliderDefaults.colors(thumbColor=col.accent,activeTrackColor=col.accent,inactiveTrackColor=col.border))
            }
        }
    }
}
@Composable
internal fun SonarAnalysisSettings(controller:SonarNativeController,scale:Float,col:SonarUiColors,section:String="analysis-context"){
    val m=controller.model;val s=controller.snapshot
    val cursor=controller.cursor
    val id=if(cursor!=null)cursor.pingId else m.viewRing.takeIf{it.count>0}?.pingIdAt(0)
    val time=cursor?.timestampEpochMs?:m.viewRing.takeIf{it.count>0}?.timestampAt(0)
    val cursorAge=cursor?.pingId?.let { wanted -> (0 until m.viewRing.count).firstOrNull { m.viewRing.pingIdAt(it)==wanted } }
    val p=if(cursor?.firstTimestampEpochMs!=null)null else cursorAge?.let { m.viewRing.presentationAt(it) } ?: m.viewPresentation?.takeIf{it.pingId==id}
    val bottom=cursorAge?.let { m.viewRing.bottomAt(it) } ?: m.viewBottom
    val current=controller.liveEvidence && !s.display.freeze && cursor==null
    val context=if(cursor?.firstTimestampEpochMs!=null)"Bildaggregation" else if(cursor!=null)"Cursor" else if(s.display.freeze)"Eingefroren" else "Live"
    if(section=="analysis-context"){
    SonarFactPair("Befundkontext" to No5Text.ui(context),"Qualität" to sonarAnalysisQualityLabel(p?.quality),scale,col)
    SonarFact("Ping-ID",id?.toString()?:"—",scale,col)
    if(cursor?.firstTimestampEpochMs!=null) {
        SonarFact("Bildaggregation","${cursor.firstTimestampEpochMs}–${cursor.timestampEpochMs} · ${cursor.aggregationCount}",scale,col)
        SonarHint(No5Text.ui("Peak-Aggregation beobachteter Pings · keine einzelne Pinganalyse"),scale,col)
    }
    SonarFactPair("Ping-Beobachtung" to (time?.toString()?:"—"),"Alter" to (controller.observationAgeMs(time)?.let{"$it ms"}?:"—"),scale,col)
    SonarFactPair("Pingquelle" to (if(cursor!=null)sonarSourceLabel(cursor.pingSource)else (cursorAge ?: if(m.viewRing.count>0)0 else null)?.let { sonarSourceLabel(m.viewRing.pingSourceAt(it)) } ?: sonarSourceLabel(null)),"Befundfilter · Wunsch/Wirkung" to (p?.let{"${it.requestedFilter} / ${it.effectiveFilter}"}?:"—"),scale,col)
    }
    if(section=="filter"){
    SonarFact("Live-Filter · Wunsch/Wirkung","${controller.filterState.mode?.name?:"—"} / ${controller.filterState.effectiveMode?:"—"}",scale,col)
    Row {
        SonarFilterMode.entries.forEach {mode->SonarButton(No5Text.ui(mode.name),"Sonar filter ${mode.name}",scale,col,{controller.filter(mode)},enabled=controller.capabilities.filterControl,reason=No5Text.ui("Filter nicht verfügbar"),selected=controller.filterState.mode==mode,modifier=Modifier.weight(1f))}
    }
    SonarFact("Live-Filtergrund",controller.filterState.reason?:"—",scale,col)
    SonarFact("Filterauftrag",controller.filterOutcome?.let{"#${it.commandId?:"—"} · ${it.status} · ${it.reason}"}?:"—",scale,col)
    }
    if(section=="bottom"){
    Row {
        SonarButton(No5Text.ui("Tiefenlinie"),"Sonar current bottom overlay",scale,col,{controller.bottomOverlay(!controller.requestedBottomOverlay)},enabled=controller.capabilities.bottomDetection,reason=No5Text.ui("Bodenbefund nicht verfügbar"),selected=controller.requestedBottomOverlay,modifier=Modifier.weight(1f))
        SonarButton(No5Text.ui("Bestätigte Tracks"),"Sonar confirmed track overlays",scale,col,{controller.trackOverlay(!controller.requestedTrackOverlay)},enabled=controller.capabilities.confirmedTracks,reason=No5Text.ui("Tracks nicht verfügbar"),selected=controller.requestedTrackOverlay,modifier=Modifier.weight(1f))
    }
    SonarFact("Analysegrund",p?.reason?:No5Text.ui("Kein passender Analysebefund für diesen Ping."),scale,col)
    SonarFactPair("Tiefenquelle" to (bottom.source?.name?:"—"),"Track-ID" to (bottom.trackId?.toString()?:"—"),scale,col)
    SonarFactPair("Confidence · heuristisch" to sonarNumber(bottom.confidence,2),"Regelklasse" to sonarClassification(bottom.classification),scale,col)
    SonarFactPair("Tracks / ausgelassen" to (p?.let{"${it.trackTotal} / ${it.trackOmitted}"}?:"—"),"Bereichsempfehlung" to (p?.let{if(it.followBottom)"FOLLOW_BOTTOM"else"HOLD"}?:"—"),scale,col)
    }
    if(section=="analysis-context") {
        SonarFact("Sitzung",controller.sourceStatus.serverSessionId?:"—",scale,col)
        SonarFact("Generation","${controller.sourceStatus.serverGeneration?:controller.sourceStatus.generation}",scale,col)
        SonarFact("Auswahlgeneration",controller.selectedSource.selection?.generation?.toString()?:"—",scale,col)
        SonarHint(No5Text.ui("Klassen sind Regelhypothesen, keine kalibrierten Arten- oder Materialnachweise."),scale,col)
    }
}
@Composable
internal fun SonarDiagnosticsSettings(controller:SonarNativeController,scale:Float,col:SonarUiColors,section:String="all"){
    val s=controller.snapshot;val m=controller.model;val rate=controller.pingRate;val diagnostic=controller.diagnostics
    if(section in listOf("all","source","host")){
    if(section in listOf("all","source")){
    SonarFactPair("Pingquelle" to controller.sourceLabel(),"Verbindung" to No5Text.ui(if(s.connected&&!controller.sourceStatus.stale)"Verbunden" else "Veraltet"),scale,col)
    Row {
        SonarPingSource.entries.forEach {value->SonarButton(sonarSourceLabel(value),"Sonar source ${value.name}",scale,col,{controller.selectSource(value)},enabled=controller.capabilities.sourceSelection,reason=No5Text.ui("Quellenwahl nicht verfügbar"),selected=controller.selectedSource.selection?.source==value,modifier=Modifier.weight(1f))}
    }
    SonarFact("Quellenauftrag",controller.sourceOutcome?.let{"#${it.commandId?:"—"} · ${it.status} · ${it.reason}"}?:"—",scale,col)
    }
    if(section in listOf("all","host")){
    SonarFactPair("Veröffentlichte Pings · Auswahl" to controller.rateLabel(),"Fenster / Abdeckung" to "${rate.windowMs} ms / ${rate.coverageMs} ms",scale,col)
    SonarFactPair("Gezählte Pings" to rate.eventCount.toString(),"Zeitbasis" to No5Text.ui(if(rate.timeBasis==SonarRateTimeBasis.RECEPTION)"Empfangszeit" else "Messzeit"),scale,col)
    SonarFact("Brauchbare Tiefe · Auswahl",controller.selectedUsableRateLabel(),scale,col)
    val usable=controller.usableRate
    SonarFactPair("Brauchbar · Eigene" to controller.ownUsableRateLabel(),"Fenster / Abdeckung" to "${sonarNumber(usable.windowSeconds)} s / ${sonarNumber(usable.observedSeconds)} s",scale,col)
    SonarFactPair("Gezählte Pings" to (usable.count?.toString()?:"—"),"Zeitbasis" to (usable.timeBasis?:"—"),scale,col)
    SonarFact("Hostratenbeleg",usable.reason,scale,col)
    SonarFact("Aktueller Fehler",controller.sourceStatus.error?:"—",scale,col)
    SonarFact("Beobachtungsgrund",controller.observationReasonLabel()?:"—",scale,col)
    SonarFactPair("Hoststatus beobachtet" to (controller.sourceStatus.observationReason?.statusObservedAtEpochMs?.toString()?:"—"),"Fehlerereignis beobachtet" to (controller.sourceStatus.observationReason?.observedAtEpochMs?.toString()?:"—"),scale,col)
    SonarFact("Diagnosestatus",No5Text.ui(if(diagnostic==null||!diagnostic.available)"Erweiterte Diagnose nicht verfügbar."else if(diagnostic.stale)"Analysebefund veraltet"else"Aktuell"),scale,col)
    run {
        val fields=diagnostic?.fields.orEmpty()
        val catalogue=remember(controller,section){fields.map{it.copy(value="—")}}
        val stableFields=catalogue.map{key->fields.firstOrNull{it.group==key.group&&it.name==key.name}?:key}
        SonarFactPair("Status für Hostraten beobachtet" to (diagnostic?.statusObservedAtEpochMs?.toString()?:"—"),
            "Statusalter" to (controller.observationAgeMs(diagnostic?.statusObservedAtEpochMs)?.let{"$it ms"}?:"—"),scale,col)
        SonarFactPair("Hostratenstatus" to No5Text.ui(when(diagnostic?.statusStale){true->"Veraltet";false->"Status frisch";null->"Unbestätigt"}),
            "Statuszeitbasis" to (diagnostic?.statusTimeBasis?:"—"),scale,col)
        SonarHint(No5Text.ui("Statuszeit ist der Beginn der Gateway-Anfrage, keine Ping-Messzeit."),scale,col)
        SonarFactPair("Diagnose-Ping" to (diagnostic?.pingId?.toString()?:"—"),"Diagnosealter" to (controller.observationAgeMs(diagnostic?.observedAtEpochMs)?.let{"$it ms"}?:"—"),scale,col)
        val groups=stableFields.groupBy{it.group}
        sonarDiagnosticSummary(stableFields).chunked(2).forEach { pair ->
            fun SonarDiagnosticSummary.value()=field.value+(field.unit?.let{"\u00a0$it"}?:"")
            if(pair.size==2)SonarFactPair(pair[0].label to pair[0].value(),pair[1].label to pair[1].value(),scale,col)
            else SonarFact(pair[0].label,pair[0].value(),scale,col)
        }
        stableFields.firstOrNull{it.group=="transmit"&&it.name=="ping_rates.window_s"}?.let {field->
            SonarFact("Hostraten · Fenster",field.value+" s",scale,col)
        }
        groups.forEach {(group,fields)->SonarDisclosure(group,scale,col){fields.forEach{field->SonarFact(field.name,field.value+(field.unit?.let{"\u00a0$it"}?:""),scale,col)}}}
        SonarFactPair("Tracks / ausgelassen" to "${diagnostic?.trackTotal?:"—"} / ${diagnostic?.trackOmitted?:"—"}","Blöcke / ausgelassen" to "${diagnostic?.blockTotal?:"—"} / ${diagnostic?.blockOmitted?:"—"}",scale,col)
    }
    }
    }
    if(section in listOf("all","geometry")) {
        val ring=m.viewRing;val available=ring.count>0
        val geometry=if(available)ring.geometryAt(0) else null
        var low=65535;var high=0;var sum=0L
        if(available)for(bin in 0..1023){val v=ring.sampleAt(0,bin);low=minOf(low,v);high=maxOf(high,v);sum+=v}
        SonarFact("Ping-ID",if(available)ring.pingIdAt(0).toString()else "—",scale,col)
        SonarFactPair("Displayprofil Min / Max" to if(available)"$low / $high" else "—","Displayprofil Mittel" to if(available)sonarNumber(sum/1024.0)else "—",scale,col)
        SonarFactPair("Anzeige / Vollskala" to "1024 Bins / ${if(available)ring.amplitudeFullScaleAt(0)else "—"}","ADC-Abstand" to (geometry?.adcSamplePeriodUs?.let{"${sonarNumber(it,2)} µs"}?:"—"),scale,col)
        val amplitude=m.viewAmplitudeContext?.takeIf{it.hasDisplayContext}
        SonarFact("Anzeigetransformation",amplitude?.let{controller.profileInterpretationLabel(it,summarizeHistory=false)}?:"—",scale,col)
        SonarFact("Rauschrevision",amplitude?.noiseRevision?.toString()?:"—",scale,col)
        SonarFact("Amplitude",if(amplitude?.noiseActive==true)No5Text.ui("Bereinigter ADC-Kontrast; keine kalibrierte akustische Leistung.")else"—",scale,col)
        SonarFactPair("Anzeigebin-Abstand" to (if(available)ring.metersPerBinAt(0)?.let{"${sonarNumber(it,5)} m"}?:"—" else "—"),"Sendebezug" to (geometry?.txReference?.name?:"—"),scale,col)
        SonarHint(No5Text.ui("Displayprofil-Statistiken beschreiben nicht den vollständigen Roh-Ping."),scale,col)
    }
    if(section in listOf("all","session")) {
        SonarFact("Sitzung",controller.sourceStatus.serverSessionId?:"—",scale,col)
        SonarFactPair("Generation" to "${controller.sourceStatus.serverGeneration?:controller.sourceStatus.generation}","Sonard-Paket" to (m.hostPackageVersion?:"—"),scale,col)
        SonarFactPair("App-Warteschlangenverluste" to controller.sourceStatus.queueDrops.toString(),"Abgewiesene Frames" to controller.sourceStatus.rejectedFrames.toString(),scale,col)
        SonarFactPair("Pingspeicher" to "${s.ringCount} / 2048","Messbetrieb" to No5Text.ui(if(s.running)"RUNNING" else "Messbetrieb beendet"),scale,col)
        SonarButton(No5Text.ui("Messbetrieb starten"),"Start sonar acquisition",scale,col,{controller.start()},enabled=controller.canStart,reason=No5Text.ui("Start nicht verfügbar"),modifier=Modifier.fillMaxWidth())
        SonarButton(No5Text.ui("Messbetrieb stoppen"),"Stop sonar acquisition",scale,col,{controller.stop()},enabled=controller.canStop,reason=No5Text.ui("Stop nicht verfügbar"),modifier=Modifier.fillMaxWidth())
    }
}
internal fun sonarSourceLabel(source:SonarPingSource?):String=No5Text.ui(when(source){SonarPingSource.OWN->"Eigene";SonarPingSource.NATIVE->"Venterior";SonarPingSource.ALL->"Alle";null->"Quelle unbestätigt"})
internal fun sonarClassification(value:String?):String=when(value){"fish_like"->No5Text.ui("Fischähnlicher Rückläufer · Regelhypothese");null,"unknown"->No5Text.ui("Unbekannt");else->value.replace('_',' ')}
