package com.no5.helm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import com.no5.header.artwork.No5WorkspaceView as Workspace
import com.no5.ui.*

/** D03 navigation, with the existing instrument and source owners. No synthetic sonar data. */
@Composable internal fun SonarWorkspace(scale:Float,night:Boolean,ctl:SonarNativeController,tab:SonarDrawerTab,unit:DepthUnit){
    var page by remember(ctl){mutableStateOf(when(tab){SonarDrawerTab.DISPLAY->"menu";SonarDrawerTab.ANALYSIS->"analysis";SonarDrawerTab.DIAGNOSTICS->"diagnostics"})}
    var sheet by remember(ctl){mutableStateOf<String?>(null)}
    // Explicit menu requests (including long press) return to their real entry page.
    // Internal leaf navigation changes the category without issuing another entry request.
    LaunchedEffect(ctl,ctl.drawerRequest){
        sheet=null;page=when(tab){SonarDrawerTab.DISPLAY->"menu";SonarDrawerTab.ANALYSIS->"analysis";SonarDrawerTab.DIAGNOSTICS->"diagnostics"}
    }
    val col=sonarUiColors(night);val d=ctl.snapshot.display
    val allAuto=listOf(d.gainMode,d.gammaMode,d.thresholdMode).all{it==SonarImageMode.AUTO}
    fun go(next:String){page=next;ctl.selectDrawerTab(when(next){"analysis"->SonarDrawerTab.ANALYSIS;"diagnostics"->SonarDrawerTab.DIAGNOSTICS;else->SonarDrawerTab.DISPLAY})}
    fun back(){when{sheet!=null->sheet=null;page=="menu"->ctl.close();page in listOf("image-details","image-manual")->go(if(page=="image-manual")"image-details"else"image");else->go("menu")}}
    BackHandler{back()}
    fun row(label:String,key:String,detail:String?=null,value:String?=null,enabled:Boolean=true,selected:Boolean=false,control:String="",slider:Workspace.Slider?=null,action:(()->Unit)?=null)=Workspace.Row(label,detail,value,enabled=enabled,selected=selected,control=control,key=key,rawValue=true,action=action,slider=slider)
    fun auto(mode:SonarImageMode)=No5Text.ui(if(mode==SonarImageMode.AUTO)"Auto"else"Manuell")
    val rows=when(page){
        "image"->listOf(
            row("Bildautomatik","sonar-image-auto","Gain, Gamma und Schwelle gemeinsam",No5Text.ui(if(allAuto)"An"else"Aus"),selected=allAuto,control="toggle",action={ctl.imageAuto(!allAuto)}),
            row("Bereich","sonar-range","Nur bestätigte Tiefe",No5Text.ui(if(ctl.requestedRangeMode==SonarRangeMode.AUTO)"Auto"else"Manuell"),action={sheet="range"}),
            row("Gain","sonar-gain",value=sonarGainLabel(d),control="slider",slider=Workspace.Slider(if(d.gainMode==SonarImageMode.AUTO)d.autoGainOffsetDb else d.gainDb,-24.0,if(d.gainMode==SonarImageMode.AUTO)24.0 else 36.0,1.0){ctl.manualImage(SonarImageField.GAIN,it)},action={sheet="gain"}),
            row("Weitere Bildeinstellungen","sonar-picture-details",action={go("image-details")}))
        "image-details"->listOf(
            row("Glättung","sonar-smoothing",value=No5Text.ui(if(d.exactPingProfiles)"Echte Pingprofile"else if(ctl.linearInterpolation)"Linear"else"Nächster Nachbar"),action={sheet="smoothing"}),
            row("Palette","sonar-palette",value=sonarPaletteName(d.palette),action={sheet="palette"}),
            row("Zeitfenster","sonar-history",value=sonarNumber(d.historySeconds)+" s",action={sheet="history"}),
            row("Gamma / Schwelle","sonar-image-manual",value=if(d.gammaMode==d.thresholdMode)auto(d.gammaMode)else No5Text.ui("Gemischt"),action={go("image-manual")}),
            row("2D / Echo-Relief","sonar-relief","Relief nur bei vorhandener Fähigkeit",No5Text.ui(if(ctl.reliefEnabled&&!d.exactPingProfiles)"3D ECHO RELIEF"else"2D ECHOGRAM"),action={sheet="relief"}))
        "image-manual"->listOf(
            row("Gain","sonar-manual-gain",value=sonarGainLabel(d),control="slider",slider=Workspace.Slider(if(d.gainMode==SonarImageMode.AUTO)d.autoGainOffsetDb else d.gainDb,-24.0,if(d.gainMode==SonarImageMode.AUTO)24.0 else 36.0,1.0){ctl.manualImage(SonarImageField.GAIN,it)},action={sheet="gain"}),
            row("Gamma","sonar-manual-gamma",value=auto(d.gammaMode)+" · "+sonarNumber(d.gamma,1),control="slider",slider=Workspace.Slider(d.gamma,.2,3.0,.1){ctl.manualImage(SonarImageField.GAMMA,it)},action={sheet="gamma"}),
            row("Anzeigeschwelle","sonar-manual-threshold",value=auto(d.thresholdMode)+" · "+sonarNumber(d.threshold,2),control="slider",slider=Workspace.Slider(d.threshold,0.0,.95,.01){ctl.manualImage(SonarImageField.THRESHOLD,it)},action={sheet="threshold"}))
        "analysis"->listOf(
            row("Analysebezug","sonar-analysis-context",value=No5Text.ui(if(ctl.cursor!=null)"Cursor"else if(d.freeze)"Eingefroren"else"Live"),action={sheet="analysis-context"}),
            row("Boden","sonar-bottom",value=sonarAnalysisQualityLabel(ctl.model.viewPresentation?.quality),action={sheet="bottom"}),
            row("Filter","sonar-filter",value=if(ctl.capabilities.filterControl)No5Text.ui(ctl.filterState.mode?.name?:"Nicht bestätigt")else No5Text.ui("Nicht verfügbar"),action={sheet="filter"}))
        "diagnostics"->listOf(
            row("Pingquelle","sonar-source",value=ctl.sourceLabel(),action={sheet="source"}),
            row("Host, Tracks und Blöcke","sonar-host",action={sheet="host"}),
            row("Anzeige und Geometrie","sonar-geometry",action={sheet="geometry"}),
            row("Sitzung und Transport","sonar-session",action={sheet="session"}))
        else->listOf(row("Bild","sonar-menu-image","Auto, Bereich, Darstellung und Glättung",action={go("image")}),
            row("Analyse","sonar-menu-analysis","Boden und gültiger Ping-Kontext",action={go("analysis")}),
            row("Diagnose","sonar-menu-diagnostics","Quellen, Zeiten und beobachtete Werte",action={go("diagnostics")}))
    }
    val title=when(page){"image"->"Sonar · Bild";"image-details"->"Weitere Bildeinstellungen";"image-manual"->"Manuelle Bildwerte";"analysis"->"Sonar · Analyse";"diagnostics"->"Sonar · Diagnose";else->"Sonar einstellen"}
    val p=sonarPanelPresentation(ctl,unit)
    // Actual live publication rate continues during freeze; depth/quality retain their matched view context.
    val caption=listOf(p.depthLabel,p.qualityLabel,ctl.sourceLabel(),ctl.rateTitle()+": "+ctl.rateLabel(),No5Text.ui("Brauchbar: %s",ctl.selectedUsableRateLabel())).joinToString(" · ")
    val actions=if(page in listOf("image","analysis","diagnostics"))buildList{
        if(page!="image")add(Workspace.Action("Bild",key="sonar-tab-image",action={go("image")}))
        if(page!="analysis")add(Workspace.Action("Analyse",key="sonar-tab-analysis",action={go("analysis")}))
        if(page!="diagnostics")add(Workspace.Action("Diagnose",key="sonar-tab-diagnostics",action={go("diagnostics")}))
        add(Workspace.Action("Schließen",key="sonar-close",action={ctl.close()}))
    }else emptyList()
    Box(Modifier.offset(y=(130*scale).dp).size((2000*scale).dp,(928*scale).dp)){
        No5Workspace(title,caption,rows,night,::back,Modifier.fillMaxSize().testTag("sonar-workspace-$page"),actions=actions,rawSubtitle=true,liveSubtitle=true,stateKey=page)
    }
    sheet?.let{selected->
        val label=when(selected){"gain"->"Gain";"gamma"->"Gamma";"threshold"->"Anzeigeschwelle";"range"->"Bereich";"smoothing"->"Glättung";"palette"->"Palette";"history"->"Zeitfenster";"relief"->"2D / Echo-Relief";"source"->"Pingquelle wählen";"host"->"Host, Tracks und Blöcke";"geometry"->"Anzeige und Geometrie";"session"->"Sitzung und Transport";"filter"->"Filter";"bottom"->"Boden";else->"Analysebezug"}
        No5DetailsDialog(label,night,{sheet=null},tag="sonar-sheet-$selected",bodyKey=selected,preferredHeight=780f){
            Column(verticalArrangement=Arrangement.spacedBy((12*scale).dp)){
                when(selected){
                    "gain"->SonarImageField(ctl,SonarImageField.GAIN,"Darstellungs-Gain",if(d.gainMode==SonarImageMode.AUTO)d.autoGainOffsetDb else d.gainDb,d.gainMode,-24f..(if(d.gainMode==SonarImageMode.AUTO)24f else 36f),1f," dB",scale,col)
                    "gamma"->SonarImageField(ctl,SonarImageField.GAMMA,"Gamma",d.gamma,d.gammaMode,.2f..3f,.1f,"",scale,col)
                    "threshold"->SonarImageField(ctl,SonarImageField.THRESHOLD,"Anzeigeschwelle",d.threshold,d.thresholdMode,0f..0.95f,.01f,"",scale,col)
                    "range"->SonarRangeSettings(ctl,unit,scale,col,night)
                    "smoothing"->{
                        SonarButton(No5Text.ui("Echte Pingprofile"),"Sonar exact ping profiles",scale,col,{ctl.exactPingProfiles(!d.exactPingProfiles)},enabled=!d.freeze,selected=d.exactPingProfiles,modifier=Modifier.fillMaxWidth())
                        listOf(false,true).forEach{linear->SonarButton(No5Text.ui(if(linear)"Linear"else"Nächster Nachbar"),"Sonar interpolation "+if(linear)"linear"else"nearest",scale,col,{ctl.interpolation(linear)},enabled=!d.freeze&&!d.exactPingProfiles,selected=!d.exactPingProfiles&&ctl.linearInterpolation==linear,modifier=Modifier.fillMaxWidth())}
                        SonarHint(No5Text.ui("Linear glättet Messwerte vor der gespeicherten Spaltenabbildung und der Zeitverdichtung. Fehlende Nachbarn bleiben unverändert. Messwerte bleiben erhalten."),scale,col)
                    }
                    "palette"->SonarPalette.entries.forEach{palette->SonarButton(sonarPaletteName(palette),"Sonar palette ${palette.name}",scale,col,{ctl.setPalette(palette)},selected=d.palette==palette,modifier=Modifier.fillMaxWidth())}
                    "history"->{
                        SonarSlider("Zeitbereich",d.historySeconds,10f..600f,10f," s",scale,col){ctl.display{v->v.copy(historySeconds=it)}}
                        val coverage=ctl.model.historyCoverage();val available=if(ctl.model.imageHistoryAggregated)ctl.model.imageAvailableSeconds()else coverage.visibleSeconds
                        SonarFact("Sichtbare Historie",No5Text.ui("Zeitfenster %s s · vorhanden %s s",sonarNumber(d.historySeconds),sonarNumber(minOf(d.historySeconds,available))),scale,col)
                        if(coverage.capacityLimited&&!ctl.model.imageHistoryAggregated)SonarHint(No5Text.ui("Pingspeicher begrenzt"),scale,col)
                    }
                    "relief"->{
                        SonarButton(No5Text.ui("2D ECHOGRAM"),"Sonar 2D echogram",scale,col,{ctl.relief(false)},selected=!ctl.reliefEnabled||d.exactPingProfiles,modifier=Modifier.fillMaxWidth())
                        SonarButton(No5Text.ui("3D ECHO RELIEF"),"Sonar 3D echo relief",scale,col,{ctl.relief(true)},enabled=!d.exactPingProfiles,selected=ctl.reliefEnabled&&!d.exactPingProfiles,modifier=Modifier.fillMaxWidth())
                        if(ctl.reliefEnabled&&!d.exactPingProfiles){
                            SonarHint(No5Text.ui("Relief zeigt Zeit, Bereich und Anzeigeamplitude; keine gemessene Bodentopografie."),scale,col)
                            SonarButton(No5Text.ui("Bereich ausrichten"),"Align sonar range with A-scope",scale,col,{ctl.alignReliefRange()},selected=ctl.reliefAligned,modifier=Modifier.fillMaxWidth())
                            SonarButton(No5Text.ui("Kamera zurücksetzen"),"Reset sonar relief camera",scale,col,{ctl.resetReliefCamera()},modifier=Modifier.fillMaxWidth())
                        }
                    }
                    "analysis-context","bottom","filter"->SonarAnalysisSettings(ctl,scale,col,selected)
                    else->SonarDiagnosticsSettings(ctl,scale,col,selected)
                }
            }
        }
    }
}
@Composable private fun SonarRangeSettings(ctl:SonarNativeController,unit:DepthUnit,scale:Float,col:SonarUiColors,night:Boolean){
    val d=ctl.snapshot.display
    val settings=ctl.depthProcessingSettings
    var startDraft by remember(ctl,unit,d.startDepthM){mutableStateOf(sonarNumber(unit.fromMeters(d.startDepthM),2))}
    var startEdited by remember(ctl,unit,d.startDepthM){mutableStateOf(false)}
    val startValue=sonarStartDepthInput(startDraft,unit)
    No5FormFieldRow(No5FormField(No5Text.ui("Anzeige ab")+" (${unit.symbol})",startDraft,"sonar-start-depth-input",
        keyboard=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal),
        onChange={startDraft=it;startEdited=true}),night,scale,designWidth=1064f,fieldX=702f)
    Row(horizontalArrangement=Arrangement.spacedBy((8*scale).dp)){
        SonarButton(No5Text.ui("Übernehmen"),"Sonar apply start depth",scale,col,
            {startValue?.let{ctl.startDepth(it)};startEdited=false},enabled=startEdited&&startValue!=null,
            reason=No5Text.ui(if(startEdited)"Gültige Tiefe ab 0 eingeben"else"Anfangstiefe unverändert"),modifier=Modifier.weight(1f))
        SonarButton(No5Text.ui("Ab Oberfläche"),"Sonar reset start depth",scale,col,
            {ctl.startDepth(0.0);startDraft=sonarNumber(0.0,2);startEdited=false},modifier=Modifier.weight(1f))
    }
    SonarFact("Tiefenausschnitt",No5Text.ui(if(startEdited&&startValue==null)"Gültige Tiefe ab 0 eingeben"else ctl.viewportStatusLabel()),scale,col)
    Row(horizontalArrangement=Arrangement.spacedBy((8*scale).dp)){
        SonarButton(No5Text.ui("Auto"),"Sonar auto range mode",scale,col,{ctl.autoRange(true)},selected=ctl.requestedRangeMode==SonarRangeMode.AUTO,modifier=Modifier.weight(1f))
        SonarButton(No5Text.ui("Manuell"),"Sonar manual range mode",scale,col,{ctl.autoRange(false)},selected=ctl.requestedRangeMode==SonarRangeMode.MANUAL,modifier=Modifier.weight(1f))
    }
    SonarFact("Tiefenverarbeitung",No5Text.ui(if(settings.mode==SonarDepthMode.SMOOTHED)"Ruhige Tiefe"else"Sonard direkt"),scale,col)
    Row(horizontalArrangement=Arrangement.spacedBy((8*scale).dp)){
        listOf(SonarDepthMode.DIRECT to "Sonard direkt",SonarDepthMode.SMOOTHED to "Ruhige Tiefe").forEach{(mode,label)->
            SonarButton(No5Text.ui(label),"Sonar depth mode ${mode.name}",scale,col,{ctl.depthProcessing(mode)},selected=settings.mode==mode,modifier=Modifier.weight(1f))
        }
    }
    SonarSlider("Tiefenfenster",settings.windowSeconds.toDouble(),1f..30f,1f," s",scale,col,
        enabled=settings.mode==SonarDepthMode.SMOOTHED){ctl.depthWindowSeconds(it.toInt())}
    val status=if(ctl.requestedRangeMode==SonarRangeMode.AUTO&&ctl.depthDisplay.valueM==null)No5Text.ui("Wartet auf Tiefe")else ctl.depthStatusLabel()
    SonarFact("Tiefenstatus",status,scale,col)
    // Keep the preset catalogue stable for the lifetime of this open sheet.
    val metric=remember(ctl){ctl.model.viewMetersPerBin!=null}
    val meters=ctl.model.viewMetersPerBin
    if(metric){
        val choices=remember(ctl){listOf(5.0,10.0,20.0,50.0,null)}
        choices.forEach{preset->val v=preset?:meters?.times(1023)
            SonarButton(if(preset==null)No5Text.ui("Voller Bereich")else "${sonarNumber(unit.fromMeters(preset))} ${unit.symbol}",
                "Sonar manual range ${preset?:"full"}",scale,col,{v?.let{ctl.range(it)}},
                enabled=v!=null&&meters!=null&&v<=meters*1023&&v>d.startDepthM,
                reason=No5Text.ui(if(v!=null&&v<=d.startDepthM)"Endtiefe muss unterhalb der Anfangstiefe liegen"else"Messbereich nicht verfügbar"),modifier=Modifier.fillMaxWidth())
        }
    }else listOf(0.0 to 1023.0,0.0 to 511.0,512.0 to 1023.0).forEach{(a,b)->
        SonarButton("${a.toInt()}–${b.toInt()} Bins","Sonar crop $a $b",scale,col,{ctl.crop(a,b)},
            enabled=d.startDepthM==0.0,reason=No5Text.ui("Tiefenskalierung nicht verfügbar"),modifier=Modifier.fillMaxWidth())}
    SonarButton(No5Text.ui(if(d.zoom)"Zoom 2× aus"else"Zoom 2×"),"Sonar 2x zoom",scale,col,{ctl.display{it.copy(zoom=!it.zoom)}},selected=d.zoom,modifier=Modifier.fillMaxWidth())
}
