package com.no5.helm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.no5.domain.DepthUnit
import com.no5.ui.No5DetailsDialog
import com.no5.ui.No5Text
import com.no5.ui.no5Clickable

/** Independent Compose affordance: a stalled GPU never owns or delays a warning. */
@Composable internal fun SonarStatusButton(scale:Float,night:Boolean,ctl:SonarNativeController,modifier:Modifier=Modifier){
    val status=ctl.uiStatus;val col=sonarUiColors(night)
    val color=when(status.symbolLevel){
        SonarStatusLevel.NORMAL->col.secondary
        SonarStatusLevel.WARNING->if(night)Color(0xffb84b24)else Color(0xff985000)
        SonarStatusLevel.ERROR->if(night)col.accent else Color(0xffb02020)
    }
    Box(modifier.testTag("Sonar status").semantics {
            contentDescription=No5Text.ui("Sonarstatus öffnen")
            stateDescription=No5Text.ui(status.label)+(if(status.recovering)" · "+No5Text.ui("Letzte Störung; Erholungsphase")else "")
        }.no5Clickable(role=Role.Button,feedbackColor=col.accent){ctl.openStatusDetails()},contentAlignment=Alignment.Center){
        Box(Modifier.fillMaxSize().padding(horizontal=(4*scale).dp,vertical=(10*scale).dp)
            .background(col.background,RoundedCornerShape((9*scale).dp)).border((1*scale).dp,col.border,RoundedCornerShape((9*scale).dp)),contentAlignment=Alignment.Center){
        Canvas(Modifier.size((48*scale).dp)) {
            val w=size.width;val h=size.height;val stroke=Stroke(w*.065f)
            if(status.symbolLevel==SonarStatusLevel.WARNING){
                val triangle=Path().apply{moveTo(w*.5f,h*.06f);lineTo(w*.96f,h*.92f);lineTo(w*.04f,h*.92f);close()}
                drawPath(triangle,color,style=stroke)
            }else drawCircle(color,w*.44f,style=stroke)
            if(status.symbolLevel==SonarStatusLevel.ERROR){
                drawLine(color,Offset(w*.32f,h*.32f),Offset(w*.68f,h*.68f),w*.07f)
                drawLine(color,Offset(w*.68f,h*.32f),Offset(w*.32f,h*.68f),w*.07f)
            }else{
                val warning=status.symbolLevel==SonarStatusLevel.WARNING
                drawLine(color,Offset(w*.5f,h*(if(warning).35f else .45f)),Offset(w*.5f,h*(if(warning).63f else .73f)),w*.07f)
                drawCircle(color,w*.045f,Offset(w*.5f,h*(if(warning).77f else .28f)))
            }
        }
        }
    }
}

