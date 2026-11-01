package com.no5.helm

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.watchdog.*
import no5.sonar.windowrecovery.RecoveryBudgetPolicy
import no5.sonar.windowrecovery.RecoveryBudgetPolicy.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real own-UID child process + public one-shot Alarm restart on a PRIVATE display.
 * The parent instrumentation/display stay alive. Production HELM is never targeted.
 * Admission evidence is explicit synthetic fixture evidence; this proves platform
 * mechanics and persistent budget, not recovery of a live Sonar source/old incident.
 */
@RunWith(AndroidJUnit4::class)
class OwnRestartMechanismDeviceTest {
    @Test fun oneBehindTaskChildRestartPersistsBudgetAcrossRealProcessDeath() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName=="com.no5.helm.sonarfixture"&&Build.VERSION.SDK_INT==29)
        val folder=File(context.filesDir,"sonar-private-proof").apply{mkdirs()}
        val events=File(folder,"restart-events.jsonl");require(!events.exists())
        val drain=HandlerThread("sonar-private-restart-output").apply{start()}
        val reader=ImageReader.newInstance(128,128,PixelFormat.RGBA_8888,2).apply{setOnImageAvailableListener({it.acquireLatestImage()?.close()},Handler(drain.looper))}
        val display=(context.getSystemService(Context.DISPLAY_SERVICE)as DisplayManager).createVirtualDisplay("No5 private restart mechanic",128,128,160,reader.surface,DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        val target=ComponentName(context.packageName,"com.no5.helm.watchdog.PrivateRestartProbeActivity")
        val report=JSONObject().put("qualifying_seconds",0).put("synthetic_admission_fixture",true).put("production_HELM_or_source_signals",0)
        try{
            val nonce=UUID.randomUUID().toString();File(folder,"restart-plan.json").writeText(JSONObject().put("nonce",nonce).put("display_id",display.display.displayId).toString())
            val policy=RecoveryBudgetPolicy(RecoveryLedger(context,"private-restart-native"))
            val options=ActivityOptions.makeTaskLaunchBehind().setLaunchDisplayId(display.display.displayId).toBundle()
            val flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            val focused=File(folder,"restart-probe-focused.txt")
            context.startActivity(Intent().setComponent(target).addFlags(flags),options)
            fun records():List<JSONObject> = if(!events.exists())emptyList()else events.readLines().filter{it.isNotBlank()}.map(::JSONObject)
            fun waitCount(count:Int,observationTimeoutMs:Long=2000){val end=SystemClock.elapsedRealtime()+observationTimeoutMs;while(records().size<count&&SystemClock.elapsedRealtime()<end)Thread.sleep(20);assertEquals("bounded private restart event count",count,records().size)}
            waitCount(1);assertFalse("private non-focusable fixture must not take focus",focused.exists());val old=records().single();assertEquals(nonce,old.getString("nonce"));assertFalse(old.getBoolean("restart_budget_consumed"))
            // The external owner verifies the protected foreground/resumed app
            // after this behind-task capability stage, BEFORE any own signal.
            Thread.sleep(200);assertFalse("behind launch must not take focus",focused.exists())
            File(folder,"restart-stage-ready.json").writeText(JSONObject().put("nonce",nonce).put("pid",old.getInt("pid")).toString())
            val allow=File(folder,"restart-stage-allow.json");val allowDeadline=SystemClock.elapsedRealtime()+2000
            while(!allow.exists()&&SystemClock.elapsedRealtime()<allowDeadline)Thread.sleep(20)
            assertTrue("fresh external foreground protection check required before signal",allow.exists())
            assertEquals(nonce,JSONObject(allow.readText()).getString("nonce"))
            val native=OwnProcessIdentity.captureNamed(old.getInt("pid"),context.packageName+":sonar_restart_probe")
            assertEquals(old.getLong("start_ticks"),native.startTicks);assertEquals(old.getString("argv_sha256"),native.argvSha);assertEquals(old.getString("exe"),native.exe)
            fun evidence(fault:Fault)=Evidence("private-restart-window","synthetic-private-source",true,true,true,true,0,SystemClock.elapsedRealtimeNanos(),fault)
            assertTrue(policy.consume(Action.VIEW_REBUILD,evidence(Fault.OWN_GPU_FAILED),"private-restart-window","synthetic-private-source",SystemClock.elapsedRealtimeNanos()))
            val admitted=evidence(Fault.COMPONENT_RECOVERY_FAILED)
            assertTrue(policy.consume(Action.APP_RESTART,admitted,"private-restart-window","synthetic-private-source",SystemClock.elapsedRealtimeNanos()))
            assertTrue("budget must be on disk BEFORE native signal",RecoveryBudgetPolicy(RecoveryLedger(context,"private-restart-native")).snapshot().appRestartConsumed)
            assertTrue(OwnActivityRestart.armAndSignal(context,target,native,options,flags){
                display.display.isValid&&RecoveryBudgetPolicy.fresh(admitted,SystemClock.elapsedRealtimeNanos())
            })
            waitCount(2,8000);Thread.sleep(200);assertFalse("restart must not take focus from the protected foreground",focused.exists());val next=records().last();assertEquals(nonce,next.getString("nonce"));assertEquals(display.display.displayId,next.getInt("display_id"))
            val actual=OwnProcessIdentity.captureNamed(next.getInt("pid"),context.packageName+":sonar_restart_probe")
            assertNotEquals("new native process identity",native,actual);assertTrue(next.getBoolean("restart_budget_consumed"))
            assertFalse(policy.consume(Action.APP_RESTART,evidence(Fault.COMPONENT_RECOVERY_FAILED),"private-restart-window","synthetic-private-source",SystemClock.elapsedRealtimeNanos()))
            report.put("passed",true).put("old_private_pid",native.pid).put("new_private_pid",actual.pid)
                .put("new_process_loaded_consumed_budget",true).put("second_restart_denied",true).put("private_display_id",display.display.displayId).put("private_task_never_focused",true).put("restart_observation_limit_ms",8000).put("data_freshness_limit_ms",2000)
        }finally{
            val intent=Intent().setComponent(target).setAction(context.packageName+".SONAR_RECOVERY_ONCE")
            android.app.PendingIntent.getActivity(context,0x534f4e,intent,android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE)?.let{
                (context.getSystemService(Context.ALARM_SERVICE)as android.app.AlarmManager).cancel(it);it.cancel()
            }
            val manager=context.getSystemService(Context.ACTIVITY_SERVICE)as ActivityManager
            manager.appTasks.filter{it.taskInfo.baseActivity==target}.forEach{it.finishAndRemoveTask()}
            display.release();reader.close();drain.quitSafely();drain.join(1000)
            File(folder,"own-restart-mechanism-behind2.json").writeText(report.toString(2))
        }
    }
}
