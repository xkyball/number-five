package com.no5.helm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import com.no5.ui.no5Clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.no5.domain.*
import com.no5.ui.No5Text
import java.util.Locale
import kotlin.math.roundToInt

internal data class DashboardField(val value: Double?, val quality: TelemetryStatus,
    val observedAtEpochMs: Long?, val simulated: Boolean)

/** The same per-field observation and freshness rules as HELM; no publication-time refresh. */
internal fun dashboardField(raw: HelmState, name: String, value: Double?, now: Long, policy: FreshnessPolicy): DashboardField {
    val observation=raw.fieldObservation(name,value)
    return DashboardField(raw.displayField(name,value,now,policy),raw.fieldStatus(name,now,policy),
        observation.quality.timestampEpochMs,observation.quality.simulated)
}

@Immutable private data class DashboardColors(val paper:Color,val ink:Color,val muted:Color,val line:Color,val accent:Color)
private fun dashboardColors(night:Boolean)=if(night)
    DashboardColors(Color(0xff090000),Color(0xffd40000),Color(0xffb00000),Color(0xff700000),Color(0xffff1a0a))
else DashboardColors(Color(0xfff6f8f7),Color(0xff07121b),Color(0xff344b59),Color(0xff90a0a7),Color(0xff005daf))
private val dashboardFont=FontFamily(Font(R.font.barlow_condensed_semibold))
private val dashboardHero=FontFamily(Font(R.font.barlow_condensed_extrabold))

/** Read-only models and the very same controllers as the detail panels. Owns no sessions,
 * goal state, command queues or command acknowledgements. The host owns its lifetime/tick. */
@Composable
internal fun DashboardScreen(
    scale:Float,
    night:Boolean,
    helm:HelmState,
    weather:WeatherSnapshot,
    depthHistory:DepthHistorySnapshot,
    audio:AudioNativeController,
    sonar:SonarNativeController,
    nowEpochMs:Long,
    helmPolicy:FreshnessPolicy=QualityEvaluator.SHARED,
    weatherPolicy:FreshnessPolicy=QualityEvaluator.SHARED,
    units:DisplayUnits=DisplayUnits(),
    active:Boolean=true,
    reducedMotion:Boolean=false,
    onOpenPage:(String)->Unit,
    helmPresentation:State<HelmPanelPresentation>?=null,
    historyClock:State<Long>?=null,
    externalSonarScene:Boolean=false,
) {
    val context=LocalContext.current
    val fallback=if(helmPresentation==null) {
        val slow=slowPresentation(helm,nowEpochMs,helmPolicy,weather,weatherPolicy,units)
        helmPanelPresentation(helm,nowEpochMs,helmPolicy,slow,motionPresentation(helm,nowEpochMs,helmPolicy,units),
            SpeedDepthInstrumentGeometry.reading(helm,units,slow.values.quality,nowEpochMs,helmPolicy),
            WeatherPresentation.from(weather,nowEpochMs,weatherPolicy))
    } else null
    val panelState=helmPresentation?:rememberUpdatedState(checkNotNull(fallback))
    // Root page/source recomposition must not give all three panel owners a new
    // callback identity. Invoke the current navigation guard only on a real tap.
    val currentOpenPage=rememberUpdatedState(onOpenPage)
    val openPage=remember { { next:String -> currentOpenPage.value(next) } }
    val image by produceState<android.graphics.Bitmap?>(null, context.applicationContext) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            DashboardArtworkImage.load(context.applicationContext)
        }
    }
    val artwork=remember(context,image){DashboardNativeScene(context,image)}
    Box(Modifier.fillMaxSize().testTag("dashboard")) {
        DashboardArtwork(artwork,night,externalSonarScene)
        Canvas(Modifier.fillMaxSize()) {drawIntoCanvas { canvas ->
            val panel=panelState.value
            val c=canvas.nativeCanvas;c.save();c.scale(size.width/2000f,size.height/1200f)
            artwork.drawEngineBands(c,panel.motion.values.portRpm,panel.motion.values.stbdRpm,
                panel.text("rpm-port")!="—",panel.text("rpm-stbd")!="—",night);c.restore()
        }}
        DashboardHelmWidgets(scale,night,panelState,depthHistory,units,active,historyClock,openPage)
        DashboardSonarWidgets(scale,night,sonar,units,externalSonarScene,openPage)
        DashboardAudioWidgets(scale,night,audio,active,reducedMotion,openPage)
    }
}

