package com.no5.helm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.no5.header.artwork.No5WorkspaceView
import com.no5.helm.chart.*
import com.no5.ui.*
import kotlin.math.max

/** One existing confirmed style draft; task pages never create another profile or renderer. */
@Composable internal fun MapStyleWorkspace(candidate:ChartPresentation,active:ChartPresentation,busy:Boolean,error:String?,
 onChange:(ChartPresentation)->Unit,onMode:(PresentationMode)->Unit,onClose:()->Unit,onApply:()->Unit){
 var section by rememberSaveable{mutableStateOf("styles")};var page by rememberSaveable(candidate.mode){mutableIntStateOf(0)}
 fun back(){if(section=="styles")onClose()else section="styles"}
 BackHandler{back()}
 val night=MaterialTheme.colorScheme.surface.red<.15f
 val style=ChartStyles.require(candidate.styleId)
 BoxWithConstraints(Modifier.fillMaxSize()){
  val scale=maxWidth.value/2000f
  Box(Modifier.offset(y=(130*scale).dp).fillMaxWidth().height(minOf((808*scale).dp,(maxHeight-130.dp*scale).coerceAtLeast(0.dp)))){
   if(section!="styles"){
    val rows=when(section){
     "modes"->PresentationMode.entries.map{mode->No5WorkspaceView.Row(No5Text.ui(mapM1ModeTitle(mode)),selected=candidate.mode==mode,enabled=!busy,key="chart-style-mode-${mode.name}",control="check",action={onMode(mode);section="styles"})}
     "symbols"->ChartSymbolSet.entries.map{s->No5WorkspaceView.Row(No5Text.ui(s.title),selected=candidate.symbols==s,enabled=!busy&&s in style.symbolSets,key="chart-style-symbol-${s.id}",control="check",action={onChange(candidate.copy(symbols=s))})}
     else->listOf(false,true).map{on->No5WorkspaceView.Row(No5Text.ui(if(on)"An"else"Aus"),selected=candidate.landRelief==on,enabled=!busy&&style.allowsLandRelief,key="chart-style-relief-$on",control="check",action={onChange(candidate.copy(landRelief=on))})}
    }
    No5Workspace(No5Text.ui(if(section=="modes")"Darstellungsmodus"else if(section=="symbols")"Symbole"else"Relief"),"",rows,night,::back,Modifier.fillMaxSize())
   }else{
    val styles=ChartStyles.all.filter{it.mode==candidate.mode};val pages=max(1,(styles.size+3)/4);val current=page.coerceIn(0,pages-1)
    val controls=buildList{
     add(m1Back(::back));styles.drop(current*4).take(4).forEachIndexed{i,s->add(M1Control("no5-row-chart-style-${s.id}",mapM1StyleTitle(s),32f,234f+i*112,650f,selected=candidate.styleId==s.id,enabled=!busy,action={onChange(candidate.withStyle(s))}))}
     add(M1Control("no5-action-style-prev",m1("Zurück","Previous","Vorige"),32f,690f,260f,enabled=current>0,action={page=current-1}));add(M1Control("no5-action-style-next",m1("Weiter","Next","Volgende"),430f,690f,252f,enabled=current+1<pages,action={page=current+1}))
     add(M1Control("no5-row-chart-style-modes",m1("Modus","Mode","Modus")+": "+No5Text.ui(mapM1ModeTitle(candidate.mode)),714f,552f,600f,enabled=!busy,action={section="modes"}))
     add(M1Control("no5-row-chart-style-symbols",m1("Symbole","Symbols","Simbole")+": "+mapM1SymbolTitle(candidate.symbols),1330f,552f,630f,enabled=!busy,action={section="symbols"}))
     add(M1Control("no5-row-chart-style-land-relief",m1("Relief","Relief","Reliëf")+": "+if(style.allowsLandRelief)No5Text.ui(if(candidate.landRelief)"An"else"Aus")else m1("nicht unterstützt","not supported","nie ondersteun nie"),714f,664f,1246f,enabled=!busy&&style.allowsLandRelief,action={section="relief"}))
     addAll(m1Actions(No5WorkspaceView.Action(m1("Zurück","Back","Terug"),enabled=!busy,key="chart-style-close",action=onClose),No5WorkspaceView.Action(m1("Anwenden","Apply","Pas toe"),enabled=!busy&&candidate!=active,key="chart-style-apply",action=onApply)))
    }
    MapM1Surface(M1Scene(m1("Stil und Symbole","Style and symbols","Styl en simbole"),controls,paint={
     grid(714f,234f,1246f,290f,m1("SCHEMATISCHE KOORDINATENANSICHT","SCHEMATIC COORDINATE VIEW","SKEMATIESE KOÖRDINAATAANSIG"))
     text(m1("SCHEMATISCHE STILVORSCHAU","SCHEMATIC STYLE PREVIEW","SKEMATIESE STYLVOORSKOU"),744f,330f,32f,1180f,true)
     line(770f,430f,1400f,390f,accent);line(1400f,390f,1840f,450f)
     text(m1("Kein gerendertes Seekartenbild","Not a rendered chart","Nie 'n gelewerde seekaartbeeld nie"),744f,491f,27f,1180f,color=muted)
     text("${current+1} / $pages",312f,748f,27f,100f)
    },notice=error),night)
   }
  }
 }
}

internal fun mapM1ModeTitle(mode:PresentationMode)=when(mode){PresentationMode.DAY->"Tag";PresentationMode.RED_NIGHT->"Rotnacht";PresentationMode.GRAY_COMPARISON->"Grau"}

// These four are the approved style names, not translated actions.
internal fun mapM1StyleTitle(style:ChartStyle)=when(style.id){"default"->"Default";"glacier","signal","paper"->style.title;else->No5Text.ui(style.title)}
internal fun mapM1SymbolTitle(symbols:ChartSymbolSet)=when(symbols){ChartSymbolSet.DEFAULT->m1("Standard","Default","Standaard");ChartSymbolSet.BIG->m1("Groß","Large","Groot");else->symbols.title}
