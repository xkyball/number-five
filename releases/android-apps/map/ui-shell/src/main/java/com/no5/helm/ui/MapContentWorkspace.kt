package com.no5.helm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.no5.header.artwork.No5WorkspaceView
import com.no5.helm.chart.*
import com.no5.ui.No5Text
import com.no5.ui.No5Workspace
import kotlin.math.max

@Composable internal fun MapContentWorkspace(state:ChartObjectFilterState,dictionary:Map<String,String>,busy:Boolean,notice:String?,
 night:Boolean,onBack:()->Unit,apply:(ChartObjectFilters)->Unit,layers:@Composable ((()->Unit))->Unit){
 val pages=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
 var categoryBack by rememberSaveable{mutableStateOf("content")}
 var page by rememberSaveable{mutableStateOf("content")};var reset by rememberSaveable{mutableStateOf(false)}
 val parents=mapOf("layers" to "content","category" to "content","labels" to "content","soundings" to "content","groups" to "content","aids" to "groups","hazards" to "groups","geography" to "groups","classes" to "groups")
 fun back(){if(page=="content")onBack()else page=if(page=="category")categoryBack else parents[page]?:"content"}
 BackHandler{back()}
 val v=state.effective;val custom=v.category==S52DisplayCategory.CUSTOM
 val enabled=state.supported&&!busy
 fun row(label:String,id:String)=No5WorkspaceView.Row(label,key="m1-content-$id",action={if(id=="category")categoryBack=page;page=id})
 fun toggle(label:String,key:String,value:Boolean,change:()->ChartObjectFilters)=No5WorkspaceView.Row(label,
  value=No5Text.ui(if(busy)"Wird bestätigt …"else if(value)"An"else"Aus"),selected=value,control="check",enabled=enabled,key=key,action={apply(change())})
 val title=when(page){"layers"->m1("Ebenen","Layers","Lae");"category"->m1("Objektumfang","Object scope","Objekomvang");"labels"->m1("Beschriftung","Labels","Byskrifte");"soundings"->m1("Tiefenzahlen","Depth soundings","Dieptesyfers");"groups"->m1("Objektklassen","Object classes","Objekklasse");"aids"->m1("Seezeichen","Navigation aids","Navigasiemerke");"hazards"->m1("Gefahren/Gebiete","Hazards/areas","Gevare/gebiede");"geography"->m1("Geografie","Geography","Geografie");else->m1("Inhalt","Content","Inhoud")}
 if(page=="layers")layers{page="content"}else if(page=="classes")pages.SaveableStateProvider("classes"){MapIndividualClasses(state,dictionary,busy,notice,night,{page="groups"},{categoryBack="classes";page="category"},apply)}
 else{
  val rows=when(page){
   "category"->S52DisplayCategory.entries.map{cat->No5WorkspaceView.Row(No5Text.ui(when(cat){S52DisplayCategory.BASE->"Basis";S52DisplayCategory.STANDARD->"Standard";S52DisplayCategory.ALL->"Alle";S52DisplayCategory.CUSTOM->"Benutzerdefiniert"}),selected=v.category==cat,control="check",enabled=enabled,key="s52-category-${cat.name}",action={apply(v.copy(category=cat))})}
   "labels"->listOf(toggle(m1("Texte","Text","Teks"),"s52-text",v.text){v.copy(text=!v.text)},toggle(m1("Nur wichtige Texte","Important text only","Slegs belangrike teks"),"s52-important",v.importantTextOnly){v.copy(importantTextOnly=!v.importantTextOnly)},toggle(m1("Seezeichenbeschriftung","Navigation aid labels","Navigasiemerkbyskrifte"),"s52-aton",v.atonText){v.copy(atonText=!v.atonText)},toggle(m1("Lichtertexte","Light labels","Ligbyskrifte"),"s52-light",v.lightText){v.copy(lightText=!v.lightText)})
   "soundings"->listOf(toggle(m1("Tiefenzahlen","Depth soundings","Dieptesyfers"),"s52-soundings",v.soundings){v.copy(soundings=!v.soundings)})
   "groups"->listOf(row(m1("Seezeichen","Navigation aids","Navigasiemerke"),"aids"),row(m1("Gefahren/Gebiete","Hazards/areas","Gevare/gebiede"),"hazards"),row(m1("Geografie","Geography","Geografie"),"geography"),row(m1("Einzelklassen suchen","Search individual classes","Soek individuele klasse"),"classes"))
   "aids","hazards","geography"->{
    val keys=when(page){"aids"->listOf("Tonnen / Baken","Lichter");"hazards"->listOf("Wracks / Hindernisse","Schutz- / Sperrgebiete","Warn- / Übungsgebiete");else->listOf("Tiefenlinien","Land / Anlagen")}
    keys.map{key->val codes=objectFilterGroups.getValue(key).intersect(dictionary.keys);val shown=codes.all{it !in v.hiddenClasses}
     No5WorkspaceView.Row(No5Text.ui(key),value=if(custom)No5Text.ui(if(shown)"An"else"Aus")else m1("Benutzerdefiniert erforderlich","Custom required","Pasgemaak nodig"),selected=shown,control="check",enabled=enabled&&custom&&codes.isNotEmpty(),key="s52-group-${codes.sorted().firstOrNull()?:key}",action={apply(v.copy(hiddenClasses=if(shown)v.hiddenClasses+codes else v.hiddenClasses-codes))})}
   }
   else->listOf(row(m1("Ebenen","Layers","Lae"),"layers"),row(m1("Objektumfang","Object scope","Objekomvang"),"category"),row(m1("Beschriftung","Labels","Byskrifte"),"labels"),row(m1("Tiefenzahlen","Depth soundings","Dieptesyfers"),"soundings"),row(m1("Objektklassen","Object classes","Objekklasse"),"groups"))
  }
  val subtitle=when{!state.supported->m1("S52-Filter nicht verfügbar","S52 filters unavailable","S52-filters onbeskikbaar");page in setOf("aids","hazards","geography")&&!custom->m1("Objektumfang Benutzerdefiniert zuerst bestätigen","Confirm Custom object scope first","Bevestig eers Pasgemaakte objekomvang");else->notice.orEmpty()}
  No5Workspace(title,subtitle,rows,night,::back,Modifier.fillMaxSize(),rawSubtitle=true,
   actions=if(page=="content")listOf(No5WorkspaceView.Action(m1("Filter zurücksetzen","Reset filters","Stel filters terug"),enabled=enabled,key="s52-reset",action={reset=true}))else if(page in setOf("aids","hazards","geography")&&!custom)listOf(No5WorkspaceView.Action(m1("Objektumfang","Object scope","Objekomvang"),key="s52-require-custom",action={categoryBack=page;page="category"}))else emptyList())
 }
 if(reset)com.no5.ui.No5DetailsDialog(m1("S52-Filter zurücksetzen?","Reset S52 filters?","Stel S52-filters terug?"),night,{reset=false},
  body=m1("Nur S52-Filter. Kartenbestand und Ebenen bleiben unverändert.","S52 filters only. Chart library and layers remain unchanged.","Slegs S52-filters. Kaartversameling en lae bly onveranderd."),tag="s52-reset-dialog",
  actions=listOf(No5WorkspaceView.Action(m1("Zurücksetzen","Reset","Stel terug"),enabled=enabled,key="s52-reset-confirm",action={reset=false;apply(ChartObjectFilters())}),No5WorkspaceView.Action(m1("Bleiben","Stay","Bly"),key="s52-reset-cancel",action={reset=false})))
}
@Composable internal fun MapIndividualClasses(state:ChartObjectFilterState,dictionary:Map<String,String>,busy:Boolean,notice:String?,night:Boolean,onBack:()->Unit,onCategory:()->Unit,apply:(ChartObjectFilters)->Unit){
 var capacity by rememberSaveable{mutableIntStateOf(4)}
 var query by rememberSaveable{mutableStateOf("")};var page by rememberSaveable{mutableIntStateOf(0)}
 val rows=dictionary.filter{(k,v)->k.contains(query,true)||v.contains(query,true)||MapClassLabels.title(k,v).contains(query,true)}.toList();val count=max(1,(rows.size+capacity-1)/capacity);val current=page.coerceIn(0,count-1)
 val value=state.effective;val custom=value.category==S52DisplayCategory.CUSTOM
 val controls=buildList{
  add(m1Back(onBack));add(M1Control("no5-field-s52-search",m1("Klasse suchen · Name oder Kennung","Search class · name or code","Soek klas · naam of kode"),32f,234f,1240f,value=query,change={query=it}))
  add(M1Control("no5-row-s52-scope",m1("Umfang","Scope","Omvang")+": "+No5Text.ui(when(value.category){S52DisplayCategory.CUSTOM->"Benutzerdefiniert";S52DisplayCategory.BASE->"Basis";S52DisplayCategory.STANDARD->"Standard";else->"Alle"}),1300f,234f,660f,action=onCategory))
  rows.drop(current*capacity).take(capacity).forEachIndexed{i,(code,name)->add(M1Control("no5-row-s52-class-$code",MapClassLabels.title(code,name),32f,344f+i*104,96f,check=true,selected=code !in value.hiddenClasses,enabled=custom&&state.supported&&!busy,description="$code · ${MapClassLabels.title(code,name)}",action={apply(value.copy(hiddenClasses=if(code in value.hiddenClasses)value.hiddenClasses-code else value.hiddenClasses+code))}))}
  add(M1Control("no5-action-s52-prev",m1("Zurück","Previous","Vorige"),353f,824f,400f,enabled=current>0,action={page=current-1}));add(M1Control("no5-action-s52-next",m1("Weiter","Next","Volgende"),1256f,824f,400f,enabled=current+1<count,action={page=current+1}))
 }
 MapM1Surface(M1Scene(m1("Einzelne Objektklassen","Individual object classes","Individuele objekklasse"),controls,paint={
  rows.drop(current*capacity).take(capacity).forEachIndexed{i,(code,name)->val y=344f+i*104;text(code,150f,y+57,32f,250f,true);text(MapClassLabels.title(code,name),450f,y+57,32f,950f);text(No5Text.ui(if(code in value.hiddenClasses)"Ausgeblendet"else"Sichtbar"),1550f,y+57,27f,400f);line(32f,y+100,1960f,y+100)}
  if(rows.isEmpty())text(m1("Keine Treffer","No results","Geen treffers"),450f,405f,32f,950f)
  text("${current+1} / $count",900f,884f,28f,200f)
 },keyboard=M1Keyboard("no5-field-s52-search",rows.drop(current*capacity).take(capacity).map{(code,name)->controls.first{it.key=="no5-row-s52-class-$code"}.copy(label="$code · ${MapClassLabels.title(code,name)}",dynamicLabel=true)},controls.first{it.key=="no5-action-s52-prev"},controls.first{it.key=="no5-action-s52-next"},m1Actions(No5WorkspaceView.Action(m1("Zurück","Previous","Vorige"),key="s52-keyboard-prev",enabled=current>0,action={page=current-1}),No5WorkspaceView.Action("${current+1} / $count",key="s52-keyboard-page",enabled=false,action={}),No5WorkspaceView.Action(m1("Weiter","Next","Volgende"),key="s52-keyboard-next",enabled=current+1<count,action={page=current+1})),4,{n->if(n!=capacity){page=current*capacity/n;capacity=n}}),notice=if(!custom)m1("Erst Objektumfang Benutzerdefiniert bestätigen.","Confirm Custom object scope first.","Bevestig eers Pasgemaakte objekomvang.")else notice),night)
}