private fun Modifier.dashboardRegion(rect:AudioNativeRect,scale:Float)=offset((rect.x*scale).dp,(rect.y*scale).dp).size((rect.width*scale).dp,(rect.height*scale).dp)

/** Restart scopes follow their actual owners. A PING cannot invalidate the
 * instrument, weather, artwork and audio text trees. Continuous engine-band
 * motion is read in Canvas's draw phase, outside composition/layout. */
@Composable private fun DashboardHelmWidgets(scale:Float,night:Boolean,panelState:State<HelmPanelPresentation>,
    depthHistory:DepthHistorySnapshot,units:DisplayUnits,active:Boolean,historyClock:State<Long>?,onOpenPage:(String)->Unit) {
    val context=LocalContext.current
    val clock=rememberDashboardTimeFormatter(Locale.forLanguageTag(com.no5.ui.UiLanguage.current(context)))
    val motion=remember(panelState,clock){derivedStateOf{dashboardMotionText(panelState.value,clock)}}
    val slow=remember(panelState,clock){derivedStateOf{dashboardSlowText(panelState.value,clock)}}
    DashboardMotionWidgets(scale,night,motion,units,onOpenPage)
    DashboardDepthTrace(scale,night,slow,depthHistory,active,historyClock)
    DashboardSlowWidgets(scale,night,slow,units)
}

