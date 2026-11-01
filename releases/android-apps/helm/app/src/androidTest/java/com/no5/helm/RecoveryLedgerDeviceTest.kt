package com.no5.helm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.watchdog.RecoveryLedger
import no5.sonar.windowrecovery.RecoveryBudgetPolicy
import no5.sonar.windowrecovery.RecoveryBudgetPolicy.*
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Actual Android AtomicFile/fsync/reload, no source/Activity/process signal. */
@RunWith(AndroidJUnit4::class)
class RecoveryLedgerDeviceTest {
    @Test fun durableConsumeReloadAndCorruptionFailClosed() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName=="com.no5.helm.sonarfixture")
        val ledger=RecoveryLedger(context);var policy=RecoveryBudgetPolicy(ledger)
        fun evidence(fault:Fault)=Evidence("private-window","private-source",true,true,true,true,0,SystemClock.elapsedRealtimeNanos(),fault)
        assertTrue(policy.consume(Action.WINDOW_RELAYOUT,evidence(Fault.MISSING_WINDOW_SURFACE),"private-window","private-source",SystemClock.elapsedRealtimeNanos()))
        policy=RecoveryBudgetPolicy(RecoveryLedger(context))
        assertFalse(policy.consume(Action.WINDOW_RELAYOUT,evidence(Fault.MISSING_WINDOW_SURFACE),"private-window","private-source",SystemClock.elapsedRealtimeNanos()))
        assertTrue(policy.consume(Action.APP_RESTART,evidence(Fault.COMPONENT_RECOVERY_FAILED),"private-window","private-source",SystemClock.elapsedRealtimeNanos()))
        policy=RecoveryBudgetPolicy(RecoveryLedger(context));assertTrue(policy.snapshot().appRestartConsumed)
        assertFalse(policy.consume(Action.APP_RESTART,evidence(Fault.COMPONENT_RECOVERY_FAILED),"private-window","private-source",SystemClock.elapsedRealtimeNanos()))
        val file=File(context.noBackupFilesDir,"sonar-recovery-budget-v1");val saved=file.readBytes()
        try{
            file.writeText("corrupt")
            try{RecoveryBudgetPolicy(RecoveryLedger(context));fail("corrupt ledger was accepted")}catch(expected:IllegalArgumentException){}
            check(file.delete())
            try{RecoveryBudgetPolicy(RecoveryLedger(context));fail("missing initialized ledger was silently reset")}catch(expected:java.io.FileNotFoundException){}
        }finally{file.writeBytes(saved)}
        val result=JSONObject().put("atomic_reload_preserved_app_budget",true).put("duplicate_actions_denied",true)
            .put("corrupt_and_missing_initialized_ledger_fail_closed",true).put("process_signals_sent",0).put("qualifying_seconds",0)
        val folder=File(context.filesDir,"sonar-private-proof");folder.mkdirs();File(folder,"recovery-ledger-native1.json").writeText(result.toString(2))
    }
}
