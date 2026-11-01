package com.no5.helm
import no5.sonar.windowrecovery.RecoveryBudgetPolicy
import no5.sonar.windowrecovery.RecoveryBudgetPolicy.*
import org.junit.Assert.*
import org.junit.Test

class RecoveryBudgetPolicyTest {
    private class MemoryStore:Store {
        var state=State("",0,false);var writes=0;var fail=false
        override fun load()=state
        override fun save(value:State){writes++;if(fail)error("persist failed");state=value}
    }
    private fun evidence(fault:Fault=Fault.MISSING_WINDOW_SURFACE,age:Long=100,observed:Long=1_000_000_000,
        own:Boolean=true,visible:Boolean=true,unfrozen:Boolean=true,baseline:Boolean=true,window:String="window",source:String="source")=
        Evidence(window,source,own,visible,unfrozen,baseline,age,observed,fault)
    @Test fun twoSecondLimitIncludesAllIpcAndQueueTimeRoundedUp() {
        val e=evidence(age=1500)
        assertTrue(RecoveryBudgetPolicy.fresh(e,1_500_000_000))
        assertFalse(RecoveryBudgetPolicy.fresh(e,1_500_000_001))
        assertFalse(RecoveryBudgetPolicy.fresh(e,999_999_999))
        assertFalse(RecoveryBudgetPolicy.fresh(evidence(age=-1),1_000_000_000))
        assertFalse(RecoveryBudgetPolicy.fresh(evidence(observed=-1),Long.MAX_VALUE))
    }
    @Test fun pausedForeignUnprovenAndChangedContextsNeverConsumeBudget() {
        val store=MemoryStore();val policy=RecoveryBudgetPolicy(store)
        for(e in listOf(evidence(own=false),evidence(visible=false),evidence(unfrozen=false),evidence(baseline=false),
            evidence(window="foreign"),evidence(source="other"),evidence(age=2001)))
            assertFalse(policy.consume(Action.WINDOW_RELAYOUT,e,"window","source",1_000_000_000))
        assertEquals(0,store.writes)
    }
    @Test fun successfulConsumptionSurvivesProcessAndWindowRestart() {
        val store=MemoryStore();var policy=RecoveryBudgetPolicy(store)
        assertTrue(policy.consume(Action.WINDOW_RELAYOUT,evidence(),"window","source",1_000_000_000))
        policy=RecoveryBudgetPolicy(store)
        assertFalse(policy.consume(Action.WINDOW_RELAYOUT,evidence(),"window","source",1_000_000_000))
        assertTrue(policy.consume(Action.APP_RESTART,evidence(Fault.COMPONENT_RECOVERY_FAILED),"window","source",1_000_000_000))
        policy=RecoveryBudgetPolicy(store)
        assertTrue(policy.consume(Action.WINDOW_RELAYOUT,evidence(window="new"),"new","source",1_000_000_000))
        assertFalse(policy.consume(Action.APP_RESTART,evidence(Fault.COMPONENT_RECOVERY_FAILED,window="new"),"new","source",1_000_000_000))
    }
    @Test fun wrongComponentAndAppRestartWithoutPrecedingAttemptReject() {
        val policy=RecoveryBudgetPolicy(MemoryStore())
        assertFalse(policy.consume(Action.VIEW_REBUILD,evidence(),"window","source",1_000_000_000))
        assertFalse(policy.consume(Action.RX_RECONNECT,evidence(),"window","source",1_000_000_000))
        assertFalse(policy.consume(Action.APP_RESTART,evidence(Fault.COMPONENT_RECOVERY_FAILED),"window","source",1_000_000_000))
    }
    @Test fun failedPersistenceNeverGrantsPermitAndNeverPretendsToSave() {
        val store=MemoryStore();val policy=RecoveryBudgetPolicy(store);store.fail=true
        try{policy.consume(Action.WINDOW_RELAYOUT,evidence(),"window","source",1_000_000_000);fail()}catch(expected:IllegalStateException){}
        assertEquals(0,policy.snapshot().windowMask);assertEquals(1,store.writes)
    }
}
