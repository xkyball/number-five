package com.no5.helm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import com.no5.ui.no5Clickable
import com.no5.ui.LocalNo5ActionFeedbackColor
import com.no5.ui.No5Text
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import com.no5.helm.ocean.rememberOceanReducedMotion
import kotlinx.coroutines.delay

/** Native body only; the shared header/footer remain owned by the host. */
@Composable
fun AudioScreen(
    scale:Float,
    night:Boolean,
    controller:AudioNativeController,
    presentation: AudioPresentationSettings = controller.presentation,
    onSavePresentation: (AudioPresentationSettings) -> Unit = { controller.updatePresentation(it) },
    registerNavigationGuard: ((((() -> Unit) -> Unit)?) -> Unit) = {},
    onOpenDiagnostics: (() -> Unit)? = null,
) {
    val context=LocalContext.current
    DisposableEffect(controller,context){controller.soundEditor.attachStorage(context);onDispose{}}
    val workspaceBackdrop=remember(context){com.no5.header.artwork.No5WorkspaceBackdrop(context.assets)}
    DisposableEffect(workspaceBackdrop){onDispose{workspaceBackdrop.close()}}
    val scene=remember(context,night,com.no5.ui.UiLanguage.current(context)){AudioNativeScene(context,night)}
    val panel=controller.panel;val g=AudioNativeGeometry
    var soundSetupCloseRequest by remember { mutableStateOf<(() -> Unit)?>(null) }
    var soundEditorCloseRequest by remember { mutableStateOf<(() -> Unit)?>(null) }
    var soundEditorNavigationGuard by remember { mutableStateOf<((() -> Unit) -> Unit)?>(null) }
    val isSoundEditor = panel in listOf("sound-preset","sound-speakers")
    var soundSetupNavigationGuard by remember { mutableStateOf<((() -> Unit) -> Unit)?>(null) }
    val requestPanelClose: () -> Unit = {
        if (controller.panel in listOf("sound-preset","sound-speakers")) soundEditorCloseRequest?.invoke()
        else if (controller.panel == "mixer") soundSetupCloseRequest?.invoke()
        else if (controller.panel == "preset")controller.openPanel("audio-menu")else controller.closePanel()
    }
    val latestPanelClose by rememberUpdatedState(requestPanelClose)
    val latestNavigation by rememberUpdatedState<(()->Unit)->Unit> { continuation ->
        val guard = if(controller.panel in listOf("sound-preset","sound-speakers"))soundEditorNavigationGuard else soundSetupNavigationGuard
        if (controller.panel in listOf("mixer","sound-preset","sound-speakers") && guard != null) guard(continuation) else continuation()
    }
    val latestRegisterNavigation by rememberUpdatedState(registerNavigationGuard)
    DisposableEffect(Unit) {
        latestRegisterNavigation { continuation -> latestNavigation(continuation) }
        onDispose { latestRegisterNavigation(null) }
    }
    DisposableEffect(controller) {
        val guard: (() -> Unit) -> Unit = { continuation -> latestNavigation(continuation) }
        controller.panelNavigationGuard = guard
        onDispose { if (controller.panelNavigationGuard === guard) controller.panelNavigationGuard = null }
    }
    val density = LocalDensity.current.density
    DisposableEffect(controller){onDispose{controller.cancelPreview()}}
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var visible by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) }
    val reducedMotion = rememberOceanReducedMotion()
    val marqueeTime = remember { mutableLongStateOf(0L) }
    var queueNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val mediaTitle=if(panel==null)controller.state.media.title else null
    val animateTitle=visible && !reducedMotion && panel==null && scene.titleOverflows(mediaTitle)
    LaunchedEffect(mediaTitle,animateTitle) {
        marqueeTime.longValue=0L
        if(animateTitle) {
            val start=android.os.SystemClock.uptimeMillis()
            while(true) { withFrameNanos { marqueeTime.longValue=android.os.SystemClock.uptimeMillis()-start } }
        }
    }
    LaunchedEffect(visible,controller,panel) {
        if(visible && panel!="media") while(true) { queueNow=System.currentTimeMillis();delay(1000) }
    }
    DisposableEffect(lifecycleOwner, controller) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            visible=lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(visible,panel,controller) {
        controller.soundEditor.catalogVisibility(visible && panel in setOf("audio-menu","preset"))
    }
    DisposableEffect(controller) { onDispose { controller.soundEditor.catalogVisibility(false) } }
    BackHandler(enabled=panel!=null){requestPanelClose()}
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr,
        LocalNo5ActionFeedbackColor provides androidx.compose.ui.graphics.Color(if(night)0xffff1a0a else 0xffff5b00),
        androidx.compose.foundation.text.selection.LocalTextSelectionColors provides androidx.compose.foundation.text.selection.TextSelectionColors(
            androidx.compose.ui.graphics.Color(if(night)0xffed0000 else 0xffff5b00),androidx.compose.ui.graphics.Color(if(night)0x55ed0000 else 0x55ff5b00))) {
        Box(Modifier.fillMaxSize().pointerInput(panel, controller) {
            if (panel != "queue") return@pointerInput
            try {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
                        controller.queueIdle.pointer = event.changes.any { it.pressed }
                        controller.queueIdle.activity()
                    }
                }
            } finally { controller.queueIdle.pointer = false; controller.queueIdle.activity() }
        }.onPreviewKeyEvent {
            if (panel == "queue" && it.type == KeyEventType.KeyDown) controller.queueIdle.activity()
            if(it.type==KeyEventType.KeyDown&&it.key==Key.Escape&&(controller.panel!=null||controller.preview!=null)) {
                if(controller.preview!=null)controller.cancelPreview()else requestPanelClose();true
            }else false
        }) {
            if (panel != "media" && !isSoundEditor) Canvas(Modifier.fillMaxSize()) { drawIntoCanvas { canvas ->
                val c=canvas.nativeCanvas;c.save();val uniform=size.width/AudioNativeGeometry.WIDTH;c.scale(uniform,uniform);scene.draw(c,controller,marqueeTime.longValue,animateTitle,queueNow);c.restore()
            } }
            if (panel !in listOf("media","queue") && !isSoundEditor) {
            val master=controller.masterControl()?.takeIf(controller::isVisible);val masterDb=master?.db;val limit=master?.limit
            // Covered controls must not remain actionable through Accessibility.
            // The compact mixer still leaves MASTER visible and accessible.
            Box(Modifier.fillMaxSize().then(if(panel in listOf("preset","audio-menu"))Modifier.clearAndSetSemantics{} else Modifier)) {
            // Master stays accessible while Sound Setup is open, but only when
            // the gateway explicitly identifies physical control 0 as MASTER.
            if(master!=null) {
                Fader(controller,master,No5Text.ui("Pegel %s",controller.displayName(master)),g.masterSlider,scale,vertical=true)
                AudioButton(No5Text.ui("Master volume down"),g.masterDown,scale,controller.canAdjust(master)&&masterDb!=null&&limit!=null&&masterDb>limit.minDb){controller.stepMaster(-(limit?.stepDb ?: 1.0))}
                AudioButton(No5Text.ui("Master volume up"),g.masterUp,scale,controller.canAdjust(master)&&masterDb!=null&&limit!=null&&masterDb<limit.maxDb){controller.stepMaster(limit?.stepDb ?: 1.0)}
                AudioButton(No5Text.ui(if(master.muted)"%s wieder einschalten"else"%s stummschalten",controller.displayName(master)),g.masterMute,scale,controller.canPresent(AudioCommand.Mute(master.gatewayId,true)),master.muted){controller.toggleMute(master)}
            }
            AudioButton(No5Text.ui("Audio einrichten"),g.settings,scale){controller.openPanel("audio-menu")}
            if (panel != "queue") {
                AudioButton(No5Text.ui("Als Nächstes"),g.nextSongs,scale,controller.mediaAvailable){controller.openPanel("queue")}
                AudioButton(No5Text.ui("Musik"),g.music,scale,controller.mediaAvailable){controller.openPanel("media")}
            }
            }
            if (panel == "mixer" || panel == "preset" || panel == "audio-menu") {
                Canvas(Modifier.fillMaxSize()) {
                    val n=drawContext.canvas.nativeCanvas;val u=size.width/2000f;n.save();n.scale(u,u)
                    val bg=if(night)android.graphics.Color.argb(241,0,0,0)else android.graphics.Color.argb(241,246,249,250)
                    n.drawRect(0f,130f,2000f,1058f,android.graphics.Paint().apply{color=bg})
                    workspaceBackdrop.draw(n,night);n.restore()
                }
                // A transparent scrim above the ordinary AUDIO controls stops
                // an outside-close tap from also changing a live fader.
                val pixelsPerUnit = scale * density
                val bounds = if (panel == "mixer") g.soundSetup else AudioNativeRect(0f,130f,2000f,928f)
                Box(Modifier.fillMaxSize().outsidePanelTap(Rect(
                    bounds.x * pixelsPerUnit, bounds.y * pixelsPerUnit,
                    bounds.right * pixelsPerUnit, bounds.bottom * pixelsPerUnit,
                )) { latestPanelClose() })
            }
            if(panel==null || panel=="queue") {
                val state=controller.state
                if (panel != "queue") {
                AudioReadout(No5Text.ui("Now playing"),"${state.media.title?:No5Text.ui("No track")}. ${state.media.artists.joinToString()}. ${controller.provenanceLabel}",516f,232f,458f,410f,scale)
                val upcoming=audioUpcoming(controller.mediaMetadata,queueNow)
                AudioReadout(No5Text.ui(upcoming.label),upcoming.rows.mapIndexed { i,item -> "${i+1}. ${item.title?:No5Text.ui("Titelinformationen fehlen")}. ${item.artist.orEmpty()}" }.joinToString(". "),519f,493f,451f,126f,scale,live=true)
                val seek=g.seek
                AudioSlider("Seek track",(controller.position()?:0).toFloat(),0f..(state.media.durationMs?.toFloat()?:1f).coerceAtLeast(1f),seek.x,seek.y,seek.width,seek.height,scale,
                    enabled=controller.canPresent(AudioCommand.Transport(TransportAction.SEEK,0)),valueDescription=No5Text.ui("%s von %s",audioTime(controller.position()),audioTime(state.media.durationMs)),
                    onPreview={controller.previewSeek(it.toDouble())},onCommit=controller::commitPreview,onCancel=controller::cancelPreview)
                val actions=listOf(TransportAction.SHUFFLE,TransportAction.PREVIOUS,if(state.media.state==MediaState.PLAYING)TransportAction.PAUSE else TransportAction.PLAY,TransportAction.NEXT)
                actions.forEachIndexed { i,a ->
                    val label=No5Text.ui(when(a){TransportAction.SHUFFLE->"Zufallswiedergabe";TransportAction.PREVIOUS->"Previous track";TransportAction.NEXT->"Next track";TransportAction.PAUSE->"Pause";else->"Play"})
                    AudioButton(label,g.transport[i],scale,controller.canPresent(AudioCommand.Transport(a)),if(a==TransportAction.SHUFFLE)state.media.shuffle==true else null){controller.transport(a)}
                }
                }
                controller.visibleFaderControls.filter { it != master }.take(3).forEachIndexed { slot, control ->
                    Fader(controller,control,No5Text.ui("Pegel %s",controller.displayName(control)),g.zoneSlider(slot),scale,vertical=true)
                    AudioButton(No5Text.ui(if(control.muted)"%s wieder einschalten"else"%s stummschalten",controller.displayName(control)),g.zoneMute(slot),scale,controller.canPresent(AudioCommand.Mute(control.gatewayId,true)),control.muted){controller.toggleMute(control)}
                }
                if (panel != "queue") AudioReadout(No5Text.ui("Audio write status"),if(controller.mediaSelection.status==AudioMediaSelectionStatus.UNKNOWN)No5Text.ui("Ausgang unklar · keine Wiederholung")else state.feedback?:controller.lastOutcome?.message?:No5Text.ui("No pending command"),519f,627f,451f,30f,scale,live=true)
            } else if (panel == "audio-menu") AudioSettingsMenu(night,controller,controller::closePanel,onOpenDiagnostics,
                Modifier.audioRegion(0f,130f,2000f,928f,scale))
            else if (panel == "preset") AudioPresetPanel(
                scale = scale,
                night = night,
                controller = controller,
                onClose = {controller.openPanel("audio-menu")},
                modifier = Modifier.audioRegion(0f,130f,2000f,928f,scale),
            ) else ImeFormViewport(scale,g.soundSetup.x,g.soundSetup.y,g.soundSetup.width,g.soundSetup.height,viewportTag="sound-setup-viewport") {
            SoundSetupPanel(
                scale = scale,
                night = night,
                controller = controller,
                saved = presentation,
                onSave = onSavePresentation,
                onClose = controller::closePresentationPanel,
                modifier = Modifier.fillMaxSize(),
                registerCloseRequest = { soundSetupCloseRequest = it },
                registerNavigationGuard = { soundSetupNavigationGuard = it },
            )
            }
            }
            if (isSoundEditor) AudioSoundEditor(scale,night,controller,controller::closeSoundPanel,visible=visible,
                registerClose={soundEditorCloseRequest=it},registerGuard={soundEditorNavigationGuard=it})
            if (panel == "queue") {
                val pixelsPerUnit = scale * density
                Box(Modifier.fillMaxSize().outsidePanelTap(Rect(
                    g.queueOverlay.x * pixelsPerUnit, g.queueOverlay.y * pixelsPerUnit,
                    (g.queueOverlay.x + g.queueOverlay.width) * pixelsPerUnit,
                    (g.queueOverlay.y + g.queueOverlay.height) * pixelsPerUnit,
                )) { latestPanelClose() })
            }
            if(panel in listOf("queue","media")) ImeFormViewport(scale,0f,130f,2000f,928f,viewportTag="audio-music-viewport"){
                if(panel=="queue")AudioQueueOverlay(scale,night,controller,controller::closePanel,Modifier.fillMaxSize())
                else AudioMediaPanel(scale,night,controller,controller::closePanel,Modifier.fillMaxSize())
            }
        }
    }
}

