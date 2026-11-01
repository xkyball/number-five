package com.no5.helm.watchdog

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.view.View
import android.view.WindowManager
import android.graphics.Canvas
import android.graphics.Color
import org.json.JSONObject
import java.io.File

/** DEBUG ONLY, separate private test Activity/process. Never starts the HELM root/source. */
class PrivateRestartProbeActivity:Activity() {
    override fun onCreate(state:Bundle?){
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        super.onCreate(state)
        require(packageName=="com.no5.helm.sonarfixture")
        val root=File(filesDir,"sonar-private-proof");val plan=JSONObject(File(root,"restart-plan.json").readText())
        require(windowManager.defaultDisplay.displayId==plan.getInt("display_id")&&windowManager.defaultDisplay.displayId!=0)
        setContentView(object:View(this){override fun onDraw(canvas:Canvas){canvas.drawColor(Color.GREEN)}})
        val identity=OwnProcessIdentity.captureNamed(Process.myPid(),packageName+":sonar_restart_probe")
        val record=JSONObject().put("pid",identity.pid).put("start_ticks",identity.startTicks).put("argv_sha256",identity.argvSha)
            .put("exe",identity.exe).put("uid",identity.uid).put("display_id",windowManager.defaultDisplay.displayId).put("nonce",plan.getString("nonce")).put("restart_budget_consumed",no5.sonar.windowrecovery.RecoveryBudgetPolicy(RecoveryLedger(this,"private-restart-native")).snapshot().appRestartConsumed)
        val log=File(root,"restart-events.jsonl");require(!log.exists()||log.length()<2048)
        log.appendText(record.toString()+"\n")
    }
    override fun onResume(){
        super.onResume()
        File(File(filesDir,"sonar-private-proof"),"restart-probe-resumed.txt").appendText("${Process.myPid()}\n")
    }

    override fun onWindowFocusChanged(focused:Boolean){
        super.onWindowFocusChanged(focused)
        if(focused)File(File(filesDir,"sonar-private-proof"),"restart-probe-focused.txt").appendText("${Process.myPid()}\n")
    }

}