@Composable private fun DashboardMotionWidgets(scale:Float,night:Boolean,observed:State<DashboardMotionText>,
    units:DisplayUnits,onOpenPage:(String)->Unit) {
    val c=dashboardColors(night)
    val context=LocalContext.current
    fun tr(value:String)=No5Text.text(context,value)
    fun Modifier.region(rect:AudioNativeRect)=dashboardRegion(rect,scale)
    fun bounds(x:Float,y:Float,w:Float,h:Float)=AudioNativeRect(x,y,w,h)
    val labels=remember(scale,c){DashboardLabels(scale,c)}
    val shown=observed.value
    val photoInk=if(night)c.ink else Color.White
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.region(DashboardGeometry.helm).testTag("dashboard-open-helm")
            .semantics{contentDescription=tr("HELM")}.no5Clickable(feedbackColor=c.accent){onOpenPage("HELM")})
        labels.text(tr("PORT"),52f,160f,270f,42f,28f,hero=true)
        labels.text(tr("STBD"),980f,160f,274f,42f,28f,hero=true,center=true)
        labels.text(shown.portRpm,48f,236f,280f,132f,120f,hero=true,tag="dashboard-port-rpm")
        labels.text(shown.starboardRpm,978f,236f,276f,132f,120f,hero=true,tag="dashboard-starboard-rpm",center=true)
        labels.text("RPM",74f,366f,230f,48f,40f,hero=true)
        labels.text("RPM",1006f,366f,230f,48f,40f,hero=true,center=true)
        Box(Modifier.region(bounds(48f,416f,270f,44f))) {EvidenceText(shown.portEvidence,scale,c)}
        Box(Modifier.region(bounds(980f,416f,274f,44f))) {EvidenceText(shown.starboardEvidence,scale,c)}
        labels.text("COG",430f,164f,150f,28f,20f,center=true)
        labels.text(shown.course,430f,192f,150f,58f,38f,hero=true,tag="dashboard-course",center=true)
        labels.text("HDG",784f,164f,150f,28f,20f,center=true)
        val heading=shown.heading
        labels.text(heading,784f,192f,150f,58f,38f,hero=true,center=true)
        Box(Modifier.region(bounds(398f,284f,200f,44f))) {EvidenceText(shown.courseEvidence,scale,c,center=true)}
        Box(Modifier.region(bounds(798f,284f,200f,44f))) {EvidenceText(shown.headingEvidence,scale,c,center=true)}
        labels.text(tr("SPEED"),470f,338f,366f,32f,25f,photoInk,center=true)
        labels.text(shown.speed,450f,366f,406f,110f,96f,photoInk,true,"dashboard-speed",true)
        labels.text(units.speed.symbol,470f,474f,366f,34f,30f,photoInk,center=true)
        Box(Modifier.region(bounds(470f,510f,366f,36f))) {
            EvidenceText(shown.speedEvidence,scale,c.copy(muted=photoInk),center=true)
        }
    }
}
@Composable private fun DashboardDepthTrace(scale:Float,night:Boolean,observed:State<DashboardSlowText>,
    depthHistory:DepthHistorySnapshot,active:Boolean,historyClock:State<Long>?) {
    val c=dashboardColors(night);val ink=if(night)c.ink else Color.White
    val status by remember(observed){derivedStateOf{observed.value.depthStatus}}
    DepthPreview(depthHistory,status,active,historyClock,scale,c.copy(accent=ink,muted=ink),
        Modifier.dashboardRegion(DashboardGeometry.history,scale))
}
@Composable private fun DashboardSlowWidgets(scale:Float,night:Boolean,observed:State<DashboardSlowText>,units:DisplayUnits) {
    val c=dashboardColors(night)
    val context=LocalContext.current
    fun tr(value:String)=No5Text.text(context,value)
    fun Modifier.region(rect:AudioNativeRect)=dashboardRegion(rect,scale)
    fun bounds(x:Float,y:Float,w:Float,h:Float)=AudioNativeRect(x,y,w,h)
    val labels=remember(scale,c){DashboardLabels(scale,c)}
    val panel=observed.value
    val photoInk=if(night)c.ink else Color.White
    Box(Modifier.fillMaxSize()) {
        for((side,x) in listOf("port" to 112f,"stbd" to 1080f)) {
            val prefix=if(side=="port")"port" else "starboard"
            for((index,item) in listOf(Triple("voltage","Voltage","VOLTAGE"),Triple("oil","OilBar","ÖLDRUCK"),Triple("coolant","CoolantC","COOLANT")).withIndex()) {
                val y=476f+index*106f
                labels.text(panel.text("secondary-$side-${item.first}"),x,y,180f,44f,36f,hero=true)
                labels.text(tr(item.third),x,y+42f,180f,26f,20f,color=c.muted)
                Box(Modifier.region(bounds(x,y+68f,180f,30f))) {
                    val name=prefix+item.second
                    EvidenceText(panel.fieldEvidence.getValue(name),scale,c,compact=true)
                }
            }
        }
        labels.text(tr("DEPTH"),540f,624f,228f,30f,24f,photoInk,center=true)
        labels.text(panel.depthText,514f,652f,280f,72f,64f,photoInk,true,"dashboard-depth",true)
        labels.text(units.depth.symbol,560f,724f,188f,28f,24f,photoInk,center=true)
        Box(Modifier.region(bounds(514f,754f,280f,36f))) {EvidenceText(panel.depthEvidence,scale,c.copy(muted=photoInk),center=true)}
        val weatherView=panel.weather
        labels.text(No5Text.text(context,weatherView.evidenceLabel),110f,808f,350f,36f,20f,c.muted,maxLines=2)
        labels.text(weatherView.observationTime,110f,850f,350f,26f,16f,c.muted)
        labels.text(weatherView.airTemperature+" °C",550f,800f,240f,48f,36f,hero=true)
        labels.text(tr("AIR TEMP"),550f,848f,240f,28f,20f,c.muted)
        labels.text(weatherView.windSpeed+" kn",950f,800f,240f,48f,36f,hero=true)
        labels.text(tr("WIND"),950f,848f,240f,28f,20f,c.muted)
    }
}

