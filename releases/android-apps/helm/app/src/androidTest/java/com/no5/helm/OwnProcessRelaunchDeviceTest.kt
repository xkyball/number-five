package com.no5.helm
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.watchdog.*
import no5.sonar.windowrecovery.RecoveryBudgetPolicy
import no5.sonar.windowrecovery.RecoveryBudgetPolicy.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OwnProcessRelaunchDeviceTest {
    @Test fun exactOwnProcessDeathOneShotReentryAndDurableBudgetWithoutActivity() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName=="com.no5.helm.sonarfixture")
        val root=File(context.filesDir,"sonar-private-proof").apply{mkdirs()}
        val output=File(root,"receiver-restart-events.jsonl");require(!output.exists())
        val nonce=UUID.randomUUID().toString()
        File(root,"receiver-restart-plan.json").writeText(JSONObject().put("nonce",nonce).toString())
        val target=ComponentName(context.packageName,"com.no5.helm.watchdog.PrivateRestartProbeReceiver")
        val info=context.packageManager.getReceiverInfo(target,0)
        assertEquals(android.os.Process.myUid(),info.applicationInfo.uid)
        val intent=Intent().setComponent(target).setAction(context.packageName+".SONAR_PRIVATE_REENTRY").putExtra("nonce",nonce)
        val report=JSONObject().put("qualification_seconds",0).put("synthetic_admission_fixture",true)
            .put("Activity_Window_Display_started",false).put("Activity_reopen_NOT_RUN",true).put("hard_source_age_ms",2000)
        var alarm:PendingIntent?=null
        fun records()=if(output.exists())output.readLines().filter{it.isNotBlank()}.map(::JSONObject)else emptyList()
        fun awaitCount(n:Int,limit:Long){val end=SystemClock.elapsedRealtime()+limit;while(records().size<n&&SystemClock.elapsedRealtime()<end)Thread.sleep(20);assertEquals(n,records().size)}
        try {
            context.sendBroadcast(intent);awaitCount(1,2000)
            val old=records().single();val identity=OwnProcessIdentity.captureNamed(old.getInt("pid"),info.processName)
            assertEquals(old.getLong("start_ticks"),identity.startTicks);assertEquals(old.getString("argv_sha256"),identity.argvSha);assertFalse(old.getBoolean("appRestartConsumed"))
            val policy=RecoveryBudgetPolicy(RecoveryLedger(context,"private-receiver-restart"))
            fun evidence(fault:Fault)=Evidence("private-reentry-window","synthetic-private-source",true,true,true,true,0,SystemClock.elapsedRealtimeNanos(),fault)
            assertTrue(policy.consume(Action.VIEW_REBUILD,evidence(Fault.OWN_GPU_FAILED),"private-reentry-window","synthetic-private-source",SystemClock.elapsedRealtimeNanos()))
            val admitted=evidence(Fault.COMPONENT_RECOVERY_FAILED)
            assertTrue(policy.consume(Action.APP_RESTART,admitted,"private-reentry-window","synthetic-private-source",SystemClock.elapsedRealtimeNanos()))
            assertTrue(RecoveryBudgetPolicy(RecoveryLedger(context,"private-receiver-restart")).snapshot().appRestartConsumed)
            assertTrue(OwnProcessRelaunch.armAndSignal(context,identity,info.processName,reentry={
                PendingIntent.getBroadcast(context,0x534f51,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE).also{alarm=it}
            }){RecoveryBudgetPolicy.fresh(admitted,SystemClock.elapsedRealtimeNanos())})
            awaitCount(2,8000)
            val next=records().last();val actual=OwnProcessIdentity.captureNamed(next.getInt("pid"),info.processName)
            assertNotEquals(identity,actual);assertEquals(nonce,next.getString("nonce"));assertTrue(next.getBoolean("appRestartConsumed"))
            assertFalse(RecoveryBudgetPolicy(RecoveryLedger(context,"private-receiver-restart")).consume(Action.APP_RESTART,evidence(Fault.COMPONENT_RECOVERY_FAILED),"private-reentry-window","synthetic-private-source",SystemClock.elapsedRealtimeNanos()))
            report.put("passed",true).put("old_private_pid",identity.pid).put("new_private_pid",actual.pid).put("budget_consumed_before_signal",true).put("reloaded_budget_consumed",true).put("second_restart_denied",true)
        }finally{alarm?.let{(context.getSystemService(Context.ALARM_SERVICE)as AlarmManager).cancel(it);it.cancel()};File(root,"own-process-reentry1.json").writeText(report.toString(2))}
    }
}
