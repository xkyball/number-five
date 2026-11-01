package com.no5.helm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.no5.header.artwork.No5WorkspaceView
import com.no5.helm.library.*
import com.no5.ui.No5Text
import com.no5.ui.No5Workspace

@Composable internal fun MapLibraryWorkspace(page:String,onPage:(String)->Unit,state:ChartLibraryUiState,
 night:Boolean,measurementActive:Boolean,managementSupported:Boolean,onBack:()->Unit,
 onImport:()->Unit,onSelectImported:()->Unit,onSelect:(ChartPackage)->Unit,onManage:(ChartPackage)->Unit,onProvenance:(ChartPackage)->Unit,onCancel:()->Unit,onNotice:(String)->Unit,query:String,onQuery:(String)->Unit,listPage:Int,onListPage:(Int)->Unit){
 var cancelRequested by rememberSaveable{mutableStateOf(false)}
 fun back(){if(page=="library"||page=="packages")onBack()else onPage("library")}
 BackHandler{back()}
 when(page){
  "progress"->MapImportWorkspace(state.importProgress,state.busy,night,{onPage("library")},{cancelRequested=true},onSelectImported,state.message,state.importSourceLabel)
  "import"->No5Workspace(m1("Karten importieren","Import charts","Voer kaarte in"),m1("Bestehender Bestand bleibt erhalten","Existing library is retained","Bestaande versameling bly behoue"),listOf(
   No5WorkspaceView.Row(m1("Unterstützte Pakete","Supported packages","Ondersteunde pakkette"),No5Text.ui("NOAA-/WSV-S57 mit Basiszellen und Updates"),key="chart-import-supported"),
   No5WorkspaceView.Row(m1("Nach Import Zellen auswählen und aktivieren","Select and activate cells after import","Kies en aktiveer selle ná invoer"),key="chart-import-retained")),night,::back,Modifier.fillMaxSize(),actions=listOf(No5WorkspaceView.Action(m1("Datei wählen","Choose file","Kies lêer"),enabled=!state.busy&&!measurementActive,key="chart-package-import",action=onImport)))
  else->{
   val found=state.snapshot.packages.filter{it.label.contains(query,true)||it.id.contains(query,true)}
   val rows=found.map{p->val active=state.snapshot.active?.takeIf{it.packageId==p.id}?.cells?.size?:0
    No5WorkspaceView.Row(p.label,No5Text.ui("%d gespeichert · %d aktiv",p.cells.size,active),rawLabel=true,selected=active>0,enabled=!state.busy,key="chart-package-select-${p.id}",action={if(managementSupported)onManage(p)else onSelect(p)})
   }.ifEmpty{listOf(No5WorkspaceView.Row(if(state.snapshot.packages.isEmpty())m1("Keine Karten installiert","No charts installed","Geen kaarte geïnstalleer nie")else m1("Keine Treffer","No results","Geen treffers"),enabled=false,key="chart-library-empty"))}
   MapPagedWorkspace(m1("Kartenbestand","Chart library","Kaartversameling"),state.message?:if(state.busy)No5Text.ui(state.progress)else"",rows,night,::back,
    actions=listOf(No5WorkspaceView.Action(m1("Importieren","Import","Voer in"),enabled=!state.busy&&!measurementActive,key="chart-library-import",action={onPage("import")})),search=query,onSearch=onQuery,pageIndex=listPage,onPageChange=onListPage)
  }
 }
 if(cancelRequested&&state.busy)com.no5.ui.No5DetailsDialog(m1("Import abbrechen?","Cancel import?","Kanselleer invoer?"),night,{cancelRequested=false},
  body=m1("Der laufende Import wird bereinigt. Vorhandene Karten bleiben erhalten.","The current import will be cleaned up. Existing charts are retained.","Die huidige invoer word opgeruim. Bestaande kaarte bly behoue."),tag="chart-import-cancel-dialog",
  actions=listOf(No5WorkspaceView.Action(m1("Import abbrechen","Cancel import","Kanselleer invoer"),key="chart-import-cancel-confirm",enabled=state.importProgress?.canCancel==true,action={cancelRequested=false;onCancel()}),
   No5WorkspaceView.Action(m1("Bleiben","Stay","Bly"),key="chart-import-cancel-stay",action={cancelRequested=false})))
 LaunchedEffect(state.busy){if(!state.busy)cancelRequested=false}

}
