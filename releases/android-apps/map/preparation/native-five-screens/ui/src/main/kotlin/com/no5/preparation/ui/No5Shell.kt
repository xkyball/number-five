package com.no5.preparation.ui

import com.no5.ui.No5Text
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import com.no5.ui.no5Clickable
import com.no5.ui.No5ActionFeedback
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import com.no5.keyboard.SpecialKeyboardGeometry
import androidx.compose.ui.zIndex
import android.util.Log
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

enum class Destination(val title: String, val shortTitle: String) {
    CHART("KARTE", "Karte"), ROUTES("ROUTEN", "Routen"), AIS("AIS", "AIS"),
    LAYERS("EBENEN", "Ebenen"), SETTINGS("EINSTELLUNGEN", "Setup")
}
@Immutable data class NavigationHud(
    val leg: String = "KEINE NAVIGATION", val distance: String = "—", val bearing: String = "—",
    val xte: String = "—", val eta: String = "—", val active: Boolean = false,
)
enum class PositionButtonMode { OFF, CENTERED, FOLLOW }
@Immutable data class ChartUiState(
    val status: DataStatus = DataStatus(Availability.EMPTY, "Keine Seekarte bestätigt"),
    val ownFixValid: Boolean = false, val following: Boolean = false,
    val zoomInAvailable: Boolean = false, val zoomOutAvailable: Boolean = false,
    val viewport: ViewportSnapshot? = null, val scale: ScaleBar? = null,
    val coverage: String = "Abdeckung unbekannt · Darstellung nicht freigegeben",
    val selectedTarget: String? = null, val targetSummary: String = "",
    val touchActive:Boolean = false,
    val followRequested: Boolean = false, val positionDetail: String = "",
    val positionMode: PositionButtonMode = if (followRequested) PositionButtonMode.FOLLOW else PositionButtonMode.OFF,
    /** Visual instrument authority can remain on the visible buffer while a new query camera is pending. */
    val scaleViewport: ViewportSnapshot? = null,
)
@Immutable data class ShellState(
    val destination: Destination = Destination.CHART, val redNight: Boolean = false,
    val simulated: Boolean = false, val sourceLabel: String = "Keine Datenquelle",
    val chart: ChartUiState = ChartUiState(), val hud: NavigationHud = NavigationHud(),
    val message: String? = null, val mobDialog: Boolean = false, val panelExpanded: Boolean = true,
    /** Backend evidence; must not be inferred from chart rendering availability. */
    val sourceStatus: DataStatus = DataStatus(Availability.EMPTY, "Quellenstatus unbekannt"),
    val uiDimmingPercent: Int = 100,
)
sealed interface ShellEvent {
    data object OpenHome : ShellEvent
    data class Navigate(val destination: Destination) : ShellEvent
    data object TogglePanel : ShellEvent
    data object Back : ShellEvent
    data class SetNight(val enabled: Boolean) : ShellEvent
    data object ZoomIn : ShellEvent
    data object ZoomOut : ShellEvent
    data object NorthUp : ShellEvent
    data object PositionCycle : ShellEvent
    data object CenterBoat : ShellEvent
    data object FollowBoat : ShellEvent
    data object OpenControl : ShellEvent
    data object PositionSources : ShellEvent
    data object CloseTarget : ShellEvent
    data object TargetDetails : ShellEvent
    data object RequestMob : ShellEvent
    data object DismissMob : ShellEvent
    data object ConfirmSimulatedMob : ShellEvent
    data object DismissMessage : ShellEvent
}

