package com.no5.helm.watchdog
/** Recheck the SAME requested component, never switch actions using an old proof. */
internal object RecoveryFaultRecheck {
    fun needed(gpuRequested:Boolean,rxRequested:Boolean,gpuFaultNow:Boolean,receiveSilenceNanos:Long):Boolean =
        if(gpuRequested)gpuFaultNow else rxRequested&&receiveSilenceNanos>=1_000_000_000L
}