@Composable private fun DashboardSonarWidgets(scale:Float,night:Boolean,sonar:SonarNativeController,
    units:DisplayUnits,externalSonarScene:Boolean,onOpenPage:(String)->Unit) {
    val c=dashboardColors(night)
    val context=LocalContext.current
    val locale=Locale.forLanguageTag(com.no5.ui.UiLanguage.current(context))
    val clock=rememberDashboardTimeFormatter(locale)
    fun tr(value:String)=No5Text.text(context,value)
    fun Modifier.region(rect:AudioNativeRect)=dashboardRegion(rect,scale)
    fun bounds(x:Float,y:Float,w:Float,h:Float)=AudioNativeRect(x,y,w,h)
    val labels=remember(scale,c){DashboardLabels(scale,c)}
    val sonarSnapshot=sonar.snapshot
    val sonarPresentation=sonarPanelPresentation(sonar,units.depth)
    Box(Modifier.fillMaxSize()) {
        labels.text("SONAR",1316f,154f,240f,60f,40f,hero=true)
        labels.text(sonarPresentation.sourceLabel,1568f,150f,244f,30f,20f,c.muted,center=true)
        Box(Modifier.region(DashboardGeometry.sonarDetail).testTag("dashboard-open-sonar")
            .semantics{contentDescription=tr("SONAR")}.no5Clickable(feedbackColor=c.accent){onOpenPage("SONAR")})
        if(!externalSonarScene) SonarPreview(sonar,night,Modifier.region(DashboardGeometry.echogram))
        SonarAxis(sonar,units.depth,scale,c)
        labels.text(sonarPresentation.depthLabel,1304f,626f,450f,48f,40f,hero=true,tag="dashboard-sonar-depth")
        labels.text(sonarPresentation.depthStatusLabel,1780f,626f,174f,48f,20f,c.muted,tag="dashboard-sonar-depth-status")
        SonarStatusButton(scale,night,sonar,Modifier.region(AudioNativeRect(1828f,136f,96f,96f)))
        SonarStatusDetails(scale,night,sonar,units.depth)
        labels.text(tr("Bereich"),1304f,728f,200f,24f,19f,c.muted,center=true)
        labels.text(sonarPresentation.rangeLabel,1304f,752f,200f,30f,23f,tag="dashboard-sonar-range",center=true)
        labels.text(tr("GAIN"),1530f,728f,200f,24f,19f,c.muted,center=true)
        labels.text(sonarPresentation.gainLabel,1530f,752f,200f,30f,23f,tag="dashboard-sonar-gain",center=true)
        labels.text(tr(if(sonarSnapshot.display.freeze)"Fortsetzen" else "Freeze"),1780f,728f,184f,54f,22f,c.muted,center=true)
        val sonarEnabled=sonarSnapshot.ringCount>0
        DashboardAction("−",scale,c,sonarEnabled,Modifier.region(DashboardGeometry.actions.getValue("dashboard-range-down")),"dashboard-range-down",accessibilityLabel=tr("Bereich")+" −") {sonar.adjustRange(1)}
        DashboardAction("+",scale,c,sonarEnabled,Modifier.region(DashboardGeometry.actions.getValue("dashboard-range-up")),"dashboard-range-up",accessibilityLabel=tr("Bereich")+" +") {sonar.adjustRange(-1)}
        DashboardAction("−",scale,c,sonarEnabled,Modifier.region(DashboardGeometry.actions.getValue("dashboard-gain-down")),"dashboard-gain-down",accessibilityLabel=tr("Gain")+" −") {sonar.adjustGain(-1)}
        DashboardAction("+",scale,c,sonarEnabled,Modifier.region(DashboardGeometry.actions.getValue("dashboard-gain-up")),"dashboard-gain-up",accessibilityLabel=tr("Gain")+" +") {sonar.adjustGain(1)}
        DashboardAction(tr(if(sonarSnapshot.display.freeze)"Fortsetzen" else "Freeze"),scale,c,sonarEnabled,
            Modifier.region(DashboardGeometry.actions.getValue("dashboard-freeze")),"dashboard-freeze",DashboardIcon.FREEZE,sonarSnapshot.display.freeze) {sonar.freeze()}

    }
}
@Composable private fun DashboardAudioWidgets(scale:Float,night:Boolean,audio:AudioNativeController,
    active:Boolean,reducedMotion:Boolean,onOpenPage:(String)->Unit) {
    val c=dashboardColors(night)
    val context=LocalContext.current
    fun tr(value:String)=No5Text.text(context,value)
    fun Modifier.region(rect:AudioNativeRect)=dashboardRegion(rect,scale)
    fun bounds(x:Float,y:Float,w:Float,h:Float)=AudioNativeRect(x,y,w,h)
    val labels=remember(scale,c){DashboardLabels(scale,c)}
    val selected=audio.selectedFader()
    val level=selected?.let(audio::level)
    val playback=if(audio.state.media.state==MediaState.PLAYING)TransportAction.PAUSE else TransportAction.PLAY
    Box(Modifier.fillMaxSize()) {
        // One audio strip; every action delegates to AUDIO's existing owner and result path.
        Box(Modifier.region(DashboardGeometry.audioDetail)) {
            Column(Modifier.fillMaxWidth()) {
                val title=audio.state.media.title?:tr("Kein Titel")
                DashboardText(title,33f,scale,c.ink,true,Modifier.fillMaxWidth()
                    .then(if(active&&!reducedMotion)Modifier.basicMarquee(iterations=Int.MAX_VALUE,initialDelayMillis=1200,repeatDelayMillis=1200,velocity=(48*scale).dp) else Modifier),tag="dashboard-title")
                DashboardText(audio.state.media.artists.joinToString(", ").ifBlank{tr("Quelle nicht verfügbar")},24f,scale,c.muted)
                DashboardText(audioPanelSourceLabel(audio),18f,scale,c.muted,tag="dashboard-audio-quality")
            }
        }
        Box(Modifier.region(DashboardGeometry.audioDetail).testTag("dashboard-open-audio")
            .semantics{contentDescription=tr("AUDIO")}.no5Clickable(feedbackColor=c.accent){onOpenPage("AUDIO")})
        DashboardAction(tr("Zurück"),scale,c,audio.canPresent(AudioCommand.Transport(TransportAction.PREVIOUS)),
            Modifier.region(DashboardGeometry.actions.getValue("dashboard-previous")),"dashboard-previous",DashboardIcon.PREVIOUS) {audio.transport(TransportAction.PREVIOUS)}
        DashboardAction(tr(if(playback==TransportAction.PAUSE)"Pause" else "Play"),scale,c,audio.canPresent(AudioCommand.Transport(playback)),
            Modifier.region(DashboardGeometry.actions.getValue("dashboard-play-pause")),"dashboard-play-pause",if(playback==TransportAction.PAUSE)DashboardIcon.PAUSE else DashboardIcon.PLAY) {audio.togglePlayback()}
        DashboardAction(tr("Weiter"),scale,c,audio.canPresent(AudioCommand.Transport(TransportAction.NEXT)),
            Modifier.region(DashboardGeometry.actions.getValue("dashboard-next")),"dashboard-next",DashboardIcon.NEXT) {audio.transport(TransportAction.NEXT)}
        labels.text(selected?.let(audio::displayName)?:tr("Kein Fader"),976f,918f,126f,34f,24f,hero=true)
        labels.text(audioDb(level),976f,954f,126f,44f,29f,hero=true,tag="dashboard-fader-level")
        labels.text(tr("Knopf links"),976f,998f,126f,26f,17f,c.muted)
        DashboardAction("−",scale,c,selected?.let(audio::canPresentAdjust)==true,
            Modifier.region(DashboardGeometry.actions.getValue("dashboard-fader-down")),"dashboard-fader-down",accessibilityLabel=(selected?.let(audio::displayName)?:tr("Kein Fader"))+" −") {audio.adjustSelectedFader(-1)}
        DashboardFader(audio,selected,scale,c,Modifier.region(DashboardGeometry.actions.getValue("dashboard-fader")))
        DashboardAction("+",scale,c,selected?.let(audio::canPresentAdjust)==true,
            Modifier.region(DashboardGeometry.actions.getValue("dashboard-fader-up")),"dashboard-fader-up",accessibilityLabel=(selected?.let(audio::displayName)?:tr("Kein Fader"))+" +") {audio.adjustSelectedFader(1)}
        DashboardAction(tr(if(selected?.muted==true)"Ton ein" else "Stumm"),scale,c,
            selected?.let{audio.canPresent(AudioCommand.Mute(it.gatewayId,!it.muted))}==true,
            Modifier.region(DashboardGeometry.actions.getValue("dashboard-mute")),"dashboard-mute",DashboardIcon.MUTE,selected?.muted==true) {audio.toggleSelectedMute()}    }
}