/** One persistent renderer slot: dock, navigation and panels never resize the map. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable fun No5Shell(
    state: ShellState, onEvent: (ShellEvent) -> Unit,
    rendererSlot: @Composable (Modifier) -> Unit,
    onBlockedRegions: (List<Rect>) -> Unit = {},
    onGestureBlockedRegions: (List<Rect>) -> Unit = {},
    onDockGestureClaimed: () -> Unit = {},
    isChartTouchActive: () -> Boolean = { false },
    objectPanelVisible: Boolean = false,
    objectPanel: @Composable (Modifier) -> Unit = {},
    chartActions: @Composable ColumnScope.() -> Unit = {},
    mediaHeaderSlot: @Composable (Modifier, Boolean) -> Unit = { _, _ -> },
    headerControlsSlot: (@Composable (Modifier, Boolean, (Boolean) -> Unit) -> Unit)? = null,
    workspacePanels:Boolean = false,
    panelContent: @Composable (Destination, Modifier) -> Unit = { _, _ -> },
) {
    val panels=rememberSaveableStateHolder()
    var dockOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val regions=remember { mutableStateMapOf<String,Rect>() }
    var mapBounds by remember { mutableStateOf(Rect.Zero) }
    var shellBounds by remember { mutableStateOf(Rect.Zero) }
    // Collapsing Settings (or another panel) exposes the map without changing
    // the selected destination. Its controls must return with the map.
    val mapExposed = state.destination == Destination.CHART || !state.panelExpanded
    val scaleViewport = state.chart.scaleViewport ?: state.chart.viewport
    val scaleAvailable = scaleBarLengthDp(state.chart.scale, scaleViewport, mapBounds.width, mapBounds.height,
        1f, mapBounds.width * (488f / 2000f)) != null
    var scaleRegionAtRest by remember { mutableStateOf(false) }
    // Camera/frame authority may briefly diverge between two MOVE events. Hide stale
    // values immediately, but keep this gesture's chrome reservation stable until UP/CANCEL.
    // A gesture begun without a scale bar must not acquire a new exclusion mid-stream.
    val scaleRegionActive = if (state.chart.touchActive) scaleRegionAtRest else scaleAvailable
    val active=buildSet {
        add("legend")
        if(scaleRegionActive)add("scale")
        if(dockOpen&&!state.chart.touchActive)add("dock")
        if(!dockOpen&&!state.chart.touchActive)add("dock-toggle")
        if(mapExposed&&!state.chart.touchActive){if(state.chart.positionDetail.isNotBlank()&&!state.hud.active)add("position-status");add("zoom");if(state.chart.selectedTarget!=null)add("target")}
        else if(state.destination!=Destination.CHART&&state.panelExpanded)add("panel")
        if(state.destination==Destination.CHART&&objectPanelVisible&&!state.chart.touchActive)add("object-panel")
        if(state.hud.active)add("hud")
        if(state.chart.status.availability!=Availability.READY && (state.destination==Destination.CHART || !state.panelExpanded))add("warning")
    }
    val touchMasks=remember(mapBounds) {
        if(mapBounds.width==0f)emptyList()else buildList {
            val scale=mapBounds.width/2000f
            // Conservative 8px strips protect the complete measured ellipse + touch clearance.
            // These input exclusions do not clip, resize or offset renderer pixels.
            KnobGeometry.knobs.forEach { k ->
                val rx=k.rx+KnobGeometry.TOUCH_CLEARANCE;val ry=k.ry+KnobGeometry.TOUCH_CLEARANCE
                var y=(k.cy-ry).coerceAtLeast(130f)
                while(y<1200f) {
                    val end=minOf(1200f,y+8f);val near=k.cy.coerceIn(y,end)
                    val half=rx*kotlin.math.sqrt((1f-((near-k.cy)/ry).let{it*it}).coerceAtLeast(0f))
                    add(Rect(maxOf(0f,k.cx-half)*scale,(y-130f)*scale,minOf(2000f,k.cx+half)*scale,(end-130f)*scale))
                    y=end
                }
            }
        }
    }
    val blocked=active.mapNotNull { regions[it]?.translate(-mapBounds.left,-mapBounds.top) }+touchMasks
    val gestureBlocked=(active-setOf("dock","dock-toggle","zoom","target")).mapNotNull { regions[it]?.translate(-mapBounds.left,-mapBounds.top) }+touchMasks
    SideEffect {
        if (!state.chart.touchActive) scaleRegionAtRest = scaleAvailable
        if(mapBounds.width>0){onGestureBlockedRegions(gestureBlocked);onBlockedRegions(blocked)}
    }
    fun Modifier.opaque(id:String)=graphicsLayer { alpha=state.uiDimmingPercent.coerceIn(30,100)/100f }.onGloballyPositioned { regions[id]=it.boundsInRoot() }.pointerInput(id) {
        awaitPointerEventScope { while(true)awaitPointerEvent(PointerEventPass.Final) }
    }
    val context=LocalContext.current
    val hostView=LocalView.current
    var keyboard by remember{mutableStateOf<android.graphics.Rect?>(null)}
    DisposableEffect(context){val subscription=SpecialKeyboardGeometry.Subscription(context){keyboard=it};onDispose{subscription.close()}}
    val androidDensity=LocalDensity.current
    val reservedBottom=maxOf(WindowInsets.mandatorySystemGestures.getBottom(androidDensity),WindowInsets.navigationBars.getBottom(androidDensity),WindowInsets.displayCutout.getBottom(androidDensity))
    val imeVisible=keyboard!=null || WindowInsets.ime.getBottom(androidDensity)>0
    val swipe=remember(context,androidDensity.density) { BottomDockSwipe(android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat(),64*androidDensity.density) }
    val band by rememberUpdatedState(if(!dockOpen&&!imeVisible&&mapBounds.width>0&&shellBounds.height>0) {
        val scale=mapBounds.width/2000f;val bottom=shellBounds.bottom-reservedBottom
        Rect(mapBounds.left+353*scale,bottom-12*androidDensity.density,mapBounds.left+1656*scale,bottom)
    } else null)
    val claim by rememberUpdatedState(onDockGestureClaimed)
    val chartTouchActive by rememberUpdatedState(isChartTouchActive)
    val visibleButtonRegions by rememberUpdatedState((active intersect setOf("dock","dock-toggle","zoom","target")).mapNotNull { regions[it] })
    val lifecycleOwner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(swipe,lifecycleOwner,shellBounds,mapBounds,reservedBottom,imeVisible,dockOpen) {
        swipe.cancel()
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event -> if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP)swipe.cancel() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose{lifecycleOwner.lifecycle.removeObserver(observer);swipe.cancel()}
    }
    No5Theme(state.redNight) {
        BackHandler(dockOpen || state.destination!=Destination.CHART || state.mobDialog || state.chart.selectedTarget!=null || objectPanelVisible) {
            if(dockOpen && state.destination==Destination.CHART && !state.mobDialog && state.chart.selectedTarget==null && !objectPanelVisible)dockOpen=false
            else onEvent(ShellEvent.Back)
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("no5-shell")
            .onGloballyPositioned { shellBounds=it.boundsInRoot() }
            .semantics { customActions=listOf(CustomAccessibilityAction(if(dockOpen)"Kartenmenü schließen" else "Kartenmenü öffnen") { claim();dockOpen=!dockOpen;true }) }
            .pointerInput(swipe) {
                // Observe the edge gesture before native dispatch. A second DOWN may hit
                // a button before Compose removes it; suppress only that stale hit path.
                interceptOutOfBoundsChildEvents=true
                try { awaitPointerEventScope { while(true) {
                    val pointerEvent=awaitPointerEvent(PointerEventPass.Initial)
                    val event=pointerEvent.motionEvent
                    if(event==null) {
                        if(pointerEvent.changes.none{it.pressed})swipe.cancel()
                    } else if(event.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)) {
                        fun point(x:Float=event.x,y:Float=event.y)=Offset(x,y)
                        fun move(p:Offset){if(swipe.move(p))claim()}
                        when(event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> if(event.pointerCount==1)swipe.down(point(),band)else swipe.cancel()
                            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                                swipe.reject()
                                val added=point(event.getX(event.actionIndex),event.getY(event.actionIndex))
                                if(chartTouchActive()&&visibleButtonRegions.any{it.contains(added)}) {
                                    // The original AndroidView pointer still delivers the complete
                                    // MotionEvent, including this finger, to the chart tracker.
                                    pointerEvent.changes.filter{it.changedToDownIgnoreConsumed()}.forEach{it.consume()}
                                }
                            }
                            android.view.MotionEvent.ACTION_POINTER_UP -> swipe.reject()
                            android.view.MotionEvent.ACTION_MOVE -> if(event.pointerCount==1){for(i in 0 until event.historySize)move(point(event.getHistoricalX(i),event.getHistoricalY(i)));move(point())}else swipe.reject()
                            android.view.MotionEvent.ACTION_UP -> {move(point());if(swipe.up(point()))dockOpen=true}
                            android.view.MotionEvent.ACTION_CANCEL,android.view.MotionEvent.ACTION_OUTSIDE -> swipe.cancel()
                        }
                    }
                } } } finally { swipe.cancel() }
            }) {
            val density=LocalDensity.current
            val scale=minOf(maxWidth.value/2000f,maxHeight.value/1200f)
            Box(Modifier.size((2000*scale).dp,(1200*scale).dp).align(Alignment.Center)) {
                // Logical dp here represents design pixels. Scaling comes only from available
                // stage bounds; the 237mm calibration is never inferred from Android dpi.
                CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(density.density*scale,1f),
                    androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                Box(Modifier.size(2000.dp,1200.dp)) {
                    fun Modifier.region(x:Float,y:Float,w:Float,h:Float)=offset(x.dp,y.dp).size(w.dp,h.dp)
                    Box(Modifier.region(0f,130f,2000f,1070f).clipToBounds().onGloballyPositioned { mapBounds=it.boundsInRoot() }.testTag("map-area")) {
                        // The design raster belongs to chrome only. Existing renderer content,
                        // empty-chart prompts and any dialogs retain Android dp/sp and fontScale.
                        CompositionLocalProvider(LocalDensity provides density) {
                            rendererSlot(Modifier.fillMaxSize().testTag("renderer-slot"))
                        }
                    }
                    // Header and masks are siblings of the renderer, not renderer inputs.
                    Box(Modifier.region(0f,0f,2000f,130f).zIndex(1f)) { No5Header(state,onEvent,mediaHeaderSlot,headerControlsSlot) }
                    No5ControlTheme(state.redNight) {
                        Surface(Modifier.region(24f,150f,500f,40f).opaque("legend").testTag("chart-legend"),shape=androidx.compose.foundation.shape.RoundedCornerShape(9.dp)) {
                            Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(15.dp)) {
                                Text(state.chart.coverage.substringBefore(" · "),Modifier.weight(1f),fontSize=24.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text("POS —  ·  — m",fontSize=24.sp,maxLines=1)
                            }
                        }
                        val chromeDensity = LocalDensity.current
                        val scaleBar = state.chart.scale?.takeIf { scaleRegionActive && scaleBarLengthDp(it, scaleViewport,
                            mapBounds.width, mapBounds.height, chromeDensity.density,
                            with(chromeDensity) { 488.dp.toPx() }) != null }
                        if (scaleBar != null) Surface(Modifier.region(24f,196f,500f,62f).opaque("scale").testTag("chart-scale-bar"),
                            shape=androidx.compose.foundation.shape.RoundedCornerShape(9.dp)) {
                            ScaleBarView(scaleBar, scaleViewport, mapBounds.width, mapBounds.height)
                        }
                        if(mapExposed&&!state.chart.touchActive) {
                            if (state.chart.positionDetail.isNotBlank() && !state.hud.active) Surface(Modifier.region(554f,150f,640f,96f).opaque("position-status").testTag("position-status").no5Clickable { onEvent(ShellEvent.PositionSources) }) {
                                Text(state.chart.positionDetail, Modifier.fillMaxSize().wrapContentHeight().padding(horizontal=12.dp).horizontalScroll(rememberScrollState()).testTag("map-position-status"),
                                    fontSize=24.sp,maxLines=1)
                            }
                            Column(Modifier.region(1864f,150f,112f,384f).opaque("zoom")) {
                                MapIconAction("Nordausrichtung","north-up",state.chart.viewport!=null,{onEvent(ShellEvent.NorthUp)})
                                Box(Modifier.size(112.dp,192.dp)) {
                                    Surface(Modifier.offset(24.dp,0.dp).size(64.dp,192.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {}
                                    Column {
                                        MapIconAction("Vergrößern","zoom-in",state.chart.zoomInAvailable,{onEvent(ShellEvent.ZoomIn)},joined=true)
                                        MapIconAction("Verkleinern","zoom-out",state.chart.zoomOutAvailable,{onEvent(ShellEvent.ZoomOut)},joined=true)
                                    }
                                }
                                MapIconAction(when(state.chart.positionMode) {
                                    PositionButtonMode.OFF -> "Wo bin ich"
                                    PositionButtonMode.CENTERED -> "Boot folgen"
                                    PositionButtonMode.FOLLOW -> "Folgen beenden"
                                }, "position-cycle", state.chart.ownFixValid || state.chart.positionMode == PositionButtonMode.FOLLOW,
                                    {onEvent(ShellEvent.PositionCycle)}, open=state.chart.positionMode!=PositionButtonMode.OFF,
                                    positionMode=state.chart.positionMode)
                            }
                            if(state.destination==Destination.CHART&&objectPanelVisible) {
                                if(workspacePanels){objectPanel(Modifier.opaque("object-panel"))}else Surface(Modifier.region(1210f,214f,620f,700f).clipToBounds().opaque("object-panel").testTag("object-info-panel"),
                                    shape=androidx.compose.foundation.shape.RoundedCornerShape(9.dp),border=androidx.compose.foundation.BorderStroke(1.5.dp,MaterialTheme.colorScheme.outline)) {
                                    CompositionLocalProvider(LocalDensity provides density) { objectPanel(Modifier.fillMaxSize()) }
                                }
                            }
                            state.chart.selectedTarget?.let { target ->
                                Surface(Modifier.region(353f,702f,1303f,210f).opaque("target").testTag("target-overlay")) {
                                    Row(Modifier.padding(24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                                        Column(Modifier.weight(1f)){Text(target,fontSize=30.sp);Text(No5Text.ui(state.chart.targetSummary),fontSize=23.sp)}
                                        No5DockAction("DETAILS",{onEvent(ShellEvent.TargetDetails)},Modifier.width(190.dp))
                                        No5DockAction(No5Text.ui("SCHLIESSEN"),{onEvent(ShellEvent.CloseTarget)},Modifier.width(200.dp))
                                    }
                                }
                            }
                        } else if(state.destination!=Destination.CHART&&state.panelExpanded) {
                            if(workspacePanels){
                                BoxWithConstraints(Modifier.fillMaxSize()) {
                                    val origin=IntArray(2);hostView.getLocationOnScreen(origin)
                                    val keyboardTop=keyboard?.let{with(androidx.compose.ui.platform.LocalDensity.current){(it.top-origin[1]).coerceAtLeast(0).toDp()}}?:maxHeight
                                    val imeBottom=with(androidx.compose.ui.platform.LocalDensity.current){WindowInsets.ime.getBottom(this).toDp()}
                                    val panelHeight=minOf(808.dp,(minOf(maxHeight-imeBottom,keyboardTop)-130.dp).coerceAtLeast(0.dp))
                                    Box(Modifier.region(0f,130f,2000f,panelHeight.value).clipToBounds().opaque("panel").testTag("screen-panel")) {
                                        CompositionLocalProvider(LocalDensity provides density) {
                                            panels.SaveableStateProvider(state.destination.name){panelContent(state.destination,Modifier.fillMaxSize())}
                                        }
                                    }
                                }
                            } else {
                            BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
                            val origin=IntArray(2);hostView.getLocationOnScreen(origin)
                            val keyboardTop=keyboard?.let{with(androidx.compose.ui.platform.LocalDensity.current){(it.top-origin[1]).coerceAtLeast(0).toDp()}}?:maxHeight
                            val panelHeight=minOf(710.dp,(minOf(maxHeight,keyboardTop)-210.dp).coerceAtLeast(0.dp))
                            Surface(Modifier.region(344f,210f,1312f,panelHeight.value).clipToBounds().opaque("panel").testTag("screen-panel"),shape=androidx.compose.foundation.shape.RoundedCornerShape(9.dp),
                                border=androidx.compose.foundation.BorderStroke(1.5.dp,MaterialTheme.colorScheme.outline)) {
                                Column {
                                    Row(Modifier.fillMaxWidth().height(96.dp).padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically) {
                                        Text(No5Text.ui(state.destination.title),Modifier.weight(1f),fontSize=30.sp)
                                        No5DockAction("‹ Einklappen",{onEvent(ShellEvent.TogglePanel)},Modifier.width(230.dp).testTag("panel-toggle"))
                                    }
                                    CompositionLocalProvider(LocalDensity provides density) {
                                        if(state.chart.status.availability!=Availability.READY)ChartWarning(state.chart,Modifier.fillMaxWidth())
                                        panels.SaveableStateProvider(state.destination.name) { panelContent(state.destination,Modifier.weight(1f).fillMaxWidth()) }
                                    }
                                }
                            }
                            }
                            }
                        }
                        if(state.hud.active && (state.destination==Destination.CHART || !state.panelExpanded))Surface(Modifier.region(550f,150f,1080f,112f).opaque("hud").testTag("navigation-hud")) {
                            Column(Modifier.padding(12.dp)) {
                                Text(state.hud.leg,fontSize=26.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Row(horizontalArrangement=Arrangement.spacedBy(24.dp)){No5Metric("DISTANZ",state.hud.distance);No5Metric("PEILUNG",state.hud.bearing);No5Metric("XTE",state.hud.xte);No5Metric("ETA",state.hud.eta)}
                            }
                        }
                        if(state.chart.status.availability!=Availability.READY && (state.destination==Destination.CHART || !state.panelExpanded))
                            ChartWarning(state.chart,Modifier.region(550f,280f,1080f,150f).opaque("warning"))
                        if(!dockOpen&&!state.chart.touchActive) {
                            // The design raster can shrink in OEM windows; retain a real
                            // 48 Android-dp hit target without changing the renderer bounds.
                            val minimum=if(scale>0f)48f/scale else 120f
                            val width=maxOf(120f,minimum);val height=maxOf(96f,minimum)
                            MapIconAction("Kartenmenü öffnen","map-dock-toggle",true,
                                {if(!chartTouchActive())dockOpen=true},
                                Modifier.region((2000f-width)/2f,1192f-height,width,height).opaque("dock-toggle"),dock=true)
                        }
                        if(dockOpen&&!state.chart.touchActive)Surface(Modifier.region(353f,938f,1303f,254f).opaque("dock").testTag("map-dock"),
                            shape=androidx.compose.foundation.shape.RoundedCornerShape(28.dp),border=androidx.compose.foundation.BorderStroke(1.5.dp,MaterialTheme.colorScheme.outline)) {
                            Column {
                                Row(Modifier.fillMaxWidth().height(122.dp).padding(start=10.dp,end=10.dp,top=12.dp),horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                                    Column(Modifier.weight(1f).height(96.dp)){chartActions()}
                                    No5DockAction("MOB",{onEvent(ShellEvent.RequestMob)},Modifier.width(515.dp).testTag("mob"))
                                    MapIconAction("Kartenmenü schließen","dock-close",true,{dockOpen=false},Modifier.width(120.dp).height(96.dp),dock=true,open=true)
                                }
                                MapFooter(state.destination) { onEvent(ShellEvent.Navigate(it)) }
                            }
                        }
                    }
                    MapKnobMasks(state.redNight,state.chart)
                }
                }
            }
        }
        No5ControlTheme(state.redNight) {
        if(state.mobDialog)com.no5.ui.No5DetailsDialog(No5Text.ui("MOB · %s",No5Text.ui(if(state.simulated)"SIMULATION" else "NICHT FREIGEGEBEN")),state.redNight,{onEvent(ShellEvent.DismissMob)},
            body=No5Text.ui("MOB benötigt einen bestätigten aktuellen Fix und eine eigene Bedienfreigabe. Ohne Fix wird keine Position erzeugt."),
            actions=listOf(
                com.no5.header.artwork.No5WorkspaceView.Action("Abbrechen",key="mob-cancel",action={onEvent(ShellEvent.DismissMob)}),
                com.no5.header.artwork.No5WorkspaceView.Action("Testmarkierung setzen",enabled=state.simulated&&state.chart.ownFixValid,key="mob-confirm",action={onEvent(ShellEvent.ConfirmSimulatedMob)})),tag="map-mob")
        state.message?.let { message -> com.no5.ui.No5DetailsDialog("NO 5",state.redNight,{onEvent(ShellEvent.DismissMessage)},body=message,tag="map-message") }
        }
    }
}

/** All dock actions use the approved 96 design-pixel interactive height. */
@Composable fun No5DockAction(label:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
    val shape=androidx.compose.foundation.shape.RoundedCornerShape(9.dp)
    No5ActionFeedback(enabled,MaterialTheme.colorScheme.primary,shape) { feedback ->
        OutlinedButton(onClick,modifier.height(96.dp).then(feedback.modifier),enabled=feedback.enabled,
            interactionSource=feedback.interactionSource,shape=shape,contentPadding=PaddingValues(12.dp)) {
            Text(No5Text.ui(label),fontSize=28.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}
@Composable private fun MapFooter(selected:Destination,onNavigate:(Destination)->Unit) {
    Row(Modifier.fillMaxWidth().height(132.dp).padding(top=10.dp).testTag("footer")) {
        Destination.entries.forEach { item ->
            val color=if(item==selected)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            Box(Modifier.weight(1f).height(114.dp).testTag("nav-${item.name}").semantics(mergeDescendants=true){role=Role.Tab;this.selected=item==selected;contentDescription=item.title}.no5Clickable(feedbackColor=MaterialTheme.colorScheme.primary){onNavigate(item)}) {
                Row(Modifier.align(Alignment.Center).offset(y=(-10).dp).padding(horizontal=17.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    NavigationSymbol(item,color,Modifier.size(44.dp));Text(No5Text.ui(item.title.takeIf{item!=Destination.SETTINGS}?:"SETUP"),fontSize=27.sp,color=color,maxLines=1)
                }
                if(item==selected)Box(Modifier.align(Alignment.BottomCenter).width(206.dp).height(4.dp).background(MaterialTheme.colorScheme.primary).testTag("active-nav-${item.name}"))
            }
        }
    }
}
@Composable private fun MapIconAction(label:String,tag:String,enabled:Boolean,onClick:()->Unit,modifier:Modifier=Modifier,joined:Boolean=false,dock:Boolean=false,open:Boolean=false,positionMode:PositionButtonMode?=null) {
    val language=com.no5.ui.LocalNo5Language.current
    val color=if(open && enabled)MaterialTheme.colorScheme.primary else if(enabled)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier.then(if(dock)Modifier else Modifier.size(112.dp,96.dp)).testTag(tag).semantics{contentDescription=No5Text.text(language,label);role=Role.Button;if(!enabled)disabled();if(dock || tag=="follow-boat" || tag=="position-cycle")selected=open;if(positionMode!=null)stateDescription=No5Text.text(language,when(positionMode){PositionButtonMode.OFF->"Aus";PositionButtonMode.CENTERED->"Einmal zentriert";PositionButtonMode.FOLLOW->"Folgen an"})}.no5Clickable(enabled=enabled,feedbackColor=MaterialTheme.colorScheme.primary,onClick=onClick),contentAlignment=Alignment.Center) {
        if(!joined)Surface(Modifier.size(if(dock)120.dp else 64.dp,if(dock)96.dp else 64.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {}
        Canvas(Modifier.size(46.dp)) {
            val w=size.width;val h=size.height;val stroke=2.8f*w/46
            fun line(x:Float,y:Float,x2:Float,y2:Float)=drawLine(color,Offset(x*w,y*h),Offset(x2*w,y2*h),stroke)
            when(tag) {
                "zoom-in"->{line(.18f,.5f,.82f,.5f);line(.5f,.18f,.5f,.82f)}
                "zoom-out"->line(.18f,.5f,.82f,.5f)
                "north-up"->{drawCircle(color,w*.42f,style=Stroke(stroke));line(.5f,.2f,.3f,.75f);line(.3f,.75f,.5f,.62f);line(.5f,.62f,.7f,.75f);line(.7f,.75f,.5f,.2f)}
                "center-boat","follow-boat","position-cycle"->{
                    if(positionMode==PositionButtonMode.FOLLOW)drawCircle(color,w*.42f,style=Stroke(stroke))
                    if(positionMode==PositionButtonMode.CENTERED)drawCircle(color,w*.12f)
                    else {line(.5f,.12f,.25f,.8f);line(.25f,.8f,.5f,.65f);line(.5f,.65f,.75f,.8f);line(.75f,.8f,.5f,.12f)}
                    if(positionMode==PositionButtonMode.OFF){line(.5f,.0f,.5f,.1f);line(.0f,.5f,.13f,.5f);line(.87f,.5f,1f,.5f)}
                }
                "position-sources"->{drawCircle(color,w*.28f,style=Stroke(stroke));line(.5f,.05f,.5f,.25f);line(.5f,.75f,.5f,.95f);line(.05f,.5f,.25f,.5f);line(.75f,.5f,.95f,.5f)}
                else->{val y=if(open).3f else .7f;line(.2f,y,.5f,1-y);line(.5f,1-y,.8f,y);line(.2f,.9f,.8f,.9f)}
            }
        }
    }
}
@Composable private fun MapKnobMasks(night:Boolean,chart:ChartUiState) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val font=remember(context){context.resources.getFont(R.font.barlow_condensed_semibold)}
    val paint=remember{android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)}
    Canvas(Modifier.size(2000.dp,1200.dp)) {
        val c=drawContext.canvas.nativeCanvas;c.save();c.scale(size.width/2000f,size.height/1200f)
        KnobGeometry.knobs.forEachIndexed { i,k ->
            // Rendered map continues 2mm under the body; no 5mm render ring remains.
            val rx=k.rx-KnobGeometry.MAP_UNDERLAP;val ry=k.ry-KnobGeometry.MAP_UNDERLAP
            paint.color=if(night)android.graphics.Color.rgb(9,0,0)else android.graphics.Color.rgb(246,248,247)
            c.drawOval(android.graphics.RectF(k.cx-rx,k.cy-ry,k.cx+rx,k.cy+ry),paint)
            paint.color=android.graphics.Color.rgb(3,3,3)
            c.drawOval(android.graphics.RectF(k.cx-k.opticalRx,k.cy-k.opticalRy,k.cx+k.opticalRx,k.cy+k.opticalRy),paint)
            paint.typeface=font;paint.textAlign=android.graphics.Paint.Align.CENTER;paint.color=if(night)0xffff2511.toInt()else 0xfff6f8f7.toInt()
            val values=if(i==0)listOf("—°","N","R")else listOf("Z","—",chart.scale?.takeIf{it.viewport==(chart.scaleViewport?:chart.viewport)}?.label?.take(6)?:"—")
            val anchors=if(i==0)listOf(49f to 1094.15f,49f to 1157.4f,112.25f to 1157.4f)else listOf(1943f to 1096.9f,1881.4f to 1157.4f,1943f to 1157.4f)
            anchors.forEachIndexed { j,(x,y)->paint.textSize=if(i==0&&j==1 || i==1&&j==2)36f else 24f;c.drawText(values[j],x,y,paint) }
        };c.restore()
    }
}

@Composable private fun ChartWarning(chart: ChartUiState, modifier: Modifier) {
    Surface(modifier.testTag("chart-warning"), color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
        Column(Modifier.padding(8.dp)) {
            StatusNotice(chart.status)
            Text(chart.coverage, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Native vector marks, no font-dependent emoji or image assets. */
@Composable private fun NavigationSymbol(destination: Destination, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val unit = size.width / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, point(x1, y1), point(x2, y2), 1.6f * unit)
        fun circle(x: Float, y: Float, r: Float) = drawCircle(color, r * unit, point(x, y), style = Stroke(1.6f * unit))
        fun path(vararg vertices: Pair<Float, Float>, closed: Boolean = false) {
            val p = Path().apply { vertices.forEachIndexed { i, xy -> if (i == 0) moveTo(xy.first * unit, xy.second * unit) else lineTo(xy.first * unit, xy.second * unit) }; if (closed) close() }
            drawPath(p, color, style = Stroke(1.6f * unit))
        }
        when (destination) {
            Destination.CHART -> { path(2f to 5f, 8f to 2f, 16f to 5f, 22f to 2f, 22f to 19f, 16f to 22f, 8f to 19f, 2f to 22f, closed = true); line(8f, 2f, 8f, 19f); line(16f, 5f, 16f, 22f) }
            Destination.ROUTES -> { circle(4f, 19f, 2f); circle(20f, 5f, 2f); path(6f to 18f, 13f to 18f, 10f to 7f, 18f to 6f) }
            Destination.AIS -> { path(12f to 2f, 5f to 18f, 12f to 15f, 19f to 18f, closed = true); path(2f to 20f, 7f to 22f, 12f to 20f, 17f to 22f, 22f to 20f) }
            Destination.LAYERS -> { path(2f to 8f, 12f to 2f, 22f to 8f, 12f to 14f, closed = true); path(2f to 13f, 12f to 19f, 22f to 13f); path(2f to 18f, 12f to 23f, 22f to 18f) }
            Destination.SETTINGS -> { circle(12f, 12f, 7f); circle(12f, 12f, 2.5f); repeat(8) { i -> val a = i * kotlin.math.PI / 4; line(12f + cos(a).toFloat() * 7, 12f + sin(a).toFloat() * 7, 12f + cos(a).toFloat() * 10, 12f + sin(a).toFloat() * 10) } }
        }
    }
}
