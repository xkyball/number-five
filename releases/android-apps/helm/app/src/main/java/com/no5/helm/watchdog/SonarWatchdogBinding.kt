package com.no5.helm.watchdog

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.no5.helm.SonarNativeController
import com.no5.domain.SonarOriginClockProof
import no5.sonar.windowrecovery.RecoveryBudgetPolicy.*
import no5.sonar.windowrecovery.WindowRecoveryBinding

/** Lifecycle observer outside the plot; only a measured missing presentation or RX silence triggers a proof.
 * Healthy frames do no PixelCopy/clock request/relayout. Pausing cancels admission, not Window baseline/budgets.
 */
internal class SonarWatchdogBinding(activity:Activity,private var controller:SonarNativeController):AutoCloseable {
    val window=WindowRecoveryBinding(activity.window)
    private val main=Handler(Looper.getMainLooper())
    private val budget=SonarRecoveryWatchdog(activity,window.instanceId(),{window.isEligible()},{window.currentContext()})
    private val presentationGaps=PresentationGapWatch()
    private var presentationSubscription=controller.observeGpuPresentations{stamp,at->presentationGaps.presented(stamp.context,at)}
    private var eligible=false
    private var closed=false
    private var scheduled=false
    private var pending=false
    private var proofSerial=0L
    private var ping:Long?=null
    private var original:Long?=null
    private var context:String?=null
    private val incidents=RecoveryIncidentPolicy()
    @Volatile var gpuFailures=0;private set
    private var escalate=false
    private var componentDeadline=0L
    private var componentHardDeadline=0L
    private var previousPresentation=0L
    private var previousReceive=0L
    private var missingViewSinceNanos=0L
    private var suspectedGpu=false
    private var suspectedRx=false
    @Volatile var failures=0;private set
    @Volatile var diagnostic="WAITING_FOR_LIVE_WINDOW";private set
    private var proofHandoffPhase="NONE"
    private var proofHandoffWaitNs:Long?=null
    private var proofUpperAtCallbackMs:Long?=null
    private var proofUpperAtGateMs:Long?=null
    private var proofClientAtCallback="No5SonarOriginProof: v=1 state=NOT_OBSERVED"
    init {
        window.setProofProvider { id,stamp,done->controller.requestRecoveryOriginClockProof(id,stamp,done) }
        window.setRepairBudget { proof,done->
            budget.request(Action.WINDOW_RELAYOUT,Fault.MISSING_WINDOW_SURFACE,proof){granted->
                if(granted){incidents.windowRepaired();suspectedGpu=false;suspectedRx=false;componentDeadline=System.nanoTime()+1_000_000_000L;componentHardDeadline=componentDeadline+1_000_000_000L;escalate=true}
                done.accept(granted)
            }
        }
    }
    fun bindController(value:SonarNativeController){
        if(controller===value)return
        pause();proofSerial++;pending=false;presentationSubscription.close();controller=value;presentationSubscription=controller.observeGpuPresentations{stamp,at->presentationGaps.presented(stamp.context,at)};context=null;ping=null;original=null;escalate=false
        // A source switch never creates another Window or resets durable budgets.
    }
    fun progress(source:String,id:Long?,admitted:Boolean,stamp:Long) {
        if(closed)return
        context=source;ping=id;original=stamp;eligible=admitted;presentationGaps.admission(source,admitted,System.nanoTime())
        window.progress(source,id,admitted,stamp)
        if(admitted&&!scheduled){scheduled=true;main.postDelayed(check,250)}
        if(!admitted)pause()
    }
    fun pause(){presentationGaps.admission(context?:"",false,System.nanoTime());eligible=false;missingViewSinceNanos=0L;if(pending)incidents.cancelUnconsumedPending();proofSerial++;pending=false;window.pause();main.removeCallbacks(check);scheduled=false}
    private val check=object:Runnable {
        override fun run(){
            scheduled=false
            if(closed||!eligible||!window.isEligible())return
            val now=System.nanoTime();val views=controller.activeGpuViews()
            if(views.isEmpty()){if(missingViewSinceNanos==0L)missingViewSinceNanos=now}else missingViewSinceNanos=0L
            val presentation=views.maxOfOrNull{it.lastPresentedAtNanos}?:0L
            val receive=controller.readReceiveSilenceNanos()
            if(escalate&&now>=componentDeadline&&!window.hasPendingRecoveryWork()){
                val recovered=when{
                    suspectedGpu->presentation>previousPresentation&&views.none{it.hasFailure()}
                    suspectedRx->receive in 0 until previousReceive
                    else->window.lastDiagnostic=="RECOVERY_FRAME_COMMITTED_AND_SURFACE_VALID"
                }
                if(recovered){escalate=false;diagnostic="COMPONENT_RECOVERED"}
                else if(suspectedGpu&&now<componentHardDeadline&&views.any{!it.hasFailure()&&it.makingForwardProgress(now)}){
                    // One absolute2s component boundary. Genuine work may complete;
                    // neither progress nor a replacement View can erase the live gap.
                    componentDeadline=minOf(componentHardDeadline,now+250_000_000L)
                }else if(!pending){escalate=false;failures++;diagnostic="COMPONENT_RECOVERY_FAILED";requestProof(true,suspectedGpu,suspectedRx)}
            }else if(!pending&&!escalate&&!window.hasPendingRecoveryWork()){
                val gpu=views.any{it.hasFailure()||it.missingPresentation(now)}||missingViewSinceNanos>0L&&now-missingViewSinceNanos>=1_000_000_000L
                val rx=receive>=1_000_000_000L
                incidents.synchronizeConsumedWindowMask(budget.consumedMask)
                when(incidents.observe(gpu,rx)){
                    RecoveryIncidentPolicy.Decision.NONE -> Unit
                    RecoveryIncidentPolicy.Decision.RESTART -> {
                        if(gpu&&views.any{it.hasFailure()})gpuFailures++
                        failures++;diagnostic="COMPONENT_REPAIR_BUDGET_EXHAUSTED";requestProof(true,gpu,rx)
                    }
                    RecoveryIncidentPolicy.Decision.REPAIR_GPU -> {
                        if(views.any{it.hasFailure()})gpuFailures++
                        previousPresentation=presentation;previousReceive=receive;requestProof(false,true,false)
                    }
                    RecoveryIncidentPolicy.Decision.REPAIR_RX -> {
                        previousPresentation=presentation;previousReceive=receive;requestProof(false,false,true)
                    }
                }
            }
            if(!closed&&eligible){scheduled=true;main.postDelayed(this,250)}
        }
    }
    private fun requestedFaultStillPresent(gpu:Boolean,rx:Boolean):Boolean {
        val now=System.nanoTime();val views=controller.activeGpuViews()
        val gpuFault=views.any{it.hasFailure()||it.missingPresentation(now)}||
            views.isEmpty()&&missingViewSinceNanos>0L&&now-missingViewSinceNanos>=1_000_000_000L
        return RecoveryFaultRecheck.needed(gpu,rx,gpuFault,controller.readReceiveSilenceNanos())
    }
    private fun resolvedBeforeAction(){incidents.cancelUnconsumedPending();diagnostic="COMPONENT_RESUMED_BEFORE_ACTION"}
    private fun requestProof(restart:Boolean,gpu:Boolean,rx:Boolean){
        if(restart&&!incidents.claimLastResort())return
        val expected=context?:return;val id=ping?:return;val stamp=original?:return
        val lease=window.beginComponentInspection()
        if(lease==0L){incidents.cancelUnconsumedPending();return}
        val serial=++proofSerial;pending=true
        val queued=controller.requestRecoveryOriginClockProof(id,stamp){proof->
            val callbackAt=android.os.SystemClock.elapsedRealtimeNanos()
            val callbackUpper=proof?.upperAgeMs(callbackAt)
            val clientAtCallback=controller.originClockDiagnosticLine()
            deliverOriginProofOnMain(main, Runnable handoff@{
                if(closed||serial!=proofSerial)return@handoff
                pending=false
                val sameContextAtGate=!closed&&eligible&&context==expected
                val windowEligibleAtGate=sameContextAtGate&&window.isEligible()
                // Keep the authoritative bound AFTER the original lifecycle/
                // context checks. Diagnostics cannot shorten its elapsed time.
                val gateAt=android.os.SystemClock.elapsedRealtimeNanos()
                val gateUpper=if(windowEligibleAtGate)proof?.upperAgeMs(gateAt)else null
                proofHandoffWaitNs=try{Math.subtractExact(gateAt,callbackAt)}catch(_:ArithmeticException){null}
                proofUpperAtCallbackMs=callbackUpper;proofUpperAtGateMs=gateUpper;proofClientAtCallback=clientAtCallback
                proofHandoffPhase=when{
                    closed->"CLOSED"
                    !eligible->"NO_LONGER_ELIGIBLE"
                    context!=expected->"SOURCE_CONTEXT_CHANGED"
                    !windowEligibleAtGate->"WINDOW_NOT_ELIGIBLE"
                    proof==null->"SOURCE_RETURNED_NULL"
                    gateUpper==null->"BOUND_AFTER_MAIN_HANDOFF"
                    else->"BOUND_VALID_AT_GATE"
                }
                if(!windowEligibleAtGate||proof==null||gateUpper==null){
                    android.util.Log.w("No5OriginProof",proofHandoffDiagnosticLine()+" "+clientAtCallback)
                    window.endComponentInspection(lease);failures++;diagnostic="RECOVERY_ORIGIN_PROOF_REJECTED";return@handoff
                }
                if(restart){
                    window.endComponentInspection(lease)
                    val stillRequired=if(gpu||rx)requestedFaultStillPresent(gpu,rx)
                        else window.lastDiagnostic!="RECOVERY_FRAME_COMMITTED_AND_SURFACE_VALID"
                    if(!stillRequired){resolvedBeforeAction();return@handoff}
                    escalate=false;budget.restartOwnActivity(proof){
                        if(gpu||rx)requestedFaultStillPresent(gpu,rx)
                        else window.lastDiagnostic!="RECOVERY_FRAME_COMMITTED_AND_SURFACE_VALID"
                    };return@handoff
                }
                if(!requestedFaultStillPresent(gpu,rx)){window.endComponentInspection(lease);resolvedBeforeAction();return@handoff}
                // A Window loss is repaired by the Window adapter, never by a blind EGL reset.
                window.inspectBackingSurface(lease,proof,expected){result->
                    if(result==no5.sonar.windowrecovery.BackingSurfaceProbe.Result.RECOVERY_REQUESTED){diagnostic="WINDOW_REPAIR_PRECEDES_COMPONENT";return@inspectBackingSurface}
                    if(result!=no5.sonar.windowrecovery.BackingSurfaceProbe.Result.VALID){failures++;diagnostic="COMPONENT_WINDOW_NOT_VALID";return@inspectBackingSurface}
                    if(!requestedFaultStillPresent(gpu,rx)){resolvedBeforeAction();return@inspectBackingSurface}
                    val action=if(gpu)Action.VIEW_REBUILD else Action.RX_RECONNECT
                    val fault=if(gpu)Fault.PRESENTATION_MISSING else Fault.RX_STALLED
                    budget.request(action,fault,proof){granted->
                        if(!granted){failures++;diagnostic="COMPONENT_PERMIT_DENIED";return@request}
                        // fsync can complete after a legitimate new presentation/RX.
                        // Keep the durable permit consumed, but do not reset a healthy component.
                        if(!requestedFaultStillPresent(gpu,rx)){resolvedBeforeAction();return@request}
                        suspectedGpu=gpu;suspectedRx=rx&&!gpu
                        if(gpu){controller.rebuildOwnedGpuViews()}
                        else {if(!controller.reconnectOwnReadTransport()){failures++;diagnostic="RX_RECONNECT_REJECTED"}}
                        componentDeadline=System.nanoTime()+1_000_000_000L;componentHardDeadline=componentDeadline+1_000_000_000L;escalate=true
                        diagnostic="COMPONENT_${action.name}_REQUESTED"
                    }
                }
            })
        }
        if(!queued){window.endComponentInspection(lease);pending=false;failures++;diagnostic="RECOVERY_ORIGIN_PROOF_UNAVAILABLE"}
    }
    private fun proofHandoffDiagnosticLine():String = "No5SonarOriginHandoff: v=1 phase=$proofHandoffPhase callbackWaitNs=${proofHandoffWaitNs?:"unknown"} upperAtCallbackMs=${proofUpperAtCallbackMs?:"unknown"} upperAtGateMs=${proofUpperAtGateMs?:"unknown"}"
    fun gpuDiagnosticLine()=controller.gpuDiagnosticLine()+"\n"+controller.originClockDiagnosticLine()+"\n"+proofHandoffDiagnosticLine()+"\n"+proofClientAtCallback.replace("No5SonarOriginProof:","No5SonarOriginAtCallback:")
    fun diagnosticLine()="${budget.line()} componentFailures=$failures gpuFailures=$gpuFailures componentOutcome=$diagnostic presentationGapMaxNs=${presentationGaps.maximumNs(System.nanoTime())} presentationClockFailures=${presentationGaps.failures}"
    override fun close(){if(closed)return;closed=true;pause();presentationSubscription.close();window.close();budget.close()}
}
