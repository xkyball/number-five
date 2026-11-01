package com.no5.helm.network

import com.no5.domain.SonarOriginClockProof
import com.no5.domain.SonarOriginClockRequest
import com.no5.domain.SonarOriginClockDiagnostics
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom

/** One queued/in-flight/result handoff on the EXISTING sonar poll worker.
 * No executor, periodic proof poll, timestamp modification or cached proof.
 * The last scalar diagnostic records the rejecting stage, not a usable proof.
 */
internal class OriginClockProofClient(private val http: PiHttpTransport,
    private val nowNanos: () -> Long = android.os.SystemClock::elapsedRealtimeNanos,
    private val contextToken: () -> Any = { Unit },
    private val captureContext: ((SonarOriginClockRequest) -> Any?)? = null,
    private val onReady: () -> Unit = {},
    private val contextValid: (SonarOriginClockRequest) -> Boolean) {
    private data class Pending(val request: SonarOriginClockRequest, val nonce: String,
        val start: Long, val fence: Any, val callback: (SonarOriginClockProof?) -> Unit,
        val sequence:Long, var sent: Boolean = false, var complete: Boolean = false,
        var proof: SonarOriginClockProof? = null, var phase:String="QUEUED",
        var pumpStarted:Long?=null, var readFinished:Long?=null,
        var sampleMinusOriginalMs:Long?=null, var responseBytes:Int=0,
        var httpFailureStatus:Int?=null, var exceptionType:String="NONE", val recoveryInput:Boolean=false)
    private val lock = Any()
    private var closed = false
    private var pending: Pending? = null
    private var sequence=0L
    @Volatile var diagnostics=SonarOriginClockDiagnostics();private set
    private fun interval(end:Long?,start:Long?):Long? = try {
        if(end==null||start==null||end<start)null else Math.subtractExact(end,start)
    }catch(_:ArithmeticException){null}
    private fun record(item:Pending,state:String,phase:String,at:Long) = synchronized(lock) {
        // A finished callback cannot replace diagnostics of a newer request.
        if(diagnostics.sequence!=item.sequence)return@synchronized
        diagnostics=SonarOriginClockDiagnostics(item.sequence,state,phase,
            interval(item.pumpStarted,item.start),interval(item.readFinished,item.pumpStarted),
            if(item.complete)interval(at,item.readFinished)else null,interval(at,item.start),
            item.sampleMinusOriginalMs,item.responseBytes,item.httpFailureStatus,item.exceptionType)
    }
    private fun fence(value:SonarOriginClockRequest):Any? = captureContext?.invoke(value) ?:
        if(captureContext==null && contextValid(value)) contextToken() else null
    fun request(value: SonarOriginClockRequest, callback: (SonarOriginClockProof?) -> Unit): Boolean {
        return requestFrom({value},callback)
    }
    /** Selection/draining is part of the original enqueue bracket, never a new deadline. */
    fun requestFrom(select: () -> SonarOriginClockRequest?, callback: (SonarOriginClockProof?) -> Unit): Boolean {
        return enqueue(select,callback,false)
    }
    fun requestRecoveryFrom(select: () -> SonarOriginClockRequest?, callback: (SonarOriginClockProof?) -> Unit): Boolean {
        return enqueue(select,callback,true)
    }
    private fun enqueue(select:()->SonarOriginClockRequest?,callback:(SonarOriginClockProof?)->Unit,recoveryInput:Boolean):Boolean {
        val enqueued=nowNanos()
        if(synchronized(lock){closed||pending!=null})return false
        val value=select()?:return false
        val fence=fence(value)?:return false
        return synchronized(lock) {
            if (closed || pending != null) return@synchronized false
            if(sequence==Long.MAX_VALUE)return@synchronized false
            val nonce = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            sequence++
            pending = Pending(value, nonce, enqueued, fence, callback,sequence,recoveryInput=recoveryInput)
            diagnostics=SonarOriginClockDiagnostics(sequence,"QUEUED","QUEUED")
            true
        }
    }
    fun pump() {
        val item = synchronized(lock) { pending?.takeIf { !closed && !it.sent }?.also { it.sent = true } } ?: return
        item.pumpStarted=nowNanos()
        val proof = try {
            item.phase="CONTEXT_BEFORE_READ";require(fence(item.request)==item.fence)
            item.phase="QUEUE_DEADLINE";require(nowNanos() >= item.start && nowNanos() - item.start <= 2_000_000_000L)
            val r = item.request
            val endpoint=if(item.recoveryInput)"recovery-input-proof"else"origin-clock-proof"
            val path = "/v1/sonar/$endpoint?nonce=${item.nonce}&serverSessionId=${URLEncoder.encode(r.serverSessionId, "UTF-8")}" +
                "&generation=${r.generation}&displayGeneration=${r.displayGeneration}&pingId=${r.pingId}&observedAtEpochMs=${r.originalEpochMs}"
            val remaining=2_000_000_000L-(nowNanos()-item.start)
            require(remaining>=1_000_000L)
            item.phase="HTTP_READ"
            val body = http.getBounded(path,remaining/1_000_000L,2048)
            item.readFinished=nowNanos();item.responseBytes=body.toByteArray(Charsets.UTF_8).size
            item.phase="BODY_BYTES";require(item.responseBytes<=2048)
            item.phase="JSON_PARSE";val root = JSONObject(body)
            fun integer(key: String): Long {
                val value = root.get(key)
                require(value is Long || value is Int)
                return (value as Number).toLong()
            }
            item.phase="SCHEMA_NONCE"
            require(root.getString("schema") == (if(item.recoveryInput)"no5.recovery-input-proof/1"else"no5.origin-clock-proof/1") && root.getString("nonce") == item.nonce)
            item.phase="RESPONSE_CONTEXT"
            require(root.getString("serverSessionId") == r.serverSessionId && integer("generation") == r.generation &&
                integer("displayGeneration") == r.displayGeneration)
            item.phase="ORIGINAL_TIMESTAMP"
            val actualPing=integer("pingId");val actualNs=integer("timeUtcNs")
            require(integer("uncertaintyMs")==1L&&actualNs>0)
            val measured=if(item.recoveryInput){
                item.phase="RECOVERY_INPUT_MINIMUM"
                require(integer("minimumPingId")==r.pingId&&integer("minimumTimeUtcNs")/1_000_000==r.originalEpochMs&&
                    actualPing>=r.pingId&&actualNs>=integer("minimumTimeUtcNs")&&
                    root.getString("actualSource")=="own"&&root.get("validTxReference")==true)
                SonarOriginClockRequest(r.serverSessionId,r.generation,r.displayGeneration,actualPing,actualNs/1_000_000)
            }else{
                require(actualPing==r.pingId&&actualNs/1_000_000==r.originalEpochMs);r
            }
            item.phase="CONTEXT_AFTER_READ";require(fence(r)==item.fence)
            item.phase="ORIGIN_SAMPLE_BOUND"
            val upper=integer("originClockUpperEpochMs")
            item.sampleMinusOriginalMs=try{Math.subtractExact(upper,measured.originalEpochMs)}catch(_:ArithmeticException){null}
            val candidate=SonarOriginClockProof(measured,upper,item.start,nowNanos())
            item.phase="BOUND_AFTER_READ";require(candidate.upperAgeMs(nowNanos()) != null)
            candidate
        } catch (error: Exception) {
            item.exceptionType=error.javaClass.simpleName.take(64)
            item.httpFailureStatus=(error as? PiHttpStatusException)?.statusCode
            null
        }
        val ready = synchronized(lock) {
            if (!closed && pending === item) {
                item.proof = proof;item.complete = true
                record(item,if(proof==null)"REJECTED"else"READY",item.phase,nowNanos())
                true
            } else false
        }
        // Notify only after publishing completion and releasing the client lock.
        // The same consumer applies all queued context events and the age gate.
        if (ready) onReady()
    }
    /** Scheduling failure is a diagnostic rejection, never another request or deadline. */
    fun rejectQueuedWorkerWake() {
        val ready = synchronized(lock) {
            val item = pending?.takeIf { !closed && !it.sent && !it.complete } ?: return@synchronized false
            item.phase="WORKER_WAKE_REJECTED"; item.sent=true; item.complete=true
            record(item,"REJECTED",item.phase,nowNanos()); true
        }
        if (ready) onReady()
    }
    /** Must be called by the source's consumer, not the HTTP worker. */
    fun tick() {
        val item = synchronized(lock) {
            pending?.takeIf { it.complete && !closed }?.also { pending = null }
        } ?: return
        val result=item.proof?.takeIf {
            if(fence(it.request)!=item.fence){item.phase="CONTEXT_AT_CONSUMER";false}
            else{item.phase="BOUND_AT_CONSUMER";it.upperAgeMs(nowNanos())!=null}
        }
        record(item,if(result==null)"REJECTED"else"DELIVERED",item.phase,nowNanos())
        item.callback(result)
    }
    fun close() = synchronized(lock) {
        closed = true;pending = null
        diagnostics=diagnostics.copy(state="CLOSED",phase="CLOSED")
    }
}
