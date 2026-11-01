package com.no5.helm.watchdog

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.system.Os
import java.io.File
import java.security.MessageDigest

/** Public own-UID /proc reads only. Any unreadable field rejects a last-resort signal. */
internal data class OwnProcessIdentity(val pid:Int,val uid:Int,val startTicks:Long,val argvSha:String,val exe:String) {
    companion object {
        fun capture(context:Context):OwnProcessIdentity {
            return captureNamed(Process.myPid(),context.packageName)
        }
        fun captureNamed(pid:Int,expectedCommand:String):OwnProcessIdentity {
            require(pid>0&&expectedCommand.isNotBlank()&&expectedCommand.length<=256)
            val uid=Os.stat("/proc/$pid").st_uid;require(uid==Process.myUid()){"foreign UID denied"}
            val stat=File("/proc/$pid/stat").readText()
            val fields=stat.substring(stat.lastIndexOf(')')+2).split(' ')
            val argv=File("/proc/$pid/cmdline").readBytes()
            require(argv.size in 1..4096&&argv.toString(Charsets.UTF_8).substringBefore('\u0000')==expectedCommand)
            val exe=Os.readlink("/proc/$pid/exe")
            require(exe=="/system/bin/app_process64"||exe=="/system/bin/app_process32")
            val digest=MessageDigest.getInstance("SHA-256").digest(argv).joinToString(""){"%02x".format(it.toInt()and 255)}
            val after=File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19].toLong()
            require(after==fields[19].toLong()){"process changed during identity read"}
            return OwnProcessIdentity(pid,uid,after,digest,exe)
        }
    }
    fun stillOwnFocused(activity:Activity):Boolean=try {
        val process=ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        this==capture(activity)&&process.pid==pid&&process.uid==Process.myUid()&&
            process.importance==ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND&&
            !activity.isFinishing&&!activity.isDestroyed&&activity.hasWindowFocus()&&
            activity.window.decorView.isShown&&activity.window.decorView.isAttachedToWindow
    }catch(_:Exception){false}
}
