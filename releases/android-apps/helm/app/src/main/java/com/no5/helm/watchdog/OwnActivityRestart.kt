package com.no5.helm.watchdog

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.SystemClock

/** Public platform mechanism, only an exact current native identity of an own-UID Activity process.
 * Caller must durably consume its one restart token and recheck fresh admission before entering.
 * No source/service/VM command, repeating alarm, rollback or implicit retry exists here.
 */
internal object OwnActivityRestart {
    fun armAndSignal(context:Context,target:ComponentName,expected:OwnProcessIdentity,
                     options:Bundle?=null,launchFlags:Int=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,stillAdmitted:()->Boolean):Boolean {
        require(target.packageName==context.packageName)
        val info=context.packageManager.getActivityInfo(target,0)
        require(info.applicationInfo.uid==Process.myUid()&&expected.uid==Process.myUid())
        return OwnProcessRelaunch.armAndSignal(context,expected,info.processName,reentry={
            val intent=Intent().setComponent(target).setAction(context.packageName+".SONAR_RECOVERY_ONCE")
                .addFlags(launchFlags)
            PendingIntent.getActivity(context,0x534f4e,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,options)
        },stillAdmitted=stillAdmitted)
    }
}
