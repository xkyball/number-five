package com.no5.helm.watchdog

/** Bounded incident bookkeeping, independent of renderer/Android/clock.
 * A failed admission never retries. Lifecycle cancellation may rearm only an unconsumed
 * component attempt. A second observed fault after a consumed repair gets ONE last-resort request.
 */
internal class RecoveryIncidentPolicy {
    enum class Decision { NONE, REPAIR_GPU, REPAIR_RX, RESTART }
    private var requested=0
    private var consumed=0
    private var restartRequested=false
    fun synchronizeConsumedWindowMask(mask:Int){
        consumed=consumed or (if(mask and 2!=0)1 else 0) or (if(mask and 4!=0)2 else 0)
        requested=requested or consumed
    }
    fun observe(gpuFault:Boolean,rxFault:Boolean):Decision {
        if(gpuFault&&consumed and 1!=0 || rxFault&&consumed and 2!=0)
            return if(restartRequested)Decision.NONE else Decision.RESTART
        if(gpuFault&&requested and 1==0){requested=requested or 1;return Decision.REPAIR_GPU}
        if(rxFault&&requested and 2==0){requested=requested or 2;return Decision.REPAIR_RX}
        return Decision.NONE
    }
    fun claimLastResort():Boolean {if(restartRequested)return false;restartRequested=true;return true}
    fun cancelUnconsumedPending(){requested=requested and consumed}
    fun windowRepaired(){requested=requested and consumed}
}
