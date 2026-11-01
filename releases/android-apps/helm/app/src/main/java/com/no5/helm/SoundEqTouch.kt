package com.no5.helm

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import com.no5.domain.SoundEqPoint
import kotlin.math.*

/** Stage coordinates, including a 48-pixel margin outside the graph for boundary handles. */
internal data class SoundEqTouchTarget(val point:SoundEqPoint,val low:Boolean?=null,val lock:Boolean=false)
internal object SoundEqTouch {
    fun x(f:Double,w:Float)=(ln(f.coerceIn(20.0,20000.0)/20)/ln(1000.0)*w).toFloat()
    fun y(g:Double,h:Float,range:Double)=((range-g)/(2*range)*h).toFloat()
    fun target(points:List<SoundEqPoint>,selected:String?,at:Offset,w:Float,h:Float,range:Double,pending:SoundEqPoint?=null):SoundEqTouchTarget? {
        // Nearest visible control wins, regardless of draw order or expanded semantics bounds.
        // Selected width handles are candidates even when locked: dragging one must never move a point.
        // The pending ring is an additional visible point, never a replacement for
        // the confirmed point. Width/lock controls exist only on the confirmed curve.
        val ring=pending?.takeIf{p->points.any{it.id==p.id}}?.let{listOf(SoundEqTouchTarget(it))}.orEmpty()
        val candidates=points.map{SoundEqTouchTarget(it)}+ring+points.firstOrNull{it.id==selected}?.let{
            listOf(SoundEqTouchTarget(it,true),SoundEqTouchTarget(it,false))+
                if(y(it.gainDb,h,range)>=75)listOf(SoundEqTouchTarget(it,lock=true))else emptyList()
        }.orEmpty()
        return candidates.mapNotNull{t->
            val f=when(t.low){true->t.point.lowHz;false->t.point.highHz;null->t.point.frequencyHz}
            val dx=abs(x(f,w)-at.x);val dy=abs(y(t.point.gainDb,h,range)-(if(t.lock)49 else 0)-at.y)
            if(t.lock && hypot(dx,dy)<=28 || !t.lock && (t.low!=null && dx<=48 && dy<=40 || t.low==null && hypot(dx,dy)<=40))
                t to hypot(dx,dy) else null
        }.minWithOrNull(compareBy<Pair<SoundEqTouchTarget,Float>>{it.second}.thenBy{if(it.first.low==null)1 else 0})?.first
    }
    fun moved(target:SoundEqTouchTarget,delta:Offset,w:Float,h:Float,range:Double):SoundEqPoint {
        val p=target.point
        if(target.lock)return p
        val ratio=1000.0.pow(delta.x/w.toDouble())
        return target.low?.let{low->p.withBandEdge((if(low)p.lowHz else p.highHz)*ratio,low)}
            ?:p.move(p.frequencyHz*ratio,((p.gainDb-delta.y/h*2*range)*10).roundToInt()/10.0)
    }
}

@Composable internal fun Modifier.soundEqTouch(ctl:AudioSoundUiController,w:Float,h:Float,unit:Float,range:Double):Modifier {
    val currentRange=rememberUpdatedState(range)
    val focused=LocalWindowInfo.current.isWindowFocused
    return pointerInput(ctl,ctl.eqCurveEditable,unit,focused,ctl.controlInk.configGeneration,ctl.controlInk.sourceGeneration){
        var lastTapTime=Long.MIN_VALUE
        var lastTap=Offset.Unspecified
        awaitEachGesture {
            val down=awaitFirstDown()
            if(!ctl.eqCurveEditable || !focused)return@awaitEachGesture
            val at=down.position/unit-Offset(48f,48f)
            val startRange=currentRange.value
            val target=SoundEqTouch.target(ctl.preset?.eqPoints.orEmpty(),ctl.selectedPoint,at,w,h,startRange,ctl.eqTarget)
            var dragging=false
            var cancelled=false
            var ended=false
            var endTime=down.uptimeMillis
            ctl.beginEqGesture(startRange)
            try {
            // Resolve at initial contact, before touch slop. Never re-pick from the moving graph.
            do {
                val event=awaitPointerEvent()
                val change=event.changes.firstOrNull{it.id==down.id}
                if(event.changes.any{it.id!=down.id && (it.pressed || it.previousPressed)} || change==null || change.isConsumed){
                    cancelled=true
                    break
                }
                endTime=change.uptimeMillis
                val delta=(change.position-down.position)/unit
                if(!dragging && delta.getDistance()>min(viewConfiguration.touchSlop/unit,4f)){
                    dragging=true
                    lastTapTime=Long.MIN_VALUE
                    target?.let{ctl.choosePoint(it.point.id)}
                }
                if(dragging){
                    target?.takeUnless{it.lock}?.let{point->traceEqStage("No5.EQ.TouchSample"){
                        val next=SoundEqTouch.moved(point,delta,w,h,startRange)
                        traceEqStage("No5.EQ.TouchUpdate"){ctl.updatePoint(next)}
                    }}
                    change.consume()
                }
                ended=!change.pressed
            }while(!ended)
            if(!cancelled && !dragging && ended){
                if(target!=null){
                    ctl.choosePoint(target.point.id)
                    if(target.lock)ctl.preset?.eqPoints?.firstOrNull{it.id==target.point.id}?.let{ctl.updatePoint(it.copy(widthLocked=!it.widthLocked))}
                    lastTapTime=Long.MIN_VALUE
                }
                else if(at.x in 0f..w && at.y in 0f..h){
                    val doubleTap=lastTapTime!=Long.MIN_VALUE && endTime-lastTapTime in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis &&
                        lastTap.isSpecified && (at-lastTap).getDistance()<40f
                    if(ctl.addingPoint || doubleTap){
                        ctl.placeNeutralPoint(20.0*1000.0.pow(at.x/w.toDouble()))
                        lastTapTime=Long.MIN_VALUE
                    }else{lastTapTime=endTime;lastTap=at}
                }
            }
            } finally { ctl.endEqGesture() }
        }
    }
}
