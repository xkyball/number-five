package com.no5.helm.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.no5.helm.model.*
import com.no5.preparation.ui.No5CompactAction
import com.no5.preparation.ui.No5PanelDialog
import com.no5.ui.No5Text
import com.no5.ui.no5Clickable
import java.time.Instant

internal fun positionStatusText(status: PositionStatus, language: String = com.no5.ui.UiLanguage.current()) = No5Text.text(language, when (status) {
    PositionStatus.LIVE -> "Position aktuell"
    PositionStatus.STALE -> "Position veraltet"
    PositionStatus.LOST -> "Position verloren"
    PositionStatus.INVALID -> "Position ungültig"
    PositionStatus.UNAVAILABLE -> "Position nicht verfügbar"
})
private fun gnssStatusText(status: PositionStatus, language: String) = No5Text.text(language, when(status) {
    PositionStatus.LIVE -> "Messwert aktuell"; PositionStatus.STALE -> "Messwert veraltet"; PositionStatus.LOST -> "Messwert verloren"
    PositionStatus.INVALID -> "Messwert ungültig"; PositionStatus.UNAVAILABLE -> "Messwert nicht verfügbar"
})
internal fun positionSummary(state: MapPositionState, language: String = com.no5.ui.UiLanguage.current()): String {
    fun tr(source: String) = No5Text.text(language, source)
    val status = if (!state.chartBoundsKnown) tr("Kartengrenzen unbekannt") else if (state.outsideChartBounds) tr("Außerhalb des aktiven Kartenbereichs") else if (state.awaitingFreshFix && state.status == PositionStatus.LIVE) tr("Warte auf neuen Fix") else positionStatusText(state.status, language)
    val source = if (state.intent.choice == PositionChoice.COMBINED) tr("GPS kombiniert") + when (state.sources?.combinationReason) {
        "SINGLE_SOURCE" -> " · " + tr("Einzelquellenbetrieb")
        "SOURCE_DISAGREEMENT", "TIME_MISMATCH" -> " · " + tr("GPS-Konflikt")
        else -> ""
    } else state.selectedLabel ?: state.intent.sourceId ?: if (state.legacy) tr("Gateway-Primärposition · Legacy") else tr("Keine GPS-Quelle bestätigt")
    val follow = when(state.mode) {
        PositionMode.OFF -> tr("Folgen aus")
        PositionMode.CENTERED -> tr("Einmal zentriert")
        PositionMode.FOLLOW -> tr(if(state.touchActive) "Folgen pausiert während Touch" else if(!state.chartBoundsKnown) "Kartengrenzen unbekannt" else if(state.outsideChartBounds) "Folgen pausiert am Kartenrand" else if(state.followActive) "Folgen an" else "Folgen wartet")
    }
    return "$source · $status · $follow"
}
@Composable internal fun MapPositionDialog(state: MapPositionState, onDismiss: () -> Unit,
    onChoice: (PositionChoice, String?) -> Unit) {
    val language = com.no5.ui.LocalNo5Language.current
    fun tr(source:String,vararg args:Any?)=No5Text.text(language,source,*args)
    var page by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("position") }
    fun back(){if(page=="position")onDismiss()else page="position"}
    androidx.activity.compose.BackHandler{back()}
    val night=MaterialTheme.colorScheme.surface.red<.15f
    val choice=when(state.intent.choice){PositionChoice.AUTOMATIC->tr("Automatisch");PositionChoice.COMBINED->tr("Gemeinsame Lösung");else->state.selectedLabel?:state.intent.sourceId?:tr("Unbekannt")}
    val follow=when(state.mode){PositionMode.OFF->tr("Folgen aus");PositionMode.CENTERED->tr("Einmal zentriert");PositionMode.FOLLOW->tr(if(state.touchActive)"Folgen pausiert während Touch"else if(!state.chartBoundsKnown)"Kartengrenzen unbekannt"else if(state.outsideChartBounds)"Folgen pausiert am Kartenrand"else if(state.followActive)"Folgen an"else"Folgen wartet")}
    val combinedReason=tr(when(state.sources?.combinationReason){
        "OK"->"Gemeinsame Position aus mehreren GPS-Quellen"
        "SINGLE_SOURCE"->"Einzelquellenbetrieb: nur eine GPS-Quelle ist brauchbar"
        "SOURCE_DISAGREEMENT"->"GPS-Konflikt: Positionen liegen zu weit auseinander"
        "TIME_MISMATCH"->"GPS-Konflikt: Messzeiten passen nicht zusammen"
        "NO_USABLE_SOURCE"->"Keine frische GPS-Quelle mit gültiger Fixqualität"
        "SOURCE_CATALOG_TRUNCATED"->"GPS-Kombination: Quellenkatalog unvollständig"
        else->"GPS-Kombination im Gateway nicht verfügbar"
    })
    BoxWithConstraints(Modifier.fillMaxSize()){
        val scale=maxWidth.value/2000f
        Box(Modifier.offset(y=(130*scale).dp).fillMaxWidth().height((808*scale).dp)){
            if(page=="sources")MapPagedWorkspace(m1("Empfänger wählen","Choose receiver","Kies ontvanger"),positionSummary(state,language),
                state.sources?.sources.orEmpty().map{source->com.no5.header.artwork.No5WorkspaceView.Row(source.label,positionStatusText(source.status,language),rawLabel=true,
                    selected=state.intent.choice==PositionChoice.MANUAL&&state.intent.sourceId==source.sourceId,key="position-source-${source.sourceId}",control="check",action={onChoice(PositionChoice.MANUAL,source.sourceId)})}.ifEmpty{listOf(com.no5.header.artwork.No5WorkspaceView.Row(m1("Keine Empfänger verfügbar","No receivers available","Geen ontvangers beskikbaar nie"),enabled=false,key="position-no-receivers"))},night,::back)
            else com.no5.ui.No5Workspace(m1("Position","Position","Posisie"),positionSummary(state,language),listOf(
                com.no5.header.artwork.No5WorkspaceView.Row(m1("Automatisch","Automatic","Outomaties"),selected=state.intent.choice==PositionChoice.AUTOMATIC,key="position-auto",control="check",action={onChoice(PositionChoice.AUTOMATIC,null)}),
                com.no5.header.artwork.No5WorkspaceView.Row(m1("Einzelquelle","Single source","Enkelbron"),value=choice,rawValue=true,selected=state.intent.choice==PositionChoice.MANUAL,key="position-individual",action={page="sources"}),
                com.no5.header.artwork.No5WorkspaceView.Row(m1("Gemeinsame Lösung","Combined solution","Gekombineerde oplossing"),combinedReason,selected=state.intent.choice==PositionChoice.COMBINED,enabled=state.sources?.let{it.valid&&it.combinationAvailable}==true,key="position-combined",control="check",action={onChoice(PositionChoice.COMBINED,null)})),night,::back,Modifier.fillMaxSize(),rawSubtitle=true)
        }
    }
}
