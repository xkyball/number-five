package com.no5.helm.ui

import com.no5.header.artwork.No5WorkspaceView
import com.no5.ui.No5Text
import com.no5.ui.No5ActionFeedback
import android.net.Uri
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.no5.helm.library.*
import com.no5.helm.chart.ChartResidencyStatus
import com.no5.preparation.ui.No5CompactAction
import com.no5.preparation.ui.No5KnobSafeDialog
import com.no5.preparation.ui.No5Tab
import com.no5.preparation.ui.No5UnsavedChangesDialog
import kotlinx.coroutines.flow.StateFlow

/** UI observes durable package state; the Activity owns copying, native validation and activation. */
data class ChartLibraryUiState(val snapshot: ChartLibrarySnapshot = ChartLibrarySnapshot(), val busy: Boolean = false,
    val progress: String = "", val message: String? = null, val activationRevision: Long = 0,
    val importProgress: ImportProgress? = null, val residency: ChartResidencyStatus? = null,
    val residentMessage: String? = null, val importSourceLabel: String? = null)
interface ChartLibraryActions {
    val state: StateFlow<ChartLibraryUiState>
    val supportsResidentSelection: Boolean get() = false
    val supportsLibraryManagement: Boolean get() = false
    suspend fun readObjectText(reference: com.no5.helm.chart.ChartObjectReference, cellName: String, name: String): com.no5.helm.chart.ChartObjectText =
        com.no5.helm.chart.ChartObjectText(name)
    fun importPackage(uri: Uri)
    fun activate(selection: ChartSelection)
    fun cancelImport()
    fun deactivate(selection: ChartSelection) = Unit
    fun deletePackage(packageId: String) = Unit
    fun updatePackage(packageId: String, uri: Uri) = Unit
}
@Composable internal fun ChartLibraryPanel(actions: ChartLibraryActions?, measurementActive: Boolean, workspace:Boolean=false,onBack:()->Unit={}) {
    if (actions == null) { Text(No5Text.text(LocalContext.current, "Kartenverwaltung nicht verfügbar.")); return }
    val state by actions.state.collectAsStateWithLifecycle()
    var libraryQuery by rememberSaveable{mutableStateOf("")};var libraryListPage by rememberSaveable{mutableIntStateOf(0)}
    var libraryPage by rememberSaveable { mutableStateOf("library") }
    var provenanceId by rememberSaveable { mutableStateOf<String?>(null) }
    var noticeDetails by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionReturn by rememberSaveable{mutableStateOf<String?>(null)}
    var packageId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedNames by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var draftPackageId by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    var managedPackageId by rememberSaveable { mutableStateOf<String?>(null) }
    var managedPackageLabel by rememberSaveable { mutableStateOf("") }
    var importSelectionTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var updateTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionCloseRequest by remember { mutableStateOf<(() -> Unit)?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null){
            importSelectionTarget=null
            val before=actions.state.value.importProgress
            actions.importPackage(uri)
            // The existing owner publishes acceptance synchronously. A refused stale picker
            // result must not show a previous import as the newly requested operation.
            val after=actions.state.value
            if(workspace&&after.importProgress!=before&&after.importProgress!=null)libraryPage="progress"
        }
    }
    val updatePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = updateTarget
        updateTarget = null
        if (uri != null && target != null) {
            val before=actions.state.value.importProgress
            actions.updatePackage(target, uri)
            val after=actions.state.value
            if(workspace&&after.importProgress!=before&&after.importProgress!=null){importSelectionTarget=target;managedPackageId=null;libraryPage="progress"}
        }
    }
    val active = state.snapshot.active
    val selectionLimit = if (actions.supportsResidentSelection) Int.MAX_VALUE else 100
    val activeCells = remember(state.snapshot) {
        state.snapshot.packages.find { it.id == active?.packageId }?.cells.orEmpty().filter { it.name in active?.cells.orEmpty() }
    }
    if(workspace&&packageId==null&&managedPackageId==null){
        MapLibraryWorkspace(libraryPage,{libraryPage=it},state,
            night=MaterialTheme.colorScheme.surface.red<.15f,measurementActive=measurementActive,
            managementSupported=actions.supportsLibraryManagement,onBack=onBack,
            onImport={picker.launch(chartPackageMimeTypes)},onCancel=actions::cancelImport,
            onSelectImported={
                val stored=state.snapshot.packages.find{it.id==importSelectionTarget}?:state.snapshot.packages.singleOrNull()
                if(stored==null)libraryPage="library"else{
                    selectionReturn=stored.id;packageId=stored.id;managedPackageLabel=stored.label
                    if(draftPackageId!=stored.id){draftPackageId=stored.id;filter="";selectedNames=if(active?.packageId==stored.id)active.cells else stored.cells.filterNot{it.cancelled}.takeIf{it.size<=selectionLimit}?.map{it.name}.orEmpty()}
                }
            },
            onSelect={stored->
                packageId=stored.id
                if(draftPackageId!=stored.id){draftPackageId=stored.id;filter=""
                    selectedNames=if(active?.packageId==stored.id)active.cells else
                        stored.cells.filterNot{it.cancelled}.takeIf{it.size<=selectionLimit}?.map{it.name}.orEmpty()
                }
            },onManage={stored->managedPackageId=stored.id;managedPackageLabel=stored.label},
            onProvenance={provenanceId=it.id},onNotice={noticeDetails=it},query=libraryQuery,onQuery={libraryQuery=it},listPage=libraryListPage,onListPage={libraryListPage=it})
    }else if(!workspace){
    Text(No5Text.text(LocalContext.current, "Kartenbestand"), style = MaterialTheme.typography.titleLarge)
    Text(No5Text.text(LocalContext.current, "Gespeicherte Pakete bleiben offline verfügbar. Die Auswahl bestimmt, welche Zellen bei Bedarf geladen werden dürfen."), style = MaterialTheme.typography.bodyMedium)
    state.importProgress?.let { ChartImportProgress(it, state.busy, actions::cancelImport) }
    if (state.busy && state.importProgress == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.progress) }
    state.message?.let { Text(it) }
    state.snapshot.issues.forEach { Text(it) }
    if (measurementActive) Text(No5Text.text(LocalContext.current, "Rendereraufnahme zuerst beenden. Import und Aktivierung sind gesperrt."))
    if (state.snapshot.packages.isEmpty()) Text(No5Text.text(LocalContext.current, "Keine Karten installiert. Ein NOAA-/WSV-S57-ZIP, einen No5-Pi-Export oder das separate Boston-Paket importieren."), Modifier.testTag("chart-library-empty"))
    if (actions.supportsResidentSelection && active != null) {
        Text(chartResidentStatusText(active.cells.size, state.residency), Modifier.testTag("chart-residency-status"))
        state.residentMessage?.let { Text(it) }
    }
    state.snapshot.packages.forEach { stored ->
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stored.label, style = MaterialTheme.typography.titleMedium)
                Text(No5Text.text(LocalContext.current, "%d Zellen · %s", stored.cells.size, if (active?.packageId == stored.id) No5Text.text(LocalContext.current, "%d aktiv", active.cells.size) else No5Text.text(LocalContext.current, "gespeichert")), style = MaterialTheme.typography.bodySmall)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                No5CompactAction(No5Text.text(LocalContext.current, "Zellen auswählen"), {
                    packageId = stored.id
                    if (draftPackageId != stored.id) {
                        draftPackageId = stored.id; filter = ""
                        selectedNames = if (active?.packageId == stored.id) active.cells else
                            stored.cells.filterNot { it.cancelled }.takeIf { it.size <= selectionLimit }?.map { it.name }.orEmpty()
                    }
                }, Modifier.testTag("chart-package-select-${stored.id}"), enabled = !state.busy)
                if (actions.supportsLibraryManagement) No5CompactAction(No5Text.text(LocalContext.current, "Verwalten"), {
                    managedPackageId = stored.id; managedPackageLabel = stored.label
                }, Modifier.testTag("chart-package-manage-${stored.id}"), enabled = !state.busy)
            }
        }
    }
    HorizontalDivider()
    Text(No5Text.text(LocalContext.current, "Paket hinzufügen"), style = MaterialTheme.typography.titleMedium)
    Text(No5Text.text(LocalContext.current, "NOAA-/WSV-S57-ZIP mit Basiszellen und Updates oder ein No5-Pi-Kartenexport. Nach dem Import die gewünschten Karten auswählen und aktivieren."), style = MaterialTheme.typography.bodySmall)
    No5CompactAction(No5Text.text(LocalContext.current, "Kartenpaket importieren"), { picker.launch(chartPackageMimeTypes) },
        Modifier.testTag("chart-package-import"), enabled = !state.busy && !measurementActive)
    Text(No5Text.text(LocalContext.current, if (actions.supportsLibraryManagement)
        "Updates werden unter Verwalten manuell importiert und geprüft. Bestätigte Abkündigungen werden ausgeschlossen und bereinigt. Keine automatische Aktualitätsprüfung."
        else "Updates werden manuell importiert. Keine automatische Aktualitätsprüfung. Abgekündigte Zellen sind nicht aktivierbar."),
        style = MaterialTheme.typography.bodySmall)
    }
    state.snapshot.packages.find { it.id == packageId }?.let { stored ->
        val selection:@Composable ()->Unit = {
            // Dialog composition inherits the current shell theme.
            ChartPackageRegionPicker(stored, selectedNames.toSet(), filter, { filter = it }, { selectedNames = it.sorted() },
                active, activeCells, state.busy, state.progress, state.message, measurementActive,
                selectionLimit = selectionLimit, residency = state.residency, activationRevision = state.activationRevision, workspace=workspace,
                registerCloseRequest = { selectionCloseRequest = it },
                selectionReader = { selectedNames.toSet() },
                close = { packageId = null;selectionReturn?.let{managedPackageId=it};selectionReturn=null }, activate = { actions.activate(ChartSelection(stored.id, selectedNames.sorted())) },
                deactivate = if (actions.supportsLibraryManagement) ({
                    val activeNames = active?.takeIf { it.packageId == stored.id }?.cells.orEmpty()
                    actions.deactivate(ChartSelection(stored.id, selectedNames.filter { it in activeNames }.sorted()))
                }) else null)
        }
        if(workspace)selection() else No5KnobSafeDialog(onDismissRequest={selectionCloseRequest?.invoke()},content=selection)
    }
    state.snapshot.packages.find{it.id==provenanceId}?.let{stored->
        com.no5.ui.No5DetailsDialog("Herkunft und Lizenzen",MaterialTheme.colorScheme.surface.red<.15f,{provenanceId=null},
            body=listOf(stored.label,stored.source,No5Text.ui("%d Zellen · %s",stored.cells.size,stored.id)).joinToString("\n"),tag="chart-provenance")
    }
    noticeDetails?.let{observed->
        com.no5.ui.No5DetailsDialog("Status",MaterialTheme.colorScheme.surface.red<.15f,{noticeDetails=null},
            body=No5Text.ui(observed),tag="chart-library-notice")
    }
    managedPackageId?.let { id ->
        val management:@Composable ()->Unit = {
            ChartPackageManagement(id, managedPackageLabel, state.snapshot.packages.find { it.id == id }, state,
                measurementActive, close = { managedPackageId = null },
                deactivate = { active?.takeIf { it.packageId == id }?.let(actions::deactivate) },
                update = { updateTarget = id; updatePicker.launch(chartPackageMimeTypes) },
                delete = { actions.deletePackage(id) },workspace=workspace,
                selectCells={stored->selectionReturn=stored.id;managedPackageId=null;packageId=stored.id
                    if(draftPackageId!=stored.id){draftPackageId=stored.id;filter="";selectedNames=if(active?.packageId==stored.id)active.cells else stored.cells.filterNot{it.cancelled}.takeIf{it.size<=selectionLimit}?.map{it.name}.orEmpty()}},
                provenance={provenanceId=id})
        }
        if(workspace)management()else No5KnobSafeDialog(onDismissRequest={managedPackageId=null},content=management)
    }

}