@Composable private fun DashboardArtwork(scene:DashboardNativeScene,night:Boolean,externalSonarScene:Boolean) {
    Canvas(Modifier.fillMaxSize()) {drawIntoCanvas { canvas ->
        val c=canvas.nativeCanvas;c.save();c.scale(size.width/2000f,size.height/1200f)
        scene.draw(c,night,externalSonarScene);c.restore()
    }}
}

@Immutable private class DashboardLabels(private val scale:Float,private val colors:DashboardColors) {
    @Composable fun text(value:String,x:Float,y:Float,w:Float,h:Float,size:Float,color:Color=colors.ink,
        hero:Boolean=false,tag:String?=null,center:Boolean=false,maxLines:Int=1) {
        Box(Modifier.offset((x*scale).dp,(y*scale).dp).size((w*scale).dp,(h*scale).dp),
            contentAlignment=if(center)Alignment.Center else Alignment.CenterStart) {
            DashboardText(value,size,scale,color,hero,Modifier.fillMaxWidth(),maxLines,tag,center)
        }
    }
}

@Composable private fun DashboardText(value:String,size:Float,scale:Float,color:Color,hero:Boolean=false,modifier:Modifier=Modifier,maxLines:Int=1,tag:String?=null,center:Boolean=false) {
    val textStyle=remember(color,scale){androidx.compose.ui.text.TextStyle(shadow=if(color==Color.White)androidx.compose.ui.graphics.Shadow(
        Color.Black.copy(alpha=.75f),androidx.compose.ui.geometry.Offset.Zero,2f*scale) else null)}
    Text(value,modifier.then(if(tag!=null)Modifier.testTag(tag) else Modifier),color=color,fontFamily=if(hero)dashboardHero else dashboardFont,
        fontSize=(size*scale).sp,lineHeight=(size*1.12f*scale).sp,maxLines=maxLines,overflow=TextOverflow.Ellipsis,
        style=textStyle,
        textAlign=if(center)androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start)
}
private enum class DashboardIcon { PREVIOUS,PLAY,PAUSE,NEXT,MUTE,FREEZE }
@Composable private fun DashboardAction(label:String,scale:Float,c:DashboardColors,enabled:Boolean,modifier:Modifier=Modifier,tag:String,
    icon:DashboardIcon?=null,active:Boolean=false,accessibilityLabel:String=label,onClick:()->Unit) {
    val shape=if(icon==DashboardIcon.PLAY||icon==DashboardIcon.PAUSE)androidx.compose.foundation.shape.CircleShape
        else androidx.compose.foundation.shape.RoundedCornerShape((14*scale).dp)
    Box(modifier.testTag(tag).semantics{contentDescription=accessibilityLabel}
        .background(if(active)c.accent.copy(alpha=.12f)else Color.Transparent,shape)
        .then(if(icon==DashboardIcon.PREVIOUS||icon==DashboardIcon.NEXT)Modifier else Modifier.border((1.5f*scale).dp,if(active)c.accent else if(enabled)c.line else c.line.copy(alpha=.45f),shape))
        .no5Clickable(enabled=enabled,feedbackColor=c.accent,shape=shape,onClick=onClick),contentAlignment=Alignment.Center) {
        if(icon==null)DashboardText(label,36f,scale,if(enabled)c.ink else c.line)
        else Canvas(Modifier.size((48*scale).dp)) {DashboardIconDrawing.draw(this,icon.name,if(enabled)c.ink else c.line)}
    }
}
/** A full-height hit surface, rather than a Material slider's narrower internal
 * semantics/gesture node. Preview and commit still belong solely to AUDIO's controller. */
