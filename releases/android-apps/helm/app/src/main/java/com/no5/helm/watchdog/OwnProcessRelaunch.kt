package com.no5.helm.watchdog

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Process
import android.os.SystemClock

/** Exact own native process only. Reentry construction is separate from signal/one-shot alarm.
 * Android may defer an alarm for its minimum futurity. That NEVER extends source admission.
 * The durable APP_RESTART token must already be consumed before entering this primitive.
 */
internal object OwnProcessRelaunch {
    fun armAndSignal(context:Context,expected:OwnProcessIdentity,expectedCommand:String,
                     reentry:()->PendingIntent,stillAdmitted:()->Boolean):Boolean {
        require(expected.uid==Process.myUid())
        fun same()=try{expected==OwnProcessIdentity.captureNamed(expected.pid,expectedCommand)}catch(_:Exception){false}
        if(!stillAdmitted()||!same())return false
        val alarm=reentry();val manager=context.getSystemService(Context.ALARM_SERVICE)as AlarmManager
        try{manager.setExact(AlarmManager.ELAPSED_REALTIME,SystemClock.elapsedRealtime()+300,alarm)}
        catch(error:Exception){alarm.cancel();throw error}
        if(!stillAdmitted()||!same()){manager.cancel(alarm);alarm.cancel();return false}
        Process.killProcess(expected.pid)
        return true
    }
}
