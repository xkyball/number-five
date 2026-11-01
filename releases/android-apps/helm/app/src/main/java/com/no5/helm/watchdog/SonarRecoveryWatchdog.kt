package com.no5.helm.watchdog

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.no5.domain.SonarOriginClockProof
import no5.sonar.windowrecovery.RecoveryBudgetPolicy
import no5.sonar.windowrecovery.RecoveryBudgetPolicy.*

/** Outside the renderer. One queued ledger operation; observation never refreshes a healthy view.
 * The UI/source/window adapter supplies an independently measured origin proof for EVERY action.
 * No source/TX, service, Pi, Lab or AVD control is available here.
 */
private object RecoveryLedgerOwner {
    private val thread=HandlerThread("no5-recovery-ledger").apply{start()}
    val writer=Handler(thread.looper)
    private var initialized=false
    private var policy:RecoveryBudgetPolicy?=null
    fun load(context:Context):RecoveryBudgetPolicy {
        check(Looper.myLooper()==writer.looper)
        if(!initialized){initialized=true;policy=RecoveryBudgetPolicy(RecoveryLedger(context.applicationContext))}
        return checkNotNull(policy){"RECOVERY_LEDGER_FAILED_CLOSED"}
    }
}
internal class SonarRecoveryWatchdog(private val activity:Activity,private val windowId:String,
    private val admissible:()->Boolean,private val sourceContext:()->String?):AutoCloseable {
    private val main=Handler(Looper.getMainLooper())
    private val writer=RecoveryLedgerOwner.writer
    private var policy:RecoveryBudgetPolicy?=null
    private val ownIdentity=try{OwnProcessIdentity.capture(activity)}catch(_:Exception){null}
    private var pending=false
    private var closed=false
    @Volatile var diagnostic="INITIALIZING";private set
    @Volatile var consumedMask=0;private set
    @Volatile var appRestartConsumed=false;private set
    @Volatile var failures=0;private set
    init { writer.post {
        try{policy=RecoveryLedgerOwner.load(activity.applicationContext);publish();diagnostic="READY"}
        catch(_:Exception){failures++;diagnostic="LEDGER_UNAVAILABLE_FAIL_CLOSED"}
    } }
    private fun publish(){policy?.snapshot()?.let{consumedMask=if(it.window==windowId)it.windowMask else 0;appRestartConsumed=it.appRestartConsumed}}
    fun request(action:Action,fault:Fault,proof:SonarOriginClockProof,complete:(Boolean)->Unit) {
        check(Looper.myLooper()==main.looper)
        val source=sourceContext();val age=proof.upperAgeMs(android.os.SystemClock.elapsedRealtimeNanos())
        if(closed||pending||source==null||age==null||!admissible()||ownIdentity?.stillOwnFocused(activity)!=true){
            diagnostic="ACTION_REJECTED_ADMISSION";complete(false);return
        }
        val evidence=Evidence(windowId,source,true,true,true,true,age,SystemClock.elapsedRealtimeNanos(),fault)
        pending=true
        writer.post {
            val permitted=try{policy?.consume(action,evidence,windowId,source,SystemClock.elapsedRealtimeNanos())==true}
                catch(_:Exception){failures++;diagnostic="LEDGER_WRITE_FAILED";false}
            publish()
            main.post {
                pending=false
                // The durable budget remains consumed if focus/context/freshness changed during fsync.
                val valid=permitted&&!closed&&admissible()&&sourceContext()==source&&
                    RecoveryBudgetPolicy.fresh(evidence,SystemClock.elapsedRealtimeNanos())&&
                    proof.upperAgeMs(android.os.SystemClock.elapsedRealtimeNanos())!=null&&ownIdentity?.stillOwnFocused(activity)==true
                diagnostic=if(valid)"PERMIT_${action.name}" else "ACTION_DENIED_OR_EXPIRED"
                complete(valid)
            }
        }
    }
    /** Only after a failed component attempt, a NEW proof and durable APP_RESTART consumption. */
    fun restartOwnActivity(proof:SonarOriginClockProof, stillRequired:()->Boolean) {
        request(Action.APP_RESTART,Fault.COMPONENT_RECOVERY_FAILED,proof){granted->
            if(granted&&!stillRequired()){diagnostic="APP_RESTART_CANCELLED_FAULT_RESOLVED";return@request}
            if(granted&&ownIdentity?.stillOwnFocused(activity)==true&&proof.upperAgeMs(android.os.SystemClock.elapsedRealtimeNanos())!=null){
                try {
                    diagnostic="APP_RESTART_CONSUMED_QUALIFICATION_FAIL"
                    val performed=OwnActivityRestart.armAndSignal(activity,
                        android.content.ComponentName(activity,activity.javaClass),ownIdentity){
                        !closed&&admissible()&&stillRequired()&&ownIdentity.stillOwnFocused(activity)&&proof.upperAgeMs(android.os.SystemClock.elapsedRealtimeNanos())!=null
                    }
                    if(!performed)diagnostic="APP_RESTART_CANCELLED_CONTEXT_CHANGED"
                }catch(_:Exception){failures++;diagnostic="APP_RESTART_FAILED_BUDGET_REMAINS_CONSUMED"}
            }
        }
    }
    fun line()="No5SonarWatchdog: v=1 mask=$consumedMask appRestartConsumed=$appRestartConsumed pending=$pending failures=$failures outcome=$diagnostic"
    override fun close(){closed=true} // One process-wide writer survives Window replacement; no stale budget copy.
}
