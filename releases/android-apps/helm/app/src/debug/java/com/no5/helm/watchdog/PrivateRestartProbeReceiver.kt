package com.no5.helm.watchdog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import java.io.File
import org.json.JSONObject
import no5.sonar.windowrecovery.RecoveryBudgetPolicy

/** DEBUG ONLY native process-entry fixture. No Activity, Window, source or display is created. */
class PrivateRestartProbeReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent){
        require(context.packageName=="com.no5.helm.sonarfixture")
        val root=File(context.filesDir,"sonar-private-proof").apply{mkdirs()}
        val plan=JSONObject(File(root,"receiver-restart-plan.json").readText())
        require(intent.getStringExtra("nonce")==plan.getString("nonce"))
        val identity=OwnProcessIdentity.captureNamed(Process.myPid(),context.packageName+":sonar_restart_probe")
        val record=JSONObject().put("pid",identity.pid).put("start_ticks",identity.startTicks).put("argv_sha256",identity.argvSha)
            .put("uid",identity.uid).put("exe",identity.exe).put("nonce",plan.getString("nonce"))
            .put("appRestartConsumed",RecoveryBudgetPolicy(RecoveryLedger(context,"private-receiver-restart")).snapshot().appRestartConsumed)
        val output=File(root,"receiver-restart-events.jsonl");require(!output.exists()||output.length()<2048)
        output.appendText(record.toString()+"\n")
    }
}
