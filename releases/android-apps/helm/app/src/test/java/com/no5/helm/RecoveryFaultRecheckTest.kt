package com.no5.helm
import com.no5.helm.watchdog.*
import no5.sonar.windowrecovery.WindowDrawPolicy
import org.junit.Assert.*
import org.junit.Test
class RecoveryFaultRecheckTest {
 @Test fun newPresentationDuringProofPreventsGpuReset(){assertFalse(RecoveryFaultRecheck.needed(true,false,false,0))}
 @Test fun newRxDuringProofPreventsReconnect(){assertFalse(RecoveryFaultRecheck.needed(false,true,false,0))}
 @Test fun gpuProofCannotBeReusedForDifferentRxFault(){assertFalse(RecoveryFaultRecheck.needed(true,false,false,1_500_000_000))}
 @Test fun stillFailedSameComponentsRemainEligible(){assertTrue(RecoveryFaultRecheck.needed(true,false,true,0));assertTrue(RecoveryFaultRecheck.needed(false,true,false,1_000_000_000));assertFalse(RecoveryFaultRecheck.needed(false,true,false,999_999_999));assertFalse(RecoveryFaultRecheck.needed(false,true,false,-1))}
 @Test fun windowDrawWhileOriginProofPendingCancelsProbe(){
  val p=WindowDrawPolicy();p.drawn(0);p.observedProgress("source",1,true,10);p.observedProgress("source",2,true,100)
  assertTrue(p.stillMissingAfterProof(1,1,1100));p.drawn(1150)
  assertFalse(p.stillMissingAfterProof(1,2,1200))
  p.observedProgress("source",3,true,1300);assertTrue(p.stillMissingAfterProof(2,2,2200))
 }
 @Test fun pauseAndExpiredLocalProgressNeverTurnIntoLateWindowAction(){
  val p=WindowDrawPolicy();p.drawn(0);p.observedProgress("source",1,true,10);p.observedProgress("source",2,true,100)
  assertFalse(p.stillMissingAfterProof(1,1,2101));p.pause();assertFalse(p.stillMissingAfterProof(1,1,1100))
 }
}
