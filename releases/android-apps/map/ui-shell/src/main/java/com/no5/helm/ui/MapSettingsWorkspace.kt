package com.no5.helm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.no5.header.artwork.No5WorkspaceView
import com.no5.preparation.ui.*
import com.no5.ui.No5Text
import com.no5.ui.No5Workspace

/** M1 routes only. No technical diagnostics and no new gateway/storage actions. */
@Composable internal fun MapSettingsWorkspace(state:SettingsState,onEvent:(SettingsEvent)->Unit,
 night:Boolean,onClose:()->Unit,onPosition:()->Unit,onStyle:()->Unit,onRenderer:()->Unit,
 onConnection:()->Unit,onInfo:(()->Unit)?,library:@Composable ((()->Unit))->Unit,
 layers:@Composable ((()->Unit))->Unit,displayContent:@Composable ()->Unit,diagnosticsContent:@Composable ()->Unit,
 modifier:Modifier=Modifier,openLibrary:Boolean=false,onLibraryOpened:()->Unit={}){
 var section by rememberSaveable{mutableStateOf("menu")}
 LaunchedEffect(openLibrary){if(openLibrary){section="library";onLibraryOpened()}}
 val parent=mapOf("appearance" to "menu","display" to "menu","about" to "menu","fast" to "appearance","layers" to "appearance","library" to "menu","distance" to "display","speed" to "display","dimming" to "display")
 fun back(){if(section=="menu")onClose()else section=parent[section]?:"menu"}
 BackHandler{back()}
 when(section){
  "library"->library{section="menu"}
  "layers"->layers{section="appearance"}
  "fast"->MapWorkspaceForm(m1("Schneller Kartenmodus","Fast chart mode","Vinnige kaartmodus"),"",night,{section="appearance"},modifier){displayContent()}
  else->{
   fun row(label:String,key:String,action:()->Unit)=No5WorkspaceView.Row(label,key=key,action=action)
   val rows=when(section){
    "appearance"->listOf(row(m1("Stil und Symbole","Style and symbols","Styl en simbole"),"map-appearance-style",onStyle),row(m1("Karteninhalt","Chart content","Kaartinhoud"),"map-settings-layers"){section="layers"},row(m1("Schneller Kartenmodus","Fast chart mode","Vinnige kaartmodus"),"map-fast-mode"){section="fast"})
    "display"->listOf(row(m1("Distanzeinheit","Distance unit","Afstandseenheid"),"map-display-distance"){section="distance"},row(m1("Geschwindigkeitseinheit","Speed unit","Spoedeenheid"),"map-display-speed"){section="speed"},row(m1("UI-Dimmung","UI dimming","UI-verduistering"),"map-display-dimming"){section="dimming"})
    "distance"->DistanceUnit.entries.map{v->No5WorkspaceView.Row(if(v==DistanceUnit.NAUTICAL_MILES)m1("sm","nm","nm")else"km",selected=state.distanceUnit==v,key="map-unit-$v",control="check",action={onEvent(SettingsEvent.SetDistanceUnit(v))})}
    "speed"->SpeedUnit.entries.map{v->No5WorkspaceView.Row(if(v==SpeedUnit.KNOTS)"kn"else"km/h",selected=state.speedUnit==v,key="map-unit-$v",control="check",action={onEvent(SettingsEvent.SetSpeedUnit(v))})}
    "dimming"->listOf(30,60,100).map{v->No5WorkspaceView.Row("$v %",selected=state.uiDimmingPercent==v,key="map-dimming-$v",control="check",action={onEvent(SettingsEvent.SetUiDimming(v))})}
    "about"->listOf(row(m1("App-/Buildinformationen","App/build information","Toep-/bouinligting"),"map-about-build"){onInfo?.invoke()},row(m1("Änderungen","Changes","Veranderings"),"map-about-changes"){onInfo?.invoke()})
    else->listOf(row(m1("Darstellung","Appearance","Voorkoms"),"map-settings-appearance"){section="appearance"},row(m1("Position","Position","Posisie"),"map-settings-position",onPosition),row(m1("Anzeige","Display","Vertoning"),"map-settings-display"){section="display"},row(m1("Kartenbestand","Chart library","Kaartversameling"),"map-settings-library"){section="library"},row(m1("Über MAP","About MAP","Oor MAP"),"map-settings-about"){section="about"})
   }
   val title=when(section){"appearance"->m1("Darstellung","Appearance","Voorkoms");"display"->m1("Anzeige","Display","Vertoning");"distance"->m1("Distanzeinheit","Distance unit","Afstandseenheid");"speed"->m1("Geschwindigkeitseinheit","Speed unit","Spoedeenheid");"dimming"->m1("UI-Dimmung","UI dimming","UI-verduistering");"about"->m1("Über MAP","About MAP","Oor MAP");else->m1("Einstellungen","Settings","Instellings")}
   No5Workspace(title,"",rows,night,::back,modifier.testTag("map-settings-$section"),showBack=section!="menu")
  }
 }
}
@Composable internal fun MapWorkspaceForm(title:String,subtitle:String,night:Boolean,onBack:()->Unit,modifier:Modifier=Modifier,content:@Composable ()->Unit){
 BoxWithConstraints(modifier.testTag("map-workspace-form")){
  val scale=maxWidth.value/2000f
  No5Workspace(title,subtitle,emptyList(),night,onBack,Modifier.fillMaxSize())
  Box(Modifier.offset((360*scale).dp,(148*scale).dp).size((1280*scale).dp,minOf((526*scale).dp,(maxHeight-148.dp*scale).coerceAtLeast(0.dp))).clipToBounds().testTag("map-workspace-form-body")){
   No5WorkspaceTheme(night,scale){CompositionLocalProvider(LocalNo5FlatPanel provides true){content()}}
  }
 }
}
@Composable internal fun MapLayersWorkspace(state:LayersState,onEvent:(LayersEvent)->Unit,night:Boolean,onClose:()->Unit,modifier:Modifier=Modifier,s52Supported:Boolean=true,objectContent:@Composable ()->Unit){
 var details by rememberSaveable{mutableStateOf(false)}
 BackHandler{if(details)details=false else onClose()}
 if(details)objectContent() else{
  val rows=state.layers.filter{it.id in setOf("ownship","ais","waypoints","route")}.map{v->No5WorkspaceView.Row(
   when(v.id){"ownship"->m1("Bootsposition","Boat position","Bootposisie");"ais"->"AIS";"waypoints"->m1("Wegpunkte","Waypoints","Wegpunte");else->m1("Route/Entwurf","Route/draft","Roete/konsep")},
   value=No5Text.ui(when{!v.supported->"Nicht unterstützt";v.pendingRequestId!=null->"Wird bestätigt …";v.effective->"An";else->"Aus"}),
   enabled=v.supported&&!v.locked&&v.pendingRequestId==null,selected=v.effective,control="check",key="map-layer-${v.id}",action={onEvent(LayersEvent.SetVisible(v.id,!v.effective))})}
  No5Workspace(m1("Ebenen","Layers","Lae"),m1("Seekarte bleibt feste Grundlage","Chart remains the fixed base","Seekaart bly die vaste grondslag"),rows,night,onClose,modifier.testTag("map-layers-workspace"))
 }
}