private fun Modifier.audioRegion(x:Float,y:Float,w:Float,h:Float,scale:Float)=offset((x*scale).dp,(y*scale).dp).size((w*scale).dp,(h*scale).dp)

@Composable
private fun AudioButton(label:String,b:AudioNativeRect,scale:Float,enabled:Boolean=true,selected:Boolean?=null,onClick:()->Unit) =
    AudioButton(label,b.x,b.y,b.width,b.height,scale,enabled,selected,onClick=onClick)
@Composable
private fun Fader(ctl:AudioNativeController,control:AudioFaderControl?,label:String,b:AudioNativeRect,scale:Float,vertical:Boolean=false) =
    Fader(ctl,control,label,b.x,b.y,b.width,b.height,scale,vertical)
@Composable
private fun AudioButton(label:String,x:Float,y:Float,w:Float,h:Float,scale:Float,enabled:Boolean=true,selected:Boolean?=null,focusRequester:FocusRequester?=null,onClick:()->Unit) {
    var modifier=Modifier.audioRegion(x,y,w,h,scale)
    if(focusRequester!=null)modifier=modifier.focusRequester(focusRequester)
    Box(modifier.semantics { contentDescription=label;role=Role.Button; selected?.let { this.selected=it };if(!enabled)disabled() }
        .no5Clickable(enabled=enabled,role=Role.Button,onClick=onClick))
}
@Composable
private fun AudioReadout(label:String,value:String,x:Float,y:Float,w:Float,h:Float,scale:Float,live:Boolean=false) {
    Box(Modifier.audioRegion(x,y,w,h,scale).semantics { contentDescription="$label. $value";if(live)liveRegion=LiveRegionMode.Polite })
}
@Composable
private fun Fader(ctl:AudioNativeController,control:AudioFaderControl?,label:String,x:Float,y:Float,w:Float,h:Float,scale:Float,vertical:Boolean=false) {
    val limit=control?.limit
    val range=(limit?.minDb?.toFloat()?:0f)..(limit?.maxDb?.toFloat()?:1f)
    val value=control?.let(ctl::level)
    AudioSlider(label,value?.toFloat()?:range.start,range,x,y,w,h,scale,vertical,
        control?.let(ctl::canDrag) == true,audioDb(value)+No5Text.ui(if(ctl.preview?.channel==control?.gatewayId)" preview"else if(ctl.pendingVisual?.channel==control?.gatewayId)" awaiting gateway confirmation"else if(ctl.isSimulation)" simulated value"else if(ctl.state.mode==OperatingMode.LIVE)" adapter value"else" unavailable"),
        { requested -> control?.let { ctl.previewLevel(it,requested.toDouble()) } },ctl::commitPreview,ctl::cancelPreview)
}
@Composable
private fun AudioSlider(label:String,value:Float,range:ClosedFloatingPointRange<Float>,x:Float,y:Float,w:Float,h:Float,scale:Float,vertical:Boolean=false,enabled:Boolean,valueDescription:String,onPreview:(Float)->Unit,onCommit:()->Unit,onCancel:()->Unit) {
    // Keep the existing seek identity for keyboard step/gesture ownership; localize its spoken label only.
    val spokenLabel=if(label=="Seek track")No5Text.ui("Seek track")else label
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    val cancel by rememberUpdatedState(onCancel)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentValue by rememberUpdatedState(value)
    val currentRange by rememberUpdatedState(range)
    Box(Modifier.audioRegion(x,y,w,h,scale)
        .semantics {
            contentDescription=spokenLabel;stateDescription=valueDescription
            if(enabled) {
                progressBarRangeInfo=ProgressBarRangeInfo(value.coerceIn(range),range)
                setProgress { requested -> if(currentEnabled){preview(requested.coerceIn(currentRange));commit();true}else false }
            } else disabled()
        }
        .onPreviewKeyEvent { event ->
            if(!enabled||event.type!=KeyEventType.KeyDown)false
            else { val step=if(label=="Seek track")5000f else 1f
                val requested=when(event.key){Key.DirectionRight,Key.DirectionUp->value+step;Key.DirectionLeft,Key.DirectionDown->value-step;Key.MoveHome->range.start;Key.MoveEnd->range.endInclusive;else->null}
                if(requested!=null){preview(requested.coerceIn(range));commit();true}else false
            }
        }
        .focusable(enabled)
        .pointerInput(label,vertical) {
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false)
                if(!currentEnabled)return@awaitEachGesture
                var finished=false
                val startValue=currentValue
                var dragged=false
                fun update(position:androidx.compose.ui.geometry.Offset) {
                    val length=if(vertical)h else w
                    val pixels=if(vertical)size.height.toFloat()else size.width.toFloat()
                    val local=(if(vertical)position.y else position.x)/pixels*length
                    val requested=currentRange.start+AudioNativeGeometry.fraction(local,length,vertical)*(currentRange.endInclusive-currentRange.start)
                    val delta=if(vertical)down.position.y-position.y else position.x-down.position.x
                    if(kotlin.math.abs(delta)>viewConfiguration.touchSlop)dragged=true
                    val bounded=if(!vertical)requested else if(dragged)
                        startValue+delta/pixels*length/(length-40)*(currentRange.endInclusive-currentRange.start)
                        else requested.coerceIn(startValue-3,startValue+3)
                    preview(bounded.coerceIn(currentRange))
                }
                try {
                    down.consume();update(down.position)
                    while(true) {
                        val change=awaitPointerEvent().changes.firstOrNull { it.id==down.id }?:break
                        if(!currentEnabled)break
                        update(change.position);change.consume()
                        if(!change.pressed){commit();finished=true;break}
                    }
                } finally { if(!finished)cancel() }
            }
        })
}
