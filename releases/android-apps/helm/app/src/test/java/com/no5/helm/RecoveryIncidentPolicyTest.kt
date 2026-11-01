package com.no5.helm
import com.no5.helm.watchdog.RecoveryIncidentPolicy
import com.no5.helm.watchdog.RecoveryIncidentPolicy.Decision.*
import org.junit.Assert.*
import org.junit.Test
class RecoveryIncidentPolicyTest {
 @Test fun healthyNeverRequestsWork(){val p=RecoveryIncidentPolicy();repeat(50){assertEquals(NONE,p.observe(false,false))}}
 @Test fun firstGpuAttemptDoesNotPollProofAfterFailedAdmission(){val p=RecoveryIncidentPolicy();assertEquals(REPAIR_GPU,p.observe(true,false));repeat(20){assertEquals(NONE,p.observe(true,false))}}
 @Test fun firstRxAttemptDoesNotPollProofAfterFailedAdmission(){val p=RecoveryIncidentPolicy();assertEquals(REPAIR_RX,p.observe(false,true));assertEquals(NONE,p.observe(false,true))}
 @Test fun simultaneousFaultRepairsGpuBeforeIndependentRx(){val p=RecoveryIncidentPolicy();assertEquals(REPAIR_GPU,p.observe(true,true));assertEquals(REPAIR_RX,p.observe(false,true))}
 @Test fun secondGpuFaultAfterConsumedRepairGetsOneLastResort(){val p=RecoveryIncidentPolicy();p.observe(true,false);p.synchronizeConsumedWindowMask(2);assertEquals(RESTART,p.observe(true,false));assertTrue(p.claimLastResort());assertEquals(NONE,p.observe(true,false));assertFalse(p.claimLastResort())}
 @Test fun secondRxFaultAfterConsumedRepairGetsOneLastResort(){val p=RecoveryIncidentPolicy();p.synchronizeConsumedWindowMask(4);assertEquals(RESTART,p.observe(false,true));assertTrue(p.claimLastResort());assertEquals(NONE,p.observe(false,true))}
 @Test fun failedComponentDeadlineCannotRepeatLastResortProof(){val p=RecoveryIncidentPolicy();assertTrue(p.claimLastResort());assertFalse(p.claimLastResort())}
 @Test fun lifecycleCancellationOnlyRearmsUnconsumedAttempt(){val p=RecoveryIncidentPolicy();p.observe(true,false);p.cancelUnconsumedPending();assertEquals(REPAIR_GPU,p.observe(true,false));p.synchronizeConsumedWindowMask(2);p.cancelUnconsumedPending();assertEquals(RESTART,p.observe(true,false))}
 @Test fun realWindowRepairCanRetirePreActionComponentSuspicion(){val p=RecoveryIncidentPolicy();p.observe(true,false);p.windowRepaired();assertEquals(REPAIR_GPU,p.observe(true,false));p.synchronizeConsumedWindowMask(2);p.windowRepaired();assertEquals(RESTART,p.observe(true,false))}
 @Test fun restoredWindowBudgetCannotBeMistakenForAnUnusedComponent(){val p=RecoveryIncidentPolicy();p.synchronizeConsumedWindowMask(2);assertEquals(RESTART,p.observe(true,false));assertTrue(p.claimLastResort());p.cancelUnconsumedPending();assertEquals(NONE,p.observe(true,false))}
}
