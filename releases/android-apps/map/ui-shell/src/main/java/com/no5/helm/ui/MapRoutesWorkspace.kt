package com.no5.helm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.no5.header.artwork.No5WorkspaceView
import com.no5.helm.library.ChartSelectionExtent
import com.no5.preparation.ui.*
import com.no5.ui.No5Text
import com.no5.ui.No5Workspace
import kotlin.math.max

/** Task UI only. RoutesEvent remains the sole editor/write state machine. */
@Composable internal fun MapRoutesWorkspace(state:RoutesState,night:Boolean,onEvent:(RoutesEvent)->Unit,onBack:()->Unit,
 chartReady:Boolean,onRefresh:()->Unit,onFit:(RouteDocument)->Unit,onMapEdit:()->Unit,
 recovery:RouteDocument?=null,onDiscardRecovery:()->Unit={}){
 var listPage by rememberSaveable{mutableIntStateOf(0)}
 var capacity by rememberSaveable{mutableIntStateOf(3)}
 var query by rememberSaveable{mutableStateOf("")};var page by rememberSaveable{mutableIntStateOf(0)}
 var selected by rememberSaveable{mutableIntStateOf(0)};var section by rememberSaveable{mutableStateOf("main")}
 val d=state.draft;val shown=state.shown;val locked=state.saveRequest!=null||state.startRequest!=null||state.stopRequest!=null
 fun back(){when{section=="order"->section="point";section=="discard-recovery"->section="recovery";section!="main"->section="main";d!=null->onEvent(RoutesEvent.CancelEdit);shown!=null->onEvent(RoutesEvent.Hide);else->onBack()}}
 BackHandler{back()}
 if(d==null&&shown==null){
  if(section=="recovery"&&recovery!=null){
   No5Workspace(m1("Gesicherter Entwurf","Recovered draft","Herstelde konsep"),recovery.name,listOf(
    No5WorkspaceView.Row(m1("Entwurf fortsetzen","Continue draft","Gaan voort met konsep"),key="route-recover",action={onEvent(RoutesEvent.Recover(recovery));section="main"}),
    No5WorkspaceView.Row(m1("Entwurf verwerfen","Discard draft","Verwerp konsep"),key="route-discard-recovery",action={section="discard-recovery"})),night,::back,Modifier.fillMaxSize(),rawSubtitle=true)
   return
  }
  if(section=="discard-recovery"&&recovery!=null){
   com.no5.ui.No5DetailsDialog(m1("Entwurf verwerfen?","Discard draft?","Verwerp konsep?"),night,{section="recovery"},body=recovery.name,tag="route-recovery-discard",actions=listOf(
    No5WorkspaceView.Action(m1("Verwerfen","Discard","Verwerp"),key="route-recovery-confirm",action={onDiscardRecovery();section="main"}),
    No5WorkspaceView.Action(m1("Bleiben","Stay","Bly"),key="route-recovery-stay",action={section="recovery"})))
  }
  val routes=state.routes.filter{it.name.contains(query,true)||it.id.contains(query,true)}
  val rows=listOfNotNull(recovery?.let{No5WorkspaceView.Row(m1("Gesicherter Entwurf","Recovered draft","Herstelde konsep"),it.name,rawDetail=true,key="route-recovery",action={section="recovery"})})+routes.map{r->No5WorkspaceView.Row(r.name,No5Text.ui("%d Punkte",r.points.size),rawLabel=true,key="route-open-${r.id}",enabled=!locked,action={onEvent(RoutesEvent.Open(r.id))})}.ifEmpty{listOf(No5WorkspaceView.Row(m1("Keine Routen","No routes","Geen roetes"),enabled=false,key="route-empty"))}
  MapPagedWorkspace(m1("Routen","Routes","Roetes"),listOfNotNull(state.message,state.status.detail.takeIf{it.isNotBlank()}).joinToString(" · "),rows,night,::back,actions=listOf(No5WorkspaceView.Action(m1("Neu","New","Nuut"),enabled=!locked,key="route-new",action={onEvent(RoutesEvent.New)}),No5WorkspaceView.Action(m1("Aktualisieren","Refresh","Verfris"),enabled=!locked,key="route-refresh",action=onRefresh)),search=query,onSearch={query=it},pageIndex=listPage,onPageChange={listPage=it})
 }else if(d==null&&shown!=null){
  if(section=="info")No5Workspace(m1("Routeninformationen","Route information","Roete-inligting"),shown.name,listOf(No5WorkspaceView.Row(m1("Punkte","Points","Punte"),value=shown.points.size.toString(),key="route-info-points"),No5WorkspaceView.Row(m1("Quelle","Source","Bron"),value=shown.source.orEmpty(),rawValue=true,key="route-info-source"),No5WorkspaceView.Row(m1("Revision","Revision","Hersiening"),value=shown.revision.toString(),key="route-info-revision")),night,::back,Modifier.fillMaxSize(),rawSubtitle=true)
  else No5Workspace(m1("Gewählte Route","Selected route","Gekose roete"),shown.name,listOf(
   No5WorkspaceView.Row(m1("Anzeigen/einpassen","Show/fit","Wys/pas in"),enabled=chartReady,key="route-fit",action={onFit(shown)}),
   No5WorkspaceView.Row(m1("Bearbeiten/Kopieren","Edit/copy","Wysig/kopieer"),enabled=!locked,key="route-edit",action={onEvent(RoutesEvent.EditShown)}),
   No5WorkspaceView.Row(m1("Informationen","Information","Inligting"),key="route-info",action={section="info"}),
   No5WorkspaceView.Row(m1("Löschen","Delete","Vee uit"),enabled=!locked&&state.saveSupported&&!shown.external,key="route-delete",action={onEvent(RoutesEvent.ReviewDelete)})),night,::back,Modifier.fillMaxSize(),rawSubtitle=true)
 }else if(d!=null){
  val point=d.points.getOrNull(selected)
  when(section){
   "point"->No5Workspace(m1("Gewählter Punkt","Selected point","Gekose punt"),point?.name.orEmpty(),listOf(
    No5WorkspaceView.Row(m1("Koordinaten/Name","Coordinates/name","Koördinate/naam"),enabled=!locked&&point!=null,key="route-point-edit",action={onEvent(RoutesEvent.EditPoint(selected))}),
    No5WorkspaceView.Row(m1("Auf Karte versetzen","Move on chart","Skuif op kaart"),enabled=!locked&&point!=null&&chartReady,key="route-point-map",action={onEvent(RoutesEvent.ReplaceOnMap(selected));onMapEdit()}),
    No5WorkspaceView.Row(m1("Reihenfolge","Order","Volgorde"),enabled=!locked&&point!=null,key="route-point-order",action={section="order"}),
    No5WorkspaceView.Row(m1("Entfernen","Remove","Verwyder"),enabled=!locked&&point!=null,key="route-point-remove",action={onEvent(RoutesEvent.RemovePoint(selected));section="main"})),night,::back,Modifier.fillMaxSize(),rawSubtitle=true)
   "order"->MapPagedWorkspace(m1("Punktreihenfolge","Point order","Puntvolgorde"),"",d.points.mapIndexed{i,p->No5WorkspaceView.Row("${i+1} · ${p.name}",rawLabel=true,enabled=!locked,key="route-order-$i",action={onEvent(RoutesEvent.MovePoint(selected,i-selected));selected=i;section="point"})},night,{section="point"})
   "add"->No5Workspace(m1("Punkt ergänzen","Add point","Voeg punt by"),"",listOf(No5WorkspaceView.Row(m1("Koordinaten eingeben","Enter coordinates","Voer koördinate in"),enabled=!locked,key="route-coordinate",action={onEvent(RoutesEvent.EditPoint())}),No5WorkspaceView.Row(m1("Auf Karte setzen","Place on chart","Plaas op kaart"),enabled=!locked&&chartReady,key="route-map-add",action={onEvent(RoutesEvent.InsertBefore(d.points.size));onMapEdit()})),night,::back,Modifier.fillMaxSize())
   else->{
    val points=d.points;val count=max(1,(points.size+capacity-1)/capacity);val current=page.coerceIn(0,count-1)
    var fitted by remember(d.id){mutableStateOf(points.filter{it.valid}.map{ChartSelectionExtent(it.latitude,it.latitude,it.longitude,it.longitude)})}
    var panX by rememberSaveable(d.id){mutableFloatStateOf(0f)};var panY by rememberSaveable(d.id){mutableFloatStateOf(0f)}
    val projection=remember(fitted){MapSpatialOverview(fitted,M1Rect(32f,234f,1272f,626f))}
    val xy=points.map{p->projection.rect(ChartSelectionExtent(p.latitude,p.latitude,p.longitude,p.longitude),panX,panY)}
    val controls=buildList{
     add(m1Back(::back));add(M1Control("no5-field-route-name",m1("Routenname","Route name","Roetenaam"),1300f,234f,660f,enabled=!locked,value=d.name,displayPrefix=m1("Name: ","Name: ","Naam: "),change={onEvent(RoutesEvent.Rename(it))}))
     points.drop(current*capacity).take(capacity).forEachIndexed{i,p->val index=current*capacity+i;add(M1Control("no5-row-route-point-$index","${index+1} · ${p.name}",1300f,344f+i*104,660f,enabled=!locked,dynamicLabel=true,action={selected=index;section="point"}))}
     add(M1Control("no5-action-route-point-add",m1("Punkt ergänzen","Add point","Voeg punt by"),60f,642f,420f,enabled=!locked,action={section="add"}));add(M1Control("no5-action-route-undo",m1("Rückgängig","Undo","Ontdoen"),500f,642f,350f,enabled=!locked&&state.undoDocument!=null,action={onEvent(RoutesEvent.Undo)}))
     add(M1Control("no5-action-route-prev",m1("Zurück","Previous","Vorige"),1300f,674f,260f,enabled=current>0,action={page=current-1}));add(M1Control("no5-action-route-next",m1("Weiter","Next","Volgende"),1700f,674f,260f,enabled=current+1<count,action={page=current+1}))
     addAll(m1Actions(No5WorkspaceView.Action(m1("Zurück","Back","Terug"),key="route-close",enabled=!locked,action=::back),No5WorkspaceView.Action(m1("Einpassen","Fit","Pas in"),key="route-fit",enabled=points.isNotEmpty(),action={fitted=points.filter{it.valid}.map{ChartSelectionExtent(it.latitude,it.latitude,it.longitude,it.longitude)};panX=0f;panY=0f}),No5WorkspaceView.Action(m1("Speichern","Save","Stoor"),key="route-save",enabled=!locked&&state.dirty&&state.saveSupported&&d.valid(),action={onEvent(RoutesEvent.Save)})))
    }
    MapM1Surface(M1Scene(m1("Route bearbeiten","Edit route","Wysig roete"),controls,paint={
     grid(32f,234f,1240f,520f,m1("SCHEMATISCHE KOORDINATENANSICHT","SCHEMATIC COORDINATE VIEW","SKEMATIESE KOÖRDINAATAANSIG"))
     canvas.save();canvas.clipRect(32f,274f,1272f,754f)
     for(i in 1 until xy.size){val a=xy[i-1];val b=xy[i];if(a!=null&&b!=null)line(a.left,a.top,b.left,b.top,accent)}
     xy.forEachIndexed{i,q->if(q!=null){p.color=accent;canvas.drawCircle(q.left,q.top,10f,p);text("${i+1}",q.left+18,q.top-15,30f,120f,true)}}
     canvas.restore()
     text("${current+1} / $count",1580f,731f,27f,100f)
     text(m1("Koordinaten bleiben ohne Seekarte bearbeitbar.","Coordinates remain editable without a chart.","Koördinate bly sonder 'n seekaart bewerkbaar."),50f,793f,25f,1200f,color=muted)
    },keyboard=M1Keyboard("no5-field-route-name",controls.filter{it.key.startsWith("no5-row-route-point-")},controls.first{it.key=="no5-action-route-prev"},controls.first{it.key=="no5-action-route-next"},controls.filter{it.y==824f},3,{n->if(n!=capacity){page=current*capacity/n;capacity=n}}),notice=state.message?.let{mapRouteNotice(it)},mapBounds=M1Rect(32f,234f,1272f,754f),onMapPan={x,y->panX+=x;panY+=y},onMapTap={x,y->
     val index=xy.indices.minByOrNull{i->xy[i]?.let{kotlin.math.hypot(it.left-x,it.top-y)}?:Float.MAX_VALUE}
     if(index!=null&&xy[index]?.let{kotlin.math.hypot(it.left-x,it.top-y)<=48f}==true){selected=index;section="point"}
    }),night)
   }
  }
 }
}