@Composable private fun DashboardFader(audio:AudioNativeController,control:AudioFaderControl?,scale:Float,c:DashboardColors,modifier:Modifier) {
    val limit=control?.limit
    val range=(limit?.minDb?.toFloat()?:0f)..(limit?.maxDb?.toFloat()?:1f)
    val value=(control?.let(audio::level)?.toFloat()?:range.start).coerceIn(range)
    val enabled=control?.let(audio::canDrag)==true
    val currentEnabled by rememberUpdatedState(enabled)
    val currentControl by rememberUpdatedState(control)
    val currentRange by rememberUpdatedState(range)
    Box(modifier.testTag("dashboard-fader")
        .semantics {
            contentDescription=control?.let(audio::displayName)?:No5Text.ui("Kein Fader")
            if(enabled) {
                progressBarRangeInfo=ProgressBarRangeInfo(value,range)
                setProgress { requested ->
                    if(currentEnabled) {currentControl?.let {audio.previewLevel(it,requested.coerceIn(currentRange).toDouble())};audio.commitPreview();true}
                    else false
                }
            } else disabled()
        }.onPreviewKeyEvent { event ->
            if(!currentEnabled||event.type!=KeyEventType.KeyDown)false
            else when(event.key) {
                Key.DirectionLeft,Key.DirectionDown -> audio.adjustSelectedFader(-1)
                Key.DirectionRight,Key.DirectionUp -> audio.adjustSelectedFader(1)
                else -> false
            }
        }.focusable(enabled)
        .pointerInput(audio,control?.gatewayId,limit) {
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false)
                if(!currentEnabled)return@awaitEachGesture
                var committed=false
                fun preview(x:Float) {
                    val inset=20f*scale*density
                    val fraction=((x-inset)/(size.width-2*inset).coerceAtLeast(1f)).coerceIn(0f,1f)
                    val requested=currentRange.start+fraction*(currentRange.endInclusive-currentRange.start)
                    currentControl?.let{audio.previewLevel(it,requested.toDouble())}
                }
                try {
                    down.consume();preview(down.position.x)
                    while(true) {
                        val change=awaitPointerEvent().changes.firstOrNull{it.id==down.id}?:break
                        if(!currentEnabled)break
                        preview(change.position.x);change.consume()
                        if(!change.pressed){audio.commitPreview();committed=true;break}
                    }
                } finally {if(!committed)audio.cancelPreview()}
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            val inset=20f*scale*density
            val y=size.height/2
            val fraction=if(range.endInclusive>range.start)(value-range.start)/(range.endInclusive-range.start) else 0f
            val x=inset+(size.width-2*inset)*fraction
            drawLine(c.line,androidx.compose.ui.geometry.Offset(inset,y),androidx.compose.ui.geometry.Offset(size.width-inset,y),(5*scale*density).coerceAtLeast(1f))
            drawLine(if(enabled)c.accent else c.line,androidx.compose.ui.geometry.Offset(inset,y),androidx.compose.ui.geometry.Offset(x,y),(5*scale*density).coerceAtLeast(1f))
            drawCircle(if(enabled)c.ink else c.line,14*scale*density,androidx.compose.ui.geometry.Offset(x,y))
        }
    }
}
@Composable private fun rememberDashboardTimeFormatter(locale:Locale):DashboardTimeFormatter {
    val zone=java.util.TimeZone.getDefault()
    return remember(locale,zone.id){DashboardTimeFormatter(locale,zone)}
}
@Composable private fun EvidenceText(value:String,scale:Float,c:DashboardColors,center:Boolean=false,compact:Boolean=false) {
    DashboardText(value,
        if(compact)15f else 18f,scale,c.muted,modifier=Modifier.fillMaxWidth(),maxLines=2,center=center)
}
@Composable private fun SonarAxis(controller:SonarNativeController,unit:DepthUnit,scale:Float,c:DashboardColors) {
    val bounds=controller.bounds();val meters=controller.model.viewMetersPerBin
    for(i in 0..4) {
        val bin=bounds.start+(bounds.end-bounds.start)*i/4
        val value=if(meters==null)kotlin.math.round(bin).toInt().toString() else sonarNumber(unit.fromMeters(bin*meters))
        Box(Modifier.offset((1934*scale).dp,((220f+i*95)*scale).dp).size((62*scale).dp,(26*scale).dp)) {
            DashboardText(value,18f,scale,c.muted)
        }
    }
}
@Composable private fun DepthPreview(history:DepthHistorySnapshot,status:TelemetryStatus,active:Boolean,clock:State<Long>?,scale:Float,c:DashboardColors,modifier:Modifier) {
    val now=clock?:rememberInstrumentHistoryClock(history,active)
    val cache=remember{InstrumentHistoryTraceCache()}
    Canvas(modifier.semantics{contentDescription="Depth history · 60 s · $status"}) {
        drawLine(c.line,androidx.compose.ui.geometry.Offset(0f,size.height),androidx.compose.ui.geometry.Offset(size.width,size.height),scale)
        val frame=instrumentHistoryFrame(cache,history,now.value)
        val values=frame.trace.points
        if(values.isNotEmpty()) {
            val path=Path();values.forEachIndexed{index,point->
                val x=point.x*size.width
                val y=point.y*size.height
                if(index==0||point.breakBefore)path.moveTo(x,y)else path.lineTo(x,y)
            }
            clipRect {translate(left=-frame.scrollFraction*size.width) {
                drawPath(path,if(status==TelemetryStatus.LIVE)c.accent else c.muted,style=Stroke(width=(2*scale).coerceAtLeast(1f)))
            }}
        }
    }
}
@Composable private fun SonarPreview(controller:SonarNativeController,night:Boolean,modifier:Modifier) {
    val context=LocalContext.current
    val renderer=remember(context){SonarNativeRenderer(context,externalPlot=true)}
    SonarRendererLifecycle(renderer)
    Box(modifier.testTag("dashboard-echogram").semantics{contentDescription="Sonar echogram. ${controller.statusText}"}) {
      com.no5.helm.gpu.SonarGpuPlot(controller,night,"DASHBOARD",Modifier.fillMaxSize())
      Canvas(Modifier.fillMaxSize()) {
        controller.snapshot;controller.linearInterpolation
        drawIntoCanvas { canvas ->
            renderer.drawPreview(canvas.nativeCanvas,controller,night,android.graphics.RectF(0f,0f,size.width,size.height))
        }
    }
    }
}
