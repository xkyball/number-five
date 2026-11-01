package com.no5.helm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.no5.header.artwork.No5WorkspaceView
import com.no5.preparation.ui.*
import com.no5.ui.No5Text
import com.no5.ui.No5Workspace

@Composable internal fun MapAisWorkspace(state:AisState,onEvent:(AisEvent)->Unit,night:Boolean,onBack:()->Unit){
 var listPage by rememberSaveable{mutableIntStateOf(0)}
 var filters by rememberSaveable{mutableStateOf(false)};var facts by rememberSaveable{mutableStateOf(false)}
 val target=selectedAisTarget(state)?.takeIf{state.detailsExpanded}
 fun back(){if(facts)facts=false else if(filters)filters=false else if(target!=null)onEvent(AisEvent.ShowList)else onBack()}
 BackHandler{back()}
 if(filters)No5Workspace(m1("AIS-Filter","AIS filters","AIS-filters"),"",AisFilter.entries.map{f->No5WorkspaceView.Row(No5Text.ui(f.label),selected=state.filter==f,control="check",key="ais-filter-$f",action={onEvent(AisEvent.SetFilter(f));filters=false})},night,::back,Modifier.fillMaxSize())
 else if(target!=null){
  val position=target.position;val speed=aisSpeedValue(target.speedKnots,state.speedUnit);val age=aisDisplayedAgeSeconds(state,target)
  if(facts){
   fun number(v:Double?,unit:String)=v?.takeIf{it.isFinite()}?.let{String.format(No5Text.locale(com.no5.ui.UiLanguage.current()),"%.1f %s",it,unit)}?:No5Text.ui("Unbekannt")
   val distanceUnit=if(state.distanceUnit==DistanceUnit.NAUTICAL_MILES)m1("sm","nm","nm")else"km"
   val freshness=No5Text.ui(when(target.freshness){AisFreshness.CURRENT->"Aktuell";AisFreshness.STALE->"Veraltet";AisFreshness.LOST->"Verloren";else->"Unbekannt"})
   val quality=No5Text.ui(when(target.quality){AisQuality.VERIFIED->"Verifiziert";AisQuality.DEGRADED->"Eingeschränkt";else->"Unbekannt"})
   val text=listOf("MMSI ${target.mmsi}",m1("Kurs über Grund","Course over ground","Koers oor grond")+": "+number(target.courseDegrees,"°"),
    m1("Entfernung","Range","Afstand")+": "+number(aisDistanceValue(target.rangeNauticalMiles,state.distanceUnit),distanceUnit),
    "CPA: "+number(aisDistanceValue(target.cpaNauticalMiles,state.distanceUnit),distanceUnit),"TCPA: "+number(target.tcpaMinutes,"min"),
    No5Text.ui("CPA/TCPA unbekannt bedeutet keine bestätigte Entwarnung."),freshness+" · "+quality,
    No5Text.ui(when(target.approaching){true->"Annäherung gemeldet";false->"Keine Annäherung gemeldet";else->"Risiko unbekannt"}),
    No5Text.ui("Messzeit (UTC): %s",target.measurementEpochMillis?.let{java.time.Instant.ofEpochMilli(it).toString()}?:No5Text.ui("Unbekannt")),
    No5Text.ui("Empfang (UTC): %s",target.receptionEpochMillis?.let{java.time.Instant.ofEpochMilli(it).toString()}?:No5Text.ui("Unbekannt"))).joinToString("\n")
   com.no5.ui.No5DetailsDialog(m1("Fahrt- und Dateninformationen","Navigation and data information","Vaar- en data-inligting"),night,{facts=false},body=text,tag="ais-target-facts")
  }
  No5Workspace(m1("AIS-Zieldetails","AIS target details","AIS-teikenbesonderhede"),target.name?:target.mmsi,listOf(
   No5WorkspaceView.Row(m1("Position","Position","Posisie"),value=position?.let{"${it.latitudeDegrees}° / ${it.longitudeDegrees}°"}?:No5Text.ui("Unbekannt"),rawValue=true,key="ais-target-position"),
   No5WorkspaceView.Row(m1("Geschwindigkeit","Speed","Spoed"),value=speed?.let{String.format(No5Text.locale(com.no5.ui.UiLanguage.current()),"%.1f %s",it,if(state.speedUnit==SpeedUnit.KNOTS)"kn"else"km/h")}?:No5Text.ui("Unbekannt"),rawValue=true,key="ais-target-speed"),
   No5WorkspaceView.Row(m1("Fahrt und Datenstand","Navigation and data age","Vaart en dataouderdom"),value=age?.let{"$it s"}?:No5Text.ui("Unbekannt"),rawValue=true,key="ais-target-age",action={facts=true}),
   No5WorkspaceView.Row(m1("Beobachten","Watch","Volg teiken"),selected=target.watched,control="check",key="ais-target-watch",action={onEvent(AisEvent.ToggleWatch(target.mmsi))}),
   No5WorkspaceView.Row(m1("Auf Karte","On chart","Op kaart"),enabled=position!=null,key="ais-target-map",action={position?.let{onEvent(AisEvent.ShowOnMap(target.mmsi,it))}})),night,::back,Modifier.fillMaxSize(),rawSubtitle=true)
 }else MapPagedWorkspace(m1("AIS-Ziele","AIS targets","AIS-teikens"),No5Text.ui(state.status.detail),visibleAisTargets(state).map{t->No5WorkspaceView.Row(t.name?:t.mmsi,t.mmsi,rawLabel=true,rawDetail=true,key="ais-target-${t.mmsi}",action={onEvent(AisEvent.Select(t.mmsi))})}.ifEmpty{listOf(No5WorkspaceView.Row(m1("Keine AIS-Ziele","No AIS targets","Geen AIS-teikens"),enabled=false,key="ais-empty"))},night,::back,actions=listOf(No5WorkspaceView.Action(m1("Filter","Filters","Filters"),key="ais-filters",action={filters=true})),search=state.query,onSearch={onEvent(AisEvent.Search(it))},pageIndex=listPage,onPageChange={listPage=it})
}
