package com.no5.helm.watchdog

import android.content.Context
import android.util.AtomicFile
import no5.sonar.windowrecovery.RecoveryBudgetPolicy
import java.io.File
import java.io.IOException

/** Single writer, bounded durable ledger. No automatic budget reset on process/version changes. */
internal class RecoveryLedger(context:Context,namespace:String="sonar-recovery"):RecoveryBudgetPolicy.Store {
    init{require(namespace.matches(Regex("[a-z0-9-]{1,64}")))}
    private val marker=context.getSharedPreferences(if(namespace=="sonar-recovery")"sonar_recovery_initialization"else namespace+"-initialization",Context.MODE_PRIVATE)
    private val file=AtomicFile(File(context.noBackupFilesDir,"$namespace-budget-v1"))
    override fun load():RecoveryBudgetPolicy.State {
        if(!marker.getBoolean("initialized",false)) {
            // Mark first: a crash or failed fsync fails closed on the next start.
            check(!file.baseFile.exists()){"ORPHAN_RECOVERY_LEDGER"}
            check(marker.edit().putBoolean("initialized",true).commit()){"RECOVERY_INITIALIZATION_FAILED"}
            save(RecoveryBudgetPolicy.State("",0,false))
        }
        val input=file.openRead()
        val bytes=input.use { stream->
            val limit=ByteArray(4097);var length=0
            while(length<limit.size){val n=stream.read(limit,length,limit.size-length);if(n<0)break;length+=n}
            limit.copyOf(length)
        }
        require(bytes.size<=4096){"RECOVERY_LEDGER_OVERSIZE"}
        val fields=bytes.toString(Charsets.UTF_8).split('\n')
        require(fields.size==5&&fields[0]=="no5-recovery/1"&&fields[4].isEmpty()){"RECOVERY_LEDGER_INVALID"}
        require(fields[3]=="0"||fields[3]=="1")
        return RecoveryBudgetPolicy.State(fields[1],fields[2].toInt(),fields[3]=="1")
    }
    override fun save(value:RecoveryBudgetPolicy.State) {
        require('\n' !in value.window&&'\r' !in value.window)
        val bytes="no5-recovery/1\n${value.window}\n${value.windowMask}\n${if(value.appRestartConsumed)1 else 0}\n".toByteArray()
        require(bytes.size<=4096)
        val output=file.startWrite()
        try { output.write(bytes);output.fd.sync();file.finishWrite(output) }
        catch(failure:Exception){file.failWrite(output);throw IOException("RECOVERY_BUDGET_PERSIST_FAILED",failure)}
    }
}