private val chartPackageMimeTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

@Composable private fun ChartPackageManagement(packageId: String, label: String, stored: ChartPackage?,
    state: ChartLibraryUiState, measurementActive: Boolean, close: () -> Unit, deactivate: () -> Unit,
    update: () -> Unit, delete: () -> Unit,workspace:Boolean=false,selectCells:(ChartPackage)->Unit={},provenance:()->Unit={}) {
    var confirmDelete by rememberSaveable(packageId) { mutableStateOf(false) }
    var confirmDeactivate by rememberSaveable(packageId){mutableStateOf(false)}
    val active = state.snapshot.active?.takeIf { it.packageId == packageId }
    val deleteImpact = when {
        active == null -> ""
        active.cells.size == 1 -> No5Text.ui("Die aktive Zelle dieses Pakets wird dabei deaktiviert.") + " "
        else -> No5Text.ui("Die %d aktiven Zellen dieses Pakets werden dabei deaktiviert.", active.cells.size) + " "
    }
    val writable = stored != null && !state.busy && !measurementActive
    val scroll = rememberScrollState()
    val nextStep = when {
        state.busy -> state.progress.ifBlank { "Kartenbestand wird vorbereitet …" }
        measurementActive -> "Rendereraufnahme beenden, dann Kartenbestand ändern."
        stored == null -> "Diesen Paketstand gibt es nicht mehr. Nach dem Schließen den aktuellen Bestand ansehen."
        confirmDelete -> "Paket prüfen, dann Löschen bestätigen oder abbrechen."
        else -> "Aktion wählen. Deaktivieren behält die Dateien."
    }
    if(workspace){
        val night=MaterialTheme.colorScheme.surface.red<.15f
        androidx.activity.compose.BackHandler { if(confirmDelete&&!state.busy)confirmDelete=false else close() }
        val rows=listOf(
            No5WorkspaceView.Row(m1("Bereiche/Zellen auswählen","Select areas/cells","Kies gebiede/selle"),enabled=writable,key="chart-package-cells",action={stored?.let(selectCells)}),
            No5WorkspaceView.Row(m1("Aktualisieren","Update","Werk by"),enabled=writable,key="chart-package-update",action=update),
            No5WorkspaceView.Row(m1("Herkunft/Bedingungen","Source/terms","Bron/voorwaardes"),key="chart-package-provenance",action=provenance),
            No5WorkspaceView.Row(m1("Löschen","Delete","Vee uit"),enabled=writable,key="chart-package-delete",action={confirmDelete=true}),
            No5WorkspaceView.Row(m1("Deaktivieren","Deactivate","Deaktiveer"),enabled=writable&&active!=null,key="chart-package-deactivate",action={confirmDeactivate=true}))
        com.no5.ui.No5Workspace(m1("Gewähltes Paket","Selected package","Gekose pakket"),label+" · "+if(stored==null)No5Text.ui("Nicht mehr gespeichert")else No5Text.ui("%d gespeichert · %d aktiv",stored.cells.size,active?.cells?.size?:0),
            rows,night,close,Modifier.fillMaxSize(),rawSubtitle=true)
        if(confirmDeactivate)com.no5.ui.No5DetailsDialog(m1("Paket deaktivieren?","Deactivate package?","Deaktiveer pakket?"),night,{confirmDeactivate=false},body=No5Text.ui("Dateien bleiben gespeichert"),tag="chart-deactivate-dialog",actions=listOf(
            No5WorkspaceView.Action(m1("Deaktivieren","Deactivate","Deaktiveer"),enabled=writable&&active!=null,key="chart-deactivate-confirm",action={confirmDeactivate=false;deactivate()}),
            No5WorkspaceView.Action(m1("Bleiben","Stay","Bly"),key="chart-deactivate-stay",action={confirmDeactivate=false})))
        if(confirmDelete)com.no5.ui.No5DetailsDialog("Paket wirklich löschen?",night,{if(!state.busy)confirmDelete=false},
            body=No5Text.ui("„%s“ wird aus dem gespeicherten Kartenbestand entfernt. %sAndere Pakete bleiben erhalten.",label,deleteImpact),
            actions=listOf(
                com.no5.header.artwork.No5WorkspaceView.Action("Zurück",enabled=!state.busy,key="chart-package-delete-cancel",action={confirmDelete=false}),
                com.no5.header.artwork.No5WorkspaceView.Action("Löschen bestätigen",enabled=writable,key="chart-package-delete-confirm",action={confirmDelete=false;delete()})),tag="chart-package-delete-dialog")
        return
    }
    Surface(Modifier.fillMaxSize().testTag("chart-management-workspace"), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.fillMaxWidth().testTag("chart-management-fixed-status"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(No5Text.text(LocalContext.current, "Kartenbestand verwalten"), style = MaterialTheme.typography.titleLarge)
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (stored == null) No5Text.text(LocalContext.current, "Nicht mehr gespeichert") else
                    No5Text.text(LocalContext.current, "%d gespeichert · %d aktiv", stored.cells.size, active?.cells?.size ?: 0),
                    Modifier.testTag("chart-management-status"), style = MaterialTheme.typography.bodyMedium)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(No5Text.text(LocalContext.current, nextStep), Modifier.testTag("chart-management-next-step"), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                state.message?.let { Text(it, Modifier.testTag("chart-management-result"), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
            HorizontalDivider()
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag("chart-management-scroll-content"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (confirmDelete) {
                    Text(No5Text.text(LocalContext.current, "Paket löschen?"), style = MaterialTheme.typography.titleMedium)
                    Text(No5Text.text(LocalContext.current, "„%s“ wird aus dem gespeicherten Kartenbestand entfernt. %sAndere Pakete bleiben erhalten.", label, deleteImpact),
                        Modifier.testTag("chart-package-delete-summary"), style = MaterialTheme.typography.bodyMedium)
                    Text(No5Text.text(LocalContext.current, "Gelöschte Karten stehen offline nicht mehr zur Verfügung. Zum erneuten Verwenden das Paket wieder importieren."), style = MaterialTheme.typography.bodySmall)
                } else if (stored != null) {
                    Text(No5Text.text(LocalContext.current, "Deaktivieren"), style = MaterialTheme.typography.titleMedium)
                    Text(No5Text.text(LocalContext.current, "Entfernt die aktive Freigabe dieses Pakets. Gespeicherte Karten bleiben verfügbar und können erneut ausgewählt werden."), style = MaterialTheme.typography.bodyMedium)
                    Text(No5Text.text(LocalContext.current, "Aktualisieren"), style = MaterialTheme.typography.titleMedium)
                    Text(No5Text.text(LocalContext.current, "Ein neues ZIP des Kartenherausgebers auswählen. Der Ersatz wird vollständig geprüft; unveränderte Zellen und die bisherige Auswahl bleiben erhalten. Neue Zellen werden nicht automatisch aktiviert."), style = MaterialTheme.typography.bodyMedium)
                    Text(No5Text.text(LocalContext.current, "Paket löschen"), style = MaterialTheme.typography.titleMedium)
                    Text(No5Text.text(LocalContext.current, "Entfernt dieses gespeicherte Paket nach Bestätigung. Für das Entfernen einzelner Zellen aus der aktiven Karte die Bereichsauswahl öffnen und „Gewählte deaktivieren“ verwenden."), style = MaterialTheme.typography.bodyMedium)
                    Text(No5Text.text(LocalContext.current, "Es findet keine Online-Aktualitätsprüfung statt. Alter, fehlende Koordinaten oder abgebrochene Ladejobs sind keine Abkündigung."), style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
            FlowRow(Modifier.fillMaxWidth().testTag("chart-management-fixed-actions"),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                No5CompactAction(No5Text.text(LocalContext.current, "Schließen"), close, Modifier.testTag("chart-management-close"))
                if (confirmDelete) {
                    No5CompactAction(No5Text.text(LocalContext.current, "Abbrechen"), { confirmDelete = false }, Modifier.testTag("chart-package-delete-cancel"), enabled = !state.busy)
                    No5CompactAction(No5Text.text(LocalContext.current, "Löschen bestätigen"), { confirmDelete = false; delete() },
                        Modifier.testTag("chart-package-delete-confirm"), enabled = writable)
                } else {
                    No5CompactAction(No5Text.text(LocalContext.current, "Deaktivieren"), deactivate, Modifier.testTag("chart-package-deactivate"), enabled = writable && active != null)
                    No5CompactAction(No5Text.text(LocalContext.current, "Aktualisieren …"), update, Modifier.testTag("chart-package-update"), enabled = writable)
                    No5CompactAction(No5Text.text(LocalContext.current, "Paket löschen …"), { confirmDelete = true }, Modifier.testTag("chart-package-delete"), enabled = writable)
                }
            }
        }
    }
}

@Composable private fun ChartPackageRegionPicker(stored: ChartPackage, selected: Set<String>, filter: String,
    onFilter: (String) -> Unit, onSelection: (Set<String>) -> Unit, active: ChartSelection?, activeCells: List<ChartCell>,
    busy: Boolean, progress: String, message: String?, measurementActive: Boolean,
    selectionLimit: Int, residency: ChartResidencyStatus?, close: () -> Unit, activate: () -> Unit,
    deactivate: (() -> Unit)?, activationRevision: Long = 0,
    registerCloseRequest: (((() -> Unit)?) -> Unit) = {}, selectionReader: () -> Set<String> = { selected },workspace:Boolean=false) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val termsPreferences = remember(context) { context.getSharedPreferences("chart-package-terms-v1", Context.MODE_PRIVATE) }
    val termsKey = "noaa.${stored.id}"
    var noaaTerms by rememberSaveable(stored.id) { mutableStateOf(termsPreferences.getBoolean(termsKey, false)) }
    // Inspect the validated package's actual provider; a filename cannot identify NOAA.
    val requiresNoaaTerms = stored.piCache == null && stored.cells.any { it.name in selected && it.provider == S57Profiles.NOAA }
    var zonePage by rememberSaveable(stored.id) { mutableIntStateOf(0) }
    var openZone by rememberSaveable(stored.id) { mutableStateOf<String?>(null) }
    var regionScope by rememberSaveable(stored.id) { mutableStateOf("ALL") }
    var notice by rememberSaveable(stored.id) { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val selectable = remember(stored) { stored.cells.filterNot { it.cancelled }.mapTo(linkedSetOf()) { it.name } }
    val filtered = remember(stored, filter) { stored.cells.filter { it.name.contains(filter.trim(), ignoreCase = true) } }
    val regions = remember(filtered) { chartRegions(filtered) }
    val filteredAvailable = remember(filtered) { filtered.filterNot { it.cancelled }.mapTo(linkedSetOf()) { it.name } }
    val seeds = remember(stored, selected, activeCells) { stored.cells.filter { it.name in selected }.ifEmpty { activeCells } }
    val neighborNames = remember(stored, seeds) { chartNeighborNames(stored.cells, seeds) - selected }
    val visibleRegions = remember(regions, regionScope, selected, neighborNames) {
        regions.filter { region -> when (regionScope) {
            "SELECTED" -> region.cells.any { it.name in selected }
            "NEIGHBORS" -> region.cells.any { it.name in neighborNames }
            else -> true
        } }
    }
    val activeHere = active?.takeIf { it.packageId == stored.id }?.cells?.toSet().orEmpty()
    val draftActive = active?.packageId == stored.id && selected == activeHere
    val validDraft = selected.isNotEmpty() && selected.size <= selectionLimit && selectable.containsAll(selected)
    var baseline by rememberSaveable(stored.id) { mutableStateOf(selected.sorted()) }
    var closeRequested by rememberSaveable(stored.id) { mutableStateOf(false) }
    var closeAfterActivation by rememberSaveable(stored.id) { mutableStateOf(false) }
    var pendingRevision by rememberSaveable(stored.id) { mutableStateOf<Long?>(null) }
    var pendingSelection by rememberSaveable(stored.id) { mutableStateOf(emptyList<String>()) }
    var observedBusy by remember(stored.id) { mutableStateOf(false) }
    var saveError by remember(stored.id) { mutableStateOf<String?>(null) }
    val exitGate=LocalMapTaskExit.current
    val exitOwner=remember(stored.id){Any()}
    fun finishClose(){if(exitGate!=null)exitGate.complete(exitOwner,close)else close()}
    val dirty = selected != baseline.toSet()
    val canActivate = !busy && !measurementActive && validDraft && (!requiresNoaaTerms || noaaTerms)
    fun requestClose() {
        if (busy || pendingRevision != null) return
        if (selectionReader() != baseline.toSet()) closeRequested = true else finishClose()
    }
    fun submitActivation(closeAfter: Boolean) {
        if (!canActivate || pendingRevision != null) return
        if (draftActive) { baseline = selected.sorted(); closeRequested = false; if (closeAfter) finishClose(); return }
        pendingRevision = activationRevision
        pendingSelection = selected.sorted()
        observedBusy = false
        closeAfterActivation = closeAfter
        saveError = null
        activate()
    }
    RegisterMapTaskExit(exitOwner,::requestClose)
    val latestCloseRequest by remember { mutableStateOf<(()->Unit)>(::requestClose, referentialEqualityPolicy()) }
        .apply { value = ::requestClose }
    val latestRegister by rememberUpdatedState(registerCloseRequest)
    DisposableEffect(stored.id) {
        latestRegister { latestCloseRequest() }
        onDispose { latestRegister(null) }
    }
    LaunchedEffect(activationRevision, busy, active, pendingRevision) {
        val requestedRevision = pendingRevision ?: return@LaunchedEffect
        if (busy) { observedBusy = true; return@LaunchedEffect }
        val confirmed = activationRevision > requestedRevision && active?.packageId == stored.id && active.cells.toSet() == pendingSelection.toSet()
        if (confirmed) {
            baseline = pendingSelection
            pendingRevision = null
            closeRequested = false
            if (closeAfterActivation||exitGate?.isPending(exitOwner)==true) finishClose()
        } else if (observedBusy) {
            pendingRevision = null
            saveError = message ?: com.no5.ui.No5Text.text(context, "Speichern fehlgeschlagen. Die Änderungen bleiben erhalten.")
        } else {
            // A refused dispatch never produces a busy transition or a durable
            // activation revision. Do not leave the editor permanently locked.
            withFrameNanos { }
            pendingRevision = null
            saveError = com.no5.ui.No5Text.text(context, "Speichern fehlgeschlagen. Die Änderungen bleiben erhalten.")
        }
    }
    val detail = visibleRegions.find { it.id == openZone } ?: visibleRegions.singleOrNull()?.takeIf { it.cells.size <= 12 }
    fun add(names: Set<String>) {
        val result = chartAddSelection(selected, names, selectable, selectionLimit)
        if (result.exceededLimit) notice = No5Text.text(context, "Über %d Zellen: Auswahl unverändert. Kleinere Zone wählen.", selectionLimit)
        else { onSelection(result.selection); notice = null }
    }
    val nextStep = when {
        busy -> progress.ifBlank { "Karten werden vorbereitet …" }
        measurementActive -> "Rendereraufnahme beenden, dann Auswahl aktivieren."
        selected.isEmpty() -> "Zonen oder einzelne Zellen auswählen."
        !validDraft -> if (selectionLimit < Int.MAX_VALUE) No5Text.text(context, "Auswahl prüfen: maximal %d nutzbare Zellen.", selectionLimit) else "Auswahl prüfen: nur nutzbare Zellen dieses Pakets freigeben."
        requiresNoaaTerms && !noaaTerms -> "NOAA-Nutzungshinweise lesen und bestätigen, dann Auswahl aktivieren."
        draftActive -> "Diese Auswahl ist aktiv. Fertig – du kannst schließen."
        else -> "Entwurf bereit. Auswahl aktivieren, dann schließen."
    }
    if(workspace) {
        MapRegionWorkspace(stored,regions,selected,activeHere,filter,onFilter,busy,pendingRevision!=null,
            canActivate,draftActive,selectionLimit,requiresNoaaTerms,noaaTerms,
            {accepted->noaaTerms=accepted;termsPreferences.edit().putBoolean(termsKey,accepted).apply()},
            {links.openUri("https://www.charts.noaa.gov/ENCs/ENCs.shtml")},saveError?:notice?:nextStep,message,
            ::requestClose,{submitActivation(false)},deactivate,onSelection,::add,neighborNames)
    } else {
    Surface(Modifier.fillMaxSize().testTag("chart-region-workspace"), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().testTag("chart-region-fixed-status"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(No5Text.text(LocalContext.current, "Kartenbereiche · %s", stored.label), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (selectionLimit < Int.MAX_VALUE) No5Text.text(LocalContext.current, "Entwurf: %d/%d Zellen", selected.size, selectionLimit) else No5Text.text(LocalContext.current, "Entwurf: %d Zellen", selected.size), Modifier.testTag("chart-region-draft-count"), style = MaterialTheme.typography.titleMedium)
                    Text(if (active?.packageId == stored.id) No5Text.text(LocalContext.current, if (activeHere.size == 1) "Aktiv: %d Zelle" else "Aktiv: %d Zellen", activeHere.size) else
                        if (active == null) No5Text.text(LocalContext.current, "Noch keine aktive Karte") else No5Text.text(LocalContext.current, "Aktiv: %d Zellen in anderem Paket", active.cells.size),
                        Modifier.testTag("chart-region-active-status"), style = MaterialTheme.typography.bodyMedium)
                }
                if (selectionLimit == Int.MAX_VALUE && active?.packageId == stored.id) Text(
                    chartResidentStatusText(active.cells.size, residency), Modifier.testTag("chart-region-residency"), style = MaterialTheme.typography.bodySmall)
                Text(No5Text.text(LocalContext.current, if (draftActive) "Entwurf und aktive Karte stimmen überein." else "Änderungen werden erst durch Aktivieren auf der Karte wirksam."),
                    style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag("chart-region-scroll-content"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Editing tools belong to this single bounded body. Keeping them above it
                // squeezed the fixed close/apply actions below48dp when the keyboard opened.
                FlowRow(Modifier.fillMaxWidth().testTag("chart-region-fixed-actions"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    No5CompactAction(No5Text.text(LocalContext.current, "Alle auswählen (%d)", selectable.size), { add(selectable) }, Modifier.testTag("chart-select-all"),
                        enabled = !busy && selectable.isNotEmpty() && selectable.size <= selectionLimit)
                    No5CompactAction(No5Text.text(LocalContext.current, "Alle abwählen"), { onSelection(emptySet()); notice = null }, Modifier.testTag("chart-select-none"), enabled = !busy)
                    No5CompactAction(No5Text.text(LocalContext.current, "Nachbarzonen (+%d)", neighborNames.size), { add(neighborNames) }, Modifier.testTag("chart-select-neighbors"),
                        enabled = !busy && neighborNames.isNotEmpty() && (selected + neighborNames).size <= selectionLimit)
                    if (filter.isNotBlank()) {
                        No5CompactAction(No5Text.text(LocalContext.current, "Gefilterte auswählen"), { add(filteredAvailable) },
                            enabled = !busy && filteredAvailable.isNotEmpty() && (selected + filteredAvailable).size <= selectionLimit)
                        No5CompactAction(No5Text.text(LocalContext.current, "Gefilterte abwählen"), { onSelection(chartRemoveSelection(selected, filteredAvailable)); notice = null },
                            enabled = !busy && filteredAvailable.isNotEmpty())
                    }
                    if (detail != null) {
                        No5CompactAction(No5Text.text(LocalContext.current, "Zone auswählen (%d)", detail.selectable.size), { add(detail.selectable) }, Modifier.testTag("chart-zone-select"),
                            enabled = !busy && detail.selectable.isNotEmpty() && (selected + detail.selectable).size <= selectionLimit)
                        No5CompactAction(No5Text.text(LocalContext.current, "Zone abwählen"), { onSelection(chartRemoveSelection(selected, detail.selectable)); notice = null },
                            Modifier.testTag("chart-zone-deselect"), enabled = !busy && detail.selectable.any { it in selected })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(filter, { onFilter(it); zonePage = 0; openZone = null }, label = { Text(No5Text.text(LocalContext.current, "Zellen suchen · Kennung, z. B. BOS")) },
                        singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                    Text(if (detail != null) No5Text.text(LocalContext.current, "Zone: %s", chartRegionLabel(detail)) else No5Text.text(LocalContext.current, "%d Zonen · %d Zellen", visibleRegions.size, filtered.size),
                        Modifier.widthIn(max = 210.dp), style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ALL" to "Alle Zonen", "SELECTED" to "Ausgewählte Zonen", "NEIGHBORS" to "Nachbarzonen").forEach { (scope, label) ->
                        No5Tab(No5Text.text(LocalContext.current, label), regionScope == scope, { regionScope = scope; zonePage = 0; openZone = null })
                    }
                }
                Text(No5Text.text(LocalContext.current, "1°-Raster nach Zellmittelpunkt. Vorschau: Geometrie-Umrisse; keine Abdeckungsprüfung."), style = MaterialTheme.typography.bodySmall)
                Text(No5Text.text(LocalContext.current, if (selected.isEmpty()) "Nachbarn: ein Ring angrenzender Rasterfelder um die Auswahl oder aktive Karte; ergänzt werden nur Zellen dieses Pakets." else "Nachbarn: ein Ring angrenzender Rasterfelder um die Auswahl; ergänzt werden nur Zellen dieses Pakets."), style = MaterialTheme.typography.bodySmall)
                if (selectable.size > selectionLimit) Text(No5Text.text(LocalContext.current, "Alle %d Zellen überschreiten das Aktivierungslimit. Eine Zone oder einzelne Zellen wählen.", selectable.size), style = MaterialTheme.typography.bodySmall)
                if (neighborNames.isNotEmpty() && (selected + neighborNames).size > selectionLimit) Text(No5Text.text(LocalContext.current, "Die Nachbarzonen würden %d Zellen überschreiten. Unter Nachbarzonen eine Zone öffnen und einzeln auswählen.", selectionLimit), style = MaterialTheme.typography.bodySmall)
                if (visibleRegions.isEmpty()) Text(No5Text.text(LocalContext.current, if (regionScope == "NEIGHBORS") "Keine weiteren räumlich zugeordneten Nachbarzonen im Paket." else "Keine passenden Zellen.")) else {
                    val pageCount = (visibleRegions.size + 11) / 12
                    val currentPage = zonePage.coerceIn(0, pageCount - 1)
                    ChartRegionGrid(visibleRegions.drop(currentPage * 12).take(12), selected, openZone, !busy) { region ->
                        openZone = if (openZone == region.id) null else region.id
                    }
                    if (pageCount > 1) RegionPageControls(currentPage, pageCount, !busy) { zonePage = it; openZone = null }
                    detail?.let { region ->
                        ChartRegionDetails(region, selected, !busy, selectionLimit,
                            onToggle = { name -> if (name in selected) onSelection(selected - name) else add(setOf(name)) })
                    }
                }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().testTag("chart-region-fixed-footer"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (requiresNoaaTerms) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        No5ActionFeedback(enabled = !busy) { feedback ->
                            Checkbox(noaaTerms, { accepted ->
                                noaaTerms = accepted; termsPreferences.edit().putBoolean(termsKey, accepted).apply()
                            }, feedback.modifier.testTag("chart-noaa-terms"), enabled = feedback.enabled,
                                interactionSource = feedback.interactionSource)
                        }
                        Text(No5Text.text(LocalContext.current, "NOAA-Nutzungsbedingungen gelesen und akzeptiert"),
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        No5CompactAction(No5Text.text(LocalContext.current, "NOAA-Bedingungen"), { links.openUri("https://www.charts.noaa.gov/ENCs/ENCs.shtml") }, enabled = !busy)
                    }
                }
                Text(No5Text.text(LocalContext.current, notice ?: nextStep), Modifier.testTag("chart-region-next-step"), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (message != null && !draftActive) Text(message, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    No5CompactAction(No5Text.text(LocalContext.current, "Schließen"), ::requestClose, Modifier.testTag("chart-region-close"), enabled = !busy && pendingRevision == null)
                    if (deactivate != null) No5CompactAction(No5Text.text(LocalContext.current, "Gewählte deaktivieren"), deactivate,
                        Modifier.testTag("chart-region-deactivate"), enabled = !busy && !measurementActive && selected.any { it in activeHere })
                    No5CompactAction(No5Text.text(LocalContext.current, if (draftActive) "Auswahl ist aktiv" else "Auswahl aktivieren"), { submitActivation(false) },
                        Modifier.testTag("chart-package-activate"), enabled = canActivate && !draftActive && pendingRevision == null)
                }
            }
        }
    }
    }
    if (closeRequested) MapTaskDecision(
        saveLabel=m1("Aktivieren","Activate","Aktiveer"),onSave = { submitActivation(true) },
        onDiscard = { onSelection(baseline.toSet()); closeRequested = false; finishClose() },
        onContinue = { closeRequested = false; exitGate?.cancel(exitOwner) },
        saving = busy || pendingRevision != null, saveEnabled = canActivate,
        error = saveError ?: nextStep.takeIf { !canActivate },
    )
}

/** Counts are geometry residency, not pixel or nautical completeness. */
internal fun chartResidentStatusText(released: Int, status: ChartResidencyStatus?): String =
    if (status == null) No5Text.ui("%d freigegeben · Ausschnitt wird vorbereitet", released) else
        No5Text.ui("%d freigegeben · %d im Ausschnitt benötigt · %d geladen", released, status.required, status.resident) +
            if (status.missing > 0) No5Text.ui(" · %d noch nicht geladen", status.missing) else ""
