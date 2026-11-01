package com.no5.helm.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.no5.header.artwork.No5WorkspaceView
import com.no5.helm.library.*
import com.no5.ui.No5DetailsDialog
import com.no5.ui.No5Text
import com.no5.ui.No5Workspace
import com.no5.ui.No5ActionCanvas
import com.no5.ui.no5Clickable

/** Approved import layout, populated only from the existing observed import owner. */
@Composable internal fun MapImportWorkspace(progress:ImportProgress?,busy:Boolean,night:Boolean,onBack:()->Unit,
    onCancel:()->Unit,onChoose:()->Unit,message:String?,sourceLabel:String?=null) {
    val context=LocalContext.current;val density=LocalDensity.current
    val regular=remember{Typeface.createFromAsset(context.assets,"no5-header/barlow_condensed_regular.ttf")}
    val bold=remember{Typeface.createFromAsset(context.assets,"no5-header/barlow_condensed_semibold.ttf")}
    val heavy=remember{Typeface.createFromAsset(context.assets,"no5-header/barlow_condensed_extrabold.ttf")}
    val ink=if(night)0xffed0000.toInt()else 0xff07121b.toInt()
    val muted=if(night)0xffa40000.toInt()else 0xff526b7b.toInt()
    val line=if(night)0xff550000.toInt()else 0xffb5c2c8.toInt()
    var details by remember{mutableStateOf(false)}
    val phase=when(progress?.activity){
        ImportActivity.RUNNING->No5Text.ui(if(progress.phase==ImportPhase.SAVING)"import.phase.saving"else progress.phase.title)
        ImportActivity.CANCELLING->No5Text.ui("import.state.cancelling")
        ImportActivity.CANCELLED->No5Text.ui("Import abgebrochen")
        ImportActivity.FAILED->No5Text.ui("Import fehlgeschlagen")
        ImportActivity.COMPLETED->No5Text.ui("import.state.completed")
        null->No5Text.ui("Kein Importauftrag aktiv.")
    }
    val description=when(progress?.activity){
        ImportActivity.COMPLETED->"Paket gespeichert. Neue Zellen sind noch nicht aktiviert."
        ImportActivity.CANCELLED->"Teilimport bereinigt. Vorhandene Karten bleiben erhalten."
        ImportActivity.CANCELLING->"Die laufende Datei wird beendet und bereinigt."
        ImportActivity.FAILED->progress.error?:"Import fehlgeschlagen"
        else->"Gesamtfortschritt unbekannt. Angaben gelten nur für diese Phase."
    }.let{No5Text.ui(it)}
    val terminal=progress?.activity in listOf(ImportActivity.COMPLETED,ImportActivity.CANCELLED,ImportActivity.FAILED)
    val actions=buildList{
        add(No5WorkspaceView.Action("Zum Kartenbestand",key="chart-import-library",action=onBack))
        if(progress?.activity==ImportActivity.COMPLETED)add(No5WorkspaceView.Action("Zellen auswählen",key="chart-import-select",action=onChoose))
        else if(busy&&progress?.canCancel==true)add(No5WorkspaceView.Action("Import abbrechen",key="chart-import-cancel",action=onCancel))
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("map-import-observed")){
        val scale=maxWidth.value/2000f
        No5Workspace("Kartenimport","Angaben gelten nur für diese Phase.",emptyList(),night,onBack,Modifier.fillMaxSize(),showBack=false)
        @Composable fun label(tag:String,text:String,y:Float,size:Float,width:Float=1280f,x:Float=360f,face:Typeface=regular,color:Int=ink){
            Canvas(Modifier.offset((x*scale).dp,((y-130-size*1.4f)*scale).dp)
                .size((width*scale).dp,(size*1.8f*scale).dp).testTag(tag).semantics{this.text=androidx.compose.ui.text.AnnotatedString(text)}){
                val n=drawContext.canvas.nativeCanvas;val u=this.size.width/width;n.save();n.scale(u,u)
                val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=face;textSize=size*density.fontScale;this.color=color}
                val count=p.breakText(text,true,width,null)
                val drawn=if(count<text.length)text.take((count-1).coerceAtLeast(0))+"…"else text
                n.drawText(drawn,0f,size*1.4f,p);n.restore()
            }
        }
        label("chart-import-source",sourceLabel?:No5Text.ui("Importauftrag"),324f,34f,face=bold)
        label("chart-import-phase",phase,423f,43f,face=heavy)
        if(busy&&!terminal)Canvas(Modifier.offset((360*scale).dp,(340*scale).dp).size((1280*scale).dp,(5*scale).dp)
            .semantics{progressBarRangeInfo=progress?.byteFraction?.takeIf{progress.activity==ImportActivity.RUNNING}?.let{ProgressBarRangeInfo(it,0f..1f)}?:ProgressBarRangeInfo.Indeterminate}){
            val n=drawContext.canvas.nativeCanvas;val p=Paint(Paint.ANTI_ALIAS_FLAG);val u=size.width/1280f
            n.save();n.scale(u,u);n.translate(-360f,-470f)
            p.color=line;n.drawRoundRect(360f,470f,1640f,475f,4f,4f,p)
            // An unknown total remains indeterminate. This segment is not a percentage.
            p.color=muted;val fraction=progress?.byteFraction?.takeIf{progress.activity==ImportActivity.RUNNING}
            n.drawRoundRect(360f,470f,360f+1280f*(fraction?:.1484375f),475f,4f,4f,p);n.restore()
        }
        Box(Modifier.offset((360*scale).dp,(376*scale).dp).size((1280*scale).dp,(126*scale).dp)
            .no5Clickable(feedbackColor=Color(muted),onClick={details=true}).semantics{contentDescription=description+". "+No5Text.ui("Details")}){
            Canvas(Modifier.fillMaxSize()){
                val n=drawContext.canvas.nativeCanvas;val u=size.width/1280f;n.save();n.scale(u,u)
                val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=regular;textSize=30f*density.fontScale;color=ink}
                var rest=description;var baseline=34f
                while(rest.isNotEmpty()&&baseline<=126f){
                    val fit=p.breakText(rest,true,1280f,null).coerceAtLeast(1)
                    val split=if(fit<rest.length)rest.lastIndexOf(' ',fit).takeIf{it>0}?:fit else fit
                    n.drawText(rest.take(split),0f,baseline,p);rest=rest.drop(split).trimStart();baseline+=30f*density.fontScale*1.35f
                };n.restore()
            }
        }
        if(!terminal){
            label("chart-import-current-label",No5Text.ui("Aktuelle Datei"),657f,25f,width=600f,color=muted)
            label("chart-import-file",progress?.currentFile?:"—",700f,31f,width=600f,face=bold)
            label("chart-import-count-label",No5Text.ui("Zellen geprüft"),657f,25f,width=590f,x=1050f,color=muted)
            label("chart-import-count",if(progress?.phase==ImportPhase.INDEXING)progress.processedFiles?.let{done->progress.totalFiles?.let{"$done / $it"}?:"$done"}?:"—"else"—",700f,31f,width=590f,x=1050f,face=bold)
        }
        // Import actions have two fixed columns, including a left-aligned single
        // Back action. The generic workspace's equal-fill row has different geometry.
        actions.forEachIndexed { index,action ->
            val x=360f+index*660f;var origin by remember{mutableStateOf(Offset(x,824f))}
            val text=No5Text.ui(action.label)
            No5ActionCanvas(Modifier.offset((x*scale).dp,(694*scale).dp)
                .size((620*scale).dp,(96*scale).dp)
                .onGloballyPositioned{val p=it.positionInRoot();val unit=with(density){(620*scale).dp.toPx()}/620f;origin=Offset(p.x/unit,p.y/unit)}
                .testTag("no5-action-${action.key}").semantics{contentDescription=text},
                enabled=action.enabled,night=night,designWidth=620f,designOrigin=origin,onClick=action.action){
                val n=drawContext.canvas.nativeCanvas;val u=size.width/620f;n.save();n.scale(u,u)
                val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=bold;textSize=27f*density.fontScale;color=ink}
                n.drawText(text,(620f-p.measureText(text))/2f,58f,p);n.restore()
            }
        }
    }
    if(details)No5DetailsDialog("Importstatus",night,{details=false},body=buildList{
        add(phase);progress?.let{p->add(No5Text.ui(p.detail));p.currentFile?.let{add(No5Text.ui("Datei: %s",it))}
            p.processedFiles?.let{done->add(No5Text.ui(if(p.phase==ImportPhase.INDEXING)"%s Zellen abgeschlossen"else"%s Dateien verarbeitet",p.totalFiles?.let{"$done / $it"}?:"$done"))}
            p.processedBytes?.let{done->add(No5Text.ui("%s verarbeitet · diese Phase",p.totalBytes?.let{"$done / $it B"}?:"$done B"))}
            p.error?.let{add(No5Text.ui(it))}}
        message?.let{add(No5Text.ui(it))}
    }.joinToString("\n"),tag="chart-import-details")
}