@Composable internal fun SonarStatusDetails(scale:Float,night:Boolean,ctl:SonarNativeController,unit:DepthUnit){
    if(!ctl.statusDetailsOpen)return
    val col=sonarUiColors(night);val state=ctl.uiStatus;val snapshot=ctl.snapshot;val model=ctl.model
    val now=ctl.statusNowNanos
    val incident=state.currentIncident?:state.lastIncident
    val last=state.lastIncident
    fun age(start:Long?)=start?.takeIf{now>=it}?.let{sonarNumber((now-it)/1e9,1)+" s"}?:"—"
    val analysis=if(ctl.lastGpuPresentation!=null)ctl.displayedGpuAnalysis()else model.viewPresentation
    val cursor=ctl.cursor;val pressure=ctl.statusReceiveDiagnostics()
    val presented=ctl.lastGpuPresentation?.takeIf{it.context==ctl.gpuSourceContext()&&it.ringGeneration==model.viewRing.lifecycleGeneration}
    val plotEpoch=presented?.originalEpochMs?:model.viewRing.takeIf{ctl.lastGpuPresentation==null&&it.count>0}?.timestampAt(0)
    val mode=if(snapshot.display.exactPingProfiles)"Echte Pingprofile"else if(ctl.reliefEnabled)"Echo-Relief"else "2D-Echogramm"
    val relief=when {
        !ctl.reliefEnabled->"Aus"
        ctl.reliefFailure!=null->ctl.reliefFailure!!
        !model.viewHistoryHasUniformGeometry()->"Wechselnde Messbereiche · 2D aktiv"
        model.imageHistoryAggregated->"Bildaggregation · 2D aktiv"
        !ctl.reliefReady->"Nicht bereit · 2D aktiv"
        ctl.reliefAligned->"Bereich ausgerichtet; Zeit nach rechts, Tiefe nach unten"
        else->"Orbitansicht; Bereich perspektivisch verkürzt"
    }
    val smoothing=if(snapshot.display.exactPingProfiles)"Aus"else if(ctl.linearInterpolation)if(presented?.smoothing==true)"7-Ping-Signalglättung; vollständige Nachbarschaften"else "Glättung gewählt; für diesen Bildstand unbestätigt"else "Nächster Nachbar"
    No5DetailsDialog("Sonarstatus",night,ctl::closeStatusDetails,tag="sonar-status-details",bodyKey="sonar-status",preferredHeight=780f){
        Column(verticalArrangement=Arrangement.spacedBy((12*scale).dp)){
            SonarFact("Aktueller Zustand",No5Text.ui(state.label),scale,col)
            SonarFactPair("Tiefenausschnitt" to No5Text.ui(ctl.viewportStatusLabel()),
                "Anzeige ab" to "${sonarNumber(unit.fromMeters(model.viewDisplay.startDepthM),2)} ${unit.symbol}",scale,col)
            SonarFactPair("Empfang" to No5Text.ui(if(pressure?.backpressured==true)"Empfang wartet"else "Kein Rückdruck"),
                "Aktueller Fehler" to (ctl.sourceStatus.error?:ctl.observationReasonLabel()?:"—"),scale,col)
            SonarFact("Aktive Störung",state.currentIncident?.reason?.label?.let(No5Text::ui)?:"—",scale,col)
            SonarFactPair("Letzte beendete Störung" to (last?.reason?.label?.let(No5Text::ui)?:"—"),
                "Beendet vor" to age(last?.endedNanos),scale,col)
            SonarFactPair("Letzter Empfang vor" to ctl.readReceiveSilenceNanos().takeIf{it>=0}?.let{sonarNumber(it/1e9,1)+" s"}.orEmpty().ifEmpty{"—"},
                "Bild-Messalter" to (ctl.observationAgeMs(plotEpoch)?.let{sonarNumber(it/1000.0,1)+" s"}?:"—"),scale,col)
            SonarFact("Bildbindung",presented?.let{"PING ${it.pingId} · ${it.originalEpochMs}"}?:plotEpoch?.let{"Messzeit $it"}?:"—",scale,col)
            SonarFactPair("Analyse" to (ctl.observationReasonLabel()?:ctl.profileInterpretationLabel()?:sonarAnalysisQualityLabel(analysis?.quality)),
                "Tracks / Gesamt / ausgelassen" to (analysis?.let{"${it.tracks.size} / ${it.trackTotal} / ${it.trackOmitted}"}?:"—"),scale,col)
            SonarFact("Track-Befunde",analysis?.tracks?.take(64)?.joinToString("; "){"T${it.trackId}: ${it.classification} · ${it.classificationSource}"}.orEmpty().ifEmpty{"—"},scale,col)
            SonarFact("Echo-Relief",relief+" · Höhe bedeutet Amplitude, keine gemessene Bodentopografie.",scale,col)
            SonarFactPair("Darstellung" to mode,"Glättung" to smoothing,scale,col)
            SonarFactPair("Zeitfenster / vorhanden" to "${sonarNumber(snapshot.display.historySeconds)} / ${sonarNumber(model.historyCoverage().visibleSeconds)} s",
                "Bildhistorie" to (if(model.imageHistoryUnavailable)"Nicht verfügbar · Einzelpings"else if(model.imageHistoryAggregated)"Peak-Aggregation · 300 ms / 256 Tiefenbins"else "Einzelpings"),scale,col)
            SonarFactPair("A-Scope" to (if(!snapshot.display.aScope)"Aus"else if(snapshot.display.freeze)"Eingefrorener Ping"else "Angezeigter Ping"),
                "Letzte Bedienmeldung" to (ctl.lastStatusMessage?:"—"),scale,col)
            SonarFactPair("Cursor · Tiefe / Bin" to (cursor?.let{if(it.depthM==null)"BIN ${it.bin}"else "${sonarNumber(unit.fromMeters(it.depthM))} ${unit.symbol}"}?:"—"),
                "Cursor · Zeit / Amplitude" to (cursor?.let{"−${sonarNumber(it.secondsAgo)} s · ${it.amplitude} / ${it.amplitudeFullScale}"}?:"—"),scale,col)
            SonarFact("Cursorbindung",cursor?.let{"PING ${it.pingId?:"—"} · ${it.timestampEpochMs?:"—"}"}?:"—",scale,col)
        }
    }
}
