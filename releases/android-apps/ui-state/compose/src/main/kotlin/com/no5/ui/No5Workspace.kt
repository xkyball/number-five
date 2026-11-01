package com.no5.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.no5.header.artwork.No5WorkspaceView

/** The D03 workspace body, shared with HOME. Its coordinates start at header y=130.
 * Native Views own both their ink and touch/accessibility bounds; no screenshot overlay. */
@Composable
fun No5Workspace(title:String,subtitle:String,rows:List<No5WorkspaceView.Row>,night:Boolean,
                 onBack:()->Unit,modifier:Modifier=Modifier,showBack:Boolean=true,
                 actions:List<No5WorkspaceView.Action> = emptyList(),rawSubtitle:Boolean=false,
                 liveSubtitle:Boolean=false,stateKey:Any?=null,initialScrollY:Int=0,onScroll:((Int,Boolean)->Unit)?=null) {
    val context=LocalContext.current
    val fontScale=LocalDensity.current.fontScale
    val latestBack by rememberUpdatedState(onBack)
    key(night,UiLanguage.current(context),title,if(liveSubtitle)null else subtitle,showBack,fontScale,rawSubtitle,stateKey) {
        AndroidView(factory={
            WorkspaceHost(it,No5WorkspaceView(it,night,title,subtitle,rows,{latestBack()},showBack,fontScale,actions,rawSubtitle).apply{restoreScroll(initialScrollY)})
        },modifier=modifier,update={it.workspace.renderRows(rows);it.workspace.renderActions(actions);it.workspace.renderSubtitle(subtitle,rawSubtitle);it.workspace.observeScroll(onScroll)})
    }
}

private class WorkspaceHost(context:Context,val workspace:No5WorkspaceView):ViewGroup(context) {
    init {addView(workspace);clipChildren=true;clipToPadding=true}
    override fun onMeasure(widthSpec:Int,heightSpec:Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec),MeasureSpec.getSize(heightSpec))
        val scale=(measuredWidth/2000f).takeIf{it>0}?:1f
        workspace.measure(MeasureSpec.makeMeasureSpec(2000,MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((measuredHeight/scale).toInt().coerceAtLeast(0),MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        workspace.pivotX=0f;workspace.pivotY=0f
        workspace.scaleX=width/2000f;workspace.scaleY=workspace.scaleX
        workspace.layout(0,0,2000,workspace.measuredHeight)
    }
}
