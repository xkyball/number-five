package com.no5.helm.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.no5.header.artwork.No5MomentFeedback
import com.no5.helm.chart.ChartControlResult
import com.no5.helm.chart.ChartEngineStatus
import org.lumina.control.commands.v1.CommandRing

/** One replaceable UI observation, never a command queue or an additional input owner.
 * Admission writes primitives and posts one preallocated task. Receipt polling and text live on UI. */
internal class MapKnobFeedback(private val status:()->ChartEngineStatus,private val positionGeneration:()->Long,
    private val read:(Long,ChartControlResult)->Boolean,private val intentOnly:(Long)->Boolean,
    private val label:(ChartKnobInput)->String,private val clock:()->Long=SystemClock::uptimeMillis):AutoCloseable {
    private val lock=Any()
    private val handler=Handler(Looper.getMainLooper())
    private val result=ChartControlResult()
    private var input=ChartKnobInput.LEFT_CW
    private var id=0L;private var source=0L;private var surface=0L;private var generation=0L
    private var serial=0L;private var observedSerial=0L;private var submittedAt=0L
    private var posted=false;private var closed=false
    private var ticket=0L
    private val drainTask=Runnable{drain()}
    private val retireTask=Runnable{No5MomentFeedback.invalidate(this)}
    fun submitted(input:ChartKnobInput,id:Long){
        val schedule=synchronized(lock){
            if(closed)return
            val current=status()
            this.input=input;this.id=id;source=current.chartSourceGeneration;surface=current.surfaceGeneration
            generation=positionGeneration();submittedAt=clock();serial++
            if(posted)false else{posted=true;true}
        }
        if(schedule&&!handler.post(drainTask))synchronized(lock){posted=false}
    }
    private fun drain(){
        val expected:Long;val command:Long;val selected:ChartKnobInput;val sourceFence:Long;val surfaceFence:Long;val positionFence:Long;val stamp:Long
        synchronized(lock){
            posted=false
            if(closed)return
            expected=serial;command=id;selected=input;sourceFence=source;surfaceFence=surface;positionFence=generation;stamp=submittedAt
        }
        fun current():Boolean {
            val state=status()
            return state.chartSourceGeneration==sourceFence&&state.surfaceGeneration==surfaceFence&&positionGeneration()==positionFence&&
                synchronized(lock){!closed&&serial==expected}
        }
        if(!current()){No5MomentFeedback.invalidate(this);return}
        if(observedSerial!=expected){observedSerial=expected;ticket=No5MomentFeedback.begin(this,label(selected),expected)}
        // Receipt reads and header observers execute outside the admission lock.
        val phase=when{
            command<=0->No5MomentFeedback.Phase.FAILED
            !read(command,result)->if(clock()-stamp>=2000)No5MomentFeedback.Phase.UNKNOWN else null
            result.state==CommandRing.State.REJECTED.ordinal||result.state==CommandRing.State.CANCELLED.ordinal->No5MomentFeedback.Phase.FAILED
            result.state==CommandRing.State.UNKNOWN.ordinal||result.state==CommandRing.State.SUPERSEDED.ordinal->No5MomentFeedback.Phase.UNKNOWN
            result.state==CommandRing.State.APPLIED.ordinal&&intentOnly(command)->No5MomentFeedback.Phase.EXECUTED
            result.state==CommandRing.State.APPLIED.ordinal&&result.revision>=0&&
                result.appliedRevision>=result.revision&&result.presentedRevision>=result.revision->No5MomentFeedback.Phase.EXECUTED
            clock()-stamp>=2000->No5MomentFeedback.Phase.UNKNOWN
            else->null
        }
        if(!current()){No5MomentFeedback.invalidate(this);return}
        if(phase!=null)No5MomentFeedback.complete(ticket,phase)
        else {
            val schedule=synchronized(lock){if(closed||serial!=expected||posted)false else{posted=true;true}}
            if(schedule)handler.postDelayed(drainTask,50)
        }
    }
    override fun close(){
        synchronized(lock){if(closed)return;closed=true;posted=false}
        handler.removeCallbacks(drainTask)
        if(Looper.myLooper()==Looper.getMainLooper())No5MomentFeedback.invalidate(this)else handler.post(retireTask)
    }
}
