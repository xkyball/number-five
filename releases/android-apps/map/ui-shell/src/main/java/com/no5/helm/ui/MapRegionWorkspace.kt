package com.no5.helm.ui

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.no5.header.artwork.No5WorkspaceView
import com.no5.helm.library.*
import com.no5.ui.No5Text
import com.no5.ui.No5Workspace
import kotlin.math.max

/** M1 spatial picker. Its existing parent remains sole owner of activation/terms and durable success. */
@Composable internal fun MapRegionWorkspace(stored:ChartPackage,regions:List<ChartRegion>,selected:Set<String>,active:Set<String>,
 filter:String,onFilter:(String)->Unit,busy:Boolean,pending:Boolean,canActivate:Boolean,draftActive:Boolean,
 selectionLimit:Int,requiresTerms:Boolean,terms:Boolean,onTerms:(Boolean)->Unit,onDocument:()->Unit,
 nextStep:String,message:String?,onClose:()->Unit,onActivate:()->Unit,onDeactivate:(()->Unit)?,
 onSelection:(Set<String>)->Unit,onAdd:(Set<String>)->Unit,neighbors:Set<String>){
 var section by rememberSaveable(stored.id){mutableStateOf("regions")};var page by rememberSaveable(stored.id){mutableIntStateOf(0)}
 var focus by rememberSaveable(stored.id){mutableStateOf<String?>(null)};var hitNames by rememberSaveable(stored.id){mutableStateOf(emptyList<String>())}
 var panX by rememberSaveable(stored.id){mutableFloatStateOf(0f)};var panY by rememberSaveable(stored.id){mutableFloatStateOf(0f)}
 var termsBack by rememberSaveable(stored.id){mutableStateOf("regions")};var infoBack by rememberSaveable(stored.id){mutableStateOf("regions")}
 var confirm by rememberSaveable(stored.id){mutableStateOf(false)}
 fun back(){when(section){"regions"->onClose();"terms"->section=termsBack;"info"->section=infoBack;"filtered"->section="tools";else->section="regions"}}
 BackHandler{back()}
 val night=androidx.compose.material3.MaterialTheme.colorScheme.surface.red<.15f;val writable=!busy&&!pending
 var capacity by rememberSaveable(stored.id){mutableIntStateOf(3)}
 val found=regions.flatMap{it.cells}.distinctBy{it.name};val pageCount=max(1,(found.size+capacity-1)/capacity);val current=page.coerceIn(0,pageCount-1)
 val plot=M1Rect(32f,234f,1272f,754f)
 val projection=remember(stored){MapSpatialOverview(stored.cells.mapNotNull{it.selectionExtent},plot)}
 val rectangles=remember(stored,panX,panY){stored.cells.mapNotNull{cell->cell.selectionExtent?.let{projection.rect(it,panX,panY)}?.let{cell to it}}}
 when(section){
  "terms"->No5Workspace(m1("Nutzungsbedingungen","Terms of use","Gebruiksvoorwaardes"),stored.source,
   listOf(No5WorkspaceView.Row(stored.label,rawLabel=true,key="chart-terms-provider"),No5WorkspaceView.Row(m1("Bedingungen gelesen","Terms read","Voorwaardes gelees"),selected=terms,control="check",enabled=writable,key="chart-noaa-terms",action={onTerms(!terms)}),No5WorkspaceView.Row(m1("Originaldokument öffnen","Open original document","Maak oorspronklike dokument oop"),key="chart-noaa-document",action=onDocument)),night,::back,Modifier.fillMaxSize(),rawSubtitle=true)
  "tools","filtered"->{
   val available=stored.cells.filterNot{it.cancelled}.mapTo(linkedSetOf()){it.name};val filtered=found.filterNot{it.cancelled}.mapTo(linkedSetOf()){it.name}
   val rows=if(section=="filtered")listOf(No5WorkspaceView.Row(m1("Gefilterte auswählen","Select filtered","Kies gefiltreerde"),enabled=writable&&(selected+filtered).size<=selectionLimit,key="chart-select-filtered",action={onAdd(filtered)}),No5WorkspaceView.Row(m1("Gefilterte abwählen","Deselect filtered","Ontkies gefiltreerde"),enabled=writable,key="chart-deselect-filtered",action={onSelection(selected-filtered)}))else listOf(
    No5WorkspaceView.Row(m1("Alle","All","Almal"),enabled=writable&&available.size<=selectionLimit,key="chart-select-all",action={onAdd(available)}),
    No5WorkspaceView.Row(m1("Keine","None","Geen"),enabled=writable,key="chart-select-none",action={onSelection(emptySet())}),
    No5WorkspaceView.Row(m1("Gefilterte","Filtered","Gefiltreer"),enabled=writable&&filter.isNotBlank(),key="chart-filtered-tools",action={section="filtered"}),
    No5WorkspaceView.Row(m1("Nachbarn","Neighbours","Bure"),enabled=writable&&neighbors.isNotEmpty()&&(selected+neighbors).size<=selectionLimit,key="chart-select-neighbors",action={onAdd(neighbors)}))
   No5Workspace(m1("Auswahlwerkzeuge","Selection tools","Keusegereedskap"),m1("Gesamtes Paket / alle Suchtreffer, nicht nur diese Seite","Whole package / all search results, not just this page","Hele pakket / alle soekresultate, nie net hierdie bladsy nie"),rows,night,{section=if(section=="filtered")"tools"else"regions"},Modifier.fillMaxSize())
  }
  "hits"->MapPagedWorkspace(m1("Zelltreffer","Cell matches","Seltreffers"),"",stored.cells.filter{it.name in hitNames}.map{cell->No5WorkspaceView.Row(cell.name,rawLabel=true,key="chart-hit-${cell.name}",action={focus=cell.name;infoBack="hits";section="info"})},night,::back)
  "info"->{val cell=stored.cells.find{it.name==focus};No5Workspace(m1("Gewählte Zelle","Selected cell","Gekose sel"),cell?.name.orEmpty(),listOfNotNull(cell?.let{No5WorkspaceView.Row(it.name,No5Text.ui("Ed. %d · Update %d",it.edition,it.update),rawLabel=true,key="chart-cell-info")},No5WorkspaceView.Row(m1("Ausgewählt","Selected","Gekies"),selected=cell?.name in selected,control="check",enabled=writable&&cell!=null&&!cell.cancelled&&(cell.name in selected||selected.size<selectionLimit),key="chart-cell-info-select",action={cell?.let{if(it.name in selected)onSelection(selected-it.name)else onAdd(setOf(it.name))}}),No5WorkspaceView.Row(m1("Herkunft und Bedingungen","Source and terms","Bron en voorwaardes"),key="chart-cell-terms",action={termsBack="info";section="terms"})),night,::back,Modifier.fillMaxSize(),rawSubtitle=true)}
  else->{
   val shown=found.drop(current*capacity).take(capacity)
   val controls=buildList{
    add(m1Back(::back));add(M1Control("no5-field-chart-region-filter",m1("Zelle / Bereich suchen","Search cell / area","Soek sel / gebied"),1300f,234f,660f,value=filter,change=onFilter))
    shown.forEachIndexed{i,cell->val y=344f+i*104
     add(M1Control("no5-row-chart-cell-${cell.name}",cell.name,1300f,y,96f,check=true,active=cell.name in active,dynamicLabel=true,selected=cell.name in selected,enabled=writable&&!cell.cancelled&&(cell.name in selected||selected.size<selectionLimit),description=cell.name+" · "+No5Text.ui(if(cell.name in selected)"Gewählt"else"Nicht gewählt"),action={if(cell.name in selected)onSelection(selected-cell.name)else onAdd(setOf(cell.name))}))
     add(M1Control("no5-row-chart-cell-info-${cell.name}","",1410f,y,550f,description=cell.name,plain=true,action={focus=cell.name;infoBack="regions";section="info"}))
    }
    add(M1Control("no5-action-chart-prev",m1("Zurück","Previous","Vorige"),1300f,674f,260f,enabled=current>0,action={page=current-1}));add(M1Control("no5-action-chart-next",m1("Weiter","Next","Volgende"),1700f,674f,260f,enabled=current+1<pageCount,action={page=current+1}))
    addAll(m1Actions(No5WorkspaceView.Action(m1("Zurück","Back","Terug"),enabled=writable,key="chart-region-close",action=onClose),No5WorkspaceView.Action(m1("Auswahlwerkzeuge","Selection tools","Keusegereedskap"),enabled=writable,key="chart-region-tools",action={section="tools"}),No5WorkspaceView.Action(m1("Aktivieren","Activate","Aktiveer"),enabled=writable&&!draftActive&&selected.isNotEmpty(),key="chart-package-activate",action={if(requiresTerms&&!terms){termsBack="regions";section="terms"}else confirm=true})))
   }
   MapM1Surface(M1Scene(m1("Bereiche und Zellen","Areas and cells","Gebiede en selle"),controls,paint={
    grid(32f,234f,1240f,520f,m1("SCHEMATISCHE KOORDINATENANSICHT","SCHEMATIC COORDINATE VIEW","SKEMATIESE KOÖRDINAATAANSIG"))
    canvas.save();canvas.clipRect(32f,274f,1272f,754f)
    rectangles.forEach{(cell,r)->rect(r.left,r.top,r.right-r.left,r.bottom-r.top,if(cell.name in active||cell.name==focus)accent else muted,true)}
    val labels=mutableListOf<M1Rect>()
    rectangles.sortedByDescending{(cell,_)->when{cell.name==focus->3;cell.name in selected->2;cell.name in active->1;else->0}}.forEach{(cell,r)->
     val stateLabel=if(cell.name in selected)m1("Entwurf gewählt","Draft selected","Konsep gekies")else if(cell.name in active)m1("Aktiv","Active","Aktief")else m1("Gespeichert","Stored","Gestoor")
     p.typeface=regular;p.textSize=24f*font
     val stateFits=p.measureText(stateLabel)<=r.right-r.left-32&&r.bottom-r.top>=100
     val label=M1Rect(r.left+16,r.top+5,r.right-16,r.top+if(stateFits)100 else 56)
     if(label.right-label.left>=96&&r.bottom-r.top>=56&&labels.none{it.left<label.right&&it.right>label.left&&it.top<label.bottom&&it.bottom>label.top}){
      labels+=label;text(cell.name,r.left+16,r.top+45,30f,r.right-r.left-32,true,dynamic=true)
      if(stateFits)text(stateLabel,r.left+16,r.top+91,24f,r.right-r.left-32)
     }
    };canvas.restore()
    text(m1("Zellenausdehnungen ≠ exakte Abdeckung","Cell extents ≠ exact coverage","Selomvange ≠ presiese dekking"),50f,793f,25f,1200f,color=muted)
    shown.forEachIndexed{i,cell->val y=344f+i*104;text(cell.name,1410f,y+47,30f,510f,true,dynamic=true);text(if(cell.cancelled)No5Text.ui("Abgekündigt")else if(cell.name in active)m1("Aktiv","Active","Aktief")else if(cell.name in selected)m1("Entwurf gewählt","Draft selected","Konsep gekies")else m1("Gespeichert","Stored","Gestoor"),1410f,y+84,24f,510f);line(1300f,y+100,1960f,y+100)}
    text("${current+1} / $pageCount",1580f,731f,27f,100f)
    if(shown.isEmpty())text(m1("Keine Treffer","No results","Geen treffers"),1320f,405f,32f,620f)
   },keyboard=M1Keyboard("no5-field-chart-region-filter",shown.map{cell->
    controls.first{it.key=="no5-row-chart-cell-${cell.name}"}.copy(label=cell.name+" · "+if(cell.name in active)m1("Aktiv","Active","Aktief")else if(cell.name in selected)m1("Entwurf gewählt","Draft selected","Konsep gekies")else m1("Gespeichert","Stored","Gestoor"))
   },controls.first{it.key=="no5-action-chart-prev"},controls.first{it.key=="no5-action-chart-next"},controls.filter{it.y==824f},3,{n->if(n!=capacity){page=current*capacity/n;capacity=n}}),notice=No5Text.ui(message?:nextStep).takeIf{it.isNotBlank()},mapBounds=plot,onMapTap={x,y->
    val hits=rectangles.filter{it.second.contains(x,y)}.map{it.first.name};if(hits.size==1){focus=hits.first();infoBack="regions";section="info"}else if(hits.size>1){hitNames=hits;section="hits"}
   },onMapPan={x,y->panX+=x;panY+=y}),night)
  }
 }
 if(confirm)com.no5.ui.No5DetailsDialog(m1("Auswahl aktivieren?","Activate selection?","Aktiveer keuse?"),night,{confirm=false},body=m1("Die gewählten Zellen werden erst nach Bestätigung aktiv.","Selected cells become active only after confirmation.","Gekose selle word slegs ná bevestiging aktief."),tag="chart-activation-confirm",actions=listOf(No5WorkspaceView.Action(m1("Aktivieren","Activate","Aktiveer"),enabled=canActivate&&writable,key="chart-activate-confirm",action={confirm=false;onActivate()}),No5WorkspaceView.Action(m1("Bleiben","Stay","Bly"),key="chart-activate-stay",action={confirm=false})))
}
